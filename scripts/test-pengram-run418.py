#!/usr/bin/env python3
"""run418 → v2: кусочно-аффинная намотка цилиндра.

Каждое сообщение — жёсткая карточка на валике: внутри ячейки точный аффин
(текст/фото/рамка едут вместе), края — от глобальной окружности (швы точны).
Плюс наследие: clipBounds, fast-path покоя, legacy-путь API<33.
"""
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

    print("== AGSL: кусочно-аффин внутри ячейки")
    check("agsl: uniforms off/yscale/sigma/size", all(t in ag for t in (
        "uniform shader img;", "uniform float off;", "uniform float yscale;",
        "uniform float sigma;", "uniform float2 size;")))
    check("agsl: обратное аффинное отображение (dst-off)/yscale",
          "(fragCoord.y - off) / yscale" in ag)
    check("agsl: защита от нулевого масштаба", "yscale <= 0.0001" in ag)
    check("agsl: старой нелинейной математики больше нет",
          "asin" not in ag and "neutral" not in ag and "radius" not in ag)
    check("agsl: обрезка листа по Y и по X (прозрачность)",
          ag.count("return half4(0.0);") >= 3)
    check("agsl: единственный сэмпл img.eval", ag.count("img.eval") == 1
          and "img.eval(float2(sx, s))" in ag)
    check("agsl: сигнатура как у liquid", "half4 main(in float2 fragCoord)" in ag)

    print("== Java: края от глобальной окружности, карточка внутри")
    check("cy: PHI_MAX предел угла", "PHI_MAX = 1.35f" in cy and "clampPhi" in cy)
    check("cy: оба края ячейки кладутся на окружность",
          "phi0 = clampPhi((top - anchor) / radius)" in cy
          and "phi1 = clampPhi((top + h - anchor) / radius)" in cy)
    check("cy: позиции краёв от sin окружности",
          "d0 = anchor + (float) Math.sin(phi0) * radius - top" in cy
          and "d1 = anchor + (float) Math.sin(phi1) * radius - top" in cy)
    check("cy: аффинный масштаб между краями", "yscale = (d1 - d0) / h" in cy)
    check("cy: перспективное сужение от среднего угла",
          "(float) Math.cos((phi0 + phi1) * 0.5f)) * PERSPECTIVE" in cy)
    check("cy: bind(off, yscale, sigma, w, h)", "bind(d0, yscale, sigma, w, h)" in cy)
    check("cy: uniforms off/yscale/sigma/size", all(t in cy for t in (
          'setFloatUniform("off", off)', 'setFloatUniform("yscale", yscale)',
          'setFloatUniform("sigma", sigma)', 'setFloatUniform("size", width, height)')))
    check("cy: top с учётом сдвига (анимации едут вместе)",
          "child.getTop() + child.getTranslationY()" in cy)
    check("cy: ноль размера не считаем (h>0 в ветке)", "if (warp && w > 0 && h > 0)" in cy)

    print("== Java: наследие run415/417")
    check("cy: clipBounds на ячейке (run415)",
          "child.setClipBounds(clip)" in cy and "child.setClipBounds(null)" in cy)
    check("cy: быстрый путь покоя с layoutSum (run417)",
          "Math.abs(layoutSum - lastLayoutSum) < 0.5f" in cy
          and "if (applied && scrollOffset == lastScrollOffset && count == lastChildCount" in cy)
    check("cy: сила/RADIUS формула не тронута",
          "RADIUS_TIGHT_DP = 760f" in cy
          and "radiusDp = RADIUS_GENTLE_DP + (RADIUS_TIGHT_DP - RADIUS_GENTLE_DP) * strength" in cy)
    check("cy: !wants() полный сброс", "if (!wants()) {" in cy
          and "if (applied) {\n                reset();\n            }" in cy)

    print("== Java: ворота и legacy (API<33)")
    check("cy: ворота API33 + readRes", "Build.VERSION_CODES.TIRAMISU" in cy
          and "R.raw.pengram_cylinder_bend" in cy)
    check("cy: RuntimeShader под try/catch", "new RuntimeShader(code)" in cy
          and "catch (Throwable t)" in cy)
    check("cy: снятие эффекта", cy.count("setRenderEffect(null)") >= 2 and "bends.clear();" in cy)
    check("legacy: поворот вокруг ребра у центра",
          "child.setPivotY(centerY <= anchor ? child.getHeight() : 0f)" in cy
          and "child.setRotationX(angle)" in cy)
    check("legacy: камера и возврат в reset", "CAMERA_BOOST = 4f" in cy
          and "child.setRotationX(0f)" in cy and "setPivotY(child.getHeight() / 2f)" in cy)

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run418 v2 (piecewise-affine) asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
