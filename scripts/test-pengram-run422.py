#!/usr/bin/env python3
"""run422 — тумблеры не «слетают» после скролла + цилиндр гнёт, не переворачивает.

Тумблер: прямые писатели (setGhostMode, MENU_ITEM_*, setSpyOption…) идут через
putBoolean в обход setBool — раньше кэш не обновлялся, getBool отдавал
старое, список пересобирался с ложным выключением после скролла вверх-вниз.
Фикс: boolCache следует за КАЖДОЙ реальной записью (симметрично putInt).

Цилиндр: EDGE_K=0.8 (у края остаётся ~53° — ряды гнутся, не схлопываются в
линию), перспектива смягчена до 2.0, гашение убрано — без «переворота».
"""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
CFG = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
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
    cfg = io.open(CFG, encoding="utf-8").read()
    ag = io.open(SHADER, encoding="utf-8").read()
    cy = io.open(CY, encoding="utf-8").read()

    print("== PengramConfig: кэш всегда следует за записью")
    body = cfg.split("private static void putBoolean(String key, boolean value)", 1)[1].split("}", 1)[0]
    check("putBoolean: boolCache.put после stage-гарда",
          "if (stageVisual(key, value)) return;" in body and "boolCache.put(key, value);" in body)
    check("putInt: intCache.put (симметрия — эталон дизайна)",
          "private static void putInt(String key, int value)" in cfg
          and cfg.split("private static void putInt", 1)[1].split("}", 1)[0].count("intCache.put(key, value);") == 1)
    check("setBool: по-прежнему пишет и кэш, и пресет (идемпотентно)",
          "public static void setBool(String key, boolean value)" in cfg
          and "boolCache.put(key, value);" in cfg.split("public static void setBool", 1)[1].split("public static boolean toggle", 1)[0])
    check("getBool: кэш раньше пресетов — но только свежий",
          "final Boolean cached = boolCache.get(key);" in cfg)

    print("== Цилиндр: сгиб без переворота")
    check("agsl: без гашения/растворения", "alpha" not in ag and "0.94" not in ag)
    check("agsl: мягкая перспектива 2.0", "scale = 2.0 / (2.0 + back)" in ag)
    check("cy: EDGE_K=0.8 — край ~53°, не90°",
          "ROLLER_EDGE_K = 0.8f" in cy
          and "Math.min(radius * ROLLER_EDGE_K, span / 3f)" in cy
          and "insetTop = topEdge + band" in cy and "insetBottom = bottomEdge - band" in cy)
    check("cy: плоская середина и касательные не тронуты",
          "localT = insetTop - top" in cy and "localB = insetBottom - top" in cy)
    check("cy: ползунок и legacy целы",
          "radiusDp = RADIUS_GENTLE_DP + (RADIUS_TIGHT_DP - RADIUS_GENTLE_DP) * strength" in cy
          and "legacyRadius" in cy)

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run422 (toggle cache + bend-not-flip) asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
