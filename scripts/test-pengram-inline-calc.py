#!/usr/bin/env python3
"""
Regression suite for:
1) Fixed Join/Leave Service Notification Profile Navigation (zero backgroundRect NPE,
   pill-wide + row-wide tap in ChatActionCell, ChatActivity & ChannelAdminLogActivity,
   all 5 join/leave action types + AdminLog events + basic group fallback)
2) Inline Calculator, Unit Converter & Live Multi-Source Currency/Crypto Converter
   (PengramInlineCalc, PengramCurrencyRates, ChatActivityEnterTopView, ChatActivityEnterView,
   PengramSettingsActivity & PengramSearchIndex)
"""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def check_join_leave_fix():
    opener = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramServiceProfileOpener.java")
    action_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatActionCell.java")
    chat_act = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    admin_log = read("TMessagesProj/src/main/java/org/telegram/ui/ChannelAdminLogActivity.java")

    for cls in (
        "TL_messageActionChatAddUser",
        "TL_messageActionChatJoinedByLink",
        "TL_messageActionChatJoinedByRequest",
        "TL_messageActionChatJoinedViaCommunity",
        "TL_messageActionChatDeleteUser",
        "TL_channelAdminLogEventActionParticipantJoin",
        "TL_channelAdminLogEventActionParticipantLeave",
        "TL_messages_getFullChat",
        "primaryParticipantUid",
    ):
        assert cls in opener, f"Missing {cls} in PengramServiceProfileOpener"

    # Must NEVER dereference nullable backgroundRect in join/leave touch block!
    touch_tail = action_cell.split("isJoinOrLeaveAction(messageObject)", 1)[1].split("checkBotButtonMotionEvent", 1)[0]
    assert "backgroundRect" not in touch_tail, "Nullable backgroundRect must not be used in join/leave touch handling!"
    assert "isInsideServicePill(x, y)" in touch_tail
    assert "resolveJoinLeaveTapUidInternal(x, y)" in touch_tail
    assert "getActionSpannedText(messageObject)" in touch_tail

    # Both ChatActivity and ChannelAdminLogActivity must handle ChatActionCell clicks in OnItemClickListener
    assert "actionCell.resolveJoinLeaveTapUid(x, y)" in chat_act
    assert "actionCell.resolveJoinLeaveTapUid(x, y)" in admin_log


def check_live_currency_engine():
    rates = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramCurrencyRates.java")
    for src in (
        "TL_wallet.getCurrencyRates()",
        "open.er-api.com/v6/latest/USD",
        "currency-api@latest/v1/currencies/usd.min.json",
        "cbr-xml-daily.ru/daily_json.js",
        "api.binance.com/api/v3/ticker/price",
        "tonUsdRate",
        "starsUsdSellRate1000",
        "Utilities.globalQueue.postRunnable",
    ):
        assert src in rates, f"Missing live currency source/mechanism: {src}"
    assert ".commit()" not in rates, "Must use async .apply() in PengramCurrencyRates"


def check_inline_calc_and_ui():
    calc = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramInlineCalc.java")
    top = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterTopView.java")
    enter = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
    cfg = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
    settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
    search_idx = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java")

    assert "UNICODE_CHARACTER_CLASS" not in calc and "(?U)" not in calc
    assert "\\b" not in calc, "Avoid ASCII-only \\b around Cyrillic tokens in Android Pattern"

    # Verify core regexes compile & match expected inputs in Python too
    explicit_pat = re.compile(
        r"(?:^|(?<=\s))(=\s*)?([0-9(][0-9()+\-*/^×÷:.,\u00A0 ]*[0-9)]|[0-9]+(?:[.,][0-9]+)?(?:[kкmм])?)\s*"
        r"([$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿]|°?[A-Za-zА-Яа-яЁё./]+)\s*"
        r"(?:в|во|in|to|into|->|→)\s*"
        r"([$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿]|°?[A-Za-zА-Яа-яЁё./]+)\s*$",
        re.IGNORECASE,
    )
    for sample, expected_expr, expected_from, expected_to in [
        ("150$ в руб", "150", "$", "руб"),
        ("= 150 usd in rub", "150", "usd", "rub"),
        ("(45 + 15)$ в руб", "(45 + 15)", "$", "руб"),
        ("2500 м в км", "2500", "м", "км"),
        ("1.5к $ в руб", "1.5к", "$", "руб"),
        ("0.025 btc в usd", "0.025", "btc", "usd"),
        ("36.6 c to f", "36.6", "c", "f"),
    ]:
        m = explicit_pat.search(sample)
        assert m is not None, f"Failed to match explicit conversion: {sample}"
        assert m.group(2) == expected_expr, f"{sample}: got expr {m.group(2)!r}"
        assert m.group(3) == expected_from, f"{sample}: got from {m.group(3)!r}"
        assert m.group(4) == expected_to, f"{sample}: got to {m.group(4)!r}"

    # Verify UI wiring
    assert "setCalcView(" in top and "setCalcVisible(" in top
    assert "updatePengramCalcSuggestion(" in enter
    assert "applyPengramCalcSuggestion(" in enter
    assert "PengramCurrencyRates.addListener(" in enter
    assert "PengramCurrencyRates.removeListener(" in enter

    # Verify settings & defaults
    assert 'KEY_INLINE_CALC = "inlineCalcEnabled"' in cfg
    assert 'KEY_INLINE_CURRENCY = "inlineCurrencyEnabled"' in cfg
    assert "getBool(KEY_INLINE_CALC, true)" in cfg
    assert "getBool(KEY_INLINE_CURRENCY, true)" in cfg
    assert "PengramCalcHeader" in settings
    assert "PengramInlineCalc" in search_idx
    assert "PengramInlineCurrency" in search_idx

    # Verify EN/RU strings & <=150 char shadow limit
    en_map = {el.attrib["name"]: (el.text or "") for el in ET.parse(ROOT / "TMessagesProj/src/main/res/values/strings.xml").getroot().findall("string") if "name" in el.attrib}
    ru_map = {el.attrib["name"]: (el.text or "") for el in ET.parse(ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml").getroot().findall("string") if "name" in el.attrib}
    for k in (
        "PengramCalcHeader", "PengramInlineCalc", "PengramInlineCalcInfo",
        "PengramInlineCurrency", "PengramInlineCurrencyInfo", "PengramCalcDefaultCurrency",
        "PengramCalcCurrencyAuto", "PengramCalcRefreshRates", "PengramCalcRatesLive",
        "PengramCalcRatesLoading", "PengramCalcInsertChip", "PengramCalcTapToInsert",
        "PengramCalcSectionInfo",
    ):
        assert k in en_map and en_map[k].strip(), f"Missing EN string {k}"
        assert k in ru_map and ru_map[k].strip(), f"Missing RU string {k}"
    assert len(en_map["PengramCalcSectionInfo"]) <= 150
    assert len(ru_map["PengramCalcSectionInfo"]) <= 150


if __name__ == "__main__":
    check_join_leave_fix()
    check_live_currency_engine()
    check_inline_calc_and_ui()
    print("Pengram join/leave fix + inline calculator & live currency converter: OK")
