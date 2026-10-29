package org.telegram.messenger;

import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pengram: быстрый интернет-поиск по префиксу прямо в строке ввода чата
 * (например, «.как называется китайская валюта»).
 *
 * <p>По умолчанию функция выключена ({@link PengramConfig#isInlineWebSearchEnabled()} == false).
 * Все сетевые запросы выполняются строго в фоновом пуле с короткими таймаутами,
 * параллельным опросом источников и LRU-кэшем в памяти — 0 мс блокировок UI-потока (120 Гц).
 */
public final class PengramInlineWebSearch {

    private static final int CONNECT_TIMEOUT_MS = 3500;
    private static final int READ_TIMEOUT_MS = 4500;
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_CACHE_ENTRIES = 64;

    private static final String WIKI_USER_AGENT =
            "PengramAndroid/1.0 (Telegram Messenger Client; Android)";
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final Set<String> IGNORED_DOT_TOKENS = new HashSet<>();

    static {
        final String[] extAndTlds = new String[]{
                "ru", "su", "рф", "com", "org", "net", "io", "me", "co", "ai", "gg", "dev", "app",
                "uk", "de", "fr", "es", "it", "nl", "pl", "ua", "kz", "by", "uz", "am", "ge", "az", "tr", "cn",
                "apk", "apks", "xapk", "pen", "dex", "so", "jar", "aar",
                "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "json", "xml", "yaml", "yml", "md", "log", "ini", "cfg", "env",
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "avif", "ico",
                "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav",
                "mp4", "mkv", "webm", "avi", "mov", "3gp",
                "exe", "msi", "dmg", "deb", "rpm", "sh", "bat", "py", "js", "ts", "java", "kt", "cpp", "c", "h",
                "gitignore", "nomedia", "htaccess"
        };
        Collections.addAll(IGNORED_DOT_TOKENS, extAndTlds);
    }

    // Разговорные вопросительные приставки, которые мешают точному ранжированию в энциклопедии
    private static final String[] QUESTION_OPENERS = new String[]{
            "а как называется ", "как называется ", "как называются ", "как официально называется ",
            "как по другому называется ", "как иначе называется ", "как зовут ",
            "что такое ", "что значит ", "что означает ", "что это ",
            "кто такой ", "кто такая ", "кто такие ", "кто написал ", "кто создал ", "кто изобрел ", "кто изобрёл ",
            "где находится ", "где расположен ", "где расположена ",
            "какая валюта в ", "какая валюта у ", "валюта в ",
            "какая столица у ", "какая столица в ",
            "what is the name of the ", "what is the name of ", "what is a ", "what is an ", "what is the ", "what is ",
            "who is ", "who wrote ", "who invented ", "where is "
    };

    // Скомпилированные регулярные выражения для разбора HTML-результатов (Android ART-safe)
    private static final Pattern DDG_RESULT_LINK_PATTERN = Pattern.compile(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern DDG_RESULT_SNIPPET_PATTERN = Pattern.compile(
            "<(?:a|div|span)[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div|span)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY_DEC = Pattern.compile("&#([0-9]{1,7});");
    private static final Pattern NUMERIC_ENTITY_HEX = Pattern.compile("&#x([0-9a-fA-F]{1,6});");

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);
    private static final ExecutorService SEARCH_POOL = Executors.newFixedThreadPool(4, r -> {
        final Thread t = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignore) {
            }
            r.run();
        }, "pengram-websearch-" + THREAD_SEQ.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private PengramInlineWebSearch() {
    }

    public interface Callback {
        void onResult(SearchResponse response);
    }

    public static final class SearchHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    public static final class PrefixMatch {
        public final String prefix;
        public final String query;
        public final int replaceStart;
        public final int replaceEnd;

        public PrefixMatch(String prefix, String query, int replaceStart, int replaceEnd) {
            this.prefix = prefix;
            this.query = query;
            this.replaceStart = replaceStart;
            this.replaceEnd = replaceEnd;
        }
    }

    public static final class InstantAnswer {
        public final String title;
        public final String subtitle;
        public final String summary;
        public final String shortInsert;
        public final String summaryInsert;
        public final String sourceName;
        public final String url;

        public InstantAnswer(
                String title,
                String subtitle,
                String summary,
                String shortInsert,
                String summaryInsert,
                String sourceName,
                String url
        ) {
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.summary = summary != null ? summary : "";
            this.shortInsert = shortInsert != null ? shortInsert : "";
            this.summaryInsert = summaryInsert != null ? summaryInsert : "";
            this.sourceName = sourceName != null ? sourceName : "";
            this.url = url != null ? url : "";
        }
    }

    public static final class WebHit {
        public final String title;
        public final String snippet;
        public final String url;
        public final String domain;

        public WebHit(String title, String snippet, String url, String domain) {
            this.title = title != null ? title : "";
            this.snippet = snippet != null ? snippet : "";
            this.url = url != null ? url : "";
            this.domain = domain != null ? domain : "";
        }
    }

    public static final class SearchResponse {
        public final String query;
        public final InstantAnswer instant;
        public final List<WebHit> webHits;
        public final List<String> suggestions;

        public SearchResponse(
                String query,
                InstantAnswer instant,
                List<WebHit> webHits,
                List<String> suggestions
        ) {
            this.query = query != null ? query : "";
            this.instant = instant;
            this.webHits = webHits != null ? webHits : Collections.emptyList();
            this.suggestions = suggestions != null ? suggestions : Collections.emptyList();
        }

        public boolean hasDirectContent() {
            return instant != null || !webHits.isEmpty();
        }
    }

    private static final class CacheEntry {
        final SearchResponse response;
        final long createdAtMs;

        CacheEntry(SearchResponse response, long createdAtMs) {
            this.response = response;
            this.createdAtMs = createdAtMs;
        }
    }

    // -------------------------------------------------------------------------
    // Извлечение запроса по префиксу с защитой от любых ложных срабатываний
    // -------------------------------------------------------------------------

    public static PrefixMatch extractPrefixQuery(CharSequence text) {
        if (!PengramConfig.isInlineWebSearchEnabled()) {
            return null;
        }
        return extractPrefixQueryWithPrefix(text, PengramConfig.getInlineWebSearchPrefix());
    }

    public static PrefixMatch extractPrefixQueryWithPrefix(CharSequence text, String rawPrefix) {
        if (text == null || text.length() < 2) {
            return null;
        }
        final String prefix = PengramConfig.sanitizeWebSearchPrefix(rawPrefix);
        if (prefix == null) {
            return null;
        }
        final String full = text.toString();
        if (full.indexOf('\n') >= 0 || full.indexOf('\r') >= 0) {
            return null;
        }
        // Допускаем только ведущие пробелы, но не текст перед префиксом
        int lead = 0;
        while (lead < full.length() && full.charAt(lead) == ' ') {
            lead++;
        }
        if (!full.startsWith(prefix, lead)) {
            return null;
        }
        final String lowerFull = full.substring(lead).toLowerCase(Locale.ROOT);
        if (lowerFull.startsWith("http://") || lowerFull.startsWith("https://") || lowerFull.startsWith("t.me/")) {
            return null;
        }
        final String afterPrefix = full.substring(lead + prefix.length());
        if (afterPrefix.isEmpty()) {
            return null;
        }
        // Защита от многоточия «...», «..», «???», «!!» и смайликов «.)», «.(»
        final char lastPrefixChar = prefix.charAt(prefix.length() - 1);
        final char firstAfter = afterPrefix.charAt(0);
        if (firstAfter == lastPrefixChar) {
            return null;
        }
        final String query = afterPrefix.trim();
        if (query.length() < 2 || query.length() > 160) {
            return null;
        }
        final char qFirst = query.charAt(0);
        if (qFirst == '.' || qFirst == ',' || qFirst == ')' || qFirst == '('
                || qFirst == '!' || qFirst == '?' || qFirst == ';' || qFirst == ':') {
            return null;
        }

        int letters = 0;
        int digits = 0;
        for (int i = 0; i < query.length(); i++) {
            final char c = query.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
            } else if (Character.isDigit(c)) {
                digits++;
            }
        }
        // Игнорируем десятичные дроби вроде «.5», «.25» и чистые числа
        if (letters < 2 && !(letters >= 1 && digits >= 1 && query.length() >= 3)) {
            return null;
        }

        // Если префикс — точка, и введено одно слово без пробелов, отсекаем расширения файлов и домены (.apk, .pen, .ru, .com)
        if (".".equals(prefix) && query.indexOf(' ') < 0) {
            final String tokenLower = query.toLowerCase(Locale.ROOT);
            if (IGNORED_DOT_TOKENS.contains(tokenLower)) {
                return null;
            }
            if (tokenLower.indexOf('/') >= 0 || tokenLower.indexOf('\\') >= 0) {
                return null;
            }
        }

        return new PrefixMatch(prefix, query, lead, full.length());
    }

    // -------------------------------------------------------------------------
    // Построение URL для открытия полного поиска в выбранном поисковике
    // -------------------------------------------------------------------------

    public static String buildBrowserSearchUrl(String query, int engine) {
        final String q = encodeParam(query != null ? query.trim() : "");
        switch (engine) {
            case PengramConfig.WEB_ENGINE_YANDEX:
                return "https://ya.ru/search/?text=" + q;
            case PengramConfig.WEB_ENGINE_DUCKDUCKGO:
                return "https://duckduckgo.com/?q=" + q;
            case PengramConfig.WEB_ENGINE_BRAVE:
                return "https://search.brave.com/search?q=" + q;
            case PengramConfig.WEB_ENGINE_GOOGLE:
            default:
                return "https://www.google.com/search?q=" + q;
        }
    }

    public static String buildWikipediaSearchUrl(String query) {
        final String clean = query != null ? query.trim() : "";
        final boolean cyrillic = containsCyrillic(clean);
        final String lang = cyrillic ? "ru" : "en";
        return "https://" + lang + ".wikipedia.org/wiki/Special:Search?search=" + encodeParam(clean);
    }

    // -------------------------------------------------------------------------
    // Кэшированный асинхронный поиск (параллельный опрос источников)
    // -------------------------------------------------------------------------

    public static SearchResponse getCached(String rawQuery) {
        if (TextUtils.isEmpty(rawQuery)) {
            return null;
        }
        final String key = cacheKey(rawQuery);
        synchronized (CACHE) {
            final CacheEntry entry = CACHE.get(key);
            if (entry == null) {
                return null;
            }
            if (System.currentTimeMillis() - entry.createdAtMs > CACHE_TTL_MS) {
                CACHE.remove(key);
                return null;
            }
            return entry.response;
        }
    }

    public static SearchHandle searchAsync(final String rawQuery, final int currentAccount, final Callback callback) {
        final SearchHandle handle = new SearchHandle();
        if (TextUtils.isEmpty(rawQuery) || callback == null) {
            return handle;
        }
        final String trimmed = rawQuery.trim();
        final SearchResponse cached = getCached(trimmed);
        if (cached != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!handle.isCancelled()) {
                    callback.onResult(cached);
                }
            });
            return handle;
        }

        SEARCH_POOL.execute(() -> {
            if (handle.isCancelled()) {
                return;
            }
            final SearchResponse built = executeParallelSearch(trimmed, currentAccount, handle);
            if (handle.isCancelled()) {
                return;
            }
            if (built.hasDirectContent() || !built.suggestions.isEmpty()) {
                synchronized (CACHE) {
                    CACHE.put(cacheKey(trimmed), new CacheEntry(built, System.currentTimeMillis()));
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!handle.isCancelled()) {
                    callback.onResult(built);
                }
            });
        });
        return handle;
    }

    private static SearchResponse executeParallelSearch(String query, int currentAccount, SearchHandle handle) {
        // 0) Мгновенная локальная проверка калькулятора / конвертера величин / живых курсов валют
        InstantAnswer calcInstant = null;
        try {
            final PengramInlineCalc.Suggestion calc = PengramInlineCalc.analyzeDirect(query, currentAccount);
            if (calc != null && !calc.loadingRates && !TextUtils.isEmpty(calc.primaryReplacement)) {
                final String title = calc.displayTitle.startsWith("= ") || calc.displayTitle.startsWith("≈ ")
                        ? calc.displayTitle.substring(2).trim()
                        : calc.displayTitle;
                calcInstant = new InstantAnswer(
                        title,
                        calc.displaySubtitle,
                        query + " = " + calc.primaryReplacement,
                        calc.primaryReplacement,
                        query + " = " + calc.primaryReplacement,
                        "Pengram",
                        ""
                );
            }
        } catch (Throwable ignore) {
        }

        final boolean cyrillic = containsCyrillic(query);
        final String primaryLang = cyrillic ? "ru" : (isRussianUiLocale() ? "ru" : "en");
        final String secondaryLang = "ru".equals(primaryLang) ? "en" : "ru";

        final WikiFetchResult[] wikiHolder = new WikiFetchResult[1];
        final DdgInstantResult[] ddgInstantHolder = new DdgInstantResult[1];
        final List<WebHit>[] ddgWebHolder = new List[1];
        final List<String>[] suggestHolder = new List[1];

        final CountDownLatch latch = new CountDownLatch(3);

        // Поток 1: Wikipedia CirrusSearch (с очисткой разговорной обёртки вопроса + резервным языком)
        SEARCH_POOL.execute(() -> {
            try {
                if (!handle.isCancelled()) {
                    WikiFetchResult res = fetchWikipedia(query, cyrillic ? "ru" : "en");
                    if ((res == null || res.hits.isEmpty()) && !handle.isCancelled()) {
                        res = fetchWikipedia(query, cyrillic ? "en" : "ru");
                    }
                    wikiHolder[0] = res;
                }
            } catch (Throwable ignore) {
            } finally {
                latch.countDown();
            }
        });

        // Поток 2: DuckDuckGo Instant Answer JSON + DuckDuckGo HTML органическая выдача
        SEARCH_POOL.execute(() -> {
            try {
                if (!handle.isCancelled()) {
                    ddgInstantHolder[0] = fetchDuckDuckGoInstant(query);
                }
                if (!handle.isCancelled()) {
                    ddgWebHolder[0] = fetchDuckDuckGoWeb(query, cyrillic || "ru".equals(primaryLang));
                }
            } catch (Throwable ignore) {
            } finally {
                latch.countDown();
            }
        });

        // Поток 3: Быстрые поисковые подсказки Google Suggest
        SEARCH_POOL.execute(() -> {
            try {
                if (!handle.isCancelled()) {
                    suggestHolder[0] = fetchGoogleSuggestions(query, primaryLang);
                }
            } catch (Throwable ignore) {
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(5200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }

        // Собираем лучший мгновенный ответ
        InstantAnswer instant = calcInstant;
        final WikiFetchResult wiki = wikiHolder[0];
        final DdgInstantResult ddgInstant = ddgInstantHolder[0];

        if (instant == null && ddgInstant != null && ddgInstant.directAnswer != null) {
            instant = ddgInstant.directAnswer;
        }
        if (instant == null && wiki != null && wiki.primaryInstant != null) {
            instant = wiki.primaryInstant;
        }
        if (instant == null && ddgInstant != null && ddgInstant.abstractAnswer != null) {
            instant = ddgInstant.abstractAnswer;
        }

        // Объединяем веб-результаты без дубликатов URL
        final ArrayList<WebHit> mergedHits = new ArrayList<>();
        final HashSet<String> seenUrls = new HashSet<>();

        if (ddgWebHolder[0] != null) {
            for (WebHit h : ddgWebHolder[0]) {
                if (h != null && !TextUtils.isEmpty(h.url) && seenUrls.add(normalizeUrlKey(h.url))) {
                    mergedHits.add(h);
                }
            }
        }
        if (wiki != null && wiki.hits != null) {
            for (WebHit h : wiki.hits) {
                if (h != null && !TextUtils.isEmpty(h.url) && seenUrls.add(normalizeUrlKey(h.url))) {
                    mergedHits.add(h);
                }
            }
        }
        if (ddgInstant != null && ddgInstant.relatedHits != null) {
            for (WebHit h : ddgInstant.relatedHits) {
                if (h != null && !TextUtils.isEmpty(h.url) && seenUrls.add(normalizeUrlKey(h.url))) {
                    mergedHits.add(h);
                }
            }
        }

        // Если мгновенной энциклопедической карточки нет, но есть первый веб-результат — делаем компактное превью для плашки
        if (instant == null && !mergedHits.isEmpty()) {
            final WebHit top = mergedHits.get(0);
            final String shortIns = top.title.length() <= 72 ? top.title : top.url;
            final String fullIns = !TextUtils.isEmpty(top.snippet)
                    ? (top.title + " — " + trimToLength(top.snippet, 160))
                    : top.title;
            instant = new InstantAnswer(
                    top.title,
                    top.domain,
                    top.snippet,
                    shortIns,
                    fullIns,
                    top.domain,
                    top.url
            );
        }

        final List<String> suggestions = suggestHolder[0] != null ? suggestHolder[0] : Collections.emptyList();
        return new SearchResponse(query, instant, mergedHits, suggestions);
    }

    // -------------------------------------------------------------------------
    // Источник 1: MediaWiki CirrusSearch API (Wikipedia)
    // -------------------------------------------------------------------------

    private static final class WikiFetchResult {
        final InstantAnswer primaryInstant;
        final List<WebHit> hits;

        WikiFetchResult(InstantAnswer primaryInstant, List<WebHit> hits) {
            this.primaryInstant = primaryInstant;
            this.hits = hits;
        }
    }

    private static final class WikiPage {
        int index;
        String title;
        String description;
        String extract;
        String url;
    }

    public static String simplifyQuestionForWiki(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        String q = rawQuery.trim();
        while (!q.isEmpty()) {
            final char tail = q.charAt(q.length() - 1);
            if (tail == '?' || tail == '!' || tail == '.' || tail == ' ') {
                q = q.substring(0, q.length() - 1);
            } else {
                break;
            }
        }
        final String lower = q.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String opener : QUESTION_OPENERS) {
            if (lower.startsWith(opener) && q.length() > opener.length() + 2) {
                final String stripped = q.substring(opener.length()).trim();
                if (stripped.length() >= 2) {
                    return stripped;
                }
            }
        }
        return q;
    }

    private static WikiFetchResult fetchWikipedia(String rawQuery, String lang) {
        final String simplified = simplifyQuestionForWiki(rawQuery);
        WikiFetchResult res = queryMediaWiki(simplified, lang);
        if ((res == null || res.hits.isEmpty()) && !simplified.equalsIgnoreCase(rawQuery.trim())) {
            res = queryMediaWiki(rawQuery.trim(), lang);
        }
        return res;
    }

    private static WikiFetchResult queryMediaWiki(String searchTerm, String lang) {
        if (TextUtils.isEmpty(searchTerm)) {
            return null;
        }
        final String endpoint = "https://" + lang + ".wikipedia.org/w/api.php"
                + "?action=query&format=json&utf8=1&redirects=1"
                + "&generator=search&gsrsearch=" + encodeParam(searchTerm)
                + "&gsrlimit=5&prop=extracts|description|info"
                + "&exintro=1&explaintext=1&exsentences=3&inprop=url";

        final String body = httpGet(endpoint, WIKI_USER_AGENT, "application/json");
        if (TextUtils.isEmpty(body)) {
            return null;
        }
        try {
            final JSONObject root = new JSONObject(body);
            final JSONObject queryObj = root.optJSONObject("query");
            if (queryObj == null) {
                return null;
            }
            final JSONObject pagesObj = queryObj.optJSONObject("pages");
            if (pagesObj == null) {
                return null;
            }
            final JSONArray names = pagesObj.names();
            if (names == null || names.length() == 0) {
                return null;
            }
            final ArrayList<WikiPage> pages = new ArrayList<>();
            for (int i = 0; i < names.length(); i++) {
                final JSONObject p = pagesObj.optJSONObject(names.getString(i));
                if (p == null) {
                    continue;
                }
                final String title = cleanWhitespace(p.optString("title", ""));
                if (title.isEmpty()) {
                    continue;
                }
                final WikiPage wp = new WikiPage();
                wp.index = p.optInt("index", 999);
                wp.title = title;
                wp.description = cleanWhitespace(p.optString("description", ""));
                wp.extract = cleanWhitespace(p.optString("extract", ""));
                wp.url = p.optString("fullurl", "");
                if (wp.url.isEmpty()) {
                    wp.url = "https://" + lang + ".wikipedia.org/wiki/" + encodeParam(title.replace(' ', '_'));
                }
                pages.add(wp);
            }
            if (pages.isEmpty()) {
                return null;
            }
            Collections.sort(pages, Comparator.comparingInt(a -> a.index));

            final ArrayList<WebHit> hits = new ArrayList<>();
            InstantAnswer primary = null;
            final String wikiLabel = "ru".equals(lang) ? "Википедия" : "Wikipedia";

            for (int i = 0; i < pages.size(); i++) {
                final WikiPage wp = pages.get(i);
                // Пропускаем страницы неоднозначности для главного ответа, если есть конкретная статья следом
                final boolean disambig = isDisambiguation(wp.title, wp.description, wp.extract);
                if (primary == null && (!disambig || i == pages.size() - 1)) {
                    final String subtitle = !wp.description.isEmpty() ? wp.description : wikiLabel;
                    final String summary = !wp.extract.isEmpty() ? wp.extract : wp.description;
                    final String summaryInsert;
                    if (!wp.extract.isEmpty()) {
                        summaryInsert = trimToFirstSentence(wp.title, wp.extract, 180);
                    } else if (!wp.description.isEmpty()) {
                        summaryInsert = wp.title + " — " + wp.description;
                    } else {
                        summaryInsert = wp.title;
                    }
                    primary = new InstantAnswer(
                            wp.title,
                            subtitle,
                            summary,
                            wp.title,
                            summaryInsert,
                            wikiLabel,
                            wp.url
                    );
                }
                final String snippet = !wp.extract.isEmpty() ? wp.extract : wp.description;
                hits.add(new WebHit(wp.title, snippet, wp.url, lang + ".wikipedia.org"));
            }
            return new WikiFetchResult(primary, hits);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean isDisambiguation(String title, String desc, String extract) {
        final String t = (title != null ? title : "").toLowerCase(Locale.ROOT);
        final String d = (desc != null ? desc : "").toLowerCase(Locale.ROOT);
        final String e = (extract != null ? extract : "").toLowerCase(Locale.ROOT);
        return t.contains("(значения)") || t.contains("(disambiguation)")
                || d.contains("страница значений") || d.contains("disambiguation")
                || e.startsWith("может означать:") || e.contains("may refer to:");
    }

    // -------------------------------------------------------------------------
    // Источник 2: DuckDuckGo Instant Answer JSON + DuckDuckGo HTML Web
    // -------------------------------------------------------------------------

    private static final class DdgInstantResult {
        InstantAnswer directAnswer;
        InstantAnswer abstractAnswer;
        List<WebHit> relatedHits = new ArrayList<>();
    }

    private static DdgInstantResult fetchDuckDuckGoInstant(String query) {
        final String endpoint = "https://api.duckduckgo.com/?q=" + encodeParam(query)
                + "&format=json&no_html=1&skip_disambig=1";
        final String body = httpGet(endpoint, WIKI_USER_AGENT, "application/json");
        if (TextUtils.isEmpty(body)) {
            return null;
        }
        try {
            final JSONObject root = new JSONObject(body);
            final DdgInstantResult out = new DdgInstantResult();

            final String answer = cleanWhitespace(decodeHtmlEntities(root.optString("Answer", "")));
            if (!answer.isEmpty()) {
                out.directAnswer = new InstantAnswer(
                        answer,
                        "DuckDuckGo",
                        answer,
                        answer,
                        answer,
                        "DuckDuckGo",
                        root.optString("AbstractURL", "")
                );
            }

            final String heading = cleanWhitespace(root.optString("Heading", ""));
            final String abstractText = cleanWhitespace(decodeHtmlEntities(root.optString("AbstractText", "")));
            final String definition = cleanWhitespace(decodeHtmlEntities(root.optString("Definition", "")));
            final String abstractUrl = root.optString("AbstractURL", "");
            final String source = cleanWhitespace(root.optString("AbstractSource", "DuckDuckGo"));

            final String text = !abstractText.isEmpty() ? abstractText : definition;
            if (!text.isEmpty()) {
                final String title = !heading.isEmpty() ? heading : query;
                out.abstractAnswer = new InstantAnswer(
                        title,
                        source,
                        text,
                        title,
                        trimToFirstSentence(title, text, 180),
                        source,
                        abstractUrl
                );
            }

            final JSONArray related = root.optJSONArray("RelatedTopics");
            if (related != null) {
                for (int i = 0; i < related.length() && out.relatedHits.size() < 4; i++) {
                    final JSONObject item = related.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    final String relText = cleanWhitespace(decodeHtmlEntities(item.optString("Text", "")));
                    final String relUrl = item.optString("FirstURL", "");
                    if (!relText.isEmpty() && !relUrl.isEmpty()) {
                        final int dash = relText.indexOf(" - ");
                        final String relTitle = dash > 0 ? relText.substring(0, dash).trim() : trimToLength(relText, 64);
                        out.relatedHits.add(new WebHit(relTitle, relText, relUrl, extractDomain(relUrl)));
                    }
                }
            }
            return out;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static List<WebHit> fetchDuckDuckGoWeb(String query, boolean preferRu) {
        final String kl = preferRu ? "ru-ru" : "wt-wt";
        final String endpoint = "https://html.duckduckgo.com/html/?q=" + encodeParam(query) + "&kl=" + kl;
        final String html = httpGet(endpoint, BROWSER_USER_AGENT, "text/html,application/xhtml+xml");
        if (TextUtils.isEmpty(html)) {
            return Collections.emptyList();
        }
        return parseDuckDuckGoHtml(html);
    }

    public static List<WebHit> parseDuckDuckGoHtml(String html) {
        if (TextUtils.isEmpty(html)) {
            return Collections.emptyList();
        }
        final ArrayList<WebHit> results = new ArrayList<>();
        final Matcher linkMatcher = DDG_RESULT_LINK_PATTERN.matcher(html);
        while (linkMatcher.find() && results.size() < 8) {
            final String rawHref = linkMatcher.group(1);
            final String rawTitle = linkMatcher.group(2);
            final String realUrl = unwrapDuckDuckGoRedirect(decodeHtmlEntities(rawHref));
            if (TextUtils.isEmpty(realUrl) || !realUrl.startsWith("http")) {
                continue;
            }
            // Отсекаем рекламные переходы DuckDuckGo (y.js / ad_domain)
            if (realUrl.contains("duckduckgo.com/y.js") || realUrl.contains("ad_domain=")) {
                continue;
            }
            final String cleanTitle = cleanWhitespace(decodeHtmlEntities(stripHtmlTags(rawTitle)));
            if (cleanTitle.isEmpty()) {
                continue;
            }

            // Ищем ближайший сниппет в пределах следующих 1800 символов до следующей карточки
            final int searchFrom = linkMatcher.end();
            final int searchTo = Math.min(html.length(), searchFrom + 2000);
            String cleanSnippet = "";
            final Matcher snippetMatcher = DDG_RESULT_SNIPPET_PATTERN.matcher(html.substring(searchFrom, searchTo));
            if (snippetMatcher.find()) {
                cleanSnippet = cleanWhitespace(decodeHtmlEntities(stripHtmlTags(snippetMatcher.group(1))));
            }
            results.add(new WebHit(cleanTitle, cleanSnippet, realUrl, extractDomain(realUrl)));
        }
        return results;
    }

    public static String unwrapDuckDuckGoRedirect(String href) {
        if (TextUtils.isEmpty(href)) {
            return "";
        }
        String h = href.trim();
        if (h.startsWith("//")) {
            h = "https:" + h;
        }
        final int uddgIdx = h.indexOf("uddg=");
        if (uddgIdx >= 0) {
            int start = uddgIdx + 5;
            int end = h.indexOf('&', start);
            if (end < 0) {
                end = h.length();
            }
            try {
                return URLDecoder.decode(h.substring(start, end), "UTF-8");
            } catch (Throwable ignore) {
                return h.substring(start, end);
            }
        }
        return h;
    }

    // -------------------------------------------------------------------------
    // Источник 3: Google Autocomplete (подсказки для уточнения запроса)
    // -------------------------------------------------------------------------

    private static List<String> fetchGoogleSuggestions(String query, String hl) {
        final String endpoint = "https://suggestqueries.google.com/complete/search?client=firefox&hl="
                + encodeParam(hl) + "&q=" + encodeParam(query);
        final String body = httpGet(endpoint, BROWSER_USER_AGENT, "application/json");
        if (TextUtils.isEmpty(body)) {
            return Collections.emptyList();
        }
        try {
            final JSONArray arr = new JSONArray(body);
            if (arr.length() < 2) {
                return Collections.emptyList();
            }
            final JSONArray items = arr.optJSONArray(1);
            if (items == null) {
                return Collections.emptyList();
            }
            final ArrayList<String> out = new ArrayList<>();
            final String qNorm = query.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < items.length() && out.size() < 6; i++) {
                final String s = cleanWhitespace(items.optString(i, ""));
                if (!s.isEmpty() && !s.toLowerCase(Locale.ROOT).equals(qNorm)) {
                    out.add(s);
                }
            }
            return out;
        } catch (Throwable ignore) {
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // HTTP и текстовые утилиты
    // -------------------------------------------------------------------------

    private static String httpGet(String urlStr, String userAgent, String accept) {
        HttpURLConnection conn = null;
        try {
            final URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept", accept);
            conn.setRequestProperty("Accept-Language", isRussianUiLocale()
                    ? "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
                    : "en-US,en;q=0.9,ru;q=0.7");

            final int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                final StringBuilder sb = new StringBuilder(4096);
                final char[] buf = new char[2048];
                int n;
                // Защита от огромных ответов: читаем не более 256 КБ
                while ((n = reader.read(buf)) != -1 && sb.length() < 262_144) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            }
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static String encodeParam(String value) {
        try {
            return URLEncoder.encode(value != null ? value : "", "UTF-8");
        } catch (Throwable ignore) {
            return value != null ? value : "";
        }
    }

    private static String cacheKey(String query) {
        return query.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static boolean containsCyrillic(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if ((c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё') {
                return true;
            }
        }
        return false;
    }

    private static boolean isRussianUiLocale() {
        try {
            final Locale loc = LocaleController.getInstance().getCurrentLocale();
            final String lang = loc != null && loc.getLanguage() != null ? loc.getLanguage() : "";
            return "ru".equalsIgnoreCase(lang) || "be".equalsIgnoreCase(lang) || "uk".equalsIgnoreCase(lang);
        } catch (Throwable ignore) {
            return true;
        }
    }

    private static String stripHtmlTags(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        return HTML_TAG_PATTERN.matcher(html).replaceAll(" ");
    }

    public static String decodeHtmlEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text != null ? text : "";
        }
        String s = text
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&hellip;", "…");

        if (s.indexOf("&#") >= 0) {
            Matcher mDec = NUMERIC_ENTITY_DEC.matcher(s);
            StringBuffer sbDec = null;
            while (mDec.find()) {
                if (sbDec == null) sbDec = new StringBuffer(s.length());
                try {
                    final int cp = Integer.parseInt(mDec.group(1), 10);
                    final String ch = Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "";
                    mDec.appendReplacement(sbDec, Matcher.quoteReplacement(ch));
                } catch (Throwable ignore) {
                }
            }
            if (sbDec != null) {
                mDec.appendTail(sbDec);
                s = sbDec.toString();
            }

            Matcher mHex = NUMERIC_ENTITY_HEX.matcher(s);
            StringBuffer sbHex = null;
            while (mHex.find()) {
                if (sbHex == null) sbHex = new StringBuffer(s.length());
                try {
                    final int cp = Integer.parseInt(mHex.group(1), 16);
                    final String ch = Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "";
                    mHex.appendReplacement(sbHex, Matcher.quoteReplacement(ch));
                } catch (Throwable ignore) {
                }
            }
            if (sbHex != null) {
                mHex.appendTail(sbHex);
                s = sbHex.toString();
            }
        }
        return s;
    }

    private static String cleanWhitespace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = true;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00A0') {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String trimToFirstSentence(String title, String extract, int maxLen) {
        if (TextUtils.isEmpty(extract)) {
            return title != null ? title : "";
        }
        int dot = -1;
        for (int i = 20; i < extract.length() - 1; i++) {
            final char c = extract.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && extract.charAt(i + 1) == ' ') {
                dot = i + 1;
                break;
            }
        }
        final String sentence = dot > 0 ? extract.substring(0, dot).trim() : extract.trim();
        return trimToLength(sentence, maxLen);
    }

    private static String trimToLength(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s != null ? s : "";
        }
        int cut = s.lastIndexOf(' ', maxLen - 1);
        if (cut < maxLen / 2) {
            cut = maxLen - 1;
        }
        return s.substring(0, cut).trim() + "…";
    }

    private static String extractDomain(String url) {
        try {
            final Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String normalizeUrlKey(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
