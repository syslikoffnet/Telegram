package org.telegram.ui.Components;

import android.app.Activity;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.HorizontalScrollView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
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
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
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
    public static final String KEY_THEME_STYLE = "quoteFollowChatTheme";
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
        String name, text, time, missingMediaLabel, unavailableLabel;
        boolean motion;
        long groupId, senderId;
        StaticLayout layout;
        Bitmap media;
        Bitmap avatar;
        File avatarFile;
        File imageFile, videoFile, lottieFile;
        byte[] cachedImage, strippedImage;
        boolean sticker, visual;
        final ArrayList<Entry> albumEntries = new ArrayList<>();
        final ArrayList<Bitmap> albumImages = new ArrayList<>();
        int imageHeight, height, width;
    }

    private static final class Result {
        File file;
        Bitmap preview;
        boolean jpeg;
    }

    /** Immutable UI-thread snapshot: never draw the live wallpaper or read a changing theme on a worker. */
    private static final class QuotePalette {
        Bitmap wallpaper;
        int background, bubble, ink, muted;
    }

    private static QuotePalette capturePalette(ChatActivity chat, boolean includeWallpaper) {
        QuotePalette palette = new QuotePalette();
        Theme.ResourcesProvider provider = chat.getResourceProvider();
        int wallpaperColor = Theme.getColor(Theme.key_chat_wallpaper, provider);
        palette.background = wallpaperColor != 0 ? wallpaperColor | 0xff000000
                : Theme.getColor(Theme.key_windowBackgroundGray, provider) | 0xff000000;
        palette.bubble = Theme.getColor(Theme.key_chat_inBubble, provider);
        palette.ink = Theme.getColor(Theme.key_chat_messageTextIn, provider);
        palette.muted = Theme.getColor(Theme.key_chat_inTimeText, provider);
        if (includeWallpaper) {
            Drawable drawable = chat.getPengramQuoteWallpaper();
            if (drawable != null) {
                try {
                    int height = Math.max(WIDTH, Math.min(1600,
                            Math.round(chat.getParentActivity().getResources().getDisplayMetrics().heightPixels
                                    * WIDTH / (float) chat.getParentActivity().getResources().getDisplayMetrics().widthPixels)));
                    palette.wallpaper = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.RGB_565);
                    Canvas canvas = new Canvas(palette.wallpaper);
                    canvas.drawColor(palette.background);
                    if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
                        Bitmap photo = ((BitmapDrawable) drawable).getBitmap();
                        float crop = Math.min(photo.getWidth() / (float) WIDTH, photo.getHeight() / (float) height);
                        int sourceW = Math.max(1, Math.min(photo.getWidth(), Math.round(WIDTH * crop)));
                        int sourceH = Math.max(1, Math.min(photo.getHeight(), Math.round(height * crop)));
                        canvas.drawBitmap(photo, new Rect((photo.getWidth() - sourceW) / 2,
                                (photo.getHeight() - sourceH) / 2, (photo.getWidth() + sourceW) / 2,
                                (photo.getHeight() + sourceH) / 2), new Rect(0, 0, WIDTH, height),
                                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                    } else {
                        Rect old = new Rect(drawable.getBounds());
                        try {
                            drawable.setBounds(0, 0, WIDTH, height);
                            drawable.draw(canvas);
                        } finally {
                            drawable.setBounds(old);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    if (palette.wallpaper != null) palette.wallpaper.recycle();
                    palette.wallpaper = null;
                }
            }
        }
        return palette;
    }

    private static void drawWallpaper(Canvas canvas, Bitmap wallpaper, int height, Paint paint) {
        int tile = wallpaper.getHeight();
        for (int y = 0; y < height; y += tile) {
            int chunk = Math.min(tile, height - y);
            canvas.drawBitmap(wallpaper, new Rect(0, 0, WIDTH, chunk),
                    new Rect(0, y, WIDTH, y + chunk), paint);
        }
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
                    || message.needDrawBluredPreview()
                    || message.hasMediaSpoilers() && !message.isMediaSpoilersRevealed
                    || message.type == MessageObject.TYPE_PAID_MEDIA
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
            entry.sticker = message.isAnyKindOfSticker() || message.isSticker() || message.isAnimatedSticker();
            entry.motion = message.isVideo() || message.isRoundVideo() || message.isGif();
            entry.visual = message.isPhoto() || entry.motion || entry.sticker || isImageDocument(message);
            entry.unavailableLabel = safeString(activity, R.string.PengramQuoteMediaUnavailable);
            entry.missingMediaLabel = safeString(activity, entry.sticker ? R.string.PengramQuoteSticker
                    : entry.motion ? R.string.PengramQuoteVideo
                    : isImageDocument(message) ? R.string.PengramQuoteDocument : R.string.PengramQuotePhoto);
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
                    entry.avatarFile = file; // FileLoader writes to this path; recheck it when rendering.
                    if (file == null || !file.isFile() || file.length() == 0) {
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
            // Telegram's generated messageText ("Sticker", "Video", etc.) is not a caption.
            // Do not print it over an already visible image or poster.
            entry.text = !TextUtils.isEmpty(message.caption) ? message.caption.toString()
                    : entry.visual ? ""
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
            if (TextUtils.isEmpty(entry.text) && !entry.visual) {
                if (message.isVoice()) entry.text = safeString(activity, R.string.PengramQuoteVoice);
                else if (message.isMusic()) entry.text = safeString(activity, R.string.PengramQuoteMusic);
                else if (message.isDocument()) entry.text = safeString(activity, R.string.PengramQuoteDocument);
                else if (message.messageText == null) {
                    error(chat, R.string.PengramQuoteUnsupported);
                    return;
                }
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
            if (entry.visual) {
                // Record local sources even with media toggled off, so the preview chip can
                // enable them later without rebuilding the selection.
                entry.imageFile = findLocalImage(chat.getCurrentAccount(), message);
                entry.cachedImage = cachedPhotoBytes(message, false);
                entry.strippedImage = cachedPhotoBytes(message, true);
                if (entry.motion || entry.sticker) {
                    File original = localMediaFile(chat.getCurrentAccount(), message);
                    if (original != null) {
                        if (entry.sticker && message.isAnimatedSticker()
                                && original.length() <= 2L * 1024 * 1024
                                && message.getDocument() != null
                                && "application/x-tgsticker".equals(message.getDocument().mime_type)) {
                            entry.lottieFile = original;
                        } else if (entry.motion || message.isVideoSticker()) {
                            entry.videoFile = original;
                        }
                    }
                }
            }
            if (entry.visual && entry.groupId != 0 && !entries.isEmpty()) {
                Entry previous = entries.get(entries.size() - 1);
                if (previous.visual && previous.groupId == entry.groupId
                        && previous.senderId == entry.senderId) {
                    // Keep an album slot for every item, even if one poster is missing.
                    previous.albumEntries.add(entry);
                    if (!TextUtils.isEmpty(entry.text)) {
                        previous.text = TextUtils.isEmpty(previous.text) ? entry.text : previous.text + "\n" + entry.text;
                    }
                    previous.time = entry.time;
                    continue;
                }
            }
            imagePaths.add(entry.imageFile);
            entries.add(entry);
        }
        final boolean jpeg = PengramConfig.getBool(KEY_JPEG, false)
                && PengramConfig.getIntCached(KEY_BACKGROUND, 0) != 1;
        final String chatName = chat.getCurrentChat() != null ? chat.getCurrentChat().title
                : chat.getCurrentUser() != null ? UserObject.getUserName(chat.getCurrentUser()) : "";
        final QuotePalette palette = capturePalette(chat, PengramConfig.getIntCached(KEY_BACKGROUND, 0) == 0);
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(3, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final AlertDialog spinner = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, chat.getResourceProvider());
        chat.showDialog(spinner);
        boolean awaitingAvatar = false;
        for (Entry entry : entries) {
            if (entry.avatarFile != null && !entry.avatarFile.isFile() && entry.avatar == null) {
                awaitingAvatar = true;
                break;
            }
        }
        // Give a just-requested avatar a brief chance to reach the disk. Never block
        // the worker queue indefinitely or fail the quote if the user is offline.
        Utilities.globalQueue.postRunnable(() -> {
            Result result = null;
            try {
                result = render(activity, entries, imagePaths, dark, style, jpeg, false,
                        palette, chatName);
            } catch (Throwable ex) {
                FileLog.e(ex);
            } finally {
                if (palette.wallpaper != null) palette.wallpaper.recycle();
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
        }, awaitingAvatar ? 1400 : 0);
    }

    private static boolean isImageDocument(MessageObject message) {
        TLRPC.Document document = message.getDocument();
        return document != null && document.mime_type != null
                && document.mime_type.startsWith("image/") && !message.isGif();
    }

    private static File localMediaFile(int account, MessageObject message) {
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            File attached = new File(message.messageOwner.attachPath);
            if (attached.isFile() && attached.length() > 0) return attached;
        }
        File original = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
        return original != null && original.isFile() && original.length() > 0 ? original : null;
    }

    /** Pick a decodable local image rather than assuming that getPathToMessage is in the cache. */
    private static File findLocalImage(int account, MessageObject message) {
        FileLoader loader = FileLoader.getInstance(account);
        File original = localMediaFile(account, message);
        if (isImageFile(original)) return original;
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media != null && media.photo != null && media.photo.sizes != null) {
            File photo = findInSizes(loader, media.photo.sizes);
            if (photo != null) return photo;
        }
        TLRPC.Document document = message.getDocument();
        if (document != null && document.thumbs != null) {
            File thumb = findInSizes(loader, document.thumbs);
            if (thumb != null) return thumb;
        }
        if (message.photoThumbs != null) {
            File thumb = findInSizes(loader, message.photoThumbs);
            if (thumb != null) return thumb;
        }
        return null;
    }

    private static File findInSizes(FileLoader loader, ArrayList<TLRPC.PhotoSize> sizes) {
        // Try higher-quality sizes first, but do not assume they have finished downloading.
        ArrayList<TLRPC.PhotoSize> ordered = new ArrayList<>(sizes);
        ordered.sort((a, b) -> Long.compare(b == null ? 0L : (long) b.w * b.h,
                a == null ? 0L : (long) a.w * a.h));
        for (TLRPC.PhotoSize size : ordered) {
            if (size == null || size instanceof TLRPC.TL_photoStrippedSize
                    || size instanceof TLRPC.TL_photoPathSize || size instanceof TLRPC.TL_photoSizeEmpty) continue;
            File media = loader.getPathToAttach(size, false);
            if (isImageFile(media)) return media;
            File cached = loader.getPathToAttach(size, true);
            if (isImageFile(cached)) return cached;
        }
        return null;
    }

    private static boolean isImageFile(File file) {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > 40L * 1024 * 1024) return false;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            return bounds.outWidth > 0 && bounds.outHeight > 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Telegram can embed a JPEG or a tiny stripped thumbnail in the message. */
    private static byte[] cachedPhotoBytes(MessageObject message, boolean stripped) {
        byte[] best = cachedBytesInSizes(message.photoThumbs, stripped);
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media != null && media.photo != null) {
            byte[] photo = cachedBytesInSizes(media.photo.sizes, stripped);
            if (photo != null && (best == null || photo.length > best.length)) best = photo;
        }
        TLRPC.Document document = message.getDocument();
        if (document != null) {
            byte[] thumb = cachedBytesInSizes(document.thumbs, stripped);
            if (thumb != null && (best == null || thumb.length > best.length)) best = thumb;
        }
        return best;
    }

    private static byte[] cachedBytesInSizes(ArrayList<TLRPC.PhotoSize> sizes, boolean stripped) {
        if (sizes == null) return null;
        byte[] best = null;
        for (TLRPC.PhotoSize size : sizes) {
            byte[] bytes = stripped && size instanceof TLRPC.TL_photoStrippedSize
                    ? ((TLRPC.TL_photoStrippedSize) size).bytes
                    : !stripped && size instanceof TLRPC.TL_photoCachedSize
                    ? ((TLRPC.TL_photoCachedSize) size).bytes : null;
            if (bytes != null && bytes.length > (stripped ? 3 : 0)
                    && bytes.length <= (stripped ? 200_000 : 2_000_000)
                    && (best == null || bytes.length > best.length)) best = bytes;
        }
        return best;
    }

    /** Render the first frame of a downloaded TGS, never inflate unbounded input. */
    private static Bitmap decodeLottie(File file) {
        if (file == null || !file.isFile() || file.length() > 2L * 1024 * 1024) return null;
        RLottieNative nativeFrame = null;
        Bitmap frame = null;
        try (GZIPInputStream gzip = new GZIPInputStream(new FileInputStream(file));
             ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int size, total = 0;
            while ((size = gzip.read(chunk)) != -1) {
                total += size;
                if (total > 2_000_000) return null;
                data.write(chunk, 0, size);
            }
            nativeFrame = RLottieNative.createFromFile(file.getAbsolutePath(),
                    new String(data.toByteArray(), StandardCharsets.UTF_8), null, null, 0, null);
            if (nativeFrame == null) return null;
            frame = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            if (nativeFrame.getFrame(0, frame, true) < 0) {
                frame.recycle();
                return null;
            }
            return frame;
        } catch (Exception e) {
            if (frame != null && !frame.isRecycled()) frame.recycle();
            FileLog.e(e);
            return null;
        } finally {
            if (nativeFrame != null) nativeFrame.recycle();
        }
    }

    private static Bitmap decodeMedia(Entry entry) {
        Bitmap image = decodeLottie(entry.lottieFile);
        if (image == null) image = decode(entry.imageFile, entry.sticker);
        if (image == null && entry.cachedImage != null) image = decodeBytes(entry.cachedImage);
        if (image == null && entry.videoFile != null) {
            try { image = decodeVideo(entry.videoFile); }
            catch (Exception e) { FileLog.e(e); }
        }
        if (image == null && entry.strippedImage != null) {
            try { image = ImageLoader.getStrippedPhotoBitmap(entry.strippedImage, null); }
            catch (Exception e) { FileLog.e(e); }
        }
        return image;
    }

    /** A bounded video frame when Telegram has no cached poster image. */
    private static Bitmap decodeVideo(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            if (Build.VERSION.SDK_INT >= 27) {
                String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (width != null && height != null) {
                    int w = Integer.parseInt(width), h = Integer.parseInt(height);
                    if (w > 0 && h > 0) {
                        float scale = Math.min(1f, 720f / Math.max(w, h));
                        Bitmap small = retriever.getScaledFrameAtTime(0,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                        if (small != null) return small;
                    }
                }
            }
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int max = Math.max(frame.getWidth(), frame.getHeight());
            if (max <= 720) return frame;
            Bitmap scaled = Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * 720f / max)),
                    Math.max(1, Math.round(frame.getHeight() * 720f / max)), true);
            if (scaled != frame) frame.recycle();
            return scaled;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static Bitmap decodeBytes(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1100 || bounds.outHeight / options.inSampleSize > 1100) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static Bitmap decode(File file) {
        return decode(file, false);
    }

    private static Bitmap decode(File file, boolean alpha) {
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1100 || bounds.outHeight / options.inSampleSize > 1100) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = alpha || "image/png".equals(bounds.outMimeType)
                || "image/webp".equals(bounds.outMimeType) ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap image = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (image == null || !("image/jpeg".equals(bounds.outMimeType)
                || "image/heif".equals(bounds.outMimeType)
                || "image/heic".equals(bounds.outMimeType))) return image;
        try {
            int orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
                default: return image;
            }
            Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
            if (rotated != image) image.recycle();
            return rotated;
        } catch (Exception e) {
            FileLog.e(e);
            return image;
        }
    }

    private static Result render(Activity activity, ArrayList<Entry> entries, ArrayList<File> paths,
                                 boolean dark, int style, boolean jpeg, boolean sticker,
                                 QuotePalette palette, String chatName) throws Exception {
        final boolean followTheme = PengramConfig.getBool(KEY_THEME_STYLE, true);
        final int accent = followTheme ? palette.ink
                : new int[]{0xff5685f8, 0xff23ad85, 0xffd26496, 0xffeda546}[style];
        int bgType = sticker && PengramConfig.getBool(KEY_STICKER_TRANSPARENT, true) ? 1
                : Math.max(0, Math.min(2, PengramConfig.getIntCached(KEY_BACKGROUND, 0)));
        final int background = bgType == 1 && !jpeg ? Color.TRANSPARENT
                : bgType == 2 ? PengramConfig.getIntCached(KEY_BACKGROUND_COLOR, 0xffeef3ff) | 0xff000000
                : bgType == 0 ? palette.background
                : dark ? 0xff141a28 : 0xffeef3ff;
        final int pad = Math.max(0, Math.min(72, PengramConfig.getIntCached(KEY_PADDING, 24)));
        final int radius = Math.max(0, Math.min(64, PengramConfig.getIntCached(KEY_RADIUS, 28)));
        final int contentLeft = pad + 36;
        final int contentWidth = WIDTH - contentLeft * 2;
        final float requestedScale = sticker ? 1f : new float[]{1f, 1.5f, 2f}[
                Math.max(0, Math.min(2, PengramConfig.getIntCached(KEY_SCALE, 0)))];
        final int surface = followTheme ? palette.bubble : dark ? 0xff222b3b : Color.WHITE;
        final int ink = followTheme ? palette.ink : dark ? Color.WHITE : 0xff1e293b;
        final int muted = followTheme ? palette.muted : dark ? 0xffa6b4ca : 0xff77869c;
        final boolean darkSurface = (Color.red(surface) * 299 + Color.green(surface) * 587
                + Color.blue(surface) * 114) < 128000;
        float fontScale = Math.max(1f, Math.min(1.55f, activity.getResources().getConfiguration().fontScale));
        final int nameHeight = (int) Math.ceil(34 * fontScale);
        final int timeHeight = (int) Math.ceil(28 * fontScale);
        final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(ink);
        body.setTextSize(27 * fontScale);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int total = pad + 8;
        final boolean includeMedia = PengramConfig.getBool(KEY_MEDIA, true);
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            e.imageHeight = 0;
            e.media = null;
            e.albumImages.clear();
            if (includeMedia && e.visual) {
                e.media = decodeMedia(e);
                if (!e.albumEntries.isEmpty()) {
                    for (Entry tile : e.albumEntries) e.albumImages.add(decodeMedia(tile));
                    int cell = (contentWidth - 8) / 2;
                    int count = 1 + e.albumEntries.size();
                    e.imageHeight = ((count + 1) / 2) * (cell + 8) - 8;
                } else if (e.media != null) {
                    e.imageHeight = e.sticker ? Math.min(400, contentWidth)
                            : Math.min(420, Math.max(120,
                            Math.round(contentWidth * e.media.getHeight() / (float) Math.max(1, e.media.getWidth()))));
                } else {
                    e.imageHeight = 148; // No local poster: show a clearly labeled missing-media tile.
                }
            }
            String displayText = e.text;
            if (TextUtils.isEmpty(displayText) && e.visual && !includeMedia) {
                displayText = e.missingMediaLabel;
            }
            e.layout = TextUtils.isEmpty(displayText) ? null : new StaticLayout(displayText, body, contentWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.18f, 0, false);
            final int textHeight = e.layout == null ? 0 : e.layout.getHeight();
            e.height = 26 + (e.name == null ? 0 : nameHeight) + (e.imageHeight == 0 ? 0 : e.imageHeight + 14)
                    + textHeight + (e.time == null ? 0 : timeHeight) + 23;
            float lineWidth = 0;
            if (e.layout != null) for (int line = 0; line < e.layout.getLineCount(); line++) {
                lineWidth = Math.max(lineWidth, e.layout.getLineWidth(line));
            }
            float nameWidth = e.name == null ? 0 : body.measureText(e.name) + (PengramConfig.getBool(KEY_AVATAR, true) ? 48 : 0);
            float timeWidth = e.time == null ? 0 : body.measureText(e.time) * .76f;
            e.width = e.imageHeight > 0 ? WIDTH - pad * 2 : Math.min(WIDTH - pad * 2,
                    Math.max(230, Math.round(Math.max(lineWidth, Math.max(nameWidth, timeWidth)) + 76)));
            total += e.height + 13;
        }
        total += pad + 8;
        // Long albums should still render: reduce only the requested export scale,
        // never the layout, and stay inside the bitmap memory budget.
        if (total < 1 || (long) WIDTH * total > MAX_PIXELS)
            throw new IllegalStateException("Quote too large");
        final float scale = Math.min(requestedScale,
                (float) Math.sqrt(MAX_PIXELS / ((double) WIDTH * total)));
        final int outWidth = Math.max(1, (int) (WIDTH * scale));
        final int outHeight = Math.max(1, (int) (total * scale));
        Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(background);
            canvas.scale(scale, scale);
            if (bgType == 0 && palette.wallpaper != null && !palette.wallpaper.isRecycled()) {
                drawWallpaper(canvas, palette.wallpaper, total, paint);
            }
            int y = pad + 8;
            for (Entry e : entries) {
                paint.setColor(surface);
                canvas.drawRoundRect(new RectF(pad, y, pad + e.width, y + e.height), radius, radius, paint);
                int inner = y + 26;
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
                            paint.setColor(darkSurface ? Color.BLACK : Color.WHITE);
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
                if (e.imageHeight > 0) {
                    if (!e.albumEntries.isEmpty()) {
                        int cell = (contentWidth - 8) / 2;
                        for (int j = 0; j <= e.albumEntries.size(); j++) {
                            Entry tile = j == 0 ? e : e.albumEntries.get(j - 1);
                            Bitmap image = j == 0 ? e.media : e.albumImages.get(j - 1);
                            int x = contentLeft + (j % 2) * (cell + 8);
                            int top = inner + (j / 2) * (cell + 8);
                            RectF bounds = new RectF(x, top, x + cell, top + cell);
                            if (image != null) drawImage(canvas, paint, image, bounds, !tile.sticker, tile.sticker, surface);
                            else drawMissingMedia(canvas, paint, bounds, tile.missingMediaLabel + " · " + tile.unavailableLabel, muted);
                            if (image != null && tile.motion) drawPlayIcon(canvas, paint, x + 32, top + 32);
                        }
                    } else {
                        RectF bounds = new RectF(contentLeft, inner, WIDTH - contentLeft, inner + e.imageHeight);
                        if (e.media != null) {
                            drawImage(canvas, paint, e.media, bounds, false, e.sticker, surface);
                            if (e.motion) drawPlayIcon(canvas, paint, contentLeft + 32, inner + 32);
                        } else {
                            drawMissingMedia(canvas, paint, bounds, e.missingMediaLabel + " · " + e.unavailableLabel, muted);
                        }
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
                    canvas.drawText(e.time, Math.max(contentLeft,
                            pad + e.width - 20 - paint.measureText(e.time)), inner + 24 * fontScale, paint);
                }
                y += e.height + 13;
            }
            if (PengramConfig.getBool(KEY_WATERMARK, false)) {
                drawWatermark(canvas, total, PengramConfig.getBool(KEY_NAME, true) ? chatName : "",
                        entries.size(), darkSurface);
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

    private static void drawPlayIcon(Canvas canvas, Paint paint, float cx, float cy) {
        paint.setColor(0xB3000000);
        canvas.drawCircle(cx, cy, 23, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(24);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("▶", cx - 10, cy + 8, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private static void drawMissingMedia(Canvas canvas, Paint paint, RectF dest, String label, int muted) {
        paint.setColor((muted & 0x00ffffff) | 0x22000000);
        canvas.drawRoundRect(dest, 15, 15, paint);
        paint.setColor(muted);
        paint.setTextSize(22);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(TextUtils.ellipsize(label, new TextPaint(paint),
                Math.max(12, dest.width() - 20), TextUtils.TruncateAt.END).toString(),
                dest.centerX(), dest.centerY() + 8, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private static void drawImage(Canvas canvas, Paint paint, Bitmap media, RectF dest,
                                  boolean crop, boolean sticker, int surface) {
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(dest, 15, 15, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        // A sticker keeps its alpha: do not paint a blue JPEG-like thumbnail behind it.
        if (!sticker) {
            paint.setColor(surface);
            canvas.drawRect(dest, paint);
        }
        int mw = media.getWidth(), mh = media.getHeight();
        float scale = crop ? Math.max(dest.width() / mw, dest.height() / mh)
                : Math.min(dest.width() / mw, dest.height() / mh);
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
            if (resId == R.string.PengramQuoteMediaUnavailable) return ru ? "Превью недоступно" : "Preview unavailable";
            if (resId == R.string.PengramQuoteVideo) return ru ? "Видео" : "Video";
            if (resId == R.string.PengramQuoteVoice) return ru ? "Голосовое сообщение" : "Voice message";
            if (resId == R.string.PengramQuoteSticker) return ru ? "Стикер" : "Sticker";
            if (resId == R.string.PengramQuoteDocument) return ru ? "Документ" : "Document";
            if (resId == R.string.PengramQuoteMusic) return ru ? "Музыка" : "Music";
            if (resId == R.string.PengramQuoteTooLong) return ru ? "Сообщение слишком длинное для цитаты-изображения." : "This message is too long for an image quote.";
            if (resId == R.string.PengramQuoteDownload) return ru ? "Скачайте фото или отключите показ медиа в цитате." : "Download the photo or turn off media in quote settings.";
            if (resId == R.string.PengramQuoteRenderError) return ru ? "Не удалось создать цитату. Попробуйте выбрать меньше сообщений." : "Could not create the quote. Try selecting fewer messages.";
            if (resId == R.string.PengramQuotePrivacyHint) return ru ? "Проверьте имена и текст перед отправкой." : "Check names and message text before sharing.";
            if (resId == R.string.PengramQuoteNames) return ru ? "Показывать имена отправителей" : "Show sender names";
            if (resId == R.string.PengramQuoteAvatar) return ru ? "Аватарки отправителей" : "Sender avatars";
            if (resId == R.string.PengramQuoteTimes) return ru ? "Показывать время сообщений" : "Show message times";
            if (resId == R.string.PengramQuoteMedia) return ru ? "Показывать медиа" : "Include media";
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

    private static void styleAction(TextView button, Theme.ResourcesProvider provider, boolean primary) {
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, provider);
        int neutral = Theme.getColor(Theme.key_dialogBackgroundGray, provider);
        int background = primary ? accent : neutral;
        int pressed = Theme.blendOver(background, 0x18000000);
        int lightness = Color.red(background) * 299 + Color.green(background) * 587 + Color.blue(background) * 114;
        button.setTextColor(primary ? (lightness < 154000 ? Color.WHITE : Color.BLACK)
                : Theme.getColor(Theme.key_dialogTextBlack, provider));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(13),
                background, pressed));
    }

    private static void styleChip(TextView chip, Theme.ResourcesProvider provider, boolean enabled) {
        int base = Theme.getColor(Theme.key_dialogBackgroundGray, provider);
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, provider);
        int background = enabled ? Theme.blendOver(base, (accent & 0x00ffffff) | 0x18000000) : base;
        chip.setTextColor(enabled ? accent : Theme.getColor(Theme.key_dialogTextBlack, provider));
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12),
                background, Theme.blendOver(background, 0x18000000)));
    }

    private static TextView action(Activity activity, Theme.ResourcesProvider provider,
                                   int title, boolean primary, Runnable callback) {
        TextView button = new TextView(activity);
        button.setText(safeString(activity, title));
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14);
        button.setTypeface(AndroidUtilities.bold());
        button.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(10),
                AndroidUtilities.dp(13), AndroidUtilities.dp(10));
        button.setMinHeight(AndroidUtilities.dp(44));
        styleAction(button, provider, primary);
        button.setOnClickListener(v -> callback.run());
        return button;
    }

    private static void addAction(Activity activity, Theme.ResourcesProvider provider,
                                  LinearLayout root, int title, boolean primary, Runnable callback) {
        TextView button = action(activity, provider, title, primary, callback);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = AndroidUtilities.dp(7);
        root.addView(button, lp);
    }

    /** Re-render in place; stale chip taps never replace a newer preview. */
    private static void refreshPreview(ChatActivity chat, Result current, ImageView image,
                                       ArrayList<Entry> entries, ArrayList<File> paths,
                                       java.util.concurrent.atomic.AtomicInteger version) {
        if (!active(chat.getParentActivity())) return;
        final int expected = version.incrementAndGet();
        Activity activity = chat.getParentActivity();
        final QuotePalette palette = capturePalette(chat, PengramConfig.getIntCached(KEY_BACKGROUND, 0) == 0);
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(3, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final boolean jpeg = PengramConfig.getBool(KEY_JPEG, false)
                && PengramConfig.getIntCached(KEY_BACKGROUND, 0) != 1;
        final String chatName = chat.getCurrentChat() != null ? chat.getCurrentChat().title
                : chat.getCurrentUser() != null ? UserObject.getUserName(chat.getCurrentUser()) : "";
        image.animate().cancel();
        image.setAlpha(.55f);
        Utilities.globalQueue.postRunnable(() -> {
            Result updated = null;
            try {
                updated = render(activity, entries, paths, dark, style, jpeg, false, palette, chatName);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (palette.wallpaper != null) palette.wallpaper.recycle();
                for (Entry entry : entries) {
                    if (entry.media != null && !entry.media.isRecycled()) entry.media.recycle();
                    entry.media = null;
                    for (Bitmap tile : entry.albumImages) if (tile != null && !tile.isRecycled()) tile.recycle();
                    entry.albumImages.clear();
                }
            }
            Result ready = updated;
            AndroidUtilities.runOnUIThread(() -> {
                if (ready == null) {
                    if (version.get() == expected) {
                        image.animate().alpha(1f).setDuration(160).start();
                        error(chat, R.string.PengramQuoteRenderError);
                    }
                    return;
                }
                if (version.get() != expected || !active(chat.getParentActivity())) {
                    ready.preview.recycle();
                    ready.file.delete();
                    return;
                }
                Bitmap old = current.preview;
                current.file = ready.file;
                current.preview = ready.preview;
                current.jpeg = ready.jpeg;
                image.setImageBitmap(ready.preview);
                image.animate().alpha(1f).setDuration(180).withEndAction(() -> {
                    if (old != null && old != ready.preview && !old.isRecycled()) old.recycle();
                }).start();
            });
        });
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
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, chat.getResourceProvider()));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(9));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        ScrollView pictureScroll = new ScrollView(activity);
        pictureScroll.setFillViewport(false);
        pictureScroll.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),
                Theme.getColor(Theme.key_windowBackgroundGray, chat.getResourceProvider())));
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
        final java.util.concurrent.atomic.AtomicInteger previewVersion = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < keys.length; i++) {
            if (i == 1 && !PengramConfig.getBool(KEY_NAME, true)) continue;
            if (i == 5 && PengramConfig.getBool(KEY_THEME_STYLE, true)) continue;
            final int idx = i;
            boolean enabled = PengramConfig.getBool(config[i], defaults[i]);
            TextView chip = action(activity, chat.getResourceProvider(), keys[i], false, () -> {});
            styleChip(chip, chat.getResourceProvider(), enabled);
            chip.setOnClickListener(v -> {
                boolean next = !PengramConfig.getBool(config[idx], defaults[idx]);
                PengramConfig.setBool(config[idx], next);
                styleChip(chip, chat.getResourceProvider(), next);
                refreshPreview(chat, result, image, entries, paths, previewVersion);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = AndroidUtilities.dp(6);
            toggles.addView(chip, lp);
        }
        TextView bgChip = action(activity, chat.getResourceProvider(), R.string.PengramQuoteBackground,
                false, () -> {});
        styleChip(bgChip, chat.getResourceProvider(), PengramConfig.getIntCached(KEY_BACKGROUND, 0) != 0);
        bgChip.setOnClickListener(v -> {
            int next = (Math.max(0, Math.min(2, PengramConfig.getIntCached(KEY_BACKGROUND, 0))) + 1) % 3;
            PengramConfig.setIntValue(KEY_BACKGROUND, next);
            styleChip(bgChip, chat.getResourceProvider(), next != 0);
            refreshPreview(chat, result, image, entries, paths, previewVersion);
        });
        toggles.addView(bgChip, new LinearLayout.LayoutParams(-2, -2));
        quick.addView(toggles);
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(-1, -2);
        quickParams.topMargin = AndroidUtilities.dp(8);
        root.addView(quick, quickParams);

        boolean canSend = chat.getCurrentChat() == null || ChatObject.canWriteToChat(chat.getCurrentChat())
                && ChatObject.canSendPhoto(chat.getCurrentChat());
        if (canSend) {
            addAction(activity, chat.getResourceProvider(), root, R.string.PengramQuoteSend, true, () -> {
                send(chat, result);
                if (ref[0] != null) ref[0].dismiss();
            });
        }
        // The primary decision stays visible; less common formats are one tap away,
        // not six full-width buttons occupying the entire screen.
        LinearLayout secondary = new LinearLayout(activity);
        secondary.setOrientation(LinearLayout.HORIZONTAL);
        int[] secondaryLabels = {R.string.Copy, R.string.Save, R.string.PengramQuoteMore};
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
            TextView button = action(activity, chat.getResourceProvider(), secondaryLabels[i], false, secondaryActions[i]);
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
        ref[0].setOnDismissListener(dialog -> previewVersion.incrementAndGet());
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
        final QuotePalette palette = capturePalette(chat, !PengramConfig.getBool(KEY_STICKER_TRANSPARENT, true)
                && PengramConfig.getIntCached(KEY_BACKGROUND, 0) == 0);
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(3, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final AlertDialog spinner = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, chat.getResourceProvider());
        chat.showDialog(spinner);
        Utilities.globalQueue.postRunnable(() -> {
            File webp = null;
            File sourceFile = null;
            try {
                Result source = render(activity, entries, paths, dark, style, false, true,
                        palette, chatName);
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
                if (palette.wallpaper != null) palette.wallpaper.recycle();
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
