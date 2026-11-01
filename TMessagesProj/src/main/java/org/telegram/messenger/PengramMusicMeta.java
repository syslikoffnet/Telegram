package org.telegram.messenger;

import android.text.TextUtils;

import java.util.Locale;

/**
 * Pengram: умная очистка тегов и выделение исполнителя/названия из грязных имён файлов и ботов.
 *
 * У многих файлов теги пустые или забиты рекламой ботов («@muz_bot | t.me/hits»),
 * а всё написано в одной строке: «02._Пошлая_Молли_-_Нимфоманка_(320kbps)_[zaycev.net].mp3».
 * Плеер в таком случае показывал мусор или «Неизвестный исполнитель».
 *
 * Здесь аккуратный многоступенчатый разбор: счищаем расширения, номера дорожек, ссылки и мусорные
 * суффиксы сайтов, сохраняя настоящие музыкальные пометки вроде (Remix), (Slowed + Reverb), (Live), (feat. ...).
 */
public final class PengramMusicMeta {

    private static final int MAX_ARTIST = 56;
    private static final int MIN_TITLE = 1;

    private static final String[] EXTENSIONS = {
            ".mp3", ".m4a", ".flac", ".wav", ".ogg", ".opus", ".aac", ".wma", ".alac", ".aiff", ".mp4", ".webm"
    };

    private static final String[] JUNK_DOMAINS = {
            "zaycev.net", "zaycev", "sefon.pro", "sefon.me", "sefon", "muzofond", "muzonov",
            "hitmos", "mp3party", "mp3uk", "muzmo", "drivemusic", "musmore", "lightaudio",
            "promodj", "vk.com/", "t.me/", "telegram.me/", "youtu.be/", "youtube.com/",
            "soundcloud.com/", "spotify.com/", "music.yandex"
    };

    private static final String[] JUNK_BRACKET_TAGS = {
            "official video", "official music video", "official audio", "official lyric video",
            "lyric video", "lyrics video", "lyrics", "audio", "hq", "hd", "4k",
            "320kbps", "320 kbps", "256kbps", "192kbps", "128kbps", "flac", "lossless",
            "скачать", "новинка", "премьера", "без рекламы", "полная версия"
    };

    private PengramMusicMeta() {
    }

    public static boolean enabled() {
        return PengramConfig.isMusicSmartArtist() || PengramConfig.isSmartTrackRecognition();
    }

    /** Очищает тег исполнителя от спама ботов/ссылок; возвращает null, если там был только спам/«<unknown>». */
    public static String cleanPerformerTag(String rawPerformer) {
        if (TextUtils.isEmpty(rawPerformer)) {
            return null;
        }
        String s = normalizeUnderscores(rawPerformer.trim());
        final String lower = s.toLowerCase(Locale.US);
        if ("<unknown>".equals(lower) || "unknown".equals(lower) || "unknown artist".equals(lower)
                || "неизвестный исполнитель".equals(lower) || "неизвестен".equals(lower)
                || "audio".equals(lower) || "track".equals(lower)) {
            return null;
        }
        s = stripBotAndDomainSegments(s);
        s = stripNoiseBrackets(s);
        s = collapseSpaces(s);
        if (!sane(s, 80)) {
            return null;
        }
        return s;
    }

    /** Очищает название трека от расширений, номеров дорожек, рекламных хвостов и мусорных скобок. */
    public static String cleanTitleTag(String rawTitle) {
        if (TextUtils.isEmpty(rawTitle)) {
            return rawTitle;
        }
        String s = rawTitle.trim();
        s = stripExtension(s);
        s = normalizeUnderscores(s);
        s = stripLeadingNumber(s);
        s = stripBotAndDomainSegments(s);
        s = stripNoiseBrackets(s);
        s = collapseSpaces(s);
        return TextUtils.isEmpty(s) ? rawTitle.trim() : s;
    }

    /** имя исполнителя из названия или null, если его там не видно */
    public static String artistFrom(String raw) {
        final String[] parts = split(raw);
        return parts == null ? null : parts[0];
    }

