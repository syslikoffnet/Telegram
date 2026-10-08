package org.telegram.ui.Components;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** A separate local copy keeps the clipboard URI valid after Telegram clears its media cache. */
public final class PengramPhotoClipboard {
    private PengramPhotoClipboard() { }

    public static void copy(BaseFragment fragment, MessageObject message) {
        Activity activity = fragment.getParentActivity();
        if (activity == null || message == null || !message.isPhoto() || message.messageOwner == null) return;
        if (message.isSecretMedia() || DialogObject.isEncryptedDialog(message.getDialogId())
                || message.messageOwner.noforwards
                || MessagesController.getInstance(message.currentAccount).isPeerNoForwards(message.getDialogId())) {
            show(fragment, R.string.PengramPhotoCopyRestricted);
            return;
        }
        File source = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        if ((source == null || !source.isFile()) && message.messageOwner.attachPath != null) {
            File attached = new File(message.messageOwner.attachPath);
            if (attached.isFile()) source = attached;
        }
        if (source == null || !source.isFile() || source.length() == 0) {
            show(fragment, R.string.PengramPhotoCopyDownload);
            return;
        }
        final File from = source;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File dir = new File(activity.getFilesDir(), "cache/pengram_photo_clipboard");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Clipboard directory unavailable");
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) {
                    if (System.currentTimeMillis() - f.lastModified() > 7 * 86400000L) f.delete();
                }
                File target = new File(dir, "photo-" + System.nanoTime() + ".jpg");
                try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[32768];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                if (target.length() == 0) throw new IllegalStateException("Empty photo");
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (fragment.getParentActivity() == null) return;
                        Uri uri = FileProvider.getUriForFile(activity,
                                ApplicationLoader.getApplicationId() + ".provider", target);
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                        clipboard.setPrimaryClip(ClipData.newUri(activity.getContentResolver(), "Photo", uri));
                        show(fragment, R.string.PengramPhotoCopied);
                    } catch (Throwable e) {
                        FileLog.e(e);
                        show(fragment, R.string.PengramPhotoCopyError);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> show(fragment, R.string.PengramPhotoCopyError));
            }
        });
    }

    private static void show(BaseFragment fragment, int stringId) {
        if (fragment.getParentActivity() != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.copy, fragment.getParentActivity().getString(stringId)).show();
        }
    }
}
