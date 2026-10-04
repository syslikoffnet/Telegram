package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

/**
 * Полноценный «чат» из сохранённых удалённых/изменённых сообщений:
 * обои, бабблы, поиск, прокрутка — выглядит как обычная переписка,
 * только тут живут удалёнки и история правок.
 */
public class PengramHistoryChatActivity extends BaseFragment {

    public static final int MODE_DELETED = 0;
    public static final int MODE_EDITED = 1;
    public static final int MODE_ALL = 2;

    private static final int MENU_CLEAR = 10;

    private final long dialogId;
    private final int mode;
    private final int singleMessageId;

    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private TextView emptyView;
    private ActionBarMenuItem searchItem;

    private String searchQuery = "";
    private final ArrayList<Item> items = new ArrayList<>();

    private static class Item {
        MessageObject message;
        CharSequence serviceText;   // «удалено 12 мая в 18:04» / «версия 2»
    }

    public PengramHistoryChatActivity(long dialogId, int mode) {
        this(dialogId, mode, 0);
    }

    /** @param singleMessageId != 0 — показываем историю правок одного сообщения */
    public PengramHistoryChatActivity(long dialogId, int mode, int singleMessageId) {
        super();
        this.dialogId = dialogId;
        this.mode = mode;
        this.singleMessageId = singleMessageId;
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        // Явный фон и занятая status-bar область: без этого на некоторых темах сверху оставалась чёрная полоса.
        actionBar.setOccupyStatusBar(true);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        actionBar.setTitle(getTitleText());
        actionBar.setSubtitle(getSubtitleText());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CLEAR) {
                    showClearAlert();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(0, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchCollapse() {
                        searchQuery = "";
                        reload();
                    }

                    @Override
                    public void onTextChanged(android.widget.EditText editText) {
                        searchQuery = editText.getText() == null ? "" : editText.getText().toString();
                        reload();
                    }
                });
        searchItem.setSearchFieldHint(getString(R.string.Search));
        if (singleMessageId == 0) {
            menu.addItem(MENU_CLEAR, R.drawable.msg_delete);
        }

        contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        fragmentView = contentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(true);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(4));
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
        emptyView.setBackground(Theme.createServiceDrawable(dp(12), emptyView, contentView));
        emptyView.setPadding(dp(12), dp(7), dp(12), dp(8));
        emptyView.setText(getString(R.string.PengramHistoryEmpty));
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));

        reload();
        return fragmentView;
    }

    private CharSequence getTitleText() {
        if (singleMessageId != 0) {
            return getString(R.string.PengramEditHistoryTitle);
        }
        switch (mode) {
            case MODE_DELETED: return getString(R.string.PengramDeletedTitle);
            case MODE_EDITED: return getString(R.string.PengramEditedTitle);
            default: return getString(R.string.PengramHistoryTitle);
        }
    }

    private CharSequence getSubtitleText() {
        if (dialogId == 0) {
            return LocaleController.formatPluralString("messages", items.size());
        }
        String name = null;
        if (dialogId > 0) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            name = user != null ? UserObject.getUserName(user) : null;
        } else {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            name = chat != null ? chat.title : null;
        }
        if (TextUtils.isEmpty(name)) {
            return LocaleController.formatPluralString("messages", items.size());
        }
        return name;
    }

    private void showClearAlert() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PengramHistoryClearTitle));
        builder.setMessage(getString(dialogId != 0 ? R.string.PengramHistoryClearChat : R.string.PengramHistoryClearAll));
        builder.setPositiveButton(getString(R.string.Delete), (d, w) -> {
            PengramHistory.clear(dialogId);
            reload();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, getString(R.string.PengramHistoryCleared)).show();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void reload() {
        final int filter;
        if (mode == MODE_DELETED) {
            filter = PengramHistory.FILTER_DELETED;
        } else if (mode == MODE_EDITED) {
            filter = PengramHistory.FILTER_EDITED;
        } else {
            filter = PengramHistory.FILTER_ALL;
        }
        final String query = searchQuery;
        // база может быть большой — читаем её не на главном потоке
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<PengramHistory.Entry> entries =
                    PengramHistory.getEntries(dialogId, filter, 1000, query, singleMessageId, true);
            AndroidUtilities.runOnUIThread(() -> applyEntries(entries, query));
        });
    }

    private void applyEntries(ArrayList<PengramHistory.Entry> entries, String query) {
        if (!TextUtils.equals(query, searchQuery)) {
            return; // пока читали, запрос успел измениться
        }
        items.clear();
        if (singleMessageId != 0) {
            // история правок: сначала самый первый известный текст, потом все версии
            for (int i = 0; i < entries.size(); ++i) {
                final PengramHistory.Entry entry = entries.get(i);
                if (i == 0 && !TextUtils.isEmpty(entry.prevText)) {
                    addItem(entry, entry.prevText, LocaleController.getString(R.string.PengramVersionOriginal));
                }
                addItem(entry, entry.text, LocaleController.formatString(R.string.PengramVersionNumber, i + 2));
            }
        } else {
            // как в обычном чате: разделитель с датой, а статус удалено/изменено — значком у времени
            int lastDay = Integer.MIN_VALUE;
            for (int i = 0; i < entries.size(); ++i) {
                final PengramHistory.Entry entry = entries.get(i);
                final int date = entry.date != 0 ? entry.date : entry.savedAt;
                final int day = dayOf(date);
                CharSequence service = null;
                if (day != lastDay) {
                    lastDay = day;
                    service = LocaleController.formatDateChat(date);
                }
                addItem(entry, entry.text, service);
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (actionBar != null) {
            actionBar.setSubtitle(getSubtitleText());
        }
        if (layoutManager != null && !items.isEmpty()) {
            layoutManager.scrollToPosition(items.size() - 1);
        }
    }

    private String formatDate(int unixtime) {
        if (unixtime <= 0) {
            return "";
        }
        return LocaleController.formatDateTime(unixtime, true);
    }

    /** номер дня (для разделителей дат) */
    private static int dayOf(int unixtime) {
        return unixtime / 86400;
    }

    private void addItem(PengramHistory.Entry entry, String text, CharSequence service) {
        final MessageObject messageObject = buildMessage(entry, text);
        if (messageObject == null) {
            return;
        }
        if (entry.action == PengramHistory.ACTION_DELETED) {
            messageObject.pengramDeleted = true;
        }
        Item item = new Item();
        item.message = messageObject;
        item.serviceText = service;
        items.add(item);
    }

    private MessageObject buildMessage(PengramHistory.Entry entry, String text) {
        try {
            TLRPC.Message message = null;
            if (entry.data != null && TextUtils.equals(text, entry.text)) {
                message = PengramHistory.deserialize(entry.data);
            }
            if (message == null) {
                if (TextUtils.isEmpty(text)) {
                    return null;
                }
                TLRPC.TL_message msg = new TLRPC.TL_message();
                msg.id = entry.messageId;
                msg.message = text;
                msg.date = entry.date != 0 ? entry.date : entry.savedAt;
                msg.dialog_id = entry.dialogId;
                msg.peer_id = getMessagesController().getPeer(entry.dialogId);
                msg.from_id = getMessagesController().getPeer(entry.fromId != 0 ? entry.fromId : entry.dialogId);
                msg.out = entry.out;
                msg.unread = false;
                msg.media = new TLRPC.TL_messageMediaEmpty();
                msg.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
                if (entry.action == PengramHistory.ACTION_EDITED) {
                    msg.flags |= TLRPC.MESSAGE_FLAG_EDITED;
                    msg.edit_date = entry.savedAt;
                }
                message = msg;
            } else {
                if (!TextUtils.isEmpty(text) && !TextUtils.equals(message.message, text)) {
                    message.message = text;
                }
                message.dialog_id = entry.dialogId;
                if (entry.action == PengramHistory.ACTION_EDITED) {
                    message.flags |= TLRPC.MESSAGE_FLAG_EDITED;
                    if (message.edit_date == 0) {
                        message.edit_date = entry.savedAt;
                    }
                }
            }
            MessageObject messageObject = new MessageObject(currentAccount, message, true, true);
            messageObject.resetLayout();
            return messageObject;
        } catch (Throwable e) {
            return null;
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);

            final TextView service = new TextView(context);
            service.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            service.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
            service.setGravity(Gravity.CENTER);
            service.setPadding(dp(10), dp(4), dp(10), dp(5));
            service.setBackground(Theme.createServiceDrawable(dp(10), service, contentView));

            final FrameLayout serviceWrap = new FrameLayout(context);
            serviceWrap.addView(service, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 8, 6, 8, 2));
            layout.addView(serviceWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
            cell.isChat = dialogId < 0;
            cell.setFullyDraw(true);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return false;
                }
            });
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            layout.setTag(cell);
            layout.setTag(R.id.object_tag, service);
            return new RecyclerListView.Holder(layout);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            final Item item = items.get(position);
            final View view = holder.itemView;
            final ChatMessageCell cell = (ChatMessageCell) view.getTag();
            final TextView service = (TextView) view.getTag(R.id.object_tag);
            if (service != null) {
                if (TextUtils.isEmpty(item.serviceText)) {
                    ((View) service.getParent()).setVisibility(View.GONE);
                } else {
                    ((View) service.getParent()).setVisibility(View.VISIBLE);
                    service.setText(item.serviceText);
                }
            }
            if (cell != null) {
                cell.setMessageObject(item.message, null, false, false, false);
                cell.setAlpha(item.message != null && item.message.pengramDeleted
                        && org.telegram.messenger.PengramConfig.isFadingDeleted() ? 0.55f : 1f);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return super.isLightStatusBar();
    }
}
