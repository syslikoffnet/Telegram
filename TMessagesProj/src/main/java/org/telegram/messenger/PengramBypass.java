package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pengram: обход блокировки Telegram без VPN и без своего сервера.
 *
 * Как это работает. Приложение держит свежий список публичных MTProto-входов
 * (их отдают открытые каталоги и телеграм-каналы), само проверяет каждый на скорость
 * и, если прямое соединение не поднимается, молча переключается на самый быстрый живой.
 * Предпочтение отдаётся входам с маскировкой под обычный HTTPS (секрет на «ee»):
 * для оператора такой трафик выглядит как открытие сайта.
 *
 * Нагрузки нет: проверка состояния — раз в 15 секунд и только сравнение числа,
 * список обновляется не чаще раза в сутки и только когда это реально нужно.
 */
public class PengramBypass {

    /** обход выключен по умолчанию — включает сам пользователь */
    public static final String KEY_ENABLED = "bypassEnabled";
    /** включать прокси автоматически, когда прямое соединение не поднимается */
    public static final String KEY_AUTO = "bypassAuto";
    /** возвращаться на прямое соединение, когда блокировка отпустила */
    public static final String KEY_BACK_TO_DIRECT = "bypassBackToDirect";

    public static final int STATUS_OFF = 0;
    public static final int STATUS_DIRECT = 1;
    public static final int STATUS_SEARCHING = 2;
    public static final int STATUS_PROXY = 3;
    public static final int STATUS_FAILED = 4;

    private static final String PREFS = "pengram_bypass";
    private static final long LIST_TTL = 24L * 60 * 60 * 1000;
    private static final long WATCH_INTERVAL = 15000;
    /** столько ждём прямое соединение, прежде чем лезть в обход */
    private static final long DIRECT_PATIENCE = 20000;

