package org.telegram.messenger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Pengram: потоковое изменение голоса для голосовых сообщений.
 *
 * Работает прямо на PCM 16-bit mono, который AudioRecord отдаёт перед кодированием в opus,
 * поэтому не требует нативного кода и не ломает запись — при выключенном эффекте буфер
 * вообще не трогается.
 *
 * Алгоритм: классический гранулярный питч-шифтер (линия задержки + две считывающие головки
 * со встречным треугольным кроссфейдом). Длина выходного блока всегда равна длине входного,
 * поэтому длительность голосового и расчёт амплитуд остаются прежними.
 */
public class PengramVoiceChanger {

    public static final int MODE_OFF = 0;
    public static final int MODE_HELIUM = 1;      // очень высокий
    public static final int MODE_CHILD = 2;       // ребёнок
    public static final int MODE_FEMALE = 3;      // женский
    public static final int MODE_MALE = 4;        // мужской
    public static final int MODE_DEEP = 5;        // очень низкий
    public static final int MODE_MONSTER = 6;     // монстр
    public static final int MODE_ROBOT = 7;       // робот
    public static final int MODE_CUSTOM = 8;      // свой сдвиг в полутонах

    private static final int DELAY_SIZE = 8192;   // ~170 мс при 48 кГц
    private static final int WINDOW = DELAY_SIZE / 2;

    private static final short[] delay = new short[DELAY_SIZE];
    private static int writePos;
    private static float readPhase;
    private static double robotPhase;
    private static boolean initialized;

    /** сбрасываем состояние перед каждой новой записью */
    public static synchronized void reset() {
        java.util.Arrays.fill(delay, (short) 0);
        writePos = 0;
        readPhase = 0;
        robotPhase = 0;
        initialized = true;
    }

    public static boolean isEnabled() {
        return PengramConfig.getVoiceChangerMode() != MODE_OFF;
    }

    /** коэффициент изменения высоты тона для текущего режима */
    public static float getPitchFactor() {
        final int mode = PengramConfig.getVoiceChangerMode();
        switch (mode) {
            case MODE_HELIUM: return 1.80f;
            case MODE_CHILD: return 1.45f;
            case MODE_FEMALE: return 1.20f;
            case MODE_MALE: return 0.84f;
            case MODE_DEEP: return 0.70f;
            case MODE_MONSTER: return 0.58f;
            case MODE_ROBOT: return 1.0f;
            case MODE_CUSTOM: return semitonesToFactor(PengramConfig.getVoiceChangerPitch());
            default: return 1.0f;
        }
    }

    public static float semitonesToFactor(int semitones) {
        return (float) Math.pow(2.0, semitones / 12.0);
    }

    /**
     * Обрабатывает PCM на месте.
     *
     * @param buffer прямой ByteBuffer с 16-битными сэмплами (моно)
     * @param len    количество валидных байт
     */
    public static synchronized void process(ByteBuffer buffer, int len) {
        final int mode = PengramConfig.getVoiceChangerMode();
        if (mode == MODE_OFF || buffer == null || len < 2) {
            return;
        }
        try {
            if (!initialized) {
                reset();
            }
            final int oldPosition = buffer.position();
            final int oldLimit = buffer.limit();
            buffer.order(ByteOrder.nativeOrder());
            buffer.position(0);
            buffer.limit(len);
            final ShortBuffer shorts = buffer.asShortBuffer();
            final int count = shorts.limit();

            final float pitch = getPitchFactor();
            final boolean robot = mode == MODE_ROBOT || mode == MODE_MONSTER;
            final int sampleRate = 48000;
            final double robotStep = 2.0 * Math.PI * (mode == MODE_ROBOT ? 75.0 : 35.0) / sampleRate;

            for (int i = 0; i < count; ++i) {
                short in = shorts.get(i);

                delay[writePos] = in;
                writePos = (writePos + 1) % DELAY_SIZE;

                float out;
                if (Math.abs(pitch - 1f) < 0.001f) {
                    out = in;
                } else {
                    readPhase += (pitch - 1f);
                    if (readPhase >= WINDOW) {
                        readPhase -= WINDOW;
                    } else if (readPhase < 0) {
                        readPhase += WINDOW;
                    }

                    final float head1 = readPhase;
                    final float head2 = readPhase + WINDOW / 2f >= WINDOW ? readPhase + WINDOW / 2f - WINDOW : readPhase + WINDOW / 2f;

                    // треугольные окна, чтобы не было щелчков на стыках гранул
                    final float gain1 = 1f - Math.abs(head1 - WINDOW / 2f) / (WINDOW / 2f);
                    final float gain2 = 1f - Math.abs(head2 - WINDOW / 2f) / (WINDOW / 2f);
                    final float sum = gain1 + gain2 < 0.0001f ? 1f : gain1 + gain2;

                    out = (read(head1) * gain1 + read(head2) * gain2) / sum;
                }

                if (robot) {
                    robotPhase += robotStep;
                    if (robotPhase > 2.0 * Math.PI) {
                        robotPhase -= 2.0 * Math.PI;
                    }
                    final float modulation = (float) (0.55 + 0.45 * Math.cos(robotPhase));
                    out *= modulation;
                }

                if (out > 32767f) {
                    out = 32767f;
                } else if (out < -32768f) {
                    out = -32768f;
                }
                shorts.put(i, (short) out);
            }

            buffer.limit(oldLimit);
            buffer.position(oldPosition);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** линейная интерполяция по линии задержки, head — задержка в сэмплах от головы записи */
    private static float read(float head) {
        float pos = writePos - WINDOW + head;
        while (pos < 0) {
            pos += DELAY_SIZE;
        }
        while (pos >= DELAY_SIZE) {
            pos -= DELAY_SIZE;
        }
        final int i0 = (int) pos;
        final int i1 = (i0 + 1) % DELAY_SIZE;
        final float frac = pos - i0;
        return delay[i0] * (1f - frac) + delay[i1] * frac;
    }

    public static String getModeName(int mode) {
        switch (mode) {
            case MODE_HELIUM: return LocaleController.getString(R.string.PengramVoiceHelium);
            case MODE_CHILD: return LocaleController.getString(R.string.PengramVoiceChild);
            case MODE_FEMALE: return LocaleController.getString(R.string.PengramVoiceFemale);
            case MODE_MALE: return LocaleController.getString(R.string.PengramVoiceMale);
            case MODE_DEEP: return LocaleController.getString(R.string.PengramVoiceDeep);
            case MODE_MONSTER: return LocaleController.getString(R.string.PengramVoiceMonster);
            case MODE_ROBOT: return LocaleController.getString(R.string.PengramVoiceRobot);
            case MODE_CUSTOM: return LocaleController.getString(R.string.PengramVoiceCustom);
            default: return LocaleController.getString(R.string.PengramVoiceOff);
        }
    }
}
