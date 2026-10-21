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
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.FileLog;
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
    private final boolean sampleOnly;
    private String editorSentMessage;
    public interface EditorSelection { void onSelect(int element); }
    private EditorSelection editorSelection;
    private static final long SAMPLE_CHAT_ID = 700000000000L;

    private Drawable backgroundDrawable;
    private Drawable shadowDrawable;

    public PengramMessagePreviewView(Context context, Theme.ResourcesProvider resourcesProvider) {
        this(context, resourcesProvider, false);
    }

    /** Isolated editor version: native Telegram message cells, synthetic peers only. */
    public PengramMessagePreviewView(Context context, Theme.ResourcesProvider resourcesProvider, boolean sampleOnly) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.currentAccount = UserConfig.selectedAccount;
        this.sampleOnly = sampleOnly;

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

    public void setEditorSentMessage(String text) {
        editorSentMessage = text;
        if (sampleOnly) update();
    }

    public void setEditorSelection(EditorSelection selection) {
        editorSelection = selection;
    }

    public void update() {
        final int date = (int) (sampleOnly ? System.currentTimeMillis() / 1000
                : android.os.SystemClock.elapsedRealtime() / 1000) - 60 * 60;

        final MessageObject incoming = createMessage(LocaleController.getString(R.string.PengramPreviewIncoming), false, date, 1, false, false);
        final MessageObject deleted = createMessage(LocaleController.getString(R.string.PengramPreviewDeleted), false, date + 120, 2, true, false);
        final MessageObject edited = createMessage(sampleOnly && editorSentMessage != null && !editorSentMessage.isEmpty()
                ? editorSentMessage : LocaleController.getString(R.string.PengramPreviewEdited),
                true, date + 240, 3, false, true);

        bind(0, incoming);
        bind(1, deleted);
        bind(2, edited);
        invalidate();
    }

    public boolean isEditorReady() {
        return !sampleOnly || cells[0].getMessageObject() != null
                && cells[1].getMessageObject() != null && cells[2].getMessageObject() != null;
    }

    private void bind(int index, MessageObject messageObject) {
        if (cells[index] == null || messageObject == null) {
            return;
        }
        try {
            cells[index].setMessageObject(messageObject, null, false, false, false);
            final float alpha = messageObject.pengramDeleted && PengramConfig.isFadingDeleted() ? 0.55f : 1f;
            cells[index].setAlpha(alpha);
            cells[index].requestLayout();
            cells[index].invalidate();
        } catch (RuntimeException error) {
            // The sample must never crash Telegram if an upstream message-cell
            // implementation no longer accepts a synthetic peer.
            FileLog.e(error);
        }
    }

    private MessageObject createMessage(String text, boolean out, int date, int id, boolean deleted, boolean edited) {
        try {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.message = text;
            message.date = date;
            message.dialog_id = sampleOnly ? SAMPLE_CHAT_ID : 1;
            message.flags = 259;
            message.id = id;
            message.media = new TLRPC.TL_messageMediaEmpty();
            message.out = out;
            message.from_id = new TLRPC.TL_peerUser();
            message.peer_id = new TLRPC.TL_peerUser();
            final long selfId = sampleOnly ? SAMPLE_CHAT_ID : UserConfig.getInstance(currentAccount).getClientUserId();
            message.from_id.user_id = selfId;
            message.peer_id.user_id = sampleOnly ? SAMPLE_CHAT_ID : 0;
            if (edited) {
                message.flags |= TLRPC.MESSAGE_FLAG_EDITED;
                message.edit_date = date + 30;
            }
            MessageObject messageObject = new MessageObject(currentAccount, message, true, false);
            messageObject.eventId = 1;
            messageObject.pengramDeleted = deleted;
            if (!out) {
                messageObject.forceAvatar = true;
                TLRPC.User self = sampleOnly ? null : UserConfig.getInstance(currentAccount).getCurrentUser();
                messageObject.customName = sampleOnly ? LocaleController.getString(R.string.PengramEditorName)
                        : self != null ? UserObject.getUserName(self) : LocaleController.getString(R.string.AppName);
                if (sampleOnly || self == null) {
                    // запасной вариант: рисуем хотя бы цветную «заглушку» с инициалами
                    final AvatarDrawable avatarDrawable = new AvatarDrawable();
                    avatarDrawable.setInfo(selfId, messageObject.customName, null);
                    messageObject.customAvatarDrawable = avatarDrawable;
                }
            }
            messageObject.resetLayout();
            return messageObject;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (sampleOnly) PengramConfig.beginVisualPreviewRead();
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } finally {
            if (sampleOnly) PengramConfig.endVisualPreviewRead();
        }
    }

    /**
     * В реальном чате аватарку рисует не сама ячейка, а список сообщений — поэтому
     * в превью её тоже рисуем мы, ровно по той же геометрии (слева, 42dp, по низу пузыря).
     */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (sampleOnly) PengramConfig.beginVisualPreviewRead();
        try {
        super.dispatchDraw(canvas);
        for (int a = 0; a < cells.length; a++) {
            final ChatMessageCell cell = cells[a];
            if (cell == null || !cell.isAvatarVisible) {
                continue;
            }
            final ImageReceiver avatarImage = cell.getAvatarImage();
            if (avatarImage == null) {
                continue;
            }
            final float y = cell.getY() + cell.getPaddingTop() + cell.getLayoutHeight() - AndroidUtilities.dp(44);
            final float x = PengramConfig.getGroupAvatarPos() == PengramConfig.AVATAR_POS_RIGHT
                    ? getMeasuredWidth() - AndroidUtilities.dp(48)
                    : AndroidUtilities.dp(6);
            avatarImage.setImageCoords(x, y, AndroidUtilities.dp(42), AndroidUtilities.dp(42));
            avatarImage.setAlpha(cell.getAlpha());
            avatarImage.setVisible(true, false);
            avatarImage.draw(canvas);
        }
        } finally {
            if (sampleOnly) PengramConfig.endVisualPreviewRead();
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** Select only sample message cells; never forward taps to a real chat. */
    private void selectEditorCell(float y) {
        if (!sampleOnly || editorSelection == null) return;
        for (int i = 0; i < cells.length; i++) {
            if (y >= cells[i].getTop() && y < cells[i].getBottom()) {
                editorSelection.onSelect(i == 1 ? PengramScreenMockView.EL_MARK
                        : PengramScreenMockView.EL_BUBBLE);
                return;
            }
        }
    }

    /** превью не реагирует на нажатия: иначе пузырь подсвечивался синим «выделением» */
    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        return true;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (sampleOnly && event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            performClick();
            selectEditorCell(event.getY());
        }
        return true;
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
