package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

/**
 * Pengram: остров в «трёх точках» чата.
 * Что именно внутри — решает пользователь: любой наш пункт можно перетащить
 * из основного меню в остров и обратно (Настройки → Pengram → Пункты меню чата).
 */
public class PengramChatMenuWrapper {

    public final ActionBarPopupWindow.ActionBarPopupWindowLayout windowLayout;

    public interface Callback {
        void dismiss();
        void onItem(int itemId);
    }

    public PengramChatMenuWrapper(Context context, PopupSwipeBackLayout swipeBackLayout, long dialogId,
                                  List<Integer> items, Theme.ResourcesProvider resourcesProvider, Callback callback) {
        windowLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, resourcesProvider);
        windowLayout.setFitItems(true);

        if (swipeBackLayout != null) {
            final View backItem = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, resourcesProvider);
            backItem.setOnClickListener(view -> swipeBackLayout.closeForeground());
        }

        if (items != null) {
            for (int a = 0; a < items.size(); ++a) {
                final int id = items.get(a);
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
                    final int count = PengramHistory.getCountCached(dialogId, () -> {
                        final int fresh = PengramHistory.getCountCached(dialogId, null);
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
        }


        for (int i = 0; i < PengramConfig.getQuickActionCount(); i++) {
            final int index = i;
            final int icon = PengramConfig.getQuickActionType(index) == PengramConfig.QUICK_ACTION_FORWARD
                    ? R.drawable.msg_forward : R.drawable.msg_message;
            final ActionBarMenuSubItem quick = ActionBarMenuItem.addItem(windowLayout, icon,
                    PengramConfig.getQuickActionName(index), false, resourcesProvider);
            quick.setOnClickListener(view -> { callback.dismiss(); callback.onItem(-101 - index); });
        }

        windowLayout.setMinimumWidth(AndroidUtilities.dp(220));
    }
}
