package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.SharedConfig;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.WeakHashMap;

/** Transient fixed-width spans that animate only newly typed glyphs, never the whole draft. */
public final class PengramTypingEffects {
    private static final WeakHashMap<EditText, ArrayDeque<GlyphSpan>> active = new WeakHashMap<>();
    private static final WeakHashMap<EditText, Long> lastFrame = new WeakHashMap<>();
    private PengramTypingEffects() {}

    public static void apply(EditText edit, Editable text, int start, int before, int count) {
        if (edit == null || text == null || count <= before || count > 2 || start < 0 || start >= text.length()) return;
        if (SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW) return;
        final int mode = PengramConfig.getInputAnimation();
        if (mode == PengramConfig.INPUT_ANIM_NONE) return;
        final long now = SystemClock.uptimeMillis();
        final Long previous = lastFrame.get(edit);
        if (previous != null && now - previous < 28) return;
        lastFrame.put(edit, now);

        final int end = Math.min(text.length(), start + count);
        if (end <= start || Character.isLowSurrogate(text.charAt(start))) return;
        final GlyphSpan span = new GlyphSpan(edit, mode, PengramConfig.getInputAnimationIntensity());
        text.setSpan(span, start, end, Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ArrayDeque<GlyphSpan> queue = active.get(edit);
        if (queue == null) active.put(edit, queue = new ArrayDeque<>());
        queue.addLast(span);
        while (queue.size() > 10) queue.removeFirst().finish();
        span.start(new long[]{90, 145, 220}[PengramConfig.getInputAnimationSpeed()]);
    }

    public static void clear(EditText edit) {
        final ArrayDeque<GlyphSpan> queue = active.remove(edit);
        if (queue != null) while (!queue.isEmpty()) queue.removeFirst().finish();
        lastFrame.remove(edit);
    }

    private static final class GlyphSpan extends ReplacementSpan {
        private final WeakReference<EditText> editRef;
        private final int mode;
        private final float intensity;
        private ValueAnimator animator;
        private float progress;

        GlyphSpan(EditText edit, int mode, int intensity) {
            editRef = new WeakReference<>(edit);
            this.mode = mode;
            this.intensity = intensity * .5f;
        }

        void start(long duration) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            if (mode == PengramConfig.INPUT_ANIM_BOUNCE) animator.setInterpolator(new OvershootInterpolator(.8f));
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                final EditText edit = editRef.get();
                if (edit != null) edit.invalidate(); else a.cancel();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { finish(); }
                @Override public void onAnimationCancel(android.animation.Animator animation) { removeSpan(); }
            });
            animator.start();
        }

        void finish() {
            if (animator != null) { ValueAnimator a = animator; animator = null; if (a.isRunning()) a.cancel(); }
            removeSpan();
        }

        private void removeSpan() {
            final EditText edit = editRef.get();
            if (edit != null && edit.getText() != null) {
                edit.getText().removeSpan(this);
                edit.invalidate();
                final ArrayDeque<GlyphSpan> queue = active.get(edit);
                if (queue != null) queue.remove(this);
            }
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(paint.measureText(text, start, end));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            final float p = Math.max(0f, Math.min(1f, progress));
            float alpha = p, scale = 1f, dx = 0f, dy = 0f, rotation = 0f;
            switch (mode) {
                case PengramConfig.INPUT_ANIM_POP: scale = .55f + .45f * p; break;
                case PengramConfig.INPUT_ANIM_SLIDE: alpha = p; dx = (1f - p) * dp(7) * intensity * (LocaleController.isRTL ? -1 : 1); break;
                case PengramConfig.INPUT_ANIM_RISE: alpha = p; dy = (1f - p) * dp(8) * intensity; break;
                case PengramConfig.INPUT_ANIM_BOUNCE: alpha = Math.min(1f, p * 2f); scale = .65f + .35f * p; break;
                case PengramConfig.INPUT_ANIM_SHAKE: alpha = Math.min(1f, p * 2f); rotation = (float) Math.sin(p * Math.PI * 4) * (1f - p) * 5f * intensity; break;
                default: break;
            }
            final int oldAlpha = paint.getAlpha();
            canvas.save();
            canvas.translate(x + dx, dy);
            canvas.rotate(rotation, 0, y);
            canvas.scale(scale, scale, 0, y);
            paint.setAlpha(Math.round(oldAlpha * alpha));
            canvas.drawText(text, start, end, 0, y, paint);
            paint.setAlpha(oldAlpha);
            canvas.restore();
        }
    }
}
