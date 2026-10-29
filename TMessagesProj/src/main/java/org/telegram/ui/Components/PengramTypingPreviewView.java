package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Isolated preview using exactly the same per-glyph renderer as the chat composer. */
public class PengramTypingPreviewView extends LinearLayout {
    private final EditText input;
    private final TextView caption;

    public PengramTypingPreviewView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(10), dp(16), dp(12));

        caption = new TextView(context);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        caption.setMaxLines(2);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        addView(caption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        input = new EditTextBoldCursor(context) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                final int pengramSave = PengramTypingEffects.preDraw(this, canvas);
                super.onDraw(canvas);
                if (pengramSave >= 0) {
                    canvas.restoreToCount(pengramSave);
                }
                PengramTypingEffects.drawOverlay(this, canvas);
            }
        };
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setHint(LocaleController.getString(R.string.PengramInputPreviewHint));
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(createBackground());
        addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (count > 0) {
                    PengramTypingEffects.captureBefore(input, s, start, count);
                }
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                PengramTypingEffects.apply(input, input.getText(), start, before, count);
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
        caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        caption.setText(LocaleController.formatString(R.string.PengramInputPreviewCurrent,
                LocaleController.getString(names[PengramConfig.getInputAnimation()])));
        PengramTypingEffects.clearGlyphsOnly(input);
        // Re-apply theme colors because this view can survive a live day/night theme switch.
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setBackground(createBackground());
        input.setAlpha(1f);
    }

    @Override protected void onDetachedFromWindow() {
        PengramTypingEffects.clear(input);
        super.onDetachedFromWindow();
    }
}
