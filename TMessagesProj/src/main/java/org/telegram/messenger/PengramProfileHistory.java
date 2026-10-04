package org.telegram.messenger;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

/** Compact, account-aware profile delta journal. Photos live outside Telegram's disposable cache. */
public final class PengramProfileHistory extends SQLiteOpenHelper {
    public static final String KEY_ENABLED = "profileHistoryEnabled";
    public static final String KEY_AVATARS = "profileHistoryAvatars";
    public static final String KEY_BIO = "profileHistoryBio";
    public static final String KEY_SCOPE = "profileHistoryScope";
    public static final String KEY_STORAGE = "profileHistoryStorage";
    /** 0 means unlimited. Defaults: 1 GiB and unlimited time. */
    public static final String KEY_LIMIT_MB = "profileHistoryLimitMb";
    public static final String KEY_LIMIT_DAYS = "profileHistoryLimitDays";
    public static final String KEY_CLOUD_FORMAT = "profileHistoryCloudFormat";
    public static final int CLOUD_DELTA = 0, CLOUD_BATCH = 1, CLOUD_SUMMARY = 2;
    public static final int SCOPE_MANUAL = 0, SCOPE_INTERACTED = 1, SCOPE_CONTACTS_CHATS = 2, SCOPE_ENCOUNTERED = 3;
    public static final int STORAGE_LOCAL = 0, STORAGE_CLOUD = 1, STORAGE_BOTH = 2;

    private static volatile PengramProfileHistory instance;
    private static final Object lock = new Object();
    private static final java.util.HashSet<String> trackedCache = new java.util.HashSet<>();
    private static volatile boolean trackedCacheLoaded;
    private static volatile boolean trackedCacheLoading;
    private static final java.util.HashSet<String> avatarLoads = new java.util.HashSet<>();

    public static final class Change {
        public long id, userId, time;
        public int account;
        public String name, username, bio, avatarHash;
    }

