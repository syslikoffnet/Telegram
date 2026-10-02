package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки форка Pengram (призрак-режим, отображение ID и т.д.).
 * Хранится в отдельном файле настроек, чтобы не конфликтовать с апстримом.
 */
public class PengramConfig {

    private static final String PREFS = "pengramconfig";

    // --- стили отображения ID в профиле ---
    public static final int ID_STYLE_OFF = 0;          // не показывать
    public static final int ID_STYLE_ROW = 1;          // отдельная строка "ID" под юзернеймом
    public static final int ID_STYLE_ROW_DC = 2;       // отдельная строка "ID • DC2"
    public static final int ID_STYLE_INLINE = 3;       // дописывается к подписи юзернейма

    // --- призрак-режим ---
    public static boolean ghostMode;          // мастер-переключатель
    public static boolean hideOnline;         // не показывать онлайн / не слать updateStatus
    public static boolean dontSendRead;       // не отправлять прочтение сообщений
    public static boolean dontSendTyping;     // не отправлять "печатает"
    public static boolean dontSendStoryViews; // не отмечать просмотр историй

    // --- формат ID ---
    public static final int ID_FORMAT_HIDE = 0;        // не показывать ID вовсе
    public static final int ID_FORMAT_TELEGRAM = 1;    // как есть (Telegram API)
    public static final int ID_FORMAT_BOT = 2;         // с минусом / -100 (Bot API)

    // --- профиль ---
    public static int idStyle = ID_STYLE_ROW_DC;
    public static int idFormat = ID_FORMAT_TELEGRAM;
    public static boolean copyIdOnTap = true;

    // --- дата регистрации ---
    public static final int REG_STYLE_OFF = 0;
    public static final int REG_STYLE_DATE = 1;      // «≈ март 2021»
    public static final int REG_STYLE_DATE_AGE = 2;  // «≈ март 2021 • 4 года»
    public static final int REG_STYLE_AGE = 3;       // «4 года»
    public static final int REG_STYLE_EXACT = 4;     // «≈ 12 марта 2021»
    public static int regDateStyle = REG_STYLE_DATE_AGE;

    // где и чем показывать дату регистрации
    public static final int REG_PLACE_ROW = 0;       // отдельная строка в профиле
    public static final int REG_PLACE_ICON = 1;      // только значок рядом с ID
    public static final int REG_PLACE_BOTH = 2;      // строка + значок
    public static final int REG_PLACE_SUBTITLE = 3;  // в подписи под именем
    public static int regDatePlace = REG_PLACE_BOTH;

    // значок даты регистрации
    public static final int REG_ICON_CALENDAR = 0;
    public static final int REG_ICON_CLOCK = 1;
    public static final int REG_ICON_CAKE = 2;
    public static final int REG_ICON_STAR = 3;
    public static final int REG_ICON_INFO = 4;
    public static final int REG_ICON_PENGUIN = 5;
    public static final int REG_ICON_NONE = 6;
    public static int regDateIcon = REG_ICON_CALENDAR;

    // заголовок списка чатов
    public static final int TITLE_MODE_DEFAULT = 0;   // как в Telegram
    public static final int TITLE_MODE_PENGRAM = 1;   // «Pengram»
    public static final int TITLE_MODE_CHATS = 2;     // «Чаты»
    public static final int TITLE_MODE_NAME = 3;      // имя аккаунта
    public static final int TITLE_MODE_USERNAME = 4;  // @username
    public static final int TITLE_MODE_CUSTOM = 5;    // свой текст
    public static int titleMode = TITLE_MODE_DEFAULT;
    public static String titleCustom = "";

    // размер нижней панели вкладок, % (70..130)
    public static int tabBarSize = 100;

    // --- история удалённых/изменённых ---
    public static boolean saveDeleted = true;
    public static boolean saveEdited = true;
    public static boolean saveOutgoing = true;       // сохранять и свои сообщения (удалённые собеседником)
    public static boolean historyRowInProfile = true;

    // --- снятие ограничений ---
    public static boolean allowScreenshots = true;      // разрешать скриншоты везде
    public static boolean noScreenshotNotify = true;    // не уведомлять о скриншотах в секретных чатах
    public static boolean allowForwards = true;         // обходить запрет пересылки/сохранения
    public static boolean keepOnceMedia = true;         // одноразовые медиа не «сгорают»
    public static boolean hideAds = true;               // убирать рекламу
    public static boolean localPremium = false;         // локальный premium

    // --- внешний вид ---
    public static final int MENU_POS_TOP = 0;
    public static final int MENU_POS_BOTTOM = 1;
    public static int chatMenuPosition = MENU_POS_TOP;  // где остров Pengram в меню чата
    public static boolean chatMenuEnabled = true;

    public static final int FONT_DEFAULT = 0;
    public static final int FONT_SYSTEM = 1;
    public static final int FONT_SERIF = 2;
    public static final int FONT_MONOSPACE = 3;
    public static int appFont = FONT_DEFAULT;

    // --- автостиль отправляемого текста ---
    public static final int SEND_STYLE_OFF = 0;
    public static final int SEND_STYLE_BOLD = 1;
    public static final int SEND_STYLE_ITALIC = 2;
    public static final int SEND_STYLE_MONO = 3;
    public static final int SEND_STYLE_STRIKE = 4;
    public static final int SEND_STYLE_UNDERLINE = 5;
    public static final int SEND_STYLE_SPOILER = 6;
    public static final int SEND_STYLE_QUOTE = 7;
    public static final int SEND_STYLE_WIDE = 8;       // ш и р о к и й  (полноширинные символы)
    public static int sendTextStyle = SEND_STYLE_OFF;
    /** применять стиль и к подписям к фото/видео */
    public static final String KEY_SEND_STYLE_CAPTIONS = "sendStyleCaptions";

    // --- пересылка ---
    /** прятать ленту историй в списке чатов */
    public static final String KEY_HIDE_STORIES = "hideStories";

