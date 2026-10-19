package org.telegram.messenger;

import java.net.InetAddress;

/** Guard untrusted public proxy lists before probing or opening any socket. */
public final class PengramProxyPolicy {
    private PengramProxyPolicy() {}

    public static boolean validHost(String value) {
        return value != null && !value.isEmpty() && value.length() <= 253
                && value.matches("[A-Za-z0-9.:\\[\\]-]+") && !hasControls(value);
    }

    public static boolean validPath(String path) {
        if (path == null || !path.startsWith("/") || path.length() > 1024 || path.contains("#")) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 32 || c >= 127) return false;
        }
        return true;
    }

    private static boolean hasControls(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 31 || c == 127) return true;
        }
        return false;
    }

    public static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] ip = address.getAddress();
        if (ip.length == 4) {
            int a = ip[0] & 255, b = ip[1] & 255, c = ip[2] & 255;
            return a >= 1 && a < 224 && a != 10 && a != 127
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 0 || b == 168))
                    && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                    && !(a == 203 && b == 0 && c == 113);
        }
        if (ip.length != 16 || (ip[0] & 0xe0) != 0x20) return false;
        // IPv6 documentation, Teredo and 6to4 can contain embedded private IPv4.
        return !(ip[0] == 0x20 && ip[1] == 0x01
                && (ip[2] == 0x0d && (ip[3] & 255) == 0xb8 || ip[2] == 0 && ip[3] == 0))
                && !(ip[0] == 0x20 && ip[1] == 0x02);
    }
}
