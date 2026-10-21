package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One-chat server-history export. Never substitutes the local message cache for server pagination. */
public final class PengramChatExport {
    public static final int REQUEST_CODE = 6503;
    private static final int PAGE_SIZE = 100;
    private final ChatActivity fragment;
    private final int account;
    private final long dialogId;
    private final long legacyDialogId;
    private final TLRPC.InputPeer legacyPeer;
    private final int topicId;
    private final String title;
    private final TLRPC.InputPeer inputPeer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final HashMap<Long, String> peers = new HashMap<>();
    // Temporary fragments contain only HTML and small markers; binary files are
    // streamed into the final document once, in chronological order.
    private final HashMap<Integer, InlineMedia> inlineMedia = new HashMap<>();
    private int nextMediaId, writtenMedia;
    private volatile boolean cancelled;
    private volatile int requestId;
    private AlertDialog progress;
    private boolean media;
    private int count, legacyCount, missing, exportedFiles, deletedCount;
    private boolean rootSeen, rootMissing;
    private boolean running;

    public PengramChatExport(ChatActivity fragment, int account, long dialogId, long topicId, long mergeDialogId, String title) {
        this.fragment = fragment;
        this.account = account;
        this.dialogId = dialogId;
        this.legacyDialogId = topicId == 0 && mergeDialogId != dialogId ? mergeDialogId : 0;
        this.legacyPeer = legacyDialogId == 0 ? null : MessagesController.getInstance(account).getInputPeer(legacyDialogId);
        this.topicId = (int) topicId;
        this.title = title == null ? String.valueOf(dialogId) : title;
        this.inputPeer = MessagesController.getInstance(account).getInputPeer(dialogId);
    }

    public void choose() {
        if (running || fragment.getParentActivity() == null) return;
        new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(s(R.string.PengramExportChat))
                .setMessage(s(R.string.PengramExportDescription))
                .setItems(new CharSequence[]{
                        s(R.string.PengramExportHtml) + " · " + s(R.string.PengramExportWithMedia),
                        s(R.string.PengramExportHtml) + " · " + s(R.string.PengramExportWithoutMedia)
                }, (d, which) -> {
                    media = which == 0;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/html");
                    intent.putExtra(Intent.EXTRA_TITLE, "Pengram_chat_" + Math.abs(dialogId) + "_" + System.currentTimeMillis() / 1000 + ".html");
                    try {
                        fragment.startActivityForResult(intent, REQUEST_CODE);
                    } catch (Exception e) {
                        showResult(s(R.string.PengramExportFailed));
                    }
                }).setNegativeButton(s(R.string.Cancel), null).show();
    }

    public int selectedFormat() { return 0; }
    public boolean includesMedia() { return media; }
    public void restoreSelection(int savedFormat, boolean savedMedia) {
        media = savedMedia;
    }

