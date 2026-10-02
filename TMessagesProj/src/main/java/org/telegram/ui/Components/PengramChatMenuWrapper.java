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

/**
 * Pengram: подменю в «трёх точках» чата.
 * Специально держим его пустым — тут живёт только просмотр удалёнок,
 * всё остальное вынесено в основное меню и настраивается в настройках Pengram.
 */
public class PengramChatMenuWrapper {

    public final ActionBarPopupWindow.ActionBarPopupWindowLayout windowLayout;

    public interface Callback {
        void dismiss();
        void openAll();
    }

    public PengramChatMenuWrapper(Context context, PopupSwipeBackLayout swipeBackLayout, long dialogId, Theme.ResourcesProvider resourcesProvider, Callback callback) {
        windowLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, resourcesProvider);
        windowLayout.setFitItems(true);

        if (swipeBackLayout != null) {
            final View backItem = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, resourcesProvider);
            backItem.setOnClickListener(view -> swipeBackLayout.closeForeground());
        }

        final int deletedIcon = PengramConfig.getMarkIcon(PengramConfig.MARK_TRASH);
        final ActionBarMenuSubItem item = ActionBarMenuItem.addItem(windowLayout, deletedIcon != 0 ? deletedIcon : R.drawable.msg_delete,
                LocaleController.getString(R.string.PengramViewDeleted), false, resourcesProvider);
        final int count = PengramHistory.getCount(dialogId);
        if (count > 0) {
            item.setSubtext(LocaleController.formatPluralString("PengramSavedMessagesCount", count));
        }
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.openAll();
        });

        windowLayout.setMinimumWidth(AndroidUtilities.dp(220));
    }
}
