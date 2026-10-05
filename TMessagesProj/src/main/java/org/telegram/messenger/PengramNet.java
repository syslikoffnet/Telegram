package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Pengram: обход блокировок на уровне собственных сокетов.
 *
 * <p>Ни VPN, ни прокси: мы просто иначе отдаём ядру первые байты соединения.
 * DPI ищет сигнатуру Telegram в первом TCP-сегменте (64 байта obfuscated2 или
 * TLS ClientHello при FakeTLS-прокси). Если те же байты уедут несколькими
 * сегментами, с паузами и вразнобой — сигнатура не собирается, а сервер
 * получает ровно тот же поток и ничего не замечает.
 *
 * <p>Честная граница возможностей: это помогает против <b>DPI</b>. Против
 * <b>белых списков</b> (когда разрешены только определённые IP) не поможет
 * ничто из того, что живёт внутри приложения — нужен адрес из разрешённых.
 */
public class PengramNet {

    // ------------------------------------------------------------- профили

    public static final int PROFILE_OFF = 0;
    public static final int PROFILE_SPLIT2 = 1;
    public static final int PROFILE_SPLIT_MTPROTO = 2;
    public static final int PROFILE_MULTISPLIT = 3;
    public static final int PROFILE_PACED = 4;
    public static final int PROFILE_MOBILE = 5;
    public static final int PROFILE_HARD = 6;
    public static final int PROFILE_OOB = 7;
    public static final int PROFILE_FAKE = 8;
    public static final int PROFILE_CUSTOM = 9;
    public static final int PROFILE_COUNT = 10;

    /** один набор параметров: то, что реально уходит в нативный движок */
    public static class Strategy {
        public int firstPackets = 1;
        public int split1, split2, split3;
        public boolean randomSplit;
        public int delayMs;
        public boolean oob;
        public boolean noDelay = true;
        public boolean fake;
        public int fakeTtl = 3;

        Strategy(int firstPackets, int s1, int s2, int s3, boolean random, int delay, boolean oob, boolean fake, int ttl) {
            this.firstPackets = firstPackets;
            this.split1 = s1;
            this.split2 = s2;
            this.split3 = s3;
            this.randomSplit = random;
            this.delayMs = delay;
            this.oob = oob;
            this.fake = fake;
            this.fakeTtl = ttl;
        }
    }

    /**
     * Готовые профили. Позиции разреза взяты из практики zapret/ByeDPI:
     * 2 байта — классический разрез TLS-заголовка, 8/16 — внутри
     * obfuscated2-инициализации Telegram, отрицательные — отступ с конца.
     */
    public static Strategy strategyOf(int profile) {
        switch (profile) {
            case PROFILE_SPLIT2:
                return new Strategy(1, 2, 0, 0, false, 0, false, false, 3);
            case PROFILE_SPLIT_MTPROTO:
                return new Strategy(1, 8, 0, 0, false, 0, false, false, 3);
            case PROFILE_MULTISPLIT:
                return new Strategy(1, 2, 8, 32, false, 0, false, false, 3);
            case PROFILE_PACED:
                return new Strategy(1, 2, 16, 0, false, 25, false, false, 3);
            case PROFILE_MOBILE:
                return new Strategy(2, 2, 16, 48, true, 10, false, false, 3);
            case PROFILE_HARD:
                return new Strategy(3, 1, 4, 16, true, 40, false, false, 3);
            case PROFILE_OOB:
                return new Strategy(1, 2, 16, 0, false, 5, true, false, 3);
            case PROFILE_FAKE:
                return new Strategy(1, 2, 0, 0, false, 0, false, true, 3);
            case PROFILE_CUSTOM:
                return custom();
            case PROFILE_OFF:
            default:
                return new Strategy(1, 0, 0, 0, false, 0, false, false, 3);
        }
    }

    // ------------------------------------------------------------ настройки