    /** название без приклеенного спереди исполнителя (или очищенная строка) */
    public static String titleFrom(String raw) {
        if (!enabled() || TextUtils.isEmpty(raw)) {
            return raw;
        }
        final String[] parts = split(raw);
        if (parts != null) {
            return parts[1];
        }
        return PengramConfig.isSmartTrackRecognition() ? cleanTitleTag(raw) : raw;
    }

    /** Полная пара {artist, title} для любого MessageObject с учётом грязных тегов и имени файла. */
    public static String[] resolveDisplayPair(MessageObject msg) {
        if (msg == null) {
            return new String[]{"", ""};
        }
        String rawTitle = msg.getMusicTitle(false);
        String rawAuthor = msg.getMusicAuthor(false);
        if (!PengramConfig.isSmartTrackRecognition() && !PengramConfig.isMusicSmartArtist()) {
            return new String[]{
                    rawAuthor == null ? "" : rawAuthor,
                    rawTitle == null ? "" : rawTitle
            };
        }
        String cleanAuthor = cleanPerformerTag(rawAuthor);
        String candidateTitle = cleanTitleTag(rawTitle);
        final String[] splitTitle = splitAny(candidateTitle);
        if (TextUtils.isEmpty(cleanAuthor) && splitTitle != null) {
            cleanAuthor = splitTitle[0];
            candidateTitle = splitTitle[1];
        } else if (!TextUtils.isEmpty(cleanAuthor) && splitTitle != null
                && splitTitle[0].equalsIgnoreCase(cleanAuthor)) {
            candidateTitle = splitTitle[1];
        }
        if (TextUtils.isEmpty(cleanAuthor) && msg.getDocument() != null) {
            final String fileName = FileLoader.getDocumentFileName(msg.getDocument());
            if (!TextUtils.isEmpty(fileName)) {
                final String[] fromFile = splitAny(cleanTitleTag(fileName));
                if (fromFile != null) {
                    cleanAuthor = fromFile[0];
                    if (TextUtils.isEmpty(candidateTitle) || isGenericTrackName(candidateTitle)) {
                        candidateTitle = fromFile[1];
                    }
                }
            }
        }
        if (TextUtils.isEmpty(cleanAuthor)) {
            cleanAuthor = rawAuthor == null ? "" : rawAuthor.trim();
        }
        if (TextUtils.isEmpty(candidateTitle)) {
            candidateTitle = rawTitle == null ? "" : rawTitle.trim();
        }
        return new String[]{cleanAuthor, candidateTitle};
    }

    private static boolean isGenericTrackName(String s) {
        final String low = s.toLowerCase(Locale.US);
        return low.startsWith("aud-") || low.startsWith("audio_") || low.startsWith("track ")
                || low.startsWith("track_") || low.equals("unknown") || low.equals("неизвестно");
    }

    /** разбор на пару «исполнитель, название»; null — разбирать нечего или небезопасно */
    private static String[] split(String raw) {
        if (!enabled() || TextUtils.isEmpty(raw)) {
            return null;
        }
        return splitAny(raw);
    }

    public static String[] splitAny(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String value = raw.trim();
        value = stripExtension(value);
        value = normalizeUnderscores(value);
        value = stripLeadingNumber(value);
        value = stripBotAndDomainSegments(value);
        value = stripNoiseBrackets(value);
        value = collapseSpaces(value);
        final int at = separatorAt(value);
        if (at < 0) {
            return null;
        }
        final String artist = collapseSpaces(value.substring(0, at));
        final String title = collapseSpaces(stripLeadingDash(value.substring(at + 1)));
        if (!sane(artist, MAX_ARTIST) || title.length() < MIN_TITLE || title.length() > 200) {
            return null;
        }
        return new String[]{artist, title};
    }

