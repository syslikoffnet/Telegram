package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Помощник ссылок: превращает ссылку TikTok / YouTube в прямые URL видеофайла.
 *
 * YouTube — InnerTube player API (клиенты ANDROID/IOS/ANDROID_VR: прямые URL без
 * JS-дешифровки и без PO-token, стратегия соответствует yt-dlp 2026). Форматы
 * берутся либо muxed (mp4 с звуком), либо пара «видео+аудио» → склеивается
 * на устройстве через {@link PengramVideoMux}.
 *
 * TikTok — SSR-страница с {@code __UNIVERSAL_DATA_FOR_REHYDRATION__}
 * (itemStruct.video.playAddr / bitrateInfo[].PlayAddr), короткие ссылки
 * vm.tiktok.com разворачиваются редиректом; cookie страницы пересылаются на
 * CDN (без них CDN отвечает 403).
 */
public final class PengramLinkResolver {

    public static final int MAX_DURATION_SEC = 600; // 10 минут

    public static final class Resolved {
        /** Один muxed URL либо два — [видео, аудио]. */
        public final List<String> urls;
        public final boolean needsMux;
        public final String title;
        public final String author;
        public final long durationMs;
        /** Cookie/referer страницы (TikTok CDN требует; для YouTube пусто). */
        public final Map<String, String> cookies;
        public final String referer;

