package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: шапка раздела настроек.
 *
 * Вместо «простыни из переключателей» каждый раздел начинается с карточки:
 * крупный значок в цветном круге, название раздела и строка о том, что здесь
 * вообще происходит. Сразу понятно, куда попал.
 */
public class PengramSectionHero extends FrameLayout {

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF circle = new RectF();
    private final int colorTop;
    private final int colorBottom;
    private int shaderHeight = -1;

    public PengramSectionHero(Context context, int icon, CharSequence title, CharSequence subtitle, int colorTop, int colorBottom) {
        super(context);
        this.colorTop = colorTop;
        this.colorBottom = colorBottom;
        setWillNotDraw(false);
        setPadding(dp(16), dp(10), dp(16), dp(10));

        final ImageView iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setImageResource(icon);
        iconView.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        addView(iconView, LayoutHelper.createFrame(56, 56, Gravity.LEFT | Gravity.CENTER_VERTICAL, 28, 0, 0, 0));

        final TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(title);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 100, 26, 26, 0));

        final TextView subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        subtitleView.setLineSpacing(dp(1.5f), 1f);
        subtitleView.setMaxLines(3);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setText(subtitle);
        addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 100, 50, 26, 0));

        setMinimumHeight(dp(108));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(getMeasuredWidth(), Math.max(dp(108), getMeasuredHeight()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth();
        final int height = getHeight();

        // мягкая карточка под всем блоком — она отделяет шапку от списка
        rect.set(dp(10), dp(6), width - dp(10), height - dp(6));
        cardPaint.setColor(ColorUtils.blendARGB(
                Theme.getColor(Theme.key_windowBackgroundWhite), colorTop, 0.10f));
        canvas.drawRoundRect(rect, dp(16), dp(16), cardPaint);

        circle.set(dp(28), (height - dp(56)) / 2f, dp(84), (height + dp(56)) / 2f);
        if (shaderHeight != height) {
            shaderHeight = height;
            circlePaint.setShader(new LinearGradient(0, circle.top, 0, circle.bottom,
                    new int[]{colorTop, colorBottom}, null, Shader.TileMode.CLAMP));
        }
        canvas.drawRoundRect(circle, dp(18), dp(18), circlePaint);
    }
}
