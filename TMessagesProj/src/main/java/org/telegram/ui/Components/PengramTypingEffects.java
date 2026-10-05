package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import android.text.style.UpdateAppearance;
import android.view.Gravity;
import android.widget.EditText;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Анимация появления только что набранных букв.
 *
 * Прошлая версия рисовала каждую букву через ReplacementSpan — и именно поэтому
 * текст мигал: ReplacementSpan подменяет отрисовку глифа, сообщает ширину целым
 * числом пикселей (разметка считает дробными), ломает кернинг и лигатуры, а на
 * каждом снятии спана строка пересобиралась и прыгала. Плюс спаны ставились
 * прямо внутри onTextChanged, то есть во время рассылки изменений текста.
 *
 * Теперь разметка не трогается вообще:
 *   • на анимируемый символ вешается невидимый CharacterStyle (alpha = 0).
 *     Он не влияет на метрики, поэтому TextView не пересобирает Layout —
 *     ширина, позиция и перенос строк остаются ровно теми же;
 *   • сам символ дорисовывается поверх в onDraw поля ввода, уже с прозрачностью,
 *     сдвигом, поворотом и масштабом — трансформации не влияют ни на что, кроме
 *     этого одного глифа;
 *   • когда анимация доиграла, скрывающий спан снимается, и буква просто
 *     остаётся на том же месте — прыгать нечему.
 *
 * Дополнительно: клавиатуры с подсказками на каждом нажатии переписывают всё
 * набираемое слово, поэтому анимируются только реально добавленные символы в
 * конце изменения, а не весь пришедший кусок.
 */
public final class PengramTypingEffects {

    /** столько символов хвоста анимируем одновременно */
    private static final int MAX_ACTIVE = 6;
    /** максимум символов из одной вставки (быстрый набор/автозамена) */
    private static final int MAX_PER_BATCH = 3;
    /** всё, что длиннее, — вставка, а не набор */
    private static final int PASTE_THRESHOLD = 12;

    private static final WeakHashMap<EditText, State> states = new WeakHashMap<>();

    private PengramTypingEffects() {}

    // ------------------------------------------------------------------ ввод

    public static void apply(EditText edit, Editable text, int start, int before, int count) {
        if (edit == null || text == null || count <= 0 || count <= before || start < 0 || start >= text.length()) {
            return;
        }
        final int mode = PengramConfig.getInputAnimation();
        if (mode == PengramConfig.INPUT_ANIM_NONE) {
            return;
        }
        final int end = Math.min(text.length(), start + count);
        if (end - start > PASTE_THRESHOLD) {
            final State existing = states.get(edit);
            if (existing != null) {
                existing.clear();
            }
            return;
        }
        // Клавиатуры с подсказками (Gboard и прочие) на каждом нажатии переписывают
        // всё набираемое слово целиком: onTextChanged приходит на весь кусок.
        // Анимировать надо только реально добавленные символы в конце — иначе
        // уже стоящие на месте буквы каждый раз проигрывают анимацию заново,
        // и это читается как мигание всего слова.
        final int added = Math.max(1, count - before);
        int animStart = end;
        for (int i = 0; i < added && animStart > start; ++i) {
            animStart = android.text.TextUtils.getOffsetBefore(text, animStart);
        }
        if (animStart < start) {
            animStart = start;
        }
        State state = states.get(edit);
        if (state == null) {
            state = new State(edit);
            states.put(edit, state);
        }
        // Спаны ставим не внутри рассылки onTextChanged, а сразу после неё:
        // менять разметку, пока TextView ещё обрабатывает изменение, нельзя.
        state.enqueue(animStart, end, mode);
    }

    public static void clear(EditText edit) {
        final State state = states.remove(edit);
        if (state != null) {
            state.clear();
        }
        if (edit != null && edit.getText() != null) {
            for (HideSpan span : edit.getText().getSpans(0, edit.length(), HideSpan.class)) {
                edit.getText().removeSpan(span);
            }
            edit.invalidate();
        }
    }

    // -------------------------------------------------------------- отрисовка

    /** вызывается из onDraw поля ввода сразу после super.onDraw */
    public static void drawOverlay(EditText edit, Canvas canvas) {
        final State state = states.get(edit);
        if (state != null) {
            state.draw(canvas);
        }
    }

    // ----------------------------------------------------------------- состояние

    private static final class State implements Runnable {

        final WeakReference<EditText> ref;
        final ArrayList<Glyph> glyphs = new ArrayList<>();
        final TextPaint paint = new TextPaint();
        boolean scheduled;

        /** отложенная пачка символов из последнего изменения текста */
        int pendingStart = -1, pendingEnd = -1, pendingMode;
        boolean pendingPosted;

        private final Runnable applyRunnable = this::applyPending;

        State(EditText edit) {
            ref = new WeakReference<>(edit);
        }

        void enqueue(int start, int end, int mode) {
            pendingStart = pendingStart < 0 ? start : Math.min(pendingStart, start);
            pendingEnd = Math.max(pendingEnd, end);
            pendingMode = mode;
            final EditText edit = ref.get();
            if (edit != null && !pendingPosted) {
                pendingPosted = true;
                edit.post(applyRunnable);
            }
        }

