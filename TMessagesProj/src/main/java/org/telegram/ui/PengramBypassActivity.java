package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramBypass;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Pengram: экран обхода блокировок.
 *
 * Ровно один переключатель и живой кружок состояния. Никаких способов,
 * списков входов и настроек: всё, что нужно сделать человеку, — включить.
 */
public class PengramBypassActivity extends BaseFragment {

    private StatusView statusView;
    private TextCheckCell switchCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PengramBypass));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        statusView = new StatusView(context);
        root.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 132));

        switchCell = new TextCheckCell(context);
        switchCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        switchCell.setOnClickListener(v -> {
            final boolean value = !PengramBypass.isEnabled();
            switchCell.setChecked(value);
            PengramBypass.setEnabled(value);
            updateAll();
        });
        root.addView(switchCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell mainInfo = new TextInfoPrivacyCell(context);
        mainInfo.setText(LocaleController.getString(R.string.PengramBypassMainInfo));
        root.addView(mainInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        container.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = container;

        updateAll();
        return fragmentView;
    }

    @Override
    public boolean onFragmentCreate() {
        PengramBypass.setListener(this::updateAll);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        PengramBypass.setListener(null);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAll();
    }

    private void updateAll() {
        if (switchCell == null) {
            return;
        }
        switchCell.setTextAndValueAndCheck(
                LocaleController.getString(R.string.PengramBypassMain),
                String.valueOf(stateText()),
                PengramBypass.isEnabled(), true, false);
        if (statusView != null) {
            statusView.update();
        }
    }

    private CharSequence stateText() {
        return shortState();
    }

    /** живой кружок: спокойный зелёный, тревожный оранжевый, ищущий синий с пульсом */
    private class StatusView extends View {

        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaintHolder title = new TextPaintHolder(20, true);
        private final TextPaintHolder subtitle = new TextPaintHolder(14, false);
        private ValueAnimator pulse;
        private float phase;

        StatusView(Context context) {
            super(context);
        }

        void update() {
            invalidate();
            final boolean animate = PengramBypass.getStatus() == PengramBypass.STATUS_SEARCHING;
            if (animate && pulse == null) {
                pulse = ValueAnimator.ofFloat(0f, 1f);
                pulse.setDuration(1400);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setInterpolator(CubicBezierInterpolator.DEFAULT);
                pulse.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
                pulse.start();
            } else if (!animate && pulse != null) {
                pulse.cancel();
                pulse = null;
                phase = 0;
                invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (pulse != null) {
                pulse.cancel();
                pulse = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int status = PengramBypass.getStatus();
            int color;
            if (status == PengramBypass.STATUS_PROXY || status == PengramBypass.STATUS_DIRECT) {
                color = 0xff4bb34b;
            } else if (status == PengramBypass.STATUS_FAILED) {
                color = 0xffe8663a;
            } else if (status == PengramBypass.STATUS_SEARCHING) {
                color = Theme.getColor(Theme.key_featuredStickers_addButton);
            } else {
                color = Theme.getColor(Theme.key_windowBackgroundWhiteHintText);
            }
            final float cx = AndroidUtilities.dp(42);
            final float cy = getMeasuredHeight() / 2f;

            if (phase > 0) {
                wavePaint.setColor(color);
                wavePaint.setAlpha((int) (90 * (1f - phase)));
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(16) + AndroidUtilities.dp(20) * phase, wavePaint);
            }
            circlePaint.setColor(color);
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(14), circlePaint);

            final float left = AndroidUtilities.dp(76);
            title.paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            subtitle.paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            final String top = String.valueOf(stateText());
            String bottom = PengramBypass.getStatusDetails();
            if (TextUtils.isEmpty(bottom)) {
                bottom = LocaleController.getString(R.string.PengramBypassInfo);
            }
            canvas.drawText(top, left, cy - AndroidUtilities.dp(4), title.paint);
            final CharSequence ellipsized = TextUtils.ellipsize(bottom, subtitle.textPaint(),
                    getMeasuredWidth() - left - AndroidUtilities.dp(18), TextUtils.TruncateAt.END);
            canvas.drawText(String.valueOf(ellipsized), left, cy + AndroidUtilities.dp(20), subtitle.paint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(132));
        }
    }

    /** маленькая обёртка, чтобы не плодить настройку кистей в onDraw */
    private static class TextPaintHolder {
        final android.text.TextPaint paint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);

        TextPaintHolder(int sizeDp, boolean bold) {
            paint.setTextSize(AndroidUtilities.dp(sizeDp));
            if (bold) {
                paint.setTypeface(AndroidUtilities.bold());
            }
        }

        android.text.TextPaint textPaint() {
            return paint;
        }
    }

    @Override
    public boolean needDelayOpenAnimation() {
        return true;
    }

    public static CharSequence shortState() {
        switch (PengramBypass.getStatus()) {
            case PengramBypass.STATUS_PROXY:
                return LocaleController.getString(R.string.PengramBypassStateProxy);
            case PengramBypass.STATUS_SEARCHING:
                return LocaleController.getString(R.string.PengramBypassStateSearching);
            case PengramBypass.STATUS_FAILED:
                return LocaleController.getString(R.string.PengramBypassStateFailed);
            case PengramBypass.STATUS_DIRECT:
                return LocaleController.getString(R.string.PengramBypassStateDirect);
            default:
                return LocaleController.getString(R.string.PengramBypassStateOff);
        }
    }
}
