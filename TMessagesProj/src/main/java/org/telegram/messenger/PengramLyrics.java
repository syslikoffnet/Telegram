package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Pengram: тексты песен.
 * Текст ищется сам — по исполнителю, названию и длительности трека в открытой базе LRCLIB
 * (там же лежат и караоке-версии с таймкодами). Ничего вводить руками не нужно:
 * один раз найденный текст кладётся в локальный кэш и дальше открывается мгновенно и без сети.
 */
public class PengramLyrics {

    private static final String PREFS = "pengram_lyrics";
    private static final String UA = "Pengram for Android (https://github.com/syslikoffnet/Telegram)";

    /** сколько ждать до следующей попытки, если текст не нашёлся */
    private static final long RETRY_AFTER = 6L * 60 * 60 * 1000;

    public static final int STATE_NONE = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_FOUND = 2;
    public static final int STATE_NOT_FOUND = 3;

    public interface Callback {
        void onLyrics(String key, String raw, int state);
    }

    public static class Line {
        /** время начала строки в миллисекундах, -1 если текст без таймкодов */
        public final long time;
        public final String text;

        public Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private static final HashMap<String, String> memory = new HashMap<>();
    private static final HashSet<String> loading = new HashSet<>();

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ ключи

    /** ключ хранения для сообщения с музыкой */
    public static String keyFor(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        final String artist = artistOf(messageObject);
        final String title = titleOf(messageObject);
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        return (TextUtils.isEmpty(artist) ? "" : artist.toLowerCase(Locale.ROOT)) + "|" + title.toLowerCase(Locale.ROOT);
    }

    /** исполнитель: из тегов, а если их нет — из имени файла «Исполнитель - Название» */
    public static String artistOf(MessageObject messageObject) {
        String artist = null;
        try {
            artist = messageObject.getMusicAuthor(false);
        } catch (Throwable ignore) {
        }
        if (!TextUtils.isEmpty(artist)) {
            return cleanArtist(artist);
        }
        final String[] fromName = splitFileName(messageObject);
        return fromName == null ? null : fromName[0];
    }

    /** название трека: из тегов, иначе из имени файла */
    public static String titleOf(MessageObject messageObject) {
        String title = null;
        try {
            title = messageObject.getMusicTitle(false);
        } catch (Throwable ignore) {
        }
        if (!TextUtils.isEmpty(title)) {
            return cleanTitle(title);
        }
        final String[] fromName = splitFileName(messageObject);
        if (fromName != null) {
            return fromName[1];
        }
        String name = null;
        try {
            name = messageObject.getFileName();
        } catch (Throwable ignore) {
        }
        return TextUtils.isEmpty(name) ? null : cleanTitle(stripExtension(name));
    }

    private static String[] splitFileName(MessageObject messageObject) {
        String name = null;
        try {
            name = messageObject.getFileName();
        } catch (Throwable ignore) {
        }
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        name = stripExtension(name).replace('_', ' ').trim();
        // «01. Artist - Title» → артист и название
        name = name.replaceFirst("^\\s*\\d{1,3}\\s*[.)-]\\s*", "");
        final int dash = name.indexOf(" - ");
        if (dash <= 0 || dash + 3 >= name.length()) {
            return null;
        }
        final String artist = cleanArtist(name.substring(0, dash));
        final String title = cleanTitle(name.substring(dash + 3));
        if (TextUtils.isEmpty(artist) || TextUtils.isEmpty(title)) {
            return null;
        }
        return new String[]{artist, title};
    }

    private static String stripExtension(String name) {
        final int dot = name.lastIndexOf('.');
        return dot > 0 && name.length() - dot <= 5 ? name.substring(0, dot) : name;
    }

