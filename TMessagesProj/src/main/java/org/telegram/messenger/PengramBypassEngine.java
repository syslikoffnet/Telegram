package org.telegram.messenger;

import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pengram: сам туннель.
 *
 * Внутри приложения поднимается крошечный локальный SOCKS5-сервер на 127.0.0.1,
 * и сетевое ядро Telegram отправляется ходить через него. Дальше каждый поток
 * уходит наружу одним из способов:
 *
 *  • РЕЖИМ «РАЗРЫВ» — прямое соединение, но первые байты рвутся на несколько
 *    отдельных пакетов. Фильтр не узнаёт подпись Telegram в первом пакете и
 *    пропускает соединение. Ни одного постороннего сервера в цепочке нет.
 *
 *  • РЕЖИМ «ЧЕРЕЗ САЙТ» — обычное HTTPS-подключение к сайту на Cloudflare,
 *    внутри которого живёт WebSocket, а внутри него — поток к серверам Telegram
 *    (протокол VLESS). Для оператора это неотличимо от открытия сайта, поэтому
 *    способ проходит даже там, где разрешён только белый список адресов.
 *
 * Весь код здесь — обычные сокеты и потоки, никаких сторонних библиотек.
 */
public final class PengramBypassEngine {

    public static final int ROUTE_SPLIT = 1;
    public static final int ROUTE_WS = 2;
    public static final int ROUTE_TGWS = 4;

    /** домены веб-версии Telegram по номеру дата-центра (1..5) */
    private static final String[] TGWS_HOSTS = {
            "pluto.web.telegram.org",
            "venus.web.telegram.org",
            "aurora.web.telegram.org",
            "vesta.web.telegram.org",
            "flora.web.telegram.org"
    };
    private static final String TGWS_PATH = "/apiws";

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final int BUFFER = 32 * 1024;
    private static final int CONNECT_TIMEOUT = 5000;

    private static final Object lock = new Object();
    private static ServerSocket server;
    private static Thread acceptThread;
    private static ExecutorService io;
    private static volatile boolean running;
    private static volatile int route = ROUTE_SPLIT;
    private static volatile PengramBypassSources.Node node;
    private static volatile int okStreams;
    private static volatile int failStreams;
    private static final SecureRandom random = new SecureRandom();

    private PengramBypassEngine() {
    }

    // ------------------------------------------------------------- управление

    /** поднимает локальный вход и возвращает его порт (0 — не получилось) */
    public static int start(int newRoute, PengramBypassSources.Node newNode) {
        synchronized (lock) {
            if (running && route == newRoute && sameNode(node, newNode) && server != null && !server.isClosed()) {
                return server.getLocalPort();
            }
            stop();
            route = newRoute;
            node = newNode;
            okStreams = 0;
            failStreams = 0;
            try {
                server = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
                io = Executors.newCachedThreadPool();
                running = true;
                acceptThread = new Thread(PengramBypassEngine::acceptLoop, "pengram-bypass-socks");
                acceptThread.setDaemon(true);
                acceptThread.start();
                return server.getLocalPort();
            } catch (Throwable e) {
                FileLog.e(e);
                stopLocked();
                return 0;
            }
        }
    }

