package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Pengram: трассировка пути отправки текстового сообщения.
 *
 * <p>Зачем: анимация отправки «мигает» только на устройстве, а воспроизвести её
 * здесь нечем — ни устройства, ни эмулятора, ни GL. Этот класс собирает ровно те
 * числа, по которым видно, кто и что рисует в каждом кадре перелёта: геометрию
 * пузыря и текста overlay, реальное положение строки, альфу ячейки, флаг
 * глушения, состояние блоков текста и момент, когда вёрстка сообщения
 * пересоздаётся.
 *
 * <p>По умолчанию выключено и в выключенном состоянии стоит одну проверку
 * статического флага на точку — на производительность не влияет. Буфер кольцевой
 * и держится в памяти, наружу уходит только файлом по явному действию
 * пользователя.
 */
public final class PengramSendTrace {

    public static final String KEY = "sendTraceEnabled";
    private static final int MAX_LINES = 8000;

    private static final ArrayList<String> lines = new ArrayList<>(512);
    private static long startedAt;
    private static int frames;
    private static int sends;

    private PengramSendTrace() {
    }

    /**
     * Кэш флага. {@code drawMessageText} вызывается на каждый кадр каждой
     * текстовой строки, и чтение настройки там было бы расходом, которого
     * раньше не было: трассировка не должна сама влиять на плавность.
     * Сбрасывается в {@link #send()} — то есть перечитывается на каждое новое
     * сообщение, поэтому сброс настроек в другом месте не оставит сбор
     * включённым навсегда.
     */
    private static volatile Boolean cachedEnabled;

    public static boolean enabled() {
        final Boolean cached = cachedEnabled;
        if (cached != null) {
            return cached;
        }
        final boolean value = PengramConfig.getIntCached(KEY, 0) != 0;
        cachedEnabled = value;
        return value;
    }

    public static void setEnabled(boolean value) {
        PengramConfig.setIntValue(KEY, value ? 1 : 0);
        cachedEnabled = value;
        if (value) {
            reset();
        }
    }

    /** Новый сеанс сбора: буфер, счётчики и точка отсчёта времени. */
    public static synchronized void reset() {
        lines.clear();
        startedAt = SystemClock.uptimeMillis();
        frames = 0;
        sends = 0;
    }

    public static synchronized int lineCount() {
        return lines.size();
    }

    /** Отметка нового отправленного сообщения — граница между перелётами. */
    public static synchronized void send() {
        sends++;
        frames = 0;
        cachedEnabled = null;
    }

    public static synchronized int nextFrame() {
        return ++frames;
    }

    /**
     * Запись строки трассы.
     *
     * @param tag    короткая метка точки (SEND, CTOR, FRAME, DRAW, CELL, LAYOUT…)
     * @param detail готовые пары {@code key=value} через пробел
     */
    public static synchronized void log(String tag, String detail) {
        if (!enabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        // uptimeMillis уже в миллисекундах: раньше здесь было деление на 1000, и
        // первый лог с устройства показывал «5.1ms» вместо 5100 мс — по такому
        // логу длительность перелёта не измерить.
        lines.add(String.format(Locale.US, "%8dms %-6s %s", now - startedAt, tag, detail));
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
    }

    public static void log(String tag, String key, Object value) {
        if (enabled()) {
            log(tag, key + '=' + value);
        }
    }

    /** Заголовок отчёта: устройство, версия, настройки, влияющие на путь отправки. */
    private static String header() {
        final StringBuilder b = new StringBuilder(2048);
        b.append("Pengram send trace\n");
        b.append("captured=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        b.append("app=").append(PengramVersion.report().replace('\n', ';')).append('\n');
        b.append("performanceClass=").append(SharedConfig.getDevicePerformanceClass()).append('\n');
        b.append("density=").append(AndroidUtilities.density).append(" display=").append(AndroidUtilities.displaySize.x)
                .append('x').append(AndroidUtilities.displaySize.y).append('\n');
        b.append("inputAnimation=").append(PengramConfig.getInputAnimation());
        b.append(" sendTextStyle=").append(PengramConfig.getSendTextStyle());
        b.append('\n');
        // Состояние жидкого стекла: оно меняет высоту «острова» ввода, от которой
        // считается цель перелёта.
        b.append("glassInputSignature=").append(org.telegram.ui.Components.PengramGlassSkin.signature(
                org.telegram.ui.Components.PengramGlassSkin.SURFACE_INPUT));
        b.append(" glassRefraction=").append(org.telegram.ui.Components.PengramGlassSkin.refractionAllowed());
        b.append('\n');
        b.append("sends=").append(sends).append(" frames=").append(frames).append(" lines=").append(lines.size()).append('\n');
        b.append("--- legend ---\n");
        b.append("SEND   новая отправка, счётчик кадров сброшен\n");
        b.append("CTOR   конструктор перелёта: bail=<причина> если перелёт не создан\n");
        b.append("START  перелёт стартовал: willRun=запустится ли аниматор\n");
        b.append("FRAME  кадр overlay: bub=прямоугольник пузыря, cellText=где текст строки,\n");
        b.append("       overlayText=где его рисует overlay, drew*=кто реально рисовал текст\n");
        b.append("DRAW   отрисовка текста: who=overlay|cell, alpha, блоки, crossfade\n");
        b.append("SKIP   текст не нарисован: reason=no_blocks|alpha_zero\n");
        b.append("CELL   смена состояния ячейки: suppress/release, generation\n");
        b.append("LAYOUT пересоздание вёрстки сообщения: softWrap/U+200B/layoutCreated/failures\n");
        b.append("ANIM   ChatListItemAnimator: создан ли перелёт и почему нет\n");
        b.append("FINISH завершение перелёта: progress, frames, id\n");
        b.append("---\n");
        return b.toString();
    }

    public static synchronized String dump() {
        final StringBuilder b = new StringBuilder(header());
        for (int i = 0; i < lines.size(); i++) {
            b.append(lines.get(i)).append('\n');
        }
        return b.toString();
    }

    /**
     * Сохранить отчёт в {@code files/logs/} — этот каталог опубликован в
     * {@code provider_paths}, поэтому файл можно отдать наружу через FileProvider.
     */
    public static File writeToFile(Context context) {
        if (context == null) {
            return null;
        }
        final File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        final File file = new File(dir, "pengram-send-trace-" + stamp + ".txt");
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            writer.write(dump());
            writer.flush();
            return file;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** Отдать отчёт наружу: файл в общий лист отправки, текст — в буфер обмена. */
    public static boolean share(Context context) {
        if (context == null) {
            return false;
        }
        final File file = writeToFile(context);
        if (file == null) {
            return false;
        }
        try {
            final Uri uri = FileProvider.getUriForFile(context,
                    ApplicationLoader.getApplicationId() + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "Pengram send trace");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(intent, "Pengram send trace")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            // Файл уже на диске — если шарить нечем, хотя бы скопируем текст.
            AndroidUtilities.addToClipboard(dump());
            return false;
        }
    }

    /** Путь к сохранённому отчёту — показать пользователю, если шаринг не сработал. */
    public static String lastFilePath(Context context) {
        if (context == null) {
            return null;
        }
        final File dir = new File(context.getFilesDir(), "logs");
        final File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        File newest = files[0];
        for (File f : files) {
            if (f.getName().startsWith("pengram-send-trace-") && f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest.getAbsolutePath();
    }
}
