package org.telegram.messenger;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pengram: чтение файлов сессий, которые создают другие клиенты.
 *
 * <p>Поддерживаются четыре распространённых формата:
 * <ul>
 *   <li><b>Telethon</b> — SQLite-файл {@code *.session};</li>
 *   <li><b>Pyrogram</b> — тоже SQLite, но с другим набором колонок;</li>
 *   <li><b>Telegram Desktop</b> — папка {@code tdata}, упакованная в zip:
 *       {@code TDF$}-записи с контрольной суммой, ключ, зашифрованный
 *       PBKDF2+SHA-512, и AES-IGE поверх; </li>
 *   <li><b>строковые сессии</b> — Telethon {@code StringSession} и
 *       Pyrogram-строка.</li>
 * </ul>
 *
 * <p>Всё читается только на чтение и только в памяти: файл никуда не
 * отправляется, база не изменяется, ничего не выполняется. Пароли tdata (v2)
 * не поддерживаются намеренно: обещать «введёте пароль — и всё будет» значило
 * бы тянуть сюда чужой криптокод, а без пароля такой файл честно отклоняется.
 */
public final class PengramSessionFormats {

    /** предел на входной файл: больше в память не тянем */
    public static final long MAX_INPUT = 512L * 1024 * 1024;
    /** предел на одну запись tdata */
    private static final int MAX_RECORD = 1024 * 1024;
    private static final int MAX_ENTRIES = 50000;

    public static final String KIND_TELETHON = "Telethon";
    public static final String KIND_PYROGRAM = "Pyrogram";
    public static final String KIND_TDESKTOP = "Telegram Desktop";
    public static final String KIND_STRING = "String session";

    /** одна расшифрованная сессия */
    public static final class Account {
        public String kind;
        public int dcId;
        public byte[] authKey;
        public long userId;
        public int apiId;
        public boolean testMode;
        /** для строковых сессий — как их показать в списке */
        public String detail;

        public boolean isUsable() {
            return dcId >= 1 && dcId <= 5 && authKey != null && authKey.length == 256;
        }

        public String describe() {
            final StringBuilder builder = new StringBuilder(kind);
            builder.append(" · DC ").append(dcId);
            if (userId != 0) {
                builder.append(" · id ").append(userId);
            }
            return builder.toString();
        }

        public void clear() {
            if (authKey != null) {
                Arrays.fill(authKey, (byte) 0);
                authKey = null;
            }
        }
    }

    /** ошибка разбора: код, а не текст — тексты живут в строках интерфейса */
    public static final class Failure extends Exception {
        public final String code;

        public Failure(String code) {
            super(code);
            this.code = code;
        }
    }

    private PengramSessionFormats() {
    }

    // ------------------------------------------------------------------ вход

