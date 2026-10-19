package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * Pengram: schematic chat preview with sample labels; it never reads real messages.
 * Only the avatar and chat button bar can be moved into supported slots.
 * Changes made here are saved to the shared appearance preferences.
 */
public class PengramScreenMockView extends View {

    public interface Listener {
        /** выбран другой элемент (или снято выделение) */
        void onSelection(Element element);

        /** настройка изменилась — панель снизу должна обновиться */
        void onChanged();
    }

    /** что можно трогать на макете */
    public static class Element {
        public final int id;
        public final int titleRes;
        public final RectF bounds = new RectF();
        public boolean draggable;
        public boolean hideable;
        public boolean sizeable;

        Element(int id, int titleRes) {
            this.id = id;
            this.titleRes = titleRes;
        }
    }

    public static final int EL_TITLE = 1;        // заголовок в шапке
    public static final int EL_AVATAR = 2;       // аватар собеседника в группе
    public static final int EL_BUBBLE = 3;       // пузырь сообщения
    public static final int EL_MARK = 4;         // метка удалённого
    public static final int EL_MENU = 5;         // панель кнопок чата
    public static final int EL_TABS = 6;         // нижние вкладки

    /** куда можно перетащить аватар */
    private static final int[] AVATAR_SLOTS = new int[]{
            PengramConfig.AVATAR_POS_LEFT,
            PengramConfig.AVATAR_POS_BEFORE_NAME,
            PengramConfig.AVATAR_POS_AFTER_NAME,
            PengramConfig.AVATAR_POS_RIGHT,
    };

    private final ArrayList<Element> elements = new ArrayList<>();
    private final ArrayList<RectF> dropSlots = new ArrayList<>();
    private final ArrayList<Integer> dropValues = new ArrayList<>();
    private final RectF[] tabRects = new RectF[4];
    private final String[] menuLabels = new String[4];
    private int menuLabelCount;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private android.graphics.drawable.Drawable deletedIcon;
    private int deletedIconResource = -1;
    private int deletedIconTint;

    private Element selected;
    private Element dragging;
    private float dragX, dragY, downX, downY;
    private int hoverSlot = -1;
    private long downTime;
    private boolean longPressed;
    private float selectionPhase;

    private Listener listener;
    private boolean embedded;
    private String sampleMessage;

    /** A local-only sample bubble; never forwarded to a Telegram dialog. */
    public void setSampleMessage(String text) {
        sampleMessage = text;
        invalidate();
    }

    /** The enclosing visual editor supplies its own navigation bar. */
    public void setEmbedded(boolean embedded) {
        this.embedded = embedded;
        invalidate();
    }

