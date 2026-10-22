#!/usr/bin/env python3
"""Провайдер запекается при create: переключатель обязан создавать новый фон."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
skin = (java / 'ui/Components/PengramGlassSkin.java').read_text()
config = (java / 'messenger/PengramConfig.java').read_text()
chat = (java / 'ui/ChatActivity.java').read_text()
dialogs = (java / 'ui/DialogsActivity.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
bar = (java / 'ui/ActionBar/ActionBar.java').read_text()
for key in ('KEY_INPUT_GLASS', 'KEY_HEADER_GLASS', 'KEY_CHAT_HEADER_GLASS'):
    assert key in config and key in settings
assert 'Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU' in skin
assert 'canRefract() && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)' in skin
assert not re.search(r'0x[0-9a-fA-F]{6,8}', skin)
assert 'Theme.getColor' in skin and 'getGlassOpacity()' in skin
assert 'getGlassGlow()' in (java / 'ui/Components/PengramTabBarGlass.java').read_text().split('public static int signature()', 1)[1]
assert 'runOnUIThread(dispatch, 80)' in skin
assert 'didSetNewTheme' in skin and 'WeakReference<Runnable>' in skin
for source, method in ((chat, 'refreshPengramSurfaces'), (dialogs, 'refreshPengramHeaderGlass')):
    body = source.split('private void ' + method + '()', 1)[1].split('\n    private ', 1)[0]
    assert 'signature(' in body
    assert body.index('setLiquidGlassEffectAllowed(') < body.index('.create(') if '.create(' in body else True
    assert 'actionBar.setupGlass(' in body and 'actionBar.clearGlass()' in body
    assert 'register(pengramGlassRefresh)' in source and 'unregister(pengramGlassRefresh)' in source
# contentView локален createView; callback должен проверять поле BaseFragment.
header_refresh = dialogs.split('private void refreshPengramHeaderGlass()', 1)[1].split('\n    private ', 1)[0]
assert 'fragmentView == null' in header_refresh
assert not re.search(r'\bcontentView\b', header_refresh)
assert 'public void clearGlass()' in bar
assert 'setBackground(pengramBackgroundBeforeGlass)' in bar
assert 'setClipChildren(pengramClipBeforeGlass)' in bar
assert settings.count('PengramGlassSkin.notifyChanged()') >= 5
xml = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()
index = (java / 'ui/PengramSearchIndex.java').read_text()
for name in ('PengramInputGlass', 'PengramHeaderGlass', 'PengramChatHeaderGlass'):
    assert f'name="{name}"' in xml and name in index
sheen = (java / 'ui/Components/PengramGlassSheen.java').read_text()
draw = sheen.split('public void draw(', 1)[1]
assert 'new ' not in draw and 'isGlassSheenEnabled()' in draw and 'getGlassGlow()' in draw
print('glass: settings, signature, guarded recreate, reversible header, live dispatch: OK')
