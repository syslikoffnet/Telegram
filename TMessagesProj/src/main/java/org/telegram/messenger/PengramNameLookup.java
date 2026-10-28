package org.telegram.messenger;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pengram: настоящее имя человека из его публичной страницы профиля Telegram.
 *
 * <p>Пока человек записан в ваших контактах, клиент показывает ваше имя записи.
 * Публичная страница {@code https://t.me/<username>} отдаёт ровно то имя,
 * которое человек сам указал у себя в профиле Telegram, без любых изменений
 * в контактах и без передачи номера телефона.
 */
public final class PengramNameLookup {

    public interface Callback {
        void onDone(@Nullable String name);
    }

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    /** страница профиля весит заметно меньше; больше — значит это уже не она */
    private static final int MAX_BYTES = 128 * 1024;

    /** ники Telegram: латиница, цифры и подчёркивание */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,32}");
    private static final Pattern META = Pattern.compile("<meta\\s+[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_PROPERTY = Pattern.compile(
            "(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CONTENT = Pattern.compile(
            "content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_DIV = Pattern.compile(
            "<div[^>]*class\\s*=\\s*[\"'][^\"']*tgme_page_title[^\"']*[\"'][^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE_TAG = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private PengramNameLookup() {
    }

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    /**
     * Найти настоящее имя на публичной странице профиля.
     *
     * @param username ник без «@»
     * @param callback результат на фоновом потоке; {@code null} — узнать не вышло
     */
    public static void lookup(final String username, final Callback callback) {
        if (callback == null) {
            return;
        }
        if (!isValidUsername(username)) {
            callback.onDone(null);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String name = null;
            try {
                String html = fetch("https://t.me/" + username, false);
                name = parseName(html);
                if (name == null && hasSocksProxy()) {
                    html = fetch("https://t.me/" + username, true);
                    name = parseName(html);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            callback.onDone(name);
        });
    }

    private static boolean hasSocksProxy() {
        try {
            return SharedConfig.proxyEnabled
                    && SharedConfig.currentProxy != null
                    && TextUtils.isEmpty(SharedConfig.currentProxy.secret)
                    && !TextUtils.isEmpty(SharedConfig.currentProxy.address)
                    && SharedConfig.currentProxy.port > 0;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static String fetch(String urlString, boolean useProxy) {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            final URL url = new URL(urlString);
            if (useProxy && hasSocksProxy()) {
                Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                        new InetSocketAddress(SharedConfig.currentProxy.address, SharedConfig.currentProxy.port));
                connection = (HttpURLConnection) url.openConnection(proxy);
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }
            in = connection.getInputStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            final byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (out.size() + read > MAX_BYTES) {
                    out.write(buffer, 0, MAX_BYTES - out.size());
                    break;
                }
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignore) {
                }
            }
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** имя со страницы или {@code null}, если его там нет */
    @Nullable
    static String parseName(@Nullable String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        String name = null;
        final Matcher titleDiv = TITLE_DIV.matcher(html);
        if (titleDiv.find()) {
            name = cleanName(titleDiv.group(1));
        }
        if (name == null) {
            name = cleanName(meta(html, "og:title"));
        }
        if (name == null) {
            final Matcher title = TITLE_TAG.matcher(html);
            if (title.find()) {
                name = cleanName(title.group(1));
            }
        }
        return name;
    }

    @Nullable
    private static String meta(String html, String property) {
        final Matcher tags = META.matcher(html);
        while (tags.find()) {
            final String tag = tags.group();
            final Matcher prop = META_PROPERTY.matcher(tag);
            if (!prop.find() || !property.equalsIgnoreCase(prop.group(1))) {
                continue;
            }
            final Matcher content = META_CONTENT.matcher(tag);
            if (content.find()) {
                return content.group(1);
            }
        }
        return null;
    }

    /**
     * Привести содержимое тега к имени.
     *
     * <p>Отбрасывается всё, что именем не является: пустая строка, «@ник»,
     * служебные заголовки вида «Telegram: Contact @user» и хвост «(… @ник)»
     * в конце, если страница его приписала.
     */
    @Nullable
    static String cleanName(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String value = TAGS.matcher(raw).replaceAll(" ");
        value = unescape(value).replace('\u00a0', ' ');
        value = value.replaceAll("\\s+", " ").trim();
        final int tail = value.lastIndexOf(" (@");
        if (tail > 0 && value.endsWith(")")) {
            value = value.substring(0, tail).trim();
        }
        if (value.isEmpty() || value.startsWith("@")) {
            return null;
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("telegram") || lower.equals("telegram messenger")
                || lower.startsWith("telegram:") || lower.startsWith("telegram –")
                || lower.startsWith("contact @") || lower.startsWith("preview")
                || lower.startsWith("you are invited")) {
            return null;
        }
        if (value.length() > 96) {
            value = value.substring(0, 96).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private static String unescape(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        String out = value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
        final Matcher numeric = Pattern.compile("&#(x?[0-9a-fA-F]{1,6});").matcher(out);
        final StringBuffer sb = new StringBuffer(out.length());
        while (numeric.find()) {
            String code = numeric.group(1);
            int radix = 10;
            if (code.charAt(0) == 'x' || code.charAt(0) == 'X') {
                code = code.substring(1);
                radix = 16;
            }
            String replacement = "";
            try {
                final int point = Integer.parseInt(code, radix);
                if (Character.isValidCodePoint(point)) {
                    replacement = new String(Character.toChars(point));
                }
            } catch (Throwable ignore) {
            }
            numeric.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        numeric.appendTail(sb);
        return sb.toString();
    }
}
