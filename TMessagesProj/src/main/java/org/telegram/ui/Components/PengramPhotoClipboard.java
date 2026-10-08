package org.telegram.ui.Components;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.function.Consumer;

/** A separate private copy keeps the clipboard URI valid after the media cache is cleared. */
public final class PengramPhotoClipboard {
    private PengramPhotoClipboard() { }

    /** A forwarding restriction is not a secret-media restriction: local photos can be copied. */
    public static boolean isSensitive(MessageObject message) {
        return message != null && (message.isSecretMedia() || message.needDrawBluredPreview()
                || message.hasRevealedExtendedMedia() || DialogObject.isEncryptedDialog(message.getDialogId())
                || message.messageOwner != null && (message.messageOwner.ttl != 0
                || MessageObject.getMedia(message.messageOwner) != null
                && MessageObject.getMedia(message.messageOwner).ttl_seconds != 0));
    }

    public static void copy(BaseFragment fragment, MessageObject message) {
        if (fragment == null) return;
        Activity activity = fragment.getParentActivity();
        if (activity != null) {
            copy(activity, message, null, null, text -> show(fragment, text));
        }
    }

    /** Caller obtains a bitmap holder on the UI thread; we release it after the background copy. */
    public static void copy(Activity activity, MessageObject message, File preferred,
                            ImageReceiver.BitmapHolder preview, Consumer<CharSequence> result) {
        if (activity == null) {
            release(preview);
            return;
        }
        if (message != null && isSensitive(message)) {
            release(preview);
            notify(activity, result, R.string.PengramPhotoCopyRestricted);
            return;
        }
        if (message != null && (!message.isPhoto() || message.messageOwner == null)
                || message == null && preferred == null && preview == null) {
            release(preview);
            notify(activity, result, R.string.PengramPhotoCopyError);
            return;
        }
        copyAvailable(activity, message, preferred, preview, result, true);
    }

