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
 * «переворачивало» целиком. Шейдер (AGSL, res/raw/pengram_cylinder_bend)
 * гнёт каждую строку распределённо: ровно в точке пересечения с центром
 * экрана лист остаётся прямым, к краям кривизна нарастает плавно, дальние
 * участки сужаются в перспективу и уходят за поверхность экрана. Соседние
 * строки считаются от одной глобальной оси — общий шов всегда совпадает и
 * не расходится. Рисунок детей (clipChildren у ячеек отключён) на время
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

    /** Радиус «валика» при100 % силы: меньше — сочнее изгиб, но без «лсда». */
    private static final float RADIUS_TIGHT_DP = 760f;
    /** Радиус при0 % силы — прежняя нежная дуга. */
    private static final float RADIUS_GENTLE_DP = 1400f;
    /** Удлинение камеры для старого пути (API < 33): перспектива без обрезания. */
    private static final float CAMERA_BOOST = 4f;
    /** Перспективное сужение краёв при уходе вглубь (0 — без сужения). */
    private static final float PERSPECTIVE = 0.6f;

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
        private float radius, neutral, width, height;

        private Bend(String code) {
            shader = new RuntimeShader(code);
        }

        /** false — параметры не изменились, эффект ставить заново не нужно. */
        private boolean bind(float radius, float neutral, float width, float height) {
            if (effect != null && Math.abs(this.radius - radius) < 0.1f
                    && Math.abs(this.neutral - neutral) < 0.1f
                    && this.width == width && this.height == height) {
                return false;
            }
            this.radius = radius;
            this.neutral = neutral;
            this.width = width;
            this.height = height;
            shader.setFloatUniform("radius", radius);
            shader.setFloatUniform("neutral", neutral);
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("persp", PERSPECTIVE);
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
            if (warp) {
                // нейтраль — точка пересечения строки с центром экрана
                // (с учётом текущего сдвига): ровно там лист остаётся прямым,
                // сгиб распределён по всей высоте, «плитой» ничего не крутит
                final float neutral = anchor - (child.getTop() + child.getTranslationY());
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
                if (bend != null && bend.bind(radius, neutral, w, h)) {
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
                final float angle = (anchor - centerY) / radius * (180f / (float) Math.PI);
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