    public PengramScreenMockView(Context context) {
        super(context);
        text.setTextSize(dp(13));
        small.setTextSize(dp(10));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(dp(1.5f));
        dash.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));

        add(EL_TITLE, R.string.PengramEditorTitle, false, false, false);
        add(EL_AVATAR, R.string.PengramEditorAvatar, true, true, false);
        add(EL_BUBBLE, R.string.PengramEditorBubble, false, false, true);
        add(EL_MARK, R.string.PengramEditorMark, false, false, false);
        add(EL_MENU, R.string.PengramEditorMenu, true, true, false);
        add(EL_TABS, R.string.PengramEditorTabs, false, false, false);
        updateMenuLabels();
    }

    private void add(int id, int titleRes, boolean draggable, boolean hideable, boolean sizeable) {
        final Element element = new Element(id, titleRes);
        element.draggable = draggable;
        element.hideable = hideable;
        element.sizeable = sizeable;
        elements.add(element);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Element getSelected() {
        return selected;
    }

    public void select(Element element) {
        selected = element;
        selectionPhase = 0;
        invalidate();
        if (listener != null) {
            listener.onSelection(element);
        }
    }

    public Element elementById(int id) {
        for (Element element : elements) {
            if (element.id == id) {
                return element;
            }
        }
        return null;
    }

    private int editorBubbleRadius = -1;
    private int editorFontSize = -1;

    /** These values are for the synthetic chat only, never the real message renderer. */
    public void setEditorMessageSize(int radius, int fontSize) {
        editorBubbleRadius = radius;
        editorFontSize = fontSize;
        invalidate();
    }

    public void refresh() {
        PengramConfig.withVisualPreview(this::updateMenuLabels);
        invalidate();
    }

    private void updateMenuLabels() {
        menuLabelCount = 0;
        for (int id : PengramConfig.getChatItemsOrder()) {
            if (menuLabelCount == menuLabels.length) break;
            if (!PengramConfig.isChatItemHidden(id)
                    && PengramConfig.getChatItemPlacement(id) == PengramConfig.CHAT_PLACE_ISLAND) {
                menuLabels[menuLabelCount++] = getString(PengramConfig.getChatItemTitle(id));
            }
        }
    }

    // --------------------------------------------------------------- рисуем

    @Override
    protected void onDraw(Canvas canvas) {
        PengramConfig.beginVisualPreviewRead();
        try {
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        selectionPhase = Math.min(1f, selectionPhase + 0.08f);

        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int panel = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int gray = Theme.getColor(Theme.key_windowBackgroundGray);
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int hintColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);

        // A standalone constructor shows the phone frame. Inside the full-screen
        // editor the chat occupies the entire available width without a nested phone.
        rect.set(embedded ? 0 : dp(10), embedded ? 0 : dp(6),
                embedded ? width : width - dp(10), embedded ? height : height - dp(6));
        fill.setColor(embedded ? panel : gray);
        if (embedded) canvas.drawRect(rect, fill);
        else canvas.drawRoundRect(rect, dp(22), dp(22), fill);

        final float left = rect.left;
        final float right = rect.right;
        final boolean menuTop = PengramConfig.getChatMenuPosition() == PengramConfig.MENU_POS_TOP;

        // ---------- шапка ----------
        final float headerBottom = rect.top + dp(embedded ? 4 : 52);
        final Element title = elementById(EL_TITLE);
        if (!embedded) {
        fill.setColor(panel);
        canvas.drawRect(left, rect.top + dp(16), right, headerBottom, fill);
        rect.set(left, rect.top, right, rect.top + dp(34));
        canvas.drawRoundRect(rect, dp(22), dp(22), fill);

        fill.setColor(ColorUtils.setAlphaComponent(hintColor, 120));
        canvas.drawCircle(left + dp(22), headerBottom - dp(18), dp(4), fill);           // «назад»
        fill.setColor(ColorUtils.setAlphaComponent(accent, 90));
        canvas.drawCircle(left + dp(48), headerBottom - dp(18), dp(13), fill);          // аватар чата

        text.setColor(textColor);
        text.setTypeface(AndroidUtilities.bold());
        final String titleText = headerTitle();
        canvas.drawText(titleText, left + dp(68), headerBottom - dp(20), text);
        small.setColor(hintColor);
        canvas.drawText(getString(R.string.PengramEditorOnline), left + dp(68), headerBottom - dp(7), small);
        title.bounds.set(left + dp(64), headerBottom - dp(34), left + dp(64) + Math.max(dp(90), text.measureText(titleText) + dp(10)), headerBottom - dp(2));

        fill.setColor(ColorUtils.setAlphaComponent(hintColor, 120));
        for (int a = 0; a < 3; ++a) {
            canvas.drawCircle(right - dp(22), headerBottom - dp(25) + a * dp(7), dp(1.8f), fill);
        }

        } else {
            title.bounds.setEmpty();
        }
        float y = headerBottom + dp(10);

        // ---------- панель кнопок чата (сверху) ----------
        final Element menu = elementById(EL_MENU);
        final boolean menuEnabled = PengramConfig.isChatMenuEnabled();
        if (menuEnabled && menuTop && dragging != menu) {
            y = drawMenuRow(canvas, left, right, y, accent, panel, menu);
        }

        // ---------- сообщения ----------
        // Zero is a real bubble-radius option; never round it up in the preview.
        final float radius = dp(Math.max(0, Math.min(17, editorBubbleRadius >= 0 ? editorBubbleRadius : SharedConfig.bubbleRadius)));
        final float fontScale = Math.max(0.75f, Math.min(2f,
                (editorFontSize >= 0 ? editorFontSize : SharedConfig.fontSize) / 16f * getResources().getConfiguration().fontScale));
        final int avatarPos = PengramConfig.getGroupAvatarPos();
        final boolean avatarVisible = avatarPos != PengramConfig.AVATAR_POS_HIDE;
        final Element avatar = elementById(EL_AVATAR);

        final float bubbleLeft = left + dp(avatarVisible && avatarPos == PengramConfig.AVATAR_POS_LEFT ? 50 : 14);
        final float bubbleTop = y;
        final float bubbleHeight = dp(52) * fontScale;
        final float bubbleRight = Math.min(right - dp(60), bubbleLeft + dp(190));
        rect.set(bubbleLeft, bubbleTop, bubbleRight, bubbleTop + bubbleHeight);
        fill.setColor(Theme.getColor(Theme.key_chat_inBubble));
        canvas.drawRoundRect(rect, radius, radius, fill);

        final Element bubble = elementById(EL_BUBBLE);
        bubble.bounds.set(rect);

        // имя и аватар внутри строки
        text.setTextSize(dp(12) * fontScale);
        text.setColor(accent);
        float nameX = rect.left + dp(10);
        if (avatarVisible && avatarPos == PengramConfig.AVATAR_POS_BEFORE_NAME) {
            drawAvatar(canvas, nameX + dp(7), rect.top + dp(13), dp(7), accent, avatar);
            nameX += dp(18);
        }
        canvas.drawText(getString(R.string.PengramEditorName), nameX, rect.top + dp(17), text);
        final float nameWidth = text.measureText(getString(R.string.PengramEditorName));
        if (avatarVisible && avatarPos == PengramConfig.AVATAR_POS_AFTER_NAME) {
            drawAvatar(canvas, nameX + nameWidth + dp(11), rect.top + dp(13), dp(7), accent, avatar);
        }
        if (avatarVisible && avatarPos == PengramConfig.AVATAR_POS_LEFT) {
            drawAvatar(canvas, left + dp(30), rect.bottom - dp(14), dp(13), accent, avatar);
        }
        if (avatarVisible && avatarPos == PengramConfig.AVATAR_POS_RIGHT) {
            drawAvatar(canvas, rect.right + dp(18), rect.bottom - dp(14), dp(13), accent, avatar);
        }
        if (!avatarVisible) {
            avatar.bounds.set(left + dp(18), rect.top, left + dp(44), rect.top + dp(26));
        }

        text.setTextSize(dp(12.5f) * fontScale);
        text.setTypeface(Typeface.DEFAULT);
        text.setColor(Theme.getColor(Theme.key_chat_messageTextIn));
        canvas.drawText(getString(R.string.PengramEditorMessage), rect.left + dp(10), rect.top + dp(36) * fontScale, text);

        y = rect.bottom + dp(10);

        // исходящее + метка удалённого
        final float outRight = right - dp(14);
        final float outLeft = Math.max(left + dp(80), outRight - dp(150));
        rect.set(outLeft, y, outRight, y + dp(40) * fontScale);
        fill.setColor(Theme.getColor(Theme.key_chat_outBubble));
        canvas.drawRoundRect(rect, radius, radius, fill);
        text.setColor(Theme.getColor(Theme.key_chat_messageTextOut));
        final String outgoing = sampleMessage == null || sampleMessage.isEmpty()
                ? getString(R.string.PengramEditorMessageOut) : sampleMessage;
        canvas.drawText(TextUtils.ellipsize(outgoing, text, Math.max(0, rect.width() - dp(20)),
                TextUtils.TruncateAt.END).toString(), rect.left + dp(10), rect.top + dp(24) * fontScale, text);

        final Element mark = elementById(EL_MARK);
        small.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messageTextOut), 180));
        final String markText = getString(R.string.PengramEditorDeleted);
        final int markIcon = PengramConfig.getDeletedMarkIcon();
        float markX = rect.left + dp(10);
        if (deletedIconResource != markIcon) {
            deletedIconResource = markIcon;
            deletedIcon = null;
            deletedIconTint = 0;
            if (markIcon != 0) {
                try {
                    deletedIcon = getContext().getResources().getDrawable(markIcon).mutate();
                } catch (Exception ignore) {
                    // Preview remains usable if a themed drawable is unavailable.
                }
            }
        }
        if (deletedIcon != null) {
            if (deletedIconTint != small.getColor()) {
                deletedIconTint = small.getColor();
                deletedIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(deletedIconTint,
                        android.graphics.PorterDuff.Mode.SRC_IN));
            }
            deletedIcon.setBounds((int) markX, (int) (rect.bottom - dp(17)),
                    (int) (markX + dp(12)), (int) (rect.bottom - dp(5)));
            deletedIcon.draw(canvas);
            markX += dp(15);
        }
        small.setAlpha(PengramConfig.getDeletedMark() == PengramConfig.MARK_NONE ? 90 : 255);
        canvas.drawText(markText, markX, rect.bottom - dp(6), small);
        small.setAlpha(255);
        mark.bounds.set(rect.left + dp(6), rect.bottom - dp(19), markX + small.measureText(markText) + dp(4), rect.bottom - dp(1));

        // ---------- панель кнопок чата (снизу) ----------
        final float inputTop = getMeasuredHeight() - dp(6) - (embedded ? 0 : dp(54) + dp(44));
        if (menuEnabled && !menuTop && dragging != menu) {
            drawMenuRow(canvas, left, right, inputTop - dp(42), accent, panel, menu);
        } else if (!menuEnabled) {
            menu.bounds.set(left + dp(14), inputTop - dp(40), left + dp(120), inputTop - dp(8));
        }

        // The parent editor provides a real, local-only EditText when embedded.
        if (!embedded) {
        // ---------- строка ввода ----------
        fill.setColor(panel);
        canvas.drawRect(left, inputTop, right, inputTop + dp(44), fill);
        rect.set(left + dp(14), inputTop + dp(8), right - dp(52), inputTop + dp(36));
        fill.setColor(ColorUtils.blendARGB(panel, gray, 0.75f));
        canvas.drawRoundRect(rect, dp(14), dp(14), fill);
        small.setColor(hintColor);
        canvas.drawText(getString(R.string.PengramEditorInput), rect.left + dp(10), rect.centerY() + dp(3.5f), small);
        fill.setColor(accent);
        canvas.drawCircle(right - dp(30), inputTop + dp(22), dp(14), fill);
        }

        // ---------- нижние вкладки ----------
        final float barTop = getMeasuredHeight() - dp(6) - dp(54);
        if (!embedded) {
        fill.setColor(panel);
        rect.set(left, barTop, right, getMeasuredHeight() - dp(6));
        canvas.drawRoundRect(rect, dp(22), dp(22), fill);
        canvas.drawRect(left, barTop, right, barTop + dp(20), fill);

        final Element tabs = elementById(EL_TABS);
        tabs.bounds.set(left, barTop, right, getMeasuredHeight() - dp(6));
        drawTabs(canvas, left, right, barTop, accent, hintColor);
        } else {
            elementById(EL_TABS).bounds.setEmpty();
            for (RectF tabRect : tabRects) {
                if (tabRect != null) tabRect.setEmpty();
            }
        }

        // ---------- точки, куда можно положить ----------
        dropSlots.clear();
        dropValues.clear();
        if (dragging != null) {
            prepareSlots(dragging, left, right, bubbleTop, bubbleHeight, headerBottom, inputTop, barTop);
            for (int a = 0; a < dropSlots.size(); ++a) {
                final RectF slot = dropSlots.get(a);
                final boolean hover = a == hoverSlot;
                dash.setColor(ColorUtils.setAlphaComponent(accent, hover ? 255 : 120));
                if (hover) {
                    fill.setColor(ColorUtils.setAlphaComponent(accent, 40));
                    canvas.drawRoundRect(slot, dp(8), dp(8), fill);
                }
                canvas.drawRoundRect(slot, dp(8), dp(8), dash);
            }
        }

        // ---------- выделение ----------
        if (selected != null && dragging == null) {
            final float grow = dp(4) * (1f + 0.15f * (float) Math.sin(SystemClock.elapsedRealtime() / 320.0));
            rect.set(selected.bounds.left - grow, selected.bounds.top - grow,
                    selected.bounds.right + grow, selected.bounds.bottom + grow);
            stroke.setColor(ColorUtils.setAlphaComponent(accent, (int) (220 * selectionPhase)));
            canvas.drawRoundRect(rect, dp(10), dp(10), stroke);
            // пульс выделения крутим только пока экран реально на виду
            if (isShown()) {
                postInvalidateOnAnimation();
            }
        }

        // ---------- то, что сейчас в руке ----------
        if (dragging != null) {
            final float w = Math.max(dp(40), dragging.bounds.width());
            final float h = Math.max(dp(26), dragging.bounds.height());
            rect.set(dragX - w / 2f, dragY - h / 2f, dragX + w / 2f, dragY + h / 2f);
            fill.setColor(ColorUtils.setAlphaComponent(accent, 70));
            canvas.drawRoundRect(rect, dp(10), dp(10), fill);
            stroke.setColor(accent);
            canvas.drawRoundRect(rect, dp(10), dp(10), stroke);
            small.setColor(textColor);
            final String label = getString(dragging.titleRes);
            canvas.drawText(TextUtils.ellipsize(label, small, w - dp(8), TextUtils.TruncateAt.END).toString(),
                    rect.left + dp(4), rect.centerY() + dp(3.5f), small);
            if (isShown()) {
                postInvalidateOnAnimation();
            }
        }
        } finally {
            PengramConfig.endVisualPreviewRead();
        }
    }

    private float drawMenuRow(Canvas canvas, float left, float right, float y, int accent, int panel, Element menu) {
        rect.set(left + dp(14), y, right - dp(14), y + dp(34));
        fill.setColor(ColorUtils.setAlphaComponent(accent, 30));
        canvas.drawRoundRect(rect, dp(12), dp(12), fill);
        small.setTextSize(dp(9));
        small.setTextAlign(Paint.Align.CENTER);
        small.setColor(accent);
        if (menuLabelCount == 0) {
            canvas.drawText("+", rect.centerX(), rect.centerY() + dp(3), small);
        } else {
            final float step = rect.width() / menuLabelCount;
            for (int a = 0; a < menuLabelCount; a++) {
                final float cx = rect.left + step * (a + .5f);
                canvas.drawText(TextUtils.ellipsize(menuLabels[a], small, step - dp(8), TextUtils.TruncateAt.END).toString(),
                        cx, rect.centerY() + dp(3), small);
            }
        }
        small.setTextSize(dp(10));
        small.setTextAlign(Paint.Align.LEFT);
        menu.bounds.set(rect);
        return rect.bottom + dp(10);
    }

    private void drawAvatar(Canvas canvas, float cx, float cy, float radius, int accent, Element avatar) {
        fill.setColor(ColorUtils.setAlphaComponent(accent, 150));
        canvas.drawCircle(cx, cy, radius, fill);
        avatar.bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    private void drawTabs(Canvas canvas, float left, float right, float barTop, int accent, int hintColor) {
        // The real bottom bar has four slots: Calls and Settings share one slot.
        final boolean calls = UserConfig.getInstance(UserConfig.selectedAccount).showCallsTab;
        final String[] titles = new String[]{
                getString(R.string.PengramTabChats), getString(R.string.PengramTabContacts),
                getString(calls ? R.string.PengramTabCalls : R.string.PengramTabSettings),
                getString(R.string.PengramTabProfile)};
        final String[] keys = new String[]{null, PengramConfig.KEY_TAB_CONTACTS,
                calls ? PengramConfig.KEY_TAB_CALLS : PengramConfig.KEY_TAB_SETTINGS,
                PengramConfig.KEY_TAB_PROFILE};
        final float step = (right - left) / titles.length;
        small.setTextAlign(Paint.Align.CENTER);
        for (int a = 0; a < titles.length; ++a) {
            final float cx = left + step * (a + 0.5f);
            final boolean hidden = keys[a] != null && PengramConfig.getBool(keys[a], false);
            if (tabRects[a] == null) {
                tabRects[a] = new RectF();
            }
            tabRects[a].set(cx - step / 2f, barTop + dp(6), cx + step / 2f, barTop + dp(48));
            final int alpha = hidden ? 60 : 255;
            fill.setColor(ColorUtils.setAlphaComponent(a == 0 ? accent : hintColor, alpha));
            canvas.drawRoundRect(cx - dp(9), barTop + dp(14), cx + dp(9), barTop + dp(26), dp(4), dp(4), fill);
            small.setColor(ColorUtils.setAlphaComponent(a == 0 ? accent : hintColor, alpha));
            canvas.drawText(TextUtils.ellipsize(titles[a], small, step - dp(2), TextUtils.TruncateAt.END).toString(),
                    cx, barTop + dp(40), small);
            if (hidden) {
                dash.setColor(ColorUtils.setAlphaComponent(hintColor, 90));
                canvas.drawRoundRect(tabRects[a].left + dp(3), tabRects[a].top, tabRects[a].right - dp(3), tabRects[a].bottom - dp(4), dp(8), dp(8), dash);
            }
        }
        small.setTextAlign(Paint.Align.LEFT);
    }

    /** куда можно уронить то, что сейчас в руке */
    private void prepareSlots(Element element, float left, float right, float bubbleTop, float bubbleHeight,
                              float headerBottom, float inputTop, float barTop) {
        if (element.id == EL_AVATAR) {
            final float top = bubbleTop;
            dropSlots.add(new RectF(left + dp(16), top + dp(6), left + dp(46), top + dp(36)));
            dropValues.add(PengramConfig.AVATAR_POS_LEFT);
            dropSlots.add(new RectF(left + dp(56), top + dp(2), left + dp(92), top + dp(26)));
            dropValues.add(PengramConfig.AVATAR_POS_BEFORE_NAME);
            dropSlots.add(new RectF(left + dp(120), top + dp(2), left + dp(156), top + dp(26)));
            dropValues.add(PengramConfig.AVATAR_POS_AFTER_NAME);
            dropSlots.add(new RectF(right - dp(46), top + dp(6), right - dp(16), top + dp(36)));
            dropValues.add(PengramConfig.AVATAR_POS_RIGHT);
        } else if (element.id == EL_MENU) {
            dropSlots.add(new RectF(left + dp(14), headerBottom + dp(8), right - dp(14), headerBottom + dp(44)));
            dropValues.add(PengramConfig.MENU_POS_TOP);
            dropSlots.add(new RectF(left + dp(14), inputTop - dp(44), right - dp(14), inputTop - dp(8)));
            dropValues.add(PengramConfig.MENU_POS_BOTTOM);
        }
    }

    private String headerTitle() {
        switch (PengramConfig.getTitleMode()) {
            case PengramConfig.TITLE_MODE_PENGRAM: return "Pengram";
            case PengramConfig.TITLE_MODE_CHATS: return getString(R.string.PengramTitleChats);
            case PengramConfig.TITLE_MODE_NAME: return getString(R.string.PengramEditorName);
            case PengramConfig.TITLE_MODE_USERNAME: return "@username";
            case PengramConfig.TITLE_MODE_CUSTOM:
                final String custom = PengramConfig.getTitleCustom();
                return TextUtils.isEmpty(custom) ? "Pengram" : custom;
            default: return "Telegram";
        }
    }

    // ------------------------------------------------------------- касания

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        PengramConfig.beginVisualPreviewRead();
        try {
        final float x = event.getX();
        final float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                downTime = SystemClock.elapsedRealtime();
                longPressed = false;
                final Element hit = elementAt(x, y);
                if (hit != null) {
                    select(hit);
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                postDelayed(longPressCheck, ViewConfiguration.getLongPressTimeout());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                dragX = x;
                dragY = y;
                if (dragging != null) {
                    hoverSlot = slotAt(x, y);
                    invalidate();
                } else if (Math.hypot(x - downX, y - downY) > dp(12)) {
                    removeCallbacks(longPressCheck);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressCheck);
                if (dragging != null) {
                    final int slot = event.getAction() == MotionEvent.ACTION_UP ? slotAt(x, y) : -1;
                    final boolean changed = slot >= 0 && slot < dropValues.size()
                            && applyDrop(dragging, dropValues.get(slot));
                    dragging = null;
                    hoverSlot = -1;
                    dropSlots.clear();
                    dropValues.clear();
                    invalidate();
                    if (changed && listener != null) {
                        listener.onChanged();
                    }
                    return true;
                }
                if (!longPressed && event.getAction() == MotionEvent.ACTION_UP
                        && Math.hypot(x - downX, y - downY) < dp(12)) {
                    handleTap(x, y);
                }
                return true;
            }
        }
        return true;
        } finally {
            PengramConfig.endVisualPreviewRead();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPressCheck);
        dragging = null;
        hoverSlot = -1;
        dropSlots.clear();
        dropValues.clear();
        super.onDetachedFromWindow();
    }

    private final Runnable longPressCheck = () -> {
        if (!isAttachedToWindow()) {
            return;
        }
        final Element hit = elementAt(downX, downY);
        if (hit == null || !hit.draggable) {
            return;
        }
        longPressed = true;
        dragging = hit;
        dragX = downX;
        dragY = downY;
        try {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, 2);
        } catch (Exception ignore) {
        }
        invalidate();
    };

    private void handleTap(float x, float y) {
        // по вкладкам — прямое включение и выключение
        for (int a = 1; a < tabRects.length; ++a) {
            if (tabRects[a] != null && tabRects[a].contains(x, y)) {
                final String key = a == 1 ? PengramConfig.KEY_TAB_CONTACTS
                        : a == 2 ? (UserConfig.getInstance(UserConfig.selectedAccount).showCallsTab
                                ? PengramConfig.KEY_TAB_CALLS : PengramConfig.KEY_TAB_SETTINGS)
                        : PengramConfig.KEY_TAB_PROFILE;
                PengramConfig.setBool(key, !PengramConfig.getBool(key, false));
                tap();
                if (listener != null) {
                    listener.onChanged();
                }
                return;
            }
        }
        final Element hit = elementAt(x, y);
        if (hit == null) {
            select(null);
            return;
        }
        if (hit.id == EL_TITLE) {
            PengramConfig.setTitleMode((PengramConfig.getTitleMode() + 1) % 6);
            tap();
            if (listener != null) {
                listener.onChanged();
            }
        } else if (hit.id == EL_MARK) {
            PengramConfig.setDeletedMark((PengramConfig.getDeletedMark() + 1) % 5);
            tap();
            if (listener != null) {
                listener.onChanged();
            }
        }
    }

    private void tap() {
        try {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, 2);
        } catch (Exception ignore) {
        }
        invalidate();
    }

    private boolean applyDrop(Element element, int value) {
        if (element.id == EL_AVATAR) {
            if (PengramConfig.getGroupAvatarPos() == value) {
                return false;
            }
            PengramConfig.setGroupAvatarPos(value);
        } else if (element.id == EL_MENU) {
            if (PengramConfig.getChatMenuPosition() == value) {
                return false;
            }
            PengramConfig.setChatMenuPosition(value);
        } else {
            return false;
        }
        tap();
        return true;
    }

    private int slotAt(float x, float y) {
        int best = -1;
        float closest = Float.MAX_VALUE;
        for (int a = 0; a < dropSlots.size(); ++a) {
            final RectF slot = dropSlots.get(a);
            if (x >= slot.left - dp(16) && x <= slot.right + dp(16)
                    && y >= slot.top - dp(16) && y <= slot.bottom + dp(16)) {
                final float distance = (x - slot.centerX()) * (x - slot.centerX())
                        + (y - slot.centerY()) * (y - slot.centerY());
                if (distance < closest) {
                    closest = distance;
                    best = a;
                }
            }
        }
        return best;
    }

    private Element elementAt(float x, float y) {
        Element best = null;
        float bestArea = Float.MAX_VALUE;
        for (Element element : elements) {
            final RectF b = element.bounds;
            if (b.width() <= 0) {
                continue;
            }
            if (x >= b.left - dp(8) && x <= b.right + dp(8) && y >= b.top - dp(8) && y <= b.bottom + dp(8)) {
                final float area = b.width() * b.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = element;
                }
            }
        }
        return best;
    }

    /** список доступных позиций для выбранного элемента — для панели снизу */
    public static int[] slotsFor(int elementId) {
        return elementId == EL_AVATAR ? AVATAR_SLOTS : null;
    }
}
