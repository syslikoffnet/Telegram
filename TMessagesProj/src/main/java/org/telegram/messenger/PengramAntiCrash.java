package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Антикраш: защита от «краш-сообщений».
 *
 * <p>Свежая волна строится не на стикерах, а на разметке: в сообщение кладут
 * таблицу, у ячейки которой colspan в миллиард. Клиент складывает
 * {@code start + size}, int переполняется, и дальше размер сетки уходит в
 * {@code Integer.MIN_VALUE} — падение в {@code new int[-2147483648]} ещё до
 * того, как сообщение успеет нарисоваться. Чат после такого не открывается
 * вообще: краш повторяется при каждой загрузке истории.
 *
 * <p>Идея защиты: не прятать сообщение, а привести его к вменяемым размерам и
 * обложить рискованные участки ловушкой. Пользователь видит всё, что можно
 * показать; то, что показать физически нельзя, заменяется плашкой
 * «заблокировано», а не падением приложения.
 *
 * <p>Все пределы заданы заведомо выше любого настоящего сообщения, поэтому на
 * нормальной переписке защита не меняет ни вёрстку, ни поведение.
 */
public class PengramAntiCrash {

    /** предельный номер строки/столбца таблицы */
    public static final int MAX_TABLE_INDEX = 2048;
    /** предельный colspan/rowspan одной ячейки */
    public static final int MAX_TABLE_SPAN = 64;
    /** предельное число ячеек в одной таблице */
    public static final int MAX_TABLE_CELLS = 4096;
    /** предельная глубина вложенности блоков (списки в списках и т.д.) */
    public static final int MAX_BLOCK_LEVEL = 24;
    /** предельное число блоков в одном сообщении */
    public static final int MAX_BLOCKS = 1500;

    private static final String KEY_BLOCKED = "antiCrashBlocked";

    private static final AtomicInteger session = new AtomicInteger();
    private static volatile String lastReason;
    private static volatile long lastTime;
    private static long lastStore;

    public static boolean isEnabled() {
        return PengramConfig.isAntiCrash();
    }

    /** показывать ли плашку на месте обезвреженного блока */
    public static boolean isMarking() {
        return PengramConfig.isAntiCrashMark();
    }

    /** сколько атак обезврежено за всё время */
    public static int totalBlocked() {
        return PengramConfig.getAntiCrashBlocked();
    }

    public static String lastReason() {
        return lastReason;
    }

    public static long lastTime() {
        return lastTime;
    }

    public static void resetStats() {
        session.set(0);
        lastReason = null;
        lastTime = 0;
        PengramConfig.setAntiCrashBlocked(0);
    }

    /**
     * Зафиксировать обезвреженную попытку. Счётчик в памяти обновляется сразу,
     * на диск пишем не чаще раза в пять секунд: при потоке битых сообщений
     * иначе получим шторм записей в prefs.
     */
    public static void report(String reason) {
        session.incrementAndGet();
        lastReason = reason;
        lastTime = System.currentTimeMillis();
        try {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("pengram anticrash: " + reason);
            }
        } catch (Throwable ignore) {
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastStore < 5000) {
            return;
        }
        lastStore = now;
        try {
            PengramConfig.setAntiCrashBlocked(PengramConfig.getAntiCrashBlocked() + session.getAndSet(0));
        } catch (Throwable ignore) {
        }
    }

    /** colspan/rowspan — в разумные рамки */
    public static int clampSpan(int span) {
        if (span < 1) {
            return 1;
        }
        if (span > MAX_TABLE_SPAN) {
            report("table span " + span);
            return MAX_TABLE_SPAN;
        }
        return span;
    }

    /** номер строки/столбца — в разумные рамки */
    public static int clampIndex(int index) {
        if (index < 0) {
            report("table index " + index);
            return 0;
        }
        if (index > MAX_TABLE_INDEX) {
            report("table index " + index);
            return MAX_TABLE_INDEX;
        }
        return index;
    }

    /**
     * Конец интервала без переполнения: именно сложение {@code start + size}
     * в int и даёт отрицательный размер сетки.
     */
    public static int spanEnd(int start, int size) {
        final long end = (long) start + (long) Math.max(1, size);
        if (end > MAX_TABLE_INDEX) {
            report("table span end " + end);
            return MAX_TABLE_INDEX;
        }
        return (int) Math.max(1, end);
    }

    public static boolean tooManyCells(int count) {
        if (count >= MAX_TABLE_CELLS) {
            report("table cells " + count);
            return true;
        }
        return false;
    }

    /** предельное число сущностей форматирования в одном сообщении */
    public static final int MAX_ENTITIES = 4000;

    /**
     * Выбросить из сообщения заведомо невозможные сущности форматирования.
     *
     * <p>Трогаем только явный мусор: отрицательные и гигантские смещения и
     * длины, которых не бывает ни у одного настоящего сообщения. Сущности,
     * которые просто не попали в текущий текст (перевод, служебные сообщения),
     * остаются на месте — иначе защита сама испортила бы вёрстку.
     */
    public static void sanitizeEntities(TLRPC.Message message) {
        if (message == null || message.entities == null || message.entities.isEmpty()) {
            return;
        }
        boolean changed = false;
        if (message.entities.size() > MAX_ENTITIES) {
            while (message.entities.size() > MAX_ENTITIES) {
                message.entities.remove(message.entities.size() - 1);
            }
            changed = true;
        }
        for (int a = message.entities.size() - 1; a >= 0; --a) {
            final TLRPC.MessageEntity entity = message.entities.get(a);
            if (entity == null
                    || entity.offset < 0 || entity.length < 0
                    || entity.offset > 1_000_000 || entity.length > 1_000_000
                    || (long) entity.offset + (long) entity.length > Integer.MAX_VALUE / 2) {
                message.entities.remove(a);
                changed = true;
            }
        }
        if (changed) {
            report("entities");
        }
    }

    /**
     * Выполнить рискованный участок вёрстки. Возвращает {@code true}, если всё
     * прошло штатно; при падении ловит всё, включая {@link OutOfMemoryError} и
     * {@link StackOverflowError} — именно ими обычно и заканчиваются
     * специально собранные сообщения.
     */
    public static boolean guard(String reason, Runnable action) {
        if (!isEnabled()) {
            action.run();
            return true;
        }
        try {
            action.run();
            return true;
        } catch (Throwable e) {
            report(reason + ": " + e.getClass().getSimpleName());
            return false;
        }
    }
}
