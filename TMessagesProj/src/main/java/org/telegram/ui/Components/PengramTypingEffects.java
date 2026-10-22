package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.Layout;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Random;
import java.util.WeakHashMap;

/** Только добавочное рисование. Ни текст, ни спаны прозрачности, ни слой EditText
 * не меняются: опоздавшая разметка означает пропуск эффекта, а не пропажу текста. */
public final class PengramTypingEffects {
    private static final int MAX_ACTIVE = 6, MAX_PER_BATCH = 3, PASTE_THRESHOLD = 64;
    private static final int MAX_DELETE_CLUSTERS = 24, MAX_PARTICLES = 160, PARTICLE_LIFE = 620;
    private static final int CURSOR_EVENT_NONE = 0, CURSOR_EVENT_PULSE = 1, CURSOR_EVENT_DIVE = 2, CURSOR_EVENT_JUMP = 3;
    private static final WeakHashMap<EditText, State> states = new WeakHashMap<>();
    private static final ArrayList<WeakReference<Runnable>> listeners = new ArrayList<>();
    private static boolean observing;
    private static boolean systemMotion = true;
    private static final android.database.ContentObserver motionObserver = new android.database.ContentObserver(
            new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { settingsChanged(); }
    };
    private PengramTypingEffects() {}

    public static void refreshMotionPreference() { readMotion(); }
    public static boolean motionAllowed() {
        return SharedConfig.animationsEnabled() && systemMotion;
    }
    private static void readMotion() {
        try {
            systemMotion = Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled()
                    : Settings.Global.getFloat(ApplicationLoader.applicationContext.getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        } catch (RuntimeException ignored) { systemMotion = false; }
    }
    public static void settingsChanged() {
        readMotion();
        final boolean disabled = !motionAllowed() || !effectsEnabled();
        for (State state : states.values()) {
            state.clearAll();
            final EditText edit = state.ref.get();
            if (edit != null) {
                if (disabled) edit.removeOnAttachStateChangeListener(state); else state.warmSettings();
                edit.invalidate();
            }
        }
        if (disabled) states.clear();
        for (int i = listeners.size() - 1; i >= 0; i--) {
            Runnable callback = listeners.get(i).get();
            if (callback == null) listeners.remove(i); else callback.run();
        }
    }
    public static void watch(View view, Runnable callback) {
        ensureObserver();
        // View держит callback, глобальный список — только weak reference.
        final WeakReference<Runnable> weak = new WeakReference<>(callback);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { listeners.add(weak); callback.run(); }
            @Override public void onViewDetachedFromWindow(View v) { listeners.remove(weak); }
        });
        if (view.isAttachedToWindow()) listeners.add(weak);
        callback.run();
    }
    private static final android.content.SharedPreferences.OnSharedPreferenceChangeListener appMotionListener = (prefs, key) -> {
        if ("view_animations".equals(key)) org.telegram.messenger.AndroidUtilities.runOnUIThread(PengramTypingEffects::settingsChanged);
    };
    private static void ensureObserver() {
        if (observing) return;
        readMotion();
        ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver);
        org.telegram.messenger.MessagesController.getGlobalMainSettings().registerOnSharedPreferenceChangeListener(appMotionListener);
        observing = true;
    }
    private static State state(EditText edit) {
        ensureObserver();
        State value = states.get(edit);
        if (value == null) { value = new State(edit); states.put(edit, value); }
        return value;
    }
    public static boolean composing(EditText edit) {
        return edit != null && edit.getText() != null && BaseInputConnection.getComposingSpanStart(edit.getText()) >= 0;
    }
    private static boolean effectsEnabled() {
        // Почему: удаление и курсор — отдельные переключатели, не дочерние режимы появления.
        return PengramConfig.getInputAnimation() != PengramConfig.INPUT_ANIM_NONE
                || PengramConfig.isTypingDeleteAnim() || PengramConfig.isTypingCursorSmooth();
    }
    private static boolean live(EditText edit) {
        return edit != null && edit.isAttachedToWindow() && edit.isShown() && edit.isFocused()
                && edit.hasWindowFocus() && motionAllowed() && effectsEnabled();
    }
    public static void wake(EditText edit) {
        try {
            if (live(edit) && !composing(edit)) { State s = state(edit); s.onTextChanged(); }
        } catch (RuntimeException | OutOfMemoryError unavailable) { clear(edit); }
    }
    public static void apply(EditText edit, Editable text, int start, int before, int count) {
        apply(edit, text, start, before, count, false);
    }
    public static void apply(EditText edit, Editable text, int start, int before, int count, boolean isPaste) {
        apply(edit, text, start, before, count, isPaste, false);
    }
    public static void apply(EditText edit, Editable text, int start, int before, int count, boolean isPaste, boolean composition) {
        try { applyUserEdit(edit, text, start, before, count, isPaste, composition); }
        catch (RuntimeException | OutOfMemoryError unavailable) { clear(edit); }
    }
    private static void applyUserEdit(EditText edit, Editable text, int start, int before, int count, boolean isPaste, boolean composition) {
        if (edit == null || text == null) return;
        if (!live(edit) || isPaste) { clear(edit); return; }
        final State s = state(edit);
        s.onTextChanged();
        if (before > 0 && count > 0 && s.replacedStart == start && s.replacedText != null && s.replacedText.length() == before) {
            final String old = s.replacedText;
            int prefix = 0;
            while (prefix < before && prefix < count && old.charAt(prefix) == text.charAt(start + prefix)) prefix++;
            int suffix = 0;
            while (suffix < before - prefix && suffix < count - prefix
                    && old.charAt(before - suffix - 1) == text.charAt(start + count - suffix - 1)) suffix++;
            start += prefix;
            before -= prefix + suffix;
            count -= prefix + suffix;
        }
        s.replacedText = null;
        s.invalidateEditedRange(start, before, count);
        // Почему: IME часто заменяет слово целиком; эффект нужен лишь изменённому хвосту.
        if (before > 0) {
            if (count == 0 && PengramConfig.isTypingDeleteAnim()) s.spawnFromSnapshot(start, before);
            else s.releaseSnapshot();
            s.noteCursorEvent(CURSOR_EVENT_PULSE);
            if (count == 0) return;
        }
        s.releaseSnapshot();
        if (count <= 0 || start < 0 || start + count > text.length()
                || composition || composing(edit) || PengramConfig.getInputAnimation() == PengramConfig.INPUT_ANIM_NONE) return;
        final int end = start + count;
        // Размер — лишь страховка против массового/голосового ввода. Длинное
        // одиночное слово (glide) допускается; факт paste передаётся отдельно.
        if (count > PASTE_THRESHOLD && count > 4096) return;
        int from = PengramGraphemes.clusterStart(text, start);
        if (from != start) s.invalidateEditedRange(from, end - from, end - from);
        if (PengramGraphemes.clusterStart(text, end) != end) return;
        int last = Character.codePointBefore(text, end);
        s.noteCursorEvent(last == '\n' ? CURSOR_EVENT_DIVE : Character.isWhitespace(last) ? CURSOR_EVENT_JUMP : CURSOR_EVENT_NONE);
        s.enqueue(from, end, PengramConfig.getInputAnimation());
    }
    // Почему: удаление пробельного кластера не должно создавать пустую частицу.
    // Это фильтр снапшота, не эвристика paste/glide.
    private static boolean multiword(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) if (Character.isWhitespace(text.charAt(i))) return true;
        return false;
    }

    /** Коммит композиции может не менять текст вообще: watcher тогда молчит. */
    public static void committed(EditText edit, int start, int end) {
        try { committedSafe(edit, start, end); }
        catch (RuntimeException | OutOfMemoryError unavailable) { clear(edit); }
    }
    private static void committedSafe(EditText edit, int start, int end) {
        if (live(edit) && PengramConfig.getInputAnimation() != PengramConfig.INPUT_ANIM_NONE
                && !composing(edit) && start >= 0 && end > start && end <= edit.length()
                && end - start <= 256) {
            State s = state(edit);
            s.onTextChanged();
            boolean covered = false;
            for (int i = 0; i < s.pendingBatches.size(); i++) {
                PendingBatch batch = s.pendingBatches.get(i);
                if (batch.start >= start && batch.end <= end) { covered = true; break; }
            }
            for (int i = 0; !covered && i < s.glyphs.size(); i++) {
                Glyph glyph = s.glyphs.get(i);
                covered = glyph.from >= start && glyph.to <= end;
            }
            if (!covered) s.enqueue(PengramGraphemes.clusterStart(edit.getText(), start), end, PengramConfig.getInputAnimation());
        }
    }
    public static void captureReplacement(EditText edit, CharSequence text, int start, int before, int count) {
        if (!live(edit)) return;
        State s = state(edit);
        s.replacedText = before > 0 && count > 0 && before <= 4096 && start >= 0 && start + before <= text.length()
                ? text.subSequence(start, start + before).toString() : null;
        s.replacedStart = start;
    }

    public static void captureBefore(EditText edit, CharSequence text, int start, int count) {
        if (!live(edit) || !PengramConfig.isTypingDeleteAnim() || count <= 0) {
            State previous = states.get(edit);
            if (previous != null) previous.releaseSnapshot();
            return;
        }
        try { state(edit).snapshot(text, start, count); }
        catch (RuntimeException | OutOfMemoryError unavailable) { clear(edit); }
    }
    public static void clear(EditText edit) {
        State s = states.remove(edit);
        if (s != null) { s.clearAll(); edit.removeOnAttachStateChangeListener(s); }
    }
    public static void prepareDraw(EditText edit) {
        State s = states.get(edit);
        if (s != null) {
            try {
                if (s.textColor != edit.getCurrentTextColor() || s.textSize != edit.getTextSize()) {
                    s.clearAll();
                    s.textColor = edit.getCurrentTextColor(); s.textSize = edit.getTextSize();
                }
                s.computeTarget(edit);
                s.syncNativeCursor(edit, s.cursorActive() && s.targetValid && edit.getSelectionStart() == edit.getSelectionEnd());
            } catch (RuntimeException unavailable) { s.clearAll(); }
        }
    }
    public static void drawOverlay(EditText edit, Canvas canvas) {
        State s = states.get(edit);
        if (s == null) return;
        final int save = canvas.save();
        try { s.draw(canvas); }
        catch (RuntimeException | OutOfMemoryError unavailable) { s.clearAll(); }
        finally { canvas.restoreToCount(save); }
    }

    private static final class Raster {
        String key;
        Bitmap bitmap;
        int uses;
        Raster(String key, Bitmap bitmap) { this.key = key; this.bitmap = bitmap; }
    }
    private static final class Glyph {
        int from, to, mode;
        String expected;
        TextPaint paint;
        Raster raster;
        float intensity;
        long start, duration;
    }
    private static final class Particle {
        int style;
        String text;
        Raster raster;
        float x, y, vx, vy, gravity, size, rotationSpeed, seed, scale, scrollX, scrollY, topDelta;
        long start, duration;
    }
    private static final class PendingBatch {
        int start, end, mode, attempts;
        long deadline;
        String text;
    }
    private static final class State implements Runnable, View.OnAttachStateChangeListener, android.view.ViewTreeObserver.OnPreDrawListener {
        final WeakReference<EditText> ref;
        final ArrayList<Glyph> glyphs = new ArrayList<>();
        final ArrayList<Particle> particles = new ArrayList<>();
        final ArrayList<Raster> rasterCache = new ArrayList<>();
        final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final Random random = new Random();
        int cacheBytes;
        final String[] removedClusters = new String[MAX_DELETE_CLUSTERS];
        final int[] removedOffsets = new int[MAX_DELETE_CLUSTERS];
        final float[] removedX = new float[MAX_DELETE_CLUSTERS], removedBaseline = new float[MAX_DELETE_CLUSTERS];
        final float[] removedWidth = new float[MAX_DELETE_CLUSTERS], removedDirection = new float[MAX_DELETE_CLUSTERS];
        final Raster[] removedRasters = new Raster[MAX_DELETE_CLUSTERS];
        int removedCount, removedStart, removedUnits;
        int removedScrollX, removedScrollY;
        final float[] removedTop = new float[MAX_DELETE_CLUSTERS];
        String replacedText;
        int replacedStart;
        final ArrayList<PendingBatch> pendingBatches = new ArrayList<>();
        android.view.ViewTreeObserver pendingObserver;
        boolean pendingPosted, scheduled, nativeHidden;
        int textColor;
        float textSize;
        int lastSelStart = -1, lastSelEnd = -1, cursorEvent;
        float cursorX = Float.NaN, cursorY, targetX, targetY, targetTop, targetBottom;
        boolean targetValid;
        long lastFrame, cursorEventTime, forceVisibleUntil;
        final Runnable applyRunnable = this::applyPending;

        State(EditText edit) {
            ref = new WeakReference<>(edit);
            textColor = edit.getCurrentTextColor(); textSize = edit.getTextSize();
            warmSettings();
            edit.addOnAttachStateChangeListener(this);
        }
        void warmSettings() {
            // Прогреваем ленивые boxed-кэши вне отрисовки.
            PengramConfig.getInputAnimationSpeed(); PengramConfig.getInputAnimationIntensity();
            PengramConfig.isTypingBlur(); PengramConfig.getTypingBlurRadius();
            PengramConfig.isTypingIgnoreSpaces(); PengramConfig.isTypingAnimateAllLines();
            PengramConfig.isTypingDeleteAnim(); PengramConfig.getTypingDeleteStyle();
            PengramConfig.getTypingDeleteCount(); PengramConfig.getTypingDeleteSpeed();
            PengramConfig.getTypingDeleteSpread(); PengramConfig.getTypingDeleteSize();
            PengramConfig.isTypingCursorSmooth(); PengramConfig.getTypingCursorSpeed();
            PengramConfig.getTypingCursorWidth(); PengramConfig.isTypingCursorLiquid();
            PengramConfig.getTypingCursorLiquidScale(); PengramConfig.isTypingSelectionLiquid();
        }
        void syncNativeCursor(EditText edit, boolean active) {
            if (nativeHidden != active && edit instanceof EditTextBoldCursor) {
                nativeHidden = active;
                ((EditTextBoldCursor) edit).setAllowDrawCursor(!active);
            }
        }
        @Override public void onViewAttachedToWindow(View v) { }
        @Override public void onViewDetachedFromWindow(View v) { clear((EditText) v); }
        void onTextChanged() {
            long now = SystemClock.uptimeMillis();
            forceVisibleUntil = now + 530;
            EditText edit = ref.get();
            if (edit != null) { edit.removeCallbacks(this); scheduled = false; edit.invalidate(); scheduleAfter(0); }
        }
        void noteCursorEvent(int event) { cursorEvent = event; cursorEventTime = SystemClock.uptimeMillis(); }
        void invalidateEditedRange(int start, int before, int count) {
            final int oldEnd = start + before;
            final int delta = count - before;
            for (int i = glyphs.size() - 1; i >= 0; i--) {
                Glyph glyph = glyphs.get(i);
                if (glyph.to <= start) continue;
                if (glyph.from >= oldEnd) { glyph.from += delta; glyph.to += delta; }
                else removeGlyph(i);
            }
            for (int i = pendingBatches.size() - 1; i >= 0; i--) {
                PendingBatch batch = pendingBatches.get(i);
                if (batch.end <= start) continue;
                if (batch.start >= oldEnd) { batch.start += delta; batch.end += delta; }
                else pendingBatches.remove(i);
            }
        }
        void enqueue(int start, int end, int mode) {
            EditText edit = ref.get();
            if (edit == null) return;
            if (pendingBatches.size() == 16) pendingBatches.remove(0);
            PendingBatch batch = new PendingBatch();
            batch.start = start; batch.end = end; batch.mode = mode;
            batch.text = edit.getText().subSequence(start, end).toString();
            batch.deadline = SystemClock.uptimeMillis() + 400;
            pendingBatches.add(batch);
            if (!pendingPosted) {
                pendingPosted = true;
                pendingObserver = edit.getViewTreeObserver();
                pendingObserver.addOnPreDrawListener(this);
                // Почему: post() может исполниться раньше пересборки Layout.
                edit.postDelayed(applyRunnable, 400);
                edit.invalidate();
            }
        }
        @Override public boolean onPreDraw() {
            applyPending();
            return true;
        }
        void stopPendingObserver() {
            EditText edit = ref.get();
            if (edit != null) edit.removeCallbacks(applyRunnable);
            if (pendingObserver != null && pendingObserver.isAlive()) pendingObserver.removeOnPreDrawListener(this);
            pendingObserver = null;
            pendingPosted = false;
        }
        void applyPending() {
            try { applyPendingSafe(); }
            catch (RuntimeException | OutOfMemoryError unavailable) { clearGlyphs(); }
        }
        void applyPendingSafe() {
            EditText edit = ref.get();
            if (!live(edit)) { pendingBatches.clear(); stopPendingObserver(); return; }
            final long now = SystemClock.uptimeMillis();
            final Layout layout = edit.getLayout();
            for (int i = 0; i < pendingBatches.size();) {
                PendingBatch batch = pendingBatches.get(i);
                if (!matches(edit.getText(), batch.start, batch.end, batch.text)) {
                    pendingBatches.remove(i);
                    continue;
                }
                final boolean ready = validLayout(edit, layout)
                        && matches(layout.getText(), batch.start, batch.end, batch.text);
                if (!ready && ++batch.attempts <= 3 && now < batch.deadline) {
                    edit.postInvalidateOnAnimation();
                    i++;
                    continue;
                }
                pendingBatches.remove(i);
                if (ready) applyBatch(edit, layout, batch);
                if (org.telegram.messenger.BuildVars.LOGS_ENABLED) org.telegram.messenger.FileLog.d(
                        "Pengram typing applied=" + ready + " attempts=" + batch.attempts
                        + " start=" + batch.start + " length=" + batch.text.length());
            }
            if (pendingBatches.isEmpty()) stopPendingObserver();
        }
        void applyBatch(EditText edit, Layout layout, PendingBatch batch) {
            final int start = batch.start, end = batch.end, mode = batch.mode;
            Editable text = edit.getText();
            long now = SystemClock.uptimeMillis();
            long duration = PengramConfig.getInputAnimationSpeed() == 0 ? 150 : PengramConfig.getInputAnimationSpeed() == 1 ? 210 : 320;
            int cursorLine = layout.getLineForOffset(Math.max(0, Math.min(edit.getSelectionEnd(), text.length())));
            // Не обрываем старые следы при заполненном бюджете: пропускаем новый.
            for (int from = start, n = 0; from < end && n < MAX_PER_BATCH && glyphs.size() < MAX_ACTIVE; n++) {
                int to = PengramGraphemes.clusterEnd(text, from);
                if (to > end) break;
                String cluster = text.subSequence(from, to).toString();
                if (cluster.indexOf('\n') < 0 && cluster.indexOf('\r') < 0 && (!PengramConfig.isTypingIgnoreSpaces() || !cluster.trim().isEmpty())
                        && (PengramConfig.isTypingAnimateAllLines() || layout.getLineForOffset(from) == cursorLine)) {
                    Glyph g = new Glyph();
                    g.from = from; g.to = to; g.expected = cluster; g.mode = mode;
                    g.paint = new TextPaint(edit.getPaint());
                    for (CharacterStyle style : text.getSpans(from, to, CharacterStyle.class)) style.updateDrawState(g.paint);
                    g.start = now; g.duration = duration; g.intensity = PengramConfig.getInputAnimationIntensity() * .4f;
                    if (glyphRect(edit, layout, from, to)) {
                        g.raster = raster(edit, layout, from, to, cluster);
                        if (g.raster != null) { g.raster.uses++; glyphs.add(g); }
                    }
                }
                from = to;
            }
            if (!glyphs.isEmpty()) { scheduleAfter(0); edit.invalidate(); }
        }
        boolean matches(CharSequence text, int from, int to, String expected) {
            if (from < 0 || to > text.length() || to - from != expected.length()) return false;
            for (int i = 0; i < expected.length(); i++) if (text.charAt(from + i) != expected.charAt(i)) return false;
            return true;
        }
        boolean validLayout(EditText edit, Layout layout) {
            return layout != null && layout.getText() != null && layout.getText().length() == edit.length()
                    && layout.getLineCount() > 0 && layout.getLineEnd(layout.getLineCount() - 1) == edit.length();
        }
        /** Canvas onDraw уже сдвинут View на -scroll: храним content coordinates.
         * При bidi используем фактический run, не направление локали интерфейса. */
        boolean glyphRect(EditText edit, Layout layout, int start, int end) {
            if (!validLayout(edit, layout) || start < 0 || end > edit.length() || end <= start) return false;
            int line = layout.getLineForOffset(start);
            if (layout.getLineForOffset(end - 1) != line) return false;
            float x = layout.getPrimaryHorizontal(start), next = layout.getPrimaryHorizontal(end);
            boolean rtl = layout.isRtlCharAt(start);
            // В конце bidi-run primary(end) может принадлежать соседнему run.
            float secondary = layout.getSecondaryHorizontal(end);
            if (rtl && next > x || !rtl && next < x) next = secondary;
            rect.set(Math.min(x, next) + edit.getCompoundPaddingLeft(),
                    layout.getLineTop(line) + edit.getTotalPaddingTop(),
                    Math.max(x, next) + edit.getCompoundPaddingLeft(),
                    layout.getLineBottom(line) + edit.getTotalPaddingTop());
            return rect.width() > 0 && rect.width() < edit.getWidth();
        }
        void snapshot(CharSequence text, int start, int count) {
            releaseSnapshot();
            EditText edit = ref.get();
            if (edit == null || start < 0 || count > text.length() - start
                    || PengramGraphemes.clusterStart(text, start) != start
                    || PengramGraphemes.clusterStart(text, start + count) != start + count) return;
            Layout layout = edit.getLayout();
            if (!validLayout(edit, layout)) return;
            removedStart = start; removedUnits = count;
            removedScrollX = edit.getScrollX(); removedScrollY = edit.getScrollY();
            // Почему: IME заменяет и длинное слово целиком, а стирает обычно
            // кластер перед курсором. Бюджет снимка тратим возле курсора, не в начале слова.
            int cursor = Math.min(start + count, Math.max(start, edit.getSelectionStart()));
            int boundaryCount = 0;
            // Один прямой проход, без 24 повторных сканирований всей строки.
            // removedOffsets пока свободен после releaseSnapshot: используем его как ring buffer.
            for (int offset = start; offset < cursor; offset = PengramGraphemes.clusterEnd(text, offset)) {
                removedOffsets[boundaryCount % MAX_DELETE_CLUSTERS] = offset;
                boundaryCount++;
            }
            int captureStart = boundaryCount >= MAX_DELETE_CLUSTERS
                    ? removedOffsets[boundaryCount % MAX_DELETE_CLUSTERS] : start;
            for (int offset = captureStart; offset < start + count && removedCount < MAX_DELETE_CLUSTERS;) {
                int end = PengramGraphemes.clusterEnd(text, offset);
                if (end > start + count) break;
                String cluster = text.subSequence(offset, end).toString();
                if (!multiword(cluster, 0, cluster.length()) && glyphRect(edit, layout, offset, end)) {
                    int n = removedCount++;
                    int line = layout.getLineForOffset(offset);
                    int paddingTop = edit.getTotalPaddingTop();
                    removedClusters[n] = cluster;
                    removedOffsets[n] = offset;
                    removedTop[n] = rect.top;
                    removedX[n] = rect.left;
                    removedBaseline[n] = layout.getLineBaseline(line) + paddingTop;
                    removedWidth[n] = rect.width();
                    removedDirection[n] = layout.isRtlCharAt(offset) ? -1 : 1;
                }
                offset = end;
            }
            rasterizeSnapshot(edit, layout, cursor);
        }
        void rasterizeSnapshot(EditText edit, Layout layout, int cursor) {
            if (PengramConfig.getTypingDeleteStyle() != PengramConfig.DELETE_PARTICLE_LETTERS) return;
            // Почему: бюджет cache32 мог занять неизменённый префикс snapshot24,
            // оставив реальной удаляемой букве только fallback-пыль. Начинаем у курсора.
            int pivot = 0;
            while (pivot + 1 < removedCount && removedOffsets[pivot + 1] < cursor) pivot++;
            for (int n = 0; n < removedCount; n++) {
                int i = n <= pivot ? pivot - n : n;
                int start = removedOffsets[i];
                int end = start + removedClusters[i].length();
                if (!glyphRect(edit, layout, start, end)) continue;
                removedRasters[i] = raster(edit, layout, start, end, removedClusters[i]);
                if (removedRasters[i] != null) removedRasters[i].uses++;
            }
        }
        boolean hasGlyphs(TextPaint font, String text) {
            for (int from = 0; from < text.length();) {
                int end = PengramGraphemes.clusterEnd(text, from);
                if (!font.hasGlyph(text.substring(from, end))) return false;
                from = end;
            }
            return true;
        }
        boolean safeGlyphs(EditText edit, TextPaint font, int start, int end, String cluster) {
            ReplacementSpan[] spans = edit.getText().getSpans(start, end, ReplacementSpan.class);
            if (spans.length == 0) return hasGlyphs(font, cluster);
            // Картинка EmojiSpan — тоже проверенный глиф, даже если системная
            // гарнитура старого Android не знает этот Unicode. Незагруженный
            // drawable и произвольные custom spans в bitmap не переносим.
            if (spans.length != 1 || !(spans[0] instanceof org.telegram.messenger.Emoji.EmojiSpan)
                    || edit.getText().getSpanStart(spans[0]) != start || edit.getText().getSpanEnd(spans[0]) != end) return false;
            android.graphics.drawable.Drawable drawable = ((org.telegram.messenger.Emoji.EmojiSpan) spans[0]).getDrawable();
            return drawable instanceof org.telegram.messenger.Emoji.EmojiDrawable
                    && ((org.telegram.messenger.Emoji.EmojiDrawable) drawable).isLoaded();
        }
        Raster raster(EditText edit, Layout layout, int start, int end, String cluster) {
            // hasGlyph проверяется при событии, не в кадре. Неподдерживаемый
            // кластер становится пылью: вопросительный знак никогда не синтезируем.
            paint.set(edit.getPaint());
            for (CharacterStyle style : edit.getText().getSpans(start, end, CharacterStyle.class)) style.updateDrawState(paint);
            if (!safeGlyphs(edit, paint, start, end, cluster) || rect.width() > 1024 || rect.height() > 256) return null;
            String context = edit.getText().subSequence(Math.max(0, start - 2), Math.min(edit.length(), end + 2)).toString();
            String key = cluster + ':' + context + ':' + paint.getTextSize() + ':' + paint.getColor()
                    + ':' + paint.getTypeface() + ':' + paint.getTextScaleX() + ':' + paint.getTextSkewX()
                    + ':' + paint.isFakeBoldText() + ':' + rect.width() + ':' + rect.height();
            for (int i = 0; i < rasterCache.size(); i++) {
                Raster cached = rasterCache.get(i);
                if (cached.key.equals(key)) { rasterCache.remove(i); rasterCache.add(cached); return cached; }
            }
            final int bytes = Math.max(1, (int) Math.ceil(rect.width())) * Math.max(1, (int) Math.ceil(rect.height())) * 4;
            while (rasterCache.size() >= 32 || cacheBytes + bytes > 2 * 1024 * 1024) {
                int unused = -1;
                for (int i = 0; i < rasterCache.size(); i++) if (rasterCache.get(i).uses == 0) { unused = i; break; }
                if (unused < 0) return null;
                Raster evicted = rasterCache.remove(unused);
                cacheBytes -= evicted.bitmap.getAllocationByteCount();
                // Bitmap уже мог уйти в аппаратный display list: его пиксели
                // освобождает Android после последней GPU-ссылки, не recycle().
            }
            try {
                Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(rect.width())),
                        Math.max(1, (int) Math.ceil(rect.height())), Bitmap.Config.ARGB_8888);
                Canvas capture = new Canvas(bitmap);
                capture.translate(-rect.left + edit.getCompoundPaddingLeft(), -rect.top + edit.getTotalPaddingTop());
                // Layout сохраняет контекст shaping и ReplacementSpan; clip не
                // даёт захватить соседние буквы. Editable дальше может мутировать.
                layout.draw(capture);
                Raster value = new Raster(key, bitmap);
                rasterCache.add(value);
                cacheBytes += bitmap.getAllocationByteCount();
                return value;
            } catch (OutOfMemoryError | RuntimeException unavailable) { return null; }
        }
        void releaseSnapshot() {
            for (int i = 0; i < MAX_DELETE_CLUSTERS; i++) {
                if (removedRasters[i] != null) removedRasters[i].uses--;
                removedRasters[i] = null; removedClusters[i] = null;
            }
            removedCount = 0;
        }
        void spawnFromSnapshot(int start, int before) {
            EditText edit = ref.get();
            int perCluster = PengramConfig.getTypingDeleteCount();
            if (start < removedStart || before > removedUnits || start - removedStart > removedUnits - before
                    || edit == null || perCluster == 0) { releaseSnapshot(); return; }
            float speed = .2f + PengramConfig.getTypingDeleteSpeed() / 50f;
            float spread = PengramConfig.getTypingDeleteSpread() / 50f;
            float size = PengramConfig.getTypingDeleteSize() / 50f;
            int style = PengramConfig.getTypingDeleteStyle();
            long now = SystemClock.uptimeMillis();
            // Плотность LETTERS ограничивает число целых глифов, а не дробит
            // один эмодзи на несколько наложенных копий.
            final int total = style == PengramConfig.DELETE_PARTICLE_LETTERS
                    ? Math.min(removedCount, perCluster * 2) : removedCount;
            int emitted = 0;
            for (int i = 0; i < removedCount && emitted < total && particles.size() < MAX_PARTICLES; i++) {
                // Почему: prefix/suffix нормализация IME оставляет только удалённый диапазон.
                if (removedOffsets[i] < start || removedOffsets[i] + removedClusters[i].length() > start + before) continue;
                emitted++;
                // Буква/эмодзи остаётся единым глифом. count=0 выключает и его.
                int pieces = style == PengramConfig.DELETE_PARTICLE_LETTERS ? 1 : perCluster;
                for (int j = 0; j < pieces && particles.size() < MAX_PARTICLES; j++) {
                    Particle p = new Particle();
                    p.style = style; p.text = removedClusters[i]; p.raster = removedRasters[i];
                    if (p.style == PengramConfig.DELETE_PARTICLE_LETTERS && p.raster == null) p.style = PengramConfig.DELETE_PARTICLE_DUST;
                    if (p.raster != null) p.raster.uses++;
                    p.x = removedX[i]; p.y = removedBaseline[i];
                    p.scrollX = removedScrollX; p.scrollY = removedScrollY;
                    p.topDelta = removedTop[i] - removedBaseline[i];
                    float em = edit.getTextSize();
                    if (p.style != PengramConfig.DELETE_PARTICLE_LETTERS) {
                        p.x += removedWidth[i] * .5f;
                        p.y -= em * .35f;
                    }
                    p.vx = (random.nextFloat() - .65f) * em * spread * removedDirection[i] * speed;
                    p.vy = (random.nextFloat() - .7f) * em * speed;
                    if (style == PengramConfig.DELETE_PARTICLE_SNOW) p.vy = em * (.08f + random.nextFloat() * .12f) * speed;
                    else if (style == PengramConfig.DELETE_PARTICLE_PETALS) p.vy = em * (.1f + random.nextFloat() * .2f) * speed;
                    else if (style == PengramConfig.DELETE_PARTICLE_SPARKS) { p.vx *= 1.6f; p.vy *= 1.6f; }
                    p.gravity = em * (style == PengramConfig.DELETE_PARTICLE_SNOW ? .15f : .65f);
                    p.size = Math.max(.5f, em * .06f * size);
                    p.scale = Math.max(.2f, Math.min(2.4f, size));
                    if (p.raster != null) p.scale *= removedWidth[i] / Math.max(1, p.raster.bitmap.getWidth());
                    p.rotationSpeed = (random.nextFloat() - .5f) * 100f;
                    p.seed = random.nextFloat() * 6f;
                    p.start = now; p.duration = (long) (PARTICLE_LIFE / Math.max(.4f, speed));
                    particles.add(p);
                }
            }
            releaseSnapshot();
            scheduleAfter(0); edit.invalidate();
        }
        void removeGlyph(int i) {
            Glyph g = glyphs.remove(i);
            if (g.raster != null) g.raster.uses--;
        }
        void removeParticle(int i) {
            Particle p = particles.remove(i);
            if (p.raster != null) p.raster.uses--;
        }
        void clearGlyphs() {
            EditText edit = ref.get();
            stopPendingObserver();
            pendingBatches.clear();
            for (int i = glyphs.size() - 1; i >= 0; i--) removeGlyph(i);
        }
        void clearAll() {
            EditText edit = ref.get();
            if (edit != null) { edit.removeCallbacks(this); syncNativeCursor(edit, false); }
            scheduled = false; clearGlyphs(); releaseSnapshot(); replacedText = null;
            for (int i = particles.size() - 1; i >= 0; i--) removeParticle(i);
            rasterCache.clear(); cacheBytes = 0; cursorX = Float.NaN; lastFrame = 0; targetValid = false; cursorEvent = CURSOR_EVENT_NONE;
        }
        void scheduleAfter(long delay) {
            EditText edit = ref.get();
            if (scheduled || !live(edit)) return;
            scheduled = true;
            if (delay == 0) edit.postOnAnimation(this);
            else edit.postOnAnimationDelayed(this, delay);
        }
        boolean cursorActive() {
            EditText edit = ref.get();
            return live(edit) && edit instanceof EditTextBoldCursor && PengramConfig.isTypingCursorSmooth() && edit.isCursorVisible() && !composing(edit);
        }
        @Override public void run() {
            scheduled = false;
            EditText edit = ref.get();
            if (!live(edit)) { clearAll(); return; }
            long now = SystemClock.uptimeMillis();
            for (int i = glyphs.size() - 1; i >= 0; i--) if (now >= glyphs.get(i).start + glyphs.get(i).duration) removeGlyph(i);
            for (int i = particles.size() - 1; i >= 0; i--) if (now >= particles.get(i).start + particles.get(i).duration) removeParticle(i);
            edit.invalidate();
            // Следующий кадр выбирает draw: в покое остаётся только редкий blink,
            // без полного FPS. При выделении даже blink не нужен.
        }
        void computeTarget(EditText edit) {
            targetValid = false;
            Layout layout = edit.getLayout();
            int offset = edit.getSelectionEnd();
            if (!validLayout(edit, layout) || offset < 0 || offset > edit.length()) return;
            int line = layout.getLineForOffset(offset);
            final int paddingTop = edit.getTotalPaddingTop();
            targetX = layout.getPrimaryHorizontal(offset) + edit.getCompoundPaddingLeft();
            targetY = layout.getLineBaseline(line) + paddingTop;
            targetTop = layout.getLineTop(line) + paddingTop;
            targetBottom = layout.getLineBottom(line) + paddingTop;
            targetValid = targetBottom > edit.getScrollY() && targetTop < edit.getScrollY() + edit.getHeight();
        }
        void advanceCursor(EditText edit, long now) {
            computeTarget(edit);
            if (!targetValid) return;
            int a = edit.getSelectionStart(), b = edit.getSelectionEnd();
            if (a != lastSelStart || b != lastSelEnd) { lastSelStart = a; lastSelEnd = b; forceVisibleUntil = now + 530; }
            float dt = lastFrame == 0 ? 16 : Math.min(64, Math.max(0, now - lastFrame));
            lastFrame = now;
            if (Float.isNaN(cursorX)) { cursorX = targetX; cursorY = targetY; }
            float ease = 1f - (float) Math.exp(-dt / (18f + (60 - PengramConfig.getTypingCursorSpeed()) * 1.6f));
            if (Math.abs(targetY - cursorY) > edit.getTextSize() * .5f && cursorEvent == CURSOR_EVENT_NONE) noteCursorEvent(CURSOR_EVENT_DIVE);
            cursorX += (targetX - cursorX) * ease; cursorY += (targetY - cursorY) * ease;
            if (Math.abs(targetX - cursorX) < .3f) cursorX = targetX;
            if (Math.abs(targetY - cursorY) < .3f) cursorY = targetY;
            if (now - cursorEventTime >= 200) cursorEvent = CURSOR_EVENT_NONE;
        }
        void draw(Canvas canvas) {
            EditText edit = ref.get();
            if (!live(edit)) { clearAll(); return; }
            long now = SystemClock.uptimeMillis();
            int save = canvas.save();
            int scrollX = edit.getScrollX(), scrollY = edit.getScrollY();
            // Не вычитаем scroll второй раз: Canvas уже в content coordinates.
            canvas.clipRect(edit.getCompoundPaddingLeft() + scrollX, scrollY,
                    edit.getWidth() - edit.getCompoundPaddingRight() + scrollX, edit.getHeight() + scrollY);
            Layout layout = edit.getLayout();
            if (validLayout(edit, layout)) drawGlyphs(canvas, edit, edit.getText(), layout, now);
            drawParticles(canvas, edit, now);
            boolean active = cursorActive();
            if (active) advanceCursor(edit, now);
            boolean drawn = active && drawCursor(canvas, edit, now);
            syncNativeCursor(edit, drawn);
            canvas.restoreToCount(save);
            boolean moving = drawn && (Math.abs(targetX - cursorX) > .3f || Math.abs(targetY - cursorY) > .3f || cursorEvent != CURSOR_EVENT_NONE);
            if (!glyphs.isEmpty() || !particles.isEmpty() || moving) scheduleAfter(0);
            else if (drawn && lastSelStart == lastSelEnd) {
                long elapsed = Math.max(0, now - forceVisibleUntil);
                scheduleAfter(now < forceVisibleUntil ? forceVisibleUntil - now : 530 - elapsed % 530);
            }
        }
        void drawGlyphs(Canvas canvas, EditText edit, Editable text, Layout layout, long now) {
            for (int i = 0; i < glyphs.size(); i++) {
                Glyph g = glyphs.get(i);
                if (!matches(text, g.from, g.to, g.expected) || !glyphRect(edit, layout, g.from, g.to)) continue;
                float t = Math.min(1f, Math.max(0f, (now - g.start) / (float) g.duration));
                if (t >= 1) continue;
                float tail = 1 - t, scale = 1, dx = 0, dy = 0;
                float em = g.paint.getTextSize(), intensity = g.intensity;
                switch (g.mode) {
                    case PengramConfig.INPUT_ANIM_NONE: continue;
                    case PengramConfig.INPUT_ANIM_FADE: break;
                    case PengramConfig.INPUT_ANIM_POP: scale = 1f - .3f * tail * intensity + .16f * (float) Math.sin(t * Math.PI) * intensity; break;
                    case PengramConfig.INPUT_ANIM_SLIDE: dx = tail * em * .3f * intensity * (layout.isRtlCharAt(g.from) ? 1 : -1); break;
                    case PengramConfig.INPUT_ANIM_RISE: dy = tail * em * .35f * intensity; break;
                    case PengramConfig.INPUT_ANIM_BOUNCE: dy = -(float) Math.sin(t * Math.PI) * tail * em * .3f * intensity; break;
                    case PengramConfig.INPUT_ANIM_SHAKE: dx = (float) Math.sin(t * Math.PI * 6) * tail * Math.min(dp(1.5f), em * .045f * intensity); break;
                }
                paint.set(g.paint); paint.setMaskFilter(null); paint.setFilterBitmap(true);
                paint.setAlpha(Math.round(100 * tail * tail * Math.min(1f, intensity)));
                int save = canvas.save();
                canvas.translate(dx, dy); canvas.scale(scale, scale, rect.centerX(), (rect.top + rect.bottom) * .5f);
                // Аппаратный BlurMaskFilter не работает. Два дешёвых прозрачных
                // отпечатка дают растворение, не переводя весь EditText в software.
                if (PengramConfig.isTypingBlur() && PengramConfig.getTypingBlurRadius() > 0) {
                    float halo = Math.min(em * .15f, PengramConfig.getTypingBlurRadius()) * tail;
                    paint.setAlpha(Math.round(18 * tail));
                    canvas.drawBitmap(g.raster.bitmap, rect.left - halo, rect.top, paint);
                    canvas.drawBitmap(g.raster.bitmap, rect.left + halo, rect.top, paint);
                    paint.setAlpha(Math.round(70 * tail * tail * Math.min(1f, intensity)));
                }
                canvas.drawBitmap(g.raster.bitmap, rect.left, rect.top, paint);
                canvas.restoreToCount(save);
            }
        }
        void drawParticles(Canvas canvas, EditText edit, long now) {
            int color = edit.getCurrentTextColor();
            for (int i = 0; i < particles.size(); i++) {
                Particle p = particles.get(i);
                float t = Math.max(0, Math.min(1f, (now - p.start) / (float) p.duration));
                if (t >= 1) continue;
                float seconds = (now - p.start) / 1000f;
                float px = p.x + edit.getScrollX() - p.scrollX + p.vx * seconds;
                float py = p.y + edit.getScrollY() - p.scrollY + p.vy * seconds + p.gravity * seconds * seconds;
                shapePaint.setColor(color); shapePaint.setAlpha(Math.round(180 * (1 - t) * (1 - t)));
                float r = p.size;
                switch (p.style) {
                    case PengramConfig.DELETE_PARTICLE_SPARKS:
                        shapePaint.setStrokeWidth(Math.max(.5f, r * .4f));
                        canvas.drawLine(px, py, px - p.vx * .04f, py - p.vy * .04f, shapePaint); break;
                    case PengramConfig.DELETE_PARTICLE_SNOW:
                        canvas.drawCircle(px + (float) Math.sin(seconds * 3 + p.seed) * r, py + seconds * r * 4, r, shapePaint); break;
                    case PengramConfig.DELETE_PARTICLE_PETALS: {
                        int save = canvas.save();
                        canvas.rotate(seconds * p.rotationSpeed, px, py);
                        canvas.drawOval(px - r, py - r * 1.6f, px + r, py + r * 1.6f, shapePaint);
                        canvas.restoreToCount(save); break;
                    }
                    case PengramConfig.DELETE_PARTICLE_LETTERS: {
                        if (p.raster == null) break;
                        int save = canvas.save();
                        Bitmap bitmap = p.raster.bitmap;
                        float centerY = py + p.topDelta + bitmap.getHeight() * .5f;
                        canvas.rotate(seconds * p.rotationSpeed * .3f, px + bitmap.getWidth() * .5f, centerY);
                        canvas.scale(p.scale, p.scale, px + bitmap.getWidth() * .5f, centerY);
                        canvas.drawBitmap(bitmap, px, py + p.topDelta, shapePaint);
                        canvas.restoreToCount(save); break;
                    }
                    case PengramConfig.DELETE_PARTICLE_DUST:
                    default: canvas.drawCircle(px, py, r, shapePaint); break;
                }
            }
        }
        boolean drawCursor(Canvas canvas, EditText edit, long now) {
            if (!targetValid) return false;
            int color = Theme.getColor(Theme.key_chat_messagePanelCursor);
            if (lastSelStart != lastSelEnd) {
                if (PengramConfig.isTypingSelectionLiquid()) {
                    drawSelectionEdge(canvas, edit, lastSelStart, color);
                    drawSelectionEdge(canvas, edit, lastSelEnd, color);
                }
                return false;
            }
            if (now >= forceVisibleUntil && (now - forceVisibleUntil) / 530 % 2 == 0) return true;
            float width = dp(PengramConfig.getTypingCursorWidth());
            float height = targetBottom - targetTop, top = cursorY - (targetY - targetTop);
            float dx = targetX - cursorX;
            if (PengramConfig.isTypingCursorLiquid()) {
                float stretch = Math.min(edit.getTextSize() * .7f, Math.abs(dx)) * PengramConfig.getTypingCursorLiquidScale() / 40f;
                width /= 1f + stretch / Math.max(1f, height);
                cursorPaint.setColor(color); cursorPaint.setAlpha(100);
                canvas.drawRoundRect(Math.min(cursorX, cursorX + Math.signum(dx) * stretch) - width * .5f, top,
                        Math.max(cursorX, cursorX + Math.signum(dx) * stretch) + width * .5f, top + height, width, width, cursorPaint);
            }
            float k = Math.max(0f, 1f - (now - cursorEventTime) / 200f);
            if (cursorEvent == CURSOR_EVENT_PULSE) width *= 1f + .5f * k;
            else if (cursorEvent == CURSOR_EVENT_DIVE) top += edit.getTextSize() * .12f * k;
            else if (cursorEvent == CURSOR_EVENT_JUMP) top -= edit.getTextSize() * .12f * k;
            cursorPaint.setColor(color); cursorPaint.setAlpha(255);
            canvas.drawRoundRect(cursorX - width * .5f, top, cursorX + width * .5f, top + height, width * .5f, width * .5f, cursorPaint);
            return true;
        }
        void drawSelectionEdge(Canvas canvas, EditText edit, int offset, int color) {
            Layout layout = edit.getLayout();
            if (!validLayout(edit, layout) || offset < 0 || offset > edit.length()) return;
            int line = layout.getLineForOffset(offset);
            float x = layout.getPrimaryHorizontal(offset) + edit.getCompoundPaddingLeft();
            cursorPaint.setColor(color); cursorPaint.setAlpha(100);
            canvas.drawRoundRect(x - 1, layout.getLineTop(line) + edit.getTotalPaddingTop(),
                    x + 1, layout.getLineBottom(line) + edit.getTotalPaddingTop(), 1, 1, cursorPaint);
        }
    }
}
