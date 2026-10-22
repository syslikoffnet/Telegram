package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.io.ByteArrayOutputStream;

/**
 * «Неубиваемое» локальное хранилище удалённых и отредактированных сообщений.
 * Лежит в отдельной базе в files/ (а не в кэше Telegram), поэтому
 * очистка кэша приложения его не трогает.
 */
public class PengramHistory extends SQLiteOpenHelper {

    public static final int ACTION_DELETED = 0;
    public static final int ACTION_EDITED = 1;

    public static final int FILTER_ALL = 0;
    public static final int FILTER_DELETED = 1;
    public static final int FILTER_EDITED = 2;

    private static final String DB_NAME = "pengram_history.db";
    private static final int DB_VERSION = 8;
    private static final String TABLE = "history";
    private static final String TABLE_MARKS = "deleted_marks";
    private static final String TABLE_PENDING = "pending_edits";
    private static final java.util.concurrent.ConcurrentHashMap<String, Entry> pendingEdits =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile PengramHistory instance;
    private static volatile boolean storageError;
    public static boolean hasStorageError() { return storageError; }
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> changeListeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    public static void addChangeListener(Runnable listener) {
        if (listener != null) changeListeners.add(listener);
    }

    public static void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean changePending = new java.util.concurrent.atomic.AtomicBoolean();
    private static void notifyChanged() {
        if (!changePending.compareAndSet(false, true)) return;
        AndroidUtilities.runOnUIThread(() -> {
            changePending.set(false);
            for (Runnable listener : changeListeners) {
                try { listener.run(); } catch (Throwable e) { FileLog.e(e); }
            }
        }, 16);
    }

    /**
     * Pengram: набор «удалено» поменялся. Открытый чат должен перекрасить
     * видимые сообщения сразу, а не после перезахода. Слушатели те же, что у
     * истории, поэтому отдельной подписки держать не нужно.
     */
    public static void notifyMarksChanged() {
        notifyChanged();
    }

    public static class Entry {
        public long rowId;
        public int account;
        public long dialogId;
        public int messageId;
        public long fromId;
        public int date;       // дата самого сообщения
        public int savedAt;    // когда мы это записали
        public int action;
        public String text;
        public String prevText;
        public boolean out;
        public byte[] data;    // сериализованное TLRPC.Message (может быть null)
        public byte[] prevData; // старая фото/видео-версия, если была доступна локально
        public String prevMediaPath; // постоянная копия старого вложения, если было в кэше
    }

    private PengramHistory(android.content.Context context) {
        super(context, DB_NAME, null, DB_VERSION, db -> {
            storageError = true;
            FileLog.e("Pengram archive corrupt; original database retained for recovery");
            notifyChanged();
        });
        setWriteAheadLoggingEnabled(true);
    }

