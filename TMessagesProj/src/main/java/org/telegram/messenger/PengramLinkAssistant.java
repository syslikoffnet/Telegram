package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Оркестратор «Помощника ссылок»: фоновое разрешение ссылки TikTok / YouTube,
 * маршрутизация в интерактивный предпросмотр (фото, карусели, видео), выборочная
 * отправка 1..N фотографий альбомом, аппаратная склейка фото в MP4-видео со звуком
 * ({@link PengramPhotoSlideshow}), склейка адаптивных потоков ({@link PengramVideoMux})
 * и настраиваемое поведение при ошибке (экран выбора / отправка ссылки / отмена).
 */
public final class PengramLinkAssistant {

    /** Жёсткий потолок на размер одного видео (1.5 ГБ) — защита от бесконечной загрузки. */
    public static final long MAX_VIDEO_BYTES = 1_500_000_000L;
    private static final long MAX_PHOTO_BYTES = 25L * 1024L * 1024L;
    private static final long MAX_AUDIO_BYTES = 30L * 1024L * 1024L;
    /** Старые временные файлы старше 30 минут зачищаются при следующем старте задачи. */
    private static final long TEMP_TTL_MS = 30L * 60L * 1000L;

    public static final int ACTION_AUTO = 0;
    public static final int ACTION_SEND_PHOTOS = 1;
    public static final int ACTION_STITCH_VIDEO = 2;
    public static final int ACTION_SEND_VIDEO = 3;

    public static final class Request {
        public final int currentAccount;
        public final long dialogId;
        public final String url;
        /** Оригинальный текст из поля ввода — для восстановления при отмене или отправки ссылкой. */
        public final String originalText;
        /** Дополнительная подпись от пользователя (текст вокруг ссылки), если была. */
        public final String extraCaption;
        public final MessageObject replyToMsg;
        public final MessageObject replyToTopMsg;

        public Request(int currentAccount, long dialogId, String url, String originalText,
                       String extraCaption, MessageObject replyToMsg, MessageObject replyToTopMsg) {
            this.currentAccount = currentAccount;
            this.dialogId = dialogId;
            this.url = url;
            this.originalText = originalText != null ? originalText : url;
            this.extraCaption = extraCaption != null ? extraCaption.trim() : "";
            this.replyToMsg = replyToMsg;
            this.replyToTopMsg = replyToTopMsg;
        }
    }

    public static final class ExecutionOptions {
        public final boolean forcePreview;
        @Nullable
        public final PengramLinkResolver.Resolved preResolved;
        public final int action;
        @Nullable
        public final List<Integer> selectedPhotoIndices;
        public final int captionModeOverride;
        @Nullable
        public final Boolean includeAudioOverride;

        public ExecutionOptions(boolean forcePreview,
                                @Nullable PengramLinkResolver.Resolved preResolved,
                                int action,
                                @Nullable List<Integer> selectedPhotoIndices,
                                int captionModeOverride,
                                @Nullable Boolean includeAudioOverride) {
            this.forcePreview = forcePreview;
            this.preResolved = preResolved;
            this.action = action;
            this.selectedPhotoIndices = selectedPhotoIndices;
            this.captionModeOverride = captionModeOverride;
            this.includeAudioOverride = includeAudioOverride;
        }

        public static ExecutionOptions auto() {
            return new ExecutionOptions(false, null, ACTION_AUTO, null, -1, null);
        }

        public static ExecutionOptions previewOnly() {
            return new ExecutionOptions(true, null, ACTION_AUTO, null, -1, null);
        }
    }

    public static final int PHASE_RESOLVE = 1;
    public static final int PHASE_DOWNLOAD = 2;
    public static final int PHASE_MUX = 3;
    public static final int PHASE_STITCH = 4;
    public static final int PHASE_SEND = 5;

