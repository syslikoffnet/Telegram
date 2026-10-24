#!/usr/bin/env python3
"""run418 — цилиндр без «переворота»: шейдерный распределённый сгиб строки
по дуге валика (нейтраль = центр экрана), legacy-путь сохранён для API<33."""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SHADER = os.path.join(ROOT, "TMessagesProj/src/main/res/raw/pengram_cylinder_bend.agsl")
CY = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    check("shader файл существует", os.path.exists(SHADER))
    if not os.path.exists(SHADER):
        print("FAIL: нет ресурса сгиба")
        return 1
    ag = io.open(SHADER, encoding="utf-8").read()
    cy = io.open(CY, encoding="utf-8").read()

    print("== AGSL: цилиндрический сгиб")
    check("agsl: uniform img/radius/neutral/size/persp", all(t in ag for t in (
        "uniform shader img;", "uniform float radius;", "uniform float neutral;",
        "uniform float2 size;", "uniform float persp;")))
    check("agsl: обратное отображение asin((d-neutral)/radius)",
          "asin(arg)" in ag and "(d - neutral) / radius" in ag)
    check("agsl: исходная координата s = neutral + radius * phi",
          "neutral + radius * phi" in ag)
    check("agsl: clamp аргумента в [-1,1] (без выхода из домена)",
          "clamp(arg, -1.0, 1.0)" in ag)
    check("agsl: нейтраль — единичное отображение (identity при d==neutral)",
          "float phi = asin(arg);" in ag and "float s = neutral + radius * phi;" in ag)
    check("agsl: перспективное сужение по X", "(1.0 - c) * persp" in ag)
    check("agsl: обрезка листа по Y и по X (прозрачность)",
          ag.count("return half4(0.0);") >= 2)
    check("agsl: единственный сэмпл img.eval", ag.count("img.eval") == 1
          and "img.eval(float2(sx, s))" in ag)
    check("agsl: сигнатура как у liquid (сверка стиля)",
          "half4 main(in float2 fragCoord)" in ag)

    print("== Java: шейдерный путь")
    check("cy: загрузка R.raw.pengram_cylinder_bend через readRes",
          "R.raw.pengram_cylinder_bend" in cy and "AndroidUtilities.readRes" in cy)
    check("cy: ворота API33 (TIRAMISU)", "Build.VERSION_CODES.TIRAMISU" in cy)
    check("cy: RuntimeShader создаётся под защитой try/catch (нет краша)",
          "new RuntimeShader(code)" in cy and "catch (Throwable t)" in cy)
    check("cy: createRuntimeShaderEffect по имени img",
          "RenderEffect.createRuntimeShaderEffect(shader, \"img\")" in cy)
    check("cy: эффект ставится ячейке", "child.setRenderEffect(bend.effect)" in cy)
    check("cy: снятие эффекта в reset и при деградации",
          cy.count("setRenderEffect(null)") >= 2 and "bends.clear();" in cy)
    check("cy: копия шейдера на ячейку (WeakHashMap<View, Bend>)",
          "WeakHashMap<View, Bend>" in cy)
    check("cy: нейтраль = центр − (top + translation), сдвиги учтены",
          "anchor - (child.getTop() + child.getTranslationY())" in cy)
    check("cy: dirty-check uniforms как у liquid (0.1f)",
          "Math.abs(this.neutral - neutral) < 0.1f" in cy)
    check("cy: сила/RADIUS формула не тронута",
          "RADIUS_TIGHT_DP = 760f" in cy
          and "radiusDp = RADIUS_GENTLE_DP + (RADIUS_TIGHT_DP - RADIUS_GENTLE_DP) * strength" in cy)
    check("cy: перспектива PERSPECTIVE=0.6f", "PERSPECTIVE = 0.6f" in cy)

    print("== Java: покой и целостность")
    check("cy: быстрый путь с подписью включая сдвиги строк",
          "Math.abs(layoutSum - lastLayoutSum) < 0.5f" in cy
          and "if (applied && scrollOffset == lastScrollOffset && count == lastChildCount" in cy)
    check("cy: layoutSum считается ДО ветвления и обновляется",
          cy.find("float layoutSum = 0f;") < cy.find("lastLayoutSum = layoutSum;"))
    check("cy: !wants() по-прежнему делает полный сброс",
          "if (!wants()) {" in cy and "if (applied) {\n                reset();\n            }" in cy)
    check("cy: clipBounds на ячейке (run415) не тронут",
          "child.setClipBounds(clip)" in cy and "child.setClipBounds(null)" in cy)

    print("== Java: legacy-путь API<33 сохранён (без потери качества старых устройств)")
    check("legacy: поворот вокруг ребра у центра",
          "child.setPivotY(centerY <= anchor ? child.getHeight() : 0f)" in cy
          and "child.setRotationX(angle)" in cy)
    check("legacy: камера CAMERA_BOOST=4f", "CAMERA_BOOST = 4f" in cy
          and "child.setCameraDistance" in cy)
    check("legacy: полный возврат в reset",
          "child.setRotationX(0f)" in cy and "setPivotY(child.getHeight() / 2f)" in cy)

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run418 cylinder-bend asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
