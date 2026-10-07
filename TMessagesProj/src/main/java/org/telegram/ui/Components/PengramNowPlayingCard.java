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
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native, self-contained share card for the track in Pengram's own player. */
public final class PengramNowPlayingCard {
    private PengramNowPlayingCard() {}
    private static final ExecutorService renderQueue = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TrackCardRenderer");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sending = new AtomicBoolean();

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
                if (!sending.compareAndSet(false, true)) return;
                final long topic = chat.getTopicId();
                final MessageObject replyTop = chat.getThreadMessage();
                final org.telegram.messenger.SendMessageChatArguments sendArgs = chat.getMessageChatSendParams();
                final Bitmap existing = resolveCover(track, visibleCover);
                final AtomicBoolean dispatched = new AtomicBoolean();
                final java.util.function.Consumer<Bitmap> prepare = artwork -> {
                    if (!dispatched.compareAndSet(false, true)) return;
                    try {
                        renderQueue.execute(() -> {
                            File file = null;
                            try {
                                Bitmap card = render(title, artist, artwork, 720);
                                try {
                                    File folder = new File(context.getCacheDir(), "pengram_cards");
                                    if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("Cannot create share cache");
                                    File[] stale = folder.listFiles();
                                    if (stale != null) for (File old : stale) {
                                        if (System.currentTimeMillis() - old.lastModified() > 86400000L) old.delete();
                                    }
                                    file = new File(folder, "track_" + System.nanoTime() + ".jpg");
                                    try (FileOutputStream stream = new FileOutputStream(file)) {
                                        if (!card.compress(Bitmap.CompressFormat.JPEG, 88, stream)) throw new java.io.IOException("JPEG encode failed");
                                    }
                                } finally {
                                    card.recycle();
                                }
                                final File ready = file;
                                main.post(() -> {
                                    try {
                                        if (currentChat() != chat || chat.getDialogId() != did || chat.getTopicId() != topic) {
                                            ready.delete();
                                            return;
                                        }
                                        org.telegram.messenger.SendMessagesHelper.prepareSendingPhoto(
                                                org.telegram.messenger.AccountInstance.getInstance(account), ready.getAbsolutePath(), null,
                                                did, null, replyTop, null, caption, null, null, null, 0, null,
                                                true, 0, chat.getChatMode(), sendArgs);
                                        org.telegram.ui.Components.BulletinFactory.of(chat)
                                                .createSimpleBulletin(R.raw.forward, getString(R.string.PengramNowPlayingSent)).show();
                                    } catch (Throwable error) {
                                        FileLog.e(error);
                                        ready.delete();
                                        showError(context, resourcesProvider);
                                    } finally {
                                        sending.set(false);
                                    }
                                });
                            } catch (Throwable error) {
                                FileLog.e(error);
                                if (file != null) file.delete();
                                main.post(() -> {
                                    sending.set(false);
                                    showError(context, resourcesProvider);
                                });
                            }
                        });
                    } catch (Throwable error) {
                        sending.set(false);
                        FileLog.e(error);
                        showError(context, resourcesProvider);
                    }
                };
                if (existing != null) {
                    prepare.accept(existing);
                } else {
                    // The file may still be downloading. Wait briefly for its cover; never block sending.
                    PengramCovers.request(track, (key, bitmap) -> {
                        if (TextUtils.equals(key, PengramCovers.keyFor(track))) prepare.accept(bitmap);
                    });
                    main.postDelayed(() -> prepare.accept(resolveCover(track, null)), 450);
                }
                return;
            }
            org.telegram.ui.Components.BulletinFactory.of(chat)
                    .createSimpleBulletin(R.raw.forward, getString(R.string.PengramNowPlayingSent)).show();
        } catch (Throwable error) {
            FileLog.e(error);
            showError(context, resourcesProvider);
        }
    }

    private static void showError(Context context, Theme.ResourcesProvider resourcesProvider) {
        new org.telegram.ui.ActionBar.AlertDialog.Builder(context, resourcesProvider)
                .setMessage(getString(R.string.PengramNowPlayingError))
                .setPositiveButton(getString(R.string.OK), null).show();
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
        final int[] generation = {0};
        final Runnable redraw = () -> {
            final int request = ++generation[0];
            final Bitmap artwork = cover[0];
            renderQueue.execute(() -> {
                Bitmap image = null;
                try { image = render(title, artist, artwork, 480); } catch (Throwable error) { FileLog.e(error); }
                final Bitmap result = image;
                main.post(() -> {
                    if (request == generation[0] && preview.isAttachedToWindow()) {
                        Bitmap old = preview.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                                ? ((android.graphics.drawable.BitmapDrawable) preview.getDrawable()).getBitmap() : null;
                        preview.setImageBitmap(result);
                        if (old != null && old != result && !old.isRecycled()) old.recycle();
                    } else if (result != null) {
                        result.recycle();
                    }
                });
            });
        };
        PengramCovers.request(track, (key, bitmap) -> {
            if (!TextUtils.equals(key, PengramCovers.keyFor(track))) return;
            cover[0] = bitmap;
            if (preview.isAttachedToWindow()) redraw.run();
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
        redraw.run();
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

    /** Square card with a square artwork crop and separate, non-overlapping text area. */
    private static Bitmap render(String title, String artist, Bitmap cover, int size) {
        final int w = 720, h = 720;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.scale(size / (float) w, size / (float) h);
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
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        RectF art = new RectF(104, 20, 616, 532);
        if (cover != null && !cover.isRecycled()) {
            try {
                int save = c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(art, 24, 24, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                int side = Math.min(cover.getWidth(), cover.getHeight());
                c.drawBitmap(cover, new android.graphics.Rect((cover.getWidth() - side) / 2,
                        (cover.getHeight() - side) / 2, (cover.getWidth() + side) / 2,
                        (cover.getHeight() + side) / 2), art, p);
                c.restoreToCount(save);
            } catch (Throwable error) {
                p.setColor(0xFF284F60);
                c.drawRoundRect(art, 24, 24, p);
            }
        } else {
            p.setColor(0xFF284F60);
            c.drawRoundRect(art, 24, 24, p);
            p.setColor(accent);
            c.drawCircle(360, 276, 130, p);
            p.setColor(0xFF20404C);
            c.drawCircle(360, 276, 50, p);
        }
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        text.setTextSize(44);
        CharSequence safeTitle = TextUtils.ellipsize(title, text, 580, TextUtils.TruncateAt.END);
        c.drawText(safeTitle.toString(), 70, 598, text);
        text.setColor(0xFFCFDEE0);
        text.setTextSize(29);
        CharSequence safeArtist = TextUtils.ellipsize(artist, text, 580, TextUtils.TruncateAt.END);
        c.drawText(safeArtist.toString(), 70, 647, text);
        text.setColor(accent);
        text.setTextSize(20);
        if (org.telegram.messenger.PengramConfig.isTrackCardBrand()) {
            c.drawText("NOW PLAYING  ·  PENGRAM", 70, 694, text);
        }
        return out;
    }
}
