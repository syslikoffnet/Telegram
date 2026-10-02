package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;

import java.util.Random;

/**
 * Pengram: красивое исчезновение сообщения.
 * Снимок сообщения разбивается на клетки, и каждая клетка живёт своей жизнью —
 * улетает пылью, сгорает, осыпается осколками, схлопывается или растворяется.
 * Всё рисуется одним битмапом, поэтому эффект не грузит ни чат, ни список.
 */
public class PengramDeleteEffectView extends View {

    private static final int GRID_X = 16;
    private static final int GRID_Y = 11;

    private final Bitmap bitmap;
    private final int effect;
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private final float[] cellSeed;
    private final float[] cellAngle;
    private final float[] cellSpeed;
    private final float[] cellDelay;

    private long startTime;
    private final long duration;
    private Runnable whenDone;
    private boolean finished;

    public PengramDeleteEffectView(Context context, Bitmap bitmap, int effect) {
        super(context);
        this.bitmap = bitmap;
        this.effect = effect;
        this.duration = durationOf(effect);
        final int count = GRID_X * GRID_Y;
        cellSeed = new float[count];
        cellAngle = new float[count];
        cellSpeed = new float[count];
        cellDelay = new float[count];
        for (int a = 0; a < count; ++a) {
            final int cx = a % GRID_X;
            final int cy = a / GRID_X;
            cellSeed[a] = random.nextFloat();
            cellAngle[a] = (random.nextFloat() - 0.5f) * 2f;
            cellSpeed[a] = 0.6f + random.nextFloat() * 0.8f;
            switch (effect) {
                case PengramConfig.DELETE_EFFECT_DUST:
                    cellDelay[a] = cx / (float) GRID_X * 0.45f + cellSeed[a] * 0.12f;
                    break;
                case PengramConfig.DELETE_EFFECT_BURN:
                    cellDelay[a] = (1f - cy / (float) GRID_Y) * 0.55f + cellSeed[a] * 0.08f;
                    break;
                case PengramConfig.DELETE_EFFECT_DISSOLVE:
                case PengramConfig.DELETE_EFFECT_PIXELATE:
                    cellDelay[a] = cellSeed[a] * 0.55f;
                    break;
                case PengramConfig.DELETE_EFFECT_SHATTER:
                    cellDelay[a] = cellSeed[a] * 0.12f;
                    break;
                default:
                    cellDelay[a] = 0;
                    break;
            }
        }
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private static long durationOf(int effect) {
        switch (effect) {
            case PengramConfig.DELETE_EFFECT_BURN: return 900;
            case PengramConfig.DELETE_EFFECT_SHATTER: return 850;
            case PengramConfig.DELETE_EFFECT_COLLAPSE: return 520;
            case PengramConfig.DELETE_EFFECT_SLIDE: return 480;
            case PengramConfig.DELETE_EFFECT_IMPLODE: return 620;
            case PengramConfig.DELETE_EFFECT_PIXELATE: return 700;
            default: return 800;
        }
    }

    public void start(Runnable whenDone) {
        this.whenDone = whenDone;
        startTime = System.currentTimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled()) {
            finish();
            return;
        }
        if (startTime == 0) {
            startTime = System.currentTimeMillis();
        }
        final float t = Math.min(1f, (System.currentTimeMillis() - startTime) / (float) duration);

        switch (effect) {
            case PengramConfig.DELETE_EFFECT_COLLAPSE: drawCollapse(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_SLIDE: drawSlide(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_IMPLODE: drawImplode(canvas, t); break;
            default: drawCells(canvas, t); break;
        }

        if (t >= 1f) {
            finish();
        } else {
            postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------- цельные эффекты

    private void drawCollapse(Canvas canvas, float t) {
        final float e = CubicBezierInterpolator.EASE_IN.getInterpolation(t);
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight());
        canvas.scale(1f - 0.25f * e, Math.max(0.001f, 1f - e), 0, 0);
        canvas.translate(-getWidth() / 2f, -getHeight());
        paint.setAlpha((int) (255 * (1f - t * t)));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }

    private void drawSlide(Canvas canvas, float t) {
        final float e = CubicBezierInterpolator.EASE_IN.getInterpolation(t);
        canvas.save();
        canvas.translate(getWidth() * e * 1.15f, 0);
        canvas.rotate(6f * e, getWidth() / 2f, getHeight() / 2f);
        paint.setAlpha((int) (255 * (1f - e)));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }

    private void drawImplode(Canvas canvas, float t) {
        final float e = CubicBezierInterpolator.EASE_IN.getInterpolation(t);
        canvas.save();
        canvas.rotate(28f * e, getWidth() / 2f, getHeight() / 2f);
        canvas.scale(Math.max(0.001f, 1f - e), Math.max(0.001f, 1f - e), getWidth() / 2f, getHeight() / 2f);
        paint.setAlpha((int) (255 * (1f - e * e)));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }

    // ------------------------------------------------------- клеточные эффекты

    private void drawCells(Canvas canvas, float t) {
        final float w = getWidth() / (float) GRID_X;
        final float h = getHeight() / (float) GRID_Y;
        final float bw = bitmap.getWidth() / (float) GRID_X;
        final float bh = bitmap.getHeight() / (float) GRID_Y;

        for (int a = 0; a < GRID_X * GRID_Y; ++a) {
            final int cx = a % GRID_X;
            final int cy = a / GRID_X;
            final float local = cellDelay[a] >= 1f ? 0f
                    : Math.max(0f, Math.min(1f, (t - cellDelay[a]) / (1f - cellDelay[a])));
            if (local >= 1f) {
                continue;
            }
            src.set((int) (cx * bw), (int) (cy * bh), (int) Math.ceil((cx + 1) * bw), (int) Math.ceil((cy + 1) * bh));
            float x = cx * w;
            float y = cy * h;
            float scale = 1f;
            float rotate = 0f;
            float alpha = 1f;

            switch (effect) {
                case PengramConfig.DELETE_EFFECT_DUST: {
                    final float e = local * local;
                    x += e * AndroidUtilities.dp(60) * cellSpeed[a];
                    y -= e * AndroidUtilities.dp(26) * (0.3f + cellSeed[a]);
                    scale = 1f - 0.45f * e;
                    alpha = 1f - local;
                    break;
                }
                case PengramConfig.DELETE_EFFECT_BURN: {
                    final float e = local;
                    y -= e * AndroidUtilities.dp(34) * (0.4f + cellSeed[a]);
                    x += (cellAngle[a]) * AndroidUtilities.dp(16) * e;
                    scale = 1f - 0.5f * e;
                    alpha = 1f - e;
                    break;
                }
                case PengramConfig.DELETE_EFFECT_SHATTER: {
                    final float e = local * local;
                    final float dirX = (cx + 0.5f) / GRID_X - 0.5f;
                    final float dirY = (cy + 0.5f) / GRID_Y - 0.5f;
                    x += dirX * AndroidUtilities.dp(190) * e * cellSpeed[a];
                    y += (dirY * AndroidUtilities.dp(120) + AndroidUtilities.dp(90) * e) * e * cellSpeed[a];
                    rotate = cellAngle[a] * 90f * e;
                    alpha = 1f - e;
                    break;
                }
                case PengramConfig.DELETE_EFFECT_DISSOLVE: {
                    scale = 1f - 0.2f * local;
                    alpha = 1f - local;
                    y -= local * AndroidUtilities.dp(6);
                    break;
                }
                case PengramConfig.DELETE_EFFECT_PIXELATE: {
                    final float grow = 1f + local * 1.6f;
                    scale = grow;
                    alpha = 1f - local * local;
                    break;
                }
                default: {
                    alpha = 1f - local;
                    break;
                }
            }

            if (alpha <= 0.01f) {
                continue;
            }
            dst.set(x, y, x + w, y + h);
            canvas.save();
            if (rotate != 0 || scale != 1f) {
                canvas.rotate(rotate, dst.centerX(), dst.centerY());
                canvas.scale(scale, scale, dst.centerX(), dst.centerY());
            }
            paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, alpha))));
            canvas.drawBitmap(bitmap, src, dst, paint);
            canvas.restore();
        }

