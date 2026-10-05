package org.telegram.messenger;

import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pengram: перенос настроек файлом .pen.
 *
 * Внутри файла — сжатый JSON всех переносимых настроек, зашифрованный AES-256-GCM.
 * Ключ выводится из пароля через PBKDF2-HMAC-SHA256 (210 000 итераций, соль на каждый файл),
 * поэтому даже короткий пароль подбирается долго, а подмена содержимого ловится тегом GCM.
 * Без пароля файл тоже шифруется — ключ тогда выводится из встроенной фразы,
 * это защита от «открыл блокнотом и поправил», а не от целенаправленного взлома.
 */
public final class PengramBackup {

    private PengramBackup() {}

    /** «PENGRAM» + версия формата (2 — двухслойное шифрование) */
    private static final byte[] MAGIC = {'P', 'E', 'N', 'G', 'R', 'A', 'M', 2};
    /** первая версия формата: один слой AES-GCM, соль 16 байт */
    private static final byte[] MAGIC_V1 = {'P', 'E', 'N', 'G', 'R', 'A', 'M', 1};
    private static final int SALT_LEN = 32;
    private static final int SALT_LEN_V1 = 16;
    private static final int IV_LEN = 12;
    private static final int CTR_IV_LEN = 16;
    private static final int TAG_BITS = 128;
    /** PBKDF2: столько итераций подбор пароля переживает плохо, а телефон — нормально */
    private static final int ITERATIONS = 320000;
    private static final String FALLBACK_SECRET = "pengram.local.backup.v1";
    /** перец: подбирать пароль «вслепую», не зная сборку, бессмысленно */
    private static final String PEPPER = "pengram//pen//2026//penguin";

    public static final String EXTENSION = "pen";

    /**
     * Потолок распакованных настроек.
     *
     * Gzip сжимает нули в тысячи раз, поэтому восьмимегабайтный .pen мог
     * развернуться в гигабайты и убить приложение по памяти ещё до того, как
     * пользователь что-то подтвердил. Настоящий файл настроек — это десятки
     * килобайт, так что два мегабайта с огромным запасом.
     */
    private static final int MAX_PLAIN = 2 * 1024 * 1024;

    /** ключи, которые переносить бессмысленно или вредно: кеши, разовые отметки, статистика */
    private static final String[] SKIP_PREFIXES = {
            "cache_", "learned", "fail_", "off_", "rej_", "txt_", "anticrash_", "lastCrash",
            "profileHistoryLast", "penguinSecretSeen", "historyDbVersion"
    };

    /** есть ли вообще смысл это читать как .pen */
    public static boolean looksLikeBackup(byte[] data) {
        return version(data) > 0;
    }

    /** 2 — текущий формат, 1 — старый, 0 — это не наш файл */
    private static int version(byte[] data) {
        if (data == null || data.length < MAGIC.length + SALT_LEN_V1 + IV_LEN + 16) {
            return 0;
        }
        for (int a = 0; a < MAGIC.length - 1; ++a) {
            if (data[a] != MAGIC[a]) {
                return 0;
            }
        }
        final byte v = data[MAGIC.length - 1];
        return v == 2 || v == 1 ? v : 0;
    }

    /** имя файла с датой: Pengram-2026-02-14.pen */
    public static String fileName() {
        final java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return "Pengram-" + format.format(new java.util.Date()) + "." + EXTENSION;
    }

