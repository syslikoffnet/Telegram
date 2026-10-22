#!/usr/bin/env python3
"""Crash-triage contracts only; not a reproduction of obfuscated build 71789."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
base = (java / 'ui/ActionBar/BaseFragment.java').read_text()
block = base.split('private View performCreateViewImpl(Context context)', 1)[1].split('@CallSuper', 1)[0]
assert 'PengramDiagnostics.recordCreateFailure(' in block, 'createView failure lacks persistent route/phase diagnostics'
assert block.index('createView(context)') < block.index('onViewCreated(view)')
assert 'throw error;' in block, 'triage must not silently swallow the original failure'
metadata = (java / 'ui/PengramSearchMetadata.java').read_text()
opening = metadata.split('public static void open(BaseFragment fragment, int[] entry, boolean remember)', 1)[1].split('public static String path', 1)[0]
assert 'PengramDiagnostics.recordSearch(entry, fragment)' in opening
assert opening.index('PengramDiagnostics.recordSearch(entry, fragment)') < opening.index('fragment.presentFragment(target)')
diag = (java / 'messenger/PengramDiagnostics.java').read_text()
for key in ('lastSearch', 'createView', 'onViewCreated', 'PengramSettingsActivity', 'PengramChatLookActivity', 'PengramProfileHistorySettingsActivity'):
    assert key in diag, key
assert '.commit()' in diag, 'a crash must not outrun the small persistent diagnostic record'
assert 'getMessage()' not in diag, 'exception messages may contain user data'
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
ids = dict((name, int(value)) for name, value in re.findall(r'private static final int (BTN_\w+) = (\d+);', settings))
assert ids['BTN_DIAGNOSTICS'] not in [value for name, value in ids.items() if name != 'BTN_DIAGNOSTICS'], 'diagnostics button ID collides with another action'
activity = (java / 'ui/PengramDiagnosticsActivity.java').read_text()
assert activity.index('fragmentView = root') < activity.index('root.addView(')
assert 'removeCallbacks(refresh)' in activity and 'postDelayed(refresh, 1000)' in activity
assert 'AndroidUtilities.addToClipboard' in activity
for locale in ('values', 'values-ru'):
    text = (root / 'TMessagesProj/src/main/res' / locale / 'strings.xml').read_text()
    for key in ('PengramDiagnostics', 'PengramDiagnosticsInfo', 'PengramDiagnosticsCopy', 'PengramSessionErrSlotBusy'):
        assert 'name="' + key + '"' in text, (locale, key)
print('OK: persistent search/create-phase diagnostics and attached-only diagnostic screen')
