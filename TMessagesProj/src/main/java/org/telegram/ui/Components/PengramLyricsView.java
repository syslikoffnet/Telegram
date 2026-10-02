package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramLyrics;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

/**
 * Pengram: текст песни с побуквенными анимациями.
 * Умеет и караоке по таймкодам LRC, и обычный текст — тогда строки распределяются
 * по длительности трека. Стиль анимации, размер, выравнивание и скорость берутся
 * из настроек Pengram и применяются на лету.
 */
public class PengramLyricsView extends View {

    public interface SeekCallback {
        void seekTo(float progress);
    }

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<PengramLyrics.Line> lines = new ArrayList<>();
    private final ArrayList<StaticLayout> layouts = new ArrayList<>();
    private final ArrayList<Integer> tops = new ArrayList<>();
    private final float[] widths = new float[256];

    private boolean timed;
    private long durationMs;
    private float progress;
    private int activeLine = -1;
    private float scrollY;
    private float targetScrollY;
    private long lastFrame;
    private float enterAnim;

    private int accentColor = 0xFFFFFFFF;
    private int baseColor = 0xFFFFFFFF;
    private SeekCallback seekCallback;
    private int builtWidth;
    private int previewAnim = -1;
    private boolean previewMode;
    private int previewSize;
    private int builtSize;
    private boolean builtBold;
    private boolean tickerMode;
    private int animOverride = -1;
    private String offsetKey;
    private long lyricsLength;
    private float timeScale = 1f;
    private long progressMs;
    private long progressTime;
    private float tickerScrollX;
    private float tickerTargetX;
    private float[] charFractions;
    private int fractionsLine = -1;
    private int builtAlign;

    // --- всё ниже нужно, чтобы кадр стоил как можно дешевле ---
    /** снимок настроек: читаем их не по сто раз за кадр, а раз в 400 мс */
    private long configTime;
    private int cfgDim = 35;
    private int cfgSpeed = 100;
    private int cfgAnim;
    private boolean cfgShadow = true;
    private boolean cfgAutoscroll = true;
    private boolean cfgWords = true;
    private int cfgTickerSpeed = 100;
    /** ширины символов каждой строки считаются один раз на разметку */
    private float[][] lineWidths;
    private final float[] hsv = new float[3];
    private float lastDrawnCut = -1f;
    private int lastDrawnLine = -2;

    public PengramLyricsView(Context context) {
        super(context);
        textPaint.setTextSize(AndroidUtilities.dp(PengramConfig.getLyricsSize()));
    }

    /** витрина анимации: одна строка, которая бесконечно проигрывается заново */
    public void setPreview(String text, int anim, int textSize) {
        previewMode = true;
        previewAnim = anim;
        previewSize = textSize;
        timed = false;
        durationMs = 4000;
        lines.clear();
        lines.add(new PengramLyrics.Line(-1, text));
        activeLine = -1;
        enterAnim = 0;
        builtWidth = 0;
        buildLayouts();
        invalidate();
    }

    /**
     * Режим «одна строка»: для шапки чата. Рисуем только ту строку, что звучит сейчас,
     * по центру и без прокрутки, нажатия не перехватываем — ими занимается сама панель.
     */
    public void setTickerMode(boolean ticker, int textSize, int anim) {
        tickerMode = ticker;
        previewSize = textSize;
        animOverride = anim;
        builtWidth = 0;
        buildLayouts();
        invalidate();
    }

    public void setColors(int accent, int base) {
        accentColor = accent;
        baseColor = base;
        invalidate();
    }

    public void setSeekCallback(SeekCallback callback) {
        seekCallback = callback;
    }

