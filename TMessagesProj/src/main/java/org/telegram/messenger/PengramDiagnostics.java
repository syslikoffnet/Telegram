package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.PengramChatLookActivity;
import org.telegram.ui.PengramProfileHistorySettingsActivity;
import org.telegram.ui.PengramSettingsActivity;

/** Minimal local evidence, never session bytes, message text or exception messages. */
public final class PengramDiagnostics {
    private PengramDiagnostics() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("pengram_diagnostics", Context.MODE_PRIVATE);
    }

    public static void recordLogout(int account) {
        try {
            prefs().edit().putLong("logout." + account, System.currentTimeMillis())
                    .putLong("logoutUser." + account, UserConfig.getInstance(account).getClientUserId()).apply();
        } catch (RuntimeException error) {
            FileLog.e(error);
        }
    }

    public static void recordImportAttempt(int account) {
        try {
            prefs().edit().putLong("import." + account, System.currentTimeMillis())
                    .putLong("importPreviousUser." + account, UserConfig.getInstance(account).getClientUserId()).apply();
        } catch (RuntimeException error) {
            FileLog.e(error);
        }
    }

    private static final long[] identityProbeGenerations = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] identityProbeTimes = new long[UserConfig.MAX_ACCOUNT_COUNT];

    /** Explicit diagnostic button only; never repairs, clears or replaces account data. */
    public static void verifyAccountIdentities() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final int account = a;
            final UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated() || PengramLocalKeep.isLocalOnly(account)) continue;
            long now = android.os.SystemClock.elapsedRealtime();
            if (identityProbeGenerations[account] != 0 && now - identityProbeTimes[account] < 30000) continue;
            identityProbeTimes[account] = now;
            final long generation = ++identityProbeGenerations[account];
            final long expected = config.getClientUserId();
            recordAccountIdentity(account, expected, 0, "PENDING");
            org.telegram.tgnet.TLRPC.TL_users_getFullUser request = new org.telegram.tgnet.TLRPC.TL_users_getFullUser();
            request.id = new org.telegram.tgnet.TLRPC.TL_inputUserSelf();
            ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> {
                        if (generation != identityProbeGenerations[account]) return;
                        if (config.getClientUserId() != expected || !config.isClientActivated()) {
                            recordAccountIdentity(account, expected, 0, "CONFIG_CHANGED");
                            return;
                        }
                        org.telegram.tgnet.TLRPC.User self = response instanceof org.telegram.tgnet.TLRPC.TL_users_userFull
                                ? PengramSessionLogin.resolveSessionSelf((org.telegram.tgnet.TLRPC.TL_users_userFull) response) : null;
                        recordAccountIdentity(account, expected, self == null ? 0 : self.id,
                                error != null ? "ERROR" : self == null ? "UNVERIFIED" : self.id == expected ? "MATCH" : "MISMATCH");
                    }), ConnectionsManager.RequestFlagFailOnServerErrors);
        }
    }

    private static void recordAccountIdentity(int account, long expected, long actual, String status) {
        try {
            // Почему: несовпадение профиля и владельца server session требует доказательства, не сброса БД.
            prefs().edit().putLong("identity.expected." + account, expected)
                    .putLong("identity.self." + account, actual)
                    .putString("identity.status." + account, status)
                    .putLong("identity.time." + account, System.currentTimeMillis()).apply();
        } catch (RuntimeException error) {
            FileLog.e(error);
        }
    }

    public static void recordSearch(int[] entry, BaseFragment source) {
        try {
            // Почему: строковые имена маршрутов сохраняются и после обфускации.
            String target = entry[3] == 1 ? "PengramChatLookActivity"
                    : entry[3] == 2 ? "PengramProfileHistorySettingsActivity" : "PengramSettingsActivity";
            String route = "target=" + target + ", resource=" + entry[0] + ", section=" + entry[1]
                    + ", group=" + entry[2] + ", kind=" + entry[3]
                    + ", sourceLayout=" + (source != null && source.getParentLayout() != null)
                    + ", sourceAttached=" + (source != null && source.getFragmentView() != null
                    && source.getFragmentView().isAttachedToWindow());
            if (!prefs().edit().putString("lastSearch", route)
                    .putLong("lastSearchTime", System.currentTimeMillis()).commit()) {
                FileLog.e("Pengram diagnostic search record was not persisted");
            }
        } catch (RuntimeException error) {
            FileLog.e(error);
        }
    }

    public static void recordCreateFailure(BaseFragment fragment, String phase, Throwable error) {
        String target = fragment instanceof PengramSettingsActivity ? "PengramSettingsActivity"
                : fragment instanceof PengramChatLookActivity ? "PengramChatLookActivity"
                : fragment instanceof PengramProfileHistorySettingsActivity ? "PengramProfileHistorySettingsActivity" : null;
        if (target == null) return;
        try {
            // Фазы createView и onViewCreated различаются: это не доказательство старой причины NPE.
            String record = "target=" + target + ", phase=" + phase + ", error=" + error.getClass().getName()
                    + ", account=" + fragment.getCurrentAccount() + ", root=" + (fragment.getFragmentView() != null);
            if (!prefs().edit().putString("lastCreateFailure", record)
                    .putLong("lastCreateFailureTime", System.currentTimeMillis()).commit()) {
                FileLog.e("Pengram diagnostic failure record was not persisted");
            }
            FileLog.e(error);
        } catch (RuntimeException diagnosticError) {
            FileLog.e(diagnosticError);
        }
    }

    public static String report() {
        SharedPreferences saved = prefs();
        StringBuilder result = new StringBuilder("Pengram diagnostic schema=1\n");
        result.append(PengramVersion.shortLine()).append('\n');
        result.append("time=").append(System.currentTimeMillis()).append(" selectedAccount=")
                .append(UserConfig.selectedAccount).append(" ghostDelay=").append(PengramConfig.isGhostSendDelay())
                .append(" delayTextMs=12000 delayMediaMs=20000\n");
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            result.append("slot=").append(account).append(" userId=").append(config.getClientUserId())
                    .append(" activated=").append(config.isClientActivated())
                    .append(" connectionState=").append(ConnectionsManager.pengramPeekConnectionState(account))
                    .append(" localOnly=").append(PengramLocalKeep.isLocalOnly(account))
                    .append(" authKeyPresent=UNKNOWN pendingGhost=").append(SendMessagesHelper.getPendingGhostCount(account))
                    .append(" lastLogout=").append(saved.getLong("logout." + account, 0))
                    .append(" lastLogoutUser=").append(saved.getLong("logoutUser." + account, 0))
                    .append(" lastImportAttempt=").append(saved.getLong("import." + account, 0))
                    .append(" previousImportUser=").append(saved.getLong("importPreviousUser." + account, 0))
                    .append(" identityExpected=").append(saved.getLong("identity.expected." + account, 0))
                    .append(" serverSelf=").append(saved.getLong("identity.self." + account, 0))
                    .append(" identityStatus=").append(saved.getString("identity.status." + account, "NOT_CHECKED"))
                    .append(" identityTime=").append(saved.getLong("identity.time." + account, 0)).append('\n');
        }
        result.append("lastSearchTime=").append(saved.getLong("lastSearchTime", 0)).append('\n')
                .append("lastSearch=").append(saved.getString("lastSearch", "none")).append('\n')
                .append("lastCreateFailureTime=").append(saved.getLong("lastCreateFailureTime", 0)).append('\n')
                .append("lastCreateFailure=").append(saved.getString("lastCreateFailure", "none")).append('\n');
        return result.toString();
    }
}
