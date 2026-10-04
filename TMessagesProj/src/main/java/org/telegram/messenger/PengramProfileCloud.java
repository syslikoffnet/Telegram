package org.telegram.messenger;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/** Persistent adaptive cloud queue. It never sends faster than one delta per five seconds. */
public final class PengramProfileCloud {
    private static final HashSet<Integer> running = new HashSet<>();
    private PengramProfileCloud() {}

    public static void schedule(int account) {
        synchronized (running) { if (!running.add(account)) return; }
        Utilities.globalQueue.postRunnable(() -> drain(account), 5000);
    }
    private static SQLiteDatabase db(){ PengramProfileHistory h=PengramProfileHistory.getInstance(); return h==null?null:h.getWritableDatabase(); }
    private static long channelId(int account){SQLiteDatabase d=db();if(d==null)return 0;d.execSQL("CREATE TABLE IF NOT EXISTS cloud_meta(account INTEGER PRIMARY KEY,channel_id INTEGER NOT NULL DEFAULT 0)");try(Cursor c=d.rawQuery("SELECT channel_id FROM cloud_meta WHERE account=?",new String[]{""+account})){return c.moveToFirst()?c.getLong(0):0;}}
    private static void setChannel(int account,long id){SQLiteDatabase d=db();if(d!=null)d.execSQL("INSERT OR REPLACE INTO cloud_meta(account,channel_id) VALUES(?,?)",new Object[]{account,id});}

    private static void drain(int account) {
        if (!PengramProfileHistory.enabled() || PengramConfig.getIntCached(PengramProfileHistory.KEY_STORAGE,0)==0) { stop(account); return; }
        long cid=channelId(account); if(cid==0){createChannel(account);return;}
        SQLiteDatabase d=db(); if(d==null){stop(account);return;} long id=0;String payload=null;int retries=0;
        try(Cursor c=d.rawQuery("SELECT id,payload,retries FROM cloud_queue WHERE account=? AND next_try<=? ORDER BY id LIMIT 1",new String[]{""+account,""+(System.currentTimeMillis()/1000)})){if(c.moveToFirst()){id=c.getLong(0);payload=c.getString(1);retries=c.getInt(2);}}
        if(id==0){stop(account);return;}
        final long row=id; final int oldRetries=retries;
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(payload,-cid,null,null,null,true,null,null,null,false,0,0,null,false));
        // SendMessagesHelper queues reliably itself; remove our envelope only after handing it over.
        d.delete("cloud_queue","id=?",new String[]{""+row});
        Utilities.globalQueue.postRunnable(()->drain(account),Math.min(60000,5000L+(long)oldRetries*5000L));
    }
    private static void createChannel(int account){
        TLRPC.TL_channels_createChannel req=new TLRPC.TL_channels_createChannel();req.title="Pengram";req.about="Private Pengram profile change journal";req.broadcast=true;
        ConnectionsManager.getInstance(account).sendRequest(req,(response,error)->{
            if(error!=null||!(response instanceof TLRPC.Updates)){retryCreate(account);return;}
            TLRPC.Updates updates=(TLRPC.Updates)response;MessagesController mc=MessagesController.getInstance(account);mc.processUpdates(updates,false);long id=0;
            if(updates.chats!=null&&!updates.chats.isEmpty()){TLRPC.Chat chat=updates.chats.get(0);id=chat.id;mc.putChats(updates.chats,false);}
            if(id==0){retryCreate(account);return;}setChannel(account,id);long did=-id;
            NotificationsController.getInstance(account).muteDialog(did,0,true);mc.addDialogToFolder(did,1,-1,0);Utilities.globalQueue.postRunnable(()->drain(account),5000);
        },ConnectionsManager.RequestFlagFailOnServerErrors);
    }
    private static void retryCreate(int account){Utilities.globalQueue.postRunnable(()->createChannel(account),60000);}
    private static void stop(int account){synchronized(running){running.remove(account);} }
    public static boolean isHiddenDialog(int account,long dialogId){long id=channelId(account);return id!=0&&dialogId==-id;}
}
