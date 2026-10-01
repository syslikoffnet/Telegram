package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

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
        final boolean enabled = PengramConfig.isBackgroundMode();
        try {
            SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
            preferences.edit().putBoolean("pushService", enabled).apply();
        } catch (Throwable ignore) {
        }
        applyPushConnection(enabled);
        try {
            Context appContext = context != null ? context.getApplicationContext() : ApplicationLoader.applicationContext;
            if (appContext == null) {
                return;
            }
            Intent intent = new Intent(appContext, NotificationsService.class);
            if (enabled) {
                appContext.startService(intent);
            } else {
                appContext.stopService(intent);
            }
        } catch (Throwable ignore) {
        }
    }

    /** Вызывается при старте приложения. */
    public static void onApplicationStart() {
        if (PengramConfig.isBackgroundMode()) {
            applyPushConnection(true);
        }
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
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                ConnectionsManager.getInstance(a).setPushConnectionEnabled(enabled);
            } catch (Throwable ignore) {
            }
        }
    }
}
