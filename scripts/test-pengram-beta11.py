#!/usr/bin/env python3
"""Regression test suite for Pengram beta 11:
1. Version bump to beta 11 (code 11)
2. Profile ID & Registration Date deduplication, 2026 anchors, and interactions
3. Typing & Delete-Scatter UAX #29 + EmojiDrawable / AnimatedEmojiDrawable engine (zero '?')
4. Official-Telegram Quote Studio renderer, Live BottomSheet Studio, and settings
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

gradle_props = (ROOT / "gradle.properties").read_text(encoding="utf-8")
config_src = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java").read_text(encoding="utf-8")
reg_src = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/PengramRegDate.java").read_text(encoding="utf-8")
profile_src = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java").read_text(encoding="utf-8")
typing_src = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramTypingEffects.java").read_text(encoding="utf-8")
quote_src = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramQuoteMaker.java").read_text(encoding="utf-8")
settings_src = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java").read_text(encoding="utf-8")
search_idx_src = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java").read_text(encoding="utf-8")
en_xml = ET.parse(ROOT / "TMessagesProj/src/main/res/values/strings.xml")
ru_xml = ET.parse(ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml")

# 1. Version bump to beta 11
assert "PENGRAM_VERSION_STAGE=beta 11" in gradle_props, "PENGRAM_VERSION_STAGE must be beta 11"
assert "PENGRAM_VERSION_CODE=11" in gradle_props, "PENGRAM_VERSION_CODE must be 11"

# 2. Profile ID & RegDate polish
assert "regDatePlace = p.getInt(\"regDatePlace\", REG_PLACE_ROW);" in config_src
assert "if (!isRegDateVisible() || isRegDateRowVisible()) return false;" in config_src
assert "8400000000L" in reg_src and "9100000000L" in reg_src, "2026 ID anchor points missing"
assert "pengramNeedDetailDivider" in profile_src, "Profile detail divider helper missing"
assert "pengramRegDateText(estimate)" in profile_src, "RegDate copy helper missing"

# 3. Typing & Delete-Scatter Emoji/Grapheme Engine
for sym in (
    "nextGraphemeEnd",
    "prevGraphemeStart",
    "removedClusters",
    "removedEmoji",
    "removedAnimDoc",
    "isBrokenOrInvisibleCluster",
    "drawEmojiDrawableSafely",
    "releaseAllParticles",
    "WeakReference<View>",
    "ed.preload()",
    "AnimatedEmojiDrawable",
):
    assert sym in typing_src, f"Missing emoji/grapheme symbol in PengramTypingEffects: {sym}"

# Ensure no forbidden regex flags anywhere
for name, txt in (("PengramTypingEffects", typing_src), ("PengramQuoteMaker", quote_src), ("PengramSettingsActivity", settings_src)):
    assert "UNICODE_CHARACTER_CLASS" not in txt and "(?U" not in txt, f"Forbidden regex flag in {name}"

# 4. Official-Telegram Quote Studio
new_quote_keys = (
    "quoteAvatarLayout",
    "quoteBubbleTail",
    "quotePeerNameColor",
    "quoteShowReply",
    "quoteShowForward",
    "quoteShowBadges",
    "quoteShowReactions",
    "quoteAlignOutgoing",
    "quoteDateInTime",
)
for k in new_quote_keys:
    assert f'"{k}"' in quote_src, f"Key {k} missing in PengramQuoteMaker"
    assert f'"{k}"' in config_src, f"Key {k} missing in PengramConfig.EXPORTABLE_NAMES"

for feature in (
    "PEER_AVATAR_GRADIENTS",
    "buildTelegramBubblePath",
    "drawBubbleShadow",
    "ReactionChip",
    "createPreviewStageDrawable",
    "BottomSheet",
    "PengramSettingsActivity.SECTION_QUOTES",
    "PengramQuoteCopied",
    "createSaveToGalleryBulletin",
):
    assert feature in quote_src, f"Official-TG Quote Studio feature missing: {feature}"

for xml_name, tree in (("EN", en_xml), ("RU", ru_xml)):
    arrays = {a.get("name"): [i.text for i in a.findall("item")] for a in tree.iter("string-array")}
    assert len(arrays.get("pengram_quote_styles", [])) == 8, f"{xml_name} pengram_quote_styles must have 8 items"
    assert len(arrays.get("pengram_quote_backgrounds", [])) == 8, f"{xml_name} pengram_quote_backgrounds must have 8 items"
    assert len(arrays.get("pengram_quote_avatar_layouts", [])) == 2, f"{xml_name} pengram_quote_avatar_layouts must have 2 items"
    strings = {s.get("name") for s in tree.iter("string")}
    for s_key in (
        "PengramQuoteTapSticker",
        "PengramQuoteCopied",
        "PengramQuoteAvatarLayout",
        "PengramQuoteBubbleTail",
        "PengramQuotePeerNameColor",
        "PengramQuoteShowReply",
        "PengramQuoteShowForward",
        "PengramQuoteShowBadges",
        "PengramQuoteShowReactions",
        "PengramQuoteAlignOutgoing",
        "PengramQuoteDateInTime",
    ):
        assert s_key in strings, f"{xml_name} missing string {s_key}"

assert "BTN_QUOTE_AVATAR_LAYOUT" in settings_src
assert "PengramQuoteAvatarLayout" in search_idx_src
assert "PengramQuoteBubbleTail" in search_idx_src

print("Pengram beta 11 (version 11, Profile ID/RegDate, UAX#29 Emoji Scatter, Official-TG Quote Studio): ALL OK")
