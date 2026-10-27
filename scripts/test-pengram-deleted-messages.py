#!/usr/bin/env python3
"""Приёмка: удалённые сообщения — отображение и сохранение.

Порт лучшей лайнейной реализации (f03ce811) на мастер-базу, БЕЗ lock/архивной
охраны и БЕЗ правок send-файлов. Проверяет:

 - MessagesStorage: keep-возврат и запись метки при keepVisible (`archived || keepVisible`),
   guards isArchivableSnapshot/isUserDeleted;
 - ChatActivity: keep-ветка ставит снимок в архив и читает метку (не слепой true),
   переживание перезапуска через pengramApplyDeletedState/marksLoaded;
 - PengramHistory: DB_VERSION=8, постраничная выгрузка архива, hasStorageError;
 - экраны истории: постраничность с generation, return на месте;
 - LocalKeep: синхронизация localOnly;
 - поиск: localSearchLoading у адаптера, без pendingLocalSearchOpenGeneration;
 - отображение в ячейке (метка+fade) — без правок send-файлов;
 - строки EN/RU; путь отправки байт-в-байт мастер-тегу.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = 'build-master-20261023-1345'
SEND_FILES = [
    'TMessagesProj/src/main/java/org/telegram/ui/TextMessageEnterTransition.java',
    'TMessagesProj/src/main/java/org/telegram/ui/MessageEnterTransitionContainer.java',
    'TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java',
]
# ChatMessageCell и MessageObject обновлены апстримом13.1.1: байт-паритет с
# мастер-тегом заменён маркерной контрактурой — золотые патчи Pengram и новые
# строки13.1.1 обязаны присутствовать в файле (любая потеря при будущих мержах = фейл).
SEND_MARKER_FILES = {
    'TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java':
        'scripts/testdata/send_gold_chat_message_cell.txt',
    'TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java':
        'scripts/testdata/send_gold_message_object.txt',
}


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def main():
    # A. Хранилище: архив+метка при keepVisible, guards, keep-возврат
    st = read('TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java')
    assert 'pengramSaveDeleted(' in st, 'нет вызова сохранения перед удалением'
    assert 'PengramHistory.keepInChat(currentAccount, messages)' in st, 'нет keep-возврата из БД'
    assert 'if (archived || keepVisible) {' in st, 'метка должна писаться и при keepVisible'
    assert 'isArchivableSnapshot(did, message, PengramHistory.ACTION_DELETED)' in st, 'нет guard архивируемости'
    assert 'PengramHistory.isUserDeleted(message.id)' in st, 'нет guard «удалено самим»'

    # B. Чат: keep-ветка и переживание перезапуска
    chat = read('TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java')
    assert 'PengramHistory.saveDeletedSnapshot(currentAccount, dialog_id, obj.messageOwner)' in chat, \
        'keep-ветка обязана сохранять снимок'
    # порядок: снимки ВСЕХ id сохраняются до markDeleted — иначе hasEntry ложно
    # отсекает первую волну удаления (метка вставляется только на дубле события,
    # а анимация эффекта при дубле переигрывается — дефект «иногда повторно»)
    keep_start = chat.index('private boolean pengramKeepDeletedMessages')
    keep_body = chat[keep_start:chat.index('private void pengramUnselect', keep_start)]
    assert keep_body.index('saveDeletedSnapshot') < keep_body.index('markDeleted('), \
        'keep: снимки обязаны сохраняться до markDeleted'
    # флаг ставится синхронно по решению «оставляем призрака», а не читается из
    # кэша меток до асинхронной публикации (там всегда false на первой волне)
    assert 'obj.pengramDeleted = true;' in keep_body, \
        'флаг pengramDeleted ставится синхронно в keep-ветке'
    assert 'obj.pengramDeleted = org.telegram.messenger.PengramHistory.isMarkedDeleted(' not in keep_body, \
        'keep не должен читать кэш меток до публикации'
    # эффект идемпотентен: повтор события удаления не переигрывает анимацию
    eff_start = chat.index('private void pengramPlayDeleteEffect')
    eff_body = chat[eff_start:chat.index('private android.graphics.RectF pengramEffectBounds', eff_start)]
    for token in ('object.pengramDeleted', 'object.pengramArchived',
                  'PengramHistory.isMarkedDeleted(currentAccount, dialog_id, object.getId())'):
        assert token in eff_body, f'pengramPlayDeleteEffect: нет гарда «{token}»'
    assert 'pengramApplyDeletedState' in chat, 'нет переживания состояния при перерисовке'
    assert 'PengramHistory.marksLoaded(currentAccount, dialog_id)' in chat, \
        'недогруженные метки не должны обнулять прозрачность'
    assert 'pendingLocalSearchOpenGeneration' not in chat, 'старая навигация поиска не должна вернуться'
    # два ханка анимации перехода не тронуты
    assert 'getFragmentTransitionDuration' in chat and 'resetPengramEnterAnim' in chat

    # C. История: версия БД, постраница, ошибки
    hist = read('TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java')
    assert 'DB_VERSION = 8' in hist, 'ожидается схема v8'
    for token in ('loadArchivePage', 'hasStorageError', 'saveDeletedSnapshot',
                  'isArchivableSnapshot', 'marksLoaded', 'insertDeletedMark'):
        assert token in hist, f'PengramHistory: нет {token}'

    # D. Экраны истории: пагинация и return'ы
    act = read('TMessagesProj/src/main/java/org/telegram/ui/PengramHistoryActivity.java')
    for token in ('PAGE_SIZE', 'loadNextPage', 'generation', 'return view;'):
        assert token in act, f'PengramHistoryActivity: нет {token}'
    assert 'archiveGuard' not in act and 'PengramArchiveGuard' not in act, 'lock-код не должен попадать'
    chat_act = read('TMessagesProj/src/main/java/org/telegram/ui/PengramHistoryChatActivity.java')
    for token in ('hasStorageError', 'return fragmentView;'):
        assert token in chat_act, f'PengramHistoryChatActivity: нет {token}'
    assert 'archiveGuard' not in chat_act and 'pengramHistorySnapshot' not in chat_act, \
        'lock/флаг-в-MessageObject не должны попадать'

    # E. LocalKeep: синхронизация
    lk = read('TMessagesProj/src/main/java/org/telegram/messenger/PengramLocalKeep.java')
    assert 'synchronized (localOnly)' in lk, 'localOnly должен быть синхронизирован'
    assert lk.index('native_pauseNetwork(account)') < lk.index('localOnly.add(account)'), \
        'аккаунт помечается локальным только после успешной паузы сети'

    # F. Поиск: состояние загрузки локального архива
    ad = read('TMessagesProj/src/main/java/org/telegram/ui/Adapters/MessagesSearchAdapter.java')
    assert 'isLocalSearchLoading' in ad and 'localSearchLoading = !value.isEmpty();' in ad

    # G. Отображение в ячейке (send-файлы НЕ правим — метка и fade уже в мастере)
    cell = read('TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java')
    for token in ('getDeletedMarkIcon', 'pengramUpdateFade'):
        assert token in cell, f'ChatMessageCell: нет {token}'

    # H. Строки
    en = read('TMessagesProj/src/main/res/values/strings.xml')
    ru = read('TMessagesProj/src/main/res/values-ru/strings.xml')
    for name in ('PengramHistoryLoadMore', 'PengramHistoryReadFailed'):
        assert f'name="{name}"' in en, f'EN: нет {name}'
        assert f'name="{name}"' in ru, f'RU: нет {name}'

    # I. Лок/lock не подмешаны
    settings = read('TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java')
    assert 'KEY_HISTORY_LOCK' not in settings, 'lock-строка не должна попадать'
    assert 'PengramKeepDeleted' in settings, 'строка keep в настройках должна остаться'

    # J. Путь отправки байт-в-байт мастерский
    for path in SEND_FILES:
        res = subprocess.run(['git', 'diff', '--quiet', BASE, '--', path], cwd=ROOT)
        assert res.returncode == 0, f'{path} отличается от мастера'
    for path, mf in SEND_MARKER_FILES.items():
        text = (ROOT / path).read_text(encoding='utf-8')
        missing = [l for l in (ROOT / mf).read_text(encoding='utf-8').splitlines()
                   if l.strip() and not l.startswith('#') and l not in text]
        assert not missing, f'{path}: потеряны маркеры {mf}: {missing[:3]}'

    print('OK: удалённые сообщения — сохранение (v8+keepVisible) и отображение на месте, '
          'без lock и без правок send-файлов')
    return 0


if __name__ == '__main__':
    sys.exit(main())
