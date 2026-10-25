#!/usr/bin/env python3
"""run417 — оптимизация FPS: блик Sheen без вечного цикла + idle fast-path цилиндра."""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SH = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramGlassSheen.java")
CY = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    sh = io.open(SH, encoding="utf-8").read()
    cy = io.open(CY, encoding="utf-8").read()

    print("== PengramGlassSheen: конечный проход вместо вечного 30 fps цикла")
    # 1. Состояние запуска и константы окна
    check("sheen: runStart инициализируется -1", "private long runStart = -1;" in sh)
    check("sheen: SWEEP_MS=4000 (полный проход)", "private static final long SWEEP_MS = 4000;" in sh)
    check("sheen: GRACE_MS=100 (поглощение кадра затухания)", "private static final long GRACE_MS = 100;" in sh)
    # 2. configure запускает свежий проход
    check("sheen: configure сбрасывает runStart",
          sh.count("runStart = SystemClock.uptimeMillis();") >= 1
          and "runStart = SystemClock.uptimeMillis();" in sh.split("public void configure")[1].split("public void draw")[0])
    # 3. После прохода (>= SWEEP_MS) в пределах grace — ранний return БЕЗ рисования
    #    и БЕЗ планирования: цикл самогасится
    check("sheen: гашение в покое (elapsed>=SWEEP -> return)",
          "if (elapsed >= SWEEP_MS) {" in sh and "if (elapsed < SWEEP_MS + GRACE_MS) return;" in sh)
    guard_i = sh.find("if (elapsed < SWEEP_MS + GRACE_MS) return;")
    loop_i = sh.find("view.postInvalidateDelayed(32);")
    check("sheen: postInvalidateDelayed строго ПОСЛЕ гашения", guard_i != -1 and loop_i > guard_i)
    # 4. Фаза берётся из elapsed (один проход), а не из вечного uptimeMillis()%4000
    check("sheen: фаза от runStart, не от uptime%4000",
          "elapsed / (float) SWEEP_MS" in sh and "% 4000" not in sh)
    # 5. Внешняя перерисовка после паузы — новый проход (блик по событию остаётся)
    check("sheen: рестарт по внешней перерисовке после grace",
          "runStart = now; // внешняя перерисовка спустя паузу" in sh)

    print("== PengramCylinderScroll: пропуск идентичных кадров")
    check("cyl: поля быстрого пути", all(t in cy for t in
          ("private int lastScrollOffset = -1;", "private int lastChildCount = -1;", "private int lastTargetHeight = -1;")))
    check("cyl: сигнатура кадра (offset+count+height)",
          "final int scrollOffset = target.computeVerticalScrollOffset();" in cy
          and "final int targetHeight = target.getHeight();" in cy)
    check("cyl: пропуск при неизменной сигнатуре",
          "if (applied && scrollOffset == lastScrollOffset && count == lastChildCount" in cy)
    check("cyl: обновление сигнатуры после проверки",
          "lastScrollOffset = scrollOffset;\n        lastChildCount = count;\n        lastTargetHeight = targetHeight;" in cy)
    # порядок: fast-path стоит ДО силы/радиуса (дешёвый выход раньше тяжёлых вычислений)
    fp_i = cy.find("lastTargetHeight = targetHeight;")
    st_i = cy.find("final float strength = PengramConfig.getCylinderScrollStrength()")
    check("cyl: fast-path ДО вычисления strength/radius", fp_i != -1 and st_i > fp_i)
    check("cyl: полный сброс при !wants()/reset сохранился",
          "if (!wants()) {" in cy and "if (applied) {\n                reset();\n            }" in cy)
    check("cyl: полный сброс в reset (клипы + mesh-захваты)",
          "child.setClipBounds(null)" in cy and "warp.clear();" in cy)

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run417 optimization asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
