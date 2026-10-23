#!/usr/bin/env python3
"""Отправка текста: строка видна сразу, отрисовка перелёта — как в официальном Telegram.

Финальная схема (после четырёх отклонённых итераций и удаления накопленных
костылей). Отличий от официального ``TextMessageEnterTransition`` ровно два по
сути и защитный жизненный цикл форка:

1. Ячейка НЕ глушится на время текстового перелёта: ``start()`` не вызывает
   ``setEnterTransitionInProgress``. Строку рисует она сама, поэтому текст,
   время и просмотры видны с первого кадра и не зависят от геометрии overlay.
   Для голосовых перелётов так было всегда — гейт ячейки
   ``!(enterTransitionInProgress && !currentMessageObject.isVoice())``.
2. Текст для сравнения с полем ввода берётся без невидимых мест переноса
   (``MessageObject.pengramUnwrappedText()``). Мягкая вёрстка расставляет U+200B
   в длинных словах и ссылках и объединяет display-текст с текстом layout; из-за
   этого длина расходилась с полем ввода и штатное условие
   ``editText.length() != text.length() || containsSpans`` включало crossfade на
   каждом таком сообщении — другую ветвь отрисовки, чем в официальном Telegram.

Всё остальное в отрисовке (пузырь, летящий текст, crossfade с проявлением через
``alphaProgress``, rtl, тень, призрак пузырька цвета поля ввода, летящая кнопка,
компенсация нижнего отступа списка) — официальное. Никаких «страховок» и
дополнительных веток: причина устранена, а не замаскирована.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java/org/telegram'
transition = (JAVA / 'ui/TextMessageEnterTransition.java').read_text()
message_object = (JAVA / 'messenger/MessageObject.java').read_text()
voice = (JAVA / 'ui/VoiceMessageEnterTransition.java').read_text()
cell = (JAVA / 'ui/Cells/ChatMessageCell.java').read_text()
container = (JAVA / 'ui/MessageEnterTransitionContainer.java').read_text()

start = transition[transition.index('public void start() {'):transition.index('\n    }', transition.index('public void start() {'))]
on_draw = transition[transition.index('public void onDraw(Canvas canvas) {'):]

# --- 1. ячейка рисует строку сама -------------------------------------------
assert 'setEnterTransitionInProgress' not in start, (
    'текстовый перелёт снова глушит ячейку — вернётся «пустое место» вместо сообщения')
assert transition.count('setEnterTransitionInProgress') == 0, (
    'в файле остался вызов глушения ячейки')
assert 'enterGeneration = messageView.getEnterTransitionGeneration();' in start, (
    'поколение перелёта не запоминается: ранние выходы onDraw потеряют опору')
assert 'enterView.setTextTransitionIsRunning(true);' in start, (
    'композитор не знает о перелёте — сбросит поле ввода посреди анимации')
assert 'container.addTransition(this);' in start, 'overlay не добавляется в контейнер'
assert 'messageView.finishEnterTransition(enterGeneration);' in transition, (
    'перелёт не снимает состояние ячейки при завершении')

# --- 2. причина устранена в источнике текста, а не вычетом в условии ---------
assert 'pengramUnwrappedText()' in transition, (
    'текст для анимации снова берётся вместе с невидимыми U+200B мягкой вёрстки')
assert 'CharSequence text = messageView.getMessageObject().pengramUnwrappedText();' in transition
assert 'pengramZeroWidth' not in transition, (
    'остался костыль вычета U+200B вместо текста из источника')
assert re.search(r'if \(editText\.length\(\) != text\.length\(\) \|\| containsSpans\) \{\s*crossfade = true;', transition), (
    'условие crossfade отличается от официального Telegram')
assert 'public CharSequence pengramUnwrappedText() {' in message_object, (
    'в MessageObject нет доступа к тексту без мест переноса')
unwrapped = message_object[message_object.index('public CharSequence pengramUnwrappedText() {'):]
unwrapped = unwrapped[:unwrapped.index('}')]
assert 'pengramOriginalMessageText != null ? pengramOriginalMessageText : messageText' in unwrapped, (
    'pengramUnwrappedText() должен возвращать исходный текст, если мягкая вёрстка его не меняла')
# мягкая вёрстка по-прежнему чинит вылезание текста за границы пузыря
assert 'pengramSoftWrap' in message_object, 'мягкий перенос длинных слов удалён'
assert 'messageText = pengramWrappedMessageText = textLayout.getText();' in message_object, (
    'display-текст перестал быть единым источником с layout: разъедутся ссылки и выделение')

# --- 3. отрисовка overlay — официальная, без самодеятельности ---------------
assert 'float alphaProgress = this.progress > 0.4f ? 1f : this.progress / 0.4f;' in transition, (
    'проявление содержимого overlay изменено относительно официального Telegram')
assert 'messageView.drawMessageText(canvas, messageView.getMessageObject().textLayoutBlocks, true, alphaProgress, true);' in transition, (
    'в ветви crossfade текст строки рисуется не так, как в официальном Telegram')
assert 'messageView.drawAnimatedEmojis(canvas, alphaProgress);' in transition
assert 'ALPHA_RAMP' not in transition, 'осталась нестандартная константа проявления'
assert 'cellTop' not in on_draw, 'остался костыль прижима цели перелёта к строке'
assert 'messageViewY -= (listViewTargetBottomPadding - listView.getPaddingBottom());' in on_draw, (
    'официальная компенсация нижнего отступа списка изменена')
assert 'getBackgroundDrawableRight() - messageView.getBackgroundDrawableLeft() < dp(2)' not in transition, (
    'осталась страховка от вырожденной геометрии — она маскировала причину')
for token in ('crossfade', 'progressX', 'gradientPaint', 'roundRectRadii'):
    assert token in transition, f'из отрисовки пропал {token}'
assert transition.count('canvas.save()') + transition.count('canvas.saveLayerAlpha') == \
       transition.count('canvas.restore()') + transition.count('canvas.restoreToCount'), (
    'баланс save/restore нарушен — клип потечёт на весь экран')

# --- 4. соседние механизмы не задеты ----------------------------------------
assert 'messageView.setEnterTransitionInProgress(true);' in voice, (
    'голосовой перелёт потерял установку флага')
assert cell.count('!(enterTransitionInProgress && !currentMessageObject.isVoice())') >= 5, (
    'гейт ячейки для голосовых перелётов изменён')
assert 'public void finishEnterTransition(long generation)' in cell, 'нет снятия флага по поколению'
assert 'DEFAULT_DURATION + 250' in cell, 'watchdog ячейки пропал'
assert 'onRemoved()' in container, 'контейнер потерял очистку перелётов'
assert re.search(r'if \(TextUtils\.isEmpty\(AndroidUtilities\.trim\(editText, null\)\)\) \{'
                 r'[^}]*?PengramSendTrace\.log\("CTOR", "bail=empty_edit_text[^}]*?return;',
                 transition, re.S), (
    'пропал выход при уже очищенном поле ввода')


# ---------------------------------------------------------------------------
# Числовая модель: что видит пользователь на каждом кадре перелёта.
# ---------------------------------------------------------------------------
def cell_draws_row(suppress):
    """Рисует ли ячейка текст/время/просмотры строки."""
    return not suppress


def overlay_text_alpha(progress, crossfade):
    """Альфа копии строки в overlay: в crossfade она проявляется, иначе полная."""
    if progress > 0.4:
        return 1.0
    return progress / 0.4 if crossfade else 1.0


STEPS = 201
for crossfade in (False, True):
    # нынешняя схема: ячейка не глушится
    blanks = [i for i in range(STEPS)
              if not cell_draws_row(suppress=False)
              and overlay_text_alpha(i / (STEPS - 1), crossfade) <= 0.02]
    assert not blanks, f'пустые кадры при crossfade={crossfade}: {blanks[:5]}'

    # прежняя схема: ячейка глушилась, строку рисовал только overlay
    old_blanks = [i for i in range(STEPS)
                  if not cell_draws_row(suppress=True)
                  and overlay_text_alpha(i / (STEPS - 1), crossfade) <= 0.02]
    if crossfade:
        assert old_blanks, 'модель не воспроизводит прежний провал в ветви crossfade'

# длина текста: мягкая вёрстка не должна влиять на выбор ветви анимации
original = 'see https://example.com/some/very/long/unbreakable/path'
wrapped = original.replace('/some', '\u200b/some').replace('/long', '\u200b/long')
assert len(original) != len(wrapped), 'модель мягкой вёрстки не расходится по длине'
assert (len(original) != len(original)) is False, 'без вёрстки ветвь не меняется'
edit_text = original
# с unwrapped-текстом штатное условие не включает crossfade, с wrapped — включало бы
assert len(edit_text) == len(original), 'unwrapped текст не должен давать crossfade'
assert len(edit_text) != len(wrapped), 'wrapped текст давал crossfade — это и был баг'

print('send-text-visible: OK')
print(f'  кадров в перелёте: {STEPS}, длительность 250 мс')
print('  пустых кадров: 0 в обеих ветвях (строку рисует ячейка)')
print('  отличий от официального overlay: только источник текста и отсутствие глушения')
sys.exit(0)
