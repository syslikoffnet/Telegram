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
import android.view.ViewParent;
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
    /** огонь: путь и градиент готовятся один раз, в кадре ничего не создаётся */
    private final android.graphics.Path firePath = new android.graphics.Path();
    private final android.graphics.Matrix fireMatrix = new android.graphics.Matrix();
    private final android.graphics.LinearGradient fireShader = new android.graphics.LinearGradient(
            0, -AndroidUtilities.dp(10), 0, AndroidUtilities.dp(22),
            new int[]{0x00FFC46B, 0xFFFFE9A8, 0xFFFF8A1F, 0x00FF5A00},
            new float[]{0f, 0.28f, 0.6f, 1f},
            android.graphics.Shader.TileMode.CLAMP);

    /** кромка «шторки» и цветовой развал «помех» — создаются один раз */
    private final android.graphics.Matrix sweepMatrix = new android.graphics.Matrix();
    private final android.graphics.LinearGradient sweepShader = new android.graphics.LinearGradient(
            0, 0, AndroidUtilities.dp(26), 0,
            new int[]{0x00FFFFFF, 0x55FFD9A0, 0xFFFFF0D0},
            new float[]{0f, 0.65f, 1f}, android.graphics.Shader.TileMode.CLAMP);
    private final android.graphics.ColorFilter glitchRed =
            new android.graphics.PorterDuffColorFilter(0xFFFF4D4D, android.graphics.PorterDuff.Mode.MULTIPLY);
    private final android.graphics.ColorFilter glitchCyan =
            new android.graphics.PorterDuffColorFilter(0xFF4DF2FF, android.graphics.PorterDuff.Mode.MULTIPLY);

    private final float[] cellSeed;
    private final float[] cellAngle;
    private final float[] cellSpeed;
    private final float[] cellDelay;

    private long startTime;
    private final long duration;
    private Runnable whenDone;
    private boolean finished;

    /** где внутри себя рисуем снимок — сам вид растянут на весь экран чата */
    private float originX;
    private float originY;
    /** то же самое, но в координатах окна: не зависит от того, куда контейнер положил накладку */
    private float windowX;
    private float windowY;
    private final RectF windowClip = new RectF();
    private final int[] selfAt = new int[2];
    private final int bmpW;
    private final int bmpH;
    /** за пределы списка сообщений эффект не выходит никогда */
    private final RectF bounds = new RectF();
    private boolean hasBounds;

    // ----- «пазл»: куски с замками, собранные один раз при создании -----
    private android.graphics.Path[] piecePaths;
    private float[] pieceX;
    private float[] pieceY;
    private float[] pieceCx;
    private float[] pieceCy;
    private float[] pieceDelay;
    private float[] pieceVx;
    private float[] pieceVy;
    private float[] pieceSpin;
    private int pieceCols;
    private int pieceRows;

    /** если список едет во время эффекта, картинка едет вместе с ним */
    private androidx.recyclerview.widget.RecyclerView followList;
    private androidx.recyclerview.widget.RecyclerView.OnScrollListener scrollListener;

    public PengramDeleteEffectView(Context context, Bitmap bitmap, int effect) {
        super(context);
        this.bitmap = bitmap;
        this.effect = effect;
        this.bmpW = bitmap == null ? 0 : bitmap.getWidth();
        this.bmpH = bitmap == null ? 0 : bitmap.getHeight();
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
                case PengramConfig.DELETE_EFFECT_TNT: {
                    // волна идёт от центра: ближние куски срывает первыми
                    final float dx = (cx + 0.5f) / GRID_X - 0.5f;
                    final float dy = (cy + 0.5f) / GRID_Y - 0.5f;
                    cellDelay[a] = 0.04f + Math.min(0.26f, (float) Math.hypot(dx, dy) * 0.42f);
                    break;
                }
                case PengramConfig.DELETE_EFFECT_PORTAL:
                    cellDelay[a] = cellSeed[a] * 0.16f;
                    break;
                case PengramConfig.DELETE_EFFECT_PORTAL_BLOCKS: {
                    // Портал по крупинкам съедает сообщение от краёв к центру.
                    final float edge = Math.min(Math.min(cx, GRID_X - 1 - cx), Math.min(cy, GRID_Y - 1 - cy));
                    cellDelay[a] = Math.min(.58f, edge * .075f + cellSeed[a] * .08f);
                    break;
                }
                default:
                    cellDelay[a] = 0;
                    break;
            }
        }
        if (effect == PengramConfig.DELETE_EFFECT_PUZZLE) {
            buildPuzzle();
        } else if (effect == PengramConfig.DELETE_EFFECT_SHARDS) {
            buildShards();
        }
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /**
     * Режем снимок на крупные куски с замками «выступ — впадина».
     * Пути строятся один раз: в кадре никаких новых объектов не появляется.
     */
    private void buildPuzzle() {
        if (bmpW <= 0 || bmpH <= 0) {
            return;
        }
        pieceCols = Math.max(3, Math.min(6, Math.round(bmpW / (float) AndroidUtilities.dp(72))));
        pieceRows = Math.max(2, Math.min(5, Math.round(bmpH / (float) AndroidUtilities.dp(54))));
        final int count = pieceCols * pieceRows;
        piecePaths = new android.graphics.Path[count];
        pieceX = new float[count];
        pieceY = new float[count];
        pieceCx = new float[count];
        pieceCy = new float[count];
        pieceDelay = new float[count];
        pieceVx = new float[count];
        pieceVy = new float[count];
        pieceSpin = new float[count];

        final float pw = bmpW / (float) pieceCols;
        final float ph = bmpH / (float) pieceRows;
        final float knob = Math.min(pw, ph) * 0.17f;
        final int startCol = random.nextInt(pieceCols);
        final int startRow = random.nextInt(pieceRows);
        final float maxDistance = (float) Math.hypot(pieceCols, pieceRows);
        final RectF rect = new RectF();
        final android.graphics.Path bump = new android.graphics.Path();

        for (int a = 0; a < count; ++a) {
            final int col = a % pieceCols;
            final int row = a / pieceCols;
            final float left = col * pw;
            final float top = row * ph;
            pieceX[a] = left;
            pieceY[a] = top;
            pieceCx[a] = left + pw / 2f;
            pieceCy[a] = top + ph / 2f;

            if (isPieceEmpty(left, top, pw, ph)) {
                piecePaths[a] = null;   // прозрачные места кусков не дают
                continue;
            }

            final android.graphics.Path path = new android.graphics.Path();
            rect.set(left, top, left + pw, top + ph);
            path.addRoundRect(rect, knob * 0.35f, knob * 0.35f, android.graphics.Path.Direction.CW);

            // правое ребро
            if (col < pieceCols - 1) {
                bump.reset();
                bump.addCircle(left + pw, top + ph / 2f, knob, android.graphics.Path.Direction.CW);
                path.op(bump, ((col + row) % 2 == 0)
                        ? android.graphics.Path.Op.UNION : android.graphics.Path.Op.DIFFERENCE);
            }
            // левое ребро — зеркально соседу
            if (col > 0) {
                bump.reset();
                bump.addCircle(left, top + ph / 2f, knob, android.graphics.Path.Direction.CW);
                path.op(bump, ((col - 1 + row) % 2 == 0)
                        ? android.graphics.Path.Op.DIFFERENCE : android.graphics.Path.Op.UNION);
            }
            // нижнее ребро
            if (row < pieceRows - 1) {
                bump.reset();
                bump.addCircle(left + pw / 2f, top + ph, knob, android.graphics.Path.Direction.CW);
                path.op(bump, ((col + row) % 2 == 0)
                        ? android.graphics.Path.Op.DIFFERENCE : android.graphics.Path.Op.UNION);
            }
            // верхнее ребро
            if (row > 0) {
                bump.reset();
                bump.addCircle(left + pw / 2f, top, knob, android.graphics.Path.Direction.CW);
                path.op(bump, ((col + row - 1) % 2 == 0)
                        ? android.graphics.Path.Op.UNION : android.graphics.Path.Op.DIFFERENCE);
            }
            piecePaths[a] = path;

            final float distance = (float) Math.hypot(col - startCol, row - startRow);
            pieceDelay[a] = Math.min(0.5f, distance / Math.max(1f, maxDistance) * 0.5f);
            final float dirX = (col + 0.5f) / pieceCols - 0.5f;
            pieceVx[a] = dirX * AndroidUtilities.dp(70) + (random.nextFloat() - 0.5f) * AndroidUtilities.dp(16);
            pieceVy[a] = AndroidUtilities.dp(26) + random.nextFloat() * AndroidUtilities.dp(18);
            pieceSpin[a] = (random.nextFloat() - 0.5f) * 50f;
        }
    }

    /**
     * «Осколки»: сетка режется по диагоналям на треугольники, вершины немного
     * сдвинуты — получается рваная мозаика, а не аккуратная шахматка.
     * Как и у пазла, все пути строятся один раз.
     */
    private void buildShards() {
        if (bmpW <= 0 || bmpH <= 0) {
            return;
        }
        pieceCols = Math.max(3, Math.min(7, Math.round(bmpW / (float) AndroidUtilities.dp(56))));
        pieceRows = Math.max(2, Math.min(6, Math.round(bmpH / (float) AndroidUtilities.dp(44))));
        final int cells = pieceCols * pieceRows;
        final int count = cells * 2;
        piecePaths = new android.graphics.Path[count];
        pieceX = new float[count];
        pieceY = new float[count];
        pieceCx = new float[count];
        pieceCy = new float[count];
        pieceDelay = new float[count];
        pieceVx = new float[count];
        pieceVy = new float[count];
        pieceSpin = new float[count];

        final float pw = bmpW / (float) pieceCols;
        final float ph = bmpH / (float) pieceRows;
        final float jitter = Math.min(pw, ph) * 0.18f;
        final float centerX = bmpW / 2f;
        final float centerY = bmpH / 2f;

        for (int cell = 0; cell < cells; ++cell) {
            final int col = cell % pieceCols;
            final int row = cell / pieceCols;
            final float left = col * pw;
            final float top = row * ph;
            final boolean flip = ((col + row) % 2) == 0;

            if (isPieceEmpty(left, top, pw, ph)) {
                continue;   // прозрачный участок осколков не даёт
            }

            // углы ячейки с лёгким смещением — сколы выглядят неровными
            final float jx = (random.nextFloat() - 0.5f) * jitter;
            final float jy = (random.nextFloat() - 0.5f) * jitter;
            final float x0 = left, y0 = top;
            final float x1 = left + pw, y1 = top;
            final float x2 = left + pw, y2 = top + ph;
            final float x3 = left, y3 = top + ph;
            final float mx = left + pw / 2f + jx;
            final float my = top + ph / 2f + jy;

            for (int half = 0; half < 2; ++half) {
                final int a = cell * 2 + half;
                final android.graphics.Path path = new android.graphics.Path();
                final float ax, ay, bx, by;
                if (flip == (half == 0)) {
                    ax = x0; ay = y0; bx = x1; by = y1;
                } else {
                    ax = x2; ay = y2; bx = x3; by = y3;
                }
                final float cx2 = half == 0 ? x2 : x0;
                final float cy2 = half == 0 ? y2 : y0;
                path.moveTo(ax, ay);
                path.lineTo(bx, by);
                path.lineTo(mx, my);
                path.lineTo(cx2, cy2);
                path.close();
                piecePaths[a] = path;

                pieceX[a] = left;
                pieceY[a] = top;
                pieceCx[a] = (ax + bx + mx + cx2) / 4f;
                pieceCy[a] = (ay + by + my + cy2) / 4f;
                pieceDelay[a] = random.nextFloat() * 0.1f;

                final float dirX = (pieceCx[a] - centerX) / Math.max(1f, bmpW / 2f);
                final float dirY = (pieceCy[a] - centerY) / Math.max(1f, bmpH / 2f);
                pieceVx[a] = dirX * AndroidUtilities.dp(90) * (0.7f + random.nextFloat() * 0.6f);
                pieceVy[a] = dirY * AndroidUtilities.dp(45) - AndroidUtilities.dp(20) * random.nextFloat();
                pieceSpin[a] = (random.nextFloat() - 0.5f) * 220f;
            }
        }
    }

    /** кусок целиком прозрачный? тогда он не нужен */
    private boolean isPieceEmpty(float left, float top, float pw, float ph) {
        try {
            for (int x = 0; x < 5; ++x) {
                for (int y = 0; y < 5; ++y) {
                    final int px = (int) Math.min(bmpW - 1, left + pw * (x + 0.5f) / 5f);
                    final int py = (int) Math.min(bmpH - 1, top + ph * (y + 0.5f) / 5f);
                    if (px < 0 || py < 0) {
                        continue;
                    }
                    if (Color.alpha(bitmap.getPixel(px, py)) > 8) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static long durationOf(int effect) {
        switch (effect) {
            case PengramConfig.DELETE_EFFECT_BURN: return 900;
            case PengramConfig.DELETE_EFFECT_SHATTER: return 850;
            case PengramConfig.DELETE_EFFECT_COLLAPSE: return 520;
            case PengramConfig.DELETE_EFFECT_SLIDE: return 480;
            case PengramConfig.DELETE_EFFECT_IMPLODE: return 620;
            case PengramConfig.DELETE_EFFECT_PIXELATE: return 700;
            case PengramConfig.DELETE_EFFECT_PUZZLE: return 950;
            case PengramConfig.DELETE_EFFECT_SHARDS: return 900;
            case PengramConfig.DELETE_EFFECT_TNT: return 1050;
            case PengramConfig.DELETE_EFFECT_PORTAL: return 1000;
            case PengramConfig.DELETE_EFFECT_PORTAL_BLOCKS: return 1250;
            case PengramConfig.DELETE_EFFECT_GHOST: return 920;
            case PengramConfig.DELETE_EFFECT_GLITCH: return 700;
            case PengramConfig.DELETE_EFFECT_SWEEP: return 760;
            default: return 800;
        }
    }

    public void start(Runnable whenDone) {
        this.whenDone = whenDone;
        startTime = android.os.SystemClock.elapsedRealtime();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled()) {
            finish();
            return;
        }
        if (startTime == 0) {
            startTime = android.os.SystemClock.elapsedRealtime();
        }
        final float t = Math.min(1f, (android.os.SystemClock.elapsedRealtime() - startTime) / (float) duration);
        syncGeometry();

        canvas.save();
        if (hasBounds) {
            canvas.clipRect(bounds);
        }
        canvas.translate(originX, originY);

        switch (effect) {
            case PengramConfig.DELETE_EFFECT_COLLAPSE: drawCollapse(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_SLIDE: drawSlide(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_IMPLODE: drawImplode(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_PUZZLE: drawPuzzle(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_SHARDS: drawShards(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_GHOST: drawGhost(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_GLITCH: drawGlitch(canvas, t); break;
            case PengramConfig.DELETE_EFFECT_SWEEP: drawSweep(canvas, t); break;
            default: drawCells(canvas, t); break;
        }

        canvas.restore();

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
        canvas.translate(bmpW / 2f, bmpH);
        canvas.scale(1f - 0.25f * e, Math.max(0.001f, 1f - e), 0, 0);
        canvas.translate(-bmpW / 2f, -bmpH);
        paint.setAlpha((int) (255 * (1f - t * t)));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }

    private void drawSlide(Canvas canvas, float t) {
        final float e = CubicBezierInterpolator.EASE_IN.getInterpolation(t);
        canvas.save();
        canvas.translate(bmpW * e * 0.55f, 0);
        canvas.rotate(6f * e, bmpW / 2f, bmpH / 2f);
        paint.setAlpha((int) (255 * (1f - e)));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }

    private void drawImplode(Canvas canvas, float t) {
        final float e = CubicBezierInterpolator.EASE_IN.getInterpolation(t);
        canvas.save();
        canvas.rotate(28f * e, bmpW / 2f, bmpH / 2f);
        canvas.scale(Math.max(0.001f, 1f - e), Math.max(0.001f, 1f - e), bmpW / 2f, bmpH / 2f);
        paint.setAlpha((int) (255 * (1f - e * e)));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }

    /** каскад кусочков пазла: импульс наружу-вниз, лёгкое вращение и падение */
    private void drawPuzzle(Canvas canvas, float t) {
        if (piecePaths == null) {
            drawCells(canvas, t);
            return;
        }
        for (int a = 0; a < piecePaths.length; ++a) {
            final android.graphics.Path path = piecePaths[a];
            if (path == null) {
                continue;
            }
            final float delay = pieceDelay[a];
            final float local = delay >= 1f ? 0f : Math.max(0f, Math.min(1f, (t - delay) / (1f - delay)));
            float alpha = local >= 0.7f ? 1f - (local - 0.7f) / 0.3f : 1f;
            if (alpha <= 0.01f) {
                continue;
            }
            final float dx = pieceVx[a] * local;
            final float dy = pieceVy[a] * local + AndroidUtilities.dp(130) * local * local;
            alpha *= edgeFade(pieceX[a] + dx, pieceY[a] + dy, bmpW / (float) pieceCols, bmpH / (float) pieceRows);
            if (alpha <= 0.01f) {
                continue;
            }
            canvas.save();
            canvas.translate(dx, dy);
            canvas.rotate(pieceSpin[a] * local, pieceCx[a], pieceCy[a]);
            canvas.clipPath(path);
            paint.setAlpha((int) (255 * alpha));
            canvas.drawBitmap(bitmap, 0, 0, paint);
            canvas.restore();
        }
    }

    /** осколки: разлёт от центра, вращение и гравитация */
    private void drawShards(Canvas canvas, float t) {
        if (piecePaths == null) {
            drawCells(canvas, t);
            return;
        }
        final float gravity = AndroidUtilities.dp(90);
        for (int a = 0; a < piecePaths.length; ++a) {
            final android.graphics.Path path = piecePaths[a];
            if (path == null) {
                continue;
            }
            final float delay = pieceDelay[a];
            final float local = delay >= 1f ? 0f : clamp01((t - delay) / (1f - delay));
            if (local <= 0f) {
                continue;
            }
            float alpha = local >= 0.72f ? 1f - (local - 0.72f) / 0.28f : 1f;
            if (alpha <= 0.01f) {
                continue;
            }
            final float dx = pieceVx[a] * local;
            final float dy = pieceVy[a] * local + gravity * local * local;
            alpha *= edgeFade(pieceX[a] + dx, pieceY[a] + dy, bmpW / (float) pieceCols, bmpH / (float) pieceRows);
            if (alpha <= 0.01f) {
                continue;
            }
            canvas.save();
            canvas.translate(dx, dy);
            canvas.rotate(pieceSpin[a] * local, pieceCx[a], pieceCy[a]);
            canvas.clipPath(path);
            paint.setAlpha((int) (255 * alpha));
            canvas.drawBitmap(bitmap, 0, 0, paint);
            canvas.restore();
        }
    }

    /** призрак: копия сообщения всплывает, покачиваясь, и тает */
    private void drawGhost(Canvas canvas, float t) {
        final float e = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(t);
        final float lift = -bmpH * 0.85f * e;
        final float sway = (float) Math.sin(t * 6.5f) * AndroidUtilities.dp(7) * (0.25f + e);
        canvas.save();
        canvas.translate(sway, lift);
        canvas.scale(1f + 0.05f * e, 1f + 0.1f * e, bmpW / 2f, bmpH);
        canvas.skew((float) Math.sin(t * 5.2f) * 0.05f * (0.3f + e), 0);
        paint.setAlpha((int) (255 * (1f - e) * (1f - 0.25f * t)));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }

    /** помехи: сообщение рвётся на полосы с цветным развалом */
    private void drawGlitch(Canvas canvas, float t) {
        final int bands = GRID_Y + 3;
        final float bh = bmpH / (float) bands;
        final float srcBh = bitmap.getHeight() / (float) bands;
        final float split = AndroidUtilities.dp(3);
        for (int i = 0; i < bands; ++i) {
            final float seed = cellSeed[(i * GRID_X + i) % cellSeed.length];
            float shift = (float) Math.sin(t * 24f + i * 1.7f) * AndroidUtilities.dp(13) * (0.25f + t) * (0.4f + seed);
            if ((((int) (t * 16f)) + i) % 5 == 0) {
                shift *= 1.8f;   // редкие сильные срывы строки
            }
            float alpha = clamp01(1f - (t - seed * 0.22f) / 0.78f);
            if (alpha <= 0.01f) {
                continue;
            }
            final float top = i * bh;
            alpha *= edgeFade(shift, top, bmpW, bh);
            if (alpha <= 0.01f) {
                continue;
            }
            src.set(0, (int) (i * srcBh), bitmap.getWidth(), (int) Math.ceil((i + 1) * srcBh));
            dst.set(shift, top, bmpW + shift, top + bh);

            paint.setAlpha((int) (110 * alpha));
            paint.setColorFilter(glitchRed);
            dst.offset(-split, 0);
            canvas.drawBitmap(bitmap, src, dst, paint);
            paint.setColorFilter(glitchCyan);
            dst.offset(split * 2f, 0);
            canvas.drawBitmap(bitmap, src, dst, paint);

            paint.setColorFilter(null);
            paint.setAlpha((int) (255 * alpha));
            dst.offset(-split, 0);
            canvas.drawBitmap(bitmap, src, dst, paint);
        }
        paint.setColorFilter(null);
    }

    /** шторка: светящаяся кромка идёт слева направо и стирает пузырь */
    private void drawSweep(Canvas canvas, float t) {
        final float e = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(t);
        final float edge = bmpW * e * 1.08f;
        if (edge < bmpW) {
            canvas.save();
            canvas.clipRect(edge, 0, bmpW, bmpH);
            paint.setAlpha((int) (255 * (1f - 0.35f * t)));
            canvas.drawBitmap(bitmap, 0, 0, paint);
            canvas.restore();

            final float band = AndroidUtilities.dp(26);
            sweepMatrix.setTranslate(edge - band, 0);
            sweepShader.setLocalMatrix(sweepMatrix);
            glow.setShader(sweepShader);
            glow.setAlpha((int) (255 * clamp01(1.15f - t)));
            canvas.drawRect(edge - band, 0, edge + AndroidUtilities.dp(2), bmpH, glow);
            glow.setShader(null);
            glow.setAlpha(255);
        }
    }

    /** динамит: вспышка в центре и расходящаяся ударная волна */
    private void drawBlast(Canvas canvas, float t) {
        final float cx = bmpW / 2f;
        final float cy = bmpH / 2f;
        glow.setShader(null);
        if (t < 0.24f) {
            final float f = 1f - t / 0.24f;
            glow.setStyle(Paint.Style.FILL);
            glow.setColor(0xFFFFF4D6);
            glow.setAlpha((int) (225 * f));
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(22) + (1f - f) * AndroidUtilities.dp(64), glow);
        }
        final float ring = clamp01(1f - t * 1.35f);
        if (ring > 0.01f) {
            final float radius = AndroidUtilities.dp(16) + t * Math.max(bmpW, bmpH) * 0.95f;
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(AndroidUtilities.dp(5) * ring + AndroidUtilities.dp(1));
            glow.setColor(0xFFFFB457);
            glow.setAlpha((int) (210 * ring));
            canvas.drawCircle(cx, cy, radius, glow);
            glow.setStyle(Paint.Style.FILL);
        }
        glow.setAlpha(255);
    }

    // ------------------------------------------------------- клеточные эффекты

    private void drawCells(Canvas canvas, float t) {
        if (effect == PengramConfig.DELETE_EFFECT_TNT && t < .46f) {
            // Взведение в стиле блочного TNT: белые вспышки всё чаще и короткое «раздувание».
            final float armed = t / .46f;
            final float pulse = (float) Math.pow(Math.max(0f, Math.sin((3f + armed * 5f) * Math.PI * armed)), 1.7);
            final float swell = 1f + pulse * .035f;
            canvas.save();
            canvas.scale(swell, swell, bmpW / 2f, bmpH / 2f);
            paint.setAlpha(255);
            paint.setColorFilter(null);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            glow.setStyle(Paint.Style.FILL);
            glow.setColor(Color.WHITE);
            glow.setAlpha((int) ((45 + 180 * armed) * pulse));
            canvas.drawRoundRect(0, 0, bmpW, bmpH, AndroidUtilities.dp(8), AndroidUtilities.dp(8), glow);
            canvas.restore();
            glow.setAlpha(255);
            return;
        }
        if (effect == PengramConfig.DELETE_EFFECT_TNT) t = (t - .46f) / .54f;
        if (effect == PengramConfig.DELETE_EFFECT_PORTAL_BLOCKS) drawPortalBlocks(canvas, t);

        final float w = bmpW / (float) GRID_X;
        final float h = bmpH / (float) GRID_Y;
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
                    x += e * AndroidUtilities.dp(34) * cellSpeed[a];
                    y -= e * AndroidUtilities.dp(18) * (0.3f + cellSeed[a]);
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
                    x += dirX * AndroidUtilities.dp(90) * e * cellSpeed[a];
                    y += (dirY * AndroidUtilities.dp(70) + AndroidUtilities.dp(60) * e) * e * cellSpeed[a];
                    rotate = cellAngle[a] * 90f * e;
                    alpha = 1f - e;
                    break;
                }
                case PengramConfig.DELETE_EFFECT_TNT: {
                    final float e = local;
                    final float dirX = (cx + 0.5f) / GRID_X - 0.5f;
                    final float dirY = (cy + 0.5f) / GRID_Y - 0.5f;
                    // ближе к эпицентру — сильнее импульс
                    final float push = 0.22f / ((float) Math.hypot(dirX, dirY) + 0.1f) * cellSpeed[a];
                    x += dirX * AndroidUtilities.dp(110) * push * e;
                    y += dirY * AndroidUtilities.dp(80) * push * e + AndroidUtilities.dp(70) * e * e;
                    rotate = cellAngle[a] * 70f * e;
                    scale = 1f - 0.3f * e;
                    alpha = 1f - e * e;
                    break;
                }
                case PengramConfig.DELETE_EFFECT_PORTAL: {
                    final float e = CubicBezierInterpolator.EASE_IN.getInterpolation(local);
                    final float centerX = bmpW / 2f;
                    final float centerY = bmpH / 2f;
                    final float px = x + w / 2f - centerX;
                    final float py = y + h / 2f - centerY;
                    final float radius = (float) Math.hypot(px, py) * (1f - e);
                    final float angle = (float) Math.atan2(py, px) + 4.4f * e * (0.6f + cellSeed[a] * 0.6f);
                    x = centerX + (float) Math.cos(angle) * radius - w / 2f;
                    y = centerY + (float) Math.sin(angle) * radius - h / 2f;
                    scale = 1f - 0.8f * e;
                    rotate = angle * 12f;
                    alpha = 1f - e * e;
                    break;
                }
                case PengramConfig.DELETE_EFFECT_PORTAL_BLOCKS: {
                    // Кусок не летит к рамке: пиксели сообщения прямо на месте заменяются порталом.
                    scale = 1f;
                    rotate = 0f;
                    alpha = local < .88f ? 1f : clamp01((1f - local) / .12f);
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

            alpha *= edgeFade(x, y, w, h);
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
            if (effect == PengramConfig.DELETE_EFFECT_PORTAL_BLOCKS && local > .18f) {
                // Сначала виден исходный фрагмент, затем его буквально замещает анимированный портал.
                if (local < .55f) {
                    paint.setAlpha((int) (255 * alpha * (1f - (local - .18f) / .37f)));
                    canvas.drawBitmap(bitmap, src, dst, paint);
                }
                final float portalAlpha = clamp01((local - .18f) / .24f) * alpha;
                glow.setStyle(Paint.Style.FILL);
                glow.setColor(((cx + cy) & 1) == 0 ? 0xFF42106F : 0xFF7B25B8);
                glow.setAlpha((int) (255 * portalAlpha));
                canvas.drawRect(dst, glow);
                glow.setColor(0xFFC15BFF);
                glow.setAlpha((int) (145 * portalAlpha * (.65f + .35f * (float) Math.sin(t * 18f + a))));
                canvas.drawRect(dst.left + w * .18f, dst.top + h * .18f, dst.right - w * .22f, dst.bottom - h * .22f, glow);
                glow.setAlpha(255);
            } else {
                canvas.drawBitmap(bitmap, src, dst, paint);
            }
            canvas.restore();
        }

        if (effect == PengramConfig.DELETE_EFFECT_BURN) {
            drawFireLine(canvas, t);
        }
    }

    /** Блочная рамка фиолетового портала собирается вокруг сообщения и остаётся, пока оно осыпается внутрь. */
    private void drawPortalBlocks(Canvas canvas, float t) {
        final float appear = clamp01(t / .2f);
        final float block = Math.max(AndroidUtilities.dp(7), Math.min(AndroidUtilities.dp(13), Math.min(bmpW, bmpH) / 7f));
        glow.setStyle(Paint.Style.FILL);
        for (float x = 0; x < bmpW; x += block) {
            drawPortalBlock(canvas, x, -block * .72f, block, appear, (int) (x / block));
            drawPortalBlock(canvas, x, bmpH - block * .28f, block, appear, (int) (x / block) + 17);
        }
        for (float y = block; y < bmpH - block; y += block) {
            drawPortalBlock(canvas, -block * .72f, y, block, appear, (int) (y / block) + 31);
            drawPortalBlock(canvas, bmpW - block * .28f, y, block, appear, (int) (y / block) + 47);
        }
        glow.setAlpha(255);
    }

    private void drawPortalBlock(Canvas canvas, float x, float y, float size, float appear, int seed) {
        final float phase = .72f + .28f * (float) Math.sin(seed * 2.17f + appear * 9f);
        glow.setColor((seed & 1) == 0 ? 0xFF32105E : 0xFF6D21A8);
        glow.setAlpha((int) (255 * appear));
        canvas.drawRect(x, y, x + size, y + size, glow);
        glow.setColor(0xFFB34CFF);
        glow.setAlpha((int) (150 * appear * phase));
        canvas.drawRect(x + size * .18f, y + size * .18f, x + size * .58f, y + size * .58f, glow);
    }

    /** у «сгорания» есть живая линия огня, которая съедает сообщение снизу вверх */
    private void drawFireLine(Canvas canvas, float t) {
        final float edge = bmpH * (1f - Math.min(1f, t * 1.25f));
        if (edge <= 0 || t >= 0.92f) {
            return;
        }
        final float band = AndroidUtilities.dp(22);
        firePath.reset();
        firePath.moveTo(0, edge + band);
        final int steps = 16;
        for (int a = 0; a <= steps; ++a) {
            final float x = bmpW * a / (float) steps;
            final float wobble = (float) Math.sin(t * 9f + a * 1.3f) * AndroidUtilities.dp(3)
                    + (float) Math.sin(t * 17f + a * 0.7f) * AndroidUtilities.dp(2);
            firePath.lineTo(x, edge + wobble);
        }
        firePath.lineTo(bmpW, edge + band);
        firePath.close();

        fireMatrix.setTranslate(0, edge);
        fireShader.setLocalMatrix(fireMatrix);
        glow.setShader(fireShader);
        glow.setAlpha(255);
        canvas.drawPath(firePath, glow);
        glow.setShader(null);
    }

    /**
     * Чем ближе клетка к краю видимой области, тем она прозрачнее:
     * так ничего не «обрубается» и не вылезает за экран.
     */
    private float edgeFade(float x, float y, float w, float h) {
        if (!hasBounds) {
            return 1f;
        }
        final float left = originX + x;
        final float top = originY + y;
        final float right = left + w;
        final float bottom = top + h;
        final float margin = AndroidUtilities.dp(18);
        float fade = 1f;
        fade = Math.min(fade, clamp01((right - bounds.left) / margin));
        fade = Math.min(fade, clamp01((bounds.right - left) / margin));
        fade = Math.min(fade, clamp01((bottom - bounds.top) / margin));
        fade = Math.min(fade, clamp01((bounds.bottom - top) / margin));
        return fade;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }

    /**
     * Куда лечь снимку и где проходит граница — всё в координатах окна.
     * Перед каждым кадром пересчитываем на свои координаты, поэтому неважно,
     * куда именно контейнер положил саму накладку.
     */
    public void setGeometryInWindow(float x, float y, RectF clipInWindow) {
        windowX = x;
        windowY = y;
        if (clipInWindow != null && clipInWindow.width() > 0 && clipInWindow.height() > 0) {
            windowClip.set(clipInWindow);
            hasBounds = true;
        }
        syncGeometry();
    }

    /** перевод координат окна в свои: вызывается каждый кадр */
    private void syncGeometry() {
        try {
            getLocationInWindow(selfAt);
        } catch (Throwable e) {
            selfAt[0] = 0;
            selfAt[1] = 0;
        }
        originX = windowX - selfAt[0];
        originY = windowY - selfAt[1];
        if (hasBounds) {
            bounds.set(windowClip.left - selfAt[0], windowClip.top - selfAt[1],
                    windowClip.right - selfAt[0], windowClip.bottom - selfAt[1]);
        }
    }

    /** подписаться на прокрутку списка, чтобы эффект ехал вместе с сообщениями */
    private void followScroll(androidx.recyclerview.widget.RecyclerView list) {
        followList = list;
        scrollListener = new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                // следуем только за живой прокруткой; служебные сдвиги списка
                // после удаления сообщения эффект двигать не должны
                if (recyclerView.getScrollState() == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                windowX -= dx;
                windowY -= dy;
                invalidate();
            }
        };
        list.addOnScrollListener(scrollListener);
    }

    private void stopFollowing() {
        if (followList != null && scrollListener != null) {
            try {
                followList.removeOnScrollListener(scrollListener);
            } catch (Throwable ignore) {
            }
        }
        followList = null;
        scrollListener = null;
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        stopFollowing();
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
        return snapshot(view, null);
    }

    /**
     * Снимок именно сообщения, а не всей строки списка.
     * У ячейки чата спрашиваем точные границы пузыря — ровно так же, как это
     * делает телеграмовский «танос». В offset возвращается сдвиг снимка
     * относительно левого верхнего угла вида.
     */
    public static Bitmap snapshot(View view, int[] offset) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        if (offset != null) {
            offset[0] = 0;
            offset[1] = 0;
        }
        try {
            int left = 0;
            int top = 0;
            int right = view.getWidth();
            int bottom = view.getHeight();
            if (view instanceof org.telegram.ui.Cells.ChatMessageCell) {
                final org.telegram.ui.Cells.ChatMessageCell cell = (org.telegram.ui.Cells.ChatMessageCell) view;
                final int pad = AndroidUtilities.dp(2);
                final int bl = cell.getBackgroundDrawableLeft() - pad;
                final int br = cell.getBackgroundDrawableRight() + pad;
                final int bt = cell.getBackgroundDrawableTop() - pad;
                final int bb = cell.getBackgroundDrawableBottom() + pad;
                if (br - bl > AndroidUtilities.dp(16) && bb - bt > AndroidUtilities.dp(12)) {
                    left = Math.max(0, bl);
                    top = Math.max(0, bt);
                    right = Math.min(view.getWidth(), br);
                    bottom = Math.min(view.getHeight(), bb);
                }
            }
            final int w = Math.min(right - left, AndroidUtilities.displaySize.x);
            final int h = Math.min(bottom - top, (int) (AndroidUtilities.displaySize.y * 1.2f));
            if (w <= 0 || h <= 0) {
                return null;
            }
            final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            if (left != 0 || top != 0) {
                canvas.translate(-left, -top);
            }
            view.draw(canvas);
            if (offset != null) {
                offset[0] = left;
                offset[1] = top;
            }
            return bitmap;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Ячейка списка занимает всю ширину экрана, а само сообщение — только пузырь
     * где-то внутри. Поэтому снимок обрезаем по непрозрачному содержимому:
     * иначе эффект рисуется по всей строке и выглядит «мимо сообщения».
     * В offset возвращается сдвиг обрезки относительно левого верхнего угла вида.
     */
    private static Bitmap cropToContent(Bitmap bitmap, int[] offset) {
        offset[0] = 0;
        offset[1] = 0;
        if (bitmap == null) {
            return null;
        }
        try {
            final int w = bitmap.getWidth();
            final int h = bitmap.getHeight();
            if (w <= 2 || h <= 2) {
                return bitmap;
            }
            final int step = h > 400 ? 3 : 2;
            final int[] row = new int[w];
            int left = w, top = -1, right = -1, bottom = -1;
            for (int y = 0; y < h; y += step) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1);
                int rowLeft = -1, rowRight = -1;
                for (int x = 0; x < w; ++x) {
                    if ((row[x] >>> 24) > 8) {
                        if (rowLeft < 0) {
                            rowLeft = x;
                        }
                        rowRight = x;
                    }
                }
                if (rowLeft < 0) {
                    continue;
                }
                if (top < 0) {
                    top = y;
                }
                bottom = y;
                left = Math.min(left, rowLeft);
                right = Math.max(right, rowRight);
            }
            if (top < 0 || right < 0) {
                return bitmap;   // пустой снимок — пусть решает вызывающий
            }
            final int pad = AndroidUtilities.dp(2);
            left = Math.max(0, left - pad);
            top = Math.max(0, top - step - pad);
            right = Math.min(w - 1, right + pad);
            bottom = Math.min(h - 1, bottom + step + pad);
            final int cw = right - left + 1;
            final int ch = bottom - top + 1;
            if (cw <= 0 || ch <= 0 || (cw == w && ch == h)) {
                return bitmap;
            }
            final Bitmap cropped = Bitmap.createBitmap(bitmap, left, top, cw, ch);
            if (cropped != bitmap) {
                bitmap.recycle();
            }
            offset[0] = left;
            offset[1] = top;
            return cropped;
        } catch (Throwable e) {
            return bitmap;
        }
    }

    /**
     * Проиграть эффект поверх контейнера на месте указанного вида.
     * Сам вид после этого может исчезнуть — анимация уже живёт отдельно.
     */
    public static boolean play(ViewGroup container, View view, int effect) {
        return play(container, view, effect, null);
    }

    /**
     * То же самое, но с явной областью, за которую выходить нельзя
     * (чат отдаёт сюда полосу между шапкой и полем ввода).
     */
    public static boolean play(ViewGroup container, View view, int effect, RectF clipInWindow) {
        if (container == null || view == null || effect == PengramConfig.DELETE_EFFECT_NONE) {
            return false;
        }
        final int[] shot = new int[2];
        final int[] crop = new int[2];
        final Bitmap bitmap = cropToContent(snapshot(view, shot), crop);
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 1 || bitmap.getHeight() <= 1) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return false;
        }

        // всё считаем в координатах окна: так эффект ложится ровно на сообщение,
        // куда бы контейнер ни положил саму накладку
        final int[] from = new int[2];
        view.getLocationInWindow(from);
        final float winX = from[0] + shot[0] + crop[0];
        final float winY = from[1] + shot[1] + crop[1];

        final RectF clip = new RectF();
        final ViewParent parent = view.getParent();
        final View frame = parent instanceof View ? (View) parent : container;
        final int[] frameAt = new int[2];
        frame.getLocationInWindow(frameAt);
        clip.set(frameAt[0], frameAt[1], frameAt[0] + frame.getWidth(), frameAt[1] + frame.getHeight());
        if (clipInWindow != null && clipInWindow.width() > 0 && clipInWindow.height() > 0) {
            clip.set(Math.max(clip.left, clipInWindow.left),
                    Math.max(clip.top, clipInWindow.top),
                    Math.min(clip.right, clipInWindow.right),
                    Math.min(clip.bottom, clipInWindow.bottom));
        }
        if (clip.width() <= 0 || clip.height() <= 0) {
            bitmap.recycle();
            return false;
        }

        final PengramDeleteEffectView effectView = new PengramDeleteEffectView(container.getContext(), bitmap, effect);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        try {
            container.addView(effectView, lp);
        } catch (Throwable e) {
            bitmap.recycle();
            return false;
        }
        effectView.setGeometryInWindow(winX, winY, clip);
        if (parent instanceof androidx.recyclerview.widget.RecyclerView) {
            effectView.followScroll((androidx.recyclerview.widget.RecyclerView) parent);
        }
        effectView.start(null);
        return true;
    }
}