    private static void copyAvailable(Activity activity, MessageObject message, File preferred,
                                      ImageReceiver.BitmapHolder preview, Consumer<CharSequence> result,
                                      boolean allowDownload) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File source = validPhoto(preferred) ? preferred : findLocalPhoto(message);
                if (source == null && preview == null && allowDownload && message != null && !message.pengramDeleted
                        && requestDownload(activity, message, result)) {
                    return;
                }
                if (source == null && (preview == null || preview.bitmap == null || preview.bitmap.isRecycled())) {
                    notify(activity, result, R.string.PengramPhotoCopyDownload);
                    return;
                }
                File dir = new File(activity.getFilesDir(), "cache/pengram_photo_clipboard");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Clipboard directory unavailable");
                // Don't remove a recently copied image: other apps can read its URI later.
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) {
                    if (f.isFile() && System.currentTimeMillis() - f.lastModified() > 7 * 86400000L) f.delete();
                }

                boolean isPreview = false;
                File target;
                if (source != null) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(source.getAbsolutePath(), options);
                    String extension = "image/png".equals(options.outMimeType) ? ".png"
                            : "image/webp".equals(options.outMimeType) ? ".webp" : ".jpg";
                    target = new File(dir, "photo-" + System.nanoTime() + extension);
                    try (FileInputStream in = new FileInputStream(source);
                         FileOutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[32768];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                } else if (preview != null && preview.bitmap != null && !preview.bitmap.isRecycled()) {
                    isPreview = true;
                    target = new File(dir, "photo-" + System.nanoTime() + ".png");
                    Bitmap visible = preview.bitmap;
                    int maxSide = Math.max(visible.getWidth(), visible.getHeight());
                    Bitmap scaled = maxSide > 2560 ? Bitmap.createScaledBitmap(visible,
                            Math.max(1, Math.round(visible.getWidth() * 2560f / maxSide)),
                            Math.max(1, Math.round(visible.getHeight() * 2560f / maxSide)), true) : visible;
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw new IllegalStateException("Could not encode photo preview");
                        }
                    } finally {
                        if (scaled != visible) scaled.recycle();
                    }
                } else {
                    notify(activity, result, R.string.PengramPhotoCopyDownload);
                    return;
                }
                if (!target.isFile() || target.length() == 0) throw new IllegalStateException("Empty photo copy");
                final boolean copiedPreview = isPreview;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(activity,
                                ApplicationLoader.getApplicationId() + ".provider", target);
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                        clipboard.setPrimaryClip(ClipData.newUri(activity.getContentResolver(), "Photo", uri));
                        notify(activity, result, copiedPreview ? R.string.PengramPhotoCopiedPreview : R.string.PengramPhotoCopied);
                    } catch (Throwable e) {
                        FileLog.e(e);
                        target.delete();
                        notify(activity, result, R.string.PengramPhotoCopyError);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
                notify(activity, result, R.string.PengramPhotoCopyError);
            } finally {
                release(preview);
            }
        });
    }

    /** Fetch an ordinary, still-available message photo when the user copies from the chat. */
    private static boolean requestDownload(Activity activity, MessageObject message, Consumer<CharSequence> result) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media == null || media.photo == null || media.photo.sizes == null) return false;
        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize());
        if (size == null) return false;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                FileLoader.getInstance(message.currentAccount).loadFile(
                        ImageLocation.getForObject(size, media.photo), message, null, FileLoader.PRIORITY_HIGH, 0);
                notify(activity, result, R.string.PengramPhotoCopyLoading);
                long deadline = SystemClock.elapsedRealtime() + 20000;
                Utilities.globalQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (findLocalPhoto(message) != null) {
                            copyAvailable(activity, message, null, null, result, false);
                        } else if (SystemClock.elapsedRealtime() < deadline) {
                            Utilities.globalQueue.postRunnable(this, 350);
                        } else {
                            notify(activity, result, R.string.PengramPhotoCopyDownload);
                        }
                    }
                }, 350);
            } catch (Throwable e) {
                FileLog.e(e);
                notify(activity, result, R.string.PengramPhotoCopyDownload);
            }
        });
        return true;
    }

    private static File findLocalPhoto(MessageObject message) {
        if (message == null || message.messageOwner == null) return null;
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        FileLoader loader = FileLoader.getInstance(message.currentAccount);
        String path = message.messageOwner.attachPath;
        if (!TextUtils.isEmpty(path)) {
            File attached = new File(path);
            if (validPhoto(attached)) return attached;
        }
        File original = loader.getPathToMessage(message.messageOwner);
        if (validPhoto(original)) return original;
        if (media != null && media.photo != null && media.photo.sizes != null) {
            // A deleted/archived message may still have a photo in the normal media folder,
            // even if the generic message path points to an empty cache location.
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize());
            if (size != null) {
                File typed = loader.getPathToAttach(size, false);
                if (validPhoto(typed)) return typed;
                File cached = loader.getPathToAttach(size, true);
                if (validPhoto(cached)) return cached;
            }
        }
        return null;
    }

    private static boolean validPhoto(File file) {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > 64L * 1024 * 1024) return false;
        // In particular, never put encrypted .enc bytes (or arbitrary documents) on the clipboard.
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            return bounds.outWidth > 0 && bounds.outHeight > 0 && ("image/jpeg".equals(bounds.outMimeType)
                    || "image/png".equals(bounds.outMimeType) || "image/webp".equals(bounds.outMimeType));
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void release(ImageReceiver.BitmapHolder holder) {
        if (holder != null) holder.release();
    }

    private static void notify(Activity activity, Consumer<CharSequence> result, int stringId) {
        AndroidUtilities.runOnUIThread(() -> {
            if (result == null || activity.isFinishing() || activity.isDestroyed()) return;
            try {
                result.accept(safeString(activity, stringId));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static CharSequence safeString(Activity activity, int id) {
        try {
            return activity.getString(id);
        } catch (android.content.res.Resources.NotFoundException e) {
            FileLog.e(e);
            // A stale release resource table must never crash the UI during error reporting.
            if (id == R.string.PengramPhotoCopied) return "Photo copied";
            if (id == R.string.PengramPhotoCopiedPreview) return "Available photo preview copied";
            if (id == R.string.PengramPhotoCopyLoading) return "Downloading photo…";
            if (id == R.string.PengramPhotoCopyDownload) return "Photo not downloaded or no longer on this device";
            if (id == R.string.PengramPhotoCopyRestricted) return "Copying is unavailable for secret photos";
            return "Could not copy the photo";
        }
    }

    private static void show(BaseFragment fragment, CharSequence text) {
        try {
            if (fragment.getParentActivity() != null && fragment.getFragmentView() != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.copy, text).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
