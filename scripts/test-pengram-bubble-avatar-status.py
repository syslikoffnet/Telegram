#!/usr/bin/env python3
"""
Regression suite for:
1) Service join/leave notification profile navigation (ON by default, disableable, 3-tier user resolution)
2) Unified Avatar Shape across the entire app (Circle, iOS Squircle, Soft Square, Hexagon)
3) Read Checkmarks (5 styles) & Online Badge Indicators (5 styles)
4) Advanced Bubble Studio (Glass bubbles + wallpaper tint, Liquid Glass rim light, Shadow modes, Independent grouped inner radius)
5) Strict 120Hz zero-allocation & XML resource integrity checks
"""

from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "TMessagesProj/src/main/java"
RES = ROOT / "TMessagesProj/src/main/res"


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def check_xml_and_strings():
    en_tree = ET.parse(RES / "values/strings.xml")
    ru_tree = ET.parse(RES / "values-ru/strings.xml")
    en_keys = {el.attrib["name"] for el in en_tree.getroot().findall("string") if "name" in el.attrib}
    ru_keys = {el.attrib["name"] for el in ru_tree.getroot().findall("string") if "name" in el.attrib}

    required = [
        "PengramServiceJoinLeaveProfile",
        "PengramServiceJoinLeaveProfileInfo",
        "PengramAvatarShape",
        "PengramAvatarShapeInfo",
        "PengramAvatarShapeCircle",
        "PengramAvatarShapeSquircle",
        "PengramAvatarShapeSquircleShort",
        "PengramAvatarShapeSoftSquare",
        "PengramAvatarShapeSoftSquareShort",
        "PengramAvatarShapeHexagon",
        "PengramAvatarShapeHexagonShort",
        "PengramStatusStylesHeader",
        "PengramCheckmarkStyle",
        "PengramCheckmarkStandard",
        "PengramCheckmarkIosRounded",
        "PengramCheckmarkDoubleDots",
        "PengramCheckmarkOutlineFill",
        "PengramCheckmarkMinimal",
        "PengramOnlineBadgeStyle",
        "PengramOnlineStandard",
        "PengramOnlineRing",
        "PengramOnlineCapsule",
        "PengramOnlineDiamond",
        "PengramOnlinePulse",
        "PengramStatusStylesInfo",
        "PengramBubbleStudioHeader",
        "PengramBubbleGlass",
        "PengramBubbleGlassInfo",
        "PengramBubbleGlassOpacity",
        "PengramBubbleWallpaperTint",
        "PengramBubbleRim",
        "PengramBubbleRimInfo",
        "PengramBubbleRimStrength",
        "PengramBubbleShadowMode",
        "PengramBubbleShadowNone",
        "PengramBubbleShadowSoft",
        "PengramBubbleShadowAmbient",
        "PengramBubbleShadowGlow",
        "PengramBubbleShadowStrength",
        "PengramBubbleOuterRadius",
        "PengramBubbleInnerRadius",
        "PengramBubbleInnerRadiusAuto",
        "PengramBubbleStudioInfo",
    ]
    for key in required:
        assert key in en_keys, f"Missing EN string: {key}"
        assert key in ru_keys, f"Missing RU string: {key}"


def check_service_join_leave_profile():
    cfg = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
    opener = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramServiceProfileOpener.java")
    action_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatActionCell.java")
    chat_act = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    admin_log = read("TMessagesProj/src/main/java/org/telegram/ui/ChannelAdminLogActivity.java")
    settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
    search_idx = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java")

    assert 'KEY_SERVICE_JOIN_LEAVE_PROFILE = "serviceJoinLeaveProfile"' in cfg
    assert "getBool(KEY_SERVICE_JOIN_LEAVE_PROFILE, true)" in cfg, "Join/leave profile open must default to TRUE"
    for action_cls in (
        "TL_messageActionChatAddUser",
        "TL_messageActionChatJoinedByLink",
        "TL_messageActionChatJoinedByRequest",
        "TL_messageActionChatDeleteUser",
    ):
        assert action_cls in opener, f"Missing action support: {action_cls}"
    assert "storage.getStorageQueue().postRunnable" in opener
    assert "storage.getUser(uid)" in opener
    assert "Sync(" not in opener, "Never block UI thread with *Sync storage queries"
    assert "TL_inputUserFromMessage" in opener and "TL_users_getUsers" in opener
    assert "PengramServiceProfileOpener.ensureUserAndOpen" in action_cell
    assert "resolveJoinLeaveTapUid" in action_cell
    assert "PengramServiceProfileOpener.ensureUserAndOpen" in chat_act
    assert 'args.putBoolean("my_profile", true)' in admin_log
    assert "PengramServiceJoinLeaveProfile" in settings
    assert "PengramServiceJoinLeaveProfile" in search_idx


