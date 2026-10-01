package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Stories.recorder.StoryEntry;

/**
 * Pengram: живое превью чата — так сообщения будут выглядеть с текущими настройками.
 * Рисуется с реальными обоями, аватаркой, именем и временем, то есть практически
 * скриншот будущего чата.
 */
public class PengramMessagePreviewView extends LinearLayout {

    private final ChatMessageCell[] cells = new ChatMessageCell[3];
    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;

    private Drawable backgroundDrawable;
    private Drawable shadowDrawable;

    public PengramMessagePreviewView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.currentAccount = UserConfig.selectedAccount;

        setWillNotDraw(false);
        setOrientation(VERTICAL);
        setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));

        Theme.createChatResources(context, false);
        shadowDrawable = Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow, resourcesProvider);

        for (int a = 0; a < cells.length; ++a) {
            cells[a] = new ChatMessageCell(context, currentAccount, false, null, resourcesProvider);
            cells[a].setFullyDraw(true);
            cells[a].isChat = a != 2;
            cells[a].setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return false;
                }
            });
            addView(cells[a], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        update();
    }

    public void update() {
        final int date = (int) (System.currentTimeMillis() / 1000) - 60 * 60;

        final MessageObject incoming = createMessage(LocaleController.getString(R.string.PengramPreviewIncoming), false, date, 1, false, false);
        final MessageObject deleted = createMessage(LocaleController.getString(R.string.PengramPreviewDeleted), false, date + 120, 2, true, false);
        final MessageObject edited = createMessage(LocaleController.getString(R.string.PengramPreviewEdited), true, date + 240, 3, false, true);

        bind(0, incoming);
        bind(1, deleted);
        bind(2, edited);
        invalidate();
    }

    private void bind(int index, MessageObject messageObject) {
        if (cells[index] == null || messageObject == null) {
            return;
        }
        cells[index].setMessageObject(messageObject, null, false, false, false);
        final float alpha = messageObject.pengramDeleted && PengramConfig.isFadingDeleted() ? 0.55f : 1f;
        cells[index].setAlpha(alpha);
        cells[index].requestLayout();
        cells[index].invalidate();
    }

    private MessageObject createMessage(String text, boolean out, int date, int id, boolean deleted, boolean edited) {
        try {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.message = text;
            message.date = date;
            message.dialog_id = 1;
            message.flags = 259;
            message.id = id;
            message.media = new TLRPC.TL_messageMediaEmpty();
            message.out = out;
            message.from_id = new TLRPC.TL_peerUser();
            message.peer_id = new TLRPC.TL_peerUser();
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            message.from_id.user_id = selfId;
            message.peer_id.user_id = 0;
            if (edited) {
                message.flags |= TLRPC.MESSAGE_FLAG_EDITED;
                message.edit_date = date + 30;
            }
            MessageObject messageObject = new MessageObject(currentAccount, message, true, false);
            messageObject.eventId = 1;
            messageObject.pengramDeleted = deleted;
            if (!out) {
                messageObject.forceAvatar = true;
                TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
                messageObject.customName = self != null ? UserObject.getUserName(self) : LocaleController.getString(R.string.AppName);
            }
            messageObject.resetLayout();
            return messageObject;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        for (int a = 0; a < cells.length; a++) {
            if (cells[a] != null) {
                cells[a].invalidate();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable newDrawable = Theme.getCachedWallpaperNonBlocking();
        if (Theme.wallpaperLoadTask != null) {
            invalidate();
        }
        if (newDrawable != null) {
            backgroundDrawable = newDrawable;
        }
        final Drawable drawable = backgroundDrawable;
        if (drawable != null) {
            drawable.setAlpha(255);
            if (drawable instanceof ColorDrawable || drawable instanceof GradientDrawable || drawable instanceof MotionBackgroundDrawable) {
                drawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                if (drawable instanceof BackgroundGradientDrawable) {
                    ((BackgroundGradientDrawable) drawable).drawExactBoundsSize(canvas, this);
                } else {
                    drawable.draw(canvas);
                }
            } else if (drawable instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                bitmapDrawable.setFilterBitmap(true);
                if (bitmapDrawable.getTileModeX() == Shader.TileMode.REPEAT) {
                    canvas.save();
                    float scale = 2.0f / AndroidUtilities.density;
                    canvas.scale(scale, scale);
                    drawable.setBounds(0, 0, (int) Math.ceil(getMeasuredWidth() / scale), (int) Math.ceil(getMeasuredHeight() / scale));
                } else {
                    int viewHeight = getMeasuredHeight();
                    float scaleX = (float) getMeasuredWidth() / (float) drawable.getIntrinsicWidth();
                    float scaleY = (float) (viewHeight) / (float) drawable.getIntrinsicHeight();
                    float scale = Math.max(scaleX, scaleY);
                    int width = (int) Math.ceil(drawable.getIntrinsicWidth() * scale);
                    int height = (int) Math.ceil(drawable.getIntrinsicHeight() * scale);
                    int x = (getMeasuredWidth() - width) / 2;
                    int y = (viewHeight - height) / 2;
                    canvas.save();
                    canvas.clipRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    drawable.setBounds(x, y, x + width, y + height);
                }
                drawable.draw(canvas);
                canvas.restore();
            } else {
                StoryEntry.drawBackgroundDrawable(canvas, drawable, getWidth(), getHeight());
            }
        }
        if (shadowDrawable != null) {
            shadowDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
            shadowDrawable.draw(canvas);
        }
    }
}
