package org.telegram.messenger;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Pengram: «работать в фоне».
 *
 * Мы не плодим собственный сервис-пожиратель батареи, а аккуратно включаем уже существующий
 * механизм Telegram: лёгкий foreground-сервис {@link NotificationsService} + пуш-соединение
 * для всех аккаунтов. Так уведомления доходят и сообщения (в том числе удалённые) сохраняются,
 * пока приложение закрыто, а расход батареи остаётся минимальным — соединение простаивает.
 *
 * <p>Второй, более важный режим — страховка от неработающего FCM. Сборка подписана
 * собственным ключом и живёт в чужом Firebase-проекте, поэтому токен может не выдаваться
 * вовсе (нет Google Play Services, ключ API ограничен подписью Telegram, установка без
 * магазина). Сервер в этом случае не отправляет пуши, и уведомления приходят только когда
 * система случайно разбудит процесс, — на практике «через полчаса». Как только видно, что
 * токена нет или Firebase вернул ошибку, единственным рабочим каналом становится наше
 * пуш-соединение, и оно включается само.
 */
public class PengramBackgroundService {

    private PengramBackgroundService() {
    }

    /** Применить текущее состояние настройки. */
    public static void update(Context context) {
        // Do not overwrite Telegram's per-account pushService preference.
        applyPushConnection();
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
        applyPushConnection();
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

    /**
     * FCM сейчас не может разбудить приложение: токена нет (и это не
     * промежуточное «получаем»), либо Firebase уже ответил ошибкой.
     *
     * <p>На старте {@code pushStringStatus} намеренно выставляется в
     * {@code __FIREBASE_GENERATING_SINCE_…__} — это ещё не приговор, токен
     * запрашивается через секунду. Приговор — пустой токен без «generating»
     * или явные {@code __FIREBASE_FAILED__} / {@code __NO_GOOGLE_PLAY_SERVICES__}.
     */
    public static boolean isPushTokenMissing() {
        if (!TextUtils.isEmpty(SharedConfig.pushString)) {
            return false;
        }
        final String status = SharedConfig.pushStringStatus;
        if (status != null && status.startsWith("__FIREBASE_GENERATING")) {
            return false;
        }
        return true;
    }

    /**
     * Держать ли постоянное соединение. Помимо явной настройки и выбора
     * пользователя в уведомлениях, оно включается автоматически, когда FCM
     * недоступен: без этого канала сообщения ждут открытия приложения.
     */
    public static boolean shouldKeepAlive() {
        if (PengramConfig.isBackgroundMode()) {
            return true;
        }
        if (isPushTokenMissing()) {
            return true;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()
                        && MessagesController.getNotificationsSettings(a).getBoolean("pushService",
                        MessagesController.getMainSettings(a).getBoolean("keepAliveService", false))) {
                    return true;
                }
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    /**
     * Результат попытки получить FCM-токен. Успех возвращает управление
     * обычной схеме, ошибка — включает пуш-соединение, чтобы уведомления не
     * зависели от чужого Firebase-проекта.
     */
    public static void onPushRegistrationResult(boolean success) {
        if (!success) {
            // Токен мог остаться с прошлой установки и уже не работать:
            // без этой очистки приложение считало бы FCM живым.
            SharedConfig.pushString = "";
            SharedConfig.saveConfig();
        }
        // sendRegistrationToServer пишет токен в stageQueue: успех проверяем
        // чуть позже, иначе решение примется по ещё пустому pushString.
        AndroidUtilities.runOnUIThread(() -> {
            if (success && !TextUtils.isEmpty(SharedConfig.pushString)) {
                // иначе следующий старт начинается с «токена нет» и лишний
                // раз поднимает сервис до первого ответа Firebase
                SharedConfig.saveConfig();
            }
            update(ApplicationLoader.applicationContext);
        }, success ? 2500 : 0);
    }

    /** Аккаунт только что авторизовался: сервис ещё ни разу не видел активных слотов. */
    public static void onAccountAuthorized() {
        AndroidUtilities.runOnUIThread(() -> {
            applyPushConnection();
            ApplicationLoader.startPushService();
        });
    }

    private static void applyPushConnection() {
        final boolean enabled = shouldKeepAlive();
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
