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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private static final long RETRY_AFTER = 30L * 60 * 1000;

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
        /** время начала каждого размеченного слова, мс (enhanced LRC) */
        public final long[] wordTimes;
        /** позиция первого символа соответствующего слова в тексте строки */
        public final int[] wordChars;

        public Line(long time, String text) {
            this(time, text, null, null);
        }

        public Line(long time, String text, long[] wordTimes, int[] wordChars) {
            this.time = time;
            this.text = text;
            this.wordTimes = wordTimes;
            this.wordChars = wordChars;
        }

        public boolean hasWords() {
            return wordTimes != null && wordTimes.length > 1;
        }
    }

    private static final HashMap<String, String> memory = new HashMap<>();
    private static final HashSet<String> loading = new HashSet<>();

    /** источники опрашиваются одновременно — кто первый, того и текст */
    private static final ExecutorService pool = Executors.newFixedThreadPool(4, runnable -> {
        final Thread thread = new Thread(runnable, "PengramLyrics");
        thread.setPriority(Thread.MIN_PRIORITY + 2);
        thread.setDaemon(true);
        return thread;
    });

    /** общий срок на весь поиск, мс */
    private static final long SEARCH_DEADLINE = 9000;

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
            editor.remove(key);
            // без сети запоминать неудачу нельзя: появится интернет — ищем снова сами
            boolean online = true;
            try {
                online = ApplicationLoader.isNetworkOnline();
            } catch (Throwable ignore) {
            }
            if (online) {
                editor.putLong("fail_" + key, System.currentTimeMillis());
            }
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
        final String path = pathOf(messageObject);
        pool.execute(() -> {
            String found = null;
            try {
                found = fetch(artist, title, duration, path);
                if (TextUtils.isEmpty(found) && !TextUtils.isEmpty(rawTitle) && !rawTitle.equalsIgnoreCase(title)) {
                    found = fetch(artist, cleanTitle(rawTitle), duration, null);
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

    /** заранее поискать текст для следующего трека, чтобы он открылся мгновенно */
    public static void prefetch(MessageObject messageObject) {
        final String key = keyFor(messageObject);
        if (TextUtils.isEmpty(key) || has(key)) {
            return;
        }
        synchronized (loading) {
            if (loading.contains(key)) {
                return;
            }
        }
        final long failed = prefs().getLong("fail_" + key, 0);
        if (failed > 0 && System.currentTimeMillis() - failed < RETRY_AFTER) {
            return;
        }
        request(messageObject, false, null);
    }

    /** путь к скачанному файлу трека — из него читаются встроенные в теги тексты */
    private static String pathOf(MessageObject messageObject) {
        try {
            if (messageObject == null || messageObject.messageOwner == null) {
                return null;
            }
            final java.io.File file = FileLoader.getInstance(messageObject.currentAccount)
                    .getPathToMessage(messageObject.messageOwner);
            return file != null && file.exists() && file.length() > 0 ? file.getAbsolutePath() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // --------------------------------------------------------- сдвиг по трекам

    /** личный сдвиг текста для конкретного трека, мс */
    /** личный сдвиг текущего трека держим в памяти: его спрашивают по нескольку раз за кадр */
    private static String offsetCacheKey;
    private static int offsetCacheValue;

    public static int getOffset(String key) {
        if (TextUtils.isEmpty(key)) {
            return PengramConfig.getLyricsOffset() + learnedOffset();
        }
        final int personal = getTrackOffset(key);
        // у трека нет личного сдвига — берём тот, который приложение выучило на прошлых песнях
        return (personal != 0 ? personal : learnedOffset()) + PengramConfig.getLyricsOffset();
    }

    /**
     * Средний сдвиг, который пользователь задавал руками. Пока он ничего не двигал — ноль,
     * а дальше новые треки сразу идут с его поправкой, и трогать кнопки больше не нужно.
     */
    public static int learnedOffset() {
        if (!PengramConfig.isLyricsAuto()) {
            return 0;
        }
        return prefs().getInt("learned_offset", 0);
    }

    /** запомнить поправку пользователя (скользящее среднее — одна случайность ничего не ломает) */
    private static void learnOffset(int value) {
        final int learned = prefs().getInt("learned_offset", 0);
        final int count = Math.min(10, prefs().getInt("learned_count", 0)) + 1;
        final int updated = Math.max(-3000, Math.min(3000, learned + (value - learned) / count));
        prefs().edit().putInt("learned_offset", updated).putInt("learned_count", count).apply();
    }

    public static int getTrackOffset(String key) {
        if (TextUtils.isEmpty(key)) {
            return 0;
        }
        if (key.equals(offsetCacheKey)) {
            return offsetCacheValue;
        }
        final int value = prefs().getInt("off_" + key, 0);
        offsetCacheKey = key;
        offsetCacheValue = value;
        return value;
    }

    public static void setTrackOffset(String key, int value) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        value = Math.max(-10000, Math.min(10000, value));
        learnOffset(value);
        offsetCacheKey = key;
        offsetCacheValue = value;
        if (value == 0) {
            prefs().edit().remove("off_" + key).apply();
        } else {
            prefs().edit().putInt("off_" + key, value).apply();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** результат одного источника */
    private static class Found {
        final String raw;
        final boolean synced;
        final boolean words;
        final int priority;

        Found(String raw, int priority) {
            this.raw = raw;
            this.synced = raw != null && raw.contains("[") && raw.indexOf(':') > 0 && hasTimings(parse(raw));
            this.words = raw != null && raw.contains("<");
            this.priority = priority;
        }

        int score() {
            return (words ? 0 : synced ? 10 : 100) + priority;
        }
    }

    /**
     * Опрашиваем все разрешённые источники сразу и забираем лучший ответ:
     * пословный караоке-текст лучше обычного синхронного, тот — лучше простого текста.
     * Встроенный в файл текст проверяется первым и возвращается мгновенно.
     */
    private static String fetch(String artist, String title, int duration, String path) {
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        final int source = PengramConfig.getLyricsSource();

        if (source == PengramConfig.LYRICS_SOURCE_AUTO || source == PengramConfig.LYRICS_SOURCE_FILE) {
            final String embedded = fetchEmbedded(path);
            if (!TextUtils.isEmpty(embedded)) {
                final Found found = new Found(embedded, 0);
                if (found.synced || source == PengramConfig.LYRICS_SOURCE_FILE) {
                    return embedded;
                }
            }
            if (source == PengramConfig.LYRICS_SOURCE_FILE) {
                return null;
            }
        }

        final ArrayList<Future<Found>> futures = new ArrayList<>();
        final ArrayList<Callable<Found>> tasks = new ArrayList<>();
        if (source == PengramConfig.LYRICS_SOURCE_AUTO || source == PengramConfig.LYRICS_SOURCE_LRCLIB) {
            tasks.add(() -> wrap(fetchLrclib(artist, title, duration), 1));
        }
        if (source == PengramConfig.LYRICS_SOURCE_AUTO || source == PengramConfig.LYRICS_SOURCE_MUSIXMATCH) {
            tasks.add(() -> wrap(fetchMusixmatch(artist, title, duration), 2));
        }
        if (source == PengramConfig.LYRICS_SOURCE_AUTO || source == PengramConfig.LYRICS_SOURCE_GENIUS) {
            tasks.add(() -> wrap(fetchGenius(artist, title), 3));
        }
        if (source == PengramConfig.LYRICS_SOURCE_AUTO) {
            tasks.add(() -> wrap(fetchTextyl(artist, title), 4));
            tasks.add(() -> wrap(fetchLyricsOvh(artist, title), 5));
        }
        for (Callable<Found> task : tasks) {
            try {
                futures.add(pool.submit(task));
            } catch (Throwable ignore) {
            }
        }

        final long deadline = System.currentTimeMillis() + SEARCH_DEADLINE;
        Found best = null;
        for (Future<Found> future : futures) {
            final long left = deadline - System.currentTimeMillis();
            Found value = null;
            try {
                value = left > 0 ? future.get(left, TimeUnit.MILLISECONDS) : future.isDone() ? future.get() : null;
            } catch (Throwable ignore) {
            }
            if (value == null) {
                continue;
            }
            if (best == null || value.score() < best.score()) {
                best = value;
            }
            if (best.words) {
                break;   // пословный текст — лучше уже не будет
            }
        }
        for (Future<Found> future : futures) {
            try {
                future.cancel(true);
            } catch (Throwable ignore) {
            }
        }
        if (best == null && (source == PengramConfig.LYRICS_SOURCE_AUTO)) {
            final String embedded = fetchEmbedded(path);
            if (!TextUtils.isEmpty(embedded)) {
                return embedded;
            }
        }
        return best == null ? null : best.raw;
    }

    private static Found wrap(String raw, int priority) {
        return TextUtils.isEmpty(raw) ? null : new Found(raw, priority);
    }

    /** текст прямо из тегов файла — быстрее всех и всегда совпадает с версией трека */
    private static String fetchEmbedded(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            final java.io.File file = new java.io.File(path);
            if (!file.exists() || file.length() <= 0) {
                return null;
            }
            final org.telegram.messenger.audioinfo.AudioInfo info =
                    org.telegram.messenger.audioinfo.AudioInfo.getAudioInfo(file);
            if (info == null) {
                return null;
            }
            final String lyrics = info.getLyrics();
            if (TextUtils.isEmpty(lyrics) || lyrics.trim().length() < 20) {
                return null;
            }
            return lyrics.replace("\r", "").trim();
        } catch (Throwable e) {
            return null;
        }
    }

    // --------------------------------------------------------------- LRCLIB

    /** несколько попыток подряд — от самой точной к самой широкой */
    private static String fetchLrclib(String artist, String title, int duration) {
        String result;
        if (!TextUtils.isEmpty(artist)) {
            result = parseRecord(get("/api/get?artist_name=" + enc(artist) + "&track_name=" + enc(title)
                    + (duration > 0 ? "&duration=" + duration : "")), duration);
            if (result != null) {
                return result;
            }
            if (duration > 0) {
                result = parseRecord(get("/api/get?artist_name=" + enc(artist) + "&track_name=" + enc(title)), duration);
                if (result != null) {
                    return result;
                }
            }
            result = parseList(get("/api/search?artist_name=" + enc(artist) + "&track_name=" + enc(title)), duration);
            if (result != null) {
                return result;
            }
        }
        result = parseList(get("/api/search?q=" + enc((TextUtils.isEmpty(artist) ? "" : artist + " ") + title)), duration);
        if (result != null) {
            return result;
        }
        return parseList(get("/api/search?track_name=" + enc(title)), duration);
    }

    // ------------------------------------------------------------ Musixmatch

    private static final String MXM_APP = "web-desktop-app-v1.0";
    private static String mxmToken;
    private static long mxmTokenTime;

    private static String musixmatchToken() {
        final long now = System.currentTimeMillis();
        if (!TextUtils.isEmpty(mxmToken) && now - mxmTokenTime < 12L * 60 * 60 * 1000) {
            return mxmToken;
        }
        final String saved = prefs().getString("mxm_token", null);
        final long savedTime = prefs().getLong("mxm_token_time", 0);
        if (!TextUtils.isEmpty(saved) && now - savedTime < 12L * 60 * 60 * 1000) {
            mxmToken = saved;
            mxmTokenTime = savedTime;
            return mxmToken;
        }
        try {
            final String json = getUrl("https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=" + MXM_APP + "&format=json");
            if (TextUtils.isEmpty(json)) {
                return null;
            }
            final JSONObject body = new JSONObject(json).optJSONObject("message");
            final JSONObject inner = body == null ? null : body.optJSONObject("body");
            final String token = inner == null ? null : inner.optString("user_token", null);
            if (TextUtils.isEmpty(token) || "UpgradeOnlyUpgradeOnlyUpgradeOnlyUpgradeOnly".equals(token)) {
                return null;
            }
            mxmToken = token;
            mxmTokenTime = now;
            prefs().edit().putString("mxm_token", token).putLong("mxm_token_time", now).apply();
            return token;
        } catch (Throwable e) {
            return null;
        }
    }

    /** официальные синхронизированные тексты от лейблов */
    private static String fetchMusixmatch(String artist, String title, int duration) {
        try {
            final String token = musixmatchToken();
            if (TextUtils.isEmpty(token)) {
                return null;
            }
            final StringBuilder url = new StringBuilder("https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get?format=json&namespace=lyrics_richsynched&subtitle_format=lrc&app_id=")
                    .append(MXM_APP).append("&usertoken=").append(enc(token))
                    .append("&q_track=").append(enc(title));
            if (!TextUtils.isEmpty(artist)) {
                url.append("&q_artist=").append(enc(artist));
            }
            if (duration > 0) {
                url.append("&q_duration=").append(duration).append("&f_subtitle_length=").append(duration);
            }
            final String json = getUrl(url.toString());
            if (TextUtils.isEmpty(json)) {
                return null;
            }
            final JSONObject root = new JSONObject(json).optJSONObject("message");
            final JSONObject body = root == null ? null : root.optJSONObject("body");
            final JSONObject macro = body == null ? null : body.optJSONObject("macro_calls");
            if (macro == null) {
                return null;
            }
            final JSONObject subtitles = macro.optJSONObject("track.subtitles.get");
            if (subtitles != null) {
                final JSONObject message = subtitles.optJSONObject("message");
                final JSONObject inner = message == null ? null : message.optJSONObject("body");
                final JSONArray list = inner == null ? null : inner.optJSONArray("subtitle_list");
                if (list != null && list.length() > 0) {
                    final JSONObject item = list.optJSONObject(0);
                    final JSONObject subtitle = item == null ? null : item.optJSONObject("subtitle");
                    final String text = subtitle == null ? null : subtitle.optString("subtitle_body", null);
                    if (!TextUtils.isEmpty(text) && text.trim().length() > 10) {
                        final int length = subtitle.optInt("subtitle_length", 0);
                        return withLength(text.replace("\r", "").trim(), length);
                    }
                }
            }
            final JSONObject plainCall = macro.optJSONObject("track.lyrics.get");
            if (plainCall != null) {
                final JSONObject message = plainCall.optJSONObject("message");
                final JSONObject inner = message == null ? null : message.optJSONObject("body");
                final JSONObject lyrics = inner == null ? null : inner.optJSONObject("lyrics");
                final String text = lyrics == null ? null : lyrics.optString("lyrics_body", null);
                if (!TextUtils.isEmpty(text) && text.trim().length() > 20) {
                    return text.replace("\r", "").replace("******* This Lyrics is NOT for Commercial use *******", "").trim();
                }
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Genius

    /** тексты от пользователей Genius: таймкодов нет, зато есть почти всё */
    private static String fetchGenius(String artist, String title) {
        try {
            final String query = (TextUtils.isEmpty(artist) ? "" : artist + " ") + title;
            final String json = getUrl("https://genius.com/api/search/multi?q=" + enc(query));
            if (TextUtils.isEmpty(json)) {
                return null;
            }
            final JSONObject response = new JSONObject(json).optJSONObject("response");
            final JSONArray sections = response == null ? null : response.optJSONArray("sections");
            if (sections == null) {
                return null;
            }
            String path = null;
            for (int a = 0; a < sections.length() && path == null; ++a) {
                final JSONObject section = sections.optJSONObject(a);
                if (section == null || !"song".equals(section.optString("type"))) {
                    continue;
                }
                final JSONArray hits = section.optJSONArray("hits");
                for (int b = 0; hits != null && b < hits.length() && b < 3; ++b) {
                    final JSONObject hit = hits.optJSONObject(b);
                    final JSONObject result = hit == null ? null : hit.optJSONObject("result");
                    if (result == null) {
                        continue;
                    }
                    final String url = result.optString("url", null);
                    if (!TextUtils.isEmpty(url)) {
                        path = url;
                        break;
                    }
                }
            }
            if (path == null) {
                return null;
            }
            final String html = getUrl(path);
            return html == null ? null : geniusLyrics(html);
        } catch (Throwable e) {
            return null;
        }
    }

    /** выдираем текст из блоков data-lyrics-container страницы Genius */
    private static String geniusLyrics(String html) {
        try {
            final StringBuilder out = new StringBuilder();
            int from = 0;
            while (true) {
                final int start = html.indexOf("data-lyrics-container=\"true\"", from);
                if (start < 0) {
                    break;
                }
                final int open = html.indexOf('>', start);
                if (open < 0) {
                    break;
                }
                final int close = html.indexOf("</div>", open);
                if (close < 0) {
                    break;
                }
                out.append(html, open + 1, close).append('\n');
                from = close + 6;
            }
            if (out.length() == 0) {
                return null;
            }
            String text = out.toString();
            text = text.replaceAll("(?i)<br\\s*/?>", "\n");
            text = text.replaceAll("(?s)<[^>]+>", "");
            text = text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
                    .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
            text = text.replaceAll("\\n{3,}", "\n\n").trim();
            return text.length() < 30 ? null : text;
        } catch (Throwable e) {
            return null;
        }
    }

    /** api.textyl.co отдаёт готовый список строк с секундами */
    private static String fetchTextyl(String artist, String title) {
        try {
            final String query = (TextUtils.isEmpty(artist) ? "" : artist + " ") + title;
            final String json = getUrl("https://api.textyl.co/api/lyrics?q=" + enc(query));
            if (TextUtils.isEmpty(json) || json.charAt(0) != '[') {
                return null;
            }
            final JSONArray array = new JSONArray(json);
            if (array.length() < 3) {
                return null;
            }
            final StringBuilder builder = new StringBuilder();
            for (int a = 0; a < array.length(); ++a) {
                final JSONObject item = array.optJSONObject(a);
                if (item == null) {
                    continue;
                }
                final String text = item.optString("lyrics", "").trim();
                if (TextUtils.isEmpty(text)) {
                    continue;
                }
                final int seconds = item.optInt("seconds", -1);
                if (seconds >= 0) {
                    builder.append(String.format(Locale.US, "[%02d:%02d.00]", seconds / 60, seconds % 60));
                }
                builder.append(text).append('\n');
            }
            final String result = builder.toString().trim();
            return TextUtils.isEmpty(result) ? null : result;
        } catch (Throwable e) {
            return null;
        }
    }

    /** api.lyrics.ovh — только обычный текст, зато там есть то, чего нет больше нигде */
    private static String fetchLyricsOvh(String artist, String title) {
        if (TextUtils.isEmpty(artist) || TextUtils.isEmpty(title)) {
            return null;
        }
        try {
            final String json = getUrl("https://api.lyrics.ovh/v1/" + enc(artist) + "/" + enc(title));
            if (TextUtils.isEmpty(json) || json.charAt(0) != '{') {
                return null;
            }
            final JSONObject object = new JSONObject(json);
            final String lyrics = object.isNull("lyrics") ? null : object.optString("lyrics", null);
            if (TextUtils.isEmpty(lyrics)) {
                return null;
            }
            final String cleaned = lyrics.replace("\r", "").trim();
            return cleaned.length() < 20 ? null : cleaned;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Throwable e) {
            return "";
        }
    }

    private static String get(String path) {
        return getUrl("https://lrclib.net" + path);
    }

    private static String getUrl(String address) {
        HttpURLConnection connection = null;
        try {
            final URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            final String host = url.getHost() == null ? "" : url.getHost();
            if (host.contains("musixmatch") || host.contains("genius")) {
                // этим сайтам нужен обычный браузерный агент, иначе они молчат
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                connection.setRequestProperty("Accept", "application/json, text/html;q=0.9,*/*;q=0.8");
                if (host.contains("musixmatch")) {
                    connection.setRequestProperty("Cookie", "x-mxm-token-guid=");
                }
            } else {
                connection.setRequestProperty("User-Agent", UA);
                connection.setRequestProperty("Accept", "application/json");
            }
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final InputStream stream = connection.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"), 8192);
            final StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
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

    private static String parseRecord(String json, int duration) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            final JSONObject record = new JSONObject(json);
            final String lyrics = lyricsOf(record);
            return lyrics == null ? null : withLength(lyrics, record.optInt("duration", 0));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Запоминаем, под какую длительность написаны таймкоды.
     * Для ускоренных и замедленных версий это единственный способ попасть в такт.
     */
    private static String withLength(String raw, int seconds) {
        if (TextUtils.isEmpty(raw) || seconds <= 0 || raw.contains("[length:")) {
            return raw;
        }
        return String.format(Locale.US, "[length:%02d:%02d.00]%n", seconds / 60, seconds % 60) + raw;
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
            if (best == null) {
                return null;
            }
            final String lyrics = lyricsOf(best);
            return lyrics == null ? null : withLength(lyrics, best.optInt("duration", 0));
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
        final long tagOffset = offsetOf(raw);
        final String[] parts = raw.replace("\r", "").split("\n");
        for (String part : parts) {
            String line = part;
            if (isMetaLine(line)) {
                continue;
            }
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
            // enhanced LRC: внутри строки встречаются пословные метки <00:12.34>
            final ArrayList<Long> wordTimes = new ArrayList<>();
            final ArrayList<Integer> wordChars = new ArrayList<>();
            final StringBuilder clean = new StringBuilder();
            for (int i = 0; i < line.length(); ) {
                final char c = line.charAt(i);
                if (c == '<') {
                    final int close = line.indexOf('>', i);
                    if (close > i) {
                        final long wordTime = parseTime(line.substring(i + 1, close));
                        if (wordTime >= 0) {
                            int at = clean.length();
                            while (at > 0 && clean.charAt(at - 1) == ' ') {
                                at--;
                            }
                            wordTimes.add(wordTime);
                            wordChars.add(Math.min(at, clean.length()));
                            i = close + 1;
                            continue;
                        }
                    }
                }
                clean.append(c);
                i++;
            }
            final String text = clean.toString().trim();
            long[] wt = null;
            int[] wc = null;
            if (wordTimes.size() > 1) {
                wt = new long[wordTimes.size()];
                wc = new int[wordTimes.size()];
                for (int i = 0; i < wordTimes.size(); ++i) {
                    wt[i] = wordTimes.get(i);
                    wc[i] = Math.max(0, Math.min(text.length(), wordChars.get(i)));
                }
            }
            if (times.isEmpty()) {
                if (!TextUtils.isEmpty(text)) {
                    result.add(new Line(wt != null ? wt[0] : -1, text, wt, wc));
                }
            } else {
                for (long time : times) {
                    result.add(new Line(time, text, wt, wc));
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
        if (timed && tagOffset != 0) {
            // [offset:] в самом файле — смещение всех строк
            for (int a = 0; a < result.size(); ++a) {
                final Line line = result.get(a);
                if (line.time < 0) {
                    continue;
                }
                long[] shifted = null;
                if (line.wordTimes != null) {
                    shifted = new long[line.wordTimes.length];
                    for (int b = 0; b < shifted.length; ++b) {
                        shifted[b] = Math.max(0, line.wordTimes[b] - tagOffset);
                    }
                }
                result.set(a, new Line(Math.max(0, line.time - tagOffset), line.text, shifted, line.wordChars));
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

    /** служебные строки LRC: [ar:], [ti:], [length:], [offset:] и прочее */
    private static boolean isMetaLine(String line) {
        final String trimmed = line == null ? "" : line.trim();
        if (trimmed.length() < 4 || trimmed.charAt(0) != '[') {
            return false;
        }
        final int close = trimmed.indexOf(']');
        if (close < 0 || close != trimmed.length() - 1) {
            return false;
        }
        final int colon = trimmed.indexOf(':');
        if (colon <= 1 || colon > close) {
            return false;
        }
        final String name = trimmed.substring(1, colon).toLowerCase(Locale.ROOT);
        switch (name) {
            case "ar": case "ti": case "al": case "au": case "by": case "re": case "ve":
            case "length": case "offset": case "tool": case "id": case "#":
                return true;
            default:
                return false;
        }
    }

    /** длительность, под которую написаны таймкоды, мс (0 — неизвестно) */
    public static long lengthOf(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return 0;
        }
        final int start = raw.indexOf("[length:");
        if (start < 0) {
            return 0;
        }
        final int end = raw.indexOf(']', start);
        if (end < 0) {
            return 0;
        }
        final long value = parseTime(raw.substring(start + 8, end));
        return value < 0 ? 0 : value;
    }

    /** [offset:+250] — сдвиг в миллисекундах прямо из файла */
    public static long offsetOf(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return 0;
        }
        final int start = raw.indexOf("[offset:");
        if (start < 0) {
            return 0;
        }
        final int end = raw.indexOf(']', start);
        if (end < 0) {
            return 0;
        }
        try {
            return Long.parseLong(raw.substring(start + 8, end).trim().replace("+", ""));
        } catch (Throwable e) {
            return 0;
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
