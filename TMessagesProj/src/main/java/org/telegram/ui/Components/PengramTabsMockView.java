package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: живой макет нижней панели вкладок.
 * Никаких галочек — вкладка выключается прямо на макете, по нажатию на неё.
 */
public class PengramTabsMockView extends View {

    public interface OnChanged {
        void onChanged();
    }

    private static class Tab {
        final String key;
        final int iconRes;
        final int titleRes;
        Drawable icon;
        float progress = 1f;     // 1 — вкладка на месте, 0 — скрыта
        final RectF bounds = new RectF();

        Tab(String key, int iconRes, int titleRes) {
            this.key = key;
            this.iconRes = iconRes;
            this.titleRes = titleRes;
        }

        boolean hidden() {
            return key != null && PengramConfig.getBool(key, false);
        }
    }

    private final Tab[] tabs = new Tab[]{
            new Tab(null, R.drawable.msg_discussion, R.string.PengramTabChats),
            new Tab(PengramConfig.KEY_TAB_CONTACTS, R.drawable.msg_contacts, R.string.PengramTabContacts),
            new Tab(PengramConfig.KEY_TAB_CALLS, R.drawable.msg_calls, R.string.PengramTabCalls),
            new Tab(PengramConfig.KEY_TAB_SETTINGS, R.drawable.msg_settings, R.string.PengramTabSettings),
            new Tab(PengramConfig.KEY_TAB_PROFILE, R.drawable.msg_openprofile, R.string.PengramTabProfile),
    };

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private OnChanged onChanged;
    private long lastFrame;

    public PengramTabsMockView(Context context) {
        super(context);
        labelPaint.setTextSize(dp(11));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextSize(dp(13));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(dp(1.5f));
        dashPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(4), dp(3)}, 0));
        for (Tab tab : tabs) {
            tab.icon = context.getResources().getDrawable(tab.iconRes).mutate();
            tab.progress = tab.hidden() ? 0f : 1f;
        }
    }

    public void setOnChanged(OnChanged listener) {
        this.onChanged = listener;
    }

    public void sync() {
        for (Tab tab : tabs) {
            tab.progress = tab.hidden() ? 0f : 1f;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(150), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = android.os.SystemClock.elapsedRealtime();
        final float delta = lastFrame == 0 ? 16 : Math.min(32, now - lastFrame);
        lastFrame = now;
        boolean animating = false;

        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);

        hintPaint.setColor(textColor);
        final String hint = getString(R.string.PengramTabsMockHint);
        canvas.drawText(TextUtils_ellipsize(hint, hintPaint, getMeasuredWidth() - dp(24)),
                getMeasuredWidth() / 2f, dp(18), hintPaint);

        // «телефон»
        final float barTop = dp(40);
        final float barBottom = getMeasuredHeight() - dp(10);
        rect.set(dp(16), barTop, getMeasuredWidth() - dp(16), barBottom);
        backgroundPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        backgroundPaint.setShadowLayer(dp(6), 0, dp(1), 0x1a000000);
        canvas.drawRoundRect(rect, dp(18), dp(18), backgroundPaint);
        linePaint.setColor(ColorUtils.setAlphaComponent(textColor, 40));
        canvas.drawLine(rect.left + dp(10), barTop + dp(28), rect.right - dp(10), barTop + dp(28), linePaint);

        final float cellWidth = (rect.width() - dp(12)) / tabs.length;
        final float centerY = barTop + dp(28) + (barBottom - barTop - dp(28)) / 2f;

        for (int a = 0; a < tabs.length; ++a) {
            final Tab tab = tabs[a];
            final float target = tab.hidden() ? 0f : 1f;
            if (Math.abs(tab.progress - target) > 0.001f) {
                tab.progress += (target > tab.progress ? 1 : -1) * delta / 200f;
                tab.progress = Math.max(0, Math.min(1, tab.progress));
                animating = true;
            } else {
                tab.progress = target;
            }

            final float cx = rect.left + dp(6) + cellWidth * (a + 0.5f);
            tab.bounds.set(cx - cellWidth / 2f, barTop + dp(28), cx + cellWidth / 2f, barBottom);

            final float alpha = AndroidUtilities.lerp(0.22f, 1f, tab.progress);
            final float scale = AndroidUtilities.lerp(0.82f, 1f, tab.progress);
            final int color = ColorUtils.blendARGB(textColor, a == 0 ? accent : textColor, tab.progress);

            if (tab.progress < 0.99f) {
                dashPaint.setColor(ColorUtils.setAlphaComponent(textColor, (int) (90 * (1f - tab.progress))));
                rect.set(cx - cellWidth / 2f + dp(3), tab.bounds.top + dp(3), cx + cellWidth / 2f - dp(3), barBottom - dp(3));
                canvas.drawRoundRect(rect, dp(10), dp(10), dashPaint);
                rect.set(dp(16), barTop, getMeasuredWidth() - dp(16), barBottom);
            }

            canvas.save();
            canvas.scale(scale, scale, cx, centerY - dp(6));
            final int size = dp(22);
            tab.icon.setColorFilter(new PorterDuffColorFilter(ColorUtils.setAlphaComponent(color, (int) (255 * alpha)), PorterDuff.Mode.SRC_IN));
            tab.icon.setBounds((int) (cx - size / 2f), (int) (centerY - dp(12) - size / 2f),
                    (int) (cx + size / 2f), (int) (centerY - dp(12) + size / 2f));
            tab.icon.draw(canvas);

            labelPaint.setColor(ColorUtils.setAlphaComponent(color, (int) (255 * alpha)));
            labelPaint.setTypeface(tab.progress > 0.5f ? AndroidUtilities.bold() : Typeface.DEFAULT);
            canvas.drawText(TextUtils_ellipsize(getString(tab.titleRes), labelPaint, cellWidth - dp(4)),
                    cx, centerY + dp(12), labelPaint);
            canvas.restore();
        }

        if (animating) {
            invalidate();
        }
    }

    private static String TextUtils_ellipsize(String text, TextPaint paint, float width) {
        return android.text.TextUtils.ellipsize(text, paint, Math.max(dp(20), width), android.text.TextUtils.TruncateAt.END).toString();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return true;
        }
        for (int a = 1; a < tabs.length; ++a) {
            final Tab tab = tabs[a];
            if (event.getX() >= tab.bounds.left && event.getX() <= tab.bounds.right
                    && event.getY() >= tab.bounds.top - dp(20) && event.getY() <= tab.bounds.bottom + dp(6)) {
                PengramConfig.setBool(tab.key, !tab.hidden());
                try {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, 2);
                } catch (Exception ignore) {
                }
                invalidate();
                if (onChanged != null) {
                    onChanged.onChanged();
                }
                return true;
            }
        }
        if (event.getY() < dp(40)) {
            return true;
        }
        return true;
    }
}
