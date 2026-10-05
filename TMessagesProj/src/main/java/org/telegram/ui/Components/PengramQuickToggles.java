package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: ряд «быстрых тумблеров» в самом верху настроек.
 *
 * Четыре вещи, которые переключают чаще всего (призрак, антиудаление, реклама,
 * премиум), вынесены плитками: одно касание — и не надо нырять в раздел.
 * Включённая плитка заливается своим цветом, выключенная остаётся спокойной.
 */
public class PengramQuickToggles extends LinearLayout {

    public interface Toggle {
        boolean isOn();
        void toggle();
    }

    private final Tile[] tiles;

    public PengramQuickToggles(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setPadding(dp(10), dp(4), dp(10), dp(10));
        tiles = new Tile[4];
    }

    public PengramQuickToggles add(int index, int icon, CharSequence title, int color, Toggle toggle, Runnable after) {
        final Tile tile = new Tile(getContext(), icon, title, color, toggle, after);
        tiles[index] = tile;
        addView(tile, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 3, 0, 3, 0));
        return this;
    }

    /** перерисовать состояния — вызывается, когда список обновился */
    public void update() {
        for (Tile tile : tiles) {
            if (tile != null) {
                tile.sync(false);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(84), MeasureSpec.EXACTLY));
    }

    private static class Tile extends FrameLayout {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final ImageView iconView;
        private final TextView titleView;
        private final int color;
        private final Toggle toggle;
        private final Runnable after;

        private final AnimatedFloat progress = new AnimatedFloat(this, 0, 260, CubicBezierInterpolator.EASE_OUT_QUINT);
        private boolean on;

        Tile(Context context, int icon, CharSequence title, int color, Toggle toggle, Runnable after) {
            super(context);
            this.color = color;
            this.toggle = toggle;
            this.after = after;
            setWillNotDraw(false);

            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iconView.setImageResource(icon);
            addView(iconView, LayoutHelper.createFrame(26, 26, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 14, 0, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(Gravity.CENTER_HORIZONTAL);
            titleView.setMaxLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setText(title);
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP, 4, 46, 4, 0));

            on = toggle.isOn();
            progress.set(on ? 1f : 0f, true);
            sync(false);

            setOnClickListener(v -> {
                toggle.toggle();
                AndroidUtilities.vibrateCursor(v);
                sync(true);
                // короткий «клевок» плитки, чтобы нажатие читалось без текста
                animate().cancel();
                setScaleX(0.93f);
                setScaleY(0.93f);
                animate().scaleX(1f).scaleY(1f).setDuration(240)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                if (after != null) {
                    after.run();
                }
            });
        }

        void sync(boolean animated) {
            on = toggle.isOn();
            if (!animated) {
                progress.set(on ? 1f : 0f, true);
            }
            iconView.setColorFilter(new PorterDuffColorFilter(
                    on ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            titleView.setTextColor(on ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float t = progress.set(on ? 1f : 0f);
            rect.set(0, 0, getWidth(), getHeight());
            bgPaint.setColor(ColorUtils.blendARGB(
                    ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite),
                            Theme.getColor(Theme.key_windowBackgroundGray), 0.55f),
                    color, t));
            canvas.drawRoundRect(rect, dp(14), dp(14), bgPaint);

            // лёгкая обводка у включённой плитки
            if (t > 0.01f) {
                bgPaint.setColor(ColorUtils.setAlphaComponent(0xFFFFFFFF, (int) (40 * t)));
                canvas.drawRoundRect(rect, dp(14), dp(14), bgPaint);
            }
            if (progress.isInProgress()) {
                invalidate();
            }
        }
    }

    public View view() {
        return this;
    }
}
