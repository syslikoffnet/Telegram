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

    public static final int SECTION_ROOT = 0;
    public static final int SECTION_PROFILE = 1;
    public static final int SECTION_GHOST = 2;
    public static final int SECTION_HISTORY = 3;
    public static final int SECTION_APPEARANCE = 4;
    public static final int SECTION_CHATS = 5;
    public static final int SECTION_FREEDOM = 6;

    private static final int BTN_SECTION_PROFILE = 1001;
    private static final int BTN_SECTION_GHOST = 1002;
    private static final int BTN_SECTION_HISTORY = 1003;
    private static final int BTN_SECTION_APPEARANCE = 1004;
    private static final int BTN_SECTION_CHATS = 1005;
    private static final int BTN_SECTION_FREEDOM = 1006;

    private static final int BTN_HIDE_PHONE = 1100;
    private static final int BTN_SAVE_IN_BOTS = 1101;
    private static final int BTN_SAVE_READ_DATE = 1102;
    private static final int BTN_SAVE_LAST_ONLINE = 1103;
    private static final int BTN_MEDIA_LIMIT = 1104;
    private static final int BTN_MEDIA_CLEAR = 1105;

    private static final int BTN_HIDE_MENU_NEW_GROUP = 1200;
    private static final int BTN_HIDE_MENU_SAVED = 1201;
    private static final int BTN_HIDE_MENU_SETTINGS = 1202;
    private static final int BTN_HIDE_MENU_THEME = 1203;
    private static final int BTN_HIDE_CHAT_SEARCH = 1210;
    private static final int BTN_HIDE_CHAT_TRANSLATE = 1211;
    private static final int BTN_HIDE_CHAT_CLEAR = 1212;
    private static final int BTN_HIDE_CHAT_WALLPAPER = 1213;
    private static final int BTN_HIDE_CHAT_SHORTCUT = 1214;
    private static final int BTN_HIDE_CHAT_REPORT = 1215;
    private static final int BTN_HIDE_CHAT_CALL = 1216;
    private static final int BTN_HIDE_CHAT_AUTODELETE = 1217;

    private final int section;

    public PengramSettingsActivity() {
        this(SECTION_ROOT);
    }

    public PengramSettingsActivity(int section) {
        super();
        this.section = section;
    }

    private static final int[] MEDIA_LIMITS = new int[]{0, 1024, 2048, 4096, 8192, 16384, 32768, 65536};

    private ProfilePreviewView previewView;

    @Override
    protected CharSequence getTitle() {
        switch (section) {
            case SECTION_PROFILE: return getString(R.string.PengramSectionProfile);
            case SECTION_GHOST: return getString(R.string.PengramSectionGhost);
            case SECTION_HISTORY: return getString(R.string.PengramSectionHistory);
            case SECTION_APPEARANCE: return getString(R.string.PengramSectionAppearance);
            case SECTION_CHATS: return getString(R.string.PengramSectionChats);
            case SECTION_FREEDOM: return getString(R.string.PengramSectionFreedom);
            default: return getString(R.string.PengramSettings);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PengramConfig.init();
        switch (section) {
            case SECTION_PROFILE: fillProfile(items); break;
            case SECTION_GHOST: fillGhost(items); break;
            case SECTION_HISTORY: fillHistory(items); break;
            case SECTION_APPEARANCE: fillAppearance(items); break;
            case SECTION_CHATS: fillChats(items); break;
            case SECTION_FREEDOM: fillFreedom(items); break;
            default: fillRoot(items); break;
        }
    }

    private void fillRoot(ArrayList<UItem> items) {
        if (previewView == null) {
            previewView = new ProfilePreviewView(getContext());
        }
        previewView.update();
        items.add(UItem.asCustom(previewView));
        items.add(UItem.asShadow(getString(R.string.PengramIdPreviewInfo)));


        items.add(UItem.asButton(BTN_SECTION_PROFILE, R.drawable.settings_account, getString(R.string.PengramSectionProfile)));
        items.add(UItem.asButton(BTN_SECTION_GHOST, R.drawable.settings_privacy, getString(R.string.PengramSectionGhost)));
        items.add(UItem.asButton(BTN_SECTION_HISTORY, R.drawable.msg_viewchats, getString(R.string.PengramSectionHistory)));
        items.add(UItem.asButton(BTN_SECTION_APPEARANCE, R.drawable.settings_features, getString(R.string.PengramSectionAppearance)));
        items.add(UItem.asButton(BTN_SECTION_CHATS, R.drawable.settings_chat, getString(R.string.PengramSectionChats)));
        items.add(UItem.asButton(BTN_SECTION_FREEDOM, R.drawable.settings_devices, getString(R.string.PengramSectionFreedom)));
        items.add(UItem.asShadow(getString(R.string.PengramSectionsInfo)));
    }

    private void fillProfile(ArrayList<UItem> items) {
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

        items.add(UItem.asHeader(getString(R.string.PengramPrivacyHeader)));
        items.add(UItem.asCheck(BTN_HIDE_PHONE, getString(R.string.PengramHidePhone)).setChecked(PengramConfig.hidePhoneNumber));
        items.add(UItem.asShadow(getString(R.string.PengramHidePhoneInfo)));
    }

    private void fillHistory(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramHistoryHeader)));
        items.add(UItem.asCheck(BTN_HIST_DELETED, getString(R.string.PengramHistorySaveDeleted)).setChecked(PengramConfig.saveDeleted));
        items.add(UItem.asCheck(BTN_HIST_EDITED, getString(R.string.PengramHistorySaveEdited)).setChecked(PengramConfig.saveEdited));
        items.add(UItem.asCheck(BTN_HIST_OUTGOING, getString(R.string.PengramHistorySaveOutgoing)).setChecked(PengramConfig.saveOutgoing));
        items.add(UItem.asCheck(BTN_SAVE_IN_BOTS, getString(R.string.PengramSaveInBots)).setChecked(PengramConfig.saveInBots));
        items.add(UItem.asCheck(BTN_HIST_PROFILE, getString(R.string.PengramHistoryShowInProfile)).setChecked(PengramConfig.historyRowInProfile));
        items.add(UItem.asButton(BTN_HIST_OPEN, R.drawable.msg_viewchats, getString(R.string.PengramHistoryOpen),
                String.valueOf(PengramHistory.getCount(0))));
        items.add(UItem.asButton(BTN_HIST_CLEAR, R.drawable.msg_delete, getString(R.string.PengramHistoryClearButton)).red());
        items.add(UItem.asCheck(BTN_MEDIA_SAVE, getString(R.string.PengramMediaSave)).setChecked(PengramConfig.saveDeletedMedia));
        items.add(UItem.asButton(BTN_MEDIA_FOLDER, getString(R.string.PengramMediaFolder), PengramConfig.getMediaFolder()).setEnabled(PengramConfig.saveDeletedMedia));
        items.add(UItem.asButton(BTN_MEDIA_PATTERN, getString(R.string.PengramMediaPattern), PengramConfig.getMediaPattern()).setEnabled(PengramConfig.saveDeletedMedia));
        if (PengramConfig.saveDeletedMedia) {
            final int[] gb = MEDIA_LIMITS;
            int chosen = 0;
            for (int i = 0; i < gb.length; ++i) {
                if (gb[i] == PengramConfig.getMediaMaxSizeMb()) {
                    chosen = i;
                    break;
                }
            }
            String[] titles = new String[gb.length];
            for (int i = 0; i < gb.length; ++i) {
                titles[i] = gb[i] == 0 ? getString(R.string.PengramMediaLimitOff) : (gb[i] / 1024) + " GB";
            }
            items.add(UItem.asSlideView(titles, chosen, index -> {
                PengramConfig.setMediaMaxSizeMb(MEDIA_LIMITS[index]);
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
            }));
            items.add(UItem.asShadow(LocaleController.formatString(R.string.PengramMediaLimitInfo, AndroidUtilities.formatFileSize(PengramHistory.getSavedMediaSize()))));
            items.add(UItem.asButton(BTN_MEDIA_CLEAR, R.drawable.msg_delete, getString(R.string.PengramMediaClear)).red());
        }
        items.add(UItem.asShadow(getString(R.string.PengramMediaInfo)));
        items.add(UItem.asShadow(LocaleController.formatString(R.string.PengramHistorySize, AndroidUtilities.formatFileSize(PengramHistory.getDatabaseSize()))));

    }

    private void fillGhost(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramGhostHeader)));
        items.add(UItem.asCheck(BTN_GHOST, getString(R.string.PengramGhostMode)).setChecked(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_HIDE_ONLINE, getString(R.string.PengramGhostHideOnline)).setChecked(PengramConfig.hideOnline).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_READ, getString(R.string.PengramGhostDontRead)).setChecked(PengramConfig.dontSendRead).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_TYPE, getString(R.string.PengramGhostDontType)).setChecked(PengramConfig.dontSendTyping).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_DONT_STORY, getString(R.string.PengramGhostDontStory)).setChecked(PengramConfig.dontSendStoryViews).setEnabled(PengramConfig.ghostMode));
        items.add(UItem.asCheck(BTN_SAVE_READ_DATE, getString(R.string.PengramSaveReadDate)).setChecked(PengramConfig.saveReadDate));
        items.add(UItem.asCheck(BTN_SAVE_LAST_ONLINE, getString(R.string.PengramSaveLastOnline)).setChecked(PengramConfig.saveLastOnline));
        items.add(UItem.asShadow(getString(R.string.PengramGhostInfo)));

    }

    private void fillFreedom(ArrayList<UItem> items) {
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

    }

    private void fillAppearance(ArrayList<UItem> items) {
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

    private void fillChats(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramHideMenuHeader)));
        items.add(UItem.asCheck(BTN_HIDE_MENU_NEW_GROUP, getString(R.string.PengramHideMenuNewGroup)).setChecked(PengramConfig.hideMenuNewGroup));
        items.add(UItem.asCheck(BTN_HIDE_MENU_SAVED, getString(R.string.PengramHideMenuSaved)).setChecked(PengramConfig.hideMenuSavedMessages));
        items.add(UItem.asCheck(BTN_HIDE_MENU_SETTINGS, getString(R.string.PengramHideMenuSettings)).setChecked(PengramConfig.hideMenuSettings));
        items.add(UItem.asCheck(BTN_HIDE_MENU_THEME, getString(R.string.PengramHideMenuTheme)).setChecked(PengramConfig.hideMenuTheme));
        items.add(UItem.asShadow(getString(R.string.PengramHideMenuInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramHideChatHeader)));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_SEARCH, getString(R.string.PengramHideChatSearch)).setChecked(PengramConfig.hideChatSearch));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_TRANSLATE, getString(R.string.PengramHideChatTranslate)).setChecked(PengramConfig.hideChatTranslate));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_CLEAR, getString(R.string.PengramHideChatClear)).setChecked(PengramConfig.hideChatClearHistory));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_WALLPAPER, getString(R.string.PengramHideChatWallpaper)).setChecked(PengramConfig.hideChatWallpaper));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_SHORTCUT, getString(R.string.PengramHideChatShortcut)).setChecked(PengramConfig.hideChatShortcut));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_REPORT, getString(R.string.PengramHideChatReport)).setChecked(PengramConfig.hideChatReport));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_CALL, getString(R.string.PengramHideChatCall)).setChecked(PengramConfig.hideChatCall));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_AUTODELETE, getString(R.string.PengramHideChatAutoDelete)).setChecked(PengramConfig.hideChatAutoDelete));
        items.add(UItem.asShadow(getString(R.string.PengramHideChatInfo)));
    }

    private void toggleHideFlag(int id, View view) {
        boolean value;
        switch (id) {
            case BTN_HIDE_MENU_NEW_GROUP: value = PengramConfig.toggleBoolean("hideMenuNewGroup"); break;
            case BTN_HIDE_MENU_SAVED: value = PengramConfig.toggleBoolean("hideMenuSavedMessages"); break;
            case BTN_HIDE_MENU_SETTINGS: value = PengramConfig.toggleBoolean("hideMenuSettings"); break;
            case BTN_HIDE_MENU_THEME: value = PengramConfig.toggleBoolean("hideMenuTheme"); break;
            case BTN_HIDE_CHAT_SEARCH: value = PengramConfig.toggleBoolean("hideChatSearch"); break;
            case BTN_HIDE_CHAT_TRANSLATE: value = PengramConfig.toggleBoolean("hideChatTranslate"); break;
            case BTN_HIDE_CHAT_CLEAR: value = PengramConfig.toggleBoolean("hideChatClearHistory"); break;
            case BTN_HIDE_CHAT_WALLPAPER: value = PengramConfig.toggleBoolean("hideChatWallpaper"); break;
            case BTN_HIDE_CHAT_SHORTCUT: value = PengramConfig.toggleBoolean("hideChatShortcut"); break;
            case BTN_HIDE_CHAT_REPORT: value = PengramConfig.toggleBoolean("hideChatReport"); break;
            case BTN_HIDE_CHAT_CALL: value = PengramConfig.toggleBoolean("hideChatCall"); break;
            case BTN_HIDE_CHAT_AUTODELETE: value = PengramConfig.toggleBoolean("hideChatAutoDelete"); break;
            default: return;
        }
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        boolean updateAll = false;
        switch (item.id) {
            case BTN_SECTION_PROFILE:
                presentFragment(new PengramSettingsActivity(SECTION_PROFILE));
                return;
            case BTN_SECTION_GHOST:
                presentFragment(new PengramSettingsActivity(SECTION_GHOST));
                return;
            case BTN_SECTION_HISTORY:
                presentFragment(new PengramSettingsActivity(SECTION_HISTORY));
                return;
            case BTN_SECTION_APPEARANCE:
                presentFragment(new PengramSettingsActivity(SECTION_APPEARANCE));
                return;
            case BTN_SECTION_CHATS:
                presentFragment(new PengramSettingsActivity(SECTION_CHATS));
                return;
            case BTN_SECTION_FREEDOM:
                presentFragment(new PengramSettingsActivity(SECTION_FREEDOM));
                return;
            case BTN_HIDE_PHONE:
                PengramConfig.toggleHidePhoneNumber();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.hidePhoneNumber);
                break;
            case BTN_SAVE_IN_BOTS:
                PengramConfig.toggleSaveInBots();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.saveInBots);
                break;
            case BTN_SAVE_READ_DATE:
                PengramConfig.toggleSaveReadDate();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.saveReadDate);
                break;
            case BTN_SAVE_LAST_ONLINE:
                PengramConfig.toggleSaveLastOnline();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.saveLastOnline);
                break;
            case BTN_MEDIA_CLEAR:
                PengramHistory.clearSavedMedia();
                updateAll = true;
                break;
            case BTN_HIDE_MENU_NEW_GROUP:
            case BTN_HIDE_MENU_SAVED:
            case BTN_HIDE_MENU_SETTINGS:
            case BTN_HIDE_MENU_THEME:
            case BTN_HIDE_CHAT_SEARCH:
            case BTN_HIDE_CHAT_TRANSLATE:
            case BTN_HIDE_CHAT_CLEAR:
            case BTN_HIDE_CHAT_WALLPAPER:
            case BTN_HIDE_CHAT_SHORTCUT:
            case BTN_HIDE_CHAT_REPORT:
            case BTN_HIDE_CHAT_CALL:
            case BTN_HIDE_CHAT_AUTODELETE:
                toggleHideFlag(item.id, view);
                break;
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
