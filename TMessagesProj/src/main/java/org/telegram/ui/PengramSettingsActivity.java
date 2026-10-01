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
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
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

    private static final int BTN_REG_OFF = 300;
    private static final int BTN_REG_DATE = 301;
    private static final int BTN_REG_DATE_AGE = 302;

    private static final int BTN_HIST_DELETED = 400;
    private static final int BTN_HIST_EDITED = 401;
    private static final int BTN_HIST_OUTGOING = 402;
    private static final int BTN_HIST_PROFILE = 403;
    private static final int BTN_HIST_OPEN = 404;
    private static final int BTN_HIST_CLEAR = 405;

    private static final int BTN_MEDIA_SAVE = 450;
    private static final int BTN_MEDIA_FOLDER = 451;
    private static final int BTN_MEDIA_PATTERN = 452;

    private static final int BTN_SCREENSHOTS = 500;
    private static final int BTN_NO_SS_NOTIFY = 501;
    private static final int BTN_FORWARDS = 502;
    private static final int BTN_KEEP_ONCE = 503;
    private static final int BTN_ADS = 510;
    private static final int BTN_LOCAL_PREMIUM = 520;
    private static final int BTN_FONT_DEFAULT = 530;
    private static final int BTN_FONT_SYSTEM = 531;
    private static final int BTN_FONT_SERIF = 532;
    private static final int BTN_FONT_MONO = 533;
    private static final int BTN_CHAT_MENU = 540;
    private static final int BTN_CHAT_MENU_TOP = 541;
    private static final int BTN_CHAT_MENU_BOTTOM = 542;

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

        items.add(UItem.asHeader(getString(R.string.PengramRegHeader)));
        items.add(UItem.asRadio(BTN_REG_OFF, getString(R.string.PengramRegStyleOff)).setChecked(PengramConfig.regDateStyle == PengramConfig.REG_STYLE_OFF));
        items.add(UItem.asRadio(BTN_REG_DATE, getString(R.string.PengramRegStyleDate)).setChecked(PengramConfig.regDateStyle == PengramConfig.REG_STYLE_DATE));
        items.add(UItem.asRadio(BTN_REG_DATE_AGE, getString(R.string.PengramRegStyleDateAge)).setChecked(PengramConfig.regDateStyle == PengramConfig.REG_STYLE_DATE_AGE));
        items.add(UItem.asShadow(getString(R.string.PengramRegInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramHistoryHeader)));
        items.add(UItem.asCheck(BTN_HIST_DELETED, getString(R.string.PengramHistorySaveDeleted)).setChecked(PengramConfig.saveDeleted));
        items.add(UItem.asCheck(BTN_HIST_EDITED, getString(R.string.PengramHistorySaveEdited)).setChecked(PengramConfig.saveEdited));
        items.add(UItem.asCheck(BTN_HIST_OUTGOING, getString(R.string.PengramHistorySaveOutgoing)).setChecked(PengramConfig.saveOutgoing));
        items.add(UItem.asCheck(BTN_HIST_PROFILE, getString(R.string.PengramHistoryShowInProfile)).setChecked(PengramConfig.historyRowInProfile));
        items.add(UItem.asButton(BTN_HIST_OPEN, R.drawable.msg_viewchats, getString(R.string.PengramHistoryOpen),
                String.valueOf(PengramHistory.getCount(0))));
        items.add(UItem.asButton(BTN_HIST_CLEAR, R.drawable.msg_delete, getString(R.string.PengramHistoryClearButton)).red());
        items.add(UItem.asCheck(BTN_MEDIA_SAVE, getString(R.string.PengramMediaSave)).setChecked(PengramConfig.saveDeletedMedia));
        items.add(UItem.asButton(BTN_MEDIA_FOLDER, getString(R.string.PengramMediaFolder), PengramConfig.getMediaFolder()).setEnabled(PengramConfig.saveDeletedMedia));
        items.add(UItem.asButton(BTN_MEDIA_PATTERN, getString(R.string.PengramMediaPattern), PengramConfig.getMediaPattern()).setEnabled(PengramConfig.saveDeletedMedia));
        items.add(UItem.asShadow(getString(R.string.PengramMediaInfo)));
        items.add(UItem.asShadow(LocaleController.formatString(R.string.PengramHistorySize, AndroidUtilities.formatFileSize(PengramHistory.getDatabaseSize()))));

        items.add(UItem.asHeader(getString(R.string.PengramGhostHeader)));
        items.add(UItem.asCheck(BTN_GHOST, getString(R.string.PengramGhostMode)).setChecked(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_HIDE_ONLINE, getString(R.string.PengramGhostHideOnline)).setChecked(PengramConfig.hideOnline).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_READ, getString(R.string.PengramGhostDontRead)).setChecked(PengramConfig.dontSendRead).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_TYPE, getString(R.string.PengramGhostDontType)).setChecked(PengramConfig.dontSendTyping).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_STORY, getString(R.string.PengramGhostDontStory)).setChecked(PengramConfig.dontSendStoryViews).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asShadow(getString(R.string.PengramGhostInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramFreedomHeader)));
        items.add(UItem.asCheck(BTN_SCREENSHOTS, getString(R.string.PengramAllowScreenshots)).setChecked(PengramConfig.allowScreenshots));
        items.add(UItem.asCheck(BTN_NO_SS_NOTIFY, getString(R.string.PengramNoScreenshotNotify)).setChecked(PengramConfig.noScreenshotNotify).setEnabled(PengramConfig.allowScreenshots));
        items.add(UItem.asCheck(BTN_FORWARDS, getString(R.string.PengramAllowForwards)).setChecked(PengramConfig.allowForwards));
        items.add(UItem.asCheck(BTN_KEEP_ONCE, getString(R.string.PengramKeepOnce)).setChecked(PengramConfig.keepOnceMedia));
        items.add(UItem.asShadow(getString(R.string.PengramFreedomInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramAdsHeader)));
        items.add(UItem.asCheck(BTN_ADS, getString(R.string.PengramHideAds)).setChecked(PengramConfig.hideAds));
        items.add(UItem.asShadow(getString(R.string.PengramAdsInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramPremiumHeader)));
        items.add(UItem.asCheck(BTN_LOCAL_PREMIUM, getString(R.string.PengramLocalPremium)).setChecked(PengramConfig.localPremium));
        items.add(UItem.asShadow(getString(R.string.PengramPremiumInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramAppearanceHeader)));
        items.add(UItem.asRadio(BTN_FONT_DEFAULT, getString(R.string.PengramFontDefault)).setChecked(PengramConfig.appFont == PengramConfig.FONT_DEFAULT));
        items.add(UItem.asRadio(BTN_FONT_SYSTEM, getString(R.string.PengramFontSystem)).setChecked(PengramConfig.appFont == PengramConfig.FONT_SYSTEM));
        items.add(UItem.asRadio(BTN_FONT_SERIF, getString(R.string.PengramFontSerif)).setChecked(PengramConfig.appFont == PengramConfig.FONT_SERIF));
        items.add(UItem.asRadio(BTN_FONT_MONO, getString(R.string.PengramFontMono)).setChecked(PengramConfig.appFont == PengramConfig.FONT_MONOSPACE));
        items.add(UItem.asShadow(null));
        items.add(UItem.asCheck(BTN_CHAT_MENU, getString(R.string.PengramChatMenu)).setChecked(PengramConfig.chatMenuEnabled));
        items.add(UItem.asRadio(BTN_CHAT_MENU_TOP, getString(R.string.PengramChatMenuTop)).setChecked(PengramConfig.chatMenuPosition == PengramConfig.MENU_POS_TOP).setEnabled(PengramConfig.chatMenuEnabled));
        items.add(UItem.asRadio(BTN_CHAT_MENU_BOTTOM, getString(R.string.PengramChatMenuBottom)).setChecked(PengramConfig.chatMenuPosition == PengramConfig.MENU_POS_BOTTOM).setEnabled(PengramConfig.chatMenuEnabled));
        items.add(UItem.asShadow(null));
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
            case BTN_REG_OFF:
                PengramConfig.setRegDateStyle(PengramConfig.REG_STYLE_OFF);
                updateAll = true;
                break;
            case BTN_REG_DATE:
                PengramConfig.setRegDateStyle(PengramConfig.REG_STYLE_DATE);
                updateAll = true;
                break;
            case BTN_REG_DATE_AGE:
                PengramConfig.setRegDateStyle(PengramConfig.REG_STYLE_DATE_AGE);
                updateAll = true;
                break;
            case BTN_HIST_DELETED:
                PengramConfig.toggleSaveDeleted();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.saveDeleted);
                break;
            case BTN_HIST_EDITED:
                PengramConfig.toggleSaveEdited();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.saveEdited);
                break;
            case BTN_HIST_OUTGOING:
                PengramConfig.toggleSaveOutgoing();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.saveOutgoing);
                break;
            case BTN_HIST_PROFILE:
                PengramConfig.toggleHistoryRowInProfile();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.historyRowInProfile);
                break;
            case BTN_HIST_OPEN:
                presentFragment(new PengramHistoryActivity(0));
                break;
            case BTN_MEDIA_SAVE:
                PengramConfig.toggleSaveDeletedMedia();
                updateAll = true;
                break;
            case BTN_MEDIA_FOLDER:
                showTextDialog(getString(R.string.PengramMediaFolder), PengramConfig.getMediaFolder(), PengramConfig.DEFAULT_MEDIA_FOLDER, value -> {
                    PengramConfig.setMediaFolder(value);
                    if (listView != null && listView.adapter != null) listView.adapter.update(true);
                });
                break;
            case BTN_MEDIA_PATTERN:
                showTextDialog(getString(R.string.PengramMediaPattern), PengramConfig.getMediaPattern(), getString(R.string.PengramMediaPatternHint), value -> {
                    PengramConfig.setMediaPattern(value);
                    if (listView != null && listView.adapter != null) listView.adapter.update(true);
                });
                break;
            case BTN_SCREENSHOTS:
                PengramConfig.toggleAllowScreenshots();
                updateAll = true;
                break;
            case BTN_NO_SS_NOTIFY:
                PengramConfig.toggleNoScreenshotNotify();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.noScreenshotNotify);
                break;
            case BTN_FORWARDS:
                PengramConfig.toggleAllowForwards();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.allowForwards);
                break;
            case BTN_KEEP_ONCE:
                PengramConfig.toggleKeepOnceMedia();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.keepOnceMedia);
                break;
            case BTN_ADS:
                PengramConfig.toggleHideAds();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.hideAds);
                break;
            case BTN_LOCAL_PREMIUM:
                PengramConfig.toggleLocalPremium();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.localPremium);
                break;
            case BTN_FONT_DEFAULT:
            case BTN_FONT_SYSTEM:
            case BTN_FONT_SERIF:
            case BTN_FONT_MONO:
                PengramConfig.setAppFont(item.id - BTN_FONT_DEFAULT);
                updateAll = true;
                break;
            case BTN_CHAT_MENU:
                PengramConfig.toggleChatMenu();
                updateAll = true;
                break;
            case BTN_CHAT_MENU_TOP:
                PengramConfig.setChatMenuPosition(PengramConfig.MENU_POS_TOP);
                updateAll = true;
                break;
            case BTN_CHAT_MENU_BOTTOM:
                PengramConfig.setChatMenuPosition(PengramConfig.MENU_POS_BOTTOM);
                updateAll = true;
                break;
            case BTN_HIST_CLEAR:
                if (getParentActivity() != null) {
                    AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                    b.setTitle(getString(R.string.PengramHistoryClearTitle));
                    b.setMessage(getString(R.string.PengramHistoryClearAll));
                    b.setPositiveButton(getString(R.string.Delete), (d, w) -> {
                        PengramHistory.clear(0);
                        if (listView != null && listView.adapter != null) listView.adapter.update(true);
                    });
                    b.setNegativeButton(getString(R.string.Cancel), null);
                    showDialog(b.create());
                }
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

    private void showTextDialog(String title, String current, String hint, Utilities.Callback<String> onDone) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setText(current);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setBackgroundDrawable(null);
        editText.setPadding(dp(22), dp(8), dp(22), dp(8));
        if (current != null) {
            editText.setSelection(current.length());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setView(editText);
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> onDone.run(editText.getText().toString()));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
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
