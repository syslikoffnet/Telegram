#!/usr/bin/env python3
"""Host-JVM smoke test for the bounded Android ApplicationExitInfo protobuf reader."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/java/org/telegram/messenger/PengramTombstone.java"
fixture = r'''import org.telegram.messenger.PengramTombstone;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class TestNativeReport {
    static void v(ByteArrayOutputStream b, long value) {
        while ((value & ~127L) != 0) {
            b.write(((int)value & 127) | 128);
            value >>>= 7;
        }
        b.write((int)value);
    }
    static void num(ByteArrayOutputStream b, int field, long value) {
        v(b, field * 8L); v(b, value);
    }
    static void data(ByteArrayOutputStream b, int field, byte[] value) {
        v(b, field * 8L + 2); v(b, value.length); b.write(value, 0, value.length);
    }
    static void text(ByteArrayOutputStream b, int field, String value) {
        data(b, field, value.getBytes(StandardCharsets.UTF_8));
    }
    static void ok(boolean value, String error) { if (!value) throw new AssertionError(error); }
    public static void main(String[] args) {
        ByteArrayOutputStream proto = new ByteArrayOutputStream();
        num(proto, 1, 1); num(proto, 6, 1234);
        ByteArrayOutputStream signal = new ByteArrayOutputStream();
        num(signal, 1, 6); text(signal, 2, "SIGABRT"); text(signal, 4, "SI_TKILL");
        data(proto, 10, signal.toByteArray());
        text(proto, 14, "terminating due to std::bad_alloc");
        // Tombstone.threads is a map, not a list of Thread messages.
        for (int tid : new int[]{555, 1234}) {
            ByteArrayOutputStream thread = new ByteArrayOutputStream();
            text(thread, 2, tid == 1234 ? "imageLoadQueue" : "unrelated");
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            num(frame, 1, 0x123abc); text(frame, 4, "native_decoder");
            text(frame, 6, "/data/app/libtmessages.49.so");
            text(frame, 8, "df8e72");
            data(thread, 4, frame.toByteArray());
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            num(entry, 1, tid); data(entry, 2, thread.toByteArray());
            data(proto, 16, entry.toByteArray());
        }
        byte[] bytes = proto.toByteArray();
        String summary = PengramTombstone.summarize(bytes, bytes.length);
        for (String value : new String[]{"SIGABRT", "std::bad_alloc", "imageLoadQueue",
                "libtmessages.49.so", "123abc", "df8e72"})
            ok(summary.contains(value), "missing " + value + ": " + summary);
        ok(!summary.contains("unrelated") && !summary.contains("/data/app"), "leaked unrelated/private data");
        ok(PengramTombstone.summarize(bytes, 4).contains("Tombstone"), "truncation must not throw");
        ok(PengramTombstone.summarize(new byte[]{(byte)0xff, (byte)0xff}, 2)
                .contains("Tombstone"), "invalid varint must not throw");
        System.out.println("native tombstone: map, thread, frames, Build ID, truncation OK");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="pengram-native-report-") as temp:
    root = Path(temp)
    test = root / "TestNativeReport.java"
    test.write_text(fixture)
    subprocess.run(["javac", "-d", str(root), str(source), str(test)], check=True)
    subprocess.run(["java", "-cp", str(root), "TestNativeReport"], check=True)
