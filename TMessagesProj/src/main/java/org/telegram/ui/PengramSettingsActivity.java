package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Настройки клиента Pengram: призрак-режим и отображение ID в профиле
 * с живым превью карточки профиля.
 */
public class PengramSettingsActivity extends UniversalFragment {

    private static final int BTN_GHOST = 100;
    private static final int BTN_HIDE_ONLINE = 101;
    private static final int BTN_DONT_READ = 102;
    private static final int BTN_DONT_TYPE = 103;
    private static final int BTN_DONT_STORY = 104;

    private static final int BTN_ID_OFF = 200;
    private static final int BTN_ID_ROW = 201;
    private static final int BTN_ID_ROW_DC = 202;
    private static final int BTN_ID_INLINE = 203;
    private static final int BTN_ID_COPY = 210;

    private ProfilePreviewView previewView;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PengramSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PengramConfig.init();

        if (previewView == null) {
            previewView = new ProfilePreviewView(getContext());
        }
        previewView.update();
        items.add(UItem.asCustom(previewView));
        items.add(UItem.asShadow(getString(R.string.PengramIdPreviewInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramIdHeader)));
        items.add(UItem.asRadio(BTN_ID_OFF, getString(R.string.PengramIdStyleOff)).setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_OFF));
        items.add(UItem.asRadio(BTN_ID_ROW, getString(R.string.PengramIdStyleRow)).setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_ROW));
        items.add(UItem.asRadio(BTN_ID_ROW_DC, getString(R.string.PengramIdStyleRowDc)).setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_ROW_DC));
        items.add(UItem.asRadio(BTN_ID_INLINE, getString(R.string.PengramIdStyleInline)).setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_INLINE));
        items.add(UItem.asCheck(BTN_ID_COPY, getString(R.string.PengramIdCopyOnTap)).setChecked(PengramConfig.copyIdOnTap).setEnabled(PengramConfig.idStyle != PengramConfig.ID_STYLE_OFF));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramGhostHeader)));
        items.add(UItem.asCheck(BTN_GHOST, getString(R.string.PengramGhostMode)).setChecked(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_HIDE_ONLINE, getString(R.string.PengramGhostHideOnline)).setChecked(PengramConfig.hideOnline).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_READ, getString(R.string.PengramGhostDontRead)).setChecked(PengramConfig.dontSendRead).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_TYPE, getString(R.string.PengramGhostDontType)).setChecked(PengramConfig.dontSendTyping).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_STORY, getString(R.string.PengramGhostDontStory)).setChecked(PengramConfig.dontSendStoryViews).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asShadow(getString(R.string.PengramGhostInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        boolean updateAll = false;
        switch (item.id) {
            case BTN_GHOST:
                PengramConfig.toggleGhostMode();
                updateAll = true;
                break;
            case BTN_HIDE_ONLINE:
                PengramConfig.toggleHideOnline();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.hideOnline);
                break;
            case BTN_DONT_READ:
                PengramConfig.toggleDontSendRead();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.dontSendRead);
                break;
            case BTN_DONT_TYPE:
                PengramConfig.toggleDontSendTyping();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.dontSendTyping);
                break;
            case BTN_DONT_STORY:
                PengramConfig.toggleDontSendStoryViews();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.dontSendStoryViews);
                break;
            case BTN_ID_COPY:
                PengramConfig.toggleCopyIdOnTap();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.copyIdOnTap);
                break;
            case BTN_ID_OFF:
                PengramConfig.setIdStyle(PengramConfig.ID_STYLE_OFF);
                updateAll = true;
                break;
            case BTN_ID_ROW:
                PengramConfig.setIdStyle(PengramConfig.ID_STYLE_ROW);
                updateAll = true;
                break;
            case BTN_ID_ROW_DC:
                PengramConfig.setIdStyle(PengramConfig.ID_STYLE_ROW_DC);
                updateAll = true;
                break;
            case BTN_ID_INLINE:
                PengramConfig.setIdStyle(PengramConfig.ID_STYLE_INLINE);
                updateAll = true;
                break;
        }
        if (previewView != null) {
            previewView.update();
        }
        if (updateAll && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /**
     * Живое превью: так карточка профиля будет выглядеть с текущими настройками.
     */
    private class ProfilePreviewView extends LinearLayout {

        private final BackupImageView avatarImage;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView nameText;
        private final TextDetailCell usernameCell;
        private final TextDetailCell idCell;

        public ProfilePreviewView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));

            FrameLayout header = new FrameLayout(context);
            avatarImage = new BackupImageView(context);
            avatarImage.setRoundRadius(dp(32));
            header.addView(avatarImage, LayoutHelper.createFrame(64, 64, Gravity.LEFT | Gravity.TOP, 20, 14, 0, 0));

            nameText = new TextView(context);
            nameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            nameText.setTypeface(AndroidUtilities.bold());
            nameText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            nameText.setSingleLine(true);
            header.addView(nameText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 98, 32, 20, 0));
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 92));

            usernameCell = new TextDetailCell(context, getResourceProvider(), false, true);
            addView(usernameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            idCell = new TextDetailCell(context, getResourceProvider(), false, true);
            addView(idCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        public void update() {
            final TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
            long id = user != null ? user.id : 1234567890L;
            String username = user != null ? UserObject.getPublicUsername(user) : null;
            if (username == null) {
                username = "username";
            }
            int dcId = user != null && user.photo != null ? user.photo.dc_id : 2;
            if (dcId <= 0) {
                dcId = 2;
            }

            if (user != null) {
                avatarDrawable.setInfo(currentAccount, user);
                avatarImage.setForUserOrChat(user, avatarDrawable);
                nameText.setText(UserObject.getUserName(user));
            } else {
                nameText.setText("Pengram");
            }

            final int style = PengramConfig.getIdStyle();
            CharSequence usernameValue = getString(R.string.Username);
            if (style == PengramConfig.ID_STYLE_INLINE) {
                usernameValue = usernameValue + " \u2022 ID: " + id;
            }
            usernameCell.setTextAndValue("@" + username, usernameValue, style == PengramConfig.ID_STYLE_ROW || style == PengramConfig.ID_STYLE_ROW_DC);

            if (style == PengramConfig.ID_STYLE_ROW || style == PengramConfig.ID_STYLE_ROW_DC) {
                idCell.setVisibility(VISIBLE);
                idCell.setTextAndValue(String.valueOf(id), style == PengramConfig.ID_STYLE_ROW_DC ? ("ID \u2022 DC" + dcId) : "ID", false);
            } else {
                idCell.setVisibility(GONE);
            }
        }
    }
}
