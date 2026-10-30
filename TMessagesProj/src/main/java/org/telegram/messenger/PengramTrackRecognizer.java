package org.telegram.messenger;

import android.media.MediaMetadataRetriever;
import android.text.TextUtils;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pengram: многоуровневое распознавание и обогащение музыкальных треков.
 *
 * 1) Санитайзер PengramMusicMeta (мгновенно,0 мс) — чистит рекламу ботов и номера дорожек.
 * 2) Локальный инспектор файла (MediaMetadataRetriever) — достаёт настоящие ID3v2 / MP4 / FLAC
 *    теги (альбом, год, жанр, точный битрейт) и вычисляет плашку качества (FLAC Lossless,320 kbps).
 * 3) Каталожное распознавание (iTunes Search API + Deezer API с проверкой длительности ±8 сек) —
 *    восстанавливает каноническое написание исполнителя, названия, альбома, года и жанра.
 */
public final class PengramTrackRecognizer {

    public interface Callback {
        void onRecognized(String key, TrackInfo info);
    }

    public static final class TrackInfo {
        public final String artist;
        public final String title;
        public final String album;
        public final String year;
        public final String genre;
        public final String qualityBadge;
        public final String coverUrl;
        public final boolean catalogVerified;
        public final String source;

        public TrackInfo(String artist, String title, String album, String year,
                         String genre, String qualityBadge, String coverUrl, boolean catalogVerified) {
            this.artist = artist == null ? "" : artist;
            this.title = title == null ? "" : title;
            this.album = album == null ? "" : album;
            this.year = year == null ? "" : year;
            this.genre = genre == null ? "" : genre;
            this.qualityBadge = qualityBadge == null ? "" : qualityBadge;
            this.coverUrl = coverUrl;
            this.catalogVerified = catalogVerified;
            this.source = catalogVerified ? "iTunes / Deezer + ID3" : "Local ID3 / Audio";
        }

        public String qualityBadge() {
            return qualityBadge;
        }

