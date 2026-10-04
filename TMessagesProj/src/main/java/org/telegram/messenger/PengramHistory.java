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
    private static final int DB_VERSION = 3;
    private static final String TABLE = "history";
    private static final String TABLE_MARKS = "deleted_marks";

    private static volatile PengramHistory instance;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

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
    }

    private PengramHistory(android.content.Context context) {
        super(context, DB_NAME, null, DB_VERSION);
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
        createV2(db);
        createV3(db);
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
    }

    // ------------------------------------------------------------------ запись

    public static void save(final int account, final long dialogId, final int messageId, final long fromId,
                            final int date, final int action, final String text, final String prevText) {
        save(account, dialogId, messageId, fromId, date, action, text, prevText, false, null);
    }

    public static void save(final int account, final long dialogId, final int messageId, final long fromId,
                            final int date, final int action, final String text, final String prevText,
                            final boolean out, final byte[] data) {
        if (TextUtils.isEmpty(text) && TextUtils.isEmpty(prevText) && data == null) {
            return;
        }
        final PengramHistory history = getInstance();
        if (history == null) {
            return;
        }
        executor.execute(() -> {
            try {
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
                    cv.put("data", data);
                }
                history.getWritableDatabase().insert(TABLE, null, cv);
                invalidateCounts(dialogId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
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

    public static TLRPC.Message deserialize(byte[] data) {
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
                message.readAttachPath(buffer, UserConfig.getInstance(UserConfig.selectedAccount).clientUserId);
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

    // ------------------------------------------------- метки «сообщение удалено»

    /** кэш: dialogId -> набор id удалённых сообщений (чтобы не дёргать базу при отрисовке) */
    private static final java.util.HashMap<Long, java.util.HashSet<Integer>> marksCache = new java.util.HashMap<>();

    // ------------------------------------------------------- счётчики без фризов
    // COUNT(*) по базе занимает десятки миллисекунд, а зовут его из onBindViewHolder
    // и при открытии меню. Поэтому наружу отдаём кэш, а базу опрашиваем в фоне.

    private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> countCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Long> countDirty = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.Set<Long> countLoading = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * Сколько записей сохранено для диалога (0 — по всей базе).
     * Возвращается мгновенно последнее известное значение; если оно устарело,
     * база опрашивается в фоне и onUpdated вызывается на UI-потоке только при реальном изменении.
     */
    public static int getCountCached(final long dialogId, final Runnable onUpdated) {
        final Integer cached = countCache.get(dialogId);
        if (cached == null || countDirty.contains(dialogId)) {
            refreshCount(dialogId, onUpdated);
        }
        return cached == null ? 0 : cached;
    }

    private static void refreshCount(final long dialogId, final Runnable onUpdated) {
        if (getInstance() == null || !countLoading.add(dialogId)) {
            return;
        }
        executor.execute(() -> {
            int value = 0;
            try {
                value = getCount(dialogId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Integer previous = countCache.put(dialogId, value);
            countDirty.remove(dialogId);
            countLoading.remove(dialogId);
            if (onUpdated != null && (previous == null || previous != value)) {
                AndroidUtilities.runOnUIThread(onUpdated);
            }
        });
    }

    /** запись изменилась — при следующем запросе счётчики пересчитаются */
    private static void invalidateCounts(long dialogId) {
        countDirty.add(0L);
        if (dialogId != 0) {
            countDirty.add(dialogId);
        } else {
            countDirty.addAll(countCache.keySet());
        }
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

    public static void markDeleted(final int account, final long dialogId, final java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty() || dialogId == 0) {
            return;
        }
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        synchronized (marksCache) {
            java.util.HashSet<Integer> set = marksCache.get(dialogId);
            if (set == null) {
                set = new java.util.HashSet<>();
                marksCache.put(dialogId, set);
            }
            set.addAll(copy);
        }
        final PengramHistory history = getInstance();
        if (history == null) {
            return;
        }
        final int now = (int) (System.currentTimeMillis() / 1000L);
        executor.execute(() -> {
            try {
                SQLiteDatabase db = history.getWritableDatabase();
                db.beginTransaction();
                try {
                    for (int i = 0; i < copy.size(); ++i) {
                        ContentValues cv = new ContentValues();
                        cv.put("account", account);
                        cv.put("dialog_id", dialogId);
                        cv.put("message_id", copy.get(i));
                        cv.put("date", now);
                        db.insertWithOnConflict(TABLE_MARKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** быстрая проверка по кэшу; кэш подгружается в loadMarks() при открытии чата */
    public static boolean isMarkedDeleted(long dialogId, int messageId) {
        if (dialogId == 0) {
            return false;
        }
        synchronized (marksCache) {
            java.util.HashSet<Integer> set = marksCache.get(dialogId);
            return set != null && set.contains(messageId);
        }
    }

    /** успели ли подгрузить метки этого диалога (до этого «не удалено» ничего не значит) */
    public static boolean marksLoaded(long dialogId) {
        synchronized (marksCache) {
            return marksCache.containsKey(dialogId);
        }
    }

    public static boolean hasMarks(long dialogId) {
        synchronized (marksCache) {
            java.util.HashSet<Integer> set = marksCache.get(dialogId);
            return set != null && !set.isEmpty();
        }
    }

    /** подгружает метки диалога в память (вызывается при открытии чата) */
    public static void loadMarks(final long dialogId, final Runnable done) {
        if (dialogId == 0) {
            if (done != null) AndroidUtilities.runOnUIThread(done);
            return;
        }
        synchronized (marksCache) {
            if (marksCache.containsKey(dialogId)) {
                if (done != null) AndroidUtilities.runOnUIThread(done);
                return;
            }
        }
        final PengramHistory history = getInstance();
        if (history == null) {
            return;
        }
        executor.execute(() -> {
            final java.util.HashSet<Integer> set = new java.util.HashSet<>();
            Cursor c = null;
            try {
                c = history.getReadableDatabase().rawQuery(
                        "SELECT message_id FROM " + TABLE_MARKS + " WHERE dialog_id = ?",
                        new String[]{String.valueOf(dialogId)});
                while (c.moveToNext()) {
                    set.add(c.getInt(0));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignore) {}
            }
            synchronized (marksCache) {
                java.util.HashSet<Integer> existing = marksCache.get(dialogId);
                if (existing != null) {
                    set.addAll(existing);
                }
                marksCache.put(dialogId, set);
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    public static void unmarkDeleted(final long dialogId, final java.util.Collection<Integer> ids) {
        if (dialogId == 0 || ids == null || ids.isEmpty()) {
            return;
        }
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        synchronized (marksCache) {
            java.util.HashSet<Integer> set = marksCache.get(dialogId);
            if (set != null) {
                set.removeAll(copy);
            }
        }
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                history.getWritableDatabase().delete(TABLE_MARKS,
                        "dialog_id = ? AND message_id IN (" + TextUtils.join(",", copy) + ")",
                        new String[]{String.valueOf(dialogId)});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static void clearMarks(final long dialogId) {
        synchronized (marksCache) {
            if (dialogId == 0) {
                marksCache.clear();
            } else {
                marksCache.remove(dialogId);
            }
        }
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                if (dialogId == 0) {
                    history.getWritableDatabase().delete(TABLE_MARKS, null, null);
                } else {
                    history.getWritableDatabase().delete(TABLE_MARKS, "dialog_id = ?", new String[]{String.valueOf(dialogId)});
                }
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
    public static boolean shouldKeep(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        boolean anySaveForMyself = false;
        boolean anyUserDeleted = false;
        synchronized (userDeletedGuard) {
            cleanupGuard(System.currentTimeMillis());
            for (Integer id : ids) {
                if (id == null) continue;
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
        return PengramConfig.isKeepingDeletedInChat();
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

    public static ArrayList<Entry> getEntries(long dialogId, int filter, int limit) {
        return getEntries(dialogId, filter, limit, null, 0, false);
    }

    /**
     * @param messageId если != 0 — только версии конкретного сообщения
     * @param ascending true — от старых к новым (как в чате)
     */
    public static ArrayList<Entry> getEntries(long dialogId, int filter, int limit, String query, int messageId, boolean ascending) {
        ArrayList<Entry> result = new ArrayList<>();
        final PengramHistory history = getInstance();
        if (history == null) return result;
        Cursor c = null;
        try {
            StringBuilder where = new StringBuilder();
            ArrayList<String> args = new ArrayList<>();
            if (dialogId != 0) {
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
            if (!TextUtils.isEmpty(query)) {
                if (where.length() > 0) where.append(" AND ");
                where.append("(text LIKE ? OR prev_text LIKE ?)");
                args.add("%" + query + "%");
                args.add("%" + query + "%");
            }
            String sql = "SELECT id, account, dialog_id, message_id, from_id, date, saved_at, action, text, prev_text, out, data FROM " + TABLE +
                    (where.length() > 0 ? (" WHERE " + where) : "") +
                    // Общий «чат истории» стоит по исходной дате/ID сообщения, а версии одного
                    // сообщения — по времени сохранения. Так удалёнка никогда не прыгает в конец.
                    (ascending ? (messageId != 0
                            ? " ORDER BY saved_at ASC, id ASC"
                            : " ORDER BY date ASC, message_id ASC, saved_at ASC, id ASC")
                            : " ORDER BY saved_at DESC, id DESC") +
                    " LIMIT " + Math.max(1, limit);
            c = history.getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
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
                try {
                    e.data = c.getBlob(11);
                } catch (Throwable ignore) {
                    e.data = null;
                }
                result.add(e);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
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

    public static int getCount(long dialogId) {
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        Cursor c = null;
        try {
            if (dialogId != 0) {
                c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE dialog_id = ?", new String[]{String.valueOf(dialogId)});
            } else {
                c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
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

    /** сколько сохранено записей нужного типа (ACTION_DELETED / ACTION_EDITED) */
    public static int getCount(long dialogId, int action) {
        final PengramHistory history = getInstance();
        if (history == null) return 0;
        Cursor c = null;
        try {
            if (dialogId != 0) {
                c = history.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE dialog_id = ? AND action = ?",
                        new String[]{String.valueOf(dialogId), String.valueOf(action)});
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
            history.getWritableDatabase().delete(TABLE_MARKS, "date > 0 AND date < ?", new String[]{String.valueOf(edge)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return removed;
    }

    /** фоновая автоочистка по сроку хранения из настроек */
    public static void autoCleanup() {
        final int days = PengramConfig.getHistoryKeepDays();
        if (days <= 0) {
            return;
        }
        executor.execute(() -> {
            final int removed = deleteOlderThan(days);
            if (BuildVars.LOGS_ENABLED && removed > 0) {
                FileLog.d("pengram: автоочистка истории — удалено " + removed + " записей старше " + days + " дней");
            }
        });
    }

    public static void clear(final long dialogId) {
        final PengramHistory history = getInstance();
        clearMarks(dialogId);
        if (history == null) return;
        try {
            if (dialogId != 0) {
                history.getWritableDatabase().delete(TABLE, "dialog_id = ?", new String[]{String.valueOf(dialogId)});
                invalidateCounts(dialogId);
            } else {
                history.getWritableDatabase().delete(TABLE, null, null);
                invalidateCounts(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void deleteEntry(final long rowId) {
        final PengramHistory history = getInstance();
        if (history == null) return;
        try {
            history.getWritableDatabase().delete(TABLE, "id = ?", new String[]{String.valueOf(rowId)});
            invalidateCounts(0);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static long getDatabaseSize() {
        try {
            if (ApplicationLoader.applicationContext == null) return 0;
            File f = ApplicationLoader.applicationContext.getDatabasePath(DB_NAME);
            return f != null && f.exists() ? f.length() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ------------------------------------------------- последний онлайн / прочтение

    public static void saveLastOnline(final long userId, final int unixtime) {
        if (userId == 0 || unixtime <= 0) return;
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                SQLiteDatabase db = history.getWritableDatabase();
                db.execSQL("INSERT OR IGNORE INTO peer_meta (peer_id, last_online, read_date) VALUES (?, 0, 0)", new Object[]{userId});
                db.execSQL("UPDATE peer_meta SET last_online = MAX(last_online, ?) WHERE peer_id = ?", new Object[]{unixtime, userId});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static void saveReadDate(final long peerId, final int unixtime) {
        if (peerId == 0 || unixtime <= 0) return;
        final PengramHistory history = getInstance();
        if (history == null) return;
        executor.execute(() -> {
            try {
                SQLiteDatabase db = history.getWritableDatabase();
                db.execSQL("INSERT OR IGNORE INTO peer_meta (peer_id, last_online, read_date) VALUES (?, 0, 0)", new Object[]{peerId});
                db.execSQL("UPDATE peer_meta SET read_date = MAX(read_date, ?) WHERE peer_id = ?", new Object[]{unixtime, peerId});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** @return {last_online, read_date} */
    public static int[] getPeerMeta(long peerId) {
        final int[] result = new int[]{0, 0};
        final PengramHistory history = getInstance();
        if (history == null || peerId == 0) return result;
        Cursor c = null;
        try {
            c = history.getReadableDatabase().rawQuery("SELECT last_online, read_date FROM peer_meta WHERE peer_id = ?", new String[]{String.valueOf(peerId)});
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
