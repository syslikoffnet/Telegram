package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.PengramConfig;

/**
 * Pengram: миниатюра плеера — рисует, как будет выглядеть выбранный вид.
 * Нужна, чтобы выбирать оформление глазами, а не по названиям в списке.
 */
public class PengramPlayerMockView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private LinearGradient gradient;
    private int gradientHeight;

    private int style;
    private int accent = 0xFF5FD0A0;

    public PengramPlayerMockView(Context context, int style) {
        super(context);
        this.style = style;
    }

    public void setStyle(int style) {
        this.style = style;
        invalidate();
    }

    public void setAccent(int accent) {
        this.accent = accent;
        gradient = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final float radius = dp(12);
        rect.set(0, 0, w, h);

        if (style == PengramConfig.PLAYER_STYLE_ORIGINAL) {
            drawOriginal(canvas, w, h, radius);
            return;
        }

        // фон как в нашем плеере — градиент из «обложки»
        if (gradient == null || gradientHeight != h) {
            gradientHeight = h;
            gradient = new LinearGradient(0, 0, 0, h,
                    new int[]{ColorUtils.blendARGB(accent, 0xFF000000, 0.3f), 0xFF101018},
                    null, Shader.TileMode.CLAMP);
        }
        paint.setShader(gradient);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        switch (style) {
            case PengramConfig.PLAYER_STYLE_LYRICS:
                drawLyricsStyle(canvas, w, h);
                break;
            case PengramConfig.PLAYER_STYLE_COMPACT:
                drawCompact(canvas, w, h, radius, false);
                break;
            case PengramConfig.PLAYER_STYLE_MINI_LYRICS:
                drawCompact(canvas, w, h, radius, true);
                break;
            case PengramConfig.PLAYER_STYLE_FULL:
            default:
                drawFull(canvas, w, h);
                break;
        }
    }

    private void bar(Canvas canvas, float left, float top, float width, float height, int color, float radius) {
        paint.setColor(color);
        rect.set(left, top, left + width, top + height);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private void dots(Canvas canvas, float centerX, float centerY, int color, float playRadius) {
        paint.setColor(color);
        canvas.drawCircle(centerX, centerY, playRadius, paint);
        paint.setColor(ColorUtils.setAlphaComponent(color, 150));
        canvas.drawCircle(centerX - playRadius * 2.4f, centerY, playRadius * 0.45f, paint);
        canvas.drawCircle(centerX + playRadius * 2.4f, centerY, playRadius * 0.45f, paint);
    }

    private void drawFull(Canvas canvas, int w, int h) {
        final float pad = dp(10);
        final float cover = Math.min(w - pad * 2, h * 0.46f);
        rect.set((w - cover) / 2f, pad + dp(6), (w + cover) / 2f, pad + dp(6) + cover);
        paint.setColor(0x4DFFFFFF);
        canvas.drawRoundRect(rect, dp(8), dp(8), paint);
        paint.setColor(ColorUtils.setAlphaComponent(accent, 110));
        canvas.drawRoundRect(rect, dp(8), dp(8), paint);

        float y = rect.bottom + dp(12);
        bar(canvas, pad, y, w * 0.62f, dp(6), 0xE6FFFFFF, dp(3));
        bar(canvas, pad, y + dp(10), w * 0.42f, dp(4), 0x80FFFFFF, dp(2));
        y += dp(22);
        bar(canvas, pad, y, w - pad * 2, dp(3), 0x40FFFFFF, dp(2));
        bar(canvas, pad, y, (w - pad * 2) * 0.45f, dp(3), accent, dp(2));
        dots(canvas, w / 2f, y + dp(16), 0xFFFFFFFF, dp(7));
    }

    private void drawLyricsStyle(Canvas canvas, int w, int h) {
        final float pad = dp(10);
        float y = pad + dp(4);
        final float[] widths = {0.78f, 0.62f, 0.86f, 0.54f};
        for (int a = 0; a < widths.length; ++a) {
            final boolean active = a == 1;
            bar(canvas, pad, y, (w - pad * 2) * widths[a], dp(active ? 7 : 5),
                    active ? accent : 0x59FFFFFF, dp(3));
            y += dp(active ? 15 : 13);
        }
        y = h - dp(42);
        rect.set(pad, y, pad + dp(22), y + dp(22));
        paint.setColor(0x59FFFFFF);
        canvas.drawRoundRect(rect, dp(5), dp(5), paint);
        bar(canvas, pad + dp(28), y + dp(3), w * 0.4f, dp(5), 0xE6FFFFFF, dp(3));
        bar(canvas, pad + dp(28), y + dp(12), w * 0.28f, dp(4), 0x80FFFFFF, dp(2));
        dots(canvas, w / 2f, h - dp(12), 0xFFFFFFFF, dp(6));
    }

    private void drawCompact(Canvas canvas, int w, int h, float radius, boolean lyrics) {
        // верх полупрозрачный — видно, что это не весь экран
        paint.setColor(0x33000000);
        rect.set(0, 0, w, h * 0.34f);
        canvas.drawRect(rect, paint);

        final float top = h * 0.34f;
        paint.setColor(0xFF15161E);
        rect.set(0, top, w, h);
        canvas.drawRoundRect(rect, radius, radius, paint);
        rect.set(0, h - radius, w, h);
        canvas.drawRect(rect, paint);

        final float pad = dp(9);
        final float cover = dp(lyrics ? 26 : 34);
        rect.set(pad, top + dp(10), pad + cover, top + dp(10) + cover);
        paint.setColor(ColorUtils.setAlphaComponent(accent, 150));
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);

        if (lyrics) {
            float y = top + dp(10);
            bar(canvas, pad + cover + dp(8), y, w * 0.42f, dp(5), accent, dp(3));
            bar(canvas, pad + cover + dp(8), y + dp(9), w * 0.34f, dp(4), 0x66FFFFFF, dp(2));
            bar(canvas, pad + cover + dp(8), y + dp(17), w * 0.38f, dp(4), 0x40FFFFFF, dp(2));
        } else {
            bar(canvas, pad + cover + dp(8), top + dp(16), w * 0.4f, dp(6), 0xE6FFFFFF, dp(3));
            bar(canvas, pad + cover + dp(8), top + dp(26), w * 0.3f, dp(4), 0x80FFFFFF, dp(2));
        }

        final float y = h - dp(28);
        bar(canvas, pad, y, w - pad * 2, dp(3), 0x40FFFFFF, dp(2));
        bar(canvas, pad, y, (w - pad * 2) * 0.5f, dp(3), accent, dp(2));
        dots(canvas, w / 2f, y + dp(14), 0xFFFFFFFF, dp(6));
    }

    private void drawOriginal(Canvas canvas, int w, int h, float radius) {
        paint.setColor(0xFFF2F3F5);
        canvas.drawRoundRect(rect, radius, radius, paint);

        final float pad = dp(10);
        paint.setColor(0xFFFFFFFF);
        rect.set(dp(3), h * 0.22f, w - dp(3), h);
        canvas.drawRoundRect(rect, radius, radius, paint);

        final float cover = Math.min(w - pad * 2, h * 0.34f);
        rect.set((w - cover) / 2f, h * 0.3f, (w + cover) / 2f, h * 0.3f + cover);
        paint.setColor(0xFFD9DEE3);
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);

        float y = rect.bottom + dp(10);
        bar(canvas, pad, y, w * 0.58f, dp(6), 0xFF2A2F36, dp(3));
        bar(canvas, pad, y + dp(10), w * 0.4f, dp(4), 0xFF9AA3AD, dp(2));
        y += dp(22);
        bar(canvas, pad, y, w - pad * 2, dp(3), 0xFFDDE1E6, dp(2));
        bar(canvas, pad, y, (w - pad * 2) * 0.4f, dp(3), 0xFF4FA3E3, dp(2));
        dots(canvas, w / 2f, y + dp(15), 0xFF4FA3E3, dp(6));
    }
}
