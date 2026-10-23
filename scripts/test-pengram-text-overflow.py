#!/usr/bin/env python3
"""Текст не должен вылезать из пузыря.

Страховка при отрисовке включается по правой границе блока (`maxRight`), но
раньше эта граница считалась только для цитат — у обычного сообщения она
оставалась нулевой, и страховка не срабатывала вообще. Здесь проверяется, что
граница считается для всех блоков, а сама отрисовка действительно умеет
обрезать вылезший текст (включая RTL-строки).
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
message = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java').read_text()
cell = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java').read_text()

# --- правая граница блока считается всегда -----------------------------------
count = message.count('block.maxRight = 0;')
assert count == 2, count
assert 'if (block.quote) {\n                block.maxRight = 0;' not in message
assert 'if (block.quote) {\n                    block.maxRight = 0;' not in message
# рядом с расчётом — пояснение, зачем это обычным сообщениям
assert message.count('по ней страховка при отрисовке ловит текст') == 2
for index in [i for i in range(len(message)) if message.startswith('block.maxRight = 0;', i)]:
    tail = message[index:index + 600]
    assert 'getLineRight(n)' in tail, tail[:120]

# --- страховка в ячейке ------------------------------------------------------
assert 'boolean pengramOverflow = false;' in cell
assert 'b.maxRight > maxWidth + 1' in cell
# RTL-строки проверяются по ширине разметки: правая граница строки не видит вылет влево
assert 'b.isRtl() && b.textLayout != null && b.textLayout.getWidth() > maxWidth + 1' in cell
assert 'canvas.clipRect(Math.min(textX - rtlOffset, textX) - dp(4)' in cell

# --- длинные слова по-прежнему переносятся, а не растягивают пузырь ----------
assert 'private static CharSequence pengramSoftWrap(' in message
assert 'private static int pengramFitWidth(' in message
assert 'private static boolean pengramLinesWider(' in message
assert '\\u200B' in message or '\u200b' in message

print('text overflow: block right edge measured for every block, clip safety net armed: OK')
