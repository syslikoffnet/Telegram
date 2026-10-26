package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;

import java.util.WeakHashMap;

/**
 * Единый цилиндрический сгиб для устройств без RuntimeShader (API &lt; 33 и
 * случай сбоя шейдера). Карта попиксельно повторяет
 * {@code res/raw/pengram_cylinder_bend.agsl} (asin-намотка + x-перспектива
 * от центра), но исполняется каноническим для Android способом «контент →
 * bitmap → drawBitmapMesh»: тот же примитив рекомендуют статьи про
 * деформацию скролла, и он hardware-поддерживается С API 18 — т.е. на всех
 * устройствах minSdk 23 одинаково (drawVertices в HW есть только с API 29).
 *
 * Прямая карта: исходная строка s экранной строке y = localT −
 * R·sin((localT−s)/R) (снизу симметрично, плоская середина y = s), а x
 * сжимается к центру по scale = 2/(2+1−cosφ) — точная перспектива цилиндра
 * с глазом на расстоянии 2R. Вершины сетки — позиции НАЗНАЧЕНИЯ для
 * равномерной сетки источника; источники вне домена карты схлопываются на
 * линию касательной (это ровно прозрачность шейдера), а диапазон ячейки
 * считается обратным образом источника — швы соседних ячеек сходятся точно
 * (биекция), мёртвых полос на границах зон нет.
 *
 * Содержимое ячейки захватывается в bitmap один раз на изменение контента:
 * позиционные сдвиги перезахвата не требуют — матрица и альфа применяются
 * при отрисовке, а onDescendantInvalidated отличает «уехала» от
 * «перерисовалась» по снимку трансформаций. В зоне сгиба живёт лишь пара-
 * тройка битмапов; каждый кадр — один drawBitmapMesh (GPU-тесселяция).
 * Вне зоны draw() возвращает false — список рисует ребёнка сам.
 */
final class PengramCylinderWarp implements RecyclerListView.ChildWarp {

    /** Шаг выборки рядов-источников: кривая меняется медленно, 2px дают <0.5px ошибки. */
    private static final float ROW_STEP = 2f;
    /** Запас при росте сетки (узлы кривой добавляются к равномерной сетке). */
    private static final int ROW_SLOTS = 8;
    /** Жёсткий предел высоты сетки (в строках) — тесселлятору так комфортно. */
    private static final int MAX_ROWS = 15000;

    /** Захват контента одной ячейки + снимок трансформаций для классификации инвалидаций. */
    private static final class Capture {
        Bitmap bitmap;
        boolean stale = true;
        boolean failed;
        int width, height;
        // снимок трансформаций: отличаем «уехала/проявилась» от «перерисовалась»
        float tx, ty, alpha, sx, sy, rot, pivotX, pivotY;
        // кэш вершин сетки (позиции зависят от геометрии кадра — перезаполняются)
        float[] verts;

        void snapshot(View child) {
            tx = child.getTranslationX();
            ty = child.getTranslationY();
            alpha = child.getAlpha();
            sx = child.getScaleX();
            sy = child.getScaleY();
            rot = child.getRotation();
            pivotX = child.getPivotX();
            pivotY = child.getPivotY();
            width = child.getWidth();
            height = child.getHeight();
        }

        boolean transformMatches(View child) {
            return width == child.getWidth() && height == child.getHeight()
                    && tx == child.getTranslationX() && ty == child.getTranslationY()
                    && alpha == child.getAlpha() && sx == child.getScaleX()
                    && sy == child.getScaleY() && rot == child.getRotation()
                    && pivotX == child.getPivotX() && pivotY == child.getPivotY();
        }
    }

    private final WeakHashMap<View, Capture> captures = new WeakHashMap<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    private boolean active;
    private float insetTop;
    private float insetBottom;
    private float roller;

    /** Геометрия на кадр — та же, что uniforms шейдера (localT/localB/roller). */
    void setGeometry(float insetTop, float insetBottom, float roller) {
        this.insetTop = insetTop;
        this.insetBottom = insetBottom;
        this.roller = roller;
        this.active = roller > 0f && insetBottom > insetTop;
    }