    /** все переносимые настройки одним JSON */
    public static String collectJson() {
        PengramConfig.init();
        final SharedPreferences p = prefs();
        if (p == null) {
            return null;
        }
        try {
            final org.json.JSONObject root = new org.json.JSONObject();
            root.put("pengram", 2);
            root.put("version", BuildVars.BUILD_VERSION_STRING);
            root.put("created", System.currentTimeMillis() / 1000L);
            final org.json.JSONObject values = new org.json.JSONObject();
            int count = 0;
            for (java.util.Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                if (skip(entry.getKey())) {
                    continue;
                }
                final Object v = entry.getValue();
                if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof String) {
                    values.put(entry.getKey(), v);
                    count++;
                }
            }
            root.put("count", count);
            root.put("values", values);
            return root.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** сколько настроек уедет в файл — чтобы человеку было что показать до нажатия */
    public static int countTransferable() {
        PengramConfig.init();
        final SharedPreferences p = prefs();
        if (p == null) {
            return 0;
        }
        int count = 0;
        for (java.util.Map.Entry<String, ?> entry : p.getAll().entrySet()) {
            if (!skip(entry.getKey())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Что в файл не едет. Белый список один на всё приложение и живёт в
     * PengramConfig.isExportableKey(): иначе .pen и «экспорт в JSON» расходятся,
     * и в бэкап снова начинает попадать служебное состояние интерфейса.
     */
    private static boolean skip(String key) {
        if (key == null) {
            return true;
        }
        if (!PengramConfig.isExportableKey(key)) {
            return true;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** собрать и зашифровать файл; password может быть пустым */
    public static byte[] pack(String password) {
        final String json = collectJson();
        if (json == null) {
            return null;
        }
        try {
            final SecureRandom random = new SecureRandom();
            // сначала прячем длину: к сжатым данным добавляем случайный хвост
            final byte[] body = pad(gzip(json.getBytes("UTF-8")), random);

            final byte[] salt = new byte[SALT_LEN];
            final byte[] iv = new byte[IV_LEN];
            final byte[] ctrIv = new byte[CTR_IV_LEN];
            random.nextBytes(salt);
            random.nextBytes(iv);
            random.nextBytes(ctrIv);

            final byte[][] keys = keys(password, salt);

            // слой 1: поток AES-256-CTR
            final Cipher inner = Cipher.getInstance("AES/CTR/NoPadding");
            inner.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[1], "AES"), new javax.crypto.spec.IvParameterSpec(ctrIv));
            final byte[] once = inner.doFinal(body);

            // слой 2: AES-256-GCM, заголовок идёт в AAD — подменить его не выйдет
            final ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.write(MAGIC);
            head.write(hasPassword(password) ? 1 : 0);
            head.write(salt);
            head.write(iv);
            head.write(ctrIv);
            final byte[] header = head.toByteArray();

            final Cipher outer = Cipher.getInstance("AES/GCM/NoPadding");
            outer.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(TAG_BITS, iv));
            outer.updateAAD(header);
            final byte[] encrypted = outer.doFinal(once);

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(header);
            out.write(encrypted);
            return out.toByteArray();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** нужен ли файлу пароль — чтобы спрашивать его только там, где он правда нужен */
    public static boolean needsPassword(byte[] data) {
        return looksLikeBackup(data) && data[MAGIC.length] == 1;
    }

    /** расшифровать файл; null — неверный пароль или битый файл */
    public static String unpack(byte[] data, String password) {
        final int version = version(data);
        if (version == 0) {
            return null;
        }
        try {
            final int saltLen = version == 1 ? SALT_LEN_V1 : SALT_LEN;
            int offset = MAGIC.length + 1;
            final byte[] salt = new byte[saltLen];
            System.arraycopy(data, offset, salt, 0, saltLen);
            offset += saltLen;
            final byte[] iv = new byte[IV_LEN];
            System.arraycopy(data, offset, iv, 0, IV_LEN);
            offset += IV_LEN;
            byte[] ctrIv = null;
            if (version >= 2) {
                ctrIv = new byte[CTR_IV_LEN];
                System.arraycopy(data, offset, ctrIv, 0, CTR_IV_LEN);
                offset += CTR_IV_LEN;
            }
            final byte[] header = new byte[offset];
            System.arraycopy(data, 0, header, 0, offset);
            final byte[] encrypted = new byte[data.length - offset];
            System.arraycopy(data, offset, encrypted, 0, encrypted.length);

            final byte[][] keys = version == 1
                    ? new byte[][]{legacyKey(password, salt), null}
                    : keys(password, salt);

            final Cipher outer = Cipher.getInstance("AES/GCM/NoPadding");
            outer.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (version >= 2) {
                outer.updateAAD(header);
            }
            byte[] body = outer.doFinal(encrypted);

            if (version >= 2) {
                final Cipher inner = Cipher.getInstance("AES/CTR/NoPadding");
                inner.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys[1], "AES"), new javax.crypto.spec.IvParameterSpec(ctrIv));
                body = unpad(inner.doFinal(body));
            }
            return new String(ungzip(body), "UTF-8");
        } catch (Throwable e) {
            return null;   // неверный пароль — это обычное дело, в лог не шумим
        }
    }

    /**
     * Расшифровка в фоне.
     *
     * PBKDF2 здесь считает 320 000 итераций — на UI-потоке это гарантированная
     * заморозка интерфейса и ANR даже на честном файле. Результат отдаём в
     * главный поток: null означает «не тот пароль или не наш файл».
     */
    public static void unpackAsync(byte[] data, String password, Utilities.Callback<String> whenDone) {
        Utilities.globalQueue.postRunnable(() -> {
            final String json = unpack(data, password);
            AndroidUtilities.runOnUIThread(() -> whenDone.run(json));
        });
    }

    /** записать файл во временную папку и вернуть его */
    public static File writeToCache(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            final File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            final File file = new File(dir, fileName());
            final FileOutputStream stream = new FileOutputStream(file);
            try {
                stream.write(data);
            } finally {
                stream.close();
            }
            return file;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static byte[] readFile(File file) {
        if (file == null || !file.exists() || file.length() > 8 * 1024 * 1024) {
            return null;
        }
        try {
            final java.io.FileInputStream stream = new java.io.FileInputStream(file);
            try {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy(stream, out, MAX_PLAIN * 4);
                return out.toByteArray();
            } finally {
                stream.close();
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** применить расшифрованный JSON; false — это не наши настройки */
    public static boolean apply(String json) {
        return PengramConfig.importFromJson(json);
    }

    // ------------------------------- мелочи -------------------------------

    private static boolean hasPassword(String password) {
        return password != null && password.trim().length() > 0;
    }

    /** два независимых ключа по 256 бит: для внешнего GCM и внутреннего CTR */
    private static byte[][] keys(String password, byte[] salt) throws Exception {
        final char[] chars = ((hasPassword(password) ? password.trim() : FALLBACK_SECRET) + PEPPER).toCharArray();
        final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        final byte[] material = factory.generateSecret(new PBEKeySpec(chars, salt, ITERATIONS, 512)).getEncoded();
        final byte[] k1 = new byte[32];
        final byte[] k2 = new byte[32];
        System.arraycopy(material, 0, k1, 0, 32);
        System.arraycopy(material, 32, k2, 0, 32);
        java.util.Arrays.fill(material, (byte) 0);
        return new byte[][]{k1, k2};
    }

    /** ключ файлов первой версии — чтобы старые .pen продолжали открываться */
    private static byte[] legacyKey(String password, byte[] salt) throws Exception {
        final char[] chars = (hasPassword(password) ? password.trim() : FALLBACK_SECRET).toCharArray();
        final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return factory.generateSecret(new PBEKeySpec(chars, salt, 210000, 256)).getEncoded();
    }

    /** случайный хвост: по размеру файла не видно, сколько у человека настроек */
    private static byte[] pad(byte[] input, SecureRandom random) {
        final int extra = 64 + random.nextInt(960);
        final byte[] result = new byte[4 + input.length + extra];
        result[0] = (byte) (input.length >>> 24);
        result[1] = (byte) (input.length >>> 16);
        result[2] = (byte) (input.length >>> 8);
        result[3] = (byte) input.length;
        System.arraycopy(input, 0, result, 4, input.length);
        final byte[] tail = new byte[extra];
        random.nextBytes(tail);
        System.arraycopy(tail, 0, result, 4 + input.length, extra);
        return result;
    }

    private static byte[] unpad(byte[] input) throws Exception {
        if (input == null || input.length < 4) {
            throw new IllegalStateException("broken");
        }
        final int length = ((input[0] & 0xFF) << 24) | ((input[1] & 0xFF) << 16) | ((input[2] & 0xFF) << 8) | (input[3] & 0xFF);
        if (length < 0 || length > input.length - 4 || length > MAX_PLAIN) {
            throw new IllegalStateException("broken");
        }
        final byte[] result = new byte[length];
        System.arraycopy(input, 4, result, 0, length);
        return result;
    }

    private static byte[] gzip(byte[] input) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final GZIPOutputStream gzip = new GZIPOutputStream(out);
        gzip.write(input);
        gzip.close();
        return out.toByteArray();
    }

    private static byte[] ungzip(byte[] input) throws Exception {
        final GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(input));
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(gzip, out, MAX_PLAIN);
            return out.toByteArray();
        } finally {
            gzip.close();
        }
    }

    /** копирование с потолком: превышение — это зип-бомба, а не настройки */
    private static void copy(InputStream from, ByteArrayOutputStream to, int limit) throws Exception {
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = from.read(buffer)) > 0) {
            if (to.size() + read > limit) {
                throw new IllegalStateException("too big");
            }
            to.write(buffer, 0, read);
        }
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences("pengramconfig", android.content.Context.MODE_PRIVATE);
    }
}
