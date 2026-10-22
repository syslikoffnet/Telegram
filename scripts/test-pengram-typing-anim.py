#!/usr/bin/env python3
"""Контракты наложения + официальные Unicode-векторы; Android-профайлер не заменяем."""
from pathlib import Path
import bisect
import hashlib
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
effects = (java / 'ui/Components/PengramTypingEffects.java').read_text()
clusters = (java / 'ui/Components/PengramGraphemes.java').read_text()
data = (java / 'ui/Components/PengramGraphemeData.java').read_text()
composer = (java / 'ui/Components/ChatActivityEnterView.java').read_text()
preview = (java / 'ui/Components/PengramTypingPreviewView.java').read_text()
config = (java / 'messenger/PengramConfig.java').read_text()
stats = (java / 'messenger/PengramTypingStats.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
index = (java / 'ui/PengramSearchIndex.java').read_text()
metadata = (java / 'ui/PengramSearchMetadata.java').read_text()

def body(source, name):
    match = re.search(r'\b' + name + r'\([^;{}]*\)\s*\{', source)
    assert match, name
    start = match.end()
    depth = 1
    end = start
    while depth:
        if source[end] == '{': depth += 1
        if source[end] == '}': depth -= 1
        end += 1
    return source[start:end - 1]

for name in ('prepareDraw', 'drawOverlay', 'draw', 'drawGlyphs', 'drawParticles', 'drawSelectionEdge', 'drawCursor', 'advanceCursor', 'computeTarget', 'glyphRect', 'matches', 'syncNativeCursor'):
    hot = body(effects, name)
    for forbidden in ('new ', 'String.format', 'getString(', 'substring(', 'subSequence(', 'getSpans(', 'String.valueOf', 'blurCache', '.put('):
        assert forbidden not in hot, (name, forbidden)
assert 'BlurMaskFilter' not in re.sub(r'//[^\n]*', '', effects)
for source in (composer, preview):
    assert 'PengramTypingEffects.prepareDraw(this)' in source
    assert 'PengramTypingEffects.drawOverlay(this, canvas)' in source
for name in re.findall(r'public static final int (INPUT_ANIM_\w+|DELETE_PARTICLE_\w+) =', config):
    if name != 'INPUT_ANIM_COUNT': assert 'case PengramConfig.' + name in effects, name
assert 'getScrollY()' in effects and 'getScrollX()' in effects and 'getTotalPaddingTop()' in effects
assert 'getPrimaryHorizontal(start)' in effects and 'getPrimaryHorizontal(end)' in effects
assert 'getSecondaryHorizontal(end)' in effects and 'isRtlCharAt' in effects
assert 'LocaleController.isRTL' not in effects
assert 'canvas.clipRect(' in effects and '- edit.getScrollY()' not in effects
assert 'PengramGraphemes.clusterEnd' in effects and 'PengramGraphemes.clusterStart' in effects
assert 'removedClusters' in effects and 'MAX_DELETE_CLUSTERS = 24' in effects
assert 'charAt(' not in body(effects, 'snapshot')
assert 'String text;' in effects and 'char ch;' not in effects
assert '.hasGlyph(' in effects and 'p.raster == null' in effects
assert 'removedTop[i] - removedBaseline[i]' in effects
assert 'edit.getScrollY() - p.scrollY' in effects
assert '.isLoaded()' in effects
assert 'MAX_PARTICLES = 160' in effects and 'perCluster == 0' in effects
assert 'rasterCache.size() >= 32' in effects and '2 * 1024 * 1024' in effects
assert '.uses++' in effects and '.uses--' in effects
assert 'isPaste || pengramExternalEditDepth > 0' in composer
assert 'setComposingText' in composer and 'finishComposingText' in composer and 'getComposingSpanStart' in effects
assert 'count == 0 && PengramConfig.isTypingDeleteAnim()' in effects
assert 'if (before > 0)' in effects and 's.invalidateEditedRange(start, before, count)' in effects
assert 'count > PASTE_THRESHOLD && count > 4096' in effects
assert 'OnPreDrawListener' in effects and 'batch.attempts <= 3' in effects
assert 'ignoreTextChange || innerTextChange != 0' in composer
assert 'autofill(' in composer and 'ACTION_SET_TEXT' in composer and 'android.R.id.undo' in composer
assert 'setLayerType' not in effects and 'setSpan(' not in effects and 'setText(' not in effects
assert 'SystemClock.uptimeMillis()' in effects and 'Math.exp(-dt /' in effects
assert 'Math.min(dp(1.5f)' in effects and 'now - forceVisibleUntil' in effects
assert 'areAnimatorsEnabled' in effects and 'ANIMATOR_DURATION_SCALE' in effects and 'SharedConfig.animationsEnabled()' in effects
assert 'hasWindowFocus()' in effects and 'edit.isFocused()' in effects
assert 'edit.removeCallbacks(this)' in effects and 'edit.removeCallbacks(applyRunnable)' in effects
assert 'PengramTypingEffects.clear(this)' in composer and 'protected void onDetachedFromWindow()' in composer
assert 'public void onWindowFocusChanged(boolean focused)' in composer
assert 'id != dialog_id || account != currentAccount' in composer
assert 'oldValue && !keyboardVisible' in composer
assert 'FLAG_IGNORE_GLOBAL_SETTING' not in body(composer, 'pengramAnimateTyping')
assert 'before > 0 && count == 0' in composer and '>= 90' in composer
assert 'WeakReference<EditText>' in effects and 'WeakHashMap<EditText, State>' in effects
assert 'WeakReference<Runnable>' in effects
assert 'announceForAccessibility' not in effects and 'contentDescription' not in effects
assert 'PengramTextFormatting.apply(editable)' in composer
assert '!composition && (before > 0 || count > 3)' in stats
assert 'onCompositionEdit(currentAccount, before, count)' in composer
assert 'pengramExternalEditDepth == 0' in composer and '!pengramImeComposing' in composer
# Все параметры либо рисует renderer, либо они принадлежат haptic/WPM.
getters = ('getInputAnimation', 'getInputAnimationSpeed', 'getInputAnimationIntensity', 'getTypingBlurRadius',
           'getTypingDeleteStyle', 'getTypingDeleteCount', 'getTypingDeleteSpeed', 'getTypingDeleteSpread',
           'getTypingDeleteSize', 'getTypingCursorSpeed', 'getTypingCursorWidth', 'getTypingCursorLiquidScale',
           'isTypingBlur', 'isTypingIgnoreSpaces', 'isTypingAnimateAllLines', 'isTypingDeleteAnim',
           'isTypingCursorSmooth', 'isTypingCursorLiquid', 'isTypingSelectionLiquid')
for getter in getters: assert 'PengramConfig.' + getter + '()' in effects, getter
assert 'isInputAnimationHaptic()' in composer and 'KEY_SHOW_BADGE' in composer
assert 'setTypingPreset' in config and 'getTypingPreset' in config and 'resetTypingAnimations' in config
assert 'typingAnimationPreset", 4' in config
for group in ('GROUP_TYPING_APPEAR', 'GROUP_TYPING_DELETE', 'GROUP_TYPING_CURSOR', 'GROUP_TYPING_EXTRA'):
    assert 'expanded(' + group + ')' in settings
assert 'typingPreview.update()' in settings and 'PengramTypingEffects.watch(this, this::update)' in preview
assert 'input.setCursorVisible(enabled && PengramTypingEffects.motionAllowed())' in preview
assert 'PresetCustom' in settings and 'PresetCalm' in settings and 'PresetMaximum' in settings
for term in ('анимация ввода', 'печатание', 'каретка', 'частицы', 'исчезновение', 'эмодзи'):
    assert term in metadata
strings = {}
for locale in ('values', 'values-ru'):
    nodes = list(ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml').iter('string'))
    strings[locale] = {node.get('name'): node.text for node in nodes}
    assert len(nodes) == len(strings[locale]), 'duplicate resources: ' + locale
new_names = set(re.findall(r'R.string.(PengramTyping(?:Preset\w*|Step\w*|ReducedMotion|EffectsOff|AdvancedHeader|PerformanceHint|Reset\w*))', settings + preview))
for name in new_names: assert name in strings['values'] and name in strings['values-ru'], name
for name in ('PengramTypingPreset', 'PengramTypingReset', 'PengramTypingDeleteCount', 'PengramTypingCursorWidth'):
    assert 'R.string.' + name + ',' in index, name

# Независимая проверка таблиц/правил на UAX-векторах. Это reference model,
# не доказательство исполнения production Java (отдельный javac runner ниже).
def table(name):
    raw = data.split('int[] ' + name + ' = {', 1)[1].split('};', 1)[0]
    numbers = [int(n, 0) for n in re.findall(r'0x[0-9A-F]+|\b\d+\b', raw)]
    return [tuple(numbers[i:i + 3]) for i in range(0, len(numbers), 3)]
tables = {name: table(name) for name in ('GCB', 'INCB', 'PICTOGRAPHIC')}
starts = {name: [row[0] for row in rows] for name, rows in tables.items()}
for rows in tables.values():
    assert all(a <= b for a, b, _ in rows)
    assert all(rows[i - 1][1] < rows[i][0] for i in range(1, len(rows)))
def prop(name, cp):
    i = bisect.bisect_right(starts[name], cp) - 1
    return tables[name][i][2] if i >= 0 and cp <= tables[name][i][1] else 0
def gcb(cp):
    return (12 if (cp - 0xAC00) % 28 == 0 else 13) if 0xAC00 <= cp <= 0xD7A3 else prop('GCB', cp)
def cuts(codepoints):
    result = [0]
    for i in range(1, len(codepoints)):
        a, b = gcb(codepoints[i - 1]), gcb(codepoints[i])
        joined = a == 1 and b == 2
        if a not in (1, 2, 3) and b not in (1, 2, 3):
            joined = (a == 9 and b in (9, 10, 12, 13) or a in (10, 12) and b in (10, 11)
                      or a in (11, 13) and b == 11 or b in (4, 5, 8) or a == 7)
            j = i - 1
            if prop('INCB', codepoints[i]) == 1:
                saw_linker = False
                while j >= 0 and prop('INCB', codepoints[j]) in (2, 3):
                    saw_linker |= prop('INCB', codepoints[j]) == 2
                    j -= 1
                joined |= j >= 0 and prop('INCB', codepoints[j]) == 1 and saw_linker
            if a == 5 and prop('PICTOGRAPHIC', codepoints[i]):
                j = i - 2
                while j >= 0 and gcb(codepoints[j]) == 4: j -= 1
                joined |= j >= 0 and bool(prop('PICTOGRAPHIC', codepoints[j]))
            if a == b == 6:
                j = i - 1
                while j >= 0 and gcb(codepoints[j]) == 6: j -= 1
                joined |= (i - 1 - j) % 2 == 1
        if not joined: result.append(i)
    return result + [len(codepoints)]
fixture = root / 'scripts/testdata/unicode/GraphemeBreakTest-17.0.0.txt'
assert hashlib.sha256(fixture.read_bytes()).hexdigest() == 'e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec'
cases = 0
for raw in fixture.read_text().splitlines():
    line = raw.split('#', 1)[0].strip()
    if not line: continue
    cp, expected = [], []
    for token in line.split():
        if token == '÷': expected.append(len(cp))
        elif token != '×': cp.append(int(token, 16))
    assert cuts(cp) == expected, raw
    cases += 1
assert cases > 700
# Снимок таблиц воспроизводим из опубликованных UCD-источников.
assert 'Unicode 17' in clusters and data.count('SHA256 ') == 3
assert 'previous == ZWJ && zwjAfterPictographic && ep' in clusters
assert 'consonant && linker && incb == 1' in clusters

java_home = os.environ.get('JAVA_HOME')
javac = os.environ.get('JAVAC') or (str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac'))
java_bin = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
if javac and java_bin:
    with tempfile.TemporaryDirectory(prefix='pengram-graphemes-') as temp:
        subprocess.run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', temp,
                        str(java / 'ui/Components/PengramGraphemeData.java'), str(java / 'ui/Components/PengramGraphemes.java'),
                        str(root / 'scripts/tests/PengramGraphemesCheck.java')], check=True)
        subprocess.run([java_bin, '-cp', temp, 'PengramGraphemesCheck', str(fixture)], check=True)
else:
    print('Production Java UAX runner: SKIP (javac/JDK unavailable; reference model is not JVM execution)')
print(f'Typing source contracts, all modes/styles, lifecycle, settings and {cases} Unicode reference vectors: PASS')
