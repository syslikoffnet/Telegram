package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pengram: система «❤️ Любимая музыка» для плеера и медиа-уведомления.
 *
 * Работает СТРОГО когда включён тумблер в настройках плеера (PengramConfig.isPlayerFavoritesEnabled()).
 * При нажатии на «❤️» автоматически находит или создаёт приватный музыкальный канал/чат
 * (либо использует Избранное / выбранный чат) и отправляет туда трек без плашки «Переслано от»
 * одной карточкой с распознанными метаданными. Поддерживает работу прямо с экрана блокировки.
 */
public final class PengramMusicFavorites {

    public static final String ACTION_LIKE = "org.telegram.android.musicplayer.pengram_like";
    private static final String PREFS_NAME = "pengram_music_favorites";

    public interface StateListener {
        void onFavoritesChanged();
    }

    private static final CopyOnWriteArrayList<StateListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Runnable> RUNNABLE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final HashMap<Integer, HashSet<String>> LIKED_CACHE = new HashMap<>();
    private static final HashMap<Integer, Boolean> CREATING_VAULT = new HashMap<>();
    private static final HashMap<Integer, ArrayList<PendingLike>> PENDING_VAULT_SENDS = new HashMap<>();
    private static final HashMap<String, Runnable> PENDING_LIKE_DELIVERIES = new HashMap<>();
    private static final HashMap<String, Long> SAVE_IN_FLIGHT_UNTIL = new HashMap<>();

    private static final class PendingLike {
        final MessageObject track;
        final boolean showUiFeedback;

        PendingLike(MessageObject track, boolean showUiFeedback) {
            this.track = track;
            this.showUiFeedback = showUiFeedback;
        }
    }

    private PengramMusicFavorites() {
    }

    public static void addListener(StateListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(StateListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static void addListener(Runnable listener) {
        if (listener != null && !RUNNABLE_LISTENERS.contains(listener)) {
            RUNNABLE_LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        if (listener != null) {
            RUNNABLE_LISTENERS.remove(listener);
        }
    }

    public static void notifyServiceChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < LISTENERS.size(); i++) {
                try {
                    LISTENERS.get(i).onFavoritesChanged();
                } catch (Throwable ignore) {
                }
            }
            for (int i = 0; i < RUNNABLE_LISTENERS.size(); i++) {
                try {
                    RUNNABLE_LISTENERS.get(i).run();
                } catch (Throwable ignore) {
                }
            }
            MusicPlayerService.onPengramFavoritesStateChanged();
        });
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static synchronized HashSet<String> likedSet(int account) {
        HashSet<String> set = LIKED_CACHE.get(account);
        if (set == null) {
            set = new HashSet<>();
            final SharedPreferences p = prefs();
            if (p != null) {
                final Set<String> stored = p.getStringSet("liked_" + account, null);
                if (stored != null) {
                    set.addAll(stored);
                }
            }
            LIKED_CACHE.put(account, set);
        }
        return set;
    }

