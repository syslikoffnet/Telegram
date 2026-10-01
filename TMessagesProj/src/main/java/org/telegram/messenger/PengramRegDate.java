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

    // опорные точки: {id, unixtime}
    private static final long[][] POINTS = {
            {1L,            1376438400L}, // 2013-08-14
            {1000000L,      1383264000L}, // 2013-11-01
            {5000000L,      1388534400L}, // 2014-01-01
            {10000000L,     1393632000L}, // 2014-03-01
            {50000000L,     1417392000L}, // 2014-12-01
            {100000000L,    1451606400L}, // 2016-01-01
            {150000000L,    1485907200L}, // 2017-02-01
            {200000000L,    1498867200L}, // 2017-07-01
            {250000000L,    1506816000L}, // 2017-10-01
            {300000000L,    1514764800L}, // 2018-01-01
            {400000000L,    1530403200L}, // 2018-07-01
            {500000000L,    1546300800L}, // 2019-01-01
            {600000000L,    1561939200L}, // 2019-07-01
            {700000000L,    1577836800L}, // 2020-01-01
            {800000000L,    1593561600L}, // 2020-07-01
            {900000000L,    1606780800L}, // 2020-12-01
            {1000000000L,   1614556800L}, // 2021-03-01
            {1200000000L,   1630454400L}, // 2021-09-01
            {1300000000L,   1638316800L}, // 2021-12-01
            {1400000000L,   1643673600L}, // 2022-02-01
            {1500000000L,   1651363200L}, // 2022-05-01
            {1600000000L,   1659312000L}, // 2022-08-01
            {1700000000L,   1667260800L}, // 2022-11-01
            {1800000000L,   1672531200L}, // 2023-01-01
            {1900000000L,   1680307200L}, // 2023-04-01
            {2000000000L,   1688169600L}, // 2023-07-01
            {5000000000L,   1664582400L}, // 2022-10-01 (новое 64-битное пространство)
            {6000000000L,   1696118400L}, // 2023-10-01
            {7000000000L,   1722470400L}, // 2024-08-01
            {7500000000L,   1740787200L}, // 2025-03-01
            {8000000000L,   1756684800L}, // 2025-09-01
    };

    /** @return примерный unixtime регистрации или 0, если оценить нельзя */
    public static long estimate(long userId) {
        if (userId <= 0) {
            return 0;
        }
        // 64-битное пространство идёт отдельной шкалой
        final boolean newSpace = userId >= 5000000000L;
        long[] prev = null, next = null;
        for (long[] point : POINTS) {
            final boolean pointNew = point[0] >= 5000000000L;
            if (pointNew != newSpace) {
                continue;
            }
            if (point[0] <= userId) {
                prev = point;
            } else if (next == null) {
                next = point;
            }
        }
        if (prev == null && next == null) {
            return 0;
        }
        if (prev == null) {
            return next[1];
        }
        if (next == null) {
            return prev[1];
        }
        final double k = (double) (userId - prev[0]) / (double) (next[0] - prev[0]);
        return (long) (prev[1] + k * (next[1] - prev[1]));
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
