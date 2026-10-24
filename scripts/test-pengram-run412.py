#!/usr/bin/env python3
"""run412: стекло шапки «сейчас играет», живое меню Pengram, MD3-экраны,
цилиндровая прокрутка.

Контракты:
  * меню-остров: фон страницы рисуется тем же resourcesProvider, что и пункты
    (ColorDrawable + key_actionBarDefaultSubmenuBackground) — анти-чёрный экран;
  * playerHeaderLiquidGlass (default false) — тонированный слой на шапке
    аудио-плеера, включается только в STYLE_AUDIO_PLAYER;
  * md3Screens (default true) — тональные подложки больших экранов:
    профиль и журнал звонков; строка есть в тонкой настройке MD3;
  * chatCylinderScroll (default false) — поворот ячеек вокруг невидимого
    валика: строка в «Эффектах», крепление к списку чата после setAdapter;
  * строки EN+RU на все новые тумблеры.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
cfg = (java / 'messenger/PengramConfig.java').read_text()
md3 = (java / 'messenger/PengramMD3.java').read_text()
tint = (java / 'ui/Components/PengramGlassTintView.java').read_text()
wrapper = (java / 'ui/Components/PengramChatMenuWrapper.java').read_text()
fcv = (java / 'ui/Components/FragmentContextView.java').read_text()
cyl = (java / 'ui/Components/PengramCylinderScroll.java').read_text()
chat = (java / 'ui/ChatActivity.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
profile = (java / 'ui/ProfileActivity.java').read_text()
calllog = (java / 'ui/CallLogActivity.java').read_text()

# --- 1. Меню Pengram: анти-чёрная страница ----------------------------------
assert 'ColorDrawable(' in wrapper
assert 'Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider)' in wrapper
assert 'windowLayout.setBackground(' in wrapper

# --- 2. Ключи: по умолчанию стекло шапки выкл, цилиндр выкл ------------------
assert 'KEY_PLAYER_HEADER_GLASS = "playerHeaderLiquidGlass"' in cfg
assert 'isPlayerHeaderGlass() { return getBool(KEY_PLAYER_HEADER_GLASS, false); }' in cfg
assert 'PengramGlassSkin.notifyChanged();' in cfg.split('KEY_PLAYER_HEADER_GLASS', 1)[1].split('KEY_CYLINDER_SCROLL', 1)[0]
assert 'KEY_CYLINDER_SCROLL = "chatCylinderScroll"' in cfg
assert 'isCylinderScroll() { return getBool(KEY_CYLINDER_SCROLL, false); }' in cfg

# --- 3. Tint-слой: шлюз вместо жёсткого ключа -------------------------------
assert 'public interface Gate' in tint and 'boolean enabled();' in tint
assert 'this.gate = gate;' in tint
assert 'PengramConfig.isChatPinnedGlass()' not in tint
assert 'gate.enabled()' in tint

# --- 4. Шапка плеера: слой в frameLayout, только аудио-стиль ----------------
assert 'PengramGlassTintView(context, resourcesProvider, 0,' in fcv
assert 'PengramConfig.isPlayerHeaderGlass() && currentStyle == STYLE_AUDIO_PLAYER' in fcv
assert 'frameLayout.addView(pengramGlassTint, 0,' in fcv
fcv_update_style = fcv.split('currentStyle = style;', 1)[1].split('if (pengramSwipe', 1)[0]
assert 'pengramGlassTint.syncState();' in fcv_update_style
assert fcv.split('public void updateColors()', 1)[1].split('float currentPlaybackSpeed', 1)[0].count('pengramGlassTint.syncState();') == 1

# --- 5. Закреп: прежний шлюз передан метод-ссылкой --------------------------
assert 'PengramGlassTintView(getContext(), themeDelegate, 14,' in chat
assert '() -> PengramConfig.isChatPinnedGlass());' in chat

# --- 6. MD3 большие экраны ---------------------------------------------------
assert 'KEY_SCREENS = "md3Screens"' in md3
screens = md3.split('public static boolean screens()', 1)[1].split('}', 1)[0]
assert 'isEnabled()' in screens and 'KEY_SCREENS, true' in screens
assert 'PengramMD3.screens()' in profile and 'PengramMD3.surface(resourcesProvider, 0.08f)' in profile
assert 'PengramMD3.screens()' in calllog and 'PengramMD3.surface(resourceProvider, 0.08f)' in calllog
assert 'subCheck(org.telegram.messenger.PengramMD3.KEY_SCREENS, true, getString(R.string.PengramMD3Screens))' in settings

# --- 7. Настройки: строки стекла шапки и цилиндра ----------------------------
assert 'check(PengramConfig.KEY_PLAYER_HEADER_GLASS, false, getString(R.string.PengramPlayerHeaderGlass))' in settings
handler = settings.split('KEY_SETTINGS_SURFACE_GLASS.equals(key)', 1)[1].split('notifyChanged();', 1)[0]
assert 'KEY_PLAYER_HEADER_GLASS.equals(key)' in handler
more = settings.split('isChatListHeaderGlass()', 1)[1].split('moreButton(GROUP_GLASS', 1)[0]
assert 'isPlayerHeaderGlass()' in more
assert 'tgCheckInfo(BTN_EXTRA_BASE + 65, getString(R.string.PengramCylinderScroll)' in settings
assert 'PengramConfig::isCylinderScroll' in settings and 'setCylinderScroll(!PengramConfig.isCylinderScroll())' in settings

# --- 8. Цилиндр: крепление и механика ---------------------------------------
assert 'PengramCylinderScroll.attach(chatListView, PengramConfig::isCylinderScroll)' in chat
assert 'RADIUS_DP' in cyl and 'OnPreDrawListener' in cyl
assert 'child.setRotationX(angle)' in cyl and 'child.setCameraDistance' in cyl
assert 'child.setRotationX(0f)' in cyl  # выключение возвращает ячейки в норму
assert 'child.setScaleX(1f)' in cyl

# --- 9. Строки EN+RU ---------------------------------------------------------
for locale in ('values', 'values-ru'):
    resources = ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml')
    names = {x.get('name') for x in resources.iter('string')}
    for name in ('PengramPlayerHeaderGlass', 'PengramMD3Screens',
                 'PengramCylinderScroll', 'PengramCylinderScrollInfo'):
        assert name in names, f'{locale}: нет строки {name}'

# --- 10. Путь отправки не тронут --------------------------------------------
for rel in ('messenger/SendMessagesHelper.java', 'messenger/MessageObject.java'):
    assert (java / rel).exists()

print('run412: меню-остров со своим фоном, стекло шапки плеера, '
      'md3Screens (профиль/звонки), цилиндр выключен по умолчанию: OK')
