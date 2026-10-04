package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.EditText;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.WeakHashMap;

/** One lightweight frame loop per input; glyphs never create individual animators. */
public final class PengramTypingEffects {
    private static final int MAX_ACTIVE = 40;
    private static final WeakHashMap<EditText, State> states = new WeakHashMap<>();

    private PengramTypingEffects() {}

    public static void apply(EditText edit, Editable text, int start, int before, int count) {
        if (edit == null || text == null || count <= 0 || count <= before || start < 0 || start >= text.length()) return;
        final int mode = PengramConfig.getInputAnimation();
        if (mode == PengramConfig.INPUT_ANIM_NONE) return;
        final int end = Math.min(text.length(), start + count);
        State state = states.get(edit);
        if (state == null) {
            state = new State(edit);
            states.put(edit, state);
        }
        // Composing/autocorrect replaces text in-place. Overlapping spans are invalidated first.
        for (GlyphSpan span : text.getSpans(Math.max(0, start - 1), Math.min(text.length(), end + 1), GlyphSpan.class)) {
            state.remove(span);
        }
        final long now = SystemClock.uptimeMillis();
        final long duration = new long[]{150, 205, 270}[PengramConfig.getInputAnimationSpeed()];
        int offset = start;
        int index = 0;
        while (offset < end && index < 24) {
            int next = Math.min(end, offset + Character.charCount(Character.codePointAt(text, offset)));
            GlyphSpan span = new GlyphSpan(mode, PengramConfig.getInputAnimationIntensity(), now + Math.min(28, index * 4L), duration);
            text.setSpan(span, offset, next, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            state.spans.addLast(span);
            offset = next;
            index++;
        }
        while (state.spans.size() > MAX_ACTIVE) state.remove(state.spans.peekFirst());
        state.schedule();
    }

    public static void clear(EditText edit) {
        State state = states.remove(edit);
        if (state != null) state.clear();
        if (edit != null && edit.getText() != null) {
            for (GlyphSpan span : edit.getText().getSpans(0, edit.length(), GlyphSpan.class)) edit.getText().removeSpan(span);
            edit.invalidate();
        }
    }

    private static final class State implements Runnable {
        final WeakReference<EditText> ref;
        final ArrayDeque<GlyphSpan> spans = new ArrayDeque<>();
        boolean scheduled;
        State(EditText edit) { ref = new WeakReference<>(edit); }
        void schedule() {
            if (scheduled) return;
            EditText edit = ref.get();
            if (edit != null) { scheduled = true; edit.postOnAnimation(this); }
        }
        @Override public void run() {
            scheduled = false;
            EditText edit = ref.get();
            if (edit == null) { spans.clear(); return; }
            long now = SystemClock.uptimeMillis();
            while (!spans.isEmpty() && spans.peekFirst().endTime() <= now) remove(spans.peekFirst());
            edit.invalidate();
            if (!spans.isEmpty()) schedule();
        }
        void remove(GlyphSpan span) {
            if (span == null) return;
            spans.remove(span);
            EditText edit = ref.get();
            if (edit != null && edit.getText() != null) edit.getText().removeSpan(span);
        }
        void clear() {
            EditText edit = ref.get();
            if (edit != null) edit.removeCallbacks(this);
            scheduled = false;
            while (!spans.isEmpty()) remove(spans.peekFirst());
        }
    }

    private static final class GlyphSpan extends ReplacementSpan {
        final int mode;
        final float intensity;
        final long start;
        final long duration;
        GlyphSpan(int mode, int intensity, long start, long duration) {
            this.mode = mode;
            this.intensity = Math.max(.65f, intensity * .5f);
            this.start = start;
            this.duration = duration;
        }
        long endTime() { return start + duration; }
        float progress() {
            float t = (SystemClock.uptimeMillis() - start) / (float) duration;
            t = Math.max(0f, Math.min(1f, t));
            return 1f - (float) Math.pow(1f - t, 3.2);
        }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.max(1, Math.round(paint.measureText(text, start, end)));
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            float p = progress();
            float alpha = Math.min(1f, p * 2.2f), scale = 1f, dx = 0, dy = 0, rotation = 0;
            switch (mode) {
                case PengramConfig.INPUT_ANIM_POP: scale = .82f + .18f * p; break;
                case PengramConfig.INPUT_ANIM_SLIDE: dx = (1f - p) * dp(3.5f) * intensity * (LocaleController.isRTL ? -1 : 1); break;
                case PengramConfig.INPUT_ANIM_RISE: dy = (1f - p) * dp(4) * intensity; break;
                case PengramConfig.INPUT_ANIM_BOUNCE:
                    scale = .82f + .18f * p + (float) Math.sin(p * Math.PI) * .06f * intensity; break;
                case PengramConfig.INPUT_ANIM_SHAKE:
                    rotation = (float) Math.sin(p * Math.PI * 3) * (1f - p) * 2.2f * intensity; break;
            }
            int oldAlpha = paint.getAlpha();
            int save = canvas.save();
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
