package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.WeakHashMap;

/** Lightweight VSync-driven animation of newly inserted Unicode glyphs only. */
public final class PengramTypingEffects {
    private static final int MAX_ACTIVE_GLYPHS = 48;
    private static final int MAX_INSERT_GLYPHS = 32;
    private static final WeakHashMap<EditText, ArrayDeque<GlyphSpan>> active = new WeakHashMap<>();
    private static final PathInterpolator SMOOTH_OUT = new PathInterpolator(.16f, 1f, .30f, 1f);
    private static final PathInterpolator SOFT_OUT = new PathInterpolator(.22f, 1f, .36f, 1f);

    private PengramTypingEffects() {}

    public static void apply(EditText edit, Editable text, int start, int before, int count) {
        if (edit == null || text == null || count <= 0 || count <= before || start < 0 || start >= text.length()) return;
        final int mode = PengramConfig.getInputAnimation();
        if (mode == PengramConfig.INPUT_ANIM_NONE) return;

        final int end = Math.min(text.length(), start + count);
        // IME completion may replace a composing range. Never leave stale/overlapping replacement spans.
        for (GlyphSpan old : text.getSpans(Math.max(0, start - 1), Math.min(text.length(), end + 1), GlyphSpan.class)) {
            old.finish();
        }

        ArrayDeque<GlyphSpan> queue = active.get(edit);
        if (queue == null) active.put(edit, queue = new ArrayDeque<>());
        final long duration = new long[]{170, 225, 300}[PengramConfig.getInputAnimationSpeed()];
        int offset = start;
        int glyphs = 0;
        while (offset < end && glyphs++ < MAX_INSERT_GLYPHS) {
            final int codePoint = Character.codePointAt(text, offset);
            final int glyphEnd = Math.min(end, offset + Character.charCount(codePoint));
            final GlyphSpan span = new GlyphSpan(edit, mode, PengramConfig.getInputAnimationIntensity());
            text.setSpan(span, offset, glyphEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            queue.addLast(span);
            span.start(duration, Math.min(42L, (glyphs - 1L) * 7L));
            offset = glyphEnd;
        }
        while (queue.size() > MAX_ACTIVE_GLYPHS) queue.removeFirst().finish();
    }

    public static void clear(EditText edit) {
        final ArrayDeque<GlyphSpan> queue = active.remove(edit);
        if (queue != null) while (!queue.isEmpty()) queue.removeFirst().finish();
        if (edit != null && edit.getText() != null) {
            for (GlyphSpan span : edit.getText().getSpans(0, edit.length(), GlyphSpan.class)) edit.getText().removeSpan(span);
            edit.invalidate();
        }
    }

    private static final class GlyphSpan extends ReplacementSpan {
        private final WeakReference<EditText> editRef;
        private final int mode;
        private final float intensity;
        private ValueAnimator animator;
        private float progress;
        private boolean removing;

        GlyphSpan(EditText edit, int mode, int intensity) {
            editRef = new WeakReference<>(edit);
            this.mode = mode;
            this.intensity = Math.max(.5f, intensity * .5f);
        }

        void start(long duration, long delay) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.setStartDelay(delay);
            animator.setInterpolator(mode == PengramConfig.INPUT_ANIM_BOUNCE
                    ? new OvershootInterpolator(.65f)
                    : mode == PengramConfig.INPUT_ANIM_SHAKE ? SOFT_OUT : SMOOTH_OUT);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                EditText edit = editRef.get();
                if (edit != null) edit.invalidate(); else a.cancel();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { removeSpan(); }
                @Override public void onAnimationCancel(Animator animation) { removeSpan(); }
            });
            animator.start();
        }

        void finish() {
            ValueAnimator value = animator;
            animator = null;
            if (value != null && value.isStarted()) value.cancel();
            removeSpan();
        }

        private void removeSpan() {
            if (removing) return;
            removing = true;
            final EditText edit = editRef.get();
            if (edit != null && edit.getText() != null) {
                edit.getText().removeSpan(this);
                ArrayDeque<GlyphSpan> queue = active.get(edit);
                if (queue != null) queue.remove(this);
                edit.invalidate();
            }
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.max(1, Math.round(paint.measureText(text, start, end)));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            final float p = Math.max(0f, Math.min(1f, progress));
            float alpha = Math.min(1f, p * 1.8f), scale = 1f, dx = 0f, dy = 0f, rotation = 0f;
            switch (mode) {
                case PengramConfig.INPUT_ANIM_POP: scale = .72f + .28f * p; break;
                case PengramConfig.INPUT_ANIM_SLIDE: dx = (1f - p) * dp(5) * intensity * (LocaleController.isRTL ? -1 : 1); break;
                case PengramConfig.INPUT_ANIM_RISE: dy = (1f - p) * dp(6) * intensity; break;
                case PengramConfig.INPUT_ANIM_BOUNCE: scale = Math.max(.7f, .68f + .32f * progress); break;
                case PengramConfig.INPUT_ANIM_SHAKE: rotation = (float) Math.sin(p * Math.PI * 4) * (1f - p) * 3.5f * intensity; break;
            }
            final int oldAlpha = paint.getAlpha();
            final int save = canvas.save();
            try {
                canvas.translate(x + dx, dy);
                canvas.rotate(rotation, 0, y);
                canvas.scale(scale, scale, 0, y);
                paint.setAlpha(Math.max(0, Math.min(255, Math.round(oldAlpha * alpha))));
                canvas.drawText(text, start, end, 0, y, paint);
            } finally {
                paint.setAlpha(oldAlpha);
                canvas.restoreToCount(save);
            }
        }
    }
}
