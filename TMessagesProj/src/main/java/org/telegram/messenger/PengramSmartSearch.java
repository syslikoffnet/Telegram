package org.telegram.messenger;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Pengram: единое ядро умного поиска (настройки, глобальный поиск диалогов/контактов,
 * поиск по удалённым и изменённым сообщениям, а также быстрые ответы).
 *
 * <ul>
 *   <li>Двустороннее автоисправление раскладки клавиатуры {@code QWERTY <-> ЙЦУКЕН}
 *       («fdfnfhrf» -> «аватарка», «bp,hfyyjt» -> «избранное», «wbkbynh» -> «цилиндр»).</li>
 *   <li>Фонетическая транслитерация {@code RU <-> EN} («avatarka» <-> «аватарка», «parol» <-> «пароль»).</li>
 *   <li>Нормализация {@code ё -> е}, морфологических окончаний русского/английского языков
 *       и многословный поиск независимо от порядка слов.</li>
 *   <li>Допуск опечаток и перестановки соседних букв (ограниченное расстояние Дамерау — Левенштейна
 *       без аллокаций на горячем пути — доли миллисекунды на запрос).</li>
 * </ul>
 */
public final class PengramSmartSearch {

    private static final String EN_LAYOUT =
            "`qwertyuiop[]asdfghjkl;'zxcvbnm,./~QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>?";
    private static final String RU_LAYOUT =
            "ёйцукенгшщзхъфывапролджэячсмитьбю.ЁЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮ,";

    private static final String[] RU_SUFFIXES = new String[]{
            "ованиями", "ениями", "ациями", "остями", "ующими", "ющими",
            "ование", "ением", "ацией", "остью", "енный", "анный", "овать", "ировать",
            "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "аться", "яться", "ться",
            "ость", "ение", "ания", "ения", "ками", "ках", "кой",
            "ам", "ям", "ах", "ях", "ов", "ев", "ей", "ой", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие",
            "ом", "ем", "ую", "юю", "ть", "ка", "ки", "ку", "ке", "у", "ю", "а", "я", "ы", "и", "е", "о"
    };

    private static final String[] EN_SUFFIXES = new String[]{
            "ations", "ition", "ation", "ments", "ment", "nesses", "ness",
            "ings", "ing", "edly", "ies", "ied", "ers", "ed", "er", "es", "ly", "s"
    };

    private PengramSmartSearch() {
    }

    /** Приводит строку к нижнему регистру, заменяет «ё» -> «е» и схлопывает пунктуацию в пробелы. */
    public static String normalize(String input) {
        if (TextUtils.isEmpty(input)) {
            return "";
        }
        final int len = input.length();
        final StringBuilder sb = new StringBuilder(len);
        boolean prevSpace = true;
        for (int i = 0; i < len; i++) {
            char c = Character.toLowerCase(input.charAt(i));
            if (c == 'ё') {
                c = 'е';
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                prevSpace = false;
            } else if (!prevSpace) {
                sb.append(' ');
                prevSpace = true;
            }
        }
        int outLen = sb.length();
        if (outLen > 0 && sb.charAt(outLen - 1) == ' ') {
            sb.setLength(outLen - 1);
        }
        return sb.toString();
    }

    private static final String[] EMPTY_TOKENS = new String[0];

