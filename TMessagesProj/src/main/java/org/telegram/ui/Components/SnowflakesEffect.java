/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import org.telegram.messenger.PengramConfig;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class SnowflakesEffect {
    private final BatchParticlesDrawHelper.BatchParticlesBuffer batchParticlesBuffer;
    private final Paint batchParticlesPaint;

    private final Paint particlePaint;
    private final Paint particleThinPaint;
    private final Paint bitmapPaint = new Paint();
    private int colorKey = Theme.key_actionBarDefaultTitle;
    private int forcedColor;
    private final int viewType;
    private final int maxCount;

    Bitmap particleBitmap;

    private long lastAnimationTime;
    private final CustomParticle[] customParticles = new CustomParticle[300];
    private final Paint customPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path customPath = new Path();
    private Bitmap sunBitmap; // Created once, only if the Sun style is selected.
    private static final char[] MATRIX_DIGITS = "0123456789".toCharArray();
    private long customTime;
    private int customMode = -1;

    private static class CustomParticle {
        float x, y, size, speed, phase, spin;
    }

    /** Draw one stylized sun into a tiny reusable sprite; no font/emoji dependencies. */
    private static Bitmap createSunBitmap() {
        final int size = Math.max(1, dp(40));
        final float center = size / 2f;
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setShader(new RadialGradient(center, center, dp(17),
                new int[]{0x60ffcf65, 0x24ffb944, 0x00ffb944},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(center, center, dp(17), halo);

        final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
        sun.setStrokeCap(Paint.Cap.ROUND);
        for (int layer = 0; layer < 2; layer++) {
            sun.setColor(layer == 0 ? 0xb986440d : 0xffffbd42);
            sun.setStrokeWidth(dp(layer == 0 ? 3f : 1.8f));
            for (int i = 0; i < 8; i++) {
                final double angle = i * Math.PI / 4;
                final float dx = (float) Math.cos(angle);
                final float dy = (float) Math.sin(angle);
                canvas.drawLine(center + dx * dp(10), center + dy * dp(10),
                        center + dx * dp(14), center + dy * dp(14), sun);
            }
        }
        sun.setStyle(Paint.Style.FILL);
        sun.setColor(0xffad601c);
        canvas.drawCircle(center, center, dp(8), sun);
        sun.setColor(0xffffc84d);
        canvas.drawCircle(center, center, dp(6.6f), sun);
        sun.setColor(0xfffff2a9);
        canvas.drawCircle(center - dp(1.6f), center - dp(1.8f), dp(2.2f), sun);
        return bitmap;
    }

    /** Reuses particles, paints and the sun sprite across frames. */
    private void drawCustom(View parent, Canvas canvas) {
        final int mode = PengramConfig.getParticleMode();
        final int requestedCount = PengramConfig.getParticleCount();
        // Keep the small header readable at high density; suns are deliberately larger.
        final int count = mode == PengramConfig.PARTICLE_SUN
                ? (viewType == 0 ? Math.min(24, Math.max(8, requestedCount / 4))
                        : Math.max(10, requestedCount / 2))
                : (viewType == 0 ? Math.min(120, requestedCount) : requestedCount);
        final float opacity = PengramConfig.getParticleAlpha() / 100f;
        final float speed = PengramConfig.getParticleSpeed();
        final float rotation = PengramConfig.getParticleRotation();
        final int width = parent.getMeasuredWidth(), height = parent.getMeasuredHeight();
        if (width <= 0 || height <= 0) return;
        final long now = android.os.SystemClock.uptimeMillis();
        final float dt = customTime == 0 ? 0 : Math.max(0, Math.min(40, now - customTime)) / 1000f;
        customTime = now;
        if (mode != customMode) {
            customMode = mode;
            java.util.Arrays.fill(customParticles, null);
        }
        if (mode == PengramConfig.PARTICLE_SUN && sunBitmap == null) {
            sunBitmap = createSunBitmap();
        }
        final float invSwayHeight = 1f / Math.max(1, dp(35));
        final float sway = dp(mode == PengramConfig.PARTICLE_SUN ? 9 : 3) * speed;
        final float edge = dp(mode == PengramConfig.PARTICLE_SUN ? 20 : 12);
        final boolean isSun = mode == PengramConfig.PARTICLE_SUN;
        final float verticalSpeed = speed * (isSun ? -0.65f : mode == 3 ? 2.3f : 1f);
        final float angularSpeed = rotation * (isSun ? 30f : 105f);
        final float alphaBase = 255f * opacity;
        final float sunScaleBase = 1f / Math.max(1, dp(20));
        final float invHeight = 1f / height;
        final int tint = mode == 1 ? 0xffffa8cc : mode == 2 ? 0xff79f7b2
                : mode == 4 ? 0xffffba63 : color;
        customPaint.setShader(null);
        customPaint.setStyle(Paint.Style.FILL);
        customPaint.setColor(tint);
        for (int i = 0; i < count; i++) {
            CustomParticle p = customParticles[i];
            if (p == null) {
                p = new CustomParticle();
                customParticles[i] = p;
                p.x = Utilities.random.nextFloat() * width;
                p.y = Utilities.random.nextFloat() * height;
                p.size = mode == PengramConfig.PARTICLE_SUN
                        ? dp(8f + Utilities.random.nextFloat() * 4f)
                        : dp(2.5f + Utilities.random.nextFloat() * 3f);
                p.speed = dp(mode == PengramConfig.PARTICLE_SUN
                        ? 9 + Utilities.random.nextFloat() * 10
                        : 12 + Utilities.random.nextFloat() * 22);
                p.phase = Utilities.random.nextFloat() * 6.28f;
                p.spin = Utilities.random.nextFloat() * 360f;
            }
            p.y += dt * p.speed * verticalSpeed;
            p.x += dt * (float) Math.sin(p.phase + p.y * invSwayHeight) * sway;
            p.spin += dt * angularSpeed;
            if (isSun ? p.y < -edge : p.y > height + edge) {
                p.y = isSun ? height + edge : -edge;
                p.x = Utilities.random.nextFloat() * width;
            }
            if (p.x < 0) p.x += width;
            if (p.x > width) p.x -= width;
            final float shimmer = (float) Math.sin(p.phase + p.y * invHeight * 3.14f);
            customPaint.setAlpha(Math.max(0, Math.min(255,
                    (int) (alphaBase * (isSun ? 0.75f + 0.25f * shimmer : 0.6f + 0.4f * shimmer)))));
            canvas.save();
            canvas.translate(p.x, p.y);
            if (isSun) {
                canvas.rotate(p.spin);
                final float scale = p.size * sunScaleBase;
                canvas.scale(scale, scale);
                canvas.drawBitmap(sunBitmap, -sunBitmap.getWidth() / 2f, -sunBitmap.getHeight() / 2f, customPaint);
            } else if (mode == 1 || mode == 4) {
                canvas.rotate(p.spin);
                customPath.reset();
                customPath.moveTo(0, -p.size);
                customPath.quadTo(p.size * 1.5f, 0, 0, p.size);
                customPath.quadTo(-p.size * 0.8f, 0, 0, -p.size);
                canvas.drawPath(customPath, customPaint);
            } else if (mode == 2) {
                customPaint.setTextSize(p.size * 3f);
                canvas.drawText(MATRIX_DIGITS, i % 10, 1, 0, 0, customPaint);
            } else if (mode == 3) {
                customPaint.setStrokeWidth(Math.max(1, p.size / 3));
                canvas.drawLine(0, 0, -p.size / 3, p.size * 3, customPaint);
            } else {
                canvas.drawCircle(0, 0, p.size / 2, customPaint);
            }
            canvas.restore();
        }
        parent.postInvalidateDelayed(32);
    }

    private class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float velocity;
        float alpha;
        float lifeTime;
        float currentTime;
        float scale;
        int type;

        public void draw(Canvas canvas) {
            switch (type) {
                case 0: {
                    particlePaint.setAlpha((int) (255 * alpha));
                    canvas.drawPoint(x, y, particlePaint);
                    break;
                }
                case 1:
                default: {
                    if (particleBitmap == null) {
                        particleBitmap = createParticlesBitmap(false);
                    }
                    bitmapPaint.setAlpha((int) (255 * alpha));
                    canvas.save();
                    canvas.scale(scale, scale, x, y);
                    canvas.drawBitmap(particleBitmap, x, y, bitmapPaint);
                    canvas.restore();
                    break;
                }
            }

        }
    }

    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Particle> freeParticles = new ArrayList<>();

    private int color;

    public SnowflakesEffect(int viewType) {
        this.viewType = viewType;
        this.maxCount = viewType == 0 ? 100 : 300;
        particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        particlePaint.setStrokeWidth(dp(1.5f));
        particlePaint.setStrokeCap(Paint.Cap.ROUND);
        particlePaint.setStyle(Paint.Style.STROKE);

        particleThinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        particleThinPaint.setStrokeWidth(dp(0.5f));
        particleThinPaint.setStrokeCap(Paint.Cap.ROUND);
        particleThinPaint.setStyle(Paint.Style.STROKE);

        if (BatchParticlesDrawHelper.isAvailable()) {
            batchParticlesBuffer = new BatchParticlesDrawHelper.BatchParticlesBuffer(maxCount);
            batchParticlesPaint = BatchParticlesDrawHelper.createBatchParticlesPaint(createParticlesBitmap(true));
        } else {
            batchParticlesBuffer = null;
            batchParticlesPaint = null;
        }

        updateColors();

        for (int a = 0; a < 20; a++) {
            freeParticles.add(new Particle());
        }
    }

    public void setForcedColor(int forcedColor) {
        this.forcedColor = forcedColor;
        updateColors();
    }

    public void setColorKey(int key) {
        colorKey = key;
        updateColors();
    }

    public void updateColors() {
        final int color = forcedColor != 0 ? forcedColor : Theme.getColor(colorKey) & 0xffe6e6e6;
        if (this.color != color) {
            this.color = color;
            particlePaint.setColor(color);
            particleThinPaint.setColor(color);
        }
    }

    private void updateParticles(long dt) {
        int count = particles.size();
        for (int a = 0; a < count; a++) {
            Particle particle = particles.get(a);
            if (particle.currentTime >= particle.lifeTime) {
                if (freeParticles.size() < 40) {
                    freeParticles.add(particle);
                }
                particles.remove(a);
                a--;
                count--;
                continue;
            }
            if (viewType == 0) {
                if (particle.currentTime < 200.0f) {
                    particle.alpha = AndroidUtilities.accelerateInterpolator.getInterpolation(particle.currentTime / 200.0f);
                } else {
                    particle.alpha = 1.0f - AndroidUtilities.decelerateInterpolator.getInterpolation((particle.currentTime - 200.0f) / (particle.lifeTime - 200.0f));
                }
            } else {
                if (particle.currentTime < 200.0f) {
                    particle.alpha = AndroidUtilities.accelerateInterpolator.getInterpolation(particle.currentTime / 200.0f);
                } else if (particle.lifeTime - particle.currentTime < 2000) {
                    particle.alpha = AndroidUtilities.decelerateInterpolator.getInterpolation((particle.lifeTime - particle.currentTime) / 2000);
                }
            }
            particle.x += particle.vx * particle.velocity * dt / 500.0f;
            particle.y += particle.vy * particle.velocity * dt / 500.0f;
            particle.currentTime += dt;
        }
    }

    public void onDraw(View parent, Canvas canvas) {
        if (parent == null || canvas == null) {
            return;
        }
        // This is an explicit visual preference; Lite Mode only controls the seasonal animation.
        if (PengramConfig.isForcedSnow()) {
            drawCustom(parent, canvas);
            return;
        }
        customTime = 0;
        if (!LiteMode.isEnabled(LiteMode.FLAG_CHAT_BACKGROUND)) {
            return;
        }

        if (batchParticlesBuffer != null) {
            final int count = Math.min(maxCount, particles.size());
            final int texSize = dp(TEXTURE_SIZE_DP);

            for (int a = 0; a < count; a++) {
                Particle particle = particles.get(a);
                final float x = particle.x, y = particle.y;
                final float h = particle.type == 0 ? (texSize / 2f) : (texSize / 2f * particle.scale);
                final float tx = particle.type == 0 ? texSize : 0;

                batchParticlesBuffer.setParticleColor(a, ColorUtils.setAlphaComponent(color, (int) (255 * particle.alpha)));
                batchParticlesBuffer.setParticleVertexCords(a, x - h, y - h, x + h, y + h);
                batchParticlesBuffer.setParticleTextureCords(a, tx, 0, tx + texSize, texSize);
            }
            BatchParticlesDrawHelper.draw(canvas, batchParticlesBuffer, count, batchParticlesPaint);
        } else {
            final int count = particles.size();
            for (int a = 0; a < count; a++) {
                Particle particle = particles.get(a);
                particle.draw(canvas);
            }
        }

        int createPerFrame = viewType == 0 ? 1 : 10;
        if (particles.size() < maxCount) {
            for (int i = 0; i < createPerFrame; i++) {
                if (particles.size() < maxCount && Utilities.random.nextFloat() > 0.7f) {
                    int statusBarHeight = AndroidUtilities.statusBarHeight;
                    float cx = Utilities.random.nextFloat() * parent.getMeasuredWidth();
                    float cy;
                    if (viewType == 0) {
                        cy = statusBarHeight + Utilities.random.nextFloat() * (parent.getMeasuredHeight() - dp(20) - statusBarHeight);
                    } else {
                        cy = Utilities.random.nextFloat() * (parent.getMeasuredHeight());
                    }

                    int angle = Utilities.random.nextInt(40) - 20 + 90;
                    float vx = (float) Math.cos(Math.PI / 180.0 * angle);
                    float vy = (float) Math.sin(Math.PI / 180.0 * angle);

                    Particle newParticle;
                    if (!freeParticles.isEmpty()) {
                        newParticle = freeParticles.get(0);
                        freeParticles.remove(0);
                    } else {
                        newParticle = new Particle();
                    }
                    newParticle.x = cx;
                    newParticle.y = cy;

                    newParticle.vx = vx;
                    newParticle.vy = vy;

                    newParticle.alpha = 0.0f;
                    newParticle.currentTime = 0;

                    newParticle.scale = Utilities.random.nextFloat() * 1.2f;
                    newParticle.type = Utilities.random.nextInt(2);

                    if (viewType == 0) {
                        newParticle.lifeTime = 2000 + Utilities.random.nextInt(100);
                    } else {
                        newParticle.lifeTime = 3000 + Utilities.random.nextInt(2000);
                    }
                    newParticle.velocity = 20.0f + Utilities.random.nextFloat() * 4.0f;
                    particles.add(newParticle);
                }
            }
        }

        long newTime = System.currentTimeMillis();
        long dt = Math.min(17, newTime - lastAnimationTime);
        updateParticles(dt);
        lastAnimationTime = newTime;
        parent.invalidate();
    }


    private static final int TEXTURE_SIZE_DP = 10;
    private static Bitmap createParticlesBitmap(boolean useFull) {
        final Paint particleThinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        particleThinPaint.setStrokeWidth(dp(0.5f));
        particleThinPaint.setStrokeCap(Paint.Cap.ROUND);
        particleThinPaint.setStyle(Paint.Style.STROKE);
        particleThinPaint.setColor(0xFFFFFFFF);

        final Bitmap particleBitmap = Bitmap.createBitmap(useFull ? dp(TEXTURE_SIZE_DP * 2) : dp(TEXTURE_SIZE_DP), dp(TEXTURE_SIZE_DP), Bitmap.Config.ARGB_8888);
        final Canvas bitmapCanvas = new Canvas(particleBitmap);
        final float px = dpf2(2.0f) * 2;
        final float px1 = -dpf2(0.57f) * 2;
        final float py1 = dpf2(1.55f) * 2;
        final float x = dp(TEXTURE_SIZE_DP / 2f);
        final float y = dp(TEXTURE_SIZE_DP / 2f);
        final float angleDiff = (float) (Math.PI / 180 * 60);

        float angle = (float) -Math.PI / 2;
        for (int a = 0; a < 6; a++) {
            float x1 = (float) Math.cos(angle) * px;
            float y1 = (float) Math.sin(angle) * px;
            float cx = x1 * 0.66f;
            float cy = y1 * 0.66f;
            bitmapCanvas.drawLine(x, y, x + x1, y + y1, particleThinPaint);

            float angle2 = (float) (angle - Math.PI / 2);
            x1 = (float) (Math.cos(angle2) * px1 - Math.sin(angle2) * py1);
            y1 = (float) (Math.sin(angle2) * px1 + Math.cos(angle2) * py1);
            bitmapCanvas.drawLine(x + cx, y + cy, x + x1, y + y1, particleThinPaint);

            x1 = (float) (-Math.cos(angle2) * px1 - Math.sin(angle2) * py1);
            y1 = (float) (-Math.sin(angle2) * px1 + Math.cos(angle2) * py1);
            bitmapCanvas.drawLine(x + cx, y + cy, x + x1, y + y1, particleThinPaint);

            angle += angleDiff;
        }

        if (useFull) {
            final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            particlePaint.setStrokeWidth(dp(1.5f));
            particlePaint.setStrokeCap(Paint.Cap.ROUND);
            particlePaint.setStyle(Paint.Style.STROKE);
            particlePaint.setColor(0xFFFFFFFF);
            bitmapCanvas.drawPoint(dp(TEXTURE_SIZE_DP * 1.5f), dp(TEXTURE_SIZE_DP / 2f), particlePaint);
        }

        return particleBitmap;
    }
}
