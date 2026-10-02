package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Pengram: конструктор меню.
 * Экран собран из нескольких «корзин»: пункты можно перетаскивать между ними
 * (например, из «трёх точек» внутрь острова Pengram и обратно) или прятать совсем.
 * Короткий тап перекидывает пункт в следующую корзину — всё с анимацией.
 */
public class PengramMenuItemsActivity extends BaseFragment {

    /** пункты верхнего меню списка чатов */
    public static final int MODE_MENU = 0;
    /** пункты экрана «Настройки» */
    public static final int MODE_SETTINGS = 1;
    /** наши пункты в «трёх точках» чата */
    public static final int MODE_CHAT = 2;

    private static final int VIEW_TYPE_INFO = 0;
    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_ITEM = 2;
    private static final int VIEW_TYPE_PLACEHOLDER = 3;
    private static final int VIEW_TYPE_SHADOW = 4;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private final ArrayList<Row> rows = new ArrayList<>();
    private final int mode;

    private static class Row {
        final int type;
        final int id;
        int section;

        Row(int type, int id, int section) {
            this.type = type;
            this.id = id;
            this.section = section;
        }
    }

    public PengramMenuItemsActivity() {
        this(MODE_MENU);
    }

    public PengramMenuItemsActivity(int mode) {
        super();
        this.mode = mode;
    }

    /** сколько «корзин» на экране */
    private int sectionCount() {
        return mode == MODE_CHAT ? 3 : 2;
    }

    private int hiddenSection() {
        return sectionCount() - 1;
    }

