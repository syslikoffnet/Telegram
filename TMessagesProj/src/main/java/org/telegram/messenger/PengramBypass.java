package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pengram: обход блокировки одной галочкой.
 *
 * Пользователь включает один переключатель — дальше приложение всё делает само
 * и молча. Как только Telegram перестаёт подключаться, Pengram по очереди
 * пробует способы, от самого быстрого к самому пробивному:
 *
 *   1. «Разрыв» — прямое соединение с разрезанным первым пакетом. Никаких
 *      посредников, скорость обычная; снимает блокировку по подписи трафика.
 *   2. «Через сайт» — HTTPS-туннель к сайту на Cloudflare. Снаружи это
 *      обычное открытие сайта, поэтому проходит даже при белом списке адресов.
 *   3. «MTProto» — классический телеграм-вход, если первые два не сработали.
 *
 * Победивший способ запоминается и в следующий раз включается сразу. Когда
 * блокировка отпускает, приложение само возвращается на прямое соединение,
 * потому что оно быстрее.
 */
public final class PengramBypass {

    /** единственная галочка, которую видит пользователь */
    public static final String KEY_ENABLED = "bypassEnabled";

    public static final int STATUS_OFF = 0;
    public static final int STATUS_DIRECT = 1;
    public static final int STATUS_SEARCHING = 2;
    public static final int STATUS_PROXY = 3;
    public static final int STATUS_FAILED = 4;

    public static final int MODE_NONE = 0;
    public static final int MODE_SPLIT = 1;
    public static final int MODE_WS = 2;
    public static final int MODE_MT = 3;

    public static final int ROUTE_AUTO = 0;
    public static final int ROUTE_WS = 1;
    public static final int ROUTE_MT = 2;
    public static final int ROUTE_SPLIT_LEGACY = 3;

    private static final String PREFS = "pengram_bypass";
    /** как часто смотрим на состояние связи (одно сравнение числа) */
    private static final long WATCH_INTERVAL = 8000;
    /** столько ждём прямое соединение, прежде чем включать обход */
    private static final long DIRECT_PATIENCE = 3500;
    /** столько ждём, пока ядро подключится через выбранный способ */
    private static final long MODE_PATIENCE = 10000;
    /** пауза после полной неудачи */
    private static final long RETRY_AFTER_FAIL = 90000;
    /** как часто проверяем, не отпустила ли блокировка */
    private static final long DIRECT_RECHECK = 10L * 60 * 1000;

    private static boolean watching;
    private static volatile int status = STATUS_OFF;
    private static volatile int mode = MODE_NONE;
    private static volatile String details = "";
    private static volatile boolean busy;
    private static volatile boolean tunnelActive;
    private static long lastGoodTime;
    private static long lastFailTime;
    private static long lastDirectCheck;
    private static Runnable listener;

    private PengramBypass() {
    }

