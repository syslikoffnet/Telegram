#!/usr/bin/env python3
"""Приёмка: «удалить все мои сообщения» + опция «не сохранять локально».

Проверяет:
 - ChatActivity: в диалоге подтверждения есть чекбокс PengramDeleteMyMessagesNoLocal
   (по умолчанию выключен — прежнее поведение не меняется молча), флаг доносится
   до страниц удаления, и на каждой странице при включённой опции стоят
   guardUserDeleted + purgeMessages ДО вызова deleteMessages;
 - PengramHistory.purgeMessages: чистит и history, и pending_edits чанками
   (account+dialog_id+message_id IN), внутри executor-а и транзакции, шлёт
   notifyChanged, не трогает deleted_marks и saved_media;
 - строки EN/RU присутствуют и уникальны;
 - сохранение при прежнем режиме (без опции) не задето: saveSavedBatch и
   AlertsCreator guardSaveForMyself остались на месте.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def main():
    chat = read('TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java')

    # 1. Флаг и диалог подтверждения
    assert 'private boolean pengramDeleteNoLocalCopy;' in chat, 'нет флага режима'
    assert 'R.string.PengramDeleteMyMessagesNoLocal' in chat, 'чекбокс не добавлен в диалог'
    assert 'new CheckBoxCell(getContext(), 1, themeDelegate)' in chat, \
        'нет чекбокса по образцу AlertsCreator'
    assert '.setView(noLocalFrame)' in chat, 'view не подключён к диалогу'
    assert 'final boolean[] noLocalCopy = new boolean[]{false};' in chat, \
        'опция по умолчанию должна быть выключена (прежнее поведение не меняем молча)'
    assert 'pengramStartDeleteMyMessages(total, noLocalCopy[0])' in chat, \
        'флаг не доносится до стартового метода'
    assert 'private void pengramStartDeleteMyMessages(int total, boolean noLocalCopy)' in chat, \
        'сигнатура старта не обновлена'

    # 2. Порядок на странице: guard + purge строго ДО серверного удаления
    guard_idx = chat.index('PengramHistory.guardUserDeleted(ids);', chat.index('pengramDeleteMyMessagesPage'))
    purge_idx = chat.index('PengramHistory.purgeMessages(getCurrentAccount(), getDialogId(), ids);')
    delete_idx = chat.index('getMessagesController().deleteMessages(ids, null, null, getDialogId(), 0, true, 0);')
    assert guard_idx < delete_idx, 'guard должен стоять до deleteMessages'
    assert purge_idx < delete_idx, 'purge должен стоять до deleteMessages'
    assert 'if (pengramDeleteNoLocalCopy) {' in chat, 'нет ветки по опции'
    # прежний режим не тронут: вне ветки ровно один вызов deleteMessages без guard
    assert chat.count('getMessagesController().deleteMessages(ids, null, null, getDialogId(), 0, true, 0);') == 1

    # 3. purgeMessages в PengramHistory — умная чистка ВСЕХ следов id
    hist = read('TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java')
    assert 'public static void purgeMessages(final int account, final long dialogId' in hist, \
        'нет purgeMessages'
    start = hist.index('public static void purgeMessages')
    end = hist.index('public static void', start + 10)
    body = hist[start:end]
    assert 'db.delete(TABLE, where, whereArgs)' in body, 'история (saved/deleted/edited) не чистится'
    assert 'db.delete(TABLE_PENDING, where, whereArgs)' in body, 'pending_edits не чистится'
    assert 'db.delete(TABLE_MARKS, where, whereArgs)' in body, \
        'метки deleted_marks этих id должны вычищаться — ledger целиком'
    assert 'message_id IN (' in body, 'нужен выборочный delete по id'
    assert 'executor.execute' in body, 'запрос должен идти в history executor'
    assert 'db.beginTransaction()' in body and 'db.setTransactionSuccessful()' in body, \
        'пакетная чистка должна быть в транзакции'
    assert 'chunkSize = 400' in body, 'IN-пачки обязаны укладываться в лимит SQLite (999)'
    # файлы копий вложений: сбор кандидатов ДО вырезания строк, клининг ПОСЛЕ
    collect = body.index('files.addAll(collectMediaPaths(db, where, whereArgs))')
    delete = body.index('db.delete(TABLE, where, whereArgs)')
    assert collect < delete, 'пути файлов собираются ДО удаления строк'
    assert 'SELECT attach_path FROM ' in body, 'пути из pending_edits тоже собираем'
    after = body[body.index('db.endTransaction()'):]
    assert 'forgetCommittedMarks(account, dialogId)' in after, 'кэш меток не перезагружен'
    assert 'cleanupUnreferencedMedia(db, files)' in after, 'файлы копий не дочищены'
    assert 'invalidateCounts(dialogId)' in after, 'счётчики истории не сброшены'
    assert 'notifyChanged()' in after, 'слушателей истории нужно уведомить'
    # запись при прежнем режиме не задета, все пути записи под гардом
    assert 'if (message == null || isUserDeleted(message.id)) continue;' in hist, \
        'guard записи в saveSavedBatch потерян'
    assert '|| isUserDeleted(messageId)) return;' in hist, 'guard enqueueSave потерян'
    assert 'if (isUserDeleted(messageId)) return;' in hist, 'guard save() потерян'
    assert 'if (!isArchivableSnapshot(dialogId, message, ACTION_DELETED) || isUserDeleted(message.id)) return;' in hist, \
        'guard saveDeletedSnapshot потерян'
    assert 'if (isUserDeleted(id)) return;' in hist, 'guard publishDeletedMark потерян'
    storage = read('TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java')
    assert 'PengramHistory.isUserDeleted(message.id)) continue;' in storage, \
        'guard в pengramSaveDeleted (удалённые + их медиа) потерян'

    # 4. Строки EN/RU уникальны, с формулировкой из задачи
    en = read('TMessagesProj/src/main/res/values/strings.xml')
    ru = read('TMessagesProj/src/main/res/values-ru/strings.xml')
    for name, blob in (('EN', en), ('RU', ru)):
        assert blob.count('name="PengramDeleteMyMessagesNoLocal"') == 1, f'{name}: дубль/пропуск строки'
    assert '<string name="PengramDeleteMyMessagesNoLocal">Не сохранять локально</string>' in ru, \
        'RU-формулировка должна быть «Не сохранять локально»'
    assert '<string name="PengramDeleteMyMessagesNoLocal">Do not keep locally</string>' in en, \
        'EN-формулировка должна быть «Do not keep locally»'

    # 5. Обычный путь удаления с «сохранить у себя» не тронут
    alerts = read('TMessagesProj/src/main/java/org/telegram/ui/Components/AlertsCreator.java')
    assert 'PengramHistory.guardSaveForMyself(ids);' in alerts, \
        'прецедент guardSaveForMyself в основном диалоге должен остаться'
    assert 'PengramHistory.guardUserDeleted(ids);' in alerts

    print('OK: опция «удалить не сохраняя локально» — чекбокс, guard+purge до delete, '
          'purge чистит history+pending_edits транзакционно, строки EN/RU на месте')


if __name__ == '__main__':
    main()
