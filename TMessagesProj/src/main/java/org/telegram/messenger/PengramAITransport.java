package org.telegram.messenger;

import java.net.URI;
import java.util.Locale;

/** Strict URL policy shared by manual requests and opt-in auto-replies. */
public final class PengramAITransport {
    private PengramAITransport() {}

    public static boolean isSafeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 2048) return false;
        try {
            URI uri = new URI(endpoint);
            String host = uri.getHost();
            if (host == null || host.isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawAuthority() == null || uri.getRawPath() == null
                    || uri.getPort() > 65535 || uri.getPort() == 0
                    || endpoint.indexOf('\\') >= 0 || endpoint.indexOf('\r') >= 0
                    || endpoint.indexOf('\n') >= 0) return false;
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme)) return true;
            // Cleartext is only for literal device-local addresses; no DNS names
            // other than localhost and no user-info or ambiguous URL authority.
            if (!"http".equalsIgnoreCase(scheme)) return false;
            String lower = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(lower) || "127.0.0.1".equals(lower)
                    || "[::1]".equals(lower) || "::1".equals(lower);
        } catch (Exception e) {
            return false;
        }
    }
}
