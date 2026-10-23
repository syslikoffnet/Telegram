package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

/**
 * Pengram: сияние вокруг пингвина, живущее под музыку.
 *
 * Рисуем три вещи и ничего больше: мягкое свечение, которое дышит вместе с
 * громкостью, расходящиеся кольца на ударах и несколько искр по кругу.
 * Всё — обычным Canvas: слой лежит под 3D-пингвином, и тянуть ради него
 * второй GL-контекст было бы расточительно.
 */
public class PengramBeatHalo extends View {

    /** сколько колец может расходиться одновременно */
    private static final int RINGS = 4;
    /** искры по кругу: больше — каша, меньше — пусто */
    private static final int SPARKS = 7;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] ringProgress = new float[RINGS];
    private final float[] ringPower = new float[RINGS];
    private int ringIndex;

    private int accentColor = 0xFF5FD0A0;
    private float intensity = 1f;

    private float level;        // сглаженная громкость
    private float targetLevel;
    private float beat;         // затухающий отклик на удар

    private RadialGradient glowShader;
    private int shaderSize;
    private int shaderColor;

    private long lastFrame;
    private boolean animating;

    public PengramBeatHalo(Context context) {
        super(context);
        ringPaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStyle(Paint.Style.FILL);
    }

    public void setAccentColor(int color) {
        if (accentColor == color) {
            return;
        }
        accentColor = color;
        glowShader = null;
        invalidate();
    }

    /** 0 — совсем деликатно, 1 — как задумано, выше — «сочнее» */
    public void setIntensity(float value) {
        intensity = Math.max(0f, value);
    }

    public void onPulse(float level, float bass) {
        targetLevel = Math.max(level, bass * 0.8f);
        kick();
    }

    public void onBeat(float power) {
        if (power <= 0 || intensity <= 0) {
            return;
        }
        ringProgress[ringIndex] = 0f;
        ringPower[ringIndex] = Math.min(1f, power);
        ringIndex = (ringIndex + 1) % RINGS;
        beat = Math.min(1f, beat + 0.45f + power * 0.55f);
        kick();
    }

    /** музыка остановилась — всё гаснет само, но помочь циклу надо */
    public void reset() {
        targetLevel = 0;
        kick();
    }

    private void kick() {
        if (!animating) {
            animating = true;
            lastFrame = 0;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = android.os.SystemClock.elapsedRealtime();
        float dt = lastFrame == 0 ? 0.016f : (now - lastFrame) / 1000f;
        lastFrame = now;
        dt = Math.min(dt, 0.05f);

        level += (targetLevel - level) * Math.min(1f, dt * 9f);
        beat = Math.max(0f, beat - dt * 2.2f);

        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0 || intensity <= 0) {
            animating = false;
            return;
        }
        final float cx = w / 2f;
        final float cy = h / 2f;
        final float base = Math.min(w, h) / 2f;

        boolean alive = level > 0.01f || beat > 0.01f;

        // свечение: радиус дышит от громкости, яркость — от удара
        final float glowRadius = base * (0.62f + level * 0.3f + beat * 0.12f) * (0.85f + intensity * 0.15f);
        final int glowAlpha = (int) (((level * 90f) + beat * 70f) * Math.min(1.4f, intensity));
        if (glowAlpha > 2 && glowRadius > 1) {
            ensureShader((int) glowRadius);
            glowPaint.setShader(glowShader);
            glowPaint.setAlpha(Math.min(190, glowAlpha));
            canvas.save();
            canvas.translate(cx, cy);
            canvas.drawCircle(0, 0, glowRadius, glowPaint);
            canvas.restore();
        }

        // кольца: каждое расходится наружу и тает
        for (int i = 0; i < RINGS; i++) {
            if (ringPower[i] <= 0) {
                continue;
            }
            ringProgress[i] += dt * 1.35f;
            if (ringProgress[i] >= 1f) {
                ringPower[i] = 0;
                ringProgress[i] = 0;
                continue;
            }
            alive = true;
            final float p = ringProgress[i];
            final float eased = 1f - (1f - p) * (1f - p);   // быстро стартует, мягко уходит
            final float radius = base * (0.5f + eased * (0.45f + ringPower[i] * 0.3f) * intensity);
            final int alpha = (int) (150 * (1f - p) * ringPower[i] * Math.min(1.3f, intensity));
            if (alpha <= 1) {
                continue;
            }
            ringPaint.setColor(accentColor);
            ringPaint.setAlpha(alpha);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(2.5f) * (1f - p * 0.6f));
            canvas.drawCircle(cx, cy, radius, ringPaint);
        }

        // искры: медленно кружат, на ударах разлетаются чуть дальше
        final float spark = level * 0.7f + beat * 0.5f;
        if (spark > 0.04f) {
            alive = true;
            final float t = now / 1000f;
            sparkPaint.setColor(accentColor);
            for (int i = 0; i < SPARKS; i++) {
                final double a = t * (0.55f + i * 0.045f) + i * (Math.PI * 2 / SPARKS);
                final float r = base * (0.72f + 0.16f * (float) Math.sin(t * 1.7f + i) + spark * 0.22f * intensity);
                final float x = cx + (float) Math.cos(a) * r;
                final float y = cy + (float) Math.sin(a) * r;
                sparkPaint.setAlpha((int) Math.min(170, 200 * spark * Math.min(1.3f, intensity)));
                canvas.drawCircle(x, y, AndroidUtilities.dp(1.6f) + AndroidUtilities.dp(1.4f) * spark, sparkPaint);
            }
        }

        if (alive) {
            postInvalidateOnAnimation();
        } else {
            animating = false;
            lastFrame = 0;
        }
    }

    private void ensureShader(int radius) {
        final int size = Math.max(1, radius);
        if (glowShader != null && Math.abs(size - shaderSize) < AndroidUtilities.dp(4) && shaderColor == accentColor) {
            return;
        }
        shaderSize = size;
        shaderColor = accentColor;
        glowShader = new RadialGradient(0, 0, size,
                new int[]{
                        Color.argb(255, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                        Color.argb(90, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                        Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                },
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }
}
