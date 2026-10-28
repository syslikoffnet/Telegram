package org.telegram.messenger;

import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Pengram: настоящее имя человека в Telegram.
 *
 * <p>Сохранённого в контакты человека Telegram везде показывает так, как
 * записали его вы. Жест по имени визуально показывает настоящее имя, которое
 * человек сам указал у себя в профиле Telegram, и возвращает ваше имя обратно
 * при повторном жесте или выходе с экрана.
 *
 * <p>Контактов и номеров телефонов функция никогда не касается. Официальное
 * имя берётся только из подлинных источников самого Telegram:
 * <ol>
 *   <li>поле {@code UserFull.private_forward_name} от сервера Telegram
 *       (работает даже без {@code @username});</li>
 *   <li>публичная страница профиля {@code https://t.me/<username>}
 *       ({@link PengramNameLookup});</li>
 *   <li>другой авторизованный аккаунт на устройстве, где этот человек не
 *       записан в контактах;</li>
 *   <li>непереименованная запись пользователя от сервера Telegram
 *       (до добавления в контакты или {@code min}-объект из общих групп).</li>
 * </ol>
 */
public final class PengramOriginalName {

    /** как переключать: ничем, нажатием, свайпом или и тем и другим */
    public static final String KEY_MODE = "originalName";
    public static final int MODE_OFF = 0;
    public static final int MODE_TAP = 1;
    public static final int MODE_SWIPE = 2;
    public static final int MODE_BOTH = 3;

    /** откуда взяли официальное имя */
    public static final int SOURCE_PUBLIC = 0;
    public static final int SOURCE_HISTORY = 1;
    /** оставлено для совместимости: ником имя никогда не подменяется */
    public static final int SOURCE_USERNAME = 2;
    /** проверенное официальное имя из кэша */
    public static final int SOURCE_PROBE = 3;
    /** официальное имя профиля Telegram */
    public static final int SOURCE_PROFILE = 4;

    private static final String PREFS = "pengram_public_names";

    private static final long VERIFIED_TTL = 30L * 24L * 60L * 60L * 1000L;
    private static final long FRESH_FOR = 12L * 60L * 60L * 1000L;

    private static final java.util.HashSet<String> refreshing = new java.util.HashSet<>();
    private static final java.util.HashSet<String> refreshedThisRun = new java.util.HashSet<>();

    public interface RefreshCallback {
        void onRefreshed(@Nullable Result result);
    }

    private PengramOriginalName() {
    }

    public static int mode() {
        return PengramConfig.getIntCached(KEY_MODE, MODE_BOTH);
    }

    public static void setMode(int mode) {
        PengramConfig.setIntValue(KEY_MODE, mode);
    }

    public static boolean enabled() {
        return mode() != MODE_OFF;
    }

    public static boolean tapEnabled() {
        final int mode = mode();
        return mode == MODE_TAP || mode == MODE_BOTH;
    }

    public static boolean swipeEnabled() {
        final int mode = mode();
        return mode == MODE_SWIPE || mode == MODE_BOTH;
    }

    public static class Result {
        public final String name;
        public final int source;
        public final boolean sameAsShown;

        Result(String name, int source) {
            this(name, source, false);
        }

        Result(String name, int source, boolean sameAsShown) {
            this.name = name;
            this.source = source;
            this.sameAsShown = sameAsShown;
        }
    }

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    /**
     * Запомнить непереименованное имя пользователя, пришедшее напрямую от Telegram
     * (человек не в контактах или это {@code min}-запись из общей группы/канала).
     */
    public static void observe(int account, TLRPC.User incoming, TLRPC.User local, boolean fromCache) {
        try {
            if (!enabled() || incoming == null || incoming.id <= 0
                    || incoming.self || incoming.bot || incoming.deleted) {
                return;
            }
            if (incoming.contact && !incoming.min) {
                return;
            }
            if (fromCache && local != null && local.contact) {
                return;
            }
            final String name = clean(ContactsController.formatName(incoming.first_name, incoming.last_name));
            if (TextUtils.isEmpty(name) || !isPlausible(name)) {
                return;
            }
            final String key = key(account, incoming.id);
            final android.content.SharedPreferences prefs = prefs();
            if (name.equals(prefs.getString(key, null))) {
                return;
            }
            prefs.edit().putString(key, name).apply();
        } catch (Throwable ignore) {
        }
    }

    private static String key(int account, long userId) {
        long owner = UserConfig.getInstance(account).getClientUserId();
        return account + ":" + owner + ":" + userId;
    }

    private static String fromPublicCache(int account, TLRPC.User user) {
        if (user == null || user.id <= 0 || user.self || user.bot || user.deleted) {
            return null;
        }
        try {
            return prefs().getString(key(account, user.id), null);
        } catch (Throwable ignore) {
            return null;
        }
    }

    @Nullable
    private static String fromUserFull(int account, long userId) {
        if (userId <= 0) {
            return null;
        }
        try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (a != account && !UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                final TLRPC.UserFull full = MessagesController.getInstance(a).getUserFull(userId);
                if (full != null && !TextUtils.isEmpty(full.private_forward_name)) {
                    final String candidate = clean(full.private_forward_name);
                    if (!TextUtils.isEmpty(candidate) && isPlausible(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    @Nullable
    private static String fromOtherAccounts(int account, long userId) {
        if (userId <= 0) {
            return null;
        }
        try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (a == account || !UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                final TLRPC.User u = MessagesController.getInstance(a).getUser(userId);
                if (u != null && !u.contact && !u.deleted) {
                    final String candidate = clean(ContactsController.formatName(u.first_name, u.last_name));
                    if (!TextUtils.isEmpty(candidate) && isPlausible(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public static void clearAccount(int account) {
        try {
            android.content.SharedPreferences store = prefs();
            android.content.SharedPreferences.Editor edit = store.edit();
            final String probePrefix = "confirmed:" + account + ":";
            final String pendingPrefix = "restore:" + account + ":";
            for (String key : store.getAll().keySet()) {
                if (key.startsWith(account + ":")
                        || key.startsWith(probePrefix)
                        || key.startsWith(pendingPrefix)) {
                    edit.remove(key);
                }
            }
            edit.apply();
        } catch (Throwable ignore) {
        }
    }

    /**
     * Официальное имя человека в Telegram из уже имеющихся данных или {@code null}.
     */
    @Nullable
    public static Result other(int account, TLRPC.User user) {
        if (user == null || user.id <= 0 || user.self || user.bot || user.deleted) {
            return null;
        }
        final String shown = clean(ContactsController.formatName(user.first_name, user.last_name));
        if (!user.contact && !TextUtils.isEmpty(shown) && isPlausible(shown)) {
            return new Result(shown, SOURCE_PROFILE, true);
        }

        final String liveForward = fromUserFull(account, user.id);
        if (!TextUtils.isEmpty(liveForward)) {
            storeVerified(account, user.id, liveForward);
            return new Result(liveForward, SOURCE_PROFILE, liveForward.equals(shown));
        }

        final String otherAcc = fromOtherAccounts(account, user.id);
        if (!TextUtils.isEmpty(otherAcc)) {
            storeVerified(account, user.id, otherAcc);
            return new Result(otherAcc, SOURCE_PROFILE, otherAcc.equals(shown));
        }

        final String verified = clean(verifiedName(account, user.id));
        if (!TextUtils.isEmpty(verified) && isPlausible(verified)) {
            return new Result(verified, SOURCE_PROBE, verified.equals(shown));
        }

        final String open = clean(fromPublicCache(account, user));
        if (!TextUtils.isEmpty(open) && isPlausible(open)) {
            return new Result(open, SOURCE_PUBLIC, open.equals(shown));
        }

        return null;
    }

    /**
     * Можно ли запросить свежее официальное имя у Telegram / публичной страницы.
     */
    public static boolean canRefresh(int account, TLRPC.User user) {
        if (user == null || user.id <= 0 || user.self || user.bot || user.deleted) {
            return false;
        }
        final String id = account + ":" + user.id;
        synchronized (refreshing) {
            if (refreshing.contains(id)) {
                return false;
            }
            if (refreshedThisRun.contains(id)
                    && System.currentTimeMillis() - verifiedAt(account, user.id) < FRESH_FOR) {
                return false;
            }
        }
        final String username = UserObject.getPublicUsername(user);
        final boolean hasWeb = PengramNameLookup.isValidUsername(username);
        final boolean canFetchFull = !user.min && MessagesController.getInstance(account).getUserFull(user.id) == null;
        if (!hasWeb && !canFetchFull) {
            return false;
        }
        return System.currentTimeMillis() - verifiedAt(account, user.id) >= FRESH_FOR
                || verifiedName(account, user.id) == null;
    }

    /**
     * Проверить актуальное официальное имя человека (через {@code UserFull} и/или {@code t.me/<username>}).
     */
    public static void refresh(final int account, final TLRPC.User user, final RefreshCallback callback) {
        if (callback == null || user == null || user.id <= 0) {
            if (callback != null) {
                callback.onRefreshed(null);
            }
            return;
        }
        final String shown = clean(ContactsController.formatName(user.first_name, user.last_name));
        final String immediateFull = fromUserFull(account, user.id);
        if (!TextUtils.isEmpty(immediateFull)) {
            storeVerified(account, user.id, immediateFull);
            callback.onRefreshed(new Result(immediateFull, SOURCE_PROFILE, immediateFull.equals(shown)));
            return;
        }
        final String id = account + ":" + user.id;
        synchronized (refreshing) {
            if (!refreshing.add(id)) {
                return;
            }
            refreshedThisRun.add(id);
        }
        final String username = UserObject.getPublicUsername(user);
        if (PengramNameLookup.isValidUsername(username)) {
            PengramNameLookup.lookup(username, name -> {
                final String value = clean(name);
                if (!TextUtils.isEmpty(value) && isPlausible(value)) {
                    synchronized (refreshing) {
                        refreshing.remove(id);
                    }
                    storeVerified(account, user.id, value);
                    callback.onRefreshed(new Result(value, SOURCE_PROFILE, value.equals(shown)));
                    return;
                }
                fetchUserFullForwardName(account, user, id, shown, callback);
            });
        } else {
            fetchUserFullForwardName(account, user, id, shown, callback);
        }
    }

    private static void fetchUserFullForwardName(final int account, final TLRPC.User user,
            final String lockId, final String shown, final RefreshCallback callback) {
        try {
            if (user.min) {
                synchronized (refreshing) {
                    refreshing.remove(lockId);
                }
                callback.onRefreshed(null);
                return;
            }
            final TLRPC.InputUser inputUser = MessagesController.getInstance(account).getInputUser(user);
            if (inputUser == null || inputUser instanceof TLRPC.TL_inputUserEmpty) {
                synchronized (refreshing) {
                    refreshing.remove(lockId);
                }
                callback.onRefreshed(null);
                return;
            }
            final TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
            req.id = inputUser;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                synchronized (refreshing) {
                    refreshing.remove(lockId);
                }
                if (response instanceof TLRPC.TL_users_userFull) {
                    final TLRPC.TL_users_userFull res = (TLRPC.TL_users_userFull) response;
                    if (res.full_user != null && !TextUtils.isEmpty(res.full_user.private_forward_name)) {
                        final String value = clean(res.full_user.private_forward_name);
                        if (!TextUtils.isEmpty(value) && isPlausible(value)) {
                            storeVerified(account, user.id, value);
                            callback.onRefreshed(new Result(value, SOURCE_PROFILE, value.equals(shown)));
                            return;
                        }
                    }
                }
                callback.onRefreshed(null);
            });
        } catch (Throwable e) {
            synchronized (refreshing) {
                refreshing.remove(lockId);
            }
            FileLog.e(e);
            callback.onRefreshed(null);
        }
    }

    private static boolean isPlausible(String name) {
        return name != null && !name.isEmpty() && name.length() <= 96 && !name.startsWith("@");
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    // ------------------------------------------------------------ хранилище

    private static String confirmedKey(int account, long userId) {
        return "confirmed:" + key(account, userId);
    }

    @Nullable
    public static String verifiedName(int account, long userId) {
        try {
            final String raw = prefs().getString(confirmedKey(account, userId), null);
            if (TextUtils.isEmpty(raw)) {
                return null;
            }
            final int split = raw.lastIndexOf('\u0001');
            if (split < 0) {
                return raw;
            }
            final long savedAt;
            try {
                savedAt = Long.parseLong(raw.substring(split + 1));
            } catch (Throwable ignore) {
                return raw.substring(0, split);
            }
            if (System.currentTimeMillis() - savedAt > VERIFIED_TTL) {
                return null;
            }
            return raw.substring(0, split);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static long verifiedAt(int account, long userId) {
        try {
            final String raw = prefs().getString(confirmedKey(account, userId), null);
            if (TextUtils.isEmpty(raw)) {
                return 0L;
            }
            final int split = raw.lastIndexOf('\u0001');
            if (split < 0) {
                return 0L;
            }
            return Long.parseLong(raw.substring(split + 1));
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    private static void storeVerified(int account, long userId, String name) {
        final String value = clean(name);
        if (!TextUtils.isEmpty(value)) {
            prefs().edit().putString(confirmedKey(account, userId),
                    value + '\u0001' + System.currentTimeMillis()).apply();
        }
    }

    /**
     * Вернуть контакты, которые остались удалёнными после падения приложения
     * в старой версии проверки имени.
     */
    public static void restorePending(int account) {
        try {
            final android.content.SharedPreferences p = prefs();
            for (String rawKey : new java.util.ArrayList<>(p.getAll().keySet())) {
                if (!rawKey.startsWith("restore:" + account + ":")) {
                    continue;
                }
                final String[] parts = String.valueOf(p.getString(rawKey, "")).split("\u0001");
                if (parts.length < 3) {
                    p.edit().remove(rawKey).apply();
                    continue;
                }
                final String[] keyParts = rawKey.split(":");
                if (keyParts.length < 4) {
                    continue;
                }
                final long userId;
                try {
                    userId = Long.parseLong(keyParts[keyParts.length - 1]);
                } catch (Throwable ignore) {
                    continue;
                }
                final long accessHash;
                try {
                    accessHash = Long.parseLong(parts[2]);
                } catch (Throwable ignore) {
                    continue;
                }
                if (ContactsController.getInstance(account).contactsDict.containsKey(userId)) {
                    p.edit().remove(rawKey).apply();
                    continue;
                }
                final TLRPC.User restore = new TLRPC.TL_user();
                restore.id = userId;
                restore.access_hash = accessHash;
                restore.first_name = parts[0];
                restore.last_name = parts[1];
                restore.phone = "";
                restore.contact = true;
                final TLRPC.TL_textWithEntities note = parts.length > 3 ? decodeNote(parts[3]) : null;
                final String key = rawKey;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        ContactsController.getInstance(account).pengramRestoreContact(restore, note, ok -> {
                            if (ok) {
                                prefs().edit().remove(key).apply();
                            }
                        });
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static TLRPC.TL_textWithEntities decodeNote(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        org.telegram.tgnet.SerializedData data = null;
        try {
            final byte[] bytes = android.util.Base64.decode(raw, android.util.Base64.NO_WRAP);
            data = new org.telegram.tgnet.SerializedData(bytes);
            final int constructor = data.readInt32(false);
            return TLRPC.TL_textWithEntities.TLdeserialize(data, constructor, false);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    public static int sourceTextRes(int source) {
        switch (source) {
            case SOURCE_PROFILE:
                return R.string.PengramOriginalNamePublicProfile;
            case SOURCE_PROBE:
                return R.string.PengramOriginalNameProbeSource;
            case SOURCE_PUBLIC:
                return R.string.PengramOriginalNamePublic;
            case SOURCE_HISTORY:
                return R.string.PengramOriginalNameHistory;
            default:
                return R.string.PengramOriginalNameNothing;
        }
    }

    // ------------------------------------------------------------------ жест

    /**
     * Повесить жест (нажатие и/или горизонтальный свайп) на имя в профиле.
     *
     * <p>Если слой сейчас прозрачен ({@code alpha <= 0.15f}) или функция
     * выключена, касание пропускается дальше. Правый значок эмодзи-статуса
     * {@code SimpleTextView} продолжает обрабатываться штатно.
     */
    public static void attach(View view, Runnable onToggle) {
        if (view == null || onToggle == null) {
            return;
        }
        final int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        final float[] start = new float[2];
        final boolean[] state = new boolean[2]; // [0] = swiped, [1] = delegatedToView
        final GestureDetector detector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (!tapEnabled() || state[0]) {
                    return false;
                }
                onToggle.run();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (!swipeEnabled() || state[0] || e1 == null || e2 == null) {
                    return false;
                }
                final float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > slop * 1.4f && Math.abs(dx) > Math.abs(e2.getY() - e1.getY())) {
                    state[0] = true;
                    onToggle.run();
                    return true;
                }
                return false;
            }
        });
        detector.setIsLongpressEnabled(false);
        view.setOnTouchListener((v, event) -> {
            if (!enabled() || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0.15f) {
                return false;
            }
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                state[0] = false;
                state[1] = v.onTouchEvent(event);
                if (state[1]) {
                    return true;
                }
                start[0] = event.getX();
                start[1] = event.getY();
                detector.onTouchEvent(event);
                return true;
            }
            if (state[1]) {
                return v.onTouchEvent(event);
            }
            if (action == MotionEvent.ACTION_MOVE && swipeEnabled() && !state[0]) {
                final float dx = event.getX() - start[0];
                final float dy = event.getY() - start[1];
                if (Math.abs(dx) > slop * 1.4f && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    state[0] = true;
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    onToggle.run();
                    return true;
                }
            }
            if (!state[0]) {
                detector.onTouchEvent(event);
            }
            return true;
        });
    }
}
