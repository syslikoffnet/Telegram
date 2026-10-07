package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PengramCovers;
import org.telegram.messenger.R;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;

/** Native, self-contained share card for the track in Pengram's own player. */
public final class PengramNowPlayingCard {
    private PengramNowPlayingCard() {}

    /** Only a real, writable chat may expose the send button. */
    public static org.telegram.ui.ChatActivity currentChat() {
        final org.telegram.ui.ActionBar.BaseFragment visible =
                org.telegram.ui.LaunchActivity.getLastFragmentIncludeMainTabs();
        if (!(visible instanceof org.telegram.ui.ChatActivity)) return null;
        final org.telegram.ui.ChatActivity chat = (org.telegram.ui.ChatActivity) visible;
        if (chat.getDialogId() == 0 || chat.getChatMode() != org.telegram.ui.ChatActivity.MODE_DEFAULT
                || (!chat.canSendMessage() && chat.getCurrentEncryptedChat() == null)
                || chat.getMessagesController().getSendPaidMessagesStars(chat.getDialogId()) > 0) {
            return null;
        }
        final TLRPC.Chat group = chat.getCurrentChat();
        if (group != null && (!ChatObject.canSendMessages(group)
                || chat.getMessagesController().isForum(chat.getDialogId()) && chat.getTopicId() == 0)) {
            return null;
        }
        return chat;
    }

    /** Short tap: send the preselected format straight into the visible chat. */
    public static void send(Context context, Theme.ResourcesProvider resourcesProvider,
                            MessageObject track, Bitmap visibleCover, int format) {
        final org.telegram.ui.ChatActivity chat = currentChat();
        if (chat == null || track == null || !track.isMusic()) return;
        final String title = TextUtils.isEmpty(track.getMusicTitle()) ? "Music" : track.getMusicTitle();
        final String artist = TextUtils.isEmpty(track.getMusicAuthor()) ? "Unknown artist" : track.getMusicAuthor();
        final String link = trackLink(track);
        final String caption = description(title, artist, link);
        final long did = chat.getDialogId();
        final int account = chat.getCurrentAccount();
        try {
            if (format == org.telegram.messenger.PengramConfig.TRACK_CARD_TEXT) {
                if (chat.getCurrentChat() != null && !ChatObject.canSendPlain(chat.getCurrentChat())) {
                    noRights(context, resourcesProvider);
                    return;
                }
                final org.telegram.messenger.SendMessagesHelper.SendMessageParams params =
                        org.telegram.messenger.SendMessagesHelper.SendMessageParams.of(caption, did);
                params.replyToTopMsg = chat.getThreadMessage();
                params.sendMessageChatArguments = chat.getMessageChatSendParams();
                org.telegram.messenger.SendMessagesHelper.getInstance(account).sendMessage(params);
            } else {
                if (chat.getCurrentChat() != null && !ChatObject.canSendPhoto(chat.getCurrentChat())) {
                    noRights(context, resourcesProvider);
                    return;
                }
                final Bitmap cover = resolveCover(track, visibleCover);
                final Bitmap card = render(title, artist, cover);
                final File folder = new File(context.getCacheDir(), "pengram_cards");
                if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("Cannot create share cache");
                final File[] old = folder.listFiles();
                if (old != null) for (File stale : old) {
                    if (System.currentTimeMillis() - stale.lastModified() > 86400000L) stale.delete();
                }
                final File file = new File(folder, "track_" + System.nanoTime() + ".png");
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    if (!card.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new java.io.IOException("PNG encode failed");
                } finally {
                    card.recycle();
                }
                org.telegram.messenger.SendMessagesHelper.prepareSendingPhoto(
                        org.telegram.messenger.AccountInstance.getInstance(account), file.getAbsolutePath(), null,
                        did, null, chat.getThreadMessage(), null, caption, null, null, null, 0, null,
                        true, 0, chat.getChatMode(), chat.getMessageChatSendParams());
            }
            org.telegram.ui.Components.BulletinFactory.of(chat)
                    .createSimpleBulletin(R.raw.forward, getString(R.string.PengramNowPlayingSent)).show();
        } catch (Throwable error) {
            FileLog.e(error);
            new org.telegram.ui.ActionBar.AlertDialog.Builder(context, resourcesProvider)
                    .setMessage(getString(R.string.PengramNowPlayingError))
                    .setPositiveButton(getString(R.string.OK), null).show();
        }
    }

    private static void noRights(Context context, Theme.ResourcesProvider resourcesProvider) {
        new org.telegram.ui.ActionBar.AlertDialog.Builder(context, resourcesProvider)
                .setMessage(getString(R.string.PengramNowPlayingNoRights))
                .setPositiveButton(getString(R.string.OK), null).show();
    }