    public void setLyrics(String raw, long durationMs) {
        lines.clear();
        lines.addAll(PengramLyrics.parse(raw));
        timed = PengramLyrics.hasTimings(lines);
        this.durationMs = durationMs;
        lyricsLength = PengramLyrics.lengthOf(raw);
        // ускоренная или замедленная версия трека: подгоняем таймкоды под реальную длину
        timeScale = 1f;
        if (PengramConfig.isLyricsStretch() && lyricsLength > 30000 && durationMs > 30000) {
            final float ratio = durationMs / (float) lyricsLength;
            if (ratio > 1.02f || ratio < 0.98f) {
                timeScale = Math.max(0.5f, Math.min(2f, ratio));
            }
        }
        activeLine = -1;
        scrollY = targetScrollY = 0;
        tickerScrollX = tickerTargetX = 0;
        enterAnim = 0;
        progressMs = 0;
        progressTime = 0;
        builtWidth = 0;
        buildLayouts();
        invalidate();
    }

    /** мгновенно забыть предыдущую песню — чтобы старый текст не «бежал» поверх новой */
    public void clear() {
        lines.clear();
        layouts.clear();
        tops.clear();
        timed = false;
        activeLine = -1;
        progress = 0;
        progressMs = 0;
        progressTime = 0;
        scrollY = targetScrollY = 0;
        tickerScrollX = tickerTargetX = 0;
        charFractions = null;
        fractionsLine = -1;
        builtWidth = 0;
        invalidate();
    }

    /** ключ трека: по нему хранится личный сдвиг текста */
    public void setOffsetKey(String key) {
        offsetKey = key;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public void setDuration(long durationMs) {
        this.durationMs = durationMs;
    }

    public void setProgress(float progress) {
        this.progress = progress;
        this.progressMs = (long) (progress * durationMs);
        this.progressTime = android.os.SystemClock.elapsedRealtime();
        invalidate();
    }

    /**
     * Текущая позиция в тексте. Плеер присылает прогресс редко — между обновлениями
     * мы досчитываем время сами, поэтому подсветка идёт ровно по голосу, а не рывками.
     */
    private long positionMs() {
        if (previewMode) {
            return (long) (progress * durationMs);
        }
        long value = progressMs;
        if (PengramConfig.isLyricsSmooth() && progressTime > 0) {
            boolean playing = true;
            try {
                playing = !org.telegram.messenger.MediaController.getInstance().isMessagePaused();
            } catch (Throwable ignore) {
            }
            if (playing) {
                final long elapsed = android.os.SystemClock.elapsedRealtime() - progressTime;
                if (elapsed > 0 && elapsed < 3000) {
                    value += elapsed;
                }
            }
        }
        if (durationMs > 0) {
            value = Math.max(0, Math.min(durationMs, value));
        }
        if (timeScale != 1f && timeScale > 0) {
            value = (long) (value / timeScale);
        }
        return value - PengramLyrics.getOffset(offsetKey);
    }

    /** перечитать настройки раз в 400 мс — в кадре они не меняются */
    private void refreshConfig(long now) {
        if (!previewMode && configTime != 0 && now - configTime < 400) {
            return;
        }
        configTime = now;
        cfgDim = PengramConfig.getLyricsDim();
        cfgSpeed = PengramConfig.getLyricsSpeed();
        cfgAnim = previewAnim >= 0 ? previewAnim
                : animOverride >= 0 ? animOverride : PengramConfig.getLyricsAnim();
        cfgShadow = !tickerMode && PengramConfig.getBool(PengramConfig.KEY_LYRICS_SHADOW, true);
        cfgAutoscroll = PengramConfig.getBool(PengramConfig.KEY_LYRICS_AUTOSCROLL, true);
        cfgWords = !tickerMode || PengramConfig.getBool(PengramConfig.KEY_HEADER_LYRICS_WORDS, true);
        cfgTickerSpeed = PengramConfig.getHeaderLyricsSpeed();
    }

    /** ширины символов строки: считаем один раз, а не каждый кадр */
    private float[] widthsFor(int index) {
        if (lineWidths == null || index < 0 || index >= lineWidths.length) {
            return null;
        }
        if (lineWidths[index] == null) {
            final CharSequence text = layouts.get(index).getText();
            final float[] result = new float[text.length()];
            if (result.length > 0) {
                textPaint.getTextWidths(text, 0, result.length, result);
            }
            lineWidths[index] = result;
        }
        return lineWidths[index];
    }

    /** перестроить разметку, если поменялись настройки или ширина */
    private void buildLayouts() {
        final int width = getMeasuredWidth() - AndroidUtilities.dp(32);
        if (width <= 0) {
            return;
        }
        final int size = (previewMode || tickerMode) && previewSize > 0 ? previewSize : PengramConfig.getLyricsSize();
        final boolean bold = tickerMode
                ? PengramConfig.getBool(PengramConfig.KEY_HEADER_LYRICS_BOLD, false)
                : PengramConfig.getBool(PengramConfig.KEY_LYRICS_BOLD, true);
        final int align = tickerMode ? PengramConfig.LYRICS_ALIGN_LEFT : PengramConfig.getLyricsAlign();
        if (builtWidth == width && builtSize == size && builtBold == bold && builtAlign == align && !layouts.isEmpty()) {
            return;
        }
        builtWidth = width;
        builtSize = size;
        builtBold = bold;
        builtAlign = align;
        textPaint.setTextSize(AndroidUtilities.dp(size));
        textPaint.setTypeface(bold ? AndroidUtilities.bold() : null);
        layouts.clear();
        tops.clear();
        lineWidths = null;
        int y = 0;
        final Layout.Alignment alignment = align == PengramConfig.LYRICS_ALIGN_CENTER
                ? Layout.Alignment.ALIGN_CENTER
                : align == PengramConfig.LYRICS_ALIGN_RIGHT ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        final boolean marquee = tickerMode && PengramConfig.getBool(PengramConfig.KEY_HEADER_LYRICS_MARQUEE, true);
        for (PengramLyrics.Line line : lines) {
            CharSequence text = TextUtils.isEmpty(line.text) ? " " : line.text;
            int lineWidth = width;
            if (tickerMode) {
                if (marquee) {
                    // строка живёт одной строкой во всю длину — наружу её вывозит бегущая прокрутка
                    lineWidth = (int) Math.ceil(textPaint.measureText(text, 0, text.length())) + AndroidUtilities.dp(4);
                    lineWidth = Math.max(lineWidth, width);
                } else {
                    text = TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END);
                }
            }
            final StaticLayout layout = new StaticLayout(
                    text, textPaint, Math.max(1, lineWidth), alignment, 1.05f, 0, false);
            layouts.add(layout);
            tops.add(y);
            y += layout.getHeight() + AndroidUtilities.dp(14);
        }
        lineWidths = new float[layouts.size()][];
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        buildLayouts();
    }