    /** открытые каталоги входов: если один недоступен, спросим следующий */
    private static final String[] SOURCES = new String[]{
            "https://mtpro.xyz/api/?type=mtproto",
            "https://t.me/s/MTProtoProxies",
            "https://t.me/s/mtproxy_tg",
            "https://raw.githubusercontent.com/hookzof/socks5_list/master/tg/mtproto.json",
    };

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "server=([A-Za-z0-9._\\-]+)(?:&|&amp;)port=(\\d{2,5})(?:&|&amp;)secret=([A-Za-z0-9_\\-=+%]{16,})");
    private static final Pattern HOST_PATTERN = Pattern.compile("\"(?:host|ip|server)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*\"?(\\d{2,5})\"?");
    private static final Pattern SECRET_PATTERN = Pattern.compile("\"secret\"\\s*:\\s*\"([A-Za-z0-9_\\-=+]{16,})\"");

    private static ExecutorService pool;
    private static boolean watching;
    private static long directBadSince;
    private static int status = STATUS_OFF;
    private static String statusDetails = "";
    private static boolean busy;

    public interface Callback {
        void onResult(int alive, int total);
    }

    // ------------------------------------------------------------- настройки

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext == null ? null
                : ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, false);
    }

    public static boolean isAuto() {
        return PengramConfig.getBool(KEY_AUTO, true);
    }

    public static boolean isBackToDirect() {
        return PengramConfig.getBool(KEY_BACK_TO_DIRECT, true);
    }

    public static int getStatus() {
        if (!isEnabled()) {
            return STATUS_OFF;
        }
        return status;
    }

    public static String getStatusDetails() {
        return statusDetails == null ? "" : statusDetails;
    }

    public static boolean isBusy() {
        return busy;
    }

    public static int savedCount() {
        final SharedPreferences p = prefs();
        return p == null ? 0 : p.getInt("count", 0);
    }

    /** включить или выключить обход целиком */
    public static void setEnabled(boolean enabled) {
        PengramConfig.setBool(KEY_ENABLED, enabled);
        if (enabled) {
            status = STATUS_DIRECT;
            start();
        } else {
            status = STATUS_OFF;
            directBadSince = 0;
            disableProxy();
        }
    }

    // ------------------------------------------------------------- наблюдение

    /** запускается один раз при старте приложения */
    public static void start() {
        if (watching || !isEnabled()) {
            return;
        }
        watching = true;
        AndroidUtilities.runOnUIThread(watchRunnable, WATCH_INTERVAL);
    }

    private static final Runnable watchRunnable = new Runnable() {
        @Override
        public void run() {
            watching = false;
            try {
                tick();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (isEnabled()) {
                watching = true;
                AndroidUtilities.runOnUIThread(this, WATCH_INTERVAL);
            }
        }
    };

    /** одна проверка: дешёвая, это просто сравнение состояния соединения */
    private static void tick() {
        if (!isEnabled() || !isAuto()) {
            return;
        }
        if (!ApplicationLoader.isNetworkOnline()) {
            directBadSince = 0;
            return;
        }
        final int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        final boolean connected = state == ConnectionsManager.ConnectionStateConnected;
        final boolean onOurProxy = SharedConfig.isProxyEnabled() && isOurProxy(SharedConfig.currentProxy);

        if (connected) {
            directBadSince = 0;
            status = onOurProxy ? STATUS_PROXY : STATUS_DIRECT;
            if (onOurProxy && isBackToDirect()) {
                maybeReturnToDirect();
            }
            return;
        }
        if (onOurProxy) {
            // наш вход тоже не тянет — пробуем следующий по скорости
            if (directBadSince == 0) {
                directBadSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - directBadSince > DIRECT_PATIENCE) {
                directBadSince = 0;
                useNext();
            }
            return;
        }
        if (directBadSince == 0) {
            directBadSince = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - directBadSince < DIRECT_PATIENCE) {
            return;
        }
        directBadSince = 0;
        status = STATUS_SEARCHING;
        final SharedPreferences p = prefs();
        final long updated = p == null ? 0 : p.getLong("updated", 0);
        if (p == null || p.getInt("count", 0) == 0 || System.currentTimeMillis() - updated > LIST_TTL) {
            refresh((alive, total) -> useBest());
        } else {
            useBest();
        }
    }

    /**
     * Раз в десять минут тихо проверяем, не отпустила ли блокировка:
     * одно TCP-подключение к адресу Telegram, и если оно проходит — возвращаемся напрямую.
     */
    private static long lastDirectProbe;

    private static void maybeReturnToDirect() {
        final long now = System.currentTimeMillis();
        if (now - lastDirectProbe < 10L * 60 * 1000) {
            return;
        }
        lastDirectProbe = now;
        pool().submit(() -> {
            boolean direct = false;
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress("149.154.167.50", 443), 2500);
                direct = true;
            } catch (Throwable e) {
                direct = false;
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
            if (direct) {
                AndroidUtilities.runOnUIThread(PengramBypass::disableProxy);
            }
        });
    }

    // ------------------------------------------------------------- список входов

    private static ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(6, r -> {
                final Thread thread = new Thread(r, "pengram-bypass");
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.setDaemon(true);
                return thread;
            });
        }
        return pool;
    }

    /** скачать свежий список входов и проверить каждый на скорость */
    public static void refresh(Callback callback) {
        if (busy) {
            return;
        }
        busy = true;
        pool().submit(() -> {
            int alive = 0;
            int total = 0;
            try {
                final ArrayList<Entry> entries = new ArrayList<>();
                final HashSet<String> seen = new HashSet<>();
                for (String source : SOURCES) {
                    final String body = download(source);
                    if (TextUtils.isEmpty(body)) {
                        continue;
                    }
                    parse(body, entries, seen);
                    if (entries.size() >= 60) {
                        break;
                    }
                }
                total = entries.size();
                alive = measure(entries);
                save(entries);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            busy = false;
            final int resultAlive = alive;
            final int resultTotal = total;
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null) {
                    callback.onResult(resultAlive, resultTotal);
                }
            });
        });
    }

    private static String download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
            if (connection.getResponseCode() / 100 != 2) {
                return null;
            }
            final StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && builder.length() < 400000) {
                    builder.append(line).append('\n');
                }
            }
            return builder.toString();
        } catch (Throwable e) {
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** из любого текста достаём всё, что похоже на вход: и ссылки, и JSON */
    private static void parse(String body, ArrayList<Entry> out, HashSet<String> seen) {
        final Matcher links = LINK_PATTERN.matcher(body);
        while (links.find()) {
            add(out, seen, links.group(1), links.group(2), links.group(3));
        }
        int from = 0;
        while (true) {
            final int open = body.indexOf('{', from);
            if (open < 0) {
                break;
            }
            final int close = body.indexOf('}', open);
            if (close < 0) {
                break;
            }
            final String block = body.substring(open, close);
            from = close + 1;
            final Matcher host = HOST_PATTERN.matcher(block);
            final Matcher port = PORT_PATTERN.matcher(block);
            final Matcher secret = SECRET_PATTERN.matcher(block);
            if (host.find() && port.find() && secret.find()) {
                add(out, seen, host.group(1), port.group(1), secret.group(1));
            }
        }
    }

    private static void add(ArrayList<Entry> out, HashSet<String> seen, String host, String port, String secret) {
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(port) || TextUtils.isEmpty(secret)) {
            return;
        }
        secret = secret.replace("%3D", "").replace("=", "");
        final int portValue;
        try {
            portValue = Integer.parseInt(port);
        } catch (Throwable e) {
            return;
        }
        if (portValue <= 0 || portValue > 65535) {
            return;
        }
        final String key = host + ":" + portValue;
        if (!seen.add(key)) {
            return;
        }
        final Entry entry = new Entry();
        entry.host = host;
        entry.port = portValue;
        entry.secret = secret;
        out.add(entry);
    }

    /** замерить каждый вход: обычное TCP-подключение с коротким тайм-аутом */
    private static int measure(ArrayList<Entry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }
        final CountDownLatch latch = new CountDownLatch(entries.size());
        for (Entry entry : entries) {
            pool().submit(() -> {
                final long start = System.currentTimeMillis();
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(entry.host, entry.port), 2500);
                    entry.ping = System.currentTimeMillis() - start;
                } catch (Throwable e) {
                    entry.ping = -1;
                } finally {
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Throwable ignore) {
                        }
                    }
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(14, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
        int alive = 0;
        for (Entry entry : entries) {
            if (entry.ping > 0) {
                alive++;
            }
        }
        Collections.sort(entries, (a, b) -> {
            final long pa = a.ping <= 0 ? Long.MAX_VALUE : a.ping - (a.isFakeTls() ? 60 : 0);
            final long pb = b.ping <= 0 ? Long.MAX_VALUE : b.ping - (b.isFakeTls() ? 60 : 0);
            return Long.compare(pa, pb);
        });
        return alive;
    }

    private static void save(ArrayList<Entry> entries) {
        final SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        final SharedPreferences.Editor editor = p.edit();
        int saved = 0;
        for (Entry entry : entries) {
            if (entry.ping <= 0 || saved >= 20) {
                continue;
            }
            editor.putString("p" + saved, entry.host + "|" + entry.port + "|" + entry.secret + "|" + entry.ping);
            saved++;
        }
        editor.putInt("count", saved).putLong("updated", System.currentTimeMillis()).putInt("pos", 0).apply();
    }

    private static ArrayList<Entry> load() {
        final ArrayList<Entry> result = new ArrayList<>();
        final SharedPreferences p = prefs();
        if (p == null) {
            return result;
        }
        final int count = p.getInt("count", 0);
        for (int a = 0; a < count; ++a) {
            final String value = p.getString("p" + a, null);
            if (value == null) {
                continue;
            }
            final String[] parts = value.split("\\|");
            if (parts.length < 3) {
                continue;
            }
            final Entry entry = new Entry();
            entry.host = parts[0];
            try {
                entry.port = Integer.parseInt(parts[1]);
                entry.ping = parts.length > 3 ? Long.parseLong(parts[3]) : 0;
            } catch (Throwable e) {
                continue;
            }
            entry.secret = parts[2];
            result.add(entry);
        }
        return result;
    }

    // ------------------------------------------------------------- включение

    /** поставить самый быстрый живой вход */
    public static boolean useBest() {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putInt("pos", 0).apply();
        }
        return apply(0);
    }

    /** следующий по списку — если текущий перестал тянуть */
    public static boolean useNext() {
        final SharedPreferences p = prefs();
        final int pos = p == null ? 0 : p.getInt("pos", 0) + 1;
        if (p != null) {
            p.edit().putInt("pos", pos).apply();
        }
        return apply(pos);
    }

    private static boolean apply(int index) {
        final ArrayList<Entry> entries = load();
        if (entries.isEmpty()) {
            status = STATUS_FAILED;
            statusDetails = "";
            return false;
        }
        final Entry entry = entries.get(Math.max(0, index % entries.size()));
        try {
            final ProxySettings settings = ProxySettings.builder()
                    .setType(ProxySettings.Type.MTPROTO)
                    .setAddress(entry.host)
                    .setPort(entry.port)
                    .setSecret(entry.secret)
                    .build();
            final SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
            info.ping = entry.ping;
            info.available = true;
            info.availableCheckTime = System.currentTimeMillis();
            SharedConfig.currentProxy = info;
            final SharedPreferences main = MessagesController.getGlobalMainSettings();
            main.edit()
                    .putBoolean("proxy_enabled", true)
                    .putString("proxy_ip", entry.host)
                    .putInt("proxy_port", entry.port)
                    .putString("proxy_secret", entry.secret)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .apply();
            ConnectionsManager.setProxySettings(true, settings);
            rememberOurProxy(entry.host, entry.port);
            status = STATUS_PROXY;
            statusDetails = entry.host + ":" + entry.port + (entry.ping > 0 ? " · " + entry.ping + " ms" : "");
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            status = STATUS_FAILED;
            return false;
        }
    }

    /** вернуться на прямое соединение */
    public static void disableProxy() {
        try {
            if (!SharedConfig.isProxyEnabled() || !isOurProxy(SharedConfig.currentProxy)) {
                return;
            }
            MessagesController.getGlobalMainSettings().edit().putBoolean("proxy_enabled", false).apply();
            ConnectionsManager.setProxySettings(false, ProxySettings.EMPTY);
            status = isEnabled() ? STATUS_DIRECT : STATUS_OFF;
            statusDetails = "";
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** помним, какие входы поставили мы: чужие ручные прокси не трогаем */
    private static void rememberOurProxy(String host, int port) {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putString("ours", host + ":" + port).apply();
        }
    }

    private static boolean isOurProxy(SharedConfig.ProxyInfo info) {
        if (info == null) {
            return false;
        }
        final SharedPreferences p = prefs();
        if (p == null) {
            return false;
        }
        final String ours = p.getString("ours", "");
        return !TextUtils.isEmpty(ours)
                && ours.equals(info.settings.getAddress() + ":" + info.settings.getPort());
    }

    /** разобрать ссылку вида tg://proxy?server=…&port=…&secret=… , вставленную руками */
    public static boolean addFromLink(String link) {
        if (TextUtils.isEmpty(link)) {
            return false;
        }
        final Matcher matcher = LINK_PATTERN.matcher(link);
        if (!matcher.find()) {
            return false;
        }
        final ArrayList<Entry> entries = load();
        final Entry entry = new Entry();
        entry.host = matcher.group(1);
        entry.secret = matcher.group(3).replace("%3D", "").replace("=", "");
        try {
            entry.port = Integer.parseInt(matcher.group(2));
        } catch (Throwable e) {
            return false;
        }
        entry.ping = 1;
        entries.add(0, entry);
        final SharedPreferences p = prefs();
        if (p != null) {
            final SharedPreferences.Editor editor = p.edit();
            int saved = 0;
            for (Entry item : entries) {
                if (saved >= 20) {
                    break;
                }
                editor.putString("p" + saved, item.host + "|" + item.port + "|" + item.secret + "|" + item.ping);
                saved++;
            }
            editor.putInt("count", saved).putLong("updated", System.currentTimeMillis()).putInt("pos", 0).apply();
        }
        return apply(0);
    }

    private static class Entry {
        String host;
        int port;
        String secret;
        long ping;

        boolean isFakeTls() {
            return secret != null && (secret.startsWith("ee") || secret.length() > 34);
        }
    }
}