        if (effect == PengramConfig.DELETE_EFFECT_BURN) {
            drawFireLine(canvas, t);
        }
    }

    /** у «сгорания» есть живая линия огня, которая съедает сообщение снизу вверх */
    private void drawFireLine(Canvas canvas, float t) {
        final float edge = getHeight() * (1f - Math.min(1f, t * 1.25f));
        if (edge <= 0 || t >= 0.92f) {
            return;
        }
        final float thickness = AndroidUtilities.dp(14);
        for (int a = 0; a < GRID_X; ++a) {
            final float cw = getWidth() / (float) GRID_X;
            final float wobble = (float) Math.sin(t * 16f + a * 0.9f) * AndroidUtilities.dp(4);
            glow.setColor(ColorUtils.blendARGB(0xFFFF7A18, 0xFFFFD66B, (a % 3) / 2f));
            glow.setAlpha(190);
            canvas.drawRoundRect(a * cw, edge + wobble - thickness * 0.5f,
                    (a + 1) * cw, edge + wobble + thickness * 0.35f,
                    thickness * 0.5f, thickness * 0.5f, glow);
            glow.setColor(Color.WHITE);
            glow.setAlpha(80);
            canvas.drawRoundRect(a * cw + cw * 0.2f, edge + wobble - thickness * 0.2f,
                    (a + 1) * cw - cw * 0.2f, edge + wobble + thickness * 0.12f,
                    thickness * 0.3f, thickness * 0.3f, glow);
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        final Runnable done = whenDone;
        whenDone = null;
        AndroidUtilities.runOnUIThread(() -> {
            AndroidUtilities.removeFromParent(this);
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (done != null) {
                done.run();
            }
        });
    }

    // ------------------------------------------------------------- запуск

    /** снять вид в картинку: дальше анимация живёт сама, исходник можно убирать */
    public static Bitmap snapshot(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        try {
            final int w = Math.min(view.getWidth(), AndroidUtilities.displaySize.x);
            final int h = Math.min(view.getHeight(), AndroidUtilities.displaySize.y);
            final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);
            return bitmap;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Проиграть эффект поверх контейнера на месте указанного вида.
     * Сам вид после этого может исчезнуть — анимация уже живёт отдельно.
     */
    public static boolean play(ViewGroup container, View view, int effect) {
        if (container == null || view == null || effect == PengramConfig.DELETE_EFFECT_NONE) {
            return false;
        }
        final Bitmap bitmap = snapshot(view);
        if (bitmap == null) {
            return false;
        }
        final int[] from = new int[2];
        final int[] to = new int[2];
        view.getLocationInWindow(from);
        container.getLocationInWindow(to);
        final PengramDeleteEffectView effectView = new PengramDeleteEffectView(container.getContext(), bitmap, effect);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bitmap.getWidth(), bitmap.getHeight());
        lp.leftMargin = from[0] - to[0];
        lp.topMargin = from[1] - to[1];
        try {
            container.addView(effectView, lp);
        } catch (Throwable e) {
            bitmap.recycle();
            return false;
        }
        effectView.start(null);
        return true;
    }
}
