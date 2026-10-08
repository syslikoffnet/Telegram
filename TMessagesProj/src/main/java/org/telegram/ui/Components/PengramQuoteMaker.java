package org.telegram.ui.Components;

import android.app.Activity;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.HorizontalScrollView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
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
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
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
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.DialogsActivity;
import androidx.core.content.FileProvider;
import java.util.HashMap;

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

    /** 0: compact preview, 1: render and send to the current chat immediately. */
    public static final String KEY_ACTION_MODE = "quoteActionMode";
    public static final String KEY_NAME = "quoteShowName";
    public static final String KEY_AVATAR = "quoteShowAvatar";
    public static final String KEY_TIME = "quoteShowTime";
    public static final String KEY_MEDIA = "quoteIncludeMedia";
    public static final String KEY_DARK = "quoteDarkCard";
    public static final String KEY_JPEG = "quoteUseJpeg";
    public static final String KEY_STYLE = "quoteAccentStyle";
    public static final String KEY_ANON_MENTIONS = "quoteAnonMentions";
    public static final String KEY_WATERMARK = "quoteWatermarkEnabled";
    public static final String KEY_BACKGROUND = "quoteBackground";
    public static final String KEY_BACKGROUND_COLOR = "quoteBackgroundColor";
    public static final String KEY_PADDING = "quotePadding";
    public static final String KEY_RADIUS = "quoteRadius";
    public static final String KEY_SCALE = "quoteScale";
    public static final String KEY_JPEG_QUALITY = "quoteJpegQuality";
    public static final String KEY_WATERMARK_POSITION = "quoteWatermarkPosition";
    public static final String KEY_WATERMARK_OPACITY = "quoteWatermarkOpacity";
    public static final String KEY_FAKE_NAME = "quoteFakeName";
    public static final String KEY_WATERMARK_TEXT = "quoteWatermarkText";
    public static final String KEY_STICKER_TRANSPARENT = "quoteStickerTransparent";
    public static final String KEY_LOGO_SIZE = "quoteLogoSize";

    public static File logoFile(Context context) {
        return new File(context.getFilesDir(), "pengram_quote_logo.png");
    }
    public static final int MAX_MESSAGES = 12;
    private static final int WIDTH = 720;
    private static final long MAX_PIXELS = 7_000_000L;

    private static final class Entry {
        String name, text, time;
        long groupId, senderId;
        StaticLayout layout;
        Bitmap media;
        Bitmap avatar;
        File avatarFile;
        final ArrayList<File> albumPaths = new ArrayList<>();
        final ArrayList<Bitmap> albumImages = new ArrayList<>();
        int imageHeight, height;
    }

    private static final class Result {
        File file;
        Bitmap preview;
        boolean jpeg;
    }

    private static boolean active(Activity activity) {
        return activity != null && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private static MessageObject topMessage(int account, long dialogId, long topicId) {
        if (topicId <= 0 || topicId > Integer.MAX_VALUE) return null;
        TLRPC.TL_message topic = new TLRPC.TL_message();
        topic.id = (int) topicId;
        topic.message = "";
        topic.peer_id = MessagesController.getInstance(account).getPeer(dialogId);
        return new MessageObject(account, topic, false, false);
    }

    private static Uri shareUri(Activity activity, File file) {
        return FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
    }


    /** Protection is checked here, not only when the menu was built: permissions can change. */
    private static boolean isRestricted(ChatActivity chat, ArrayList<MessageObject> selected) {
        if (chat.getCurrentEncryptedChat() != null || chat.isPeerNoForwards()) return true;
        boolean bypass = PengramConfig.isBypassingForwardRestrictions();
        MessagesController controller = MessagesController.getInstance(chat.getCurrentAccount());
        for (MessageObject message : selected) {
            if (message == null || message.messageOwner == null) continue;
            if (message.messageOwner instanceof TLRPC.TL_message_secret
                    || message.isVoiceOnce() || message.isRoundOnce() || message.isSecretMedia()
                    || message.needDrawBluredPreview() || message.type == MessageObject.TYPE_PAID_MEDIA
                    || !bypass && (message.messageOwner.noforwards
                    || controller.isPeerNoForwards(message.getDialogId()))) return true;
        }
        return false;
    }

    public static void show(ChatActivity chat, ArrayList<MessageObject> selected) {
        final Activity activity = chat.getParentActivity();
        if (!active(activity) || selected == null || selected.isEmpty()) return;
        if (selected.size() > MAX_MESSAGES) {
            error(chat, R.string.PengramQuoteTooMany);
            return;
        }
        if (isRestricted(chat, selected)) {
            error(chat, R.string.PengramQuoteRestricted);
            return;
        }
        final ArrayList<Entry> entries = new ArrayList<>();
        final MessagesController controller = MessagesController.getInstance(chat.getCurrentAccount());
        final boolean names = PengramConfig.getBool(KEY_NAME, true);
        final ArrayList<File> imagePaths = new ArrayList<>();
        for (MessageObject message : selected) {
            if (message == null || message.messageOwner == null || message.isSponsored()
                    ) {
                error(chat, R.string.PengramQuoteUnsupported);
                return;
            }
            final Entry entry = new Entry();
            final long senderId = message.getSenderId();
            entry.senderId = senderId;
            entry.groupId = message.getGroupId();
            if (names) {
                final String fakeName = PengramConfig.getQuoteFakeName().trim();
                if (!fakeName.isEmpty()) entry.name = fakeName;
                else if (senderId > 0) {
                    TLRPC.User user = controller.getUser(senderId);
                    entry.name = user == null ? null : UserObject.getUserName(user);
                } else if (senderId < 0) {
                    TLRPC.Chat sender = controller.getChat(-senderId);
                    entry.name = sender == null ? null : sender.title;
                }
                if (TextUtils.isEmpty(entry.name)) entry.name = safeString(activity, R.string.PengramQuoteUnknown);
            }
            if (names && PengramConfig.getBool(KEY_AVATAR, true)
                    && PengramConfig.getQuoteFakeName().trim().isEmpty()) {
                org.telegram.tgnet.TLObject avatarPeer = senderId > 0 ? controller.getUser(senderId)
                        : senderId < 0 ? controller.getChat(-senderId) : null;
                TLRPC.FileLocation avatarLocation = null;
                if (avatarPeer instanceof TLRPC.User && ((TLRPC.User) avatarPeer).photo != null)
                    avatarLocation = ((TLRPC.User) avatarPeer).photo.photo_small;
                else if (avatarPeer instanceof TLRPC.Chat && ((TLRPC.Chat) avatarPeer).photo != null)
                    avatarLocation = ((TLRPC.Chat) avatarPeer).photo.photo_small;
                if (avatarLocation != null) {
                    File file = FileLoader.getInstance(chat.getCurrentAccount()).getPathToAttach(avatarLocation, true);
                    if (file != null && file.isFile() && file.length() > 0) entry.avatarFile = file;
                    else {
                        BitmapDrawable cached = ImageLoader.getInstance().getImageFromMemory(avatarLocation, null, "50_50");
                        if (cached != null && cached.getBitmap() != null && !cached.getBitmap().isRecycled()) {
                            try { entry.avatar = cached.getBitmap().copy(Bitmap.Config.ARGB_8888, false); }
                            catch (Throwable e) { FileLog.e(e); }
                        }
                        if (entry.avatar == null && avatarPeer != null) {
                            try {
                                FileLoader.getInstance(chat.getCurrentAccount()).loadFile(
                                        ImageLocation.getForUserOrChat(chat.getCurrentAccount(), avatarPeer, ImageLocation.TYPE_SMALL),
                                        avatarPeer, "jpg", FileLoader.PRIORITY_LOW, 1);
                            } catch (Throwable e) { FileLog.e(e); }
                        }
                    }
                }
            }
            entry.text = !TextUtils.isEmpty(message.caption) ? message.caption.toString()
                    : message.isPhoto() && PengramConfig.getBool(KEY_MEDIA, true) ? ""
                    : message.messageText == null ? "" : message.messageText.toString();
            if (MessageObject.getMedia(message.messageOwner) instanceof TLRPC.TL_messageMediaPoll) {
                TLRPC.TL_messageMediaPoll poll = (TLRPC.TL_messageMediaPoll) MessageObject.getMedia(message.messageOwner);
                if (poll.poll != null && poll.poll.question != null) {
                    StringBuilder question = new StringBuilder(poll.poll.question.text);
                    if (poll.poll.answers != null) {
                        int number = 1;
                        for (TLRPC.PollAnswer answer : poll.poll.answers) {
                            if (answer != null && answer.text != null) {
                                question.append("\n").append(number++).append(". ").append(answer.text.text);
                            }
                        }
                    }
                    entry.text = question.toString();
                }
            }
            if (message.isPhoto() && !PengramConfig.getBool(KEY_MEDIA, true) && TextUtils.isEmpty(entry.text)) {
                entry.text = safeString(activity, R.string.PengramQuotePhoto);
            } else if (TextUtils.isEmpty(entry.text) && message.isVideo()) {
                entry.text = safeString(activity, R.string.PengramQuoteVideo);
            } else if (TextUtils.isEmpty(entry.text) && message.isVoice()) {
                entry.text = safeString(activity, R.string.PengramQuoteVoice);
            } else if (TextUtils.isEmpty(entry.text) && message.isSticker()) {
                entry.text = safeString(activity, R.string.PengramQuoteSticker);
            } else if (TextUtils.isEmpty(entry.text) && message.isDocument()) {
                entry.text = safeString(activity, R.string.PengramQuoteDocument);
            }
            if (TextUtils.isEmpty(entry.text) && !message.isPhoto()) {
                error(chat, R.string.PengramQuoteUnsupported);
                return;
            }
            if (PengramConfig.getBool(KEY_ANON_MENTIONS, false)) {
                entry.text = entry.text.replaceAll("(?<![\\w@])@[A-Za-z0-9_]{3,32}", "@•••");
            }
            if (entry.text.length() > 5000) {
                error(chat, R.string.PengramQuoteTooLong);
                return;
            }
            if (PengramConfig.getBool(KEY_TIME, true)) {
                entry.time = new SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
                        .format(new Date(message.messageOwner.date * 1000L));
            }
            // Resolve local media before queuing work; missing photos fail explicitly.
            File local = null;
            if (PengramConfig.getBool(KEY_MEDIA, true) && (message.isPhoto() || message.isVideo()
                    || message.isGif() || message.isSticker() || message.isRoundVideo())) {
                final FileLoader loader = FileLoader.getInstance(chat.getCurrentAccount());
                if (message.isPhoto() || message.isSticker()) {
                    local = loader.getPathToMessage(message.messageOwner);
                }
                if ((local == null || !local.isFile() || local.length() == 0)
                        && message.photoThumbs != null && !message.photoThumbs.isEmpty()) {
                    TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, 640);
                    if (thumb != null) local = loader.getPathToAttach(thumb, true);
                }
                if (local == null || !local.isFile() || local.length() == 0) {
                    if (message.isPhoto()) {
                        error(chat, R.string.PengramQuoteDownload);
                        return;
                    }
                    local = null; // Video and sticker still have a visible type label.
                }
            }
            if (local != null && entry.groupId != 0 && !entries.isEmpty()) {
                Entry previous = entries.get(entries.size() - 1);
                if (previous.groupId == entry.groupId && previous.senderId == entry.senderId
                        && imagePaths.get(imagePaths.size() - 1) != null) {
                    previous.albumPaths.add(local);
                    if (!TextUtils.isEmpty(entry.text)) {
                        previous.text = TextUtils.isEmpty(previous.text) ? entry.text : previous.text + "\n" + entry.text;
                    }
                    previous.time = entry.time;
                    continue;
                }
            }
            imagePaths.add(local);
            entries.add(entry);
        }
        final boolean jpeg = PengramConfig.getBool(KEY_JPEG, false)
                && PengramConfig.getIntCached(KEY_BACKGROUND, 0) != 1;
        final String chatName = chat.getCurrentChat() != null ? chat.getCurrentChat().title
                : chat.getCurrentUser() != null ? UserObject.getUserName(chat.getCurrentUser()) : "";
        final int themedBackground = Theme.getColor(Theme.key_chat_wallpaper, chat.getResourceProvider());
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(3, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final AlertDialog spinner = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, chat.getResourceProvider());
        chat.showDialog(spinner);
        Utilities.globalQueue.postRunnable(() -> {
            Result result = null;
            try {
                result = render(activity, entries, imagePaths, dark, style, jpeg, false,
                        themedBackground, chatName);
            } catch (Throwable ex) {
                FileLog.e(ex);
            } finally {
                for (Entry e : entries) {
                    if (e.media != null && !e.media.isRecycled()) e.media.recycle();
                    for (Bitmap tile : e.albumImages) if (tile != null && !tile.isRecycled()) tile.recycle();
                    e.albumImages.clear();
                }
            }
            final Result ready = result;
            AndroidUtilities.runOnUIThread(() -> {
                spinner.dismiss();
                if (!active(activity)) {
                    if (ready != null && ready.preview != null) ready.preview.recycle();
                    return;
                }
                if (ready == null) {
                    error(chat, R.string.PengramQuoteRenderError);
                } else if (isRestricted(chat, selected)) {
                    // A chat can turn on content protection while the background render runs.
                    ready.preview.recycle();
                    ready.file.delete();
                    error(chat, R.string.PengramQuoteRestricted);
                } else {
                    if (PengramConfig.getIntCached(KEY_ACTION_MODE, 0) == 1
                            && (chat.getCurrentChat() == null || ChatObject.canWriteToChat(chat.getCurrentChat())
                            && ChatObject.canSendPhoto(chat.getCurrentChat()))) {
                        send(chat, ready);
                        if (ready.preview != null) ready.preview.recycle();
                    } else {
                        preview(chat, ready, new ArrayList<>(selected), entries, imagePaths);
                    }
                }
            });
        });
    }

    private static Bitmap decode(File file) {
        return decode(file, false);
    }

    private static Bitmap decode(File file, boolean alpha) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1100 || bounds.outHeight / options.inSampleSize > 1100) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static Result render(Activity activity, ArrayList<Entry> entries, ArrayList<File> paths,
                                 boolean dark, int style, boolean jpeg, boolean sticker,
                                 int themedBackground, String chatName) throws Exception {
        final int accent = new int[]{0xff5685f8, 0xff23ad85, 0xffd26496, 0xffeda546}[style];
        int bgType = sticker && PengramConfig.getBool(KEY_STICKER_TRANSPARENT, true) ? 1
                : Math.max(0, Math.min(2, PengramConfig.getIntCached(KEY_BACKGROUND, 0)));
        final int background = bgType == 1 && !jpeg ? Color.TRANSPARENT
                : bgType == 2 ? PengramConfig.getIntCached(KEY_BACKGROUND_COLOR, 0xffeef3ff) | 0xff000000
                : bgType == 0 ? themedBackground | 0xff000000
                : dark ? 0xff141a28 : 0xffeef3ff;
        final int pad = Math.max(0, Math.min(72, PengramConfig.getIntCached(KEY_PADDING, 24)));
        final int radius = Math.max(0, Math.min(64, PengramConfig.getIntCached(KEY_RADIUS, 28)));
        final int contentLeft = pad + 36;
        final int contentWidth = WIDTH - contentLeft * 2;
        final float scale = sticker ? 1f : new float[]{1f, 1.5f, 2f}[
                Math.max(0, Math.min(2, PengramConfig.getIntCached(KEY_SCALE, 0)))];
        final int surface = dark ? 0xff222b3b : Color.WHITE;
        final int ink = dark ? Color.WHITE : 0xff1e293b;
        final int muted = dark ? 0xffa6b4ca : 0xff77869c;
        float fontScale = Math.max(1f, Math.min(1.55f, activity.getResources().getConfiguration().fontScale));
        final int nameHeight = (int) Math.ceil(34 * fontScale);
        final int timeHeight = (int) Math.ceil(28 * fontScale);
        final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(ink);
        body.setTextSize(27 * fontScale);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int total = pad + 8;
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            if (paths.get(i) != null) {
                e.media = decode(paths.get(i));
                if (e.media == null) throw new IllegalStateException("Photo is unavailable");
                e.imageHeight = Math.min(320, Math.max(110, contentWidth * e.media.getHeight() / Math.max(1, e.media.getWidth())));
                if (!e.albumPaths.isEmpty()) {
                    for (File tile : e.albumPaths) {
                        Bitmap media = decode(tile);
                        if (media == null) throw new IllegalStateException("Album media is unavailable");
                        e.albumImages.add(media);
                    }
                    int cell = (contentWidth - 8) / 2;
                    e.imageHeight = ((1 + e.albumImages.size() + 1) / 2) * (cell + 8) - 8;
                }
            }
            e.layout = TextUtils.isEmpty(e.text) ? null : new StaticLayout(e.text, body, contentWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.18f, 0, false);
            final int textHeight = e.layout == null ? 0 : e.layout.getHeight();
            e.height = 30 + (e.name == null ? 0 : nameHeight) + (e.imageHeight == 0 ? 0 : e.imageHeight + 14)
                    + textHeight + (e.time == null ? 0 : timeHeight) + 26;
            total += e.height + 16;
        }
        total += pad + 8;
        final int outWidth = Math.round(WIDTH * scale);
        final int outHeight = Math.round(total * scale);
        if (total < 1 || (long) outWidth * outHeight > MAX_PIXELS)
            throw new IllegalStateException("Quote too large");
        Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(background);
            canvas.scale(scale, scale);
            int y = pad + 8;
            for (Entry e : entries) {
                paint.setColor(surface);
                canvas.drawRoundRect(new RectF(pad, y, WIDTH - pad, y + e.height), radius, radius, paint);
                paint.setColor(accent);
                canvas.drawRoundRect(new RectF(pad + 6, y + 20, pad + 12, y + e.height - 20), 3, 3, paint);
                int inner = y + 30;
                if (e.name != null) {
                    paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    paint.setTextSize(25 * fontScale);
                    paint.setColor(accent);
                    boolean avatar = PengramConfig.getBool(KEY_AVATAR, true);
                    int nameInset = avatar ? Math.round(33 * fontScale) : 0;
                    if (avatar && !e.name.isEmpty()) {
                        float cx = contentLeft + 13 * fontScale, cy = inner + 13 * fontScale;
                        Bitmap face = e.avatar != null && !e.avatar.isRecycled() ? e.avatar
                                : e.avatarFile != null ? decode(e.avatarFile, true) : null;
                        if (face != null) {
                            canvas.save();
                            android.graphics.Path circle = new android.graphics.Path();
                            circle.addCircle(cx, cy, 13 * fontScale, android.graphics.Path.Direction.CW);
                            canvas.clipPath(circle);
                            paint.setColor(Color.WHITE);
                            canvas.drawBitmap(face, null, new RectF(cx - 13 * fontScale, cy - 13 * fontScale,
                                    cx + 13 * fontScale, cy + 13 * fontScale), paint);
                            canvas.restore();
                            if (face != e.avatar) face.recycle();
                        } else {
                        canvas.drawCircle(cx, cy, 13 * fontScale, paint);
                        String initial = e.name.substring(0, Character.charCount(e.name.codePointAt(0)));
                        paint.setColor(Color.WHITE);
                        paint.setTextSize(16 * fontScale);
                        paint.setTextAlign(Paint.Align.CENTER);
                        canvas.drawText(initial, cx, cy + 5 * fontScale, paint);
                        paint.setTextAlign(Paint.Align.LEFT);
                        paint.setColor(accent);
                        paint.setTextSize(25 * fontScale);
                        }
                    }
                    canvas.drawText(TextUtils.ellipsize(e.name, new TextPaint(paint),
                            Math.max(20, contentWidth - nameInset), TextUtils.TruncateAt.END).toString(),
                            contentLeft + nameInset, inner + 24 * fontScale, paint);
                    inner += nameHeight;
                    paint.setTypeface(android.graphics.Typeface.DEFAULT);
                }
                if (e.media != null) {
                    if (!e.albumImages.isEmpty()) {
                        int cell = (contentWidth - 8) / 2;
                        drawImage(canvas, paint, e.media, new RectF(contentLeft, inner,
                                contentLeft + cell, inner + cell));
                        for (int j = 0; j < e.albumImages.size(); j++) {
                            int index = j + 1;
                            int x = contentLeft + (index % 2) * (cell + 8);
                            int top = inner + (index / 2) * (cell + 8);
                            drawImage(canvas, paint, e.albumImages.get(j),
                                    new RectF(x, top, x + cell, top + cell));
                        }
                    } else {
                        drawImage(canvas, paint, e.media,
                                new RectF(contentLeft, inner, WIDTH - contentLeft, inner + e.imageHeight));
                    }
                    inner += e.imageHeight + 14;
                }
                if (e.layout != null) {
                    canvas.save();
                    canvas.translate(contentLeft, inner);
                    e.layout.draw(canvas);
                    canvas.restore();
                    inner += e.layout.getHeight();
                }
                if (e.time != null) {
                    paint.setColor(muted);
                    paint.setTextSize(19 * fontScale);
                    paint.setTypeface(android.graphics.Typeface.DEFAULT);
                    canvas.drawText(e.time, contentLeft, inner + 24 * fontScale, paint);
                }
                y += e.height + 16;
            }
            if (PengramConfig.getBool(KEY_WATERMARK, false)) {
                drawWatermark(canvas, total, PengramConfig.getBool(KEY_NAME, true) ? chatName : "",
                        entries.size(), dark);
                File logo = logoFile(activity);
                if (logo.isFile()) {
                    Bitmap mark = decode(logo, true);
                    if (mark != null) {
                        try {
                            int size = Math.max(16, Math.min(128, PengramConfig.getIntCached(KEY_LOGO_SIZE, 48)));
                            int width = Math.max(1, Math.round(size * mark.getWidth() / (float) Math.max(mark.getWidth(), mark.getHeight())));
                            int height = Math.max(1, Math.round(size * mark.getHeight() / (float) Math.max(mark.getWidth(), mark.getHeight())));
                            int position = Math.max(0, Math.min(5, PengramConfig.getIntCached(KEY_WATERMARK_POSITION, 0)));
                            int x = position == 1 || position == 3 ? 30 : WIDTH - 30 - width;
                            int top = position == 2 || position == 3 ? 24 : total - 24 - height;
                            if (position == 4 || position == 5) { x = (WIDTH - width) / 2; top = (total - height) / 2; }
                            paint.setAlpha(Math.round(255 * Math.max(10, Math.min(100,
                                    PengramConfig.getIntCached(KEY_WATERMARK_OPACITY, 72))) / 100f));
                            canvas.drawBitmap(mark, null, new Rect(x, top, x + width, top + height), paint);
                            paint.setAlpha(255);
                        } finally {
                            mark.recycle();
                        }
                    }
                }
            }
            File directory = activity.getExternalCacheDir();
            if (directory == null) directory = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
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
                if (!bitmap.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                        Math.max(40, Math.min(100, PengramConfig.getIntCached(KEY_JPEG_QUALITY, 92))), out)) {
                    throw new IllegalStateException("Image compression failed");
                }
            }
            BitmapFactory.Options previewOptions = new BitmapFactory.Options();
            previewOptions.inSampleSize = outHeight > 1500 ? (outHeight > 3000 ? 4 : 2) : 1;
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

    private static void drawImage(Canvas canvas, Paint paint, Bitmap media, RectF dest) {
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(dest, 15, 15, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        paint.setColor(0xffd8e0ed);
        canvas.drawRect(dest, paint);
        int mw = media.getWidth(), mh = media.getHeight();
        float scale = Math.max(dest.width() / mw, dest.height() / mh);
        float w = mw * scale, h = mh * scale;
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(media, new Rect(0, 0, mw, mh),
                new RectF(dest.centerX() - w / 2, dest.centerY() - h / 2,
                        dest.centerX() + w / 2, dest.centerY() + h / 2), paint);
        canvas.restore();
    }

    private static void drawWatermark(Canvas canvas, int total, String chatName, int count, boolean dark) {
        String text = PengramConfig.getQuoteWatermarkText();
        if (TextUtils.isEmpty(text)) return;
        text = text.replace("{date}", new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()))
                .replace("{chat}", chatName == null ? "" : chatName)
                .replace("{count}", String.valueOf(count));
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(20);
        int alpha = Math.max(10, Math.min(100, PengramConfig.getIntCached(KEY_WATERMARK_OPACITY, 72)));
        p.setColor((Math.round(alpha * 2.55f) << 24) | (dark ? 0x00ffffff : 0x00203040));
        String label = TextUtils.ellipsize(text, p, WIDTH - 80, TextUtils.TruncateAt.END).toString();
        float measured = p.measureText(label);
        final int position = Math.max(0, Math.min(5, PengramConfig.getIntCached(KEY_WATERMARK_POSITION, 0)));
        if (position == 5) {
            int drawn = 0;
            for (int y = 48; y < total - 20 && drawn < 400; y += 130) {
                for (int x = -80; x < WIDTH - 30 && drawn < 400; x += (int) Math.max(160, measured + 48)) {
                    canvas.save();
                    canvas.rotate(-20, x, y);
                    canvas.drawText(label, x, y, p);
                    canvas.restore();
                    drawn++;
                }
            }
            return;
        }
        float x = position == 1 || position == 3 ? 32 : WIDTH - 32 - measured;
        float y = position == 2 || position == 3 ? 58 : total - 28;
        if (position == 4) {
            x = (WIDTH - measured) / 2;
            y = total / 2f;
        }
        canvas.drawText(label, Math.max(8, x), y, p);
    }

    /** Guard every string used by quote creation, including its error dialogs. */
    private static String safeString(Activity activity, int resId) {
        try {
            return activity.getString(resId);
        } catch (android.content.res.Resources.NotFoundException e) {
            FileLog.e(e);
            Locale currentLocale = org.telegram.messenger.LocaleController.getInstance().getCurrentLocale();
            boolean ru = currentLocale != null && "ru".equals(currentLocale.getLanguage());
            if (resId == R.string.PengramQuoteTooMany) return ru ? "Выберите не более 12 сообщений." : "Select no more than 12 messages.";
            if (resId == R.string.PengramQuoteUnsupported) return ru ? "Этот тип сообщения нельзя преобразовать в цитату." : "This type of message cannot be quoted.";
            if (resId == R.string.PengramQuoteUnknown) return ru ? "Неизвестный отправитель" : "Unknown sender";
            if (resId == R.string.PengramQuotePhoto) return ru ? "Фото" : "Photo";
            if (resId == R.string.PengramQuoteVideo) return ru ? "Видео" : "Video";
            if (resId == R.string.PengramQuoteVoice) return ru ? "Голосовое сообщение" : "Voice message";
            if (resId == R.string.PengramQuoteSticker) return ru ? "Стикер" : "Sticker";
            if (resId == R.string.PengramQuoteDocument) return ru ? "Документ" : "Document";
            if (resId == R.string.PengramQuoteTooLong) return ru ? "Сообщение слишком длинное для цитаты-изображения." : "This message is too long for an image quote.";
            if (resId == R.string.PengramQuoteDownload) return ru ? "Скачайте фото перед созданием цитаты или отключите фотографии в настройках цитат." : "Download the photo before creating the quote, or turn off photos in quote settings.";
            if (resId == R.string.PengramQuoteRenderError) return ru ? "Не удалось создать цитату. Попробуйте выбрать меньше сообщений." : "Could not create the quote. Try selecting fewer messages.";
            if (resId == R.string.PengramQuotePrivacyHint) return ru ? "Проверьте имена и текст перед отправкой." : "Check names and message text before sharing.";
            if (resId == R.string.PengramQuoteNames) return ru ? "Показывать имена отправителей" : "Show sender names";
            if (resId == R.string.PengramQuoteAvatar) return ru ? "Инициалы отправителей" : "Sender initials";
            if (resId == R.string.PengramQuoteTimes) return ru ? "Показывать время сообщений" : "Show message times";
            if (resId == R.string.PengramQuoteMedia) return ru ? "Добавлять фотографии" : "Include photos";
            if (resId == R.string.PengramQuoteAnonMentions) return ru ? "Скрывать @упоминания в тексте" : "Hide @mentions in text";
            if (resId == R.string.PengramQuoteDark) return ru ? "Тёмный фон" : "Dark background";
            if (resId == R.string.PengramQuoteJpeg) return ru ? "JPEG вместо PNG" : "JPEG instead of PNG";
            if (resId == R.string.PengramQuoteWatermark) return ru ? "Добавлять водяной знак" : "Add watermark";
            if (resId == R.string.PengramQuoteBackground) return ru ? "Тип фона" : "Background type";
            if (resId == R.string.PengramQuoteSend) return ru ? "Отправить в чат" : "Send to chat";
            if (resId == R.string.PengramQuoteSendFile) return ru ? "Отправить файлом" : "Send as file";
            if (resId == R.string.PengramQuoteSendSticker) return ru ? "Отправить стикером" : "Send as sticker";
            if (resId == R.string.PengramQuoteOtherChat) return ru ? "Отправить в другой чат" : "Send to another chat";
            if (resId == R.string.PengramQuoteSave) return ru ? "Сохранить в галерею" : "Save to gallery";
            if (resId == R.string.PengramQuoteShare) return ru ? "Поделиться…" : "Share…";
            if (resId == R.string.PengramQuoteCopy) return ru ? "Копировать изображение" : "Copy image";
            if (resId == R.string.PengramQuotePreview) return ru ? "Предпросмотр цитаты" : "Quote preview";
            if (resId == R.string.Cancel) return ru ? "Отмена" : "Cancel";
            if (resId == R.string.OK) return ru ? "ОК" : "OK";
            if (resId == R.string.PengramQuoteCannotSend) return ru ? "В этот чат сейчас нельзя отправить фото." : "This chat is not available for sending photos.";
            if (resId == R.string.PengramQuoteShareError) return ru ? "Не удалось поделиться изображением." : "Could not share the image.";
            if (resId == R.string.PengramQuoteCopyError) return ru ? "Не удалось скопировать изображение." : "Could not copy the image.";
            if (resId == R.string.PengramQuoteStickerError) return ru ? "Не удалось создать стикер. Выберите меньше сообщений или снизьте размер изображения." : "Could not create a sticker. Select fewer messages or reduce the image size.";
            if (resId == R.string.PengramQuoteSaveError) return ru ? "Не удалось сохранить цитату в галерею." : "Could not save the quote to the gallery.";
            if (resId == R.string.PengramQuoteRestricted) return ru ? "Для защищённого содержимого создание цитат недоступно." : "Quotes are unavailable for protected content.";
            return ru ? "Цитата" : "Quote";
        }
    }

    private static TextView action(Activity activity, int title, boolean primary, Runnable callback) {
        TextView button = new TextView(activity);
        button.setText(safeString(activity, title));
        button.setGravity(Gravity.CENTER);
        button.setTextSize(15);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(primary ? Color.WHITE : 0xff4873c8);
        button.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        button.setMinHeight(AndroidUtilities.dp(48));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12),
                primary ? 0xff5685f8 : 0xffecf2ff, primary ? 0xff416ad4 : 0xffdce8ff));
        button.setOnClickListener(v -> callback.run());
        return button;
    }

    private static void addAction(Activity activity, LinearLayout root, int title,
                                  boolean primary, Runnable callback) {
        TextView button = action(activity, title, primary, callback);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = AndroidUtilities.dp(7);
        root.addView(button, lp);
    }

    private static void preview(ChatActivity chat, Result result,
                                ArrayList<MessageObject> selected, ArrayList<Entry> entries, ArrayList<File> paths) {
        final Activity activity = chat.getParentActivity();
        if (!active(activity)) return;
        final AlertDialog[] ref = new AlertDialog[1];
        ScrollView outer = new ScrollView(activity);
        outer.setFillViewport(false);
        outer.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        outer.addView(root, new ScrollView.LayoutParams(-1, -2));
        TextView hint = new TextView(activity);
        hint.setText(safeString(activity, R.string.PengramQuotePrivacyHint));
        hint.setTextSize(14);
        hint.setTextColor(0xff77869c);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(9));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        ScrollView pictureScroll = new ScrollView(activity);
        pictureScroll.setFillViewport(false);
        pictureScroll.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0xffd9e2f0));
        ImageView image = new ImageView(activity);
        image.setImageBitmap(result.preview);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pictureScroll.addView(image, new ScrollView.LayoutParams(-1, -2));
        pictureScroll.setOnClickListener(v -> zoom(activity, result.preview));
        image.setOnClickListener(v -> zoom(activity, result.preview));
        int previewHeight = Math.max(AndroidUtilities.dp(130),
                Math.min(AndroidUtilities.dp(260), activity.getResources().getDisplayMetrics().heightPixels / 4));
        root.addView(pictureScroll, new LinearLayout.LayoutParams(-1, previewHeight));

        HorizontalScrollView quick = new HorizontalScrollView(activity);
        quick.setHorizontalScrollBarEnabled(false);
        LinearLayout toggles = new LinearLayout(activity);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        int[] keys = {R.string.PengramQuoteNames, R.string.PengramQuoteAvatar, R.string.PengramQuoteTimes,
                R.string.PengramQuoteMedia, R.string.PengramQuoteAnonMentions,
                R.string.PengramQuoteDark, R.string.PengramQuoteJpeg, R.string.PengramQuoteWatermark};
        String[] config = {KEY_NAME, KEY_AVATAR, KEY_TIME, KEY_MEDIA, KEY_ANON_MENTIONS,
                KEY_DARK, KEY_JPEG, KEY_WATERMARK};
        boolean[] defaults = {true, true, true, true, false, false, false, false};
        for (int i = 0; i < keys.length; i++) {
            if (i == 1 && !PengramConfig.getBool(KEY_NAME, true)) continue;
            final int idx = i;
            boolean enabled = PengramConfig.getBool(config[i], defaults[i]);
            TextView chip = action(activity, keys[i], enabled, () -> {
                PengramConfig.setBool(config[idx], !PengramConfig.getBool(config[idx], defaults[idx]));
                if (ref[0] != null) ref[0].dismiss();
                show(chat, selected);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = AndroidUtilities.dp(6);
            toggles.addView(chip, lp);
        }
        TextView bgChip = action(activity, R.string.PengramQuoteBackground,
                PengramConfig.getIntCached(KEY_BACKGROUND, 0) != 0, () -> {
                    int next = (Math.max(0, Math.min(2, PengramConfig.getIntCached(KEY_BACKGROUND, 0))) + 1) % 3;
                    PengramConfig.setIntValue(KEY_BACKGROUND, next);
                    if (ref[0] != null) ref[0].dismiss();
                    show(chat, selected);
                });
        toggles.addView(bgChip, new LinearLayout.LayoutParams(-2, -2));
        quick.addView(toggles);
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(-1, -2);
        quickParams.topMargin = AndroidUtilities.dp(8);
        root.addView(quick, quickParams);

        boolean canSend = chat.getCurrentChat() == null || ChatObject.canWriteToChat(chat.getCurrentChat())
                && ChatObject.canSendPhoto(chat.getCurrentChat());
        if (canSend) {
            addAction(activity, root, R.string.PengramQuoteSend, true, () -> {
                send(chat, result);
                if (ref[0] != null) ref[0].dismiss();
            });
        }
        // The primary decision stays visible; less common formats are one tap away,
        // not six full-width buttons occupying the entire screen.
        LinearLayout secondary = new LinearLayout(activity);
        secondary.setOrientation(LinearLayout.HORIZONTAL);
        int[] secondaryLabels = {R.string.PengramQuoteCopy, R.string.PengramQuoteSave,
                R.string.PengramQuoteMore};
        Runnable[] secondaryActions = {
                () -> copy(chat, result),
                () -> save(chat, result),
                () -> {
                    ArrayList<CharSequence> choices = new ArrayList<>();
                    ArrayList<Runnable> operations = new ArrayList<>();
                    if (canSend && (chat.getCurrentChat() == null || ChatObject.canSendDocument(chat.getCurrentChat()))) {
                        choices.add(safeString(activity, R.string.PengramQuoteSendFile));
                        operations.add(() -> sendDocument(chat, result));
                    }
                    if (canSend && (chat.getCurrentChat() == null || ChatObject.canSendStickers(chat.getCurrentChat()))) {
                        choices.add(safeString(activity, R.string.PengramQuoteSendSticker));
                        operations.add(() -> sticker(chat, entries, paths));
                    }
                    choices.add(safeString(activity, R.string.PengramQuoteOtherChat));
                    operations.add(() -> sendOtherChat(chat, result));
                    choices.add(safeString(activity, R.string.PengramQuoteShare));
                    operations.add(() -> share(chat, result));
                    chat.showDialog(new AlertDialog.Builder(activity, chat.getResourceProvider())
                            .setItems(choices.toArray(new CharSequence[0]), (dialog, which) -> {
                                if (ref[0] != null) ref[0].dismiss();
                                operations.get(which).run();
                            }).create());
                }
        };
        for (int i = 0; i < secondaryLabels.length; i++) {
            TextView button = action(activity, secondaryLabels[i], false, secondaryActions[i]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.leftMargin = i == 0 ? 0 : AndroidUtilities.dp(5);
            secondary.addView(button, params);
        }
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(-1, -2);
        secondaryParams.topMargin = AndroidUtilities.dp(7);
        root.addView(secondary, secondaryParams);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, chat.getResourceProvider());
        builder.setTitle(safeString(activity, R.string.PengramQuotePreview));
        builder.setView(outer);
        builder.setNegativeButton(safeString(activity, R.string.Cancel), null);
        ref[0] = builder.create();
        chat.showDialog(ref[0]);
    }

    private static final class ZoomImageView extends ImageView {
        private final Bitmap image;
        private final Matrix transform = new Matrix();
        private final ScaleGestureDetector detector;
        private float scale = 1f;
        private float dx, dy, lastX, lastY;

        ZoomImageView(Activity activity, Bitmap bitmap) {
            super(activity);
            image = bitmap;
            setImageBitmap(bitmap);
            setScaleType(ScaleType.MATRIX);
            detector = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector gesture) {
                    scale = Math.max(1f, Math.min(5f, scale * gesture.getScaleFactor()));
                    redraw();
                    return true;
                }
            });
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            redraw();
        }

        private void redraw() {
            if (getWidth() <= 0 || getHeight() <= 0 || image.isRecycled()) return;
            float fit = Math.min(getWidth() / (float) image.getWidth(), getHeight() / (float) image.getHeight());
            float w = image.getWidth() * fit * scale, h = image.getHeight() * fit * scale;
            dx = Math.max((getWidth() - w) / 2, Math.min((w - getWidth()) / 2, dx));
            dy = Math.max((getHeight() - h) / 2, Math.min((h - getHeight()) / 2, dy));
            if (w <= getWidth()) dx = 0;
            if (h <= getHeight()) dy = 0;
            transform.setScale(fit * scale, fit * scale);
            transform.postTranslate((getWidth() - w) / 2 + dx, (getHeight() - h) / 2 + dy);
            setImageMatrix(transform);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            detector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                    && !detector.isInProgress()) {
                dx += event.getX() - lastX;
                dy += event.getY() - lastY;
                redraw();
                lastX = event.getX();
                lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                lastX = event.getX(0);
                lastY = event.getY(0);
            }
            return true;
        }
    }

    private static void zoom(Activity activity, Bitmap bitmap) {
        if (!active(activity) || bitmap == null || bitmap.isRecycled()) return;
        ZoomImageView image = new ZoomImageView(activity, bitmap);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(activity);
        int height = Math.max(AndroidUtilities.dp(180),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.68f));
        frame.addView(image, new android.widget.FrameLayout.LayoutParams(-1, height));
        new AlertDialog.Builder(activity).setTitle(safeString(activity, R.string.PengramQuotePreview))
                .setView(frame).setPositiveButton(safeString(activity, R.string.OK), null).show();
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
            if (replyToTop == null) replyToTop = topMessage(chat.getCurrentAccount(), chat.getDialogId(), chat.getTopicId());
            SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(chat.getCurrentAccount()),
                    result.file.getAbsolutePath(), null, chat.getDialogId(), null, replyToTop,
                    null, null, null, null, null, 0, null, true, 0, 0, SendMessageChatArguments.EMPTY);
        } catch (Throwable e) {
            FileLog.e(e);
            error(chat, R.string.PengramQuoteCannotSend);
        }
    }

    private static void sendDocument(ChatActivity chat, Result result) {
        if (!result.file.isFile()) {
            error(chat, R.string.PengramQuoteRenderError);
            return;
        }
        if (chat.getCurrentChat() != null && !ChatObject.canSendDocument(chat.getCurrentChat())) {
            error(chat, R.string.PengramQuoteCannotSend);
            return;
        }
        try {
            MessageObject top = chat.getThreadMessage();
            if (top == null) top = topMessage(chat.getCurrentAccount(), chat.getDialogId(), chat.getTopicId());
            SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(chat.getCurrentAccount()),
                    result.file.getAbsolutePath(), result.file.getAbsolutePath(), null, null,
                    result.jpeg ? "image/jpeg" : "image/png", chat.getDialogId(), null, top,
                    null, null, null, true, 0, null, SendMessageChatArguments.EMPTY, false);
        } catch (Throwable e) {
            FileLog.e(e);
            error(chat, R.string.PengramQuoteCannotSend);
        }
    }

    private static void sendOtherChat(ChatActivity chat, Result result) {
        if (!result.file.isFile() || chat.getParentActivity() == null) {
            error(chat, R.string.PengramQuoteRenderError);
            return;
        }
        try {
            Bundle args = new Bundle();
            args.putBoolean("onlySelect", true);
            args.putBoolean("closeFragment", false);
            args.putBoolean("canSelectTopics", true);
            args.putBoolean("checkCanWrite", true);
            args.putBoolean("allowGlobalSearch", true);
            args.putBoolean("allowUsers", true);
            args.putBoolean("allowBots", true);
            args.putBoolean("allowGroups", true);
            args.putBoolean("allowMegagroups", true);
            args.putBoolean("allowChannels", true);
            DialogsActivity picker = new DialogsActivity(args);
            picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, repeat, topicsFragment) -> {
                if (dids == null || dids.isEmpty() || dids.get(0) == null || dids.get(0).dialogId == 0) {
                    return false;
                }
                MessagesStorage.TopicKey destination = dids.get(0);
                TLRPC.Chat peer = destination.dialogId < 0
                        ? MessagesController.getInstance(chat.getCurrentAccount()).getChat(-destination.dialogId) : null;
                if (peer != null && (!ChatObject.canWriteToChat(peer) || !ChatObject.canSendPhoto(peer))) {
                    error(chat, R.string.PengramQuoteCannotSend);
                    return false;
                }
                MessageObject top = topMessage(chat.getCurrentAccount(), destination.dialogId, destination.topicId);
                try {
                    SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(chat.getCurrentAccount()),
                            result.file.getAbsolutePath(), null, destination.dialogId, null, top,
                            null, null, null, null, null, 0, null, notify, scheduleDate, 0,
                            SendMessageChatArguments.EMPTY);
                    fragment.finishFragment();
                    return true;
                } catch (Throwable e) {
                    FileLog.e(e);
                    error(chat, R.string.PengramQuoteCannotSend);
                    return false;
                }
            });
            chat.presentFragment(picker);
        } catch (Throwable e) {
            FileLog.e(e);
            error(chat, R.string.PengramQuoteCannotSend);
        }
    }

    private static void share(ChatActivity chat, Result result) {
        Activity activity = chat.getParentActivity();
        if (!active(activity) || !result.file.isFile()) return;
        try {
            Uri uri = shareUri(activity, result.file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(result.jpeg ? "image/jpeg" : "image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(ClipData.newUri(activity.getContentResolver(), "Quote", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, safeString(activity, R.string.PengramQuoteShare)));
        } catch (Throwable e) {
            FileLog.e(e);
            error(chat, R.string.PengramQuoteShareError);
        }
    }

    private static void copy(ChatActivity chat, Result result) {
        Activity activity = chat.getParentActivity();
        if (!active(activity) || !result.file.isFile()) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
            Uri uri = shareUri(activity, result.file);
            clipboard.setPrimaryClip(ClipData.newUri(activity.getContentResolver(), "Quote", uri));
        } catch (Throwable e) {
            FileLog.e(e);
            error(chat, R.string.PengramQuoteCopyError);
        }
    }

    private static void sticker(ChatActivity chat, ArrayList<Entry> entries, ArrayList<File> paths) {
        Activity activity = chat.getParentActivity();
        if (!active(activity)) return;
        final int account = chat.getCurrentAccount();
        final long dialogId = chat.getDialogId();
        final long topicId = chat.getTopicId();
        final String chatName = chat.getCurrentChat() == null ? "" : chat.getCurrentChat().title;
        final int themeBackground = Theme.getColor(Theme.key_chat_wallpaper, chat.getResourceProvider());
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(3, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final AlertDialog spinner = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, chat.getResourceProvider());
        chat.showDialog(spinner);
        Utilities.globalQueue.postRunnable(() -> {
            File webp = null;
            File sourceFile = null;
            try {
                Result source = render(activity, entries, paths, dark, style, false, true,
                        themeBackground, chatName);
                sourceFile = source.file;
                if (source.preview != null) source.preview.recycle();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(source.file.getAbsolutePath(), bounds);
                BitmapFactory.Options sampled = new BitmapFactory.Options();
                sampled.inSampleSize = 1;
                while (bounds.outWidth / sampled.inSampleSize > 1024 || bounds.outHeight / sampled.inSampleSize > 1024)
                    sampled.inSampleSize *= 2;
                Bitmap bitmap = BitmapFactory.decodeFile(source.file.getAbsolutePath(), sampled);
                if (bitmap == null) throw new IllegalStateException("Sticker decode failed");
                int w = Math.max(1, Math.round(512f * bitmap.getWidth() / Math.max(bitmap.getWidth(), bitmap.getHeight())));
                int h = Math.max(1, Math.round(512f * bitmap.getHeight() / Math.max(bitmap.getWidth(), bitmap.getHeight())));
                Bitmap scaled = null;
                try {
                    scaled = Bitmap.createScaledBitmap(bitmap, w, h, true);
                    webp = new File(source.file.getParentFile(), "pengram-quote-" + System.nanoTime() + ".webp");
                    for (int quality : new int[]{100, 85, 70, 55, 40}) {
                        try (FileOutputStream out = new FileOutputStream(webp)) {
                            Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= 30 && quality == 100
                                    ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP;
                            if (!scaled.compress(format, quality, out)) throw new IllegalStateException("WebP compression failed");
                        }
                        if (webp.length() > 0 && webp.length() <= 512 * 1024) break;
                    }
                } finally {
                    if (scaled != null && scaled != bitmap) scaled.recycle();
                    bitmap.recycle();
                }
                if (webp.length() == 0 || webp.length() > 512 * 1024) throw new IllegalStateException("Sticker over 512 KB");
                final File file = webp;
                final int finalW = w, finalH = h;
                AndroidUtilities.runOnUIThread(() -> {
                    spinner.dismiss();
                    if (!active(chat.getParentActivity()) || !file.isFile()) {
                        file.delete();
                        return;
                    }
                    if (chat.getCurrentChat() != null && !ChatObject.canSendStickers(chat.getCurrentChat())) {
                        file.delete();
                        error(chat, R.string.PengramQuoteCannotSend);
                        return;
                    }
                    try {
                        TLRPC.TL_document document = new TLRPC.TL_document();
                        document.file_reference = new byte[0];
                        document.date = (int) (System.currentTimeMillis() / 1000);
                        document.mime_type = "image/webp";
                        document.size = file.length();
                        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
                        filename.file_name = file.getName();
                        document.attributes.add(filename);
                        TLRPC.TL_documentAttributeSticker stickerAttr = new TLRPC.TL_documentAttributeSticker();
                        stickerAttr.alt = "";
                        stickerAttr.stickerset = new TLRPC.TL_inputStickerSetEmpty();
                        document.attributes.add(stickerAttr);
                        TLRPC.TL_documentAttributeImageSize sizeAttr = new TLRPC.TL_documentAttributeImageSize();
                        sizeAttr.w = finalW;
                        sizeAttr.h = finalH;
                        document.attributes.add(sizeAttr);
                        HashMap<String, String> params = new HashMap<>();
                        params.put("originalPath", file.getAbsolutePath());
                        SendMessagesHelper.SendMessageParams message = SendMessagesHelper.SendMessageParams.of(
                                document, null, file.getAbsolutePath(), dialogId, null,
                                topMessage(account, dialogId, topicId), null, null, null, params,
                                true, 0, 0, 0, null, null, false);
                        SendMessagesHelper.getInstance(account).sendMessage(message);
                    } catch (Throwable e) {
                        FileLog.e(e);
                        file.delete();
                        error(chat, R.string.PengramQuoteStickerError);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
                if (webp != null) webp.delete();
                AndroidUtilities.runOnUIThread(() -> {
                    spinner.dismiss();
                    error(chat, R.string.PengramQuoteStickerError);
                });
            } finally {
                // Only the WebP is sent; the full-size temporary PNG is never needed again.
                if (sourceFile != null) sourceFile.delete();
                for (Entry entry : entries) {
                    if (entry.media != null && !entry.media.isRecycled()) entry.media.recycle();
                    entry.media = null;
                    for (Bitmap tile : entry.albumImages) if (tile != null && !tile.isRecycled()) tile.recycle();
                    entry.albumImages.clear();
                }
            }
        });
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
                if (uri != null) {
                    try { activity.getContentResolver().delete(uri, null, null); }
                    catch (Throwable cleanupError) { FileLog.e(cleanupError); }
                }
                AndroidUtilities.runOnUIThread(() -> error(chat, R.string.PengramQuoteSaveError));
            }
        });
    }

    private static void error(ChatActivity chat, int stringId) {
        Activity activity = chat.getParentActivity();
        if (active(activity)) {
            try {
                chat.showDialog(new AlertDialog.Builder(activity, chat.getResourceProvider())
                        .setMessage(safeString(activity, stringId))
                        .setPositiveButton(safeString(activity, R.string.OK), null).create());
            } catch (RuntimeException e) {
                // A fragment can detach between validation and showing its dialog.
                FileLog.e(e);
            }
        }
    }
}
