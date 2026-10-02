package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.PengramTextStyle;
import org.telegram.messenger.PengramVoiceChanger;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PengramPenguinView;
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

    private static final int BTN_ID_FORMAT_HIDE = 220;
    private static final int BTN_ID_FORMAT_TELEGRAM = 221;
    private static final int BTN_ID_FORMAT_BOT = 222;

    private static final int BTN_LINK_CHANNEL = 1500;
    private static final int BTN_LINK_AUTHOR = 1501;

    public static final String LINK_CHANNEL = "mishadox";
    public static final String LINK_AUTHOR = "handsgod";

    public static final int SECTION_ROOT = 0;
    public static final int SECTION_PROFILE = 1;
    public static final int SECTION_GHOST = 2;
    public static final int SECTION_HISTORY = 3;
    public static final int SECTION_APPEARANCE = 4;
    public static final int SECTION_CHATS = 5;
    public static final int SECTION_FREEDOM = 6;
    public static final int SECTION_MEDIA = 7;
    public static final int SECTION_GENERAL = 8;
    public static final int SECTION_CUSTOM = 9;

    private static final int BTN_SECTION_PROFILE = 1001;
    private static final int BTN_SECTION_GHOST = 1002;
    private static final int BTN_SECTION_HISTORY = 1003;
    private static final int BTN_SECTION_APPEARANCE = 1004;
    private static final int BTN_SECTION_CHATS = 1005;
    private static final int BTN_SECTION_FREEDOM = 1006;
    private static final int BTN_SECTION_MEDIA = 1007;
    private static final int BTN_SECTION_GENERAL = 1008;
    private static final int BTN_SECTION_CUSTOM = 1009;

    private static final int BTN_BOOST_OFF = 1300;
    private static final int BTN_BOOST_FAST = 1301;
    private static final int BTN_BOOST_EXTREME = 1302;
    private static final int BTN_VOICE_BASE = 1310; // + режим

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

    private static final int BTN_DELETED_MARK = 1400;
    private static final int BTN_OPEN_DELETED_CHAT = 1401;
    private static final int BTN_OPEN_EDITED_CHAT = 1402;
    private static final int BTN_EDITED_MARK = 1403;
    private static final int BTN_KEEP_DAYS = 1404;
    private static final int BTN_OPEN_BY_ID = 1405;
    private static final int BTN_CFG_EXPORT = 1406;
    private static final int BTN_CFG_IMPORT = 1407;
    private static final int BTN_CFG_RESET = 1408;
    private static final int BTN_SEND_STYLE = 1409;
    private static final int BTN_GENERIC_BASE = 2000;

    /** раскрывающиеся блоки: id кнопки «Показать ещё» = BTN_COLLAPSE_BASE + группа */
    private static final int BTN_COLLAPSE_BASE = 3000;
    private static final int GROUP_VOICE = 1;
    private static final int GROUP_MENU_MAIN = 2;
    private static final int GROUP_MENU_CHAT = 3;
    private static final int GROUP_HISTORY_MEDIA = 4;

    private final java.util.HashSet<Integer> expandedGroups = new java.util.HashSet<>();

    private final java.util.HashMap<String, Integer> boolIds = new java.util.HashMap<>();
    private final ArrayList<String> boolKeys = new ArrayList<>();
    private final ArrayList<Boolean> boolDefaults = new ArrayList<>();

    private final int section;

    public PengramSettingsActivity() {
        this(SECTION_ROOT);
    }

    public PengramSettingsActivity(int section) {
        super();
        this.section = section;
    }

    private static final int[] MEDIA_LIMITS = new int[]{0, 1024, 2048, 4096, 8192, 16384, 32768, 65536};

    private PengramHeaderView headerView;
    private boolean ghostExpanded = true;
    private ProfilePreviewView previewView;
    private VoicePreviewView voicePreview;
    private org.telegram.ui.Components.PengramMessagePreviewView previewMessages;
    private org.telegram.ui.Cells.AppIconsSelectorCell appIconsCell;

    @Override
    protected CharSequence getTitle() {
        switch (section) {
            case SECTION_PROFILE: return getString(R.string.PengramSectionProfile);
            case SECTION_GHOST: return getString(R.string.PengramSectionGhost);
            case SECTION_HISTORY: return getString(R.string.PengramSectionSpy);
            case SECTION_APPEARANCE: return getString(R.string.PengramSectionAppearance);
            case SECTION_CHATS: return getString(R.string.PengramSectionChats);
            case SECTION_FREEDOM: return getString(R.string.PengramSectionFreedom);
            case SECTION_MEDIA: return getString(R.string.PengramSectionMedia);
            case SECTION_GENERAL: return getString(R.string.PengramSectionGeneral);
            case SECTION_CUSTOM: return getString(R.string.PengramSectionCustom);
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
            case SECTION_MEDIA: fillMedia(items); break;
            case SECTION_GENERAL: fillGeneral(items); break;
            case SECTION_CUSTOM: fillCustom(items, adapter); break;
            default: fillRoot(items); break;
        }
    }

    /** чекбокс, завязанный на ключ в PengramConfig — чтобы не плодить константы */
    private UItem check(String key, boolean def, CharSequence text) {
        Integer id = boolIds.get(key);
        if (id == null) {
            id = BTN_GENERIC_BASE + boolKeys.size();
            boolIds.put(key, id);
            boolKeys.add(key);
            boolDefaults.add(def);
        }
        return UItem.asCheck(id, text).setChecked(PengramConfig.getBool(key, def));
    }

    /** свитч с подписью — родная ячейка NotificationsCheckCell */
    private UItem checkInfo(String key, boolean def, CharSequence text, CharSequence subtext) {
        Integer id = boolIds.get(key);
        if (id == null) {
            id = BTN_GENERIC_BASE + boolKeys.size();
            boolIds.put(key, id);
            boolKeys.add(key);
            boolDefaults.add(def);
        }
        return UItem.asButtonCheck(id, text, subtext).setChecked(PengramConfig.getBool(key, def));
    }

    /** круглая галочка внутри раскрывающегося блока */
    private UItem subCheck(String key, boolean def, CharSequence text) {
        Integer id = boolIds.get(key);
        if (id == null) {
            id = BTN_GENERIC_BASE + boolKeys.size();
            boolIds.put(key, id);
            boolKeys.add(key);
            boolDefaults.add(def);
        }
        return UItem.asRoundCheckbox(id, text).setChecked(PengramConfig.getBool(key, def)).setPad(1);
    }

    private int boolId(String key) {
        Integer id = boolIds.get(key);
        return id == null ? -1 : id;
    }

    /** раскрыт ли блок */
    private boolean expanded(int group) {
        return expandedGroups.contains(group);
    }

    /** строка-переключатель «Показать ещё ▾» / «Свернуть ▴» под блоком */
    private UItem moreButton(int group, CharSequence moreText) {
        final boolean open = expanded(group);
        return UItem.asShadowCollapseButton(BTN_COLLAPSE_BASE + group, open ? getString(R.string.PengramShowLess) : moreText)
                .setCollapsed(!open)
                .accent();
    }

    private UItem moreButton(int group) {
        return moreButton(group, getString(R.string.PengramShowMore));
    }

    private boolean onGenericClick(UItem item, View view) {
        final int index = item.id - BTN_GENERIC_BASE;
        if (index < 0 || index >= boolKeys.size()) {
            return false;
        }
        final String key = boolKeys.get(index);
        final boolean value = PengramConfig.toggle(key, boolDefaults.get(index));
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        } else if (view instanceof org.telegram.ui.Cells.CheckBoxCell) {
            ((org.telegram.ui.Cells.CheckBoxCell) view).setChecked(value, true);
        } else if (view instanceof org.telegram.ui.Cells.NotificationsCheckCell) {
            ((org.telegram.ui.Cells.NotificationsCheckCell) view).setChecked(value);
        }
        // список перестраиваем всегда и с анимацией: зависимые пункты
        // должны выезжать/сворачиваться прямо при переключении
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        if (previewMessages != null) {
            previewMessages.update();
        }
        return true;
    }

    /** «Вкл» / «Выкл» справа в строке раздела */
    private CharSequence onOff(boolean value) {
        return getString(value ? R.string.PengramValueOn : R.string.PengramValueOff);
    }

    private CharSequence fontName(int font) {
        switch (font) {
            case PengramConfig.FONT_SYSTEM: return getString(R.string.PengramFontSystem);
            case PengramConfig.FONT_SERIF: return getString(R.string.PengramFontSerif);
            case PengramConfig.FONT_MONOSPACE: return getString(R.string.PengramFontMono);
            default: return getString(R.string.PengramFontDefault);
        }
    }

    /** Сколько пунктов интерфейса сейчас скрыто */
    private CharSequence hiddenCountValue() {
        int count = 0;
        final String[] keys = new String[] {
                PengramConfig.KEY_TAB_CONTACTS, PengramConfig.KEY_TAB_CALLS, PengramConfig.KEY_TAB_SETTINGS,
                PengramConfig.KEY_TAB_PROFILE, PengramConfig.KEY_MENU_PENGRAM, PengramConfig.KEY_MENU_GHOST
        };
        for (int a = 0; a < keys.length; ++a) {
            if (PengramConfig.getBool(keys[a], false)) {
                count++;
            }
        }
        final boolean[] flags = new boolean[] {
                PengramConfig.hideMenuNewGroup, PengramConfig.hideMenuSavedMessages, PengramConfig.hideMenuSettings, PengramConfig.hideMenuTheme,
                PengramConfig.hideChatSearch, PengramConfig.hideChatTranslate, PengramConfig.hideChatClearHistory, PengramConfig.hideChatWallpaper,
                PengramConfig.hideChatShortcut, PengramConfig.hideChatReport, PengramConfig.hideChatCall, PengramConfig.hideChatAutoDelete
        };
        for (int a = 0; a < flags.length; ++a) {
            if (flags[a]) {
                count++;
            }
        }
        return count <= 0 ? "" : LocaleController.formatPluralString("PengramHiddenItems", count);
    }

    /** «Вкл · 128» — сколько всего сохранено */
    private CharSequence spySectionValue() {
        final boolean on = PengramConfig.isSavingDeleted() || PengramConfig.isSavingEdited();
        if (!on) {
            return onOff(false);
        }
        final int count = PengramHistory.getCount(0);
        return count > 0 ? (getString(R.string.PengramValueOn) + " \u00b7 " + count) : onOff(true);
    }

    /** Коротко о скорости и голосе */
    private CharSequence mediaSectionValue() {
        final int voice = PengramConfig.getVoiceChangerMode();
        if (voice != PengramVoiceChanger.MODE_OFF) {
            return PengramVoiceChanger.getModeName(voice);
        }
        switch (PengramConfig.getSpeedBoost()) {
            case PengramConfig.BOOST_FAST: return getString(R.string.PengramBoostFast);
            case PengramConfig.BOOST_EXTREME: return getString(R.string.PengramBoostExtreme);
            default: return "";
        }
    }

    private CharSequence markName(int mark) {
        final String text;
        switch (mark) {
            case PengramConfig.MARK_TRASH: text = getString(R.string.PengramMarkTrash); break;
            case PengramConfig.MARK_CROSS: text = getString(R.string.PengramMarkCross); break;
            case PengramConfig.MARK_EYE: text = getString(R.string.PengramMarkEye); break;
            case PengramConfig.MARK_FIRE: text = getString(R.string.PengramMarkFire); break;
            default: return getString(R.string.PengramMarkNone);
        }
        return withMarkIcon(text, PengramConfig.getMarkIcon(mark));
    }

    private CharSequence editedMarkName(int mark) {
        final String text;
        switch (mark) {
            case PengramConfig.MARK_EDIT_PENCIL: text = getString(R.string.PengramMarkPencil); break;
            case PengramConfig.MARK_EDIT_CLOCK: text = getString(R.string.PengramMarkClock); break;
            case PengramConfig.MARK_EDIT_DOT: text = getString(R.string.PengramMarkDot); break;
            default: return getString(R.string.PengramMarkNone);
        }
        return withMarkIcon(text, PengramConfig.getEditedMarkIcon(mark));
    }

    /** дорисовываем к названию сам значок — чтобы выбор был наглядным */
    private CharSequence withMarkIcon(CharSequence text, int icon) {
        final Context context = getContext();
        if (icon == 0 || context == null) {
            return text;
        }
        final android.text.SpannableStringBuilder builder = new android.text.SpannableStringBuilder(text);
        builder.append("  \u200B");
        builder.setSpan(
                new org.telegram.ui.Components.PengramMarkSpan(context, icon, 1.05f, 0f),
                builder.length() - 1, builder.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return builder;
    }

    /** красивое меню выбора значка — как родное телеграмовское */
    private void showMarkPicker(boolean edited) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final org.telegram.ui.ActionBar.BottomSheet.Builder builder =
                new org.telegram.ui.ActionBar.BottomSheet.Builder(context, false, getResourceProvider());
        builder.setTitle(getString(edited ? R.string.PengramEditedMarkTitle : R.string.PengramDeletedMarkTitle), true);

        final LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        final int count = edited ? 4 : 5;
        final org.telegram.ui.Cells.RadioColorCell[] cells = new org.telegram.ui.Cells.RadioColorCell[count];
        for (int a = 0; a < cells.length; ++a) {
            final int mark = a;
            cells[a] = new org.telegram.ui.Cells.RadioColorCell(context, getResourceProvider());
            cells[a].setPadding(dp(4), 0, dp(4), 0);
            cells[a].setCheckColor(Theme.getColor(Theme.key_radioBackground, getResourceProvider()), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, getResourceProvider()));
            cells[a].setTextAndValue(edited ? editedMarkName(mark) : markName(mark),
                    (edited ? PengramConfig.getEditedMark() : PengramConfig.getDeletedMark()) == mark);
            cells[a].setBackground(Theme.getSelectorDrawable(false));
            cells[a].setOnClickListener(v -> {
                if (edited) {
                    PengramConfig.setEditedMark(mark);
                    if (mark != PengramConfig.MARK_EDIT_NONE && !PengramConfig.isMarkingEdited()) {
                        PengramConfig.setBool(PengramConfig.KEY_MARK_EDITED, true);
                    }
                } else {
                    PengramConfig.setDeletedMark(mark);
                }
                for (int b = 0; b < cells.length; ++b) {
                    cells[b].setChecked(b == mark, true);
                }
                if (previewMessages != null) {
                    previewMessages.update();
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (visibleDialog != null) {
                            visibleDialog.dismiss();
                        }
                    } catch (Throwable ignore) {}
                }, 180);
            });
            linearLayout.addView(cells[a], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }
        builder.setCustomView(linearLayout);
        showDialog(builder.create());
    }

    /** «Кнопка скрыта» / «Кнопка видна» — чтобы было сразу понятно, что делает галочка */
    private CharSequence tabStateText(String key) {
        return getString(PengramConfig.getBool(key, false) ? R.string.PengramTabHidden : R.string.PengramTabVisible);
    }

    /** живой пример выбранного стиля отправки */
    private CharSequence sendStyleInfo() {
        final int style = PengramConfig.getSendTextStyle();
        if (style == PengramConfig.SEND_STYLE_OFF) {
            return getString(R.string.PengramSendStyleInfo);
        }
        final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(getString(R.string.PengramSendStyleExample));
        sb.append(" ");
        sb.append(styledSample(style));
        return sb;
    }

    /** образец текста, оформленный выбранным стилем */
    private CharSequence styledSample(int style) {
        final String sample = getString(R.string.PengramSendStyleSample);
        if (style == PengramConfig.SEND_STYLE_WIDE) {
            return org.telegram.messenger.PengramTextStyle.toWide(sample);
        }
        final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(sample);
        final int len = sb.length();
        switch (style) {
            case PengramConfig.SEND_STYLE_BOLD:
                sb.setSpan(new org.telegram.ui.Components.TypefaceSpan(AndroidUtilities.bold()), 0, len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case PengramConfig.SEND_STYLE_ITALIC:
                sb.setSpan(new android.text.style.StyleSpan(Typeface.ITALIC), 0, len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case PengramConfig.SEND_STYLE_MONO:
                sb.setSpan(new org.telegram.ui.Components.TypefaceSpan(Typeface.MONOSPACE), 0, len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case PengramConfig.SEND_STYLE_STRIKE:
                sb.setSpan(new android.text.style.StrikethroughSpan(), 0, len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case PengramConfig.SEND_STYLE_UNDERLINE:
                sb.setSpan(new android.text.style.UnderlineSpan(), 0, len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case PengramConfig.SEND_STYLE_SPOILER:
                sb.setSpan(new android.text.style.BackgroundColorSpan(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()), .35f)),
                        0, len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case PengramConfig.SEND_STYLE_QUOTE:
                sb.insert(0, "\u258E ");
                break;
        }
        return sb;
    }

    /** выбор автостиля отправляемого текста — с наглядными образцами */
    private void showSendStylePicker() {
        final int[] styles = org.telegram.messenger.PengramTextStyle.ALL_STYLES;
        final CharSequence[] options = new CharSequence[styles.length];
        int selected = 0;
        for (int a = 0; a < styles.length; ++a) {
            final int style = styles[a];
            if (style == PengramConfig.getSendTextStyle()) {
                selected = a;
            }
            final CharSequence name = getString(org.telegram.messenger.PengramTextStyle.getNameRes(style));
            if (style == PengramConfig.SEND_STYLE_OFF) {
                options[a] = name;
            } else {
                final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
                sb.append("   ");
                sb.append(styledSample(style));
                options[a] = sb;
            }
        }
        showChoicePicker(getString(R.string.PengramSendStyle), options, selected, index -> {
            PengramConfig.setSendTextStyle(styles[index]);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    /** Pengram: мгновенно применяем скрытие вкладок к главному экрану */
    private void applyTabsNow() {
        try {
            if (getParentLayout() == null) {
                return;
            }
            final java.util.List<org.telegram.ui.ActionBar.BaseFragment> stack = getParentLayout().getFragmentStack();
            for (int a = 0; a < stack.size(); ++a) {
                final org.telegram.ui.ActionBar.BaseFragment fragment = stack.get(a);
                if (fragment instanceof MainTabsActivity) {
                    ((MainTabsActivity) fragment).checkPengramTabsVisibility();
                }
            }
        } catch (Throwable ignore) {}
    }

    private void fillRoot(ArrayList<UItem> items) {
        if (headerView == null) {
            headerView = new PengramHeaderView(getContext());
        }
        items.add(UItem.asCustom(headerView));
        items.add(UItem.asShadow(null));

        items.add(UItem.asButton(BTN_SECTION_GENERAL, R.drawable.msg_settings, getString(R.string.PengramSectionGeneral),
                PengramConfig.getSendTextStyle() == PengramConfig.SEND_STYLE_OFF ? "" : getString(PengramTextStyle.getNameRes(PengramConfig.getSendTextStyle()))));
        items.add(UItem.asButton(BTN_SECTION_PROFILE, R.drawable.settings_account, getString(R.string.PengramSectionProfile)));
        items.add(UItem.asButton(BTN_SECTION_APPEARANCE, R.drawable.msg_theme, getString(R.string.PengramSectionAppearance), fontName(PengramConfig.appFont)));
        items.add(UItem.asButton(BTN_SECTION_CUSTOM, R.drawable.msg_customize, getString(R.string.PengramSectionCustom), markName(PengramConfig.getDeletedMark())));
        items.add(UItem.asButton(BTN_SECTION_CHATS, R.drawable.settings_chat, getString(R.string.PengramSectionChats), hiddenCountValue()));
        items.add(UItem.asButton(BTN_SECTION_GHOST, R.drawable.msg_secret, getString(R.string.PengramSectionGhost), onOff(PengramConfig.ghostMode)));
        items.add(UItem.asButton(BTN_SECTION_HISTORY, R.drawable.msg_viewchats, getString(R.string.PengramSectionSpy), spySectionValue()));
        items.add(UItem.asButton(BTN_SECTION_MEDIA, R.drawable.settings_data, getString(R.string.PengramSectionMedia), mediaSectionValue()));
        items.add(UItem.asButton(BTN_SECTION_FREEDOM, R.drawable.settings_features, getString(R.string.PengramSectionFreedom)));
        items.add(UItem.asShadow(getString(R.string.PengramSectionsInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramLinksHeader)));
        items.add(UItem.asSettingsCell(BTN_LINK_CHANNEL, R.drawable.msg_channel, getString(R.string.PengramLinkChannel), "@" + LINK_CHANNEL));
        items.add(UItem.asSettingsCell(BTN_LINK_AUTHOR, R.drawable.msg_openprofile, getString(R.string.PengramLinkAuthor), "@" + LINK_AUTHOR));
        items.add(UItem.asShadow(getString(R.string.PengramLinksInfo)));
    }

    /** Основное — мелочи, которые влияют на весь клиент */
    private void fillGeneral(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramGeneralHeader)));
        items.add(checkInfo(PengramConfig.KEY_NO_ROUNDING, false, getString(R.string.PengramNoRounding), getString(R.string.PengramNoRoundingInfo)));
        items.add(checkInfo(PengramConfig.KEY_TIME_SECONDS, false, getString(R.string.PengramTimeSeconds), getString(R.string.PengramTimeSecondsInfo)));
        items.add(checkInfo(PengramConfig.KEY_VIBRATION, true, getString(R.string.PengramVibration), getString(R.string.PengramVibrationInfo)));
        items.add(checkInfo(PengramConfig.KEY_ZALGO, false, getString(R.string.PengramZalgo), getString(R.string.PengramZalgoInfo)));
        items.add(UItem.asShadow(getString(R.string.PengramGeneralInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramSendStyleHeader)));
        items.add(UItem.asSettingsCell(BTN_SEND_STYLE, R.drawable.msg_edit, getString(R.string.PengramSendStyle),
                getString(PengramTextStyle.getNameRes(PengramConfig.getSendTextStyle()))));
        if (PengramConfig.getSendTextStyle() != PengramConfig.SEND_STYLE_OFF) {
            items.add(check(PengramConfig.KEY_SEND_STYLE_CAPTIONS, true, getString(R.string.PengramSendStyleCaptions)));
        }
        items.add(UItem.asShadow(sendStyleInfo()));

        items.add(UItem.asHeader(getString(R.string.PengramToolsHeader)));
        items.add(UItem.asButton(BTN_OPEN_BY_ID, R.drawable.msg_search, getString(R.string.PengramOpenById)));
        items.add(UItem.asShadow(getString(R.string.PengramOpenByIdInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramBackupHeader)));
        items.add(UItem.asButton(BTN_CFG_EXPORT, R.drawable.msg_copy, getString(R.string.PengramBackupExport)));
        items.add(UItem.asButton(BTN_CFG_IMPORT, R.drawable.msg_download, getString(R.string.PengramBackupImport)));
        items.add(UItem.asButton(BTN_CFG_RESET, R.drawable.msg_delete, getString(R.string.PengramBackupReset)).red());
        items.add(UItem.asShadow(getString(R.string.PengramBackupInfo)));
    }

    /** короткая статистика под блоком хранилища */
    private CharSequence historyStatsText() {
        final StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.formatString(R.string.PengramHistorySize, AndroidUtilities.formatFileSize(PengramHistory.getDatabaseSize())));
        final int mediaCount = PengramHistory.getSavedMediaCount();
        if (mediaCount > 0) {
            sb.append('\n');
            sb.append(LocaleController.formatString(R.string.PengramHistoryMediaStats, mediaCount,
                    AndroidUtilities.formatFileSize(PengramHistory.getSavedMediaSize())));
        }
        return sb.toString();
    }

    /** срок хранения сохранённых сообщений */
    private CharSequence keepDaysName(int days) {
        switch (days) {
            case 7: return getString(R.string.PengramKeepDays7);
            case 30: return getString(R.string.PengramKeepDays30);
            case 90: return getString(R.string.PengramKeepDays90);
            case 365: return getString(R.string.PengramKeepDays365);
            default: return getString(R.string.PengramKeepDaysForever);
        }
    }

    private static final int[] KEEP_DAYS = new int[]{0, 7, 30, 90, 365};

    /** универсальный выбор одного значения из списка — как родные диалоги Telegram */
    private void showChoicePicker(CharSequence title, CharSequence[] options, int selected, Utilities.Callback<Integer> onSelected) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final org.telegram.ui.ActionBar.BottomSheet.Builder builder =
                new org.telegram.ui.ActionBar.BottomSheet.Builder(context, false, getResourceProvider());
        builder.setTitle(title, true);

        final LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        final org.telegram.ui.Cells.RadioColorCell[] cells = new org.telegram.ui.Cells.RadioColorCell[options.length];
        for (int a = 0; a < options.length; ++a) {
            final int index = a;
            cells[a] = new org.telegram.ui.Cells.RadioColorCell(context, getResourceProvider());
            cells[a].setPadding(dp(4), 0, dp(4), 0);
            cells[a].setCheckColor(Theme.getColor(Theme.key_radioBackground, getResourceProvider()), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, getResourceProvider()));
            cells[a].setTextAndValue(options[a], selected == a);
            cells[a].setBackground(Theme.getSelectorDrawable(false));
            cells[a].setOnClickListener(v -> {
                for (int b = 0; b < cells.length; ++b) {
                    cells[b].setChecked(b == index, true);
                }
                onSelected.run(index);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (visibleDialog != null) {
                            visibleDialog.dismiss();
                        }
                    } catch (Throwable ignore) {}
                }, 180);
            });
            linearLayout.addView(cells[a], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }
        builder.setCustomView(linearLayout);
        showDialog(builder.create());
    }

    private void showKeepDaysPicker() {
        final CharSequence[] options = new CharSequence[KEEP_DAYS.length];
        int selected = 0;
        for (int a = 0; a < KEEP_DAYS.length; ++a) {
            options[a] = keepDaysName(KEEP_DAYS[a]);
            if (KEEP_DAYS[a] == PengramConfig.getHistoryKeepDays()) {
                selected = a;
            }
        }
        showChoicePicker(getString(R.string.PengramKeepDaysTitle), options, selected, index -> {
            PengramConfig.setHistoryKeepDays(KEEP_DAYS[index]);
            PengramHistory.autoCleanup();
        });
    }

    // ------------------------------------------------- резервная копия настроек

    private void exportSettings() {
        final String json = PengramConfig.exportToJson();
        if (json == null) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupFailed)).show();
            return;
        }
        AndroidUtilities.addToClipboard(json);
        BulletinFactory.of(this).createCopyBulletin(getString(R.string.PengramBackupCopied)).show();
    }

    private void importSettings() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        String clip = null;
        try {
            final android.content.ClipboardManager cm = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                final CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
                clip = text == null ? null : text.toString();
            }
        } catch (Throwable ignore) {}
        if (clip == null || !clip.contains("pengram")) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupNothing)).show();
            return;
        }
        final String json = clip;
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PengramBackupImport));
        builder.setMessage(getString(R.string.PengramBackupImportConfirm));
        builder.setPositiveButton(getString(R.string.PengramBackupApply), (d, w) -> {
            if (PengramConfig.importFromJson(json)) {
                afterSettingsReplaced(getString(R.string.PengramBackupImported));
            } else {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupFailed)).show();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void resetSettings() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PengramBackupReset));
        builder.setMessage(getString(R.string.PengramBackupResetConfirm));
        builder.setPositiveButton(getString(R.string.Reset), (d, w) -> {
            PengramConfig.resetAll();
            afterSettingsReplaced(getString(R.string.PengramBackupResetDone));
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /** после импорта/сброса: подтянуть всё, что кэшируется в других местах */
    private void afterSettingsReplaced(CharSequence text) {
        try {
            getUserConfig().pengramApplyLocalPremiumStatus();
            PengramVoiceChanger.reset();
            org.telegram.messenger.PengramBackgroundService.update(getContext());
        } catch (Throwable ignore) {}
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.done, text).show();
    }

    // ------------------------------------------------- быстрый переход по ID / @имени

    private void showOpenByIdDialog() {
        showTextDialog(getString(R.string.PengramOpenById), "", getString(R.string.PengramOpenByIdHint), getString(R.string.Open), this::openByQuery);
    }

    private void openByQuery(String query) {
        if (query == null) {
            return;
        }
        String q = query.trim();
        if (q.isEmpty()) {
            return;
        }
        final int slash = q.lastIndexOf('/');
        if (q.startsWith("http") && slash >= 0) {
            q = q.substring(slash + 1);
        }
        if (q.startsWith("@")) {
            q = q.substring(1);
        }
        long id = 0;
        try {
            id = Long.parseLong(q);
        } catch (Exception ignore) {}
        if (id == 0) {
            getMessagesController().openByUserName(q, this, 0);
            return;
        }
        final long raw = id;
        final long chatId = raw < 0 ? (raw <= -1000000000000L ? -(raw + 1000000000000L) : -raw) : raw;
        final long userId = raw > 0 ? raw : 0;
        if (userId != 0 && getMessagesController().getUser(userId) != null) {
            presentFragment(ChatActivity.of(userId));
            return;
        }
        if (getMessagesController().getChat(chatId) != null) {
            presentFragment(ChatActivity.of(-chatId));
            return;
        }
        final org.telegram.messenger.MessagesStorage storage = getMessagesStorage();
        storage.getStorageQueue().postRunnable(() -> {
            // уже внутри очереди хранилища, поэтому читаем напрямую (без *Sync, иначе дедлок)
            final TLRPC.User user = userId != 0 ? storage.getUser(userId) : null;
            final TLRPC.Chat chat = user == null ? storage.getChat(chatId) : null;
            AndroidUtilities.runOnUIThread(() -> {
                if (user != null) {
                    getMessagesController().putUser(user, true);
                    presentFragment(ChatActivity.of(user.id));
                } else if (chat != null) {
                    getMessagesController().putChat(chat, true);
                    presentFragment(ChatActivity.of(-chat.id));
                } else {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramOpenByIdNotFound)).show();
                }
            });
        });
    }

    /** Кастомизация — как выглядят сообщения */
    private void fillCustom(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (previewMessages == null) {
            previewMessages = new org.telegram.ui.Components.PengramMessagePreviewView(getContext(), getResourceProvider());
        }
        previewMessages.update();
        items.add(UItem.asCustom(previewMessages));
        items.add(UItem.asShadow(getString(R.string.PengramPreviewInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramDeletedLookHeader)));
        items.add(check(PengramConfig.KEY_FADE_DELETED, true, getString(R.string.PengramFadeDeleted)));
        items.add(UItem.asSettingsCell(BTN_DELETED_MARK, R.drawable.msg_delete, getString(R.string.PengramDeletedMark), markName(PengramConfig.getDeletedMark())));
        items.add(check(PengramConfig.KEY_MARK_EDITED, false, getString(R.string.PengramMarkEditedOption)));
        if (PengramConfig.isMarkingEdited()) {
            items.add(UItem.asSettingsCell(BTN_EDITED_MARK, R.drawable.msg_edit, getString(R.string.PengramEditedMark), editedMarkName(PengramConfig.getEditedMark())));
        }
        items.add(UItem.asShadow(getString(R.string.PengramDeletedLookInfo)));
    }

    private void fillProfile(ArrayList<UItem> items) {
        if (previewView == null) {
            previewView = new ProfilePreviewView(getContext());
        }
        previewView.update();
        items.add(UItem.asCustom(previewView));
        items.add(UItem.asShadow(getString(R.string.PengramIdPreviewInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramIdHeader)));
        items.add(UItem.asRadio(BTN_ID_FORMAT_HIDE, getString(R.string.PengramIdFormatHide)).setChecked(PengramConfig.getIdFormat() == PengramConfig.ID_FORMAT_HIDE));
        items.add(UItem.asRadio(BTN_ID_FORMAT_TELEGRAM, getString(R.string.PengramIdFormatTelegram)).setChecked(PengramConfig.getIdFormat() == PengramConfig.ID_FORMAT_TELEGRAM));
        items.add(UItem.asRadio(BTN_ID_FORMAT_BOT, getString(R.string.PengramIdFormatBot)).setChecked(PengramConfig.getIdFormat() == PengramConfig.ID_FORMAT_BOT));
        items.add(UItem.asShadow(getString(R.string.PengramIdFormatInfo)));

        if (PengramConfig.getIdFormat() != PengramConfig.ID_FORMAT_HIDE) {
            items.add(UItem.asHeader(getString(R.string.PengramIdStyleHeader)));
            items.add(UItem.asRadio(BTN_ID_OFF, getString(R.string.PengramIdStyleOff)).setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_OFF));
            items.add(UItem.asRadio(BTN_ID_ROW, getString(R.string.PengramIdStyleRow)).setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_ROW));
            items.add(UItem.asRadio(BTN_ID_ROW_DC, getString(R.string.PengramIdStyleRowDc)).setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_ROW_DC));
            items.add(UItem.asRadio(BTN_ID_INLINE, getString(R.string.PengramIdStyleInline)).setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_INLINE));
            if (PengramConfig.idStyle != PengramConfig.ID_STYLE_OFF) {
                items.add(UItem.asCheck(BTN_ID_COPY, getString(R.string.PengramIdCopyOnTap)).setChecked(PengramConfig.copyIdOnTap));
            }
            items.add(UItem.asShadow(null));
        }

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
        final boolean saving = PengramConfig.saveDeleted || PengramConfig.saveEdited;
        if (saving) {
            items.add(check(PengramConfig.KEY_SAVE_FOR_MYSELF_SHOW, true, getString(R.string.PengramSaveForMyselfOption)));
            items.add(check(PengramConfig.KEY_SAVE_FOR_MYSELF_DEFAULT, false, getString(R.string.PengramSaveForMyselfDefault)));
            items.add(UItem.asCheck(BTN_SAVE_IN_BOTS, getString(R.string.PengramSaveInBots)).setChecked(PengramConfig.saveInBots));
        }
        items.add(UItem.asShadow(getString(R.string.PengramHistoryInfo2)));

        if (PengramConfig.saveDeleted) {
            items.add(UItem.asHeader(getString(R.string.PengramInChatHeader)));
            items.add(check(PengramConfig.KEY_KEEP_DELETED, true, getString(R.string.PengramKeepDeleted)));
            items.add(UItem.asShadow(getString(R.string.PengramKeepDeletedInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.PengramHistoryStorage)));
        items.add(UItem.asButton(BTN_OPEN_DELETED_CHAT, R.drawable.msg_delete, getString(R.string.PengramOpenDeletedChat),
                String.valueOf(PengramHistory.getCount(0, PengramHistory.ACTION_DELETED))));
        items.add(UItem.asButton(BTN_OPEN_EDITED_CHAT, R.drawable.msg_edit, getString(R.string.PengramOpenEditedChat),
                String.valueOf(PengramHistory.getCount(0, PengramHistory.ACTION_EDITED))));
        items.add(UItem.asButton(BTN_HIST_OPEN, R.drawable.msg_viewchats, getString(R.string.PengramHistoryOpen)));
        items.add(UItem.asSettingsCell(BTN_KEEP_DAYS, R.drawable.msg_autodelete, getString(R.string.PengramKeepDays), keepDaysName(PengramConfig.getHistoryKeepDays())));
        items.add(UItem.asButton(BTN_HIST_CLEAR, R.drawable.msg_delete, getString(R.string.PengramHistoryClearButton)).red());
        items.add(UItem.asShadow(historyStatsText()));

        if (PengramConfig.saveDeleted) {
            items.add(UItem.asHeader(getString(R.string.PengramMediaHeader)));
            items.add(UItem.asCheck(BTN_MEDIA_SAVE, getString(R.string.PengramMediaSave)).setChecked(PengramConfig.saveDeletedMedia));
            if (PengramConfig.saveDeletedMedia) {
                items.add(UItem.asButton(BTN_MEDIA_FOLDER, getString(R.string.PengramMediaFolder), PengramConfig.getMediaFolder()));
                items.add(UItem.asButton(BTN_MEDIA_PATTERN, getString(R.string.PengramMediaPattern), PengramConfig.getMediaPattern()));
                if (expanded(GROUP_HISTORY_MEDIA)) {
                    items.add(UItem.asShadow(getString(R.string.PengramMediaInfo)));

                    items.add(UItem.asHeader(getString(R.string.PengramMediaLimitHeader)));
                    int chosen = 0;
                    final String[] titles = new String[MEDIA_LIMITS.length];
                    for (int i = 0; i < MEDIA_LIMITS.length; ++i) {
                        if (MEDIA_LIMITS[i] == PengramConfig.getMediaMaxSizeMb()) {
                            chosen = i;
                        }
                        titles[i] = MEDIA_LIMITS[i] == 0 ? getString(R.string.PengramMediaLimitOff) : (MEDIA_LIMITS[i] / 1024) + " GB";
                    }
                    items.add(UItem.asSlideView(titles, chosen, index -> {
                        PengramConfig.setMediaMaxSizeMb(MEDIA_LIMITS[index]);
                        if (listView != null && listView.adapter != null) listView.adapter.update(true);
                    }));
                    items.add(UItem.asButton(BTN_MEDIA_CLEAR, R.drawable.msg_delete, getString(R.string.PengramMediaClear)).red());
                }
                items.add(moreButton(GROUP_HISTORY_MEDIA));
                items.add(UItem.asShadow(LocaleController.formatString(R.string.PengramMediaLimitInfo, AndroidUtilities.formatFileSize(PengramHistory.getSavedMediaSize()))));
            } else {
                items.add(UItem.asShadow(getString(R.string.PengramMediaInfo)));
            }
        }
    }

    private void fillGhost(ArrayList<UItem> items) {
        final boolean autoOffline = PengramConfig.getBool(PengramConfig.KEY_GHOST_AUTO_OFFLINE, true);
        final boolean voiceRead = PengramConfig.getBool(PengramConfig.KEY_GHOST_DONT_SEND_VOICE_READ, true);
        final boolean reactions = PengramConfig.getBool(PengramConfig.KEY_GHOST_DONT_SEND_REACTIONS, false);
        final int enabled = (PengramConfig.dontSendRead ? 1 : 0)
                + (PengramConfig.dontSendStoryViews ? 1 : 0)
                + (PengramConfig.hideOnline ? 1 : 0)
                + (PengramConfig.dontSendTyping ? 1 : 0)
                + (autoOffline ? 1 : 0)
                + (voiceRead ? 1 : 0)
                + (reactions ? 1 : 0);

        items.add(UItem.asHeader(getString(R.string.PengramGhostHeader)));
        items.add(
            UItem.asExpandableSwitch(BTN_GHOST, getString(R.string.PengramGhostMode), enabled + "/7")
                .setChecked(PengramConfig.ghostMode)
                .setCollapsed(!ghostExpanded)
                .setClickCallback(v -> {
                    PengramConfig.toggleGhostMode();
                    if (v instanceof TextCheckCell2) {
                        ((TextCheckCell2) v).setChecked(PengramConfig.ghostMode);
                    }
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                })
        );
        if (ghostExpanded) {
            items.add(UItem.asRoundCheckbox(BTN_DONT_READ, getString(R.string.PengramGhostDontRead)).setChecked(PengramConfig.dontSendRead).setPad(1));
            items.add(UItem.asRoundCheckbox(BTN_DONT_STORY, getString(R.string.PengramGhostDontStory)).setChecked(PengramConfig.dontSendStoryViews).setPad(1));
            items.add(UItem.asRoundCheckbox(BTN_HIDE_ONLINE, getString(R.string.PengramGhostHideOnline)).setChecked(PengramConfig.hideOnline).setPad(1));
            items.add(UItem.asRoundCheckbox(BTN_DONT_TYPE, getString(R.string.PengramGhostDontType)).setChecked(PengramConfig.dontSendTyping).setPad(1));
            items.add(subCheck(PengramConfig.KEY_GHOST_AUTO_OFFLINE, true, getString(R.string.PengramGhostAutoOffline)));
            items.add(subCheck(PengramConfig.KEY_GHOST_DONT_SEND_VOICE_READ, true, getString(R.string.PengramGhostDontSendVoiceRead)));
            items.add(subCheck(PengramConfig.KEY_GHOST_DONT_SEND_REACTIONS, false, getString(R.string.PengramGhostDontSendReactions)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramGhostInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramGhostExtraHeader)));
        items.add(checkInfo(PengramConfig.KEY_GHOST_STORIES_WARN, false, getString(R.string.PengramGhostStoriesWarn), getString(R.string.PengramGhostStoriesWarnInfo)));
        items.add(checkInfo(PengramConfig.KEY_GHOST_SEND_DELAY, false, getString(R.string.PengramGhostSendDelay), getString(R.string.PengramGhostSendDelayInfo)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramTrackHeader)));
        items.add(UItem.asCheck(BTN_SAVE_READ_DATE, getString(R.string.PengramSaveReadDate)).setChecked(PengramConfig.saveReadDate));
        items.add(UItem.asCheck(BTN_SAVE_LAST_ONLINE, getString(R.string.PengramSaveLastOnline)).setChecked(PengramConfig.saveLastOnline));
        items.add(UItem.asShadow(getString(R.string.PengramTrackInfo)));
    }

    private void fillFreedom(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramFreedomHeader)));
        items.add(UItem.asCheck(BTN_SCREENSHOTS, getString(R.string.PengramAllowScreenshots)).setChecked(PengramConfig.allowScreenshots));
        if (PengramConfig.allowScreenshots) {
            items.add(UItem.asCheck(BTN_NO_SS_NOTIFY, getString(R.string.PengramNoScreenshotNotify)).setChecked(PengramConfig.noScreenshotNotify));
        }
        items.add(UItem.asCheck(BTN_FORWARDS, getString(R.string.PengramAllowForwards)).setChecked(PengramConfig.allowForwards));
        items.add(UItem.asCheck(BTN_KEEP_ONCE, getString(R.string.PengramKeepOnce)).setChecked(PengramConfig.keepOnceMedia));
        items.add(UItem.asShadow(getString(R.string.PengramFreedomInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramAdsHeader)));
        items.add(UItem.asCheck(BTN_ADS, getString(R.string.PengramHideAds)).setChecked(PengramConfig.hideAds));
        items.add(UItem.asShadow(getString(R.string.PengramAdsInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramPremiumHeader)));
        items.add(UItem.asCheck(BTN_LOCAL_PREMIUM, getString(R.string.PengramLocalPremium)).setChecked(PengramConfig.localPremium));
        if (PengramConfig.localPremium) {
            items.add(check(PengramConfig.KEY_PREMIUM_STATUS, true, getString(R.string.PengramLocalPremiumStatus)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramPremiumInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramBackgroundHeader)));
        items.add(check(PengramConfig.KEY_BACKGROUND_MODE, false, getString(R.string.PengramBackgroundMode)));
        if (PengramConfig.isBackgroundMode()) {
            items.add(check(PengramConfig.KEY_BACKGROUND_SILENT, true, getString(R.string.PengramBackgroundSilent)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramBackgroundInfo)));
    }

    private void fillAppearance(ArrayList<UItem> items) {
        if (appIconsCell == null && getContext() != null) {
            appIconsCell = new org.telegram.ui.Cells.AppIconsSelectorCell(getContext(), this, currentAccount);
        }
        if (appIconsCell != null) {
            items.add(UItem.asHeader(getString(R.string.AppIcon)));
            items.add(UItem.asCustom(appIconsCell, 104));
            items.add(UItem.asShadow(getString(R.string.PengramAppIconInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.PengramAppearanceHeader)));
        items.add(UItem.asRadio(BTN_FONT_DEFAULT, getString(R.string.PengramFontDefault)).setChecked(PengramConfig.appFont == PengramConfig.FONT_DEFAULT));
        items.add(UItem.asRadio(BTN_FONT_SYSTEM, getString(R.string.PengramFontSystem)).setChecked(PengramConfig.appFont == PengramConfig.FONT_SYSTEM));
        items.add(UItem.asRadio(BTN_FONT_SERIF, getString(R.string.PengramFontSerif)).setChecked(PengramConfig.appFont == PengramConfig.FONT_SERIF));
        items.add(UItem.asRadio(BTN_FONT_MONO, getString(R.string.PengramFontMono)).setChecked(PengramConfig.appFont == PengramConfig.FONT_MONOSPACE));
        items.add(UItem.asShadow(null));
        items.add(UItem.asCheck(BTN_CHAT_MENU, getString(R.string.PengramChatMenu)).setChecked(PengramConfig.chatMenuEnabled));
        if (PengramConfig.chatMenuEnabled) {
            items.add(UItem.asRadio(BTN_CHAT_MENU_TOP, getString(R.string.PengramChatMenuTop)).setChecked(PengramConfig.chatMenuPosition == PengramConfig.MENU_POS_TOP));
            items.add(UItem.asRadio(BTN_CHAT_MENU_BOTTOM, getString(R.string.PengramChatMenuBottom)).setChecked(PengramConfig.chatMenuPosition == PengramConfig.MENU_POS_BOTTOM));
        }
        items.add(UItem.asShadow(null));
    }

    private void fillMedia(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramBoostHeader)));
        items.add(UItem.asRadio(BTN_BOOST_OFF, getString(R.string.PengramBoostOff)).setChecked(PengramConfig.getSpeedBoost() == PengramConfig.BOOST_OFF));
        items.add(UItem.asRadio(BTN_BOOST_FAST, getString(R.string.PengramBoostFast)).setChecked(PengramConfig.getSpeedBoost() == PengramConfig.BOOST_FAST));
        items.add(UItem.asRadio(BTN_BOOST_EXTREME, getString(R.string.PengramBoostExtreme)).setChecked(PengramConfig.getSpeedBoost() == PengramConfig.BOOST_EXTREME));
        items.add(UItem.asShadow(getString(R.string.PengramBoostInfo)));

        final int mode = PengramConfig.getVoiceChangerMode();

        if (voicePreview == null) {
            voicePreview = new VoicePreviewView(getContext());
        }
        voicePreview.update();
        items.add(UItem.asCustom(voicePreview));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramVoiceHeader)));
        final boolean voiceExpanded = expanded(GROUP_VOICE);
        int voiceHidden = 0;
        for (int a = 0; a < PengramVoiceChanger.MODES.length; ++a) {
            final int m = PengramVoiceChanger.MODES[a];
            if (!voiceExpanded && !isPrimaryVoiceMode(m) && m != mode) {
                voiceHidden++;
                continue;
            }
            items.add(UItem.asRadio2(BTN_VOICE_BASE + m, PengramVoiceChanger.getModeName(m), voiceModeDescription(m)).setChecked(mode == m));
        }
        if (voiceHidden > 0 || voiceExpanded) {
            items.add(moreButton(GROUP_VOICE, LocaleController.formatString(R.string.PengramVoiceMore, voiceHidden)));
        }
        if (mode == PengramVoiceChanger.MODE_CUSTOM) {
            items.add(UItem.asHeader(getString(R.string.PengramVoicePitch)));
            items.add(UItem.asIntSlideView(
                    1,
                    -12, PengramConfig.getVoiceChangerPitch(), 12,
                    value -> value > 0 ? "+" + value + " st" : value + " st",
                    value -> {
                        PengramConfig.setVoiceChangerPitch(value);
                        PengramVoiceChanger.reset();
                        if (voicePreview != null) {
                            voicePreview.update();
                        }
                    }
            ));
        }
        if (mode == PengramVoiceChanger.MODE_ANONYMOUS) {
            items.add(UItem.asShadow(getString(R.string.PengramVoiceAnonymousInfo)));
        } else {
            items.add(UItem.asShadow(getString(R.string.PengramVoiceInfo)));
        }
    }

    /** эффекты, которые всегда видны в свёрнутом списке */
    private static boolean isPrimaryVoiceMode(int mode) {
        return mode == PengramVoiceChanger.MODE_OFF
                || mode == PengramVoiceChanger.MODE_ANONYMOUS
                || mode == PengramVoiceChanger.MODE_FEMALE
                || mode == PengramVoiceChanger.MODE_MALE
                || mode == PengramVoiceChanger.MODE_CHILD;
    }

    private CharSequence voiceModeDescription(int mode) {
        switch (mode) {
            case PengramVoiceChanger.MODE_OFF: return getString(R.string.PengramVoiceOffValue);
            case PengramVoiceChanger.MODE_ROBOT: return getString(R.string.PengramVoiceRobotValue);
            case PengramVoiceChanger.MODE_ANONYMOUS: return getString(R.string.PengramVoiceAnonymousValue);
            case PengramVoiceChanger.MODE_ALIEN: return getString(R.string.PengramVoiceAlienValue);
            case PengramVoiceChanger.MODE_RADIO: return getString(R.string.PengramVoiceRadioValue);
            case PengramVoiceChanger.MODE_PHONE: return getString(R.string.PengramVoicePhoneValue);
            case PengramVoiceChanger.MODE_CAVE: return getString(R.string.PengramVoiceCaveValue);
            case PengramVoiceChanger.MODE_UNDERWATER: return getString(R.string.PengramVoiceUnderwaterValue);
            case PengramVoiceChanger.MODE_WHISPER: return getString(R.string.PengramVoiceWhisperValue);
            case PengramVoiceChanger.MODE_DEMON: return getString(R.string.PengramVoiceDemonValue);
        }
        if (mode == PengramVoiceChanger.MODE_CUSTOM) {
            final int st = PengramConfig.getVoiceChangerPitch();
            return (st > 0 ? "+" + st : String.valueOf(st)) + " st";
        }
        final float factor = PengramVoiceChanger.getPitchFactor(mode);
        final int semitones = Math.round((float) (12.0 * Math.log(factor) / Math.log(2.0)));
        return String.format(java.util.Locale.US, "%s%d st  \u00b7  \u00d7%.2f", semitones > 0 ? "+" : "", semitones, factor);
    }

    private void fillChats(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramTabsHeader)));
        items.add(checkInfo(PengramConfig.KEY_TAB_CONTACTS, false, getString(R.string.PengramHideTabContacts), tabStateText(PengramConfig.KEY_TAB_CONTACTS)));
        items.add(checkInfo(PengramConfig.KEY_TAB_CALLS, false, getString(R.string.PengramHideTabCalls), tabStateText(PengramConfig.KEY_TAB_CALLS)));
        items.add(checkInfo(PengramConfig.KEY_TAB_SETTINGS, false, getString(R.string.PengramHideTabSettings), tabStateText(PengramConfig.KEY_TAB_SETTINGS)));
        items.add(checkInfo(PengramConfig.KEY_TAB_PROFILE, false, getString(R.string.PengramHideTabProfile), tabStateText(PengramConfig.KEY_TAB_PROFILE)));
        items.add(UItem.asShadow(getString(R.string.PengramTabsInfo2)));

        items.add(UItem.asHeader(getString(R.string.PengramDialogsHeader)));
        items.add(checkInfo(PengramConfig.KEY_HIDE_STORIES, false, getString(R.string.PengramHideStories), getString(R.string.PengramHideStoriesInfo)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramForwardHeader)));
        items.add(checkInfo(PengramConfig.KEY_FORWARD_LOCK, true, getString(R.string.PengramForwardLock), getString(R.string.PengramForwardLockInfo)));
        if (PengramConfig.isForwardLockEnabled()) {
            items.add(check(PengramConfig.KEY_FORWARD_DONE_ALERT, true, getString(R.string.PengramForwardDoneAlert)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramForwardInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramHideMenuHeader)));
        items.add(check(PengramConfig.KEY_MENU_PENGRAM, false, getString(R.string.PengramHideMenuPengram)));
        items.add(check(PengramConfig.KEY_MENU_GHOST, false, getString(R.string.PengramHideMenuGhost)));
        if (expanded(GROUP_MENU_MAIN)) {
            items.add(UItem.asCheck(BTN_HIDE_MENU_NEW_GROUP, getString(R.string.PengramHideMenuNewGroup)).setChecked(PengramConfig.hideMenuNewGroup));
            items.add(UItem.asCheck(BTN_HIDE_MENU_SAVED, getString(R.string.PengramHideMenuSaved)).setChecked(PengramConfig.hideMenuSavedMessages));
            items.add(UItem.asCheck(BTN_HIDE_MENU_SETTINGS, getString(R.string.PengramHideMenuSettings)).setChecked(PengramConfig.hideMenuSettings));
            items.add(UItem.asCheck(BTN_HIDE_MENU_THEME, getString(R.string.PengramHideMenuTheme)).setChecked(PengramConfig.hideMenuTheme));
        }
        items.add(moreButton(GROUP_MENU_MAIN));
        items.add(UItem.asShadow(getString(R.string.PengramHideMenuInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramHideChatHeader)));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_SEARCH, getString(R.string.PengramHideChatSearch)).setChecked(PengramConfig.hideChatSearch));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_TRANSLATE, getString(R.string.PengramHideChatTranslate)).setChecked(PengramConfig.hideChatTranslate));
        items.add(UItem.asCheck(BTN_HIDE_CHAT_CLEAR, getString(R.string.PengramHideChatClear)).setChecked(PengramConfig.hideChatClearHistory));
        if (expanded(GROUP_MENU_CHAT)) {
            items.add(UItem.asCheck(BTN_HIDE_CHAT_WALLPAPER, getString(R.string.PengramHideChatWallpaper)).setChecked(PengramConfig.hideChatWallpaper));
            items.add(UItem.asCheck(BTN_HIDE_CHAT_SHORTCUT, getString(R.string.PengramHideChatShortcut)).setChecked(PengramConfig.hideChatShortcut));
            items.add(UItem.asCheck(BTN_HIDE_CHAT_REPORT, getString(R.string.PengramHideChatReport)).setChecked(PengramConfig.hideChatReport));
            items.add(UItem.asCheck(BTN_HIDE_CHAT_CALL, getString(R.string.PengramHideChatCall)).setChecked(PengramConfig.hideChatCall));
            items.add(UItem.asCheck(BTN_HIDE_CHAT_AUTODELETE, getString(R.string.PengramHideChatAutoDelete)).setChecked(PengramConfig.hideChatAutoDelete));
        }
        items.add(moreButton(GROUP_MENU_CHAT));
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
        if (item.id >= BTN_COLLAPSE_BASE && item.id < BTN_COLLAPSE_BASE + 100) {
            final int group = item.id - BTN_COLLAPSE_BASE;
            if (!expandedGroups.remove(group)) {
                expandedGroups.add(group);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            return;
        }
        if (item.id >= BTN_GENERIC_BASE && onGenericClick(item, view)) {
            if (item.id == boolId(PengramConfig.KEY_TAB_CONTACTS) || item.id == boolId(PengramConfig.KEY_TAB_CALLS)
                    || item.id == boolId(PengramConfig.KEY_TAB_SETTINGS) || item.id == boolId(PengramConfig.KEY_TAB_PROFILE)) {
                applyTabsNow();
            }
            if (item.id == boolId(PengramConfig.KEY_PREMIUM_STATUS)) {
                getUserConfig().pengramApplyLocalPremiumStatus();
            }
            if (item.id == boolId(PengramConfig.KEY_BACKGROUND_MODE)) {
                org.telegram.messenger.PengramBackgroundService.update(getContext());
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
            } else if (item.id == boolId(PengramConfig.KEY_KEEP_DELETED)
                    || item.id == boolId(PengramConfig.KEY_FADE_DELETED)
                    || item.id == boolId(PengramConfig.KEY_MARK_EDITED)) {
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
            }
            return;
        }
        if (item.id >= BTN_VOICE_BASE && item.id <= BTN_VOICE_BASE + PengramVoiceChanger.MODE_PHONE) {
            PengramConfig.setVoiceChangerMode(item.id - BTN_VOICE_BASE);
            PengramVoiceChanger.reset();
            if (voicePreview != null) {
                voicePreview.update();
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            return;
        }
        switch (item.id) {
            case BTN_DELETED_MARK:
                showMarkPicker(false);
                return;
            case BTN_EDITED_MARK:
                showMarkPicker(true);
                return;
            case BTN_KEEP_DAYS:
                showKeepDaysPicker();
                return;
            case BTN_SEND_STYLE:
                showSendStylePicker();
                return;
            case BTN_OPEN_BY_ID:
                showOpenByIdDialog();
                return;
            case BTN_CFG_EXPORT:
                exportSettings();
                return;
            case BTN_CFG_IMPORT:
                importSettings();
                return;
            case BTN_CFG_RESET:
                resetSettings();
                return;
            case BTN_OPEN_DELETED_CHAT:
                presentFragment(new PengramHistoryChatActivity(0, PengramHistoryChatActivity.MODE_DELETED));
                return;
            case BTN_OPEN_EDITED_CHAT:
                presentFragment(new PengramHistoryChatActivity(0, PengramHistoryChatActivity.MODE_EDITED));
                return;
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
            case BTN_SECTION_MEDIA:
                presentFragment(new PengramSettingsActivity(SECTION_MEDIA));
                return;
            case BTN_SECTION_GENERAL:
                presentFragment(new PengramSettingsActivity(SECTION_GENERAL));
                return;
            case BTN_SECTION_CUSTOM:
                presentFragment(new PengramSettingsActivity(SECTION_CUSTOM));
                return;
            case BTN_LINK_CHANNEL:
                openLink(LINK_CHANNEL);
                return;
            case BTN_LINK_AUTHOR:
                openLink(LINK_AUTHOR);
                return;
            case BTN_ID_FORMAT_HIDE:
                PengramConfig.setIdFormat(PengramConfig.ID_FORMAT_HIDE);
                updateAll = true;
                break;
            case BTN_ID_FORMAT_TELEGRAM:
                PengramConfig.setIdFormat(PengramConfig.ID_FORMAT_TELEGRAM);
                updateAll = true;
                break;
            case BTN_ID_FORMAT_BOT:
                PengramConfig.setIdFormat(PengramConfig.ID_FORMAT_BOT);
                updateAll = true;
                break;
            case BTN_BOOST_OFF:
                PengramConfig.setSpeedBoost(PengramConfig.BOOST_OFF);
                updateAll = true;
                break;
            case BTN_BOOST_FAST:
                PengramConfig.setSpeedBoost(PengramConfig.BOOST_FAST);
                updateAll = true;
                break;
            case BTN_BOOST_EXTREME:
                PengramConfig.setSpeedBoost(PengramConfig.BOOST_EXTREME);
                updateAll = true;
                break;
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
                ghostExpanded = !ghostExpanded;
                if (view instanceof TextCheckCell2) {
                    ((TextCheckCell2) view).setChecked(PengramConfig.ghostMode);
                }
                updateAll = true;
                break;
            case BTN_HIDE_ONLINE:
                PengramConfig.toggleHideOnline();
                if (view instanceof org.telegram.ui.Cells.CheckBoxCell) {
                    ((org.telegram.ui.Cells.CheckBoxCell) view).setChecked(PengramConfig.hideOnline, true);
                } else if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(PengramConfig.hideOnline);
                }
                updateAll = true;
                break;
            case BTN_DONT_READ:
                PengramConfig.toggleDontSendRead();
                if (view instanceof org.telegram.ui.Cells.CheckBoxCell) {
                    ((org.telegram.ui.Cells.CheckBoxCell) view).setChecked(PengramConfig.dontSendRead, true);
                } else if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(PengramConfig.dontSendRead);
                }
                updateAll = true;
                break;
            case BTN_DONT_TYPE:
                PengramConfig.toggleDontSendTyping();
                if (view instanceof org.telegram.ui.Cells.CheckBoxCell) {
                    ((org.telegram.ui.Cells.CheckBoxCell) view).setChecked(PengramConfig.dontSendTyping, true);
                } else if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(PengramConfig.dontSendTyping);
                }
                updateAll = true;
                break;
            case BTN_DONT_STORY:
                PengramConfig.toggleDontSendStoryViews();
                if (view instanceof org.telegram.ui.Cells.CheckBoxCell) {
                    ((org.telegram.ui.Cells.CheckBoxCell) view).setChecked(PengramConfig.dontSendStoryViews, true);
                } else if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(PengramConfig.dontSendStoryViews);
                }
                updateAll = true;
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
                updateAll = true;
                break;
            case BTN_HIST_EDITED:
                PengramConfig.toggleSaveEdited();
                updateAll = true;
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
                getUserConfig().pengramApplyLocalPremiumStatus();
                updateAll = true;
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
        if (previewMessages != null) {
            previewMessages.update();
        }
        if (updateAll && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (headerView != null) {
            headerView.setPaused(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (headerView != null) {
            headerView.setPaused(false);
        }
        // значения справа в строках разделов могли измениться на вложенном экране
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final String username;
        if (item.id == BTN_LINK_CHANNEL) {
            username = LINK_CHANNEL;
        } else if (item.id == BTN_LINK_AUTHOR) {
            username = LINK_AUTHOR;
        } else {
            return false;
        }
        final String url = "https://t.me/" + username;
        ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_copy, getString(R.string.PengramCopyLink), () -> {
                    AndroidUtilities.addToClipboard(url);
                    BulletinFactory.of(this).createCopyLinkBulletin().show();
                })
                .add(R.drawable.msg_share, getString(R.string.PengramShareLink), () -> shareLink(url))
                .setGravity(android.view.Gravity.RIGHT)
                .show();
        return true;
    }

    private void openLink(String username) {
        if (getContext() == null) {
            return;
        }
        Browser.openUrl(getContext(), "https://t.me/" + username);
    }

    private void shareLink(String url) {
        if (getParentActivity() == null) {
            return;
        }
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_TEXT, url);
            getParentActivity().startActivityForResult(android.content.Intent.createChooser(intent, getString(R.string.ShareFile)), 500);
        } catch (Exception ignore) {}
    }

    private void showTextDialog(String title, String current, String hint, Utilities.Callback<String> onDone) {
        showTextDialog(title, current, hint, getString(R.string.Save), onDone);
    }

    private void showTextDialog(String title, String current, String hint, String button, Utilities.Callback<String> onDone) {
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
        builder.setPositiveButton(button, (d, w) -> onDone.run(editText.getText().toString()));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /** Шапка настроек: живой 3D-пингвин, название и короткое описание */
    private class PengramHeaderView extends LinearLayout {

        private PengramPenguinView penguinView;
        private android.widget.ImageView fallbackLogo;

        public PengramHeaderView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(16), dp(14), dp(16), dp(18));

            final FrameLayout penguinContainer = new FrameLayout(context);

            fallbackLogo = new android.widget.ImageView(context);
            fallbackLogo.setImageResource(R.drawable.pengram_logo);
            fallbackLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            penguinContainer.addView(fallbackLogo, LayoutHelper.createFrame(96, 96, Gravity.CENTER));

            try {
                penguinView = new PengramPenguinView(context);
                penguinView.setOnTapListener(() -> {
                    try {
                        if (PengramConfig.isVibrationEnabled()) {
                            penguinView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                        }
                    } catch (Exception ignore) {}
                });
                penguinView.whenReady(() -> {
                    if (fallbackLogo != null) {
                        fallbackLogo.animate().alpha(0f).setDuration(180).start();
                    }
                });
                penguinContainer.addView(penguinView, LayoutHelper.createFrame(132, 132, Gravity.CENTER));
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
                penguinView = null;
            }

            addView(penguinContainer, LayoutHelper.createLinear(140, 132, Gravity.CENTER_HORIZONTAL));

            final TextView title = new TextView(context);
            title.setText("Pengram");
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

            final String versionText = getAppVersion();
            final TextView subtitle = new TextView(context);
            subtitle.setText(versionText);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
            subtitle.setLineSpacing(dp(2), 1f);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
            subtitle.setPadding(dp(10), dp(4), dp(10), dp(4));
            subtitle.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, getResourceProvider()), 8, 8));
            subtitle.setOnClickListener(v -> {
                AndroidUtilities.addToClipboard(versionText);
                BulletinFactory.of(PengramSettingsActivity.this).createCopyBulletin(getString(R.string.TextCopied)).show();
            });
            addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 4, 24, 0));
        }

        public void setPaused(boolean paused) {
            if (penguinView != null) {
                penguinView.setPaused(paused);
            }
        }
    }

    /** версия приложения — показывается под пингвином */
    private static String getAppVersion() {
        String version = org.telegram.messenger.BuildVars.BUILD_VERSION_STRING;
        int code = 0;
        try {
            final android.content.pm.PackageInfo info = ApplicationLoader.applicationContext
                    .getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            if (info != null) {
                if (info.versionName != null) {
                    version = info.versionName;
                }
                if (info.versionCode > 0) {
                    code = info.versionCode;
                }
            }
        } catch (Throwable ignore) {}
        if (code > 0) {
            return LocaleController.formatString(R.string.PengramHeaderVersion, version, code);
        }
        return LocaleController.formatString(R.string.PengramHeaderVersionShort, version);
    }

    /**
     * Живое превью: так карточка профиля будет выглядеть с текущими настройками.
     */
    /** «Островок» с живой волной: наглядно показывает выбранный эффект голоса */
    private class VoicePreviewView extends View {

        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float[] seeds = new float[48];

        private String title = "";
        private String subtitle = "";
        private float pitch = 1f;
        private long startTime = System.currentTimeMillis();

        public VoicePreviewView(Context context) {
            super(context);
            titlePaint.setTextSize(dp(17));
            titlePaint.setTypeface(AndroidUtilities.bold());
            subtitlePaint.setTextSize(dp(13));
            final java.util.Random random = new java.util.Random(42);
            for (int i = 0; i < seeds.length; ++i) {
                seeds[i] = 0.25f + random.nextFloat() * 0.75f;
            }
        }

        public void update() {
            final int mode = PengramConfig.getVoiceChangerMode();
            title = PengramVoiceChanger.getModeName(mode);
            pitch = PengramVoiceChanger.getPitchFactor();
            if (mode == PengramVoiceChanger.MODE_OFF) {
                subtitle = getString(R.string.PengramVoicePreviewOff);
            } else {
                final int semitones = Math.round((float) (12.0 * Math.log(pitch) / Math.log(2.0)));
                subtitle = LocaleController.formatString(R.string.PengramVoicePreviewOn,
                        (semitones > 0 ? "+" : "") + semitones);
            }
            startTime = System.currentTimeMillis();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(132), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int width = getWidth();
            final int height = getHeight();
            if (width <= 0) {
                return;
            }
            final boolean enabled = PengramConfig.getVoiceChangerMode() != PengramVoiceChanger.MODE_OFF;
            final int accent = Theme.getColor(enabled ? Theme.key_switch2TrackChecked : Theme.key_windowBackgroundWhiteGrayText, getResourceProvider());

            rect.set(dp(14), dp(10), width - dp(14), height - dp(10));
            cardPaint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{
                            Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()), Theme.multAlpha(accent, 0.14f)),
                            Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()), Theme.multAlpha(accent, 0.04f))
                    }, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, dp(16), dp(16), cardPaint);

            titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            subtitlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
            canvas.drawText(title, rect.left + dp(16), rect.top + dp(26), titlePaint);
            canvas.drawText(subtitle, rect.left + dp(16), rect.top + dp(46), subtitlePaint);

            // волна: чем выше питч — тем чаще и «звонче» столбики
            final float time = (System.currentTimeMillis() - startTime) / 1000f;
            final float left = rect.left + dp(16);
            final float right = rect.right - dp(16);
            final float centerY = rect.bottom - dp(30);
            final float barWidth = dp(3);
            final float gap = dp(3);
            final int count = (int) ((right - left) / (barWidth + gap));
            barPaint.setColor(accent);
            for (int i = 0; i < count; ++i) {
                final float seed = seeds[i % seeds.length];
                final double wave = Math.sin(i * 0.45f * pitch + time * 3.2f * pitch);
                float amplitude = (float) (0.35f + 0.65f * Math.abs(wave)) * seed;
                if (!enabled) {
                    amplitude *= 0.5f;
                }
                final float h = dp(6) + amplitude * dp(26);
                final float x = left + i * (barWidth + gap);
                rect.set(x, centerY - h / 2f, x + barWidth, centerY + h / 2f);
                barPaint.setAlpha((int) (255 * (enabled ? 0.9f : 0.45f)));
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, barPaint);
            }
            if (isAttachedToWindow()) {
                invalidate();
            }
        }
    }

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
