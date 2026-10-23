package org.telegram.ui.Components;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

/**
 * Pengram: фон «жидкого стекла» для нижней панели.
 *
 * <p>Штатный {@link BlurredBackgroundProviderImpl#mainTabs(Theme.ResourcesProvider)}
 * заливает размытие почти непрозрачной плёнкой: alpha 0.85 на устройствах с
 * включённым системным liquid glass и 0.76 на остальных. Из-за этого сквозь
 * панель не видно содержимого — получается просто серая плитка, а не стекло.
 *
 * <p>Здесь тот же оттенок темы ({@code key_glass_targetMainTabs}), но с
 * настоящей прозрачностью: контент под панелью читается, а размытие и
 * преломление (RuntimeShader на API 33+) остаются. Кромку и тень делаем чуть
 * заметнее — на прозрачном стекле именно они дают ощущение толщины.
 */
public final class PengramTabBarGlass {

    private PengramTabBarGlass() {
    }

    /**
     * Насколько плотно стекло закрашивает размытие. Значение задаётся
     * пользователем (20…90 %), по умолчанию 42 % — та самая прозрачность, при
     * которой контент под панелью читается, а иконки остаются контрастными.
     */
    public static float alpha(boolean isDark) {
        return org.telegram.messenger.PengramConfig.getGlassOpacity() / 100f;
    }

    /** толщина линзы в пикселях: меньше — тоньше и «водянистее» преломление */
    public static int thicknessPx() {
        return Math.max(1, Math.round(AndroidUtilities.dp(
                org.telegram.messenger.PengramConfig.getGlassThicknessDp())));
    }

    /** сила преломления в шейдере */
    public static float intensity() {
        return org.telegram.messenger.PengramConfig.getGlassIntensity() / 100f;
    }

    /** включён ли светлый кант по кромке */
    public static boolean rimEnabled() {
        return org.telegram.messenger.PengramConfig.isGlassRimEnabled();
    }

    /** множитель яркости бликов под пальцем и под выбранной вкладкой */
    public static float glowScale() {
        return org.telegram.messenger.PengramConfig.getGlassGlow() / 100f;
    }

    public static BlurredBackgroundProvider provider(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    final int colorTarget = Theme.getColor(Theme.key_glass_targetMainTabs, r);
                    return BlurredBackgroundProviderImpl.solveSrcColor(colorBg, colorTarget, alpha(isDark));
                })
                // Светлая тема: белый кант сверху читается как блик на стекле,
                // тёмная — тот же блик, но тише, иначе он «светит» в темноте.
                .setStrokeColorTop(rimEnabled() ? 0x38FFFFFF : 0, rimEnabled() ? 0x2AFFFFFF : 0)
                .setStrokeColorBottom(rimEnabled() ? 0x14FFFFFF : 0, rimEnabled() ? 0x14FFFFFF : 0)
                .setShadowColor(0x1C000000, 0x04000000)
                .setShadowLayer(AndroidUtilities.dpf2(2.667f), 0, AndroidUtilities.dpf2(0.85f))
                .setStrokeWidth(rimEnabled() ? AndroidUtilities.dpf2(0.5f) : 0f,
                        rimEnabled() ? AndroidUtilities.dpf2(0.5f) : 0f)
                .build();
    }

    /**
     * Отпечаток настроек стекла: по нему MainTabsActivity понимает, что фон
     * панели надо пересобрать (альфа, толщина, кант живут в провайдере и
     * запекаются при создании drawable).
     */
    public static int signature() {
        int hash = org.telegram.messenger.PengramConfig.getGlassOpacity();
        hash = hash * 31 + org.telegram.messenger.PengramConfig.getGlassThicknessDp();
        hash = hash * 31 + org.telegram.messenger.PengramConfig.getGlassIntensity();
        hash = hash * 31 + (rimEnabled() ? 1 : 0);
        return hash;
    }
}
