package org.telegram.messenger;

import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Оркестратор «Помощника ссылок»: резолв → скачивание (± склейка дорожек) →
 * отправка видео через штатный медиа-конвейер Telegram. При любой ошибке —
 * деградация без потери смысла: ссылка уходит текстом, юзер всегда получает
 * результат. Один активный запрос; UI (бар над полем ввода) получает фазы
 * через Listener на главном потоке.
 */
public final class PengramLinkAssistant {

    public static final int PHASE_IDLE = -1;
    public static final int PHASE_RESOLVE = 0;
    public static final int PHASE_DOWNLOAD = 1;
    public static final int PHASE_MUX = 2;
    public static final int PHASE_SEND = 3;

    public interface Listener {
        /** percent: -1 — без процента (фаза resolve/mux/send), иначе 0..100. */
        void onPhase(int phase, int percent);

        void onFinished(boolean videoSent);

        void onFailed(String reason, boolean fallbackLinkSent);
    }

    public static final class SendRequest {
        public final int account;
        public final long dialogId;
        public final String url;
        public final org.telegram.messenger.MessageObject replyToMsg;
        public final org.telegram.messenger.MessageObject replyToTopMsg;

        public SendRequest(int account, long dialogId, String url,
                           org.telegram.messenger.MessageObject replyToMsg,
                           org.telegram.messenger.MessageObject replyToTopMsg) {
            this.account = account;
            this.dialogId = dialogId;
            this.url = url;
            this.replyToMsg = replyToMsg;
            this.replyToTopMsg = replyToTopMsg;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "PengramLinkAssistant");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean busy;
    private static volatile boolean[] cancelFlag;
    private static volatile Listener listener;
    private static volatile SendRequest activeRequest;

    private PengramLinkAssistant() {
    }

