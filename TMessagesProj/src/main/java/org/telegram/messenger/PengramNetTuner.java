package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;

/**
 * Pengram: автоподбор стратегии обхода.
 *
 * <p>Перебирать профили руками — это пол дня и блокнот. Здесь приложение делает
 * это само: ставит профиль, роняет соединения и честно засекает, за сколько
 * миллисекунд ядро доходит до состояния «подключено». Профиль, который не
 * уложился в отведённое время, помечается как неработающий.
 *
 * <p>Проверка идёт по настоящему пути — тем же сокетам tgnet, с которыми
 * работает мессенджер, — поэтому результат означает ровно то, что написано,
 * а не «успешно открылся тестовый сокет куда-то».
 */
public final class PengramNetTuner {

    /** сколько ждём подключения на один профиль */
    private static final long PROFILE_TIMEOUT = 9000;
    /** как часто смотрим состояние */
    private static final long POLL = 200;
    /** пауза между профилями, чтобы ядро успело разорвать старое */
    private static final long SETTLE = 400;

    public static class Result {
        public final int profile;
        /** миллисекунды до подключения или -1, если не успел */
        public final int ms;

        Result(int profile, int ms) {
            this.profile = profile;
            this.ms = ms;
        }

        public boolean ok() {
            return ms >= 0;
        }
    }

    public interface Callback {
        /** вызывается в UI-потоке перед проверкой очередного профиля */
        void onProgress(int profile, int index, int total);

        /** вызывается в UI-потоке, когда профиль проверен */
        void onResult(Result result);

        /** вызывается в UI-потоке в самом конце; best — лучший профиль или -1 */
        void onFinish(ArrayList<Result> results, int best);
    }

    /** что именно перебираем: от самого безобидного к самому пробивному */
    private static final int[] ORDER = {
            PengramNet.PROFILE_OFF,
            PengramNet.PROFILE_SPLIT2,
            PengramNet.PROFILE_SPLIT_MTPROTO,
            PengramNet.PROFILE_MULTISPLIT,
            PengramNet.PROFILE_PACED,
            PengramNet.PROFILE_MOBILE,
            PengramNet.PROFILE_HARD,
    };

    private static volatile boolean running;
    private static final ArrayList<Result> lastResults = new ArrayList<>();

    private PengramNetTuner() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static ArrayList<Result> getLastResults() {
        synchronized (lastResults) {
            return new ArrayList<>(lastResults);
        }
    }

    /** результат конкретного профиля из прошлого прогона, null — не проверяли */
    public static Result resultOf(int profile) {
        synchronized (lastResults) {
            for (Result result : lastResults) {
                if (result.profile == profile) {
                    return result;
                }
            }
        }
        return null;
    }

    public static void stop() {
        running = false;
    }

    public static void start(final Callback callback) {
        if (running) {
            return;
        }
        running = true;
        synchronized (lastResults) {
            lastResults.clear();
        }
        final boolean savedEnabled = PengramNet.isEnabled();
        final int savedProfile = PengramNet.getProfile();

        new Thread(() -> {
            final ArrayList<Result> results = new ArrayList<>();
            try {
                for (int index = 0; index < ORDER.length && running; ++index) {
                    final int profile = ORDER[index];
                    final int position = index;
                    AndroidUtilities.runOnUIThread(() -> callback.onProgress(profile, position, ORDER.length));

                    PengramNet.applyStrategy(PengramNet.strategyOf(profile), profile != PengramNet.PROFILE_OFF);
                    PengramNet.dropConnections();
                    Thread.sleep(SETTLE);

                    final int ms = measure();
                    final Result result = new Result(profile, ms);
                    results.add(result);
                    synchronized (lastResults) {
                        lastResults.add(result);
                    }
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }

            // возвращаем то, что было, и сообщаем победителя
            int best = -1;
            int bestMs = Integer.MAX_VALUE;
            for (Result result : results) {
                if (result.ok() && result.ms < bestMs) {
                    bestMs = result.ms;
                    best = result.profile;
                }
            }
            // если прямое соединение и так работает, незачем ничего включать
            final int winner = best;
            PengramNet.applyStrategy(PengramNet.strategyOf(savedProfile), savedEnabled);
            PengramNet.dropConnections();
            running = false;
            AndroidUtilities.runOnUIThread(() -> callback.onFinish(results, winner));
        }, "PengramNetTuner").start();
    }

    /** ждём состояния «подключено» и возвращаем, сколько это заняло */
    private static int measure() {
        final long start = System.currentTimeMillis();
        final int account = UserConfig.selectedAccount;
        while (running && System.currentTimeMillis() - start < PROFILE_TIMEOUT) {
            try {
                final int state = ConnectionsManager.getInstance(account).getConnectionState();
                if (state == ConnectionsManager.ConnectionStateConnected
                        || state == ConnectionsManager.ConnectionStateUpdating) {
                    return (int) (System.currentTimeMillis() - start);
                }
                Thread.sleep(POLL);
            } catch (Throwable e) {
                return -1;
            }
        }
        return -1;
    }

    /** применить победителя автоподбора как постоянную настройку */
    public static void apply(int profile) {
        if (profile == PengramNet.PROFILE_OFF) {
            PengramNet.setEnabled(false);
            return;
        }
        PengramNet.setProfile(profile);
        PengramNet.setEnabled(true);
    }
}
