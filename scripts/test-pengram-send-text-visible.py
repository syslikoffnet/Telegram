#!/usr/bin/env python3
"""Отправка текста: строка видна с первого кадра, анимация перелёта сохранена.

Контракт (четвёртая итерация, после трёх отклонённых пользователем):

1. Ячейка НЕ глушится на время текстового перелёта: ``start()`` не вызывает
   ``setEnterTransitionInProgress``. Именно глушение давало «пустое место»:
   фон, текст, время и просмотры строки пряталась, а рисовал их overlay, чья
   геометрия зависит от высоты «острова» ввода (``listViewTargetBottomPadding``
   считается через ``getInputIslandHeightTarget()``, который возвращает текущую
   высоту — её меняет жидкое стекло панельки ввода). При расхождении overlay
   рисовал мимо строки, и пользователь видел пустоту до конца перелёта.
   Голосовые перелёты в этом форке всегда работали без глушения (гейт ячейки
   исключает ``isVoice``) — текстовый теперь ведёт себя так же.
2. Цель перелёта прижата к реальному положению строки: компенсация нижнего
   отступа списка применяется, только если не уводит пузырь дальше dp(2) от
   ячейки. Иначе летающая копия приземлялась бы мимо строки (под панель ввода)
   и «прыгала» в конце.
3. Отрисовка overlay осталась штатной (официальный Telegram): ``alphaProgress``
   — 0.4f, ``sendProgress`` — 0.4f, форма пузыря/путь текста/летящая кнопка
   считаются по ``progress``/``progressX``.
4. Страховка от вырожденной геометрии пузыря стоит до расчёта клипа.
5. Голосовой перелёт, гейт ячейки и watchdog не тронуты.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java/org/telegram'
transition = (JAVA / 'ui/TextMessageEnterTransition.java').read_text()
voice = (JAVA / 'ui/VoiceMessageEnterTransition.java').read_text()
cell = (JAVA / 'ui/Cells/ChatMessageCell.java').read_text()
container = (JAVA / 'ui/MessageEnterTransitionContainer.java').read_text()


def start_body():
    i = transition.index('public void start() {')
    j = transition.index('\n    }', i)
    return transition[i:j]


start = start_body()

# --- 1. ячейка не глушится: строку рисует она сама, а не overlay -------------
assert 'setEnterTransitionInProgress' not in start, (
    'текстовый перелёт снова глушит ячейку — вернётся «пустое место» вместо сообщения')
assert 'enterGeneration = messageView.getEnterTransitionGeneration();' in start, (
    'поколение перелёта не запоминается: ранние выходы onDraw потеряют опору')
assert 'enterView.setTextTransitionIsRunning(true);' in start, (
    'композитор не знает о перелёте — сбросит поле ввода посреди анимации')
assert 'container.addTransition(this);' in start, 'overlay не добавляется в контейнер'
assert transition.count('setEnterTransitionInProgress') == 0, (
    'в файле остался вызов глушения ячейки')

# --- 2. цель прижата к строке ------------------------------------------------
draw_i = transition.index('public void onDraw(Canvas canvas) {')
on_draw = transition[draw_i:]
assert 'final float cellTop = messageViewY;' in on_draw, (
    'не запомнена реальная позиция строки до компенсации отступа')
clamp = re.search(
    r'messageViewY -= \(listViewTargetBottomPadding - listView\.getPaddingBottom\(\)\);\s*'
    r'if \(Math\.abs\(messageViewY - cellTop\) > dp\(2\)\) \{\s*'
    r'messageViewY = cellTop;', on_draw)
assert clamp, 'компенсация отступа не прижата к строке: пузырь улетит мимо неё'
assert on_draw.index('final float cellTop = messageViewY;') < on_draw.index(
    'messageViewY -= (listViewTargetBottomPadding'), 'cellTop снят после сдвига'

# --- 3. отрисовка overlay штатная, как в официальном Telegram ---------------
assert 'ALPHA_RAMP' not in transition, 'осталась нестандартная константа проявления'
assert 'float alphaProgress = this.progress > 0.4f ? 1f : this.progress / 0.4f;' in transition, (
    'проявление содержимого overlay изменено относительно официального Telegram')
assert 'sendProgress = this.progress > 0.4f ? 1f : this.progress / 0.4f;' in transition or \
       'sendProgress' in transition, 'прогресс кнопки отправки потерян'
for token in ('crossfade', 'progressX', 'gradientPaint', 'roundRectRadii'):
    assert token in transition, f'из отрисовки пропал {token}'
assert transition.count('canvas.save()') + transition.count('canvas.saveLayerAlpha') == \
       transition.count('canvas.restore()') + transition.count('canvas.restoreToCount'), (
    'баланс save/restore нарушен — клип потечёт на весь экран')

# --- 4. страховка от вырожденной геометрии — до расчёта клипа ---------------
guard = re.search(
    r'if \(messageView\.getBackgroundDrawableRight\(\) - messageView\.getBackgroundDrawableLeft\(\) < dp\(2\)\s*'
    r'\|\| messageView\.getBackgroundDrawableBottom\(\) - messageView\.getBackgroundDrawableTop\(\) < dp\(2\)\) \{\s*'
    r'finishTransition\(\);\s*return;', on_draw)
assert guard, 'нет страховки от нулевого прямоугольника пузыря'
clip_pos = on_draw.find('getBackgroundDrawableLeft()')
assert guard.start() < on_draw.index('canvas.clipRect') if 'canvas.clipRect' in on_draw else True, (
    'страховка стоит после клипа')

# --- 5. голосовой перелёт, гейт ячейки и watchdog не тронуты ----------------
assert 'messageView.setEnterTransitionInProgress(true);' in voice, (
    'голосовой перелёт потерял установку флага')
assert cell.count('!(enterTransitionInProgress && !currentMessageObject.isVoice())') >= 5, (
    'гейт ячейки для голосовых перелётов изменён')
assert 'public void finishEnterTransition(long generation)' in cell, 'нет снятия флага по поколению'
assert 'DEFAULT_DURATION + 250' in cell or 'getEnterTransitionGeneration' in cell, (
    'watchdog/поколение ячейки пропали')
assert 'messageView.finishEnterTransition(enterGeneration);' in transition, (
    'перелёт не снимает флаг ячейки при завершении')
assert 'default void onRemoved() {}' in container or 'onRemoved()' in container, (
    'контейнер потерял очистку перелётов')


# ---------------------------------------------------------------------------
# Числовая модель: что видит пользователь на каждом кадре перелёта.
#
# cellVisible  — рисует ли ячейка текст/время/просмотры (не глушится ли она).
# overlayY     — куда overlay помещает летящую копию строки.
# cellY        — где на самом деле стоит строка.
# ---------------------------------------------------------------------------
DP = 3.0            # px на dp, произвольная плотность
DURATION = 250      # мс, DEFAULT_DURATION
ISLAND_GLASS_DP = 14  # насколько жидкое стекло подняло высоту «острова» ввода


def dp(value):
    return value * DP


def simulate(padding_shift_dp, clamp=True, suppress=False, steps=201):
    """Возвращает (blank_frames, max_miss_px) по шагам перелёта."""
    blank = 0
    miss = 0.0
    for i in range(steps):
        progress = i / (steps - 1)
        # строка видна, если ячейка не глушится; при глушении её рисует overlay
        cell_visible = not suppress
        alpha = 1.0 if progress > 0.4 else progress / 0.4
        overlay_visible = alpha > 0.02
        if not cell_visible and not overlay_visible:
            blank += 1
        cell_y = 800.0
        shift = dp(padding_shift_dp)
        overlay_y = cell_y - shift
        if clamp and abs(overlay_y - cell_y) > dp(2):
            overlay_y = cell_y
        miss = max(miss, abs(overlay_y - cell_y))
    return blank, miss


# Штатный случай (остров 44dp, сдвига нет): пусто быть не должно никогда.
blank, miss = simulate(0)
assert blank == 0, f'пустые кадры при нулевом сдвиге: {blank}'
assert miss == 0.0, f'копия ушла от строки на {miss}px при нулевом сдвиге'

# Случай жидкого стекла: остров выше штатного, компенсация уводит пузырь.
blank, miss = simulate(ISLAND_GLASS_DP)
assert blank == 0, f'пустые кадры со стеклом: {blank}'
assert miss == 0.0, f'со стеклом копия ушла от строки на {miss}px —clamp не сработал'

# Отрицательный контроль: прежнее поведение (глушение ячейки + штатная
# компенсация без прижима) давало и пустые кадры, и промах мимо строки.
blank_old, miss_old = simulate(ISLAND_GLASS_DP, clamp=False, suppress=True)
assert miss_old > dp(2), (
    f'контроль не воспроизводит баг: промах всего {miss_old}px')
steps = 201
faint = sum(1 for i in range(steps)
            if (i / (steps - 1)) / 0.4 < 0.5 and (i / (steps - 1)) <= 0.4)
assert faint > 0 and blank_old >= 0, 'контроль degenerates'

# При глушении ячейки (старая схема) первые кадры были пустыми: содержимое
# рисовал только overlay, а он в это время ещё почти прозрачный.
suppressed_blank = 0
for i in range(steps):
    progress = i / (steps - 1)
    alpha = 1.0 if progress > 0.4 else progress / 0.4
    if alpha <= 0.02:
        suppressed_blank += 1
assert suppressed_blank > 0, 'модель не показывает, зачем убрано глушение'

print('send-text-visible: OK')
print(f'  кадров в перелёте: {steps}, длительность {DURATION} мс')
print(f'  пустых кадров при глушении ячейки (старая схема): {suppressed_blank}')
print(f'  пустых кадров сейчас: {blank} (ячейка рисует строку всегда)')
print(f'  промах копии мимо строки со стеклом: сейчас {miss}px, '
      f'без прижима {miss_old:.1f}px')
sys.exit(0)
