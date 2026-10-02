package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: живое превью шрифта.
 * Это не картинка-пример, а рабочий макет: нажатие на любой пузырь меняет шрифт прямо здесь.
 */
public class PengramFontPreviewView extends View {

    public interface OnChanged {
        void onChanged();
    }

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private StaticLayout incoming;
    private StaticLayout outgoing;
    private int lastWidth;
    private float appear = 1f;
    private long lastFrame;

    private OnChanged onChanged;

    public PengramFontPreviewView(Context context) {
        super(context);
        hintPaint.setTextSize(dp(12));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setTextSize(dp(11));
    }

    public void setOnChanged(OnChanged listener) {
        this.onChanged = listener;
    }

    public static Typeface typefaceFor(int font) {
        switch (font) {
            case PengramConfig.FONT_SYSTEM: return Typeface.DEFAULT;
            case PengramConfig.FONT_SERIF: return Typeface.SERIF;
            case PengramConfig.FONT_MONOSPACE: return Typeface.MONOSPACE;
            default: return Typeface.DEFAULT;
        }
    }

    public void update() {
        lastWidth = 0;
        appear = 0f;
        requestLayout();
        invalidate();
    }

    private void build(int width) {
        if (width <= 0 || width == lastWidth) {
            return;
        }
        lastWidth = width;
        textPaint.setTextSize(dp(SharedConfig.fontSize));
        textPaint.setTypeface(typefaceFor(PengramConfig.appFont));
        final int maxWidth = (int) (width * 0.72f) - dp(24);
        incoming = new StaticLayout(getString(R.string.PengramFontPreviewIn), textPaint,
                Math.max(dp(40), maxWidth), Layout.Alignment.ALIGN_NORMAL, 1.1f, 0, false);
        outgoing = new StaticLayout(getString(R.string.PengramFontPreviewOut), textPaint,
                Math.max(dp(40), maxWidth), Layout.Alignment.ALIGN_NORMAL, 1.1f, 0, false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        build(width);
        final int height = dp(44)
                + (incoming != null ? incoming.getHeight() : dp(20))
                + (outgoing != null ? outgoing.getHeight() : dp(20))
                + dp(40);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        build(getMeasuredWidth());
        if (incoming == null || outgoing == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final float delta = lastFrame == 0 ? 16 : Math.min(32, now - lastFrame);
        lastFrame = now;
        if (appear < 1f) {
            appear = Math.min(1f, appear + delta / 220f);
            invalidate();
        }

        final float radius = dp(Math.max(0, Math.min(17, SharedConfig.bubbleRadius)));
        final int inColor = Theme.getColor(Theme.key_chat_inBubble);
        final int outColor = Theme.getColor(Theme.key_chat_outBubble);
        final int inText = Theme.getColor(Theme.key_chat_messageTextIn);
        final int outText = Theme.getColor(Theme.key_chat_messageTextOut);

        float y = dp(10);

        // входящее
        canvas.save();
        canvas.scale(AndroidUtilities.lerp(0.94f, 1f, appear), AndroidUtilities.lerp(0.94f, 1f, appear), dp(12), y);
        bubblePaint.setColor(inColor);
        rect.set(dp(12), y, dp(12) + incoming.getWidth() + dp(24), y + incoming.getHeight() + dp(16));
        canvas.drawRoundRect(rect, radius, radius, bubblePaint);
        canvas.save();
        canvas.translate(dp(24), y + dp(8));
        textPaint.setColor(inText);
        textPaint.setAlpha((int) (255 * appear));
        incoming.draw(canvas);
        canvas.restore();
        canvas.restore();

        y += incoming.getHeight() + dp(24);

        // исходящее
        canvas.save();
        canvas.scale(AndroidUtilities.lerp(0.94f, 1f, appear), AndroidUtilities.lerp(0.94f, 1f, appear), getMeasuredWidth() - dp(12), y);
        bubblePaint.setColor(outColor);
        final float right = getMeasuredWidth() - dp(12);
        rect.set(right - outgoing.getWidth() - dp(24), y, right, y + outgoing.getHeight() + dp(16));
        canvas.drawRoundRect(rect, radius, radius, bubblePaint);
        canvas.save();
        canvas.translate(rect.left + dp(12), y + dp(8));
        textPaint.setColor(outText);
        textPaint.setAlpha((int) (255 * appear));
        outgoing.draw(canvas);
        canvas.restore();
        canvas.restore();

        y += outgoing.getHeight() + dp(30);

        hintPaint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 220));
        canvas.drawText(getString(R.string.PengramFontPreviewHint), getMeasuredWidth() / 2f, y, hintPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            PengramConfig.setAppFont((PengramConfig.appFont + 1) % 4);
            try {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, 2);
            } catch (Exception ignore) {
            }
            update();
            if (onChanged != null) {
                onChanged.onChanged();
            }
        }
        return true;
    }
}
