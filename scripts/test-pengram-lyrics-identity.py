#!/usr/bin/env python3
"""Guard against lyric results crossing track/version identities."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lyrics = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramLyrics.java').read_text()
service = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/MusicPlayerService.java').read_text()
receiver = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/MusicPlayerReceiver.java').read_text()
chat_sheet = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/PengramChatMusicSheet.java').read_text()
player_sheet = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMusicPlayerSheet.java').read_text()

# Candidate identity must preserve meaningful recording/version markers end to end.
assert 'return editionTokens(a).equals(editionTokens(b));' in lyrics
assert 'remix' in lyrics and 'acoustic' in lyrics and 'instrumental' in lyrics
assert 'return cleanTitle(title);' in lyrics  # query normalization never strips remix suffixes
assert 'title.replaceAll("\\\\([^)]*\\\\)"' not in lyrics
assert 'final float allowed = Math.max(5f, wantSeconds * 0.02f);' in lyrics
assert 'best == null) {\n            log("lrclib: подходящих нет' in lyrics
assert 'но название и исполнитель совпали — берём' not in lyrics
assert 'if (!durationGate(duration, gotLength)) {\n                log("musixmatch: отсев' in lyrics
assert 'fetchLyricsOvh(artist, title)' not in lyrics  # metadata-free endpoint cannot prove identity

# Cached/duplicate text cannot bypass source matching or collide on a 32-bit hash.
assert 'cacheMatches(key, cached, artist, title, cachedDuration)' in lyrics
assert 'unbound legacy cache entries are never displayed' in lyrics
assert 'MessageDigest.getInstance("SHA-256")' in lyrics
assert 'Identical lyrics can be correct for covers, remixes, and alternate edits.' in lyrics
assert 'sourcePool.submit(task)' in lyrics

# Previous player-audit defects: valid compact-action indices, notification refresh,
# request timeouts/retry and complete pagination of the embedded chat tab.
assert 'mediaStyle.setShowActionsInCompactView(0, 1, 2);' in service
assert 'MusicPlayerService.onPlaybackModeChanged();' in receiver
assert 'NotificationCenter.musicPlaybackOrderChanged' in player_sheet
assert 'runOnUIThread(loadTimeout, 15000)' in chat_sheet
assert 'args[7] instanceof Integer' in player_sheet
assert 'PengramChatMusicLoadMore' in player_sheet

print('Lyrics identity guards and player regressions: OK')
