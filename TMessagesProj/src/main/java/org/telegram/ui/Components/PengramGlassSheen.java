package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.ui.ActionBar.Theme;

/** Блик поверх готового размытия не требует повторного захвата сцены. */
public final class PengramGlassSheen {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private LinearGradient shader;
    private int lastColor = Integer.MIN_VALUE;
    private boolean enabled;
    private boolean selfTickPending;
    private long runStart = -1;
    /** Полный проход блика по ширине — как в исходной анимации. */
    private static final long SWEEP_MS = 4000;
    /** Поглощает единственный самопланируемый кадр затухания; любая поздняя внешняя
     * перерисовка (смена вкладки, скин) тут же запускает новый проход блика. */
    private static final long GRACE_MS = 100;

    public boolean consumeSelfTick() {
        final boolean was = selfTickPending;
        selfTickPending = false;
        return was;
    }

    public void configure(boolean enabled, Theme.ResourcesProvider resources) {
        this.enabled = enabled;
        final int color = Theme.getColor(Theme.key_windowBackgroundWhite, resources);
        if (shader == null || lastColor != color) {
            lastColor = color;
            shader = new LinearGradient(0, 0, AndroidUtilities.dp(72), 0,
                    new int[]{Theme.multAlpha(color, 0), Theme.multAlpha(color, .22f), Theme.multAlpha(color, 0)},
                    new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
        }
        paint.setShader(shader);
        // Свежая настройка — новый проход блика (обновление скина, смена вкладки, attach).
        runStart = SystemClock.uptimeMillis();
    }

    public void draw(Canvas canvas, View view, Rect bounds) {
        if (!org.telegram.messenger.SharedConfig.animationsEnabled()) {
            selfTickPending = false;
            return;
        }
        if (!enabled || shader == null || !PengramConfig.isGlassSheenEnabled()
                || PengramConfig.getGlassGlow() == 0 || bounds.isEmpty()) {
            selfTickPending = false;
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (runStart < 0) runStart = now;
        long elapsed = now - runStart;
        if (elapsed >= SWEEP_MS) {
            selfTickPending = false;
            // Проход окончен: в покое блик не рисуется и цикл перерисовок НЕ планируется —
            // иначе каждая стеклянная поверхность крутит вечный цикл ~30 fps и съедает GPU.
            if (elapsed < SWEEP_MS + GRACE_MS) return;
            runStart = now; // внешняя перерисовка спустя паузу — запускаем блик заново
            elapsed = 0;
        }
        final float phase = elapsed / (float) SWEEP_MS;
        final float x = bounds.left - AndroidUtilities.dp(72)
                + (bounds.width() + AndroidUtilities.dp(144)) * phase;
        matrix.setTranslate(x, bounds.top);
        shader.setLocalMatrix(matrix);
        paint.setAlpha(Math.min(255, Math.round(255 * PengramConfig.getGlassGlow() / 100f)));
        // Тонкая полоса на внутренней кромке не перекрывает текст и иконки.
        final float inset = AndroidUtilities.dp(12);
        canvas.drawRect(bounds.left + inset, bounds.top + AndroidUtilities.dp(7),
                bounds.right - inset, bounds.top + AndroidUtilities.dp(8), paint);
        if (view.isShown() && view.isAttachedToWindow()) {
            selfTickPending = true;
            if (view.isHardwareAccelerated()) {
                view.postInvalidateOnAnimation(
                        bounds.left,
                        Math.max(0, bounds.top + AndroidUtilities.dp(5)),
                        bounds.right,
                        bounds.top + AndroidUtilities.dp(10)
                );
            } else {
                view.postInvalidateDelayed(32);
            }
        }
    }
}
