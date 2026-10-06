package org.telegram.messenger;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: слой Material Design 3 поверх привычного интерфейса Telegram.
 *
 * Идея — не переписывать темы целиком, а аккуратно подменять формы и тональные
 * подложки там, где это заметно глазу: список чатов, кнопка «написать» и дальше
 * по списку экранов. Всё выключено по умолчанию: включается одним тумблером в
 * настройках внешнего вида, подпункты уточняют, насколько далеко заходить.
 */
public final class PengramMD3 {

    /** главный выключатель всего слоя */
    public static final String KEY_ENABLED = "md3Enabled";
    /** список чатов в стиле M3 */
    public static final String KEY_DIALOGS = "md3Dialogs";
    /** непрочитанные и закреплённые — тональной подложкой */
    public static final String KEY_TONAL_UNREAD = "md3TonalUnread";
    /** вместо разделителей — воздух между строками */
    public static final String KEY_NO_DIVIDERS = "md3NoDividers";
    /** кнопка «написать» со скруглённым квадратом вместо круга */
    public static final String KEY_FAB = "md3Fab";
    /** нижние шторки с крупным скруглением */
    public static final String KEY_SHEETS = "md3Sheets";
    /** диалоги с крупным скруглением */
    public static final String KEY_ALERTS = "md3Alerts";
    /** экран настроек Pengram в тональных тонах */
    public static final String KEY_SETTINGS = "md3Settings";
    /** строка поиска в стиле M3 Search Bar */
    public static final String KEY_SEARCH_BAR = "md3SearchBar";
    /** мягкая тень под строкой поиска (M3 elevated) */
    public static final String KEY_SEARCH_SHADOW = "md3SearchShadow";

    private PengramMD3() {
    }

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, false);
    }

    public static boolean dialogs() {
        return isEnabled() && PengramConfig.getBool(KEY_DIALOGS, true);
    }

    public static boolean tonalRows() {
        return dialogs() && PengramConfig.getBool(KEY_TONAL_UNREAD, true);
    }

    public static boolean hideDividers() {
        return dialogs() && PengramConfig.getBool(KEY_NO_DIVIDERS, true);
    }

    public static boolean searchBar() {
        return isEnabled() && PengramConfig.getBool(KEY_SEARCH_BAR, true);
    }

    public static boolean searchBarShadow() {
        return searchBar() && PengramConfig.getBool(KEY_SEARCH_SHADOW, false);
    }

    /** заливка строки поиска: M3 Search Bar — тональная поверхность, а не серый прямоугольник */
    public static int searchBarColor(Theme.ResourcesProvider resourcesProvider, boolean dark) {
        final int base = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int neutral = ColorUtils.blendARGB(base, dark ? 0xFFFFFFFF : 0xFF000000, dark ? 0.09f : 0.06f);
        return ColorUtils.blendARGB(neutral, accent, 0.1f);
    }

    public static float searchBarRadius() {
        return AndroidUtilities.dp(22);
    }

    public static boolean fab() {
        return isEnabled() && PengramConfig.getBool(KEY_FAB, true);
    }

    public static boolean sheets() {
        return isEnabled() && PengramConfig.getBool(KEY_SHEETS, true);
    }

    public static boolean alerts() {
        return isEnabled() && PengramConfig.getBool(KEY_ALERTS, true);
    }

    public static boolean settingsScreen() {
        return isEnabled() && PengramConfig.getBool(KEY_SETTINGS, true);
    }

    /**
     * Подложка шторки или диалога со скруглением M3 (28dp).
     *
     * Родной девятипатч нужен ради отступов: по ним считается положение
     * содержимого. Поэтому отступы берём у него, а рисуем свою фигуру — белую,
     * чтобы уже существующий MULTIPLY-фильтр покрасил её в цвет темы.
     */
    public static android.graphics.drawable.Drawable sheetBackground(android.graphics.drawable.Drawable original, boolean topCornersOnly) {
        final android.graphics.Rect padding = new android.graphics.Rect();
        if (original != null) {
            original.getPadding(padding);
        }
        final android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(0xFFFFFFFF);
        final float r = AndroidUtilities.dp(28);
        shape.setCornerRadii(topCornersOnly
                ? new float[]{r, r, r, r, 0, 0, 0, 0}
                : new float[]{r, r, r, r, r, r, r, r});
        return new android.graphics.drawable.InsetDrawable(shape,
                padding.left, padding.top, padding.right, padding.bottom);
    }

    /** скругление контейнера строки: M3 Expressive любит крупные радиусы */
    public static float rowRadius() {
        return AndroidUtilities.dp(22);
    }

    /** отступ контейнера строки от краёв экрана */
    public static int rowInset() {
        return AndroidUtilities.dp(8);
    }

    public static int fabRadius() {
        return AndroidUtilities.dp(16);
    }

    /**
     * Тональная поверхность M3: акцент темы подмешивается в фон списка.
     * Так подложка остаётся читаемой и в светлой, и в тёмной теме, и слушается
     * выбранного пользователем цвета оформления.
     */
    public static int surface(Theme.ResourcesProvider resourcesProvider, float amount) {
        final int base = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        return ColorUtils.blendARGB(base, accent, amount);
    }

    public static int unreadContainer(Theme.ResourcesProvider resourcesProvider) {
        return surface(resourcesProvider, 0.11f);
    }

    public static int pinnedContainer(Theme.ResourcesProvider resourcesProvider) {
        return surface(resourcesProvider, 0.05f);
    }

    public static int selectedContainer(Theme.ResourcesProvider resourcesProvider) {
        return surface(resourcesProvider, 0.2f);
    }
}
