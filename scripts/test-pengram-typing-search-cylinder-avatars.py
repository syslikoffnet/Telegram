#!/usr/bin/env python3
"""Comprehensive regression suite for Pengram beta 10 final 4-part upgrade:
1. Typing animations (preDraw clipOutRect, 0->1 spring modes, Gboard diff, OFF default + juicy preset)
2. Smart Search (layout flip, translit, stemming, typo tolerance, t.me links, phone, Saved Messages, world time, recent web queries)
3. 3D Cylinder Scroll (whole-list RenderEffect bending outgoing bubble frames + unclipped incoming text, API<33 cell background fallback, OFF default + juicy preset)
4. Group Deleted Message Avatars (PengramSenderHydrator 3-layer cache + ChatActivity !deleted||pengramDeleted + ChatMessageCell async hydration & archive avatar draw)
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"

cfg = (JAVA / "messenger/PengramConfig.java").read_text(encoding="utf-8")
hydrator = (JAVA / "messenger/PengramSenderHydrator.java").read_text(encoding="utf-8")
smart = (JAVA / "messenger/PengramSmartSearch.java").read_text(encoding="utf-8")
history = (JAVA / "messenger/PengramHistory.java").read_text(encoding="utf-8")
web = (JAVA / "messenger/PengramInlineWebSearch.java").read_text(encoding="utf-8")
typing = (JAVA / "ui/Components/PengramTypingEffects.java").read_text(encoding="utf-8")
typing_prev = (JAVA / "ui/Components/PengramTypingPreviewView.java").read_text(encoding="utf-8")
enter = (JAVA / "ui/Components/ChatActivityEnterView.java").read_text(encoding="utf-8")
cyl = (JAVA / "ui/Components/PengramCylinderScroll.java").read_text(encoding="utf-8")
web_sheet = (JAVA / "ui/Components/PengramWebSearchSheet.java").read_text(encoding="utf-8")
cell = (JAVA / "ui/Cells/ChatMessageCell.java").read_text(encoding="utf-8")
chat = (JAVA / "ui/ChatActivity.java").read_text(encoding="utf-8")
settings = (JAVA / "ui/PengramSettingsActivity.java").read_text(encoding="utf-8")
meta = (JAVA / "ui/PengramSearchMetadata.java").read_text(encoding="utf-8")
dialogs_search = (JAVA / "ui/Adapters/DialogsSearchAdapter.java").read_text(encoding="utf-8")
msg_search = (JAVA / "ui/Adapters/MessagesSearchAdapter.java").read_text(encoding="utf-8")
hist_chat = (JAVA / "ui/PengramHistoryChatActivity.java").read_text(encoding="utf-8")
hist_list = (JAVA / "ui/PengramHistoryActivity.java").read_text(encoding="utf-8")

# ---------------------------------------------------------------------------
# 1. Typing animations: genuine visibility, Gboard compatibility, OFF default + juicy preset
# ---------------------------------------------------------------------------
assert "getIntCached(KEY_INPUT_ANIMATION, INPUT_ANIM_NONE)" in cfg
assert "isTypingCursorSmooth() { return getBool(KEY_TYPING_CURSOR, false); }" in cfg
assert "migrBeta10TypingCylinderOff" in cfg
assert "public static void setTypingEffectsMaster(boolean enabled)" in cfg
assert "public static void applyJuicyTypingPreset()" in cfg
assert "ensureJuicyTypingSubDefaults()" in cfg
assert "DELETE_PARTICLE_SPARKS" in cfg

assert "public static int preDraw(EditText edit, Canvas canvas)" in typing
assert "canvas.clipOutRect(left, top, right, bottom)" in typing
assert "final int netAdded = count - before;" in typing
assert "if (added > PASTE_THRESHOLD)" in typing
assert "void spawnFromSnapshot(int start, int before, int count, CharSequence afterText)" in typing
assert "Math.exp(-5.0f * rawT) * Math.cos(9.2f * rawT)" in typing
assert "PengramTypingEffects.preDraw(this, canvas)" in typing_prev
assert "PengramTypingEffects.preDraw(this, canvas)" in enter
assert "(count - before) <= 2" in enter
assert "BTN_TYPING_MASTER" in settings and "BTN_TYPING_JUICY_PRESET" in settings

# ---------------------------------------------------------------------------
# 2. Smart Search across Settings, Global Dialogs, Archive, and Web
# ---------------------------------------------------------------------------
assert "public static String flipKeyboardLayout(String raw)" in smart
assert "public static String transliterate(String raw)" in smart
assert "public static boolean withinEditDistance(String a, String b, int maxEdits)" in smart
assert "public static String extractTelegramUsername(String raw)" in smart
assert "public static boolean matchesSavedMessagesAlias(String rawQuery)" in smart
assert "public static String normalizePhoneDigits(String raw)" in smart

assert "PengramSmartSearch.flipKeyboardLayout" in meta
assert "PengramSmartSearch.transliterate" in meta
assert "public static String suggestedFlippedQuery(String rawQuery)" in meta
assert "BTN_SEARCH_QUICK_SECTION_BASE" in settings
assert "PengramSearchFlippedHeader" in settings

assert "pengramSmartLocalSearch(query, q, resultArray, resultArrayNames, encUsers);" in dialogs_search
assert "PengramSmartSearch.extractTelegramUsername" in dialogs_search
assert "PengramSmartSearch.matchesSavedMessagesAlias" in dialogs_search
assert "PengramSmartSearch.normalizePhoneDigits" in dialogs_search

assert "PengramSmartSearch.matchesArchiveText" in history
assert "tryResolveWorldTime(" in web
assert "\\b" not in web, "Forbidden ASCII word boundary in PengramInlineWebSearch"
assert "renderSmartEmptyState()" in web_sheet
assert "PengramConfig.getRecentWebQueries()" in web_sheet
assert "readClipboardSearchCandidate()" in web_sheet

# ---------------------------------------------------------------------------
# 3. 3D Cylinder Scroll: whole-bubble & unclipped incoming text, OFF default + juicy preset
# ---------------------------------------------------------------------------
assert "isCylinderScroll() { return getBool(KEY_CYLINDER_SCROLL, false); }" in cfg
assert "if (!p.contains(KEY_CYLINDER_STRENGTH))" in cfg
assert "if (!p.contains(KEY_CYLINDER_TO_BARS))" in cfg
assert "public static void applyJuicyCylinderPreset()" in cfg
assert "BTN_CYLINDER_JUICY_PRESET" in settings

assert "private Bend listBend;" in cyl
assert "target.setRenderEffect(listBend.effect);" in cyl
assert "target.setRenderEffect(null);" in cyl
assert "Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU" in cell
assert "org.telegram.messenger.PengramConfig.isCylinderScroll()" in cell

# ---------------------------------------------------------------------------
# 4. Deleted message sender avatars in groups & archive
# ---------------------------------------------------------------------------
assert "pengram_sender_snapshots" in hydrator
assert "TL_channels_getParticipant" in hydrator
assert "public static void rememberMessageSender(int account, TLRPC.Message message)" in hydrator
assert "public static void ensureLoaded(final int account, final long dialogId, final long peerId, final Listener listener)" in hydrator

assert "(!mcell.getMessageObject().deleted || mcell.getMessageObject().pengramDeleted)" in chat
assert "cell.getMessageObject().deleted && !cell.getMessageObject().pengramDeleted" in chat
assert "(!messageObject.deleted || messageObject.pengramDeleted || cell.linkedChatId != linkedChatId)" in chat
assert "PengramSenderHydrator.rememberMessageSender(currentAccount, obj.messageOwner)" in chat
assert "PengramSenderHydrator.ensureLoaded(" in cell
assert "pengramDrawStandaloneAvatar(canvas);" in cell
assert "(currentMessageObject.deleted && !currentMessageObject.pengramDeleted)" in cell
assert "preloadDialogSenders(" in history
assert "preloadPeersBlocking(" in hist_chat
assert "PengramSenderHydrator.ensureLoaded(" in hist_list
assert "PengramSenderHydrator.preloadPeersBlocking(" in msg_search

# ---------------------------------------------------------------------------
# 5. Localization & XML well-formedness + no stray @Override on fields
# ---------------------------------------------------------------------------
needed_strings = [
    "PengramTypingMaster", "PengramTypingMasterInfo", "PengramTypingJuicyPreset",
    "PengramCylinderJuicyPreset", "PengramPresetAppliedToast",
    "PengramWebSearchHistory", "PengramWebSearchHistoryInfo",
    "PengramWebSearchFromClipboard", "PengramWebSearchQuickExamples",
    "PengramWebSearchEx1", "PengramWebSearchEx2", "PengramWebSearchEx3", "PengramWebSearchEx4",
    "PengramSearchQuickSections", "PengramSearchFlippedHeader",
]
for locale in ("values", "values-ru"):
    tree = ET.parse(ROOT / f"TMessagesProj/src/main/res/{locale}/strings.xml")
    names = {el.get("name") for el in tree.iter("string")}
    for k in needed_strings:
        assert k in names, f"Missing string {k} in {locale}"

stray_override = re.compile(r"@Override\s+(?:private|protected|public| static|final|volatile|transient)+[^(\n;]*;")
for jf in JAVA.rglob("*.java"):
    txt = jf.read_text(encoding="utf-8")
    assert not stray_override.search(txt), f"Stray @Override on field in {jf}"

print("Pengram beta 10 typing animations, smart search, 3D cylinder scroll & group deleted avatars: ALL OK")
