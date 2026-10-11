#!/usr/bin/env python3
"""Host-JVM PCM smoke test for the Android microphone DSP (no Android device needed).

Creates small stubs for the configuration and Android clock, then compiles the
real PengramVoiceChanger.java. GitHub Actions' JDK runs this before APK builds.
"""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/PengramVoiceChanger.java"


def put(root, name, text):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


with tempfile.TemporaryDirectory(prefix="pengram-voice-test-") as directory:
    root = Path(directory)
    put(root, "org/telegram/messenger/PengramVoiceChanger.java", SOURCE.read_text())
    names = sorted(set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", SOURCE.read_text())))
    put(root, "org/telegram/messenger/R.java", "package org.telegram.messenger; public class R { public static class string { "
        + " ".join(f"public static final int {name} = {i + 1};" for i, name in enumerate(names)) + " } }")
    put(root, "org/telegram/messenger/LocaleController.java", "package org.telegram.messenger; public class LocaleController { public static String getString(int id) { return \"test\"; } }")
    put(root, "org/telegram/messenger/FileLog.java", "package org.telegram.messenger; public class FileLog { public static void e(Throwable t) { throw new AssertionError(t); } }")
    put(root, "android/os/SystemClock.java", "package android.os; public class SystemClock { public static long elapsedRealtime() { return System.nanoTime()/1000000; } public static long elapsedRealtimeNanos() { return System.nanoTime(); } }")
    put(root, "org/telegram/messenger/PengramConfig.java", """package org.telegram.messenger;
public class PengramConfig {
    public static int mode = 0, pitch = 3, formant = 3, echo = 2, drive = 3, robot = 3, mix = 100;
    public static int getVoiceChangerMode() { return mode; }
    public static int getVoiceChangerPitch() { return pitch; }
    public static int getVoiceFormant() { return formant; }
    public static int getVoiceEcho() { return echo; }
    public static int getVoiceGate() { return 0; }
    public static int getVoiceStudioDrive() { return drive; }
    public static int getVoiceStudioRobot() { return robot; }
    public static int getVoiceStudioMix() { return mix; }
    public static boolean isVoiceCallsEnabled() { return true; }
    public static boolean isVoiceMessagesEnabled() { return true; }
    public static boolean isVoiceRoundEnabled() { return true; }
}
""")
    put(root, "TestVoiceStudio.java", """import java.nio.*;
import org.telegram.messenger.*;
public class TestVoiceStudio {
    static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    static ByteBuffer voice(int frames) {
        ByteBuffer b = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; ++i) {
            double sample = Math.sin(2 * Math.PI * i / 71.) * 9500
                + Math.sin(2 * Math.PI * i / 19.) * 2000;
            b.putShort(i * 2, (short)sample);
        }
        return b;
    }
    public static void main(String[] args) {
        final int[] modes = {PengramVoiceChanger.MODE_CUSTOM,
                PengramVoiceChanger.MODE_ASSISTANT, PengramVoiceChanger.MODE_ARMOR};
        for (int rate : new int[] {8000, 16000, 48000, 96000}) {
            int frames = rate / 100;
            for (int mode : modes) {
                PengramConfig.mode = mode;
                PengramConfig.mix = 100;
                PengramVoiceChanger.reset();
                ByteBuffer b = voice(frames);
                ByteBuffer original = voice(frames);
                for (int packet = 0; packet < 30; ++packet)
                    PengramVoiceChanger.process(b, b.capacity(), rate);
                int changed = 0;
                for (int i = 0; i < frames; ++i) {
                    if (b.getShort(i * 2) != original.getShort(i * 2)) changed++;
                }
                check(changed > frames / 4, "no audible transformation at " + rate + ", mode " + mode);
            }
            PengramConfig.mode = PengramVoiceChanger.MODE_CUSTOM;
            PengramConfig.mix = 0;
            PengramVoiceChanger.reset();
            ByteBuffer b = voice(frames), original = voice(frames);
            PengramVoiceChanger.process(b, b.capacity(), rate);
            for (int i = 0; i < frames; ++i)
                check(b.getShort(i * 2) == original.getShort(i * 2), "zero mix changed dry sample");
            PengramConfig.mix = 100;
            PengramVoiceChanger.reset();
            b = voice(frames);
            original = voice(frames);
            b.limit(frames); // valid samples < capacity; absolute writes must respect limit
            PengramVoiceChanger.process(b, frames * 2, rate);
            b.limit(frames * 2);
            for (int i = frames / 2; i < frames; ++i)
                check(b.getShort(i * 2) == original.getShort(i * 2), "wrote beyond buffer limit");
        }
        PengramConfig.mode = PengramVoiceChanger.MODE_OFF;
        ByteBuffer b = voice(480), original = voice(480);
        PengramVoiceChanger.process(b, b.capacity(), 48000);
        for (int i = 0; i < 480; ++i)
            check(b.getShort(i * 2) == original.getShort(i * 2), "off mode modified audio");
        System.out.println("voice studio PCM: 8/16/48/96 kHz, dry mix, buffer limits and off-mode OK");
    }
}
""")
    sources = [str(p) for p in root.rglob("*.java")]
    subprocess.run(["javac", "-d", str(root), *sources], check=True)
    subprocess.run(["java", "-cp", str(root), "TestVoiceStudio"], check=True)
