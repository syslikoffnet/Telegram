package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки форка Pengram (призрак-режим, отображение ID и т.д.).
 * Хранится в отдельном файле настроек, чтобы не конфликтовать с апстримом.
 */
public class PengramConfig {

    private static final String PREFS = "pengramconfig";

    // --- стили отображения ID в профиле ---
    public static final int ID_STYLE_OFF = 0;          // не показывать
    public static final int ID_STYLE_ROW = 1;          // отдельная строка "ID" под юзернеймом
    public static final int ID_STYLE_ROW_DC = 2;       // отдельная строка "ID • DC2"
    public static final int ID_STYLE_INLINE = 3;       // дописывается к подписи юзернейма

    // --- призрак-режим ---
    public static boolean ghostMode;          // мастер-переключатель
    public static boolean hideOnline;         // не показывать онлайн / не слать updateStatus
    public static boolean dontSendRead;       // не отправлять прочтение сообщений
    public static boolean dontSendTyping;     // не отправлять "печатает"
    public static boolean dontSendStoryViews; // не отмечать просмотр историй

    // --- профиль ---
    public static int idStyle = ID_STYLE_ROW_DC;
    public static boolean copyIdOnTap = true;

    // --- дата регистрации ---
    public static final int REG_STYLE_OFF = 0;
    public static final int REG_STYLE_DATE = 1;      // «≈ март 2021»
    public static final int REG_STYLE_DATE_AGE = 2;  // «≈ март 2021 • 4 года»
    public static int regDateStyle = REG_STYLE_DATE_AGE;

    // --- история удалённых/изменённых ---
    public static boolean saveDeleted = true;
    public static boolean saveEdited = true;
    public static boolean saveOutgoing = false;      // сохранять и свои сообщения
    public static boolean historyRowInProfile = true;

    // --- снятие ограничений ---
    public static boolean allowScreenshots = true;      // разрешать скриншоты везде
    public static boolean noScreenshotNotify = true;    // не уведомлять о скриншотах в секретных чатах
    public static boolean allowForwards = true;         // обходить запрет пересылки/сохранения
    public static boolean keepOnceMedia = true;         // одноразовые медиа не «сгорают»
    public static boolean hideAds = true;               // убирать рекламу
    public static boolean localPremium = false;         // локальный premium

    // --- внешний вид ---
    public static final int MENU_POS_TOP = 0;
    public static final int MENU_POS_BOTTOM = 1;
    public static int chatMenuPosition = MENU_POS_TOP;  // где остров Pengram в меню чата
    public static boolean chatMenuEnabled = true;

    public static final int FONT_DEFAULT = 0;
    public static final int FONT_SYSTEM = 1;
    public static final int FONT_SERIF = 2;
    public static final int FONT_MONOSPACE = 3;
    public static int appFont = FONT_DEFAULT;

    private static boolean loaded;

    public static void init() {
        if (loaded) return;
        synchronized (PengramConfig.class) {
            if (loaded) return;
            SharedPreferences p = prefs();
            if (p == null) return;
            ghostMode = p.getBoolean("ghostMode", false);
            hideOnline = p.getBoolean("hideOnline", true);
            dontSendRead = p.getBoolean("dontSendRead", true);
            dontSendTyping = p.getBoolean("dontSendTyping", true);
            dontSendStoryViews = p.getBoolean("dontSendStoryViews", true);
            idStyle = p.getInt("idStyle", ID_STYLE_ROW_DC);
            copyIdOnTap = p.getBoolean("copyIdOnTap", true);
            regDateStyle = p.getInt("regDateStyle", REG_STYLE_DATE_AGE);
            saveDeleted = p.getBoolean("saveDeleted", true);
            saveEdited = p.getBoolean("saveEdited", true);
            saveOutgoing = p.getBoolean("saveOutgoing", false);
            historyRowInProfile = p.getBoolean("historyRowInProfile", true);
            allowScreenshots = p.getBoolean("allowScreenshots", true);
            noScreenshotNotify = p.getBoolean("noScreenshotNotify", true);
            allowForwards = p.getBoolean("allowForwards", true);
            keepOnceMedia = p.getBoolean("keepOnceMedia", true);
            hideAds = p.getBoolean("hideAds", true);
            localPremium = p.getBoolean("localPremium", false);
            chatMenuPosition = p.getInt("chatMenuPosition", MENU_POS_TOP);
            chatMenuEnabled = p.getBoolean("chatMenuEnabled", true);
            appFont = p.getInt("appFont", FONT_DEFAULT);
            loaded = true;
        }
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) return null;
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void putBoolean(String key, boolean value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(key, value).apply();
    }

    private static void putInt(String key, int value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(key, value).apply();
    }

    public static void toggleGhostMode() {
        init();
        ghostMode = !ghostMode;
        putBoolean("ghostMode", ghostMode);
    }

    public static void toggleHideOnline() {
        init();
        hideOnline = !hideOnline;
        putBoolean("hideOnline", hideOnline);
    }

    public static void toggleDontSendRead() {
        init();
        dontSendRead = !dontSendRead;
        putBoolean("dontSendRead", dontSendRead);
    }