        void applyPending() {
            pendingPosted = false;
            final int start = pendingStart, end = pendingEnd, mode = pendingMode;
            pendingStart = pendingEnd = -1;
            final EditText edit = ref.get();
            if (edit == null || start < 0) {
                return;
            }
            final Editable text = edit.getText();
            if (text == null || end > text.length()) {
                return;
            }
            final long now = SystemClock.uptimeMillis();
            final long duration = new long[]{150, 210, 280}[
                    Math.max(0, Math.min(2, PengramConfig.getInputAnimationSpeed()))];
            final float intensity = Math.max(.6f, PengramConfig.getInputAnimationIntensity() * .45f);

            // если клавиатура переписала уже анимируемый кусок, старые спаны снимаем —
            // иначе они останутся висеть на чужих символах и спрячут их
            for (int a = glyphs.size() - 1; a >= 0; --a) {
                final Glyph glyph = glyphs.get(a);
                final int gs = text.getSpanStart(glyph.span);
                final int ge = text.getSpanEnd(glyph.span);
                if (gs < 0 || (gs < end && ge > start)) {
                    finish(glyph);
                }
            }

            // берём ровно хвост изменения: последние MAX_PER_BATCH символов
            int from = end;
            final int[] bounds = new int[MAX_PER_BATCH + 1];
            int count = 0;
            while (from > start && count < MAX_PER_BATCH) {
                bounds[count++] = from;
                from = android.text.TextUtils.getOffsetBefore(text, from);
            }
            bounds[count] = Math.max(start, from);

            for (int i = count - 1, index = 0; i >= 0; --i, ++index) {
                final int offset = i + 1 <= count ? bounds[i + 1] : start;
                final int next = bounds[i];
                if (offset < 0 || next <= offset || next > text.length()) {
                    continue;
                }
                // эмодзи и прочие ReplacementSpan рисуют себя сами — их не трогаем
                if (text.getSpans(offset, next, ReplacementSpan.class).length > 0) {
                    continue;
                }
                final HideSpan span = new HideSpan();
                try {
                    text.setSpan(span, offset, next, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    glyphs.add(new Glyph(span, mode, intensity, now + Math.min(30, index * 14L), duration));
                } catch (Exception ignore) {
                }
            }
            while (glyphs.size() > MAX_ACTIVE) {
                finish(glyphs.get(0));
            }
            schedule();
            edit.invalidate();
        }

        void schedule() {
            if (scheduled || glyphs.isEmpty()) {
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
                glyphs.clear();
                return;
            }
            final long now = SystemClock.uptimeMillis();
            for (int a = glyphs.size() - 1; a >= 0; --a) {
                final Glyph glyph = glyphs.get(a);
                if (glyph.endTime() <= now) {
                    // снимаем по одному: скрывающий спан не влияет на метрики,
                    // поэтому буква просто проявляется на своём месте
                    finish(glyph);
                }
            }
            edit.invalidate();
            schedule();
        }

        void finish(Glyph glyph) {
            glyphs.remove(glyph);
            final EditText edit = ref.get();
            if (edit != null && edit.getText() != null) {
                edit.getText().removeSpan(glyph.span);
            }
        }

        void clear() {
            final EditText edit = ref.get();
            if (edit != null) {
                edit.removeCallbacks(this);
                edit.removeCallbacks(applyRunnable);
            }
            scheduled = false;
            pendingPosted = false;
            pendingStart = pendingEnd = -1;
            while (!glyphs.isEmpty()) {
                finish(glyphs.get(0));
            }
        }

        void draw(Canvas canvas) {
            if (glyphs.isEmpty()) {
                return;
            }
            final EditText edit = ref.get();
            if (edit == null) {
                return;
            }
            final Editable text = edit.getText();
            final Layout layout = edit.getLayout();
            if (text == null || layout == null) {
                return;
            }
            // страховка: если кадровый колбэк не дошёл (поле скрыли, окно ушло в фон),
            // буква не должна остаться невидимой навсегда
            final long nowMs = SystemClock.uptimeMillis();
            for (int a = glyphs.size() - 1; a >= 0; --a) {
                if (glyphs.get(a).endTime() + 500 < nowMs) {
                    finish(glyphs.get(a));
                }
            }
            if (glyphs.isEmpty()) {
                return;
            }
            final int paddingLeft = edit.getCompoundPaddingLeft();
            // Главная причина «съехавших» букв: поле ввода в чате выровнено по низу
            // (setGravity(Gravity.BOTTOM)), а превью в настройках — по центру.
            // TextView в таком случае сдвигает всю строку вниз, а мы рисовали
            // анимируемый символ по верхнему краю — он висел отдельно от текста
            // и в конце анимации прыгал на место. Повторяем сдвиг один в один.
            final int paddingTop = edit.getExtendedPaddingTop() + verticalOffset(edit, layout);
            // super.onDraw уже снял свои трансформации, поэтому прокрутку учитываем сами
            final int scrollX = edit.getScrollX();
            final int scrollY = edit.getScrollY();

            final int clip = canvas.save();
            // по горизонтали держим букву внутри поля, по вертикали не режем:
            // «подъём» и «подпрыгивание» выносят глиф выше строки
            canvas.clipRect(edit.getCompoundPaddingLeft() - dp(2), 0,
                    edit.getWidth() - edit.getCompoundPaddingRight() + dp(2), edit.getHeight());

            for (int a = 0; a < glyphs.size(); ++a) {
                final Glyph glyph = glyphs.get(a);
                final int from = text.getSpanStart(glyph.span);
                final int to = text.getSpanEnd(glyph.span);
                if (from < 0 || to <= from || to > text.length()) {
                    continue;
                }
                final int line;
                try {
                    line = layout.getLineForOffset(from);
                } catch (Exception e) {
                    continue;
                }
                // рисуем ровно тем же пером, что и сам текст: жирный, курсив, цвет,
                // размер — всё, что навешано спанами, кроме нашего «скрывателя»
                paint.set(edit.getPaint());
                try {
                    for (CharacterStyle style : text.getSpans(from, to, CharacterStyle.class)) {
                        if (!(style instanceof HideSpan)) {
                            style.updateDrawState(paint);
                        }
                    }
                } catch (Exception ignore) {
                }
                final int baseAlpha = paint.getAlpha();
                final float x = layout.getPrimaryHorizontal(from) + paddingLeft - scrollX;
                final float baseline = layout.getLineBaseline(line) + paddingTop - scrollY;
                final float width = paint.measureText(text, from, to);

                final float p = glyph.progress();
                float alpha = Math.min(1f, p * 1.25f);
                float scale = 1f, dx = 0, dy = 0, rotation = 0;
                switch (glyph.mode) {
                    case PengramConfig.INPUT_ANIM_POP:
                        scale = .6f + .4f * p;
                        break;
                    case PengramConfig.INPUT_ANIM_SLIDE:
                        dx = (1f - p) * dp(7f) * glyph.intensity * (LocaleController.isRTL ? -1 : 1);
                        break;
                    case PengramConfig.INPUT_ANIM_RISE:
                        dy = (1f - p) * dp(8f) * glyph.intensity;
                        break;
                    case PengramConfig.INPUT_ANIM_BOUNCE:
                        scale = .7f + .3f * p + (float) Math.sin(p * Math.PI) * .18f * glyph.intensity;
                        break;
                    case PengramConfig.INPUT_ANIM_SHAKE:
                        rotation = (float) Math.sin(p * Math.PI * 3f) * (1f - p) * (1f - p) * 12f * glyph.intensity;
                        break;
                }

                final int save = canvas.save();
                try {
                    canvas.translate(dx, dy);
                    if (rotation != 0) {
                        canvas.rotate(rotation, x + width * .5f, baseline);
                    }
                    if (scale != 1f) {
                        canvas.scale(scale, scale, x + width * .5f, baseline);
                    }
                    paint.setAlpha(Math.max(0, Math.min(255, Math.round(baseAlpha * alpha))));
                    canvas.drawText(text, from, to, x, baseline, paint);
                } catch (Exception ignore) {
                } finally {
                    paint.setAlpha(baseAlpha);
                    canvas.restoreToCount(save);
                }
            }
            canvas.restoreToCount(clip);
        }

        /**
         * То же самое, что приватный TextView.getVerticalOffset(): если текст ниже
         * поля, он прижимается по гравитации, и все координаты строки съезжают.
         */
        private int verticalOffset(EditText edit, Layout layout) {
            final int gravity = edit.getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
            if (gravity == Gravity.TOP) {
                return 0;
            }
            final int boxHeight = edit.getHeight() - edit.getExtendedPaddingTop() - edit.getExtendedPaddingBottom();
            final int textHeight = layout.getHeight();
            if (textHeight >= boxHeight) {
                return 0;
            }
            return gravity == Gravity.BOTTOM ? boxHeight - textHeight : (boxHeight - textHeight) >> 1;
        }
    }

    private static final class Glyph {
        final HideSpan span;
        final int mode;
        final float intensity;
        final long start;
        final long duration;

        Glyph(HideSpan span, int mode, float intensity, long start, long duration) {
            this.span = span;
            this.mode = mode;
            this.intensity = intensity;
            this.start = start;
            this.duration = duration;
        }

        long endTime() {
            return start + duration;
        }

        float progress() {
            final long now = SystemClock.uptimeMillis();
            if (now <= start) {
                return 0f;
            }
            float t = (now - start) / (float) duration;
            t = Math.max(0f, Math.min(1f, t));
            // мягкое торможение в конце, без перелёта — строка не дрожит
            return 1f - (1f - t) * (1f - t) * (1f - t);
        }
    }

    /**
     * Прячет оригинальный глиф, не трогая метрики: TextView перерисует строку,
     * но не будет пересчитывать Layout — ширина и переносы остаются прежними.
     */
    private static final class HideSpan extends CharacterStyle implements UpdateAppearance {
        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setAlpha(0);
        }
    }
}
