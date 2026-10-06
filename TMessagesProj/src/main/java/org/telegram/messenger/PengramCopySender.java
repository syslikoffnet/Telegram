package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Pengram: пересылка копией от своего лица.
 *
 * Обычная пересылка — это просьба к серверу повторить чужое сообщение, и в
 * защищённых чатах сервер на неё отвечает отказом. Здесь сообщение собирается
 * заново: текст уходит новым текстом, а файл поднимается с диска и заливается
 * как собственное вложение. Поэтому копия работает и там, где запрещены
 * пересылка и сохранение, и в секретных чатах, и с удалёнками, и с одноразовыми
 * медиа — для сервера это просто новое сообщение от вас.
 *
 * Важные мелочи, на которых всё обычно и ломается:
 *  • файл копируется во временный, иначе заливка трогает кэш исходного чата;
 *  • у копии документа стираются серверные опознавательные знаки и превью,
 *    иначе загрузка уходит в ошибку file reference;
 *  • размер проставляется по реальному файлу — сервер сверяет его при заливке;
 *  • если файла нет и скачать не вышло, пользователь видит понятную причину,
 *    а не молчаливо пропавшее сообщение.
 */
public final class PengramCopySender {

    /** сколько ждём докачку файла, прежде чем признать неудачу */
    private static final long DOWNLOAD_TIMEOUT = 90_000L;

    public interface Result {
        /** sent — сколько ушло, failed — сколько не получилось собрать */
        void onDone(int sent, int failed);
    }

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
        if (message.getId() <= 0) {
            return true;
        }
        // чат целиком с запретом пересылки — проверяем сам чат, а не только флаг сообщения
        try {
            final long dialogId = message.getDialogId();
            if (dialogId < 0) {
                final TLRPC.Chat chat = MessagesController.getInstance(message.currentAccount)
                        .getChat(-dialogId);
                if (chat != null && chat.noforwards) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
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
        sendCopies(account, messages, dialogId, null);
    }

    public static void sendCopies(int account, ArrayList<MessageObject> messages, long dialogId, Result result) {
        if (messages == null || messages.isEmpty()) {
            if (result != null) {
                result.onDone(0, 0);
            }
            return;
        }
        final ArrayList<MessageObject> queue = new ArrayList<>(messages);
        final int[] counters = new int[2];
        AndroidUtilities.runOnUIThread(() -> next(account, queue, 0, dialogId, counters, result));
    }

    /** сообщения уходят по очереди: следующее — только когда предыдущее собрано */
    private static void next(int account, ArrayList<MessageObject> queue, int index, long dialogId,
                             int[] counters, Result result) {
        if (index >= queue.size()) {
            if (result != null) {
                result.onDone(counters[0], counters[1]);
            }
            return;
        }
        final MessageObject message = queue.get(index);
        final Utilities.Callback<Boolean> goOn = ok -> {
            if (ok != null && ok) {
                counters[0]++;
            } else {
                counters[1]++;
            }
            next(account, queue, index + 1, dialogId, counters, result);
        };
        if (message == null) {
            goOn.run(false);
            return;
        }
        try {
            sendOne(account, message, dialogId, goOn);
        } catch (Throwable e) {
            FileLog.e(e);
            goOn.run(false);
        }
    }

    private static void sendOne(int account, MessageObject message, long dialogId, Utilities.Callback<Boolean> done) {
        final TLRPC.MessageMedia media = message.messageOwner != null ? message.messageOwner.media : null;
        final boolean hasPhoto = media != null && media.photo instanceof TLRPC.TL_photo;
        final boolean hasDocument = media != null && media.document instanceof TLRPC.TL_document;

        if (!hasPhoto && !hasDocument) {
            done.run(sendWithoutFile(account, message, dialogId));
            return;
        }

        final String ready = localPath(account, message);
        if (ready != null) {
            done.run(sendFile(account, message, dialogId, ready));
            return;
        }

        download(account, message, path -> {
            if (path != null) {
                done.run(sendFile(account, message, dialogId, path));
            } else {
                // файла нет и скачать не вышло: хотя бы подпись не теряем
                done.run(sendWithoutFile(account, message, dialogId));
            }
        });
    }

    // ------------------------------------------------------------ сообщения без файла

    /** текст, ссылка, геопозиция, контакт, опрос — собираем заново, без обращения к серверу-источнику */
    private static boolean sendWithoutFile(int account, MessageObject message, long dialogId) {
        final SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
        final TLRPC.MessageMedia media = message.messageOwner != null ? message.messageOwner.media : null;
        final String text = message.messageOwner != null ? message.messageOwner.message : null;
        final ArrayList<TLRPC.MessageEntity> entities = copyEntities(message.messageOwner != null
                ? message.messageOwner.entities : null);
        try {
            if (media instanceof TLRPC.TL_messageMediaGeo
                    || media instanceof TLRPC.TL_messageMediaVenue
                    || media instanceof TLRPC.TL_messageMediaGeoLive) {
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of(media, dialogId,
                        null, null, null, null, true, 0, 0));
                return true;
            }
            if (media instanceof TLRPC.TL_messageMediaContact) {
                final TLRPC.TL_messageMediaContact contact = (TLRPC.TL_messageMediaContact) media;
                final TLRPC.TL_user user = new TLRPC.TL_user();
                user.id = contact.user_id;
                user.phone = contact.phone_number;
                user.first_name = contact.first_name;
                user.last_name = contact.last_name;
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of(user, dialogId,
                        null, null, null, null, true, 0, 0));
                return true;
            }
            if (!TextUtils.isEmpty(text)) {
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId,
                        null, null, null, true, entities, null, null, true, 0, 0, null, false));
                return true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        // последний шанс: пусть сервер попробует сам — вдруг сообщение не защищено
        try {
            if (!isProtected(message)) {
                SendMessagesHelper.getInstance(account)
                        .processForwardFromMyName(message, dialogId, 0, 0, null);
                return true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    // ------------------------------------------------------------ сообщения с файлом

    /** собрать новое сообщение из лежащего на диске файла */
    private static boolean sendFile(int account, MessageObject message, long dialogId, String sourcePath) {
        final TLRPC.MessageMedia media = message.messageOwner.media;
        final String caption = message.messageOwner.message;
        final ArrayList<TLRPC.MessageEntity> entities = copyEntities(message.messageOwner.entities);
        final boolean spoiler = media != null && media.spoiler;

        // работаем с копией: заливка не должна трогать кэш исходного чата
        final String path = tempCopy(sourcePath, message);
        if (path == null) {
            return sendWithoutFile(account, message, dialogId);
        }

        try {
            if (media != null && media.document instanceof TLRPC.TL_document) {
                final TLRPC.TL_document document = cloneAsNew((TLRPC.TL_document) media.document, path);
                if (document == null) {
                    return sendWithoutFile(account, message, dialogId);
                }
                final HashMap<String, String> params = new HashMap<>();
                params.put("originalPath", path);
                SendMessagesHelper.getInstance(account).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(document, null, path, dialogId,
                                null, null, caption, entities, null, params, true, 0, 0, 0,
                                null, null, false, spoiler));
                return true;
            }
            SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(account), path, null,
                    dialogId, null, null, null, caption, entities, null, null, 0, null, true, 0, 0, null);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return sendWithoutFile(account, message, dialogId);
        }
    }

