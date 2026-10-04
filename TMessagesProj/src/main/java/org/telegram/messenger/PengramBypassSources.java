package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pengram: склад входов для обхода блокировки.
 *
 * Здесь два разных вида входов.
 *
 * 1. «ws» — туннель через обычный сайт на Cloudflare: подключение идёт по HTTPS
 *    на 443-й порт к адресу, который в сети выглядит как рядовой сайт. Именно это
 *    работает там, где разрешён только белый список: Cloudflare держит миллионы
 *    сайтов, его адреса почти всегда остаются доступны.
 * 2. «mt» — классический MTProto-вход. Запасной вариант: быстрее, но его адреса
 *    при белых списках чаще всего закрыты.
 *
 * Список приложение собирает само из открытых каталогов, проверяет каждый вход
 * настоящим подключением и хранит два десятка самых быстрых. Всё это происходит
 * не чаще раза в сутки и только если обход реально включён.
 */
public final class PengramBypassSources {

    private static final String PREFS = "pengram_bypass";
    // Публичные входы быстро блокируются и перегружаются: обновляем несколько раз в сутки.
    private static final long LIST_TTL = 6L * 60 * 60 * 1000;
    private static final int KEEP = 24;
    private static final int PROBE_TIMEOUT = 4000;

    /** каталоги входов через Cloudflare; зеркала cdn.jsdelivr.net идут первыми — они живут на том же Cloudflare */
    private static final String[] WS_SOURCES = new String[]{
            "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Config@main/Splitted-By-Protocol/vless.txt",
            "https://cdn.jsdelivr.net/gh/Epodonios/v2ray-configs@main/Splitted-By-Protocol/vless.txt",
            "https://cdn.jsdelivr.net/gh/mahdibland/V2RayAggregator@master/sub/sub_merge.txt",
            "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt",
            "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt",
    };

    /** каталоги классических MTProto-входов */
    private static final String[] MT_SOURCES = new String[]{
            "https://mtpro.xyz/api/?type=mtproto",
            "https://cdn.jsdelivr.net/gh/Chumbayoumba/free-telegram-proxy-russia-2026@main/README.md",
            "https://raw.githubusercontent.com/Chumbayoumba/free-telegram-proxy-russia-2026/main/README.md",
            "https://t.me/s/MTProtoProxies",
            "https://t.me/s/mtproxy_tg",
    };

    private static final Pattern VLESS_PATTERN = Pattern.compile("vless://[^\\s\"'<>]+");
    private static final Pattern MT_LINK_PATTERN = Pattern.compile(
            "server=([A-Za-z0-9._\\-]+)(?:&|&amp;)port=(\\d{2,5})(?:&|&amp;)secret=([A-Za-z0-9_\\-=+%]{16,})");
    private static final Pattern MT_HOST_PATTERN = Pattern.compile("\"(?:host|ip|server)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MT_PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*\"?(\\d{2,5})\"?");
    private static final Pattern MT_SECRET_PATTERN = Pattern.compile("\"secret\"\\s*:\\s*\"([A-Za-z0-9_\\-=+]{16,})\"");

    private static ExecutorService pool;
    private static volatile boolean busy;
    private static ArrayList<Node> cache;

    public interface Callback {
        void onResult(int alive, int total);
    }

    /** один вход: либо туннель через сайт, либо MTProto */
    public static final class Node {
        public String kind = "ws";
        public String host = "";
        public int port = 443;
        public String uuid = "";
        public String sni = "";
        public String wsHost = "";
        public String path = "/";
        public boolean tls = true;
        public String secret = "";
        public String title = "";
        public int ping = 9999;

        public boolean isWs() {
            return "ws".equals(kind);
        }

        public boolean valid() {
            if (TextUtils.isEmpty(host) || port <= 0 || port > 65535) {
                return false;
            }
            return isWs() ? uuid.length() >= 32 : secret.length() >= 16;
        }

        public String id() {
            return kind + "|" + host + "|" + port + "|" + (isWs() ? uuid : secret);
        }

        JSONObject toJson() throws Exception {
            final JSONObject o = new JSONObject();
            o.put("k", kind);
            o.put("h", host);
            o.put("p", port);
            o.put("u", uuid);
            o.put("s", sni);
            o.put("w", wsHost);
            o.put("t", path);
            o.put("l", tls);
            o.put("x", secret);
            o.put("n", title);
            o.put("g", ping);
            return o;
        }

