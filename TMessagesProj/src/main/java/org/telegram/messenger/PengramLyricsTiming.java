package org.telegram.messenger;

/** Pure timing decisions for synced lyrics; no player or Android dependency. */
public final class PengramLyricsTiming {
    private PengramLyricsTiming() {}

    /** Map track time onto the original LRC clock. A declared [length:] is
     * authoritative; without one, the last timed syllable is only an estimate.
     * Never stretch plain text or an implausibly incomplete transcript. */
    public static float scale(boolean enabled, boolean timed, long trackMs,
                              long declaredMs, long lastTimedMs) {
        if (!enabled || !timed || trackMs <= 30000) return 1f;
        final boolean declared = declaredMs > 30000;
        if (!declared && (lastTimedMs < 30000 || lastTimedMs < trackMs * 0.4)) return 1f;
        final long sourceMs = declared ? declaredMs : lastTimedMs + 3000;
        final float ratio = trackMs / (float) sourceMs;
        if (ratio < 0.25f || ratio > 4f || Math.abs(ratio - 1f) < (declared ? 0.02f : 0.12f)) {
            return 1f;
        }
        return ratio;
    }

    /** Telegram reports music progress every 17 ms. Merely stopping our own
     * interpolation would still look smooth; the off mode must visibly hold
     * each highlight frame until the next half-second step. */
    public static long sample(long playerMs, boolean smooth, boolean timed) {
        return !smooth && timed && playerMs > 0 ? playerMs / 500 * 500 : playerMs;
    }

    /** With smoothing disabled, the timestamp never advances between player
     * progress events. With it enabled, interpolate at the actual playback rate. */
    public static long position(long sampleMs, long sampledAtMs, long nowMs,
                                boolean smooth, boolean playing, float speed) {
        if (!smooth || !playing || sampledAtMs <= 0 || nowMs <= sampledAtMs) return sampleMs;
        final long elapsed = nowMs - sampledAtMs;
        if (elapsed >= 3000) return sampleMs; // stale / suspended player
        final float safeSpeed = !Float.isNaN(speed) && !Float.isInfinite(speed) && speed > 0 && speed <= 4 ? speed : 1f;
        return sampleMs + (long) (elapsed * safeSpeed);
    }

    public static float seekFraction(long lyricTimeMs, float scale, long trackMs) {
        if (trackMs <= 0) return 0;
        final float value = lyricTimeMs * scale / trackMs;
        return Math.max(0, Math.min(1, value));
    }
}
