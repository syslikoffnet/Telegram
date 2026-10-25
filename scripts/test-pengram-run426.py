#!/usr/bin/env python3
"""run426 — внутренние контракты PengramCylinderWarp (mesh-гиб вместо поворота).

Проверяет механику, из-за которой «без фризов»: перезахват только при смене
контента (снимок трансформаций), выпуск битмапов при выходе из зоны и
откреплении, кэш сетки на размер ячейки, отказоустойчивость захвата и
alpha-гейт. Плюс отсутствие поворота в цилиндре и хук RecyclerListView.
"""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
WARP = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderWarp.java")
CY = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java")
RLV = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/RecyclerListView.java")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    w = io.open(WARP, encoding="utf-8").read()
    cy = io.open(CY, encoding="utf-8").read()
    rlv = io.open(RLV, encoding="utf-8").read()

    print("== Снимок трансформаций: «уехала» ≠ «перерисовалась»")
    check("поля снимка покрывают аниматоры (tx/ty/alpha/scale/rot/pivot)",
          "float tx, ty, alpha, sx, sy, rot, pivotX, pivotY;" in w)
    check("совпадение снимка → контент устарел",
          "if (capture.transformMatches(child)) {" in w
          and "capture.stale = true;" in w)
    check("расхождение → позиция: снимок обновляем, ротация/скейл не требуют захвата",
          "capture.snapshot(child);" in w
          and "capture.width != oldW || capture.height != oldH" in w)
    check("инвалидация без захвата — no-op (не порождает работу)",
          "public void onChildInvalidate(View child)" in w
          and "capture == null || capture.failed || capture.bitmap == null" in w)

    print("== Память: захваты живут только в зоне сгиба")
    check("выход из зоны выпускает битмап",
          "final Capture flat = captures.remove(child);" in w
          and "releaseBitmap(flat);" in w)
    check("открепление от списка чистит захват",
          "public void onChildDetached(View child)" in w
          and "captures.remove(child)" in w)
    check("полный clear() — на выключение эффекта",
          "void clear()" in w and "captures.clear();" in w)
    check("WeakHashMap — ячейки не держим после GC списка",
          "WeakHashMap<View, Capture> captures" in w)

    print("== Кадр: минимум работы, максимум предсказуемости")
    check("невидимая ячейка не захватывается",
          "alpha <= 0.004f" in w and "return true; // невидима" in w)
    check("сбой захвата — ядро с гибом, без повторов",
          "catch (Throwable t)" in w and "capture.failed = true;" in w)
    check("сетка выделяется один раз на размер ячейки (maxRows-запас)",
          "private void ensureGrid(Capture capture, int rows)" in w
          and "maxRows * 4" in w and "ROW_SLOTS" in w)
    check("ряды: равномерный шаг + точные узлы касательных и sn=1",
          "ROW_STEP = 2f" in w and "kinks[0] = localT" in w
          and "localT - roller" in w and "localB + roller" in w
          and "Arrays.sort(rowsBuf, 0, n)" in w)
    check("матрица ребёнка и альфа применяются при рисовке (без перезахвата)",
          "canvas.concat(child.getMatrix())" in w
          and "paint.setAlpha(Math.round(alpha * 255f))" in w)
    check("текстура: фильтрация и CLAMP к прозрачной рамке",
          "FILTER_BITMAP_FLAG" in w and "DITHER_FLAG" in w
          and "Shader.TileMode.CLAMP, Shader.TileMode.CLAMP" in w)

    print("== Цилиндр и список: связка цела")
    check("scroll: шейдер >=33, иначе варп — режимы не пересекаются",
          "if (shader) {" in cy and "warp.deactivate();" in cy
          and "warp.setGeometry(insetTop, insetBottom, radius);" in cy)
    check("rlv: хук отключаем (null) и стоит до секционного клипа",
          "private ChildWarp childWarp;" in rlv
          and rlv.find("childWarp != null && childWarp.draw")
          < rlv.find("hasSections() && !ignoreClipChild"))
    check("rlv: старые пути суперкласса не тронуты (вызовы super сохранены)",
          "super.onDescendantInvalidated(child, target);" in rlv
          and "super.invalidateChild(child, dirty);" in rlv
          and "super.onChildDetachedFromWindow(child);" in rlv
          and "super.drawChild(canvas, child, drawingTime)" in rlv)

    print()
    if fails:
        print("FAIL:", len(fails))
        for f in fails:
            print("  -", f)
        sys.exit(1)
    print("ALL OK")


if __name__ == "__main__":
    main()
