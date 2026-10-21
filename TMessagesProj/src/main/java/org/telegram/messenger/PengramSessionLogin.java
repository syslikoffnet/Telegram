package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Pengram: вход в аккаунт по файлу сессии.
 *
 * <p>Два режима — оба дают полноценный вход, а не «полусессию»:
 *
 * <ul>
 *   <li><b>Безопасный</b> (по умолчанию). Приложение просит у сервера
 *       одноразовый login-token, уже авторизованная сессия из файла его
 *       подтверждает ({@code auth.acceptLoginToken}), после чего приложение
 *       получает обычную авторизацию. Старый ключ остаётся только у старого
 *       клиента, дублирования ключа нет — и сессию не выкинет с
 *       {@code AUTH_KEY_DUPLICATED}.</li>
 *   <li><b>Мгновенный</b>. Ключ из файла ставится прямо в это устройство:
 *       вход виден сразу, без второго участника. Быстрее, но тот же ключ
 *       остаётся и в исходном клиенте: если он ещё жив, Telegram может
 *       посчитать это дубликатом. Поэтому это осознанный выбор в диалоге.</li>
 * </ul>
 *
 * <p>Пароль двухфакторной защиты в безопасном режиме спрашивает та же страница,
 * что и при обычном входе; в мгновенном его не будет — ключ уже прошёл проверку
 * на исходном устройстве.
 */
public final class PengramSessionLogin {

    public static final String MODE_SAFE = "safe";
    public static final String MODE_INSTANT = "instant";

    /** без логина, но с правом неавторизованного запроса и внятной ошибкой */
    private static final int FLAGS = ConnectionsManager.RequestFlagWithoutLogin
            | ConnectionsManager.RequestFlagFailOnServerErrors
            | ConnectionsManager.RequestFlagEnableUnauthorized;

    /** сколько раз ждём, пока сервер отдаст готовую авторизацию */
    private static final int MAX_ROUNDS = 5;
    /** пауза между экспортом токена: серверу нужно время применить подтверждение */
    private static final long RETRY_DELAY = 700L;
    /** сколько раз сервер может переслать токен на другой DC */
    private static final int MAX_MIGRATIONS = 3;

    public interface Listener {
        void onSuccess(TLRPC.TL_auth_authorization authorization);

        /** нужен пароль двухфакторной защиты: показать штатную страницу */
        void onPasswordNeeded(TL_account.Password password);

        /** код ошибки — текст живёт в строках интерфейса */
        void onError(String code);
    }

    /**
     * Состояние одного входа. Отдельный объект, а не статические поля: два
     * параллельных входа (например, пользователь передумал и выбрал другой
     * файл) не должны перемешивать слоты и счётчики.
     */
    private static final class Flow {
        final int account;
        final PengramSessionFormats.Account session;
        final Listener listener;

        int round;
        int migrations;
        int sourceSlot = -1;
        /** токен, который уже отправлен на подтверждение: повторно не шлём */
        byte[] acceptedToken;
        boolean cancelled;

        Flow(int account, PengramSessionFormats.Account session, Listener listener) {
            this.account = account;
            this.session = session;
            this.listener = listener;
        }
    }

    /** вход, который сейчас выполняется: нужен, чтобы его можно было отменить */
    private static Flow active;

    private PengramSessionLogin() {
    }

    /**
     * Идентификатор ключа — младшие 64 бита SHA-1, ровно как в handshake.
     * Считаем здесь, а не в нативе: формулу можно проверить тестом, не поднимая
     * сеть, а ошибка в ней ломает вообще все запросы.
     */
    public static long authKeyId(byte[] authKey) {
        if (authKey == null || authKey.length != 256) {
            return 0;
        }
        try {
            final byte[] sha = MessageDigest.getInstance("SHA-1").digest(authKey);
            long value = 0;
            for (int a = 19; a >= 12; --a) {
                value = (value << 8) | (sha[a] & 0xFF);
            }
            return value;
        } catch (Throwable error) {
            return 0;
        }
    }

    public static void start(final int targetAccount, final PengramSessionFormats.Account session,
                             final String mode, final Listener listener) {
        if (session == null || !session.isUsable() || authKeyId(session.authKey) == 0) {
            listener.onError("invalid_key");
            return;
        }
        cancel();
        final Flow flow = new Flow(targetAccount, session, listener);
        active = flow;
        if (MODE_INSTANT.equals(mode)) {
            instant(flow);
        } else {
            exportToken(flow);
        }
    }

    /**
     * Отмена: закрываем временный слот, если он был занят. Ключ из файла при
     * этом затирается — второй попытке он уже не понадобится, а держать его
     * в памяти незачем.
     */
    public static void cancel() {
        final Flow flow = active;
        active = null;
        if (flow == null) {
            return;
        }
        flow.cancelled = true;
        releaseSource(flow);
        flow.session.clear();
    }

