#!/usr/bin/env python3
"""Ранний выход насоса не должен навсегда замораживать прикреплённую шапку."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'TMessagesProj/src/main/java/org/telegram/ui/Components'
s = (root / 'FragmentContextView.java').read_text()
pump = s.split('private final Runnable pengramTickerPump', 1)[1].split('private void pengramStartTickerPump()', 1)[0]
assert pump.index('runOnUIThread(this, 500)') < pump.index('!PengramConfig.isHeaderLyrics()')
assert 'if (!isAttachedToWindow())' in pump
assert 'checkPlayer(false)' in pump and 'getPlayingMessageObject()' in pump
assert 'keyFor(current)' in pump and 'setTickerMode(true,' in pump
attach = s.split('protected void onAttachedToWindow()', 1)[1].split('public void didReceivedNotification', 1)[0]
assert 'pengramStartTickerPump();' in attach
assert 'protected void onVisibilityChanged(View changedView, int visibility)' in s
show = s.split('private void pengramShowTicker()', 1)[1].split('private void pengramHideTicker()', 1)[0]
assert show.index('pengramStartTickerPump()') < show.index('if (pengramTickerShown)')
assert 'setVisibility(VISIBLE)' in show and 'postInvalidateOnAnimation()' in show
view = (root / 'PengramLyricsView.java').read_text()
assert 'tickerMode == ticker && previewSize == textSize && animOverride == anim' in view
print('ticker: lifecycle, track identity, reusable show, settings: OK')
