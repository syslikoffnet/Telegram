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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
        public final List<Resolved> fallbacks = new ArrayList<>();

        Resolved(int kind, int platform, List<String> urls, List<PhotoItem> photos,
                 String audioUrl, String thumbnailUrl, boolean needsMux,
                 String title, String author, long durationMs,
                 Map<String, String> cookies, String referer,
                 String userAgent, boolean rangeChunked) {
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
        }

        Resolved(List<String> urls, boolean needsMux, String title, String author, long durationMs,
                 Map<String, String> cookies, String referer) {
            this(KIND_VIDEO, PLATFORM_UNKNOWN, urls, Collections.<PhotoItem>emptyList(),
                    "", "", needsMux, title, author, durationMs, cookies, referer, UA_CHROME, false);
        }

        public boolean isPhotos() {
            return kind == KIND_PHOTOS && !photos.isEmpty();
        }

        static Resolved forVideo(int platform, List<String> urls, boolean needsMux,
                                 String title, String author, long durationMs, String thumbnailUrl,
                                 Map<String, String> cookies, String referer,
                                 String userAgent, boolean rangeChunked) {
            return new Resolved(KIND_VIDEO, platform, urls, Collections.<PhotoItem>emptyList(),
                    "", thumbnailUrl, needsMux, title, author, durationMs,
                    cookies, referer, userAgent, rangeChunked);
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

    private static volatile String cachedYtVisitorData;
    private static volatile long cachedYtVisitorTimeMs;

    private PengramLinkResolver() {
    }

    /** Нулевой по аллокациям префильтр перед запуском тяжёлых URL-регулярок на каждом нажатии клавиши. */
    private static boolean mayContainSupportedLink(CharSequence text) {
        final int n = text.length();
        if (n < 9) {
            return false;
        }
        boolean hasSlash = false;
        boolean hasDomain = false;
        for (int i = 0; i < n; i++) {
            final char c = text.charAt(i);
            if (c == '/') {
                hasSlash = true;
                if (hasDomain) return true;
                continue;
            }
            if (!hasDomain) {
                if ((c == 'y' || c == 'Y') && i + 4 < n
                        && (text.charAt(i + 1) | 32) == 'o'
                        && (text.charAt(i + 2) | 32) == 'u'
                        && (text.charAt(i + 3) | 32) == 't'
                        && (text.charAt(i + 4) | 32) == 'u') {
                    hasDomain = true;
                    if (hasSlash) return true;
                } else if ((c == 't' || c == 'T') && i + 5 < n
                        && (text.charAt(i + 1) | 32) == 'i'
                        && (text.charAt(i + 2) | 32) == 'k'
                        && (text.charAt(i + 3) | 32) == 't'
                        && (text.charAt(i + 4) | 32) == 'o'
                        && (text.charAt(i + 5) | 32) == 'k') {
                    hasDomain = true;
                    if (hasSlash) return true;
                }
            }
        }
        return false;
    }

    /** Последняя (свежайшая) поддерживаемая ссылка в тексте; null — нет. */
    public static FoundLink findLink(CharSequence text) {
        if (text == null || !mayContainSupportedLink(text)) {
            return null;
        }
        final String s = text.toString();
        FoundLink best = null;
        for (Pattern p : new Pattern[]{YOUTUBE, TIKTOK}) {
            Matcher m = p.matcher(s);
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
        return PLATFORM_UNKNOWN;
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

    /** Полный конвейер: платформа → прямые URL видео или фото. */
    public static Resolved resolve(String url) throws Exception {
        final String normalized = (url != null && !url.startsWith("http")) ? "https://" + url : url;
        final int platform = platformOf(normalized);
        if (platform == PLATFORM_YOUTUBE) {
            return resolveYouTube(normalized);
        }
        if (platform == PLATFORM_TIKTOK) {
            return resolveTikTok(normalized);
        }
        throw new Exception("unsupported");
    }

    // ---------------------------------------------------------------- YouTube

    /**
     * InnerTube player: клиенты без JS-шифрования (yt-dlp master окт-2026:
     * VISIONOS — дефолтный no-JS клиент без PO-token; ANDROID / IOS / WEB_EMBEDDED — запасные).
     * ANDROID_VR удалён (403 с 2026-08-17, yt-dlp #17348).
     */
    private static Resolved resolveYouTube(String url) throws Exception {
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
                final Resolved r = playerRequest(videoId, client, visitorData);
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

    private static Resolved playerRequest(String videoId, String[] client, String visitorData) throws Exception {
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
                    if (bestMuxedUrl == null || h > bestMuxedHeight) {
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
                    if (h <= 0 || h > 1080 || !mime.contains("mp4")) {
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

        // Предпочитаем адаптивную пару HD (720p/1080p H.264+AAC), если прогрессивный поток только 360p.
        if (videoUrl != null && audioUrl != null && bestVideoHeight > bestMuxedHeight) {
            return Resolved.forVideo(PLATFORM_YOUTUBE, Arrays.asList(videoUrl, audioUrl), true,
                    title, author, lengthSec * 1000L, thumbUrl, null, "https://www.youtube.com/",
                    userAgent, true);
        }
        if (bestMuxedUrl != null) {
            return Resolved.forVideo(PLATFORM_YOUTUBE, Arrays.asList(bestMuxedUrl), false,
                    title, author, lengthSec * 1000L, thumbUrl, null, "https://www.youtube.com/",
                    userAgent, true);
        }
        if (videoUrl != null && audioUrl != null) {
            return Resolved.forVideo(PLATFORM_YOUTUBE, Arrays.asList(videoUrl, audioUrl), true,
                    title, author, lengthSec * 1000L, thumbUrl, null, "https://www.youtube.com/",
                    userAgent, true);
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
        final String ua = (userAgent != null && !userAgent.isEmpty()) ? userAgent : UA_CHROME;
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

    public interface Progress {
        void onBytes(long written, long total);
    }

    public static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final String UA_CHROME =
            "Mozilla/5.0 (Linux; Android 11; RMX3263) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36";
}
