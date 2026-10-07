package org.telegram.messenger;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.LruCache;

import org.telegram.messenger.audioinfo.AudioInfo;

import java.io.File;
import java.util.HashMap;
import java.util.ArrayList;
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

    private static final HashMap<String, ArrayList<Callback>> loading = new HashMap<>();

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
        final File file = pathOf(messageObject);
        // A track can be streamed before its file is downloaded. Never cache
        // "missing" permanently: retry after the file finishes downloading.
        if (file == null) {
            return;
        }
        synchronized (loading) {
            final ArrayList<Callback> waiting = loading.get(key);
            if (waiting != null) {
                waiting.add(callback);
                return;
            }
            final ArrayList<Callback> callbacks = new ArrayList<>();
            callbacks.add(callback);
            loading.put(key, callbacks);
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
                final ArrayList<Callback> waiting;
                synchronized (loading) {
                    waiting = loading.remove(key);
                }
                if (result != null && !result.isRecycled()) {
                    cache.put(key, result);
                    if (waiting != null) {
                        for (Callback listener : waiting) {
                            try {
                                listener.onCover(key, result);
                            } catch (Throwable error) {
                                FileLog.e(error);
                            }
                        }
                    }
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

    }
}