    /** убрать «хвостик» у пузырей сообщений */
    public static final String KEY_HIDE_TAIL = "hideBubbleTail";
    /** не писать «изменено» у времени (метка остаётся) */
    public static final String KEY_HIDE_EDITED_LABEL = "hideEditedLabel";
    /** всегда идёт снег в шапке */
    public static final String KEY_FORCE_SNOW = "forceSnow";
    /** заголовок по центру */
    public static final String KEY_TITLE_CENTER = "titleCenter";
    /** мини-аватарки отправителей в списке чатов */
    public static final String KEY_DIALOG_SENDER_AVATARS = "dialogSenderAvatars";
    /** всегда показывать галочку «Удалить у всех» */
    public static final String KEY_FORCE_DELETE_FOR_ALL = "forceDeleteForAll";
    /** удалённые пересылать от своего лица, без «переслано от» */
    public static final String KEY_RESEND_AS_MINE = "resendDeletedAsMine";
    /** пункты «отправить удалёнку» в меню сообщения */
    public static final String KEY_RESEND_MENU = "resendDeletedMenu";
    /** одноразовые медиа тоже можно переслать от своего лица */
    public static final String KEY_RESEND_ONCE = "resendOnceMedia";
    /** спрашивать чат перед отправкой удалёнки */
    public static final String KEY_RESEND_ASK_CHAT = "resendAskChat";

    /** блокировать поле ввода, пока в чат идёт пересылка */
    public static final String KEY_FORWARD_LOCK = "forwardLockInput";
    /** вибрация/звук по окончании пересылки */
    public static final String KEY_FORWARD_DONE_ALERT = "forwardDoneAlert";

    // --- сохранение медиа удалённых сообщений ---
    public static final String DEFAULT_MEDIA_FOLDER = "Pengram";
    public static final String DEFAULT_MEDIA_PATTERN = "deleted_{date}_{chat}_{id}";
    public static boolean saveDeletedMedia = false;
    public static String mediaFolder = DEFAULT_MEDIA_FOLDER;
    public static String mediaPattern = DEFAULT_MEDIA_PATTERN;

    // --- приватность локально ---
    public static boolean hidePhoneNumber = false;

    // --- история: дополнительно ---
    public static boolean saveInBots = true;
    public static boolean saveReadDate = true;
    public static boolean saveLastOnline = true;
    public static int voiceChangerMode;
    public static int voiceChangerPitch;
    public static int speedBoost = 1; // BOOST_FAST
    public static int mediaMaxSizeMb = 2048;   // 0 = без лимита
    public static int historyKeepDays = 0;      // 0 = хранить всегда

    // --- скрытие кнопок ---
    public static boolean hideMenuNewGroup = false;
    public static boolean hideMenuSavedMessages = false;
    public static boolean hideMenuSettings = false;
    public static boolean hideMenuTheme = false;
    public static boolean hideChatSearch = false;
    public static boolean hideChatTranslate = false;
    public static boolean hideChatClearHistory = false;
    public static boolean hideChatWallpaper = false;
    public static boolean hideChatShortcut = false;
    public static boolean hideChatReport = false;
    public static boolean hideChatCall = false;
    public static boolean hideChatAutoDelete = false;

    // --- удалённые прямо в чате ---
    public static final int MARK_NONE = 0;
    public static final int MARK_TRASH = 1;
    public static final int MARK_CROSS = 2;
    public static final int MARK_EYE = 3;
    public static final int MARK_FIRE = 4;

    // --- метка изменённых сообщений ---
    public static final int MARK_EDIT_NONE = 0;
    public static final int MARK_EDIT_PENCIL = 1;
    public static final int MARK_EDIT_CLOCK = 2;
    public static final int MARK_EDIT_DOT = 3;

    /** оставлять удалённые сообщения в чате (помечать, а не удалять) */
    public static final String KEY_KEEP_DELETED = "keepDeletedInChat";
    /** делать удалённые полупрозрачными */
    public static final String KEY_FADE_DELETED = "fadeDeleted";
    /** метка у изменённых сообщений */
    public static final String KEY_MARK_EDITED = "markEdited";
    /** галочка «сохранить у себя» включена по умолчанию */
    public static final String KEY_SAVE_FOR_MYSELF_DEFAULT = "saveForMyselfDefault";
    /** показывать подсказку «сохранить у себя» в меню удаления */
    public static final String KEY_SAVE_FOR_MYSELF_SHOW = "saveForMyselfShow";

    // --- призрак ---
    public static final String KEY_GHOST_AUTO_OFFLINE = "ghostAutoOffline";
    public static final String KEY_GHOST_STORIES_WARN = "ghostStoriesWarn";
    public static final String KEY_GHOST_SEND_DELAY = "ghostSendDelay";
    public static final String KEY_GHOST_DONT_SEND_REACTIONS = "ghostDontSendReactions";
    public static final String KEY_GHOST_DONT_SEND_VOICE_READ = "ghostDontSendVoiceRead";

    // --- полезные функции ---
    public static final String KEY_BACKGROUND_MODE = "backgroundMode";
    public static final String KEY_BACKGROUND_SILENT = "backgroundSilentIcon";
    public static final String KEY_PREMIUM_STATUS = "localPremiumStatus";

    // --- основное ---
    /** не округлять числа (1 234 567 вместо 1,2M) */
    public static final String KEY_NO_ROUNDING = "noNumberRounding";
    /** показывать время с секундами */
    public static final String KEY_TIME_SECONDS = "timeWithSeconds";
    /** вибрация внутри приложения */
    public static final String KEY_VIBRATION = "inAppVibration";
    /** фильтр Zalgo-символов */
    public static final String KEY_ZALGO = "zalgoFilter";

    // --- вкладки главного экрана ---
    public static final String KEY_TAB_CONTACTS = "hideTabContacts";
    public static final String KEY_TAB_CALLS = "hideTabCalls";
    public static final String KEY_TAB_SETTINGS = "hideTabSettings";
    public static final String KEY_TAB_PROFILE = "hideTabProfile";
    public static final String KEY_MENU_CONTACTS = "hideMenuContacts";
    public static final String KEY_MENU_CALLS = "hideMenuCalls";
    public static final String KEY_MENU_GHOST = "hideMenuGhost";
    public static final String KEY_MENU_PENGRAM = "hideMenuPengram";

    public static int deletedMark = MARK_TRASH;
    public static int editedMark = MARK_EDIT_PENCIL;

