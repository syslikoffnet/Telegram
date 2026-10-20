#!/usr/bin/env python3
"""Regression checks for the message action island.

The island is a horizontal strip of quick actions attached to the message
context menu. Buttons placed there must disappear from the long menu (that is
the whole point), the default set must stay reply / copy / copy link / forward,
and the feature must be off until the user turns it on.

Android Java is compiled by preflight CI; this script only guards the wiring.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
config = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java').read_text()
chat = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text()
settings = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java').read_text()
items = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramMenuItemsActivity.java').read_text()
island = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMessageIslandView.java').read_text()
constructor = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramConstructorActivity.java').read_text()

# --- configuration surface ---------------------------------------------------
assert 'public static final int MSG_ACTION_REPLY = 1;' in config
assert 'public static final int MSG_ACTION_RESEND = 14;' in config
assert 'public static final int MESSAGE_ISLAND_LIMIT = 6;' in config
assert 'private static final int[] MESSAGE_ISLAND_DEFAULT = new int[]{\n            MSG_ACTION_REPLY, MSG_ACTION_COPY, MSG_ACTION_COPY_LINK, MSG_ACTION_FORWARD\n    };' in config
# off by default: the base look must not change until the user opts in
assert 'public static boolean isMessageIslandEnabled() {\n        return getBool(KEY_MESSAGE_ISLAND, false);' in config
assert 'public static final String KEY_MESSAGE_ISLAND = "messageIslandEnabled";' in config
for key in ('KEY_MESSAGE_ISLAND_LABELS', 'KEY_MESSAGE_ISLAND_COMPACT',
            'KEY_MESSAGE_ISLAND_HAPTIC', 'KEY_MESSAGE_ISLAND_POSITION', 'KEY_MESSAGE_ISLAND_TINT'):
    assert f'public static final String {key} = ' in config
assert 'isMessageIslandHaptic() {\n        return getBool(KEY_MESSAGE_ISLAND_HAPTIC, true) && isVibrationEnabled();' in config
# the island must never grow past the documented limit, even with a stale prefs file
assert 'if (result.size() >= MESSAGE_ISLAND_LIMIT) {\n                    break;' in config

# --- action registry ---------------------------------------------------------
action_ids = [int(v) for v in re.findall(r'public static final int MSG_ACTION_[A-Z_]+ = (\d+);', config)]
# 14 настраиваемых действий + служебное MSG_ACTION_DELETE = 15
assert len(action_ids) == len(set(action_ids)) == 15, action_ids
assert 'public static final int MSG_ACTION_DELETE = 15;' in config
# корзина — не пользовательская кнопка: в редакторе и в наборе по умолчанию
# её быть не должно, иначе она станет настраиваемой и «известной» меню
actions_block = config.split('private static final int[] MESSAGE_ISLAND_ACTIONS', 1)[1] \
    if 'private static final int[] MESSAGE_ISLAND_ACTIONS' in config else ''
default_block = config.split('MESSAGE_ISLAND_DEFAULT = new int[]{', 1)[1].split('};', 1)[0]
assert 'MSG_ACTION_DELETE' not in default_block
assert 'MSG_ACTION_DELETE' not in actions_block
titles = config.split('public static int getMessageIslandItemTitle(int id) {', 1)[1].split('public static int getMessageIslandItemIcon(int id) {', 1)[0]
icons = config.split('public static int getMessageIslandItemIcon(int id) {', 1)[1].split('// ------------------------------- скины пингвина', 1)[0]
for action in re.findall(r'MSG_ACTION_([A-Z_]+) =', config):
    assert f'case MSG_ACTION_{action}:' in titles, action
    assert f'case MSG_ACTION_{action}:' in icons, action

mapping = chat.split('private static int[] pengramMessageActionOptions(int action) {', 1)[1] \
    .split('/** Первый реально присутствующий пункт действия', 1)[0]
assert 'case org.telegram.messenger.PengramConfig.MSG_ACTION_REPLY: return new int[]{OPTION_REPLY};' in mapping
assert 'OPTION_COPY_LINK' in mapping and 'OPTION_FORWARD' in mapping and 'OPTION_COPY}' in mapping
assert 'OPTION_SAVE_TO_GALLERY, OPTION_SAVE_TO_GALLERY2, OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC' in mapping
assert 'PENGRAM_QUOTE' in mapping and 'PENGRAM_SAVE' in mapping and 'PENGRAM_AI' in mapping
# Translate has row-specific UI (live language detection) and must stay in the
# menu. Delete is available to the island only as the stand-in for an
# unavailable "copy link" — a deliberate, documented exception.
assert 'OPTION_TRANSLATE' not in mapping
assert 'case org.telegram.messenger.PengramConfig.MSG_ACTION_DELETE: return new int[]{OPTION_DELETE};' in mapping
assert mapping.count('OPTION_DELETE') == 1

# --- menu integration --------------------------------------------------------
menu = chat.split('private boolean createMenu(', 1)[1]
build = menu.split('if (!suggestEdit && type >= 0 && org.telegram.messenger.PengramConfig.isMessageIslandEnabled()) {', 1)[1] \
    .split('scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];', 1)[0]
assert 'int present = pengramFirstPresentOption(action, options);' in build
assert 'final int deleteOption = pengramFirstPresentOption(' in build
# пустой слот ссылки превращается в корзину только когда удаление реально
# доступно и сообщение не самоуничтожается (у него своя строка в меню)
assert 'org.telegram.messenger.PengramConfig.MSG_ACTION_DELETE, options);' in build
assert 'org.telegram.messenger.PengramConfig.isMessageIslandLinkTrash()' in build
assert 'selectedObject.messageOwner.ttl_period == 0' in build
assert 'action == org.telegram.messenger.PengramConfig.MSG_ACTION_COPY_LINK) {' in build
assert 'shown = org.telegram.messenger.PengramConfig.MSG_ACTION_DELETE;' in build
assert 'islandActions.add(shown);' in build
assert 'for (int option : pengramMessageActionOptions(shown)) {' in build
assert 'if (present < 0) {\n                            continue;' in build
assert 'islandOptions.add(present);' in build
assert 'movedOptions.contains(options.get(i))' in build
# indices stay aligned across the three parallel lists
assert 'options.remove(i);\n                                items.remove(i);\n                                icons.remove(i);' in build
assert 'messageIslandView.setListener((position, view) -> {' in build
assert 'processSelectedOption(option);' in build
assert 'final int option = islandOptions.get(position);' in build
# unavailable actions stay in the strip, dimmed, so the set never shrinks
assert 'islandEnabled.add(present >= 0);' in build
assert 'messageIslandView.setActions(islandActions, islandEnabled);' in build
assert 'if (present < 0) {\n                            continue;' in build
# both placements are wired, and only one of them runs
assert 'MESSAGE_ISLAND_POSITION_BOTTOM' in build
assert menu.count('popupLayout.addView(messageIslandView, LayoutHelper.createLinear(') == 2
assert menu.count('Gravity.CENTER_HORIZONTAL, 0,') == 2
assert 'if (messageIslandView != null && org.telegram.messenger.PengramConfig.getMessageIslandPosition()' in menu
assert 'popupLayout.precalculateHeight();' in menu.split('if (messageIslandView != null', 1)[1].split('ReactionsContainerLayout finalReactionsLayout1', 1)[0]

# --- island widget -----------------------------------------------------------
assert 'public class PengramMessageIslandView extends LinearLayout' in island
assert 'PengramConfig.isMessageIslandCompact()' in island and 'PengramConfig.isMessageIslandLabels()' in island
assert 'PengramConfig.getMessageIslandItemIcon(action)' in island
assert 'PengramConfig.getMessageIslandItemTitle(action)' in island
assert 'PengramConfig.getMessageIslandItemShortTitle(action)' in island  # short capsule label
assert 'widestLabelWidth()' in island  # capsule width follows the real text width
assert 'listener.onIslandAction(position, view);' in island
assert 'PengramConfig.isMessageIslandHaptic()' in island
assert 'MESSAGE_ISLAND_LIMIT' in island
# --- оформление Material 3 ---------------------------------------------------
assert 'PengramConfig.isMessageIslandMd3()' in island
assert 'label.setTypeface(AndroidUtilities.bold());' in island
assert 'label.setLetterSpacing(0.04f);' in island
assert 'md3 ? 0.38f : 0.42f' in island
assert 'public static final String KEY_MESSAGE_ISLAND_MD3 = "messageIslandMd3";' in config
assert 'public static boolean isMessageIslandMd3() {\n        return getBool(KEY_MESSAGE_ISLAND_MD3, true);' in config
assert 'public static final String KEY_MESSAGE_ISLAND_LINK_TRASH = "messageIslandLinkTrash";' in config
# переключатели обязаны быть на экране настроек и в поиске
assert 'PengramConfig.KEY_MESSAGE_ISLAND_MD3' in settings
assert 'PengramConfig.KEY_MESSAGE_ISLAND_LINK_TRASH' in settings
search_index = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java').read_text()
search_meta = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchMetadata.java').read_text()
assert 'R.string.PengramMessageIslandMd3,' in search_index
assert 'R.string.PengramMessageIslandLinkTrash,' in search_index
assert 'R.string.PengramMessageIslandMd3' in search_meta
assert 'R.string.PengramMessageIslandLinkTrash' in search_meta
for name in ('PengramIslandBtnDelete', 'PengramMessageIslandMd3', 'PengramMessageIslandLinkTrash'):
    for res in ('values', 'values-ru'):
        xml = (root / f'TMessagesProj/src/main/res/{res}/strings.xml').read_text()
        assert f'<string name="{name}">' in xml, (name, res)
# a message that is still being sent has no island: only existing messages are read
assert 'if (!suggestEdit && type >= 0 && org.telegram.messenger.PengramConfig.isMessageIslandEnabled()) {' in menu
assert 'setVisibility(GONE);' in island  # empty island must not leave an empty capsule
assert 'MESSAGE_ISLAND_TINT_ACCENT' in island and 'MESSAGE_ISLAND_TINT_PLAIN' in island
# a preview must not fire real actions
assert 'if (preview || listener == null || !isAvailable(position)) {' in island
# недоступное действие остаётся на месте, но приглушено и не нажимается
assert 'wrapper.setAlpha(available ? 1f : (md3 ? 0.38f : 0.42f));' in island
assert 'wrapper.setClickable(!preview && available);' in island
# ширина считается от реального места в поповере, а не от ширины экрана
assert 'protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)' in island
assert 'Math.max(hardMin, fit)' in island
assert 'getDisplayMetrics().widthPixels' in island
# скругление на стыке меню и полосы убирается
assert 'a == 0 && !islandAtTop, a == N - 1 && !islandAtBottom' in menu

# --- settings + reorder screen ----------------------------------------------
assert 'BTN_MESSAGE_ISLAND_ITEMS = 1031;' in settings
assert 'BTN_MESSAGE_ISLAND_POSITION = 1032;' in settings
assert 'BTN_MESSAGE_ISLAND_TINT = 1033;' in settings
assert 'check(PengramConfig.KEY_MESSAGE_ISLAND, false,' in settings
assert 'new PengramMenuItemsActivity(PengramMenuItemsActivity.MODE_MESSAGE)' in settings
# settings shows a live island preview and rebuilds it when size/labels change
assert 'new PengramMessageIslandView(getContext(), getResourceProvider())' in settings
assert 'islandPreview.setActions(PengramConfig.getMessageIslandItems());' in settings
assert 'compact != islandPreviewCompact || labels != islandPreviewLabels\n                || md3 != islandPreviewMd3' in settings
assert 'public static final int MODE_MESSAGE = 3;' in items
assert 'PengramConfig.getMessageIslandOrder()' in items
assert 'PengramConfig.setMessageIslandOrder(order)' in items
assert 'PengramConfig.setMessageIslandItem(row.id, row.section == 1)' in items
# both entry points guard the 6-button limit: tap-to-move and drag-and-drop
assert items.count('showIslandLimit();') == 2
assert 'PengramMessageIslandCountInfo' in items  # editor info shows a live counter
assert 'messageIslandCount() >= PengramConfig.MESSAGE_ISLAND_LIMIT' in items
assert 'PengramConfig.MESSAGE_ISLAND_LIMIT' in items
assert 'PengramConstructorMessageIsland' in constructor

# --- strings ----------------------------------------------------------------
needed = [
    'PengramMessageIsland', 'PengramMessageIslandEnable', 'PengramMessageIslandInfo',
    'PengramMessageIslandButtons', 'PengramMessageIslandLabels', 'PengramMessageIslandCompact',
    'PengramMessageIslandHaptic', 'PengramMessageIslandPosition', 'PengramMessageIslandPositionTop',
    'PengramMessageIslandPositionBottom', 'PengramMessageIslandTint', 'PengramMessageIslandTintPlain',
    'PengramMessageIslandTintSoft', 'PengramMessageIslandTintAccent', 'PengramMessageIslandLimit',
    'PengramMessageIslandItemsTitle', 'PengramMessageIslandItemsInfo', 'PengramPlaceMessageMenu',
    'PengramPlaceMessageIsland', 'PengramMessageIslandOn', 'PengramMessageIslandOff',
    'PengramConstructorMessageIsland', 'PengramConstructorMessageIslandInfo',
    'PengramMessageIslandCountInfo',
]

# каждый пункт островка имеет короткую подпись в обоих языках
short_actions = [
    'PengramIslandBtnReply', 'PengramIslandBtnCopy', 'PengramIslandBtnCopyLink',
    'PengramIslandBtnForward', 'PengramIslandBtnEdit', 'PengramIslandBtnPin',
    'PengramIslandBtnUnpin', 'PengramIslandBtnSave', 'PengramIslandBtnShare',
    'PengramIslandBtnQuote', 'PengramIslandBtnSaved', 'PengramIslandBtnCopyId',
    'PengramIslandBtnAi', 'PengramIslandBtnResend',
]
needed += short_actions
for key in short_actions:
    assert key in config, key
for locale in ('values', 'values-ru'):
    resources = ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml')
    names = {x.get('name') for x in resources.iter('string')}
    for key in needed:
        assert key in names, (locale, key)

# the settings search index must be regenerated when new rows appear
index = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java').read_text()
assert 'PengramMessageIsland' in index and 'PengramMessageIslandButtons' in index

print('message island configuration and menu wiring: OK')
