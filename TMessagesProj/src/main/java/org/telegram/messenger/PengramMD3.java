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
    public static final String KEY_SCREENS = "md3Screens";
    /** строка поиска в стиле M3 Search Bar */
    public static final String KEY_SEARCH_BAR = "md3SearchBar";
    /** мягкая тень под строкой поиска (M3 elevated) */
    public static final String KEY_SEARCH_SHADOW = "md3SearchShadow";
    /** всплывающие меню с крупным скруглением */
    public static final String KEY_MENUS = "md3Menus";

    private PengramMD3() {
    }

    public static void notifyChanged() {
        org.telegram.ui.Components.PengramGlassSkin.notifyChanged();
    }

    public static void watch(android.view.View view, Runnable update) {
        // Подписка принадлежит прикреплённому виду, а не статическому экрану:
        // открытый sheet обновится на месте и не останется в памяти после закрытия.
        view.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View attached) {
                org.telegram.ui.Components.PengramGlassSkin.register(update);
            }
            @Override public void onViewDetachedFromWindow(android.view.View detached) {
                org.telegram.ui.Components.PengramGlassSkin.unregister(update);
            }
        });
        if (view.isAttachedToWindow()) org.telegram.ui.Components.PengramGlassSkin.register(update);
        else update.run();
    }

    public static void styleSheetHeader(android.view.ViewGroup header, Theme.ResourcesProvider resources,
                                        org.telegram.ui.Components.PengramSurfaceGlass glass) {
        final android.graphics.drawable.Drawable original = header.getBackground();
        final java.util.ArrayList<HeaderColor> colors = new java.util.ArrayList<>();
        collectHeaderColors(header, colors);
        watch(header, () -> {
            android.graphics.drawable.Drawable background = original;
            if (sheets()) {
                background = sheetBackground(original, true);
                background.setColorFilter(new android.graphics.PorterDuffColorFilter(
                        surface(resources, .05f), android.graphics.PorterDuff.Mode.MULTIPLY));
            }
            if (glass != null) glass.setOriginalBackground(background);
            else {
                final int left = header.getPaddingLeft(), top = header.getPaddingTop();
                final int right = header.getPaddingRight(), bottom = header.getPaddingBottom();
                header.setBackground(background);
                header.setPadding(left, top, right, bottom);
            }
            final boolean tonal = sheets() || glass != null && glass.isRequested();
            for (HeaderColor color : colors) color.apply(tonal, resources);
        });
    }

    private static void collectHeaderColors(android.view.View view, java.util.ArrayList<HeaderColor> colors) {
        if (view instanceof android.widget.TextView || view instanceof android.widget.ImageView) colors.add(new HeaderColor(view));
        if (view instanceof android.view.ViewGroup) {
            final android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectHeaderColors(group.getChildAt(i), colors);
        }
    }

    private static final class HeaderColor {
        final android.view.View view;
        final android.content.res.ColorStateList text, hint;
        final android.graphics.ColorFilter filter;
        HeaderColor(android.view.View view) {
            this.view = view;
            text = view instanceof android.widget.TextView ? ((android.widget.TextView) view).getTextColors() : null;
            hint = view instanceof android.widget.TextView ? ((android.widget.TextView) view).getHintTextColors() : null;
            filter = view instanceof android.widget.ImageView ? ((android.widget.ImageView) view).getColorFilter() : null;
        }
        void apply(boolean tonal, Theme.ResourcesProvider resources) {
            // В светлой теме белые значки поверх новой тональной шапки исчезли
            // бы; при отключении возвращаем точные исходные ColorStateList/фильтр.
            if (view instanceof android.widget.TextView) {
                final android.widget.TextView label = (android.widget.TextView) view;
                if (tonal) {
                    label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resources));
                    label.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray3, resources));
                } else {
                    if (text != null) label.setTextColor(text);
                    if (hint != null) label.setHintTextColor(hint);
                }
            } else if (view instanceof android.widget.ImageView) {
                ((android.widget.ImageView) view).setColorFilter(tonal
                        ? new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_dialogIcon, resources),
                                android.graphics.PorterDuff.Mode.SRC_IN) : filter);
            }
        }
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

    public static boolean menus() {
        return isEnabled() && PengramConfig.getBool(KEY_MENUS, true);
    }

    public static boolean settingsScreen() {
        return isEnabled() && PengramConfig.getBool(KEY_SETTINGS, true);
    }

    /** Тональные подложки больших экранов: профиль, журнал звонков. */
    public static boolean screens() {
        return isEnabled() && PengramConfig.getBool(KEY_SCREENS, true);
    }

    /**
     * Подложка шторки или диалога со скруглением M3 (28dp).
     *
     * Родной девятипатч нужен ради отступов: по ним считается положение
     * содержимого и место для тени. Поэтому отступы берём у него, а рисуем свою
     * фигуру — белую, чтобы уже существующий MULTIPLY-фильтр покрасил её в цвет
     * темы. Тень рисуем сами: девятипатч с ней мы больше не используем.
     */
    public static android.graphics.drawable.Drawable sheetBackground(android.graphics.drawable.Drawable original, boolean topCornersOnly) {
        return roundedContainer(original, AndroidUtilities.dp(28), topCornersOnly);
    }

    /**
     * Подложка всплывающего меню: 16dp вместо мелкого скругления девятипатча.
     * Отступы снова берём у оригинала, поэтому геометрия меню не «прыгает».
     */
    public static android.graphics.drawable.Drawable menuBackground(android.graphics.drawable.Drawable original) {
        return roundedContainer(original, AndroidUtilities.dp(16), false);
    }

    private static android.graphics.drawable.Drawable roundedContainer(android.graphics.drawable.Drawable original, int radius, boolean topCornersOnly) {
        final android.graphics.Rect padding = new android.graphics.Rect();
        if (original != null) {
            original.getPadding(padding);
        }
        return new RoundedContainerDrawable(radius, topCornersOnly, padding);
    }

    /**
     * Скруглённый контейнер с мягкой тенью внутри отступов девятипатча.
     * Тень нарисована несколькими полупрозрачными слоями: это дешевле, чем
     * отдельный bitmap, и корректно работает на любой плотности экрана.
     */
    private static final class RoundedContainerDrawable extends android.graphics.drawable.Drawable {

        private final android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint shadowPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final android.graphics.Path path = new android.graphics.Path();
        private final float[] radii = new float[8];
        private final int paddingLeft;
        private final int paddingTop;
        private final int paddingRight;
        private final int paddingBottom;
        private int alpha = 255;
        private android.graphics.ColorFilter colorFilter;

        RoundedContainerDrawable(int radius, boolean topCornersOnly, android.graphics.Rect padding) {
            fillPaint.setColor(0xFFFFFFFF);
            shadowPaint.setColor(0xFF000000);
            if (topCornersOnly) {
                radii[0] = radii[1] = radius;
                radii[2] = radii[3] = radius;
            } else {
                for (int i = 0; i < radii.length; i++) {
                    radii[i] = radius;
                }
            }
            paddingLeft = padding.left;
            paddingTop = padding.top;
            paddingRight = padding.right;
            paddingBottom = padding.bottom;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect bounds = getBounds();
            final float left = bounds.left + paddingLeft;
            final float top = bounds.top + paddingTop;
            final float right = bounds.right - paddingRight;
            final float bottom = bounds.bottom - paddingBottom;
            if (right <= left || bottom <= top) {
                return;
            }
            final float step = AndroidUtilities.dpf2(0.9f);
            final int layers = 6;
            for (int i = layers; i >= 1; i--) {
                final float grow = step * i;
                rect.set(left - grow, top - grow, right + grow, bottom + grow);
                // Постоянная альфа на слой: у самой фигуры слои складываются в
                // более плотную кромку, к внешнему краю тень плавно сходит.
                shadowPaint.setAlpha(Math.round(alpha * 0.026f));
                final float shadowRadius = Math.max(radii[0], radii[2]) + grow;
                canvas.drawRoundRect(rect, shadowRadius, shadowRadius, shadowPaint);
            }
            rect.set(left, top, right, bottom);
            fillPaint.setAlpha(alpha);
            // Canvas не умеет рисовать RectF с разными радиусами по углам,
            // поэтому фигуру собираем в Path (нужно для шторок: скруглены
            // только верхние углы).
            path.rewind();
            path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW);
            canvas.drawPath(path, fillPaint);
        }

        @Override
        public void setAlpha(int value) {
            if (alpha != value) {
                alpha = value;
                invalidateSelf();
            }
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            if (colorFilter != filter) {
                colorFilter = filter;
                fillPaint.setColorFilter(filter);
                shadowPaint.setColorFilter(filter);
                invalidateSelf();
            }
        }

        @Override
        public boolean getPadding(android.graphics.Rect padding) {
            padding.set(paddingLeft, paddingTop, paddingRight, paddingBottom);
            return paddingLeft != 0 || paddingTop != 0 || paddingRight != 0 || paddingBottom != 0;
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
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
