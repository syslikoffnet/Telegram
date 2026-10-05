package org.telegram.messenger;

import android.text.TextUtils;

/**
 * Pengram: исполнитель из названия трека.
 *
 * У многих файлов теги пустые, а всё написано в одной строке:
 * «Пошлая Молли — Нимфоманка» или «02. Кино - Группа крови.mp3». Плеер в таком
 * случае показывает «Неизвестный исполнитель», хотя имя есть прямо в названии.
 *
 * Здесь очень осторожный разбор: делим строку только по тире с пробелами (или по
 * длинному тире), проверяем обе половины на вменяемость и, если хоть что-то
 * выглядит подозрительно, честно возвращаем null — тогда всё остаётся как было.
 * Разбор применяется ТОЛЬКО когда тег исполнителя пуст, так что настоящие теги
 * никогда не перетираются.
 */
public final class PengramMusicMeta {

    /** длиннее этого «исполнителя» уже не бывает — скорее всего, разрезали само название */
    private static final int MAX_ARTIST = 48;
    private static final int MIN_TITLE = 1;

    private static final String[] EXTENSIONS = {
            ".mp3", ".m4a", ".flac", ".wav", ".ogg", ".opus", ".aac", ".wma", ".alac", ".aiff"
    };

    private PengramMusicMeta() {
    }

    public static boolean enabled() {
        return PengramConfig.isMusicSmartArtist();
    }

    /** имя исполнителя из названия или null, если его там не видно */
    public static String artistFrom(String raw) {
        final String[] parts = split(raw);
        return parts == null ? null : parts[0];
    }

    /** название без приклеенного спереди исполнителя (или исходная строка) */
    public static String titleFrom(String raw) {
        final String[] parts = split(raw);
        return parts == null ? raw : parts[1];
    }

    /** разбор на пару «исполнитель, название»; null — разбирать нечего или небезопасно */
    private static String[] split(String raw) {
        if (!enabled() || TextUtils.isEmpty(raw)) {
            return null;
        }
        String value = raw.trim();
        value = stripExtension(value);
        value = stripLeadingNumber(value);
        final int at = separatorAt(value);
        if (at < 0) {
            return null;
        }
        final String artist = value.substring(0, at).trim();
        final String title = value.substring(at + 1).trim();
        if (!sane(artist, MAX_ARTIST) || title.length() < MIN_TITLE || title.length() > 200) {
            return null;
        }
        return new String[]{artist, title};
    }

    /**
     * Позиция разделителя. Длинное и среднее тире считаем разделителем всегда,
     * обычный дефис — только когда он окружён пробелами: иначе развалятся
     * названия вроде «Non-Stop» или «Ля-ля».
     */
    private static int separatorAt(String value) {
        for (int i = 1; i < value.length() - 1; i++) {
            final char c = value.charAt(i);
            if (c == '\u2014' || c == '\u2013' || c == '\u2015' || c == '\u2212') {
                return i;
            }
            if (c == '-' && value.charAt(i - 1) == ' ' && value.charAt(i + 1) == ' ') {
                return i;
            }
        }
        return -1;
    }

    /** «01. », «03 » и прочая нумерация в начале файла разделителем быть не должна */
    private static String stripLeadingNumber(String value) {
        int i = 0;
        while (i < value.length() && Character.isDigit(value.charAt(i))) {
            i++;
        }
        if (i == 0 || i > 3 || i >= value.length()) {
            return value;
        }
        int j = i;
        if (j < value.length() && (value.charAt(j) == '.' || value.charAt(j) == ')')) {
            j++;
        }
        if (j < value.length() && value.charAt(j) == ' ') {
            final String rest = value.substring(j).trim();
            return rest.isEmpty() ? value : rest;
        }
        return value;
    }

    private static String stripExtension(String value) {
        final String lower = value.toLowerCase(java.util.Locale.US);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return value.substring(0, value.length() - ext.length()).trim();
            }
        }
        return value;
    }

    /** в имени исполнителя должна быть хотя бы одна буква и ничего похожего на ссылку */
    private static boolean sane(String value, int max) {
        if (TextUtils.isEmpty(value) || value.length() > max) {
            return false;
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.toLowerCase(java.util.Locale.US).contains("http")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
