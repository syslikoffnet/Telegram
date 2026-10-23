package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.ui.ActionBar.Theme;

/**
 * Декоративное «жидкое стекло» для плоских плашек: полупрозрачный тон по
 * плотности стекла (пользовательский ползунок), световой кант и бегущий блик.
 * Плашка закрепа перекрывает собой список — размыть «за собой» физически
 * нечего, поэтому работает тонированная плёнка, а не захват экрана.
 * Включение — ключом chatPinnedGlass (по умолчанию выключен).
 */
public final class PengramGlassTintView extends View {

    private final PengramGlassSheen sheen = new PengramGlassSheen();
    private final Theme.ResourcesProvider resources;
    private final float radius;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Rect sheenBounds = new Rect();
    private final Runnable skinUpdate = this::syncState;

    public PengramGlassTintView(Context context, Theme.ResourcesProvider resourcesProvider, float radiusDp) {
        super(context);
        resources = resourcesProvider;
        radius = AndroidUtilities.dp(radiusDp);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(AndroidUtilities.dp(1));
        setWillNotDraw(false);
        setClickable(false);
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                PengramGlassSkin.register(skinUpdate);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                PengramGlassSkin.unregister(skinUpdate);
            }
        });
        if (isAttachedToWindow()) {
            PengramGlassSkin.register(skinUpdate);
        }
        syncState();
    }

    /** Состояние тумблера читается снаружи: вкладка/скрытие + перерисовка. */
    public void syncState() {
        final boolean enabled = PengramConfig.isChatPinnedGlass();
        if (getVisibility() != (enabled ? VISIBLE : GONE)) {
            setVisibility(enabled ? VISIBLE : GONE);
        }
        if (enabled) {
            sheen.configure(PengramConfig.isGlassSheenEnabled(), resources);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!PengramConfig.isChatPinnedGlass()) return;

        final int window = Theme.getColor(Theme.key_windowBackgroundWhite, resources);
        final boolean lightBackground = (window & 0xff) > 128 && ((window >> 8) & 0xff) > 128 && ((window >> 16) & 0xff) > 128;
        final int opacity = Math.max(20, Math.min(90, PengramConfig.getGlassOpacity()));
        final int base = lightBackground ? 0xffffff : 0x000000;
        fillPaint.setColor(base | (int) (opacity * 2.55f) << 24);
        bounds.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(bounds, radius, radius, fillPaint);

        // световой кант: на светлой теме — тёмный, на тёмной — светлый
        strokePaint.setColor(lightBackground ? 0x22000000 : 0x59ffffff);
        final float inset = strokePaint.getStrokeWidth() / 2f;
        bounds.inset(inset, inset);
        canvas.drawRoundRect(bounds, radius, radius, strokePaint);
        bounds.inset(-inset, -inset);

        if (PengramConfig.isGlassSheenEnabled()) {
            sheenBounds.set(0, 0, getWidth(), getHeight());
            sheen.draw(canvas, this, sheenBounds);
        }
    }
}