    private static final ThreadLocal<int[]> DP_SCRATCH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[3 * 36];
        }
    };

    public static String[] splitTokens(String normalizedText) {
        if (TextUtils.isEmpty(normalizedText)) {
            return EMPTY_TOKENS;
        }
        final int len = normalizedText.length();
        int count = 0;
        boolean inToken = false;
        for (int i = 0; i < len; i++) {
            if (normalizedText.charAt(i) != ' ') {
                if (!inToken) {
                    count++;
                    inToken = true;
                }
            } else {
                inToken = false;
            }
        }
        if (count == 0) {
            return EMPTY_TOKENS;
        }
        final String[] out = new String[count];
        int idx = 0;
        int start = -1;
        for (int i = 0; i < len; i++) {
            if (normalizedText.charAt(i) != ' ') {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                out[idx++] = normalizedText.substring(start, i);
                start = -1;
            }
        }
        if (start >= 0) {
            out[idx] = normalizedText.substring(start, len);
        }
        return out;
    }

    /**
     * Переключает раскладку клавиатуры: латинские клавиши -> русские ЙЦУКЕН и наоборот.
     * Всегда возвращает не-null строку ({@code ""}, если переключение не дало отличия).
     */
    public static String flipKeyboardLayout(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        int latCount = 0;
        int cyrCount = 0;
        final int len = raw.length();
        for (int i = 0; i < len; i++) {
            final char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latCount++;
            } else if ((c >= 'а' && c <= 'я') || (c >= 'А' && c <= 'Я') || c == 'ё' || c == 'Ё') {
                cyrCount++;
            }
        }
        if (latCount == 0 && cyrCount == 0) {
            return "";
        }
        final boolean toRussian = latCount >= cyrCount;
        final String from = toRussian ? EN_LAYOUT : RU_LAYOUT;
        final String to = toRussian ? RU_LAYOUT : EN_LAYOUT;
        final StringBuilder sb = new StringBuilder(len);
        boolean changed = false;
        for (int i = 0; i < len; i++) {
            final char c = raw.charAt(i);
            final int idx = from.indexOf(c);
            if (idx >= 0 && idx < to.length()) {
                final char mapped = to.charAt(idx);
                sb.append(mapped);
                if (mapped != c) {
                    changed = true;
                }
            } else {
                sb.append(c);
            }
        }
        if (!changed) {
            return "";
        }
        final String res = sb.toString().trim();
        return res.isEmpty() || res.equalsIgnoreCase(raw.trim()) ? "" : res;
    }

    /** Фонетическая транслитерация латиницы в русский («avatarka» -> «аватарка», «shрифт» -> «шрифт»). */
    public static String transliterateLatinToRu(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        final String lower = raw.toLowerCase(Locale.ROOT).trim();
        boolean hasLat = false;
        for (int i = 0; i < lower.length(); i++) {
            final char c = lower.charAt(i);
            if (c >= 'a' && c <= 'z') {
                hasLat = true;
                break;
            }
        }
        if (!hasLat) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(lower.length());
        int i = 0;
        final int n = lower.length();
        while (i < n) {
            if (i + 3 < n && lower.charAt(i) == 's' && lower.charAt(i + 1) == 'h'
                    && lower.charAt(i + 2) == 'c' && lower.charAt(i + 3) == 'h') {
                sb.append('щ');
                i += 4;
                continue;
            }
            if (i + 2 < n && lower.charAt(i + 1) == 'c' && lower.charAt(i + 2) == 'h') {
                final char c0 = lower.charAt(i);
                if (c0 == 's') {
                    sb.append('щ');
                    i += 3;
                    continue;
                }
                if (c0 == 't') {
                    sb.append('ч');
                    i += 3;
                    continue;
                }
            }
            if (i + 1 < n) {
                final char c1 = lower.charAt(i);
                final char c2 = lower.charAt(i + 1);
                if (c1 == 'z' && c2 == 'h') { sb.append('ж'); i += 2; continue; }
                if (c1 == 'k' && c2 == 'h') { sb.append('х'); i += 2; continue; }
                if (c1 == 't' && c2 == 's') { sb.append('ц'); i += 2; continue; }
                if (c1 == 'c' && c2 == 'h') { sb.append('ч'); i += 2; continue; }
                if (c1 == 's' && c2 == 'h') { sb.append('ш'); i += 2; continue; }
                if (c1 == 'y' && (c2 == 'u' || c2 == 'o' || c2 == 'a')) {
                    sb.append(c2 == 'u' ? 'ю' : (c2 == 'o' ? 'е' : 'я'));
                    i += 2;
                    continue;
                }
                if (c1 == 'j' && (c2 == 'u' || c2 == 'a')) {
                    sb.append(c2 == 'u' ? 'ю' : 'я');
                    i += 2;
                    continue;
                }
            }
            final char c = lower.charAt(i++);
            switch (c) {
                case 'a': sb.append('а'); break;
                case 'b': sb.append('б'); break;
                case 'v': case 'w': sb.append('в'); break;
                case 'g': sb.append('г'); break;
                case 'd': sb.append('д'); break;
                case 'e': sb.append('е'); break;
                case 'z': sb.append('з'); break;
                case 'i': sb.append('и'); break;
                case 'j': sb.append('й'); break;
                case 'k': case 'q': sb.append('к'); break;
                case 'l': sb.append('л'); break;
                case 'm': sb.append('м'); break;
                case 'n': sb.append('н'); break;
                case 'o': sb.append('о'); break;
                case 'p': sb.append('п'); break;
                case 'r': sb.append('р'); break;
                case 's': sb.append('с'); break;
                case 't': sb.append('т'); break;
                case 'u': sb.append('у'); break;
                case 'f': sb.append('ф'); break;
                case 'h': sb.append('х'); break;
                case 'c': sb.append('ц'); break;
                case 'x': sb.append("кс"); break;
                case 'y': sb.append('ы'); break;
                default: sb.append(c); break;
            }
        }
        final String out = sb.toString();
        return out.equals(lower) ? "" : out;
    }

    /** Фонетическая транслитерация русского текста в латиницу («телеграм» -> «telegram»). */
    public static String transliterateRuToLatin(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        final String lower = raw.toLowerCase(Locale.ROOT).trim();
        boolean hasCyr = false;
        for (int i = 0; i < lower.length(); i++) {
            final char c = lower.charAt(i);
            if ((c >= 'а' && c <= 'я') || c == 'ё') {
                hasCyr = true;
                break;
            }
        }
        if (!hasCyr) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(lower.length() + 8);
        for (int i = 0; i < lower.length(); i++) {
            final char c = lower.charAt(i);
            switch (c) {
                case 'а': sb.append('a'); break;
                case 'б': sb.append('b'); break;
                case 'в': sb.append('v'); break;
                case 'г': sb.append('g'); break;
                case 'д': sb.append('d'); break;
                case 'е': case 'ё': case 'э': sb.append('e'); break;
                case 'ж': sb.append("zh"); break;
                case 'з': sb.append('z'); break;
                case 'и': case 'й': case 'ы': sb.append('i'); break;
                case 'к': sb.append('k'); break;
                case 'л': sb.append('l'); break;
                case 'м': sb.append('m'); break;
                case 'н': sb.append('n'); break;
                case 'о': sb.append('o'); break;
                case 'п': sb.append('p'); break;
                case 'р': sb.append('r'); break;
                case 'с': sb.append('s'); break;
                case 'т': sb.append('t'); break;
                case 'у': sb.append('u'); break;
                case 'ф': sb.append('f'); break;
                case 'х': sb.append("kh"); break;
                case 'ц': sb.append("ts"); break;
                case 'ч': sb.append("ch"); break;
                case 'ш': sb.append("sh"); break;
                case 'щ': sb.append("shch"); break;
                case 'ъ': case 'ь': break;
                case 'ю': sb.append("yu"); break;
                case 'я': sb.append("ya"); break;
                default: sb.append(c); break;
            }
        }
        final String out = sb.toString();
        return out.isEmpty() || out.equals(lower) ? "" : out;
    }

    /** Все альтернативные варианты запроса (смена раскладки и транслит) без дубликатов. */
    public static List<String> alternativeQueries(String rawQuery) {
        final LinkedHashSet<String> set = new LinkedHashSet<>(4);
        if (TextUtils.isEmpty(rawQuery)) {
            return new ArrayList<>(0);
        }
        final String baseNorm = normalize(rawQuery);
        final String flipped = flipKeyboardLayout(rawQuery);
        if (!TextUtils.isEmpty(flipped)) {
            final String fn = normalize(flipped);
            if (!fn.isEmpty() && !fn.equals(baseNorm)) {
                set.add(flipped);
            }
        }
        final String latToRu = transliterateLatinToRu(rawQuery);
        if (!TextUtils.isEmpty(latToRu)) {
            final String rn = normalize(latToRu);
            if (!rn.isEmpty() && !rn.equals(baseNorm)) {
                set.add(latToRu);
            }
        }
        final String ruToLat = transliterateRuToLatin(rawQuery);
        if (!TextUtils.isEmpty(ruToLat)) {
            final String ln = normalize(ruToLat);
            if (!ln.isEmpty() && !ln.equals(baseNorm)) {
                set.add(ruToLat);
            }
        }
        return new ArrayList<>(set);
    }

    /** Выделяет морфологическую основу слова (без риска обрезать короткие корни < 3 букв). */
    public static String stem(String word) {
        if (word == null || word.length() < 5) {
            return word == null ? "" : word;
        }
        final char first = word.charAt(0);
        if (first >= 'а' && first <= 'я') {
            for (int i = 0; i < RU_SUFFIXES.length; i++) {
                final String suf = RU_SUFFIXES[i];
                if (word.length() - suf.length() >= 4 && word.endsWith(suf)) {
                    return word.substring(0, word.length() - suf.length());
                }
            }
        } else if (first >= 'a' && first <= 'z') {
            for (int i = 0; i < EN_SUFFIXES.length; i++) {
                final String suf = EN_SUFFIXES[i];
                if (word.length() - suf.length() >= 4 && word.endsWith(suf)) {
                    return word.substring(0, word.length() - suf.length());
                }
            }
        }
        return word;
    }

    /**
     * Быстрая проверка ограниченного расстояния Дамерау — Левенштейна ({@code <= maxEdits}).
     * Поддерживает вставку, удаление, замену и транспозицию соседних символов («автарка» / «аватрака»).
     */
    public static boolean withinDamerauDistance(String a, String b, int maxEdits) {
        if (a == null || b == null) {
            return false;
        }
        final int la = a.length();
        final int lb = b.length();
        if (Math.abs(la - lb) > maxEdits) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        if (maxEdits <= 0) {
            return false;
        }
        if (maxEdits == 1) {
            return withinOneDamerauEdit(a, b);
        }
        // Компактная полоса ДП для maxEdits == 2 на словах до 32 символов (без аллокаций в куче)
        if (la > 32 || lb > 32) {
            return false;
        }
        final int stride = 36;
        final int[] buf = DP_SCRATCH.get();
        int offPrev2 = 0;
        int offPrev = stride;
        int offCurr = stride * 2;
        for (int j = 0; j <= lb; j++) {
            buf[offPrev + j] = j;
        }
        for (int i = 1; i <= la; i++) {
            buf[offCurr] = i;
            int rowMin = i;
            final char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                final char cb = b.charAt(j - 1);
                final int cost = ca == cb ? 0 : 1;
                int val = Math.min(Math.min(buf[offCurr + j - 1] + 1, buf[offPrev + j] + 1), buf[offPrev + j - 1] + cost);
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    val = Math.min(val, buf[offPrev2 + j - 2] + 1);
                }
                buf[offCurr + j] = val;
                if (val < rowMin) {
                    rowMin = val;
                }
            }
            if (rowMin > maxEdits) {
                return false;
            }
            final int tmp = offPrev2;
            offPrev2 = offPrev;
            offPrev = offCurr;
            offCurr = tmp;
        }
        return buf[offPrev + lb] <= maxEdits;
    }

    private static boolean withinOneDamerauEdit(String a, String b) {
        return withinOneDamerauEditSlice(a, a.length(), b, b.length());
    }

    /**
     * Нулевая по аллокациям проверка 1 опечатки (включая транспозицию) между префиксом {@code word}
     * и токеном запроса {@code term} без создания {@code word.substring(...)}.
     */
    public static boolean withinPrefixOneEdit(String word, String term) {
        if (word == null || term == null) {
            return false;
        }
        final int wLen = word.length();
        final int tLen = term.length();
        final int effWLen = wLen > tLen + 1 ? Math.min(wLen, tLen) : wLen;
        return withinOneDamerauEditSlice(word, effWLen, term, tLen);
    }

    private static boolean withinOneDamerauEditSlice(String a, int la, String b, int lb) {
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        while (i < la && j < lb && a.charAt(i) == b.charAt(j)) {
            i++;
            j++;
        }
        if (i == la && j == lb) {
            return true;
        }
        if (la == lb) {
            // Транспозиция соседних символов («аватрака» <-> «аватарка»)
            if (i + 1 < la && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)) {
                return regionEquals(a, i + 2, b, i + 2, la - (i + 2));
            }
            // Одиночная замена символа
            return regionEquals(a, i + 1, b, i + 1, la - (i + 1));
        }
        if (la < lb) {
            // Пропущена 1 буква в a
            return regionEquals(a, i, b, i + 1, la - i);
        }
        // Лишняя 1 буква в a
        return regionEquals(a, i + 1, b, i, lb - i);
    }

    private static boolean regionEquals(String a, int offA, String b, int offB, int len) {
        for (int k = 0; k < len; k++) {
            if (a.charAt(offA + k) != b.charAt(offB + k)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Проверяет, подходит ли хотя бы одно слово из массива {@code words} под токен {@code term}
     * по морфологической основе или с допуском 1 опечатки (на длинных словах >= 5 букв).
     */
    public static boolean fuzzyOrStemWordMatches(String[] words, String term) {
        if (words == null || TextUtils.isEmpty(term)) {
            return false;
        }
        final int tLen = term.length();
        final String termStem = tLen >= 5 ? stem(term) : term;
        for (int i = 0; i < words.length; i++) {
            final String w = words[i];
            if (TextUtils.isEmpty(w)) {
                continue;
            }
            if (w.startsWith(term)) {
                return true;
            }
            if (tLen >= 5 && w.length() >= 4) {
                final String wStem = stem(w);
                if (wStem.length() >= 4 && termStem.length() >= 4
                        && (wStem.startsWith(termStem) || termStem.startsWith(wStem))) {
                    return true;
                }
            }
            if (tLen >= 5 && w.length() >= 4 && term.charAt(0) == w.charAt(0)) {
                if (tLen < 9) {
                    if (withinPrefixOneEdit(w, term) || withinOneDamerauEdit(term, w)) {
                        return true;
                    }
                } else {
                    final int cmpLen = Math.min(w.length(), tLen + 1);
                    final String wPrefix = w.length() > cmpLen ? w.substring(0, cmpLen) : w;
                    if (withinDamerauDistance(term, wPrefix, 2)
                            || withinDamerauDistance(term, w, 2)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Нужен ли расширенный проход при поиске в архиве {@link PengramHistory}:
     * не-ASCII, несколько слов, наличие раскладочного варианта или буква «ё/е».
     */
    public static boolean requiresSmartArchiveScan(String query) {
        if (TextUtils.isEmpty(query)) {
            return false;
        }
        for (int i = 0; i < query.length(); i++) {
            final char c = query.charAt(i);
            if (c > 127 || c == ' ') {
                return true;
            }
        }
        return !flipKeyboardLayout(query).isEmpty();
    }

    /**
     * Умное сопоставление строки архива (текст, предыдущая версия, имя автора) с запросом:
     * понимает «ё/е», произвольный порядок слов, окончания и неверную раскладку клавиатуры,
     * но сохраняет точное совпадение спецсимволов (например «%_»), если в запросе нет букв.
     */
    public static boolean matchesArchiveText(String text, String prevText, String senderName, String rawQuery) {
        if (TextUtils.isEmpty(rawQuery)) {
            return true;
        }
        final String lowerRaw = rawQuery.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (containsFolded(text, lowerRaw) || containsFolded(prevText, lowerRaw)
                || containsFolded(senderName, lowerRaw)) {
            return true;
        }
        final String normQuery = normalize(rawQuery);
        if (normQuery.isEmpty()) {
            // Запрос состоит только из спецсимволов (например "%_") — только точное вхождение!
            return false;
        }
        final String combined = normalize(joinNonEmpty(text, prevText, senderName));
        if (combined.isEmpty()) {
            return false;
        }
        if (matchesAllQueryTokens(combined, splitTokens(normQuery))) {
            return true;
        }
        final String flipped = flipKeyboardLayout(rawQuery);
        if (!TextUtils.isEmpty(flipped)) {
            final String flippedNorm = normalize(flipped);
            if (!flippedNorm.isEmpty() && matchesAllQueryTokens(combined, splitTokens(flippedNorm))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsFolded(String haystack, String lowerNeedle) {
        if (TextUtils.isEmpty(haystack) || TextUtils.isEmpty(lowerNeedle)) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).replace('ё', 'е').contains(lowerNeedle);
    }

    private static String joinNonEmpty(String a, String b, String c) {
        final StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(a)) {
            sb.append(a);
        }
        if (!TextUtils.isEmpty(b)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(b);
        }
        if (!TextUtils.isEmpty(c)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean matchesAllQueryTokens(String normalizedHaystack, String[] queryTokens) {
        if (queryTokens.length == 0) {
            return false;
        }
        String[] hayWords = null;
        for (int i = 0; i < queryTokens.length; i++) {
            final String qt = queryTokens[i];
            if (qt.isEmpty()) {
                continue;
            }
            if (normalizedHaystack.contains(qt)) {
                continue;
            }
            if (hayWords == null) {
                hayWords = splitTokens(normalizedHaystack);
            }
            if (!fuzzyOrStemWordMatches(hayWords, qt)) {
                return false;
            }
        }
        return true;
    }

    /** Извлекает username из ссылок вида {@code https://t.me/durov/123} или {@code @durov}. */
    public static String extractTelegramUsernameFromQuery(String query) {
        if (TextUtils.isEmpty(query)) {
            return null;
        }
        String s = query.trim();
        final String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            s = s.substring(8);
        } else if (lower.startsWith("http://")) {
            s = s.substring(7);
        }
        final String sl = s.toLowerCase(Locale.ROOT);
        if (sl.startsWith("t.me/") || sl.startsWith("telegram.me/")) {
            s = s.substring(s.indexOf('/') + 1);
        } else if (s.startsWith("@") && s.length() > 1) {
            s = s.substring(1);
        } else {
            return null;
        }
        final int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        final int qm = s.indexOf('?');
        if (qm >= 0) {
            s = s.substring(0, qm);
        }
        if (s.length() < 3 || s.startsWith("+") || "joinchat".equalsIgnoreCase(s)) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                return null;
            }
        }
        return s;
    }

    /** Похоже ли обращение к «Избранному» («избранное», «заметки», «saved», «bp,hfyyjt»). */
    public static boolean isSavedMessagesAlias(String rawQuery) {
        if (TextUtils.isEmpty(rawQuery)) {
            return false;
        }
        String n = normalize(rawQuery);
        if (matchesSavedAliasNormalized(n)) {
            return true;
        }
        final String flipped = flipKeyboardLayout(rawQuery);
        return !TextUtils.isEmpty(flipped) && matchesSavedAliasNormalized(normalize(flipped));
    }

    private static boolean matchesSavedAliasNormalized(String n) {
        if (n.length() < 4) {
            return false;
        }
        return "избранное".startsWith(n)
                || "заметки".startsWith(n)
                || "сохраненки".startsWith(n)
                || "сохраненные".startsWith(n)
                || "saved".startsWith(n)
                || "saved messages".startsWith(n)
                || "notes".startsWith(n);
    }

    public static boolean matchesSavedMessagesAlias(String rawQuery) {
        return isSavedMessagesAlias(rawQuery);
    }

    public static String extractTelegramUsername(String raw) {
        return extractTelegramUsernameFromQuery(raw);
    }

    public static boolean withinEditDistance(String a, String b, int maxEdits) {
        return withinDamerauDistance(a, b, maxEdits);
    }

    /** Автоматическая двунаправленная транслитерация (RU ↔ LAT). */
    public static String transliterate(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if ((c >= 'а' && c <= 'я') || (c >= 'А' && c <= 'Я') || c == 'ё' || c == 'Ё') {
                return transliterateRuToLatin(raw);
            }
        }
        return transliterateLatinToRu(raw);
    }

    /** Нормализация цифр телефонного номера (+7 / 8 / скобки / дефисы -> чистые цифры с 8->7). */
    public static String normalizePhoneDigits(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c == '+' || c == '-' || c == ' ' || c == '(' || c == ')' || c == '\u00A0') {
                // допустимые разделители телефонного номера
            } else {
                return "";
            }
        }
        if (sb.length() == 11 && sb.charAt(0) == '8') {
            sb.setCharAt(0, '7');
        }
        return sb.toString();
    }
}
