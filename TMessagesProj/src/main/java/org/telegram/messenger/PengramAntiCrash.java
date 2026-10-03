package org.telegram.messenger;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.core.content.ContextCompat;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

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

    /** 0 — выключено, 1 — включено, -1 — ещё не читали */
    private static volatile int enabledCache = -1;

    /**
     * Читается на каждом замере и отрисовке ячейки, поэтому ответ держим в
     * обычном поле, а не лезем каждый раз в настройки.
     */
    public static boolean isEnabled() {
        int value = enabledCache;
        if (value < 0) {
            value = PengramConfig.isAntiCrash() ? 1 : 0;
            enabledCache = value;
        }
        return value == 1;
    }

    public static void invalidateEnabled() {
        enabledCache = -1;
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
     * иначе получим шторм записей в prefs. Параллельно ведём журнал последних
     * ста попыток — время и тип, его показывает настройка «Журнал атак».
     */
    public static void report(String reason) {
        session.incrementAndGet();
        lastReason = reason;
        lastTime = System.currentTimeMillis();
        journalAdd(reason);
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
            journalStore();
        } catch (Throwable ignore) {
        }
    }

    /* ------------------- журнал атак ------------------- */

    /** сколько записей храним: старые вытесняются новыми */
    public static final int JOURNAL_LIMIT = 100;

    public static final int KIND_TABLE = 0;
    public static final int KIND_LAYOUT = 1;
    public static final int KIND_ENTITIES = 2;
    public static final int KIND_MESSAGE = 3;
    public static final int KIND_DRAW = 4;
    public static final int KIND_OTHER = 5;

    /** записи вида timeMillis\treason, свежие в конце */
    private static java.util.ArrayList<String> journal;
    private static long lastJournalAdd;

    private static synchronized void journalLoad() {
        if (journal != null) {
            return;
        }
        journal = new java.util.ArrayList<>();
        try {
            final String stored = PengramConfig.getAntiCrashJournal();
            if (stored != null && !stored.isEmpty()) {
                final String[] rows = stored.split("\\n");
                final int from = Math.max(0, rows.length - JOURNAL_LIMIT);
                for (int a = from; a < rows.length; ++a) {
                    if (rows[a] != null && !rows[a].isEmpty()) {
                        journal.add(rows[a]);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static synchronized void journalAdd(String reason) {
        // море одинаковых блоков подряд не должно замусорить журнал
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastJournalAdd < 1500) {
            return;
        }
        lastJournalAdd = now;
        journalLoad();
        journal.add(System.currentTimeMillis() + "\t" + reason);
        while (journal.size() > JOURNAL_LIMIT) {
            journal.remove(0);
        }
    }

    private static synchronized void journalStore() {
        if (journal == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (int a = 0; a < journal.size(); ++a) {
            if (a > 0) {
                sb.append('\n');
            }
            sb.append(journal.get(a));
        }
        PengramConfig.setAntiCrashJournal(sb.toString());
    }

    public static synchronized int journalSize() {
        journalLoad();
        return journal.size();
    }

    /** индексация от старой записи к новой */
    public static synchronized String journalEntry(int index) {
        journalLoad();
        if (index < 0 || index >= journal.size()) {
            return null;
        }
        return journal.get(index);
    }

    public static synchronized void clearJournal() {
        journalLoad();
        journal.clear();
        PengramConfig.setAntiCrashJournal("");
    }

    /** семейство атаки по причине — для значка и короткого названия в журнале */
    public static int kindOf(String reason) {
        if (reason == null) {
            return KIND_OTHER;
        }
        if (reason.startsWith("table")) {
            return KIND_TABLE;
        }
        if (reason.startsWith("rich block") || reason.startsWith("rich blocks") || reason.startsWith("rich layout")) {
            return KIND_LAYOUT;
        }
        if (reason.startsWith("entities")) {
            return KIND_ENTITIES;
        }
        if (reason.startsWith("message layout")) {
            return KIND_MESSAGE;
        }
        if (reason.startsWith("cell measure") || reason.startsWith("cell draw")) {
            return KIND_DRAW;
        }
        return KIND_OTHER;
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

    /* ------ плашка-замена на месте ячейки, которую нельзя нарисовать вообще ------ */

    private static Paint stubFill;
    private static Paint stubStroke;
    private static int stubTextWidth = -1;
    private static StaticLayout stubTitle;
    private static StaticLayout stubMessage;
    private static Drawable stubShield;
    private static int stubShieldColor = Integer.MIN_VALUE;
    private static final RectF stubRect = new RectF();

    /**
     * Нарисовать системную плашку «обезврежено» поверх области ячейки.
     *
     * <p>Используется, когда сообщение нельзя ни сверстать, ни нарисовать
     * штатно. Выглядит как служебное сообщение: такую пластину нельзя
     * подделать обычным текстом чужого сообщения. Любой сбой внутри самой
     * плашки проглатывается — она последний рубеж, дальше падать некуда.
     */
    public static void drawStub(Canvas canvas, Theme.ResourcesProvider provider, int width, int height) {
        try {
            if (canvas == null || width <= 0 || height <= 0) {
                return;
            }
            if (stubFill == null) {
                stubFill = new Paint(Paint.ANTI_ALIAS_FLAG);
                stubStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                stubStroke.setStyle(Paint.Style.STROKE);
            }
            final int serviceBg = Theme.getColor(Theme.key_chat_serviceBackground, provider);
            final int serviceText = Theme.getColor(Theme.key_chat_serviceText, provider);

            final int iconSize = AndroidUtilities.dp(22);
            final int padH = AndroidUtilities.dp(12);
            final int padV = AndroidUtilities.dp(9);
            final int gap = AndroidUtilities.dp(9);
            final float margin = AndroidUtilities.dp(4);
            final float plateWidth = Math.max(0, width - margin * 2);
            final int textWidth = (int) Math.max(1, plateWidth - padH * 2 - iconSize - gap);
            if (textWidth != stubTextWidth || stubTitle == null) {
                stubTextWidth = textWidth;
                final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                titlePaint.setTypeface(AndroidUtilities.bold());
                titlePaint.setTextSize(AndroidUtilities.dp(13.5f));
                stubTitle = new StaticLayout(
                        LocaleController.getString(R.string.PengramAntiCrashStubTitle),
                        titlePaint, textWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
                final TextPaint messagePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                messagePaint.setTextSize(AndroidUtilities.dp(12));
                stubMessage = new StaticLayout(
                        LocaleController.getString(R.string.PengramAntiCrashBlocked),
                        messagePaint, textWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
            }
            stubTitle.getPaint().setColor(serviceText);
            stubMessage.getPaint().setColor(Theme.multAlpha(serviceText, 0.78f));

            if (stubShield == null) {
                stubShield = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.outline_shield_check);
            }
            if (stubShield != null && stubShieldColor != serviceText) {
                stubShieldColor = serviceText;
                stubShield.setColorFilter(new PorterDuffColorFilter(serviceText, PorterDuff.Mode.SRC_IN));
            }

            final int contentH = Math.max(iconSize, stubTitle.getHeight() + AndroidUtilities.dp(2) + stubMessage.getHeight());
            final int plateHeight = contentH + padV * 2;
            final float top = Math.max(0, (height - plateHeight) / 2f);
            stubRect.set(margin, top, margin + plateWidth, top + plateHeight);
            stubFill.setColor(serviceBg);
            stubStroke.setStrokeWidth(AndroidUtilities.dpf2(1));
            stubStroke.setColor(Theme.multAlpha(serviceText, 0.4f));
            final float radius = AndroidUtilities.dpf2(10);
            canvas.drawRoundRect(stubRect, radius, radius, stubFill);
            canvas.drawRoundRect(stubRect, radius, radius, stubStroke);

            final int contentTop = (int) (top + padV);
            if (stubShield != null) {
                final int iconTop = contentTop + (contentH - iconSize) / 2;
                stubShield.setBounds((int) margin + padH, iconTop, (int) margin + padH + iconSize, iconTop + iconSize);
                stubShield.draw(canvas);
            }
            final int textH = stubTitle.getHeight() + AndroidUtilities.dp(2) + stubMessage.getHeight();
            final float textTop = contentTop + (contentH - textH) / 2f;
            canvas.save();
            canvas.translate(margin + padH + iconSize + gap, textTop);
            stubTitle.draw(canvas);
            canvas.translate(0, stubTitle.getHeight() + AndroidUtilities.dp(2));
            stubMessage.draw(canvas);
            canvas.restore();
        } catch (Throwable ignore) {
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
