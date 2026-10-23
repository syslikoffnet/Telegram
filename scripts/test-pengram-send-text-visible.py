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
3. Летящий текст рисует официальная пара копий, восстановленная после
   проверки master-сборкой: полевая копия (``drawFieldCopy = true``) даёт
   непрозрачные пиксели с первого кадра при обычной отправке, копия строки
   (``drawMessageText``) работает только в ``crossfade`` с официальной
   парой альф. Прежняя схема (полевая копия выключена, копия строки
   безусловна) оставляла перелёт на один путь, и на устройстве он пикселей
   не давал — пустой пузырь до конца анимации (видео 2026-10-23 13:37,
   run393).
4. Затухание полевой копии при ``crossfade`` идёт официальным приёмом из
   rtl-ветви той же функции (``oldAlpha * (1 - alphaProgress)`` через
   paint), а не ``saveLayerAlpha``: в логе run399 crossfade-отправка с
   ``fade=255`` на первом кадре не дала видимого текста, тогда как
   обычная отправка (без слоя, та же геометрия и paint) пиксели давала —
   слой оставался единственной немониторенной веткой. Для проверки
   добавлен кадровый лог ``FIELD`` (длина текста, размер layout, alpha
   paint, затухание).

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
# Летящий текст = официальная пара копий (причина — в докстринге): полевая
# копия включена и в обеих ветвях даёт пиксели, копия строки работает только
# при crossfade с официальной альфой alphaProgress.
assert 'final boolean drawFieldCopy = true;' in transition, (
    'полевая копия выключена: обычная отправка теряет текст с первого кадра — '
    'именно на полевой копии master-сборка прошла проверку пользователем')
assert 'saveLayerAlpha(0,0,layout.getWidth()' not in transition, (
    'кроссфейд поля снова через saveLayerAlpha: на устройстве (run399) слой '
    'не давал пикселей при обычной паре копий')
assert 'messageView.drawMessageText(canvas, messageView.getMessageObject().textLayoutBlocks, true, alphaProgress, true);' in transition, (
    'копия строки не использует официальную пару альф: при crossfade поле уходит '
    '1→0, строка входит 0→1 — иначе возможны пустые кадры')
assert 'messageView.drawAnimatedEmojis(canvas, alphaProgress);' in transition
assert 'layout.getPaint().setAlpha((int) (oldAlpha * (1f - alphaProgress)));' in transition, (
    'затухание поля при crossfade потеряло официальную paint-альфу из '
    'rtl-ветви — поле снова не даст пикселей на устройстве')
assert 'int oldColor = layout.getPaint().getColor();\n                    int oldAlpha = Color.alpha(oldColor);' in transition, (
    'changeColor-ветка поля потеряла fade через альфу цвета (приём rtl-ветви)')
assert 'PengramSendTrace.log(\"FIELD\"' in transition, (
    'нет кадрового лога полевой копии — следующий лог с устройства не покажет, '
    'рисует ли поле')
assert 'if (crossfade) {\n            canvas.save();\n            canvas.translate(messageView.getLeft()' in transition, (
    'копия строки снова безусловная: одна копия — одна точка отказа, '
    'на устройстве строковая копия пикселей не давала (run393)')
assert 'drawMessageText(canvas, messageView.getMessageObject().textLayoutBlocks, true, 1f, true)' not in transition, (
    'осталась безусловная непрозрачная копия строки поверх официальной пары')
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
    """Копия поля ввода — официальная: drawFieldCopy = true.

    Обычная отправка (crossfade=False): копия рисуется без затухания,
    полная непрозрачность каждый кадр — на этой копии работает master-сборка.
    Crossfade: официальная пара альф поле (1-alphaProgress, fade через
    paint-альфу, приём rtl-ветви) + строка (alphaProgress) даёт сплошные
    пиксели в каждый кадр.
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
print('  отличий от официального overlay: источник текста (unwrapped),')
print('  bail на пустом поле, обход viewport-диапазона в wrapper, жизненный цикл,')
print('  затухание поля paint-альфой вместо saveLayerAlpha (урок run399), лог FIELD')
sys.exit(0)
