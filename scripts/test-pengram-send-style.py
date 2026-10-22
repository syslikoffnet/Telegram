#!/usr/bin/env python3
"""Regression checks for outgoing style, emoji layout and settings navigation.

These are source-level checks; Android Java compilation runs separately in CI.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'TMessagesProj/src/main'
java = root / 'java/org/telegram'
style = (java / 'messenger/PengramTextStyle.java').read_text()
config = (java / 'messenger/PengramConfig.java').read_text()
send = (java / 'messenger/SendMessagesHelper.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
index = (java / 'ui/PengramSearchIndex.java').read_text()
metadata = (java / 'ui/PengramSearchMetadata.java').read_text()

assert 'getBool(KEY_SEND_STYLE_LARGE_EMOJI, true)' in config
assert '"sendStyleLargeEmoji"' in config
assert 'Emoji.parseEmojis(text, emojiOnly)' in style
assert 'standaloneMessage && PengramConfig.isSendStyleLargeEmoji()' in style
assert style.index('if (emojiOnly[0] > 0)') < style.index('if (style == PengramConfig.SEND_STYLE_WIDE)', style.index('public static Styled apply('))
assert style.index('return null;', style.index('if (emojiOnly[0] > 0)')) < style.index('final String trimmed = text.trim();')
assert 'PengramTextStyle.apply(message, entities)' in send
assert 'PengramTextStyle.apply(caption, entities, false)' in send
assert 'retryMessageObject == null' in send[send.index('// Pengram: автоматический стиль'):send.index('if (richMessage != null', send.index('// Pengram: автоматический стиль'))]
assert 'checkInfo(PengramConfig.KEY_SEND_STYLE_LARGE_EMOJI, true,' in settings
assert settings.index('private void fillTyping(') < settings.index('getString(R.string.PengramSendStyleLargeEmoji)') < settings.index('private void fillMediaTime(', settings.index('private void fillTyping('))
assert '{R.string.PengramSendStyle, PengramSettingsActivity.SECTION_TYPING,' in index
assert '{R.string.PengramSendStyleLargeEmoji, PengramSettingsActivity.SECTION_TYPING,' in index
assert 'id == R.string.PengramSendStyle' in metadata and ' шрифтом писать' in metadata
for locale in ('values', 'values-ru'):
    strings = list(ET.parse(root / f'res/{locale}/strings.xml').iter('string'))
    names = {node.get('name') for node in strings}
    assert {'PengramSendStyleLargeEmoji', 'PengramSendStyleLargeEmojiInfo'} <= names
    # XML parsing alone accepts apostrophes which Android's resource flattener
    # rejects. This previously broke the APK while Java preflight passed.
    for node in strings:
        if (node.get('name') or '').startswith('Pengram'):
            value = ''.join(node.itertext())
            assert not __import__('re').search(r"(?<!\\)'", value), node.get('name')
print('Outgoing style and large-emoji routing: OK')
