package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: живой предпросмотр свайпа по свёрнутому плееру.
 *
 * Рисуется настоящая шапка плеера в миниатюре: кнопка, название, исполнитель,
 * полоска прогресса. Её можно тянуть пальцем ровно так же, как настоящую, —
 * трек «переключается» на следующий из короткого демо-списка. Если к превью
 * не прикасаться, оно само показывает жест: содержимое уезжает и возвращается.
 */
public class PengramSwipePreview extends View {

    private static final String[][] DEMO = {
            {"Нимфоманка", "Пошлая Молли"},
            {"Группа крови", "Кино"},
            {"Пыяла", "АИГЕЛ"},
            {"Воины света", "Ляпис Трубецкой"},
    };

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path triangle = new Path();
    private final Path arrow = new Path();

    private final int touchSlop;
    private float shift;
    private int track;
    private boolean dragging;
    private boolean touched;
    private float startX, startY;
    private ValueAnimator animator;
    private Runnable demo;
    private boolean enabled = true;

    public PengramSwipePreview(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        titlePaint.setTextSize(dp(14));
        titlePaint.setTypeface(AndroidUtilities.bold());
        subtitlePaint.setTextSize(dp(12));
        hintPaint.setTextSize(dp(12));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        arrowPaint.setStrokeWidth(dp(2));
    }

    /** выключенная настройка — превью показывает статичную шапку и не реагирует */
    public void setEnabledPreview(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        cancelAnimator();
        setShift(0);
        if (enabled) {
            scheduleDemo(1200);
        } else {
            removeCallbacks(demo);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (enabled) {
            scheduleDemo(1400);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnimator();
        if (demo != null) {
            removeCallbacks(demo);
        }
    }

    // ------------------------------------------------------------- показ жеста

    private void scheduleDemo(long delay) {
        if (demo == null) {
            demo = () -> {
                if (!enabled || touched || !isAttachedToWindow()) {
                    scheduleDemo(2600);
                    return;
                }
                playDemo();
            };
        }
        removeCallbacks(demo);
        postDelayed(demo, delay);
    }

    private void playDemo() {
        animateTo(-dp(70), 420, () -> {
            next(true);
            setShift(dp(52));
            animateTo(0, 420, () -> scheduleDemo(2400));
        });
    }

    // ------------------------------------------------------------- касания

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!enabled) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touched = true;
                dragging = false;
                startX = event.getX();
                startY = event.getY();
                cancelAnimator();
                if (demo != null) {
                    removeCallbacks(demo);
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                final float dx = event.getX() - startX;
                final float dy = event.getY() - startY;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    startX = event.getX();
                }
                if (dragging) {
                    setShift(resist(event.getX() - startX));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                touched = false;
                if (dragging && Math.abs(shift) >= dp(48)) {
                    final boolean forward = shift < 0;
                    animateTo((forward ? -1 : 1) * dp(96), 150, () -> {
                        next(forward);
                        setShift((forward ? 1 : -1) * dp(72));
                        animateTo(0, 300, () -> scheduleDemo(2600));
                    });
                } else {
                    animateTo(0, 240, () -> scheduleDemo(2600));
                }
                dragging = false;
                return true;
            }
        }
        return false;
    }

    private float resist(float dx) {
        final float limit = dp(84);
        final float value = Math.abs(dx);
        return value <= limit ? dx : Math.signum(dx) * (limit + (value - limit) * 0.2f);
    }

    private void next(boolean forward) {
        track = (track + (forward ? 1 : DEMO.length - 1)) % DEMO.length;
    }

    private void setShift(float value) {
        shift = value;
        invalidate();
    }

    private void animateTo(float target, long duration, Runnable after) {
        cancelAnimator();
        animator = ValueAnimator.ofFloat(shift, target);
        animator.setDuration(duration);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> setShift((float) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animator == animation) {
                    animator = null;
                }
                if (after != null) {
                    after.run();
                }
            }
        });
        animator.start();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    // ------------------------------------------------------------- рисование

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);

        final float barHeight = dp(44);
        final float barTop = dp(18);
        final float side = dp(16);
        rect.set(side, barTop, w - side, barTop + barHeight);

        barPaint.setColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite),
                accent, 0.07f));
        canvas.drawRoundRect(rect, dp(12), dp(12), barPaint);

        // стрелки по краям проступают по мере сдвига
        if (enabled && Math.abs(shift) > dp(4)) {
            final float progress = Math.min(1f, Math.abs(shift) / dp(48));
            drawArrows(canvas, shift < 0, rect, progress, accent);
        }

        canvas.save();
        canvas.clipRect(rect);
        final float alpha = 1f - Math.min(0.6f, Math.abs(shift) / dp(140));
        canvas.translate(shift, 0);

        final float cx = rect.left + dp(24);
        final float cy = rect.centerY();
        accentPaint.setColor(ColorUtils.setAlphaComponent(accent, (int) (255 * alpha)));
        canvas.drawCircle(cx, cy, dp(13), accentPaint);
        triangle.reset();
        triangle.moveTo(cx - dp(3.5f), cy - dp(5.5f));
        triangle.lineTo(cx + dp(5.5f), cy);
        triangle.lineTo(cx - dp(3.5f), cy + dp(5.5f));
        triangle.close();
        accentPaint.setColor(ColorUtils.setAlphaComponent(0xffffffff, (int) (255 * alpha)));
        canvas.drawPath(triangle, accentPaint);

        final float left = cx + dp(22);
        final float right = rect.right - dp(14);
        titlePaint.setColor(ColorUtils.setAlphaComponent(text, (int) (255 * alpha)));
        subtitlePaint.setColor(ColorUtils.setAlphaComponent(gray, (int) (255 * alpha)));
        final String[] item = DEMO[track];
        canvas.drawText(String.valueOf(TextUtils.ellipsize(item[0], titlePaint, right - left,
                TextUtils.TruncateAt.END)), left, cy - dp(2), titlePaint);
        canvas.drawText(String.valueOf(TextUtils.ellipsize(item[1], subtitlePaint, right - left,
                TextUtils.TruncateAt.END)), left, cy + dp(13), subtitlePaint);
        canvas.restore();

        // подпись снизу
        hintPaint.setColor(gray);
        final String hint = LocaleController.getString(enabled
                ? R.string.PengramPlayerSwipePreviewHint : R.string.PengramPlayerSwipePreviewOff);
        canvas.drawText(String.valueOf(TextUtils.ellipsize(hint, hintPaint, w - dp(28),
                TextUtils.TruncateAt.END)), w / 2f, barTop + barHeight + dp(22), hintPaint);
    }

    private void drawArrows(Canvas canvas, boolean forward, RectF bar, float progress, int accent) {
        arrowPaint.setColor(ColorUtils.setAlphaComponent(accent, (int) (255 * (0.3f + 0.7f * progress))));
        final float dir = forward ? 1 : -1;
        final float cx = forward ? bar.right - dp(18) : bar.left + dp(18);
        final float cy = bar.centerY();
        final float size = dp(4) + dp(2) * progress;
        arrow.reset();
        for (int i = 0; i < 2; i++) {
            final float x = cx + dir * (i * dp(5) - dp(2));
            arrow.moveTo(x - dir * size, cy - size);
            arrow.lineTo(x + dir * size, cy);
            arrow.lineTo(x - dir * size, cy + size);
        }
        canvas.drawPath(arrow, arrowPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(104));
    }
}
