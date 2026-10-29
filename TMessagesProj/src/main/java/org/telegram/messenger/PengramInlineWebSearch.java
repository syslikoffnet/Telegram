package org.telegram.messenger;

import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * Pengram: быстрый интернет-поиск и точные ответы по префиксу прямо в строке ввода чата
 * (например, «.какая валюта используется в Казахстане» -> «тенге» / «Тенге (KZT, ₸)»).
 *
 * <p>По умолчанию функция выключена ({@link PengramConfig#isInlineWebSearchEnabled()} == false).
 * Объединяет граф знаний Wikidata (точные факты: валюта, столица, президент, население, автор,
 * возраст, площадь, телефонный код и др.), очищенные выжимки Wikipedia, живой веб-поиск
 * (DuckDuckGo POST/Lite + Brave Search) и локальный калькулятор/конвертер валют.
 * Все сетевые запросы идут строго в фоновом пуле с LRU-кэшем — 0 мс нагрузки на UI (120 Гц).
 */
public final class PengramInlineWebSearch {

    private static final int CONNECT_TIMEOUT_MS = 3400;
    private static final int READ_TIMEOUT_MS = 4200;
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_CACHE_ENTRIES = 64;

    private static final String WIKI_USER_AGENT =
            "PengramAndroid/1.0 (Telegram Messenger Client; Android)";
    private static final String DESKTOP_BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    // Типы вопросов для графа знаний Wikidata и ранжирования ответов
    public static final int INTENT_NONE = 0;
    public static final int INTENT_DEFINITION = 1;
    public static final int INTENT_OPEN_QUESTION = 2;
    public static final int INTENT_CURRENCY = 10;       // P38
    public static final int INTENT_CAPITAL = 11;        // P36
    public static final int INTENT_LANGUAGE = 12;       // P37
    public static final int INTENT_POPULATION = 13;     // P1082
    public static final int INTENT_AREA = 14;           // P2046
    public static final int INTENT_HEAD_STATE = 15;     // P35
    public static final int INTENT_HEAD_GOV = 16;       // P6
    public static final int INTENT_COUNTRY = 17;        // P17
    public static final int INTENT_AUTHOR = 18;         // P50
    public static final int INTENT_DIRECTOR = 19;       // P57
    public static final int INTENT_FOUNDER = 20;        // P112 / P170 / P61
    public static final int INTENT_CEO = 21;            // P169
    public static final int INTENT_INCEPTION = 22;      // P571 / P577 / P580 / P585
    public static final int INTENT_BIRTH_DATE = 23;     // P569
    public static final int INTENT_AGE = 24;            // P569 -> возраст
    public static final int INTENT_DEATH_DATE = 25;     // P570
    public static final int INTENT_BIRTHPLACE = 26;     // P19
    public static final int INTENT_HEIGHT = 27;         // P2044 / P2048
    public static final int INTENT_LENGTH = 28;         // P2043
    public static final int INTENT_MASS = 29;           // P2067
    public static final int INTENT_CALLING_CODE = 30;   // P474
    public static final int INTENT_TLD = 31;            // P78
    public static final int INTENT_FORMULA = 32;        // P274

    private static final Set<String> IGNORED_DOT_TOKENS = new HashSet<>();
    private static final Set<String> FILLER_STOP_WORDS = new HashSet<>();
    private static final Map<String, String> COUNTRY_SYNONYMS = new HashMap<>();

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
                "gitignore", "nomedia", "htaccess", "git", "ssh", "npm", "bashrc", "zshrc", "idea", "vscode", "ds_store"
        };
        Collections.addAll(IGNORED_DOT_TOKENS, extAndTlds);

        final String[] fillers = new String[]{
                "а", "и", "ну", "же", "ли", "вот", "это", "такое", "такой", "такая", "такие",
                "скажи", "подскажи", "ответь", "напиши", "пожалуйста", "срочно",
                "какая", "какой", "какие", "какое", "каков", "какова", "каковы", "какими", "каким", "чем", "чей", "чья", "чье", "чьи",
                "как", "сколько", "когда", "где", "кто", "что",
                "называется", "называются", "зовется", "именуется", "обозначается",
                "используется", "используются", "применяется", "действует", "ходит", "ходят", "принята", "принят", "платить", "расплачиваться",
                "является", "будет", "была", "был", "было", "есть", "сейчас", "сегодня", "текущий", "текущая", "нынешний", "нынешняя",
                "официальная", "официальный", "официальное", "официальные",
                "национальная", "национальный", "государственная", "государственный", "основная", "основной", "главная", "главный",
                "в", "во", "на", "у", "из", "по", "о", "об", "от", "до", "для", "про", "при", "к", "ко", "с", "со",
                "стране", "страны", "государстве", "государства", "республике", "республики", "городе", "города",
                "what", "which", "who", "where", "when", "how", "is", "are", "was", "were", "the", "a", "an",
                "in", "of", "at", "for", "to", "used", "called", "named", "official", "national", "current", "currently", "does", "do", "have", "has"
        };
        Collections.addAll(FILLER_STOP_WORDS, fillers);

        // Прилагательные и разговорные сокращения стран -> точные именительные названия для графа знаний
        final String[][] adjPairs = new String[][]{
                {"казахстан", "Казахстан"}, {"казахск", "Казахстан"}, {"кз", "Казахстан"}, {"рк", "Казахстан"},
                {"росси", "Россия"}, {"русск", "Россия"}, {"рф", "Россия"},
                {"китай", "Китай"}, {"китайск", "Китай"}, {"кнр", "Китай"},
                {"япон", "Япония"}, {"японск", "Япония"},
                {"турци", "Турция"}, {"турецк", "Турция"},
                {"сша", "США"}, {"америк", "США"}, {"американск", "США"}, {"соединенных штат", "США"}, {"штатах", "США"},
                {"великобритан", "Великобритания"}, {"британск", "Великобритания"}, {"англи", "Великобритания"}, {"английск", "Великобритания"},
                {"германи", "Германия"}, {"немецк", "Германия"}, {"фрг", "Германия"},
                {"франци", "Франция"}, {"французск", "Франция"},
                {"испани", "Испания"}, {"испанск", "Испания"},
                {"итали", "Италия"}, {"итальянск", "Италия"},
                {"польш", "Польша"}, {"польск", "Польша"},
                {"чехи", "Чехия"}, {"чешск", "Чехия"},
                {"швейцари", "Швейцария"}, {"швейцарск", "Швейцария"},
                {"швеци", "Швеция"}, {"шведск", "Швеция"},
                {"норвеги", "Норвегия"}, {"норвежск", "Норвегия"},
                {"дани", "Дания"}, {"датск", "Дания"},
                {"финлянди", "Финляндия"}, {"финск", "Финляндия"},
                {"нидерланд", "Нидерланды"}, {"голланд", "Нидерланды"},
                {"бельги", "Бельгия"}, {"бельгийск", "Бельгия"},
                {"австри", "Австрия"}, {"австрийск", "Австрия"},
                {"португали", "Португалия"}, {"португальск", "Португалия"},
                {"греци", "Греция"}, {"греческ", "Греция"},
                {"серби", "Сербия"}, {"сербск", "Сербия"},
                {"венгри", "Венгрия"}, {"венгерск", "Венгрия"},
                {"румыни", "Румыния"}, {"румынск", "Румыния"},
                {"болгари", "Болгария"}, {"болгарск", "Болгария"},
                {"белорусси", "Белоруссия"}, {"беларус", "Белоруссия"}, {"белорусск", "Белоруссия"}, {"рб", "Белоруссия"},
                {"украин", "Украина"}, {"украинск", "Украина"},
                {"узбекистан", "Узбекистан"}, {"узбекск", "Узбекистан"},
                {"кыргызстан", "Кыргызстан"}, {"киргизи", "Кыргызстан"}, {"киргизск", "Кыргызстан"},
                {"таджикистан", "Таджикистан"}, {"таджикск", "Таджикистан"},
                {"туркменистан", "Туркменистан"}, {"туркмени", "Туркменистан"}, {"туркменск", "Туркменистан"},
                {"армени", "Армения"}, {"армянск", "Армения"},
                {"грузи", "Грузия"}, {"грузинск", "Грузия"},
                {"азербайджан", "Азербайджан"}, {"азербайджанск", "Азербайджан"},
                {"молдави", "Молдавия"}, {"молдов", "Молдавия"}, {"молдавск", "Молдавия"},
                {"инди", "Индия"}, {"индийск", "Индия"},
                {"бразили", "Бразилия"}, {"бразильск", "Бразилия"},
                {"аргентин", "Аргентина"}, {"аргентинск", "Аргентина"},
                {"мексик", "Мексика"}, {"мексиканск", "Мексика"},
                {"канад", "Канада"}, {"канадск", "Канада"},
                {"австрали", "Австралия"}, {"австралийск", "Австралия"},
                {"южн* коре", "Республика Корея"}, {"коре", "Республика Корея"}, {"корейск", "Республика Корея"}, {"южнокорейск", "Республика Корея"},
                {"таиланд", "Таиланд"}, {"тайск", "Таиланд"},
                {"вьетнам", "Вьетнам"}, {"вьетнамск", "Вьетнам"},
                {"индонези", "Индонезия"}, {"индонезийск", "Индонезия"},
                {"египт", "Египет"}, {"египет", "Египет"}, {"египетск", "Египет"},
                {"оаэ", "Объединённые Арабские Эмираты"}, {"эмират", "Объединённые Арабские Эмираты"}, {"дуба", "Объединённые Арабские Эмираты"},
                {"саудовск", "Саудовская Аравия"},
                {"израил", "Израиль"}, {"израильск", "Израиль"},
                {"иран", "Иран"}, {"иранск", "Иран"},
                {"юар", "Южно-Африканская Республика"},
                {"kazakh", "Kazakhstan"}, {"chinese", "China"}, {"japanese", "Japan"},
                {"russian", "Russia"}, {"german", "Germany"}, {"french", "France"},
                {"british", "United Kingdom"}, {"turkish", "Turkey"}, {"indian", "India"},
                {"brazilian", "Brazil"}, {"mexican", "Mexico"}, {"canadian", "Canada"},
                {"australian", "Australia"}, {"korean", "South Korea"}, {"swiss", "Switzerland"},
                {"swedish", "Sweden"}, {"polish", "Poland"}, {"spanish", "Spain"}, {"italian", "Italy"}
        };
        for (String[] pair : adjPairs) {
            COUNTRY_SYNONYMS.put(pair[0], pair[1]);
        }
    }

    private static final String[] DEFINITION_OPENERS = new String[]{
            "а что такое ", "что такое ", "что значит ", "что означает ", "что это ",
            "кто такой ", "кто такая ", "кто такие ", "определение слова ", "значение слова ",
            "what is a ", "what is an ", "what is the ", "what is ", "who is ", "define "
    };

    // Скомпилированные регулярные выражения (Android ART-safe, без UNICODE флагов и границ слов)
    private static final Pattern DDG_RESULT_LINK_PATTERN = Pattern.compile(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern DDG_RESULT_SNIPPET_PATTERN = Pattern.compile(
            "<(?:a|div|span)[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div|span)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern DDG_LITE_LINK_PATTERN = Pattern.compile(
            "<a[^>]*rel=\"nofollow\"[^>]*href=\"([^\"]+)\"[^>]*class=[\"']result-link[\"'][^>]*>(.*?)</a>|<a[^>]*class=[\"']result-link[\"'][^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern DDG_LITE_SNIPPET_PATTERN = Pattern.compile(
            "<td[^>]*class=[\"']result-snippet[\"'][^>]*>(.*?)</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BRAVE_BLOCK_PATTERN = Pattern.compile(
            "<div[^>]*data-type=\"web\"[^>]*>(.*?)(?=<div[^>]*data-type=\"|<footer|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BRAVE_LINK_PATTERN = Pattern.compile(
            "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BRAVE_TITLE_PATTERN = Pattern.compile(
            "<div[^>]*class=\"[^\"]*title[^\"]*\"[^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BRAVE_DESC_PATTERN = Pattern.compile(
            "<(?:div|p)[^>]*class=\"[^\"]*snippet-description[^\"]*\"[^>]*>(.*?)</(?:div|p)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY_DEC = Pattern.compile("&#([0-9]{1,7});");
    private static final Pattern NUMERIC_ENTITY_HEX = Pattern.compile("&#x([0-9a-fA-F]{1,6});");
    private static final Pattern WIKI_ETYMOLOGY_PARENS = Pattern.compile(
            "\\s*\\((?:[^()]{0,160}?(?:каз\\.|рус\\.|англ\\.|лат\\.|фр\\.|нем\\.|исп\\.|ит\\.|греч\\.|др\\.-греч\\.|араб\\.|кит\\.|яп\\.|тюрк\\.|перс\\.|пол\\.|укр\\.|бел\\.|произношение|МФА:|IPA:)[^()]{0,160}?)\\)"
    );

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

    public static final class QuestionAnalysis {
        public final int intent;
        public final String rawQuery;
        public final String subjectRaw;
        public final String subjectNormalized;
        public final String focusedSearchQuery;

        public QuestionAnalysis(
                int intent,
                String rawQuery,
                String subjectRaw,
                String subjectNormalized,
                String focusedSearchQuery
        ) {
            this.intent = intent;
            this.rawQuery = rawQuery != null ? rawQuery : "";
            this.subjectRaw = subjectRaw != null ? subjectRaw : "";
            this.subjectNormalized = subjectNormalized != null ? subjectNormalized : "";
            this.focusedSearchQuery = focusedSearchQuery != null ? focusedSearchQuery : "";
        }

        public boolean hasStructuredProperty() {
            return intent >= 10 && !subjectNormalized.isEmpty();
        }
    }

    public static final class InstantAnswer {
        public final String title;
        public final String subtitle;
        public final String summary;
        public final String shortInsert;
        public final String altInsert;
        public final String summaryInsert;
        public final String sourceName;
        public final String url;
        public final boolean confidentShort;

        public InstantAnswer(
                String title,
                String subtitle,
                String summary,
                String shortInsert,
                String summaryInsert,
                String sourceName,
                String url
        ) {
            this(title, subtitle, summary, shortInsert, "", summaryInsert, sourceName, url, !TextUtils.isEmpty(shortInsert));
        }

        public InstantAnswer(
                String title,
                String subtitle,
                String summary,
                String shortInsert,
                String altInsert,
                String summaryInsert,
                String sourceName,
                String url,
                boolean confidentShort
        ) {
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.summary = summary != null ? summary : "";
            this.shortInsert = shortInsert != null ? shortInsert : "";
            this.altInsert = altInsert != null ? altInsert : "";
            this.summaryInsert = summaryInsert != null ? summaryInsert : "";
            this.sourceName = sourceName != null ? sourceName : "";
            this.url = url != null ? url : "";
            this.confidentShort = confidentShort;
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
    // Извлечение запроса по префиксу (в начале строки ИЛИ в конце фразы после пробела)
    // без ложных срабатываний на точки в предложениях, домены и расширения файлов
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

        int lead = 0;
        while (lead < full.length() && full.charAt(lead) == ' ') {
            lead++;
        }
        if (lead >= full.length()) {
            return null;
        }

        // Вариант 1: префикс стоит в самом начале строки ввода
        if (full.startsWith(prefix, lead)) {
            final PrefixMatch m = validateCandidateAt(full, prefix, lead, true);
            if (m != null) {
                return m;
            }
        }

        // Вариант 2: префикс написан внутри сообщения после пробела («Кстати, там валюта .какая валюта в казахстане»)
        // Строго: перед префиксом пробел, сразу после префикса — буква/цифра (НЕ пробел и НЕ знак препинания)
        final String marker = " " + prefix;
        int pos = full.lastIndexOf(marker);
        while (pos >= lead) {
            final int prefixIdx = pos + 1;
            final PrefixMatch m = validateCandidateAt(full, prefix, prefixIdx, false);
            if (m != null) {
                return m;
            }
            pos = pos > 0 ? full.lastIndexOf(marker, pos - 1) : -1;
        }
        return null;
    }

    private static PrefixMatch validateCandidateAt(String full, String prefix, int prefixIdx, boolean atLineStart) {
        final String lowerFromPrefix = full.substring(prefixIdx).toLowerCase(Locale.ROOT);
        if (lowerFromPrefix.startsWith("http://") || lowerFromPrefix.startsWith("https://") || lowerFromPrefix.startsWith("t.me/")) {
            return null;
        }
        final String afterPrefix = full.substring(prefixIdx + prefix.length());
        if (afterPrefix.isEmpty()) {
            return null;
        }
        // Если префикс внутри текста — сразу после него обязана идти буква (не пробел!), чтобы «Привет . Как дела» никогда не триггерилось
        final char firstRaw = afterPrefix.charAt(0);
        if (!atLineStart && !Character.isLetter(firstRaw)) {
            return null;
        }
        final char lastPrefixChar = prefix.charAt(prefix.length() - 1);
        if (firstRaw == lastPrefixChar) {
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
        if (letters < 2 && !(letters >= 1 && digits >= 1 && query.length() >= 3)) {
            return null;
        }

        if (".".equals(prefix)) {
            final int firstSpace = query.indexOf(' ');
            final String firstToken = (firstSpace > 0 ? query.substring(0, firstSpace) : query).toLowerCase(Locale.ROOT);
            if (IGNORED_DOT_TOKENS.contains(firstToken)) {
                return null;
            }
            if (firstToken.indexOf('/') >= 0 || firstToken.indexOf('\\') >= 0) {
                return null;
            }
            // В середине сообщения с точкой-префиксом требуем либо >= 2 слов, либо слово >= 4 букв
            if (!atLineStart && firstSpace < 0 && letters < 4) {
                return null;
            }
        }
        return new PrefixMatch(prefix, query, prefixIdx, full.length());
    }

    // -------------------------------------------------------------------------
    // Интеллектуальный разбор вопроса (намерение + объект + очищенный поисковый запрос)
    // -------------------------------------------------------------------------

    public static QuestionAnalysis parseQuestionIntent(String rawQuery) {
        if (rawQuery == null) {
            return new QuestionAnalysis(INTENT_NONE, "", "", "", "");
        }
        String clean = rawQuery.trim();
        while (!clean.isEmpty()) {
            final char tail = clean.charAt(clean.length() - 1);
            if (tail == '?' || tail == '!' || tail == '.' || tail == ',' || tail == ' ') {
                clean = clean.substring(0, clean.length() - 1);
            } else {
                break;
            }
        }
        if (clean.isEmpty()) {
            return new QuestionAnalysis(INTENT_NONE, "", "", "", "");
        }

        final String norm = " " + clean.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('«', ' ')
                .replace('»', ' ')
                .replace('"', ' ')
                .replace('—', ' ')
                .replace('–', ' ') + " ";

        // 1. Определяем конкретное свойство (вопрос о факте)
        int intent = INTENT_NONE;
        String[] intentWordsToStrip = null;
        String topicKeywordRu = "";
        String topicKeywordEn = "";

        if (containsAny(norm, " валют", " денежн", " какие деньги ", " какими деньгами ", " каким деньгами ", " чем платят ", " чем расплачива", " currency ", " what money ")) {
            intent = INTENT_CURRENCY;
            intentWordsToStrip = new String[]{"валюта", "валюту", "валюты", "валютой", "валюте", "денежная", "единица", "деньги", "деньгами", "платят", "расплачиваются", "currency", "money"};
            topicKeywordRu = "валюта";
            topicKeywordEn = "currency";
        } else if (containsAny(norm, " столиц", " главный город ", " capital ")) {
            intent = INTENT_CAPITAL;
            intentWordsToStrip = new String[]{"столица", "столицей", "столицу", "столицы", "главный", "город", "capital"};
            topicKeywordRu = "столица";
            topicKeywordEn = "capital";
        } else if (containsAny(norm, " телефонный код ", " код телефона ", " код страны ", " calling code ", " dialing code ", " phone code ")) {
            intent = INTENT_CALLING_CODE;
            intentWordsToStrip = new String[]{"телефонный", "телефона", "код", "страны", "calling", "dialing", "phone", "code"};
            topicKeywordRu = "телефонный код";
            topicKeywordEn = "calling code";
        } else if (containsAny(norm, " домен ", " интернет-домен ", " интернет домен ", " top level domain ")) {
            intent = INTENT_TLD;
            intentWordsToStrip = new String[]{"интернет-домен", "интернет", "национальный", "домен", "верхнего", "уровня", "top", "level", "domain"};
            topicKeywordRu = "интернет-домен";
            topicKeywordEn = "top-level domain";
        } else if (containsAny(norm, " химическая формула ", " формула ", " chemical formula ")) {
            intent = INTENT_FORMULA;
            intentWordsToStrip = new String[]{"химическая", "формула", "вещества", "кислоты", "газа", "chemical", "formula"};
            topicKeywordRu = "химическая формула";
            topicKeywordEn = "chemical formula";
        } else if (containsAny(norm, " населени", " сколько человек жив", " сколько людей жив", " сколько жителей ", " численность населен", " population ")) {
            intent = INTENT_POPULATION;
            intentWordsToStrip = new String[]{"население", "населения", "численность", "человек", "людей", "жителей", "живет", "проживает", "population"};
            topicKeywordRu = "население";
            topicKeywordEn = "population";
        } else if (containsAny(norm, " площад", " размер территории ", " area of ")) {
            intent = INTENT_AREA;
            intentWordsToStrip = new String[]{"площадь", "площади", "размер", "территории", "территория", "area"};
            topicKeywordRu = "площадь";
            topicKeywordEn = "area";
        } else if (containsAny(norm, " официальный язык", " государственный язык", " на каком языке говор", " какой язык в ", " какие языки в ", " official language", " language spoken")) {
            intent = INTENT_LANGUAGE;
            intentWordsToStrip = new String[]{"официальный", "официальные", "государственный", "государственные", "язык", "языка", "языке", "языки", "говорят", "разговаривают", "official", "language", "languages", "spoken"};
            topicKeywordRu = "официальный язык";
            topicKeywordEn = "official language";
        } else if (containsAny(norm, " премьер-министр", " премьер министр", " канцлер ", " глава правительства ", " prime minister", " chancellor ")) {
            intent = INTENT_HEAD_GOV;
            intentWordsToStrip = new String[]{"премьер-министр", "премьер", "министр", "канцлер", "глава", "правительства", "prime", "minister", "chancellor"};
            topicKeywordRu = "глава правительства";
            topicKeywordEn = "prime minister";
        } else if (containsAny(norm, " президент", " король ", " королева ", " монарх ", " император ", " глава государства ", " president ", " king of ", " queen of ")) {
            intent = INTENT_HEAD_STATE;
            intentWordsToStrip = new String[]{"президент", "президентом", "король", "королева", "монарх", "император", "глава", "государства", "правит", "president", "king", "queen", "monarch"};
            topicKeywordRu = "президент";
            topicKeywordEn = "president";
        } else if (containsAny(norm, " генеральный директор ", " гендиректор ", " глава компании ", " кто возглавляет ", " ceo ")) {
            intent = INTENT_CEO;
            intentWordsToStrip = new String[]{"генеральный", "гендиректор", "директор", "глава", "компании", "возглавляет", "руководитель", "ceo"};
            topicKeywordRu = "генеральный директор";
            topicKeywordEn = "CEO";
        } else if (containsAny(norm, " кто написал ", " кто автор ", " автор книги ", " автор романа ", " автор пьесы ", " автор стихотворения ", " чей роман ", " чья книга ", " who wrote ", " author of ")) {
            intent = INTENT_AUTHOR;
            intentWordsToStrip = new String[]{"написал", "написала", "автор", "книги", "романа", "пьесы", "повести", "рассказа", "стихотворения", "произведения", "роман", "книга", "wrote", "author"};
            topicKeywordRu = "автор";
            topicKeywordEn = "author";
        } else if (containsAny(norm, " кто снял ", " кто режиссер ", " режиссер фильма ", " режиссер ", " who directed ", " director of ")) {
            intent = INTENT_DIRECTOR;
            intentWordsToStrip = new String[]{"снял", "сняла", "режиссер", "фильма", "фильм", "сериала", "сериал", "картины", "directed", "director"};
            topicKeywordRu = "режиссёр";
            topicKeywordEn = "director";
        } else if (containsAny(norm, " кто основал ", " основатель ", " кто создал ", " создатель ", " кто изобрел ", " изобретатель ", " кто придумал ", " who founded ", " founder of ", " who invented ", " inventor of ", " who created ")) {
            intent = INTENT_FOUNDER;
            intentWordsToStrip = new String[]{"основал", "основала", "основали", "основатель", "создал", "создала", "создали", "создатель", "изобрел", "изобрела", "изобретатель", "придумал", "разработал", "компании", "компанию", "founded", "founder", "invented", "inventor", "created", "creator"};
            topicKeywordRu = "основатель";
            topicKeywordEn = "founder";
        } else if (containsAny(norm, " сколько лет ", " какой возраст ", " возраст ", " how old is ")) {
            intent = INTENT_AGE;
            intentWordsToStrip = new String[]{"сколько", "лет", "полных", "возраст", "исполнилось", "сейчас", "old"};
            topicKeywordRu = "дата рождения";
            topicKeywordEn = "born";
        } else if (containsAny(norm, " когда родил", " дата рождения ", " год рождения ", " when was ") && containsAny(norm, " родил", " рожден", " born ")) {
            intent = INTENT_BIRTH_DATE;
            intentWordsToStrip = new String[]{"родился", "родилась", "дата", "год", "день", "рождения", "born", "birth"};
            topicKeywordRu = "дата рождения";
            topicKeywordEn = "birth date";
        } else if (containsAny(norm, " где родил", " место рождения ", " откуда родом ", " birthplace ", " where was ") && containsAny(norm, " родил", " рожден", " родом ", " born ", " birthplace ")) {
            intent = INTENT_BIRTHPLACE;
            intentWordsToStrip = new String[]{"родился", "родилась", "место", "город", "рождения", "откуда", "родом", "born", "birthplace"};
            topicKeywordRu = "место рождения";
            topicKeywordEn = "birthplace";
        } else if (containsAny(norm, " когда умер", " дата смерти ", " год смерти ", " when did ") && containsAny(norm, " умер", " смерт", " скончал", " die", " death ")) {
            intent = INTENT_DEATH_DATE;
            intentWordsToStrip = new String[]{"умер", "умерла", "скончался", "скончалась", "дата", "год", "смерти", "died", "die", "death"};
            topicKeywordRu = "дата смерти";
            topicKeywordEn = "death date";
        } else if (containsAny(norm, " когда основан", " год основания ", " дата основания ", " когда создан", " когда вышел ", " когда вышла ", " дата выхода ", " год выхода ", " когда началась ", " когда начался ", " founded in ", " release date ")) {
            intent = INTENT_INCEPTION;
            intentWordsToStrip = new String[]{"основан", "основана", "основано", "основали", "год", "дата", "основания", "создан", "создана", "создания", "вышел", "вышла", "выхода", "началась", "начался", "начала", "founded", "created", "released", "release", "date"};
            topicKeywordRu = "дата основания";
            topicKeywordEn = "inception";
        } else if (containsAny(norm, " в какой стране ", " какая страна ", " где находится ", " what country ", " which country ")) {
            intent = INTENT_COUNTRY;
            intentWordsToStrip = new String[]{"какой", "стране", "страна", "находится", "расположен", "расположена", "расположено", "город", "country", "located"};
            topicKeywordRu = "страна";
            topicKeywordEn = "country";
        } else if (containsAny(norm, " высота ", " какая высота ", " какой рост ", " рост ", " height of ", " elevation of ", " how tall ", " how high ")) {
            intent = INTENT_HEIGHT;
            intentWordsToStrip = new String[]{"высота", "высоту", "рост", "роста", "горы", "башни", "здания", "height", "elevation", "tall", "high"};
            topicKeywordRu = "высота";
            topicKeywordEn = "height";
        } else if (containsAny(norm, " длина ", " какая длина ", " протяженность ", " length of ", " how long is ")) {
            intent = INTENT_LENGTH;
            intentWordsToStrip = new String[]{"длина", "длину", "протяженность", "реки", "дороги", "length", "long"};
            topicKeywordRu = "длина";
            topicKeywordEn = "length";
        } else if (containsAny(norm, " масса ", " какой вес ", " сколько весит ", " вес ", " mass of ", " weight of ")) {
            intent = INTENT_MASS;
            intentWordsToStrip = new String[]{"масса", "вес", "весит", "тяжелый", "mass", "weight", "heavy"};
            topicKeywordRu = "масса";
            topicKeywordEn = "mass";
        }

        if (intent != INTENT_NONE) {
            final String subjRaw = extractSubjectPhrase(clean, intentWordsToStrip);
            final String subjNorm = normalizeSubjectEntity(subjRaw, intent);
            final boolean cyr = containsCyrillic(clean);
            final String kw = cyr ? topicKeywordRu : topicKeywordEn;
            final String focused = !subjNorm.isEmpty() ? (kw + " " + subjNorm).trim() : clean;
            return new QuestionAnalysis(intent, clean, subjRaw, subjNorm, focused);
        }

        // 2. Определение термина («что такое инфляция», «кто такой Илон Маск»)
        final String lowerClean = clean.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String opener : DEFINITION_OPENERS) {
            if (lowerClean.startsWith(opener) && clean.length() > opener.length() + 1) {
                final String term = clean.substring(opener.length()).trim();
                return new QuestionAnalysis(INTENT_DEFINITION, clean, term, term, term);
            }
        }

        // 3. Общий открытый вопрос («почему...», «зачем...», «сколько варить...», «как сделать...»)
        if (startsWithQuestionWord(lowerClean)) {
            final String strippedKeywords = stripConversationalVerbs(clean);
            return new QuestionAnalysis(INTENT_OPEN_QUESTION, clean, "", "", strippedKeywords.isEmpty() ? clean : strippedKeywords);
        }

        // 4. Прямой ввод термина или названия («тенге», «инфляция», «Казахстан»)
        return new QuestionAnalysis(INTENT_DEFINITION, clean, clean, clean, clean);
    }

    private static boolean startsWithQuestionWord(String lower) {
        final String[] qStarts = new String[]{
                "какая ", "какой ", "какие ", "какое ", "как ", "почему ", "зачем ", "отчего ",
                "сколько ", "когда ", "где ", "куда ", "откуда ", "кто ", "чем ", "кому ", "можно ли ", "нужно ли ", "правда ли ",
                "how ", "why ", "when ", "where ", "what ", "which ", "who ", "can ", "does ", "is "
        };
        for (String s : qStarts) {
            if (lower.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static String extractSubjectPhrase(String cleanQuery, String[] intentWords) {
        final Set<String> extraStrip = new HashSet<>();
        if (intentWords != null) {
            Collections.addAll(extraStrip, intentWords);
        }
        final String[] rawTokens = cleanQuery.split("\\s+");
        final ArrayList<String> kept = new ArrayList<>();
        for (int i = 0; i < rawTokens.length; i++) {
            String tok = trimPunctuation(rawTokens[i]);
            if (tok.isEmpty()) {
                continue;
            }
            final String low = tok.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (extraStrip.contains(low)) {
                continue;
            }
            // Внутри названия произведения («Война и мир», «Мастер и Маргарита») сохраняем союзы между значимыми словами
            if (FILLER_STOP_WORDS.contains(low)) {
                if (("и".equals(low) || "в".equals(low) || "на".equals(low) || "of".equals(low) || "and".equals(low))
                        && !kept.isEmpty() && i + 1 < rawTokens.length) {
                    final String nextLow = trimPunctuation(rawTokens[i + 1]).toLowerCase(Locale.ROOT).replace('ё', 'е');
                    if (!FILLER_STOP_WORDS.contains(nextLow) && !extraStrip.contains(nextLow)) {
                        kept.add(tok);
                    }
                }
                continue;
            }
            kept.add(tok);
        }
        return joinStrings(kept, " ");
    }

    private static String normalizeSubjectEntity(String rawSubject, int intent) {
        if (TextUtils.isEmpty(rawSubject)) {
            return "";
        }
        final String trimmed = rawSubject.trim();
        final String low = trimmed.toLowerCase(Locale.ROOT).replace('ё', 'е');

        // Точное совпадение с короткими аббревиатурами стран («кз», «рф», «сша», «оаэ»)
        final String exactSyn = COUNTRY_SYNONYMS.get(low);
        if (exactSyn != null) {
            return exactSyn;
        }

        // Для географических/государственных вопросов нормализуем прилагательные и падежи стран («китайская» -> «Китай», «Казахстане» -> «Казахстан»)
        final boolean geoIntent = intent == INTENT_CURRENCY || intent == INTENT_CAPITAL || intent == INTENT_LANGUAGE
                || intent == INTENT_POPULATION || intent == INTENT_AREA || intent == INTENT_HEAD_STATE
                || intent == INTENT_HEAD_GOV || intent == INTENT_CALLING_CODE || intent == INTENT_TLD;
        if (geoIntent) {
            if (low.contains("южн") && low.contains("коре")) return "Республика Корея";
            if (low.contains("северн") && low.contains("коре")) return "КНДР";
            if (low.contains("саудовск") && low.contains("арави")) return "Саудовская Аравия";
            if (low.contains("нов") && low.contains("зеланди")) return "Новая Зеландия";
            if (low.contains("южн") && low.contains("африк")) return "Южно-Африканская Республика";
            if (low.contains("арабск") && low.contains("эмират")) return "Объединённые Арабские Эмираты";
            if (low.contains("соединенн") && low.contains("штат")) return "США";
            if (trimmed.indexOf(' ') < 0) {
                for (Map.Entry<String, String> e : COUNTRY_SYNONYMS.entrySet()) {
                    final String stem = e.getKey();
                    if (stem.length() >= 4 && low.startsWith(stem)) {
                        return e.getValue();
                    }
                }
            }
        }
        return trimmed;
    }

    private static String stripConversationalVerbs(String query) {
        final String[] tokens = query.split("\\s+");
        final ArrayList<String> out = new ArrayList<>();
        for (String t : tokens) {
            final String clean = trimPunctuation(t);
            if (clean.isEmpty()) continue;
            final String low = clean.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if ("подскажи".equals(low) || "скажи".equals(low) || "пожалуйста".equals(low)
                    || "используется".equals(low) || "называется".equals(low)) {
                continue;
            }
            out.add(clean);
        }
        return joinStrings(out, " ");
    }

    public static String simplifyQuestionForWiki(String rawQuery) {
        final QuestionAnalysis qa = parseQuestionIntent(rawQuery);
        if (!qa.focusedSearchQuery.isEmpty()) {
            return qa.focusedSearchQuery;
        }
        return rawQuery != null ? rawQuery.trim() : "";
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
    // Кэшированный асинхронный поиск (4 параллельных потока)
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
                        "",
                        query + " = " + calc.primaryReplacement,
                        "Pengram",
                        "",
                        true
                );
            }
        } catch (Throwable ignore) {
        }

        final QuestionAnalysis qa = parseQuestionIntent(query);
        final boolean cyrillic = containsCyrillic(query);
        final String primaryLang = cyrillic ? "ru" : (isRussianUiLocale() ? "ru" : "en");

        final InstantAnswer[] wikidataHolder = new InstantAnswer[1];
        final WikiFetchResult[] wikiHolder = new WikiFetchResult[1];
        final DdgInstantResult[] ddgInstantHolder = new DdgInstantResult[1];
        final List<WebHit>[] webHitsHolder = new List[1];
        final List<String>[] suggestHolder = new List[1];

        final CountDownLatch latch = new CountDownLatch(4);

        // Поток 1: Граф знаний Wikidata (точный структурированный ответ на фактические вопросы)
        SEARCH_POOL.execute(() -> {
            try {
                if (!handle.isCancelled() && qa.hasStructuredProperty()) {
                    wikidataHolder[0] = resolveWikidataFact(qa, cyrillic ? "ru" : "en");
                }
            } catch (Throwable ignore) {
            } finally {
                latch.countDown();
            }
        });

        // Поток 2: Wikipedia CirrusSearch (с очищенным запросом и проверкой релевантности)
        SEARCH_POOL.execute(() -> {
            try {
                if (!handle.isCancelled()) {
                    WikiFetchResult res = fetchWikipedia(qa, cyrillic ? "ru" : "en");
                    if ((res == null || res.hits.isEmpty()) && !handle.isCancelled()) {
                        res = fetchWikipedia(qa, cyrillic ? "en" : "ru");
                    }
                    wikiHolder[0] = res;
                }
            } catch (Throwable ignore) {
            } finally {
                latch.countDown();
            }
        });

        // Поток 3: Живая веб-выдача (DuckDuckGo POST -> Brave Search -> DuckDuckGo Lite + DDG Instant)
        SEARCH_POOL.execute(() -> {
            try {
                if (!handle.isCancelled()) {
                    ddgInstantHolder[0] = fetchDuckDuckGoInstant(query);
                }
                if (!handle.isCancelled()) {
                    webHitsHolder[0] = fetchCombinedWebResults(query, qa.focusedSearchQuery, cyrillic || "ru".equals(primaryLang), handle);
                }
            } catch (Throwable ignore) {
            } finally {
                latch.countDown();
            }
        });

        // Поток 4: Поисковые подсказки Google Suggest
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
            latch.await(4900, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }

        // Приоритет точного ответа:
        // 1) Калькулятор/курс  2) Wikidata Knowledge Graph  3) DDG Direct Answer  4) Релевантная статья Википедии  5) Извлечённое предложение из топ-сниппетов
        InstantAnswer instant = calcInstant;
        final WikiFetchResult wiki = wikiHolder[0];
        final DdgInstantResult ddgInstant = ddgInstantHolder[0];

        if (instant == null && wikidataHolder[0] != null) {
            instant = wikidataHolder[0];
        }
        if (instant == null && ddgInstant != null && ddgInstant.directAnswer != null) {
            instant = ddgInstant.directAnswer;
        }
        if (instant == null && wiki != null && wiki.primaryInstant != null) {
            instant = wiki.primaryInstant;
        }
        if (instant == null && ddgInstant != null && ddgInstant.abstractAnswer != null && qa.intent == INTENT_DEFINITION) {
            instant = ddgInstant.abstractAnswer;
        }

        // Собираем веб-результаты без дубликатов (сначала релевантная Википедия для справочных вопросов, затем живая выдача)
        final ArrayList<WebHit> mergedHits = new ArrayList<>();
        final HashSet<String> seenUrls = new HashSet<>();

        if (webHitsHolder[0] != null) {
            for (WebHit h : webHitsHolder[0]) {
                if (h != null && !TextUtils.isEmpty(h.url) && seenUrls.add(normalizeUrlKey(h.url))) {
                    mergedHits.add(h);
                }
            }
        }
        if (wiki != null && wiki.hits != null) {
            int insertPos = mergedHits.isEmpty() ? 0 : Math.min(1, mergedHits.size());
            for (WebHit h : wiki.hits) {
                if (h != null && !TextUtils.isEmpty(h.url) && seenUrls.add(normalizeUrlKey(h.url))) {
                    if (insertPos <= mergedHits.size()) {
                        mergedHits.add(insertPos++, h);
                    } else {
                        mergedHits.add(h);
                    }
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

        // Если готовой карточки нет — пробуем аккуратно вытащить фактическое предложение из лучших сниппетов (БЕЗ подстановки кликбейтных заголовков или URL!)
        if (instant == null && !mergedHits.isEmpty()) {
            instant = buildBestSnippetPreview(query, mergedHits);
        }

        final List<String> suggestions = suggestHolder[0] != null ? suggestHolder[0] : Collections.emptyList();
        return new SearchResponse(query, instant, mergedHits, suggestions);
    }

    // -------------------------------------------------------------------------
    // Источник 1: Граф знаний Wikidata (точные структурированные ответы)
    // -------------------------------------------------------------------------

    private static InstantAnswer resolveWikidataFact(QuestionAnalysis qa, String lang) {
        String qid = resolveSubjectQidViaWikipedia(qa.subjectNormalized, lang);
        if (TextUtils.isEmpty(qid) && !qa.subjectRaw.equalsIgnoreCase(qa.subjectNormalized)) {
            qid = resolveSubjectQidViaWikipedia(qa.subjectRaw, lang);
        }
        if (TextUtils.isEmpty(qid)) {
            qid = resolveSubjectQidViaWikidataSearch(qa.subjectNormalized, lang);
        }
        if (TextUtils.isEmpty(qid)) {
            return null;
        }

        final JSONObject entity = fetchWikidataEntities(qid);
        if (entity == null) {
            return null;
        }
        final JSONObject subjObj = entity.optJSONObject(qid);
        if (subjObj == null) {
            return null;
        }
        final String subjLabel = humanEntityName(getLocalizedWikidataLabel(subjObj, lang));
        final JSONObject claims = subjObj.optJSONObject("claims");
        if (claims == null) {
            return null;
        }

        final String[] propCandidates = propertiesForIntent(qa.intent);
        JSONArray matchedClaims = null;
        String usedProp = "";
        for (String prop : propCandidates) {
            final JSONArray arr = claims.optJSONArray(prop);
            if (arr != null && arr.length() > 0) {
                matchedClaims = arr;
                usedProp = prop;
                break;
            }
        }
        if (matchedClaims == null) {
            return null;
        }

        final boolean allowMultiple = qa.intent == INTENT_LANGUAGE || qa.intent == INTENT_AUTHOR
                || qa.intent == INTENT_DIRECTOR || qa.intent == INTENT_FOUNDER;
        final List<JSONObject> bestStmts = selectBestWikidataClaims(matchedClaims, allowMultiple);
        if (bestStmts.isEmpty()) {
            return null;
        }

        final String wikiArticleUrl = getSitelinkUrl(subjObj, lang);
        final boolean ru = "ru".equals(lang);

        // Разбираем тип значения первого лучшего утверждения
        final JSONObject firstSnak = bestStmts.get(0).optJSONObject("mainsnak");
        if (firstSnak == null || !"value".equals(firstSnak.optString("snaktype"))) {
            return null;
        }
        final JSONObject firstDv = firstSnak.optJSONObject("datavalue");
        if (firstDv == null) {
            return null;
        }
        final String dvType = firstDv.optString("type", "");

        // 1) Ссылка на сущность Wikidata (валюта, столица, президент, автор, страна...)
        if ("wikibase-entityid".equals(dvType)) {
            final ArrayList<String> targetIds = new ArrayList<>();
            for (JSONObject stmt : bestStmts) {
                final JSONObject ms = stmt.optJSONObject("mainsnak");
                final JSONObject dv = ms != null ? ms.optJSONObject("datavalue") : null;
                final JSONObject val = dv != null ? dv.optJSONObject("value") : null;
                final String tid = val != null ? val.optString("id", "") : "";
                if (!tid.isEmpty() && !targetIds.contains(tid) && targetIds.size() < 3) {
                    targetIds.add(tid);
                }
            }
            if (targetIds.isEmpty()) {
                return null;
            }
            final JSONObject targetEntities = fetchWikidataEntities(joinStrings(targetIds, "|"));
            if (targetEntities == null) {
                return null;
            }

            // Специальная богатая обработка для ВАЛЮТЫ (код ISO 4217 + символ валюты + живой курс!)
            if (qa.intent == INTENT_CURRENCY) {
                final JSONObject curObj = targetEntities.optJSONObject(targetIds.get(0));
                if (curObj == null) {
                    return null;
                }
                final String fullCurLabel = humanEntityName(getLocalizedWikidataLabel(curObj, lang));
                final String sitelinkTitle = cleanParentheticalTitle(getSitelinkTitle(curObj, lang));
                final String shortCurName = pickShortCurrencyName(fullCurLabel, sitelinkTitle, ru);
                final String isoCode = extractFirstStringClaim(curObj.optJSONObject("claims"), "P498");
                final String curSymbol = extractFirstStringClaim(curObj.optJSONObject("claims"), "P489");

                final StringBuilder codeBadge = new StringBuilder();
                if (!isoCode.isEmpty()) {
                    codeBadge.append(isoCode);
                }
                if (!curSymbol.isEmpty() && !curSymbol.equalsIgnoreCase(isoCode)) {
                    if (codeBadge.length() > 0) codeBadge.append(", ");
                    codeBadge.append(curSymbol);
                }

                final String capShort = capitalizeFirst(shortCurName);
                final String cardTitle = codeBadge.length() > 0
                        ? (capShort + " (" + codeBadge + ")")
                        : capShort;
                final String subtitle = ru
                        ? ("Национальная валюта — " + subjLabel)
                        : ("Official currency — " + subjLabel);

                final String displayFull = !fullCurLabel.isEmpty() ? fullCurLabel : shortCurName;
                String summary = ru
                        ? ("Официальная валюта (" + subjLabel + ") — " + displayFull
                        + (codeBadge.length() > 0 ? " (" + codeBadge + ")." : "."))
                        : ("The official currency of " + subjLabel + " is the " + displayFull
                        + (codeBadge.length() > 0 ? " (" + codeBadge + ")." : "."));

                // Если в памяти уже есть живые курсы Pengram — добавляем актуальный ориентир курса!
                if (!isoCode.isEmpty() && PengramCurrencyRates.hasRates() && !"USD".equalsIgnoreCase(isoCode)) {
                    final double perUsd = PengramCurrencyRates.convert(1.0, "USD", isoCode);
                    if (!Double.isNaN(perUsd) && perUsd > 0) {
                        summary += (ru ? " Курс: 1 $ ≈ " : " Rate: 1 $ ≈ ")
                                + formatCompactNumber(perUsd, ru) + " " + (!curSymbol.isEmpty() ? curSymbol : isoCode) + ".";
                    }
                }

                final String shortInsert = shortCurName.toLowerCase(Locale.ROOT);
                final String altInsert = !isoCode.isEmpty() ? (capShort + " (" + isoCode + ")") : capShort;
                final String summaryInsert = ru
                        ? ("Национальная валюта (" + subjLabel + ") — " + displayFull
                        + (codeBadge.length() > 0 ? " (" + codeBadge + ")" : ""))
                        : (capShort + (codeBadge.length() > 0 ? " (" + codeBadge + ")" : ""));
                final String targetUrl = getSitelinkUrl(curObj, lang);

                return new InstantAnswer(
                        cardTitle,
                        subtitle,
                        summary,
                        shortInsert,
                        altInsert,
                        summaryInsert,
                        ru ? "Викиданные" : "Wikidata",
                        !targetUrl.isEmpty() ? targetUrl : wikiArticleUrl,
                        true
                );
            }

            final ArrayList<String> names = new ArrayList<>();
            String firstTargetUrl = "";
            String firstTargetDesc = "";
            for (String tid : targetIds) {
                final JSONObject tobj = targetEntities.optJSONObject(tid);
                if (tobj == null) continue;
                String nm = humanEntityName(getLocalizedWikidataLabel(tobj, lang));
                if (nm.isEmpty()) {
                    nm = humanEntityName(cleanParentheticalTitle(getSitelinkTitle(tobj, lang)));
                }
                if (!nm.isEmpty()) {
                    if (qa.intent == INTENT_LANGUAGE) {
                        nm = nm.replace(" язык", "").trim();
                    }
                    names.add(nm);
                }
                if (firstTargetUrl.isEmpty()) {
                    firstTargetUrl = getSitelinkUrl(tobj, lang);
                }
                if (firstTargetDesc.isEmpty()) {
                    firstTargetDesc = getLocalizedWikidataDescription(tobj, lang);
                }
            }
            if (names.isEmpty()) {
                return null;
            }

            final String joined = joinStrings(names, ", ");
            final String propLabel = intentDisplayLabel(qa.intent, ru);
            final String subtitle = !subjLabel.isEmpty() ? (propLabel + " — " + subjLabel) : propLabel;
            final String summary = !firstTargetDesc.isEmpty()
                    ? (capitalizeFirst(joined) + " — " + firstTargetDesc + ".")
                    : (subtitle + ": " + joined + ".");
            final String summaryInsert = !subjLabel.isEmpty()
                    ? (propLabel + " (" + subjLabel + ") — " + joined)
                    : joined;

            return new InstantAnswer(
                    capitalizeFirst(joined),
                    subtitle,
                    summary,
                    joined,
                    "",
                    summaryInsert,
                    ru ? "Викиданные" : "Wikidata",
                    !firstTargetUrl.isEmpty() ? firstTargetUrl : wikiArticleUrl,
                    true
            );
        }

        // 2) Числовая величина (население, площадь, высота, длина, масса)
        if ("quantity".equals(dvType)) {
            final JSONObject val = firstDv.optJSONObject("value");
            if (val == null) return null;
            String amountStr = val.optString("amount", "");
            if (amountStr.startsWith("+")) {
                amountStr = amountStr.substring(1);
            }
            final double amount;
            try {
                amount = Double.parseDouble(amountStr);
            } catch (Throwable e) {
                return null;
            }
            final String unitUri = val.optString("unit", "");
            final String formatted = formatWikidataQuantity(amount, unitUri, qa.intent, ru);
            final String propLabel = intentDisplayLabel(qa.intent, ru);
            final String yearNote = extractPointInTimeYear(bestStmts.get(0));
            final String subtitle = !subjLabel.isEmpty()
                    ? (propLabel + " — " + subjLabel + (!yearNote.isEmpty() ? " (" + yearNote + ")" : ""))
                    : propLabel;
            final String summary = subtitle + ": " + formatted + ".";
            return new InstantAnswer(
                    formatted,
                    subtitle,
                    summary,
                    formatted,
                    "",
                    (!subjLabel.isEmpty() ? subjLabel + " — " : "") + formatted,
                    ru ? "Викиданные" : "Wikidata",
                    wikiArticleUrl,
                    true
            );
        }

        // 3) Дата / время / возраст
        if ("time".equals(dvType)) {
            final JSONObject val = firstDv.optJSONObject("value");
            if (val == null) return null;
            final String rawTime = val.optString("time", "");
            final int precision = val.optInt("precision", 9);

            if (qa.intent == INTENT_AGE) {
                final String ageStr = computeAgeFromWikidataTime(rawTime, ru);
                final String dateStr = formatWikidataTime(rawTime, precision, ru);
                if (!ageStr.isEmpty()) {
                    final String sub = !subjLabel.isEmpty() ? ("Возраст — " + subjLabel) : (ru ? "Возраст" : "Age");
                    final String sum = !dateStr.isEmpty()
                            ? (ageStr + " (" + (ru ? "дата рождения: " : "born: ") + dateStr + ").")
                            : ageStr;
                    return new InstantAnswer(
                            ageStr,
                            sub,
                            sum,
                            ageStr,
                            !dateStr.isEmpty() ? (ageStr + " (" + dateStr + ")") : "",
                            (!subjLabel.isEmpty() ? subjLabel + " — " : "") + sum,
                            ru ? "Викиданные" : "Wikidata",
                            wikiArticleUrl,
                            true
                    );
                }
            }

            final String formattedDate = formatWikidataTime(rawTime, precision, ru);
            if (formattedDate.isEmpty()) {
                return null;
            }
            final String propLabel = intentDisplayLabel(qa.intent, ru);
            final String subtitle = !subjLabel.isEmpty() ? (propLabel + " — " + subjLabel) : propLabel;
            return new InstantAnswer(
                    formattedDate,
                    subtitle,
                    subtitle + ": " + formattedDate + ".",
                    formattedDate,
                    "",
                    subtitle + " — " + formattedDate,
                    ru ? "Викиданные" : "Wikidata",
                    wikiArticleUrl,
                    true
            );
        }

        // 4) Строка (телефонный код +7, химическая формула H₂SO₄, домен .kz)
        if ("string".equals(dvType) || "monolingualtext".equals(dvType)) {
            final String sVal;
            if ("string".equals(dvType)) {
                sVal = cleanWhitespace(firstDv.optString("value", ""));
            } else {
                final JSONObject vObj = firstDv.optJSONObject("value");
                sVal = vObj != null ? cleanWhitespace(vObj.optString("text", "")) : "";
            }
            if (sVal.isEmpty()) {
                return null;
            }
            final String propLabel = intentDisplayLabel(qa.intent, ru);
            final String subtitle = !subjLabel.isEmpty() ? (propLabel + " — " + subjLabel) : propLabel;
            return new InstantAnswer(
                    sVal,
                    subtitle,
                    subtitle + ": " + sVal + ".",
                    sVal,
                    "",
                    subtitle + " — " + sVal,
                    ru ? "Викиданные" : "Wikidata",
                    wikiArticleUrl,
                    true
            );
        }

        return null;
    }

    private static String[] propertiesForIntent(int intent) {
        switch (intent) {
            case INTENT_CURRENCY: return new String[]{"P38"};
            case INTENT_CAPITAL: return new String[]{"P36"};
            case INTENT_LANGUAGE: return new String[]{"P37", "P2936"};
            case INTENT_POPULATION: return new String[]{"P1082"};
            case INTENT_AREA: return new String[]{"P2046"};
            case INTENT_HEAD_STATE: return new String[]{"P35"};
            case INTENT_HEAD_GOV: return new String[]{"P6"};
            case INTENT_COUNTRY: return new String[]{"P17"};
            case INTENT_AUTHOR: return new String[]{"P50"};
            case INTENT_DIRECTOR: return new String[]{"P57"};
            case INTENT_FOUNDER: return new String[]{"P112", "P61", "P170", "P178"};
            case INTENT_CEO: return new String[]{"P169", "P488"};
            case INTENT_INCEPTION: return new String[]{"P571", "P577", "P580", "P585"};
            case INTENT_BIRTH_DATE:
            case INTENT_AGE: return new String[]{"P569"};
            case INTENT_DEATH_DATE: return new String[]{"P570"};
            case INTENT_BIRTHPLACE: return new String[]{"P19"};
            case INTENT_HEIGHT: return new String[]{"P2044", "P2048"};
            case INTENT_LENGTH: return new String[]{"P2043"};
            case INTENT_MASS: return new String[]{"P2067"};
            case INTENT_CALLING_CODE: return new String[]{"P474"};
            case INTENT_TLD: return new String[]{"P78"};
            case INTENT_FORMULA: return new String[]{"P274"};
            default: return new String[0];
        }
    }

    private static String intentDisplayLabel(int intent, boolean ru) {
        switch (intent) {
            case INTENT_CURRENCY: return ru ? "Валюта" : "Currency";
            case INTENT_CAPITAL: return ru ? "Столица" : "Capital";
            case INTENT_LANGUAGE: return ru ? "Официальный язык" : "Official language";
            case INTENT_POPULATION: return ru ? "Население" : "Population";
            case INTENT_AREA: return ru ? "Площадь" : "Area";
            case INTENT_HEAD_STATE: return ru ? "Глава государства" : "Head of state";
            case INTENT_HEAD_GOV: return ru ? "Глава правительства" : "Head of government";
            case INTENT_COUNTRY: return ru ? "Страна" : "Country";
            case INTENT_AUTHOR: return ru ? "Автор" : "Author";
            case INTENT_DIRECTOR: return ru ? "Режиссёр" : "Director";
            case INTENT_FOUNDER: return ru ? "Основатель / создатель" : "Founder / creator";
            case INTENT_CEO: return ru ? "Руководитель (CEO)" : "CEO";
            case INTENT_INCEPTION: return ru ? "Дата создания" : "Date";
            case INTENT_BIRTH_DATE: return ru ? "Дата рождения" : "Born";
            case INTENT_AGE: return ru ? "Возраст" : "Age";
            case INTENT_DEATH_DATE: return ru ? "Дата смерти" : "Died";
            case INTENT_BIRTHPLACE: return ru ? "Место рождения" : "Birthplace";
            case INTENT_HEIGHT: return ru ? "Высота / рост" : "Height";
            case INTENT_LENGTH: return ru ? "Длина" : "Length";
            case INTENT_MASS: return ru ? "Масса" : "Mass";
            case INTENT_CALLING_CODE: return ru ? "Телефонный код" : "Calling code";
            case INTENT_TLD: return ru ? "Домен" : "Domain";
            case INTENT_FORMULA: return ru ? "Формула" : "Formula";
            default: return ru ? "Ответ" : "Answer";
        }
    }

    /**
     * Выбирает актуальные утверждения из Wikidata:
     * - отбрасывает deprecated;
     * - штрафует утверждения с датой окончания P582 (бывшие валюты вроде советского рубля/франка, бывшие президенты);
     * - предпочитает rank == preferred и более свежие даты P585 / P580.
     */
    public static List<JSONObject> selectBestWikidataClaims(JSONArray claimsArray, boolean allowMultiple) {
        if (claimsArray == null || claimsArray.length() == 0) {
            return Collections.emptyList();
        }
        final class ScoredClaim {
            final JSONObject stmt;
            final double score;

            ScoredClaim(JSONObject stmt, double score) {
                this.stmt = stmt;
                this.score = score;
            }
        }
        final ArrayList<ScoredClaim> scored = new ArrayList<>();
        for (int i = 0; i < claimsArray.length(); i++) {
            final JSONObject st = claimsArray.optJSONObject(i);
            if (st == null) continue;
            final String rank = st.optString("rank", "normal");
            if ("deprecated".equals(rank)) continue;
            final JSONObject mainsnak = st.optJSONObject("mainsnak");
            if (mainsnak == null || !"value".equals(mainsnak.optString("snaktype"))) continue;

            double s = "preferred".equals(rank) ? 1000.0 : 500.0;
            final JSONObject quals = st.optJSONObject("qualifiers");
            if (quals != null) {
                if (hasValueQualifier(quals, "P582")) {
                    // Завершившийся период (историческая валюта, бывший президент и т.п.)
                    s -= 800.0;
                }
                final int year585 = extractQualifierYear(quals, "P585");
                final int year580 = extractQualifierYear(quals, "P580");
                final int bestYear = Math.max(year585, year580);
                if (bestYear > 0) {
                    s += bestYear * 0.1;
                }
            }
            scored.add(new ScoredClaim(st, s));
        }
        if (scored.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));
        final ArrayList<JSONObject> out = new ArrayList<>();
        final double topScore = scored.get(0).score;
        out.add(scored.get(0).stmt);
        if (allowMultiple) {
            for (int i = 1; i < scored.size() && out.size() < 3; i++) {
                if (topScore - scored.get(i).score <= 8.0 && scored.get(i).score > 0) {
                    out.add(scored.get(i).stmt);
                }
            }
        }
        return out;
    }

    private static boolean hasValueQualifier(JSONObject qualifiers, String prop) {
        final JSONArray arr = qualifiers.optJSONArray(prop);
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject q = arr.optJSONObject(i);
            if (q != null && "value".equals(q.optString("snaktype"))) {
                return true;
            }
        }
        return false;
    }

    private static int extractQualifierYear(JSONObject qualifiers, String prop) {
        final JSONArray arr = qualifiers.optJSONArray(prop);
        if (arr == null || arr.length() == 0) return 0;
        final JSONObject q = arr.optJSONObject(0);
        final JSONObject dv = q != null ? q.optJSONObject("datavalue") : null;
        final JSONObject val = dv != null ? dv.optJSONObject("value") : null;
        final String t = val != null ? val.optString("time", "") : "";
        return parseYearFromWikidataTime(t);
    }

    private static String extractPointInTimeYear(JSONObject stmt) {
        final JSONObject quals = stmt != null ? stmt.optJSONObject("qualifiers") : null;
        if (quals == null) return "";
        final int y = extractQualifierYear(quals, "P585");
        return y > 0 ? String.valueOf(y) : "";
    }

    private static String resolveSubjectQidViaWikipedia(String subject, String lang) {
        if (TextUtils.isEmpty(subject)) return "";
        final String endpoint = "https://" + lang + ".wikipedia.org/w/api.php"
                + "?action=query&format=json&utf8=1&redirects=1"
                + "&generator=search&gsrsearch=" + encodeParam(subject)
                + "&gsrlimit=3&prop=pageprops|description&ppprop=wikibase_item";
        final String body = httpGet(endpoint, WIKI_USER_AGENT, "application/json");
        if (TextUtils.isEmpty(body)) return "";
        try {
            final JSONObject query = new JSONObject(body).optJSONObject("query");
            final JSONObject pages = query != null ? query.optJSONObject("pages") : null;
            final JSONArray names = pages != null ? pages.names() : null;
            if (names == null || names.length() == 0) return "";

            String bestQid = "";
            int bestIdx = Integer.MAX_VALUE;
            for (int i = 0; i < names.length(); i++) {
                final JSONObject p = pages.optJSONObject(names.getString(i));
                if (p == null) continue;
                final String title = p.optString("title", "");
                final String desc = p.optString("description", "");
                if (isDisambiguation(title, desc, "")) continue;
                final JSONObject pp = p.optJSONObject("pageprops");
                final String qid = pp != null ? pp.optString("wikibase_item", "") : "";
                final int idx = p.optInt("index", 999);
                if (!qid.isEmpty() && idx < bestIdx) {
                    bestIdx = idx;
                    bestQid = qid;
                }
            }
            return bestQid;
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String resolveSubjectQidViaWikidataSearch(String subject, String lang) {
        if (TextUtils.isEmpty(subject)) return "";
        final String endpoint = "https://www.wikidata.org/w/api.php"
                + "?action=wbsearchentities&format=json&utf8=1"
                + "&search=" + encodeParam(subject)
                + "&language=" + encodeParam(lang)
                + "&uselang=" + encodeParam(lang)
                + "&type=item&limit=3";
        final String body = httpGet(endpoint, WIKI_USER_AGENT, "application/json");
        if (TextUtils.isEmpty(body)) return "";
        try {
            final JSONArray arr = new JSONObject(body).optJSONArray("search");
            if (arr == null || arr.length() == 0) return "";
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                final String desc = item.optString("description", "");
                if (isDisambiguation("", desc, "")) continue;
                final String id = item.optString("id", "");
                if (!id.isEmpty()) return id;
            }
        } catch (Throwable ignore) {
        }
        return "";
    }

    private static JSONObject fetchWikidataEntities(String idsPipeSeparated) {
        if (TextUtils.isEmpty(idsPipeSeparated)) return null;
        final String endpoint = "https://www.wikidata.org/w/api.php"
                + "?action=wbgetentities&format=json&utf8=1"
                + "&ids=" + encodeParam(idsPipeSeparated)
                + "&props=labels|descriptions|claims|sitelinks"
                + "&languages=ru|en&sitefilter=ruwiki|enwiki";
        final String body = httpGet(endpoint, WIKI_USER_AGENT, "application/json");
        if (TextUtils.isEmpty(body)) return null;
        try {
            return new JSONObject(body).optJSONObject("entities");
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String getLocalizedWikidataLabel(JSONObject entity, String lang) {
        if (entity == null) return "";
        final JSONObject labels = entity.optJSONObject("labels");
        if (labels != null) {
            final JSONObject primary = labels.optJSONObject(lang);
            if (primary != null && !TextUtils.isEmpty(primary.optString("value"))) {
                return cleanWhitespace(primary.optString("value"));
            }
            final JSONObject fallback = labels.optJSONObject("ru".equals(lang) ? "en" : "ru");
            if (fallback != null && !TextUtils.isEmpty(fallback.optString("value"))) {
                return cleanWhitespace(fallback.optString("value"));
            }
        }
        return "";
    }

    private static String getLocalizedWikidataDescription(JSONObject entity, String lang) {
        if (entity == null) return "";
        final JSONObject descs = entity.optJSONObject("descriptions");
        if (descs != null) {
            final JSONObject primary = descs.optJSONObject(lang);
            if (primary != null && !TextUtils.isEmpty(primary.optString("value"))) {
                return cleanWhitespace(primary.optString("value"));
            }
        }
        return "";
    }

    private static String getSitelinkTitle(JSONObject entity, String lang) {
        if (entity == null) return "";
        final JSONObject links = entity.optJSONObject("sitelinks");
        if (links == null) return "";
        final JSONObject site = links.optJSONObject("ru".equals(lang) ? "ruwiki" : "enwiki");
        return site != null ? cleanWhitespace(site.optString("title", "")) : "";
    }

    private static String getSitelinkUrl(JSONObject entity, String lang) {
        final String title = getSitelinkTitle(entity, lang);
        if (!title.isEmpty()) {
            return "https://" + lang + ".wikipedia.org/wiki/" + encodeParam(title.replace(' ', '_'));
        }
        return "";
    }

    private static String extractFirstStringClaim(JSONObject claims, String prop) {
        if (claims == null) return "";
        final JSONArray arr = claims.optJSONArray(prop);
        final List<JSONObject> best = selectBestWikidataClaims(arr, false);
        if (best.isEmpty()) return "";
        final JSONObject ms = best.get(0).optJSONObject("mainsnak");
        final JSONObject dv = ms != null ? ms.optJSONObject("datavalue") : null;
        return dv != null ? cleanWhitespace(dv.optString("value", "")) : "";
    }

    private static String pickShortCurrencyName(String fullLabel, String sitelinkTitle, boolean ru) {
        // Например, sitelinkTitle = «Тенге», fullLabel = «казахстанский тенге» -> короткая вставка «тенге»
        if (!sitelinkTitle.isEmpty() && sitelinkTitle.indexOf(' ') < 0 && sitelinkTitle.length() <= 18) {
            return sitelinkTitle;
        }
        if (!fullLabel.isEmpty()) {
            final String[] parts = fullLabel.split("\\s+");
            if (parts.length == 2 && ru) {
                final String adj = parts[0].toLowerCase(Locale.ROOT);
                // «казахстанский тенге» -> «тенге», но «доллар США» или «новый шекель» оставляем осмысленными
                if ((adj.endsWith("ский") || adj.endsWith("ская") || adj.endsWith("ское")
                        || adj.endsWith("цкий") || adj.endsWith("ная") || adj.endsWith("ный"))
                        && !"новый".equals(adj)) {
                    final String base = parts[1];
                    // Для мировых одноимённых валют («доллар», «франк», «песо», «рупия», «динар», «крона») лучше сохранить уточнение («доллар США», «швейцарский франк»)
                    final String bLow = base.toLowerCase(Locale.ROOT);
                    if (!"доллар".equals(bLow) && !"франк".equals(bLow) && !"песо".equals(bLow)
                            && !"динар".equals(bLow) && !"крона".equals(bLow) && !"рупия".equals(bLow)
                            && !"фунт".equals(bLow) && !"реал".equals(bLow) && !"риал".equals(bLow)) {
                        return base;
                    }
                }
            }
            return fullLabel;
        }
        return sitelinkTitle;
    }

    private static String humanEntityName(String name) {
        if (TextUtils.isEmpty(name)) return "";
        // Переворачиваем энциклопедический порядок «Булгаков, Михаил Афанасьевич» -> «Михаил Афанасьевич Булгаков»
        final int comma = name.indexOf(", ");
        if (comma > 1 && name.indexOf(',', comma + 2) < 0 && name.indexOf(' ') == comma + 1) {
            final String last = name.substring(0, comma).trim();
            final String rest = name.substring(comma + 2).trim();
            if (!rest.isEmpty() && !last.isEmpty() && Character.isUpperCase(rest.charAt(0))) {
                return rest + " " + last;
            }
        }
        return name;
    }

    private static String cleanParentheticalTitle(String title) {
        if (TextUtils.isEmpty(title)) return "";
        final int open = title.indexOf(" (");
        if (open > 1 && title.endsWith(")")) {
            return title.substring(0, open).trim();
        }
        return title;
    }

    private static String formatWikidataQuantity(double amount, String unitUri, int intent, boolean ru) {
        if (intent == INTENT_POPULATION) {
            if (amount >= 1_000_000_000d) {
                return formatCompactNumber(amount / 1_000_000_000d, ru) + (ru ? " млрд чел." : "B");
            }
            if (amount >= 1_000_000d) {
                return formatCompactNumber(amount / 1_000_000d, ru) + (ru ? " млн чел." : "M");
            }
            return formatGroupedInteger(Math.round(amount), ru) + (ru ? " чел." : "");
        }
        if (intent == INTENT_AREA || (unitUri != null && unitUri.endsWith("Q712226"))) {
            return formatGroupedNumber(amount, ru) + (ru ? " км²" : " km²");
        }
        if (unitUri != null) {
            if (unitUri.endsWith("Q11573")) {
                return formatGroupedNumber(amount, ru) + (ru ? " м" : " m");
            }
            if (unitUri.endsWith("Q828224")) {
                return formatGroupedNumber(amount, ru) + (ru ? " км" : " km");
            }
            if (unitUri.endsWith("Q174728")) {
                return formatGroupedNumber(amount, ru) + (ru ? " см" : " cm");
            }
            if (unitUri.endsWith("Q11570")) {
                return formatGroupedNumber(amount, ru) + (ru ? " кг" : " kg");
            }
        }
        return formatGroupedNumber(amount, ru);
    }

    private static int parseYearFromWikidataTime(String rawTime) {
        if (TextUtils.isEmpty(rawTime) || rawTime.startsWith("-")) return 0;
        String t = rawTime.startsWith("+") ? rawTime.substring(1) : rawTime;
        final int dash = t.indexOf('-');
        if (dash <= 0) return 0;
        try {
            return Integer.parseInt(t.substring(0, dash));
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static String formatWikidataTime(String rawTime, int precision, boolean ru) {
        if (TextUtils.isEmpty(rawTime)) return "";
        final boolean bce = rawTime.startsWith("-");
        final String t = (rawTime.startsWith("+") || rawTime.startsWith("-")) ? rawTime.substring(1) : rawTime;
        final String[] parts = t.split("[-T]");
        if (parts.length < 3) return "";
        try {
            final int year = Integer.parseInt(parts[0]);
            final int month = Integer.parseInt(parts[1]);
            final int day = Integer.parseInt(parts[2]);
            final String suffix = bce ? (ru ? " до н. э." : " BCE") : (ru && precision <= 9 ? " г." : "");
            if (precision >= 11 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                final String[] ruMonths = new String[]{"января", "февраля", "марта", "апреля", "мая", "июня",
                        "июля", "августа", "сентября", "октября", "ноября", "декабря"};
                final String[] enMonths = new String[]{"January", "February", "March", "April", "May", "June",
                        "July", "August", "September", "October", "November", "December"};
                return ru
                        ? (day + " " + ruMonths[month - 1] + " " + year + (bce ? " до н. э." : ""))
                        : (enMonths[month - 1] + " " + day + ", " + year + (bce ? " BCE" : ""));
            }
            if (precision == 10 && month >= 1 && month <= 12) {
                final String[] ruMonthsNom = new String[]{"январь", "февраль", "март", "апрель", "май", "июнь",
                        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"};
                final String[] enMonths = new String[]{"January", "February", "March", "April", "May", "June",
                        "July", "August", "September", "October", "November", "December"};
                return (ru ? ruMonthsNom[month - 1] : enMonths[month - 1]) + " " + year + suffix;
            }
            return year + suffix;
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String computeAgeFromWikidataTime(String rawTime, boolean ru) {
        if (TextUtils.isEmpty(rawTime) || rawTime.startsWith("-")) return "";
        final String t = rawTime.startsWith("+") ? rawTime.substring(1) : rawTime;
        final String[] parts = t.split("[-T]");
        if (parts.length < 3) return "";
        try {
            final int bYear = Integer.parseInt(parts[0]);
            final int bMonth = Math.max(1, Integer.parseInt(parts[1]));
            final int bDay = Math.max(1, Integer.parseInt(parts[2]));
            final Calendar now = Calendar.getInstance();
            final int cYear = now.get(Calendar.YEAR);
            final int cMonth = now.get(Calendar.MONTH) + 1;
            final int cDay = now.get(Calendar.DAY_OF_MONTH);
            int age = cYear - bYear;
            if (cMonth < bMonth || (cMonth == bMonth && cDay < bDay)) {
                age--;
            }
            if (age < 0 || age > 135) return "";
            if (!ru) {
                return age + " years old";
            }
            final int mod100 = age % 100;
            final int mod10 = age % 10;
            final String word;
            if (mod100 >= 11 && mod100 <= 14) {
                word = "лет";
            } else if (mod10 == 1) {
                word = "год";
            } else if (mod10 >= 2 && mod10 <= 4) {
                word = "года";
            } else {
                word = "лет";
            }
            return age + " " + word;
        } catch (Throwable ignore) {
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Источник 2: MediaWiki CirrusSearch API (Wikipedia) с фильтром релевантности
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

    private static WikiFetchResult fetchWikipedia(QuestionAnalysis qa, String lang) {
        final String searchTerm = !qa.focusedSearchQuery.isEmpty() ? qa.focusedSearchQuery : qa.rawQuery;
        WikiFetchResult res = queryMediaWiki(searchTerm, qa, lang);
        if ((res == null || res.hits.isEmpty()) && !searchTerm.equalsIgnoreCase(qa.rawQuery)) {
            res = queryMediaWiki(qa.rawQuery, qa, lang);
        }
        return res;
    }

    private static WikiFetchResult queryMediaWiki(String searchTerm, QuestionAnalysis qa, String lang) {
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
            final JSONObject pagesObj = queryObj != null ? queryObj.optJSONObject("pages") : null;
            final JSONArray names = pagesObj != null ? pagesObj.names() : null;
            if (names == null || names.length() == 0) {
                return null;
            }
            final ArrayList<WikiPage> pages = new ArrayList<>();
            for (int i = 0; i < names.length(); i++) {
                final JSONObject p = pagesObj.optJSONObject(names.getString(i));
                if (p == null) continue;
                final String title = cleanWikiText(p.optString("title", ""));
                if (title.isEmpty()) continue;
                final WikiPage wp = new WikiPage();
                wp.index = p.optInt("index", 999);
                wp.title = title;
                wp.description = cleanWikiText(p.optString("description", ""));
                wp.extract = cleanWikiText(p.optString("extract", ""));
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
                final boolean disambig = isDisambiguation(wp.title, wp.description, wp.extract);
                if (primary == null && !disambig && isWikiPageDirectAnswerMatch(wp, qa)) {
                    final String subtitle = !wp.description.isEmpty() ? wp.description : wikiLabel;
                    final String summary = !wp.extract.isEmpty() ? wp.extract : wp.description;
                    final String firstSentence = !wp.extract.isEmpty()
                            ? trimToFirstSentence(wp.title, wp.extract, 190)
                            : (!wp.description.isEmpty() ? (wp.title + " — " + wp.description) : wp.title);

                    // Если человек спросил «что такое инфляция», не подставляем голое слово «Инфляция», а даём чистое определение!
                    final boolean userAlreadyTypedTitle = !qa.subjectNormalized.isEmpty()
                            && cleanParentheticalTitle(wp.title).equalsIgnoreCase(qa.subjectNormalized);
                    final String shortIns = userAlreadyTypedTitle
                            ? firstSentence
                            : cleanParentheticalTitle(wp.title);

                    primary = new InstantAnswer(
                            wp.title,
                            subtitle,
                            summary,
                            shortIns,
                            "",
                            firstSentence,
                            wikiLabel,
                            wp.url,
                            true
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

    /**
     * Строгая защита от случайных нерелевантных статей (чтобы на вопрос «сколько варить яйца»
     * не предлагалась статья «Яйцо (пищевой продукт)» в качестве готового ответа для вставки).
     */
    private static boolean isWikiPageDirectAnswerMatch(WikiPage wp, QuestionAnalysis qa) {
        if (wp == null || TextUtils.isEmpty(wp.title)) {
            return false;
        }
        final String cleanTitle = cleanParentheticalTitle(wp.title).toLowerCase(Locale.ROOT).replace('ё', 'е');
        final String extractLow = (wp.extract + " " + wp.description).toLowerCase(Locale.ROOT).replace('ё', 'е');

        if (qa.intent == INTENT_DEFINITION) {
            final String target = qa.subjectNormalized.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (target.isEmpty()) return false;
            return cleanTitle.equals(target) || cleanTitle.startsWith(target) || target.startsWith(cleanTitle);
        }

        // Если это структурированный вопрос (например, «валюта Казахстана»), статья в Википедии подходит только если её текст прямо упоминает субъект
        if (qa.hasStructuredProperty()) {
            final String rawLow = qa.rawQuery.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (qa.intent == INTENT_CURRENCY && !rawLow.contains("цифров") && !rawLow.contains("крипт")
                    && (cleanTitle.contains("цифров") || extractLow.startsWith("цифров"))) {
                return false;
            }
            final String subjStem = stemRussianWord(qa.subjectNormalized.toLowerCase(Locale.ROOT).replace('ё', 'е'));
            if (subjStem.length() >= 3 && extractLow.contains(subjStem) && !cleanTitle.equals(qa.subjectNormalized.toLowerCase(Locale.ROOT).replace('ё', 'е'))) {
                return true;
            }
            return false;
        }

        // Для произвольных открытых вопросов («почему...», «сколько варить...») заголовок статьи не является прямым ответом
        return false;
    }

    private static String stemRussianWord(String w) {
        if (w == null || w.length() <= 4) {
            return w != null ? w : "";
        }
        return w.substring(0, w.length() - 2);
    }

    /**
     * Удаляет знаки ударения Википедии (\u0301) и громоздкие этимологические скобки «(каз. ..., англ. ...)»
     */
    public static String cleanWikiText(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        // Снимаем комбинирующее ударение U+0301 («Казахста́нский тенге́» -> «Казахстанский тенге»)
        final StringBuilder noAccent = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c != '\u0301') {
                noAccent.append(c);
            }
        }
        final String strippedParens = WIKI_ETYMOLOGY_PARENS.matcher(noAccent.toString()).replaceAll("");
        return cleanWhitespace(strippedParens);
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
    // Источник 3: Мульти-поиск по вебу (DuckDuckGo POST -> Brave Search -> DDG Lite)
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
                        "",
                        answer,
                        "DuckDuckGo",
                        root.optString("AbstractURL", ""),
                        true
                );
            }

            final String heading = cleanWhitespace(root.optString("Heading", ""));
            final String abstractText = cleanWikiText(decodeHtmlEntities(root.optString("AbstractText", "")));
            final String definition = cleanWikiText(decodeHtmlEntities(root.optString("Definition", "")));
            final String abstractUrl = root.optString("AbstractURL", "");
            final String source = cleanWhitespace(root.optString("AbstractSource", "DuckDuckGo"));

            final String text = !abstractText.isEmpty() ? abstractText : definition;
            if (!text.isEmpty()) {
                final String title = !heading.isEmpty() ? heading : query;
                final String firstSent = trimToFirstSentence(title, text, 190);
                out.abstractAnswer = new InstantAnswer(
                        title,
                        source,
                        text,
                        firstSent,
                        "",
                        firstSent,
                        source,
                        abstractUrl,
                        true
                );
            }

            final JSONArray related = root.optJSONArray("RelatedTopics");
            if (related != null) {
                for (int i = 0; i < related.length() && out.relatedHits.size() < 4; i++) {
                    final JSONObject item = related.optJSONObject(i);
                    if (item == null) continue;
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

    private static List<WebHit> fetchCombinedWebResults(String rawQuery, String focusedQuery, boolean preferRu, SearchHandle handle) {
        final String effectiveQuery = !TextUtils.isEmpty(focusedQuery) && focusedQuery.length() >= 4 ? rawQuery : rawQuery;
        List<WebHit> hits = fetchDuckDuckGoWebPost(effectiveQuery, preferRu);
        if ((hits == null || hits.isEmpty()) && !handle.isCancelled()) {
            hits = fetchBraveWeb(effectiveQuery, preferRu);
        }
        if ((hits == null || hits.isEmpty()) && !handle.isCancelled()) {
            hits = fetchDuckDuckGoLitePost(effectiveQuery, preferRu);
        }
        return hits != null ? hits : Collections.emptyList();
    }

    private static List<WebHit> fetchDuckDuckGoWebPost(String query, boolean preferRu) {
        final String kl = preferRu ? "ru-ru" : "wt-wt";
        final String form = "q=" + encodeParam(query) + "&kl=" + encodeParam(kl);
        final String html = httpPostForm(
                "https://html.duckduckgo.com/html/",
                form,
                "https://html.duckduckgo.com/",
                DESKTOP_BROWSER_UA
        );
        if (TextUtils.isEmpty(html)) {
            return Collections.emptyList();
        }
        return parseDuckDuckGoHtml(html);
    }

    private static List<WebHit> fetchDuckDuckGoLitePost(String query, boolean preferRu) {
        final String kl = preferRu ? "ru-ru" : "wt-wt";
        final String form = "q=" + encodeParam(query) + "&kl=" + encodeParam(kl);
        final String html = httpPostForm(
                "https://lite.duckduckgo.com/lite/",
                form,
                "https://lite.duckduckgo.com/",
                DESKTOP_BROWSER_UA
        );
        if (TextUtils.isEmpty(html)) {
            return Collections.emptyList();
        }
        return parseDuckDuckGoLiteHtml(html);
    }

    private static List<WebHit> fetchBraveWeb(String query, boolean preferRu) {
        final String endpoint = "https://search.brave.com/search?q=" + encodeParam(query) + "&source=web";
        final String html = httpGetWithLang(
                endpoint,
                DESKTOP_BROWSER_UA,
                "text/html,application/xhtml+xml",
                preferRu ? "ru-RU,ru;q=0.9,en-US;q=0.8" : "en-US,en;q=0.9"
        );
        if (TextUtils.isEmpty(html)) {
            return Collections.emptyList();
        }
        return parseBraveSearchHtml(html);
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
            if (realUrl.contains("duckduckgo.com/y.js") || realUrl.contains("ad_domain=")) {
                continue;
            }
            final String cleanTitle = cleanWhitespace(decodeHtmlEntities(stripHtmlTags(rawTitle)));
            if (cleanTitle.isEmpty()) {
                continue;
            }
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

    public static List<WebHit> parseDuckDuckGoLiteHtml(String html) {
        if (TextUtils.isEmpty(html)) {
            return Collections.emptyList();
        }
        final ArrayList<WebHit> results = new ArrayList<>();
        final Matcher m = DDG_LITE_LINK_PATTERN.matcher(html);
        while (m.find() && results.size() < 8) {
            final String href = m.group(1) != null ? m.group(1) : m.group(3);
            final String titleRaw = m.group(2) != null ? m.group(2) : m.group(4);
            final String realUrl = unwrapDuckDuckGoRedirect(decodeHtmlEntities(href));
            if (TextUtils.isEmpty(realUrl) || !realUrl.startsWith("http")) continue;
            if (realUrl.contains("duckduckgo.com/y.js") || realUrl.contains("ad_domain=")) continue;
            final String cleanTitle = cleanWhitespace(decodeHtmlEntities(stripHtmlTags(titleRaw)));
            if (cleanTitle.isEmpty()) continue;
            final int from = m.end();
            final int to = Math.min(html.length(), from + 2000);
            String cleanSnippet = "";
            final Matcher sm = DDG_LITE_SNIPPET_PATTERN.matcher(html.substring(from, to));
            if (sm.find()) {
                cleanSnippet = cleanWhitespace(decodeHtmlEntities(stripHtmlTags(sm.group(1))));
            }
            results.add(new WebHit(cleanTitle, cleanSnippet, realUrl, extractDomain(realUrl)));
        }
        return results;
    }

    public static List<WebHit> parseBraveSearchHtml(String html) {
        if (TextUtils.isEmpty(html)) {
            return Collections.emptyList();
        }
        final ArrayList<WebHit> results = new ArrayList<>();
        final Matcher blockM = BRAVE_BLOCK_PATTERN.matcher(html);
        while (blockM.find() && results.size() < 8) {
            final String block = blockM.group(1);
            final Matcher linkM = BRAVE_LINK_PATTERN.matcher(block);
            if (!linkM.find()) continue;
            final String url = decodeHtmlEntities(linkM.group(1));
            if (url.contains("search.brave.com")) continue;

            final Matcher titleM = BRAVE_TITLE_PATTERN.matcher(block);
            final String title = titleM.find()
                    ? cleanWhitespace(decodeHtmlEntities(stripHtmlTags(titleM.group(1))))
                    : "";
            if (title.isEmpty()) continue;

            final Matcher descM = BRAVE_DESC_PATTERN.matcher(block);
            final String snippet = descM.find()
                    ? cleanWhitespace(decodeHtmlEntities(stripHtmlTags(descM.group(1))))
                    : "";
            results.add(new WebHit(title, snippet, url, extractDomain(url)));
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

    /**
     * Извлекает чистое информативное предложение из сниппета веб-выдачи (для карточки превью и кнопки «Вставить суть»).
     * Никогда не вставляет кликбейтные заголовки страниц или голые ссылки под видом ответа!
     */
    public static String extractCleanSentenceFromSnippet(String snippet) {
        if (TextUtils.isEmpty(snippet)) {
            return "";
        }
        String s = cleanWikiText(snippet);
        // Срезаем ведущую дату поискового сниппета («14 мар. 2025 г. — ...» или «Mar 14, 2025 — ...»)
        final int dashIdx = s.indexOf(" — ");
        if (dashIdx > 0 && dashIdx <= 24) {
            boolean hasDigits = false;
            for (int i = 0; i < dashIdx; i++) {
                if (Character.isDigit(s.charAt(i))) {
                    hasDigits = true;
                    break;
                }
            }
            if (hasDigits) {
                s = s.substring(dashIdx + 3).trim();
            }
        }
        return trimToFirstSentence("", s, 180);
    }

    private static InstantAnswer buildBestSnippetPreview(String query, List<WebHit> hits) {
        final String[] qWords = query.toLowerCase(Locale.ROOT).replace('ё', 'е').split("\\s+");
        WebHit bestHit = null;
        String bestSentence = "";
        int bestScore = -1;

        final int limit = Math.min(hits.size(), 5);
        for (int i = 0; i < limit; i++) {
            final WebHit h = hits.get(i);
            if (h == null || TextUtils.isEmpty(h.snippet) || h.snippet.length() < 25) {
                continue;
            }
            final String sent = extractCleanSentenceFromSnippet(h.snippet);
            if (sent.length() < 16 || sent.endsWith("?")) {
                continue;
            }
            final String sentLow = sent.toLowerCase(Locale.ROOT).replace('ё', 'е');
            int score = (limit - i) * 3;
            for (String qw : qWords) {
                final String cl = trimPunctuation(qw);
                if (cl.length() >= 4 && !FILLER_STOP_WORDS.contains(cl)) {
                    final String stem = stemRussianWord(cl);
                    if (sentLow.contains(stem)) {
                        score += 5;
                    }
                }
            }
            for (int c = 0; c < sent.length(); c++) {
                if (Character.isDigit(sent.charAt(c))) {
                    score += 4;
                    break;
                }
            }
            if (sent.contains(" — ") || sent.contains(" - ")) {
                score += 3;
            }
            if (!sent.endsWith("…") && !sent.endsWith("...")) {
                score += 4;
            }
            if (score > bestScore) {
                bestScore = score;
                bestHit = h;
                bestSentence = sent;
            }
        }

        if (bestHit != null && !bestSentence.isEmpty()) {
            // confidentShort = false на верхней плашке, если сниппет обрывается троеточием; если предложение законченное — даём чистую подстановку
            final boolean completeSentence = !bestSentence.endsWith("…") && !bestSentence.endsWith("...") && bestScore >= 12;
            return new InstantAnswer(
                    bestSentence,
                    bestHit.domain,
                    bestHit.snippet,
                    completeSentence ? bestSentence : "",
                    "",
                    bestSentence,
                    bestHit.domain,
                    bestHit.url,
                    completeSentence
            );
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Источник 4: Google Autocomplete (подсказки для уточнения запроса)
    // -------------------------------------------------------------------------

    private static List<String> fetchGoogleSuggestions(String query, String hl) {
        final String endpoint = "https://suggestqueries.google.com/complete/search?client=firefox&hl="
                + encodeParam(hl) + "&q=" + encodeParam(query);
        final String body = httpGet(endpoint, DESKTOP_BROWSER_UA, "application/json");
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
    // HTTP (строгая проверка HTTP 200 OK!) и текстовые утилиты
    // -------------------------------------------------------------------------

    private static String httpGet(String urlStr, String userAgent, String accept) {
        return httpGetWithLang(
                urlStr,
                userAgent,
                accept,
                isRussianUiLocale() ? "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7" : "en-US,en;q=0.9,ru;q=0.7"
        );
    }

    private static String httpGetWithLang(String urlStr, String userAgent, String accept, String acceptLang) {
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
            conn.setRequestProperty("Accept-Language", acceptLang);

            final int code = conn.getResponseCode();
            // Строго только 200 OK (отсекаем HTTP 202 anti-bot заглушки!)
            if (code != 200) {
                return null;
            }
            return readBoundedBody(conn);
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

    private static String httpPostForm(String urlStr, String urlEncodedBody, String referer, String userAgent) {
        HttpURLConnection conn = null;
        try {
            final byte[] payload = urlEncodedBody.getBytes(StandardCharsets.UTF_8);
            final URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
            conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("Accept-Language", isRussianUiLocale()
                    ? "ru-RU,ru;q=0.9,en-US;q=0.8"
                    : "en-US,en;q=0.9");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            final int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            return readBoundedBody(conn);
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

    private static String readBoundedBody(HttpURLConnection conn) throws Exception {
        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder(4096);
            final char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) != -1 && sb.length() < 262_144) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String trimPunctuation(String token) {
        if (token == null || token.isEmpty()) return "";
        int s = 0;
        int e = token.length();
        while (s < e && !Character.isLetterOrDigit(token.charAt(s)) && token.charAt(s) != '+' && token.charAt(s) != '-') {
            s++;
        }
        while (e > s && !Character.isLetterOrDigit(token.charAt(e - 1))) {
            e--;
        }
        return token.substring(s, e);
    }

    private static String joinStrings(List<String> list, String sep) {
        if (list == null || list.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String capitalizeFirst(String s) {
        if (TextUtils.isEmpty(s)) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatCompactNumber(double val, boolean ru) {
        final DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        sym.setDecimalSeparator(ru ? ',' : '.');
        final DecimalFormat df = new DecimalFormat(val >= 100 ? "0.#" : "0.##", sym);
        return df.format(val);
    }

    private static String formatGroupedInteger(long val, boolean ru) {
        final DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        sym.setGroupingSeparator(ru ? ' ' : ',');
        final DecimalFormat df = new DecimalFormat("#,###", sym);
        return df.format(val);
    }

    private static String formatGroupedNumber(double val, boolean ru) {
        if (Math.abs(val - Math.rint(val)) < 1e-6) {
            return formatGroupedInteger(Math.round(val), ru);
        }
        final DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        sym.setGroupingSeparator(ru ? ' ' : ',');
        sym.setDecimalSeparator(ru ? ',' : '.');
        final DecimalFormat df = new DecimalFormat("#,##0.##", sym);
        return df.format(val);
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
        for (int i = 16; i < extract.length() - 1; i++) {
            final char c = extract.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && extract.charAt(i + 1) == ' ') {
                // Не рвём на сокращениях вроде «т. е.», «г. Москва», «н. э.», «млн.»
                final char prev = extract.charAt(i - 1);
                final boolean singleLetterAbbr = Character.isLetter(prev) && (i < 2 || !Character.isLetter(extract.charAt(i - 2)));
                if (!singleLetterAbbr) {
                    dot = i + 1;
                    break;
                }
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
