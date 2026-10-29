package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.PengramConfig;

/**
 * Pengram: векторная отрисовка кастомных стилей галочек прочтения и индикаторов «онлайн»
 * с нулевыми аллокациями в кадре (все Paint, Path и RectF переиспользуются на UI-потоке).
 */
public final class PengramStatusIndicators {

    private static final Paint STROKE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF RECT = new RectF();

    static {
        STROKE_PAINT.setStyle(Paint.Style.STROKE);
        STROKE_PAINT.setStrokeCap(Paint.Cap.ROUND);
        STROKE_PAINT.setStrokeJoin(Paint.Join.ROUND);
        FILL_PAINT.setStyle(Paint.Style.FILL);
    }

    private PengramStatusIndicators() {
    }

    public static boolean useCustomCheckmarks() {
        return PengramConfig.getCheckmarkStyle() != PengramConfig.CHECKMARK_STANDARD;
    }

    public static boolean useCustomOnlineBadge() {
        return PengramConfig.getOnlineBadgeStyle() != PengramConfig.ONLINE_STANDARD;
    }

    /**
     * Рисует галочки в пузыре сообщения / поверх стикера / поверх медиа-плашки.
     *
     * @param sentColor цвет состояния «доставлено / отправлено»
     * @param readColor цвет состояния «прочитано»
     * @param drawSent  нужно ли рисовать первую галочку
     * @param drawRead  нужно ли рисовать вторую галочку (прочитано)
     * @param progress  анимация появления второй галочки [0..1]
     * @param alpha     общая прозрачность [0..1]
     * @param boxLeft   левая граница области галочек (~18..20dp шириной)
     * @param centerY   вертикальный центр строки времени
     */
    public static void drawMessageChecks(
            Canvas canvas,
            float boxLeft,
            float centerY,
            int sentColor,
            int readColor,
            boolean drawSent,
            boolean drawRead,
            float progress,
            float scale,
            float alpha
    ) {
        if (!drawSent && !drawRead) {
            return;
        }
        final int style = PengramConfig.getCheckmarkStyle();
        final float readP = drawRead ? clamp01(progress) : 0f;
        final int activeColor = readP > 0.01f ? ColorUtils.blendARGB(sentColor, readColor, readP) : sentColor;
        final float totalAlpha = clamp01(alpha);
        if (totalAlpha <= 0.004f) {
            return;
        }

        final boolean scaled = Math.abs(scale - 1f) > 0.005f;
        final float cx = boxLeft + dpf2(8.5f);
        if (scaled) {
            canvas.save();
            canvas.scale(scale, scale, cx, centerY);
        }

        switch (style) {
            case PengramConfig.CHECKMARK_IOS_ROUNDED:
                drawIosRoundedChecks(canvas, boxLeft, centerY, activeColor, readColor, drawSent, drawRead, readP, totalAlpha, false);
                break;
            case PengramConfig.CHECKMARK_DOUBLE_DOTS:
                drawDotsOrCapsules(canvas, boxLeft, centerY, activeColor, readColor, drawSent, drawRead, readP, totalAlpha, false);
                break;
            case PengramConfig.CHECKMARK_OUTLINE_FILL:
                drawOutlineFillBadge(canvas, boxLeft, centerY, sentColor, readColor, drawSent, drawRead, readP, totalAlpha, false);
                break;
            case PengramConfig.CHECKMARK_MINIMAL:
            default:
                drawMinimalChecks(canvas, boxLeft, centerY, activeColor, readColor, drawSent, drawRead, readP, totalAlpha, false);
                break;
        }

        if (scaled) {
            canvas.restore();
        }
    }

    /**
     * Рисует галочки в строке списка диалогов ({@code DialogCell} и превью).
     */
    public static void drawDialogChecks(
            Canvas canvas,
            float left,
            float top,
            float width,
            float height,
            int sentColor,
            int readColor,
            boolean isRead,
            float alpha
    ) {
        final int style = PengramConfig.getCheckmarkStyle();
        final float centerY = top + height * 0.52f;
        final float boxLeft = left + Math.max(0f, (width - dpf2(17f)) * 0.5f);
        final float readP = isRead ? 1f : 0f;
        final int activeColor = isRead ? readColor : sentColor;
        final float a = clamp01(alpha);

        switch (style) {
            case PengramConfig.CHECKMARK_IOS_ROUNDED:
                drawIosRoundedChecks(canvas, boxLeft, centerY, activeColor, readColor, true, isRead, readP, a, true);
                break;
            case PengramConfig.CHECKMARK_DOUBLE_DOTS:
                drawDotsOrCapsules(canvas, boxLeft, centerY, activeColor, readColor, true, isRead, readP, a, true);
                break;
            case PengramConfig.CHECKMARK_OUTLINE_FILL:
                drawOutlineFillBadge(canvas, boxLeft, centerY, sentColor, readColor, true, isRead, readP, a, true);
                break;
            case PengramConfig.CHECKMARK_MINIMAL:
            default:
                drawMinimalChecks(canvas, boxLeft, centerY, activeColor, readColor, true, isRead, readP, a, true);
                break;
        }
    }

