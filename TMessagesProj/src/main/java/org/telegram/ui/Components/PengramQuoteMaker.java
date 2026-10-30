package org.telegram.ui.Components;

import android.app.Activity;
import android.content.DialogInterface;
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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
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
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.PengramSettingsActivity;
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

    /** 0: studio preview, 1: render and send photo immediately, 2: render and send sticker immediately. */
    public static final String KEY_ACTION_MODE = "quoteActionMode";
    public static final String KEY_THEME_STYLE = "quoteFollowChatTheme";
    public static final String KEY_NAME = "quoteShowName";
    public static final String KEY_AVATAR = "quoteShowAvatar";
    /** 0: outer side avatar column like official Telegram, 1: compact avatar inside bubble header. */
    public static final String KEY_AVATAR_LAYOUT = "quoteAvatarLayout";
    public static final String KEY_AVATAR_SIZE = "quoteAvatarSize";
    public static final String KEY_AVATAR_GAP = "quoteAvatarGap";
    public static final String KEY_AVATAR_ROUNDED = "quoteAvatarRounded";
    public static final String KEY_BUBBLE_TAIL = "quoteBubbleTail";
    public static final String KEY_PEER_NAME_COLOR = "quotePeerNameColor";
    public static final String KEY_SHOW_REPLY = "quoteShowReply";
    public static final String KEY_SHOW_FORWARD = "quoteShowForward";
    public static final String KEY_SHOW_BADGES = "quoteShowBadges";
    public static final String KEY_REACTIONS = "quoteShowReactions";
    public static final String KEY_ALIGN_OUTGOING = "quoteAlignOutgoing";
    public static final String KEY_DATE_IN_TIME = "quoteDateInTime";
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

    public static final int AVATAR_LAYOUT_SIDE = 0;
    public static final int AVATAR_LAYOUT_INLINE = 1;

    public static File logoFile(Context context) {
        return new File(context.getFilesDir(), "pengram_quote_logo.png");
    }
    public static final int MAX_MESSAGES = 12;
    private static final int WIDTH = 720;
    private static final long MAX_PIXELS = 7_000_000L;

    /** Official Telegram 2-tone diagonal avatar gradients (Red, Orange, Violet, Green, Cyan, Blue, Pink). */
    private static final int[][] PEER_AVATAR_GRADIENTS = {
            {0xFFFF885E, 0xFFFF516A},
            {0xFFFFCD6A, 0xFFFFA85C},
            {0xFF82B1FF, 0xFF665FFF},
            {0xFFA0DE7E, 0xFF54CB68},
            {0xFF53EDD6, 0xFF28C9B7},
            {0xFF72D5FD, 0xFF2A9EF1},
            {0xFFE0A2F3, 0xFFD669ED}
    };
    private static final int[] DEFAULT_PEER_NAME_DARK = {
            0xFFFF6B6B, 0xFFFFA94D, 0xFF9775FA, 0xFF69DB7C, 0xFF3BC9DB, 0xFF4DABF7, 0xFFF783AC
    };
    private static final int[] DEFAULT_PEER_NAME_LIGHT = {
            0xFFE03131, 0xFFE8590C, 0xFF7048E8, 0xFF2F9E44, 0xFF0C8599, 0xFF1971C2, 0xFFC2255C
    };

    private static final class ReactionChip {
        String emoticon;
        int count;
        boolean chosen;
        boolean paid;
    }

    private static final class Entry {
        String name, initials, text, shortTime, fullTime, editedLabel;
        String missingMediaLabel, unavailableLabel;
        String replyAuthor, replySnippet;
        int replyPeerColor;
        String forwardHeader;
        boolean verified, scam, fake;
        boolean outgoing, outRead;
        int peerColorIndex;
        int peerNameColor;
        final ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        final ArrayList<ReactionChip> reactions = new ArrayList<>();
        boolean motion;
        long groupId, senderId;
        StaticLayout layout;
        Bitmap media;
        Bitmap avatar;
        File avatarFile;
        File imageFile, videoFile, lottieFile;
        byte[] cachedImage, strippedImage;
        boolean sticker, visual, framelessSticker;
        final ArrayList<Entry> albumEntries = new ArrayList<>();
        final ArrayList<Bitmap> albumImages = new ArrayList<>();
        int imageHeight, imageWidth, replyBlockHeight, reactionsHeight, height, width;
        boolean grouped, lastInGroup;
        boolean timeInline;
        int gapBefore = 13;
    }

    private static final class Result {
        File file;
        Bitmap preview;
        boolean jpeg;
        boolean published; // Upload/share/copy may still read this file after the dialog closes.
    }

    private static void discard(Result result) {
        if (result == null) return;
        if (result.preview != null && !result.preview.isRecycled()) result.preview.recycle();
        if (!result.published && result.file != null) result.file.delete();
    }

    /** Immutable UI-thread snapshot: never draw the live wallpaper or read a changing theme on a worker. */
    private static final class QuotePalette {
        Bitmap wallpaper;
        int background, bubble, outBubble, ink, outInk, muted, outMuted, link, accent;
    }

    private static QuotePalette capturePalette(ChatActivity chat, boolean includeWallpaper) {
        QuotePalette palette = new QuotePalette();
        Theme.ResourcesProvider provider = chat.getResourceProvider();
        int wallpaperColor = Theme.getColor(Theme.key_chat_wallpaper, provider);
        palette.background = wallpaperColor != 0 ? wallpaperColor | 0xff000000
                : Theme.getColor(Theme.key_windowBackgroundGray, provider) | 0xff000000;
        palette.bubble = Theme.getColor(Theme.key_chat_inBubble, provider);
        palette.outBubble = Theme.getColor(Theme.key_chat_outBubble, provider);
        palette.ink = Theme.getColor(Theme.key_chat_messageTextIn, provider);
        palette.outInk = Theme.getColor(Theme.key_chat_messageTextOut, provider);
        palette.muted = Theme.getColor(Theme.key_chat_inTimeText, provider);
        palette.outMuted = Theme.getColor(Theme.key_chat_outTimeText, provider);
        palette.link = Theme.getColor(Theme.key_chat_messageLinkIn, provider);
        palette.accent = Theme.getColor(Theme.key_chat_inForwardedNameText, provider);
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

    private static void drawWallpaper(Canvas canvas, Bitmap wallpaper, int width, int height, Paint paint) {
        int tileH = Math.max(1, wallpaper.getHeight());
        int tileW = Math.min(wallpaper.getWidth(), Math.max(1, width));
        int srcX = Math.max(0, (wallpaper.getWidth() - tileW) / 2);
        for (int y = 0; y < height; y += tileH) {
            int chunk = Math.min(tileH, height - y);
            canvas.drawBitmap(wallpaper, new Rect(srcX, 0, srcX + tileW, chunk),
                    new Rect(0, y, width, y + chunk), paint);
        }
    }

    private static int resolvePeerColorIndex(long peerId, TLRPC.User user, TLRPC.Chat chat) {
        int colorId = -1;
        if (user != null) {
            colorId = UserObject.getColorId(user);
        } else if (chat != null) {
            colorId = ChatObject.getColorId(chat);
        }
        if (colorId >= 0) {
            return colorId % 7;
        }
        return AvatarDrawable.getColorIndex(Math.abs(peerId));
    }

    private static int resolvePeerNameColor(int account, long peerId, TLRPC.User user, TLRPC.Chat chat,
                                            Theme.ResourcesProvider provider) {
        int colorId = -1;
        if (user != null) {
            colorId = UserObject.getColorId(user);
        } else if (chat != null) {
            colorId = ChatObject.getColorId(chat);
        }
        if (colorId < 0) {
            colorId = AvatarDrawable.getColorIndex(Math.abs(peerId));
        }
        if (colorId >= 0 && colorId < Theme.keys_avatar_nameInMessage.length) {
            return Theme.getColor(Theme.keys_avatar_nameInMessage[colorId], provider);
        }
        MessagesController.PeerColors peerColors = MessagesController.getInstance(account).peerColors;
        MessagesController.PeerColor pc = peerColors != null ? peerColors.getColor(colorId) : null;
        if (pc != null) {
            return pc.getColor(0, provider);
        }
        return Theme.getColor(Theme.keys_avatar_nameInMessage[Math.abs(colorId) % Theme.keys_avatar_nameInMessage.length], provider);
    }

    private static String extractInitials(TLRPC.User user, String displayName) {
        if (user != null) {
            String first = user.first_name != null ? user.first_name.trim() : "";
            String last = user.last_name != null ? user.last_name.trim() : "";
            StringBuilder sb = new StringBuilder(4);
            if (!first.isEmpty()) {
                int cp = first.codePointAt(0);
                sb.appendCodePoint(Character.toUpperCase(cp));
            }
            if (!last.isEmpty()) {
                int cp = last.codePointAt(0);
                sb.appendCodePoint(Character.toUpperCase(cp));
            }
            if (sb.length() > 0) return sb.toString();
        }
        if (TextUtils.isEmpty(displayName)) return "?";
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) return "?";
        String[] parts = trimmed.split("\\s+");
        StringBuilder sb = new StringBuilder(4);
        int firstCp = parts[0].codePointAt(0);
        sb.appendCodePoint(Character.toUpperCase(firstCp));
        if (parts.length > 1 && !parts[parts.length - 1].isEmpty()) {
            int secondCp = parts[parts.length - 1].codePointAt(0);
            sb.appendCodePoint(Character.toUpperCase(secondCp));
        }
        return sb.toString();
    }

    private static String summarizeReplyMessage(Activity activity, MessageObject reply) {
        if (reply == null || reply.messageOwner == null) return "";
        if (!TextUtils.isEmpty(reply.caption)) return reply.caption.toString().replace('\n', ' ').trim();
        if (!TextUtils.isEmpty(reply.messageOwner.message)) {
            return reply.messageOwner.message.replace('\n', ' ').trim();
        }
        if (reply.isAnyKindOfSticker() || reply.isSticker()) return safeString(activity, R.string.PengramQuoteSticker);
        if (reply.isPhoto()) return safeString(activity, R.string.PengramQuotePhoto);
        if (reply.isVideo() || reply.isRoundVideo() || reply.isGif()) return safeString(activity, R.string.PengramQuoteVideo);
        if (reply.isVoice()) return safeString(activity, R.string.PengramQuoteVoice);
        if (reply.isMusic()) return safeString(activity, R.string.PengramQuoteMusic);
        if (reply.isDocument()) return safeString(activity, R.string.PengramQuoteDocument);
        return reply.messageText != null ? reply.messageText.toString().replace('\n', ' ').trim() : "";
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
        // Chat selection is mutable; a pending render must retain exactly the
        // messages the user approved, not a later recycled selection list.
        final ArrayList<MessageObject> snapshot = new ArrayList<>(selected);
        final ArrayList<Entry> entries = new ArrayList<>();
        final MessagesController controller = MessagesController.getInstance(chat.getCurrentAccount());
        final ArrayList<File> imagePaths = new ArrayList<>();
        for (MessageObject message : snapshot) {
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
            final String fakeName = PengramConfig.getQuoteFakeName().trim();
            TLRPC.User senderUser = senderId > 0 ? controller.getUser(senderId) : null;
            TLRPC.Chat senderChat = senderId < 0 ? controller.getChat(-senderId) : null;
            if (!fakeName.isEmpty()) {
                entry.name = fakeName;
                entry.peerColorIndex = AvatarDrawable.getColorIndex(Math.abs(fakeName.hashCode()));
                entry.peerNameColor = Theme.getColor(
                        Theme.keys_avatar_nameInMessage[entry.peerColorIndex % Theme.keys_avatar_nameInMessage.length],
                        chat.getResourceProvider());
                entry.initials = extractInitials(null, fakeName);
            } else {
                if (senderUser != null) {
                    entry.name = UserObject.getUserName(senderUser);
                    entry.verified = senderUser.verified;
                    entry.scam = senderUser.scam;
                    entry.fake = senderUser.fake;
                } else if (senderChat != null) {
                    entry.name = senderChat.title;
                    entry.verified = senderChat.verified;
                    entry.scam = senderChat.scam;
                    entry.fake = senderChat.fake;
                } else if (message.messageOwner.fwd_from != null && !TextUtils.isEmpty(message.messageOwner.fwd_from.from_name)) {
                    entry.name = message.messageOwner.fwd_from.from_name;
                }
                if (TextUtils.isEmpty(entry.name)) entry.name = safeString(activity, R.string.PengramQuoteUnknown);
                entry.peerColorIndex = resolvePeerColorIndex(senderId != 0 ? senderId : entry.name.hashCode(), senderUser, senderChat);
                entry.peerNameColor = resolvePeerNameColor(chat.getCurrentAccount(),
                        senderId != 0 ? senderId : entry.name.hashCode(), senderUser, senderChat, chat.getResourceProvider());
                entry.initials = extractInitials(senderUser, entry.name);
            }
            entry.outgoing = message.isOutOwner();
            entry.outRead = !message.isUnread();

            // Forwarded-from mini-header
            if (fakeName.isEmpty() && message.isForwarded() && message.messageOwner.fwd_from != null) {
                TLRPC.MessageFwdHeader fwd = message.messageOwner.fwd_from;
                String fwdAuthor = null;
                if (!TextUtils.isEmpty(fwd.from_name)) {
                    fwdAuthor = fwd.from_name;
                } else if (fwd.from_id != null) {
                    long fid = MessageObject.getPeerId(fwd.from_id);
                    if (fid > 0) {
                        TLRPC.User fu = controller.getUser(fid);
                        if (fu != null) fwdAuthor = UserObject.getUserName(fu);
                    } else if (fid < 0) {
                        TLRPC.Chat fc = controller.getChat(-fid);
                        if (fc != null) fwdAuthor = fc.title;
                    }
                }
                if (!TextUtils.isEmpty(fwdAuthor) && !TextUtils.equals(fwdAuthor, entry.name)) {
                    Locale loc = LocaleController.getInstance().getCurrentLocale();
                    boolean ru = loc != null && "ru".equals(loc.getLanguage());
                    entry.forwardHeader = (ru ? "Переслано от " : "Forwarded from ") + fwdAuthor;
                }
            }

            // Reply-to quote block
            if (message.replyMessageObject != null && message.replyMessageObject.messageOwner != null) {
                MessageObject replyObj = message.replyMessageObject;
                long rSenderId = replyObj.getSenderId();
                TLRPC.User rUser = rSenderId > 0 ? controller.getUser(rSenderId) : null;
                TLRPC.Chat rChat = rSenderId < 0 ? controller.getChat(-rSenderId) : null;
                String rName = rUser != null ? UserObject.getUserName(rUser)
                        : rChat != null ? rChat.title : null;
                if (TextUtils.isEmpty(rName) && replyObj.messageOwner.fwd_from != null) {
                    rName = replyObj.messageOwner.fwd_from.from_name;
                }
                String rSnippet = summarizeReplyMessage(activity, replyObj);
                if (!TextUtils.isEmpty(rName) || !TextUtils.isEmpty(rSnippet)) {
                    entry.replyAuthor = TextUtils.isEmpty(rName)
                            ? safeString(activity, R.string.PengramQuoteUnknown) : rName;
                    entry.replySnippet = rSnippet == null ? "" : rSnippet;
                    entry.replyPeerColor = resolvePeerNameColor(chat.getCurrentAccount(),
                            rSenderId != 0 ? rSenderId : entry.replyAuthor.hashCode(),
                            rUser, rChat, chat.getResourceProvider());
                }
            }

            if (fakeName.isEmpty()) {
                org.telegram.tgnet.TLObject avatarPeer = senderUser != null ? senderUser : senderChat;
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
            String rawOwnerMsg = message.messageOwner.message;
            if (!TextUtils.isEmpty(message.caption)) {
                entry.text = message.caption.toString();
                if (message.messageOwner.entities != null && TextUtils.equals(entry.text, rawOwnerMsg)) {
                    entry.entities.addAll(message.messageOwner.entities);
                }
            } else if (entry.visual) {
                entry.text = "";
            } else if (!TextUtils.isEmpty(rawOwnerMsg)) {
                entry.text = rawOwnerMsg;
                if (message.messageOwner.entities != null) {
                    entry.entities.addAll(message.messageOwner.entities);
                }
            } else {
                entry.text = message.messageText == null ? "" : message.messageText.toString();
            }
            if (MessageObject.getMedia(message.messageOwner) instanceof TLRPC.TL_messageMediaPoll) {
                TLRPC.TL_messageMediaPoll poll = (TLRPC.TL_messageMediaPoll) MessageObject.getMedia(message.messageOwner);
                if (poll.poll != null && poll.poll.question != null) {
                    StringBuilder question = new StringBuilder("📊 ").append(poll.poll.question.text);
                    if (poll.poll.answers != null) {
                        for (TLRPC.PollAnswer answer : poll.poll.answers) {
                            if (answer != null && answer.text != null) {
                                question.append("\n○ ").append(answer.text.text);
                            }
                        }
                    }
                    entry.text = question.toString();
                    entry.entities.clear();
                }
            }
            if (TextUtils.isEmpty(entry.text) && !entry.visual) {
                if (message.isVoice()) entry.text = "🎤 " + safeString(activity, R.string.PengramQuoteVoice);
                else if (message.isMusic()) entry.text = "🎵 " + safeString(activity, R.string.PengramQuoteMusic);
                else if (message.isDocument()) entry.text = "📎 " + safeString(activity, R.string.PengramQuoteDocument);
                else if (message.messageText == null) {
                    error(chat, R.string.PengramQuoteUnsupported);
                    return;
                }
            }
            if (entry.text.length() > 5000) {
                error(chat, R.string.PengramQuoteTooLong);
                return;
            }
            Date msgDate = new Date(message.messageOwner.date * 1000L);
            entry.shortTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(msgDate);
            entry.fullTime = new SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(msgDate);
            if (message.messageOwner.edit_date != 0) {
                Locale loc = LocaleController.getInstance().getCurrentLocale();
                boolean ru = loc != null && "ru".equals(loc.getLanguage());
                entry.editedLabel = ru ? "изм." : "edited";
            }
            if (message.messageOwner.reactions != null && message.messageOwner.reactions.results != null) {
                for (TLRPC.ReactionCount rc : message.messageOwner.reactions.results) {
                    if (rc == null || rc.count <= 0) continue;
                    String emo = null;
                    boolean paid = false;
                    if (rc.reaction instanceof TLRPC.TL_reactionEmoji) {
                        emo = ((TLRPC.TL_reactionEmoji) rc.reaction).emoticon;
                    } else if (rc.reaction instanceof TLRPC.TL_reactionPaid) {
                        emo = "⭐";
                        paid = true;
                    }
                    if (!TextUtils.isEmpty(emo)) {
                        ReactionChip chip = new ReactionChip();
                        chip.emoticon = emo;
                        chip.count = rc.count;
                        chip.chosen = rc.chosen;
                        chip.paid = paid;
                        entry.reactions.add(chip);
                        if (entry.reactions.size() >= 6) break;
                    }
                }
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
                        if (TextUtils.isEmpty(previous.text)) {
                            previous.text = entry.text;
                            previous.entities.clear();
                            previous.entities.addAll(entry.entities);
                        } else {
                            previous.text = previous.text + "\n" + entry.text;
                            previous.entities.clear();
                        }
                    }
                    previous.shortTime = entry.shortTime;
                    previous.fullTime = entry.fullTime;
                    if (!entry.reactions.isEmpty() && previous.reactions.isEmpty()) {
                        previous.reactions.addAll(entry.reactions);
                    }
                    continue;
                }
            }
            imagePaths.add(entry.imageFile);
            entries.add(entry);
        }
        final int actionMode = PengramConfig.getIntCached(KEY_ACTION_MODE, 0);
        if (actionMode == 2 && (chat.getCurrentChat() == null
                || (ChatObject.canWriteToChat(chat.getCurrentChat()) && ChatObject.canSendStickers(chat.getCurrentChat())))) {
            sticker(chat, entries, imagePaths, snapshot);
            return;
        }
        final boolean jpeg = PengramConfig.getBool(KEY_JPEG, false)
                && PengramConfig.getIntCached(KEY_BACKGROUND, 0) != 1;
        final String chatName = chat.getCurrentChat() != null ? chat.getCurrentChat().title
                : chat.getCurrentUser() != null ? UserObject.getUserName(chat.getCurrentUser()) : "";
        final QuotePalette palette = capturePalette(chat, PengramConfig.getIntCached(KEY_BACKGROUND, 0) == 0);
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(6, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final AlertDialog spinner = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, chat.getResourceProvider());
        // A dismissed spinner (Back, chat navigation, replacement dialog) cancels
        // delivery, whereas the normal completion dismissal does not.
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        if (chat.showDialog(spinner, dialog -> {
            if (!completed.get()) cancelled.set(true);
        }) == null) {
            if (palette.wallpaper != null) palette.wallpaper.recycle();
            return;
        }
        spinner.setCanceledOnTouchOutside(false);
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
            if (cancelled.get()) {
                if (palette.wallpaper != null) palette.wallpaper.recycle();
                return;
            }
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
                completed.set(true);
                spinner.dismiss();
                if (cancelled.get() || !active(activity)) {
                    discard(ready);
                    return;
                }
                if (ready == null) {
                    error(chat, R.string.PengramQuoteRenderError);
                } else if (isRestricted(chat, snapshot)) {
                    // A chat can turn on content protection while the background render runs.
                    discard(ready);
                    error(chat, R.string.PengramQuoteRestricted);
                } else {
                    if (PengramConfig.getIntCached(KEY_ACTION_MODE, 0) == 1
                            && (chat.getCurrentChat() == null || ChatObject.canWriteToChat(chat.getCurrentChat())
                            && ChatObject.canSendPhoto(chat.getCurrentChat()))) {
                        ready.published = send(chat, ready);
                        discard(ready);
                    } else {
                        preview(chat, ready, snapshot, entries, imagePaths);
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
        if (image == null && entry.cachedImage != null) image = decodeBytes(entry.cachedImage, entry.sticker);
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

    private static Bitmap decodeBytes(byte[] bytes, boolean alpha) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1100 || bounds.outHeight / options.inSampleSize > 1100) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = alpha || "image/png".equals(bounds.outMimeType)
                || "image/webp".equals(bounds.outMimeType) ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
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
        final int clampedStyle = Math.max(0, Math.min(6, style));
        final int[] styleAccents = {
                0xff3b82f6, // Telegram Blue
                0xff16a34a, // Emerald
                0xffdb2777, // Rose
                0xffd97706, // Amber
                0xff8b5cf6, // Premium Violet
                0xff38bdf8, // Midnight Sky
                0xff94a3b8  // Graphite
        };
        final int[] styleDarkBubbles = {
                0xff1e293b, 0xff162822, 0xff291b27, 0xff292118, 0xff221c35, 0xff111927, 0xff1c1e22
        };
        final int[] styleLightBubbles = {
                0xffffffff, 0xfff7fdf9, 0xfffff8fb, 0xfffffcf5, 0xfffaf7ff, 0xfff4f8ff, 0xfff8fafc
        };
        final int[] styleOutDarkBubbles = {
                0xff2b5278, 0xff1e4635, 0xff4c2440, 0xff4a3419, 0xff3b2b63, 0xff1d3557, 0xff2d3139
        };
        final int[] styleOutLightBubbles = {
                0xffe3f2fd, 0xffe2f8e9, 0xfffce7f3, 0xfffef3c7, 0xffede9fe, 0xffe0f2fe, 0xffe2e8f0
        };
        final boolean effectiveDark = !followTheme && (dark || clampedStyle == 5);
        int baseAccent = followTheme
                ? (palette.accent != 0 ? palette.accent : 0xff3b82f6)
                : styleAccents[clampedStyle];
        final int bgType = sticker && PengramConfig.getBool(KEY_STICKER_TRANSPARENT, true) ? 1
                : Math.max(0, Math.min(7, PengramConfig.getIntCached(KEY_BACKGROUND, 0)));
        final boolean transparentCanvas = bgType == 1 && !jpeg;
        final int background = transparentCanvas ? Color.TRANSPARENT
                : bgType == 2 ? (PengramConfig.getIntCached(KEY_BACKGROUND_COLOR, 0xffeef3ff) | 0xff000000)
                : bgType == 0 ? palette.background
                : bgType == 3 ? 0xff0f172a
                : bgType == 4 ? 0xffdbeafe
                : bgType == 5 ? 0xff1e1b4b
                : bgType == 6 ? 0xff3b182f
                : 0xff000000;
        final int pad = Math.max(0, Math.min(72, PengramConfig.getIntCached(KEY_PADDING, 24)));
        final int radius = Math.max(0, Math.min(64, PengramConfig.getIntCached(KEY_RADIUS, 28)));
        final float requestedScale = sticker ? 1f : new float[]{1f, 1.5f, 2f}[
                Math.max(0, Math.min(2, PengramConfig.getIntCached(KEY_SCALE, 0)))];

        final int inSurface = followTheme ? palette.bubble
                : effectiveDark ? styleDarkBubbles[clampedStyle] : styleLightBubbles[clampedStyle];
        final int outSurface = followTheme ? (palette.outBubble != 0 ? palette.outBubble : palette.bubble)
                : effectiveDark ? styleOutDarkBubbles[clampedStyle] : styleOutLightBubbles[clampedStyle];
        final int inInk = followTheme ? palette.ink : effectiveDark ? Color.WHITE : 0xff111827;
        final int outInk = followTheme ? (palette.outInk != 0 ? palette.outInk : inInk)
                : effectiveDark ? Color.WHITE : 0xff111827;
        final int inMuted = followTheme ? palette.muted : effectiveDark ? 0xff94a3b8 : 0xff6b7280;
        final int outMuted = followTheme ? (palette.outMuted != 0 ? palette.outMuted : inMuted)
                : effectiveDark ? 0xff93c5fd : 0xff4b7a5e;
        final int baseLinkColor = followTheme
                ? (palette.link != 0 ? palette.link : baseAccent)
                : baseAccent;

        final boolean darkSurface = luminance(inSurface) < 0.45;
        float fontScale = Math.max(1f, Math.min(1.50f, activity.getResources().getConfiguration().fontScale));

        final boolean showNames = PengramConfig.getBool(KEY_NAME, true);
        final int avatarLayout = Math.max(0, Math.min(1, PengramConfig.getIntCached(KEY_AVATAR_LAYOUT, AVATAR_LAYOUT_SIDE)));
        final boolean sideAvatarMode = avatarLayout == AVATAR_LAYOUT_SIDE;
        final boolean avatarEnabled = PengramConfig.getBool(KEY_AVATAR, true);
        final boolean showAvatars = !sideAvatarMode && showNames && avatarEnabled;
        final boolean showSideAvatars = sideAvatarMode && avatarEnabled;
        final float avatarSize = Math.max(22, Math.min(42,
                PengramConfig.getIntCached(KEY_AVATAR_SIZE, 28))) * fontScale;
        final float avatarGap = Math.max(4, Math.min(20,
                PengramConfig.getIntCached(KEY_AVATAR_GAP, 9))) * fontScale;
        final float sideAvatarSize = Math.round(avatarSize * 1.92f); // ~54px at 720w, authentic 42dp Telegram proportion
        final float sideAvatarGap = Math.round(avatarGap + 4f * fontScale);
        final boolean roundedAvatar = PengramConfig.getBool(KEY_AVATAR_ROUNDED, false);
        final boolean bubbleTail = PengramConfig.getBool(KEY_BUBBLE_TAIL, true);
        final boolean usePeerNameColors = PengramConfig.getBool(KEY_PEER_NAME_COLOR, true);
        final boolean showReply = PengramConfig.getBool(KEY_SHOW_REPLY, true);
        final boolean showForward = PengramConfig.getBool(KEY_SHOW_FORWARD, true);
        final boolean showBadges = PengramConfig.getBool(KEY_SHOW_BADGES, true);
        final boolean showReactions = PengramConfig.getBool(KEY_REACTIONS, true);
        final boolean alignOutgoing = PengramConfig.getBool(KEY_ALIGN_OUTGOING, false) && entries.size() > 1;
        final boolean dateInTime = PengramConfig.getBool(KEY_DATE_IN_TIME, false);
        final boolean includeMedia = PengramConfig.getBool(KEY_MEDIA, true);
        final boolean showTimes = PengramConfig.getBool(KEY_TIME, true);
        final boolean hideMentions = PengramConfig.getBool(KEY_ANON_MENTIONS, false);

        final int innerPadX = Math.round(20 * fontScale);
        final int innerPadTop = Math.round(14 * fontScale);
        final int innerPadBottom = Math.round(13 * fontScale);
        final int tailReserve = bubbleTail ? Math.round(11 * fontScale) : 0;
        final int sideColumnW = showSideAvatars ? Math.round(sideAvatarSize + sideAvatarGap) : 0;
        final int maxBubbleOuterW = Math.max(240, WIDTH - pad * 2 - sideColumnW - tailReserve);
        final int contentWidth = Math.max(180, maxBubbleOuterW - innerPadX * 2);

        final int nameHeight = (int) Math.ceil(showAvatars
                ? Math.max(34 * fontScale, avatarSize + 6 * fontScale)
                : 32 * fontScale);
        final int fwdHeight = (int) Math.ceil(25 * fontScale);
        final int timeHeight = (int) Math.ceil(24 * fontScale);

        final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setTextSize(26 * fontScale);
        final TextPaint nameMeasurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        nameMeasurePaint.setTypeface(Typeface.DEFAULT_BOLD);
        nameMeasurePaint.setTextSize(25 * fontScale);
        final TextPaint smallMeasurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        smallMeasurePaint.setTextSize(20 * fontScale);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        // Pass 1: determine consecutive sender groups (first & last in group)
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            final boolean eOut = alignOutgoing && e.outgoing;
            final boolean prevSame = i > 0
                    && (alignOutgoing && entries.get(i - 1).outgoing) == eOut
                    && e.senderId != 0
                    && entries.get(i - 1).senderId == e.senderId
                    && TextUtils.equals(entries.get(i - 1).name, e.name);
            e.grouped = prevSame;
            e.lastInGroup = true;
            if (prevSame) {
                entries.get(i - 1).lastInGroup = false;
            }
            e.gapBefore = i == 0 ? 0 : prevSame ? Math.round(5 * fontScale) : Math.round(13 * fontScale);
        }

        int total = pad + 8;
        int maxUsedBubbleSpan = 0;
        boolean hasAnyLeft = false;
        boolean hasAnyRight = false;

        // Pass 2: measure rich layouts, replies, media, reactions, inline timestamps
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            final boolean isOut = alignOutgoing && e.outgoing;
            if (isOut) hasAnyRight = true;
            else hasAnyLeft = true;

            final int bubbleSurf = isOut ? outSurface : inSurface;
            final int bubbleInk = isOut ? outInk : inInk;
            final int linkColor = readableOn(baseLinkColor, bubbleSurf);
            body.setColor(bubbleInk);

            final String name = showNames && !e.grouped && !isOut ? e.name : null;
            final String fwd = showForward && !TextUtils.isEmpty(e.forwardHeader) ? e.forwardHeader : null;
            String rawTime = showTimes ? (dateInTime ? e.fullTime : e.shortTime) : null;
            if (rawTime != null && !TextUtils.isEmpty(e.editedLabel)) {
                rawTime = e.editedLabel + " " + rawTime;
            }
            final String time = rawTime;
            final float outChecksW = (isOut && time != null) ? 28f * fontScale : 0f;

            e.imageHeight = 0;
            e.imageWidth = 0;
            e.media = null;
            e.albumImages.clear();
            if (includeMedia && e.visual) {
                e.media = decodeMedia(e);
                if (!e.albumEntries.isEmpty()) {
                    for (Entry tile : e.albumEntries) e.albumImages.add(decodeMedia(tile));
                    int albumW = Math.min(contentWidth, Math.round(460 * fontScale));
                    int cell = (albumW - 6) / 2;
                    int count = 1 + e.albumEntries.size();
                    e.imageWidth = albumW;
                    e.imageHeight = ((count + 1) / 2) * (cell + 6) - 6;
                } else if (e.media != null) {
                    if (e.sticker) {
                        final float aspect = e.media.getWidth() / (float) Math.max(1, e.media.getHeight());
                        final int maxSide = Math.min(Math.round(310 * fontScale), contentWidth);
                        int width = Math.min(maxSide, Math.round(maxSide * aspect));
                        int height = Math.max(1, Math.round(width / aspect));
                        if (height > maxSide) {
                            height = maxSide;
                            width = Math.max(1, Math.round(height * aspect));
                        }
                        e.imageWidth = width;
                        e.imageHeight = height;
                    } else {
                        int maxPhotoW = Math.min(contentWidth, Math.round(480 * fontScale));
                        float aspect = e.media.getHeight() / (float) Math.max(1, e.media.getWidth());
                        e.imageWidth = Math.max(Math.round(200 * fontScale),
                                Math.min(maxPhotoW, Math.round(e.media.getWidth() * 1.15f)));
                        e.imageHeight = Math.min(Math.round(460 * fontScale),
                                Math.max(Math.round(120 * fontScale), Math.round(e.imageWidth * aspect)));
                    }
                } else {
                    e.imageHeight = Math.round(136 * fontScale);
                    e.imageWidth = Math.min(contentWidth, Math.round(320 * fontScale));
                }
            }

            CharSequence richText = buildRichQuoteText(e.text, e.entities, hideMentions, body, linkColor, bubbleSurf);
            if (TextUtils.isEmpty(richText) && e.visual && !includeMedia) {
                richText = e.missingMediaLabel;
            }
            e.layout = TextUtils.isEmpty(richText) ? null : new StaticLayout(richText, body, contentWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.16f, 0, false);

            final boolean hasReplyBlock = showReply && (!TextUtils.isEmpty(e.replyAuthor) || !TextUtils.isEmpty(e.replySnippet));
            e.replyBlockHeight = hasReplyBlock ? Math.round(62 * fontScale) : 0;

            final boolean hasReactionsBlock = showReactions && !e.reactions.isEmpty();
            e.reactionsHeight = hasReactionsBlock ? Math.round(42 * fontScale) : 0;

            // Frameless Telegram sticker: standalone sticker with no text, no reply, no forward header, and either side-avatar or hidden name
            e.framelessSticker = includeMedia && e.sticker && e.media != null
                    && e.albumEntries.isEmpty() && e.layout == null
                    && !hasReplyBlock && fwd == null && (name == null);

            final int textHeight = e.layout == null ? 0 : e.layout.getHeight();
            float maxLineW = 0f;
            if (e.layout != null) {
                for (int line = 0; line < e.layout.getLineCount(); line++) {
                    maxLineW = Math.max(maxLineW, e.layout.getLineWidth(line));
                }
            }

            float badgeW = 0f;
            if (name != null && showBadges) {
                if (e.verified) badgeW = 28f * fontScale;
                else if (e.scam || e.fake) badgeW = 64f * fontScale;
            }
            float nameRowW = name == null ? 0f : nameMeasurePaint.measureText(name) + badgeW
                    + (showAvatars && !name.isEmpty() ? avatarSize + avatarGap : 0f);
            float fwdRowW = fwd == null ? 0f : smallMeasurePaint.measureText(fwd) + 8f * fontScale;

            float replyMinW = 0f;
            if (hasReplyBlock) {
                smallMeasurePaint.setTypeface(Typeface.DEFAULT_BOLD);
                smallMeasurePaint.setTextSize(21 * fontScale);
                float raW = e.replyAuthor != null ? smallMeasurePaint.measureText(e.replyAuthor) : 0f;
                smallMeasurePaint.setTypeface(Typeface.DEFAULT);
                smallMeasurePaint.setTextSize(20 * fontScale);
                float rsW = e.replySnippet != null ? smallMeasurePaint.measureText(e.replySnippet) : 0f;
                replyMinW = Math.min(contentWidth, Math.max(raW, rsW) + 28f * fontScale);
            }

            float reactionsRowW = 0f;
            if (hasReactionsBlock) {
                smallMeasurePaint.setTypeface(Typeface.DEFAULT_BOLD);
                smallMeasurePaint.setTextSize(19 * fontScale);
                for (ReactionChip rc : e.reactions) {
                    reactionsRowW += 40f * fontScale + smallMeasurePaint.measureText(formatReactionCount(rc.count)) + 8f * fontScale;
                }
                reactionsRowW = Math.min(contentWidth, reactionsRowW);
            }

            smallMeasurePaint.setTypeface(Typeface.DEFAULT);
            smallMeasurePaint.setTextSize(18.5f * fontScale);
            float timeW = time == null ? 0f : smallMeasurePaint.measureText(time) + outChecksW;

            e.timeInline = false;
            float textPlusInlineTimeW = maxLineW;
            if (time != null && e.layout != null && e.layout.getLineCount() > 0 && !hasReactionsBlock) {
                final float lastLine = e.layout.getLineWidth(e.layout.getLineCount() - 1);
                final float inlineCandidate = lastLine + 16f * fontScale + timeW;
                if (inlineCandidate <= contentWidth) {
                    e.timeInline = true;
                    textPlusInlineTimeW = Math.max(maxLineW, inlineCandidate);
                }
            }

            if (e.framelessSticker) {
                e.width = Math.max(Math.round(140 * fontScale), Math.round(Math.max(e.imageWidth, reactionsRowW)));
                e.height = e.imageHeight + (hasReactionsBlock ? e.reactionsHeight + Math.round(8 * fontScale) : 0)
                        + (time != null && e.media == null ? timeHeight : 0);
            } else {
                float neededInnerW = Math.max(
                        Math.max(textPlusInlineTimeW, Math.max(nameRowW, fwdRowW)),
                        Math.max(Math.max(replyMinW, reactionsRowW), Math.max(e.imageWidth, timeW)));
                int minBubbleW = Math.round(132 * fontScale);
                e.width = Math.min(maxBubbleOuterW,
                        Math.max(minBubbleW, (int) Math.ceil(neededInnerW) + innerPadX * 2));
                // Double-check inline fit against actual bubble inner width
                if (e.timeInline && e.layout != null) {
                    final float lastLine = e.layout.getLineWidth(e.layout.getLineCount() - 1);
                    if (lastLine + 14f * fontScale + timeW > e.width - innerPadX * 2 + 2f) {
                        e.timeInline = false;
                    }
                }
                final boolean photoOverlayTime = time != null && e.layout == null && e.imageHeight > 0
                        && !e.sticker && !hasReactionsBlock;
                int h = innerPadTop;
                if (name != null) h += nameHeight;
                if (fwd != null) h += fwdHeight;
                if (hasReplyBlock) h += e.replyBlockHeight + Math.round(8 * fontScale);
                if (e.imageHeight > 0) {
                    h += e.imageHeight + (e.layout != null || hasReactionsBlock || (!photoOverlayTime && time != null)
                            ? Math.round(10 * fontScale) : 0);
                }
                if (textHeight > 0) h += textHeight;
                if (hasReactionsBlock) h += Math.round(8 * fontScale) + e.reactionsHeight;
                if (time != null && !e.timeInline && !photoOverlayTime) {
                    h += timeHeight;
                }
                h += innerPadBottom;
                // Ensure tall enough for side avatar on standalone 1-line bubble
                if (showSideAvatars && !e.grouped && e.lastInGroup) {
                    h = Math.max(h, Math.round(sideAvatarSize));
                }
                e.height = h;
            }

            int rowSpan = (isOut ? 0 : sideColumnW) + e.width + tailReserve;
            maxUsedBubbleSpan = Math.max(maxUsedBubbleSpan, rowSpan);
            total += e.height + e.gapBefore;
        }
        total += pad + 8;

        final int canvasWidth = (hasAnyLeft && hasAnyRight)
                ? WIDTH
                : Math.min(WIDTH, Math.max(Math.round(260 * fontScale), maxUsedBubbleSpan + pad * 2));

        // Long albums should still render: reduce only the requested export scale,
        // never the layout, and stay inside the bitmap memory budget.
        if (total < 1 || (long) canvasWidth * total > MAX_PIXELS)
            throw new IllegalStateException("Quote too large");
        final float scale = Math.min(requestedScale,
                (float) Math.sqrt(MAX_PIXELS / ((double) canvasWidth * total)));
        final int outWidth = Math.max(1, (int) Math.round(canvasWidth * scale));
        final int outHeight = Math.max(1, (int) Math.round(total * scale));
        Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            if (!transparentCanvas) {
                drawCanvasBackdrop(canvas, bgType, background, canvasWidth, total, palette, paint);
            } else {
                canvas.drawColor(Color.TRANSPARENT);
            }
            canvas.scale(scale, scale);

            int y = pad + 8;
            final float mediaRadius = radius <= 2 ? 0f : Math.max(6f, Math.min(16f, radius * 0.58f));
            final Path bubblePath = new Path();

            for (int idx = 0; idx < entries.size(); idx++) {
                final Entry e = entries.get(idx);
                final boolean isOut = alignOutgoing && e.outgoing;
                final int surface = isOut ? outSurface : inSurface;
                final boolean cardDark = luminance(surface) < 0.45;
                final int muted = isOut ? outMuted : inMuted;
                final int rawSenderColor = usePeerNameColors
                        ? (e.peerNameColor != 0 ? e.peerNameColor
                            : (cardDark ? DEFAULT_PEER_NAME_DARK : DEFAULT_PEER_NAME_LIGHT)[Math.abs(e.peerColorIndex) % 7])
                        : baseAccent;
                int accent = readableOn(rawSenderColor, surface);

                final String name = showNames && !e.grouped && !isOut ? e.name : null;
                final String fwd = showForward && !TextUtils.isEmpty(e.forwardHeader) ? e.forwardHeader : null;
                String rawTime = showTimes ? (dateInTime ? e.fullTime : e.shortTime) : null;
                if (rawTime != null && !TextUtils.isEmpty(e.editedLabel)) {
                    rawTime = e.editedLabel + " " + rawTime;
                }
                final String time = rawTime;

                y += e.gapBefore;

                final int bubbleLeft = isOut
                        ? (canvasWidth - pad - tailReserve - e.width)
                        : (pad + sideColumnW + (bubbleTail ? Math.round(6 * fontScale) : 0));
                final int contentLeft = bubbleLeft + innerPadX;
                final RectF card = new RectF(bubbleLeft, y, bubbleLeft + e.width, y + e.height);

                // Side avatar column: drawn on the LAST message of a consecutive sender group, bottom-aligned with the bubble!
                if (showSideAvatars && !isOut && e.lastInGroup) {
                    final float avCx = pad + sideAvatarSize * 0.5f;
                    final float avCy = card.bottom - sideAvatarSize * 0.5f;
                    drawSenderAvatar(canvas, paint, e, avCx, avCy, sideAvatarSize, roundedAvatar, accent,
                            transparentCanvas ? cardDark : luminance(background) < 0.45, fontScale);
                }

                if (!e.framelessSticker) {
                    final float rFull = Math.min(radius, Math.min(card.width(), card.height()) * 0.48f);
                    final float rGrouped = Math.min(rFull, Math.max(4f, 8f * fontScale));
                    final float rTL = (!isOut && e.grouped) ? rGrouped : rFull;
                    final float rBL = (!isOut && !e.lastInGroup) ? rGrouped : rFull;
                    final float rTR = (isOut && e.grouped) ? rGrouped : rFull;
                    final float rBR = (isOut && !e.lastInGroup) ? rGrouped : rFull;
                    final boolean drawLeftTail = bubbleTail && !isOut && e.lastInGroup && radius >= 8;
                    final boolean drawRightTail = bubbleTail && isOut && e.lastInGroup && radius >= 8;
                    final float tailW = 10.5f * fontScale;

                    buildTelegramBubblePath(bubblePath, card, rTL, rTR, rBR, rBL, drawLeftTail, drawRightTail, tailW);
                    drawBubbleShadow(canvas, bubblePath, card, rFull, cardDark, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(surface);
                    canvas.drawPath(bubblePath, paint);
                    drawBubbleBorder(canvas, bubblePath, cardDark, paint);
                }

                int inner = y + (e.framelessSticker ? 0 : innerPadTop);
                if (name != null) {
                    paint.setTypeface(Typeface.DEFAULT_BOLD);
                    paint.setTextSize(25 * fontScale);
                    paint.setColor(accent);
                    final boolean avatar = showAvatars && !name.isEmpty();
                    final float nameInset = avatar ? avatarSize + avatarGap : 0;
                    if (avatar) {
                        final float cx = contentLeft + avatarSize / 2f;
                        final float cy = inner + nameHeight / 2f;
                        drawSenderAvatar(canvas, paint, e, cx, cy, avatarSize, roundedAvatar, accent, cardDark, fontScale);
                        paint.setStyle(Paint.Style.FILL);
                        // The face bitmap uses a white paint; restore name color,
                        // size and alignment for both the cached and fallback path.
                        paint.setColor(accent);
                        paint.setTextSize(25 * fontScale);
                        paint.setTextAlign(Paint.Align.LEFT);
                    }
                    paint.setTypeface(Typeface.DEFAULT_BOLD);
                    float badgeSpace = 0f;
                    if (showBadges) {
                        if (e.verified) badgeSpace = 28f * fontScale;
                        else if (e.scam || e.fake) badgeSpace = 62f * fontScale;
                    }
                    Paint.FontMetrics nameMetrics = paint.getFontMetrics();
                    final float baseline = inner + nameHeight / 2f
                            - (nameMetrics.ascent + nameMetrics.descent) / 2f;
                    final float maxNameW = Math.max(24f, e.width - innerPadX * 2 - nameInset - badgeSpace);
                    final String drawnName = TextUtils.ellipsize(name, new TextPaint(paint),
                            maxNameW, TextUtils.TruncateAt.END).toString();
                    canvas.drawText(drawnName, contentLeft + nameInset, baseline, paint);
                    if (showBadges && (e.verified || e.scam || e.fake)) {
                        final float badgeLeft = contentLeft + nameInset + paint.measureText(drawnName) + 6f * fontScale;
                        final float badgeCy = inner + nameHeight / 2f;
                        if (e.verified) {
                            drawVerifiedBadge(canvas, paint, badgeLeft + 10f * fontScale, badgeCy, 10.5f * fontScale, accent, surface);
                        } else {
                            drawScamOrFakeBadge(canvas, paint, badgeLeft, badgeCy, e.scam ? "SCAM" : "FAKE", accent, fontScale);
                        }
                    }
                    inner += nameHeight;
                    paint.setTypeface(Typeface.DEFAULT);
                }

                if (fwd != null) {
                    paint.setTypeface(Typeface.DEFAULT);
                    paint.setTextSize(20 * fontScale);
                    paint.setColor(accent);
                    Paint.FontMetrics ffm = paint.getFontMetrics();
                    final float fBase = inner + fwdHeight * 0.5f - (ffm.ascent + ffm.descent) * 0.5f;
                    final String drawnFwd = TextUtils.ellipsize(fwd, new TextPaint(paint),
                            Math.max(24f, e.width - innerPadX * 2), TextUtils.TruncateAt.END).toString();
                    canvas.drawText(drawnFwd, contentLeft, fBase, paint);
                    inner += fwdHeight;
                }

                if (e.replyBlockHeight > 0) {
                    final int replyRawColor = usePeerNameColors && e.replyPeerColor != 0 ? e.replyPeerColor : accent;
                    final int replyAccent = readableOn(replyRawColor, surface);
                    final RectF replyRect = new RectF(contentLeft, inner,
                            card.right - innerPadX, inner + e.replyBlockHeight);
                    drawReplyBlock(canvas, paint, replyRect, e.replyAuthor, e.replySnippet,
                            replyAccent, isOut ? outInk : inInk, cardDark, fontScale);
                    inner += e.replyBlockHeight + Math.round(8 * fontScale);
                }

                final boolean photoOverlayTime = !e.framelessSticker && time != null
                        && e.layout == null && e.imageHeight > 0 && !e.sticker && e.reactionsHeight == 0;
                RectF lastMediaRect = null;
                if (e.imageHeight > 0) {
                    final int mediaLeft = e.framelessSticker ? bubbleLeft : contentLeft;
                    final int availMediaW = e.framelessSticker ? e.width : Math.max(40, e.width - innerPadX * 2);
                    if (!e.albumEntries.isEmpty()) {
                        int cell = (availMediaW - 6) / 2;
                        for (int j = 0; j <= e.albumEntries.size(); j++) {
                            Entry tile = j == 0 ? e : e.albumEntries.get(j - 1);
                            Bitmap image = j == 0 ? e.media : e.albumImages.get(j - 1);
                            int x = mediaLeft + (j % 2) * (cell + 6);
                            int top = inner + (j / 2) * (cell + 6);
                            RectF bounds = new RectF(x, top, x + cell, top + cell);
                            lastMediaRect = bounds;
                            if (image != null) drawImage(canvas, paint, image, bounds, !tile.sticker, tile.sticker, surface, mediaRadius);
                            else drawMissingMedia(canvas, paint, bounds, tile.missingMediaLabel + " · " + tile.unavailableLabel, muted, mediaRadius);
                            if (image != null && tile.motion) drawPlayIcon(canvas, paint, bounds.centerX(), bounds.centerY());
                        }
                    } else {
                        final int mediaWidth = e.framelessSticker ? e.imageWidth : availMediaW;
                        RectF bounds = new RectF(mediaLeft, inner, mediaLeft + mediaWidth, inner + e.imageHeight);
                        lastMediaRect = bounds;
                        if (e.media != null) {
                            drawImage(canvas, paint, e.media, bounds, !e.sticker, e.sticker, surface, e.sticker ? 0f : mediaRadius);
                            if (e.motion) drawPlayIcon(canvas, paint, bounds.centerX(), bounds.centerY());
                        } else {
                            drawMissingMedia(canvas, paint, bounds, e.missingMediaLabel + " · " + e.unavailableLabel, muted, mediaRadius);
                        }
                    }
                    inner += e.imageHeight + (e.layout != null || e.reactionsHeight > 0 || (!photoOverlayTime && !e.framelessSticker && time != null)
                            ? Math.round(10 * fontScale) : 0);
                }

                if (e.layout != null) {
                    canvas.save();
                    canvas.translate(contentLeft, inner);
                    e.layout.draw(canvas);
                    canvas.restore();
                    inner += e.layout.getHeight();
                }

                if (e.reactionsHeight > 0) {
                    inner += Math.round(8 * fontScale);
                    drawReactionsBar(canvas, paint, e.reactions, e.framelessSticker ? bubbleLeft : contentLeft,
                            inner, e.reactionsHeight, Math.max(80f, e.width - (e.framelessSticker ? 0 : innerPadX * 2)),
                            accent, isOut ? outInk : inInk, cardDark, fontScale);
                    inner += e.reactionsHeight;
                }

                if (time != null) {
                    paint.setTypeface(Typeface.DEFAULT);
                    paint.setTextSize(18.5f * fontScale);
                    final float checksW = isOut ? 25f * fontScale : 0f;
                    final float timeTextW = paint.measureText(time);
                    if ((photoOverlayTime || (e.framelessSticker && e.media != null && e.reactionsHeight == 0)) && lastMediaRect != null) {
                        // Official Telegram translucent time pill at bottom-right of standalone photo/sticker
                        final float pillPadX = 10f * fontScale;
                        final float pillH = 26f * fontScale;
                        final float pillW = timeTextW + checksW + pillPadX * 2;
                        final RectF pill = new RectF(
                                lastMediaRect.right - 8f * fontScale - pillW,
                                lastMediaRect.bottom - 8f * fontScale - pillH,
                                lastMediaRect.right - 8f * fontScale,
                                lastMediaRect.bottom - 8f * fontScale);
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(0x6E000000);
                        canvas.drawRoundRect(pill, pillH * 0.5f, pillH * 0.5f, paint);
                        paint.setColor(Color.WHITE);
                        Paint.FontMetrics tfm = paint.getFontMetrics();
                        final float tBase = pill.centerY() - (tfm.ascent + tfm.descent) * 0.5f;
                        canvas.drawText(time, pill.left + pillPadX, tBase, paint);
                        if (isOut) {
                            drawOutgoingChecks(canvas, paint, pill.left + pillPadX + timeTextW + 5f * fontScale,
                                    pill.centerY(), Color.WHITE, e.outRead, fontScale);
                        }
                    } else {
                        paint.setColor(muted);
                        final float rightAnchor = card.right - innerPadX;
                        final float tx = Math.max(contentLeft, rightAnchor - timeTextW - checksW);
                        final float tBase;
                        if (e.timeInline && e.layout != null && e.layout.getLineCount() > 0) {
                            final int last = e.layout.getLineCount() - 1;
                            tBase = inner - e.layout.getHeight() + e.layout.getLineBaseline(last) + 2f * fontScale;
                        } else {
                            tBase = inner + 19f * fontScale;
                        }
                        canvas.drawText(time, tx, tBase, paint);
                        if (isOut) {
                            drawOutgoingChecks(canvas, paint, tx + timeTextW + 5f * fontScale,
                                    tBase - 6f * fontScale, readableOn(accent, surface), e.outRead, fontScale);
                        }
                    }
                }
                y += e.height;
            }
            if (PengramConfig.getBool(KEY_WATERMARK, false)) {
                drawWatermark(canvas, canvasWidth, total, PengramConfig.getBool(KEY_NAME, true) ? chatName : "",
                        entries.size(), transparentCanvas ? darkSurface : luminance(background) < 0.45);
                File logo = logoFile(activity);
                if (logo.isFile()) {
                    Bitmap mark = decode(logo, true);
                    if (mark != null) {
                        try {
                            int size = Math.max(16, Math.min(128, PengramConfig.getIntCached(KEY_LOGO_SIZE, 48)));
                            int width = Math.max(1, Math.round(size * mark.getWidth() / (float) Math.max(mark.getWidth(), mark.getHeight())));
                            int height = Math.max(1, Math.round(size * mark.getHeight() / (float) Math.max(mark.getWidth(), mark.getHeight())));
                            int position = Math.max(0, Math.min(5, PengramConfig.getIntCached(KEY_WATERMARK_POSITION, 0)));
                            int x = position == 1 || position == 3 ? 24 : Math.max(8, canvasWidth - 24 - width);
                            int top = position == 2 || position == 3 ? 20 : Math.max(8, total - 20 - height);
                            if (position == 4 || position == 5) { x = (canvasWidth - width) / 2; top = (total - height) / 2; }
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
            try {
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
            } catch (Throwable e) {
                file.delete();
                throw e;
            }
        } finally {
            bitmap.recycle();
        }
    }

    /** Renders solid, chat-wallpaper, or official Telegram diagonal gradient canvas backdrops. */
    private static void drawCanvasBackdrop(Canvas canvas, int bgType, int solidColor,
                                           int width, int height, QuotePalette palette, Paint paint) {
        if (bgType == 0) {
            canvas.drawColor(solidColor);
            if (palette.wallpaper != null && !palette.wallpaper.isRecycled()) {
                drawWallpaper(canvas, palette.wallpaper, width, height, paint);
            }
            return;
        }
        int[] grad = null;
        if (bgType == 3) { // Telegram Night
            grad = new int[]{0xFF0F172A, 0xFF1E293B, 0xFF0F2439};
        } else if (bgType == 4) { // Telegram Day
            grad = new int[]{0xFFD6E8FA, 0xFFEAF3E2, 0xFFD2E5F6};
        } else if (bgType == 5) { // Aurora
            grad = new int[]{0xFF1E1B4B, 0xFF312E81, 0xFF0F766E};
        } else if (bgType == 6) { // Warm Sunset
            grad = new int[]{0xFF2E1065, 0xFF831843, 0xFF9A3412};
        }
        if (grad != null) {
            Shader prev = paint.getShader();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, width, height, grad,
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(prev);
        } else {
            canvas.drawColor(solidColor);
        }
    }

    /** Builds an authentic Telegram chat bubble path with per-corner grouping radii and smooth Bézier tail. */
    private static void buildTelegramBubblePath(Path out, RectF c,
                                                float rTL, float rTR, float rBR, float rBL,
                                                boolean tailLeft, boolean tailRight, float tailW) {
        out.reset();
        final float l = c.left, t = c.top, r = c.right, b = c.bottom;
        if (!tailLeft && !tailRight) {
            out.addRoundRect(c, new float[]{rTL, rTL, rTR, rTR, rBR, rBR, rBL, rBL}, Path.Direction.CW);
            return;
        }
        final float tailStemH = Math.min(26f, c.height() * 0.46f);
        out.moveTo(l + rTL, t);
        // Top edge + top-right corner
        out.lineTo(r - rTR, t);
        if (rTR > 0) out.arcTo(new RectF(r - rTR * 2, t, r, t + rTR * 2), 270f, 90f, false);
        // Right edge
        if (tailRight) {
            out.lineTo(r, b - tailStemH);
            out.cubicTo(r, b - tailStemH * 0.36f, r + tailW * 0.30f, b - 1.5f, r + tailW, b);
            out.cubicTo(r + tailW * 0.35f, b + 0.5f, r - 3f, b, r - Math.min(18f, c.width() * 0.25f), b);
        } else {
            out.lineTo(r, b - rBR);
            if (rBR > 0) out.arcTo(new RectF(r - rBR * 2, b - rBR * 2, r, b), 0f, 90f, false);
        }
        // Bottom edge
        if (tailLeft) {
            out.lineTo(l + Math.min(18f, c.width() * 0.25f), b);
            out.cubicTo(l + 3f, b, l - tailW * 0.35f, b + 0.5f, l - tailW, b);
            out.cubicTo(l - tailW * 0.30f, b - 1.5f, l, b - tailStemH * 0.36f, l, b - tailStemH);
        } else {
            out.lineTo(l + rBL, b);
            if (rBL > 0) out.arcTo(new RectF(l, b - rBL * 2, l + rBL * 2, b), 90f, 90f, false);
        }
        // Left edge + top-left corner
        out.lineTo(l, t + rTL);
        if (rTL > 0) out.arcTo(new RectF(l, t, l + rTL * 2, t + rTL * 2), 180f, 90f, false);
        out.close();
    }

    /** Soft elevation following the exact bubble & tail silhouette without CPU BlurMaskFilter. */
    private static void drawBubbleShadow(Canvas canvas, Path bubblePath, RectF card, float radius, boolean dark, Paint paint) {
        final Paint.Style oldStyle = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        final float[] offsetsY = {1.2f, 2.6f, 4.4f};
        final int[] alphas = dark ? new int[]{24, 15, 8} : new int[]{15, 9, 5};
        for (int i = 0; i < offsetsY.length; i++) {
            canvas.save();
            canvas.translate(0f, offsetsY[i]);
            paint.setAlpha(alphas[i]);
            canvas.drawPath(bubblePath, paint);
            canvas.restore();
        }
        paint.setAlpha(255);
        paint.setStyle(oldStyle);
    }

    private static void drawBubbleBorder(Canvas canvas, Path bubblePath, boolean dark, Paint paint) {
        final Paint.Style oldStyle = paint.getStyle();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.25f);
        paint.setColor(dark ? 0x1AFFFFFF : 0x12000000);
        canvas.drawPath(bubblePath, paint);
        paint.setStyle(oldStyle);
    }

    /** Draws either the sender's real profile photo or an authentic Telegram 2-tone gradient initials avatar. */
    private static void drawSenderAvatar(Canvas canvas, Paint paint, Entry e,
                                         float cx, float cy, float size, boolean rounded,
                                         int fallbackAccent, boolean darkBackdrop, float fontScale) {
        final float half = size * 0.5f;
        final RectF faceRect = new RectF(cx - half, cy - half, cx + half, cy + half);
        final float cornerR = rounded ? Math.max(6f, size * 0.30f) : half;
        Bitmap face = e.avatar != null && !e.avatar.isRecycled() ? e.avatar
                : e.avatarFile != null ? decode(e.avatarFile, true) : null;
        if (face != null) {
            canvas.save();
            Path clip = new Path();
            if (rounded) clip.addRoundRect(faceRect, cornerR, cornerR, Path.Direction.CW);
            else clip.addCircle(cx, cy, half, Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            final int side = Math.min(face.getWidth(), face.getHeight());
            final int cropX = (face.getWidth() - side) / 2;
            final int cropY = (face.getHeight() - side) / 2;
            canvas.drawBitmap(face, new Rect(cropX, cropY, cropX + side, cropY + side), faceRect, paint);
            canvas.restore();
            if (face != e.avatar) face.recycle();
        } else {
            final int[] pair = PEER_AVATAR_GRADIENTS[Math.abs(e.peerColorIndex) % PEER_AVATAR_GRADIENTS.length];
            Shader prevShader = paint.getShader();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(faceRect.left, faceRect.top, faceRect.left, faceRect.bottom,
                    pair[0], pair[1], Shader.TileMode.CLAMP));
            if (rounded) canvas.drawRoundRect(faceRect, cornerR, cornerR, paint);
            else canvas.drawCircle(cx, cy, half, paint);
            paint.setShader(prevShader);

            final String initials = !TextUtils.isEmpty(e.initials) ? e.initials
                    : (!TextUtils.isEmpty(e.name) ? e.name.substring(0, Character.charCount(e.name.codePointAt(0))).toUpperCase(Locale.getDefault()) : "?");
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(size * (initials.length() > 1 ? 0.40f : 0.46f));
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(initials, cx, cy - (fm.ascent + fm.descent) * 0.5f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTypeface(Typeface.DEFAULT);
        }
        // Subtle crisp outline ring
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.3f * fontScale));
        paint.setColor(darkBackdrop ? 0x26FFFFFF : 0x1A000000);
        if (rounded) canvas.drawRoundRect(faceRect, cornerR, cornerR, paint);
        else canvas.drawCircle(cx, cy, Math.max(0f, half - 0.65f * fontScale), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Builds rich SpannableStringBuilder with Telegram MessageEntity formatting + preloaded Apple EmojiSpans. */
    private static CharSequence buildRichQuoteText(String rawText, ArrayList<TLRPC.MessageEntity> entities,
                                                   boolean hideMentions, TextPaint bodyPaint,
                                                   int linkColor, int bubbleSurface) {
        if (TextUtils.isEmpty(rawText)) return "";
        SpannableStringBuilder ssb = new SpannableStringBuilder(rawText);
        final int len = ssb.length();
        final boolean darkCard = luminance(bubbleSurface) < 0.45;
        final int codeBg = (linkColor & 0x00FFFFFF) | (darkCard ? 0x26000000 : 0x1A000000);
        final int spoilerBg = darkCard ? 0x38FFFFFF : 0x22000000;

        if (entities != null && !entities.isEmpty()) {
            for (TLRPC.MessageEntity entity : entities) {
                if (entity == null) continue;
                final int s = Math.max(0, Math.min(len, entity.offset));
                final int e = Math.max(s, Math.min(len, entity.offset + entity.length));
                if (e <= s) continue;
                final int flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;
                if (entity instanceof TLRPC.TL_messageEntityBold) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), s, e, flags);
                } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
                    ssb.setSpan(new StyleSpan(Typeface.ITALIC), s, e, flags);
                } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
                    ssb.setSpan(new UnderlineSpan(), s, e, flags);
                } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
                    ssb.setSpan(new StrikethroughSpan(), s, e, flags);
                } else if (entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre) {
                    ssb.setSpan(new TypefaceSpan("monospace"), s, e, flags);
                    ssb.setSpan(new ForegroundColorSpan(linkColor), s, e, flags);
                    ssb.setSpan(new BackgroundColorSpan(codeBg), s, e, flags);
                } else if (entity instanceof TLRPC.TL_messageEntityUrl
                        || entity instanceof TLRPC.TL_messageEntityTextUrl
                        || entity instanceof TLRPC.TL_messageEntityMention
                        || entity instanceof TLRPC.TL_messageEntityMentionName
                        || entity instanceof TLRPC.TL_messageEntityHashtag
                        || entity instanceof TLRPC.TL_messageEntityCashtag
                        || entity instanceof TLRPC.TL_messageEntityBotCommand
                        || entity instanceof TLRPC.TL_messageEntityEmail
                        || entity instanceof TLRPC.TL_messageEntityPhone) {
                    ssb.setSpan(new ForegroundColorSpan(linkColor), s, e, flags);
                } else if (entity instanceof TLRPC.TL_messageEntitySpoiler) {
                    ssb.setSpan(new BackgroundColorSpan(spoilerBg), s, e, flags);
                }
            }
        }

        if (hideMentions) {
            maskMentionsInPlace(ssb);
        }

        // Attach Telegram Apple EmojiSpans ONLY when their atlas sheet is loaded (preloading briefly if needed),
        // guaranteeing crisp Telegram emojis with zero risk of gray placeholder circles!
        try {
            ArrayList<Emoji.EmojiSpanRange> ranges = Emoji.parseEmojis(ssb);
            if (ranges != null && !ranges.isEmpty()) {
                boolean anyUnloaded = false;
                for (Emoji.EmojiSpanRange r : ranges) {
                    Emoji.EmojiDrawable d = Emoji.getEmojiDrawable(r.code);
                    if (d != null && !d.isLoaded()) {
                        d.preload();
                        anyUnloaded = true;
                    }
                }
                if (anyUnloaded) {
                    long deadline = System.currentTimeMillis() + 95L;
                    while (System.currentTimeMillis() < deadline) {
                        boolean allReady = true;
                        for (Emoji.EmojiSpanRange r : ranges) {
                            Emoji.EmojiDrawable d = Emoji.getEmojiDrawable(r.code);
                            if (d != null && !d.isLoaded()) {
                                allReady = false;
                                break;
                            }
                        }
                        if (allReady) break;
                        try { Thread.sleep(12L); } catch (InterruptedException ie) { break; }
                    }
                }
                Paint.FontMetricsInt fmi = bodyPaint.getFontMetricsInt();
                for (Emoji.EmojiSpanRange r : ranges) {
                    if (r.start < 0 || r.end > ssb.length() || r.end <= r.start) continue;
                    Emoji.EmojiDrawable d = Emoji.getEmojiDrawable(r.code);
                    if (d != null && d.isLoaded()) {
                        ssb.setSpan(new Emoji.EmojiSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM, fmi),
                                r.start, r.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return ssb;
    }

    private static boolean isAsciiUsernameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static void maskMentionsInPlace(SpannableStringBuilder ssb) {
        int i = 0;
        while (i < ssb.length()) {
            if (ssb.charAt(i) == '@' && (i == 0 || (!isAsciiUsernameChar(ssb.charAt(i - 1)) && ssb.charAt(i - 1) != '@'))) {
                int j = i + 1;
                while (j < ssb.length() && isAsciiUsernameChar(ssb.charAt(j)) && (j - i - 1) < 32) {
                    j++;
                }
                int unameLen = j - i - 1;
                if (unameLen >= 3) {
                    ssb.replace(i, j, "@•••");
                    i += 4;
                    continue;
                }
            }
            i++;
        }
    }

    /** Authentic Telegram Reply-to tinted box with vertical accent bar, bold author, and 1-line snippet. */
    private static void drawReplyBlock(Canvas canvas, Paint paint, RectF rect,
                                       String author, String snippet,
                                       int replyAccent, int bodyInk, boolean darkCard, float fontScale) {
        final Paint.Style prevStyle = paint.getStyle();
        final float r = 10f * fontScale;
        final float barW = Math.max(3f, 4f * fontScale);

        // Tinted translucent container
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((replyAccent & 0x00FFFFFF) | (darkCard ? 0x26000000 : 0x1A000000));
        canvas.drawRoundRect(rect, r, r, paint);

        // Left vertical accent bar clipped cleanly inside rounded corners
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.clipPath(clip);
        paint.setColor(replyAccent);
        canvas.drawRect(rect.left, rect.top, rect.left + barW, rect.bottom, paint);
        canvas.restore();

        final float textLeft = rect.left + barW + 10f * fontScale;
        final float maxW = Math.max(20f, rect.right - textLeft - 10f * fontScale);

        if (!TextUtils.isEmpty(author)) {
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(20.5f * fontScale);
            paint.setColor(replyAccent);
            String ellipAuthor = TextUtils.ellipsize(author, new TextPaint(paint), maxW, TextUtils.TruncateAt.END).toString();
            canvas.drawText(ellipAuthor, textLeft, rect.top + 25f * fontScale, paint);
        }
        if (!TextUtils.isEmpty(snippet)) {
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(19.5f * fontScale);
            paint.setColor(bodyInk);
            paint.setAlpha(215);
            String ellipSnip = TextUtils.ellipsize(snippet, new TextPaint(paint), maxW, TextUtils.TruncateAt.END).toString();
            canvas.drawText(ellipSnip, textLeft, rect.top + 50f * fontScale, paint);
            paint.setAlpha(255);
        }
        paint.setTypeface(Typeface.DEFAULT);
        paint.setStyle(prevStyle);
    }

    /** Authentic Telegram scalloped Verified badge + inner checkmark. */
    private static void drawVerifiedBadge(Canvas canvas, Paint paint, float cx, float cy, float r, int accent, int surface) {
        final Paint.Style prev = paint.getStyle();
        Path star = new Path();
        final int points = 16;
        for (int i = 0; i < points; i++) {
            double ang = (Math.PI * 2.0 * i) / points;
            float rad = (i % 2 == 0) ? r : r * 0.80f;
            float px = cx + (float) Math.cos(ang) * rad;
            float py = cy + (float) Math.sin(ang) * rad;
            if (i == 0) star.moveTo(px, py);
            else star.lineTo(px, py);
        }
        star.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        canvas.drawPath(star, paint);

        Path check = new Path();
        check.moveTo(cx - r * 0.36f, cy + r * 0.02f);
        check.lineTo(cx - r * 0.08f, cy + r * 0.30f);
        check.lineTo(cx + r * 0.40f, cy - r * 0.24f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(1.8f, r * 0.22f));
        paint.setColor(luminance(accent) > 0.6 ? Color.BLACK : Color.WHITE);
        canvas.drawPath(check, paint);
        paint.setStyle(prev);
    }

    private static void drawScamOrFakeBadge(Canvas canvas, Paint paint, float left, float cy, String tag, int accent, float fontScale) {
        final Paint.Style prev = paint.getStyle();
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(15.5f * fontScale);
        final float tw = paint.measureText(tag);
        final float padX = 6f * fontScale;
        final float h = 21f * fontScale;
        final RectF pill = new RectF(left, cy - h * 0.5f, left + tw + padX * 2, cy + h * 0.5f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.4f, 1.6f * fontScale));
        paint.setColor(0xFFEF4444);
        canvas.drawRoundRect(pill, 5f * fontScale, 5f * fontScale, paint);
        paint.setStyle(Paint.Style.FILL);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(tag, left + padX, cy - (fm.ascent + fm.descent) * 0.5f, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setStyle(prev);
    }

    private static String formatReactionCount(int count) {
        if (count >= 1_000_000) return String.format(Locale.US, "%.1fM", count / 1_000_000f);
        if (count >= 10_000) return (count / 1000) + "K";
        if (count >= 1_000) return String.format(Locale.US, "%.1fK", count / 1000f);
        return String.valueOf(count);
    }

    /** Authentic Telegram rounded reaction pills (with Apple EmojiDrawable if loaded + formatted counter). */
    private static void drawReactionsBar(Canvas canvas, Paint paint, ArrayList<ReactionChip> reactions,
                                         float startX, float top, int rowH, float maxWidth,
                                         int accent, int bodyInk, boolean darkCard, float fontScale) {
        final Paint.Style prev = paint.getStyle();
        float x = startX;
        final float pillH = Math.min(rowH, 38f * fontScale);
        final float cy = top + pillH * 0.5f;
        final float emojiSize = 23f * fontScale;

        for (ReactionChip rc : reactions) {
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(18.5f * fontScale);
            final String cnt = formatReactionCount(rc.count);
            final float cntW = paint.measureText(cnt);
            final float padX = 10f * fontScale;
            final float pillW = padX + emojiSize + 6f * fontScale + cntW + padX;
            if (x > startX && x + pillW > startX + maxWidth) {
                break;
            }
            final RectF pill = new RectF(x, top, x + pillW, top + pillH);
            paint.setStyle(Paint.Style.FILL);
            if (rc.paid) {
                paint.setColor(darkCard ? 0x38F59E0B : 0x26F59E0B);
            } else if (rc.chosen) {
                paint.setColor((accent & 0x00FFFFFF) | (darkCard ? 0x42000000 : 0x29000000));
            } else {
                paint.setColor((accent & 0x00FFFFFF) | (darkCard ? 0x22000000 : 0x16000000));
            }
            canvas.drawRoundRect(pill, pillH * 0.5f, pillH * 0.5f, paint);

            Emoji.EmojiDrawable ed = Emoji.getEmojiDrawable(rc.emoticon);
            if (ed != null && !ed.isLoaded()) ed.preload();
            final float eLeft = x + padX;
            if (ed != null && ed.isLoaded()) {
                Rect prevB = new Rect(ed.getBounds());
                ed.setBounds(Math.round(eLeft), Math.round(cy - emojiSize * 0.5f),
                        Math.round(eLeft + emojiSize), Math.round(cy + emojiSize * 0.5f));
                ed.setAlpha(255);
                ed.draw(canvas);
                ed.setBounds(prevB);
            } else {
                paint.setTextSize(20f * fontScale);
                paint.setColor(bodyInk);
                Paint.FontMetrics efm = paint.getFontMetrics();
                canvas.drawText(rc.emoticon, eLeft, cy - (efm.ascent + efm.descent) * 0.5f, paint);
            }

            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(18.5f * fontScale);
            paint.setColor(rc.paid ? (darkCard ? 0xFFFBBF24 : 0xFFD97706) : rc.chosen ? accent : bodyInk);
            if (!rc.chosen && !rc.paid) paint.setAlpha(210);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(cnt, eLeft + emojiSize + 6f * fontScale, cy - (fm.ascent + fm.descent) * 0.5f, paint);
            paint.setAlpha(255);

            x += pillW + 7f * fontScale;
        }
        paint.setTypeface(Typeface.DEFAULT);
        paint.setStyle(prev);
    }

    /** Crisp vector outgoing single/double checkmarks next to message timestamp. */
    private static void drawOutgoingChecks(Canvas canvas, Paint paint, float left, float cy,
                                           int color, boolean read, float fontScale) {
        final Paint.Style prev = paint.getStyle();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(1.8f, 2.0f * fontScale));
        paint.setColor(color);

        Path c1 = new Path();
        c1.moveTo(left, cy + 0.5f * fontScale);
        c1.lineTo(left + 4.5f * fontScale, cy + 5f * fontScale);
        c1.lineTo(left + 13f * fontScale, cy - 4.5f * fontScale);
        canvas.drawPath(c1, paint);

        if (read) {
            Path c2 = new Path();
            float shift = 6.5f * fontScale;
            c2.moveTo(left + shift + 1.5f * fontScale, cy + 2f * fontScale);
            c2.lineTo(left + shift + 4.5f * fontScale, cy + 5f * fontScale);
            c2.lineTo(left + shift + 13f * fontScale, cy - 4.5f * fontScale);
            canvas.drawPath(c2, paint);
        }
        paint.setStyle(prev);
    }

    /** Soft elevation for a card: several translucent layers, no blur filter. */
    private static void drawCardShadow(Canvas canvas, RectF card, float radius, boolean dark, Paint paint) {
        final float step = 1.7f;
        final int layers = 5;
        final Paint.Style oldStyle = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        for (int i = layers; i >= 1; i--) {
            final float grow = step * i;
            final RectF shadow = new RectF(card.left - grow, card.top - grow + grow * 0.55f,
                    card.right + grow, card.bottom + grow + grow * 0.55f);
            paint.setColor(0xFF000000);
            paint.setAlpha(dark ? 16 : 10);
            canvas.drawRoundRect(shadow, radius + grow, radius + grow, paint);
        }
        paint.setAlpha(255);
        paint.setStyle(oldStyle);
    }

    /** A hairline stops a white card from melting into a light background. */
    private static void drawCardBorder(Canvas canvas, RectF card, float radius, boolean dark, Paint paint) {
        final Paint.Style oldStyle = paint.getStyle();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(dark ? 0x1FFFFFFF : 0x14000000);
        final float inset = 0.75f;
        canvas.drawRoundRect(new RectF(card.left + inset, card.top + inset,
                card.right - inset, card.bottom - inset), radius, radius, paint);
        paint.setStyle(oldStyle);
    }

    /** Relative luminance of an sRGB color (WCAG 2.1). */
    private static double luminance(int color) {
        final double[] channel = new double[3];
        final int[] raw = {Color.red(color), Color.green(color), Color.blue(color)};
        for (int i = 0; i < 3; i++) {
            final double c = raw[i] / 255.0;
            channel[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return channel[0] * 0.2126 + channel[1] * 0.7152 + channel[2] * 0.0722;
    }

    private static double contrast(int a, int b) {
        final double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Blend towards white or black until the text is readable on the card. */
    private static int readableOn(int color, int surface) {
        if (contrast(color, surface) >= 3.0) {
            return color;
        }
        final boolean dark = luminance(surface) < 0.5;
        for (int i = 1; i <= 10; i++) {
            final float ratio = i * 0.1f;
            final int blended = Color.argb(Color.alpha(color),
                    Math.round(Color.red(color) + ((dark ? 255 : 0) - Color.red(color)) * ratio),
                    Math.round(Color.green(color) + ((dark ? 255 : 0) - Color.green(color)) * ratio),
                    Math.round(Color.blue(color) + ((dark ? 255 : 0) - Color.blue(color)) * ratio));
            if (contrast(blended, surface) >= 3.0) {
                return blended;
            }
        }
        return dark ? Color.WHITE : Color.BLACK;
    }

    private static void drawPlayIcon(Canvas canvas, Paint paint, float cx, float cy) {
        // A drawn triangle instead of a font glyph: the same shape on every device.
        final Paint.Style oldStyle = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x40000000);
        canvas.drawCircle(cx, cy, 26, paint);
        paint.setColor(0xA6000000);
        canvas.drawCircle(cx, cy, 21, paint);
        paint.setColor(Color.WHITE);
        final android.graphics.Path triangle = new android.graphics.Path();
        triangle.moveTo(cx - 6, cy - 9);
        triangle.lineTo(cx + 10, cy);
        triangle.lineTo(cx - 6, cy + 9);
        triangle.close();
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(triangle, paint);
        paint.setStyle(oldStyle);
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    private static void drawMissingMedia(Canvas canvas, Paint paint, RectF dest, String label, int muted, float radius) {
        final Paint.Style oldStyle = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(muted);
        paint.setAlpha(26);
        canvas.drawRoundRect(dest, radius, radius, paint);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setAlpha(70);
        canvas.drawRoundRect(new RectF(dest.left + 1, dest.top + 1, dest.right - 1, dest.bottom - 1),
                Math.max(0, radius - 1), Math.max(0, radius - 1), paint);
        paint.setStyle(Paint.Style.FILL);
        // A tiny picture glyph, so the empty tile reads as "media missing".
        final float gx = dest.centerX();
        final float gy = dest.centerY() - radius * 1.15f - 6;
        paint.setAlpha(90);
        canvas.drawRoundRect(gx - 21, gy - 15, gx + 21, gy + 15, 6, 6, paint);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setAlpha(150);
        canvas.drawCircle(gx - 9, gy - 6, 3.5f, paint);
        canvas.drawPath(mediaGlyph(new android.graphics.Path(), gx, gy), paint);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(muted);
        paint.setTextSize(21);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(TextUtils.ellipsize(label, new TextPaint(paint),
                Math.max(12, dest.width() - 24), TextUtils.TruncateAt.END).toString(),
                dest.centerX(), gy + 34, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setStyle(oldStyle);
    }

    private static android.graphics.Path mediaGlyph(android.graphics.Path path, float cx, float cy) {
        path.reset();
        path.moveTo(cx - 15, cy + 9);
        path.lineTo(cx - 4, cy - 2);
        path.lineTo(cx + 3, cy + 5);
        path.lineTo(cx + 8, cy);
        path.lineTo(cx + 15, cy + 9);
        return path;
    }

    private static void drawImage(Canvas canvas, Paint paint, Bitmap media, RectF dest,
                                  boolean crop, boolean sticker, int surface, float radius) {
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(dest, radius, radius, android.graphics.Path.Direction.CW);
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

    private static void drawWatermark(Canvas canvas, int canvasWidth, int total, String chatName, int count, boolean dark) {
        String text = PengramConfig.getQuoteWatermarkText();
        if (TextUtils.isEmpty(text)) return;
        text = text.replace("{date}", new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()))
                .replace("{chat}", chatName == null ? "" : chatName)
                .replace("{count}", String.valueOf(count));
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(19);
        int alpha = Math.max(10, Math.min(100, PengramConfig.getIntCached(KEY_WATERMARK_OPACITY, 72)));
        p.setColor((Math.round(alpha * 2.55f) << 24) | (dark ? 0x00ffffff : 0x00203040));
        String label = TextUtils.ellipsize(text, p, Math.max(80, canvasWidth - 64), TextUtils.TruncateAt.END).toString();
        float measured = p.measureText(label);
        final int position = Math.max(0, Math.min(5, PengramConfig.getIntCached(KEY_WATERMARK_POSITION, 0)));
        if (position == 5) {
            int drawn = 0;
            for (int y = 48; y < total - 20 && drawn < 400; y += 130) {
                for (int x = -80; x < canvasWidth - 24 && drawn < 400; x += (int) Math.max(160, measured + 48)) {
                    canvas.save();
                    canvas.rotate(-20, x, y);
                    canvas.drawText(label, x, y, p);
                    canvas.restore();
                    drawn++;
                }
            }
            return;
        }
        float x = position == 1 || position == 3 ? 24 : canvasWidth - 24 - measured;
        float y = position == 2 || position == 3 ? 52 : total - 22;
        if (position == 4) {
            x = (canvasWidth - measured) / 2f;
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
            if (resId == R.string.PengramQuoteCopied) return ru ? "Цитата скопирована в буфер обмена" : "Quote copied to clipboard";
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
            if (resId == R.string.PengramQuoteZoomHint) return ru ? "Нажмите, чтобы увеличить" : "Tap to zoom";
            if (resId == R.string.PengramQuoteQuickSettings) return ru ? "Оформление цитаты" : "Customize quote";
            if (resId == R.string.PengramQuoteCount) return ru ? "Сообщений: %1$d" : "Messages: %1$d";
            if (resId == R.string.PengramQuoteUpdating) return ru ? "Обновляем предпросмотр…" : "Updating preview…";
            if (resId == R.string.PengramQuoteUpdateFailed) return ru ? "Снимок не обновился · попробуйте ещё раз" : "Preview unchanged · try again";
            if (resId == R.string.Cancel) return ru ? "Отмена" : "Cancel";
            if (resId == R.string.OK) return ru ? "ОК" : "OK";
            if (resId == R.string.PengramQuoteCannotSend) return ru ? "В этот чат сейчас нельзя отправить фото." : "This chat is not available for sending photos.";
            if (resId == R.string.PengramQuoteShareError) return ru ? "Не удалось поделиться изображением." : "Could not share the image.";
            if (resId == R.string.PengramQuoteCopyError) return ru ? "Не удалось скопировать изображение." : "Could not copy the image.";
            if (resId == R.string.PengramQuoteStickerError) return ru ? "Не удалось создать стикер. Выберите меньше сообщений или снизьте размер изображения." : "Could not create a sticker. Select fewer messages or reduce the image size.";
            if (resId == R.string.PengramQuoteSaveError) return ru ? "Не удалось сохранить цитату в галерею." : "Could not save the quote to the gallery.";
            if (resId == R.string.PengramQuoteRestricted) return ru ? "Для защищённого содержимого создание цитат недоступно." : "Quotes are unavailable for protected content.";
            if (resId == R.string.PengramQuoteBubbleTail) return ru ? "Хвостик облачка" : "Bubble tail";
            if (resId == R.string.PengramQuotePeerNameColor) return ru ? "Цвета имён TG" : "TG peer colors";
            if (resId == R.string.PengramQuoteShowReply) return ru ? "Блок ответа" : "Reply header";
            if (resId == R.string.PengramQuoteShowForward) return ru ? "Переслано от" : "Forwarded from";
            if (resId == R.string.PengramQuoteShowBadges) return ru ? "Галочки и метки" : "Badges";
            if (resId == R.string.PengramQuoteShowReactions) return ru ? "Реакции" : "Reactions";
            if (resId == R.string.PengramQuoteAlignOutgoing) return ru ? "Свои справа" : "Outgoing right";
            if (resId == R.string.PengramQuoteDateInTime) return ru ? "Дата во времени" : "Date in time";
            if (resId == R.string.PengramQuoteAvatarLayout) return ru ? "Аватар сбоку" : "Side avatar";
            if (resId == R.string.PengramQuoteStudioPresets) return ru ? "Палитра и фон" : "Palette & background";
            if (resId == R.string.PengramQuoteStudioGeometry) return ru ? "Форма и поля" : "Corners & margins";
            if (resId == R.string.PengramQuoteOpenSettings) return ru ? "Все настройки цитат" : "All quote settings";
            return ru ? "Цитата" : "Quote";
        }
    }

    /** Subtle checkerboard stage background when previewing a Transparent PNG quote. */
    private static Drawable createPreviewStageDrawable(Theme.ResourcesProvider provider, boolean checkerboard) {
        final int radius = AndroidUtilities.dp(18);
        final int baseColor = Theme.getColor(Theme.key_windowBackgroundGray, provider);
        if (!checkerboard) {
            return Theme.createRoundRectDrawable(radius, baseColor);
        }
        final boolean isDark = luminance(baseColor) < 0.35;
        final int cellA = isDark ? 0xFF1A202C : 0xFFEAEFF6;
        final int cellB = isDark ? 0xFF232B3A : 0xFFDCE4EE;
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path clip = new Path();
            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                clip.reset();
                clip.addRoundRect(new RectF(b), radius, radius, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                p.setColor(cellA);
                canvas.drawRect(b, p);
                p.setColor(cellB);
                int step = AndroidUtilities.dp(12);
                for (int y = b.top, row = 0; y < b.bottom; y += step, row++) {
                    for (int x = b.left + (row % 2) * step; x < b.right; x += step * 2) {
                        canvas.drawRect(x, y, Math.min(b.right, x + step), Math.min(b.bottom, y + step), p);
                    }
                }
                canvas.restore();
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private static void styleAction(TextView button, Theme.ResourcesProvider provider, boolean primary) {
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton, provider);
        if (accent == 0) accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, provider);
        int neutral = Theme.getColor(Theme.key_dialogBackgroundGray, provider);
        int background = primary ? accent : neutral;
        int pressed = Theme.blendOver(background, 0x1A000000);
        int lightness = Color.red(background) * 299 + Color.green(background) * 587 + Color.blue(background) * 114;
        button.setTextColor(primary ? (lightness < 165000 ? Color.WHITE : Color.BLACK)
                : Theme.getColor(Theme.key_dialogTextBlack, provider));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12),
                background, pressed));
    }

    private static void styleChip(TextView chip, Theme.ResourcesProvider provider, boolean enabled) {
        int base = Theme.getColor(Theme.key_dialogBackgroundGray, provider);
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, provider);
        int background = enabled ? Theme.blendOver(base, (accent & 0x00ffffff) | 0x22000000) : base;
        chip.setTextColor(enabled ? accent : Theme.getColor(Theme.key_dialogTextBlack, provider));
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16),
                background, Theme.blendOver(background, 0x18000000)));
    }

    private static TextView action(Activity activity, Theme.ResourcesProvider provider,
                                   int title, boolean primary, Runnable callback) {
        TextView button = new TextView(activity);
        button.setText(safeString(activity, title));
        button.setGravity(Gravity.CENTER);
        button.setTextSize(13.5f);
        button.setTypeface(AndroidUtilities.bold());
        button.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9),
                AndroidUtilities.dp(12), AndroidUtilities.dp(9));
        button.setMinHeight(AndroidUtilities.dp(42));
        styleAction(button, provider, primary);
        button.setOnClickListener(v -> callback.run());
        return button;
    }

    private static TextView studioChip(Activity activity, Theme.ResourcesProvider provider,
                                       CharSequence label, boolean active, View.OnClickListener click) {
        TextView chip = new TextView(activity);
        chip.setText(label);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(13);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setSingleLine(true);
        chip.setMaxWidth(AndroidUtilities.dp(230));
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(7),
                AndroidUtilities.dp(13), AndroidUtilities.dp(7));
        chip.setMinHeight(AndroidUtilities.dp(34));
        styleChip(chip, provider, active);
        chip.setOnClickListener(click);
        return chip;
    }

    /** Re-render in place; stale chip taps never replace a newer preview. */
    private static void refreshPreview(ChatActivity chat, Result current, ImageView image,
                                       FrameLayout stageView,
                                       ArrayList<Entry> entries, ArrayList<File> paths,
                                       ArrayList<MessageObject> selected, DialogInterface[] dialog,
                                       java.util.concurrent.atomic.AtomicInteger version,
                                       java.util.concurrent.atomic.AtomicBoolean rendering, TextView status) {
        if (!ensureAllowed(chat, selected)) {
            if (dialog[0] != null) dialog[0].dismiss();
            return;
        }
        final int expected = version.incrementAndGet();
        rendering.set(true);
        status.setText(safeString(chat.getParentActivity(), R.string.PengramQuoteUpdating));
        Activity activity = chat.getParentActivity();
        final int bgMode = PengramConfig.getIntCached(KEY_BACKGROUND, 0);
        if (stageView != null) {
            stageView.setBackground(createPreviewStageDrawable(chat.getResourceProvider(), bgMode == 1));
        }
        final QuotePalette palette = capturePalette(chat, bgMode == 0);
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(6, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final boolean jpeg = PengramConfig.getBool(KEY_JPEG, false) && bgMode != 1;
        final String chatName = chat.getCurrentChat() != null ? chat.getCurrentChat().title
                : chat.getCurrentUser() != null ? UserObject.getUserName(chat.getCurrentUser()) : "";
        image.animate().cancel();
        image.setAlpha(.58f);
        Utilities.globalQueue.postRunnable(() -> {
            Result updated = null;
            try {
                if (version.get() == expected) {
                    updated = render(activity, entries, paths, dark, style, jpeg, false, palette, chatName);
                }
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
                if (version.get() != expected || !active(chat.getParentActivity())) {
                    discard(ready);
                    return;
                }
                if (!ensureAllowed(chat, selected)) {
                    discard(ready);
                    if (dialog[0] != null) dialog[0].dismiss();
                    return;
                }
                rendering.set(false);
                if (ready == null) {
                    image.setAlpha(1f);
                    status.setText(safeString(activity, R.string.PengramQuoteUpdateFailed));
                    error(chat, R.string.PengramQuoteRenderError);
                    return;
                }
                status.setText(String.format(Locale.getDefault(), safeString(activity, R.string.PengramQuoteCount),
                        selected.size()) + " · " + (ready.jpeg ? "JPEG" : "PNG"));
                Bitmap old = current.preview;
                File oldFile = current.file;
                boolean oldPublished = current.published;
                current.file = ready.file;
                current.preview = ready.preview;
                current.jpeg = ready.jpeg;
                current.published = false;
                image.setImageBitmap(ready.preview);
                image.animate().alpha(1f).setDuration(150).start();
                // No stale frame or orphaned private image after rapid chip taps.
                if (old != null && old != ready.preview && !old.isRecycled()) old.recycle();
                if (!oldPublished && oldFile != null && !oldFile.equals(ready.file)) oldFile.delete();
            });
        });
    }

    private static boolean readyForAction(ChatActivity chat, ArrayList<MessageObject> selected,
                                          java.util.concurrent.atomic.AtomicBoolean rendering) {
        if (!ensureAllowed(chat, selected)) return false;
        if (rendering.get()) {
            error(chat, R.string.PengramQuoteUpdating);
            return false;
        }
        return true;
    }

    /** Official-Telegram-grade BottomSheet Live Quote Studio. */
    private static void preview(ChatActivity chat, Result result,
                                ArrayList<MessageObject> selected, ArrayList<Entry> entries, ArrayList<File> paths) {
        final Activity activity = chat.getParentActivity();
        if (!active(activity)) {
            discard(result);
            return;
        }
        final Theme.ResourcesProvider provider = chat.getResourceProvider();
        final DialogInterface[] ref = new DialogInterface[1];
        final java.util.concurrent.atomic.AtomicBoolean rendering = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger previewVersion = new java.util.concurrent.atomic.AtomicInteger();

        final int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, provider);
        final int muted = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, provider);
        final int titleColor = Theme.getColor(Theme.key_dialogTextBlack, provider);

        ScrollView outer = new ScrollView(activity);
        outer.setFillViewport(false);
        outer.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10),
                AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        outer.addView(root, new ScrollView.LayoutParams(-1, -2));

        // 1. Header bar: Title + Live status pill + Settings shortcut
        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(safeString(activity, R.string.PengramQuotePreview));
        title.setTextSize(19);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(titleColor);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView countLabel = new TextView(activity);
        countLabel.setText(String.format(Locale.getDefault(), safeString(activity, R.string.PengramQuoteCount),
                selected.size()) + " · " + (result.jpeg ? "JPEG" : "PNG"));
        countLabel.setTextSize(12);
        countLabel.setSingleLine(true);
        countLabel.setMaxWidth(AndroidUtilities.dp(170));
        countLabel.setEllipsize(TextUtils.TruncateAt.END);
        countLabel.setTypeface(AndroidUtilities.bold());
        countLabel.setTextColor(accent);
        countLabel.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),
                Theme.blendOver(Theme.getColor(Theme.key_dialogBackgroundGray, provider),
                        (accent & 0x00ffffff) | 0x22000000)));
        countLabel.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(5),
                AndroidUtilities.dp(10), AndroidUtilities.dp(5));
        heading.addView(countLabel);

        ImageView gearBtn = new ImageView(activity);
        gearBtn.setImageResource(R.drawable.msg_settings);
        gearBtn.setScaleType(ImageView.ScaleType.CENTER);
        gearBtn.setColorFilter(muted);
        gearBtn.setContentDescription(safeString(activity, R.string.PengramQuoteOpenSettings));
        gearBtn.setBackground(Theme.createSelectorDrawable(0x18000000, 1));
        gearBtn.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            chat.presentFragment(new PengramSettingsActivity(PengramSettingsActivity.SECTION_QUOTES));
        });
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(AndroidUtilities.dp(34), AndroidUtilities.dp(34));
        gearLp.leftMargin = AndroidUtilities.dp(6);
        heading.addView(gearBtn, gearLp);

        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.bottomMargin = AndroidUtilities.dp(10);
        root.addView(heading, headingParams);

        // 2. Live Preview Stage (with checkerboard support for Transparent PNG)
        final FrameLayout picture = new FrameLayout(activity);
        picture.setBackground(createPreviewStageDrawable(provider,
                PengramConfig.getIntCached(KEY_BACKGROUND, 0) == 1));
        picture.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8),
                AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        final ImageView image = new ImageView(activity);
        image.setImageBitmap(result.preview);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.addView(image, new FrameLayout.LayoutParams(-1, -1));

        TextView zoomHint = new TextView(activity);
        zoomHint.setText(safeString(activity, R.string.PengramQuoteZoomHint));
        zoomHint.setTextSize(11.5f);
        zoomHint.setTypeface(AndroidUtilities.bold());
        zoomHint.setTextColor(Color.WHITE);
        zoomHint.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(11), 0xB018222D));
        zoomHint.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(5),
                AndroidUtilities.dp(10), AndroidUtilities.dp(5));
        FrameLayout.LayoutParams hintPos = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        hintPos.setMargins(0, 0, AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        picture.addView(zoomHint, hintPos);
        picture.setContentDescription(safeString(activity, R.string.PengramQuoteZoomHint));
        picture.setOnClickListener(v -> {
            if (ensureAllowed(chat, selected)) zoom(activity, result.file);
        });
        int previewHeight = Math.min(AndroidUtilities.dp(290),
                Math.max(AndroidUtilities.dp(165), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.31f)));
        root.addView(picture, new LinearLayout.LayoutParams(-1, previewHeight));

        final Runnable triggerRefresh = () -> refreshPreview(chat, result, image, picture,
                entries, paths, selected, ref, previewVersion, rendering, countLabel);

        // 3. Row A — Palette & Background Studio Pickers
        TextView presetsHeader = new TextView(activity);
        presetsHeader.setText(safeString(activity, R.string.PengramQuoteStudioPresets));
        presetsHeader.setTextSize(13);
        presetsHeader.setTypeface(AndroidUtilities.bold());
        presetsHeader.setTextColor(muted);
        presetsHeader.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(11),
                AndroidUtilities.dp(2), AndroidUtilities.dp(6));
        root.addView(presetsHeader, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView presetsScroll = new HorizontalScrollView(activity);
        presetsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presetsRow = new LinearLayout(activity);
        presetsRow.setOrientation(LinearLayout.HORIZONTAL);

        final String[] bgArray = activity.getResources().getStringArray(R.array.pengram_quote_backgrounds);
        final String[] styleArray = activity.getResources().getStringArray(R.array.pengram_quote_styles);

        final TextView[] darkBubbleChipRef = new TextView[1];

        final int initBg = Math.max(0, Math.min(bgArray.length - 1, PengramConfig.getIntCached(KEY_BACKGROUND, 0)));
        final TextView bgChip = studioChip(activity, provider,
                "🖼 " + bgArray[initBg], initBg != 0, null);
        bgChip.setOnClickListener(v -> {
            int cur = Math.max(0, Math.min(bgArray.length - 1, PengramConfig.getIntCached(KEY_BACKGROUND, 0)));
            new AlertDialog.Builder(activity, provider)
                    .setTitle(safeString(activity, R.string.PengramQuoteBackground))
                    .setItems(bgArray, (d, which) -> {
                        PengramConfig.setIntValue(KEY_BACKGROUND, which);
                        bgChip.setText("🖼 " + bgArray[which]);
                        styleChip(bgChip, provider, which != 0);
                        triggerRefresh.run();
                    }).show();
        });
        LinearLayout.LayoutParams bgLp = new LinearLayout.LayoutParams(-2, -2);
        bgLp.rightMargin = AndroidUtilities.dp(6);
        presetsRow.addView(bgChip, bgLp);

        final boolean initFollow = PengramConfig.getBool(KEY_THEME_STYLE, true);
        final int initStyleIdx = initFollow ? 0 : Math.min(styleArray.length - 1, Math.max(1, PengramConfig.getIntCached(KEY_STYLE, 0) + 1));
        final TextView paletteChip = studioChip(activity, provider,
                "🎨 " + styleArray[initStyleIdx], !initFollow, null);
        paletteChip.setOnClickListener(v -> {
            new AlertDialog.Builder(activity, provider)
                    .setTitle(safeString(activity, R.string.PengramQuoteAccent))
                    .setItems(styleArray, (d, which) -> {
                        boolean follow = (which == 0);
                        PengramConfig.setBool(KEY_THEME_STYLE, follow);
                        if (!follow) {
                            PengramConfig.setIntValue(KEY_STYLE, which - 1);
                        }
                        paletteChip.setText("🎨 " + styleArray[which]);
                        styleChip(paletteChip, provider, !follow);
                        if (darkBubbleChipRef[0] != null) {
                            darkBubbleChipRef[0].setVisibility(follow ? View.GONE : View.VISIBLE);
                        }
                        triggerRefresh.run();
                    }).show();
        });
        LinearLayout.LayoutParams palLp = new LinearLayout.LayoutParams(-2, -2);
        palLp.rightMargin = AndroidUtilities.dp(6);
        presetsRow.addView(paletteChip, palLp);

        final TextView darkChip = studioChip(activity, provider,
                safeString(activity, R.string.PengramQuoteDark),
                PengramConfig.getBool(KEY_DARK, false), null);
        darkChip.setVisibility(initFollow ? View.GONE : View.VISIBLE);
        darkChip.setOnClickListener(v -> {
            boolean next = !PengramConfig.getBool(KEY_DARK, false);
            PengramConfig.setBool(KEY_DARK, next);
            styleChip(darkChip, provider, next);
            triggerRefresh.run();
        });
        darkBubbleChipRef[0] = darkChip;
        LinearLayout.LayoutParams darkLp = new LinearLayout.LayoutParams(-2, -2);
        darkLp.rightMargin = AndroidUtilities.dp(6);
        presetsRow.addView(darkChip, darkLp);

        // Radius quick cycle (Sharp 10 -> Telegram 24 -> Pill 34)
        final int curRad = PengramConfig.getIntCached(KEY_RADIUS, 28);
        final TextView radChip = studioChip(activity, provider,
                safeString(activity, R.string.PengramQuoteRadius) + ": " + curRad,
                curRad != 28, null);
        radChip.setOnClickListener(v -> {
            int r = PengramConfig.getIntCached(KEY_RADIUS, 28);
            int nextR = r < 16 ? 24 : (r < 30 ? 34 : 10);
            PengramConfig.setIntValue(KEY_RADIUS, nextR);
            radChip.setText(safeString(activity, R.string.PengramQuoteRadius) + ": " + nextR);
            styleChip(radChip, provider, nextR != 28);
            triggerRefresh.run();
        });
        LinearLayout.LayoutParams radLp = new LinearLayout.LayoutParams(-2, -2);
        radLp.rightMargin = AndroidUtilities.dp(6);
        presetsRow.addView(radChip, radLp);

        // Padding quick cycle (Compact 12 -> Standard 24 -> Card 44)
        final int curPad = PengramConfig.getIntCached(KEY_PADDING, 24);
        final TextView padChip = studioChip(activity, provider,
                safeString(activity, R.string.PengramQuotePadding) + ": " + curPad,
                curPad != 24, null);
        padChip.setOnClickListener(v -> {
            int p = PengramConfig.getIntCached(KEY_PADDING, 24);
            int nextP = p < 18 ? 24 : (p < 36 ? 44 : 12);
            PengramConfig.setIntValue(KEY_PADDING, nextP);
            padChip.setText(safeString(activity, R.string.PengramQuotePadding) + ": " + nextP);
            styleChip(padChip, provider, nextP != 24);
            triggerRefresh.run();
        });
        presetsRow.addView(padChip, new LinearLayout.LayoutParams(-2, -2));

        presetsScroll.addView(presetsRow);
        root.addView(presetsScroll, new LinearLayout.LayoutParams(-1, -2));

        // 4. Row B — Official Telegram Bubble Details & Content Chips
        TextView settingsLabel = new TextView(activity);
        settingsLabel.setText(safeString(activity, R.string.PengramQuoteQuickSettings));
        settingsLabel.setTextSize(13);
        settingsLabel.setTypeface(AndroidUtilities.bold());
        settingsLabel.setTextColor(muted);
        settingsLabel.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(10),
                AndroidUtilities.dp(2), AndroidUtilities.dp(6));
        root.addView(settingsLabel, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView quick = new HorizontalScrollView(activity);
        quick.setHorizontalScrollBarEnabled(false);
        LinearLayout toggles = new LinearLayout(activity);
        toggles.setOrientation(LinearLayout.HORIZONTAL);

        // Side vs Inline avatar toggle chip
        final boolean initSideAv = PengramConfig.getIntCached(KEY_AVATAR_LAYOUT, AVATAR_LAYOUT_SIDE) == AVATAR_LAYOUT_SIDE;
        final TextView avLayoutChip = studioChip(activity, provider,
                safeString(activity, R.string.PengramQuoteAvatarLayout), initSideAv, null);
        avLayoutChip.setOnClickListener(v -> {
            int cur = PengramConfig.getIntCached(KEY_AVATAR_LAYOUT, AVATAR_LAYOUT_SIDE);
            int next = cur == AVATAR_LAYOUT_SIDE ? AVATAR_LAYOUT_INLINE : AVATAR_LAYOUT_SIDE;
            PengramConfig.setIntValue(KEY_AVATAR_LAYOUT, next);
            styleChip(avLayoutChip, provider, next == AVATAR_LAYOUT_SIDE);
            triggerRefresh.run();
        });
        LinearLayout.LayoutParams avLayoutLp = new LinearLayout.LayoutParams(-2, -2);
        avLayoutLp.rightMargin = AndroidUtilities.dp(6);
        toggles.addView(avLayoutChip, avLayoutLp);

        final int[] toggleLabels = {
                R.string.PengramQuoteAvatar,
                R.string.PengramQuoteBubbleTail,
                R.string.PengramQuoteNames,
                R.string.PengramQuotePeerNameColor,
                R.string.PengramQuoteShowReply,
                R.string.PengramQuoteShowReactions,
                R.string.PengramQuoteShowBadges,
                R.string.PengramQuoteTimes,
                R.string.PengramQuoteDateInTime,
                R.string.PengramQuoteMedia,
                R.string.PengramQuoteAlignOutgoing,
                R.string.PengramQuoteAnonMentions,
                R.string.PengramQuoteWatermark
        };
        final String[] toggleKeys = {
                KEY_AVATAR,
                KEY_BUBBLE_TAIL,
                KEY_NAME,
                KEY_PEER_NAME_COLOR,
                KEY_SHOW_REPLY,
                KEY_REACTIONS,
                KEY_SHOW_BADGES,
                KEY_TIME,
                KEY_DATE_IN_TIME,
                KEY_MEDIA,
                KEY_ALIGN_OUTGOING,
                KEY_ANON_MENTIONS,
                KEY_WATERMARK
        };
        final boolean[] toggleDefaults = {
                true, true, true, true, true, true, true, true, false, true, false, false, false
        };

        for (int i = 0; i < toggleLabels.length; i++) {
            if (toggleKeys[i].equals(KEY_ALIGN_OUTGOING) && entries.size() <= 1) continue;
            final int idx = i;
            boolean enabled = PengramConfig.getBool(toggleKeys[i], toggleDefaults[i]);
            TextView chip = studioChip(activity, provider, safeString(activity, toggleLabels[i]), enabled, null);
            chip.setContentDescription(safeString(activity, toggleLabels[i]));
            chip.setOnClickListener(v -> {
                boolean next = !PengramConfig.getBool(toggleKeys[idx], toggleDefaults[idx]);
                PengramConfig.setBool(toggleKeys[idx], next);
                styleChip(chip, provider, next);
                triggerRefresh.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = i == toggleLabels.length - 1 ? 0 : AndroidUtilities.dp(6);
            toggles.addView(chip, lp);
        }
        quick.addView(toggles);
        root.addView(quick, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(activity);
        hint.setText(safeString(activity, R.string.PengramQuotePrivacyHint));
        hint.setTextSize(11.5f);
        hint.setTextColor(muted);
        hint.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(8),
                AndroidUtilities.dp(2), AndroidUtilities.dp(10));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        // 5. Primary Action Row: [Send as Sticker] + [Send to Chat] right at top level!
        final boolean canWrite = chat.getCurrentChat() == null || ChatObject.canWriteToChat(chat.getCurrentChat());
        final boolean canSendPhoto = canSendCurrent(chat);
        final boolean canSendSticker = canWrite && (chat.getCurrentChat() == null || ChatObject.canSendStickers(chat.getCurrentChat()));

        if (canSendSticker || canSendPhoto) {
            LinearLayout primaryRow = new LinearLayout(activity);
            primaryRow.setOrientation(LinearLayout.HORIZONTAL);
            if (canSendSticker) {
                TextView stickerBtn = action(activity, provider, R.string.PengramQuoteSendSticker, true, () -> {
                    if (!readyForAction(chat, selected, rendering)) return;
                    if (ref[0] != null) ref[0].dismiss();
                    sticker(chat, entries, paths, selected);
                });
                stickerBtn.setSingleLine(true);
                stickerBtn.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (canSendPhoto) stLp.rightMargin = AndroidUtilities.dp(6);
                primaryRow.addView(stickerBtn, stLp);
            }
            if (canSendPhoto) {
                TextView sendBtn = action(activity, provider, R.string.PengramQuoteSend, !canSendSticker, () -> {
                    if (!readyForAction(chat, selected, rendering)) return;
                    if (send(chat, result) && ref[0] != null) {
                        ref[0].dismiss();
                    }
                });
                sendBtn.setSingleLine(true);
                sendBtn.setEllipsize(TextUtils.TruncateAt.END);
                if (canSendSticker) {
                    // Tinted accent secondary primary button so both Sticker and Photo stand out cleanly
                    int tintBg = Theme.blendOver(Theme.getColor(Theme.key_dialogBackgroundGray, provider),
                            (accent & 0x00ffffff) | 0x26000000);
                    sendBtn.setTextColor(accent);
                    sendBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12),
                            tintBg, Theme.blendOver(tintBg, 0x1A000000)));
                }
                primaryRow.addView(sendBtn, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(-1, -2);
            primaryLp.bottomMargin = AndroidUtilities.dp(7);
            root.addView(primaryRow, primaryLp);
        }

        // 6. Secondary Action Row: Copy · Save · Other Chat · More…
        LinearLayout secondary = new LinearLayout(activity);
        secondary.setOrientation(LinearLayout.HORIZONTAL);
        int[] secondaryLabels = {R.string.Copy, R.string.Save, R.string.PengramQuoteMore};
        Runnable[] secondaryActions = {
                () -> {
                    if (!readyForAction(chat, selected, rendering)) return;
                    result.published = true;
                    copy(chat, result);
                },
                () -> {
                    if (!readyForAction(chat, selected, rendering)) return;
                    result.published = true;
                    save(chat, result);
                },
                () -> {
                    if (!readyForAction(chat, selected, rendering)) return;
                    ArrayList<CharSequence> choices = new ArrayList<>();
                    ArrayList<Runnable> operations = new ArrayList<>();
                    if (canWrite && (chat.getCurrentChat() == null || ChatObject.canSendDocument(chat.getCurrentChat()))) {
                        choices.add(safeString(activity, R.string.PengramQuoteSendFile));
                        operations.add(() -> sendDocument(chat, result));
                    }
                    choices.add(safeString(activity, R.string.PengramQuoteOtherChat));
                    operations.add(() -> sendOtherChat(chat, result, selected));
                    choices.add(safeString(activity, R.string.PengramQuoteShare));
                    operations.add(() -> share(chat, result));
                    final boolean previouslyPublished = result.published;
                    final java.util.concurrent.atomic.AtomicBoolean chosen = new java.util.concurrent.atomic.AtomicBoolean();
                    result.published = true;
                    AlertDialog chooser = new AlertDialog.Builder(activity, provider)
                            .setItems(choices.toArray(new CharSequence[0]), (dialog, which) -> {
                                if (!readyForAction(chat, selected, rendering)) return;
                                chosen.set(true);
                                if (ref[0] != null) ref[0].dismiss();
                                operations.get(which).run();
                            }).create();
                    if (chat.showDialog(chooser, dialog -> {
                        if (!chosen.get() && !previouslyPublished) result.file.delete();
                    }) == null) result.published = previouslyPublished;
                }
        };
        for (int i = 0; i < secondaryLabels.length; i++) {
            TextView button = action(activity, provider, secondaryLabels[i], false, secondaryActions[i]);
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setContentDescription(safeString(activity, secondaryLabels[i]));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            params.leftMargin = i == 0 ? 0 : AndroidUtilities.dp(6);
            secondary.addView(button, params);
        }
        root.addView(secondary, new LinearLayout.LayoutParams(-1, -2));

        BottomSheet.Builder sheetBuilder = new BottomSheet.Builder(activity, false, provider);
        sheetBuilder.setCustomView(outer);
        BottomSheet sheet = sheetBuilder.create();
        ref[0] = sheet;
        if (chat.showDialog(sheet, dialog -> {
            previewVersion.incrementAndGet();
            image.animate().cancel();
            image.setImageDrawable(null);
            discard(result);
        }) == null) {
            discard(result);
        }
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

    private static void zoom(Activity activity, File file) {
        if (!active(activity) || file == null) return;
        // Own the zoom bitmap: preview may be closed or re-rendered behind it.
        Bitmap enlarged;
        try {
            enlarged = decode(file, true);
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        if (enlarged == null) return;
        final Bitmap bitmap = enlarged;
        try {
            ZoomImageView image = new ZoomImageView(activity, bitmap);
            FrameLayout frame = new FrameLayout(activity);
            int height = Math.max(AndroidUtilities.dp(180),
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.68f));
            frame.addView(image, new FrameLayout.LayoutParams(-1, height));
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(safeString(activity, R.string.PengramQuotePreview))
                    .setView(frame).setPositiveButton(safeString(activity, R.string.OK), null).create();
            dialog.setOnDismissListener(d -> {
                image.setImageDrawable(null);
                if (!bitmap.isRecycled()) bitmap.recycle();
            });
            dialog.show();
        } catch (Throwable e) {
            FileLog.e(e);
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static boolean canSendCurrent(ChatActivity chat) {
        return chat.getCurrentChat() == null || ChatObject.canWriteToChat(chat.getCurrentChat())
                && ChatObject.canSendPhoto(chat.getCurrentChat());
    }

    private static boolean ensureAllowed(ChatActivity chat, ArrayList<MessageObject> selected) {
        if (!active(chat.getParentActivity())) return false;
        if (isRestricted(chat, selected)) {
            error(chat, R.string.PengramQuoteRestricted);
            return false;
        }
        return true;
    }

    private static boolean send(ChatActivity chat, Result result) {
        if (chat.getParentActivity() == null || !result.file.isFile()) {
            error(chat, R.string.PengramQuoteRenderError);
            return false;
        }
        if (!canSendCurrent(chat)) {
            error(chat, R.string.PengramQuoteCannotSend);
            return false;
        }
        try {
            MessageObject replyToTop = chat.getThreadMessage();
            if (replyToTop == null) replyToTop = topMessage(chat.getCurrentAccount(), chat.getDialogId(), chat.getTopicId());
            SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(chat.getCurrentAccount()),
                    result.file.getAbsolutePath(), null, chat.getDialogId(), null, replyToTop,
                    null, null, null, null, null, 0, null, true, 0, 0, SendMessageChatArguments.EMPTY);
            result.published = true;
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            error(chat, R.string.PengramQuoteCannotSend);
            return false;
        }
    }

    private static void sendDocument(ChatActivity chat, Result result) {
        if (!result.file.isFile()) {
            error(chat, R.string.PengramQuoteRenderError);
            return;
        }
        if (chat.getCurrentChat() != null && (!ChatObject.canWriteToChat(chat.getCurrentChat())
                || !ChatObject.canSendDocument(chat.getCurrentChat()))) {
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

    private static void sendOtherChat(ChatActivity chat, Result result,
                                      ArrayList<MessageObject> selected) {
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
                if (!ensureAllowed(chat, selected)) return false;
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
            try {
                BulletinFactory.of(chat).createCopyBulletin(
                        safeString(activity, R.string.PengramQuoteCopied), chat.getResourceProvider()).show();
            } catch (Throwable ignore) {
            }
        } catch (Throwable e) {
            FileLog.e(e);
            error(chat, R.string.PengramQuoteCopyError);
        }
    }

    private static void sticker(ChatActivity chat, ArrayList<Entry> entries, ArrayList<File> paths,
                                ArrayList<MessageObject> selected) {
        Activity activity = chat.getParentActivity();
        if (!active(activity)) return;
        final int account = chat.getCurrentAccount();
        final long dialogId = chat.getDialogId();
        final long topicId = chat.getTopicId();
        final String chatName = chat.getCurrentChat() != null ? chat.getCurrentChat().title
                : chat.getCurrentUser() != null ? UserObject.getUserName(chat.getCurrentUser()) : "";
        final QuotePalette palette = capturePalette(chat, !PengramConfig.getBool(KEY_STICKER_TRANSPARENT, true)
                && PengramConfig.getIntCached(KEY_BACKGROUND, 0) == 0);
        final boolean dark = PengramConfig.getBool(KEY_DARK, false);
        final int style = Math.max(0, Math.min(6, PengramConfig.getIntCached(KEY_STYLE, 0)));
        final AlertDialog spinner = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, chat.getResourceProvider());
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        if (chat.showDialog(spinner, dialog -> {
            if (!completed.get()) cancelled.set(true);
        }) == null) {
            if (palette.wallpaper != null) palette.wallpaper.recycle();
            return;
        }
        spinner.setCanceledOnTouchOutside(false);
        Utilities.globalQueue.postRunnable(() -> {
            File webp = null;
            File sourceFile = null;
            try {
                if (cancelled.get()) return;
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
                    completed.set(true);
                    spinner.dismiss();
                    if (cancelled.get() || !active(chat.getParentActivity()) || !file.isFile()) {
                        file.delete();
                        return;
                    }
                    if (!ensureAllowed(chat, selected)) {
                        file.delete();
                        return;
                    }
                    if (chat.getCurrentChat() != null && (!ChatObject.canWriteToChat(chat.getCurrentChat())
                            || !ChatObject.canSendStickers(chat.getCurrentChat()))) {
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
                    completed.set(true);
                    spinner.dismiss();
                    if (!cancelled.get()) error(chat, R.string.PengramQuoteStickerError);
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
                        else {
                            try {
                                BulletinFactory.createSaveToGalleryBulletin(chat, false, chat.getResourceProvider()).show();
                            } catch (Throwable ignore) {}
                        }
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
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        BulletinFactory.createSaveToGalleryBulletin(chat, false, chat.getResourceProvider()).show();
                    } catch (Throwable ignore) {}
                });
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
