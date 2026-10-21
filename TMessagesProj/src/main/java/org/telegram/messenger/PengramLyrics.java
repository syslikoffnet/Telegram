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

    /** кого будить, когда любой поиск текста завершился */
    private static final ArrayList<Runnable> globalListeners = new ArrayList<>();

    public static void addListener(Runnable listener) {
        if (listener != null && !globalListeners.contains(listener)) {
            globalListeners.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        globalListeners.remove(listener);
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
    /** кто ещё ждёт этот же текст: ответ придёт всем, а не только первому спросившему */
    private static final HashMap<String, ArrayList<Callback>> waiters = new HashMap<>();

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

    /**
     * Один и тот же текст не может принадлежать двум разным трекам.
     * Если источник уже отдавал эти же слова для другой песни — это чужой текст,
     * и показывать его нельзя (именно так «одна и та же песня» налипала на всё подряд).
     */
    private static String guardDuplicate(String key, String raw) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(raw)) {
            return raw;
        }
        try {
            final String hash = "txt_" + hashOf(raw);
            final String owner = prefs().getString(hash, null);
            if (!TextUtils.isEmpty(owner) && !owner.equals(key) && !sameTrackKey(owner, key)) {
                log("отсев — такой же текст уже принадлежит треку «" + owner + "»");
                return null;
            }
            prefs().edit().putString(hash, key).apply();
        } catch (Throwable ignore) {
        }
        return raw;
    }

    /**
     * Один и тот же трек часто приходит с разными ключами: у одного файла теги есть,
     * у другого исполнитель вытащен из имени файла. Считаем такие ключи одним треком,
     * иначе защита от дублей отбирает текст у законного владельца.
     */
    private static boolean sameTrackKey(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) {
            return false;
        }
        final int sepA = a.indexOf('|');
        final int sepB = b.indexOf('|');
        if (sepA < 0 || sepB < 0) {
            return false;
        }
        final String titleA = a.substring(sepA + 1).trim();
        final String titleB = b.substring(sepB + 1).trim();
        if (titleA.isEmpty() || titleB.isEmpty() || !titleA.equals(titleB)) {
            return false;
        }
        final String artistA = a.substring(0, sepA).trim();
        final String artistB = b.substring(0, sepB).trim();
        // один из ключей без исполнителя либо исполнитель вложен в другой
        return artistA.isEmpty() || artistB.isEmpty()
                || artistA.contains(artistB) || artistB.contains(artistA);
    }

    private static void store(String key, String raw) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        final SharedPreferences.Editor editor = prefs().edit();
        if (TextUtils.isEmpty(raw)) {
            // Уже найденный когда-то текст НЕ удаляем: неудачный повторный поиск —
            // это не повод терять то, что человек уже слушал. Именно так тексты
            // пропадали у ранее прослушанных треков.
            final boolean hadText = !TextUtils.isEmpty(prefs().getString(key, null));
            if (!hadText) {
                editor.remove(key);
                memory.remove(key);
            }
            // без сети запоминать неудачу нельзя: появится интернет — ищем снова сами
            boolean online = true;
            try {
                online = ApplicationLoader.isNetworkOnline();
            } catch (Throwable ignore) {
            }
            if (online && !hadText) {
                editor.putLong("fail_" + key, System.currentTimeMillis());
            }
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

    /** служебная запись склада, а не сохранённый текст песни */
    private static boolean isServiceKey(String key) {
        return key == null
                || key.startsWith("fail_")     // отметка о неудачном поиске
                || key.startsWith("off_")      // личный сдвиг синхронизации
                || key.startsWith("rej_")      // забракованный вручную текст
                || key.startsWith("txt_")      // владелец текста (защита от дублей)
                || key.startsWith("learned")   // выученная поправка
                || key.equals("cache_version");
    }

    public static int savedCount() {
        try {
            int count = 0;
            for (java.util.Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
                // Раньше сюда попадали все служебные записи, и счётчик рос сам по себе,
                // даже когда ни одного нового текста не сохранялось.
                if (isServiceKey(entry.getKey())) {
                    continue;
                }
                final Object value = entry.getValue();
                if (value instanceof String && !((String) value).isEmpty()) {
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

    /**
     * Прошлые версии складывали в кэш и чужие тексты — один раз чистим склад,
     * сохраняя личные сдвиги и выученную поправку.
     */
    private static boolean checkedCache;

    private static void ensureFreshCache() {
        if (checkedCache) {
            return;
        }
        checkedCache = true;
        try {
            final SharedPreferences p = prefs();
            if (p.getInt("cache_version", 1) >= 6) {
                return;
            }
            final SharedPreferences.Editor editor = p.edit();
            for (String name : new ArrayList<>(p.getAll().keySet())) {
                if (name.startsWith("off_") || name.startsWith("learned") || name.startsWith("rej_")) {
                    continue;
                }
                editor.remove(name);
            }
            editor.putInt("cache_version", 6).apply();
            memory.clear();
        } catch (Throwable ignore) {
        }
    }

    public static void request(MessageObject messageObject, boolean force, Callback callback) {
        ensureFreshCache();
        final String key = keyFor(messageObject);
        if (TextUtils.isEmpty(key)) {
            if (callback != null) {
                callback.onLyrics(key, null, STATE_NOT_FOUND);
            }
            return;
        }
        int cachedDuration = 0;
        try {
            cachedDuration = (int) messageObject.getDuration();
        } catch (Throwable ignore) {
        }
        final String cached = getCached(key);
        if (!force && !TextUtils.isEmpty(cached)) {
            // старая запись могла быть мусором: проверяем её теми же воротами
            if (coverageGate(cached, cachedDuration)) {
                if (callback != null) {
                    callback.onLyrics(key, cached, STATE_FOUND);
                }
                return;
            }
            // Запись подозрительная (например, у этого файла другая длительность), поэтому
            // ищем заново — но старый текст оставляем на складе: если ничего лучше не
            // найдётся, пусть лучше будет он, чем пустой экран.
            log("кэш: запись для «" + key + "» не прошла проверку, ищем заново");
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
                // поиск уже идёт — встаём в очередь, чтобы получить результат вместе со всеми
                if (callback != null) {
                    ArrayList<Callback> list = waiters.get(key);
                    if (list == null) {
                        list = new ArrayList<>();
                        waiters.put(key, list);
                    }
                    list.add(callback);
                    callback.onLyrics(key, null, STATE_LOADING);
                }
                return;
            }
            loading.add(key);
            if (callback != null) {
                ArrayList<Callback> list = waiters.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    waiters.put(key, list);
                }
                list.add(callback);
            }
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
        final String rejected = prefs().getString("rej_" + key, null);
        pool.execute(() -> {
            String found = null;
            try {
                found = fetch(artist, title, duration, path, rejected);
                if (TextUtils.isEmpty(found) && !TextUtils.isEmpty(rawTitle) && !rawTitle.equalsIgnoreCase(title)) {
                    found = fetch(artist, cleanTitle(rawTitle), duration, null, rejected);
                }
            } catch (Throwable ignore) {
            }
            final String result = guardDuplicate(key, found);
            AndroidUtilities.runOnUIThread(() -> {
                final ArrayList<Callback> list;
                synchronized (loading) {
                    loading.remove(key);
                    list = waiters.remove(key);
                }
                store(key, result);
                final int state = TextUtils.isEmpty(result) ? STATE_NOT_FOUND : STATE_FOUND;
                if (list != null) {
                    for (int a = 0; a < list.size(); ++a) {
                        try {
                            list.get(a).onLyrics(key, result, state);
                        } catch (Throwable ignore) {
                        }
                    }
                }
                for (int a = 0; a < globalListeners.size(); ++a) {
                    try {
                        globalListeners.get(a).run();
                    } catch (Throwable ignore) {
                    }
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
    // --------------------------------------------------------- журнал подбора

    /** последние решения подбора: по ним видно, почему текст тот или не тот */
    private static final ArrayList<String> debugLines = new ArrayList<>();

    static void log(String message) {
        try {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("pengram-lyrics: " + message);
            }
        } catch (Throwable ignore) {
        }
        synchronized (debugLines) {
            debugLines.add(message);
            while (debugLines.size() > 120) {
                debugLines.remove(0);
            }
        }
    }

    public static ArrayList<String> debugLog() {
        synchronized (debugLines) {
            return new ArrayList<>(debugLines);
        }
    }

    // --------------------------------------------------------- проверка совпадения

    /** приводим название к сравнимому виду: без скобок, знаков и регистра */
    private static String norm(String value) {
        if (value == null) {
            return "";
        }
        String text = value.toLowerCase(Locale.ROOT);
        text = text.replaceAll("\\([^)]*\\)", " ");
        text = text.replaceAll("\\[[^\\]]*\\]", " ");
        text = text.replaceAll("feat\\.?|ft\\.?|prod\\.?|official|remastered|lyrics|audio|video", " ");
        final StringBuilder out = new StringBuilder(text.length());
        for (int a = 0; a < text.length(); ++a) {
            final char c = text.charAt(a);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
        }
        return out.toString().trim();
    }

    /** насколько похожи две строки: доля общих слов плюс вхождение целиком */
    private static float similar(String a, String b) {
        final String left = norm(a);
        final String right = norm(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0f;
        }
        if (left.equals(right)) {
            return 1f;
        }
        if (left.contains(right) || right.contains(left)) {
            return 0.9f;
        }
        final String[] leftWords = left.split(" ");
        final String[] rightWords = right.split(" ");
        int common = 0;
        for (String word : leftWords) {
            if (word.length() < 2) {
                continue;
            }
            for (String other : rightWords) {
                if (word.equals(other)) {
                    common++;
                    break;
                }
            }
        }
        final int total = Math.max(leftWords.length, rightWords.length);
        return total == 0 ? 0f : common / (float) total;
    }

    /** доля кириллицы в тексте — ею ловим «русский трек, английский текст» */
    private static float cyrillicRatio(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0f;
        }
        int letters = 0;
        int cyrillic = 0;
        final int limit = Math.min(text.length(), 4000);
        for (int a = 0; a < limit; ++a) {
            final char c = text.charAt(a);
            if (!Character.isLetter(c)) {
                continue;
            }
            letters++;
            if (c >= 0x0400 && c <= 0x04FF) {
                cyrillic++;
            }
        }
        return letters == 0 ? 0f : cyrillic / (float) letters;
    }

    /**
     * Текст на другом языке, чем название трека, почти всегда означает,
     * что нашли чужую песню. Такой ответ лучше выбросить, чем показать.
     */
    private static boolean sameLanguage(String artist, String title, String lyrics) {
        // язык берём по названию: у русского исполнителя песня вполне может быть
        // на английском, и отбрасывать её из-за имени артиста нельзя
        final float wanted = cyrillicRatio(title);
        final float got = cyrillicRatio(lyrics);
        if (wanted > 0.5f && got < 0.2f) {
            return false;
        }
        return !(wanted < 0.15f && got > 0.6f);
    }

    /** совпали ли исполнитель, название и длительность найденной записи */
    private static boolean looksLikeMatch(String wantArtist, String wantTitle,
                                          String gotArtist, String gotTitle,
                                          int wantDuration, int gotDuration) {
        final float titleScore = similar(wantTitle, gotTitle);
        if (titleScore < 0.6f) {
            return false;
        }
        if (!TextUtils.isEmpty(wantArtist) && !TextUtils.isEmpty(gotArtist)) {
            if (similar(wantArtist, gotArtist) < 0.45f && titleScore < 0.95f) {
                return false;
            }
        }
        if (wantDuration > 0 && gotDuration > 0 && Math.abs(wantDuration - gotDuration) > 20) {
            return false;
        }
        return true;
    }

    /** слова названия после нормализации */
    private static ArrayList<String> tokens(String value) {
        final ArrayList<String> out = new ArrayList<>();
        final String text = norm(value);
        if (text.isEmpty()) {
            return out;
        }
        for (String word : text.split(" ")) {
            if (word.length() > 1 || Character.isDigit(word.charAt(0))) {
                out.add(word);
            }
        }
        return out;
    }

    private static int commonTokens(ArrayList<String> a, ArrayList<String> b) {
        int common = 0;
        for (String word : a) {
            if (b.contains(word)) {
                common++;
            }
        }
        return common;
    }

    /** названия совпадают как множества слов, а не как подстроки */
    private static boolean titleGate(String wantTitle, String gotTitle) {
        final ArrayList<String> want = tokens(wantTitle);
        final ArrayList<String> got = tokens(gotTitle);
        if (want.isEmpty() || got.isEmpty()) {
            return false;
        }
        final int common = commonTokens(want, got);
        final int small = Math.min(want.size(), got.size());
        final int big = Math.max(want.size(), got.size());
        if (common == 0) {
            return false;
        }
        // все слова меньшего названия должны найтись в большем, и размеры не должны сильно расходиться
        return common >= small && big - small <= 2;
    }

    /** у исполнителей должно быть хотя бы одно общее слово */
    private static boolean artistGate(String wantArtist, String gotArtist) {
        if (TextUtils.isEmpty(wantArtist)) {
            return true;
        }
        if (TextUtils.isEmpty(gotArtist)) {
            return false;
        }
        if (norm(wantArtist).equals(norm(gotArtist))) {
            return true;
        }
        return commonTokens(tokens(wantArtist), tokens(gotArtist)) >= 1;
    }

    /** допустимое расхождение длительности: пять секунд или пять процентов */
    private static boolean durationGate(int wantSeconds, int gotSeconds) {
        if (wantSeconds <= 0 || gotSeconds <= 0) {
            return true;
        }
        final float allowed = Math.max(5f, wantSeconds * 0.05f);
        return Math.abs(wantSeconds - gotSeconds) <= allowed;
    }

    /**
     * Текст должен покрывать песню: пара строк на трёхминутный трек — это мусор,
     * а не слова песни.
     */
    private static boolean coverageGate(String raw, int wantSeconds) {
        if (TextUtils.isEmpty(raw)) {
            return false;
        }
        final ArrayList<Line> parsed = parse(raw);
        int textLines = 0;
        int letters = 0;
        long first = -1;
        long last = -1;
        for (int a = 0; a < parsed.size(); ++a) {
            final Line line = parsed.get(a);
            if (line.text != null && line.text.trim().length() > 0) {
                textLines++;
                letters += line.text.trim().length();
            }
            if (line.time >= 0) {
                if (first < 0) {
                    first = line.time;
                }
                last = Math.max(last, line.time);
            }
        }
        if (textLines < 4 || letters < 80) {
            return false;
        }
        if (wantSeconds > 0 && first >= 0 && last > first) {
            final float span = (last - first) / 1000f;
            return span >= wantSeconds * 0.3f;
        }
        return true;
    }

    /** окончательный фильтр: пустое, слишком короткое и чужое по языку не пропускаем */
    private static String accept(String raw, String artist, String title, int duration,
                                 String rejected, String source, boolean verified) {
        if (TextUtils.isEmpty(raw) || raw.trim().length() < 24) {
            return null;
        }
        // язык проверяем только у источников, которые не сверяли сам трек:
        // у русских исполнителей сплошь и рядом английские названия
        if (!verified && !sameLanguage(artist, title, raw)) {
            log(source + ": отсев — язык текста не совпал с названием");
            return null;
        }
        if (!coverageGate(raw, duration)) {
            log(source + ": отсев — текст слишком короткий для трека на " + duration + " с");
            return null;
        }
        if (!TextUtils.isEmpty(rejected) && rejected.equals(hashOf(raw))) {
            log(source + ": отсев — этот текст уже забракован вручную");
            return null;   // этот текст пользователь уже забраковал
        }
        return raw;
    }

    static String hashOf(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        int hash = 7;
        final String text = raw.trim();
        for (int a = 0; a < text.length(); ++a) {
            hash = hash * 31 + text.charAt(a);
        }
        return Integer.toHexString(hash);
    }

    /**
     * «Текст не тот»: забываем найденное, запоминаем отпечаток, чтобы второй раз
     * его не подсунуть, и ищем заново.
     */
    public static void reject(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        final String cached = getCached(key);
        final SharedPreferences.Editor editor = prefs().edit();
        if (!TextUtils.isEmpty(cached)) {
            editor.putString("rej_" + key, hashOf(cached));
            editor.remove("txt_" + hashOf(cached));
        }
        editor.remove(key).remove("fail_" + key).apply();
        memory.remove(key);
    }

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
    private static String fetch(String artist, String title, int duration, String path, String rejected) {
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
            tasks.add(() -> wrap(accept(fetchLrclib(artist, title, duration), artist, title, duration, rejected, "lrclib", true), 1));
        }
        if (source == PengramConfig.LYRICS_SOURCE_AUTO || source == PengramConfig.LYRICS_SOURCE_MUSIXMATCH) {
            tasks.add(() -> wrap(accept(fetchMusixmatch(artist, title, duration), artist, title, duration, rejected, "musixmatch", true), 2));
        }
        if (source == PengramConfig.LYRICS_SOURCE_AUTO || source == PengramConfig.LYRICS_SOURCE_GENIUS) {
            tasks.add(() -> wrap(accept(fetchGenius(artist, title), artist, title, duration, rejected, "genius", true), 3));
        }
        if (source == PengramConfig.LYRICS_SOURCE_AUTO && !TextUtils.isEmpty(artist)) {
            // lyrics.ovh ищет строго по паре «исполнитель/название», поэтому чужую
            // песню отдать не может; поиск «по строке запроса» мы больше не зовём вовсе
            tasks.add(() -> wrap(accept(fetchLyricsOvh(artist, title), artist, title, duration, rejected, "lyricsovh", false), 5));
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
        log("итог для «" + artist + " — " + title + "»: "
                + (best == null ? "текст не найден" : "источник №" + best.priority
                + (best.words ? ", пословный" : best.synced ? ", с таймингами" : ", обычный")));
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
    /** один кандидат из каталога LRCLIB со всеми полями, по которым его можно проверить */
    private static final class Candidate {
        String artist = "";
        String title = "";
        String lyrics;
        int duration;
        boolean synced;
        int delta = Integer.MAX_VALUE;
    }

    /**
     * Подбор текста в LRCLIB: сначала точный запрос, потом до двух поисков
     * по вариантам названия. Каждый кандидат проходит жёсткие ворота —
     * длительность, название, исполнитель, покрытие текста. Не прошедшие
     * отсеиваются молча, «первый из выдачи» не берётся никогда.
     */
    private static String fetchLrclib(String artist, String title, int duration) {
        final ArrayList<Candidate> candidates = new ArrayList<>();
        int requests = 0;

        // 1. точный запрос — он и должен срабатывать на нормальных треках
        if (!TextUtils.isEmpty(artist)) {
            requests++;
            collectOne(get("/api/get?artist_name=" + enc(artist) + "&track_name=" + enc(title)
                    + (duration > 0 ? "&duration=" + duration : "")), candidates);
        }

        // 2. до двух поисков по вариантам написания
        final ArrayList<String[]> variants = queryVariants(artist, title);
        for (int a = 0; a < variants.size() && requests < 3; ++a) {
            final String[] variant = variants.get(a);
            requests++;
            final String query = TextUtils.isEmpty(variant[0])
                    ? "/api/search?track_name=" + enc(variant[1])
                    : "/api/search?artist_name=" + enc(variant[0]) + "&track_name=" + enc(variant[1]);
            collectMany(get(query), candidates);
        }

        log("lrclib: запросов " + requests + ", кандидатов " + candidates.size()
                + " для «" + artist + " — " + title + "» (" + duration + " с)");

        Candidate best = null;
        for (int a = 0; a < candidates.size(); ++a) {
            final Candidate candidate = candidates.get(a);
            final String reason = rejectReason(candidate, artist, title, duration);
            if (reason != null) {
                log("  × " + candidate.artist + " — " + candidate.title
                        + " (" + candidate.duration + " с): " + reason);
                continue;
            }
            candidate.delta = duration > 0 && candidate.duration > 0
                    ? Math.abs(duration - candidate.duration) : 0;
            log("  ✓ " + candidate.artist + " — " + candidate.title
                    + " (" + candidate.duration + " с), Δ=" + candidate.delta
                    + (candidate.synced ? ", с таймингами" : ", без таймингов"));
            if (best == null
                    || candidate.delta < best.delta
                    || (candidate.delta == best.delta && candidate.synced && !best.synced)) {
                best = candidate;
            }
        }
        if (best == null) {
            // длительность в Telegram бывает неточной; если всё остальное совпало —
            // берём кандидата с самой близкой длительностью, но честно пишем об этом
            for (int a = 0; a < candidates.size(); ++a) {
                final Candidate candidate = candidates.get(a);
                if (TextUtils.isEmpty(candidate.lyrics)
                        || !titleGate(title, candidate.title)
                        || !artistGate(artist, candidate.artist)
                        || !coverageGate(candidate.lyrics, 0)) {
                    continue;
                }
                candidate.delta = duration > 0 && candidate.duration > 0
                        ? Math.abs(duration - candidate.duration) : 0;
                if (best == null || candidate.delta < best.delta
                        || (candidate.delta == best.delta && candidate.synced && !best.synced)) {
                    best = candidate;
                }
            }
            if (best != null) {
                log("lrclib: взят по названию и исполнителю, длительность расходится на "
                        + best.delta + " с");
            }
        }
        if (best == null) {
            log("lrclib: подходящих нет — честный «текст не найден»");
            return null;
        }
        log("lrclib: выбран " + best.artist + " — " + best.title + " (Δ=" + best.delta + ")");
        return withLength(best.lyrics, best.duration);
    }

    /** варианты написания: без скобок и ремикс-хвостов, без feat., и «дружелюбная» форма */
    private static ArrayList<String[]> queryVariants(String artist, String title) {
        final ArrayList<String[]> out = new ArrayList<>();
        addVariant(out, artist, title);
        addVariant(out, stripFeat(artist), stripTail(title));
        addVariant(out, norm(stripFeat(artist)), norm(stripTail(title)));
        return out;
    }

    private static void addVariant(ArrayList<String[]> list, String artist, String title) {
        if (TextUtils.isEmpty(title)) {
            return;
        }
        final String a = artist == null ? "" : artist.trim();
        final String t = title.trim();
        for (int i = 0; i < list.size(); ++i) {
            if (list.get(i)[0].equalsIgnoreCase(a) && list.get(i)[1].equalsIgnoreCase(t)) {
                return;
            }
        }
        list.add(new String[]{a, t});
    }

    private static String stripFeat(String artist) {
        if (TextUtils.isEmpty(artist)) {
            return "";
        }
        String value = artist;
        final String[] marks = new String[]{" feat.", " feat ", " ft.", " ft ", " x ", " & ", ", "};
        for (String mark : marks) {
            final int index = value.toLowerCase(Locale.ROOT).indexOf(mark);
            if (index > 0) {
                value = value.substring(0, index);
            }
        }
        return value.trim();
    }

    private static String stripTail(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }
        String value = title.replaceAll("\\([^)]*\\)", " ").replaceAll("\\[[^\\]]*\\]", " ");
        final String[] marks = new String[]{" - remix", " remix", " prod.", " official", " lyrics"};
        for (String mark : marks) {
            final int index = value.toLowerCase(Locale.ROOT).indexOf(mark);
            if (index > 0) {
                value = value.substring(0, index);
            }
        }
        return value.trim();
    }

    /** почему кандидат не подходит; null — подходит */
    private static String rejectReason(Candidate candidate, String artist, String title, int duration) {
        if (TextUtils.isEmpty(candidate.lyrics)) {
            return "нет текста";
        }
        if (!durationGate(duration, candidate.duration)) {
            return "длительность расходится на " + Math.abs(duration - candidate.duration) + " с";
        }
        if (!titleGate(title, candidate.title)) {
            return "название не совпало";
        }
        if (!artistGate(artist, candidate.artist)) {
            return "исполнитель не совпал";
        }
        if (!coverageGate(candidate.lyrics, duration)) {
            return "текст не покрывает песню";
        }
        return null;
    }

    private static void collectOne(String json, ArrayList<Candidate> out) {
        if (TextUtils.isEmpty(json)) {
            return;
        }
        try {
            addCandidate(new JSONObject(json), out);
        } catch (Throwable ignore) {
        }
    }

    private static void collectMany(String json, ArrayList<Candidate> out) {
        if (TextUtils.isEmpty(json)) {
            return;
        }
        try {
            final JSONArray array = new JSONArray(json);
            for (int a = 0; a < array.length() && a < 20; ++a) {
                addCandidate(array.optJSONObject(a), out);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void addCandidate(JSONObject record, ArrayList<Candidate> out) {
        if (record == null || record.optBoolean("instrumental", false)) {
            return;
        }
        final String lyrics = lyricsOf(record);
        if (lyrics == null) {
            return;
        }
        final Candidate candidate = new Candidate();
        candidate.artist = record.optString("artistName", "");
        candidate.title = record.optString("trackName", "");
        candidate.duration = record.optInt("duration", 0);
        candidate.lyrics = lyrics;
        candidate.synced = !record.isNull("syncedLyrics");
        for (int a = 0; a < out.size(); ++a) {
            final Candidate other = out.get(a);
            if (other.duration == candidate.duration
                    && other.title.equalsIgnoreCase(candidate.title)
                    && other.artist.equalsIgnoreCase(candidate.artist)) {
                return;   // та же запись из другого запроса
            }
        }
        out.add(candidate);
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
            if (!musixmatchMatches(macro, artist, title, duration)) {
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

    /**
     * Musixmatch на непонятный запрос честно отвечает «вот похожая песня».
     * Поэтому смотрим, какой трек он нашёл, и сверяем его теми же воротами,
     * что и каталог LRCLIB: название, исполнитель, длительность.
     */
    private static boolean musixmatchMatches(JSONObject macro, String artist, String title, int duration) {
        try {
            final JSONObject call = macro.optJSONObject("matcher.track.get") != null
                    ? macro.optJSONObject("matcher.track.get")
                    : macro.optJSONObject("track.get");
            if (call == null) {
                log("musixmatch: отсев — ответ без описания трека");
                return false;
            }
            final JSONObject message = call.optJSONObject("message");
            final JSONObject body = message == null ? null : message.optJSONObject("body");
            final JSONObject track = body == null ? null : body.optJSONObject("track");
            if (track == null) {
                log("musixmatch: отсев — ответ без описания трека");
                return false;
            }
            final String gotTitle = track.optString("track_name", "");
            final String gotArtist = track.optString("artist_name", "");
            final int gotLength = track.optInt("track_length", 0);
            if (!titleGate(title, gotTitle)) {
                log("musixmatch: отсев — название «" + gotTitle + "» не то");
                return false;
            }
            if (!artistGate(artist, gotArtist)) {
                log("musixmatch: отсев — исполнитель «" + gotArtist + "» не тот");
                return false;
            }
            if (!durationGate(duration, gotLength)) {
                log("musixmatch: длительность расходится (" + gotLength + " с вместо " + duration
                        + " с), но название и исполнитель совпали — берём");
            }
            log("musixmatch: подходит «" + gotArtist + " — " + gotTitle + "»");
            return true;
        } catch (Throwable e) {
            return false;
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
                    final org.json.JSONObject primary = result.optJSONObject("primary_artist");
                    final String gotArtist = primary == null ? "" : primary.optString("name", "");
                    final String gotTitle = result.optString("title", "");
                    if (TextUtils.isEmpty(url)) {
                        continue;
                    }
                    if (!titleGate(title, gotTitle)) {
                        log("genius: отсев — название «" + gotTitle + "» не то");
                        continue;
                    }
                    if (!artistGate(artist, gotArtist)) {
                        log("genius: отсев — исполнитель «" + gotArtist + "» не тот");
                        continue;
                    }
                    log("genius: подходит «" + gotArtist + " — " + gotTitle + "»");
                    path = url;
                    break;
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
