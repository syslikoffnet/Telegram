#!/usr/bin/env python3
"""run425 — две жалобы на run424: «всё плывёт» на цилиндре и перемешанный
текст в пунктах секции «Набор и отправка текста».

1. Цилиндр (SDK<33 legacy-путь — шейдер на Android 11 недоступен):
   а) ортографическая камера — проекция аффинна, содержимое ячейки (фото,
      обводка, строки) едет единым полотном: точка на z ≤ 2000px ошибается
      <1px против старого boost ×4, дававшего гомографический дрейф в десятки
      пикселей между фото и рамкой;
   б) якорь legacy — центр ЗОНЫ изгиба: тумблер «до шапки и поля ввода»
      реально переносит кромку намотки к барам и на Android<13 (раньше был
      no-op: anchor всегда height/2); выкл зоны = прежний height/2 байт-в-байт.
2. Пункты с двумя текстами: длинная подпись (value/subtext > 26) обязана
   ложиться ПОД заголовок внутри самой ячейки при любом состоянии флагов —
   наложение «заголовок поверх описания» на узких экранах исключено.
"""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
CY = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java")
NCC = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Cells/NotificationsCheckCell.java")
TSC = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Cells/TextSettingsCell.java")
SET = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
CHAT = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    cy = io.open(CY, encoding="utf-8").read()
    ncc = io.open(NCC, encoding="utf-8").read()
    tsc = io.open(TSC, encoding="utf-8").read()
    st = io.open(SET, encoding="utf-8").read()
    chat = io.open(CHAT, encoding="utf-8").read()

    print("== Цилиндр: орто-камера (нет внутреннего дрейфа)")
    check("scroll: орто-константа вместо boost ×4",
          "ORTHO_CAMERA_DISTANCE = 1000000f" in cy
          and "CAMERA_BOOST" not in cy)
    check("scroll: дистанция сохраняется и восстанавливается",
          "cameraBackup.put(child, child.getCameraDistance())" in cy
          and "child.setCameraDistance(ORTHO_CAMERA_DISTANCE)" in cy
          and "cameraBackup.remove(child)" in cy
          and "child.setCameraDistance(originalDistance)" in cy)
    check("scroll: поле-мап хранит исходную дистанцию (Float)",
          "WeakHashMap<View, Float> cameraBackup" in cy)

    print("== Цилиндр: якорь по зоне изгиба (to-bars работает на SDK<33)")
    check("scroll: anchor = середина зоны topEdge..bottomEdge",
          "anchor = (topEdge + bottomEdge) / 2f" in cy
          and "getHeight() / 2f;" not in cy.split("anchor = (topEdge + bottomEdge) / 2f")[0])
    check("scroll: выкл to-bars байт-в-байт прежний (topEdge=0, bottomEdge=height)",
          "float topEdge = 0f;" in cy and "float bottomEdge = targetHeight;" in cy)
    check("scroll: угол и пивот legacy используют anchor (ветка !warp)",
          "final float angle = (anchor - centerY) / legacyRadius" in cy
          and "child.setPivotY(centerY <= anchor ? child.getHeight() : 0f)" in cy)
    check("scroll: восстановление в reset не тронуто",
          "child.setRotationX(0f)" in cy
          and "child.setPivotY(child.getHeight() / 2f);" in cy
          and "cameraBackup.clear()" in cy)

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
