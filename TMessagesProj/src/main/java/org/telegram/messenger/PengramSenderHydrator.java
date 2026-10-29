package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.view.ViewParent;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pengram: гарантированное восстановление автора (имени, цвета и аватарки) у удалённых
 * и архивных сообщений в группах и супергруппах.
 *
 * <p>Когда собеседник удаляет своё сообщение в группе, сервер перестаёт присылать его
 * в {@code messages.getHistory} и может не прислать объект {@link TLRPC.User} в {@code res.users}.
 * Из-за этого {@code MessagesController.getUser(fromId)} в RAM иногда оказывался {@code null},
 * и сохранённое удалённое сообщение отображалось с пустым местом вместо аватарки.
 *
 * <p>Этот класс решает проблему в три слоя без единого обращения к БД на UI-потоке:
 * <ol>
 *   <li>При сохранении удалённого/изменённого сообщения сохраняет компактный TL-снимок
 *       отправителя ({@link TLRPC.User} / {@link TLRPC.Chat} вместе с {@code access_hash},
 *       {@code photo} и {@code stripped_thumb}).</li>
 *   <li>Асинхронно подгружает недостающего автора из {@link MessagesStorage} на {@code storageQueue},
 *       а если кэш Telegram был очищен — восстанавливает из собственного TL-снимка или участников чата.</li>
 *   <li>Мгновенно регистрирует найденного автора в {@link MessagesController} и перерисовывает
 *       все ожидающие ячейки сообщений вместе с аватаркой в списке чата.</li>
 * </ol>
 */
public final class PengramSenderHydrator {

    public interface Listener {
        void onPengramSenderHydrated(long peerId);
    }

    private static final String PREFS_NAME = "pengram_sender_snapshots";
    private static final int MAX_SAVED_PEERS = 600;

    private static final Set<String> IN_FLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> MISS_CACHE =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ConcurrentHashMap<String, CopyList> WAITERS =
            new ConcurrentHashMap<>();

