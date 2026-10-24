#!/usr/bin/env python3
"""Жидкое стекло: больше настроек, чем один тумблер.

Плотность, толщина линзы, сила преломления и яркость бликов читаются из
конфига и попадают в провайдер/шов отрисовки; блик при смене вкладки, блик
за пальцем и светлый кант отключаются отдельно. Ползунки должны применяться
сразу, поэтому activity пересобирает фон при смене отпечатка настроек.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

config = (java / 'org/telegram/messenger/PengramConfig.java').read_text()
glass = (java / 'org/telegram/ui/Components/PengramTabBarGlass.java').read_text()
layout = (java / 'org/telegram/ui/MainTabsLayout.java').read_text()
activity = (java / 'org/telegram/ui/MainTabsActivity.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
index = (java / 'org/telegram/ui/PengramSearchIndex.java').read_text()
en = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()
ru = (root / 'TMessagesProj/src/main/res/values-ru/strings.xml').read_text()

# --- ключи и границы ---------------------------------------------------------
for key in ('KEY_GLASS_OPACITY', 'KEY_GLASS_THICKNESS', 'KEY_GLASS_INTENSITY',
            'KEY_GLASS_GLOW', 'KEY_GLASS_SHEEN', 'KEY_GLASS_DRAG', 'KEY_GLASS_RIM'):
    assert key in config, key
assert 'getIntCached(KEY_GLASS_OPACITY, 56)' in config
assert 'Math.max(4, Math.min(30' in config          # толщина линзы, dp
assert 'Math.max(0, Math.min(150' in config          # яркость/преломление, %
assert 'getBool(KEY_GLASS_SHEEN, true)' in config
assert 'getBool(KEY_GLASS_DRAG, true)' in config
assert 'getBool(KEY_GLASS_RIM, true)' in config

# --- провайдер и отрисовка читают настройки ---------------------------------
assert 'org.telegram.messenger.PengramConfig.getGlassThicknessDp()' in glass
assert 'org.telegram.messenger.PengramConfig.getGlassIntensity() / 100f' in glass
assert 'public static boolean rimEnabled()' in glass
assert 'public static float glowScale()' in glass
assert 'public static int signature()' in glass
# кант выключается и в провайдере, и в шейном слое панели
assert 'setStrokeColorTop(rimEnabled() ? 0x38FFFFFF : 0' in glass
assert 'if (org.telegram.messenger.PengramConfig.isGlassRimEnabled())' in layout
# блик при смене вкладки и блик за пальцем — отдельные переключатели
assert 'if (!org.telegram.messenger.PengramConfig.isGlassSheenEnabled())' in layout
assert '|| !org.telegram.messenger.PengramConfig.isGlassDragEnabled())' in layout
assert 'PengramTabBarGlass.glowScale()' in layout

# --- ползунки применяются без перезапуска -----------------------------------
assert 'pengramGlassSignature' in activity
assert 'signature != pengramGlassSignature' in activity
assert 'public static void refreshPengramGlassOverlayOnly()' in activity
assert 'org.telegram.ui.MainTabsActivity.refreshPengramGlass();' in settings
assert 'org.telegram.ui.MainTabsActivity.refreshPengramGlassOverlayOnly();' in settings

# --- у каждой настройки одно место, и она ищется ----------------------------
assert 'GROUP_GLASS = 12' in settings
for name in ('PengramGlassOpacity', 'PengramGlassThickness', 'PengramGlassIntensity',
             'PengramGlassGlow', 'PengramGlassSheen', 'PengramGlassDrag', 'PengramGlassRim'):
    assert settings.count('getString(R.string.%s)' % name) == 1, name + ' duplicated'
    assert 'R.string.%s,' % name in index, name + ' not searchable'
    assert 'name="%s"' % name in en and 'name="%s"' % name in ru, name + ' string'

print('liquid glass settings: knobs, wiring and search home: OK')
