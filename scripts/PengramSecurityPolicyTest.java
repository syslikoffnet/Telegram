import org.telegram.messenger.PengramAITransport;
import org.telegram.messenger.PengramProfilePolicy;
import org.telegram.messenger.PengramProxyPolicy;
import java.net.InetAddress;

public class PengramSecurityPolicyTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
    private static boolean allows(int scope, boolean tracked, boolean contact, boolean dialog) {
        return PengramProfilePolicy.allows(scope, true, true, false, false, false,
                false, false, false, tracked, contact, dialog);
    }
    public static void main(String[] args) throws Exception {
        for (int scope = 0; scope <= 3; scope++) {
            boolean expected = scope == 3;
            check(allows(scope, false, false, false) == expected, "unknown profile " + scope);
            check(allows(scope, true, false, false) == (scope != 2), "tracked " + scope);
            check(allows(scope, false, true, false) == (scope >= 2), "contact " + scope);
            check(allows(scope, false, false, true) == (scope != 0), "dialog " + scope);
            for (int reason = 0; reason < 8; reason++) {
                boolean allowed = PengramProfilePolicy.allows(scope, reason != 0,
                        reason != 1, reason == 2, reason == 3, reason == 4,
                        reason == 5, reason == 7, reason == 6, true, true, true);
                check(!allowed, "excluded user or cache " + scope + "/" + reason);
            }
        }
        check(!allows(-1, true, true, true), "unknown scope");
        for (int storage = 0; storage <= 2; storage++) {
            check(!PengramProfilePolicy.queueToCloud(storage, false), "disallowed cloud " + storage);
            check(PengramProfilePolicy.queueToCloud(storage, true) == (storage > 0), "allowed cloud " + storage);
        }
        String[] unsafe = {
                "http://127.0.0.1:80@attacker.example/v1/chat/completions",
                "http://localhost:80@attacker.example/v1/chat/completions",
                "http://attacker.example/v1/chat/completions", "http://[::ffff:7f00:1]/v1/chat/completions",
                "https://user:pass@example.com/v1/chat/completions",
                "https://example.com/v1/chat/completions?key=secret",
                "https://example.com/v1/chat/completions#fragment",
                "file:///tmp/a", "https://example.com\\@evil.example/v1/chat/completions"
        };
        for (String url : unsafe) check(!PengramAITransport.isSafeEndpoint(url), "unsafe URL " + url);
        check(PengramAITransport.isSafeEndpoint("https://api.openai.com/v1/chat/completions"), "HTTPS");
        check(PengramAITransport.isSafeEndpoint("http://127.0.0.1:11434/v1/chat/completions"), "loopback");
        check(PengramAITransport.isSafeEndpoint("http://localhost:11434/v1/chat/completions"), "localhost");
        check(PengramAITransport.isSafeEndpoint("http://[::1]:11434/v1/chat/completions"), "IPv6 loopback");
        for (String ip : new String[]{"127.0.0.1", "10.0.0.5", "172.16.0.1", "192.168.1.1",
                "169.254.169.254", "100.64.0.1", "192.0.2.3", "198.51.100.2", "203.0.113.9",
                "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "2001:db8::1"})
            check(!PengramProxyPolicy.isPublicAddress(InetAddress.getByName(ip)), "unsafe proxy " + ip);
        check(PengramProxyPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")), "public IPv4");
        check(PengramProxyPolicy.isPublicAddress(InetAddress.getByName("2001:4860:4860::8888")), "public IPv6");
        for (String path : new String[]{"/foo\r\nX-Evil: hi", "/foo\n", "/foo\u0000", "/foo#frag"})
            check(!PengramProxyPolicy.validPath(path), "bad proxy path");
        check(!PengramProxyPolicy.validHost("host\r\nX-Evil: hi"), "bad host");
        check(PengramProxyPolicy.validPath("/websocket?ed=2048"), "valid WebSocket path");
        System.out.println("Pengram security policy: OK");
    }
}
