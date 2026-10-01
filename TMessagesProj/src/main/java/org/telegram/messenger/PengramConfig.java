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
