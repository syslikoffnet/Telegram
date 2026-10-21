package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;

/**
 * Pengram: всё, что касается работы с ID как с полноценным «адресом» человека или чата.
 *
 * ID в Pengram должен работать там же, где работает юзернейм: в глобальном поиске,
 * в поиске «от кого» внутри чата, в упоминаниях. Этот класс отвечает за две вещи:
 * 1) терпимо разбирает то, что человек вставил из буфера («ID: 123», «tg://user?id=123», «-100123…»);
 * 2) достаёт по ID настоящего пользователя или чат — из памяти, из базы, а если там пусто — с сервера.
 */
public final class PengramPeerId {

    private PengramPeerId() {}

    /** ID, которые сервер уже отказался отдавать — чтобы не долбить его одним и тем же запросом */
    private static final java.util.HashSet<Long> failed = new java.util.HashSet<>();
    /** ID, запрос по которым прямо сейчас в полёте */
    private static final java.util.HashSet<Long> pending = new java.util.HashSet<>();

    /**
     * Разбирает строку как ID. Понимает лишние пробелы, префиксы «id:», «ид:», ссылки tg://
     * и обе формы ID чата: Telegram API (123…) и Bot API (-100123…).
     *
     * @return число ровно в том виде, как его написали (отрицательное — значит чат), 0 — это не ID
     */
    public static long parse(String query) {
        if (query == null) {
            return 0;
        }
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) {
            return 0;
        }
        // ссылки вида tg://user?id=123 и tg://openmessage?user_id=123
        final int idx = q.lastIndexOf("id=");
        if (idx >= 0) {
            q = q.substring(idx + 3);
        } else {
            // «id: 123», «ид 123», «айди — 123»
            final int colon = q.lastIndexOf(':');
            if (colon >= 0 && colon < q.length() - 1) {
                q = q.substring(colon + 1);
            }
        }
        q = q.replace(" ", "").replace("\u00A0", "").replace("_", "").replace("\u2014", "").replace("\u2013", "");
        if (q.startsWith("id")) {
            q = q.substring(2);
        }
        if (q.startsWith("+")) {
            q = q.substring(1);
        }
        if (q.isEmpty()) {
            return 0;
        }
        boolean negative = false;
        if (q.charAt(0) == '-') {
            negative = true;
            q = q.substring(1);
        }
        if (q.isEmpty() || q.length() > 19) {
            return 0;
        }
        for (int a = 0; a < q.length(); ++a) {
            final char c = q.charAt(a);
            if (c < '0' || c > '9') {
                return 0;
            }
        }
        try {
            final long value = Long.parseLong(q);
            if (value == 0) {
                return 0;
            }
            return negative ? -value : value;
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** Превращает любой разобранный ID в dialog_id клиента: человек > 0, чат/канал < 0. */
    public static long toDialogId(long raw) {
        if (raw == 0) {
            return 0;
        }
        if (raw > 0) {
            return raw;
        }
        // Bot API: -1001234567890 -> -1234567890
        if (raw <= -1000000000000L) {
            return raw + 1000000000000L;
        }
        return raw;
    }

    /** Разбирает строку сразу в dialog_id. */
    public static long parseDialogId(String query) {
        return toDialogId(parse(query));
    }

    /** Похоже ли написанное на ID (достаточно длинное число). */
    public static boolean looksLikeId(String query) {
        final long raw = parse(query);
        if (raw == 0) {
            return false;
        }
        final long abs = Math.abs(raw);
        return abs >= 100;
    }

    /** Пир уже лежит в памяти? Возвращает TLRPC.User или TLRPC.Chat, иначе null. */
    public static TLObject cached(int account, long dialogId) {
        if (dialogId == 0) {
            return null;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        if (dialogId > 0) {
            final TLRPC.User user = controller.getUser(dialogId);
            return user == null || user instanceof TLRPC.TL_userEmpty ? null : user;
        }
        return controller.getChat(-dialogId);
    }

    /**
     * Достаёт пир по ID: сначала память, потом сервер. Колбэк всегда вызывается в UI-потоке,
     * с TLRPC.User, TLRPC.Chat или null, если такого ID не существует (или он нам недоступен).
     */
    public static void resolve(int account, long dialogId, Utilities.Callback<TLObject> whenDone) {
        if (dialogId == 0) {
            if (whenDone != null) whenDone.run(null);
            return;
        }
        final TLObject cachedPeer = cached(account, dialogId);
        if (cachedPeer != null) {
            if (whenDone != null) whenDone.run(cachedPeer);
            return;
        }
        final long key = dialogId * 31L + account;
        synchronized (failed) {
            if (failed.contains(key) || pending.contains(key)) {
                if (whenDone != null) whenDone.run(null);
                return;
            }
            pending.add(key);
        }
        final Utilities.Callback<TLObject> done = result -> AndroidUtilities.runOnUIThread(() -> {
            synchronized (failed) {
                pending.remove(key);
                if (result == null) {
                    failed.add(key);
                }
            }
            if (whenDone != null) {
                whenDone.run(result);
            }
        });
        if (dialogId > 0) {
            requestUser(account, dialogId, done);
        } else {
            requestChat(account, -dialogId, done);
        }
    }

    private static void requestUser(int account, long userId, Utilities.Callback<TLObject> done) {
        final TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        TLRPC.InputUser inputUser = MessagesController.getInstance(account).getInputUser(userId);
        if (inputUser == null || inputUser instanceof TLRPC.TL_inputUserEmpty) {
            // хэша доступа нет — пробуем «голый» ID: для ботов и общих чатов сервер его принимает
            final TLRPC.TL_inputUser bare = new TLRPC.TL_inputUser();
            bare.user_id = userId;
            bare.access_hash = 0;
            inputUser = bare;
        }
        req.id.add(inputUser);
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            TLRPC.User found = null;
            if (res instanceof Vector) {
                final ArrayList<Object> objects = ((Vector) res).objects;
                for (int a = 0; a < objects.size(); ++a) {
                    if (objects.get(a) instanceof TLRPC.User && !(objects.get(a) instanceof TLRPC.TL_userEmpty)) {
                        found = (TLRPC.User) objects.get(a);
                        break;
                    }
                }
            }
            if (found == null) {
                done.run(null);
                return;
            }
            final ArrayList<TLRPC.User> users = new ArrayList<>();
            users.add(found);
            final TLRPC.User result = found;
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).putUsers(users, false);
                MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
                done.run(result);
            });
        });
    }

    private static void requestChat(int account, long chatId, Utilities.Callback<TLObject> done) {
        final TLRPC.TL_channels_getChannels req = new TLRPC.TL_channels_getChannels();
        final TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
        inputChannel.channel_id = chatId;
        inputChannel.access_hash = 0;
        req.id.add(inputChannel);
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (res instanceof TLRPC.messages_Chats && !((TLRPC.messages_Chats) res).chats.isEmpty()) {
                deliverChat(account, ((TLRPC.messages_Chats) res).chats, done);
                return;
            }
            // не канал — вдруг это обычная группа
            final TLRPC.TL_messages_getChats groupReq = new TLRPC.TL_messages_getChats();
            groupReq.id.add(chatId);
            ConnectionsManager.getInstance(account).sendRequest(groupReq, (res2, err2) -> {
                if (res2 instanceof TLRPC.messages_Chats && !((TLRPC.messages_Chats) res2).chats.isEmpty()) {
                    deliverChat(account, ((TLRPC.messages_Chats) res2).chats, done);
                } else {
                    done.run(null);
                }
            });
        });
    }

    private static void deliverChat(int account, ArrayList<TLRPC.Chat> chats, Utilities.Callback<TLObject> done) {
        TLRPC.Chat found = null;
        for (int a = 0; a < chats.size(); ++a) {
            final TLRPC.Chat chat = chats.get(a);
            if (chat != null && !(chat instanceof TLRPC.TL_chatEmpty)) {
                found = chat;
                break;
            }
        }
        if (found == null) {
            done.run(null);
            return;
        }
        final ArrayList<TLRPC.Chat> result = new ArrayList<>();
        result.add(found);
        final TLRPC.Chat chat = found;
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController.getInstance(account).putChats(result, false);
            MessagesStorage.getInstance(account).putUsersAndChats(null, result, true, true);
            done.run(chat);
        });
    }
}
