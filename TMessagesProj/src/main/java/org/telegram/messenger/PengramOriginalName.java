package org.telegram.messenger;

import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import org.telegram.tgnet.TLRPC;

/**
 * Pengram: официальное имя человека в его профиле.
 *
 * <p>Сохранённого в контакты человека Telegram везде показывает так, как
 * записали его вы. Жест по имени показывает, как того же человека видят все
 * остальные: имя, которое он поставил себе сам.
 *
 * <p>Отдельным полем сервер такое имя не отдаёт: пока человек в ваших
 * контактах, {@code first_name}/{@code last_name} подменяются вашим вариантом.
 * Поэтому имя берётся из трёх источников, и ни один из них не трогает контакты
 * и не отправляет номер:
 * <ol>
 *   <li>публичная страница профиля {@code t.me/<ник>} — её видит любой
 *       посторонний, и там имя ровно такое, каким его поставил человек
 *       ({@link PengramNameLookup});</li>
 *   <li>имя, встреченное до того, как человек попал в ваши контакты — в
 *       группах, каналах, поиске;</li>
 *   <li>самое раннее имя из истории профилей.</li>
 * </ol>
 *
 * <p>Числа проверок нет: страница — обычный GET, а не метод API. Повторные
 * жесты не спамят её: сначала показывается то, что уже известно, а обновление
 * уходит в фон не чаще одного раза на известное состояние и не чаще, чем раз в
 * {@link #FRESH_FOR}.
 *
 * <p>Важно: {@code @ник} никогда не выдаётся за имя. Если имени нет ни в одном
 * источнике, профиль честно говорит, что оно неизвестно.
 */
public final class PengramOriginalName {

    /** как переключать: ничем, нажатием, свайпом или и тем и другим */
    public static final String KEY_MODE = "originalName";
    public static final int MODE_OFF = 0;
    public static final int MODE_TAP = 1;
    public static final int MODE_SWIPE = 2;
    public static final int MODE_BOTH = 3;

    /** откуда взяли официальное имя — чтобы честно подписать, что показываем */
    public static final int SOURCE_PUBLIC = 0;
    public static final int SOURCE_HISTORY = 1;
    /** оставлено для совместимости: ником имя больше не подменяется */
    public static final int SOURCE_USERNAME = 2;
    /** имя, проверенное раньше и сохранённое в кэше */
    public static final int SOURCE_PROBE = 3;
    /** имя с публичной страницы профиля */
    public static final int SOURCE_PROFILE = 4;

    /** здесь живут имена, увиденные до переименования в контактах */
    private static final String PREFS = "pengram_public_names";

    /** проверенное имя живёт месяц: человек мог сменить его, но чаще — незачем */
    private static final long VERIFIED_TTL = 30L * 24L * 60L * 60L * 1000L;
    /** чаще раза в неделю публичную страницу одного человека не перечитываем */
    private static final long FRESH_FOR = 7L * 24L * 60L * 60L * 1000L;

    /** id, для которых прямо сейчас идёт чтение страницы */
    private static final java.util.HashSet<String> refreshing = new java.util.HashSet<>();
    /** за один запуск приложения известное состояние обновляем один раз */
    private static final java.util.HashSet<String> refreshedThisRun = new java.util.HashSet<>();

    public interface RefreshCallback {
        void onRefreshed(@Nullable Result result);
    }

    private PengramOriginalName() {
    }

