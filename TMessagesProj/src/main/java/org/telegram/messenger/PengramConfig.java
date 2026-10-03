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
    /** скрыть круглую кнопку «Написать» в списке чатов */
    public static final String KEY_HIDE_WRITE_BUTTON = "hideWriteButton";
    /** пункт «Копировать ID сообщения» в меню сообщения */
    public static final String KEY_MENU_COPY_MESSAGE_ID = "menuCopyMessageId";
    /** пункт «Сохранить в Избранное» в меню сообщения */
    public static final String KEY_MENU_SAVE_TO_SAVED = "menuSaveToSaved";

    public static final String KEY_HIDE_TAIL = "hideBubbleTail";
    /** не писать «изменено» у времени (метка остаётся) */
    public static final String KEY_HIDE_EDITED_LABEL = "hideEditedLabel";
    /** тап по календарику показывает текст вместо окна */
    public static final String KEY_REG_TAP_TEXT = "regTapText";
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
    private static final java.util.HashMap<String, Integer> intCache = new java.util.HashMap<>();

    /**
     * Чтение числовой настройки без похода в SharedPreferences.
     * Настройки читаются в анимациях по многу раз за кадр, поэтому держим их в памяти.
     */
    public static int getIntCached(String key, int def) {
        init();
        synchronized (intCache) {
            Integer cached = intCache.get(key);
            if (cached != null) {
                return cached;
            }
            SharedPreferences p = prefs();
            final int value = p != null ? p.getInt(key, def) : def;
            intCache.put(key, value);
            return value;
        }
    }

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
        if (KEY_ANTICRASH.equals(key)) {
            PengramAntiCrash.invalidateEnabled();   // его читают в onDraw, там свой кэш
        }
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

    public static final int DIALOG_AVATAR_CIRCLE = 0;
    public static final int DIALOG_AVATAR_ROUNDED = 1;
    public static final int DIALOG_AVATAR_SQUARE = 2;
    public static final int DIALOG_AVATAR_COUNT = 3;

    /** форма миниатюр в списке чатов */
    public static int getDialogAvatarShape() {
        final int value = getIntCached("dialogAvatarShape", DIALOG_AVATAR_CIRCLE);
        return value < 0 || value >= DIALOG_AVATAR_COUNT ? DIALOG_AVATAR_CIRCLE : value;
    }

    public static void setDialogAvatarShape(int value) {
        putInt("dialogAvatarShape", value < 0 || value >= DIALOG_AVATAR_COUNT ? DIALOG_AVATAR_CIRCLE : value);
    }

    public static int getDialogAvatarShapeName(int value) {
        switch (value) {
            case DIALOG_AVATAR_ROUNDED: return org.telegram.messenger.R.string.PengramCoverRounded;
            case DIALOG_AVATAR_SQUARE: return org.telegram.messenger.R.string.PengramCoverSquare;
            case DIALOG_AVATAR_CIRCLE:
            default: return org.telegram.messenger.R.string.PengramCoverCircle;
        }
    }

    /** радиус миниатюры в списке чатов: круг — как было, иначе наш вариант */
    public static int dialogAvatarRadius(int defaultRadius) {
        switch (getDialogAvatarShape()) {
            case DIALOG_AVATAR_ROUNDED: return Math.min(defaultRadius, AndroidUtilities.dp(13));
            case DIALOG_AVATAR_SQUARE: return 0;
            default: return defaultRadius;
        }
    }

    // ------------------------------------------------------------ тексты песен

    public static final int LYRICS_SOURCE_AUTO = 0;
    public static final int LYRICS_SOURCE_FILE = 1;
    public static final int LYRICS_SOURCE_LRCLIB = 2;
    public static final int LYRICS_SOURCE_MUSIXMATCH = 3;
    public static final int LYRICS_SOURCE_GENIUS = 4;
    public static final int LYRICS_SOURCE_COUNT = 5;

    /** откуда брать текст песни */
    public static int getLyricsSource() {
        if (isLyricsAuto()) {
            return LYRICS_SOURCE_AUTO;   // в авто-режиме спрашиваем сразу всех
        }
        final int value = getIntCached("lyricsSource", LYRICS_SOURCE_AUTO);
        return value < 0 || value >= LYRICS_SOURCE_COUNT ? LYRICS_SOURCE_AUTO : value;
    }

    /** выбранный вручную источник (для экрана настроек) */
    public static int getLyricsSourceRaw() {
        final int value = getIntCached("lyricsSource", LYRICS_SOURCE_AUTO);
        return value < 0 || value >= LYRICS_SOURCE_COUNT ? LYRICS_SOURCE_AUTO : value;
    }

    public static void setLyricsSource(int value) {
        putInt("lyricsSource", value < 0 || value >= LYRICS_SOURCE_COUNT ? LYRICS_SOURCE_AUTO : value);
    }

    public static int getLyricsSourceName(int value) {
        switch (value) {
            case LYRICS_SOURCE_FILE: return R.string.PengramLyricsSourceFile;
            case LYRICS_SOURCE_LRCLIB: return R.string.PengramLyricsSourceLrclib;
            case LYRICS_SOURCE_MUSIXMATCH: return R.string.PengramLyricsSourceMusixmatch;
            case LYRICS_SOURCE_GENIUS: return R.string.PengramLyricsSourceGenius;
            default: return R.string.PengramLyricsSourceAuto;
        }
    }

    public static int getLyricsSourceInfo(int value) {
        switch (value) {
            case LYRICS_SOURCE_FILE: return R.string.PengramLyricsSourceFileInfo;
            case LYRICS_SOURCE_LRCLIB: return R.string.PengramLyricsSourceLrclibInfo;
            case LYRICS_SOURCE_MUSIXMATCH: return R.string.PengramLyricsSourceMusixmatchInfo;
            case LYRICS_SOURCE_GENIUS: return R.string.PengramLyricsSourceGeniusInfo;
            default: return R.string.PengramLyricsSourceAutoInfo;
        }
    }

    /** общий сдвиг текста относительно звука, мс (−5000…5000) */
    public static int getLyricsOffset() {
        return Math.max(-5000, Math.min(5000, getIntCached("lyricsOffset", 0)));
    }

    public static void setLyricsOffset(int value) {
        putInt("lyricsOffset", Math.max(-5000, Math.min(5000, value)));
    }

    /** подгонять таймкоды под реальную длительность трека (ускоренные версии) */
    // ------------------------------------------------------- эффекты удаления

    public static final int DELETE_EFFECT_NONE = 0;
    public static final int DELETE_EFFECT_DUST = 1;
    public static final int DELETE_EFFECT_BURN = 2;
    public static final int DELETE_EFFECT_SHATTER = 3;
    public static final int DELETE_EFFECT_COLLAPSE = 4;
    public static final int DELETE_EFFECT_DISSOLVE = 5;
    public static final int DELETE_EFFECT_SLIDE = 6;
    public static final int DELETE_EFFECT_IMPLODE = 7;
    public static final int DELETE_EFFECT_PIXELATE = 8;
    public static final int DELETE_EFFECT_PUZZLE = 9;
    public static final int DELETE_EFFECT_SHARDS = 10;
    public static final int DELETE_EFFECT_TNT = 11;
    public static final int DELETE_EFFECT_PORTAL = 12;
    public static final int DELETE_EFFECT_GHOST = 13;
    public static final int DELETE_EFFECT_GLITCH = 14;
    public static final int DELETE_EFFECT_SWEEP = 15;
    public static final int DELETE_EFFECT_COUNT = 16;

    public static int getDeleteEffect() {
        final int value = getIntCached("deleteEffect", DELETE_EFFECT_DUST);
        return value < 0 || value >= DELETE_EFFECT_COUNT ? DELETE_EFFECT_DUST : value;
    }

    public static void setDeleteEffect(int value) {
        putInt("deleteEffect", value < 0 || value >= DELETE_EFFECT_COUNT ? DELETE_EFFECT_DUST : value);
    }

    /** играть эффект и когда сообщение удалил собеседник */
    public static final String KEY_DELETE_EFFECT_INCOMING = "deleteEffectIncoming";

    public static boolean isDeleteEffectIncoming() { return getBool(KEY_DELETE_EFFECT_INCOMING, true); }

    public static int getDeleteEffectName(int value) {
        switch (value) {
            case DELETE_EFFECT_DUST: return org.telegram.messenger.R.string.PengramDeleteEffectDust;
            case DELETE_EFFECT_BURN: return org.telegram.messenger.R.string.PengramDeleteEffectBurn;
            case DELETE_EFFECT_SHATTER: return org.telegram.messenger.R.string.PengramDeleteEffectShatter;
            case DELETE_EFFECT_COLLAPSE: return org.telegram.messenger.R.string.PengramDeleteEffectCollapse;
            case DELETE_EFFECT_DISSOLVE: return org.telegram.messenger.R.string.PengramDeleteEffectDissolve;
            case DELETE_EFFECT_SLIDE: return org.telegram.messenger.R.string.PengramDeleteEffectSlide;
            case DELETE_EFFECT_IMPLODE: return org.telegram.messenger.R.string.PengramDeleteEffectImplode;
            case DELETE_EFFECT_PIXELATE: return org.telegram.messenger.R.string.PengramDeleteEffectPixelate;
            case DELETE_EFFECT_PUZZLE: return org.telegram.messenger.R.string.PengramDeleteEffectPuzzle;
            case DELETE_EFFECT_SHARDS: return org.telegram.messenger.R.string.PengramDeleteEffectShards;
            case DELETE_EFFECT_TNT: return org.telegram.messenger.R.string.PengramDeleteEffectTnt;
            case DELETE_EFFECT_PORTAL: return org.telegram.messenger.R.string.PengramDeleteEffectPortal;
            case DELETE_EFFECT_GHOST: return org.telegram.messenger.R.string.PengramDeleteEffectGhost;
            case DELETE_EFFECT_GLITCH: return org.telegram.messenger.R.string.PengramDeleteEffectGlitch;
            case DELETE_EFFECT_SWEEP: return org.telegram.messenger.R.string.PengramDeleteEffectSweep;
            default: return org.telegram.messenger.R.string.PengramDeleteEffectNone;
        }
    }

    public static int getDeleteEffectInfo(int value) {
        switch (value) {
            case DELETE_EFFECT_DUST: return org.telegram.messenger.R.string.PengramDeleteEffectDustInfo;
            case DELETE_EFFECT_BURN: return org.telegram.messenger.R.string.PengramDeleteEffectBurnInfo;
            case DELETE_EFFECT_SHATTER: return org.telegram.messenger.R.string.PengramDeleteEffectShatterInfo;
            case DELETE_EFFECT_COLLAPSE: return org.telegram.messenger.R.string.PengramDeleteEffectCollapseInfo;
            case DELETE_EFFECT_DISSOLVE: return org.telegram.messenger.R.string.PengramDeleteEffectDissolveInfo;
            case DELETE_EFFECT_SLIDE: return org.telegram.messenger.R.string.PengramDeleteEffectSlideInfo;
            case DELETE_EFFECT_IMPLODE: return org.telegram.messenger.R.string.PengramDeleteEffectImplodeInfo;
            case DELETE_EFFECT_PIXELATE: return org.telegram.messenger.R.string.PengramDeleteEffectPixelateInfo;
            case DELETE_EFFECT_PUZZLE: return org.telegram.messenger.R.string.PengramDeleteEffectPuzzleInfo;
            case DELETE_EFFECT_SHARDS: return org.telegram.messenger.R.string.PengramDeleteEffectShardsInfo;
            case DELETE_EFFECT_TNT: return org.telegram.messenger.R.string.PengramDeleteEffectTntInfo;
            case DELETE_EFFECT_PORTAL: return org.telegram.messenger.R.string.PengramDeleteEffectPortalInfo;
            case DELETE_EFFECT_GHOST: return org.telegram.messenger.R.string.PengramDeleteEffectGhostInfo;
            case DELETE_EFFECT_GLITCH: return org.telegram.messenger.R.string.PengramDeleteEffectGlitchInfo;
            case DELETE_EFFECT_SWEEP: return org.telegram.messenger.R.string.PengramDeleteEffectSweepInfo;
            default: return org.telegram.messenger.R.string.PengramDeleteEffectNoneInfo;
        }
    }

    // ------------------------------------------------------------ антикраш

    /** защита от сообщений, собранных специально чтобы уронить клиент */
    public static final String KEY_ANTICRASH = "antiCrash";
    /** показывать плашку на месте обезвреженного куска */
    public static final String KEY_ANTICRASH_MARK = "antiCrashMark";

    public static boolean isAntiCrash() { return getBool(KEY_ANTICRASH, true); }

    public static boolean isAntiCrashMark() { return getBool(KEY_ANTICRASH_MARK, true); }

    public static int getAntiCrashBlocked() { return getIntCached("antiCrashBlocked", 0); }

    public static void setAntiCrashBlocked(int value) { putInt("antiCrashBlocked", Math.max(0, value)); }

    /** всё, что связано с текстами, приложение делает само */
    public static final String KEY_LYRICS_AUTO = "lyricsAuto";

    public static boolean isLyricsAuto() { return getBool(KEY_LYRICS_AUTO, true); }

    public static final String KEY_LYRICS_STRETCH = "lyricsStretch";
    public static boolean isLyricsStretch() { return isLyricsAuto() || getBool(KEY_LYRICS_STRETCH, true); }

    /** плавная подсветка между обновлениями прогресса плеера */
    public static final String KEY_LYRICS_SMOOTH = "lyricsSmooth";
    public static boolean isLyricsSmooth() { return isLyricsAuto() || getBool(KEY_LYRICS_SMOOTH, true); }

    // ------------------------------------------------------------ бегущая строка

    /** строка уезжает вбок, когда не помещается */
    public static final String KEY_HEADER_LYRICS_MARQUEE = "headerLyricsMarquee";
    public static final String KEY_HEADER_LYRICS_BOLD = "headerLyricsBold";
    public static final String KEY_HEADER_LYRICS_WORDS = "headerLyricsWords";
    public static final String KEY_HEADER_LYRICS_ARTIST = "headerLyricsArtist";

    public static int getHeaderLyricsSpeed() {
        return Math.max(40, Math.min(250, getIntCached("headerLyricsSpeed", 100)));
    }

    public static void setHeaderLyricsSpeed(int value) {
        putInt("headerLyricsSpeed", Math.max(40, Math.min(250, value)));
    }

    /** класть сохранённые копии в системную галерею (по умолчанию нет) */
    public static final String KEY_MEDIA_GALLERY = "mediaToGallery";
    public static boolean isMediaToGallery() { return getBool(KEY_MEDIA_GALLERY, false); }

    public static final String KEY_HEADER_LYRICS = "headerLyrics";

    /** строка песни прямо в шапке чата, поверх мини-плеера */
    public static boolean isHeaderLyrics() { return getBool(KEY_HEADER_LYRICS, true); }

    /** анимация строки в шапке: -1 — как в плеере */
    public static int getHeaderLyricsAnim() {
        final int value = getIntCached("headerLyricsAnim", -1);
        return value < -1 || value >= LYRICS_ANIM_COUNT ? -1 : value;
    }

    public static void setHeaderLyricsAnim(int value) {
        putInt("headerLyricsAnim", value < -1 || value >= LYRICS_ANIM_COUNT ? -1 : value);
    }

    public static int getHeaderLyricsSize() {
        return Math.max(11, Math.min(20, getIntCached("headerLyricsSize", 14)));
    }

    public static void setHeaderLyricsSize(int value) {
        putInt("headerLyricsSize", Math.max(11, Math.min(20, value)));
    }

    public static final String KEY_KEEP_FORMATTING = "keepFormatting";

    /** продолжать оформление (жирный, курсив…) при наборе и при правке сообщения */
    public static boolean isKeepFormatting() { return getBool(KEY_KEEP_FORMATTING, true); }
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
        synchronized (intCache) {
            intCache.put(key, value);
        }
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
            case REG_ICON_PENGUIN: return org.telegram.messenger.R.drawable.pengram_penguin_glyph;
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

    // ------------------------------- пункты экрана «Настройки» -------------------------------

    public static final int SETTINGS_ITEM_NOTIFICATIONS = 1;
    public static final int SETTINGS_ITEM_PENGRAM = 2;
    public static final int SETTINGS_ITEM_ACCOUNT = 3;
    public static final int SETTINGS_ITEM_CHAT = 4;
    public static final int SETTINGS_ITEM_PRIVACY = 5;
    public static final int SETTINGS_ITEM_DATA = 6;
    public static final int SETTINGS_ITEM_FOLDERS = 7;
    public static final int SETTINGS_ITEM_DEVICES = 8;
    public static final int SETTINGS_ITEM_POWER = 9;
    public static final int SETTINGS_ITEM_LANGUAGE = 10;

    /** порядок по умолчанию: Pengram, уведомления, дальше как в Telegram */
    private static final int[] SETTINGS_ITEMS_DEFAULT = new int[]{
            SETTINGS_ITEM_PENGRAM,
            SETTINGS_ITEM_NOTIFICATIONS,
            SETTINGS_ITEM_ACCOUNT,
            SETTINGS_ITEM_CHAT,
            SETTINGS_ITEM_PRIVACY,
            SETTINGS_ITEM_DATA,
            SETTINGS_ITEM_FOLDERS,
            SETTINGS_ITEM_DEVICES,
            SETTINGS_ITEM_POWER,
            SETTINGS_ITEM_LANGUAGE
    };

    /** сохранённый порядок пунктов экрана «Настройки» (всегда полный список) */
    /** версия раскладки «Настроек»: растёт, когда меняется порядок по умолчанию */
    private static final int SETTINGS_ORDER_VERSION = 2;

    public static java.util.ArrayList<Integer> getSettingsOrder() {
        init();
        final java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        if (getIntCached("settingsOrderVersion", 1) < SETTINGS_ORDER_VERSION) {
            // раскладка по умолчанию поменялась — старый сохранённый порядок больше не актуален
            putString("settingsOrder", "");
            putInt("settingsOrderVersion", SETTINGS_ORDER_VERSION);
        }
        final String saved = prefs().getString("settingsOrder", "");
        if (saved != null && saved.length() > 0) {
            for (String part : saved.split(",")) {
                try {
                    final int id = Integer.parseInt(part.trim());
                    boolean known = false;
                    for (int def : SETTINGS_ITEMS_DEFAULT) {
                        if (def == id) {
                            known = true;
                            break;
                        }
                    }
                    if (known && !result.contains(id)) {
                        result.add(id);
                    }
                } catch (Exception ignore) {
                }
            }
        }
        for (int def : SETTINGS_ITEMS_DEFAULT) {
            if (!result.contains(def)) {
                result.add(def);
            }
        }
        return result;
    }

    public static void setSettingsOrder(java.util.List<Integer> order) {
        init();
        final StringBuilder sb = new StringBuilder();
        for (int id : order) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        putString("settingsOrder", sb.toString());
        putInt("settingsOrderVersion", SETTINGS_ORDER_VERSION);
    }

    private static String settingsItemKey(int id) {
        return "settingsHidden_" + id;
    }

    public static boolean isSettingsItemHidden(int id) {
        return getBool(settingsItemKey(id), false);
    }

    public static void setSettingsItemHidden(int id, boolean hidden) {
        setBool(settingsItemKey(id), hidden);
    }

    public static int getHiddenSettingsItemsCount() {
        int count = 0;
        for (int id : SETTINGS_ITEMS_DEFAULT) {
            if (isSettingsItemHidden(id)) {
                count++;
            }
        }
        return count;
    }

    /** строка-название пункта экрана «Настройки» */
    public static int getSettingsItemTitle(int id) {
        switch (id) {
            case SETTINGS_ITEM_NOTIFICATIONS: return org.telegram.messenger.R.string.SettingsNotifications;
            case SETTINGS_ITEM_PENGRAM: return org.telegram.messenger.R.string.PengramSettings;
            case SETTINGS_ITEM_ACCOUNT: return org.telegram.messenger.R.string.SettingsAccount;
            case SETTINGS_ITEM_CHAT: return org.telegram.messenger.R.string.SettingsChat;
            case SETTINGS_ITEM_PRIVACY: return org.telegram.messenger.R.string.SettingsPrivacySecurity;
            case SETTINGS_ITEM_DATA: return org.telegram.messenger.R.string.SettingsData;
            case SETTINGS_ITEM_FOLDERS: return org.telegram.messenger.R.string.SettingsFolders;
            case SETTINGS_ITEM_DEVICES: return org.telegram.messenger.R.string.SettingsDevices;
            case SETTINGS_ITEM_POWER: return org.telegram.messenger.R.string.SettingsPowerSaving;
            case SETTINGS_ITEM_LANGUAGE:
            default: return org.telegram.messenger.R.string.SettingsLanguage;
        }
    }

    public static int getSettingsItemIcon(int id) {
        switch (id) {
            case SETTINGS_ITEM_NOTIFICATIONS: return org.telegram.messenger.R.drawable.settings_sounds;
            case SETTINGS_ITEM_PENGRAM: return org.telegram.messenger.R.drawable.settings_features;
            case SETTINGS_ITEM_ACCOUNT: return org.telegram.messenger.R.drawable.settings_account;
            case SETTINGS_ITEM_CHAT: return org.telegram.messenger.R.drawable.settings_chat;
            case SETTINGS_ITEM_PRIVACY: return org.telegram.messenger.R.drawable.settings_privacy;
            case SETTINGS_ITEM_DATA: return org.telegram.messenger.R.drawable.settings_data;
            case SETTINGS_ITEM_FOLDERS: return org.telegram.messenger.R.drawable.settings_folders;
            case SETTINGS_ITEM_DEVICES: return org.telegram.messenger.R.drawable.settings_devices;
            case SETTINGS_ITEM_POWER: return org.telegram.messenger.R.drawable.settings_power;
            case SETTINGS_ITEM_LANGUAGE:
            default: return org.telegram.messenger.R.drawable.settings_language;
        }
    }

    /** «зальгофицированный» пример текста — чтобы было видно, что именно вырезает фильтр */
    public static CharSequence zalgoSample(CharSequence text) {
        if (text == null) {
            return "";
        }
        final char[] above = new char[]{'\u0301', '\u0308', '\u030A', '\u0352', '\u0360'};
        final char[] below = new char[]{'\u0323', '\u0330', '\u032C'};
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ++i) {
            final char c = text.charAt(i);
            sb.append(c);
            if (Character.isLetterOrDigit(c)) {
                sb.append(above[i % above.length]);
                sb.append(above[(i + 2) % above.length]);
                sb.append(below[i % below.length]);
            }
        }
        return sb.toString();
    }

    /** зальгофицировать только одно слово внутри строки (для подписей в настройках) */
    public static CharSequence zalgoWord(CharSequence text, String word) {
        if (text == null) {
            return "";
        }
        if (word == null || word.length() == 0) {
            return text;
        }
        final String src = text.toString();
        final int index = src.indexOf(word);
        if (index < 0) {
            return text;
        }
        return src.substring(0, index) + zalgoSample(word) + src.substring(index + word.length());
    }

    /** подходит ли ID под поисковый запрос (для поиска людей и чатов по номеру) */
    public static boolean idMatches(long id, String query) {
        if (query == null) {
            return false;
        }
        final String q = query.trim();
        if (q.length() < 3) {
            return false;
        }
        for (int a = 0; a < q.length(); ++a) {
            final char c = q.charAt(a);
            if (a == 0 && (c == '-' || c == '+')) {
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
        }
        String needle = q;
        if (needle.startsWith("-") || needle.startsWith("+")) {
            needle = needle.substring(1);
        }
        if (needle.length() < 3) {
            return false;
        }
        if (needle.startsWith("100") && needle.length() > 3) {
            // Bot API id канала: -100xxxxxxxxxx
            if (String.valueOf(Math.abs(id)).startsWith(needle.substring(3))) {
                return true;
            }
        }
        return String.valueOf(Math.abs(id)).startsWith(needle);
    }

    /** разобрать строку в peer id: принимаем 123, -100123, @name отбрасываем */
    public static long parsePeerId(String query) {
        if (query == null) {
            return 0;
        }
        String q = query.trim();
        if (q.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(q);
        } catch (Throwable ignore) {
        }
        return 0;
    }

    // ------------------------------- пункты меню чата -------------------------------

    public static final int CHAT_ITEM_PENGRAM = 1;
    public static final int CHAT_ITEM_TO_BEGINNING = 2;
    public static final int CHAT_ITEM_COPY_ID = 3;
    public static final int CHAT_ITEM_SAVED_MEDIA = 4;
    public static final int CHAT_ITEM_VIEW_DELETED = 5;

    private static final int[] CHAT_ITEMS_DEFAULT = new int[]{
            CHAT_ITEM_VIEW_DELETED, CHAT_ITEM_TO_BEGINNING, CHAT_ITEM_COPY_ID, CHAT_ITEM_SAVED_MEDIA
    };

    /** где живёт пункт: в самом меню «три точки» или внутри острова Pengram */
    public static final int CHAT_PLACE_MAIN = 0;
    public static final int CHAT_PLACE_ISLAND = 1;

    /** куда помещён пункт меню чата */
    public static int getChatItemPlacement(int id) {
        init();
        final int def = id == CHAT_ITEM_VIEW_DELETED ? CHAT_PLACE_ISLAND : CHAT_PLACE_MAIN;
        final int value = getIntCached("chatItemPlace_" + id, def);
        return value == CHAT_PLACE_ISLAND ? CHAT_PLACE_ISLAND : CHAT_PLACE_MAIN;
    }

    public static void setChatItemPlacement(int id, int place) {
        putInt("chatItemPlace_" + id, place == CHAT_PLACE_ISLAND ? CHAT_PLACE_ISLAND : CHAT_PLACE_MAIN);
    }

    /** пункты, которые нужно показать в острове Pengram (в выбранном порядке) */
    public static java.util.ArrayList<Integer> getChatIslandItems() {
        final java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        for (int id : getChatItemsOrder()) {
            if (!isChatItemHidden(id) && getChatItemPlacement(id) == CHAT_PLACE_ISLAND) {
                result.add(id);
            }
        }
        return result;
    }

    /** пункты, которые идут прямо в «три точки» чата */
    public static java.util.ArrayList<Integer> getChatMainItems() {
        final java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        for (int id : getChatItemsOrder()) {
            if (!isChatItemHidden(id) && getChatItemPlacement(id) == CHAT_PLACE_MAIN) {
                result.add(id);
            }
        }
        return result;
    }

    /** порядок наших пунктов в «трёх точках» чата */
    public static java.util.ArrayList<Integer> getChatItemsOrder() {
        init();
        final java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        final String saved = prefs().getString("chatItemsOrder", "");
        if (saved != null && !saved.isEmpty()) {
            for (String part : saved.split(",")) {
                try {
                    final int id = Integer.parseInt(part.trim());
                    for (int known : CHAT_ITEMS_DEFAULT) {
                        if (known == id && !result.contains(id)) {
                            result.add(id);
                            break;
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
        }
        for (int known : CHAT_ITEMS_DEFAULT) {
            if (!result.contains(known)) {
                result.add(known);
            }
        }
        return result;
    }

    public static void setChatItemsOrder(java.util.List<Integer> order) {
        if (order == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (int a = 0; a < order.size(); ++a) {
            if (a > 0) sb.append(',');
            sb.append(order.get(a));
        }
        putString("chatItemsOrder", sb.toString());
    }

    public static boolean isChatItemHidden(int id) {
        return getBool("chatItemHidden_" + id, id == CHAT_ITEM_COPY_ID || id == CHAT_ITEM_SAVED_MEDIA);
    }

    public static void setChatItemHidden(int id, boolean hidden) {
        setBool("chatItemHidden_" + id, hidden);
    }

    public static int getHiddenChatItemsCount() {
        int count = 0;
        for (int id : CHAT_ITEMS_DEFAULT) {
            if (isChatItemHidden(id)) {
                count++;
            }
        }
        return count;
    }

    public static int getChatItemTitle(int id) {
        switch (id) {
            case CHAT_ITEM_TO_BEGINNING: return org.telegram.messenger.R.string.PengramJumpToBeginning;
            case CHAT_ITEM_COPY_ID: return org.telegram.messenger.R.string.PengramCopyChatId;
            case CHAT_ITEM_SAVED_MEDIA: return org.telegram.messenger.R.string.PengramChatItemSavedMedia;
            case CHAT_ITEM_VIEW_DELETED: return org.telegram.messenger.R.string.PengramViewDeleted;
            case CHAT_ITEM_PENGRAM:
            default: return org.telegram.messenger.R.string.PengramMenuTitle;
        }
    }

    public static int getChatItemIcon(int id) {
        switch (id) {
            case CHAT_ITEM_TO_BEGINNING: return org.telegram.messenger.R.drawable.msg_go_up;
            case CHAT_ITEM_COPY_ID: return org.telegram.messenger.R.drawable.msg_copy;
            case CHAT_ITEM_SAVED_MEDIA: return org.telegram.messenger.R.drawable.msg_saved;
            case CHAT_ITEM_VIEW_DELETED: return org.telegram.messenger.R.drawable.msg_delete;
            case CHAT_ITEM_PENGRAM:
            default: return org.telegram.messenger.R.drawable.msg_viewchats;
        }
    }

    // ------------------------------- скины пингвина -------------------------------

    public static final int SKIN_NONE = 0;
    public static final int SKIN_SANTA = 1;
    public static final int SKIN_SCARF = 2;
    public static final int SKIN_CAP = 3;
    public static final int SKIN_GLASSES = 4;
    public static final int SKIN_CROWN = 5;
    public static final int SKIN_HEADPHONES = 6;
    public static final int SKIN_BOWTIE = 7;
    public static final int SKIN_WIZARD = 8;
    public static final int SKIN_COUNT = 9;

    public static int getPenguinSkin() {
        init();
        final int skin = getIntCached("penguinSkin", SKIN_NONE);
        return skin < 0 || skin >= SKIN_COUNT ? SKIN_NONE : skin;
    }

    public static void setPenguinSkin(int skin) {
        putInt("penguinSkin", skin < 0 || skin >= SKIN_COUNT ? SKIN_NONE : skin);
    }

    public static int getPenguinSkinName(int skin) {
        switch (skin) {
            case SKIN_SANTA: return org.telegram.messenger.R.string.PengramSkinSanta;
            case SKIN_SCARF: return org.telegram.messenger.R.string.PengramSkinScarf;
            case SKIN_CAP: return org.telegram.messenger.R.string.PengramSkinCap;
            case SKIN_GLASSES: return org.telegram.messenger.R.string.PengramSkinGlasses;
            case SKIN_CROWN: return org.telegram.messenger.R.string.PengramSkinCrown;
            case SKIN_HEADPHONES: return org.telegram.messenger.R.string.PengramSkinHeadphones;
            case SKIN_BOWTIE: return org.telegram.messenger.R.string.PengramSkinBowtie;
            case SKIN_WIZARD: return org.telegram.messenger.R.string.PengramSkinWizard;
            case SKIN_NONE:
            default: return org.telegram.messenger.R.string.PengramSkinNone;
        }
    }

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
    public static final int MENU_ITEM_READ_ALL = 12;
    public static final int MENU_ITEM_ARCHIVE = 13;

    public static final int[] MENU_ITEMS_DEFAULT = new int[]{
            MENU_ITEM_PENGRAM, MENU_ITEM_GHOST, MENU_ITEM_THEME, MENU_ITEM_READ_ALL,
            MENU_ITEM_NEW_GROUP, MENU_ITEM_NEW_CHANNEL, MENU_ITEM_SAVED, MENU_ITEM_ARCHIVE,
            MENU_ITEM_CONTACTS, MENU_ITEM_CALLS,
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
            case MENU_ITEM_ARCHIVE:
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
            case MENU_ITEM_READ_ALL: return org.telegram.messenger.R.string.MarkAllAsRead;
            case MENU_ITEM_ARCHIVE: return org.telegram.messenger.R.string.ArchivedChats;
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
            case MENU_ITEM_READ_ALL: return org.telegram.messenger.R.drawable.msg_markread;
            case MENU_ITEM_ARCHIVE: return org.telegram.messenger.R.drawable.msg_archive;
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
    public static boolean isHidingWriteButton() { return getBool(KEY_HIDE_WRITE_BUTTON, false); }
    public static boolean isMenuCopyMessageId() { return getBool(KEY_MENU_COPY_MESSAGE_ID, true); }
    public static boolean isMenuSaveToSaved() { return getBool(KEY_MENU_SAVE_TO_SAVED, true); }
    public static boolean isHidingEditedLabel() { return getBool(KEY_HIDE_EDITED_LABEL, false); }
    /**
     * Прятать слово «изменено» имеет смысл только когда вместо него рисуется значок.
     * Если значок не выбран — ведём себя как обычный Telegram и пишем «изменено».
     */
    public static boolean shouldHideEditedLabel() { return isHidingEditedLabel() && getEditedMarkIconRes() != 0; }
    /** по нажатию на календарик показывать текст, а не открывать окно */
    public static boolean isRegTapText() { return getBool(KEY_REG_TAP_TEXT, true); }
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
        folder = sanitizeFolder(folder);
        mediaFolder = folder;
        putString("mediaFolder", folder);
    }

    /**
     * Папка для сохранённых медиа уходит в MediaStore как RELATIVE_PATH:
     * «..», двоеточия и прочие спецсимволы там роняют запись, причём молча.
     * Поэтому чистим каждый сегмент и оставляем только безопасные символы.
     */
    public static String sanitizeFolder(String folder) {
        if (folder == null) {
            return DEFAULT_MEDIA_FOLDER;
        }
        final StringBuilder out = new StringBuilder();
        for (String part : folder.split("/")) {
            final String cleaned = part.trim()
                    .replaceAll("[^\\p{L}\\p{N} ._()\\-]", "")
                    .replaceAll("^\\.+", "")
                    .trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(cleaned.length() > 48 ? cleaned.substring(0, 48) : cleaned);
            if (out.length() > 120) {
                break;
            }
        }
        return out.length() == 0 ? DEFAULT_MEDIA_FOLDER : out.toString();
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
        synchronized (intCache) {
            intCache.clear();
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

    // ------------------------------- выделение и пересылка -------------------------------

    /** варианты лимита выделения сообщений */
    public static final int[] SELECTION_LIMITS = new int[]{100, 200, 300, 500, 1000, 1500, 2000, 3000};

    /** сколько сообщений разрешаем выделить за раз (по умолчанию как в оригинале — 100) */
    public static int getSelectionLimit() {
        init();
        final int value = getIntCached("selectionLimit", 100);
        return value < 100 ? 100 : Math.min(value, 3000);
    }

    public static void setSelectionLimit(int value) {
        putInt("selectionLimit", value < 100 ? 100 : Math.min(value, 3000));
    }

    /**
     * Пачки по 100 сообщений уходят на сервер с небольшой задержкой друг за другом,
     * иначе большие пересылки ловят флуд-вейт и подвешивают интерфейс.
     */
    public static int forwardChunkDelay(int chunkIndex) {
        if (chunkIndex <= 0) {
            return 0;
        }
        return Math.min(chunkIndex * 260, 30000);
    }

    // ------------------------------- аватарки авторов в чате -------------------------------

    public static final int AVATAR_POS_LEFT = 0;
    public static final int AVATAR_POS_RIGHT = 1;
    public static final int AVATAR_POS_HIDE = 2;
    /** маленькая аватарка прямо перед ником внутри сообщения */
    public static final int AVATAR_POS_BEFORE_NAME = 3;
    /** маленькая аватарка сразу после ника */
    public static final int AVATAR_POS_AFTER_NAME = 4;
    public static final int AVATAR_POS_COUNT = 5;

    /** порядок, в котором позиции показываются в визуальном редакторе */
    public static final int[] AVATAR_POS_ORDER = new int[]{
            AVATAR_POS_LEFT, AVATAR_POS_BEFORE_NAME, AVATAR_POS_AFTER_NAME, AVATAR_POS_RIGHT, AVATAR_POS_HIDE
    };

    public static int getGroupAvatarPos() {
        init();
        final int value = getIntCached("groupAvatarPos", AVATAR_POS_LEFT);
        return value < 0 || value >= AVATAR_POS_COUNT ? AVATAR_POS_LEFT : value;
    }

    public static void setGroupAvatarPos(int value) {
        putInt("groupAvatarPos", value < 0 || value >= AVATAR_POS_COUNT ? AVATAR_POS_LEFT : value);
    }

    /** рисуем ли маленькую аватарку рядом с ником */
    public static boolean isInlineAvatar() {
        final int pos = getGroupAvatarPos();
        return pos == AVATAR_POS_BEFORE_NAME || pos == AVATAR_POS_AFTER_NAME;
    }

    public static int getGroupAvatarPosName(int value) {
        switch (value) {
            case AVATAR_POS_RIGHT: return org.telegram.messenger.R.string.PengramAvatarPosRight;
            case AVATAR_POS_HIDE: return org.telegram.messenger.R.string.PengramAvatarPosHide;
            case AVATAR_POS_BEFORE_NAME: return org.telegram.messenger.R.string.PengramAvatarPosBeforeName;
            case AVATAR_POS_AFTER_NAME: return org.telegram.messenger.R.string.PengramAvatarPosAfterName;
            case AVATAR_POS_LEFT:
            default: return org.telegram.messenger.R.string.PengramAvatarPosLeft;
        }
    }

    // ------------------------------- экран настроек -------------------------------

    /** Pengram отдельной плашкой над всеми пунктами настроек */
    public static boolean isPengramCardOnTop() {
        return getBool(KEY_PENGRAM_CARD, true);
    }

    public static final String KEY_PENGRAM_CARD = "pengramCardOnTop";

    // ------------------------------- музыкальный плеер -------------------------------

    public static final String KEY_NEW_PLAYER = "newPlayer";
    public static final String KEY_PLAYER_BLUR = "playerBlur";
    public static final String KEY_PLAYER_ROTATE = "playerRotate";
    public static final String KEY_PLAYER_WAVE = "playerWave";
    public static final String KEY_LYRICS_AUTOSCROLL = "lyricsAutoScroll";
    public static final String KEY_LYRICS_BOLD = "lyricsBold";
    public static final String KEY_LYRICS_SHADOW = "lyricsShadow";

    /** стили плеера */
    public static final int PLAYER_STYLE_ORIGINAL = 0;
    public static final int PLAYER_STYLE_FULL = 1;
    public static final int PLAYER_STYLE_LYRICS = 2;
    public static final int PLAYER_STYLE_COMPACT = 3;
    public static final int PLAYER_STYLE_MINI_LYRICS = 4;
    public static final int PLAYER_STYLE_COUNT = 5;

    public static int getPlayerStyle() {
        init();
        // по умолчанию — наш вариант с текстом песни: караоке «буква в букву»
        final int value = getIntCached("playerStyle", PLAYER_STYLE_LYRICS);
        return value < 0 || value >= PLAYER_STYLE_COUNT ? PLAYER_STYLE_FULL : value;
    }

    public static void setPlayerStyle(int value) {
        putInt("playerStyle", value < 0 || value >= PLAYER_STYLE_COUNT ? PLAYER_STYLE_FULL : value);
    }

    public static int getPlayerStyleName(int value) {
        switch (value) {
            case PLAYER_STYLE_ORIGINAL: return org.telegram.messenger.R.string.PengramPlayerStyleOriginal;
            case PLAYER_STYLE_LYRICS: return org.telegram.messenger.R.string.PengramPlayerStyleLyrics;
            case PLAYER_STYLE_COMPACT: return org.telegram.messenger.R.string.PengramPlayerStyleCompact;
            case PLAYER_STYLE_MINI_LYRICS: return org.telegram.messenger.R.string.PengramPlayerStyleMini;
            case PLAYER_STYLE_FULL:
            default: return org.telegram.messenger.R.string.PengramPlayerStyleFull;
        }
    }

    public static int getPlayerStyleInfo(int value) {
        switch (value) {
            case PLAYER_STYLE_ORIGINAL: return org.telegram.messenger.R.string.PengramPlayerStyleOriginalInfo;
            case PLAYER_STYLE_LYRICS: return org.telegram.messenger.R.string.PengramPlayerStyleLyricsInfo;
            case PLAYER_STYLE_COMPACT: return org.telegram.messenger.R.string.PengramPlayerStyleCompactInfo;
            case PLAYER_STYLE_MINI_LYRICS: return org.telegram.messenger.R.string.PengramPlayerStyleMiniInfo;
            case PLAYER_STYLE_FULL:
            default: return org.telegram.messenger.R.string.PengramPlayerStyleFullInfo;
        }
    }

    /** показывает ли выбранный стиль текст песни */
    public static boolean playerStyleHasLyrics(int style) {
        return style == PLAYER_STYLE_FULL || style == PLAYER_STYLE_LYRICS || style == PLAYER_STYLE_MINI_LYRICS;
    }

    public static boolean isNewPlayer() {
        return getPlayerStyle() != PLAYER_STYLE_ORIGINAL;
    }

    /** анимации текста песни */
    public static final int LYRICS_ANIM_KARAOKE = 0;
    public static final int LYRICS_ANIM_LETTERS = 1;
    public static final int LYRICS_ANIM_WAVE = 2;
    public static final int LYRICS_ANIM_BOUNCE = 3;
    public static final int LYRICS_ANIM_TYPEWRITER = 4;
    public static final int LYRICS_ANIM_PULSE = 5;
    public static final int LYRICS_ANIM_NEON = 6;
    public static final int LYRICS_ANIM_RAINBOW = 7;
    public static final int LYRICS_ANIM_BLUR = 8;
    public static final int LYRICS_ANIM_NONE = 9;
    public static final int LYRICS_ANIM_GRADIENT = 10;
    public static final int LYRICS_ANIM_SHAKE = 11;
    public static final int LYRICS_ANIM_DROP = 12;
    public static final int LYRICS_ANIM_FLIP = 13;
    public static final int LYRICS_ANIM_SWEEP = 14;
    public static final int LYRICS_ANIM_MAGNIFY = 15;
    public static final int LYRICS_ANIM_COUNT = 16;

    public static int getLyricsAnim() {
        init();
        final int value = getIntCached("lyricsAnim", LYRICS_ANIM_KARAOKE);
        return value < 0 || value >= LYRICS_ANIM_COUNT ? LYRICS_ANIM_KARAOKE : value;
    }

    public static void setLyricsAnim(int value) {
        putInt("lyricsAnim", value < 0 || value >= LYRICS_ANIM_COUNT ? LYRICS_ANIM_KARAOKE : value);
    }

    public static int getLyricsAnimName(int value) {
        switch (value) {
            case LYRICS_ANIM_LETTERS: return org.telegram.messenger.R.string.PengramLyricsAnimLetters;
            case LYRICS_ANIM_WAVE: return org.telegram.messenger.R.string.PengramLyricsAnimWave;
            case LYRICS_ANIM_BOUNCE: return org.telegram.messenger.R.string.PengramLyricsAnimBounce;
            case LYRICS_ANIM_TYPEWRITER: return org.telegram.messenger.R.string.PengramLyricsAnimTypewriter;
            case LYRICS_ANIM_PULSE: return org.telegram.messenger.R.string.PengramLyricsAnimPulse;
            case LYRICS_ANIM_NEON: return org.telegram.messenger.R.string.PengramLyricsAnimNeon;
            case LYRICS_ANIM_RAINBOW: return org.telegram.messenger.R.string.PengramLyricsAnimRainbow;
            case LYRICS_ANIM_BLUR: return org.telegram.messenger.R.string.PengramLyricsAnimBlur;
            case LYRICS_ANIM_NONE: return org.telegram.messenger.R.string.PengramLyricsAnimNone;
            case LYRICS_ANIM_GRADIENT: return org.telegram.messenger.R.string.PengramLyricsAnimGradient;
            case LYRICS_ANIM_SHAKE: return org.telegram.messenger.R.string.PengramLyricsAnimShake;
            case LYRICS_ANIM_DROP: return org.telegram.messenger.R.string.PengramLyricsAnimDrop;
            case LYRICS_ANIM_FLIP: return org.telegram.messenger.R.string.PengramLyricsAnimFlip;
            case LYRICS_ANIM_SWEEP: return org.telegram.messenger.R.string.PengramLyricsAnimSweep;
            case LYRICS_ANIM_MAGNIFY: return org.telegram.messenger.R.string.PengramLyricsAnimMagnify;
            case LYRICS_ANIM_KARAOKE:
            default: return org.telegram.messenger.R.string.PengramLyricsAnimKaraoke;
        }
    }

    /** выравнивание текста песни */
    public static final int LYRICS_ALIGN_LEFT = 0;
    public static final int LYRICS_ALIGN_CENTER = 1;
    public static final int LYRICS_ALIGN_RIGHT = 2;

    public static int getLyricsAlign() {
        init();
        final int value = getIntCached("lyricsAlign", LYRICS_ALIGN_LEFT);
        return value < 0 || value > LYRICS_ALIGN_RIGHT ? LYRICS_ALIGN_LEFT : value;
    }

    public static void setLyricsAlign(int value) {
        putInt("lyricsAlign", value < 0 || value > LYRICS_ALIGN_RIGHT ? LYRICS_ALIGN_LEFT : value);
    }

    public static int getLyricsAlignName(int value) {
        switch (value) {
            case LYRICS_ALIGN_CENTER: return org.telegram.messenger.R.string.PengramLyricsAlignCenter;
            case LYRICS_ALIGN_RIGHT: return org.telegram.messenger.R.string.PengramLyricsAlignRight;
            case LYRICS_ALIGN_LEFT:
            default: return org.telegram.messenger.R.string.PengramLyricsAlignLeft;
        }
    }

    /** размер текста песни, dp */
    public static int getLyricsSize() {
        init();
        final int value = getIntCached("lyricsSize", 22);
        return value < 14 ? 14 : Math.min(value, 40);
    }

    public static void setLyricsSize(int value) {
        putInt("lyricsSize", value < 14 ? 14 : Math.min(value, 40));
    }

    /** прозрачность неактивных строк, % */
    public static int getLyricsDim() {
        init();
        final int value = getIntCached("lyricsDim", 35);
        return value < 5 ? 5 : Math.min(value, 100);
    }

    public static void setLyricsDim(int value) {
        putInt("lyricsDim", value < 5 ? 5 : Math.min(value, 100));
    }

    /** скорость анимации текста, % от обычной */
    public static int getLyricsSpeed() {
        init();
        final int value = getIntCached("lyricsSpeed", 100);
        return value < 25 ? 25 : Math.min(value, 300);
    }

    public static void setLyricsSpeed(int value) {
        putInt("lyricsSpeed", value < 25 ? 25 : Math.min(value, 300));
    }

    /** фон плеера */
    public static final int PLAYER_BG_COVER = 0;
    public static final int PLAYER_BG_GRADIENT = 1;
    public static final int PLAYER_BG_DARK = 2;
    public static final int PLAYER_BG_THEME = 3;

    public static int getPlayerBg() {
        init();
        final int value = getIntCached("playerBg", PLAYER_BG_COVER);
        return value < 0 || value > PLAYER_BG_THEME ? PLAYER_BG_COVER : value;
    }

    public static void setPlayerBg(int value) {
        putInt("playerBg", value < 0 || value > PLAYER_BG_THEME ? PLAYER_BG_COVER : value);
    }

    public static int getPlayerBgName(int value) {
        switch (value) {
            case PLAYER_BG_GRADIENT: return org.telegram.messenger.R.string.PengramPlayerBgGradient;
            case PLAYER_BG_DARK: return org.telegram.messenger.R.string.PengramPlayerBgDark;
            case PLAYER_BG_THEME: return org.telegram.messenger.R.string.PengramPlayerBgTheme;
            case PLAYER_BG_COVER:
            default: return org.telegram.messenger.R.string.PengramPlayerBgCover;
        }
    }

    /** форма обложки */
    public static final int COVER_SHAPE_ROUNDED = 0;
    public static final int COVER_SHAPE_CIRCLE = 1;
    public static final int COVER_SHAPE_SQUARE = 2;

    public static int getCoverShape() {
        init();
        final int value = getIntCached("coverShape", COVER_SHAPE_ROUNDED);
        return value < 0 || value > COVER_SHAPE_SQUARE ? COVER_SHAPE_ROUNDED : value;
    }

    public static void setCoverShape(int value) {
        putInt("coverShape", value < 0 || value > COVER_SHAPE_SQUARE ? COVER_SHAPE_ROUNDED : value);
    }

    public static int getCoverShapeName(int value) {
        switch (value) {
            case COVER_SHAPE_CIRCLE: return org.telegram.messenger.R.string.PengramCoverCircle;
            case COVER_SHAPE_SQUARE: return org.telegram.messenger.R.string.PengramCoverSquare;
            case COVER_SHAPE_ROUNDED:
            default: return org.telegram.messenger.R.string.PengramCoverRounded;
        }
    }
}
