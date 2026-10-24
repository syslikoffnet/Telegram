#!/usr/bin/env python3
"""run418 → v4: два валика (эталонное видео run414).

Модель: касательные на radius от верхнего/нижнего края списка; между ними
полотно плоское (identity); у краёв — наматывание на валик (asin-обратная
карта), перспективное сужение, погасание у кромки. Швы точны: касательные
одинаковы для всех ячеек. Плюс наследие: clipBounds, fast-path покоя,
legacy-путь API<33.
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

    print("== AGSL: два валика — плоская середь + намотка краёв")
    check("agsl: uniforms localT/localB/roller/size", all(t in ag for t in (
        "uniform shader img;", "uniform float localT;", "uniform float localB;",
        "uniform float roller;", "uniform float2 size;")))
    check("agsl: верх — обратная карта через asin", "sn = (localT - y) / roller" in ag
          and "s = localT - roller * asin(sn)" in ag)
    check("agsl: низ — симметричная обратная карта", "sn = (y - localB) / roller" in ag
          and "s = localB + roller * asin(sn)" in ag)
    check("agsl: между касательными — чистый identity", "s = y;" in ag)
    check("agsl: cull за валиком и за границей полотна",
          ag.count("sn > 1.0") >= 2 and "s < -1.0 || s > size.y + 1.0" in ag)
    check("agsl: мягкая перспектива 2.0 (без flip-щелчка)",
          "back = 1.0 - cos(phi)" in ag and "scale = 2.0 / (2.0 + back)" in ag)
    check("agsl: без гашения — ряды не растворяются", "alpha" not in ag and "0.94" not in ag)
    check("agsl: единственный сэмпл img.eval", ag.count("img.eval") == 1
          and "img.eval(float2(sx, s))" in ag)
    check("agsl: сигнатура как у liquid", "half4 main(in float2 fragCoord)" in ag)

    print("== Java: касательные от краёв, ползунок, наследие")
    check("cy: радиусы валиков150/340dp (сила → сочность завитка)",
          "RADIUS_TIGHT_DP = 150f" in cy and "RADIUS_GENTLE_DP = 340f" in cy)
    check("cy: EDGE_K=0.8 — у края остаётся ~53°, ряды гнутся без переворота",
          "ROLLER_EDGE_K = 0.8f" in cy
          and "Math.min(radius * ROLLER_EDGE_K, span / 3f)" in cy
          and "insetTop = topEdge + band" in cy and "insetBottom = bottomEdge - band" in cy)
    check("cy: касательные локально: inset - top / (H - inset) - top",
          "localT = insetTop - top" in cy and "localB = insetBottom - top" in cy)
    check("cy: top с учётом сдвига (анимации едут вместе)",
          "child.getTop() + child.getTranslationY()" in cy)
    check("cy: bind(localT, localB, radius, w, h)", "bind(localT, localB, radius, w, h)" in cy)
    check("cy: uniforms localT/localB/roller/size", all(t in cy for t in (
          'setFloatUniform("localT", localT)', 'setFloatUniform("localB", localB)',
          'setFloatUniform("roller", roller)', 'setFloatUniform("size", width, height)')))
    check("cy: формула ползунка не тронута",
          "radiusDp = RADIUS_GENTLE_DP + (RADIUS_TIGHT_DP - RADIUS_GENTLE_DP) * strength" in cy)
    check("cy: ноль размера не считаем", "if (warp && w > 0 && h > 0)" in cy)

    print("== Наследие run415/417/419")
    check("cy: clipBounds на ячейке (run415)",
          "child.setClipBounds(clip)" in cy and "child.setClipBounds(null)" in cy)
    check("cy: быстрый путь покоя с layoutSum (run417)",
          "Math.abs(layoutSum - lastLayoutSum) < 0.5f" in cy
          and "if (applied && scrollOffset == lastScrollOffset && count == lastChildCount" in cy)
    check("cy: !wants() полный сброс", "if (!wants()) {" in cy
          and "if (applied) {\n                reset();\n            }" in cy)
    check("cy: ворота API33 + readRes + try/catch шейдера",
          "Build.VERSION_CODES.TIRAMISU" in cy and "R.raw.pengram_cylinder_bend" in cy
          and "new RuntimeShader(code)" in cy and "catch (Throwable t)" in cy)
    check("cy: снятие эффекта", cy.count("setRenderEffect(null)") >= 2 and "bends.clear();" in cy)

    print("== Legacy API<33")
    check("legacy: поворот вокруг ребра у центра",
          "child.setPivotY(centerY <= anchor ? child.getHeight() : 0f)" in cy
          and "child.setRotationX(angle)" in cy)
    check("legacy: мягкий знаменатель (радиус валиков мал)",
          "LEGACY_MIN_RADIUS_DP" in cy and "legacyRadius" in cy)
    check("legacy: камера и возврат в reset", "CAMERA_BOOST = 4f" in cy
          and "child.setRotationX(0f)" in cy and "setPivotY(child.getHeight() / 2f)" in cy)

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run418 v4 (two-roller) asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
