package org.telegram.messenger.audioinfo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Decode embedded artwork within a small, predictable memory budget. */
public final class CoverDecode {
    public static final int MAX_COMPRESSED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SIDE = 800;

    private CoverDecode() {}

    public static Bitmap decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_COMPRESSED_BYTES) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0
                || options.outWidth > 32768 || options.outHeight > 32768) return null;
        int sample = 1;
        while (Math.max(options.outWidth, options.outHeight) / sample > MAX_SIDE) sample *= 2;
        options.inSampleSize = sample;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
