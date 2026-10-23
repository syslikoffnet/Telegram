#!/usr/bin/env python3
"""Send overlay must not inherit RecyclerView's visible-block culling.

The cell suppresses its own text while the overlay owns it. A viewport range of
(-1, -1), or a range left over from another layout, must not suppress the overlay
as well. Exercise the production forwarding method and production range selector
on the JVM with a counting renderer. This is not Android/pixel instrumentation.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
cell = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java').read_text()
enter = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/TextMessageEnterTransition.java').read_text()
start = cell.index('public void drawMessageText(Canvas canvas, ArrayList<MessageObject.TextLayoutBlock>')
wrapper = cell[start:cell.index('\n    }', start) + 6]
selector_start = cell.index('        int firstVisibleBlockNum;', start)
selector = cell[selector_start:cell.index('\n        final TranslateController', selector_start)]
alpha_start = cell.rfind('        if (alpha == 0) {', start, selector_start)
assert alpha_start >= 0
alpha_guard = cell[alpha_start:cell.index('\n        }\n', alpha_start) + 10]
assert '&& !drawOnlyText' in alpha_guard, 'explicit overlay alpha=0 must not become opaque'
# Как в официальном Telegram: на время перелёта ячейка глушится, содержимое
# строки рисует overlay официальной парой копий. Поэтому overlay обязан
# рисовать ВСЕ блоки текста, а не диапазон, оставшийся от ячейки, — иначе
# вместе с глушением вернётся пустая строка (исторический баг
# overlay_missing_text).
assert 'messageView.setEnterTransitionInProgress(true);' in enter, (
    'cell suppression missing: overlay and cell would both paint the text')
# Летящий текст = официальная пара копий: полевая (drawFieldCopy = true)
# непрозрачна с первого кадра при обычной отправке — как в master-сборке,
# которую пользователь подтвердил; строковая — только при crossfade с
# официальной альфой alphaProgress (поле уходит 1→0, строка входит 0→1).
assert 'messageView.drawMessageText(canvas, messageView.getMessageObject().textLayoutBlocks, true, alphaProgress, true)' in enter
assert 'messageView.drawAnimatedEmojis(canvas, alphaProgress);' in enter
assert 'final boolean drawFieldCopy = true;' in enter
# run399: кроссфейд поля гасит через paint-альфу (официальный приём
# rtl-ветви), без saveLayerAlpha — слой на устройстве пикселей не давал;
# кадровый лог FIELD показывает длину текста, alpha paint и fade.
assert 'layout.getPaint().setAlpha((int) (oldAlpha * (1f - alphaProgress)));' in enter, (
    'field crossfade must fade via official paint-alpha technique')
assert 'saveLayerAlpha(0,0,layout.getWidth()' not in enter, (
    'field crossfade went back to saveLayerAlpha — device showed no pixels (run399)')
assert 'PengramSendTrace.log(\"FIELD\"' in enter, (
    'no per-frame FIELD trace — device log cannot prove the field copy painted')
assert 'origin, alpha, drawOnlyText, drawOnlyText, false);' in wrapper, (
    'overlay_missing_text: drawOnlyText overlay still uses the cell viewport range')
emoji_start = cell.index('private void drawAnimatedEmojiMessageText(Canvas canvas, float alpha)')
emoji_wrapper = cell[emoji_start:cell.index('\n    }', emoji_start) + 6]
assert emoji_wrapper.count('currentMessageObject.textXOffset, enterTransitionInProgress);') == 3, 'animated emoji overlay still inherits the cell viewport'
emoji_selector_start = cell.index('        int firstVisibleBlockNum;', emoji_start)
emoji_selector = cell[emoji_selector_start:cell.index('\n        float right;', emoji_selector_start)]
assert emoji_selector.strip() == selector.strip(), 'emoji/text range selection diverged; extend runtime harness before changing it'
print('Send overlay forwarding source contract PASS')

if not shutil.which('javac') or not shutil.which('java'):
    print('Production forwarding/range JVM runner SKIP: no local JDK; not a pixel guarantee')
else:
    harness = '''import java.util.ArrayList;
public class ViewportHarness {
 static class Canvas { }
 static class MessageObject {
  static class TextLayoutBlock { }
  float textXOffset;
 }
 static class Params { boolean animateTextY; float animateFromTextY, animateChangeProgress; ArrayList<MessageObject.TextLayoutBlock> animateOutTextBlocks; }
 Params transitionParams = new Params();
 MessageObject currentMessageObject = new MessageObject();
 float textX, textY; boolean fullyDraw;
 int firstVisibleBlockNum, lastVisibleBlockNum, rendered;
 WRAPPER
 void drawMessageText(float x, float y, Canvas canvas,
   ArrayList<MessageObject.TextLayoutBlock> textLayoutBlocks, float rtlOffset,
   boolean origin, float alpha, boolean drawAllBlocks, boolean drawOnlyText, boolean caption) {
 boolean explanation = false;
 ALPHA_GUARD
 SELECTOR
  if (firstVisibleBlockNum >= 0) {
   for (int a = firstVisibleBlockNum; a <= lastVisibleBlockNum && a < textLayoutBlocks.size(); a++) rendered++;
  }
 }
 static void check(boolean origin, boolean overlay, int first, int last, int count, boolean full, int expected) {
  ViewportHarness cell = new ViewportHarness();
  cell.firstVisibleBlockNum = first; cell.lastVisibleBlockNum = last; cell.fullyDraw = full;
  ArrayList<MessageObject.TextLayoutBlock> blocks = new ArrayList<>();
  for (int i = 0; i < count; i++) blocks.add(new MessageObject.TextLayoutBlock());
  cell.drawMessageText(new Canvas(), blocks, origin, 1f, overlay);
  if (cell.rendered != expected) throw new AssertionError("overlay_missing_text: origin="+origin+" overlay="+overlay+" range="+first+":"+last+" expected="+expected+" rendered="+cell.rendered);
 }
 static void checkAlpha(boolean overlay, int expected) {
  ViewportHarness cell = new ViewportHarness();
  cell.firstVisibleBlockNum = 0; cell.lastVisibleBlockNum = 0;
  ArrayList<MessageObject.TextLayoutBlock> blocks = new ArrayList<>();
  blocks.add(new MessageObject.TextLayoutBlock());
  cell.drawMessageText(new Canvas(), blocks, true, 0f, overlay);
  if (cell.rendered != expected) throw new AssertionError("overlay_alpha_zero: overlay="+overlay+" rendered="+cell.rendered);
 }
 public static void main(String[] args) {
  check(true, true, -1, -1, 1, false, 1);
  check(true, true, 3, 3, 1, false, 1);
  check(true, true, -1, -1, 3, false, 3);
  check(true, true, 0, 0, 3, false, 3);
  check(true, false, -1, -1, 1, false, 0);
  check(true, false, 1, 2, 3, false, 2);
  check(true, false, 0, 0, 1, false, 1);
  check(false, false, -1, -1, 1, false, 1);
  check(true, false, -1, -1, 3, true, 3);
  checkAlpha(true, 0);
  checkAlpha(false, 1);
  if (org.telegram.messenger.PengramSendTrace.calls == 0) throw new AssertionError("trace_stub_unused: диагностика не попала в харнесс");
  System.out.println("Production send forwarding/range/alpha: 11 cases PASS (counting renderer, not Android)");
 }
}'''.replace('WRAPPER', wrapper).replace('SELECTOR', selector).replace('ALPHA_GUARD', alpha_guard)
    legacy = harness.replace('ViewportHarness', 'ViewportLegacyHarness').replace(
        'origin, alpha, drawOnlyText, drawOnlyText, false);',
        'origin, alpha, false, drawOnlyText, false);')
    assert legacy != harness.replace('ViewportHarness', 'ViewportLegacyHarness')
    legacy_alpha = harness.replace('ViewportHarness', 'ViewportAlphaLegacyHarness').replace(' && !drawOnlyText', '')
    # Продакшн-код встраивается в харнесс дословно, включая трассировку отправки,
    # поэтому харнессу нужен org.telegram.messenger.PengramSendTrace. Заглушка
    # отвечает enabled()=true и считает вызовы: так проверяется, что диагностика
    # инертна и не меняет ни диапазон блоков, ни альфу.
    trace_stub = '''package org.telegram.messenger;

public final class PengramSendTrace {
    public static int calls;

    public static boolean enabled() {
        return true;
    }

    public static void log(String tag, String detail) {
        calls++;
    }

    public static void log(String tag, String key, Object value) {
        calls++;
    }
}
'''
    with tempfile.TemporaryDirectory(prefix='pengram-send-draw-') as tmp:
        tmp = Path(tmp)
        (tmp / 'org/telegram/messenger').mkdir(parents=True)
        (tmp / 'org/telegram/messenger/PengramSendTrace.java').write_text(trace_stub)
        (tmp/'ViewportHarness.java').write_text(harness)
        (tmp/'ViewportLegacyHarness.java').write_text(legacy)
        (tmp/'ViewportAlphaLegacyHarness.java').write_text(legacy_alpha)
        subprocess.run(['javac', 'org/telegram/messenger/PengramSendTrace.java',
                        'ViewportHarness.java', 'ViewportLegacyHarness.java',
                        'ViewportAlphaLegacyHarness.java'], cwd=tmp, check=True)
        red = subprocess.run(['java', '-cp', str(tmp), 'ViewportLegacyHarness'], capture_output=True, text=True)
        assert red.returncode != 0 and 'overlay_missing_text' in red.stderr, red.stdout + red.stderr
        print('Historical viewport-culling negative control: rejected as expected')
        red_alpha = subprocess.run(['java', '-cp', str(tmp), 'ViewportAlphaLegacyHarness'], capture_output=True, text=True)
        assert red_alpha.returncode != 0 and 'overlay_alpha_zero' in red_alpha.stderr, red_alpha.stdout + red_alpha.stderr
        print('Historical forced-alpha negative control: rejected as expected')
        subprocess.run(['java', '-cp', str(tmp), 'ViewportHarness'], cwd=tmp, check=True)
