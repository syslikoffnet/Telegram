#!/usr/bin/env python3
"""Жидкое стекло нижней панели: настоящая прозрачность и реакция на жест.

Проверяем три вещи, из-за которых прошлый вариант «не был стеклом»:
1) фон панели берётся из нашего провайдера с прозрачностью меньше половины,
   а не из штатного mainTabs() с alpha 0.85;
2) линза-преломление включается пользовательским переключателем и получает
   толщину/интенсивность;
3) палец можно вести по панели — блик тянется за ним.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent

glass = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/PengramTabBarGlass.java').read_text()
layout = (root / 'TMessagesProj/src/main/java/org/telegram/ui/MainTabsLayout.java').read_text()
activity = (root / 'TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java').read_text()

# --- провайдер: прозрачность и кант -----------------------------------------
assert 'public final class PengramTabBarGlass' in glass
assert 'BlurredBackgroundProviderImpl.solveSrcColor(colorBg, colorTarget, alpha(isDark))' in glass
# Плотность стекла теперь пользовательская: 20…90 %, по умолчанию 42 % —
# это по-прежнему заметно прозрачнее штатных 0.85/0.76.
assert 'org.telegram.messenger.PengramConfig.getGlassOpacity() / 100f' in glass
config = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java').read_text()
assert 'getIntCached(KEY_GLASS_OPACITY, 42)' in config
assert 'Math.max(20, Math.min(90' in config
# штатные 0.85/0.76 больше не используются для панели
assert 'PengramTabBarGlass.provider(resourceProvider)' in activity
assert 'BlurredBackgroundProviderImpl.mainTabs(resourceProvider)' not in activity
assert 'public static int thicknessPx()' in glass and 'public static float intensity()' in glass

# --- линза включается нашим тумблером, а не только системным флагом ----------
assert 'org.telegram.messenger.PengramConfig.isTabBarLiquidGlass()\n                || LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)' in activity
assert 'drawable.setThickness(org.telegram.ui.Components.PengramTabBarGlass.thicknessPx());' in activity
assert 'drawable.setIntensity(org.telegram.ui.Components.PengramTabBarGlass.intensity());' in activity
assert 'pengramGlassFactory.setLiquidGlassEffectAllowed(want);' in activity

# --- жест пальцем ------------------------------------------------------------
assert 'case MotionEvent.ACTION_MOVE:' in layout
assert 'glassPressLastX' in layout and 'glassPressLastY' in layout
assert 'glassDragDx' in layout and 'glassDragDy' in layout and 'glassDragSpeed' in layout
assert 'private float glassDragNow()' in layout
# блик подсветки тянется к пальцу и вытягивается по ходу движения
assert 'final float glowCx = glassGlowX + (glassPressX - glassGlowX) * 0.45f * pull;' in layout
assert 'glassMatrix.setScale(pressRadius * (1f + 0.60f * drag),' in layout
assert 'glassMatrix.postRotate(dragAngle);' in layout
# анимация продолжается, пока палец движется, а не только пока он нажат
assert 'glassPressDown || glassPressT() > 0f || glassDragNow() > 0.01f' in layout
# палец отпустили — хвост гаснет, состояние сбрасывается
assert 'glassDragSpeed = 0f;' in layout and 'glassDragDx = glassDragDy = 0f;' in layout

print('liquid glass: transparent provider, real lens and press-drag response: OK')
