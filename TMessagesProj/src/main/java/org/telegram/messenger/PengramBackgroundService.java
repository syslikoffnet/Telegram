package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Pengram: «работать в фоне».
 *
 * Мы не плодим собственный сервис-пожиратель батареи, а аккуратно включаем уже существующий
 * механизм Telegram: лёгкий foreground-сервис {@link NotificationsService} + пуш-соединение
 * для всех аккаунтов. Так уведомления доходят и сообщения (в том числе удалённые) сохраняются,
 * пока приложение закрыто, а расход батареи остаётся минимальным — соединение простаивает.
 */
public class PengramBackgroundService {

    private PengramBackgroundService() {
    }

    /** Применить текущее состояние настройки. */
    public static void update(Context context) {
        // Do not overwrite Telegram's per-account pushService preference.
        applyPushConnection(PengramConfig.isBackgroundMode());
        ApplicationLoader.startPushService();
    }

    /** Called on startup; also repairs the old global preference. */
    public static void onApplicationStart() {
        // Old Pengram versions overwrote account 0's pushService setting when
        // switching this mode. Migrate once; later user choices remain intact.
        final String migration = "migratedBackgroundPushService";
        if (!PengramConfig.getBool(migration, false)) {
            MessagesController.getGlobalNotificationsSettings().edit().remove("pushService").apply();
            PengramConfig.setBool(migration, true);
        }
        applyPushConnection(PengramConfig.isBackgroundMode());
        ApplicationLoader.startPushService();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    UserConfig.getInstance(a).pengramApplyLocalPremiumStatus();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private static void applyPushConnection(boolean enabled) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (!UserConfig.getInstance(a).isClientActivated()) continue;
                // Restore the user's Telegram preference instead of forcing the
                // connection OFF whenever the optional Pengram mode is disabled.
                boolean preferred = MessagesController.getNotificationsSettings(a).getBoolean("pushConnection",
                        MessagesController.getMainSettings(a).getBoolean("backgroundConnection", false));
                ConnectionsManager.getInstance(a).setPushConnectionEnabled(enabled || preferred);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}
