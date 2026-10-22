package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramDiagnostics;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/** On-demand snapshots; no network initialization or background polling. */
public class PengramDiagnosticsActivity extends BaseFragment {
    private TextView reportView;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (reportView == null || !reportView.isAttachedToWindow()) return;
            reportView.setText(PengramDiagnostics.report());
            reportView.postDelayed(refresh, 1000);
        }
    };

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.PengramDiagnostics));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        LinearLayout root = new LinearLayout(context);
        fragmentView = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.PengramDiagnosticsInfo));
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        root.addView(info, LayoutHelper.createLinear(-1, -2, 16, 12, 16, 12));
        TextView copy = new TextView(context);
        copy.setText(LocaleController.getString(R.string.PengramDiagnosticsCopy));
        copy.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        copy.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        copy.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(PengramDiagnostics.report());
            BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        });
        root.addView(copy, LayoutHelper.createLinear(-1, -2));
        ScrollView scroll = new ScrollView(context);
        reportView = new TextView(context);
        reportView.setTextIsSelectable(true);
        reportView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        reportView.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        scroll.addView(reportView);
        root.addView(scroll, LayoutHelper.createLinear(-1, 0, 1f));
        reportView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                view.removeCallbacks(refresh);
                view.post(refresh);
            }
            @Override public void onViewDetachedFromWindow(View view) { view.removeCallbacks(refresh); }
        });
        return fragmentView;
    }

    @Override public void onFragmentDestroy() {
        if (reportView != null) reportView.removeCallbacks(refresh);
        super.onFragmentDestroy();
    }
}
