package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: визуальный выбор места для аватарки автора.
 * Аватарку можно просто перетащить пальцем в нужную ячейку — или тапнуть по ней.
 * Каждая ячейка показывает эскиз сообщения, так что сразу видно, что получится.
 */
public class PengramAvatarPlacementView extends View {

    public interface Callback {
        void onPositionChanged(int position);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final ImageReceiver avatarReceiver = new ImageReceiver(this);
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();

    private final int[] positions = PengramConfig.AVATAR_POS_ORDER;
    private final float[] slotCenterX = new float[PengramConfig.AVATAR_POS_ORDER.length];

    private Callback callback;
    private int selected;
    private int hovered = -1;
    private boolean dragging;
    private float tokenX, tokenY;
    private float targetX, targetY;
    private long lastFrame;

    public PengramAvatarPlacementView(Context context) {
        super(context);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(dp(1.5f));
        dashPaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));
        textPaint.setTextSize(dp(11));
        textPaint.setTextAlign(Paint.Align.CENTER);

        final int account = UserConfig.selectedAccount;
        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        avatarDrawable.setInfo(account, user);
        avatarReceiver.setForUserOrChat(user, avatarDrawable);
        avatarReceiver.setRoundRadius(dp(40));

        selected = indexOf(PengramConfig.getGroupAvatarPos());
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void update() {
        final int index = indexOf(PengramConfig.getGroupAvatarPos());
        if (index != selected) {
            selected = index;
            invalidate();
        }
    }

