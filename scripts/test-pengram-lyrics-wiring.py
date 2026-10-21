#!/usr/bin/env python3
"""Verify the two lyric switches are independent and reach both player surfaces."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'TMessagesProj/src/main'
config = (root / 'java/org/telegram/messenger/PengramConfig.java').read_text()
view = (root / 'java/org/telegram/ui/Components/PengramLyricsView.java').read_text()
settings = (root / 'java/org/telegram/ui/PengramSettingsActivity.java').read_text()

for key, method in [('KEY_LYRICS_STRETCH', 'isLyricsStretch'),
                    ('KEY_LYRICS_SMOOTH', 'isLyricsSmooth')]:
    assert f'boolean {method}() {{ return getBool({key}, true); }}' in config
    assert f'checkInfo(PengramConfig.{key}, true' in settings
    assert f'PengramConfig.{method}()' in view
assert 'updateTimeScale();' in view and 'stretchEnabled != PengramConfig.isLyricsStretch()' in view
assert 'if (lyricsLength > 0) return lyricsLength;' in view
assert 'PengramLyricsTiming.position(value, progressTime' in view
assert 'PengramLyricsTiming.sample((long) (this.progress * durationMs)' in view
assert 'if ((smooth || tickerMode) && durationMs > 0)' in view
assert 'boolean interpolate = previewMode || !timed || PengramConfig.isLyricsSmooth()' in view
assert 'PengramLyricsTiming.seekFraction(time + PengramLyrics.getOffset(offsetKey), timeScale, durationMs)' in view
assert 'PengramConfig.KEY_LYRICS_SMOOTH' in settings.split('if (!PengramConfig.isLyricsAuto())', 1)[1].split('items.add(UItem.asHeader(getString(R.string.PengramLyricsHeader)))', 1)[0]
for locale in ('values', 'values-ru'):
    ET.parse(root / 'res' / locale / 'strings.xml')
print('Lyric switches and playback wiring: OK')
