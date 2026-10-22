#!/usr/bin/env python3
"""Отправленный текст виден сразу: строку рисует ячейка, overlay даёт только анимацию.

История в двух шагах:

1. Штатный морф отправки Telegram взводил ChatMessageCell.enterTransitionInProgress,
   а этот флаг глушит у ячейки не только текст, но и фон пузыря, время, ответ,
   реакции и превью ссылки — `!(enterTransitionInProgress && !isVoice())`. Всё
   содержимое 250 мс рисовал overlay, пузырь и текст нарастали с
   `alphaProgress = progress / 0.4`. Пользователь видел: сообщение отправилось,
   текста нет, потом он появляется.
2. Первая попытка (не создавать overlay вовсе) убрала пустой пузырь, но вместе с
   ним и анимацию отправки — «вовсе нет анимации».

Текущее решение: ячейка не прячется и рисует себя сразу и полностью, а overlay
оставляет только анимацию — форму пузыря, летящую из поля ввода, тень, «призрак»
пузырька цвета поля ввода, затухание поля ввода и летящую кнопку отправки.
Содержимое в overlay не рисуется, поэтому второй копии текста в кадре нет.

Проверки: source-контракты + JVM-исполнение выражения gate, взятого из ячейки, и
флага overlay, взятого из перелёта: у текста ровно один владелец на каждом шаге
перелёта, голосовой перелёт свою механику сохранил, отрицательный контроль
показывает, что штатный вариант действительно дал бы пустую строку.
Это не Android-инструментовка и не гарантия пикселей.
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

GATE = '!(enterTransitionInProgress && !currentMessageObject.isVoice())'
TEXT_GATE = 'if (!enterTransitionInProgress && currentMessageObject != null && !currentMessageObject.preview) {'
FLAG = re.search(r'private static final boolean DRAW_MESSAGE_CONTENT_IN_OVERLAY = (true|false);', enter)

# --- анимация отправки на месте (штатный путь, штатные условия) ---------------
assert 'new TextMessageEnterTransition(chatMessageCell, activity, recyclerListView, activity.messageEnterTransitionContainer, resourcesProvider);' in animator
assert 'new VoiceMessageEnterTransition(chatMessageCell, activity.getChatActivityEnterView(), recyclerListView, activity.messageEnterTransitionContainer, resourcesProvider);' in animator
assert 'SharedConfig.getDevicePerformanceClass() != SharedConfig.PERFORMANCE_CLASS_LOW' in animator
assert 'activity.getChatActivityEnterView().startMessageTransition();' in animator
assert 'view.setTranslationY(addedItemsHeight);' in animator          # строка выезжает снизу
assert 'animation.translationY(0).setDuration(getMoveDuration())' in animator

# --- ячейка больше не прячется на время перелёта текста -----------------------
assert 'messageView.setEnterTransitionInProgress(true)' not in enter
assert FLAG and FLAG.group(1) == 'false', 'overlay снова рисует содержимое строки'
assert 'enterGeneration = messageView.getEnterTransitionGeneration();' in enter   # досрочный финиш жив
assert 'messageView.finishEnterTransition(enterGeneration)' in enter
assert 'messageView.setBackgroundTopY(true);' in enter                # геометрия пузыря возвращается ячейке

# --- содержимое в overlay закрыто флагом, анимация — нет ----------------------
for guard in ('if (DRAW_MESSAGE_CONTENT_IN_OVERLAY) {\n            messageView.drawTime(canvas, alphaProgress, false);',
              'if (hasReply && DRAW_MESSAGE_CONTENT_IN_OVERLAY) {',
              'if (rtlLayout != null && DRAW_MESSAGE_CONTENT_IN_OVERLAY) {',
              'if (crossfade && DRAW_MESSAGE_CONTENT_IN_OVERLAY) {',
              'if (!DRAW_MESSAGE_CONTENT_IN_OVERLAY) {'):
    assert guard in enter, guard
for kept in ('drawable.setAlpha((int) (255 * alphaProgress));',        # пузырь летит и проявляется
             'shadowDrawable.setAlpha((int) (255 * progressX));',
             'fromMessageDrawable.draw(canvas);',                      # призрак пузырька поля ввода
             'sendButton.draw(canvas);',                               # летящая кнопка отправки
             'chatActivityEnterView.getEditField().setAlpha(progress);',
             'animator.setDuration(ChatListItemAnimator.DEFAULT_DURATION);',
             'TextUtils.isEmpty(AndroidUtilities.trim(editText, null))'):   # пустое поле не владеет текстом
    assert kept in enter, kept
# код отрисовки содержимого сохранён под флагом — откат в одну правку
assert 'messageView.drawMessageText(canvas, messageView.getMessageObject().textLayoutBlocks, true, alphaProgress, true)' in enter

# --- gate ячейки не тронут: он нужен голосовому перелёту и превью -------------
assert cell.count(GATE) >= 5, cell.count(GATE)
assert TEXT_GATE in cell
assert 'postDelayed(pengramEnterWatchdog' in cell
assert 'DEFAULT_DURATION + 250' in cell
assert 'messageView.setEnterTransitionInProgress(true);' in voice     # голосовой перелёт владеет строкой сам

# Вырезание содержимого не имело права тронуть пары save/restore: saveLayerAlpha
# тоже закрывается restore(), поэтому сумма обоих обязана совпасть с restore().
on_draw = enter[enter.index('public void onDraw(Canvas canvas) {'):]
saves = on_draw.count('canvas.save()') + on_draw.count('canvas.saveLayerAlpha')
assert saves == on_draw.count('canvas.restore()'), \
    f'save/restore разбалансированы после вырезания содержимого: {saves} != {on_draw.count("canvas.restore()")}'

print('Send text visible: source contracts PASS')

if not shutil.which('javac') or not shutil.which('java'):
    print('Owner JVM runner SKIP: no local JDK; not a pixel guarantee')
    raise SystemExit(0)

harness = '''public class OwnerHarness {
    static class MessageObject {
        boolean voice;
        boolean isVoice() { return voice; }
    }
    static boolean enterTransitionInProgress;
    static MessageObject currentMessageObject;

    /** gate ячейки, взятый дословно из ChatMessageCell */
    static boolean cellDrawsContent() {
        return GATE;
    }

    /** флаг overlay, взятый дословно из TextMessageEnterTransition */
    static final boolean DRAW_MESSAGE_CONTENT_IN_OVERLAY = FLAG;

    public static void main(String[] args) {
        MessageObject text = new MessageObject();
        MessageObject voiceMsg = new MessageObject();
        voiceMsg.voice = true;

        int cases = 0;
        // Перелёт текста: start() больше не взводит флаг, поэтому он false все 250 мс.
        for (int step = 0; step <= 100; step++) {
            float progress = step / 100f;
            enterTransitionInProgress = false;
            currentMessageObject = text;
            boolean cell = cellDrawsContent();
            boolean overlay = DRAW_MESSAGE_CONTENT_IN_OVERLAY;
            if (!cell) throw new AssertionError("blank_bubble_at_progress_" + progress);
            if (cell && overlay) throw new AssertionError("double_text_at_progress_" + progress);
            cases++;
        }
        // Голосовой перелёт: флаг взведён, но gate обязан пропустить содержимое.
        enterTransitionInProgress = true;
        currentMessageObject = voiceMsg;
        if (!cellDrawsContent()) throw new AssertionError("voice_row_suppressed");
        cases++;
        // Отрицательный контроль: штатный вариант (флаг взведён для текста)
        // действительно давал пустую строку — значит проверка не бессмысленна.
        currentMessageObject = text;
        if (cellDrawsContent()) throw new AssertionError("negative_control_failed: stock suppression not modelled");
        cases++;
        System.out.println("Owner JVM runner: " + cases + " cases PASS (cell owns text, overlay animates only)");
    }
}
'''.replace('GATE', GATE).replace('FLAG', FLAG.group(1))

with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / 'OwnerHarness.java'
    src.write_text(harness)
    build = subprocess.run(['javac', '-d', tmp, str(src)], capture_output=True, text=True)
    if build.returncode != 0:
        raise SystemExit('harness compile failed:\n' + build.stdout + build.stderr)
    run = subprocess.run(['java', '-cp', tmp, 'OwnerHarness'], capture_output=True, text=True)
    if run.returncode != 0:
        raise SystemExit('harness failed:\n' + run.stdout + run.stderr)
    print(run.stdout.strip())

print('Send text visible with animation: PASS; device/pixels NOT RUN')
