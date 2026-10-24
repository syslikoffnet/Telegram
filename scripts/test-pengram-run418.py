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

    print("== AGSL: перспективная карточка (v3: наклон + трапеция)")
    check("agsl: uniforms off/span/kappa/sigmas/size", all(t in ag for t in (
        "uniform shader img;", "uniform float off;", "uniform float span;",
        "uniform float kappa;", "uniform float2 sigmas;", "uniform float2 size;")))
    check("agsl: обратная доля dst q=(y-off)/span", "(fragCoord.y - off) / span" in ag)
    check("agsl: рациональный перспективный наклон (дальняя половина сильнее)",
          "denom = (1.0 + kappa) - kappa * q" in ag and "t = q / denom" in ag)
    check("agsl: трапеция по X между краевыми сужениями",
          "sigmas.x + (sigmas.y - sigmas.x) * t" in ag)
    check("agsl: защита вырожденных случаев (span/denom/sigma)",
          "span <= 0.0001" in ag and "denom <= 0.05" in ag and "sigma < 0.05" in ag)
    check("agsl: старой математики больше нет",
          "asin" not in ag and "neutral" not in ag and "yscale" not in ag)
    check("agsl: обрезка листа по Y и по X (прозрачность)",
          ag.count("return half4(0.0);") >= 4)
    check("agsl: единственный сэмпл img.eval", ag.count("img.eval") == 1
          and "img.eval(float2(sx, s))" in ag)
    check("agsl: сигнатура как у liquid", "half4 main(in float2 fragCoord)" in ag)

    print("== Java: края от глобальной окружности, наклон + трапеция")
    check("cy: PHI_MAX предел угла", "PHI_MAX = 1.35f" in cy and "clampPhi" in cy)
    check("cy: оба края ячейки кладутся на окружность",
          "phi0 = clampPhi((top - anchor) / radius)" in cy
          and "phi1 = clampPhi((top + h - anchor) / radius)" in cy)
    check("cy: позиции краёв от sin окружности (швы точны)",
          "d0 = anchor + (float) Math.sin(phi0) * radius - top" in cy
          and "d1 = anchor + (float) Math.sin(phi1) * radius - top" in cy)
    check("cy: наклон от среднего угла карточки (KAPPA_MAX)",
          "KAPPA_MAX = 0.35f" in cy
          and "kappa = phiMid / PHI_MAX * KAPPA_MAX" in cy
          and "phiMid = clampPhi((top + h / 2f - anchor) / radius)" in cy)
    check("cy: трапеция от краевых углов (edgeSigma)",
          "PERSPECTIVE = 0.75f" in cy
          and "sigmaTop = edgeSigma(phi0)" in cy
          and "sigmaBottom = edgeSigma(phi1)" in cy
          and "1f + (1f - (float) Math.cos(phi)) * PERSPECTIVE" in cy)
    check("cy: bind(off, span, kappa, sigmas…)", "bind(d0, d1 - d0, kappa, sigmaTop, sigmaBottom, w, h)" in cy)
    check("cy: uniforms off/span/kappa/sigmas/size", all(t in cy for t in (
          'setFloatUniform("off", off)', 'setFloatUniform("span", span)',
          'setFloatUniform("kappa", kappa)', 'setFloatUniform("sigmas", sigmaTop, sigmaBottom)',
          'setFloatUniform("size", width, height)')))
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
    print("all run418 v3 (perspective card) asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
