package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengram: горизонтальный островок быстрых действий, прикреплённый к меню
 * сообщения. Островок живёт в том же поповере, что и строки меню, поэтому
 * выглядит его частью, но занимает одну компактную полосу вместо четырёх
 * полноширинных строк.
 *
 * <p>Что внутри — решает пользователь (Настройки → Pengram → Сообщения →
 * Островок действий): набор кнопок, их порядок, подписи, размер, подсветку и
 * сторону, с которой полоса примыкает к меню. Действия, уехавшие в островок,
 * из списка меню убираются — иначе смысла в нём нет.
 */
public class PengramMessageIslandView extends LinearLayout {

    public interface Listener {
        /**
         * @param position индекс нажатой кнопки в текущем наборе островка
         * @param view     сама кнопка — удобно для ripple/анимации
         */
        void onIslandAction(int position, View view);
    }

    private final Theme.ResourcesProvider resourcesProvider;

    @Nullable
    private Listener listener;
    private boolean preview;

    private final ArrayList<Integer> actions = new ArrayList<>();
    private final ArrayList<View> buttons = new ArrayList<>();

    private int backgroundColor;
    private int iconColor;
    private int labelColor;
    private int selectorColor;
    private boolean accentFill;

    private final int iconSize;
    private final int itemHeight;
    private final int itemMinWidth;
    private final int itemMaxWidth;
    private final int screenLimit;
    private final int labelSizeSp;
    private final boolean labels;
    private final boolean compact;