    public static int mode() {
        return PengramConfig.getIntCached(KEY_MODE, MODE_TAP);
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

        Result(String name, int source) {
            this.name = name;
            this.source = source;
        }
    }

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    /**
     * Запомнить, как человека зовут «для всех».
     *
     * <p>Сюда попадают объекты пользователя, пришедшие без подмены на имя из
     * ваших контактов: человек ещё не в контактах или это сокращённая (min)
     * запись из группы либо канала.
     */
    public static void observe(int account, TLRPC.User incoming, TLRPC.User local, boolean fromCache) {
        try {
            if (!PengramProfileHistory.canObserve(account, incoming, fromCache)) {
                return;
            }
            if (incoming.contact && !incoming.min) {
                return;   // такое имя уже могло быть заменено на ваше
            }
            final String name = clean(ContactsController.formatName(incoming.first_name, incoming.last_name));
            if (TextUtils.isEmpty(name)) {
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

    /** имя, которое видят остальные, если Pengram успел его встретить */
    private static String key(int account, long userId) {
        long owner = UserConfig.getInstance(account).getClientUserId();
        return account + ":" + owner + ":" + userId;
    }

    private static String fromPublicCache(int account, TLRPC.User user) {
        // The display path obeys the same opt-out and scope as collection.
        if (!PengramProfileHistory.canObserve(account, user, false)) return null;
        try {
            return prefs().getString(key(account, user.id), null);
        } catch (Throwable ignore) {
            return null;
        }
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
        } catch (Throwable ignore) { }
    }

    /**
     * Официальное имя человека или {@code null}, если его взять неоткуда.
     *
     * <p>Только чтение кэша и истории профилей — ни запросов, ни изменений в
     * контактах. Историю профилей читает SQLite, поэтому вызывать лучше не из
     * UI-потока.
     */
    @Nullable
    public static Result other(int account, TLRPC.User user) {
        if (user == null) {
            return null;
        }
        final String shown = clean(ContactsController.formatName(user.first_name, user.last_name));

        // Явно проверенное имя важнее любых догадок: оно получено напрямую
        // и действует, даже если наблюдение профилей выключено.
        final String verified = clean(verifiedName(account, user.id));
        if (!TextUtils.isEmpty(verified) && !verified.equalsIgnoreCase(shown)) {
            return new Result(verified, SOURCE_PROBE);
        }

        final String open = clean(fromPublicCache(account, user));
        if (!TextUtils.isEmpty(open) && !open.equalsIgnoreCase(shown)) {
            return new Result(open, SOURCE_PUBLIC);
        }

        final String history = clean(fromHistory(account, user.id));
        if (!TextUtils.isEmpty(history) && !history.equalsIgnoreCase(shown)) {
            return new Result(history, SOURCE_HISTORY);
        }
        return null;
    }

    /**
     * Стоит ли перечитать публичную страницу.
     *
     * <p>Второй раз за запуск — нет: то, что уже показано, показывается снова
     * без единого запроса. Если имя свежее недели, страница тоже не нужна.
     */
    public static boolean canRefresh(int account, TLRPC.User user) {
        if (user == null || user.id == 0 || user.min) {
            return false;
        }
        final String username = UserObject.getPublicUsername(user);
        if (!PengramNameLookup.isValidUsername(username)) {
            return false;
        }
        final String id = account + ":" + user.id;
        synchronized (refreshing) {
            if (refreshing.contains(id) || refreshedThisRun.contains(id)) {
                return false;
            }
        }
        return System.currentTimeMillis() - verifiedAt(account, user.id) >= FRESH_FOR;
    }

    /**
     * Перечитать публичную страницу профиля.
     *
     * <p>Колбэк приходит на фоновом потоке. Одновременно на человека уходит не
     * больше одного запроса — второй жест во время первого просто ничего не
     * делает.
     */
    public static void refresh(final int account, final TLRPC.User user, final RefreshCallback callback) {
        if (callback == null || user == null) {
            return;
        }
        final String username = UserObject.getPublicUsername(user);
        if (!PengramNameLookup.isValidUsername(username)) {
            callback.onRefreshed(null);
            return;
        }
        final String id = account + ":" + user.id;
        synchronized (refreshing) {
            if (!refreshing.add(id)) {
                return;
            }
            refreshedThisRun.add(id);
        }
        PengramNameLookup.lookup(username, name -> {
            synchronized (refreshing) {
                refreshing.remove(id);
            }
            final String value = clean(name);
            if (!TextUtils.isEmpty(value) && isPlausible(value)) {
                storeVerified(account, user.id, value);
                callback.onRefreshed(new Result(value, SOURCE_PROFILE));
            } else {
                callback.onRefreshed(null);
            }
        });
    }

    private static boolean isPlausible(String name) {
        return name.length() <= 96 && !name.startsWith("@");
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    /** самое раннее имя, которое Pengram успел увидеть до переименования */
    private static String fromHistory(int account, long userId) {
        try {
            final java.util.ArrayList<PengramProfileHistory.Change> changes = PengramProfileHistory.getChanges(account, userId);
            // список идёт от свежих к старым — нам нужно самое первое имя
            for (int i = changes.size() - 1; i >= 0; i--) {
                final PengramProfileHistory.Change change = changes.get(i);
                if (change != null && !TextUtils.isEmpty(change.name)) {
                    return change.name;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    // ------------------------------------------------------------ хранилище

    private static String confirmedKey(int account, long userId) {
        return "confirmed:" + key(account, userId);
    }

    /** проверенное имя или {@code null}, если его нет или оно устарело */
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

    /** когда имя проверяли в последний раз; 0 — не проверяли ни разу */
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
     * в старой версии проверки имени. Новых таких записей больше не появляется:
     * проверка больше не трогает контакты, но у тех, кто обновился, метка могла
     * остаться — её надо честно отработать.
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
     * Повесить жест на имя в профиле.
     *
     * <p>Нажатие и свайп включаются раздельно, поэтому обработчик всегда
     * спрашивает настройку заново: переключили в настройках — заработало сразу,
     * без заперехода в профиль.
     *
     * <p>Нажатие никогда не «съедается»: {@code ACTION_DOWN} отдаётся обычной
     * обработке вида, поэтому имя остаётся именем — с нажатием, нажатием с
     * задержкой и всем, что на нём висит снаружи. Жест только слушает поток
     * событий и не мешает ему.
     */
    public static void attach(View view, Runnable onToggle) {
        if (view == null || onToggle == null) {
            return;
        }
        final int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        final GestureDetector detector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                // false — вид получает DOWN как обычно; детектор всё равно
                // продолжает считать движение по MOVE/UP.
                return false;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (!tapEnabled()) {
                    return false;
                }
                onToggle.run();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (!swipeEnabled() || e1 == null || e2 == null) {
                    return false;
                }
                final float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(e2.getY() - e1.getY())) {
                    onToggle.run();
                    return true;
                }
                return false;
            }
        });
        view.setOnTouchListener((v, event) -> {
            if (enabled()) {
                detector.onTouchEvent(event);
            }
            // Событие никогда не считается обработанным: иначе вид не получит
            // UP, останется «нажатым» и потеряет собственные клики.
            return false;
        });
    }
}
