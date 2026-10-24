package org.telegram.ui.Components;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengram: остров в «трёх точках» чата.
 * Что именно внутри — решает пользователь: любой наш пункт можно перетащить
 * из основного меню в остров и обратно (Настройки → Pengram → Пункты меню чата).
 *
 * <p>Страница — САМАЯ ПРОСТАЯ вёрстка: ScrollView → вертикальный LinearLayout
 * с обычными пунктами. Никаких ActionBarPopupWindowLayout/fitItems/вложенных
 * scrollView — весь этот слой чужой механики уже один раз оставил страницу
 * пустой и чёрной. Фон и цвета пунктов считаются от одного источника
 * ({@link #pageBackground()}) — текст виден при любом разрешении темы.
 *
 * <p>Состав пересобирается прямо на открытом чате через {@link #setItems(List)},
 * а если штатная сборка вдруг упадёт — {@link #buildEmergencyPage} отдаёт
 * полноценную (пусть простую) страницу, но НИКОГДА не пустую.
 */
public class PengramChatMenuWrapper {

    /** Корень страницы — то, что уходит в swipe-back попапа. */
    public final ScrollView page;
    /** Контент страницы (тёлка для пунктов) — поле сохранено для контрактов. */
    public final LinearLayout windowLayout;

    public interface Callback {
        void dismiss();
        void onItem(int itemId);
    }

    private final int account;
    private final long dialogId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Callback callback;
    private final ArrayList<Integer> items = new ArrayList<>();

    public PengramChatMenuWrapper(Context context, int account, long dialogId,
                                  List<Integer> items, Theme.ResourcesProvider resourcesProvider, Callback callback) {
        this.account = account;
        this.dialogId = dialogId;
        this.resourcesProvider = resourcesProvider;
        this.callback = callback;
        page = new ScrollView(context);
        windowLayout = new LinearLayout(context);
        windowLayout.setOrientation(LinearLayout.VERTICAL);
        windowLayout.setBackground(new android.graphics.drawable.ColorDrawable(pageBackground()));
        windowLayout.setMinimumWidth(AndroidUtilities.dp(220));
        page.addView(windowLayout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setItems(items);
    }

    /** новый набор пунктов: пересобираемся содержимое на месте */
    public void setItems(List<Integer> newItems) {
        items.clear();
        if (newItems != null) {
            for (Integer id : newItems) {
                if (id != null && !items.contains(id)) {
                    items.add(id);
                }
            }
        }
        rebuild();
    }

    private void rebuild() {
        // Сборка по-элементно: исключение на одном пункте не должно оставлять
        // пустую чёрную страницу — остальные пункты и кнопка закрытия обязаны
        // присутствовать всегда.
        windowLayout.removeAllViews();
        try {
            final ActionBarMenuSubItem closeItem = ActionBarMenuItem.addItem(windowLayout, R.drawable.msg_arrow_back,
                    LocaleController.getString(R.string.Back), false, resourcesProvider);
            closeItem.setOnClickListener(view -> callback.dismiss());
            applyContrastColors(closeItem);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        for (int a = 0; a < items.size(); ++a) {
            try {
                addChatItem(items.get(a));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        final int quickCount = PengramConfig.getQuickActionCount();
        for (int i = 0; i < quickCount; i++) {
            try {
                addQuickAction(i);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private void addChatItem(final int id) {
        int icon = PengramConfig.getChatItemIcon(id);
        if (id == PengramConfig.CHAT_ITEM_VIEW_DELETED) {
            final int markIcon = PengramConfig.getMarkIcon(PengramConfig.MARK_TRASH);
            if (markIcon != 0) {
                icon = markIcon;
            }
        }
        final ActionBarMenuSubItem item = ActionBarMenuItem.addItem(windowLayout, icon,
                LocaleController.getString(PengramConfig.getChatItemTitle(id)), false, resourcesProvider);
        if (id == PengramConfig.CHAT_ITEM_VIEW_DELETED) {
            // меню должно открыться мгновенно, поэтому число берём из кэша
            // и дорисовываем подпись, когда счётчик досчитается в фоне
            final int count = PengramHistory.getCountCached(account, dialogId, () -> {
                final int fresh = PengramHistory.getCountCached(account, dialogId, null);
                if (fresh > 0 && item.isAttachedToWindow()) {
                    item.setSubtext(LocaleController.formatPluralString("PengramSavedMessagesCount", fresh));
                }
            });
            if (count > 0) {
                item.setSubtext(LocaleController.formatPluralString("PengramSavedMessagesCount", count));
            }
        }
        item.setOnClickListener(view -> {
            callback.dismiss();
            callback.onItem(id);
        });
        applyContrastColors(item);
    }

    private void addQuickAction(final int index) {
        final int icon = PengramConfig.getQuickActionType(index) == PengramConfig.QUICK_ACTION_FORWARD
                ? R.drawable.msg_forward : R.drawable.msg_message;
        final ActionBarMenuSubItem quick = ActionBarMenuItem.addItem(windowLayout, icon,
                PengramConfig.getQuickActionName(index), false, resourcesProvider);
        quick.setOnClickListener(view -> {
            callback.dismiss();
            callback.onItem(-101 - index);
        });
        applyContrastColors(quick);
    }

    /**
     * Контрастный цвет текста и иконки пункта страницы острова.
     * Фон страницы задан в конструкторе цветом key_actionBarDefaultSubmenuBackground
     * с тем же resourcesProvider; по этому же фону выбираем белый или почти-чёрный
     * цвет пункта. Считаем контраст прямо от фактического фона, чтобы
     * видимость была гарантирована при любом разрешении темы.
     */
    private int contrastForegroundColor() {
        final int bg = pageBackground();
        final int luminance = ((bg >> 16) & 0xFF) * 299 + ((bg >> 8) & 0xFF) * 587 + (bg & 0xFF) * 114;
        return luminance / 1000 < 140 ? 0xFFFFFFFF : 0xFF1C1C1E;
    }

    /** Фактический фон страницы: провайдер темы чата, а при пустом ключе —
     * базовая тема. Один источник и для заливки, и для расчёта контраста —
     * текст пунктов всегда виден на своём фоне. */
    private int pageBackground() {
        int bg = Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider);
        if (bg == 0) {
            bg = Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground);
        }
        return bg;
    }

    private void applyContrastColors(ActionBarMenuSubItem item) {
        final int fg = contrastForegroundColor();
        item.setColors(fg, fg);
        item.setSubtextColor(fg);
    }

    /**
     * Неуязвимая страница на чистых TextView: её отдаёт фабрика, если штатная
     * сборка по какой-то причине упала. Раньше фабрика возвращала пустую
     * заглушку — пользователь видел «пусто, всё чёрное». Теперь даже сбой даёт
     * рабочие кнопки закрытия и пунктов.
     */
    public static View buildEmergencyPage(Context context, Theme.ResourcesProvider provider,
                                          List<Integer> islandItems, Callback callback) {
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMinimumWidth(AndroidUtilities.dp(220));
        int bg = provider != null ? Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, provider) : 0;
        if (bg == 0) {
            bg = Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground);
        }
        root.setBackgroundColor(bg);
        final int lumBg = ((bg >> 16) & 0xFF) * 299 + ((bg >> 8) & 0xFF) * 587 + (bg & 0xFF) * 114;
        final int fg = lumBg / 1000 < 140 ? 0xFFFFFFFF : 0xFF1C1C1E;
        addSimpleRow(context, root, LocaleController.getString(R.string.Back), fg,
                v -> callback.dismiss());
        if (islandItems != null) {
            for (Integer id : islandItems) {
                if (id == null) {
                    continue;
                }
                try {
                    final int itemId = id;
                    addSimpleRow(context, root,
                            LocaleController.getString(PengramConfig.getChatItemTitle(itemId)), fg, v -> {
                                callback.dismiss();
                                callback.onItem(itemId);
                            });
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
        return root;
    }

    private static void addSimpleRow(Context context, LinearLayout root, String text, int fg, View.OnClickListener listener) {
        final TextView row = new TextView(context);
        row.setText(text);
        row.setTextColor(fg);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        final int pad = AndroidUtilities.dp(16);
        row.setPadding(pad, AndroidUtilities.dp(14), pad, AndroidUtilities.dp(14));
        row.setOnClickListener(listener);
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
