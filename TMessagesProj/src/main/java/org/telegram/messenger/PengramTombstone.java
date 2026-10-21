package org.telegram.messenger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded reader for Android debuggerd's Tombstone protobuf (not plain text).
 * Only signal, abort message and the crashing thread's backtrace are extracted;
 * memory dumps, registers, other threads and potentially private logs are skipped.
 */
public final class PengramTombstone {
    private PengramTombstone() {}

    private static final class Reader {
        final byte[] data;
        final int end;
        int pos;

        Reader(byte[] data, int start, int end) {
            this.data = data;
            this.pos = start;
            this.end = end;
        }

        long varint() throws IOException {
            long value = 0;
            for (int i = 0; i < 10; ++i) {
                if (pos >= end) throw new IOException("Truncated protobuf");
                int b = data[pos++] & 255;
                value |= (long) (b & 127) << (7 * i);
                if ((b & 128) == 0) return value;
            }
            throw new IOException("Invalid protobuf varint");
        }

        Reader field(int wire) throws IOException {
            if (wire != 2) throw new IOException("Unexpected protobuf field");
            long length = varint();
            if (length < 0) throw new IOException("Invalid protobuf length");
            // The OS tombstone can be larger than the 128 KiB read budget.
            // Parse only the available prefix, never allocate from a protobuf length.
            int bounded = (int) Math.min(length, end - pos);
            Reader result = new Reader(data, pos, pos + bounded);
            pos += bounded;
            return result;
        }

        String text(int wire, int max) throws IOException {
            Reader bytes = field(wire);
            int length = Math.min(max, bytes.end - bytes.pos);
            String value = new String(data, bytes.pos, length, StandardCharsets.UTF_8);
            StringBuilder safe = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); ++i) {
                char c = value.charAt(i);
                if (c == '\n' || c == '\t' || !Character.isISOControl(c)) safe.append(c);
            }
            return safe.toString().trim();
        }

        void skip(int wire) throws IOException {
            switch (wire) {
                case 0: varint(); return;
                case 1: if (end - pos >= 8) { pos += 8; return; } break;
                case 2: field(2); return;
                case 5: if (end - pos >= 4) { pos += 4; return; } break;
                default: break;
            }
            throw new IOException("Invalid protobuf wire type");
        }
    }

    private static class ThreadInfo {
        String name;
        StringBuilder frames = new StringBuilder();
        int count;
    }

    private static String parseSignal(Reader signal) throws IOException {
        String name = "", code = "";
        while (signal.pos < signal.end) {
            long tag = signal.varint();
            int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (field == 2) name = signal.text(wire, 64);
            else if (field == 4) code = signal.text(wire, 64);
            else signal.skip(wire);
        }
        return name + (code.isEmpty() ? "" : " (" + code + ")");
    }

    private static void parseFrame(Reader frame, ThreadInfo info) throws IOException {
        long pc = 0, offset = 0;
        String path = "", function = "", buildId = "";
        while (frame.pos < frame.end) {
            long tag = frame.varint();
            int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (field == 1 && wire == 0) pc = frame.varint(); // rel_pc within the shared library
            else if (field == 4) function = frame.text(wire, 256);
            else if (field == 5 && wire == 0) offset = frame.varint();
            else if (field == 6) path = frame.text(wire, 256);
            else if (field == 8) buildId = frame.text(wire, 64);
            else frame.skip(wire);
        }
        if (info.count++ >= 12) return;
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        info.frames.append('#').append(info.count - 1).append("  ")
                .append(path.isEmpty() ? "?" : path).append("  pc 0x")
                .append(Long.toHexString(pc));
        if (!function.isEmpty()) {
            info.frames.append("  ").append(function);
            if (offset != 0) info.frames.append(" + ").append(offset);
        }
        if (!buildId.isEmpty()) info.frames.append(" [Build ID: ").append(buildId).append(']');
        info.frames.append('\n');
    }

    private static ThreadInfo parseThread(Reader thread) throws IOException {
        ThreadInfo result = new ThreadInfo();
        try {
            while (thread.pos < thread.end) {
                long tag = thread.varint();
                int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                if (field == 2) result.name = thread.text(wire, 96);
                else if (field == 4) {
                    if (result.count < 12) parseFrame(thread.field(wire), result);
                    else thread.skip(wire);
                } else thread.skip(wire);
            }
        } catch (IOException ignored) {
            // Keep any frames decoded before a truncated last field.
        }
        return result;
    }

    /** Fail closed on malformed/truncated OEM traces; never print binary bytes. */
    public static String summarize(byte[] data, int length) {
        if (data == null || length <= 0) return "Tombstone unavailable.\n";
        Reader root = new Reader(data, 0, Math.min(length, data.length));
        int tid = 0;
        String signal = "", abort = "";
        ThreadInfo crashed = null;
        try {
            while (root.pos < root.end) {
                long tag = root.varint();
                int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                if (field == 0) break;
                if (field == 6 && wire == 0) tid = (int) root.varint();
                else if (field == 10) signal = parseSignal(root.field(wire));
                else if (field == 14) abort = root.text(wire, 512);
                else if (field == 16) {
                    Reader entry = root.field(wire);
                    int id = 0;
                    Reader threadValue = null;
                    try {
                        while (entry.pos < entry.end) {
                            long subtag = entry.varint();
                            int subfield = (int) (subtag >>> 3), subwire = (int) (subtag & 7);
                            if (subfield == 1 && subwire == 0) id = (int) entry.varint();
                            else if (subfield == 2) threadValue = entry.field(subwire);
                            else entry.skip(subwire);
                        }
                    } catch (IOException ignored) {
                        // A partial map entry may still contain the crashing stack.
                    }
                    // Map-entry fields need not be serialized in key-then-value order.
                    // Do not parse registers/backtraces for any unrelated thread.
                    if (id == tid && tid != 0 && threadValue != null) crashed = parseThread(threadValue);
                } else root.skip(wire);
            }
        } catch (IOException ignored) {
            // ApplicationExitInfo streams can be truncated by the OS or our size cap.
        }
        StringBuilder out = new StringBuilder(1800);
        if (!signal.isEmpty()) out.append("Сигнал: ").append(signal).append('\n');
        if (!abort.isEmpty()) out.append("Причина: ").append(abort).append('\n');
        if (crashed != null) {
            out.append("Поток: ").append(crashed.name == null ? "?" : crashed.name)
                    .append(" (tid ").append(tid).append(")\n");
            out.append(crashed.frames);
        }
        if (out.length() == 0) out.append("Tombstone could not be decoded. ");
        out.append("Для точной диагностики сохраните полный tombstone или adb logcat.\n");
        return out.toString();
    }
}
