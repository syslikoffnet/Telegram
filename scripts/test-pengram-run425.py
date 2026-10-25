#!/usr/bin/env python3
"""run425/426 — две жалобы на run424 и полный отказ от «полумер» в цилиндре.

1. Цилиндр: устройство на Android 11 (нет RuntimeShader) получало legacy-
   поворот — «тянется вверх и искажается» вместо гиба по валику. Теперь ту же
   карту шейдера (asin-намотка + x-перспектива от центра + прозрачность вне
   домена) исполняет PengramCylinderWarp: захват контента ячейки в bitmap
   на изменение контента, каждый кадр — один Canvas.drawVertices (GPU),
   позиционные сдвиги перезахвата не требуют. Поворота/камеры/якоря больше
   нет; выключение возвращает клипы и выпускает захваты.
2. Пункты с двумя текстами: длинная подпись (value/subtext > 26) обязана
   ложиться ПОД заголовок внутри самой ячейки при любом состоянии флагов —
   наложение «заголовок поверх описания» на узких экранах исключено.
"""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
CY = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java")
WARP = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderWarp.java")
RLV = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/RecyclerListView.java")
NCC = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Cells/NotificationsCheckCell.java")
TSC = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Cells/TextSettingsCell.java")
SET = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
CHAT = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
SHADER = os.path.join(ROOT, "TMessagesProj/src/main/res/raw/pengram_cylinder_bend.agsl")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    cy = io.open(CY, encoding="utf-8").read()
    warp = io.open(WARP, encoding="utf-8").read()
    rlv = io.open(RLV, encoding="utf-8").read()
    ncc = io.open(NCC, encoding="utf-8").read()
    tsc = io.open(TSC, encoding="utf-8").read()
    st = io.open(SET, encoding="utf-8").read()
    chat = io.open(CHAT, encoding="utf-8").read()
    shader = io.open(SHADER, encoding="utf-8").read()

    print("== Цилиндр: настоящий гиб на всех API (mesh-варп вместо поворота)")
    check("scroll: воин создан и зарегистрирован в списке",
          "PengramCylinderWarp warp = new PengramCylinderWarp()" in cy
          and "setChildWarp(scroll.warp)" in cy)
    check("scroll: геометрия (те же localT/localB/roller шейдера) публикуется воину",
          "warp.setGeometry(insetTop, insetBottom, radius)" in cy
          and "warp.deactivate()" in cy)
    check("scroll: legacy-поворот/камера/якорь вырезаны целиком",
          "setRotationX" not in cy and "setCameraDistance" not in cy
          and "ORTHO_CAMERA" not in cy and "LEGACY_MIN" not in cy
          and "anchor" not in cy)
    check("scroll: выключение — полный сброс (клипы + захваты)",
          "warp.clear();" in cy and "child.setClipBounds(null)" in cy)
    check("scroll: шейдерный путь для API>=33 не тронут",
          "R.raw.pengram_cylinder_bend" in cy and "new RuntimeShader(code)" in cy
          and "shader = ensureWarp()" in cy and "bind(localT, localB, radius, w, h)" in cy)

    print("== Варп: карта шейдера, перенесённая попиксельно")
    check("warp: asin-намотка у касательной — та же формула",
          "localT - roller * phi" in warp and "(float) Math.asin(sn)" in warp
          and "s = localB + roller * phi" in warp)
    check("warp: x-перспектива от центра — 2/(2+1-cos) как в .agsl",
          "float scale = 2f / (2f + back)" in warp
          and "cx + (0f - cx) / scale" in warp and "cx + (w - cx) / scale" in warp)
    check("warp: вне домена — прозрачность (в рамку захвата, как half4(0))",
          "sn > 1f" in warp and "texs[r * 4] = OUT" in warp
          and "Shader.TileMode.CLAMP" in warp)
    check("шпаргалка шейдера не изменилась (паритет гарантирован формулой)",
          "s = localT - roller * asin(sn)" in shader
          and "scale = 2.0 / (2.0 + back)" in shader)
    check("warp: захват — один раз на изменение контента, позиции не перезахватываем",
          "child.draw(onto)" in warp and "capture.stale" in warp
          and "transformMatches(child)" in warp and "capture.snapshot(child)" in warp)
    check("warp: каждый кадр — один drawVertices, сетка кэшируется по размеру",
          "Canvas.VertexMode.TRIANGLES" in warp and "drawVertices(" in warp
          and "ensureGrid(capture, rows)" in warp)
    check("warp: центр списка рисует список сам (вне зоны — без нагрузки)",
          "return false" in warp and "localT <= 0f && localB >= h" in warp)

    print("== Хук в RecyclerListView: строго null-guarded")
    check("rlv: интерфейс и установка",
          "interface ChildWarp" in rlv and "setChildWarp(ChildWarp warp)" in rlv)
    check("rlv: drawChild предлагает воину первым делом",
          "childWarp != null && childWarp.draw(canvas, child)" in rlv)
    check("rlv: инвалидации и открепление доходят до воина",
          "onDescendantInvalidated" in rlv
          and "public void onChildDetachedFromWindow(View child)" in rlv
          and "childWarp.onChildInvalidate(child)" in rlv
          and "childWarp.onChildDetached(child)" in rlv
          and "invalidateChild(View child, Rect dirty)" not in rlv)

    print("== Регрессия run423: тумблер и лямбда баров целы")
    check("chat:4-арг attach с лямбдой баров", "pengramCylinderBars" in chat)
    check("set: строка BTN_EXTRA_BASE + 66", "BTN_EXTRA_BASE + 66" in st)

    print("== Пункты: длинная подпись всегда под заголовком")
    check("NotificationsCheckCell: авто-stacked при value > 26",
          "stackValue = multiline || adaptiveLayout" in ncc
          and "value.length() > 26" in ncc
          and "setMultiline(stackValue)" in ncc)
    check("NotificationsCheckCell: стек-ветка onMeasure (высота растёт)",
          "hasValue = isMultiline && !TextUtils.isEmpty(multilineValueTextView.getText())" in ncc
          and "valueParams.topMargin = dp(10) + textView.getMeasuredHeight() + dp(3)" in ncc)
    check("TextSettingsCell: длинное значение форсирует адаптив",
          "adaptive = adaptive || value != null && value.length() > 26" in tsc
          and "if (adaptive) valueTextView.setVisibility(INVISIBLE);" in tsc)
    check("TextSettingsCell: стек-мерка с пересчётом верхней кромки value",
          "valueParams.topMargin = used + AndroidUtilities.dp(3)" in tsc
          and "adaptiveLayout = adaptive;" in tsc)

    print("== fillTyping: пары заголовок/описание не перепутаны")
    check("set: секция строит checkInfo с описаниями и settings-ячейки",
          "private void fillTyping" in st
          and "checkInfo(PengramConfig.KEY_SEND_STYLE_LARGE_EMOJI" in st
          and "checkInfo(org.telegram.messenger.PengramTypingStats.KEY_ENABLED" in st)
    check("set: глобальный adaptive-цикл для чек/setting-типов на месте",
          "item.adaptiveLayout = true;" in st
          and "VIEW_TYPE_TEXT_SETTINGS" in st)

    print()
    if fails:
        print("FAIL:", len(fails))
        for f in fails:
            print("  -", f)
        sys.exit(1)
    print("ALL OK")


if __name__ == "__main__":
    main()
