package org.telegram.messenger;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Приблизительная дата регистрации аккаунта по его ID.
 * ID в Telegram выдаются последовательно, поэтому по опорным точкам
 * (id -> дата) можно интерполировать момент регистрации. Результат всегда
 * приблизительный, поэтому в UI показывается со знаком «≈».
 */
public class PengramRegDate {

    /**
     * Опорные точки {id, unixtime}. Первая шкала — «старое» 32-битное пространство,
     * вторая (с 5 000 000 000) — новое, его Telegram начал раздавать осенью 2022-го.
     * Точки отсортированы по id внутри каждой шкалы.
     */
    private static final long[][] POINTS = {
            {1L,            1376438400L}, // 2013-08-14
            {1000000L,      1383264000L}, // 2013-11-01
            {2000000L,      1385856000L}, // 2013-12-01
            {5000000L,      1388534400L}, // 2014-01-01
            {10000000L,     1393632000L}, // 2014-03-01
            {20000000L,     1401580800L}, // 2014-06-01
            {30000000L,     1409529600L}, // 2014-09-01
            {50000000L,     1417392000L}, // 2014-12-01
            {70000000L,     1430438400L}, // 2015-05-01
            {90000000L,     1443657600L}, // 2015-10-01
            {100000000L,    1451606400L}, // 2016-01-01
            {120000000L,    1464739200L}, // 2016-06-01
            {140000000L,    1477958400L}, // 2016-11-01
            {150000000L,    1485907200L}, // 2017-02-01
            {180000000L,    1493596800L}, // 2017-05-01
            {200000000L,    1498867200L}, // 2017-07-01
            {230000000L,    1504224000L}, // 2017-09-01
            {250000000L,    1506816000L}, // 2017-10-01
            {280000000L,    1512086400L}, // 2017-12-01
            {300000000L,    1514764800L}, // 2018-01-01
            {350000000L,    1522540800L}, // 2018-04-01
            {400000000L,    1530403200L}, // 2018-07-01
            {450000000L,    1538352000L}, // 2018-10-01
            {500000000L,    1546300800L}, // 2019-01-01
            {550000000L,    1554076800L}, // 2019-04-01
            {600000000L,    1561939200L}, // 2019-07-01
            {650000000L,    1569888000L}, // 2019-10-01
            {700000000L,    1577836800L}, // 2020-01-01
            {750000000L,    1585699200L}, // 2020-04-01
            {800000000L,    1593561600L}, // 2020-07-01
            {850000000L,    1601510400L}, // 2020-10-01
            {900000000L,    1606780800L}, // 2020-12-01
            {1000000000L,   1614556800L}, // 2021-03-01
            {1100000000L,   1622505600L}, // 2021-06-01
            {1200000000L,   1630454400L}, // 2021-09-01
            {1300000000L,   1638316800L}, // 2021-12-01
            {1400000000L,   1643673600L}, // 2022-02-01
            {1500000000L,   1651363200L}, // 2022-05-01
            {1600000000L,   1659312000L}, // 2022-08-01
            {1700000000L,   1667260800L}, // 2022-11-01
            {1800000000L,   1672531200L}, // 2023-01-01
            {1900000000L,   1680307200L}, // 2023-04-01
            {2000000000L,   1688169600L}, // 2023-07-01
            // ---- новое 64-битное пространство ----
            {5000000000L,   1664582400L}, // 2022-10-01
            {5500000000L,   1680307200L}, // 2023-04-01
            {6000000000L,   1696118400L}, // 2023-10-01
            {6500000000L,   1709251200L}, // 2024-03-01
            {7000000000L,   1722470400L}, // 2024-08-01
            {7200000000L,   1730419200L}, // 2024-11-01
            {7500000000L,   1740787200L}, // 2025-03-01
            {7800000000L,   1748736000L}, // 2025-06-01
            {8000000000L,   1756684800L}, // 2025-09-01
    };

    /** граница, с которой начинается «новая» шкала id */
    private static final long NEW_SPACE = 5000000000L;

    /** внешние уточнения: реальные пары id -> дата, если мы их где-то узнали */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> known = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Сообщить точно известную дату для конкретного id (например, дату первого
     * сообщения человека — зарегистрировался он заведомо не позже).
     * Оценка после этого никогда не окажется позже этой даты.
     */
    public static void noteNotLaterThan(long userId, long unixtime) {
        if (userId <= 0 || unixtime <= 1376438400L || unixtime > System.currentTimeMillis() / 1000L) {
            return;
        }
        final Long old = known.get(userId);
        if (old == null || unixtime < old) {
            known.put(userId, unixtime);
        }
    }

    /** @return примерный unixtime регистрации или 0, если оценить нельзя */
    public static long estimate(long userId) {
        if (userId <= 0) {
            return 0;
        }
        long value = interpolate(userId);
        if (value <= 0) {
            return 0;
        }
        // факты важнее таблицы: если человек уже писал до нашей оценки — оценка неверна
        final Long upper = known.get(userId);
        if (upper != null && upper < value) {
            value = upper;
        }
        final long now = System.currentTimeMillis() / 1000L;
        return Math.min(value, now);
    }

