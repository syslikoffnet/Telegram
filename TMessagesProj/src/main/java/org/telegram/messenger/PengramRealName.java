package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Pengram: «а как его зовут на самом деле».
 *
 * <p>Telegram не отдаёт клиенту настоящее имя человека, если он у вас в контактах:
 * сервер подменяет first_name/last_name вашей записью вообще везде — в профиле,
 * в группах, в пересланных сообщениях. Единственный способ увидеть то имя, которое
 * видят остальные, — на мгновение перестать считать человека контактом.
 *
 * <p>Поэтому здесь ровно такой сценарий: снять контакт → прочитать имя → вернуть контакт
 * обратно ровно с теми же полями. Собеседник ничего не узнает: удаление контакта у себя
 * его не уведомляет и его список контактов не меняет.
 *
 * <p>Главный риск — потерять контакт, если приложение умрёт между «снять» и «вернуть».
 * От этого защищает журнал: снимок полей пишется в настройки ДО удаления и
 * восстанавливается при следующем запуске ({@link #restorePending(int)}).
 *
 * <p>Чего мы принципиально не трогаем: контакты с персональным фото (его удаление
 * необратимо) — для них функция просто отказывает.
 */
public final class PengramRealName {

    private PengramRealName() {}

    private static final String PREFS = "pengramrealname";
    /** сколько держим ответ, чтобы не дёргать сервер на каждое нажатие */
    private static final long CACHE_TTL = 12 * 60 * 60 * 1000L;
    /** защита от «чеканки»: чаще этого один и тот же человек не перепроверяется */
    private static final long MIN_INTERVAL = 60 * 1000L;

    /** запросы, которые прямо сейчас в полёте */
    private static final HashSet<Long> running = new HashSet<>();

    public static final int ERROR_NONE = 0;
    /** человек не в контактах — его имя и так настоящее */
    public static final int ERROR_NOT_CONTACT = 1;
    /** стоит персональное фото: удаление контакта его сотрёт безвозвратно */
    public static final int ERROR_PERSONAL_PHOTO = 2;
    /** сервер не дал (флуд, нет связи, отказ) */
    public static final int ERROR_NETWORK = 3;

    public interface Callback {
        /** name != null — настоящее имя; иначе смотри error */
        void onResult(String name, int error);
    }

    // ------------------------------------------------------------------ снимок контакта

    /** всё, что нужно, чтобы вернуть контакт в точности таким, каким он был */
    private static final class Snapshot {
        long userId;
        long accessHash;
        String firstName = "";
        String lastName = "";
        String phone = "";
        String note = "";

        String serialize() {
            return userId + "\n" + accessHash + "\n"
                    + esc(firstName) + "\n" + esc(lastName) + "\n" + esc(phone) + "\n" + esc(note);
        }

        static Snapshot parse(String raw) {
            if (TextUtils.isEmpty(raw)) {
                return null;
            }
            final String[] parts = raw.split("\n", -1);
            if (parts.length < 6) {
                return null;
            }
            try {
                final Snapshot s = new Snapshot();
                s.userId = Long.parseLong(parts[0]);
                s.accessHash = Long.parseLong(parts[1]);
                s.firstName = unesc(parts[2]);
                s.lastName = unesc(parts[3]);
                s.phone = unesc(parts[4]);
                s.note = unesc(parts[5]);
                return s.userId != 0 ? s : null;
            } catch (Throwable ignore) {
                return null;
            }
        }

        private static String esc(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace("\n", "\\n");
        }

        private static String unesc(String value) {
            return value == null ? "" : value.replace("\\n", "\n").replace("\\\\", "\\");
        }
    }

    // ------------------------------------------------------------------ публичное API

    /** имя из кэша; null — ещё не спрашивали или кэш протух */
    public static String cached(long userId) {
        final SharedPreferences p = prefs();
        if (p == null) {
            return null;
        }
        final long time = p.getLong("time_" + userId, 0);
        if (time <= 0 || System.currentTimeMillis() - time > CACHE_TTL) {
            return null;
        }
        final String name = p.getString("name_" + userId, null);
        return TextUtils.isEmpty(name) ? null : name;
    }

    /** есть ли смысл предлагать жест вообще */
    public static boolean isApplicable(int account, long userId) {
        final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        return user != null && user.contact && !user.bot && !UserObject.isDeleted(user);
    }

    /** идёт ли сейчас проверка — чтобы показать крутилку вместо имени */
    public static boolean isRunning(long userId) {
        synchronized (running) {
            return running.contains(userId);
        }
    }

    /**
     * Узнать настоящее имя. Сначала смотрим кэш, потом — короткий цикл
     * «снять контакт → прочитать → вернуть».
     */
    public static void resolve(int account, long userId, Callback callback) {
        final String fromCache = cached(userId);
        if (fromCache != null) {
            done(callback, fromCache, ERROR_NONE);
            return;
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        if (user == null) {
            done(callback, null, ERROR_NETWORK);
            return;
        }
        if (!user.contact) {
            // не контакт — то, что видим, и есть настоящее имя
            done(callback, UserObject.getUserName(user), ERROR_NOT_CONTACT);
            return;
        }
        if (user.photo != null && user.photo.personal) {
            // персональное фото живёт на стороне контакта: снимем контакт — фото исчезнет навсегда
            done(callback, null, ERROR_PERSONAL_PHOTO);
            return;
        }
        final SharedPreferences p = prefs();
        if (p != null) {
            final long last = p.getLong("try_" + userId, 0);
            if (System.currentTimeMillis() - last < MIN_INTERVAL) {
                done(callback, null, ERROR_NETWORK);
                return;
            }
        }
        synchronized (running) {
            if (!running.add(userId)) {
                return;   // уже спрашиваем
            }
        }
        if (p != null) {
            p.edit().putLong("try_" + userId, System.currentTimeMillis()).apply();
        }

        final Snapshot snapshot = snapshotOf(account, user);
        // журнал пишем ДО удаления: если процесс умрёт — контакт вернётся при следующем старте
        store(snapshot);

        final TLRPC.TL_contacts_deleteContacts delete = new TLRPC.TL_contacts_deleteContacts();
        final TLRPC.InputUser input = inputUser(account, snapshot);
        if (input == null) {
            forget(userId);
            finish(userId);
            done(callback, null, ERROR_NETWORK);
            return;
        }
        delete.id.add(input);
        ConnectionsManager.getInstance(account).sendRequest(delete, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.Updates)) {
                forget(userId);
                finish(userId);
                done(callback, null, ERROR_NETWORK);
                return;
            }
            final TLRPC.Updates updates = (TLRPC.Updates) response;
            AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).processUpdates(updates, false));
            // имя уже лежит в ответе на удаление, но на всякий случай перечитываем пользователя
            readName(account, snapshot, realName -> {
                if (!TextUtils.isEmpty(realName)) {
                    remember(userId, realName);
                }
                restore(account, snapshot, () -> {
                    finish(userId);
                    done(callback, realName, TextUtils.isEmpty(realName) ? ERROR_NETWORK : ERROR_NONE);
                });
            });
        });
    }

    /**
     * Вызывается при старте приложения: если прошлый запуск оборвался между
     * «снять контакт» и «вернуть», возвращаем контакт на место.
     */
    public static void restorePending(int account) {
        final SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        for (String key : new ArrayList<>(p.getAll().keySet())) {
            if (!key.startsWith("pending_")) {
                continue;
            }
            final Snapshot snapshot = Snapshot.parse(p.getString(key, null));
            if (snapshot == null) {
                p.edit().remove(key).apply();
                continue;
            }
            restore(account, snapshot, null);
        }
    }

    // ------------------------------------------------------------------ шаги

    private static Snapshot snapshotOf(int account, TLRPC.User user) {
        final Snapshot s = new Snapshot();
        s.userId = user.id;
        s.accessHash = user.access_hash;
        s.firstName = user.first_name == null ? "" : user.first_name;
        s.lastName = user.last_name == null ? "" : user.last_name;
        s.phone = user.phone == null ? "" : user.phone;
        try {
            final TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(user.id);
            if (full != null && full.note != null && full.note.text != null) {
                s.note = full.note.text;
            }
        } catch (Throwable ignore) {
        }
        return s;
    }

    private interface NameCallback {
        void onName(String name);
    }

    private static void readName(int account, Snapshot snapshot, NameCallback whenDone) {
        final TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        final TLRPC.InputUser input = inputUser(account, snapshot);
        if (input == null) {
            whenDone.onName(null);
            return;
        }
        req.id.add(input);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            String name = null;
            if (response instanceof Vector) {
                final ArrayList<Object> objects = ((Vector) response).objects;
                for (int a = 0; a < objects.size(); ++a) {
                    if (objects.get(a) instanceof TLRPC.User && !(objects.get(a) instanceof TLRPC.TL_userEmpty)) {
                        final TLRPC.User fresh = (TLRPC.User) objects.get(a);
                        name = ContactsController.formatName(fresh.first_name, fresh.last_name);
                        break;
                    }
                }
            }
            whenDone.onName(TextUtils.isEmpty(name) ? null : name);
        });
    }

    /** вернуть контакт ровно таким, каким он был */
    private static void restore(int account, Snapshot snapshot, Runnable whenDone) {
        final TLRPC.TL_contacts_addContact req = new TLRPC.TL_contacts_addContact();
        final TLRPC.InputUser input = inputUser(account, snapshot);
        if (input == null) {
            forget(snapshot.userId);
            if (whenDone != null) whenDone.run();
            return;
        }
        req.id = input;
        req.first_name = snapshot.firstName;
        req.last_name = snapshot.lastName;
        req.phone = TextUtils.isEmpty(snapshot.phone) ? ""
                : (snapshot.phone.startsWith("+") ? snapshot.phone : "+" + snapshot.phone);
        if (!TextUtils.isEmpty(snapshot.note)) {
            final TLRPC.TL_textWithEntities note = new TLRPC.TL_textWithEntities();
            note.text = snapshot.note;
            req.flags |= 2;
            req.note = note;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                final TLRPC.Updates updates = (TLRPC.Updates) response;
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(account).processUpdates(updates, false);
                    ContactsController.getInstance(account).loadContacts(false, 0);
                });
                forget(snapshot.userId);
            } else {
                // не вышло — журнал оставляем, попробуем при следующем запуске
                FileLog.e(new IllegalStateException("pengram: контакт не восстановлен, "
                        + (error != null ? error.text : "пустой ответ")));
            }
            if (whenDone != null) {
                whenDone.run();
            }
        });
    }

    // ------------------------------------------------------------------ мелочи

    private static TLRPC.InputUser inputUser(int account, Snapshot snapshot) {
        TLRPC.InputUser input = MessagesController.getInstance(account).getInputUser(snapshot.userId);
        if (input == null || input instanceof TLRPC.TL_inputUserEmpty) {
            if (snapshot.accessHash == 0) {
                return null;
            }
            final TLRPC.TL_inputUser bare = new TLRPC.TL_inputUser();
            bare.user_id = snapshot.userId;
            bare.access_hash = snapshot.accessHash;
            input = bare;
        }
        return input;
    }

    private static void store(Snapshot snapshot) {
        final SharedPreferences p = prefs();
        if (p != null) {
            // commit, а не apply: запись должна лечь на диск до того, как уйдёт удаление
            p.edit().putString("pending_" + snapshot.userId, snapshot.serialize()).commit();
        }
    }

    private static void forget(long userId) {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().remove("pending_" + userId).apply();
        }
    }

    private static void remember(long userId, String name) {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit()
                    .putString("name_" + userId, name)
                    .putLong("time_" + userId, System.currentTimeMillis())
                    .apply();
        }
    }

    private static void finish(long userId) {
        synchronized (running) {
            running.remove(userId);
        }
    }

    private static void done(Callback callback, String name, int error) {
        if (callback == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> callback.onResult(name, error));
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext == null ? null
                : ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }
}
