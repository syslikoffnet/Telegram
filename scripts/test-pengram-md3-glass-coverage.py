#!/usr/bin/env python3
"""Новые поверхности обязаны отключаться без потери прежнего фона и настроек."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
read = lambda name: (java / name).read_text()
glass = read('ui/Components/PengramSurfaceGlass.java')
skin = read('ui/Components/PengramGlassSkin.java')
tab = read('ui/Components/PengramTabBarGlass.java')
md3 = read('messenger/PengramMD3.java')
settings = read('ui/PengramSettingsActivity.java')
menu = read('ui/PengramMenuItemsActivity.java')
player = read('ui/Components/PengramMusicPlayerSheet.java')
sheets = read('ui/ActionBar/BottomSheet.java')
assert 'Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU' in glass
assert 'LiteMode.FLAG_LIQUID_GLASS' in glass
assert glass.index('factory.setLiquidGlassEffectAllowed(') < glass.index('factory.create(')
assert 'new NodeRecorder() : new BitmapRecorder()' in glass
assert 'Utilities.stackBlurBitmap(bitmap, 3)' in glass
assert 'setTargetBackground(original)' in glass
assert 'target.setPadding(left, top, right, bottom)' in glass
assert 'setBackground(null)' not in glass
assert 'sourceView.draw(canvas)' in glass and 'target.draw(canvas)' not in glass
assert 'recorder.close()' in glass and 'removeOnPreDrawListener(this)' in glass
assert 'watch(android.view.View view, Runnable update)' in md3
assert 'PengramGlassSkin.register(update)' in md3 and 'PengramGlassSkin.unregister(update)' in md3
assert 'runOnUIThread(dispatch, 80)' in skin
signature = tab.split('public static int signature()', 1)[1]
assert 'getGlassGlow()' in signature and 'rimEnabled()' in signature and 'isGlassSheenEnabled()' in signature
assert 'isSettingsSurfaceGlass()' in skin
assert 'new PengramSurfaceGlass(topBar, backgroundView' in player
assert 'backgroundView.blurred != null || backgroundView.unblurred != null' in player
assert 'new org.telegram.ui.Components.PengramSurfaceGlass(doneBar, listView' in menu
assert 'PengramMD3.menus()' in menu and 'setCornerRadii(' in menu and 'setElevation(0)' in menu
assert 'SharedConfig.animationsEnabled()' in menu and 'CubicBezierInterpolator.EASE_OUT_QUINT' in menu
assert 'sheetBackground(pengramOriginalSheetBackground, true)' in sheets
assert 'PengramMD3.styleSheetHeader(topBar, resourcesProvider, topBarGlass)' in player
assert 'PengramMD3.styleSheetHeader(header, resourcesProvider, null)' in read('ui/Components/PengramChatMusicSheet.java')
assert 'containerView.getBackground() == old' in sheets
assert ': pengramOriginalSheetBackground' in sheets
assert 'new org.telegram.ui.Components.FragmentSearchField' in settings
assert 'pengramSearchField.updateColors()' in settings
field = read('ui/Components/FragmentSearchField.java')
for token in ('PengramMD3.searchBar()', 'PengramMD3.searchBarColor(', 'PengramMD3.searchBarRadius()', 'PengramMD3.searchBarShadow()'):
    assert token in field
assert 'PengramMD3.surface(' in read('ui/Components/UniversalAdapter.java')
for name in ('PengramHistoryActivity', 'PengramConstructorActivity'):
    source = read('ui/' + name + '.java')
    assert 'PengramMD3.surface(' in source and 'PengramMD3.watch(' in source

needed = ('PengramSearchHistoryRemove', 'PengramSearchHistoryRemoved', 'PengramSettingsSurfaceGlass',
          'PengramSettingsSurfaceGlassInfo', 'PengramMenuDoneCount', 'PengramInputGlass',
          'PengramHeaderGlass', 'PengramChatHeaderGlass', 'PengramGlassSharedInfo')
for locale in ('values', 'values-ru'):
    nodes = list(ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml').iter('string'))
    names = [node.get('name') for node in nodes]
    for name in needed:
        assert names.count(name) == 1, (locale, name)
# Не ищем new по всему классу: создание bitmap/провайдера при смене размера
# допустимо; запрещены аллокации именно в добавленном draw.
for path in ('ui/Components/PengramSurfaceGlass.java', 'ui/Components/PengramGlassSheen.java'):
    source = read(path)
    for match in re.finditer(r'public void draw\([^)]*\)\s*\{', source):
        depth, pos = 1, match.end()
        end = pos
        while depth:
            if source[end] == '{': depth += 1
            if source[end] == '}': depth -= 1
            end += 1
        body = source[pos:end - 1]
        assert 'new ' not in body and 'getString(' not in body, path
assert not re.search(r'0x[0-9a-fA-F]{6,8}', glass)
print('MD3/glass: shared search field, tonal menus/history, reversible sheets, real backdrop capture and API fallback: OK')
