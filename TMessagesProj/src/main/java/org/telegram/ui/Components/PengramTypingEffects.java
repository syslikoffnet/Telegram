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

/**
 * Анимация появления только что набранных букв.
 *
 * Почему так, а не «по спану на каждый символ, пока хватает памяти»:
 * ReplacementSpan сам сообщает ширину целым числом пикселей, а обычный текст
 * размечается дробными. Пока спанов много, сумма округлений расходится с реальной
 * шириной строки, и текст заметно «дёргается» по мере того, как спаны гаснут.
 * Поэтому одновременно живёт максимум несколько спанов у самого хвоста строки,
 * ширина измеряется один раз и кэшируется, а гаснут они одной пачкой.
 */
public final class PengramTypingEffects {
    /** столько символов хвоста анимируем одновременно — дальше текст уже стоит на месте */
    private static final int MAX_ACTIVE = 4;
    /** максимум символов из одной вставки (быстрый набор/автозамена) */
    private static final int MAX_PER_BATCH = 3;
    private static final WeakHashMap<EditText, State> states = new WeakHashMap<>();

    private PengramTypingEffects() {}

    public static void apply(EditText edit, Editable text, int start, int before, int count) {
        if (edit == null || text == null || count <= 0 || count <= before || start < 0 || start >= text.length()) {
            return;
        }
        final int mode = PengramConfig.getInputAnimation();
        if (mode == PengramConfig.INPUT_ANIM_NONE) {
            return;
        }
        final int end = Math.min(text.length(), start + count);
        // Большие вставки (буфер обмена, автодополнение) не анимируем: это не набор.
        if (end - start > 12) {
            State existing = states.get(edit);
            if (existing != null) {
                existing.clear();
            }
            return;
        }
        State state = states.get(edit);
        if (state == null) {
            state = new State(edit);
            states.put(edit, state);
        }
        // Автозамена и composing-текст переписывают уже набранное: старые спаны снимаем,
        // иначе они останутся висеть на чужих символах.
        for (GlyphSpan span : text.getSpans(Math.max(0, start - 1), Math.min(text.length(), end + 1), GlyphSpan.class)) {
            state.remove(span);
        }
        final long now = SystemClock.uptimeMillis();
        final long duration = new long[]{140, 190, 250}[PengramConfig.getInputAnimationSpeed()];
        final int intensity = PengramConfig.getInputAnimationIntensity();
        int offset = start;
        int index = 0;
        while (offset < end && index < MAX_PER_BATCH) {
            final int next = Math.min(end, offset + Character.charCount(Character.codePointAt(text, offset)));
            if (hasForeignReplacement(text, offset, next)) {
                // Эмодзи и прочие ReplacementSpan рисуют себя сами — поверх них нельзя.
                offset = next;
                continue;
            }
            final GlyphSpan span = new GlyphSpan(mode, intensity, now + Math.min(24, index * 12L), duration);
            try {
                text.setSpan(span, offset, next, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception e) {
                offset = next;
                continue;
            }
            state.spans.addLast(span);
            offset = next;
            index++;
        }
        while (state.spans.size() > MAX_ACTIVE) {
            state.remove(state.spans.peekFirst());
        }
        state.schedule();
    }

    /** есть ли в этом месте чужой ReplacementSpan (анимированное эмодзи и т.п.) */
    private static boolean hasForeignReplacement(Editable text, int from, int to) {
        final ReplacementSpan[] spans = text.getSpans(from, to, ReplacementSpan.class);
        for (ReplacementSpan span : spans) {
            if (!(span instanceof GlyphSpan)) {
                return true;
            }
        }
        return false;
    }

    public static void clear(EditText edit) {
        final State state = states.remove(edit);
        if (state != null) {
            state.clear();
        }
        if (edit != null && edit.getText() != null) {
            for (GlyphSpan span : edit.getText().getSpans(0, edit.length(), GlyphSpan.class)) {
                edit.getText().removeSpan(span);
            }
            edit.invalidate();
        }
    }

    private static final class State implements Runnable {
        final WeakReference<EditText> ref;
        final ArrayDeque<GlyphSpan> spans = new ArrayDeque<>();
        boolean scheduled;

        State(EditText edit) {
            ref = new WeakReference<>(edit);
        }

        void schedule() {
            if (scheduled) {
                return;
            }
            final EditText edit = ref.get();
            if (edit != null) {
                scheduled = true;
                edit.postOnAnimation(this);
            }
        }

        @Override
        public void run() {
            scheduled = false;
            final EditText edit = ref.get();
            if (edit == null) {
                spans.clear();
                return;
            }
            final long now = SystemClock.uptimeMillis();
            // Снимаем спаны только когда отыграла вся пачка: один пересчёт разметки
            // вместо череды мелких сдвигов текста.
            boolean allFinished = true;
            for (GlyphSpan span : spans) {
                if (span.endTime() > now) {
                    allFinished = false;
                    break;
                }
            }
            if (allFinished) {
                while (!spans.isEmpty()) {
                    remove(spans.peekFirst());
                }
            }
            edit.invalidate();
            if (!spans.isEmpty()) {
                schedule();
            }
        }

        void remove(GlyphSpan span) {
            if (span == null) {
                return;
            }
            spans.remove(span);
            final EditText edit = ref.get();
            if (edit != null && edit.getText() != null) {
                edit.getText().removeSpan(span);
            }
        }

        void clear() {
            final EditText edit = ref.get();
            if (edit != null) {
                edit.removeCallbacks(this);
            }
            scheduled = false;
            while (!spans.isEmpty()) {
                remove(spans.peekFirst());
            }
        }
    }

    private static final class GlyphSpan extends ReplacementSpan {
        final int mode;
        final float intensity;
        final long start;
        final long duration;
        /** ширина меряется один раз: иначе символ «плывёт» между кадрами */
        private int width = -1;

        GlyphSpan(int mode, int intensity, long start, long duration) {
            this.mode = mode;
            this.intensity = Math.max(.6f, intensity * .45f);
            this.start = start;
            this.duration = duration;
        }

        long endTime() {
            return start + duration;
        }

        private float progress() {
            final long now = SystemClock.uptimeMillis();
            if (now <= start) {
                return 0f;
            }
            float t = (now - start) / (float) duration;
            t = Math.max(0f, Math.min(1f, t));
            // мягкое затухание без «перелёта»: приятнее глазу и не трясёт строку
            return 1f - (1f - t) * (1f - t) * (1f - t);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                paint.getFontMetricsInt(fm);
            }
            if (width < 0) {
                width = Math.max(1, Math.round(paint.measureText(text, start, end)));
            }
            return width;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            final float p = progress();
            // буква никогда не появляется совсем прозрачной — это и читалось как «мигание»
            float alpha = .25f + .75f * Math.min(1f, p * 1.6f);
            float scale = 1f, dx = 0, dy = 0, rotation = 0;
            switch (mode) {
                case PengramConfig.INPUT_ANIM_POP:
                    scale = .88f + .12f * p;
                    break;
                case PengramConfig.INPUT_ANIM_SLIDE:
                    dx = (1f - p) * dp(3f) * intensity * (LocaleController.isRTL ? -1 : 1);
                    break;
                case PengramConfig.INPUT_ANIM_RISE:
                    dy = (1f - p) * dp(3.5f) * intensity;
                    break;
                case PengramConfig.INPUT_ANIM_BOUNCE:
                    // одна затухающая волна вместо непрерывной тряски
                    scale = .9f + .1f * p + (float) Math.sin(p * Math.PI) * .05f * intensity;
                    break;
                case PengramConfig.INPUT_ANIM_SHAKE:
                    rotation = (float) Math.sin(p * Math.PI * 2f) * (1f - p) * (1f - p) * 2f * intensity;
                    break;
            }
            final int oldAlpha = paint.getAlpha();
            final int save = canvas.save();
            try {
                canvas.translate(x + dx, dy);
                if (rotation != 0) {
                    canvas.rotate(rotation, width * .5f, y);
                }
                if (scale != 1f) {
                    canvas.scale(scale, scale, width * .5f, y);
                }
                paint.setAlpha(Math.max(0, Math.min(255, Math.round(oldAlpha * alpha))));
                canvas.drawText(text, start, end, 0, y, paint);
            } catch (Exception e) {
                // рисование текста не должно ронять поле ввода ни при каких спанах
            } finally {
                paint.setAlpha(oldAlpha);
                canvas.restoreToCount(save);
            }
        }
    }
}