    /** убираем из названия всё, что мешает поиску: «(Official Video)», «[Lyrics]», «feat. …» */
    public static String cleanTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return title;
        }
        String result = title.replace('_', ' ');
        result = result.replaceAll("(?i)\\s*[\\[(][^\\[\\]()]*(official|lyric|lyrics|video|audio|visuali[sz]er|hd|hq|mv|clip|slowed|reverb|sped up|nightcore|remaster[^\\[\\]()]*)[^\\[\\]()]*[\\])]", "");
        result = result.replaceAll("(?i)\\s*[\\[(]\\s*(prod\\.?|produced by)[^\\[\\]()]*[\\])]", "");
        result = result.replaceAll("(?i)\\s*[-–—]\\s*(official\\s+)?(music\\s+)?(video|audio|lyrics?)\\s*$", "");
        result = result.replaceAll("(?i)\\s*(feat\\.?|ft\\.?|with)\\s+[^-\\[(]+$", "");
        result = result.replaceAll("\\s{2,}", " ").trim();
        result = result.replaceAll("[\\s,\\-–—]+$", "").trim();
        return TextUtils.isEmpty(result) ? title.trim() : result;
    }

    /** у исполнителя убираем «- Topic», «VEVO» и список соавторов */
    public static String cleanArtist(String artist) {
        if (TextUtils.isEmpty(artist)) {
            return artist;
        }
        String result = artist.replace('_', ' ');
        result = result.replaceAll("(?i)\\s*-\\s*topic\\s*$", "");
        result = result.replaceAll("(?i)vevo\\s*$", "");
        final int cut = indexOfAny(result, new String[]{" feat.", " feat ", " ft.", " ft ", " & ", ", ", " x ", " vs "});
        if (cut > 0) {
            result = result.substring(0, cut);
        }
        result = result.replaceAll("\\s{2,}", " ").trim();
        return TextUtils.isEmpty(result) ? artist.trim() : result;
    }

    private static int indexOfAny(String text, String[] parts) {
        int best = -1;
        final String lower = text.toLowerCase(Locale.ROOT);
        for (String part : parts) {
            final int index = lower.indexOf(part);
            if (index > 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ кэш

    public static String getCached(String key) {
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        final String cached = memory.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        final String saved = prefs().getString(key, null);
        if (saved != null) {
            memory.put(key, saved);
        }
        return TextUtils.isEmpty(saved) ? null : saved;
    }

    public static boolean has(String key) {
        return !TextUtils.isEmpty(getCached(key));
    }

    public static int getState(String key) {
        if (TextUtils.isEmpty(key)) {
            return STATE_NONE;
        }
        if (has(key)) {
            return STATE_FOUND;
        }
        synchronized (loading) {
            if (loading.contains(key)) {
                return STATE_LOADING;
            }
        }
        final long failed = prefs().getLong("fail_" + key, 0);
        return failed > 0 && System.currentTimeMillis() - failed < RETRY_AFTER ? STATE_NOT_FOUND : STATE_NONE;
    }

    private static void store(String key, String raw) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        final SharedPreferences.Editor editor = prefs().edit();
        if (TextUtils.isEmpty(raw)) {
            editor.remove(key).putLong("fail_" + key, System.currentTimeMillis());
            memory.remove(key);
        } else {
            editor.putString(key, raw).remove("fail_" + key);
            memory.put(key, raw);
        }
        editor.apply();
    }

    public static void clearAll() {
        memory.clear();
        prefs().edit().clear().apply();
    }

    public static int savedCount() {
        try {
            int count = 0;
            for (String key : prefs().getAll().keySet()) {
                if (!key.startsWith("fail_")) {
                    count++;
                }
            }
            return count;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ поиск

    /**
     * Запросить текст для трека. Если он уже в кэше — колбэк придёт сразу,
     * иначе уйдёт запрос в сеть, а колбэк вызовется на главном потоке, когда ответ придёт.
     */
    public static void request(MessageObject messageObject, Callback callback) {
        request(messageObject, false, callback);
    }

    public static void request(MessageObject messageObject, boolean force, Callback callback) {
        final String key = keyFor(messageObject);
        if (TextUtils.isEmpty(key)) {
            if (callback != null) {
                callback.onLyrics(key, null, STATE_NOT_FOUND);
            }
            return;
        }
        final String cached = getCached(key);
        if (!force && !TextUtils.isEmpty(cached)) {
            if (callback != null) {
                callback.onLyrics(key, cached, STATE_FOUND);
            }
            return;
        }
        if (!force) {
            final long failed = prefs().getLong("fail_" + key, 0);
            if (failed > 0 && System.currentTimeMillis() - failed < RETRY_AFTER) {
                if (callback != null) {
                    callback.onLyrics(key, null, STATE_NOT_FOUND);
                }
                return;
            }
        }
        synchronized (loading) {
            if (loading.contains(key)) {
                if (callback != null) {
                    callback.onLyrics(key, null, STATE_LOADING);
                }
                return;
            }
            loading.add(key);
        }
        if (callback != null) {
            callback.onLyrics(key, null, STATE_LOADING);
        }

        final String artist = artistOf(messageObject);
        final String title = titleOf(messageObject);
        int durationSec = 0;
        try {
            durationSec = (int) messageObject.getDuration();
        } catch (Throwable ignore) {
        }
        final int duration = durationSec;
        final String rawTitle = safe(messageObject.getMusicTitle());
        Utilities.globalQueue.postRunnable(() -> {
            String found = null;
            try {
                found = fetch(artist, title, duration);
                if (TextUtils.isEmpty(found) && !TextUtils.isEmpty(rawTitle) && !rawTitle.equalsIgnoreCase(title)) {
                    found = fetch(artist, cleanTitle(rawTitle), duration);
                }
            } catch (Throwable ignore) {
            }
            final String result = found;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (loading) {
                    loading.remove(key);
                }
                store(key, result);
                if (callback != null) {
                    callback.onLyrics(key, result, TextUtils.isEmpty(result) ? STATE_NOT_FOUND : STATE_FOUND);
                }
            });
        });
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** несколько попыток подряд — от самой точной к самой широкой */
    private static String fetch(String artist, String title, int duration) {
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        String result;
        if (!TextUtils.isEmpty(artist)) {
            // 1. точное совпадение с длительностью — самый качественный вариант
            result = parseRecord(get("/api/get?artist_name=" + enc(artist) + "&track_name=" + enc(title)
                    + (duration > 0 ? "&duration=" + duration : "")));
            if (result != null) {
                return result;
            }
            // 2. то же самое, но без привязки к длительности
            if (duration > 0) {
                result = parseRecord(get("/api/get?artist_name=" + enc(artist) + "&track_name=" + enc(title)));
                if (result != null) {
                    return result;
                }
            }
            // 3. поиск по полям — берём ближайшую по длительности запись
            result = parseList(get("/api/search?artist_name=" + enc(artist) + "&track_name=" + enc(title)), duration);
            if (result != null) {
                return result;
            }
        }
        // 4. общий поиск строкой
        result = parseList(get("/api/search?q=" + enc((TextUtils.isEmpty(artist) ? "" : artist + " ") + title)), duration);
        if (result != null) {
            return result;
        }
        // 5. совсем широкий поиск только по названию
        return parseList(get("/api/search?track_name=" + enc(title)), duration);
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Throwable e) {
            return "";
        }
    }

    private static String get(String path) {
        HttpURLConnection connection = null;
        try {
            final URL url = new URL("https://lrclib.net" + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final InputStream stream = connection.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"), 8192);
            final StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                if (builder.length() > 2_000_000) {
                    break;
                }
            }
            reader.close();
            return builder.toString();
        } catch (Throwable e) {
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static String parseRecord(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            return lyricsOf(new JSONObject(json));
        } catch (Throwable e) {
            return null;
        }
    }

    private static String parseList(String json, int duration) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            final JSONArray array = new JSONArray(json);
            JSONObject best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int a = 0; a < array.length() && a < 20; ++a) {
                final JSONObject item = array.optJSONObject(a);
                if (item == null || lyricsOf(item) == null) {
                    continue;
                }
                int score = 0;
                if (duration > 0) {
                    score = Math.abs(item.optInt("duration", 0) - duration);
                }
                if (item.isNull("syncedLyrics")) {
                    score += 30; // текст без таймкодов — хуже караоке
                }
                score += a; // порядок выдачи тоже что-то значит
                if (score < bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
            return best == null ? null : lyricsOf(best);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String lyricsOf(JSONObject record) {
        if (record == null || record.optBoolean("instrumental", false)) {
            return null;
        }
        final String synced = record.isNull("syncedLyrics") ? null : record.optString("syncedLyrics", null);
        if (!TextUtils.isEmpty(synced) && synced.trim().length() > 2) {
            return synced;
        }
        final String plain = record.isNull("plainLyrics") ? null : record.optString("plainLyrics", null);
        if (!TextUtils.isEmpty(plain) && plain.trim().length() > 2) {
            return plain;
        }
        return null;
    }

    // ------------------------------------------------------------------ разбор

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
            // пустые строки в конце только мешают
            while (!result.isEmpty() && TextUtils.isEmpty(result.get(result.size() - 1).text)) {
                result.remove(result.size() - 1);
            }
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
