package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

/** Interactive, isolated preview: it never touches the real chat draft. */
public class PengramTypingPreviewView extends FrameLayout {
    private final EditText input;
    private final TextView caption;
    private Animator animator;
    private long lastFrame;
    private boolean internalChange;

    public PengramTypingPreviewView(Context context) {
        super(context);
        setPadding(dp(16), dp(10), dp(16), dp(12));

        caption = new TextView(context);
        caption.setTextSize(13);
        caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        addView(caption, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 24, Gravity.TOP | Gravity.LEFT));

        input = new EditText(context);
        input.setTextSize(17);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setHint(LocaleController.getString(R.string.PengramInputPreviewHint));
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(createBackground());
        addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 0, 28, 0, 0));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!internalChange && count > before && count <= 2) animatePreview();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        update();
    }

    private GradientDrawable createBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), Theme.getColor(Theme.key_divider));
        return d;
    }

    public void update() {
        final int[] names = {R.string.PengramInputAnimNone, R.string.PengramInputAnimFade, R.string.PengramInputAnimPop,
                R.string.PengramInputAnimSlide, R.string.PengramInputAnimRise, R.string.PengramInputAnimBounce, R.string.PengramInputAnimShake};
        caption.setText(LocaleController.formatString(R.string.PengramInputPreviewCurrent,
                LocaleController.getString(names[PengramConfig.getInputAnimation()])));
        resetTransform();
    }

    private void resetTransform() {
        if (animator != null) animator.cancel();
        animator = null;
        input.setAlpha(1f);
        input.setScaleX(1f);
        input.setScaleY(1f);
        input.setTranslationX(0f);
        input.setTranslationY(0f);
    }

    private void animatePreview() {
        final int mode = PengramConfig.getInputAnimation();
        if (mode == PengramConfig.INPUT_ANIM_NONE || SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW) return;
        final long now = SystemClock.uptimeMillis();
        if (now - lastFrame < 32) return;
        lastFrame = now;
        resetTransform();
        final float k = PengramConfig.getInputAnimationIntensity() * .5f;
        final long duration = new long[]{90, 140, 210}[PengramConfig.getInputAnimationSpeed()];
        switch (mode) {
            case PengramConfig.INPUT_ANIM_POP:
                input.setScaleX(1f - .018f * k); input.setScaleY(1f - .018f * k);
                animator = ObjectAnimator.ofPropertyValuesHolder(input, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
                break;
            case PengramConfig.INPUT_ANIM_SLIDE:
                input.setTranslationX(dp(2.5f) * k * (LocaleController.isRTL ? -1 : 1));
                animator = ObjectAnimator.ofFloat(input, View.TRANSLATION_X, 0f); break;
            case PengramConfig.INPUT_ANIM_RISE:
                input.setTranslationY(dp(2f) * k);
                animator = ObjectAnimator.ofFloat(input, View.TRANSLATION_Y, 0f); break;
            case PengramConfig.INPUT_ANIM_BOUNCE:
                input.setScaleX(1f + .012f * k); input.setScaleY(1f + .012f * k);
                animator = ObjectAnimator.ofPropertyValuesHolder(input, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
                animator.setInterpolator(new OvershootInterpolator(.9f)); break;
            case PengramConfig.INPUT_ANIM_SHAKE:
                animator = ObjectAnimator.ofFloat(input, View.TRANSLATION_X, 0, dp(1.2f) * k, -dp(1.2f) * k, 0); break;
            default:
                input.setAlpha(Math.max(.72f, 1f - .12f * k));
                animator = ObjectAnimator.ofFloat(input, View.ALPHA, 1f); break;
        }
        animator.setDuration(duration);
        final Animator running = animator;
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { if (animator == running) animator = null; }
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        resetTransform();
        super.onDetachedFromWindow();
    }
}
