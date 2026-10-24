#!/usr/bin/env python3
"""run419 — меню «три точки»: устойчивость цепочки сборки.

Жалоба: 3 точки → Pengram — «нету меню, всё чёрное, не работает».
Корень класса: сборка меню была БЕЗ единой защиты — одно исключение на любом
lazy-пункте или в фабрике острова убивало весь попап (чёрный экран, мёртвые
клики). Теперь: по-элементный guard в layoutLazyItems, guard фабрики с
существующей страницей-фолбэком, по-элементный rebuild обёртки, всегда есть
кнопка закрытия, фон страницы с фолбэком на базовую тему.
"""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
AMI = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarMenuItem.java")
WRAP = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramChatMenuWrapper.java")
CHAT = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    ami = io.open(AMI, encoding="utf-8").read()
    wr = io.open(WRAP, encoding="utf-8").read()
    chat = io.open(CHAT, encoding="utf-8").read()

    print("== layoutLazyItems: сбой одного пункта не убивает меню")
    ll = ami.split("private void layoutLazyItems()", 1)[1].split("\n    public static ActionBarMenuSubItem addItem", 1)[0]
    check("lazy: try вокруг каждого пункта", "try {" in ll and "lazyList.get(i).add(this);" in ll)
    check("lazy: catch(Throwable) + FileLog", "catch (Throwable e)" in ll
          and "org.telegram.messenger.FileLog.e(e);" in ll)
    check("lazy: clear() остаётся", "lazyList.clear();" in ll)

    print("== Фабрика swipe-back страницы: никогда не отдаёт null")
    sw = ami.split("if (swipeBackViewFactory != null) {", 1)[1].split("parent.popupLayout.swipeBackGravityRight = true;", 1)[0]
    check("factory: run() под try/catch", "try {" in sw and "swipeBackViewFactory.run();" in sw
          and "catch (Throwable e)" in sw)
    check("factory: null-страница заменяется существующей",
          "if (viewToSwipeBack == null)" in sw and "viewToSwipeBack = new android.view.View(parent.getContext())" in sw)
    check("factory: фон фолбэка с базовой темой",
          "int fallbackBg = Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, parent.resourcesProvider)" in sw
          and "viewToSwipeBack.setBackgroundColor(fallbackBg);" in sw)
    check("factory: addViewToSwipeBack строго ПОСЛЕ null-guard",
          sw.find("if (viewToSwipeBack == null)") < sw.find("addViewToSwipeBack(viewToSwipeBack)"))

    print("== Обёртка острова: по-элементная сборка + всегда закрытие")
    rb = wr.split("private void rebuild()", 1)[1].split("private void addChatItem", 1)[0]
    check("rebuild: один guard на back/close", rb.count("catch (Throwable e)") >= 2)
    check("rebuild: пункт-кнопка есть и без swipeBack (callback.dismiss)",
          "closeItem.setOnClickListener(view -> callback.dismiss());" in rb)
    check("rebuild: каждый пункт под try",
          "try {\n                addChatItem(items.get(a));\n            } catch (Throwable e)" in rb
          and "try {\n                addQuickAction(i);\n            } catch (Throwable e)" in rb)
    check("wrapper: общий источник фона pageBackground()",
          "private int pageBackground()" in wr and "if (bg == 0)" in wr
          and "Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground);" in wr)
    check("wrapper: заливка и контраст — от одного источника",
          "ColorDrawable(pageBackground())" in wr and "final int bg = pageBackground();" in wr)

    print("== ChatActivity: фабрика острова не бросает наружу")
    fac = chat.split("pengramCreateIslandView = () -> {", 1)[1].split("\n    };", 1)[0]
    check("chat: тело фабрики под try/catch", "try {" in fac and "catch (Throwable e)" in fac)
    check("chat: возврат существующей страницы при сбое",
          "final View fallback = new View(getContext());" in fac and "return fallback;" in fac)
    check("chat: фон фолбэка темы чата с базовым фолбэком",
          "Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, themeDelegate)" in fac)
    check("chat: wrapper-поле не затирается при сбое",
          "pengramMenuWrapper = wrapper;" in fac and "pengramMenuWrapper = wrapper;" in fac.split("catch (Throwable e)")[0])

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run419 menu-resilience asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
