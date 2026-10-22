#!/usr/bin/env python3
"""Метка «удалено» и прозрачность: удаление обязано доходить до чата.

Тестер сообщил: на beta 9 удалённые сообщения не получают ни значка, ни
прозрачности, хотя настройка включена, и перезапуск не помогает. Причина была
в том, что включённая по умолчанию локальная копия выключала разбор
updateDeleteMessages/updateDeleteChannelMessages целиком: сообщение оставалось
в чате, но метка никогда не ставилась и не сохранялась.

Здесь проверяется весь путь удаления: приём апдейта, решение «оставить»,
запись меток, чтение меток при открытии чата и мгновенная перерисовка.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

controller = (java / 'org/telegram/messenger/MessagesController.java').read_text()
storage = (java / 'org/telegram/messenger/MessagesStorage.java').read_text()
history = (java / 'org/telegram/messenger/PengramHistory.java').read_text()
chat = (java / 'org/telegram/ui/ChatActivity.java').read_text()
cell = (java / 'org/telegram/ui/Cells/ChatMessageCell.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()

# --- 1. апдейт об удалении разбирается всегда --------------------------------
assert 'TL_updateDeleteMessages' in controller
assert 'TL_updateDeleteChannelMessages' in controller
assert 'PengramLocalKeep.keepDeletedMessages()' not in controller, \
    'локальная копия снова выключает разбор удалений'
assert controller.count('deletedMessages.put(0, arrayList)') == 1
assert controller.count('deletedMessages.put(dialogId, arrayList)') == 1

# --- 2. хранилище решает судьбу сообщения -----------------------------------
assert 'PengramHistory.keepInChat(currentAccount, messages)' in storage
assert 'PengramConfig.isSavingDeleted(currentAccount)' in storage       # архив по настройке
assert 'PengramLocalKeep.keepDeletedMessages()' in storage              # метка всегда
assert 'final boolean archived = saveArchive' in storage
assert 'if (!archived && !keepVisible)' in storage
assert 'PengramHistory.markDeleted(currentAccount, marksByDialog.keyAt(a)' in storage

# --- 3. метка нужна и локальной копии, и слежке ------------------------------
assert 'public static boolean keepInChat(int account' in history
assert 'PengramLocalKeep.keepDeletedMessages() || shouldKeep(account, ids)' in history
assert 'public static boolean marksEnabled(int account)' in history
assert 'PengramLocalKeep.keepDeletedMessages() || PengramConfig.isSavingDeleted(account)' in history
# явное «удалить у себя» сильнее любой настройки
idx_guard = history.index('public static boolean keepInChat')
idx_user = history.index('userDeletedGuard.containsKey(id)', idx_guard)
assert idx_guard < idx_user

# --- 4. прочитанные метки не путаются с «появившимися на лету» ---------------
assert 'marksLoadedDialogs' in history
assert 'marksLoadedDialogs.contains(marksKey(account, dialogId))' in history
assert 'marksLoadedDialogs.add(marksKey(account, dialogId))' in history
assert 'accountMarks(account).indexOfKey(dialogId) >= 0' not in history
# новая метка дочитывает старые, а не прячет их
idx_mark = history.index('loadMarks(account, dialogId, null);')
assert history.index('set.add(id);', history.index('private static void publishDeletedMark')) < idx_mark
assert 'hasEntry(account, dialogId, id)' in history
assert 'db.endTransaction();' in history[:history.index('publishDeletedMark(account, dialogId, messageId);')]

# --- 5. открытый чат перекрашивается сам ------------------------------------
assert 'PengramHistory.marksEnabled(currentAccount)' in chat
assert 'PengramHistory.addChangeListener(pengramMarksListener)' in chat
assert 'PengramHistory.removeChangeListener(pengramMarksListener)' in chat
assert 'private void pengramRefreshMarkedCells()' in chat
assert 'pengramApplyDeletedState(message, cell);' in chat
assert 'PengramHistory.notifyMarksChanged()' in settings

# --- 6. сама отрисовка: значок у времени и прозрачность 0.55 -----------------
assert 'PENGRAM_DELETED_ALPHA = 0.55f' in cell
assert 'private boolean pengramFaded()' in cell
assert 'public void pengramUpdateFade()' in cell
assert 'PengramConfig.getDeletedMarkIcon()' in cell
assert 'PENGRAM_MARK_SCALE' in cell
start = cell.index('public void setMessageObject(MessageObject messageObject,')
end = cell.index('private boolean frozen;', start)
assert 'pengramUpdateFade();' in cell[start:end], 'ячейка забывает пересчитать прозрачность при привязке'

print('deleted marks: update -> keepInChat -> marks -> cache -> repaint, '
      'no restart and no re-entering the chat: OK')
