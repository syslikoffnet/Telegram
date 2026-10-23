#!/usr/bin/env python3
"""Visibility contracts, original NFT failure model and extracted Java lifecycle runner.
Not Android instrumentation, IME verification, performance or a pixel guarantee.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java/org/telegram'
cell = (JAVA/'ui/Cells/ChatMessageCell.java').read_text()
msg = (JAVA/'messenger/MessageObject.java').read_text()
enter = (JAVA/'ui/TextMessageEnterTransition.java').read_text()
container = (JAVA/'ui/MessageEnterTransitionContainer.java').read_text()
typing = (JAVA/'ui/Components/PengramTypingEffects.java').read_text()
composer = (JAVA/'ui/Components/ChatActivityEnterView.java').read_text()

def method(source, name):
    match = re.search(r'(?:(?:public|private|protected)\s+)?(?:static\s+)?[\w<>]+\s+' + name + r'\([^{};]*\)\s*\{', source)
    assert match, name
    depth, end = 1, match.end()
    while depth:
        if source[end] == '{': depth += 1
        if source[end] == '}': depth -= 1
        end += 1
    return source[match.start():end]

setter = method(cell, 'setEnterTransitionInProgress')
assert 'postDelayed(pengramEnterWatchdog' in setter
assert 'DEFAULT_DURATION + 250' in setter
assert 'setEnterTransitionInProgress(false)' in method(cell, 'onDetachedFromWindow')
assert 'boolean lastInChatList) {\n        setEnterTransitionInProgress(false);' in cell
assert 'onAnimationCancel(Animator animation)' in enter
assert 'messageView.finishEnterTransition(enterGeneration)' in method(enter, 'finishTransition')
assert 'getEnterTransitionGeneration() != enterGeneration' in method(enter, 'onDraw')
assert 'transition.onRemoved()' in method(container, 'removeTransition')
assert 'removeTransition(' in method(container, 'onDetachedFromWindow')
assert 'pengramTextChangeWatchdog' in cell
assert '!lastDrawingTextBlocks.isEmpty()' in cell
assert '!transitionParams.animateOutTextBlocks.isEmpty()' in method(cell, 'drawMessageText')
assert 'currentMessageObject.pengramPlainTextBlocks' in cell
assert 'alpha = 1f;' in cell
layout = method(msg, 'generateLayout')
assert 'pengramPlainTextFallback();' in layout
assert 'cachedTextHeight = null' in layout
assert 'layoutCreated = true;' not in method(msg, 'checkLayout')
assert 'layoutCreated = pengramHasTextLayout()' in layout
assert 'block.textLayout == null || block.height <= 0' in method(msg, 'pengramHasTextLayout')
assert 'TextUtils.equals(newText, oldText)' in cell
assert 'new StaticLayout(plain,' in method(msg, 'pengramPlainTextFallback')
assert 'PengramAntiCrashBlocked' in method(msg, 'pengramPlainTextFallback')
assert 'messageText == pengramWrappedMessageText' in layout
assert 'messageText = pengramOriginalMessageText;' in layout
assert 'messageText = pengramWrappedMessageText = textLayout.getText();' in msg
assert msg.index('text = textLayout.getText();\n//') < msg.index('CharSequence blockText = text.subSequence(range.start, range.end)')
assert cell.count('textY += pengramPreviewTextOffset();') == 2
assert 'drawInstantView && !hasInvoicePreview' in method(cell, 'pengramPreviewTextOffset')
assert 'OnPreDrawListener' in typing and 'batch.attempts <= 3' in typing and '+ 400' in typing
assert 'matches(layout.getText(), batch.start, batch.end, batch.text)' in typing
assert 'captureReplacement' in composer and 's.invalidateEditedRange' in typing
assert 'if (before > 0 || s.overlapsInsertion(start))' not in typing
assert 'finally { if (suppress) { pengramExternalEditDepth--; isPaste = false; } }' in composer
assert 'isPaste = false;' in method(composer, 'onFocusChanged')
assert 'isPaste = false;' in composer[composer.index('public void afterTextChanged(Editable s)'):][:160]
# Retain the tested Unicode17 cluster implementation; Android23 BreakIterator is older
# and would regress ZWJ/Indic/RI clusters already covered by 766 official vectors.
assert 'PengramGraphemes.clusterEnd' in typing and '.hasGlyph(' in typing
assert 'PASTE_THRESHOLD = 64' in typing and '|| isPaste)' in typing
for locale in ('values', 'values-ru'):
    names = {s.get('name') for s in ET.parse(ROOT/f'TMessagesProj/src/main/res/{locale}/strings.xml').iter('string')}
    assert 'PengramAntiCrashBlocked' in names
# No message-content logging added in the draw routines.
assert 'FileLog' not in method(cell, 'drawMessageText')
assert '.getMessage()' not in layout and 'FileLog.e(e)' not in layout
# Reproduce the actual range bug independently (Python slicing itself would silently clamp).
for url_length in (40, 160, 600):
    original = 'x' * 600 + ' https://t.me/nft/' + 'a' * url_length
    rendered = original[:-20] + '\u200b' + original[-20:]
    end = len(rendered)
    try:
        if end > len(original): raise IndexError('Java CharSequence.subSequence')
    except IndexError: pass
    else: raise AssertionError('old range must fail')
    assert rendered[:end] == rendered
    # Every generation starts from the original, so wrapping never accumulates.
    display = rendered
    for _ in range(10):
        source = original if display is rendered else display
        assert '\u200b' not in source
        display = rendered
print('Text visibility source contracts + old NFT range failure / stable display model: PASS')

if not shutil.which('javac') or not shutil.which('java'):
    print('Extracted lifecycle JVM runner: SKIP (JDK unavailable; model is not Android proof)')
else:
    with tempfile.TemporaryDirectory(prefix='pengram-text-') as tmp:
        tmp = Path(tmp)
        stub = tmp/'org/telegram/ui/recyclerview/ChatListItemAnimator.java'
        stub.parent.mkdir(parents=True)
        real = (JAVA/'ui/recyclerview/ChatListItemAnimator.java').read_text()
        duration = re.search(r'public static final long DEFAULT_DURATION = (\d+);', real).group(1)
        stub.write_text('package org.telegram.ui.recyclerview; public class ChatListItemAnimator { public static final long DEFAULT_DURATION = '+duration+'; }')
        # Продакшн-сеттер и finishEnterTransition встраиваются дословно, а в них
        # теперь есть трассировка отправки — харнессу нужна её заглушка, иначе
        # org.telegram.messenger не существует и модель не компилируется.
        trace_stub = tmp/'org/telegram/messenger/PengramSendTrace.java'
        trace_stub.parent.mkdir(parents=True, exist_ok=True)
        trace_stub.write_text('package org.telegram.messenger;\n'
                              'public final class PengramSendTrace {\n'
                              '    public static int calls;\n'
                              '    public static boolean enabled() { return true; }\n'
                              '    public static void log(String tag, String detail) { calls++; }\n'
                              '    public static void log(String tag, String key, Object value) { calls++; }\n'
                              '}\n')
        harness = '''public class VisibilityHarness {
boolean enterTransitionInProgress; long pengramEnterGeneration; int invalidations;
Runnable queued; long delay;
private final Runnable pengramEnterWatchdog = () -> setEnterTransitionInProgress(false);
void removeCallbacks(Runnable r) { if (queued == r) queued = null; }
void postDelayed(Runnable r, long d) { queued = r; delay = d; }
void invalidate() { invalidations++; }
'''+setter+'\n'+method(cell,'getEnterTransitionGeneration')+'\n'+method(cell,'finishEnterTransition')+'''
public static void main(String[] args) {
 for (int n=0;n<100;n++) {
  VisibilityHarness cell = new VisibilityHarness();
  cell.setEnterTransitionInProgress(true);
  long old = cell.getEnterTransitionGeneration();
  cell.setEnterTransitionInProgress(true);
  cell.finishEnterTransition(old);
  if (!cell.enterTransitionInProgress) throw new AssertionError("stale owner cleared new transition");
  if (cell.delay != 500 || cell.queued == null) throw new AssertionError("deadline");
  cell.queued.run();
  if (cell.enterTransitionInProgress || cell.queued != null) throw new AssertionError("watchdog");
  cell.setEnterTransitionInProgress(true);
  cell.finishEnterTransition(cell.getEnterTransitionGeneration());
  if (cell.enterTransitionInProgress || cell.queued != null) throw new AssertionError("cancel");
 }
 System.out.println("Extracted production transition setter/owner/watchdog: 100 cases PASS (fake scheduler, not Android)");
}
}'''
        (tmp/'VisibilityHarness.java').write_text(harness)
        subprocess.run(['javac', str(stub), str(trace_stub), str(tmp/'VisibilityHarness.java')], check=True, cwd=tmp)
        subprocess.run(['java', '-cp', str(tmp), 'VisibilityHarness'], check=True, cwd=tmp)