    void deactivate() {
        active = false;
    }

    /** Полный сброс: выпустить все захваты (вызывается из reset() цилиндра). */
    void clear() {
        for (final Capture capture : captures.values()) {
            releaseBitmap(capture);
        }
        captures.clear();
        active = false;
    }

    private static void releaseBitmap(Capture capture) {
        if (capture.bitmap != null && !capture.bitmap.isRecycled()) {
            capture.bitmap.recycle();
        }
        capture.bitmap = null;
    }

    @Override
    public boolean draw(Canvas canvas, View child) {
        if (!active) {
            return false;
        }
        final int w = child.getWidth();
        final int h = child.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        final float top = child.getTop() + child.getTranslationY();
        final float localT = insetTop - top;
        final float localB = insetBottom - top;
        if (localT <= 0f && localB >= h) {
            // центр списка, плоский участок: рисуем штатно и не держим захват
            final Capture flat = captures.remove(child);
            if (flat != null) {
                releaseBitmap(flat);
            }
            return false;
        }
        final float alpha = child.getAlpha();
        if (alpha <= 0.004f) {
            return true; // невидима: рисовать нечего, контент не захватываем
        }
        if (Build.VERSION.SDK_INT < 24) {
            // Android 6/7: onDescendantInvalidated ещё нет, а invalidateChild
            // финален — «уехала» от «перерисовалась» отличить нечем:
            // на этих редких версиях капчим зону каждый кадр (верность превыше)
            final Capture watched = captures.get(child);
            if (watched != null) {
                watched.stale = true;
            }
        }
        final Capture capture = capture(child, w, h);
        if (capture == null) {
            return false; // захват невозможен — штатная отрисовка без сгиба
        }
        canvas.save();
        canvas.concat(child.getMatrix());
        drawMesh(canvas, capture, localT, localB, alpha);
        canvas.restore();
        return true;
    }

    /** Захват контента: лениво и только при устаревании (смена контента/размера). */
    private Capture capture(View child, int w, int h) {
        Capture capture = captures.get(child);
        if (capture == null) {
            capture = new Capture();
            capture.stale = true;
            captures.put(child, capture);
        }
        if (capture.failed) {
            return null;
        }
        if (!capture.stale && capture.bitmap != null && capture.width == w && capture.height == h) {
            return capture;
        }
        try {
            Bitmap surface = capture.bitmap;
            if (surface == null || surface.isRecycled()
                    || surface.getWidth() != w || surface.getHeight() != h) {
                if (surface != null && !surface.isRecycled()) {
                    surface.recycle();
                }
                surface = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                capture.bitmap = surface;
                releaseGrid(capture);
            } else {
                surface.eraseColor(0);
            }
            final Canvas onto = new Canvas(surface);
            child.draw(onto);
            capture.width = w;
            capture.height = h;
            capture.stale = false;
            capture.snapshot(child);
            return capture;
        } catch (Throwable t) {
            capture.failed = true;
            releaseBitmap(capture);
            return null;
        }
    }

    @Override
    public void onChildInvalidate(View child) {
        final Capture capture = captures.get(child);
        if (capture == null || capture.failed || capture.bitmap == null) {
            return;
        }
        if (capture.transformMatches(child)) {
            // трансформации прежние — изменился сам контент
            capture.stale = true;
        } else {
            // уехала/проявилась/повернулась: контент тот же, снимок обновляем
            final int oldW = capture.width;
            final int oldH = capture.height;
            capture.snapshot(child);
            if (capture.width != oldW || capture.height != oldH) {
                capture.stale = true;
            }
        }
    }

    @Override
    public void onChildDetached(View child) {
        final Capture capture = captures.remove(child);
        if (capture != null) {
            releaseBitmap(capture);
        }
    }

    private void releaseGrid(Capture capture) {
        capture.verts = null;
    }

