package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

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
 * Pengram: аккуратное подменю в «трёх точках» чата.
 * Все наши пункты живут в одном месте и не захламляют основное меню.
 */
public class PengramChatMenuWrapper {

    public final ActionBarPopupWindow.ActionBarPopupWindowLayout windowLayout;

    public interface Callback {
        void dismiss();
        void openDeleted();
        void openEdited();
        void openAll();
        void clearHistory();
        void openSettings();
        void jumpToBeginning();
        void copyChatId();
    }

    public PengramChatMenuWrapper(Context context, PopupSwipeBackLayout swipeBackLayout, long dialogId, Theme.ResourcesProvider resourcesProvider, Callback callback) {
        windowLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, resourcesProvider);
        windowLayout.setFitItems(true);

        if (swipeBackLayout != null) {
            final View backItem = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, resourcesProvider);
            backItem.setOnClickListener(view -> swipeBackLayout.closeForeground());
        }

        final int deletedIcon = PengramConfig.getMarkIcon(PengramConfig.MARK_TRASH);
        ActionBarMenuSubItem item = ActionBarMenuItem.addItem(windowLayout, deletedIcon != 0 ? deletedIcon : R.drawable.msg_delete,
                LocaleController.getString(R.string.PengramViewDeleted), false, resourcesProvider);
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.openDeleted();
        });

        final int editedIcon = PengramConfig.getEditedMarkIcon(PengramConfig.MARK_EDIT_PENCIL);
        item = ActionBarMenuItem.addItem(windowLayout, editedIcon != 0 ? editedIcon : R.drawable.msg_edit,
                LocaleController.getString(R.string.PengramViewEdited), false, resourcesProvider);
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.openEdited();
        });

        item = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_viewchats,
                LocaleController.getString(R.string.PengramViewAll), false, resourcesProvider);
        final int count = PengramHistory.getCount(dialogId);
        if (count > 0) {
            item.setSubtext(LocaleController.formatPluralString("PengramSavedMessagesCount", count));
        }
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.openAll();
        });

        item = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_go_up,
                LocaleController.getString(R.string.PengramJumpToBeginning), false, resourcesProvider);
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.jumpToBeginning();
        });

        item = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_copy,
                LocaleController.getString(R.string.PengramCopyChatId), false, resourcesProvider);
        item.setSubtext(String.valueOf(dialogId));
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.copyChatId();
        });

        item = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_settings,
                LocaleController.getString(R.string.PengramSettings), false, resourcesProvider);
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.openSettings();
        });

        final FrameLayout gap = new FrameLayout(context);
        gap.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator, resourcesProvider));
        final View gapShadow = new View(context);
        gapShadow.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow, resourcesProvider));
        gap.addView(gapShadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        gap.setTag(R.id.fit_width_tag, 1);
        windowLayout.addView(gap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        item = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_clear,
                LocaleController.getString(R.string.PengramHistoryClearButton), false, resourcesProvider);
        item.setColors(Theme.getColor(Theme.key_text_RedBold, resourcesProvider), Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.clearHistory();
        });

        windowLayout.setMinimumWidth(AndroidUtilities.dp(220));
    }
}
