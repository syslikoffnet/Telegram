package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

/**
 * Pengram: пересылка копией от своего лица.
 *
 * Обычная пересылка — это просьба к серверу повторить чужое сообщение, и в
 * защищённых чатах сервер на неё отвечает отказом. Здесь сообщение собирается
 * заново: текст берётся как есть, а файл поднимается с диска и уходит новым
 * вложением. Поэтому копия работает и там, где запрещены пересылка и
 * сохранение, и в секретных чатах, и с удалёнками, и с одноразовыми медиа.
 */
public final class PengramCopySender {

    private PengramCopySender() {
    }

    // ------------------------------------------------------------ когда нужна копия

    /** сообщение, которое сервер отказался бы переслать обычным способом */
    public static boolean isProtected(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (message.messageOwner.noforwards) {
            return true;
        }
        if (message.pengramDeleted) {
            return true;   // сообщения больше нет на сервере — пересылать нечего
        }
        if (message.isSecretMedia() || message.messageOwner.ttl_period != 0 || message.messageOwner.ttl != 0) {
            return true;
        }
        if (DialogObject.isEncryptedDialog(message.getDialogId())) {
            return true;
        }
        return message.getId() <= 0;
    }

    /** стоит ли подменить обычную пересылку копией */
    public static boolean shouldCopy(ArrayList<MessageObject> messages, long targetDialogId) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        if (!PengramConfig.isBypassingForwardRestrictions()) {
            return false;
        }
        if (DialogObject.isEncryptedDialog(targetDialogId)) {
            return false;   // в секретный чат Telegram умеет отправлять сам
        }
        for (int a = 0; a < messages.size(); a++) {
            if (isProtected(messages.get(a))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ отправка

    public static void sendCopies(int account, ArrayList<MessageObject> messages, long dialogId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        final ArrayList<MessageObject> queue = new ArrayList<>(messages);
        AndroidUtilities.runOnUIThread(() -> next(account, queue, 0, dialogId));
    }

    /** сообщения уходят по очереди: следующее — только когда предыдущее собрано */
    private static void next(int account, ArrayList<MessageObject> queue, int index, long dialogId) {
        if (index >= queue.size()) {
            return;
        }
        final MessageObject message = queue.get(index);
        final Runnable goOn = () -> next(account, queue, index + 1, dialogId);
        if (message == null) {
            goOn.run();
            return;
        }
        try {
            sendOne(account, message, dialogId, goOn);
        } catch (Throwable e) {
            FileLog.e(e);
            goOn.run();
        }
    }

    private static void sendOne(int account, MessageObject message, long dialogId, Runnable done) {
        final TLRPC.MessageMedia media = message.messageOwner != null ? message.messageOwner.media : null;
        final boolean hasPhoto = media != null && media.photo instanceof TLRPC.TL_photo;
        final boolean hasDocument = media != null && media.document instanceof TLRPC.TL_document;

        if ((!hasPhoto && !hasDocument) || !isProtected(message)) {
            // текст, геопозиция, контакт и всё, что сервер и так отдаёт копией:
            // зачем качать и заливать файл заново, если можно сослаться на него
            SendMessagesHelper.getInstance(account).processForwardFromMyName(message, dialogId, 0, 0, null);
            done.run();
            return;
        }

        final String ready = localPath(account, message);
        if (ready != null) {
            sendFile(account, message, dialogId, ready);
            done.run();
            return;
        }

        download(account, message, path -> {
            if (path != null) {
                sendFile(account, message, dialogId, path);
            } else {
                // файла нет и скачать не вышло — пробуем обычным путём
                SendMessagesHelper.getInstance(account).processForwardFromMyName(message, dialogId, 0, 0, null);
            }
            done.run();
        });
    }

    /** собрать новое сообщение из лежащего на диске файла */
    private static void sendFile(int account, MessageObject message, long dialogId, String path) {
        final TLRPC.MessageMedia media = message.messageOwner.media;
        final String caption = message.messageOwner.message;
        final ArrayList<TLRPC.MessageEntity> entities = copyEntities(message.messageOwner.entities);
        final boolean spoiler = media != null && media.spoiler;

        try {
            if (media.document instanceof TLRPC.TL_document) {
                final TLRPC.TL_document document = cloneAsNew((TLRPC.TL_document) media.document);
                if (document == null) {
                    SendMessagesHelper.getInstance(account).processForwardFromMyName(message, dialogId, 0, 0, null);
                    return;
                }
                SendMessagesHelper.getInstance(account).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(document, null, path, dialogId,
                                null, null, caption, entities, null, null, true, 0, 0, 0,
                                null, null, false, spoiler));
            } else {
                SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(account), path, null,
                        dialogId, null, null, null, caption, entities, null, null, 0, null, true, 0, 0, null);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------ файл на диске

    /** путь к уже скачанному файлу или null */
    private static String localPath(int account, MessageObject message) {
        try {
            final String attach = message.messageOwner != null ? message.messageOwner.attachPath : null;
            if (!TextUtils.isEmpty(attach)) {
                final File file = new File(attach);
                if (file.exists() && file.length() > 0) {
                    return file.getAbsolutePath();
                }
            }
            final File file = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
            if (file != null && file.exists() && file.length() > 0) {
                return file.getAbsolutePath();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /** скачать вложение и позвать обратно, когда файл окажется на диске */
    private static void download(int account, MessageObject message, Utilities.Callback<String> callback) {
        final TLRPC.MessageMedia media = message.messageOwner != null ? message.messageOwner.media : null;
        if (media == null) {
            callback.run(null);
            return;
        }
        final TLRPC.Document document = media.document;
        final TLRPC.PhotoSize photoSize = media.photo != null
                ? FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize())
                : null;
        final String fileName = document instanceof TLRPC.TL_document
                ? FileLoader.getAttachFileName(document)
                : (photoSize != null ? FileLoader.getAttachFileName(photoSize) : null);
        if (TextUtils.isEmpty(fileName)) {
            callback.run(null);
            return;
        }

        final NotificationCenter center = NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
        final boolean[] finished = new boolean[1];
        final Runnable finish = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            if (holder[0] != null) {
                center.removeObserver(holder[0], NotificationCenter.fileLoaded);
                center.removeObserver(holder[0], NotificationCenter.fileLoadFailed);
            }
            callback.run(localPath(account, message));
        };

        holder[0] = (id, acc, args) -> {
            if (args == null || args.length == 0 || !(args[0] instanceof String)) {
                return;
            }
            if (fileName.equals(args[0])) {
                finish.run();
            }
        };
        center.addObserver(holder[0], NotificationCenter.fileLoaded);
        center.addObserver(holder[0], NotificationCenter.fileLoadFailed);

        try {
            if (document instanceof TLRPC.TL_document) {
                FileLoader.getInstance(account).loadFile(document, message, FileLoader.PRIORITY_HIGH, 0);
            } else if (photoSize != null) {
                FileLoader.getInstance(account).loadFile(ImageLocation.getForObject(photoSize, media.photo),
                        message, null, FileLoader.PRIORITY_HIGH, 0);
            } else {
                finish.run();
                return;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            finish.run();
            return;
        }
        // ждём не вечно: минута без файла — и отправляем тем, что есть
        AndroidUtilities.runOnUIThread(finish, 60_000L);
    }

    // ------------------------------------------------------------ мелочи

    /**
     * Копия файла без серверных опознавательных знаков.
     *
     * Обнулённый access_hash — это способ сказать отправке: «файл новый, залей
     * его с диска». Все признаки (кружок, голосовое, стикер, видео) живут в
     * attributes и переезжают вместе с копией.
     */
    private static TLRPC.TL_document cloneAsNew(TLRPC.TL_document source) {
        try {
            final NativeByteBuffer buffer = new NativeByteBuffer(source.getObjectSize());
            source.serializeToStream(buffer);
            buffer.position(0);
            final TLRPC.Document copy = TLRPC.Document.TLdeserialize(buffer, buffer.readInt32(false), false);
            buffer.reuse();
            if (!(copy instanceof TLRPC.TL_document)) {
                return null;
            }
            final TLRPC.TL_document document = (TLRPC.TL_document) copy;
            document.id = 0;
            document.access_hash = 0;
            document.file_reference = new byte[0];
            document.dc_id = 0;
            return document;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static ArrayList<TLRPC.MessageEntity> copyEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        return new ArrayList<>(entities);
    }
}
