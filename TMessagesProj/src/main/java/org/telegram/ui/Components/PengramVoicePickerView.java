package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramVoiceChanger;
import org.telegram.ui.ActionBar.Theme;

import androidx.annotation.NonNull;

/**
 * Плиточный выбор режима изменения голоса.
 *
 * Вместо длинного списка радио-кнопок — компактная сетка карточек со значком и названием:
 * все режимы видно сразу, выбранный подсвечен, нажатие анимируется.
 */
public class PengramVoicePickerView extends ViewGroup {

    public interface OnModeSelected {
        void onSelected(int mode);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private OnModeSelected listener;
    private int columns = 4;

    public PengramVoicePickerView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setClipChildren(false);
        for (int mode : PengramVoiceChanger.MODES) {
            addView(new Tile(context, mode));
        }
    }

    public void setOnModeSelected(OnModeSelected listener) {
        this.listener = listener;
    }

    /** перечитать выбранный режим (например, после сброса настроек) */
    public void update() {
        for (int i = 0; i < getChildCount(); ++i) {
            getChildAt(i).invalidate();
        }
    }

    private void select(int mode) {
        if (PengramConfig.getVoiceChangerMode() == mode) {
            return;
        }
        PengramConfig.setVoiceChangerMode(mode);
        PengramVoiceChanger.reset();
        if (PengramConfig.isVibrationEnabled()) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
        update();
        if (listener != null) {
            listener.onSelected(mode);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int padding = dp(10);
        final int available = Math.max(dp(80), width - padding * 2);
        columns = available >= dp(420) ? 6 : available >= dp(330) ? 5 : 4;
        final int cell = available / columns;
        final int cellHeight = dp(78);
        final int rows = (int) Math.ceil(getChildCount() / (float) columns);
        for (int i = 0; i < getChildCount(); ++i) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, rows * cellHeight + dp(8));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int padding = dp(10);
        final int available = Math.max(dp(80), getMeasuredWidth() - padding * 2);
        final int cell = available / columns;
        final int cellHeight = dp(78);
        for (int i = 0; i < getChildCount(); ++i) {
            final int column = i % columns;
            final int row = i / columns;
            final int x = padding + column * cell;
            final int y = dp(4) + row * cellHeight;
            getChildAt(i).layout(x, y, x + cell, y + cellHeight);
        }
    }

    private class Tile extends View {

        private final int mode;
        private final RectF rect = new RectF();
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint emojiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final String emoji;
        private final String name;
        private final AnimatedFloat selectedAnimated = new AnimatedFloat(this, 0, 240, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final AnimatedFloat pressedAnimated = new AnimatedFloat(this, 0, 140, CubicBezierInterpolator.DEFAULT);
        private boolean pressedState;

        Tile(Context context, int mode) {
            super(context);
            this.mode = mode;
            emoji = PengramVoiceChanger.getModeEmoji(mode);
            name = PengramVoiceChanger.getModeName(mode);
            emojiPaint.setTextSize(dp(24));
            emojiPaint.setTextAlign(Paint.Align.CENTER);
            namePaint.setTextSize(dp(11));
            namePaint.setTextAlign(Paint.Align.CENTER);
            namePaint.setTypeface(AndroidUtilities.bold());
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressedState = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pressedState && (event.getX() < 0 || event.getY() < 0 || event.getX() > getWidth() || event.getY() > getHeight())) {
                        pressedState = false;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pressedState) {
                        pressedState = false;
                        invalidate();
                        select(mode);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressedState = false;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final boolean selected = PengramConfig.getVoiceChangerMode() == mode;
            final float selectedProgress = selectedAnimated.set(selected ? 1f : 0f);
            final float pressProgress = pressedAnimated.set(pressedState ? 1f : 0f);
            final int accent = Theme.getColor(Theme.key_switch2TrackChecked, resourcesProvider);
            final int surface = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);

            final float scale = 1f - .05f * pressProgress;
            final int save = canvas.save();
            canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);

            rect.set(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(8));
            background.setColor(ColorUtilsCompat.blend(surface, Theme.blendOver(surface, Theme.multAlpha(accent, .24f)), selectedProgress));
            canvas.drawRoundRect(rect, dp(14), dp(14), background);
            if (selectedProgress > 0) {
                stroke.setColor(Theme.multAlpha(accent, selectedProgress));
                canvas.drawRoundRect(rect, dp(14), dp(14), stroke);
            }

            canvas.drawText(emoji, getWidth() / 2f, rect.top + dp(30), emojiPaint);

            namePaint.setColor(ColorUtilsCompat.blend(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider),
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider),
                    selectedProgress));
            final CharSequence shortName = TextUtils.ellipsize(name, namePaint, getWidth() - dp(10), TextUtils.TruncateAt.END);
            canvas.drawText(shortName, 0, shortName.length(), getWidth() / 2f, rect.bottom - dp(9), namePaint);

            canvas.restoreToCount(save);
        }
    }

    /** крошечная обёртка, чтобы не тянуть лишние зависимости ради смешивания цветов */
    private static final class ColorUtilsCompat {
        static int blend(int from, int to, float progress) {
            if (progress <= 0) return from;
            if (progress >= 1) return to;
            return androidx.core.graphics.ColorUtils.blendARGB(from, to, progress);
        }
    }
}
