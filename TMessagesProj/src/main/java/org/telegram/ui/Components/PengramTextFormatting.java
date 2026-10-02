package org.telegram.ui.Components;

import android.text.Spannable;
import android.text.Spanned;

import org.telegram.messenger.PengramConfig;

/**
 * Pengram: форматирование «продолжается» при наборе.
 * В оригинале стили висят на тексте как SPAN_EXCLUSIVE_EXCLUSIVE: стоит дописать
 * букву в конец жирного куска — и она уже обычная. При редактировании сообщения это
 * особенно обидно: заходишь исправить слово, а оформление слетает.
 * Здесь мы переводим стили в SPAN_EXCLUSIVE_INCLUSIVE — ровно так ведут себя
 * нормальные редакторы: пишешь дальше жирным, пока сам не выключишь.
 */
public class PengramTextFormatting {

    public static CharSequence keepStyleOnType(CharSequence text) {
        if (!PengramConfig.isKeepFormatting() || !(text instanceof Spannable)) {
            return text;
        }
        apply((Spannable) text);
        return text;
    }

    public static void apply(Spannable spannable) {
        if (spannable == null || !PengramConfig.isKeepFormatting()) {
            return;
        }
        try {
            final TextStyleSpan[] spans = spannable.getSpans(0, spannable.length(), TextStyleSpan.class);
            if (spans == null) {
                return;
            }
            for (TextStyleSpan span : spans) {
                final int start = spannable.getSpanStart(span);
                final int end = spannable.getSpanEnd(span);
                if (start < 0 || end <= start) {
                    continue;
                }
                if ((spannable.getSpanFlags(span) & Spanned.SPAN_POINT_MARK_MASK) == Spanned.SPAN_EXCLUSIVE_INCLUSIVE) {
                    continue;
                }
                spannable.removeSpan(span);
                spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            }
        } catch (Throwable ignore) {
        }
    }
}