    /** Long tap: preview and override the saved format for this single send. */
    public static void show(Context context, Theme.ResourcesProvider resourcesProvider,
                            MessageObject track, Bitmap visibleCover) {
        if (context == null || track == null || !track.isMusic() || currentChat() == null) return;
        final String title = TextUtils.isEmpty(track.getMusicTitle()) ? "Music" : track.getMusicTitle();
        final String artist = TextUtils.isEmpty(track.getMusicAuthor()) ? "Unknown artist" : track.getMusicAuthor();
        final Bitmap[] cover = {resolveCover(track, visibleCover)};
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider);
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(14), dp(20), dp(20));
        final TextView heading = new TextView(context);
        heading.setText(getString(R.string.PengramNowPlayingShare));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        heading.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        layout.addView(heading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));
        final ImageView preview = new ImageView(context);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(title + " — " + artist);
        layout.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 290));
        final Runnable redraw = () -> preview.setImageBitmap(render(title, artist, cover[0]));
        redraw.run();
        PengramCovers.request(track, (key, bitmap) -> {
            if (!TextUtils.equals(key, PengramCovers.keyFor(track)) || !preview.isAttachedToWindow()) return;
            cover[0] = bitmap;
            redraw.run();
        });
        final BottomSheet[] sheet = new BottomSheet[1];
        final TextView imageButton = button(context, resourcesProvider, R.string.PengramNowPlayingImage);
        imageButton.setOnClickListener(v -> {
            if (sheet[0] != null) sheet[0].dismiss();
            send(context, resourcesProvider, track, cover[0], org.telegram.messenger.PengramConfig.TRACK_CARD_IMAGE);
        });
        layout.addView(imageButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 12, 0, 0));
        final TextView textButton = button(context, resourcesProvider, R.string.PengramNowPlayingText);
        textButton.setOnClickListener(v -> {
            if (sheet[0] != null) sheet[0].dismiss();
            send(context, resourcesProvider, track, cover[0], org.telegram.messenger.PengramConfig.TRACK_CARD_TEXT);
        });
        layout.addView(textButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        builder.setCustomView(layout);
        sheet[0] = builder.create();
        sheet[0].show();
    }

    private static Bitmap resolveCover(MessageObject track, Bitmap visible) {
        if (visible != null && !visible.isRecycled()) return visible;
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && TextUtils.equals(PengramCovers.keyFor(playing), PengramCovers.keyFor(track))) {
            final AudioInfo info = MediaController.getInstance().getAudioInfo();
            if (info != null) {
                final Bitmap bitmap = info.getCover() != null ? info.getCover() : info.getSmallCover();
                if (bitmap != null && !bitmap.isRecycled()) return bitmap;
            }
        }
        return PengramCovers.getCached(track);
    }

    private static TextView button(Context context, Theme.ResourcesProvider resourcesProvider, int label) {
        TextView button = new TextView(context);
        button.setText(getString(label));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider));
        button.setGravity(Gravity.CENTER);
        button.setBackground(Theme.getSelectorDrawable(false));
        return button;
    }

    private static String description(String title, String artist, String link) {
        return "♫ " + title + " — " + artist +
                (org.telegram.messenger.PengramConfig.isTrackCardLink() ? "\n" +
                        (link.contains("/search?q=") ? getString(R.string.PengramNowPlayingFind) + ": " : "") + link : "");
    }

    private static String trackLink(MessageObject track) {
        // Only public channel posts have a shareable direct link. Private-chat IDs
        // must never be exposed as a broken/inaccessible t.me/c/... URL.
        if (track.getId() > 0 && track.getDialogId() < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(track.currentAccount).getChat(-track.getDialogId());
            String username = chat == null ? null : ChatObject.getPublicUsername(chat);
            if (!TextUtils.isEmpty(username)) return "https://t.me/" + username + "/" + track.getId();
        }
        return "https://music.youtube.com/search?q=" + Uri.encode(
                track.getMusicAuthor() + " " + track.getMusicTitle());
    }

    private static Bitmap render(String title, String artist, Bitmap cover) {
        final int w = 900, h = 1080;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int accent = 0xFF48D5BC;
        if (cover != null && !cover.isRecycled()) {
            try {
                final int sample = cover.getPixel(cover.getWidth() / 2, cover.getHeight() / 2);
                final float[] hsv = new float[3];
                Color.colorToHSV(sample, hsv);
                hsv[1] = Math.max(.36f, Math.min(.75f, hsv[1]));
                hsv[2] = .85f;
                accent = Color.HSVToColor(hsv);
            } catch (Throwable ignore) {}
        }
        final float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        hsv[2] = .28f;
        final int darkAccent = Color.HSVToColor(hsv);
        p.setShader(new LinearGradient(0, 0, w, h, darkAccent, 0xFF121421, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, w, h), 42, 42, p);
        p.setShader(null);
        p.setColor((accent & 0x00FFFFFF) | 0x33000000);
        c.drawCircle(790, 110, 270, p);
        c.drawCircle(60, 840, 215, p);
        p.setColor(Color.WHITE);
        RectF art = new RectF(105, 105, 795, 795);
        p.setAlpha(255);
        if (cover != null && !cover.isRecycled()) {
            int save = c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(art, 28, 28, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            p.setAlpha(255);
            c.drawBitmap(cover, null, art, p);
            c.restoreToCount(save);
        } else {
            p.setColor(0xFF284F60);
            c.drawRoundRect(art, 28, 28, p);
            p.setColor(accent);
            c.drawCircle(450, 450, 160, p);
            p.setColor(0xFF20404C);
            c.drawCircle(450, 450, 55, p);
        }
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        text.setTextSize(52);
        CharSequence safeTitle = TextUtils.ellipsize(title, text, 700, TextUtils.TruncateAt.END);
        c.drawText(safeTitle.toString(), 105, 885, text);
        text.setColor(0xFFB5CED1);
        text.setTextSize(32);
        CharSequence safeArtist = TextUtils.ellipsize(artist, text, 700, TextUtils.TruncateAt.END);
        c.drawText(safeArtist.toString(), 105, 945, text);
        text.setColor(accent);
        text.setTextSize(24);
        if (org.telegram.messenger.PengramConfig.isTrackCardBrand()) {
            c.drawText("NOW PLAYING  ·  PENGRAM", 105, 1020, text);
        }
        return out;
    }
}