    public static PengramHistory getInstance() {
        if (instance == null) {
            synchronized (PengramHistory.class) {
                if (instance == null && ApplicationLoader.applicationContext != null) {
                    instance = new PengramHistory(ApplicationLoader.applicationContext);
                }
            }
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account INTEGER NOT NULL DEFAULT 0," +
                "dialog_id INTEGER NOT NULL," +
                "message_id INTEGER NOT NULL," +
                "from_id INTEGER NOT NULL DEFAULT 0," +
                "date INTEGER NOT NULL DEFAULT 0," +
                "saved_at INTEGER NOT NULL DEFAULT 0," +
                "action INTEGER NOT NULL DEFAULT 0," +
                "text TEXT," +
                "prev_text TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dialog ON " + TABLE + " (dialog_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_saved ON " + TABLE + " (saved_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_message ON " + TABLE + " (dialog_id, message_id)");
        createV2(db);
        createV3(db);
        createV5(db);
        createV6(db);
        createV7(db);
        createV8(db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.execSQL("PRAGMA synchronous = NORMAL");
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        final android.content.SharedPreferences preferences = ApplicationLoader.applicationContext
                .getSharedPreferences("pengram_history_health", android.content.Context.MODE_PRIVATE);
        final int openings = preferences.getInt("openings", 0);
        preferences.edit().putInt("openings", openings + 1).apply();
        if (openings % 10 != 0) return;
        try (Cursor check = db.rawQuery("PRAGMA quick_check(1)", null)) {
            if (!check.moveToFirst() || !"ok".equals(check.getString(0))) {
                storageError = true;
                throw new android.database.sqlite.SQLiteDatabaseCorruptException("History integrity check failed; original retained");
            }
        }
    }

    /** Аддитивные индексы: открытие базы и миграция происходят на history executor. */
    private void createV8(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_account_dialog ON history (account, dialog_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_account_saved ON history (account, saved_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_account_action_date ON history (account, action, date)");
    }

    private void createV7(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PENDING + " (" +
                "account INTEGER NOT NULL, dialog_id INTEGER NOT NULL, message_id INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL, data BLOB NOT NULL, attach_path TEXT," +
                "PRIMARY KEY (account, dialog_id, message_id))");
    }

    private void createV6(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN prev_data BLOB");
        } catch (Throwable ignore) {}
        try {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN prev_media_path TEXT");
        } catch (Throwable ignore) {}
    }

    /** Legacy peer_meta had no account column; keep it untouched, never misattribute its rows. */
    private void createV5(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS peer_meta_account (" +
                "account INTEGER NOT NULL," +
                "peer_id INTEGER NOT NULL," +
                "last_online INTEGER NOT NULL DEFAULT 0," +
                "read_date INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (account, peer_id))");
    }

    private void createV3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MARKS + " (" +
                "account INTEGER NOT NULL DEFAULT 0," +
                "dialog_id INTEGER NOT NULL," +
                "message_id INTEGER NOT NULL," +
                "date INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (account, dialog_id, message_id))");
        try {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN data BLOB");
        } catch (Throwable ignore) {}
        try {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN out INTEGER NOT NULL DEFAULT 0");
        } catch (Throwable ignore) {}
    }

    private void createV2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS peer_meta (" +
                "peer_id INTEGER PRIMARY KEY," +
                "last_online INTEGER NOT NULL DEFAULT 0," +
                "read_date INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS saved_media (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "uri TEXT," +
                "path TEXT," +
                "size INTEGER NOT NULL DEFAULT 0," +
                "saved_at INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createV2(db);
        }
        if (oldVersion < 3) {
            createV3(db);
        }
        if (oldVersion < 5) {
            createV5(db);
        }
        if (oldVersion < 6) {
            createV6(db);
        }
        if (oldVersion < 7) {
            createV7(db);
        }
        if (oldVersion < 8) {
            createV8(db);
        }
        if (oldVersion < 4) {
            // старые записи лежат несжатыми — ужмём их в фоне и вернём место файлу
            pendingCompaction = true;
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_message ON " + TABLE + " (dialog_id, message_id)");
            } catch (Throwable ignore) {}
        }
    }

    // ------------------------------------------------------------------ запись

    private static String pendingKey(int account, long dialogId, int messageId) {
        return account + ":" + dialogId + ":" + messageId;
    }

    /** Capture our own edit BEFORE Telegram optimistically overwrites the local row. */
    public static void rememberPendingEdit(int account, TLRPC.Message message) {
        if (message == null || !isArchivableSnapshot(MessageObject.getDialogId(message), message, ACTION_EDITED)) return;
        final byte[] snapshot = serialize(message);
        final PengramHistory history = getInstance();
        if (snapshot == null || history == null) return;
        final long dialogId = MessageObject.getDialogId(message);
        final int messageId = message.id;
        final Entry pending = new Entry();
        pending.data = snapshot;
        pending.prevMediaPath = message.attachPath;
        pending.savedAt = (int) (System.currentTimeMillis() / 1000L);
        pendingEdits.put(pendingKey(account, dialogId, messageId), pending);
        executor.execute(() -> {
            try {
                ContentValues cv = new ContentValues();
                cv.put("account", account);
                cv.put("dialog_id", dialogId);
                cv.put("message_id", messageId);
                cv.put("created_at", pending.savedAt);
                cv.put("data", pack(snapshot));
                cv.put("attach_path", pending.prevMediaPath);
                history.getWritableDatabase().insertWithOnConflict(TABLE_PENDING, null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Available on the storage queue, even after a process restart during an edit. */
    public static TLRPC.Message pendingEditSnapshot(int account, long dialogId, int messageId) {
        final String key = pendingKey(account, dialogId, messageId);
        Entry pending = pendingEdits.get(key);
        if (pending == null) {
            final PengramHistory history = getInstance();
            if (history == null) return null;
            Cursor c = null;
            try {
                c = history.getReadableDatabase().rawQuery(
                        "SELECT created_at, data, attach_path FROM " + TABLE_PENDING
                                + " WHERE account = ? AND dialog_id = ? AND message_id = ?",
                        new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
                if (c.moveToFirst()) {
                    pending = new Entry();
                    pending.savedAt = c.getInt(0);
                    pending.data = c.getBlob(1);
                    pending.prevMediaPath = c.getString(2);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (c != null) c.close();
            }
        }
        // An abandoned/failed edit must not be mistaken for a much later edit.
        if (pending == null || System.currentTimeMillis() / 1000L - pending.savedAt > 86400) return null;
        TLRPC.Message result = deserialize(pending.data, account);
        if (result != null) result.attachPath = pending.prevMediaPath;
        return result;
    }

    public static void clearPendingEdit(int account, long dialogId, int messageId) {
        pendingEdits.remove(pendingKey(account, dialogId, messageId));
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                history.getWritableDatabase().delete(TABLE_PENDING,
                        "account = ? AND dialog_id = ? AND message_id = ?",
                        new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static void save(final int account, final long dialogId, final int messageId, final long fromId,
                            final int date, final int action, final String text, final String prevText) {
        save(account, dialogId, messageId, fromId, date, action, text, prevText, false, null);
    }

    public static void save(final int account, final long dialogId, final int messageId, final long fromId,
                            final int date, final int action, final String text, final String prevText,
                            final boolean out, final byte[] data) {
        save(account, dialogId, messageId, fromId, date, action, text, prevText, out, data, null);
    }

    public static void save(final int account, final long dialogId, final int messageId, final long fromId,
                            final int date, final int action, final String text, final String prevText,
                            final boolean out, final byte[] data, final TLRPC.Message previous) {
        save(account, dialogId, messageId, fromId, date, action, text, prevText, out, data, previous, false);
    }

    public static void save(final int account, final long dialogId, final int messageId, final long fromId,
                            final int date, final int action, final String text, final String prevText,
                            final boolean out, final byte[] data, final TLRPC.Message previous,
                            final boolean keepMedia) {
        try {
            enqueueSave(account, dialogId, messageId, fromId, date, action, text, prevText, out, data, previous, keepMedia);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void enqueueSave(final int account, final long dialogId, final int messageId, final long fromId,
                            final int date, final int action, final String text, final String prevText,
                            final boolean out, final byte[] data, final TLRPC.Message previous, final boolean keepMedia) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || dialogId == 0 || messageId <= 0 || DialogObject.isEncryptedDialog(dialogId)
                || isUserDeleted(messageId)) return;
        if (TextUtils.isEmpty(text) && TextUtils.isEmpty(prevText) && data == null && previous == null) {
            return;
        }
        final PengramHistory history = getInstance();
        if (history == null) {
            return;
        }
        executor.execute(() -> {
            try {
                if (isUserDeleted(messageId)) return;
                // Удаление одного и того же сообщения прилетает не один раз: из апдейта,
                // из локальной чистки, из перезагрузки диалога. Вторая копия ничего не
                // добавляет, а место занимает — поэтому для удалённых пишем один раз.
                if (action == ACTION_DELETED && messageId != 0 && hasEntry(account, dialogId, messageId)) {
                    markDeleted(account, dialogId, java.util.Collections.singletonList(messageId));
                    return;
                }
                if (action == ACTION_EDITED && messageId != 0
                        && isSameLastEdit(account, dialogId, messageId, text, data)) {
                    return; // duplicate update from the server or optimistic send
                }
                ContentValues cv = new ContentValues();
                cv.put("account", account);
                cv.put("dialog_id", dialogId);
                cv.put("message_id", messageId);
                cv.put("from_id", fromId);
                cv.put("date", date);
                cv.put("saved_at", (int) (System.currentTimeMillis() / 1000L));
                cv.put("action", action);
                cv.put("text", text);
                cv.put("prev_text", prevText);
                cv.put("out", out ? 1 : 0);
                if (data != null) {
                    cv.put("data", pack(data));
                }
                if (previous != null) {
                    // Copy the cached old attachment on the history executor, never while
                    // holding Telegram's storage queue. No server-side media is downloaded.
                    if (keepMedia) {
                        String savedPath = preserveEditedMedia(account, dialogId, previous);
                        if (savedPath != null) cv.put("prev_media_path", savedPath);
                    }
                    byte[] oldData = serialize(previous);
                    if (oldData != null) cv.put("prev_data", pack(oldData));
                }
                final SQLiteDatabase db = history.getWritableDatabase();
                db.beginTransaction();
                try {
                    if (db.insertOrThrow(TABLE, null, cv) < 0) throw new IllegalStateException("History insert failed");
                    if (action == ACTION_DELETED) insertDeletedMark(db, account, dialogId, messageId);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                // Только после COMMIT: при ENOSPC/rollback ложной метки не появится.
                if (action == ACTION_DELETED) publishDeletedMark(account, dialogId, messageId);
                maybeEnforceLimits();
                invalidateCounts(dialogId);
                notifyChanged();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static boolean isSameLastEdit(int account, long dialogId, int messageId,
                                          String text, byte[] data) {
        final PengramHistory history = getInstance();
        if (history == null) return false;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery(
                    "SELECT text, data FROM " + TABLE
                            + " WHERE account = ? AND dialog_id = ? AND message_id = ? AND action = ?"
                            + " ORDER BY id DESC LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(dialogId),
                            String.valueOf(messageId), String.valueOf(ACTION_EDITED)});
            if (!c.moveToFirst()) return false;
            if (data != null) {
                byte[] previous = c.getBlob(1);
                return previous != null && java.util.Arrays.equals(unpack(previous), data);
            }
            return c.isNull(1) && TextUtils.equals(c.getString(0), text);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** есть ли уже запись об удалении этого сообщения */
    private static boolean hasEntry(int account, long dialogId, int messageId) {
        final PengramHistory history = getInstance();
        if (history == null) {
            return false;
        }
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery(
                    "SELECT 1 FROM " + TABLE + " WHERE account = ? AND dialog_id = ? AND message_id = ? AND action = ? LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(dialogId),
                            String.valueOf(messageId), String.valueOf(ACTION_DELETED)});
            return c.moveToFirst();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignore) {}
            }
        }
    }

    /** сериализует сообщение целиком — чтобы потом показать его как настоящее */
    public static byte[] serialize(TLRPC.Message message) {
        if (message == null) {
            return null;
        }
        try {
            final org.telegram.tgnet.NativeByteBuffer buffer = new org.telegram.tgnet.NativeByteBuffer(message.getObjectSize());
            message.serializeToStream(buffer);
            final byte[] bytes = new byte[buffer.limit()];
            buffer.position(0);
            buffer.readBytes(bytes, false);
            buffer.reuse();
            return bytes;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static TLRPC.Message deserialize(byte[] stored) {
        return deserialize(stored, UserConfig.selectedAccount);
    }

    /** Deserialise against the specified account, even if the user switches accounts mid-export. */
    public static TLRPC.Message deserialize(byte[] stored, int account) {
        final byte[] data = unpack(stored);
        if (data == null || data.length == 0) {
            return null;
        }
        org.telegram.tgnet.NativeByteBuffer buffer = null;
        try {
            buffer = new org.telegram.tgnet.NativeByteBuffer(data.length);
            buffer.writeBytes(data);
            buffer.position(0);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false);
            if (message != null) {
                message.readAttachPath(buffer, UserConfig.getInstance(account).clientUserId);
            }
            return message;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (buffer != null) {
                try { buffer.reuse(); } catch (Throwable ignore) {}
            }
        }
    }

    // ------------------------------------------------- сжатие записей

    /**
     * Метка сжатой записи.
     *
     * Сериализованное сообщение начинается с номера TL-конструктора, и ни один
     * из них не начинается с этих четырёх байт — значит, по первым байтам всегда
     * понятно, сжата запись или досталась от старой версии. Поэтому старые
     * записи продолжают читаться как есть, без миграции «всё или ничего».
     */
    private static final byte[] PACK_MAGIC = {'P', 'G', 'Z', '1'};

    /** blob сообщения ужимается раза в два-три: это текст, имена и TL-поля */
    static byte[] pack(byte[] raw) {
        if (raw == null || raw.length < 64) {
            return raw;   // мелочь сжимать невыгодно: заголовок съест выигрыш
        }
        Deflater deflater = null;
        try {
            deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
            deflater.setInput(raw);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
            out.write(PACK_MAGIC, 0, PACK_MAGIC.length);
            final byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                final int written = deflater.deflate(buffer);
                if (written <= 0) {
                    break;
                }
                out.write(buffer, 0, written);
            }
            final byte[] packed = out.toByteArray();
            // если сжатие не помогло (уже сжатое видео в превью и т.п.) — храним как есть
            return packed.length < raw.length ? packed : raw;
        } catch (Throwable e) {
            FileLog.e(e);
            return raw;
        } finally {
            if (deflater != null) {
                try { deflater.end(); } catch (Throwable ignore) {}
            }
        }
    }

    static boolean isPacked(byte[] data) {
        if (data == null || data.length <= PACK_MAGIC.length) {
            return false;
        }
        for (int a = 0; a < PACK_MAGIC.length; a++) {
            if (data[a] != PACK_MAGIC[a]) {
                return false;
            }
        }
        return true;
    }

    static byte[] unpack(byte[] data) {
        if (!isPacked(data)) {
            return data;
        }
        Inflater inflater = null;
        try {
            inflater = new Inflater(true);
            inflater.setInput(data, PACK_MAGIC.length, data.length - PACK_MAGIC.length);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(65536, data.length));
            final byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                final int read = inflater.inflate(buffer);
                if (read <= 0 || out.size() > 32 * 1024 * 1024 - read) {
                    return null; // Оборванный поток или zip-bomb не превращаем в частичный TL-снимок.
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (inflater != null) {
                try { inflater.end(); } catch (Throwable ignore) {}
            }
        }
    }

    // ------------------------------------------------- метки «сообщение удалено»

    /** Account + dialog: IDs may coincide across logged-in accounts. */
    private static final android.util.SparseArray<android.util.LongSparseArray<java.util.HashSet<Integer>>> marksCache = new android.util.SparseArray<>();
    /**
     * Какие диалоги мы уже прочитали из базы. Отдельно от marksCache: кэш может
     * быть заполнен «на лету» (пришло удаление), и тогда раннее «ключ есть —
     * значит всё загружено» навсегда скрывало старые метки этого диалога.
     */
    private static final java.util.HashSet<String> marksLoadedDialogs = new java.util.HashSet<>();

    private static String marksKey(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    /** minSdk 23: без Collection.removeIf — обычный итератор */
    private static void forgetLoadedMarks(int account) {
        final String prefix = account + ":";
        java.util.Iterator<String> it = marksLoadedDialogs.iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    /** Called while holding marksCache's monitor; avoids allocating keys on every chat row. */
    private static android.util.LongSparseArray<java.util.HashSet<Integer>> accountMarks(int account) {
        android.util.LongSparseArray<java.util.HashSet<Integer>> marks = marksCache.get(account);
        if (marks == null) {
            marks = new android.util.LongSparseArray<>();
            marksCache.put(account, marks);
        }
        return marks;
    }

    // ------------------------------------------------------- счётчики без фризов
    // COUNT(*) по базе занимает десятки миллисекунд, а зовут его из onBindViewHolder
    // и при открытии меню. Поэтому наружу отдаём кэш, а базу опрашиваем в фоне.

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> countCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> countDirty = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.Set<String> countLoading = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static String countKey(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    /** Count for an account and dialog; account -1 and dialog 0 mean the whole archive. */
    public static int getCountCached(final int account, final long dialogId, final Runnable onUpdated) {
        final String key = countKey(account, dialogId);
        final Integer cached = countCache.get(key);
        if (cached == null || countDirty.contains(key)) {
            refreshCount(account, dialogId, key, onUpdated);
        }
        return cached == null ? 0 : cached;
    }

    private static void refreshCount(final int account, final long dialogId, final String key, final Runnable onUpdated) {
        if (getInstance() == null || !countLoading.add(key)) {
            return;
        }
        executor.execute(() -> {
            int value = 0;
            try {
                value = getCountForAccount(account, dialogId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Integer previous = countCache.put(key, value);
            countDirty.remove(key);
            countLoading.remove(key);
            if (onUpdated != null && (previous == null || previous != value)) {
                AndroidUtilities.runOnUIThread(onUpdated);
            }
        });
    }

    /** Record changes can affect a dialog count and the global total. */
    private static void invalidateCounts(long dialogId) {
        countDirty.addAll(countCache.keySet());
        statsDirty = true;
    }

    /** Снимок размеров хранилища для экрана настроек. Считается в фоне, экран не ждёт. */
    public static class Stats {
        public long databaseSize;
        public int totalEntries;
        public int mediaCount;
        public long mediaSize;
    }

    private static volatile Stats statsCache;
    private static volatile boolean statsDirty = true;
    private static volatile boolean statsLoading;

    public static Stats getStatsCached(final Runnable onUpdated) {
        final Stats cached = statsCache;
        if (cached == null || statsDirty) {
            refreshStats(onUpdated);
        }
        return cached == null ? new Stats() : cached;
    }

    private static void refreshStats(final Runnable onUpdated) {
        if (statsLoading) {
            return;
        }
        statsLoading = true;
        statsDirty = false;
        executor.execute(() -> {
            final Stats stats = new Stats();
            try {
                stats.databaseSize = getDatabaseSize();
                stats.totalEntries = getCount(0);
                stats.mediaCount = getSavedMediaCount();
                stats.mediaSize = getSavedMediaSize();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            statsCache = stats;
            statsLoading = false;
            if (onUpdated != null) {
                AndroidUtilities.runOnUIThread(onUpdated);
            }
        });
    }

    /**
     * Pengram: у ещё не отправленных сообщений номер отрицательный — это черновик
     * загрузки, а не настоящее сообщение собеседника. Такие в историю не берём.
     */
    public static boolean isRealId(Integer id) {
        return id != null && id > 0;
    }

    public static void markDeleted(final int account, final long dialogId, final java.util.Collection<Integer> ids) {
        try {
            if (ids == null || ids.isEmpty() || dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) return;
            final ArrayList<Integer> copy = new ArrayList<>(ids);
            final PengramHistory history = getInstance();
            if (history == null) return;
            executor.execute(() -> {
                try {
                    final ArrayList<Integer> committed = new ArrayList<>();
                    final SQLiteDatabase db = history.getWritableDatabase();
                    db.beginTransaction();
                    try {
                        for (Integer id : copy) {
                            // Нет снимка — нет обещания «сохранено». Никакого чтения БД на UI.
                            if (isRealId(id) && !isUserDeleted(id) && hasEntry(account, dialogId, id)) {
                                insertDeletedMark(db, account, dialogId, id);
                                committed.add(id);
                            }
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                    for (Integer id : committed) publishDeletedMark(account, dialogId, id);
                    if (!committed.isEmpty()) notifyMarksChanged();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void insertDeletedMark(SQLiteDatabase db, int account, long dialogId, int id) {
        ContentValues mark = new ContentValues();
        mark.put("account", account);
        mark.put("dialog_id", dialogId);
        mark.put("message_id", id);
        mark.put("date", (int) (System.currentTimeMillis() / 1000L));
        db.insertWithOnConflict(TABLE_MARKS, null, mark, SQLiteDatabase.CONFLICT_REPLACE);
        // insertWithOnConflict возвращает -1 вместо исключения на некоторых сбоях.
        if (android.database.DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM deleted_marks WHERE account=? AND dialog_id=? AND message_id=?",
                new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(id)}) != 1) {
            throw new IllegalStateException("History mark insert failed");
        }
    }

    private static void publishDeletedMark(int account, long dialogId, int id) {
        if (isUserDeleted(id)) return;
        synchronized (marksCache) {
            java.util.HashSet<Integer> set = accountMarks(account).get(dialogId);
            if (set == null) {
                set = new java.util.HashSet<>();
                accountMarks(account).put(dialogId, set);
            }
            set.add(id);
        }
        loadMarks(account, dialogId, null);
    }

    /** Секретные и самоуничтожающиеся сообщения не покидают своё исходное хранилище. */
    public static boolean isArchivableSnapshot(long dialogId, TLRPC.Message message, int action) {
        return message != null && message.id > 0 && !DialogObject.isEncryptedDialog(dialogId)
                && message.ttl_period == 0 && (message.media == null || message.media.ttl_seconds == 0)
                && (action != ACTION_EDITED || !message.edit_hide);
    }

    /** Снимок только из уже имеющейся памяти. Не загружает сообщения/медиа из сети. */
    public static void saveDeletedSnapshot(int account, long dialogId, TLRPC.Message message) {
        try {
            if (!isArchivableSnapshot(dialogId, message, ACTION_DELETED) || isUserDeleted(message.id)) return;
            final long from = message.from_id == null ? dialogId : DialogObject.getPeerDialogId(message.from_id);
            save(account, dialogId, message.id, from, message.date, ACTION_DELETED,
                    describe(message), null, message.out, serialize(message));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** быстрая проверка по кэшу; кэш подгружается в loadMarks() при открытии чата */
    public static boolean isMarkedDeleted(int account, long dialogId, int messageId) {
        if (dialogId == 0) {
            return false;
        }
        synchronized (marksCache) {
            java.util.HashSet<Integer> set = accountMarks(account).get(dialogId);
            return set != null && set.contains(messageId);
        }
    }

    /** успели ли подгрузить метки этого диалога (до этого «не удалено» ничего не значит) */
    public static boolean marksLoaded(int account, long dialogId) {
        synchronized (marksCache) {
            return marksLoadedDialogs.contains(marksKey(account, dialogId));
        }
    }

    public static boolean hasMarks(int account, long dialogId) {
        synchronized (marksCache) {
            java.util.HashSet<Integer> set = accountMarks(account).get(dialogId);
            return set != null && !set.isEmpty();
        }
    }

    /** подгружает метки диалога в память (вызывается при открытии чата) */
    public static void loadMarks(final int account, final long dialogId, final Runnable done) {
        if (dialogId == 0) {
            if (done != null) AndroidUtilities.runOnUIThread(done);
            return;
        }
        synchronized (marksCache) {
            if (marksLoadedDialogs.contains(marksKey(account, dialogId))) {
                if (done != null) AndroidUtilities.runOnUIThread(done);
                return;
            }
        }
        final PengramHistory history = getInstance();
        if (history == null) {
            // без базы меток нет, но вызывающая сторона не должна ждать вечно
            notifyMarksChanged();
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
            return;
        }
        executor.execute(() -> {
            final java.util.HashSet<Integer> set = new java.util.HashSet<>();
            Cursor c = null;
            try {
                c = history.getReadableDatabase().rawQuery(
                        "SELECT m.message_id FROM " + TABLE_MARKS + " m WHERE m.account = ? AND m.dialog_id = ?"
                                + " AND EXISTS (SELECT 1 FROM history h WHERE h.account=m.account"
                                + " AND h.dialog_id=m.dialog_id AND h.message_id=m.message_id AND h.action=0)",
                        new String[]{String.valueOf(account), String.valueOf(dialogId)});
                while (c.moveToNext()) {
                    set.add(c.getInt(0));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignore) {}
            }
            synchronized (marksCache) {
                java.util.HashSet<Integer> existing = accountMarks(account).get(dialogId);
                if (existing != null) {
                    set.addAll(existing);
                }
                accountMarks(account).put(dialogId, set);
                marksLoadedDialogs.add(marksKey(account, dialogId));
            }
            // метки появились — открытый чат должен перекраситься сразу
            notifyMarksChanged();
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    public static void unmarkDeleted(final int account, final long dialogId, final java.util.Collection<Integer> ids) {
        if (dialogId == 0 || ids == null || ids.isEmpty()) {
            return;
        }
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        synchronized (marksCache) {
            java.util.HashSet<Integer> set = accountMarks(account).get(dialogId);
            if (set != null) {
                set.removeAll(copy);
            }
        }
        notifyMarksChanged();
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                history.getWritableDatabase().delete(TABLE_MARKS,
                        "account = ? AND dialog_id = ? AND message_id IN (" + TextUtils.join(",", copy) + ")",
                        new String[]{String.valueOf(account), String.valueOf(dialogId)});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Clear only this account's deletion marks; dialog IDs repeat across accounts. */
    public static void clearMarks(final int account, final long dialogId) {
        synchronized (marksCache) {
            if (dialogId == 0) {
                marksCache.remove(account);
            } else {
                accountMarks(account).remove(dialogId);
            }
            if (dialogId == 0) {
                forgetLoadedMarks(account);
            } else {
                marksLoadedDialogs.remove(marksKey(account, dialogId));
            }
        }
        notifyMarksChanged();
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                if (dialogId == 0) {
                    history.getWritableDatabase().delete(TABLE_MARKS, "account = ?",
                            new String[]{String.valueOf(account)});
                } else {
                    history.getWritableDatabase().delete(TABLE_MARKS, "account = ? AND dialog_id = ?",
                            new String[]{String.valueOf(account), String.valueOf(dialogId)});
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Purge the logged-out account's captured messages before its slot is reused. */
    public static void clearAccount(int account) {
        synchronized (marksCache) {
            marksCache.remove(account);
            forgetLoadedMarks(account);
        }
        for (String key : pendingEdits.keySet()) {
            if (key.startsWith(account + ":")) pendingEdits.remove(key);
        }
        PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                SQLiteDatabase db = history.getWritableDatabase();
                String[] args = {String.valueOf(account)};
                final java.util.HashSet<String> files = collectMediaPaths(db, "account = ?", args);
                db.beginTransaction();
                try {
                    db.delete(TABLE, "account = ?", args);
                    db.delete(TABLE_MARKS, "account = ?", args);
                    db.delete(TABLE_PENDING, "account = ?", args);
                    db.delete("peer_meta_account", "account = ?", args);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                cleanupUnreferencedMedia(db, files);
                invalidateCounts(0);
                notifyChanged();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    // ------------------------------- «я удалил сам»: такие сообщения реально удаляем

    private static final java.util.HashMap<Integer, Long> userDeletedGuard = new java.util.HashMap<>();
    private static final java.util.HashMap<Integer, Long> saveForMyself = new java.util.HashMap<>();
    private static final long GUARD_TTL = 120_000L;
    /** «сохранить у себя» живёт дольше: сервер может прислать апдейт об удалении с задержкой */
    private static final long SAVE_TTL = 15 * 60_000L;

    /**
     * Пользователь сам удалил эти сообщения — удаляем по-настоящему.
     * Важно: это снимает прошлую пометку «сохранить у себя», иначе
     * повторное удаление уже сохранённой «удалёнки» ничего бы не сделало.
     */
    public static void guardUserDeleted(java.util.Collection<Integer> ids) {
        if (ids == null) return;
        final long now = System.currentTimeMillis();
        synchronized (userDeletedGuard) {
            cleanupGuard(now);
            for (Integer id : ids) {
                if (id != null) {
                    userDeletedGuard.put(id, now);
                    saveForMyself.remove(id);
                }
            }
        }
    }

    /** пользователь удалил, но попросил оставить копию у себя */
    public static void guardSaveForMyself(java.util.Collection<Integer> ids) {
        if (ids == null) return;
        final long now = System.currentTimeMillis();
        synchronized (userDeletedGuard) {
            cleanupGuard(now);
            for (Integer id : ids) {
                if (id != null) {
                    saveForMyself.put(id, now);
                    userDeletedGuard.remove(id);
                }
            }
        }
    }

    /** снять любые пометки (например, сообщение больше не существует) */
    public static void forgetGuard(java.util.Collection<Integer> ids) {
        if (ids == null) return;
        synchronized (userDeletedGuard) {
            for (Integer id : ids) {
                if (id != null) {
                    userDeletedGuard.remove(id);
                    saveForMyself.remove(id);
                }
            }
        }
    }

    private static void cleanupGuard(long now) {
        java.util.Iterator<java.util.Map.Entry<Integer, Long>> it = userDeletedGuard.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Integer, Long> e = it.next();
            if (now - e.getValue() > GUARD_TTL) {
                it.remove();
            }
        }
        java.util.Iterator<java.util.Map.Entry<Integer, Long>> it2 = saveForMyself.entrySet().iterator();
        while (it2.hasNext()) {
            java.util.Map.Entry<Integer, Long> e = it2.next();
            if (now - e.getValue() > SAVE_TTL) {
                it2.remove();
            }
        }
    }

    public static boolean isUserDeleted(int messageId) {
        synchronized (userDeletedGuard) {
            return userDeletedGuard.containsKey(messageId);
        }
    }

    public static boolean isSaveForMyself(int messageId) {
        synchronized (userDeletedGuard) {
            return saveForMyself.containsKey(messageId);
        }
    }

    /** решаем, оставлять ли сообщения в чате вместо удаления */
    public static boolean shouldKeep(int account, java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        boolean anyReal = false;
        for (Integer id : ids) {
            if (isRealId(id)) {
                anyReal = true;
                break;
            }
        }
        if (!anyReal) {
            // отменённая отправка: сообщения ещё не существовало, хранить нечего
            return false;
        }
        boolean anySaveForMyself = false;
        boolean anyUserDeleted = false;
        synchronized (userDeletedGuard) {
            cleanupGuard(System.currentTimeMillis());
            for (Integer id : ids) {
                if (!isRealId(id)) continue;
                if (saveForMyself.containsKey(id)) anySaveForMyself = true;
                if (userDeletedGuard.containsKey(id)) anyUserDeleted = true;
            }
        }
        // явное «удалить» всегда сильнее, чем прошлое «сохранить у себя»
        if (anyUserDeleted) {
            return false;
        }
        if (anySaveForMyself) {
            return true;
        }
        return PengramConfig.isKeepingDeletedInChat(account);
    }

    /**
     * Pengram: оставлять ли сообщения видимыми в чате.
     *
     * <p>Кроме «слежки» за удалёнными есть локальная копия: пока она включена,
     * ничего из уже увиденного не исчезает. Раньше два этих механизма жили
     * раздельно, и при включённой локальной копии апдейт об удалении вообще не
     * доходил до разбора — сообщение оставалось в чате, но без метки и без
     * прозрачности. Теперь оба идут одним путём: остаётся и помечается.
     *
     * <p>Явное «удалить у себя» по-прежнему сильнее любой настройки.
     */
    public static boolean keepInChat(int account, java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        boolean anyReal = false;
        for (Integer id : ids) {
            if (isRealId(id)) {
                anyReal = true;
                break;
            }
        }
        if (!anyReal) {
            return false;
        }
        synchronized (userDeletedGuard) {
            cleanupGuard(System.currentTimeMillis());
            for (Integer id : ids) {
                if (isRealId(id) && userDeletedGuard.containsKey(id)) {
                    return false;
                }
            }
        }
        return PengramLocalKeep.keepDeletedMessages() || shouldKeep(account, ids);
    }

    /**
     * Pengram: нужно ли вообще знать про удаления в этом аккаунте — то есть
     * читать и писать метки. Метки нужны и «слежке», и локальной копии.
     */
    public static boolean marksEnabled(int account) {
        return PengramLocalKeep.keepDeletedMessages() || PengramConfig.isSavingDeleted(account);
    }

    /** последний сохранённый вариант текста этого сообщения (для цепочки правок) */
    public static String getLastKnownText(int account, long dialogId, int messageId) {
        final PengramHistory history = getInstance();
        if (history == null) return null;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery(
                    "SELECT text FROM " + TABLE + " WHERE account = ? AND dialog_id = ? AND message_id = ? ORDER BY id DESC LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return null;
    }

    // ------------------------------------------------------------------ чтение

    /** Lightweight search for the ordinary chat search: scoped to account and dialog(s).
     * No media BLOB is read or decoded on the UI thread. SQL wildcards are literal. */
    public static ArrayList<Entry> searchDeleted(int account, long dialogId, long mergeDialogId, String query, int limit) {
        ArrayList<Entry> result = new ArrayList<>();
        PengramHistory history = getInstance();
        if (history == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || dialogId == 0 || TextUtils.isEmpty(query)) return result;
        final int wanted = Math.max(1, Math.min(200, limit));
        final String needle = query.toLowerCase(Locale.ROOT);
        final String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        boolean unicode = false;
        for (int i = 0; i < query.length(); i++) {
            if (query.charAt(i) > 127) { unicode = true; break; }
        }
        long beforeId = Long.MAX_VALUE;
        int beforeDate = Integer.MAX_VALUE;
        try {
            boolean more;
            do {
                int scanned = 0;
                final ArrayList<String> args = new ArrayList<>();
                args.add(String.valueOf(account));
                args.add(String.valueOf(dialogId));
                args.add(String.valueOf(mergeDialogId));
                args.add(String.valueOf(ACTION_DELETED));
                args.add(String.valueOf(beforeDate));
                args.add(String.valueOf(beforeDate));
                args.add(String.valueOf(beforeId));
                if (!unicode) {
                    args.add("%" + escaped + "%");
                    args.add("%" + escaped + "%");
                }
                final int page = unicode ? 256 : wanted;
                final String sql = "SELECT id, account, dialog_id, message_id, from_id, date, saved_at, action, text, prev_text, out "
                        + "FROM history WHERE account = ? AND (dialog_id = ? OR dialog_id = ?) AND action = ? "
                        + "AND (date < ? OR (date = ? AND id < ?)) "
                        + (unicode ? "" : "AND (text LIKE ? ESCAPE '\\' OR prev_text LIKE ? ESCAPE '\\') ")
                        + "ORDER BY date DESC, id DESC LIMIT " + page;
                try (Cursor c = history.getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
                    while (c.moveToNext() && result.size() < wanted) {
                        scanned++;
                        beforeDate = c.getInt(5);
                        beforeId = c.getLong(0);
                        String text = c.getString(8), previous = c.getString(9);
                        if (unicode && !(text != null && text.toLowerCase(Locale.ROOT).contains(needle))
                                && !(previous != null && previous.toLowerCase(Locale.ROOT).contains(needle))) continue;
                        Entry e = new Entry();
                        e.rowId = c.getLong(0); e.account = c.getInt(1); e.dialogId = c.getLong(2);
                        e.messageId = c.getInt(3); e.fromId = c.getLong(4); e.date = c.getInt(5);
                        e.savedAt = c.getInt(6); e.action = c.getInt(7); e.text = text;
                        e.prevText = previous; e.out = c.getInt(10) != 0;
                        result.add(e);
                    }
                }
                more = unicode && scanned == page && result.size() < wanted;
            } while (more);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    public interface PageCallback {
        void loaded(ArrayList<Entry> entries, int total, boolean failed);
    }

    /** Ограниченная страница лёгких строк: blob не читается для глобального списка. */
    public static void loadArchivePage(int account, long dialogId, int filter, long beforeId, int limit, PageCallback callback) {
        executor.execute(() -> {
            final ArrayList<Entry> rows = new ArrayList<>();
            int total = 0;
            boolean failed = false;
            try {
                final PengramHistory history = getInstance();
                if (history == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new IllegalArgumentException("History account unavailable");
                final SQLiteDatabase db = history.getReadableDatabase();
                String where = "account = ?";
                final ArrayList<String> args = new ArrayList<>();
                args.add(String.valueOf(account));
                if (dialogId != 0) { where += " AND dialog_id = ?"; args.add(String.valueOf(dialogId)); }
                if (filter == FILTER_DELETED || filter == FILTER_EDITED) {
                    where += " AND action = ?";
                    args.add(String.valueOf(filter == FILTER_DELETED ? ACTION_DELETED : ACTION_EDITED));
                }
                total = (int) android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM history WHERE " + where, args.toArray(new String[0]));
                where += " AND id < ?"; args.add(String.valueOf(beforeId));
                try (Cursor c = db.rawQuery("SELECT id, account, dialog_id, message_id, from_id, date, saved_at, action, text, prev_text, out FROM history WHERE "
                        + where + " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(200, limit)), args.toArray(new String[0]))) {
                    while (c.moveToNext()) {
                        Entry e = new Entry();
                        e.rowId = c.getLong(0); e.account = c.getInt(1); e.dialogId = c.getLong(2);
                        e.messageId = c.getInt(3); e.fromId = c.getLong(4); e.date = c.getInt(5);
                        e.savedAt = c.getInt(6); e.action = c.getInt(7); e.text = c.getString(8);
                        e.prevText = c.getString(9); e.out = c.getInt(10) != 0; rows.add(e);
                    }
                }
            } catch (Throwable e) { FileLog.e(e); failed = true; }
            final int count = total;
            final boolean error = failed;
            AndroidUtilities.runOnUIThread(() -> callback.loaded(rows, count, error));
        });
    }

    /** Read one archived search hit, including its original TL snapshot, on a worker. */
    public static Entry getDeletedEntry(int account, long rowId) {
        PengramHistory history = getInstance();
        if (history == null || rowId <= 0 || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return null;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery(
                    "SELECT id, account, dialog_id, message_id, from_id, date, saved_at, action, text, prev_text, out, data, prev_data, prev_media_path " +
                            "FROM " + TABLE + " WHERE account = ? AND id = ? AND action = ? LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(rowId), String.valueOf(ACTION_DELETED)});
            if (!c.moveToFirst()) return null;
            Entry e = new Entry();
            e.rowId = c.getLong(0);
            e.account = c.getInt(1);
            e.dialogId = c.getLong(2);
            e.messageId = c.getInt(3);
            e.fromId = c.getLong(4);
            e.date = c.getInt(5);
            e.savedAt = c.getInt(6);
            e.action = c.getInt(7);
            e.text = c.getString(8);
            e.prevText = c.getString(9);
            e.out = c.getInt(10) != 0;
            e.data = c.getBlob(11);
            e.prevData = c.getBlob(12);
            e.prevMediaPath = c.getString(13);
            return e;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Stream deleted snapshots for one account/dialog without materialising the whole history. */
    public interface DeletedVisitor {
        void visit(Entry entry) throws Exception;
    }

    public static void forEachDeleted(int account, long dialogId, DeletedVisitor visitor) throws Exception {
        PengramHistory history = getInstance();
        if (history == null) return;
        long lastRow = 0;
        while (true) {
            // Release the SQLite cursor before the visitor downloads media. Holding a read
            // transaction across a long download would block local history writes.
            ArrayList<Entry> batch = new ArrayList<>(64);
            Cursor c = null;
            try {
                c = history.getReadableDatabase().rawQuery(
                        "SELECT id, account, dialog_id, message_id, from_id, date, saved_at, action, text, prev_text, out, data FROM " + TABLE +
                        " WHERE account = ? AND dialog_id = ? AND action = ? AND id > ? ORDER BY id ASC LIMIT 64",
                        new String[]{String.valueOf(account), String.valueOf(dialogId),
                                String.valueOf(ACTION_DELETED), String.valueOf(lastRow)});
                while (c.moveToNext()) {
                    Entry e = new Entry();
                    e.rowId = c.getLong(0);
                    e.account = c.getInt(1);
                    e.dialogId = c.getLong(2);
                    e.messageId = c.getInt(3);
                    e.fromId = c.getLong(4);
                    e.date = c.getInt(5);
                    e.savedAt = c.getInt(6);
                    e.action = c.getInt(7);
                    e.text = c.getString(8);
                    e.prevText = c.getString(9);
                    e.out = c.getInt(10) != 0;
                    e.data = c.getBlob(11);
                    batch.add(e);
                }
            } finally {
                if (c != null) c.close();
            }
            if (batch.isEmpty()) break;
            for (Entry entry : batch) visitor.visit(entry);
            lastRow = batch.get(batch.size() - 1).rowId;
        }
    }

    /** Archive queries always belong to one Telegram account. */
    public static ArrayList<Entry> getEntries(long dialogId, int filter, int limit, String query, int messageId, boolean ascending, int account) {
        ArrayList<Entry> result = new ArrayList<>();
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return result;
        final PengramHistory history = getInstance();
        if (history == null) return result;
        Cursor c = null;
        try {
            StringBuilder where = new StringBuilder("account = ?");
            ArrayList<String> args = new ArrayList<>();
            args.add(String.valueOf(account));
            if (dialogId != 0) {
                if (where.length() > 0) where.append(" AND ");
                where.append("dialog_id = ?");
                args.add(String.valueOf(dialogId));
            }
            if (filter == FILTER_DELETED || filter == FILTER_EDITED) {
                if (where.length() > 0) where.append(" AND ");
                where.append("action = ?");
                args.add(String.valueOf(filter == FILTER_DELETED ? ACTION_DELETED : ACTION_EDITED));
            }
            if (messageId != 0) {
                if (where.length() > 0) where.append(" AND ");
                where.append("message_id = ?");
                args.add(String.valueOf(messageId));
            }
            boolean unicode = false;
            if (!TextUtils.isEmpty(query)) {
                for (int i = 0; i < query.length(); i++) {
                    if (query.charAt(i) > 127) { unicode = true; break; }
                }
                if (!unicode) {
                    if (where.length() > 0) where.append(" AND ");
                    where.append("(text LIKE ? ESCAPE '\\' OR prev_text LIKE ? ESCAPE '\\')");
                    String escaped = query.replace("\\", "\\\\").replace("%", "\\%")
                            .replace("_", "\\_");
                    args.add("%" + escaped + "%");
                    args.add("%" + escaped + "%");
                }
            }
            // Select the newest matching snapshots first. A simple ascending LIMIT used to
            // discard recent deletions as soon as the history exceeded one page.
            String order = messageId != 0 ? "saved_at DESC, id DESC"
                    : "date DESC, message_id DESC, saved_at DESC, id DESC";
            String sql = "SELECT id, account, dialog_id, message_id, from_id, date, saved_at, action, text, prev_text, out, data, prev_data, prev_media_path FROM " + TABLE +
                    (where.length() > 0 ? (" WHERE " + where) : "") +
                    " ORDER BY " + order + (unicode ? "" : " LIMIT " + Math.max(1, limit));
            c = history.getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            String needle = unicode ? query.toLowerCase(Locale.ROOT) : null;
            while (c.moveToNext() && result.size() < Math.max(1, limit)) {
                // SQLite LIKE case-folds ASCII only. Filter other scripts in Java on this
                // background reader; apply the page limit to matches, not database rows.
                if (unicode && !(c.getString(8) != null && c.getString(8).toLowerCase(Locale.ROOT).contains(needle))
                        && !(c.getString(9) != null && c.getString(9).toLowerCase(Locale.ROOT).contains(needle))) {
                    continue;
                }
                Entry e = new Entry();
                e.rowId = c.getLong(0);
                e.account = c.getInt(1);
                e.dialogId = c.getLong(2);
                e.messageId = c.getInt(3);
                e.fromId = c.getLong(4);
                e.date = c.getInt(5);
                e.savedAt = c.getInt(6);
                e.action = c.getInt(7);
                e.text = c.getString(8);
                e.prevText = c.getString(9);
                e.out = c.getInt(10) != 0;
                try {
                    e.data = c.getBlob(11);
                } catch (Throwable ignore) {
                    e.data = null;
                }
                e.prevData = c.getBlob(12);
                e.prevMediaPath = c.getString(13);
                result.add(e);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        if (ascending) {
            java.util.Collections.reverse(result);
        }
        return result;
    }

    /** сколько раз правили конкретное сообщение */
    public static int getEditCount(long dialogId, int messageId) {
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE dialog_id = ? AND message_id = ? AND action = " + ACTION_EDITED,
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)});
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return 0;
    }

    public static int getCountForAccount(int account, long dialogId) {
        if (account < 0) return dialogId == 0 ? getCount(0) : 0;
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        Cursor c = null;
        try {
            if (dialogId != 0) {
                c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE account = ? AND dialog_id = ?",
                        new String[]{String.valueOf(account), String.valueOf(dialogId)});
            } else {
                c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE account = ?",
                        new String[]{String.valueOf(account)});
            }
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
    }

    public static int getCount(long dialogId) {
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        Cursor c = null;
        try {
            if (dialogId != 0) return getCountForAccount(UserConfig.selectedAccount, dialogId);
            c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return 0;
    }

    /** сколько сохранено записей нужного типа (ACTION_DELETED / ACTION_EDITED) */
    public static int getCount(long dialogId, int action) {
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        Cursor c = null;
        try {
            if (dialogId != 0) {
                c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE account = ? AND dialog_id = ? AND action = ?",
                        new String[]{String.valueOf(UserConfig.selectedAccount), String.valueOf(dialogId), String.valueOf(action)});
            } else {
                c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE action = ?",
                        new String[]{String.valueOf(action)});
            }
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return 0;
    }

    /** Глобальное обслуживание после глобального лимита; не выборка чужого чата в UI. */
    private static void reconcilePrunedMarks(SQLiteDatabase db) {
        db.execSQL("DELETE FROM deleted_marks WHERE NOT EXISTS (SELECT 1 FROM history h"
                + " WHERE h.account=deleted_marks.account AND h.dialog_id=deleted_marks.dialog_id"
                + " AND h.message_id=deleted_marks.message_id AND h.action=0)");
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) forgetCommittedMarks(account, 0);
        notifyChanged();
    }

    /** сколько файлов лежит в сохранённых медиа */
    public static int getSavedMediaCount() {
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM saved_media", null);
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return 0;
    }

    // ------------------------------------------------------------------ очистка

    /** удаляет записи старше указанного числа дней; 0 — ничего не трогаем */
    public static int deleteOlderThan(final int days) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            executor.execute(() -> deleteOlderThan(days));
            return 0;
        }
        if (days <= 0) {
            return 0;
        }
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        final int edge = (int) (System.currentTimeMillis() / 1000L) - days * 86400;
        int removed = 0;
        try {
            removed = history.getWritableDatabase().delete(TABLE, "saved_at > 0 AND saved_at < ?", new String[]{String.valueOf(edge)});
            invalidateCounts(0);
            if (removed > 0) reconcilePrunedMarks(history.getWritableDatabase());
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return removed;
    }

    /** фоновая автоочистка: сначала по сроку хранения, потом по числу записей */
    public static void autoCleanup() {
        final int days = PengramConfig.getHistoryKeepDays();
        executor.execute(() -> {
            if (days > 0) {
                final int removed = deleteOlderThan(days);
                if (BuildVars.LOGS_ENABLED && removed > 0) {
                    FileLog.d("pengram: автоочистка истории — удалено " + removed + " записей старше " + days + " дней");
                }
            }
            enforceEntryLimit();
            enforceMediaLimit();
        });
    }

    // ------------------------------------------------- уплотнение файла базы

    /** после обновления схемы в базе лежат несжатые записи — их нужно ужать один раз */
    private static volatile boolean pendingCompaction;
    /** VACUUM — дорогая операция, чаще раза в сутки она не нужна */
    private static final long VACUUM_INTERVAL = 24 * 60 * 60 * 1000L;
    private static long lastVacuum;

    /**
     * Разовое уплотнение после обновления.
     *
     * Ничего не выбрасываем: старые записи читаются, ужимаются и кладутся
     * обратно. Делается пачками в фоновом потоке, чтобы не держать базу
     * заблокированной надолго, а в конце — VACUUM: без него SQLite оставляет
     * освободившиеся страницы внутри файла, и «похудевшая» база на диске
     * занимает столько же, сколько занимала.
     */
    public static void compactIfNeeded() {
        final PengramHistory history = getInstance();
        if (history == null) {
            return;
        }
        executor.execute(() -> {
            try {
                history.getWritableDatabase();   // поднимаем базу: onUpgrade выставит флаг
            } catch (Throwable e) {
                FileLog.e(e);
                return;
            }
            if (!pendingCompaction) {
                return;
            }
            pendingCompaction = false;
            final long before = getDatabaseSize();
            int packed = 0;
            try {
                final SQLiteDatabase db = history.getWritableDatabase();
                while (true) {
                    final ArrayList<long[]> ids = new ArrayList<>();
                    final ArrayList<byte[]> blobs = new ArrayList<>();
                    Cursor c = null;
                    try {
                        c = db.rawQuery("SELECT id, data FROM " + TABLE
                                + " WHERE data IS NOT NULL AND id > ? ORDER BY id LIMIT 200",
                                new String[]{String.valueOf(compactCursor)});
                        while (c.moveToNext()) {
                            ids.add(new long[]{c.getLong(0)});
                            blobs.add(c.getBlob(1));
                        }
                    } finally {
                        if (c != null) {
                            try { c.close(); } catch (Throwable ignore) {}
                        }
                    }
                    if (ids.isEmpty()) {
                        break;
                    }
                    db.beginTransaction();
                    try {
                        for (int a = 0; a < ids.size(); a++) {
                            final long rowId = ids.get(a)[0];
                            compactCursor = rowId;
                            final byte[] raw = blobs.get(a);
                            if (raw == null || isPacked(raw)) {
                                continue;
                            }
                            final byte[] small = pack(raw);
                            if (small == raw || small.length >= raw.length) {
                                continue;
                            }
                            final ContentValues cv = new ContentValues();
                            cv.put("data", small);
                            db.update(TABLE, cv, "id = ?", new String[]{String.valueOf(rowId)});
                            packed++;
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            vacuum(true);
            statsDirty = true;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("pengram: журнал уплотнён, сжато записей " + packed
                        + ", файл " + before + " → " + getDatabaseSize());
            }
        });
    }

    /** id последней обработанной записи — чтобы пачки не пересекались */
    private static volatile long compactCursor;

    /** вернуть файлу базы освободившиеся страницы */
    static void vacuum(boolean force) {
        final PengramHistory history = getInstance();
        if (history == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastVacuum < VACUUM_INTERVAL) return;
        try {
            final File database = ApplicationLoader.applicationContext.getDatabasePath(DB_NAME);
            if (database.getUsableSpace() < Math.max(32L * 1024 * 1024, database.length() * 2)) return;
            final android.content.Intent battery = ApplicationLoader.applicationContext.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0 && level * 100 / scale < 15
                        && battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) == 0) return;
            }
            lastVacuum = now;
            history.getWritableDatabase().execSQL("VACUUM");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** сколько записей журнала осталось после прошлой проверки — чтобы не считать на каждое сохранение */
    private static volatile int entriesSinceCheck;

    /**
     * Потолок по числу записей: журнал ведёт себя как кольцевой буфер —
     * когда записей больше лимита, самые старые уходят. Иначе база растёт вечно.
     */
    public static void enforceEntryLimit() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            executor.execute(PengramHistory::enforceEntryLimit);
            return;
        }
        final int limit = PengramConfig.getHistoryMaxEntries();
        if (limit <= 0) {
            return;
        }
        final PengramHistory history = getInstance();
        if (history == null) return;
        try {
            final int total = getCount(0);
            if (total <= limit) {
                return;
            }
            // оставляем ровно limit самых свежих записей
            final int removed = history.getWritableDatabase().delete(TABLE,
                    "id NOT IN (SELECT id FROM " + TABLE + " ORDER BY id DESC LIMIT " + limit + ")", null);
            invalidateCounts(0);
            statsDirty = true;
            if (removed > 0) {
                reconcilePrunedMarks(history.getWritableDatabase());
                vacuum(false);
            }
            if (BuildVars.LOGS_ENABLED && removed > 0) {
                FileLog.d("pengram: журнал подрезан до " + limit + " записей, удалено " + removed);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** вызывается после сохранения: раз в сотню записей проверяем, не пора ли подрезать */
    private static void maybeEnforceLimits() {
        if (++entriesSinceCheck < 100) {
            return;
        }
        entriesSinceCheck = 0;
        executor.execute(() -> {
            enforceEntryLimit();
            final int days = PengramConfig.getHistoryKeepDays();
            if (days > 0) {
                deleteOlderThan(days);
            }
        });
    }

    public static void clear(final int account, final long dialogId) {
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                SQLiteDatabase db = history.getWritableDatabase();
                final String where = dialogId == 0 ? "account = ?" : "account = ? AND dialog_id = ?";
                final String[] args = dialogId == 0 ? new String[]{String.valueOf(account)}
                        : new String[]{String.valueOf(account), String.valueOf(dialogId)};
                final java.util.HashSet<String> files = collectMediaPaths(db, where, args);
                db.beginTransaction();
                try {
                    if (dialogId == 0) {
                        db.delete(TABLE, "account = ?", new String[]{String.valueOf(account)});
                    } else {
                        db.delete(TABLE, "account = ? AND dialog_id = ?", new String[]{String.valueOf(account), String.valueOf(dialogId)});
                    }
                    db.delete(TABLE_MARKS, where, args);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                forgetCommittedMarks(account, dialogId);
                cleanupUnreferencedMedia(db, files);
                invalidateCounts(dialogId);
                notifyChanged();
            } catch (Throwable e) { FileLog.e(e); }
        });
    }

    public static void deleteEntry(final int account, final long rowId) {
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                SQLiteDatabase db = history.getWritableDatabase();
                final String[] args = {String.valueOf(account), String.valueOf(rowId)};
                final java.util.HashSet<String> files = collectMediaPaths(db, "account = ? AND id = ?", args);
                db.beginTransaction();
                try {
                    db.delete(TABLE, "account = ? AND id = ?", args);
                    // Только запись ACTION_DELETED даёт право на метку, не история правок.
                    db.execSQL("DELETE FROM deleted_marks WHERE account = ? AND NOT EXISTS"
                            + " (SELECT 1 FROM history h WHERE h.account=deleted_marks.account"
                            + " AND h.dialog_id=deleted_marks.dialog_id AND h.message_id=deleted_marks.message_id AND h.action=0)",
                            new Object[]{account});
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                forgetCommittedMarks(account, 0);
                cleanupUnreferencedMedia(db, files);
                invalidateCounts(0);
                notifyChanged();
            } catch (Throwable e) { FileLog.e(e); }
        });
    }

    private static void forgetCommittedMarks(int account, long dialogId) {
        final ArrayList<Long> reload = new ArrayList<>();
        synchronized (marksCache) {
            if (dialogId == 0) {
                android.util.LongSparseArray<java.util.HashSet<Integer>> old = accountMarks(account);
                for (int i = 0; i < old.size(); i++) reload.add(old.keyAt(i));
                marksCache.remove(account);
                forgetLoadedMarks(account);
            } else {
                reload.add(dialogId);
                accountMarks(account).remove(dialogId);
                marksLoadedDialogs.remove(marksKey(account, dialogId));
            }
        }
        for (Long did : reload) loadMarks(account, did, null);
    }

    private static java.util.HashSet<String> collectMediaPaths(SQLiteDatabase db, String where, String[] args) {
        final java.util.HashSet<String> paths = new java.util.HashSet<>();
        try (Cursor c = db.rawQuery("SELECT account, data, prev_data, prev_media_path FROM history WHERE " + where, args)) {
            while (c.moveToNext()) {
                if (!c.isNull(3)) paths.add(c.getString(3));
                for (int col = 1; col <= 2; col++) {
                    final byte[] blob = c.getBlob(col);
                    TLRPC.Message message = deserialize(blob, c.getInt(0));
                    if (blob != null && message == null) paths.add(""); // Нельзя доказать отсутствие ссылки.
                    if (message != null && !TextUtils.isEmpty(message.attachPath)) paths.add(message.attachPath);
                }
            }
        }
        return paths;
    }

    /** Файлы могут разделяться ревизиями и аккаунтами: глобальная проверка владения намеренна. */
    private static void cleanupUnreferencedMedia(SQLiteDatabase db, java.util.HashSet<String> candidates) {
        try { cleanupUnreferencedMediaInternal(db, candidates); }
        catch (Throwable e) { FileLog.e(e); }
    }

    private static void cleanupUnreferencedMediaInternal(SQLiteDatabase db, java.util.HashSet<String> candidates) {
        if (candidates.isEmpty()) return;
        final java.util.HashSet<String> live = collectMediaPaths(db, "1=1", null);
        if (live.contains("")) return; // При повреждённом снимке не рискуем удалить ещё нужный файл.
        candidates.removeAll(live);
        final File directory = privateMediaDir();
        if (directory == null) return;
        for (String path : candidates) {
            try {
                final File file = new File(path);
                // Не удаляем исходный Telegram cache, чужой путь или явно экспортированную галерею.
                if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) continue;
                if (!file.exists() || file.delete()) {
                    db.delete("saved_media", "path = ?", new String[]{path});
                }
            } catch (Throwable e) { FileLog.e(e); }
        }
    }

    public static long getDatabaseSize() {
        try {
            if (ApplicationLoader.applicationContext == null) return 0;
            File f = ApplicationLoader.applicationContext.getDatabasePath(DB_NAME);
            if (f == null) return 0;
            return f.length() + new File(f.getPath() + "-wal").length() + new File(f.getPath() + "-shm").length();
        } catch (Throwable e) {
            return 0;
        }
    }

    // ------------------------------------------------- последний онлайн / прочтение

    public static void saveLastOnline(final int account, final long userId, final int unixtime) {
        if (userId == 0 || unixtime <= 0) return;
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                SQLiteDatabase db = history.getWritableDatabase();
                db.execSQL("INSERT OR IGNORE INTO peer_meta_account (account, peer_id, last_online, read_date) VALUES (?, ?, 0, 0)", new Object[]{account, userId});
                db.execSQL("UPDATE peer_meta_account SET last_online = MAX(last_online, ?) WHERE account = ? AND peer_id = ?", new Object[]{unixtime, account, userId});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static void saveReadDate(final int account, final long peerId, final int unixtime) {
        if (peerId == 0 || unixtime <= 0) return;
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                SQLiteDatabase db = history.getWritableDatabase();
                db.execSQL("INSERT OR IGNORE INTO peer_meta_account (account, peer_id, last_online, read_date) VALUES (?, ?, 0, 0)", new Object[]{account, peerId});
                db.execSQL("UPDATE peer_meta_account SET read_date = MAX(read_date, ?) WHERE account = ? AND peer_id = ?", new Object[]{unixtime, account, peerId});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** @return {last_online, read_date} */
    public static int[] getPeerMeta(int account, long peerId) {
        final int[] result = new int[]{0, 0};
        final PengramHistory history = getInstance();
        if (history == null || peerId == 0) return result;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery("SELECT last_online, read_date FROM peer_meta_account WHERE account = ? AND peer_id = ?",
                    new String[]{String.valueOf(account), String.valueOf(peerId)});
            if (c.moveToFirst()) {
                result[0] = c.getInt(0);
                result[1] = c.getInt(1);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return result;
    }

    public static boolean isPhotoOrVideo(TLRPC.Message message) {
        if (message == null || message.media == null) return false;
        if (message.media.photo != null) return true;
        TLRPC.Document document = message.media.document;
        if (document == null) return false;
        if (document.mime_type != null && document.mime_type.startsWith("video/")) return true;
        if (document.attributes == null) return false;
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeVideo) return true;
        }
        return false;
    }

    public static boolean isEditedPhotoOrVideo(TLRPC.Message previous, TLRPC.Message current) {
        if (previous == null || current == null || !isPhotoOrVideo(previous)) return false;
        boolean oldPhoto = previous.media.photo != null;
        boolean newPhoto = current.media != null && current.media.photo != null;
        if (oldPhoto != newPhoto) return true;
        long oldId = oldPhoto ? previous.media.photo.id : previous.media.document.id;
        long newId = current.media == null ? 0 : newPhoto ? current.media.photo.id
                : current.media.document != null ? current.media.document.id : 0;
        return oldId != newId;
    }

    private static String preserveEditedMedia(int account, long dialogId, TLRPC.Message previous) {
        try {
            if (!isPhotoOrVideo(previous)) return null;
            File source = FileLoader.getInstance(account).getPathToMessage(previous);
            if ((source == null || !source.isFile()) && !TextUtils.isEmpty(previous.attachPath)) {
                source = new File(previous.attachPath);
            }
            if (source == null || !source.isFile() || source.length() == 0) return null;
            boolean photo = previous.media.photo != null;
            String filename = "edited_" + account + "_" + dialogId + "_" + previous.id
                    + "_" + System.nanoTime() + (photo ? ".jpg" : ".mp4");
            File saved = savePrivateCopy(source, filename, PengramConfig.getMediaFolder());
            if (saved != null) {
                previous.attachPath = saved.getAbsolutePath();
                trackSavedMedia(null, saved.getAbsolutePath(), saved.length());
                return saved.getAbsolutePath();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    // --------------------------------------------- сохранение медиа удалёнок

    /**
     * Копирует вложение удалённого сообщения в общую папку /storage/emulated/0/<folder>.
     * Download остаётся только для файлов, которые явно скачал сам пользователь. Голосовые
     * сообщения намеренно не экспортируются: они остаются частью приватной истории Pengram.
     */
    public static void saveMediaCopy(final File source, final String displayName, final String mimeType,
                                     final boolean isVideo, final boolean isImage) {
        if (source == null || !source.exists() || ApplicationLoader.applicationContext == null) {
            return;
        }
        executor.execute(() -> {
            try {
                if (!PengramConfig.isMediaToGallery()) {
                    // по умолчанию копии живут внутри Pengram: ни галерея, ни другие
                    // программы их не видят (в папке лежит .nomedia)
                    final File dest = savePrivateCopy(source, displayName, PengramConfig.getMediaFolder());
                    if (dest != null) {
                        trackSavedMedia(null, dest.getAbsolutePath(), dest.length());
                    }
                    return;
                }
                final String folder = PengramConfig.getMediaFolder();
                final String mime = mimeType != null ? mimeType : (isVideo ? "video/mp4" : isImage ? "image/jpeg" : "application/octet-stream");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    final Uri collection = MediaStore.Files.getContentUri("external");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/");
                    Uri uri = ApplicationLoader.applicationContext.getContentResolver().insert(collection, cv);
                    if (uri == null) {
                        return;
                    }
                    try (FileInputStream in = new FileInputStream(source);
                         OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri)) {
                        if (out == null) return;
                        byte[] buf = new byte[64 * 1024];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    trackSavedMedia(uri.toString(), null, source.length());
                } else {
                    File dir = new File(Environment.getExternalStorageDirectory(), folder);
                    if (!dir.exists() && !dir.mkdirs()) {
                        return;
                    }
                    File dest = new File(dir, displayName);
                    try (FileInputStream in = new FileInputStream(source);
                         FileOutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[64 * 1024];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    trackSavedMedia(null, dest.getAbsolutePath(), dest.length());
                    try {
                        android.media.MediaScannerConnection.scanFile(ApplicationLoader.applicationContext,
                                new String[]{dest.getAbsolutePath()}, new String[]{mime}, null);
                    } catch (Throwable ignore) {}
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** папка внутри приложения: её не видит ни галерея, ни другие программы */
    public static File privateMediaDir() {
        try {
            File base = ApplicationLoader.applicationContext.getExternalFilesDir(null);
            if (base == null) {
                base = ApplicationLoader.applicationContext.getFilesDir();
            }
            if (base == null) {
                return null;
            }
            final File dir = new File(base, PengramConfig.getMediaFolder());
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            final File noMedia = new File(dir, ".nomedia");
            if (!noMedia.exists()) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    noMedia.createNewFile();
                } catch (Throwable ignore) {
                }
            }
            return dir;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Сохраняет голосовое во внутреннем каталоге приложения. Оно остаётся доступно
     * истории Pengram, но не появляется в /storage/emulated/0/Pengram и медиатеке.
     * Метод синхронный: вызывающий код успевает записать новый attachPath в историю.
     */
    public static File savePrivateVoiceCopy(File source, String displayName) {
        if (source == null || !source.exists()) return null;
        final File dest = savePrivateCopy(source, displayName, PengramConfig.getMediaFolder());
        if (dest != null) {
            trackSavedMedia(null, dest.getAbsolutePath(), dest.length());
        }
        return dest;
    }

    private static File savePrivateCopy(File source, String displayName, String folder) {
        final File dir = privateMediaDir();
        if (dir == null) {
            return null;
        }
        String name = displayName == null || displayName.isEmpty() ? ("media_" + System.currentTimeMillis()) : displayName;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File dest = new File(dir, name);
        int index = 1;
        while (dest.exists() && index < 1000) {
            final int dot = name.lastIndexOf('.');
            final String base = dot > 0 ? name.substring(0, dot) : name;
            final String ext = dot > 0 ? name.substring(dot) : "";
            dest = new File(dir, base + "_" + index + ext);
            index++;
        }
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            final byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        return dest;
    }

    /** учёт сохранённого файла + контроль лимита папки (удаляем самые старые) */
    private static void trackSavedMedia(String uri, String path, long size) {
        final PengramHistory history = getInstance();
        if (history == null) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put("uri", uri);
            cv.put("path", path);
            cv.put("size", size);
            cv.put("saved_at", (int) (System.currentTimeMillis() / 1000L));
            history.getWritableDatabase().insert("saved_media", null, cv);
            statsDirty = true;
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        enforceMediaLimit();
    }

    /** суммарный размер сохранённых медиа, байт */
    public static long getSavedMediaSize() {
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery("SELECT SUM(size) FROM saved_media", null);
            if (c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return 0;
    }

    private static void enforceMediaLimit() {
        final int limitMb = PengramConfig.getMediaMaxSizeMb();
        if (limitMb <= 0) {
            return;
        }
        final long limit = limitMb * 1024L * 1024L;
        final PengramHistory history = getInstance();
        if (history == null) return;
        long total = getSavedMediaSize();
        if (total <= limit) {
            return;
        }
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery("SELECT id, uri, path, size FROM saved_media ORDER BY saved_at ASC, id ASC", null);
            while (c.moveToNext() && total > limit) {
                final long id = c.getLong(0);
                final String uri = c.getString(1);
                final String path = c.getString(2);
                final long size = c.getLong(3);
                boolean removed = false;
                try {
                    if (uri != null && ApplicationLoader.applicationContext != null) {
                        removed = ApplicationLoader.applicationContext.getContentResolver().delete(Uri.parse(uri), null, null) > 0;
                    } else if (path != null) {
                        File f = new File(path);
                        removed = !f.exists() || f.delete();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                if (!removed) {
                    // файл удалить не вышло — строку оставляем, иначе он навсегда
                    // выпадет из учёта и лимит папки станет фикцией
                    continue;
                }
                history.getWritableDatabase().delete("saved_media", "id = ?", new String[]{String.valueOf(id)});
                statsDirty = true;
                total -= size;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
    }

    public static void clearSavedMedia() {
        final PengramHistory history = getInstance();
        if (history == null) return;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery("SELECT uri, path FROM saved_media", null);
            while (c.moveToNext()) {
                final String uri = c.getString(0);
                final String path = c.getString(1);
                try {
                    if (uri != null && ApplicationLoader.applicationContext != null) {
                        ApplicationLoader.applicationContext.getContentResolver().delete(Uri.parse(uri), null, null);
                    } else if (path != null) {
                        new File(path).delete();
                    }
                } catch (Throwable ignore) {}
            }
            history.getWritableDatabase().delete("saved_media", null, null);
            statsDirty = true;
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
    }

    /** Имя файла по шаблону пользователя */
    public static String buildFileName(String pattern, long dialogId, int messageId, int date, String extension) {
        if (pattern == null || pattern.trim().isEmpty()) {
            pattern = PengramConfig.DEFAULT_MEDIA_PATTERN;
        }
        String chatName;
        try {
            if (dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(dialogId);
                chatName = user != null ? UserObject.getUserName(user) : String.valueOf(dialogId);
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-dialogId);
                chatName = chat != null ? chat.title : String.valueOf(dialogId);
            }
        } catch (Throwable e) {
            chatName = String.valueOf(dialogId);
        }
        chatName = sanitize(chatName);
        final Date d = new Date((date > 0 ? date : (int) (System.currentTimeMillis() / 1000L)) * 1000L);
        String name = pattern
                .replace("{date}", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d))
                .replace("{time}", new SimpleDateFormat("HH-mm-ss", Locale.US).format(d))
                .replace("{chat}", chatName)
                .replace("{id}", String.valueOf(messageId))
                .replace("{dialog}", String.valueOf(dialogId));
        name = sanitize(name);
        if (name.isEmpty()) {
            name = "pengram_" + messageId;
        }
        if (extension != null && !extension.isEmpty() && !name.toLowerCase(Locale.US).endsWith("." + extension.toLowerCase(Locale.US))) {
            name = name + "." + extension;
        }
        return name;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
    }

    // --------------------------------------------------------------- хелперы

    /** короткое описание медиа, если текста нет */
    public static String describe(TLRPC.Message message) {
        if (message == null) return null;
        if (!TextUtils.isEmpty(message.message)) {
            return message.message;
        }
        if (message.media == null) return null;
        if (message.media instanceof TLRPC.TL_messageMediaPhoto) return "[photo]";
        if (message.media instanceof TLRPC.TL_messageMediaGeo) return "[location]";
        if (message.media instanceof TLRPC.TL_messageMediaContact) return "[contact]";
        if (message.media instanceof TLRPC.TL_messageMediaPoll) return "[poll]";
        if (message.media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document doc = message.media.document;
            if (doc != null) {
                for (int i = 0; i < doc.attributes.size(); ++i) {
                    TLRPC.DocumentAttribute a = doc.attributes.get(i);
                    if (a instanceof TLRPC.TL_documentAttributeSticker) return "[sticker]";
                    if (a instanceof TLRPC.TL_documentAttributeAudio) return "[voice/audio]";
                    if (a instanceof TLRPC.TL_documentAttributeVideo) return "[video]";
                }
            }
            return "[file]";
        }
        return "[media]";
    }
}
