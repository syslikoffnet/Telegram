package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.security.MessageDigest;

/**
 * Pengram: вход в аккаунт по готовой сессии из файла.
 *
 * <p>Ключ авторизации — это и есть сессия. Так же поступают Telethon, Pyrogram
 * и сам Telegram Desktop: файл кладёт ключ в конкретный DC, клиент ставит его
 * постоянным ключом этого DC и работает дальше обычным путём. Никаких
 * одноразовых токенов здесь нет и быть не может: токен создаётся на одном DC,
 * принимается только на нём же и требует второй уже авторизованной сессии — на
 * телефоне её нет, а попытка «подтвердить» токен тем же самым ключом создаёт
 * ровно ту ситуацию с двумя копиями ключа, из-за которой Telegram завершает
 * сессию. Поэтому путь один и он же самый быстрый.
 *
 * <p>Порядок важен: сначала чистим слот (иначе в запрос уйдёт ключ от прошлой
 * неудачной попытки, и сервер ответит AUTH_KEY_UNREGISTERED — «сессия уже
 * недействительна», хотя файл в порядке), потом ставим ключ и домашний DC,
 * и только потом проверяем ключ запросом {@code users.getFullUser}.
 */
public final class PengramSessionLogin {

    /** режим по умолчанию: ни одного вопроса пользователю */
    public static final String MODE_AUTO = "auto";
    /** оставлены для совместимости: оба означают тот же прямой вход */
    public static final String MODE_SAFE = "safe";
    public static final String MODE_INSTANT = "instant";

    /**
     * Без логина (аккаунта ещё нет), с правом неавторизованного запроса и с
     * внятной ошибкой сервера вместо бесконечного ожидания.
     */
    private static final int FLAGS = ConnectionsManager.RequestFlagWithoutLogin
            | ConnectionsManager.RequestFlagFailOnServerErrors
            | ConnectionsManager.RequestFlagEnableUnauthorized;

    public interface Listener {
        void onSuccess(TLRPC.TL_auth_authorization authorization);

        /**
         * Оставлено для совместимости с экраном входа. При входе по файлу
         * пароль не нужен: он уже пройден в том клиенте, который создал ключ.
         */
        void onPasswordNeeded(org.telegram.tgnet.tl.TL_account.Password password);

        /** код ошибки — текст живёт в строках интерфейса */
        void onError(String code);
    }

    /** состояние одного входа: два параллельных входа не мешают друг другу */
    private static final class Flow {
        final int account;
        final PengramSessionFormats.Account session;
        final Listener listener;
        volatile boolean cancelled;
        /** одна автоматическая повторная попытка на дубликат ключа и сеть */
        boolean retried;

        Flow(int account, PengramSessionFormats.Account session, Listener listener) {
            this.account = account;
            this.session = session;
            this.listener = listener;
        }
    }

    private static volatile Flow active;

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

    /**
     * Вход по уже разобранной сессии. Режим принимается только для
     * совместимости: любой из них означает прямой вход ключом.
     */
    public static void start(final int targetAccount, final PengramSessionFormats.Account session,
                             final String mode, final Listener listener) {
        if (session == null || !session.isUsable() || authKeyId(session.authKey) == 0) {
            listener.onError("invalid_key");
            return;
        }
        if (targetAccount < 0 || targetAccount >= UserConfig.MAX_ACCOUNT_COUNT) {
            listener.onError("no_slot");
            return;
        }
        PengramDiagnostics.recordImportAttempt(targetAccount);
        // Почему: сетевой cleanup не удаляет профиль и БД занятого слота.
        // Без отдельного подтверждённого сброса заменять ключ здесь нельзя.
        if (!isSlotAvailable(targetAccount)) {
            listener.onError("slot_busy");
            return;
        }
        if (session.userId != 0 && hasAccountUserId(session.userId, targetAccount)) {
            listener.onError("account_exists");
            return;
        }
        cancel();
        final Flow flow = new Flow(targetAccount, session, listener);
        active = flow;
        // Почему: пустой UserConfig ещё не доказывает отсутствие сохранённой истории.
        // Проверка идёт по storageQueue до установки ключа, без удаления старой БД.
        MessagesStorage.getInstance(targetAccount).checkSessionSlotEmpty(empty -> {
            if (flow.cancelled || active != flow) return;
            if (!empty) {
                fail(flow, "slot_busy");
                return;
            }
            connect(flow);
        });
    }

