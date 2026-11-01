package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pengram: надёжное открытие профиля из сервисных уведомлений о входе/выходе участника.
 *
 * <p>В обычном Telegram:
 * <ul>
 *   <li>нажатие мимо короткого жирного спана имени по тексту плашки вообще не обрабатывается
 *       или открывает контекстное меню;</li>
 *   <li>пользователь, покинувший группу, часто отсутствует в оперативном кэше
 *       {@link MessagesController#getUser(long)}, из-за чего {@code ProfileActivity.onFragmentCreate()}
 *       молча возвращает {@code false}.</li>
 * </ul>
 * Здесь применяется многоступенчатое разрешение без блокировки UI-потока:
 * <ol>
 *   <li>Оперативный кэш {@code MessagesController} (0 мс);</li>
 *   <li>Локальная БД {@code MessagesStorage.getUser(uid)} в {@code storageQueue};</li>
 *   <li>Запрос {@code users.getUsers} с {@code inputUserFromMessage} по исходному сервисному сообщению;</li>
 *   <li>Для обычных групп — резервная подгрузка участников через {@code messages.getFullChat}.</li>
 * </ol>
 */
public final class PengramServiceProfileOpener {

    private static final Set<String> IN_FLIGHT = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private PengramServiceProfileOpener() {
    }

    public static boolean isJoinOrLeaveAction(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        final TLRPC.MessageAction action = messageObject.messageOwner.action;
        if (action instanceof TLRPC.TL_messageActionChatAddUser
                || action instanceof TLRPC.TL_messageActionChatJoinedByLink
                || action instanceof TLRPC.TL_messageActionChatJoinedByRequest
                || action instanceof TLRPC.TL_messageActionChatJoinedViaCommunity
                || action instanceof TLRPC.TL_messageActionChatDeleteUser) {
            return true;
        }
        if (messageObject.currentEvent != null && messageObject.currentEvent.action != null) {
            final TLRPC.ChannelAdminLogEventAction ev = messageObject.currentEvent.action;
            return ev instanceof TLRPC.TL_channelAdminLogEventActionParticipantJoin
                    || ev instanceof TLRPC.TL_channelAdminLogEventActionParticipantJoinByInvite
                    || ev instanceof TLRPC.TL_channelAdminLogEventActionParticipantJoinByRequest
                    || ev instanceof TLRPC.TL_channelAdminLogEventActionParticipantLeave
                    || ev instanceof TLRPC.TL_channelAdminLogEventActionParticipantInvite;
        }
        return false;
    }

    /**
     * Восстанавливает user_id, если в момент сборки сервисного текста объект пользователя
     * отсутствовал в памяти и ссылка получила заглушку {@code "0"}.
     */
    public static long resolveFallbackUid(MessageObject messageObject, int spanIndex) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return 0;
        }
        final TLRPC.Message msg = messageObject.messageOwner;
        final TLRPC.MessageAction action = msg.action;
        long fromUid = msg.from_id instanceof TLRPC.TL_peerUser ? msg.from_id.user_id : 0;
        if (fromUid == 0 && messageObject.currentEvent != null && messageObject.currentEvent.user_id > 0) {
            fromUid = messageObject.currentEvent.user_id;
        }
        if (action == null) {
            return fromUid;
        }
        if (action instanceof TLRPC.TL_messageActionChatJoinedByLink
                || action instanceof TLRPC.TL_messageActionChatJoinedByRequest
                || action instanceof TLRPC.TL_messageActionChatJoinedViaCommunity) {
            return fromUid != 0 ? fromUid : action.user_id;
        }
        if (action instanceof TLRPC.TL_messageActionChatDeleteUser) {
            if (spanIndex > 0 && action.user_id != 0) {
                return action.user_id;
            }
            if (fromUid != 0 && (spanIndex == 0 || action.user_id == 0)) {
                return fromUid;
            }
            return action.user_id;
        }
        if (action instanceof TLRPC.TL_messageActionChatAddUser) {
            long targetUid = action.user_id;
            if (targetUid == 0 && action.users != null && !action.users.isEmpty()) {
                int idx = Math.max(0, Math.min(action.users.size() - 1, spanIndex > 0 ? spanIndex - 1 : 0));
                targetUid = action.users.get(idx);
            }
            if (spanIndex > 0 && targetUid != 0) {
                return targetUid;
            }
            if (fromUid != 0 && (spanIndex == 0 || targetUid == 0)) {
                return fromUid;
            }
            return targetUid;
        }
        return fromUid != 0 ? fromUid : action.user_id;
    }

    /**
     * Если уведомление о входе/выходе относится ровно к одному участнику, возвращает его UID.
     * Если в уведомлении два разных человека («А добавил Б»), возвращает 0.
     */
    public static long singleParticipantUid(MessageObject messageObject) {
        if (!isJoinOrLeaveAction(messageObject)) {
            return 0;
        }
        final TLRPC.Message msg = messageObject.messageOwner;
        final TLRPC.MessageAction action = msg.action;
        long fromUid = msg.from_id instanceof TLRPC.TL_peerUser ? msg.from_id.user_id : 0;
        if (fromUid == 0 && messageObject.currentEvent != null && messageObject.currentEvent.user_id > 0) {
            fromUid = messageObject.currentEvent.user_id;
        }
        long unique = 0;

        if (fromUid > 0) {
            unique = fromUid;
        }
        if (action != null && action.user_id > 0) {
            if (unique != 0 && unique != action.user_id) {
                return 0;
            }
            unique = action.user_id;
        }
        if (action != null && action.users != null) {
            for (int i = 0; i < action.users.size(); i++) {
                final Long u = action.users.get(i);
                if (u != null && u > 0) {
                    if (unique != 0 && unique != u) {
                        return 0;
                    }
                    unique = u;
                }
            }
        }
        return unique;
    }

    /**
     * Возвращает основного участника события входа/выхода при тапе по плашке целиком
     * (например, если в тексте нет спанов или пользователь нажал между словами):
     * <ul>
     *   <li>«Вас добавил А» / «Вас исключил А» → профиль А;</li>
     *   <li>«А добавил Б» / «А исключил Б» → профиль Б (того, кто вошёл / вышел);</li>
     *   <li>«А вступил» / «А покинул» → профиль А.</li>
     * </ul>
     */
    public static long primaryParticipantUid(MessageObject messageObject) {
        if (!isJoinOrLeaveAction(messageObject)) {
            return 0;
        }
        final long single = singleParticipantUid(messageObject);
        if (single > 0) {
            return single;
        }
        final TLRPC.Message msg = messageObject.messageOwner;
        final TLRPC.MessageAction action = msg.action;
        final long selfUid = UserConfig.getInstance(messageObject.currentAccount).getClientUserId();
        long fromUid = msg.from_id instanceof TLRPC.TL_peerUser ? msg.from_id.user_id : 0;
        if (fromUid == 0 && messageObject.currentEvent != null && messageObject.currentEvent.user_id > 0) {
            fromUid = messageObject.currentEvent.user_id;
        }
        if (action instanceof TLRPC.TL_messageActionChatDeleteUser) {
            if (action.user_id == selfUid && fromUid > 0) {
                return fromUid;
            }
            return action.user_id > 0 ? action.user_id : fromUid;
        }
        if (action instanceof TLRPC.TL_messageActionChatAddUser) {
            long target = action.user_id;
            if (target == 0 && action.users != null && !action.users.isEmpty()) {
                target = action.users.get(0);
            }
            if (target == selfUid && fromUid > 0 && fromUid != selfUid) {
                return fromUid;
            }
            return target > 0 ? target : fromUid;
        }
        return fromUid;
    }

    private static final long REMOTE_TIMEOUT_MS = 8000L;

    /**
     * Гарантирует наличие {@link TLRPC.User} в {@link MessagesController} перед вызовом {@code openAction}.
     */
    public static void ensureUserAndOpen(int currentAccount, long uid, MessageObject serviceMessage, Runnable openAction) {
        if (openAction == null) {
            return;
        }
        if (uid <= 0) {
            openAction.run();
            return;
        }
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        if (controller.getUser(uid) != null) {
            openAction.run();
            return;
        }
        final String flightKey = currentAccount + ":" + uid;
        if (!IN_FLIGHT.add(flightKey)) {
            return;
        }
        final AtomicReference<Runnable> actionRef = new AtomicReference<>(openAction);
        final AtomicInteger activeReqId = new AtomicInteger(0);
        final Runnable timeoutWatchdog = () -> {
            actionRef.set(null);
            IN_FLIGHT.remove(flightKey);
            final int reqId = activeReqId.getAndSet(0);
            if (reqId != 0) {
                try {
                    ConnectionsManager.getInstance(currentAccount).cancelRequest(reqId, true);
                } catch (Throwable ignore) {
                }
            }
        };
        AndroidUtilities.runOnUIThread(timeoutWatchdog, REMOTE_TIMEOUT_MS);

        final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            TLRPC.User dbUser = null;
            try {
                dbUser = storage.getUser(uid);
            } catch (Throwable ignore) {
            }
            final TLRPC.User foundLocal = dbUser;
            AndroidUtilities.runOnUIThread(() -> {
                if (actionRef.get() == null) {
                    return;
                }
                if (foundLocal != null) {
                    AndroidUtilities.cancelRunOnUIThread(timeoutWatchdog);
                    IN_FLIGHT.remove(flightKey);
                    controller.putUser(foundLocal, true);
                    final Runnable act = actionRef.getAndSet(null);
                    if (act != null) {
                        act.run();
                    }
                    return;
                }
                fetchFromMessageAndOpen(currentAccount, uid, serviceMessage, flightKey, actionRef, activeReqId, timeoutWatchdog);
            });
        });
    }

    private static void fetchFromMessageAndOpen(
            int currentAccount,
            long uid,
            MessageObject serviceMessage,
            String flightKey,
            AtomicReference<Runnable> actionRef,
            AtomicInteger activeReqId,
            Runnable timeoutWatchdog
    ) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        final long dialogId = serviceMessage != null ? serviceMessage.getDialogId() : 0;
        final int msgId = serviceMessage != null ? serviceMessage.getId() : 0;

        if (dialogId != 0 && msgId > 0) {
            final TLRPC.InputPeer peer = controller.getInputPeer(dialogId);
            if (peer != null && !(peer instanceof TLRPC.TL_inputPeerEmpty)) {
                final TLRPC.TL_inputUserFromMessage inputUser = new TLRPC.TL_inputUserFromMessage();
                inputUser.peer = peer;
                inputUser.msg_id = msgId;
                inputUser.user_id = uid;

                final TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
                req.id.add(inputUser);

                final int reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    activeReqId.set(0);
                    if (actionRef.get() == null) {
                        return;
                    }
                    if (response instanceof Vector) {
                        final Vector vector = (Vector) response;
                        final ArrayList<TLRPC.User> fetched = new ArrayList<>(vector.objects.size());
                        for (int i = 0; i < vector.objects.size(); i++) {
                            Object o = vector.objects.get(i);
                            if (o instanceof TLRPC.User && !(o instanceof TLRPC.TL_userEmpty)) {
                                fetched.add((TLRPC.User) o);
                            }
                        }
                        if (!fetched.isEmpty()) {
                            controller.putUsers(fetched, false);
                            MessagesStorage.getInstance(currentAccount).putUsersAndChats(fetched, null, true, true);
                        }
                    }
                    if (controller.getUser(uid) != null) {
                        AndroidUtilities.cancelRunOnUIThread(timeoutWatchdog);
                        IN_FLIGHT.remove(flightKey);
                        final Runnable act = actionRef.getAndSet(null);
                        if (act != null) {
                            act.run();
                        }
                        return;
                    }
                    fetchFromChatFallback(currentAccount, uid, dialogId, flightKey, actionRef, activeReqId, timeoutWatchdog);
                }));
                activeReqId.set(reqId);
                return;
            }
        }
        fetchFromChatFallback(currentAccount, uid, dialogId, flightKey, actionRef, activeReqId, timeoutWatchdog);
    }

    private static void fetchFromChatFallback(
            int currentAccount,
            long uid,
            long dialogId,
            String flightKey,
            AtomicReference<Runnable> actionRef,
            AtomicInteger activeReqId,
            Runnable timeoutWatchdog
    ) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        if (dialogId < 0) {
            final long chatId = -dialogId;
            final TLRPC.Chat chat = controller.getChat(chatId);
            if (chat != null && !ChatObject.isChannel(chat)) {
                final TLRPC.TL_messages_getFullChat req = new TLRPC.TL_messages_getFullChat();
                req.chat_id = chatId;
                final int reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    activeReqId.set(0);
                    AndroidUtilities.cancelRunOnUIThread(timeoutWatchdog);
                    IN_FLIGHT.remove(flightKey);
                    if (response instanceof TLRPC.TL_messages_chatFull) {
                        final TLRPC.TL_messages_chatFull res = (TLRPC.TL_messages_chatFull) response;
                        controller.putUsers(res.users, false);
                        controller.putChats(res.chats, false);
                        MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
                    }
                    final Runnable act = actionRef.getAndSet(null);
                    if (act != null && controller.getUser(uid) != null) {
                        act.run();
                    }
                }));
                activeReqId.set(reqId);
                return;
            }
        }
        AndroidUtilities.cancelRunOnUIThread(timeoutWatchdog);
        IN_FLIGHT.remove(flightKey);
        final Runnable act = actionRef.getAndSet(null);
        if (act != null && controller.getUser(uid) != null) {
            act.run();
        }
    }
}
