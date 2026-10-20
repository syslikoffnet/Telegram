package org.telegram.messenger;

import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Pengram: официальное имя человека в его профиле.
 *
 * Сохранённого в контакты человека Telegram везде показывает так, как записали
 * его вы — это и остаётся обычным видом профиля. Жест по имени показывает, как
 * того же человека видят все остальные: имя, которое он поставил себе сам.
 *
 * Telegram не отдаёт такое имя отдельным полем, поэтому Pengram запоминает его
 * сам: имя, встреченное до того, как человек попал в ваши контакты (в группах,
 * каналах, поиске), затем самое раннее имя из истории профилей, и в крайнем
 * случае — @ник.
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
    public static final int SOURCE_USERNAME = 2;
    /** имя получено точной проверкой: контакт на мгновение убирался и вернулся */
    public static final int SOURCE_PROBE = 3;

    /** точная проверка выключена по умолчанию: это запросы к серверу */
    public static final String KEY_PROBE = "originalNameProbe";
    public static final String KEY_PROBE_ACK = "originalNameProbeAck";

    public static boolean probeEnabled() {
        return PengramConfig.getBool(KEY_PROBE, false);
    }

    public static void setProbeEnabled(boolean value) {
        PengramConfig.setBool(KEY_PROBE, value);
    }

    public static boolean probeAcknowledged() {
        return PengramConfig.getBool(KEY_PROBE_ACK, false);
    }

    public static void setProbeAcknowledged(boolean value) {
        PengramConfig.setBool(KEY_PROBE_ACK, value);
    }

    public static final int PROBE_OK = 0;
    public static final int PROBE_DISABLED = 1;
    public static final int PROBE_NOT_CONTACT = 2;
    public static final int PROBE_LIMITED = 3;
    public static final int PROBE_BUSY = 4;
    public static final int PROBE_FAILED = 5;

    public interface ProbeCallback {
        void onProbeResult(@androidx.annotation.Nullable Result result, int error);
    }

    /** не чаще одной проверки в 15 минут и одной на человека в сутки */
    private static final long PROBE_GLOBAL_COOLDOWN = 15L * 60L * 1000L;
    private static final long PROBE_USER_COOLDOWN = 24L * 60L * 60L * 1000L;
    private static final int PROBE_DAILY_LIMIT = 10;
    /** проверенное имя живёт месяц: человек мог сменить его, но чаще — незачем */
    private static final long PROBE_CACHE_TTL = 30L * 24L * 60L * 60L * 1000L;

    private static final java.util.HashSet<Long> probing = new java.util.HashSet<>();

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

    /** здесь живут имена, увиденные до переименования в контактах */
    private static final String PREFS = "pengram_public_names";

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    /**
     * Запомнить, как человека зовут «для всех».
     *
     * Сюда попадают объекты пользователя, пришедшие без подмены на имя из ваших
     * контактов: человек ещё не в контактах или это сокращённая (min) запись из
     * группы либо канала.
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

    public static class Result {
        public final String name;
        public final int source;

        Result(String name, int source) {
            this.name = name;
            this.source = source;
        }
    }

    /**
     * Официальное имя человека или null, если его взять неоткуда.
     *
     * Ищем по очереди: имя, встреченное до добавления в контакты, самое раннее
     * имя из истории профилей и, если больше нечего показать, @ник.
     */
    public static Result other(int account, TLRPC.User user) {
        if (user == null) {
            return null;
        }
        final String shown = clean(ContactsController.formatName(user.first_name, user.last_name));

        // Явно проверенное имя важнее любых догадок: оно получено напрямую
        // с сервера и действует, даже если наблюдение профилей выключено.
        final String probed = clean(probedName(account, user.id));
        if (!TextUtils.isEmpty(probed) && !probed.equalsIgnoreCase(shown)) {
            return new Result(probed, SOURCE_PROBE);
        }

        final String open = clean(fromPublicCache(account, user));
        if (!TextUtils.isEmpty(open) && !open.equalsIgnoreCase(shown)) {
            return new Result(open, SOURCE_PUBLIC);
        }

        final String history = clean(fromHistory(account, user.id));
        if (!TextUtils.isEmpty(history) && !history.equalsIgnoreCase(shown)) {
            return new Result(history, SOURCE_HISTORY);
        }

        final String username = UserObject.getPublicUsername(user);
        if (!TextUtils.isEmpty(username)) {
            return new Result("@" + username, SOURCE_USERNAME);
        }
        return null;
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


    // ------------------------------------------------- точная проверка имени

    /**
     * Показать имя, которое человек поставил себе сам, даже если он записан
     * в контактах под другим именем.
     *
     * Сервер подменяет first_name/last_name на сохранённое вами имя, пока
     * человек в контактах. Единственный способ увидеть настоящее — на один
     * запрос убрать контакт и сразу вернуть его с прежним именем. Проверка
     * кэшируется навсегда, поэтому повторные жесты не стоят ни одного запроса.
     *
     * Число запросов жёстко ограничено: 15 минут между проверками, сутки на
     * человека и не больше десяти в день. Номер телефона не передаётся
     * (contacts.addContact вызывается с пустым phone и без privacy exception).
     */
    /** человек записан в контакты: имя в его профиле сейчас подменено вашим */
    public static boolean isContact(int account, TLRPC.User user) {
        if (user == null) {
            return false;
        }
        if (user.contact) {
            return true;
        }
        try {
            return ContactsController.getInstance(account).contactsDict.containsKey(user.id);
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void probe(final int account, final TLRPC.User user, final ProbeCallback callback) {
        if (callback == null) {
            return;
        }
        if (!probeEnabled()) {
            callback.onProbeResult(null, PROBE_DISABLED);
            return;
        }
        if (user == null || user.id == 0 || user.min || !isContact(account, user)) {
            callback.onProbeResult(null, PROBE_NOT_CONTACT);
            return;
        }
        if (hasPending(account, user.id)) {
            // контакт уже ждёт восстановления — сначала возвращаем его, иначе
            // два сценария подерутся за одно и то же состояние
            callback.onProbeResult(null, PROBE_BUSY);
            return;
        }
        final long userId = user.id;
        synchronized (probing) {
            if (!probing.add(userId)) {
                callback.onProbeResult(null, PROBE_BUSY);
                return;
            }
        }
        if (!allowProbe(account, userId)) {
            synchronized (probing) {
                probing.remove(userId);
            }
            callback.onProbeResult(null, PROBE_LIMITED);
            return;
        }
        final String savedFirst = user.first_name == null ? "" : user.first_name;
        final String savedLast = user.last_name == null ? "" : user.last_name;
        if (TextUtils.isEmpty(savedFirst) && TextUtils.isEmpty(savedLast)) {
            // Нечего восстанавливать — такой контакт лучше не трогать.
            synchronized (probing) {
                probing.remove(userId);
            }
            callback.onProbeResult(null, PROBE_FAILED);
            return;
        }
        if (user.access_hash == 0) {
            synchronized (probing) {
                probing.remove(userId);
            }
            callback.onProbeResult(null, PROBE_FAILED);
            return;
        }
        noteProbe(account, userId);
        final long accessHash = user.access_hash;
        // Заметка о человеке — часть карточки контакта. Запоминаем её, чтобы
        // вернуть вместе с контактом: иначе проверка имени стирала бы текст,
        // который человек писал о собеседнике.
        final TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(userId);
        final TLRPC.TL_textWithEntities note = full == null ? null : full.note;
        // Метка восстановления пишется ДО запроса на удаление: если приложение
        // упадёт между запросами, контакт вернётся при следующем запуске.
        savePending(account, userId, savedFirst, savedLast, accessHash, note);

        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.InputUser inputUser = mc.getInputUser(user);
        if (inputUser == null) {
            clearPending(account, userId);
            synchronized (probing) {
                probing.remove(userId);
            }
            callback.onProbeResult(null, PROBE_FAILED);
            return;
        }

        final TLRPC.TL_contacts_deleteContacts request = new TLRPC.TL_contacts_deleteContacts();
        request.id.add(inputUser);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (error != null) {
                // Контакт ещё на месте — просто снимаем метку ожидания.
                clearPending(account, userId);
                finishProbe(account, userId, null, callback, PROBE_FAILED);
                return;
            }
            if (response instanceof TLRPC.Updates) {
                mc.processUpdates((TLRPC.Updates) response, false);
            }
            try {
                ContactsController.getInstance(account).pengramMarkContactRemoved(user);
            } catch (Throwable ignore) {
            }
            final String fromResponse = findRealName(account, userId, response, savedFirst, savedLast);
            if (!TextUtils.isEmpty(fromResponse)) {
                restoreAndFinish(account, userId, savedFirst, savedLast, accessHash, note, fromResponse, callback);
                return;
            }
            // Ответ на удаление не всегда несёт карточку человека. Тогда один
            // лёгкий запрос за именами — и сразу возвращаем контакт. Пока он
            // не в контактах, сервер отдаёт имя, которое человек поставил сам.
            final TLRPC.TL_users_getUsers names = new TLRPC.TL_users_getUsers();
            names.id.add(inputUser);
            ConnectionsManager.getInstance(account).sendRequest(names, (namesResponse, namesError) -> {
                String realName = null;
                if (namesResponse instanceof org.telegram.tgnet.Vector) {
                    final org.telegram.tgnet.Vector vector = (org.telegram.tgnet.Vector) namesResponse;
                    for (int a = 0; a < vector.objects.size(); a++) {
                        final Object item = vector.objects.get(a);
                        if (item instanceof TLRPC.User && ((TLRPC.User) item).id == userId) {
                            realName = clean(ContactsController.formatName(
                                    ((TLRPC.User) item).first_name, ((TLRPC.User) item).last_name));
                        }
                    }
                }
                if (TextUtils.isEmpty(realName)) {
                    realName = findRealName(account, userId, null, savedFirst, savedLast);
                }
                restoreAndFinish(account, userId, savedFirst, savedLast, accessHash, note, realName, callback);
            });
        });
    }

    /**
     * Вернуть контакт и отдать результат. Совпавшее имя — тоже результат:
     * значит человек и правда подписан так, как у вас записан.
     */
    private static void restoreAndFinish(final int account, final long userId,
                                         final String savedFirst, final String savedLast,
                                         final long accessHash, final TLRPC.TL_textWithEntities note,
                                         final String realName, final ProbeCallback callback) {
        final String name = TextUtils.isEmpty(realName)
                ? clean(ContactsController.formatName(savedFirst, savedLast)) : realName;
        final boolean known = !TextUtils.isEmpty(name);
        restoreContact(account, userId, savedFirst, savedLast, accessHash, note, restored -> {
            if (known) {
                storeProbedName(account, userId, name);
            }
            finishProbe(account, userId, known ? new Result(name, SOURCE_PROBE) : null,
                    callback, known ? PROBE_OK : PROBE_FAILED);
        });
    }

    private static void finishProbe(int account, long userId, Result result, ProbeCallback callback, int error) {
        synchronized (probing) {
            probing.remove(userId);
        }
        try {
            callback.onProbeResult(result, error);
        } catch (Throwable ignore) {
        }
    }

    /** имя из ответа на удаление: сервер больше не подменяет его контактом */
    private static String findRealName(int account, long userId, TLObject response, String savedFirst, String savedLast) {
        final String saved = clean(ContactsController.formatName(savedFirst, savedLast));
        if (response instanceof TLRPC.Updates) {
            final TLRPC.Updates updates = (TLRPC.Updates) response;
            for (int a = 0; a < updates.users.size(); a++) {
                final TLRPC.User u = updates.users.get(a);
                if (u != null && u.id == userId) {
                    final String name = clean(ContactsController.formatName(u.first_name, u.last_name));
                    if (!TextUtils.isEmpty(name) && !name.equalsIgnoreCase(saved)) {
                        return name;
                    }
                }
            }
        }
        final TLRPC.User cached = MessagesController.getInstance(account).getUser(userId);
        if (cached != null) {
            final String name = clean(ContactsController.formatName(cached.first_name, cached.last_name));
            if (!TextUtils.isEmpty(name) && !name.equalsIgnoreCase(saved)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Вернуть контакт с прежним именем. Идёт через отдельный «тихий» путь
     * ContactsController: он обновляет базу и секции контактов так же, как
     * обычное добавление, но не пишет в телефонную книгу и не отправляет
     * номер. Метка ожидания снимается только после успешного ответа сервера,
     * поэтому неудачная попытка повторится при следующем запуске.
     */
    private static void restoreContact(final int account, final long userId,
                                       final String first, final String last, final long accessHash,
                                       final TLRPC.TL_textWithEntities note,
                                       final Utilities.Callback<Boolean> done) {
        final TLRPC.User restore = new TLRPC.TL_user();
        restore.id = userId;
        restore.access_hash = accessHash;
        restore.first_name = first;
        restore.last_name = last;
        restore.phone = "";
        restore.contact = true;
        restore.min = false;
        restoreAttempt(account, userId, restore, note, 0, done);
    }

    /** одна повторная попытка через паузу: разовый сбой сети не должен «съесть» контакт */
    private static void restoreAttempt(final int account, final long userId, final TLRPC.User restore,
                                       final TLRPC.TL_textWithEntities note, final int attempt,
                                       final Utilities.Callback<Boolean> done) {
        try {
            ContactsController.getInstance(account).pengramRestoreContact(restore, note, ok -> {
                if (ok) {
                    clearPending(account, userId);
                    if (done != null) done.run(true);
                    return;
                }
                if (attempt < 1) {
                    AndroidUtilities.runOnUIThread(
                            () -> restoreAttempt(account, userId, restore, note, attempt + 1, done), 2500);
                    return;
                }
                // метка остаётся: при следующем запуске restorePending() повторит
                if (done != null) done.run(false);
            });
        } catch (Throwable e) {
            FileLog.e(e);
            if (done != null) done.run(false);
        }
    }

    // ---------------------------------------------------------- хранилище

    private static String probeKey(int account, long userId) {
        return "probe:" + key(account, userId);
    }

    private static String pendingKey(int account, long userId) {
        return "restore:" + key(account, userId);
    }

    private static String confirmedKey(int account, long userId) {
        return "confirmed:" + key(account, userId);
    }

    private static boolean allowProbe(int account, long userId) {
        final android.content.SharedPreferences p = prefs();
        final long now = System.currentTimeMillis();
        if (now - p.getLong("probeGlobal", 0L) < PROBE_GLOBAL_COOLDOWN) {
            return false;
        }
        if (now - p.getLong(probeKey(account, userId), 0L) < PROBE_USER_COOLDOWN) {
            return false;
        }
        final String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
                .format(new java.util.Date(now));
        if (!day.equals(p.getString("probeDay", ""))) {
            return true;
        }
        return p.getInt("probeDayCount", 0) < PROBE_DAILY_LIMIT;
    }

    private static void noteProbe(int account, long userId) {
        final long now = System.currentTimeMillis();
        final String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
                .format(new java.util.Date(now));
        final android.content.SharedPreferences p = prefs();
        final int count = day.equals(p.getString("probeDay", "")) ? p.getInt("probeDayCount", 0) : 0;
        p.edit()
                .putLong("probeGlobal", now)
                .putLong(probeKey(account, userId), now)
                .putString("probeDay", day)
                .putInt("probeDayCount", count + 1)
                .apply();
    }

    private static boolean hasPending(int account, long userId) {
        try {
            return prefs().contains(pendingKey(account, userId));
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void savePending(int account, long userId, String first, String last,
                                    long accessHash, TLRPC.TL_textWithEntities note) {
        // Заметку храним сериализованной: так переживёт и перезапуск, и смену
        // формата, потому что разбор идёт тем же классом, что и запись.
        prefs().edit().putString(pendingKey(account, userId),
                first + "\u0001" + last + "\u0001" + accessHash + "\u0001" + encodeNote(note)).commit();
    }

    private static String encodeNote(TLRPC.TL_textWithEntities note) {
        if (note == null || TextUtils.isEmpty(note.text)) {
            return "";
        }
        try {
            final org.telegram.tgnet.SerializedData data =
                    new org.telegram.tgnet.SerializedData(note.getObjectSize());
            note.serializeToStream(data);
            final byte[] bytes = data.toByteArray();
            data.cleanup();
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
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

    private static void clearPending(int account, long userId) {
        prefs().edit().remove(pendingKey(account, userId)).apply();
    }

    public static String probedName(int account, long userId) {
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
            if (System.currentTimeMillis() - savedAt > PROBE_CACHE_TTL) {
                return null;
            }
            return raw.substring(0, split);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void storeProbedName(int account, long userId, String name) {
        final String value = clean(name);
        if (!TextUtils.isEmpty(value)) {
            prefs().edit().putString(confirmedKey(account, userId),
                    value + '\u0001' + System.currentTimeMillis()).apply();
        }
    }

    /**
     * Восстановить контакты, которые не успели вернуться из-за падения или
     * потери сети. Вызывается при запуске приложения.
     */
    public static void restorePending(int account) {
        try {
            final android.content.SharedPreferences p = prefs();
            for (String rawKey : new java.util.ArrayList<>(p.getAll().keySet())) {
                if (!rawKey.startsWith("restore:") || !rawKey.startsWith("restore:" + account + ":")) {
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

    public static int sourceTextRes(int source) {
        switch (source) {
            case SOURCE_PROBE:
                return R.string.PengramOriginalNameProbeSource;
            case SOURCE_PUBLIC:
                return R.string.PengramOriginalNamePublic;
            case SOURCE_HISTORY:
                return R.string.PengramOriginalNameHistory;
            default:
                return R.string.PengramOriginalNameUsername;
        }
    }

    // ------------------------------------------------------------ жест

    /**
     * Повесить жест на имя в профиле.
     *
     * Нажатие и свайп включаются раздельно, поэтому обработчик всегда
     * спрашивает настройку заново: переключили в настройках — заработало сразу,
     * без перезахода в профиль.
     */
    public static void attach(View view, Runnable onToggle) {
        if (view == null || onToggle == null) {
            return;
        }
        final int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        final GestureDetector detector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return enabled();
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
            if (!enabled()) {
                return false;
            }
            return detector.onTouchEvent(event);
        });
    }
}
