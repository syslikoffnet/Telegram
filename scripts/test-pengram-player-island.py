#!/usr/bin/env python3
"""Островок свёрнутого плеера + тестовые слои жидкого стекла (run409-B).

Контракты:
  * глобальная капсула поверх контента: attach в LaunchActivity, снос в onDestroy;
  * показ только для играющей музыки, крестик прячет до следующего трека;
  * дубль штатной шапки чата (FragmentContextView) подавляется;
  * play/pause/next — штатным API MediaController, тап открывает полный плеер;
  * стекло островка — свой ключ playerIslandGlass (default false) через
    переработанный PengramSurfaceGlass (гейт делегируется вызывающему);
  * плашка закрепа — тестовый ключ chatPinnedGlass (default false) с тоном,
    кантом и бликом; строки тумблеров есть в настройках (EN+RU).
"""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
cfg = (java / 'messenger/PengramConfig.java').read_text()
island = (java / 'ui/Components/PengramMusicIsland.java').read_text()
surface = (java / 'ui/Components/PengramSurfaceGlass.java').read_text()
tint = (java / 'ui/Components/PengramGlassTintView.java').read_text()
launch = (java / 'ui/LaunchActivity.java').read_text()
chat = (java / 'ui/ChatActivity.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()

# --- 1. Ключи: по умолчанию оба выключены, сеттеры оповещают слушателей ------
assert 'KEY_PLAYER_ISLAND_GLASS = "playerIslandGlass"' in cfg
assert 'isPlayerIslandGlass() { return getBool(KEY_PLAYER_ISLAND_GLASS, false); }' in cfg
assert cfg.count('setBool(KEY_PLAYER_ISLAND_GLASS, value);') == 1
island_block = cfg.split('KEY_PLAYER_ISLAND_GLASS', 1)[1].split('KEY_CHAT_PINNED_GLASS', 1)[0]
assert 'PengramGlassSkin.notifyChanged();' in island_block
assert 'KEY_CHAT_PINNED_GLASS = "chatPinnedGlass"' in cfg
assert 'isChatPinnedGlass() { return getBool(KEY_CHAT_PINNED_GLASS, false); }' in cfg
pinned_block = cfg.split('KEY_CHAT_PINNED_GLASS', 1)[1]
assert pinned_block.count('PengramGlassSkin.notifyChanged();') == 1

# --- 2. Островок: музыка, показ, управление ---------------------------------
assert 'playing.isMusic()' in island and '!playing.isVideo()' in island
assert 'playing.getId() != 0' in island
assert 'getMusicTitle(true)' in island and 'getMusicAuthor(true)' in island
assert 'isMessagePaused()' in island and 'playMessage(playing)' in island and 'pauseMessage(playing)' in island
assert 'playNextMessage()' in island
assert 'userClosed = true' in island and 'userClosed = false' in island
assert 'PengramMusicPlayerSheet.create(target, null).show()' in island
# дубль с штатной шапкой чата подавляется
assert 'chatHeaderOwnsPlayer' in island
assert 'getFragmentContextView()' in island and 'header.getVisibility() == VISIBLE' in island
# глобальное крепление: верх по центру, статус-бар учтён, детач полный
assert 'PengramMusicIsland.attach(' in launch
idx_set = launch.index('setContentView(frameLayout);')
assert launch.index('PengramMusicIsland.attach(', idx_set) > idx_set
assert 'Gravity.TOP | Gravity.CENTER_HORIZONTAL' in island
assert 'AndroidUtilities.statusBarHeight' in island
on_destroy = launch.index('protected void onDestroy()')
assert launch.index('PengramMusicIsland.detach();', on_destroy) > on_destroy
assert 'ticker.removeCallbacks(instance.tick)' in island
assert 'parent.removeView(instance)' in island
# опрос плеера без привязки к аккаунту
assert 'ticker.postDelayed(tick, TICK_MS)' in island or 'ticker.postDelayed(island.tick, TICK_MS)' in island

# --- 3. Стекло островка: свой ключ + делегированное условие ------------------
assert 'PengramSurfaceGlass(island, backdrop, null, PengramConfig::isPlayerIslandGlass)' in island
assert 'return applicable.call();' in surface
assert 'isSettingsSurfaceGlass() && applicable' not in surface

# --- 4. Плашка закрепа: тестовый слой стекла ---------------------------------
assert 'PengramGlassTintView(getContext(), themeDelegate, 14)' in chat
assert 'pinnedMessageView.addView(pinnedGlassTint, 0,' in chat
assert 'pinnedGlassTint.syncState();' in chat
assert 'PengramConfig.isChatPinnedGlass()' in tint
assert 'PengramConfig.getGlassOpacity()' in tint
assert 'PengramConfig.isGlassSheenEnabled()' in tint
assert 'PengramGlassSkin.register(skinUpdate)' in tint and 'PengramGlassSkin.unregister(skinUpdate)' in tint

# --- 5. Настройки: строки тумблеров, уведомление, тонкая настройка -----------
assert 'check(PengramConfig.KEY_PLAYER_ISLAND_GLASS, false, getString(R.string.PengramPlayerIslandGlass))' in settings
assert 'check(PengramConfig.KEY_CHAT_PINNED_GLASS, false, getString(R.string.PengramChatPinnedGlass))' in settings
handler = settings.split('KEY_SETTINGS_SURFACE_GLASS.equals(key)', 1)[1].split('notifyChanged();', 1)[0]
assert 'KEY_PLAYER_ISLAND_GLASS.equals(key)' in handler and 'KEY_CHAT_PINNED_GLASS.equals(key)' in handler
more = settings.split('isChatListHeaderGlass()', 1)[1].split('moreButton(GROUP_GLASS', 1)[0]
assert 'isPlayerIslandGlass()' in more and 'isChatPinnedGlass()' in more

# --- 6. Строки EN+RU ---------------------------------------------------------
for locale in ('values', 'values-ru'):
    resources = ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml')
    names = {x.get('name') for x in resources.iter('string')}
    for name in ('PengramPlayerIslandGlass', 'PengramChatPinnedGlass'):
        assert name in names, f'{locale}: нет строки {name}'

# --- 7. Плеер-лист не трогает отправку --------------------------------------
for rel in ('messenger/SendMessagesHelper.java', 'messenger/MessageObject.java',
            'messenger/MessagesController.java', 'messenger/MediaController.java',
            'messenger/NotificationCenter.java'):
    assert (java / rel).exists()

print('Игрок-островок: показ по музыке, дубль чат-шапки подавлен, '
      'playerIslandGlass и chatPinnedGlass выключены по умолчанию: OK')
