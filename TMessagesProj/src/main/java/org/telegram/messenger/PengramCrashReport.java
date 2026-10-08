package org.telegram.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Debug;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

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
    private static final String KEY_COPY = "copyToClipboard";
    private static final String SEPARATOR = "\n=== pengram crash ===\n";

    private static volatile boolean installed;
    private static ArrayList<String> cache;

    private PengramCrashReport() {}

    // ------------------------------------------------------------------ установка

    /** вызывается один раз при старте приложения, до всего остального */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    handle(thread, error);
                } catch (Throwable ignore) {
                    // отчёт не должен мешать падению: что угодно пошло не так — просто идём дальше
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            });
        } catch (Throwable ignore) {
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
            final SharedPreferences p = prefs();
            if (p != null) {
                // commit, а не apply: процесс умирает прямо сейчас
                p.edit().putString(KEY_PENDING, report)
                        .putLong(KEY_PENDING_TIME, System.currentTimeMillis())
                        .commit();
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
                p.edit().remove(KEY_PENDING).remove(KEY_PENDING_TIME).apply();
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ «показать один раз»

    /** есть ли отчёт, о котором пользователю ещё не сказали */
    public static boolean hasPending() {
        final SharedPreferences p = prefs();
        return p != null && p.getString(KEY_PENDING, null) != null;
    }

    /** забрать отчёт и пометить показанным */
    public static String consumePending() {
        final SharedPreferences p = prefs();
        if (p == null) {
            return null;
        }
        final String report = p.getString(KEY_PENDING, null);
        if (report != null) {
            p.edit().remove(KEY_PENDING).remove(KEY_PENDING_TIME).apply();
        }
        return report;
    }

    public static long pendingTime() {
        final SharedPreferences p = prefs();
        return p == null ? 0 : p.getLong(KEY_PENDING_TIME, 0);
    }
}
