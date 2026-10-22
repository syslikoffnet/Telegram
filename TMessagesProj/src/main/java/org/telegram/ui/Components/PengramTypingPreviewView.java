package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Isolated preview using exactly the same per-glyph renderer as the chat composer. */
public class PengramTypingPreviewView extends FrameLayout {
    private final EditText input;
    private final TextView caption;
    private boolean suppress;
    private boolean compositionEdit;

    public PengramTypingPreviewView(Context context) {
        super(context);
        setPadding(dp(16), dp(10), dp(16), dp(12));

        caption = new TextView(context);
        caption.setTextSize(13);
        caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        addView(caption, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        input = new EditTextBoldCursor(context) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                PengramTypingEffects.prepareDraw(this);
                super.onDraw(canvas);
                PengramTypingEffects.drawOverlay(this, canvas);
            }
            @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
                super.onFocusChanged(focused, direction, previous);
                if (focused) PengramTypingEffects.wake(this); else PengramTypingEffects.clear(this);
            }
            @Override public void onWindowFocusChanged(boolean focused) {
                super.onWindowFocusChanged(focused);
                if (focused) PengramTypingEffects.wake(this); else PengramTypingEffects.clear(this);
            }
            @Override protected void onSelectionChanged(int start, int end) {
                super.onSelectionChanged(start, end);
                if (!suppress) PengramTypingEffects.wake(this);
            }
            @android.annotation.TargetApi(26)
            @Override public void autofill(android.view.autofill.AutofillValue value) {
                suppress = true;
                try { super.autofill(value); } finally { suppress = false; }
            }
            @Override public boolean onTextContextMenuItem(int id) {
                suppress = id == android.R.id.paste || id == android.R.id.pasteAsPlainText || id == android.R.id.undo || id == android.R.id.redo;
                try { return super.onTextContextMenuItem(id); } finally { suppress = false; }
            }
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info) {
                android.view.inputmethod.InputConnection original = super.onCreateInputConnection(info);
                if (original == null) return null;
                return new android.view.inputmethod.InputConnectionWrapper(original, false) {
                    @Override public boolean setComposingText(CharSequence text, int position) {
                        compositionEdit = true;
                        try { return super.setComposingText(text, position); }
                        finally { compositionEdit = false; }
                    }
                    @android.annotation.TargetApi(33)
                    @Override public boolean setComposingText(CharSequence text, int position, android.view.inputmethod.TextAttribute attributes) {
                        compositionEdit = true;
                        try { return super.setComposingText(text, position, attributes); }
                        finally { compositionEdit = false; }
                    }
                    @Override public boolean setComposingRegion(int start, int end) {
                        // Почему: обновление composing-region не отменяет уже удалённые глифы.
                        return super.setComposingRegion(start, end);
                    }
                    @android.annotation.TargetApi(33)
                    @Override public boolean setComposingRegion(int start, int end, android.view.inputmethod.TextAttribute attributes) {
                        // Почему: обновление composing-region не отменяет уже удалённые глифы.
                        return super.setComposingRegion(start, end, attributes);
                    }
                    @android.annotation.TargetApi(33)
                    @Override public boolean commitText(CharSequence text, int position, android.view.inputmethod.TextAttribute attributes) {
                        int start = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(getText());
                        boolean result = super.commitText(text, position, attributes);
                        if (result && text != null) PengramTypingEffects.committed(input, start, start + text.length());
                        return result;
                    }
                    @Override public boolean commitText(CharSequence text, int position) {
                        int start = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(getText());
                        boolean result = super.commitText(text, position);
                        if (result && text != null) PengramTypingEffects.committed(input, start, start + text.length());
                        return result;
                    }
                    @Override public boolean finishComposingText() {
                        int start = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(getText());
                        int end = android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(getText());
                        boolean result = super.finishComposingText();
                        if (result) PengramTypingEffects.committed(input, start, end);
                        return result;
                    }
                };
            }
        };
        input.setTextSize(17);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setHint(LocaleController.getString(R.string.PengramInputPreviewHint));
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(createBackground());
        addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 0, 28, 0, 0));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!suppress) {
                    PengramTypingEffects.captureReplacement(input, s, start, count, after);
                    if (count > after) PengramTypingEffects.captureBefore(input, s, start, count);
                } else PengramTypingEffects.clear(input);
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                PengramTypingEffects.apply(input, input.getText(), start, before, count, suppress, compositionEdit);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        PengramTypingEffects.watch(this, this::update);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Фиксированные 90 dp обрезали подпись reduce motion при шрифте 2×.
        final int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        caption.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) input.getLayoutParams();
        params.topMargin = caption.getMeasuredHeight() + dp(6);
        params.height = Math.max(dp(48), input.getPaint().getFontMetricsInt(null) + dp(12));
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(getPaddingTop() + params.topMargin
                + params.height + getPaddingBottom(), MeasureSpec.EXACTLY));
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
        setLayoutDirection(LocaleController.isRTL ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
        caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        PengramTypingEffects.refreshMotionPreference();
        boolean enabled = PengramConfig.getInputAnimation() != PengramConfig.INPUT_ANIM_NONE
                || PengramConfig.isTypingDeleteAnim() || PengramConfig.isTypingCursorSmooth();
        caption.setText(!enabled ? LocaleController.getString(R.string.PengramTypingEffectsOff)
                : !PengramTypingEffects.motionAllowed() ? LocaleController.getString(R.string.PengramTypingReducedMotion)
                : LocaleController.formatString(R.string.PengramInputPreviewCurrent,
                LocaleController.getString(names[PengramConfig.getInputAnimation()])));
        input.setCursorVisible(enabled && PengramTypingEffects.motionAllowed());
        PengramTypingEffects.clear(input);
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