    private PengramProfileHistory(Context context) { super(context, "pengram_profiles.db", null, 1); }
    public static PengramProfileHistory getInstance() {
        if (instance == null && ApplicationLoader.applicationContext != null) synchronized (PengramProfileHistory.class) {
            if (instance == null) instance = new PengramProfileHistory(ApplicationLoader.applicationContext);
        }
        return instance;
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE changes(id INTEGER PRIMARY KEY AUTOINCREMENT,account INTEGER NOT NULL,user_id INTEGER NOT NULL,time INTEGER NOT NULL,name TEXT,username TEXT,bio TEXT,avatar_hash TEXT)");
        db.execSQL("CREATE INDEX changes_user ON changes(user_id,time)");
        db.execSQL("CREATE TABLE tracked(account INTEGER NOT NULL,user_id INTEGER NOT NULL,PRIMARY KEY(account,user_id))");
        db.execSQL("CREATE TABLE cloud_queue(id INTEGER PRIMARY KEY AUTOINCREMENT,account INTEGER NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL,retries INTEGER NOT NULL DEFAULT 0,next_try INTEGER NOT NULL DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public static boolean enabled() { return PengramConfig.getBool(KEY_ENABLED, false); }
    private static String trackedKey(int account,long userId){return account+":"+userId;}
    private static void ensureTrackedCache(){
        if(trackedCacheLoaded||trackedCacheLoading)return;trackedCacheLoading=true;
        Utilities.globalQueue.postRunnable(()->{PengramProfileHistory h=getInstance();if(h!=null)try(Cursor c=h.getReadableDatabase().rawQuery("SELECT account,user_id FROM tracked",null)){synchronized(trackedCache){while(c.moveToNext())trackedCache.add(trackedKey(c.getInt(0),c.getLong(1)));}}trackedCacheLoaded=true;trackedCacheLoading=false;});
    }
    public static boolean isTracked(int account, long userId) {
        ensureTrackedCache();synchronized(trackedCache){return trackedCache.contains(trackedKey(account,userId));}
    }
    public static void setTracked(int account, long userId, boolean tracked) {
        ensureTrackedCache();synchronized(trackedCache){if(tracked)trackedCache.add(trackedKey(account,userId));else trackedCache.remove(trackedKey(account,userId));}
        Utilities.globalQueue.postRunnable(()->{PengramProfileHistory h=getInstance();if(h==null)return;if(tracked)h.getWritableDatabase().execSQL("INSERT OR IGNORE INTO tracked(account,user_id) VALUES(?,?)",new Object[]{account,userId});else h.getWritableDatabase().delete("tracked","account=? AND user_id=?",new String[]{""+account,""+userId});synchronized(trackedCache){if(tracked)trackedCache.add(trackedKey(account,userId));else trackedCache.remove(trackedKey(account,userId));}});
    }

    public static void observeUser(int account, TLRPC.User user, boolean fromCache) {
        if (!enabled() || user == null || fromCache || user.bot || user.deleted || user.id == 777000 || user.id == UserObject.VERIFY || user.self) return;
        int scope = PengramConfig.getIntCached(KEY_SCOPE, SCOPE_MANUAL);
        if (scope == SCOPE_MANUAL && !isTracked(account, user.id)) return;
        if (scope == SCOPE_CONTACTS_CHATS && !user.contact && MessagesController.getInstance(account).dialogs_dict.get(user.id) == null) return;
        if (scope == SCOPE_INTERACTED && MessagesController.getInstance(account).dialogs_dict.get(user.id) == null && !isTracked(account, user.id)) return;
        final String name = ContactsController.formatName(user.first_name, user.last_name);
        final String username = collectUsernames(user);
        final String avatarToken = user.photo == null ? null : String.valueOf(user.photo.photo_id);
        Utilities.globalQueue.postRunnable(() -> saveDelta(account, user.id, name, username, null, avatarToken, user));
    }

    /** Primary, additional and collectible usernames in stable Telegram order. */
    private static String collectUsernames(TLRPC.User user) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (!TextUtils.isEmpty(user.username)) names.add(user.username);
        if (user.usernames != null) for (TLRPC.TL_username item : user.usernames) {
            if (item != null && (item.active || item.editable) && !TextUtils.isEmpty(item.username)) names.add(item.username);
        }
        if (names.isEmpty()) return ""; // empty is a real delta: all usernames were removed
        StringBuilder out = new StringBuilder();
        for (String value : names) { if (out.length() > 0) out.append(", @"); out.append(value); }
        return out.toString();
    }

    public static void observeAdditionalUsernames(int account, TLRPC.User user, boolean fromCache) {
        if (!enabled() || user == null || fromCache || user.bot || user.deleted || user.id == 777000 || user.id == UserObject.VERIFY || user.self) return;
        int scope = PengramConfig.getIntCached(KEY_SCOPE, SCOPE_MANUAL);
        if (scope == SCOPE_MANUAL && !isTracked(account, user.id)) return;
        if (scope == SCOPE_CONTACTS_CHATS && !user.contact && MessagesController.getInstance(account).dialogs_dict.get(user.id) == null) return;
        if (scope == SCOPE_INTERACTED && MessagesController.getInstance(account).dialogs_dict.get(user.id) == null && !isTracked(account, user.id)) return;
        final String usernames = collectUsernames(user);
        if (TextUtils.isEmpty(usernames)) return;
        Utilities.globalQueue.postRunnable(() -> saveDelta(account, user.id, null, usernames, null, null, null));
    }

    public static void observeBio(int account, long userId, String bio) {
        if (!enabled() || !PengramConfig.getBool(KEY_BIO, true)) return;
        Utilities.globalQueue.postRunnable(() -> saveDelta(account, userId, null, null, bio, null, null));
    }

    private static void saveDelta(int account, long userId, String name, String username, String bio, String avatarToken, TLRPC.User user) {
        PengramProfileHistory h = getInstance(); if (h == null) return;
        synchronized (lock) {
            String oldName=null, oldUsername=null, oldBio=null, oldAvatar=null;
            try (Cursor c=h.getReadableDatabase().rawQuery("SELECT name,username,bio,avatar_hash FROM changes WHERE user_id=? ORDER BY id DESC",new String[]{""+userId})) {
                while(c.moveToNext() && (oldName==null||oldUsername==null||oldBio==null||oldAvatar==null)) {
                    if(oldName==null&&!c.isNull(0))oldName=c.getString(0); if(oldUsername==null&&!c.isNull(1))oldUsername=c.getString(1);
                    if(oldBio==null&&!c.isNull(2))oldBio=c.getString(2); if(oldAvatar==null&&!c.isNull(3))oldAvatar=c.getString(3);
                }
            }
            String dn=name!=null&&!TextUtils.equals(name,oldName)?name:null;
            String du=username!=null&&!TextUtils.equals(username,oldUsername)?username:null;
            String db=bio!=null&&!TextUtils.equals(bio,oldBio)?bio:null;
            String currentAvatar = null;
            if (avatarToken != null && user != null && PengramConfig.getBool(KEY_AVATARS, false)) {
                currentAvatar = saveAvatar(account, user);
                if (currentAvatar == null) ensureAvatarAvailable(account, user, avatarToken);
            }
            String da=currentAvatar!=null&&!TextUtils.equals(currentAvatar,oldAvatar)?currentAvatar:null;
            if(dn==null&&du==null&&db==null&&da==null)return;
            h.getWritableDatabase().execSQL("INSERT INTO changes(account,user_id,time,name,username,bio,avatar_hash) VALUES(?,?,?,?,?,?,?)",new Object[]{account,userId,System.currentTimeMillis()/1000,dn,du,db,da});
            int storage=PengramConfig.getIntCached(KEY_STORAGE,STORAGE_LOCAL);
            if(storage!=STORAGE_LOCAL) enqueueCloud(h,account,userId,dn,du,db,da);
            trimToLimit();
        }
    }

    private static void enqueueCloud(PengramProfileHistory h,int account,long uid,String name,String username,String bio,String avatar) {
        StringBuilder p=new StringBuilder("#user_").append(uid).append("\nID: ").append(uid);
        if(name!=null)p.append("\nName: ").append(name); if(username!=null)p.append("\nUsernames: ").append(username.isEmpty()?"—":"@"+username);
        if(bio!=null)p.append("\nBio: ").append(bio); if(avatar!=null)p.append("\nAvatar: #avatar_").append(avatar);
        p.append("\nDate: ").append(String.format(Locale.US,"%tF %<tR",System.currentTimeMillis()));
        h.getWritableDatabase().execSQL("INSERT INTO cloud_queue(account,payload,created) VALUES(?,?,?)",new Object[]{account,p.toString(),System.currentTimeMillis()/1000});
        PengramProfileCloud.schedule(account);
    }

    private static void ensureAvatarAvailable(int account, TLRPC.User user, String token) {
        final String loadKey=account+":"+user.id+":"+token;
        synchronized(avatarLoads){if(avatarLoads.contains(loadKey)||avatarLoads.size()>=4)return;avatarLoads.add(loadKey);}
        try {
            File source = FileLoader.getInstance(account).getPathToAttach(user.photo.photo_small, true);
            if (source != null && source.exists()){synchronized(avatarLoads){avatarLoads.remove(loadKey);}return;}
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    FileLoader.getInstance(account).loadFile(ImageLocation.getForUser(account, user, ImageLocation.TYPE_SMALL), user,
                            "jpg", FileLoader.PRIORITY_LOW, 1);
                } catch (Throwable e) { FileLog.e(e); }
            });
            // FileLoader owns retries/network policy. These bounded probes only import the completed file.
            for (int i = 1; i <= 3; i++) {
                final int attempt = i;
                Utilities.globalQueue.postRunnable(() -> {
                    File ready = FileLoader.getInstance(account).getPathToAttach(user.photo.photo_small, true);
                    if (ready != null && ready.exists()) { synchronized(avatarLoads){avatarLoads.remove(loadKey);} saveDelta(account, user.id, null, null, null, token, user); }
                    else if(attempt==3)synchronized(avatarLoads){avatarLoads.remove(loadKey);}
                }, attempt * attempt * 2500L);
            }
        } catch (Throwable e) { synchronized(avatarLoads){avatarLoads.remove(loadKey);} FileLog.e(e); }
    }
    private static File avatarDir(){ File d=new File(ApplicationLoader.applicationContext.getFilesDir(),"pengram_avatars"); d.mkdirs(); return d; }
    public static File avatarFile(String hash) { return TextUtils.isEmpty(hash) ? null : new File(avatarDir(), hash + ".webp"); }
    /** Narrow allow-list for sending Pengram-owned private avatar snapshots through SendMessagesHelper. */
    public static boolean isCloudAvatarFile(String path) {
        if (TextUtils.isEmpty(path)) return false;
        try {
            File file = new File(path).getCanonicalFile();
            return file.getParentFile() != null
                    && file.getParentFile().equals(avatarDir().getCanonicalFile())
                    && file.getName().matches("[0-9a-f]{32}\\.webp")
                    && file.isFile() && file.length() > 0;
        } catch (Throwable ignore) {
            return false;
        }
    }
    private static String saveAvatar(int account, TLRPC.User user) {
        try {
            if(user.photo==null||user.photo.photo_small==null)return null;
            File source=FileLoader.getInstance(account).getPathToAttach(user.photo.photo_small,true);
            if(source==null||!source.exists())return null;
            Bitmap b=BitmapFactory.decodeFile(source.getAbsolutePath()); if(b==null)return null;
            int s=Math.max(b.getWidth(),b.getHeight()); float k=Math.min(1f,200f/Math.max(1,s));
            Bitmap thumb=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*k)),Math.max(1,Math.round(b.getHeight()*k)),true);
            ByteArrayOutputStream out=new ByteArrayOutputStream(); thumb.compress(Bitmap.CompressFormat.WEBP,72,out); byte[] bytes=out.toByteArray();
            MessageDigest md=MessageDigest.getInstance("MD5"); StringBuilder hex=new StringBuilder(); for(byte v:md.digest(bytes))hex.append(String.format(Locale.US,"%02x",v&255));
            File dest=new File(avatarDir(),hex+".webp"); if(!dest.exists())try(FileOutputStream f=new FileOutputStream(dest)){f.write(bytes);}
            if(thumb!=b)thumb.recycle(); b.recycle();
            trimToLimit();
            return hex.toString();
        }catch(Throwable e){FileLog.e(e); return null;}
    }

    public static ArrayList<Change> getChanges(long userId) { return getChanges(userId, null); }
    public static ArrayList<Change> getChanges(long userId, String query) {
        ArrayList<Change> out=new ArrayList<>(); PengramProfileHistory h=getInstance(); if(h==null)return out;
        StringBuilder where = new StringBuilder(); ArrayList<String> args = new ArrayList<>();
        if (userId != 0) { where.append("user_id=?"); args.add("" + userId); }
        if (!TextUtils.isEmpty(query)) {
            if (where.length() > 0) where.append(" AND ");
            where.append("(name LIKE ? OR username LIKE ? OR bio LIKE ? OR CAST(user_id AS TEXT) LIKE ?)");
            String q = "%" + query.trim() + "%"; args.add(q); args.add(q); args.add(q); args.add(q);
        }
        final String sql = "SELECT id,account,user_id,time,name,username,bio,avatar_hash FROM changes" + (where.length() > 0 ? " WHERE " + where : "") + " ORDER BY time DESC,id DESC";
        try(Cursor c=h.getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))){
            while(c.moveToNext()){Change x=new Change();x.id=c.getLong(0);x.account=c.getInt(1);x.userId=c.getLong(2);x.time=c.getLong(3);x.name=c.getString(4);x.username=c.getString(5);x.bio=c.getString(6);x.avatarHash=c.getString(7);out.add(x);}
        } return out;
    }
    public static int count(long userId){PengramProfileHistory h=getInstance();if(h==null)return 0;try(Cursor c=h.getReadableDatabase().rawQuery("SELECT count(*) FROM changes WHERE user_id=?",new String[]{""+userId})){return c.moveToFirst()?c.getInt(0):0;}}
    public static void applyRetentionNow() { Utilities.globalQueue.postRunnable(PengramProfileHistory::trimToLimit); }
    private static void trimToLimit() {
        final int days = PengramConfig.getIntCached(KEY_LIMIT_DAYS, 0);
        final PengramProfileHistory h = getInstance();
        if (days > 0 && h != null) {
            final long cutoff = System.currentTimeMillis() / 1000L - days * 86400L;
            h.getWritableDatabase().delete("changes", "time<?", new String[]{String.valueOf(cutoff)});
            File[] candidates = avatarDir().listFiles();
            if (candidates != null) for (File file : candidates) {
                String n = file.getName();
                if (!n.endsWith(".webp")) continue;
                String hash = n.substring(0, n.length() - 5);
                try (Cursor c = h.getReadableDatabase().rawQuery("SELECT 1 FROM changes WHERE avatar_hash=? LIMIT 1", new String[]{hash})) {
                    if (!c.moveToFirst()) file.delete();
                }
            }
        }
        final int limitMb = PengramConfig.getIntCached(KEY_LIMIT_MB, 1024);
        if (limitMb == 0) return; // explicit unlimited storage
        final long limit = limitMb * 1024L * 1024L;
        File[] files = avatarDir().listFiles();
        if (files == null) return;
        File databaseFile = ApplicationLoader.applicationContext.getDatabasePath("pengram_profiles.db");
        long total = databaseFile.exists() ? databaseFile.length() : 0; for (File f : files) total += f.length();
        if (total <= limit) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= limit) break;
            long size = f.length();
            String name = f.getName();
            String hash = name.endsWith(".webp") ? name.substring(0, name.length() - 5) : "";
            if (f.delete()) {
                total -= size;
                if (h != null) h.getWritableDatabase().execSQL("UPDATE changes SET avatar_hash=NULL WHERE avatar_hash=?", new Object[]{hash});
            }
        }
        if (total > limit && h != null) {
            // Text-only rows are tiny, but enforce the same hard ceiling even for extreme databases.
            SQLiteDatabase db = h.getWritableDatabase();
            while (total > limit) {
                db.execSQL("DELETE FROM changes WHERE id IN (SELECT id FROM changes ORDER BY time ASC,id ASC LIMIT 1000)");
                db.execSQL("VACUUM");
                long next = databaseFile.exists() ? databaseFile.length() : 0;
                if (next >= total) break;
                total = next;
            }
        }
    }
    public static void clearAvatars(){Utilities.globalQueue.postRunnable(()->{File[] fs=avatarDir().listFiles();if(fs!=null)for(File f:fs)if(f.getName().endsWith(".webp"))f.delete();PengramProfileHistory h=getInstance();if(h!=null)h.getWritableDatabase().execSQL("UPDATE changes SET avatar_hash=NULL");});}
    public static void clearLocal() { synchronized(trackedCache){trackedCache.clear();trackedCacheLoaded=true;}Utilities.globalQueue.postRunnable(() -> { PengramProfileHistory h=getInstance(); if(h!=null){h.getWritableDatabase().delete("changes",null,null);h.getWritableDatabase().delete("tracked",null,null);} clearAvatars(); }); }
}