    public void onPickerResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || running) return;
        if (fragment.getParentActivity() == null || !PengramConfig.getBool(PengramConfig.KEY_EXPORT_CHAT, false) || !fragment.pengramCanExportChat()) {
            try { ApplicationLoader.applicationContext.getContentResolver().delete(data.getData(), null, null); } catch (Exception e) { FileLog.e(e); }
            return;
        }
        final Uri destination = data.getData();
        running = true;
        cancelled = false;
        count = legacyCount = missing = exportedFiles = deletedCount = 0;
        rootSeen = rootMissing = false;
        nextMediaId = writtenMedia = 0;
        inlineMedia.clear();
        progress = new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(s(R.string.PengramExportChat))
                .setMessage(s(R.string.PengramExportLoading))
                .setNegativeButton(s(R.string.Cancel), (d, w) -> cancel())
                .create();
        progress.setOnCancelListener(d -> cancel());
        try {
            if (fragment.showDialog(progress) == null) throw new IOException("Export progress dialog unavailable");
            // BaseFragment.showDialog sets outside-touch cancellation back to true.
            progress.setCanceledOnTouchOutside(false);
            executor.execute(() -> export(destination));
        } catch (Exception e) {
            FileLog.e(e);
            running = false;
            progress = null;
            executor.shutdown();
            try { ApplicationLoader.applicationContext.getContentResolver().delete(destination, null, null); } catch (Exception ignored) { FileLog.e(ignored); }
            showResult(s(R.string.PengramExportFailed));
        }
    }

    public void cancel() {
        cancelled = true;
        int id = requestId;
        if (id != 0) ConnectionsManager.getInstance(account).cancelRequest(id, true);
    }

    private void check() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private void await(CountDownLatch latch, int seconds, String timeout) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            check();
            if (System.nanoTime() >= until) throw new IOException(timeout);
        }
    }

    private void status(String message) {
        AndroidUtilities.runOnUIThread(() -> {
            if (progress != null && !cancelled) progress.setMessage(message);
        });
    }

    private TLRPC.messages_Messages page(int offset) throws Exception {
        return page(dialogId, offset);
    }

    private TLRPC.messages_Messages page(long source, int offset) throws Exception {
        check();
        TLObject req;
        TLRPC.InputPeer peer = source == dialogId ? inputPeer : legacyPeer;
        if (peer == null) throw new IOException("Peer unavailable");
        if (source == dialogId && topicId != 0) {
            TLRPC.TL_messages_getReplies r = new TLRPC.TL_messages_getReplies();
            r.peer = peer;
            r.msg_id = topicId;
            r.offset_id = offset;
            r.limit = PAGE_SIZE;
            req = r;
        } else {
            TLRPC.TL_messages_getHistory r = new TLRPC.TL_messages_getHistory();
            r.peer = peer;
            r.offset_id = offset;
            r.limit = PAGE_SIZE;
            req = r;
        }
        return requestMessages(req);
    }

    private TLRPC.messages_Messages requestMessages(TLObject req) throws Exception {
        check();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<TLObject> response = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            response.set(res);
            if (err != null) error.set(err.text);
            latch.countDown();
        });
        try {
            await(latch, 90, "History request timed out");
            check();
            if (!(response.get() instanceof TLRPC.messages_Messages)) throw new IOException("History unavailable: " + error.get());
            return (TLRPC.messages_Messages) response.get();
        } finally {
            int id = requestId;
            requestId = 0;
            if (latch.getCount() != 0) ConnectionsManager.getInstance(account).cancelRequest(id, true);
        }
    }

    private TLRPC.messages_Messages topicRoot() throws Exception {
        TLObject req;
        if (inputPeer instanceof TLRPC.TL_inputPeerChannel || inputPeer instanceof TLRPC.TL_inputPeerChannelFromMessage) {
            TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = MessagesController.getInstance(account).getInputChannel(-dialogId);
            r.id.add(topicId);
            req = r;
        } else {
            TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id.add(topicId);
            req = r;
        }
        return requestMessages(req);
    }

    private void export(Uri destination) {
        File directory = new File(ApplicationLoader.applicationContext.getCacheDir(), "pengram-chat-export-" + System.nanoTime());
        boolean success = false;
        try {
            if (!directory.mkdirs()) throw new IOException("Cannot create temporary directory");
            int offset = 0;
            int pages = 0;
            while (true) {
                check();
                status(s(R.string.PengramExportLoading) + " · " + count);
                TLRPC.messages_Messages batch = page(offset);
                peers.clear();
                for (TLRPC.User u : batch.users) peers.put(u.id, UserObject.getUserName(u));
                for (TLRPC.Chat c : batch.chats) peers.put(-c.id, c.title);
                ArrayList<TLRPC.Message> messages = batch.messages;
                if (messages == null || messages.isEmpty()) break;
                // Server pages arrive newest first. Stage compact HTML pages, then
                // concatenate them in reverse order when writing the final document.
                Collections.sort(messages, Comparator.comparingInt(m -> m.id));
                int next = messages.get(0).id;
                if (next <= 0 || offset != 0 && next >= offset) throw new IOException("History pagination stalled");
                try (BufferedWriter hw = writer(new File(directory, pages + ".html"))) {
                    for (TLRPC.Message message : messages) {
                        check();
                        if (message == null || message.id <= 0 || pages > 0 && message.id >= offset
                                || message instanceof TLRPC.TL_messageEmpty) continue;
                        if (message.noforwards) throw new IOException("Protected message");
                        if (topicId != 0 && message.id == topicId) rootSeen = true;
                        Attachment a = attachment(message);
                        hw.write(render(message, a));
                        count++;
                    }
                }
                pages++;
                offset = next;
            }
            check();
            // getReplies can omit the opening topic message.
            if (topicId != 0 && !rootSeen) {
                try {
                    TLRPC.messages_Messages rootBatch = topicRoot();
                    TLRPC.Message root = null;
                    for (TLRPC.Message candidate : rootBatch.messages) {
                        if (candidate != null && candidate.id == topicId && !(candidate instanceof TLRPC.TL_messageEmpty)) {
                            root = candidate;
                            break;
                        }
                    }
                    if (root != null && !root.noforwards) {
                        peers.clear();
                        for (TLRPC.User u : rootBatch.users) peers.put(u.id, UserObject.getUserName(u));
                        for (TLRPC.Chat c : rootBatch.chats) peers.put(-c.id, c.title);
                        Attachment a = attachment(root);
                        try (BufferedWriter w = writer(new File(directory, pages + ".html"))) {
                            w.write(render(root, a));
                        }
                        count++;
                        pages++;
                        rootSeen = true;
                    } else {
                        rootMissing = true;
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    FileLog.e(e);
                    rootMissing = true;
                }
            }
            check();
            int legacyPages = 0;
            int legacyOffset = 0;
            if (legacyDialogId != 0) {
                while (true) {
                    check();
                    status(s(R.string.PengramExportLegacyTitle) + " · " + legacyCount);
                    TLRPC.messages_Messages batch = page(legacyDialogId, legacyOffset);
                    if (batch.messages == null || batch.messages.isEmpty()) break;
                    Collections.sort(batch.messages, Comparator.comparingInt(m -> m.id));
                    int next = batch.messages.get(0).id;
                    if (next <= 0 || legacyOffset != 0 && next >= legacyOffset) throw new IOException("Migrated history pagination stalled");
                    peers.clear();
                    for (TLRPC.User u : batch.users) peers.put(u.id, UserObject.getUserName(u));
                    for (TLRPC.Chat c : batch.chats) peers.put(-c.id, c.title);
                    try (BufferedWriter lh = writer(new File(directory, "legacy_" + legacyPages + ".html"))) {
                        for (TLRPC.Message message : batch.messages) {
                            check();
                            if (message == null || message.id <= 0 || legacyOffset != 0 && message.id >= legacyOffset
                                    || message instanceof TLRPC.TL_messageEmpty) continue;
                            if (message.noforwards) throw new IOException("Protected migrated message");
                            Attachment a = attachment(message);
                            lh.write(render(message, a, "legacy-m" + message.id));
                            legacyCount++;
                        }
                    }
                    legacyPages++;
                    legacyOffset = next;
                }
            }
            check();
            File localHtml = new File(directory, "local.html");
            try (BufferedWriter lh = writer(localHtml)) {
                PengramHistory.DeletedVisitor visitor = entry -> {
                    check();
                    if (entry.messageId <= 0) return;
                    TLRPC.Message message = PengramHistory.deserialize(entry.data, account);
                    if (topicId != 0) {
                        if (message == null || message.id != topicId && (message.reply_to == null ||
                                message.reply_to.reply_to_top_id != topicId && message.reply_to.reply_to_msg_id != topicId)) return;
                    }
                    if (message == null) {
                        message = new TLRPC.TL_message();
                        message.id = entry.messageId;
                        message.media = new TLRPC.TL_messageMediaEmpty();
                        message.from_id = MessagesController.getInstance(account).getPeer(entry.fromId != 0 ? entry.fromId : entry.dialogId);
                    }
                    if (message.noforwards) return;
                    if (message.from_id == null && entry.fromId != 0) message.from_id = MessagesController.getInstance(account).getPeer(entry.fromId);
                    message.message = entry.text == null ? "" : entry.text;
                    message.date = entry.date != 0 ? entry.date : entry.savedAt;
                    message.out = entry.out;
                    Attachment a = attachment(message);
                    lh.write(render(message, a,
                            (entry.dialogId == legacyDialogId ? "legacy-d" : "d") + entry.rowId));
                    deletedCount++;
                    if (deletedCount % PAGE_SIZE == 0) status(s(R.string.PengramExportLocalLoading) + " · " + deletedCount);
                };
                PengramHistory.forEachDeleted(account, dialogId, visitor);
                if (legacyDialogId != 0) PengramHistory.forEachDeleted(account, legacyDialogId, visitor);
            }
            check();
            status(s(R.string.PengramExportWriting));
            // Only open the user's destination after successfully collecting all
            // history. No ZIP, companion files, or local file:// references.
            try (OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(destination, "w")) {
                if (out == null) throw new IOException("Cannot open destination");
                String note = s(R.string.PengramExportLimitations) + (rootMissing ? " " + s(R.string.PengramExportRootMissing) : "");
                write(out, "<!doctype html><html lang=\"" + esc(Locale.getDefault().getLanguage()) + "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; media-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\"><title>" + esc(title) + "</title>" +
                        "<style>:root{color-scheme:light dark}*{box-sizing:border-box}body{font:15px system-ui,sans-serif;margin:0;background:#d9e7e8;color:#172d39}.wrap{max-width:780px;margin:auto;padding:16px}header{background:#326f92;color:white;padding:22px;border-radius:14px;margin-bottom:16px}h1{margin:0 0 8px;font-size:23px}header small{opacity:.85}.notice{background:#fff3d6;color:#543e1b;border-radius:12px;padding:12px;margin:14px 0}.msg{background:#fff;color:#182932;border-radius:14px;padding:12px 16px;margin:10px 0;box-shadow:0 2px 8px #15344816;overflow-wrap:anywhere}.msg.out{background:#ddf4df;margin-left:10%}.head{display:flex;gap:8px;justify-content:space-between;align-items:baseline;margin-bottom:7px}.name{font-weight:700;color:#266d93}.date{font-size:12px;color:#657c85;white-space:nowrap}.text{white-space:pre-wrap;line-height:1.5}.reply{border-left:3px solid #51aaca;padding-left:9px;color:#527888;margin:7px 0}.media{display:block;max-width:100%;max-height:540px;border-radius:9px;margin-top:9px}a{color:#21769c}.tag{font-size:12px;color:#956037}.reactions{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}.reaction{background:#e6f1f5;color:#245a75;padding:3px 9px;border-radius:24px;font-size:13px}</style></head><body><div class=\"wrap\"><header><h1>" + esc(title) + "</h1><small>" + count + " · " + date((int)(System.currentTimeMillis()/1000)) + "</small></header><div class=\"notice\">" + esc(note) + " " + (missing > 0 ? esc(s(R.string.PengramExportMissing)) + ": " + missing : "") + "</div>");
                if (legacyDialogId != 0) {
                    write(out, "<section><h2>" + esc(s(R.string.PengramExportLegacyTitle)) + " · " + legacyCount + "</h2><p class=\"notice\">" + esc(s(R.string.PengramExportLegacyNote)) + "</p>");
                    for (int i = legacyPages - 1; i >= 0; --i) copyHtml(new File(directory, "legacy_" + i + ".html"), out);
                    write(out, "</section>");
                }
                for (int i = pages - 1; i >= 0; --i) copyHtml(new File(directory, i + ".html"), out);
                if (deletedCount > 0) {
                    write(out, "<section><h2>" + esc(s(R.string.PengramExportLocalTitle)) + " · " + deletedCount + "</h2><p class=\"notice\">" + esc(s(R.string.PengramExportLocalNote)) + "</p>");
                    copyHtml(localHtml, out);
                    write(out, "</section>");
                }
                write(out, "</div></body></html>");
                out.flush();
            }
            check();
            success = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            // An interrupted/failed export must never masquerade as a complete HTML.
            if (!success) {
                try { ApplicationLoader.applicationContext.getContentResolver().delete(destination, null, null); } catch (Exception e) { FileLog.e(e); }
            }
            inlineMedia.clear();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
            final boolean done = success;
            AndroidUtilities.runOnUIThread(() -> {
                running = false;
                if (progress != null) {
                    progress.dismiss();
                    progress = null;
                }
                showResult(done ? s(R.string.PengramExportDone) + " · " + count + " / " + legacyCount + " / " + deletedCount + " / " + exportedFiles + " / " + missing :
                        cancelled ? s(R.string.PengramExportCancelled) : s(R.string.PengramExportFailed));
                executor.shutdown();
            });
        }
    }

    private static final class Attachment {
        String type;
        String name;
        String path;
        String status;
        String mime;
    }

    private static final class InlineMedia {
        final File file;
        final String mime;

        InlineMedia(File file, String mime) {
            this.file = file;
            this.mime = mime;
        }
    }

    /** Untrusted document MIME types must not become executable HTML/SVG data URLs. */
    private static String safeMime(TLRPC.Document doc) {
        if (doc == null) return "image/jpeg";
        String mime = doc.mime_type;
        if (mime == null) return "application/octet-stream";
        switch (mime) {
            case "image/jpeg": case "image/png": case "image/webp": case "image/gif":
            case "video/mp4": case "video/webm": case "video/ogg":
            case "audio/mpeg": case "audio/mp4": case "audio/ogg": case "audio/opus":
            case "audio/wav": case "audio/webm": case "application/pdf":
                return mime;
            default: return "application/octet-stream";
        }
    }

    private Attachment attachment(TLRPC.Message msg) throws Exception {
        TLRPC.MessageMedia mm = msg.media;
        if (mm == null || mm instanceof TLRPC.TL_messageMediaEmpty) return null;
        Attachment a = new Attachment();
        a.type = mm instanceof TLRPC.TL_messageMediaWebPage ? "Web preview" : mm.getClass().getSimpleName().replace("TL_messageMedia", "");
        TLRPC.Document doc = mm.document != null ? mm.document : mm.webpage == null ? null : mm.webpage.document;
        TLRPC.Photo photo = mm.photo != null ? mm.photo : mm.webpage == null ? null : mm.webpage.photo;
        if (doc == null && photo == null) {
            if (mm.geo != null) {
                a.type = "Location";
                a.name = String.format(Locale.US, "https://www.openstreetmap.org/?mlat=%f&mlon=%f#map=16/%f/%f", mm.geo.lat, mm.geo._long, mm.geo.lat, mm.geo._long);
            } else if (mm instanceof TLRPC.TL_messageMediaContact) {
                a.type = "Contact";
                a.name = mm.first_name + " " + mm.last_name + " · " + mm.phone_number;
            } else if (mm instanceof TLRPC.TL_messageMediaWebPage && mm.webpage != null) {
                a.name = mm.webpage.title == null ? mm.webpage.url : mm.webpage.title;
            } else if (mm instanceof TLRPC.TL_messageMediaPoll) {
                a.type = "Poll";
                a.name = ((TLRPC.TL_messageMediaPoll) mm).poll != null ? ((TLRPC.TL_messageMediaPoll) mm).poll.question.text : "";
            } else {
                a.status = s(R.string.PengramExportUnsupported);
            }
            return a;
        }
        if (doc != null) {
            a.name = FileLoader.getDocumentFileName(doc);
            if (TextUtils.isEmpty(a.name)) a.name = "document_" + doc.id;
        } else a.name = "photo_" + photo.id + ".jpg";
        if (!media) { a.status = s(R.string.PengramExportSkipped); return a; }
        if (mm.ttl_seconds > 0) { a.status = s(R.string.PengramExportUnavailable); missing++; return a; }
        TLRPC.PhotoSize size = bestPhotoSize(photo);
        if (doc == null && (size == null || size.location == null)) { a.status = s(R.string.PengramExportUnavailable); missing++; return a; }
        FileLoader loader = FileLoader.getInstance(account);
        File file = doc != null ? loader.getPathToAttach(doc) : loader.getPathToAttach(size);
        if ((file == null || !file.isFile() || file.length() == 0) && !TextUtils.isEmpty(msg.attachPath)) {
            File attached = new File(msg.attachPath);
            if (attached.isFile() && attached.length() > 0) file = attached;
        }
        if ((file == null || !file.isFile() || file.length() == 0) && doc == null) {
            File cached = loader.getPathToAttach(size, true);
            if (cached != null && cached.isFile() && cached.length() > 0) file = cached;
        }
        if (file == null || !file.isFile() || file.length() == 0) {
            status(s(R.string.PengramExportLoading) + " · " + count + " · " + a.name);
            try { file = download(loader, msg, doc, photo, size, file); } catch (InterruptedException e) { throw e; } catch (Exception e) { FileLog.e(e); }
        }
        check();
        if (file == null || !file.isFile() || file.length() == 0) {
            a.status = s(R.string.PengramExportUnavailable);
            missing++;
            return a;
        }
        a.mime = safeMime(doc);
        int id = ++nextMediaId;
        a.path = "pengram-inline:" + id;
        inlineMedia.put(id, new InlineMedia(file, a.mime));
        exportedFiles++;
        return a;
    }

    /** Use the largest downloadable image, not the tiny inline/stripped thumbnail. */
    private static TLRPC.PhotoSize bestPhotoSize(TLRPC.Photo photo) {
        if (photo == null || photo.sizes == null) return null;
        TLRPC.PhotoSize best = null;
        for (TLRPC.PhotoSize size : photo.sizes) {
            if (size == null || size.location == null || size instanceof TLRPC.TL_photoSizeEmpty
                    || size instanceof TLRPC.TL_photoStrippedSize || size instanceof TLRPC.TL_photoPathSize) continue;
            if (best == null || (long) size.w * size.h > (long) best.w * best.h) best = size;
        }
        return best;
    }

    private File download(FileLoader loader, TLRPC.Message msg, TLRPC.Document doc, TLRPC.Photo photo, TLRPC.PhotoSize size, File path) throws Exception {
        final String key = doc != null ? FileLoader.getAttachFileName(doc) : FileLoader.getAttachFileName(size);
        if (key == null || key.isEmpty()) return path;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<File> loaded = new AtomicReference<>();
        NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> {
            if (args.length > 0 && key.equals(args[0])) {
                if (id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File) loaded.set((File) args[1]);
                latch.countDown();
            }
        };
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<Throwable> startError = new AtomicReference<>();
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (cancelled) return;
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.addObserver(observer, NotificationCenter.fileLoaded);
                center.addObserver(observer, NotificationCenter.fileLoadFailed);
                if (doc != null) loader.loadFile(doc, msg, FileLoader.PRIORITY_HIGH, 0);
                else if (size.location != null) loader.loadFile(ImageLocation.getForPhoto(size, photo), msg, "jpg", FileLoader.PRIORITY_HIGH, 0);
                else latch.countDown();
            } catch (Throwable e) {
                startError.set(e);
                latch.countDown();
            } finally {
                registered.countDown();
            }
        });
        try {
            if (!registered.await(10, TimeUnit.SECONDS)) throw new IOException("Download could not start");
            if (startError.get() != null) throw new IOException("Download could not start", startError.get());
            await(latch, 240, "Media download timed out");
            check();
            File found = loaded.get();
            return found != null && found.isFile() ? found : path;
        } finally {
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.removeObserver(observer, NotificationCenter.fileLoaded);
                center.removeObserver(observer, NotificationCenter.fileLoadFailed);
            });
        }
    }

    private String sender(TLRPC.Message msg) {
        long id = msg.from_id == null ? (msg.out ? UserConfig.getInstance(account).getClientUserId() : dialogId) : DialogObject.getPeerDialogId(msg.from_id);
        String name = peers.get(id);
        if (name == null) {
            if (id > 0) {
                TLRPC.User user = MessagesController.getInstance(account).getUser(id);
                if (user != null) name = UserObject.getUserName(user);
            } else if (id < 0) {
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-id);
                if (chat != null) name = chat.title;
            }
        }
        return TextUtils.isEmpty(name) ? String.valueOf(id) : name;
    }

    private String render(TLRPC.Message msg, Attachment a) {
        return render(msg, a, "m" + msg.id);
    }

    private String render(TLRPC.Message msg, Attachment a, String anchor) {
        StringBuilder b = new StringBuilder(512);
        b.append("<article class=\"msg").append(msg.out ? " out" : "").append("\" id=\"").append(anchor).append("\"><div class=\"head\"><span class=\"name\">").append(esc(sender(msg))).append("</span><time class=\"date\">").append(esc(date(msg.date))).append("</time></div>");
        if (msg.reply_to != null && msg.reply_to.reply_to_msg_id > 0) {
            b.append("<div class=\"reply\">↩ <a href=\"#").append(anchor.startsWith("legacy-") ? "legacy-m" : "m").append(msg.reply_to.reply_to_msg_id).append("\">#").append(msg.reply_to.reply_to_msg_id).append("</a>");
            if (!TextUtils.isEmpty(msg.reply_to.quote_text)) b.append(" · ").append(esc(msg.reply_to.quote_text));
            b.append("</div>");
        }
        if (msg.fwd_from != null) b.append("<div class=\"tag\">↪ ").append(esc(msg.fwd_from.from_name == null ? s(R.string.PengramExportForwarded) : msg.fwd_from.from_name)).append("</div>");
        if (msg.action != null) b.append("<div class=\"tag\">").append(esc(service(msg.action))).append("</div>");
        if (!TextUtils.isEmpty(msg.message)) b.append("<div class=\"text\">").append(richText(msg.message, msg.entities)).append("</div>");
        if (a != null) {
            b.append("<div class=\"tag\">").append(esc(a.type)).append(" · ").append(esc(a.name)).append("</div>");
            if (msg.media instanceof TLRPC.TL_messageMediaPoll && ((TLRPC.TL_messageMediaPoll) msg.media).poll != null) {
                b.append("<ol>");
                for (TLRPC.PollAnswer option : ((TLRPC.TL_messageMediaPoll) msg.media).poll.answers) {
                    b.append("<li>").append(esc(option.text == null ? "" : option.text.text)).append("</li>");
                }
                b.append("</ol>");
            }
            if ("Location".equals(a.type) && a.name != null && a.name.startsWith("https://"))
                b.append("<a href=\"").append(esc(a.name)).append("\" rel=\"noreferrer noopener\">").append(esc(s(R.string.PengramExportMap))).append("</a>");
            if (a.path != null) {
                String path = esc(a.path);
                if (a.mime.startsWith("image/")) {
                    b.append("<img loading=\"lazy\" class=\"media\" src=\"").append(path).append("\" alt=\"").append(esc(a.name)).append("\">");
                } else if (a.mime.startsWith("video/")) {
                    b.append("<video controls preload=\"none\" class=\"media\" src=\"").append(path).append("\"></video>");
                } else if (a.mime.startsWith("audio/")) {
                    b.append("<audio controls preload=\"none\" src=\"").append(path).append("\"></audio>");
                } else {
                    b.append("<a download=\"").append(esc(a.name)).append("\" href=\"").append(path)
                            .append("\">").append(esc(s(R.string.PengramExportDownload))).append("</a>");
                }
            } else if (a.status != null) b.append("<div class=\"tag\">").append(esc(a.status)).append("</div>");
        }
        if (msg.reactions != null && msg.reactions.results != null && !msg.reactions.results.isEmpty()) {
            b.append("<div class=\"reactions\">");
            for (TLRPC.ReactionCount reaction : msg.reactions.results) {
                b.append("<span class=\"reaction\">").append(esc(reactionName(reaction.reaction)))
                        .append(" ").append(reaction.count).append("</span>");
            }
            b.append("</div>");
        }
        if (msg.edit_date > 0) b.append("<div class=\"tag\">").append(esc(s(R.string.PengramExportEdited))).append(" ").append(esc(date(msg.edit_date))).append("</div>");
        b.append("</article>\n");
        return b.toString();
    }

    private static String reactionName(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji) return ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) return "#" + ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
        return "?";
    }

    private static String service(TLRPC.MessageAction action) {
        if (action instanceof TLRPC.TL_messageActionCustomAction && action.message != null) return action.message;
        if (action instanceof TLRPC.TL_messageActionChatCreate) return s(R.string.PengramExportGroupCreated);
        if (action instanceof TLRPC.TL_messageActionChatEditTitle) return s(R.string.PengramExportTitleChanged) + ": " + action.title;
        if (action instanceof TLRPC.TL_messageActionChatAddUser) return s(R.string.PengramExportMemberAdded);
        if (action instanceof TLRPC.TL_messageActionChatDeleteUser) return s(R.string.PengramExportMemberRemoved);
        if (action instanceof TLRPC.TL_messageActionChatEditPhoto) return s(R.string.PengramExportPhotoChanged);
        if (action instanceof TLRPC.TL_messageActionChatDeletePhoto) return s(R.string.PengramExportPhotoRemoved);
        if (action instanceof TLRPC.TL_messageActionPinMessage) return s(R.string.PengramExportPinned);
        if (action instanceof TLRPC.TL_messageActionPhoneCall) return s(R.string.PengramExportCall);
        return action.getClass().getSimpleName().replace("TL_messageAction", "").replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private static String richText(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) return esc(text);
        ArrayList<TLRPC.MessageEntity> sorted = new ArrayList<>(entities);
        Collections.sort(sorted, Comparator.comparingInt(e -> e.offset));
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (TLRPC.MessageEntity e : sorted) {
            if (e.offset < at || e.offset < 0 || e.length <= 0 || (long)e.offset + e.length > text.length()) continue;
            out.append(esc(text.substring(at, e.offset)));
            String value = text.substring(e.offset, e.offset + e.length);
            String url = e instanceof TLRPC.TL_messageEntityTextUrl ? e.url : e instanceof TLRPC.TL_messageEntityUrl ? value : null;
            if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) out.append("<a rel=\"noreferrer noopener\" href=\"").append(esc(url)).append("\">").append(esc(value)).append("</a>");
            else if (e instanceof TLRPC.TL_messageEntityBold) out.append("<b>").append(esc(value)).append("</b>");
            else if (e instanceof TLRPC.TL_messageEntityItalic) out.append("<i>").append(esc(value)).append("</i>");
            else if (e instanceof TLRPC.TL_messageEntityCode || e instanceof TLRPC.TL_messageEntityPre) out.append("<code>").append(esc(value)).append("</code>");
            else out.append(esc(value));
            at = e.offset + e.length;
        }
        out.append(esc(text.substring(at)));
        return out.toString();
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String date(int seconds) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(seconds * 1000L));
    }

    private static BufferedWriter writer(File file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new java.io.FileOutputStream(file), StandardCharsets.UTF_8));
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Replace one generated marker at a time; never buffer a whole attachment or page. */
    private void copyHtml(File source, OutputStream out) throws Exception {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                check();
                int at = 0;
                while (true) {
                    int begin = line.indexOf("pengram-inline:", at);
                    if (begin < 0) break;
                    int end = begin + "pengram-inline:".length();
                    while (end < line.length() && line.charAt(end) >= '0' && line.charAt(end) <= '9') end++;
                    if (end == begin + "pengram-inline:".length() || end >= line.length() || line.charAt(end) != '"')
                        throw new IOException("Invalid media marker");
                    int id;
                    try { id = Integer.parseInt(line.substring(begin + "pengram-inline:".length(), end)); }
                    catch (NumberFormatException e) { throw new IOException("Invalid media identifier", e); }
                    InlineMedia item = inlineMedia.remove(id);
                    if (item == null) throw new IOException("Missing media reference");
                    write(out, line.substring(at, begin));
                    write(out, "data:" + item.mime + ";base64,");
                    copyBase64(item.file, out);
                    writtenMedia++;
                    if (writtenMedia % 10 == 0 || writtenMedia == exportedFiles)
                        status(s(R.string.PengramExportWriting) + " · " + writtenMedia + " / " + exportedFiles);
                    at = end;
                }
                write(out, line.substring(at) + "\n");
            }
        }
    }

    private void copyBase64(File source, OutputStream out) throws Exception {
        if (!source.isFile() || source.length() == 0) throw new IOException("Media disappeared during export");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buffer = new byte[12 * 1024]; // divisible by 3, at most 16 KiB encoded
            int carry = 0, size;
            while ((size = in.read(buffer, carry, buffer.length - carry)) != -1) {
                check();
                int length = size + carry;
                int complete = length - length % 3;
                if (complete > 0) out.write(Base64.encode(buffer, 0, complete, Base64.NO_WRAP));
                carry = length - complete;
                if (carry > 0) System.arraycopy(buffer, complete, buffer, 0, carry);
            }
            if (carry > 0) out.write(Base64.encode(buffer, 0, carry, Base64.NO_WRAP));
        }
    }

    private static String s(int id) { return org.telegram.messenger.LocaleController.getString(id); }

    private void showResult(String message) {
        Activity activity = fragment.getParentActivity();
        if (activity != null && !activity.isFinishing()) new AlertDialog.Builder(activity)
                .setTitle(s(R.string.PengramExportChat)).setMessage(message)
                .setPositiveButton(s(R.string.OK), null).show();
    }
}
