package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
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
 * крупный значок в цветной плашке, название раздела и строка о том, что здесь
 * вообще происходит. Значок на появлении слегка «выпрыгивает», а под ним
 * дышит мягкое свечение цвета раздела — экран перестаёт быть мёртвым списком.
 */
public class PengramSectionHero extends FrameLayout {

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF icon = new RectF();

    private final int colorTop;
    private final int colorBottom;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView subtitleView;
    private boolean narrow;

    private int shaderHeight = -1;
    private RadialGradient glowShader;
    private float glowShaderX = Float.NaN;
    private float glowShaderY = Float.NaN;
    private float appear;
    private float breath;
    private ValueAnimator appearAnimator;
    private ValueAnimator breathAnimator;

    public PengramSectionHero(Context context, int iconRes, CharSequence title, CharSequence subtitle, int colorTop, int colorBottom) {
        super(context);
        this.colorTop = colorTop;
        this.colorBottom = colorBottom;
        setWillNotDraw(false);

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconView.setImageResource(iconRes);
        iconView.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        addView(iconView, LayoutHelper.createFrame(30, 30, Gravity.LEFT | Gravity.CENTER_VERTICAL, 41, 0, 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setMaxLines(Integer.MAX_VALUE);
        titleView.setText(title);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 100, 27, 26, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        subtitleView.setLineSpacing(dp(2), 1f);
        subtitleView.setMaxLines(Integer.MAX_VALUE);
        subtitleView.setText(subtitle);
        addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 100, 52, 26, 0));

        setMinimumHeight(dp(112));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        narrow = width < dp(330) || getResources().getConfiguration().fontScale > 1.4f;
        FrameLayout.LayoutParams iconParams = (FrameLayout.LayoutParams) iconView.getLayoutParams();
        iconParams.gravity = Gravity.LEFT | (narrow ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        iconParams.topMargin = narrow ? dp(37) : 0;
        FrameLayout.LayoutParams titleParams = (FrameLayout.LayoutParams) titleView.getLayoutParams();
        FrameLayout.LayoutParams subtitleParams = (FrameLayout.LayoutParams) subtitleView.getLayoutParams();
        titleParams.leftMargin = subtitleParams.leftMargin = dp(narrow ? 28 : 100);
        titleParams.topMargin = dp(narrow ? 92 : 24);
        titleView.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width - dp(narrow ? 56 : 126)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        subtitleParams.topMargin = titleParams.topMargin + titleView.getMeasuredHeight() + dp(5);
        subtitleView.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width - dp(narrow ? 56 : 126)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int height = Math.max(dp(narrow ? 170 : 112), subtitleParams.topMargin + subtitleView.getMeasuredHeight() + dp(22));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // один короткий «выпрыг» значка при открытии раздела
        appear = 0;
        iconView.setScaleX(0.6f);
        iconView.setScaleY(0.6f);
        iconView.animate().scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();

        if (appearAnimator != null) appearAnimator.cancel();
        appearAnimator = ValueAnimator.ofFloat(0f, 1f);
        appearAnimator.setDuration(380);
        appearAnimator.addUpdateListener(a -> {
            appear = (float) a.getAnimatedValue();
            invalidate();
        });
        appearAnimator.start();

        if (breathAnimator == null) {
            breathAnimator = ValueAnimator.ofFloat(0f, 1f);
            breathAnimator.setDuration(2600);
            breathAnimator.setRepeatCount(ValueAnimator.INFINITE);
            breathAnimator.setRepeatMode(ValueAnimator.REVERSE);
            breathAnimator.addUpdateListener(a -> {
                breath = (float) a.getAnimatedValue();
                invalidate();
            });
        }
        if (!breathAnimator.isRunning()) {
            breathAnimator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        iconView.animate().cancel();
        if (appearAnimator != null) appearAnimator.cancel();
        if (breathAnimator != null) breathAnimator.cancel();
        super.onDetachedFromWindow();
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

        icon.set(dp(28), narrow ? dp(24) : (height - dp(56)) / 2f,
                dp(84), narrow ? dp(80) : (height + dp(56)) / 2f);

        // Reuse the gradient: the old path allocated a shader and colors on every frame.
        final float cx = icon.centerX(), cy = icon.centerY();
        final float baseRadius = dp(36);
        if (glowShader == null || glowShaderX != cx || glowShaderY != cy) {
            glowShaderX = cx;
            glowShaderY = cy;
            glowShader = new RadialGradient(cx, cy, baseRadius,
                    new int[]{ColorUtils.setAlphaComponent(colorTop, 255), 0},
                    null, Shader.TileMode.CLAMP);
            glowPaint.setShader(glowShader);
        }
        glowPaint.setAlpha((int) ((42 + 26 * breath) * appear));
        canvas.save();
        float glowScale = (baseRadius + dp(6) * breath) / baseRadius;
        canvas.scale(glowScale, glowScale, cx, cy);
        canvas.drawCircle(cx, cy, baseRadius, glowPaint);
        canvas.restore();

        if (shaderHeight != height) {
            shaderHeight = height;
            iconPaint.setShader(new LinearGradient(0, icon.top, 0, icon.bottom,
                    new int[]{colorTop, colorBottom}, null, Shader.TileMode.CLAMP));
        }
        final float scale = 0.7f + 0.3f * AndroidUtilities.overshootInterpolator.getInterpolation(Math.min(1f, appear));
        canvas.save();
        canvas.scale(scale, scale, icon.centerX(), icon.centerY());
        canvas.drawRoundRect(icon, dp(18), dp(18), iconPaint);
        canvas.restore();
    }

    /** высота блока целиком — чтобы список мог зарезервировать место */
    public static int height() {
        return dp(112);
    }

    public View view() {
        return this;
    }
}
