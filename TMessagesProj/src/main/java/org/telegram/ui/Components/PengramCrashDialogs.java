package org.telegram.ui.Components;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramCrashReport;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Pengram: окна отчёта о вылете.
 *
 * <p>Сам отчёт собирается в {@link PengramCrashReport} в момент падения и тогда же
 * попадает в буфер обмена. Здесь — только показ: окно «мы упали, причина уже
 * скопирована» при следующем запуске и журнал последних падений в настройках.
 */
public final class PengramCrashDialogs {

    private static boolean shownThisSession;

    private PengramCrashDialogs() {}

    /** окно после вылета — один раз за запуск */
    public static void showPendingIfNeeded(Context context) {
        if (shownThisSession || !(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final String report = PengramCrashReport.pendingReport();
        if (report == null) return;
        // отчёт уже в буфере, но его могли затереть, пока приложение было закрыто
        final boolean copied = PengramCrashReport.isCopyEnabled() && PengramCrashReport.copy(report);
        final String reason = PengramCrashReport.shortReason(report);
        final StringBuilder message = new StringBuilder();
        if (!TextUtils.isEmpty(reason)) {
            message.append(reason).append("\n\n");
        }
        message.append(getString(copied ? R.string.PengramCrashCopiedInfo : R.string.PengramCrashNotCopiedInfo));

        try {
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(getString(R.string.PengramCrashTitle))
                    .setMessage(message.toString())
                    .setPositiveButton(getString(R.string.PengramCrashCopy), (d, w) -> {
                        PengramCrashReport.acknowledgePending(report);
                        copyWithToast(context, report);
                    })
                    .setNeutralButton(getString(R.string.PengramCrashDetails), (d, w) -> {
                        PengramCrashReport.acknowledgePending(report);
                        showReport(context, report);
                    })
                    .setNegativeButton(getString(R.string.Close), (d, w) ->
                            PengramCrashReport.acknowledgePending(report))
                    .show();
            shownThisSession = dialog.isShowing();
        } catch (Throwable error) {
            // A destroyed Activity or an overlay may prevent the dialog; retry
            // on the next resume instead of losing the only copy of the report.
            org.telegram.messenger.FileLog.e(error);
        }
    }

    /** полный текст одного отчёта */
    public static void showReport(Context context, String report) {
        if (context == null || report == null) {
            return;
        }
        final TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        text.setTextIsSelectable(true);
        text.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(6));
        text.setText(report);

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(text, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.PengramCrashReportTitle))
                .setView(scroll)
                .setPositiveButton(getString(R.string.PengramCrashCopy), (d, w) -> copyWithToast(context, report))
                .setNeutralButton(getString(R.string.PengramCrashShare), (d, w) -> share(context, report))
                .setNegativeButton(getString(R.string.Close), null)
                .show();
    }

    /** журнал последних падений */
    public static void showJournal(BaseFragment fragment) {
        if (fragment == null || fragment.getContext() == null) {
            return;
        }
        final Context context = fragment.getContext();
        final ArrayList<String> reports = PengramCrashReport.all();
        if (reports.isEmpty()) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info,
                    getString(R.string.PengramCrashEmpty)).show();
            return;
        }
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        final int padH = AndroidUtilities.dp(20);
        final int padV = AndroidUtilities.dp(10);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int subColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
        final SimpleDateFormat fmt = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());

        // свежие сверху
        for (int a = reports.size() - 1; a >= 0; --a) {
            final String report = reports.get(a);
            final long when = PengramCrashReport.timeOf(report);

            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(padH, padV, padH, padV);
            row.setBackground(Theme.getSelectorDrawable(false));

            final ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.msg_report);
            icon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            row.addView(icon, LayoutHelper.createLinear(22, 22));

            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(textColor);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            final String reason = PengramCrashReport.shortReason(report);
            title.setText(TextUtils.isEmpty(reason) ? getString(R.string.PengramCrashReportTitle) : reason);
            texts.addView(title);
            final TextView sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            sub.setTextColor(subColor);
            sub.setText(when > 0 ? fmt.format(new Date(when)) : getString(R.string.PengramCrashTapToCopy));
            texts.addView(sub);
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

            row.setOnClickListener(v -> showReport(context, report));
            list.addView(row);
        }

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        final String last = PengramCrashReport.last();
        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.PengramCrashReports))
                .setView(scroll)
                .setPositiveButton(getString(R.string.PengramCrashCopyLast), (d, w) -> copyWithToast(context, last))
                .setNeutralButton(getString(R.string.PengramCrashClear), (d, w) -> {
                    PengramCrashReport.clear();
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip,
                            getString(R.string.PengramCrashCleared)).show();
                })
                .setNegativeButton(getString(R.string.Close), null)
                .show();
    }

    private static void copyWithToast(Context context, String report) {
        if (PengramCrashReport.copy(report)) {
            try {
                android.widget.Toast.makeText(context, getString(R.string.PengramCrashCopied),
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void share(Context context, String report) {
        try {
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "Pengram crash");
            intent.putExtra(Intent.EXTRA_TEXT, report);
            context.startActivity(Intent.createChooser(intent, getString(R.string.PengramCrashShare)));
        } catch (Throwable ignore) {
        }
    }

    /** подпись для строки настроек: «3 · 5 окт, 20:14» */
    public static String summary() {
        final int count = PengramCrashReport.count();
        if (count == 0) {
            return getString(R.string.PengramCrashNone);
        }
        final long when = PengramCrashReport.timeOf(PengramCrashReport.last());
        if (when <= 0) {
            return String.valueOf(count);
        }
        return count + " · " + LocaleController.getInstance().getFormatterStats().format(new Date(when));
    }
}
