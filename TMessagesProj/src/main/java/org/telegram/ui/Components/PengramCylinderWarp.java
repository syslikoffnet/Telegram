package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.os.Build;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import java.util.Arrays;
import java.util.WeakHashMap;

/**
 * Настоящий цилиндрический сгиб для устройств без RuntimeShader (API &lt; 33
 * и случай сбоя шейдера). Карта попиксельно повторяет
 * {@code res/raw/pengram_cylinder_bend.agsl}: экранная строка y у касательной
 * берёт исходную строку {@code s = localT - roller*asin((localT-y)/roller)}
 * (снизу симметрично), в плоской середине {@code s = y}, а x сжимается к
 * центру по {@code scale = 2 / (2 + 1 - cos(asin(sn)))} — содержимое
 * наматывается на валик, а не наклоняется жёсткой плитой.
 *
 * Содержимое ячейки захватывается в bitmap один раз на изменение контента:
 * позиционные сдвиги (translation/scale/rotation/alpha) перезахвата не
 * требуют — матрица и альфа применяются прямо при отрисовке, а
 * onDescendantInvalidated отличает «уехала» от «перерисовалась» по снимку
 * трансформаций. В зоне сгиба живёт лишь пара-тройка битмапов; каждый кадр —
 * один {@link Canvas#drawVertices} с текстурой из захвата: GPU-путь без
 * программных проходов по ячейке. Вне зоны сгиба draw() возвращает false и
 * список рисует ребёнка сам — нулевая нагрузка на центр списка.
 *
 * За пределами карты (sn &gt; 1, s или sx вне холста) текстура уходит в
 * прозрачную рамку захвата шириной1 px — как в шейдере, который в этих
 * местах возвращает прозрачный пиксель.
 */
final class PengramCylinderWarp implements RecyclerListView.ChildWarp {

    /** Шаг выборки рядов: кривая участка меняется медленно, 2px дают &lt;0.5px ошибки. */
    private static final float ROW_STEP = 2f;
    /** Запас при росте массивов (кinks касательных добавляются к равномерной сетке). */
    private static final int ROW_SLOTS = 8;
    /** Текстура за пределами захвата — в прозрачную рамку (CLAMP). */
    private static final float OUT = -9999f;
    /** Жёсткий предел индексов drawVertices (short): держимся далеко. */
    private static final int MAX_ROWS = 15000;

    /** Захват контента одной ячейки + снимок трансформаций для классификации инвалидаций. */
    private static final class Capture {
        Bitmap bitmap;
        BitmapShader shader;
        boolean stale = true;
        boolean failed;
        int width, height;
        // снимок трансформаций: отличаем «уехала/проявилась» от «перерисовалась»
        float tx, ty, alpha, sx, sy, rot, pivotX, pivotY;
        // кэш сетки (позиции зависят только от размера ячейки)
        float[] positions;
        float[] texs;
        short[] indices;
        int vertexCount;
        int indexCount;

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
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final float[] kinks = new float[4];

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
        capture.shader = null;
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
            capture.failed = false;
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
                    || surface.getWidth() != w + 2 || surface.getHeight() != h + 2) {
                if (surface != null && !surface.isRecycled()) {
                    surface.recycle();
                }
                surface = Bitmap.createBitmap(w + 2, h + 2, Bitmap.Config.ARGB_8888);
                capture.bitmap = surface;
                capture.shader = new BitmapShader(surface, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                releaseGrid(capture);
            } else {
                surface.eraseColor(0);
            }
            final Canvas onto = new Canvas(surface);
            onto.translate(1f, 1f);
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
        capture.positions = null;
        capture.texs = null;
        capture.indices = null;
        capture.vertexCount = 0;
        capture.indexCount = 0;
    }

    /** Обратная карта варпа: исходная строка s -> экранная (локальная) строка y.
     * Прямая карта s(y) монотонна и биективна на своём домене, поэтому
     * обратная считается через синус: верх y = localT - R*sin((localT-s)/R),
     * низ симметрично, плоская середина y = s. Клетки по этой функции
     * размечаются вплотную — швы идеальны, мёртвых полос на границах зон нет. */
    private float yOfSource(float s, float localT, float localB) {
        if (s < localT) {
            return localT - roller * (float) Math.sin((localT - s) / roller);
        }
        if (s > localB) {
            return localB + roller * (float) Math.sin((s - localB) / roller);
        }
        return s;
    }

