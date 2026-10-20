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

    /** насколько плотно стекло закрашивает размытие (0 — совсем прозрачно) */
    public static float alpha(boolean isDark) {
        return isDark ? 0.38f : 0.44f;
    }

    /** толщина линзы в пикселях: меньше — тоньше и «водянистее» преломление */
    public static int thicknessPx() {
        return Math.max(1, Math.round(AndroidUtilities.dp(16)));
    }

    /** сила преломления в шейдере */
    public static float intensity() {
        return 0.92f;
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
                .setStrokeColorTop(0x38FFFFFF, 0x2AFFFFFF)
                .setStrokeColorBottom(0x14FFFFFF, 0x14FFFFFF)
                .setShadowColor(0x1C000000, 0x04000000)
                .setShadowLayer(AndroidUtilities.dpf2(2.667f), 0, AndroidUtilities.dpf2(0.85f))
                .setStrokeWidth(AndroidUtilities.dpf2(0.5f), AndroidUtilities.dpf2(0.5f))
                .build();
    }
}
