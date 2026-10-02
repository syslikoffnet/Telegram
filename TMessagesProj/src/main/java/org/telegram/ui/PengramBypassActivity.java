package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramBypass;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Pengram: «Обход блокировок».
 * Один тумблер: приложение само находит рабочий вход и переключается на него,
 * когда Telegram перестаёт подключаться. По умолчанию выключено.
 */
public class PengramBypassActivity extends BaseFragment {

    private LinearLayout content;
    private StatusView statusView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PengramBypass));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        statusView = new StatusView(context);
        content.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 132));

        rebuild();
        fragmentView = scroll;
        return fragmentView;
    }

    private void rebuild() {
        if (content == null) {
            return;
        }
        while (content.getChildCount() > 1) {
            content.removeViewAt(1);
        }
        final Context context = getContext();

        final LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(group, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell main = new TextCheckCell(context);
        main.setTextAndCheck(getString(R.string.PengramBypassEnable), PengramBypass.isEnabled(), true);
        main.setBackground(Theme.getSelectorDrawable(false));
        main.setOnClickListener(v -> {
            final boolean enabled = !PengramBypass.isEnabled();
            main.setChecked(enabled);
            PengramBypass.setEnabled(enabled);
            if (enabled && PengramBypass.savedCount() == 0) {
                refreshList();
            }
            rebuild();
        });
        group.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        if (PengramBypass.isEnabled()) {
            final TextCheckCell auto = new TextCheckCell(context);
            auto.setTextAndValueAndCheck(getString(R.string.PengramBypassAuto),
                    getString(R.string.PengramBypassAutoInfo), PengramBypass.isAuto(), true, true);
            auto.setBackground(Theme.getSelectorDrawable(false));
            auto.setOnClickListener(v -> {
                final boolean value = !PengramBypass.isAuto();
                auto.setChecked(value);
                PengramConfig.setBool(PengramBypass.KEY_AUTO, value);
            });
            group.addView(auto, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final TextCheckCell back = new TextCheckCell(context);
            back.setTextAndValueAndCheck(getString(R.string.PengramBypassBackToDirect),
                    getString(R.string.PengramBypassBackToDirectInfo), PengramBypass.isBackToDirect(), true, false);
            back.setBackground(Theme.getSelectorDrawable(false));
            back.setOnClickListener(v -> {
                final boolean value = !PengramBypass.isBackToDirect();
                back.setChecked(value);
                PengramConfig.setBool(PengramBypass.KEY_BACK_TO_DIRECT, value);
            });
            group.addView(back, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        addInfo(getString(R.string.PengramBypassInfo));

        if (PengramBypass.isEnabled()) {
            final LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            content.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            actions.addView(action(getString(R.string.PengramBypassRefresh),
                    LocaleController.formatString(R.string.PengramBypassEntries, PengramBypass.savedCount()),
                    R.drawable.msg_retry, this::refreshList), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

            actions.addView(action(getString(R.string.PengramBypassConnectNow), null,
                    R.drawable.msg_download, () -> {
                        if (PengramBypass.useBest()) {
                            bulletin(getString(R.string.PengramBypassConnected));
                        } else {
                            bulletin(getString(R.string.PengramBypassNoEntries));
                        }
                        updateStatus();
                    }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

            actions.addView(action(getString(R.string.PengramBypassDirect), null,
                    R.drawable.msg_cancel, () -> {
                        PengramBypass.disableProxy();
                        bulletin(getString(R.string.PengramBypassDirectDone));
                        updateStatus();
                    }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

            actions.addView(action(getString(R.string.PengramBypassPaste), null,
                    R.drawable.msg_link, this::pasteLink), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

            addInfo(getString(R.string.PengramBypassLimits));
        }
        updateStatus();
    }

    private View action(CharSequence title, CharSequence value, int icon, Runnable action) {
        final TextSettingsCell cell = new TextSettingsCell(getContext());
        if (value != null) {
            cell.setTextAndValue(title, value, true);
        } else {
            cell.setTextAndIcon(title, icon, true);
        }
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> action.run());
        return cell;
    }

    private void addInfo(CharSequence text) {
        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(getContext());
        info.setText(text);
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void refreshList() {
        bulletin(getString(R.string.PengramBypassSearching));
        updateStatus();
        PengramBypass.refresh((alive, total) -> {
            bulletin(LocaleController.formatString(R.string.PengramBypassFound, alive, total));
            if (alive > 0 && PengramBypass.isEnabled() && !PengramBypass.isAuto()) {
                PengramBypass.useBest();
            }
            rebuild();
        });
    }

    private void pasteLink() {
        final Context context = getContext();
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PengramBypassPaste));
        final EditText editText = new EditText(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHint("tg://proxy?server=…&port=…&secret=…");
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setPadding(dp(4), dp(8), dp(4), dp(8));
        final FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 24, 6, 24, 0));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Add), (dialog, which) -> {
            if (PengramBypass.addFromLink(editText.getText().toString())) {
                bulletin(getString(R.string.PengramBypassConnected));
            } else {
                bulletin(getString(R.string.PengramBypassBadLink));
            }
            rebuild();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void bulletin(CharSequence text) {
        try {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show();
        } catch (Throwable ignore) {
        }
    }

    private void updateStatus() {
        if (statusView != null) {
            statusView.invalidate();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatus();
    }

    /** Живая плашка состояния: прямое соединение, поиск или уже рабочий вход. */
    private class StatusView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint title = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint subtitle = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float phase;

        StatusView(Context context) {
            super(context);
            title.setTypeface(AndroidUtilities.bold());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int state = PengramBypass.getStatus();
            final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            final int color;
            final String text;
            switch (state) {
                case PengramBypass.STATUS_PROXY:
                    color = 0xFF4BB34B;
                    text = getString(R.string.PengramBypassStateProxy);
                    break;
                case PengramBypass.STATUS_SEARCHING:
                    color = accent;
                    text = getString(R.string.PengramBypassStateSearching);
                    break;
                case PengramBypass.STATUS_FAILED:
                    color = 0xFFE05B5B;
                    text = getString(R.string.PengramBypassStateFailed);
                    break;
                case PengramBypass.STATUS_DIRECT:
                    color = 0xFF4BB34B;
                    text = getString(R.string.PengramBypassStateDirect);
                    break;
                default:
                    color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
                    text = getString(R.string.PengramBypassStateOff);
                    break;
            }
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            canvas.drawRect(0, 0, getMeasuredWidth(), getMeasuredHeight(), paint);

            final float cx = getMeasuredWidth() / 2f;
            final float cy = dp(50);
            phase += 0.02f;
            final float pulse = PengramBypass.isBusy() || state == PengramBypass.STATUS_SEARCHING
                    ? 1f + 0.12f * (float) Math.sin(phase * 3f) : 1f;
            paint.setColor(ColorUtils.setAlphaComponent(color, 36));
            canvas.drawCircle(cx, cy, dp(32) * pulse, paint);
            paint.setColor(ColorUtils.setAlphaComponent(color, 70));
            canvas.drawCircle(cx, cy, dp(22) * pulse, paint);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, dp(11), paint);

            title.setTextSize(dp(15));
            title.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(text, cx, cy + dp(38), title);

            final String details = PengramBypass.getStatusDetails();
            if (!android.text.TextUtils.isEmpty(details)) {
                subtitle.setTextSize(dp(13));
                subtitle.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                subtitle.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(details, cx, cy + dp(57), subtitle);
            }

            if (PengramBypass.isBusy() || state == PengramBypass.STATUS_SEARCHING) {
                postInvalidateOnAnimation();
            }
        }
    }
}
