package org.telegram.ui.Components;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;

import java.util.WeakHashMap;
import java.util.concurrent.Callable;

/**
 * Прокрутка-цилиндр: лента ложится на невидимый валик. Сообщение НЕ
 * поворачивается жёсткой плитой вокруг шарнира — большие ячейки так
 * «переворачивало» целиком. Модель — ДВА ВАЛИКА, сверху и снизу, как на
 * эталонном видео: между касательными (radius от краёв) полотно полностью
 * плоское (сообщения не деформируются в центре), у краёв экрана оно
 * наматывается на валик — перспективное сужение и плавное погасание у самой
 * кромки, «как за экран». Касательные считаются от ОДНОЙ глобальной
 * геометрии — швы соседних строк совпадают точно, содержимое одного
 * сообщения движется неразрывно. Рисунок детей (clipChildren у ячеек отключён) на время
 * эффекта ограничивается границами ячейки (setClipBounds), поэтому медиа
 * не вылезает из полей сообщения. Обновление — каждый кадр из текущих
 * координат ячеек; покой и кадры без сдвига (GIF/resize) пропускаются
 * быстрым путём. На API &lt; 33 (нет RuntimeShader) работает прежний путь —
 * поворот вокруг ребра, ближнего к центру.
 *
 * Включается тумблером chatCylinderScroll (по умолчанию включён), сила —
 * ползунком cylinderScrollStrength (по умолчанию100 %, «самый сочный»);
 * выключение возвращает ячейкам исходные повороты/масштаб/камеру/шейдер.
 */
public final class PengramCylinderScroll implements ViewTreeObserver.OnPreDrawListener {

    /** Радиус валика при100 % силы: меньше — сочнее завиток у края экрана. */
    private static final float RADIUS_TIGHT_DP = 150f;
    /** Радиус валика при0 % силы — мягкий, почти прямой край. */
    private static final float RADIUS_GENTLE_DP = 340f;
    /** Удлинение камеры для старого пути (API < 33): перспектива без обрезания. */
    private static final float CAMERA_BOOST = 4f;
    /** Ось поворота старого пути не ближе этого к краю — угол остаётся мягким. */
    private static final float LEGACY_MIN_RADIUS_DP = 700f;
    /** Доля радиуса валика до касательной: у края экрана остаётся ~53° —
     * ряды гнутся, а не схлопываются в линию (иначе читается «переворот»). */
    private static final float ROLLER_EDGE_K = 0.8f;

    private final RecyclerView target;
    private final Callable<Boolean> enabled;
    /** камера уже удлинена (WeakHashMap — не держим ячейки после GC списка). */
    private final WeakHashMap<View, Boolean> cameraBoosted = new WeakHashMap<>();
    /** актуальные границы клипа каждой ячейки (снимается в reset()). */
    private final WeakHashMap<View, android.graphics.Rect> clipRects = new WeakHashMap<>();
    /** шейдер сгиба на ячейку: uniforms у RuntimeShader общие для всех его эффектов. */
    private final WeakHashMap<View, Bend> bends = new WeakHashMap<>();
    private String shaderCode;
    private boolean shaderFailed;
    private boolean applied;
    // Быстрый путь покоя: скролл/геометрия/сдвиги не менялись — кадр идентичен,
    // углы и шейдер заново не выставляем (перерисовки списка бывают из-за GIF/видео
    // и resize без прокрутки).
    private int lastScrollOffset = -1;
    private int lastChildCount = -1;
    private int lastTargetHeight = -1;
    private float lastLayoutSum;
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

    /** Своя копия шейдера на ячейку: значения uniforms общие для всех эффектов
     * одного RuntimeShader, а ячейки стоят на разных высотах. */
    private static final class Bend {
        private final RuntimeShader shader;
        private RenderEffect effect;
        private float localT, localB, roller, width, height;

        private Bend(String code) {
            shader = new RuntimeShader(code);
        }

        /** false — параметры не изменились, эффект ставить заново не нужно. */
        private boolean bind(float localT, float localB, float roller, float width, float height) {
            if (effect != null && Math.abs(this.localT - localT) < 0.1f
                    && Math.abs(this.localB - localB) < 0.1f
                    && Math.abs(this.roller - roller) < 0.1f
                    && this.width == width && this.height == height) {
                return false;
            }
            this.localT = localT;
            this.localB = localB;
            this.roller = roller;
            this.width = width;
            this.height = height;
            shader.setFloatUniform("localT", localT);
            shader.setFloatUniform("localB", localB);
            shader.setFloatUniform("roller", roller);
            shader.setFloatUniform("size", width, height);
            effect = RenderEffect.createRuntimeShaderEffect(shader, "img");
            return true;
        }
    }

