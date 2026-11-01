package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Looper;

import org.telegram.messenger.PengramConfig;

/**
 * Pengram: единая геометрия аватарок по всему приложению (Круг, iOS Squircle, Мягкий квадрат, Шестиугольник).
 *
 * <p>Все контуры строятся по часовой стрелке начиная ровно с 12 часов {@code (cx, top)},
 * чтобы сегменты колец историй в {@code StoriesUtilities} совпадали по углам без смещения,
 * а параметр {@code morph} {@code [0..1]} обеспечивал плавное раскрытие аватарки в полноэкранную
 * прямоугольную шапку профиля без скачков и аллокаций на 120 Гц.
 */
public final class PengramAvatarShape {

    private static final Path UI_PATH = new Path();
    private static final RectF UI_RECT = new RectF();

    private PengramAvatarShape() {
    }

    public static int currentShape() {
        return PengramConfig.getAvatarShape();
    }

    public static boolean isCustomShape() {
        return PengramConfig.getAvatarShape() != PengramConfig.AVATAR_SHAPE_CIRCLE;
    }

    public static void buildPath(Path out, RectF bounds, float morph) {
        buildPathForShape(out, bounds, PengramConfig.getAvatarShape(), morph);
    }

    public static void buildStoryRingPath(Path out, RectF bounds) {
        buildPathForShape(out, bounds, PengramConfig.getAvatarShape(), 1f);
    }

    public static void buildPathForShape(Path out, RectF bounds, int shape, float morph) {
        out.rewind();
        if (bounds == null || bounds.isEmpty()) {
            return;
        }
        final float m = morph < 0f ? 0f : (morph > 1f ? 1f : morph);
        final float left = bounds.left;
        final float top = bounds.top;
        final float right = bounds.right;
        final float bottom = bounds.bottom;
        final float w = right - left;
        final float h = bottom - top;
        final float minDim = Math.min(w, h);
        if (minDim <= 0f) {
            return;
        }
        if (m <= 0.001f) {
            out.addRect(bounds, Path.Direction.CW);
            return;
        }

        switch (shape) {
            case PengramConfig.AVATAR_SHAPE_SQUIRCLE:
                buildSquircleCWFromTop(out, left, top, right, bottom, minDim, m);
                break;
            case PengramConfig.AVATAR_SHAPE_SOFT_SQUARE:
                buildSoftSquareCWFromTop(out, left, top, right, bottom, minDim * 0.24f * m);
                break;
            case PengramConfig.AVATAR_SHAPE_HEXAGON:
                buildHexagonCWFromTop(out, left, top, right, bottom, minDim, m);
                break;
            case PengramConfig.AVATAR_SHAPE_CIRCLE:
            default:
                buildSoftSquareCWFromTop(out, left, top, right, bottom, minDim * 0.5f * m);
                break;
        }
    }

    /**
     * Настоящий iOS Squircle (суперэллипс |x|^4.5 + |y|^4.5 = r^4.5) с непрерывной кривизной G2,
     * начинающийся в верхней центральной точке (cx, top) по часовой стрелке.
     */
    private static void buildSquircleCWFromTop(Path out, float left, float top, float right, float bottom, float minDim, float morph) {
        final float cx = (left + right) * 0.5f;
        // Плечо угла 0.47 * minDim даёт плавный переход без резкого стыка прямой и дуги
        final float shoulder = minDim * 0.47f * morph;
        final float sx = Math.min((right - left) * 0.5f, shoulder);
        final float sy = Math.min((bottom - top) * 0.5f, shoulder);
        // Коэффициент рычагов Безье 0.865 даёт аппроксимацию суперэллипса n≈4.5 (как в iOS)
        final float kx = sx * 0.865f;
        final float ky = sy * 0.865f;

        out.moveTo(cx, top);
        // Верхняя правая четверть
        out.lineTo(right - sx, top);
        out.cubicTo(right - sx + kx, top, right, top + sy - ky, right, top + sy);
        // Правая грань и нижний правый угол
        out.lineTo(right, bottom - sy);
        out.cubicTo(right, bottom - sy + ky, right - sx + kx, bottom, right - sx, bottom);
        // Нижняя грань и нижний левый угол
        out.lineTo(left + sx, bottom);
        out.cubicTo(left + sx - kx, bottom, left, bottom - sy + ky, left, bottom - sy);
        // Левая грань и верхний левый угол
        out.lineTo(left, top + sy);
        out.cubicTo(left, top + sy - ky, left + sx - kx, top, left + sx, top);
        out.close();
    }

    /**
     * Мягкий квадрат с началом в (cx, top) по часовой стрелке.
     */
    private static void buildSoftSquareCWFromTop(Path out, float left, float top, float right, float bottom, float radius) {
        final float maxR = Math.min(right - left, bottom - top) * 0.5f;
        final float r = Math.max(0f, Math.min(maxR, radius));
        final float cx = (left + right) * 0.5f;
        final float k = r * 0.55228475f;

        out.moveTo(cx, top);
        out.lineTo(right - r, top);
        if (r > 0f) {
            out.cubicTo(right - r + k, top, right, top + r - k, right, top + r);
        }
        out.lineTo(right, bottom - r);
        if (r > 0f) {
            out.cubicTo(right, bottom - r + k, right - r + k, bottom, right - r, bottom);
        }
        out.lineTo(left + r, bottom);
        if (r > 0f) {
            out.cubicTo(left + r - k, bottom, left, bottom - r + k, left, bottom - r);
        }
        out.lineTo(left, top + r);
        if (r > 0f) {
            out.cubicTo(left, top + r - k, left + r - k, top, left + r, top);
        }
        out.close();
    }

