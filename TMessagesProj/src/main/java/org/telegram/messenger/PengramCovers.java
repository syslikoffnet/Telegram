package org.telegram.messenger;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.LruCache;

import org.telegram.messenger.audioinfo.AudioInfo;

import java.io.File;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pengram: обложки треков без ожидания.
 * Обложка достаётся прямо из тегов скачанного файла в фоне и кладётся в память,
 * поэтому второй раз тот же трек открывается с картинкой мгновенно — без «получасовой загрузки».
 */
public class PengramCovers {

    public interface Callback {
        void onCover(String key, Bitmap bitmap);
    }

    // Byte budget, not 16 arbitrary full-size bitmaps (which could exhaust a low-RAM phone).
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final HashMap<String, ArrayList<Callback>> loading = new HashMap<>();
    private static final int MAX_WAITERS = 4;
    private static int generation;

    // Prefetch must not leave an unlimited backlog of retained messages and views.
    // One decoder runs at a time so compressed cover buffers don't accumulate.
    private static final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
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
                // A filename alone collides across chats/accounts and can show
                // someone else's cached artwork on an unrelated message.
                return messageObject.currentAccount + ":" + messageObject.getDialogId()
                        + ":" + messageObject.getId() + ":" + name;
            }
        } catch (Throwable ignore) {
        }
        return messageObject.currentAccount + ":" + messageObject.getDialogId() + ":" + messageObject.getId();
    }

    public static Bitmap getCached(MessageObject messageObject) {
        final String key = keyFor(messageObject);
        final Bitmap bitmap = TextUtils.isEmpty(key) ? null : cache.get(key);
        return bitmap != null && !bitmap.isRecycled() ? bitmap : null;
    }

    /**
     * Обложка из файла. Если она уже в памяти — колбэк придёт прямо сейчас,
     * иначе файл разберётся в фоне и колбэк вызовется на главном потоке.
     */
    public static void request(MessageObject messageObject, Callback callback) {
        if (callback != null) requestInternal(messageObject, callback, false);
    }

    /** заранее разобрать обложку следующего трека, только если декодер свободен */
    public static void prefetch(MessageObject messageObject) {
        requestInternal(messageObject, null, true);
    }

    private static void requestInternal(MessageObject messageObject, Callback callback, boolean prefetch) {
        final String key = keyFor(messageObject);
        if (TextUtils.isEmpty(key)) return;
        final Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            if (callback != null) callback.onCover(key, cached);
            return;
        }
        // Visible artwork takes priority over speculative decoding.
        if (prefetch && pool.getQueue().size() >= 2) return;
        final File file = pathOf(messageObject);
        // A track can be streamed before its file is downloaded. Never cache
        // "missing" permanently: retry after the file finishes downloading.
        if (file == null) return;
        final ArrayList<Callback> callbacks;
        final int requestGeneration;
        synchronized (loading) {
            final ArrayList<Callback> waiting = loading.get(key);
            if (waiting != null) {
                if (callback != null) {
                    // Keep the latest visible view, not dozens of old sheet instances.
                    if (waiting.size() == MAX_WAITERS) waiting.remove(0);
                    waiting.add(callback);
                }
                return;
            }
            callbacks = new ArrayList<>(1);
            if (callback != null) callbacks.add(callback);
            requestGeneration = generation;
            loading.put(key, callbacks);
        }
        try {
            pool.execute(() -> {
                synchronized (loading) {
                    if (generation != requestGeneration) return;
                }
                Bitmap bitmap = null;
                try {
                    final AudioInfo info = AudioInfo.getAudioInfo(file);
                    if (info != null) {
                        bitmap = info.getCover();
                        if (bitmap == null) bitmap = info.getSmallCover();
                    }
                } catch (Throwable ignore) {
                }
                final Bitmap result = bitmap;
                AndroidUtilities.runOnUIThread(() -> {
                    synchronized (loading) {
                        // clear() may have cancelled this job and a newer one may
                        // already be waiting for the very same track key.
                        if (generation != requestGeneration || loading.get(key) != callbacks) return;
                        loading.remove(key);
                    }
                    if (result != null && !result.isRecycled()) {
                        cache.put(key, result);
                        final ArrayList<Callback> listeners;
                        synchronized (loading) {
                            listeners = new ArrayList<>(callbacks);
                        }
                        for (Callback listener : listeners) {
                            try {
                                listener.onCover(key, result);
                            } catch (Throwable error) {
                                FileLog.e(error);
                            }
                        }
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            synchronized (loading) {
                if (loading.get(key) == callbacks) loading.remove(key);
            }
        }
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
        synchronized (loading) {
            generation++;
            loading.clear();
            pool.getQueue().clear();
        }
        cache.evictAll();
    }
}
