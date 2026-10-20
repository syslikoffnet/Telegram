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
    /** для каждого действия: доступно ли оно у этого сообщения */
    private final ArrayList<Boolean> availability = new ArrayList<>();
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
    private final int labelSizeSp;
    private final boolean labels;
    private final boolean compact;
    /** желаемая ширина кнопки: по самой длинной подписи, но в разумных пределах */
    private int desiredItemWidth;
    /** ширина, которая реально влезает в текущий замер (меню может быть уже экрана) */
    private int appliedItemWidth;

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
        itemMinWidth = AndroidUtilities.dp(compact ? 36 : 40);
        // С подписями колонка шире: короткое слово должно влезать целиком,
        // а не превращаться в «Пересл…». Без подписей это обычная иконка.
        // 68dp хватает «Поделиться»; полосу всё равно ужимает onMeasure,
        // поэтому широкий потолок не растягивает островок на весь экран.
        itemMaxWidth = AndroidUtilities.dp(labels ? (compact ? 66 : 68) : (compact ? 44 : 46));
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
        setActions(value, null);
    }

    /**
     * @param enabled по одному флагу на действие: недоступное для этого
     *                сообщения действие остаётся в полосе, но приглушено —
     *                набор кнопок не «прыгает» от сообщения к сообщению.
     */
    public void setActions(@Nullable List<Integer> value, @Nullable List<Boolean> enabled) {
        actions.clear();
        availability.clear();
        if (value != null) {
            for (int i = 0; i < value.size(); i++) {
                final Integer id = value.get(i);
                if (id == null || actions.contains(id) || actions.size() >= PengramConfig.MESSAGE_ISLAND_LIMIT) {
                    continue;
                }
                actions.add(id);
                final Boolean flag = enabled != null && i < enabled.size() ? enabled.get(i) : null;
                availability.add(flag == null || flag);
            }
        }
        rebuild();
    }

    private boolean isAvailable(int position) {
        return position < 0 || position >= availability.size() || availability.get(position);
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

        // Желаемая ширина: самая длинная подпись + боковые отступы. Реальную
        // подгоняем в onMeasure — там уже известна ширина меню на экране.
        desiredItemWidth = labels
                ? Math.max(itemMinWidth, Math.min(itemMaxWidth, widestLabelWidth() + AndroidUtilities.dp(8)))
                : itemMaxWidth;
        appliedItemWidth = desiredItemWidth;
        for (int a = 0; a < actions.size(); a++) {
            final int action = actions.get(a);
            final boolean available = isAvailable(a);
            final View button = createButton(action, available);
            final int position = a;
            button.setOnClickListener(view -> {
                if (preview || listener == null || !isAvailable(position)) {
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
            addView(button, new LinearLayout.LayoutParams(desiredItemWidth, itemHeight));
            buttons.add(button);
        }
        requestLayout();
    }

    /**
     * Полоса обязана целиком влезть в меню: строки меню и поповер задают
     * реальную доступную ширину, и она бывает меньше экранной. Раньше кнопки
     * считались от ширины экрана, четвёртая уезжала за край и «пропадала».
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int mode = MeasureSpec.getMode(widthMeasureSpec);
        if (mode != MeasureSpec.UNSPECIFIED && !buttons.isEmpty()) {
            // Поповер шире островка (padding + фон + отступы у края экрана),
            // поэтому одного AT_MOST мало: держим свой потолок по экрану.
            final int screenCap = getResources().getDisplayMetrics().widthPixels
                    - AndroidUtilities.dp(56) - getPaddingLeft() - getPaddingRight();
            int content = Math.max(0, MeasureSpec.getSize(widthMeasureSpec)
                    - getPaddingLeft() - getPaddingRight());
            if (screenCap > 0) {
                content = Math.min(content, screenCap);
            }
            final int fit = content / Math.max(1, buttons.size());
            int width = Math.max(itemMinWidth, Math.min(itemMaxWidth, desiredItemWidth));
            if (fit > 0) {
                // ниже минимума не ужимаем — лучше чуть уже текст, чем нечитаемые кнопки
                width = Math.min(width, Math.max(itemMinWidth, fit));
            }
            if (width != appliedItemWidth) {
                appliedItemWidth = width;
                for (View button : buttons) {
                    ViewGroup.LayoutParams lp = button.getLayoutParams();
                    if (lp != null && lp.width != width) {
                        lp.width = width;
                    }
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private View createButton(int action, boolean available) {
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
        if (available) {
            wrapper.setBackground(Theme.createRadSelectorDrawable(selectorColor, 12, 12));
        }
        wrapper.setClickable(!preview && available);
        wrapper.setFocusable(!preview && available);
        // Недоступное действие видно, но не обещает нажатия.
        wrapper.setAlpha(available ? 1f : 0.42f);
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
