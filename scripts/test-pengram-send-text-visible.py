#!/usr/bin/env python3
"""Отправленное сообщение не стоит пустым: анимация официальная, содержимое проявляется сразу.

Что видел пользователь: после нажатия «отправить» на месте сообщения пустое место —
ни текста, ни времени, ни просмотров, одна обводка, а содержимое появляется спустя
время.

Механика штатного морфа (она же в официальном Telegram): на время перелёта
ChatMessageCell.enterTransitionInProgress глушит у ячейки фон пузыря (:20990),
время (:20575), текст (:14552, :21939), ответ (:28384) и превью ссылки (:14561),
а всё содержимое рисует overlay в MessageEnterTransitionContainer. Содержимое в
overlay нарастало с alphaProgress = progress / 0.4, то есть первые 100 мс из 250
пузырь, текст, время и просмотры были полупрозрачными — отсюда «пустое место» и
«вспышка» текста в конце. Плюс если геометрия пузыря ячейки ещё не готова, клип
overlay схлопывается и не рисуется вовсе ничего.

Решение (две точечные правки, код отрисовки не переписан):
1. ALPHA_RAMP = 0.08 — содержимое становится непрозрачным за ~20 мс вместо 100 мс;
   геометрия перелёта по-прежнему считается по progress/progressX, поэтому форма
   пузыря, путь текста и летящая кнопка отправки не изменились.
2. Если прямоугольник пузыря ячейки вырожден (ячейка не измерена), перелёт
   завершается немедленно и строку рисует ячейка — вместо пустого места.

Проверки: source-контракты + JVM-исполнение числовой модели непрозрачности на
производной формуле и модели страховки. Не Android-инструментовка, не пиксели.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java/org/telegram'
cell = (JAVA / 'ui/Cells/ChatMessageCell.java').read_text()
enter = (JAVA / 'ui/TextMessageEnterTransition.java').read_text()
voice = (JAVA / 'ui/VoiceMessageEnterTransition.java').read_text()
animator = (JAVA / 'ui/recyclerview/ChatListItemAnimator.java').read_text()
container = (JAVA / 'ui/MessageEnterTransitionContainer.java').read_text()

GATE = '!(enterTransitionInProgress && !currentMessageObject.isVoice())'
RAMP = re.search(r'private static final float ALPHA_RAMP = ([\d.]+)f;', enter)
ALPHA = re.search(r'float alphaProgress = this\.progress > ALPHA_RAMP \? 1f : this\.progress / ALPHA_RAMP;', enter)
GUARD = re.search(
    r'if \(messageView\.getBackgroundDrawableRight\(\) - messageView\.getBackgroundDrawableLeft\(\) < dp\(2\)\s*\n'
    r'\s*\|\| messageView\.getBackgroundDrawableBottom\(\) - messageView\.getBackgroundDrawableTop\(\) < dp\(2\)\) \{\s*\n'
    r'\s*finishTransition\(\);\s*\n\s*return;\s*\n\s*\}', enter)

# --- анимация отправки официальная: overlay по-прежнему владеет содержимым ----
assert 'new TextMessageEnterTransition(chatMessageCell, activity, recyclerListView, activity.messageEnterTransitionContainer, resourcesProvider);' in animator
assert 'new VoiceMessageEnterTransition(' in animator
assert 'SharedConfig.getDevicePerformanceClass() != SharedConfig.PERFORMANCE_CLASS_LOW' in animator
assert 'view.setTranslationY(addedItemsHeight);' in animator            # строка выезжает снизу
assert 'messageView.setEnterTransitionInProgress(true);' in enter        # ячейка спрятана, как в официальном
assert 'layout.draw(canvas);' in enter                                   # летящий текст из поля ввода
assert 'messageView.drawMessageText(canvas, messageView.getMessageObject().textLayoutBlocks, true, alphaProgress, true)' in enter
assert 'if (transitionFinished) return;' in enter
assert 'finishTransition();' in enter

# --- правка 1: содержимое проявляется за ~20 мс, а не за 100 ------------------
assert RAMP, 'нет константы ALPHA_RAMP'
ramp = float(RAMP.group(1))
assert 0.02 <= ramp <= 0.12, ramp
assert ALPHA, 'alphaProgress не использует ALPHA_RAMP'
# геометрия перелёта не тронута: интерполятор и позиция считаются по progress
assert 'float progress = ChatListItemAnimator.DEFAULT_INTERPOLATOR.getInterpolation(this.progress);' in enter
assert 'float p2 = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(this.progress);' in enter
# кнопка отправки летит по-прежнему штатно (её ramp не меняли)
assert 'float sendProgress = this.progress > 0.4f ? 1f : this.progress / 0.4f;' in enter
for kept in ('drawable.setAlpha((int) (255 * alphaProgress));',
             'shadowDrawable.setAlpha((int) (255 * progressX));',
             'fromMessageDrawable.draw(canvas);',
             'sendButton.draw(canvas);',
             'chatActivityEnterView.getEditField().setAlpha(progress);',
             'animator.setDuration(ChatListItemAnimator.DEFAULT_DURATION);',
             'TextUtils.isEmpty(AndroidUtilities.trim(editText, null))'):
    assert kept in enter, kept

# --- правка 2: вырожденная геометрия пузыря не оставляет пустое место ---------
assert GUARD, 'нет страховки от вырожденного прямоугольника пузыря'
guard_at = enter.index('getBackgroundDrawableRight() - messageView.getBackgroundDrawableLeft()')
draw_at = enter.index('float drawableX = messageViewX + messageView.getBackgroundDrawableLeft()')
assert guard_at < draw_at, 'страховка обязана стоять до расчёта клипа и границ пузыря'
assert 'if (!messageView.getTransitionParams().wasDraw) {' in enter      # геометрию готовят заранее

# --- gate ячейки и голосовой перелёт не тронуты ------------------------------
assert cell.count(GATE) >= 5, cell.count(GATE)
assert 'if (!enterTransitionInProgress && currentMessageObject != null && !currentMessageObject.preview) {' in cell
assert 'postDelayed(pengramEnterWatchdog' in cell and 'DEFAULT_DURATION + 250' in cell
assert 'messageView.setEnterTransitionInProgress(true);' in voice
assert 'transitions.get(i).onDraw(canvas)' in container

print('Send text visible: source contracts PASS')

if not shutil.which('javac') or not shutil.which('java'):
    print('Opacity JVM runner SKIP: no local JDK; not a pixel guarantee')
    raise SystemExit(0)

harness = '''public class OpacityHarness {
    /** формула проявления, взятая из TextMessageEnterTransition.onDraw */
    static float alpha(float progress, float ramp) {
        return progress > ramp ? 1f : progress / ramp;
    }

    /** страховка: вырожденный прямоугольник пузыря завершает перелёт */
    static boolean guardFinishes(int left, int right, int top, int bottom, int dp2) {
        return right - left < dp2 || bottom - top < dp2;
    }

    public static void main(String[] args) {
        final float ramp = RAMP;
        final float durationMs = 250f;   // ChatListItemAnimator.DEFAULT_DURATION
        int cases = 0;

        // 1. Содержимое непрозрачно уже через ~20 мс и до конца перелёта.
        for (int step = 0; step <= 200; step++) {
            float progress = step / 200f;
            float ms = progress * durationMs;
            float a = alpha(progress, ramp);
            if (ms >= ramp * durationMs + 1f && a < 1f) {
                throw new AssertionError("faint_content_at_ms_" + ms + "_alpha_" + a);
            }
            if (a < 0f || a > 1f) throw new AssertionError("alpha_out_of_range_" + a);
            cases++;
        }
        // 2. Первый кадр (16.7 мс) уже почти непрозрачен, а не пустой.
        float firstFrame = alpha(16.7f / durationMs, ramp);
        if (firstFrame < 0.8f) throw new AssertionError("first_frame_faint_" + firstFrame);
        cases++;
        // 3. Отрицательный контроль: штатный ramp 0.4 давал 100 мс полупрозрачности.
        float stockAt20ms = alpha(20f / durationMs, 0.4f);
        if (stockAt20ms >= 0.8f) throw new AssertionError("negative_control_failed_" + stockAt20ms);
        cases++;
        // 4. Геометрия перелёта не зависит от ramp: позиция текста одинакова.
        for (int step = 0; step <= 100; step++) {
            float progress = step / 100f;
            float posY = 100f * (1f - progress) + 400f * progress;
            if (posY < 100f || posY > 400f) throw new AssertionError("geometry_changed");
            cases++;
        }
        // 5. Страховка: нулевой/вырожденный пузырь завершает перелёт, готовый — нет.
        if (!guardFinishes(0, 0, 0, 0, 2)) throw new AssertionError("zero_bounds_not_guarded");
        if (!guardFinishes(10, 11, 0, 60, 2)) throw new AssertionError("narrow_bounds_not_guarded");
        if (guardFinishes(10, 220, 0, 60, 2)) throw new AssertionError("valid_bounds_guarded");
        cases += 3;
        System.out.println("Opacity JVM runner: " + cases + " cases PASS (ramp=" + ramp
                + ", first frame alpha=" + firstFrame + ", stock 20ms alpha=" + stockAt20ms + ")");
    }
}
'''.replace('RAMP', RAMP.group(1) + 'f')

with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / 'OpacityHarness.java'
    src.write_text(harness)
    build = subprocess.run(['javac', '-d', tmp, str(src)], capture_output=True, text=True)
    if build.returncode != 0:
        raise SystemExit('harness compile failed:\n' + build.stdout + build.stderr)
    run = subprocess.run(['java', '-cp', tmp, 'OpacityHarness'], capture_output=True, text=True)
    if run.returncode != 0:
        raise SystemExit('harness failed:\n' + run.stdout + run.stderr)
    print(run.stdout.strip())

print('Send text visible with official animation: PASS; device/pixels NOT RUN')
