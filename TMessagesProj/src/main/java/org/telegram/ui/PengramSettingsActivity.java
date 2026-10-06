package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramAntiCrash;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramLyrics;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.PengramTextStyle;
import org.telegram.messenger.PengramVoiceChanger;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.SwipeGestureSettingsView;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PengramPenguinView;
import androidx.recyclerview.widget.LinearLayoutManager;
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
    private static final int BTN_PROFILE_HISTORY = 406;

    private static final int BTN_MEDIA_SAVE = 450;
    private static final int BTN_MEDIA_FOLDER = 451;
    private static final int BTN_MEDIA_PATTERN = 452;

    private static final int BTN_SCREENSHOTS = 500;
    private static final int BTN_NO_SS_NOTIFY = 501;
    private static final int BTN_FORWARDS = 502;
    private static final int BTN_KEEP_ONCE = 503;
    private static final int BTN_ADS = 510;
    private static final int BTN_LOCAL_PREMIUM = 520;
    private static final int BTN_FONT_PICK = 530;
    private static final int BTN_SHARE_PHONE_MODE = 535;
    private static final int BTN_EMPTY_COVER = 536;
    private static final int BTN_PLAYER_ACCENT = 537;
    private static final int BTN_CHAT_MENU = 540;
    private static final int BTN_CHAT_MENU_TOP = 541;
    private static final int BTN_CHAT_MENU_BOTTOM = 542;
    private static final int BTN_SENDER_AVATAR_POSITION = 547;
    private static final int BTN_INPUT_ANIMATION = 548;
    private static final int BTN_INPUT_ANIMATION_SPEED = 549;
    private static final int BTN_INPUT_ANIMATION_INTENSITY = 550;
    private static final int BTN_QUICK_ADD = 543;
    private static final int BTN_QUICK_ACTION_BASE = 560;

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
    public static final int SECTION_PENGUIN = 10;
    public static final int SECTION_PLAYER = 11;
    public static final int SECTION_CHAT_ACTIONS = 12;
    public static final int SECTION_CHAT_MESSAGES = 13;
    public static final int SECTION_CHAT_INTERFACE = 14;
    public static final int SECTION_CHAT_MENUS = 15;
    public static final int SECTION_ABOUT = 16;
    public static final int SECTION_AI = 17;
    public static final int SECTION_LYRICS = 18;

    private static final int BTN_SECTION_PROFILE = 1001;
    private static final int BTN_SECTION_GHOST = 1002;
    private static final int BTN_SECTION_HISTORY = 1003;
    private static final int BTN_SECTION_APPEARANCE = 1004;
    private static final int BTN_SECTION_CHATS = 1005;
    private static final int BTN_SECTION_FREEDOM = 1006;
    private static final int BTN_SECTION_MEDIA = 1007;
    private static final int BTN_SECTION_GENERAL = 1008;
    private static final int BTN_SECTION_CUSTOM = 1009;
    private static final int BTN_SECTION_PENGUIN = 1010;
    private static final int BTN_SECTION_PLAYER = 1011;
    private static final int BTN_SECTION_CHAT_ACTIONS = 1012;
    private static final int BTN_SECTION_CHAT_MESSAGES = 1013;
    private static final int BTN_SECTION_CHAT_INTERFACE = 1014;
    private static final int BTN_SECTION_CHAT_MENUS = 1015;
    private static final int BTN_SECTION_AI = 1016;
    private static final int BTN_SECTION_LYRICS = 1017;

    // AI: сервисы, роли и поведение ответа
    private static final int BTN_ORIGINAL_NAME = 1710;
    private static final int BTN_MONET_STRENGTH = 1720;
    private static final int BTN_AI_ADD_SERVICE = 1700;
    private static final int BTN_AI_ADD_ROLE = 1701;
    private static final int BTN_AI_STREAM = 1702;
    private static final int BTN_AI_ONLY_ANSWER = 1703;
    private static final int BTN_AI_AS_QUOTE = 1704;
    private static final int BTN_AI_HISTORY = 1705;
    private static final int BTN_AI_DEPTH = 1706;
    private static final int BTN_AI_RESET = 1707;
    /** строки сервисов и ролей: к базе прибавляется номер в списке */
    private static final int BTN_AI_SERVICE_BASE = 9000;
    private static final int BTN_AI_ROLE_BASE = 9500;

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
    private static final int BTN_REG_STYLE_PICK = 1410;
    private static final int BTN_REG_PLACE = 1411;
    private static final int BTN_REG_ICON = 1412;
    private static final int BTN_TITLE_MODE = 1413;
    private static final int BTN_TITLE_CUSTOM = 1414;
    private static final int BTN_TABBAR_SIZE = 1415;
    private static final int BTN_MENU_ITEMS = 1416;
    private static final int BTN_SETTINGS_ITEMS = 1417;
    private static final int BTN_FONT_SIZE = 1419;
    private static final int BTN_BUBBLE_RADIUS = 1420;
    private static final int BTN_SWIPE_ACTION = 1421;
    private static final int BTN_CHAT_ITEMS = 1418;
    private static final int BTN_PENGUIN_SKIN = 1422;
    private static final int BTN_FIND_BY_ID = 1423;
    private static final int BTN_SELECTION_LIMIT = 1424;
    private static final int BTN_AVATAR_POS = 1425;
    private static final int BTN_LYRICS_ANIM = 1426;
    private static final int BTN_LYRICS_ALIGN = 1427;
    private static final int BTN_PLAYER_BG = 1428;
    private static final int BTN_COVER_SHAPE = 1429;
    private static final int BTN_LYRICS_CLEAR = 1430;
    private static final int BTN_PLAYER_STYLE = 1431;
    private static final int BTN_TRACK_FORWARD_MODE = 1446;
    private static final int BTN_QUICK_TILES = 1450;
    private static final int BTN_CHAT_LOOK = 1432;
    private static final int BTN_CONSTRUCTOR = 1433;
    private static final int BTN_HEADER_LYRICS_ANIM = 1434;
    private static final int BTN_LYRICS_SOURCE = 1435;

    // время поверх медиа (стикеры / кружки / фото)
    private static final int BTN_MEDIA_TIME_DEFAULT = 1440;
    private static final int BTN_MEDIA_TIME_STICKERS = 1441;
    private static final int BTN_MEDIA_TIME_MEDIA = 1442;
    /** экран выбора анимации удаления */
    private static final int BTN_DELETE_EFFECT = 1490;
    /** экран обхода блокировок */
    private static final int BTN_BYPASS = 1491;
    private static final int BTN_ANTICRASH_STATS = 1492;
    private static final int BTN_ANTICRASH_LOG = 1493;
    private static final int BTN_CRASH_REPORTS = 1494;
    private static final int BTN_CRASH_AUTOCOPY = 1495;
    private static final int BTN_SECTION_ABOUT = 1496;
    private static final int BTN_ABOUT_COPY = 1497;
    private static final int BTN_RESET_SECTION = 1498;
    private static final int BTN_RESET_ALL = 1499;
    private static final int BTN_HIST_MAX = 407;
    /** строки выбора скина пингвина: BTN_SKIN_BASE + номер скина */
    private static final int BTN_SKIN_BASE = 1600;
    private static final int BTN_SEARCH = 1447;
    /** строки результатов поиска: BTN_SEARCH_BASE + номер в списке найденного */
    private static final int BTN_SEARCH_BASE = 7000;
    private static final int BTN_PENGUIN_FLIP = 1443;
    private static final int BTN_PENGUIN_DANCE = 1444;
    private static final int BTN_PENGUIN_STRAIGHTEN = 1445;
    /** переключатели «чужих» настроек Telegram и LiteMode */
    private static final int BTN_EXTRA_BASE = 4000;
    private static final int BTN_GENERIC_BASE = 2000;

    /** раскрывающиеся блоки: id кнопки «Показать ещё» = BTN_COLLAPSE_BASE + группа */
    private static final int BTN_COLLAPSE_BASE = 3000;
    private static final int GROUP_VOICE = 1;
    private static final int GROUP_MENU_MAIN = 2;
    private static final int GROUP_MENU_CHAT = 3;
    private static final int GROUP_HISTORY_MEDIA = 4;
    private static final int GROUP_EFFECTS = 5;
    private static final int GROUP_MD3 = 6;
    private static final int GROUP_MONET = 7;

    /** ключ состояния раскрытого блока (состояние переживает выход с экрана) */
    private static String expandedKey(int group) {
        return "uiExpandedGroup_" + group;
    }

    private final java.util.HashMap<String, Integer> boolIds = new java.util.HashMap<>();
    private final ArrayList<String> boolKeys = new ArrayList<>();
    private final ArrayList<Boolean> boolDefaults = new ArrayList<>();

    private final int section;

    public PengramSettingsActivity() {
        this(SECTION_ROOT);
    }

    public PengramSettingsActivity(int section) {
        this(section, 0);
    }

    /**
     * Pengram: открыть раздел и показать в нём конкретную настройку.
     *
     * Так работает переход из поиска: человек нашёл функцию — и попадает прямо
     * к ней, где бы она ни лежала, а строка на пару секунд подсвечивается,
     * чтобы её не пришлось выискивать глазами.
     */
    public PengramSettingsActivity(int section, int highlightRes) {
        super();
        this.section = section;
        this.highlightRes = highlightRes;
    }

    /** строка, к которой нужно прокрутить сразу после открытия раздела */
    private final int highlightRes;

    private static final int[] MEDIA_LIMITS = new int[]{0, 1024, 2048, 4096, 8192, 16384, 32768, 65536};

    private PengramHeaderView headerView;
    /** раскрыт ли список подпунктов режима призрака (помним между заходами) */
    private static boolean isGhostExpanded() {
        return PengramConfig.getBool("uiGhostExpanded", true);
    }
    private ProfilePreviewView previewView;
    private VoicePreviewView voicePreview;
    private org.telegram.ui.Components.PengramVoicePickerView voicePicker;
    private org.telegram.ui.Components.PengramMessagePreviewView previewMessages;
    private org.telegram.ui.Cells.AppIconsSelectorCell appIconsCell;

    @Override
    protected CharSequence getTitle() {
        return sectionTitle(section);
    }

    /**
     * Ряд быстрых плиток над разделами.
     *
     * <p>По умолчанию его нет вовсе: четыре разноцветных квадрата на главном экране
     * выглядели случайным набором. Теперь пользователь сам собирает ряд в
     * «Внешний вид → Плитки на главном» — показываем только то, что он выбрал,
     * в постоянном порядке. Долгое нажатие по ряду открывает тот же выбор.
     */
    private org.telegram.ui.Components.PengramQuickToggles quickTogglesView;

    private CharSequence quickTileName(int tile) {
        switch (tile) {
            case PengramConfig.QUICK_TILE_DELETED: return getString(R.string.PengramQuickSpy);
            case PengramConfig.QUICK_TILE_ADS: return getString(R.string.PengramQuickAds);
            case PengramConfig.QUICK_TILE_PREMIUM: return getString(R.string.PengramQuickPremium);
            case PengramConfig.QUICK_TILE_GHOST:
            default: return getString(R.string.PengramQuickGhost);
        }
    }

    private org.telegram.ui.Components.PengramQuickToggles quickToggles() {
        final Runnable refresh = () -> {
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        };
        if (quickTogglesView == null) {
            quickTogglesView = new org.telegram.ui.Components.PengramQuickToggles(getContext());
            quickTogglesView.setOnLongClickListener(v -> {
                showQuickTilesPicker();
                return true;
            });
        }
        quickTogglesView.clear();
        for (int tile = 0; tile < PengramConfig.QUICK_TILE_COUNT; ++tile) {
            if (!PengramConfig.isQuickTileOn(tile)) {
                continue;
            }
            switch (tile) {
                case PengramConfig.QUICK_TILE_GHOST:
                    quickTogglesView.add(R.drawable.msg_secret, quickTileName(tile),
                            IconBackgroundColors.GREEN.bottom,
                            new org.telegram.ui.Components.PengramQuickToggles.Toggle() {
                                @Override public boolean isOn() { return PengramConfig.ghostMode; }
                                @Override public void toggle() { PengramConfig.toggleGhostMode(); }
                            }, refresh);
                    break;
                case PengramConfig.QUICK_TILE_DELETED:
                    quickTogglesView.add(R.drawable.msg_viewchats, quickTileName(tile),
                            IconBackgroundColors.RED.bottom,
                            new org.telegram.ui.Components.PengramQuickToggles.Toggle() {
                                @Override public boolean isOn() { return PengramConfig.saveDeleted; }
                                @Override public void toggle() { PengramConfig.toggleSaveDeleted(); }
                            }, refresh);
                    break;
                case PengramConfig.QUICK_TILE_ADS:
                    quickTogglesView.add(R.drawable.msg_block, quickTileName(tile),
                            IconBackgroundColors.ORANGE.bottom,
                            new org.telegram.ui.Components.PengramQuickToggles.Toggle() {
                                @Override public boolean isOn() { return PengramConfig.hideAds; }
                                @Override public void toggle() { PengramConfig.toggleHideAds(); }
                            }, refresh);
                    break;
                case PengramConfig.QUICK_TILE_PREMIUM:
                    quickTogglesView.add(R.drawable.msg_premium_liststar, quickTileName(tile),
                            IconBackgroundColors.PURPLE.bottom,
                            new org.telegram.ui.Components.PengramQuickToggles.Toggle() {
                                @Override public boolean isOn() { return PengramConfig.localPremium; }
                                @Override public void toggle() { PengramConfig.toggleLocalPremium(); }
                            }, refresh);
                    break;
            }
        }
        quickTogglesView.update();
        return quickTogglesView;
    }

    /** подпись строки «Плитки на главном»: сколько выбрано */
    private CharSequence quickTilesValue() {
        final int count = PengramConfig.getQuickTilesCount();
        return count == 0 ? getString(R.string.PengramQuickTilesNone)
                : LocaleController.formatString(R.string.PengramQuickTilesCount, count, PengramConfig.QUICK_TILE_COUNT);
    }

    /** выбор плиток: несколько галочек в одном листе, применяем по кнопке */
    private void showQuickTilesPicker() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final org.telegram.ui.ActionBar.BottomSheet.Builder builder =
                new org.telegram.ui.ActionBar.BottomSheet.Builder(context, false, getResourceProvider());
        builder.setTitle(getString(R.string.PengramQuickTiles), true);

        final LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        final boolean[] checks = new boolean[PengramConfig.QUICK_TILE_COUNT];
        for (int a = 0; a < PengramConfig.QUICK_TILE_COUNT; ++a) {
            final int index = a;
            checks[a] = PengramConfig.isQuickTileOn(a);
            final org.telegram.ui.Cells.CheckBoxCell cell =
                    new org.telegram.ui.Cells.CheckBoxCell(context, 1, getResourceProvider());
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setText(quickTileName(a), "", checks[a], false);
            cell.setPadding(dp(LocaleController.isRTL ? 16 : 8), 0, dp(LocaleController.isRTL ? 8 : 16), 0);
            cell.setOnClickListener(v -> {
                // применяем сразу: список под листом перестраивается на глазах,
                // и видно, как ряд появляется или исчезает
                checks[index] = !checks[index];
                ((org.telegram.ui.Cells.CheckBoxCell) v).setChecked(checks[index], true);
                PengramConfig.setQuickTileOn(index, checks[index]);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
            linearLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }
        builder.setCustomView(linearLayout);
        showDialog(builder.create());
    }

    /** карточка-шапка раздела: цветной значок, название и зачем сюда заходить */
    private org.telegram.ui.Components.PengramSectionHero heroView;

    private void addSectionHero(ArrayList<UItem> items) {
        // в разделах, которые и так открываются большим превью или шапкой с пингвином,
        // вторая крупная карточка подряд только мешает
        if (section == SECTION_ROOT || section == SECTION_PENGUIN
                || section == SECTION_PROFILE || section == SECTION_CUSTOM) {
            return;
        }
        final int info = sectionInfo(section);
        if (info == 0 || getContext() == null) {
            return;
        }
        if (heroView == null) {
            final IconBackgroundColors colors = sectionColors(section);
            heroView = new org.telegram.ui.Components.PengramSectionHero(getContext(),
                    sectionIcon(section), sectionTitle(section), getString(info), colors.top, colors.bottom);
        }
        items.add(UItem.asCustom(heroView));
        items.add(UItem.asShadow(null));
    }

    /** цвет раздела — тот же, что у его строки в корне настроек */
    public static IconBackgroundColors sectionColors(int section) {
        switch (section) {
            case SECTION_GENERAL: return IconBackgroundColors.GRAY;
            case SECTION_PROFILE: return IconBackgroundColors.BLUE;
            case SECTION_APPEARANCE: return IconBackgroundColors.PURPLE;
            case SECTION_CUSTOM: return IconBackgroundColors.ORANGE;
            case SECTION_CHATS:
            case SECTION_CHAT_ACTIONS:
            case SECTION_CHAT_MESSAGES:
            case SECTION_CHAT_INTERFACE:
            case SECTION_CHAT_MENUS: return IconBackgroundColors.BLUE_ALT;
            case SECTION_GHOST: return IconBackgroundColors.GREEN;
            case SECTION_HISTORY: return IconBackgroundColors.RED;
            case SECTION_MEDIA: return IconBackgroundColors.BLUE_DEEP;
            case SECTION_PLAYER: return IconBackgroundColors.ORANGE_DEEP;
            case SECTION_PENGUIN: return IconBackgroundColors.BLUE_LIGHT;
            case SECTION_FREEDOM: return IconBackgroundColors.CYAN;
            case SECTION_ABOUT: return IconBackgroundColors.BLUE_LIGHT;
            case SECTION_AI: return IconBackgroundColors.PURPLE;
            case SECTION_LYRICS: return IconBackgroundColors.ORANGE;
            default: return IconBackgroundColors.GRAY;
        }
    }

    /** одна строка о том, что умеет раздел */
    public static int sectionInfo(int section) {
        switch (section) {
            case SECTION_GENERAL: return R.string.PengramHeroGeneral;
            case SECTION_PROFILE: return R.string.PengramHeroProfile;
            case SECTION_APPEARANCE: return R.string.PengramHeroAppearance;
            case SECTION_CUSTOM: return R.string.PengramHeroCustom;
            case SECTION_CHATS: return R.string.PengramHeroChats;
            case SECTION_CHAT_ACTIONS: return R.string.PengramHeroChatActions;
            case SECTION_CHAT_MESSAGES: return R.string.PengramHeroChatMessages;
            case SECTION_CHAT_INTERFACE: return R.string.PengramHeroChatInterface;
            case SECTION_CHAT_MENUS: return R.string.PengramHeroChatMenus;
            case SECTION_GHOST: return R.string.PengramHeroGhost;
            case SECTION_HISTORY: return R.string.PengramHeroSpy;
            case SECTION_MEDIA: return R.string.PengramHeroMedia;
            case SECTION_PLAYER: return R.string.PengramHeroPlayer;
            case SECTION_PENGUIN: return R.string.PengramHeroPenguin;
            case SECTION_FREEDOM: return R.string.PengramHeroFreedom;
            case SECTION_ABOUT: return R.string.PengramHeroAbout;
            case SECTION_AI: return R.string.PengramHeroAI;
            case SECTION_LYRICS: return R.string.PengramHeroLyrics;
            default: return 0;
        }
    }

    /** иконка раздела — та же, что на главном экране настроек */
    public static int sectionIcon(int section) {
        switch (section) {
            case SECTION_GENERAL: return R.drawable.msg_settings;
            case SECTION_PROFILE: return R.drawable.settings_account;
            case SECTION_APPEARANCE: return R.drawable.msg_theme;
            case SECTION_CUSTOM: return R.drawable.msg_customize;
            case SECTION_CHATS:
            case SECTION_CHAT_ACTIONS:
            case SECTION_CHAT_MESSAGES:
            case SECTION_CHAT_INTERFACE:
            case SECTION_CHAT_MENUS: return R.drawable.settings_chat;
            case SECTION_GHOST: return R.drawable.msg_secret;
            case SECTION_HISTORY: return R.drawable.msg_viewchats;
            case SECTION_MEDIA: return R.drawable.settings_data;
            case SECTION_PLAYER: return R.drawable.msg_played;
            case SECTION_PENGUIN: return R.drawable.pengram_penguin_glyph;
            case SECTION_FREEDOM: return R.drawable.settings_features;
            case SECTION_ABOUT: return R.drawable.msg_info;
            case SECTION_AI: return R.drawable.msg_bot;
            case SECTION_LYRICS: return R.drawable.msg_msgbubble3;
            default: return R.drawable.msg_settings;
        }
    }

    /** название раздела настроек — одно на все экраны и на поиск */
    public static CharSequence sectionTitle(int section) {
        switch (section) {
            case SECTION_PROFILE: return getString(R.string.PengramSectionProfile);
            case SECTION_GHOST: return getString(R.string.PengramSectionGhost);
            case SECTION_HISTORY: return getString(R.string.PengramSectionSpy);
            case SECTION_APPEARANCE: return getString(R.string.PengramSectionAppearance);
            case SECTION_CHATS: return getString(R.string.PengramSectionChats);
            case SECTION_CHAT_ACTIONS: return getString(R.string.PengramSubsectionActions);
            case SECTION_CHAT_MESSAGES: return getString(R.string.PengramSubsectionMessages);
            case SECTION_CHAT_INTERFACE: return getString(R.string.PengramSubsectionInterface);
            case SECTION_CHAT_MENUS: return getString(R.string.PengramSubsectionMenus);
            case SECTION_FREEDOM: return getString(R.string.PengramSectionFreedom);
            case SECTION_MEDIA: return getString(R.string.PengramSectionMedia);
            case SECTION_GENERAL: return getString(R.string.PengramSectionGeneral);
            case SECTION_CUSTOM: return getString(R.string.PengramSectionCustom);
            case SECTION_PENGUIN: return getString(R.string.PengramSectionPenguin);
            case SECTION_PLAYER: return getString(R.string.PengramSectionPlayer);
            case SECTION_ABOUT: return getString(R.string.PengramAbout);
            case SECTION_AI: return getString(R.string.PengramSectionAI);
            case SECTION_LYRICS: return getString(R.string.PengramLyricsSection);
            default: return getString(R.string.PengramSettings);
        }
    }

    /** текст в строке поиска; null — поиск закрыт */
    private String searchQuery;
    /** что сейчас показано в выдаче: разделы по номерам строк */
    private final ArrayList<int[]> searchResults = new ArrayList<>();

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        // Поиск прямо внутри настроек Pengram: не нужно помнить, в каком разделе лежит функция.
        final ActionBarMenu menu = actionBar.createMenu();
        final ActionBarMenuItem searchItem = menu.addItem(BTN_SEARCH, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        searchQuery = "";
                        updateList();
                    }

                    @Override
                    public void onSearchCollapse() {
                        searchQuery = null;
                        updateList();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        searchQuery = editText.getText() == null ? "" : editText.getText().toString();
                        updateList();
                    }
                });
        searchItem.setSearchFieldHint(getString(R.string.Search));
        searchItem.setContentDescription(getString(R.string.Search));
        if (highlightRes != 0) {
            AndroidUtilities.runOnUIThread(() -> pengramJumpTo(highlightRes), 120);
        }
        return view;
    }

    /** прокрутить список к строке с таким заголовком и подсветить её */
    private void pengramJumpTo(int stringRes) {
        if (listView == null || listView.adapter == null || stringRes == 0) {
            return;
        }
        final CharSequence target = getString(stringRes);
        if (TextUtils.isEmpty(target)) {
            return;
        }
        int position = -1;
        for (int i = 0; i < listView.adapter.getItemCount(); ++i) {
            final UItem item = listView.adapter.getItem(i);
            if (item != null && item.text != null && TextUtils.equals(target, item.text)) {
                position = i;
                break;
            }
        }
        if (position < 0) {
            return;
        }
        final int found = position;
        if (listView.getLayoutManager() instanceof LinearLayoutManager) {
            ((LinearLayoutManager) listView.getLayoutManager()).scrollToPositionWithOffset(found, AndroidUtilities.dp(96));
        }
        // подсветка включается после прокрутки — иначе ячейки ещё нет на экране
        AndroidUtilities.runOnUIThread(() -> {
            if (listView != null) {
                listView.highlightRow(() -> found, 2000);
            }
        }, 180);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (searchQuery != null && actionBar != null && actionBar.isSearchFieldVisible()) {
            if (invoked) {
                actionBar.closeSearchField();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    /** выдача поиска по всем разделам сразу */
    private void fillSearch(ArrayList<UItem> items) {
        searchResults.clear();
        final String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase();
        if (query.length() == 0) {
            items.add(UItem.asShadow(getString(R.string.PengramSearchHint)));
            return;
        }
        for (int[] entry : PengramSearchIndex.ITEMS) {
            final String title = getString(entry[0]);
            if (TextUtils.isEmpty(title) || title.startsWith("LOC_ERR")) {
                continue;
            }
            final CharSequence sectionName = sectionTitle(entry[1]);
            final boolean matches = title.toLowerCase().contains(query)
                    || (sectionName != null && sectionName.toString().toLowerCase().contains(query));
            if (!matches) {
                continue;
            }
            items.add(UItem.asSettingsCell(BTN_SEARCH_BASE + searchResults.size(),
                    sectionIcon(entry[1]), title, sectionName));
            searchResults.add(entry);
            if (searchResults.size() >= 40) {
                break;
            }
        }
        if (searchResults.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.NoResult)));
        } else {
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PengramConfig.init();
        if (searchQuery != null) {
            fillSearch(items);
            return;
        }
        addSectionHero(items);
        switch (section) {
            case SECTION_PROFILE: fillProfile(items); break;
            case SECTION_GHOST: fillGhost(items); break;
            case SECTION_HISTORY: fillHistory(items); break;
            case SECTION_APPEARANCE: fillAppearance(items); break;
            case SECTION_CHATS: fillChats(items); break;
            case SECTION_CHAT_ACTIONS: fillChatActions(items); break;
            case SECTION_CHAT_MESSAGES: fillChatMessages(items); break;
            case SECTION_CHAT_INTERFACE: fillChatInterface(items); break;
            case SECTION_CHAT_MENUS: fillChatMenus(items); break;
            case SECTION_FREEDOM: fillFreedom(items); break;
            case SECTION_MEDIA: fillMedia(items); break;
            case SECTION_GENERAL: fillGeneral(items); break;
            case SECTION_CUSTOM: fillCustom(items, adapter); break;
            case SECTION_PENGUIN: fillPenguin(items); break;
            case SECTION_PLAYER: fillPlayer(items); break;
            case SECTION_ABOUT: fillAbout(items); break;
            case SECTION_AI: fillAI(items); break;
            case SECTION_LYRICS: fillLyrics(items); break;
            default: fillRoot(items); break;
        }
        addResetRow(items);
    }

    // ------------------------------------------------------------ сброс настроек

    /**
     * Ключи, которые относятся к разделу. Звёздочка на конце — префикс.
     * Таблица нужна кнопке «сбросить раздел»: она удаляет ровно эти ключи,
     * а всё остальное (другие разделы, состояние экранов) не трогает.
     */
    private static String[] sectionKeys(int section) {
        switch (section) {
            case SECTION_PROFILE:
                return new String[]{"id*", "regDate*", "regTapText", "copyIdOnTap", "hidePhoneNumber", "originalName",
                        "hideSharePhoneOption", "sharePhoneDefault", "sharePhoneMode", "historyRowInProfile",
                        "groupAvatarPos", "coverShape", "showAccountsInSettings"};
            case SECTION_GHOST:
                return new String[]{"ghost*", "dontSend*", "hideOnline"};
            case SECTION_HISTORY:
                return new String[]{"save*", "history*", "keepDeletedInChat", "fadeDeleted", "markEdited",
                        "deletedMark", "editedMark", "forceDeleteForAll", "resend*", "keepOnceMedia",
                        "mediaFolder", "mediaPattern", "mediaMaxSizeMb", "mediaToGallery"};
            case SECTION_APPEARANCE:
                return new String[]{"appFont", "dialogAvatar*", "dialogSenderAvatar*", "hideBubbleTail",
                        "hideEditedLabel", "hideStories", "hideWriteButton", "mediaTime*", "title*",
                        "tabBarSize", "hideTab*", "forceSnow", "md3*"};
            case SECTION_CHATS:
            case SECTION_CHAT_ACTIONS:
            case SECTION_CHAT_MESSAGES:
            case SECTION_CHAT_INTERFACE:
            case SECTION_CHAT_MENUS:
                return new String[]{"chat*", "menu*", "hideMenu*", "hideChat*", "settingsOrder*",
                        "pengramCardOnTop", "inputAnimation*", "sendTextStyle", "sendStyleCaptions",
                        "keepFormatting", "selectionLimit", "speedBoost", "deleteEffect*",
                        "forward*", "trackForward*"};
            case SECTION_FREEDOM:
                return new String[]{"allowForwards", "allowScreenshots", "noScreenshotNotify", "hideAds",
                        "localPremium*", "backgroundMode", "backgroundSilentIcon", "antiCrash*"};
            case SECTION_MEDIA:
                return new String[]{"media*", "saveDeletedMedia", "voiceChanger*", "trackForward*"};
            case SECTION_GENERAL:
                return new String[]{"noNumberRounding", "timeWithSeconds", "inAppVibration", "zalgoFilter",
                        "quickAction*", "sendTextStyle"};
            case SECTION_CUSTOM:
                return new String[]{"deletedMark", "editedMark", "deleteEffect*", "markEdited",
                        "fadeDeleted", "keepDeletedInChat"};
            case SECTION_PENGUIN:
                return new String[]{"penguin*"};
            case SECTION_PLAYER:
                return new String[]{"player*", "newPlayer", "coverShape", "trackForward*", "musicForward*", "musicSmartArtist"};
            case SECTION_LYRICS:
                return new String[]{"lyrics*", "headerLyrics*"};
            default:
                return null;
        }
    }

    /** красная кнопка внизу раздела: вернуть этот раздел к заводским значениям */
    private void addResetRow(ArrayList<UItem> items) {
        if (searchQuery != null || section == SECTION_ROOT || section == SECTION_ABOUT) {
            return;
        }
        if (sectionKeys(section) == null) {
            return;
        }
        items.add(UItem.asButton(BTN_RESET_SECTION, R.drawable.msg_reset, getString(R.string.PengramResetSection)).red());
        items.add(UItem.asShadow(getString(R.string.PengramResetSectionInfo)));
    }

    /** экран «О программе»: всё, что нужно приложить к баг-репорту */
    private void fillAbout(ArrayList<UItem> items) {
        aboutRowIndex = 0;
        aboutValues.clear();
        items.add(UItem.asHeader(getString(R.string.PengramAboutBuildHeader)));
        items.add(aboutRow(R.string.PengramAboutVersion, org.telegram.messenger.PengramVersion.shortLine()));
        items.add(aboutRow(R.string.PengramAboutBase, org.telegram.messenger.PengramVersion.telegramVersion()));
        items.add(aboutRow(R.string.PengramAboutPackage, org.telegram.messenger.PengramVersion.packageName()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramAboutDeviceHeader)));
        items.add(aboutRow(R.string.PengramAboutDevice, org.telegram.messenger.PengramVersion.device()));
        items.add(aboutRow(R.string.PengramAboutSystem, org.telegram.messenger.PengramVersion.androidVersion()));
        items.add(aboutRow(R.string.PengramAboutAbi, org.telegram.messenger.PengramVersion.abi()));
        items.add(UItem.asButton(BTN_ABOUT_COPY, R.drawable.msg_copy, getString(R.string.PengramAboutCopy)));
        items.add(UItem.asShadow(getString(R.string.PengramAboutCopyInfo)));

        items.add(sectionRow(BTN_SECTION_FREEDOM, IconBackgroundColors.CYAN, R.drawable.msg_report,
                getString(R.string.PengramCrashReports), org.telegram.ui.Components.PengramCrashDialogs.summary()));
        items.add(UItem.asShadow(getString(R.string.PengramCrashReportsMovedInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramResetHeader)));
        items.add(UItem.asButton(BTN_RESET_ALL, R.drawable.msg_reset, getString(R.string.PengramResetAll)).red());
        items.add(UItem.asShadow(getString(R.string.PengramResetAllInfo)));
    }

    /** строки «О программе»: id раздаём по порядку, значение помним для копирования */
    private static final int BTN_ABOUT_ROW_BASE = 8000;
    private final android.util.SparseArray<CharSequence> aboutValues = new android.util.SparseArray<>();
    private int aboutRowIndex;

    /** строка «название — значение», по тапу значение уходит в буфер */
    private UItem aboutRow(int titleRes, CharSequence value) {
        final int id = BTN_ABOUT_ROW_BASE + aboutRowIndex++;
        aboutValues.put(id, value);
        return UItem.asButton(id, getString(titleRes), value);
    }

    /** читалка состояния для переключателей чужих настроек */
    private interface BoolGetter {
        boolean get();
    }

    private final android.util.SparseArray<BoolGetter> extraGetters = new android.util.SparseArray<>();
    private final android.util.SparseArray<Runnable> extraToggles = new android.util.SparseArray<>();

    /** переключатель настройки Telegram (не Pengram) */
    private UItem tgCheck(int id, CharSequence text, BoolGetter getter, Runnable toggle) {
        extraGetters.put(id, getter);
        extraToggles.put(id, toggle);
        return UItem.asCheck(id, text).setChecked(getter.get());
    }

    /** переключатель настройки Telegram с подписью */
    private UItem tgCheckInfo(int id, CharSequence text, CharSequence subtext, BoolGetter getter, Runnable toggle) {
        extraGetters.put(id, getter);
        extraToggles.put(id, toggle);
        return UItem.asButtonCheck(id, text, subtext).setChecked(getter.get());
    }

    /** переключатель флага «экономии» LiteMode */
    private UItem liteCheck(int id, int flag, CharSequence text) {
        return tgCheck(id, text, () -> LiteMode.isEnabled(flag), () -> LiteMode.toggleFlag(flag));
    }

    /** системные эмодзи вместо телеграмных */
    private void toggleSystemEmoji() {
        SharedConfig.useSystemEmoji = !SharedConfig.useSystemEmoji;
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                    .edit().putBoolean("useSystemEmoji", SharedConfig.useSystemEmoji).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** плавные анимации интерфейса */
    private void toggleInterfaceAnimations() {
        final boolean enabled = SharedConfig.animationsEnabled();
        SharedConfig.setAnimationsEnabled(!enabled);
        try {
            MessagesController.getGlobalMainSettings().edit().putBoolean("view_animations", !enabled).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** применить размер шрифта сообщений */
    private void applyFontSize(int size) {
        SharedConfig.fontSize = size;
        SharedConfig.fontSizeIsDefault = false;
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                    .edit().putInt("fons_size", size).commit();
            Theme.createCommonMessageResources();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** применить радиус углов пузырей */
    private void applyBubbleRadius(int radius) {
        SharedConfig.bubbleRadius = radius;
        try {
            MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", radius).commit();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** название действия свайпа в списке чатов */
    private CharSequence swipeActionName(int action) {
        switch (action) {
            case SwipeGestureSettingsView.SWIPE_GESTURE_PIN: return getString(R.string.SwipeSettingsPin);
            case SwipeGestureSettingsView.SWIPE_GESTURE_READ: return getString(R.string.SwipeSettingsRead);
            case SwipeGestureSettingsView.SWIPE_GESTURE_MUTE: return getString(R.string.SwipeSettingsMute);
            case SwipeGestureSettingsView.SWIPE_GESTURE_DELETE: return getString(R.string.SwipeSettingsDelete);
            case SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS: return getString(R.string.SwipeSettingsFolders);
            case SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE:
            default: return getString(R.string.SwipeSettingsArchive);
        }
    }

    private int crashKindTitle(int kind) {
        switch (kind) {
            case PengramAntiCrash.KIND_TABLE: return R.string.PengramCrashKindTable;
            case PengramAntiCrash.KIND_LAYOUT: return R.string.PengramCrashKindLayout;
            case PengramAntiCrash.KIND_ENTITIES: return R.string.PengramCrashKindEntities;
            case PengramAntiCrash.KIND_MESSAGE: return R.string.PengramCrashKindMessage;
            case PengramAntiCrash.KIND_DRAW: return R.string.PengramCrashKindDraw;
            default: return R.string.PengramCrashKindOther;
        }
    }

    private int crashKindIcon(int kind) {
        switch (kind) {
            case PengramAntiCrash.KIND_TABLE: return R.drawable.msg_block;
            case PengramAntiCrash.KIND_LAYOUT: return R.drawable.msg_policy;
            case PengramAntiCrash.KIND_ENTITIES: return R.drawable.msg_language;
            case PengramAntiCrash.KIND_MESSAGE: return R.drawable.msg_secret;
            case PengramAntiCrash.KIND_DRAW: return R.drawable.msg_info;
            default: return R.drawable.msg_retry;
        }
    }

    /** журнал атак: кто когда пытался уронить клиент и чем именно */
    private void showAntiCrashLog() {
        final int count = PengramAntiCrash.journalSize();
        if (count == 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                    getString(R.string.PengramAntiCrashLogEmpty)).show();
            return;
        }
        final android.content.Context context = getContext();
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        final int padH = AndroidUtilities.dp(20);
        final int padV = AndroidUtilities.dp(10);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int subColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
        final java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault());
        // свежие сверху
        for (int a = count - 1; a >= 0; --a) {
            final String entry = PengramAntiCrash.journalEntry(a);
            if (entry == null) {
                continue;
            }
            final int tab = entry.indexOf('\t');
            final String reason = tab >= 0 ? entry.substring(tab + 1) : entry;
            long when = 0;
            if (tab > 0) {
                try {
                    when = Long.parseLong(entry.substring(0, tab));
                } catch (Throwable ignore) {
                }
            }
            final int kind = PengramAntiCrash.kindOf(reason);

            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(padH, padV, padH, padV);
            final ImageView icon = new ImageView(context);
            icon.setImageResource(crashKindIcon(kind));
            icon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            row.addView(icon, LayoutHelper.createLinear(22, 22));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(textColor);
            title.setText(getString(crashKindTitle(kind)));
            texts.addView(title);
            final TextView sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            sub.setTextColor(subColor);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setText((when > 0 ? fmt.format(new java.util.Date(when)) + "  ·  " : "") + reason);
            texts.addView(sub);
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
            list.addView(row);
        }
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.PengramAntiCrashLog))
                .setView(scroll)
                .setNegativeButton(getString(R.string.PengramAntiCrashLogClear), (dialog, which) -> {
                    PengramAntiCrash.clearJournal();
                    listView.adapter.update(true);
                })
                .setPositiveButton(getString(R.string.Close), null)
                .show();
    }

    /** чекбокс, завязанный на ключ в PengramConfig — чтобы не плодить константы */
    /** «12» или «12 · Последняя: table measure» — коротко о работе антикраша */
    private String antiCrashStats() {
        final int total = PengramAntiCrash.totalBlocked();
        final String last = PengramAntiCrash.lastReason();
        if (last == null) {
            return String.valueOf(total);
        }
        // В значении строки показываем тип последней атаки, а не её техническое
        // описание: оно бывает длиной в абзац и разъезжалось по экрану.
        // Подробности с текстом причины остались в «Журнале атак».
        final String kind = getString(crashKindTitle(PengramAntiCrash.kindOf(last)));
        return total + " · " + LocaleController.formatString(R.string.PengramAntiCrashLast, kind);
    }

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

    /** свитч с подписью — родная ячейка NotificationsCheckCell; иконка подбирается по ключу */
    private UItem checkInfo(String key, boolean def, CharSequence text, CharSequence subtext) {
        Integer id = boolIds.get(key);
        if (id == null) {
            id = BTN_GENERIC_BASE + boolKeys.size();
            boolIds.put(key, id);
            boolKeys.add(key);
            boolDefaults.add(def);
        }
        final boolean value = PengramConfig.getBool(key, def);
        final int icon = iconForKey(key);
        if (icon != 0) {
            return UItem.asIconButtonCheck(id, icon, text, subtext).setChecked(value);
        }
        return UItem.asButtonCheck(id, text, subtext).setChecked(value);
    }

    /** свитч с подписью и явной иконкой слева */
    private UItem checkIcon(String key, boolean def, int icon, CharSequence text, CharSequence subtext) {
        Integer id = boolIds.get(key);
        if (id == null) {
            id = BTN_GENERIC_BASE + boolKeys.size();
            boolIds.put(key, id);
            boolKeys.add(key);
            boolDefaults.add(def);
        }
        return UItem.asIconButtonCheck(id, icon, text, subtext).setChecked(PengramConfig.getBool(key, def));
    }

    /**
     * Иконка для переключателя с подписью. Без неё строки с заголовком и описанием
     * выглядят одинаковыми «кирпичами», а глазу нужна зацепка, чтобы находить нужный пункт.
     */
    private static int iconForKey(String key) {
        if (key == null) {
            return 0;
        }
        switch (key) {
            case PengramConfig.KEY_NO_ROUNDING: return R.drawable.msg_views;
            case PengramConfig.KEY_TIME_SECONDS: return R.drawable.msg_contacts_time;
            case PengramConfig.KEY_VIBRATION: return R.drawable.msg_tone_on;
            case PengramConfig.KEY_SHOW_ACCOUNTS_SETTINGS: return R.drawable.msg_contacts;
            case PengramConfig.KEY_ZALGO: return R.drawable.msg_text_outlined;
            case PengramConfig.KEY_REG_TAP_TEXT: return R.drawable.msg_calendar2;
            case PengramConfig.KEY_GHOST_STORIES_WARN: return R.drawable.msg_media;
            case PengramConfig.KEY_GHOST_SEND_DELAY: return R.drawable.msg_recent;
            case PengramConfig.KEY_FORCE_SNOW: return R.drawable.msg_theme;
            case PengramConfig.KEY_PENGUIN_TIPS: return R.drawable.msg_info;
            case PengramConfig.KEY_PENGUIN_AUTO_SKIN: return R.drawable.msg_customize;
            case PengramConfig.KEY_TRACK_FORWARD_BUTTON: return R.drawable.msg_forward;
            case PengramConfig.KEY_TRACK_FORWARD_CAPTION: return R.drawable.msg_edit;
            case PengramConfig.KEY_LYRICS_AUTO: return R.drawable.msg_download;
            case PengramConfig.KEY_LYRICS_STRETCH: return R.drawable.msg_select;
            case PengramConfig.KEY_LYRICS_SMOOTH: return R.drawable.msg_speed;
            case PengramConfig.KEY_HEADER_LYRICS: return R.drawable.msg_played;
            case PengramConfig.KEY_HEADER_LYRICS_MARQUEE: return R.drawable.msg_reorder;
            case PengramConfig.KEY_FORWARD_LOCK: return R.drawable.msg_mini_lock3;
            case PengramConfig.KEY_KEEP_FORMATTING: return R.drawable.msg_copy;
            case PengramConfig.KEY_HIDE_TAIL: return R.drawable.msg_msgbubble3;
            case PengramConfig.KEY_HIDE_EDITED_LABEL: return R.drawable.msg_edit;
            case PengramConfig.KEY_FORCE_DELETE_FOR_ALL: return R.drawable.msg_delete;
            case PengramConfig.KEY_HIDE_STORIES: return R.drawable.msg_media;
            case PengramConfig.KEY_DIALOG_SENDER_AVATARS: return R.drawable.msg_openprofile;
            case PengramConfig.KEY_PENGRAM_CARD: return R.drawable.msg_settings;
            default: return 0;
        }
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
        return PengramConfig.getBool(expandedKey(group), false);
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
        // короткая тактильная отдача: переключение должно ощущаться, а не только выглядеть
        AndroidUtilities.vibrateCursor(view);
        // слой Material 3 меняет отрисовку чужих экранов — просим их перерисоваться
        if (key.startsWith("md3")) {
            if (key.startsWith("md3Monet")) {
                org.telegram.messenger.PengramMonet.update(getContext());
            }
            AndroidUtilities.runOnUIThread(() -> org.telegram.messenger.NotificationCenter.getGlobalInstance()
                    .postNotificationName(org.telegram.messenger.NotificationCenter.reloadInterface));
        }
        // список перестраиваем всегда и с анимацией: зависимые пункты
        // должны выезжать/сворачиваться прямо при переключении
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        if (previewMessages != null) {
            previewMessages.update();
        }
        if (md3Preview != null) {
            md3Preview.update();
        }
        return true;
    }

    private CharSequence monetStrengthName() {
        switch (org.telegram.messenger.PengramMonet.strength()) {
            case 0: return getString(R.string.PengramMonetStrengthCalm);
            case 2: return getString(R.string.PengramMonetStrengthVivid);
            default: return getString(R.string.PengramMonetStrengthNormal);
        }
    }

    /** выбор насыщенности системной палитры */
    private void showMonetStrengthPicker() {
        final CharSequence[] options = new CharSequence[]{
                getString(R.string.PengramMonetStrengthCalm),
                getString(R.string.PengramMonetStrengthNormal),
                getString(R.string.PengramMonetStrengthVivid)
        };
        showChoicePicker(getString(R.string.PengramMonetStrength), options,
                org.telegram.messenger.PengramMonet.strength(), value -> {
                    PengramConfig.setIntValue(org.telegram.messenger.PengramMonet.KEY_STRENGTH, value);
                    org.telegram.messenger.PengramMonet.update(getContext());
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                    org.telegram.messenger.NotificationCenter.getGlobalInstance()
                            .postNotificationName(org.telegram.messenger.NotificationCenter.reloadInterface);
                });
    }

    /** «Вкл» / «Выкл» справа в строке раздела */
    private CharSequence onOff(boolean value) {
        return getString(value ? R.string.PengramValueOn : R.string.PengramValueOff);
    }

    /**
     * Состояние раздела «Текст песни».
     *
     * Раньше здесь висело «выкл» у всех, кто выключил автоподбор и настроил
     * источник руками, — хотя текст при этом прекрасно показывался. Теперь
     * строка отвечает на тот вопрос, который человек и задаёт: показывает ли
     * выбранное оформление плеера текст вообще, а уж как он ищется — уточнение.
     */
    private CharSequence lyricsSectionValue() {
        if (!PengramConfig.isNewPlayer()
                || !PengramConfig.playerStyleHasLyrics(PengramConfig.getPlayerStyle())) {
            return onOff(false);
        }
        return getString(R.string.PengramValueOn) + " \u00b7 " + getString(PengramConfig.isLyricsAuto()
                ? R.string.PengramLyricsModeAuto : R.string.PengramLyricsModeManual);
    }

    /** палитра «своего» цвета: без пипеток и HEX — двенадцать приятных оттенков */
    private static final int[] PLAYER_PALETTE = {
            0xFF5FD0A0, 0xFF4FC3F7, 0xFF7E8CF7, 0xFFB388FF,
            0xFFFF8AB4, 0xFFFF6E6E, 0xFFFF9F43, 0xFFFFD166,
            0xFF9CCC65, 0xFF26C6DA, 0xFFBCAAA4, 0xFFE0E0E0
    };

    /** значение строки «Цвет плеера»: название режима и сам цвет кружком */
    private CharSequence playerAccentValue() {
        final int mode = PengramConfig.getPlayerAccentMode();
        final String name = getString(PengramConfig.getPlayerAccentName(mode));
        if (mode != PengramConfig.PLAYER_ACCENT_CUSTOM) {
            return name;
        }
        final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("\u25CF  ");
        sb.setSpan(new android.text.style.ForegroundColorSpan(PengramConfig.getPlayerAccentColor()),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(name);
        return sb;
    }

    private void showPlayerAccentPicker() {
        final boolean monetSupported = android.os.Build.VERSION.SDK_INT >= 31;
        final CharSequence[] options = new CharSequence[]{
                getString(R.string.PengramPlayerAccentCover),
                getString(R.string.PengramPlayerAccentTheme),
                monetSupported
                        ? getString(R.string.PengramPlayerAccentMonet)
                        : getString(R.string.PengramPlayerAccentMonet) + " \u00b7 " + getString(R.string.PengramMonetUnsupportedShort),
                getString(R.string.PengramPlayerAccentCustom)
        };
        showChoicePicker(getString(R.string.PengramPlayerAccent), options,
                PengramConfig.getPlayerAccentMode(), value -> {
                    if (value == PengramConfig.PLAYER_ACCENT_MONET && !monetSupported) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                                getString(R.string.PengramMonetUnsupported)).show();
                        return;
                    }
                    PengramConfig.setPlayerAccentMode(value);
                    if (value == PengramConfig.PLAYER_ACCENT_CUSTOM) {
                        showPlayerColorPicker();
                        return;
                    }
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
    }

    /** выбор оттенка: каждый пункт нарисован своим цветом, чтобы выбирать глазами */
    private void showPlayerColorPicker() {
        final CharSequence[] names = new CharSequence[PLAYER_PALETTE.length];
        int selected = 0;
        for (int a = 0; a < PLAYER_PALETTE.length; a++) {
            final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("\u25CF   ");
            sb.setSpan(new android.text.style.ForegroundColorSpan(PLAYER_PALETTE[a]),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(String.format(java.util.Locale.US, "#%06X", PLAYER_PALETTE[a] & 0xFFFFFF));
            names[a] = sb;
            if (PLAYER_PALETTE[a] == PengramConfig.getPlayerAccentColor()) {
                selected = a;
            }
        }
        showChoicePicker(getString(R.string.PengramPlayerAccentCustom), names, selected, value -> {
            PengramConfig.setPlayerAccentColor(PLAYER_PALETTE[value]);
            PengramConfig.setPlayerAccentMode(PengramConfig.PLAYER_ACCENT_CUSTOM);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    private CharSequence sharePhoneModeName(int mode) {
        switch (mode) {
            case PengramConfig.SHARE_PHONE_ASK: return getString(R.string.PengramSharePhoneAsk);
            case PengramConfig.SHARE_PHONE_ALWAYS: return getString(R.string.PengramSharePhoneAlways);
            default: return getString(R.string.PengramSharePhoneNever);
        }
    }

    private void showSharePhonePicker() {
        final CharSequence[] options = new CharSequence[]{
                getString(R.string.PengramSharePhoneNever),
                getString(R.string.PengramSharePhoneAsk),
                getString(R.string.PengramSharePhoneAlways)
        };
        showChoicePicker(getString(R.string.PengramSharePhoneMode), options,
                PengramConfig.getSharePhoneMode(), value -> {
                    PengramConfig.setSharePhoneMode(value);
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
    }

    private CharSequence fontName(int font) {
        return org.telegram.messenger.PengramFonts.name(font);
    }

    /**
     * Выбор шрифта списком.
     *
     * Показываем только те семейства, которые на этом телефоне действительно
     * отличаются: системы без «casual» или «condensed» молча подменяют их
     * обычным sans-serif, и пункт-обманка раздражал бы больше, чем его
     * отсутствие.
     */
    private void showFontPicker() {
        final int[] fonts = org.telegram.messenger.PengramFonts.available();
        final CharSequence[] names = new CharSequence[fonts.length];
        int selected = 0;
        for (int a = 0; a < fonts.length; a++) {
            final android.text.SpannableString name =
                    new android.text.SpannableString(org.telegram.messenger.PengramFonts.name(fonts[a]));
            final android.graphics.Typeface typeface = org.telegram.messenger.PengramFonts.typeface(fonts[a]);
            if (typeface != null) {
                name.setSpan(new org.telegram.ui.Components.TypefaceSpan(typeface), 0, name.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            names[a] = name;
            if (fonts[a] == PengramConfig.appFont) {
                selected = a;
            }
        }
        showChoicePicker(getString(R.string.PengramFont), names, selected, value -> {
            PengramConfig.setAppFont(fonts[value]);
            if (fontPreview != null) {
                fontPreview.update();
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
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

    /** перерисовать список настроек (вызывается, когда дочитались фоновые счётчики) */
    private void refreshList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    /** «Вкл · 128» — сколько всего сохранено */
    private CharSequence spySectionValue() {
        final boolean on = PengramConfig.isSavingDeleted() || PengramConfig.isSavingEdited();
        if (!on) {
            return onOff(false);
        }
        // счётчик берём из кэша: SELECT COUNT(*) на UI-потоке подвешивал открытие настроек
        final int count = PengramHistory.getCountCached(0, this::refreshList);
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
    /** название формата даты регистрации */
    private CharSequence regStyleName(int style) {
        switch (style) {
            case PengramConfig.REG_STYLE_DATE: return getString(R.string.PengramRegStyleDate);
            case PengramConfig.REG_STYLE_DATE_AGE: return getString(R.string.PengramRegStyleDateAge);
            case PengramConfig.REG_STYLE_AGE: return getString(R.string.PengramRegStyleAge);
            case PengramConfig.REG_STYLE_EXACT: return getString(R.string.PengramRegStyleExact);
            default: return getString(R.string.PengramRegStyleOff);
        }
    }

    /** где показывать дату регистрации */
    private CharSequence regPlaceName(int place) {
        switch (place) {
            case PengramConfig.REG_PLACE_ICON: return getString(R.string.PengramRegPlaceIcon);
            case PengramConfig.REG_PLACE_SUBTITLE: return getString(R.string.PengramRegPlaceSubtitle);
            case PengramConfig.REG_PLACE_ROW: return getString(R.string.PengramRegPlaceRow);
            default: return getString(R.string.PengramRegPlaceBoth);
        }
    }

    /** какой значок у даты регистрации */
    private CharSequence regIconName(int icon) {
        switch (icon) {
            case PengramConfig.REG_ICON_CLOCK: return getString(R.string.PengramRegIconClock);
            case PengramConfig.REG_ICON_CAKE: return getString(R.string.PengramRegIconCake);
            case PengramConfig.REG_ICON_STAR: return getString(R.string.PengramRegIconStar);
            case PengramConfig.REG_ICON_INFO: return getString(R.string.PengramRegIconInfo);
            case PengramConfig.REG_ICON_PENGUIN: return getString(R.string.PengramRegIconPenguin);
            case PengramConfig.REG_ICON_NONE: return getString(R.string.PengramRegIconNone);
            default: return getString(R.string.PengramRegIconCalendar);
        }
    }

    /** название режима заголовка */
    private CharSequence titleModeName(int mode) {
        switch (mode) {
            case PengramConfig.TITLE_MODE_PENGRAM: return "Pengram";
            case PengramConfig.TITLE_MODE_CHATS: return getString(R.string.PengramTitleChats);
            case PengramConfig.TITLE_MODE_NAME: return getString(R.string.PengramTitleModeName);
            case PengramConfig.TITLE_MODE_USERNAME: return getString(R.string.PengramTitleModeUsername);
            case PengramConfig.TITLE_MODE_CUSTOM: return getString(R.string.PengramTitleModeCustom);
            default: return getString(R.string.PengramTitleModeDefault);
        }
    }

    /** свой текст заголовка */
    private void showTitleCustomDialog() {
        showTextDialog(getString(R.string.PengramTitleCustom), PengramConfig.getTitleCustom(), "Pengram", value -> {
            PengramConfig.setTitleCustom(value == null ? "" : value.trim());
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    /** название лимита размера папки */
    private CharSequence mediaLimitName(int limitMb) {
        if (limitMb <= 0) {
            return getString(R.string.PengramMediaLimitOff);
        }
        return (limitMb / 1024) + " GB";
    }

    /** заголовок строки фильтра — ровный, без «поломанного» текста */
    private CharSequence zalgoTitle() {
        return getString(R.string.PengramZalgo);
    }

    /** а вот в подписи слово «Zalgo» показываем именно зальго — чтобы было видно, о чём речь */
    private CharSequence zalgoInfo() {
        return PengramConfig.zalgoWord(getString(R.string.PengramZalgoInfo), "Zalgo");
    }

    /** сколько наших кнопок в меню чата скрыто */
    private CharSequence hiddenChatItemsValue() {
        final int count = PengramConfig.getHiddenChatItemsCount();
        return count <= 0 ? "" : LocaleController.formatPluralString("PengramHiddenItems", count);
    }

    /** сколько разделов настроек скрыто */
    private CharSequence hiddenSettingsValue() {
        final int hidden = PengramConfig.getHiddenSettingsItemsCount();
        if (hidden <= 0) {
            return getString(R.string.PengramMenuItemsAll);
        }
        return LocaleController.formatString(R.string.PengramMenuItemsHidden, hidden);
    }

    /** сколько пунктов меню скрыто */
    private CharSequence hiddenMenuValue() {
        final int hidden = PengramConfig.getHiddenMenuItemsCount();
        if (hidden <= 0) {
            return getString(R.string.PengramMenuItemsAll);
        }
        return LocaleController.formatString(R.string.PengramMenuItemsHidden, hidden);
    }

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
    private org.telegram.ui.Components.PengramTabsMockView tabsMockView;
    private org.telegram.ui.Components.PengramTypingPreviewView typingPreview;
    private org.telegram.ui.Components.PengramFontPreviewView fontPreview;
    private org.telegram.ui.Components.PengramMD3PreviewView md3Preview;

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

    /** Pengram: размер панели поменялся — пересобираем экран целиком */
    private void applyTabsSizeNow() {
        try {
            DialogsActivity.pengramApplyTabsSize();
            if (getParentLayout() == null) {
                return;
            }
            final java.util.List<org.telegram.ui.ActionBar.BaseFragment> stack = getParentLayout().getFragmentStack();
            for (int a = 0; a < stack.size(); ++a) {
                final org.telegram.ui.ActionBar.BaseFragment fragment = stack.get(a);
                if (fragment instanceof MainTabsActivity) {
                    ((MainTabsActivity) fragment).pengramRebuildTabs();
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

        if (getContext() != null && PengramConfig.getQuickTilesCount() > 0) {
            items.add(UItem.asCustom(quickToggles()));
            items.add(UItem.asShadow(null));
        }

        // Разделы разложены по смыслу: сначала то, что видно глазу, потом приватность,
        // потом чаты, потом медиа. Пояснений под группами нет намеренно — заголовка
        // и подписи у строки достаточно, а лишние абзацы делали экран длинным.
        items.add(UItem.asHeader(getString(R.string.PengramGroupLook)));
        items.add(sectionRow(BTN_SECTION_APPEARANCE, IconBackgroundColors.PURPLE, R.drawable.msg_theme,
                getString(R.string.PengramSectionAppearance), fontName(PengramConfig.appFont)));
        items.add(sectionRow(BTN_SECTION_CUSTOM, IconBackgroundColors.ORANGE, R.drawable.msg_customize,
                getString(R.string.PengramSectionCustom), markName(PengramConfig.getDeletedMark())));
        items.add(sectionRow(BTN_SECTION_PENGUIN, IconBackgroundColors.BLUE_LIGHT, R.drawable.pengram_penguin_glyph,
                getString(R.string.PengramSectionPenguin), getString(PengramConfig.getPenguinSkinName(PengramConfig.getPenguinSkin()))));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramGroupPrivacy)));
        items.add(sectionRow(BTN_SECTION_GHOST, IconBackgroundColors.GREEN, R.drawable.msg_secret,
                getString(R.string.PengramSectionGhost), onOff(PengramConfig.ghostMode)));
        items.add(sectionRow(BTN_SECTION_HISTORY, IconBackgroundColors.RED, R.drawable.msg_viewchats,
                getString(R.string.PengramSectionSpy), spySectionValue()));
        items.add(sectionRow(BTN_SECTION_PROFILE, IconBackgroundColors.BLUE, R.drawable.settings_account,
                getString(R.string.PengramSectionProfile), null));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramGroupChats)));
        items.add(sectionRow(BTN_SECTION_CHATS, IconBackgroundColors.BLUE_ALT, R.drawable.settings_chat,
                getString(R.string.PengramSectionChats), hiddenCountValue()));
        items.add(sectionRow(BTN_SECTION_GENERAL, IconBackgroundColors.GRAY, R.drawable.msg_settings,
                getString(R.string.PengramSectionGeneral),
                PengramConfig.getSendTextStyle() == PengramConfig.SEND_STYLE_OFF ? "" : getString(PengramTextStyle.getNameRes(PengramConfig.getSendTextStyle()))));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramGroupMedia)));
        items.add(sectionRow(BTN_SECTION_PLAYER, IconBackgroundColors.ORANGE_DEEP, R.drawable.msg_played,
                getString(R.string.PengramSectionPlayer), playerSectionValue()));
        items.add(sectionRow(BTN_SECTION_MEDIA, IconBackgroundColors.BLUE_DEEP, R.drawable.settings_data,
                getString(R.string.PengramSectionMedia), mediaSectionValue()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramGroupExtra)));
        items.add(sectionRow(BTN_SECTION_FREEDOM, IconBackgroundColors.CYAN, R.drawable.settings_features,
                getString(R.string.PengramSectionFreedom), null));
        items.add(sectionRow(BTN_SECTION_AI, IconBackgroundColors.PURPLE, R.drawable.msg_bot,
                getString(R.string.PengramSectionAI), aiSectionValue()));
        items.add(sectionRow(BTN_CONSTRUCTOR, IconBackgroundColors.BLUE_DEEP, R.drawable.msg_photo_settings,
                getString(R.string.PengramConstructor), getString(R.string.PengramConstructorValue)));
        items.add(sectionRow(BTN_SECTION_ABOUT, IconBackgroundColors.BLUE_LIGHT, R.drawable.msg_info,
                getString(R.string.PengramAbout), org.telegram.messenger.PengramVersion.shortLine()));
        items.add(UItem.asShadow(getString(R.string.PengramSectionsInfo)));

    }

    /** строка раздела с цветной иконкой */
    private UItem sectionRow(int id, IconBackgroundColors colors, int icon, CharSequence title, CharSequence value) {
        // Pengram: в Material 3 иконка раздела — ровный тональный кружок без градиента
        if (org.telegram.messenger.PengramMD3.settingsScreen()) {
            final int container = org.telegram.messenger.PengramMD3.surface(getResourceProvider(), 0.75f);
            return SettingsActivity.SettingCell.Factory.of(id, container, container, icon, title, null, value);
        }
        return SettingsActivity.SettingCell.Factory.of(id, colors.top, colors.bottom, icon, title, null, value);
    }

    /** Основное — мелочи, которые влияют на весь клиент */
    private void fillGeneral(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramGeneralHeader)));
        items.add(checkInfo(PengramConfig.KEY_NO_ROUNDING, false, getString(R.string.PengramNoRounding), getString(R.string.PengramNoRoundingInfo)));
        items.add(checkInfo(PengramConfig.KEY_TIME_SECONDS, false, getString(R.string.PengramTimeSeconds), getString(R.string.PengramTimeSecondsInfo)));
        items.add(checkInfo(PengramConfig.KEY_VIBRATION, true, getString(R.string.PengramVibration), getString(R.string.PengramVibrationInfo)));
        items.add(checkInfo(PengramConfig.KEY_SHOW_ACCOUNTS_SETTINGS, false, getString(R.string.PengramShowAccountsSettings), getString(R.string.PengramShowAccountsSettingsInfo)));
        items.add(checkInfo(PengramConfig.KEY_ZALGO, false, zalgoTitle(), zalgoInfo()));
        items.add(UItem.asShadow(getString(R.string.PengramGeneralInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramSendStyleHeader)));
        items.add(UItem.asSettingsCell(BTN_SEND_STYLE, R.drawable.msg_edit, getString(R.string.PengramSendStyle),
                getString(PengramTextStyle.getNameRes(PengramConfig.getSendTextStyle()))));
        if (PengramConfig.getSendTextStyle() != PengramConfig.SEND_STYLE_OFF) {
            items.add(check(PengramConfig.KEY_SEND_STYLE_CAPTIONS, true, getString(R.string.PengramSendStyleCaptions)));
        }
        items.add(UItem.asShadow(sendStyleInfo()));

        items.add(UItem.asHeader(getString(R.string.PengramSystemHeader)));
        items.add(tgCheck(BTN_EXTRA_BASE + 30, getString(R.string.DirectShare), () -> SharedConfig.directShare, SharedConfig::toggleDirectShare));
        items.add(tgCheck(BTN_EXTRA_BASE + 31, getString(R.string.PengramSortContacts), () -> SharedConfig.sortContactsByName, SharedConfig::toggleSortContactsByName));
        items.add(tgCheck(BTN_EXTRA_BASE + 32, getString(R.string.PengramStickerOrder), () -> SharedConfig.updateStickersOrderOnSend, SharedConfig::toggleUpdateStickersOrderOnSend));
        items.add(UItem.asShadow(getString(R.string.PengramSystemInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramToolsHeader)));
        items.add(UItem.asButton(BTN_OPEN_BY_ID, R.drawable.msg_search, getString(R.string.PengramOpenById)));
        items.add(UItem.asShadow(getString(R.string.PengramIdSearchInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramBackupHeader)));
        items.add(UItem.asSettingsCell(BTN_CFG_EXPORT, R.drawable.msg_shareout, getString(R.string.PengramBackupExport),
                LocaleController.formatString(R.string.PengramBackupCount, org.telegram.messenger.PengramBackup.countTransferable())));
        items.add(UItem.asSettingsCell(BTN_CFG_IMPORT, R.drawable.msg_download, getString(R.string.PengramBackupImport), null));
        items.add(UItem.asButton(BTN_CFG_RESET, R.drawable.msg_delete, getString(R.string.PengramBackupReset)).red());
        items.add(UItem.asShadow(getString(R.string.PengramBackupInfoFile)));
    }

    /** подпись к лимиту записей журнала */
    private CharSequence historyMaxName(int limit) {
        return limit <= 0 ? getString(R.string.PengramHistoryMaxUnlimited)
                : LocaleController.formatString(R.string.PengramHistoryMaxValue, limit);
    }

    /** после сброса настроек перерисовываем всё, до чего дотягиваемся */
    private void rebuildAfterReset() {
        if (headerView != null) {
            headerView.applySkin();
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
        // общий хвост с импортом бэкапа: перезапуск сервисов, премиум-статус, бюллетень
        afterSettingsReplaced(getString(R.string.PengramResetDone));
    }

    /** короткая статистика под блоком хранилища */
    private CharSequence historyStatsText() {
        // размеры считаются в фоне, экран рисуется сразу и обновится сам
        final PengramHistory.Stats stats = PengramHistory.getStatsCached(this::refreshList);
        final StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.formatString(R.string.PengramHistorySize, AndroidUtilities.formatFileSize(stats.databaseSize)));
        if (stats.mediaCount > 0) {
            sb.append('\n');
            sb.append(LocaleController.formatString(R.string.PengramHistoryMediaStats, stats.mediaCount,
                    AndroidUtilities.formatFileSize(stats.mediaSize)));
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
                // Закрываем именно этот picker до callback. Иначе callback успевает открыть
                // следующий диалог, а отложенный dismiss закрывает уже его.
                try {
                    if (visibleDialog != null) {
                        visibleDialog.dismiss();
                    }
                } catch (Throwable ignore) {}
                onSelected.run(index);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
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

    /** Что сделать с настройками: отправить себе, сохранить файлом или скопировать текстом */
    private void exportSettings() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final org.telegram.ui.ActionBar.BottomSheet.Builder builder =
                new org.telegram.ui.ActionBar.BottomSheet.Builder(context, false, getResourceProvider());
        builder.setTitle(getString(R.string.PengramBackupExport), true);
        builder.setItems(new CharSequence[]{
                getString(R.string.PengramBackupToSaved),
                getString(R.string.PengramBackupToFile),
                getString(R.string.PengramBackupToClipboard)
        }, new int[]{R.drawable.msg_saved, R.drawable.msg_shareout, R.drawable.msg_copy}, (dialog, which) -> {
            if (which == 2) {
                exportAsText();
            } else {
                askPassword(true, password -> packAndDeliver(password, which == 0));
            }
        });
        builder.show();
    }

    private void exportAsText() {
        final String json = PengramConfig.exportToJson();
        if (json == null) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupFailed)).show();
            return;
        }
        AndroidUtilities.addToClipboard(json);
        BulletinFactory.of(this).createCopyBulletin(getString(R.string.PengramBackupCopied)).show();
    }

    /** собрать .pen и либо отправить в Избранное, либо отдать системе «поделиться» */
    private void packAndDeliver(String password, boolean toSaved) {
        final byte[] data = org.telegram.messenger.PengramBackup.pack(password);
        final java.io.File file = org.telegram.messenger.PengramBackup.writeToCache(data);
        if (file == null) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupFailed)).show();
            return;
        }
        if (toSaved) {
            final long selfId = getUserConfig().getClientUserId();
            org.telegram.messenger.SendMessagesHelper.prepareSendingDocument(getAccountInstance(),
                    file.getAbsolutePath(), file.getAbsolutePath(), null,
                    getString(R.string.PengramBackupCaption), "application/octet-stream",
                    selfId, null, null, null, null, null, true, 0, null, null, false);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.saved_messages,
                    getString(R.string.PengramBackupSentToSaved)).show();
            return;
        }
        try {
            final android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(android.content.Intent.EXTRA_STREAM,
                    androidx.core.content.FileProvider.getUriForFile(getParentActivity(),
                            ApplicationLoader.getApplicationId() + ".provider", file));
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivityForResult(android.content.Intent.createChooser(intent,
                    getString(R.string.PengramBackupToFile)), 500);
        } catch (Throwable e) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupFailed)).show();
        }
    }

    /** Импорт: файл .pen или текст из буфера */
    private void importSettings() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final org.telegram.ui.ActionBar.BottomSheet.Builder builder =
                new org.telegram.ui.ActionBar.BottomSheet.Builder(context, false, getResourceProvider());
        builder.setTitle(getString(R.string.PengramBackupImport), true);
        builder.setItems(new CharSequence[]{
                getString(R.string.PengramBackupFromFile),
                getString(R.string.PengramBackupFromClipboard)
        }, new int[]{R.drawable.msg_download, R.drawable.msg_copy}, (dialog, which) -> {
            if (which == 0) {
                pickBackupFile();
            } else {
                importFromClipboard();
            }
        });
        builder.show();
    }

    private static final int REQUEST_PICK_BACKUP = 4711;

    /** системный выбор файла — так .pen открывается откуда угодно, хоть из Избранного */
    private void pickBackupFile() {
        if (getParentActivity() == null) {
            return;
        }
        try {
            final android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(android.content.Intent.createChooser(intent,
                    getString(R.string.PengramBackupFromFile)), REQUEST_PICK_BACKUP);
        } catch (Throwable e) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupFailed)).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_BACKUP || data == null || data.getData() == null) {
            return;
        }
        byte[] bytes = null;
        try {
            final java.io.InputStream stream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(data.getData());
            if (stream != null) {
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) > 0 && out.size() < 8 * 1024 * 1024) {
                    out.write(buffer, 0, read);
                }
                stream.close();
                bytes = out.toByteArray();
            }
        } catch (Throwable ignore) {
        }
        applyPickedBackup(bytes);
    }

    private void applyPickedBackup(byte[] bytes) {
        if (bytes == null || !org.telegram.messenger.PengramBackup.looksLikeBackup(bytes)) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupNotPen)).show();
            return;
        }
        if (org.telegram.messenger.PengramBackup.needsPassword(bytes)) {
            askPassword(false, password -> applyBackup(bytes, password));
        } else {
            applyBackup(bytes, null);
        }
    }

    private void applyBackup(byte[] data, String password) {
        if (getParentActivity() == null) {
            return;
        }
        // ключ считается сотнями тысяч итераций — на UI-потоке это заморозка
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        org.telegram.messenger.PengramBackup.unpackAsync(data, password, json -> {
            try {
                progress.dismiss();
            } catch (Throwable ignore) {
            }
            applyBackupJson(json);
        });
    }

    private void applyBackupJson(String json) {
        if (json == null) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupWrongPassword)).show();
            return;
        }
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PengramBackupImport));
        builder.setMessage(getString(R.string.PengramBackupImportConfirm));
        builder.setPositiveButton(getString(R.string.PengramBackupApply), (d, w) -> {
            if (org.telegram.messenger.PengramBackup.apply(json)) {
                afterSettingsReplaced(getString(R.string.PengramBackupImported));
            } else {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramBackupFailed)).show();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void importFromClipboard() {
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

    /** один и тот же диалог пароля на экспорт и импорт */
    private void askPassword(boolean creating, Utilities.Callback<String> whenDone) {
        final Context context = getContext();
        if (context == null || getParentActivity() == null) {
            return;
        }
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint(getString(creating ? R.string.PengramBackupPasswordHint : R.string.PengramBackupPasswordEnter));
        field.setBackground(null);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setCursorWidth(1.5f);

        final android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 22, 4, 22, 0));

        final TextView hint = new TextView(context);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        hint.setText(getString(creating ? R.string.PengramBackupPasswordInfo : R.string.PengramBackupPasswordInfoOpen));
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 10, 22, 4));

        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PengramBackupPasswordTitle));
        builder.setView(layout);
        builder.setPositiveButton(getString(creating ? R.string.PengramBackupSave : R.string.PengramBackupApply), (d, w) ->
                whenDone.run(field.getText() == null ? "" : field.getText().toString()));
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
            showPeerActions(userId);
            return;
        }
        if (getMessagesController().getChat(chatId) != null) {
            showPeerActions(-chatId);
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
                    showPeerActions(user.id);
                } else if (chat != null) {
                    getMessagesController().putChat(chat, true);
                    showPeerActions(-chat.id);
                } else {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.PengramOpenByIdNotFound)).show();
                }
            });
        });
    }

    /** нашли человека или чат по ID — спрашиваем, куда идти: в переписку или в профиль */
    private void showPeerActions(long dialogId) {
        if (getParentActivity() == null) {
            presentFragment(ChatActivity.of(dialogId));
            return;
        }
        CharSequence name = null;
        if (dialogId > 0) {
            final TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                name = UserObject.getUserName(user);
            }
        } else {
            final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                name = chat.title;
            }
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(TextUtils.isEmpty(name) ? getString(R.string.PengramOpenById) : name);
        builder.setMessage(PengramConfig.formatId(Math.abs(dialogId), dialogId < 0, false));
        builder.setPositiveButton(getString(R.string.PengramOpenChat), (d, w) -> presentFragment(ChatActivity.of(dialogId)));
        builder.setNegativeButton(getString(R.string.PengramOpenProfile), (d, w) -> {
            final Bundle args = new Bundle();
            if (dialogId > 0) {
                args.putLong("user_id", dialogId);
            } else {
                args.putLong("chat_id", -dialogId);
            }
            presentFragment(new ProfileActivity(args));
        });
        showDialog(builder.create());
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

        items.add(UItem.asHeader(getString(R.string.PengramDeleteEffectHeader)));
        items.add(UItem.asSettingsCell(BTN_DELETE_EFFECT, R.drawable.msg_delete,
                getString(R.string.PengramDeleteEffect),
                getString(PengramConfig.getDeleteEffectName(PengramConfig.getDeleteEffect()))));
        if (PengramConfig.getDeleteEffect() != PengramConfig.DELETE_EFFECT_NONE) {
            items.add(check(PengramConfig.KEY_DELETE_EFFECT_INCOMING, true, getString(R.string.PengramDeleteEffectIncoming)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramDeleteEffectIncomingInfo)));
    }

    /** ID для примеров в настройках — свой, если он уже известен */
    private long sampleId() {
        try {
            final TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (user != null && user.id > 0) {
                return user.id;
            }
        } catch (Throwable ignore) {
        }
        return 1234567890L;
    }

    private void fillProfile(ArrayList<UItem> items) {
        if (previewView == null) {
            previewView = new ProfilePreviewView(getContext());
        }
        previewView.update();
        items.add(UItem.asCustom(previewView));
        items.add(UItem.asShadow(getString(R.string.PengramIdPreviewInfo)));

        final long selfId = sampleId();
        items.add(UItem.asHeader(getString(R.string.PengramIdHeader)));
        items.add(UItem.asRadio2(BTN_ID_FORMAT_HIDE, getString(R.string.PengramIdFormatHide), getString(R.string.PengramIdFormatHideInfo))
                .setChecked(PengramConfig.getIdFormat() == PengramConfig.ID_FORMAT_HIDE));
        items.add(UItem.asRadio2(BTN_ID_FORMAT_TELEGRAM, getString(R.string.PengramIdFormatTelegram), String.valueOf(selfId))
                .setChecked(PengramConfig.getIdFormat() == PengramConfig.ID_FORMAT_TELEGRAM));
        items.add(UItem.asRadio2(BTN_ID_FORMAT_BOT, getString(R.string.PengramIdFormatBot), "-100" + selfId)
                .setChecked(PengramConfig.getIdFormat() == PengramConfig.ID_FORMAT_BOT));
        items.add(UItem.asShadow(getString(R.string.PengramIdFormatInfo)));

        if (PengramConfig.getIdFormat() != PengramConfig.ID_FORMAT_HIDE) {
            items.add(UItem.asHeader(getString(R.string.PengramIdStyleHeader)));
            items.add(UItem.asRadio2(BTN_ID_OFF, getString(R.string.PengramIdStyleOff), getString(R.string.PengramIdStyleOffInfo))
                    .setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_OFF));
            items.add(UItem.asRadio2(BTN_ID_ROW, getString(R.string.PengramIdStyleRow), getString(R.string.PengramIdStyleRowInfo))
                    .setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_ROW));
            items.add(UItem.asRadio2(BTN_ID_ROW_DC, getString(R.string.PengramIdStyleRowDc), getString(R.string.PengramIdStyleRowDcInfo))
                    .setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_ROW_DC));
            items.add(UItem.asRadio2(BTN_ID_INLINE, getString(R.string.PengramIdStyleInline), getString(R.string.PengramIdStyleInlineInfo))
                    .setChecked(PengramConfig.idStyle == PengramConfig.ID_STYLE_INLINE));
            if (PengramConfig.idStyle != PengramConfig.ID_STYLE_OFF) {
                items.add(UItem.asShadow(null));
                items.add(UItem.asCheck(BTN_ID_COPY, getString(R.string.PengramIdCopyOnTap)).setChecked(PengramConfig.copyIdOnTap));
                items.add(UItem.asShadow(getString(R.string.PengramIdCopyOnTapInfo)));
            } else {
                items.add(UItem.asShadow(null));
            }
        }

        items.add(UItem.asHeader(getString(R.string.PengramOriginalNameHeader)));
        items.add(UItem.asSettingsCell(BTN_ORIGINAL_NAME, R.drawable.msg_contacts,
                getString(R.string.PengramOriginalName), originalNameModeName()));
        items.add(UItem.asShadow(getString(R.string.PengramOriginalNameInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramRegHeader)));
        items.add(UItem.asSettingsCell(BTN_REG_STYLE_PICK, R.drawable.msg_calendar2, getString(R.string.PengramRegStyle), regStyleName(PengramConfig.getRegDateStyle())));
        if (PengramConfig.isRegDateVisible()) {
            items.add(UItem.asSettingsCell(BTN_REG_PLACE, R.drawable.msg_customize, getString(R.string.PengramRegPlace), regPlaceName(PengramConfig.getRegDatePlace())));
            if (PengramConfig.getRegDatePlace() != PengramConfig.REG_PLACE_SUBTITLE) {
                items.add(UItem.asSettingsCell(BTN_REG_ICON, PengramConfig.getRegDateIconRes() == 0 ? R.drawable.msg_info : PengramConfig.getRegDateIconRes(),
                        getString(R.string.PengramRegIcon), regIconName(PengramConfig.getRegDateIcon())));
                items.add(checkInfo(PengramConfig.KEY_REG_TAP_TEXT, true, getString(R.string.PengramRegTapText), getString(R.string.PengramRegTapTextInfo)));
            }
        }
        items.add(UItem.asShadow(getString(R.string.PengramRegInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramPrivacyHeader)));
        items.add(UItem.asCheck(BTN_HIDE_PHONE, getString(R.string.PengramHidePhone)).setChecked(PengramConfig.hidePhoneNumber));
        // Две галочки про один и тот же номер («прятать опцию» и «включать заранее»)
        // заменены одним выбором из трёх состояний: так понятнее, что произойдёт.
        items.add(UItem.asSettingsCell(BTN_SHARE_PHONE_MODE, R.drawable.msg_secret,
                getString(R.string.PengramSharePhoneMode), sharePhoneModeName(PengramConfig.getSharePhoneMode())));
        items.add(UItem.asShadow(getString(R.string.PengramSharePhoneModeInfo)));
        items.add(UItem.asShadow(getString(R.string.PengramHidePhoneInfo)));
    }

    private void fillHistory(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramHistoryHeader)));
        items.add(UItem.asCheck(BTN_HIST_DELETED, getString(R.string.PengramHistorySaveDeleted)).setChecked(PengramConfig.saveDeleted));
        items.add(UItem.asCheck(BTN_HIST_EDITED, getString(R.string.PengramHistorySaveEdited)).setChecked(PengramConfig.saveEdited));
        final boolean saving = PengramConfig.saveDeleted || PengramConfig.saveEdited;
        if (saving) {
            items.add(UItem.asCheck(BTN_HIST_OUTGOING, getString(R.string.PengramHistorySaveOutgoing))
                    .setChecked(PengramConfig.isSavingOutgoing()));
            items.add(check(PengramConfig.KEY_SAVE_FOR_MYSELF_SHOW, true, getString(R.string.PengramSaveForMyselfOption)));
            items.add(check(PengramConfig.KEY_SAVE_FOR_MYSELF_DEFAULT, false, getString(R.string.PengramSaveForMyselfDefault)));
            items.add(UItem.asCheck(BTN_SAVE_IN_BOTS, getString(R.string.PengramSaveInBots)).setChecked(PengramConfig.saveInBots));
        }
        items.add(UItem.asShadow(getString(R.string.PengramHistoryInfo2)));
        items.add(UItem.asHeader(getString(R.string.PengramProfileHistory)));
        items.add(UItem.asSettingsCell(BTN_PROFILE_HISTORY, R.drawable.msg_contacts,
                getString(R.string.PengramProfileHistory),
                onOff(org.telegram.messenger.PengramProfileHistory.enabled())));
        items.add(UItem.asShadow(getString(R.string.PengramProfileHistoryDisabledInfo)));

        if (PengramConfig.saveDeleted) {
            items.add(UItem.asHeader(getString(R.string.PengramInChatHeader)));
            items.add(check(PengramConfig.KEY_KEEP_DELETED, true, getString(R.string.PengramKeepDeleted)));
            items.add(UItem.asShadow(getString(R.string.PengramKeepDeletedInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.PengramHistoryStorage)));
        items.add(UItem.asSettingsCell(BTN_KEEP_DAYS, R.drawable.msg_autodelete, getString(R.string.PengramKeepDays), keepDaysName(PengramConfig.getHistoryKeepDays())));
        items.add(UItem.asSettingsCell(BTN_HIST_MAX, R.drawable.msg_limit_links, getString(R.string.PengramHistoryMax),
                historyMaxName(PengramConfig.getHistoryMaxEntries())));
        items.add(UItem.asButton(BTN_HIST_CLEAR, R.drawable.msg_delete, getString(R.string.PengramHistoryClearButton)).red());
        items.add(UItem.asShadow(historyStatsText()));

        if (PengramConfig.saveDeleted) {
            items.add(UItem.asHeader(getString(R.string.PengramMediaHeader)));
            items.add(UItem.asCheck(BTN_MEDIA_SAVE, getString(R.string.PengramMediaSave)).setChecked(PengramConfig.saveDeletedMedia));
            if (PengramConfig.saveDeletedMedia) {
                items.add(UItem.asButton(BTN_MEDIA_FOLDER, getString(R.string.PengramMediaFolder), PengramConfig.getMediaFolder()));
                items.add(UItem.asButton(BTN_MEDIA_PATTERN, getString(R.string.PengramMediaPattern), PengramConfig.getMediaPattern()));
                items.add(check(PengramConfig.KEY_MEDIA_GALLERY, false, getString(R.string.PengramMediaGallery)));
                if (expanded(GROUP_HISTORY_MEDIA)) {
                    items.add(UItem.asShadow(getString(PengramConfig.isMediaToGallery()
                            ? R.string.PengramMediaInfo : R.string.PengramMediaGalleryInfo)));

                    items.add(UItem.asHeader(getString(R.string.PengramMediaLimitHeader)));
                    items.add(UItem.asSettingsCell(BTN_MEDIA_LIMIT, R.drawable.msg_download, getString(R.string.PengramMediaLimitValue), mediaLimitName(PengramConfig.getMediaMaxSizeMb())));
                    items.add(UItem.asButton(BTN_MEDIA_CLEAR, R.drawable.msg_delete, getString(R.string.PengramMediaClear)).red());
                }
                items.add(moreButton(GROUP_HISTORY_MEDIA));
                items.add(UItem.asShadow(LocaleController.formatString(R.string.PengramMediaLimitInfo,
                            AndroidUtilities.formatFileSize(PengramHistory.getStatsCached(this::refreshList).mediaSize))));
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
                .setCollapsed(!isGhostExpanded())
                .setClickCallback(v -> {
                    PengramConfig.toggleGhostMode();
                    AndroidUtilities.vibrateCursor(v);
                    if (v instanceof TextCheckCell2) {
                        ((TextCheckCell2) v).setChecked(PengramConfig.ghostMode);
                    }
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                })
        );
        if (isGhostExpanded()) {
            items.add(UItem.asRoundCheckbox(BTN_DONT_READ, getString(R.string.PengramGhostDontRead)).setChecked(PengramConfig.dontSendRead).setPad(1));
            items.add(UItem.asRoundCheckbox(BTN_DONT_STORY, getString(R.string.PengramGhostDontStory)).setChecked(PengramConfig.dontSendStoryViews).setPad(1));
            items.add(UItem.asRoundCheckbox(BTN_HIDE_ONLINE, getString(R.string.PengramGhostHideOnline)).setChecked(PengramConfig.hideOnline).setPad(1));
            items.add(UItem.asRoundCheckbox(BTN_DONT_TYPE, getString(R.string.PengramGhostDontType)).setChecked(PengramConfig.dontSendTyping).setPad(1));
            items.add(subCheck(PengramConfig.KEY_GHOST_AUTO_OFFLINE, true, getString(R.string.PengramGhostAutoOffline)));
            items.add(subCheck(PengramConfig.KEY_GHOST_DONT_SEND_VOICE_READ, true, getString(R.string.PengramGhostDontSendVoiceRead)));
            items.add(subCheck(PengramConfig.KEY_GHOST_DONT_SEND_REACTIONS, false, getString(R.string.PengramGhostDontSendReactions)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramGhostInfo)
                + "\n\n" + getString(R.string.PengramGhostWhatHeader) + ": "
                + getString(R.string.PengramGhostWhatInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramGhostExtraHeader)));
        items.add(checkInfo(PengramConfig.KEY_GHOST_STORIES_WARN, false, getString(R.string.PengramGhostStoriesWarn), getString(R.string.PengramGhostStoriesWarnInfo)));
        items.add(checkInfo(PengramConfig.KEY_GHOST_SEND_DELAY, false, getString(R.string.PengramGhostSendDelay), getString(R.string.PengramGhostSendDelayInfo)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramTrackHeader)));
        items.add(UItem.asCheck(BTN_SAVE_READ_DATE, getString(R.string.PengramSaveReadDate)).setChecked(PengramConfig.saveReadDate));
        items.add(UItem.asCheck(BTN_SAVE_LAST_ONLINE, getString(R.string.PengramSaveLastOnline)).setChecked(PengramConfig.saveLastOnline));
        items.add(UItem.asShadow(getString(R.string.PengramTrackInfo)));
    }

    /** короткое состояние обхода для строки настроек */
    private CharSequence bypassStateName() {
        switch (org.telegram.messenger.PengramBypass.getStatus()) {
            case org.telegram.messenger.PengramBypass.STATUS_PROXY: return getString(R.string.PengramBypassStateProxy);
            case org.telegram.messenger.PengramBypass.STATUS_SEARCHING: return getString(R.string.PengramBypassStateSearching);
            case org.telegram.messenger.PengramBypass.STATUS_FAILED: return getString(R.string.PengramBypassStateFailed);
            case org.telegram.messenger.PengramBypass.STATUS_DIRECT: return getString(R.string.PengramBypassStateDirect);
            default: return getString(R.string.PengramBypassStateOff);
        }
    }

    private void fillFreedom(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramBypass)));
        items.add(UItem.asSettingsCell(BTN_BYPASS, R.drawable.msg_language,
                getString(R.string.PengramBypass), bypassStateName()));
        items.add(UItem.asShadow(getString(R.string.PengramBypassInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramFreedomHeader)));
        items.add(UItem.asCheck(BTN_SCREENSHOTS, getString(R.string.PengramAllowScreenshots)).setChecked(PengramConfig.allowScreenshots));
        if (PengramConfig.allowScreenshots) {
            items.add(UItem.asCheck(BTN_NO_SS_NOTIFY, getString(R.string.PengramNoScreenshotNotify)).setChecked(PengramConfig.noScreenshotNotify));
        }
        items.add(UItem.asCheck(BTN_FORWARDS, getString(R.string.PengramAllowForwards)).setChecked(PengramConfig.allowForwards));
        items.add(UItem.asCheck(BTN_KEEP_ONCE, getString(R.string.PengramKeepOnce)).setChecked(PengramConfig.keepOnceMedia));
        items.add(UItem.asShadow(getString(R.string.PengramFreedomInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramAntiCrashHeader)));
        items.add(check(PengramConfig.KEY_ANTICRASH, true, getString(R.string.PengramAntiCrash)));
        if (PengramConfig.isAntiCrash()) {
            items.add(check(PengramConfig.KEY_ANTICRASH_MARK, true, getString(R.string.PengramAntiCrashMark)));
            items.add(check(PengramConfig.KEY_ANTICRASH_JOURNAL, false, getString(R.string.PengramAntiCrashJournalEnabled)));
            items.add(UItem.asSettingsCell(BTN_ANTICRASH_STATS, R.drawable.msg_policy,
                    getString(R.string.PengramAntiCrashStats), antiCrashStats()));
            if (PengramConfig.isAntiCrashJournalEnabled()) {
                items.add(UItem.asSettingsCell(BTN_ANTICRASH_LOG, R.drawable.msg_secret,
                        getString(R.string.PengramAntiCrashLog), String.valueOf(PengramAntiCrash.journalSize())));
            }
        }
        items.add(UItem.asShadow(getString(R.string.PengramAntiCrashInfo)));

        // отчёт о вылете: причина уходит в буфер обмена прямо в момент падения
        items.add(UItem.asHeader(getString(R.string.PengramCrashReports)));
        items.add(UItem.asCheck(BTN_CRASH_AUTOCOPY, getString(R.string.PengramCrashAutoCopy))
                .setChecked(org.telegram.messenger.PengramCrashReport.isCopyEnabled()));
        items.add(UItem.asSettingsCell(BTN_CRASH_REPORTS, R.drawable.msg_report,
                getString(R.string.PengramCrashReports),
                org.telegram.ui.Components.PengramCrashDialogs.summary()));
        items.add(UItem.asShadow(LocaleController.formatString(R.string.PengramCrashReportsInfo,
                String.valueOf(org.telegram.messenger.PengramCrashReport.LIMIT))));

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

        items.add(UItem.asHeader(getString(R.string.PengramMD3Header)));
        if (md3Preview == null && getContext() != null) {
            md3Preview = new org.telegram.ui.Components.PengramMD3PreviewView(getContext(), getResourceProvider());
        }
        if (md3Preview != null) {
            md3Preview.update();
            items.add(UItem.asCustom(md3Preview));
        }
        items.add(checkInfo(org.telegram.messenger.PengramMD3.KEY_ENABLED, false,
                getString(R.string.PengramMD3), getString(R.string.PengramMD3Subtitle)));
        // подробности прячем за «Настроить» — включённый режим не должен вываливать десяток строк
        if (org.telegram.messenger.PengramMD3.isEnabled()) {
            if (expanded(GROUP_MD3)) {
                items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_DIALOGS, true, getString(R.string.PengramMD3Dialogs)));
                if (PengramConfig.getBool(org.telegram.messenger.PengramMD3.KEY_DIALOGS, true)) {
                    items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_TONAL_UNREAD, true, getString(R.string.PengramMD3Tonal)));
                    items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_NO_DIVIDERS, true, getString(R.string.PengramMD3NoDividers)));
                }
                items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_SEARCH_BAR, true, getString(R.string.PengramMD3SearchBar)));
                if (PengramConfig.getBool(org.telegram.messenger.PengramMD3.KEY_SEARCH_BAR, true)) {
                    items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_SEARCH_SHADOW, false, getString(R.string.PengramMD3SearchShadow)));
                }
                items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_SHEETS, true, getString(R.string.PengramMD3Sheets)));
                items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_ALERTS, true, getString(R.string.PengramMD3Alerts)));
                items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_SETTINGS, true, getString(R.string.PengramMD3Settings)));
                items.add(subCheck(org.telegram.messenger.PengramMD3.KEY_FAB, true, getString(R.string.PengramMD3Fab)));
            }
            items.add(moreButton(GROUP_MD3, getString(R.string.PengramMD3Tune)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramMD3Info)));

        items.add(UItem.asHeader(getString(R.string.PengramMonetHeader)));
        if (org.telegram.messenger.PengramMonet.isSupported()) {
            items.add(checkInfo(org.telegram.messenger.PengramMonet.KEY_ENABLED, false,
                    getString(R.string.PengramMonet), getString(R.string.PengramMonetSubtitle)));
            if (org.telegram.messenger.PengramMonet.isEnabled()) {
                if (expanded(GROUP_MONET)) {
                    items.add(subCheck(org.telegram.messenger.PengramMonet.KEY_MESSAGES, true,
                            getString(R.string.PengramMonetMessages)));
                    items.add(UItem.asSettingsCell(BTN_MONET_STRENGTH, R.drawable.msg_colors,
                            getString(R.string.PengramMonetStrength), monetStrengthName()));
                }
                items.add(moreButton(GROUP_MONET, getString(R.string.PengramMD3Tune)));
            }
            items.add(UItem.asShadow(getString(R.string.PengramMonetInfo)));
        } else {
            items.add(UItem.asShadow(getString(R.string.PengramMonetUnsupported)));
        }

        items.add(UItem.asHeader(getString(R.string.PengramQuickTilesHeader)));
        items.add(UItem.asSettingsCell(BTN_QUICK_TILES, R.drawable.msg_customize,
                getString(R.string.PengramQuickTiles), quickTilesValue()));
        items.add(UItem.asShadow(getString(R.string.PengramQuickTilesInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramAppearanceHeader)));
        if (fontPreview == null && getContext() != null) {
            fontPreview = new org.telegram.ui.Components.PengramFontPreviewView(getContext());
            fontPreview.setOnChanged(() -> {
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        }
        if (fontPreview != null) {
            fontPreview.update();
            items.add(UItem.asCustom(fontPreview));
        }
        // Одна строка вместо столбика радиокнопок: шрифтов стало больше, а
        // места они занимать стали меньше — выбор живёт в списке, превью выше
        // показывает результат сразу.
        items.add(UItem.asSettingsCell(BTN_FONT_PICK, R.drawable.msg_customize,
                getString(R.string.PengramFont), fontName(PengramConfig.appFont)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramTitleHeader)));
        items.add(UItem.asSettingsCell(BTN_TITLE_MODE, R.drawable.msg_edit, getString(R.string.PengramTitleText), titleModeName(PengramConfig.getTitleMode())));
        if (PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_CUSTOM) {
            items.add(UItem.asSettingsCell(BTN_TITLE_CUSTOM, R.drawable.msg_customize, getString(R.string.PengramTitleCustom),
                    PengramConfig.getTitleCustom().isEmpty() ? "Pengram" : PengramConfig.getTitleCustom()));
        }
        items.add(check(PengramConfig.KEY_TITLE_CENTER, false, getString(R.string.PengramTitleCenter)));
        items.add(checkInfo(PengramConfig.KEY_FORCE_SNOW, false, getString(R.string.PengramSnow), getString(R.string.PengramSnowInfo)));

        // Пузыри и время сообщений живут в «Чаты и кнопки → Сообщения» — здесь только переход,
        // чтобы одна и та же настройка не лежала в двух местах.
        items.add(sectionRow(BTN_SECTION_CHAT_MESSAGES, IconBackgroundColors.BLUE, R.drawable.msg_message,
                getString(R.string.PengramSubsectionMessages), null));
        items.add(UItem.asShadow(getString(R.string.PengramBubblesMovedInfo)));

        items.add(sectionRow(BTN_SECTION_PENGUIN, IconBackgroundColors.BLUE_LIGHT, R.drawable.pengram_penguin_glyph,
                getString(R.string.PengramSectionPenguin),
                getString(PengramConfig.getPenguinSkinName(PengramConfig.getPenguinSkin()))));
        items.add(UItem.asShadow(getString(R.string.PengramPenguinMovedInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramTabBarHeader)));
        items.add(UItem.asSettingsCell(BTN_TABBAR_SIZE, R.drawable.msg_customize, getString(R.string.PengramTabBarSize), PengramConfig.getTabBarSize() + "%"));
        items.add(UItem.asShadow(getString(R.string.PengramTabBarInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramTextHeader)));
        items.add(UItem.asSettingsCell(BTN_FONT_SIZE, R.drawable.msg_customize, getString(R.string.TextSizeHeader), String.valueOf(SharedConfig.fontSize)));
        items.add(UItem.asSettingsCell(BTN_BUBBLE_RADIUS, R.drawable.msg_message, getString(R.string.BubbleRadius), String.valueOf(SharedConfig.bubbleRadius)));
        items.add(tgCheck(BTN_EXTRA_BASE + 1, getString(R.string.LargeEmoji), () -> SharedConfig.allowBigEmoji, SharedConfig::toggleBigEmoji));
        items.add(tgCheck(BTN_EXTRA_BASE + 2, getString(R.string.PengramSystemEmoji), () -> SharedConfig.useSystemEmoji, this::toggleSystemEmoji));
        items.add(tgCheck(BTN_EXTRA_BASE + 3, getString(R.string.LoopAnimatedStickers), SharedConfig::loopStickers, SharedConfig::toggleLoopStickers));
        items.add(UItem.asShadow(null));

        // девять одинаковых галочек подряд читаются тяжело: три самых нужных сверху,
        // остальное — под «Показать ещё»
        items.add(UItem.asHeader(getString(R.string.PengramEffectsHeader)));
        items.add(liteCheck(BTN_EXTRA_BASE + 10, LiteMode.FLAG_ANIMATED_STICKERS_CHAT, getString(R.string.LiteOptionsStickers)));
        items.add(liteCheck(BTN_EXTRA_BASE + 11, LiteMode.FLAG_ANIMATED_EMOJI_CHAT, getString(R.string.LiteOptionsEmoji)));
        items.add(tgCheck(BTN_EXTRA_BASE + 18, getString(R.string.EnableAnimations), SharedConfig::animationsEnabled, this::toggleInterfaceAnimations));
        if (expanded(GROUP_EFFECTS)) {
            items.add(liteCheck(BTN_EXTRA_BASE + 12, LiteMode.FLAG_CHAT_BLUR, getString(R.string.PengramChatBlur)));
            items.add(liteCheck(BTN_EXTRA_BASE + 13, LiteMode.FLAG_CHAT_SPOILER, getString(R.string.PengramSpoilerEffect)));
            items.add(liteCheck(BTN_EXTRA_BASE + 14, LiteMode.FLAG_CHAT_THANOS, getString(R.string.PengramThanosEffect)));
            items.add(liteCheck(BTN_EXTRA_BASE + 15, LiteMode.FLAG_PARTICLES, getString(R.string.LiteOptionsParticles)));
            items.add(liteCheck(BTN_EXTRA_BASE + 16, LiteMode.FLAG_CALLS_ANIMATIONS, getString(R.string.LiteOptionsCalls)));
            items.add(liteCheck(BTN_EXTRA_BASE + 17, LiteMode.FLAG_CHAT_BACKGROUND, getString(R.string.PengramChatBackgroundAnim)));
        }
        items.add(moreButton(GROUP_EFFECTS));
        items.add(UItem.asShadow(getString(R.string.PengramEffectsInfo)));

        // Всё, что про список чатов, собрано в «Чаты и кнопки → Интерфейс».
        // Здесь остаётся только то, что меняет приложение целиком.
        items.add(UItem.asHeader(getString(R.string.PengramInterfaceHeader)));
        items.add(tgCheck(BTN_EXTRA_BASE + 22, getString(R.string.PengramNoTabletMode), () -> SharedConfig.forceDisableTabletMode, SharedConfig::toggleForceDisableTabletMode));
        items.add(UItem.asShadow(getString(R.string.PengramInterfaceInfo)));
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
        // Вместо простыни радио-кнопок — сетка карточек: все режимы видно сразу.
        if (voicePicker == null) {
            voicePicker = new org.telegram.ui.Components.PengramVoicePickerView(getContext(), getResourceProvider());
            voicePicker.setOnModeSelected(selectedMode -> {
                if (voicePreview != null) {
                    voicePreview.update();
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        }
        voicePicker.update();
        items.add(UItem.asCustom(voicePicker));
        final CharSequence currentVoiceDescription = mode == PengramVoiceChanger.MODE_CUSTOM ? null : voiceModeDescription(mode);
        if (!TextUtils.isEmpty(currentVoiceDescription)) {
            items.add(UItem.asShadow(currentVoiceDescription));
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

        items.add(UItem.asHeader(getString(R.string.PengramStreamHeader)));
        items.add(tgCheck(BTN_EXTRA_BASE + 40, getString(R.string.EnableStreaming), () -> SharedConfig.streamMedia, SharedConfig::toggleStreamMedia));
        items.add(tgCheck(BTN_EXTRA_BASE + 41, getString(R.string.PengramStreamAllVideo), () -> SharedConfig.streamAllVideo, SharedConfig::toggleStreamAllVideo));
        items.add(tgCheck(BTN_EXTRA_BASE + 42, getString(R.string.PengramStreamMkv), () -> SharedConfig.streamMkv, SharedConfig::toggleStreamMkv));
        items.add(tgCheck(BTN_EXTRA_BASE + 43, getString(R.string.PengramSaveStream), () -> SharedConfig.saveStreamMedia, SharedConfig::toggleSaveStreamMedia));
        items.add(UItem.asShadow(getString(R.string.PengramStreamInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramAutoplayHeader)));
        items.add(liteCheck(BTN_EXTRA_BASE + 44, LiteMode.FLAG_AUTOPLAY_GIFS, getString(R.string.LiteOptionsAutoplayGifs)));
        items.add(liteCheck(BTN_EXTRA_BASE + 45, LiteMode.FLAG_AUTOPLAY_VIDEOS, getString(R.string.LiteOptionsAutoplayVideo)));
        items.add(tgCheck(BTN_EXTRA_BASE + 46, getString(R.string.NextMediaTap), () -> SharedConfig.nextMediaTap, SharedConfig::toggleNextMediaTap));
        items.add(tgCheck(BTN_EXTRA_BASE + 47, getString(R.string.PengramSortFilesByName), () -> SharedConfig.sortFilesByName, SharedConfig::toggleSortFilesByName));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramCameraHeader)));
        items.add(tgCheck(BTN_EXTRA_BASE + 50, getString(R.string.PengramInAppCamera), () -> SharedConfig.inappCamera, SharedConfig::toggleInappCamera));
        items.add(tgCheck(BTN_EXTRA_BASE + 51, getString(R.string.PengramBigCameraRound), () -> SharedConfig.bigCameraForRound, SharedConfig::toggleRoundCamera));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramAudioHeader)));
        items.add(tgCheck(BTN_EXTRA_BASE + 60, getString(R.string.RaiseToSpeak), () -> SharedConfig.raiseToSpeak, SharedConfig::toggleRaiseToSpeak));
        items.add(tgCheck(BTN_EXTRA_BASE + 61, getString(R.string.RaiseToListen), () -> SharedConfig.raiseToListen, SharedConfig::toggleRaiseToListen));
        items.add(tgCheck(BTN_EXTRA_BASE + 62, getString(R.string.PengramPauseOnRecord), () -> SharedConfig.pauseMusicOnRecord, SharedConfig::togglePauseMusicOnRecord));
        items.add(tgCheck(BTN_EXTRA_BASE + 63, getString(R.string.PengramNoiseSuppression), () -> SharedConfig.noiseSupression, SharedConfig::toggleNoiseSupression));
        items.add(tgCheck(BTN_EXTRA_BASE + 64, getString(R.string.PengramVoiceEffectsOff), () -> SharedConfig.disableVoiceAudioEffects, SharedConfig::toggleDisableVoiceAudioEffects));
        items.add(UItem.asShadow(getString(R.string.PengramAudioInfo)));

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

    private CharSequence playerSectionValue() {
        return getString(PengramConfig.getPlayerStyleName(PengramConfig.getPlayerStyle()));
    }

    /** Пингвин — скины, жесты и всё, что с ним связано */
    private void fillPenguin(ArrayList<UItem> items) {
        if (headerView == null) {
            headerView = new PengramHeaderView(getContext());
        }
        items.add(UItem.asCustom(headerView));
        items.add(UItem.asShadow(getString(R.string.PengramPenguinInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramSkinsHeader)));
        final int skin = PengramConfig.getPenguinSkin();
        for (int a = 0; a < PengramConfig.SKIN_COUNT; ++a) {
            items.add(UItem.asRadio2(BTN_SKIN_BASE + a,
                    getString(PengramConfig.getPenguinSkinName(a)),
                    getString(skinDescription(a))).setChecked(skin == a));
        }
        items.add(UItem.asShadow(getString(R.string.PengramSkinsInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramPenguinHeader)));
        items.add(checkInfo(PengramConfig.KEY_PENGUIN_TIPS, true,
                getString(R.string.PengramPenguinTips), getString(R.string.PengramPenguinTipsInfo)));
        items.add(check(PengramConfig.KEY_PENGUIN_DANCE_MUSIC, true, getString(R.string.PengramPenguinDanceMusic)));
        items.add(check(PengramConfig.KEY_PENGUIN_SLEEP_GHOST, true, getString(R.string.PengramPenguinSleepGhost)));
        items.add(checkInfo(PengramConfig.KEY_PENGUIN_AUTO_SKIN, true,
                getString(R.string.PengramPenguinAutoSkin), getString(R.string.PengramPenguinAutoSkinInfo)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramPenguinSize)));
        items.add(UItem.asIntSlideView(
                1,
                70, PengramConfig.getPenguinSize(), 150,
                value -> value + "%",
                value -> {
                    PengramConfig.setPenguinSize(value);
                    // пересобираем шапку: размер задаётся при создании вьюхи
                    headerView = null;
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                }
        ));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramPenguinActionsHeader)));
        items.add(UItem.asButton(BTN_PENGUIN_FLIP, R.drawable.msg_reset, getString(R.string.PengramPenguinFlip)));
        items.add(UItem.asButton(BTN_PENGUIN_DANCE, R.drawable.msg_played, getString(R.string.PengramPenguinDance)));
        items.add(UItem.asButton(BTN_PENGUIN_STRAIGHTEN, R.drawable.msg_photo_rotate, getString(R.string.PengramPenguinStraighten)));
        items.add(UItem.asShadow(getString(R.string.PengramPenguinGesturesInfo)));
    }

    private int skinDescription(int skin) {
        switch (skin) {
            case PengramConfig.SKIN_SANTA: return R.string.PengramSkinSantaInfo;
            case PengramConfig.SKIN_SCARF: return R.string.PengramSkinScarfInfo;
            case PengramConfig.SKIN_CAP: return R.string.PengramSkinCapInfo;
            case PengramConfig.SKIN_GLASSES: return R.string.PengramSkinGlassesInfo;
            case PengramConfig.SKIN_CROWN: return R.string.PengramSkinCrownInfo;
            case PengramConfig.SKIN_HEADPHONES: return R.string.PengramSkinHeadphonesInfo;
            case PengramConfig.SKIN_BOWTIE: return R.string.PengramSkinBowtieInfo;
            case PengramConfig.SKIN_WIZARD: return R.string.PengramSkinWizardInfo;
            default: return R.string.PengramSkinNoneInfo;
        }
    }

    private org.telegram.ui.Components.PengramPlayerMockView playerMock;
    private org.telegram.ui.Components.PengramSwipePreview swipePreview;

    /** Плеер — Spotify-режим и текст песни */
    private void fillPlayer(ArrayList<UItem> items) {
        // живая миниатюра плеера: по нажатию перебирает оформления прямо на месте
        if (playerMock == null && getContext() != null) {
            playerMock = new org.telegram.ui.Components.PengramPlayerMockView(getContext(), PengramConfig.getPlayerStyle());
            playerMock.setAccent(Theme.getColor(Theme.key_featuredStickers_addButton));
            playerMock.setOnClickListener(v -> {
                final int next = (PengramConfig.getPlayerStyle() + 1) % PengramConfig.PLAYER_STYLE_COUNT;
                PengramConfig.setPlayerStyle(next);
                playerMock.setStyle(next);
                AndroidUtilities.vibrateCursor(v);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        }
        if (playerMock != null) {
            playerMock.setStyle(PengramConfig.getPlayerStyle());
            items.add(UItem.asCustom(playerMock, 150));
            items.add(UItem.asShadow(getString(R.string.PengramPlayerPreviewInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.PengramPlayerHeader)));
        items.add(UItem.asSettingsCell(BTN_PLAYER_STYLE, R.drawable.msg_played, getString(R.string.PengramPlayerLook), getString(PengramConfig.getPlayerStyleName(PengramConfig.getPlayerStyle()))));
        if (PengramConfig.isNewPlayer()) {
            items.add(UItem.asSettingsCell(BTN_PLAYER_BG, R.drawable.msg_theme, getString(R.string.PengramPlayerBg), getString(PengramConfig.getPlayerBgName(PengramConfig.getPlayerBg()))));
            items.add(UItem.asSettingsCell(BTN_PLAYER_ACCENT, R.drawable.msg_palette,
                    getString(R.string.PengramPlayerAccent), playerAccentValue()));
            items.add(UItem.asSettingsCell(BTN_COVER_SHAPE, R.drawable.msg_photos, getString(R.string.PengramCoverShape), getString(PengramConfig.getCoverShapeName(PengramConfig.getCoverShape()))));
            items.add(check(PengramConfig.KEY_PLAYER_BLUR, true, getString(R.string.PengramPlayerBlur)));
            // что показывать, когда у трека нет картинки
            items.add(UItem.asSettingsCell(BTN_EMPTY_COVER, R.drawable.pengram_penguin_glyph,
                    getString(R.string.PengramEmptyCover),
                    getString(PengramConfig.getEmptyCoverName(PengramConfig.getEmptyCoverMode()))));
            if (PengramConfig.isEmptyCoverPenguin()) {
                items.add(checkInfo(PengramConfig.KEY_PENGUIN_DANCE, true,
                        getString(R.string.PengramPlayerPenguinDance), getString(R.string.PengramPlayerPenguinDanceInfo)));
            }
        }
        if (PengramConfig.isNewPlayer()) {
            items.add(UItem.asShadow(PengramConfig.isEmptyCoverPenguin()
                    ? getString(R.string.PengramEmptyCoverInfo)
                    : getString(R.string.PengramPlayerAccentInfo)));
        } else {
            items.add(UItem.asShadow(null));
        }

        // Свайп по свёрнутому плееру: листать треки прямо из шапки
        items.add(UItem.asHeader(getString(R.string.PengramPlayerSwipeHeader)));
        items.add(check(PengramConfig.KEY_PLAYER_SWIPE, true, getString(R.string.PengramPlayerSwipe)));
        if (swipePreview == null) {
            swipePreview = new org.telegram.ui.Components.PengramSwipePreview(getContext());
        }
        swipePreview.setEnabledPreview(PengramConfig.isPlayerSwipe());
        items.add(UItem.asCustom(swipePreview, 104));
        items.add(UItem.asShadow(getString(R.string.PengramPlayerSwipeInfo)));

        // Пересылка трека: манера выбирается заранее, кнопка потом не задаёт лишних вопросов
        items.add(UItem.asHeader(getString(R.string.PengramTrackForwardHeader)));
        items.add(checkInfo(PengramConfig.KEY_TRACK_FORWARD_BUTTON, true,
                getString(R.string.PengramTrackForwardButton), getString(R.string.PengramTrackForwardButtonInfo)));
        items.add(checkInfo(PengramConfig.KEY_MUSIC_FORWARD_CLEAN, true,
                getString(R.string.PengramMusicForwardClean), getString(R.string.PengramMusicForwardCleanInfo)));
        if (!PengramConfig.isMusicForwardClean()) {
            // манера и подпись имеют смысл только когда трек пересылается «как есть»
            items.add(UItem.asSettingsCell(BTN_TRACK_FORWARD_MODE, R.drawable.msg_forward,
                    getString(R.string.PengramTrackForwardMode),
                    org.telegram.ui.Components.PengramTrackForward.modeName(PengramConfig.getTrackForwardMode())));
            items.add(checkInfo(PengramConfig.KEY_TRACK_FORWARD_CAPTION, false,
                    getString(R.string.PengramTrackForwardCaption), getString(R.string.PengramTrackForwardCaptionInfo)));
        }
        items.add(UItem.asShadow(org.telegram.ui.Components.PengramTrackForward.buttonHint()));
        items.add(UItem.asHeader(getString(R.string.PengramMusicMetaHeader)));
        items.add(checkInfo(PengramConfig.KEY_MUSIC_SMART_ARTIST, true,
                getString(R.string.PengramMusicSmartArtist), getString(R.string.PengramMusicSmartArtistInfo)));
        items.add(UItem.asShadow(null));

        items.add(sectionRow(BTN_SECTION_LYRICS, IconBackgroundColors.ORANGE, R.drawable.msg_msgbubble3,
                getString(R.string.PengramLyricsSection), lyricsSectionValue()));
        items.add(UItem.asShadow(getString(R.string.PengramLyricsSectionInfo)));

    }

    /** Текст песни: отдельный экран — в плеере этих строк было больше, чем всего остального */
    private void fillLyrics(ArrayList<UItem> items) {
        if (!PengramConfig.isNewPlayer()) {
            items.add(UItem.asShadow(getString(R.string.PengramLyricsNeedsNewPlayer)));
            return;
        }
            items.add(UItem.asHeader(getString(R.string.PengramLyricsSyncHeader)));
            items.add(checkInfo(PengramConfig.KEY_LYRICS_AUTO, true,
                    getString(R.string.PengramLyricsAuto), getString(R.string.PengramLyricsAutoInfo)));
            if (!PengramConfig.isLyricsAuto()) {
                // ручной режим: тут всё то же самое, но руками
                items.add(UItem.asSettingsCell(BTN_LYRICS_SOURCE, R.drawable.msg_download,
                        getString(R.string.PengramLyricsSourceHeader),
                        getString(PengramConfig.getLyricsSourceName(PengramConfig.getLyricsSourceRaw()))));
                items.add(UItem.asShadow(getString(PengramConfig.getLyricsSourceInfo(PengramConfig.getLyricsSourceRaw()))));
                items.add(checkInfo(PengramConfig.KEY_LYRICS_STRETCH, true, getString(R.string.PengramLyricsStretch), getString(R.string.PengramLyricsStretchInfo)));
                items.add(checkInfo(PengramConfig.KEY_LYRICS_SMOOTH, true, getString(R.string.PengramLyricsSmooth), getString(R.string.PengramLyricsSmoothInfo)));
                items.add(UItem.asShadow(null));

                items.add(UItem.asHeader(getString(R.string.PengramLyricsOffset)));
                items.add(UItem.asIntSlideView(1, 0,
                        Math.max(0, Math.min(40, Math.round(PengramConfig.getLyricsOffset() / 100f) + 20)), 40,
                        value -> String.format(java.util.Locale.US, "%+.1f c", (value - 20) * 0.1f),
                        value -> PengramConfig.setLyricsOffset((value - 20) * 100)));
                items.add(UItem.asShadow(getString(R.string.PengramLyricsOffsetInfo)));
            } else {
                items.add(UItem.asShadow(getString(R.string.PengramLyricsAutoDetails)));
            }

            items.add(UItem.asHeader(getString(R.string.PengramLyricsHeader)));
            items.add(UItem.asSettingsCell(BTN_LYRICS_ANIM, R.drawable.msg_customize, getString(R.string.PengramLyricsAnim), getString(PengramConfig.getLyricsAnimName(PengramConfig.getLyricsAnim()))));
            items.add(UItem.asSettingsCell(BTN_LYRICS_ALIGN, R.drawable.msg_message, getString(R.string.PengramLyricsAlign), getString(PengramConfig.getLyricsAlignName(PengramConfig.getLyricsAlign()))));
            items.add(check(PengramConfig.KEY_LYRICS_BOLD, true, getString(R.string.PengramLyricsBold)));
            items.add(check(PengramConfig.KEY_LYRICS_SHADOW, true, getString(R.string.PengramLyricsShadow)));
            items.add(check(PengramConfig.KEY_LYRICS_AUTOSCROLL, true, getString(R.string.PengramLyricsAutoScroll)));
            items.add(UItem.asShadow(getString(R.string.PengramLyricsAnimInfo)));

            items.add(UItem.asHeader(getString(R.string.PengramLyricsSize)));
            items.add(UItem.asIntSlideView(1, 14, PengramConfig.getLyricsSize(), 40,
                    value -> value + " dp",
                    value -> PengramConfig.setLyricsSize(value)));
            items.add(UItem.asShadow(null));

            items.add(UItem.asHeader(getString(R.string.PengramLyricsDim)));
            items.add(UItem.asIntSlideView(1, 5, PengramConfig.getLyricsDim(), 100,
                    value -> value + "%",
                    value -> PengramConfig.setLyricsDim(value)));
            items.add(UItem.asShadow(getString(R.string.PengramLyricsDimInfo)));

            items.add(UItem.asHeader(getString(R.string.PengramLyricsSpeed)));
            items.add(UItem.asIntSlideView(1, 25, PengramConfig.getLyricsSpeed(), 300,
                    value -> value + "%",
                    value -> PengramConfig.setLyricsSpeed(value)));
            items.add(UItem.asShadow(null));

            items.add(UItem.asHeader(getString(R.string.PengramHeaderLyrics)));
            items.add(checkInfo(PengramConfig.KEY_HEADER_LYRICS, true, getString(R.string.PengramHeaderLyricsOn), getString(R.string.PengramHeaderLyricsInfo)));
            if (PengramConfig.isHeaderLyrics()) {
                items.add(UItem.asSettingsCell(BTN_HEADER_LYRICS_ANIM, R.drawable.msg_customize, getString(R.string.PengramHeaderLyricsAnim),
                        PengramConfig.getHeaderLyricsAnim() < 0
                                ? getString(R.string.PengramHeaderLyricsAnimSame)
                                : getString(PengramConfig.getLyricsAnimName(PengramConfig.getHeaderLyricsAnim()))));
                items.add(checkInfo(PengramConfig.KEY_HEADER_LYRICS_MARQUEE, true, getString(R.string.PengramHeaderLyricsMarquee), getString(R.string.PengramHeaderLyricsMarqueeInfo)));
                items.add(check(PengramConfig.KEY_HEADER_LYRICS_WORDS, true, getString(R.string.PengramHeaderLyricsWords)));
                items.add(check(PengramConfig.KEY_HEADER_LYRICS_BOLD, false, getString(R.string.PengramHeaderLyricsBold)));
                items.add(UItem.asShadow(null));

                items.add(UItem.asHeader(getString(R.string.PengramHeaderLyricsSize)));
                items.add(UItem.asIntSlideView(1, 11, PengramConfig.getHeaderLyricsSize(), 20,
                        value -> value + " dp",
                        value -> PengramConfig.setHeaderLyricsSize(value)));
                items.add(UItem.asShadow(null));

                items.add(UItem.asHeader(getString(R.string.PengramHeaderLyricsSpeedTitle)));
                items.add(UItem.asIntSlideView(1, 40, PengramConfig.getHeaderLyricsSpeed(), 250,
                        value -> value + "%",
                        value -> PengramConfig.setHeaderLyricsSpeed(value)));
                items.add(UItem.asShadow(getString(R.string.PengramHeaderLyricsSpeedInfo)));
            }
            items.add(UItem.asShadow(null));

            items.add(UItem.asButton(BTN_LYRICS_CLEAR, R.drawable.msg_delete, LocaleController.formatString(R.string.PengramLyricsClear, org.telegram.messenger.PengramLyrics.savedCount())).red());
            items.add(UItem.asShadow(getString(R.string.PengramLyricsClearInfo)));
    }

    private void fillChats(ArrayList<UItem> items) {
        // у каждого подраздела своя подпись — видно состояние, не заходя внутрь
        items.add(sectionRow(BTN_SECTION_CHAT_ACTIONS, IconBackgroundColors.ORANGE, R.drawable.msg_customize,
                getString(R.string.PengramSubsectionActions), String.valueOf(PengramConfig.getQuickActionCount())));
        items.add(sectionRow(BTN_SECTION_CHAT_MESSAGES, IconBackgroundColors.BLUE, R.drawable.msg_message,
                getString(R.string.PengramSubsectionMessages), inputAnimationName()));
        items.add(sectionRow(BTN_SECTION_CHAT_INTERFACE, IconBackgroundColors.PURPLE, R.drawable.settings_chat,
                getString(R.string.PengramSubsectionInterface), hiddenCountValue()));
        items.add(sectionRow(BTN_SECTION_CHAT_MENUS, IconBackgroundColors.GRAY, R.drawable.msg_settings_old,
                getString(R.string.PengramSubsectionMenus), hiddenChatItemsValue()));
        items.add(UItem.asShadow(getString(R.string.PengramChatsHubInfo)));
    }

    private void fillChatActions(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramQuickActions)));
        final int quickCount = PengramConfig.getQuickActionCount();
        for (int i = 0; i < quickCount; i++) {
            final String type = getString(PengramConfig.getQuickActionType(i) == PengramConfig.QUICK_ACTION_FORWARD
                    ? R.string.PengramQuickTypeForward : R.string.PengramQuickTypeText);
            items.add(UItem.asSettingsCell(BTN_QUICK_ACTION_BASE + i,
                    PengramConfig.getQuickActionType(i) == PengramConfig.QUICK_ACTION_FORWARD ? R.drawable.msg_forward : R.drawable.msg_message,
                    PengramConfig.getQuickActionName(i), type));
        }
        if (quickCount < PengramConfig.QUICK_ACTION_LIMIT) {
            items.add(UItem.asButton(BTN_QUICK_ADD, R.drawable.msg_add, getString(R.string.PengramQuickAdd)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramQuickActionsInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramDeletedSendHeader)));
        // Базовые пункты отправки удалёнки и отправка без чужой подписи всегда включены.
        // Пользователю остаются только действительно значимые варианты поведения.
        items.add(check(PengramConfig.KEY_RESEND_ONCE, true, getString(R.string.PengramResendOnce)));
        items.add(check(PengramConfig.KEY_RESEND_ASK_CHAT, false, getString(R.string.PengramResendAsk)));
        items.add(UItem.asShadow(getString(R.string.PengramResendAlwaysOnInfo)));
        items.add(UItem.asHeader(getString(R.string.PengramForwardHeader)));
        items.add(checkInfo(PengramConfig.KEY_FORWARD_LOCK, true, getString(R.string.PengramForwardLock), getString(R.string.PengramForwardLockInfo)));
        if (PengramConfig.isForwardLockEnabled()) items.add(check(PengramConfig.KEY_FORWARD_DONE_ALERT, true, getString(R.string.PengramForwardDoneAlert)));
        items.add(UItem.asShadow(getString(R.string.PengramForwardInfo)));
    }

    private void fillChatMessages(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramMessagesHeader)));
        items.add(UItem.asSettingsCell(BTN_CHAT_LOOK, R.drawable.msg_openprofile, getString(R.string.PengramChatLook), getString(PengramConfig.getGroupAvatarPosName(PengramConfig.getGroupAvatarPos()))));
        items.add(checkInfo(PengramConfig.KEY_KEEP_FORMATTING, true, getString(R.string.PengramKeepFormatting), getString(R.string.PengramKeepFormattingInfo)));
        items.add(UItem.asShadow(getString(R.string.PengramChatLookInfo)));

        // --- вид пузырей (переехало сюда из «Оформления»: это про сообщения, а не про тему) ---
        items.add(UItem.asHeader(getString(R.string.PengramBubblesHeader)));
        items.add(checkInfo(PengramConfig.KEY_HIDE_TAIL, false, getString(R.string.PengramHideTail), getString(R.string.PengramHideTailInfo)));
        items.add(checkInfo(PengramConfig.KEY_HIDE_EDITED_LABEL, false, getString(R.string.PengramHideEditedLabel), getString(R.string.PengramHideEditedLabelInfo)));
        items.add(UItem.asShadow(null));

        // --- время, которое лежит поверх стикера/медиа ---
        fillMediaTime(items);
        items.add(UItem.asHeader(getString(R.string.PengramInputAnimationHeader)));
        if (typingPreview == null && getContext() != null) typingPreview = new org.telegram.ui.Components.PengramTypingPreviewView(getContext());
        if (typingPreview != null) { typingPreview.update(); items.add(UItem.asCustom(typingPreview, 90)); }
        items.add(UItem.asSettingsCell(BTN_INPUT_ANIMATION, R.drawable.msg_customize,
                getString(R.string.PengramInputAnimation), inputAnimationName()));
        if (PengramConfig.getInputAnimation() != PengramConfig.INPUT_ANIM_NONE) {
            items.add(UItem.asSettingsCell(BTN_INPUT_ANIMATION_SPEED, getString(R.string.PengramInputAnimationSpeed), inputAnimationSpeedName()));
            items.add(UItem.asSettingsCell(BTN_INPUT_ANIMATION_INTENSITY, getString(R.string.PengramInputAnimationIntensity), inputAnimationIntensityName()));
            items.add(check(PengramConfig.KEY_INPUT_ANIMATION_HAPTIC, false, getString(R.string.PengramInputAnimationHaptic)));
        }
        items.add(UItem.asShadow(getString(R.string.PengramInputAnimationInfo)));
        items.add(UItem.asHeader(getString(R.string.PengramSelectionLimit)));
        items.add(UItem.asSlideView(selectionLimitNames(), selectionLimitIndex(), value -> PengramConfig.setSelectionLimit(PengramConfig.SELECTION_LIMITS[Math.max(0, Math.min(PengramConfig.SELECTION_LIMITS.length - 1, value))])));
        items.add(UItem.asShadow(getString(R.string.PengramSelectionLimitInfo)));
        items.add(checkInfo(PengramConfig.KEY_FORCE_DELETE_FOR_ALL, true, getString(R.string.PengramForceDeleteForAll), getString(R.string.PengramForceDeleteForAllInfo)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(getString(R.string.PengramMessageMenuHeader)));
        items.add(check(PengramConfig.KEY_MENU_COPY_MESSAGE_ID, true, getString(R.string.PengramMenuCopyMessageId)));
        items.add(check(PengramConfig.KEY_MENU_SAVE_TO_SAVED, true, getString(R.string.PengramMenuSaveToSaved)));
        items.add(UItem.asShadow(getString(R.string.PengramMessageMenuInfo)));
    }

    /**
     * Плашка времени поверх стикеров, кружков и медиа.
     * Три понятных режима + уточнения, которые появляются, только когда время вообще прячется.
     */
    private void fillMediaTime(ArrayList<UItem> items) {
        final int mode = PengramConfig.getMediaTimeMode();
        items.add(UItem.asHeader(getString(R.string.PengramMediaTimeHeader)));
        items.add(UItem.asRadio2(BTN_MEDIA_TIME_DEFAULT, getString(R.string.PengramMediaTimeDefault), getString(R.string.PengramMediaTimeDefaultInfo))
                .setChecked(mode == PengramConfig.MEDIA_TIME_DEFAULT));
        items.add(UItem.asRadio2(BTN_MEDIA_TIME_STICKERS, getString(R.string.PengramMediaTimeStickers), getString(R.string.PengramMediaTimeStickersInfo))
                .setChecked(mode == PengramConfig.MEDIA_TIME_HIDE_STICKERS));
        items.add(UItem.asRadio2(BTN_MEDIA_TIME_MEDIA, getString(R.string.PengramMediaTimeMedia), getString(R.string.PengramMediaTimeMediaInfo))
                .setChecked(mode == PengramConfig.MEDIA_TIME_HIDE_MEDIA));
        if (mode == PengramConfig.MEDIA_TIME_DEFAULT) {
            items.add(UItem.asShadow(getString(R.string.PengramMediaTimeInfo)));
            return;
        }
        items.add(UItem.asShadow(null));
        items.add(subCheck(PengramConfig.KEY_MEDIA_TIME_ROUND, false, getString(R.string.PengramMediaTimeRound)));
        items.add(subCheck(PengramConfig.KEY_MEDIA_TIME_SENDING, true, getString(R.string.PengramMediaTimeSending)));
        items.add(UItem.asShadow(getString(R.string.PengramMediaTimeHiddenInfo)));
    }

    private void fillChatInterface(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramTabsHeader)));
        if (tabsMockView == null && getContext() != null) { tabsMockView = new org.telegram.ui.Components.PengramTabsMockView(getContext()); tabsMockView.setOnChanged(this::applyTabsNow); }
        if (tabsMockView != null) { tabsMockView.sync(); items.add(UItem.asCustom(tabsMockView, 140)); }
        items.add(UItem.asShadow(getString(R.string.PengramTabsInfo2)));
        items.add(UItem.asHeader(getString(R.string.PengramDialogsHeader)));
        // переехало из «Оформления»: это настройки именно списка чатов
        items.add(check(PengramConfig.KEY_HIDE_WRITE_BUTTON, false, getString(R.string.PengramHideWriteButton)));
        items.add(tgCheck(BTN_EXTRA_BASE + 20, getString(R.string.PengramThreeLines), () -> SharedConfig.useThreeLinesLayout, () -> SharedConfig.setUseThreeLinesLayout(!SharedConfig.useThreeLinesLayout)));
        items.add(tgCheck(BTN_EXTRA_BASE + 21, getString(R.string.PengramHideArchive), () -> SharedConfig.archiveHidden, SharedConfig::toggleArchiveHidden));
        items.add(checkInfo(PengramConfig.KEY_HIDE_STORIES, false, getString(R.string.PengramHideStories), getString(R.string.PengramHideStoriesInfo)));
        items.add(checkInfo(PengramConfig.KEY_DIALOG_SENDER_AVATARS, false, getString(R.string.PengramSenderAvatars), getString(R.string.PengramSenderAvatarsInfo)));
        if (PengramConfig.isDialogSenderAvatars()) {
            // Длинное выбранное значение выводим отдельной строкой: на узких экранах
            // title/value в TextSettingsCell визуально склеивались и перекрывались.
            items.add(UItem.asSettingsCell(BTN_SENDER_AVATAR_POSITION, R.drawable.msg_customize,
                    getString(R.string.PengramSenderAvatarPosition)));
            items.add(UItem.asShadow(senderAvatarPositionName()));
        } else {
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asHeader(getString(R.string.PengramGesturesHeader)));
        items.add(UItem.asSettingsCell(BTN_SWIPE_ACTION, R.drawable.msg_archive, getString(R.string.PengramSwipeAction), swipeActionName(SharedConfig.getChatSwipeAction(currentAccount))));
        items.add(UItem.asShadow(null));
    }

    private void fillChatMenus(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PengramChatMenu)));
        items.add(UItem.asCheck(BTN_CHAT_MENU, getString(R.string.PengramChatMenu)).setChecked(PengramConfig.chatMenuEnabled));
        if (PengramConfig.chatMenuEnabled) {
            items.add(UItem.asRadio(BTN_CHAT_MENU_TOP, getString(R.string.PengramChatMenuTop)).setChecked(PengramConfig.chatMenuPosition == PengramConfig.MENU_POS_TOP));
            items.add(UItem.asRadio(BTN_CHAT_MENU_BOTTOM, getString(R.string.PengramChatMenuBottom)).setChecked(PengramConfig.chatMenuPosition == PengramConfig.MENU_POS_BOTTOM));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.PengramHideMenuHeader)));
        items.add(UItem.asSettingsCell(BTN_MENU_ITEMS, R.drawable.msg_viewchats, getString(R.string.PengramMenuItemsTitle), hiddenMenuValue()));
        items.add(UItem.asSettingsCell(BTN_SETTINGS_ITEMS, R.drawable.msg_settings_old, getString(R.string.PengramSettingsItemsTitle), hiddenSettingsValue()));
        items.add(UItem.asSettingsCell(BTN_CHAT_ITEMS, R.drawable.msg_message, getString(R.string.PengramChatItemsTitle), hiddenChatItemsValue()));
        items.add(checkInfo(PengramConfig.KEY_PENGRAM_CARD, true, getString(R.string.PengramCardOnTop), getString(R.string.PengramCardOnTopInfo)));
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

    private void editQuickAction(final int index, final boolean isNew) {
        final CharSequence[] options = new CharSequence[]{
                getString(R.string.PengramQuickTypeText),
                getString(R.string.PengramQuickTypeForward),
                getString(R.string.Delete)
        };
        final int selected = PengramConfig.getQuickActionType(index) == PengramConfig.QUICK_ACTION_FORWARD ? 1 : 0;
        showChoicePicker(getString(R.string.PengramQuickActionType), options, selected, choice -> {
            if (choice == 2) {
                if (!isNew) {
                    PengramConfig.removeQuickAction(index);
                    if (listView != null && listView.adapter != null) listView.adapter.update(true);
                }
                return;
            }
            final int type = choice == 1 ? PengramConfig.QUICK_ACTION_FORWARD : PengramConfig.QUICK_ACTION_TEXT;
            showTextDialog(getString(R.string.PengramQuickActionName), PengramConfig.getQuickActionName(index),
                    getString(R.string.PengramQuickDefaultName), name -> showTextDialog(
                            getString(type == PengramConfig.QUICK_ACTION_FORWARD ? R.string.PengramQuickActionLink : R.string.PengramQuickActionText),
                            PengramConfig.getQuickActionValue(index), "", value -> {
                                if (type == PengramConfig.QUICK_ACTION_FORWARD && !PengramConfig.isValidQuickUrl(value)) {
                                    new AlertDialog.Builder(getContext()).setTitle(getString(R.string.AppName))
                                            .setMessage(getString(R.string.PengramQuickInvalidUrl)).setPositiveButton(getString(R.string.OK), null).show();
                                    return;
                                }
                                if (type == PengramConfig.QUICK_ACTION_TEXT && (value == null || value.isEmpty())) {
                                    new AlertDialog.Builder(getContext()).setTitle(getString(R.string.AppName))
                                            .setMessage(getString(R.string.PengramQuickEmptyText)).setPositiveButton(getString(R.string.OK), null).show();
                                    return;
                                }
                                PengramConfig.setQuickAction(index, name, value, type);
                                if (isNew) {
                                    PengramConfig.setQuickActionCount(index + 1);
                                }
                                if (listView != null && listView.adapter != null) listView.adapter.update(true);
                            }));
        });
    }

    private CharSequence quickUrlPreview(String value) {
        if (value == null) return "";
        String text = value.replaceFirst("(?i)^https?://", "");
        if (text.length() <= 26) return text;
        final int slash = text.lastIndexOf('/');
        final String tail = slash >= 0 ? text.substring(slash) : "";
        return text.substring(0, Math.min(18, text.length())) + "…" + tail;
    }

    private CharSequence inputAnimationName() {
        final int[] names = {R.string.PengramInputAnimNone, R.string.PengramInputAnimFade, R.string.PengramInputAnimPop,
                R.string.PengramInputAnimSlide, R.string.PengramInputAnimRise, R.string.PengramInputAnimBounce, R.string.PengramInputAnimShake};
        return getString(names[PengramConfig.getInputAnimation()]);
    }

    private CharSequence inputAnimationSpeedName() {
        return getString(new int[]{R.string.PengramInputSpeedFast, R.string.PengramInputSpeedNormal, R.string.PengramInputSpeedSmooth}[PengramConfig.getInputAnimationSpeed()]);
    }

    private CharSequence inputAnimationIntensityName() {
        return getString(new int[]{R.string.PengramInputIntensitySoft, R.string.PengramInputIntensityMedium, R.string.PengramInputIntensityStrong}[PengramConfig.getInputAnimationIntensity() - 1]);
    }

    private CharSequence senderAvatarPositionName() {
        switch (PengramConfig.getDialogSenderAvatarPosition()) {
            case PengramConfig.SENDER_AVATAR_BOTTOM: return getString(R.string.PengramSenderAvatarBottom);
            case PengramConfig.SENDER_AVATAR_TOP: return getString(R.string.PengramSenderAvatarTop);
            case PengramConfig.SENDER_AVATAR_BEFORE_NAME: return getString(R.string.PengramSenderAvatarBeforeName);
            case PengramConfig.SENDER_AVATAR_AFTER_NAME: return getString(R.string.PengramSenderAvatarAfterName);
            default: return getString(R.string.PengramSenderAvatarInline);
        }
    }

    private String[] selectionLimitNames() {
        final String[] result = new String[PengramConfig.SELECTION_LIMITS.length];
        for (int a = 0; a < result.length; ++a) {
            result[a] = String.valueOf(PengramConfig.SELECTION_LIMITS[a]);
        }
        return result;
    }

    private int selectionLimitIndex() {
        final int current = PengramConfig.getSelectionLimit();
        for (int a = 0; a < PengramConfig.SELECTION_LIMITS.length; ++a) {
            if (PengramConfig.SELECTION_LIMITS[a] == current) {
                return a;
            }
        }
        return 0;
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
        // «О программе»: тап по любой строке кладёт её значение в буфер
        if (item.id >= BTN_ABOUT_ROW_BASE && aboutValues.indexOfKey(item.id) >= 0) {
            final CharSequence value = aboutValues.get(item.id);
            if (!TextUtils.isEmpty(value)) {
                AndroidUtilities.addToClipboard(value.toString());
                BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
            }
            return;
        }
        // выбор скина пингвина — применяется мгновенно, прямо на превью сверху
        if (item.id >= BTN_SKIN_BASE && item.id < BTN_SKIN_BASE + PengramConfig.SKIN_COUNT) {
            PengramConfig.setPenguinSkin(item.id - BTN_SKIN_BASE);
            if (headerView != null) {
                headerView.applySkin();
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            return;
        }
        if (item.id >= BTN_SEARCH_BASE && item.id - BTN_SEARCH_BASE < searchResults.size()) {
            final int[] found = searchResults.get(item.id - BTN_SEARCH_BASE);
            final int targetSection = found[1];
            final int targetRes = found[0];
            if (targetSection == section) {
                // мы уже здесь — закрываем поиск и прыгаем к самой строке
                actionBar.closeSearchField();
                AndroidUtilities.runOnUIThread(() -> pengramJumpTo(targetRes), 160);
            } else {
                presentFragment(new PengramSettingsActivity(targetSection, targetRes));
            }
            return;
        }
        if (item.id == BTN_PENGUIN_FLIP || item.id == BTN_PENGUIN_DANCE || item.id == BTN_PENGUIN_STRAIGHTEN) {
            if (headerView != null) {
                headerView.doAction(item.id);
            }
            return;
        }
        if (onAIClick(item)) {
            return;
        }
        final Runnable extraToggle = extraToggles.get(item.id);
        if (extraToggle != null) {
            extraToggle.run();
            final BoolGetter getter = extraGetters.get(item.id);
            if (view instanceof TextCheckCell && getter != null) {
                ((TextCheckCell) view).setChecked(getter.get());
            } else if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            if (PengramConfig.isVibrationEnabled() && view != null) {
                try {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Throwable ignore) {
                }
            }
            return;
        }
        if (item.id >= BTN_COLLAPSE_BASE && item.id < BTN_COLLAPSE_BASE + 100) {
            final int group = item.id - BTN_COLLAPSE_BASE;
            PengramConfig.setBool(expandedKey(group), !expanded(group));
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
            if (item.id == boolId(PengramConfig.KEY_ZALGO)) {
                // чистим уже загруженные имена и названия, иначе эффект был бы виден только после перезапуска
                getMessagesController().pengramApplyZalgoFilter();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
                getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                // описание самой настройки тоже подчиняется фильтру: включено — ровный текст,
                // выключено — слово «Zalgo» снова написано зальго-символами
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
            }
            if (item.id == boolId(PengramConfig.KEY_HIDE_SHARE_PHONE_OPTION) || item.id == boolId(PengramConfig.KEY_ANTICRASH_JOURNAL) || item.id == boolId(PengramConfig.KEY_DIALOG_SENDER_AVATARS)) {
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
                if (item.id == boolId(PengramConfig.KEY_DIALOG_SENDER_AVATARS)) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
                }
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
        if (item.id >= BTN_QUICK_ACTION_BASE && item.id < BTN_QUICK_ACTION_BASE + PengramConfig.QUICK_ACTION_LIMIT) {
            editQuickAction(item.id - BTN_QUICK_ACTION_BASE, false);
            return;
        }
        switch (item.id) {
            case BTN_INPUT_ANIMATION: {
                final CharSequence[] options = new CharSequence[PengramConfig.INPUT_ANIM_COUNT];
                final int[] names = {R.string.PengramInputAnimNone, R.string.PengramInputAnimFade, R.string.PengramInputAnimPop,
                        R.string.PengramInputAnimSlide, R.string.PengramInputAnimRise, R.string.PengramInputAnimBounce, R.string.PengramInputAnimShake};
                for (int i = 0; i < options.length; i++) options[i] = getString(names[i]);
                showChoicePicker(getString(R.string.PengramInputAnimation), options, PengramConfig.getInputAnimation(), value -> {
                    PengramConfig.setInputAnimation(value);
                    if (typingPreview != null) typingPreview.update();
                });
                return;
            }
            case BTN_INPUT_ANIMATION_SPEED: {
                final CharSequence[] options = {getString(R.string.PengramInputSpeedFast), getString(R.string.PengramInputSpeedNormal), getString(R.string.PengramInputSpeedSmooth)};
                showChoicePicker(getString(R.string.PengramInputAnimationSpeed), options, PengramConfig.getInputAnimationSpeed(), value -> {
                    PengramConfig.setInputAnimationSpeed(value);
                    if (typingPreview != null) typingPreview.update();
                });
                return;
            }
            case BTN_INPUT_ANIMATION_INTENSITY: {
                final CharSequence[] options = {getString(R.string.PengramInputIntensitySoft), getString(R.string.PengramInputIntensityMedium), getString(R.string.PengramInputIntensityStrong)};
                showChoicePicker(getString(R.string.PengramInputAnimationIntensity), options, PengramConfig.getInputAnimationIntensity() - 1, value -> {
                    PengramConfig.setInputAnimationIntensity(value + 1);
                    if (typingPreview != null) typingPreview.update();
                });
                return;
            }
            case BTN_QUICK_ADD: {
                final int index = PengramConfig.getQuickActionCount();
                if (index < PengramConfig.QUICK_ACTION_LIMIT) {
                    // Не создаём пустую кнопку заранее: отмена любого диалога оставляет список неизменным.
                    editQuickAction(index, true);
                }
                return;
            }
            case BTN_SENDER_AVATAR_POSITION: {
                final CharSequence[] options = new CharSequence[]{
                        getString(R.string.PengramSenderAvatarInline),
                        getString(R.string.PengramSenderAvatarBottom),
                        getString(R.string.PengramSenderAvatarTop),
                        getString(R.string.PengramSenderAvatarBeforeName),
                        getString(R.string.PengramSenderAvatarAfterName)
                };
                showChoicePicker(getString(R.string.PengramSenderAvatarPosition), options,
                        PengramConfig.getDialogSenderAvatarPosition(), value -> {
                            PengramConfig.setDialogSenderAvatarPosition(value);
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
                        });
                return;
            }
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
            case BTN_REG_STYLE_PICK: {
                final CharSequence[] options = new CharSequence[]{
                        getString(R.string.PengramRegStyleOff),
                        getString(R.string.PengramRegStyleDate),
                        getString(R.string.PengramRegStyleDateAge),
                        getString(R.string.PengramRegStyleAge),
                        getString(R.string.PengramRegStyleExact)
                };
                showChoicePicker(getString(R.string.PengramRegStyle), options, PengramConfig.getRegDateStyle(), value -> {
                    PengramConfig.setRegDateStyle(value);
                });
                return;
            }
            case BTN_REG_PLACE: {
                final CharSequence[] options = new CharSequence[]{
                        getString(R.string.PengramRegPlaceRow),
                        getString(R.string.PengramRegPlaceIcon),
                        getString(R.string.PengramRegPlaceBoth),
                        getString(R.string.PengramRegPlaceSubtitle)
                };
                showChoicePicker(getString(R.string.PengramRegPlace), options, PengramConfig.getRegDatePlace(), value -> {
                    PengramConfig.setRegDatePlace(value);
                });
                return;
            }
            case BTN_REG_ICON: {
                final CharSequence[] options = new CharSequence[]{
                        getString(R.string.PengramRegIconCalendar),
                        getString(R.string.PengramRegIconClock),
                        getString(R.string.PengramRegIconCake),
                        getString(R.string.PengramRegIconStar),
                        getString(R.string.PengramRegIconInfo),
                        getString(R.string.PengramRegIconPenguin),
                        getString(R.string.PengramRegIconNone)
                };
                showChoicePicker(getString(R.string.PengramRegIcon), options, PengramConfig.getRegDateIcon(), value -> {
                    PengramConfig.setRegDateIcon(value);
                });
                return;
            }
            case BTN_QUICK_TILES:
                showQuickTilesPicker();
                return;
            case BTN_MONET_STRENGTH:
                showMonetStrengthPicker();
                return;
            case BTN_TITLE_MODE: {
                final CharSequence[] options = new CharSequence[]{
                        getString(R.string.PengramTitleModeDefault),
                        "Pengram",
                        getString(R.string.PengramTitleChats),
                        getString(R.string.PengramTitleModeName),
                        getString(R.string.PengramTitleModeUsername),
                        getString(R.string.PengramTitleModeCustom)
                };
                showChoicePicker(getString(R.string.PengramTitleText), options, PengramConfig.getTitleMode(), value -> {
                    PengramConfig.setTitleMode(value);
                    if (value == PengramConfig.TITLE_MODE_CUSTOM && PengramConfig.getTitleCustom().isEmpty()) {
                        AndroidUtilities.runOnUIThread(this::showTitleCustomDialog, 220);
                    }
                });
                return;
            }
            case BTN_TITLE_CUSTOM:
                showTitleCustomDialog();
                return;
            case BTN_TABBAR_SIZE: {
                final int[] sizes = new int[]{80, 90, 100, 110, 120, 130};
                final CharSequence[] options = new CharSequence[sizes.length];
                int selected = 2;
                for (int a = 0; a < sizes.length; ++a) {
                    options[a] = sizes[a] + "%" + (sizes[a] == 100 ? " \u2014 " + getString(R.string.PengramTabBarDefault) : "");
                    if (sizes[a] == PengramConfig.getTabBarSize()) {
                        selected = a;
                    }
                }
                showChoicePicker(getString(R.string.PengramTabBarSize), options, selected, value -> {
                    PengramConfig.setTabBarSize(sizes[value]);
                    applyTabsSizeNow();
                });
                return;
            }
            case BTN_MENU_ITEMS:
                presentFragment(new PengramMenuItemsActivity(PengramMenuItemsActivity.MODE_MENU));
                return;
            case BTN_FONT_SIZE: {
                final int[] sizes = new int[]{12, 13, 14, 15, 16, 17, 18, 20, 22, 24, 26, 28, 30};
                final CharSequence[] options = new CharSequence[sizes.length];
                int selected = 4;
                for (int a = 0; a < sizes.length; ++a) {
                    options[a] = String.valueOf(sizes[a]);
                    if (sizes[a] == SharedConfig.fontSize) {
                        selected = a;
                    }
                }
                showChoicePicker(getString(R.string.TextSizeHeader), options, selected, value -> applyFontSize(sizes[value]));
                return;
            }
            case BTN_BUBBLE_RADIUS: {
                final int[] radii = new int[]{0, 2, 4, 6, 8, 10, 12, 14, 15, 16, 17};
                final CharSequence[] options = new CharSequence[radii.length];
                int selected = radii.length - 1;
                for (int a = 0; a < radii.length; ++a) {
                    options[a] = String.valueOf(radii[a]);
                    if (radii[a] == SharedConfig.bubbleRadius) {
                        selected = a;
                    }
                }
                showChoicePicker(getString(R.string.BubbleRadius), options, selected, value -> applyBubbleRadius(radii[value]));
                return;
            }
            case BTN_SWIPE_ACTION: {
                final int[] actions = new int[]{
                        SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE,
                        SwipeGestureSettingsView.SWIPE_GESTURE_READ,
                        SwipeGestureSettingsView.SWIPE_GESTURE_PIN,
                        SwipeGestureSettingsView.SWIPE_GESTURE_MUTE,
                        SwipeGestureSettingsView.SWIPE_GESTURE_DELETE,
                        SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS
                };
                final CharSequence[] options = new CharSequence[actions.length];
                int selected = 0;
                final int current = SharedConfig.getChatSwipeAction(currentAccount);
                for (int a = 0; a < actions.length; ++a) {
                    options[a] = swipeActionName(actions[a]);
                    if (actions[a] == current) {
                        selected = a;
                    }
                }
                showChoicePicker(getString(R.string.PengramSwipeAction), options, selected, value -> {
                    SharedConfig.updateChatListSwipeSetting(actions[value]);
                });
                return;
            }
            case BTN_SETTINGS_ITEMS:
                presentFragment(new PengramMenuItemsActivity(PengramMenuItemsActivity.MODE_SETTINGS));
                return;
            case BTN_CHAT_ITEMS:
                presentFragment(new PengramMenuItemsActivity(PengramMenuItemsActivity.MODE_CHAT));
                return;
            case BTN_PENGUIN_SKIN: {
                final CharSequence[] options = new CharSequence[PengramConfig.SKIN_COUNT];
                for (int a = 0; a < options.length; ++a) {
                    options[a] = getString(PengramConfig.getPenguinSkinName(a));
                }
                showChoicePicker(getString(R.string.PengramPenguinSkin), options, PengramConfig.getPenguinSkin(), value -> {
                    PengramConfig.setPenguinSkin(value);
                    if (headerView != null) {
                        headerView.applySkin();
                    }
                });
                return;
            }
            case BTN_MEDIA_LIMIT: {
                int selected = 0;
                final CharSequence[] options = new CharSequence[MEDIA_LIMITS.length];
                for (int a = 0; a < MEDIA_LIMITS.length; ++a) {
                    options[a] = mediaLimitName(MEDIA_LIMITS[a]);
                    if (MEDIA_LIMITS[a] == PengramConfig.getMediaMaxSizeMb()) {
                        selected = a;
                    }
                }
                showChoicePicker(getString(R.string.PengramMediaLimitHeader), options, selected, value -> {
                    PengramConfig.setMediaMaxSizeMb(MEDIA_LIMITS[value]);
                });
                return;
            }
            case BTN_PROFILE_HISTORY:
                presentFragment(new PengramProfileHistorySettingsActivity());
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
            case BTN_SECTION_CHAT_ACTIONS:
                presentFragment(new PengramSettingsActivity(SECTION_CHAT_ACTIONS));
                return;
            case BTN_SECTION_CHAT_MESSAGES:
                presentFragment(new PengramSettingsActivity(SECTION_CHAT_MESSAGES));
                return;
            case BTN_SECTION_CHAT_INTERFACE:
                presentFragment(new PengramSettingsActivity(SECTION_CHAT_INTERFACE));
                return;
            case BTN_SECTION_CHAT_MENUS:
                presentFragment(new PengramSettingsActivity(SECTION_CHAT_MENUS));
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
            case BTN_SECTION_PENGUIN:
                presentFragment(new PengramSettingsActivity(SECTION_PENGUIN));
                return;
            case BTN_SECTION_ABOUT:
                presentFragment(new PengramSettingsActivity(SECTION_ABOUT));
                return;
            case BTN_ABOUT_COPY:
                AndroidUtilities.addToClipboard(org.telegram.messenger.PengramVersion.report());
                BulletinFactory.of(this).createCopyBulletin(getString(R.string.PengramAboutCopied)).show();
                return;
            case BTN_RESET_SECTION: {
                final String[] keys = sectionKeys(section);
                if (keys == null || getParentActivity() == null) {
                    return;
                }
                final AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(getString(R.string.PengramResetSection));
                b.setMessage(LocaleController.formatString(R.string.PengramResetSectionAsk, sectionTitle(section)));
                b.setPositiveButton(getString(R.string.PengramResetButton), (d, w) -> {
                    PengramConfig.resetKeys(keys);
                    rebuildAfterReset();
                });
                b.setNegativeButton(getString(R.string.Cancel), null);
                showDialog(b.create());
                return;
            }
            case BTN_RESET_ALL: {
                if (getParentActivity() == null) {
                    return;
                }
                final AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(getString(R.string.PengramResetAll));
                b.setMessage(getString(R.string.PengramResetAllAsk));
                b.setPositiveButton(getString(R.string.PengramResetButton), (d, w) -> {
                    PengramConfig.resetAll();
                    rebuildAfterReset();
                });
                b.setNegativeButton(getString(R.string.Cancel), null);
                showDialog(b.create());
                return;
            }
            case BTN_HIST_MAX: {
                final int[] limits = new int[]{2000, 10000, 20000, 50000, 0};
                final CharSequence[] options = new CharSequence[limits.length];
                int selected = 0;
                for (int a = 0; a < limits.length; ++a) {
                    options[a] = historyMaxName(limits[a]);
                    if (limits[a] == PengramConfig.getHistoryMaxEntries()) {
                        selected = a;
                    }
                }
                showChoicePicker(getString(R.string.PengramHistoryMax), options, selected, value -> {
                    PengramConfig.setHistoryMaxEntries(limits[value]);
                    PengramHistory.enforceEntryLimit();
                    if (listView != null && listView.adapter != null) listView.adapter.update(true);
                });
                return;
            }
            case BTN_SECTION_PLAYER:
            case BTN_PLAYER_STYLE:
                presentFragment(new PengramPlayerStyleActivity());
                return;
            case BTN_CHAT_LOOK:
            case BTN_AVATAR_POS:
                presentFragment(new PengramChatLookActivity());
                return;
            case BTN_CONSTRUCTOR:
                presentFragment(new PengramConstructorActivity());
                return;
            case BTN_BYPASS:
                presentFragment(new PengramBypassActivity());
                return;
            case BTN_ANTICRASH_STATS:
                PengramAntiCrash.resetStats();
                listView.adapter.update(true);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                        getString(R.string.PengramAntiCrashStatsReset)).show();
                return;
            case BTN_CRASH_AUTOCOPY:
                org.telegram.messenger.PengramCrashReport.setCopyEnabled(
                        !org.telegram.messenger.PengramCrashReport.isCopyEnabled());
                listView.adapter.update(true);
                return;
            case BTN_CRASH_REPORTS:
                org.telegram.ui.Components.PengramCrashDialogs.showJournal(this);
                return;
            case BTN_ANTICRASH_LOG:
                showAntiCrashLog();
                return;
            case BTN_DELETE_EFFECT:
                presentFragment(new PengramDeleteEffectActivity());
                return;
            case BTN_LYRICS_SOURCE: {
                final CharSequence[] options = new CharSequence[PengramConfig.LYRICS_SOURCE_COUNT];
                for (int a = 0; a < options.length; ++a) {
                    options[a] = getString(PengramConfig.getLyricsSourceName(a));
                }
                showChoicePicker(getString(R.string.PengramLyricsSourceHeader), options,
                        PengramConfig.getLyricsSourceRaw(), value -> {
                            PengramConfig.setLyricsSource(value);
                            PengramLyrics.clearAll();
                            listView.adapter.update(true);
                        });
                return;
            }
            case BTN_HEADER_LYRICS_ANIM: {
                final CharSequence[] options = new CharSequence[PengramConfig.LYRICS_ANIM_COUNT + 1];
                options[0] = getString(R.string.PengramHeaderLyricsAnimSame);
                for (int a = 0; a < PengramConfig.LYRICS_ANIM_COUNT; ++a) {
                    options[a + 1] = getString(PengramConfig.getLyricsAnimName(a));
                }
                showChoicePicker(getString(R.string.PengramHeaderLyricsAnim), options, PengramConfig.getHeaderLyricsAnim() + 1,
                        value -> PengramConfig.setHeaderLyricsAnim(value - 1));
                return;
            }
            case BTN_PLAYER_BG: {
                final CharSequence[] options = new CharSequence[4];
                for (int a = 0; a < options.length; ++a) {
                    options[a] = getString(PengramConfig.getPlayerBgName(a));
                }
                showChoicePicker(getString(R.string.PengramPlayerBg), options, PengramConfig.getPlayerBg(),
                        value -> PengramConfig.setPlayerBg(value));
                return;
            }
            case BTN_COVER_SHAPE: {
                final CharSequence[] options = new CharSequence[3];
                for (int a = 0; a < options.length; ++a) {
                    options[a] = getString(PengramConfig.getCoverShapeName(a));
                }
                showChoicePicker(getString(R.string.PengramCoverShape), options, PengramConfig.getCoverShape(),
                        value -> PengramConfig.setCoverShape(value));
                return;
            }
            case BTN_LYRICS_ANIM: {
                final CharSequence[] options = new CharSequence[PengramConfig.LYRICS_ANIM_COUNT];
                for (int a = 0; a < options.length; ++a) {
                    options[a] = getString(PengramConfig.getLyricsAnimName(a));
                }
                showChoicePicker(getString(R.string.PengramLyricsAnim), options, PengramConfig.getLyricsAnim(),
                        value -> PengramConfig.setLyricsAnim(value));
                return;
            }
            case BTN_LYRICS_ALIGN: {
                final CharSequence[] options = new CharSequence[3];
                for (int a = 0; a < options.length; ++a) {
                    options[a] = getString(PengramConfig.getLyricsAlignName(a));
                }
                showChoicePicker(getString(R.string.PengramLyricsAlign), options, PengramConfig.getLyricsAlign(),
                        value -> PengramConfig.setLyricsAlign(value));
                return;
            }
            case BTN_TRACK_FORWARD_MODE: {
                final CharSequence[] options = new CharSequence[]{
                        org.telegram.ui.Components.PengramTrackForward.modeName(PengramConfig.TRACK_FORWARD_ASK),
                        org.telegram.ui.Components.PengramTrackForward.modeName(PengramConfig.TRACK_FORWARD_AS_ME),
                        org.telegram.ui.Components.PengramTrackForward.modeName(PengramConfig.TRACK_FORWARD_WITH_AUTHOR)
                };
                showChoicePicker(getString(R.string.PengramTrackForwardMode), options, PengramConfig.getTrackForwardMode(),
                        value -> PengramConfig.setTrackForwardMode(value));
                return;
            }
            case BTN_LYRICS_CLEAR: {
                org.telegram.messenger.PengramLyrics.clearAll();
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                return;
            }
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
            case BTN_MEDIA_TIME_DEFAULT:
            case BTN_MEDIA_TIME_STICKERS:
            case BTN_MEDIA_TIME_MEDIA:
                PengramConfig.setMediaTimeMode(item.id - BTN_MEDIA_TIME_DEFAULT);
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
                PengramConfig.setBool("uiGhostExpanded", !isGhostExpanded());
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
            case BTN_HIST_OUTGOING:
                PengramConfig.toggleSaveOutgoing();
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(PengramConfig.isSavingOutgoing());
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
            case BTN_FONT_PICK:
                showFontPicker();
                break;
            case BTN_SHARE_PHONE_MODE:
                showSharePhonePicker();
                break;
            case BTN_PLAYER_ACCENT:
                showPlayerAccentPicker();
                break;
            case BTN_EMPTY_COVER: {
                final CharSequence[] options = new CharSequence[]{
                        getString(R.string.PengramEmptyCoverPenguin),
                        getString(R.string.PengramEmptyCoverHide),
                        getString(R.string.PengramEmptyCoverPlain)
                };
                showChoicePicker(getString(R.string.PengramEmptyCover), options,
                        PengramConfig.getEmptyCoverMode(), value -> {
                            PengramConfig.setEmptyCoverMode(value);
                            if (listView != null && listView.adapter != null) {
                                listView.adapter.update(true);
                            }
                        });
                break;
            }
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

    /** поделиться ссылкой через выбор чата внутри Telegram */
    private void shareLink(String url) {
        if (getContext() == null) {
            return;
        }
        try {
            showDialog(new org.telegram.ui.Components.ShareAlert(getContext(), null, url, false, url, false));
        } catch (Exception e) {
            FileLog.e(e);
        }
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


    // ------------------------------------------------------------ AI-сервисы

    /** что показать в строке раздела на главном экране: активный сервис или «не настроено» */
    private CharSequence aiSectionValue() {
        final org.telegram.messenger.PengramAI.Service service = org.telegram.messenger.PengramAI.active();
        return service == null ? getString(R.string.PengramAIEmptyValue) : service.title;
    }

    /** Экран «Нейросети»: свои сервисы вместо встроенных, роли и вид ответа */
    private void fillAI(ArrayList<UItem> items) {
        final java.util.List<org.telegram.messenger.PengramAI.Service> services = org.telegram.messenger.PengramAI.services();
        final String activeId = org.telegram.messenger.PengramAI.activeId();

        items.add(UItem.asHeader(getString(R.string.PengramAIServices)));
        if (services.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.PengramAIServicesEmpty)));
        } else {
            for (int i = 0; i < services.size(); i++) {
                final org.telegram.messenger.PengramAI.Service service = services.get(i);
                final boolean active = TextUtils.equals(service.id, activeId)
                        || (activeId == null && i == 0);
                items.add(UItem.asRadio(BTN_AI_SERVICE_BASE + i, service.title, service.summary())
                        .setChecked(active));
            }
        }
        items.add(UItem.asButton(BTN_AI_ADD_SERVICE, R.drawable.msg_add, getString(R.string.PengramAIAddService)));
        items.add(UItem.asShadow(getString(R.string.PengramAIServicesInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramAIRoles)));
        final java.util.List<org.telegram.messenger.PengramAIRoles.Role> roles = org.telegram.messenger.PengramAIRoles.all();
        final String activeRole = org.telegram.messenger.PengramAIRoles.activeId();
        for (int i = 0; i < roles.size(); i++) {
            final org.telegram.messenger.PengramAIRoles.Role role = roles.get(i);
            items.add(UItem.asRadio(BTN_AI_ROLE_BASE + i, role.title)
                    .setChecked(TextUtils.equals(role.id, activeRole)));
        }
        items.add(UItem.asButton(BTN_AI_ADD_ROLE, R.drawable.msg_add, getString(R.string.PengramAIAddRole)));
        items.add(UItem.asShadow(getString(R.string.PengramAIRolesInfo)));

        items.add(UItem.asHeader(getString(R.string.PengramAIAnswer)));
        items.add(tgCheckInfo(BTN_AI_STREAM, getString(R.string.PengramAIStream), getString(R.string.PengramAIStreamInfo),
                org.telegram.messenger.PengramAI::isStream,
                () -> org.telegram.messenger.PengramAI.toggle(org.telegram.messenger.PengramAI.KEY_STREAM, true)));
        items.add(tgCheckInfo(BTN_AI_ONLY_ANSWER, getString(R.string.PengramAIOnlyAnswer), getString(R.string.PengramAIOnlyAnswerInfo),
                org.telegram.messenger.PengramAI::isOnlyAnswer,
                () -> org.telegram.messenger.PengramAI.toggle(org.telegram.messenger.PengramAI.KEY_ONLY_ANSWER, false)));
        items.add(tgCheckInfo(BTN_AI_AS_QUOTE, getString(R.string.PengramAIAsQuote), getString(R.string.PengramAIAsQuoteInfo),
                org.telegram.messenger.PengramAI::isAsQuote,
                () -> org.telegram.messenger.PengramAI.toggle(org.telegram.messenger.PengramAI.KEY_AS_QUOTE, false)));
        items.add(tgCheckInfo(BTN_AI_HISTORY, getString(R.string.PengramAIHistory), getString(R.string.PengramAIHistoryInfo),
                org.telegram.messenger.PengramAI::isHistory,
                () -> org.telegram.messenger.PengramAI.toggle(org.telegram.messenger.PengramAI.KEY_HISTORY, true)));
        if (org.telegram.messenger.PengramAI.isHistory()) {
            items.add(UItem.asButton(BTN_AI_DEPTH, getString(R.string.PengramAIDepth),
                    String.valueOf(org.telegram.messenger.PengramAI.historyDepth())));
        }
        items.add(UItem.asShadow(getString(R.string.PengramAIAnswerInfo)));

        items.add(UItem.asButton(BTN_AI_RESET, R.drawable.msg_reset, getString(R.string.PengramAIReset)).red());
        items.add(UItem.asShadow(getString(R.string.PengramAIResetInfo)));
    }

    /** список заготовок: выбрал шлюз — остались только ключ и модель */
    private void showAIPresets() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final CharSequence[] titles = new CharSequence[org.telegram.messenger.PengramAI.PRESETS.length + 1];
        for (int i = 0; i < org.telegram.messenger.PengramAI.PRESETS.length; i++) {
            titles[i] = org.telegram.messenger.PengramAI.PRESETS[i].title;
        }
        titles[titles.length - 1] = getString(R.string.PengramAIManual);
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PengramAIAddService));
        builder.setItems(titles, (d, which) -> {
            if (which >= org.telegram.messenger.PengramAI.PRESETS.length) {
                showAIServiceDialog(null);
                return;
            }
            final org.telegram.messenger.PengramAI.Preset preset = org.telegram.messenger.PengramAI.PRESETS[which];
            showAIServiceDialog(new org.telegram.messenger.PengramAI.Service(null, preset.title,
                    preset.baseUrl, preset.model, ""));
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private EditTextBoldCursor aiField(Context context, CharSequence hint, String value, LinearLayout parent) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        field.setBackgroundDrawable(null);
        field.setPadding(0, dp(8), 0, dp(8));
        parent.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return field;
    }

    /** добавление и правка сервиса: адрес, модель, ключ */
    private void showAIServiceDialog(org.telegram.messenger.PengramAI.Service source) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final boolean editing = source != null && !TextUtils.isEmpty(source.id);
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(4), dp(22), dp(4));
        final EditTextBoldCursor title = aiField(context, getString(R.string.PengramAIFieldTitle),
                source == null ? "" : source.title, layout);
        final EditTextBoldCursor url = aiField(context, getString(R.string.PengramAIFieldUrl),
                source == null ? "https://" : source.baseUrl, layout);
        final EditTextBoldCursor model = aiField(context, getString(R.string.PengramAIFieldModel),
                source == null ? "" : source.model, layout);
        final EditTextBoldCursor key = aiField(context, getString(R.string.PengramAIFieldKey),
                source == null ? "" : source.apiKey, layout);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(editing ? R.string.PengramAIEditService : R.string.PengramAIAddService));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> {
            final org.telegram.messenger.PengramAI.Service service = new org.telegram.messenger.PengramAI.Service(
                    source == null ? null : source.id,
                    TextUtils.isEmpty(title.getText()) ? getString(R.string.PengramSectionAI).toString() : title.getText().toString().trim(),
                    url.getText().toString().trim(),
                    model.getText().toString().trim(),
                    key.getText().toString().trim());
            if (!service.isReady()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.PengramAIFieldsNeeded)).show();
                return;
            }
            org.telegram.messenger.PengramAI.put(service);
            org.telegram.messenger.PengramAI.setActive(service.id);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            checkAIService(service);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        if (editing) {
            builder.setNeutralButton(getString(R.string.Delete), (d, w) -> {
                org.telegram.messenger.PengramAI.remove(source.id);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        }
        showDialog(builder.create());
    }

    /** живая проверка: сервис отвечает — значит адрес, модель и ключ сошлись */
    private void checkAIService(org.telegram.messenger.PengramAI.Service service) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.PengramAIChecking)).show();
        org.telegram.messenger.PengramAIClient.check(service, new org.telegram.messenger.PengramAIClient.Listener() {
            @Override
            public void onChunk(String text) {
            }

            @Override
            public void onDone(String text) {
                BulletinFactory.of(PengramSettingsActivity.this)
                        .createSimpleBulletin(R.raw.done, getString(R.string.PengramAICheckOk)).show();
            }

            @Override
            public void onError(String message) {
                BulletinFactory.of(PengramSettingsActivity.this)
                        .createSimpleBulletin(R.raw.error, message).show();
            }
        });
    }

    /** своя роль: название и то, что модель прочитает перед работой */
    private void showAIRoleDialog(org.telegram.messenger.PengramAIRoles.Role source) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(4), dp(22), dp(4));
        final EditTextBoldCursor title = aiField(context, getString(R.string.PengramAIFieldRoleTitle),
                source == null ? "" : source.title, layout);
        final EditTextBoldCursor prompt = aiField(context, getString(R.string.PengramAIFieldPrompt),
                source == null ? "" : source.prompt, layout);
        prompt.setSingleLine(false);
        prompt.setMaxLines(6);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PengramAIAddRole));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> {
            final String name = title.getText().toString().trim();
            final String text = prompt.getText().toString().trim();
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(text)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.PengramAIFieldsNeeded)).show();
                return;
            }
            final org.telegram.messenger.PengramAIRoles.Role role =
                    new org.telegram.messenger.PengramAIRoles.Role(source == null ? null : source.id, name, text, false);
            org.telegram.messenger.PengramAIRoles.put(role);
            org.telegram.messenger.PengramAIRoles.setActive(role.id);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        if (source != null && !source.builtin) {
            builder.setNeutralButton(getString(R.string.Delete), (d, w) -> {
                org.telegram.messenger.PengramAIRoles.remove(source.id);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        }
        showDialog(builder.create());
    }

    /** подпись режима «второе имя»: выключено / нажатие / свайп / оба */
    private CharSequence originalNameModeName() {
        switch (org.telegram.messenger.PengramOriginalName.mode()) {
            case org.telegram.messenger.PengramOriginalName.MODE_TAP:
                return getString(R.string.PengramOriginalNameTap);
            case org.telegram.messenger.PengramOriginalName.MODE_SWIPE:
                return getString(R.string.PengramOriginalNameSwipe);
            case org.telegram.messenger.PengramOriginalName.MODE_BOTH:
                return getString(R.string.PengramOriginalNameBoth);
            default:
                return getString(R.string.PengramOriginalNameOff);
        }
    }

    private void showOriginalNamePicker() {
        showChoicePicker(getString(R.string.PengramOriginalName), new CharSequence[]{
                getString(R.string.PengramOriginalNameOff),
                getString(R.string.PengramOriginalNameTap),
                getString(R.string.PengramOriginalNameSwipe),
                getString(R.string.PengramOriginalNameBoth)
        }, org.telegram.messenger.PengramOriginalName.mode(), value -> {
            org.telegram.messenger.PengramOriginalName.setMode(value);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    /** нажатия в разделе «Нейросети»; true — обработали */
    private boolean onAIClick(UItem item) {
        if (item.id >= BTN_AI_SERVICE_BASE && item.id < BTN_AI_SERVICE_BASE + 100) {
            final java.util.List<org.telegram.messenger.PengramAI.Service> services = org.telegram.messenger.PengramAI.services();
            final int index = item.id - BTN_AI_SERVICE_BASE;
            if (index < services.size()) {
                final org.telegram.messenger.PengramAI.Service service = services.get(index);
                if (TextUtils.equals(service.id, org.telegram.messenger.PengramAI.activeId())) {
                    showAIServiceDialog(service);   // повторное нажатие — правка
                } else {
                    org.telegram.messenger.PengramAI.setActive(service.id);
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            }
            return true;
        }
        if (item.id >= BTN_AI_ROLE_BASE && item.id < BTN_AI_ROLE_BASE + 100) {
            final java.util.List<org.telegram.messenger.PengramAIRoles.Role> roles = org.telegram.messenger.PengramAIRoles.all();
            final int index = item.id - BTN_AI_ROLE_BASE;
            if (index < roles.size()) {
                final org.telegram.messenger.PengramAIRoles.Role role = roles.get(index);
                if (TextUtils.equals(role.id, org.telegram.messenger.PengramAIRoles.activeId()) && !role.builtin) {
                    showAIRoleDialog(role);
                } else {
                    org.telegram.messenger.PengramAIRoles.setActive(role.id);
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            }
            return true;
        }
        switch (item.id) {
            case BTN_AI_ADD_SERVICE:
                showAIPresets();
                return true;
            case BTN_AI_ADD_ROLE:
                showAIRoleDialog(null);
                return true;
            case BTN_AI_DEPTH: {
                showTextDialog(getString(R.string.PengramAIDepth),
                        String.valueOf(org.telegram.messenger.PengramAI.historyDepth()), "10", value -> {
                            try {
                                org.telegram.messenger.PengramAI.setHistoryDepth(Integer.parseInt(value.trim()));
                            } catch (Throwable ignore) {
                            }
                            if (listView != null && listView.adapter != null) {
                                listView.adapter.update(true);
                            }
                        });
                return true;
            }
            case BTN_AI_RESET: {
                final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle(getString(R.string.PengramAIReset));
                builder.setMessage(getString(R.string.PengramAIResetInfo));
                builder.setPositiveButton(getString(R.string.Delete), (d, w) -> {
                    org.telegram.messenger.PengramAI.resetAll();
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
                builder.setNegativeButton(getString(R.string.Cancel), null);
                showDialog(builder.create());
                return true;
            }
            case BTN_SECTION_LYRICS:
                presentFragment(new PengramSettingsActivity(SECTION_LYRICS));
                return true;
            case BTN_SECTION_AI:
                presentFragment(new PengramSettingsActivity(SECTION_AI));
                return true;
            case BTN_ORIGINAL_NAME:
                showOriginalNamePicker();
                return true;
        }
        return false;
    }

    /** Шапка настроек: живой 3D-пингвин, название и короткое описание */
    private class PengramHeaderView extends LinearLayout {

        private PengramPenguinView penguinView;
        private android.widget.ImageView fallbackLogo;
        private TextView bubble;
        /** какой скин реально надет сейчас — чтобы не переодевать пингвина каждую секунду */
        private int appliedSkin = -1;
        private int phraseIndex;
        private long lastPhraseTime;
        private final Runnable stateTicker = new Runnable() {
            @Override
            public void run() {
                syncWithApp();
                AndroidUtilities.runOnUIThread(this, 1500);
            }
        };

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
                penguinView.setSkin(PengramConfig.getPenguinSkin());
                penguinView.setOnTapListener(() -> {
                    nextPhrase(true);
                    try {
                        if (PengramConfig.isVibrationEnabled()) {
                            penguinView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                        }
                    } catch (Exception ignore) {}
                });
                penguinView.setOnSecretListener(() -> {
                    // 10 быстрых тапов — все настройки пингвина
                    try {
                        if (section != SECTION_PENGUIN) {
                            presentFragment(new PengramSettingsActivity(SECTION_PENGUIN));
                        }
                    } catch (Throwable ignore) {}
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

            final int penguinSize = Math.round(132 * PengramConfig.getPenguinSize() / 100f);
            addView(penguinContainer, LayoutHelper.createLinear(penguinSize + 8, penguinSize, Gravity.CENTER_HORIZONTAL));

            // Пузырь с репликой: пингвин рассказывает, что происходит, и подсказывает жесты.
            bubble = new TextView(context);
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            bubble.setGravity(Gravity.CENTER);
            bubble.setMaxLines(2);
            bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bubble.setPadding(dp(12), dp(6), dp(12), dp(7));
            bubble.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            bubble.setBackground(Theme.createRoundRectDrawable(dp(12),
                    Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider())));
            bubble.setOnClickListener(v -> nextPhrase(true));
            addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 2, 24, 0));

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

            // Канал и автор — сразу под шапкой. Раньше они лежали в самом низу списка,
            // куда никто не доскроллит.
            final LinearLayout links = new LinearLayout(context);
            links.setOrientation(HORIZONTAL);
            links.setGravity(Gravity.CENTER_HORIZONTAL);
            links.addView(linkChip(context, R.drawable.msg_channel, getString(R.string.PengramLinkChannel),
                    () -> openLink(LINK_CHANNEL)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 4, 0));
            links.addView(linkChip(context, R.drawable.msg_openprofile, getString(R.string.PengramLinkAuthor),
                    () -> openLink(LINK_AUTHOR)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 4, 0, 0, 0));
            addView(links, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL, 16, 10, 16, 0));
        }

        /** маленькая «таблетка» со значком и подписью */
        private View linkChip(Context context, int icon, CharSequence text, Runnable onClick) {
            final LinearLayout chip = new LinearLayout(context);
            chip.setOrientation(HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(11), 0, dp(13), 0);
            chip.setBackground(Theme.createRoundRectDrawable(dp(16),
                    Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider())));

            final android.widget.ImageView iconView = new android.widget.ImageView(context);
            iconView.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            iconView.setImageResource(icon);
            iconView.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, getResourceProvider()),
                    android.graphics.PorterDuff.Mode.SRC_IN));
            chip.addView(iconView, LayoutHelper.createLinear(17, 17, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

            final TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            label.setTypeface(AndroidUtilities.bold());
            label.setSingleLine(true);
            label.setText(text);
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, getResourceProvider()));
            chip.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            chip.setOnClickListener(v -> {
                AndroidUtilities.vibrateCursor(v);
                onClick.run();
            });
            return chip;
        }

        public void setPaused(boolean paused) {
            if (penguinView != null) {
                penguinView.setPaused(paused);
            }
            if (paused) {
                AndroidUtilities.cancelRunOnUIThread(stateTicker);
            } else if (isAttachedToWindow()) {
                AndroidUtilities.cancelRunOnUIThread(stateTicker);
                AndroidUtilities.runOnUIThread(stateTicker, 300);
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            syncWithApp();
            nextPhrase(false);
            AndroidUtilities.cancelRunOnUIThread(stateTicker);
            AndroidUtilities.runOnUIThread(stateTicker, 1500);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            AndroidUtilities.cancelRunOnUIThread(stateTicker);
        }

        /** выполнить трюк по кнопке из настроек */
        public void doAction(int id) {
            if (penguinView == null) {
                return;
            }
            penguinView.setSleeping(false);
            if (id == BTN_PENGUIN_FLIP) {
                penguinView.doFlip();
            } else if (id == BTN_PENGUIN_DANCE) {
                penguinView.doDance();
                penguinView.doWave();
            } else {
                penguinView.resetRotation();
            }
        }

        /** переодеть пингвина после выбора скина */
        public void applySkin() {
            appliedSkin = -1;
            syncWithApp();
        }

        /** играет ли сейчас музыка (именно играет, а не стоит на паузе) */
        private MessageObject playingMusic() {
            try {
                final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                if (playing != null && playing.isMusic() && !MediaController.getInstance().isMessagePaused()) {
                    return playing;
                }
            } catch (Throwable ignore) {
            }
            return null;
        }

        /**
         * Пингвин живёт вместе с приложением: спит в режиме призрака, танцует под музыку
         * и переодевается по ситуации. Состояние проверяется раз в полторы секунды —
         * это дешевле и надёжнее, чем подписки на десяток уведомлений.
         */
        private void syncWithApp() {
            if (penguinView == null) {
                return;
            }
            final MessageObject music = playingMusic();
            final boolean sleep = PengramConfig.isPenguinSleepGhost() && PengramConfig.ghostMode && music == null;
            penguinView.setSleeping(sleep);
            penguinView.setDanceLoop(!sleep && music != null && PengramConfig.isPenguinDanceMusic());

            int skin = PengramConfig.getPenguinSkin();
            if (PengramConfig.isPenguinAutoSkin()) {
                if (music != null) {
                    skin = PengramConfig.SKIN_HEADPHONES;
                } else if (java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) == java.util.Calendar.DECEMBER
                        && skin == PengramConfig.SKIN_NONE) {
                    skin = PengramConfig.SKIN_SANTA;
                }
            }
            if (skin != appliedSkin) {
                appliedSkin = skin;
                penguinView.setSkin(skin);
            }

            if (bubble != null) {
                final int visibility = PengramConfig.isPenguinTips() ? VISIBLE : GONE;
                if (bubble.getVisibility() != visibility) {
                    bubble.setVisibility(visibility);
                }
                if (visibility == VISIBLE && System.currentTimeMillis() - lastPhraseTime > 6500) {
                    nextPhrase(false);
                }
            }
        }

        /** следующая реплика; forced — пользователь сам ткнул в пингвина */
        private void nextPhrase(boolean forced) {
            if (bubble == null || !PengramConfig.isPenguinTips()) {
                return;
            }
            final ArrayList<CharSequence> phrases = new ArrayList<>();
            if (penguinView != null && penguinView.isSleeping()) {
                phrases.add(getString(R.string.PengramPenguinSleeping));
            } else {
                final MessageObject music = playingMusic();
                if (music != null) {
                    phrases.add(LocaleController.formatString(R.string.PengramPenguinNowPlaying, music.getMusicTitle()));
                }
                phrases.add(getString(R.string.PengramPenguinHi));
                phrases.add(getString(PengramConfig.ghostMode ? R.string.PengramPenguinGhostOn : R.string.PengramPenguinGhostOff));
                final int lyrics = org.telegram.messenger.PengramLyrics.savedCount();
                if (lyrics > 0) {
                    phrases.add(LocaleController.formatString(R.string.PengramPenguinLyricsSaved, lyrics));
                }
                phrases.add(getString(R.string.PengramPenguinTipSwipe));
                phrases.add(getString(R.string.PengramPenguinTipFlip));
                phrases.add(getString(R.string.PengramPenguinTipHold));
            }
            if (phrases.isEmpty()) {
                return;
            }
            phraseIndex = (phraseIndex + 1) % phrases.size();
            lastPhraseTime = System.currentTimeMillis();
            final CharSequence text = phrases.get(phraseIndex);
            if (text.equals(bubble.getText())) {
                return;
            }
            bubble.setText(text);
            if (forced) {
                bubble.setAlpha(0.4f);
            }
            bubble.animate().cancel();
            bubble.setAlpha(0.35f);
            bubble.animate().alpha(1f).setDuration(180).start();
        }
    }

    /** версия приложения — показывается под пингвином */
    private static String getAppVersion() {
        // В шапке — своя версия форка: именно её называют в баг-репортах.
        return "Pengram " + org.telegram.messenger.PengramVersion.shortLine();
    }

    @SuppressWarnings("unused")
    private static String getTelegramVersion() {
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
        private long startTime = android.os.SystemClock.elapsedRealtime();
        /** градиент карточки пересобирается только при смене размера или цвета */
        private LinearGradient cardShader;
        private int shaderWidth;
        private int shaderHeight;
        private int shaderAccent;

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
            startTime = android.os.SystemClock.elapsedRealtime();
            cardShader = null;
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
            if (cardShader == null || shaderWidth != width || shaderHeight != height || shaderAccent != accent) {
                cardShader = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{
                                Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()), Theme.multAlpha(accent, 0.14f)),
                                Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()), Theme.multAlpha(accent, 0.04f))
                        }, null, Shader.TileMode.CLAMP);
                shaderWidth = width;
                shaderHeight = height;
                shaderAccent = accent;
                cardPaint.setShader(cardShader);
            }
            canvas.drawRoundRect(rect, dp(16), dp(16), cardPaint);

            titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            subtitlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
            canvas.drawText(title, rect.left + dp(16), rect.top + dp(26), titlePaint);
            canvas.drawText(subtitle, rect.left + dp(16), rect.top + dp(46), subtitlePaint);

            // волна: чем выше питч — тем чаще и «звонче» столбики
            final float time = (android.os.SystemClock.elapsedRealtime() - startTime) / 1000f;
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
            // крутим волну только когда эффект включён и экран виден —
            // иначе вьюшка жгла бы батарею вхолостую
            if (enabled && isAttachedToWindow() && getVisibility() == VISIBLE) {
                postInvalidateOnAnimation();
            }
        }
    }

    private class ProfilePreviewView extends LinearLayout {

        private final BackupImageView avatarImage;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView nameText;
        private final TextView statusText;
        private final TextDetailCell usernameCell;
        private final TextDetailCell idCell;
        private final TextDetailCell regCell;

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
            header.addView(nameText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 98, 26, 20, 0));

            statusText = new TextView(context);
            statusText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            statusText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
            statusText.setSingleLine(true);
            header.addView(statusText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 98, 50, 20, 0));

            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 92));

            usernameCell = new TextDetailCell(context, getResourceProvider(), false, true);
            addView(usernameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            idCell = new TextDetailCell(context, getResourceProvider(), false, true);
            addView(idCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            regCell = new TextDetailCell(context, getResourceProvider(), false, true);
            addView(regCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        /** превью — картинка, а не кнопка: нажатия не ловим, чтобы ничего не подсвечивалось */
        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            return true;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            return false;
        }

        public void update() {
            final TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
            final long id = user != null ? user.id : 1234567890L;
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

            final long estimate = org.telegram.messenger.PengramRegDate.estimate(id);
            final CharSequence regText = regSampleText(estimate);
            final boolean regVisible = PengramConfig.isRegDateVisible() && !TextUtils.isEmpty(regText);

            CharSequence status = getString(R.string.Online);
            if (regVisible && PengramConfig.isRegDateInSubtitle()) {
                status = status + " \u2022 " + regText;
            }
            statusText.setText(status);

            final int format = PengramConfig.getIdFormat();
            final int style = format == PengramConfig.ID_FORMAT_HIDE ? PengramConfig.ID_STYLE_OFF : PengramConfig.getIdStyle();
            final String formatted = PengramConfig.formatId(id, false, false);

            CharSequence usernameValue = getString(R.string.Username);
            if (style == PengramConfig.ID_STYLE_INLINE) {
                usernameValue = usernameValue + " \u2022 ID: " + formatted;
            }
            final boolean idRowVisible = style == PengramConfig.ID_STYLE_ROW || style == PengramConfig.ID_STYLE_ROW_DC;
            usernameCell.setTextAndValue("@" + username, usernameValue, idRowVisible || regVisible);

            if (idRowVisible) {
                idCell.setVisibility(VISIBLE);
                idCell.setTextAndValue(formatted, style == PengramConfig.ID_STYLE_ROW_DC ? ("ID \u2022 DC" + dcId) : "ID", regVisible);
                idCell.setContentDescriptionValueFirst(true);
                if (PengramConfig.isRegDateIconVisible() && PengramConfig.getRegDateIconRes() != 0) {
                    idCell.setImage(tintedRegIcon(), getString(R.string.PengramRegDate));
                } else {
                    idCell.setImage(null);
                }
            } else {
                idCell.setVisibility(GONE);
            }

            final boolean regRowVisible = regVisible && PengramConfig.getRegDatePlace() != PengramConfig.REG_PLACE_SUBTITLE
                    && (PengramConfig.getRegDatePlace() != PengramConfig.REG_PLACE_ICON || !idRowVisible);
            if (regRowVisible) {
                regCell.setVisibility(VISIBLE);
                regCell.setTextAndValue(regText, getString(R.string.PengramRegDate), false);
                regCell.setContentDescriptionValueFirst(true);
                if (PengramConfig.getRegDateIconRes() != 0) {
                    regCell.setImage(tintedRegIcon(), getString(R.string.PengramRegDate));
                } else {
                    regCell.setImage(null);
                }
            } else {
                regCell.setVisibility(GONE);
            }
        }

        /** значок даты регистрации, перекрашенный под тему (иначе чёрный пингвин тонет в тёмной) */
        private android.graphics.drawable.Drawable tintedRegIcon() {
            final int res = PengramConfig.getRegDateIconRes();
            if (res == 0 || getContext() == null) {
                return null;
            }
            final android.graphics.drawable.Drawable drawable = androidx.core.content.ContextCompat.getDrawable(getContext(), res);
            if (drawable != null) {
                drawable.mutate().setColorFilter(new android.graphics.PorterDuffColorFilter(
                        Theme.getColor(Theme.key_switch2TrackChecked, getResourceProvider()), android.graphics.PorterDuff.Mode.SRC_IN));
            }
            return drawable;
        }

        /** текст даты регистрации в выбранном формате — тот же, что и в профиле */
        private CharSequence regSampleText(long estimate) {
            if (estimate <= 0) {
                return "";
            }
            final int style = PengramConfig.getRegDateStyle();
            final String age = org.telegram.messenger.PengramRegDate.formatAge(estimate);
            if (style == PengramConfig.REG_STYLE_AGE) {
                return age == null ? "" : age;
            }
            String date = style == PengramConfig.REG_STYLE_EXACT
                    ? org.telegram.messenger.PengramRegDate.formatDate(estimate)
                    : org.telegram.messenger.PengramRegDate.formatMonthYear(estimate);
            if (date == null) {
                return "";
            }
            String text = "\u2248 " + date;
            if ((style == PengramConfig.REG_STYLE_DATE_AGE || style == PengramConfig.REG_STYLE_EXACT) && age != null) {
                text = text + " \u2022 " + age;
            }
            return text;
        }
    }
}
