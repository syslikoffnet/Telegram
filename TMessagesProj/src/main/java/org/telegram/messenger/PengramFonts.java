package org.telegram.messenger;

import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Pengram: шрифты интерфейса.
 *
 * Системные варианты берём из Android, а единственный встроенный дисплейный
 * шрифт — Pixelify Sans под SIL OFL 1.1: он нужен для пиксельной темы и покрывает
 * кириллицу. На разных прошивках набор системных семейств отличается, поэтому
 * каждое из них проверяется: если Android подменяет его обычным sans-serif,
 * вариант не показывается в списке.
 */
public final class PengramFonts {

    public static final int DEFAULT = 0;        // родной Roboto из ассетов Telegram
    public static final int SYSTEM = 1;
    public static final int SERIF = 2;
    public static final int MONOSPACE = 3;
    public static final int CONDENSED = 4;
    public static final int LIGHT = 5;
    public static final int MEDIUM = 6;
    public static final int BLACK = 7;
    public static final int THIN = 8;
    public static final int CASUAL = 9;
    public static final int CURSIVE = 10;
    public static final int SERIF_MONO = 11;
    /** Open-licensed Cyrillic-capable pixel display font (Minecraft-like, not Mojang's font). */
    public static final int PIXEL = 12;

    private static final int[] ALL = {
            DEFAULT, SYSTEM, MEDIUM, CONDENSED, LIGHT, THIN, BLACK, SERIF, SERIF_MONO, MONOSPACE, CASUAL, CURSIVE, PIXEL
    };

    private static final HashMap<String, Typeface> cache = new HashMap<>();
    private static int[] availableCache;

    private PengramFonts() {
    }

    /** системное семейство; null — значит родной шрифт приложения */
    public static String family(int font) {
        switch (font) {
            case SYSTEM: return "sans-serif";
            case SERIF: return "serif";
            case MONOSPACE: return "monospace";
            case CONDENSED: return "sans-serif-condensed";
            case LIGHT: return "sans-serif-light";
            case MEDIUM: return "sans-serif-medium";
            case BLACK: return "sans-serif-black";
            case THIN: return "sans-serif-thin";
            case CASUAL: return "casual";
            case CURSIVE: return "cursive";
            case SERIF_MONO: return "serif-monospace";
            default: return null;
        }
    }

    public static int nameRes(int font) {
        switch (font) {
            case SYSTEM: return R.string.PengramFontSystem;
            case SERIF: return R.string.PengramFontSerif;
            case MONOSPACE: return R.string.PengramFontMono;
            case CONDENSED: return R.string.PengramFontCondensed;
            case LIGHT: return R.string.PengramFontLight;
            case MEDIUM: return R.string.PengramFontMedium;
            case BLACK: return R.string.PengramFontBlack;
            case THIN: return R.string.PengramFontThin;
            case CASUAL: return R.string.PengramFontCasual;
            case CURSIVE: return R.string.PengramFontCursive;
            case SERIF_MONO: return R.string.PengramFontSerifMono;
            case PIXEL: return R.string.PengramFontPixel;
            default: return R.string.PengramFontDefault;
        }
    }

    public static CharSequence name(int font) {
        return LocaleController.getString(nameRes(font));
    }

    /**
     * Есть ли семейство на этом устройстве.
     *
     * Android на незнакомое имя молча возвращает обычный sans-serif — и
     * выглядит это как «настройка не работает». Поэтому сравниваем результат
     * с дефолтным: совпал — значит семейства в системе нет.
     */
    public static boolean isAvailable(int font) {
        if (font == DEFAULT || font == SERIF || font == MONOSPACE) {
            return true;
        }
        if (font == SYSTEM) {
            try {
                final Typeface system = Typeface.create("sans-serif", Typeface.NORMAL);
                return system != null && !system.equals(Typeface.DEFAULT);
            } catch (Throwable e) {
                return false;
            }
        }
        if (font == PIXEL) {
            return pixelTypeface() != null;
        }
        final String family = family(font);
        if (family == null) {
            return false;
        }
        try {
            final Typeface typeface = Typeface.create(family, Typeface.NORMAL);
            return typeface != null && !typeface.equals(Typeface.DEFAULT) && !typeface.equals(Typeface.SANS_SERIF);
        } catch (Throwable e) {
            return false;
        }
    }

    /** список шрифтов, которые реально отличаются друг от друга на этом телефоне */
    public static int[] available() {
        if (availableCache != null) {
            return availableCache;
        }
        final ArrayList<Integer> list = new ArrayList<>();
        for (int font : ALL) {
            if (isAvailable(font)) {
                list.add(font);
            }
        }
        final int[] result = new int[list.size()];
        for (int a = 0; a < list.size(); a++) {
            result[a] = list.get(a);
        }
        availableCache = result;
        return result;
    }

    public static int indexOf(int font) {
        final int[] list = available();
        for (int a = 0; a < list.length; a++) {
            if (list[a] == font) {
                return a;
            }
        }
        return 0;
    }

    /** следующий доступный шрифт — для переключения тапом по превью */
    public static int next(int font) {
        final int[] list = available();
        if (list.length == 0) {
            return DEFAULT;
        }
        return list[(indexOf(font) + 1) % list.length];
    }

    private static Typeface pixelTypeface() {
        final String key = PIXEL + "#base";
        synchronized (cache) {
            final Typeface cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            if (ApplicationLoader.applicationContext == null) {
                return null;
            }
            try {
                final Typeface typeface = Typeface.createFromAsset(
                        ApplicationLoader.applicationContext.getAssets(),
                        "fonts/PixelifySans-Regular.ttf"
                );
                cache.put(key, typeface);
                return typeface;
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            }
        }
    }

    public static Typeface typeface(int font, int style) {
        if (font == DEFAULT) {
            try {
                return Typeface.create(Typeface.DEFAULT, style);
            } catch (Throwable ignore) {
                return Typeface.DEFAULT;
            }
        }
        if (font == PIXEL) {
            final Typeface base = pixelTypeface();
            if (base == null) {
                return null;
            }
            final String key = PIXEL + "#" + style;
            synchronized (cache) {
                final Typeface cached = cache.get(key);
                if (cached != null) {
                    return cached;
                }
                final Typeface styled = Typeface.create(base, style);
                if (styled != null) {
                    cache.put(key, styled);
                }
                return styled;
            }
        }
        final String family = family(font);
        if (family == null) {
            return null;
        }
        final String key = font + "#" + style;
        synchronized (cache) {
            final Typeface cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Typeface typeface = null;
        try {
            typeface = Typeface.create(family, style);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (typeface != null) {
            synchronized (cache) {
                cache.put(key, typeface);
            }
        }
        return typeface;
    }

    /** Returns true when a live view uses a built-in Pengram font family. */
    public static boolean isSelectableTypeface(Typeface candidate) {
        if (candidate == null) return false;
        for (int font : ALL) {
            for (int style = Typeface.NORMAL; style <= Typeface.BOLD_ITALIC; style++) {
                final Typeface typeface = typeface(font, style);
                if (typeface != null && (candidate == typeface || candidate.equals(typeface))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Typeface typeface(int font) {
        final Typeface typeface = typeface(font, Typeface.NORMAL);
        return typeface != null ? typeface : Typeface.DEFAULT;
    }
}