    private static void drawIosRoundedChecks(
            Canvas canvas, float left, float cy, int color1, int color2,
            boolean drawSent, boolean drawRead, float readP, float alpha, boolean compact
    ) {
        final float s = compact ? 0.92f : 1f;
        final float shift = readP * dpf2(2.4f) * s;
        STROKE_PAINT.setStrokeWidth(dpf2(compact ? 1.65f : 1.75f));
        STROKE_PAINT.setStrokeCap(Paint.Cap.ROUND);
        STROKE_PAINT.setStrokeJoin(Paint.Join.ROUND);

        if (drawSent) {
            applyPaintColor(STROKE_PAINT, color1, alpha);
            final float x0 = left + dpf2(4.2f) * s - shift;
            PATH.rewind();
            PATH.moveTo(x0, cy + dpf2(0.3f) * s);
            PATH.lineTo(x0 + dpf2(2.9f) * s, cy + dpf2(3.2f) * s);
            PATH.lineTo(x0 + dpf2(8.7f) * s, cy - dpf2(3.1f) * s);
            canvas.drawPath(PATH, STROKE_PAINT);
        }

        if (drawRead && readP > 0.01f) {
            applyPaintColor(STROKE_PAINT, color2, alpha * readP);
            final float x1 = left + dpf2(6.9f) * s;
            // Короткая левая лапка второй галочки не пересекает первую — чисто как в iOS
            PATH.rewind();
            PATH.moveTo(x1 + dpf2(1.5f) * s, cy + dpf2(1.8f) * s);
            PATH.lineTo(x1 + dpf2(2.9f) * s, cy + dpf2(3.2f) * s);
            PATH.lineTo(x1 + dpf2(8.7f) * s, cy - dpf2(3.1f) * s);
            canvas.drawPath(PATH, STROKE_PAINT);
        }
    }

    private static void drawDotsOrCapsules(
            Canvas canvas, float left, float cy, int color1, int color2,
            boolean drawSent, boolean drawRead, float readP, float alpha, boolean compact
    ) {
        final float s = compact ? 0.92f : 1f;
        final float r = dpf2(2.35f) * s;
        if (!drawRead || readP <= 0.01f) {
            if (drawSent) {
                applyPaintColor(FILL_PAINT, color1, alpha);
                canvas.drawCircle(left + dpf2(9.2f) * s, cy + dpf2(0.3f), r, FILL_PAINT);
            }
            return;
        }
        // При прочтении: левая точка + плавно раскрывающаяся правая микро-капсула
        final float spread = dpf2(3.2f) * s * readP;
        applyPaintColor(FILL_PAINT, color2, alpha);
        canvas.drawCircle(left + dpf2(8.2f) * s - spread, cy + dpf2(0.3f), r * 0.96f, FILL_PAINT);

        final float capHalfW = r + dpf2(1.55f) * s * readP;
        final float c2x = left + dpf2(9.4f) * s + spread * 0.78f;
        final float c2y = cy + dpf2(0.3f);
        RECT.set(c2x - capHalfW, c2y - r * 0.96f, c2x + capHalfW, c2y + r * 0.96f);
        applyPaintColor(FILL_PAINT, color2, alpha * readP);
        canvas.drawRoundRect(RECT, r, r, FILL_PAINT);
    }

