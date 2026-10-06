package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Pengram: ответ на удалённое сообщение в личке.
 *
 * Сообщения, которые собеседник удалил, Pengram оставляет в чате — но на
 * сервере их больше нет. Если ответить на такое сообщение обычным способом,
 * у собеседника ответ повиснет в пустоте: цитировать нечего, и он просто не
 * поймёт, о чём речь.
 *
 * Поэтому текст удалённого подклеивается прямо в ваше сообщение цитатой.
 * Подпись с именем автора — отдельная настройка: в личке собеседник и так
 * знает, кто это написал, поэтому кому-то она нужна, а кому-то мешает.
 */
public final class PengramDeletedReply {

    /** сколько символов удалённого берём в цитату — дальше многоточие */
    private static final int MAX_QUOTE = 300;
    /** общий потолок, чтобы собранное сообщение не упёрлось в лимит сервера */
    private static final int MAX_TOTAL = 3800;

    private PengramDeletedReply() {
    }

    /**
     * Дополняет исходящее сообщение цитатой удалённого.
     *
     * Вызывается в самом начале отправки, до того как параметры разобраны на
     * локальные переменные — так правка одинаково работает для всех путей,
     * которые ведут к {@code sendMessage}.
     */
    public static void decorate(SendMessagesHelper.SendMessageParams params) {
        try {
            if (params == null || params.retryMessageObject != null) {
                return;   // повтор отправки: текст уже собран, второй раз цитату не клеим
            }
            if (TextUtils.isEmpty(params.message) || params.replyToMsg == null) {
                return;
            }
            if (!PengramConfig.isDeletedReplyQuote()) {
                return;
            }
            if (!params.replyToMsg.pengramDeleted) {
                return;
            }
            if (!DialogObject.isUserDialog(params.peer) || DialogObject.isEncryptedDialog(params.peer)) {
                return;   // только обычные личные чаты
            }
            final String quote = quoteText(params.replyToMsg);
            if (TextUtils.isEmpty(quote)) {
                return;
            }
            final String name = PengramConfig.isDeletedReplySigned() ? senderName(params.replyToMsg) : null;
            final ArrayList<TLRPC.MessageEntity> quoteEntities = new ArrayList<>();
            final String head = signedBlock(name, quote, quoteEntities);
            final String prefix = head + "\n\n";
            if (prefix.length() + params.message.length() > MAX_TOTAL) {
                return;   // вместе не влезают — лучше отправить как есть, чем обрезать мысль
            }

            // цитата становится началом сообщения, поэтому все метки исходного
            // текста (жирный, ссылки, эмодзи) уезжают вправо ровно на её длину
            final int shift = prefix.length();
            if (params.entities != null) {
                for (int a = 0; a < params.entities.size(); a++) {
                    final TLRPC.MessageEntity entity = params.entities.get(a);
                    if (entity != null) {
                        entity.offset += shift;
                    }
                }
            } else {
                params.entities = new ArrayList<>();
            }
            params.entities.addAll(0, quoteEntities);
            params.message = prefix + params.message;
        } catch (Throwable e) {
            FileLog.e(e);   // не даём украшательству сорвать саму отправку
        }
    }

    /**
     * Чужой текст с подписью автора — один и тот же вид во всём приложении.
     *
     * И ответ на удалённое, и копия «от своего лица» показывают одно и то же:
     * жирное имя, двоеточие, под ним текст, и всё это в блок-цитате. Вид
     * собран здесь в одном месте, чтобы две фичи не разъезжались по мелочам.
     *
     * Метки форматирования самого текста, если они переданы, сдвигаются на
     * длину добавленной строки с именем.
     */
    public static String signedBlock(String name, String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (TextUtils.isEmpty(name)) {
            return text;
        }
        final String body = text == null ? "" : text;
        final String head = body.isEmpty() ? name : name + ":\n" + body;
        final int shift = head.length() - body.length();
        if (entities != null) {
            for (int a = 0; a < entities.size(); a++) {
                final TLRPC.MessageEntity entity = entities.get(a);
                if (entity != null) {
                    entity.offset += shift;
                }
            }
            final TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
            bold.offset = 0;
            bold.length = name.length();
            entities.add(0, bold);
            final TLRPC.TL_messageEntityBlockquote blockquote = new TLRPC.TL_messageEntityBlockquote();
            blockquote.offset = 0;
            blockquote.length = head.length();
            entities.add(0, blockquote);
        }
        return head;
    }

    /** текст удалённого сообщения, подрезанный до разумной длины */
    private static String quoteText(MessageObject message) {
        CharSequence text = message.messageOwner != null && !TextUtils.isEmpty(message.messageOwner.message)
                ? message.messageOwner.message
                : message.messageText;
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String result = text.toString().trim();
        if (result.length() > MAX_QUOTE) {
            // режем по пробелу, чтобы цитата не обрывалась на середине слова
            int cut = result.lastIndexOf(' ', MAX_QUOTE);
            if (cut < MAX_QUOTE / 2) {
                cut = MAX_QUOTE;
            }
            result = result.substring(0, cut).trim() + "\u2026";
        }
        return result;
    }

    /** как подписать цитату: имя автора удалённого сообщения */
    private static String senderName(MessageObject message) {
        try {
            final int account = message.currentAccount;
            final long from = message.getFromChatId();
            if (from > 0) {
                final TLRPC.User user = MessagesController.getInstance(account).getUser(from);
                if (user != null) {
                    final String name = ContactsController.formatName(user.first_name, user.last_name);
                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                    final String username = UserObject.getPublicUsername(user);
                    if (!TextUtils.isEmpty(username)) {
                        return "@" + username;
                    }
                }
            } else if (from < 0) {
                final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-from);
                if (chat != null && !TextUtils.isEmpty(chat.title)) {
                    return chat.title;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }
}
