package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import androidx.annotation.Nullable;

import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Оркестратор «Помощника ссылок»: фоновое разрешение ссылки TikTok / YouTube,
 * маршрутизация в интерактивный предпросмотр (фото, карусели, видео), выборочная
 * отправка 1..N фотографий альбомом, аппаратная склейка фото в MP4-видео со звуком
 * ({@link PengramPhotoSlideshow}), склейка адаптивных потоков ({@link PengramVideoMux}),
 * LRU-кэш последних 3 запросов TikTok и реестр фоновых задач по чатам.
 */
public final class PengramLinkAssistant {

    /** Жёсткий потолок на размер одного видео (1.5 ГБ) — защита от бесконечной загрузки. */
    public static final long MAX_VIDEO_BYTES = 1_500_000_000L;
    private static final long MAX_PHOTO_BYTES = 25L * 1024L * 1024L;
    private static final long MAX_AUDIO_BYTES = 30L * 1024L * 1024L;
    /** Старые временные файлы старше 30 минут зачищаются при следующем старте задачи. */
    private static final long TEMP_TTL_MS = 30L * 60L * 1000L;

    /** LRU-кэш последних 3 запросов TikTok (только для разумных по объёму файлов). */
    public static final int MAX_TIKTOK_CACHE_ENTRIES = 3;
    public static final long MAX_TIKTOK_CACHE_ENTRY_BYTES = 45L * 1024L * 1024L;
    public static final long MAX_TIKTOK_CACHE_TOTAL_BYTES = 90L * 1024L * 1024L;
    private static final long TIKTOK_CACHE_TTL_MS = 45L * 60L * 1000L;

    public static final int ACTION_AUTO = 0;
    public static final int ACTION_SEND_PHOTOS = 1;
    public static final int ACTION_STITCH_VIDEO = 2;
    public static final int ACTION_SEND_VIDEO = 3;
    public static final int ACTION_SEND_AUDIO = 4;

    public static final int PHASE_RESOLVE = 1;
    public static final int PHASE_DOWNLOAD = 2;
    public static final int PHASE_MUX = 3;
    public static final int PHASE_STITCH = 4;
    public static final int PHASE_SEND = 5;

    private static final ExecutorService TASK_EXECUTOR = Executors.newFixedThreadPool(3, r -> {
        final Thread t = new Thread(r, "PengramLinkTask");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService PREFETCH_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        final Thread t = new Thread(r, "PengramLinkPrefetch");
        t.setDaemon(true);
        return t;
    });