    // ------------------------------------------------------------- мгновенный

    private static void instant(final Flow flow) {
        final ConnectionsManager manager = ConnectionsManager.getInstance(flow.account);
        final PengramSessionFormats.Account session = flow.session;
        manager.importAuthorization(session.dcId, session.authKey, authKeyId(session.authKey));
        if (session.userId > 0) {
            manager.setUserId(session.userId);
        }

        final TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
        request.id = new TLRPC.TL_inputUserSelf();
        manager.sendRequest(request, (response, error) -> {
            if (flow.cancelled) {
                return;
            }
            TLRPC.User user = null;
            if (response instanceof TLRPC.TL_users_userFull) {
                final TLRPC.TL_users_userFull full = (TLRPC.TL_users_userFull) response;
                if (full.full_user != null && full.full_user.user != null) {
                    user = full.full_user.user;
                } else if (full.users != null && !full.users.isEmpty()) {
                    user = full.users.get(0);
                }
            }
            if (user == null) {
                // Ключ не подошёл — не оставляем его в слоте аккаунта.
                manager.cleanup(true);
                fail(flow, mapError(error));
                return;
            }
            final TLRPC.TL_auth_authorization authorization = new TLRPC.TL_auth_authorization();
            authorization.user = user;
            succeed(flow, authorization);
        }, FLAGS);
    }

    // --------------------------------------------------------------- токен

    private static void exportToken(final Flow flow) {
        if (flow.round > MAX_ROUNDS) {
            fail(flow, "login_timeout");
            return;
        }
        final TLRPC.TL_auth_exportLoginToken request = new TLRPC.TL_auth_exportLoginToken();
        request.api_id = BuildVars.APP_ID;
        request.api_hash = BuildVars.APP_HASH;
        ConnectionsManager.getInstance(flow.account).sendRequest(request,
                (response, error) -> handleToken(flow, request, response, error), FLAGS);
    }