    /**
     * Разбирает файл и возвращает все найденные аккаунты. Для tdata это может
     * быть несколько профилей — пользователь выберет нужный.
     */
    public static ArrayList<Account> parse(File file) throws Failure {
        if (file == null || !file.exists() || file.length() < 16) {
            throw new Failure("file_size");
        }
        if (file.length() > MAX_INPUT) {
            throw new Failure("file_size");
        }
        // 16 байт: ровно столько занимает заголовок SQLite, по нему и различаем
        final byte[] head = readHead(file, 16);
        if (head.length >= 16 && matches(head, 0, "SQLite format 3\0".getBytes())) {
            return readSessionDatabase(file);
        }
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K') {
            return readTdata(file);
        }
        final String text = readText(file);
        if (text != null) {
            final Account account = readStringSession(text);
            if (account != null) {
                final ArrayList<Account> accounts = new ArrayList<>();
                accounts.add(account);
                return accounts;
            }
        }
        throw new Failure("unsupported_file");
    }

    // --------------------------------------------------------------- SQLite

    private static ArrayList<Account> readSessionDatabase(File file) throws Failure {
        SQLiteDatabase database = null;
        Cursor cursor = null;
        try {
            database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            final HashSet<String> columns = new HashSet<>();
            cursor = database.rawQuery("PRAGMA table_info(sessions)", null);
            while (cursor.moveToNext()) {
                final String name = cursor.getString(1);
                if (name != null) {
                    columns.add(name.toLowerCase(Locale.US));
                }
            }
            cursor.close();
            cursor = null;
            if (!columns.contains("dc_id") || !columns.contains("auth_key")) {
                throw new Failure("unsupported_session");
            }
            final boolean pyrogram = columns.contains("user_id") && columns.contains("test_mode");
            final String query = pyrogram
                    ? "SELECT dc_id, auth_key, test_mode, user_id, is_bot FROM sessions LIMIT 2"
                    : "SELECT dc_id, auth_key FROM sessions LIMIT 2";
            cursor = database.rawQuery(query, null);
            if (cursor.getCount() != 1) {
                // Несколько строк — это не «одна сессия», а неоднозначность.
                throw new Failure("ambiguous_session");
            }
            cursor.moveToFirst();
            final Account account = new Account();
            account.kind = pyrogram ? KIND_PYROGRAM : KIND_TELETHON;
            account.dcId = cursor.getInt(0);
            account.authKey = cursor.getBlob(1);
            if (pyrogram) {
                account.testMode = cursor.getInt(2) != 0;
                account.userId = cursor.getLong(3);
                final boolean bot = cursor.getInt(4) != 0;
                if (account.testMode) {
                    throw new Failure("test_session");
                }
                if (bot) {
                    throw new Failure("bot_session");
                }
            }
            if (!account.isUsable()) {
                throw new Failure("invalid_key");
            }
            final ArrayList<Account> accounts = new ArrayList<>();
            accounts.add(account);
            return accounts;
        } catch (Failure failure) {
            throw failure;
        } catch (Throwable error) {
            throw new Failure("invalid_file");
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignore) {
                }
            }
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // ----------------------------------------------------------------- tdata

    private static ArrayList<Account> readTdata(File file) throws Failure {
        ZipFile zip = null;
        final ArrayList<Account> accounts = new ArrayList<>();
        try {
            zip = new ZipFile(file);
            final HashMap<String, ZipEntry> entries = new HashMap<>();
            final Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                final ZipEntry entry = enumeration.nextElement();
                final String name = entry.getName();
                if (name == null || name.startsWith("/") || name.contains("\\")
                        || name.contains("..") || name.indexOf('\0') >= 0) {
                    throw new Failure("unsafe_archive");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (entries.put(name, entry) != null) {
                    throw new Failure("unsafe_archive");
                }
                if (entries.size() > MAX_ENTRIES) {
                    throw new Failure("archive_limits");
                }
            }

            // Корень tdata — папка, где лежит key_data*. Архив может быть как
            // самим tdata, так и «tdata.zip» с папкой tdata внутри, поэтому
            // корень вычисляем, а не угадываем. Из нескольких key_data (0/1 —
            // с паролем и без) берём самый свежий.
            String prefix = null;
            ZipEntry masterEntry = null;
            for (ZipEntry entry : entries.values()) {
                final String name = entry.getName();
                if (!isKeyDataStem(stemOf(name))) {
                    continue;
                }
                if (masterEntry == null || entry.getTime() > masterEntry.getTime()) {
                    masterEntry = entry;
                    prefix = parentOf(name);
                }
            }
            if (masterEntry == null || prefix == null) {
                throw new Failure("tdata_not_found");
            }

            final TdataReader reader = new TdataReader(zip, entries, prefix);
            final byte[][] unwrapped = unwrapKey(reader.readEntry(masterEntry));
            // masterKey расшифровывает и список аккаунтов, и сами файлы аккаунтов
            final byte[] masterKey = unwrapped[0];
            final byte[] infoPayload;
            try {
                infoPayload = decryptLocal(unwrapped[1], masterKey);
            } catch (Failure failure) {
                // ключ есть, но не подходит: файл закрыт паролем Desktop
                throw new Failure("password_tdata");
            }

            final Reader info = new Reader(infoPayload);
            final int count = info.i32();
            if (count < 0 || count > 6) {
                throw new Failure("invalid_file");
            }
            final int[] indices = new int[count];
            for (int a = 0; a < count; ++a) {
                indices[a] = info.i32();
                if (indices[a] < 0 || indices[a] > 5) {
                    throw new Failure("invalid_file");
                }
            }
            for (int index : indices) {
                accounts.add(readCopies(reader, index, masterKey));
            }
            Arrays.fill(masterKey, (byte) 0);
            if (accounts.isEmpty()) {
                throw new Failure("tdata_not_found");
            }
            return accounts;
        } catch (Failure failure) {
            for (Account account : accounts) {
                account.clear();
            }
            throw failure;
        } catch (Throwable error) {
            for (Account account : accounts) {
                account.clear();
            }
            throw new Failure("invalid_file");
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Авторизация аккаунта Desktop: блок dbiMtpAuthorization и вложенный
     * QByteArray с ключами.
     *
     * <p>Первые восемь байт — тег формата: либо ~0 (широкий userId), либо
     * старые 32-битные userId/dcId. Ключи идут после домашнего DC, и брать
     * нужно именно ключ домашнего DC: остальные — медиа-DC и служебные.
     */
    private static Account readAuthorization(byte[] payload, int index) throws Failure {
        final Reader stream = new Reader(payload);
        // Файл — поток блоков настроек: quint32 id и QByteArray-значение.
        // Блок авторизации не обязан быть первым (после входа в другой версии
        // выше могли лежать другие настройки), поэтому идём по потоку, а не
        // требуем 0x4B по нулевому смещению.
        byte[] authPayload = null;
        while (stream.remaining() >= 4) {
            final long blockId = stream.u32();
            final byte[] value = stream.blob();
            if (blockId == 0x4B) {
                authPayload = value;
                break;
            }
        }
        if (authPayload == null) {
            throw new Failure("unsupported_tdata");
        }
        final Reader auth = new Reader(authPayload);
        final long userId;
        final int dcId;
        final byte[] head = auth.read(8);
        if (allFFBytes(head)) {
            userId = auth.u64();
            dcId = auth.i32();
        } else {
            userId = readIntBE(head, 0) & 0xFFFFFFFFL;
            dcId = readIntBE(head, 4);
        }
        final int count = auth.i32();
        if (count < 1 || count > 32) {
            throw new Failure("invalid_file");
        }
        final HashMap<Integer, byte[]> keys = new HashMap<>();
        for (int a = 0; a < count; ++a) {
            final int keyDc = auth.i32();
            final byte[] key = auth.read(256);
            if (keys.put(keyDc, key) != null) {
                throw new Failure("invalid_file");
            }
        }
        // Ключи к уничтожению читаем и выбрасываем: они непригодны.
        final int destroy = auth.i32();
        if (destroy < 0 || destroy > 32) {
            throw new Failure("invalid_file");
        }
        auth.read(destroy * 260);
        if (auth.remaining() != 0) {
            throw new Failure("invalid_file");
        }
        final byte[] authKey = keys.get(dcId);
        if (authKey == null || userId == 0 || dcId < 1 || dcId > 5) {
            throw new Failure("invalid_key");
        }
        final Account account = new Account();
        account.kind = KIND_TDESKTOP;
        account.dcId = dcId;
        account.authKey = authKey;
        account.userId = userId;
        account.detail = "tdata #" + (index + 1);
        return account;
    }

    /**
     * Файлы одного аккаунта. Desktop держит до трёх копий (`s` — актуальная,
     * `0`/`1` — прошлые), и какая из них читается, заранее не известно: после
     * смены пароля старые копии не расшифровываются. Поэтому пробуем все, а не
     * только первую по имени.
     */
    private static Account readCopies(TdataReader reader, int index, byte[] masterKey) throws Failure {
        Failure last = new Failure("incomplete_tdata");
        for (byte[] payload : reader.readAccountFiles(accountFilename(index))) {
            try {
                // Полезная нагрузка файла аккаунта — это QByteArray, а не сам
                // шифроблок: Desktop пишет его через stream << encrypted.
                final Reader outer = new Reader(payload);
                final byte[] encrypted = outer.blob();
                if (outer.remaining() != 0 || encrypted.length <= 16) {
                    throw new Failure("invalid_file");
                }
                return readAuthorization(decryptLocal(encrypted, masterKey), index);
            } catch (Failure failure) {
                last = failure;
            }
        }
        throw last;
    }

    /** master-key: salt, зашифрованный ключ, зашифрованный список аккаунтов */
    private static byte[][] unwrapKey(byte[] payload) throws Failure {
        final Reader stream = new Reader(payload);
        final byte[] salt = stream.blob();
        final byte[] encryptedKey = stream.blob();
        final byte[] encryptedInfo = stream.blob();
        boolean trailer = false;
        boolean liveWrap = false;
        if (stream.remaining() > 0) {
            trailer = true;
            final byte[] trailerPayload = stream.blob();
            if (stream.remaining() != 0 || !hasKeyDataMagic(trailerPayload)) {
                // непонятный хвост — не рискуем угадывать, что внутри
                throw new Failure("password_tdata");
            }
            liveWrap = hasLivePasscodeWrap(trailerPayload);
        }
        // Живая обёртка пароля означает, что открытый ключ уже не действует:
        // пароль снят только тогда, когда committed не совпадает ни с одной
        // обёрткой. Читать такой файл без пароля нельзя.
        if (liveWrap) {
            throw new Failure("password_tdata");
        }
        // Пустой openKeyEncrypted означает, что локальный ключ закрыт паролем:
        // без него master-key не достать. Пустой пароль здесь не поможет.
        if (encryptedKey.length == 0) {
            throw new Failure("password_tdata");
        }
        // Salt обязан быть непустым: иначе CreateLocalKey вырождается, и любой
        // архив «открывался» бы мусорным ключом.
        if (salt.length < 8 || encryptedKey.length <= 16) {
            throw new Failure("invalid_file");
        }
        final byte[] localKey = deriveLocalKey("", salt);
        final byte[] masterKey;
        try {
            masterKey = decryptLocal(encryptedKey, localKey);
        } catch (Failure failure) {
            Arrays.fill(localKey, (byte) 0);
            if (trailer) {
                // modern-файл без открытого ключа — это пароль Desktop
                throw new Failure("password_tdata");
            }
            throw failure;
        } finally {
            Arrays.fill(localKey, (byte) 0);
        }
        if (masterKey.length != 256) {
            throw new Failure("invalid_key");
        }
        return new byte[][]{masterKey, encryptedInfo};
    }

    /**
     * Есть ли в трейлере обёртка, которая совпадает с committed. Только номер
     * поколения: сами параметры KDF (memory/time/parallel) читаем, но никогда
     * не применяем — их задаёт автор файла, а не мы.
     */
    private static boolean hasLivePasscodeWrap(byte[] trailer) {
        try {
            final Reader fields = new Reader(trailer);
            if (fields.u32() != 0x4B443200 || fields.u32() != 2) {
                return false;
            }
            final int committed = fields.u32();
            final int count = fields.u32();
            if (count < 0 || count > 2) {
                return false;
            }
            for (int a = 0; a < count; ++a) {
                final int kind = fields.u32();
                final int generation = fields.u32();
                fields.u32();
                fields.u32();
                fields.u32();
                final byte[] salt = fields.blob();
                final byte[] key = fields.blob();
                if (kind < 0 || salt == null || key == null) {
                    return false;
                }
                if (generation == committed) {
                    return true;
                }
            }
            return false;
        } catch (Throwable error) {
            return false;
        }
    }

    /** magic трейлера key_data: 0x4B443200 («KD2\0») */
    private static boolean hasKeyDataMagic(byte[] trailer) {
        return trailer != null && trailer.length >= 8
                && (trailer[0] & 0xFF) == 0x4B && (trailer[1] & 0xFF) == 0x44
                && (trailer[2] & 0xFF) == 0x32 && (trailer[3] & 0xFF) == 0x00;
    }

    private static boolean isKeyDataStem(String stem) {
        if (stem == null) {
            return false;
        }
        if ("key_data".equals(stem) || "key_datas".equals(stem)) {
            return true;
        }
        return stem.length() == 9 && stem.startsWith("key_data")
                && Character.isDigit(stem.charAt(8));
    }

    private static String stemOf(String name) {
        final int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static String parentOf(String name) {
        final int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash + 1);
    }

    private static final class TdataReader {
        private final ZipFile zip;
        private final HashMap<String, ZipEntry> entries;
        private final String prefix;

        TdataReader(ZipFile zip, HashMap<String, ZipEntry> entries, String prefix) {
            this.zip = zip;
            this.entries = entries;
            this.prefix = prefix;
        }

        byte[] readEntry(ZipEntry entry) throws Failure {
            if (entry.getSize() > MAX_RECORD) {
                throw new Failure("archive_limits");
            }
            try {
                return tdfPayload(readStream(zip.getInputStream(entry), MAX_RECORD));
            } catch (Failure failure) {
                throw failure;
            } catch (Throwable error) {
                throw new Failure("invalid_file");
            }
        }

        /** ключ Desktop: key_data, key_datas или key_dataN — что найдётся */
        byte[] read(String stem) throws Failure {
            final ArrayList<ZipEntry> candidates = new ArrayList<>();
            final ZipEntry direct = entries.get(prefix + stem);
            if (direct != null) {
                candidates.add(direct);
            }
            for (String suffix : new String[]{"s", "0", "1"}) {
                final ZipEntry entry = entries.get(prefix + stem + suffix);
                if (entry != null) {
                    candidates.add(entry);
                }
            }
            // у аккаунта может не быть суффикса — ищем «начинается с нашего хеша»
            for (ZipEntry entry : entries.values()) {
                final String name = entry.getName();
                if (!name.startsWith(prefix) || name.indexOf('/', prefix.length()) >= 0) {
                    continue;
                }
                final String file = name.substring(prefix.length());
                if (file.startsWith(stem) && !candidates.contains(entry)) {
                    candidates.add(entry);
                }
            }
            if (candidates.isEmpty()) {
                throw new Failure("incomplete_tdata");
            }
            Failure last = null;
            for (ZipEntry entry : candidates) {
                try {
                    return readEntry(entry);
                } catch (Failure failure) {
                    last = failure;
                }
            }
            throw last != null ? last : new Failure("incomplete_tdata");
        }

        /**
         * Все читаемые копии файлов аккаунта: имя, затем любые записи с тем же
         * хешем где угодно в архиве (архив мог быть собран из другой папки).
         */
        ArrayList<byte[]> readAccountFiles(String stem) throws Failure {
            final ArrayList<ZipEntry> candidates = new ArrayList<>();
            for (String suffix : new String[]{"s", "0", "1", ""}) {
                final ZipEntry entry = entries.get(prefix + stem + suffix);
                if (entry != null && !candidates.contains(entry)) {
                    candidates.add(entry);
                }
            }
            for (ZipEntry entry : entries.values()) {
                final String name = entry.getName();
                if (name.endsWith(stem + "s") || name.endsWith(stem + "0")
                        || name.endsWith(stem + "1") || name.endsWith(stem)) {
                    if (!candidates.contains(entry)) {
                        candidates.add(entry);
                    }
                }
            }
            final ArrayList<byte[]> result = new ArrayList<>();
            for (ZipEntry entry : candidates) {
                try {
                    if (entry.getSize() > MAX_RECORD) {
                        continue;
                    }
                    result.add(tdfPayload(readStream(zip.getInputStream(entry), MAX_RECORD)));
                } catch (Throwable ignore) {
                }
            }
            if (result.isEmpty()) {
                throw new Failure("incomplete_tdata");
            }
            return result;
        }
    }

    /** TDF$: версия, полезная нагрузка и MD5 в хвосте */
    static byte[] tdfPayload(byte[] data) throws Failure {
        if (data.length < 24 || data[0] != 'T' || data[1] != 'D' || data[2] != 'F' || data[3] != '$') {
            throw new Failure("invalid_file");
        }
        final int payloadLength = data.length - 8 - 16;
        final byte[] payload = Arrays.copyOfRange(data, 8, 8 + payloadLength);
        final MessageDigest digest = md5Digest();
        digest.update(payload);
        digest.update(new byte[]{
                (byte) payloadLength, (byte) (payloadLength >>> 8),
                (byte) (payloadLength >>> 16), (byte) (payloadLength >>> 24)});
        digest.update(data, 4, 4);
        digest.update(new byte[]{'T', 'D', 'F', '$'});
        final byte[] expected = digest.digest();
        for (int a = 0; a < 16; ++a) {
            if (expected[a] != data[8 + payloadLength + a]) {
                throw new Failure("checksum_failed");
            }
        }
        return payload;
    }

    // ------------------------------------------------------------- ключи

    /** локальный ключ Desktop: sha512(salt+pwd+salt) → PBKDF2-SHA512 */
    static byte[] deriveLocalKey(String passcode, byte[] salt) throws Failure {
        if (salt.length != 32) {
            throw new Failure("invalid_file");
        }
        try {
            final byte[] password = passcode == null ? new byte[0] : passcode.getBytes("UTF-8");
            final MessageDigest sha = MessageDigest.getInstance("SHA-512");
            sha.update(salt);
            sha.update(password);
            sha.update(salt);
            final byte[] digest = sha.digest();
            // Без пароля Desktop делает ровно одну итерацию — и мы тоже:
            // 100 000 итераций нужны только для файлов, закрытых паролем.
            return pbkdf2Sha512(digest, salt, password.length == 0 ? 1 : 100000, 256);
        } catch (Failure failure) {
            throw failure;
        } catch (Throwable error) {
            throw new Failure("invalid_file");
        }
    }

    private static byte[] pbkdf2Sha512(byte[] password, byte[] salt, int iterations, int length)
            throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(password, "HmacSHA512"));
        final int hashLength = 64;
        final int blocks = (length + hashLength - 1) / hashLength;
        final byte[] output = new byte[blocks * hashLength];
        for (int block = 1; block <= blocks; ++block) {
            mac.update(salt);
            mac.update(new byte[]{
                    (byte) (block >>> 24), (byte) (block >>> 16),
                    (byte) (block >>> 8), (byte) block});
            byte[] u = mac.doFinal();
            final byte[] t = u.clone();
            for (int iteration = 1; iteration < iterations; ++iteration) {
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; ++k) {
                    t[k] ^= u[k];
                }
            }
            System.arraycopy(t, 0, output, (block - 1) * hashLength, hashLength);
        }
        return Arrays.copyOf(output, length);
    }

    /** старое MTProto-расписание AES для локальных файлов Desktop */
    static byte[][] oldMtpAes(byte[] authKey, byte[] messageKey) throws Failure {
        try {
            final byte[] a = sha1(concat(messageKey, slice(authKey, 8, 40)));
            final byte[] b = sha1(concat(slice(authKey, 40, 56), messageKey, slice(authKey, 56, 72)));
            final byte[] c = sha1(concat(slice(authKey, 72, 104), messageKey));
            final byte[] d = sha1(concat(messageKey, slice(authKey, 104, 136)));
            final byte[] key = new byte[32];
            final byte[] iv = new byte[32];
            System.arraycopy(a, 0, key, 0, 8);
            System.arraycopy(b, 8, key, 8, 12);
            System.arraycopy(c, 4, key, 20, 12);
            System.arraycopy(a, 8, iv, 0, 12);
            System.arraycopy(b, 0, iv, 12, 8);
            System.arraycopy(c, 16, iv, 20, 4);
            System.arraycopy(d, 0, iv, 24, 8);
            return new byte[][]{key, iv};
        } catch (Throwable error) {
            throw new Failure("invalid_file");
        }
    }

    /** AES-IGE: P[i] = D(C[i] ^ P[i-1]) ^ C[i-1], C[-1]=iv[0..16), P[-1]=iv[16..32) */
    static byte[] aesIgeDecrypt(byte[] data, byte[] key, byte[] iv) throws Failure {
        if (data.length == 0 || data.length % 16 != 0) {
            throw new Failure("invalid_file");
        }
        try {
            final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            final byte[] output = new byte[data.length];
            byte[] previousCipher = Arrays.copyOfRange(iv, 0, 16);
            byte[] previousPlain = Arrays.copyOfRange(iv, 16, 32);
            final byte[] block = new byte[16];
            for (int offset = 0; offset < data.length; offset += 16) {
                for (int a = 0; a < 16; ++a) {
                    block[a] = (byte) (data[offset + a] ^ previousPlain[a]);
                }
                final byte[] decrypted = cipher.doFinal(block);
                final byte[] plain = new byte[16];
                for (int a = 0; a < 16; ++a) {
                    plain[a] = (byte) (decrypted[a] ^ previousCipher[a]);
                }
                System.arraycopy(plain, 0, output, offset, 16);
                System.arraycopy(data, offset, previousCipher, 0, 16);
                previousPlain = plain;
            }
            return output;
        } catch (Throwable error) {
            throw new Failure("invalid_file");
        }
    }

    /** одна запись Desktop: msg_key + AES-IGE + проверка SHA-1 и длины */
    static byte[] decryptLocal(byte[] encrypted, byte[] authKey) throws Failure {
        if (authKey.length != 256 || encrypted.length <= 16 || encrypted.length % 16 != 0) {
            throw new Failure("invalid_file");
        }
        final byte[] messageKey = Arrays.copyOfRange(encrypted, 0, 16);
        final byte[][] schedule = oldMtpAes(authKey, messageKey);
        final byte[] plain = aesIgeDecrypt(
                Arrays.copyOfRange(encrypted, 16, encrypted.length), schedule[0], schedule[1]);
        final byte[] check;
        try {
            check = sha1(plain);
        } catch (Exception error) {
            throw new Failure("invalid_file");
        }
        for (int a = 0; a < 16; ++a) {
            if (check[a] != messageKey[a]) {
                throw new Failure("decrypt_failed");
            }
        }
        if (plain.length < 4) {
            throw new Failure("invalid_file");
        }
        final int size = (plain[0] & 0xFF) | ((plain[1] & 0xFF) << 8)
                | ((plain[2] & 0xFF) << 16) | ((plain[3] & 0xFF) << 24);
        if (size < 4 || size > plain.length || size <= plain.length - 16) {
            throw new Failure("invalid_file");
        }
        return Arrays.copyOfRange(plain, 4, size);
    }

    // -------------------------------------------------------- строковые сессии

    private static Account readStringSession(String raw) {
        final String text = raw.replaceAll("\\s+", "");
        if (text.length() < 64 || text.length() > 2048) {
            return null;
        }
        // Telethon StringSession: первый символ — версия формата ('1'),
        // дальше base64 от [dc 1][ip 4|16][port 2 BE][key 256].
        if (text.charAt(0) == '1' || text.charAt(0) == '2') {
            final byte[] data = decodeBase64(text.substring(1));
            if (data != null && (data.length == 263 || data.length == 275)) {
                final int ipLength = data.length == 263 ? 4 : 16;
                final Account account = new Account();
                account.kind = KIND_STRING;
                account.dcId = data[0] & 0xFF;
                account.authKey = Arrays.copyOfRange(data, 1 + ipLength + 2, 1 + ipLength + 2 + 256);
                account.detail = "Telethon";
                return account.isUsable() ? account : null;
            }
        }
        // Pyrogram: >BI?256sQ? — dc, api_id, test, ключ, user_id, is_bot
        final byte[] data = decodeBase64(text);
        if (data == null) {
            return null;
        }
        final Account account = new Account();
        account.kind = KIND_STRING;
        account.detail = "Pyrogram";
        if (data.length == 271) {
            account.dcId = data[0] & 0xFF;
            account.apiId = readIntBE(data, 1);
            account.testMode = data[5] != 0;
            account.authKey = Arrays.copyOfRange(data, 6, 6 + 256);
            account.userId = readLongBE(data, 262);
        } else if (data.length == 267) {
            // старый формат без api_id
            account.dcId = data[0] & 0xFF;
            account.testMode = data[1] != 0;
            account.authKey = Arrays.copyOfRange(data, 2, 2 + 256);
            account.userId = readLongBE(data, 258);
        } else if (data.length == 263) {
            account.dcId = data[0] & 0xFF;
            account.testMode = data[1] != 0;
            account.authKey = Arrays.copyOfRange(data, 2, 2 + 256);
            account.userId = readIntBE(data, 258) & 0xFFFFFFFFL;
        } else {
            return null;
        }
        if (account.testMode) {
            return null;
        }
        return account.isUsable() ? account : null;
    }

    /** base64 от пользователя приходит и с padding, и без, и url-safe */
    private static byte[] decodeBase64(String text) {
        final StringBuilder padded = new StringBuilder(text);
        while (padded.length() % 4 != 0) {
            padded.append('=');
        }
        for (int flags : new int[]{Base64.URL_SAFE | Base64.NO_WRAP,
                Base64.DEFAULT | Base64.NO_WRAP}) {
            try {
                final byte[] data = Base64.decode(padded.toString(), flags);
                if (data != null && data.length > 0) {
                    return data;
                }
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- экспорт

    /**
     * Имя файла аккаунта в tdata: MD5 имени данных (data, data#2, ...), первые
     * восемь байт, по полубайту снизу вверх — ровно как ToFilePart у Desktop.
     */
    static String accountFilename(int index) {
        final String dataName = index <= 0 ? "data" : "data#" + (index + 1);
        final byte[] digest = md5Digest().digest(dataName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final StringBuilder name = new StringBuilder(16);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (int a = 0; a < 8; ++a) {
            name.append(hex[digest[a] & 0x0F]);
            name.append(hex[(digest[a] >> 4) & 0x0F]);
        }
        return name.toString();
    }

    /** стандартные адреса DC: их ждут Telethon/Pyrogram в файле сессии */
    private static final String[] DC_ADDRESS = {
            "", "149.154.175.53", "149.154.167.51", "149.154.175.100",
            "149.154.167.91", "91.108.56.130"
    };
    /** версия формата TDF, которую понимает любой Desktop */
    private static final int TDF_VERSION = 3000000;

    /**
     * tdata.zip с текущим аккаунтом: ровно та структура, которую пишет
     * Telegram Desktop (key_datas + файл аккаунта, TDF-обёртки, QDataStream).
     *
     * <p>Ключ, попавший в архив, — это полный доступ к аккаунту, поэтому файл
     * создаётся только по явному подтверждению и пишется во временный кэш.
     */
    public static byte[] exportTdata(byte[] authKey, int dcId, long userId) throws Failure {
        if (authKey == null || authKey.length != 256 || dcId < 1 || dcId > 5 || userId == 0) {
            throw new Failure("invalid_key");
        }
        final byte[] salt = new byte[32];
        new java.security.SecureRandom().nextBytes(salt);
        final byte[] masterKey = new byte[256];
        new java.security.SecureRandom().nextBytes(masterKey);

        final byte[] localKey = deriveLocalKey("", salt);
        final byte[] openKeyEncrypted = prepareEncrypted(masterKey, localKey);
        Arrays.fill(localKey, (byte) 0);

        final byte[] info = concat(be32(1), be32(0), be32(0));
        final byte[] infoEncrypted = prepareEncrypted(info, masterKey);

        final byte[] authSerialized = concat(
                be64(-1L), be64(userId), be32(dcId),      // wide tag, userId, main dc
                be32(1), be32(dcId), authKey,             // ключи: один, наш
                be32(0));                                 // ключи на уничтожение
        final byte[] accountPayload = prepareEncrypted(
                concat(be32(0x4B), beBlob(authSerialized)), masterKey);

        final byte[] keyFile = tdfEncode(concat(beBlob(salt), beBlob(openKeyEncrypted), beBlob(infoEncrypted)));
        // файл аккаунта Desktop пишет как QByteArray с шифроблоком внутри
        final byte[] accountFile = tdfEncode(beBlob(accountPayload));
        Arrays.fill(masterKey, (byte) 0);

        try {
            final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            final java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes);
            // Внутри архива — готовая папка tdata: её достаточно распаковать
            // рядом с Telegram Desktop.
            putZipEntry(zip, "tdata/key_datas", keyFile);
            final String account = "tdata/" + accountFilename(0);
            putZipEntry(zip, account, accountFile);
            // прошлые версии Desktop читали только файл с суффиксом s
            putZipEntry(zip, account + "s", accountFile);
            zip.finish();
            zip.close();
            return bytes.toByteArray();
        } catch (Throwable error) {
            throw new Failure("export_failed");
        }
    }

    /**
     * Файл .session для Telethon: та же схема, что создаёт сам Telethon
     * (версия 7, таблицы version/sessions/entities/sent_files/update_state).
     */
    public static void exportTelethonSession(File target, byte[] authKey, int dcId) throws Failure {
        checkExportTarget(target, authKey, dcId);
        SQLiteDatabase database = null;
        try {
            deleteDatabase(target);
            database = SQLiteDatabase.openOrCreateDatabase(target, null);
            database.execSQL("CREATE TABLE version (version integer primary key)");
            database.execSQL("CREATE TABLE sessions (dc_id integer primary key, server_address text, port integer, auth_key blob, takeout_id integer)");
            database.execSQL("CREATE TABLE entities (id integer primary key, hash integer not null, username text, phone integer, name text, date integer)");
            database.execSQL("CREATE TABLE sent_files (md5_digest blob, file_size integer, type integer, id integer, hash integer, primary key(md5_digest, file_size, type))");
            database.execSQL("CREATE TABLE update_state (id integer primary key, pts integer, qts integer, date integer, seq integer)");
            database.execSQL("insert into version values (7)");
            database.execSQL("insert into sessions values (?,?,?,?,?)",
                    new Object[]{dcId, DC_ADDRESS[dcId], 443, authKey, null});
        } catch (Throwable error) {
            throw new Failure("export_failed");
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** Файл .session для Pyrogram (версия 3 и полная схема его storage) */
    public static void exportPyrogramSession(File target, byte[] authKey, int dcId,
                                             int apiId, long userId, boolean bot) throws Failure {
        checkExportTarget(target, authKey, dcId);
        SQLiteDatabase database = null;
        try {
            deleteDatabase(target);
            database = SQLiteDatabase.openOrCreateDatabase(target, null);
            database.execSQL("CREATE TABLE sessions (dc_id INTEGER PRIMARY KEY, api_id INTEGER, test_mode INTEGER, auth_key BLOB, date INTEGER NOT NULL, user_id INTEGER, is_bot INTEGER)");
            database.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY, access_hash INTEGER, type INTEGER NOT NULL, username TEXT, phone_number TEXT, last_update_on INTEGER NOT NULL DEFAULT (CAST(STRFTIME('%s', 'now') AS INTEGER)))");
            database.execSQL("CREATE TABLE version (number INTEGER PRIMARY KEY)");
            database.execSQL("insert into version values (3)");
            database.execSQL("insert into sessions values (?,?,?,?,?,?,?)",
                    new Object[]{dcId, apiId > 0 ? apiId : null, 0, authKey,
                            System.currentTimeMillis() / 1000, userId, bot ? 1 : 0});
        } catch (Throwable error) {
            throw new Failure("export_failed");
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** строка Telethon: '1' + base64(dc, ip, port, key) */
    public static String telethonStringSession(byte[] authKey, int dcId) throws Failure {
        checkExportTarget(null, authKey, dcId);
        final byte[] ip = ipv4(DC_ADDRESS[dcId]);
        final byte[] packed = concat(new byte[]{(byte) dcId}, ip,
                new byte[]{(byte) (443 >> 8), (byte) (443 & 0xFF)}, authKey);
        return "1" + Base64.encodeToString(packed, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** строка Pyrogram: base64(dc, api_id, test, key, user_id, is_bot) */
    public static String pyrogramStringSession(byte[] authKey, int dcId, int apiId,
                                               long userId, boolean bot) throws Failure {
        checkExportTarget(null, authKey, dcId);
        final byte[] packed = concat(new byte[]{(byte) dcId}, be32(apiId > 0 ? apiId : 0),
                new byte[]{0}, authKey, be64(userId), new byte[]{(byte) (bot ? 1 : 0)});
        return Base64.encodeToString(packed, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static void checkExportTarget(File target, byte[] authKey, int dcId) throws Failure {
        if (target == null && authKey == null) {
            throw new Failure("export_failed");
        }
        if (authKey == null || authKey.length != 256 || dcId < 1 || dcId > 5) {
            throw new Failure("invalid_key");
        }
    }

    private static void deleteDatabase(File target) {
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            final File file = new File(target.getAbsolutePath() + suffix);
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static byte[] ipv4(String address) {
        final String[] parts = address.split("\\.");
        final byte[] result = new byte[4];
        for (int a = 0; a < 4 && a < parts.length; ++a) {
            result[a] = (byte) Integer.parseInt(parts[a]);
        }
        return result;
    }

    private static void putZipEntry(java.util.zip.ZipOutputStream zip, String name, byte[] data)
            throws java.io.IOException {
        final java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
        entry.setTime(System.currentTimeMillis());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    /** TDF$ + версия + данные + md5(данные, длина, версия, магия) */
    private static byte[] tdfEncode(byte[] payload) {
        final byte[] result = new byte[8 + payload.length + 16];
        result[0] = 'T';
        result[1] = 'D';
        result[2] = 'F';
        result[3] = '$';
        System.arraycopy(le32(payload.length), 0, result, 4, 4);
        System.arraycopy(payload, 0, result, 8, payload.length);
        final MessageDigest digest = md5Digest();
        digest.update(payload);
        digest.update(le32(payload.length));
        digest.update(le32(TDF_VERSION));
        digest.update(new byte[]{'T', 'D', 'F', '$'});
        System.arraycopy(digest.digest(), 0, result, 8 + payload.length, 16);
        return result;
    }

    /**
     * Дескриптор Desktop: [u32 длина][данные][паддинг] и AES-IGE со старым
     * расписанием, а первые 16 байт — SHA-1 от открытого блока.
     */
    static byte[] prepareEncrypted(byte[] plain, byte[] key) throws Failure {
        // Поле длины считает и собственный заголовок: Desktop пишет размер
        // всего расшифрованного блока, а не только полезной нагрузки.
        final int sizeField = 4 + plain.length;
        int fullSize = sizeField;
        if (fullSize % 16 != 0) {
            fullSize += 16 - (fullSize % 16);
        }
        final byte[] block = new byte[fullSize];
        System.arraycopy(le32(sizeField), 0, block, 0, 4);
        System.arraycopy(plain, 0, block, 4, plain.length);
        if (fullSize > 4 + plain.length) {
            // паддинг пишем прямо в блок: копия ушла бы в мусор, а хвост
            // остался бы нулевым — это лишняя подсказка для анализа файла
            final byte[] padding = new byte[fullSize - 4 - plain.length];
            new java.security.SecureRandom().nextBytes(padding);
            System.arraycopy(padding, 0, block, 4 + plain.length, padding.length);
        }
        try {
            final byte[] messageKey = Arrays.copyOf(sha1(block), 16);
            final byte[][] schedule = oldMtpAes(key, messageKey);
            return concat(messageKey, aesIgeEncrypt(block, schedule[0], schedule[1]));
        } catch (Failure failure) {
            throw failure;
        } catch (Throwable error) {
            throw new Failure("export_failed");
        }
    }

    /** AES-IGE: C[i] = E(P[i] ^ C[i-1]) ^ P[i-1], C[-1]=iv[0..16), P[-1]=iv[16..32) */
    static byte[] aesIgeEncrypt(byte[] data, byte[] key, byte[] iv) throws Failure {
        if (data.length == 0 || data.length % 16 != 0) {
            throw new Failure("invalid_file");
        }
        try {
            final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            final byte[] output = new byte[data.length];
            byte[] previousCipher = Arrays.copyOfRange(iv, 0, 16);
            byte[] previousPlain = Arrays.copyOfRange(iv, 16, 32);
            final byte[] block = new byte[16];
            for (int offset = 0; offset < data.length; offset += 16) {
                for (int a = 0; a < 16; ++a) {
                    block[a] = (byte) (data[offset + a] ^ previousPlain[a]);
                }
                final byte[] encrypted = cipher.doFinal(block);
                final byte[] result = new byte[16];
                for (int a = 0; a < 16; ++a) {
                    result[a] = (byte) (encrypted[a] ^ previousCipher[a]);
                }
                System.arraycopy(result, 0, output, offset, 16);
                previousCipher = result;
                previousPlain = Arrays.copyOfRange(data, offset, offset + 16);
            }
            return output;
        } catch (Throwable error) {
            throw new Failure("invalid_file");
        }
    }

    private static byte[] be32(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    private static byte[] le32(int value) {
        return new byte[]{(byte) value, (byte) (value >>> 8),
                (byte) (value >>> 16), (byte) (value >>> 24)};
    }

    private static byte[] be64(long value) {
        return concat(be32((int) (value >>> 32)), be32((int) value));
    }

    private static byte[] beBlob(byte[] data) {
        return concat(be32(data.length), data);
    }

    private static long readLongBE(byte[] data, int offset) {
        return ((long) readIntBE(data, offset) << 32)
                | (readIntBE(data, offset + 4) & 0xFFFFFFFFL);
    }

    // ---------------------------------------------------------------- утилиты

    /**
     * Чтение структур tdata. Desktop пишет их через QDataStream, а он по
     * умолчанию big-endian: и длины блобов, и все числа идут старшим байтом
     * вперёд. Младший порядок здесь — самая дорогая ошибка: любой настоящий
     * key_datas тогда читается как «повреждённый файл».
     */
    static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - position;
        }

        byte[] read(int length) throws Failure {
            if (length < 0 || length > remaining()) {
                throw new Failure("invalid_file");
            }
            final byte[] value = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return value;
        }

        int u32() throws Failure {
            if (remaining() < 4) {
                throw new Failure("invalid_file");
            }
            final int value = ((data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            position += 4;
            return value;
        }

        int i32() throws Failure {
            return u32();
        }

        long u64() throws Failure {
            final long high = u32() & 0xFFFFFFFFL;
            final long low = u32() & 0xFFFFFFFFL;
            return (high << 32) | low;
        }

        /** QByteArray: длина u32, а 0xFFFFFFFF — «пустой» */
        byte[] blob() throws Failure {
            final int length = u32();
            if (length == 0xFFFFFFFF) {
                return new byte[0];
            }
            if (length < 0 || length > MAX_RECORD) {
                throw new Failure("invalid_file");
            }
            return read(length);
        }

        /** остаток потока — для необязательных хвостов (например, active-индекса) */
        byte[] rest() {
            final byte[] value = Arrays.copyOfRange(data, position, data.length);
            position = data.length;
            return value;
        }
    }

    private static boolean allFFBytes(byte[] value) {
        for (byte b : value) {
            if (b != (byte) 0xFF) {
                return false;
            }
        }
        return true;
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static boolean matches(byte[] data, int offset, byte[] pattern) {
        if (data.length - offset < pattern.length) {
            return false;
        }
        for (int a = 0; a < pattern.length; ++a) {
            if (data[offset + a] != pattern[a]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        final byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] slice(byte[] base, int from, int to) {
        return Arrays.copyOfRange(base, from, to);
    }

    private static byte[] sha1(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    private static byte[] md5(byte[] data) {
        try {
            return md5Digest().digest(data);
        } catch (Throwable error) {
            return new byte[8];
        }
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (Throwable error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] readHead(File file, int length) {
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(file);
            final byte[] buffer = new byte[length];
            int read = 0;
            // read() не обязан заполнить буфер целиком даже на обычном файле:
            // недобранные байты иначе превращают SQLite-сессию в «битый файл»
            while (read < length) {
                final int count = stream.read(buffer, read, length - read);
                if (count <= 0) {
                    break;
                }
                read += count;
            }
            return read == length ? buffer : Arrays.copyOf(buffer, Math.max(0, read));
        } catch (Throwable error) {
            return new byte[0];
        } finally {
            close(stream);
        }
    }

    private static String readText(File file) {
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(file);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                if (output.size() > 4096) {
                    return null;
                }
            }
            return output.toString("UTF-8");
        } catch (Throwable error) {
            return null;
        } finally {
            close(stream);
        }
    }

    private static byte[] readStream(InputStream stream, int limit) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            output.write(buffer, 0, read);
            if (output.size() > limit) {
                throw new Failure("archive_limits");
            }
        }
        return output.toByteArray();
    }

    private static void close(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
