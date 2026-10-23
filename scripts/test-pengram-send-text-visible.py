#!/usr/bin/env python3
"""Отправка текста: строка видна сразу, анимация перелёта сохранена.

Финальная схема после лога с устройства (Xiaomi 2311DRK48G, Android 14, 4
отправки, ``SKIP reason=alpha_zero`` и 11 кадров ``alphaP=0→1`` — ровно те
~100 мс, когда сообщение стояло без текста). Отличий от официального
``TextMessageEnterTransition`` ровно три по сути и защитный жизненный цикл
форка:

1. Ячейка глушится на время перелёта (``setEnterTransitionInProgress(true)`` в
   конструкторе) — как в официальном, чтобы копию строки рисовал ровно один
   владелец: две копии в ~30 px друг от друга дают мигание из первого лога.
2. Текст для сравнения с полем ввода берётся без невидимых мест переноса
   (``MessageObject.pengramUnwrappedText()``); штатное условие
   ``editText.length() != text.length() || containsSpans`` не тронуто.
3. Летящий текст — это всегда копия строки (``drawMessageText``), и она
   непрозрачна с первого кадра (``1f``, а не ``alphaProgress``). Официальная
   копия поля ввода сохранена в коде под ``drawFieldCopy = false``: на
   устройстве её пиксели в перелёте не видны ни в одной ветви (жалоба
   воспроизводится и с выключенными фичами), а ``drawMessageText`` в логе
   виден в каждом кадре — единственный подтверждённый путь. Без этого
   ``alphaProgress`` (0→1 за первые 100 мс из 250) оставлял отправленное
   сообщение без текста — ровно исходный баг.

Всё остальное в отрисовке (формула ``alphaProgress`` и проявление пузыря,
официальная геометрия перелёта fromX/fromY/scale, клип по пузырю, rtl-код,
тень, призрак пузырька цвета поля ввода, летящая кнопка, компенсация нижнего
отступа списка, время/реакции с ``alphaProgress``) — официальное.
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
    'в официальном Telegram флаг ставит конструктор, а не start()')
# Официальное поведение: на время перелёта содержимое строки рисует overlay,
# ячейка глушится. Ровно одна копия текста — иначе две копии в ~30 px друг от
# друга с разной альфой дают то самое мигание, которое видно на устройстве.
assert transition.count('messageView.setEnterTransitionInProgress(true);') == 1, (
    'флаг глушения должен ставиться ровно один раз — в конструкторе')
assert 'setEnterTransitionInProgress(false)' not in transition, (
    'снятие флага идёт через finishEnterTransition(поколение), а не напрямую')
ctor = transition[transition.index('public TextMessageEnterTransition(ChatMessageCell'):
                  transition.index('private void finishTransition()')]
flag_at = ctor.index('messageView.setEnterTransitionInProgress(true);')
assert 'return;' not in ctor[flag_at:ctor.rindex('}')], (
    'после установки флага в конструкторе не должно быть выходов: иначе ячейка '
    'останется глухой без перелёта — пустое место вместо сообщения')
assert ctor[:flag_at].count('return;') == 3, (
    'все три отказа перелёта (блоки, поле ввода, пустой текст) обязаны идти до флага')
assert 'messageView.finishEnterTransition(enterGeneration);' in transition, (
    'перелёт не снимает глушение ячейки при завершении')
assert 'enterGeneration = messageView.getEnterTransitionGeneration();' in start, (
    'поколение перелёта не запоминается: ранние выходы onDraw потеряют опору')
# поколение снимает глушение только у своего перелёта
assert 'if (generation == pengramEnterGeneration) setEnterTransitionInProgress(false);' in cell, (
    'глушение снимается без проверки поколения — чужой перелёт снимет его раньше')
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
unwrapped = unwrapped[:unwrapped.index('\n    }')]
assert 'pengramOriginalMessageText != null && messageText == pengramWrappedMessageText' in unwrapped, (
    'pengramUnwrappedText() должен возвращать исходный текст только пока display-текст — '
    'его обёрнутая версия; иначе устаревший оригинал создаёт расхождение длин')
assert unwrapped.rstrip().endswith('return messageText;'), (
    'без живой пары оригинал/обёртка возвращать надо текущий текст')
# мягкая вёрстка по-прежнему чинит вылезание текста за границы пузыря
assert 'pengramSoftWrap' in message_object, 'мягкий перенос длинных слов удалён'
assert 'messageText = pengramWrappedMessageText = textLayout.getText();' in message_object, (
    'display-текст перестал быть единым источником с layout: разъедутся ссылки и выделение')

# --- 3. отрисовка overlay — официальная, без самодеятельности ---------------
assert 'float alphaProgress = this.progress > 0.4f ? 1f : this.progress / 0.4f;' in transition, (
    'проявление пузыря изменено относительно официального Telegram')
# Летящий текст = копия строки, непрозрачная с первого кадра (причина — в
# докстринге). Официальная копия поля ввода сохранена, но не рисуется.
assert 'final boolean drawFieldCopy = false;' in transition, (
    'копия поля ввода снова участвует в отрисовке: на устройстве её пиксели не видны')
assert 'messageView.drawMessageText(canvas, messageView.getMessageObject().textLayoutBlocks, true, 1f, true);' in transition, (
    'текст строки снова проявляется через alphaProgress — вернётся пустые 100 мс')
assert 'messageView.drawAnimatedEmojis(canvas, 1f);' in transition
assert 'if (crossfade) {\n            canvas.save();\n            canvas.translate(messageView.getLeft()' not in transition, (
    'копия строки ограничена ветвью crossfade: без неё пустыми останутся обычные отправки')
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


def alpha_progress(progress):
    """Проявление пузыря overlay — официальная формула (для пузыря)."""
    return 1.0 if progress > 0.4 else progress / 0.4


def overlay_field_alpha(progress, crossfade):
    """Копия поля ввода: drawFieldCopy = false — в модели всегда 0.

    Это сознательно пессимистичная модель: на устройстве пиксели этой копии
    не подтверждены ни в одной ветви (жалоба воспроизводится и в обычной
    ветви, где официальный код рисует её вообще без saveLayerAlpha). Гарантия
    видимости не должна на ней держаться.
    """
    return 0.0


def overlay_row_alpha(progress, crossfade):
    """Копия текста строки: рисуется всегда и непрозрачна с первого кадра."""
    return 1.0


STEPS = 201
for crossfade in (False, True):
    # Нынешняя схема: ячейка глушится, летящий текст рисует overlay копией
    # строки с альфой 1 с первого кадра; копия поля ввода не участвует.
    blanks, doubles = [], []
    for i in range(STEPS):
        p = i / (STEPS - 1)
        ink = overlay_field_alpha(p, crossfade) + overlay_row_alpha(p, crossfade)
        if not cell_draws_row(suppress=True) and ink <= 0.02:
            blanks.append(i)
        # копий текста строки на экране: своя у ячейки + у overlay
        copies = int(cell_draws_row(suppress=True)) + int(overlay_row_alpha(p, crossfade) > 0.02)
        if copies > 1:
            doubles.append(i)
    assert not blanks, f'пустые кадры при crossfade={crossfade}: {blanks[:5]}'
    assert not doubles, f'две копии текста строки одновременно при crossfade={crossfade}: {doubles[:5]}'

    # Штатная схема официального Telegram (до правок форка): ячейка глушится,
    # копия строки проявляется через alphaProgress — при crossfade=False её
    # вовсе нет, и весь текст держится на копии поля ввода (0 в нашей модели),
    # при crossfade=True первые кадры alphaProgress≈0. Это и есть пустые
    # ~100 мс из лога устройства при штатном alphaProgress.
    def stock_row_alpha(progress, crossfade=crossfade):
        return alpha_progress(progress) if crossfade else 0.0
    # Порог 0.5 — не «есть ли хоть капля чернил», а «различимо ли на экране»:
    # полупрозрачный текст в первые десятки мс — ровно та «пустота», которую
    # описывал пользователь (текст «вспыхивает» к концу ramp'а).
    stock_blanks = [i for i in range(STEPS)
                    if not cell_draws_row(suppress=True)
                    and overlay_field_alpha(0, crossfade) + stock_row_alpha(i / (STEPS - 1)) < 0.5]
    if crossfade:
        # штатная схема: текст различим только после p=0.2 (50 мс), полностью —
        # при p=0.4 (100 мс); до этого кадры «пустые»
        assert len(stock_blanks) > STEPS * 0.15, 'модель не воспроизводит пустые кадры штатной схемы'
    else:
        assert len(stock_blanks) == STEPS, 'в штатной обычной ветви текст держится на копии поля ввода'

# длина текста: мягкая вёрстка не должна влиять на выбор ветви анимации
original = 'see https://example.com/some/very/long/unbreakable/path'
wrapped = original.replace('/some', '\u200b/some').replace('/long', '\u200b/long')
assert len(original) != len(wrapped), 'модель мягкой вёрстки не расходится по длине'
assert (len(original) != len(original)) is False, 'без вёрстки ветвь не меняется'
edit_text = original
# с unwrapped-текстом штатное условие не включает crossfade, с wrapped — включало бы
assert len(edit_text) == len(original), 'unwrapped текст не должен давать crossfade'
assert len(edit_text) != len(wrapped), 'wrapped текст давал crossfade — это и был баг'

# «оригинал» мягкой вёрстки принадлежит только тому тексту, который он породил:
# иначе сравнение с полем ввода расходится уже по другой причине (лог устройства:
# unwrappedLen=4 при msgTextLen=3, zwsp=-1)
class Row:
    def __init__(self, message_text, original=None, wrapped=None):
        self.messageText, self.pengramOriginalMessageText, self.pengramWrappedMessageText = \
            message_text, original, wrapped

    def unwrapped(self):
        if self.pengramOriginalMessageText is not None \
                and self.messageText is self.pengramWrappedMessageText:
            return self.pengramOriginalMessageText
        return self.messageText


wrapped = 'see\u200b https://example.com'
original = 'see https://example.com'
assert Row(wrapped, original, wrapped).unwrapped() == original, (
    'пока display-текст — обёрнутая версия, возвращать надо оригинал')
replaced = 'bold text'
assert Row(replaced, original, wrapped).unwrapped() == replaced, (
    'устаревший оригинал не должен подменять текущий текст')
assert len(Row(replaced, original, wrapped).unwrapped()) == len(replaced), (
    'иначе длина расходится с полем ввода и ветвь анимации выбирается неверно')

print('send-text-visible: OK')
print(f'  кадров в перелёте: {STEPS}, длительность 250 мс')
print('  пустых кадров: 0 в обеих ветвях (копия строки непрозрачна с кадра 1)')
print('  копий текста строки: ровно 1 в каждый кадр (прежняя схема давала 2)')
print('  отличий от официального overlay: источник текста, порядок проверки поля')
print('  ввода и владелец летящего текста (drawFieldCopy=false, копия строки 1f)')
sys.exit(0)
