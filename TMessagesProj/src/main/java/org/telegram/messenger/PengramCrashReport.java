package org.telegram.messenger;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Debug;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pengram: отчёт о вылете.
 *
 * <p>Когда приложение падает, пользователю обычно нечего показать разработчику:
 * системное окно закрывается, а логи лежат там, куда без компьютера не добраться.
 * Здесь мы перехватываем падение, собираем короткий человекочитаемый отчёт
 * (версия, устройство, поток, причина, стек) и <b>сразу кладём его в буфер обмена</b> —
 * достаточно открыть чат и вставить.
 *
 * <p>Тот же отчёт сохраняется в файл, поэтому при следующем запуске мы показываем
 * окно «приложение вылетело, причина уже скопирована», а в настройках есть журнал
 * последних падений с кнопками «скопировать» и «поделиться».
 *
 * <p>Перехватчик никогда не «съедает» падение: отработав, он передаёт исключение
 * дальше, прежнему обработчику, поэтому стандартное поведение системы (и логи
 * Telegram) сохраняется.
 */
public final class PengramCrashReport {

    /** сколько последних падений храним */
    public static final int LIMIT = 10;

    private static final String PREFS = "pengramcrash";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_PENDING_TIME = "pendingTime";
    private static final String KEY_PENDING_INSTALL_STAMP = "pendingInstallStamp";
    private static final String KEY_COPY = "copyToClipboard";
    private static final String KEY_NATIVE_EXIT_SEEN = "nativeExitSeen";
    private static final long MAX_PENDING_AGE_MS = 24L * 3600000L;
    private static final String SEPARATOR = "\n=== pengram crash ===\n";

    private static volatile Thread.UncaughtExceptionHandler installedHandler;
    private static volatile String cachedInstallStamp;
    private static final ThreadLocal<Boolean> handling = new ThreadLocal<>();
    private static ArrayList<String> cache;

    private PengramCrashReport() {}

    // ------------------------------------------------------------------ установка

