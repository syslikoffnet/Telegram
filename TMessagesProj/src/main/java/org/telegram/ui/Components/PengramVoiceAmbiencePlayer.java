package org.telegram.ui.Components;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramVoiceChanger;

/**
 * 8-секундный плеер живого предпрослушивания фоновой среды (Ambience) в настройках Pengram.
 * Вынесен из PengramVoiceChanger, чтобы DSP-ядро оставалось чистым Java-кодом без AudioTrack.
 */
public final class PengramVoiceAmbiencePlayer {

    private static volatile boolean running;
    private static volatile Thread workerThread;

    private PengramVoiceAmbiencePlayer() {
    }

    public static boolean isPlaying() {
        return running;
    }

    public static void stop() {
        running = false;
        final Thread t = workerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public static void toggle(final Runnable onStateChanged) {
        if (running) {
            stop();
            if (onStateChanged != null) {
                AndroidUtilities.runOnUIThread(onStateChanged);
            }
            return;
        }
        if (PengramConfig.getVoiceAmbience() == PengramVoiceChanger.AMBIENCE_OFF) {
            PengramConfig.setVoiceAmbience(PengramVoiceChanger.AMBIENCE_SUBWAY);
        }
        running = true;
        if (onStateChanged != null) {
            AndroidUtilities.runOnUIThread(onStateChanged);
        }
        final Thread worker = new Thread(() -> {
            AudioTrack track = null;
            try {
                final int rate = 48000;
                final int minBuf = AudioTrack.getMinBufferSize(
                        rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                final int bufBytes = Math.max(minBuf, rate / 10 * 2);
                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        rate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufBytes,
                        AudioTrack.MODE_STREAM);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    return;
                }
                PengramVoiceChanger.resetAmbiencePreview(rate);
                track.play();
                final int chunkSamples = rate / 50; // 20 ms
                final short[] pcm = new short[chunkSamples];
                final int totalSamples = rate * 8;
                final int fadeSamples = rate / 6;
                int written = 0;
                while (running && written < totalSamples && !Thread.currentThread().isInterrupted()) {
                    final int amb = PengramConfig.getVoiceAmbience();
                    if (amb == PengramVoiceChanger.AMBIENCE_OFF) {
                        break;
                    }
                    PengramVoiceChanger.renderAmbiencePreviewChunk(
                            pcm, chunkSamples, rate, amb, PengramConfig.getVoiceAmbienceLevel(),
                            written, totalSamples, fadeSamples);
                    final int rc = track.write(pcm, 0, chunkSamples);
                    if (rc <= 0) {
                        break;
                    }
                    written += rc;
                }
            } catch (Throwable ignore) {
            } finally {
                running = false;
                workerThread = null;
                if (track != null) {
                    try {
                        track.pause();
                        track.flush();
                        track.release();
                    } catch (Throwable ignore) {
                    }
                }
                if (onStateChanged != null) {
                    AndroidUtilities.runOnUIThread(onStateChanged);
                }
            }
        }, "pengram-ambience-preview");
        worker.setDaemon(true);
        workerThread = worker;
        worker.start();
    }
}
