package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pengram: надёжное открытие профиля из сервисных уведомлений о входе/выходе участника.
 *
 * <p>В обычном Telegram пользователь, покинувший группу, часто отсутствует в оперативном
 * кэше {@link MessagesController#getUser(long)}, из-за чего {@code ProfileActivity.onFragmentCreate()}
 * молча возвращает {@code false}. Здесь применяется 3-ступенчатое разрешение без фризов UI:
 * <ol>
 *   <li>Оперативный кэш {@code MessagesController} (0 мс);</li>
 *   <li>Локальная БД {@code MessagesStorage.getUser(uid)} в {@code storageQueue};</li>
 *   <li>Запрос {@code users.getUsers} с {@code inputUserFromMessage} по исходному сервисному сообщению.</li>
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
        return action instanceof TLRPC.TL_messageActionChatAddUser
                || action instanceof TLRPC.TL_messageActionChatJoinedByLink
                || action instanceof TLRPC.TL_messageActionChatJoinedByRequest
                || action instanceof TLRPC.TL_messageActionChatDeleteUser;
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
        final long fromUid = msg.from_id instanceof TLRPC.TL_peerUser ? msg.from_id.user_id : 0;
        if (action == null) {
            return fromUid;
        }
        if (action instanceof TLRPC.TL_messageActionChatJoinedByLink
                || action instanceof TLRPC.TL_messageActionChatJoinedByRequest) {
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
     * Если уведомление о входе/выходе относится ровно к одному участнику, возвращает его UID,
     * чтобы нажатие по любой части компактной сервисной плашки открывало профиль без промахов.
     * Если в уведомлении два разных человека («А добавил Б»), возвращает 0 — выбор идёт по спану.
     */
    public static long singleParticipantUid(MessageObject messageObject) {
        if (!isJoinOrLeaveAction(messageObject)) {
            return 0;
        }
        final TLRPC.Message msg = messageObject.messageOwner;
        final TLRPC.MessageAction action = msg.action;
        final long fromUid = msg.from_id instanceof TLRPC.TL_peerUser ? msg.from_id.user_id : 0;
        long unique = 0;

        if (fromUid > 0) {
            unique = fromUid;
        }
        if (action.user_id > 0) {
            if (unique != 0 && unique != action.user_id) {
                return 0;
            }
            unique = action.user_id;
        }
        if (action.users != null) {
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
        final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            TLRPC.User dbUser = null;
            try {
                dbUser = storage.getUser(uid);
            } catch (Throwable ignore) {
            }
            final TLRPC.User foundLocal = dbUser;
            AndroidUtilities.runOnUIThread(() -> {
                if (foundLocal != null) {
                    IN_FLIGHT.remove(flightKey);
                    controller.putUser(foundLocal, true);
                    openAction.run();
                    return;
                }
                fetchFromMessageAndOpen(currentAccount, uid, serviceMessage, flightKey, openAction);
            });
        });
    }

    private static void fetchFromMessageAndOpen(int currentAccount, long uid, MessageObject serviceMessage, String flightKey, Runnable openAction) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        if (serviceMessage == null || serviceMessage.getId() <= 0 || serviceMessage.getDialogId() == 0) {
            IN_FLIGHT.remove(flightKey);
            openAction.run();
            return;
        }
        final TLRPC.InputPeer peer = controller.getInputPeer(serviceMessage.getDialogId());
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
            IN_FLIGHT.remove(flightKey);
            openAction.run();
            return;
        }
        final TLRPC.TL_inputUserFromMessage inputUser = new TLRPC.TL_inputUserFromMessage();
        inputUser.peer = peer;
        inputUser.msg_id = serviceMessage.getId();
        inputUser.user_id = uid;

        final TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        req.id.add(inputUser);

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            IN_FLIGHT.remove(flightKey);
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
                openAction.run();
            }
        }));
    }
}
