package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
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
 * быстрым путём. На API &lt; 33 (нет RuntimeShader) и при сбое шейдера ту же
 * самую карту исполняет PengramCylinderWarp: содержимое ячейки захватывается
 * в bitmap на изменение контента и гнётся через Canvas.drawVertices —
 * настоящий сгиб «по валику», а не наклон плитой.
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
    /** Доля радиуса валика до касательной: у края экрана остаётся ~53° —
     * ряды гнутся, а не схлопываются в линию (иначе читается «переворот»). */
    private static final float ROLLER_EDGE_K = 0.8f;

    private final RecyclerView target;
    private final Callable<Boolean> enabled;
    /** Mesh-варп для API < 33: захват контента + drawVertices вместо поворота. */
    private final PengramCylinderWarp warp = new PengramCylinderWarp();
    /** актуальные границы клипа каждой ячейки (снимается в reset()). */
    private final WeakHashMap<View, android.graphics.Rect> clipRects = new WeakHashMap<>();
    /** шейдер сгиба на ячейку: uniforms у RuntimeShader общие для всех его эффектов. */
    private final WeakHashMap<View, Bend> bends = new WeakHashMap<>();
    /** единый шейдер сгиба на весь список (API 33+): гнёт фон исходящих пузырей, альбомы, аватарки и текст без обрезки по краям ячейки. */
    private Bend listBend;
    private String shaderCode;
    private boolean shaderFailed;
    private boolean applied;
    // Быстрый путь покоя: скролл/геометрия/сдвиги не менялись — кадр идентичен,
    // углы и шейдер заново не выставляем (перерисовки списка бывают из-за GIF/видео
    // и resize без прокрутки).
    private int lastScrollOffset = -1;
    private int lastChildCount = -1;
    private int lastTargetWidth = -1;
    private int lastTargetHeight = -1;
    private float lastLayoutSum;
    private int lastBarsSig;
    private boolean observing;
    private float activeInsetTop;
    private float activeInsetBottom;
    private float activeRadius;
    private boolean activeListShader;

    private static final int BLUR_SLOT_COUNT = 4;
    private BlurSlot[] blurSlots;
    private int blurSlotCursor;
    private final RectF blurSrcRectTmp = new RectF();

    private static final class BlurSlot {
        final RenderNode node;
        final Bend bend;

        BlurSlot(String code) {
            this.node = new RenderNode("PengramCylinderBlur");
            this.node.setClipToBounds(false);
            this.bend = new Bend(code);
        }
    }

    /** Границы баров в координатах списка: [0] — низ шапки, [1] — верх ввода. */
    public interface BarsProvider {
        float[] get();
    }

    private final Callable<Boolean> toBarsEnabled;
    private final BarsProvider bars;

    private PengramCylinderScroll(RecyclerView target, Callable<Boolean> enabled,
                                  Callable<Boolean> toBarsEnabled, BarsProvider bars) {
        this.target = target;
        this.enabled = enabled;
        this.toBarsEnabled = toBarsEnabled;
        this.bars = bars;
    }

    public static PengramCylinderScroll attach(RecyclerView target, Callable<Boolean> enabled) {
        return attach(target, enabled, null, null);
    }

    /** С тумблером «до шапки и поля ввода»: валики привязываются к барам. */
    public static PengramCylinderScroll attach(RecyclerView target, Callable<Boolean> enabled,
                                               Callable<Boolean> toBarsEnabled, BarsProvider bars) {
        final PengramCylinderScroll scroll = new PengramCylinderScroll(target, enabled, toBarsEnabled, bars);
        if (target instanceof RecyclerListView) {
            // воин рисует детей сам только пока эффект включён; в обычных
            // списках (childWarp == null) хук остаётся пустым
            ((RecyclerListView) target).setChildWarp(scroll.warp);
            ((RecyclerListView) target).setPengramCylinderScroll(scroll);
        }
        target.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
                scroll.detachChild(view);
            }
        });
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
        private boolean active;
        private float localT, localB, roller, width, height;

        private Bend(String code) {
            shader = new RuntimeShader(code);
        }

        private boolean detachIfActive() {
            if (!active) {
                return false;
            }
            active = false;
            return true;
        }

        /** false — параметры не изменились, эффект ставить заново не нужно. */
        private boolean bind(float localT, float localB, float roller, float width, float height) {
            if (active && effect != null && Math.abs(this.localT - localT) < 0.1f
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
            active = true;
            return true;
        }
    }

    private void detachChild(View child) {
        if (child == null) {
            return;
        }
        final Bend bend = bends.get(child);
        if (bend != null && bend.detachIfActive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            child.setRenderEffect(null);
        }
        if (clipRects.remove(child) != null) {
            child.setClipBounds(null);
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
        final int targetWidth = target.getWidth();
        final int targetHeight = target.getHeight();
        // границы зоны изгиба: по умолчанию — края списка (режим как раньше);
        // с тумблером — низ шапки и верх ввода: полоса сгиба видна целиком
        // между барами, а исчезновение уходит ПОД бар (EDGE_K ≤ 1 гарантирует)
        float topEdge = 0f;
        float bottomEdge = targetHeight;
        boolean toBars = false;
        if (toBarsEnabled != null && bars != null) {
            try {
                toBars = Boolean.TRUE.equals(toBarsEnabled.call());
            } catch (Throwable ignored) {
            }
            if (toBars) {
                try {
                    final float[] edges = bars.get();
                    if (edges != null && edges.length >= 2 && edges[1] > edges[0] + 1f) {
                        topEdge = Math.max(0f, Math.min(edges[0], targetHeight - 2f));
                        bottomEdge = Math.max(topEdge + 2f, Math.min(edges[1], targetHeight));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        final int strengthPct = PengramConfig.getCylinderScrollStrength();
        final int barsSig = (Float.floatToIntBits(topEdge) * 31 + Float.floatToIntBits(bottomEdge)) * 31 + strengthPct;
        // На API 33+ с активным listBend весь список гнётся единым GPU-слоем:
        // его шейдер зависит только от геометрии списка и баров, а НЕ от скролла
        // и позиций отдельных ячеек — при 120 Гц скролле пропускаем цикл по детям целиком.
        if (applied && listBend != null && listBend.active
                && targetWidth == lastTargetWidth && targetHeight == lastTargetHeight
                && barsSig == lastBarsSig) {
            return;
        }
        final int scrollOffset = target.computeVerticalScrollOffset();
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
                && targetHeight == lastTargetHeight && barsSig == lastBarsSig
                && Math.abs(layoutSum - lastLayoutSum) < 0.5f) {
            return;
        }
        lastScrollOffset = scrollOffset;
        lastChildCount = count;
        lastTargetHeight = targetHeight;
        lastTargetWidth = targetWidth;
        lastLayoutSum = layoutSum;
        lastBarsSig = barsSig;
        // сила:100 % — сочный, но читаемый изгиб,0 % — прежняя нежная дуга
        final float strength = PengramConfig.getCylinderScrollStrength() / 100f;
        final float radiusDp = RADIUS_GENTLE_DP + (RADIUS_TIGHT_DP - RADIUS_GENTLE_DP) * strength;
        final float radius = radiusDp * AndroidUtilities.density;
        // два валика: касательные на EDGE_K*radius внутрь от ГРАНИЦ зоны
        // (края списка либо внутренние края баров) — у кромки остаётся ~53°
        // намотки (сгиб без переворота), плоская середина ≥ трети зоны
        final float span = bottomEdge - topEdge;
        final float band = Math.min(radius * ROLLER_EDGE_K, span / 3f);
        final float insetTop = topEdge + band;
        final float insetBottom = bottomEdge - band;
        boolean shader = ensureWarp();
        boolean listShaderActive = false;
        if (shader && targetWidth > 0 && targetHeight > 0) {
            if (listBend == null) {
                try {
                    listBend = new Bend(shaderCode);
                } catch (Throwable t) {
                    shaderFailed = true;
                    shader = false;
                }
            }
            if (listBend != null) {
                if (listBend.bind(insetTop, insetBottom, radius, targetWidth, targetHeight)) {
                    target.setRenderEffect(listBend.effect);
                }
                listShaderActive = true;
            }
        }
        activeInsetTop = insetTop;
        activeInsetBottom = insetBottom;
        activeRadius = radius;
        activeListShader = listShaderActive;
        if (shader) {
            warp.deactivate();
        } else {
            // шейдера нет (API < 33 или сбой) — ту же карту исполняет mesh-варп
            if (listBend != null && listBend.detachIfActive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                target.setRenderEffect(null);
            }
            warp.setGeometry(insetTop, insetBottom, radius);
        }
        if ((!shader || listShaderActive) && !bends.isEmpty()) {
            // шейдер не поднялся или перешёл на весь список — снимаем по-ячеечные эффекты
            for (final View view : bends.keySet()) {
                view.setRenderEffect(null);
            }
            bends.clear();
        }
        if (listShaderActive && !clipRects.isEmpty()) {
            for (final View view : clipRects.keySet()) {
                if (view != null) {
                    view.setClipBounds(null);
                }
            }
            clipRects.clear();
        }
        if (!listShaderActive) {
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
                if (shader && w > 0 && h > 0) {
                    // Касательные валиков в локальных координатах ячейки: карта одна
                    // на весь список (инсет одинаков), поэтому швы соседних строк
                    // совпадают точно, а содержимое одного сообщения едет неразрывно.
                    final float top = child.getTop() + child.getTranslationY();
                    final float localT = insetTop - top;
                    final float localB = insetBottom - top;
                    // В плоской середине между валиками шейдер — тождественное 1:1 копирование:
                    // снимаем RenderEffect, чтобы HWUI не выделял offscreen FBO на плоские ячейки.
                    if (localT <= 0f && localB >= h) {
                        final Bend existing = bends.get(child);
                        if (existing != null && existing.detachIfActive()) {
                            child.setRenderEffect(null);
                        }
                        continue;
                    }
                    Bend bend = bends.get(child);
                    if (bend == null) {
                        try {
                            bend = new Bend(shaderCode);
                        } catch (Throwable t) {
                            shaderFailed = true;
                        }
                        if (bend != null) {
                            bends.put(child, bend);
                        }
                    }
                    if (bend != null && bend.bind(localT, localB, radius, w, h)) {
                        child.setRenderEffect(bend.effect);
                    }
                }
            }
        }
        applied = true;
    }

    private void reset() {
        applied = false;
        activeListShader = false;
        activeRadius = 0f;
        activeInsetTop = 0f;
        activeInsetBottom = 0f;
        warp.clear();
        if (listBend != null && listBend.detachIfActive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            target.setRenderEffect(null);
        }
        if (blurSlots != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (int i = 0; i < blurSlots.length; i++) {
                if (blurSlots[i] != null) {
                    blurSlots[i].node.discardDisplayList();
                }
            }
        }
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
            if (clipRects.remove(child) != null) {
                child.setClipBounds(null);
            }
        }
        clipRects.clear();
    }

    /** Активен ли mesh-варп ячеек (API < 33 fallback), когда внегабаритные реакции нельзя рисовать плоско поверх. */
    public boolean isMeshWarpActive() {
        return applied && !activeListShader && activeRadius > 1f;
    }

    /** Хэш состояния цилиндра для инвалидации кэша Liquid Glass (DownscaleScrollableNoiseSuppressor). */
    public long getBlurStateHash() {
        if (!applied || activeRadius <= 1f) {
            return 0L;
        }
        long h = activeListShader ? 0x51C3L : 0x29A7L;
        h = h * 31L + Float.floatToIntBits(activeInsetTop);
        h = h * 31L + Float.floatToIntBits(activeInsetBottom);
        h = h * 31L + Float.floatToIntBits(activeRadius);
        return h;
    }

    /** Расширение верхней границы захвата под обратную дугу asin() верхнего валика. */
    public float expandBlurSourceTop(float y) {
        if (!applied || activeRadius <= 1f || y >= activeInsetTop) {
            return y;
        }
        final float sn = Math.min(1f, (activeInsetTop - Math.max(0f, y)) / activeRadius);
        final float s = activeInsetTop - activeRadius * (float) Math.asin(sn);
        return Math.max(0f, Math.min(y, s) - 2f);
    }

    /** Расширение нижней границы захвата под обратную дугу asin() нижнего валика. */
    public float expandBlurSourceBottom(float y, float listHeight) {
        if (!applied || activeRadius <= 1f || y <= activeInsetBottom) {
            return y;
        }
        final float sn = Math.min(1f, (Math.min(listHeight, y) - activeInsetBottom) / activeRadius);
        final float s = activeInsetBottom + activeRadius * (float) Math.asin(sn);
        return Math.min(listHeight, Math.max(y, s) + 2f);
    }

    public interface BlurCaptureDrawer {
        void draw(Canvas canvas, RectF position);
    }

    /**
     * Захват фрагмента списка для Liquid Glass (blur3) с точным совпадением 3D-изгиба цилиндра:
     * на API 33+ применяет тот же AGSL-шейдер валика к полосе под стеклянной плашкой, чтобы внутри
     * прозрачного стекла не просвечивали плоские «призрачные» пузыри и не рвался шов преломления.
     */
    public void drawForBlurCapture(Canvas blurCanvas, RectF listLocalPos, BlurCaptureDrawer listDrawer) {
        if (!applied || !activeListShader || activeRadius <= 1f
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || !blurCanvas.isHardwareAccelerated()
                || shaderCode == null) {
            listDrawer.draw(blurCanvas, listLocalPos);
            return;
        }
        final int listW = target.getWidth();
        final int listH = target.getHeight();
        if (listW <= 0 || listH <= 0) {
            listDrawer.draw(blurCanvas, listLocalPos);
            return;
        }
        // Если область целиком лежит в плоской центральной зоне между валиками — шейдер тождественен 1:1.
        if (listLocalPos.top >= activeInsetTop && listLocalPos.bottom <= activeInsetBottom) {
            listDrawer.draw(blurCanvas, listLocalPos);
            return;
        }
        final float dstTop = Math.max(0f, listLocalPos.top);
        final float dstBottom = Math.min(listH, listLocalPos.bottom);
        if (dstBottom <= dstTop) {
            return;
        }
        final int nodeTop = Math.max(0, (int) Math.floor(expandBlurSourceTop(dstTop)));
        final int nodeBottom = Math.min(listH, (int) Math.ceil(expandBlurSourceBottom(dstBottom, listH)));
        final int nodeH = nodeBottom - nodeTop;
        if (nodeH <= 0) {
            return;
        }
        try {
            if (blurSlots == null) {
                blurSlots = new BlurSlot[BLUR_SLOT_COUNT];
            }
            final int idx = (blurSlotCursor++) & (BLUR_SLOT_COUNT - 1);
            BlurSlot slot = blurSlots[idx];
            if (slot == null) {
                slot = new BlurSlot(shaderCode);
                blurSlots[idx] = slot;
            }
            slot.node.setPosition(0, 0, listW, nodeH);
            if (slot.bend.bind(activeInsetTop - nodeTop, activeInsetBottom - nodeTop, activeRadius, listW, nodeH)) {
                slot.node.setRenderEffect(slot.bend.effect);
            }
            final Canvas rc = slot.node.beginRecording(listW, nodeH);
            rc.translate(0f, -nodeTop);
            blurSrcRectTmp.set(0f, nodeTop, listW, nodeBottom);
            listDrawer.draw(rc, blurSrcRectTmp);
            slot.node.endRecording();

            blurCanvas.save();
            blurCanvas.clipRect(listLocalPos);
            blurCanvas.translate(0f, nodeTop);
            blurCanvas.drawRenderNode(slot.node);
            blurCanvas.restore();
        } catch (Throwable t) {
            listDrawer.draw(blurCanvas, listLocalPos);
        }
    }
}