    private static void drawOutlineFillBadge(
            Canvas canvas, float left, float cy, int sentColor, int readColor,
            boolean drawSent, boolean drawRead, float readP, float alpha, boolean compact
    ) {
        final float s = compact ? 0.92f : 1f;
        final float cx = left + dpf2(9.2f) * s;
        final float cyAdj = cy + dpf2(0.2f);
        final float halfW = dpf2(5.4f + 1.5f * readP) * s;
        final float halfH = dpf2(5.1f) * s;

        RECT.set(cx - halfW, cyAdj - halfH, cx + halfW, cyAdj + halfH);

        if (readP < 0.99f && drawSent) {
            STROKE_PAINT.setStrokeWidth(dpf2(1.35f));
            applyPaintColor(STROKE_PAINT, sentColor, alpha * (1f - readP));
            canvas.drawRoundRect(RECT, halfH, halfH, STROKE_PAINT);

            PATH.rewind();
            PATH.moveTo(cx - dpf2(2.3f) * s, cyAdj + dpf2(0.2f) * s);
            PATH.lineTo(cx - dpf2(0.5f) * s, cyAdj + dpf2(2.0f) * s);
            PATH.lineTo(cx + dpf2(2.6f) * s, cyAdj - dpf2(1.6f) * s);
            STROKE_PAINT.setStrokeWidth(dpf2(1.3f));
            canvas.drawPath(PATH, STROKE_PAINT);
        }

        if (drawRead && readP > 0.01f) {
            applyPaintColor(FILL_PAINT, readColor, alpha * readP);
            canvas.drawRoundRect(RECT, halfH, halfH, FILL_PAINT);

            // Контрастная галочка внутри заполненной капсулы (тёмная на светлом или белая на насыщенном)
            final int tickColor = ColorUtils.calculateLuminance(readColor) > 0.62 ? 0xFF15191E : Color.WHITE;
            STROKE_PAINT.setStrokeWidth(dpf2(1.45f));
            applyPaintColor(STROKE_PAINT, tickColor, alpha * readP);
            PATH.rewind();
            PATH.moveTo(cx - dpf2(2.6f) * s, cyAdj + dpf2(0.2f) * s);
            PATH.lineTo(cx - dpf2(0.6f) * s, cyAdj + dpf2(2.2f) * s);
            PATH.lineTo(cx + dpf2(3.0f) * s, cyAdj - dpf2(1.8f) * s);
            canvas.drawPath(PATH, STROKE_PAINT);
        }
    }

