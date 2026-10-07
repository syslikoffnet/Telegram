package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.MaskFilter;
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
import org.telegram.ui.ActionBar.Theme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Анимации ввода текста: появление букв, рассыпание удалённых, плавный курсор.
 *
 * Три независимых слоя, которые включаются/настраиваются по отдельности:
 *
 * 1. Появление. Набранные символы прячутся невидимым CharacterStyle
 *    (alpha = 0, метрики не трogaется) и дорисовываются поверх в onDraw
 *    с прозрачностью, сдвигом, поворотом, масштабом и размытием.
 *    Когда анимация доиграла, скрывающий спан снимается — буква просто
 *    остаётся на своём месте, разметка никогда не пересобирается.
 *
 * 2. Удаление. Перед стиранием (beforeTextChanged) запоминается, какие
 *    символы и где лежали — это единственный момент, когда Layout ещё
 *    описывает старый текст. При самом удалении из этих координат
 *    разлетаются частицы: пыль, искры, снежинки, лепестки или сами буквы.
 *
 * 3. Курсор. Системную каретку умеет глушить наследник EditTextBoldCursor
 *    (setAllowDrawCursor), а мы рисуем свою: она не телепортируется на
 *    новое место, а скользит, пульсирует на бэкспейсе, «присаживается»
 *    на переносе строки и приподскакивает после пробела. По желанию
 *    тянется во время движения (жидкость) и обрамляет края выделения.
 */
public final class PengramTypingEffects {

    /** столько символов хвоста анимируем одновременно */
    private static final int MAX_ACTIVE = 6;
    /** максимум символов из одной вставки (быстрый набор/автозамена) */
    private static final int MAX_PER_BATCH = 3;
    /** всё, что длиннее, — вставка, а не набор */
    private static final int PASTE_THRESHOLD = 12;

    /** максимум стёртых символов, которые рассыпаем за один раз */
    private static final int MAX_DELETE_CHARS = 24;
    /** частицы полураспадаются за это время */
    private static final int PARTICLE_LIFE = 620;

    private static final int CURSOR_EVENT_NONE = 0;
    private static final int CURSOR_EVENT_PULSE = 1;
    private static final int CURSOR_EVENT_DIVE = 2;
    private static final int CURSOR_EVENT_JUMP = 3;

    private static final WeakHashMap<EditText, State> states = new WeakHashMap<>();

    private PengramTypingEffects() {}

    // ------------------------------------------------------------------ ввод

