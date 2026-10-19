#!/usr/bin/env python3
"""Regression checks for archived-message search and newest-first paging.

Uses the same SQLite LIKE and ordering as PengramHistory; Android Java is compiled
by preflight CI, not by this host-side test.
"""
from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
history = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java').read_text()
screen = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramHistoryChatActivity.java').read_text()
adapter = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Adapters/MessagesSearchAdapter.java').read_text()
chat = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text()

assert 'text LIKE ? ESCAPE' in history and 'prev_text LIKE ? ESCAPE' in history
assert 'java.util.Collections.reverse(result)' in history
assert 'unicode ? "" : " LIMIT "' in history
assert 'generation != loadGeneration' in screen and 'reload(true)' in screen
assert 'getEntries(dialogId, filter, limit, query, singleMessageId, true, currentAccount)' in screen
assert 'OPTION_PENGRAM_EDIT_HISTORY' in chat and 'isEditHistoryMarkEnabled()' in chat
assert 'm.getDialogId() + ":" + m.getId()' in adapter
assert 'msg.message = "🗑 " + (matched == null ? "" : matched)' in adapter
# Local hits must not force Telegram's full-screen results list open while typing.
assert '() -> showMessagesSearchListView(true)' not in chat
assert 'getLocalDeletedAt' in adapter and 'localDeletedNavigation' in chat
assert 'hasLocalMessage(entry.dialogId, entry.messageId' in chat
assert 'navigateToDeletedSearchResult(deleted)' in chat
for locale in ('values', 'values-ru'):
    resources = ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml')
    assert any(x.get('name') == 'PengramVersionBeforeEdit' for x in resources.iter('string'))

conn = sqlite3.connect(':memory:')
conn.execute('CREATE TABLE archive (id INTEGER PRIMARY KEY, account INTEGER, dialog_id INTEGER, '
             'message_id INTEGER, date INTEGER, saved_at INTEGER, action INTEGER, text TEXT, prev_text TEXT)')
for i in range(1, 1102):
    conn.execute('INSERT INTO archive VALUES (?, 0, 42, ?, ?, ?, 0, ?, ?)',
                 (i, i, i, i, f'archive {i}', 'older' if i == 1101 else ''))
conn.execute('INSERT INTO archive VALUES (2000, 1, 42, 2000, 2000, 2000, 0, ?, "")', ('other account',))
conn.execute('INSERT INTO archive VALUES (2001, 0, 43, 2001, 2001, 2001, 0, ?, "")', ('other dialog',))
conn.execute('INSERT INTO archive VALUES (2002, 0, 42, 2002, 2002, 2002, 0, ?, "")', ('100%_ literal',))
conn.execute('INSERT INTO archive VALUES (2003, 0, 42, 2003, 2003, 2003, 0, ?, "")', ('ПрИвЕт',))

def page(needle, size):
    sql = 'SELECT id, text, prev_text FROM archive WHERE account=? AND dialog_id=? AND action=?'
    args = [0, 42, 0]
    unicode = any(ord(ch) > 127 for ch in needle)
    if needle and not unicode:
        escaped = needle.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
        sql += " AND (text LIKE ? ESCAPE '\\' OR prev_text LIKE ? ESCAPE '\\')"
        args += [f'%{escaped}%'] * 2
    sql += ' ORDER BY date DESC, message_id DESC, saved_at DESC, id DESC'
    if not unicode:
        sql += f' LIMIT {size}'
    rows = conn.execute(sql, args)
    if unicode:
        rows = (row for row in rows if needle.casefold() in (row[1] or '').casefold()
                or needle.casefold() in (row[2] or '').casefold())
    found = []
    for row in rows:
        found.append(row[0])
        if len(found) >= size:
            break
    return list(reversed(found))

assert page('', 500)[-1] == 2003 and page('', 500)[0] == 604
assert page('', 1000)[0] == 104  # expanding the page reveals older rows
assert page('%_', 500) == [2002]  # SQL wildcards are literal
assert page('older', 500) == [1101]  # search can match previous version
assert page('привет', 500) == [2003]  # Unicode case folding off SQLite's UI thread
assert page('other', 500) == []  # account and dialog isolation
print('Archived-message search and paging checks: OK')
