#!/usr/bin/env python3
"""Меню «три точки» в чате: полнота настройки и мгновенное применение.

Проверяем три вещи, которые раньше ломались:

1. «Музыка чата» есть в редакторе пунктов меню — раньше порядок сверялся со
   старым списком по умолчанию, пункт отбрасывался при чтении и его нельзя было
   включить.
2. Состав меню и содержимое островка пересобираются на открытом чате.
3. Пункты, добавленные в попап, можно убрать — иначе пересобрать меню на месте
   невозможно.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
config = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java').read_text()
chat = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text()
menu = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarMenuItem.java').read_text()
wrapper = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/PengramChatMenuWrapper.java').read_text()

# --- 1. все пункты участвуют в порядке и в счётчике скрытых --------------------
assert 'private static final int[] CHAT_ITEMS_ALL' in config
assert 'CHAT_ITEMS_DEFAULT' not in config, 'старый усечённый список вернулся'
tail = config.split('private static final int[] CHAT_ITEMS_ALL', 1)[1].split('};', 1)[0]
for name in ['CHAT_ITEM_VIEW_DELETED', 'CHAT_ITEM_TO_BEGINNING', 'CHAT_ITEM_COPY_ID',
             'CHAT_ITEM_SAVED_MEDIA', 'CHAT_ITEM_DELETE_MY_MESSAGES', 'CHAT_ITEM_CHAT_MUSIC']:
    assert name in tail, name
assert 'CHAT_ITEM_PENGRAM' not in tail, 'legacy-пункт без действия попал в редактор'

order = config.split('public static java.util.ArrayList<Integer> getChatItemsOrder()', 1)[1].split('public static void', 1)[0]
assert 'CHAT_ITEMS_ALL' in order and 'knownChatItem' in order

save = config.split('public static void setChatItemsOrder(', 1)[1].split('public static boolean isChatItemHidden', 1)[0]
assert 'knownChatItem(id)' in save, 'в настройки может попасть неизвестный id'

hidden_count = config.split('public static int getHiddenChatItemsCount()', 1)[1].split('public static int chatMenuSignature', 1)[0]
assert 'CHAT_ITEMS_ALL' in hidden_count
assert 'CHAT_ITEM_CHAT_MUSIC' in config.split('public static boolean isChatItemHidden', 1)[1].split('public static void setChatItemHidden', 1)[0], \
    'новый пункт по умолчанию виден — «три точки» станут длиннее без согласия'

# --- 2. отпечаток настроек меню ------------------------------------------------
signature = config.split('public static int chatMenuSignature()', 1)[1]
for part in ['isChatMenuEnabled()', 'getChatMenuPosition()', 'getChatItemsOrder()',
             'isChatItemHidden(id)', 'getChatItemPlacement(id)',
             'getQuickActionCount()', 'getQuickActionType(a)', 'getQuickActionName(a)']:
    assert part in signature.split('public static', 1)[0], part

# --- 3. пересборка на месте ----------------------------------------------------
assert 'private void pengramCheckChatMenuSettings()' in chat
assert 'pengramCheckChatMenuSettings();' in chat.split('public void onResume()', 1)[1].split('super.onResume();', 1)[1][:400], \
    'пересборка меню не вызывается из onResume'
check = chat.split('private void pengramCheckChatMenuSettings()', 1)[1].split('\n    /**', 1)[0]
for part in ['chatMenuSignature()', 'removeLazyItem(item)', 'pengramMenuSubItems.clear()',
             'removeLazyItem(pengramMenuGap)', 'pengramMenuWrapper.setItems(',
             'applyPengramChatMainItems()']:
    assert part in check, part
assert 'pengramMenuSignature = signature;' in check

# меню собирается всегда, а видимость решает настройка — иначе включение без перезахода невозможно
assert 'isChatMenuEnabled() && ' not in chat.split('private void addPengramMenuItems()', 1)[1].split('private void pengramRunQuickAction', 1)[0]
assert 'pengramMenuIslandItem.setVisibility(org.telegram.messenger.PengramConfig.isChatMenuEnabled() ? View.VISIBLE : View.GONE)' in chat

# --- 4. убрать пункт из попапа действительно можно -----------------------------
assert 'public void removeLazyItem(Item item)' in menu
remove = menu.split('public void removeLazyItem(Item item)', 1)[1].split('private Item putLazyItem', 1)[0]
assert 'lazyList.remove(item)' in remove and 'lazyMap.remove(item.id)' in remove
assert 'item.detach()' in remove
assert 'private void detach()' in menu
assert 'removeView(view)' in menu.split('private void detach()', 1)[1].split('public void setColors', 1)[0]

# --- 5. островок пересобирается без пересоздания попапа ------------------------
assert 'public void setItems(List<Integer> newItems)' in wrapper
assert 'windowLayout.removeAllViews();' in wrapper
assert wrapper.count('ActionBarMenuItem.addItem(windowLayout') == 3, 'пункты и быстрые действия должны добавляться в одном месте'
assert 'private void rebuild()' in wrapper
assert 'getQuickActionCount()' in wrapper

print('chat menu: all items editable, popup rebuilds in place, island refreshes live: OK')
