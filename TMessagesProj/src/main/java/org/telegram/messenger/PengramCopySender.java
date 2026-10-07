package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
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

    /** кому-то (обычно открытому чату) интересно, как идёт отправка копий */
    public interface ProgressListener {
        void onCopyProgress();
    }

    private static final ArrayList<ProgressListener> listeners = new ArrayList<>();

    private static long busyDialogId;
    private static int busyTotal;
    private static int busyDone;
    private static boolean busyPreparing;
    private static long busySince;
    /** страховка: если отправка где-то застряла, статус не должен висеть вечно */
    private static final long BUSY_MAX = 15 * 60_000L;

    private PengramCopySender() {
    }

    /** сразу оповестить о состоянии (например, открыли чат во время отправки) */
    public static void notifyProgressNow() {
        notifyProgress();
    }

    public static void addProgressListener(ProgressListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public static void removeProgressListener(ProgressListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    /** идёт ли прямо сейчас отправка копий в этот чат */
    public static boolean isBusy(long dialogId) {
        if (busyTotal <= 0 || busyDialogId != dialogId) {
            return false;
        }
        return android.os.SystemClock.elapsedRealtime() - busySince < BUSY_MAX;
    }

    public static int getTotal() {
        return busyTotal;
    }

    public static int getDone() {
        return busyDone;
    }

    /**
     * Файл ещё качается.
     *
     * Это важное отличие от обычной пересылки: сообщения в чате ещё нет, в
     * ленте ничего не мигает, и без отдельного признака статус выглядел бы
     * зависшим.
     */
    public static boolean isPreparing() {
        return busyTotal > 0 && busyPreparing;
    }

    private static void setBusy(long dialogId, int total, int done, boolean preparing) {
        if (busyTotal <= 0 && total > 0) {
            busySince = android.os.SystemClock.elapsedRealtime();
        }
        busyDialogId = dialogId;
        busyTotal = total;
        busyDone = done;
        busyPreparing = preparing;
        notifyProgress();
    }

    private static void notifyProgress() {
        final ProgressListener[] copy;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                return;
            }
            copy = listeners.toArray(new ProgressListener[0]);
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (ProgressListener listener : copy) {
                try {
                    listener.onCopyProgress();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    // ------------------------------------------------------------ когда нужна копия

    /** сообщение, которое сервер отказался бы переслать обычным способом */
    public static boolean isProtected(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        // удалённое сообщение (pengramDeleted) сюда не светится отдельно:
        // если у него есть медиа, localPath сразу не найдёт файл в кэше
        // (он удалён вместе с сообщением), и download его не восстановит —
        // копия выйдет только если файл уже лежал на диске вне кэша чата.
        if (message.messageOwner.noforwards) {
            return true;
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
        // Запрет копирования у самого чата. Проверяем сырые флаги, а не
        // isChatNoForwards/isUserNoForwards: те при включённом обходе честно
        // отвечают «можно», и мы бы просто отдали сообщение серверу, который
        // в ответ отказал бы.
        try {
            final long dialogId = message.getDialogId();
            if (isPeerRestricted(message.currentAccount, dialogId)) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * Чат, из которого нельзя копировать.
     *
     * Личные чаты сюда входят наравне с группами и каналами: запрет там живёт
     * не на сообщении, а в настройках собеседника, и без этой проверки текст
     * из такого чата уходил бы обычной пересылкой — то есть никуда.
     */
    public static boolean isPeerRestricted(int account, long dialogId) {
        try {
            if (dialogId < 0) {
                final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
                return chat != null && chat.noforwards;
            }
            if (dialogId > 0) {
                final TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(dialogId);
                return full != null && (full.noforwards_peer_enabled || full.noforwards_my_enabled);
            }
        } catch (Throwable e) {
            FileLog.e(e);
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
        // В секретный чат нельзя ничего переслать — даже наше собственное.
        // Если что-то защищено, а цель секретная, сюда даже не приходим:
        // мы просто не можем. Это меню не показывает пункт «Переслать».
        if (DialogObject.isEncryptedDialog(targetDialogId)) {
            return false;
        }
        for (int a = 0; a < messages.size(); a++) {
            if (isProtected(messages.get(a))) {
                return true;
            }
        }
        return false;
    }

    /** то же самое, но когда на руках только чат-источник (например, текст без медиа) */
    public static boolean shouldCopyFrom(int account, long sourceDialogId, long targetDialogId) {
        if (!PengramConfig.isBypassingForwardRestrictions()) {
            return false;
        }
        if (DialogObject.isEncryptedDialog(targetDialogId)) {
            return false;
        }
        return isPeerRestricted(account, sourceDialogId);
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
        setBusy(dialogId, queue.size(), 0, false);
        AndroidUtilities.runOnUIThread(() -> next(account, queue, 0, dialogId, counters, result));
    }

    /** сообщения уходят по очереди: следующее — только когда предыдущее собрано */
    private static void next(int account, ArrayList<MessageObject> queue, int index, long dialogId,
                             int[] counters, Result result) {
        if (index >= queue.size()) {
            setBusy(0, 0, 0, false);
            if (result != null) {
                result.onDone(counters[0], counters[1]);
            }
            return;
        }
        setBusy(dialogId, queue.size(), index, false);
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
        // В секретных чатах медиа приходит не как TL_photo/TL_document, а как
        // их «зашифрованные» родственники. Раньше такие сообщения сюда не
        // попадали и уходили без файла — то есть фактически не пересылались.
        // Документ приоритетнее фото: у веб-страницы одновременно есть и обложка
        // (photo), и вложенный объект (document); забирать надо вместе с объектом,
        // иначе потеряем голос/видео/файл, который пользователь на самом деле видит.
        // «пустые» заглушки — локально сгоревшее одноразовое медиа, файла нет
        final boolean hasDocument = media != null && media.document != null
                && !(media.document instanceof TLRPC.TL_documentEmpty);
        final boolean hasPhoto = media != null && media.photo != null
                && !(media.photo instanceof TLRPC.TL_photoEmpty);

        if (!hasDocument && !hasPhoto) {
            done.run(sendWithoutFile(account, message, dialogId));
            return;
        }

        final String ready = localPath(account, message);
        if (ready != null) {
            done.run(sendFile(account, message, dialogId, ready));
            return;
        }

        setBusy(dialogId, busyTotal, busyDone, true);
        download(account, message, path -> {
            setBusy(dialogId, busyTotal, busyDone, false);
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
        // TTL и прочие атрибуты «одноразовости» сознательно не переносятся:
        // копия должна остаться постоянной, в этом и смысл «переслать удалёнку».
        final ArrayList<TLRPC.MessageEntity> entities = copyEntities(message.messageOwner != null
                ? message.messageOwner.entities : null);
        final String text = withAuthor(message,
                message.messageOwner != null ? message.messageOwner.message : null, entities);
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
        final ArrayList<TLRPC.MessageEntity> entities = copyEntities(message.messageOwner.entities);
        final String caption = withAuthor(message, message.messageOwner.message, entities);
        final boolean spoiler = media != null && media.spoiler;

        // работаем с копией: заливка не должна трогать кэш исходного чата
        final String path = tempCopy(sourcePath, message);
        if (path == null) {
            return sendWithoutFile(account, message, dialogId);
        }

        // Таймером файл не трогаем: отправка может ещё читать его после возврата
        // из sendMessage (особенно для больших видео). Лишняя копия лежит в
        // app-cache до тех пор, пока система/настройки хранилища не найдут её
        // готовой к удалению.
        try {
            if (media != null && media.document != null) {
                final TLRPC.TL_document document = buildDocument(media.document, path);
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
            // своя сим-папка внутри app-cache: чиститься штатной чисткой телеграма
            // не будет, но можно без страха удалять из телеграма → хранилища
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "pengram_copy");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            final File target = new File(dir, message.getId() + "_" + System.currentTimeMillis()
                    + "_" + ((int) (Math.random() * 1000)) + extension);
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
        return localPathEncrypted(account, message);
    }

    /**
     * Одноразовые медиа Telegram скачивает зашифрованными (cacheType 2):
     * рядом с обычным именем лежит файл .enc, а его ключ — во внутреннем кэше.
     * Отправка сырых байт такого файла даст собеседнику битую картинку.
     * Поэтому снимаем шифрование во временный файл и работаем с ним.
     */
    private static String localPathEncrypted(int account, MessageObject message) {
        try {
            final TLRPC.MessageMedia media = message.messageOwner != null ? message.messageOwner.media : null;
            if (media == null) {
                return null;
            }
            final boolean ttl = media.ttl_seconds != 0;
            final boolean secret = message.messageOwner instanceof TLRPC.TL_message_secret
                    || DialogObject.isEncryptedDialog(message.getDialogId());
            if (!ttl && !secret) {
                return null;
            }
            final ArrayList<String> candidates = new ArrayList<>();
            final String attach = message.messageOwner.attachPath;
            if (!TextUtils.isEmpty(attach)) {
                candidates.add(attach);
                candidates.add(attach + ".enc");
            }
            final FileLoader loader = FileLoader.getInstance(account);
            // getPathToMessage для ttl медиа отдаёт путь в общем кэше, но
            // шифрованные файлы локальная загрузка кладёт в «типовые» папки
            // (фото → image, видео → video, документы → document) — проверяем оба
            try {
                final File plain = loader.getPathToMessage(message.messageOwner);
                if (plain != null) {
                    candidates.add(plain.getAbsolutePath() + ".enc");
                }
            } catch (Throwable ignore) {
            }
            try {
                if (media.photo != null) {
                    final TLRPC.PhotoSize sizeFull = FileLoader.getClosestPhotoSizeWithSize(
                            media.photo.sizes, AndroidUtilities.getPhotoSize());
                    if (sizeFull != null) {
                        final File imageDirFile = loader.getPathToAttach(sizeFull);
                        if (imageDirFile != null) {
                            candidates.add(imageDirFile.getAbsolutePath() + ".enc");
                        }
                    }
                }
                if (media.document != null) {
                    final File docFile = loader.getPathToAttach(media.document);
                    if (docFile != null) {
                        candidates.add(docFile.getAbsolutePath() + ".enc");
                    }
                    final File docCacheFile = loader.getPathToAttach(media.document, true);
                    if (docCacheFile != null) {
                        candidates.add(docCacheFile.getAbsolutePath() + ".enc");
                    }
                }
            } catch (Throwable ignore) {
            }
            for (String candidate : candidates) {
                final File file = candidate == null ? null : new File(candidate);
                if (file == null || !file.exists() || file.length() == 0) {
                    continue;
                }
                final String out = decryptToCache(file);
                if (out != null) {
                    return out;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /** расшифровать .enc во временный файл в нашем кэше; null, если ключа нет */
    private static String decryptToCache(File file) {
        try {
            final File keyDir = FileLoader.getInternalCacheDir();
            // два имени встречаются в коде: FileLoadOperation пишет <имя>.key,
            // ImageLoader — <имя>.enc.key; проверяем оба
            final File[] keys = new File[]{
                    new File(keyDir, file.getName() + ".key"),
                    new File(keyDir, file.getName() + ".enc.key"),
                    file.getName().endsWith(".enc")
                            ? new File(keyDir, file.getName() + ".key")
                            : null,
            };
            for (File key : keys) {
                if (key == null || !key.exists() || key.length() < 48) {
                    continue;
                }
                File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "pengram_copy");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                final File out = new File(dir, "dec_" + file.getName().replace(".enc", "")
                        + "_" + System.currentTimeMillis());
                try (InputStream in = new EncryptedFileInputStream(file, key);
                     FileOutputStream o = new FileOutputStream(out)) {
                    final byte[] buffer = new byte[64 * 1024];
                    int read;
                    int total = 0;
                    while ((read = in.read(buffer)) > 0) {
                        o.write(buffer, 0, read);
                        total += read;
                        if (total > 212 * 1024 * 1024) {
                            break;   // потолок заливки, дальше здоровее не будет
                        }
                    }
                    o.flush();
                }
                if (out.length() > 0 && looksLikeMedia(out)) {
                    return out.getAbsolutePath();
                }
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /** санитарная проверка: похоже ли на медиа после снятия шифрования */
    private static boolean looksLikeMedia(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            final byte[] head = new byte[12];
            final int n = in.read(head);
            if (n < 4) {
                return false;
            }
            if (head[0] == (byte) 0xFF && head[1] == (byte) 0xD8) return true;              // JPEG
            if (head[0] == (byte) 0x89 && head[1] == 0x50) return true;                    // PNG
            if (head[0] == 0x47 && head[1] == 0x49 && head[2] == 0x46) return true;        // GIF
            if (head[0] == 0x1A && head[1] == 0x45 && head[2] == (byte) 0xDF) return true; // WebM/MKV
            if (n >= 8 && head[4] == 0x66 && head[5] == 0x74 && head[6] == 0x79 && head[7] == 0x70) return true; // MP4 "ftyp"
            if (head[0] == 0x4F && head[1] == 0x67 && head[2] == 0x67 && head[3] == 0x53) return true; // Ogg
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Скачать вложение и позвать обратно, когда файл окажется на диске.
     *
     * Важно: у FileLoader НЕТ события «файл готов» — fileLoaded/fileLoadFailed
     * постит только ImageLoader, и они не про наши прямые загрузки. Раньше
     * слушатели висели на событиях, которые не происходят, и каждая копия
     * ждала полный таймаут. Поэтому здесь маленький опрашиватель: имя файла
     * на диске появляется тогда и только тогда, когда загрузка завершена
     * (промежуточные байты идут в .temp-файл).
     */
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
        if (document == null && photoSize == null) {
            callback.run(null);
            return;
        }

        final boolean[] finished = new boolean[1];
        final Runnable finish = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            callback.run(localPath(account, message));
        };

        try {
            if (document != null) {
                FileLoader.getInstance(account).loadFile(document, message, FileLoader.PRIORITY_HIGH, 0);
            } else {
                FileLoader.getInstance(account).loadFile(ImageLocation.getForObject(photoSize, media.photo),
                        message, null, FileLoader.PRIORITY_HIGH, 0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            finish.run();
            return;
        }

        final long deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT;
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                if (finished[0]) {
                    return;
                }
                try {
                    if (localPath(account, message) != null) {
                        finish.run();
                        return;
                    }
                } catch (Throwable ignore) {
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    finish.run();
                    return;
                }
                Utilities.globalQueue.postRunnable(this, 250);
            }
        }, 250);
    }

    // ------------------------------------------------------------ мелочи

    /**
     * Документ для копии — из чего угодно.
     *
     * Обычный TL_document просто клонируем. Из секретного чата приходит
     * TL_documentEncrypted: клонировать его нельзя, но все признаки (голосовое,
     * кружок, видео, стикер, имя файла) лежат в attributes, и новый документ
     * собирается из них вместе с файлом на диске.
     */
    private static TLRPC.TL_document buildDocument(TLRPC.Document source, String path) {
        if (source instanceof TLRPC.TL_document) {
            return cloneAsNew((TLRPC.TL_document) source, path);
        }
        try {
            final TLRPC.TL_document document = new TLRPC.TL_document();
            document.id = 0;
            document.access_hash = 0;
            document.file_reference = new byte[0];
            document.dc_id = 0;
            document.date = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
            document.mime_type = TextUtils.isEmpty(source.mime_type) ? "application/octet-stream" : source.mime_type;
            if (source.attributes != null) {
                document.attributes = new ArrayList<>(source.attributes);
            }
            final File file = new File(path);
            document.size = file.exists() ? file.length() : source.size;
            if (document.size <= 0) {
                return null;
            }
            return document;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

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

    /**
     * Подпись автора над копией.
     *
     * Копия уходит от вашего лица, и из неё невозможно понять, чьё это было
     * сообщение. Для пересылок из секретных и защищённых чатов это иногда
     * важно — но включать такое по умолчанию нельзя: подпись раскрывает
     * собеседника третьему человеку. Поэтому отдельная галочка, выключенная.
     */
    private static String authorName(MessageObject message) {
        if (message == null || !PengramConfig.isCopySignAuthor()) {
            return null;
        }
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

    /**
     * Приклеить подпись к тексту или подписи медиа.
     *
     * Метки форматирования исходного текста считаются от его начала, поэтому
     * при добавлении строки сверху их все нужно сдвинуть — иначе жирный и
     * ссылки расползутся по сообщению.
     */
    private static String withAuthor(MessageObject message, String text, ArrayList<TLRPC.MessageEntity> entities) {
        final String name = authorName(message);
        if (TextUtils.isEmpty(name)) {
            return text;
        }
        // тот же вид, что у ответа на удалённое: жирное имя и текст в цитате
        return PengramDeletedReply.signedBlock(name, text, entities);
    }

    private static ArrayList<TLRPC.MessageEntity> copyEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            // при включённой подписи список нужен даже пустой: в него ляжет жирное имя
            return PengramConfig.isCopySignAuthor() ? new ArrayList<>() : null;
        }
        return new ArrayList<>(entities);
    }
}