    private static void drawMinimalChecks(
            Canvas canvas, float left, float cy, int color1, int color2,
            boolean drawSent, boolean drawRead, float readP, float alpha, boolean compact
    ) {
        final float s = compact ? 0.92f : 1f;
        final float shift = readP * dpf2(2.1f) * s;
        STROKE_PAINT.setStrokeWidth(dpf2(1.25f));
        STROKE_PAINT.setStrokeCap(Paint.Cap.ROUND);
        STROKE_PAINT.setStrokeJoin(Paint.Join.MITER);

        if (drawSent) {
            applyPaintColor(STROKE_PAINT, color1, alpha);
            final float x0 = left + dpf2(5.0f) * s - shift;
            PATH.rewind();
            PATH.moveTo(x0, cy + dpf2(0.6f) * s);
            PATH.lineTo(x0 + dpf2(2.4f) * s, cy + dpf2(3.0f) * s);
            PATH.lineTo(x0 + dpf2(7.6f) * s, cy - dpf2(2.6f) * s);
            canvas.drawPath(PATH, STROKE_PAINT);
        }

        if (drawRead && readP > 0.01f) {
            applyPaintColor(STROKE_PAINT, color2, alpha * readP);
            // Лаконичная параллельная диагональ без визуального шума
            final float x1 = left + dpf2(8.7f) * s;
            canvas.drawLine(
                    x1 + dpf2(1.6f) * s, cy + dpf2(2.8f) * s,
                    x1 + dpf2(6.6f) * s, cy - dpf2(2.6f) * s,
                    STROKE_PAINT
            );
        }
        STROKE_PAINT.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * Рисует индикатор «онлайн» на аватарке в соответствии с выбранным стилем.
     */
    public static void drawOnlineBadge(
            Canvas canvas,
            float cx,
            float cy,
            float progress,
            int cutoutColor,
            int onlineColor
    ) {
        final float p = clamp01(progress);
        if (p <= 0.005f) {
            return;
        }
        final int style = PengramConfig.getOnlineBadgeStyle();
        switch (style) {
            case PengramConfig.ONLINE_RING: {
                // Подложка-вырез + неоновое кольцо с прозрачной сердцевиной и микро-искрой
                applyPaintColor(FILL_PAINT, cutoutColor, 1f);
                canvas.drawCircle(cx, cy, dpf2(7.2f) * p, FILL_PAINT);

                STROKE_PAINT.setStrokeWidth(dpf2(2.05f) * p);
                applyPaintColor(STROKE_PAINT, onlineColor, 1f);
                canvas.drawCircle(cx, cy, dpf2(4.45f) * p, STROKE_PAINT);

                applyPaintColor(FILL_PAINT, onlineColor, 0.9f);
                canvas.drawCircle(cx, cy, dpf2(1.3f) * p, FILL_PAINT);
                break;
            }
            case PengramConfig.ONLINE_CAPSULE: {
                final float hw = dpf2(6.8f) * p;
                final float hh = dpf2(4.4f) * p;
                final float ihw = dpf2(5.1f) * p;
                final float ihh = dpf2(2.8f) * p;

                RECT.set(cx - hw, cy - hh, cx + hw, cy + hh);
                applyPaintColor(FILL_PAINT, cutoutColor, 1f);
                canvas.drawRoundRect(RECT, hh, hh, FILL_PAINT);

                RECT.set(cx - ihw, cy - ihh, cx + ihw, cy + ihh);
                applyPaintColor(FILL_PAINT, onlineColor, 1f);
                canvas.drawRoundRect(RECT, ihh, ihh, FILL_PAINT);

                // Лёгкий стеклянный блик сверху капсулы
                applyPaintColor(FILL_PAINT, Color.WHITE, 0.35f);
                canvas.drawCircle(cx - dpf2(2.0f) * p, cy - dpf2(0.5f) * p, dpf2(1.05f) * p, FILL_PAINT);
                break;
            }
            case PengramConfig.ONLINE_DIAMOND: {
                buildDiamondPath(PATH, cx, cy, dpf2(7.6f) * p);
                applyPaintColor(FILL_PAINT, cutoutColor, 1f);
                canvas.drawPath(PATH, FILL_PAINT);

                buildDiamondPath(PATH, cx, cy, dpf2(5.5f) * p);
                applyPaintColor(FILL_PAINT, onlineColor, 1f);
                canvas.drawPath(PATH, FILL_PAINT);
                break;
            }
            case PengramConfig.ONLINE_PULSE: {
                // Многослойный мягкий ореол без бесконечного таймера перерисовки (0% расхода батареи/FPS)
                applyPaintColor(FILL_PAINT, cutoutColor, 0.92f);
                canvas.drawCircle(cx, cy, dpf2(7.0f) * p, FILL_PAINT);

                applyPaintColor(FILL_PAINT, onlineColor, 0.24f);
                canvas.drawCircle(cx, cy, dpf2(8.2f) * p, FILL_PAINT);

                applyPaintColor(FILL_PAINT, onlineColor, 0.45f);
                canvas.drawCircle(cx, cy, dpf2(6.0f) * p, FILL_PAINT);

                applyPaintColor(FILL_PAINT, onlineColor, 1f);
                canvas.drawCircle(cx, cy, dpf2(4.0f) * p, FILL_PAINT);

                applyPaintColor(FILL_PAINT, Color.WHITE, 0.45f);
                canvas.drawCircle(cx - dpf2(1.1f) * p, cy - dpf2(1.1f) * p, dpf2(1.1f) * p, FILL_PAINT);
                break;
            }
            case PengramConfig.ONLINE_STANDARD:
            default: {
                applyPaintColor(FILL_PAINT, cutoutColor, 1f);
                canvas.drawCircle(cx, cy, dp(7) * p, FILL_PAINT);
                applyPaintColor(FILL_PAINT, onlineColor, 1f);
                canvas.drawCircle(cx, cy, dp(5) * p, FILL_PAINT);
                break;
            }
        }
    }

    private static void buildDiamondPath(Path out, float cx, float cy, float r) {
        out.rewind();
        final float k = r * 0.22f;
        out.moveTo(cx, cy - r);
        out.quadTo(cx + k, cy - k, cx + r, cy);
        out.quadTo(cx + k, cy + k, cx, cy + r);
        out.quadTo(cx - k, cy + k, cx - r, cy);
        out.quadTo(cx - k, cy - k, cx, cy - r);
        out.close();
    }

    private static void applyPaintColor(Paint paint, int color, float alphaFactor) {
        final int baseAlpha = Color.alpha(color);
        final int finalAlpha = Math.max(0, Math.min(255, Math.round(baseAlpha * clamp01(alphaFactor))));
        paint.setColor((color & 0x00FFFFFF) | (finalAlpha << 24));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
