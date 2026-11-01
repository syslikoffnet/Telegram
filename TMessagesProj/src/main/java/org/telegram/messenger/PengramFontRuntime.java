package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.WeakHashMap;

/** Applies the selected Pengram UI font to live screens as well as newly-created text. */
public final class PengramFontRuntime {
    private static final WeakHashMap<Activity, Boolean> resumedActivities = new WeakHashMap<>();
    /** Views explicitly changed by us, so app-selected monospace can be switched back without restyling code. */
    private static final WeakHashMap<View, Integer> appliedFontByView = new WeakHashMap<>();
    private static boolean installed;

    private PengramFontRuntime() {
    }

    public static void install(Application application) {
        if (installed || application == null) return;
        installed = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                synchronized (resumedActivities) {
                    resumedActivities.put(activity, true);
                }
                apply(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                synchronized (resumedActivities) {
                    resumedActivities.remove(activity);
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                synchronized (resumedActivities) {
                    resumedActivities.remove(activity);
                }
            }
        });
    }

    /** Called immediately when the user changes the app-wide font. */
    public static void onFontChanged() {
        AndroidUtilities.clearPengramTypefaceCaches();
        Theme.applyPengramFontToPaints();
        final ArrayList<Activity> activities = new ArrayList<>();
        synchronized (resumedActivities) {
            activities.addAll(resumedActivities.keySet());
        }
        for (Activity activity : activities) {
            if (activity != null && !activity.isFinishing()) {
                apply(activity);
            }
        }
    }

    private static void apply(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) return;
        final View decor = activity.getWindow().getDecorView();
        apply(decor, PengramConfig.getAppFont());
        decor.invalidate();
    }

    private static void apply(View view, int font) {
        if (view == null) return;
        try {
            if (view instanceof TextView || view instanceof SimpleTextView) {
                final Typeface current = view instanceof TextView
                        ? ((TextView) view).getTypeface()
                        : ((SimpleTextView) view).getTypeface();
                final int style = appStyle(current);
                final Integer previousFont;
                synchronized (appliedFontByView) {
                    previousFont = appliedFontByView.get(view);
                }
                final boolean wasAppliedByPengram = previousFont != null
                        && matches(PengramFonts.typeface(previousFont, style), current);
                if (isTelegramTextTypeface(current, style, wasAppliedByPengram)) {
                    final Typeface selected = PengramFonts.typeface(font, style);
                    if (view instanceof TextView) {
                        ((TextView) view).setTypeface(selected);
                    } else {
                        ((SimpleTextView) view).setTypeface(selected);
                    }
                    synchronized (appliedFontByView) {
                        appliedFontByView.put(view, font);
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                apply(group.getChildAt(i), font);
            }
        }
    }

    private static boolean matches(Typeface expected, Typeface actual) {
        return expected != null && actual != null && (expected == actual || expected.equals(actual));
    }

    private static int appStyle(Typeface current) {
        final int style = current == null ? Typeface.NORMAL : current.getStyle();
        final int telegramStyle = AndroidUtilities.telegramTypefaceStyle(current, style);
        return telegramStyle == Integer.MIN_VALUE ? style : telegramStyle;
    }

    /** Do not replace code/editor and icon typefaces; update Android/Telegram UI text and our own families. */
    private static boolean isTelegramTextTypeface(Typeface current, int style, boolean wasAppliedByPengram) {
        if (isMonospace(current, style) && !wasAppliedByPengram) return false;
        if (current == null) return true;
        if (PengramFonts.isSelectableTypeface(current)) return true;
        if (AndroidUtilities.isTelegramTextTypeface(current, style)) return true;
        try {
            return current.equals(Typeface.DEFAULT)
                    || current.equals(Typeface.DEFAULT_BOLD)
                    || current.equals(Typeface.SANS_SERIF)
                    || current.equals(Typeface.create("sans-serif", style));
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean isMonospace(Typeface typeface, int style) {
        if (typeface == null) return false;
        try {
            return typeface.equals(Typeface.MONOSPACE)
                    || typeface.equals(Typeface.create("monospace", style));
        } catch (Throwable ignore) {
            return false;
        }
    }
}