    private static String stripLeadingDash(String s) {
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '-' || c == '\u2014' || c == '\u2013' || c == ' ') {
                i++;
            } else {
                break;
            }
        }
        return s.substring(i);
    }

    private static int separatorAt(String value) {
        for (int i = 1; i < value.length() - 1; i++) {
            final char c = value.charAt(i);
            if (c == '\u2014' || c == '\u2013' || c == '\u2015' || c == '\u2212') {
                return i;
            }
            if (c == '-' && value.charAt(i - 1) == ' ' && value.charAt(i + 1) == ' ') {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeUnderscores(String s) {
        if (s.indexOf('_') < 0) {
            return s;
        }
        // Если в имени файла совсем нет пробелов или есть «_-_», подчёркивания заменяют пробелы
        if (s.indexOf(' ') < 0 || s.contains("_-_")) {
            return s.replace('_', ' ').trim();
        }
        return s;
    }

    private static String stripBotAndDomainSegments(String s) {
        if (TextUtils.isEmpty(s)) {
            return s;
        }
        // Отрезаем хвосты после « | » если в них реклама бота или ссылка
        final int pipeIdx = s.indexOf('|');
        if (pipeIdx > 0) {
            final String left = s.substring(0, pipeIdx).trim();
            final String right = s.substring(pipeIdx + 1).trim();
            if (looksLikePromo(right) && !looksLikePromo(left)) {
                s = left;
            } else if (looksLikePromo(left) && !looksLikePromo(right)) {
                s = right;
            }
        }
        // Удаляем одиночные @bot_username токены по краям
        String[] tokens = s.split("\\s+");
        if (tokens.length > 1) {
            final StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < tokens.length; i++) {
                final String tok = tokens[i];
                if (looksLikePromoToken(tok)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(tok);
            }
            if (sb.length() >= 2) {
                s = sb.toString();
            }
        }
        return s.trim();
    }

    private static boolean looksLikePromo(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        final String low = text.toLowerCase(Locale.US);
        if (low.startsWith("@") || low.contains("http://") || low.contains("https://") || low.contains("www.")) {
            return true;
        }
        for (String d : JUNK_DOMAINS) {
            if (low.contains(d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikePromoToken(String tok) {
        if (TextUtils.isEmpty(tok)) {
            return false;
        }
        final String low = tok.toLowerCase(Locale.US);
        if (low.startsWith("@") && low.length() > 3) {
            return true;
        }
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("t.me/") || low.startsWith("vk.com/")) {
            return true;
        }
        for (String d : JUNK_DOMAINS) {
            if (low.contains(d)) {
                return true;
            }
        }
        return false;
    }

    private static String stripNoiseBrackets(String s) {
        if (TextUtils.isEmpty(s) || (s.indexOf('(') < 0 && s.indexOf('[') < 0)) {
            return s;
        }
        final StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '(' || c == '[') {
                final char close = c == '(' ? ')' : ']';
                final int end = s.indexOf(close, i + 1);
                if (end > i) {
                    final String inside = s.substring(i + 1, end).trim();
                    if (isJunkBracketContent(inside)) {
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString().trim();
    }

    private static boolean isJunkBracketContent(String inside) {
        if (TextUtils.isEmpty(inside)) {
            return true;
        }
        final String low = inside.toLowerCase(Locale.US);
        if (looksLikePromo(low)) {
            return true;
        }
        for (String junk : JUNK_BRACKET_TAGS) {
            if (low.equals(junk)) {
                return true;
            }
        }
        return false;
    }

    private static String collapseSpaces(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = true;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        int len = sb.length();
        while (len > 0 && sb.charAt(len - 1) == ' ') {
            len--;
        }
        return sb.substring(0, len);
    }

    private static String stripLeadingNumber(String value) {
        int i = 0;
        while (i < value.length() && Character.isDigit(value.charAt(i))) {
            i++;
        }
        if (i == 0 || i > 3 || i >= value.length()) {
            return value;
        }
        int j = i;
        while (j < value.length()) {
            final char c = value.charAt(j);
            if (c == '.' || c == ')' || c == '-' || c == '\u2014' || c == '\u2013' || c == ' ') {
                j++;
            } else {
                break;
            }
        }
        if (j > i && j < value.length()) {
            final String rest = value.substring(j).trim();
            return rest.isEmpty() ? value : rest;
        }
        return value;
    }

    private static String stripExtension(String value) {
        final String lower = value.toLowerCase(Locale.US);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext) && value.length() > ext.length()) {
                return value.substring(0, value.length() - ext.length()).trim();
            }
        }
        return value;
    }

    private static boolean sane(String value, int max) {
        if (TextUtils.isEmpty(value) || value.length() > max) {
            return false;
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.toLowerCase(Locale.US).contains("http")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