    public interface Callback {
        void onPhase(int phase, float progress);
        void onSent();
        /** Открыть интерактивную шторку предпросмотра контента (фото/видео/выбор). */
        void onPreviewRequested(Request req, PengramLinkResolver.Resolved resolved);
        /** При сбое отправлена обычная ссылка (режим LINK_FALLBACK_SEND). */
        void onFallbackSent(String reason);
        /** При сбое отправка отменена, ссылку нужно вернуть в поле ввода (LINK_FALLBACK_NONE). */
        void onFailedNoSend(Request req, String reason);
        /** При сбое показать экран выбора действия (LINK_FALLBACK_ASK — по умолчанию). */
        void onFallbackChoiceRequested(Request req, String reason);
        void onCancelled();
    }

    /** Ручка отмены запущенной задачи. */
    public static final class Handle {
        final boolean[] cancel = new boolean[]{false};

        public void cancel() {
            cancel[0] = true;
        }

        public boolean isCancelled() {
            return cancel[0];
        }
    }

    private PengramLinkAssistant() {
    }

    public static Handle start(final Request req, final Callback cb) {
        return start(req, ExecutionOptions.auto(), cb);
    }

    public static Handle start(final Request req, final ExecutionOptions opts, final Callback cb) {
        final Handle handle = new Handle();
        final ExecutionOptions options = opts != null ? opts : ExecutionOptions.auto();
        Utilities.globalQueue.postRunnable(() -> execute(req, options, handle, cb));
        return handle;
    }

