#!/usr/bin/env python3
"""run423 — тумблер «изгиб до шапки и поля ввода» + локальный эмодзи-статус.

1. Цилиндр: новый тумблер (по умолчанию ВЫКЛ) — валики привязываются к низу
   шапки и верху ввода: полоса сгиба видна целиком между барами, исчезновение
   уходит под бар (EDGE_K ≤ 1). Выкл — прежняя геометрия по краям списка.
2. Локальный премиум: выбранный эмодзи-статус сохраняется локально (любой TL-
   тип, Base64), серверный запрос при выключенной премии не шлётся, перехват
   в putUser не даёт серверной заливке затереть выбор после чата.
"""
import io, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
CFG = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
UC = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")
MC = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
CY = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java")
CHAT = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
SET = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
EN = os.path.join(ROOT, "TMessagesProj/src/main/res/values/strings.xml")
RU = os.path.join(ROOT, "TMessagesProj/src/main/res/values-ru/strings.xml")

fails = []


def check(name, ok, detail=""):
    if ok:
        print("  ok  ", name)
    else:
        fails.append(name)
        print("  FAIL", name, detail)


def main():
    cfg = io.open(CFG, encoding="utf-8").read()
    uc = io.open(UC, encoding="utf-8").read()
    mc = io.open(MC, encoding="utf-8").read()
    cy = io.open(CY, encoding="utf-8").read()
    chat = io.open(CHAT, encoding="utf-8").read()
    st = io.open(SET, encoding="utf-8").read()
    en = io.open(EN, encoding="utf-8").read()
    ru = io.open(RU, encoding="utf-8").read()

    print("== Тумблер «до шапки и ввода» (по умолчанию off)")
    check("cfg: ключ и getter со значением false по умолчанию",
          'KEY_CYLINDER_TO_BARS = "chatCylinderToBars"' in cfg
          and "isCylinderScrollToBars() { return getBool(KEY_CYLINDER_TO_BARS, false); }" in cfg)
    check("cfg: setter переключателя", "setCylinderScrollToBars(boolean value)" in cfg)
    check("settings: строка-тумблер BTN_EXTRA_BASE+66 рядом с цилиндром",
          "BTN_EXTRA_BASE + 66" in st and "PengramCylinderToBars" in st
          and st.find("BTN_EXTRA_BASE + 66") > st.find("BTN_EXTRA_BASE + 65"))
    check("strings: EN и RU присутствуют",
          'PengramCylinderToBars">' in en and 'PengramCylinderToBarsInfo">' in en
          and 'PengramCylinderToBars">' in ru and 'PengramCylinderToBarsInfo">' in ru)

    print("== Геометрия: границы зоны вместо жёстких краёв")
    check("scroll: BarsProvider + attach с4 аргументами",
          "interface BarsProvider" in cy and "Callable<Boolean> toBarsEnabled" in cy
          and "attach(RecyclerView target, Callable<Boolean> enabled,\n                                               Callable<Boolean> toBarsEnabled, BarsProvider bars)" in cy)
    check("scroll: edges считаются (OFF = края списка, ON = бары)",
          "float topEdge = 0f;" in cy and "float bottomEdge = targetHeight;" in cy
          and "toBars = Boolean.TRUE.equals(toBarsEnabled.call());" in cy)
    check("scroll: band от span зоны, тангенсы внутрь от границ",
          "Math.min(radius * ROLLER_EDGE_K, span / 3f)" in cy
          and "insetTop = topEdge + band" in cy and "insetBottom = bottomEdge - band" in cy)
    check("scroll: подпись быстрого пути учитывает бары",
          "barsSig == lastBarsSig" in cy and "lastBarsSig = barsSig;" in cy)
    check("chat: attach с поставщиком баров и переиспользуемыми массивами",
          "pengramCylinderBarsTmp" in chat and "pengramCylinderScratch" in chat
          and "PengramConfig::isCylinderScrollToBars" in chat)
    check("chat: шапка и ввод через location в координатах списка",
          "actionBar.getLocationInWindow(scratch)" in chat
          and "chatActivityEnterView.getLocationInWindow(scratch)" in chat
          and "chatListView.getLocationInWindow(scratch)" in chat)

    print("== Локальный эмодзи-статус: сохранение и перехват")
    check("cfg: ключ localEmojiStatus входит в reset-набор",
          '"localPremiumStatus", "localEmojiStatus"' in cfg
          and 'KEY_LOCAL_EMOJI_STATUS = "localEmojiStatus"' in cfg)
    check("userconfig: сериализация TL-объекта в Base64 (любой тип статуса)",
          "pengramSaveLocalEmojiStatus" in uc
          and "status.serializeToStream(data)" in uc
          and "android.util.Base64.encodeToString" in uc)
    check("userconfig: восстановление через TLdeserialize",
          "TLRPC.EmojiStatus.TLdeserialize(data, data.readInt32(false), false)" in uc)
    check("userconfig: apply наложит при включённой фиче и снимет только своё",
          "public void pengramApplyLocalEmojiStatus(TLRPC.User user)" in uc
          and "PengramConfig.isPremiumStatusLocal()" in uc
          and "Arrays.equals(a.toByteArray(), b.toByteArray())" in uc)
    check("userconfig: apply вызывается и из loadConfig",
          "pengramApplyLocalEmojiStatus(currentUser);" in uc)
    check("userconfig: pengramApplyLocalPremiumStatus тоже применяет статус",
          "final boolean statusBefore = currentUser.emoji_status != null;" in uc
          and "UPDATE_MASK_EMOJI_STATUS" in uc)
    check("messagescontroller: putUser перекрывает серверную заливку",
          "UserConfig.getInstance(currentAccount).pengramApplyLocalEmojiStatus(user);" in mc
          and mc.find("pengramApplyLocalEmojiStatus(user)") < mc.find("public boolean putUser(TLRPC.User user, boolean fromCache, boolean force)") + 3000)
    check("messagescontroller: при локальной премии серверный запрос не шлётся",
          "if (PengramConfig.isPremiumStatusLocal() && !isPremiumUser(getUserConfig().getCurrentUser())) {" in mc
          and "UserConfig.pengramSaveLocalEmojiStatus(new_emoji_status);" in mc
          and mc.split("Pengram: локальная премка", 1)[1].split("TL_account.updateEmojiStatus req", 1)[0].count("sendRequest") == 0)
    check("messagescontroller: notify после локального выбора",
          "NotificationCenter.userEmojiStatusUpdated, me" in mc)

    print()
    if fails:
        print("FAIL:", len(fails), fails)
        return 1
    print("all run423 (to-bars toggle + local emoji status) asserts passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
