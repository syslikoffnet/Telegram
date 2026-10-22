#!/usr/bin/env python3
"""Production-source contracts + host SQLite experiments, not Android/device acceptance."""
from pathlib import Path
import json
import re
import sqlite3
import statistics
import time
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT/'TMessagesProj/src/main/java/org/telegram'
h = (JAVA/'messenger/PengramHistory.java').read_text()
c = (JAVA/'messenger/PengramConfig.java').read_text()
s = (JAVA/'messenger/MessagesStorage.java').read_text()
chat = (JAVA/'ui/ChatActivity.java').read_text()
a = (JAVA/'ui/PengramHistoryActivity.java').read_text()
lock = (JAVA/'ui/Components/PengramArchiveGuard.java').read_text()
local = (JAVA/'messenger/PengramLocalKeep.java').read_text()
assert 'DB_VERSION = 8' in h and 'if (oldVersion < 8)' in h
indexes = re.findall(r'db.execSQL\("(CREATE INDEX IF NOT EXISTS idx_history_[^"]+)"\)', h)
assert len(indexes) == 3 and all('(account,' in sql for sql in indexes)
assert 'setWriteAheadLoggingEnabled(true)' in h and 'PRAGMA synchronous = NORMAL' in h
assert 'Executors.newSingleThreadExecutor()' in h
save = h.split('private static void enqueueSave(',1)[1].split('private static boolean isSameLastEdit',1)[0]
assert 'db.beginTransaction()' in save and 'db.insertOrThrow(TABLE' in save
assert save.index('insertDeletedMark(db') < save.index('db.setTransactionSuccessful()') < save.index('db.endTransaction()') < save.index('publishDeletedMark(account')
assert 'isUserDeleted(messageId)' in save
marks = h.split('public static void markDeleted(',1)[1].split('/** быстрая проверка',1)[0]
assert marks.index('executor.execute(') < marks.index('hasEntry(account, dialogId, id)') < marks.index('insertDeletedMark(db, account')
assert 'AND EXISTS (SELECT 1 FROM history h' in h
assert 'isArchivableSnapshot(did, message, PengramHistory.ACTION_DELETED)' in s
assert 'saveDeletedSnapshot(currentAccount, dialog_id, obj.messageOwner)' in chat
keep = chat.split('private boolean pengramKeepDeletedMessages(',1)[1].split('private void pengramUnselect',1)[0]
assert 'obj.pengramDeleted = true' not in keep
assert 'message.ttl_period == 0' in h and 'message.media.ttl_seconds == 0' in h
assert '(action != ACTION_EDITED || !message.edit_hide)' in h
assert 'return isMarkingEdited() ? getEditedMarkIcon(getEditedMark()) : 0;' in c
for key in ('KEY_RESEND_AS_MINE','KEY_RESEND_MENU','isResendDeletedAsMine'):
    assert key not in c
signature=c.split('public static int chatLookSignature(',1)[1].split('return v;',1)[0]
for key in ('isMarkingEdited()', 'getEditedMark()', 'shouldHideEditedLabel()', 'isForceDeleteForAll()'):
    assert key in signature
for method in ('public static void clear(final', 'public static void deleteEntry(final'):
    block=h.split(method,1)[1].split('\n    public static',1)[0]
    assert 'collectMediaPaths(' in block and 'cleanupUnreferencedMedia(db, files)' in block
assert 'prev_media_path FROM history WHERE' in h and 'db.delete("saved_media", "path = ?"' in h
assert 'file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)' in h
assert 'PengramHistory.getEntries(' not in a and 'loadArchivePage(currentAccount' in a
assert 'PAGE_SIZE = 100' in a and 'totalEntries)' in a and 'loadNextPage();' in a
assert 'request != generation' in a and 'destroyed' in a and 'morePages' in a
search=h.split('public static ArrayList<Entry> searchDeleted(',1)[1].split('public interface PageCallback',1)[0]
assert 'unicode ? "" : " LIMIT' not in search
assert 'ORDER BY date DESC, id DESC LIMIT ' in search and 'date < ? OR (date = ? AND id < ?)' in search
assert 'unicode ? 256 : wanted' in search
assert local.index('if (localOnly.contains(account)) return true;') < local.index('native_pauseNetwork(account)')
assert 'protected boolean isAppLock() { return false; }' in lock
assert 'SharedConfig.passcodeHash.isEmpty()' in lock and 'passcode.setDelegate' in lock
assert 'FLAG_SECURE' in lock and 'lease.count' in lock
for name in ('PengramHistoryActivity','PengramHistoryChatActivity'):
    source=(JAVA/('ui/'+name+'.java')).read_text()
    for token in ('archiveGuard.resume()', 'archiveGuard.pause()', 'archiveGuard.destroy()'):
        assert token in source,(name,token)
manifest=(ROOT/'TMessagesProj/src/main/AndroidManifest.xml').read_text()
assert 'android:allowBackup="false"' in manifest and 'android:fullBackupContent="false"' in manifest
assert not re.search(r'FileLog\.[ed]\([^\n]*(?:\btext\b|\bprevText\b)',h)
for locale in ('values','values-ru'):
    xml=ET.parse(ROOT/'TMessagesProj/src/main/res'/locale/'strings.xml').getroot()
    for name in ('PengramHistoryReadFailed','PengramHistoryLoadMore','PengramHistoryPrivacyPolicy','PengramHistoryLock','PengramHistoryLockInfo','PengramHistorySetPasscode'):
        assert len(xml.findall(f"string[@name='{name}']"))==1,(locale,name)
