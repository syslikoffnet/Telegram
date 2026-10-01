package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import android.content.ContentValues;
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

    // --------------------------------------------- сохранение медиа удалёнок

    /**
     * Копирует файл удалённого сообщения в выбранную пользователем папку.
     * На Android 10+ пишем через MediaStore (без разрешений), ниже — обычным файлом.
     */
    public static void saveMediaCopy(final File source, final String displayName, final String mimeType, final boolean isVideo, final boolean isImage) {
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
                    final String relative;
                    final Uri collection;
                    if (isImage) {
                        relative = Environment.DIRECTORY_PICTURES + "/" + folder;
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    } else if (isVideo) {
                        relative = Environment.DIRECTORY_MOVIES + "/" + folder;
                        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    } else {
                        relative = Environment.DIRECTORY_DOWNLOADS + "/" + folder;
                        collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    }
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
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
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            isVideo ? Environment.DIRECTORY_MOVIES : isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS), folder);
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
