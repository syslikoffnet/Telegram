package org.telegram.messenger;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/** Persistent cloud queue with batching, FloodWait backoff and automatic channel recovery. */
public final class PengramProfileCloud {
    private static final HashSet<Integer> running = new HashSet<>();
    private PengramProfileCloud() {}

    private static final class Row { long id, created; String payload; int retries; }

    public static void schedule(int account) {
        synchronized (running) { if (!running.add(account)) return; }
        Utilities.globalQueue.postRunnable(() -> drain(account), 5000);
    }
    private static SQLiteDatabase db(){ PengramProfileHistory h=PengramProfileHistory.getInstance(); return h==null?null:h.getWritableDatabase(); }
    private static void ensureCloudTables(SQLiteDatabase d) {
        if (d == null) return;
        d.execSQL("CREATE TABLE IF NOT EXISTS cloud_meta(account INTEGER PRIMARY KEY,channel_id INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE IF NOT EXISTS cloud_avatars(account INTEGER NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(account,hash))");
    }
    private static long channelId(int account){SQLiteDatabase d=db();if(d==null)return 0;ensureCloudTables(d);try(Cursor c=d.rawQuery("SELECT channel_id FROM cloud_meta WHERE account=?",new String[]{""+account})){return c.moveToFirst()?c.getLong(0):0;}}
    private static void setChannel(int account,long id){SQLiteDatabase d=db();if(d!=null){ensureCloudTables(d);d.execSQL("INSERT OR REPLACE INTO cloud_meta(account,channel_id) VALUES(?,?)",new Object[]{account,id});}}

    private static void drain(int account) {
        if (!PengramProfileHistory.enabled() || PengramConfig.getIntCached(PengramProfileHistory.KEY_STORAGE,0)==0) { stop(account); return; }
        long cid=channelId(account); if(cid==0){createChannel(account);return;}
        SQLiteDatabase d=db(); if(d==null){stop(account);return;}
        int format=PengramConfig.getIntCached(PengramProfileHistory.KEY_CLOUD_FORMAT,PengramProfileHistory.CLOUD_DELTA);
        int max=format==PengramProfileHistory.CLOUD_DELTA?1:(format==PengramProfileHistory.CLOUD_BATCH?20:50);
        ArrayList<Row> rows=readRows(d,account,max); if(rows.isEmpty()){stop(account);return;}
        if(format==PengramProfileHistory.CLOUD_SUMMARY && rows.size()<50 && rows.get(0).created>System.currentTimeMillis()/1000L-3600L){reschedule(account,60000);return;}

        Row first=rows.get(0); String avatarHash=extractAvatarHash(first.payload); java.io.File avatar=avatarHash==null?null:PengramProfileHistory.avatarFile(avatarHash);
        if(avatar!=null&&avatar.exists()&&!isAvatarUploaded(d,account,avatarHash)){
            // Media is deliberately one item per drain, preserving the global 5s minimum interval.
            SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(account),avatar.getAbsolutePath(),null,null,-cid,null,null,null,null,null,null,null,0,null,null,false,0,0,0,false,first.payload,null,0,0,0,null);
            d.execSQL("INSERT OR IGNORE INTO cloud_avatars(account,hash) VALUES(?,?)",new Object[]{account,avatarHash});
            d.delete("cloud_queue","id=?",new String[]{""+first.id});
            reschedule(account,5000);return;
        }

