package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Помощник ссылок: превращает ссылку TikTok / YouTube (включая YouTube Shorts и
 * фото-карусели TikTok) в прямые URL видео, фотографий, превью и фоновой музыки.
 *
 * YouTube — InnerTube player API (клиенты VISIONOS / ANDROID / IOS / WEB_EMBEDDED:
 * прямые URL без JS-шифрования, с передачей X-YouTube-Client-Name / VisitorData,
 * сохранением клиентского User-Agent и скачиванием Range-чанками для обхода троттлинга GVS).
 *
 * TikTok — разворот коротких ссылок (vm/vt/t), канонический запрос @i/video/<id> с
 * десктопным User-Agent (отдаёт и видео, и фото-посты imagePost), встроенное решение
 * SHA-256 WAF-челленджа (id="cs"), плюс резервные слои SIGI_STATE, embed/v2 и og:video.
 */
public final class PengramLinkResolver {

    public static final int MAX_DURATION_SEC = 600; // 10 минут
    private static final long RANGE_CHUNK_BYTES = 9_961_472L; // ~9.5 МБ чанки для googlevideo

    public static final int KIND_VIDEO = 0;
    public static final int KIND_PHOTOS = 1;
    public static final int KIND_AUDIO = 2;

    public static final class PhotoItem {
        public final String imageUrl;
        public final String thumbUrl;
        public final int width;
        public final int height;

        public PhotoItem(String imageUrl, String thumbUrl, int width, int height) {
            this.imageUrl = imageUrl;
            this.thumbUrl = thumbUrl != null && !thumbUrl.isEmpty() ? thumbUrl : imageUrl;
            this.width = width;
            this.height = height;
        }
    }

    public static final class Resolved {
        public final int kind;
        public final int platform;
        /** Для видео: [muxedUrl] либо [videoUrl, audioUrl]. Для фото: список URL всех кадров. */
        public final List<String> urls;
        public final List<PhotoItem> photos;
        /** Фоновая аудиодорожка для фото-карусели TikTok (может быть пустой). */
        public final String audioUrl;
        /** Обложка ролика или первый кадр для шторки предпросмотра. */
        public final String thumbnailUrl;
        public final boolean needsMux;
        public final String title;
        public final String author;
        public final long durationMs;
        public final Map<String, String> cookies;
        public final String referer;
        public final String userAgent;
        public final boolean rangeChunked;
        public final boolean hlsStream;
        public final List<Resolved> fallbacks = new ArrayList<>();

        Resolved(int kind, int platform, List<String> urls, List<PhotoItem> photos,
                 String audioUrl, String thumbnailUrl, boolean needsMux,
                 String title, String author, long durationMs,
                 Map<String, String> cookies, String referer,
                 String userAgent, boolean rangeChunked) {
            this(kind, platform, urls, photos, audioUrl, thumbnailUrl, needsMux,
                    title, author, durationMs, cookies, referer, userAgent, rangeChunked, false);
        }

        Resolved(int kind, int platform, List<String> urls, List<PhotoItem> photos,
                 String audioUrl, String thumbnailUrl, boolean needsMux,
                 String title, String author, long durationMs,
                 Map<String, String> cookies, String referer,
                 String userAgent, boolean rangeChunked, boolean hlsStream) {
            this.kind = kind;
            this.platform = platform;
            this.urls = urls != null ? urls : Collections.<String>emptyList();
            this.photos = photos != null ? photos : Collections.<PhotoItem>emptyList();
            this.audioUrl = audioUrl != null ? audioUrl : "";
            this.thumbnailUrl = thumbnailUrl != null ? thumbnailUrl : "";
            this.needsMux = needsMux;
            this.title = title != null ? title : "";
            this.author = author != null ? author : "";
            this.durationMs = durationMs;
            this.cookies = cookies != null ? cookies : new HashMap<String, String>();
            this.referer = referer != null ? referer : "";
            this.userAgent = userAgent != null && !userAgent.isEmpty() ? userAgent : UA_CHROME;
            this.rangeChunked = rangeChunked;
            this.hlsStream = hlsStream;
        }

        Resolved(List<String> urls, boolean needsMux, String title, String author, long durationMs,
                 Map<String, String> cookies, String referer) {
            this(KIND_VIDEO, PLATFORM_UNKNOWN, urls, Collections.<PhotoItem>emptyList(),
                    "", "", needsMux, title, author, durationMs, cookies, referer, UA_CHROME, false, false);
        }

        public boolean isPhotos() {
            return kind == KIND_PHOTOS && !photos.isEmpty();
        }

        public boolean isAudio() {
            return kind == KIND_AUDIO && !urls.isEmpty();
        }

        static Resolved forVideo(int platform, List<String> urls, boolean needsMux,
                                 String title, String author, long durationMs, String thumbnailUrl,
                                 Map<String, String> cookies, String referer,
                                 String userAgent, boolean rangeChunked) {
            return new Resolved(KIND_VIDEO, platform, urls, Collections.<PhotoItem>emptyList(),
                    "", thumbnailUrl, needsMux, title, author, durationMs,
                    cookies, referer, userAgent, rangeChunked, false);
        }

        static Resolved forVideoWithAudio(int platform, List<String> urls, String standaloneAudioUrl,
                                          boolean needsMux, boolean hlsStream,
                                          String title, String author, long durationMs, String thumbnailUrl,
                                          Map<String, String> cookies, String referer,
                                          String userAgent, boolean rangeChunked) {
            return new Resolved(KIND_VIDEO, platform, urls, Collections.<PhotoItem>emptyList(),
                    standaloneAudioUrl, thumbnailUrl, needsMux, title, author, durationMs,
                    cookies, referer, userAgent, rangeChunked, hlsStream);
        }

        static Resolved forAudio(int platform, String streamUrl, boolean hlsStream,
                                 String title, String author, long durationMs, String coverUrl,
                                 Map<String, String> cookies, String referer, String userAgent) {
            return new Resolved(KIND_AUDIO, platform, Collections.singletonList(streamUrl),
                    Collections.<PhotoItem>emptyList(), streamUrl, coverUrl, false,
                    title, author, durationMs, cookies, referer, userAgent, false, hlsStream);
        }

        static Resolved forPhotos(int platform, List<PhotoItem> photos, String audioUrl,
                                  String title, String author,
                                  Map<String, String> cookies, String referer, String userAgent) {
            final List<String> imageUrls = new ArrayList<>(photos.size());
            for (PhotoItem p : photos) {
                imageUrls.add(p.imageUrl);
            }
            final String thumb = photos.isEmpty() ? "" : photos.get(0).thumbUrl;
            return new Resolved(KIND_PHOTOS, platform, imageUrls, photos,
                    audioUrl, thumb, false, title, author, 0L,
                    cookies, referer, userAgent, false);
        }
    }

    /** Результат поиска ссылки в тексте: url + границы совпадения. */
    public static final class FoundLink {
        public final String url;
        public final int start;
        public final int end;

        FoundLink(String url, int start, int end) {
            this.url = url;
            this.start = start;
            this.end = end;
        }
    }

    public static final int PLATFORM_UNKNOWN = 0;
    public static final int PLATFORM_YOUTUBE = 1;
    public static final int PLATFORM_TIKTOK = 2;
    public static final int PLATFORM_INSTAGRAM = 3;
    public static final int PLATFORM_TWITTER = 4;
    public static final int PLATFORM_PINTEREST = 5;
    public static final int PLATFORM_VK = 6;
    public static final int PLATFORM_RUTUBE = 7;
    public static final int PLATFORM_REDDIT = 8;
    public static final int PLATFORM_SOUNDCLOUD = 9;
    public static final int PLATFORM_YANDEX = 10;
    public static final int ALL_PLATFORMS_MASK = (1 << 11) - 2;