    private static boolean isSlotAvailable(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                && UserConfig.getInstance(account).isConfigLoaded()
                && UserConfig.getInstance(account).getClientUserId() == 0
                && !UserConfig.getInstance(account).isClientActivated()
                && !PengramLocalKeep.isLocalOnly(account);
    }

    /** Отмена: стираем разобранный ключ из памяти; нативный слот здесь не очищаем. */
    public static void cancel() {
        final Flow flow = active;
        active = null;
        if (flow == null) {
            return;
        }
        flow.cancelled = true;
        flow.session.clear();
    }

    /**
     * Прямой вход: ключ становится постоянным ключом своего DC, дальше обычный
     * запрос проверяет, что Telegram этот ключ знает.
     */
    private static void connect(final Flow flow) {
        if (flow.cancelled || active != flow) return;
        if (!isSlotAvailable(flow.account)) {
            fail(flow, "slot_busy");
            return;
        }
        final ConnectionsManager manager = ConnectionsManager.getInstance(flow.account);
        final PengramSessionFormats.Account session = flow.session;

        // В слоте мог остаться ключ (или userId) от прошлой попытки входа: с ним
        // первый же запрос ушёл бы «чужим» ключом. Чистим до импорта.
        manager.cleanup(true);
        manager.importAuthorization(session.dcId, session.authKey, authKeyId(session.authKey));
        // userId до проверки не ставим: если ключ не подойдёт, аккаунт выглядел
        // бы наполовину вошедшим. Проверочный запрос идёт без логина, а
        // настоящий id ставит onAuthSuccess обычным путём.

        final TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
        request.id = new TLRPC.TL_inputUserSelf();
        manager.sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (flow.cancelled || active != flow) return;
            // Почему: ответ старого импорта не должен менять уже занятый слот.
            if (!isSlotAvailable(flow.account)) {
                fail(flow, "slot_busy");
                return;
            }
            TLRPC.User user = null;
            if (response instanceof TLRPC.TL_users_userFull) {
                final TLRPC.TL_users_userFull full = (TLRPC.TL_users_userFull) response;
                user = resolveSessionSelf(full);
                if (user == null) {
                    manager.cleanup(true);
                    fail(flow, "identity_mismatch");
                    return;
                }
            }
            if (user == null) {
                // Ключ не подошёл — не оставляем его в слоте аккаунта.
                manager.cleanup(true);
                final String code = mapError(error);
                // Повторяем ровно один раз и только то, что может быть
                // случайностью: сеть, дубликат ключа и «ключ не найден» —
                // последнее часто означает, что временный ключ не привязался
                // к постоянному в первом handshake, а не что файл мёртв.
                // Бан и несовпадение формата повтором не лечатся.
                if (!flow.retried && ("key_duplicated".equals(code)
                        || "network_error".equals(code) || "session_revoked".equals(code))) {
                    flow.retried = true;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!flow.cancelled && active == flow) {
                            connect(flow);
                        }
                    }, 1500);
                    return;
                }
                fail(flow, code);
                return;
            }
            if (session.userId != 0 && session.userId != user.id) {
                manager.cleanup(true);
                fail(flow, "identity_mismatch");
                return;
            }
            if (hasAccountUserId(user.id, flow.account)) {
                manager.cleanup(true);
                fail(flow, "account_exists");
                return;
            }
            manager.setUserId(user.id);
            final TLRPC.TL_auth_authorization authorization = new TLRPC.TL_auth_authorization();
            authorization.user = user;
            succeed(flow, authorization);
        }), FLAGS);
    }

    static TLRPC.User resolveSessionSelf(TLRPC.TL_users_userFull full) {
        if (full == null || full.full_user == null) return null;
        final TLRPC.User legacy = full.full_user.user;
        final long id = full.full_user.id != 0 ? full.full_user.id : legacy != null ? legacy.id : 0;
        if (id <= 0) return null;
        // Почему: users содержит также связанных пользователей, порядок не задаёт self.
        TLRPC.User result = null;
        if (legacy != null) {
            if (legacy.id != id || !legacy.self || legacy.deleted) return null;
            result = legacy;
        }
        if (full.users != null) {
            for (int i = 0; i < full.users.size(); i++) {
                final TLRPC.User user = full.users.get(i);
                if (user != null && user.id == id) {
                    if (!user.self || user.deleted || (result != null && result != user)) return null;
                    result = user;
                }
            }
        }
        return result;
    }

    private static boolean hasAccountUserId(long userId, int exceptAccount) {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (account != exceptAccount && UserConfig.getInstance(account).getClientUserId() == userId) {
                return true;
            }
        }
        return false;
    }

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
}