    private boolean ensureWarp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shaderFailed) {
            return false;
        }
        if (shaderCode == null) {
            try {
                shaderCode = AndroidUtilities.readRes(R.raw.pengram_cylinder_bend);
            } catch (Throwable t) {
                shaderFailed = true;
            }
        }
        return !shaderFailed && shaderCode != null;
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
        final int scrollOffset = target.computeVerticalScrollOffset();
        final int targetHeight = target.getHeight();
        // сумма «верх + сдвиг» по всем строкам: аниматоры двигают ячейки и без
        // смены скролла — такой кадр обязан пересчитать сгиб, а не пропустить
        float layoutSum = 0f;
        for (int i = 0; i < count; i++) {
            final View child = target.getChildAt(i);
            if (child != null) {
                layoutSum += child.getTop() + child.getTranslationY();
            }
        }
        if (applied && scrollOffset == lastScrollOffset && count == lastChildCount
                && targetHeight == lastTargetHeight && Math.abs(layoutSum - lastLayoutSum) < 0.5f) {
            return;
        }
        lastScrollOffset = scrollOffset;
        lastChildCount = count;
        lastTargetHeight = targetHeight;
        lastLayoutSum = layoutSum;
        // сила:100 % — сочный, но читаемый изгиб,0 % — прежняя нежная дуга
        final float strength = PengramConfig.getCylinderScrollStrength() / 100f;
        final float radiusDp = RADIUS_GENTLE_DP + (RADIUS_TIGHT_DP - RADIUS_GENTLE_DP) * strength;
        final float radius = radiusDp * AndroidUtilities.density;
        final float anchor = target.getHeight() / 2f;
        // два валика: касательная на EDGE_K*radius от края — у самой кромки
        // остаётся ~53° намотки (сгиб без переворота), плоская середина intact
        final float inset = Math.min(radius * ROLLER_EDGE_K, targetHeight / 3f);
        boolean warp = ensureWarp();
        if (!warp && !bends.isEmpty()) {
            // шейдер не поднялся — снимаем эффекты и уходим на старый путь
            for (final View view : bends.keySet()) {
                view.setRenderEffect(null);
            }
            bends.clear();
        }
        for (int i = 0; i < count; i++) {
            final View child = target.getChildAt(i);
            if (child == null) {
                continue;
            }
            // ячейки списка не клипуются (clipChildren и у них самих выключен) —
            // без ограничения рисунок детей вылезал из полей сообщения
            // на соседей («лсд»). Держим ровно границы ячейки.
            final int w = child.getWidth();
            final int h = child.getHeight();
            if (w > 0 && h > 0) {
                android.graphics.Rect clip = clipRects.get(child);
                if (clip == null) {
                    clip = new android.graphics.Rect(0, 0, w, h);
                    clipRects.put(child, clip);
                    child.setClipBounds(clip);
                } else if (clip.right != w || clip.bottom != h) {
                    clip.set(0, 0, w, h);
                    child.setClipBounds(clip);
                }
            }
            if (warp && w > 0 && h > 0) {
                // Касательные валиков в локальных координатах ячейки: карта одна
                // на весь список (инсет одинаков), поэтому швы соседних строк
                // совпадают точно, а содержимое одного сообщения едет неразрывно.
                final float top = child.getTop() + child.getTranslationY();
                final float localT = inset - top;
                final float localB = (targetHeight - inset) - top;
                Bend bend = bends.get(child);
                if (bend == null) {
                    try {
                        bend = new Bend(shaderCode);
                    } catch (Throwable t) {
                        shaderFailed = true;
                        warp = false;
                    }
                    if (bend != null) {
                        bends.put(child, bend);
                    }
                }
                if (bend != null && bend.bind(localT, localB, radius, w, h)) {
                    child.setRenderEffect(bend.effect);
                }
            }
            if (!warp) {
                // старый путь (API < 33): поворот вокруг ребра, ближнего к центру —
                // общий край соседних строк остаётся «прибит» к валику
                if (!cameraBoosted.containsKey(child)) {
                    child.setCameraDistance(child.getCameraDistance() * CAMERA_BOOST);
                    cameraBoosted.put(child, Boolean.TRUE);
                }
                final float centerY = child.getTop() + child.getHeight() / 2f;
                child.setPivotX(child.getWidth() / 2f);
                child.setPivotY(centerY <= anchor ? child.getHeight() : 0f);
                // старый путь масштабируется отдельно: радиус валиков мал,
                // а дуге старого пути нужен мягкий знаменатель
                final float legacyRadius = Math.max(radius, AndroidUtilities.dp(LEGACY_MIN_RADIUS_DP));
                final float angle = (anchor - centerY) / legacyRadius * (180f / (float) Math.PI);
                if (Math.abs(child.getRotationX() - angle) > 0.04f) {
                    child.setRotationX(angle);
                }
            }
        }
        applied = true;
    }

    private void reset() {
        applied = false;
        if (!bends.isEmpty()) {
            for (final View view : bends.keySet()) {
                view.setRenderEffect(null);
            }
            bends.clear();
        }
        final int count = target.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = target.getChildAt(i);
            if (child == null) {
                continue;
            }
            child.setRotationX(0f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            child.setPivotX(child.getWidth() / 2f);
            child.setPivotY(child.getHeight() / 2f);
            if (clipRects.remove(child) != null) {
                child.setClipBounds(null);
            }
            if (cameraBoosted.remove(child) != null) {
                child.setCameraDistance(child.getCameraDistance() / CAMERA_BOOST);
            }
        }
        cameraBoosted.clear();
        clipRects.clear();
    }
}