    private static final java.util.HashMap<String, Boolean> boolCache = new java.util.HashMap<>();

    public static boolean getBool(String key, boolean def) {
        init();
        synchronized (boolCache) {
            Boolean cached = boolCache.get(key);
            if (cached != null) {
                return cached;
            }
            SharedPreferences p = prefs();
            final boolean value = p != null ? p.getBoolean(key, def) : def;
            boolCache.put(key, value);
            return value;
        }
    }

    public static void setBool(String key, boolean value) {
        init();
        synchronized (boolCache) {
            boolCache.put(key, value);
        }
        putBoolean(key, value);
    }

    public static boolean toggle(String key, boolean def) {
        final boolean value = !getBool(key, def);
        setBool(key, value);
        return value;
    }

    public static boolean isKeepingDeletedInChat() { return isSavingDeleted() && getBool(KEY_KEEP_DELETED, true); }
    public static boolean isFadingDeleted() { return getBool(KEY_FADE_DELETED, true); }
    public static boolean isMarkingEdited() { return getBool(KEY_MARK_EDITED, false); }
    public static boolean isSaveForMyselfDefault() { return getBool(KEY_SAVE_FOR_MYSELF_DEFAULT, false); }
    public static boolean isSaveForMyselfVisible() { return getBool(KEY_SAVE_FOR_MYSELF_SHOW, true); }

    public static boolean isGhostAutoOffline() { return ghostMode && getBool(KEY_GHOST_AUTO_OFFLINE, true); }
    public static boolean isGhostStoriesWarn() { return getBool(KEY_GHOST_STORIES_WARN, false); }
    public static boolean isGhostSendDelay() { return ghostMode && getBool(KEY_GHOST_SEND_DELAY, false); }
    public static boolean isNotSendingReactionsRead() { return ghostMode && getBool(KEY_GHOST_DONT_SEND_REACTIONS, false); }
    public static boolean isNotSendingVoiceRead() { return ghostMode && getBool(KEY_GHOST_DONT_SEND_VOICE_READ, true); }

    public static boolean isNoRounding() { return getBool(KEY_NO_ROUNDING, false); }
    public static boolean isTimeWithSeconds() { return getBool(KEY_TIME_SECONDS, false); }
    public static boolean isVibrationEnabled() { return getBool(KEY_VIBRATION, true); }
    public static boolean isZalgoFilter() { return getBool(KEY_ZALGO, false); }

