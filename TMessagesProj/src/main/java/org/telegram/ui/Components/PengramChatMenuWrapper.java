package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengram: остров в «трёх точках» чата.
 * Что именно внутри — решает пользователь: любой наш пункт можно перетащить
 * из основного меню в остров и обратно (Настройки → Pengram → Пункты меню чата).
 *
 * <p>Состав острова пересобирается прямо на открытом чате: настройки меняются
 * на другом экране, а список внутри попапа уже создан — поэтому содержимое
 * обновляется через {@link #setItems(List)}, а не пересозданием меню.
 */
public class PengramChatMenuWrapper {

    public final ActionBarPopupWindow.ActionBarPopupWindowLayout windowLayout;

    public interface Callback {
        void dismiss();
        void onItem(int itemId);
    }

    private final PopupSwipeBackLayout swipeBackLayout;
    private final int account;
    private final long dialogId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Callback callback;
    private final ArrayList<Integer> items = new ArrayList<>();

    public PengramChatMenuWrapper(Context context, PopupSwipeBackLayout swipeBackLayout, int account, long dialogId,
                                  List<Integer> items, Theme.ResourcesProvider resourcesProvider, Callback callback) {
        this.swipeBackLayout = swipeBackLayout;
        this.account = account;
        this.dialogId = dialogId;
        this.resourcesProvider = resourcesProvider;
        this.callback = callback;
        windowLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, resourcesProvider);
        // Страница острова — foreground внутри PopupSwipeBackLayout: её фон рисует
        // сам свайп-слой, а цвет пунктов берётся из этого resourcesProvider. Когда
        // провайдеры различались (actionbar против chat theme), пара фон/текст
        // съезжала и страница уходила в чёрное. Рисуем фон сами — тем же
        // провайдером, что и пункты.
        windowLayout.setBackground(new android.graphics.drawable.ColorDrawable(
                Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider)));
        windowLayout.setFitItems(true);
        setItems(items);
        windowLayout.setMinimumWidth(AndroidUtilities.dp(220));
    }

    /** новый набор пунктов: пересобираем содержимое на месте */
    public void setItems(List<Integer> newItems) {
        items.clear();
        if (newItems != null) {
            for (Integer id : newItems) {
                if (id != null && !items.contains(id)) {
                    items.add(id);
                }
            }
        }
        rebuild();
    }

    private void rebuild() {
        try {
            windowLayout.removeAllViews();
            if (swipeBackLayout != null) {
                final View backItem = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_arrow_back,
                        LocaleController.getString(R.string.Back), false, resourcesProvider);
                backItem.setOnClickListener(view -> swipeBackLayout.closeForeground());
            }
            for (int a = 0; a < items.size(); ++a) {
                addChatItem(items.get(a));
            }
            final int quickCount = PengramConfig.getQuickActionCount();
            for (int i = 0; i < quickCount; i++) {
                addQuickAction(i);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void addChatItem(final int id) {
        int icon = PengramConfig.getChatItemIcon(id);
        if (id == PengramConfig.CHAT_ITEM_VIEW_DELETED) {
            final int markIcon = PengramConfig.getMarkIcon(PengramConfig.MARK_TRASH);
            if (markIcon != 0) {
                icon = markIcon;
            }
        }
        final ActionBarMenuSubItem item = ActionBarMenuItem.addItem(windowLayout, icon,
                LocaleController.getString(PengramConfig.getChatItemTitle(id)), false, resourcesProvider);
        if (id == PengramConfig.CHAT_ITEM_VIEW_DELETED) {
            // меню должно открыться мгновенно, поэтому число берём из кэша
            // и дорисовываем подпись, когда счётчик досчитается в фоне
            final int count = PengramHistory.getCountCached(account, dialogId, () -> {
                final int fresh = PengramHistory.getCountCached(account, dialogId, null);
                if (fresh > 0 && item.isAttachedToWindow()) {
                    item.setSubtext(LocaleController.formatPluralString("PengramSavedMessagesCount", fresh));
                }
            });
            if (count > 0) {
                item.setSubtext(LocaleController.formatPluralString("PengramSavedMessagesCount", count));
            }
        }
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.onItem(id);
        });
    }

    private void addQuickAction(final int index) {
        final int icon = PengramConfig.getQuickActionType(index) == PengramConfig.QUICK_ACTION_FORWARD
                ? R.drawable.msg_forward : R.drawable.msg_message;
        final ActionBarMenuSubItem quick = ActionBarMenuItem.addItem(windowLayout, icon,
                PengramConfig.getQuickActionName(index), false, resourcesProvider);
        quick.setOnClickListener(view -> {
            callback.dismiss();
            callback.onItem(-101 - index);
        });
    }
}
