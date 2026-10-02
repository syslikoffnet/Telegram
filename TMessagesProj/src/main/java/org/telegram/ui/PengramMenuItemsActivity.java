package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengram: управление пунктами верхнего меню списка чатов.
 * Нажатие — скрыть/показать, удержание — перетащить и поменять порядок.
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
    private static final int VIEW_TYPE_SHADOW = 3;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private final ArrayList<Integer> order = new ArrayList<>();
    private final int mode;

    public PengramMenuItemsActivity() {
        this(MODE_MENU);
    }

    public PengramMenuItemsActivity(int mode) {
        super();
        this.mode = mode;
    }

    @Override
    public boolean onFragmentCreate() {
        order.clear();
        if (mode == MODE_SETTINGS) {
            order.addAll(PengramConfig.getSettingsOrder());
        } else if (mode == MODE_CHAT) {
            order.addAll(PengramConfig.getChatItemsOrder());
        } else {
            for (int id : PengramConfig.getMenuOrder()) {
                order.add(id);
            }
        }
        return super.onFragmentCreate();
    }

    private boolean isHidden(int id) {
        if (mode == MODE_SETTINGS) {
            return PengramConfig.isSettingsItemHidden(id);
        } else if (mode == MODE_CHAT) {
            return PengramConfig.isChatItemHidden(id);
        }
        return PengramConfig.isMenuItemHidden(id);
    }

    private void setHidden(int id, boolean hidden) {
        if (mode == MODE_SETTINGS) {
            PengramConfig.setSettingsItemHidden(id, hidden);
        } else if (mode == MODE_CHAT) {
            PengramConfig.setChatItemHidden(id, hidden);
        } else {
            PengramConfig.setMenuItemHidden(id, hidden);
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
        listView.setItemAnimator(itemAnimator);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);

        listView.setOnItemClickListener((view, position) -> {
            final int index = positionToIndex(position);
            if (index < 0 || index >= order.size()) {
                return;
            }
            final int id = order.get(index);
            final boolean nowHidden = isHidden(id);
            setHidden(id, !nowHidden);
            if (view instanceof TextCell) {
                ((TextCell) view).setChecked(nowHidden);
                view.setAlpha(nowHidden ? 1f : 0.5f);
            } else {
                adapter.notifyItemChanged(position);
            }
            if (PengramConfig.isVibrationEnabled()) {
                try {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Throwable ignore) {
                }
            }
        });

        return fragmentView;
    }

    /** позиция в списке → индекс в order (учитываем «шапку») */
    private int positionToIndex(int position) {
        return position - 2;
    }

    private void saveOrder() {
        if (mode == MODE_SETTINGS) {
            PengramConfig.setSettingsOrder(order);
        } else if (mode == MODE_CHAT) {
            PengramConfig.setChatItemsOrder(order);
        } else {
            PengramConfig.setMenuOrder(order);
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
                case VIEW_TYPE_SHADOW: {
                    view = new ShadowSectionCell(context);
                    break;
                }
                case VIEW_TYPE_ITEM:
                default: {
                    final TextCell cell = new TextCell(context, 23, false, true, null);
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
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_INFO: {
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(infoRes()));
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.PengramMenuItemsHeader));
                    break;
                }
                case VIEW_TYPE_ITEM: {
                    final int index = positionToIndex(position);
                    if (index < 0 || index >= order.size()) {
                        return;
                    }
                    final int id = order.get(index);
                    final TextCell cell = (TextCell) holder.itemView;
                    final boolean hidden = isHidden(id);
                    cell.setTextAndCheckAndIcon(
                            itemTitle(id),
                            !hidden,
                            itemIcon(id),
                            index != order.size() - 1
                    );
                    cell.setAlpha(hidden ? 0.5f : 1f);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_INFO;
            }
            if (position == 1) {
                return VIEW_TYPE_HEADER;
            }
            if (position == order.size() + 2) {
                return VIEW_TYPE_SHADOW;
            }
            return VIEW_TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return order.size() + 3;
        }

        void swapElements(int fromPosition, int toPosition) {
            final int from = positionToIndex(fromPosition);
            final int to = positionToIndex(toPosition);
            if (from < 0 || to < 0 || from >= order.size() || to >= order.size()) {
                return;
            }
            final int id = order.remove(from);
            order.add(to, id);
            notifyItemMoved(fromPosition, toPosition);
            saveOrder();
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
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
            if (source.getItemViewType() != target.getItemViewType()) {
                return false;
            }
            adapter.swapElements(source.getAdapterPosition(), target.getAdapterPosition());
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                listView.cancelClickRunnables(false);
                if (viewHolder != null) {
                    viewHolder.itemView.setPressed(true);
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