    public static final String KEY_ENABLED = "netBypass";
    private static final String KEY_PROFILE = "netProfile";
    private static final String KEY_SPLIT1 = "netSplit1";
    private static final String KEY_SPLIT2 = "netSplit2";
    private static final String KEY_SPLIT3 = "netSplit3";
    private static final String KEY_RANDOM = "netSplitRandom";
    private static final String KEY_DELAY = "netDelay";
    private static final String KEY_OOB = "netOob";
    private static final String KEY_NODELAY = "netNoDelay";
    private static final String KEY_FAKE = "netFake";
    private static final String KEY_FAKE_TTL = "netFakeTtl";
    private static final String KEY_FIRST = "netFirstPackets";

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        PengramConfig.setBool(KEY_ENABLED, enabled);
        applyAndReconnect();
    }

    public static int getProfile() {
        final int value = PengramConfig.getIntCached(KEY_PROFILE, PROFILE_MOBILE);
        return value < 0 || value >= PROFILE_COUNT ? PROFILE_MOBILE : value;
    }

    public static void setProfile(int profile) {
        PengramConfig.setIntValue(KEY_PROFILE, profile < 0 || profile >= PROFILE_COUNT ? PROFILE_MOBILE : profile);
        applyAndReconnect();
    }

    /** свой набор: отдельные ключи, чтобы переключение профилей их не затирало */
    public static Strategy custom() {
        final Strategy s = new Strategy(
                PengramConfig.getIntCached(KEY_FIRST, 1),
                PengramConfig.getIntCached(KEY_SPLIT1, 2),
                PengramConfig.getIntCached(KEY_SPLIT2, 0),
                PengramConfig.getIntCached(KEY_SPLIT3, 0),
                PengramConfig.getBool(KEY_RANDOM, false),
                PengramConfig.getIntCached(KEY_DELAY, 0),
                PengramConfig.getBool(KEY_OOB, false),
                PengramConfig.getBool(KEY_FAKE, false),
                PengramConfig.getIntCached(KEY_FAKE_TTL, 3));
        s.noDelay = PengramConfig.getBool(KEY_NODELAY, true);
        return s;
    }

    public static void setCustomSplit(int index, int value) {
        PengramConfig.setIntValue(index == 0 ? KEY_SPLIT1 : index == 1 ? KEY_SPLIT2 : KEY_SPLIT3, value);
        apply();
    }

    public static int getCustomSplit(int index) {
        return index == 0 ? PengramConfig.getIntCached(KEY_SPLIT1, 2)
                : index == 1 ? PengramConfig.getIntCached(KEY_SPLIT2, 0)
                : PengramConfig.getIntCached(KEY_SPLIT3, 0);
    }

    public static int getCustomDelay() { return PengramConfig.getIntCached(KEY_DELAY, 0); }
    public static void setCustomDelay(int value) { PengramConfig.setIntValue(KEY_DELAY, value); apply(); }

    public static int getCustomFirstPackets() { return Math.max(1, Math.min(8, PengramConfig.getIntCached(KEY_FIRST, 1))); }
    public static void setCustomFirstPackets(int value) { PengramConfig.setIntValue(KEY_FIRST, value); apply(); }

    public static int getCustomFakeTtl() { return Math.max(1, Math.min(16, PengramConfig.getIntCached(KEY_FAKE_TTL, 3))); }
    public static void setCustomFakeTtl(int value) { PengramConfig.setIntValue(KEY_FAKE_TTL, value); apply(); }

    public static boolean isCustomRandom() { return PengramConfig.getBool(KEY_RANDOM, false); }
    public static boolean isCustomOob() { return PengramConfig.getBool(KEY_OOB, false); }
    public static boolean isCustomNoDelay() { return PengramConfig.getBool(KEY_NODELAY, true); }
    public static boolean isCustomFake() { return PengramConfig.getBool(KEY_FAKE, false); }

    public static void toggleCustomFlag(String key) {
        PengramConfig.setBool(key, !PengramConfig.getBool(key, key.equals(KEY_NODELAY)));
        apply();
    }

    public static final String FLAG_RANDOM = KEY_RANDOM;
    public static final String FLAG_OOB = KEY_OOB;
    public static final String FLAG_NODELAY = KEY_NODELAY;
    public static final String FLAG_FAKE = KEY_FAKE;

    // -------------------------------------------------------------- вывод

    /** короткое описание текущей стратегии для подписи в настройках */
    public static String describe() {
        if (!isEnabled()) {
            return null;
        }
        final Strategy s = strategyOf(getProfile());
        final StringBuilder sb = new StringBuilder();
        if (s.split1 != 0 || s.split2 != 0 || s.split3 != 0) {
            sb.append("split ");
            boolean first = true;
            final int[] all = {s.split1, s.split2, s.split3};
            for (int value : all) {
                if (value == 0) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                sb.append(value);
                first = false;
            }
        }
        if (s.delayMs > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s.delayMs).append(" ms");
        }
        if (s.randomSplit) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("random");
        }
        if (s.oob) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("oob");
        }
        if (s.fake) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("fake ttl ").append(s.fakeTtl);
        }
        if (s.firstPackets > 1) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("x").append(s.firstPackets);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 0 — ещё не пробовали, 1 — фейковые сегменты работают, -1 — нет прав */
    public static int fakeSupport() {
        try {
            return ConnectionsManager.native_pengramDesyncFakeSupport();
        } catch (Throwable e) {
            return -1;
        }
    }

    // --------------------------------------------------------- IP-протокол

    public static final int IP_AUTO = 0;
    public static final int IP_V4 = 1;
    public static final int IP_V6 = 2;
    public static final int IP_BOTH = 3;

    public static int getIpStrategy() {
        final int value = PengramConfig.getIntCached("netIpStrategy", IP_AUTO);
        return value < IP_AUTO || value > IP_BOTH ? IP_AUTO : value;
    }

    public static void setIpStrategy(int value) {
        PengramConfig.setIntValue("netIpStrategy", value);
        reconnect();
    }

    // ------------------------------------------------------- точечная подача

    /**
     * Отдать движку конкретную стратегию, не трогая сохранённые настройки.
     * Нужно автоподбору: он гоняет профили один за другим.
     */
    public static void applyStrategy(Strategy s, boolean enabled) {
        try {
            ConnectionsManager.native_pengramSetDesync(
                    enabled && (s.split1 != 0 || s.split2 != 0 || s.split3 != 0 || s.fake),
                    s.firstPackets, s.split1, s.split2, s.split3, s.randomSplit,
                    s.delayMs, s.oob, 'a', s.noDelay, s.fake, s.fakeTtl);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Уронить живые сокеты, чтобы следующая попытка пошла с новой стратегией:
     * она действует только на первые пакеты соединения.
     */
    public static void dropConnections() {
        try {
            final int type = ApplicationLoader.getCurrentNetworkType();
            final boolean slow = ApplicationLoader.isConnectionSlow();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; ++a) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                ConnectionsManager.native_setNetworkAvailable(a, false, type, slow);
            }
            Thread.sleep(120);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; ++a) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                ConnectionsManager.native_setNetworkAvailable(a, true, type, slow);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Переподключиться, чтобы новая стратегия применилась сразу: она действует
     * на первые пакеты соединения, а значит на уже открытых её не увидеть.
     */
    private static void reconnect() {
        try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; ++a) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    ConnectionsManager.getInstance(a).checkConnection();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** отдать текущие настройки нативному движку; вызывать после любого изменения */
    public static void apply() {
        try {
            // Разрез пакетов убран из интерфейса: современные фильтры его узнают,
            // пользы от него нет. Один раз гасим у тех, кто успел его включить.
            if (!PengramConfig.getBool("netBypassRetired", false)) {
                PengramConfig.setBool("netBypassRetired", true);
                PengramConfig.setBool(KEY_ENABLED, false);
            }
            final boolean enabled = isEnabled();
            final Strategy s = enabled ? strategyOf(getProfile()) : strategyOf(PROFILE_OFF);
            ConnectionsManager.native_pengramSetDesync(
                    enabled && (s.split1 != 0 || s.split2 != 0 || s.split3 != 0 || s.fake),
                    s.firstPackets, s.split1, s.split2, s.split3, s.randomSplit,
                    s.delayMs, s.oob, 'a', s.noDelay, s.fake, s.fakeTtl);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** то же, что apply(), но ещё и роняет текущие соединения — для экрана настроек */
    public static void applyAndReconnect() {
        apply();
        reconnect();
    }
}
