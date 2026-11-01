#!/usr/bin/env python3
"""Static regression checks for resolving known peers by ID and the liquid-glass preset."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
peer_id = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/PengramPeerId.java").read_text()
settings = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java").read_text()
config = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java").read_text()

# Resolving must restore familiar IDs from persisted peers before a network lookup,
# so deleting a public username cannot strand a previously contacted user.
resolve = peer_id.split("public static void resolve(", 1)[1].split("private static void requestUser", 1)[0]
assert "getStorageQueue().postRunnable" in resolve
assert "getUser(dialogId)" in resolve and "getChat(-dialogId)" in resolve
assert "hasUsablePeerReference(cachedPeer, account)" in resolve
assert "user.access_hash != 0" in peer_id and "chat.access_hash != 0" in peer_id
assert resolve.index("peerFromStorage instanceof TLRPC.User") < resolve.index("requestUser(account, dialogId, finish)")
assert "pending.put(key, callbacks)" in resolve and "callbacks.add(whenDone)" in resolve
assert '"user_id".equals(name)' in peer_id and 'final int idx = q.lastIndexOf("id=")' not in peer_id
assert 'PengramPeerId.parseDialogId(q)' in settings
assert 'PengramPeerId.resolve(currentAccount, dialogId' in settings

# One action enables every configurable Pengram glass surface and the rich optics.
preset = config.split("public static void applyJuicyGlassPreset()", 1)[1].split("// ------------------------------- пункты экрана", 1)[0]
for surface in (
    "setTabBarLiquidGlass(true)", "setInputGlass(true)",
    "setChatListHeaderGlass(true)", "setChatHeaderGlass(true)", "setPlayerHeaderGlass(true)",
    "setChatPinnedGlass(true)", "setGlassThicknessDp(30)", "setGlassIntensity(150)",
    "setGlassGlow(150)", "KEY_GLASS_SHEEN", "KEY_GLASS_DRAG", "KEY_GLASS_RIM",
):
    assert surface in preset, surface
assert "BTN_GLASS_JUICY_PRESET" in settings and "applyJuicyGlassPreset()" in settings
assert "LiteMode.FLAG_LIQUID_GLASS" in settings

for values in ("values/strings.xml", "values-ru/strings.xml"):
    root = ET.parse(ROOT / "TMessagesProj/src/main/res" / values).getroot()
    names = {node.attrib.get("name") for node in root.findall("string")}
    assert "PengramGlassJuicyPreset" in names

print("peer-ID restoration and liquid-glass preset regression checks passed")