    public static void toggleDontSendTyping() {
        init();
        dontSendTyping = !dontSendTyping;
        putBoolean("dontSendTyping", dontSendTyping);
    }

    public static void toggleDontSendStoryViews() {
        init();
        dontSendStoryViews = !dontSendStoryViews;
        putBoolean("dontSendStoryViews", dontSendStoryViews);
    }

    public static void toggleCopyIdOnTap() {
        init();
        copyIdOnTap = !copyIdOnTap;
        putBoolean("copyIdOnTap", copyIdOnTap);
    }

    public static void setRegDateStyle(int style) {
        init();
        regDateStyle = style;
        putInt("regDateStyle", style);
    }

    public static void toggleSaveDeleted() {
        init();
        saveDeleted = !saveDeleted;
        putBoolean("saveDeleted", saveDeleted);
    }

    public static void toggleSaveEdited() {
        init();
        saveEdited = !saveEdited;
        putBoolean("saveEdited", saveEdited);
    }

    public static void toggleSaveOutgoing() {
        init();
        saveOutgoing = !saveOutgoing;
        putBoolean("saveOutgoing", saveOutgoing);
    }

    public static void toggleHistoryRowInProfile() {
        init();
        historyRowInProfile = !historyRowInProfile;
        putBoolean("historyRowInProfile", historyRowInProfile);
    }

    public static boolean isSavingDeleted() {
        init();
        return saveDeleted;
    }

    public static boolean isSavingEdited() {
        init();
        return saveEdited;
    }

    public static boolean isSavingOutgoing() {
        init();
        return saveOutgoing;
    }

    public static int getRegDateStyle() {
        init();
        return regDateStyle;
    }

    public static boolean isRegDateVisible() {
        return getRegDateStyle() != REG_STYLE_OFF;
    }

    public static void toggleAllowScreenshots() { init(); allowScreenshots = !allowScreenshots; putBoolean("allowScreenshots", allowScreenshots); }
    public static void toggleNoScreenshotNotify() { init(); noScreenshotNotify = !noScreenshotNotify; putBoolean("noScreenshotNotify", noScreenshotNotify); }
    public static void toggleAllowForwards() { init(); allowForwards = !allowForwards; putBoolean("allowForwards", allowForwards); }
    public static void toggleKeepOnceMedia() { init(); keepOnceMedia = !keepOnceMedia; putBoolean("keepOnceMedia", keepOnceMedia); }
    public static void toggleHideAds() { init(); hideAds = !hideAds; putBoolean("hideAds", hideAds); }
    public static void toggleLocalPremium() { init(); localPremium = !localPremium; putBoolean("localPremium", localPremium); }
    public static void toggleChatMenu() { init(); chatMenuEnabled = !chatMenuEnabled; putBoolean("chatMenuEnabled", chatMenuEnabled); }
    public static void setChatMenuPosition(int pos) { init(); chatMenuPosition = pos; putInt("chatMenuPosition", pos); }
    public static void setAppFont(int font) { init(); appFont = font; putInt("appFont", font); }

    /** true — FLAG_SECURE ставить нельзя, скриншоты разрешены */
    public static boolean screenshotsAllowed() {
        init();
        return allowScreenshots;
    }

    public static boolean isNoScreenshotNotify() { init(); return allowScreenshots && noScreenshotNotify; }
    public static boolean isBypassingForwardRestrictions() { init(); return allowForwards; }
    public static boolean isKeepingOnceMedia() { init(); return keepOnceMedia; }
    public static boolean isHidingAds() { init(); return hideAds; }
    public static boolean isLocalPremium() { init(); return localPremium; }
    public static int getChatMenuPosition() { init(); return chatMenuPosition; }
    public static boolean isChatMenuEnabled() { init(); return chatMenuEnabled; }
    public static int getAppFont() { init(); return appFont; }

    public static boolean isHistoryRowVisible() {
        init();
        return historyRowInProfile && (saveDeleted || saveEdited);
    }

    public static void setIdStyle(int style) {
        init();
        idStyle = style;
        putInt("idStyle", style);
    }

    // ---- то, что спрашивает остальной код ----

    public static boolean isHidingOnline() {
        init();
        return ghostMode && hideOnline;
    }

    public static boolean isNotSendingRead() {
        init();
        return ghostMode && dontSendRead;
    }

    public static boolean isNotSendingTyping() {
        init();
        return ghostMode && dontSendTyping;
    }

    public static boolean isNotSendingStoryViews() {
        init();
        return ghostMode && dontSendStoryViews;
    }

    public static int getIdStyle() {
        init();
        return idStyle;
    }

    public static boolean isIdVisible() {
        return getIdStyle() != ID_STYLE_OFF;
    }

    public static boolean isIdSeparateRow() {
        final int s = getIdStyle();
        return s == ID_STYLE_ROW || s == ID_STYLE_ROW_DC;
    }

    public static boolean isShowingDc() {
        return getIdStyle() == ID_STYLE_ROW_DC;
    }
}
