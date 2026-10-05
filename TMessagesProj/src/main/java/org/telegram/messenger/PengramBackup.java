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

    /** «PENGRAM» + версия формата */
    private static final byte[] MAGIC = {'P', 'E', 'N', 'G', 'R', 'A', 'M', 1};
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int ITERATIONS = 210000;
    private static final String FALLBACK_SECRET = "pengram.local.backup.v1";

    public static final String EXTENSION = "pen";

    /** ключи, которые переносить бессмысленно или вредно: кеши, разовые отметки, статистика */
    private static final String[] SKIP_PREFIXES = {
            "cache_", "learned", "fail_", "off_", "rej_", "txt_", "anticrash_", "lastCrash",
            "profileHistoryLast", "penguinSecretSeen", "historyDbVersion"
    };

    /** есть ли вообще смысл это читать как .pen */
    public static boolean looksLikeBackup(byte[] data) {
        if (data == null || data.length < MAGIC.length + SALT_LEN + IV_LEN + 16) {
            return false;
        }
        for (int a = 0; a < MAGIC.length; ++a) {
            if (data[a] != MAGIC[a]) {
                return false;
            }
        }
        return true;
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

    private static boolean skip(String key) {
        if (key == null) {
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
            final byte[] plain = gzip(json.getBytes("UTF-8"));
            final byte[] salt = new byte[SALT_LEN];
            final byte[] iv = new byte[IV_LEN];
            final SecureRandom random = new SecureRandom();
            random.nextBytes(salt);
            random.nextBytes(iv);

            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(TAG_BITS, iv));
            final byte[] encrypted = cipher.doFinal(plain);

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(MAGIC);
            out.write(hasPassword(password) ? 1 : 0);
            out.write(salt);
            out.write(iv);
            out.write(encrypted);
            return out.toByteArray();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** нужен ли паролю файл — чтобы спросить пароль только там, где он правда нужен */
    public static boolean needsPassword(byte[] data) {
        return looksLikeBackup(data) && data[MAGIC.length] == 1;
    }

    /** расшифровать файл; null — неверный пароль или битый файл */
    public static String unpack(byte[] data, String password) {
        if (!looksLikeBackup(data)) {
            return null;
        }
        try {
            int offset = MAGIC.length + 1;
            final byte[] salt = new byte[SALT_LEN];
            System.arraycopy(data, offset, salt, 0, SALT_LEN);
            offset += SALT_LEN;
            final byte[] iv = new byte[IV_LEN];
            System.arraycopy(data, offset, iv, 0, IV_LEN);
            offset += IV_LEN;
            final byte[] encrypted = new byte[data.length - offset];
            System.arraycopy(data, offset, encrypted, 0, encrypted.length);

            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(TAG_BITS, iv));
            final byte[] plain = ungzip(cipher.doFinal(encrypted));
            return new String(plain, "UTF-8");
        } catch (Throwable e) {
            return null;   // неверный пароль — это обычное дело, в лог не шумим
        }
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
                copy(stream, out);
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

    private static SecretKeySpec key(String password, byte[] salt) throws Exception {
        final char[] chars = (hasPassword(password) ? password.trim() : FALLBACK_SECRET).toCharArray();
        final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        final byte[] bytes = factory.generateSecret(new PBEKeySpec(chars, salt, ITERATIONS, 256)).getEncoded();
        return new SecretKeySpec(bytes, "AES");
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
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(gzip, out);
        gzip.close();
        return out.toByteArray();
    }

    private static void copy(InputStream from, ByteArrayOutputStream to) throws Exception {
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = from.read(buffer)) > 0) {
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
