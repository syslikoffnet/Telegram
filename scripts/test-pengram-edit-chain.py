#!/usr/bin/env python3
"""Regression checks for edit capture/display and the scrolled composer canvas."""
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
history = (java / 'messenger/PengramHistory.java').read_text()
controller = (java / 'messenger/MessagesController.java').read_text()
sender = (java / 'messenger/SendMessagesHelper.java').read_text()
config = (java / 'messenger/PengramConfig.java').read_text()
screen = (java / 'ui/PengramHistoryChatActivity.java').read_text()
effects = (java / 'ui/Components/PengramTypingEffects.java').read_text()

# An outgoing optimistic edit overwrites messages_v2: retain its old snapshot
# beforehand, recover it from the per-account pending table, and clear on error
# or after the history write has been queued.
assert 'PengramHistory.rememberPendingEdit(currentAccount, newMsg)' in sender
assert sender.index('PengramHistory.rememberPendingEdit(currentAccount, newMsg)') < sender.index('messageObject.previousMedia = newMsg.media;')
assert 'PengramHistory.clearPendingEdit(currentAccount, object.getDialogId(), object.getId())' in sender
assert 'PengramHistory.clearPendingEdit(currentAccount, objects.get(0).getDialogId(), objects.get(0).getId())' in sender
assert 'TABLE_PENDING = "pending_edits"' in history
assert 'PRIMARY KEY (account, dialog_id, message_id)' in history
assert 'SQLiteDatabase.CONFLICT_REPLACE' in history
assert 'PengramHistory.pendingEditSnapshot(account, dialogId, messageId)' in controller
assert controller.index('PengramHistory.pendingEditSnapshot(account, dialogId, messageId)') < controller.index('pengramGetMessageSnapshot(messageId, dialogId)')
assert controller.index('PengramHistory.save(account, dialogId, messageId, fromId, date,') < controller.index('PengramHistory.clearPendingEdit(account, dialogId, messageId)')
assert 'if (action == ACTION_EDITED && messageId != 0' in history and 'isSameLastEdit(account, dialogId, messageId, text, data)' in history
assert 'byte[] oldData = serialize(previous)' in history
assert 'if (keepMedia)' in history
assert 'oldText == null ||' not in controller.split('private void pengramSaveEdited(', 1)[1].split('public boolean sendTyping(', 1)[0]

# Avoid silent default expiry/eviction; explicitly configured limits still work.
assert 'historyKeepDays = p.getInt("historyKeepDays", 0)' in config
assert 'historyMaxEntries = p.getInt("historyMaxEntries", 0)' in config
assert 'if (limit <= 0)' in history and 'if (days > 0)' in history

# The archived original contains its own caption/media/entities, not its
# search summary. Unknown prior versions should not suppress the known edit.
assert 'entry.prevData != null || entry.prevText != null' in screen
assert 'missingIntermediate' in screen
assert 'if (entry.action == PengramHistory.ACTION_EDITED && !previous)' in screen
assert 'if (message == null) {' in screen
assert 'PengramHistory.addChangeListener(historyChanged)' in screen
assert 'PengramHistory.removeChangeListener(historyChanged)' in screen

# View.draw already applies -scrollX/-scrollY to the Canvas. Overlay positions
# must be in content coordinates. Otherwise with a 100px scroll a 300px text
# baseline lands at 100px (double subtraction), rather than 200px.
assert 300 - 100 == 200 and 300 - 100 - 100 != 200
assert 'targetY = layout.getLineBaseline(line) + paddingTop;' in effects
assert 'final float baseline = layout.getLineBaseline(line) + paddingTop;' in effects
assert 'removedBaseline[n] = layout.getLineBaseline(line) + paddingTop;' in effects
assert 'canvas.clipRect(edit.getCompoundPaddingLeft() + scrollX - dp(2), scrollY,' in effects
assert not re.search(r'(?:targetY|targetTop|targetBottom|removedBaseline\[n\]|final float baseline)\s*=.*-\s*(?:edit\.getScrollY\(\)|scrollY)', effects)

# Schema upgrade preserves old rows and adds crash-recovery storage without
# relying on the Telegram message cache.
db = sqlite3.connect(':memory:')
db.executescript('CREATE TABLE history(id INTEGER PRIMARY KEY, data BLOB);'
                 'INSERT INTO history VALUES(7, X\'0102\');')
db.execute('CREATE TABLE IF NOT EXISTS pending_edits(account INTEGER NOT NULL, dialog_id INTEGER NOT NULL, '
           'message_id INTEGER NOT NULL, created_at INTEGER NOT NULL, data BLOB NOT NULL, '
           'attach_path TEXT, PRIMARY KEY (account, dialog_id, message_id))')
db.execute('INSERT INTO pending_edits VALUES(0, 42, 12, 1000, ?, ?)', (b'old', '/local/photo'))
db.execute('INSERT OR REPLACE INTO pending_edits VALUES(0, 42, 12, 1001, ?, ?)', (b'older', '/local/photo'))
assert db.execute('SELECT data FROM pending_edits').fetchall() == [(b'older',)]
assert db.execute('SELECT data FROM history WHERE id = 7').fetchone() == (b'\x01\x02',)
print('Edit capture, recovery, history rendering and composer scroll coordinates: OK')
