#!/usr/bin/env python3
"""Стекло на плашке закрепа (chatPinnedGlass) и отсутствие островка плеера.

Контракты:
  * ключ chatPinnedGlass (default false), сеттер оповещает слушателей;
  * строка тумблера есть в настройках (EN+RU), участвует в notifyChanged
    и раскрывает блок тонкой настройки стекла;
  * слой PengramGlassTintView лежит на плашке закрепа (index0) и читает
    плотность/блик пользовательских ползунков;
  * островок плеера вырезан целиком: ни класса, ни ключа, ни строки, ни
    крепления в LaunchActivity; PengramSurfaceGlass вернулся к исходному
    гейту isSettingsSurfaceGlass.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
cfg = (java / 'messenger/PengramConfig.java').read_text()
tint = (java / 'ui/Components/PengramGlassTintView.java').read_text()
surface = (java / 'ui/Components/PengramSurfaceGlass.java').read_text()
launch = (java / 'ui/LaunchActivity.java').read_text()
chat = (java / 'ui/ChatActivity.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
index = (java / 'ui/PengramSearchIndex.java').read_text()

# --- 1. Ключ закрепа: по умолчанию выключен, сеттер оповещает ---------------
assert 'KEY_CHAT_PINNED_GLASS = "chatPinnedGlass"' in cfg
assert 'isChatPinnedGlass() { return getBool(KEY_CHAT_PINNED_GLASS, false); }' in cfg
pinned_block = cfg.split('KEY_CHAT_PINNED_GLASS', 1)[1]
assert pinned_block.count('PengramGlassSkin.notifyChanged();') == 1

# --- 2. Настройки: строка, notifyChanged, тонкая настройка ------------------
assert 'check(PengramConfig.KEY_CHAT_PINNED_GLASS, false, getString(R.string.PengramChatPinnedGlass))' in settings
handler = settings.split('KEY_SETTINGS_SURFACE_GLASS.equals(key)', 1)[1].split('notifyChanged();', 1)[0]
assert 'KEY_CHAT_PINNED_GLASS.equals(key)' in handler
more = settings.split('isChatListHeaderGlass()', 1)[1].split('moreButton(GROUP_GLASS', 1)[0]
assert 'isChatPinnedGlass()' in more
assert 'isPlayerIslandGlass()' not in settings

# --- 3. Плашка закрепа: слой лежит под контентом, состояние синхронно -------
assert 'PengramGlassTintView(getContext(), themeDelegate, 14)' in chat
assert 'pinnedMessageView.addView(pinnedGlassTint, 0,' in chat
assert 'pinnedGlassTint.syncState();' in chat
assert 'PengramConfig.isChatPinnedGlass()' in tint
assert 'PengramConfig.getGlassOpacity()' in tint
assert 'PengramConfig.isGlassSheenEnabled()' in tint
assert 'PengramGlassSkin.register(skinUpdate)' in tint and 'PengramGlassSkin.unregister(skinUpdate)' in tint

# --- 4. Строки EN+RU --------------------------------------------------------
for locale in ('values', 'values-ru'):
    resources = ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml')
    names = {x.get('name') for x in resources.iter('string')}
    assert 'PengramChatPinnedGlass' in names, f'{locale}: нет PengramChatPinnedGlass'
    assert 'PengramPlayerIslandGlass' not in names, f'{locale}: островок ещё в строках'

# --- 5. Островок вырезан целиком -------------------------------------------
assert 'PengramPlayerIslandGlass' not in index
assert not (java / 'ui/Components/PengramMusicIsland.java').exists()
assert 'PengramMusicIsland' not in launch
assert 'PengramMusicIsland' not in cfg
assert 'KEY_PLAYER_ISLAND_GLASS' not in cfg
assert not (root / 'scripts/test-pengram-player-island.py').exists()
# PengramSurfaceGlass вернулся к исходному условию
assert 'return PengramConfig.isSettingsSurfaceGlass() && applicable.call();' in surface

print('Стекло закрепа: chatPinnedGlass выключен по умолчанию, слой на плашке; '
      'островок плеера вырезан целиком: OK')
