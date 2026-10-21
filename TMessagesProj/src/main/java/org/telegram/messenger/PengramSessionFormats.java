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
                if (entries.put(name, entry) != null) {
                    throw new Failure("unsafe_archive");
                }
                if (entries.size() > MAX_ENTRIES) {
                    throw new Failure("archive_limits");
                }
            }
            // Корень tdata ищем по master-key файлу: у Desktop рядом с ним
            // лежат key_datas и папки аккаунтов.
            String prefix = null;
            for (String name : entries.keySet()) {
                final int slash = name.lastIndexOf('/');
                final String stem = slash < 0 ? name : name.substring(slash + 1);
                if ("key_datas".equals(stem) || "key_data0".equals(stem) || "key_data1".equals(stem)) {
                    final String root = slash < 0 ? "" : name.substring(0, slash + 1);
                    if (prefix != null && !prefix.equals(root)) {
                        throw new Failure("multiple_tdata");
                    }
                    prefix = root;
                }
            }
            if (prefix == null) {
                throw new Failure("tdata_not_found");
            }
            final TdataReader reader = new TdataReader(zip, entries, prefix);

            final byte[] masterPayload = reader.read("key_data");
            final byte[][] unwrapped = unwrapKey(masterPayload);
            // masterKey расшифровывает и список аккаунтов, и сами файлы аккаунтов
            final byte[] masterKey = unwrapped[0];
            final byte[] infoPayload = decryptLocal(unwrapped[1], masterKey);

            final Reader info = new Reader(infoPayload);
            final int count = info.i32();
            if (count < 1 || count > 6) {
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
                final Reader outer = new Reader(reader.read(accountFilename(index)));
                final byte[] encrypted = outer.blob();
                if (outer.remaining() != 0) {
                    throw new Failure("unsupported_tdata");
                }
                accounts.add(readAuthorization(decryptLocal(encrypted, masterKey), index));
            }
            Arrays.fill(masterKey, (byte) 0);
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

    /** master-key: salt, зашифрованный ключ, зашифрованный список аккаунтов */
    private static byte[][] unwrapKey(byte[] payload) throws Failure {
        final Reader stream = new Reader(payload);
        final byte[] salt = stream.blob();
        final byte[] encryptedKey = stream.blob();
        final byte[] encryptedInfo = stream.blob();
        if (stream.remaining() != 0) {
            // v2-контейнер с memory-hard KDF: без пароля такой файл не открыть,
            // а притворяться, что открыли, нельзя.
            throw new Failure("password_tdata");
        }
        if (salt.length != 32 || encryptedKey.length <= 16) {
            throw new Failure("invalid_file");
        }
        final byte[] localKey = deriveLocalKey("", salt);
        final byte[] masterKey = decryptLocal(encryptedKey, localKey);
        Arrays.fill(localKey, (byte) 0);
        if (masterKey.length != 256) {
            throw new Failure("invalid_key");
        }
        return new byte[][]{masterKey, encryptedInfo};
    }

    private static Account readAuthorization(byte[] payload, int index) throws Failure {
        final Reader stream = new Reader(payload);
        if (stream.u32() != 0x4B) {
            throw new Failure("unsupported_tdata");
        }
        final Reader auth = new Reader(stream.blob());
        if (stream.remaining() != 0) {
            throw new Failure("unsupported_tdata");
        }
        final byte[] first = auth.read(8);
        final long userId;
        final int dcId;
        if (allFFBytes(first)) {
            userId = auth.u64();
            dcId = auth.i32();
        } else {
            userId = readIntBE(first, 0);
            dcId = readIntBE(first, 4);
        }
        final int keyCount = auth.i32();
        if (keyCount < 1 || keyCount > 32) {
            throw new Failure("invalid_file");
        }
        final HashMap<Integer, byte[]> keys = new HashMap<>();
        for (int a = 0; a < keyCount; ++a) {
            final int keyDc = auth.i32();
            final byte[] key = auth.read(256);
            keys.put(keyDc, key);
        }
        final int destroy = auth.i32();
        if (destroy < 0 || destroy > 32) {
            throw new Failure("invalid_file");
        }
        auth.read(destroy * 260);
        if (auth.remaining() != 0 || userId == 0 || !keys.containsKey(dcId)) {
            throw new Failure("invalid_file");
        }
        final Account account = new Account();
        account.kind = KIND_TDESKTOP;
        account.dcId = dcId;
        account.authKey = keys.get(dcId);
        account.userId = userId;
        account.detail = "#" + index;
        return account;
    }

    /** имя файла аккаунта: md5 от "data"/"data#N" с переставленными полубайтами */
    static String accountFilename(int index) {
        final String name = index == 0 ? "data" : "data#" + (index + 1);
        final byte[] digest = md5(name.getBytes());
        final StringBuilder builder = new StringBuilder();
        for (int a = 0; a < 8; ++a) {
            final int value = digest[a] & 0xFF;
            builder.append(Character.toUpperCase(Character.forDigit(value & 0x0F, 16)));
            builder.append(Character.toUpperCase(Character.forDigit(value >> 4, 16)));
        }
        return builder.toString();
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

        byte[] read(String stem) throws Failure {
            Failure last = null;
            for (String suffix : new String[]{"s", "0", "1"}) {
                final ZipEntry entry = entries.get(prefix + stem + suffix);
                if (entry == null) {
                    continue;
                }
                if (entry.getSize() > MAX_RECORD) {
                    throw new Failure("archive_limits");
                }
                try {
                    final byte[] data = readStream(zip.getInputStream(entry), MAX_RECORD);
                    return tdfPayload(data);
                } catch (Failure failure) {
                    last = failure;
                } catch (Throwable error) {
                    last = new Failure("invalid_file");
                }
            }
            if (last != null) {
                throw last;
            }
            throw new Failure("incomplete_tdata");
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
        if (text.length() < 64 || text.length() > 1024) {
            return null;
        }
        byte[] data;
        try {
            data = Base64.decode(text, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable error) {
            try {
                data = Base64.decode(text, Base64.DEFAULT);
            } catch (Throwable second) {
                return null;
            }
        }
        if (data == null || data.length < 260) {
            return null;
        }
        // Telethon StringSession: версия 1, dc (1 байт), адрес (4 или 16),
        // порт (2), ключ (256).
        if (data[0] == 1 && (data.length == 1 + 1 + 4 + 2 + 256
                || data.length == 1 + 1 + 16 + 2 + 256)) {
            final int keyOffset = 1 + 1 + (data.length == 264 ? 4 : 16) + 2;
            final Account account = new Account();
            account.kind = KIND_STRING;
            account.dcId = data[1] & 0xFF;
            account.authKey = Arrays.copyOfRange(data, keyOffset, keyOffset + 256);
            account.detail = "Telethon";
            return account.isUsable() ? account : null;
        }
        // Pyrogram: dc (1), api_id (4 BE), test (1), ключ (256), user_id (8)
        if (data.length == 1 + 4 + 1 + 256 + 8) {
            final Account account = new Account();
            account.kind = KIND_STRING;
            account.dcId = data[0] & 0xFF;
            account.apiId = readIntBE(data, 1);
            account.testMode = data[5] != 0;
            account.authKey = Arrays.copyOfRange(data, 6, 6 + 256);
            account.userId = ((long) readIntBE(data, 262) << 32) | (readIntBE(data, 266) & 0xFFFFFFFFL);
            account.detail = "Pyrogram";
            if (account.testMode) {
                return null;
            }
            return account.isUsable() ? account : null;
        }
        return null;
    }

    // ---------------------------------------------------------------- утилиты

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
            final int value = (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8)
                    | ((data[position + 2] & 0xFF) << 16) | ((data[position + 3] & 0xFF) << 24);
            position += 4;
            return value;
        }

        int i32() throws Failure {
            return u32();
        }

        long u64() throws Failure {
            final long low = u32() & 0xFFFFFFFFL;
            final long high = u32() & 0xFFFFFFFFL;
            return (high << 32) | low;
        }

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