        static Node fromJson(JSONObject o) {
            final Node n = new Node();
            n.kind = o.optString("k", "ws");
            n.host = o.optString("h", "");
            n.port = o.optInt("p", 443);
            n.uuid = o.optString("u", "");
            n.sni = o.optString("s", "");
            n.wsHost = o.optString("w", "");
            n.path = o.optString("t", "/");
            n.tls = o.optBoolean("l", true);
            n.secret = o.optString("x", "");
            n.title = o.optString("n", "");
            n.ping = o.optInt("g", 9999);
            return n;
        }
    }

    // ------------------------------------------------------------- хранение

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext == null ? null
                : ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(8);
        }
        return pool;
    }

    public static boolean isBusy() {
        return busy;
    }

    public static synchronized ArrayList<Node> all() {
        if (cache != null) {
            return cache;
        }
        final ArrayList<Node> list = new ArrayList<>();
        final SharedPreferences p = prefs();
        if (p != null) {
            try {
                final JSONArray arr = new JSONArray(p.getString("nodes", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    final Node n = Node.fromJson(arr.getJSONObject(i));
                    if (n.valid()) {
                        list.add(n);
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        cache = list;
        return list;
    }

    public static ArrayList<Node> nodesOfKind(String kind) {
        final ArrayList<Node> out = new ArrayList<>();
        for (Node n : all()) {
            if (kind.equals(n.kind)) {
                out.add(n);
            }
        }
        Collections.sort(out, PengramBypassSources::compareRoutes);
        return out;
    }

    private static boolean isFakeTls(Node node) {
        if (node == null || node.isWs() || TextUtils.isEmpty(node.secret)) return false;
        final String secret = node.secret.trim().toLowerCase(java.util.Locale.US);
        // Fake-TLS MTProto secrets use the ee prefix (hex or URL-safe representation).
        return secret.startsWith("ee");
    }

    /** Fake-TLS на 443 предпочтительнее простого MTProto даже при чуть большем ping. */
    private static int compareRoutes(Node a, Node b) {
        if (!a.isWs() && !b.isWs()) {
            final int fake = Boolean.compare(isFakeTls(b), isFakeTls(a));
            if (fake != 0) return fake;
            final int httpsPort = Boolean.compare(b.port == 443, a.port == 443);
            if (httpsPort != 0) return httpsPort;
        }
        return Integer.compare(a.ping, b.ping);
    }

    public static int savedCount() {
        return all().size();
    }

    public static long listAge() {
        final SharedPreferences p = prefs();
        if (p == null) {
            return Long.MAX_VALUE;
        }
        final long time = p.getLong("nodes_time", 0);
        return time == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - time;
    }

    public static boolean needRefresh() {
        return savedCount() == 0 || listAge() > LIST_TTL;
    }

    private static synchronized void save(ArrayList<Node> list) {
        cache = list;
        final SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        try {
            final JSONArray arr = new JSONArray();
            for (Node n : list) {
                arr.put(n.toJson());
            }
            p.edit().putString("nodes", arr.toString()).putLong("nodes_time", System.currentTimeMillis()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** вход, добавленный вручную, живёт вечно и не вытесняется обновлением списка */
    public static synchronized boolean addManual(Node node) {
        if (node == null || !node.valid()) {
            return false;
        }
        node.title = TextUtils.isEmpty(node.title) ? node.host : node.title;
        node.ping = 1;
        final ArrayList<Node> list = new ArrayList<>(all());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(node.id())) {
                list.remove(i);
                break;
            }
        }
        list.add(0, node);
        save(list);
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putString("manual", node.id()).apply();
        }
        return true;
    }

    // ------------------------------------------------------------- разбор ссылок

    /** понимает и vless://, и tg://proxy / https://t.me/proxy */
    public static Node parseLink(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        final String link = raw.trim();
        try {
            if (link.startsWith("vless://")) {
                return parseVless(link);
            }
            if (link.contains("proxy?") || link.contains("socks?")) {
                final Uri uri = Uri.parse(link);
                final Node n = new Node();
                n.kind = "mt";
                n.host = String.valueOf(uri.getQueryParameter("server"));
                n.port = Integer.parseInt(String.valueOf(uri.getQueryParameter("port")));
                n.secret = String.valueOf(uri.getQueryParameter("secret"));
                n.title = n.host;
                return n.valid() ? n : null;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static Node parseVless(String link) {
        try {
            String rest = link.substring("vless://".length());
            String title = "";
            final int hash = rest.indexOf('#');
            if (hash >= 0) {
                title = Uri.decode(rest.substring(hash + 1));
                rest = rest.substring(0, hash);
            }
            final int at = rest.indexOf('@');
            if (at <= 0) {
                return null;
            }
            final String uuid = rest.substring(0, at);
            String hostPart = rest.substring(at + 1);
            String query = "";
            final int q = hostPart.indexOf('?');
            if (q >= 0) {
                query = hostPart.substring(q + 1);
                hostPart = hostPart.substring(0, q);
            }
            final int colon = hostPart.lastIndexOf(':');
            if (colon <= 0) {
                return null;
            }
            final Node n = new Node();
            n.kind = "ws";
            n.uuid = uuid;
            n.host = hostPart.substring(0, colon).replace("[", "").replace("]", "");
            n.port = Integer.parseInt(hostPart.substring(colon + 1).trim());
            n.title = title;
            String type = "tcp";
            String security = "none";
            for (String pair : query.split("&")) {
                final int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                final String key = pair.substring(0, eq);
                final String value = Uri.decode(pair.substring(eq + 1));
                if ("type".equals(key)) {
                    type = value;
                } else if ("security".equals(key)) {
                    security = value;
                } else if ("sni".equals(key) || "peer".equals(key)) {
                    n.sni = value;
                } else if ("host".equals(key)) {
                    n.wsHost = value;
                } else if ("path".equals(key)) {
                    n.path = value;
                } else if ("pbk".equals(key) || "publicKey".equals(key)) {
                    return null; // reality нам не по зубам
                }
            }
            if (!"ws".equals(type) && !"websocket".equals(type) && !"httpupgrade".equals(type)) {
                return null;
            }
            n.tls = "tls".equals(security);
            if (TextUtils.isEmpty(n.path)) {
                n.path = "/";
            }
            if (!n.path.startsWith("/")) {
                n.path = "/" + n.path;
            }
            if (TextUtils.isEmpty(n.sni)) {
                n.sni = TextUtils.isEmpty(n.wsHost) ? n.host : n.wsHost;
            }
            if (TextUtils.isEmpty(n.wsHost)) {
                n.wsHost = n.sni;
            }
            return n.valid() ? n : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    // ------------------------------------------------------------- загрузка списка

    /** скачивает каталоги, проверяет входы настоящим подключением и сохраняет лучшие */
    public static void refresh(final boolean force, final Callback callback) {
        if (busy) {
            return;
        }
        if (!force && !needRefresh()) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(savedCount(), savedCount()));
            }
            return;
        }
        busy = true;
        new Thread(() -> {
            int alive = 0;
            int total = 0;
            try {
                final ArrayList<Node> found = new ArrayList<>();
                final HashSet<String> seen = new HashSet<>();
                for (Node manual : all()) {
                    if (manual.ping == 1 && seen.add(manual.id())) {
                        found.add(manual);
                    }
                }
                final CountDownLatch downloads = new CountDownLatch(WS_SOURCES.length + MT_SOURCES.length);
                for (String source : WS_SOURCES) {
                    pool().execute(() -> {
                        try {
                            synchronized (found) { collectWs(download(source), found, seen); }
                        } finally { downloads.countDown(); }
                    });
                }
                for (String source : MT_SOURCES) {
                    pool().execute(() -> {
                        try {
                            synchronized (found) { collectMt(download(source), found, seen); }
                        } finally { downloads.countDown(); }
                    });
                }
                downloads.await(18, TimeUnit.SECONDS);
                total = found.size();
                alive = benchmark(found);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            busy = false;
            final int a = alive;
            final int t = total;
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(a, t));
            }
        }, "pengram-bypass-refresh").start();
    }

    private static int countOfKind(ArrayList<Node> list, String kind) {
        int n = 0;
        for (Node node : list) {
            if (kind.equals(node.kind)) {
                n++;
            }
        }
        return n;
    }

    private static void collectWs(String body, ArrayList<Node> out, HashSet<String> seen) {
        if (TextUtils.isEmpty(body)) {
            return;
        }
        String text = body;
        if (!text.contains("vless://")) {
            text = maybeBase64(text);
        }
        final Matcher m = VLESS_PATTERN.matcher(text);
        while (m.find() && countOfKind(out, "ws") < 48) {
            final Node n = parseVless(m.group());
            if (n != null && n.valid() && seen.add(n.id())) {
                out.add(n);
            }
        }
    }

    private static void collectMt(String body, ArrayList<Node> out, HashSet<String> seen) {
        if (TextUtils.isEmpty(body)) {
            return;
        }
        final Matcher link = MT_LINK_PATTERN.matcher(body);
        while (link.find() && countOfKind(out, "mt") < 64) {
            final Node n = new Node();
            n.kind = "mt";
            n.host = link.group(1);
            try {
                n.port = Integer.parseInt(link.group(2));
            } catch (Throwable ignore) {
                continue;
            }
            n.secret = Uri.decode(link.group(3));
            n.title = n.host;
            if (n.valid() && seen.add(n.id())) {
                out.add(n);
            }
        }
        final Matcher hosts = MT_HOST_PATTERN.matcher(body);
        final Matcher ports = MT_PORT_PATTERN.matcher(body);
        final Matcher secrets = MT_SECRET_PATTERN.matcher(body);
        while (hosts.find() && ports.find() && secrets.find() && countOfKind(out, "mt") < 64) {
            final Node n = new Node();
            n.kind = "mt";
            n.host = hosts.group(1);
            try {
                n.port = Integer.parseInt(ports.group(1));
            } catch (Throwable ignore) {
                continue;
            }
            n.secret = secrets.group(1);
            n.title = n.host;
            if (n.valid() && seen.add(n.id())) {
                out.add(n);
            }
        }
    }

    private static String maybeBase64(String text) {
        try {
            final String clean = text.replaceAll("\\s", "");
            if (clean.length() < 40 || !clean.matches("[A-Za-z0-9+/=_\\-]+")) {
                return text;
            }
            final byte[] data = android.util.Base64.decode(clean, android.util.Base64.DEFAULT);
            return new String(data, "UTF-8");
        } catch (Throwable ignore) {
            return text;
        }
    }

    private static String download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(9000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Pengram");
            if (connection.getResponseCode() / 100 != 2) {
                return null;
            }
            final InputStream in = connection.getInputStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[16384];
            int read;
            int guard = 0;
            while ((read = in.read(buffer)) > 0 && guard < 4 * 1024 * 1024) {
                out.write(buffer, 0, read);
                guard += read;
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable ignore) {
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

    /** проверяем входы параллельно и оставляем самые быстрые живые */
    private static int benchmark(ArrayList<Node> candidates) {
        final ArrayList<Node> alive = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(candidates.size());
        for (final Node node : candidates) {
            pool().execute(() -> {
                try {
                    if (node.ping == 1) {
                        synchronized (alive) {
                            alive.add(node);
                        }
                        return;
                    }
                    final int ping = PengramBypassEngine.probe(node, PROBE_TIMEOUT);
                    if (ping > 0) {
                        node.ping = ping;
                        synchronized (alive) {
                            alive.add(node);
                        }
                    }
                } catch (Throwable ignore) {
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(22, TimeUnit.SECONDS);
        } catch (Throwable ignore) {
        }
        synchronized (alive) {
            Collections.sort(alive, PengramBypassSources::compareRoutes);
            final ArrayList<Node> keep = new ArrayList<>();
            int ws = 0;
            int mt = 0;
            for (Node n : alive) {
                if (n.isWs() && ws < KEEP) {
                    keep.add(n);
                    ws++;
                } else if (!n.isWs() && mt < KEEP) {
                    keep.add(n);
                    mt++;
                }
            }
            save(keep);
            return keep.size();
        }
    }

    /** список для показа на экране: «через сайт» идут первыми */
    public static List<Node> forDisplay() {
        final ArrayList<Node> out = new ArrayList<>(nodesOfKind("ws"));
        out.addAll(nodesOfKind("mt"));
        return out;
    }
}