    /** Рисует варп: сетка рядов высотой ROW_STEP с точными узлами у касательных. */
    private void drawMesh(Canvas canvas, Capture capture, float localT, float localB, float alpha) {
        final int w = capture.width;
        final int h = capture.height;
        // Домен источника ячейки: всё, до чего доходит карта (sn <= 1).
        // Дальше — контента нет: у самой кромки экрана это «как за экран»
        // (паритет с шейдером), а полосы ПОД шапкой/НАД вводом покрывает
        // сосед сверху/снизу своим расширенным окном — разрывов не остаётся.
        final float curlDepth = (float) (Math.PI / 2) * roller;
        final float srcLo = Math.max(0f, localT - curlDepth);
        final float srcHi = Math.min(h, localB + curlDepth);
        if (srcHi - srcLo < 0.5f) {
            return; // ячейка целиком вне домена карты — рисовать нечего
        }
        final float yLo = yOfSource(srcLo, localT, localB);
        final float yHi = yOfSource(srcHi, localT, localB);
        if (yHi - yLo < 0.5f) {
            return;
        }
        // rows: равномерная сетка по окну + точные узлы кривой (касательные и sn=1)
        int rows = buildRows(yLo, yHi, localT, localB);
        ensureGrid(capture, rows, yHi - yLo);
        final float[] positions = capture.positions;
        final float[] texs = capture.texs;
        final short[] indices = capture.indices;
        final float invRoller = 1f / roller;
        final float cx = w * 0.5f;
        // позиции — окно рисования ячейки (может выходить за [0,h]: швы
        // соседей сходятся точно, clipChildren в списке отключён);
        // текстура — прямая карта варпа, ровно как img.eval в шейдере
        for (int r = 0; r < rows; r++) {
            final float y = rowY(r);
            positions[r * 4] = 0f;
            positions[r * 4 + 1] = y;
            positions[r * 4 + 2] = w;
            positions[r * 4 + 3] = y;
            float sn = 0f;
            if (y < localT) {
                sn = (localT - y) * invRoller;
            } else if (y > localB) {
                sn = (y - localB) * invRoller;
            }
            if (sn > 1f) {
                // шейдер здесь возвращает прозрачный: уводим текстуру в рамку
                texs[r * 4] = OUT;
                texs[r * 4 + 1] = OUT;
                texs[r * 4 + 2] = OUT;
                texs[r * 4 + 3] = OUT;
                continue;
            }
            final float phi = (float) Math.asin(sn);
            final float s;
            if (y < localT) {
                s = localT - roller * phi;
            } else if (y > localB) {
                s = localB + roller * phi;
            } else {
                s = y;
            }
            final float back = 1f - (float) Math.cos(phi);
            final float scale = 2f / (2f + back);
            final float texY = s + 1f;
            texs[r * 4] = cx + (0f - cx) / scale + 1f;
            texs[r * 4 + 1] = texY;
            texs[r * 4 + 2] = cx + (w - cx) / scale + 1f;
            texs[r * 4 + 3] = texY;
        }
        for (int r = 0; r < rows - 1; r++) {
            final int base = r * 6;
            final short l = (short) (r * 2);
            final short rgt = (short) (r * 2 + 1);
            final short l2 = (short) ((r + 1) * 2);
            final short r2 = (short) ((r + 1) * 2 + 1);
            indices[base] = l;
            indices[base + 1] = rgt;
            indices[base + 2] = r2;
            indices[base + 3] = l;
            indices[base + 4] = r2;
            indices[base + 5] = l2;
        }
        paint.setShader(capture.shader);
        paint.setAlpha(Math.round(alpha * 255f));
        // полная сигнатура со сдвигами существует с API 23; короткая
        //8-арг вырезана из современного SDK
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, capture.vertexCount,
                positions, 0, texs, 0, null, 0, indices, 0, capture.indexCount, paint);
    }

    // ---- ряды: равномерная сетка по ROW_STEP + точные узлы кривой ----

    private float[] rowsBuf = new float[64];

    private float rowY(int index) {
        return rowsBuf[index];
    }

    private int buildRows(float yLo, float yHi, float localT, float localB) {
        final float span = yHi - yLo;
        final float step = Math.max(ROW_STEP, span / (float) MAX_ROWS);
        int cap = (int) (span / ROW_STEP) + 3 + ROW_SLOTS;
        if (cap > MAX_ROWS + ROW_SLOTS) {
            cap = MAX_ROWS + ROW_SLOTS;
        }
        if (rowsBuf.length < cap) {
            rowsBuf = new float[cap];
        }
        int n = 0;
        for (float y = yLo; y < yHi; y += step) {
            rowsBuf[n++] = y;
        }
        rowsBuf[n++] = yHi;
        // узлы кривой: касательные (излом наклона) и sn=1 (граница прозрачности)
        kinks[0] = localT;
        kinks[1] = localB;
        kinks[2] = localT - roller;
        kinks[3] = localB + roller;
        for (int i = 0; i < 4; i++) {
            final float k = kinks[i];
            if (k > yLo && k < yHi) {
                rowsBuf[n++] = k;
            }
        }
        Arrays.sort(rowsBuf, 0, n);
        // схлопываем совпадения — вырожденные треугольники не нужны
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m == 0 || rowsBuf[i] - rowsBuf[m - 1] > 0.01f) {
                rowsBuf[m++] = rowsBuf[i];
            }
        }
        return m;
    }

    /** Сетка выделяется с запасом на размах окна (высота ячейки + глубина
     * намотки): число рядов за кадр гуляет только на узлах кривой. */
    private void ensureGrid(Capture capture, int rows, float span) {
        final int maxRows = (int) Math.ceil(span
                / Math.max(ROW_STEP, span / (float) MAX_ROWS)) + ROW_SLOTS;
        if (capture.positions == null || capture.positions.length < maxRows * 4
                || capture.texs == null || capture.texs.length < maxRows * 4
                || capture.indices == null || capture.indices.length < (maxRows - 1) * 6) {
            capture.positions = new float[maxRows * 4];
            capture.texs = new float[maxRows * 4];
            capture.indices = new short[(maxRows - 1) * 6];
        }
        capture.vertexCount = rows * 2;
        capture.indexCount = (rows - 1) * 6;
    }
}