    /**
     * Прямая карта цилиндра: исходная строка s → экранная (локальная) y.
     * Верх y = localT − R·sin((localT−s)/R), низ симметрично, середина y = s.
     * Источники вне домена (sn выходил бы за 1) схлопываются на линию
     * касательной — это прозрачность «за экраном» из шейдера; зато источники,
     * которые ЕСТЬ (полосы под шапкой/над вводом), получают честную
     * позицию — окна ячеек сходятся вплотную, без мёртвых разрывов.
     */
    private float yOfSource(float s, float localT, float localB) {
        if (s < localT) {
            final float phi = (localT - s) / roller;
            if (phi >= Math.PI / 2) {
                return localT - roller; // домена нет — линия касательной
            }
            return localT - roller * (float) Math.sin(phi);
        }
        if (s > localB) {
            final float phi = (s - localB) / roller;
            if (phi >= Math.PI / 2) {
                return localB + roller;
            }
            return localB + roller * (float) Math.sin(phi);
        }
        return s;
    }

    /** Форма поперечного сжатия x: 2/(2+1−cosφ) — перспектива с глазом на 2R. */
    private float xScaleAt(float s, float localT, float localB) {
        float phi = 0f;
        if (s < localT) {
            phi = (localT - s) / roller;
        } else if (s > localB) {
            phi = (s - localB) / roller;
        }
        if (phi > Math.PI / 2) {
            phi = (float) (Math.PI / 2);
        }
        if (phi <= 0f) {
            return 1f;
        }
        final float back = 1f - (float) Math.cos(phi);
        return 2f / (2f + back);
    }

    /**
     * Рисует варп одним drawBitmapMesh: сетка РАВНОМЕРНА по источнику [0,h]
     * (прямой холст), вершины = позиции назначения. Каждая ячейка покрывает
     * ровно обратный образ своих источников — швы с соседями идут вплотную.
     */
    private void drawMesh(Canvas canvas, Capture capture, float localT, float localB, float alpha) {
        final int w = capture.width;
        final int h = capture.height;
        final int rows = countRows(h);
        if (rows < 2) {
            return;
        }
        final float[] verts = ensureGrid(capture, rows);
        if (verts == null) {
            return;
        }
        final float cx = w * 0.5f;
        // Строго равномерный источник: drawBitmapMesh привязывает вершину j
        // к текстурной строке j/(rows−1)·h. Изломов не нужно — на касательной
        // и на границе домена карта гладкая (C1), схлопывание тоже.
        final float stepS = h / (float) (rows - 1);
        // row-major: строка j (источник), 2 колонки (x=0 и x=w)
        for (int r = 0; r < rows; r++) {
            final float s = r * stepS;
            final float y = yOfSource(s, localT, localB);
            final float scale = xScaleAt(s, localT, localB);
            final int base = r * 4;
            verts[base] = cx + (0f - cx) * scale;     // левый край: сжатие к центру
            verts[base + 1] = y;
            verts[base + 2] = cx + (w - cx) * scale;  // правый край
            verts[base + 3] = y;
        }
        paint.setAlpha(Math.round(alpha * 255f));
        // канонический вызов: bitmap как текстура, сетка 1×(rows−1), цветов нет
        canvas.drawBitmapMesh(capture.bitmap, 1, rows - 1, verts, 0, null, 0, paint);
    }

    // ---- строки-источники: строго равномерная сетка ----

    /** Число строк сетки: шаг ROW_STEP, но не больше MAX_ROWS. */
    private int countRows(int h) {
        int rows = (int) Math.ceil(h / ROW_STEP) + 1;
        if (rows > MAX_ROWS) {
            rows = MAX_ROWS;
        }
        return rows;
    }

    /** Кэш вершин на размер ячейки (число рядов за кадр гуляет на узлах). */
    private float[] ensureGrid(Capture capture, int rows) {
        final int need = rows * 4; // 2 колонки × (x,y) на ряд
        if (capture.verts == null || capture.verts.length < need) {
            final int capRows = Math.max(rows,
                    (int) Math.ceil(capture.height / ROW_STEP) + ROW_SLOTS);
            capture.verts = new float[capRows * 4];
        }
        return capture.verts;
    }
}
