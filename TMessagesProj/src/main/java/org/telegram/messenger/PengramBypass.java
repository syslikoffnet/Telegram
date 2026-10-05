package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pengram: обход блокировки одним переключателем.
 *
 * Снаружи — только «включено / выключено». Внутри: пока прямое соединение
 * живое, оно и используется (оно быстрее всех). Как только связь пропала,
 * Pengram замеряет задержку у готовых входов и подключается к самому
 * быстрому живому; если ни один не ответил, пробуется запасная дорога через
 * веб-адреса самого Telegram. Когда блокировка отпускает, приложение само
 * возвращается на прямое соединение.
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
    public static final int MODE_MT = 3;
    public static final int MODE_TGWS = 4;

    private static final String PREFS = "pengram_bypass";
    /** как часто смотрим на состояние связи (одно сравнение числа) */
    private static final long WATCH_INTERVAL = 4000;
    /** столько ждём прямое соединение, прежде чем включать обход */
    private static final long DIRECT_PATIENCE = 2000;
    /** столько ждём, пока ядро подключится через выбранный вход */
    private static final long MODE_PATIENCE = 5500;
    /** пауза после полной неудачи */
    private static final long RETRY_AFTER_FAIL = 20000;
    /** сколько входов замеряем параллельно перед подключением */
    private static final int MEASURE_CANDIDATES = 8;
    /** сколько самых быстрых входов пробуем по очереди */
    private static final int TRY_BEST = 3;
    /** замер задержки входа: дольше ждать нет смысла */
    private static final int MEASURE_TIMEOUT = 1500;
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

    /**
     * Остался один путь, который реально проходит: готовый вход Telegram.
     * Берём не «первый попавшийся», а самый быстрый по живому замеру задержки.
     * Запасная дорога — через веб-адреса самого Telegram.
     */
    private static boolean runLadder() {
        if (tryEntries()) {
            return true;
        }
        return tryWebRoute();
    }

    private static boolean tryEntries() {
        ArrayList<PengramBypassSources.Node> nodes = PengramBypassSources.nodesOfKind("mt");
        if (nodes.isEmpty()) {
            refreshBlocking();
            nodes = PengramBypassSources.nodesOfKind("mt");
        }
        if (nodes.isEmpty()) {
            return false;
        }
        final ArrayList<PengramBypassSources.Node> fastest = fastestFirst(nodes);
        for (int i = 0; i < Math.min(TRY_BEST, fastest.size()); i++) {
            if (!isEnabled()) {
                return false;
            }
            PengramBypassEngine.stop();
            applyMtProxy(fastest.get(i));
            mode = MODE_MT;
            if (waitForConnection()) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryWebRoute() {
        if (!isEnabled()) {
            return false;
        }
        final int port = PengramBypassEngine.start(PengramBypassEngine.ROUTE_TGWS, null);
        if (port == 0) {
            return false;
        }
        applyLocalProxy(port);
        mode = MODE_TGWS;
        return waitForConnection();
    }

    /**
     * Параллельный замер задержки: все кандидаты проверяются одновременно,
     * поэтому выбор самого быстрого стоит примерно полторы секунды, а не минуту.
     */
    private static ArrayList<PengramBypassSources.Node> fastestFirst(ArrayList<PengramBypassSources.Node> nodes) {
        final ArrayList<PengramBypassSources.Node> candidates =
                new ArrayList<>(nodes.subList(0, Math.min(MEASURE_CANDIDATES, nodes.size())));
        final ArrayList<PengramBypassSources.Node> alive = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(candidates.size());
        for (final PengramBypassSources.Node node : candidates) {
            final Thread thread = new Thread(() -> {
                try {
                    final int ms = PengramBypassEngine.probe(node, MEASURE_TIMEOUT);
                    if (ms > 0) {
                        node.ping = ms;
                        synchronized (alive) {
                            alive.add(node);
                        }
                    }
                } catch (Throwable ignore) {
                } finally {
                    latch.countDown();
                }
            }, "pengram-measure");
            thread.setDaemon(true);
            thread.start();
        }
        try {
            latch.await(MEASURE_TIMEOUT + 500L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {
        }
        synchronized (alive) {
            Collections.sort(alive, (a, b) -> Integer.compare(a.ping, b.ping));
            if (!alive.isEmpty()) {
                return new ArrayList<>(alive);
            }
        }
        return candidates;
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

    // ------------------------------------------------------------- для экрана настроек

    /** подпись под состоянием: без технических слов, пользователю они не нужны */
    public static String modeName(int value) {
        return value == MODE_NONE ? "" : LocaleController.getString(R.string.PengramBypassStateProxyInfo);
    }

    /** идёт ли сейчас трафик через обход — нужно для подписи «Переподключение…» */
    public static boolean isTunnelActive() {
        return isEnabled() && tunnelActive;
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

    /** обновление списка входов (вызывается изнутри) */
    public static void refreshNodes(PengramBypassSources.Callback callback) {
        PengramBypassSources.refresh(true, callback);
    }
}