    // ВАЖНО: Pattern.UNICODE_CHARACTER_CLASS на Android отвергается рантаймом
    // (IllegalArgumentException в <clinit> → ExceptionInInitializerError при
    // первом вводе текста). Его семантику «\s = любые Unicode-пробелы» держим
    // явным набором ниже: \s + NEL(0085) NBSP(00A0) OGHAM(1680) EN/EM quads
    // (2000-200A) LS/PS(2028/2029) NNBSP(202F) MMTSP(205F) IDSP(3000).
    private static final String URL_STOP = "\\s\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000<>()\\[\\]{}\"'";
    private static final Pattern YOUTUBE = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:shorts/|watch\\?(?:[^" + URL_STOP + "]*&)?v=|live/|embed/|v/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{6,20})[^" + URL_STOP + "]*");
    private static final Pattern TIKTOK = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.|vm\\.|vt\\.|m\\.)?tiktokv?\\.com/"
                    + "(?:@[^/" + URL_STOP + "]*/(?:video|photo)/\\d+|(?:share/(?:video|photo)|v)/\\d+|t/[A-Za-z0-9_-]+|(?!explore\\b|foryou\\b|following\\b|search\\b|upload\\b|login\\b|live\\b)[A-Za-z0-9_-]{6,24})[^" + URL_STOP + "]*");
    private static final Pattern INSTAGRAM = Pattern.compile(
            "(?i)(?:https?://)?(?:(?:www\\.|m\\.|dd|kk)?instagram\\.com|instagr\\.am)/"
                    + "(?:(?:[A-Za-z0-9._]{1,30}/)?(?:reel|reels|p|tv)/[A-Za-z0-9_-]{5,36}|share/(?:(?:reel|reels|p)/)?[A-Za-z0-9_-]{5,36})[^" + URL_STOP + "]*");
    private static final Pattern TWITTER = Pattern.compile(
            "(?i)(?:https?://)?(?:(?:www\\.|mobile\\.|fx|vx|fixup|fixvx)?(?:twitter|x)\\.com/(?:[A-Za-z0-9_]{1,20}/status|i/web/status|i/status)/\\d{5,25}|t\\.co/[A-Za-z0-9]{6,16})[^" + URL_STOP + "]*");
    private static final Pattern PINTEREST = Pattern.compile(
            "(?i)(?:https?://)?(?:(?:[a-z]{2}\\.|www\\.)?pinterest\\.(?:com|ru|de|co\\.uk|fr|es|it|ca|jp|com\\.au|com\\.mx|co\\.kr|br)/pin/(?:[^/" + URL_STOP + "]*--)?\\d{6,25}|pin\\.it/[A-Za-z0-9_-]{5,20})[^" + URL_STOP + "]*");
    private static final Pattern VK = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.|m\\.)?(?:vk\\.com|vk\\.ru|vkvideo\\.ru)/(?:(?:clip|video)-?\\d+_\\d+|clips/?\\?[^" + URL_STOP + "]*z=(?:clip|video)-?\\d+_\\d+)[^" + URL_STOP + "]*");
    private static final Pattern RUTUBE = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.|m\\.)?rutube\\.ru/(?:shorts/[a-f0-9]{16,40}|video/(?:private/)?[a-f0-9]{16,40}|play/embed/[a-f0-9]{16,40}|yappy/[a-f0-9]{16,40})[^" + URL_STOP + "]*");
    private static final Pattern REDDIT = Pattern.compile(
            "(?i)(?:https?://)?(?:(?:www\\.|old\\.|new\\.|np\\.|m\\.)?reddit\\.com/(?:r|u|user)/[^/" + URL_STOP + "]+/(?:comments/[A-Za-z0-9]{5,12}|s/[A-Za-z0-9]{6,16})|(?:v\\.|i\\.)?redd\\.it/[A-Za-z0-9]{5,18})[^" + URL_STOP + "]*");
    private static final Pattern SOUNDCLOUD = Pattern.compile(
            "(?i)(?:https?://)?(?:(?:www\\.|m\\.)?soundcloud\\.com/(?!discover(?:/|$)|stream(?:/|$)|search(?:/|$)|you(?:/|$)|upload(?:/|$)|settings(?:/|$)|pages(?:/|$))[A-Za-z0-9_-]{2,60}/(?!sets(?:/|$)|likes(?:/|$)|followers(?:/|$)|following(?:/|$)|tracks(?:/|$)|albums(?:/|$)|reposts(?:/|$)|popular-tracks(?:/|$))[A-Za-z0-9_-]{2,120}|on\\.soundcloud\\.com/[A-Za-z0-9]{4,22}|soundcloud\\.app\\.goo\\.gl/[A-Za-z0-9]{4,22})[^" + URL_STOP + "]*");
    private static final Pattern YANDEX = Pattern.compile(
            "(?i)(?:https?://)?music\\.yandex\\.(?:ru|by|kz|uz|com)/(?:album/\\d+/track/\\d+|users/[^/" + URL_STOP + "]+/playlists/\\d+/track/\\d+|track/\\d+)[^" + URL_STOP + "]*");

    private static final Pattern[] ALL_PATTERNS = {
            YOUTUBE, TIKTOK, INSTAGRAM, TWITTER, PINTEREST, VK, RUTUBE, REDDIT, SOUNDCLOUD, YANDEX
    };
    private static final int[] PATTERN_PLATFORMS = {
            PLATFORM_YOUTUBE, PLATFORM_TIKTOK, PLATFORM_INSTAGRAM, PLATFORM_TWITTER, PLATFORM_PINTEREST,
            PLATFORM_VK, PLATFORM_RUTUBE, PLATFORM_REDDIT, PLATFORM_SOUNDCLOUD, PLATFORM_YANDEX
    };

    private static volatile String cachedYtVisitorData;
    private static volatile long cachedYtVisitorTimeMs;
    private static volatile String cachedVkAnonToken;
    private static volatile String cachedVkDeviceId;
    private static volatile long cachedVkTokenTimeMs;
    private static volatile String cachedSoundCloudClientId;
    private static volatile long cachedSoundCloudClientTimeMs;

    private PengramLinkResolver() {
    }

    /** Нулевой по аллокациям префильтр перед запуском тяжёлых URL-регулярок на каждом нажатии клавиши. */
    private static boolean mayContainSupportedLink(CharSequence text) {
        final int n = text.length();
        if (n < 8) {
            return false;
        }
        boolean hasSlash = false;
        boolean hasDot = false;
        for (int i = 0; i < n; i++) {
            final char c = text.charAt(i);
            if (c == '/') {
                hasSlash = true;
                if (hasDot) return true;
            } else if (c == '.') {
                hasDot = true;
                if (hasSlash) return true;
            }
        }
        return false;
    }

    /** Последняя (свежайшая) поддерживаемая ссылка в тексте; null — нет. */
    public static FoundLink findLink(CharSequence text) {
        return findLink(text, ALL_PLATFORMS_MASK);
    }

    /** Поиск поддерживаемой ссылки с фильтром включённых площадок (битовая маска 1 << PLATFORM_*). */
    public static FoundLink findLink(CharSequence text, int enabledPlatformsMask) {
        if (text == null || enabledPlatformsMask == 0 || !mayContainSupportedLink(text)) {
            return null;
        }
        final String s = text.toString();
        FoundLink best = null;
        for (int idx = 0; idx < ALL_PATTERNS.length; idx++) {
            final int plat = PATTERN_PLATFORMS[idx];
            if ((enabledPlatformsMask & (1 << plat)) == 0) {
                continue;
            }
            Matcher m = ALL_PATTERNS[idx].matcher(s);
            while (m.find()) {
                String url = trimUrl(m.group());
                if (url.isEmpty()) {
                    continue;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                // Последнее вхождение в тексте побеждает; raw-границы сохраняем для редактирования.
                if (best == null || m.start() >= best.start) {
                    int trimmedLen = trimUrl(m.group()).length();
                    best = new FoundLink(url, m.start(), m.start() + trimmedLen);
                }
            }
        }
        return best;
    }

    /** Убирает хвостовые пунктуации и скобки, не относящиеся к URL. */
    private static String trimUrl(String raw) {
        int end = raw.length();
        while (end > 0) {
            char c = raw.charAt(end - 1);
            if (c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == ')' || c == '»'
                    || c == '\'' || c == '"' || c == ']') {
                end--;
            } else {
                break;
            }
        }
        return raw.substring(0, end);
    }

    public static int platformOf(String url) {
        if (url == null) {
            return PLATFORM_UNKNOWN;
        }
        String lower = url.toLowerCase();
        if (lower.contains("youtube.com/") || lower.contains("youtu.be/")) {
            return PLATFORM_YOUTUBE;
        }
        if (lower.contains("tiktok.com/") || lower.contains("tiktokv.com/")) {
            return PLATFORM_TIKTOK;
        }
        if (lower.contains("instagram.com/")) {
            return PLATFORM_INSTAGRAM;
        }
        if (lower.contains("twitter.com/") || lower.contains("x.com/") || lower.contains("//t.co/") || lower.startsWith("t.co/")) {
            return PLATFORM_TWITTER;
        }
        if (lower.contains("pinterest.") || lower.contains("pin.it/")) {
            return PLATFORM_PINTEREST;
        }
        if (lower.contains("vk.com/") || lower.contains("vk.ru/") || lower.contains("vkvideo.ru/")) {
            return PLATFORM_VK;
        }
        if (lower.contains("rutube.ru/")) {
            return PLATFORM_RUTUBE;
        }
        if (lower.contains("reddit.com/") || lower.contains("redd.it/")) {
            return PLATFORM_REDDIT;
        }
        if (lower.contains("soundcloud.com/") || lower.contains("soundcloud.app.goo.gl/")) {
            return PLATFORM_SOUNDCLOUD;
        }
        if (lower.contains("music.yandex.")) {
            return PLATFORM_YANDEX;
        }
        return PLATFORM_UNKNOWN;
    }

    public static boolean isAudioPlatform(int platform) {
        return platform == PLATFORM_SOUNDCLOUD || platform == PLATFORM_YANDEX;
    }

    public static boolean isYouTubeShortsUrl(String url) {
        return url != null && url.toLowerCase().contains("/shorts/");
    }

    public static boolean isTikTokPhotoHintUrl(String url) {
        return url != null && url.toLowerCase().contains("/photo/");
    }

    public static boolean isTikTokVideoHintUrl(String url) {
        if (url == null) {
            return false;
        }
        final String lower = url.toLowerCase();
        return lower.contains("/video/") || lower.contains("/v/");
    }

    /** Полный конвейер: платформа → прямые URL видео, фото-карусели или аудио. */
    public static Resolved resolve(String url) throws Exception {
        return resolve(url, 1080);
    }

    public static Resolved resolve(String url, int maxVideoHeight) throws Exception {
        final int capHeight = maxVideoHeight > 0 ? maxVideoHeight : 1080;
        final String normalized = (url != null && !url.startsWith("http")) ? "https://" + url : url;
        final int platform = platformOf(normalized);
        switch (platform) {
            case PLATFORM_YOUTUBE:
                return resolveYouTube(normalized, capHeight);
            case PLATFORM_TIKTOK:
                return resolveTikTok(normalized);
            case PLATFORM_INSTAGRAM:
                return resolveInstagram(normalized, capHeight);
            case PLATFORM_TWITTER:
                return resolveTwitter(normalized, capHeight);
            case PLATFORM_PINTEREST:
                return resolvePinterest(normalized, capHeight);
            case PLATFORM_VK:
                return resolveVk(normalized, capHeight);
            case PLATFORM_RUTUBE:
                return resolveRutube(normalized, capHeight);
            case PLATFORM_REDDIT:
                return resolveReddit(normalized, capHeight);
            case PLATFORM_SOUNDCLOUD:
                return resolveSoundCloud(normalized);
            case PLATFORM_YANDEX:
                return resolveYandexMusic(normalized);
            default:
                throw new Exception("unsupported");
        }
    }

    // ---------------------------------------------------------------- YouTube

    /**
     * InnerTube player: клиенты без JS-шифрования (yt-dlp master окт-2026:
     * VISIONOS — дефолтный no-JS клиент без PO-token; ANDROID / IOS / WEB_EMBEDDED — запасные).
     * ANDROID_VR удалён (403 с 2026-08-17, yt-dlp #17348).
     */
    private static Resolved resolveYouTube(String url, int maxVideoHeight) throws Exception {
        final String videoId = extractYouTubeId(url);
        if (videoId == null) {
            throw new Exception("bad url");
        }
        final String visitorData = getYouTubeVisitorData(videoId);

        final String[][] clients = {
                // {clientName, clientVersion, userAgent, deviceMake, deviceModel, osName, osVersion, clientId}
                {"VISIONOS", "1.02",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                        "Apple", "RealityDevice17,1", "visionOS", "26.5.23O471", "101"},
                {"IOS", "21.26.4",
                        "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                        "Apple", "iPhone16,2", "iPhone", "18.3.2.22D82", "5"},
                {"ANDROID", "21.26.364",
                        "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
                        "", "", "Android", "11", "3"},
                {"WEB_EMBEDDED_PLAYER", "2.20260708.00.00",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)",
                        "", "", "", "", "56"},
        };

        Resolved primary = null;
        Exception last = null;
        for (String[] client : clients) {
            try {
                final Resolved r = playerRequest(videoId, client, visitorData, maxVideoHeight);
                if (primary == null) {
                    primary = r;
                } else {
                    primary.fallbacks.add(r);
                }
                // Достаточно основного + одного резервного клиента, чтобы не задерживать старт.
                if (primary.fallbacks.size() >= 1) {
                    break;
                }
            } catch (Exception e) {
                last = e;
                // Если ролик слишком длинный или это стрим — не крутим остальные клиенты зря.
                final String msg = e.getMessage() != null ? e.getMessage() : "";
                if ("too long".equals(msg) || "live".equals(msg)) {
                    throw e;
                }
            }
        }
        if (primary != null) {
            return primary;
        }
        throw last != null ? last : new Exception("youtube failed");
    }

    private static String getYouTubeVisitorData(String videoId) {
        final long now = System.currentTimeMillis();
        final String cached = cachedYtVisitorData;
        if (cached != null && !cached.isEmpty() && now - cachedYtVisitorTimeMs < 30L * 60_000L) {
            return cached;
        }
        try {
            final String embed = httpGet("https://www.youtube.com/embed/" + videoId + "?html5=1",
                    UA_DESKTOP, null, "https://www.youtube.com/");
            final Matcher m = Pattern.compile("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").matcher(embed);
            if (m.find()) {
                final String vd = m.group(1);
                if (!vd.isEmpty()) {
                    cachedYtVisitorData = vd;
                    cachedYtVisitorTimeMs = now;
                    return vd;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static Resolved playerRequest(String videoId, String[] client, String visitorData, int maxVideoHeight) throws Exception {
        final String clientName = client[0];
        final String clientVersion = client[1];
        final String userAgent = client[2];
        final String clientId = client.length > 7 ? client[7] : "1";

        final JSONObject contextClient = new JSONObject()
                .put("clientName", clientName)
                .put("clientVersion", clientVersion)
                .put("hl", "en")
                .put("gl", "US");
        if (clientName.startsWith("ANDROID")) {
            contextClient.put("androidSdkVersion", 30);
        }
        if (client.length > 6) {
            if (!client[3].isEmpty()) {
                contextClient.put("deviceMake", client[3]);
            }
            if (!client[4].isEmpty()) {
                contextClient.put("deviceModel", client[4]);
            }
            if (!client[5].isEmpty()) {
                contextClient.put("osName", client[5]);
            }
            if (!client[6].isEmpty()) {
                contextClient.put("osVersion", client[6]);
            }
        }
        if (visitorData != null && !visitorData.isEmpty()) {
            contextClient.put("visitorData", visitorData);
        }

        final JSONObject context = new JSONObject().put("client", contextClient);
        if ("WEB_EMBEDDED_PLAYER".equals(clientName)) {
            context.put("thirdParty", new JSONObject().put("embedUrl", "https://www.reddit.com/"));
        }

        final JSONObject payload = new JSONObject()
                .put("context", context)
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true);

        final Map<String, String> headers = new HashMap<>();
        headers.put("X-YouTube-Client-Name", clientId);
        headers.put("X-YouTube-Client-Version", clientVersion);
        headers.put("Origin", "https://www.youtube.com");
        if (visitorData != null && !visitorData.isEmpty()) {
            headers.put("X-Goog-Visitor-Id", visitorData);
        }

        final String body = httpPostWithHeaders(
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                payload.toString(), userAgent, null, headers);
        final JSONObject root = new JSONObject(body);

        final JSONObject status = root.optJSONObject("playabilityStatus");
        final String playability = status != null ? status.optString("status") : "";
        if (!"OK".equals(playability)) {
            throw new Exception("playability " + playability);
        }

        final JSONObject videoDetails = root.optJSONObject("videoDetails");
        final String respId = videoDetails != null ? videoDetails.optString("videoId", videoId) : videoId;
        if (!videoId.equals(respId)) {
            throw new Exception("videoId mismatch");
        }
        final String title = videoDetails != null ? videoDetails.optString("title") : "";
        final String author = videoDetails != null ? videoDetails.optString("author") : "";
        final long lengthSec = videoDetails != null ? videoDetails.optLong("lengthSeconds", 0) : 0;
        if (videoDetails != null && videoDetails.optBoolean("isLiveContent", false)) {
            throw new Exception("live");
        }
        if (lengthSec <= 0 || lengthSec > MAX_DURATION_SEC) {
            throw new Exception("too long");
        }
        final String thumbUrl = extractYouTubeThumb(videoDetails, videoId);

        final JSONObject streaming = root.optJSONObject("streamingData");
        if (streaming == null) {
            throw new Exception("no streamingData");
        }

        String bestMuxedUrl = null;
        int bestMuxedHeight = 0;

        String videoUrl = null;
        int bestVideoCodecRank = -1; // 2 = avc1 (H.264), 1 = hvc1/hev1, 0 = прочее mp4
        int bestVideoHeight = 0;

        String audioUrl = null;
        int bestAudioCodecRank = -1; // 2 = mp4a, 1 = mp4, 0 = прочее
        int bestAudioBitrate = 0;

        final JSONArray formats = streaming.optJSONArray("formats");
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                final JSONObject f = formats.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                final String direct = directUrl(f);
                if (direct == null) {
                    continue;
                }
                final int h = f.optInt("height", 0);
                final String mime = f.optString("mimeType", "");
                if (f.has("audioQuality") && f.optInt("width", 0) > 0 && mime.contains("mp4")) {
                    if (h <= maxVideoHeight && (bestMuxedUrl == null || h > bestMuxedHeight)) {
                        bestMuxedUrl = direct;
                        bestMuxedHeight = h;
                    } else if (bestMuxedUrl == null) {
                        bestMuxedUrl = direct;
                        bestMuxedHeight = h;
                    }
                }
            }
        }

        final JSONArray adaptive = streaming.optJSONArray("adaptiveFormats");
        if (adaptive != null) {
            for (int i = 0; i < adaptive.length(); i++) {
                final JSONObject f = adaptive.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                final String direct = directUrl(f);
                if (direct == null) {
                    continue;
                }
                final String mime = f.optString("mimeType", "");
                final boolean hasVideo = f.optInt("width", 0) > 0;
                final boolean hasAudio = f.has("audioQuality") || mime.startsWith("audio/");
                if (hasVideo && !hasAudio) {
                    final int h = f.optInt("height", 0);
                    if (h <= 0 || h > maxVideoHeight || !mime.contains("mp4")) {
                        continue;
                    }
                    // AV1 в mp4 (av01) на части устройств ломает MediaMuxer — предпочитаем H.264 (avc1).
                    final int codecRank = mime.contains("avc1") ? 2
                            : ((mime.contains("hvc1") || mime.contains("hev1")) ? 1
                            : (mime.contains("av01") ? -1 : 0));
                    if (codecRank < 0) {
                        continue;
                    }
                    if (videoUrl == null || codecRank > bestVideoCodecRank
                            || (codecRank == bestVideoCodecRank && h > bestVideoHeight)) {
                        videoUrl = direct;
                        bestVideoCodecRank = codecRank;
                        bestVideoHeight = h;
                    }
                } else if (!hasVideo && hasAudio) {
                    if (!mime.contains("mp4")) {
                        continue;
                    }
                    final int br = f.optInt("bitrate", 0);
                    final int codecRank = mime.contains("mp4a") ? 2 : 1;
                    if (audioUrl == null || codecRank > bestAudioCodecRank
                            || (codecRank == bestAudioCodecRank && br > bestAudioBitrate)) {
                        audioUrl = direct;
                        bestAudioCodecRank = codecRank;
                        bestAudioBitrate = br;
                    }
                }
            }
        }

        // Предпочитаем адаптивную пару HD (720p/1080p H.264+AAC), если прогрессивный поток ниже потолка.
        if (videoUrl != null && audioUrl != null && bestVideoHeight > bestMuxedHeight) {
            return Resolved.forVideoWithAudio(PLATFORM_YOUTUBE, Arrays.asList(videoUrl, audioUrl),
                    audioUrl, true, false, title, author, lengthSec * 1000L, thumbUrl,
                    null, "https://www.youtube.com/", userAgent, true);
        }
        if (bestMuxedUrl != null) {
            return Resolved.forVideoWithAudio(PLATFORM_YOUTUBE, Arrays.asList(bestMuxedUrl),
                    audioUrl != null ? audioUrl : "", false, false, title, author, lengthSec * 1000L, thumbUrl,
                    null, "https://www.youtube.com/", userAgent, true);
        }
        if (videoUrl != null && audioUrl != null) {
            return Resolved.forVideoWithAudio(PLATFORM_YOUTUBE, Arrays.asList(videoUrl, audioUrl),
                    audioUrl, true, false, title, author, lengthSec * 1000L, thumbUrl,
                    null, "https://www.youtube.com/", userAgent, true);
        }
        throw new Exception("no direct formats");
    }

    private static String extractYouTubeThumb(JSONObject videoDetails, String videoId) {
        if (videoDetails != null) {
            final JSONObject thumbObj = videoDetails.optJSONObject("thumbnail");
            final JSONArray thumbs = thumbObj != null ? thumbObj.optJSONArray("thumbnails") : null;
            if (thumbs != null && thumbs.length() > 0) {
                String best = null;
                int bestW = 0;
                for (int i = 0; i < thumbs.length(); i++) {
                    final JSONObject t = thumbs.optJSONObject(i);
                    if (t == null) {
                        continue;
                    }
                    final String u = t.optString("url", "");
                    final int w = t.optInt("width", 0);
                    if (u.startsWith("http") && (best == null || (w > bestW && w <= 1280))) {
                        best = u;
                        bestW = w;
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        }
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    /** Прямой URL есть только у нешифрованных клиентов; cipher — в следующий. */
    private static String directUrl(JSONObject format) {
        final String u = format.optString("url");
        if (u.startsWith("http")) {
            return u;
        }
        return null;
    }

    static String extractYouTubeId(String url) {
        final Matcher m = YOUTUBE.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    // ----------------------------------------------------------------- TikTok

    private static Resolved resolveTikTok(String url) throws Exception {
        final Map<String, String> cookies = new HashMap<>();
        String canonical = followRedirects(url, cookies, 7);
        if (canonical == null || canonical.isEmpty()) {
            canonical = url;
        }

        String postId = extractTikTokPostId(canonical);
        if (postId == null) {
            // Иногда vm/vt редиректит только под ботовым UA (как в TikTokVMIE yt-dlp).
            final String alt = followRedirectsWithUa(url, cookies, 5, "facebookexternalhit/1.1");
            if (alt != null && !alt.isEmpty()) {
                canonical = alt;
                postId = extractTikTokPostId(canonical);
            }
        }

        // Важнейший момент (см. cobalt): даже для /photo/<id> запрос на @i/video/<id>
        // с десктопным User-Agent отдаёт полный __UNIVERSAL_DATA_FOR_REHYDRATION__ с imagePost!
        final String fetchUrl = postId != null
                ? "https://www.tiktok.com/@i/video/" + postId
                : canonical;

        String page = null;
        try {
            page = httpGet(fetchUrl, UA_DESKTOP, cookies, "https://www.tiktok.com/");
        } catch (Exception firstErr) {
            if (!fetchUrl.equals(canonical)) {
                page = httpGet(canonical, UA_DESKTOP, cookies, "https://www.tiktok.com/");
            } else {
                throw firstErr;
            }
        }

        JSONObject item = extractTikTokItem(page);
        if (item == null && page != null) {
            // Если TikTok вернул JS WAF-челлендж (id="cs" + id="wci") — решаем SHA-256 и перечитываем.
            final List<String> tempCookies = solveTikTokWafChallenge(page, cookies);
            if (!tempCookies.isEmpty()) {
                try {
                    page = httpGet(fetchUrl, UA_DESKTOP, cookies, "https://www.tiktok.com/");
                } finally {
                    for (String k : tempCookies) {
                        cookies.remove(k);
                    }
                }
                item = extractTikTokItem(page);
            }
        }

        if (item != null) {
            final Resolved fromItem = parseTikTokItemStruct(item, cookies, fetchUrl);
            if (fromItem != null) {
                return fromItem;
            }
        }

        // Резервный слой Embed v2 (редко попадает под региональный челлендж, содержит и видео, и фото).
        if (postId != null) {
            final Resolved fromEmbed = resolveTikTokEmbed(postId, cookies);
            if (fromEmbed != null) {
                return fromEmbed;
            }
        }

        // Слои 2/3: og:video / сырой imagePost / сырой playAddr на самой странице.
        if (page != null) {
            final Resolved layered = resolveTikTokLayered(page, cookies, fetchUrl);
            if (layered != null) {
                return layered;
            }
        }

        throw new Exception("no item");
    }

    public static String extractTikTokPostId(String url) {
        if (url == null) {
            return null;
        }
        final Matcher m = Pattern.compile("(?:video|photo|v)/(\\d{10,25})").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        final Matcher q = Pattern.compile("[?&]item_id=(\\d{10,25})").matcher(url);
        if (q.find()) {
            return q.group(1);
        }
        return null;
    }

    static Resolved parseTikTokItemStruct(JSONObject item, Map<String, String> cookies, String referer)
            throws Exception {
        final JSONObject authorObj = item.optJSONObject("author");
        String authorId = authorObj != null ? authorObj.optString("uniqueId", "") : "";
        if (authorId.isEmpty() && authorObj != null) {
            authorId = authorObj.optString("unique_id", "");
        }
        final String desc = item.optString("desc", "");
        final String caption = buildTikTokCaption(authorId, desc);

        // 1) Сначала проверяем фото-карусель (imagePost / image_post_info)!
        // У фото-постов в item.video.playAddr лежит только фоновая музыка (m4a/mp3), а не видео.
        JSONObject imagePost = item.optJSONObject("imagePost");
        if (imagePost == null) {
            imagePost = item.optJSONObject("image_post_info");
        }
        if (imagePost != null) {
            final JSONArray images = imagePost.optJSONArray("images");
            final List<PhotoItem> photoItems = extractTikTokPhotos(images);
            if (!photoItems.isEmpty()) {
                String audioUrl = "";
                final JSONObject music = item.optJSONObject("music");
                if (music != null) {
                    audioUrl = cleanMediaUrl(music.optString("playUrl", ""));
                    if (audioUrl.isEmpty()) {
                        audioUrl = cleanMediaUrl(music.optJSONObject("play_url") != null
                                ? firstUrlFromList(music.optJSONObject("play_url").optJSONArray("url_list")) : "");
                    }
                }
                if (audioUrl.isEmpty()) {
                    final JSONObject video = item.optJSONObject("video");
                    if (video != null) {
                        audioUrl = cleanMediaUrl(video.optString("playAddr", ""));
                    }
                }
                return Resolved.forPhotos(PLATFORM_TIKTOK, photoItems, audioUrl, caption, authorId,
                        new HashMap<>(cookies), referer, UA_DESKTOP);
            }
        }

        // 2) Обычный видеопост TikTok.
        final JSONObject video = item.optJSONObject("video");
        if (video == null) {
            return null;
        }

        final long durationSec = video.optLong("duration", 0);
        if (durationSec > MAX_DURATION_SEC) {
            throw new Exception("too long");
        }

        final List<String> candidates = collectTikTokVideoUrls(video);
        if (candidates.isEmpty()) {
            return null;
        }

        String thumbUrl = cleanMediaUrl(video.optString("originCover", ""));
        if (thumbUrl.isEmpty()) {
            thumbUrl = cleanMediaUrl(video.optString("cover", ""));
        }
        if (thumbUrl.isEmpty()) {
            thumbUrl = cleanMediaUrl(video.optString("dynamicCover", ""));
        }

        final Resolved primary = Resolved.forVideo(
                PLATFORM_TIKTOK, Arrays.asList(candidates.get(0)), false,
                caption, authorId, durationSec > 0 ? durationSec * 1000L : 0L, thumbUrl,
                new HashMap<>(cookies), referer, UA_DESKTOP, false);
        for (int i = 1; i < candidates.size() && primary.fallbacks.size() < 2; i++) {
            primary.fallbacks.add(Resolved.forVideo(
                    PLATFORM_TIKTOK, Arrays.asList(candidates.get(i)), false,
                    caption, authorId, durationSec > 0 ? durationSec * 1000L : 0L, thumbUrl,
                    new HashMap<>(cookies), referer, UA_DESKTOP, false));
        }
        return primary;
    }

    private static List<PhotoItem> extractTikTokPhotos(JSONArray images) {
        final List<PhotoItem> list = new ArrayList<>();
        if (images == null) {
            return list;
        }
        for (int i = 0; i < images.length(); i++) {
            final JSONObject img = images.optJSONObject(i);
            if (img == null) {
                continue;
            }
            final int w = img.optInt("imageWidth", img.optInt("width", 0));
            final int h = img.optInt("imageHeight", img.optInt("height", 0));
            String bestUrl = pickBestImageUrl(img.optJSONObject("imageURL"));
            if (bestUrl.isEmpty()) {
                bestUrl = pickBestImageUrl(img.optJSONObject("displayImage"));
            }
            if (bestUrl.isEmpty()) {
                bestUrl = pickBestImageUrl(img.optJSONObject("display_image"));
            }
            if (bestUrl.isEmpty()) {
                bestUrl = pickBestImageUrl(img.optJSONObject("ownerWatermarkImage"));
            }
            if (!bestUrl.isEmpty()) {
                list.add(new PhotoItem(bestUrl, bestUrl, w, h));
            }
        }
        return list;
    }

    private static String pickBestImageUrl(JSONObject holder) {
        if (holder == null) {
            return "";
        }
        JSONArray arr = holder.optJSONArray("urlList");
        if (arr == null) {
            arr = holder.optJSONArray("url_list");
        }
        if (arr == null || arr.length() == 0) {
            return "";
        }
        String fallback = "";
        for (int i = 0; i < arr.length(); i++) {
            final String u = cleanMediaUrl(arr.optString(i, ""));
            if (u.isEmpty()) {
                continue;
            }
            final String lower = u.toLowerCase();
            // Предпочитаем JPEG/PNG вместо HEIC/WebP — открываются везде без перекодирования.
            if (lower.contains(".jpeg") || lower.contains(".jpg") || lower.contains(".png")) {
                return u;
            }
            if (fallback.isEmpty() && !lower.contains(".heic")) {
                fallback = u;
            }
        }
        if (!fallback.isEmpty()) {
            return fallback;
        }
        return cleanMediaUrl(arr.optString(0, ""));
    }

    private static List<String> collectTikTokVideoUrls(JSONObject video) {
        final List<String> h264Urls = new ArrayList<>();
        final List<String> otherUrls = new ArrayList<>();

        final JSONArray bitrateInfo = video.optJSONArray("bitrateInfo");
        if (bitrateInfo != null) {
            for (int i = 0; i < bitrateInfo.length(); i++) {
                final JSONObject br = bitrateInfo.optJSONObject(i);
                if (br == null) {
                    continue;
                }
                final String codec = br.optString("CodecType", "").toLowerCase();
                final JSONObject pa = br.optJSONObject("PlayAddr");
                final String urlKey = pa != null ? pa.optString("UrlKey", "").toLowerCase() : "";
                // bytevc2 — кастомный H.266 ByteDance, не воспроизводится на Android.
                if (codec.contains("bytevc2") || urlKey.contains("bytevc2")) {
                    continue;
                }
                final boolean isHevc = codec.contains("h265") || codec.contains("bytevc1") || urlKey.contains("bytevc1");
                final JSONArray urls = pa != null ? pa.optJSONArray("UrlList") : null;
                if (urls != null) {
                    for (int j = 0; j < urls.length(); j++) {
                        final String u = cleanMediaUrl(urls.optString(j, ""));
                        if (u.isEmpty() || u.contains("/media-video-hvc1/")) {
                            continue;
                        }
                        if (!isHevc) {
                            addUnique(h264Urls, u);
                        } else {
                            addUnique(otherUrls, u);
                        }
                    }
                }
            }
        }

        final String directPlay = extractPlayAddrField(video, "playAddr");
        if (!directPlay.isEmpty()) {
            addUnique(h264Urls, directPlay);
        }

        final String dlAddr = extractPlayAddrField(video, "downloadAddr");
        if (!dlAddr.isEmpty()) {
            addUnique(otherUrls, dlAddr);
        }

        final List<String> result = new ArrayList<>(h264Urls);
        for (String u : otherUrls) {
            addUnique(result, u);
        }
        return result;
    }

    private static String extractPlayAddrField(JSONObject video, String key) {
        final Object raw = video.opt(key);
        if (raw instanceof String) {
            return cleanMediaUrl((String) raw);
        }
        if (raw instanceof JSONObject) {
            final JSONObject obj = (JSONObject) raw;
            String u = firstUrlFromList(obj.optJSONArray("UrlList"));
            if (u.isEmpty()) {
                u = firstUrlFromList(obj.optJSONArray("url_list"));
            }
            if (u.isEmpty()) {
                u = cleanMediaUrl(obj.optString("src", ""));
            }
            return u;
        }
        if (raw instanceof JSONArray) {
            final JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) {
                final Object el = arr.opt(i);
                if (el instanceof String) {
                    final String u = cleanMediaUrl((String) el);
                    if (!u.isEmpty()) {
                        return u;
                    }
                } else if (el instanceof JSONObject) {
                    final String u = cleanMediaUrl(((JSONObject) el).optString("src", ""));
                    if (!u.isEmpty()) {
                        return u;
                    }
                }
            }
        }
        return "";
    }

    private static String firstUrlFromList(JSONArray arr) {
        if (arr == null) {
            return "";
        }
        for (int i = 0; i < arr.length(); i++) {
            final String u = cleanMediaUrl(arr.optString(i, ""));
            if (!u.isEmpty()) {
                return u;
            }
        }
        return "";
    }

    private static String cleanMediaUrl(String u) {
        if (u == null) {
            return "";
        }
        String s = u.trim()
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("\\/", "/");
        if (s.startsWith("//")) {
            s = "https:" + s;
        }
        if (!s.startsWith("http")) {
            return "";
        }
        // Битые редирект-заглушки на самом www.tiktok.com (см. yt-dlp #11034).
        if (s.contains("://www.tiktok.com/")) {
            return "";
        }
        return s;
    }

    private static void addUnique(List<String> list, String item) {
        if (item != null && !item.isEmpty() && !list.contains(item)) {
            list.add(item);
        }
    }

    /**
     * Решение JS Proof-of-Work челленджа TikTok WAF (как в yt-dlp _solve_challenge_and_set_cookies):
     * ищет число i в 0..1_000_000, для которого SHA-256(base + i) == expectedDigest.
     */
    static List<String> solveTikTokWafChallenge(String page, Map<String, String> cookies) {
        final List<String> added = new ArrayList<>();
        try {
            final String csClass = extractElementAttrById(page, "cs", "class");
            final String wciName = extractElementAttrById(page, "wci", "class");
            if (csClass == null || csClass.isEmpty() || wciName == null || wciName.isEmpty()) {
                return added;
            }
            final byte[] rawJson = base64DecodeFlexible(csClass);
            if (rawJson == null || rawJson.length == 0) {
                return added;
            }
            final JSONObject challenge = new JSONObject(new String(rawJson, StandardCharsets.UTF_8));
            final JSONObject v = challenge.optJSONObject("v");
            if (v == null) {
                return added;
            }
            final byte[] prefixBytes = base64DecodeFlexible(v.optString("a", ""));
            final byte[] expectedDigest = base64DecodeFlexible(v.optString("c", ""));
            if (prefixBytes == null || expectedDigest == null) {
                return added;
            }

            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            String solvedB64 = null;
            for (int i = 0; i <= 1_000_000; i++) {
                md.reset();
                md.update(prefixBytes);
                final byte[] numBytes = Integer.toString(i).getBytes(StandardCharsets.US_ASCII);
                final byte[] digest = md.digest(numBytes);
                if (MessageDigest.isEqual(digest, expectedDigest)) {
                    solvedB64 = base64Encode(numBytes);
                    break;
                }
            }
            if (solvedB64 == null) {
                return added;
            }
            challenge.put("d", solvedB64);
            final String cookieVal = base64Encode(challenge.toString().getBytes(StandardCharsets.UTF_8));
            cookies.put(wciName, cookieVal);
            added.add(wciName);

            final String rciName = extractElementAttrById(page, "rci", "class");
            final String rsVal = extractElementAttrById(page, "rs", "class");
            if (rciName != null && !rciName.isEmpty() && rsVal != null && !rsVal.isEmpty()) {
                cookies.put(rciName, rsVal);
                added.add(rciName);
            }
        } catch (Throwable ignore) {
        }
        return added;
    }

    private static String extractElementAttrById(String html, String id, String attr) {
        final Matcher tagMatcher = Pattern.compile(
                "<[^>]+\\bid=[\"']" + Pattern.quote(id) + "[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (!tagMatcher.find()) {
            return null;
        }
        final String tag = tagMatcher.group();
        final Matcher attrMatcher = Pattern.compile(
                "\\b" + Pattern.quote(attr) + "=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
        return attrMatcher.find() ? attrMatcher.group(1) : null;
    }

    /** Резервный парсер страницы https://www.tiktok.com/embed/v2/<id>. */
    private static Resolved resolveTikTokEmbed(String postId, Map<String, String> cookies) {
        try {
            final String embedUrl = "https://www.tiktok.com/embed/v2/" + postId;
            final String html = httpGet(embedUrl, UA_DESKTOP, cookies, "https://www.tiktok.com/");
            final JSONObject frontity = extractJsonScriptById(html, "__FRONTITY_CONNECT_STATE__");
            if (frontity != null) {
                final JSONObject source = frontity.optJSONObject("source");
                final JSONObject data = source != null ? source.optJSONObject("data") : null;
                if (data != null) {
                    final Iterator<String> keys = data.keys();
                    while (keys.hasNext()) {
                        final JSONObject node = data.optJSONObject(keys.next());
                        final JSONObject videoData = node != null ? node.optJSONObject("videoData") : null;
                        if (videoData != null) {
                            final Resolved parsed = parseTikTokEmbedVideoData(videoData, cookies, embedUrl);
                            if (parsed != null) {
                                return parsed;
                            }
                        }
                    }
                }
            }
            return resolveTikTokLayered(html, cookies, embedUrl);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Resolved parseTikTokEmbedVideoData(JSONObject videoData, Map<String, String> cookies, String referer) {
        final JSONObject itemInfos = videoData.optJSONObject("itemInfos");
        final JSONObject authorInfos = videoData.optJSONObject("authorInfos");
        final String author = authorInfos != null ? authorInfos.optString("uniqueId", "") : "";
        final String desc = itemInfos != null ? itemInfos.optString("text", "") : "";
        final String caption = buildTikTokCaption(author, desc);

        final JSONArray imagePost = videoData.optJSONArray("imagePostInfo");
        if (imagePost != null && imagePost.length() > 0) {
            final List<PhotoItem> photos = extractTikTokPhotos(imagePost);
            if (!photos.isEmpty()) {
                return Resolved.forPhotos(PLATFORM_TIKTOK, photos, "", caption, author,
                        new HashMap<>(cookies), referer, UA_DESKTOP);
            }
        }
        if (itemInfos != null) {
            final JSONObject video = itemInfos.optJSONObject("video");
            if (video != null) {
                final String u = firstUrlFromList(video.optJSONArray("urls"));
                if (!u.isEmpty()) {
                    final String cover = firstUrlFromList(itemInfos.optJSONArray("coversOrigin"));
                    return Resolved.forVideo(PLATFORM_TIKTOK, Arrays.asList(u), false,
                            caption, author, 0L, cover, new HashMap<>(cookies), referer, UA_DESKTOP, false);
                }
            }
        }
        return null;
    }

    /** Слои 2/3: страница без SSR-блока — og:video-мета, затем сырой playAddr. */
    static Resolved resolveTikTokLayered(String page, Map<String, String> cookies, String referer) {
        String play = extractMetaContent(page, "og:video");
        if (play == null || !play.startsWith("http")) {
            play = extractMetaContent(page, "og:video:secure_url");
        }
        String title = extractMetaContent(page, "og:title");
        String thumb = extractMetaContent(page, "og:image");
        if (play == null || !play.startsWith("http")) {
            final Matcher m = Pattern.compile("\"playAddr\"\\s*:\\s*\"(https:[^\"]+)\"").matcher(page);
            if (m.find()) {
                play = cleanMediaUrl(m.group(1));
            }
        }
        play = cleanMediaUrl(play);
        if (play.isEmpty()) {
            return null;
        }
        if (title == null) {
            title = "";
        }
        return Resolved.forVideo(PLATFORM_TIKTOK, Arrays.asList(play), false,
                title, "", 0L, cleanMediaUrl(thumb), new HashMap<>(cookies), referer, UA_DESKTOP, false);
    }

    static String extractMetaContent(String page, String property) {
        final int at = page.indexOf("property=\"" + property + "\"");
        if (at < 0) {
            return null;
        }
        final int from = Math.max(0, at - 300);
        final int to = Math.min(page.length(), at + 300);
        final String window = page.substring(from, to);
        final Matcher m = Pattern.compile("content=\"([^\"]+)\"").matcher(window);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    static JSONObject extractTikTokItem(String page) throws Exception {
        if (page == null) {
            return null;
        }
        final JSONObject root = extractJsonScriptById(page, "__UNIVERSAL_DATA_FOR_REHYDRATION__");
        if (root != null) {
            final JSONObject scope = root.optJSONObject("__DEFAULT_SCOPE__");
            if (scope != null) {
                JSONObject detail = scope.optJSONObject("webapp.video-detail");
                if (detail == null) {
                    detail = scope.optJSONObject("webapp.photo-detail");
                }
                final JSONObject itemInfo = detail != null ? detail.optJSONObject("itemInfo") : null;
                final JSONObject itemStruct = itemInfo != null ? itemInfo.optJSONObject("itemStruct") : null;
                if (itemStruct != null) {
                    return itemStruct;
                }
            }
        }
        final JSONObject sigi = extractJsonScriptById(page, "SIGI_STATE");
        if (sigi != null) {
            final JSONObject itemModule = sigi.optJSONObject("ItemModule");
            if (itemModule != null) {
                final Iterator<String> keys = itemModule.keys();
                if (keys.hasNext()) {
                    return itemModule.optJSONObject(keys.next());
                }
            }
        }
        return null;
    }

    private static JSONObject extractJsonScriptById(String page, String marker) {
        final int scriptAt = page.indexOf(marker);
        if (scriptAt < 0) {
            return null;
        }
        final int jsonStart = page.indexOf('{', scriptAt);
        if (jsonStart < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int end = -1;
        for (int i = jsonStart; i < page.length(); i++) {
            final char c = page.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end > jsonStart) {
            try {
                return new JSONObject(page.substring(jsonStart, end + 1));
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private static String buildTikTokCaption(String author, String desc) {
        final StringBuilder sb = new StringBuilder();
        if (author != null && !author.isEmpty()) {
            sb.append('@').append(author);
        }
        if (desc != null && !desc.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" — ");
            }
            sb.append(desc);
        }
        final String out = sb.toString();
        return out.length() > 1024 ? out.substring(0, 1024) : out;
    }

    /** Разворачивает vm/vt.tiktok.com и HTML-редиректы, сохраняя cookie. */
    static String followRedirects(String url, Map<String, String> cookies, int maxHops) throws Exception {
        return followRedirectsWithUa(url, cookies, maxHops, UA_DESKTOP);
    }

    private static String followRedirectsWithUa(String url, Map<String, String> cookies, int maxHops, String ua)
            throws Exception {
        String current = url;
        for (int hop = 0; hop < maxHops; hop++) {
            final HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", ua);
            applyCookies(conn, cookies);
            final int code = conn.getResponseCode();
            storeCookies(conn, cookies);
            if (code / 100 == 3) {
                final String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    break;
                }
                current = location.startsWith("http") ? location : new URL(new URL(current), location).toString();
                if (extractTikTokPostId(current) != null) {
                    break;
                }
                continue;
            }
            // TikTok на vt.tiktok.com иногда возвращает 200 с <a href="https://www.tiktok.com/...">
            if (code == 200 && extractTikTokPostId(current) == null) {
                try {
                    final String body = readLimited(conn, 8192);
                    final Matcher href = Pattern.compile("href=\"(https?://[^\"]+tiktok[^\"]+)\"").matcher(body);
                    if (href.find()) {
                        current = href.group(1).replace("&amp;", "&");
                    }
                } catch (Throwable ignore) {
                }
            }
            conn.disconnect();
            break;
        }
        return current;
    }

    // ------------------------------------------------------------------ HTTP

    public static String httpGet(String url, String userAgent, Map<String, String> cookies, String referer)
            throws Exception {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            final HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent != null ? userAgent : UA_DESKTOP);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ru;q=0.8");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (cookies != null) {
                applyCookies(conn, cookies);
            }
            try {
                final int code = conn.getResponseCode();
                if (cookies != null) {
                    storeCookies(conn, cookies);
                }
                if (code / 100 == 3) {
                    final String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        current = loc.startsWith("http") ? loc : new URL(new URL(current), loc).toString();
                        continue;
                    }
                }
                if (code / 100 != 2) {
                    throw new Exception("http " + code);
                }
                return readAll(conn);
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    public static String httpPost(String url, String jsonBody, String userAgent, Map<String, String> cookies)
            throws Exception {
        return httpPostWithHeaders(url, jsonBody, userAgent, cookies, null);
    }

    private static String httpPostWithHeaders(String url, String jsonBody, String userAgent,
                                              Map<String, String> cookies, Map<String, String> extraHeaders)
            throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", userAgent);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
        }
        if (cookies != null) {
            applyCookies(conn, cookies);
        }
        final byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        try {
            final int code = conn.getResponseCode();
            if (code / 100 != 2) {
                throw new Exception("http " + code);
            }
            return readAll(conn);
        } finally {
            conn.disconnect();
        }
    }

    static void applyCookies(HttpURLConnection conn, Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        conn.setRequestProperty("Cookie", sb.toString());
    }

    static void storeCookies(HttpURLConnection conn, Map<String, String> cookies) {
        final Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                for (String c : entry.getValue()) {
                    final int semi = c.indexOf(';');
                    final String pair = semi > 0 ? c.substring(0, semi) : c;
                    final int eq = pair.indexOf('=');
                    if (eq > 0) {
                        cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                    }
                }
            }
        }
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder(8192);
            final char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private static String readLimited(HttpURLConnection conn, int maxChars) throws Exception {
        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder(Math.min(maxChars, 4096));
            final char[] buf = new char[2048];
            int n;
            while (sb.length() < maxChars && (n = reader.read(buf, 0, Math.min(buf.length, maxChars - sb.length()))) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    /** Совместимая перегрузка скачивания (UA по умолчанию). */
    public static long download(String url, File target, long maxBytes,
                                Map<String, String> cookies, String referer,
                                boolean[] cancel, Progress progress) throws Exception {
        return download(url, target, maxBytes, cookies, referer, UA_CHROME, false, cancel, progress);
    }

    /**
     * Скачивает поток в файл с поддержкой кастомного User-Agent, кросс-доменных
     * редиректов с сохранением Cookie и чанкового Range-скачивания (для YouTube GVS).
     */
    public static long download(String url, File target, long maxBytes,
                                Map<String, String> cookies, String referer,
                                String userAgent, boolean rangeChunked,
                                boolean[] cancel, Progress progress) throws Exception {
        return download(url, target, maxBytes, cookies, referer, userAgent, rangeChunked, false, cancel, progress);
    }

    public static long download(String url, File target, long maxBytes,
                                Map<String, String> cookies, String referer,
                                String userAgent, boolean rangeChunked, boolean hlsStream,
                                boolean[] cancel, Progress progress) throws Exception {
        final String ua = (userAgent != null && !userAgent.isEmpty()) ? userAgent : UA_CHROME;
        if (hlsStream || (url != null && url.toLowerCase().contains(".m3u8"))) {
            return downloadHlsStream(url, target, maxBytes, cookies, referer, ua, cancel, progress);
        }
        if (rangeChunked) {
            return downloadRangeChunked(url, target, maxBytes, cookies, referer, ua, cancel, progress);
        }
        return downloadSingleStream(url, target, maxBytes, cookies, referer, ua, cancel, progress);
    }

    private static long downloadSingleStream(String url, File target, long maxBytes,
                                             Map<String, String> cookies, String referer, String ua,
                                             boolean[] cancel, Progress progress) throws Exception {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            final HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", ua);
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (cookies != null && !cookies.isEmpty()) {
                applyCookies(conn, cookies);
            }
            try {
                final int code = conn.getResponseCode();
                if (cookies != null) {
                    storeCookies(conn, cookies);
                }
                if (code / 100 == 3) {
                    final String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        current = loc.startsWith("http") ? loc : new URL(new URL(current), loc).toString();
                        continue;
                    }
                }
                if (code / 100 != 2) {
                    throw new Exception("http " + code);
                }
                final long total = conn.getContentLengthLong();
                long written = 0;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(target)) {
                    final byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel[0]) {
                            throw new java.util.concurrent.CancellationException("cancelled");
                        }
                        written += n;
                        if (maxBytes > 0 && written > maxBytes) {
                            throw new Exception("too big");
                        }
                        out.write(buf, 0, n);
                        if (progress != null) {
                            progress.onBytes(written, total);
                        }
                    }
                }
                return written;
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    private static long downloadRangeChunked(String url, File target, long maxBytes,
                                             Map<String, String> cookies, String referer, String ua,
                                             boolean[] cancel, Progress progress) throws Exception {
        long written = 0;
        long totalSize = -1;
        try (FileOutputStream out = new FileOutputStream(target)) {
            final byte[] buf = new byte[64 * 1024];
            while (totalSize < 0 || written < totalSize) {
                if (cancel != null && cancel[0]) {
                    throw new java.util.concurrent.CancellationException("cancelled");
                }
                final long rangeEnd = totalSize > 0
                        ? Math.min(totalSize - 1, written + RANGE_CHUNK_BYTES - 1)
                        : (written + RANGE_CHUNK_BYTES - 1);

                String current = url;
                boolean chunkDone = false;
                for (int hop = 0; hop < 5; hop++) {
                    final HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15_000);
                    conn.setReadTimeout(25_000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", ua);
                    conn.setRequestProperty("Range", "bytes=" + written + "-" + rangeEnd);
                    if (referer != null && !referer.isEmpty()) {
                        conn.setRequestProperty("Referer", referer);
                    }
                    if (cookies != null && !cookies.isEmpty()) {
                        applyCookies(conn, cookies);
                    }
                    try {
                        final int code = conn.getResponseCode();
                        if (code / 100 == 3) {
                            final String loc = conn.getHeaderField("Location");
                            if (loc != null && !loc.isEmpty()) {
                                current = loc.startsWith("http") ? loc : new URL(new URL(current), loc).toString();
                                continue;
                            }
                        }
                        if (code != 206 && code != 200) {
                            throw new Exception("http " + code);
                        }
                        if (code == 206 && totalSize < 0) {
                            final String cr = conn.getHeaderField("Content-Range");
                            if (cr != null) {
                                final int slash = cr.lastIndexOf('/');
                                if (slash > 0 && slash + 1 < cr.length()) {
                                    try {
                                        totalSize = Long.parseLong(cr.substring(slash + 1).trim());
                                    } catch (NumberFormatException ignore) {
                                    }
                                }
                            }
                        } else if (code == 200 && written == 0) {
                            totalSize = conn.getContentLengthLong();
                        }

                        long readInChunk = 0;
                        try (InputStream in = conn.getInputStream()) {
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                if (cancel != null && cancel[0]) {
                                    throw new java.util.concurrent.CancellationException("cancelled");
                                }
                                written += n;
                                readInChunk += n;
                                if (maxBytes > 0 && written > maxBytes) {
                                    throw new Exception("too big");
                                }
                                out.write(buf, 0, n);
                                if (progress != null) {
                                    progress.onBytes(written, totalSize);
                                }
                            }
                        }
                        chunkDone = true;
                        if (code == 200 || readInChunk == 0) {
                            return written;
                        }
                        break;
                    } finally {
                        conn.disconnect();
                    }
                }
                if (!chunkDone) {
                    throw new Exception("chunk redirect failed");
                }
            }
        }
        return written;
    }

    // --- Автономный RFC 4648 Base64 (совместимо с minSdk 23 и чистой JVM без android.jar) ---

    private static final char[] B64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64Encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            final int b0 = data[i] & 0xFF;
            final int b1 = (i + 1 < data.length) ? (data[i + 1] & 0xFF) : 0;
            final int b2 = (i + 2 < data.length) ? (data[i + 2] & 0xFF) : 0;
            sb.append(B64_ALPHABET[b0 >> 2]);
            sb.append(B64_ALPHABET[((b0 & 0x03) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < data.length ? B64_ALPHABET[((b1 & 0x0F) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < data.length ? B64_ALPHABET[b2 & 0x3F] : '=');
        }
        return sb.toString();
    }

    static byte[] base64DecodeFlexible(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        final int[] inv = new int[128];
        Arrays.fill(inv, -1);
        for (int i = 0; i < B64_ALPHABET.length; i++) {
            inv[B64_ALPHABET[i]] = i;
        }
        inv['-'] = 62;
        inv['_'] = 63;

        final int[] vals = new int[input.length()];
        int len = 0;
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            if (c == '=') {
                break;
            }
            if (c < 128 && inv[c] >= 0) {
                vals[len++] = inv[c];
            }
        }
        if (len < 2) {
            return null;
        }
        final int outLen = (len * 3) / 4;
        final byte[] out = new byte[outLen];
        int o = 0;
        for (int i = 0; i + 1 < len && o < outLen; i += 4) {
            final int v0 = vals[i];
            final int v1 = vals[i + 1];
            final int v2 = (i + 2 < len) ? vals[i + 2] : 0;
            final int v3 = (i + 3 < len) ? vals[i + 3] : 0;
            out[o++] = (byte) ((v0 << 2) | (v1 >> 4));
            if (o < outLen && i + 2 < len) {
                out[o++] = (byte) (((v1 & 0x0F) << 4) | (v2 >> 2));
            }
            if (o < outLen && i + 3 < len) {
                out[o++] = (byte) (((v2 & 0x03) << 6) | v3);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- HLS (.m3u8 → .ts / fMP4)

    /**
     * Скачивает HLS-плейлист (master или media): при master-плейлисте выбирает лучший поток,
     * затем последовательно дописывает сегменты (включая #EXT-X-MAP init) в выходной файл.
     */
    private static long downloadHlsStream(String playlistUrl, File target, long maxBytes,
                                          Map<String, String> cookies, String referer, String ua,
                                          boolean[] cancel, Progress progress) throws Exception {
        String activeUrl = playlistUrl;
        String body = httpGetWithHeaders(activeUrl, ua, cookies, referer, null);
        if (body == null || !body.contains("#EXTM3U")) {
            return downloadSingleStream(playlistUrl, target, maxBytes, cookies, referer, ua, cancel, progress);
        }
        if (body.contains("#EXT-X-STREAM-INF")) {
            final String variant = selectBestHlsVariant(body, activeUrl, 1080);
            if (variant != null && !variant.isEmpty()) {
                activeUrl = variant;
                body = httpGetWithHeaders(activeUrl, ua, cookies, referer, null);
            }
        }
        final URL base = new URL(activeUrl);
        final List<String> segments = new ArrayList<>();
        final String[] lines = body.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-KEY") && line.contains("METHOD=AES-128")) {
                throw new Exception("encrypted hls");
            }
            if (line.startsWith("#EXT-X-MAP:")) {
                final Matcher mm = Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                if (mm.find()) {
                    segments.add(new URL(base, mm.group(1)).toString());
                }
                continue;
            }
            if (!line.startsWith("#")) {
                segments.add(line.startsWith("http") ? line : new URL(base, line).toString());
            }
        }
        if (segments.isEmpty()) {
            throw new Exception("empty hls playlist");
        }
        long written = 0L;
        final int totalSegs = segments.size();
        final byte[] buf = new byte[64 * 1024];
        try (FileOutputStream out = new FileOutputStream(target)) {
            for (int idx = 0; idx < totalSegs; idx++) {
                if (cancel != null && cancel[0]) {
                    throw new java.util.concurrent.CancellationException("cancelled");
                }
                String segUrl = segments.get(idx);
                for (int hop = 0; hop < 4; hop++) {
                    final HttpURLConnection conn = (HttpURLConnection) new URL(segUrl).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(12_000);
                    conn.setReadTimeout(20_000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", ua);
                    if (referer != null && !referer.isEmpty()) {
                        conn.setRequestProperty("Referer", referer);
                    }
                    if (cookies != null && !cookies.isEmpty()) {
                        applyCookies(conn, cookies);
                    }
                    try {
                        final int code = conn.getResponseCode();
                        if (code / 100 == 3) {
                            final String loc = conn.getHeaderField("Location");
                            if (loc != null && !loc.isEmpty()) {
                                segUrl = loc.startsWith("http") ? loc : new URL(new URL(segUrl), loc).toString();
                                continue;
                            }
                        }
                        if (code / 100 != 2) {
                            throw new Exception("hls seg http " + code);
                        }
                        try (InputStream in = conn.getInputStream()) {
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                if (cancel != null && cancel[0]) {
                                    throw new java.util.concurrent.CancellationException("cancelled");
                                }
                                written += n;
                                if (maxBytes > 0 && written > maxBytes) {
                                    throw new Exception("too big");
                                }
                                out.write(buf, 0, n);
                            }
                        }
                        if (progress != null) {
                            final long estTotal = (written * totalSegs) / Math.max(1, idx + 1);
                            progress.onBytes(written, estTotal);
                        }
                        break;
                    } finally {
                        conn.disconnect();
                    }
                }
            }
        }
        return written;
    }

    private static String selectBestHlsVariant(String masterM3u8, String masterUrl, int maxVideoHeight) {
        try {
            final URL base = new URL(masterUrl);
            final String[] lines = masterM3u8.split("\r?\n");
            String bestAllowed = null;
            int bestAllowedH = -1;
            long bestAllowedBw = -1L;
            String smallestOver = null;
            int smallestOverH = Integer.MAX_VALUE;

            for (int i = 0; i < lines.length; i++) {
                final String line = lines[i].trim();
                if (!line.startsWith("#EXT-X-STREAM-INF")) {
                    continue;
                }
                int h = 0;
                long bw = 0L;
                final Matcher rm = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(line);
                if (rm.find()) {
                    final int w = Integer.parseInt(rm.group(1));
                    final int hh = Integer.parseInt(rm.group(2));
                    h = Math.min(w, hh) > 0 && hh > w ? w : hh; // для вертикальных 1080x1920 короткая сторона = 1080p
                }
                final Matcher bm = Pattern.compile("BANDWIDTH=(\\d+)").matcher(line);
                if (bm.find()) {
                    bw = Long.parseLong(bm.group(1));
                }
                String nextUri = null;
                for (int j = i + 1; j < lines.length; j++) {
                    final String cand = lines[j].trim();
                    if (cand.isEmpty()) continue;
                    if (!cand.startsWith("#")) {
                        nextUri = cand.startsWith("http") ? cand : new URL(base, cand).toString();
                    }
                    break;
                }
                if (nextUri == null) continue;
                if (h <= 0 || h <= maxVideoHeight) {
                    if (h > bestAllowedH || (h == bestAllowedH && bw > bestAllowedBw)) {
                        bestAllowed = nextUri;
                        bestAllowedH = h;
                        bestAllowedBw = bw;
                    }
                } else if (h < smallestOverH) {
                    smallestOver = nextUri;
                    smallestOverH = h;
                }
            }
            return bestAllowed != null ? bestAllowed : smallestOver;
        } catch (Throwable ignore) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Instagram (Reels и карусели)

    private static final String UA_IG_APP =
            "Instagram 317.0.0.34.109 Android (33/13; 420dpi; 1080x2400; Google/google; Pixel 7; panther; panther; en_US; 562249123)";

    private static Resolved resolveInstagram(String rawUrl, int maxVideoHeight) throws Exception {
        String url = rawUrl.replace("ddinstagram.com", "www.instagram.com")
                .replace("kkinstagram.com", "www.instagram.com");
        final Map<String, String> cookies = new HashMap<>();
        if (url.toLowerCase(Locale.US).contains("/share/")) {
            // /share/ отдаёт 3xx на канонический /reel/<id> только для не-браузерного UA (curl).
            try {
                url = followRedirects(url, cookies, "curl/8.5.0");
            } catch (Throwable ignore) {
            }
        }
        final String shortcode = extractInstagramShortcode(url);
        if (shortcode == null || shortcode.isEmpty()) {
            throw new Exception("bad instagram url");
        }
        Exception lastErr = null;
        // Слой 1: Мобильный oEmbed -> media_id -> /api/v1/media/{id}/info/
        try {
            final Resolved r1 = resolveInstagramMobileApi(shortcode, maxVideoHeight, cookies);
            if (r1 != null) return r1;
        } catch (Exception e) {
            lastErr = e;
        }
        // Слой 2: /p/{shortcode}/embed/captioned/ (contextJSON / sidecar / прямой video_url)
        try {
            final Resolved r2 = resolveInstagramEmbed(shortcode, cookies);
            if (r2 != null) return r2;
        } catch (Exception e) {
            lastErr = e;
        }
        // Слой 3: GraphQL PolarisPostActionLoadPostQueryQuery (doc_id=8845758582119845)
        try {
            final Resolved r3 = resolveInstagramGraphql(shortcode, maxVideoHeight, cookies);
            if (r3 != null) return r3;
        } catch (Exception e) {
            lastErr = e;
        }
        throw lastErr != null ? lastErr : new Exception("instagram resolve failed");
    }

    private static String extractInstagramShortcode(String url) {
        final Matcher m = Pattern.compile("(?i)instagram\\.com/(?:[A-Za-z0-9._]{1,30}/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]{5,36})").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static Resolved resolveInstagramMobileApi(String shortcode, int maxVideoHeight, Map<String, String> cookies) throws Exception {
        final Map<String, String> headers = new HashMap<>();
        headers.put("x-ig-app-id", "936619743392459");
        headers.put("X-IG-App-Locale", "en_US");
        headers.put("Accept", "*/*");
        final String oembedUrl = "https://i.instagram.com/api/v1/oembed/?url="
                + URLEncoder.encode("https://www.instagram.com/p/" + shortcode + "/", "UTF-8");
        final String oembedJson = httpGetWithHeaders(oembedUrl, UA_IG_APP, cookies, "https://www.instagram.com/", headers);
        final JSONObject oe = new JSONObject(oembedJson);
        final String mediaId = oe.optString("media_id", "");
        if (mediaId.isEmpty()) {
            return null;
        }
        final String infoJson = httpGetWithHeaders("https://i.instagram.com/api/v1/media/" + mediaId + "/info/",
                UA_IG_APP, cookies, "https://www.instagram.com/", headers);
        final JSONObject root = new JSONObject(infoJson);
        final JSONArray items = root.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return null;
        }
        final JSONObject item = items.getJSONObject(0);
        final JSONObject user = item.optJSONObject("user");
        final String author = user != null ? user.optString("username", user.optString("full_name", "")) : "";
        final JSONObject cap = item.optJSONObject("caption");
        final String title = cap != null ? cap.optString("text", "") : oe.optString("title", "");

        final JSONArray carousel = item.optJSONArray("carousel_media");
        if (carousel != null && carousel.length() > 0) {
            final List<PhotoItem> photos = new ArrayList<>();
            String singleVideo = null;
            String singleThumb = "";
            for (int i = 0; i < carousel.length(); i++) {
                final JSONObject cm = carousel.optJSONObject(i);
                if (cm == null) continue;
                final String vUrl = pickIgVideoVersion(cm.optJSONArray("video_versions"), maxVideoHeight);
                final PhotoItem img = pickIgImageCandidate(cm.optJSONObject("image_versions2"));
                if (vUrl != null && carousel.length() == 1) {
                    singleVideo = vUrl;
                    singleThumb = img != null ? img.imageUrl : "";
                } else if (img != null) {
                    photos.add(img);
                }
            }
            if (singleVideo != null) {
                return Resolved.forVideo(PLATFORM_INSTAGRAM, Collections.singletonList(singleVideo), false,
                        title, author, (long) (item.optDouble("video_duration", 0) * 1000L),
                        singleThumb, cookies, "https://www.instagram.com/", UA_DESKTOP, false);
            }
            if (!photos.isEmpty()) {
                return Resolved.forPhotos(PLATFORM_INSTAGRAM, photos, "", title, author,
                        cookies, "https://www.instagram.com/", UA_DESKTOP);
            }
        }
        final String vUrl = pickIgVideoVersion(item.optJSONArray("video_versions"), maxVideoHeight);
        final PhotoItem img = pickIgImageCandidate(item.optJSONObject("image_versions2"));
        if (vUrl != null) {
            return Resolved.forVideo(PLATFORM_INSTAGRAM, Collections.singletonList(vUrl), false,
                    title, author, (long) (item.optDouble("video_duration", 0) * 1000L),
                    img != null ? img.imageUrl : "", cookies, "https://www.instagram.com/", UA_DESKTOP, false);
        }
        if (img != null) {
            return Resolved.forPhotos(PLATFORM_INSTAGRAM, Collections.singletonList(img), "",
                    title, author, cookies, "https://www.instagram.com/", UA_DESKTOP);
        }
        return null;
    }

    private static String pickIgVideoVersion(JSONArray versions, int maxHeight) {
        if (versions == null || versions.length() == 0) return null;
        String best = null;
        int bestShortSide = -1;
        String fallback = null;
        for (int i = 0; i < versions.length(); i++) {
            final JSONObject v = versions.optJSONObject(i);
            if (v == null) continue;
            final String u = cleanGenericUrl(v.optString("url", ""));
            if (u.isEmpty()) continue;
            if (fallback == null) fallback = u;
            final int w = v.optInt("width", 0);
            final int h = v.optInt("height", 0);
            final int shortSide = (w > 0 && h > 0) ? Math.min(w, h) : h;
            if (shortSide <= maxHeight && shortSide > bestShortSide) {
                best = u;
                bestShortSide = shortSide;
            }
        }
        return best != null ? best : fallback;
    }

    private static PhotoItem pickIgImageCandidate(JSONObject imageVersions2) {
        if (imageVersions2 == null) return null;
        final JSONArray cands = imageVersions2.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return null;
        JSONObject best = null;
        int bestArea = -1;
        for (int i = 0; i < cands.length(); i++) {
            final JSONObject c = cands.optJSONObject(i);
            if (c == null) continue;
            final String u = cleanGenericUrl(c.optString("url", ""));
            if (u.isEmpty()) continue;
            final int area = Math.max(1, c.optInt("width", 1)) * Math.max(1, c.optInt("height", 1));
            if (area > bestArea) {
                best = c;
                bestArea = area;
            }
        }
        if (best == null) return null;
        final String url = cleanGenericUrl(best.optString("url", ""));
        return new PhotoItem(url, url, best.optInt("width", 0), best.optInt("height", 0));
    }

    private static Resolved resolveInstagramEmbed(String shortcode, Map<String, String> cookies) throws Exception {
        final String embedUrl = "https://www.instagram.com/p/" + shortcode + "/embed/captioned/";
        final String html = httpGet(embedUrl, UA_DESKTOP, cookies, "https://www.instagram.com/");
        final Matcher cm = Pattern.compile("\"contextJSON\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(html);
        if (cm.find()) {
            final String unescaped = new JSONObject("{\"v\":\"" + cm.group(1) + "\"}").optString("v", "");
            if (!unescaped.isEmpty()) {
                final JSONObject ctx = new JSONObject(unescaped);
                JSONObject media = null;
                final JSONObject gql = ctx.optJSONObject("gql_data");
                if (gql != null) {
                    media = gql.optJSONObject("shortcode_media");
                    if (media == null) media = gql.optJSONObject("xdt_shortcode_media");
                }
                if (media != null) {
                    final Resolved parsed = parseInstagramGraphMedia(media, cookies);
                    if (parsed != null) return parsed;
                }
            }
        }
        final Matcher vm = Pattern.compile("\"video_url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"").matcher(html);
        if (vm.find()) {
            final String vUrl = cleanGenericUrl(unescapeJsonString(vm.group(1)));
            if (!vUrl.isEmpty()) {
                String thumb = "";
                final Matcher dm = Pattern.compile("\"display_url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"").matcher(html);
                if (dm.find()) thumb = cleanGenericUrl(unescapeJsonString(dm.group(1)));
                return Resolved.forVideo(PLATFORM_INSTAGRAM, Collections.singletonList(vUrl), false,
                        "", "", 0L, thumb, cookies, "https://www.instagram.com/", UA_DESKTOP, false);
            }
        }
        return null;
    }

    private static Resolved resolveInstagramGraphql(String shortcode, int maxVideoHeight, Map<String, String> cookies) throws Exception {
        final String pageHtml = httpGet("https://www.instagram.com/p/" + shortcode + "/", UA_DESKTOP, cookies, "https://www.instagram.com/");
        String lsd = "AVqbxe3J_YA";
        final Matcher lm = Pattern.compile("\"LSD\"\\s*,\\s*\\[\\]\\s*,\\s*\\{\\s*\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(pageHtml);
        if (lm.find()) lsd = lm.group(1);
        final String csrf = cookies.containsKey("csrftoken") ? cookies.get("csrftoken") : "";

        final JSONObject vars = new JSONObject()
                .put("shortcode", shortcode)
                .put("fetch_tagged_user_count", JSONObject.NULL)
                .put("hoisted_comment_id", JSONObject.NULL)
                .put("hoisted_reply_id", JSONObject.NULL);
        final String form = "av=0&__d=www&__user=0&__a=1&__req=3&lsd=" + URLEncoder.encode(lsd, "UTF-8")
                + "&fb_api_caller_class=RelayModern&fb_api_req_friendly_name=PolarisPostActionLoadPostQueryQuery"
                + "&variables=" + URLEncoder.encode(vars.toString(), "UTF-8")
                + "&server_timestamps=true&doc_id=8845758582119845";

        final Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("X-IG-App-ID", "936619743392459");
        headers.put("X-FB-LSD", lsd);
        headers.put("X-ASBD-ID", "129477");
        if (!csrf.isEmpty()) headers.put("X-CSRFToken", csrf);

        final String resp = httpPost("https://www.instagram.com/graphql/query", form,
                UA_DESKTOP, cookies, "https://www.instagram.com/p/" + shortcode + "/", headers);
        final JSONObject data = new JSONObject(resp).optJSONObject("data");
        if (data == null) return null;
        JSONObject media = data.optJSONObject("xdt_shortcode_media");
        if (media == null) media = data.optJSONObject("shortcode_media");
        return media != null ? parseInstagramGraphMedia(media, cookies) : null;
    }

    private static Resolved parseInstagramGraphMedia(JSONObject media, Map<String, String> cookies) {
        final JSONObject owner = media.optJSONObject("owner");
        final String author = owner != null ? owner.optString("username", "") : "";
        String title = "";
        final JSONObject capConn = media.optJSONObject("edge_media_to_caption");
        final JSONArray capEdges = capConn != null ? capConn.optJSONArray("edges") : null;
        if (capEdges != null && capEdges.length() > 0) {
            final JSONObject n = capEdges.optJSONObject(0) != null ? capEdges.optJSONObject(0).optJSONObject("node") : null;
            if (n != null) title = n.optString("text", "");
        }
        final JSONObject sidecar = media.optJSONObject("edge_sidecar_to_children");
        final JSONArray edges = sidecar != null ? sidecar.optJSONArray("edges") : null;
        if (edges != null && edges.length() > 0) {
            final List<PhotoItem> photos = new ArrayList<>();
            for (int i = 0; i < edges.length(); i++) {
                final JSONObject edge = edges.optJSONObject(i);
                final JSONObject node = edge != null ? edge.optJSONObject("node") : null;
                if (node == null) continue;
                if (node.optBoolean("is_video", false) && edges.length() == 1) {
                    final String vu = cleanGenericUrl(node.optString("video_url", ""));
                    if (!vu.isEmpty()) {
                        return Resolved.forVideo(PLATFORM_INSTAGRAM, Collections.singletonList(vu), false,
                                title, author, (long) (node.optDouble("video_duration", 0) * 1000L),
                                cleanGenericUrl(node.optString("display_url", "")),
                                cookies, "https://www.instagram.com/", UA_DESKTOP, false);
                    }
                }
                final String img = cleanGenericUrl(node.optString("display_url", ""));
                if (!img.isEmpty()) {
                    final JSONObject dims = node.optJSONObject("dimensions");
                    photos.add(new PhotoItem(img, img,
                            dims != null ? dims.optInt("width", 0) : 0,
                            dims != null ? dims.optInt("height", 0) : 0));
                }
            }
            if (!photos.isEmpty()) {
                return Resolved.forPhotos(PLATFORM_INSTAGRAM, photos, "", title, author,
                        cookies, "https://www.instagram.com/", UA_DESKTOP);
            }
        }
        if (media.optBoolean("is_video", false)) {
            final String vu = cleanGenericUrl(media.optString("video_url", ""));
            if (!vu.isEmpty()) {
                return Resolved.forVideo(PLATFORM_INSTAGRAM, Collections.singletonList(vu), false,
                        title, author, (long) (media.optDouble("video_duration", 0) * 1000L),
                        cleanGenericUrl(media.optString("display_url", "")),
                        cookies, "https://www.instagram.com/", UA_DESKTOP, false);
            }
        }
        final String disp = cleanGenericUrl(media.optString("display_url", ""));
        if (!disp.isEmpty()) {
            final JSONObject dims = media.optJSONObject("dimensions");
            final PhotoItem p = new PhotoItem(disp, disp,
                    dims != null ? dims.optInt("width", 0) : 0,
                    dims != null ? dims.optInt("height", 0) : 0);
            return Resolved.forPhotos(PLATFORM_INSTAGRAM, Collections.singletonList(p), "",
                    title, author, cookies, "https://www.instagram.com/", UA_DESKTOP);
        }
        return null;
    }

    // ---------------------------------------------------------------- X / Twitter (fxtwitter + syndication)

    private static Resolved resolveTwitter(String rawUrl, int maxVideoHeight) throws Exception {
        String url = rawUrl;
        final Map<String, String> cookies = new HashMap<>();
        if (url.toLowerCase(Locale.US).contains("//t.co/") || url.toLowerCase(Locale.US).startsWith("t.co/")) {
            url = followRedirects(url, cookies, UA_DESKTOP);
        }
        final Matcher m = Pattern.compile("(?i)(?:twitter|x)\\.com/(?:[A-Za-z0-9_]{1,20}/status|i/web/status|i/status)/(\\d{5,25})").matcher(url);
        if (!m.find()) {
            throw new Exception("bad twitter url");
        }
        final String tweetId = m.group(1);
        Exception lastErr = null;
        // Слой 1: api.fxtwitter.com (прямые MP4 без авторизации + мульти-фото + метаданные)
        try {
            final Resolved r1 = resolveFxTwitter(tweetId, maxVideoHeight);
            if (r1 != null) return r1;
        } catch (Exception e) {
            lastErr = e;
        }
        // Слой 2: api.vxtwitter.com (резервное зеркало FixTweet/BetterTwitFix)
        try {
            final Resolved r2 = resolveVxTwitter(tweetId);
            if (r2 != null) return r2;
        } catch (Exception e) {
            lastErr = e;
        }
        // Слой 3: Официальный cdn.syndication.twimg.com/tweet-result с математическим токеном
        try {
            final Resolved r3 = resolveTwitterSyndication(tweetId, maxVideoHeight);
            if (r3 != null) return r3;
        } catch (Exception e) {
            lastErr = e;
        }
        throw lastErr != null ? lastErr : new Exception("twitter resolve failed");
    }

    private static Resolved resolveFxTwitter(String tweetId, int maxVideoHeight) throws Exception {
        final String body = httpGet("https://api.fxtwitter.com/i/status/" + tweetId,
                "Pengram/1.0 (+https://github.com)", null, "https://x.com/");
        final JSONObject root = new JSONObject(body);
        final JSONObject tweet = root.optJSONObject("tweet");
        if (tweet == null) return null;
        final JSONObject authorObj = tweet.optJSONObject("author");
        final String author = authorObj != null ? authorObj.optString("screen_name", authorObj.optString("name", "")) : "";
        final String title = tweet.optString("text", "");
        final JSONObject media = tweet.optJSONObject("media");
        if (media == null) return null;

        final JSONArray videos = media.optJSONArray("videos");
        if (videos != null && videos.length() > 0) {
            final JSONObject v = videos.getJSONObject(0);
            String bestUrl = cleanTwitterMp4(v.optString("url", ""));
            final JSONArray formats = v.optJSONArray("formats");
            if (formats != null && formats.length() > 0) {
                String cand = null;
                int candBitrate = -1;
                for (int i = 0; i < formats.length(); i++) {
                    final JSONObject f = formats.optJSONObject(i);
                    if (f == null) continue;
                    final String cType = f.optString("container", f.optString("content_type", "")).toLowerCase(Locale.US);
                    if (!cType.contains("mp4")) continue;
                    final String fu = cleanTwitterMp4(f.optString("url", ""));
                    if (fu.isEmpty()) continue;
                    final int h = extractHeightFromTwitterUrl(fu);
                    final int br = f.optInt("bitrate", 0);
                    if ((h <= 0 || h <= maxVideoHeight) && br >= candBitrate) {
                        cand = fu;
                        candBitrate = br;
                    }
                }
                if (cand != null) bestUrl = cand;
            }
            if (!bestUrl.isEmpty()) {
                final long durMs = (long) (v.optDouble("duration", 0) * 1000L);
                if (durMs > MAX_DURATION_SEC * 1000L) throw new Exception("too long");
                final String thumb = cleanGenericUrl(v.optString("thumbnail_url", ""));
                return Resolved.forVideo(PLATFORM_TWITTER, Collections.singletonList(bestUrl), false,
                        title, author, durMs, thumb, null, "https://x.com/", UA_DESKTOP, false);
            }
        }

        final JSONArray photosJson = media.optJSONArray("photos");
        if (photosJson != null && photosJson.length() > 0) {
            final List<PhotoItem> photos = new ArrayList<>();
            for (int i = 0; i < photosJson.length(); i++) {
                final JSONObject p = photosJson.optJSONObject(i);
                if (p == null) continue;
                final String u = cleanGenericUrl(p.optString("url", ""));
                if (!u.isEmpty()) {
                    final String orig = u.contains("?") ? u : (u + "?name=orig");
                    photos.add(new PhotoItem(orig, u, p.optInt("width", 0), p.optInt("height", 0)));
                }
            }
            if (!photos.isEmpty()) {
                return Resolved.forPhotos(PLATFORM_TWITTER, photos, "", title, author,
                        null, "https://x.com/", UA_DESKTOP);
            }
        }
        return null;
    }

    private static Resolved resolveVxTwitter(String tweetId) throws Exception {
        final String body = httpGet("https://api.vxtwitter.com/Twitter/status/" + tweetId,
                "Pengram/1.0 (+https://github.com)", null, "https://x.com/");
        final JSONObject root = new JSONObject(body);
        final String author = root.optString("user_screen_name", root.optString("user_name", ""));
        final String title = root.optString("text", "");
        final JSONArray ext = root.optJSONArray("media_extended");
        if (ext == null || ext.length() == 0) return null;
        final List<PhotoItem> photos = new ArrayList<>();
        for (int i = 0; i < ext.length(); i++) {
            final JSONObject m = ext.optJSONObject(i);
            if (m == null) continue;
            final String type = m.optString("type", "");
            final String u = cleanGenericUrl(m.optString("url", ""));
            if (u.isEmpty()) continue;
            if ("video".equalsIgnoreCase(type) || "gif".equalsIgnoreCase(type)) {
                return Resolved.forVideo(PLATFORM_TWITTER, Collections.singletonList(cleanTwitterMp4(u)), false,
                        title, author, m.optLong("duration_millis", 0L),
                        cleanGenericUrl(m.optString("thumbnail_url", "")),
                        null, "https://x.com/", UA_DESKTOP, false);
            } else if ("image".equalsIgnoreCase(type)) {
                final JSONObject sz = m.optJSONObject("size");
                photos.add(new PhotoItem(u, u, sz != null ? sz.optInt("width", 0) : 0,
                        sz != null ? sz.optInt("height", 0) : 0));
            }
        }
        if (!photos.isEmpty()) {
            return Resolved.forPhotos(PLATFORM_TWITTER, photos, "", title, author,
                    null, "https://x.com/", UA_DESKTOP);
        }
        return null;
    }

    private static Resolved resolveTwitterSyndication(String tweetId, int maxVideoHeight) throws Exception {
        final String token = computeTwitterSyndicationToken(tweetId);
        final String url = "https://cdn.syndication.twimg.com/tweet-result?id=" + tweetId + "&token=" + token + "&lang=en";
        final String body = httpGet(url, UA_DESKTOP, null, "https://platform.twitter.com/");
        final JSONObject root = new JSONObject(body);
        final JSONObject user = root.optJSONObject("user");
        final String author = user != null ? user.optString("screen_name", "") : "";
        final String title = root.optString("text", "");

        final JSONObject video = root.optJSONObject("video");
        if (video != null) {
            final JSONArray variants = video.optJSONArray("variants");
            String best = null;
            int bestH = -1;
            String fallback = null;
            if (variants != null) {
                for (int i = 0; i < variants.length(); i++) {
                    final JSONObject v = variants.optJSONObject(i);
                    if (v == null) continue;
                    final String type = v.optString("type", "");
                    if (!type.contains("mp4")) continue;
                    final String vu = cleanTwitterMp4(v.optString("src", ""));
                    if (vu.isEmpty()) continue;
                    if (fallback == null) fallback = vu;
                    final int h = extractHeightFromTwitterUrl(vu);
                    if ((h <= 0 || h <= maxVideoHeight) && h >= bestH) {
                        best = vu;
                        bestH = h;
                    }
                }
            }
            final String chosen = best != null ? best : fallback;
            if (chosen != null) {
                return Resolved.forVideo(PLATFORM_TWITTER, Collections.singletonList(chosen), false,
                        title, author, video.optLong("durationMs", 0L),
                        cleanGenericUrl(video.optString("poster", "")),
                        null, "https://x.com/", UA_DESKTOP, false);
            }
        }
        final JSONArray photosArr = root.optJSONArray("photos");
        if (photosArr != null && photosArr.length() > 0) {
            final List<PhotoItem> photos = new ArrayList<>();
            for (int i = 0; i < photosArr.length(); i++) {
                final JSONObject p = photosArr.optJSONObject(i);
                if (p == null) continue;
                final String u = cleanGenericUrl(p.optString("url", ""));
                if (!u.isEmpty()) {
                    photos.add(new PhotoItem(u + (u.contains("?") ? "" : "?name=orig"), u,
                            p.optInt("width", 0), p.optInt("height", 0)));
                }
            }
            if (!photos.isEmpty()) {
                return Resolved.forPhotos(PLATFORM_TWITTER, photos, "", title, author,
                        null, "https://x.com/", UA_DESKTOP);
            }
        }
        return null;
    }

    private static String computeTwitterSyndicationToken(String id) {
        try {
            final double val = (Double.parseDouble(id) / 1e15) * Math.PI;
            long intPart = (long) val;
            double frac = val - intPart;
            final String digits = "0123456789abcdefghijklmnopqrstuvwxyz";
            final StringBuilder sb = new StringBuilder();
            if (intPart > 0) {
                while (intPart > 0) {
                    sb.insert(0, digits.charAt((int) (intPart % 36)));
                    intPart /= 36;
                }
            }
            for (int i = 0; i < 10 && frac > 0; i++) {
                frac *= 36.0;
                final int d = (int) frac;
                if (d != 0) sb.append(digits.charAt(d));
                frac -= d;
            }
            return sb.length() > 0 ? sb.toString() : "a";
        } catch (Throwable ignore) {
            return "a";
        }
    }

    private static String cleanTwitterMp4(String url) {
        final String u = cleanGenericUrl(url);
        final int q = u.indexOf("?tag=");
        return q > 0 ? u.substring(0, q) : u;
    }

    private static int extractHeightFromTwitterUrl(String url) {
        final Matcher m = Pattern.compile("/(\\d{2,4})x(\\d{2,4})/").matcher(url);
        if (m.find()) {
            return Math.min(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return 0;
    }

    // ---------------------------------------------------------------- Pinterest (оригиналы пинов и видео без водяных знаков)

    private static Resolved resolvePinterest(String rawUrl, int maxVideoHeight) throws Exception {
        String url = rawUrl;
        final Map<String, String> cookies = new HashMap<>();
        final Matcher shortM = Pattern.compile("(?i)pin\\.it/([A-Za-z0-9_-]{5,20})").matcher(url);
        if (shortM.find()) {
            try {
                url = followRedirects("https://api.pinterest.com/url_shortener/" + shortM.group(1) + "/redirect/",
                        cookies, UA_DESKTOP);
            } catch (Throwable ignore) {
                url = followRedirects(url, cookies, UA_DESKTOP);
            }
        }
        final Matcher idM = Pattern.compile("(?i)/pin/(?:[^/?#]*--)?(\\d{6,25})").matcher(url);
        if (!idM.find()) {
            throw new Exception("bad pinterest url");
        }
        final String pinId = idM.group(1);
        final String html = httpGet("https://www.pinterest.com/pin/" + pinId + "/", UA_DESKTOP, cookies, "https://www.pinterest.com/");
        if (html.contains("\"__typename\":\"PinNotFound\"")) {
            throw new Exception("pin not found");
        }
        String title = "";
        final Matcher tm = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"").matcher(html);
        if (tm.find()) title = htmlUnescape(tm.group(1));

        // Ищем прямые прогрессивные MP4 (без водяного знака TikTok-стиля) на v1.pinimg.com
        final Matcher vm = Pattern.compile("https://v1\\.pinimg\\.com/videos/[^\"'\\s\\\\]+\\.mp4").matcher(html);
        String bestMp4 = null;
        int bestScore = -1;
        while (vm.find()) {
            final String cand = cleanGenericUrl(vm.group());
            if (cand.isEmpty()) continue;
            int score = 10;
            if (cand.contains("/720p/")) score = maxVideoHeight >= 720 ? 30 : 25;
            else if (cand.contains("/mc/")) score = 20;
            else if (cand.contains("expMp4")) score = 28;
            if (score > bestScore) {
                bestMp4 = cand;
                bestScore = score;
            }
        }
        String origImg = "";
        final Matcher im = Pattern.compile("https://i\\.pinimg\\.com/originals/[0-9a-f/]+\\.(?:jpg|jpeg|png|webp)").matcher(html);
        if (im.find()) {
            origImg = cleanGenericUrl(im.group());
        } else {
            final Matcher im736 = Pattern.compile("https://i\\.pinimg\\.com/736x/[0-9a-f/]+\\.(?:jpg|jpeg|png|webp)").matcher(html);
            if (im736.find()) {
                origImg = cleanGenericUrl(im736.group());
            }
        }

        if (bestMp4 != null) {
            return Resolved.forVideo(PLATFORM_PINTEREST, Collections.singletonList(bestMp4), false,
                    title, "", 0L, origImg, cookies, "https://www.pinterest.com/", UA_DESKTOP, false);
        }
        // Резерв: HLS-поток видео-пина (.m3u8)
        final Matcher hm = Pattern.compile("https://v1\\.pinimg\\.com/videos/[^\"'\\s\\\\]+\\.m3u8").matcher(html);
        if (hm.find()) {
            final String m3u8 = cleanGenericUrl(hm.group());
            if (!m3u8.isEmpty()) {
                return Resolved.forVideoWithAudio(PLATFORM_PINTEREST, Collections.singletonList(m3u8), "",
                        false, true, title, "", 0L, origImg, cookies, "https://www.pinterest.com/", UA_DESKTOP, false);
            }
        }
        if (!origImg.isEmpty()) {
            return Resolved.forPhotos(PLATFORM_PINTEREST,
                    Collections.singletonList(new PhotoItem(origImg, origImg, 0, 0)),
                    "", title, "", cookies, "https://www.pinterest.com/", UA_DESKTOP);
        }
        throw new Exception("pinterest empty");
    }

    // ---------------------------------------------------------------- VK Клипы и VK Видео

    private static final String UA_VK_VIDEO =
            "com.vk.vkvideo.prod/1955 (iPhone, iOS 16.7.15, iPhone10,4, Scale/2.0) SAK/1.135";

    private static Resolved resolveVk(String rawUrl, int maxVideoHeight) throws Exception {
        final Matcher m = Pattern.compile("(?i)(?:clip|video)(-?\\d+)_(\\d+)(?:_([A-Za-z0-9]+))?").matcher(rawUrl);
        if (!m.find()) {
            throw new Exception("bad vk url");
        }
        final String oid = m.group(1);
        final String vid = m.group(2);
        String accessKey = m.group(3) != null ? m.group(3) : "";
        if (accessKey.isEmpty()) {
            final Matcher ak = Pattern.compile("[?&]access_key=([A-Za-z0-9]+)").matcher(rawUrl);
            if (ak.find()) accessKey = ak.group(1);
        }

        Exception lastErr = null;
        try {
            final Resolved r1 = resolveVkVideoApi(oid, vid, accessKey, maxVideoHeight);
            if (r1 != null) return r1;
        } catch (Exception e) {
            lastErr = e;
        }
        try {
            final Resolved r2 = resolveVkEmbedPage(oid, vid, accessKey, maxVideoHeight);
            if (r2 != null) return r2;
        } catch (Exception e) {
            lastErr = e;
        }
        throw lastErr != null ? lastErr : new Exception("vk resolve failed");
    }

    private static String[] getVkAnonToken() throws Exception {
        final long now = System.currentTimeMillis();
        if (cachedVkAnonToken != null && cachedVkDeviceId != null && now - cachedVkTokenTimeMs < 45L * 60_000L) {
            return new String[]{cachedVkAnonToken, cachedVkDeviceId};
        }
        final String deviceId = UUID.randomUUID().toString();
        final String tokenUrl = "https://api.vk.ru/method/auth.getAnonymToken"
                + "?client_id=51552953&client_secret=qgr0yWwXCrsxA1jnRtRX"
                + "&device_id=" + URLEncoder.encode(deviceId, "UTF-8") + "&v=5.274";
        final String resp = httpGet(tokenUrl, UA_VK_VIDEO, null, "https://vkvideo.ru/");
        final JSONObject data = new JSONObject(resp).optJSONObject("response");
        final String token = data != null ? data.optString("token", "") : "";
        if (token.isEmpty()) {
            throw new Exception("vk anon token failed");
        }
        cachedVkAnonToken = token;
        cachedVkDeviceId = deviceId;
        cachedVkTokenTimeMs = now;
        return new String[]{token, deviceId};
    }

    private static Resolved resolveVkVideoApi(String oid, String vid, String accessKey, int maxVideoHeight) throws Exception {
        final String[] auth = getVkAnonToken();
        final String videoSpec = oid + "_" + vid + (accessKey.isEmpty() ? "" : "_" + accessKey);
        final String form = "anonymous_token=" + URLEncoder.encode(auth[0], "UTF-8")
                + "&device_id=" + URLEncoder.encode(auth[1], "UTF-8")
                + "&v=5.274&extended=1&videos=" + URLEncoder.encode(videoSpec, "UTF-8");
        final Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        final String json = httpPost("https://api.vkvideo.ru/method/video.get", form,
                UA_VK_VIDEO, null, "https://vkvideo.ru/", headers);
        final JSONObject resp = new JSONObject(json).optJSONObject("response");
        final JSONArray items = resp != null ? resp.optJSONArray("items") : null;
        if (items == null || items.length() == 0) {
            return null;
        }
        final JSONObject item = items.getJSONObject(0);
        if (item.optInt("live", 0) == 1) {
            throw new Exception("live");
        }
        final long durSec = item.optLong("duration", 0L);
        if (durSec > MAX_DURATION_SEC) {
            throw new Exception("too long");
        }
        final String title = item.optString("title", "");
        String author = "";
        final long ownerLong = item.optLong("owner_id", 0L);
        if (ownerLong < 0 && resp.optJSONArray("groups") != null) {
            final JSONArray groups = resp.optJSONArray("groups");
            for (int i = 0; i < groups.length(); i++) {
                final JSONObject g = groups.optJSONObject(i);
                if (g != null && g.optLong("id") == -ownerLong) {
                    author = g.optString("name", "");
                    break;
                }
            }
        } else if (ownerLong > 0 && resp.optJSONArray("profiles") != null) {
            final JSONArray profs = resp.optJSONArray("profiles");
            for (int i = 0; i < profs.length(); i++) {
                final JSONObject p = profs.optJSONObject(i);
                if (p != null && p.optLong("id") == ownerLong) {
                    author = (p.optString("first_name", "") + " " + p.optString("last_name", "")).trim();
                    break;
                }
            }
        }
        String thumb = "";
        final JSONArray imgs = item.optJSONArray("image");
        if (imgs != null && imgs.length() > 0) {
            final JSONObject last = imgs.optJSONObject(imgs.length() - 1);
            if (last != null) thumb = cleanGenericUrl(last.optString("url", ""));
        }

        final JSONObject files = item.optJSONObject("files");
        if (files == null) return null;
        final int[] qualities = {2160, 1440, 1080, 720, 480, 360, 240, 144};
        String chosenMp4 = null;
        String fallbackMp4 = null;
        for (int q : qualities) {
            final String u = cleanGenericUrl(files.optString("mp4_" + q, ""));
            if (u.isEmpty()) continue;
            if (fallbackMp4 == null || q < maxVideoHeight) fallbackMp4 = u;
            if (q <= maxVideoHeight && chosenMp4 == null) {
                chosenMp4 = u;
                break;
            }
        }
        if (chosenMp4 == null) chosenMp4 = fallbackMp4;
        if (chosenMp4 != null && !chosenMp4.isEmpty()) {
            return Resolved.forVideo(PLATFORM_VK, Collections.singletonList(chosenMp4), false,
                    title, author, durSec * 1000L, thumb, null, "https://vkvideo.ru/", UA_VK_VIDEO, false);
        }
        final String hls = cleanGenericUrl(files.optString("hls", ""));
        if (!hls.isEmpty()) {
            return Resolved.forVideoWithAudio(PLATFORM_VK, Collections.singletonList(hls), "",
                    false, true, title, author, durSec * 1000L, thumb,
                    null, "https://vkvideo.ru/", UA_VK_VIDEO, false);
        }
        return null;
    }

    private static Resolved resolveVkEmbedPage(String oid, String vid, String accessKey, int maxVideoHeight) throws Exception {
        final String extUrl = "https://vk.com/video_ext.php?oid=" + oid + "&id=" + vid
                + (accessKey.isEmpty() ? "" : "&hash=" + accessKey);
        final Map<String, String> cookies = new HashMap<>();
        final String html = httpGet(extUrl, UA_DESKTOP, cookies, "https://vk.com/");
        final int[] qualities = {2160, 1440, 1080, 720, 480, 360, 240};
        String chosen = null;
        String fallback = null;
        for (int q : qualities) {
            final Matcher m = Pattern.compile("\"url" + q + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"").matcher(html);
            if (m.find()) {
                final String u = cleanGenericUrl(unescapeJsonString(m.group(1)));
                if (u.isEmpty()) continue;
                fallback = u;
                if (q <= maxVideoHeight && chosen == null) {
                    chosen = u;
                    break;
                }
            }
        }
        if (chosen == null) chosen = fallback;
        if (chosen != null) {
            return Resolved.forVideo(PLATFORM_VK, Collections.singletonList(chosen), false,
                    "", "", 0L, "", cookies, "https://vk.com/", UA_DESKTOP, false);
        }
        return null;
    }

    // ---------------------------------------------------------------- Rutube, Rutube Shorts и Yappy

    private static Resolved resolveRutube(String rawUrl, int maxVideoHeight) throws Exception {
        final Matcher ym = Pattern.compile("(?i)rutube\\.ru/yappy/([a-f0-9]{16,40})").matcher(rawUrl);
        if (ym.find()) {
            final String yid = ym.group(1);
            final String json = httpGet("https://rutube.ru/pangolin/api/web/yappy/v4/yappypage/?client=wdp&videoId="
                    + yid + "&page=1&page_size=1", UA_DESKTOP, null, "https://rutube.ru/");
            final JSONArray results = new JSONObject(json).optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    final JSONObject r = results.optJSONObject(i);
                    if (r == null) continue;
                    final String link = cleanGenericUrl(r.optString("link", ""));
                    if (!link.isEmpty()) {
                        return Resolved.forVideoWithAudio(PLATFORM_RUTUBE, Collections.singletonList(link), "",
                                false, link.contains(".m3u8"), r.optString("description", ""), "",
                                0L, cleanGenericUrl(r.optString("preview", "")),
                                null, "https://rutube.ru/", UA_DESKTOP, false);
                    }
                }
            }
            throw new Exception("yappy empty");
        }

        final Matcher m = Pattern.compile("(?i)rutube\\.ru/(?:shorts/|video/(?:private/)?|play/embed/)([a-f0-9]{16,40})").matcher(rawUrl);
        if (!m.find()) {
            throw new Exception("bad rutube url");
        }
        final String videoId = m.group(1);
        String pKey = "";
        final Matcher pm = Pattern.compile("[?&]p=([A-Za-z0-9_-]+)").matcher(rawUrl);
        if (pm.find()) pKey = pm.group(1);

        final String apiUrl = "https://rutube.ru/api/play/options/" + videoId
                + "/?no_404=true&referer=https%3A%2F%2Frutube.ru&pver=v2"
                + (pKey.isEmpty() ? "" : "&p=" + URLEncoder.encode(pKey, "UTF-8"));
        final String body = httpGet(apiUrl, UA_DESKTOP, null, "https://rutube.ru/");
        final JSONObject play = new JSONObject(body);
        if (play.optJSONObject("live_streams") != null && play.optJSONObject("live_streams").has("hls")) {
            throw new Exception("live");
        }
        final long durMs = play.optLong("duration", 0L);
        if (durMs > MAX_DURATION_SEC * 1000L) {
            throw new Exception("too long");
        }
        final String title = play.optString("title", "").trim();
        final JSONObject authorObj = play.optJSONObject("author");
        final String author = authorObj != null ? authorObj.optString("name", "").trim() : "";
        final String thumb = cleanGenericUrl(play.optString("thumbnail_url", ""));

        final JSONObject balancer = play.optJSONObject("video_balancer");
        if (balancer == null) {
            throw new Exception("no rutube balancer");
        }
        String m3u8Url = cleanGenericUrl(balancer.optString("m3u8", ""));
        if (m3u8Url.isEmpty()) {
            m3u8Url = cleanGenericUrl(balancer.optString("default", ""));
        }
        if (m3u8Url.isEmpty()) {
            throw new Exception("empty rutube stream");
        }
        final String master = httpGet(m3u8Url, UA_DESKTOP, null, "https://rutube.ru/");
        final String chosenVariant = master.contains("#EXT-X-STREAM-INF")
                ? selectBestHlsVariant(master, m3u8Url, maxVideoHeight)
                : m3u8Url;
        final String finalStream = (chosenVariant != null && !chosenVariant.isEmpty()) ? chosenVariant : m3u8Url;
        return Resolved.forVideoWithAudio(PLATFORM_RUTUBE, Collections.singletonList(finalStream), "",
                false, true, title, author, durMs, thumb,
                null, "https://rutube.ru/", UA_DESKTOP, false);
    }

    // ---------------------------------------------------------------- Reddit (видео со звуком + галереи)

    private static final String UA_REDDIT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 Pengram/1.0";

    private static Resolved resolveReddit(String rawUrl, int maxVideoHeight) throws Exception {
        String url = rawUrl;
        final Map<String, String> cookies = new HashMap<>();
        if (url.contains("/s/") || url.toLowerCase(Locale.US).contains("redd.it/")) {
            url = followRedirects(url, cookies, UA_REDDIT);
        }
        final Matcher cm = Pattern.compile("(?i)/comments/([A-Za-z0-9]{5,12})").matcher(url);
        if (!cm.find()) {
            throw new Exception("bad reddit url");
        }
        final String postId = cm.group(1);
        String json = null;
        final String[] endpoints = {
                "https://www.reddit.com/comments/" + postId + ".json?raw_json=1",
                "https://old.reddit.com/comments/" + postId + ".json?raw_json=1",
                "https://api.reddit.com/api/info/?id=t3_" + postId + "&raw_json=1"
        };
        Exception lastErr = null;
        for (String ep : endpoints) {
            try {
                json = httpGet(ep, UA_REDDIT, cookies, "https://www.reddit.com/");
                if (json != null && (json.trim().startsWith("[") || json.trim().startsWith("{"))) {
                    break;
                }
            } catch (Exception e) {
                lastErr = e;
            }
        }
        if (json == null) {
            throw lastErr != null ? lastErr : new Exception("reddit fetch failed");
        }
        JSONObject post;
        final String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            post = new JSONArray(trimmed).getJSONObject(0)
                    .getJSONObject("data").getJSONArray("children")
                    .getJSONObject(0).getJSONObject("data");
        } else {
            post = new JSONObject(trimmed).getJSONObject("data")
                    .getJSONArray("children").getJSONObject(0).getJSONObject("data");
        }
        final JSONArray crossposts = post.optJSONArray("crosspost_parent_list");
        if (crossposts != null && crossposts.length() > 0 && crossposts.optJSONObject(0) != null) {
            post = crossposts.getJSONObject(0);
        }
        final String title = post.optString("title", "");
        final String author = post.optString("author", "");
        String thumb = cleanGenericUrl(post.optString("thumbnail", ""));
        if (!thumb.startsWith("http")) thumb = "";

        // 1. Галерея Reddit (gallery_data + media_metadata)
        final JSONObject mediaMeta = post.optJSONObject("media_metadata");
        if (mediaMeta != null) {
            final List<PhotoItem> photos = new ArrayList<>();
            final JSONObject galleryData = post.optJSONObject("gallery_data");
            final JSONArray items = galleryData != null ? galleryData.optJSONArray("items") : null;
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    final JSONObject it = items.optJSONObject(i);
                    final String mid = it != null ? it.optString("media_id", "") : "";
                    final PhotoItem p = parseRedditGalleryItem(mediaMeta.optJSONObject(mid));
                    if (p != null) photos.add(p);
                }
            } else {
                final Iterator<String> keys = mediaMeta.keys();
                while (keys.hasNext()) {
                    final PhotoItem p = parseRedditGalleryItem(mediaMeta.optJSONObject(keys.next()));
                    if (p != null) photos.add(p);
                }
            }
            if (!photos.isEmpty()) {
                return Resolved.forPhotos(PLATFORM_REDDIT, photos, "", title, author,
                        cookies, "https://www.reddit.com/", UA_REDDIT);
            }
        }

        // 2. Видео Reddit (v.redd.it: DASH видео + отдельная звуковая дорожка)
        JSONObject rv = null;
        if (post.optJSONObject("secure_media") != null) {
            rv = post.optJSONObject("secure_media").optJSONObject("reddit_video");
        }
        if (rv == null && post.optJSONObject("media") != null) {
            rv = post.optJSONObject("media").optJSONObject("reddit_video");
        }
        if (rv == null && post.optJSONObject("preview") != null) {
            rv = post.optJSONObject("preview").optJSONObject("reddit_video_preview");
        }
        if (rv != null) {
            final long durSec = rv.optLong("duration", 0L);
            if (durSec > MAX_DURATION_SEC) {
                throw new Exception("too long");
            }
            String fallbackUrl = cleanGenericUrl(rv.optString("fallback_url", ""));
            final int qIdx = fallbackUrl.indexOf('?');
            if (qIdx > 0) fallbackUrl = fallbackUrl.substring(0, qIdx);

            if (!fallbackUrl.isEmpty()) {
                final int slash = fallbackUrl.lastIndexOf('/');
                final String baseUrl = slash > 0 ? fallbackUrl.substring(0, slash) : "";
                String videoUrl = fallbackUrl;
                final int origH = rv.optInt("height", 0);
                if (origH > maxVideoHeight && !baseUrl.isEmpty()) {
                    final int[] dashHeights = {1080, 720, 480, 360};
                    for (int dh : dashHeights) {
                        if (dh <= maxVideoHeight && probeHttpOk(baseUrl + "/DASH_" + dh + ".mp4", UA_REDDIT)) {
                            videoUrl = baseUrl + "/DASH_" + dh + ".mp4";
                            break;
                        }
                    }
                }
                String audioUrl = "";
                if (!rv.optBoolean("is_gif", false) && !baseUrl.isEmpty()) {
                    final String[] audioSuffixes = {
                            "/DASH_AUDIO_128.mp4", "/DASH_AUDIO_64.mp4", "/DASH_audio.mp4",
                            "/CMAF_AUDIO_128.mp4", "/CMAF_AUDIO_64.mp4", "/audio"
                    };
                    for (String suf : audioSuffixes) {
                        final String cand = baseUrl + suf;
                        if (probeHttpOk(cand, UA_REDDIT)) {
                            audioUrl = cand;
                            break;
                        }
                    }
                }
                if (!audioUrl.isEmpty()) {
                    return Resolved.forVideoWithAudio(PLATFORM_REDDIT, Arrays.asList(videoUrl, audioUrl),
                            audioUrl, true, false, title, author, durSec * 1000L, thumb,
                            cookies, "https://www.reddit.com/", UA_REDDIT, false);
                }
                return Resolved.forVideo(PLATFORM_REDDIT, Collections.singletonList(videoUrl), false,
                        title, author, durSec * 1000L, thumb, cookies, "https://www.reddit.com/", UA_REDDIT, false);
            }
            final String hlsUrl = cleanGenericUrl(rv.optString("hls_url", ""));
            if (!hlsUrl.isEmpty()) {
                return Resolved.forVideoWithAudio(PLATFORM_REDDIT, Collections.singletonList(hlsUrl), "",
                        false, true, title, author, durSec * 1000L, thumb,
                        cookies, "https://www.reddit.com/", UA_REDDIT, false);
            }
        }

        // 3. Одиночное фото i.redd.it
        final String dest = cleanGenericUrl(post.optString("url_overridden_by_dest", post.optString("url", "")));
        if (dest.matches("(?i).+\\.(jpg|jpeg|png|webp)(\\?.*)?$")) {
            return Resolved.forPhotos(PLATFORM_REDDIT,
                    Collections.singletonList(new PhotoItem(dest, dest, 0, 0)),
                    "", title, author, cookies, "https://www.reddit.com/", UA_REDDIT);
        }
        throw new Exception("reddit no media");
    }

    private static PhotoItem parseRedditGalleryItem(JSONObject meta) {
        if (meta == null) return null;
        final JSONObject s = meta.optJSONObject("s");
        if (s == null) return null;
        String u = htmlUnescape(cleanGenericUrl(s.optString("u", s.optString("gif", ""))));
        if (u.isEmpty()) return null;
        return new PhotoItem(u, u, s.optInt("x", 0), s.optInt("y", 0));
    }

    // ---------------------------------------------------------------- SoundCloud (трек + обложка)

    private static Resolved resolveSoundCloud(String rawUrl) throws Exception {
        String url = rawUrl;
        final Map<String, String> cookies = new HashMap<>();
        final String lower = url.toLowerCase(Locale.US);
        if (lower.contains("on.soundcloud.com/") || lower.contains("soundcloud.app.goo.gl/")) {
            url = followRedirects(url, cookies, UA_DESKTOP);
        }
        final int q = url.indexOf('?');
        if (q > 0) url = url.substring(0, q);
        url = url.replace("://m.soundcloud.com/", "://soundcloud.com/");

        for (int attempt = 0; attempt < 2; attempt++) {
            final String clientId = getSoundCloudClientId(attempt > 0);
            try {
                final String apiUrl = "https://api-v2.soundcloud.com/resolve?url="
                        + URLEncoder.encode(url, "UTF-8") + "&client_id=" + clientId;
                final String json = httpGet(apiUrl, UA_DESKTOP, cookies, "https://soundcloud.com/");
                final JSONObject track = new JSONObject(json);
                if (!"track".equalsIgnoreCase(track.optString("kind", ""))) {
                    throw new Exception("not a track");
                }
                final long durMs = track.optLong("duration", 0L);
                if (durMs > 25L * 60_000L) {
                    throw new Exception("too long");
                }
                final String title = track.optString("title", "");
                final JSONObject user = track.optJSONObject("user");
                final String author = user != null ? user.optString("username", "") : "";
                String cover = cleanGenericUrl(track.optString("artwork_url", ""));
                if (cover.isEmpty() && user != null) {
                    cover = cleanGenericUrl(user.optString("avatar_url", ""));
                }
                if (!cover.isEmpty()) {
                    cover = cover.replace("-large.", "-t500x500.");
                }
                final String trackAuth = track.optString("track_authorization", "");
                final JSONObject media = track.optJSONObject("media");
                final JSONArray transcodings = media != null ? media.optJSONArray("transcodings") : null;
                if (transcodings == null || transcodings.length() == 0) {
                    throw new Exception("no transcodings");
                }
                JSONObject chosen = null;
                boolean isHls = false;
                for (int i = 0; i < transcodings.length(); i++) {
                    final JSONObject t = transcodings.optJSONObject(i);
                    if (t == null || t.optBoolean("snipped", false)) continue;
                    final JSONObject fmt = t.optJSONObject("format");
                    final String proto = fmt != null ? fmt.optString("protocol", "") : "";
                    final String mime = fmt != null ? fmt.optString("mime_type", "") : "";
                    if (proto.contains("encrypted")) continue;
                    if ("progressive".equalsIgnoreCase(proto) && mime.contains("mpeg")) {
                        chosen = t;
                        isHls = false;
                        break;
                    }
                    if (chosen == null && "hls".equalsIgnoreCase(proto)) {
                        chosen = t;
                        isHls = true;
                    }
                }
                if (chosen == null) {
                    chosen = transcodings.optJSONObject(0);
                    final JSONObject fmt = chosen != null ? chosen.optJSONObject("format") : null;
                    isHls = fmt != null && "hls".equalsIgnoreCase(fmt.optString("protocol", ""));
                }
                if (chosen == null) throw new Exception("no soundcloud stream");
                String infoUrl = chosen.optString("url", "");
                infoUrl += (infoUrl.contains("?") ? "&" : "?") + "client_id=" + clientId;
                if (!trackAuth.isEmpty()) {
                    infoUrl += "&track_authorization=" + URLEncoder.encode(trackAuth, "UTF-8");
                }
                final String streamJson = httpGet(infoUrl, UA_DESKTOP, cookies, "https://soundcloud.com/");
                final String streamUrl = cleanGenericUrl(new JSONObject(streamJson).optString("url", ""));
                if (streamUrl.isEmpty()) throw new Exception("empty soundcloud url");
                return Resolved.forAudio(PLATFORM_SOUNDCLOUD, streamUrl, isHls,
                        title, author, durMs, cover, cookies, "https://soundcloud.com/", UA_DESKTOP);
            } catch (Exception e) {
                if (attempt == 0 && e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("403"))) {
                    continue;
                }
                throw e;
            }
        }
        throw new Exception("soundcloud resolve failed");
    }

    private static String getSoundCloudClientId(boolean forceRefresh) throws Exception {
        final long now = System.currentTimeMillis();
        if (!forceRefresh && cachedSoundCloudClientId != null && now - cachedSoundCloudClientTimeMs < 2L * 3600_000L) {
            return cachedSoundCloudClientId;
        }
        final String home = httpGet("https://soundcloud.com/", UA_DESKTOP, null, "https://soundcloud.com/");
        final Matcher scriptM = Pattern.compile("https://a-v2\\.sndcdn\\.com/assets/[^\"'\\s>]+\\.js").matcher(home);
        final List<String> scripts = new ArrayList<>();
        while (scriptM.find()) {
            scripts.add(scriptM.group());
        }
        // client_id всегда лежит в последних бандлах приложения
        for (int i = scripts.size() - 1; i >= Math.max(0, scripts.size() - 8); i--) {
            try {
                final String js = httpGet(scripts.get(i), UA_DESKTOP, null, "https://soundcloud.com/");
                final Matcher cm = Pattern.compile("client_id\\s*[:=]\\s*\"([A-Za-z0-9]{32})\"").matcher(js);
                if (cm.find()) {
                    cachedSoundCloudClientId = cm.group(1);
                    cachedSoundCloudClientTimeMs = now;
                    return cachedSoundCloudClientId;
                }
            } catch (Throwable ignore) {
            }
        }
        throw new Exception("soundcloud client_id not found");
    }

    // ---------------------------------------------------------------- Яндекс.Музыка (трек / превью + обложка)

    private static Resolved resolveYandexMusic(String rawUrl) throws Exception {
        String albumId = "";
        String trackId = "";
        final Matcher fullM = Pattern.compile("(?i)music\\.yandex\\.[a-z]+/album/(\\d+)/track/(\\d+)").matcher(rawUrl);
        if (fullM.find()) {
            albumId = fullM.group(1);
            trackId = fullM.group(2);
        } else {
            final Matcher tM = Pattern.compile("(?i)/track/(\\d+)").matcher(rawUrl);
            if (tM.find()) trackId = tM.group(1);
        }
        if (trackId.isEmpty()) {
            throw new Exception("bad yandex music url");
        }
        final Map<String, String> cookies = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("X-Retpath-Y", rawUrl);

        final String trackSpec = albumId.isEmpty() ? trackId : (trackId + ":" + albumId);
        final String metaJson = httpGetWithHeaders(
                "https://music.yandex.ru/handlers/track.jsx?track=" + URLEncoder.encode(trackSpec, "UTF-8"),
                UA_DESKTOP, cookies, rawUrl, headers);
        final JSONObject metaRoot = new JSONObject(metaJson);
        final JSONObject track = metaRoot.optJSONObject("track");
        if (track == null) {
            throw new Exception("yandex track meta empty");
        }
        final String title = track.optString("title", "");
        final long durMs = track.optLong("durationMs", 0L);
        if (durMs > 25L * 60_000L) {
            throw new Exception("too long");
        }
        final StringBuilder artistsSb = new StringBuilder();
        final JSONArray artists = track.optJSONArray("artists");
        if (artists != null) {
            for (int i = 0; i < artists.length(); i++) {
                final JSONObject a = artists.optJSONObject(i);
                if (a == null) continue;
                final String name = a.optString("name", "").trim();
                if (!name.isEmpty()) {
                    if (artistsSb.length() > 0) artistsSb.append(", ");
                    artistsSb.append(name);
                }
            }
        }
        if (albumId.isEmpty()) {
            final JSONArray albums = track.optJSONArray("albums");
            if (albums != null && albums.length() > 0 && albums.optJSONObject(0) != null) {
                albumId = String.valueOf(albums.getJSONObject(0).optLong("id", 0L));
                if ("0".equals(albumId)) albumId = "";
            }
        }
        String cover = "";
        final String coverUri = track.optString("coverUri", track.optString("ogImage", ""));
        if (!coverUri.isEmpty()) {
            cover = "https://" + coverUri.replace("%%", "1000x1000");
        }

        // Слой 1: api/v2.1 signed get-mp3 (MD5 подпись как в yt-dlp YandexMusicTrackIE)
        final String dlPathSpec = albumId.isEmpty() ? trackId : (trackId + ":" + albumId);
        final String dlApi = "https://music.yandex.ru/api/v2.1/handlers/track/" + dlPathSpec
                + "/web-album_track-track-track-main/download/m?hq=1";
        try {
            final String dlInfoJson = httpGetWithHeaders(dlApi, UA_DESKTOP, cookies, rawUrl, headers);
            final String srcUrl = cleanGenericUrl(new JSONObject(dlInfoJson).optString("src", ""));
            if (!srcUrl.isEmpty()) {
                final String storageJson = httpGetWithHeaders(
                        srcUrl + (srcUrl.contains("?") ? "&" : "?") + "format=json",
                        UA_DESKTOP, cookies, rawUrl, headers);
                final JSONObject st = new JSONObject(storageJson);
                final String host = st.optString("host", "");
                final String path = st.optString("path", "");
                final String ts = st.optString("ts", "");
                final String s = st.optString("s", "");
                if (!host.isEmpty() && path.length() > 1 && !ts.isEmpty() && !s.isEmpty()) {
                    final String sign = md5Hex("XGRlBW9FXlekgbPrRHuSiA" + path.substring(1) + s);
                    final String mp3Url = "https://" + host + "/get-mp3/" + sign + "/" + ts + path + "?track-id=" + trackId;
                    return Resolved.forAudio(PLATFORM_YANDEX, mp3Url, false,
                            title, artistsSb.toString(), durMs, cover, cookies, "https://music.yandex.ru/", UA_DESKTOP);
                }
            }
        } catch (Throwable ignore) {
        }

        // Слой 2: Публичный превью-поток api.music.yandex.net (работает даже без Plus-подписки)
        final String pubInfo = httpGet("https://api.music.yandex.net/tracks/" + trackId + "/download-info",
                UA_DESKTOP, cookies, "https://music.yandex.ru/");
        final JSONArray resArr = new JSONObject(pubInfo).optJSONArray("result");
        if (resArr != null && resArr.length() > 0) {
            JSONObject best = resArr.getJSONObject(0);
            for (int i = 0; i < resArr.length(); i++) {
                final JSONObject c = resArr.optJSONObject(i);
                if (c != null && "mp3".equalsIgnoreCase(c.optString("codec", ""))
                        && c.optInt("bitrateInKbps", 0) >= best.optInt("bitrateInKbps", 0)) {
                    best = c;
                }
            }
            final String diUrl = cleanGenericUrl(best.optString("downloadInfoUrl", ""));
            if (!diUrl.isEmpty()) {
                final String stJson = httpGet(diUrl + (diUrl.contains("?") ? "&" : "?") + "format=json",
                        UA_DESKTOP, cookies, "https://music.yandex.ru/");
                final JSONObject st = new JSONObject(stJson);
                final String host = st.optString("host", "");
                final String path = st.optString("path", "");
                final String ts = st.optString("ts", "");
                final String s = st.optString("s", "");
                if (!host.isEmpty() && path.length() > 1) {
                    final String sign = md5Hex("XGRlBW9FXlekgbPrRHuSiA" + path.substring(1) + s);
                    final String mp3Url = "https://" + host + "/get-mp3/" + sign + "/" + ts + path;
                    return Resolved.forAudio(PLATFORM_YANDEX, mp3Url, false,
                            title, artistsSb.toString(), durMs, cover, cookies, "https://music.yandex.ru/", UA_DESKTOP);
                }
            }
        }
        throw new Exception("yandex stream unavailable");
    }

    // ---------------------------------------------------------------- Общие HTTP/строковые утилиты экстракторов

    private static String cleanGenericUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim()
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&");
        if (s.startsWith("//")) s = "https:" + s;
        return s.startsWith("http") ? s : "";
    }

    private static String unescapeJsonString(String raw) {
        if (raw == null) return "";
        try {
            return new JSONObject("{\"v\":\"" + raw + "\"}").optString("v", raw);
        } catch (Throwable ignore) {
            return raw.replace("\\/", "/").replace("\\u0026", "&");
        }
    }

    private static String htmlUnescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String md5Hex(String text) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("MD5");
        final byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
        final StringBuilder sb = new StringBuilder(32);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static boolean probeHttpOk(String url, String ua) {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(6_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Range", "bytes=0-1");
            try {
                final int code = conn.getResponseCode();
                return code == 200 || code == 206;
            } finally {
                conn.disconnect();
            }
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static String httpGetWithHeaders(String url, String ua, Map<String, String> cookies,
                                             String referer, Map<String, String> extraHeaders) throws Exception {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            final HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(18_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", ua);
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (cookies != null && !cookies.isEmpty()) {
                applyCookies(conn, cookies);
            }
            try {
                final int code = conn.getResponseCode();
                if (cookies != null) {
                    storeCookies(conn, cookies);
                }
                if (code / 100 == 3) {
                    final String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        current = loc.startsWith("http") ? loc : new URL(new URL(current), loc).toString();
                        continue;
                    }
                }
                if (code / 100 != 2) {
                    throw new Exception("http " + code);
                }
                return readAll(conn);
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    private static String httpPost(String url, String body, String ua, Map<String, String> cookies,
                                   String referer, Map<String, String> extraHeaders) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(18_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", ua);
        if (referer != null && !referer.isEmpty()) {
            conn.setRequestProperty("Referer", referer);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (cookies != null && !cookies.isEmpty()) {
            applyCookies(conn, cookies);
        }
        try {
            final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            final int code = conn.getResponseCode();
            if (cookies != null) {
                storeCookies(conn, cookies);
            }
            if (code / 100 != 2) {
                throw new Exception("http " + code);
            }
            return readAll(conn);
        } finally {
            conn.disconnect();
        }
    }

    public interface Progress {
        void onBytes(long written, long total);
    }

    public static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final String UA_CHROME =
            "Mozilla/5.0 (Linux; Android 11; RMX3263) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36";
}
