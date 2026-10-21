package org.telegram.ui.Components;

/** UAX #29, Unicode 17: таблицы не зависят от старой ICU на Android 23.
 * Никогда не изменяем Editable. Незавершённую IME-графему просто не рисуем. */
public final class PengramGraphemes {
    private PengramGraphemes() {}
    private static final int CR = 1, LF = 2, CONTROL = 3, EXTEND = 4, ZWJ = 5,
            RI = 6, PREPEND = 7, SPACING_MARK = 8, L = 9, V = 10, T = 11, LV = 12, LVT = 13;

    private static int lookup(int[] ranges, int cp) {
        int low = 0, high = ranges.length / 3 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1, index = middle * 3;
            if (cp < ranges[index]) high = middle - 1;
            else if (cp > ranges[index + 1]) low = middle + 1;
            else return ranges[index + 2];
        }
        return 0;
    }
    private static int property(int cp) {
        if (cp >= 0xAC00 && cp <= 0xD7A3) return (cp - 0xAC00) % 28 == 0 ? LV : LVT;
        return lookup(PengramGraphemeData.GCB, cp);
    }
    private static boolean control(int type) { return type == CR || type == LF || type == CONTROL; }
    private static boolean hangul(int a, int b) {
        return a == L && (b == L || b == V || b == LV || b == LVT)
                || (a == V || a == LV) && (b == V || b == T) || (a == T || a == LVT) && b == T;
    }
    public static int clusterEnd(CharSequence text, int start) {
        final int length = text.length();
        if (start < 0 || start >= length) return length;
        int cp = Character.codePointAt(text, start);
        int end = start + Character.charCount(cp), previous = property(cp);
        int regional = previous == RI ? 1 : 0;
        boolean pictographic = lookup(PengramGraphemeData.PICTOGRAPHIC, cp) != 0, zwjAfterPictographic = false;
        boolean consonant = lookup(PengramGraphemeData.INCB, cp) == 1, linker = false;
        while (end < length) {
            cp = Character.codePointAt(text, end);
            int current = property(cp), incb = lookup(PengramGraphemeData.INCB, cp);
            boolean ep = lookup(PengramGraphemeData.PICTOGRAPHIC, cp) != 0;
            boolean joined;
            if (previous == CR && current == LF) joined = true;
            else if (control(previous) || control(current)) joined = false;
            else joined = hangul(previous, current) || current == EXTEND || current == ZWJ
                        || current == SPACING_MARK || previous == PREPEND
                        || consonant && linker && incb == 1
                        || previous == ZWJ && zwjAfterPictographic && ep
                        || previous == RI && current == RI && regional % 2 == 1;
            if (!joined) break;
            end += Character.charCount(cp);
            if (current == ZWJ) { zwjAfterPictographic = pictographic; pictographic = false; }
            else if (current != EXTEND) { pictographic = ep; zwjAfterPictographic = false; }
            if (incb == 1) { consonant = true; linker = false; }
            else if (incb == 2 && consonant) linker = true;
            else if (incb != 3) { consonant = false; linker = false; }
            regional = current == RI ? regional + 1 : 0;
            previous = current;
        }
        return end;
    }
    public static int clusterStart(CharSequence text, int offset) {
        int start = 0, end = 0;
        while (end < offset && end < text.length()) { start = end; end = clusterEnd(text, start); }
        return end == offset ? offset : start;
    }
    public static int previous(CharSequence text, int end) {
        int start = 0, next = 0;
        while (next < end && next < text.length()) { start = next; next = clusterEnd(text, start); }
        return start;
    }
}