    /** ядро: кусочно-линейная интерполяция с продолжением за последнюю точку */
    private static long interpolate(long userId) {
        final boolean newSpace = userId >= NEW_SPACE;
        final int from = newSpace ? firstNewIndex() : 0;
        final int to = newSpace ? POINTS.length : firstNewIndex();
        if (to - from < 2) {
            return 0;
        }
        if (userId <= POINTS[from][0]) {
            return POINTS[from][1];
        }
        if (userId >= POINTS[to - 1][0]) {
            // за последней точкой продолжаем тем же темпом, каким шли в конце
            final long[] a = POINTS[to - 2];
            final long[] b = POINTS[to - 1];
            final double speed = (double) (b[1] - a[1]) / (double) (b[0] - a[0]);
            return (long) (b[1] + (userId - b[0]) * speed);
        }
        // двоичный поиск отрезка
        int lo = from, hi = to - 1;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (POINTS[mid][0] <= userId) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        final long[] prev = POINTS[lo];
        final long[] next = POINTS[hi];
        final double k = (double) (userId - prev[0]) / (double) (next[0] - prev[0]);
        return (long) (prev[1] + k * (next[1] - prev[1]));
    }

    private static int newSpaceIndex = -1;

    private static int firstNewIndex() {
        if (newSpaceIndex < 0) {
            int index = POINTS.length;
            for (int a = 0; a < POINTS.length; ++a) {
                if (POINTS[a][0] >= NEW_SPACE) {
                    index = a;
                    break;
                }
            }
            newSpaceIndex = index;
        }
        return newSpaceIndex;
    }

    /**
     * Насколько широк «коридор» вокруг оценки, в днях.
     * Чем дальше друг от друга опорные точки вокруг id, тем честнее показывать
     * только год, а не месяц.
     */
    public static int uncertaintyDays(long userId) {
        if (userId <= 0) {
            return Integer.MAX_VALUE;
        }
        final boolean newSpace = userId >= NEW_SPACE;
        final int from = newSpace ? firstNewIndex() : 0;
        final int to = newSpace ? POINTS.length : firstNewIndex();
        if (to - from < 2) {
            return Integer.MAX_VALUE;
        }
        if (userId >= POINTS[to - 1][0]) {
            // за таблицей точность падает тем сильнее, чем дальше ушли
            return 365;
        }
        int lo = from, hi = to - 1;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (POINTS[mid][0] <= userId) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (int) Math.max(1, (POINTS[hi][1] - POINTS[lo][1]) / (24 * 3600) / 2);
    }

    /** Оценка в виде коридора: {не раньше, не позже}. */
    public static long[] estimateRange(long userId) {
        final long value = estimate(userId);
        if (value <= 0) {
            return null;
        }
        final long half = (long) uncertaintyDays(userId) * 24 * 3600;
        final long now = System.currentTimeMillis() / 1000L;
        return new long[]{Math.max(1376438400L, value - half), Math.min(now, value + half)};
    }

    /**
     * Дата в том виде, за который не стыдно: месяц и год, если точность это позволяет,
     * иначе только год.
     */
    public static String formatSmart(long unixtime, long userId) {
        if (unixtime <= 0) {
            return null;
        }
        return uncertaintyDays(userId) <= 100 ? formatMonthYear(unixtime) : formatYearOnly(unixtime);
    }

    public static String formatYearOnly(long unixtime) {
        if (unixtime <= 0) return null;
        try {
            final Calendar c = Calendar.getInstance();
            c.setTimeInMillis(unixtime * 1000L);
            return String.valueOf(c.get(Calendar.YEAR));
        } catch (Throwable e) {
            return null;
        }
    }

    public static String formatDate(long unixtime) {
        if (unixtime <= 0) return null;
        try {
            return LocaleController.getInstance().getFormatterYear().format(new Date(unixtime * 1000L));
        } catch (Throwable e) {
            return null;
        }
    }

    public static String formatMonthYear(long unixtime) {
        if (unixtime <= 0) return null;
        try {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(unixtime * 1000L);
            String month = LocaleController.getInstance().getFormatterMonthYear().format(c.getTime());
            if (month != null && month.length() > 0) {
                return month.substring(0, 1).toUpperCase(Locale.getDefault()) + month.substring(1);
            }
        } catch (Throwable ignore) {}
        return formatDate(unixtime);
    }

    /** «3 года 2 месяца» */
    public static String formatAge(long unixtime) {
        if (unixtime <= 0) return null;
        long now = System.currentTimeMillis() / 1000L;
        long diff = Math.max(0, now - unixtime);
        int years = (int) (diff / (365L * 24 * 3600));
        int months = (int) ((diff % (365L * 24 * 3600)) / (30L * 24 * 3600));
        StringBuilder sb = new StringBuilder();
        if (years > 0) {
            sb.append(LocaleController.formatPluralString("Years", years));
        }
        if (months > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(LocaleController.formatPluralString("Months", months));
        }
        if (sb.length() == 0) {
            sb.append(LocaleController.formatPluralString("Days", (int) (diff / (24 * 3600))));
        }
        return sb.toString();
    }

    /** человекочитаемое расположение дата-центра: «DC 5, Singapore, SG» */
    public static String formatDc(int dcId) {
        if (dcId <= 0) {
            return null;
        }
        final String city;
        final String country;
        switch (dcId) {
            case 1: city = "Miami"; country = "US"; break;
            case 2: city = "Amsterdam"; country = "NL"; break;
            case 3: city = "Miami"; country = "US"; break;
            case 4: city = "Amsterdam"; country = "NL"; break;
            case 5: city = "Singapore"; country = "SG"; break;
            default: return "DC " + dcId;
        }
        return "DC " + dcId + ", " + city + ", " + country;
    }
}
