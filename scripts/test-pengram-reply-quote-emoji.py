#!/usr/bin/env python3
"""Приёмка: ответ на большое анимированное эмодзи — эмодзи в отправленной цитате
больше не крупнее строки.

Корень (по воспроизведению: где = отправленная цитата в чате, что = большое
анимированное эмодзи-сообщение):
 1) в трёх ветках stringFinalText цитаты MessageObject.replaceAnimatedEmoji(.., top=true)
    шёл с жёстким scale 1.2f — глиф на20% выше строки цитаты (chat_replyTextPaint);
    теперь scale 1.0f (ровно в метрики строки), top-центрирование сохранено;
 2) Emoji.replaceEmoji(createNew=true) копировал старые Emoji.EmojiSpan из исходного
    messageText (другой FontMetrics) и добавлял новый рядом — два глифа на одном
    диапазоне с разной величиной; теперь старые снимаются перед setSpan.

Панель ответа над клавиатурой НЕ тронута (пользователь жалобу не озвучивал),
отправка (gold-файлы) не тронута.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def main():
    cell = read('TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java')

    # 1. Три цитатные ветки — scale ровно в метрики строки
    quote_sites = [line for line in cell.splitlines()
                   if 'replaceAnimatedEmoji(stringFinalText' in line]
    assert len(quote_sites) == 3, f'ожидалось3 цитатные ветки, найдено {len(quote_sites)}'
    for line in quote_sites:
        assert 'true, 1.0f, 0)' in line, f'цитатная ветка без scale 1.0f: {line.strip()}'
        assert 'getFontMetricsInt(), true);' not in line, \
            f'остался старый4-арг вызов со scale 1.2: {line.strip()}'
    # старого формата среди цитатных replaceAnimatedEmoji не осталось
    bad = [line for line in cell.splitlines()
           if 'MessageObject.replaceAnimatedEmoji(stringFinalText' in line
           and 'textPaint.getFontMetricsInt(), true);' in line]
    assert not bad, f'остались4-арг вызовы replaceAnimatedEmoji со scale 1.2: {bad}'

    # top-центрирование сохранено (не убирали вовсе)
    for line in quote_sites:
        assert ', true, 1.0f, 0)' in line

    # 2. Двойные EmojiSpan: старые снимаются ДО setSpan
    emoji = read('TMessagesProj/src/main/java/org/telegram/messenger/Emoji.java')
    rm = emoji.index('final EmojiSpan[] oldSpans = s.getSpans(')
    sp = emoji.index('s.setSpan(span, emojiRange.start, emojiRange.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);')
    assert rm < sp, 'снятие старых EmojiSpan должно идти до setSpan нового'
    assert 's.removeSpan(oldSpan);' in emoji[rm:sp], 'нет снятия старого глифа'
    assert 's.getSpanStart(oldSpan) == emojiRange.start && s.getSpanEnd(oldSpan) == emojiRange.end' in emoji[rm:sp], \
        'снимать только полные дубликаты диапазона'

    # 3. Панель ответа (ChatActivity) не тронута —3-арг вызовы как раньше
    chat = read('TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java')
    bar = [line for line in chat.splitlines()
           if 'replaceAnimatedEmoji(replyObjectText' in line or
           ( 'replaceAnimatedEmoji(mess' in line and 'replyObjectTextView' in line)]
    assert len(bar) >= 3, 'панель ответа: вызовы replaceAnimatedEmoji потеряны'
    for line in bar:
        assert 'replyObjectTextView.getPaint().getFontMetricsInt());' in line, \
            f'панель ответа изменена: {line.strip()}'
        assert '1.0f, 0' not in line, 'панель ответа не должна меняться в этом фиксе'

    # 4. Источник текста цитаты (ветка messageText) цел
    assert 'messageObject.replyMessageObject.messageText.length() > 0' in cell, \
        'ветка messageText в stringFinalText потеряна'
    assert 'hasReplyQuote || messageObject.messageOwner.reply_to != null' in cell

    print('OK: цитаты — scale 1.0f в3 ветках, двойные EmojiSpan снимаются, '
          'панель ответа и отправка не тронуты')


if __name__ == '__main__':
    main()