        Resolved(List<String> urls, boolean needsMux, String title, String author, long durationMs,
                 Map<String, String> cookies, String referer) {
            this.urls = urls;
            this.needsMux = needsMux;
            this.title = title;
            this.author = author;
            this.durationMs = durationMs;
            this.cookies = cookies != null ? cookies : new HashMap<>();
            this.referer = referer != null ? referer : "";
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
    // (2000-200A) LS/PS(2028/2029) NNBSP(202F) MMTSP(205F) IDSP(3000) —
    // ровно тот же White_Space-набор, что и при флаге.
    private static final String URL_STOP = "\\s\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000<>()\\[\\]{}\"'";
    private static final Pattern YOUTUBE = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:shorts/|watch\\?(?:.*&)?v=|live/|embed/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{6,20})[^" + URL_STOP + "]*");
    private static final Pattern TIKTOK = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.|vm\\.|vt\\.|m\\.)?tiktok\\.com/"
                    + "(?:@[^" + URL_STOP + "]+/video/\\d+|t/[A-Za-z0-9]+|[A-Za-z0-9]{6,20})[^" + URL_STOP + "]*");

    private PengramLinkResolver() {
    }

    /** Последняя (свежайшая) поддерживаемая ссылка в тексте; null — нет. */
    public static FoundLink findLink(CharSequence text) {
        if (text == null) {
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
                // Последнее вхождение в тексте побеждает.
                if (best == null || m.start() >= best.start) {
                    best = new FoundLink(url, m.start(), m.end());
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
        if (lower.contains("tiktok.com/")) {
            return PLATFORM_TIKTOK;
        }
        return PLATFORM_UNKNOWN;
    }

    /** Полный конвейер: платформа → прямые URL. Бросает Exception с текстом для тоста. */
    public static Resolved resolve(String url) throws Exception {
        final int platform = platformOf(url);
        if (platform == PLATFORM_YOUTUBE) {
            return resolveYouTube(url);
        }
        if (platform == PLATFORM_TIKTOK) {
            return resolveTikTok(url);
        }
        throw new Exception("unsupported");
    }

    // ---------------------------------------------------------------- YouTube

    /**
     * InnerTube player: клиенты без JS-шифрования (yt-dlp default-цепочка 2026:
     * tv требует cipher — пропускаем; ANDROID/IOS/ANDROID_VR отдают прямые URL).
     */
    private static Resolved resolveYouTube(String url) throws Exception {
        final String videoId = extractYouTubeId(url);
        if (videoId == null) {
            throw new Exception("bad url");
        }
        final String[][] clients = {
                // {clientName, clientVersion, userAgent}
                {"ANDROID", "21.02.35", "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip"},
                {"IOS", "21.26.4", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 17_7_2 like Mac OS X;)"},
                {"ANDROID_VR", "1.65.10", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 11) gzip"},
        };
        Exception last = null;
        for (String[] client : clients) {
            try {
                return playerRequest(videoId, client[0], client[1], client[2]);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("youtube failed");
    }

    private static Resolved playerRequest(String videoId, String clientName, String clientVersion, String userAgent)
            throws Exception {
        final JSONObject contextClient = new JSONObject()
                .put("clientName", clientName)
                .put("clientVersion", clientVersion)
                .put("hl", "en")
                .put("gl", "US");
        if (clientName.startsWith("ANDROID")) {
            contextClient.put("androidSdkVersion", 30);
        }
        final JSONObject payload = new JSONObject()
                .put("context", new JSONObject().put("client", contextClient))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true);

        final String body = httpPost(
                "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
                payload.toString(), userAgent, null);
        final JSONObject root = new JSONObject(body);

        final JSONObject status = root.optJSONObject("playabilityStatus");
        final String playability = status != null ? status.optString("status") : "";
        if (!"OK".equals(playability)) {
            throw new Exception("playability " + playability);
        }

        final JSONObject videoDetails = root.optJSONObject("videoDetails");
        final String title = videoDetails != null ? videoDetails.optString("title") : "";
        final String author = videoDetails != null ? videoDetails.optString("author") : "";
        final long lengthSec = videoDetails != null ? videoDetails.optLong("lengthSeconds", 0) : 0;
        if (videoDetails != null && videoDetails.optBoolean("isLiveContent", false)) {
            throw new Exception("live");
        }
        if (lengthSec <= 0 || lengthSec > MAX_DURATION_SEC) {
            throw new Exception("too long");
        }

        final JSONObject streaming = root.optJSONObject("streamingData");
        if (streaming == null) {
            throw new Exception("no streamingData");
        }

        String muxedUrl = null;
        String videoUrl = null;
        String videoMime = null;
        String audioUrl = null;
        String audioMime = null;
        int bestVideoHeight = 0;
        int bestAudioBitrate = 0;

        // formats = прогрессивные (видео+звук в одном потоке).
        final JSONArray formats = new JSONArray();
        append(formats, streaming.optJSONArray("formats"));
        for (int i = 0; i < formats.length(); i++) {
            final JSONObject f = formats.optJSONObject(i);
            if (f == null) {
                continue;
            }
            final String direct = directUrl(f);
            if (direct == null) {
                continue;
            }
            if (muxedUrl == null && f.has("audioQuality") && f.optInt("width", 0) > 0) {
                muxedUrl = direct;
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
                final String mime = f.optString("mimeType");
                final boolean hasVideo = f.optInt("width", 0) > 0;
                final boolean hasAudio = f.has("audioQuality") || mime.contains("audio");
                if (hasVideo && !hasAudio) {
                    final int h = f.optInt("height", 0);
                    if (h <= 0 || h > 1080) {
                        continue;
                    }
                    final boolean mp4 = mime.contains("mp4");
                    final boolean chosenMp4 = videoMime != null && videoMime.contains("mp4");
                    if (videoUrl == null || (mp4 && !chosenMp4) || (mp4 == chosenMp4 && h > bestVideoHeight)) {
                        videoUrl = direct;
                        videoMime = mime;
                        bestVideoHeight = h;
                    }
                } else if (!hasVideo && hasAudio) {
                    final int br = f.optInt("bitrate", 0);
                    final boolean aac = mime.contains("mp4a") || mime.contains("mp4");
                    final boolean chosenAac = audioMime != null && (audioMime.contains("mp4a") || audioMime.contains("mp4"));
                    if (audioUrl == null || (aac && !chosenAac) || (aac == chosenAac && br > bestAudioBitrate)) {
                        audioUrl = direct;
                        audioMime = mime;
                        bestAudioBitrate = br;
                    }
                }
            }
        }

        if (muxedUrl != null) {
            return new Resolved(List.of(muxedUrl), false, title, author, lengthSec * 1000, null, "");
        }
        if (videoUrl != null && audioUrl != null) {
            return new Resolved(List.of(videoUrl, audioUrl), true, title, author, lengthSec * 1000, null, "");
        }
        throw new Exception("no direct formats");
    }

    private static void append(JSONArray dst, JSONArray src) {
        if (src == null) {
            return;
        }
        for (int i = 0; i < src.length(); i++) {
            dst.put(src.opt(i));
        }
    }

    /** Прямой URL есть только у нешифрованных клиентов; cipher — в следующий. */
    private static String directUrl(JSONObject format) {
        final String u = format.optString("url");
        if (u.startsWith("http")) {
            return u;
        }
        return null;
    }

    private static String mimeOf(String urlHint) {
        return urlHint == null ? "" : urlHint;
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
        String canonical = followRedirects(url, cookies, 6);
        if (canonical == null) {
            canonical = url;
        }

        final String page = httpGet(canonical,
                "Mozilla/5.0 (Linux; Android 11; RMX3263) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36",
                cookies, canonical);

        final JSONObject item = extractTikTokItem(page);
        if (item == null) {
            throw new Exception("no item");
        }

        final JSONObject video = item.optJSONObject("video");
        if (video == null) {
            throw new Exception("no video");
        }

        String play = video.optString("playAddr", "");
        if (!play.startsWith("http")) {
            play = "";
        }
        if (play.isEmpty()) {
            final JSONArray bitrateInfo = video.optJSONArray("bitrateInfo");
            if (bitrateInfo != null) {
                for (int i = 0; i < bitrateInfo.length() && play.isEmpty(); i++) {
                    final JSONObject br = bitrateInfo.optJSONObject(i);
                    final JSONObject pa = br != null ? br.optJSONObject("PlayAddr") : null;
                    final JSONArray urls = pa != null ? pa.optJSONArray("UrlList") : null;
                    if (urls != null) {
                        for (int j = 0; j < urls.length(); j++) {
                            final String u = urls.optString(j);
                            if (u != null && u.startsWith("http")) {
                                play = u;
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (play.isEmpty()) {
            final String dl = video.optString("downloadAddr", "");
            if (dl.startsWith("http")) {
                play = dl;
            }
        }
        if (play.isEmpty()) {
            throw new Exception("no playAddr");
        }

        final long durationSec = video.optLong("duration", 0);
        if (durationSec > MAX_DURATION_SEC) {
            throw new Exception("too long");
        }

        final JSONObject author = item.optJSONObject("author");
        final String authorId = author != null ? author.optString("uniqueId") : "";
        final String desc = item.optString("desc", "");
        final String caption = buildTikTokCaption(authorId, desc);

        // Cookie страницы обязательны для CDN.
        return new Resolved(List.of(play), false, caption, authorId,
                durationSec > 0 ? durationSec * 1000 : 0, new HashMap<>(cookies), canonical);
    }

    static JSONObject extractTikTokItem(String page) throws Exception {
        final String marker = "__UNIVERSAL_DATA_FOR_REHYDRATION__";
        final int scriptAt = page.indexOf(marker);
        if (scriptAt >= 0) {
            final int jsonStart = page.indexOf('{', scriptAt);
            final int jsonEnd = page.indexOf('}', jsonStart);
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                // JSON может содержать вложенные скобки — ищем баланс.
                int depth = 0;
                int end = -1;
                for (int i = jsonStart; i < page.length(); i++) {
                    final char c = page.charAt(i);
                    if (c == '{') {
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
                    final JSONObject root = new JSONObject(page.substring(jsonStart, end + 1));
                    final JSONObject scope = root.optJSONObject("__DEFAULT_SCOPE__");
                    final JSONObject detail = scope != null ? scope.optJSONObject("webapp.video-detail") : null;
                    final JSONObject itemInfo = detail != null ? detail.optJSONObject("itemInfo") : null;
                    return itemInfo != null ? itemInfo.optJSONObject("itemStruct") : null;
                }
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

    /** Разворачивает vm/vt.tiktok.com, сохраняя cookie. */
    static String followRedirects(String url, Map<String, String> cookies, int maxHops) throws Exception {
        String current = url;
        for (int hop = 0; hop < maxHops; hop++) {
            final HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36");
            applyCookies(conn, cookies);
            final int code = conn.getResponseCode();
            storeCookies(conn, cookies);
            if (code / 100 == 3) {
                final String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) {
                    break;
                }
                current = location.startsWith("http") ? location : new URL(new URL(current), location).toString();
                continue;
            }
            conn.disconnect();
            break;
        }
        return current;
    }

    // ------------------------------------------------------------------ HTTP

    public static String httpGet(String url, String userAgent, Map<String, String> cookies, String referer)
            throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", userAgent);
        if (referer != null && !referer.isEmpty()) {
            conn.setRequestProperty("Referer", referer);
        }
        if (cookies != null) {
            applyCookies(conn, cookies);
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

    public static String httpPost(String url, String jsonBody, String userAgent, Map<String, String> cookies)
            throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", userAgent);
        if (cookies != null) {
            applyCookies(conn, cookies);
        }
        final byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream os = conn.getOutputStream()) {
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
        final List<String> header = conn.getHeaderFields().get("Set-Cookie");
        if (header == null) {
            return;
        }
        for (String c : header) {
            final int semi = c.indexOf(';');
            final String pair = semi > 0 ? c.substring(0, semi) : c;
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        final InputStream in = conn.getInputStream();
        try {
            final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(8192);
            final char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    /** Скачивает поток в файл; возвращает число байт (или бросает при отмене/лимите). */
    public static long download(String url, java.io.File target, long maxBytes,
                                Map<String, String> cookies, String referer,
                                boolean[] cancel, Progress progress) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA_CHROME);
        if (referer != null && !referer.isEmpty()) {
            conn.setRequestProperty("Referer", referer);
        }
        if (cookies != null && !cookies.isEmpty()) {
            applyCookies(conn, cookies);
        }
        try {
            final int code = conn.getResponseCode();
            if (code / 100 != 2) {
                throw new Exception("http " + code);
            }
            long total = conn.getContentLengthLong();
            long written = 0;
            final InputStream in = conn.getInputStream();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
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
            } finally {
                in.close();
            }
            return written;
        } finally {
            conn.disconnect();
        }
    }

    public interface Progress {
        void onBytes(long written, long total);
    }

    private static final String UA_CHROME =
            "Mozilla/5.0 (Linux; Android 11; RMX3263) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36";
}
