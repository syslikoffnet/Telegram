package org.telegram.messenger;

/**
 * Pengram: локальная копия аккаунта.
 *
 * <p>Серверные удаления чатов и сообщений не должны стирать уже полученную
 * локальную историю. Если сервер отзывает сессию, аккаунт остаётся доступен
 * только для чтения из локального кэша; состояние офлайн-копии сохраняется
 * между перезапусками приложения.
 */
public final class PengramLocalKeep {

    /** включена ли локальная копия (по умолчанию — да) */
    public static final String KEY_ENABLED = "localKeep";
    private static final String KEY_LOCAL_ONLY_PREFIX = "localOnlyRevokedAccount_";

    private PengramLocalKeep() {
    }

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, true);
    }

    private static String localOnlyKey(int account) {
        return KEY_LOCAL_ONLY_PREFIX + account;
    }

    public static void setEnabled(boolean value) {
        PengramConfig.setBool(KEY_ENABLED, value);
        if (value) {
            return;
        }
        // Turning the feature off intentionally resumes those sessions and removes
        // the persisted marker so account slots cannot remain falsely offline.
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!isLocalOnly(account)) {
                continue;
            }
            clearLocalOnly(account, false);
            try {
                org.telegram.tgnet.ConnectionsManager.native_resumeNetwork(account, false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** чат остаётся в списке, даже если сервер считает, что мы не участники */
    public static boolean keepDialog() {
        return isEnabled();
    }

    /** удалённые на сервере сообщения остаются в переписке */
    public static boolean keepDeletedMessages() {
        return isEnabled();
    }

    /**
     * Сессия отозвана. Возвращает true, если аккаунт надо оставить локальной
     * копией. Маркер привязан к слоту и сохраняется между перезапусками, чтобы
     * запуск приложения не возобновлял сеть для отозванной сессии.
     */
    public static boolean keepRevokedAccount(int account) {
        if (!isEnabled() || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || UserConfig.getInstance(account).getClientUserId() == 0) {
            return false;
        }
        PengramConfig.setBool(localOnlyKey(account), true);
        try {
            org.telegram.tgnet.ConnectionsManager.native_pauseNetwork(account);
            notifyLocalOnlyChanged(account);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            clearLocalOnly(account, false);
            return false;
        }
    }

    /** Re-apply the native network pause after process restart/native re-init. */
    public static boolean restoreLocalOnlyNetwork(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        if (!isEnabled()) {
            clearLocalOnly(account, false);
            return false;
        }
        if (!isLocalOnly(account) || UserConfig.getInstance(account).getClientUserId() == 0) {
            return false;
        }
        try {
            org.telegram.tgnet.ConnectionsManager.native_pauseNetwork(account);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Clear the marker when the slot is intentionally logged out or reused. */
    public static void clearLocalOnly(int account, boolean resumeNetwork) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        PengramConfig.setBool(localOnlyKey(account), false);
        if (resumeNetwork) {
            try {
                org.telegram.tgnet.ConnectionsManager.native_resumeNetwork(account, false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        notifyLocalOnlyChanged(account);
    }

    /** Compatibility overload for account-reset call sites that must not resume a revoked session. */
    public static void clearLocalOnly(int account) {
        clearLocalOnly(account, false);
    }

    /** аккаунт работает только с локальными данными */
    public static boolean isLocalOnly(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                && PengramConfig.getBool(localOnlyKey(account), false);
    }

    private static void notifyLocalOnlyChanged(int account) {
        try {
            NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.didUpdateConnectionState);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
