#!/usr/bin/env python3
"""Host-side regression checks for optional local pre-edit media snapshots."""
from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
history = (java / 'messenger/PengramHistory.java').read_text()
storage = (java / 'messenger/MessagesStorage.java').read_text()
controller = (java / 'messenger/MessagesController.java').read_text()
config = (java / 'messenger/PengramConfig.java').read_text()
screen = (java / 'ui/PengramHistoryChatActivity.java').read_text()
editor = (java / 'ui/PengramLayoutEditorActivity.java').read_text()
preview = (java / 'ui/Components/PengramInteractivePreviewView.java').read_text()
chat = (java / 'ui/ChatActivity.java').read_text()

assert 'DB_VERSION = 6' in history and 'if (oldVersion < 6)' in history
for column in ('prev_data BLOB', 'prev_media_path TEXT'):
    assert column in history
assert 'e.prevData = c.getBlob(12)' in history and 'e.prevMediaPath = c.getString(13)' in history
assert 'isEditedPhotoOrVideo(previous, message)' in controller
assert 'pengramGetMessageSnapshot(messageId, dialogId)' in controller
assert 'getStorageQueue().postRunnable(() -> {' in controller
assert 'SELECT data FROM messages_v2 WHERE mid = ? AND uid = ?' in storage
assert 'isSavingEditedMedia(account)' in controller and 'saveEditedMedia = false' in config
assert 'savePrivateCopy(source, filename, PengramConfig.getMediaFolder())' in history
assert 'isEditHistoryMarkEnabled() ? R.drawable.pengram_mark_pencil : 0' in config
assert 'message.attachPath = entry.prevMediaPath' in screen
assert '&& !PengramHistory.isPhotoOrVideo(message)' in screen
assert 'addItem(entry, entry.prevText, LocaleController.getString(R.string.PengramVersionOriginal), true)' in screen
assert 'OPTION_PENGRAM_EDIT_HISTORY' in chat and 'isEditHistoryMarkEnabled()' in chat
assert 'KEY_EDIT_HISTORY_MARK, true' in (java / 'ui/PengramSettingsActivity.java').read_text()
assert 'KEY_EDIT_HISTORY_MENU, false' in (java / 'ui/PengramSettingsActivity.java').read_text()
assert 'ItemOptions.makeOptions(this, actionBar)' in editor
assert 'visualDraft.save()' in editor and 'visualDraft.discard()' in editor
assert 'openSampleMenuItem(int id)' in preview
for locale in ('values', 'values-ru'):
    names = {e.get('name') for e in ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml').iter('string')}
    for name in ('PengramHistoryOpenMethod', 'PengramHistoryOpenPencil',
                 'PengramHistoryOpenLongPress', 'PengramHistoryEditMenu',
                 'PengramHistorySaveEditedMedia'):
        assert name in names, (locale, name)

# Version 5 already contains full-message data and sender metadata. The v6 upgrade
# must retain existing records while allowing original media and its durable path.
db = sqlite3.connect(':memory:')
db.executescript('CREATE TABLE history(id INTEGER PRIMARY KEY, account INTEGER, dialog_id INTEGER, '
                 'message_id INTEGER, action INTEGER, text TEXT, prev_text TEXT, data BLOB); '
                 'INSERT INTO history VALUES(1, 0, 42, 7, 1, "new", "old", X\'01\');')
db.execute('ALTER TABLE history ADD COLUMN prev_data BLOB')
db.execute('ALTER TABLE history ADD COLUMN prev_media_path TEXT')
assert db.execute('SELECT text, prev_text, prev_data FROM history').fetchone() == ('new', 'old', None)
db.execute('INSERT INTO history VALUES(2, 0, 42, 8, 1, "new", "old", ?, ?, ?)',
           (b'new', b'old-photo', '/private/old-photo.jpg'))
assert db.execute('SELECT prev_data, prev_media_path FROM history WHERE message_id=8').fetchone() == (
    b'old-photo', '/private/old-photo.jpg')
print('Edited-media archive and edit-history controls: OK')