        // Never absorb a not-yet-uploaded avatar into a batch: stop before it and handle it next.
        ArrayList<Row> sending=new ArrayList<>();StringBuilder payload=new StringBuilder();
        for(Row row:rows){String hash=extractAvatarHash(row.payload);java.io.File f=hash==null?null:PengramProfileHistory.avatarFile(hash);if(!sending.isEmpty()&&f!=null&&f.exists()&&!isAvatarUploaded(d,account,hash))break;if(payload.length()>0)payload.append("\n\n──────────\n\n");payload.append(row.payload);sending.add(row);}
        sendText(account,cid,d,sending,payload.toString());
    }

    private static ArrayList<Row> readRows(SQLiteDatabase d,int account,int limit){ArrayList<Row> out=new ArrayList<>();try(Cursor c=d.rawQuery("SELECT id,payload,retries,created FROM cloud_queue WHERE account=? AND next_try<=? ORDER BY id LIMIT "+limit,new String[]{""+account,""+(System.currentTimeMillis()/1000)})){while(c.moveToNext()){Row r=new Row();r.id=c.getLong(0);r.payload=c.getString(1);r.retries=c.getInt(2);r.created=c.getLong(3);out.add(r);}}return out;}
    private static void sendText(int account,long cid,SQLiteDatabase d,ArrayList<Row> rows,String text){
        TLRPC.TL_messages_sendMessage req=new TLRPC.TL_messages_sendMessage();req.peer=MessagesController.getInstance(account).getInputPeer(-cid);req.message=text;req.random_id=Utilities.random.nextLong();req.no_webpage=true;req.silent=true;
        ConnectionsManager.getInstance(account).sendRequest(req,(response,error)->Utilities.globalQueue.postRunnable(()->{
            if(error==null){if(response instanceof TLRPC.Updates)MessagesController.getInstance(account).processUpdates((TLRPC.Updates)response,false);for(Row row:rows)d.delete("cloud_queue","id=?",new String[]{""+row.id});reschedule(account,5000);return;}
            String value=error.text==null?"":error.text;int wait=parseFloodWait(value);long next=System.currentTimeMillis()/1000L+(wait>0?wait:Math.min(3600,30L*(1L<<Math.min(6,rows.get(0).retries))));
            for(Row row:rows)d.execSQL("UPDATE cloud_queue SET retries=retries+1,next_try=? WHERE id=?",new Object[]{next,row.id});
            if(value.contains("CHANNEL_PRIVATE")||value.contains("CHANNEL_INVALID")||value.contains("CHAT_ID_INVALID")){resetMissingChannel(account);reschedule(account,1000);}else reschedule(account,Math.max(5000,(next-System.currentTimeMillis()/1000L)*1000L));
        }));
    }
    private static int parseFloodWait(String text){if(text==null)return 0;int p=text.indexOf("FLOOD_WAIT_");if(p<0)p=text.indexOf("FLOOD_PREMIUM_WAIT_");if(p<0)return 0;p=text.lastIndexOf('_');if(p<0)return 0;StringBuilder n=new StringBuilder();for(int i=p+1;i<text.length()&&Character.isDigit(text.charAt(i));i++)n.append(text.charAt(i));try{return Integer.parseInt(n.toString());}catch(Throwable ignore){return 0;}}
    private static void reschedule(int account,long delay){Utilities.globalQueue.postRunnable(()->drain(account),Math.max(1000,delay));}

    private static void createChannel(int account){
        TLRPC.TL_channels_createChannel req=new TLRPC.TL_channels_createChannel();req.title="Pengram";req.about="Private Pengram profile change journal";req.broadcast=true;
        ConnectionsManager.getInstance(account).sendRequest(req,(response,error)->{
            if(error!=null||!(response instanceof TLRPC.Updates)){retryCreate(account);return;}TLRPC.Updates updates=(TLRPC.Updates)response;MessagesController mc=MessagesController.getInstance(account);mc.processUpdates(updates,false);long id=0;
            if(updates.chats!=null&&!updates.chats.isEmpty()){TLRPC.Chat chat=updates.chats.get(0);id=chat.id;mc.putChats(updates.chats,false);}if(id==0){retryCreate(account);return;}setChannel(account,id);long did=-id;NotificationsController.getInstance(account).muteDialog(did,0,true);mc.addDialogToFolder(did,1,-1,0);reschedule(account,5000);
        },ConnectionsManager.RequestFlagFailOnServerErrors);
    }
    private static void resetMissingChannel(int account){SQLiteDatabase d=db();if(d!=null){d.delete("cloud_meta","account=?",new String[]{""+account});d.delete("cloud_avatars","account=?",new String[]{""+account});}}
    private static String extractAvatarHash(String payload){if(payload==null)return null;int p=payload.indexOf("\nAvatar: ");if(p<0)return null;int start=p+9,end=payload.indexOf('\n',start);String hash=payload.substring(start,end<0?payload.length():end).trim();if(hash.startsWith("#avatar_"))hash=hash.substring(8);return hash.matches("[0-9a-f]{32}")?hash:null;}
    private static boolean isAvatarUploaded(SQLiteDatabase d,int account,String hash){ensureCloudTables(d);try(Cursor c=d.rawQuery("SELECT 1 FROM cloud_avatars WHERE account=? AND hash=?",new String[]{""+account,hash})){return c.moveToFirst();}}
    public static void recreateChannel(int account){resetMissingChannel(account);synchronized(running){running.remove(account);}schedule(account);}
    public static void deleteCloud(int account){final long id=channelId(account);if(id==0)return;TLRPC.Chat chat=MessagesController.getInstance(account).getChat(id);if(chat==null){resetMissingChannel(account);return;}TLRPC.TL_channels_deleteChannel req=new TLRPC.TL_channels_deleteChannel();req.channel=MessagesController.getInstance(account).getInputChannel(chat);ConnectionsManager.getInstance(account).sendRequest(req,(response,error)->{if(error==null||error.text!=null&&(error.text.contains("CHANNEL_PRIVATE")||error.text.contains("CHANNEL_INVALID"))){SQLiteDatabase d=db();if(d!=null){resetMissingChannel(account);d.delete("cloud_queue","account=?",new String[]{""+account});}}});}
    private static void retryCreate(int account){reschedule(account,60000);}
    private static void stop(int account){synchronized(running){running.remove(account);}}
    public static boolean isHiddenDialog(int account,long dialogId){long id=channelId(account);return id!=0&&dialogId==-id;}
}
