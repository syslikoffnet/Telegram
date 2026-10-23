package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Local, opt-in typing telemetry. Only counters and durations are persisted, never text. */
public final class PengramTypingStats {
    public static final String KEY_ENABLED = "typingStatsEnabled";
    public static final String KEY_SHOW_BADGE = "typingStatsBadge";
    private static final String PREFS = "pengram_typing_stats";
    private static final long PAUSE_MS = 3000;
    private static final int MIN_CHARS = 5;
    private static final long MIN_DURATION_MS = 2000;
    private static long start, last;
    private static int chars;
    private static int account = -1;

    private PengramTypingStats() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled() {
        return PengramConfig.getBool(KEY_ENABLED, false);
    }

    /** Call only for user-originated edits in the composer. Returns instantaneous WPM or zero. */
    public static synchronized int onEdit(int currentAccount, int before, int count, boolean userEdit) {
        if (!enabled()) {
            resetSession();
            return 0;
        }
        final long now = SystemClock.elapsedRealtime();
        if (!userEdit) {
            // Sending or replacing a draft ends the session; do not lose its
            // counters simply because the composer was cleared programmatically.
            finish(now);
            return 0;
        }
        if (account != currentAccount || now - last > PAUSE_MS || now < last) {
            finish(now);
            account = currentAccount;
        }
        if (count <= before || count > 3) { // deletion, paste or IME word replacement
            return currentWpm(now);
        }
        if (start == 0) start = now;
        chars += count - before;
        last = now;
        return currentWpm(now);
    }

    public static synchronized void finishSession() {
        finish(SystemClock.elapsedRealtime());
    }

    private static int currentWpm(long now) {
        long duration = now - start;
        if (start == 0 || chars < MIN_CHARS || duration < MIN_DURATION_MS) return 0;
        return (int) Math.min(300, Math.round(chars * 12000.0 / duration)); // 5 characters = one word
    }

    private static void finish(long now) {
        if (start != 0 && chars >= MIN_CHARS && last - start >= MIN_DURATION_MS && enabled()) {
            int wpm = currentWpm(last);
            SharedPreferences p = prefs();
            String prefix = "a" + account + "_";
            int sessions = p.getInt(prefix + "sessions", 0);
            long totalChars = p.getLong(prefix + "chars", 0);
            long totalMs = p.getLong(prefix + "ms", 0);
            p.edit().putInt(prefix + "sessions", sessions + 1)
                    .putLong(prefix + "chars", totalChars + chars)
                    .putLong(prefix + "ms", totalMs + last - start)
                    .putInt(prefix + "peak", Math.max(p.getInt(prefix + "peak", 0), wpm))
                    .putLong(prefix + "longest", Math.max(p.getLong(prefix + "longest", 0), last - start))
                    .apply();
        }
        resetSession();
    }

    private static void resetSession() {
        start = last = 0;
        chars = 0;
        account = -1;
    }

    public static synchronized void clear(int currentAccount) {
        resetSession();
        String prefix = "a" + currentAccount + "_";
        prefs().edit().remove(prefix + "sessions").remove(prefix + "chars")
                .remove(prefix + "ms").remove(prefix + "peak").remove(prefix + "longest").apply();
    }

    public static String summary(int currentAccount) {
        SharedPreferences p = prefs();
        String prefix = "a" + currentAccount + "_";
        int sessions = p.getInt(prefix + "sessions", 0);
        long duration = p.getLong(prefix + "ms", 0);
        long count = p.getLong(prefix + "chars", 0);
        long average = duration > 0 ? Math.min(300, Math.round(count * 12000.0 / duration)) : 0;
        return average + " WPM · " + p.getInt(prefix + "peak", 0) + " WPM · "
                + (p.getLong(prefix + "longest", 0) / 1000) + " s · " + sessions;
    }
}
