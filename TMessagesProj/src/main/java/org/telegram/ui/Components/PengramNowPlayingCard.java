package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
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

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
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

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider, MessageObject track) {
        if (context == null || track == null || !track.isMusic()) return;
        final String title = TextUtils.isEmpty(track.getMusicTitle()) ? "Music" : track.getMusicTitle();
        final String artist = TextUtils.isEmpty(track.getMusicAuthor()) ? "Unknown artist" : track.getMusicAuthor();
        final String link = trackLink(track);
        final AudioInfo info = MediaController.getInstance().getAudioInfo();
        final Bitmap cover = info != null && info.getCover() != null ? info.getCover() : PengramCovers.getCached(track);
        final Bitmap card;
        try {
            card = render(title, artist, cover);
        } catch (Throwable error) {
            FileLog.e(error);
            new org.telegram.ui.ActionBar.AlertDialog.Builder(context, resourcesProvider)
                    .setMessage(getString(R.string.PengramNowPlayingError))
                    .setPositiveButton(getString(R.string.OK), null).show();
            return; // low-memory devices: never bring down the music player
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider);
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(14), dp(20), dp(20));

        TextView heading = new TextView(context);
        heading.setText(getString(R.string.PengramNowPlayingShare));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        heading.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        layout.addView(heading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));

        ImageView preview = new ImageView(context);
        preview.setImageBitmap(card);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(title + " — " + artist);
        layout.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 290));

        final BottomSheet[] sheet = new BottomSheet[1];
        TextView imageButton = button(context, resourcesProvider, R.string.PengramNowPlayingImage);
        imageButton.setOnClickListener(v -> {
            if (sheet[0] != null) sheet[0].dismiss();
            try {
                File folder = new File(context.getFilesDir(), "cache");
                if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("Cannot create share cache");
                // Unique URI: a second share must not change the first recipient's image.
                File[] old = folder.listFiles((dir, name) -> name.startsWith("pengram_now_playing_")
                        && name.endsWith(".png"));
                if (old != null) for (File stale : old) {
                    if (System.currentTimeMillis() - stale.lastModified() > 86400000L) stale.delete();
                }
                File file = new File(folder, "pengram_now_playing_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    if (!card.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new java.io.IOException("PNG encode failed");
                }
                Uri uri = FileProvider.getUriForFile(context,
                        ApplicationLoader.getApplicationId() + ".provider", file);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.putExtra(Intent.EXTRA_TEXT, description(title, artist, link));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(Intent.createChooser(intent, getString(R.string.PengramNowPlayingShare)));
            } catch (Exception e) {
                FileLog.e(e);
                new org.telegram.ui.ActionBar.AlertDialog.Builder(context, resourcesProvider)
                        .setMessage(getString(R.string.PengramNowPlayingError))
                        .setPositiveButton(getString(R.string.OK), null).show();
            }
        });
        layout.addView(imageButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 12, 0, 0));

        TextView textButton = button(context, resourcesProvider, R.string.PengramNowPlayingText);
        textButton.setOnClickListener(v -> {
            if (sheet[0] != null) sheet[0].dismiss();
            String text = description(title, artist, link);
            new ShareAlert(context, null, text, false, link, false, resourcesProvider).show();
        });
        layout.addView(textButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        builder.setCustomView(layout);
        sheet[0] = builder.create();
        sheet[0].show();
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
        return "♫ " + title + " — " + artist + "\n" +
                (link.contains("/search?q=") ? getString(R.string.PengramNowPlayingFind) + ": " : "") + link;
    }

    private static String trackLink(MessageObject track) {
        // Only public channel posts have a shareable direct link. Private-chat IDs
        // must never be exposed as a broken/inaccessible t.me/c/... URL.
        if (track.getId() > 0 && track.getDialogId() < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(track.currentAccount).getChat(-track.getDialogId());
            String username = chat == null ? null : ChatObject.getPublicUsername(chat);
            if (!TextUtils.isEmpty(username)) return "https://t.me/" + username + "/" + track.getId();
        }
        return "https://music.youtube.com/search?q=" + Uri.encode(artist + " " + title);
    }

    private static Bitmap render(String title, String artist, Bitmap cover) {
        final int w = 900, h = 1080;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, 0xFF123445, 0xFF121421, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, w, h), 42, 42, p);
        p.setShader(null);
        p.setColor(0x3348D5BC);
        c.drawCircle(790, 110, 270, p);
        c.drawCircle(60, 840, 215, p);
        RectF art = new RectF(105, 105, 795, 795);
        p.setAlpha(255);
        if (cover != null && !cover.isRecycled()) {
            int save = c.save();
            c.clipRoundRect(art, 28, 28);
            c.drawBitmap(cover, null, art, p);
            c.restoreToCount(save);
        } else {
            p.setColor(0xFF284F60);
            c.drawRoundRect(art, 28, 28, p);
            p.setColor(0xFF68DAC3);
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
        text.setColor(0xFF68DAC3);
        text.setTextSize(24);
        c.drawText("NOW PLAYING  ·  PENGRAM", 105, 1020, text);
        return out;
    }
}