    /**
     * Вырезает «zalgo» — комбинируемые символы, которыми ломают текст.
     * Трогаем только диакритические блоки, чтобы не портить нормальные языки.
     */
    public static CharSequence filterZalgo(CharSequence text) {
        if (text == null || text.length() == 0 || !isZalgoFilter()) {
            return text;
        }
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); ++i) {
            final char c = text.charAt(i);
            if (isZalgoChar(c)) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                    sb.append(text, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? text : sb.toString();
    }

    public static String filterZalgo(String text) {
        if (text == null || text.length() == 0 || !isZalgoFilter()) {
            return text;
        }
        return filterZalgo((CharSequence) text).toString();
    }

    /** то же, но длина строки сохраняется — важно, чтобы не поехали entity-смещения */
    public static String filterZalgoKeepLength(String text) {
        if (text == null || text.length() == 0 || !isZalgoFilter()) {
            return text;
        }
        char[] chars = null;
        for (int i = 0; i < text.length(); ++i) {
            if (isZalgoChar(text.charAt(i))) {
                if (chars == null) {
                    chars = text.toCharArray();
                }
                chars[i] = '\u200C';
            }
        }
        return chars == null ? text : new String(chars);
    }

    private static boolean isZalgoChar(char c) {
        return (c >= '\u0300' && c <= '\u036F')    // Combining Diacritical Marks
                || (c >= '\u0483' && c <= '\u0489') // Cyrillic combining
                || (c >= '\u1AB0' && c <= '\u1AFF') // Extended
                || (c >= '\u1DC0' && c <= '\u1DFF') // Supplement
                || (c >= '\u20D0' && c <= '\u20F0') // Combining for symbols
                || (c >= '\uFE20' && c <= '\uFE2F');// Half marks
    }

    public static boolean isBackgroundMode() { return getBool(KEY_BACKGROUND_MODE, false); }
    public static boolean isPremiumStatusLocal() { return isLocalPremium() && getBool(KEY_PREMIUM_STATUS, true); }

    public static int getDeletedMark() { init(); return deletedMark; }

    public static void setDeletedMark(int mark) {
        init();
        deletedMark = mark;
        putInt("deletedMark", mark);
    }

    public static int getEditedMark() {
        init();
        return editedMark;
    }

    public static void setEditedMark(int mark) {
        init();
        editedMark = mark;
        putInt("editedMark", mark);
    }

    /** ресурс значка для метки удалённого сообщения */
    public static int getMarkIcon(int mark) {
        switch (mark) {
            case MARK_TRASH: return R.drawable.pengram_mark_trash;
            case MARK_CROSS: return R.drawable.pengram_mark_cross;
            case MARK_EYE: return R.drawable.pengram_mark_eye;
            case MARK_FIRE: return R.drawable.pengram_mark_fire;
            default: return 0;
        }
    }

    /** ресурс значка для метки изменённого сообщения */
    public static int getEditedMarkIcon(int mark) {
        switch (mark) {
            case MARK_EDIT_PENCIL: return R.drawable.pengram_mark_pencil;
            case MARK_EDIT_CLOCK: return R.drawable.pengram_mark_clock;
            case MARK_EDIT_DOT: return R.drawable.pengram_mark_dot;
            default: return 0;
        }
    }

    /** значок удалённого сообщения с учётом настроек (0 — метки нет) */
    public static int getDeletedMarkIcon() {
        return getMarkIcon(getDeletedMark());
    }

    /** значок изменённого сообщения с учётом настроек (0 — метки нет) */
    public static int getEditedMarkIconRes() {
        if (!isMarkingEdited()) {
            return 0;
        }
        return getEditedMarkIcon(getEditedMark());
    }

    private static boolean loaded;

    public static void init() {
        if (loaded) return;
        synchronized (PengramConfig.class) {
            if (loaded) return;
            SharedPreferences p = prefs();
            if (p == null) return;
            ghostMode = p.getBoolean("ghostMode", false);
            hideOnline = p.getBoolean("hideOnline", true);
            dontSendRead = p.getBoolean("dontSendRead", true);
            dontSendTyping = p.getBoolean("dontSendTyping", true);
            dontSendStoryViews = p.getBoolean("dontSendStoryViews", true);
            idStyle = p.getInt("idStyle", ID_STYLE_ROW_DC);
            idFormat = p.getInt("idFormat", ID_FORMAT_TELEGRAM);
            copyIdOnTap = p.getBoolean("copyIdOnTap", true);
            regDateStyle = p.getInt("regDateStyle", REG_STYLE_DATE_AGE);
            regDatePlace = p.getInt("regDatePlace", REG_PLACE_BOTH);
            regDateIcon = p.getInt("regDateIcon", REG_ICON_CALENDAR);
            titleMode = p.getInt("titleMode", TITLE_MODE_DEFAULT);
            titleCustom = p.getString("titleCustom", "");
            tabBarSize = p.getInt("tabBarSize", 100);
            saveDeleted = p.getBoolean("saveDeleted", true);
            saveEdited = p.getBoolean("saveEdited", true);
            saveOutgoing = p.getBoolean("saveOutgoing2", true);
            historyRowInProfile = p.getBoolean("historyRowInProfile", true);
            allowScreenshots = p.getBoolean("allowScreenshots", true);
            noScreenshotNotify = p.getBoolean("noScreenshotNotify", true);
            allowForwards = p.getBoolean("allowForwards", true);
            keepOnceMedia = p.getBoolean("keepOnceMedia", true);
            hideAds = p.getBoolean("hideAds", true);
            localPremium = p.getBoolean("localPremium", false);
            chatMenuPosition = p.getInt("chatMenuPosition", MENU_POS_TOP);
            chatMenuEnabled = p.getBoolean("chatMenuEnabled", true);
            appFont = p.getInt("appFont", FONT_DEFAULT);
            sendTextStyle = p.getInt("sendTextStyle", SEND_STYLE_OFF);
            saveDeletedMedia = p.getBoolean("saveDeletedMedia", false);
            mediaFolder = p.getString("mediaFolder", DEFAULT_MEDIA_FOLDER);
            mediaPattern = p.getString("mediaPattern", DEFAULT_MEDIA_PATTERN);
            hidePhoneNumber = p.getBoolean("hidePhoneNumber", false);
            saveInBots = p.getBoolean("saveInBots", true);
            saveReadDate = p.getBoolean("saveReadDate", true);
            saveLastOnline = p.getBoolean("saveLastOnline", true);
            mediaMaxSizeMb = p.getInt("mediaMaxSizeMb", 2048);
            historyKeepDays = p.getInt("historyKeepDays", 0);
            voiceChangerMode = p.getInt("voiceChangerMode", 0);
            voiceChangerPitch = p.getInt("voiceChangerPitch", 0);
            speedBoost = p.getInt("speedBoost", BOOST_FAST);
            hideMenuNewGroup = p.getBoolean("hideMenuNewGroup", false);
            hideMenuSavedMessages = p.getBoolean("hideMenuSavedMessages", false);
            hideMenuSettings = p.getBoolean("hideMenuSettings", false);
            hideMenuTheme = p.getBoolean("hideMenuTheme", false);
            hideChatSearch = p.getBoolean("hideChatSearch", false);
            hideChatTranslate = p.getBoolean("hideChatTranslate", false);
            hideChatClearHistory = p.getBoolean("hideChatClearHistory", false);
            hideChatWallpaper = p.getBoolean("hideChatWallpaper", false);
            hideChatShortcut = p.getBoolean("hideChatShortcut", false);
            hideChatReport = p.getBoolean("hideChatReport", false);
            hideChatCall = p.getBoolean("hideChatCall", false);
            hideChatAutoDelete = p.getBoolean("hideChatAutoDelete", false);
            deletedMark = p.getInt("deletedMark", MARK_TRASH);
            editedMark = p.getInt("editedMark", MARK_EDIT_PENCIL);
            loaded = true;
        }
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) return null;
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void putBoolean(String key, boolean value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(key, value).apply();
    }

    private static void putString(String key, String value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(key, value).apply();
    }

    private static void putInt(String key, int value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(key, value).apply();
    }

    public static void setGhostMode(boolean value) {
        init();
        if (ghostMode == value) {
            return;
        }
        ghostMode = value;
        putBoolean("ghostMode", ghostMode);
    }

    public static void toggleGhostMode() {
        init();
        ghostMode = !ghostMode;
        putBoolean("ghostMode", ghostMode);
    }

    public static void toggleHideOnline() {
        init();
        hideOnline = !hideOnline;
        putBoolean("hideOnline", hideOnline);
    }

    public static void toggleDontSendRead() {
        init();
        dontSendRead = !dontSendRead;
        putBoolean("dontSendRead", dontSendRead);
    }

    public static void toggleDontSendTyping() {
        init();
        dontSendTyping = !dontSendTyping;
        putBoolean("dontSendTyping", dontSendTyping);
    }

    public static void toggleDontSendStoryViews() {
        init();
        dontSendStoryViews = !dontSendStoryViews;
        putBoolean("dontSendStoryViews", dontSendStoryViews);
    }

    public static void toggleCopyIdOnTap() {
        init();
        copyIdOnTap = !copyIdOnTap;
        putBoolean("copyIdOnTap", copyIdOnTap);
    }

    public static void setRegDateStyle(int style) {
        init();
        regDateStyle = style;
        putInt("regDateStyle", style);
    }

    public static void toggleSaveDeleted() {
        init();
        saveDeleted = !saveDeleted;
        putBoolean("saveDeleted", saveDeleted);
    }

    public static void toggleSaveEdited() {
        init();
        saveEdited = !saveEdited;
        putBoolean("saveEdited", saveEdited);
    }

    public static void toggleSaveOutgoing() {
        init();
        saveOutgoing = !saveOutgoing;
        putBoolean("saveOutgoing2", saveOutgoing);
    }

    public static void toggleHistoryRowInProfile() {
        init();
        historyRowInProfile = !historyRowInProfile;
        putBoolean("historyRowInProfile", historyRowInProfile);
    }

    public static boolean isSavingDeleted() {
        init();
        return saveDeleted;
    }

    public static boolean isSavingEdited() {
        init();
        return saveEdited;
    }

    public static boolean isSavingOutgoing() {
        return true; // отдельной опции больше нет: свои сообщения сохраняются (см. «сохранить у себя»)
    }

    private static boolean isSavingOutgoingLegacy() {
        init();
        return saveOutgoing;
    }

    public static int getRegDateStyle() {
        init();
        return regDateStyle;
    }

    public static boolean isRegDateVisible() {
        return getRegDateStyle() != REG_STYLE_OFF;
    }

    public static int getRegDatePlace() { init(); return regDatePlace; }
    public static void setRegDatePlace(int place) { init(); regDatePlace = place; putInt("regDatePlace", place); }
    /** нужна ли отдельная строка «дата регистрации» в профиле */
    public static boolean isRegDateRowVisible() {
        if (!isRegDateVisible()) return false;
        final int place = getRegDatePlace();
        return place == REG_PLACE_ROW || place == REG_PLACE_BOTH;
    }
    /** нужен ли значок рядом со строкой ID */
    public static boolean isRegDateIconVisible() {
        if (!isRegDateVisible()) return false;
        if (getRegDateIcon() == REG_ICON_NONE) return false;
        final int place = getRegDatePlace();
        return place == REG_PLACE_ICON || place == REG_PLACE_BOTH;
    }
    /** дописывать ли дату регистрации в подпись под именем */
    public static boolean isRegDateInSubtitle() {
        return isRegDateVisible() && getRegDatePlace() == REG_PLACE_SUBTITLE;
    }

    public static int getRegDateIcon() { init(); return regDateIcon; }
    public static void setRegDateIcon(int icon) { init(); regDateIcon = icon; putInt("regDateIcon", icon); }
    /** ресурс значка даты регистрации (0 — без значка) */
    public static int getRegDateIconRes() {
        switch (getRegDateIcon()) {
            case REG_ICON_CLOCK: return org.telegram.messenger.R.drawable.menu_premium_clock;
            case REG_ICON_CAKE: return org.telegram.messenger.R.drawable.menu_birthday;
            case REG_ICON_STAR: return org.telegram.messenger.R.drawable.msg_premium_liststar;
            case REG_ICON_INFO: return org.telegram.messenger.R.drawable.msg_info;
            case REG_ICON_PENGUIN: return org.telegram.messenger.R.drawable.pengram_penguin_dark;
            case REG_ICON_NONE: return 0;
            case REG_ICON_CALENDAR:
            default: return org.telegram.messenger.R.drawable.msg_calendar2;
        }
    }

    public static int getTitleMode() { init(); return titleMode; }
    public static void setTitleMode(int mode) { init(); titleMode = mode; putInt("titleMode", mode); }
    public static String getTitleCustom() { init(); return titleCustom == null ? "" : titleCustom; }
    public static void setTitleCustom(String text) { init(); titleCustom = text == null ? "" : text; putString("titleCustom", titleCustom); }
    public static boolean isTitleCentered() { return getBool(KEY_TITLE_CENTER, false); }

    public static int getTabBarSize() { init(); return Math.max(70, Math.min(130, tabBarSize)); }
    public static void setTabBarSize(int percent) { init(); tabBarSize = Math.max(70, Math.min(130, percent)); putInt("tabBarSize", tabBarSize); }
    /** множитель размеров нижней панели */
    public static float getTabBarScale() { return getTabBarSize() / 100f; }

    // ------------------------------- пункты верхнего меню -------------------------------

    public static final int MENU_ITEM_PENGRAM = 1;
    public static final int MENU_ITEM_GHOST = 2;
    public static final int MENU_ITEM_THEME = 3;
    public static final int MENU_ITEM_NEW_GROUP = 4;
    public static final int MENU_ITEM_NEW_CHANNEL = 5;
    public static final int MENU_ITEM_SAVED = 6;
    public static final int MENU_ITEM_CONTACTS = 7;
    public static final int MENU_ITEM_CALLS = 8;
    public static final int MENU_ITEM_PROFILE = 9;
    public static final int MENU_ITEM_SETTINGS = 10;
    public static final int MENU_ITEM_CLOSE_APP = 11;

    public static final int[] MENU_ITEMS_DEFAULT = new int[]{
            MENU_ITEM_PENGRAM, MENU_ITEM_GHOST, MENU_ITEM_THEME, MENU_ITEM_NEW_GROUP,
            MENU_ITEM_NEW_CHANNEL, MENU_ITEM_SAVED, MENU_ITEM_CONTACTS, MENU_ITEM_CALLS,
            MENU_ITEM_PROFILE, MENU_ITEM_SETTINGS, MENU_ITEM_CLOSE_APP
    };

    /** порядок пунктов меню, с добавлением новых в конец */
    public static int[] getMenuOrder() {
        init();
        final String saved = prefs() == null ? "" : prefs().getString("menuOrder", "");
        final java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        if (saved != null && !saved.isEmpty()) {
            for (String part : saved.split(",")) {
                try {
                    final int id = Integer.parseInt(part.trim());
                    for (int known : MENU_ITEMS_DEFAULT) {
                        if (known == id && !result.contains(id)) {
                            result.add(id);
                            break;
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
        }
        for (int known : MENU_ITEMS_DEFAULT) {
            if (!result.contains(known)) {
                result.add(known);
            }
        }
        final int[] out = new int[result.size()];
        for (int a = 0; a < out.length; a++) {
            out[a] = result.get(a);
        }
        return out;
    }

    public static void setMenuOrder(java.util.List<Integer> order) {
        if (order == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (int a = 0; a < order.size(); a++) {
            if (a > 0) sb.append(',');
            sb.append(order.get(a));
        }
        putString("menuOrder", sb.toString());
    }

    private static String menuItemKey(int id) {
        switch (id) {
            case MENU_ITEM_PENGRAM: return KEY_MENU_PENGRAM;
            case MENU_ITEM_GHOST: return KEY_MENU_GHOST;
            case MENU_ITEM_THEME: return "hideMenuTheme";
            case MENU_ITEM_NEW_GROUP: return "hideMenuNewGroup";
            case MENU_ITEM_NEW_CHANNEL: return "hideMenuChannel";
            case MENU_ITEM_SAVED: return "hideMenuSavedMessages";
            case MENU_ITEM_CONTACTS: return KEY_MENU_CONTACTS;
            case MENU_ITEM_CALLS: return KEY_MENU_CALLS;
            case MENU_ITEM_PROFILE: return "hideMenuProfile";
            case MENU_ITEM_SETTINGS: return "hideMenuSettings";
            case MENU_ITEM_CLOSE_APP: return "hideMenuCloseApp";
            default: return "hideMenuUnknown" + id;
        }
    }

    /** по умолчанию прячем «дополнительные» пункты, чтобы меню не разрасталось */
    private static boolean menuItemHiddenDefault(int id) {
        switch (id) {
            case MENU_ITEM_NEW_CHANNEL:
            case MENU_ITEM_CONTACTS:
            case MENU_ITEM_CALLS:
            case MENU_ITEM_PROFILE:
            case MENU_ITEM_CLOSE_APP:
                return true;
            default:
                return false;
        }
    }

    public static boolean isMenuItemHidden(int id) {
        final String key = menuItemKey(id);
        final boolean def = menuItemHiddenDefault(id);
        switch (id) {
            case MENU_ITEM_THEME: init(); return hideMenuTheme;
            case MENU_ITEM_NEW_GROUP: init(); return hideMenuNewGroup;
            case MENU_ITEM_SAVED: init(); return hideMenuSavedMessages;
            case MENU_ITEM_SETTINGS: init(); return hideMenuSettings;
            default: return getBool(key, def);
        }
    }

    public static void setMenuItemHidden(int id, boolean hidden) {
        switch (id) {
            case MENU_ITEM_THEME: init(); hideMenuTheme = hidden; putBoolean("hideMenuTheme", hidden); break;
            case MENU_ITEM_NEW_GROUP: init(); hideMenuNewGroup = hidden; putBoolean("hideMenuNewGroup", hidden); break;
            case MENU_ITEM_SAVED: init(); hideMenuSavedMessages = hidden; putBoolean("hideMenuSavedMessages", hidden); break;
            case MENU_ITEM_SETTINGS: init(); hideMenuSettings = hidden; putBoolean("hideMenuSettings", hidden); break;
            default: setBool(menuItemKey(id), hidden); break;
        }
    }

    public static int getMenuItemTitle(int id) {
        switch (id) {
            case MENU_ITEM_PENGRAM: return org.telegram.messenger.R.string.PengramSettings;
            case MENU_ITEM_GHOST: return org.telegram.messenger.R.string.PengramGhostToggle;
            case MENU_ITEM_THEME: return org.telegram.messenger.R.string.PengramMenuThemeItem;
            case MENU_ITEM_NEW_GROUP: return org.telegram.messenger.R.string.NewGroup;
            case MENU_ITEM_NEW_CHANNEL: return org.telegram.messenger.R.string.NewChannel;
            case MENU_ITEM_SAVED: return org.telegram.messenger.R.string.SavedMessages;
            case MENU_ITEM_CONTACTS: return org.telegram.messenger.R.string.Contacts;
            case MENU_ITEM_CALLS: return org.telegram.messenger.R.string.Calls;
            case MENU_ITEM_PROFILE: return org.telegram.messenger.R.string.PengramMenuMyProfile;
            case MENU_ITEM_SETTINGS: return org.telegram.messenger.R.string.Settings;
            case MENU_ITEM_CLOSE_APP: return org.telegram.messenger.R.string.PengramMenuCloseApp;
            default: return org.telegram.messenger.R.string.AppName;
        }
    }

    public static int getMenuItemIcon(int id) {
        switch (id) {
            case MENU_ITEM_PENGRAM: return org.telegram.messenger.R.drawable.settings_features;
            case MENU_ITEM_GHOST: return org.telegram.messenger.R.drawable.msg_secret;
            case MENU_ITEM_THEME: return org.telegram.messenger.R.drawable.menu_night_mode_24;
            case MENU_ITEM_NEW_GROUP: return org.telegram.messenger.R.drawable.outline_groups_24;
            case MENU_ITEM_NEW_CHANNEL: return org.telegram.messenger.R.drawable.msg_channel;
            case MENU_ITEM_SAVED: return org.telegram.messenger.R.drawable.outline_saved_24;
            case MENU_ITEM_CONTACTS: return org.telegram.messenger.R.drawable.msg_contacts;
            case MENU_ITEM_CALLS: return org.telegram.messenger.R.drawable.msg_calls;
            case MENU_ITEM_PROFILE: return org.telegram.messenger.R.drawable.settings_account;
            case MENU_ITEM_SETTINGS: return org.telegram.messenger.R.drawable.msg_settings_old;
            case MENU_ITEM_CLOSE_APP: return org.telegram.messenger.R.drawable.msg_leave;
            default: return org.telegram.messenger.R.drawable.msg_settings_old;
        }
    }

    /** сколько пунктов меню сейчас скрыто */
    public static int getHiddenMenuItemsCount() {
        int count = 0;
        for (int id : MENU_ITEMS_DEFAULT) {
            if (isMenuItemHidden(id)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isHidingBubbleTail() { return getBool(KEY_HIDE_TAIL, false); }
    public static boolean isHidingEditedLabel() { return getBool(KEY_HIDE_EDITED_LABEL, false); }
    public static boolean isForcedSnow() { return getBool(KEY_FORCE_SNOW, false); }
    public static boolean isDialogSenderAvatars() { return getBool(KEY_DIALOG_SENDER_AVATARS, false); }
    public static boolean isForceDeleteForAll() { return getBool(KEY_FORCE_DELETE_FOR_ALL, true); }
    public static boolean isResendDeletedAsMine() { return getBool(KEY_RESEND_AS_MINE, true); }
    public static boolean isResendMenuVisible() { return getBool(KEY_RESEND_MENU, true); }
    public static boolean isResendOnceMedia() { return getBool(KEY_RESEND_ONCE, true); }
    public static boolean isResendAskChat() { return getBool(KEY_RESEND_ASK_CHAT, false); }

    public static void toggleAllowScreenshots() { init(); allowScreenshots = !allowScreenshots; putBoolean("allowScreenshots", allowScreenshots); }
    public static void toggleNoScreenshotNotify() { init(); noScreenshotNotify = !noScreenshotNotify; putBoolean("noScreenshotNotify", noScreenshotNotify); }
    public static void toggleAllowForwards() { init(); allowForwards = !allowForwards; putBoolean("allowForwards", allowForwards); }
    public static void toggleKeepOnceMedia() { init(); keepOnceMedia = !keepOnceMedia; putBoolean("keepOnceMedia", keepOnceMedia); }
    public static void toggleHideAds() { init(); hideAds = !hideAds; putBoolean("hideAds", hideAds); }
    public static void toggleLocalPremium() { init(); localPremium = !localPremium; putBoolean("localPremium", localPremium); }
    public static void toggleChatMenu() { init(); chatMenuEnabled = !chatMenuEnabled; putBoolean("chatMenuEnabled", chatMenuEnabled); }
    public static void setChatMenuPosition(int pos) { init(); chatMenuPosition = pos; putInt("chatMenuPosition", pos); }
    public static void setAppFont(int font) { init(); appFont = font; putInt("appFont", font); }
    public static int getSendTextStyle() { init(); return sendTextStyle; }
    public static void setSendTextStyle(int style) { init(); sendTextStyle = style; putInt("sendTextStyle", style); }
    public static boolean isSendStyleForCaptions() { return getBool(KEY_SEND_STYLE_CAPTIONS, true); }
    public static boolean isForwardLockEnabled() { return getBool(KEY_FORWARD_LOCK, true); }
    public static boolean isHidingStories() { return getBool(KEY_HIDE_STORIES, false); }
    public static boolean isForwardDoneAlert() { return getBool(KEY_FORWARD_DONE_ALERT, true); }

    /** true — FLAG_SECURE ставить нельзя, скриншоты разрешены */
    public static boolean screenshotsAllowed() {
        init();
        return allowScreenshots;
    }

    public static boolean isNoScreenshotNotify() { init(); return allowScreenshots && noScreenshotNotify; }
    public static boolean isBypassingForwardRestrictions() { init(); return allowForwards; }
    public static boolean isKeepingOnceMedia() { init(); return keepOnceMedia; }
    public static boolean isHidingAds() { init(); return hideAds; }
    public static boolean isLocalPremium() { init(); return localPremium; }
    public static int getChatMenuPosition() { init(); return chatMenuPosition; }
    public static boolean isChatMenuEnabled() { init(); return chatMenuEnabled; }
    public static int getAppFont() { init(); return appFont; }

    public static void toggleSaveDeletedMedia() { init(); saveDeletedMedia = !saveDeletedMedia; putBoolean("saveDeletedMedia", saveDeletedMedia); }

    public static void setMediaFolder(String folder) {
        init();
        if (folder == null || folder.trim().isEmpty()) folder = DEFAULT_MEDIA_FOLDER;
        folder = folder.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        mediaFolder = folder;
        putString("mediaFolder", folder);
    }

    public static void setMediaPattern(String pattern) {
        init();
        if (pattern == null || pattern.trim().isEmpty()) pattern = DEFAULT_MEDIA_PATTERN;
        mediaPattern = pattern.trim();
        putString("mediaPattern", mediaPattern);
    }

    public static boolean isSavingDeletedMedia() { init(); return saveDeleted && saveDeletedMedia; }
    public static String getMediaFolder() { init(); return mediaFolder == null || mediaFolder.isEmpty() ? DEFAULT_MEDIA_FOLDER : mediaFolder; }
    public static String getMediaPattern() { init(); return mediaPattern == null || mediaPattern.isEmpty() ? DEFAULT_MEDIA_PATTERN : mediaPattern; }

    public static final int BOOST_OFF = 0;
    public static final int BOOST_FAST = 1;
    public static final int BOOST_EXTREME = 2;

    public static void toggleHidePhoneNumber() { init(); hidePhoneNumber = !hidePhoneNumber; putBoolean("hidePhoneNumber", hidePhoneNumber); }
    public static void toggleSaveInBots() { init(); saveInBots = !saveInBots; putBoolean("saveInBots", saveInBots); }
    public static void toggleSaveReadDate() { init(); saveReadDate = !saveReadDate; putBoolean("saveReadDate", saveReadDate); }
    public static void toggleSaveLastOnline() { init(); saveLastOnline = !saveLastOnline; putBoolean("saveLastOnline", saveLastOnline); }
    public static void setMediaMaxSizeMb(int mb) { init(); mediaMaxSizeMb = mb; putInt("mediaMaxSizeMb", mb); }

    // ------------------------------------------------------- срок хранения истории

    /** сколько дней держать сохранённые удалённые/изменённые; 0 — бессрочно */
    public static int getHistoryKeepDays() { init(); return historyKeepDays; }

    public static void setHistoryKeepDays(int days) {
        init();
        historyKeepDays = days;
        putInt("historyKeepDays", days);
    }

    // ------------------------------------------------------- резервная копия настроек

    /** все настройки Pengram одним JSON — чтобы перенести на другое устройство */
    public static String exportToJson() {
        init();
        final SharedPreferences p = prefs();
        if (p == null) {
            return null;
        }
        try {
            final org.json.JSONObject root = new org.json.JSONObject();
            root.put("pengram", 1);
            root.put("version", BuildVars.BUILD_VERSION_STRING);
            final org.json.JSONObject values = new org.json.JSONObject();
            for (java.util.Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                final Object v = entry.getValue();
                if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof String) {
                    values.put(entry.getKey(), v);
                }
            }
            root.put("values", values);
            return root.toString(1);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** принимает JSON из exportToJson(); false — если это не наши настройки */
    public static boolean importFromJson(String json) {
        if (json == null) {
            return false;
        }
        final SharedPreferences p = prefs();
        if (p == null) {
            return false;
        }
        try {
            final org.json.JSONObject root = new org.json.JSONObject(json.trim());
            if (!root.has("pengram") || !root.has("values")) {
                return false;
            }
            final org.json.JSONObject values = root.getJSONObject("values");
            final SharedPreferences.Editor editor = p.edit();
            editor.clear();
            final java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                final Object v = values.get(key);
                if (v instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) v);
                } else if (v instanceof Integer) {
                    editor.putInt(key, (Integer) v);
                } else if (v instanceof Long) {
                    editor.putInt(key, (int) (long) (Long) v);
                } else if (v instanceof Double) {
                    editor.putInt(key, (int) Math.round((Double) v));
                } else if (v instanceof String) {
                    editor.putString(key, (String) v);
                }
            }
            editor.apply();
            reload();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** вернуть всё к заводским значениям форка */
    public static void resetAll() {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().clear().apply();
        }
        reload();
    }

    /** перечитать настройки из хранилища (после импорта/сброса) */
    private static void reload() {
        synchronized (PengramConfig.class) {
            loaded = false;
        }
        synchronized (boolCache) {
            boolCache.clear();
        }
        init();
    }

    public static boolean toggleBoolean(String key) {
        init();
        switch (key) {
            case "hideMenuNewGroup": hideMenuNewGroup = !hideMenuNewGroup; putBoolean(key, hideMenuNewGroup); return hideMenuNewGroup;
            case "hideMenuSavedMessages": hideMenuSavedMessages = !hideMenuSavedMessages; putBoolean(key, hideMenuSavedMessages); return hideMenuSavedMessages;
            case "hideMenuSettings": hideMenuSettings = !hideMenuSettings; putBoolean(key, hideMenuSettings); return hideMenuSettings;
            case "hideMenuTheme": hideMenuTheme = !hideMenuTheme; putBoolean(key, hideMenuTheme); return hideMenuTheme;
            case "hideChatSearch": hideChatSearch = !hideChatSearch; putBoolean(key, hideChatSearch); return hideChatSearch;
            case "hideChatTranslate": hideChatTranslate = !hideChatTranslate; putBoolean(key, hideChatTranslate); return hideChatTranslate;
            case "hideChatClearHistory": hideChatClearHistory = !hideChatClearHistory; putBoolean(key, hideChatClearHistory); return hideChatClearHistory;
            case "hideChatWallpaper": hideChatWallpaper = !hideChatWallpaper; putBoolean(key, hideChatWallpaper); return hideChatWallpaper;
            case "hideChatShortcut": hideChatShortcut = !hideChatShortcut; putBoolean(key, hideChatShortcut); return hideChatShortcut;
            case "hideChatReport": hideChatReport = !hideChatReport; putBoolean(key, hideChatReport); return hideChatReport;
            case "hideChatCall": hideChatCall = !hideChatCall; putBoolean(key, hideChatCall); return hideChatCall;
            case "hideChatAutoDelete": hideChatAutoDelete = !hideChatAutoDelete; putBoolean(key, hideChatAutoDelete); return hideChatAutoDelete;
        }
        return false;
    }

    public static void setVoiceChangerMode(int mode) { init(); voiceChangerMode = mode; putInt("voiceChangerMode", mode); }
    public static void setVoiceChangerPitch(int semitones) { init(); voiceChangerPitch = semitones; putInt("voiceChangerPitch", semitones); }
    public static int getVoiceChangerMode() { init(); return voiceChangerMode; }
    public static int getVoiceChangerPitch() { init(); return voiceChangerPitch; }

    public static void setSpeedBoost(int level) { init(); speedBoost = level; putInt("speedBoost", level); }
    public static int getSpeedBoost() { init(); return speedBoost; }
    /** множитель параллельных запросов: 0 — как в оригинале */
    public static int getSpeedBoostMultiplier() {
        init();
        switch (speedBoost) {
            case BOOST_FAST: return 2;
            case BOOST_EXTREME: return 4;
            default: return 1;
        }
    }

    public static boolean isHidingPhoneNumber() { init(); return hidePhoneNumber; }
    public static boolean isSavingInBots() { init(); return saveInBots; }
    public static boolean isSavingReadDate() { init(); return saveReadDate; }
    public static boolean isSavingLastOnline() { init(); return saveLastOnline; }
    public static int getMediaMaxSizeMb() { init(); return mediaMaxSizeMb; }

    public static boolean isHistoryRowVisible() {
        return false; // строку истории в профиле убрали — всё живёт в настройках Pengram
    }

    private static boolean isHistoryRowVisibleLegacy() {
        init();
        return historyRowInProfile && (saveDeleted || saveEdited);
    }

    public static void setIdFormat(int format) {
        init();
        idFormat = format;
        putInt("idFormat", format);
    }

    public static int getIdFormat() {
        init();
        return idFormat;
    }

    /**
     * ID так, как его нужно показать: Telegram API — как есть,
     * Bot API — с минусом у групп и -100 у супергрупп/каналов.
     */
    public static String formatId(long id, boolean isChat, boolean isChannelOrSupergroup) {
        if (getIdFormat() == ID_FORMAT_BOT && isChat && id > 0) {
            return isChannelOrSupergroup ? ("-100" + id) : ("-" + id);
        }
        return String.valueOf(id);
    }

    public static void setIdStyle(int style) {
        init();
        idStyle = style;
        putInt("idStyle", style);
    }

    // ---- то, что спрашивает остальной код ----

    public static boolean isHidingOnline() {
        init();
        return ghostMode && hideOnline;
    }

    public static boolean isNotSendingRead() {
        init();
        return ghostMode && dontSendRead;
    }

    public static boolean isNotSendingTyping() {
        init();
        return ghostMode && dontSendTyping;
    }

    public static boolean isNotSendingStoryViews() {
        init();
        return ghostMode && dontSendStoryViews;
    }

    public static int getIdStyle() {
        init();
        return idStyle;
    }

    public static boolean isIdVisible() {
        return getIdFormat() != ID_FORMAT_HIDE && getIdStyle() != ID_STYLE_OFF;
    }

    public static boolean isIdSeparateRow() {
        if (!isIdVisible()) {
            return false;
        }
        final int s = getIdStyle();
        return s == ID_STYLE_ROW || s == ID_STYLE_ROW_DC;
    }

    public static boolean isShowingDc() {
        return isIdVisible() && getIdStyle() == ID_STYLE_ROW_DC;
    }
}
