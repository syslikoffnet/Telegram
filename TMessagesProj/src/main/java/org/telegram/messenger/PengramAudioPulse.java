package org.telegram.messenger;

import java.util.ArrayList;

/**
 * Pengram: пульс музыки.
 *
 * Плеер уже считает спектр для волны голосовых сообщений — те же самые числа
 * прекрасно годятся, чтобы под музыку жила картинка. Здесь они превращаются
 * в две понятные величины: ровный «уровень» (насколько сейчас громко) и
 * отдельные «удары» — моменты, когда бас резко выпрыгивает над своим средним.
 *
 * Считать удары по среднему, а не по порогу, важно: тихий лоуфай и громкий
 * драм-н-бейс иначе вели бы себя совершенно по-разному, и у одного картинка
 * стояла бы столбом, а у другого тряслась без остановки.
 */
public final class PengramAudioPulse {

    public interface Listener {
        /** level — общая громкость 0..1, bass — низы 0..1, оба уже сглажены */
        void onPulse(float level, float bass);

        /** удар: power 0..1 — насколько он сильнее обычного */
        void onBeat(float power);
    }

    /** тише этого считаем, что музыка молчит: микрошум не должен дёргать картинку */
    private static final float SILENCE = 0.06f;
    /** во сколько раз бас должен превысить среднее, чтобы это считалось ударом */
    private static final float BEAT_FACTOR = 1.38f;
    /** чаще этого удары не бывают даже в быстром треке — защита от дребезга */
    private static final long BEAT_COOLDOWN = 170L;

    private static final ArrayList<Listener> listeners = new ArrayList<>();

    private static volatile boolean listening;
    private static float level;
    private static float bass;
    private static float bassAverage;
    private static long lastBeat;

    private PengramAudioPulse() {
    }

    /** слушает ли сейчас кто-нибудь: от этого зависит, считать ли спектр вообще */
    public static boolean isListening() {
        return listening;
    }

    public static void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
            listening = !listeners.isEmpty();
        }
    }

    public static void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
            listening = !listeners.isEmpty();
            if (!listening) {
                level = 0;
                bass = 0;
                bassAverage = 0;
            }
        }
    }

    /**
     * Новая порция спектра из плеера.
     *
     * values[0..5] — полосы от низов к верхам, values[6] — общая громкость.
     * Вызывается примерно каждые 64 мс, поэтому сглаживание держим мягким:
     * резкие скачки сделали бы анимацию дёрганой, а слишком вязкое — ватной.
     */
    public static void update(boolean playing, float[] values) {
        if (!listening) {
            return;
        }
        if (!playing || values == null || values.length < 7) {
            fade();
            return;
        }
        final float loudness = clamp(values[6]);
        final float low = clamp(Math.max(values[0], values[1]));

        level = level * 0.65f + loudness * 0.35f;
        bass = bass * 0.5f + low * 0.5f;

        if (bassAverage == 0) {
            bassAverage = low;
        } else {
            bassAverage = bassAverage * 0.92f + low * 0.08f;
        }

        float beat = 0;
        final long now = android.os.SystemClock.elapsedRealtime();
        if (low > SILENCE && bassAverage > 0.001f
                && low > bassAverage * BEAT_FACTOR
                && now - lastBeat > BEAT_COOLDOWN) {
            lastBeat = now;
            beat = clamp((low - bassAverage) / Math.max(0.12f, bassAverage));
        }
        dispatch(level, bass, beat);
    }

    /** музыка встала: гасим пульс плавно, чтобы картинка не замирала рывком */
    public static void fade() {
        if (level < 0.005f && bass < 0.005f) {
            return;
        }
        level *= 0.7f;
        bass *= 0.7f;
        dispatch(level, bass, 0);
    }

    private static void dispatch(float level, float bass, float beat) {
        final Listener[] copy;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                return;
            }
            copy = listeners.toArray(new Listener[0]);
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener listener : copy) {
                try {
                    listener.onPulse(level, bass);
                    if (beat > 0) {
                        listener.onBeat(beat);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    private static float clamp(float value) {
        if (value < 0 || Float.isNaN(value)) {
            return 0;
        }
        return value > 1f ? 1f : value;
    }
}
