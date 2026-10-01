package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.io.File;
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
    private static final int DB_VERSION = 1;
    private static final String TABLE = "history";

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // пока миграций нет
    }

    // ------------------------------------------------------------------ запись

    public static void save(final int account, final long dialogId, final int messageId, final long fromId,
                            final int date, final int action, final String text, final String prevText) {
        if (TextUtils.isEmpty(text) && TextUtils.isEmpty(prevText)) {
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
                history.getWritableDatabase().insert(TABLE, null, cv);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
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
            String sql = "SELECT id, account, dialog_id, message_id, from_id, date, saved_at, action, text, prev_text FROM " + TABLE +
                    (where.length() > 0 ? (" WHERE " + where) : "") +
                    " ORDER BY saved_at DESC, id DESC LIMIT " + Math.max(1, limit);
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
                result.add(e);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return result;
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

    // ------------------------------------------------------------------ очистка

    public static void clear(final long dialogId) {
        final PengramHistory history = getInstance();
        if (history == null) return;
        try {
            if (dialogId != 0) {
                history.getWritableDatabase().delete(TABLE, "dialog_id = ?", new String[]{String.valueOf(dialogId)});
            } else {
                history.getWritableDatabase().delete(TABLE, null, null);
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
