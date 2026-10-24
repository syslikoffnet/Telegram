#!/usr/bin/env python3
"""run420 — «три точки»: гарантированная видимость пунктов.

Жалоба: 3 точки → Pengram — «всё чёрное, ни одной кнопки не видно».
Корневой класс: цвета пунктов и фон подменю считались из разных
веток/провайдеров темы — при расхождении текст сливался с фоном и меню
становилось полностью невидимым (клики формально работали, но их не было
видно). Теперь на уровне драв-прохода КАЖДЫЙ пункт сверяется с фактическим
фоном и перекрашивается в контрастный — для всех подменю сразу.
"""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SUB = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarMenuSubItem.java")
APW = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    sub = io.open(SUB, encoding="utf-8").read()
    apw = io.open(APW, encoding="utf-8").read()

    print("== ActionBarMenuSubItem: ensureVisibleContrast")
    check("sub: метод присутствует с джавадоком", "public void ensureVisibleContrast(int bg)" in sub)
    check("sub: люминость фона и текста", "lumBg" in sub and "lumText" in sub
          and "((bg >> 16) & 0xFF) * 299" in sub)
    check("sub: порог слипания 96", "Math.abs(lumBg - lumText) >= 96" in sub)
    check("sub: перекраска в белый/почти-чёрный по фону",
          "lumBg / 1000 < 140 ? 0xFFFFFFFF : 0xFF1C1C1E" in sub)
    check("sub: цвета текста, иконки и подписи вместе",
          "setColors(fix, fix)" in sub and "setSubtextColor(fix)" in sub)
    check("sub: чтение текущего textColor (после любых setColors)",
          "lumText = ((textColor >> 16) & 0xFF)" in sub)

    print("== ActionBarPopupWindowLayout: хук в dispatchDraw")
    check("apw: вызов до super.dispatchDraw", "ensureItemsContrast();" in apw
          and apw.find("ensureItemsContrast();") < apw.find("super.dispatchDraw(canvas);"))
    check("apw: фон — фактический ColorDrawable страницы, иначе ключ темы",
          "getBackground() instanceof android.graphics.drawable.ColorDrawable" in apw
          and "((android.graphics.drawable.ColorDrawable) getBackground()).getColor()" in apw
          and "getThemedColor(Theme.key_actionBarDefaultSubmenuBackground)" in apw)
    check("apw: обход пунктов linearLayout", "for (int i = 0; i < linearLayout.getChildCount(); i++)" in apw)
    check("apw: ActionBarMenuSubItem → ensureVisibleContrast",
          "((ActionBarMenuSubItem) child).ensureVisibleContrast(bg)" in apw)
    check("apw: TextView (текстовые пункты меню) тоже чиним",
          "child instanceof android.widget.TextView" in apw
          and "textView.setTextColor(lumBg / 1000 < 140" in apw)
    check("apw: GapView-пространства и структура не тронуты",
          "child instanceof GapView" in apw)

    print("== связка с run419 (устойчивость сборки)")
    ami = io.open(os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarMenuItem.java"),
                  encoding="utf-8").read()
    check("run419 держится: guard layoutLazyItems", "org.telegram.messenger.FileLog.e(e);" in ami
          and "lazyList.get(i).add(this);" in ami)
    check("run419 держится: guard фабрики + null-страница",
          "swipeBackViewFactory.run();" in ami and "if (viewToSwipeBack == null)" in ami)

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run420 menu-visibility asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
