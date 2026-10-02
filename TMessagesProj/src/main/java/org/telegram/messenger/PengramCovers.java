package org.telegram.messenger;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.LruCache;

import org.telegram.messenger.audioinfo.AudioInfo;

import java.io.File;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pengram: обложки треков без ожидания.
 * Обложка достаётся прямо из тегов скачанного файла в фоне и кладётся в память,
 * поэтому второй раз тот же трек открывается с картинкой мгновенно — без «получасовой загрузки».
 */
public class PengramCovers {

    public interface Callback {
        void onCover(String key, Bitmap bitmap);
    }

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return 1;
        }
    };

    private static final HashSet<String> loading = new HashSet<>();
    private static final HashSet<String> missing = new HashSet<>();

    private static final ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "PengramCovers");
        thread.setPriority(Thread.MIN_PRIORITY + 2);
        thread.setDaemon(true);
        return thread;
    });

    public static String keyFor(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        try {
            final String name = messageObject.getFileName();
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
        } catch (Throwable ignore) {
        }
        return String.valueOf(messageObject.getId());
    }

    public static Bitmap getCached(MessageObject messageObject) {
        final String key = keyFor(messageObject);
        return TextUtils.isEmpty(key) ? null : cache.get(key);
    }

    /**
     * Обложка из файла. Если она уже в памяти — колбэк придёт прямо сейчас,
     * иначе файл разберётся в фоне и колбэк вызовется на главном потоке.
     */
    public static void request(MessageObject messageObject, Callback callback) {
        final String key = keyFor(messageObject);
        if (TextUtils.isEmpty(key) || callback == null) {
            return;
        }
        final Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            callback.onCover(key, cached);
            return;
        }
        synchronized (loading) {
            if (missing.contains(key) || loading.contains(key)) {
                return;
            }
            loading.add(key);
        }
        final File file = pathOf(messageObject);
        if (file == null) {
            synchronized (loading) {
                loading.remove(key);
                missing.add(key);
            }
            return;
        }
        pool.execute(() -> {
            Bitmap bitmap = null;
            try {
                final AudioInfo info = AudioInfo.getAudioInfo(file);
                if (info != null) {
                    bitmap = info.getCover();
                    if (bitmap == null) {
                        bitmap = info.getSmallCover();
                    }
                }
            } catch (Throwable ignore) {
            }
            final Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (loading) {
                    loading.remove(key);
                    if (result == null) {
                        missing.add(key);
                    }
                }
                if (result != null && !result.isRecycled()) {
                    cache.put(key, result);
                    callback.onCover(key, result);
                }
            });
        });
    }

    /** заранее разобрать обложку следующего трека */
    public static void prefetch(MessageObject messageObject) {
        request(messageObject, (key, bitmap) -> {
        });
    }

    private static File pathOf(MessageObject messageObject) {
        try {
            final File file = FileLoader.getInstance(messageObject.currentAccount)
                    .getPathToMessage(messageObject.messageOwner);
            return file != null && file.exists() && file.length() > 0 ? file : null;
        } catch (Throwable e) {
            return null;
        }
    }

    public static void clear() {
        cache.evictAll();
        synchronized (loading) {
            missing.clear();
        }
    }
}