    private static final Object CACHE_LOCK = new Object();
    private static boolean orphanCacheSwept = false;
    private static final LinkedHashMap<String, String> TIKTOK_URL_ALIASES =
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 24;
                }
            };
    private static final LinkedHashMap<String, TikTokCacheEntry> TIKTOK_CACHE =
            new LinkedHashMap<>(8, 0.75f, true);
    private static final Map<String, List<Utilities.Callback<PengramLinkResolver.Resolved>>> INFLIGHT_PREFETCH =
            new HashMap<>();

    private static final Map<String, ActiveSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private static final class TikTokCacheEntry {
        final String cacheKey;
        PengramLinkResolver.Resolved resolved;
        long resolvedAtMs;
        final Map<Integer, File> photoFiles = new HashMap<>();
        File audioFile;
        File videoFile;

        TikTokCacheEntry(String cacheKey, PengramLinkResolver.Resolved resolved, long now) {
            this.cacheKey = cacheKey;
            this.resolved = resolved;
            this.resolvedAtMs = now;
        }

        boolean isExpired(long now) {
            return now - resolvedAtMs > TIKTOK_CACHE_TTL_MS;
        }

        long totalBytes() {
            long sum = 0L;
            for (File f : photoFiles.values()) {
                if (f != null && f.exists()) {
                    sum += f.length();
                }
            }
            if (audioFile != null && audioFile.exists()) {
                sum += audioFile.length();
            }
            if (videoFile != null && videoFile.exists()) {
                sum += videoFile.length();
            }
            return sum;
        }

        void destroyFiles() {
            for (File f : photoFiles.values()) {
                if (f != null && f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            photoFiles.clear();
            if (audioFile != null && audioFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                audioFile.delete();
            }
            audioFile = null;
            if (videoFile != null && videoFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                videoFile.delete();
            }
            videoFile = null;
        }
    }

    public static final class Request {
        public final int currentAccount;
        public final long dialogId;
        public final long topicId;
        public final String url;
        /** Оригинальный текст из поля ввода — для восстановления при отмене или отправки ссылкой. */
        public final String originalText;
        /** Дополнительная подпись от пользователя (текст вокруг ссылки), если была. */
        public final String extraCaption;
        public final MessageObject replyToMsg;
        public final MessageObject replyToTopMsg;
        @Nullable
        public final SendMessageChatArguments sendMessageChatArguments;
        public final long monoForumPeer;

        public Request(int currentAccount, long dialogId, String url, String originalText,
                       String extraCaption, MessageObject replyToMsg, MessageObject replyToTopMsg) {
            this(currentAccount, dialogId, 0L, url, originalText, extraCaption,
                    replyToMsg, replyToTopMsg, null, 0L);
        }

        public Request(int currentAccount, long dialogId, long topicId, String url, String originalText,
                       String extraCaption, MessageObject replyToMsg, MessageObject replyToTopMsg,
                       @Nullable SendMessageChatArguments sendMessageChatArguments, long monoForumPeer) {
            this.currentAccount = currentAccount;
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.url = url;
            this.originalText = originalText != null ? originalText : url;
            this.extraCaption = extraCaption != null ? extraCaption.trim() : "";
            this.replyToMsg = replyToMsg;
            this.replyToTopMsg = replyToTopMsg;
            this.sendMessageChatArguments = sendMessageChatArguments;
            this.monoForumPeer = monoForumPeer;
        }

        public String sessionKey() {
            return chatKey(currentAccount, dialogId, topicId);
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

    /** Фоновая сессия отправки медиа, привязанная к конкретному чату/топику. */
    public static final class ActiveSession {
        static final int PENDING_NONE = 0;
        static final int PENDING_PREVIEW = 1;
        static final int PENDING_FALLBACK_CHOICE = 2;
        static final int PENDING_FAILED_NO_SEND = 3;

        public final String key;
        public final Request request;
        public final Handle handle;
        public final long startedAtMs;
        public int phase = PHASE_RESOLVE;
        public float progress = -1f;

        int pendingEvent = PENDING_NONE;
        PengramLinkResolver.Resolved pendingResolved;
        String pendingReason;
        @Nullable
        Callback attachedCallback;

        ActiveSession(String key, Request request, Handle handle) {
            this.key = key;
            this.request = request;
            this.handle = handle;
            this.startedAtMs = System.currentTimeMillis();
        }
    }

    private PengramLinkAssistant() {
    }

    public static String chatKey(int currentAccount, long dialogId, long topicId) {
        return currentAccount + ":" + dialogId + ":" + topicId;
    }

    /** Битовая маска включённых пользователем площадок (1 << PLATFORM_*). */
    public static int getEnabledPlatformsMask() {
        int mask = 0;
        if (PengramConfig.isLinkPlatformYoutubeEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_YOUTUBE);
        if (PengramConfig.isLinkPlatformTiktokEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_TIKTOK);
        if (PengramConfig.isLinkPlatformInstagramEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_INSTAGRAM);
        if (PengramConfig.isLinkPlatformTwitterEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_TWITTER);
        if (PengramConfig.isLinkPlatformPinterestEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_PINTEREST);
        if (PengramConfig.isLinkPlatformVkEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_VK);
        if (PengramConfig.isLinkPlatformRutubeEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_RUTUBE);
        if (PengramConfig.isLinkPlatformRedditEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_REDDIT);
        if (PengramConfig.isLinkPlatformSoundcloudEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_SOUNDCLOUD);
        if (PengramConfig.isLinkPlatformYandexEnabled()) mask |= (1 << PengramLinkResolver.PLATFORM_YANDEX);
        return mask;
    }

    public static boolean isPlatformEnabled(int platform) {
        return platform > 0 && (getEnabledPlatformsMask() & (1 << platform)) != 0;
    }

    @Nullable
    public static PengramLinkResolver.FoundLink findSupportedLink(@Nullable CharSequence text) {
        if (text == null) {
            return null;
        }
        return PengramLinkResolver.findLink(text, getEnabledPlatformsMask());
    }

    public static String platformDisplayName(int platform, @Nullable String url) {
        switch (platform) {
            case PengramLinkResolver.PLATFORM_YOUTUBE:
                return PengramLinkResolver.isYouTubeShortsUrl(url) ? "YouTube Shorts" : "YouTube";
            case PengramLinkResolver.PLATFORM_TIKTOK:
                return "TikTok";
            case PengramLinkResolver.PLATFORM_INSTAGRAM:
                return (url != null && url.toLowerCase(Locale.US).contains("/reel")) ? "Instagram Reels" : "Instagram";
            case PengramLinkResolver.PLATFORM_TWITTER:
                return "X / Twitter";
            case PengramLinkResolver.PLATFORM_PINTEREST:
                return "Pinterest";
            case PengramLinkResolver.PLATFORM_VK:
                return (url != null && url.toLowerCase(Locale.US).contains("clip"))
                        ? LocaleController.getString(R.string.PengramLinkPlatformVkClips)
                        : "VK Видео";
            case PengramLinkResolver.PLATFORM_RUTUBE:
                return (url != null && url.toLowerCase(Locale.US).contains("/shorts/")) ? "Rutube Shorts" : "Rutube";
            case PengramLinkResolver.PLATFORM_REDDIT:
                return "Reddit";
            case PengramLinkResolver.PLATFORM_SOUNDCLOUD:
                return "SoundCloud";
            case PengramLinkResolver.PLATFORM_YANDEX:
                return LocaleController.getString(R.string.PengramLinkPlatformYandex);
            default:
                return "Media";
        }
    }

    public static Handle start(final Request req, final Callback cb) {
        return start(req, ExecutionOptions.auto(), cb);
    }

    public static Handle start(final Request req, final ExecutionOptions opts, final Callback cb) {
        final Handle handle = new Handle();
        final ExecutionOptions options = opts != null ? opts : ExecutionOptions.auto();
        TASK_EXECUTOR.execute(() -> execute(req, options, handle, cb));
        return handle;
    }

    /**
     * Запускает задачу отправки медиа с привязкой к чату {@code req.sessionKey()}.
     * Если пользователь выйдет в другой чат, загрузка и отправка продолжатся в исходный чат,
     * а при возврате в чат островок автоматически переподключится к живому прогрессу.
     */
    public static ActiveSession startSession(final Request req,
                                             final ExecutionOptions opts,
                                             @Nullable final Callback initialUiCallback) {
        final String key = req.sessionKey();
        final ActiveSession prev = ACTIVE_SESSIONS.remove(key);
        if (prev != null && !prev.handle.isCancelled()) {
            prev.handle.cancel();
        }

        final Handle handle = new Handle();
        final ActiveSession session = new ActiveSession(key, req, handle);
        session.attachedCallback = initialUiCallback;
        ACTIVE_SESSIONS.put(key, session);

        final ExecutionOptions options = opts != null ? opts : ExecutionOptions.auto();
        final Callback dispatcher = new Callback() {
            @Override
            public void onPhase(int phase, float progress) {
                if (handle.isCancelled() || ACTIVE_SESSIONS.get(key) != session) {
                    return;
                }
                session.phase = phase;
                session.progress = progress;
                if (session.attachedCallback != null) {
                    session.attachedCallback.onPhase(phase, progress);
                }
            }

            @Override
            public void onSent() {
                ACTIVE_SESSIONS.remove(key, session);
                if (session.attachedCallback != null) {
                    session.attachedCallback.onSent();
                }
            }

            @Override
            public void onPreviewRequested(Request request, PengramLinkResolver.Resolved resolved) {
                if (handle.isCancelled() || ACTIVE_SESSIONS.get(key) != session) {
                    return;
                }
                if (session.attachedCallback != null) {
                    ACTIVE_SESSIONS.remove(key, session);
                    session.attachedCallback.onPreviewRequested(request, resolved);
                } else {
                    session.pendingEvent = ActiveSession.PENDING_PREVIEW;
                    session.pendingResolved = resolved;
                }
            }

            @Override
            public void onFallbackSent(String reason) {
                ACTIVE_SESSIONS.remove(key, session);
                if (session.attachedCallback != null) {
                    session.attachedCallback.onFallbackSent(reason);
                }
            }

            @Override
            public void onFailedNoSend(Request request, String reason) {
                if (handle.isCancelled() || ACTIVE_SESSIONS.get(key) != session) {
                    return;
                }
                if (session.attachedCallback != null) {
                    ACTIVE_SESSIONS.remove(key, session);
                    session.attachedCallback.onFailedNoSend(request, reason);
                } else {
                    session.pendingEvent = ActiveSession.PENDING_FAILED_NO_SEND;
                    session.pendingReason = reason;
                }
            }

            @Override
            public void onFallbackChoiceRequested(Request request, String reason) {
                if (handle.isCancelled() || ACTIVE_SESSIONS.get(key) != session) {
                    return;
                }
                if (session.attachedCallback != null) {
                    ACTIVE_SESSIONS.remove(key, session);
                    session.attachedCallback.onFallbackChoiceRequested(request, reason);
                } else {
                    session.pendingEvent = ActiveSession.PENDING_FALLBACK_CHOICE;
                    session.pendingReason = reason;
                }
            }

            @Override
            public void onCancelled() {
                ACTIVE_SESSIONS.remove(key, session);
                if (session.attachedCallback != null) {
                    session.attachedCallback.onCancelled();
                }
            }
        };

        TASK_EXECUTOR.execute(() -> execute(req, options, handle, dispatcher));
        return session;
    }

    /**
     * Подключает UI чата к активной фоновой сессии (если она выполняется или ждёт показа шторки).
     * Вызывать на UI-потоке при входе/возврате в чат.
     */
    @Nullable
    public static ActiveSession attachSession(final String key, final Callback uiCallback) {
        if (key == null) {
            return null;
        }
        final ActiveSession session = ACTIVE_SESSIONS.get(key);
        if (session == null) {
            return null;
        }
        if (session.handle.isCancelled()
                || System.currentTimeMillis() - session.startedAtMs > TEMP_TTL_MS) {
            ACTIVE_SESSIONS.remove(key, session);
            return null;
        }
        session.attachedCallback = uiCallback;
        if (session.pendingEvent != ActiveSession.PENDING_NONE) {
            ACTIVE_SESSIONS.remove(key, session);
            final int ev = session.pendingEvent;
            session.pendingEvent = ActiveSession.PENDING_NONE;
            if (uiCallback != null) {
                if (ev == ActiveSession.PENDING_PREVIEW && session.pendingResolved != null) {
                    uiCallback.onPreviewRequested(session.request, session.pendingResolved);
                } else if (ev == ActiveSession.PENDING_FALLBACK_CHOICE) {
                    uiCallback.onFallbackChoiceRequested(session.request,
                            session.pendingReason != null ? session.pendingReason : "error");
                } else if (ev == ActiveSession.PENDING_FAILED_NO_SEND) {
                    uiCallback.onFailedNoSend(session.request,
                            session.pendingReason != null ? session.pendingReason : "error");
                }
            }
            return null;
        }
        if (uiCallback != null) {
            uiCallback.onPhase(session.phase, session.progress);
        }
        return session;
    }

    /** Отвязывает UI при уходе из чата, НЕ отменяя саму фоновую загрузку и отправку. */
    public static void detachSession(@Nullable String key, @Nullable Callback uiCallback) {
        if (key == null) {
            return;
        }
        final ActiveSession session = ACTIVE_SESSIONS.get(key);
        if (session != null && (uiCallback == null || session.attachedCallback == uiCallback)) {
            session.attachedCallback = null;
        }
    }

    /** Явная отмена задачи пользователем (нажатие ✕ на островке). */
    public static void cancelSession(@Nullable String key) {
        if (key == null) {
            return;
        }
        final ActiveSession session = ACTIVE_SESSIONS.remove(key);
        if (session != null) {
            session.handle.cancel();
            if (session.attachedCallback != null) {
                final Callback cb = session.attachedCallback;
                session.attachedCallback = null;
                cb.onCancelled();
            }
        }
    }

    @Nullable
    public static ActiveSession getActiveSession(@Nullable String key) {
        if (key == null) {
            return null;
        }
        final ActiveSession session = ACTIVE_SESSIONS.get(key);
        if (session != null && session.handle.isCancelled()) {
            ACTIVE_SESSIONS.remove(key, session);
            return null;
        }
        return session;
    }

    // --- Кэш последних 3 запросов TikTok ---

    private static String normalizeUrlForAlias(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        final int hashIdx = s.indexOf('#');
        if (hashIdx >= 0) {
            s = s.substring(0, hashIdx);
        }
        final int qIdx = s.indexOf('?');
        if (qIdx >= 0 && !s.toLowerCase(Locale.US).contains("item_id=")) {
            s = s.substring(0, qIdx);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Nullable
    private static String deriveTikTokCacheKey(@Nullable String url,
                                               @Nullable PengramLinkResolver.Resolved resolved) {
        String postId = PengramLinkResolver.extractTikTokPostId(url);
        if (postId == null && resolved != null) {
            postId = PengramLinkResolver.extractTikTokPostId(resolved.referer);
        }
        if (postId != null && !postId.isEmpty()) {
            return "tt:" + postId;
        }
        final String norm = normalizeUrlForAlias(url);
        return norm.isEmpty() ? null : "tturl:" + norm;
    }

    @Nullable
    private static TikTokCacheEntry lookupTikTokEntryLocked(@Nullable String url,
                                                            @Nullable PengramLinkResolver.Resolved resolved) {
        final long now = System.currentTimeMillis();
        String key = null;
        final String postId = PengramLinkResolver.extractTikTokPostId(url);
        if (postId != null) {
            key = "tt:" + postId;
        }
        if (key == null && url != null) {
            key = TIKTOK_URL_ALIASES.get(normalizeUrlForAlias(url));
        }
        if (key == null && resolved != null) {
            key = deriveTikTokCacheKey(url, resolved);
        }
        if (key == null) {
            return null;
        }
        final TikTokCacheEntry entry = TIKTOK_CACHE.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(now)) {
            TIKTOK_CACHE.remove(key);
            entry.destroyFiles();
            return null;
        }
        return entry;
    }

    private static void trimTikTokCacheLocked(@Nullable String protectedKey) {
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<String, TikTokCacheEntry>> expIt = TIKTOK_CACHE.entrySet().iterator();
        while (expIt.hasNext()) {
            final Map.Entry<String, TikTokCacheEntry> e = expIt.next();
            if (e.getValue().isExpired(now)) {
                e.getValue().destroyFiles();
                expIt.remove();
            }
        }
        while (TIKTOK_CACHE.size() > MAX_TIKTOK_CACHE_ENTRIES) {
            final Iterator<Map.Entry<String, TikTokCacheEntry>> it = TIKTOK_CACHE.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            final Map.Entry<String, TikTokCacheEntry> eldest = it.next();
            if (protectedKey != null && protectedKey.equals(eldest.getKey()) && it.hasNext()) {
                final Map.Entry<String, TikTokCacheEntry> second = it.next();
                second.getValue().destroyFiles();
                it.remove();
            } else {
                eldest.getValue().destroyFiles();
                it.remove();
            }
        }
        long total = 0L;
        for (TikTokCacheEntry entry : TIKTOK_CACHE.values()) {
            total += entry.totalBytes();
        }
        if (total > MAX_TIKTOK_CACHE_TOTAL_BYTES) {
            final Iterator<Map.Entry<String, TikTokCacheEntry>> it = TIKTOK_CACHE.entrySet().iterator();
            while (it.hasNext() && total > MAX_TIKTOK_CACHE_TOTAL_BYTES) {
                final Map.Entry<String, TikTokCacheEntry> eldest = it.next();
                if (protectedKey != null && protectedKey.equals(eldest.getKey()) && TIKTOK_CACHE.size() > 1) {
                    continue;
                }
                total -= eldest.getValue().totalBytes();
                eldest.getValue().destroyFiles();
                it.remove();
            }
        }
    }

    private static TikTokCacheEntry registerTikTokResolved(String url, PengramLinkResolver.Resolved resolved) {
        if (resolved == null || resolved.platform != PengramLinkResolver.PLATFORM_TIKTOK) {
            return null;
        }
        synchronized (CACHE_LOCK) {
            final long now = System.currentTimeMillis();
            final String key = deriveTikTokCacheKey(url, resolved);
            if (key == null) {
                return null;
            }
            final String normUrl = normalizeUrlForAlias(url);
            if (!normUrl.isEmpty()) {
                TIKTOK_URL_ALIASES.put(normUrl, key);
            }
            TikTokCacheEntry entry = TIKTOK_CACHE.get(key);
            if (entry == null) {
                entry = new TikTokCacheEntry(key, resolved, now);
                TIKTOK_CACHE.put(key, entry);
            } else {
                entry.resolved = resolved;
                entry.resolvedAtMs = now;
            }
            trimTikTokCacheLocked(key);
            return entry;
        }
    }

    @Nullable
    public static PengramLinkResolver.Resolved peekCachedTikTok(@Nullable String url) {
        if (url == null || PengramLinkResolver.platformOf(url) != PengramLinkResolver.PLATFORM_TIKTOK) {
            return null;
        }
        synchronized (CACHE_LOCK) {
            final TikTokCacheEntry entry = lookupTikTokEntryLocked(url, null);
            return entry != null ? entry.resolved : null;
        }
    }

    @Nullable
    public static File getCachedTikTokPhotoFile(@Nullable String url, int photoIndex) {
        if (url == null || photoIndex < 0) {
            return null;
        }
        synchronized (CACHE_LOCK) {
            final TikTokCacheEntry entry = lookupTikTokEntryLocked(url, null);
            if (entry == null) {
                return null;
            }
            final File f = entry.photoFiles.get(photoIndex);
            if (f != null && f.exists() && f.length() > 128L) {
                return f;
            }
            return null;
        }
    }

    /**
     * Фоновое предразрешение ссылки TikTok для мгновенного открытия предпросмотра
     * и точного определения типа поста (фото/видео) на островке.
     */
    public static void prefetchTikTokMetadata(@Nullable final String url,
                                              @Nullable final Utilities.Callback<PengramLinkResolver.Resolved> onResolved) {
        if (url == null || PengramLinkResolver.platformOf(url) != PengramLinkResolver.PLATFORM_TIKTOK) {
            return;
        }
        final PengramLinkResolver.Resolved existing = peekCachedTikTok(url);
        if (existing != null) {
            if (onResolved != null) {
                AndroidUtilities.runOnUIThread(() -> onResolved.run(existing));
            }
            return;
        }
        final String norm = normalizeUrlForAlias(url);
        if (norm.isEmpty()) {
            return;
        }
        synchronized (CACHE_LOCK) {
            List<Utilities.Callback<PengramLinkResolver.Resolved>> waiters = INFLIGHT_PREFETCH.get(norm);
            if (waiters != null) {
                if (onResolved != null) {
                    waiters.add(onResolved);
                }
                return;
            }
            waiters = new ArrayList<>(2);
            if (onResolved != null) {
                waiters.add(onResolved);
            }
            INFLIGHT_PREFETCH.put(norm, waiters);
        }
        PREFETCH_EXECUTOR.execute(() -> {
            PengramLinkResolver.Resolved resolved = null;
            try {
                resolved = PengramLinkResolver.resolve(url);
                registerTikTokResolved(url, resolved);
            } catch (Throwable ignore) {
            }
            final PengramLinkResolver.Resolved result = resolved;
            final List<Utilities.Callback<PengramLinkResolver.Resolved>> callbacks;
            synchronized (CACHE_LOCK) {
                callbacks = INFLIGHT_PREFETCH.remove(norm);
            }
            if (result != null && callbacks != null && !callbacks.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    for (Utilities.Callback<PengramLinkResolver.Resolved> cb : callbacks) {
                        if (cb != null) {
                            cb.run(result);
                        }
                    }
                });
            }
        });
    }

    private static File ensureTikTokCacheDir(File workDir) {
        final File dir = new File(workDir, "tt_cache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        synchronized (CACHE_LOCK) {
            if (!orphanCacheSwept) {
                orphanCacheSwept = true;
                final File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f != null && f.isFile()) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
            }
        }
        return dir;
    }

    @Nullable
    private static File copyCachedPhotoIfPresent(String url, PengramLinkResolver.Resolved resolved,
                                                 int photoIndex, File dstFile) {
        synchronized (CACHE_LOCK) {
            final TikTokCacheEntry entry = lookupTikTokEntryLocked(url, resolved);
            if (entry == null) {
                return null;
            }
            final File cached = entry.photoFiles.get(photoIndex);
            if (cached == null || !cached.exists() || cached.length() <= 128L) {
                return null;
            }
            try {
                copyFile(cached, dstFile);
                return dstFile;
            } catch (Throwable ignore) {
                return null;
            }
        }
    }

    private static void cacheTikTokPhoto(File cacheDir, String url, PengramLinkResolver.Resolved resolved,
                                         int photoIndex, File jpgFile) {
        if (resolved.platform != PengramLinkResolver.PLATFORM_TIKTOK
                || jpgFile == null || !jpgFile.exists()
                || jpgFile.length() <= 128L
                || jpgFile.length() > MAX_TIKTOK_CACHE_ENTRY_BYTES) {
            return;
        }
        synchronized (CACHE_LOCK) {
            final TikTokCacheEntry entry = registerTikTokResolved(url, resolved);
            if (entry == null) {
                return;
            }
            final File prev = entry.photoFiles.get(photoIndex);
            final long prevLen = (prev != null && prev.exists()) ? prev.length() : 0L;
            if (entry.totalBytes() - prevLen + jpgFile.length() > MAX_TIKTOK_CACHE_ENTRY_BYTES) {
                return;
            }
            final String safeName = entry.cacheKey.replaceAll("[^a-zA-Z0-9_-]", "_") + "_p" + photoIndex + ".jpg";
            final File cached = new File(cacheDir, safeName);
            try {
                copyFile(jpgFile, cached);
                entry.photoFiles.put(photoIndex, cached);
                trimTikTokCacheLocked(entry.cacheKey);
            } catch (Throwable ignore) {
                //noinspection ResultOfMethodCallIgnored
                cached.delete();
            }
        }
    }

    @Nullable
    private static File copyCachedAudioIfPresent(String url, PengramLinkResolver.Resolved resolved, File dstFile) {
        synchronized (CACHE_LOCK) {
            final TikTokCacheEntry entry = lookupTikTokEntryLocked(url, resolved);
            if (entry == null || entry.audioFile == null
                    || !entry.audioFile.exists() || entry.audioFile.length() <= 1024L) {
                return null;
            }
            try {
                copyFile(entry.audioFile, dstFile);
                return dstFile;
            } catch (Throwable ignore) {
                return null;
            }
        }
    }

    private static void cacheTikTokAudio(File cacheDir, String url, PengramLinkResolver.Resolved resolved, File audioSrc) {
        if (resolved.platform != PengramLinkResolver.PLATFORM_TIKTOK
                || audioSrc == null || !audioSrc.exists()
                || audioSrc.length() <= 1024L
                || audioSrc.length() > MAX_TIKTOK_CACHE_ENTRY_BYTES) {
            return;
        }
        synchronized (CACHE_LOCK) {
            final TikTokCacheEntry entry = registerTikTokResolved(url, resolved);
            if (entry == null) {
                return;
            }
            final long prevLen = (entry.audioFile != null && entry.audioFile.exists()) ? entry.audioFile.length() : 0L;
            if (entry.totalBytes() - prevLen + audioSrc.length() > MAX_TIKTOK_CACHE_ENTRY_BYTES) {
                return;
            }
            final String safeName = entry.cacheKey.replaceAll("[^a-zA-Z0-9_-]", "_") + "_aud.m4a";
            final File cached = new File(cacheDir, safeName);
            try {
                copyFile(audioSrc, cached);
                entry.audioFile = cached;
                trimTikTokCacheLocked(entry.cacheKey);
            } catch (Throwable ignore) {
                //noinspection ResultOfMethodCallIgnored
                cached.delete();
            }
        }
    }

    @Nullable
    private static File copyCachedVideoIfPresent(String url, PengramLinkResolver.Resolved resolved, File dstFile) {
        if (resolved.platform != PengramLinkResolver.PLATFORM_TIKTOK) {
            return null;
        }
        synchronized (CACHE_LOCK) {
            final TikTokCacheEntry entry = lookupTikTokEntryLocked(url, resolved);
            if (entry == null || entry.videoFile == null
                    || !entry.videoFile.exists() || entry.videoFile.length() < 4096L) {
                return null;
            }
            try {
                copyFile(entry.videoFile, dstFile);
                return dstFile;
            } catch (Throwable ignore) {
                return null;
            }
        }
    }

    private static void cacheTikTokVideo(File cacheDir, String url, PengramLinkResolver.Resolved resolved, File videoSrc) {
        if (resolved.platform != PengramLinkResolver.PLATFORM_TIKTOK
                || videoSrc == null || !videoSrc.exists()
                || videoSrc.length() < 4096L
                || videoSrc.length() > MAX_TIKTOK_CACHE_ENTRY_BYTES) {
            return;
        }
        synchronized (CACHE_LOCK) {
            final TikTokCacheEntry entry = registerTikTokResolved(url, resolved);
            if (entry == null) {
                return;
            }
            final long prevLen = (entry.videoFile != null && entry.videoFile.exists()) ? entry.videoFile.length() : 0L;
            if (entry.totalBytes() - prevLen + videoSrc.length() > MAX_TIKTOK_CACHE_ENTRY_BYTES) {
                return;
            }
            final String safeName = entry.cacheKey.replaceAll("[^a-zA-Z0-9_-]", "_") + "_vid.mp4";
            final File cached = new File(cacheDir, safeName);
            try {
                copyFile(videoSrc, cached);
                entry.videoFile = cached;
                trimTikTokCacheLocked(entry.cacheKey);
            } catch (Throwable ignore) {
                //noinspection ResultOfMethodCallIgnored
                cached.delete();
            }
        }
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
            params.sendMessageChatArguments = req.sendMessageChatArguments;
            params.monoForumPeer = req.monoForumPeer;
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
        final File cacheDir = ensureTikTokCacheDir(workDir);
        sweepStaleFiles(workDir);

        final List<File> scratchToClean = new ArrayList<>();
        try {
            final PengramLinkResolver.Resolved resolved;
            if (opts.preResolved != null) {
                resolved = opts.preResolved;
                if (resolved.platform == PengramLinkResolver.PLATFORM_TIKTOK) {
                    registerTikTokResolved(req.url, resolved);
                }
            } else {
                final PengramLinkResolver.Resolved cached = peekCachedTikTok(req.url);
                if (cached != null) {
                    resolved = cached;
                } else {
                    notifyPhase(cb, PHASE_RESOLVE, 0f);
                    checkCancelled(handle);
                    resolved = PengramLinkResolver.resolve(req.url, PengramConfig.getLinkMaxVideoHeight());
                    checkCancelled(handle);
                    if (resolved.platform == PengramLinkResolver.PLATFORM_TIKTOK) {
                        registerTikTokResolved(req.url, resolved);
                    }
                }
            }

            if (shouldOpenPreview(resolved, opts)) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (cb != null && !handle.isCancelled()) {
                        cb.onPreviewRequested(req, resolved);
                    }
                });
                return;
            }

            final boolean wantAudioOnly = opts.action == ACTION_SEND_AUDIO
                    || resolved.isAudio()
                    || (opts.action == ACTION_AUTO
                            && !resolved.isPhotos()
                            && PengramConfig.getLinkDefaultFormat() == PengramConfig.LINK_FORMAT_AUDIO);

            if (wantAudioOnly) {
                deliverAudio(req, resolved, opts, workDir, scratchToClean, handle, cb);
            } else if (resolved.isPhotos()) {
                deliverPhotos(req, resolved, opts, workDir, cacheDir, scratchToClean, handle, cb);
            } else {
                deliverVideo(req, resolved, opts, workDir, cacheDir, scratchToClean, handle, cb);
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

    public static boolean shouldOpenPreview(PengramLinkResolver.Resolved resolved, ExecutionOptions opts) {
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
            if (resolved.photos.size() > 1 && PengramConfig.getLinkPhotoStitchMode() == PengramConfig.LINK_STITCH_ASK) {
                return true;
            }
            return false;
        } else {
            return previewMode == PengramConfig.LINK_PREVIEW_VIDEOS || previewMode == PengramConfig.LINK_PREVIEW_ALL;
        }
    }

    private static void deliverPhotos(Request req, PengramLinkResolver.Resolved initialResolved,
                                      ExecutionOptions opts, File workDir, File cacheDir,
                                      List<File> scratchToClean, Handle handle, Callback cb) throws Exception {
        PengramLinkResolver.Resolved resolved = initialResolved;
        final List<Integer> chosenIndices = new ArrayList<>();
        if (opts.selectedPhotoIndices != null && !opts.selectedPhotoIndices.isEmpty()) {
            for (Integer idx : opts.selectedPhotoIndices) {
                if (idx != null && idx >= 0 && idx < resolved.photos.size()) {
                    chosenIndices.add(idx);
                }
            }
        } else {
            for (int i = 0; i < resolved.photos.size(); i++) {
                chosenIndices.add(i);
            }
        }
        if (chosenIndices.isEmpty()) {
            throw new Exception("no photos selected");
        }

        final boolean stitchIntoVideo;
        if (opts.action == ACTION_STITCH_VIDEO) {
            stitchIntoVideo = true;
        } else if (opts.action == ACTION_SEND_PHOTOS) {
            stitchIntoVideo = false;
        } else {
            stitchIntoVideo = chosenIndices.size() > 1
                    && PengramConfig.getLinkPhotoStitchMode() == PengramConfig.LINK_STITCH_ALWAYS;
        }

        final long stamp = System.currentTimeMillis();
        final List<File> normalizedJpegs = new ArrayList<>(chosenIndices.size());
        notifyPhase(cb, PHASE_DOWNLOAD, 0f);
        boolean refreshedOnce = false;

        for (int i = 0; i < chosenIndices.size(); i++) {
            checkCancelled(handle);
            final int origIdx = chosenIndices.get(i);
            final File jpgFile = new File(workDir, "link_" + stamp + "_" + i + ".jpg");

            if (copyCachedPhotoIfPresent(req.url, resolved, origIdx, jpgFile) != null) {
                if (stitchIntoVideo) {
                    scratchToClean.add(jpgFile);
                }
                normalizedJpegs.add(jpgFile);
                notifyPhase(cb, PHASE_DOWNLOAD, (i + 1f) / chosenIndices.size());
                continue;
            }

            final File rawFile = new File(workDir, "link_" + stamp + "_p" + i + ".raw");
            scratchToClean.add(rawFile);

            final int photoIdx = i;
            final int totalPhotos = chosenIndices.size();
            try {
                final PengramLinkResolver.PhotoItem item = resolved.photos.get(origIdx);
                PengramLinkResolver.download(
                        item.imageUrl, rawFile, MAX_PHOTO_BYTES,
                        resolved.cookies, resolved.referer, resolved.userAgent, false,
                        handle.cancel,
                        (written, total) -> {
                            final float sub = total > 0 ? Math.min(1f, (float) written / total) : 0.5f;
                            notifyPhase(cb, PHASE_DOWNLOAD, (photoIdx + sub) / totalPhotos);
                        });
            } catch (CancellationException ce) {
                throw ce;
            } catch (Exception dlErr) {
                if (!refreshedOnce && resolved.platform == PengramLinkResolver.PLATFORM_TIKTOK) {
                    refreshedOnce = true;
                    rawFile.delete();
                    final PengramLinkResolver.Resolved fresh = PengramLinkResolver.resolve(req.url);
                    if (fresh.isPhotos() && origIdx < fresh.photos.size()) {
                        resolved = fresh;
                        registerTikTokResolved(req.url, resolved);
                        final PengramLinkResolver.PhotoItem item = resolved.photos.get(origIdx);
                        PengramLinkResolver.download(
                                item.imageUrl, rawFile, MAX_PHOTO_BYTES,
                                resolved.cookies, resolved.referer, resolved.userAgent, false,
                                handle.cancel,
                                (written, total) -> {
                                    final float sub = total > 0 ? Math.min(1f, (float) written / total) : 0.5f;
                                    notifyPhase(cb, PHASE_DOWNLOAD, (photoIdx + sub) / totalPhotos);
                                });
                    } else {
                        throw dlErr;
                    }
                } else {
                    throw dlErr;
                }
            }
            checkCancelled(handle);

            normalizePhotoToJpeg(rawFile, jpgFile);
            rawFile.delete();
            cacheTikTokPhoto(cacheDir, req.url, resolved, origIdx, jpgFile);
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
                if (copyCachedAudioIfPresent(req.url, resolved, rawAudio) != null) {
                    audioFile = rawAudio;
                } else {
                    try {
                        PengramLinkResolver.download(
                                resolved.audioUrl, rawAudio, MAX_AUDIO_BYTES,
                                resolved.cookies, resolved.referer, resolved.userAgent, false,
                                handle.cancel, null);
                        if (rawAudio.exists() && rawAudio.length() > 1024L) {
                            audioFile = rawAudio;
                            cacheTikTokAudio(cacheDir, req.url, resolved, rawAudio);
                        }
                    } catch (CancellationException ce) {
                        throw ce;
                    } catch (Throwable ignoreAudioErr) {
                        rawAudio.delete();
                    }
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
                                     ExecutionOptions opts, File workDir, File cacheDir,
                                     List<File> scratchToClean, Handle handle, Callback cb) throws Exception {
        if (primary.platform == PengramLinkResolver.PLATFORM_TIKTOK) {
            final File fastOut = new File(workDir, "link_" + System.currentTimeMillis() + ".mp4");
            if (copyCachedVideoIfPresent(req.url, primary, fastOut) != null) {
                checkCancelled(handle);
                notifyPhase(cb, PHASE_SEND, 1f);
                final String caption = buildCaption(req, primary, opts.captionModeOverride);
                sendVideoFile(req, fastOut, caption, cb);
                return;
            }
        }

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
                    if (candidate.hlsStream) {
                        final File rawTs = new File(workDir, "link_" + stamp + ".raw.ts");
                        scratchToClean.add(rawTs);
                        try {
                            PengramLinkResolver.download(
                                    candidate.urls.get(0), rawTs, MAX_VIDEO_BYTES,
                                    candidate.cookies, candidate.referer,
                                    candidate.userAgent, false, true, handle.cancel,
                                    (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
                            checkCancelled(handle);
                            notifyPhase(cb, PHASE_MUX, -1f);
                            PengramVideoMux.remuxToMp4(rawTs, out, handle.cancel);
                            cleanFiles(scratchToClean);
                            scratchToClean.clear();
                            validateDownloadedVideo(out);
                            finalFile = out;
                        } catch (Throwable dlErr) {
                            out.delete();
                            throw dlErr;
                        }
                    } else {
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

        if (finalFile == null && primary.platform == PengramLinkResolver.PLATFORM_TIKTOK) {
            // Если URL-адреса в закэшированном Resolved успели протухнуть — обновляем метаданные один раз.
            try {
                final PengramLinkResolver.Resolved fresh = PengramLinkResolver.resolve(req.url);
                registerTikTokResolved(req.url, fresh);
                if (!fresh.isPhotos() && !fresh.urls.isEmpty()) {
                    final File out = new File(workDir, "link_" + System.currentTimeMillis() + ".mp4");
                    try {
                        PengramLinkResolver.download(
                                fresh.urls.get(0), out, MAX_VIDEO_BYTES,
                                fresh.cookies, fresh.referer,
                                fresh.userAgent, fresh.rangeChunked, handle.cancel,
                                (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
                        validateDownloadedVideo(out);
                        finalFile = out;
                    } catch (Throwable dlErr) {
                        out.delete();
                    }
                }
            } catch (Throwable ignore) {
            }
        }

        if (finalFile == null) {
            throw lastErr != null ? lastErr : new Exception("empty output");
        }

        cacheTikTokVideo(cacheDir, req.url, primary, finalFile);

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
                    req.sendMessageChatArguments,
                    0L,
                    false,
                    0L,
                    req.monoForumPeer,
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
                        req.sendMessageChatArguments,
                        0L,
                        false,
                        0L,
                        req.monoForumPeer,
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

    // --- Режим «Только звук (MP3 / M4A)» + отправка как музыкального трека с обложкой ---

    private static File prepareAudioTrackFile(String sourceUrl,
                                              PengramLinkResolver.Resolved resolved,
                                              File targetDir,
                                              List<File> scratchToClean,
                                              Handle handle,
                                              Callback cb) throws Exception {
        final long stamp = System.currentTimeMillis();
        notifyPhase(cb, PHASE_DOWNLOAD, 0f);

        if (resolved.isAudio()) {
            if (!resolved.hlsStream) {
                final File outMp3 = new File(targetDir, "audio_" + stamp + ".mp3");
                PengramLinkResolver.download(
                        resolved.urls.get(0), outMp3, MAX_AUDIO_BYTES * 3L,
                        resolved.cookies, resolved.referer, resolved.userAgent, false, false,
                        handle.cancel,
                        (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
                if (outMp3.length() < 1024L) {
                    outMp3.delete();
                    throw new Exception("audio empty");
                }
                return outMp3;
            } else {
                final File rawTs = new File(targetDir, "audio_" + stamp + ".ts");
                final File outM4a = new File(targetDir, "audio_" + stamp + ".m4a");
                scratchToClean.add(rawTs);
                PengramLinkResolver.download(
                        resolved.urls.get(0), rawTs, MAX_AUDIO_BYTES * 3L,
                        resolved.cookies, resolved.referer, resolved.userAgent, false, true,
                        handle.cancel,
                        (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
                checkCancelled(handle);
                notifyPhase(cb, PHASE_MUX, -1f);
                PengramVideoMux.extractAudioToM4a(rawTs, outM4a, handle.cancel);
                rawTs.delete();
                return outM4a;
            }
        }

        // Если у ролика/карусели уже есть отдельная аудиодорожка (YouTube m4a, Reddit DASH_AUDIO, TikTok звук)
        if (resolved.audioUrl != null && !resolved.audioUrl.isEmpty()) {
            final File rawAud = new File(targetDir, "audio_" + stamp + ".raw");
            scratchToClean.add(rawAud);
            PengramLinkResolver.download(
                    resolved.audioUrl, rawAud, MAX_AUDIO_BYTES * 3L,
                    resolved.cookies, resolved.referer, resolved.userAgent, resolved.rangeChunked, false,
                    handle.cancel,
                    (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
            checkCancelled(handle);
            if (isMp3Header(rawAud)) {
                final File outMp3 = new File(targetDir, "audio_" + stamp + ".mp3");
                if (!rawAud.renameTo(outMp3)) {
                    copyFile(rawAud, outMp3);
                    rawAud.delete();
                }
                return outMp3;
            }
            notifyPhase(cb, PHASE_MUX, -1f);
            final File outM4a = new File(targetDir, "audio_" + stamp + ".m4a");
            PengramVideoMux.extractAudioToM4a(rawAud, outM4a, handle.cancel);
            rawAud.delete();
            return outM4a;
        }

        // Иначе скачиваем поток ролика и без потерь вынимаем из него AAC-дорожку в .m4a
        if (resolved.urls.isEmpty()) {
            throw new Exception("no audio in post");
        }
        final String streamUrl = resolved.needsMux && resolved.urls.size() >= 2
                ? resolved.urls.get(1) : resolved.urls.get(0);
        final boolean isHls = !resolved.needsMux && resolved.hlsStream;
        final File rawMedia = new File(targetDir, "audio_" + stamp + (isHls ? ".ts" : ".mp4"));
        scratchToClean.add(rawMedia);
        PengramLinkResolver.download(
                streamUrl, rawMedia, MAX_VIDEO_BYTES,
                resolved.cookies, resolved.referer, resolved.userAgent, resolved.rangeChunked, isHls,
                handle.cancel,
                (w, t) -> notifyPhase(cb, PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
        checkCancelled(handle);
        notifyPhase(cb, PHASE_MUX, -1f);
        final File outM4a = new File(targetDir, "audio_" + stamp + ".m4a");
        PengramVideoMux.extractAudioToM4a(rawMedia, outM4a, handle.cancel);
        rawMedia.delete();
        return outM4a;
    }

    private static boolean isMp3Header(File f) {
        if (f == null || !f.exists() || f.length() < 16L) return false;
        final byte[] h = new byte[4];
        try (FileInputStream in = new FileInputStream(f)) {
            if (in.read(h) < 4) return false;
        } catch (Throwable ignore) {
            return false;
        }
        if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return true;
        return (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0;
    }

    private static void deliverAudio(Request req, PengramLinkResolver.Resolved resolved,
                                     ExecutionOptions opts, File workDir,
                                     List<File> scratchToClean, Handle handle, Callback cb) throws Exception {
        final File audioFile = prepareAudioTrackFile(req.url, resolved, workDir, scratchToClean, handle, cb);
        File coverTemp = null;
        if (resolved.thumbnailUrl != null && !resolved.thumbnailUrl.isEmpty()) {
            final File cand = new File(workDir, "cover_" + System.currentTimeMillis() + ".raw");
            try {
                PengramLinkResolver.download(resolved.thumbnailUrl, cand, 8L * 1024L * 1024L,
                        resolved.cookies, resolved.referer, resolved.userAgent, false, handle.cancel, null);
                if (cand.exists() && cand.length() > 256L) {
                    coverTemp = cand;
                } else {
                    cand.delete();
                }
            } catch (Throwable ignore) {
                cand.delete();
            }
        }
        cleanFiles(scratchToClean);
        scratchToClean.clear();
        checkCancelled(handle);
        notifyPhase(cb, PHASE_SEND, 1f);

        final int capMode = opts.captionModeOverride >= 0 ? opts.captionModeOverride : PengramConfig.getLinkCaptionMode();
        final String caption = resolved.isAudio()
                ? (req.extraCaption != null ? req.extraCaption : "")
                : (capMode == PengramConfig.LINK_CAPTION_NONE ? (req.extraCaption != null ? req.extraCaption : "") : buildCaption(req, resolved, capMode));
        sendAudioFile(req, resolved, audioFile, coverTemp, caption, cb);
    }

    private static void sendAudioFile(Request req, PengramLinkResolver.Resolved resolved,
                                      File audioFile, @Nullable File coverTemp,
                                      String caption, Callback cb) {
        final boolean isMp3 = audioFile.getName().toLowerCase(Locale.US).endsWith(".mp3");
        double durationSec = resolved.durationMs > 0 ? (resolved.durationMs / 1000.0) : 0.0;
        if (durationSec <= 0.1) {
            final MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(audioFile.getAbsolutePath());
                final String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durationSec = Math.max(1.0, Long.parseLong(d) / 1000.0);
            } catch (Throwable ignore) {
            } finally {
                try { mmr.release(); } catch (Throwable ignore) {}
            }
        }
        if (durationSec <= 0.1) durationSec = 1.0;

        String trackTitle = resolved.title != null ? resolved.title.trim() : "";
        if (trackTitle.length() > 140) trackTitle = trackTitle.substring(0, 140).trim();
        if (trackTitle.isEmpty()) {
            trackTitle = platformDisplayName(resolved.platform, req.url);
        }
        String performer = resolved.author != null ? resolved.author.trim() : "";
        if (performer.isEmpty()) {
            performer = platformDisplayName(resolved.platform, req.url);
        }

        final boolean isEncrypted = DialogObject.isEncryptedDialog(req.dialogId);
        final TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.file_reference = new byte[0];
        doc.dc_id = Integer.MIN_VALUE;
        doc.date = AccountInstance.getInstance(req.currentAccount).getConnectionsManager().getCurrentTime();
        doc.size = audioFile.length();
        doc.mime_type = isMp3 ? "audio/mpeg" : "audio/mp4";

        final TLRPC.TL_documentAttributeAudio attrAudio = new TLRPC.TL_documentAttributeAudio();
        attrAudio.duration = durationSec;
        attrAudio.title = trackTitle;
        attrAudio.performer = performer;
        attrAudio.voice = false;
        attrAudio.flags |= 3;
        doc.attributes.add(attrAudio);

        final TLRPC.TL_documentAttributeFilename attrFile = new TLRPC.TL_documentAttributeFilename();
        final String safeBase = trackTitle.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        attrFile.file_name = (safeBase.isEmpty() ? "track" : safeBase) + (isMp3 ? ".mp3" : ".m4a");
        doc.attributes.add(attrFile);

        if (coverTemp != null && coverTemp.exists()) {
            try {
                final Bitmap bmp = PengramPhotoSlideshow.decodeOrientedBitmap(coverTemp, 512);
                if (bmp != null) {
                    final TLRPC.PhotoSize thumb = ImageLoader.scaleAndSaveImage(bmp, 320, 320, 82, isEncrypted);
                    if (thumb != null) {
                        doc.thumbs.add(thumb);
                        doc.flags |= 1;
                    }
                    bmp.recycle();
                }
            } catch (Throwable ignore) {
            } finally {
                coverTemp.delete();
            }
        }

        final HashMap<String, String> params = new HashMap<>();
        params.put("originalPath", audioFile.getAbsolutePath() + "audio" + audioFile.length());
        params.put("final", "1");

        AndroidUtilities.runOnUIThread(() -> {
            final SendMessagesHelper.SendMessageParams sendParams = SendMessagesHelper.SendMessageParams.of(
                    doc, null, audioFile.getAbsolutePath(), req.dialogId,
                    req.replyToMsg, req.replyToTopMsg,
                    caption != null && !caption.isEmpty() ? caption : null,
                    null, null, params, true, 0, 0, 0, null, null, false, false);
            sendParams.sendMessageChatArguments = req.sendMessageChatArguments;
            sendParams.monoForumPeer = req.monoForumPeer;
            SendMessagesHelper.getInstance(req.currentAccount).sendMessage(sendParams);
            if (cb != null) {
                cb.onSent();
            }
        });
    }

    // --- Режим входящих ссылок в чате: «⬇ Смотреть тут» во встроенном плеере Telegram ---

    public static final class WatchTask {
        public final String url;
        public final Handle handle;
        public volatile int phase = PHASE_RESOLVE;
        public volatile float progress = -1f;

        WatchTask(String url, Handle handle) {
            this.url = url;
            this.handle = handle;
        }
    }

    private static final class WatchCachedMedia {
        final int kind;
        final List<File> files;
        final String title;
        final String author;
        final long durationMs;
        @Nullable
        final File coverFile;
        final long createdAtMs;

        WatchCachedMedia(int kind, List<File> files, String title, String author,
                         long durationMs, @Nullable File coverFile) {
            this.kind = kind;
            this.files = files;
            this.title = title != null ? title : "";
            this.author = author != null ? author : "";
            this.durationMs = durationMs;
            this.coverFile = coverFile;
            this.createdAtMs = System.currentTimeMillis();
        }

        boolean isValid() {
            if (files == null || files.isEmpty()) return false;
            for (File f : files) {
                if (f == null || !f.exists() || f.length() < 128L) return false;
            }
            return true;
        }

        long totalBytes() {
            long sum = 0L;
            for (File f : files) {
                if (f != null && f.exists()) sum += f.length();
            }
            if (coverFile != null && coverFile.exists()) sum += coverFile.length();
            return sum;
        }

        void destroy() {
            for (File f : files) {
                if (f != null && f.exists()) f.delete();
            }
            if (coverFile != null && coverFile.exists()) coverFile.delete();
        }
    }

    private static final Object WATCH_LOCK = new Object();
    private static final int MAX_WATCH_CACHE_ITEMS = 6;
    private static final long MAX_WATCH_CACHE_BYTES = 260L * 1024L * 1024L;
    private static final LinkedHashMap<String, WatchCachedMedia> WATCH_CACHE =
            new LinkedHashMap<>(8, 0.75f, true);
    private static final Map<String, WatchTask> ACTIVE_WATCH_TASKS = new ConcurrentHashMap<>();
    private static int syntheticLocalMsgId = -710_000;

    private static String watchKey(String url, boolean audioOnly) {
        return (audioOnly ? "aud:" : "med:") + normalizeUrlForAlias(url);
    }

    @Nullable
    public static WatchTask getActiveWatchTask(@Nullable String url) {
        if (url == null) return null;
        WatchTask t = ACTIVE_WATCH_TASKS.get(watchKey(url, false));
        if (t == null) t = ACTIVE_WATCH_TASKS.get(watchKey(url, true));
        return t;
    }

    public static boolean isWatchCached(@Nullable String url) {
        if (url == null) return false;
        synchronized (WATCH_LOCK) {
            final WatchCachedMedia m = WATCH_CACHE.get(watchKey(url, false));
            if (m != null && m.isValid()) return true;
            final WatchCachedMedia a = WATCH_CACHE.get(watchKey(url, true));
            return a != null && a.isValid();
        }
    }

    public static void cancelWatch(@Nullable String url) {
        if (url == null) return;
        for (boolean aud : new boolean[]{false, true}) {
            final WatchTask t = ACTIVE_WATCH_TASKS.remove(watchKey(url, aud));
            if (t != null) {
                t.handle.cancel();
            }
        }
    }

    private static void trimWatchCacheLocked() {
        while (WATCH_CACHE.size() > MAX_WATCH_CACHE_ITEMS) {
            final Iterator<Map.Entry<String, WatchCachedMedia>> it = WATCH_CACHE.entrySet().iterator();
            if (!it.hasNext()) break;
            final Map.Entry<String, WatchCachedMedia> eldest = it.next();
            eldest.getValue().destroy();
            it.remove();
        }
        long sum = 0L;
        for (WatchCachedMedia m : WATCH_CACHE.values()) sum += m.totalBytes();
        final Iterator<Map.Entry<String, WatchCachedMedia>> it = WATCH_CACHE.entrySet().iterator();
        while (sum > MAX_WATCH_CACHE_BYTES && it.hasNext()) {
            final Map.Entry<String, WatchCachedMedia> eldest = it.next();
            sum -= eldest.getValue().totalBytes();
            eldest.getValue().destroy();
            it.remove();
        }
    }

    /**
     * Открывает ссылку прямо во встроенном плеере Telegram (PhotoViewer для видео/каруселей фото
     * или MediaController для треков SoundCloud / Яндекс.Музыки) без браузера.
     */
    public static void watchInline(final BaseFragment fragment,
                                   final int currentAccount,
                                   final String url,
                                   final boolean forceAudioOnly,
                                   @Nullable final Runnable onStateUpdate) {
        if (fragment == null || url == null || url.isEmpty()) {
            return;
        }
        final String key = watchKey(url, forceAudioOnly);
        final WatchTask existingTask = getActiveWatchTask(url);
        if (existingTask != null) {
            cancelWatch(url);
            if (onStateUpdate != null) AndroidUtilities.runOnUIThread(onStateUpdate);
            return;
        }
        synchronized (WATCH_LOCK) {
            final WatchCachedMedia cached = WATCH_CACHE.get(key);
            if (cached != null) {
                if (cached.isValid()) {
                    openWatchedMediaOnUi(fragment, currentAccount, cached);
                    return;
                } else {
                    WATCH_CACHE.remove(key);
                }
            }
        }

        final Handle handle = new Handle();
        final WatchTask task = new WatchTask(url, handle);
        ACTIVE_WATCH_TASKS.put(key, task);
        if (onStateUpdate != null) AndroidUtilities.runOnUIThread(onStateUpdate);

        TASK_EXECUTOR.execute(() -> {
            final File watchDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "pengram_watch_cache");
            //noinspection ResultOfMethodCallIgnored
            watchDir.mkdirs();
            final List<File> scratch = new ArrayList<>();
            final long[] lastUiMs = new long[]{0L};
            final Callback progressBridge = new Callback() {
                @Override
                public void onPhase(int phase, float progress) {
                    task.phase = phase;
                    task.progress = progress;
                    final long now = android.os.SystemClock.elapsedRealtime();
                    if (onStateUpdate != null && (now - lastUiMs[0] >= 80L || progress >= 0.99f)) {
                        lastUiMs[0] = now;
                        AndroidUtilities.runOnUIThread(onStateUpdate);
                    }
                }
                @Override public void onSent() {}
                @Override public void onPreviewRequested(Request req, PengramLinkResolver.Resolved resolved) {}
                @Override public void onFallbackSent(String reason) {}
                @Override public void onFailedNoSend(Request req, String reason) {}
                @Override public void onFallbackChoiceRequested(Request req, String reason) {}
                @Override public void onCancelled() {}
            };

            try {
                progressBridge.onPhase(PHASE_RESOLVE, -1f);
                final PengramLinkResolver.Resolved resolved = PengramLinkResolver.resolve(
                        url, PengramConfig.getLinkMaxVideoHeight());
                checkCancelled(handle);

                final WatchCachedMedia result;
                if (forceAudioOnly || resolved.isAudio()) {
                    final File audioOut = prepareAudioTrackFile(url, resolved, watchDir, scratch, handle, progressBridge);
                    File coverOut = null;
                    if (resolved.thumbnailUrl != null && !resolved.thumbnailUrl.isEmpty()) {
                        final File cov = new File(watchDir, "wcov_" + System.currentTimeMillis() + ".jpg");
                        try {
                            PengramLinkResolver.download(resolved.thumbnailUrl, cov, 6L * 1024L * 1024L,
                                    resolved.cookies, resolved.referer, resolved.userAgent, false, handle.cancel, null);
                            if (cov.exists() && cov.length() > 256L) coverOut = cov;
                            else cov.delete();
                        } catch (Throwable ignore) {
                            cov.delete();
                        }
                    }
                    final List<File> list = new ArrayList<>(1);
                    list.add(audioOut);
                    result = new WatchCachedMedia(PengramLinkResolver.KIND_AUDIO, list,
                            resolved.title, resolved.author, resolved.durationMs, coverOut);
                } else if (resolved.isPhotos()) {
                    final long stamp = System.currentTimeMillis();
                    final List<File> jpegs = new ArrayList<>(resolved.photos.size());
                    for (int i = 0; i < resolved.photos.size(); i++) {
                        checkCancelled(handle);
                        final int idx = i;
                        final int total = resolved.photos.size();
                        final File raw = new File(watchDir, "wp_" + stamp + "_" + i + ".raw");
                        final File jpg = new File(watchDir, "wp_" + stamp + "_" + i + ".jpg");
                        scratch.add(raw);
                        PengramLinkResolver.download(
                                resolved.photos.get(i).imageUrl, raw, MAX_PHOTO_BYTES,
                                resolved.cookies, resolved.referer, resolved.userAgent, false, handle.cancel,
                                (w, t) -> progressBridge.onPhase(PHASE_DOWNLOAD,
                                        (idx + (t > 0 ? Math.min(1f, (float) w / t) : 0.5f)) / total));
                        normalizePhotoToJpeg(raw, jpg);
                        raw.delete();
                        jpegs.add(jpg);
                    }
                    result = new WatchCachedMedia(PengramLinkResolver.KIND_PHOTOS, jpegs,
                            resolved.title, resolved.author, 0L, null);
                } else {
                    final File mp4 = downloadSingleVideoForWatch(resolved, watchDir, scratch, handle, progressBridge);
                    final List<File> list = new ArrayList<>(1);
                    list.add(mp4);
                    result = new WatchCachedMedia(PengramLinkResolver.KIND_VIDEO, list,
                            resolved.title, resolved.author, resolved.durationMs, null);
                }

                cleanFiles(scratch);
                checkCancelled(handle);
                synchronized (WATCH_LOCK) {
                    WATCH_CACHE.put(key, result);
                    trimWatchCacheLocked();
                }
                ACTIVE_WATCH_TASKS.remove(key, task);
                AndroidUtilities.runOnUIThread(() -> {
                    if (onStateUpdate != null) onStateUpdate.run();
                    openWatchedMediaOnUi(fragment, currentAccount, result);
                });
            } catch (CancellationException ce) {
                cleanFiles(scratch);
                ACTIVE_WATCH_TASKS.remove(key, task);
                if (onStateUpdate != null) AndroidUtilities.runOnUIThread(onStateUpdate);
            } catch (Throwable err) {
                FileLog.e(new Exception("PengramLinkAssistant watchInline failed for " + url + ": " + err, err));
                cleanFiles(scratch);
                ACTIVE_WATCH_TASKS.remove(key, task);
                AndroidUtilities.runOnUIThread(() -> {
                    if (onStateUpdate != null) onStateUpdate.run();
                    if (fragment.getParentActivity() != null) {
                        BulletinFactory.of(fragment).createSimpleBulletin(
                                R.raw.error,
                                LocaleController.getString(R.string.PengramLinkWatchFailed),
                                LocaleController.getString(R.string.PengramLinkOpenBrowser),
                                () -> Browser.openUrl(fragment.getParentActivity(), url)).show();
                    }
                });
            }
        });
    }

    private static File downloadSingleVideoForWatch(PengramLinkResolver.Resolved primary,
                                                    File watchDir,
                                                    List<File> scratch,
                                                    Handle handle,
                                                    Callback cb) throws Exception {
        final List<PengramLinkResolver.Resolved> cands = new ArrayList<>();
        cands.add(primary);
        cands.addAll(primary.fallbacks);
        Exception lastErr = null;
        for (int i = 0; i < cands.size(); i++) {
            checkCancelled(handle);
            final PengramLinkResolver.Resolved c = cands.get(i);
            final long stamp = System.currentTimeMillis();
            final File out = new File(watchDir, "wv_" + stamp + ".mp4");
            try {
                cb.onPhase(PHASE_DOWNLOAD, 0f);
                if (c.needsMux && c.urls.size() >= 2) {
                    final File vPart = new File(watchDir, "wv_" + stamp + ".v.mp4");
                    final File aPart = new File(watchDir, "wv_" + stamp + ".a.m4a");
                    scratch.add(vPart);
                    scratch.add(aPart);
                    PengramLinkResolver.download(c.urls.get(0), vPart, MAX_VIDEO_BYTES,
                            c.cookies, c.referer, c.userAgent, c.rangeChunked, handle.cancel,
                            (w, t) -> cb.onPhase(PHASE_DOWNLOAD, t > 0 ? 0.78f * w / t : -1f));
                    checkCancelled(handle);
                    PengramLinkResolver.download(c.urls.get(1), aPart, MAX_VIDEO_BYTES / 3,
                            c.cookies, c.referer, c.userAgent, c.rangeChunked, handle.cancel,
                            (w, t) -> cb.onPhase(PHASE_DOWNLOAD, 0.78f + (t > 0 ? 0.22f * w / t : 0f)));
                    checkCancelled(handle);
                    cb.onPhase(PHASE_MUX, -1f);
                    PengramVideoMux.mux(vPart, aPart, out, handle.cancel);
                    vPart.delete();
                    aPart.delete();
                } else if (c.hlsStream) {
                    final File rawTs = new File(watchDir, "wv_" + stamp + ".ts");
                    scratch.add(rawTs);
                    PengramLinkResolver.download(c.urls.get(0), rawTs, MAX_VIDEO_BYTES,
                            c.cookies, c.referer, c.userAgent, false, true, handle.cancel,
                            (w, t) -> cb.onPhase(PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
                    checkCancelled(handle);
                    cb.onPhase(PHASE_MUX, -1f);
                    PengramVideoMux.remuxToMp4(rawTs, out, handle.cancel);
                    rawTs.delete();
                } else {
                    PengramLinkResolver.download(c.urls.get(0), out, MAX_VIDEO_BYTES,
                            c.cookies, c.referer, c.userAgent, c.rangeChunked, handle.cancel,
                            (w, t) -> cb.onPhase(PHASE_DOWNLOAD, t > 0 ? (float) w / t : -1f));
                }
                validateDownloadedVideo(out);
                return out;
            } catch (CancellationException ce) {
                out.delete();
                throw ce;
            } catch (Exception e) {
                out.delete();
                lastErr = e;
            }
        }
        throw lastErr != null ? lastErr : new Exception("watch video failed");
    }

    private static void openWatchedMediaOnUi(BaseFragment fragment, int currentAccount, WatchCachedMedia media) {
        if (fragment == null || fragment.getParentActivity() == null || !media.isValid()) {
            return;
        }
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        final int nowSec = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        String captionText = media.title.trim();
        if (!media.author.trim().isEmpty()) {
            captionText = captionText.isEmpty() ? media.author.trim() : (captionText + "\n— " + media.author.trim());
        }
        if (captionText.length() > 400) captionText = captionText.substring(0, 400);

        if (media.kind == PengramLinkResolver.KIND_AUDIO) {
            final File f = media.files.get(0);
            final boolean isMp3 = f.getName().toLowerCase(Locale.US).endsWith(".mp3");
            final TLRPC.TL_message msg = new TLRPC.TL_message();
            msg.id = --syntheticLocalMsgId;
            msg.date = nowSec;
            msg.dialog_id = selfId;
            msg.peer_id = new TLRPC.TL_peerUser();
            msg.peer_id.user_id = selfId;
            msg.from_id = msg.peer_id;
            msg.out = true;
            msg.message = "";
            msg.attachPath = f.getAbsolutePath();

            final TLRPC.TL_messageMediaDocument mm = new TLRPC.TL_messageMediaDocument();
            final TLRPC.TL_document doc = new TLRPC.TL_document();
            doc.id = msg.id;
            doc.date = nowSec;
            doc.size = f.length();
            doc.mime_type = isMp3 ? "audio/mpeg" : "audio/mp4";
            doc.file_reference = new byte[0];

            final TLRPC.TL_documentAttributeAudio attr = new TLRPC.TL_documentAttributeAudio();
            attr.duration = media.durationMs > 0 ? (media.durationMs / 1000.0) : 180.0;
            attr.title = !media.title.isEmpty() ? media.title : LocaleController.getString(R.string.AttachMusic);
            attr.performer = !media.author.isEmpty() ? media.author : "Pengram";
            attr.voice = false;
            attr.flags |= 3;
            doc.attributes.add(attr);
            mm.document = doc;
            mm.flags |= 1;
            msg.media = mm;
            msg.flags |= 512;

            final MessageObject mo = new MessageObject(currentAccount, msg, false, false);
            MediaController.getInstance().playMessage(mo);
            return;
        }

        if (media.kind == PengramLinkResolver.KIND_PHOTOS) {
            final ArrayList<MessageObject> gallery = new ArrayList<>(media.files.size());
            for (int i = 0; i < media.files.size(); i++) {
                final File jpg = media.files.get(i);
                final BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(jpg.getAbsolutePath(), b);

                final TLRPC.TL_message msg = new TLRPC.TL_message();
                msg.id = --syntheticLocalMsgId;
                msg.date = nowSec;
                msg.dialog_id = selfId;
                msg.peer_id = new TLRPC.TL_peerUser();
                msg.peer_id.user_id = selfId;
                msg.from_id = msg.peer_id;
                msg.message = i == 0 ? captionText : "";
                msg.attachPath = jpg.getAbsolutePath();

                final TLRPC.TL_messageMediaPhoto mp = new TLRPC.TL_messageMediaPhoto();
                final TLRPC.TL_photo photo = new TLRPC.TL_photo();
                photo.id = msg.id;
                photo.date = nowSec;
                photo.file_reference = new byte[0];
                final TLRPC.TL_photoSize ps = new TLRPC.TL_photoSize();
                ps.type = "x";
                ps.w = b.outWidth > 0 ? b.outWidth : 1080;
                ps.h = b.outHeight > 0 ? b.outHeight : 1440;
                ps.size = (int) jpg.length();
                ps.location = new TLRPC.TL_fileLocationUnavailable();
                photo.sizes.add(ps);
                mp.photo = photo;
                mp.flags |= 1;
                msg.media = mp;
                msg.flags |= 512;

                final MessageObject mo = new MessageObject(currentAccount, msg, false, true);
                mo.photoThumbsObject = photo;
                gallery.add(mo);
            }
            PhotoViewer.getInstance().setParentActivity(fragment);
            PhotoViewer.getInstance().openPhoto(gallery, 0, 0L, 0L, 0L, new PhotoViewer.EmptyPhotoViewerProvider());
            return;
        }

        // Видео (.mp4) — открываем в нативном видеоплеере PhotoViewer
        final File mp4 = media.files.get(0);
        int vw = 1080;
        int vh = 1920;
        double durSec = media.durationMs > 0 ? (media.durationMs / 1000.0) : 15.0;
        final MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(mp4.getAbsolutePath());
            final String ws = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            final String hs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            final String rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            final String ds = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ws != null && hs != null) {
                int w0 = Integer.parseInt(ws);
                int h0 = Integer.parseInt(hs);
                if ("90".equals(rot) || "270".equals(rot)) {
                    vw = h0;
                    vh = w0;
                } else {
                    vw = w0;
                    vh = h0;
                }
            }
            if (ds != null) {
                durSec = Math.max(0.5, Long.parseLong(ds) / 1000.0);
            }
        } catch (Throwable ignore) {
        } finally {
            try { mmr.release(); } catch (Throwable ignore) {}
        }

        final TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = --syntheticLocalMsgId;
        msg.date = nowSec;
        msg.dialog_id = selfId;
        msg.peer_id = new TLRPC.TL_peerUser();
        msg.peer_id.user_id = selfId;
        msg.from_id = msg.peer_id;
        msg.message = captionText;
        msg.attachPath = mp4.getAbsolutePath();

        final TLRPC.TL_messageMediaDocument mm = new TLRPC.TL_messageMediaDocument();
        final TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = msg.id;
        doc.date = nowSec;
        doc.size = mp4.length();
        doc.mime_type = "video/mp4";
        doc.file_reference = new byte[0];

        final TLRPC.TL_documentAttributeVideo vAttr = new TLRPC.TL_documentAttributeVideo();
        vAttr.w = vw > 0 ? vw : 1080;
        vAttr.h = vh > 0 ? vh : 1920;
        vAttr.duration = durSec;
        vAttr.supports_streaming = true;
        doc.attributes.add(vAttr);

        final TLRPC.TL_documentAttributeFilename fAttr = new TLRPC.TL_documentAttributeFilename();
        fAttr.file_name = mp4.getName();
        doc.attributes.add(fAttr);

        mm.document = doc;
        mm.flags |= 1;
        msg.media = mm;
        msg.flags |= 512;

        final MessageObject mo = new MessageObject(currentAccount, msg, false, true);
        PhotoViewer.getInstance().setParentActivity(fragment);
        PhotoViewer.getInstance().openPhoto(mo, null, 0L, 0L, 0L, new PhotoViewer.EmptyPhotoViewerProvider());
    }
}
