package org.telegram.ui.Components;

import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PasscodeActivity;

/** Локальная проверка кода приложения, не шифрование SQLite и не разблокировка всего приложения. */
public final class PengramArchiveGuard extends FrameLayout {
    private final BaseFragment owner;
    private final View content;
    private PasscodeView passcode;
    private TextView setup;
    private Window secured;
    private boolean active, lastPolicy;
    private final Runnable policyChanged = () -> {
        if (active && lastPolicy != PengramConfig.isHistoryLocked()) resume();
    };
    private static final java.util.WeakHashMap<Window, Lease> leases = new java.util.WeakHashMap<>();

    private static final class Lease {
        int count;
        boolean previouslySecure;
    }

    public PengramArchiveGuard(BaseFragment owner, View content) {
        super(content.getContext());
        this.owner = owner;
        this.content = content;
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (PengramConfig.isHistoryLocked()) content.setVisibility(INVISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        org.telegram.messenger.PengramHistory.addChangeListener(policyChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        org.telegram.messenger.PengramHistory.removeChangeListener(policyChanged);
        pause();
        super.onDetachedFromWindow();
    }

    public void resume() {
        active = true;
        lastPolicy = PengramConfig.isHistoryLocked();
        if (!lastPolicy) {
            if (passcode != null) passcode.setVisibility(GONE);
            if (setup != null) setup.setVisibility(GONE);
            content.setVisibility(VISIBLE);
            owner.getActionBar().setVisibility(VISIBLE);
            releaseSecure();
            return;
        }
        acquireSecure();
        content.setVisibility(INVISIBLE);
        owner.getActionBar().setVisibility(INVISIBLE);
        if (SharedConfig.passcodeHash.isEmpty()) {
            if (passcode != null) passcode.setVisibility(GONE);
            if (setup == null) {
                setup = new TextView(getContext());
                setup.setGravity(android.view.Gravity.CENTER);
                setup.setPadding(24, 24, 24, 24);
                setup.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                setup.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                setup.setOnClickListener(v -> owner.presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE)));
                addView(setup, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
            setup.setText(LocaleController.getString(R.string.PengramHistorySetPasscode));
            setup.setVisibility(VISIBLE);
            return; // Нет кода — не открываем архив без аутентификации.
        }
        if (setup != null) setup.setVisibility(GONE);
        if (passcode == null) {
            passcode = new PasscodeView(getContext()) {
                @Override
                protected boolean isAppLock() { return false; }
            };
            passcode.setVisibility(GONE);
            passcode.setDelegate(view -> {
                if (active) {
                    content.setVisibility(VISIBLE);
                    owner.getActionBar().setVisibility(VISIBLE);
                }
            });
            addView(passcode, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        passcode.onShow(true, false);
        passcode.onResume();
    }

    public void pause() {
        active = false;
        if (passcode != null) { passcode.onPause(); passcode.setVisibility(GONE); }
        if (PengramConfig.isHistoryLocked()) content.setVisibility(INVISIBLE);
        // FLAG_SECURE держим до destroy: snapshot списка задач снимается после onPause.
    }

    public void destroy() {
        pause();
        releaseSecure();
    }

    private void acquireSecure() {
        if (secured != null || owner.getParentActivity() == null) return;
        secured = owner.getParentActivity().getWindow();
        Lease lease = leases.get(secured);
        if (lease == null) {
            lease = new Lease();
            lease.previouslySecure = (secured.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
            leases.put(secured, lease);
        }
        lease.count++;
        secured.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void releaseSecure() {
        if (secured == null) return;
        Lease lease = leases.get(secured);
        if (lease != null && --lease.count == 0) {
            if (!lease.previouslySecure) secured.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            leases.remove(secured);
        }
        secured = null;
    }
}