    /** какая строка сейчас звучит и насколько она «прожита» (0..1) */
    /**
     * Доля строки, на которой «зажигается» символ. Если у строки есть пословные метки
     * (enhanced LRC), считаем по ним — тогда подсветка идёт ровно по голосу.
     */
    private void buildCharFractions(int lineIndex, CharSequence text) {
        fractionsLine = lineIndex;
        final int total = text.length();
        charFractions = new float[total + 1];
        PengramLyrics.Line line = lineIndex >= 0 && lineIndex < lines.size() ? lines.get(lineIndex) : null;
        if (line == null || !line.hasWords() || !timed) {
            for (int a = 0; a <= total; ++a) {
                charFractions[a] = total == 0 ? 0 : a / (float) total;
            }
            return;
        }
        final long start = Math.max(0, line.time);
        long end = durationMs;
        for (int a = lineIndex + 1; a < lines.size(); ++a) {
            if (lines.get(a).time >= 0) {
                end = lines.get(a).time;
                break;
            }
        }
        final long lastWord = line.wordTimes[line.wordTimes.length - 1];
        if (end <= lastWord) {
            end = lastWord + 1200;
        }
        final float span = Math.max(1, end - start);
        int mark = 0;
        for (int a = 0; a <= total; ++a) {
            while (mark + 1 < line.wordChars.length && line.wordChars[mark + 1] <= a) {
                mark++;
            }
            final int charFrom = Math.min(total, line.wordChars[mark]);
            final long timeFrom = line.wordTimes[mark];
            final int charTo = mark + 1 < line.wordChars.length ? Math.min(total, line.wordChars[mark + 1]) : total;
            final long timeTo = mark + 1 < line.wordTimes.length ? line.wordTimes[mark + 1] : end;
            final float inner = charTo > charFrom ? (a - charFrom) / (float) (charTo - charFrom) : 1f;
            final float time = timeFrom + (timeTo - timeFrom) * Utilities.clamp(inner, 1f, 0f);
            charFractions[a] = Utilities.clamp((time - start) / span, 1f, 0f);
        }
    }