    public static void apply(EditText edit, Editable text, int start, int before, int count) {
        if (edit == null || text == null) {
            return;
        }
        final int mode = PengramConfig.getInputAnimation();
        final boolean deleteAnimations = PengramConfig.getInputAnimation() != PengramConfig.INPUT_ANIM_NONE
                && PengramConfig.isTypingDeleteAnim();
        // Любая правка сдвигает спаны в Editable. Убираем старые ДО следующего
        // кадра: иначе на длинных сообщениях спрятанная буква окажется на другом
        // символе и появится только после завершения таймера.
        State previous = states.get(edit);
        if (previous != null && (before > 0 || count > PASTE_THRESHOLD)) {
            previous.clearGlyphs();
        }
        // удаление: символов больше не стало — разлетаются частицы из снапшота
        if (before > count && deleteAnimations) {
            State s = states.get(edit);
            if (s == null) {
                s = new State(edit);
                states.put(edit, s);
            }
            s.spawnFromSnapshot(start, before);
            s.noteCursorEvent(CURSOR_EVENT_PULSE);
            if (mode == PengramConfig.INPUT_ANIM_NONE || count <= 0 || start < 0 || start >= text.length()) {
                return;
            }
        }
        if (mode == PengramConfig.INPUT_ANIM_NONE || count <= 0 || count <= before || start < 0 || start >= text.length()) {
            return;
        }
        final int end = Math.min(text.length(), start + count);
        if (end - start > PASTE_THRESHOLD) {
            final State existing = states.get(edit);
            if (existing != null) {
                existing.clearAll();
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
        // курсор: пробел — лёгкий подскок, перенос строки — плавное «приземление»
        final char last = end > 0 ? text.charAt(end - 1) : 0;
        if (last == ' ' || last == 0xA0) {
            state.noteCursorEvent(CURSOR_EVENT_JUMP);
        } else if (last == '\n') {
            state.noteCursorEvent(CURSOR_EVENT_DIVE);
        } else {
            state.noteTyping();
        }
        // Спаны ставим не внутри рассылки onTextChanged, а сразу после неё:
        // менять разметку, пока TextView ещё обрабатывает изменение, нельзя.
        state.enqueue(animStart, end, mode);
    }

    /**
     * Вызывается из beforeTextChanged ДО изменения текста: запоминаем уходящие
     * символы и их экранные координаты — позже Layout их уже не знает.
     */
    public static void captureBefore(EditText edit, CharSequence text, int start, int count) {
        if (edit == null || text == null || count <= 0 || count > MAX_DELETE_CHARS) {
            return;
        }
        if (PengramConfig.getInputAnimation() == PengramConfig.INPUT_ANIM_NONE
                || !PengramConfig.isTypingDeleteAnim()) {
            return;
        }
        State state = states.get(edit);
        if (state == null) {
            state = new State(edit);
            states.put(edit, state);
        }
        state.snapshot(text, start, count);
    }

    public static void clear(EditText edit) {
        final State state = states.remove(edit);
        if (state != null) {
            state.clearAll();
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
        if (state == null) {
            syncNativeCursor(edit, false);
            return;
        }
        state.draw(canvas);
    }

    /**
     * Системную каретку прячем, только пока активен наш плавный курсор,
     * и только у полей, которые это умеют (EditTextBoldCursor). Остальным
     * полям (простое превью в настройках) оставляем стандартную — иначе
     * курсор пропадёт совсем.
     */
    private static final WeakHashMap<EditTextBoldCursor, Boolean> nativeCursorAllow = new WeakHashMap<>();

    private static void syncNativeCursor(EditText edit, boolean oursActive) {
        if (edit instanceof EditTextBoldCursor) {
            final EditTextBoldCursor bold = (EditTextBoldCursor) edit;
            final boolean allow = !oursActive;
            final Boolean previous = nativeCursorAllow.get(bold);
            if (previous == null || previous != allow) {
                nativeCursorAllow.put(bold, allow);
                bold.setAllowDrawCursor(allow);
            }
        }
    }

    // ----------------------------------------------------------------- состояние

    private static final class State implements Runnable {

        final WeakReference<EditText> ref;
        final ArrayList<Glyph> glyphs = new ArrayList<>();
        final ArrayList<Particle> particles = new ArrayList<>();
        final TextPaint paint = new TextPaint();
        final android.graphics.Paint shapePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Paint cursorPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final HashMap<Integer, MaskFilter> blurCache = new HashMap<>();
        final Random random = new Random();
        boolean scheduled;

        /** отложенная пачка символов из последнего изменения текста */
        int pendingStart = -1, pendingEnd = -1, pendingMode;
        String pendingText;
        boolean pendingPosted;

        /** снапшот стираемых символов из beforeTextChanged */
        char[] removedChars;
        float[] removedX, removedBaseline;
        int removedCount, removedStart = -1;

        // ---------------------------------------------------------- курсор
        float cursorX = -1, cursorY;
        long lastCursorMove;                 // последнее изменение позиции/текста
        long forceVisibleUntil;              // не мигаем сразу после набора
        int cursorEvent = CURSOR_EVENT_NONE;
        long cursorEventTime;
        float cursorBounce = 0;              // подскок после пробела
        int lastSelStart = -1, lastSelEnd = -1;
        long idleFrom = SystemClock.uptimeMillis();

        private final Runnable applyRunnable = this::applyPending;

        State(EditText edit) {
            ref = new WeakReference<>(edit);
            shapePaint.setStyle(android.graphics.Paint.Style.FILL);
            shapePaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            cursorPaint.setStyle(android.graphics.Paint.Style.FILL);
        }

        // ---------------------------------------------------------- еnqueue ввод

        void enqueue(int start, int end, int mode) {
            // Latest edit wins: a pending offset from an earlier layout must
            // never hide unrelated letters after an IME rewrites the line.
            pendingStart = start;
            pendingEnd = end;
            pendingMode = mode;
            final EditText edit = ref.get();
            final Editable value = edit == null ? null : edit.getText();
            pendingText = value != null && start >= 0 && end <= value.length()
                    ? value.subSequence(start, end).toString() : null;
            if (edit != null && !pendingPosted) {
                pendingPosted = true;
                edit.post(applyRunnable);
            }
        }

        void noteTyping() {
            lastCursorMove = SystemClock.uptimeMillis();
            forceVisibleUntil = lastCursorMove + 1400;
        }

        void noteCursorEvent(int event) {
            noteTyping();
            if (event == CURSOR_EVENT_PULSE || cursorEvent == CURSOR_EVENT_NONE) {
                cursorEvent = event;
                cursorEventTime = lastCursorMove;
            }
            if (event == CURSOR_EVENT_JUMP) {
                cursorBounce = dp(6);
            }
        }

        void applyPending() {
            pendingPosted = false;
            final int start = pendingStart, end = pendingEnd, mode = pendingMode;
            final String expected = pendingText;
            pendingStart = pendingEnd = -1;
            pendingText = null;
            final EditText edit = ref.get();
            if (edit == null || start < 0) {
                return;
            }
            final Editable text = edit.getText();
            if (text == null || end > text.length() || !edit.isAttachedToWindow()
                    || expected == null || !android.text.TextUtils.equals(expected, text.subSequence(start, end))) {
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

            final Layout layout = edit.getLayout();
            final int lastLine = layout != null ? layout.getLineCount() - 1 : -1;
            final boolean allLines = PengramConfig.isTypingAnimateAllLines();
            final boolean ignoreSpaces = PengramConfig.isTypingIgnoreSpaces();

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
                if (ignoreSpaces && text.charAt(offset) == ' ') {
                    continue;
                }
                if (!allLines && layout != null && lastLine >= 0) {
                    // без настройки «каждая строка» анимируем только хвост последней —
                    // перенесённый наверх кусок не должен мигать повторно
                    final int line;
                    try {
                        line = layout.getLineForOffset(offset);
                    } catch (Exception e) {
                        continue;
                    }
                    if (line != lastLine && line != layout.getLineForOffset(end)) {
                        continue;
                    }
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

        // ---------------------------------------------------------- снапшот удаления

        void snapshot(CharSequence text, int start, int count) {
            final EditText edit = ref.get();
            removedCount = 0;
            removedStart = -1;
            if (edit == null) {
                return;
            }
            final Layout layout = edit.getLayout();
            if (layout == null || count > MAX_DELETE_CHARS) {
                return;
            }
            try {
                if (removedChars == null || removedChars.length < count) {
                    removedChars = new char[MAX_DELETE_CHARS];
                    removedX = new float[MAX_DELETE_CHARS];
                    removedBaseline = new float[MAX_DELETE_CHARS];
                }
                final int paddingLeft = edit.getCompoundPaddingLeft();
                final int paddingTop = edit.getExtendedPaddingTop() + verticalOffset(edit, layout);
                final int scrollX = edit.getScrollX();
                final int scrollY = edit.getScrollY();
                int n = 0;
                for (int i = 0; i < count; i++) {
                    final int offset = start + i;
                    if (offset >= text.length()) {
                        break;
                    }
                    final int line = layout.getLineForOffset(offset);
                    removedChars[n] = text.charAt(offset);
                    removedX[n] = layout.getPrimaryHorizontal(offset) + paddingLeft - scrollX;
                    removedBaseline[n] = layout.getLineBaseline(line) + paddingTop - scrollY;
                    n++;
                }
                removedCount = n;
                removedStart = start;
            } catch (Exception e) {
                removedCount = 0;
            }
        }

        /**
         * Из снапшота строим частицы. Сбрасываем его в любом случае:
         * висячий снапшот от старого удаления не должен сработать позднее.
         */
        void spawnFromSnapshot(int start, int before) {
            final char[] chars = removedChars;
            final float[] xs = removedX, baselines = removedBaseline;
            final int n = removedCount;
            final boolean match = removedStart == start && n > 0;
            removedCount = 0;
            removedStart = -1;
            if (!match || chars == null) {
                return;
            }
            final int style = PengramConfig.getTypingDeleteStyle();
            final int perChar = Math.max(1, PengramConfig.getTypingDeleteCount());
            final float speed = PengramConfig.getTypingDeleteSpeed() / 50f;
            final float spread = PengramConfig.getTypingDeleteSpread() / 50f;
            final float size = PengramConfig.getTypingDeleteSize() / 50f;
            final long now = SystemClock.uptimeMillis();
            final int total = Math.min(n, 12);
            for (int i = 0; i < total && particles.size() < 160; i++) {
                final int pieces = style == PengramConfig.DELETE_PARTICLE_LETTERS ? 1 : perChar;
                for (int p = 0; p < pieces && particles.size() < 160; p++) {
                    final Particle particle = new Particle();
                    particle.style = style;
                    particle.ch = chars[i];
                    particle.x = xs[i] + (random.nextFloat() - .5f) * dp(2);
                    particle.y = baselines[i] + (random.nextFloat() - .5f) * dp(4);
                    final double angle = style == PengramConfig.DELETE_PARTICLE_PETALS
                            ? Math.PI * .35 + random.nextDouble() * Math.PI * .3   // лепестки летят вниз-вбок
                            : random.nextDouble() * Math.PI * 2;
                    final float boost = (.5f + random.nextFloat()) * Math.max(.15f, speed) * (0.55f + spread * .45f);
                    final float velocity = dp(3) * boost * (style == PengramConfig.DELETE_PARTICLE_SPARKS ? 2.1f : 1f);
                    particle.vx = (float) Math.cos(angle) * velocity;
                    particle.vy = (float) Math.sin(angle) * velocity - dp(1f);
                    particle.gravity = style == PengramConfig.DELETE_PARTICLE_PETALS ? dp(.06f)
                            : style == PengramConfig.DELETE_PARTICLE_SNOW ? dp(.02f) : dp(.12f);
                    particle.size = dp(1.2f) * size * (.7f + random.nextFloat() * .6f);
                    particle.rotation = random.nextFloat() * 360f;
                    particle.rotationSpeed = (random.nextFloat() - .5f) *
                            (style == PengramConfig.DELETE_PARTICLE_SNOW ? 240f : 560f);
                    particle.start = now + Math.min(40, random.nextInt(40));
                    particle.duration = PARTICLE_LIFE + random.nextInt(200);
                    particle.seed = random.nextFloat() * 1000f;
                    particles.add(particle);
                }
            }
            idleFrom = 0;
            schedule();
            final EditText edit = ref.get();
            if (edit != null) {
                edit.invalidate();
            }
        }

        // ---------------------------------------------------------- кадры

        void schedule() {
            scheduleAfter(0);
        }

        void scheduleAfter(long delayMs) {
            if (scheduled) {
                return;
            }
            final EditText edit = ref.get();
            if (edit == null) {
                return;
            }
            scheduled = true;
            if (delayMs <= 0) {
                edit.postOnAnimation(this);
            } else {
                edit.postOnAnimationDelayed(this, delayMs);
            }
        }

        boolean cursorActive() {
            final EditText edit = ref.get();
            return edit != null
                    && PengramConfig.getInputAnimation() != PengramConfig.INPUT_ANIM_NONE
                    && PengramConfig.isTypingCursorSmooth()
                    && edit.isFocused() && edit.isShown();
        }

        @Override
        public void run() {
            scheduled = false;
            final EditText edit = ref.get();
            if (edit == null) {
                glyphs.clear();
                particles.clear();
                return;
            }
            final long now = SystemClock.uptimeMillis();
            for (int a = glyphs.size() - 1; a >= 0; --a) {
                final Glyph glyph = glyphs.get(a);
                if (glyph.endTime() <= now) {
                    finish(glyph);
                }
            }
            for (int a = particles.size() - 1; a >= 0; --a) {
                if (particles.get(a).end() <= now) {
                    particles.remove(a);
                }
            }
            advanceCursor(now);
            if (!glyphs.isEmpty() || !particles.isEmpty()) {
                edit.invalidate();
                schedule();
                return;
            }
            if (cursorActive()) {
                // Пока курсор скользит/пульсирует — полный FPS не нужен.
                // У движущегося курсора кадры плавные, а просто мигающий
                // достаточно дёргать на границах своего 500-мс такта.
                final boolean moving = Math.abs(targetX - cursorX) > .4f
                        || Math.abs(targetY - cursorY) > .4f
                        || cursorBounce != 0
                        || cursorEvent != CURSOR_EVENT_NONE;
                if (moving) {
                    edit.invalidate();
                    schedule();
                } else {
                    edit.invalidate();
                    scheduleAfter(500 - now % 500);
                }
                return;
            }
            idleFrom = now;
            final Editable text = edit.getText();
            if (text != null) {
                for (HideSpan span : text.getSpans(0, text.length(), HideSpan.class)) {
                    text.removeSpan(span);
                }
            }
        }

        void finish(Glyph glyph) {
            glyphs.remove(glyph);
            final EditText edit = ref.get();
            if (edit != null && edit.getText() != null) {
                edit.getText().removeSpan(glyph.span);
            }
        }

        void clearAll() {
            final EditText edit = ref.get();
            if (edit != null) {
                edit.removeCallbacks(this);
                edit.removeCallbacks(applyRunnable);
            }
            scheduled = false;
            pendingPosted = false;
            pendingStart = pendingEnd = -1;
            pendingText = null;
            particles.clear();
            removedCount = 0;
            removedStart = -1;
            clearGlyphs();
        }

        void clearGlyphs() {
            final EditText edit = ref.get();
            if (edit != null) {
                edit.removeCallbacks(applyRunnable);
            }
            pendingPosted = false;
            pendingStart = pendingEnd = -1;
            pendingText = null;
            while (!glyphs.isEmpty()) {
                finish(glyphs.get(0));
            }
        }

        // ---------------------------------------------------------- курсор: логика

        /** текущие координаты системной позиции курсора (или -1, если рисовать нечего) */
        private float targetX, targetY, targetTop, targetBottom;
        private boolean targetValid;

        void computeTarget(EditText edit) {
            targetValid = false;
            final Layout layout = edit.getLayout();
            if (layout == null || layout.getLineCount() == 0) {
                return;
            }
            final int selStart = edit.getSelectionStart();
            final int selEnd = edit.getSelectionEnd();
            if (selStart < 0 || selEnd < 0 || !edit.isCursorVisible()) {
                return;
            }
            final int line = layout.getLineForOffset(selStart);
            if (line < 0 || line >= layout.getLineCount()) {
                return;
            }
            final int paddingTop = edit.getExtendedPaddingTop() + verticalOffset(edit, layout);
            targetX = layout.getPrimaryHorizontal(selStart)
                    + edit.getCompoundPaddingLeft() - edit.getScrollX();
            targetY = layout.getLineBaseline(line) + paddingTop - edit.getScrollY();
            targetTop = layout.getLineTop(line) + paddingTop - edit.getScrollY();
            targetBottom = layout.getLineBottom(line) + paddingTop - edit.getScrollY();
            targetValid = true;
        }

        void advanceCursor(long now) {
            if (!cursorActive()) {
                return;
            }
            final EditText edit = ref.get();
            if (edit == null) {
                return;
            }
            computeTarget(edit);
            if (!targetValid) {
                return;
            }
            final int selStart = edit.getSelectionStart();
            final int selEnd = edit.getSelectionEnd();
            if (selStart != lastSelStart || selEnd != lastSelEnd) {
                lastSelStart = selStart;
                lastSelEnd = selEnd;
                lastCursorMove = now;
                forceVisibleUntil = now + 1400;
            }

            final float speed = PengramConfig.getTypingCursorSpeed() / 100f;   // 0.25 по умолчанию
            if (cursorX < 0) {
                cursorX = targetX;
                cursorY = targetY;
            }
            float ease = Math.max(.08f, Math.min(.9f, speed));
            float dx = targetX - cursorX;
            float dy = targetY - cursorY;
            if (now - cursorEventTime < 260 && cursorEvent == CURSOR_EVENT_DIVE) {
                ease *= .55f;   // «присаживаемся» на переносе строки
            }
            // При переходе на другую строку (или при скролле длинного текста)
            // не протаскиваем каретку через соседние строки.
            if (Math.abs(dy) > dp(28) || Math.abs(dx) > dp(120)) {
                cursorX = targetX;
                cursorY = targetY;
            } else {
                cursorX += dx * ease;
                cursorY += dy * ease;
            }
            if (Math.abs(dx) < .4f) {
                cursorX = targetX;
            }
            if (Math.abs(dy) < .4f) {
                cursorY = targetY;
            }
            if (cursorBounce != 0) {
                cursorBounce *= .82f;
                if (Math.abs(cursorBounce) < .5f) {
                    cursorBounce = 0;
                }
            }
            if (now - cursorEventTime > 320) {
                cursorEvent = CURSOR_EVENT_NONE;
            }
        }

        // ---------------------------------------------------------- отрисовка

        void draw(Canvas canvas) {
            final EditText edit = ref.get();
            if (edit == null) {
                return;
            }
            final Editable text = edit.getText();
            final Layout layout = edit.getLayout();
            if (text == null) {
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
            for (int a = particles.size() - 1; a >= 0; --a) {
                if (particles.get(a).end() + 300 < nowMs) {
                    particles.remove(a);
                }
            }

            final int paddingLeft = edit.getCompoundPaddingLeft();
            // Главная причина «съехавших» букв: поле ввода в чате выровнено по низу
            // (setGravity(Gravity.BOTTOM)), а превью в настройках — по центру.
            // TextView в таком случае сдвигает всю строку вниз, а мы рисовали
            // анимируемый символ по верхнему краю — он висел отдельно от текста
            // и в конце анимации прыгал на место. Повторяем сдвиг один в один.
            final int paddingTop = layout != null ? edit.getExtendedPaddingTop() + verticalOffset(edit, layout)
                    : edit.getExtendedPaddingTop();
            final int scrollX = edit.getScrollX();
            final int scrollY = edit.getScrollY();

            final int clip = canvas.save();
            canvas.clipRect(edit.getCompoundPaddingLeft() - dp(2), 0,
                    edit.getWidth() - edit.getCompoundPaddingRight() + dp(2), edit.getHeight());

            if (layout != null) {
                drawGlyphs(canvas, edit, text, layout, paddingLeft, paddingTop, scrollX, scrollY);
            } else {
                clearGlyphs(); // don't leave invisible spans when a long edit rebuilds Layout
            }
            drawParticles(canvas, edit);
            final boolean cursorDrawn = drawCursor(canvas, edit, nowMs);
            syncNativeCursor(edit, cursorDrawn);

            canvas.restoreToCount(clip);
            // цикл кадров запускает run() сам; отсюда лишь будим его, если вдруг стоит
            if (cursorActive() || !glyphs.isEmpty() || !particles.isEmpty()) {
                schedule();
            }
        }

        private void drawGlyphs(Canvas canvas, EditText edit, Editable text, Layout layout,
                                int paddingLeft, int paddingTop, int scrollX, int scrollY) {
            if (glyphs.isEmpty()) {
                return;
            }
            final boolean blurOn = PengramConfig.isTypingBlur();
            final float blurMax = PengramConfig.getTypingBlurRadius();
            for (int a = glyphs.size() - 1; a >= 0; --a) {
                final Glyph glyph = glyphs.get(a);
                final int from = text.getSpanStart(glyph.span);
                final int to = text.getSpanEnd(glyph.span);
                if (from < 0 || to <= from || to > text.length()) {
                    finish(glyph);
                    continue;
                }
                final int line;
                try {
                    line = layout.getLineForOffset(from);
                } catch (Exception e) {
                    finish(glyph);
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
                if (baseAlpha == 0) {
                    // Тема или TextView оставили Paint прозрачным: нельзя скрывать
                    // оригинал спаном, если его оверлей не будет нарисован.
                    finish(glyph);
                    continue;
                }
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
                    if (ScaleIsOff(scale)) {
                        canvas.scale(scale, scale, x + width * .5f, baseline);
                    }
                    MaskFilter previousBlur = null;
                    if (blurOn && p < 1f) {
                        // символ проступает из расфокуса: сила размытия угасает
                        // по мере проявления, резкость доезжает на последних 20%
                        final float radius = blurMax * (1f - p) * .8f;
                        if (radius > .6f) {
                            final int bucket = Math.max(1, Math.round(radius));
                            MaskFilter filter = blurCache.get(bucket);
                            if (filter == null) {
                                filter = new BlurMaskFilter(bucket, BlurMaskFilter.Blur.NORMAL);
                                blurCache.put(bucket, filter);
                            }
                            paint.setMaskFilter(filter);
                            alpha = Math.min(alpha, Math.max(.12f, p * 1.05f));
                            previousBlur = filter;
                        }
                    }
                    paint.setAlpha(Math.max(0, Math.min(255, Math.round(baseAlpha * alpha))));
                    canvas.drawText(text, from, to, x, baseline, paint);
                    if (previousBlur != null) {
                        paint.setMaskFilter(null);
                    }
                } catch (Exception ignore) {
                    try {
                        paint.setMaskFilter(null);
                    } catch (Exception ignored) {
                    }
                } finally {
                    paint.setAlpha(baseAlpha);
                    canvas.restoreToCount(save);
                }
            }
        }

        private boolean ScaleIsOff(float scale) {
            return Math.abs(scale - 1f) > .01f;
        }

        private void drawParticles(Canvas canvas, EditText edit) {
            if (particles.isEmpty()) {
                return;
            }
            final int accent = Theme.getColor(Theme.key_chat_messagePanelCursor);
            final long now = SystemClock.uptimeMillis();
            for (int a = 0; a < particles.size(); ++a) {
                final Particle p = particles.get(a);
                final float t = p.progress(now);
                if (t <= 0f || t >= 1f) {
                    continue;
                }
                final float eased = 1f - (1f - t) * (1f - t) * (1f - t);
                final float seconds = (now - p.start) / 1000f;
                final float px = p.x + p.vx * seconds * 8f;
                final float py = p.y + p.vy * seconds * 8f + p.gravity * seconds * seconds * 12f;
                final float alpha = 1f - eased;
                final float rotation = p.rotation + p.rotationSpeed * seconds;

                switch (p.style) {
                    case PengramConfig.DELETE_PARTICLE_SPARKS: {
                        shapePaint.setColor(accent);
                        shapePaint.setAlpha(Math.round(210 * alpha));
                        shapePaint.setStrokeWidth(Math.max(dp(.8f), p.size * .34f));
                        final float len = p.size * 2.6f * (1f + t);
                        canvas.drawLine(px - p.vx * .04f, py - p.vy * .04f, px + len * .2f, py, shapePaint);
                        break;
                    }
                    case PengramConfig.DELETE_PARTICLE_SNOW: {
                        shapePaint.setColor(0xFF9CC7F2);
                        shapePaint.setAlpha(Math.round(230 * alpha));
                        shapePaint.setStrokeWidth(Math.max(dp(.6f), p.size * .22f));
                        final float r = p.size * 2.4f;
                        final int save = canvas.save();
                        canvas.rotate(rotation * .4f, px, py + r * .3f);
                        for (int spoke = 0; spoke < 3; spoke++) {
                            canvas.drawLine(px - r, py + r * .3f, px + r, py + r * .3f, shapePaint);
                            canvas.rotate(60f, px, py + r * .3f);
                        }
                        canvas.restoreToCount(save);
                        break;
                    }
                    case PengramConfig.DELETE_PARTICLE_PETALS: {
                        final float sway = (float) Math.sin(seconds * 6 + p.seed) * dp(1.2f);
                        shapePaint.setColor(0xFFFF9DC8);
                        shapePaint.setAlpha(Math.round(60 * alpha));
                        final float pw = p.size * 1.5f, ph = p.size * 2.2f;
                        int save = canvas.save();
                        canvas.rotate(rotation * .5f + sway, px + sway, py);
                        canvas.drawOval(px + sway - pw * .72f, py - ph * 1.16f, px + sway + pw * .72f, py + ph * 1.16f, shapePaint);
                        shapePaint.setColor(0xFFFF7FAF);
                        shapePaint.setAlpha(Math.round(170 * alpha));
                        canvas.drawOval(px + sway - pw * .46f, py - ph * .94f, px + sway + pw * .46f, py + ph * .94f, shapePaint);
                        canvas.restoreToCount(save);
                        break;
                    }
                    case PengramConfig.DELETE_PARTICLE_LETTERS: {
                        if (p.ch == 0) {
                            break;
                        }
                        paint.set(edit.getPaint());
                        paint.setAlpha(Math.round(255 * alpha));
                        paint.setMaskFilter(null);
                        final int save = canvas.save();
                        canvas.rotate(rotation * .35f, px, py);
                        final String s = String.valueOf(p.ch);
                        canvas.drawText(s, px, py, paint);
                        canvas.restoreToCount(save);
                        paint.setAlpha(255);
                        break;
                    }
                    case PengramConfig.DELETE_PARTICLE_DUST:
                    default: {
                        shapePaint.setColor(accent);
                        shapePaint.setAlpha(Math.round(150 * alpha));
                        canvas.drawCircle(px, py, Math.max(dp(.6f), p.size), shapePaint);
                        break;
                    }
                }
            }
            shapePaint.setAlpha(255);
        }

        // ---------------------------------------------------------- курсор: отрисовка

        /**
         * Рисуем наш курсор. Возвращает true, если системную каретку надо
         * держать выключенной (наша видна или вот-вот появится).
         */
        boolean drawCursor(Canvas canvas, EditText edit, long now) {
            if (!cursorActive() || !targetValid) {
                return false;
            }
            // мигание: 500 мс горит / 500 мс пусто, но сразу после набора
            // и движения курсор просто горит (нервирует мигание под пальцем)
            final boolean blinkOn = now < forceVisibleUntil || (now / 500) % 2 == 0;
            float alpha = blinkOn ? 1f : 0f;

            final float width = dp(PengramConfig.getTypingCursorWidth());
            float h = targetBottom - targetTop;
            float x = cursorX + cursorBounce;
            float yTop = cursorY - h * .86f;
            float yBottom = cursorY + h * .12f;

            // пульс на бэкспейсе: секундный «вдох» ширины
            if (cursorEvent == CURSOR_EVENT_PULSE) {
                final float k = 1f - Math.min(1f, (now - cursorEventTime) / 300f);
                yTop -= dp(2f) * k;
                yBottom += dp(2f) * k;
            }

            // жидкость: пока курсор скользит к цели, он слегка вытягивается
            if (PengramConfig.isTypingCursorLiquid()) {
                final float stretch = Math.min(dp(8), Math.abs(targetX - cursorX) * .12f)
                        * PengramConfig.getTypingCursorLiquidScale() / 15f;
                yTop -= stretch * .5f;
                yBottom += stretch * .5f;
            }

            int color = Theme.getColor(Theme.key_chat_messagePanelCursor);
            cursorPaint.setColor(color);
            cursorPaint.setAlpha(Math.round(255 * alpha));

            final float radius = width * .5f;
            canvas.drawRoundRect(x - width * .5f, yTop, x + width * .5f, yBottom, radius, radius, cursorPaint);

            // жидкие края выделения: зажатое выделение обрамляется каплями
            if (PengramConfig.isTypingSelectionLiquid() && lastSelStart != lastSelEnd
                    && lastSelStart >= 0 && edit.getLayout() != null) {
                final Layout layout = edit.getLayout();
                drawSelectionEdge(canvas, edit, layout, lastSelStart, color, alpha);
                drawSelectionEdge(canvas, edit, layout, lastSelEnd, color, alpha);
            }
            return true;
        }

        private void drawSelectionEdge(Canvas canvas, EditText edit, Layout layout, int offset, int color, float alpha) {
            final int line;
            try {
                line = layout.getLineForOffset(Math.min(offset, Math.max(0, edit.length())));
            } catch (Exception e) {
                return;
            }
            final int paddingTop = edit.getExtendedPaddingTop() + verticalOffset(edit, layout);
            final float ex = layout.getPrimaryHorizontal(Math.min(offset, Math.max(0, edit.length())))
                    + edit.getCompoundPaddingLeft() - edit.getScrollX();
            final float top = layout.getLineTop(line) + paddingTop - edit.getScrollY();
            final float bottom = layout.getLineBottom(line) + paddingTop - edit.getScrollY();
            cursorPaint.setColor(color);
            cursorPaint.setAlpha(Math.round(110 * alpha));
            final float w = dp(1.4f);
            canvas.drawRoundRect(ex - w * .5f, top, ex + w * .5f, bottom, w * .5f, w * .5f, cursorPaint);
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

    private static final class Particle {
        int style;
        char ch;
        float x, y, vx, vy, gravity, size, rotation, rotationSpeed, seed;
        long start, duration;

        long end() {
            return start + duration;
        }

        float progress(long now) {
            if (now < start || start <= 0) {
                return 0f;
            }
            return Math.min(1f, (now - start) / (float) duration);
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
