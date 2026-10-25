#!/usr/bin/env python3
"""run421 — ДВА ВАЛИКА (эталонное видео) + остров на неуязвимой вёрстке.

1. Цилиндр: два валика сверху/снизу — плоское полотно между касательными,
   у краёв намотка с перспективным сужением и погасанием «за экран».
   Швы точны: касательные от одной глобальной геометрии.
2. Меню: страница острова — ScrollView + простой LinearLayout (без чужой
   measure-логики), фабрика на сбое отдаёт рабочую emergency-страницу,
   createPopupLayout/setBlurredBackgroundFactory под guard'ами,
   swipeback-ветка идёт через getPopupLayout().
"""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SHADER = os.path.join(ROOT, "TMessagesProj/src/main/res/raw/pengram_cylinder_bend.agsl")
CY = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java")
WRAP = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramChatMenuWrapper.java")
AMI = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarMenuItem.java")
CHAT = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    ag = io.open(SHADER, encoding="utf-8").read()
    cy = io.open(CY, encoding="utf-8").read()
    wr = io.open(WRAP, encoding="utf-8").read()
    ami = io.open(AMI, encoding="utf-8").read()
    chat = io.open(CHAT, encoding="utf-8").read()

    print("== AGSL: два валика")
    check("agsl: uniforms localT/localB/roller/size", all(t in ag for t in (
        "uniform shader img;", "uniform float localT;", "uniform float localB;",
        "uniform float roller;", "uniform float2 size;")))
    check("agsl: верхняя намотка s = localT - roller*asin",
          "sn = (localT - y) / roller" in ag and "s = localT - roller * asin(sn)" in ag)
    check("agsl: нижняя намотка симметрично",
          "sn = (y - localB) / roller" in ag and "s = localB + roller * asin(sn)" in ag)
    check("agsl: плоская середина — identity", "s = y;" in ag)
    check("agsl: cull за валиком (sn > 1) и за границей листа",
          ag.count("sn > 1.0") >= 2 and "s < -1.0 || s > size.y + 1.0" in ag)
    check("agsl: мягкая перспектива 2.0/(2.0+1-cos)",
          "scale = 2.0 / (2.0 + back)" in ag and "back = 1.0 - cos(phi)" in ag)
    check("agsl: без гашения (гнёт, не переворот — ряды не растворяются)",
          "alpha" not in ag and "0.94" not in ag)
    check("agsl: единственный сэмпл img.eval", ag.count("img.eval") == 1)

    print("== Java: касательные от краёв списка")
    check("cy: радиусы валиков150/340", "RADIUS_TIGHT_DP = 150f" in cy and "RADIUS_GENTLE_DP = 340f" in cy)
    check("cy: inset = EDGE_K*radius — у края ~53°, сгиб без переворота",
          "ROLLER_EDGE_K = 0.8f" in cy
          and "Math.min(radius * ROLLER_EDGE_K, span / 3f)" in cy
          and "insetTop = topEdge + band" in cy and "insetBottom = bottomEdge - band" in cy)
    check("cy: локальные касательные ячейки", "localT = insetTop - top" in cy
          and "localB = insetBottom - top" in cy)
    check("cy: top с учётом сдвига", "child.getTop() + child.getTranslationY()" in cy)
    check("cy: bind(localT, localB, radius, w, h) + uniforms",
          "bind(localT, localB, radius, w, h)" in cy
          and 'setFloatUniform("localT", localT)' in cy
          and 'setFloatUniform("roller", roller)' in cy)
    check("cy: формула ползунка сохранена",
          "radiusDp = RADIUS_GENTLE_DP + (RADIUS_TIGHT_DP - RADIUS_GENTLE_DP) * strength" in cy)
    check("cy: путь API<33 — mesh-варп с той же картой",
          "warp.setGeometry(insetTop, insetBottom, radius)" in cy
          and "setChildWarp(scroll.warp)" in cy and "child.setRotationX" not in cy)

    print("== Меню: страница без чужой механики + неуязвимая фабрика")
    check("wrap: корень — ScrollView, контент — простой LinearLayout",
          "public final ScrollView page;" in wr and "public final LinearLayout windowLayout;" in wr
          and "windowLayout = new LinearLayout(context);" in wr)
    check("wrap: фон и контраст — один источник pageBackground()",
          "ColorDrawable(pageBackground())" in wr and "final int bg = pageBackground();" in wr)
    check("wrap: кнопка закрытия всегда + по-элементные guard'ы",
          "closeItem.setOnClickListener(view -> callback.dismiss());" in wr
          and wr.count("catch (Throwable e)") >= 4)
    check("wrap: emergency-страница на чистых TextView с пунктами",
          "public static View buildEmergencyPage(" in wr
          and "addSimpleRow(context, root, LocaleController.getString(R.string.Back)" in wr
          and "PengramConfig.getChatItemTitle(itemId)" in wr)
    check("chat: фабрика возвращает wrapper.page, при сбое — emergency",
          "return wrapper.page;" in chat
          and "return org.telegram.ui.Components.PengramChatMenuWrapper.buildEmergencyPage(" in chat)
    check("chat: фабрика больше не трогает getPopupLayout (убран NPE-источник)",
          "pengramCreateIslandView" in chat
          and "headerItem.getPopupLayout().getSwipeBack()" not in chat.split("pengramCreateIslandView", 1)[1].split("\n    };", 1)[0])

    print("== AMI: guard'ы штатной цепочки")
    check("ami: createPopupLayout — blur-фон под try/catch",
          ami.split("private void createPopupLayout()", 1)[1].split("popupLayout.setOnTouchListener", 1)[0].count("catch (Throwable e)") >= 1)
    check("ami: setBlurredBackgroundFactory — create под try/catch",
          ami.split("public void setBlurredBackgroundFactory", 1)[1].split("public void toggleSubMenu", 1)[0].count("catch (Throwable e)") >= 1)
    check("ami: swipeback-ветка через getPopupLayout()",
          "parentLayout = parent.getPopupLayout()" in ami and "parentLayout.addViewToSwipeBack" in ami)
    check("ami: layoutLazyItems по-элементно (run419 держится)",
          "org.telegram.messenger.FileLog.e(e);" in ami)

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run421 (two-rollers + bulletproof island) asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
