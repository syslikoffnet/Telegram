#!/usr/bin/env python3
"""Regression checks for severe Beta 10 reports: audio-only playback, empty dialogs,
non-expiring archived liked-music vault, queue scrolling and missing cover artwork.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
link = (JAVA / "messenger/PengramLinkAssistant.java").read_text()
controller = (JAVA / "messenger/MessagesController.java").read_text()
dialogs = (JAVA / "ui/DialogsActivity.java").read_text()
favorites = (JAVA / "messenger/PengramMusicFavorites.java").read_text()
player = (JAVA / "ui/Components/PengramMusicPlayerSheet.java").read_text()

# Direct audio streams must not be mislabeled as MP3; sniff MP3 signatures and
# normalize all other source containers/codecs to M4A via the media extractor.
assert 'if (isMp3Header(rawAud))' in link
assert 'new File(targetDir, "audio_" + stamp + ".m4a")' in link
assert 'PengramVideoMux.extractAudioToM4a(rawAud, outM4a, handle.cancel)' in link

# A failed initial getDialogs request must release its latch, back off/retry, and recover on reconnect.
assert 'loadingDialogs.put(folderId, false);' in controller
assert 'dialogLoadRetryCount.put(folderId, retries + 1);' in controller
assert 'dialogLoadRequestGeneration.get(folderId, 0) == requestGeneration' in controller
assert 'delays = {1200L, 5000L, 15000L}' in controller
assert 'state == ConnectionsManager.ConnectionStateConnected' in dialogs
assert 'loadDialogs(0, 0, 100, false);' in dialogs

# Auto-generated favorites storage is made permanent per-peer and archived, without touching
# the user's global auto-delete preference. Existing auto vaults are repaired on next use too.
assert 'prepareAutoVault(account, existingVault, false);' in favorites
assert 'MessagesController.getInstance(account).setDialogHistoryTTL(dialogId, 0);' in favorites
assert 'TL_folders_editPeerFolders' in favorites
assert 'folderPeer.folder_id = 1;' in favorites
assert 'archiveAutoVault(account, dialogId), 3500L' in favorites

# The queue list owns vertical gestures while open, and missing artwork gets a deterministic
# designed placeholder instead of a blank gray square.
assert 'return !queueShown && super.canDismissWithSwipe();' in player
assert 'createTrackPlaceholder(messageObject)' in player
assert 'HSVToColor' in player and 'canvas.drawCircle(cx, cy, radius' in player

print("Pengram Beta 11 stability regressions: ALL OK")
