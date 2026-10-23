#!/usr/bin/env python3
"""Offline regressions for account-scoped archive deletion and safe copy routing.

The SQLite exercise verifies the Java deletion predicates with colliding dialog
IDs. Static source checks protect the Android call sites until device tests run.
"""
from pathlib import Path
import re
import sqlite3
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main"
JAVA = ROOT / "java/org/telegram"
history = (JAVA / "messenger/PengramHistory.java").read_text()
archive = (JAVA / "ui/PengramHistoryActivity.java").read_text()
chat_archive = (JAVA / "ui/PengramHistoryChatActivity.java").read_text()
chat = (JAVA / "ui/ChatActivity.java").read_text()
settings = (JAVA / "ui/PengramSettingsActivity.java").read_text()
sender = (JAVA / "messenger/SendMessagesHelper.java").read_text()
alerts = (JAVA / "ui/Components/AlertsCreator.java").read_text()

marks = history.split("public static void clearMarks(final int account, final long dialogId)", 1)[1].split("public static", 1)[0]
clear = history.split("public static void clear(final int account, final long dialogId)", 1)[1].split("public static", 1)[0]
for block, table in ((marks, "marks"), (clear, "history")):
    assert f'delete(TABLE{"_MARKS" if table == "marks" else ""}, "account = ?"' in block
    assert f'delete(TABLE{"_MARKS" if table == "marks" else ""}, "account = ? AND dialog_id = ?"' in block
    assert "new String[]{String.valueOf(account), String.valueOf(dialogId)}" in block
    assert "new String[]{String.valueOf(account)}" in block
assert "marksCache.remove(account)" in marks
assert "accountMarks(account).remove(dialogId)" in marks
assert "PengramHistory.loadArchivePage(currentAccount, dialogId, filter, beforeId, PAGE_SIZE" in archive
assert "ArrayList<Entry> getEntries(long dialogId, int filter, int limit, String query, int messageId, boolean ascending, int account)" in history
assert "PengramHistory.getEntries(dialogId, filter, limit, query, singleMessageId, true, currentAccount)" in chat_archive
assert 'StringBuilder where = new StringBuilder("account = ?")' in history
assert "if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return result" in history
assert "args.add(String.valueOf(account));" in history  # выборка всегда привязана к аккаунту
assert "PengramHistory.deleteEntry(currentAccount, entry.rowId)" in archive
assert "PengramHistory.addChangeListener(historyChanged)" in archive
assert "PengramHistory.removeChangeListener(historyChanged)" in archive
assert "PengramHistory.addChangeListener(historyChanged)" in chat_archive
assert 'delete(TABLE, "account = ? AND id = ?"' in history
assert "picker.setCurrentAccount(currentAccount)" in chat_archive
assert "fragment.setCurrentAccount(currentAccount)" in chat
assert "detail.setCurrentAccount(currentAccount)" in archive and "detail.setCurrentAccount(currentAccount)" in chat_archive
assert "archive.setCurrentAccount(currentAccount)" in settings
assert "archive.setCurrentAccount(currentAccount)" in (JAVA / "ui/ProfileActivity.java").read_text()
for screen in (archive, chat_archive, settings, chat):
    assert re.search(r"PengramHistory\.clear\(currentAccount, (?:dialogId|0|getDialogId\(\))\)", screen)
assert not re.search(r"PengramHistory\.clear\([^,)]*\)", "\n".join((archive, chat_archive, settings, chat)))

# Colliding peer IDs are commonplace when several Telegram accounts are used.
for did in (42, 0):
    db = sqlite3.connect(":memory:")
    for table in ("history", "marks"):
        db.execute(f"CREATE TABLE {table} (account INTEGER, dialog_id INTEGER, payload TEXT)")
        for account in (0, 1):
            for dialog_id in (42, 43):
                db.execute(f"INSERT INTO {table} VALUES (?, ?, ?)", (account, dialog_id, "saved"))
        where = "account = ?" + (" AND dialog_id = ?" if did else "")
        db.execute(f"DELETE FROM {table} WHERE {where}", (0, 42) if did else (0,))
        remaining = db.execute(f"SELECT account, dialog_id FROM {table} ORDER BY account, dialog_id").fetchall()
        assert remaining == ([(0, 43)] if did else []) + [(1, 42), (1, 43)]
    db.close()

# Picker preflights every target BEFORE any send, respecting silent mode and
# refusing schedules, paid destinations, topics and unsent comments.
copy = chat_archive.split("private void forwardCopy(", 1)[1].split("private void openArchivedMedia(", 1)[0]
assert copy.index("for (MessagesStorage.TopicKey destination : dids)") < copy.index("PengramCopySender.sendCopies(")
assert "PengramCopySender.copyTargetError(currentAccount, destination.dialogId, scheduleDate, repeat)" in copy
assert "destination.topicId != 0 ? 25" in copy
assert "!TextUtils.isEmpty(text)" in copy
assert "PengramCopySender.sendCopies(currentAccount, copies, target, notify," in copy
assert "replyToTopMsg != null || monoForumPeerId != 0" in sender
assert "destination.topicId != 0 &&" in chat
assert "getTopicId() != 0 ? 25" in chat
assert "!TextUtils.isEmpty(message1)" in chat
for code, key in ((25, "PengramCopyCannotTopic"), (26, "PengramCopyCannotComment")):
    assert f"result == {code}" in alerts and f"R.string.{key}" in alerts
    for locale in ("values", "values-ru"):
        strings = ET.parse(ROOT / "res" / locale / "strings.xml").getroot()
        assert len(strings.findall(f"string[@name='{key}']")) == 1

print("Archive account isolation and copy-routing regressions passed")
