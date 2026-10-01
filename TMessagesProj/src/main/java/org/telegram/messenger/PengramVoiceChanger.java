package org.telegram.messenger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.security.SecureRandom;

/**
 * Pengram: потоковое изменение голоса для голосовых сообщений и видеокружков.
 *
 * Работает прямо на PCM 16-bit mono, который AudioRecord отдаёт перед кодированием в opus,
 * поэтому не требует нативного кода и не ломает запись — при выключенном эффекте буфер
 * вообще не трогается. Длина выходного блока всегда равна длине входного, так что
 * длительность голосового и расчёт амплитуд остаются прежними.
 *
 * Цепочка: гранулярный питч-шифтер -> формантный фильтр -> эффект режима -> лимитер.
 *
 * Отдельная история — режим «Аноним». Он не просто меняет тон: все параметры
 * (сдвиг тона, формантная окраска, микро-таймварп, фазовое скремблирование и шумовая
 * подложка) выбираются криптослучайно на каждую запись и ещё и плавают во времени.
 * Никакой обратной обработкой оригинал не восстановить: кривая модуляции нигде не
 * сохраняется, а часть тонкой структуры сигнала необратимо затирается.
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
    public static final int MODE_ANONYMOUS = 9;   // необратимая анонимизация
    public static final int MODE_DEMON = 10;      // демон
    public static final int MODE_ALIEN = 11;      // инопланетянин
    public static final int MODE_RADIO = 12;      // рация
    public static final int MODE_CAVE = 13;       // пещера
    public static final int MODE_UNDERWATER = 14; // под водой
    public static final int MODE_WHISPER = 15;    // шёпот
    public static final int MODE_PHONE = 16;      // телефонная линия

    /** порядок режимов в настройках */
    public static final int[] MODES = {
            MODE_OFF,
            MODE_ANONYMOUS,
            MODE_FEMALE,
            MODE_MALE,
            MODE_CHILD,
            MODE_HELIUM,
            MODE_DEEP,
            MODE_MONSTER,
            MODE_DEMON,
            MODE_ROBOT,
            MODE_ALIEN,
            MODE_RADIO,
            MODE_PHONE,
            MODE_CAVE,
            MODE_UNDERWATER,
            MODE_WHISPER,
            MODE_CUSTOM
    };

    private static final int SAMPLE_RATE = 48000;

    private static final int DELAY_SIZE = 8192;   // ~170 мс при 48 кГц
    private static final int WINDOW = DELAY_SIZE / 2;

    private static final short[] delay = new short[DELAY_SIZE];
    private static int writePos;
    private static float readPhase;
    private static boolean initialized;

    // --- общее состояние эффектов ---
    private static double lfoPhase;      // модуляция (робот, НЛО, вода)
    private static double lfo2Phase;
    private static final int ECHO_SIZE = 48000;  // 1 с
    private static final float[] echo = new float[ECHO_SIZE];
    private static int echoPos;
    private static final Biquad formant1 = new Biquad();
    private static final Biquad formant2 = new Biquad();
    private static final Biquad bandLow = new Biquad();
    private static final Biquad bandHigh = new Biquad();
    private static float lowpassState;
    private static float highpassState;
    private static float highpassPrev;
    private static float limiterGain = 1f;
    private static float dryEnv;
    private static float wetEnv;
    private static float makeupGain = 1f;

    // --- состояние анонимайзера (новое на каждую запись) ---
    private static final SecureRandom secureRandom = new SecureRandom();
    private static float anonPitch = 1f;          // базовый сдвиг
    private static float anonPitchTarget = 1f;    // куда плывём
    private static float anonPitchNow = 1f;
    private static int anonDriftLeft;
    private static float anonFormant1Freq, anonFormant2Freq, anonFormant1Gain, anonFormant2Gain;
    private static double anonJitterPhase, anonJitterSpeed, anonJitter2Phase, anonJitter2Speed;
    private static float anonNoise;
    private static float anonMakeup = 1f;
    private static float anonTilt;
    private static final Allpass anonAllpass1 = new Allpass(311);
    private static final Allpass anonAllpass2 = new Allpass(523);
    private static float anonAllpassMix;
    private static long noiseSeed = 88172645463325252L;

    /** сбрасываем состояние перед каждой новой записью */
    public static synchronized void reset() {
        java.util.Arrays.fill(delay, (short) 0);
        java.util.Arrays.fill(echo, 0f);
        writePos = 0;
        readPhase = 0;
        echoPos = 0;
        lfoPhase = 0;
        lfo2Phase = 0;
        lowpassState = 0;
        highpassState = 0;
        highpassPrev = 0;
        limiterGain = 1f;
        dryEnv = 0;
        wetEnv = 0;
        makeupGain = 1f;
        envelopeState = 0;
        formant1.reset();
        formant2.reset();
        bandLow.reset();
        bandHigh.reset();
        anonAllpass1.reset();
        anonAllpass2.reset();
        randomizeAnonymous();
        initialized = true;
    }

    /**
     * Каждая запись получает свой случайный «отпечаток»: тон, форманты, дрожание,
     * фазовое скремблирование и уровень шумовой подложки. Параметры нигде не
     * сохраняются, поэтому восстановить исходник обратной обработкой нельзя.
     */
    private static void randomizeAnonymous() {
        final boolean up = secureRandom.nextBoolean();
        // ±(2.6 … 5.2) полутона — слышно другого человека, но речь остаётся разборчивой
        final float semitones = 2.6f + secureRandom.nextFloat() * 2.6f;
        anonPitch = (float) Math.pow(2.0, (up ? semitones : -semitones) / 12.0);
        anonPitchTarget = anonPitch;
        anonPitchNow = anonPitch;
        anonDriftLeft = 0;

        // формантная окраска «уводит» тембр в сторону от оригинала
        anonFormant1Freq = 420f + secureRandom.nextFloat() * 520f;
        anonFormant2Freq = 1500f + secureRandom.nextFloat() * 1400f;
        anonFormant1Gain = (secureRandom.nextBoolean() ? 1f : -1f) * (3.5f + secureRandom.nextFloat() * 3.5f);
        // второй резонанс всегда в противофазе первому: тембр меняется, а общая громкость — нет
        anonFormant2Gain = (anonFormant1Gain > 0 ? -1f : 1f) * (3f + secureRandom.nextFloat() * 4f);
        anonMakeup = (float) Math.pow(10.0, -(anonFormant1Gain + anonFormant2Gain) / 40.0);
        if (anonMakeup > 1.25f) {
            anonMakeup = 1.25f;
        } else if (anonMakeup < 0.8f) {
            anonMakeup = 0.8f;
        }
        anonTilt = -0.25f + secureRandom.nextFloat() * 0.5f;

        // микро-таймварп: две несинхронные медленные волны
        anonJitterPhase = secureRandom.nextFloat() * Math.PI * 2;
        anonJitterSpeed = 2.0 * Math.PI * (0.18 + secureRandom.nextFloat() * 0.22) / SAMPLE_RATE;
        anonJitter2Phase = secureRandom.nextFloat() * Math.PI * 2;
        anonJitter2Speed = 2.0 * Math.PI * (0.55 + secureRandom.nextFloat() * 0.5) / SAMPLE_RATE;

        anonNoise = 0.0016f + secureRandom.nextFloat() * 0.0016f;
        anonAllpassMix = 0.35f + secureRandom.nextFloat() * 0.3f;
        noiseSeed = secureRandom.nextLong() | 1L;

        formant1.setPeaking(SAMPLE_RATE, anonFormant1Freq, 1.1f, anonFormant1Gain);
        formant2.setPeaking(SAMPLE_RATE, anonFormant2Freq, 1.3f, anonFormant2Gain);
    }

    public static boolean isEnabled() {
        return PengramConfig.getVoiceChangerMode() != MODE_OFF;
    }

    public static boolean isAnonymous() {
        return PengramConfig.getVoiceChangerMode() == MODE_ANONYMOUS;
    }

    /** коэффициент изменения высоты тона для текущего режима */
    public static float getPitchFactor() {
        return getPitchFactor(PengramConfig.getVoiceChangerMode());
    }

    public static float getPitchFactor(int mode) {
        switch (mode) {
            case MODE_HELIUM: return 1.80f;
            case MODE_CHILD: return 1.45f;
            case MODE_FEMALE: return 1.20f;
            case MODE_MALE: return 0.84f;
            case MODE_DEEP: return 0.70f;
            case MODE_MONSTER: return 0.58f;
            case MODE_DEMON: return 0.52f;
            case MODE_ALIEN: return 1.32f;
            case MODE_WHISPER: return 1.04f;
            case MODE_UNDERWATER: return 0.92f;
            case MODE_ANONYMOUS: return anonPitch;
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

            final float basePitch = getPitchFactor(mode);
            final boolean anonymous = mode == MODE_ANONYMOUS;

            for (int i = 0; i < count; ++i) {
                final short in = shorts.get(i);

                delay[writePos] = in;
                writePos = (writePos + 1) % DELAY_SIZE;

                float pitch = basePitch;
                if (anonymous) {
                    pitch = anonymousPitch();
                }

                float out;
                if (Math.abs(pitch - 1f) < 0.0005f) {
                    out = in;
                } else {
                    readPhase += (pitch - 1f);
                    if (readPhase >= WINDOW) {
                        readPhase -= WINDOW;
                    } else if (readPhase < 0) {
                        readPhase += WINDOW;
                    }

                    final float head1 = readPhase;
                    final float head2 = readPhase + WINDOW / 2f >= WINDOW
                            ? readPhase + WINDOW / 2f - WINDOW
                            : readPhase + WINDOW / 2f;

                    // окна равной мощности (sin/cos): нет ни щелчков, ни провала громкости
                    final float theta = (float) (Math.PI * head1 / WINDOW);
                    final float gain1 = (float) Math.sin(theta);
                    final float gain2 = Math.abs((float) Math.cos(theta));

                    out = read(head1) * gain1 + read(head2) * gain2;
                    out = makeup(out, in);
                }

                out = applyMode(mode, out, in);
                out = limit(out);
                shorts.put(i, (short) out);
            }

            buffer.limit(oldLimit);
            buffer.position(oldPosition);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Гранулярный сдвиг неизбежно немного «съедает» громкость из-за гребенчатой интерференции
     * двух головок. Медленный АРУ возвращает уровень к исходному, не создавая накачки.
     */
    private static float makeup(float wet, float dry) {
        dryEnv += (Math.abs(dry) - dryEnv) * 0.0015f;
        wetEnv += (Math.abs(wet) - wetEnv) * 0.0015f;
        if (wetEnv > 1f) {
            float gain = dryEnv / wetEnv;
            if (gain > 2f) {
                gain = 2f;
            } else if (gain < 1f) {
                gain = 1f;
            }
            makeupGain += (gain - makeupGain) * 0.002f;
            return wet * makeupGain;
        }
        return wet;
    }

    /** плавающий сдвиг тона для анонимного режима */
    private static float anonymousPitch() {
        if (--anonDriftLeft <= 0) {
            // новая цель каждые 120…320 мс, отклонение до ±0.7 полутона от базы
            anonDriftLeft = (int) (SAMPLE_RATE * (0.12f + secureRandom.nextFloat() * 0.2f));
            final float deviation = (secureRandom.nextFloat() - 0.5f) * 1.4f;
            anonPitchTarget = anonPitch * (float) Math.pow(2.0, deviation / 12.0);
        }
        anonPitchNow += (anonPitchTarget - anonPitchNow) * 0.00008f;

        // микро-таймварп ±1.2%: ломает любую попытку «выровнять» запись обратно
        anonJitterPhase += anonJitterSpeed;
        anonJitter2Phase += anonJitter2Speed;
        final float warp = 1f + (float) (Math.sin(anonJitterPhase) * 0.008 + Math.sin(anonJitter2Phase) * 0.004);
        return anonPitchNow * warp;
    }

    private static float applyMode(int mode, float x, float dry) {
        switch (mode) {
            case MODE_ANONYMOUS: {
                // формантная окраска + фазовое скремблирование + шумовая подложка
                x = formant1.process(x);
                x = formant2.process(x);
                if (anonTilt > 0) {
                    x = x + anonTilt * highpass(x, 2600f);
                } else {
                    x = x + (-anonTilt) * lowpass(x, 1800f);
                }
                final float scrambled = anonAllpass2.process(anonAllpass1.process(x));
                x = x * (1f - anonAllpassMix) + scrambled * anonAllpassMix;
                x += noise() * anonNoise * 32768f;
                return x * anonMakeup;
            }
            case MODE_ROBOT: {
                lfoPhase += 2.0 * Math.PI * 75.0 / SAMPLE_RATE;
                final float mod = (float) (0.55 + 0.45 * Math.cos(lfoPhase));
                return x * mod;
            }
            case MODE_MONSTER: {
                lfoPhase += 2.0 * Math.PI * 32.0 / SAMPLE_RATE;
                final float mod = (float) (0.6 + 0.4 * Math.cos(lfoPhase));
                return clip(x * mod * 1.25f, 26000f);
            }
            case MODE_DEMON: {
                lfoPhase += 2.0 * Math.PI * 24.0 / SAMPLE_RATE;
                lfo2Phase += 2.0 * Math.PI * 7.0 / SAMPLE_RATE;
                final float growl = (float) (0.62 + 0.38 * Math.cos(lfoPhase)) * (float) (0.85 + 0.15 * Math.cos(lfo2Phase));
                final float distorted = (float) Math.tanh(x * growl / 9000f) * 9000f;
                final float demonEcho = echoRead(2600);
                echoWrite(distorted + demonEcho * 0.28f);
                return distorted + demonEcho * 0.45f;
            }
            case MODE_ALIEN: {
                lfoPhase += 2.0 * Math.PI * 180.0 / SAMPLE_RATE;
                final float ring = (float) Math.cos(lfoPhase);
                final float mixed = x * 0.55f + x * ring * 0.45f;
                final float alienEcho = echoRead(1100);
                echoWrite(mixed + alienEcho * 0.22f);
                return mixed + alienEcho * 0.35f;
            }
            case MODE_RADIO: {
                final float band = highpass(lowpass(x, 2900f), 420f);
                final float driven = (float) Math.tanh(band / 5200f) * 5200f;
                return driven + noise() * 260f;
            }
            case MODE_PHONE: {
                final float band = highpass(lowpass(x, 3400f), 330f);
                return band * 1.25f;
            }
            case MODE_CAVE: {
                final float tap1 = echoRead(7200);
                final float tap2 = echoRead(13100);
                echoWrite(x + tap1 * 0.42f + tap2 * 0.24f);
                return x * 0.72f + (tap1 * 0.5f + tap2 * 0.32f);
            }
            case MODE_UNDERWATER: {
                lfoPhase += 2.0 * Math.PI * 1.6 / SAMPLE_RATE;
                final float wobble = (float) (0.88 + 0.12 * Math.cos(lfoPhase));
                final float muffled = lowpass(x, 950f);
                final float waterEcho = echoRead(2200);
                echoWrite(muffled + waterEcho * 0.2f);
                return muffled * wobble + waterEcho * 0.3f;
            }
            case MODE_WHISPER: {
                // заменяем голосовой тон шумом, оставляя огибающую — узнать голос нельзя
                final float env = envelope(Math.abs(x));
                final float breath = noise() * env * 1.35f;
                return highpass(breath, 700f) * 1.2f + x * 0.12f;
            }
            default:
                return x;
        }
    }

    // ------------------------------------------------------------- утилиты DSP

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

    /** чтение отвода линии задержки (позиция не двигается) */
    private static float echoRead(int samples) {
        if (samples <= 0 || samples >= ECHO_SIZE) {
            return 0;
        }
        int readIndex = echoPos - samples;
        if (readIndex < 0) {
            readIndex += ECHO_SIZE;
        }
        return echo[readIndex];
    }

    /** запись в линию задержки — ровно один раз на сэмпл */
    private static void echoWrite(float value) {
        echo[echoPos] = value;
        echoPos = (echoPos + 1) % ECHO_SIZE;
    }

    private static float lowpass(float x, float cutoff) {
        final float a = (float) (1.0 - Math.exp(-2.0 * Math.PI * cutoff / SAMPLE_RATE));
        lowpassState += a * (x - lowpassState);
        return lowpassState;
    }

    private static float highpass(float x, float cutoff) {
        final float rc = 1f / (2f * (float) Math.PI * cutoff);
        final float dt = 1f / SAMPLE_RATE;
        final float a = rc / (rc + dt);
        highpassState = a * (highpassState + x - highpassPrev);
        highpassPrev = x;
        return highpassState;
    }

    private static float envelopeState;

    private static float envelope(float x) {
        final float attack = 0.01f;
        final float release = 0.0012f;
        if (x > envelopeState) {
            envelopeState += (x - envelopeState) * attack;
        } else {
            envelopeState += (x - envelopeState) * release;
        }
        return envelopeState;
    }

    /** быстрый xorshift-шум в диапазоне [-1, 1] */
    private static float noise() {
        noiseSeed ^= noiseSeed << 13;
        noiseSeed ^= noiseSeed >>> 7;
        noiseSeed ^= noiseSeed << 17;
        return (noiseSeed >> 40) / 8388608f;
    }

    private static float clip(float x, float level) {
        if (x > level) return level;
        if (x < -level) return -level;
        return x;
    }

    /** мягкий лимитер: ничего не трещит даже на агрессивных режимах */
    private static float limit(float x) {
        final float peak = Math.abs(x);
        final float ceiling = 30000f;
        if (peak * limiterGain > ceiling) {
            limiterGain = ceiling / peak;
        } else {
            limiterGain += (1f - limiterGain) * 0.0008f;
        }
        float out = x * limiterGain;
        if (out > 32767f) {
            out = 32767f;
        } else if (out < -32768f) {
            out = -32768f;
        }
        return out;
    }

    /** биквад: пиковый фильтр для формант */
    private static class Biquad {
        private float b0 = 1, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        void reset() {
            x1 = x2 = y1 = y2 = 0;
        }

        void setPeaking(int sampleRate, float freq, float q, float gainDb) {
            final double A = Math.pow(10, gainDb / 40.0);
            final double w0 = 2 * Math.PI * freq / sampleRate;
            final double alpha = Math.sin(w0) / (2 * q);
            final double cosw = Math.cos(w0);
            final double a0 = 1 + alpha / A;
            b0 = (float) ((1 + alpha * A) / a0);
            b1 = (float) ((-2 * cosw) / a0);
            b2 = (float) ((1 - alpha * A) / a0);
            a1 = (float) ((-2 * cosw) / a0);
            a2 = (float) ((1 - alpha / A) / a0);
            reset();
        }

        float process(float x) {
            final float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    /** фазовый фильтр — меняет фазу, не трогая громкость; хорош для скремблирования */
    private static class Allpass {
        private final float[] buffer;
        private int pos;
        private final float coefficient = 0.68f;

        Allpass(int size) {
            buffer = new float[size];
        }

        void reset() {
            java.util.Arrays.fill(buffer, 0f);
            pos = 0;
        }

        float process(float x) {
            final float delayed = buffer[pos];
            final float out = -coefficient * x + delayed;
            buffer[pos] = x + coefficient * out;
            pos = (pos + 1) % buffer.length;
            return out;
        }
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
            case MODE_ANONYMOUS: return LocaleController.getString(R.string.PengramVoiceAnonymous);
            case MODE_DEMON: return LocaleController.getString(R.string.PengramVoiceDemon);
            case MODE_ALIEN: return LocaleController.getString(R.string.PengramVoiceAlien);
            case MODE_RADIO: return LocaleController.getString(R.string.PengramVoiceRadio);
            case MODE_PHONE: return LocaleController.getString(R.string.PengramVoicePhone);
            case MODE_CAVE: return LocaleController.getString(R.string.PengramVoiceCave);
            case MODE_UNDERWATER: return LocaleController.getString(R.string.PengramVoiceUnderwater);
            case MODE_WHISPER: return LocaleController.getString(R.string.PengramVoiceWhisper);
            default: return LocaleController.getString(R.string.PengramVoiceOff);
        }
    }
}