    public static boolean isBusy() {
        return busy;
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void detachListener() {
        listener = null;
    }

    /** Отключение конкретного слушателя (жизненный цикл EnterView). */
    public static void detachListenerIf(Listener expected) {
        if (listener == expected) {
            listener = null;
        }
    }

    public static PengramLinkResolver.FoundLink findLink(CharSequence text) {
        return PengramLinkResolver.findLink(text);
    }

    /** Точка входа по тапу на предложение. false — уже идёт другая задача. */
    public static boolean start(SendRequest request) {
        if (request == null || TextUtils.isEmpty(request.url)) {
            return false;
        }
        synchronized (PengramLinkAssistant.class) {
            if (busy) {
                return false;
            }
            busy = true;
            cancelFlag = new boolean[]{false};
            activeRequest = request;
        }
        final boolean[] cancel = cancelFlag;
        notifyPhase(listener, PHASE_RESOLVE, -1);
        EXECUTOR.execute(() -> run(request, cancel));
        return true;
    }

    /** Отмена: задача останавливается, файлы чистятся; onFinished не зовётся. */
    public static void cancel() {
        final boolean[] flag = cancelFlag;
        if (flag != null) {
            flag[0] = true;
        }
    }

    public static void reset() {
        cancel();
        synchronized (PengramLinkAssistant.class) {
            if (!busy) {
                cancelFlag = new boolean[]{false};
                activeRequest = null;
            }
        }
    }

    private static void run(SendRequest request, boolean[] cancel) {
        File workDir = null;
        File videoPart = null;
        File audioPart = null;
        File finalFile = null;
        try {
            cleanupOldFiles();

            final PengramLinkResolver.Resolved resolved = PengramLinkResolver.resolve(request.url);
            if (cancelled(cancel)) {
                return;
            }

            workDir = workDir();
            final String base = "pengram_" + Long.toHexString(System.currentTimeMillis())
                    + Integer.toHexString(request.url.hashCode());
            final boolean mux = resolved.needsMux && resolved.urls.size() >= 2;
            videoPart = new File(workDir, base + (mux ? "_v.mp4" : ".mp4"));

            notifyPhase(listener, PHASE_DOWNLOAD, 0);
            PengramLinkResolver.download(resolved.urls.get(0), videoPart, 1_500_000_000L,
                    resolved.cookies, resolved.referer, cancel, (written, total) -> {
                        final int percent = total > 0 ? (int) Math.min(99, written * 100 / total) : -1;
                        notifyPhase(listener, PHASE_DOWNLOAD, percent);
                    });
            if (cancelled(cancel)) {
                return;
            }
            if (videoPart.length() < 10_000L) {
                throw new Exception("empty file");
            }

            if (mux) {
                notifyPhase(listener, PHASE_MUX, -1);
                audioPart = new File(workDir, base + "_a.mp4");
                PengramLinkResolver.download(resolved.urls.get(1), audioPart, 200_000_000L,
                        resolved.cookies, resolved.referer, cancel, null);
                if (cancelled(cancel)) {
                    return;
                }
                finalFile = new File(workDir, base + "_mux.mp4");
                PengramVideoMux.mux(videoPart, audioPart, finalFile, cancel);
                videoPart.delete();
                videoPart = null;
                audioPart.delete();
                audioPart = null;
                if (cancelled(cancel)) {
                    finalFile.delete();
                    return;
                }
            } else {
                finalFile = videoPart;
                videoPart = null;
            }

            notifyPhase(listener, PHASE_SEND, -1);
            sendVideo(request, finalFile, buildCaption(resolved));
            notifyFinished(listener, true);
        } catch (Throwable t) {
            if (videoPart != null) {
                videoPart.delete();
            }
            if (audioPart != null) {
                audioPart.delete();
            }
            if (finalFile != null && !(t instanceof java.util.concurrent.CancellationException)) {
                finalFile.delete();
            }
            if (cancelled(cancel) || t instanceof java.util.concurrent.CancellationException) {
                // Отмена — тишина: ссылка остаётся в поле, её восстановит UI.
                return;
            }
            final String reason = humanReason(t);
            final boolean linkSent = sendFallbackLink(request);
            notifyFailed(listener, reason, linkSent);
        } finally {
            synchronized (PengramLinkAssistant.class) {
                busy = false;
                activeRequest = null;
            }
        }
    }

    private static boolean cancelled(boolean[] flag) {
        return flag != null && flag[0];
    }

    private static String humanReason(Throwable t) {
        final String msg = t != null && t.getMessage() != null ? t.getMessage() : "";
        if (msg.contains("too long") || msg.contains("live")) {
            return LocaleController.getString(R.string.PengramLinkTooLong);
        }
        return LocaleController.getString(R.string.PengramLinkFailed);
    }

    private static String buildCaption(PengramLinkResolver.Resolved resolved) {
        if (resolved.title == null || resolved.title.trim().isEmpty()) {
            return null;
        }
        final String title = resolved.title.trim();
        if (resolved.author != null && !resolved.author.isEmpty()
                && !title.contains(resolved.author) && !title.startsWith("@")) {
            return "@" + resolved.author + " — " + title;
        }
        return title;
    }

    private static void sendVideo(SendRequest request, File file, String caption) {
        final ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
        final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = file.getAbsolutePath();
        info.caption = caption;
        info.isVideo = true;
        media.add(info);

        SendMessagesHelper.prepareSendingMedia(
                AccountInstance.getInstance(request.account),
                media,
                request.dialogId,
                request.replyToMsg,
                request.replyToTopMsg,
                null,   // story
                null,   // quote
                false,  // forceDocument → обычное видео с превью
                false,  // groupMedia
                null,   // editing
                null,   // poll
                true,   // notify
                0,      // scheduleDate
                0,      // scheduleRepeatPeriod
                0,      // mode
                false,  // updateStickersOrder
                null,   // inputContent
                SendMessageChatArguments.EMPTY,
                0,      // effectId
                false,  // invertMedia
                0,      // payStars
                0,      // monoForumPeer
                null,   // suggestionParams
                null,   // pollSendParams
                false); // forcedPollDoNotSendFinal
    }

    /** Любой сбой → ссылка всё равно уходит (обещание «не нажал — ссылка скинется»). */
    private static boolean sendFallbackLink(SendRequest request) {
        try {
            if (TextUtils.isEmpty(request.url)) {
                return false;
            }
            final SendMessagesHelper.SendMessageParams params =
                    SendMessagesHelper.SendMessageParams.of(request.url, request.dialogId);
            params.replyToMsg = request.replyToMsg;
            params.replyToTopMsg = request.replyToTopMsg;
            params.notify = true;
            SendMessagesHelper.getInstance(request.account).sendMessage(params);
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private static void notifyPhase(Listener l, int phase, int percent) {
        if (l == null) {
            return;
        }
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            try {
                l.onPhase(phase, percent);
            } catch (Throwable ignore) {
            }
        });
    }

    private static void notifyFinished(Listener l, boolean sent) {
        if (l == null) {
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                l.onFinished(sent);
            } catch (Throwable ignore) {
            }
        });
    }

    private static void notifyFailed(Listener l, String reason, boolean linkSent) {
        if (l == null) {
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                l.onFailed(reason, linkSent);
            } catch (Throwable ignore) {
            }
        });
    }

    private static File workDir() {
        final File dir = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "pengram_links");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Части старше двух суток (битые после убитого процесса) подчищаем. */
    private static void cleanupOldFiles() {
        try {
            final File dir = workDir();
            final File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            final long cutoff = System.currentTimeMillis() - 48L * 3600_000L;
            for (File f : files) {
                if (f.lastModified() < cutoff) {
                    f.delete();
                }
            }
        } catch (Throwable ignore) {
        }
    }
}
