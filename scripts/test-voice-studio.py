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
    put(root, "org/telegram/messenger/AudioEffectBridge.java", (SOURCE.parent / "AudioEffectBridge.java").read_text())
    names = sorted(set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", SOURCE.read_text())))
    put(root, "org/telegram/messenger/R.java", "package org.telegram.messenger; public class R { public static class string { "
        + " ".join(f"public static final int {name} = {i + 1};" for i, name in enumerate(names)) + " } }")
    put(root, "org/telegram/messenger/LocaleController.java", "package org.telegram.messenger; public class LocaleController { public static String getString(int id) { return \"test\"; } }")
    put(root, "org/telegram/messenger/FileLog.java", "package org.telegram.messenger; public class FileLog { public static void e(Throwable t) { throw new AssertionError(t); } }")
    put(root, "android/os/SystemClock.java", """package android.os;
public class SystemClock {
    public static long fakeMillis = -1, fakeNanosStep = 0, fakeNanos = 0;
    public static long elapsedRealtime() { return fakeMillis < 0 ? System.nanoTime()/1000000 : fakeMillis; }
    public static long elapsedRealtimeNanos() {
        if (fakeNanosStep == 0) return System.nanoTime();
        fakeNanos += fakeNanosStep;
        return fakeNanos;
    }
}""")
    put(root, "org/telegram/messenger/PengramConfig.java", """package org.telegram.messenger;
public class PengramConfig {
    public static int mode = 0, pitch = 3, formant = 3, echo = 2, drive = 3, robot = 3, mix = 100, gainDb = 0, gainDrive = 0;
    public static boolean calls = true, messages = true;
    public static int getVoiceChangerMode() { return mode; }
    public static int getVoiceChangerPitch() { return pitch; }
    public static int getVoiceFormant() { return formant; }
    public static int getVoiceEcho() { return echo; }
    public static int getVoiceGate() { return 0; }
    public static int getVoiceStudioDrive() { return drive; }
    public static int getVoiceStudioRobot() { return robot; }
    public static int getVoiceStudioMix() { return mix; }
    public static int getVoiceGainDb() { return gainDb; }
    public static int getVoiceGainDrive() { return gainDrive; }
    public static boolean isVoiceCallsEnabled() { return calls; }
    public static boolean isVoiceMessagesEnabled() { return messages; }
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
    static ByteBuffer speech(int frames, int rate, int amplitude) {
        ByteBuffer b = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; ++i) {
            b.putShort(i * 2, (short) (amplitude * Math.sin(2 * Math.PI * 250 * i / rate)));
        }
        return b;
    }
    static double rms(ByteBuffer b) {
        double sum = 0;
        for (int i = 0; i < b.limit() / 2; ++i) {
            int x = b.getShort(i * 2);
            check(Math.abs(x) <= 30001, "conference mode overflowed the limiter");
            sum += (double) x * x;
        }
        return Math.sqrt(sum / (b.limit() / 2));
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
            for (int mode : PengramVoiceChanger.MODES) {
                if (mode == PengramVoiceChanger.MODE_OFF) continue;
                PengramConfig.mode = mode;
                PengramVoiceChanger.reset();
                ByteBuffer sample = voice(frames);
                // Repeated packets cross both ring-buffer wrap points.
                for (int packet = 0; packet < 25; ++packet)
                    PengramVoiceChanger.process(sample, sample.capacity(), rate);
            }
            PengramConfig.mode = PengramVoiceChanger.MODE_CUSTOM;
            PengramConfig.mix = 0;
            PengramVoiceChanger.reset();
            ByteBuffer b = voice(frames), original = voice(frames);
            PengramVoiceChanger.process(b, b.capacity(), rate);
            for (int i = 0; i < frames; ++i)
                check(b.getShort(i * 2) == original.getShort(i * 2), "zero mix changed dry sample");
            check(!PengramVoiceChanger.isEnabledFor(PengramVoiceChanger.SOURCE_CALL)
                    && !AudioEffectBridge.isCallAudioEnabled(),
                    "zero mix must skip the call mic bridge");
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
        PengramConfig.mode = PengramVoiceChanger.MODE_CUSTOM;
        PengramConfig.mix = 100;
        android.os.SystemClock.fakeMillis = 10000;
        android.os.SystemClock.fakeNanosStep = 5_000_000; // emulate a slow 10 ms call packet
        for (int i = 0; i < 3; ++i) {
            ByteBuffer slow = voice(480);
            PengramVoiceChanger.processForSource(slow, slow.capacity(), 48000, PengramVoiceChanger.SOURCE_CALL);
        }
        check(PengramVoiceChanger.isBypassedForPerformance(), "failed to pause a slow call");
        ByteBuffer untouched = voice(480), copy = voice(480);
        PengramVoiceChanger.processForSource(untouched, untouched.capacity(), 48000, PengramVoiceChanger.SOURCE_CALL);
        for (int i = 0; i < 480; ++i)
            check(untouched.getShort(i * 2) == copy.getShort(i * 2), "paused call not dry");
        // Keep the call alive throughout cooldown; a fresh call resets the budget.
        for (int i = 0; i < 49; ++i) {
            android.os.SystemClock.fakeMillis += 100;
            PengramVoiceChanger.processForSource(untouched, untouched.capacity(), 48000, PengramVoiceChanger.SOURCE_CALL);
        }
        for (int i = 0; i < 3; ++i) {
            android.os.SystemClock.fakeMillis += 100;
            ByteBuffer slow = voice(480);
            PengramVoiceChanger.processForSource(slow, slow.capacity(), 48000, PengramVoiceChanger.SOURCE_CALL);
        }
        check(PengramVoiceChanger.isBypassedForPerformance(), "second CPU overrun not bypassed");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        check(!PengramVoiceChanger.isBypassedForPerformance(), "performance bypass leaked to next call");
        PengramConfig.mode = PengramVoiceChanger.MODE_ANONYMOUS;
        android.os.SystemClock.fakeMillis += 1000;
        for (int i = 0; i < 3; ++i) {
            ByteBuffer slow = voice(480);
            PengramVoiceChanger.processForSource(slow, slow.capacity(), 48000, PengramVoiceChanger.SOURCE_CALL);
        }
        check(PengramVoiceChanger.isBypassedForPerformance(), "anonymous overload not detected");
        ByteBuffer muted = voice(480);
        PengramVoiceChanger.processForSource(muted, muted.capacity(), 48000, PengramVoiceChanger.SOURCE_CALL);
        for (int i = 0; i < 480; ++i)
            check(muted.getShort(i * 2) == 0, "anonymous bypass exposed unmodified speech");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        android.os.SystemClock.fakeMillis = -1;
        android.os.SystemClock.fakeNanosStep = 0;
        PengramConfig.mode = PengramVoiceChanger.MODE_OFF;
        check(!AudioEffectBridge.isCallAudioEnabled(), "OFF call allocated a JNI buffer");
        ByteBuffer b = voice(480), original = voice(480);
        PengramVoiceChanger.process(b, b.capacity(), 48000);
        for (int i = 0; i < 480; ++i)
            check(b.getShort(i * 2) == original.getShort(i * 2), "off mode modified audio");
        // Amplification must work with the timbre OFF (and with 0% wet mix),
        // in both the outgoing voice-message and shared WebRTC microphone paths.
        PengramConfig.gainDb = 18;
        check(AudioEffectBridge.isCallAudioEnabled(), "boost-only call did not enter the JNI bridge");
        check(PengramVoiceChanger.isEnabled(), "boost-only voice message did not enter the recorder DSP");
        for (int source : new int[] {PengramVoiceChanger.SOURCE_VOICE, PengramVoiceChanger.SOURCE_CALL}) {
            for (int rate : new int[] {16000, 48000}) {
                PengramVoiceChanger.finishSource(source);
                ByteBuffer quiet = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 480; ++i) quiet.putShort(i * 2, (short) 500);
                quiet.position(18);
                PengramVoiceChanger.processForSource(quiet, quiet.capacity(), rate, source);
                check(quiet.getShort(0) > 3500 && quiet.getShort(0) < 4200,
                        "wrong +18 dB boost on source " + source);
                check(quiet.position() == 18, "boost changed buffer position");
                PengramVoiceChanger.finishSource(source);
            }
        }
        PengramConfig.gainDb = 30;
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        ByteBuffer loud = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 480; ++i) loud.putShort(i * 2, (short) (i % 2 == 0 ? 32000 : -32000));
        PengramVoiceChanger.processForSource(loud, loud.capacity(), 48000, PengramVoiceChanger.SOURCE_CALL);
        for (int i = 0; i < 480; ++i)
            check(Math.abs((int) loud.getShort(i * 2)) <= 30001, "boost exceeded limiter ceiling");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        PengramConfig.mode = PengramVoiceChanger.MODE_CUSTOM;
        PengramConfig.mix = 100;
        PengramConfig.gainDb = 18;
        PengramVoiceChanger.reset();
        ByteBuffer transformed = voice(480);
        PengramVoiceChanger.processForSource(transformed, transformed.capacity(), 48000,
                PengramVoiceChanger.SOURCE_VOICE);
        for (int i = 0; i < 480; ++i)
            check(Math.abs((int) transformed.getShort(i * 2)) <= 30001,
                    "effect and boost exceeded limiter ceiling");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_VOICE);
        PengramConfig.mode = PengramVoiceChanger.MODE_OFF;
        PengramConfig.gainDb = 30;
        PengramConfig.calls = false;
        check(!AudioEffectBridge.isCallAudioEnabled(), "call boost ignored opt-in switch");
        ByteBuffer disabled = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);
        disabled.putShort(0, (short) 500);
        PengramVoiceChanger.processForSource(disabled, 4, 48000, PengramVoiceChanger.SOURCE_CALL);
        check(disabled.getShort(0) == 500, "disabled call was amplified");
        PengramConfig.calls = true;
        PengramConfig.messages = false;
        check(!PengramVoiceChanger.isEnabled(), "voice messages ignored opt-in switch");
        PengramConfig.messages = true;
        PengramConfig.mode = PengramVoiceChanger.MODE_CUSTOM;
        PengramConfig.mix = 0;
        check(AudioEffectBridge.isCallAudioEnabled(), "zero wet mix suppressed independent gain");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        ByteBuffer dryBoost = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN);
        dryBoost.putShort(0, (short) 500);
        PengramVoiceChanger.processForSource(dryBoost, 2, 48000, PengramVoiceChanger.SOURCE_CALL);
        check(dryBoost.getShort(0) > 14500, "gain missing with 0% wet mix");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        // 60 dB is 1000x *before* the saturation/limiter; never wrap PCM16.
        PengramConfig.mode = PengramVoiceChanger.MODE_OFF;
        PengramConfig.gainDb = 60;
        PengramConfig.gainDrive = 75;
        for (int source : new int[] {PengramVoiceChanger.SOURCE_CALL, PengramVoiceChanger.SOURCE_VOICE}) {
            PengramVoiceChanger.finishSource(source);
            ByteBuffer hot = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 480; ++i) hot.putShort(i * 2, (short) (i % 2 == 0 ? 100 : -100));
            PengramVoiceChanger.processForSource(hot, hot.capacity(), 48000, source);
            for (int i = 0; i < 480; ++i) {
                int output = hot.getShort(i * 2);
                check(output > 0 == (i % 2 == 0), "overload flipped sample sign");
                check(Math.abs(output) > 20000 && Math.abs(output) <= 30001,
                        "60 dB overload failed or escaped the limiter");
            }
            PengramVoiceChanger.finishSource(source);
        }
        // Drive alone should also process audio, even when timbre and gain are off.
        PengramConfig.gainDb = 0;
        PengramConfig.gainDrive = 100;
        check(AudioEffectBridge.isCallAudioEnabled() && PengramVoiceChanger.isEnabled(),
                "overdrive-only microphone did not enter call/voice paths");
        ByteBuffer shaped = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);
        shaped.putShort(0, (short) 24000);
        shaped.putShort(2, (short) 500);
        PengramVoiceChanger.processForSource(shaped, 4, 48000, PengramVoiceChanger.SOURCE_CALL);
        check(shaped.getShort(0) > 6000 && shaped.getShort(0) < 24000,
                "overdrive did not audibly compress loud samples");
        check(shaped.getShort(2) == 500, "overdrive changed sample below knee");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        PengramConfig.gainDrive = 0;
        PengramConfig.mode = PengramVoiceChanger.MODE_OFF;
        PengramConfig.gainDb = 0;
        check(!AudioEffectBridge.isCallAudioEnabled() && !PengramVoiceChanger.isEnabled(),
                "disabled boost still allocated a call/voice buffer");
        // The distinct conference mode levels speech rather than clipping it.
        // It is independent of the intentionally distorted +60 dB preset.
        PengramConfig.mode = PengramVoiceChanger.MODE_TROLLING;
        for (int rate : new int[] {8000, 16000, 48000, 96000}) {
            int frames = rate / 100;
            double[] levels = new double[2];
            for (int a = 0; a < 2; ++a) {
                final int amplitude = a == 0 ? 600 : 12000;
                final int source = a == 0 ? PengramVoiceChanger.SOURCE_VOICE : PengramVoiceChanger.SOURCE_CALL;
                PengramVoiceChanger.finishSource(source);
                ByteBuffer processed = null;
                for (int packet = 0; packet < 40; ++packet) {
                    processed = speech(frames, rate, amplitude);
                    PengramVoiceChanger.processForSource(processed, processed.capacity(), rate, source);
                }
                levels[a] = rms(processed);
                PengramVoiceChanger.finishSource(source);
            }
            check(levels[0] > 2000, "quiet words were not lifted at " + rate);
            check(levels[1] > levels[0] && levels[1] < levels[0] * 6,
                    "conference speech was not leveled at " + rate);
        }
        // A real conversation changes level within one session: the gain must
        // recover for quiet words after somebody shouts, without raising hiss.
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        for (int packet = 0; packet < 20; ++packet) {
            ByteBuffer loudWord = speech(480, 48000, 16000);
            PengramVoiceChanger.processForSource(loudWord, loudWord.capacity(), 48000,
                    PengramVoiceChanger.SOURCE_CALL);
        }
        ByteBuffer quietWord = null;
        for (int packet = 0; packet < 40; ++packet) {
            quietWord = speech(480, 48000, 500);
            PengramVoiceChanger.processForSource(quietWord, quietWord.capacity(), 48000,
                    PengramVoiceChanger.SOURCE_CALL);
        }
        check(rms(quietWord) > 2000, "conference gain did not recover between words");
        ByteBuffer roomNoise = null;
        for (int packet = 0; packet < 100; ++packet) {
            roomNoise = speech(480, 48000, 10);
            PengramVoiceChanger.processForSource(roomNoise, roomNoise.capacity(), 48000,
                    PengramVoiceChanger.SOURCE_CALL);
        }
        check(rms(roomNoise) < 100, "conference noise expander amplifies quiet room noise");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        PengramConfig.calls = false;
        check(!AudioEffectBridge.isCallAudioEnabled(), "conference mode bypassed call opt-in");
        PengramConfig.calls = true;
        PengramConfig.gainDb = 60;
        PengramConfig.gainDrive = 100;
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        ByteBuffer clean = null;
        for (int packet = 0; packet < 40; ++packet) {
            clean = speech(480, 48000, 600);
            PengramVoiceChanger.processForSource(clean, clean.capacity(), 48000, PengramVoiceChanger.SOURCE_CALL);
        }
        double cleanLevel = rms(clean);
        check(cleanLevel > 2000 && cleanLevel < 18000,
                "distortion preset leaked into clean conference speech");
        PengramVoiceChanger.finishSource(PengramVoiceChanger.SOURCE_CALL);
        PengramConfig.gainDb = PengramConfig.gainDrive = 0;
        PengramConfig.mode = PengramVoiceChanger.MODE_OFF;
        System.out.println("voice studio PCM: 8/16/48/96 kHz, clear leveled conference speech, boost/overdrive voice/call, limiter, opt-in and off-mode OK");
    }
}
""")
    sources = [str(p) for p in root.rglob("*.java")]
    subprocess.run(["javac", "-d", str(root), *sources], check=True)
    subprocess.run(["java", "-cp", str(root), "TestVoiceStudio"], check=True)
