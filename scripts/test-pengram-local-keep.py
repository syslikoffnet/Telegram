#!/usr/bin/env python3
"""Локальная копия: ничего уже увиденного не исчезает.

Telegram стирает данные по команде сервера: бан/выход из чата удаляет диалог,
удаление сообщения — сами сообщения, отзыв сессии — аккаунт целиком. С
включённой локальной копией эти деструктивные действия пропускаются. Проверяем
все точки, где такие удаления запускаются, и то, что пользовательские удаления
(«Удалить чат») при этом не затрагиваются.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

keep = (java / 'org/telegram/messenger/PengramLocalKeep.java').read_text()
messages = (java / 'org/telegram/messenger/MessagesController.java').read_text()
connections = (java / 'org/telegram/tgnet/ConnectionsManager.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
en = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()
ru = (root / 'TMessagesProj/src/main/res/values-ru/strings.xml').read_text()

# --- настройка ---------------------------------------------------------------
assert 'public static final String KEY_ENABLED = "localKeep";' in keep
assert 'PengramConfig.getBool(KEY_ENABLED, true)' in keep      # по умолчанию храним
assert 'public static boolean keepDialog()' in keep
assert 'public static boolean keepDeletedMessages()' in keep
assert 'public static boolean keepRevokedAccount(int account)' in keep
assert 'ConnectionsManager.native_pauseNetwork(account)' in keep
assert 'public static boolean isLocalOnly(int account)' in keep
assert 'phone' not in keep.lower()                              # номер не трогаем
assert 'sendRequest' not in keep                                # и в сеть не ходим

# --- бан/выход: диалог остаётся ---------------------------------------------
assert messages.count('PengramLocalKeep.keepDialog()') == 4
assert '''if (!PengramLocalKeep.keepDialog()) {
                                    deleteDialog(dialog.id, 0);
                                }''' in messages
assert 'if (dialog != null && !PengramLocalKeep.keepDialog()) {' in messages
assert 'if (!PengramLocalKeep.keepDialog()) {\n                                        deleteDialog(dialog.id, 3);' in messages
assert 'if (currentDialog.top_message == 0 && !PengramLocalKeep.keepDialog()) {' in messages

# --- удалённые сообщения остаются -------------------------------------------
assert messages.count('PengramLocalKeep.keepDeletedMessages()') == 2
assert messages.count('if (!PengramLocalKeep.keepDeletedMessages()) {') == 2

# --- пользовательские удаления не тронуты -----------------------------------
# deleteDialog(did, onlyHistory) и ветка «пользователь сам удалил чат» без охраны
assert 'public void deleteDialog(final long did, int onlyHistory) {\n        deleteDialog(did, onlyHistory, false);' in messages

# --- отозванная сессия не выкидывает из аккаунта ----------------------------
assert 'if (org.telegram.messenger.PengramLocalKeep.keepRevokedAccount(currentAccount)) {' in connections
pos_guard = connections.index('PengramLocalKeep.keepRevokedAccount(currentAccount)')
pos_clear = connections.index('accountInstance.getUserConfig().clearConfig();', pos_guard - 600)
assert pos_guard < pos_clear

# --- настройки и строки ------------------------------------------------------
assert 'items.add(checkInfo(org.telegram.messenger.PengramLocalKeep.KEY_ENABLED, true,' in settings
assert 'getString(R.string.PengramLocalKeepHeader)' in settings
assert 'getString(R.string.PengramLocalKeepInfo)' in settings
for locale, text in (('en', en), ('ru', ru)):
    for key in ('PengramLocalKeepHeader', 'PengramLocalKeep', 'PengramLocalKeepInfo'):
        assert 'name="%s"' % key in text, (locale, key)

print('local copy: ban keeps dialog, server deletions keep messages, '
      'revoked session keeps account offline: OK')