    public PengramMessageIslandView(Context context, @Nullable Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        final float fontScale = context.getResources().getConfiguration().fontScale;
        compact = PengramConfig.isMessageIslandCompact();
        labels = PengramConfig.isMessageIslandLabels();
        iconSize = AndroidUtilities.dp(compact ? 21 : 23);
        labelSizeSp = compact ? 10 : 11;
        itemMinWidth = AndroidUtilities.dp(compact ? 38 : 42);
        // С подписями колонка шире: короткое слово должно влезать целиком,
        // а не превращаться в «Пересл…».
        itemMaxWidth = AndroidUtilities.dp(labels ? (compact ? 70 : 78) : (compact ? 52 : 58));
        // Полоса не должна вылезти за экран даже с шестью кнопками: ширину
        // кнопки считаем от доступного места, а не от весов.
        screenLimit = context.getResources().getDisplayMetrics().widthPixels - AndroidUtilities.dp(64);
        final int vertical = AndroidUtilities.dp(compact ? 4 : 5);
        itemHeight = AndroidUtilities.dp(compact ? 28 : 32)
                + vertical * 2
                + (labels ? (int) Math.ceil(labelSizeSp * fontScale * 1.35f) + AndroidUtilities.dp(1) : 0);
        setPadding(AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3));
        setWillNotDraw(true);
    }

    /** живой предпросмотр в конструкторе: кнопки не нажимаются и не вибрируют */
    public void setPreview(boolean preview) {
        this.preview = preview;
        for (View button : buttons) {
            button.setClickable(!preview);
            button.setFocusable(!preview);
        }
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public int getItemCount() {
        return actions.size();
    }

    /** Высота полосы вместе с внутренними отступами — нужна поповеру для замера. */
    public int getIslandHeight() {
        return itemHeight + getPaddingTop() + getPaddingBottom();
    }

    /**
     * Пересобирает полосу. Пустой список просто прячет островок: вызывающая
     * сторона сама решает, добавлять ли его в меню вообще.
     */
    public void setActions(@Nullable List<Integer> value) {
        actions.clear();
        if (value != null) {
            for (Integer id : value) {
                if (id != null && !actions.contains(id) && actions.size() < PengramConfig.MESSAGE_ISLAND_LIMIT) {
                    actions.add(id);
                }
            }
        }
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        buttons.clear();
        if (actions.isEmpty()) {
            setMinimumWidth(0);
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        resolveColors();
        setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(compact ? 14 : 16), backgroundColor));

        // Ширина колонки = самая длинная подпись + отступы, но не уже минимума
        // и не шире остатка экрана, поделённого на число кнопок.
        final int available = screenLimit - getPaddingLeft() - getPaddingRight();
        final int fitWidth = Math.max(itemMinWidth, available / Math.max(1, actions.size()));
        int perItem = Math.min(itemMaxWidth, fitWidth);
        if (labels) {
            perItem = Math.max(perItem, Math.min(itemMaxWidth, widestLabelWidth() + AndroidUtilities.dp(6)));
            perItem = Math.min(perItem, Math.max(itemMinWidth, fitWidth));
        }
        for (int a = 0; a < actions.size(); a++) {
            final int action = actions.get(a);
            final View button = createButton(action);
            final int position = a;
            button.setOnClickListener(view -> {
                if (preview || listener == null) {
                    return;
                }
                if (PengramConfig.isMessageIslandHaptic()) {
                    try {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                    } catch (Throwable ignore) {
                    }
                }
                listener.onIslandAction(position, view);
            });
            // Фиксированная ширина вместо веса: иначе LinearLayout растянул бы
            // полосу на весь AT_MOST-размер и поповер стал бы во весь экран.
            addView(button, new LinearLayout.LayoutParams(perItem, itemHeight));
            buttons.add(button);
        }
        requestLayout();
    }

    private View createButton(int action) {
        final LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setGravity(Gravity.CENTER);

        final ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        final Drawable drawable = getResources().getDrawable(PengramConfig.getMessageIslandItemIcon(action)).mutate();
        drawable.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        icon.setImageDrawable(drawable);
        column.addView(icon, LayoutHelper.createLinear(iconSize, iconSize, Gravity.CENTER_HORIZONTAL));

        final CharSequence title = LocaleController.getString(PengramConfig.getMessageIslandItemTitle(action));
        if (labels) {
            final TextView label = new TextView(getContext());
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp);
            label.setTextColor(labelColor);
            // В колонке — короткая подпись, полная остаётся в contentDescription
            // и в редакторе меню.
            label.setText(LocaleController.getString(PengramConfig.getMessageIslandItemShortTitle(action)));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            column.addView(label, LayoutHelper.createLinear(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Вся площадь кнопки — одна кликабельная поверхность: риппл должен
        // расходиться по всей ширине, а не только по иконке с подписью.
        final LinearLayout wrapper = new LinearLayout(getContext());
        wrapper.setOrientation(HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER);
        wrapper.addView(column, LayoutHelper.createLinear(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        wrapper.setContentDescription(title);
        wrapper.setBackground(Theme.createRadSelectorDrawable(selectorColor, 12, 12));
        wrapper.setClickable(!preview);
        wrapper.setFocusable(!preview);
        return wrapper;
    }

    /** ширина самой длинной короткой подписи в пикселях */
    private int widestLabelWidth() {
        final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                labelSizeSp, getResources().getDisplayMetrics()));
        int widest = 0;
        for (int action : actions) {
            final CharSequence text = LocaleController.getString(
                    PengramConfig.getMessageIslandItemShortTitle(action));
            if (text != null) {
                widest = Math.max(widest, (int) Math.ceil(paint.measureText(text.toString())));
            }
        }
        return widest;
    }

    private void resolveColors() {
        final int tint = PengramConfig.getMessageIslandTint();
        final int ink = Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final boolean dark = Theme.isCurrentThemeDark();

        accentFill = tint == PengramConfig.MESSAGE_ISLAND_TINT_ACCENT;
        switch (tint) {
            case PengramConfig.MESSAGE_ISLAND_TINT_ACCENT: {
                backgroundColor = accent;
                // Заливка акцентом: подписи и иконки должны читаться на ней,
                // а не сливаться с фоном в тёмной или светлой теме.
                final int onAccent = ColorUtils.calculateLuminance(accent) < 0.5f ? 0xffffffff : 0xff101418;
                iconColor = onAccent;
                labelColor = onAccent;
                selectorColor = Theme.multAlpha(0xffffffff, 0.18f);
                break;
            }
            case PengramConfig.MESSAGE_ISLAND_TINT_PLAIN:
                backgroundColor = Theme.multAlpha(ink, 0.06f);
                iconColor = Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider);
                labelColor = ink;
                selectorColor = Theme.getColor(Theme.key_listSelector, resourcesProvider);
                break;
            case PengramConfig.MESSAGE_ISLAND_TINT_SOFT:
            default:
                backgroundColor = Theme.multAlpha(accent, dark ? 0.16f : 0.11f);
                iconColor = accent;
                // В тёмной теме чистый акцент на 16% подложке читается, в
                // светлой — чуть темнее, чтобы подпись не «светилась».
                labelColor = dark ? accent : ColorUtils.blendARGB(accent, 0xff000000, 0.18f);
                selectorColor = Theme.multAlpha(accent, 0.18f);
                break;
        }
    }

    /** true, когда полоса залита акцентом целиком. */
    public boolean isAccentFill() {
        return accentFill;
    }
}