    /**
     * Симметричный шестиугольник со скруглёнными вершинами (pointy-top), плавно раскрывающийся
     * в прямоугольник при morph -> 0 (для шапки профиля при свайпе вниз).
     */
    private static void buildHexagonCWFromTop(Path out, float left, float top, float right, float bottom, float minDim, float morph) {
        final float w = right - left;
        final float h = bottom - top;
        final float cx = (left + right) * 0.5f;
        // При morph=1: классические плечи шестиугольника (y = 25% и 75%, небольшой боковой отступ 4.5% для гармоничных пропорций)
        final float sideInset = w * 0.045f * morph;
        final float shoulderY = h * 0.25f * morph;
        final float halfTopSpan = w * 0.5f * (1f - morph);

        final float xL = left + sideInset;
        final float xR = right - sideInset;
        final float yUpper = top + shoulderY;
        final float yLower = bottom - shoulderY;

        if (morph < 0.999f && halfTopSpan > 0.5f) {
            // Плавный морфинг 6 -> 8 точек в полный прямоугольник при раскрытии профиля
            out.moveTo(cx, top);
            out.lineTo(cx + halfTopSpan, top);
            out.lineTo(xR, yUpper);
            out.lineTo(xR, yLower);
            out.lineTo(cx + halfTopSpan, bottom);
            out.lineTo(cx - halfTopSpan, bottom);
            out.lineTo(xL, yLower);
            out.lineTo(xL, yUpper);
            out.lineTo(cx - halfTopSpan, top);
            out.close();
            return;
        }

        // Чистый 6-вершинный гексагон с мягким скруглением углов (fillet)
        final float vx0 = cx, vy0 = top;
        final float vx1 = xR, vy1 = yUpper;
        final float vx2 = xR, vy2 = yLower;
        final float vx3 = cx, vy3 = bottom;
        final float vx4 = xL, vy4 = yLower;
        final float vx5 = xL, vy5 = yUpper;

        final float t = 0.16f; // доля ребра под мягкое скругление вершины
        // Начинаем ровно в верхней точке скругления (cx, top + filletY), симметрично вокруг 12 часов
        final float startX = vx0 + (vx1 - vx0) * t;
        final float startY = vy0 + (vy1 - vy0) * t;
        final float endTopLeftX = vx0 + (vx5 - vx0) * t;
        final float endTopLeftY = vy0 + (vy5 - vy0) * t;

        // Точка на 12 часах на самой дуге вершины v0: (cx, (startY + vy0) * 0.5f)
        final float topMidY = (startY + vy0) * 0.5f;
        out.moveTo(cx, topMidY);
        out.quadTo((vx0 + startX) * 0.5f, (vy0 + startY) * 0.5f, startX, startY);

        lineAndCorner(out, vx1, vy1, vx0, vy0, vx2, vy2, t);
        lineAndCorner(out, vx2, vy2, vx1, vy1, vx3, vy3, t);
        lineAndCorner(out, vx3, vy3, vx2, vy2, vx4, vy4, t);
        lineAndCorner(out, vx4, vy4, vx3, vy3, vx5, vy5, t);
        lineAndCorner(out, vx5, vy5, vx4, vy4, vx0, vy0, t);

        out.lineTo(endTopLeftX, endTopLeftY);
        out.quadTo((vx0 + endTopLeftX) * 0.5f, (vy0 + endTopLeftY) * 0.5f, cx, topMidY);
        out.close();
    }

    private static void lineAndCorner(Path out, float vx, float vy, float prevX, float prevY, float nextX, float nextY, float t) {
        final float inX = vx + (prevX - vx) * t;
        final float inY = vy + (prevY - vy) * t;
        final float outX = vx + (nextX - vx) * t;
        final float outY = vy + (nextY - vy) * t;
        out.lineTo(inX, inY);
        out.quadTo(vx, vy, outX, outY);
    }

    public static void drawAvatarBackground(Canvas canvas, float size, int roundRadius, Paint paint) {
        drawAvatarBackground(canvas, 0f, 0f, size, roundRadius, paint);
    }

    public static void drawAvatarBackground(Canvas canvas, float left, float top, float size, int roundRadius, Paint paint) {
        final int shape = PengramConfig.getAvatarShape();
        if (shape == PengramConfig.AVATAR_SHAPE_CIRCLE) {
            if (roundRadius > 0) {
                final RectF r = isUiThread() ? UI_RECT : new RectF();
                r.set(left, top, left + size, top + size);
                canvas.drawRoundRect(r, roundRadius, roundRadius, paint);
            } else {
                final float half = size * 0.5f;
                canvas.drawCircle(left + half, top + half, half, paint);
            }
            return;
        }
        final boolean ui = isUiThread();
        final RectF rect = ui ? UI_RECT : new RectF();
        final Path path = ui ? UI_PATH : new Path();
        rect.set(left, top, left + size, top + size);
        final float morph = roundRadius > 0 ? Math.min(1f, roundRadius / Math.max(1f, size * 0.45f)) : 1f;
        buildPathForShape(path, rect, shape, morph);
        canvas.drawPath(path, paint);
    }

    public static void drawRing(Canvas canvas, RectF rect, Paint paint) {
        final boolean ui = isUiThread();
        final Path path = ui ? UI_PATH : new Path();
        buildStoryRingPath(path, rect);
        canvas.drawPath(path, paint);
    }

    private static boolean isUiThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
