package org.telegram.ui.Components;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Local quote cards. Rendering is bounded and never uploads message content to another service. */
public final class PengramQuoteMaker {
    private PengramQuoteMaker() { }

    public static final String KEY_NAME = "quoteShowName";
    public static final String KEY_TIME = "quoteShowTime";
    public static final String KEY_MEDIA = "quoteIncludeMedia";
    public static final String KEY_DARK = "quoteDarkCard";
    public static final String KEY_JPEG = "quoteUseJpeg";
    public static final String KEY_STYLE = "quoteAccentStyle";
    public static final int MAX_MESSAGES = 12;
    private static final int WIDTH = 720;
    private static final long MAX_PIXELS = 7_000_000L;

    private static final class Entry {
        String name, text, time;
        StaticLayout layout;
        Bitmap media;
        int imageHeight, height;
    }

    private static final class Result {
        File file;
        Bitmap preview;
        boolean jpeg;
    }

    public static void show(ChatActivity chat, ArrayList<MessageObject> selected) {
        final Activity activity = chat.getParentActivity();
        if (activity == null || selected == null || selected.isEmpty()) return;
        if (selected.size() > MAX_MESSAGES) {
            error(chat, R.string.PengramQuoteTooMany);
            return;
        }
        final ArrayList<Entry> entries = new ArrayList<>();
        final MessagesController controller = MessagesController.getInstance(chat.getCurrentAccount());
        final boolean names = PengramConfig.getBool(KEY_NAME, true);
        final ArrayList<File> imagePaths = new ArrayList<>();
        for (MessageObject message : selected) {
            if (message == null || message.messageOwner == null || message.isSponsored()
                    || message.messageOwner.action != null) {
                error(chat, R.string.PengramQuoteUnsupported);
                return;
            }
            final Entry entry = new Entry();
            final long senderId = message.getSenderId();
            if (names) {
                if (senderId > 0) {
                    TLRPC.User user = controller.getUser(senderId);
                    entry.name = user == null ? null : UserObject.getUserName(user);
                } else if (senderId < 0) {
                    TLRPC.Chat sender = controller.getChat(-senderId);
                    entry.name = sender == null ? null : sender.title;
                }
                if (TextUtils.isEmpty(entry.name)) entry.name = activity.getString(R.string.PengramQuoteUnknown);
            }
            entry.text = message.messageText == null ? "" : message.messageText.toString();
            if (TextUtils.isEmpty(entry.text) && message.caption != null) entry.text = message.caption.toString();
            if (message.isPhoto() && !PengramConfig.getBool(KEY_MEDIA, true) && TextUtils.isEmpty(entry.text)) {
                entry.text = activity.getString(R.string.PengramQuotePhoto);
            } else if (TextUtils.isEmpty(entry.text) && message.isVideo()) {
                entry.text = activity.getString(R.string.PengramQuoteVideo);
            } else if (TextUtils.isEmpty(entry.text) && message.isVoice()) {
                entry.text = activity.getString(R.string.PengramQuoteVoice);
            } else if (TextUtils.isEmpty(entry.text) && message.isDocument()) {
                entry.text = activity.getString(R.string.PengramQuoteDocument);
            }
            if (TextUtils.isEmpty(entry.text) && !message.isPhoto()) {
                error(chat, R.string.PengramQuoteUnsupported);
                return;
            }
            if (entry.text.length() > 5000) {
                error(chat, R.string.PengramQuoteTooLong);
                return;
            }
            if (PengramConfig.getBool(KEY_TIME, true)) {
                entry.time = new SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
                        .format(new Date(message.messageOwner.date * 1000L));
            }
            // Resolve the path on the UI thread, but decode the media on the worker below.
            if (message.isPhoto() && PengramConfig.getBool(KEY_MEDIA, true)) {
                File local = FileLoader.getInstance(chat.getCurrentAccount()).getPathToMessage(message.messageOwner);
                if (local == null || !local.isFile() || local.length() == 0) {
                    error(chat, R.string.PengramQuoteDownload);
                    return;
                }
                entry.text = (entry.text == null ? "" : entry.text);
                imagePaths.add(local); // matched to the same entry below
            } else {
                imagePaths.add(null);
            }
            entries.add(entry);
        }
        final boolean jpeg = PengramConfig.getBool(KEY_JPEG, false);
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(3, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final AlertDialog spinner = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, chat.getResourceProvider());
        chat.showDialog(spinner);
        Utilities.globalQueue.postRunnable(() -> {
            Result result = null;
            try {
                result = render(activity, entries, imagePaths, dark, style, jpeg);
            } catch (Throwable ex) {
                FileLog.e(ex);
            } finally {
                for (Entry e : entries) {
                    if (e.media != null && !e.media.isRecycled()) e.media.recycle();
                }
            }
            final Result ready = result;
            AndroidUtilities.runOnUIThread(() -> {
                spinner.dismiss();
                if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                    if (ready != null && ready.preview != null) ready.preview.recycle();
                    return;
                }
                if (ready == null) error(chat, R.string.PengramQuoteRenderError);
                else preview(chat, ready);
            });
        });
    }

    private static Bitmap decode(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1100 || bounds.outHeight / options.inSampleSize > 1100) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static Result render(Activity activity, ArrayList<Entry> entries, ArrayList<File> paths,
                                 boolean dark, int style, boolean jpeg) throws Exception {
        final int accent = new int[]{0xff5685f8, 0xff23ad85, 0xffd26496, 0xffeda546}[style];
        final int background = dark ? 0xff141a28 : 0xffeef3ff;
        final int surface = dark ? 0xff222b3b : Color.WHITE;
        final int ink = dark ? Color.WHITE : 0xff1e293b;
        final int muted = dark ? 0xffa6b4ca : 0xff77869c;
        final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(ink);
        body.setTextSize(27);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int total = 32;
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            if (paths.get(i) != null) {
                e.media = decode(paths.get(i));
                if (e.media == null) throw new IllegalStateException("Photo is unavailable");
                e.imageHeight = Math.min(320, Math.max(110, 624 * e.media.getHeight() / Math.max(1, e.media.getWidth())));
            }
            e.layout = TextUtils.isEmpty(e.text) ? null : new StaticLayout(e.text, body, 600,
                    Layout.Alignment.ALIGN_NORMAL, 1.18f, 0, false);
            final int textHeight = e.layout == null ? 0 : e.layout.getHeight();
            e.height = 30 + (e.name == null ? 0 : 34) + (e.imageHeight == 0 ? 0 : e.imageHeight + 14)
                    + textHeight + (e.time == null ? 0 : 28) + 26;
            total += e.height + 16;
        }
        total += 16;
        if (total < 1 || (long) WIDTH * total > MAX_PIXELS) throw new IllegalStateException("Quote too large");
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, total, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(background);
            int y = 32;
            for (Entry e : entries) {
                paint.setColor(surface);
                canvas.drawRoundRect(new RectF(24, y, WIDTH - 24, y + e.height), 28, 28, paint);
                paint.setColor(accent);
                canvas.drawRoundRect(new RectF(24, y + 20, 30, y + e.height - 20), 3, 3, paint);
                int inner = y + 30;
                if (e.name != null) {
                    paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    paint.setTextSize(25);
                    paint.setColor(accent);
                    canvas.drawText(TextUtils.ellipsize(e.name, new TextPaint(paint), 590, TextUtils.TruncateAt.END).toString(), 60, inner + 24, paint);
                    inner += 34;
                    paint.setTypeface(android.graphics.Typeface.DEFAULT);
                }
                if (e.media != null) {
                    canvas.save();
                    RectF dest = new RectF(60, inner, WIDTH - 60, inner + e.imageHeight);
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addRoundRect(dest, 15, 15, android.graphics.Path.Direction.CW);
                    canvas.clipPath(clip);
                    paint.setColor(0xffd8e0ed);
                    canvas.drawRect(dest, paint);
                    int mw = e.media.getWidth(), mh = e.media.getHeight();
                    float scale = Math.max(dest.width() / mw, dest.height() / mh);
                    float drawnW = mw * scale, drawnH = mh * scale;
                    paint.setColor(Color.WHITE);
                    canvas.drawBitmap(e.media, new Rect(0, 0, mw, mh),
                            new RectF(dest.centerX() - drawnW / 2, dest.centerY() - drawnH / 2,
                                    dest.centerX() + drawnW / 2, dest.centerY() + drawnH / 2), paint);
                    canvas.restore();
                    inner += e.imageHeight + 14;
                }
                if (e.layout != null) {
                    canvas.save();
                    canvas.translate(60, inner);
                    e.layout.draw(canvas);
                    canvas.restore();
                    inner += e.layout.getHeight();
                }
                if (e.time != null) {
                    paint.setColor(muted);
                    paint.setTextSize(19);
                    paint.setTypeface(android.graphics.Typeface.DEFAULT);
                    canvas.drawText(e.time, 60, inner + 24, paint);
                }
                y += e.height + 16;
            }
            File directory = activity.getExternalCacheDir();
            if (directory == null) directory = activity.getCacheDir();
            // Quotes are temporary and may contain personal data. Clear expired
            // render files, but keep recent ones until Telegram finishes uploading.
            File[] old = directory.listFiles((dir, name) -> name.startsWith("pengram-quote-"));
            if (old != null) {
                for (File candidate : old) {
                    if (System.currentTimeMillis() - candidate.lastModified() > 86_400_000L) {
                        candidate.delete();
                    }
                }
            }
            final File file = new File(directory, "pengram-quote-" + System.nanoTime() + (jpeg ? ".jpg" : ".png"));
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (!bitmap.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 94, out)) {
                    throw new IllegalStateException("Image compression failed");
                }
            }
            BitmapFactory.Options previewOptions = new BitmapFactory.Options();
            previewOptions.inSampleSize = total > 1500 ? (total > 3000 ? 4 : 2) : 1;
            Result result = new Result();
            result.file = file;
            result.jpeg = jpeg;
            result.preview = BitmapFactory.decodeFile(file.getAbsolutePath(), previewOptions);
            if (result.preview == null) throw new IllegalStateException("Preview unavailable");
            return result;
        } finally {
            bitmap.recycle();
        }
    }

    private static void preview(ChatActivity chat, Result result) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) return;
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        ImageView image = new ImageView(activity);
        image.setImageBitmap(result.preview);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scroll.addView(image, new ScrollView.LayoutParams(-1, -2));
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), 0);
        TextView hint = new TextView(activity);
        hint.setText(R.string.PengramQuotePrivacyHint);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        container.addView(hint, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(40)));
        container.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(AndroidUtilities.dp(420), AndroidUtilities.displaySize.y / 2)));
        final boolean canSend = chat.getCurrentChat() == null ||
                ChatObject.canWriteToChat(chat.getCurrentChat()) && ChatObject.canSendPhoto(chat.getCurrentChat());
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, chat.getResourceProvider());
        builder.setTitle(activity.getString(R.string.PengramQuotePreview));
        builder.setView(container);
        if (canSend) builder.setPositiveButton(activity.getString(R.string.PengramQuoteSend), (d, which) -> send(chat, result));
        builder.setNeutralButton(activity.getString(R.string.PengramQuoteSave), (d, which) -> save(chat, result));
        builder.setNegativeButton(activity.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        // Let the ImageView release its bitmap with the dialog after the exit animation.

        chat.showDialog(dialog);
    }

    private static void send(ChatActivity chat, Result result) {
        if (chat.getParentActivity() == null || !result.file.exists()) {
            error(chat, R.string.PengramQuoteRenderError);
            return;
        }
        if (chat.getCurrentChat() != null && (!ChatObject.canWriteToChat(chat.getCurrentChat())
                || !ChatObject.canSendPhoto(chat.getCurrentChat()))) {
            error(chat, R.string.PengramQuoteCannotSend);
            return;
        }
        try {
            MessageObject replyToTop = chat.getThreadMessage();
            if (replyToTop == null && chat.getTopicId() > 0 && chat.getTopicId() <= Integer.MAX_VALUE) {
                TLRPC.TL_message topic = new TLRPC.TL_message();
                topic.id = (int) chat.getTopicId();
                topic.message = "";
                topic.peer_id = MessagesController.getInstance(chat.getCurrentAccount()).getPeer(chat.getDialogId());
                replyToTop = new MessageObject(chat.getCurrentAccount(), topic, false, false);
            }
            SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(chat.getCurrentAccount()),
                    result.file.getAbsolutePath(), null, chat.getDialogId(), null, replyToTop,
                    null, null, null, null, null, 0, null, true, 0, 0, SendMessageChatArguments.EMPTY);
        } catch (Throwable e) {
            FileLog.e(e);
            error(chat, R.string.PengramQuoteCannotSend);
        }
    }

    private static void save(ChatActivity chat, Result result) {
        final Activity activity = chat.getParentActivity();
        if (activity == null || !result.file.exists()) return;
        if (Build.VERSION.SDK_INT < 29) {
            if (AndroidUtilities.isInternalUri(Uri.fromFile(result.file))) {
                error(chat, R.string.PengramQuoteSaveError);
                return;
            }
            MediaController.saveFile(result.file.getAbsolutePath(), activity, 0, null,
                    result.jpeg ? "image/jpeg" : "image/png", uri -> {
                        if (uri == null) error(chat, R.string.PengramQuoteSaveError);
                    });
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            Uri uri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, result.file.getName());
                values.put(MediaStore.Images.Media.MIME_TYPE, result.jpeg ? "image/jpeg" : "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pengram");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Gallery is unavailable");
                try (OutputStream stream = activity.getContentResolver().openOutputStream(uri);
                     java.io.FileInputStream input = new java.io.FileInputStream(result.file)) {
                    if (stream == null) throw new IllegalStateException("Gallery stream is unavailable");
                    byte[] buffer = new byte[32768];
                    int n;
                    while ((n = input.read(buffer)) >= 0) stream.write(buffer, 0, n);
                }
                ContentValues visible = new ContentValues();
                visible.put(MediaStore.Images.Media.IS_PENDING, 0);
                activity.getContentResolver().update(uri, visible, null, null);
            } catch (Throwable e) {
                FileLog.e(e);
                if (uri != null) activity.getContentResolver().delete(uri, null, null);
                AndroidUtilities.runOnUIThread(() -> error(chat, R.string.PengramQuoteSaveError));
            }
        });
    }

    private static void error(ChatActivity chat, int stringId) {
        Activity activity = chat.getParentActivity();
        if (activity != null && !activity.isFinishing()) {
            chat.showDialog(new AlertDialog.Builder(activity, chat.getResourceProvider())
                    .setMessage(activity.getString(stringId))
                    .setPositiveButton(activity.getString(R.string.OK), null).create());
        }
    }
}
