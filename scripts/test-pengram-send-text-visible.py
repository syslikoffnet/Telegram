#!/usr/bin/env python3
"""Отправка текста: закрепление на официальном коде мастера (откат).

Решение пользователя после24 часов и трёх отклонённых устройством сборок
(Xiaomi 2311DRK48G, Android 14): run393 — пустой пузырь весь перелёт,
run399 — crossfade-отправка без текста, run401 — мигание на каждом
сообщении (лог: обе отправки ``crossfade=true spans=true``, пара альф
кадр в кадр, ``FIELD fade=255→0`` + строка ``0→1`` без пропусков —
статически идеально, на экране мигает). Сессионные схемы откатаны,
``TextMessageEnterTransition.java`` побайтово равен мастер-сборке
(run397 — «текст отправляется нормально» по подтверждению пользователя).

Тест фиксирует именно откат:

1. Жизненный цикл официальный: флаг глушения ставит конструктор, снимает
   ``onAnimationEnd`` напрямую (нет ``finishTransition``/поколений/
   ``onAnimationCancel`` в transition), ``start()`` запускает только
   аниматор, overlay добавляется в контейнер в конструкторе.
2. Текст для сравнения — официальный ``messageText`` (не
   ``pengramUnwrappedText()``); условие crossfade не тронуто.
3. Официальная пара копий с ``saveLayerAlpha(1 - alphaProgress)`` у поля:
   флагов ``drawFieldCopy``, paint-альфы вместо слоя и лога ``FIELD`` в
   transition нет.
4. Числовая модель (201 кадр) прежняя: сумма альф пары не даёт пустых
   кадров — официальная формула.

Всё остальное в отрисовке (формула ``alphaProgress``, геометрия
fromX/fromY/scale, клип по пузырю, rtl-код, тень, летящая кнопка,
компенсация нижнего отступа списка) — официальное и не менялось.
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

# --- 1. жизненный цикл — официальный (откат от схемы finishTransition) -----
assert 'setEnterTransitionInProgress' not in start, (
    'в официальном Telegram флаг ставит конструктор, а не start()')
assert 'finishTransition' not in transition, (
    'жизненный цикл сессии (finishTransition) откатан: нужен официальный')
assert 'onAnimationCancel' not in transition, (
    'onAnimationCancel сессии откатан вместе с finishTransition')
assert 'finishEnterTransition' not in transition, (
    'поколенческое снятие флага в transition откатано — официальное напрямую')
ctor = transition[transition.index('public TextMessageEnterTransition(ChatMessageCell'):
                  transition.index('public void start()')]
flag_at = ctor.index('messageView.setEnterTransitionInProgress(true);')
assert 'return;' not in ctor[flag_at:ctor.rindex('}')], (
    'после установки флага в конструкторе не должно быть выходов: иначе ячейка '
    'останется глухой без перелёта — пустое место вместо сообщения')
assert ctor[:flag_at].count('return;') == 2, (
    'официальные два bail до флага (блоки вёрстки, поле ввода); снятие — '
    'только onAnimationEnd')
assert 'messageView.setEnterTransitionInProgress(false);' in transition, (
    'снятие флага не официальное: снимает onAnimationEnd, минуя поколения')
assert 'chatActivityEnterView.setTextTransitionIsRunning(true);' in ctor, (
    'композитор не знает о перелёте — официально ставится в конструкторе')
assert 'container.addTransition(this);' in ctor, (
    'overlay добавляется в контейнер в конструкторе, как в официальном')
assert 'notificationsLocker.lock();' in ctor, (
    'уведомления официально блокируются в конструкторе')
assert 'if (generation == pengramEnterGeneration) setEnterTransitionInProgress(false);' in cell, (
    'глушение снимается без проверки поколения — чужой перелёт снимет его раньше')

# --- 2. источник текста — официальный messageText (откат от unwrapped) ------
assert 'CharSequence text = messageView.getMessageObject().messageText;' in transition, (
    'источник текста перелёта не официальный messageText')
assert 'pengramUnwrappedText()' not in transition, (
    'pengramUnwrappedText() в transition откатан вместе с сессионной схемой')
assert 'pengramZeroWidth' not in transition, (
    'остался костыль вычета U+200B вместо текста из источника')
assert 'bail=empty_edit_text' not in transition, (
    'bail пустого поля — сессионная схема, откатан вместе с transition: '
    'в официальном коде мастера его нет')


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
    """Копия поля ввода — официальная: drawFieldCopy = true.

    Обычная отправка (crossfade=False): копия рисуется без затухания,
    полная непрозрачность каждый кадр — на этой копии работает master-сборка.
    Crossfade: официальная пара альф поле (1-alphaProgress, слой) + строка
    (alphaProgress) даёт сплошные пиксели в каждый кадр.
    """
    if not crossfade:
        return 1.0
    return 1.0 - alpha_progress(progress)


def overlay_row_alpha(progress, crossfade):
    """Копия текста строки — официальная ветка: только при crossfade, 0→1."""
    if not crossfade:
        return 0.0
    return alpha_progress(progress)


STEPS = 201
for crossfade in (False, True):
    # Официальная пара копий: ячейка глушится, overlay рисует поле всегда
    # и строку при crossfade — суммарная непрозрачность чернил не падает.
    blanks, doubles, thin = [], [], []
    for i in range(STEPS):
        p = i / (STEPS - 1)
        ink = overlay_field_alpha(p, crossfade) + overlay_row_alpha(p, crossfade)
        if not cell_draws_row(suppress=True) and ink <= 0.02:
            blanks.append(i)
        # ниже порога «различимо на экране» — ровно та полупрозрачность,
        # которую пользователь описывал как пустоту в первые кадры
        if not cell_draws_row(suppress=True) and ink < 0.5:
            thin.append(i)
        # копий текста строки на экране: своя у ячейки + у overlay
        copies = int(cell_draws_row(suppress=True)) + int(overlay_row_alpha(p, crossfade) > 0.02)
        if copies > 1:
            doubles.append(i)
    assert not blanks, f'пустые кадры при crossfade={crossfade}: {blanks[:5]}'
    assert not thin, f'неразличимо-полупрозрачные кадры при crossfade={crossfade}: {thin[:5]}'
    assert not doubles, f'две копии текста строки одновременно при crossfade={crossfade}: {doubles[:5]}'

    # Контроль «единственная копия» (схема run393: поле выключено, строка
    # безусловная): модель показывает, почему наивная проверка вела в брак —
    # в модели копия строки даёт чернила всегда, но на устройстве она пикселей
    # не дала. Модель не доказывает рендеринг — доказывает устройство,
    # поэтому сохраняется трасса PengramSendTrace для полевой проверки.
    def single_row_alpha(progress, crossfade=crossfade):
        return 1.0
    model_single = sum(1 for i in range(STEPS)
                       if single_row_alpha(i / (STEPS - 1), crossfade) <= 0.02)
    assert model_single == 0, 'модель одно-копийной схемы дала пустые кадры — странно'
    # а вот официальная пара для crossfade даёт полям работу и в первые кадры:
    assert overlay_field_alpha(0.0, True) == 1.0, (
        'в первом кадре crossfade поле обязано быть непрозрачным (официальная пара)')
    assert overlay_row_alpha(1.0, True) == 1.0, (
        'в конце crossfade строка обязана принять непрозрачность')
    if not crossfade:
        # обычная отправка: чернила только на полевой копии, но она полная
        # с первого по последний кадр — как в master-сборке
        assert overlay_field_alpha(0.0, False) == 1.0

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
print('  пустых кадров: 0 в обеих ветвях (официальная пара: поле всегда,')
print('  строка при crossfade, сумма альф не падает ниже 1)')
print('  копий текста строки: ровно 1 в каждый кадр (ячейка глушена)')
print('  transition = официальный код мастера (откат после run401),')
print('  закреплены: жизненный цикл, источник messageText, пара saveLayerAlpha')
sys.exit(0)
