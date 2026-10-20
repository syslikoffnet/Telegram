#!/usr/bin/env python3
"""Regression checks for the liquid-glass bottom tab bar.

The bottom bar can be switched into a glass look: the screenshot behind it is
blurred, the edge catches the light and the selected tab carries a soft glow
that follows the real selection and leaves a highlight trail wave.

The switch is a real user preference (off by default), the effect degrades
gracefully on old devices, and nothing here is allowed to break the stock bar
when the option is off.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
config = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java').read_text()
layout = (root / 'TMessagesProj/src/main/java/org/telegram/ui/MainTabsLayout.java').read_text()
activity = (root / 'TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java').read_text()
settings = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java').read_text()
search_index = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java').read_text()
search_meta = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchMetadata.java').read_text()

# --- preference --------------------------------------------------------------
assert 'public static final String KEY_TAB_GLASS = "tabBarLiquidGlass";' in config
# opt-in: the stock bar stays exactly as it was until the switch is flipped
assert 'public static boolean isTabBarLiquidGlass() {\n        return getBool(KEY_TAB_GLASS, false);' in config
assert 'public static void setTabBarLiquidGlass(boolean value) {' in config
assert 'PengramConfig.KEY_TAB_GLASS' in settings
assert 'R.string.PengramTabBarGlass,' in search_index
assert 'R.string.PengramTabBarGlass' in search_meta

# --- layout: drawing, motion, cleanup ---------------------------------------
assert 'private boolean pengramGlass;' in layout
assert 'public void setPengramGlass(boolean enabled)' in layout
assert 'public void setPengramSelectedTab(@Nullable View tab, boolean animated)' in layout
# the glass layer must be behind the tabs, the highlight on top of them
assert layout.index('drawGlassBack(canvas);') < layout.index('super.dispatchDraw(canvas);')
assert layout.index('drawGlassSheen(canvas);') > layout.index('super.dispatchDraw(canvas);')
# glow follows the selected tab instead of the raw page index
assert 'findSelectedTab()' in layout
assert 'glassGlowX = selected == null ? getWidth() / 2f : selected.getX() + selected.getWidth() / 2f;' in layout
assert 'final float target = tab == null ? getWidth() / 2f : tab.getX() + tab.getWidth() / 2f;' in layout
# highlight wave travels in the direction of the switch
assert 'startGlassSheen(target > glassGlowX);' in layout
assert 'glassSheenForward' in layout
# press feedback: light spreads from the finger and fades out
assert 'case MotionEvent.ACTION_DOWN:' in layout
assert 'glassPressRelease = SystemClock.uptimeMillis();' in layout
assert 'private float glassPressT()' in layout
# the band covers the whole bar (with margins), not a rectangle floating in it
assert 'private boolean glassGeometry()' in layout
assert 'background.getPadding(glassPadding);' in layout
assert 'glassRect.inset(glassPadding.left, glassPadding.top,' in layout
# light and shade must follow the theme, or the bar looks wrong in one of them
assert 'glassShaderDark' in layout
assert 'Theme.isCurrentThemeDark()' in layout
# animated values must not outlive the view
assert 'protected void onDetachedFromWindow()' in layout
assert 'glassSheenAnimator.cancel();' in layout
assert 'glassGlowAnimator.cancel();' in layout

# --- activity wiring --------------------------------------------------------
assert 'private boolean pengramWantsLiquidGlass()' in activity
assert 'LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)' in activity
# asking for the effect must also let the blur layer build its refraction,
# but only where the platform can do it (the factory guards SDK + render node)
assert 'pengramGlassFactory.setLiquidGlassEffectAllowed(pengramGlassEffectAllowed);' in activity
assert 'public void applyPengramGlassOverlay()' in activity
assert 'tabsView.setPengramGlass(org.telegram.messenger.PengramConfig.isTabBarLiquidGlass());' in activity
assert 'public static void refreshPengramGlass()' in activity
# the selected tab is passed by view, so hidden tabs never win the glow
assert 'tabsView.setPengramSelectedTab(selectedView, animated);' in activity
assert 'View selectedView = null;' in activity
# replacing the background must not pile up position subscriptions
assert 'pengramViewPositionWatcher.unsubscribe(tabsView);' in activity

# --- switch plumbing --------------------------------------------------------
assert 'refreshPengramGlass();' in settings
for res in ('values', 'values-ru'):
    xml = (root / f'TMessagesProj/src/main/res/{res}/strings.xml').read_text()
    for name in ('PengramTabBarGlass', 'PengramTabBarGlassInfo'):
        assert f'<string name="{name}">' in xml, (name, res)
        assert xml.count(f'<string name="{name}">') == 1, (name, res)

print('liquid glass bottom bar: OK')
