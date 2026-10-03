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
                default:
                    cellDelay[a] = 0;
                    break;
            }
        }
        if (effect == PengramConfig.DELETE_EFFECT_PUZZLE) {
            buildPuzzle();
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

    // ------------------------------------------------------- клеточные эффекты

    private void drawCells(Canvas canvas, float t) {
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
            canvas.drawBitmap(bitmap, src, dst, paint);
            canvas.restore();
        }

        if (effect == PengramConfig.DELETE_EFFECT_BURN) {
            drawFireLine(canvas, t);
        }
    }

    /** у «сгорания» есть живая линия огня, которая съедает сообщение снизу вверх */
    private void drawFireLine(Canvas canvas, float t) {
        final float edge = bmpH * (1f - Math.min(1f, t * 1.25f));
        if (edge <= 0 || t >= 0.92f) {
            return;
        }
        final float thickness = AndroidUtilities.dp(14);
        for (int a = 0; a < GRID_X; ++a) {
            final float cw = bmpW / (float) GRID_X;
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

    /** куда внутри экрана чата лечь снимку и где проходит граница списка */
    public void setGeometry(float x, float y, RectF clip) {
        originX = x;
        originY = y;
        if (clip != null && clip.width() > 0 && clip.height() > 0) {
            bounds.set(clip);
            hasBounds = true;
        }
    }

    /** подписаться на прокрутку списка, чтобы эффект ехал вместе с сообщениями */
    private void followScroll(androidx.recyclerview.widget.RecyclerView list) {
        followList = list;
        scrollListener = new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                originX -= dx;
                originY -= dy;
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
        return play(container, view, effect, null);
    }

    /**
     * То же самое, но с явной областью, за которую выходить нельзя
     * (чат отдаёт сюда полосу между шапкой и полем ввода).
     */
    public static boolean play(ViewGroup container, View view, int effect, RectF clipInContainer) {
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

        // область, за которую эффект не выйдет ни на пиксель
        final RectF clip = new RectF(0, 0, container.getWidth(), container.getHeight());
        final ViewParent parent = view.getParent();
        if (parent instanceof View) {
            final View list = (View) parent;
            final int[] listAt = new int[2];
            list.getLocationInWindow(listAt);
            clip.set(listAt[0] - to[0], listAt[1] - to[1],
                    listAt[0] - to[0] + list.getWidth(), listAt[1] - to[1] + list.getHeight());
        }
        if (clipInContainer != null && clipInContainer.width() > 0 && clipInContainer.height() > 0) {
            // пересечение: ни под поле ввода, ни под шапку ничего не заедет
            clip.set(Math.max(clip.left, clipInContainer.left),
                    Math.max(clip.top, clipInContainer.top),
                    Math.min(clip.right, clipInContainer.right),
                    Math.min(clip.bottom, clipInContainer.bottom));
        }
        if (clip.width() <= 0 || clip.height() <= 0) {
            bitmap.recycle();
            return false;
        }

        final PengramDeleteEffectView effectView = new PengramDeleteEffectView(container.getContext(), bitmap, effect);
        effectView.setGeometry(from[0] - to[0], from[1] - to[1], clip);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        try {
            container.addView(effectView, lp);
        } catch (Throwable e) {
            bitmap.recycle();
            return false;
        }
        if (parent instanceof androidx.recyclerview.widget.RecyclerView) {
            effectView.followScroll((androidx.recyclerview.widget.RecyclerView) parent);
        }
        effectView.start(null);
        return true;
    }
}
