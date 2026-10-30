package org.telegram.ui;

import org.telegram.messenger.R;

/**
 * Поисковый индекс настроек Pengram: функции, разделы и группы для полного пути.
 *
 * Файл СГЕНЕРИРОВАН скриптом scripts/gen-search-index.py по экрану настроек.
 * Руками не править — добавил настройку, перегенерировал:
 *   python3 scripts/gen-search-index.py
 */
public final class PengramSearchIndex {

    private PengramSearchIndex() {}

    /** {строка, раздел, группа, внешний экран (0=нет), собственное описание (0=нет)} */
    public static final int[][] ITEMS = {
            {R.string.PengramAboutVersion, PengramSettingsActivity.SECTION_ABOUT, R.string.PengramAboutBuildHeader, 0, 0},
            {R.string.PengramAboutBase, PengramSettingsActivity.SECTION_ABOUT, R.string.PengramAboutBuildHeader, 0, 0},
            {R.string.PengramAboutPackage, PengramSettingsActivity.SECTION_ABOUT, R.string.PengramAboutBuildHeader, 0, 0},
            {R.string.PengramAboutDevice, PengramSettingsActivity.SECTION_ABOUT, R.string.PengramAboutDeviceHeader, 0, 0},
            {R.string.PengramAboutSystem, PengramSettingsActivity.SECTION_ABOUT, R.string.PengramAboutDeviceHeader, 0, 0},
            {R.string.PengramAboutAbi, PengramSettingsActivity.SECTION_ABOUT, R.string.PengramAboutDeviceHeader, 0, 0},
            {R.string.PengramAboutCopy, PengramSettingsActivity.SECTION_ABOUT, R.string.PengramAboutDeviceHeader, 0, R.string.PengramAboutCopyInfo},
            {R.string.PengramResetAll, PengramSettingsActivity.SECTION_ABOUT, R.string.PengramResetHeader, 0, R.string.PengramResetAllInfo},
            {R.string.PengramSectionAppearance, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupLook, 0, 0},
            {R.string.PengramSectionCustom, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupLook, 0, 0},
            {R.string.PengramSectionGhost, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupPrivacy, 0, 0},
            {R.string.PengramSectionSpy, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupPrivacy, 0, 0},
            {R.string.PengramSectionProfile, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupPrivacy, 0, 0},
            {R.string.PengramSectionChats, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupChats, 0, 0},
            {R.string.PengramInputToolsSection, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupChats, 0, 0},
            {R.string.PengramSectionAI, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupChats, 0, 0},
            {R.string.PengramSectionPlayer, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupMedia, 0, 0},
            {R.string.PengramSectionMedia, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupMedia, 0, 0},
            {R.string.PengramSectionFreedom, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupExtra, 0, 0},
            {R.string.PengramSectionGeneral, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupExtra, 0, 0},
            {R.string.PengramEditor, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupExtra, 0, 0},
            {R.string.PengramAbout, PengramSettingsActivity.SECTION_ROOT, R.string.PengramGroupExtra, 0, 0},
            {R.string.PengramNoRounding, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramGeneralHeader, 0, R.string.PengramNoRoundingInfo},
            {R.string.PengramTimeSeconds, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramGeneralHeader, 0, R.string.PengramTimeSecondsInfo},
            {R.string.PengramVibration, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramGeneralHeader, 0, R.string.PengramVibrationInfo},
            {R.string.PengramShowAccountsSettings, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramGeneralHeader, 0, R.string.PengramShowAccountsSettingsInfo},
            {R.string.PengramDeviceSpoof, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramDeviceHeader, 0, R.string.PengramDeviceSpoofInfo},
            {R.string.PengramDeviceModel, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramDeviceHeader, 0, 0},
            {R.string.PengramDeviceVersion, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramDeviceHeader, 0, R.string.PengramDeviceVersionInfo},
            {R.string.PengramDeviceVersionSpoof, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramDeviceHeader, 0, 0},
            {R.string.PengramDeviceRandom, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramDeviceHeader, 0, 0},
            {R.string.PengramDeviceApply, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramDeviceHeader, 0, 0},
            {R.string.DirectShare, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramSystemHeader, 0, R.string.DirectShareInfo},
            {R.string.PengramSortContacts, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramSystemHeader, 0, 0},
            {R.string.PengramStickerOrder, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramSystemHeader, 0, 0},
            {R.string.PengramOpenById, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramToolsHeader, 0, R.string.PengramOpenByIdInfo},
            {R.string.PengramExportChatSetting, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramBackupHeader, 0, 0},
            {R.string.PengramBackupExport, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramBackupHeader, 0, 0},
            {R.string.PengramBackupImport, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramBackupHeader, 0, 0},
            {R.string.PengramBackupReset, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramBackupHeader, 0, 0},
            {R.string.PengramSessionExportSetting, PengramSettingsActivity.SECTION_GENERAL, R.string.PengramLogoutHeader, 0, R.string.PengramSessionExportSettingInfo},
            {R.string.PengramDeletedMark, PengramSettingsActivity.SECTION_CUSTOM, R.string.PengramDeletedLookHeader, 0, 0},
            {R.string.PengramEditedMark, PengramSettingsActivity.SECTION_CUSTOM, R.string.PengramDeletedLookHeader, 0, 0},
            {R.string.PengramFadeDeleted, PengramSettingsActivity.SECTION_CUSTOM, R.string.PengramDeletedLookHeader, 0, 0},
            {R.string.PengramDeleteEffect, PengramSettingsActivity.SECTION_CUSTOM, R.string.PengramDeleteEffectHeader, 0, 0},
            {R.string.PengramDeleteEffectIncoming, PengramSettingsActivity.SECTION_CUSTOM, R.string.PengramDeleteEffectHeader, 0, R.string.PengramDeleteEffectIncomingInfo},
            {R.string.PengramIdFormatPicker, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdHeader, 0, 0},
            {R.string.PengramIdPlacementPicker, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdHeader, 0, 0},
            {R.string.PengramIdCopyOnTap, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdHeader, 0, R.string.PengramIdCopyOnTapInfo},
            {R.string.PengramIdFormatHide, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdHeader, 0, R.string.PengramIdFormatHideInfo},
            {R.string.PengramIdFormatTelegram, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdHeader, 0, 0},
            {R.string.PengramIdFormatBot, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdHeader, 0, 0},
            {R.string.PengramIdStyleOff, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdStyleHeader, 0, R.string.PengramIdStyleOffInfo},
            {R.string.PengramIdStyleRow, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdStyleHeader, 0, R.string.PengramIdStyleRowInfo},
            {R.string.PengramIdStyleRowDc, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdStyleHeader, 0, R.string.PengramIdStyleRowDcInfo},
            {R.string.PengramIdStyleInline, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramIdStyleHeader, 0, R.string.PengramIdStyleInlineInfo},
            {R.string.PengramOriginalName, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramOriginalNameHeader, 0, R.string.PengramOriginalNameInfo},
            {R.string.PengramRegStyle, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramRegHeader, 0, 0},
            {R.string.PengramRegPlace, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramRegHeader, 0, R.string.PengramRegPlaceSubtitle},
            {R.string.PengramRegIcon, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramRegHeader, 0, R.string.PengramRegIconInfo},
            {R.string.PengramRegTapText, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramRegHeader, 0, R.string.PengramRegTapTextInfo},
            {R.string.PengramHidePhone, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramPrivacyHeader, 0, R.string.PengramHidePhoneInfo},
            {R.string.PengramSharePhoneMode, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramPrivacyHeader, 0, R.string.PengramSharePhoneModeInfo},
            {R.string.PengramClassicProfileMusic, PengramSettingsActivity.SECTION_PROFILE, R.string.PengramProfileMusicHeader, 0, R.string.PengramClassicProfileMusicInfo},
            {R.string.PengramSpyAccount, PengramSettingsActivity.SECTION_HISTORY, 0, 0, 0},
            {R.string.PengramSpyOwnSettings, PengramSettingsActivity.SECTION_HISTORY, 0, 0, 0},
            {R.string.PengramHistorySaveMessages, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryHeader, 0, 0},
            {R.string.PengramHistorySaveDeleted, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryHeader, 0, 0},
            {R.string.PengramHistorySaveEdited, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryHeader, 0, 0},
            {R.string.PengramHistorySaveEditedMedia, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryHeader, 0, 0},
            {R.string.PengramHistorySaveOutgoing, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryHeader, 0, 0},
            {R.string.PengramSaveInBots, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryHeader, 0, 0},
            {R.string.PengramSaveReadDate, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramTrackHeader, 0, 0},
            {R.string.PengramSaveLastOnline, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramTrackHeader, 0, 0},
            {R.string.PengramSaveForMyselfOption, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryHeader, 0, 0},
            {R.string.PengramSaveForMyselfDefault, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryHeader, 0, 0},
            {R.string.PengramLocalKeep, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramLocalKeepHeader, 0, R.string.PengramLocalKeepInfo},
            {R.string.PengramProfileHistory, PengramSettingsActivity.SECTION_HISTORY, 0, 0, 0},
            {R.string.PengramDeletedReplyQuote, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramInChatHeader, 0, 0},
            {R.string.PengramDeletedReplySign, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramInChatHeader, 0, 0},
            {R.string.PengramKeepDays, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryStorage, 0, 0},
            {R.string.PengramHistoryMax, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryStorage, 0, 0},
            {R.string.PengramHistoryClearButton, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramHistoryStorage, 0, 0},
            {R.string.PengramMediaSave, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramMediaHeader, 0, 0},
            {R.string.PengramMediaFolder, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramMediaHeader, 0, 0},
            {R.string.PengramMediaPattern, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramMediaHeader, 0, 0},
            {R.string.PengramMediaGallery, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramMediaHeader, 0, R.string.PengramMediaGalleryInfo},
            {R.string.PengramMediaLimitValue, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramMediaLimitHeader, 0, 0},
            {R.string.PengramMediaClear, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramMediaLimitHeader, 0, 0},
            {R.string.PengramGhostMode, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostHeader, 0, 0},
            {R.string.PengramGhostDontRead, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostHeader, 0, 0},
            {R.string.PengramGhostDontStory, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostHeader, 0, 0},
            {R.string.PengramGhostHideOnline, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostHeader, 0, 0},
            {R.string.PengramGhostDontType, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostHeader, 0, 0},
            {R.string.PengramGhostAutoOffline, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostHeader, 0, 0},
            {R.string.PengramGhostDontSendVoiceRead, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostHeader, 0, 0},
            {R.string.PengramGhostDontSendReactions, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostHeader, 0, 0},
            {R.string.PengramGhostReadOnReply, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostExtraHeader, 0, R.string.PengramGhostReadOnReplyInfo},
            {R.string.PengramGhostStoriesWarn, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostExtraHeader, 0, R.string.PengramGhostStoriesWarnInfo},
            {R.string.PengramGhostSendDelay, PengramSettingsActivity.SECTION_GHOST, R.string.PengramGhostExtraHeader, 0, R.string.PengramGhostSendDelayInfo},
            {R.string.PengramBypass, PengramSettingsActivity.SECTION_FREEDOM, 0, 0, R.string.PengramBypassInfo},
            {R.string.PengramAllowScreenshots, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramFreedomHeader, 0, 0},
            {R.string.PengramNoScreenshotNotify, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramFreedomHeader, 0, 0},
            {R.string.PengramAllowForwards, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramFreedomHeader, 0, 0},
            {R.string.PengramKeepOnce, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramFreedomHeader, 0, 0},
            {R.string.PengramAntiCrash, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramAntiCrashHeader, 0, R.string.PengramAntiCrashInfo},
            {R.string.PengramAntiCrashMark, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramAntiCrashHeader, 0, 0},
            {R.string.PengramAntiCrashJournalEnabled, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramAntiCrashHeader, 0, 0},
            {R.string.PengramAntiCrashStats, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramAntiCrashHeader, 0, 0},
            {R.string.PengramAntiCrashLog, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramAntiCrashHeader, 0, 0},
            {R.string.PengramCrashAutoCopy, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramCrashReports, 0, 0},
            {R.string.PengramCrashReports, PengramSettingsActivity.SECTION_FREEDOM, 0, 0, R.string.PengramCrashReportsInfo},
            {R.string.PengramHideAds, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramAdsHeader, 0, 0},
            {R.string.PengramLocalPremium, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramPremiumHeader, 0, 0},
            {R.string.PengramLocalPremiumStatus, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramPremiumHeader, 0, 0},
            {R.string.PengramNotificationIcon, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramBackgroundHeader, 0, 0},
            {R.string.PengramBackgroundMode, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramBackgroundHeader, 0, 0},
            {R.string.PengramBackgroundSilent, PengramSettingsActivity.SECTION_FREEDOM, R.string.PengramBackgroundHeader, 0, 0},
            {R.string.PengramMD3, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, R.string.PengramMD3Info},
            {R.string.PengramMD3Dialogs, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3Tonal, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3NoDividers, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3SearchBar, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3SearchShadow, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3Sheets, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3Alerts, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3Menus, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3Settings, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3Screens, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMD3Fab, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMD3Header, 0, 0},
            {R.string.PengramMonet, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMonetHeader, 0, R.string.PengramMonetInfo},
            {R.string.PengramMonetMessages, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMonetHeader, 0, 0},
            {R.string.PengramMonetStrength, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramMonetHeader, 0, 0},
            {R.string.PengramQuickTiles, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramQuickTilesHeader, 0, R.string.PengramQuickTilesInfo},
            {R.string.PengramFont, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramAppearanceHeader, 0, 0},
            {R.string.PengramAvatarShape, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramAppearanceHeader, 0, R.string.PengramAvatarShapeInfo},
            {R.string.PengramConstructor, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramAppearanceHeader, 0, R.string.PengramConstructorInfo},
            {R.string.PengramCheckmarkStyle, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramStatusStylesHeader, 0, 0},
            {R.string.PengramOnlineBadgeStyle, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramStatusStylesHeader, 0, 0},
            {R.string.PengramTitleText, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, 0},
            {R.string.PengramTitleCustom, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, 0},
            {R.string.PengramTitleCenter, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, 0},
            {R.string.PengramSnow, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, R.string.PengramSnowInfo},
            {R.string.PengramParticleType, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, 0},
            {R.string.PengramParticleCount, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, 0},
            {R.string.PengramParticleOpacity, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, 0},
            {R.string.PengramParticleSpeed, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, 0},
            {R.string.PengramParticleRotation, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTitleHeader, 0, 0},
            {R.string.PengramTabBarSize, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramTabBarGlass, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, R.string.PengramTabBarGlassInfo},
            {R.string.PengramInputGlass, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramHeaderGlass, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramChatHeaderGlass, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramPlayerHeaderGlass, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramChatPinnedGlass, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramGlassOpacity, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramGlassThickness, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramGlassIntensity, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramGlassGlow, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, 0},
            {R.string.PengramGlassSheen, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, R.string.PengramGlassSheenInfo},
            {R.string.PengramGlassDrag, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, R.string.PengramGlassDragInfo},
            {R.string.PengramGlassRim, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTabBarHeader, 0, R.string.PengramGlassRimInfo},
            {R.string.BubbleRadius, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTextHeader, 0, 0},
            {R.string.LargeEmoji, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTextHeader, 0, 0},
            {R.string.PengramSystemEmoji, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTextHeader, 0, 0},
            {R.string.LoopAnimatedStickers, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramTextHeader, 0, R.string.LoopAnimatedStickersInfo},
            {R.string.LiteOptionsStickers, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.LiteOptionsEmoji, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.EnableAnimations, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.PengramChatEnterAnim, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, R.string.PengramChatEnterAnimInfo},
            {R.string.PengramChatEnterStyle, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.PengramCylinderScroll, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, R.string.PengramCylinderScrollInfo},
            {R.string.PengramCylinderToBars, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, R.string.PengramCylinderToBarsInfo},
            {R.string.PengramCylinderStrength, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.PengramCylinderJuicyPreset, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.PengramLinkAssistant, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, R.string.PengramLinkAssistantInfo},
            {R.string.PengramChatBlur, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.PengramSpoilerEffect, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.PengramThanosEffect, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.LiteOptionsParticles, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.LiteOptionsCalls, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.PengramChatBackgroundAnim, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramEffectsHeader, 0, 0},
            {R.string.PengramTabletMode, PengramSettingsActivity.SECTION_APPEARANCE, R.string.PengramInterfaceHeader, 0, 0},
            {R.string.PengramBoostOff, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramBoostHeader, 0, 0},
            {R.string.PengramBoostFast, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramBoostHeader, 0, 0},
            {R.string.PengramBoostExtreme, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramBoostHeader, 0, 0},
            {R.string.PengramVoicePitch, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, 0},
            {R.string.PengramVoiceFormant, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, 0},
            {R.string.PengramVoiceEcho, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, 0},
            {R.string.PengramVoiceStudioDrive, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, 0},
            {R.string.PengramVoiceStudioRobot, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, 0},
            {R.string.PengramVoiceStudioMix, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, 0},
            {R.string.PengramVoiceProfileCopy, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, 0},
            {R.string.PengramVoiceProfileImport, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, 0},
            {R.string.PengramVoiceGainPreset, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceGainHeader, 0, 0},
            {R.string.PengramVoiceOverdrive, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceGainHeader, 0, 0},
            {R.string.PengramVoiceGainMaximum, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceGainHeader, 0, 0},
            {R.string.PengramVoiceAmbience, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceAmbienceHeader, 0, R.string.PengramVoiceAmbienceInfo},
            {R.string.PengramVoiceAmbienceLevel, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceAmbienceHeader, 0, 0},
            {R.string.PengramVoiceAmbienceDuck, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceAmbienceHeader, 0, R.string.PengramVoiceAmbienceDuckInfo},
            {R.string.PengramVoiceAmbienceStopPreview, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceAmbienceHeader, 0, 0},
            {R.string.PengramVoiceMessages, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceSources, 0, 0},
            {R.string.PengramVoiceRound, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceSources, 0, 0},
            {R.string.PengramVoiceCalls, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceSources, 0, 0},
            {R.string.PengramVoiceGate, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceSources, 0, 0},
            {R.string.EnableStreaming, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramStreamHeader, 0, 0},
            {R.string.PengramStreamAllVideo, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramStreamHeader, 0, 0},
            {R.string.PengramStreamMkv, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramStreamHeader, 0, 0},
            {R.string.PengramSaveStream, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramStreamHeader, 0, 0},
            {R.string.LiteOptionsAutoplayGifs, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAutoplayHeader, 0, 0},
            {R.string.LiteOptionsAutoplayVideo, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAutoplayHeader, 0, 0},
            {R.string.NextMediaTap, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAutoplayHeader, 0, R.string.NextMediaTapInfo},
            {R.string.PengramSortFilesByName, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAutoplayHeader, 0, 0},
            {R.string.PengramInAppCamera, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramCameraHeader, 0, 0},
            {R.string.PengramBigCameraRound, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramCameraHeader, 0, 0},
            {R.string.PengramDisableVoiceProximity, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAudioHeader, 0, R.string.PengramDisableVoiceProximityInfo},
            {R.string.RaiseToSpeak, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAudioHeader, 0, R.string.RaiseToSpeakInfo},
            {R.string.RaiseToListen, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAudioHeader, 0, R.string.RaiseToListenInfo},
            {R.string.PengramPauseOnRecord, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAudioHeader, 0, 0},
            {R.string.PengramNoiseSuppression, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAudioHeader, 0, 0},
            {R.string.PengramVoiceEffectsOff, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAudioHeader, 0, 0},
            {R.string.PauseMusicOnMedia, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramAudioHeader, 0, 0},
            {R.string.PengramPlayerLook, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerHeader, 0, R.string.PengramPlayerLookInfo},
            {R.string.PengramPlayerBg, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerHeader, 0, 0},
            {R.string.PengramPlayerAccent, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerHeader, 0, R.string.PengramPlayerAccentInfo},
            {R.string.PengramCoverShape, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerHeader, 0, 0},
            {R.string.PengramPlayerBlur, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerHeader, 0, 0},
            {R.string.PengramEmptyCover, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerHeader, 0, R.string.PengramEmptyCoverInfo},
            {R.string.PengramPlayerPenguinDance, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerHeader, 0, R.string.PengramPlayerPenguinDanceInfo},
            {R.string.PengramPlayerBeat, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerHeader, 0, R.string.PengramPlayerBeatInfo},
            {R.string.PengramPlayerSwipe, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerSwipeHeader, 0, R.string.PengramPlayerSwipeInfo},
            {R.string.PengramMiniPlayerQueueBtn, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramMiniPlayerQueueHeader, 0, R.string.PengramMiniPlayerQueueBtnInfo},
            {R.string.PengramPlayerFavorites, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerFavoritesHeader, 0, R.string.PengramPlayerFavoritesInfo},
            {R.string.PengramPlayerFavShowFullPlayer, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerFavoritesHeader, 0, R.string.PengramPlayerFavShowFullPlayerInfo},
            {R.string.PengramPlayerFavShowMiniPlayer, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerFavoritesHeader, 0, R.string.PengramPlayerFavShowMiniPlayerInfo},
            {R.string.PengramPlayerFavShowChatList, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerFavoritesHeader, 0, R.string.PengramPlayerFavShowChatListInfo},
            {R.string.PengramPlayerFavShowNotification, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerFavoritesHeader, 0, R.string.PengramPlayerFavShowNotificationInfo},
            {R.string.PengramPlayerFavTarget, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerFavoritesHeader, 0, 0},
            {R.string.PengramPlayerFavCaption, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerFavoritesHeader, 0, R.string.PengramPlayerFavCaptionInfo},
            {R.string.PengramPlayerFavOpenVault, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramPlayerFavoritesHeader, 0, 0},
            {R.string.PengramTrackForwardButton, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramTrackForwardHeader, 0, R.string.PengramTrackForwardButtonInfo},
            {R.string.PengramMusicForwardClean, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramTrackForwardHeader, 0, R.string.PengramMusicForwardCleanInfo},
            {R.string.PengramTrackForwardMode, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramTrackForwardHeader, 0, 0},
            {R.string.PengramTrackForwardCaption, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramTrackForwardHeader, 0, R.string.PengramTrackForwardCaptionInfo},
            {R.string.PengramTrackCardFormat, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramTrackCardSettings, 0, 0},
            {R.string.PengramTrackCardLink, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramTrackCardSettings, 0, R.string.PengramTrackCardLinkInfo},
            {R.string.PengramTrackCardBrand, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramTrackCardSettings, 0, 0},
            {R.string.PengramSmartTrackRecognition, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramMusicMetaHeader, 0, R.string.PengramSmartTrackRecognitionInfo},
            {R.string.PengramMusicSmartArtist, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramMusicMetaHeader, 0, R.string.PengramMusicSmartArtistInfo},
            {R.string.PengramLyricsSection, PengramSettingsActivity.SECTION_PLAYER, R.string.PengramMusicMetaHeader, 0, R.string.PengramLyricsSectionInfo},
            {R.string.PengramLyricsAuto, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsSyncHeader, 0, R.string.PengramLyricsAutoInfo},
            {R.string.PengramLyricsOffset, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsSyncHeader, 0, R.string.PengramLyricsOffsetInfo},
            {R.string.PengramLyricsStretch, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsSyncHeader, 0, R.string.PengramLyricsStretchInfo},
            {R.string.PengramLyricsSmooth, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsSyncHeader, 0, R.string.PengramLyricsSmoothInfo},
            {R.string.PengramLyricsAnim, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsHeader, 0, R.string.PengramLyricsAnimInfo},
            {R.string.PengramLyricsAlign, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsHeader, 0, 0},
            {R.string.PengramLyricsBold, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsHeader, 0, 0},
            {R.string.PengramLyricsShadow, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsHeader, 0, 0},
            {R.string.PengramLyricsAutoScroll, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsHeader, 0, 0},
            {R.string.PengramLyricsSize, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsHeader, 0, 0},
            {R.string.PengramLyricsDim, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsHeader, 0, R.string.PengramLyricsDimInfo},
            {R.string.PengramLyricsSpeed, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramLyricsHeader, 0, 0},
            {R.string.PengramHeaderLyricsOn, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramHeaderLyrics, 0, 0},
            {R.string.PengramHeaderLyricsAnim, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramHeaderLyrics, 0, 0},
            {R.string.PengramHeaderLyricsMarquee, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramHeaderLyrics, 0, R.string.PengramHeaderLyricsMarqueeInfo},
            {R.string.PengramHeaderLyricsWords, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramHeaderLyrics, 0, 0},
            {R.string.PengramHeaderLyricsBold, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramHeaderLyrics, 0, 0},
            {R.string.PengramHeaderLyricsSize, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramHeaderLyrics, 0, 0},
            {R.string.PengramHeaderLyricsSpeedTitle, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramHeaderLyrics, 0, 0},
            {R.string.PengramLyricsClear, PengramSettingsActivity.SECTION_LYRICS, R.string.PengramHeaderLyrics, 0, R.string.PengramLyricsClearInfo},
            {R.string.PengramSubsectionActions, PengramSettingsActivity.SECTION_CHATS, 0, 0, R.string.PengramSubsectionActionsInfo},
            {R.string.PengramSubsectionMessages, PengramSettingsActivity.SECTION_CHATS, 0, 0, R.string.PengramSubsectionMessagesInfo},
            {R.string.PengramTypingSection, PengramSettingsActivity.SECTION_CHATS, 0, 0, 0},
            {R.string.PengramQuoteSection, PengramSettingsActivity.SECTION_CHATS, 0, 0, 0},
            {R.string.PengramLinkAssistant, PengramSettingsActivity.SECTION_CHATS, 0, 0, R.string.PengramLinkAssistantInfo},
            {R.string.PengramInputToolsSection, PengramSettingsActivity.SECTION_CHATS, 0, 0, 0},
            {R.string.PengramSubsectionInterface, PengramSettingsActivity.SECTION_CHATS, 0, 0, R.string.PengramSubsectionInterfaceInfo},
            {R.string.PengramSubsectionMenus, PengramSettingsActivity.SECTION_CHATS, 0, 0, 0},
            {R.string.PengramLinkAssistant, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderGeneral, 0, R.string.PengramLinkAssistantInfo},
            {R.string.PengramLinkQualityMode, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderQuality, 0, 0},
            {R.string.PengramLinkDefaultFormat, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderQuality, 0, 0},
            {R.string.PengramLinkAudioChip, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderQuality, 0, R.string.PengramLinkAudioChipInfo},
            {R.string.PengramLinkIncomingWatch, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderIncoming, 0, R.string.PengramLinkIncomingWatchInfo},
            {R.string.PengramLinkIncomingOutgoing, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderIncoming, 0, 0},
            {R.string.PengramLinkPlatformYoutubeRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformTiktokRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformInstagramRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformTwitterRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformPinterestRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformVkRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformRutubeRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformRedditRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformSoundcloudRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPlatformYandexRow, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPlatforms, 0, 0},
            {R.string.PengramLinkPreviewMode, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPreview, 0, 0},
            {R.string.PengramLinkIslandPreviewBtn, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPreview, 0, R.string.PengramLinkIslandPreviewBtnInfo},
            {R.string.PengramLinkLivePhotos, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPhotos, 0, R.string.PengramLinkLivePhotosInfo},
            {R.string.PengramLinkPhotoStitchMode, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPhotos, 0, 0},
            {R.string.PengramLinkSlideAudio, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPhotos, 0, 0},
            {R.string.PengramLinkSlideDuration, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderPhotos, 0, 0},
            {R.string.PengramLinkFallbackMode, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderFallback, 0, 0},
            {R.string.PengramLinkCaptionMode, PengramSettingsActivity.SECTION_LINK_ASSISTANT, R.string.PengramLinkHeaderFallback, 0, 0},
            {R.string.PengramQuickAdd, PengramSettingsActivity.SECTION_CHAT_ACTIONS, R.string.PengramQuickActions, 0, 0},
            {R.string.PengramCopySignAuthor, PengramSettingsActivity.SECTION_CHAT_ACTIONS, R.string.PengramDeletedSendHeader, 0, R.string.PengramCopySignAuthorInfo},
            {R.string.PengramForwardLock, PengramSettingsActivity.SECTION_CHAT_ACTIONS, R.string.PengramForwardHeader, 0, R.string.PengramForwardLockInfo},
            {R.string.PengramForwardDoneAlert, PengramSettingsActivity.SECTION_CHAT_ACTIONS, R.string.PengramForwardHeader, 0, 0},
            {R.string.PengramChatLook, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessagesHeader, 0, R.string.PengramChatLookInfo},
            {R.string.PengramServiceJoinLeaveProfile, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessagesHeader, 0, R.string.PengramServiceJoinLeaveProfileInfo},
            {R.string.PengramKeepFormatting, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessagesHeader, 0, R.string.PengramKeepFormattingInfo},
            {R.string.PengramBubbleGlass, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, R.string.PengramBubbleGlassInfo},
            {R.string.PengramBubbleGlassOpacity, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, 0},
            {R.string.PengramBubbleWallpaperTint, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, 0},
            {R.string.PengramBubbleRim, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, R.string.PengramBubbleRimInfo},
            {R.string.PengramBubbleRimStrength, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, 0},
            {R.string.PengramBubbleShadowMode, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, 0},
            {R.string.PengramBubbleShadowStrength, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, 0},
            {R.string.PengramBubbleOuterRadius, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, 0},
            {R.string.PengramBubbleInnerRadius, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, 0},
            {R.string.PengramHideTail, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramBubbleStudioHeader, 0, R.string.PengramHideTailInfo},
            {R.string.PengramForceDeleteForAll, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramSelectionLimit, 0, R.string.PengramForceDeleteForAllInfo},
            {R.string.PengramMenuCopyMessageId, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageMenuHeader, 0, 0},
            {R.string.PengramMenuSaveToSaved, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageMenuHeader, 0, 0},
            {R.string.PengramAIMenu, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageMenuHeader, 0, 0},
            {R.string.PengramMessageIslandEnable, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageIsland, 0, 0},
            {R.string.PengramMessageIslandButtons, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageIsland, 0, 0},
            {R.string.PengramMessageIslandLabels, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageIsland, 0, 0},
            {R.string.PengramMessageIslandCompact, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageIsland, 0, 0},
            {R.string.PengramMessageIslandLinkTrash, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageIsland, 0, 0},
            {R.string.PengramMessageIslandHaptic, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageIsland, 0, 0},
            {R.string.PengramMessageIslandPosition, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageIsland, 0, 0},
            {R.string.PengramMessageIslandTint, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMessageIsland, 0, 0},
            {R.string.PengramMentionName, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMentionsHeader, 0, R.string.PengramMentionNameInfo},
            {R.string.PengramMentionAll, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMentionAllHeader, 0, R.string.PengramMentionAllInfo},
            {R.string.PengramMentionAllMode, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMentionAllHeader, 0, 0},
            {R.string.PengramMentionAllLimit, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMentionAllHeader, 0, 0},
            {R.string.PengramMentionAllCooldown, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMentionAllHeader, 0, 0},
            {R.string.PengramMentionAllStyle, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMentionAllHeader, 0, 0},
            {R.string.PengramQuoteTapAction, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteBehavior, 0, 0},
            {R.string.PengramQuoteNames, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, R.string.PengramQuoteNamesInfo},
            {R.string.PengramQuotePeerNameColor, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, R.string.PengramQuotePeerNameColorInfo},
            {R.string.PengramQuoteShowBadges, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteFakeName, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteAvatar, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, R.string.PengramQuoteAvatarInfo},
            {R.string.PengramQuoteAvatarLayout, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteAvatarRounded, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteAvatarSize, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteAvatarGap, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteShowReply, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteShowForward, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteShowReactions, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteAnonMentions, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, R.string.PengramQuoteAnonMentionsInfo},
            {R.string.PengramQuoteTimes, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteDateInTime, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, 0},
            {R.string.PengramQuoteMedia, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuotePrivacyHeader, 0, R.string.PengramQuoteMediaInfo},
            {R.string.PengramQuoteResetAppearance, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteBackgroundHeader, 0, 0},
            {R.string.PengramQuoteBackground, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteBackgroundHeader, 0, 0},
            {R.string.PengramQuoteBackgroundColor, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteBackgroundHeader, 0, 0},
            {R.string.PengramQuoteStickerTransparent, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteBackgroundHeader, 0, 0},
            {R.string.PengramQuoteAccent, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, 0},
            {R.string.PengramQuoteDark, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, 0},
            {R.string.PengramQuoteBubbleTail, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, R.string.PengramQuoteBubbleTailInfo},
            {R.string.PengramQuoteAlignOutgoing, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, R.string.PengramQuoteAlignOutgoingInfo},
            {R.string.PengramQuoteScale, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, 0},
            {R.string.PengramQuotePadding, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, 0},
            {R.string.PengramQuoteRadius, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, 0},
            {R.string.PengramQuoteJpeg, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, R.string.PengramQuoteJpegInfo},
            {R.string.PengramQuoteQuality, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteStyleHeader, 0, 0},
            {R.string.PengramQuoteWatermark, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteWatermarkHeader, 0, R.string.PengramQuoteWatermarkInfo},
            {R.string.PengramQuoteWatermarkText, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteWatermarkHeader, 0, 0},
            {R.string.PengramQuoteWatermarkPosition, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteWatermarkHeader, 0, 0},
            {R.string.PengramQuoteLogo, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteWatermarkHeader, 0, 0},
            {R.string.PengramQuoteLogoClear, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteWatermarkHeader, 0, 0},
            {R.string.PengramQuoteLogoSize, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteWatermarkHeader, 0, 0},
            {R.string.PengramQuoteWatermarkOpacity, PengramSettingsActivity.SECTION_QUOTES, R.string.PengramQuoteWatermarkHeader, 0, 0},
            {R.string.PengramInputToolsSection, PengramSettingsActivity.SECTION_TYPING, 0, 0, 0},
            {R.string.PengramSendStyle, PengramSettingsActivity.SECTION_TYPING, R.string.PengramSendStyleHeader, 0, R.string.PengramSendStyleInfo},
            {R.string.PengramSendStyleLargeEmoji, PengramSettingsActivity.SECTION_TYPING, R.string.PengramSendStyleHeader, 0, R.string.PengramSendStyleLargeEmojiInfo},
            {R.string.PengramSendStyleCaptions, PengramSettingsActivity.SECTION_TYPING, R.string.PengramSendStyleHeader, 0, 0},
            {R.string.PengramTypingMaster, PengramSettingsActivity.SECTION_TYPING, R.string.PengramInputAnimationHeader, 0, R.string.PengramTypingMasterInfo},
            {R.string.PengramInputAnimation, PengramSettingsActivity.SECTION_TYPING, R.string.PengramInputAnimationHeader, 0, R.string.PengramInputAnimationInfo},
            {R.string.PengramTypingJuicyPreset, PengramSettingsActivity.SECTION_TYPING, R.string.PengramInputAnimationHeader, 0, 0},
            {R.string.PengramInputAnimationSpeed, PengramSettingsActivity.SECTION_TYPING, R.string.PengramInputAnimationHeader, 0, 0},
            {R.string.PengramInputAnimationIntensity, PengramSettingsActivity.SECTION_TYPING, R.string.PengramInputAnimationHeader, 0, 0},
            {R.string.PengramInputAnimationHaptic, PengramSettingsActivity.SECTION_TYPING, R.string.PengramInputAnimationHeader, 0, 0},
            {R.string.PengramTypingBlur, PengramSettingsActivity.SECTION_TYPING, R.string.PengramInputAnimationHeader, 0, 0},
            {R.string.PengramTypingBlurRadius, PengramSettingsActivity.SECTION_TYPING, R.string.PengramInputAnimationHeader, 0, 0},
            {R.string.PengramTypingIgnoreSpaces, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingBehaviorHeader, 0, 0},
            {R.string.PengramTypingAllLines, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingBehaviorHeader, 0, 0},
            {R.string.PengramTypingDelete, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingDeleteHeader, 0, 0},
            {R.string.PengramTypingDeleteStyle, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingDeleteHeader, 0, 0},
            {R.string.PengramTypingDeleteCount, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingDeleteHeader, 0, 0},
            {R.string.PengramTypingDeleteSpeed, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingDeleteHeader, 0, 0},
            {R.string.PengramTypingDeleteSpread, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingDeleteHeader, 0, 0},
            {R.string.PengramTypingDeleteSize, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingDeleteHeader, 0, 0},
            {R.string.PengramTypingCursorSmooth, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingCursorHeader, 0, 0},
            {R.string.PengramTypingCursorSpeed, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingCursorHeader, 0, 0},
            {R.string.PengramTypingCursorWidth, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingCursorHeader, 0, 0},
            {R.string.PengramTypingCursorLiquid, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingCursorHeader, 0, 0},
            {R.string.PengramTypingCursorStretch, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingCursorHeader, 0, 0},
            {R.string.PengramTypingSelectionLiquid, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingCursorHeader, 0, 0},
            {R.string.PengramTypingStatsEnable, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingStatsTitle, 0, 0},
            {R.string.PengramTypingStatsBadge, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingStatsTitle, 0, 0},
            {R.string.PengramTypingStatsClear, PengramSettingsActivity.SECTION_TYPING, R.string.PengramTypingStatsTitle, 0, 0},
            {R.string.PengramInlineWebSearch, PengramSettingsActivity.SECTION_INPUT_TOOLS, R.string.PengramWebSearchHeader, 0, R.string.PengramInlineWebSearchInfo},
            {R.string.PengramWebSearchPrefix, PengramSettingsActivity.SECTION_INPUT_TOOLS, R.string.PengramWebSearchHeader, 0, 0},
            {R.string.PengramWebSearchEngine, PengramSettingsActivity.SECTION_INPUT_TOOLS, R.string.PengramWebSearchHeader, 0, 0},
            {R.string.PengramWebSearchHistory, PengramSettingsActivity.SECTION_INPUT_TOOLS, R.string.PengramWebSearchHeader, 0, R.string.PengramWebSearchHistoryInfo},
            {R.string.PengramInlineCalc, PengramSettingsActivity.SECTION_INPUT_TOOLS, R.string.PengramCalcHeader, 0, R.string.PengramInlineCalcInfo},
            {R.string.PengramInlineCurrency, PengramSettingsActivity.SECTION_INPUT_TOOLS, R.string.PengramCalcHeader, 0, R.string.PengramInlineCurrencyInfo},
            {R.string.PengramCalcDefaultCurrency, PengramSettingsActivity.SECTION_INPUT_TOOLS, R.string.PengramCalcHeader, 0, 0},
            {R.string.PengramCalcRefreshRates, PengramSettingsActivity.SECTION_INPUT_TOOLS, R.string.PengramCalcHeader, 0, 0},
            {R.string.PengramMediaTimeDefault, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMediaTimeHeader, 0, R.string.PengramMediaTimeDefaultInfo},
            {R.string.PengramMediaTimeStickers, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMediaTimeHeader, 0, R.string.PengramMediaTimeStickersInfo},
            {R.string.PengramMediaTimeMedia, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMediaTimeHeader, 0, R.string.PengramMediaTimeMediaInfo},
            {R.string.PengramMediaTimeRound, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMediaTimeHeader, 0, 0},
            {R.string.PengramMediaTimeSending, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramMediaTimeHeader, 0, 0},
            {R.string.PengramAvatarShape, PengramSettingsActivity.SECTION_CHAT_INTERFACE, R.string.PengramDialogsHeader, 0, R.string.PengramAvatarShapeInfo},
            {R.string.PengramHideWriteButton, PengramSettingsActivity.SECTION_CHAT_INTERFACE, R.string.PengramDialogsHeader, 0, 0},
            {R.string.PengramThreeLines, PengramSettingsActivity.SECTION_CHAT_INTERFACE, R.string.PengramDialogsHeader, 0, 0},
            {R.string.PengramHideArchive, PengramSettingsActivity.SECTION_CHAT_INTERFACE, R.string.PengramDialogsHeader, 0, 0},
            {R.string.PengramHideStories, PengramSettingsActivity.SECTION_CHAT_INTERFACE, R.string.PengramDialogsHeader, 0, R.string.PengramHideStoriesInfo},
            {R.string.PengramSenderAvatars, PengramSettingsActivity.SECTION_CHAT_INTERFACE, R.string.PengramDialogsHeader, 0, R.string.PengramSenderAvatarsInfo},
            {R.string.PengramSenderAvatarPosition, PengramSettingsActivity.SECTION_CHAT_INTERFACE, R.string.PengramDialogsHeader, 0, 0},
            {R.string.PengramSwipeAction, PengramSettingsActivity.SECTION_CHAT_INTERFACE, R.string.PengramGesturesHeader, 0, 0},
            {R.string.PengramChatMenu, PengramSettingsActivity.SECTION_CHAT_MENUS, 0, 0, 0},
            {R.string.PengramChatMenuTop, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramChatMenu, 0, 0},
            {R.string.PengramChatMenuBottom, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramChatMenu, 0, 0},
            {R.string.PengramMenuItemsTitle, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideMenuHeader, 0, 0},
            {R.string.PengramMenuReadAll, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideMenuHeader, 0, 0},
            {R.string.PengramSettingsItemsTitle, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideMenuHeader, 0, 0},
            {R.string.PengramChatItemsTitle, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideMenuHeader, 0, 0},
            {R.string.PengramCardOnTop, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideMenuHeader, 0, R.string.PengramCardOnTopInfo},
            {R.string.PengramHideChatSearch, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramHideChatTranslate, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramHideChatClear, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramHideChatWallpaper, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramHideChatShortcut, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramHideChatReport, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramHideChatCall, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramHideChatAutoDelete, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramHideChatShareNumber, PengramSettingsActivity.SECTION_CHAT_MENUS, R.string.PengramHideChatHeader, 0, 0},
            {R.string.PengramAIAutoJump, PengramSettingsActivity.SECTION_AI, 0, 0, 0},
            {R.string.PengramAIServices, PengramSettingsActivity.SECTION_AI, R.string.PengramAISetupHeader, 0, R.string.PengramAIServicesInfo},
            {R.string.PengramAIEditService, PengramSettingsActivity.SECTION_AI, R.string.PengramAISetupHeader, 0, 0},
            {R.string.PengramAIAddService, PengramSettingsActivity.SECTION_AI, R.string.PengramAISetupHeader, 0, 0},
            {R.string.PengramAIRoles, PengramSettingsActivity.SECTION_AI, R.string.PengramAISetupHeader, 0, R.string.PengramAIRolesInfo},
            {R.string.PengramAIEditRole, PengramSettingsActivity.SECTION_AI, R.string.PengramAISetupHeader, 0, 0},
            {R.string.PengramAIAddRole, PengramSettingsActivity.SECTION_AI, R.string.PengramAISetupHeader, 0, 0},
            {R.string.PengramAIStream, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAnswer, 0, R.string.PengramAIStreamInfo},
            {R.string.PengramAIOnlyAnswer, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAnswer, 0, R.string.PengramAIOnlyAnswerInfo},
            {R.string.PengramAIAsQuote, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAnswer, 0, R.string.PengramAIAsQuoteInfo},
            {R.string.PengramAIHistory, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAnswer, 0, R.string.PengramAIHistoryInfo},
            {R.string.PengramAIDepth, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAnswer, 0, 0},
            {R.string.PengramAIAutoMaster, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoHeader, 0, 0},
            {R.string.PengramAIAutoContext, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoHeader, 0, R.string.PengramAIAutoContextInfo},
            {R.string.PengramAIAutoContextLimit, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoHeader, 0, 0},
            {R.string.PengramAIAutoStyle, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoHeader, 0, R.string.PengramAIAutoStyleInfo},
            {R.string.PengramAIAutoAdvanced, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoHeader, 0, 0},
            {R.string.PengramAIAutoQuiet, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoHeader, 0, 0},
            {R.string.PengramAIAutoCooldown, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoHeader, 0, 0},
            {R.string.PengramAIAutoSpamCount, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoSpamHeader, 0, 0},
            {R.string.PengramAIAutoSpamPause, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoSpamHeader, 0, 0},
            {R.string.PengramAIAutoAdd, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoWatching, 0, 0},
            {R.string.PengramAIAutoRefresh, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoWatching, 0, 0},
            {R.string.PengramAIReset, PengramSettingsActivity.SECTION_AI, R.string.PengramAIAutoWatching, 0, R.string.PengramAIResetInfo},
            {R.string.PengramVoiceTrolling, PengramSettingsActivity.SECTION_MEDIA, R.string.PengramVoiceHeader, 0, R.string.PengramVoiceTrollingInfo},
            {R.string.PengramAvatarPos, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramChatLook, 1, R.string.PengramAvatarPosInfo},
            {R.string.PengramDialogAvatars, PengramSettingsActivity.SECTION_CHAT_MESSAGES, R.string.PengramChatLook, 1, R.string.PengramDialogAvatarsInfo},
            {R.string.PengramProfileHistoryEnable, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileHistoryStorage, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileCloudFormat, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileCloudRecreate, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, R.string.PengramProfileCloudRecreateInfo},
            {R.string.PengramProfileHistoryScope, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileHistoryBio, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileHistoryAvatars, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileLimitSize, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileLimitTime, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileHistoryOpen, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, 0},
            {R.string.PengramProfileHistoryClearAvatars, PengramSettingsActivity.SECTION_HISTORY, R.string.PengramProfileHistory, 2, R.string.PengramProfileHistoryClearAvatarsInfo},
    };