    private CharSequence sectionTitle(int section) {
        if (mode == MODE_CHAT) {
            if (section == 0) {
                return LocaleController.getString(R.string.PengramPlaceChatMenu);
            } else if (section == 1) {
                return LocaleController.getString(R.string.PengramPlaceIsland);
            }
            return LocaleController.getString(R.string.PengramPlaceHidden);
        }
        return section == 0
                ? LocaleController.getString(R.string.PengramPlaceVisible)
                : LocaleController.getString(R.string.PengramPlaceHidden);
    }

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        return super.onFragmentCreate();
    }

    private ArrayList<Integer> savedOrder() {
        if (mode == MODE_SETTINGS) {
            return PengramConfig.getSettingsOrder();
        } else if (mode == MODE_CHAT) {
            return PengramConfig.getChatItemsOrder();
        }
        final ArrayList<Integer> result = new ArrayList<>();
        for (int id : PengramConfig.getMenuOrder()) {
            result.add(id);
        }
        return result;
    }

    private boolean isHidden(int id) {
        if (mode == MODE_SETTINGS) {
            return PengramConfig.isSettingsItemHidden(id);
        } else if (mode == MODE_CHAT) {
            return PengramConfig.isChatItemHidden(id);
        }
        return PengramConfig.isMenuItemHidden(id);
    }

    /** в какую корзину попадает пункт при открытии экрана */
    private int sectionOf(int id) {
        if (isHidden(id)) {
            return hiddenSection();
        }
        if (mode == MODE_CHAT) {
            return PengramConfig.getChatItemPlacement(id) == PengramConfig.CHAT_PLACE_ISLAND ? 1 : 0;
        }
        return 0;
    }

    private void buildRows() {
        rows.clear();
        rows.add(new Row(VIEW_TYPE_INFO, 0, -1));
        final ArrayList<Integer> order = savedOrder();
        for (int section = 0; section < sectionCount(); ++section) {
            rows.add(new Row(VIEW_TYPE_HEADER, 0, section));
            for (int id : order) {
                if (sectionOf(id) == section) {
                    rows.add(new Row(VIEW_TYPE_ITEM, id, section));
                }
            }
            rows.add(new Row(VIEW_TYPE_PLACEHOLDER, 0, section));
        }
        rows.add(new Row(VIEW_TYPE_SHADOW, 0, -1));
    }

    /** пересчитать принадлежность строк к корзинам после перетаскивания */
    private void resolveSections() {
        int section = -1;
        for (Row row : rows) {
            if (row.type == VIEW_TYPE_HEADER) {
                section = row.section;
            } else if (row.type == VIEW_TYPE_ITEM || row.type == VIEW_TYPE_PLACEHOLDER) {
                row.section = section;
            }
        }
    }

    private void save() {
        final ArrayList<Integer> order = new ArrayList<>();
        for (Row row : rows) {
            if (row.type != VIEW_TYPE_ITEM) {
                continue;
            }
            order.add(row.id);
            final boolean hidden = row.section == hiddenSection();
            if (mode == MODE_SETTINGS) {
                PengramConfig.setSettingsItemHidden(row.id, hidden);
            } else if (mode == MODE_CHAT) {
                PengramConfig.setChatItemHidden(row.id, hidden);
                if (!hidden) {
                    PengramConfig.setChatItemPlacement(row.id, row.section == 1
                            ? PengramConfig.CHAT_PLACE_ISLAND : PengramConfig.CHAT_PLACE_MAIN);
                }
            } else {
                PengramConfig.setMenuItemHidden(row.id, hidden);
            }
        }
        if (mode == MODE_SETTINGS) {
            PengramConfig.setSettingsOrder(order);
        } else if (mode == MODE_CHAT) {
            PengramConfig.setChatItemsOrder(order);
        } else {
            PengramConfig.setMenuOrder(order);
        }
    }

    private CharSequence itemTitle(int id) {
        final int res;
        if (mode == MODE_SETTINGS) {
            res = PengramConfig.getSettingsItemTitle(id);
        } else if (mode == MODE_CHAT) {
            res = PengramConfig.getChatItemTitle(id);
        } else {
            res = PengramConfig.getMenuItemTitle(id);
        }
        return LocaleController.getString(res);
    }

    private int itemIcon(int id) {
        if (mode == MODE_SETTINGS) {
            return PengramConfig.getSettingsItemIcon(id);
        } else if (mode == MODE_CHAT) {
            return PengramConfig.getChatItemIcon(id);
        }
        return PengramConfig.getMenuItemIcon(id);
    }

    private int titleRes() {
        if (mode == MODE_SETTINGS) {
            return R.string.PengramSettingsItemsTitle;
        } else if (mode == MODE_CHAT) {
            return R.string.PengramChatItemsTitle;
        }
        return R.string.PengramMenuItemsTitle;
    }

    private int infoRes() {
        if (mode == MODE_SETTINGS) {
            return R.string.PengramSettingsItemsInfo;
        } else if (mode == MODE_CHAT) {
            return R.string.PengramChatItemsInfo;
        }
        return R.string.PengramMenuItemsInfo;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(titleRes()));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setTranslationInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setMoveDuration(320);
        itemAnimator.setRemoveDuration(260);
        itemAnimator.setAddDuration(260);
        listView.setItemAnimator(itemAnimator);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            final Row row = rows.get(position);
            if (row.type != VIEW_TYPE_ITEM) {
                return;
            }
            moveToNextSection(position);
            if (PengramConfig.isVibrationEnabled()) {
                try {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Throwable ignore) {
                }
            }
        });

        return fragmentView;
    }

    /** тап по пункту — перекинуть его в следующую корзину (по кругу) */
    private void moveToNextSection(int position) {
        final Row row = rows.get(position);
        final int next = (row.section + 1) % sectionCount();
        int target = -1;
        for (int a = 0; a < rows.size(); ++a) {
            final Row candidate = rows.get(a);
            if (candidate.type == VIEW_TYPE_PLACEHOLDER && candidate.section == next) {
                target = a;
                break;
            }
        }
        if (target < 0) {
            return;
        }
        if (target > position) {
            target--;
        }
        rows.remove(position);
        rows.add(target, row);
        resolveSections();
        adapter.notifyItemMoved(position, target);
        AndroidUtilities.runOnUIThread(() -> {
            if (adapter != null) {
                adapter.notifyItemRangeChanged(0, rows.size());
            }
        }, 340);
        save();
    }

    private class PlaceholderCell extends FrameLayout {

        final TextView textView;

        PlaceholderCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            textView.setGravity(Gravity.CENTER);
            textView.setText(LocaleController.getString(R.string.PengramDropHere));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(46), MeasureSpec.EXACTLY));
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_INFO: {
                    final TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context);
                    cell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_top, Theme.key_windowBackgroundGrayShadow));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    final HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_PLACEHOLDER: {
                    view = new PlaceholderCell(context);
                    break;
                }
                case VIEW_TYPE_SHADOW: {
                    view = new ShadowSectionCell(context);
                    break;
                }
                case VIEW_TYPE_ITEM:
                default: {
                    final TextCell cell = new TextCell(context, 23, false, false, null);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            final Row row = rows.get(position);
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_INFO: {
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(infoRes()));
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText(sectionTitle(row.section));
                    break;
                }
                case VIEW_TYPE_ITEM: {
                    final TextCell cell = (TextCell) holder.itemView;
                    final boolean hidden = row.section == hiddenSection();
                    final boolean last = position + 1 < rows.size() && rows.get(position + 1).type != VIEW_TYPE_ITEM;
                    cell.setTextAndIcon(itemTitle(row.id), itemIcon(row.id), !last);
                    final ImageView handle = cell.getValueImageView();
                    handle.setVisibility(View.VISIBLE);
                    handle.setImageResource(R.drawable.list_reorder);
                    handle.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
                    cell.setAlpha(hidden ? 0.5f : 1f);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= rows.size()) {
                return VIEW_TYPE_SHADOW;
            }
            return rows.get(position).type;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        boolean moveRow(int from, int to) {
            if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
                return false;
            }
            final Row row = rows.get(from);
            if (row.type != VIEW_TYPE_ITEM) {
                return false;
            }
            final Row target = rows.get(to);
            if (target.type != VIEW_TYPE_ITEM && target.type != VIEW_TYPE_PLACEHOLDER) {
                return false;
            }
            rows.remove(from);
            rows.add(to, row);
            resolveSections();
            notifyItemMoved(from, to);
            return true;
        }
    }

    private class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            if (viewHolder.getItemViewType() != VIEW_TYPE_ITEM) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
            final int type = target.getItemViewType();
            return type == VIEW_TYPE_ITEM || type == VIEW_TYPE_PLACEHOLDER;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
            return adapter.moveRow(source.getAdapterPosition(), target.getAdapterPosition());
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                listView.cancelClickRunnables(false);
                if (viewHolder != null) {
                    viewHolder.itemView.setPressed(true);
                    viewHolder.itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(160).start();
                }
            } else if (viewHolder != null) {
                viewHolder.itemView.setPressed(false);
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            save();
            AndroidUtilities.runOnUIThread(() -> {
                if (adapter != null) {
                    adapter.notifyItemRangeChanged(0, rows.size());
                }
            }, 60);
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        return list;
    }
}
