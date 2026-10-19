#!/usr/bin/env python3
"""Regression checks for player switches and chat/playlist identity wiring.

These are static invariants, not a substitute for Android playback testing.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1] / 'TMessagesProj/src/main/java/org/telegram'
sheet = (root / 'ui/Components/PengramMusicPlayerSheet.java').read_text()
lyrics = (root / 'ui/Components/PengramLyricsView.java').read_text()
settings = (root / 'ui/PengramSettingsActivity.java').read_text()
header = (root / 'ui/Components/FragmentContextView.java').read_text()
swipe = (root / 'ui/Components/PengramPlayerSwipe.java').read_text()

assert 'check(PengramConfig.KEY_PLAYER_BLUR, true' in settings
assert 'check(PengramConfig.KEY_LYRICS_AUTOSCROLL, true' in settings
assert 'checkInfo(PengramConfig.KEY_HEADER_LYRICS_MARQUEE, true' in settings

# Follow must not assign an active-line scroll target when disabled, and
# touching the lyrics must temporarily stop follow even when it is enabled.
follow = lyrics.split('if (previewMode) {\n            targetScrollY = scrollY = 0;', 1)[1].split('scrollY = AndroidUtilities.lerp', 1)[0]
assert re.search(r'else if \(!cfgAutoscroll\)\s*\{[^}]*targetScrollY = scrollY;', follow)
assert re.search(r'else if \(!touchScrolling && activeLine >= 0', follow)
assert 'builtMarquee == marquee' in lyrics
assert 'if (!marquee) {\n            tickerScrollX = tickerTargetX = 0;' in lyrics
assert 'buildLayouts();' in lyrics.split('private void refreshConfig(long now)', 1)[1].split('private float[] widthsFor', 1)[0]

# The background switch selects blurred vs intact artwork, not artwork vs
# gradient. No stale cover should remain when the playing track changes.
background = sheet.split('private class BackgroundView', 1)[1].split('private void toggleLyrics', 1)[0]
assert 'blur ? blurred : unblurred' in background
assert 'Utilities.stackBlurBitmap(blurredCopy, 6)' in background
assert 'canvas.drawBitmap(artwork, crop, rect, paint)' in background
assert 'backgroundView.setCover(null);' in sheet.split('private void updateCover(', 1)[1].split('final AudioInfo audioInfo', 1)[0]

# Message IDs collide across chats; both queue and media-load responses need
# the dialog ID as well as the message ID / request class GUID.
queue = sheet.split('private void updateQueue()', 1)[1].split('private void updateChatTracks()', 1)[0]
assert 'playlist.get(a).getDialogId() == playing.getDialogId()' in queue
assert 'messageObject.getDialogId() == playing.getDialogId()' in queue
assert 'row.getTop() - dp(120)' in queue  # actual row height in every layout
assert 'args[0] instanceof Long && (Long) args[0] == chatTracksDialogId' in sheet
prefetch = sheet.split('private void pengramPrefetchNext()', 1)[1].split('private void shiftLyrics(', 1)[0]
assert 'if (SharedConfig.shuffleMusic)' in prefetch
assert 'SharedConfig.playOrderReversed ? 1 : -1' in prefetch
assert re.search(r'if \(queueShown\)\s*\{\s*if \(panelTab == 1\)\s*\{\s*loadChatTracks\(\);', sheet)

# The compact header must stop showing lyrics when toggled off, including
# callbacks for lyric requests issued while the toggle was still on.
assert 'if (pengramTicker != null && !PengramConfig.isHeaderLyrics())' in header
callback = header.split('PengramLyrics.request(messageObject, (resultKey, raw, state) -> {', 1)[1].split('if (state == PengramLyrics.STATE_FOUND', 1)[0]
assert '!PengramConfig.isHeaderLyrics()' in callback
assert 'PengramConfig.isPlayerSwipe()' in header
assert 'if (cancelled || animator != animation)' in swipe
assert re.search(r'final ValueAnimator previous = animator;\s*animator = null;\s*previous.cancel\(\);', swipe)
print('Player settings, artwork, compact header, swipe and queue/chat wiring: OK')