    /** Стабильные идентификаторы переходов (не зависят от aapt2 R.string и порядка строк). */
    public static final int[] IDS = {
            0x3fa21dca, // PengramAboutVersion (SECTION_ABOUT)
            0x3357070f, // PengramAboutBase (SECTION_ABOUT)
            0x1c90b7c6, // PengramAboutPackage (SECTION_ABOUT)
            0x38a61dee, // PengramAboutDevice (SECTION_ABOUT)
            0x3fe8feff, // PengramAboutSystem (SECTION_ABOUT)
            0x3f024fd2, // PengramAboutAbi (SECTION_ABOUT)
            0x3a820691, // PengramAboutCopy (SECTION_ABOUT)
            0x14bc12c1, // PengramResetAll (SECTION_ABOUT)
            0x3b05b958, // PengramSectionAppearance (SECTION_ROOT)
            0x1c7ae604, // PengramSectionCustom (SECTION_ROOT)
            0x35353c27, // PengramSectionGhost (SECTION_ROOT)
            0x1e271456, // PengramSectionSpy (SECTION_ROOT)
            0x1d13ff14, // PengramSectionProfile (SECTION_ROOT)
            0x3f9e41c9, // PengramSectionChats (SECTION_ROOT)
            0x394b7d8b, // PengramInputToolsSection (SECTION_ROOT)
            0x3585ec29, // PengramSectionAI (SECTION_ROOT)
            0x3a464437, // PengramSectionPlayer (SECTION_ROOT)
            0x11dfdae4, // PengramSectionMedia (SECTION_ROOT)
            0x3174c262, // PengramSectionFreedom (SECTION_ROOT)
            0x39be20f4, // PengramSectionGeneral (SECTION_ROOT)
            0x3647191f, // PengramEditor (SECTION_ROOT)
            0x10dd5dc7, // PengramAbout (SECTION_ROOT)
            0x33f7fc2c, // PengramNoRounding (SECTION_GENERAL)
            0x34c56b47, // PengramTimeSeconds (SECTION_GENERAL)
            0x3475249b, // PengramVibration (SECTION_GENERAL)
            0x33368650, // PengramShowAccountsSettings (SECTION_GENERAL)
            0x37090e91, // PengramDeviceSpoof (SECTION_GENERAL)
            0x1a89db5f, // PengramDeviceModel (SECTION_GENERAL)
            0x1fe75ef8, // PengramDeviceVersion (SECTION_GENERAL)
            0x1f13754e, // PengramDeviceVersionSpoof (SECTION_GENERAL)
            0x1febbb8f, // PengramDeviceRandom (SECTION_GENERAL)
            0x336826f8, // PengramDeviceApply (SECTION_GENERAL)
            0x15cf2389, // DirectShare (SECTION_GENERAL)
            0x14bfc8df, // PengramSortContacts (SECTION_GENERAL)
            0x3d3d4c4e, // PengramStickerOrder (SECTION_GENERAL)
            0x1e5358d5, // PengramOpenById (SECTION_GENERAL)
            0x3fb96f41, // PengramExportChatSetting (SECTION_GENERAL)
            0x30bbd7f8, // PengramBackupExport (SECTION_GENERAL)
            0x11edb812, // PengramBackupImport (SECTION_GENERAL)
            0x35c9a07e, // PengramBackupReset (SECTION_GENERAL)
            0x11b4b2e1, // PengramSessionExportSetting (SECTION_GENERAL)
            0x153f7ad2, // PengramDeletedMark (SECTION_CUSTOM)
            0x19934033, // PengramEditedMark (SECTION_CUSTOM)
            0x1cb722eb, // PengramFadeDeleted (SECTION_CUSTOM)
            0x145cbc47, // PengramDeleteEffect (SECTION_CUSTOM)
            0x1033aec8, // PengramDeleteEffectIncoming (SECTION_CUSTOM)
            0x3cb5df4f, // PengramIdFormatPicker (SECTION_PROFILE)
            0x39aac179, // PengramIdPlacementPicker (SECTION_PROFILE)
            0x15de2fc6, // PengramIdCopyOnTap (SECTION_PROFILE)
            0x37a15cb5, // PengramIdFormatHide (SECTION_PROFILE)
            0x3e03c49f, // PengramIdFormatTelegram (SECTION_PROFILE)
            0x10235157, // PengramIdFormatBot (SECTION_PROFILE)
            0x3eca1846, // PengramIdStyleOff (SECTION_PROFILE)
            0x31e3d25f, // PengramIdStyleRow (SECTION_PROFILE)
            0x326f8d20, // PengramIdStyleRowDc (SECTION_PROFILE)
            0x36d5d9cd, // PengramIdStyleInline (SECTION_PROFILE)
            0x1264738f, // PengramOriginalName (SECTION_PROFILE)
            0x3a000466, // PengramRegStyle (SECTION_PROFILE)
            0x1ed83aa1, // PengramRegPlace (SECTION_PROFILE)
            0x16f0137d, // PengramRegIcon (SECTION_PROFILE)
            0x39d26581, // PengramRegTapText (SECTION_PROFILE)
            0x389931d3, // PengramHidePhone (SECTION_PROFILE)
            0x33fcd3f5, // PengramSharePhoneMode (SECTION_PROFILE)
            0x3e3ca80b, // PengramClassicProfileMusic (SECTION_PROFILE)
            0x3db706a5, // PengramSpyAccount (SECTION_HISTORY)
            0x34c01df4, // PengramSpyOwnSettings (SECTION_HISTORY)
            0x3c34a2e5, // PengramHistorySaveMessages (SECTION_HISTORY)
            0x3ffd917a, // PengramHistorySaveDeleted (SECTION_HISTORY)
            0x361c805f, // PengramHistorySaveEdited (SECTION_HISTORY)
            0x3dfd24fd, // PengramHistorySaveEditedMedia (SECTION_HISTORY)
            0x117a03ec, // PengramHistorySaveOutgoing (SECTION_HISTORY)
            0x382a3b12, // PengramSaveInBots (SECTION_HISTORY)
            0x31803414, // PengramSaveReadDate (SECTION_HISTORY)
            0x140e00e8, // PengramSaveLastOnline (SECTION_HISTORY)
            0x3bb0d277, // PengramSaveForMyselfOption (SECTION_HISTORY)
            0x32c52a6c, // PengramSaveForMyselfDefault (SECTION_HISTORY)
            0x1f5a80d7, // PengramLocalKeep (SECTION_HISTORY)
            0x15d9da00, // PengramProfileHistory (SECTION_HISTORY)
            0x3cf9eac5, // PengramDeletedReplyQuote (SECTION_HISTORY)
            0x124d3ac1, // PengramDeletedReplySign (SECTION_HISTORY)
            0x37e2b282, // PengramKeepDays (SECTION_HISTORY)
            0x320b0b0c, // PengramHistoryMax (SECTION_HISTORY)
            0x36c79bda, // PengramHistoryClearButton (SECTION_HISTORY)
            0x16940d97, // PengramMediaSave (SECTION_HISTORY)
            0x350048db, // PengramMediaFolder (SECTION_HISTORY)
            0x31294129, // PengramMediaPattern (SECTION_HISTORY)
            0x3f6d8385, // PengramMediaGallery (SECTION_HISTORY)
            0x104eab28, // PengramMediaLimitValue (SECTION_HISTORY)
            0x1994756d, // PengramMediaClear (SECTION_HISTORY)
            0x3104b8d3, // PengramGhostMode (SECTION_GHOST)
            0x3d307568, // PengramGhostDontRead (SECTION_GHOST)
            0x1e17c360, // PengramGhostDontStory (SECTION_GHOST)
            0x36a86de5, // PengramGhostHideOnline (SECTION_GHOST)
            0x1ccfe26a, // PengramGhostDontType (SECTION_GHOST)
            0x3f134c00, // PengramGhostAutoOffline (SECTION_GHOST)
            0x104cfe77, // PengramGhostDontSendVoiceRead (SECTION_GHOST)
            0x10a1ac15, // PengramGhostDontSendReactions (SECTION_GHOST)
            0x31253de0, // PengramGhostReadOnReply (SECTION_GHOST)
            0x197fc781, // PengramGhostStoriesWarn (SECTION_GHOST)
            0x3a40a152, // PengramGhostSendDelay (SECTION_GHOST)
            0x31d2d593, // PengramBypass (SECTION_FREEDOM)
            0x1c6fe946, // PengramAllowScreenshots (SECTION_FREEDOM)
            0x1c10f4a1, // PengramNoScreenshotNotify (SECTION_FREEDOM)
            0x1a77c5c2, // PengramAllowForwards (SECTION_FREEDOM)
            0x399ab634, // PengramKeepOnce (SECTION_FREEDOM)
            0x1d4005fa, // PengramAntiCrash (SECTION_FREEDOM)
            0x11d7ced7, // PengramAntiCrashMark (SECTION_FREEDOM)
            0x1b5e5a07, // PengramAntiCrashJournalEnabled (SECTION_FREEDOM)
            0x1ab8810d, // PengramAntiCrashStats (SECTION_FREEDOM)
            0x3603fc19, // PengramAntiCrashLog (SECTION_FREEDOM)
            0x195a167d, // PengramCrashAutoCopy (SECTION_FREEDOM)
            0x39a919a1, // PengramCrashReports (SECTION_FREEDOM)
            0x3e0605f2, // PengramHideAds (SECTION_FREEDOM)
            0x1f5b49c6, // PengramLocalPremium (SECTION_FREEDOM)
            0x1c115119, // PengramLocalPremiumStatus (SECTION_FREEDOM)
            0x1947584c, // PengramNotificationIcon (SECTION_FREEDOM)
            0x192df4e4, // PengramBackgroundMode (SECTION_FREEDOM)
            0x39cd751b, // PengramBackgroundSilent (SECTION_FREEDOM)
            0x31287e70, // PengramMD3 (SECTION_APPEARANCE)
            0x3210a4a2, // PengramMD3Dialogs (SECTION_APPEARANCE)
            0x1ab00c5a, // PengramMD3Tonal (SECTION_APPEARANCE)
            0x14a9e997, // PengramMD3NoDividers (SECTION_APPEARANCE)
            0x1df8427c, // PengramMD3SearchBar (SECTION_APPEARANCE)
            0x158e2362, // PengramMD3SearchShadow (SECTION_APPEARANCE)
            0x3330670b, // PengramMD3Sheets (SECTION_APPEARANCE)
            0x14bb2448, // PengramMD3Alerts (SECTION_APPEARANCE)
            0x1af81c3d, // PengramMD3Menus (SECTION_APPEARANCE)
            0x130ad9ad, // PengramMD3Settings (SECTION_APPEARANCE)
            0x33a58a53, // PengramMD3Screens (SECTION_APPEARANCE)
            0x3e6265bb, // PengramMD3Fab (SECTION_APPEARANCE)
            0x31a16d3a, // PengramMonet (SECTION_APPEARANCE)
            0x3cfd07cb, // PengramMonetMessages (SECTION_APPEARANCE)
            0x35f4ec36, // PengramMonetStrength (SECTION_APPEARANCE)
            0x192eb28d, // PengramQuickTiles (SECTION_APPEARANCE)
            0x1489928b, // PengramFont (SECTION_APPEARANCE)
            0x1608596d, // PengramAvatarShape (SECTION_APPEARANCE)
            0x1f9df468, // PengramConstructor (SECTION_APPEARANCE)
            0x3733eb5e, // PengramCheckmarkStyle (SECTION_APPEARANCE)
            0x1b7472a5, // PengramOnlineBadgeStyle (SECTION_APPEARANCE)
            0x300d2540, // PengramTitleText (SECTION_APPEARANCE)
            0x1da9e0e7, // PengramTitleCustom (SECTION_APPEARANCE)
            0x1593177d, // PengramTitleCenter (SECTION_APPEARANCE)
            0x31864da3, // PengramSnow (SECTION_APPEARANCE)
            0x3dafcc03, // PengramParticleType (SECTION_APPEARANCE)
            0x30a6065f, // PengramParticleCount (SECTION_APPEARANCE)
            0x3d1cf223, // PengramParticleOpacity (SECTION_APPEARANCE)
            0x10785a31, // PengramParticleSpeed (SECTION_APPEARANCE)
            0x336e517b, // PengramParticleRotation (SECTION_APPEARANCE)
            0x3e755e64, // PengramTabBarSize (SECTION_APPEARANCE)
            0x1eb8f54d, // PengramTabBarGlass (SECTION_APPEARANCE)
            0x172254cf, // PengramInputGlass (SECTION_APPEARANCE)
            0x3f170b20, // PengramHeaderGlass (SECTION_APPEARANCE)
            0x1a687b59, // PengramChatHeaderGlass (SECTION_APPEARANCE)
            0x3fba3c76, // PengramPlayerHeaderGlass (SECTION_APPEARANCE)
            0x3f2ff269, // PengramChatPinnedGlass (SECTION_APPEARANCE)
            0x1e3d7a85, // PengramGlassOpacity (SECTION_APPEARANCE)
            0x10e793ad, // PengramGlassThickness (SECTION_APPEARANCE)
            0x1436e823, // PengramGlassIntensity (SECTION_APPEARANCE)
            0x17da9980, // PengramGlassGlow (SECTION_APPEARANCE)
            0x39d2cced, // PengramGlassSheen (SECTION_APPEARANCE)
            0x3be8451d, // PengramGlassDrag (SECTION_APPEARANCE)
            0x1d2c37cf, // PengramGlassRim (SECTION_APPEARANCE)
            0x38c00b96, // BubbleRadius (SECTION_APPEARANCE)
            0x183ef2f1, // LargeEmoji (SECTION_APPEARANCE)
            0x1cf04240, // PengramSystemEmoji (SECTION_APPEARANCE)
            0x3af3288d, // LoopAnimatedStickers (SECTION_APPEARANCE)
            0x31a72725, // LiteOptionsStickers (SECTION_APPEARANCE)
            0x17ede4cf, // LiteOptionsEmoji (SECTION_APPEARANCE)
            0x3067317f, // EnableAnimations (SECTION_APPEARANCE)
            0x3396b087, // PengramChatEnterAnim (SECTION_APPEARANCE)
            0x14faab14, // PengramChatEnterStyle (SECTION_APPEARANCE)
            0x1ebf9c5a, // PengramCylinderScroll (SECTION_APPEARANCE)
            0x30b283f3, // PengramCylinderToBars (SECTION_APPEARANCE)
            0x19cb50e5, // PengramCylinderStrength (SECTION_APPEARANCE)
            0x3bad03c6, // PengramCylinderJuicyPreset (SECTION_APPEARANCE)
            0x36e9d0b1, // PengramLinkAssistant (SECTION_APPEARANCE)
            0x1e93faa1, // PengramChatBlur (SECTION_APPEARANCE)
            0x3c3b8904, // PengramSpoilerEffect (SECTION_APPEARANCE)
            0x345f9a52, // PengramThanosEffect (SECTION_APPEARANCE)
            0x39f15df4, // LiteOptionsParticles (SECTION_APPEARANCE)
            0x34b59ea1, // LiteOptionsCalls (SECTION_APPEARANCE)
            0x30e7cca0, // PengramChatBackgroundAnim (SECTION_APPEARANCE)
            0x1dbd90a9, // PengramTabletMode (SECTION_APPEARANCE)
            0x3eb7fb79, // PengramBoostOff (SECTION_MEDIA)
            0x3d28771c, // PengramBoostFast (SECTION_MEDIA)
            0x19d8472a, // PengramBoostExtreme (SECTION_MEDIA)
            0x1046fcbf, // PengramVoicePitch (SECTION_MEDIA)
            0x1e61930b, // PengramVoiceFormant (SECTION_MEDIA)
            0x34335aea, // PengramVoiceEcho (SECTION_MEDIA)
            0x1d5dc964, // PengramVoiceStudioDrive (SECTION_MEDIA)
            0x1ab3fe21, // PengramVoiceStudioRobot (SECTION_MEDIA)
            0x18a7fa0b, // PengramVoiceStudioMix (SECTION_MEDIA)
            0x1388ea49, // PengramVoiceProfileCopy (SECTION_MEDIA)
            0x188f6593, // PengramVoiceProfileImport (SECTION_MEDIA)
            0x1c7862aa, // PengramVoiceGainPreset (SECTION_MEDIA)
            0x38d0c499, // PengramVoiceOverdrive (SECTION_MEDIA)
            0x144228e7, // PengramVoiceGainMaximum (SECTION_MEDIA)
            0x1d8faf5b, // PengramVoiceAmbience (SECTION_MEDIA)
            0x11b5b003, // PengramVoiceAmbienceLevel (SECTION_MEDIA)
            0x33dd38ff, // PengramVoiceAmbienceDuck (SECTION_MEDIA)
            0x1799b254, // PengramVoiceAmbienceStopPreview (SECTION_MEDIA)
            0x3c879e8e, // PengramVoiceMessages (SECTION_MEDIA)
            0x11f047f5, // PengramVoiceRound (SECTION_MEDIA)
            0x3320b582, // PengramVoiceCalls (SECTION_MEDIA)
            0x1a3fd0dc, // PengramVoiceGate (SECTION_MEDIA)
            0x17ee1c44, // EnableStreaming (SECTION_MEDIA)
            0x38d0f4f6, // PengramStreamAllVideo (SECTION_MEDIA)
            0x31e4ee21, // PengramStreamMkv (SECTION_MEDIA)
            0x1a6d9a08, // PengramSaveStream (SECTION_MEDIA)
            0x16d152cd, // LiteOptionsAutoplayGifs (SECTION_MEDIA)
            0x3b59c719, // LiteOptionsAutoplayVideo (SECTION_MEDIA)
            0x152a199e, // NextMediaTap (SECTION_MEDIA)
            0x135274c2, // PengramSortFilesByName (SECTION_MEDIA)
            0x10ebaeb6, // PengramInAppCamera (SECTION_MEDIA)
            0x379048c7, // PengramBigCameraRound (SECTION_MEDIA)
            0x12f9d448, // PengramDisableVoiceProximity (SECTION_MEDIA)
            0x1b137829, // RaiseToSpeak (SECTION_MEDIA)
            0x1fa1c7c3, // RaiseToListen (SECTION_MEDIA)
            0x14386a2d, // PengramPauseOnRecord (SECTION_MEDIA)
            0x19254ace, // PengramNoiseSuppression (SECTION_MEDIA)
            0x34076bea, // PengramVoiceEffectsOff (SECTION_MEDIA)
            0x1fad4089, // PauseMusicOnMedia (SECTION_MEDIA)
            0x3c501d7f, // PengramPlayerLook (SECTION_PLAYER)
            0x3e1af43e, // PengramPlayerBg (SECTION_PLAYER)
            0x1d49324c, // PengramPlayerAccent (SECTION_PLAYER)
            0x1c854da3, // PengramCoverShape (SECTION_PLAYER)
            0x38c1388e, // PengramPlayerBlur (SECTION_PLAYER)
            0x358876cf, // PengramEmptyCover (SECTION_PLAYER)
            0x14d05261, // PengramPlayerPenguinDance (SECTION_PLAYER)
            0x342b5f17, // PengramPlayerBeat (SECTION_PLAYER)
            0x1defe278, // PengramPlayerSwipe (SECTION_PLAYER)
            0x1961e91d, // PengramMiniPlayerQueueBtn (SECTION_PLAYER)
            0x1601c374, // PengramPlayerFavorites (SECTION_PLAYER)
            0x3435cb71, // PengramPlayerFavShowFullPlayer (SECTION_PLAYER)
            0x38890d07, // PengramPlayerFavShowMiniPlayer (SECTION_PLAYER)
            0x3131f8f0, // PengramPlayerFavShowChatList (SECTION_PLAYER)
            0x3d68299f, // PengramPlayerFavShowNotification (SECTION_PLAYER)
            0x180ca148, // PengramPlayerFavTarget (SECTION_PLAYER)
            0x148690c5, // PengramPlayerFavCaption (SECTION_PLAYER)
            0x31e4ace4, // PengramPlayerFavOpenVault (SECTION_PLAYER)
            0x18968ae2, // PengramTrackForwardButton (SECTION_PLAYER)
            0x3ceef70e, // PengramMusicForwardClean (SECTION_PLAYER)
            0x31ddc846, // PengramTrackForwardMode (SECTION_PLAYER)
            0x17e40065, // PengramTrackForwardCaption (SECTION_PLAYER)
            0x3919958a, // PengramTrackCardFormat (SECTION_PLAYER)
            0x107e2f2d, // PengramTrackCardLink (SECTION_PLAYER)
            0x1d762679, // PengramTrackCardBrand (SECTION_PLAYER)
            0x1462e83d, // PengramSmartTrackRecognition (SECTION_PLAYER)
            0x13ce56d8, // PengramMusicSmartArtist (SECTION_PLAYER)
            0x38696306, // PengramLyricsSection (SECTION_PLAYER)
            0x3ca92f57, // PengramLyricsAuto (SECTION_LYRICS)
            0x3970bdd8, // PengramLyricsOffset (SECTION_LYRICS)
            0x3f52f456, // PengramLyricsStretch (SECTION_LYRICS)
            0x17fed007, // PengramLyricsSmooth (SECTION_LYRICS)
            0x1fa0dfbc, // PengramLyricsAnim (SECTION_LYRICS)
            0x1ed041c9, // PengramLyricsAlign (SECTION_LYRICS)
            0x1750f34e, // PengramLyricsBold (SECTION_LYRICS)
            0x1ca3e859, // PengramLyricsShadow (SECTION_LYRICS)
            0x1846fa5c, // PengramLyricsAutoScroll (SECTION_LYRICS)
            0x36817911, // PengramLyricsSize (SECTION_LYRICS)
            0x3a9f5927, // PengramLyricsDim (SECTION_LYRICS)
            0x109f23bc, // PengramLyricsSpeed (SECTION_LYRICS)
            0x3c1eab2b, // PengramHeaderLyricsOn (SECTION_LYRICS)
            0x14a5478c, // PengramHeaderLyricsAnim (SECTION_LYRICS)
            0x17c0d021, // PengramHeaderLyricsMarquee (SECTION_LYRICS)
            0x34b0c3d8, // PengramHeaderLyricsWords (SECTION_LYRICS)
            0x1c556b7e, // PengramHeaderLyricsBold (SECTION_LYRICS)
            0x3d84e121, // PengramHeaderLyricsSize (SECTION_LYRICS)
            0x1034ac0d, // PengramHeaderLyricsSpeedTitle (SECTION_LYRICS)
            0x1c309ba3, // PengramLyricsClear (SECTION_LYRICS)
            0x34d5cffc, // PengramSubsectionActions (SECTION_CHATS)
            0x3937b65e, // PengramSubsectionMessages (SECTION_CHATS)
            0x1500af5a, // PengramTypingSection (SECTION_CHATS)
            0x390b2ab5, // PengramQuoteSection (SECTION_CHATS)
            0x38dbdbcf, // PengramLinkAssistant (SECTION_CHATS)
            0x106dcf72, // PengramInputToolsSection (SECTION_CHATS)
            0x31aed73c, // PengramSubsectionInterface (SECTION_CHATS)
            0x38e80bc3, // PengramSubsectionMenus (SECTION_CHATS)
            0x19fa1f8b, // PengramLinkAssistant (SECTION_LINK_ASSISTANT)
            0x102ba2ba, // PengramLinkQualityMode (SECTION_LINK_ASSISTANT)
            0x140cba8a, // PengramLinkDefaultFormat (SECTION_LINK_ASSISTANT)
            0x3515f2a0, // PengramLinkAudioChip (SECTION_LINK_ASSISTANT)
            0x351ecd79, // PengramLinkIncomingWatch (SECTION_LINK_ASSISTANT)
            0x19300406, // PengramLinkIncomingOutgoing (SECTION_LINK_ASSISTANT)
            0x3af9bb99, // PengramLinkPlatformYoutubeRow (SECTION_LINK_ASSISTANT)
            0x38e3dccc, // PengramLinkPlatformTiktokRow (SECTION_LINK_ASSISTANT)
            0x3fe63294, // PengramLinkPlatformInstagramRow (SECTION_LINK_ASSISTANT)
            0x1ce2cc52, // PengramLinkPlatformTwitterRow (SECTION_LINK_ASSISTANT)
            0x1cb31816, // PengramLinkPlatformPinterestRow (SECTION_LINK_ASSISTANT)
            0x3d1e253e, // PengramLinkPlatformVkRow (SECTION_LINK_ASSISTANT)
            0x1f1d3f34, // PengramLinkPlatformRutubeRow (SECTION_LINK_ASSISTANT)
            0x320e5896, // PengramLinkPlatformRedditRow (SECTION_LINK_ASSISTANT)
            0x305b6ff3, // PengramLinkPlatformSoundcloudRow (SECTION_LINK_ASSISTANT)
            0x10b76be2, // PengramLinkPlatformYandexRow (SECTION_LINK_ASSISTANT)
            0x1d7dcd31, // PengramLinkPreviewMode (SECTION_LINK_ASSISTANT)
            0x3b85fcfe, // PengramLinkIslandPreviewBtn (SECTION_LINK_ASSISTANT)
            0x3debb3c7, // PengramLinkLivePhotos (SECTION_LINK_ASSISTANT)
            0x12abf83a, // PengramLinkPhotoStitchMode (SECTION_LINK_ASSISTANT)
            0x3c0992cf, // PengramLinkSlideAudio (SECTION_LINK_ASSISTANT)
            0x30cfb07d, // PengramLinkSlideDuration (SECTION_LINK_ASSISTANT)
            0x151c1c6d, // PengramLinkFallbackMode (SECTION_LINK_ASSISTANT)
            0x3c27d54b, // PengramLinkCaptionMode (SECTION_LINK_ASSISTANT)
            0x152e8766, // PengramQuickAdd (SECTION_CHAT_ACTIONS)
            0x11d47eb8, // PengramCopySignAuthor (SECTION_CHAT_ACTIONS)
            0x35e5b650, // PengramForwardLock (SECTION_CHAT_ACTIONS)
            0x1f227930, // PengramForwardDoneAlert (SECTION_CHAT_ACTIONS)
            0x3fdf2e36, // PengramChatLook (SECTION_CHAT_MESSAGES)
            0x1f17c06f, // PengramServiceJoinLeaveProfile (SECTION_CHAT_MESSAGES)
            0x309c900f, // PengramKeepFormatting (SECTION_CHAT_MESSAGES)
            0x3d8b1692, // PengramBubbleGlass (SECTION_CHAT_MESSAGES)
            0x1fd00062, // PengramBubbleGlassOpacity (SECTION_CHAT_MESSAGES)
            0x3198ec8c, // PengramBubbleWallpaperTint (SECTION_CHAT_MESSAGES)
            0x12ee5a67, // PengramBubbleRim (SECTION_CHAT_MESSAGES)
            0x37eda76f, // PengramBubbleRimStrength (SECTION_CHAT_MESSAGES)
            0x1df9277e, // PengramBubbleShadowMode (SECTION_CHAT_MESSAGES)
            0x352644df, // PengramBubbleShadowStrength (SECTION_CHAT_MESSAGES)
            0x16e737ec, // PengramBubbleOuterRadius (SECTION_CHAT_MESSAGES)
            0x35a88b1a, // PengramBubbleInnerRadius (SECTION_CHAT_MESSAGES)
            0x3aba5269, // PengramHideTail (SECTION_CHAT_MESSAGES)
            0x33746d5b, // PengramForceDeleteForAll (SECTION_CHAT_MESSAGES)
            0x3d59d9a4, // PengramMenuCopyMessageId (SECTION_CHAT_MESSAGES)
            0x373b3f66, // PengramMenuSaveToSaved (SECTION_CHAT_MESSAGES)
            0x1d304733, // PengramAIMenu (SECTION_CHAT_MESSAGES)
            0x3943cd78, // PengramMessageIslandEnable (SECTION_CHAT_MESSAGES)
            0x10aa6fa1, // PengramMessageIslandButtons (SECTION_CHAT_MESSAGES)
            0x1228f607, // PengramMessageIslandLabels (SECTION_CHAT_MESSAGES)
            0x1bf96c84, // PengramMessageIslandCompact (SECTION_CHAT_MESSAGES)
            0x15e52d9f, // PengramMessageIslandLinkTrash (SECTION_CHAT_MESSAGES)
            0x1ef9f6fe, // PengramMessageIslandHaptic (SECTION_CHAT_MESSAGES)
            0x125ba0b5, // PengramMessageIslandPosition (SECTION_CHAT_MESSAGES)
            0x132056c3, // PengramMessageIslandTint (SECTION_CHAT_MESSAGES)
            0x1a415222, // PengramMentionName (SECTION_CHAT_MESSAGES)
            0x3278a4a7, // PengramMentionAll (SECTION_CHAT_MESSAGES)
            0x38daf8fe, // PengramMentionAllMode (SECTION_CHAT_MESSAGES)
            0x334c142f, // PengramMentionAllLimit (SECTION_CHAT_MESSAGES)
            0x315fab66, // PengramMentionAllCooldown (SECTION_CHAT_MESSAGES)
            0x378c225d, // PengramMentionAllStyle (SECTION_CHAT_MESSAGES)
            0x3dfea57a, // PengramQuoteTapAction (SECTION_QUOTES)
            0x3f4aa12a, // PengramQuoteNames (SECTION_QUOTES)
            0x33416e86, // PengramQuotePeerNameColor (SECTION_QUOTES)
            0x39b8c5a1, // PengramQuoteShowBadges (SECTION_QUOTES)
            0x3cc876d1, // PengramQuoteFakeName (SECTION_QUOTES)
            0x1d5957a7, // PengramQuoteAvatar (SECTION_QUOTES)
            0x3ae391d8, // PengramQuoteAvatarLayout (SECTION_QUOTES)
            0x1a93bfc6, // PengramQuoteAvatarRounded (SECTION_QUOTES)
            0x1d01c0cc, // PengramQuoteAvatarSize (SECTION_QUOTES)
            0x120e4129, // PengramQuoteAvatarGap (SECTION_QUOTES)
            0x1841ea8b, // PengramQuoteShowReply (SECTION_QUOTES)
            0x35c0d605, // PengramQuoteShowForward (SECTION_QUOTES)
            0x315ab512, // PengramQuoteShowReactions (SECTION_QUOTES)
            0x1f601b82, // PengramQuoteAnonMentions (SECTION_QUOTES)
            0x345c0d9d, // PengramQuoteTimes (SECTION_QUOTES)
            0x36e0f1f1, // PengramQuoteDateInTime (SECTION_QUOTES)
            0x37c9c2d9, // PengramQuoteMedia (SECTION_QUOTES)
            0x13771649, // PengramQuoteResetAppearance (SECTION_QUOTES)
            0x3079c1bc, // PengramQuoteBackground (SECTION_QUOTES)
            0x1adf7db5, // PengramQuoteBackgroundColor (SECTION_QUOTES)
            0x11254916, // PengramQuoteStickerTransparent (SECTION_QUOTES)
            0x13a15173, // PengramQuoteAccent (SECTION_QUOTES)
            0x33b2d44f, // PengramQuoteDark (SECTION_QUOTES)
            0x1dea120d, // PengramQuoteBubbleTail (SECTION_QUOTES)
            0x159428db, // PengramQuoteAlignOutgoing (SECTION_QUOTES)
            0x3d197b7d, // PengramQuoteScale (SECTION_QUOTES)
            0x1ce195e4, // PengramQuotePadding (SECTION_QUOTES)
            0x382c3673, // PengramQuoteRadius (SECTION_QUOTES)
            0x305981d7, // PengramQuoteJpeg (SECTION_QUOTES)
            0x3b484745, // PengramQuoteQuality (SECTION_QUOTES)
            0x364dd133, // PengramQuoteWatermark (SECTION_QUOTES)
            0x1aaab8c7, // PengramQuoteWatermarkText (SECTION_QUOTES)
            0x1dd278b1, // PengramQuoteWatermarkPosition (SECTION_QUOTES)
            0x39d8f7e8, // PengramQuoteLogo (SECTION_QUOTES)
            0x3728de17, // PengramQuoteLogoClear (SECTION_QUOTES)
            0x12f15aac, // PengramQuoteLogoSize (SECTION_QUOTES)
            0x3bfec8ef, // PengramQuoteWatermarkOpacity (SECTION_QUOTES)
            0x39181370, // PengramInputToolsSection (SECTION_TYPING)
            0x3b9ca751, // PengramSendStyle (SECTION_TYPING)
            0x3698be2e, // PengramSendStyleLargeEmoji (SECTION_TYPING)
            0x1cc904f1, // PengramSendStyleCaptions (SECTION_TYPING)
            0x124f514f, // PengramTypingMaster (SECTION_TYPING)
            0x3fb66b0d, // PengramInputAnimation (SECTION_TYPING)
            0x3f0118aa, // PengramTypingJuicyPreset (SECTION_TYPING)
            0x37f4c757, // PengramInputAnimationSpeed (SECTION_TYPING)
            0x15dbc560, // PengramInputAnimationIntensity (SECTION_TYPING)
            0x1299115d, // PengramInputAnimationHaptic (SECTION_TYPING)
            0x3ca42a88, // PengramTypingBlur (SECTION_TYPING)
            0x3e7188eb, // PengramTypingBlurRadius (SECTION_TYPING)
            0x36b5fd1a, // PengramTypingIgnoreSpaces (SECTION_TYPING)
            0x1e9f54ec, // PengramTypingAllLines (SECTION_TYPING)
            0x1c598b32, // PengramTypingDelete (SECTION_TYPING)
            0x3e8cbf88, // PengramTypingDeleteStyle (SECTION_TYPING)
            0x3400bd53, // PengramTypingDeleteCount (SECTION_TYPING)
            0x3e9406c1, // PengramTypingDeleteSpeed (SECTION_TYPING)
            0x1bf713c4, // PengramTypingDeleteSpread (SECTION_TYPING)
            0x1afd1847, // PengramTypingDeleteSize (SECTION_TYPING)
            0x16434f02, // PengramTypingCursorSmooth (SECTION_TYPING)
            0x15334a33, // PengramTypingCursorSpeed (SECTION_TYPING)
            0x1560b829, // PengramTypingCursorWidth (SECTION_TYPING)
            0x3af846be, // PengramTypingCursorLiquid (SECTION_TYPING)
            0x3356109b, // PengramTypingCursorStretch (SECTION_TYPING)
            0x1ac9318f, // PengramTypingSelectionLiquid (SECTION_TYPING)
            0x103210b3, // PengramTypingStatsEnable (SECTION_TYPING)
            0x364ff4df, // PengramTypingStatsBadge (SECTION_TYPING)
            0x3e807865, // PengramTypingStatsClear (SECTION_TYPING)
            0x327bb5d4, // PengramInlineWebSearch (SECTION_INPUT_TOOLS)
            0x3a540b83, // PengramWebSearchPrefix (SECTION_INPUT_TOOLS)
            0x1733588a, // PengramWebSearchEngine (SECTION_INPUT_TOOLS)
            0x17c6aabf, // PengramWebSearchHistory (SECTION_INPUT_TOOLS)
            0x1cd0b552, // PengramInlineCalc (SECTION_INPUT_TOOLS)
            0x11ee006b, // PengramInlineCurrency (SECTION_INPUT_TOOLS)
            0x3716142c, // PengramCalcDefaultCurrency (SECTION_INPUT_TOOLS)
            0x10e3f8e1, // PengramCalcRefreshRates (SECTION_INPUT_TOOLS)
            0x1361bea8, // PengramMediaTimeDefault (SECTION_CHAT_MESSAGES)
            0x14f53d03, // PengramMediaTimeStickers (SECTION_CHAT_MESSAGES)
            0x39bfce64, // PengramMediaTimeMedia (SECTION_CHAT_MESSAGES)
            0x1b048c6b, // PengramMediaTimeRound (SECTION_CHAT_MESSAGES)
            0x3911a98c, // PengramMediaTimeSending (SECTION_CHAT_MESSAGES)
            0x19df8a2e, // PengramAvatarShape (SECTION_CHAT_INTERFACE)
            0x3a3f24e9, // PengramHideWriteButton (SECTION_CHAT_INTERFACE)
            0x191824b2, // PengramThreeLines (SECTION_CHAT_INTERFACE)
            0x393bcd34, // PengramHideArchive (SECTION_CHAT_INTERFACE)
            0x3575b4bf, // PengramHideStories (SECTION_CHAT_INTERFACE)
            0x34d6128f, // PengramSenderAvatars (SECTION_CHAT_INTERFACE)
            0x3e59107d, // PengramSenderAvatarPosition (SECTION_CHAT_INTERFACE)
            0x33d55341, // PengramSwipeAction (SECTION_CHAT_INTERFACE)
            0x3821536c, // PengramChatMenu (SECTION_CHAT_MENUS)
            0x1e13e0df, // PengramChatMenuTop (SECTION_CHAT_MENUS)
            0x324fd549, // PengramChatMenuBottom (SECTION_CHAT_MENUS)
            0x3184b510, // PengramMenuItemsTitle (SECTION_CHAT_MENUS)
            0x176bc91c, // PengramMenuReadAll (SECTION_CHAT_MENUS)
            0x1d821a1f, // PengramSettingsItemsTitle (SECTION_CHAT_MENUS)
            0x331c82d3, // PengramChatItemsTitle (SECTION_CHAT_MENUS)
            0x390793f8, // PengramCardOnTop (SECTION_CHAT_MENUS)
            0x32febb23, // PengramHideChatSearch (SECTION_CHAT_MENUS)
            0x34048d29, // PengramHideChatTranslate (SECTION_CHAT_MENUS)
            0x39d647b7, // PengramHideChatClear (SECTION_CHAT_MENUS)
            0x3cbb3f62, // PengramHideChatWallpaper (SECTION_CHAT_MENUS)
            0x3ebafa99, // PengramHideChatShortcut (SECTION_CHAT_MENUS)
            0x32bf3c20, // PengramHideChatReport (SECTION_CHAT_MENUS)
            0x1f9e66ef, // PengramHideChatCall (SECTION_CHAT_MENUS)
            0x34797da0, // PengramHideChatAutoDelete (SECTION_CHAT_MENUS)
            0x1c143a49, // PengramHideChatShareNumber (SECTION_CHAT_MENUS)
            0x3fed4013, // PengramAIAutoJump (SECTION_AI)
            0x10106019, // PengramAIServices (SECTION_AI)
            0x1fe643c5, // PengramAIEditService (SECTION_AI)
            0x3c40e0ea, // PengramAIAddService (SECTION_AI)
            0x3141b738, // PengramAIRoles (SECTION_AI)
            0x37978b30, // PengramAIEditRole (SECTION_AI)
            0x16486369, // PengramAIAddRole (SECTION_AI)
            0x344c2692, // PengramAIStream (SECTION_AI)
            0x1003ff76, // PengramAIOnlyAnswer (SECTION_AI)
            0x1df14b9e, // PengramAIAsQuote (SECTION_AI)
            0x3a93e757, // PengramAIHistory (SECTION_AI)
            0x36a4268c, // PengramAIDepth (SECTION_AI)
            0x313122bc, // PengramAIAutoMaster (SECTION_AI)
            0x1f68811d, // PengramAIAutoContext (SECTION_AI)
            0x374015d3, // PengramAIAutoContextLimit (SECTION_AI)
            0x37b7460c, // PengramAIAutoStyle (SECTION_AI)
            0x1d840053, // PengramAIAutoAdvanced (SECTION_AI)
            0x1e2d1227, // PengramAIAutoQuiet (SECTION_AI)
            0x1e450bbe, // PengramAIAutoCooldown (SECTION_AI)
            0x1d9fac87, // PengramAIAutoSpamCount (SECTION_AI)
            0x1b4fe532, // PengramAIAutoSpamPause (SECTION_AI)
            0x323c2c91, // PengramAIAutoAdd (SECTION_AI)
            0x150a18b1, // PengramAIAutoRefresh (SECTION_AI)
            0x1e6d9b05, // PengramAIReset (SECTION_AI)
            0x174919ac, // PengramVoiceTrolling (SECTION_MEDIA)
            0x3585f382, // PengramAvatarPos (SECTION_CHAT_MESSAGES)
            0x3d4293eb, // PengramDialogAvatars (SECTION_CHAT_MESSAGES)
            0x3d83de2d, // PengramProfileHistoryEnable (SECTION_HISTORY)
            0x1bfae09f, // PengramProfileHistoryStorage (SECTION_HISTORY)
            0x39a84548, // PengramProfileCloudFormat (SECTION_HISTORY)
            0x1e0408e8, // PengramProfileCloudRecreate (SECTION_HISTORY)
            0x15c3fcf1, // PengramProfileHistoryScope (SECTION_HISTORY)
            0x3cb2b8f8, // PengramProfileHistoryBio (SECTION_HISTORY)
            0x10e00d5c, // PengramProfileHistoryAvatars (SECTION_HISTORY)
            0x3b275a6a, // PengramProfileLimitSize (SECTION_HISTORY)
            0x1f7c699c, // PengramProfileLimitTime (SECTION_HISTORY)
            0x327cc2f2, // PengramProfileHistoryOpen (SECTION_HISTORY)
            0x1b9220c1, // PengramProfileHistoryClearAvatars (SECTION_HISTORY)
    };
}
