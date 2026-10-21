package org.telegram.ui.Components;

import android.os.Build;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.PengramConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Общая настройка поверхностей: провайдер запекается, поэтому нужен новый drawable. */
public final class PengramGlassSkin {
    public static final int SURFACE_TAB_BAR = 0, SURFACE_INPUT = 1, SURFACE_HEADER = 2;
    private static final ArrayList<WeakReference<Runnable>> listeners = new ArrayList<>();
    private PengramGlassSkin() { }

    public static boolean canRefract() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    public static boolean refractionAllowed() {
        return canRefract() && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
    }

    public static BlurredBackgroundProvider provider(int surface, Theme.ResourcesProvider resources) {
        final int target = surface == SURFACE_INPUT ? Theme.key_chat_messagePanelBackground
                : surface == SURFACE_HEADER ? Theme.key_actionBarDefault : Theme.key_glass_targetMainTabs;
        return new BlurredBackgroundProviderBuilder(resources)
                .setBackgroundColor((r, dark) -> BlurredBackgroundProviderImpl.solveSrcColor(
                        Theme.getColor(Theme.key_windowBackgroundWhite, r), Theme.getColor(target, r),
                        PengramConfig.getGlassOpacity() / 100f))
                .setStrokeColorTop((r, dark) -> Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, r),
                        PengramConfig.isGlassRimEnabled() ? .22f * PengramConfig.getGlassGlow() / 100f : 0))
                .setStrokeColorBottom((r, dark) -> Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, r),
                        PengramConfig.isGlassRimEnabled() ? .08f * PengramConfig.getGlassGlow() / 100f : 0))
                .setShadowColor((r, dark) -> Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefault, r), .12f))
                .setStrokeWidth(PengramConfig.isGlassRimEnabled() ? AndroidUtilities.dpf2(.5f) : 0,
                        PengramConfig.isGlassRimEnabled() ? AndroidUtilities.dpf2(.5f) : 0)
                .setShadowLayer(AndroidUtilities.dpf2(2.667f), 0, AndroidUtilities.dpf2(.85f)).build();
    }

    public static int signature(int surface) {
        int hash = PengramTabBarGlass.signature() * 31 + surface;
        hash = hash * 31 + (PengramConfig.isInputGlass() ? 1 : 0);
        hash = hash * 31 + (PengramConfig.isChatListHeaderGlass() ? 1 : 0);
        hash = hash * 31 + (PengramConfig.isChatHeaderGlass() ? 1 : 0);
        hash = hash * 31 + (refractionAllowed() ? 1 : 0);
        hash = hash * 31 + Theme.getColor(Theme.key_actionBarDefault);
        hash = hash * 31 + Theme.getColor(Theme.key_chat_messagePanelBackground);
        return hash;
    }

    public static void applyToDrawable(BlurredBackgroundDrawable drawable) {
        drawable.setRadius(AndroidUtilities.dp(23));
        drawable.setPadding(AndroidUtilities.dp(7));
        applyLens(drawable);
    }

    public static void applyLens(BlurredBackgroundDrawable drawable) {
        // Геометрию клавиатуры задаёт контейнер; меняем лишь оптику после его setter.
        drawable.setThickness(PengramTabBarGlass.thicknessPx());
        drawable.setIntensity(PengramTabBarGlass.intensity());
    }

    private static boolean observingTheme;
    private static final org.telegram.messenger.NotificationCenter.NotificationCenterDelegate themeObserver =
            (id, account, args) -> notifyChanged();

    public static void register(Runnable listener) {
        unregister(listener);
        listeners.add(new WeakReference<>(listener));
        if (!observingTheme) {
            observingTheme = true;
            org.telegram.messenger.NotificationCenter.getGlobalInstance().addObserver(themeObserver,
                    org.telegram.messenger.NotificationCenter.didSetNewTheme);
        }
        listener.run();
    }

    public static void unregister(Runnable listener) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            Runnable value = listeners.get(i).get();
            if (value == null || value == listener) listeners.remove(i);
        }
        if (listeners.isEmpty() && observingTheme) {
            observingTheme = false;
            org.telegram.messenger.NotificationCenter.getGlobalInstance().removeObserver(themeObserver,
                    org.telegram.messenger.NotificationCenter.didSetNewTheme);
        }
    }

    private static final Runnable dispatch = () -> {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            Runnable listener = listeners.get(i).get();
            if (listener == null) listeners.remove(i);
            else listener.run();
        }
        org.telegram.ui.MainTabsActivity.refreshPengramGlass();
    };

    public static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            AndroidUtilities.cancelRunOnUIThread(dispatch);
            AndroidUtilities.runOnUIThread(dispatch, 80);
        });
    }
}
