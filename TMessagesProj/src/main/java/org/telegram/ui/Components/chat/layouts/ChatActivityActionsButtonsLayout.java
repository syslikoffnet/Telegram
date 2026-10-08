package org.telegram.ui.Components.chat.layouts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundButton;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

@SuppressLint("ViewConstructor")
public class ChatActivityActionsButtonsLayout extends LinearLayout {
    private final Theme.ResourcesProvider resourcesProvider;

    private final ButtonHolder replyButton = new ButtonHolder();
    private final ButtonHolder forwardButton = new ButtonHolder();
    private final ButtonHolder quoteButton = new ButtonHolder();

    public ChatActivityActionsButtonsLayout(@NonNull Context context,
                                            Theme.ResourcesProvider resourcesProvider,
                                            BlurredBackgroundColorProvider colorProvider,
                                            BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableViewFactory) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        replyButton.button = ChatActivityBlurredRoundButton.create(
            context, blurredBackgroundDrawableViewFactory, colorProvider, resourcesProvider
        );
        replyButton.button.setOnClickListener(v -> {});
        ScaleStateListAnimator.apply(replyButton.button, .065f, 2f);

        forwardButton.button = ChatActivityBlurredRoundButton.create(
            context, blurredBackgroundDrawableViewFactory, colorProvider, resourcesProvider
        );
        forwardButton.button.setOnClickListener(v -> {});
        ScaleStateListAnimator.apply(forwardButton.button, .065f, 2f);

        quoteButton.button = ChatActivityBlurredRoundButton.create(
            context, blurredBackgroundDrawableViewFactory, colorProvider, resourcesProvider
        );
        quoteButton.button.setOnClickListener(v -> {});
        ScaleStateListAnimator.apply(quoteButton.button, .065f, 2f);

        addTextView(replyButton, LocaleController.getString(R.string.Reply), R.drawable.input_reply, false);
        addTextView(quoteButton, LocaleController.getString(R.string.PengramQuoteMenu), R.drawable.msg_copy, false);
        addTextView(forwardButton, LocaleController.getString(R.string.Forward), R.drawable.input_forward, true);

        setOrientation(HORIZONTAL);
        setClipChildren(false);

        addView(replyButton.button, LayoutHelper.createLinear(0, 56, 1f, 1, 0, -1, 0));
        addView(quoteButton.button, LayoutHelper.createLinear(0, 56, 1f, 0, 0, 0, 0));
        addView(forwardButton.button, LayoutHelper.createLinear(0, 56, 1f, -1, 0, 1, 0));
    }

    public void setReplyButtonOnClickListener(View.OnClickListener listener) {
        replyButton.button.setOnClickListener(listener);
    }

    public void setQuoteButtonOnClickListener(View.OnClickListener listener) {
        quoteButton.button.setOnClickListener(listener);
    }

    public void showQuoteButton(boolean visible, boolean animated) {
        quoteButton.visibilityAnimator.setValue(visible, animated);
    }

    public void setForwardButtonOnClickListener(View.OnClickListener listener) {
        forwardButton.button.setOnClickListener(listener);
    }

    public View getForwardButton() {
        return forwardButton.button;
    }

    private void addTextView(ButtonHolder button, String text, @DrawableRes int iconRes, boolean iconLeft) {
        TextView forwardButton = new TextView(getContext());
        forwardButton.setText(text);
        forwardButton.setGravity(Gravity.CENTER_VERTICAL);
        forwardButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        forwardButton.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
        forwardButton.setSingleLine(true);
        forwardButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        forwardButton.setCompoundDrawablePadding(AndroidUtilities.dp(4));
        forwardButton.setTextColor(Theme.getColor(Theme.key_glass_defaultText, resourcesProvider));
        forwardButton.setTypeface(AndroidUtilities.bold());
        Drawable image = getContext().getResources().getDrawable(iconRes).mutate();
        image.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        forwardButton.setCompoundDrawablesWithIntrinsicBounds(iconLeft ? image : null, null, iconLeft ? null : image, null);

        button.textView = forwardButton;
        button.button.addView(forwardButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        /*if (getDialogId() == UserObject.VERIFY) {
            forwardButton.setVisibility(View.GONE);
        }*/
    }


    public void showReplyButton(boolean visible, boolean animated) {
        replyButton.visibilityAnimator.setValue(visible, animated);
    }

    public void setReplyButtonEnabled(boolean enabled, boolean animated) {
        replyButton.enabledAnimator.setValue(enabled, animated);
        replyButton.button.setEnabled(enabled);
    }

    public void showForwardButton(boolean visible, boolean animated) {
        forwardButton.visibilityAnimator.setValue(visible, animated);
    }

    public void setForwardButtonEnabled(boolean enabled, boolean animated) {
        forwardButton.enabledAnimator.setValue(enabled, animated);
        forwardButton.button.setEnabled(enabled);
    }






    public void updateColors() {
        replyButton.button.updateColors();
        forwardButton.button.updateColors();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        checkButtonsPositionsAndVisibility();
    }

    private float totalVisibilityFactor;
    public void setTotalVisibilityFactor(float factor) {
        if (totalVisibilityFactor != factor) {
            totalVisibilityFactor = factor;
            checkButtonsPositionsAndVisibility();
        }
    }

    private void checkButtonsPositionsAndVisibility() {
        checkHolderPositionsAndVisibility(forwardButton);
        checkHolderPositionsAndVisibility(quoteButton);
        checkHolderPositionsAndVisibility(replyButton);
    }

    private void checkHolderPositionsAndVisibility(ButtonHolder holder) {
        final float visibility = totalVisibilityFactor * holder.visibilityAnimator.getFloatValue();
        final float offsetY = -dp(54) * (1f - visibility);
        float offsetX = getMeasuredWidth() / 2f * (1f - AnimatorUtils.DECELERATE_INTERPOLATOR.getInterpolation(visibility));
        if (holder == replyButton) {
            offsetX *= -1;
        } else if (holder == quoteButton) {
            offsetX = 0;
        }

        holder.button.setTranslationX(offsetX);
        holder.button.setTranslationY(offsetY);
        holder.button.setAlpha(visibility);
        holder.button.setVisibility(visibility > 0 ? VISIBLE : INVISIBLE);
    }

    private class ButtonHolder implements FactorAnimator.Target {
        public ChatActivityBlurredRoundButton button;
        public TextView textView;

        public BoolAnimator visibilityAnimator = new BoolAnimator(0, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320, true);
        public BoolAnimator enabledAnimator = new BoolAnimator(1, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320, true);

        @Override
        public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
            textView.setAlpha(lerp(0.5f, 1, enabledAnimator.getFloatValue()));
            checkHolderPositionsAndVisibility(this);
        }
    }
}
