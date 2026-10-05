package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import org.telegram.messenger.AndroidUtilities;

/**
 * Pengram: переключение трека свайпом по свёрнутому плееру в шапке.
 *
 * Палец ведёт за собой название и кнопку, по краю проступает стрелка — сразу
 * видно, куда уедет трек. Отпустили за порогом (или коротким рывком) — уходит
 * предыдущий/следующий, не дотянули — всё мягко возвращается на место.
 *
 * Класс ничего не знает про плеер: ему дают строку содержимого и два действия.
 */
public final class PengramPlayerSwipe {

    public interface Callback {
        /** можно ли сейчас свайпать (нужный режим шапки, играет музыка, настройка включена) */
        boolean canSwipe();

        /** свайп влево — следующий трек */
        void next();

        /** свайп вправо — предыдущий трек */
        void previous();
    }

    /** после какого смещения свайп считается состоявшимся */
    private static final float TRIGGER = 64;
    /** дальше этого палец содержимое не утащит: дальше идёт упругое сопротивление */
    private static final float LIMIT = 96;
    private static final float FLING = 700;

    private final View root;
    private final Callback callback;
    private final View[] content;
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    private final int touchSlop;
    private VelocityTracker velocity;
    private boolean tracking;
    private boolean dragging;
    private float startX, startY;
    private float shift;
    private ValueAnimator animator;

    public PengramPlayerSwipe(View root, View[] content, Callback callback) {
        this.root = root;
        this.content = content;
        this.callback = callback;
        this.touchSlop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        arrowPaint.setStrokeWidth(dp(2));
    }

    /** цвет стрелок — берём у текста плеера, чтобы совпадало с темой */
    public void setColor(int color) {
        arrowPaint.setColor(color);
    }

    // ------------------------------------------------------------- касания

    public boolean onIntercept(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            begin(event);
            return false;
        }
        if (!tracking || !enabled()) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            final float dx = event.getX() - startX;
            final float dy = event.getY() - startY;
            if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                dragging = true;
                startX = event.getX();
                disallowParent();
                return true;
            }
        }
        return dragging;
    }

    public boolean onTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (!tracking || !enabled()) {
                    return false;
                }
                if (velocity != null) {
                    velocity.addMovement(event);
                }
                final float dx = event.getX() - startX;
                final float dy = event.getY() - startY;
                if (!dragging) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging = true;
                        startX = event.getX();
                        disallowParent();
                    }
                    return dragging;
                }
                setShift(resist(event.getX() - startX));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) {
                    finish();
                    return false;
                }
                float speed = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    speed = velocity.getXVelocity();
                }
                final boolean cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                final boolean far = Math.abs(shift) >= dp(TRIGGER);
                final boolean flung = Math.abs(speed) > dp(FLING) && Math.signum(speed) == Math.signum(shift);
                if (!cancelled && (far || flung)) {
                    fire(shift < 0);
                } else {
                    springBack();
                }
                finish();
                return true;
            }
        }
        return false;
    }

    private void begin(MotionEvent event) {
        tracking = enabled();
        dragging = false;
        startX = event.getX();
        startY = event.getY();
        if (tracking) {
            if (velocity == null) {
                velocity = VelocityTracker.obtain();
            } else {
                velocity.clear();
            }
            velocity.addMovement(event);
        }
    }

    private void finish() {
        tracking = false;
        dragging = false;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private boolean enabled() {
        return callback != null && callback.canSwipe();
    }

    private void disallowParent() {
        if (root.getParent() != null) {
            root.getParent().requestDisallowInterceptTouchEvent(true);
        }
        cancelAnimator();
    }

    /** у края ход становится тугим — ощущается как резинка, а не как обрыв */
    private float resist(float dx) {
        final float limit = dp(LIMIT);
        final float sign = Math.signum(dx);
        final float value = Math.abs(dx);
        if (value <= limit) {
            return dx;
        }
        return sign * (limit + (value - limit) * 0.22f);
    }

    // ------------------------------------------------------------- анимации

    private void setShift(float value) {
        shift = value;
        final float progress = Math.min(1f, Math.abs(value) / dp(LIMIT));
        for (View view : content) {
            if (view == null) {
                continue;
            }
            view.setTranslationX(value);
            view.setAlpha(1f - 0.55f * progress);
        }
        root.invalidate();
    }

    private void springBack() {
        animateTo(0, 220, null);
    }

    /** уехавший трек догоняет край, новый прилетает с другой стороны */
    private void fire(boolean forward) {
        final float away = (forward ? -1 : 1) * dp(LIMIT + 40);
        animateTo(away, 140, () -> {
            if (forward) {
                callback.next();
            } else {
                callback.previous();
            }
            setShift(-away * 0.75f);
            animateTo(0, 260, null);
        });
    }

    private void animateTo(float target, long duration, Runnable after) {
        cancelAnimator();
        final float from = shift;
        animator = ValueAnimator.ofFloat(from, target);
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

    /** сброс при смене режима шапки — чтобы содержимое не осталось уехавшим */
    public void reset() {
        cancelAnimator();
        finish();
        setShift(0);
    }

    public boolean isDragging() {
        return dragging;
    }

    // ------------------------------------------------------------- стрелка у края

    public void draw(Canvas canvas, int width, int height) {
        if (Math.abs(shift) < dp(6)) {
            return;
        }
        final float progress = Math.min(1f, Math.abs(shift) / dp(TRIGGER));
        final boolean forward = shift < 0;
        final float cx = forward ? width - dp(18) : dp(18);
        final float cy = height / 2f;
        final float size = dp(4) + dp(2) * progress;
        final int alpha = (int) (255 * (0.25f + 0.75f * progress));

        arrow.reset();
        final float dir = forward ? 1 : -1;
        // двойная стрелка — как на кнопках перемотки
        for (int i = 0; i < 2; i++) {
            final float x = cx + dir * (i * dp(5) - dp(2));
            arrow.moveTo(x - dir * size, cy - size);
            arrow.lineTo(x + dir * size, cy);
            arrow.lineTo(x - dir * size, cy + size);
        }
        final int color = arrowPaint.getColor();
        arrowPaint.setAlpha(alpha);
        canvas.save();
        canvas.translate(dir * dp(4) * progress, 0);
        canvas.drawPath(arrow, arrowPaint);
        canvas.restore();
        arrowPaint.setColor(color);
    }

    /** текущее смещение — пригодится превью в настройках */
    public float shift() {
        return shift;
    }

    public static int triggerDistance() {
        return AndroidUtilities.dp(TRIGGER);
    }
}