    /** вызывается один раз при старте приложения, до всего остального */
    public static synchronized void install() {
        try {
            // Прогреваем отпечаток установленного APK заранее и сразу гасим
            // «висящие» отчёты от прошлой сборки, если приложение обновили.
            currentInstallStamp();
            pruneStalePending();
        } catch (Throwable ignore) {
        }
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            if (previous == installedHandler) return;
            final Thread.UncaughtExceptionHandler wrapper = (thread, error) -> {
                if (!Boolean.TRUE.equals(handling.get())) {
                    handling.set(true);
                    try {
                        handle(thread, error);
                    } catch (Throwable ignore) {
                        // Reporting must never replace the original crash.
                    }
                }
                try {
                    if (previous != null) previous.uncaughtException(thread, error);
                } finally {
                    handling.remove();
                }
            };
            Thread.setDefaultUncaughtExceptionHandler(wrapper);
            installedHandler = wrapper;
        } catch (Throwable ignore) {
        }
    }

    /** A native SIGABRT/SIGSEGV bypasses Java's uncaught-exception handler.
     * Android 11+ exposes the previous process exit (and sometimes its tombstone).
     * This is best effort; earlier Android versions need an adb/logcat report. */
    public static void recoverNativeCrashIfAny() {
        if (Build.VERSION.SDK_INT < 30 || hasPending()) return;
        try {
            final Context context = ApplicationLoader.applicationContext;
            final SharedPreferences p = prefs();
            if (context == null || p == null) return;
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            final long installUpdateTime = appInstallUpdateTimeMs();
            for (ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5)) {
                if (!context.getPackageName().equals(info.getProcessName())) continue;
                long timestamp = info.getTimestamp();
                if (timestamp <= p.getLong(KEY_NATIVE_EXIT_SEEN, 0) || timestamp > System.currentTimeMillis()
                        || System.currentTimeMillis() - timestamp > 7L * 86400000L) break;
                // Mark this exit before writing: do not redisplay a stale crash on every launch.
                p.edit().putLong(KEY_NATIVE_EXIT_SEEN, timestamp).commit();
                // Падение случилось до установки обновления — не выдаём его за вылет новой сборки.
                if (installUpdateTime > 0 && timestamp < installUpdateTime) break;
                if (info.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) break;
                StringBuilder sb = new StringBuilder("Pengram native crash report\n");
                sb.append("Время: ").append(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
                        .format(new Date(timestamp))).append('\n');
                sb.append("Версия: ").append(BuildVars.BUILD_VERSION_STRING).append('\n');
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ")
                        .append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
                sb.append("Причина: native crash (signal / JNI), status ").append(info.getStatus()).append("\n\n");
                try (InputStream trace = info.getTraceInputStream()) {
                    if (trace != null) {
                        // Android 11+ returns a binary debuggerd protobuf, not UTF-8.
                        // Read a fixed budget even if the tombstone contains huge memory dumps.
                        byte[] bytes = new byte[131072];
                        int count = 0, read;
                        while (count < bytes.length && (read = trace.read(bytes, count, bytes.length - count)) > 0) {
                            count += read;
                        }
                        sb.append(PengramTombstone.summarize(bytes, count));
                    }
                } catch (Throwable ignore) {
                    sb.append("Tombstone unavailable; collect adb logcat for details.\n");
                }
                store(sb.toString());
                return;
            }
        } catch (Throwable ignore) {
            // getHistoricalProcessExitReasons is not consistently available on all OEMs.
        }
    }

    private static void handle(Thread thread, Throwable error) {
        final String report = build(thread, error);
        store(report);
        if (isCopyEnabled()) {
            // буфер обмена пишется прямо здесь: приложение ещё на переднем плане,
            // а через секунду процесс уже умрёт и шанса не будет
            copy(report);
        }
    }

    // ------------------------------------------------------------------ сам отчёт

    /** собрать текст отчёта */
    public static String build(Thread thread, Throwable error) {
        final StringBuilder sb = new StringBuilder(2048);
        sb.append("Pengram crash report").append('\n');
        try {
            sb.append("Время: ")
                    .append(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(new Date()))
                    .append('\n');
            sb.append("Версия: ").append(BuildVars.BUILD_VERSION_STRING);
            try {
                final Context context = ApplicationLoader.applicationContext;
                if (context != null) {
                    sb.append(" (").append(context.getPackageName()).append(", code ")
                            .append(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode)
                            .append(')');
                }
            } catch (Throwable ignore) {
            }
            sb.append('\n');
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" / ").append(Build.DEVICE).append('\n');
            sb.append("ABI: ").append(Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                    ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
            sb.append("Язык: ").append(Locale.getDefault()).append('\n');
            sb.append("Поток: ").append(thread != null ? thread.getName() : "?").append('\n');
            try {
                final Runtime runtime = Runtime.getRuntime();
                sb.append("Память: ")
                        .append((runtime.totalMemory() - runtime.freeMemory()) / 1048576L).append(" / ")
                        .append(runtime.maxMemory() / 1048576L).append(" МБ, native ")
                        .append(Debug.getNativeHeapAllocatedSize() / 1048576L).append(" МБ\n");
            } catch (Throwable ignore) {
            }
            final String lastAttack = PengramAntiCrash.lastReason();
            if (lastAttack != null) {
                sb.append("Антикраш, последнее: ").append(lastAttack).append('\n');
            }
        } catch (Throwable ignore) {
        }
        sb.append('\n');
        sb.append(stackOf(error));
        return sb.toString();
    }

    /** короткая причина — одной строкой, для заголовков и списка */
    public static String shortReason(String report) {
        if (report == null) {
            return null;
        }
        final int from = report.indexOf("\n\n");
        String tail = from >= 0 ? report.substring(from + 2) : report;
        final int line = tail.indexOf('\n');
        if (line > 0) {
            tail = tail.substring(0, line);
        }
        tail = tail.trim();
        // java.lang.IllegalStateException: что-то пошло не так → IllegalStateException: что-то...
        final int dot = tail.lastIndexOf('.', Math.max(0, tail.indexOf(':')));
        if (dot > 0 && dot + 1 < tail.length()) {
            tail = tail.substring(dot + 1);
        }
        return tail.length() > 160 ? tail.substring(0, 157) + "…" : tail;
    }

    /** время падения из отчёта (или 0) */
    public static long timeOf(String report) {
        try {
            final int from = report.indexOf("Время: ");
            if (from < 0) {
                return 0;
            }
            final int end = report.indexOf('\n', from);
            return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
                    .parse(report.substring(from + 7, end < 0 ? report.length() : end)).getTime();
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static String stackOf(Throwable error) {
        if (error == null) {
            return "no stack trace";
        }
        try {
            final StringWriter writer = new StringWriter(2048);
            error.printStackTrace(new PrintWriter(writer));
            final String text = writer.toString();
            // гигантские стеки (переполнение стека) режем — в буфер обмена столько не нужно
            return text.length() > 12000 ? text.substring(0, 12000) + "\n… обрезано" : text;
        } catch (Throwable ignore) {
            return String.valueOf(error);
        }
    }

    // ------------------------------------------------------------------ буфер обмена

    /** положить текст в буфер обмена; работает и из падающего потока */
    public static boolean copy(CharSequence text) {
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null || text == null) {
                return false;
            }
            final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return false;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Pengram crash", text));
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static boolean isCopyEnabled() {
        try {
            return prefs() != null && prefs().getBoolean(KEY_COPY, false);
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void setCopyEnabled(boolean value) {
        try {
            final SharedPreferences p = prefs();
            if (p != null) {
                p.edit().putBoolean(KEY_COPY, value).apply();
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ хранение

    private static SharedPreferences prefs() {
        final Context context = ApplicationLoader.applicationContext;
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File file() {
        try {
            final File dir = ApplicationLoader.getFilesDirFixed();
            return dir == null ? null : new File(dir, "pengram_crashes.txt");
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static synchronized void store(String report) {
        try {
            final ArrayList<String> list = all();
            list.add(report);
            while (list.size() > LIMIT) {
                list.remove(0);
            }
            write(list);
            cache = new ArrayList<>(list);
            final SharedPreferences p = prefs();
            if (p != null) {
                // commit, а не apply: процесс умирает прямо сейчас
                final SharedPreferences.Editor editor = p.edit()
                        .putString(KEY_PENDING, report)
                        .putLong(KEY_PENDING_TIME, System.currentTimeMillis());
                final String stamp = cachedInstallStamp;
                if (stamp != null && !stamp.isEmpty()) {
                    editor.putString(KEY_PENDING_INSTALL_STAMP, stamp);
                }
                editor.commit();
            }
        } catch (Throwable ignore) {
        }
    }

    private static void write(ArrayList<String> list) {
        final File file = file();
        if (file == null) {
            return;
        }
        try {
            final StringBuilder sb = new StringBuilder();
            for (int a = 0; a < list.size(); ++a) {
                if (a > 0) {
                    sb.append(SEPARATOR);
                }
                sb.append(list.get(a));
            }
            final java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignore) {
        }
    }

    /** все сохранённые отчёты, старые в начале */
    public static synchronized ArrayList<String> all() {
        if (cache != null) {
            return new ArrayList<>(cache);
        }
        final ArrayList<String> list = new ArrayList<>();
        try {
            final File file = file();
            if (file != null && file.exists() && file.length() > 0 && file.length() < 2_000_000) {
                final byte[] bytes = new byte[(int) file.length()];
                final java.io.FileInputStream in = new java.io.FileInputStream(file);
                try {
                    int read = 0;
                    while (read < bytes.length) {
                        final int n = in.read(bytes, read, bytes.length - read);
                        if (n <= 0) {
                            break;
                        }
                        read += n;
                    }
                } finally {
                    in.close();
                }
                for (String part : new String(bytes, "UTF-8").split(SEPARATOR)) {
                    if (part != null && !part.trim().isEmpty()) {
                        list.add(part);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        cache = new ArrayList<>(list);
        return list;
    }

    public static int count() {
        return all().size();
    }

    /** самый свежий отчёт или null */
    public static String last() {
        final ArrayList<String> list = all();
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    public static synchronized void clear() {
        cache = new ArrayList<>();
        final File file = file();
        try {
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            final SharedPreferences p = prefs();
            if (p != null) {
                p.edit().remove(KEY_PENDING)
                        .remove(KEY_PENDING_TIME)
                        .remove(KEY_PENDING_INSTALL_STAMP)
                        .apply();
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ «показать один раз»

    /** время последней установки или обновления APK по данным PackageManager и файла APK */
    private static long appInstallUpdateTimeMs() {
        long best = 0;
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            final PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (pInfo != null) {
                best = Math.max(pInfo.firstInstallTime, pInfo.lastUpdateTime);
            }
            final ApplicationInfo appInfo = context.getApplicationInfo();
            if (appInfo != null && appInfo.sourceDir != null) {
                final File apk = new File(appInfo.sourceDir);
                if (apk.exists()) {
                    best = Math.max(best, apk.lastModified());
                }
            }
        } catch (Throwable ignore) {
        }
        return best;
    }

    /**
     * Отпечаток конкретной установленной сборки APK (включая размер и CRC32 classes.dex),
     * чтобы отличать пересборки даже с одинаковым versionCode (например, beta-билды).
     */
    private static String currentInstallStamp() {
        final String existing = cachedInstallStamp;
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return null;
            }
            final PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            final long versionCode = pInfo == null ? 0
                    : (Build.VERSION.SDK_INT >= 28 ? pInfo.getLongVersionCode() : pInfo.versionCode);
            final long updateTime = pInfo == null ? 0 : pInfo.lastUpdateTime;
            long apkSize = 0;
            long apkMtime = 0;
            long dexCrc = 0;
            final ApplicationInfo appInfo = context.getApplicationInfo();
            if (appInfo != null && appInfo.sourceDir != null) {
                final File apk = new File(appInfo.sourceDir);
                if (apk.exists()) {
                    apkSize = apk.length();
                    apkMtime = apk.lastModified();
                    try (ZipFile zip = new ZipFile(apk)) {
                        final ZipEntry dex = zip.getEntry("classes.dex");
                        if (dex != null) {
                            dexCrc = dex.getCrc();
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
            final String built = versionCode + ":" + updateTime + ":" + apkSize + ":" + apkMtime + ":" + dexCrc
                    + ":" + BuildVars.BUILD_VERSION_STRING;
            cachedInstallStamp = built;
            return built;
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * Если необработанный отчёт остался с прошлой сборки (до обновления приложения)
     * или устарел более чем на сутки, снимаем флаг ожидания, чтобы после обновления
     * не всплывало окно о старом вылете (в журнале падений запись при этом остаётся).
     */
    private static void pruneStalePending() {
        try {
            final SharedPreferences p = prefs();
            if (p == null) {
                return;
            }
            final String pending = p.getString(KEY_PENDING, null);
            if (pending == null) {
                return;
            }
            long crashTime = p.getLong(KEY_PENDING_TIME, 0);
            if (crashTime <= 0) {
                crashTime = timeOf(pending);
            }
            final long installUpdateTime = appInstallUpdateTimeMs();
            final String savedStamp = p.getString(KEY_PENDING_INSTALL_STAMP, null);
            final String currentStamp = currentInstallStamp();
            final long now = System.currentTimeMillis();

            final boolean predatesUpdate = installUpdateTime > 0
                    && (crashTime <= 0 || crashTime < installUpdateTime);
            final boolean buildChanged = savedStamp != null && !savedStamp.isEmpty()
                    && currentStamp != null && !currentStamp.isEmpty()
                    && !savedStamp.equals(currentStamp);
            final boolean expired = crashTime > 0 && now > 0
                    && (now - crashTime > MAX_PENDING_AGE_MS || crashTime - now > 3600000L);

            if (predatesUpdate || buildChanged || expired) {
                p.edit().remove(KEY_PENDING)
                        .remove(KEY_PENDING_TIME)
                        .remove(KEY_PENDING_INSTALL_STAMP)
                        .commit();
            }
        } catch (Throwable ignore) {
        }
    }

    /** есть ли отчёт, о котором пользователю ещё не сказали */
    public static boolean hasPending() {
        pruneStalePending();
        final SharedPreferences p = prefs();
        return p != null && p.getString(KEY_PENDING, null) != null;
    }

    /** Do not acknowledge until the crash dialog has actually opened. */
    public static String pendingReport() {
        pruneStalePending();
        final SharedPreferences p = prefs();
        return p == null ? null : p.getString(KEY_PENDING, null);
    }

    public static void acknowledgePending(String report) {
        final SharedPreferences p = prefs();
        if (p != null && report != null && report.equals(p.getString(KEY_PENDING, null))) {
            p.edit().remove(KEY_PENDING)
                    .remove(KEY_PENDING_TIME)
                    .remove(KEY_PENDING_INSTALL_STAMP)
                    .commit();
        }
    }

    public static long pendingTime() {
        pruneStalePending();
        final SharedPreferences p = prefs();
        return p == null ? 0 : p.getLong(KEY_PENDING_TIME, 0);
    }
}