def check_unified_avatar_shape():
    cfg = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
    shape = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramAvatarShape.java")
    receiver = read("TMessagesProj/src/main/java/org/telegram/messenger/ImageReceiver.java")
    avatar_dr = read("TMessagesProj/src/main/java/org/telegram/ui/Components/AvatarDrawable.java")
    stories = read("TMessagesProj/src/main/java/org/telegram/ui/Stories/StoriesUtilities.java")
    profile = read("TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java")

    assert "AVATAR_SHAPE_COUNT = 4" in cfg and "DIALOG_AVATAR_COUNT = AVATAR_SHAPE_COUNT" in cfg
    for c in ("AVATAR_SHAPE_CIRCLE", "AVATAR_SHAPE_SQUIRCLE", "AVATAR_SHAPE_SOFT_SQUARE", "AVATAR_SHAPE_HEXAGON"):
        assert c in cfg and c in shape
    assert "buildSquircleCWFromTop" in shape and "buildHexagonCWFromTop" in shape
    assert "getPengramAvatarPath(" in receiver
    assert "PengramAvatarShape.drawAvatarBackground(" in avatar_dr
    assert "PengramAvatarShape.buildStoryRingPath(" in stories
    assert "PengramAvatarShape.buildPath(clipPath, AndroidUtilities.rectTmp, morph)" in profile
    assert "saveLayer" not in shape


def check_status_indicators():
    ind = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramStatusIndicators.java")
    msg_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java")
    dlg_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java")
    share_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/ShareDialogCell.java")
    hint_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/HintDialogCell.java")
    user_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/UserCell.java")
    search_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/ProfileSearchCell.java")

    # Zero allocations inside draw methods (static final Paint/Path/RectF only)
    draw_body = ind.split("public static void drawMessageChecks", 1)[1]
    assert "new Paint(" not in draw_body
    assert "new Path(" not in draw_body
    assert "new RectF(" not in draw_body
    assert "saveLayer" not in ind

    assert "PengramStatusIndicators.drawMessageChecks(" in msg_cell
    assert "PengramStatusIndicators.drawMessageChecks(" in dlg_cell
    for cell_src in (dlg_cell, share_cell, hint_cell, user_cell, search_cell):
        assert "PengramStatusIndicators.drawOnlineBadge(" in cell_src


def check_bubble_studio():
    cfg = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
    md = read("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/MessageDrawable.java")
    msg_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java")
    settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")

    sig = cfg.split("public static int chatLookSignature(int account)", 1)[1].split("\n    }", 1)[0]
    for expr in ("getAvatarShape()", "getCheckmarkStyle()", "getOnlineBadgeStyle()", "bubbleStudioSignature()"):
        assert expr in sig, f"Missing {expr} in chatLookSignature"

    assert "drawPengramGlassFill(" in md
    assert "drawPengramRimLight(" in md
    assert "Theme.key_chat_serviceBackground" in md, "Glass bubbles must sample chat wallpaper tint"
    assert "resolveBubbleInnerRadiusDp(SharedConfig.bubbleRadius)" in md
    assert "lastShapeKey != shapeKey" in md, "PathDrawParams must invalidate when inner/outer radii change"
    assert "saveLayer" not in md
    assert "PengramConfig.getBubbleInnerRadius()" in msg_cell
    assert "PengramBubbleStudioHeader" in settings
    # Ensure applyBubbleRadius uses async .apply(), not blocking .commit()
    apply_br = settings.split("private void applyBubbleRadius(int radius)", 1)[1].split("}", 2)[0]
    assert ".commit()" not in apply_br and ".apply()" in apply_br


if __name__ == "__main__":
    check_xml_and_strings()
    check_service_join_leave_profile()
    check_unified_avatar_shape()
    check_status_indicators()
    check_bubble_studio()
    print("Pengram join/leave profile, unified avatar shapes, status indicators & Bubble Studio: OK")
