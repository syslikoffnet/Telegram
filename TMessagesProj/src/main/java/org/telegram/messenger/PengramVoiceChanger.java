package org.telegram.messenger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;

/**
 * Pengram: потоковое изменение голоса для голосовых сообщений и видеокружков.
 *
 * Работает прямо на PCM 16-bit mono, который AudioRecord отдаёт перед кодированием в opus,
 * поэтому не требует нативного кода и не ломает запись — при выключенном эффекте буфер
 * вообще не трогается. Длина выходного блока всегда равна длине входного, так что
 * длительность голосового и расчёт амплитуд остаются прежними.
 *
 * Цепочка: гранулярный питч-шифтер -> формантный фильтр -> эффект режима -> усиление -> перегруз -> лимитер.
 *
 * Отдельная история — режим «Аноним». Он не просто меняет тон: все параметры
 * (сдвиг тона, формантная окраска, микро-таймварп, фазовое скремблирование и шумовая
 * подложка) выбираются криптослучайно на каждую запись и ещё и плавают во времени.
 * Эффект меняет сигнал, но не обещает абсолютной анонимности: голос может
 * распознаваться по манере речи и другим признакам.
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
    // Original, locally synthesized timbres. These do not impersonate an actor
    // and are not neural voice conversion models.
    public static final int MODE_ASSISTANT = 17;  // clean electronic assistant
    public static final int MODE_ARMOR = 18;      // cinematic helmet comms
    public static final int MODE_TROLLING = 19;   // loud, intelligible conference speech

    // ---- фоновый шум (Ambience / Soundboard) для голосовых, кружочков и звонков ----
    public static final int AMBIENCE_OFF = 0;
    public static final int AMBIENCE_SUBWAY = 1;  // метро
    public static final int AMBIENCE_STREET = 2;  // улица
    public static final int AMBIENCE_RAIN = 3;    // дождь
    public static final int AMBIENCE_OFFICE = 4;  // офис
    public static final int AMBIENCE_RADIO = 5;   // помехи рации
    public static final int AMBIENCE_CLUB = 6;    // клуб

    public static final int[] AMBIENCES = {
            AMBIENCE_OFF,
            AMBIENCE_SUBWAY,
            AMBIENCE_STREET,
            AMBIENCE_RAIN,
            AMBIENCE_OFFICE,
            AMBIENCE_RADIO,
            AMBIENCE_CLUB
    };

    /** порядок режимов в настройках */
    public static final int[] MODES = {
            MODE_OFF,
            MODE_TROLLING,
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
            MODE_ASSISTANT,
            MODE_ARMOR,
            MODE_CAVE,
            MODE_UNDERWATER,
            MODE_WHISPER,
            MODE_CUSTOM
    };

    /** реальная частота записи: приходит из MediaController, по умолчанию 48 кГц */
    private static int sampleRate = 48000;

    private static final int DELAY_SIZE = 8192;
    private static final int DELAY_MASK = DELAY_SIZE - 1; // power-of-two ring
    // A ~32 ms grain at any supported sample rate: avoids the old 256 ms
    // pitch window at 16 kHz Bluetooth input without shortening the word.
    private static int window = 1536;
    private static final int WINDOW_TABLE_SIZE = 2048;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double INVERSE_TWO_PI = 1.0 / TWO_PI;
    private static final double LFO_TABLE_SCALE = WINDOW_TABLE_SIZE / TWO_PI;
    private static float pitchWindowScale = (float) WINDOW_TABLE_SIZE / window;
    private static final float[] windowSin = new float[WINDOW_TABLE_SIZE + 1];
    private static final float[] windowCos = new float[WINDOW_TABLE_SIZE + 1];
    private static final float[] lfoSin = new float[WINDOW_TABLE_SIZE + 1];
    static {
        for (int i = 0; i <= WINDOW_TABLE_SIZE; i++) {
            lfoSin[i] = (float) Math.sin(2.0 * Math.PI * i / WINDOW_TABLE_SIZE);
            double angle = Math.PI * i / WINDOW_TABLE_SIZE;
            windowSin[i] = (float) Math.sin(angle);
            windowCos[i] = Math.abs((float) Math.cos(angle));
        }
    }
    private static float lp950, lp1800, lp2900, lp3400;
    private static float hp330, hp420, hp700, hp2600;

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
    public static final int SOURCE_VOICE = 1;
    public static final int SOURCE_ROUND = 2;
    public static final int SOURCE_CALL = 3;
    private static int activeSource;
    private static long lastSourceFrame;
    private static int lastMode = MODE_OFF;
    private static int slowFrames, overloadStrikes;
    private static volatile boolean overloaded, disabledForSession;
    private static long retryAfter;
    private static float gateGain = 1f;
    private static int frameFormant, frameEcho, frameDrive, frameRobot;
    private static float frameMix, frameDriveGain, frameDriveScale;
    private static float limiterGain = 1f;
    private static float dryEnv;
    private static float wetEnv;
    private static float makeupGain = 1f;
    // Dedicated zero-lookahead conference chain; never shares the pitch shifter
    // or the deliberately distorted microphone boost preset.
    private static float speechHp, speechHpPrevious, speechPresenceLow;
    private static float speechEnvelope, speechGain = 1f, speechGate;
    private static float speechHpCoefficient, speechPresenceCoefficient;
    private static float speechEnvelopeAttack, speechEnvelopeRelease;
    private static float speechGainAttack, speechGainRelease;
    private static float speechGateAttack, speechGateRelease;


    // --- состояние анонимайзера (новое на каждую запись) ---
    // Do not initialize SecureRandom when loading the class for an OFF call.
    private static class AnonymousRandom {
        static final SecureRandom INSTANCE = new SecureRandom();
    }
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
        window = Math.max(256, Math.min(DELAY_SIZE / 2, Math.round(sampleRate * 0.032f)));
        pitchWindowScale = (float) WINDOW_TABLE_SIZE / window;
        lp950 = lowpassCoefficient(950f);
        lp1800 = lowpassCoefficient(1800f);
        lp2900 = lowpassCoefficient(2900f);
        lp3400 = lowpassCoefficient(3400f);
        hp330 = highpassCoefficient(330f);
        hp420 = highpassCoefficient(420f);
        hp700 = highpassCoefficient(700f);
        hp2600 = highpassCoefficient(2600f);
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
        gateGain = 1f;
        // DSP reset does not lift a CPU bypass in the middle of the same call.
        dryEnv = 0;
        wetEnv = 0;
        makeupGain = 1f;
        envelopeState = 0;
        speechHp = speechHpPrevious = speechPresenceLow = speechEnvelope = speechGate = 0f;
        speechGain = 1f;
        speechHpCoefficient = highpassCoefficient(100f);
        speechPresenceCoefficient = lowpassCoefficient(1900f);
        speechEnvelopeAttack = timeCoefficient(0.008f);
        speechEnvelopeRelease = timeCoefficient(0.09f);
        speechGainAttack = timeCoefficient(0.012f);
        speechGainRelease = timeCoefficient(0.06f);
        speechGateAttack = timeCoefficient(0.003f);
        speechGateRelease = timeCoefficient(0.14f);
        formant1.reset();
        formant2.reset();
        bandLow.reset();
        bandHigh.reset();
        anonAllpass1.reset();
        anonAllpass2.reset();
        final int mode = PengramConfig.getVoiceChangerMode();
        if (mode == MODE_ANONYMOUS) {
            randomizeAnonymous();
        }
        if (mode == MODE_CUSTOM) {
            final int formant = PengramConfig.getVoiceFormant();
            formant1.setPeaking(sampleRate, 750f + formant * 35f, 1.0f, formant * 1.4f);
            formant2.setPeaking(sampleRate, 2100f + formant * 75f, 1.3f, -formant * 0.8f);
        } else if (mode == MODE_FEMALE || mode == MODE_CHILD || mode == MODE_HELIUM) {
            // Compensate for the dark resonance a time-domain pitch shift alone leaves behind.
            formant1.setPeaking(sampleRate, 650f, 1.0f, -2.0f);
            formant2.setPeaking(sampleRate, 2400f, 1.1f, mode == MODE_CHILD ? 3.5f : 2.5f);
        } else if (mode == MODE_MALE || mode == MODE_DEEP || mode == MODE_MONSTER) {
            formant1.setPeaking(sampleRate, 500f, 1.0f, 2.5f);
            formant2.setPeaking(sampleRate, 2300f, 1.1f, -2.0f);
        } else if (mode == MODE_ASSISTANT) {
            formant1.setPeaking(sampleRate, 480f, 0.85f, -2.5f);
            formant2.setPeaking(sampleRate, 2600f, 1.1f, 3.0f);
        } else if (mode == MODE_ARMOR) {
            formant1.setPeaking(sampleRate, 680f, 0.8f, 3.5f);
            formant2.setPeaking(sampleRate, 2700f, 1.2f, -3.0f);
        }
        LIVE_AMBIENCE.reset(sampleRate);
        initialized = true;
    }

    /**
     * Каждая запись получает свой случайный «отпечаток»: тон, форманты, дрожание,
     * фазовое скремблирование и уровень шумовой подложки. Параметры нигде не
     * сохраняются. Этого недостаточно для гарантии анонимности.
     */
    private static void randomizeAnonymous() {
        final boolean up = AnonymousRandom.INSTANCE.nextBoolean();
        // ±(2.6 … 5.2) полутона — слышно другого человека, но речь остаётся разборчивой
        final float semitones = 2.6f + AnonymousRandom.INSTANCE.nextFloat() * 2.6f;
        anonPitch = (float) Math.pow(2.0, (up ? semitones : -semitones) / 12.0);
        anonPitchTarget = anonPitch;
        anonPitchNow = anonPitch;
        anonDriftLeft = 0;

        // формантная окраска «уводит» тембр в сторону от оригинала
        anonFormant1Freq = 420f + AnonymousRandom.INSTANCE.nextFloat() * 520f;
        anonFormant2Freq = 1500f + AnonymousRandom.INSTANCE.nextFloat() * 1400f;
        anonFormant1Gain = (AnonymousRandom.INSTANCE.nextBoolean() ? 1f : -1f) * (3.5f + AnonymousRandom.INSTANCE.nextFloat() * 3.5f);
        // второй резонанс всегда в противофазе первому: тембр меняется, а общая громкость — нет
        anonFormant2Gain = (anonFormant1Gain > 0 ? -1f : 1f) * (3f + AnonymousRandom.INSTANCE.nextFloat() * 4f);
        anonMakeup = (float) Math.pow(10.0, -(anonFormant1Gain + anonFormant2Gain) / 40.0);
        if (anonMakeup > 1.25f) {
            anonMakeup = 1.25f;
        } else if (anonMakeup < 0.8f) {
            anonMakeup = 0.8f;
        }
        anonTilt = -0.25f + AnonymousRandom.INSTANCE.nextFloat() * 0.5f;

        // микро-таймварп: две несинхронные медленные волны
        anonJitterPhase = AnonymousRandom.INSTANCE.nextFloat() * Math.PI * 2;
        anonJitterSpeed = 2.0 * Math.PI * (0.18 + AnonymousRandom.INSTANCE.nextFloat() * 0.22) / sampleRate;
        anonJitter2Phase = AnonymousRandom.INSTANCE.nextFloat() * Math.PI * 2;
        anonJitter2Speed = 2.0 * Math.PI * (0.55 + AnonymousRandom.INSTANCE.nextFloat() * 0.5) / sampleRate;

        anonNoise = 0.0016f + AnonymousRandom.INSTANCE.nextFloat() * 0.0016f;
        anonAllpassMix = 0.35f + AnonymousRandom.INSTANCE.nextFloat() * 0.3f;
        noiseSeed = AnonymousRandom.INSTANCE.nextLong() | 1L;

        formant1.setPeaking(sampleRate, anonFormant1Freq, 1.1f, anonFormant1Gain);
        formant2.setPeaking(sampleRate, anonFormant2Freq, 1.3f, anonFormant2Gain);
    }

    public static boolean isEnabled() {
        return isEnabledFor(SOURCE_VOICE);
    }

    private static boolean isDryOnly(int mode) {
        return (mode == MODE_CUSTOM || mode == MODE_ASSISTANT || mode == MODE_ARMOR)
                && PengramConfig.getVoiceStudioMix() == 0;
    }

    public static boolean isEnabledFor(int source) {
        final int mode = PengramConfig.getVoiceChangerMode();
        return (PengramConfig.getVoiceGainDb() > 0 || PengramConfig.getVoiceGainDrive() > 0
                || PengramConfig.getVoiceAmbience() != AMBIENCE_OFF
                || mode != MODE_OFF && !isDryOnly(mode)) &&
                (source == SOURCE_VOICE && PengramConfig.isVoiceMessagesEnabled()
                        || source == SOURCE_ROUND && PengramConfig.isVoiceRoundEnabled()
                        || source == SOURCE_CALL && PengramConfig.isVoiceCallsEnabled());
    }

    /** Called once by the shared WebRTC microphone handoff (Java, OpenSL ES, or AAudio). */
    public static void processCallAudio(ByteBuffer buffer, int length, int rate) {
        processForSource(buffer, length, rate, SOURCE_CALL);
    }

    /** PCM16 mono only. A competing recorder is bypassed, never mixed into another session's state. */
    public static synchronized void processForSource(ByteBuffer buffer, int len, int rate, int source) {
        if (!isEnabledFor(source) || buffer == null || len < 2) return;
        final int mode = PengramConfig.getVoiceChangerMode();
        if (buffer.isReadOnly() || rate < 8000 || rate > 96000) {
            muteIfAnonymous(buffer, len, mode);
            return;
        }
        // Частота приходит и этим путём (микрофон звонка), а окно задержки и
        // коэффициенты считаются по ней. Без обновления здесь 16 кГц Bluetooth
        // обрабатывались бы окном от 48 кГц.
        if (rate != sampleRate) {
            sampleRate = rate;
            initialized = false;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (activeSource != source || now - lastSourceFrame > 500) {
            if (activeSource != 0 && activeSource != source && now - lastSourceFrame < 250) {
                muteIfAnonymous(buffer, len, mode);
                return;
            }
            activeSource = source;
            initialized = false;
            limiterGain = 1f;
            overloaded = disabledForSession = false;
            slowFrames = overloadStrikes = 0;
        }
        if (lastMode != mode) {
            lastMode = mode;
            initialized = false;
            limiterGain = 1f;
            overloaded = disabledForSession = false;
            slowFrames = overloadStrikes = 0;
        }
        lastSourceFrame = now;
        if (disabledForSession) {
            muteIfAnonymous(buffer, len, mode);
            return;
        }
        if (overloaded) {
            if (now < retryAfter) {
                muteIfAnonymous(buffer, len, mode);
                return;
            }
            overloaded = false;
            slowFrames = 0;
            initialized = false;
        }
        final long started = android.os.SystemClock.elapsedRealtimeNanos();
        process(buffer, len, rate);
        // Leave most of the capture interval to Telegram/WebRTC. On slow
        // hardware prefer original PCM over repeatedly stuttering the call.
        final long packetNs = 1_000_000_000L * Math.min(len, buffer.limit()) / (2L * rate);
        final long budgetNs = Math.min(8_000_000L, Math.max(2_000_000L, packetNs * 2L / 5L));
        if (android.os.SystemClock.elapsedRealtimeNanos() - started > budgetNs) {
            if (++slowFrames >= 3) {
                slowFrames = 0;
                if (++overloadStrikes >= 2) {
                    disabledForSession = true; // until the next recording, call or mode change
                } else {
                    overloaded = true;
                    retryAfter = now + 5000;
                }
            }
        } else {
            slowFrames = 0;
        }
    }

    /** An advisory UI status; no latency-sensitive code waits on this. */
    public static boolean isBypassedForPerformance() {
        return overloaded || disabledForSession;
    }

    // Anonymous is a privacy setting: never silently substitute the user's
    // original voice when the CPU cannot keep up with the effect.
    private static void muteIfAnonymous(ByteBuffer buffer, int len, int mode) {
        if (mode != MODE_ANONYMOUS || buffer == null || buffer.isReadOnly()) return;
        final int count = Math.min(len, buffer.limit()) / 2;
        for (int i = 0; i < count; ++i) buffer.putShort(i * 2, (short) 0);
    }

    public static synchronized void finishSource(int source) {
        if (activeSource == source) {
            activeSource = 0;
            initialized = false;
            limiterGain = 1f;
            overloaded = disabledForSession = false;
            slowFrames = overloadStrikes = 0;
        }
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
            case MODE_ASSISTANT: return 1.06f;
            case MODE_ARMOR: return 0.90f;
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
        process(buffer, len, 48000);
    }

    /**
     * @param rate реальная частота записи: на Bluetooth-гарнитурах это 16 кГц,
     *             и без неё робот звучал втрое выше задуманного
     */
    public static synchronized void process(ByteBuffer buffer, int len, int rate) {
        if (rate >= 8000 && rate <= 96000 && rate != sampleRate) {
            sampleRate = rate;
            initialized = false;   // фильтры пересчитываем под новую частоту
        }
        final int mode = PengramConfig.getVoiceChangerMode();
        if (buffer == null || buffer.isReadOnly() || len < 2 || buffer.limit() < 2) return;
        if (mode == MODE_TROLLING) {
            // Speech intelligibility wins over the separate +60 dB/overdrive
            // preset. No pitch windows or extra conference latency.
            try {
                if (!initialized) reset();
                processConferenceSpeech(buffer, len);
            } catch (Throwable e) {
                disabledForSession = true;
                initialized = false;
                FileLog.e(e);
            }
            return;
        }
        final int boostDb = PengramConfig.getVoiceGainDb();
        final int overdrive = PengramConfig.getVoiceGainDrive();
        final int ambience = PengramConfig.getVoiceAmbience();
        final boolean dryOnly = mode == MODE_OFF || isDryOnly(mode);
        if (dryOnly && boostDb == 0 && overdrive == 0 && ambience == AMBIENCE_OFF) return;
        final float ambGain = ambience == AMBIENCE_OFF ? 0f : ambienceAmplitude(PengramConfig.getVoiceAmbienceLevel());
        final boolean ambDuck = ambience != AMBIENCE_OFF && PengramConfig.isVoiceAmbienceDuck();
        // dB refers to voltage/amplitude, not power. Compute once per packet.
        final float boost = boostDb == 0 ? 1f : (float) Math.pow(10.0, boostDb / 20.0);
        try {
            if (dryOnly) {
                if (ambience != AMBIENCE_OFF && !initialized) {
                    reset();
                }
                final ByteOrder previousOrder = buffer.order();
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                try {
                    final int count = Math.min(len, buffer.limit()) / 2;
                    for (int i = 0; i < count; ++i) {
                        final short rawIn = buffer.getShort(i * 2);
                        float dry = microphoneOverdrive(rawIn * boost, overdrive);
                        if (ambience != AMBIENCE_OFF) {
                            dry += LIVE_AMBIENCE.nextSample(ambience, sampleRate, ambGain, Math.abs(dry), ambDuck);
                        }
                        buffer.putShort(i * 2, (short) limit(dry));
                    }
                } finally {
                    buffer.order(previousOrder);
                }
                return;
            }

            if (!initialized) {
                reset();
            }
            // Absolute getShort/putShort respect limit, not capacity. A reused
            // AudioRecord buffer may expose fewer valid bytes than it can hold.
            final int count = Math.min(len, buffer.limit()) / 2;
            final ByteOrder previousOrder = buffer.order();
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            final float basePitch = getPitchFactor(mode);
            final boolean anonymous = mode == MODE_ANONYMOUS;
            final int gate = PengramConfig.getVoiceGate();
            frameFormant = PengramConfig.getVoiceFormant();
            frameEcho = PengramConfig.getVoiceEcho();
            // Snapshot knobs once per packet, not for every microphone sample.
            frameDrive = mode == MODE_CUSTOM ? PengramConfig.getVoiceStudioDrive() : 0;
            if (frameDrive > 0) {
                final float gain = 1f + frameDrive * 0.45f;
                frameDriveGain = gain / 24000f;
                frameDriveScale = 24000f / (float) Math.tanh(gain);
            }
            frameRobot = mode == MODE_CUSTOM ? PengramConfig.getVoiceStudioRobot() : 0;
            frameMix = mode == MODE_CUSTOM || mode == MODE_ASSISTANT || mode == MODE_ARMOR
                    ? PengramConfig.getVoiceStudioMix() / 100f : 1f;

            try {
            for (int i = 0; i < count; ++i) {
                final short in = buffer.getShort(i * 2);

                if (gate > 0) {
                    final int threshold = gate * 200;
                    final float target = Math.abs(in) < threshold ? 0f : 1f;
                    gateGain += (target - gateGain) * (target > gateGain ? 0.025f : 0.001f);
                } else {
                    gateGain = 1f;
                }
                delay[writePos] = (short) (in * gateGain);
                writePos = (writePos + 1) & DELAY_MASK;

                float pitch = basePitch;
                if (anonymous) {
                    pitch = anonymousPitch();
                }

                float out;
                if (Math.abs(pitch - 1f) < 0.0005f) {
                    out = in;
                } else {
                    readPhase += (pitch - 1f);
                    if (readPhase >= window) {
                        readPhase -= window;
                        // Шаг фазы бывает больше окна (высокий коэффициент), и
                        // тогда одного вычитания мало: голова чтения уезжала за
                        // линию задержки, и на её краю ловили выход за массив.
                        if (readPhase >= window) {
                            readPhase %= window;
                        }
                    } else if (readPhase < 0) {
                        readPhase += window;
                        if (readPhase < 0) {
                            readPhase %= window;
                            if (readPhase < 0) {
                                readPhase += window;
                            }
                        }
                    }

                    final float head1 = readPhase;
                    final float head2 = readPhase + window / 2f >= window
                            ? readPhase - window / 2f
                            : readPhase + window / 2f;

                    // Equal-power overlap: precomputed/interpolated windows save
                    // two trigonometric calls for every sample on the audio thread.
                    final float table = head1 * pitchWindowScale;
                    // head1 >= 0; handle only the rare rounded-up table edge.
                    final boolean edge = table >= WINDOW_TABLE_SIZE;
                    final int step = edge ? WINDOW_TABLE_SIZE - 1 : (int) table;
                    final float fraction = edge ? 1f : table - step;
                    final float gain1 = windowSin[step] + (windowSin[step + 1] - windowSin[step]) * fraction;
                    final float gain2 = windowCos[step] + (windowCos[step + 1] - windowCos[step]) * fraction;

                    out = read(head1) * gain1 + read(head2) * gain2;
                    out = makeup(out, in);
                }

                out = applyMode(mode, out, in);
                if (frameMix < 1f) {
                    out = in * (1f - frameMix) + out * frameMix;
                }
                float voiced = microphoneOverdrive(out * boost, overdrive) * gateGain;
                if (ambience != AMBIENCE_OFF) {
                    voiced += LIVE_AMBIENCE.nextSample(ambience, sampleRate, ambGain, Math.abs(voiced), ambDuck);
                }
                buffer.putShort(i * 2, (short) limit(voiced));
            }

            } finally {
                // Keep LFO phase precision stable for long live calls.
                lfoPhase %= 2.0 * Math.PI;
                lfo2Phase %= 2.0 * Math.PI;
                anonJitterPhase %= 2.0 * Math.PI;
                anonJitter2Phase %= 2.0 * Math.PI;
                buffer.order(previousOrder);
            }
        } catch (Throwable e) {
            // Never repeatedly throw/log on the microphone callback. The next
            // packets remain unmodified until a fresh source starts.
            disabledForSession = true;
            initialized = false;
            muteIfAnonymous(buffer, len, mode);
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
            anonDriftLeft = (int) (sampleRate * (0.12f + AnonymousRandom.INSTANCE.nextFloat() * 0.2f));
            final float deviation = (AnonymousRandom.INSTANCE.nextFloat() - 0.5f) * 1.4f;
            anonPitchTarget = anonPitch * (float) Math.pow(2.0, deviation / 12.0);
        }
        anonPitchNow += (anonPitchTarget - anonPitchNow) * 0.00008f;

        // микро-таймварп ±1.2%: ломает любую попытку «выровнять» запись обратно
        anonJitterPhase += anonJitterSpeed;
        anonJitter2Phase += anonJitter2Speed;
        final float warp = 1f + (float) (sine(anonJitterPhase) * 0.008 + sine(anonJitter2Phase) * 0.004);
        return anonPitchNow * warp;
    }

    private static float applyMode(int mode, float x, float dry) {
        switch (mode) {
            case MODE_CUSTOM: {
                if (frameFormant != 0) {
                    x = formant2.process(formant1.process(x));
                }
                final int echoLevel = frameEcho;
                if (echoLevel > 0) {
                    final float delayed = echoRead(sampleRate / 6);
                    echoWrite(x + delayed * 0.2f);
                    x += delayed * echoLevel * 0.09f;
                }
                if (frameRobot > 0) {
                    lfoPhase += 2.0 * Math.PI * 68.0 / sampleRate;
                    final float depth = frameRobot / 6f * 0.65f;
                    x *= 1f - depth * (0.5f - 0.5f * sine(lfoPhase));
                }
                if (frameDrive > 0) {
                    // Normalized soft saturation; the static coefficients are
                    // calculated once per audio packet, not for each sample.
                    x = (float) Math.tanh(x * frameDriveGain) * frameDriveScale;
                }
                return x;
            }
            case MODE_ASSISTANT: {
                final float clear = formant2.process(formant1.process(highpass(x, 330f)));
                lfoPhase += 2.0 * Math.PI * 54.0 / sampleRate;
                final float shimmer = 0.88f + 0.12f * sine(lfoPhase);
                final float tap = echoRead(Math.max(1, sampleRate / 35));
                echoWrite(clear + tap * 0.15f);
                return clear * shimmer + tap * 0.12f;
            }
            case MODE_ARMOR: {
                final float band = highpass(lowpass(x, 3400f), 330f);
                final float shaped = formant2.process(formant1.process(band));
                final float compressed = (float) Math.tanh(shaped / 14000f) * 16000f;
                final float tap = echoRead(Math.max(1, sampleRate / 12));
                echoWrite(compressed + tap * 0.22f);
                return compressed * 0.84f + tap * 0.23f;
            }
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
            case MODE_FEMALE:
            case MODE_CHILD:
            case MODE_HELIUM:
            case MODE_MALE:
            case MODE_DEEP:
                return formant2.process(formant1.process(x));
            case MODE_ROBOT: {
                lfoPhase += 2.0 * Math.PI * 75.0 / sampleRate;
                final float mod = (float) (0.55 + 0.45 * sine(lfoPhase + Math.PI / 2));
                return x * mod;
            }
            case MODE_MONSTER: {
                lfoPhase += 2.0 * Math.PI * 32.0 / sampleRate;
                final float mod = (float) (0.6 + 0.4 * sine(lfoPhase + Math.PI / 2));
                return clip(formant2.process(formant1.process(x)) * mod * 1.25f, 26000f);
            }
            case MODE_DEMON: {
                lfoPhase += 2.0 * Math.PI * 24.0 / sampleRate;
                lfo2Phase += 2.0 * Math.PI * 7.0 / sampleRate;
                final float growl = (float) (0.62 + 0.38 * sine(lfoPhase + Math.PI / 2)) * (float) (0.85 + 0.15 * sine(lfo2Phase + Math.PI / 2));
                final float distorted = (float) Math.tanh(x * growl / 9000f) * 9000f;
                final float demonEcho = echoRead(2600);
                echoWrite(distorted + demonEcho * 0.28f);
                return distorted + demonEcho * 0.45f;
            }
            case MODE_ALIEN: {
                lfoPhase += 2.0 * Math.PI * 180.0 / sampleRate;
                final float ring = (float) sine(lfoPhase + Math.PI / 2);
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
                lfoPhase += 2.0 * Math.PI * 1.6 / sampleRate;
                final float wobble = (float) (0.88 + 0.12 * sine(lfoPhase + Math.PI / 2));
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

    /** Clean, leveled speech for calls and voice notes; all coefficients and
     *  state are per microphone session and recalculated for the actual rate.
     */
    private static void processConferenceSpeech(ByteBuffer buffer, int len) {
        final ByteOrder previousOrder = buffer.order();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try {
            final int count = Math.min(len, buffer.limit()) / 2;
            for (int i = 0; i < count; ++i) {
                final float input = buffer.getShort(i * 2);
                // Remove handling noise/rumble and lift articulation without
                // changing pitch, adding echo or flattening consonants.
                speechHp = speechHpCoefficient * (speechHp + input - speechHpPrevious);
                speechHpPrevious = input;
                speechPresenceLow += speechPresenceCoefficient * (speechHp - speechPresenceLow);
                final float clear = speechHp + 0.16f * (speechHp - speechPresenceLow);
                final float level = Math.abs(speechHp);
                speechEnvelope += (level - speechEnvelope) *
                        (level > speechEnvelope ? speechEnvelopeAttack : speechEnvelopeRelease);
                // Moderate expander: do not pump room noise to full level
                // between words. Starts opening well below ordinary speech.
                final float activity = Math.max(0f, Math.min(1f, (speechEnvelope - 28f) / 112f));
                speechGate += (activity - speechGate) *
                        (activity > speechGate ? speechGateAttack : speechGateRelease);
                // Target a strong speech envelope without slamming the 30k
                // limiter on every syllable. Up to 20x for quiet words, down
                // to 0.65x for shouting; fast down and slow up prevent pumping.
                final float target = Math.max(0.65f, Math.min(20f,
                        14000f / Math.max(500f, speechEnvelope)));
                speechGain += (target - speechGain) *
                        (target < speechGain ? speechGainAttack : speechGainRelease);
                float voiced = clear * speechGain * speechGate;
                final int ambience = PengramConfig.getVoiceAmbience();
                if (ambience != AMBIENCE_OFF) {
                    final float ambGain = ambienceAmplitude(PengramConfig.getVoiceAmbienceLevel());
                    voiced += LIVE_AMBIENCE.nextSample(ambience, sampleRate, ambGain, Math.abs(voiced), PengramConfig.isVoiceAmbienceDuck());
                }
                buffer.putShort(i * 2, (short) limit(voiced));
            }
        } finally {
            buffer.order(previousOrder);
        }
    }

    // ------------------------------------------------------------- утилиты DSP

    /** Interpolated LFO. Phases are positive and normalized once per packet. */
    private static float sine(double phase) {
        if (phase < 0) phase -= Math.floor(phase * INVERSE_TWO_PI) * TWO_PI;
        else phase -= (int) (phase * INVERSE_TWO_PI) * TWO_PI;
        // 2π rounds to the end of the table on some sample rates.
        final double index = phase * LFO_TABLE_SCALE;
        final int a = (int) index;
        if (a >= WINDOW_TABLE_SIZE) return lfoSin[WINDOW_TABLE_SIZE];
        return lfoSin[a] + (lfoSin[a + 1] - lfoSin[a]) * (float) (index - a);
    }

    /** линейная интерполяция по линии задержки, head — задержка в сэмплах от головы записи */
    private static float read(float head) {
        // head is always in [0, window), window <= DELAY_SIZE / 2:
        // a single wrap is sufficient, even at the buffer boundary. Kept as a
        // loop anyway: one out-of-window head used to read past the ring.
        float pos = writePos - window + head;
        while (pos < 0) pos += DELAY_SIZE;
        while (pos >= DELAY_SIZE) pos -= DELAY_SIZE;
        final int i0 = (int) pos;
        final int i1 = (i0 + 1) & DELAY_MASK;
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
        if (++echoPos == ECHO_SIZE) echoPos = 0;
    }

    private static float timeCoefficient(float seconds) {
        return (float) (1.0 - Math.exp(-1.0 / (seconds * sampleRate)));
    }

    private static float lowpassCoefficient(float cutoff) {
        return (float) (1.0 - Math.exp(-2.0 * Math.PI * cutoff / sampleRate));
    }

    private static float highpassCoefficient(float cutoff) {
        final float rc = 1f / (2f * (float) Math.PI * cutoff);
        return rc / (rc + 1f / sampleRate);
    }

    private static float lowpass(float x, float cutoff) {
        final float a = cutoff == 950f ? lp950 : cutoff == 1800f ? lp1800
                : cutoff == 2900f ? lp2900 : lp3400;
        lowpassState += a * (x - lowpassState);
        return lowpassState;
    }

    private static float highpass(float x, float cutoff) {
        final float a = cutoff == 330f ? hp330 : cutoff == 420f ? hp420
                : cutoff == 700f ? hp700 : hp2600;
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

    /** Intentional saturation, separate from the final digital peak limiter.
     *  The knee starts at 24k for a gentle drive, down to 6k at 100%. The
     *  bounded rational curve avoids tanh on the latency-sensitive call path.
     */
    private static float microphoneOverdrive(float x, int drive) {
        if (drive == 0) return x;
        final float threshold = 24000f - drive * 180f;
        final float amplitude = Math.abs(x);
        if (amplitude <= threshold) return x;
        final float headroom = 30000f - threshold;
        final float excess = amplitude - threshold;
        final float compressed = threshold + headroom * excess / (excess + headroom);
        return x < 0f ? -compressed : compressed;
    }

    /** мягкий лимитер: ничего не трещит даже на агрессивных режимах */
    private static float limit(float x) {
        final float peak = Math.abs(x);
        final float ceiling = 30000f;
        if (peak * limiterGain > ceiling) {
            limiterGain = ceiling / peak;
        }
        final float out = x * limiterGain;
        // Recover AFTER calculating this sample; otherwise a sustained loud
        // signal can exceed the ceiling on every alternate sample.
        limiterGain += (1f - limiterGain) * 0.0008f;
        return clip(out, ceiling);
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
            case MODE_ASSISTANT: return LocaleController.getString(R.string.PengramVoiceAssistant);
            case MODE_ARMOR: return LocaleController.getString(R.string.PengramVoiceArmor);
            case MODE_TROLLING: return LocaleController.getString(R.string.PengramVoiceTrolling);
            case MODE_CAVE: return LocaleController.getString(R.string.PengramVoiceCave);
            case MODE_UNDERWATER: return LocaleController.getString(R.string.PengramVoiceUnderwater);
            case MODE_WHISPER: return LocaleController.getString(R.string.PengramVoiceWhisper);
            default: return LocaleController.getString(R.string.PengramVoiceOff);
        }
    }

    /** значок режима для плиточного выбора — чтобы список читался с одного взгляда */
    public static String getModeEmoji(int mode) {
        switch (mode) {
            case MODE_ANONYMOUS: return "\uD83D\uDD75\uFE0F"; // детектив
            case MODE_FEMALE: return "\uD83D\uDC69";
            case MODE_MALE: return "\uD83D\uDC68";
            case MODE_CHILD: return "\uD83E\uDDD2";
            case MODE_HELIUM: return "\uD83C\uDF88";
            case MODE_DEEP: return "\uD83D\uDC3B";
            case MODE_MONSTER: return "\uD83D\uDC79";
            case MODE_DEMON: return "\uD83D\uDC7F";
            case MODE_ROBOT: return "\uD83E\uDD16";
            case MODE_ALIEN: return "\uD83D\uDC7D";
            case MODE_RADIO: return "\uD83D\uDCFB";
            case MODE_PHONE: return "\u260E\uFE0F";
            case MODE_ASSISTANT: return "\uD83D\uDCBB";
            case MODE_ARMOR: return "\uD83D\uDEE1\uFE0F";
            case MODE_TROLLING: return "\uD83D\uDCE2";
            case MODE_CAVE: return "\uD83D\uDD73\uFE0F";
            case MODE_UNDERWATER: return "\uD83C\uDF0A";
            case MODE_WHISPER: return "\uD83E\uDD2B";
            case MODE_CUSTOM: return "\uD83C\uDF9B\uFE0F";
            default: return "\uD83C\uDFA4";
        }
    }

    /** группа режима: 0 — базовые, 1 — персонажи, 2 — техно и пространство */
    public static int getModeGroup(int mode) {
        switch (mode) {
            case MODE_OFF:
            case MODE_TROLLING:
            case MODE_ANONYMOUS:
            case MODE_CUSTOM:
                return 0;
            case MODE_FEMALE:
            case MODE_MALE:
            case MODE_CHILD:
            case MODE_HELIUM:
            case MODE_DEEP:
            case MODE_MONSTER:
            case MODE_DEMON:
            case MODE_WHISPER:
                return 1;
            default:
                return 2;
        }
    }

    // ------------------------------------------------------------- фоновый шум (Ambience / Soundboard)

    private static final AmbienceEngine LIVE_AMBIENCE = new AmbienceEngine();
    private static final AmbienceEngine PREVIEW_AMBIENCE = new AmbienceEngine();

    public static float ambienceAmplitude(int levelPct) {
        final float norm = Math.max(0.05f, Math.min(1.0f, levelPct / 100f));
        return (0.14f * norm + 0.86f * norm * norm) * 8600f;
    }

    public static String getAmbienceName(int mode) {
        switch (mode) {
            case AMBIENCE_SUBWAY: return LocaleController.getString(R.string.PengramVoiceAmbienceSubway);
            case AMBIENCE_STREET: return LocaleController.getString(R.string.PengramVoiceAmbienceStreet);
            case AMBIENCE_RAIN: return LocaleController.getString(R.string.PengramVoiceAmbienceRain);
            case AMBIENCE_OFFICE: return LocaleController.getString(R.string.PengramVoiceAmbienceOffice);
            case AMBIENCE_RADIO: return LocaleController.getString(R.string.PengramVoiceAmbienceRadio);
            case AMBIENCE_CLUB: return LocaleController.getString(R.string.PengramVoiceAmbienceClub);
            default: return LocaleController.getString(R.string.PengramVoiceAmbienceOff);
        }
    }

    public static String getAmbienceEmoji(int mode) {
        switch (mode) {
            case AMBIENCE_SUBWAY: return "\uD83D\uDE87";
            case AMBIENCE_STREET: return "\uD83C\uDFD9\uFE0F";
            case AMBIENCE_RAIN: return "\uD83C\uDF27\uFE0F";
            case AMBIENCE_OFFICE: return "\uD83D\uDCBC";
            case AMBIENCE_RADIO: return "\uD83D\uDCFB";
            case AMBIENCE_CLUB: return "\uD83E\uDE99";
            default: return "\uD83D\uDD07";
        }
    }

    public static void resetAmbiencePreview(int sampleRate) {
        PREVIEW_AMBIENCE.reset(sampleRate);
    }

    public static void renderAmbiencePreviewChunk(short[] pcm, int chunkSamples, int sampleRate,
                                                  int ambience, int levelPct,
                                                  int writtenSamples, int totalSamples, int fadeSamples) {
        final float gain = ambienceAmplitude(levelPct) * 1.15f;
        for (int i = 0; i < chunkSamples; i++) {
            final int idx = writtenSamples + i;
            float fade = 1f;
            if (fadeSamples > 0) {
                if (idx < fadeSamples) {
                    fade = idx / (float) fadeSamples;
                } else if (idx > totalSamples - fadeSamples) {
                    fade = Math.max(0f, (totalSamples - idx) / (float) fadeSamples);
                }
            }
            float s = PREVIEW_AMBIENCE.nextSample(ambience, sampleRate, gain * fade, 0f, false);
            if (s > 30000f) s = 30000f;
            else if (s < -30000f) s = -30000f;
            pcm[i] = (short) s;
        }
    }

    /**
     * Процедурный акустический синтезатор фоновых сред без аллокаций в аудио-потоке.
     * Работает на любой частоте дискретизации (16..48 кГц) одинаково реалистично.
     */
    private static final class AmbienceEngine {
        private int lastRate = 48000;
        private int lastMode = -1;
        private long rng = 0x5DEECE66DL;
        private float voiceEnv;
        private float duckAtk, duckRel;

        // Общие фильтры розового/коричневого шума и резонаторов
        private float pink0, pink1, pink2;
        private float lpSub, lpLow, lpMid, lpHigh, hp1State, hp1Prev, hp2State, hp2Prev;
        private float resY1, resY2, res2Y1, res2Y2;
        private double phase1, phase2, phase3;
        private long sampleTick;

        // Таймеры событий (стыки рельсов, машины, капли, клавиши, рация, бочка клуба)
        private int eventCountdown1;
        private int eventCountdown2;
        private int burstRemaining;
        private float env1, env2, env3;
        private float sweepFreq, sweepTarget;
        private int clubBarStep;

        void reset(int rate) {
            lastRate = rate > 0 ? rate : 48000;
            rng = (System.nanoTime() ^ 0x9E3779B97F4A7C15L) | 1L;
            voiceEnv = 0f;
            duckAtk = expCoef(0.012f, lastRate);
            duckRel = expCoef(0.26f, lastRate);
            pink0 = pink1 = pink2 = 0f;
            lpSub = lpLow = lpMid = lpHigh = 0f;
            hp1State = hp1Prev = hp2State = hp2Prev = 0f;
            resY1 = resY2 = res2Y1 = res2Y2 = 0f;
            phase1 = phase2 = phase3 = 0.0;
            sampleTick = 0L;
            eventCountdown1 = lastRate / 3;
            eventCountdown2 = lastRate;
            burstRemaining = 0;
            env1 = env2 = env3 = 0f;
            sweepFreq = 600f;
            sweepTarget = 340f;
            clubBarStep = 0;
        }

        private static float expCoef(float seconds, int rate) {
            return (float) (1.0 - Math.exp(-1.0 / Math.max(1f, seconds * rate)));
        }

        private static float lpCoef(float hz, int rate) {
            final float clamped = Math.min(hz, rate * 0.44f);
            return (float) (1.0 - Math.exp(-TWO_PI * clamped / rate));
        }

        private float randSigned() {
            long x = rng;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            rng = x;
            return (x >> 40) / 8388608f;
        }

        private float randUnit() {
            return (randSigned() + 1f) * 0.5f;
        }

        /** Тёплый розовый шум Пола Келлетта (3 полюса) — без резкого цифрового песка. */
        private float nextPink() {
            final float w = randSigned();
            pink0 = 0.99765f * pink0 + w * 0.0990460f;
            pink1 = 0.96300f * pink1 + w * 0.2965164f;
            pink2 = 0.57000f * pink2 + w * 1.0526913f;
            return (pink0 + pink1 + pink2 + w * 0.1848f) * 0.26f;
        }

        /** Быстрый двухполюсный резонатор для ударов капель, клавиш и стыков рельсов. */
        private float resonator1(float exc, float freqHz, float radius, int rate) {
            final float f = Math.min(freqHz, rate * 0.43f);
            final double w = TWO_PI * f / rate;
            final float y = exc + (float) (2.0 * radius * Math.cos(w)) * resY1 - (radius * radius) * resY2;
            resY2 = resY1;
            resY1 = y;
            return y;
        }

        private float resonator2(float exc, float freqHz, float radius, int rate) {
            final float f = Math.min(freqHz, rate * 0.43f);
            final double w = TWO_PI * f / rate;
            final float y = exc + (float) (2.0 * radius * Math.cos(w)) * res2Y1 - (radius * radius) * res2Y2;
            res2Y2 = res2Y1;
            res2Y1 = y;
            return y;
        }

        float nextSample(int mode, int rate, float baseGain, float voiceAbs, boolean duck) {
            if (rate <= 0) rate = 48000;
            if (rate != lastRate || mode != lastMode) {
                if (rate != lastRate) {
                    reset(rate);
                }
                lastMode = mode;
            }
            sampleTick++;

            float gain = baseGain;
            if (duck) {
                voiceEnv += (voiceAbs - voiceEnv) * (voiceAbs > voiceEnv ? duckAtk : duckRel);
                final float speech = Math.max(0f, Math.min(1f, (voiceEnv - 550f) / 4800f));
                gain *= (1f - 0.40f * speech);
            }

            float raw;
            switch (mode) {
                case AMBIENCE_SUBWAY:
                    raw = synthSubway(rate);
                    break;
                case AMBIENCE_STREET:
                    raw = synthStreet(rate);
                    break;
                case AMBIENCE_RAIN:
                    raw = synthRain(rate);
                    break;
                case AMBIENCE_OFFICE:
                    raw = synthOffice(rate);
                    break;
                case AMBIENCE_RADIO:
                    raw = synthRadio(rate);
                    break;
                case AMBIENCE_CLUB:
                    raw = synthClub(rate);
                    break;
                default:
                    return 0f;
            }
            return raw * gain;
        }

        /** 1. Метро: глубокий гул тоннеля, качение тележки и парный перестук рельсовых стыков «тук-тук». */
        private float synthSubway(int rate) {
            final float pink = nextPink();
            phase1 += TWO_PI * 0.14 / rate;
            if (phase1 >= TWO_PI) phase1 -= TWO_PI;
            final float sway = 0.82f + 0.18f * sine(phase1);

            lpSub += lpCoef(135f, rate) * (pink - lpSub);
            lpLow += lpCoef(390f, rate) * (pink - lpLow);
            lpMid += lpCoef(1650f, rate) * (pink - lpMid);
            final float ventBand = lpMid - lpLow;

            // Тяговый инвертор вагона (очень мягкая гармоника ~285 Гц)
            phase2 += TWO_PI * (278.0 + 14.0 * sine(phase1 * 0.7)) / rate;
            if (phase2 >= TWO_PI) phase2 -= TWO_PI;
            final float inverter = sine(phase2) * 0.055f;

            // Парные удары колёсных пар на стыках рельсов («тук-тук ... тук-тук»)
            float clickExc = 0f;
            if (--eventCountdown1 <= 0) {
                if (burstRemaining == 1) {
                    // Вторая колёсная пара тележки через ~88..105 мс
                    burstRemaining = 0;
                    eventCountdown1 = (int) (rate * (1.18f + randUnit() * 0.42f));
                    env1 = 0.85f + randUnit() * 0.25f;
                } else {
                    burstRemaining = 1;
                    eventCountdown1 = (int) (rate * (0.086f + randUnit() * 0.022f));
                    env1 = 1.0f;
                }
                clickExc = env1 * 0.55f;
            }
            env1 *= (1f - expCoef(0.016f, rate));
            final float clackBody = resonator1(clickExc + randSigned() * env1 * 0.12f, 470f, 0.965f, rate);
            final float clackEdge = resonator2(clickExc, 1240f, 0.93f, rate);

            return (lpSub * 1.45f + (lpLow - lpSub) * 0.95f + ventBand * 0.22f + inverter) * sway
                    + clackBody * 0.28f + clackEdge * 0.14f;
        }

        /** 2. Улица: городской фон, порывы ветра, проезжающие авто с эффектом Доплера и далёкий сигнал. */
        private float synthStreet(int rate) {
            final float pink = nextPink();
            phase1 += TWO_PI * 0.19 / rate;
            phase2 += TWO_PI * 0.43 / rate;
            if (phase1 >= TWO_PI) phase1 -= TWO_PI;
            if (phase2 >= TWO_PI) phase2 -= TWO_PI;

            final float gust = 0.55f + 0.45f * (0.6f * sine(phase1) + 0.4f * sine(phase2));
            lpLow += lpCoef(190f + 260f * Math.max(0f, gust), rate) * (pink - lpLow);
            lpMid += lpCoef(920f, rate) * (pink - lpMid);
            final float cityBed = lpLow * 0.85f + (lpMid - lpLow) * 0.38f;

            // Проезжающий автомобиль (плавный наезд и спад + сдвиг спектра вниз по Доплеру)
            if (--eventCountdown1 <= 0) {
                eventCountdown1 = (int) (rate * (2.6f + randUnit() * 3.1f));
                env2 = 1.0f;
                sweepFreq = 980f + randUnit() * 280f;
                sweepTarget = 270f + randUnit() * 90f;
            }
            // Плавная колоколообразная огибающая проезда (env1 догоняет env2, env2 плавно гаснет)
            env2 *= (1f - expCoef(0.55f, rate));
            env1 += (env2 - env1) * expCoef(0.24f, rate);
            sweepFreq += (sweepTarget - sweepFreq) * expCoef(0.62f, rate);
            lpHigh += lpCoef(sweepFreq, rate) * (pink - lpHigh);
            final float carPass = (lpHigh - lpLow * 0.45f) * env1 * 1.65f;

            // Редкий приглушённый автомобильный гудок вдали (~415 + 518 Гц)
            if (--eventCountdown2 <= 0) {
                eventCountdown2 = (int) (rate * (9.5f + randUnit() * 8.0f));
                burstRemaining = (int) (rate * (0.16f + randUnit() * 0.14f));
            }
            float horn = 0f;
            if (burstRemaining > 0) {
                burstRemaining--;
                env3 += (1f - env3) * expCoef(0.015f, rate);
            } else {
                env3 *= (1f - expCoef(0.045f, rate));
            }
            if (env3 > 0.002f) {
                phase3 += TWO_PI * 418.0 / rate;
                if (phase3 >= TWO_PI) phase3 -= TWO_PI;
                horn = (sine(phase3) + 0.7f * sine(phase3 * 1.245)) * env3 * 0.075f;
            }
            return cityBed + carPass + horn;
        }

        /** 3. Дождь: бархатная завеса ливня, отдельные капли по стеклу/зонтику и далёкий гром. */
        private float synthRain(int rate) {
            final float pink = nextPink();
            final float white = randSigned();
            lpLow += lpCoef(480f, rate) * (pink - lpLow);
            lpMid += lpCoef(3400f, rate) * ((pink * 0.65f + white * 0.35f) - lpMid);
            final float rainCurtain = (lpMid - lpLow) * 0.72f;

            // Отдельные капли (~26 капель/сек со случайной высотой тона)
            float dropExc = 0f;
            if (--eventCountdown1 <= 0) {
                eventCountdown1 = Math.max(8, (int) (rate * (0.012f + randUnit() * 0.055f)));
                sweepFreq = 950f + randUnit() * 2350f;
                dropExc = 0.32f + randUnit() * 0.45f;
            }
            final float droplets = resonator1(dropExc, sweepFreq, 0.955f, rate) * 0.19f;

            // Далёкий мягкий раскат грома в низах (65..140 Гц)
            if (--eventCountdown2 <= 0) {
                eventCountdown2 = (int) (rate * (8.5f + randUnit() * 7.5f));
                env2 = 0.85f + randUnit() * 0.3f;
            }
            env2 *= (1f - expCoef(1.35f, rate));
            env1 += (env2 - env1) * expCoef(0.45f, rate);
            lpSub += lpCoef(115f, rate) * (pink - lpSub);
            final float thunder = lpSub * env1 * 1.85f;

            return rainCurtain + droplets + thunder;
        }

        /** 4. Офис: мягкий гул кондиционера, серии печати на клавиатуре и клики мыши. */
        private float synthOffice(int rate) {
            final float pink = nextPink();
            lpSub += lpCoef(155f, rate) * (pink - lpSub);
            lpLow += lpCoef(540f, rate) * (pink - lpLow);
            phase1 += TWO_PI * 0.27 / rate;
            if (phase1 >= TWO_PI) phase1 -= TWO_PI;
            final float hvac = lpSub * 0.58f + (lpLow - lpSub) * (0.28f + 0.06f * sine(phase1));

            // Живые серии нажатий клавиш (5..13 нажатий подряд, затем пауза 0.7..2.2 с)
            float keyExc = 0f;
            float deskExc = 0f;
            if (--eventCountdown1 <= 0) {
                if (burstRemaining <= 0) {
                    burstRemaining = 5 + (int) (randUnit() * 9f);
                    eventCountdown1 = (int) (rate * (0.65f + randUnit() * 1.55f));
                } else {
                    burstRemaining--;
                    eventCountdown1 = (int) (rate * (0.068f + randUnit() * 0.095f));
                    sweepFreq = 1650f + randUnit() * 1150f;
                    final float vel = 0.45f + randUnit() * 0.45f;
                    keyExc = vel;
                    deskExc = vel * 0.65f;
                }
            }
            // Периодический двойной клик мыши
            if (--eventCountdown2 <= 0) {
                if (env3 > 0.5f) {
                    env3 = 0f;
                    eventCountdown2 = (int) (rate * (2.8f + randUnit() * 4.2f));
                } else {
                    env3 = 1f;
                    eventCountdown2 = (int) (rate * (0.11f + randUnit() * 0.04f));
                }
                sweepFreq = 2650f;
                keyExc += 0.55f;
            }
            final float keyClick = resonator1(keyExc, sweepFreq, 0.91f, rate) * 0.24f;
            final float keyThock = resonator2(deskExc, 370f, 0.955f, rate) * 0.18f;
            return hvac + keyClick + keyThock;
        }

        /** 5. Помехи рации: полосовой УКВ-шум шумоподавителя, потрескивания эфира и тон несущей. */
        private float synthRadio(int rate) {
            final float white = randSigned();
            // Полосовой тракт рации 460..2850 Гц
            final float hpCoef = (float) Math.exp(-TWO_PI * 460.0 / rate);
            hp1State = hpCoef * (hp1State + white - hp1Prev);
            hp1Prev = white;
            lpMid += lpCoef(2850f, rate) * (hp1State - lpMid);

            // Всплески радиопомех и потрескиваний
            if (--eventCountdown1 <= 0) {
                eventCountdown1 = (int) (rate * (0.35f + randUnit() * 1.45f));
                burstRemaining = (int) (rate * (0.04f + randUnit() * 0.18f));
                sweepFreq = 1580f + randUnit() * 420f;
            }
            float crackle = 0f;
            float carrier = 0f;
            if (burstRemaining > 0) {
                burstRemaining--;
                env1 += (1f - env1) * expCoef(0.005f, rate);
                if (randUnit() > 0.86f) {
                    crackle = randSigned() * 0.95f;
                }
                phase2 += TWO_PI * sweepFreq / rate;
                if (phase2 >= TWO_PI) phase2 -= TWO_PI;
                carrier = sine(phase2) * 0.085f * env1;
            } else {
                env1 *= (1f - expCoef(0.03f, rate));
            }
            lpHigh += lpCoef(2400f, rate) * (crackle - lpHigh);
            return lpMid * (0.42f + 0.45f * env1) + lpHigh * 0.55f + carrier;
        }

        /** 6. Клуб: глухой бит 124 BPM «из-за стены / у бара» с сайдчейн-басом и гулом зала. */
        private float synthClub(int rate) {
            final int beatSamples = Math.max(1, (int) (rate * 60.0f / 124.0f)); // 124 BPM
            if (--eventCountdown1 <= 0) {
                eventCountdown1 = beatSamples;
                env1 = 1.0f;       // огибающая тела бочки
                env2 = 1.0f;       // быстрый питч-свип удара бочки
                clubBarStep = (clubBarStep + 1) & 15;
            }
            env1 *= (1f - expCoef(0.115f, rate));
            env2 *= (1f - expCoef(0.022f, rate));

            // Бочка 138 -> 50 Гц с мягкой 2-й гармоникой (читается даже на динамике телефона)
            final float kickHz = 50f + 88f * env2;
            phase1 += TWO_PI * kickHz / rate;
            if (phase1 >= TWO_PI) phase1 -= TWO_PI;
            final float kickRaw = (sine(phase1) + 0.34f * sine(phase1 * 2.0)) * env1;
            final float kickWarm = kickRaw / (1f + 0.45f * Math.abs(kickRaw));

            // Сайдчейн-кач: бас и гул танцпола проседают на каждом ударе бочки и всплывают между долями
            final float pump = Math.max(0.18f, 1f - 0.78f * env1);
            final float bassNoteHz = (clubBarStep < 8) ? 55.0f : (clubBarStep < 12 ? 65.41f : 73.42f);
            phase2 += TWO_PI * bassNoteHz / rate;
            if (phase2 >= TWO_PI) phase2 -= TWO_PI;
            // Богатый гармониками клубный саб-бас под глухим ФНЧ стены (~260 Гц)
            final float subOsc = sine(phase2) * 0.65f + sine(phase2 * 2.0) * 0.35f + sine(phase2 * 3.0) * 0.16f;
            lpSub += lpCoef(255f, rate) * (subOsc - lpSub);

            // Приглушённый гул зала и музыки за дверью (180..480 Гц)
            final float pink = nextPink();
            lpLow += lpCoef(170f, rate) * (pink - lpLow);
            lpMid += lpCoef(490f, rate) * (pink - lpLow - lpMid);

            return kickWarm * 0.92f + lpSub * pump * 0.52f + lpMid * pump * 0.48f;
        }
    }
}