    private static synchronized void persistLikedSet(int account, HashSet<String> set) {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putStringSet("liked_" + account, new HashSet<>(set)).apply();
        }
    }

    public static String primaryKey(MessageObject msg) {
        if (msg == null || !msg.isMusic()) {
            return null;
        }
        final TLRPC.Document doc = msg.getDocument();
        if (doc != null && doc.id != 0) {
            return "doc:" + doc.id;
        }
        return metaFingerprint(msg);
    }

    public static String metaFingerprint(MessageObject msg) {
        if (msg == null) {
            return null;
        }
        final String[] pair = PengramMusicMeta.resolveDisplayPair(msg);
        final String a = PengramTrackRecognizer.normalizeKeyPart(pair[0]);
        final String t = PengramTrackRecognizer.normalizeKeyPart(pair[1]);
        if (TextUtils.isEmpty(a) && TextUtils.isEmpty(t)) {
            return null;
        }
        return "meta:" + a + "|" + t;
    }

    public static synchronized boolean isLiked(MessageObject msg) {
        if (msg == null || !msg.isMusic()) {
            return false;
        }
        final HashSet<String> set = likedSet(msg.currentAccount);
        final String pk = primaryKey(msg);
        if (pk != null && set.contains(pk)) {
            return true;
        }
        final String mf = metaFingerprint(msg);
        return mf != null && set.contains(mf);
    }

    public static long getCustomTargetDialogId(int account) {
        final SharedPreferences p = prefs();
        return p == null ? 0L : p.getLong("custom_dialog_" + account, 0L);
    }

    public static void setCustomTargetDialogId(int account, long dialogId) {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putLong("custom_dialog_" + account, dialogId).apply();
        }
    }

    public static long getAutoVaultDialogId(int account, boolean groupMode) {
        final SharedPreferences p = prefs();
        if (p == null) {
            return 0L;
        }
        final String key = (groupMode ? "auto_group_" : "auto_channel_") + account;
        final long stored = p.getLong(key, 0L);
        if (stored < 0L) {
            final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-stored);
            if (chat != null && !chat.left && !chat.kicked && !chat.deactivated) {
                return stored;
            }
            if (chat == null) {
                // Чат может быть ещё не подгружен в память на старте фонового сервиса — доверяем сохранённому ID
                return stored;
            }
        }
        // Поищем уже созданный ранее канал/чат с нашим названием, чтобы не плодить дубликаты
        final String ruTitle = LocaleController.getString(R.string.PengramMusicVaultTitle);
        final ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(account).getAllDialogs();
        if (dialogs != null) {
            for (int i = 0; i < dialogs.size(); i++) {
                final TLRPC.Dialog d = dialogs.get(i);
                if (d == null || d.id >= 0) continue;
                final TLRPC.Chat c = MessagesController.getInstance(account).getChat(-d.id);
                if (c == null || c.left || c.kicked || c.deactivated || !c.creator) continue;
                final boolean isChannel = ChatObject.isChannel(c) && !c.megagroup;
                if (isChannel == !groupMode && c.title != null
                        && (c.title.equals(ruTitle) || c.title.contains("Любимая музыка") || c.title.contains("Liked Music"))) {
                    p.edit().putLong(key, d.id).apply();
                    return d.id;
                }
            }
        }
        return 0L;
    }

    private static void saveAutoVaultDialogId(int account, boolean groupMode, long dialogId) {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putLong((groupMode ? "auto_group_" : "auto_channel_") + account, dialogId).apply();
        }
    }

    /** Человекочитаемое имя текущего места сохранения для настроек плеера. */
    public static CharSequence currentTargetSummary(int account) {
        final int mode = PengramConfig.getPlayerFavTargetMode();
        if (mode == PengramConfig.FAV_TARGET_SAVED) {
            return LocaleController.getString(R.string.SavedMessages);
        }
        if (mode == PengramConfig.FAV_TARGET_CUSTOM) {
            final long did = getCustomTargetDialogId(account);
            if (did != 0L) {
                final String name = DialogObject.getShortName(account, did);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
            return LocaleController.getString(R.string.PengramPlayerFavTargetCustom);
        }
        if (mode == PengramConfig.FAV_TARGET_AUTO_GROUP) {
            return LocaleController.getString(R.string.PengramPlayerFavTargetAutoGroup);
        }
        return LocaleController.getString(R.string.PengramPlayerFavTargetAutoChannel);
    }

    public static CharSequence getTargetSummary(int account) {
        return currentTargetSummary(account);
    }

    /** Открыть чат/канал с любимой музыкой из настроек или по удержанию кнопки «❤️». */
    public static boolean openVaultChat(BaseFragment host) {
        final int account = host != null ? host.getCurrentAccount() : UserConfig.selectedAccount;
        return openVaultChat(account);
    }

    public static boolean openVaultChat(int account) {
        final BaseFragment last = LaunchActivity.getLastFragment();
        if (last == null) {
            return false;
        }
        long target = resolveExistingTargetDialogId(account);
        if (target == 0L) {
            BulletinFactory.of(last).createSimpleBulletin(R.raw.info,
                    LocaleController.getString(R.string.PengramPlayerFavEmptyVault)).show();
            return false;
        }
        final Bundle args = new Bundle();
        if (target > 0) {
            args.putLong("user_id", target);
        } else {
            args.putLong("chat_id", -target);
        }
        last.presentFragment(new ChatActivity(args));
        return true;
    }

    /** Диалог выбора своего чата/канала для режима FAV_TARGET_CUSTOM. */
    public static void pickCustomVaultChat(BaseFragment host, Runnable onPicked) {
        if (host == null) {
            return;
        }
        pickCustomTargetChat(host, host.getCurrentAccount(), onPicked);
    }

    public static void pickCustomTargetChat(BaseFragment host, int account, Runnable onPicked) {
        if (host == null) {
            return;
        }
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("canSelectTopics", false);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty()) {
                setCustomTargetDialogId(account, dids.get(0).dialogId);
                PengramConfig.setPlayerFavTargetMode(PengramConfig.FAV_TARGET_CUSTOM);
                if (onPicked != null) {
                    onPicked.run();
                }
            }
            fragment.finishFragment();
            return true;
        });
        host.presentFragment(picker);
    }

    private static long resolveExistingTargetDialogId(int account) {
        final int mode = PengramConfig.getPlayerFavTargetMode();
        if (mode == PengramConfig.FAV_TARGET_SAVED) {
            return UserConfig.getInstance(account).getClientUserId();
        }
        if (mode == PengramConfig.FAV_TARGET_CUSTOM) {
            return getCustomTargetDialogId(account);
        }
        return getAutoVaultDialogId(account, mode == PengramConfig.FAV_TARGET_AUTO_GROUP);
    }

    /** Переключить «❤️» для текущего играющего трека (вызов из уведомления или плеера). */
    public static void togglePlayingTrack(boolean fromUi) {
        if (!PengramConfig.isPlayerFavoritesEnabled()) {
            return;
        }
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || !playing.isMusic()) {
            return;
        }
        toggleTrack(playing, fromUi);
    }

    /** Переключить «❤️» для конкретного трека из UI (с контекстом). */
    public static boolean toggleFavorite(MessageObject track, Context context) {
        return toggleTrack(track, context != null);
    }

    /** Переключить «❤️» для конкретного трека. Возвращает новое состояние liked. */
    public static boolean toggleTrack(MessageObject track, boolean showUiFeedback) {
        if (!PengramConfig.isPlayerFavoritesEnabled() || track == null || !track.isMusic()) {
            return false;
        }
        final int account = track.currentAccount;
        final String pk = primaryKey(track);
        final String mf = metaFingerprint(track);
        if (pk == null && mf == null) {
            return false;
        }
        final String operationKey = operationKey(account, pk, mf);
        final long savedTarget = getSavedTargetDialogId(account, pk, mf);
        final long savedAt = getSavedTargetTimestamp(account, pk, mf);

        final boolean nowLiked;
        synchronized (PengramMusicFavorites.class) {
            final HashSet<String> set = likedSet(account);
            final boolean wasLiked = (pk != null && set.contains(pk)) || (mf != null && set.contains(mf));
            if (wasLiked) {
                if (pk != null) set.remove(pk);
                if (mf != null) set.remove(mf);
                nowLiked = false;
            } else {
                if (pk != null) set.add(pk);
                if (mf != null) set.add(mf);
                nowLiked = true;
            }
            persistLikedSet(account, set);
        }

        notifyServiceChanged();

        if (!nowLiked) {
            cancelPendingLikeDelivery(operationKey);
            final ArrayList<Long> targets = getUnlikeTargetCandidates(account, savedTarget);
            for (int i = 0; i < targets.size(); i++) {
                final long target = targets.get(i);
                deleteSavedCopies(account, track, target, target == savedTarget ? savedAt : 0L, 4);
            }
            if (showUiFeedback) {
                final String[] pair = PengramMusicMeta.resolveDisplayPair(track);
                final String label = !TextUtils.isEmpty(pair[1]) ? pair[1] : track.getMusicTitle();
                showSimpleFeedback(LocaleController.formatString(R.string.PengramPlayerFavRemoved, label));
            }
            return false;
        }

        // Короткий debounce сводит быстрые нажатия к итоговому состоянию и не создаёт
        // серию отправок, пока пользователь просто проверяет/передумывает.
        scheduleLikeDelivery(operationKey, account, track, showUiFeedback);
        return true;
    }

    private static String operationKey(int account, String primaryKey, String metaKey) {
        return account + "|" + (primaryKey != null ? primaryKey : metaKey);
    }

    private static void scheduleLikeDelivery(String key, int account, MessageObject track, boolean showUiFeedback) {
        synchronized (PENDING_LIKE_DELIVERIES) {
            final Runnable previous = PENDING_LIKE_DELIVERIES.remove(key);
            if (previous != null) AndroidUtilities.cancelRunOnUIThread(previous);
            final Runnable delivery = () -> {
                synchronized (PENDING_LIKE_DELIVERIES) {
                    PENDING_LIKE_DELIVERIES.remove(key);
                }
                if (isLiked(track)) {
                    deliverLikedTrack(account, track, showUiFeedback);
                }
            };
            PENDING_LIKE_DELIVERIES.put(key, delivery);
            AndroidUtilities.runOnUIThread(delivery, 650L);
        }
    }

    private static void cancelPendingLikeDelivery(String key) {
        synchronized (PENDING_LIKE_DELIVERIES) {
            final Runnable pending = PENDING_LIKE_DELIVERIES.remove(key);
            if (pending != null) AndroidUtilities.cancelRunOnUIThread(pending);
        }
    }

    private static void deliverLikedTrack(int account, MessageObject track, boolean showUiFeedback) {
        if (!isLiked(track)) return;
        final int mode = PengramConfig.getPlayerFavTargetMode();
        if (mode == PengramConfig.FAV_TARGET_SAVED) {
            final long savedId = UserConfig.getInstance(account).getClientUserId();
            if (savedId != 0L) {
                saveTrackOnce(account, track, savedId, showUiFeedback);
            }
            return;
        }
        if (mode == PengramConfig.FAV_TARGET_CUSTOM) {
            final long customId = getCustomTargetDialogId(account);
            if (customId != 0L) {
                saveTrackOnce(account, track, customId, showUiFeedback);
                return;
            }
            if (showUiFeedback) {
                final BaseFragment last = LaunchActivity.getLastFragment();
                if (last != null) {
                    pickCustomTargetChat(last, account, () -> {
                        final long chosen = getCustomTargetDialogId(account);
                        if (chosen != 0L && isLiked(track)) {
                            saveTrackOnce(account, track, chosen, true);
                        }
                    });
                    return;
                }
            }
            // Если вызвано с экрана блокировки без выбранного чата — создаём авто-канал
        }

        final boolean groupMode = mode == PengramConfig.FAV_TARGET_AUTO_GROUP;
        final long existingVault = getAutoVaultDialogId(account, groupMode);
        if (existingVault != 0L) {
            prepareAutoVault(account, existingVault, false);
            saveTrackOnce(account, track, existingVault, showUiFeedback);
            return;
        }
        ensureAutoVaultAndSend(account, groupMode, track, showUiFeedback);
    }

    private static void saveTrackOnce(int account, MessageObject track, long targetDialogId, boolean showUiFeedback) {
        if (targetDialogId == 0L || !isLiked(track)) return;
        final String pk = primaryKey(track);
        final String mf = metaFingerprint(track);
        final String key = operationKey(account, pk, mf);
        final long now = System.currentTimeMillis();
        synchronized (SAVE_IN_FLIGHT_UNTIL) {
            final Long pendingUntil = SAVE_IN_FLIGHT_UNTIL.get(key);
            if (pendingUntil != null && pendingUntil > now) {
                final long retryDelay = Math.max(500L, Math.min(8000L, pendingUntil - now + 100L));
                AndroidUtilities.runOnUIThread(() -> {
                    if (isLiked(track)) saveTrackOnce(account, track, targetDialogId, showUiFeedback);
                }, retryDelay);
                return;
            }
            SAVE_IN_FLIGHT_UNTIL.put(key, now + 30000L);
        }
        findSavedCopies(account, track, targetDialogId, false, (messages, error) -> {
            if (!isLiked(track)) {
                clearSaveInFlight(key);
                return;
            }
            if (error != null) {
                clearSaveInFlight(key);
                FileLog.d("Pengram favorite lookup failed: " + error.text);
                return;
            }
            if (messages != null && !messages.isEmpty()) {
                saveTargetDialogId(account, pk, mf, targetDialogId);
                // В чате уже есть этот outgoing-трек — не отправляем его повторно.
                clearSaveInFlight(key);
                return;
            }
            sendTrackWithRecognition(account, track, targetDialogId, showUiFeedback);
        });
    }

    private static void clearSaveInFlight(String key) {
        synchronized (SAVE_IN_FLIGHT_UNTIL) {
            SAVE_IN_FLIGHT_UNTIL.remove(key);
        }
    }

    private interface SavedCopiesCallback {
        void onResult(ArrayList<TLRPC.Message> messages, TLRPC.TL_error error);
    }

    private static void findSavedCopies(int account, MessageObject track, long targetDialogId, boolean excludeOriginalMessage, SavedCopiesCallback callback) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.InputPeer peer = controller.getInputPeer(targetDialogId);
        if (peer == null) {
            callback.onResult(null, null);
            return;
        }
        final TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = peer;
        request.q = track.getMusicTitle(true) == null ? "" : track.getMusicTitle(true);
        request.filter = new TLRPC.TL_inputMessagesFilterMusic();
        request.limit = 100;
        request.offset_id = 0;
        request.add_offset = 0;
        request.max_id = 0;
        request.min_id = 0;
        request.hash = 0;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                callback.onResult(null, error);
                return;
            }
            final ArrayList<TLRPC.Message> matches = new ArrayList<>();
            final String wantedPrimary = primaryKey(track);
            final String wantedMeta = metaFingerprint(track);
            final ArrayList<TLRPC.Message> found = ((TLRPC.messages_Messages) response).messages;
            for (int i = 0; i < found.size(); i++) {
                final TLRPC.Message message = found.get(i);
                if (message == null || !message.out || !isSameTrack(account, message, wantedPrimary, wantedMeta)) continue;
                // If the source itself is already in the selected vault, never mistake it
                // for our saved copy and delete the user's original message on unlike.
                if (excludeOriginalMessage && targetDialogId == track.getDialogId() && message.id == track.getId()) continue;
                matches.add(message);
            }
            callback.onResult(matches, null);
        }));
    }

    private static boolean isSameTrack(int account, TLRPC.Message message, String wantedPrimary, String wantedMeta) {
        if (message == null) return false;
        try {
            final MessageObject candidate = new MessageObject(account, message, false, true);
            final String candidatePrimary = primaryKey(candidate);
            if (wantedPrimary != null && wantedPrimary.equals(candidatePrimary)) return true;
            final String candidateMeta = metaFingerprint(candidate);
            return wantedMeta != null && wantedMeta.equals(candidateMeta);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void deleteSavedCopies(int account, MessageObject track, long targetDialogId, long savedAtMs, int retriesRemaining) {
        if (targetDialogId == 0L || isLiked(track)) return;
        findSavedCopies(account, track, targetDialogId, true, (messages, error) -> {
            if (isLiked(track)) return;
            if (error != null) {
                FileLog.d("Pengram favorite delete lookup failed: " + error.text);
            } else if (messages != null && !messages.isEmpty()) {
                final ArrayList<Integer> ids = new ArrayList<>(messages.size());
                final int earliestSavedDate = savedAtMs > 0L ? (int) (savedAtMs / 1000L) - 10 : 0;
                for (int i = 0; i < messages.size(); i++) {
                    final TLRPC.Message message = messages.get(i);
                    if (earliestSavedDate == 0 || message.date >= earliestSavedDate) ids.add(message.id);
                }
                if (ids.isEmpty()) {
                    clearSavedTargetDialogId(account, primaryKey(track), metaFingerprint(track));
                    return;
                }
                MessagesController.getInstance(account).deleteMessages(ids, null, null, targetDialogId, 0, true, 0);
            } else {
                clearSavedTargetDialogId(account, primaryKey(track), metaFingerprint(track));
                return;
            }
            if (retriesRemaining > 0) {
                AndroidUtilities.runOnUIThread(() -> deleteSavedCopies(account, track, targetDialogId, savedAtMs, retriesRemaining - 1), 1300L);
            }
        });
    }

    private static ArrayList<Long> getUnlikeTargetCandidates(int account, long savedTarget) {
        final ArrayList<Long> targets = new ArrayList<>();
        final HashSet<Long> unique = new HashSet<>();
        addTargetCandidate(targets, unique, savedTarget);
        if (savedTarget != 0L) {
            return targets;
        }
        // Migration fallback for likes saved by older builds, before we persisted
        // the chosen destination alongside each liked track.
        addTargetCandidate(targets, unique, resolveExistingTargetDialogId(account));
        addTargetCandidate(targets, unique, UserConfig.getInstance(account).getClientUserId());
        addTargetCandidate(targets, unique, getCustomTargetDialogId(account));
        addTargetCandidate(targets, unique, getAutoVaultDialogId(account, false));
        addTargetCandidate(targets, unique, getAutoVaultDialogId(account, true));
        return targets;
    }

    private static void addTargetCandidate(ArrayList<Long> targets, HashSet<Long> unique, long target) {
        if (target != 0L && unique.add(target)) targets.add(target);
    }

    private static long getSavedTargetDialogId(int account, String primaryKey, String metaKey) {
        final SharedPreferences p = prefs();
        if (p == null) return 0L;
        try {
            final JSONObject map = new JSONObject(p.getString("targets_" + account, "{}"));
            if (primaryKey != null && map.has(primaryKey)) return map.optLong(primaryKey, 0L);
            if (metaKey != null && map.has(metaKey)) return map.optLong(metaKey, 0L);
        } catch (Throwable ignore) {
        }
        return 0L;
    }

    private static void saveTargetDialogId(int account, String primaryKey, String metaKey, long targetDialogId) {
        final SharedPreferences p = prefs();
        if (p == null || targetDialogId == 0L) return;
        try {
            final JSONObject map = new JSONObject(p.getString("targets_" + account, "{}"));
            if (primaryKey != null) map.put(primaryKey, targetDialogId);
            if (metaKey != null) map.put(metaKey, targetDialogId);
            p.edit().putString("targets_" + account, map.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private static long getSavedTargetTimestamp(int account, String primaryKey, String metaKey) {
        final SharedPreferences p = prefs();
        if (p == null) return 0L;
        try {
            final JSONObject map = new JSONObject(p.getString("target_dates_" + account, "{}"));
            if (primaryKey != null && map.has(primaryKey)) return map.optLong(primaryKey, 0L);
            if (metaKey != null && map.has(metaKey)) return map.optLong(metaKey, 0L);
        } catch (Throwable ignore) {
        }
        return 0L;
    }

    private static void saveTargetTimestamp(int account, String primaryKey, String metaKey, long timestampMs) {
        final SharedPreferences p = prefs();
        if (p == null || timestampMs <= 0L) return;
        try {
            final JSONObject map = new JSONObject(p.getString("target_dates_" + account, "{}"));
            if (primaryKey != null) map.put(primaryKey, timestampMs);
            if (metaKey != null) map.put(metaKey, timestampMs);
            p.edit().putString("target_dates_" + account, map.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private static void clearSavedTargetDialogId(int account, String primaryKey, String metaKey) {
        final SharedPreferences p = prefs();
        if (p == null) return;
        try {
            final JSONObject map = new JSONObject(p.getString("targets_" + account, "{}"));
            if (primaryKey != null) map.remove(primaryKey);
            if (metaKey != null) map.remove(metaKey);
            final JSONObject dates = new JSONObject(p.getString("target_dates_" + account, "{}"));
            if (primaryKey != null) dates.remove(primaryKey);
            if (metaKey != null) dates.remove(metaKey);
            p.edit().putString("targets_" + account, map.toString())
                    .putString("target_dates_" + account, dates.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private static void ensureAutoVaultAndSend(int account, boolean groupMode, MessageObject track, boolean showUiFeedback) {
        ArrayList<PendingLike> queue = PENDING_VAULT_SENDS.get(account);
        if (queue == null) {
            queue = new ArrayList<>();
            PENDING_VAULT_SENDS.put(account, queue);
        }
        queue.add(new PendingLike(track, showUiFeedback));
        if (Boolean.TRUE.equals(CREATING_VAULT.get(account))) {
            return;
        }
        CREATING_VAULT.put(account, Boolean.TRUE);

        final TLRPC.TL_channels_createChannel req = new TLRPC.TL_channels_createChannel();
        req.title = LocaleController.getString(R.string.PengramMusicVaultTitle);
        req.about = LocaleController.getString(R.string.PengramMusicVaultAbout);
        if (groupMode) {
            req.megagroup = true;
        } else {
            req.broadcast = true;
        }

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            CREATING_VAULT.put(account, Boolean.FALSE);
            final ArrayList<PendingLike> pending = PENDING_VAULT_SENDS.remove(account);
            if (error != null || !(response instanceof TLRPC.Updates)) {
                // Фолбэк на Избранное при лимите каналов аккаунта, чтобы ни один трек не потерялся
                final long savedId = UserConfig.getInstance(account).getClientUserId();
                if (savedId != 0L && pending != null) {
                    for (int i = 0; i < pending.size(); i++) {
                        if (isLiked(pending.get(i).track)) saveTrackOnce(account, pending.get(i).track, savedId, pending.get(i).showUiFeedback);
                    }
                }
                return;
            }
            final TLRPC.Updates updates = (TLRPC.Updates) response;
            MessagesController.getInstance(account).processUpdates(updates, false);
            MessagesController.getInstance(account).putUsers(updates.users, false);
            MessagesController.getInstance(account).putChats(updates.chats, false);
            long vaultDialogId = 0L;
            if (updates.chats != null && !updates.chats.isEmpty()) {
                vaultDialogId = -updates.chats.get(0).id;
                saveAutoVaultDialogId(account, groupMode, vaultDialogId);
            }
            if (vaultDialogId == 0L) {
                vaultDialogId = UserConfig.getInstance(account).getClientUserId();
            } else {
                prepareAutoVault(account, vaultDialogId, true);
            }
            if (vaultDialogId != 0L && pending != null) {
                for (int i = 0; i < pending.size(); i++) {
                    if (isLiked(pending.get(i).track)) saveTrackOnce(account, pending.get(i).track, vaultDialogId, pending.get(i).showUiFeedback);
                }
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void prepareAutoVault(int account, long dialogId, boolean newlyCreated) {
        // Retention is changed only for the dedicated music peer, never the global auto-delete setting.
        MessagesController.getInstance(account).setDialogHistoryTTL(dialogId, 0);
        archiveAutoVault(account, dialogId);
        if (newlyCreated) {
            // Reassert after the first outgoing track has created its local dialog entry.
            AndroidUtilities.runOnUIThread(() -> archiveAutoVault(account, dialogId), 3500L);
        }
    }

    private static void archiveAutoVault(int account, long dialogId) {
        final MessagesController controller = MessagesController.getInstance(account);
        // Отправляем folder update напрямую: у только что созданного хранилища
        // ещё может не быть локальной записи dialog, но Telegram всё равно примет peer.
        final TLRPC.TL_inputFolderPeer folderPeer = new TLRPC.TL_inputFolderPeer();
        folderPeer.folder_id = 1;
        folderPeer.peer = controller.getInputPeer(dialogId);
        final TLRPC.TL_folders_editPeerFolders request = new TLRPC.TL_folders_editPeerFolders();
        request.folder_peers.add(folderPeer);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (error == null && response instanceof TLRPC.Updates) {
                controller.processUpdates((TLRPC.Updates) response, false);
            } else if (error != null) {
                FileLog.e("Pengram music vault archive failed: " + error.text);
            }
        });
    }

    private static void sendTrackWithRecognition(int account, MessageObject track, long targetDialogId, boolean showUiFeedback) {
        if (!PengramConfig.isPlayerFavRichCaption()) {
            performSend(account, track, targetDialogId, null, showUiFeedback);
            return;
        }
        if (PengramConfig.isSmartTrackRecognition()) {
            final PengramTrackRecognizer.TrackInfo cached = PengramTrackRecognizer.getCached(track);
            if (cached != null) {
                performSend(account, track, targetDialogId, buildRichCaption(track, cached), showUiFeedback);
                return;
            }
            final boolean[] sent = new boolean[]{false};
            final Runnable timeoutFallback = () -> {
                if (!sent[0]) {
                    sent[0] = true;
                    performSend(account, track, targetDialogId, buildRichCaption(track, null), showUiFeedback);
                }
            };
            AndroidUtilities.runOnUIThread(timeoutFallback, 1800);
            PengramTrackRecognizer.recognize(track, (key, info) -> {
                if (!sent[0]) {
                    sent[0] = true;
                    AndroidUtilities.cancelRunOnUIThread(timeoutFallback);
                    performSend(account, track, targetDialogId, buildRichCaption(track, info), showUiFeedback);
                }
            });
        } else {
            performSend(account, track, targetDialogId, buildRichCaption(track, null), showUiFeedback);
        }
    }

    private static String buildRichCaption(MessageObject track, PengramTrackRecognizer.TrackInfo info) {
        final String[] pair = PengramMusicMeta.resolveDisplayPair(track);
        final String artist = info != null && !TextUtils.isEmpty(info.artist) ? info.artist : pair[0];
        final String title = info != null && !TextUtils.isEmpty(info.title) ? info.title : pair[1];
        if (TextUtils.isEmpty(artist) && TextUtils.isEmpty(title)) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("\u2764\ufe0f ");
        if (!TextUtils.isEmpty(artist) && !TextUtils.isEmpty(title)) {
            sb.append(artist).append(" \u2014 ").append(title);
        } else {
            sb.append(!TextUtils.isEmpty(title) ? title : artist);
        }
        if (info != null) {
            final String badge = info.buildBadgeLine();
            if (!TextUtils.isEmpty(badge)) {
                sb.append("\n\ud83d\udcbf ").append(badge);
            }
        }
        return sb.toString();
    }

    private static void performSend(int account, MessageObject track, long targetDialogId, String caption, boolean showUiFeedback) {
        if (!isLiked(track)) {
            clearSaveInFlight(operationKey(track.currentAccount, primaryKey(track), metaFingerprint(track)));
            return;
        }
        final String key = operationKey(track.currentAccount, primaryKey(track), metaFingerprint(track));
        final long sentAt = System.currentTimeMillis();
        saveTargetDialogId(account, primaryKey(track), metaFingerprint(track), targetDialogId);
        saveTargetTimestamp(account, primaryKey(track), metaFingerprint(track), sentAt);
        AndroidUtilities.runOnUIThread(() -> clearSaveInFlight(key), 8000L);
        final TLRPC.Document doc = track.getDocument();
        boolean sentDirect = false;
        if (doc instanceof TLRPC.TL_document) {
            String path = track.messageOwner != null ? track.messageOwner.attachPath : null;
            if (TextUtils.isEmpty(path) && track.messageOwner != null) {
                try {
                    final File f = FileLoader.getInstance(account).getPathToMessage(track.messageOwner);
                    if (f != null && f.exists()) {
                        path = f.getAbsolutePath();
                    }
                } catch (Throwable ignore) {
                }
            }
            SendMessagesHelper.getInstance(account).sendMessage(
                    SendMessagesHelper.SendMessageParams.of(
                            (TLRPC.TL_document) doc, null, path, targetDialogId,
                            null, null, caption, null, null, null,
                            false, 0, 0, 0, track, null, false
                    )
            );
            sentDirect = true;
        }
        if (!sentDirect && track.getId() > 0) {
            final ArrayList<MessageObject> list = new ArrayList<>(Collections.singletonList(track));
            SendMessagesHelper.getInstance(account).sendMessage(list, targetDialogId, true, false, false, 0, 0);
            if (!TextUtils.isEmpty(caption)) {
                SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(caption, targetDialogId));
            }
        }

        if (showUiFeedback) {
            final BaseFragment last = LaunchActivity.getLastFragment();
            if (last != null) {
                final String targetName = DialogObject.getShortName(account, targetDialogId);
                final String msg = TextUtils.isEmpty(targetName)
                        ? LocaleController.getString(R.string.PengramPlayerFavSaved)
                        : LocaleController.formatString(R.string.PengramPlayerFavSavedTo, targetName);
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        BulletinFactory.of(last).createSimpleBulletin(
                                R.raw.ic_download,
                                msg,
                                LocaleController.getString(R.string.Open),
                                () -> {
                                    final Bundle args = new Bundle();
                                    if (targetDialogId > 0) {
                                        args.putLong("user_id", targetDialogId);
                                    } else {
                                        args.putLong("chat_id", -targetDialogId);
                                    }
                                    last.presentFragment(new ChatActivity(args));
                                }
                        ).show();
                    } catch (Throwable ignore) {
                    }
                }, 120);
            }
        }
    }

    private static void showSimpleFeedback(String text) {
        final BaseFragment last = LaunchActivity.getLastFragment();
        if (last != null) {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    BulletinFactory.of(last).createSimpleBulletin(R.raw.info, text).show();
                } catch (Throwable ignore) {
                }
            });
        }
    }
}
