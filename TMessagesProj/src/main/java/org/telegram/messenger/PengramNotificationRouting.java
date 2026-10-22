package org.telegram.messenger;

/** Recipient and Android intent identity, independent of the selected UI account. */
public final class PengramNotificationRouting {
    private PengramNotificationRouting() { }

    public static int findPushAccount(Object recipient) {
        long userId = 0;
        if (recipient != null) {
            if (recipient instanceof Long || recipient instanceof Integer) {
                userId = ((Number) recipient).longValue();
            } else if (recipient instanceof String) {
                try {
                    userId = Long.parseLong((String) recipient);
                } catch (NumberFormatException error) {
                    return -1;
                }
            } else {
                return -1;
            }
            if (userId <= 0) return -1;
        }
        int result = -1;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated() || config.getClientUserId() <= 0
                    || PengramLocalKeep.isLocalOnly(account)) continue;
            if (userId == 0 || config.getClientUserId() == userId) {
                // Почему: legacy без recipient и дубликат профиля нельзя приписывать выбранному слоту.
                if (result != -1) return -1;
                result = account;
            }
        }
        return result;
    }

    public static String shortcutIdentity(int account, long userId, long dialogId) {
        return "ndid_" + account + "_" + userId + "_" + dialogId;
    }

    public static String broadcastIdentity(int account, long userId, long dialogId, long topicId, int messageId) {
        // Почему: Android filterEquals не сравнивает extras, включая currentAccount.
        return "pengram://notification/" + account + "/" + userId + "/" + dialogId + "/" + topicId + "/" + messageId;
    }
}
