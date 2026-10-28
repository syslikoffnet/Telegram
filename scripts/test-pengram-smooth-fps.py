#!/usr/bin/env python3
"""Regression guard for liquid/smooth cursor, Telegram real-name swap morph,
4-style GPU chat entrance choreography, and 120Hz zero-quality-loss FPS pipelines."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def require(src: str, needle: str, label: str) -> None:
    if needle not in src:
        raise AssertionError(f"[{label}] missing: {needle!r}")


def forbid(src: str, needle: str, label: str) -> None:
    if needle in src:
        raise AssertionError(f"[{label}] forbidden pattern present: {needle!r}")


def main() -> None:
    # 1. Smooth / liquid cursor decoupled from INPUT_ANIM_NONE
    typing = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramTypingEffects.java")
    for token in (
        "private static boolean wantsCustomCursor(EditText edit)",
        "public static void clearGlyphsOnly(EditText edit)",
        "liquidPath.quadTo(midX, midCY - waistHalfH",
        "Math.exp(-headRate * dt)",
        "edit.postOnAnimation(this)",
    ):
        require(typing, token, "PengramTypingEffects")

    preview = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramTypingPreviewView.java")
    require(preview, "PengramTypingEffects.clearGlyphsOnly(input);", "PengramTypingPreviewView")

    settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
    forbid(settings, "if (inputStyle != PengramConfig.INPUT_ANIM_NONE) {\n                items.add(tgCheck(BTN_CURSOR_SMOOTH", "PengramSettingsActivity")
    require(settings, "BTN_CHAT_ENTER_STYLE", "PengramSettingsActivity")

    # 2. Real Telegram name finger-follow + directional morph
    stv = read("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/SimpleTextView.java")
    for token in (
        "public void pengramSetSwipeOffset(float rawDx)",
        "public void pengramReleaseSwipe()",
        "public void pengramNudge(int dir)",
        "public void pengramPrepareSwap(int dir)",
        "public void pengramCommitSwap()",
        "public void pengramCancelSwap()",
        "private void onDrawContent(Canvas canvas)",
    ):
        require(stv, token, "SimpleTextView")

    orig = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramOriginalName.java")
    for token in ("noteGestureDirection(", "peekDirection(", "consumeDirection(", "pengramSetSwipeOffset("):
        require(orig, token, "PengramOriginalName")

    avatar = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java")
    require(avatar, "titleTextView.pengramSetSwipeOffset(dx);", "ChatAvatarContainer")
    require(avatar, "titleTextView.pengramReleaseSwipe();", "ChatAvatarContainer")

    profile = read("TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java")
    require(profile, ".pengramPrepareSwap(", "ProfileActivity")
    require(profile, ".pengramCommitSwap()", "ProfileActivity")
    require(profile, ".pengramNudge(", "ProfileActivity")

    # 3. Chat enter presets without nested fragmentView.setAlpha layer during animation
    cfg = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
    for token in (
        'KEY_CHAT_ENTER_STYLE = "chatEnterStyle"',
        "CHAT_ENTER_DEPTH_CASCADE = 0",
        "CHAT_ENTER_SPRING_ZOOM = 1",
        "CHAT_ENTER_VERTICAL_FLOW = 2",
        "CHAT_ENTER_BUBBLE_WAVE = 3",
        '"chatEnterStyle"',
    ):
        require(cfg, token, "PengramConfig")

    chat = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    enter_start = chat.index("private void applyPengramEnterAnim(")
    enter_end = chat.index("private void resetPengramEnterAnim()")
    enter_body = chat[enter_start:enter_end]
    forbid(enter_body, "fragmentView.setAlpha(", "ChatActivity.applyPengramEnterAnim")
    for token in (
        "CHAT_ENTER_SPRING_ZOOM",
        "CHAT_ENTER_VERTICAL_FLOW",
        "CHAT_ENTER_BUBBLE_WAVE",
        "CHAT_ENTER_DEPTH_CASCADE",
        "chatInputViewsContainer.setTranslationY(",
        "pengramCascadedCells",
    ):
        require(enter_body, token, "ChatActivity.applyPengramEnterAnim")
    require(chat, ".pengramPrepareSwap(", "ChatActivity.headerNameSwap")
    require(chat, ".pengramCommitSwap()", "ChatActivity.headerNameSwap")

    # 4. 120Hz zero-quality-loss FPS optimizations
    sheen = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramGlassSheen.java")
    require(sheen, "consumeSelfTick()", "PengramGlassSheen")
    require(sheen, "view.postInvalidateOnAnimation(", "PengramGlassSheen")

    glass = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramSurfaceGlass.java")
    require(glass, "computeSceneSignature()", "PengramSurfaceGlass")
    require(glass, "sheen.effect.consumeSelfTick()", "PengramSurfaceGlass")

    cyl = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java")
    require(cyl, "if (localT <= 0f && localB >= h)", "PengramCylinderScroll")
    require(cyl, "detachIfActive()", "PengramCylinderScroll")

    warp = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderWarp.java")
    require(warp, "obtainBitmap(w, h)", "PengramCylinderWarp")
    require(warp, "candidate.reconfigure(w, h, Bitmap.Config.ARGB_8888)", "PengramCylinderWarp")

    snow = read("TMessagesProj/src/main/java/org/telegram/ui/Components/SnowflakesEffect.java")
    require(snow, "createPetalBitmap(", "SnowflakesEffect")
    require(snow, "parent.postInvalidateOnAnimation();", "SnowflakesEffect")

    penguin = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java")
    for token in ("wakeRender()", "onVisibilityChanged(", "isActivelyMoving()", "System.nanoTime()"):
        require(penguin, token, "PengramPenguinView")

    player = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMusicPlayerSheet.java")
    for token in ("Utilities.globalQueue.postRunnable(", "computePlaylistSignature(", "drawArtworkLayer("):
        require(player, token, "PengramMusicPlayerSheet")

    del_view = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramDeleteEffectView.java")
    require(del_view, "bitmap.getPixels(buildPixels, 0, bmpW, 0, 0, bmpW, bmpH);", "PengramDeleteEffectView")
    require(del_view, "samplePixelFast(", "PengramDeleteEffectView")

    print("OK: test-pengram-smooth-fps passed")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