    public static void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (Throwable ignore) {
            }
            server = null;
        }
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
        acceptThread = null;
    }

    public static boolean isActive() {
        return running;
    }

    public static int getRoute() {
        return route;
    }

    public static PengramBypassSources.Node getNode() {
        return node;
    }

    public static int getPort() {
        final ServerSocket s = server;
        return s == null || s.isClosed() ? 0 : s.getLocalPort();
    }

    public static int okStreams() {
        return okStreams;
    }

    public static int failStreams() {
        return failStreams;
    }

    private static boolean sameNode(PengramBypassSources.Node a, PengramBypassSources.Node b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.id().equals(b.id());
    }

    // ------------------------------------------------------------- локальный SOCKS5

    private static void acceptLoop() {
        while (running) {
            final ServerSocket s = server;
            if (s == null || s.isClosed()) {
                break;
            }
            try {
                final Socket client = s.accept();
                final ExecutorService executor = io;
                if (executor == null) {
                    try {
                        client.close();
                    } catch (Throwable ignore) {
                    }
                    break;
                }
                executor.execute(() -> handle(client));
            } catch (Throwable e) {
                if (running) {
                    try {
                        Thread.sleep(200);
                    } catch (Throwable ignore) {
                        break;
                    }
                }
            }
        }
    }

    private static void handle(Socket client) {
        Tunnel tunnel = null;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(0);
            final InputStream in = client.getInputStream();
            final OutputStream out = client.getOutputStream();

            // приветствие SOCKS5
            if (readByte(in) != 0x05) {
                throw new IOException("not socks5");
            }
            final int methods = readByte(in);
            for (int i = 0; i < methods; i++) {
                readByte(in);
            }
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // запрос на подключение
            if (readByte(in) != 0x05) {
                throw new IOException("bad request");
            }
            final int command = readByte(in);
            readByte(in);
            final int type = readByte(in);
            String host;
            if (type == 0x01) {
                final byte[] ip = readFully(in, 4);
                host = (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
            } else if (type == 0x03) {
                final int length = readByte(in);
                host = new String(readFully(in, length), ASCII);
            } else if (type == 0x04) {
                final byte[] ip = readFully(in, 16);
                host = InetAddress.getByAddress(ip).getHostAddress();
            } else {
                throw new IOException("bad atyp");
            }
            final byte[] portBytes = readFully(in, 2);
            final int port = ((portBytes[0] & 0xff) << 8) | (portBytes[1] & 0xff);
            if (command != 0x01) {
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                throw new IOException("only connect");
            }

            tunnel = openTunnel(host, port);
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();
            okStreams++;

            final Tunnel active = tunnel;
            final Thread upstream = new Thread(() -> pipe(in, active.out, active));
            upstream.setDaemon(true);
            upstream.start();
            pipe(active.in, out, active);
            upstream.interrupt();
        } catch (Throwable e) {
            failStreams++;
            try {
                client.getOutputStream().write(new byte[]{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            } catch (Throwable ignore) {
            }
        } finally {
            if (tunnel != null) {
                tunnel.close();
            }
            try {
                client.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void pipe(InputStream from, OutputStream to, Tunnel tunnel) {
        final byte[] buffer = new byte[BUFFER];
        try {
            int read;
            while ((read = from.read(buffer)) > 0) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (Throwable ignore) {
        } finally {
            tunnel.close();
        }
    }

    private static int readByte(InputStream in) throws IOException {
        final int value = in.read();
        if (value < 0) {
            throw new IOException("eof");
        }
        return value;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        final byte[] data = new byte[length];
        int done = 0;
        while (done < length) {
            final int read = in.read(data, done, length - done);
            if (read < 0) {
                throw new IOException("eof");
            }
            done += read;
        }
        return data;
    }

    // ------------------------------------------------------------- наружу

    static final class Tunnel {
        final InputStream in;
        final OutputStream out;
        private final Socket socket;
        private volatile boolean closed;

        Tunnel(InputStream in, OutputStream out, Socket socket) {
            this.in = in;
            this.out = out;
            this.socket = socket;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static Tunnel openTunnel(String host, int port) throws Exception {
        if (route == ROUTE_TGWS) {
            final int dc = datacenterOf(host);
            if (dc > 0) {
                try {
                    return tgWsTunnel(dc);
                } catch (Throwable e) {
                    FileLog.e("pengram tgws failed: " + e);
                }
            }
            return splitTunnel(host, port);
        }
        if (route == ROUTE_WS) {
            final PengramBypassSources.Node n = node;
            if (n == null) {
                throw new IOException("no node");
            }
            return wsTunnel(n, host, port, CONNECT_TIMEOUT);
        }
        return splitTunnel(host, port);
    }

    /** прямое соединение, но первый пакет разрезан на части */
    private static Tunnel splitTunnel(String host, int port) throws Exception {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
        socket.setTcpNoDelay(true);
        return new Tunnel(socket.getInputStream(), new SplitStream(socket.getOutputStream()), socket);
    }

    /**
     * Первая порция данных уходит двумя-тремя отдельными пакетами с микропаузой:
     * фильтр собирает поток по первому пакету и подписи Telegram в нём не находит.
     */
    private static final class SplitStream extends OutputStream {
        private final OutputStream target;
        private int written;

        SplitStream(OutputStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (written >= 2 || len < 8) {
                target.write(b, off, len);
                written++;
                return;
            }
            int done = 0;
            while (done < len) {
                final int chunk = Math.min(len - done, 2 + random.nextInt(Math.max(1, Math.min(24, len - done))));
                target.write(b, off + done, chunk);
                target.flush();
                done += chunk;
                try {
                    Thread.sleep(6);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            written++;
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }

    // ------------------------------------------------------------- туннель «через сайт»

    private static Tunnel wsTunnel(PengramBypassSources.Node n, String host, int port, int timeout) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(n.host, n.port), timeout);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(timeout);
        if (n.tls) {
            socket = upgradeTls(socket, n);
        }
        final InputStream rawIn = socket.getInputStream();
        final OutputStream rawOut = socket.getOutputStream();

        handshakeWs(n, rawIn, rawOut);
        socket.setSoTimeout(0);

        final WsStream ws = new WsStream(rawIn, rawOut);
        final byte[] header = vlessHeader(n.uuid, host, port);
        return new Tunnel(new VlessInput(ws), new VlessOutput(ws, header), socket);
    }

    // ------------------------------------------------------------- туннель «через веб-версию Telegram»

    /**
     * Тот самый путь, которым ходит web.telegram.org: обычный HTTPS к домену
     * своего дата-центра, внутри — WebSocket на {@code /apiws}, а внутри него
     * байт-в-байт тот же обфусцированный поток MTProto, который сетевое ядро
     * и так собиралось отправить. Ничего не перешифровываем: без секрета
     * MTProxy поток совпадает с тем, что ждёт сервер.
     *
     * Для фильтра это посещение сайта Telegram по доменному имени на 443 —
     * помогает и там, где режут не подпись, а адреса дата-центров.
     */
    private static Tunnel tgWsTunnel(int datacenter) throws Exception {
        final String domain = TGWS_HOSTS[datacenter - 1];
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(domain, 443), CONNECT_TIMEOUT);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(CONNECT_TIMEOUT);
        socket = upgradeTls(socket, domain, 443);
        final InputStream rawIn = socket.getInputStream();
        final OutputStream rawOut = socket.getOutputStream();
        handshakeTgWs(domain, rawIn, rawOut);
        socket.setSoTimeout(0);

        final WsStream ws = new WsStream(rawIn, rawOut);
        return new Tunnel(new WsInput(ws), new TgWsOutput(ws), socket);
    }

    /** веб-версия ходит с подпротоколом binary и своим Origin — повторяем точно */
    private static void handshakeTgWs(String domain, InputStream in, OutputStream out) throws Exception {
        final byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        final String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        final String request = "GET " + TGWS_PATH + " HTTP/1.1\r\n"
                + "Host: " + domain + "\r\n"
                + "Origin: https://web.telegram.org\r\n"
                + "User-Agent: Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36\r\n"
                + "Accept: */*\r\n"
                + "Accept-Language: en-US,en;q=0.9\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Protocol: binary\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(ASCII));
        out.flush();
        final String head = readHead(in);
        if (!head.toLowerCase(Locale.US).startsWith("http/1.1 101")) {
            throw new IOException("apiws refused");
        }
    }

    /**
     * Номер дата-центра по адресу, к которому идёт сетевое ядро. Адреса Telegram
     * стабильны и перечислены в самом ядре; у IPv6 номер прямо записан в адресе
     * (…:f00X:…). Незнакомый адрес — 0, такой поток пойдёт напрямую.
     */
    private static int datacenterOf(String host) {
        if (TextUtils.isEmpty(host)) {
            return 0;
        }
        final String value = host.toLowerCase(Locale.US);
        if (value.indexOf(':') >= 0) {
            for (int dc = 1; dc <= 5; dc++) {
                if (value.contains(":f00" + dc + ":")) {
                    return dc;
                }
            }
            return 0;
        }
        final int last = lastOctet(value);
        if (value.startsWith("149.154.175.")) {
            return last >= 100 ? 3 : 1;
        }
        if (value.startsWith("149.154.167.")) {
            return last >= 80 && last < 150 ? 4 : 2;
        }
        if (value.startsWith("149.154.171.") || value.startsWith("91.108.56.")) {
            return 5;
        }
        if (value.startsWith("149.154.164.") || value.startsWith("149.154.166.")) {
            return 4;
        }
        if (value.startsWith("95.161.76.")) {
            return 2;
        }
        return 0;
    }

    private static int lastOctet(String ip) {
        final int dot = ip.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= ip.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(ip.substring(dot + 1));
        } catch (Throwable ignore) {
            return -1;
        }
    }

    /**
     * Первые 64 байта обфусцированного заголовка уходят отдельным кадром:
     * сервер веб-версии разбирает их по приходу и не любит склейку с первым пакетом.
     */
    private static final class TgWsOutput extends OutputStream {
        private final WsStream ws;
        private int head = 64;

        TgWsOutput(WsStream ws) {
            this.ws = ws;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (head > 0 && len > head) {
                ws.send(b, off, head);
                final int rest = len - head;
                final int start = off + head;
                head = 0;
                ws.send(b, start, rest);
                return;
            }
            head = Math.max(0, head - len);
            ws.send(b, off, len);
        }
    }

    /** кадры WebSocket как обычный поток байтов */
    private static final class WsInput extends InputStream {
        private final WsStream ws;

        WsInput(WsStream ws) {
            this.ws = ws;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return ws.receive(b, off, len);
        }
    }

    private static Socket upgradeTls(Socket plain, String domain, int port) throws Exception {
        final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        final SSLSocket ssl = (SSLSocket) factory.createSocket(plain, domain, port, true);
        ssl.setUseClientMode(true);
        ssl.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(domain, ssl.getSession())) {
            throw new IOException("bad certificate");
        }
        return ssl;
    }

    private static Socket upgradeTls(Socket plain, PengramBypassSources.Node n) throws Exception {
        final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        final String sni = TextUtils.isEmpty(n.sni) ? n.host : n.sni;
        final SSLSocket ssl = (SSLSocket) factory.createSocket(plain, sni, n.port, true);
        if (Build.VERSION.SDK_INT >= 24 && !sni.equals(n.host)) {
            applySni(ssl, sni);
        }
        ssl.setUseClientMode(true);
        ssl.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.getSession())) {
            throw new IOException("bad certificate");
        }
        return ssl;
    }

    private static void applySni(SSLSocket ssl, String sni) {
        try {
            final SSLParameters params = ssl.getSSLParameters();
            final SNIServerName name = new SNIHostName(sni);
            params.setServerNames(Collections.singletonList(name));
            ssl.setSSLParameters(params);
        } catch (Throwable ignore) {
        }
    }

    private static void handshakeWs(PengramBypassSources.Node n, InputStream in, OutputStream out) throws Exception {
        final byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        final String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        final String hostHeader = TextUtils.isEmpty(n.wsHost) ? n.host : n.wsHost;
        final String request = "GET " + n.path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36\r\n"
                + "Accept: */*\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(ASCII));
        out.flush();

        final String head = readHead(in);
        if (!head.toLowerCase(Locale.US).startsWith("http/1.1 101")) {
            throw new IOException("no upgrade");
        }
    }

    /** читает ответ сервера до пустой строки */
    private static String readHead(InputStream in) throws IOException {
        final StringBuilder response = new StringBuilder();
        int state = 0;
        while (state < 4 && response.length() < 8192) {
            final int value = in.read();
            if (value < 0) {
                throw new IOException("eof in handshake");
            }
            response.append((char) value);
            if (value == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (value == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = 0;
            }
        }
        return response.toString();
    }

    /** кадры WebSocket: наружу — бинарные и маскированные, внутрь — любые */
    private static final class WsStream {
        private final InputStream in;
        private final OutputStream out;
        private long frameLeft;

        WsStream(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        synchronized void send(byte[] data, int off, int len) throws IOException {
            final byte[] mask = new byte[4];
            random.nextBytes(mask);
            final byte[] header;
            if (len < 126) {
                header = new byte[]{(byte) 0x82, (byte) (0x80 | len)};
            } else if (len < 65536) {
                header = new byte[]{(byte) 0x82, (byte) (0x80 | 126), (byte) (len >> 8), (byte) len};
            } else {
                header = new byte[]{(byte) 0x82, (byte) (0x80 | 127), 0, 0, 0, 0,
                        (byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len};
            }
            final byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) {
                payload[i] = (byte) (data[off + i] ^ mask[i & 3]);
            }
            out.write(header);
            out.write(mask);
            out.write(payload);
            out.flush();
        }

        int receive(byte[] buffer, int off, int len) throws IOException {
            while (frameLeft == 0) {
                readFrameHeader();
            }
            final int want = (int) Math.min(len, frameLeft);
            final int read = in.read(buffer, off, want);
            if (read < 0) {
                return -1;
            }
            frameLeft -= read;
            return read;
        }

        private void readFrameHeader() throws IOException {
            final int first = in.read();
            if (first < 0) {
                throw new IOException("eof");
            }
            final int opcode = first & 0x0f;
            final int second = in.read();
            if (second < 0) {
                throw new IOException("eof");
            }
            final boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = ((long) readOne() << 8) | readOne();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readOne();
                }
            }
            if (masked) {
                for (int i = 0; i < 4; i++) {
                    readOne();
                }
            }
            if (opcode == 0x8) {
                throw new IOException("closed");
            }
            if (opcode == 0x9) { // ping — отвечаем и читаем дальше
                final byte[] payload = new byte[(int) length];
                int done = 0;
                while (done < payload.length) {
                    final int read = in.read(payload, done, payload.length - done);
                    if (read < 0) {
                        throw new IOException("eof");
                    }
                    done += read;
                }
                sendPong(payload);
                return;
            }
            if (opcode == 0xa) {
                skip(length);
                return;
            }
            frameLeft = length;
        }

        private synchronized void sendPong(byte[] payload) throws IOException {
            final byte[] mask = new byte[4];
            random.nextBytes(mask);
            out.write(new byte[]{(byte) 0x8a, (byte) (0x80 | payload.length)});
            out.write(mask);
            final byte[] masked = new byte[payload.length];
            for (int i = 0; i < payload.length; i++) {
                masked[i] = (byte) (payload[i] ^ mask[i & 3]);
            }
            out.write(masked);
            out.flush();
        }

        private void skip(long length) throws IOException {
            long left = length;
            final byte[] trash = new byte[1024];
            while (left > 0) {
                final int read = in.read(trash, 0, (int) Math.min(trash.length, left));
                if (read < 0) {
                    throw new IOException("eof");
                }
                left -= read;
            }
        }

        private int readOne() throws IOException {
            final int value = in.read();
            if (value < 0) {
                throw new IOException("eof");
            }
            return value;
        }
    }

    /** заголовок VLESS: версия, ключ, команда «подключись туда-то» */
    private static byte[] vlessHeader(String uuid, String host, int port) throws IOException {
        final byte[] id = uuidBytes(uuid);
        final byte[] hostBytes = host.getBytes(ASCII);
        final boolean ip = host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
        final int addrLength = ip ? 4 : 1 + hostBytes.length;
        final byte[] header = new byte[1 + 16 + 1 + 1 + 2 + 1 + addrLength];
        int pos = 0;
        header[pos++] = 0;
        System.arraycopy(id, 0, header, pos, 16);
        pos += 16;
        header[pos++] = 0;
        header[pos++] = 1;
        header[pos++] = (byte) (port >> 8);
        header[pos++] = (byte) port;
        if (ip) {
            header[pos++] = 1;
            final String[] parts = host.split("\\.");
            for (int i = 0; i < 4; i++) {
                header[pos++] = (byte) Integer.parseInt(parts[i]);
            }
        } else {
            header[pos++] = 2;
            header[pos++] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, header, pos, hostBytes.length);
        }
        return header;
    }

    private static byte[] uuidBytes(String value) throws IOException {
        try {
            final UUID uuid = UUID.fromString(value.trim());
            final byte[] out = new byte[16];
            long high = uuid.getMostSignificantBits();
            long low = uuid.getLeastSignificantBits();
            for (int i = 7; i >= 0; i--) {
                out[i] = (byte) (high & 0xff);
                high >>= 8;
                out[8 + i] = (byte) (low & 0xff);
                low >>= 8;
            }
            return out;
        } catch (Throwable e) {
            throw new IOException("bad uuid");
        }
    }

    private static final class VlessOutput extends OutputStream {
        private final WsStream ws;
        private byte[] header;

        VlessOutput(WsStream ws, byte[] header) {
            this.ws = ws;
            this.header = header;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (header != null) {
                final byte[] merged = new byte[header.length + len];
                System.arraycopy(header, 0, merged, 0, header.length);
                System.arraycopy(b, off, merged, header.length, len);
                header = null;
                ws.send(merged, 0, merged.length);
                return;
            }
            ws.send(b, off, len);
        }
    }

    private static final class VlessInput extends InputStream {
        private final WsStream ws;
        private boolean greeted;

        VlessInput(WsStream ws) {
            this.ws = ws;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!greeted) {
                final byte[] head = new byte[2];
                if (!readExact(head, 2)) {
                    return -1;
                }
                final int extra = head[1] & 0xff;
                if (extra > 0) {
                    final byte[] skip = new byte[extra];
                    if (!readExact(skip, extra)) {
                        return -1;
                    }
                }
                greeted = true;
            }
            return ws.receive(b, off, len);
        }

        private boolean readExact(byte[] target, int length) throws IOException {
            int done = 0;
            while (done < length) {
                final int read = ws.receive(target, done, length - done);
                if (read < 0) {
                    return false;
                }
                done += read;
            }
            return true;
        }
    }

    // ------------------------------------------------------------- проверка входа

    /**
     * Проверка одного входа настоящим подключением: для «через сайт» — полный путь
     * до сервера Telegram, для MTProto — обычное подключение к адресу.
     * Возвращает время в миллисекундах или -1.
     */
    public static int probe(PengramBypassSources.Node n, int timeout) {
        final long start = System.currentTimeMillis();
        if (n.isWs()) {
            Tunnel tunnel = null;
            try {
                tunnel = wsTunnel(n, "149.154.167.50", 443, timeout);
                tunnel.out.write(new byte[]{(byte) 0xef});
                tunnel.out.flush();
                return (int) Math.max(1, System.currentTimeMillis() - start);
            } catch (Throwable e) {
                return -1;
            } finally {
                if (tunnel != null) {
                    tunnel.close();
                }
            }
        }
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(n.host, n.port), timeout);
            return (int) Math.max(1, System.currentTimeMillis() - start);
        } catch (Throwable e) {
            return -1;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** быстрая проверка прямого пути: отвечает ли сервер Telegram без всяких ухищрений */
    public static boolean directReachable(int timeout) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("149.154.167.50", 443), timeout);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