    /**
     * Временная копия файла.
     *
     * Отправка переносит и переименовывает вложения, а исходник лежит в кэше
     * чата, откуда мы копируем: трогать его нельзя, иначе в исходном чате
     * медиа «пропадёт».
     */
    private static String tempCopy(String sourcePath, MessageObject message) {
        try {
            final File source = new File(sourcePath);
            if (!source.exists() || source.length() == 0) {
                return null;
            }
            String extension = "";
            final int dot = source.getName().lastIndexOf('.');
            if (dot > 0) {
                extension = source.getName().substring(dot);
            }
            if (TextUtils.isEmpty(extension)) {
                extension = guessExtension(message);
            }
            final File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            final File target = new File(dir, "pengram_copy_" + Math.abs(message.getId())
                    + "_" + System.currentTimeMillis() + extension);
            try (FileInputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(target)) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            if (target.length() == 0) {
                target.delete();
                return null;
            }
            return target.getAbsolutePath();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String guessExtension(MessageObject message) {
        if (message == null) {
            return ".dat";
        }
        if (message.isVideo() || message.isRoundVideo() || message.isGif()) {
            return ".mp4";
        }
        if (message.isVoice()) {
            return ".ogg";
        }
        if (message.isMusic()) {
            return ".mp3";
        }
        if (message.isPhoto()) {
            return ".jpg";
        }
        return ".dat";
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
        AndroidUtilities.runOnUIThread(finish, DOWNLOAD_TIMEOUT);
    }

    // ------------------------------------------------------------ мелочи

    /**
     * Копия документа без серверных опознавательных знаков.
     *
     * Обнулённый id и access_hash — это способ сказать отправке: «файл новый,
     * залей его с диска». Превью тоже убираем: они ссылаются на чужое
     * хранилище и ломают заливку. Все признаки (кружок, голосовое, стикер,
     * видео) живут в attributes и переезжают вместе с копией.
     */
    private static TLRPC.TL_document cloneAsNew(TLRPC.TL_document source, String path) {
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
            document.date = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
            if (document.thumbs != null) {
                document.thumbs.clear();
            }
            if (document.video_thumbs != null) {
                document.video_thumbs.clear();
            }
            try {
                final File file = new File(path);
                if (file.exists()) {
                    document.size = file.length();
                }
            } catch (Throwable ignore) {
            }
            if (TextUtils.isEmpty(document.mime_type)) {
                document.mime_type = "application/octet-stream";
            }
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