    /**
     * Разбор ответа на login-token. Один общий обработчик для экспорта и
     * импорта: сервер может ответить любым из трёх вариантов на оба запроса.
     */
    private static void handleToken(final Flow flow, final TLObject request,
                                    final TLObject response, final TLRPC.TL_error error) {
        if (flow.cancelled) {
            return;
        }
        if (error != null) {
            if ("SESSION_PASSWORD_NEEDED".equals(error.text)) {
                requestPassword(flow);
            } else {
                fail(flow, mapError(error));
            }
            return;
        }
        if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
            final TLRPC.TL_auth_loginTokenSuccess success = (TLRPC.TL_auth_loginTokenSuccess) response;
            if (success.authorization instanceof TLRPC.TL_auth_authorization) {
                succeed(flow, (TLRPC.TL_auth_authorization) success.authorization);
            } else {
                fail(flow, "unexpected_response");
            }
            return;
        }
        if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
            // Токен принадлежит другому DC: туда же должен переехать и аккаунт,
            // иначе ключ окажется на одном DC, а домашним останется другой.
            if (++flow.migrations > MAX_MIGRATIONS) {
                fail(flow, "token_expired");
                return;
            }
            importOnNewDc(flow, (TLRPC.TL_auth_loginTokenMigrateTo) response);
            return;
        }
        if (response instanceof TLRPC.TL_auth_loginToken) {
            final byte[] token = ((TLRPC.TL_auth_loginToken) response).token;
            if (flow.acceptedToken != null && Arrays.equals(flow.acceptedToken, token)) {
                // этот токен уже подтверждён — сервер просто ещё не успел
                // применить подтверждение; ждём, а не дёргаем сессию снова
                retryLater(flow);
                return;
            }
            // Обычный токен: подтверждаем его со стороны уже готовой сессии.
            acceptOnSource(flow, token);
            return;
        }
        fail(flow, "unexpected_response");
    }

    private static void importOnNewDc(final Flow flow, final TLRPC.TL_auth_loginTokenMigrateTo migrate) {
        final ConnectionsManager manager = ConnectionsManager.getInstance(flow.account);
        manager.selectHomeDatacenter(migrate.dc_id);

        final TLRPC.TL_auth_importLoginToken request = new TLRPC.TL_auth_importLoginToken();
        request.token = migrate.token;
        manager.sendRequest(request, (response, error) -> handleToken(flow, request, response, error), FLAGS);
    }

    /** подтверждение на стороне уже авторизованной сессии из файла */
    private static void acceptOnSource(final Flow flow, final byte[] token) {
        final int slot = findSourceSlot(flow.account);
        if (slot < 0) {
            fail(flow, "no_slot");
            return;
        }
        flow.sourceSlot = slot;
        flow.acceptedToken = token;
        final ConnectionsManager source = ConnectionsManager.getInstance(slot);
        source.importAuthorization(flow.session.dcId, flow.session.authKey, authKeyId(flow.session.authKey));
        if (flow.session.userId > 0) {
            source.setUserId(flow.session.userId);
        }

        final TLRPC.TL_auth_acceptLoginToken accept = new TLRPC.TL_auth_acceptLoginToken();
        accept.token = token;
        source.sendRequest(accept, (response, error) -> {
            if (flow.cancelled) {
                releaseSource(flow);
                return;
            }
            // auth.acceptLoginToken возвращает авторизацию самой сессии-источника:
            // это и есть подтверждение, отдельного Bool тут нет
            final boolean accepted = response instanceof TLRPC.auth_Authorization
                    || (error != null && error.text != null && error.text.contains("ALREADY_ACCEPTED"));
            // Ключ источника больше не нужен: закрываем слот до повторного
            // экспорта, иначе в памяти живут два одинаковых ключа.
            releaseSource(flow);
            if (!accepted) {
                // токен не приняли: следующий круг возьмёт актуальный
                flow.acceptedToken = null;
                fail(flow, mapError(error));
                return;
            }
            retryLater(flow);
        }, FLAGS);
    }

    private static void retryLater(final Flow flow) {
        flow.round++;
        AndroidUtilities.runOnUIThread(() -> {
            if (!flow.cancelled && active == flow) {
                exportToken(flow);
            }
        }, RETRY_DELAY);
    }

    private static void requestPassword(final Flow flow) {
        final TL_account.getPassword request = new TL_account.getPassword();
        ConnectionsManager.getInstance(flow.account).sendRequest(request, (response, error) -> {
            if (flow.cancelled) {
                return;
            }
            if (response instanceof TL_account.Password) {
                final TL_account.Password password = (TL_account.Password) response;
                active = null;
                flow.session.clear();
                flow.listener.onPasswordNeeded(password);
            } else {
                fail(flow, mapError(error));
            }
        }, FLAGS);
    }

    // ------------------------------------------------------------- служебное

    private static void succeed(Flow flow, TLRPC.TL_auth_authorization authorization) {
        active = null;
        flow.session.clear();
        flow.listener.onSuccess(authorization);
    }

    private static void fail(Flow flow, String code) {
        if (active == flow) {
            active = null;
        }
        flow.session.clear();
        flow.listener.onError(code);
    }

    /**
     * Свободный слот для временного соединения. Активные аккаунты не трогаем:
     * подменить чужой ключ было бы катастрофой.
     */
    private static int findSourceSlot(int targetAccount) {
        for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; --a) {
            if (isReusableSlot(a, targetAccount)) {
                return a;
            }
        }
        return -1;
    }

    private static void releaseSource(Flow flow) {
        final int slot = flow.sourceSlot;
        flow.sourceSlot = -1;
        if (slot < 0) {
            return;
        }
        try {
            // cleanup(true) стирает и ключ, и временный userId слота
            ConnectionsManager.getInstance(slot).cleanup(true);
        } catch (Throwable ignore) {
        }
    }

    /** коды ошибок: тексты — в строках интерфейса, ни один ответ сервера не утекает в лог */
    static String mapError(TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return "network_error";
        }
        final String text = error.text;
        if (text.contains("AUTH_KEY_UNREGISTERED") || text.contains("SESSION_REVOKED")
                || text.contains("AUTH_KEY_INVALID")) {
            return "session_revoked";
        }
        if (text.contains("AUTH_KEY_DUPLICATED")) {
            return "key_duplicated";
        }
        if (text.contains("USER_DEACTIVATED")) {
            return "account_banned";
        }
        if (text.contains("SESSION_PASSWORD_NEEDED")) {
            return "password_required";
        }
        if (text.contains("FLOOD_WAIT")) {
            return "flood_wait";
        }
        if (text.contains("API_ID_INVALID") || text.contains("API_ID_PUBLISHED_FLOOD")) {
            return "api_rejected";
        }
        if (text.contains("LOGIN_TOKEN") || text.contains("TOKEN_EXPIRED")
                || text.contains("TOKEN_INVALID")) {
            return "token_expired";
        }
        if (text.length() == 0 || text.contains("CONNECTION") || text.contains("TIMEOUT")) {
            return "network_error";
        }
        return "login_failed";
    }

    /** для тестов: какие слоты вообще разрешено занимать */
    public static boolean isReusableSlot(int slot, int targetAccount) {
        if (slot == targetAccount) {
            return false;
        }
        try {
            return !UserConfig.getInstance(slot).isClientActivated()
                    && slot != UserConfig.selectedAccount;
        } catch (Throwable ignore) {
            return false;
        }
    }
}
