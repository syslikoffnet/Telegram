package org.telegram.messenger;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: Dynamic Color (Material You).
 *
 * Android 12+ отдаёт приложению палитру, высчитанную системой из обоев, —
 * ряды system_accent1..3 и system_neutral1..2. Телеграм умеет перекрашивать
 * всю тему от одного акцентного цвета (механизм ThemeAccent), поэтому мы не
 * изобретаем свой движок: берём из системной палитры подходящий тон и
 * подставляем его в отдельный служебный акцент текущей темы.
 *
 * Акцент живёт только в памяти: при каждом запуске он создаётся заново, в файл
 * темы ничего не дописывается. Прошлый выбор пользователя запоминается, чтобы
 * при выключении Material You вернуть всё как было.
 */
public final class PengramMonet {

    /** главный выключатель Dynamic Color */
    public static final String KEY_ENABLED = "md3Monet";
    /** красить и свои сообщения тоже */
    public static final String KEY_MESSAGES = "md3MonetMessages";
    /** насыщенность: 0 — спокойно, 1 — обычно, 2 — ярко */
    public static final String KEY_STRENGTH = "md3MonetStrength";

    /** служебный id акцента: заведомо выше пользовательских (те начинаются со 100) */
    public static final int ACCENT_ID = 9100;

    private PengramMonet() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public static boolean isEnabled() {
        return isSupported() && PengramConfig.getBool(KEY_ENABLED, false);
    }

    public static boolean tintMessages() {
        return PengramConfig.getBool(KEY_MESSAGES, true);
    }

    public static int strength() {
        final int value = PengramConfig.getIntCached(KEY_STRENGTH, 1);
        return value < 0 || value > 2 ? 1 : value;
    }

    /**
     * Акцент из системной палитры. В светлой теме нужен тон потемнее, в тёмной —
     * посветлее, иначе текст на нём перестанет читаться.
     */
    public static int systemAccent(Context context, boolean dark) {
        if (context == null || !isSupported()) {
            return 0;
        }
        final int res;
        switch (strength()) {
            case 0:
                res = dark ? android.R.color.system_accent2_200 : android.R.color.system_accent2_600;
                break;
            case 2:
                res = dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600;
                break;
            default:
                res = dark ? android.R.color.system_accent1_300 : android.R.color.system_accent1_500;
                break;
        }
        try {
            return context.getColor(res);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** цвет исходящих сообщений: чуть мягче основного акцента */
    private static int messagesColor(int accent, boolean dark) {
        return dark
                ? ColorUtils.blendARGB(accent, Color.BLACK, 0.25f)
                : ColorUtils.blendARGB(accent, Color.WHITE, 0.08f);
    }

    private static String prevKey(Theme.ThemeInfo theme) {
        return "md3MonetPrevAccent_" + (theme == null || theme.name == null ? "default" : theme.name);
    }

    /**
     * Привести тему в соответствие с настройкой. Вызывается на старте, при
     * смене темы и сразу после переключения тумблеров.
     */
    public static void update(Context context) {
        try {
            final Theme.ThemeInfo theme = Theme.getActiveTheme();
            if (theme == null || theme.themeAccents == null || theme.themeAccents.isEmpty()
                    || theme.themeAccentsMap == null) {
                return;
            }
            final boolean dark = theme.isDark();
            if (isEnabled()) {
                final int accentColor = systemAccent(context, dark);
                if (accentColor == 0) {
                    return;
                }
                Theme.ThemeAccent accent = theme.themeAccentsMap.get(ACCENT_ID);
                if (accent == null) {
                    accent = Theme.ThemeAccent.createPengramAccent(theme, ACCENT_ID);
                    theme.themeAccentsMap.put(ACCENT_ID, accent);
                    theme.themeAccents.add(accent);
                }
                accent.accentColor = accentColor;
                if (tintMessages()) {
                    accent.myMessagesAccentColor = messagesColor(accentColor, dark);
                    accent.myMessagesGradientAccentColor1 = 0;
                } else {
                    accent.myMessagesAccentColor = 0;
                    accent.myMessagesGradientAccentColor1 = 0;
                }
                if (theme.currentAccentId != ACCENT_ID) {
                    PengramConfig.setIntValue(prevKey(theme), theme.currentAccentId);
                    theme.setCurrentAccentId(ACCENT_ID);
                }
                Theme.refreshThemeColors();
            } else if (theme.currentAccentId == ACCENT_ID) {
                int prev = PengramConfig.getIntCached(prevKey(theme), Theme.DEFALT_THEME_ACCENT_ID);
                if (prev == ACCENT_ID || theme.themeAccentsMap.get(prev) == null) {
                    prev = theme.themeAccents.get(0).id;
                }
                theme.setCurrentAccentId(prev);
                Theme.refreshThemeColors();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