    private int indexOf(int position) {
        for (int a = 0; a < positions.length; ++a) {
            if (positions[a] == position) {
                return a;
            }
        }
        return 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        avatarReceiver.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        avatarReceiver.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(136), MeasureSpec.EXACTLY));
    }

    private float slotWidth() {
        return (getMeasuredWidth() - dp(16)) / (float) positions.length;
    }

    private float slotLeft(int index) {
        return dp(8) + slotWidth() * index;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getMeasuredWidth();
        if (w <= 0) {
            return;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;

        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final float sw = slotWidth();
        final float cardTop = dp(6);
        final float cardBottom = getMeasuredHeight() - dp(26);

        for (int a = 0; a < positions.length; ++a) {
            final float left = slotLeft(a) + dp(3);
            final float right = slotLeft(a) + sw - dp(3);
            rect.set(left, cardTop, right, cardBottom);

            final boolean active = a == selected;
            final boolean hover = a == hovered && dragging;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            canvas.drawRoundRect(rect, dp(12), dp(12), paint);
            if (hover) {
                paint.setColor(ColorUtils.setAlphaComponent(accent, 36));
                canvas.drawRoundRect(rect, dp(12), dp(12), paint);
            }
            if (active || hover) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(ColorUtils.setAlphaComponent(accent, hover ? 255 : 170));
                canvas.drawRoundRect(rect, dp(12), dp(12), paint);
            }

            drawSketch(canvas, rect, positions[a], accent, a);

            // подпись под карточкой
            textPaint.setColor(active
                    ? accent
                    : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            final String label = shortName(positions[a]);
            canvas.drawText(TextUtils.ellipsize(label, textPaint, sw - dp(4), TextUtils.TruncateAt.END).toString(),
                    rect.centerX(), getMeasuredHeight() - dp(8), textPaint);
        }

        // сама аватарка — летит к выбранной ячейке
        if (!dragging) {
            targetX = slotCenterX[selected];
            targetY = sketchAvatarY();
        }
        if (tokenX == 0 && tokenY == 0) {
            tokenX = targetX;
            tokenY = targetY;
        }
        final float k = Math.min(1f, dt * 14f);
        tokenX = AndroidUtilities.lerp(tokenX, targetX, k);
        tokenY = AndroidUtilities.lerp(tokenY, targetY, k);

        final float size = dp(dragging ? 38 : 30);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawCircle(tokenX, tokenY + dp(1.5f), size / 2 + dp(1.5f), paint);
        avatarReceiver.setImageCoords(tokenX - size / 2, tokenY - size / 2, size, size);
        avatarReceiver.setRoundRadius((int) (size / 2));
        avatarReceiver.draw(canvas);

        if (Math.abs(tokenX - targetX) > 0.5f || Math.abs(tokenY - targetY) > 0.5f || dragging) {
            invalidate();
        }
    }

    private float sketchAvatarY() {
        return dp(6) + (getMeasuredHeight() - dp(26) - dp(6)) * 0.42f;
    }

    /** эскиз сообщения: пузырь, две строки текста и место под аватарку */
    private void drawSketch(Canvas canvas, RectF card, int position, int accent, int index) {
        final float pad = dp(7);
        final float avatarSize = dp(13);
        final float bubbleTop = card.top + dp(12);
        final float bubbleBottom = card.bottom - dp(14);

        float bubbleLeft = card.left + pad;
        float bubbleRight = card.right - pad;
        float avatarCx;
        final float avatarCy = sketchAvatarY();

        switch (position) {
            case PengramConfig.AVATAR_POS_LEFT:
                bubbleLeft = card.left + pad + avatarSize + dp(3);
                avatarCx = card.left + pad + avatarSize / 2;
                break;
            case PengramConfig.AVATAR_POS_RIGHT:
                bubbleRight = card.right - pad - avatarSize - dp(3);
                avatarCx = card.right - pad - avatarSize / 2;
                break;
            case PengramConfig.AVATAR_POS_BEFORE_NAME:
                avatarCx = bubbleLeft + dp(6) + avatarSize / 2;
                break;
            case PengramConfig.AVATAR_POS_AFTER_NAME:
                avatarCx = bubbleLeft + dp(26) + avatarSize / 2;
                break;
            case PengramConfig.AVATAR_POS_HIDE:
            default:
                avatarCx = card.centerX();
                break;
        }
        slotCenterX[index] = avatarCx;

        // пузырь
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.getColor(Theme.key_chat_inBubble));
        rect.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom);
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);

        // ник и строка текста
        final float nameLeft = bubbleLeft + dp(4)
                + (position == PengramConfig.AVATAR_POS_BEFORE_NAME ? avatarSize + dp(4) : 0);
        paint.setColor(accent);
        rect.set(nameLeft, bubbleTop + dp(4), Math.min(nameLeft + dp(16), bubbleRight - dp(4)), bubbleTop + dp(7));
        canvas.drawRoundRect(rect, dp(2), dp(2), paint);

        paint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messageTextIn), 80));
        rect.set(bubbleLeft + dp(4), bubbleTop + dp(11), bubbleRight - dp(5), bubbleTop + dp(14));
        canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        rect.set(bubbleLeft + dp(4), bubbleTop + dp(17), bubbleRight - dp(12), bubbleTop + dp(20));
        canvas.drawRoundRect(rect, dp(2), dp(2), paint);

        // пунктирное место под аватарку
        if (position == PengramConfig.AVATAR_POS_HIDE) {
            dashPaint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 140));
            canvas.drawCircle(avatarCx, avatarCy, avatarSize / 2 + dp(2), dashPaint);
            paint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 140));
            paint.setStrokeWidth(dp(1.5f));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(avatarCx - dp(5), avatarCy - dp(5), avatarCx + dp(5), avatarCy + dp(5), paint);
            paint.setStyle(Paint.Style.FILL);
        } else {
            dashPaint.setColor(ColorUtils.setAlphaComponent(accent, 120));
            canvas.drawCircle(avatarCx, avatarCy, avatarSize / 2 + dp(2), dashPaint);
        }
    }

    private String shortName(int position) {
        switch (position) {
            case PengramConfig.AVATAR_POS_RIGHT: return getString(R.string.PengramAvatarSlotRight);
            case PengramConfig.AVATAR_POS_HIDE: return getString(R.string.PengramAvatarSlotHide);
            case PengramConfig.AVATAR_POS_BEFORE_NAME: return getString(R.string.PengramAvatarSlotBeforeName);
            case PengramConfig.AVATAR_POS_AFTER_NAME: return getString(R.string.PengramAvatarSlotAfterName);
            case PengramConfig.AVATAR_POS_LEFT:
            default: return getString(R.string.PengramAvatarSlotLeft);
        }
    }

    private int slotAt(float x) {
        final float sw = slotWidth();
        if (sw <= 0) {
            return selected;
        }
        final int index = (int) ((x - dp(8)) / sw);
        return Math.max(0, Math.min(positions.length - 1, index));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float x = event.getX();
        final float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                final float dx = x - tokenX;
                final float dy = y - tokenY;
                dragging = Math.sqrt(dx * dx + dy * dy) < dp(34);
                if (dragging) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    targetX = x;
                    targetY = y;
                    hovered = slotAt(x);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    targetX = x;
                    targetY = y;
                    hovered = slotAt(x);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                final int slot = slotAt(x);
                if (dragging) {
                    dragging = false;
                    hovered = -1;
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                apply(slot);
                invalidate();
                return true;
            }
        }
        return true;
    }

    private void apply(int slot) {
        final int position = positions[Math.max(0, Math.min(positions.length - 1, slot))];
        selected = slot;
        if (PengramConfig.getGroupAvatarPos() != position) {
            PengramConfig.setGroupAvatarPos(position);
            if (PengramConfig.isVibrationEnabled()) {
                try {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Throwable ignore) {
                }
            }
            if (callback != null) {
                callback.onPositionChanged(position);
            }
        }
    }
}
