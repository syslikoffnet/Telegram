package org.telegram.ui.Components;

import android.view.View;
import android.view.ViewTreeObserver;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;

import java.util.WeakHashMap;
import java.util.concurrent.Callable;

/**
 * Прокрутка-цилиндр: видимые ячейки слегка поворачиваются вокруг горизонтальной
 * оси так, будто сообщения наматываются на невидимый валик — центр экрана
 * остаётся плоским, края заворачиваются назад. Считается каждый кадр из текущих
 * координат ячеек (пока лист прикреплён к окну), поэтому и при инерционном
 * скролле, и в покое картинка остаётся «обнятой» валиком.
 *
 * Включается тумблером chatCylinderScroll (по умолчанию выключен); выключение
 * возвращают ячейкам исходные повороты/масштаб/камеру.
 */
public final class PengramCylinderScroll implements ViewTreeObserver.OnPreDrawListener {

    /** Радиус «валика» в dp: больше — мягче изгиб (1400dp ≈ нежная дуга). */
    private static final float RADIUS_DP = 1400f;
    /** Удлинение камеры: перспектива без резкого «обрезания» ячеек. */
    private static final float CAMERA_BOOST = 4f;

    private final RecyclerView target;
    private final Callable<Boolean> enabled;
    /** камера уже удлинена (WeakHashMap — не держим ячейки после GC списка). */
    private final WeakHashMap<View, Boolean> cameraBoosted = new WeakHashMap<>();
    private boolean applied;
    private boolean observing;

    private PengramCylinderScroll(RecyclerView target, Callable<Boolean> enabled) {
        this.target = target;
        this.enabled = enabled;
    }

    public static PengramCylinderScroll attach(RecyclerView target, Callable<Boolean> enabled) {
        final PengramCylinderScroll scroll = new PengramCylinderScroll(target, enabled);
        target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                scroll.observe(true);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                scroll.observe(false);
            }
        });
        if (target.isAttachedToWindow()) {
            scroll.observe(true);
        }
        return scroll;
    }

    private void observe(boolean on) {
        if (observing == on) {
            return;
        }
        observing = on;
        final ViewTreeObserver observer = target.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        if (on) {
            observer.addOnPreDrawListener(this);
            target.invalidate();
        } else {
            observer.removeOnPreDrawListener(this);
            reset();
        }
    }

    private boolean wants() {
        try {
            return Boolean.TRUE.equals(enabled.call());
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean onPreDraw() {
        apply();
        return true;
    }

    private void apply() {
        if (!target.isAttachedToWindow()) {
            return;
        }
        if (!wants()) {
            if (applied) {
                reset();
            }
            return;
        }
        final int count = target.getChildCount();
        if (count == 0) {
            return;
        }
        final float radius = RADIUS_DP * AndroidUtilities.density;
        final float halfHeight = target.getHeight() / 2f;
        final float anchor = halfHeight;
        final float maxAngle = Math.max(halfHeight / radius * (180f / (float) Math.PI), 1f);
        for (int i = 0; i < count; i++) {
            final View child = target.getChildAt(i);
            if (child == null) {
                continue;
            }
            if (!cameraBoosted.containsKey(child)) {
                child.setCameraDistance(child.getCameraDistance() * CAMERA_BOOST);
                cameraBoosted.put(child, Boolean.TRUE);
            }
            final float centerY = child.getTop() + child.getHeight() / 2f;
            // центр экрана — прямой; выше центра верх уходит назад (+),
            // ниже центра верх приближается к зрителю (−): вид намотки на валик
            final float angle = (anchor - centerY) / radius * (180f / (float) Math.PI);
            if (Math.abs(child.getRotationX() - angle) > 0.04f) {
                child.setRotationX(angle);
            }
            final float k = Math.min(Math.abs(angle) / maxAngle, 1f);
            final float scale = 1f - k * 0.05f;
            if (Math.abs(child.getScaleX() - scale) > 0.002f) {
                child.setScaleX(scale);
                child.setScaleY(scale);
            }
        }
        applied = true;
    }

    private void reset() {
        applied = false;
        final int count = target.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = target.getChildAt(i);
            if (child == null) {
                continue;
            }
            child.setRotationX(0f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            if (cameraBoosted.remove(child) != null) {
                child.setCameraDistance(child.getCameraDistance() / CAMERA_BOOST);
            }
        }
        cameraBoosted.clear();
    }
}
