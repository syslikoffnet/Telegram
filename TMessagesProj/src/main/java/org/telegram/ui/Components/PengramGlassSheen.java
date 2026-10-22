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
    private boolean enabled;

    public void configure(boolean enabled, Theme.ResourcesProvider resources) {
        this.enabled = enabled;
        final int color = Theme.getColor(Theme.key_windowBackgroundWhite, resources);
        shader = new LinearGradient(0, 0, AndroidUtilities.dp(72), 0,
                new int[]{Theme.multAlpha(color, 0), Theme.multAlpha(color, .22f), Theme.multAlpha(color, 0)},
                new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
        paint.setShader(shader);
    }

    public void draw(Canvas canvas, View view, Rect bounds) {
        if (!org.telegram.messenger.SharedConfig.animationsEnabled()) return;
        if (!enabled || shader == null || !PengramConfig.isGlassSheenEnabled()
                || PengramConfig.getGlassGlow() == 0 || bounds.isEmpty()) return;
        final float phase = (SystemClock.uptimeMillis() % 4000) / 4000f;
        final float x = bounds.left - AndroidUtilities.dp(72)
                + (bounds.width() + AndroidUtilities.dp(144)) * phase;
        matrix.setTranslate(x, bounds.top);
        shader.setLocalMatrix(matrix);
        paint.setAlpha(Math.min(255, Math.round(255 * PengramConfig.getGlassGlow() / 100f)));
        // Тонкая полоса на внутренней кромке не перекрывает текст и иконки.
        final float inset = AndroidUtilities.dp(12);
        canvas.drawRect(bounds.left + inset, bounds.top + AndroidUtilities.dp(7),
                bounds.right - inset, bounds.top + AndroidUtilities.dp(8), paint);
        if (view.isShown() && view.isAttachedToWindow()) view.postInvalidateDelayed(32);
    }
}
