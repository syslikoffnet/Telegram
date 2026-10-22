#!/usr/bin/env python3
"""Отправленное сообщение не должно стоять пустым, пока текст «не появится».

Симптом пользователя: после отправки пузырь виден сразу, а текста в нём нет
 ~0.1–0.5 с, затем он возникает рывком.

Механизм (подтверждён по исходникам, не по скриншотам):
  1. ChatListItemAnimator.animateAddImpl создавал TextMessageEnterTransition и
     вызывал start();
  2. start() взводил ChatMessageCell.enterTransitionInProgress, и ячейка
     переставала рисовать собственный текст (ChatMessageCell: `if
     (!enterTransitionInProgress ...) drawMessageText(canvas)`);
  3. текст вместо ячейки рисовал overlay, у которого и пузырь, и текст сообщения
     идут с alphaProgress = progress / 0.4 при DEFAULT_DURATION = 250 мс, то есть
     первые ~100 мс сообщение пустое, а потом текст «вспыхивает»;
  4. если overlay не создавался (поле ввода уже очищено — конструктор выходит по
     TextUtils.isEmpty) или завершался досрочно (смена stableId / rebinding /
     generation), пустой пузырь жил до конца анимации, а страховка добивала флаг
     только через DEFAULT_DURATION + 250 = 500 мс.

Проверки ниже: source-контракты + исполнение НАСТОЯЩЕГО production
animateAddImpl на JVM с фейковым Android (не пиксели и не устройство).
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java/org/telegram'
animator_src = (JAVA / 'ui/recyclerview/ChatListItemAnimator.java').read_text()
cell = (JAVA / 'ui/Cells/ChatMessageCell.java').read_text()
enter = (JAVA / 'ui/TextMessageEnterTransition.java').read_text()
composer = (JAVA / 'ui/Components/ChatActivityEnterView.java').read_text()


def method(source, name):
    match = re.search(r'(?:(?:public|private|protected)\s+)?(?:static\s+)?[\w<>]+\s+' + name + r'\([^{};]*\)\s*\{', source)
    assert match, name
    depth, end = 1, match.end()
    while depth:
        if source[end] == '{':
            depth += 1
        if source[end] == '}':
            depth -= 1
        end += 1
    return source[match.start():end]


add_impl = method(animator_src, 'animateAddImpl')

# 1. Overlay перелёта текста больше не создаётся: именно он прятал текст ячейки.
assert 'new TextMessageEnterTransition(' not in add_impl, (
    'blank_bubble: send overlay снова создаётся — ячейка опять спрячет свой текст')
assert 'import org.telegram.ui.TextMessageEnterTransition;' in animator_src, (
    'класс оставлен обратимым: import и класс на месте, отключение в одну строку')

# 2. Голосовой перелёт не задет: у VoiceMessageEnterTransition свой текст.
assert 'new VoiceMessageEnterTransition(' in add_impl, 'voice transition lost'

# 3. Поле ввода по-прежнему сбрасывается сразу, а не через 200 мс runnable.
assert 'startMessageTransition();' in add_impl, 'composer must reset immediately after send'

# 4. Штатное появление строки (translationY -> 0) сохранено.
assert 'animation.translationY(0)' in add_impl, 'row enter animation lost'

# 5. Ячейка рисует текст, пока никто не владеет перелётом: контракт на месте.
assert 'if (!enterTransitionInProgress && currentMessageObject != null && !currentMessageObject.preview) {' in cell
assert 'postDelayed(pengramEnterWatchdog' in method(cell, 'setEnterTransitionInProgress'), (
    'страховка от зависшего владения текстом должна остаться')

# 6. Механизм пустого пузыря задокументирован там, где его отключили, и в самом
#    overlay: alpha пузыря и текста — progress/0.4 от 250 мс.
assert 'float alphaProgress = this.progress > 0.4f ? 1f : this.progress / 0.4f;' in enter
assert 'drawable.setAlpha((int) (255 * alphaProgress));' in enter
assert 'messageView.setEnterTransitionInProgress(true)' in method(enter, 'start')
assert 'DEFAULT_DURATION = 250' in animator_src
assert 'Pengram: перелёт текста из поля ввода в пузырь отключён' in add_impl
assert 'drawChild' in composer and 'child == sendButton && textTransitionIsRunning' in composer
print('Send text blank-bubble source contracts PASS')

if not shutil.which('javac') or not shutil.which('java'):
    print('animateAddImpl JVM runner SKIP: no local JDK; model is not Android proof')
    raise SystemExit(0)

HARNESS = r'''import java.util.ArrayList;

public class SendAddHarness {
    static class View {
        float translationY, scaleX = 1f, scaleY = 1f, alpha = 1f;
        int measuredHeight = 60;
        int getMeasuredHeight() { return measuredHeight; }
        void setTranslationY(float v) { translationY = v; }
        float getTranslationY() { return translationY; }
        void setScaleX(float v) { scaleX = v; }
        void setScaleY(float v) { scaleY = v; }
        void setAlpha(float v) { alpha = v; }
        float getAlpha() { return alpha; }
        ViewPropertyAnimator animate() { return new ViewPropertyAnimator(); }
    }
    static class ViewPropertyAnimator {
        ViewPropertyAnimator translationY(float v) { return this; }
        ViewPropertyAnimator setDuration(long d) { return this; }
        ViewPropertyAnimator setInterpolator(Object i) { return this; }
        ViewPropertyAnimator setListener(Object l) { return this; }
        void start() { }
    }
    static class Animator { }
    static class AnimatorListenerAdapter {
        void onAnimationStart(Animator animator) { }
        void onAnimationCancel(Animator animator) { }
        void onAnimationEnd(Animator animator) { }
    }
    static class MessageObject {
        boolean voice;
        boolean isVoice() { return voice; }
    }
    static class TransitionParams { boolean ignoreAlpha, messageEntering; }
    static class ChatMessageCell extends View {
        ChatMessageCell() { measuredHeight = 60; }
        MessageObject messageObject = new MessageObject();
        TransitionParams transitionParams = new TransitionParams();
        MessageObject getMessageObject() { return messageObject; }
        TransitionParams getTransitionParams() { return transitionParams; }
    }
    static class ViewHolder { View itemView; }
    // production-сигнатура animateAddImpl ссылается на RecyclerView.ViewHolder
    static class RecyclerView { static class ViewHolder extends SendAddHarness.ViewHolder { } }
    static class RecyclerListView extends View {
        int measuredHeight = 1000;
        int getMeasuredHeight() { return measuredHeight; }
    }
    static class TextMessageEnterTransition {
        static int created;
        TextMessageEnterTransition(Object a, Object b, Object c, Object d, Object e) { created++; }
        void start() { started++; }
        static int started;
    }
    static class VoiceMessageEnterTransition {
        static int created;
        VoiceMessageEnterTransition(Object a, Object b, Object c, Object d, Object e) { created++; }
        void start() { started++; }
        static int started;
    }
    static class EnterTransitionContainer { }
    static class EnterView {
        boolean canShow = true, started;
        boolean canShowMessageTransition() { return canShow; }
        void startMessageTransition() { started = true; }
    }
    static class Activity {
        ArrayList<MessageObject> animatingMessageObjects = new ArrayList<>();
        EnterView enterView = new EnterView();
        EnterTransitionContainer messageEnterTransitionContainer = new EnterTransitionContainer();
        EnterView getChatActivityEnterView() { return enterView; }
        void onListItemAnimatorTick() { }
    }
    static class SharedConfig {
        static final int PERFORMANCE_CLASS_LOW = 0, PERFORMANCE_CLASS_HIGH = 2;
        static int performanceClass = PERFORMANCE_CLASS_HIGH;
        static int getDevicePerformanceClass() { return performanceClass; }
    }
    final ArrayList<ViewHolder> mAddAnimations = new ArrayList<>();
    Activity activity = new Activity();
    RecyclerListView recyclerListView = new RecyclerListView();
    Object resourcesProvider = new Object();
    Object translationInterpolator = new Object();
    int dispatchStarting, dispatchFinished, dispatchAll;
    boolean shouldAnimateEnterFromBottom = true;
    long getMoveDuration() { return 250; }
    void dispatchAddStarting(ViewHolder h) { dispatchStarting++; }
    void dispatchAddFinished(ViewHolder h) { dispatchFinished++; }
    void dispatchFinishedWhenDone() { dispatchAll++; }

METHOD

    static RecyclerView.ViewHolder newHolder(boolean voice, float translationY, boolean ignoreAlpha,
                                             int cellHeight) {
        ChatMessageCell cell = new ChatMessageCell();
        cell.messageObject.voice = voice;
        cell.translationY = translationY;
        cell.measuredHeight = cellHeight;
        cell.transitionParams.ignoreAlpha = ignoreAlpha;
        RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder();
        holder.itemView = cell;
        return holder;
    }

    static void check(String name, boolean voice, float translationY, int cellHeight, int listHeight,
                      int performanceClass, boolean canShow, boolean animating,
                      int expectedText, int expectedVoice, boolean expectedComposerReset) {
        TextMessageEnterTransition.created = 0;
        TextMessageEnterTransition.started = 0;
        VoiceMessageEnterTransition.created = 0;
        VoiceMessageEnterTransition.started = 0;
        SharedConfig.performanceClass = performanceClass;
        SendAddHarness animator = new SendAddHarness();
        animator.recyclerListView.measuredHeight = listHeight;
        animator.activity.enterView.canShow = canShow;
        RecyclerView.ViewHolder holder = newHolder(voice, translationY, false, cellHeight);
        if (animating) animator.activity.animatingMessageObjects.add(holder.itemView instanceof ChatMessageCell
                ? ((ChatMessageCell) holder.itemView).messageObject : null);
        animator.animateAddImpl(holder, 0);
        if (TextMessageEnterTransition.created != expectedText || TextMessageEnterTransition.started != expectedText) {
            throw new AssertionError("blank_bubble: " + name + " text overlay created="
                    + TextMessageEnterTransition.created + " started=" + TextMessageEnterTransition.started
                    + " expected=" + expectedText);
        }
        if (VoiceMessageEnterTransition.created != expectedVoice) {
            throw new AssertionError("voice_transition_lost: " + name + " created="
                    + VoiceMessageEnterTransition.created + " expected=" + expectedVoice);
        }
        if (animator.activity.enterView.started != expectedComposerReset) {
            throw new AssertionError("composer_reset: " + name + " started=" + animator.activity.enterView.started);
        }
        if (holder.itemView.getAlpha() != 1f) {
            throw new AssertionError("cell_alpha: " + name + " alpha=" + holder.itemView.getAlpha());
        }
        if (!animator.mAddAnimations.contains(holder) || animator.dispatchStarting != 0) {
            throw new AssertionError("row_animation: " + name);
        }
    }

    public static void main(String[] args) {
        // Отправленный текст: overlay не создаётся ни на мощном, ни на слабом
        // устройстве, ни при какой высоте ячейки и списка.
        check("text_high_near", false, 10f, 60, 1000, 2, true, true, 0, 0, true);
        check("text_high_far", false, 900f, 60, 1000, 2, true, true, 0, 0, true);
        check("text_low", false, 10f, 60, 1000, 0, true, true, 0, 0, true);
        check("text_emoji_only", false, 0f, 60, 1000, 2, true, true, 0, 0, true);
        // Сообщение не из отправки — ничего не запускается, поле ввода не трогаем.
        check("text_not_animating", false, 10f, 60, 1000, 2, true, false, 0, 0, false);
        check("text_transition_denied", false, 10f, 60, 1000, 2, false, true, 0, 0, false);
        // Голосовой перелёт сохранён. Важно: animateAddImpl сам делает
        // view.setTranslationY(addedItemsHeight) ДО проверки
        // Math.abs(getTranslationY()) < getMeasuredHeight() * 3f, поэтому
        // «далеко» здесь — это не измеренная ячейка (порог 0), а не смещение
        // строки. Штатное поведение не меняем, только документируем.
        check("voice_measured", true, 10f, 60, 1000, 2, true, true, 0, 1, true);
        check("voice_translation_reset", true, 900f, 60, 1000, 2, true, true, 0, 1, true);
        check("voice_unmeasured", true, 900f, 0, 1000, 2, true, true, 0, 0, true);
        System.out.println("Production animateAddImpl: 9 cases PASS (fake Android, not a device)");
    }
}'''.replace('METHOD', add_impl)

with tempfile.TemporaryDirectory(prefix='pengram-send-blank-') as tmp:
    tmp = Path(tmp)
    (tmp / 'SendAddHarness.java').write_text(HARNESS)
    # Отрицательный контроль 1: вернуть overlay — тест обязан упасть.
    legacy = HARNESS.replace(
        'animator.animateAddImpl(holder, 0);',
        'animator.animateAddImpl(holder, 0);\n        if (!voice && SharedConfig.getDevicePerformanceClass() != 0'
        ' && Math.abs(translationY) < listHeight && canShow && animating) {'
        ' new TextMessageEnterTransition(null, null, null, null, null).start(); }')
    assert legacy != HARNESS
    (tmp / 'SendAddLegacyHarness.java').write_text(legacy.replace('SendAddHarness', 'SendAddLegacyHarness'))
    # Отрицательный контроль 2: потерять голосовой перелёт — тест обязан упасть.
    no_voice = HARNESS.replace('chatMessageCell.getMessageObject().isVoice()', 'false')
    assert no_voice != HARNESS, 'voice branch marker not found; update the harness before changing it'
    (tmp / 'SendAddNoVoiceHarness.java').write_text(no_voice.replace('SendAddHarness', 'SendAddNoVoiceHarness'))
    subprocess.run(['javac', '-encoding', 'UTF-8', 'SendAddHarness.java', 'SendAddLegacyHarness.java',
                    'SendAddNoVoiceHarness.java'], cwd=tmp, check=True)
    for name, marker in (('SendAddLegacyHarness', 'blank_bubble'), ('SendAddNoVoiceHarness', 'voice_transition_lost')):
        result = subprocess.run(['java', '-cp', str(tmp), name], capture_output=True, text=True)
        assert result.returncode != 0 and marker in result.stderr, name + ': ' + result.stdout + result.stderr
        print('Negative control ' + name + ' rejected as expected (' + marker + ')')
    subprocess.run(['java', '-cp', str(tmp), 'SendAddHarness'], cwd=tmp, check=True)
