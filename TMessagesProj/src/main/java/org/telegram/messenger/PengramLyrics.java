package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pengram: хранилище текстов песен.
 * Текст привязывается к паре «исполнитель — название», поэтому один раз добавленный
 * текст подхватится для любой копии этого трека в любом чате.
 * Поддерживается обычный текст и формат LRC с таймкодами — [00:12.34] строка.
 */
public class PengramLyrics {

    private static final String PREFS = "pengram_lyrics";

    public static class Line {
        /** время начала строки в миллисекундах, -1 если текст без таймкодов */
        public final long time;
        public final String text;

        public Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** ключ хранения для сообщения с музыкой */
    public static String keyFor(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        String author = null;
        String title = null;
        try {
            author = messageObject.getMusicAuthor();
            title = messageObject.getMusicTitle();
        } catch (Throwable ignore) {
        }
        final StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(author)) {
            sb.append(author.trim().toLowerCase());
        }
        sb.append('|');
        if (!TextUtils.isEmpty(title)) {
            sb.append(title.trim().toLowerCase());
        }
        final String key = sb.toString();
        if ("|".equals(key)) {
            final String name = messageObject.getFileName();
            return TextUtils.isEmpty(name) ? null : name.toLowerCase();
        }
        return key;
    }

    public static String getRaw(String key) {
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        return prefs().getString(key, null);
    }

    public static boolean has(String key) {
        return !TextUtils.isEmpty(getRaw(key));
    }

    public static void setRaw(String key, String raw) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        final SharedPreferences.Editor editor = prefs().edit();
        if (TextUtils.isEmpty(raw)) {
            editor.remove(key);
        } else {
            editor.putString(key, raw);
        }
        editor.apply();
    }

    public static void clearAll() {
        prefs().edit().clear().apply();
    }

    public static int savedCount() {
        try {
            return prefs().getAll().size();
        } catch (Throwable e) {
            return 0;
        }
    }

    /** разбор текста: понимает LRC-таймкоды, пустые строки превращает в паузы */
    public static ArrayList<Line> parse(String raw) {
        final ArrayList<Line> result = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        final String[] parts = raw.replace("\r", "").split("\n");
        for (String part : parts) {
            String line = part;
            final ArrayList<Long> times = new ArrayList<>();
            while (true) {
                final String trimmed = line.trim();
                if (trimmed.length() < 3 || trimmed.charAt(0) != '[') {
                    line = trimmed;
                    break;
                }
                final int end = trimmed.indexOf(']');
                if (end < 0) {
                    line = trimmed;
                    break;
                }
                final long time = parseTime(trimmed.substring(1, end));
                if (time < 0) {
                    line = trimmed;
                    break;
                }
                times.add(time);
                line = trimmed.substring(end + 1);
            }
            final String text = line.trim();
            if (times.isEmpty()) {
                if (!TextUtils.isEmpty(text)) {
                    result.add(new Line(-1, text));
                }
            } else {
                for (long time : times) {
                    result.add(new Line(time, text));
                }
            }
        }
        boolean timed = false;
        for (Line line : result) {
            if (line.time >= 0) {
                timed = true;
                break;
            }
        }
        if (timed) {
            Collections.sort(result, (a, b) -> Long.compare(a.time, b.time));
        }
        return result;
    }

    /** «01:23.45» или «01:23» → миллисекунды, -1 если это не таймкод */
    private static long parseTime(String value) {
        if (TextUtils.isEmpty(value)) {
            return -1;
        }
        final int colon = value.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        try {
            final int minutes = Integer.parseInt(value.substring(0, colon).trim());
            final String rest = value.substring(colon + 1).trim().replace(',', '.');
            final double seconds = Double.parseDouble(rest);
            if (minutes < 0 || seconds < 0) {
                return -1;
            }
            return (long) (minutes * 60_000L + seconds * 1000.0);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static boolean hasTimings(List<Line> lines) {
        if (lines == null) {
            return false;
        }
        for (Line line : lines) {
            if (line.time >= 0) {
                return true;
            }
        }
        return false;
    }
}
