#!/usr/bin/env python3
"""Regression checks for Pengram history, player controls and UI cleanup."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"

history = (JAVA / "messenger/PengramHistory.java").read_text()
storage = (JAVA / "messenger/MessagesStorage.java").read_text()
controller = (JAVA / "messenger/MessagesController.java").read_text()
favorites = (JAVA / "messenger/PengramMusicFavorites.java").read_text()
media = (JAVA / "messenger/MediaController.java").read_text()
config = (JAVA / "messenger/PengramConfig.java").read_text()
seek = (JAVA / "ui/Components/SeekBarView.java").read_text()
player = (JAVA / "ui/Components/PengramMusicPlayerSheet.java").read_text()
legacy_player = (JAVA / "ui/Components/AudioPlayerAlert.java").read_text()
lyrics = (JAVA / "ui/Components/PengramLyricsView.java").read_text()
settings = (JAVA / "ui/PengramSettingsActivity.java").read_text()
header = (JAVA / "ui/Components/FragmentContextView.java").read_text()
main_tabs = (JAVA / "ui/MainTabsActivity.java").read_text()
chat = (JAVA / "ui/ChatActivity.java").read_text()
alerts = (JAVA / "ui/Components/AlertsCreator.java").read_text()

# Transient delete/save guards must be scoped by account and peer as well as ID.
assert "HashMap<String, Long> userDeletedGuard" in history
assert "HashMap<String, Long> saveForMyself" in history
assert 'return account + ":" + dialogId + ":" + messageId;' in history
assert "Map.Entry<String, Long>" in history
assert "guardKey(account, dialogId, messageId)" in history
for source, token in (
    (storage, "isUserDeleted(currentAccount, did, message.id)"),
    (chat, "guardUserDeleted(getCurrentAccount(), getDialogId(), ids)"),
    (alerts, "guardSaveForMyself(currentAccount, targetDialogId, ids)"),
):
    assert token in source, token

# Never publish a deletion marker without a committed, serialized snapshot.
assert "if (action == ACTION_DELETED && data == null) return;" in history
assert "hasDeletedSnapshot(account, dialogId, id)" in history.split("public static void markDeleted", 1)[1].split("public static", 1)[0]
assert "Только после COMMIT" in history
assert "markDeleted(currentAccount, marksByDialog.keyAt(a)" not in storage
assert "h.action=0 AND h.data IS NOT NULL" in history

# Edits with no recoverable before-image must not create a fabricated version.
edit = controller.split("private void pengramSaveEdited(", 1)[1].split("private ", 1)[0]
assert "pendingEditSnapshot(account, dialogId, messageId)" in edit
assert "if (previous == null)" in edit
assert edit.index("if (previous == null)") < edit.index("PengramHistory.save(account, dialogId, messageId")
assert "clearPendingEdit(account, dialogId, messageId);\n                    return;" in edit

# Favorites use indexed title search for fast dedupe, recent empty-query fallback,
# and full music-history pagination for unlike cleanup.
copy_lookup = favorites.split("private static void findSavedCopies", 1)[1].split("private static boolean isSameTrack", 1)[0]
assert "request.q = query;" in copy_lookup
assert '"", true, 0, new ArrayList<>()' in copy_lookup
assert "request.filter = new TLRPC.TL_inputMessagesFilterMusic();" in copy_lookup
assert "request.limit = 100;" in copy_lookup
assert "!message.out" in copy_lookup
assert "findSavedCopiesPage(account, track, targetDialogId, peer, excludeOriginalMessage" in copy_lookup
assert "request.offset_id = offsetId;" in copy_lookup
assert "isSameTrack(account, message, primaryKey(track), metaFingerprint(track))" in copy_lookup
assert "deleteMessages(ids, null, null, targetDialogId, 0, true, 0)" in favorites

# Playback speed remains precise and is reapplied after every player prepare,
# including short files/streams; both player UIs listen to global speed changes.
assert "audioPlayer.setPlaybackSpeed(speed);" in media
assert "videoPlayer.setPlaybackSpeed(speed);" in media
assert "Math.round(speed * 10f) / 10f" not in media
assert "audioPlayer.setPlaybackSpeed(messageObject.isVoice() ? currentPlaybackSpeed : currentMusicPlaybackSpeed);" in media
assert "messagePlayingSpeedChanged" in player
assert "updateSpeedButton();" in player
assert "setPengramProgressStyle(org.telegram.messenger.PengramConfig.getPlayerSeekStyle())" in legacy_player

# Four line designs are user-selectable in Settings and preserved in settings backup.
for token in ("PLAYER_SEEK_STYLE_CLASSIC", "PLAYER_SEEK_STYLE_THIN", "PLAYER_SEEK_STYLE_GLOW", "PLAYER_SEEK_STYLE_DOTS"):
    assert token in config
assert '"playerSeekStyle"' in config.split("EXPORTABLE_NAMES =", 1)[1].split("EXPORTABLE_AI_KEYS", 1)[0]
assert "BTN_PLAYER_SEEK_STYLE" in settings
assert "PengramConfig.setPlayerSeekStyle(value)" in settings
assert "setPengramProgressStyle(PengramConfig.getPlayerSeekStyle())" in player
assert "pengramProgressStyle == 3 && (timestamps == null || timestamps.isEmpty())" in seek
assert "pengramProgressStyle == 2" in seek

# With no lyrics effects, the header island gets a low-alpha accent glass pill,
# not a flat opaque black box. Keep Dump Canvas out of all user-facing menus.
assert "drawTickerIsland(canvas)" in lyrics
assert "new LinearGradient" in lyrics
assert "ColorUtils.setAlphaComponent(accentColor, 54)" in lyrics
assert "setSingleLine(true)" in player and "TextUtils.TruncateAt.END" in player
assert "headerParams.rightMargin = dp(headerRightInsetDp);" in player
assert "shareCardButton.getVisibility() == View.VISIBLE ? 192 : 146" in player
assert "Dump Canvas" not in main_tabs
assert "Dump Canvas" not in chat
assert "lazilyAddSubItem(888" not in chat

# English and Russian labels are present exactly once, and the row is searchable.
for values in ("values", "values-ru"):
    root = ET.parse(ROOT / f"TMessagesProj/src/main/res/{values}/strings.xml").getroot()
    names = [node.attrib.get("name") for node in root.findall("string")]
    for name in ("PengramPlayerSeekStyle", "PengramPlayerSeekStyleClassic",
                 "PengramPlayerSeekStyleThin", "PengramPlayerSeekStyleGlow",
                 "PengramPlayerSeekStyleDots"):
        assert names.count(name) == 1, (values, name)
index = (JAVA / "ui/PengramSearchIndex.java").read_text()
assert "R.string.PengramPlayerSeekStyle" in index
print("Pengram task regressions: history guards/snapshots, favorites, player speed/seek styles, lyrics island and menu cleanup: OK")
