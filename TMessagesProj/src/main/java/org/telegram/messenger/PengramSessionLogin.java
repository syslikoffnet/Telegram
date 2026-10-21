package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.security.MessageDigest;

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
    private static final int MAX_ROUNDS = 4;
    /** пауза между экспортом токена: серверу нужно время применить подтверждение */
    private static final long RETRY_DELAY = 700L;

    public interface Listener {
        void onSuccess(TLRPC.TL_auth_authorization authorization);

        /** нужен пароль двухфакторной защиты: показать штатную страницу */
        void onPasswordNeeded(TLRPC.TL_account_Password password);

        /** код ошибки — текст живёт в строках интерфейса */
        void onError(String code);
    }

    /** слот, через который говорит уже готовая сессия */
    private static int sourceSlot = -1;

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
        if (MODE_INSTANT.equals(mode)) {
            instant(targetAccount, session, listener);
        } else {
            exportToken(targetAccount, session, listener, 0);
        }
    }

    /** временный слот освобождаем всегда, даже если вход не удался */
    public static void cancel() {
        releaseSource();
    }

    // ------------------------------------------------------------- мгновенный

    private static void instant(final int account, final PengramSessionFormats.Account session,
                                final Listener listener) {
        final ConnectionsManager manager = ConnectionsManager.getInstance(account);
        manager.importAuthorization(session.dcId, session.authKey, authKeyId(session.authKey));
        manager.setUserId(session.userId > 0 ? session.userId : 0);

        final TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
        request.id = new TLRPC.TL_inputUserSelf();
        manager.sendRequest(request, (response, error) -> {
            if (response instanceof TLRPC.TL_users_userFull) {
                final TLRPC.TL_users_userFull full = (TLRPC.TL_users_userFull) response;
                TLRPC.User user = full.full_user != null ? full.full_user.user : null;
                if (user == null && !full.users.isEmpty()) {
                    user = full.users.get(0);
                }
                if (user != null) {
                    final TLRPC.TL_auth_authorization authorization = new TLRPC.TL_auth_authorization();
                    authorization.user = user;
                    listener.onSuccess(authorization);
                    return;
                }
            }
            listener.onError(mapError(error));
        }, FLAGS);
    }

    // --------------------------------------------------------------- токен

    private static void exportToken(final int account, final PengramSessionFormats.Account session,
                                    final Listener listener, final int round) {
        if (round > MAX_ROUNDS) {
            listener.onError("login_timeout");
            return;
        }
        final TLRPC.TL_auth_exportLoginToken request = new TLRPC.TL_auth_exportLoginToken();
        request.api_id = BuildVars.APP_ID;
        request.api_hash = BuildVars.APP_HASH;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (error != null) {
                if ("SESSION_PASSWORD_NEEDED".equals(error.text)) {
                    requestPassword(account, listener);
                } else {
                    listener.onError(mapError(error));
                }
                return;
            }
            if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                final TLRPC.TL_auth_loginTokenSuccess success = (TLRPC.TL_auth_loginTokenSuccess) response;
                if (success.authorization instanceof TLRPC.TL_auth_authorization) {
                    listener.onSuccess((TLRPC.TL_auth_authorization) success.authorization);
                } else {
                    listener.onError("unexpected_response");
                }
                return;
            }
            if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                importOnNewDc(account, session, listener, round,
                        (TLRPC.TL_auth_loginTokenMigrateTo) response);
                return;
            }
            if (response instanceof TLRPC.TL_auth_loginToken) {
                acceptOnSource(account, session, listener, round, (TLRPC.TL_auth_loginToken) response);
                return;
            }
            listener.onError("unexpected_response");
        }, FLAGS);
    }

    private static void importOnNewDc(final int account, final PengramSessionFormats.Account session,
                                      final Listener listener, final int round,
                                      final TLRPC.TL_auth_loginTokenMigrateTo migrate) {
        final TLRPC.TL_auth_importLoginToken request = new TLRPC.TL_auth_importLoginToken();
        request.token = migrate.token;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                final TLRPC.TL_auth_loginTokenSuccess success = (TLRPC.TL_auth_loginTokenSuccess) response;
                if (success.authorization instanceof TLRPC.TL_auth_authorization) {
                    listener.onSuccess((TLRPC.TL_auth_authorization) success.authorization);
                } else {
                    listener.onError("unexpected_response");
                }
                return;
            }
            if (error != null) {
                listener.onError(mapError(error));
                return;
            }
            // Токен уже принадлежит новому DC — повторяем экспорт там же
            retryLater(account, session, listener, round + 1, RETRY_DELAY);
        }, FLAGS);
    }

    /** подтверждение на стороне уже авторизованной сессии из файла */
    private static void acceptOnSource(final int account, final PengramSessionFormats.Account session,
                                       final Listener listener, final int round,
                                       final TLRPC.TL_auth_loginToken token) {
        final int slot = findSourceSlot(account);
        if (slot < 0) {
            listener.onError("no_slot");
            return;
        }
        sourceSlot = slot;
        final ConnectionsManager source = ConnectionsManager.getInstance(slot);
        source.importAuthorization(session.dcId, session.authKey, authKeyId(session.authKey));
        source.setUserId(session.userId);

        final TLRPC.TL_auth_acceptLoginToken accept = new TLRPC.TL_auth_acceptLoginToken();
        accept.token = token.token;
        source.sendRequest(accept, (response, error) -> {
            // auth.acceptLoginToken возвращает авторизацию самой сессии-источника:
            // это и есть подтверждение, отдельного Bool тут нет
            final boolean accepted = response instanceof TLRPC.auth_Authorization
                    || (error != null && error.text != null && error.text.contains("ALREADY_ACCEPTED"));
            if (!accepted) {
                final String code = mapError(error);
                releaseSource();
                listener.onError(code);
                return;
            }
            // Ключ источника больше не нужен: закрываем его до повторного экспорта,
            // иначе в памяти живут два одинаковых ключа.
            releaseSource();
            retryLater(account, session, listener, round + 1, RETRY_DELAY);
        }, FLAGS);
    }

    private static void retryLater(final int account, final PengramSessionFormats.Account session,
                                   final Listener listener, final int round, final long delay) {
        AndroidUtilities.runOnUIThread(() -> exportToken(account, session, listener, round), delay);
    }

    private static void requestPassword(final int account, final Listener listener) {
        final TLRPC.TL_account_getPassword request = new TLRPC.TL_account_getPassword();
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (response instanceof TLRPC.TL_account_Password) {
                listener.onPasswordNeeded((TLRPC.TL_account_Password) response);
            } else {
                listener.onError(mapError(error));
            }
        }, FLAGS);
    }

    // ------------------------------------------------------------- служебное

    /**
     * Свободный слот для временного соединения. Активные аккаунты не трогаем:
     * подменить чужой ключ было бы катастрофой.
     */
    private static int findSourceSlot(int targetAccount) {
        for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; --a) {
            if (a == targetAccount) {
                continue;
            }
            try {
                if (!UserConfig.getInstance(a).isClientActivated() && a != UserConfig.selectedAccount) {
                    return a;
                }
            } catch (Throwable ignore) {
            }
        }
        return -1;
    }

    private static void releaseSource() {
        final int slot = sourceSlot;
        sourceSlot = -1;
        if (slot < 0) {
            return;
        }
        try {
            ConnectionsManager.getInstance(slot).cleanUp(true, -1);
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
            return !UserConfig.getInstance(slot).isClientActivated();
        } catch (Throwable ignore) {
            return false;
        }
    }
}
