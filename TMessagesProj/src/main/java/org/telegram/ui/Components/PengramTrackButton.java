package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

/**
 * Pengram: кнопка «предыдущий/следующий трек».
 * Рисуется вектором (треугольник и планка), а не повёрнутой стрелкой из набора —
 * поэтому выглядит ровно и живо: нажатие сжимает кнопку, отпускание возвращает с отскоком.
 */
public class PengramTrackButton extends View {

    private final boolean next;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private float pressProgress;
    private boolean pressed;
    private long lastFrame;

    public PengramTrackButton(Context context, boolean next) {
        super(context);
        this.next = next;
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = System.currentTimeMillis();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        final float target = pressed ? 1f : 0f;
        pressProgress = AndroidUtilities.lerp(pressProgress, target, Math.min(1f, dt * 14f));
        if (Math.abs(pressProgress - target) > 0.01f) {
            invalidate();
        }

        final float w = getMeasuredWidth();
        final float h = getMeasuredHeight();
        final float scale = 1f - 0.12f * pressProgress;
        canvas.save();
        canvas.scale(scale, scale, w / 2f, h / 2f);

        final float size = Math.min(w, h) * 0.42f;
        final float cx = w / 2f;
        final float cy = h / 2f;
        final float barWidth = size * 0.22f;
        final float triWidth = size * 0.78f;
        final float half = size * 0.62f;

        path.reset();
        if (next) {
            path.moveTo(cx - triWidth, cy - half);
            path.lineTo(cx + triWidth * 0.25f, cy);
            path.lineTo(cx - triWidth, cy + half);
            path.close();
            rect.set(cx + triWidth * 0.45f, cy - half, cx + triWidth * 0.45f + barWidth, cy + half);
        } else {
            path.moveTo(cx + triWidth, cy - half);
            path.lineTo(cx - triWidth * 0.25f, cy);
            path.lineTo(cx + triWidth, cy + half);
            path.close();
            rect.set(cx - triWidth * 0.45f - barWidth, cy - half, cx - triWidth * 0.45f, cy + half);
        }
        canvas.drawPath(path, paint);
        canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
        canvas.restore();
    }
}