# v1 -> v8 additive schema model, repeated migration, and duplicate revisions remain legal.
db=sqlite3.connect(':memory:')
db.execute('CREATE TABLE history(id INTEGER PRIMARY KEY,account INTEGER NOT NULL DEFAULT 0,dialog_id INTEGER,message_id INTEGER,from_id INTEGER,date INTEGER,saved_at INTEGER,action INTEGER,text TEXT,prev_text TEXT)')
db.execute("INSERT INTO history VALUES(1,0,42,7,5,10,20,0,'original',NULL)")
for col in ('data BLOB','out INTEGER NOT NULL DEFAULT 0','prev_data BLOB','prev_media_path TEXT'):
    db.execute('ALTER TABLE history ADD COLUMN '+col)
for _ in range(2):
    for sql in indexes: db.execute(sql)
assert db.execute('SELECT text FROM history WHERE id=1').fetchone()==('original',)
db.execute('CREATE TABLE deleted_marks(account INTEGER,dialog_id INTEGER,message_id INTEGER,date INTEGER,PRIMARY KEY(account,dialog_id,message_id))')
db.commit()
# Fault injection: failed mark aborts BOTH records, not only the marker.
db.execute("CREATE TRIGGER fail_mark BEFORE INSERT ON deleted_marks BEGIN SELECT RAISE(ABORT, 'disk error'); END")
try:
    with db:
        db.execute("INSERT INTO history(id,account,dialog_id,message_id,action,text) VALUES(2,0,42,8,0,'seen')")
        db.execute('INSERT INTO deleted_marks VALUES(0,42,8,1)')
except sqlite3.DatabaseError:pass
assert db.execute('SELECT count(*) FROM history WHERE id=2').fetchone()==(0,)
db.execute('DROP TRIGGER fail_mark')
with db:
    for id in (2,3):db.execute("INSERT INTO history(id,account,dialog_id,message_id,action,text) VALUES(?,0,42,8,1,'edit')",(id,))
assert db.execute('SELECT count(*) FROM history WHERE action=1').fetchone()==(2,)
# Equal dates: keyset pagination has no missing/duplicate rows, including Cyrillic hits beyond page one.
for i in range(4,20004):
    db.execute('INSERT INTO history(id,account,dialog_id,message_id,date,saved_at,action,text) VALUES(?,?,?,?,?,?,?,?)',(i,i%2,42,i,i//8,i,0,'ПРИВЕТ' if i%317==0 else 'message '+str(i)))
db.commit()
ids=[];before=2**63-1
while True:
    page=db.execute('SELECT id FROM history WHERE account=0 AND id<? ORDER BY id DESC LIMIT 100',(before,)).fetchall()
    if not page:break
    ids.extend(r[0] for r in page);before=page[-1][0]
assert len(ids)==len(set(ids))==db.execute('SELECT count(*) FROM history WHERE account=0').fetchone()[0]
# Numeric measurements are HOST SQLite, never Android latency guarantees.
def measure(sql,args):
    samples=[]
    for _ in range(7):
        t=time.perf_counter();db.execute(sql,args).fetchall();samples.append((time.perf_counter()-t)*1000)
    return round(statistics.median(samples),3)
queries=[('dialog_count','SELECT count(*) FROM history WHERE account=? AND dialog_id=?',(0,42)),('saved_page','SELECT id FROM history WHERE account=? ORDER BY saved_at DESC LIMIT 100',(0,)),('search_page','SELECT id FROM history WHERE account=? AND action=0 AND date<? ORDER BY date DESC LIMIT 256',(0,999999))]
after={name:measure(sql,args) for name,sql,args in queries}
for sql in indexes:db.execute('DROP INDEX '+sql.split()[5])
before={name:measure(sql,args) for name,sql,args in queries}
print('deleted/edited: source contracts, v1 additive model, rollback, revisions and 20k-row pagination PASS')
print('HOST SQLite medians ms (not device):',json.dumps({'before_indexes':before,'after_indexes':after}))
# Archive data must not enter the existing media retry paths.
export=(JAVA/'ui/Components/PengramChatExport.java').read_text()
visitor=export.split('PengramHistory.DeletedVisitor visitor =',1)[1].split('PengramHistory.forEachDeleted',1)[0]
assert 'attachment(message, true)' in visitor
assert 'if (!localOnly && (file == null' in export
copy=(JAVA/'messenger/PengramCopySender.java').read_text().split('private static void download(',1)[1]
assert copy.index('message.pengramHistorySnapshot') < copy.index('FileLoader.getInstance(account).loadFile')
loader=(JAVA/'messenger/FileLoader.java').read_text().split('private FileLoadOperation loadFileInternal(',1)[1]
assert 'snapshot.pengramHistorySnapshot || snapshot.pengramArchived || snapshot.pengramDeleted' in loader
settings=(JAVA/'ui/PengramSettingsActivity.java').read_text()
for name in re.findall(r'private static final int (BTN_HIST_\w+)\s*=',settings):
    assert 'case '+name+':' in settings,name
assert 'PRAGMA quick_check(1)' in h and 'original database retained for recovery' in h
assert '32 * 1024 * 1024 - read' in h
