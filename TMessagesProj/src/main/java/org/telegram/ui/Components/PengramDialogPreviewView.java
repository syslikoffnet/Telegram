package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: живой кусочек списка чатов для настроек.
 * Показывает ровно то, что получится: форму миниатюры, имя, строку сообщения,
 * время и счётчик непрочитанных — чтобы выбирать глазами, а не наугад.
 */
public class PengramDialogPreviewView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final ImageReceiver avatarReceiver = new ImageReceiver(this);
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final AvatarDrawable secondDrawable = new AvatarDrawable();

    private String selfName;

    public PengramDialogPreviewView(Context context) {
        super(context);
        namePaint.setTextSize(dp(16));
        namePaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(dp(15));
        datePaint.setTextSize(dp(13));
        badgePaint.setTextSize(dp(12));
        badgePaint.setTypeface(AndroidUtilities.bold());
        badgePaint.setTextAlign(Paint.Align.CENTER);

        final int account = UserConfig.selectedAccount;
        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        selfName = user != null ? UserObject.getUserName(user) : getString(R.string.AppName);
        avatarDrawable.setInfo(account, user);
        avatarReceiver.setForUserOrChat(user, avatarDrawable);
        secondDrawable.setInfo(7, getString(R.string.PengramPreviewChatName), null);
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
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(144), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getMeasuredWidth();
        paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        canvas.drawRect(0, 0, w, getMeasuredHeight(), paint);

        drawRow(canvas, dp(8), true);
        paint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_divider), 90));
        canvas.drawRect(dp(72), dp(76), w, dp(76) + 1, paint);
        drawRow(canvas, dp(80), false);
    }

    private void drawRow(Canvas canvas, float top, boolean self) {
        final int w = getMeasuredWidth();
        final float size = dp(54);
        final float left = dp(10);
        final int radius = PengramConfig.dialogAvatarRadius(dp(27));

        if (self) {
            avatarReceiver.setImageCoords(left, top, size, size);
            avatarReceiver.setRoundRadius(radius);
            avatarReceiver.draw(canvas);
        } else {
            secondDrawable.setRoundRadius(radius);
            secondDrawable.setBounds((int) left, (int) top, (int) (left + size), (int) (top + size));
            secondDrawable.draw(canvas);
        }

        final float textLeft = left + size + dp(12);
        namePaint.setColor(Theme.getColor(Theme.key_chats_name));
        canvas.drawText(self ? cut(selfName, dp(150), namePaint) : getString(R.string.PengramPreviewChatName),
                textLeft, top + dp(21), namePaint);

        textPaint.setColor(Theme.getColor(Theme.key_chats_message));
        canvas.drawText(cut(getString(self ? R.string.PengramPreviewIncoming : R.string.PengramPreviewEdited),
                w - textLeft - dp(60), textPaint), textLeft, top + dp(44), textPaint);

        datePaint.setColor(Theme.getColor(Theme.key_chats_date));
        final String date = self ? "19:04" : "18:52";
        canvas.drawText(date, w - dp(14) - datePaint.measureText(date), top + dp(20), datePaint);

        if (!self) {
            paint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
            final float badgeWidth = dp(22);
            rect.set(w - dp(14) - badgeWidth, top + dp(32), w - dp(14), top + dp(32) + dp(22));
            canvas.drawRoundRect(rect, dp(11), dp(11), paint);
            badgePaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));
            canvas.drawText("3", rect.centerX(), rect.centerY() + dp(4.5f), badgePaint);
        }
    }

    private String cut(String text, float maxWidth, TextPaint paint) {
        if (text == null) {
            return "";
        }
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        int end = text.length();
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