    /** Отправляет исходный текст как обычное текстовое сообщение со ссылкой (с превью страницы). */
    public static void sendPlainLink(Request req) {
        if (req == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            final String text = !req.originalText.trim().isEmpty() ? req.originalText : req.url;
            final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                    text, req.dialogId, req.replyToMsg, req.replyToTopMsg,
                    null, true, null, null, null, true, 0, 0, null, false);
            SendMessagesHelper.getInstance(req.currentAccount).sendMessage(params);
        });
    }

    public static String buildCaption(Request req, PengramLinkResolver.Resolved resolved, int captionModeOverride) {
        final int mode = captionModeOverride >= 0 ? captionModeOverride : PengramConfig.getLinkCaptionMode();
        final StringBuilder sb = new StringBuilder();
        if (mode == PengramConfig.LINK_CAPTION_FULL) {
            String meta = resolved.title != null ? resolved.title.trim() : "";
            final String author = resolved.author != null ? resolved.author.trim() : "";
            if (!author.isEmpty() && resolved.platform == PengramLinkResolver.PLATFORM_YOUTUBE) {
                if (!meta.isEmpty()) {
                    meta = meta + "\n— " + author;
                } else {
                    meta = author;
                }
            } else if (meta.isEmpty() && !author.isEmpty()) {
                meta = author.startsWith("@") ? author : ("@" + author);
            }
            if (!meta.isEmpty()) {
                sb.append(meta);
            }
        } else if (mode == PengramConfig.LINK_CAPTION_AUTHOR) {
            final String author = resolved.author != null ? resolved.author.trim() : "";
            if (!author.isEmpty()) {
                if (resolved.platform == PengramLinkResolver.PLATFORM_TIKTOK && !author.startsWith("@")) {
                    sb.append('@').append(author);
                } else {
                    sb.append(author);
                }
            }
        }
        if (req != null && !req.extraCaption.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(req.extraCaption);
        }
        final String out = sb.toString().trim();
        return out.length() > 1024 ? out.substring(0, 1024) : out;
    }

    private static void execute(Request req, ExecutionOptions opts, Handle handle, Callback cb) {
        final File workDir = new File(ApplicationLoader.getFilesDirFixed(), "pengram_links");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        sweepStaleFiles(workDir);

        final List<File> scratchToClean = new ArrayList<>();
        try {
            final PengramLinkResolver.Resolved resolved;
            if (opts.preResolved != null) {
                resolved = opts.preResolved;
            } else {
                notifyPhase(cb, PHASE_RESOLVE, 0f);
                checkCancelled(handle);
                resolved = PengramLinkResolver.resolve(req.url);
                checkCancelled(handle);
            }

            if (shouldOpenPreview(resolved, opts)) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (cb != null && !handle.isCancelled()) {
                        cb.onPreviewRequested(req, resolved);
                    }
                });
                return;
            }

            if (resolved.isPhotos()) {
                deliverPhotos(req, resolved, opts, workDir, scratchToClean, handle, cb);
            } else {
                deliverVideo(req, resolved, opts, workDir, scratchToClean, handle, cb);
            }
        } catch (CancellationException ce) {
            cleanFiles(scratchToClean);
            notifyCancelled(cb);
        } catch (Throwable err) {
            FileLog.e(new Exception("PengramLinkAssistant failed for " + req.url + ": " + err, err));
            cleanFiles(scratchToClean);
            if (handle.isCancelled()) {
                notifyCancelled(cb);
                return;
            }
            handleFailure(req, err.getMessage() != null ? err.getMessage() : "error", cb);
        }
    }

    private static boolean shouldOpenPreview(PengramLinkResolver.Resolved resolved, ExecutionOptions opts) {
        if (opts.forcePreview) {
            return true;
        }
        if (opts.action != ACTION_AUTO) {
            return false;
        }
        final int previewMode = PengramConfig.getLinkPreviewMode();
        if (resolved.isPhotos()) {
            if (previewMode == PengramConfig.LINK_PREVIEW_PHOTOS || previewMode == PengramConfig.LINK_PREVIEW_ALL) {
                return true;
            }
            // Если фото больше одного, а в настройках стоит «вызывать выбор» — открываем предпросмотр!
            if (resolved.photos.size() > 1 && PengramConfig.getLinkPhotoStitchMode() == PengramConfig.LINK_STITCH_ASK) {
                return true;
            }
            return false;
        } else {
            return previewMode == PengramConfig.LINK_PREVIEW_VIDEOS || previewMode == PengramConfig.LINK_PREVIEW_ALL;
        }
    }

    private static void deliverPhotos(Request req, PengramLinkResolver.Resolved resolved,
                                      ExecutionOptions opts, File workDir,
                                      List<File> scratchToClean, Handle handle, Callback cb) throws Exception {
        final List<PengramLinkResolver.PhotoItem> chosen = new ArrayList<>();
        if (opts.selectedPhotoIndices != null && !opts.selectedPhotoIndices.isEmpty()) {
            for (Integer idx : opts.selectedPhotoIndices) {
                if (idx != null && idx >= 0 && idx < resolved.photos.size()) {
                    chosen.add(resolved.photos.get(idx));
                }
            }
        } else {
            chosen.addAll(resolved.photos);
        }
        if (chosen.isEmpty()) {
            throw new Exception("no photos selected");
        }

        final boolean stitchIntoVideo;
        if (opts.action == ACTION_STITCH_VIDEO) {
            stitchIntoVideo = true;
        } else if (opts.action == ACTION_SEND_PHOTOS) {
            stitchIntoVideo = false;
        } else {
            stitchIntoVideo = chosen.size() > 1
                    && PengramConfig.getLinkPhotoStitchMode() == PengramConfig.LINK_STITCH_ALWAYS;
        }

        final long stamp = System.currentTimeMillis();
        final List<File> normalizedJpegs = new ArrayList<>(chosen.size());
        notifyPhase(cb, PHASE_DOWNLOAD, 0f);

        for (int i = 0; i < chosen.size(); i++) {
            checkCancelled(handle);
            final PengramLinkResolver.PhotoItem item = chosen.get(i);
            final File rawFile = new File(workDir, "link_" + stamp + "_p" + i + ".raw");
            scratchToClean.add(rawFile);

            final int photoIdx = i;
            final int totalPhotos = chosen.size();
            PengramLinkResolver.download(
                    item.imageUrl, rawFile, MAX_PHOTO_BYTES,
                    resolved.cookies, resolved.referer, resolved.userAgent, false,
                    handle.cancel,
                    (written, total) -> {
                        final float sub = total > 0 ? Math.min(1f, (float) written / total) : 0.5f;
                        notifyPhase(cb, PHASE_DOWNLOAD, (photoIdx + sub) / totalPhotos);
                    });
            checkCancelled(handle);

            final File jpgFile = new File(workDir, "link_" + stamp + "_" + i + ".jpg");
            normalizePhotoToJpeg(rawFile, jpgFile);
            rawFile.delete();
            if (stitchIntoVideo) {
                scratchToClean.add(jpgFile);
            }
            normalizedJpegs.add(jpgFile);
        }

        final String caption = buildCaption(req, resolved, opts.captionModeOverride);

        if (stitchIntoVideo) {
            File audioFile = null;
            final boolean wantAudio = opts.includeAudioOverride != null
                    ? opts.includeAudioOverride
                    : PengramConfig.isLinkSlideAudioEnabled();
            if (wantAudio && resolved.audioUrl != null && !resolved.audioUrl.isEmpty()) {
                final File rawAudio = new File(workDir, "link_" + stamp + "_aud.m4a");
                scratchToClean.add(rawAudio);
                try {
                    PengramLinkResolver.download(
                            resolved.audioUrl, rawAudio, MAX_AUDIO_BYTES,
                            resolved.cookies, resolved.referer, resolved.userAgent, false,
                            handle.cancel, null);
                    if (rawAudio.exists() && rawAudio.length() > 1024L) {
                        audioFile = rawAudio;
                    }
                } catch (CancellationException ce) {
                    throw ce;
                } catch (Throwable ignoreAudioErr) {
                    rawAudio.delete();
                }
            }

            checkCancelled(handle);
            notifyPhase(cb, PHASE_STITCH, 0f);
            final File stitchedMp4 = new File(workDir, "link_slide_" + stamp + ".mp4");
            try {
                PengramPhotoSlideshow.build(
                        normalizedJpegs,
                        audioFile,
                        stitchedMp4,
                        PengramConfig.getLinkSlideSeconds(),
                        handle.cancel,
                        (frame, totalFrames) -> {
                            if (totalFrames > 0) {
                                notifyPhase(cb, PHASE_STITCH, Math.min(1f, (float) frame / totalFrames));
                            }
                        });
            } catch (Throwable stitchErr) {
                stitchedMp4.delete();
                throw stitchErr;
            }
            cleanFiles(scratchToClean);
            scratchToClean.clear();

            checkCancelled(handle);
            notifyPhase(cb, PHASE_SEND, 1f);
            sendVideoFile(req, stitchedMp4, caption, cb);
            return;
        }

        cleanFiles(scratchToClean);
        scratchToClean.clear();
        checkCancelled(handle);
        notifyPhase(cb, PHASE_SEND, 1f);
        sendPhotoFiles(req, normalizedJpegs, caption, cb);
    }

    private static void deliverVideo(Request req, PengramLinkResolver.Resolved primary,
                                     ExecutionOptions opts, File workDir,
                                     List<File> scratchToClean, Handle handle, Callback cb) throws Exception {
        final List<PengramLinkResolver.Resolved> candidates = new ArrayList<>();
        candidates.add(primary);
        candidates.addAll(primary.fallbacks);

        File finalFile = null;
        Exception lastErr = null;
        for (int cIdx = 0; cIdx < candidates.size(); cIdx++) {
            checkCancelled(handle);
            final PengramLinkResolver.Resolved candidate = candidates.get(cIdx);
            final long stamp = System.currentTimeMillis();
            scratchToClean.clear();
            try {
                notifyPhase(cb, PHASE_DOWNLOAD, 0f);
                if (candidate.needsMux && candidate.urls.size() >= 2) {
                    final File vPart = new File(workDir, "link_" + stamp + ".v.mp4");
                    final File aPart = new File(workDir, "link_" + stamp + ".a.m4a");
                    final File out = new File(workDir, "link_" + stamp + ".mp4");
                    scratchToClean.add(vPart);
                    scratchToClean.add(aPart);

                    PengramLinkResolver.download(
                            candidate.urls.get(0), vPart, MAX_VIDEO_BYTES,
                            candidate.cookies, candidate.referer,
                            candidate.userAgent, candidate.rangeChunked, handle.cancel,
                            (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, t > 0 ? 0.78f * w / t : -1f));
                    checkCancelled(handle);

                    PengramLinkResolver.download(
                            candidate.urls.get(1), aPart, MAX_VIDEO_BYTES / 3,
                            candidate.cookies, candidate.referer,
                            candidate.userAgent, candidate.rangeChunked, handle.cancel,
                            (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, 0.78f + (t > 0 ? 0.22f * w / t : 0f)));
                    checkCancelled(handle);

                    notifyPhase(cb, PHASE_MUX, -1f);
                    try {
                        PengramVideoMux.mux(vPart, aPart, out, handle.cancel);
                    } catch (Throwable muxErr) {
                        out.delete();
                        throw muxErr;
                    }
                    cleanFiles(scratchToClean);
                    scratchToClean.clear();
                    validateDownloadedVideo(out);
                    finalFile = out;
                } else if (!candidate.urls.isEmpty()) {
                    final File out = new File(workDir, "link_" + stamp + ".mp4");
                    try {
                        PengramLinkResolver.download(
                                candidate.urls.get(0), out, MAX_VIDEO_BYTES,
                                candidate.cookies, candidate.referer,
                                candidate.userAgent, candidate.rangeChunked, handle.cancel,
                                (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
                        validateDownloadedVideo(out);
                        finalFile = out;
                    } catch (Throwable dlErr) {
                        out.delete();
                        throw dlErr;
                    }
                }
                if (finalFile != null) {
                    break;
                }
            } catch (CancellationException ce) {
                cleanFiles(scratchToClean);
                throw ce;
            } catch (Exception e) {
                cleanFiles(scratchToClean);
                lastErr = e;
            }
        }

        if (finalFile == null) {
            throw lastErr != null ? lastErr : new Exception("empty output");
        }

        checkCancelled(handle);
        notifyPhase(cb, PHASE_SEND, 1f);
        final String caption = buildCaption(req, primary, opts.captionModeOverride);
        sendVideoFile(req, finalFile, caption, cb);
    }

    private static void validateDownloadedVideo(File file) throws Exception {
        if (file == null || !file.exists() || file.length() < 4096L) {
            throw new Exception("video empty");
        }
        final byte[] head = new byte[16];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(head) < 12) {
                throw new Exception("video truncated");
            }
        }
        // Отсекаем случай, когда CDN отдал HTML-страницу с кодом 200 вместо MP4.
        if (head[0] == '<' || (head[0] == ' ' && head[1] == '<')) {
            throw new Exception("html instead of video");
        }
    }

    private static void normalizePhotoToJpeg(File rawFile, File outJpg) throws Exception {
        if (!rawFile.exists() || rawFile.length() < 128L) {
            throw new Exception("photo empty");
        }
        final byte[] magic = new byte[4];
        try (FileInputStream in = new FileInputStream(rawFile)) {
            if (in.read(magic) < 4) {
                throw new Exception("photo truncated");
            }
        }
        final boolean isJpeg = (magic[0] & 0xFF) == 0xFF && (magic[1] & 0xFF) == 0xD8;
        if (isJpeg) {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(rawFile.getAbsolutePath(), bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                if (rawFile.renameTo(outJpg)) {
                    return;
                }
                copyFile(rawFile, outJpg);
                return;
            }
        }

        // WebP / PNG / нестандартный контейнер → конвертируем в чистый JPEG 92%.
        final Bitmap bmp = PengramPhotoSlideshow.decodeOrientedBitmap(rawFile, 2560);
        if (bmp == null) {
            throw new Exception("photo decode failed");
        }
        try (FileOutputStream out = new FileOutputStream(outJpg)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new Exception("jpeg compress failed");
            }
        } finally {
            bmp.recycle();
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void sendVideoFile(Request req, File videoFile, String caption, Callback cb) {
        AndroidUtilities.runOnUIThread(() -> {
            final ArrayList<SendMessagesHelper.SendingMediaInfo> items = new ArrayList<>(1);
            final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = videoFile.getAbsolutePath();
            info.isVideo = true;
            if (!caption.isEmpty()) {
                info.caption = caption;
            }
            items.add(info);
            SendMessagesHelper.prepareSendingMedia(
                    AccountInstance.getInstance(req.currentAccount),
                    items,
                    req.dialogId,
                    req.replyToMsg,
                    req.replyToTopMsg,
                    null,
                    null,
                    false,
                    items.size() > 1,
                    null,
                    true,
                    0,
                    0,
                    0,
                    false,
                    null,
                    null,
                    0L,
                    false,
                    0L,
                    0L,
                    null);
            if (cb != null) {
                cb.onSent();
            }
        });
    }

    private static void sendPhotoFiles(Request req, List<File> photoFiles, String caption, Callback cb) {
        AndroidUtilities.runOnUIThread(() -> {
            // Telegram группирует максимум по 10 фотографий в один медиа-альбом.
            final int batchSize = 10;
            for (int start = 0; start < photoFiles.size(); start += batchSize) {
                final int end = Math.min(photoFiles.size(), start + batchSize);
                final ArrayList<SendMessagesHelper.SendingMediaInfo> items = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                    info.path = photoFiles.get(i).getAbsolutePath();
                    info.isVideo = false;
                    if (start == 0 && i == 0 && !caption.isEmpty()) {
                        info.caption = caption;
                    }
                    items.add(info);
                }
                SendMessagesHelper.prepareSendingMedia(
                        AccountInstance.getInstance(req.currentAccount),
                        items,
                        req.dialogId,
                        req.replyToMsg,
                        req.replyToTopMsg,
                        null,
                        null,
                        false,
                        items.size() > 1,
                        null,
                        true,
                        0,
                        0,
                        0,
                        false,
                        null,
                        null,
                        0L,
                        false,
                        0L,
                        0L,
                        null);
            }
            if (cb != null) {
                cb.onSent();
            }
        });
    }

    private static void handleFailure(Request req, String reason, Callback cb) {
        final int mode = PengramConfig.getLinkFallbackMode();
        if (mode == PengramConfig.LINK_FALLBACK_SEND) {
            sendPlainLink(req);
            AndroidUtilities.runOnUIThread(() -> {
                if (cb != null) {
                    cb.onFallbackSent(reason);
                }
            });
        } else if (mode == PengramConfig.LINK_FALLBACK_NONE) {
            AndroidUtilities.runOnUIThread(() -> {
                if (cb != null) {
                    cb.onFailedNoSend(req, reason);
                }
            });
        } else {
            AndroidUtilities.runOnUIThread(() -> {
                if (cb != null) {
                    cb.onFallbackChoiceRequested(req, reason);
                }
            });
        }
    }

    private static void sweepStaleFiles(File dir) {
        final File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        final long cutoff = System.currentTimeMillis() - TEMP_TTL_MS;
        for (File f : list) {
            if (f != null && f.isFile() && f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void cleanFiles(List<File> files) {
        for (File f : files) {
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void checkCancelled(Handle handle) {
        if (handle.cancel[0]) {
            throw new CancellationException("cancelled");
        }
    }

    private static void notifyPhase(Callback cb, int phase, float progress) {
        if (cb == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> cb.onPhase(phase, progress));
    }

    private static void notifyCancelled(Callback cb) {
        if (cb == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(cb::onCancelled);
    }
}
