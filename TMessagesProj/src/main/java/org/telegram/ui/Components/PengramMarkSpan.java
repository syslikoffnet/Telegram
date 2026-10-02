package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

/**
 * Pengram: значок-метка (удалено / изменено), который рисуется прямо в строке времени.
 *
 * В отличие от эмодзи это настоящая векторная иконка: она всегда одного веса со шрифтом,
 * красится в цвет времени и одинаково выглядит на всех прошивках.
 */
public class PengramMarkSpan extends ReplacementSpan {

    private final Drawable drawable;
    private final float scale;
    private final float translateY;
    private int lastColor;
    private ColorFilter colorFilter;

    public PengramMarkSpan(Context context, int resId) {
        this(context, resId, 1f, 0f);
    }

    // значки одинаковые для всех сообщений: держим по одному экземпляру на ресурс
    private static final android.util.SparseArray<Drawable> cache = new android.util.SparseArray<>();

    private static Drawable get(Context context, int resId) {
        Drawable d = cache.get(resId);
        if (d == null) {
            try {
                d = context.getResources().getDrawable(resId).mutate();
                cache.put(resId, d);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return d;
    }

    public PengramMarkSpan(Context context, int resId, float scale, float translateY) {
        this.drawable = get(context, resId);
        this.scale = scale;
        this.translateY = translateY;
    }

    /** ширина значка для выбранного размера текста */
    public static int widthFor(Paint paint, float scale) {
        return (int) Math.ceil(paint.getTextSize() * scale + AndroidUtilities.dp(3));
    }

    private int size(Paint paint) {
        return (int) Math.ceil(paint.getTextSize() * scale);
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
        if (drawable == null) {
            return 0;
        }
        return size(paint) + AndroidUtilities.dp(3);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
        if (drawable == null) {
            return;
        }
        final int color = paint.getColor();
        if (color != lastColor || colorFilter == null) {
            lastColor = color;
            colorFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        drawable.setColorFilter(colorFilter);
        drawable.setAlpha(paint.getAlpha());

        final int s = size(paint);
        // выравниваем значок по оптическому центру строки текста
        final float cy = y + (paint.descent() + paint.ascent()) / 2f + AndroidUtilities.dp(translateY);
        final int left = (int) x;
        final int topY = (int) (cy - s / 2f);
        drawable.setBounds(left, topY, left + s, topY + s);
        drawable.draw(canvas);
    }
}
