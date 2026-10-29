package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.MaskFilter;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
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
 * 1. Появление. Настоящий текст всегда остаётся видимым и принадлежит
 *    EditText. Поверх него на короткое время рисуется цветной/подвижный
 *    след нового символа. Если кадр, разметка или IME опоздали, текст не
 *    может исчезнуть — эффект просто пропускается.
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
    private static final int MAX_DELETE_CHARS = 64;
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
        // Индексы эффектов действительны лишь пока правка не сдвинула их.
        // При обычном добавлении в конце старые следы могут доиграть.
        State previous = states.get(edit);
        // A pending 500 ms blink timer must not delay the caret after an IME edit,
        // including auto-correction and backspace (which may not add glyphs).
        if (previous != null) previous.onTextChanged();
        final int netAdded = count - before;
        if (previous != null && (mode == PengramConfig.INPUT_ANIM_NONE || before > count
                || netAdded > PASTE_THRESHOLD || previous.overlapsInsertion(start + Math.min(before, count)))) {
            previous.clearGlyphs();
        }
        // удаление: символов стало меньше — разлетаются частицы реально удалённого хвоста/выделения
        if (before > count && deleteAnimations) {
            State s = states.get(edit);
            if (s == null) {
                s = new State(edit);
                states.put(edit, s);
            }
            s.spawnFromSnapshot(start, before, count, text);
            s.noteCursorEvent(CURSOR_EVENT_PULSE);
            if (mode == PengramConfig.INPUT_ANIM_NONE || count <= 0 || start < 0 || start >= text.length()) {
                return;
            }
        }
        if (mode == PengramConfig.INPUT_ANIM_NONE || count <= 0 || count <= before || start < 0 || start >= text.length()) {
            return;
        }
        final int end = Math.min(text.length(), start + count);
        final int added = Math.max(1, count - before);
        if (added > PASTE_THRESHOLD) {
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
        if (edit == null || text == null) {
            return;
        }
        if (count <= 0 || count > MAX_DELETE_CHARS
                || PengramConfig.getInputAnimation() == PengramConfig.INPUT_ANIM_NONE
                || !PengramConfig.isTypingDeleteAnim()) {
            final State previous = states.get(edit);
            if (previous != null) {
                previous.removedCount = 0;
                previous.removedStart = -1;
            }
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
        if (edit != null) {
            syncNativeCursor(edit, false);
            edit.invalidate();
        }
    }

    /** Сбросить только летящие глифы (например, при переключении слайдеров превью), не убивая живой курсор. */
    public static void clearGlyphsOnly(EditText edit) {
        if (edit == null) {
            return;
        }
        final State state = states.get(edit);
        if (state != null) {
            state.clearGlyphs();
        }
        edit.invalidate();
    }

    // -------------------------------------------------------------- отрисовка

    private static boolean wantsCustomCursor(EditText edit) {
        return edit != null
                && PengramConfig.isTypingCursorSmooth()
                && org.telegram.messenger.SharedConfig.animationsEnabled()
                && edit.isFocused()
                && edit.isShown();
    }

    /**
     * Вызывается из {@code onDraw} поля ввода ДО {@code super.onDraw(canvas)}.
     * Подхватывает свежую разметку {@link Layout} и временно маскирует прямоугольники
     * активных летящих букв через {@code clipOutRect}, чтобы стандартная отрисовка
     * {@code TextView} не печатала поверх них неподвижную 100%-плотную копию символа
     * и при этом мы никогда не меняли {@link Editable} спанами.
     *
     * @return {@code saveCount >= 0}, который нужно передать в {@code canvas.restoreToCount},
     *         либо {@code -1}, если маскирование на этом кадре не требовалось.
     */
    public static int preDraw(EditText edit, Canvas canvas) {
        if (edit == null || canvas == null || states.isEmpty()) {
            return -1;
        }
        final State state = states.get(edit);
        if (state == null) {
            return -1;
        }
        return state.preDraw(canvas);
    }

    /** вызывается из onDraw поля ввода сразу после super.onDraw */
    public static void drawOverlay(EditText edit, Canvas canvas) {
        if (states.isEmpty() && !PengramConfig.isTypingCursorSmooth()) {
            syncNativeCursor(edit, false);
            return;
        }
        State state = states.get(edit);
        if (state == null) {
            if (wantsCustomCursor(edit)) {
                state = new State(edit);
                states.put(edit, state);
            } else {
                syncNativeCursor(edit, false);
                return;
            }
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
        final android.graphics.Path liquidPath = new android.graphics.Path();
        final HashMap<Integer, MaskFilter> blurCache = new HashMap<>();
        final Random random = new Random();
        boolean scheduled;

        /** отложенная пачка символов из последнего изменения текста */
        int pendingStart = -1, pendingEnd = -1, pendingMode;
        long pendingQueuedAt;
        String pendingText;
        boolean pendingPosted;

        /** снапшот стираемых символов из beforeTextChanged */
        char[] removedChars;
        float[] removedX, removedBaseline;
        int removedCount, removedStart = -1;

        // ---------------------------------------------------------- курсор
        float cursorX = -1, cursorY;
        float tailX = -1, tailY;             // жидкий хвост капли, догоняющий головку курсора
        long lastAdvanceMs;                  // точный dt без двойного шага в run() + draw()
        long lastCursorMove;                 // последнее изменение позиции/текста
        long lastTextEdit;                   // реальная правка IME, не кадр анимации
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
            pendingQueuedAt = SystemClock.uptimeMillis();
            final EditText edit = ref.get();
            final Editable value = edit == null ? null : edit.getText();
            pendingText = value != null && start >= 0 && end <= value.length()
                    ? value.subSequence(start, end).toString() : null;
            if (edit != null) {
                if (!pendingPosted) {
                    pendingPosted = true;
                    edit.post(applyRunnable);
                }
                edit.invalidate();
            }
        }

        void onTextChanged() {
            final EditText edit = ref.get();
            lastTextEdit = SystemClock.uptimeMillis();
            lastCursorMove = lastTextEdit;
            forceVisibleUntil = lastTextEdit + 1400;
            if (edit != null && cursorActive()) {
                // Cancel any delayed blink callback; request a frame for this edit.
                edit.removeCallbacks(this);
                scheduled = false;
                schedule();
                edit.invalidate();
            }
        }

        void noteTyping() {
            lastCursorMove = SystemClock.uptimeMillis();
            lastTextEdit = lastCursorMove;
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
            if (start < 0) {
                return;
            }
            final EditText edit = ref.get();
            final long now = SystemClock.uptimeMillis();
            if (edit == null || mode != PengramConfig.getInputAnimation()
                    || mode == PengramConfig.INPUT_ANIM_NONE || now - pendingQueuedAt > 260) {
                pendingStart = pendingEnd = -1;
                pendingText = null;
                return;
            }
            final Editable text = edit.getText();
            if (text == null || end > text.length() || !edit.isAttachedToWindow()
                    || expected == null || !android.text.TextUtils.equals(expected, text.subSequence(start, end))) {
                pendingStart = pendingEnd = -1;
                pendingText = null;
                return;
            }
            final Layout layout = edit.getLayout();
            // IME обновляет Editable раньше, чем TextView успевает пересобрать Layout в onMeasure/assumeLayout.
            // НЕ сбрасываем pendingStart раньше времени — подхватим в preDraw/draw сразу после готовности Layout!
            if (layout == null || layout.getText() == null || layout.getText().length() != text.length()) {
                edit.postInvalidateOnAnimation();
                return;
            }
            pendingStart = pendingEnd = -1;
            pendingText = null;
            final long duration = new long[]{185, 250, 330}[
                    Math.max(0, Math.min(2, PengramConfig.getInputAnimationSpeed()))];
            final float intensity = new float[]{0.75f, 1.08f, 1.42f}[
                    Math.max(0, Math.min(2, PengramConfig.getInputAnimationIntensity() - 1))];

            // берём ровно хвост изменения: последние MAX_PER_BATCH символов
            int from = end;
            final int[] bounds = new int[MAX_PER_BATCH + 1];
            int count = 0;
            while (from > start && count < MAX_PER_BATCH) {
                bounds[count++] = from;
                from = android.text.TextUtils.getOffsetBefore(text, from);
            }
            bounds[count] = Math.max(start, from);

            final int lastLine = layout.getLineCount() - 1;
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
                if (!allLines && lastLine >= 0) {
                    final int line;
                    try {
                        line = layout.getLineForOffset(offset);
                    } catch (Exception e) {
                        continue;
                    }
                    if (line != lastLine && line != layout.getLineForOffset(Math.min(end, text.length()))) {
                        continue;
                    }
                }
                // drawText не умеет корректно рисовать составные эмодзи и
                // диакритику. Пусть их без изменений отрисует сам EditText.
                if (next - offset != 1 || Character.isSurrogate(text.charAt(offset))
                        || Character.getType(text.charAt(offset)) == Character.NON_SPACING_MARK) {
                    continue;
                }
                glyphs.add(new Glyph(offset, next, text.subSequence(offset, next).toString(),
                        mode, intensity, now + Math.min(32, index * 16L), duration));
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
                int n = 0;
                for (int i = 0; i < count; i++) {
                    final int offset = start + i;
                    if (offset >= text.length()) {
                        break;
                    }
                    final int line = layout.getLineForOffset(offset);
                    removedChars[n] = text.charAt(offset);
                    removedX[n] = layout.getPrimaryHorizontal(offset) + paddingLeft;
                    removedBaseline[n] = layout.getLineBaseline(line) + paddingTop;
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
        void spawnFromSnapshot(int start, int before, int count, CharSequence afterText) {
            final char[] chars = removedChars;
            final float[] xs = removedX, baselines = removedBaseline;
            final int n = removedCount;
            final boolean match = removedStart == start && n == before;
            removedCount = 0;
            removedStart = -1;
            if (!match || chars == null) {
                return;
            }
            int commonPrefix = 0;
            int commonSuffix = 0;
            if (count > 0 && afterText != null && start >= 0 && start + count <= afterText.length()) {
                final int maxCommon = Math.min(n, count);
                while (commonPrefix < maxCommon && chars[commonPrefix] == afterText.charAt(start + commonPrefix)) {
                    commonPrefix++;
                }
                while (commonSuffix < maxCommon - commonPrefix
                        && chars[n - 1 - commonSuffix] == afterText.charAt(start + count - 1 - commonSuffix)) {
                    commonSuffix++;
                }
            }
            final int delStart = commonPrefix;
            final int delEnd = Math.max(delStart, n - commonSuffix);
            if (delEnd <= delStart) {
                return;
            }
            final int style = PengramConfig.getTypingDeleteStyle();
            final int perChar = Math.max(1, PengramConfig.getTypingDeleteCount());
            final float speed = PengramConfig.getTypingDeleteSpeed() / 50f;
            final float spread = PengramConfig.getTypingDeleteSpread() / 50f;
            final float size = PengramConfig.getTypingDeleteSize() / 50f;
            final long now = SystemClock.uptimeMillis();
            final int endIdx = Math.min(delEnd, delStart + 12);
            for (int i = delStart; i < endIdx && particles.size() < 160; i++) {
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
            return wantsCustomCursor(edit);
        }

        private boolean isCursorMoving() {
            return Math.abs(targetX - cursorX) > 0.25f
                    || Math.abs(targetY - cursorY) > 0.25f
                    || (PengramConfig.isTypingCursorLiquid()
                        && (Math.abs(cursorX - tailX) > 0.25f || Math.abs(cursorY - tailY) > 0.25f))
                    || cursorBounce != 0
                    || cursorEvent != CURSOR_EVENT_NONE;
        }

        /** Задержка до следующей фазы плавного дыхания каретки (0 — нужен следующий VSync-кадр). */
        private long nextBlinkDelayMs(long now) {
            if (now < forceVisibleUntil) {
                return Math.max(16L, forceVisibleUntil - now);
            }
            final long cycle = (now - forceVisibleUntil) % 1060L;
            if (cycle < 420L) {
                return Math.max(16L, 420L - cycle);
            }
            if (cycle < 560L) {
                return 0L; // фаза плавного затухания — рисуем по VSync
            }
            if (cycle < 920L) {
                return Math.max(16L, 920L - cycle);
            }
            return 0L; // фаза плавного проявления — рисуем по VSync
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
                edit.invalidate();
                if (isCursorMoving()) {
                    schedule();
                } else {
                    scheduleAfter(nextBlinkDelayMs(now));
                }
                return;
            }
            cursorX = tailX = -1;
            lastAdvanceMs = 0;
            idleFrom = now;
        }

        void finish(Glyph glyph) {
            glyphs.remove(glyph);
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

        boolean overlapsInsertion(int offset) {
            for (Glyph glyph : glyphs) {
                if (glyph.to > offset) {
                    return true;
                }
            }
            return false;
        }

        void clearGlyphs() {
            final EditText edit = ref.get();
            if (edit != null) {
                edit.removeCallbacks(applyRunnable);
            }
            pendingPosted = false;
            pendingStart = pendingEnd = -1;
            pendingText = null;
            glyphs.clear();
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
            if (selStart < 0 || selEnd < 0 || !edit.isCursorVisible()
                    || layout.getText() == null || selStart > layout.getText().length()
                    || selEnd > layout.getText().length()) {
                // IME may update selection before the matching TextView layout.
                // Leave the native caret enabled until the next valid frame.
                return;
            }
            final int line = layout.getLineForOffset(selStart);
            if (line < 0 || line >= layout.getLineCount()) {
                return;
            }
            final int paddingTop = edit.getExtendedPaddingTop() + verticalOffset(edit, layout);
            targetX = layout.getPrimaryHorizontal(selStart)
                    + edit.getCompoundPaddingLeft();
            targetY = layout.getLineBaseline(line) + paddingTop;
            targetTop = layout.getLineTop(line) + paddingTop;
            targetBottom = layout.getLineBottom(line) + paddingTop;
            targetValid = true;
        }

        void advanceCursor(long now) {
            if (!cursorActive()) {
                cursorX = tailX = -1;
                lastAdvanceMs = 0;
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
            final boolean selectionChanged = selStart != lastSelStart || selEnd != lastSelEnd;
            if (selectionChanged) {
                lastSelStart = selStart;
                lastSelEnd = selEnd;
                lastCursorMove = now;
                forceVisibleUntil = now + 1400;
            }

            if (cursorX < 0 || tailX < 0) {
                cursorX = tailX = targetX;
                cursorY = tailY = targetY;
                lastAdvanceMs = now;
                return;
            }

            final float lineH = Math.max(dp(16), targetBottom - targetTop);
            final float rawDx = targetX - cursorX;
            final float rawDy = targetY - cursorY;
            // Мгновенно переносим каретку только при огромном прыжке (вставка большого абзаца
            // или переход через >4 строк). Обычный набор, удаление, перенос строки и тап по слову
            // всегда скользят плавно по экспоненциальной пружине без зависимости от герцовки экрана.
            final float maxJumpX = Math.max(dp(240), edit.getWidth() * 0.85f);
            if (Math.abs(rawDy) > lineH * 4.2f || Math.abs(rawDx) > maxJumpX) {
                cursorX = tailX = targetX;
                cursorY = tailY = targetY;
                lastAdvanceMs = now;
                return;
            }

            if (lastAdvanceMs == now) {
                return;
            }
            final float dt = lastAdvanceMs <= 0
                    ? 0.008f
                    : Math.min(0.05f, Math.max(0.001f, (now - lastAdvanceMs) / 1000f));
            lastAdvanceMs = now;

            final int speedSlider = PengramConfig.getTypingCursorSpeed(); // 1..60 (по умолч. 25)
            // Быстрая отзывчивая головка без эффекта «вязкой клавиатуры»:
            // при дефолте 25 — ~40 1/с (~58 мс до цели), при 1 — мягкое скольжение, при 60 — молниеносно.
            final float headRate = 11f + speedSlider * 1.15f;
            final float headAlpha = 1f - (float) Math.exp(-headRate * dt);

            // При пулемётном наборе (>10 симв/с) не даём головке отстать дальше 3 символов
            if (now - lastTextEdit < 160 && Math.abs(rawDy) < dp(6) && Math.abs(rawDx) > dp(30)) {
                cursorX = targetX - Math.copySign(dp(30), rawDx);
            }

            cursorX += (targetX - cursorX) * headAlpha;
            cursorY += (targetY - cursorY) * headAlpha;
            if (Math.abs(targetX - cursorX) < 0.2f) {
                cursorX = targetX;
            }
            if (Math.abs(targetY - cursorY) < 0.2f) {
                cursorY = targetY;
            }

            if (PengramConfig.isTypingCursorLiquid()) {
                final int stretchSlider = PengramConfig.getTypingCursorLiquidScale(); // 0..40 (по умолч. 15)
                final float tailRate = headRate * Math.max(0.22f, 0.58f - stretchSlider * 0.0085f);
                final float tailAlpha = 1f - (float) Math.exp(-tailRate * dt);
                tailX += (cursorX - tailX) * tailAlpha;
                tailY += (cursorY - tailY) * tailAlpha;
                final float maxTail = dp(6f + stretchSlider * 1.45f);
                final float tdx = cursorX - tailX;
                final float tdy = cursorY - tailY;
                final float dist = (float) Math.hypot(tdx, tdy);
                if (dist > maxTail && dist > 0.001f) {
                    final float k = maxTail / dist;
                    tailX = cursorX - tdx * k;
                    tailY = cursorY - tdy * k;
                }
                if (Math.abs(cursorX - tailX) < 0.2f) {
                    tailX = cursorX;
                }
                if (Math.abs(cursorY - tailY) < 0.2f) {
                    tailY = cursorY;
                }
            } else {
                tailX = cursorX;
                tailY = cursorY;
            }

            if (cursorBounce != 0) {
                cursorBounce *= (float) Math.pow(0.82, dt * 60f);
                if (Math.abs(cursorBounce) < 0.4f) {
                    cursorBounce = 0;
                }
            }
            if (now - cursorEventTime > 320) {
                cursorEvent = CURSOR_EVENT_NONE;
            }
        }

        // ---------------------------------------------------------- отрисовка

        int preDraw(Canvas canvas) {
            if (pendingStart >= 0) {
                applyPending();
            }
            if (glyphs.isEmpty() || PengramConfig.getInputAnimation() == PengramConfig.INPUT_ANIM_NONE) {
                return -1;
            }
            final EditText edit = ref.get();
            if (edit == null) {
                return -1;
            }
            final Editable text = edit.getText();
            final Layout layout = edit.getLayout();
            if (text == null || layout == null || layout.getText() == null || layout.getText().length() != text.length()) {
                return -1;
            }
            final long nowMs = SystemClock.uptimeMillis();
            final int paddingLeft = edit.getCompoundPaddingLeft();
            final int paddingTop = edit.getExtendedPaddingTop() + verticalOffset(edit, layout);
            final boolean customCaret = wantsCustomCursor(edit);
            int saveCount = -1;
            for (int a = glyphs.size() - 1; a >= 0; --a) {
                final Glyph glyph = glyphs.get(a);
                if (nowMs >= glyph.endTime()) {
                    finish(glyph);
                    continue;
                }
                final int from = glyph.from;
                final int to = glyph.to;
                if (from < 0 || to <= from || to > text.length()
                        || !android.text.TextUtils.equals(glyph.expected, text.subSequence(from, to))) {
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
                paint.set(edit.getPaint());
                try {
                    for (CharacterStyle style : text.getSpans(from, to, CharacterStyle.class)) {
                        style.updateDrawState(paint);
                    }
                } catch (Exception ignore) {
                }
                if (paint.getAlpha() == 0) {
                    continue;
                }
                final float width = paint.measureText(text, from, to);
                if (width <= 0.2f) {
                    continue;
                }
                final float x = layout.getPrimaryHorizontal(from) + paddingLeft;
                final float top = layout.getLineTop(line) + paddingTop;
                final float bottom = layout.getLineBottom(line) + paddingTop;
                final boolean rtl;
                boolean isRtl = false;
                try {
                    isRtl = layout.isRtlCharAt(from);
                } catch (Exception ignore) {
                }
                rtl = isRtl;
                final float padTrail = customCaret ? dp(1.2f) : -dp(0.4f);
                final float left = rtl ? (x - width - padTrail) : (x - 0.5f);
                final float right = rtl ? (x + 0.5f) : (x + Math.max(1f, width + padTrail));
                if (right <= left) {
                    continue;
                }
                if (saveCount < 0) {
                    saveCount = canvas.save();
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    canvas.clipOutRect(left, top, right, bottom);
                } else {
                    canvas.clipRect(left, top, right, bottom, android.graphics.Region.Op.DIFFERENCE);
                }
            }
            return saveCount;
        }

        void draw(Canvas canvas) {
            final EditText edit = ref.get();
            if (edit == null) {
                return;
            }
            if (pendingStart >= 0) {
                applyPending();
            }
            final Editable text = edit.getText();
            final Layout layout = edit.getLayout();
            if (text == null) {
                return;
            }
            // Если кадровый колбэк не дошёл, устаревший след снимаем на отрисовке.
            final long nowMs = SystemClock.uptimeMillis();
            for (int a = glyphs.size() - 1; a >= 0; --a) {
                if (glyphs.get(a).endTime() <= nowMs) {
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
            // View.draw has already translated this canvas by -scrollX/-scrollY.
            // Clip in content coordinates; subtracting scroll from glyph/caret
            // positions here again pushed the caret far above long input.
            canvas.clipRect(edit.getCompoundPaddingLeft() + scrollX - dp(2), scrollY,
                    edit.getWidth() - edit.getCompoundPaddingRight() + scrollX + dp(2),
                    edit.getHeight() + scrollY);

            if (layout != null) {
                drawGlyphs(canvas, edit, text, layout, paddingLeft, paddingTop);
            } else {
                clearGlyphs(); // layout was rebuilt; original text stays visible
            }
            drawParticles(canvas, edit);
            // TextView has completed its layout in super.onDraw. Sample the real
            // caret here rather than waiting for the next scheduled frame.
            advanceCursor(nowMs);
            final boolean cursorDrawn = drawCursor(canvas, edit, nowMs);
            syncNativeCursor(edit, cursorDrawn);

            canvas.restoreToCount(clip);
            // цикл кадров запускает run() сам; отсюда лишь будим его, если вдруг стоит
            if (cursorActive() || !glyphs.isEmpty() || !particles.isEmpty()) {
                schedule();
            }
        }

        private void drawGlyphs(Canvas canvas, EditText edit, Editable text, Layout layout,
                                int paddingLeft, int paddingTop) {
            if (glyphs.isEmpty()) {
                return;
            }
            if (layout.getText() == null || layout.getText().length() != text.length()) {
                clearGlyphs();
                return;
            }
            final boolean blurOn = PengramConfig.isTypingBlur();
            final float blurMax = PengramConfig.getTypingBlurRadius();
            for (int a = glyphs.size() - 1; a >= 0; --a) {
                final Glyph glyph = glyphs.get(a);
                final int from = glyph.from;
                final int to = glyph.to;
                if (from < 0 || to <= from || to > text.length()
                        || !android.text.TextUtils.equals(glyph.expected, text.subSequence(from, to))) {
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
                // Рисуем след тем же шрифтом и цветом, что и исходный текст;
                // оригинальные глифы уже нарисованы TextView и не меняются.
                paint.set(edit.getPaint());
                try {
                    for (CharacterStyle style : text.getSpans(from, to, CharacterStyle.class)) {
                        style.updateDrawState(paint);
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
                final float x = layout.getPrimaryHorizontal(from) + paddingLeft;
                final float baseline = layout.getLineBaseline(line) + paddingTop;
                final float width = paint.measureText(text, from, to);

                final float rawT = glyph.rawTime();
                if (rawT <= 0f) {
                    // Символ ждёт своей микрозадержки каскада (0..32 мс) — пока остаётся полностью скрыт
                    continue;
                }
                final float p = glyph.progress();
                final float inv = 1f - p;
                final float rawInv = 1f - rawT;
                final float k = glyph.intensity;
                // Реальный глиф проявляется от 0 до 100 % плотности и ровно в p=1 сшивается со штатным TextView
                float alpha = Math.min(1f, rawT * 2.35f);
                float scaleX = 1f, scaleY = 1f, dx = 0f, dy = 0f, rotation = 0f;
                switch (glyph.mode) {
                    case PengramConfig.INPUT_ANIM_FADE:
                        alpha = 1f - rawInv * rawInv;
                        dy = inv * dp(4.5f) * k;
                        break;
                    case PengramConfig.INPUT_ANIM_POP: {
                        // Пружина с перелётом: вырастает с ~0.35 до ~1.18 и мягко садится в 1.0
                        final float wave = (float) (Math.exp(-5.0f * rawT) * Math.cos(9.2f * rawT));
                        final float s = 1f - wave * 0.66f * Math.min(1.25f, k) * rawInv;
                        scaleX = scaleY = Math.max(0.22f, s);
                        dy = -(float) Math.sin(rawT * Math.PI) * rawInv * dp(3.0f) * k;
                        break;
                    }
                    case PengramConfig.INPUT_ANIM_SLIDE: {
                        final int dir = LocaleController.isRTL ? -1 : 1;
                        dx = inv * dp(11f) * k * dir;
                        scaleX = 1f + 0.20f * inv * k;
                        scaleY = Math.max(0.78f, 1f - 0.08f * inv * k);
                        break;
                    }
                    case PengramConfig.INPUT_ANIM_RISE: {
                        // Всплытие снизу с лёгким перелётом вверх над базовой линией
                        final float overshoot = 1f + 2.1f * (p - 1f) * (p - 1f) * (p - 1f) + 1.1f * (p - 1f) * (p - 1f);
                        dy = (1f - overshoot) * dp(11.5f) * k;
                        scaleX = scaleY = 0.72f + 0.28f * p;
                        break;
                    }
                    case PengramConfig.INPUT_ANIM_BOUNCE: {
                        // Упругая капля (squash & stretch с сохранением площади)
                        dy = -(float) Math.abs(Math.sin(rawT * Math.PI * 1.5f)) * rawInv * dp(10.5f) * k;
                        scaleY = Math.max(0.70f, 1f + (float) Math.sin(rawT * Math.PI * 2.0f) * rawInv * 0.34f * k);
                        scaleX = 1f / scaleY;
                        break;
                    }
                    case PengramConfig.INPUT_ANIM_SHAKE: {
                        rotation = (float) Math.sin(rawT * Math.PI * 2.5f) * rawInv * 19f * k;
                        scaleX = scaleY = 0.68f + 0.32f * p + (float) Math.sin(rawT * Math.PI) * rawInv * 0.14f * k;
                        break;
                    }
                }

                final int baseColor = paint.getColor();
                final int save = canvas.save();
                try {
                    canvas.translate(dx, dy);
                    final float pivotY = baseline - paint.getTextSize() * 0.32f;
                    if (rotation != 0f) {
                        canvas.rotate(rotation, x + width * .5f, pivotY);
                    }
                    if (ScaleIsOff(scaleX) || ScaleIsOff(scaleY)) {
                        canvas.scale(scaleX, scaleY, x + width * .5f, baseline);
                    }
                    if (glyph.mode == PengramConfig.INPUT_ANIM_FADE && p < 0.92f) {
                        final int accent = Theme.getColor(Theme.key_chat_messagePanelCursor);
                        paint.setColor(org.telegram.messenger.AndroidUtilities.getOffsetColor(accent, baseColor, p / 0.92f, 1f));
                    }
                    final int mainAlpha = Math.max(0, Math.min(255, Math.round(baseAlpha * alpha)));
                    if (blurOn && rawT < 0.72f) {
                        // GPU-атласный мягкий оптический ореол без CPU-растеризации BlurMaskFilter в Skia/HWUI:
                        // 0 аллокаций текстур и 120 Гц даже при пулемётном наборе.
                        final float softNorm = Math.min(1f, (blurMax / 12f) * (1f - rawT / 0.72f));
                        if (softNorm > 0.06f) {
                            final int haloSave = canvas.save();
                            final float haloScale = 1f + softNorm * 0.095f;
                            canvas.scale(haloScale, haloScale, x + width * .5f, pivotY);
                            paint.setAlpha(Math.max(1, Math.min(255, Math.round(mainAlpha * 0.28f * softNorm))));
                            canvas.drawText(text, from, to, x, baseline, paint);
                            canvas.restoreToCount(haloSave);
                        }
                    }
                    paint.setAlpha(mainAlpha);
                    canvas.drawText(text, from, to, x, baseline, paint);
                } catch (Exception ignore) {
                } finally {
                    paint.setColor(baseColor);
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
            // Плавное дыхание каретки вместо резкого квадратного мигания 0/1:
            // сразу после ввода и во время скольжения каретка горит ровно (1.0).
            float alpha;
            if (now < forceVisibleUntil || isCursorMoving()) {
                alpha = 1f;
            } else {
                final long cycle = (now - forceVisibleUntil) % 1060L;
                if (cycle < 420L) {
                    alpha = 1f;
                } else if (cycle < 560L) {
                    alpha = 0.5f + 0.5f * (float) Math.cos(Math.PI * (cycle - 420L) / 140.0);
                } else if (cycle < 920L) {
                    alpha = 0f;
                } else {
                    alpha = 0.5f - 0.5f * (float) Math.cos(Math.PI * (cycle - 920L) / 140.0);
                }
            }

            final float width = Math.max(dp(1.5f), dp(PengramConfig.getTypingCursorWidth()));
            final float h = targetBottom - targetTop;
            final float x = cursorX + cursorBounce;
            float yTop = cursorY - h * .86f;
            float yBottom = cursorY + h * .12f;

            // пульс на бэкспейсе: мягкий «вдох» каретки
            float pulseWidth = 0f;
            if (cursorEvent == CURSOR_EVENT_PULSE) {
                final float k = Math.max(0f, 1f - (now - cursorEventTime) / 280f);
                final float eased = k * k;
                yTop -= dp(2f) * eased;
                yBottom += dp(2f) * eased;
                pulseWidth = dp(0.8f) * eased;
            } else if (cursorEvent == CURSOR_EVENT_DIVE) {
                final float k = Math.max(0f, 1f - (now - cursorEventTime) / 260f);
                final float s = (float) Math.sin(k * Math.PI);
                yTop += dp(1.8f) * s;
                yBottom += dp(1.2f) * s;
            }

            final int color = Theme.getColor(Theme.key_chat_messagePanelCursor);
            cursorPaint.setColor(color);
            final int paintAlpha = Math.round(255 * alpha);
            if (paintAlpha > 0) {
                cursorPaint.setAlpha(paintAlpha);
                final float barW = width + pulseWidth;
                final float rHead = barW * .5f;
                final int liquidScale = PengramConfig.getTypingCursorLiquidScale();
                final boolean liquid = PengramConfig.isTypingCursorLiquid() && liquidScale > 0;
                final float stretchBoost = 0.55f + liquidScale / 18f;
                final float rawTailDx = (tailX - cursorX) * stretchBoost;
                final float rawTailDy = (tailY - cursorY) * Math.min(1.25f, stretchBoost * 0.75f);
                final float maxSpan = dp(5f + liquidScale * 1.4f);
                final float spanDist = (float) Math.hypot(rawTailDx, rawTailDy);
                final float clampK = spanDist > maxSpan && spanDist > 0.001f ? maxSpan / spanDist : 1f;
                final float tx = x + rawTailDx * clampK;
                final float tyCenter = (yTop + yBottom) * .5f + rawTailDy * clampK;
                final float spanX = Math.abs(tx - x);
                final float spanY = Math.abs(rawTailDy * clampK);

                if (liquid && (spanX > dp(0.75f) || spanY > dp(0.9f))) {
                    // Настоящая жидкая капля (сохранение объёма): головка слегка сжимается
                    // по вертикали, а к хвосту тянется упругая перемычка с вогнутой талией.
                    final float barH = Math.max(dp(6f), yBottom - yTop);
                    final float headCenterY = (yTop + yBottom) * .5f;
                    final float squash = Math.min(barH * 0.16f, (spanX + spanY * 0.5f) * 0.09f);
                    final float headHalfH = Math.max(rHead, (barH - squash) * .5f);
                    final float tailRatio = Math.max(0.22f, 0.48f - Math.min(0.24f, spanX / Math.max(1f, dp(42))));
                    final float tailHalfH = Math.max(dp(1.2f), headHalfH * tailRatio);
                    final float rTail = Math.min(tailHalfH, Math.max(dp(0.9f), rHead * 0.72f));

                    final boolean tailLeft = tx < x;
                    final float leftX = tailLeft ? tx : x;
                    final float rightX = tailLeft ? x : tx;
                    final float leftCY = tailLeft ? tyCenter : headCenterY;
                    final float rightCY = tailLeft ? headCenterY : tyCenter;
                    final float leftHH = tailLeft ? tailHalfH : headHalfH;
                    final float rightHH = tailLeft ? headHalfH : tailHalfH;
                    final float leftR = tailLeft ? rTail : rHead;
                    final float rightR = tailLeft ? rHead : rTail;

                    // Вогнутая талия капли посередине траектории
                    final float midX = (leftX + rightX) * .5f;
                    final float midCY = (leftCY + rightCY) * .5f;
                    final float pinch = Math.min(0.38f, spanX / Math.max(1f, dp(52)) * 0.34f);
                    final float waistHalfH = Math.max(dp(0.85f), ((leftHH + rightHH) * .5f) * (1f - pinch));

                    // Мягкое полупрозрачное свечение шлейфа + основное тело капли
                    liquidPath.reset();
                    liquidPath.moveTo(leftX, leftCY - leftHH);
                    liquidPath.quadTo(midX, midCY - waistHalfH, rightX, rightCY - rightHH);
                    liquidPath.quadTo(rightX + rightR * 1.18f, rightCY, rightX, rightCY + rightHH);
                    liquidPath.quadTo(midX, midCY + waistHalfH, leftX, leftCY + leftHH);
                    liquidPath.quadTo(leftX - leftR * 1.18f, leftCY, leftX, leftCY - leftHH);
                    liquidPath.close();

                    cursorPaint.setAlpha(Math.round(paintAlpha * 0.42f));
                    canvas.drawPath(liquidPath, cursorPaint);

                    // Плотная головка каретки всегда чётко отмечает точку ввода
                    cursorPaint.setAlpha(paintAlpha);
                    canvas.drawRoundRect(x - rHead, headCenterY - headHalfH,
                            x + rHead, headCenterY + headHalfH, rHead, rHead, cursorPaint);
                } else {
                    canvas.drawRoundRect(x - rHead, yTop, x + rHead, yBottom, rHead, rHead, cursorPaint);
                }
            }

            // жидкие края выделения: зажатое выделение обрамляется каплями
            if (PengramConfig.isTypingSelectionLiquid() && lastSelStart != lastSelEnd
                    && lastSelStart >= 0 && edit.getLayout() != null && alpha > 0.01f) {
                final Layout layout = edit.getLayout();
                drawSelectionEdge(canvas, edit, layout, lastSelStart, color, alpha, true);
                drawSelectionEdge(canvas, edit, layout, lastSelEnd, color, alpha, false);
            }
            return true;
        }

        private void drawSelectionEdge(Canvas canvas, EditText edit, Layout layout, int offset, int color, float alpha, boolean startEdge) {
            final int line;
            try {
                line = layout.getLineForOffset(Math.min(offset, Math.max(0, edit.length())));
            } catch (Exception e) {
                return;
            }
            final int paddingTop = edit.getExtendedPaddingTop() + verticalOffset(edit, layout);
            final float ex = layout.getPrimaryHorizontal(Math.min(offset, Math.max(0, edit.length())))
                    + edit.getCompoundPaddingLeft();
            final float top = layout.getLineTop(line) + paddingTop;
            final float bottom = layout.getLineBottom(line) + paddingTop;
            cursorPaint.setColor(color);
            cursorPaint.setAlpha(Math.round(165 * alpha));
            final float w = dp(1.8f);
            canvas.drawRoundRect(ex - w * .5f, top, ex + w * .5f, bottom, w * .5f, w * .5f, cursorPaint);
            final float dropR = dp(2.7f);
            final float dropCy = startEdge ? top + dropR * 0.4f : bottom - dropR * 0.4f;
            canvas.drawCircle(ex, dropCy, dropR, cursorPaint);
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
        final int from, to;
        final String expected;
        final int mode;
        final float intensity;
        final long start;
        final long duration;

        Glyph(int from, int to, String expected, int mode, float intensity, long start, long duration) {
            this.from = from;
            this.to = to;
            this.expected = expected;
            this.mode = mode;
            this.intensity = intensity;
            this.start = start;
            this.duration = duration;
        }

        long endTime() {
            return start + duration;
        }

        float rawTime() {
            final long now = SystemClock.uptimeMillis();
            if (now <= start) {
                return 0f;
            }
            return Math.max(0f, Math.min(1f, (now - start) / (float) duration));
        }

        float progress() {
            final float t = rawTime();
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

}