    // ------------------------------------------------------------- состояние

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext == null ? null
                : ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean value) {
        PengramConfig.setBool(KEY_ENABLED, value);
        if (value) {
            status = STATUS_SEARCHING;
            details = "";
            lastGoodTime = 0;
            lastFailTime = 0;
            start();
            // Каталоги обновляются параллельно, но включение не ждёт следующего 8-секундного tick.
            if (PengramBypassSources.needRefresh() && ApplicationLoader.isNetworkOnline()) {
                PengramBypassSources.refresh(false, null);
            }
            activateImmediately();
        } else {
            releaseTunnel();
            status = STATUS_OFF;
            mode = MODE_NONE;
            details = "";
            notifyChanged();
        }
    }

    public static int getStatus() {
        return isEnabled() ? status : STATUS_OFF;
    }

    public static int getMode() {
        return mode;
    }

    public static String getStatusDetails() {
        return details == null ? "" : details;
    }

    public static boolean isBusy() {
        return busy || PengramBypassSources.isBusy();
    }

    public static int savedCount() {
        return PengramBypassSources.savedCount();
    }

    public static void setListener(Runnable value) {
        listener = value;
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            final Runnable l = listener;
            if (l != null) {
                l.run();
            }
        });
    }

    private static void setState(int newStatus, String newDetails) {
        status = newStatus;
        details = newDetails == null ? "" : newDetails;
        notifyChanged();
    }

    // ------------------------------------------------------------- наблюдатель

    public static void start() {
        if (watching) {
            return;
        }
        watching = true;
        if (isEnabled() && PengramBypassSources.needRefresh() && ApplicationLoader.isNetworkOnline()) {
            // Каталоги загружаются заранее параллельно ожиданию прямого соединения.
            PengramBypassSources.refresh(false, null);
        }
        if (isEnabled()) {
            AndroidUtilities.runOnUIThread(PengramBypass::activateImmediately, 50);
        }
        AndroidUtilities.runOnUIThread(PengramBypass::tick, isEnabled() ? 500 : 3000);
    }

    private static void tick() {
        try {
            check();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        AndroidUtilities.runOnUIThread(PengramBypass::tick, WATCH_INTERVAL);
    }

    /**
     * Немедленная реакция на нажатие переключателя. Если ядро уже подключено, сохраняем
     * прямой маршрут. В противном случае запускаем запомненный рабочий способ сразу,
     * не добавляя DIRECT_PATIENCE и WATCH_INTERVAL к ожиданию пользователя.
     */
    private static void activateImmediately() {
        if (!isEnabled()) return;
        if (!ApplicationLoader.isNetworkOnline()) {
            setState(STATUS_SEARCHING, LocaleController.getString(R.string.PengramBypassNoNetwork));
            return;
        }
        if (isCoreConnected()) {
            lastGoodTime = System.currentTimeMillis();
            setState(STATUS_DIRECT, LocaleController.getString(R.string.PengramBypassStateDirectInfo));
            return;
        }
        // Явно будим сетевое ядро: после выключения VPN Android не всегда присылает
        // Telegram новый network callback достаточно быстро.
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager.getInstance(a).checkConnection();
            }
        }
        AndroidUtilities.runOnUIThread(PengramBypass::escalate, 50);
    }

    private static void check() {
        if (!isEnabled()) {
            if (tunnelActive) {
                releaseTunnel();
            }
            status = STATUS_OFF;
            return;
        }
        if (busy) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!ApplicationLoader.isNetworkOnline()) {
            setState(STATUS_SEARCHING, LocaleController.getString(R.string.PengramBypassNoNetwork));
            lastGoodTime = now;
            return;
        }
        if (!tunnelActive && foreignProxyEnabled()) {
            setState(STATUS_DIRECT, LocaleController.getString(R.string.PengramBypassOwnProxy));
            lastGoodTime = now;
            return;
        }
        if (isCoreConnected()) {
            lastGoodTime = now;
            if (tunnelActive) {
                setState(STATUS_PROXY, modeName(mode));
                if (now - lastDirectCheck > DIRECT_RECHECK) {
                    lastDirectCheck = now;
                    probeDirectAndRelease();
                }
            } else {
                setState(STATUS_DIRECT, LocaleController.getString(R.string.PengramBypassStateDirectInfo));
            }
            return;
        }
        if (lastGoodTime == 0) {
            lastGoodTime = now;
            return;
        }
        if (now - lastGoodTime < DIRECT_PATIENCE) {
            return;
        }
        if (status == STATUS_FAILED && now - lastFailTime < RETRY_AFTER_FAIL) {
            return;
        }
        escalate();
    }

    private static boolean isCoreConnected() {
        boolean sawAccount = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            sawAccount = true;
            if (isConnectedState(ConnectionsManager.getInstance(a).getConnectionState())) {
                return true;
            }
        }
        if (!sawAccount) {
            final int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
            return isConnectedState(state);
        }
        return false;
    }

    private static boolean isConnectedState(int state) {
        return state == ConnectionsManager.ConnectionStateConnected
                || state == ConnectionsManager.ConnectionStateUpdating;
    }

    /** прокси, который настроил сам пользователь, трогать нельзя */
    private static boolean foreignProxyEnabled() {
        try {
            return SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------- подбор способа

    private static void escalate() {
        if (busy) {
            return;
        }
        busy = true;
        setState(STATUS_SEARCHING, LocaleController.getString(R.string.PengramBypassSearching));
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = runLadder();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            busy = false;
            if (ok) {
                lastGoodTime = System.currentTimeMillis();
                lastDirectCheck = System.currentTimeMillis();
                setState(STATUS_PROXY, modeName(mode));
            } else {
                lastFailTime = System.currentTimeMillis();
                lastGoodTime = System.currentTimeMillis();
                mode = MODE_NONE;
                releaseTunnel();
                setState(STATUS_FAILED, LocaleController.getString(R.string.PengramBypassStateFailedInfo));
            }
        }, "pengram-bypass-ladder").start();
    }

    public static int getPreferredRoute() {
        final SharedPreferences p = prefs();
        final int value = p == null ? ROUTE_AUTO : p.getInt("preferred_route", ROUTE_AUTO);
        return value < ROUTE_AUTO || value > ROUTE_SPLIT_LEGACY ? ROUTE_AUTO : value;
    }

    public static void setPreferredRoute(int value) {
        value = Math.max(ROUTE_AUTO, Math.min(ROUTE_SPLIT_LEGACY, value));
        final SharedPreferences p = prefs();
        if (p != null) p.edit().putInt("preferred_route", value).apply();
        if (isEnabled()) retryNow();
    }

    private static boolean runLadder() {
        final ArrayList<Integer> order = new ArrayList<>();
        final int preferred = getPreferredRoute();
        if (preferred != ROUTE_AUTO) {
            final int selected = preferred == ROUTE_WS ? MODE_WS
                    : preferred == ROUTE_MT ? MODE_MT : MODE_SPLIT;
            return tryMode(selected);
        }
        final int remembered = rememberedMode();
        if (remembered != MODE_NONE) {
            order.add(remembered);
        }
        // Fragmentation-only is no longer useful against modern stateful DPI.
        // Auto uses it neither as a first attempt nor as a fallback; it remains manual for legacy networks.
        // Native Fake-TLS MTProto on 443 is the cheapest and most stable first choice;
        // the HTTPS/WebSocket VLESS tunnel remains a stronger fallback for IP filtering.
        for (int candidate : new int[]{MODE_MT, MODE_WS}) {
            if (!order.contains(candidate)) order.add(candidate);
        }
        order.remove((Integer) MODE_SPLIT);
        for (int candidate : order) {
            if (!isEnabled()) {
                return false;
            }
            if (tryMode(candidate)) {
                remember(candidate);
                return true;
            }
        }
        return false;
    }

    private static boolean tryMode(int candidate) {
        if (candidate == MODE_SPLIT) {
            setState(STATUS_SEARCHING, LocaleController.getString(R.string.PengramBypassModeSplit));
            final int port = PengramBypassEngine.start(PengramBypassEngine.ROUTE_SPLIT, null);
            if (port == 0) {
                return false;
            }
            applyLocalProxy(port);
            mode = MODE_SPLIT;
            return waitForConnection();
        }
        if (candidate == MODE_WS) {
            setState(STATUS_SEARCHING, LocaleController.getString(R.string.PengramBypassModeWs));
            ArrayList<PengramBypassSources.Node> nodes = PengramBypassSources.nodesOfKind("ws");
            if (nodes.isEmpty() || PengramBypassSources.needRefresh()) {
                refreshBlocking();
                nodes = PengramBypassSources.nodesOfKind("ws");
            }
            for (int i = 0; i < Math.min(4, nodes.size()); i++) {
                if (!isEnabled()) {
                    return false;
                }
                final int port = PengramBypassEngine.start(PengramBypassEngine.ROUTE_WS, nodes.get(i));
                if (port == 0) {
                    continue;
                }
                applyLocalProxy(port);
                mode = MODE_WS;
                if (waitForConnection()) {
                    return true;
                }
            }
            return false;
        }
        if (candidate == MODE_MT) {
            setState(STATUS_SEARCHING, LocaleController.getString(R.string.PengramBypassModeMt));
            ArrayList<PengramBypassSources.Node> nodes = PengramBypassSources.nodesOfKind("mt");
            if (nodes.isEmpty()) {
                refreshBlocking();
                nodes = PengramBypassSources.nodesOfKind("mt");
            }
            for (int i = 0; i < Math.min(4, nodes.size()); i++) {
                if (!isEnabled()) {
                    return false;
                }
                PengramBypassEngine.stop();
                applyMtProxy(nodes.get(i));
                mode = MODE_MT;
                if (waitForConnection()) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static void refreshBlocking() {
        final CountDownLatch latch = new CountDownLatch(1);
        if (PengramBypassSources.isBusy()) {
            final long deadline = System.currentTimeMillis() + 30000;
            while (PengramBypassSources.isBusy() && System.currentTimeMillis() < deadline && isEnabled()) {
                try { Thread.sleep(250); } catch (Throwable ignore) { return; }
            }
            return;
        }
        PengramBypassSources.refresh(true, (alive, total) -> latch.countDown());
        try {
            latch.await(35, TimeUnit.SECONDS);
        } catch (Throwable ignore) {
        }
    }

    private static boolean waitForConnection() {
        final long deadline = System.currentTimeMillis() + MODE_PATIENCE;
        while (System.currentTimeMillis() < deadline) {
            if (isCoreConnected()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (Throwable ignore) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- управление прокси ядра

    private static void applyLocalProxy(int port) {
        final ProxySettings settings = ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress("127.0.0.1")
                .setPort(port)
                .build();
        tunnelActive = true;
        applySettings(true, settings);
    }

    private static void applyMtProxy(PengramBypassSources.Node node) {
        final ProxySettings settings = ProxySettings.builder()
                .setType(ProxySettings.Type.MTPROTO)
                .setAddress(node.host)
                .setPort(node.port)
                .setSecret(node.secret)
                .build();
        tunnelActive = true;
        applySettings(true, settings);
    }

    /** возврат на прямое соединение; настройки пользователя при этом восстанавливаются */
    private static void releaseTunnel() {
        PengramBypassEngine.stop();
        if (!tunnelActive) {
            return;
        }
        tunnelActive = false;
        mode = MODE_NONE;
        try {
            if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null) {
                applySettings(true, SharedConfig.currentProxy.settings);
                return;
            }
        } catch (Throwable ignore) {
        }
        applySettings(false, ProxySettings.EMPTY);
    }

    private static void applySettings(boolean enabled, ProxySettings settings) {
        final CountDownLatch latch = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ConnectionsManager.setProxySettings(enabled, settings);
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        ConnectionsManager.getInstance(a).checkConnection();
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            latch.countDown();
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (Throwable ignore) {
        }
    }

    private static void probeDirectAndRelease() {
        new Thread(() -> {
            if (PengramBypassEngine.directReachable(2500)) {
                releaseTunnel();
                setState(STATUS_DIRECT, LocaleController.getString(R.string.PengramBypassStateDirectInfo));
            }
        }, "pengram-bypass-direct").start();
    }

    // ------------------------------------------------------------- память о победителе

    private static int rememberedMode() {
        final SharedPreferences p = prefs();
        return p == null ? MODE_NONE : p.getInt("winner", MODE_NONE);
    }

    private static void remember(int value) {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putInt("winner", value).putLong("winner_time", System.currentTimeMillis()).apply();
        }
    }

    // ------------------------------------------------------------- для экрана настроек

    public static String modeName(int value) {
        switch (value) {
            case MODE_SPLIT:
                return LocaleController.getString(R.string.PengramBypassModeSplit);
            case MODE_WS:
                return LocaleController.getString(R.string.PengramBypassModeWs);
            case MODE_MT:
                return LocaleController.getString(R.string.PengramBypassModeMt);
            default:
                return "";
        }
    }

    /** кнопка «проверить сейчас» на экране обхода */
    public static void retryNow() {
        if (!isEnabled()) {
            return;
        }
        lastFailTime = 0;
        lastGoodTime = 0;
        releaseTunnel();
        AndroidUtilities.runOnUIThread(PengramBypass::escalate, 200);
    }

    /** ручное добавление своей ссылки: vless:// или tg://proxy */
    public static boolean addFromLink(String link) {
        final PengramBypassSources.Node node = PengramBypassSources.parseLink(link);
        if (node == null) {
            return false;
        }
        return PengramBypassSources.addManual(node);
    }

    /** обновление списка входов руками */
    public static void refreshNodes(PengramBypassSources.Callback callback) {
        PengramBypassSources.refresh(true, callback);
    }
}