    private static final Object SNAPSHOT_LOCK = new Object();
    private static SharedPreferences snapshotPrefs;
    private static final Map<Long, String> memSnapshots = new LinkedHashMap<Long, String>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > MAX_SAVED_PEERS;
        }
    };

    private static final class CopyList {
        final ArrayList<WeakReference<Listener>> refs = new ArrayList<>(4);
    }

    private PengramSenderHydrator() {
    }

    private static SharedPreferences prefs() {
        synchronized (SNAPSHOT_LOCK) {
            if (snapshotPrefs == null && ApplicationLoader.applicationContext != null) {
                snapshotPrefs = ApplicationLoader.applicationContext.getSharedPreferences(
                        PREFS_NAME, Context.MODE_PRIVATE);
            }
            return snapshotPrefs;
        }
    }

    /**
     * Сохраняет компактный TL-снимок автора сообщения (если он сейчас есть в памяти
     * {@link MessagesController}). Безопасно вызывать с любого потока.
     */
    public static void rememberMessageSender(int account, TLRPC.Message message) {
        if (message == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        final long peerId = extractSenderPeerId(message);
        if (peerId != 0) {
            rememberPeer(account, peerId);
        }
    }

    public static long extractSenderPeerId(TLRPC.Message message) {
        if (message == null) {
            return 0;
        }
        if (message.from_id != null) {
            final long id = DialogObject.getPeerDialogId(message.from_id);
            if (id != 0) {
                return id;
            }
        }
        if (message.peer_id != null && !message.out) {
            final long pid = DialogObject.getPeerDialogId(message.peer_id);
            if (pid > 0) {
                return pid;
            }
        }
        return 0;
    }

    /**
     * Снимает сериализованную копию {@link TLRPC.User} или {@link TLRPC.Chat} из RAM,
     * чтобы даже после очистки кэша Telegram у удалённого сообщения остались имя и аватарка.
     */
    public static void rememberPeer(final int account, final long peerId) {
        if (peerId == 0 || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        try {
            final MessagesController mc = MessagesController.getInstance(account);
            if (peerId > 0) {
                final TLRPC.User user = mc.getUser(peerId);
                if (user != null && (!TextUtils.isEmpty(user.first_name)
                        || !TextUtils.isEmpty(user.last_name)
                        || !TextUtils.isEmpty(user.username)
                        || user.photo != null)) {
                    persistTlObjectAsync(peerId, user);
                }
            } else {
                final TLRPC.Chat chat = mc.getChat(-peerId);
                if (chat != null && (!TextUtils.isEmpty(chat.title) || chat.photo != null)) {
                    persistTlObjectAsync(peerId, chat);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void persistTlObjectAsync(final long peerId, final org.telegram.tgnet.TLObject obj) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final int size = obj.getObjectSize();
                if (size <= 0 || size > 32768) {
                    return;
                }
                final SerializedData data = new SerializedData(size);
                obj.serializeToStream(data);
                final byte[] bytes = data.toByteArray();
                data.cleanup();
                if (bytes == null || bytes.length == 0) {
                    return;
                }
                final String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
                final String key = (peerId > 0 ? "u_" : "c_") + Math.abs(peerId);
                synchronized (SNAPSHOT_LOCK) {
                    final String prev = memSnapshots.get(peerId);
                    if (encoded.equals(prev)) {
                        return;
                    }
                    memSnapshots.put(peerId, encoded);
                    final SharedPreferences sp = prefs();
                    if (sp != null) {
                        if (sp.getAll().size() > MAX_SAVED_PEERS * 2) {
                            sp.edit().clear().putString(key, encoded).apply();
                        } else {
                            sp.edit().putString(key, encoded).apply();
                        }
                    }
                }
                MISS_CACHE.remove("0:" + peerId);
                MISS_CACHE.remove("1:" + peerId);
                MISS_CACHE.remove("2:" + peerId);
                MISS_CACHE.remove("3:" + peerId);
            } catch (Throwable ignore) {
            }
        });
    }

    private static TLRPC.User restoreUserSnapshot(long userId) {
        if (userId <= 0) {
            return null;
        }
        String encoded;
        synchronized (SNAPSHOT_LOCK) {
            encoded = memSnapshots.get(userId);
            if (encoded == null) {
                final SharedPreferences sp = prefs();
                if (sp != null) {
                    encoded = sp.getString("u_" + userId, null);
                    if (encoded != null) {
                        memSnapshots.put(userId, encoded);
                    }
                }
            }
        }
        if (TextUtils.isEmpty(encoded)) {
            return null;
        }
        SerializedData data = null;
        try {
            final byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
            data = new SerializedData(raw);
            return TLRPC.User.TLdeserialize(data, data.readInt32(false), false);
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    private static TLRPC.Chat restoreChatSnapshot(long chatId) {
        if (chatId <= 0) {
            return null;
        }
        String encoded;
        synchronized (SNAPSHOT_LOCK) {
            encoded = memSnapshots.get(-chatId);
            if (encoded == null) {
                final SharedPreferences sp = prefs();
                if (sp != null) {
                    encoded = sp.getString("c_" + chatId, null);
                    if (encoded != null) {
                        memSnapshots.put(-chatId, encoded);
                    }
                }
            }
        }
        if (TextUtils.isEmpty(encoded)) {
            return null;
        }
        SerializedData data = null;
        try {
            final byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
            data = new SerializedData(raw);
            return TLRPC.Chat.TLdeserialize(data, data.readInt32(false), false);
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    /**
     * Проверяет, загружен ли отправитель в {@link MessagesController}; если нет —
     * подгружает на фоне из {@link MessagesStorage} / снимка Pengram / участников группы
     * и вызывает {@link Listener#onPengramSenderHydrated(long)} на UI-потоке.
     */
    public static void ensureLoaded(final int account, final long dialogId, final long peerId, final Listener listener) {
        if (peerId == 0 || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        if (peerId > 0) {
            if (mc.getUser(peerId) != null) {
                return;
            }
        } else {
            if (mc.getChat(-peerId) != null) {
                return;
            }
        }

        final String reqKey = account + ":" + peerId;
        if (MISS_CACHE.contains(reqKey)) {
            return;
        }

        if (listener != null) {
            synchronized (WAITERS) {
                CopyList list = WAITERS.get(reqKey);
                if (list == null) {
                    list = new CopyList();
                    WAITERS.put(reqKey, list);
                }
                boolean exists = false;
                for (int i = list.refs.size() - 1; i >= 0; i--) {
                    final Listener existing = list.refs.get(i).get();
                    if (existing == null) {
                        list.refs.remove(i);
                    } else if (existing == listener) {
                        exists = true;
                    }
                }
                if (!exists) {
                    list.refs.add(new WeakReference<>(listener));
                }
            }
        }

        if (!IN_FLIGHT.add(reqKey)) {
            return;
        }

        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            TLRPC.User foundUser = null;
            TLRPC.Chat foundChat = null;
            try {
                if (peerId > 0) {
                    foundUser = storage.getUser(peerId);
                    if (foundUser == null) {
                        foundUser = restoreUserSnapshot(peerId);
                    }
                } else {
                    foundChat = storage.getChat(-peerId);
                    if (foundChat == null) {
                        foundChat = restoreChatSnapshot(-peerId);
                    }
                }
            } catch (Throwable ignore) {
            }

            final TLRPC.User finalUser = foundUser;
            final TLRPC.Chat finalChat = foundChat;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalUser != null) {
                    mc.putUser(finalUser, true);
                    finishHydration(reqKey, peerId, true);
                    return;
                }
                if (finalChat != null) {
                    mc.putChat(finalChat, true);
                    finishHydration(reqKey, peerId, true);
                    return;
                }
                // Попытка достать участника из уже загруженного ChatFull или у супергруппы
                if (peerId > 0 && dialogId < 0) {
                    final TLRPC.Chat group = mc.getChat(-dialogId);
                    if (group != null && ChatObject.isChannel(group)) {
                        final TLRPC.TL_channels_getParticipant req = new TLRPC.TL_channels_getParticipant();
                        req.channel = mc.getInputChannel(-dialogId);
                        final TLRPC.TL_inputPeerUser inputUser = new TLRPC.TL_inputPeerUser();
                        inputUser.user_id = peerId;
                        req.participant = inputUser;
                        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                            boolean resolved = false;
                            if (response instanceof TLRPC.TL_channels_channelParticipant) {
                                final TLRPC.TL_channels_channelParticipant res = (TLRPC.TL_channels_channelParticipant) response;
                                mc.putUsers(res.users, false);
                                mc.putChats(res.chats, false);
                                resolved = mc.getUser(peerId) != null;
                                if (resolved) {
                                    rememberPeer(account, peerId);
                                }
                            }
                            finishHydration(reqKey, peerId, resolved);
                        }));
                        return;
                    }
                }
                finishHydration(reqKey, peerId, false);
            });
        });
    }

    private static void finishHydration(String reqKey, long peerId, boolean resolved) {
        IN_FLIGHT.remove(reqKey);
        if (!resolved) {
            MISS_CACHE.add(reqKey);
        }
        CopyList list;
        synchronized (WAITERS) {
            list = WAITERS.remove(reqKey);
        }
        if (!resolved || list == null) {
            return;
        }
        for (int i = 0; i < list.refs.size(); i++) {
            final Listener l = list.refs.get(i).get();
            if (l != null) {
                try {
                    l.onPengramSenderHydrated(peerId);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Пакетная предзагрузка авторов для архивных экранов ({@code PengramHistoryChatActivity},
     * поиск удалённых сообщений и призраки поиска в чате). Безопасно вызывать с фонового потока:
     * чтение БД выполняется строго на {@code storageQueue}, публикация — в {@link MessagesController}.
     */
    public static void preloadPeersBlocking(final int account, final Collection<Long> peerIds) {
        if (peerIds == null || peerIds.isEmpty() || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<Long> missing = new ArrayList<>();
        for (Long idObj : peerIds) {
            if (idObj == null || idObj == 0L) {
                continue;
            }
            final long id = idObj;
            if (id > 0) {
                if (mc.getUser(id) == null) {
                    missing.add(id);
                }
            } else {
                if (mc.getChat(-id) == null) {
                    missing.add(id);
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        final ArrayList<TLRPC.User> loadedUsers = new ArrayList<>();
        final ArrayList<TLRPC.Chat> loadedChats = new ArrayList<>();
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        final Runnable dbTask = () -> {
            for (int i = 0; i < missing.size(); i++) {
                final long id = missing.get(i);
                try {
                    if (id > 0) {
                        TLRPC.User u = storage.getUser(id);
                        if (u == null) {
                            u = restoreUserSnapshot(id);
                        }
                        if (u != null) {
                            loadedUsers.add(u);
                        }
                    } else {
                        TLRPC.Chat c = storage.getChat(-id);
                        if (c == null) {
                            c = restoreChatSnapshot(-id);
                        }
                        if (c != null) {
                            loadedChats.add(c);
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
        };

        if (Thread.currentThread() == storage.getStorageQueue()) {
            dbTask.run();
        } else {
            final CountDownLatch latch = new CountDownLatch(1);
            storage.getStorageQueue().postRunnable(() -> {
                try {
                    dbTask.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(320, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (!loadedUsers.isEmpty()) {
            mc.putUsers(loadedUsers, true);
        }
        if (!loadedChats.isEmpty()) {
            mc.putChats(loadedChats, true);
        }
    }

    /** Инвалидирует саму ячейку и родительский список чата (где рисуется боковая аватарка группы). */
    public static void invalidateCellAndParent(View cell) {
        if (cell == null) {
            return;
        }
        cell.invalidate();
        final ViewParent parent = cell.getParent();
        if (parent instanceof View) {
            ((View) parent).invalidate();
        }
    }
}
