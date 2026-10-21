#!/usr/bin/env python3
"""Порядок аккаунтов задаётся перетаскиванием и соблюдается везде.

Раньше каждый список сортировал слоты по loginTime, поэтому переставленные
аккаунты возвращались на место. Теперь порядок хранится в PengramConfig,
строки в настройках тащатся долгим нажатием, а все остальные списки читают
тот же порядок через PengramAccountOrder.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

order = (java / 'org/telegram/messenger/PengramAccountOrder.java').read_text()
config = (java / 'org/telegram/messenger/PengramConfig.java').read_text()
settings = (java / 'org/telegram/ui/SettingsActivity.java').read_text()
main_tabs = (java / 'org/telegram/ui/MainTabsActivity.java').read_text()
dialogs = (java / 'org/telegram/ui/DialogsActivity.java').read_text()
user_info = (java / 'org/telegram/ui/UserInfoActivity.java').read_text()
oauth = (java / 'org/telegram/ui/OAuthSheet.java').read_text()
wear = (java / 'org/telegram/ui/WearAuthSheet.java').read_text()
alerts = (java / 'org/telegram/ui/Components/AlertsCreator.java').read_text()

# --- хранилище --------------------------------------------------------------
assert 'public static final String KEY_ACCOUNT_ORDER = "accountOrder";' in config
assert 'PengramConfig.getStringValue(PengramConfig.KEY_ACCOUNT_ORDER' in order
assert 'PengramConfig.setStringValue(PengramConfig.KEY_ACCOUNT_ORDER' in order

# --- общий порядок: сохранённые слоты, потом новые по loginTime -------------
assert 'public static ArrayList<Integer> activeOrder()' in order
assert 'public static void sort(ArrayList<Integer> accounts)' in order
assert 'public static void saveVisibleOrder(ArrayList<Integer> visible)' in order
assert 'UserConfig.getInstance(a).isClientActivated()' in order
# скрытый текущий аккаунт не должен уезжать при перетаскивании видимых строк
assert 'final HashSet<Integer> visibleSet = new HashSet<>(visible);' in order
assert 'full.set(i, visible.get(cursor++));' in order

# --- списки аккаунтов читают общий порядок ---------------------------------
for name, source in [
    ('settings', settings),
    ('main tabs', main_tabs),
    ('dialogs', dialogs),
    ('user info', user_info),
    ('oauth', oauth),
    ('wear', wear),
]:
    assert 'PengramAccountOrder.sort(accountNumbers);' in source, name

# старого порядка по loginTime в списках аккаунтов больше нет
for source in (settings, main_tabs, dialogs, user_info, oauth, wear):
    assert 'Collections.sort(accountNumbers' not in source

# диалог выбора аккаунта тоже идёт по общему порядку
assert 'for (int a : PengramAccountOrder.activeOrder()) {' in alerts

# --- перетаскивание в настройках -------------------------------------------
assert 'listView.listenReorder(this::onAccountsReordered);' in settings
assert 'listView.allowReorder(true);' in settings
assert 'accountsReorderSection = adapter.reorderSectionStart();' in settings
assert 'adapter.reorderSectionEnd();' in settings
assert 'private void onAccountsReordered(int sectionId, ArrayList<UItem> items)' in settings
assert 'PengramAccountOrder.saveVisibleOrder(order);' in settings
# секция начинается ровно перед строками аккаунтов, а не вокруг всего списка
accounts_block = settings[settings.index('accountsReorderSection = adapter.reorderSectionStart();'):]
assert accounts_block.index('AccountCell.Factory.of(i, accountNumbers.get(i))') < accounts_block.index('adapter.reorderSectionEnd();')
# id строки (позиция) не используется для переключения — только слот
assert 'final int account = item.intValue;' in settings

# --- тот же жест в блоке аккаунтов профиля ---------------------------------
assert 'listView.listenReorder(this::onAccountsReordered);' in user_info
assert 'listView.allowReorder(true);' in user_info
assert 'accountsReorderSection = adapter.reorderSectionStart();' in user_info
assert 'SettingsActivity.saveAccountOrder(items);' in user_info
assert 'public static void saveAccountOrder(ArrayList<UItem> items)' in settings

print('account order: drag in settings, one order for every account list: OK')
