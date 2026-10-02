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
    private int builtAlign;

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
        activeLine = -1;
        scrollY = targetScrollY = 0;
        enterAnim = 0;
        builtWidth = 0;
        buildLayouts();
        invalidate();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public void setDuration(long durationMs) {
        this.durationMs = durationMs;
    }

    public void setProgress(float progress) {
        this.progress = progress;
        invalidate();
    }

    /** перестроить разметку, если поменялись настройки или ширина */
    private void buildLayouts() {
        final int width = getMeasuredWidth() - AndroidUtilities.dp(32);
        if (width <= 0) {
            return;
        }
        final int size = previewMode && previewSize > 0 ? previewSize : PengramConfig.getLyricsSize();
        final boolean bold = PengramConfig.getBool(PengramConfig.KEY_LYRICS_BOLD, true);
        final int align = PengramConfig.getLyricsAlign();
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
        int y = 0;
        final Layout.Alignment alignment = align == PengramConfig.LYRICS_ALIGN_CENTER
                ? Layout.Alignment.ALIGN_CENTER
                : align == PengramConfig.LYRICS_ALIGN_RIGHT ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        for (PengramLyrics.Line line : lines) {
            final StaticLayout layout = new StaticLayout(
                    TextUtils.isEmpty(line.text) ? " " : line.text,
                    textPaint, width, alignment, 1.05f, 0, false);
            layouts.add(layout);
            tops.add(y);
            y += layout.getHeight() + AndroidUtilities.dp(14);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        buildLayouts();
    }

    /** какая строка сейчас звучит и насколько она «прожита» (0..1) */
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
            final long now = (long) (progress * durationMs);
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
            final long now = (long) (progress * durationMs);
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
        if (layouts.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.064f, (now - lastFrame) / 1000f);
        lastFrame = now;
        final float speed = PengramConfig.getLyricsSpeed() / 100f;

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
        }
        enterAnim = Math.min(1f, enterAnim + dt * 2.6f * speed);

        if (activeLine >= 0 && activeLine < layouts.size()) {
            targetScrollY = tops.get(activeLine) - getMeasuredHeight() * 0.42f + layouts.get(activeLine).getHeight() * 0.5f;
        }
        if (previewMode) {
            targetScrollY = scrollY = 0;
        }
        if (!PengramConfig.getBool(PengramConfig.KEY_LYRICS_AUTOSCROLL, true)) {
            targetScrollY = Math.max(0, targetScrollY);
        }
        scrollY = AndroidUtilities.lerp(scrollY, targetScrollY, Math.min(1f, dt * 7f));

        final int dim = PengramConfig.getLyricsDim();
        final int anim = previewAnim >= 0 ? previewAnim : PengramConfig.getLyricsAnim();
        final boolean shadow = PengramConfig.getBool(PengramConfig.KEY_LYRICS_SHADOW, true);

        canvas.save();
        if (previewMode && !layouts.isEmpty()) {
            canvas.translate(AndroidUtilities.dp(16), Math.max(0, (getMeasuredHeight() - layouts.get(0).getHeight()) / 2f));
        } else {
            canvas.translate(AndroidUtilities.dp(16), -scrollY);
        }
        for (int a = 0; a < layouts.size(); ++a) {
            final StaticLayout layout = layouts.get(a);
            final int top = tops.get(a);
            if (top - scrollY > getMeasuredHeight() || top - scrollY + layout.getHeight() < 0) {
                continue;
            }
            canvas.save();
            canvas.translate(0, top);
            if (a == activeLine) {
                drawActiveLine(canvas, layout, lineProgress(a), anim, now, shadow);
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

        invalidate();
    }

    /** активная строка — здесь и живут все побуквенные эффекты */
    private void drawActiveLine(Canvas canvas, StaticLayout layout, float lineProgress, int anim, long now, boolean shadow) {
        final CharSequence text = layout.getText();
        final int total = Math.max(1, text.length());
        final float time = now / 1000f;
        final float speed = PengramConfig.getLyricsSpeed() / 100f;
        final float dimAlpha = Math.max(0.15f, PengramConfig.getLyricsDim() / 100f);

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

        int charIndex = 0;
        for (int l = 0; l < layout.getLineCount(); ++l) {
            final int start = layout.getLineStart(l);
            final int end = layout.getLineEnd(l);
            final int count = Math.min(end - start, widths.length);
            if (count <= 0) {
                continue;
            }
            textPaint.getTextWidths(text, start, start + count, widths);
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
                final float charStart = charIndex / (float) total;
                final float raw = (lineProgress - charStart) * total;
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
                        color = Color.HSVToColor(new float[]{hue, 0.65f, 1f});
                        alpha = Math.max(dimAlpha, p);
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

                if (anim != PengramConfig.LYRICS_ANIM_NEON && anim != PengramConfig.LYRICS_ANIM_BLUR) {
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
        return !previewMode;
    }

    public CharSequence emptyText() {
        return LocaleController.getString(R.string.PengramLyricsEmpty);
    }
}
