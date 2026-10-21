package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PengramDeleteEffectView;

/**
 * Pengram: выбор анимации удаления с живым предпросмотром.
 * Сверху настоящий пузырь сообщения — нажал на эффект, и он тут же исчез именно так.
 */
public class PengramDeleteEffectActivity extends BaseFragment {

    private FrameLayout previewBox;
    private View sampleBubble;
    private LinearLayout list;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PengramDeleteEffectHeader));
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
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // ----- витрина -----
        previewBox = new FrameLayout(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                canvas.drawRect(rect, paint);
            }
        };
        previewBox.setWillNotDraw(false);
        root.addView(previewBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 150));

        sampleBubble = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();
            private final android.text.TextPaint text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                final float radius = dp(Math.max(2, Math.min(17, SharedConfig.bubbleRadius)));
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                paint.setColor(Theme.getColor(Theme.key_chat_outBubble));
                canvas.drawRoundRect(rect, radius, radius, paint);
                text.setTextSize(dp(SharedConfig.fontSize - 1));
                text.setColor(Theme.getColor(Theme.key_chat_messageTextOut));
                final String value = getString(R.string.PengramDeleteEffectSample);
                canvas.drawText(android.text.TextUtils.ellipsize(value, text, getMeasuredWidth() - dp(24),
                        android.text.TextUtils.TruncateAt.END).toString(), dp(12), getMeasuredHeight() / 2f + dp(5), text);
            }
        };
        previewBox.addView(sampleBubble, LayoutHelper.createFrame(230, 54, Gravity.CENTER));

        final TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        hint.setText(getString(R.string.PengramDeleteEffectPreview));
        hint.setPadding(dp(21), dp(10), dp(21), dp(10));
        root.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ----- список эффектов -----
        final ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        rebuild();

        fragmentView = root;
        return fragmentView;
    }

    private void rebuild() {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        for (int effect = 0; effect < PengramConfig.DELETE_EFFECT_COUNT; ++effect) {
            list.addView(createRow(effect), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));
        }
        final TextView info = new TextView(getContext());
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        info.setText(getString(R.string.PengramDeleteEffectIncomingInfo));
        info.setPadding(dp(21), dp(12), dp(21), dp(24));
        list.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private View createRow(int effect) {
        final Context context = getContext();
        final FrameLayout row = new FrameLayout(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (PengramConfig.getDeleteEffect() == effect) {
                    paint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 22));
                    canvas.drawRect(0, 0, getMeasuredWidth(), getMeasuredHeight(), paint);
                }
                super.dispatchDraw(canvas);
            }
        };
        row.setWillNotDraw(false);
        row.setBackground(Theme.getSelectorDrawable(false));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setText(getString(PengramConfig.getDeleteEffectName(effect)));
        row.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 21, 10, 60, 0));

        final TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        subtitle.setText(getString(PengramConfig.getDeleteEffectInfo(effect)));
        row.addView(subtitle, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 21, 31, 60, 0));

        final View check = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                if (PengramConfig.getDeleteEffect() != effect) {
                    return;
                }
                paint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                canvas.drawCircle(getMeasuredWidth() / 2f, getMeasuredHeight() / 2f, dp(5), paint);
            }
        };
        row.addView(check, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        row.setOnClickListener(v -> {
            final boolean changed = PengramConfig.getDeleteEffect() != effect;
            PengramConfig.setDeleteEffect(effect);
            AndroidUtilities.vibrateCursor(v);
            if (changed) {
                for (int a = 0; a < list.getChildCount(); ++a) {
                    final View child = list.getChildAt(a);
                    child.invalidate();
                    if (child instanceof FrameLayout && ((FrameLayout) child).getChildCount() > 2) {
                        ((FrameLayout) child).getChildAt(2).invalidate();
                    }
                }
            }
            playPreview(effect);
        });
        return row;
    }

    /** отложенный возврат пузыря — его обязательно нужно отменять при новом запуске */
    private Runnable restoreBubble;

    /** проиграть эффект на витрине и вернуть пузырь обратно */
    private void playPreview(int effect) {
        if (previewBox == null || sampleBubble == null) {
            return;
        }
        // Главная причина рывков: быстрые нажатия по списку накладывали эффекты друг
        // на друга — несколько полноэкранных накладок рисовались одновременно.
        // Предыдущий эффект и отложенный возврат пузыря снимаем сразу.
        if (restoreBubble != null) {
            AndroidUtilities.cancelRunOnUIThread(restoreBubble);
            restoreBubble = null;
        }
        PengramDeleteEffectView.cancelAll(previewBox);
        sampleBubble.animate().cancel();

        if (effect == PengramConfig.DELETE_EFFECT_NONE) {
            sampleBubble.setAlpha(1f);
            sampleBubble.setScaleX(1f);
            sampleBubble.setScaleY(1f);
            sampleBubble.animate().alpha(0f).setDuration(120)
                    .withEndAction(() -> sampleBubble.animate().alpha(1f).setStartDelay(260).setDuration(160).start()).start();
            return;
        }

        sampleBubble.setAlpha(1f);
        sampleBubble.setScaleX(1f);
        sampleBubble.setScaleY(1f);
        if (!PengramDeleteEffectView.play(previewBox, sampleBubble, effect)) {
            return;
        }
        sampleBubble.setAlpha(0f);

        // ждём ровно столько, сколько длится сам эффект, а не наугад полторы секунды
        restoreBubble = () -> {
            restoreBubble = null;
            if (sampleBubble == null) {
                return;
            }
            sampleBubble.setScaleX(0.9f);
            sampleBubble.setScaleY(0.9f);
            sampleBubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        };
        AndroidUtilities.runOnUIThread(restoreBubble, PengramDeleteEffectView.durationFor(effect) + 180);
    }

    @Override
    public void onFragmentDestroy() {
        if (restoreBubble != null) {
            AndroidUtilities.cancelRunOnUIThread(restoreBubble);
            restoreBubble = null;
        }
        PengramDeleteEffectView.cancelAll(previewBox);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        // раньше список пересобирался целиком при каждом возврате на экран —
        // пятнадцать новых вьюх на пустом месте; достаточно перерисовать имеющиеся
        if (list == null) {
            return;
        }
        for (int a = 0; a < list.getChildCount(); ++a) {
            list.getChildAt(a).invalidate();
        }
    }
}