        /** Компактная информационная строка под треком: «Meteora · 2003 · Rock · FLAC Lossless». */
        public String buildBadgeLine() {
            final StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(album) && !album.equalsIgnoreCase(title)) {
                sb.append(album.length() > 28 ? album.substring(0, 27).trim() + "…" : album);
            }
            if (!TextUtils.isEmpty(year)) {
                if (sb.length() > 0) sb.append(" \u00b7 ");
                sb.append(year);
            }
            if (!TextUtils.isEmpty(genre) && sb.length() < 34) {
                if (sb.length() > 0) sb.append(" \u00b7 ");
                sb.append(genre);
            }
            if (!TextUtils.isEmpty(qualityBadge)) {
                if (sb.length() > 0) sb.append(" \u00b7 ");
                sb.append(qualityBadge);
            }
            return sb.toString();
        }
    }

    private static final LruCache<String, TrackInfo> CACHE = new LruCache<>(256);
    private static final HashMap<String, ArrayList<Callback>> IN_FLIGHT = new HashMap<>();
    private static final ThreadPoolExecutor POOL;

    static {
        POOL = new ThreadPoolExecutor(
                1, 2, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(64),
                r -> {
                    final Thread t = new Thread(r, "PengramTrackRecognizer");
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        POOL.allowCoreThreadTimeOut(true);
    }

    private PengramTrackRecognizer() {
    }

    public static String keyFor(MessageObject msg) {
        if (msg == null) {
            return null;
        }
        final TLRPC.Document doc = msg.getDocument();
        if (doc != null && doc.id != 0) {
            return "doc:" + doc.id;
        }
        final String[] pair = PengramMusicMeta.resolveDisplayPair(msg);
        if (TextUtils.isEmpty(pair[0]) && TextUtils.isEmpty(pair[1])) {
            return null;
        }
        return "meta:" + normalizeKeyPart(pair[0]) + "|" + normalizeKeyPart(pair[1]);
    }

    public static TrackInfo getCached(MessageObject msg) {
        if (!PengramConfig.isSmartTrackRecognition()) {
            return null;
        }
        final String key = keyFor(msg);
        return key == null ? null : CACHE.get(key);
    }

    public static String buildBadgeLine(TrackInfo info) {
        return info == null ? "" : info.buildBadgeLine();
    }

    /** Мгновенная оценка качества аудио из атрибутов документа без ожидания сети. */
    public static String quickQualityBadge(MessageObject msg) {
        if (msg == null) {
            return "";
        }
        final TLRPC.Document doc = msg.getDocument();
        if (doc == null) {
            return "";
        }
        final String mime = doc.mime_type == null ? "" : doc.mime_type.toLowerCase(Locale.US);
        final String fileName = FileLoader.getDocumentFileName(doc);
        final String lowName = fileName == null ? "" : fileName.toLowerCase(Locale.US);

        String codec = "";
        boolean lossless = false;
        if (mime.contains("flac") || lowName.endsWith(".flac")) {
            codec = "FLAC";
            lossless = true;
        } else if (lowName.endsWith(".alac")) {
            codec = "ALAC";
            lossless = true;
        } else if (mime.contains("wav") || lowName.endsWith(".wav") || lowName.endsWith(".aiff")) {
            codec = "WAV";
            lossless = true;
        } else if (mime.contains("opus") || lowName.endsWith(".opus")) {
            codec = "Opus";
        } else if (mime.contains("ogg") || lowName.endsWith(".ogg")) {
            codec = "OGG";
        } else if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") || lowName.endsWith(".m4a") || lowName.endsWith(".aac")) {
            codec = "AAC";
        } else if (mime.contains("mpeg") || mime.contains("mp3") || lowName.endsWith(".mp3")) {
            codec = "MP3";
        }

        if (lossless) {
            return codec + " Lossless";
        }
        final double dur = msg.getDuration();
        if (dur > 3.0 && doc.size > 16_384) {
            final int kbps = (int) Math.round((doc.size * 8.0) / (dur * 1000.0));
            final int snapped = snapBitrate(kbps);
            if (snapped > 0) {
                return TextUtils.isEmpty(codec) ? (snapped + " kbps") : (codec + " " + snapped + " kbps");
            }
        }
        return codec;
    }

    private static int snapBitrate(int kbps) {
        if (kbps <= 24 || kbps > 2500) {
            return 0;
        }
        final int[] standards = {64, 96, 128, 160, 192, 224, 256, 320};
        for (int s : standards) {
            if (Math.abs(kbps - s) <= 18) {
                return s;
            }
        }
        return (kbps / 16) * 16;
    }

    public static void recognize(MessageObject msg, Callback callback) {
        if (!PengramConfig.isSmartTrackRecognition() || msg == null || !msg.isMusic()) {
            if (callback != null) {
                callback.onRecognized(null, null);
            }
            return;
        }
        final String key = keyFor(msg);
        if (key == null) {
            if (callback != null) {
                callback.onRecognized(null, null);
            }
            return;
        }
        final TrackInfo cached = CACHE.get(key);
        if (cached != null) {
            if (callback != null) {
                callback.onRecognized(key, cached);
            }
            return;
        }

        final String[] initialPair = PengramMusicMeta.resolveDisplayPair(msg);
        final String initialBadge = quickQualityBadge(msg);
        final int durationSec = (int) Math.round(msg.getDuration());
        File localFile = null;
        try {
            if (!TextUtils.isEmpty(msg.messageOwner.attachPath)) {
                final File f = new File(msg.messageOwner.attachPath);
                if (f.exists() && f.length() > 0) {
                    localFile = f;
                }
            }
            if (localFile == null) {
                final File f = FileLoader.getInstance(msg.currentAccount).getPathToMessage(msg.messageOwner);
                if (f != null && f.exists() && f.length() > 0) {
                    localFile = f;
                }
            }
        } catch (Throwable ignore) {
        }
        final File finalLocalFile = localFile;

        synchronized (IN_FLIGHT) {
            ArrayList<Callback> list = IN_FLIGHT.get(key);
            if (list != null) {
                if (callback != null) {
                    list.add(callback);
                }
                return;
            }
            list = new ArrayList<>();
            if (callback != null) {
                list.add(callback);
            }
            IN_FLIGHT.put(key, list);
        }

        POOL.execute(() -> {
            final TrackInfo resolved = resolveBackground(initialPair[0], initialPair[1], initialBadge, durationSec, finalLocalFile);
            if (resolved != null) {
                CACHE.put(key, resolved);
            }
            AndroidUtilities.runOnUIThread(() -> {
                final ArrayList<Callback> listeners;
                synchronized (IN_FLIGHT) {
                    listeners = IN_FLIGHT.remove(key);
                }
                if (listeners != null) {
                    for (int i = 0; i < listeners.size(); i++) {
                        listeners.get(i).onRecognized(key, resolved);
                    }
                }
            });
        });
    }

    private static TrackInfo resolveBackground(String baseArtist, String baseTitle,
                                               String baseBadge, int durationSec, File localFile) {
        String artist = baseArtist == null ? "" : baseArtist;
        String title = baseTitle == null ? "" : baseTitle;
        String album = "";
        String year = "";
        String genre = "";
        String badge = baseBadge == null ? "" : baseBadge;

        // Шаг 1: чтение локальных ID3v2 / MP4 / FLAC тегов из скачанного аудиофайла
        if (localFile != null) {
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(localFile.getAbsolutePath());
                final String fArtist = PengramMusicMeta.cleanPerformerTag(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                final String fAlbumArtist = PengramMusicMeta.cleanPerformerTag(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
                final String fTitle = PengramMusicMeta.cleanTitleTag(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
                final String fAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                final String fYear = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
                final String fGenre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
                final String fBitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);

                if (TextUtils.isEmpty(artist)) {
                    artist = !TextUtils.isEmpty(fArtist) ? fArtist : (fAlbumArtist != null ? fAlbumArtist : "");
                }
                if (TextUtils.isEmpty(title) && !TextUtils.isEmpty(fTitle)) {
                    title = fTitle;
                }
                if (!TextUtils.isEmpty(fAlbum)) {
                    final String cleanAlb = PengramMusicMeta.cleanPerformerTag(fAlbum);
                    if (!TextUtils.isEmpty(cleanAlb)) {
                        album = cleanAlb;
                    }
                }
                if (!TextUtils.isEmpty(fYear) && fYear.length() >= 4) {
                    year = extractFourDigitYear(fYear);
                }
                if (!TextUtils.isEmpty(fGenre)) {
                    genre = cleanGenre(fGenre);
                }
                if (!badge.contains("Lossless") && !TextUtils.isEmpty(fBitrate)) {
                    try {
                        final int kbps = snapBitrate(Integer.parseInt(fBitrate.trim()) / 1000);
                        if (kbps > 0 && badge.indexOf(' ') < 0) {
                            badge = TextUtils.isEmpty(badge) ? (kbps + " kbps") : (badge + " " + kbps + " kbps");
                        }
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable ignore) {
            } finally {
                if (mmr != null) {
                    try {
                        mmr.release();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }

        // Шаг 2: каталожное распознавание iTunes Search + Deezer (с проверкой совпадения длительности)
        String coverUrl = null;
        boolean verified = false;
        final String query = buildSearchQuery(artist, title);
        if (!TextUtils.isEmpty(query) && query.length() >= 3) {
            CatalogMatch match = queryItunes(query, artist, title, durationSec);
            if (match == null) {
                match = queryDeezer(query, artist, title, durationSec);
            }
            if (match != null) {
                verified = true;
                if (TextUtils.isEmpty(artist) || artist.length() <= 2) {
                    artist = match.artist;
                }
                if (TextUtils.isEmpty(title) || title.length() <= 2) {
                    title = match.title;
                }
                if (TextUtils.isEmpty(album) && !TextUtils.isEmpty(match.album)) {
                    album = match.album;
                }
                if (TextUtils.isEmpty(year) && !TextUtils.isEmpty(match.year)) {
                    year = match.year;
                }
                if (TextUtils.isEmpty(genre) && !TextUtils.isEmpty(match.genre)) {
                    genre = match.genre;
                }
                coverUrl = match.coverUrl;
            }
        }

        return new TrackInfo(artist, title, album, year, genre, badge, coverUrl, verified);
    }

    private static final class CatalogMatch {
        String artist;
        String title;
        String album;
        String year;
        String genre;
        String coverUrl;
        int score;
    }

    private static String buildSearchQuery(String artist, String title) {
        final String cleanT = stripEditionSuffixForQuery(title);
        if (TextUtils.isEmpty(artist)) {
            return cleanT;
        }
        if (TextUtils.isEmpty(cleanT)) {
            return artist;
        }
        return artist + " " + cleanT;
    }

    private static String stripEditionSuffixForQuery(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        String out = s;
        final String low = out.toLowerCase(Locale.US);
        final String[] markers = {"(slowed", "[slowed", "(speed up", "(sped up", "(nightcore"};
        for (String m : markers) {
            final int idx = low.indexOf(m);
            if (idx > 2) {
                out = out.substring(0, idx).trim();
                break;
            }
        }
        return out;
    }

    private static CatalogMatch queryItunes(String query, String wantArtist, String wantTitle, int durationSec) {
        HttpURLConnection conn = null;
        try {
            final String url = "https://itunes.apple.com/search?media=music&entity=song&limit=6&term="
                    + URLEncoder.encode(query, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setRequestProperty("User-Agent", "Pengram/1.0");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            final JSONObject json = new JSONObject(readBody(conn));
            final JSONArray results = json.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            CatalogMatch best = null;
            for (int i = 0; i < results.length(); i++) {
                final JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                final String rArtist = item.optString("artistName", "").trim();
                final String rTitle = item.optString("trackName", "").trim();
                final int rDurSec = (int) Math.round(item.optLong("trackTimeMillis", 0L) / 1000.0);
                final int sc = scoreCandidate(wantArtist, wantTitle, durationSec, rArtist, rTitle, rDurSec);
                if (sc < 45) {
                    continue;
                }
                if (best == null || sc > best.score) {
                    best = new CatalogMatch();
                    best.score = sc;
                    best.artist = rArtist;
                    best.title = rTitle;
                    best.album = cleanCollectionName(item.optString("collectionName", ""));
                    best.year = extractFourDigitYear(item.optString("releaseDate", ""));
                    best.genre = cleanGenre(item.optString("primaryGenreName", ""));
                    final String art = item.optString("artworkUrl100", "");
                    best.coverUrl = TextUtils.isEmpty(art) ? null : art.replace("100x100bb", "600x600bb");
                }
            }
            return best;
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static CatalogMatch queryDeezer(String query, String wantArtist, String wantTitle, int durationSec) {
        HttpURLConnection conn = null;
        try {
            final String url = "https://api.deezer.com/search?limit=6&q=" + URLEncoder.encode(query, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setRequestProperty("User-Agent", "Pengram/1.0");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            final JSONObject json = new JSONObject(readBody(conn));
            final JSONArray data = json.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return null;
            }
            CatalogMatch best = null;
            for (int i = 0; i < data.length(); i++) {
                final JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                final JSONObject artistObj = item.optJSONObject("artist");
                final JSONObject albumObj = item.optJSONObject("album");
                final String rArtist = artistObj != null ? artistObj.optString("name", "").trim() : "";
                final String rTitle = item.optString("title", "").trim();
                final int rDurSec = item.optInt("duration", 0);
                final int sc = scoreCandidate(wantArtist, wantTitle, durationSec, rArtist, rTitle, rDurSec);
                if (sc < 45) {
                    continue;
                }
                if (best == null || sc > best.score) {
                    best = new CatalogMatch();
                    best.score = sc;
                    best.artist = rArtist;
                    best.title = rTitle;
                    best.album = albumObj != null ? cleanCollectionName(albumObj.optString("title", "")) : "";
                    best.year = "";
                    best.genre = "";
                    best.coverUrl = albumObj != null ? albumObj.optString("cover_xl", albumObj.optString("cover_big", null)) : null;
                }
            }
            return best;
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int scoreCandidate(String wantArtist, String wantTitle, int wantDur,
                                      String candArtist, String candTitle, int candDur) {
        final String nWantT = normalizeKeyPart(stripEditionSuffixForQuery(wantTitle));
        final String nCandT = normalizeKeyPart(stripEditionSuffixForQuery(candTitle));
        if (TextUtils.isEmpty(nCandT)) {
            return 0;
        }
        int score = 0;
        if (!TextUtils.isEmpty(nWantT)) {
            if (nWantT.equals(nCandT)) {
                score += 55;
            } else if (nCandT.contains(nWantT) || nWantT.contains(nCandT)) {
                score += 38;
            } else {
                return 0;
            }
        }
        final String nWantA = normalizeKeyPart(wantArtist);
        final String nCandA = normalizeKeyPart(candArtist);
        if (!TextUtils.isEmpty(nWantA) && !TextUtils.isEmpty(nCandA)) {
            if (nWantA.equals(nCandA)) {
                score += 35;
            } else if (nCandA.contains(nWantA) || nWantA.contains(nCandA)) {
                score += 22;
            } else {
                score -= 15;
            }
        } else {
            score += 12;
        }
        if (wantDur > 15 && candDur > 15) {
            final int diff = Math.abs(wantDur - candDur);
            if (diff <= 4) {
                score += 20;
            } else if (diff <= 10) {
                score += 10;
            } else if (diff > 45) {
                score -= 12;
            }
        }
        return score;
    }

    private static String cleanCollectionName(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String out = s.trim();
        if (out.endsWith(" - Single")) {
            out = out.substring(0, out.length() - 9).trim();
        } else if (out.endsWith(" - EP")) {
            out = out.substring(0, out.length() - 5).trim();
        }
        return out;
    }

    private static String cleanGenre(String g) {
        if (TextUtils.isEmpty(g)) return "";
        String s = g.trim();
        if (s.startsWith("(") && s.endsWith(")")) {
            return "";
        }
        if ("Music".equalsIgnoreCase(s) || "Музыка".equalsIgnoreCase(s)) {
            return "";
        }
        return s.length() > 22 ? s.substring(0, 22).trim() : s;
    }

    private static String extractFourDigitYear(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        for (int i = 0; i <= raw.length() - 4; i++) {
            final char c0 = raw.charAt(i);
            final char c1 = raw.charAt(i + 1);
            final char c2 = raw.charAt(i + 2);
            final char c3 = raw.charAt(i + 3);
            if ((c0 == '1' || c0 == '2') && Character.isDigit(c1) && Character.isDigit(c2) && Character.isDigit(c3)) {
                return raw.substring(i, i + 4);
            }
        }
        return "";
    }

    public static String normalizeKeyPart(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        final String low = s.toLowerCase(Locale.ROOT);
        final StringBuilder sb = new StringBuilder(low.length());
        for (int i = 0; i < low.length(); i++) {
            final char c = low.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        final StringBuilder sb = new StringBuilder(2048);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 131_072) break;
            }
        }
        return sb.toString();
    }
}