    private float charFraction(int charIndex, int total) {
        if (charFractions == null || charFractions.length <= total) {
            return total == 0 ? 0 : Utilities.clamp(charIndex / (float) total, 1f, 0f);
        }
        return charFractions[Math.max(0, Math.min(charFractions.length - 1, charIndex))];
    }

    private float lineProgress(int index) {
        if (index < 0 || index >= lines.size()) {
            return 0;
        }
        if (timed) {
            final long start = Math.max(0, lines.get(index).time);
            long end = durationMs;
            for (int a = index + 1; a < lines.size(); ++a) {
                if (lines.get(a).time >= 0) {
                    end = lines.get(a).time;
                    break;
                }
            }
            if (end <= start) {
                end = start + 2500;
            }
            final long now = positionMs();
            return Utilities.clamp((now - start) / (float) (end - start), 1f, 0f);
        }
        final float per = 1f / Math.max(1, lines.size());
        return Utilities.clamp((progress - index * per) / per, 1f, 0f);
    }

    private int currentLine() {
        if (lines.isEmpty()) {
            return -1;
        }
        if (timed) {
            final long now = positionMs();
            int result = 0;
            for (int a = 0; a < lines.size(); ++a) {
                if (lines.get(a).time >= 0 && lines.get(a).time <= now) {
                    result = a;
                }
            }
            return result;
        }
        return Math.min(lines.size() - 1, (int) (progress * lines.size()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (layouts.isEmpty() || getVisibility() != VISIBLE || getAlpha() < 0.01f) {
            return;
        }
        final long now = System.currentTimeMillis();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.064f, (now - lastFrame) / 1000f);
        lastFrame = now;
        refreshConfig(now);
        final float speed = cfgSpeed / 100f;

        if (previewMode) {
            final float loop = (now % 4200L) / 3200f;
            if (loop < progress) {
                enterAnim = 0;
            }
            progress = Math.min(1f, loop);
        }

        final int line = currentLine();
        if (line != activeLine) {
            activeLine = line;
            enterAnim = 0;
            charFractions = null;
            fractionsLine = -1;
        }
        enterAnim = Math.min(1f, enterAnim + dt * 2.6f * speed);

        if (activeLine >= 0 && activeLine < layouts.size()) {
            targetScrollY = tops.get(activeLine) - getMeasuredHeight() * 0.42f + layouts.get(activeLine).getHeight() * 0.5f;
        }
        if (previewMode) {
            targetScrollY = scrollY = 0;
        }
        if (!cfgAutoscroll) {
            targetScrollY = Math.max(0, targetScrollY);
        }
        scrollY = AndroidUtilities.lerp(scrollY, targetScrollY, Math.min(1f, dt * 7f));

        final int dim = cfgDim;
        final int anim = cfgAnim;
        final boolean shadow = cfgShadow;

        canvas.save();
        if (tickerMode && activeLine >= 0 && activeLine < layouts.size()) {
            final StaticLayout layout = layouts.get(activeLine);
            final float visible = Math.max(1, getMeasuredWidth() - AndroidUtilities.dp(8));
            final float over = layout.getWidth() - visible;
            if (over > 0) {
                // строка не помещается: едем за поющимся словом, оставляя его слева по центру
                final float at = Utilities.clamp(lineProgress(activeLine), 1f, 0f) * layout.getWidth();
                tickerTargetX = Utilities.clamp(at - visible * 0.45f, over, 0);
            } else {
                tickerTargetX = 0;
            }
            final float tickerSpeed = cfgTickerSpeed / 100f;
            tickerScrollX = AndroidUtilities.lerp(tickerScrollX, tickerTargetX, Math.min(1f, dt * 4.5f * tickerSpeed));
            canvas.translate(-tickerScrollX,
                    Math.max(0, (getMeasuredHeight() - layout.getHeight()) / 2f) - tops.get(activeLine));
        } else if (previewMode && !layouts.isEmpty()) {
            canvas.translate(AndroidUtilities.dp(16), Math.max(0, (getMeasuredHeight() - layouts.get(0).getHeight()) / 2f));
        } else {
            canvas.translate(AndroidUtilities.dp(16), -scrollY);
        }
        for (int a = 0; a < layouts.size(); ++a) {
            if (tickerMode && a != activeLine) {
                continue;
            }
            final StaticLayout layout = layouts.get(a);
            final int top = tops.get(a);
            if (!tickerMode && (top - scrollY > getMeasuredHeight() || top - scrollY + layout.getHeight() < 0)) {
                continue;
            }
            canvas.save();
            canvas.translate(0, top);
            if (a == activeLine) {
                // в шапке подсветку слов можно выключить — тогда строка горит целиком
                final float fraction = cfgWords ? lineProgress(a) : 1f;
                drawActiveLine(canvas, layout, fraction, anim, now, shadow);
            } else {
                final float distance = Math.abs(a - activeLine);
                final float alpha = Math.max(0.12f, dim / 100f * (1f - Math.min(0.6f, distance * 0.12f)));
                textPaint.setColor(ColorUtils.setAlphaComponent(baseColor, (int) (255 * alpha)));
                textPaint.clearShadowLayer();
                layout.draw(canvas);
            }
            canvas.restore();
        }
        canvas.restore();

        boolean playing = true;
        if (!previewMode) {
            try {
                playing = !org.telegram.messenger.MediaController.getInstance().isMessagePaused();
            } catch (Throwable ignore) {
            }
        }
        boolean moving = Math.abs(scrollY - targetScrollY) > 0.5f
                || Math.abs(tickerScrollX - tickerTargetX) > 0.5f
                || enterAnim < 1f || previewMode
                || (playing && (timed || animated(anim)));
        if (moving && playing && !previewMode && !animated(anim)
                && Math.abs(scrollY - targetScrollY) <= 0.5f
                && Math.abs(tickerScrollX - tickerTargetX) <= 0.5f
                && enterAnim >= 1f) {
            // ничего не движется, кроме подсветки: если она не сдвинулась даже на полпикселя,
            // кадр рисовать незачем — именно это съедало плавность чата
            final float cut = activeLine >= 0 && activeLine < layouts.size()
                    ? lineProgress(activeLine) * layouts.get(activeLine).getWidth() : 0;
            if (activeLine == lastDrawnLine && Math.abs(cut - lastDrawnCut) < 0.5f) {
                moving = false;
            }
            lastDrawnCut = cut;
            lastDrawnLine = activeLine;
        }
        if (moving) {
            postInvalidateOnAnimation();
        }
    }

    /** анимации, которым нужна постоянная перерисовка даже на паузе */
    private static boolean animated(int anim) {
        return anim == PengramConfig.LYRICS_ANIM_WAVE
                || anim == PengramConfig.LYRICS_ANIM_NEON
                || anim == PengramConfig.LYRICS_ANIM_PULSE
                || anim == PengramConfig.LYRICS_ANIM_SHAKE
                || anim == PengramConfig.LYRICS_ANIM_SWEEP
                || anim == PengramConfig.LYRICS_ANIM_RAINBOW;
    }

    /**
     * Быстрый путь: вся строка приглушённая + яркая часть, отсечённая ровно по спетой букве.
     * Возвращает false, если ширины символов ещё не посчитаны и нужен обычный путь.
     */
    private boolean drawClipped(Canvas canvas, StaticLayout layout, float lineProgress, int anim, boolean shadow, float dimAlpha) {
        final float[] charWidths = widthsFor(activeLine);
        if (charWidths == null) {
            return false;
        }
        final CharSequence text = layout.getText();
        final int total = text.length();
        int idx = 0;
        while (idx < total && charFraction(idx + 1, total) <= lineProgress) {
            idx++;
        }
        final float from = charFraction(idx, total);
        final float to = charFraction(idx + 1, total);
        final float inner = to > from ? Utilities.clamp((lineProgress - from) / (to - from), 1f, 0f) : 1f;

        if (anim != PengramConfig.LYRICS_ANIM_TYPEWRITER) {
            textPaint.setColor(ColorUtils.setAlphaComponent(baseColor, (int) (255 * Math.max(0.15f, dimAlpha))));
            textPaint.clearShadowLayer();
            layout.draw(canvas);
        }
        if (lineProgress <= 0) {
            return true;
        }
        textPaint.setColor(accentColor);
        if (shadow) {
            textPaint.setShadowLayer(AndroidUtilities.dp(8), 0, 0, ColorUtils.setAlphaComponent(accentColor, 70));
        } else {
            textPaint.clearShadowLayer();
        }
        for (int l = 0; l < layout.getLineCount(); ++l) {
            final int start = layout.getLineStart(l);
            final int end = layout.getLineEnd(l);
            if (start > idx) {
                break;
            }
            final float left = layout.getLineLeft(l);
            float right;
            if (end <= idx) {
                right = layout.getLineRight(l) + AndroidUtilities.dp(1);
            } else {
                float x = left;
                for (int c = start; c < Math.min(end, idx); ++c) {
                    if (c < charWidths.length) {
                        x += charWidths[c];
                    }
                }
                if (idx < charWidths.length && idx < end) {
                    x += charWidths[idx] * inner;
                }
                right = x;
            }
            if (right <= left) {
                continue;
            }
            canvas.save();
            canvas.clipRect(left - AndroidUtilities.dp(2), layout.getLineTop(l), right, layout.getLineBottom(l));
            layout.draw(canvas);
            canvas.restore();
        }
        textPaint.clearShadowLayer();
        return true;
    }

    /** активная строка — здесь и живут все побуквенные эффекты */
    private void drawActiveLine(Canvas canvas, StaticLayout layout, float lineProgress, int anim, long now, boolean shadow) {
        final CharSequence text = layout.getText();
        final int total = Math.max(1, text.length());
        final float time = now / 1000f;
        final float speed = cfgSpeed / 100f;
        final float dimAlpha = Math.max(0.15f, cfgDim / 100f);

        if (anim == PengramConfig.LYRICS_ANIM_NONE) {
            textPaint.setColor(accentColor);
            textPaint.clearShadowLayer();
            layout.draw(canvas);
            return;
        }
        if (anim == PengramConfig.LYRICS_ANIM_PULSE) {
            final float scale = 1f + 0.035f * (float) Math.sin(time * 5.5f * speed);
            canvas.save();
            canvas.scale(scale, scale, layout.getWidth() * 0.5f, layout.getHeight() * 0.5f);
            textPaint.setColor(accentColor);
            if (shadow) {
                textPaint.setShadowLayer(AndroidUtilities.dp(10), 0, 0, ColorUtils.setAlphaComponent(accentColor, 90));
            } else {
                textPaint.clearShadowLayer();
            }
            layout.draw(canvas);
            canvas.restore();
            textPaint.clearShadowLayer();
            return;
        }

        if (charFractions == null || fractionsLine != activeLine || charFractions.length != text.length() + 1) {
            buildCharFractions(activeLine, text);
        }

        // Караоке и печатная машинка рисуются отсечением: два вызова вместо сотни по буквам.
        // Подсветка остаётся буква в букву, но кадр стоит в десятки раз дешевле.
        if ((anim == PengramConfig.LYRICS_ANIM_KARAOKE || anim == PengramConfig.LYRICS_ANIM_TYPEWRITER)
                && drawClipped(canvas, layout, lineProgress, anim, shadow, dimAlpha)) {
            return;
        }

        int charIndex = 0;
        for (int l = 0; l < layout.getLineCount(); ++l) {
            final int start = layout.getLineStart(l);
            final int end = layout.getLineEnd(l);
            final int count = Math.min(end - start, widths.length);
            if (count <= 0) {
                continue;
            }
            final float[] cached = widthsFor(activeLine);
            if (cached != null && cached.length >= start + count) {
                System.arraycopy(cached, start, widths, 0, count);
            } else {
                textPaint.getTextWidths(text, start, start + count, widths);
            }
            float x = layout.getLineLeft(l);
            final float baseline = layout.getLineBaseline(l);
            for (int c = 0; c < count; ++c) {
                final char ch = text.charAt(start + c);
                final float w = widths[c];
                if (ch == ' ') {
                    x += w;
                    charIndex++;
                    continue;
                }
                final float charStart = charFraction(charIndex, total);
                final float charSpan = Math.max(0.0005f, charFraction(charIndex + 1, total) - charStart);
                final float raw = (lineProgress - charStart) / charSpan;
                final float p = Utilities.clamp(raw, 1f, 0f);
                float dy = 0;
                float scale = 1f;
                int color = accentColor;
                float alpha = 1f;

                switch (anim) {
                    case PengramConfig.LYRICS_ANIM_KARAOKE: {
                        color = p > 0 ? accentColor : ColorUtils.setAlphaComponent(baseColor, (int) (255 * dimAlpha));
                        if (p > 0 && p < 1) {
                            color = ColorUtils.blendARGB(ColorUtils.setAlphaComponent(baseColor, (int) (255 * dimAlpha)), accentColor, p);
                        }
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_LETTERS: {
                        alpha = Math.max(dimAlpha * 0.6f, p);
                        dy = (1f - p) * AndroidUtilities.dp(14);
                        scale = 0.8f + 0.2f * p;
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_WAVE: {
                        alpha = Math.max(dimAlpha, p);
                        dy = (float) Math.sin(time * 4.2f * speed + charIndex * 0.45f) * AndroidUtilities.dp(3.5f) * (0.35f + 0.65f * p);
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_BOUNCE: {
                        alpha = Math.max(dimAlpha, p);
                        final float bump = (float) Math.exp(-Math.pow((raw - 0.5f) * 1.6f, 2));
                        dy = -bump * AndroidUtilities.dp(10);
                        scale = 1f + bump * 0.28f;
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_TYPEWRITER: {
                        if (p <= 0) {
                            x += w;
                            charIndex++;
                            continue;
                        }
                        alpha = 1f;
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_NEON: {
                        alpha = Math.max(dimAlpha, p);
                        textPaint.setShadowLayer(AndroidUtilities.dp(2 + 10 * p), 0, 0, ColorUtils.setAlphaComponent(accentColor, (int) (190 * p)));
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_RAINBOW: {
                        final float hue = ((charIndex * 14f + time * 80f * speed) % 360f);
                        hsv[0] = hue;
                        hsv[1] = 0.65f;
                        hsv[2] = 1f;
                        color = Color.HSVToColor(hsv);
                        alpha = Math.max(dimAlpha, p);
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_GRADIENT: {
                        final float shift = (time * 0.35f * speed + charIndex * 0.06f) % 1f;
                        color = ColorUtils.blendARGB(accentColor, 0xFFFFFFFF, 0.5f + 0.5f * (float) Math.sin(shift * 6.283f));
                        alpha = Math.max(dimAlpha, p);
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_SHAKE: {
                        alpha = Math.max(dimAlpha, p);
                        final float energy = p * (1f - Math.min(1f, Math.abs(raw - 1f) * 0.6f));
                        dy = (float) Math.sin(time * 26f * speed + charIndex) * AndroidUtilities.dp(1.6f) * energy;
                        scale = 1f + 0.05f * energy;
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_DROP: {
                        alpha = Math.max(dimAlpha * 0.5f, p);
                        final float fall = 1f - Utilities.clamp(raw, 1f, 0f);
                        dy = -fall * AndroidUtilities.dp(22);
                        scale = 0.9f + 0.1f * p;
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_FLIP: {
                        alpha = Math.max(dimAlpha, p);
                        final float turn = Utilities.clamp(raw, 1f, 0f);
                        canvas.save();
                        canvas.translate(x, 0);
                        canvas.scale(1f, 0.15f + 0.85f * (float) Math.sin(turn * 1.5707963f), w * 0.5f, baseline - textPaint.getTextSize() * 0.32f);
                        textPaint.setColor(ColorUtils.setAlphaComponent(
                                p > 0 ? accentColor : ColorUtils.setAlphaComponent(baseColor, (int) (255 * dimAlpha)),
                                (int) (255 * Utilities.clamp(alpha * enterAnim + (1f - enterAnim) * dimAlpha, 1f, 0f))));
                        canvas.drawText(text, start + c, start + c + 1, 0, baseline, textPaint);
                        canvas.restore();
                        x += w;
                        charIndex++;
                        continue;
                    }
                    case PengramConfig.LYRICS_ANIM_SWEEP: {
                        final float head = lineProgress;
                        final float distance = Math.abs(charStart - head);
                        final float band = (float) Math.exp(-Math.pow(distance * 9f, 2));
                        color = ColorUtils.blendARGB(
                                p > 0 ? accentColor : ColorUtils.setAlphaComponent(baseColor, (int) (255 * dimAlpha)),
                                0xFFFFFFFF, band);
                        alpha = Math.max(dimAlpha, Math.max(p, band));
                        if (band > 0.1f) {
                            textPaint.setShadowLayer(AndroidUtilities.dp(10) * band, 0, 0, ColorUtils.setAlphaComponent(0xFFFFFFFF, (int) (160 * band)));
                        }
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_MAGNIFY: {
                        final float head = lineProgress;
                        final float distance = Math.abs(charStart - head);
                        final float near = (float) Math.exp(-Math.pow(distance * 11f, 2));
                        alpha = Math.max(dimAlpha, Math.max(p * 0.85f, near));
                        scale = 1f + 0.4f * near;
                        dy = -near * AndroidUtilities.dp(3);
                        break;
                    }
                    case PengramConfig.LYRICS_ANIM_BLUR: {
                        alpha = Math.max(dimAlpha * 0.8f, p);
                        if (p < 1) {
                            textPaint.setShadowLayer(AndroidUtilities.dp(1 + 7 * (1f - p)), 0, 0, ColorUtils.setAlphaComponent(accentColor, (int) (160 * (1f - p))));
                        } else {
                            textPaint.clearShadowLayer();
                        }
                        break;
                    }
                }

                if (anim != PengramConfig.LYRICS_ANIM_NEON && anim != PengramConfig.LYRICS_ANIM_BLUR
                        && anim != PengramConfig.LYRICS_ANIM_SWEEP) {
                    if (shadow) {
                        textPaint.setShadowLayer(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(1), 0x66000000);
                    } else {
                        textPaint.clearShadowLayer();
                    }
                }
                textPaint.setColor(ColorUtils.setAlphaComponent(color, (int) (255 * Utilities.clamp(alpha * enterAnim + (1f - enterAnim) * dimAlpha, 1f, 0f))));

                canvas.save();
                canvas.translate(x, dy);
                if (scale != 1f) {
                    canvas.scale(scale, scale, w * 0.5f, baseline - textPaint.getTextSize() * 0.32f);
                }
                canvas.drawText(text, start + c, start + c + 1, 0, baseline, textPaint);
                canvas.restore();

                x += w;
                charIndex++;
            }
            charIndex += Math.max(0, (end - start) - count);
        }
        textPaint.clearShadowLayer();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && timed && seekCallback != null && durationMs > 0) {
            final float y = event.getY() + scrollY;
            for (int a = 0; a < layouts.size(); ++a) {
                final int top = tops.get(a);
                if (y >= top - AndroidUtilities.dp(7) && y <= top + layouts.get(a).getHeight() + AndroidUtilities.dp(7)) {
                    final long time = Math.max(0, lines.get(a).time);
                    seekCallback.seekTo(Utilities.clamp(time / (float) durationMs, 1f, 0f));
                    return true;
                }
            }
        }
        if (previewMode || tickerMode) {
            return super.onTouchEvent(event);
        }
        return true;
    }

    public CharSequence emptyText() {
        return LocaleController.getString(R.string.PengramLyricsEmpty);
    }
}
