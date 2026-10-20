package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
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
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.PengramCopySender;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
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
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.io.File;
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
    private final long focusedRowId;

    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private TextView emptyView;
    private ActionBarMenuItem searchItem;

    private String searchQuery = "";
    private int pageLimit = 500;
    private int loadGeneration;
    private boolean loadingEntries;
    private boolean hasOlderEntries;
    private final ArrayList<Item> items = new ArrayList<>();
    private final Runnable historyChanged = () -> {
        if (fragmentView != null) reload(true);
    };

    private static class Item {
        MessageObject message;
        PengramHistory.Entry entry;
        CharSequence serviceText;   // «удалено 12 мая в 18:04» / «версия 2»
    }

    public PengramHistoryChatActivity(long dialogId, int mode) {
        this(dialogId, mode, 0);
    }

    /** @param singleMessageId != 0 — показываем версии одного сообщения */
    public PengramHistoryChatActivity(long dialogId, int mode, int singleMessageId) {
        this(dialogId, mode, singleMessageId, 0, "");
    }

    /** Open the exact matching archived row from in-chat search. */
    public PengramHistoryChatActivity(long dialogId, int mode, int singleMessageId,
                                      long focusedRowId, String query) {
        super();
        this.dialogId = dialogId;
        this.mode = mode;
        this.singleMessageId = singleMessageId;
        this.focusedRowId = focusedRowId;
        this.searchQuery = query == null ? "" : query.trim();
    }

    @Override
    public boolean onFragmentCreate() {
        boolean created = super.onFragmentCreate();
        if (created) PengramHistory.addChangeListener(historyChanged);
        return created;
    }

    @Override
    public void onFragmentDestroy() {
        PengramHistory.removeChangeListener(historyChanged);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false);
        hasOwnBackground = true;
        // Chat-like screens own their ActionBar. Letting BaseFragment reserve another bar created
        // the full 56dp black strip reported directly below the header.
        actionBar.setAddToContainer(false);
        actionBar.setCastShadows(false);

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
                        pageLimit = 500;
                        reload();
                    }

                    @Override
                    public void onTextChanged(android.widget.EditText editText) {
                        searchQuery = editText.getText() == null ? "" : editText.getText().toString();
                        pageLimit = 500;
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
        listView.setPadding(0, ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight + dp(4), 0, dp(4));
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy < 0 && !loadingEntries && hasOlderEntries && layoutManager != null
                        && layoutManager.findFirstVisibleItemPosition() <= 3) {
                    pageLimit += 500;
                    reload(true);
                }
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < items.size() && singleMessageId == 0
                    && items.get(position).entry.action == PengramHistory.ACTION_EDITED) {
                PengramHistory.Entry entry = items.get(position).entry;
                PengramHistoryChatActivity detail = new PengramHistoryChatActivity(entry.dialogId, MODE_EDITED, entry.messageId);
                detail.setCurrentAccount(currentAccount);
                presentFragment(detail);
            } else {
                showActions(position);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            showActions(position);
            return true;
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
        emptyView.setBackground(Theme.createServiceDrawable(dp(12), emptyView, contentView));
        emptyView.setPadding(dp(12), dp(7), dp(12), dp(8));
        emptyView.setText(getString(R.string.PengramHistoryEmpty));
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));

        if (!TextUtils.isEmpty(searchQuery)) {
            searchItem.openSearch(false);
            searchItem.setSearchFieldText(searchQuery, false);
        }
        reload();
        return fragmentView;
    }

    private CharSequence getTitleText() {
        if (singleMessageId != 0) {
            return getString(mode == MODE_DELETED ? R.string.PengramDeletedTitle : R.string.PengramEditHistoryTitle);
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
            PengramHistory.clear(currentAccount, dialogId);
            // historyChanged reloads after the queued deletion completes.
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
        reload(false);
    }

    private void reload(boolean keepScroll) {
        final int generation = ++loadGeneration;
        loadingEntries = true;
        // A single message normally has only a handful of revisions; never show a
        // partial chain or label the first row of a page as the original text.
        final int limit = singleMessageId != 0 ? Integer.MAX_VALUE : pageLimit;
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
                    PengramHistory.getEntries(dialogId, filter, limit, query, singleMessageId, true, currentAccount);
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != loadGeneration || fragmentView == null) return;
                loadingEntries = false;
                hasOlderEntries = entries.size() == limit;
                applyEntries(entries, query, keepScroll);
            });
        });
    }

    private void applyEntries(ArrayList<PengramHistory.Entry> entries, String query, boolean keepScroll) {
        if (!TextUtils.equals(query, searchQuery)) {
            return; // пока читали, запрос успел измениться
        }
        final int oldSize = items.size();
        final int firstVisible = layoutManager == null ? 0 : layoutManager.findFirstVisibleItemPosition();
        final View firstView = layoutManager == null ? null : layoutManager.findViewByPosition(firstVisible);
        final int firstOffset = firstView == null ? 0 : firstView.getTop();
        items.clear();
        if (singleMessageId != 0 && mode == MODE_EDITED) {
            // A media message may have an empty caption but a complete old snapshot.
            // Conversely, if the old row was absent, start at the first known
            // version instead of pretending that an unknown original was saved.
            int version = 1;
            for (int i = 0; i < entries.size(); ++i) {
                final PengramHistory.Entry entry = entries.get(i);
                final boolean firstPrevious = i == 0 && (entry.prevData != null || entry.prevText != null);
                final boolean missingIntermediate = i > 0 && entry.prevText != null
                        && !TextUtils.equals(entries.get(i - 1).text, entry.prevText);
                if (firstPrevious || missingIntermediate) {
                    // A skipped update must not silently remove the prior version
                    // that was captured by the next update.
                    if (addItem(entry, entry.prevText, LocaleController.getString(R.string.PengramVersionBeforeEdit), true)) {
                        version++;
                    }
                }
                if (addItem(entry, entry.text, LocaleController.formatString(R.string.PengramVersionNumber, version))) {
                    version++;
                }
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
                String text = TextUtils.isEmpty(entry.text) ? entry.prevText : entry.text;
                if (!TextUtils.isEmpty(query)
                        && !TextUtils.isEmpty(entry.prevText)
                        && entry.prevText.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))
                        && (TextUtils.isEmpty(text) || !text.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)))) {
                    text = entry.prevText;
                    service = service == null ? getString(R.string.PengramVersionBeforeEdit)
                            : service + " · " + getString(R.string.PengramVersionBeforeEdit);
                }
                addItem(entry, text, service, !TextUtils.equals(text, entry.text));
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setText(getString(!TextUtils.isEmpty(query) ? R.string.PengramHistoryNoMatches
                    : singleMessageId != 0 ? R.string.PengramHistoryNoVersions : R.string.PengramHistoryEmpty));
            emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (actionBar != null) {
            actionBar.setSubtitle(getSubtitleText());
        }
        if (layoutManager != null && !items.isEmpty()) {
            if (keepScroll && oldSize != 0 && firstVisible >= 0) {
                layoutManager.scrollToPositionWithOffset(firstVisible + items.size() - oldSize, firstOffset);
            } else {
                int target = items.size() - 1;
                if (focusedRowId != 0) {
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).entry.rowId == focusedRowId) {
                            target = i;
                            break;
                        }
                    }
                }
                layoutManager.scrollToPosition(target);
            }
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

    private boolean addItem(PengramHistory.Entry entry, String text, CharSequence service) {
        return addItem(entry, text, service, false);
    }

    private boolean addItem(PengramHistory.Entry entry, String text, CharSequence service, boolean previous) {
        final MessageObject messageObject = buildMessage(entry, text, previous);
        if (messageObject == null) {
            return false;
        }
        if (entry.action == PengramHistory.ACTION_DELETED) {
            messageObject.pengramDeleted = true;
            messageObject.pengramArchived = true;
        }
        Item item = new Item();
        item.entry = entry;
        item.message = messageObject;
        item.serviceText = service;
        items.add(item);
        return true;
    }

    private MessageObject buildMessage(PengramHistory.Entry entry, String text, boolean previous) {
        try {
            TLRPC.Message message = null;
            if (previous && entry.prevData != null) {
                message = PengramHistory.deserialize(entry.prevData, currentAccount);
                if (message != null && !TextUtils.isEmpty(entry.prevMediaPath)) {
                    message.attachPath = entry.prevMediaPath;
                }
            } else if (!previous && entry.data != null && TextUtils.equals(text, entry.text)) {
                message = PengramHistory.deserialize(entry.data, currentAccount);
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
                if (entry.action == PengramHistory.ACTION_EDITED && !previous) {
                    msg.flags |= TLRPC.MESSAGE_FLAG_EDITED;
                    msg.edit_date = entry.savedAt;
                }
                message = msg;
            } else {
                // The archive's searchable "[photo]" / "[video]" summary is not a
                // real caption. Keep the deserialized media and its actual caption.
                // The archived "[file]" summary is a search label, not message
                // content. A full snapshot keeps its real caption, entities and media.
                message.dialog_id = entry.dialogId;
                if (entry.action == PengramHistory.ACTION_EDITED && !previous) {
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

    /** Archived messages are local snapshots; never ask the server to forward a deleted original. */
    private void showActions(int position) {
        if (position < 0 || position >= items.size() || getParentActivity() == null) return;
        MessageObject message = items.get(position).message;
        if (message == null) return;
        final boolean restricted = message.isSecretMedia() || DialogObject.isEncryptedDialog(message.getDialogId())
                || message.messageOwner.noforwards
                || getMessagesController().isPeerNoForwards(message.getDialogId());
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (items.get(position).entry.action == PengramHistory.ACTION_DELETED) {
            labels.add(getString(R.string.ViewInChat));
            final PengramHistory.Entry entry = items.get(position).entry;
            actions.add(() -> viewInSourceChat(entry));
        }
        if (!restricted && !TextUtils.isEmpty(message.messageText)) {
            labels.add(getString(R.string.Copy));
            actions.add(() -> AndroidUtilities.addToClipboard(message.messageText));
        }
        if (message.isPhoto() && !org.telegram.ui.Components.PengramPhotoClipboard.isSensitive(message)) {
            labels.add(getString(R.string.PengramPhotoCopy));
            actions.add(() -> org.telegram.ui.Components.PengramPhotoClipboard.copy(this, message));
        }
        if (!restricted) {
            labels.add(getString(R.string.PengramHistoryResendCopy));
            actions.add(() -> forwardCopy(message));
        }
        if (labels.isEmpty()) return;
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
                .create());
    }

    /** Only jump to the live chat if Telegram still has the marked copy locally.
     * Otherwise remain on this read-only archived snapshot; never send it to MTProto. */
    private void viewInSourceChat(PengramHistory.Entry entry) {
        if (entry.account != currentAccount || getParentActivity() == null) return;
        final long owner = getUserConfig().getClientUserId();
        if (owner == 0) return;
        MessagesStorage.getInstance(currentAccount).hasLocalMessage(entry.dialogId, entry.messageId, exists -> {
            if (getParentActivity() == null || getUserConfig().getClientUserId() != owner) return;
            if (!exists) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
                        getString(R.string.PengramHistoryOnlyLocal)).show();
                return;
            }
            ChatActivity chat = ChatActivity.of(entry.dialogId, entry.messageId);
            chat.setCurrentAccount(currentAccount);
            presentFragment(chat);
        });
    }

    private void forwardCopy(MessageObject message) {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("canSelectTopics", true);
        args.putBoolean("checkCanWrite", true);
        args.putBoolean("allowGlobalSearch", true);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setCurrentAccount(currentAccount);
        picker.setDelegate((fragment, dids, text, param, notify, scheduleDate, repeat, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) return false;
            // A copy cannot yet carry a schedule, paid-message approval, topic
            // or picker comment. Never silently send it now or to the main chat.
            if (!TextUtils.isEmpty(text)) {
                AlertsCreator.showSendMediaAlert(26, this, getResourceProvider());
                return false;
            }
            for (MessagesStorage.TopicKey destination : dids) {
                if (destination == null || destination.dialogId == 0) return false;
                int error = destination.topicId != 0 ? 25 :
                        PengramCopySender.copyTargetError(currentAccount, destination.dialogId, scheduleDate, repeat);
                if (error != 0) {
                    AlertsCreator.showSendMediaAlert(error, this, getResourceProvider());
                    return false;
                }
            }
            for (MessagesStorage.TopicKey destination : dids) {
                long target = destination.dialogId;
                ArrayList<MessageObject> copies = new ArrayList<>();
                copies.add(message);
                PengramCopySender.sendCopies(currentAccount, copies, target, notify, (sent, failed) -> {
                    if (getParentActivity() != null && failed > 0) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
                                getString(R.string.PengramHistoryCopyUnavailable)).show();
                    }
                });
            }
            fragment.finishFragment();
            return true;
        });
        presentFragment(picker);
    }

    private void openArchivedMedia(MessageObject message) {
        if (message == null || message.messageOwner == null || getParentActivity() == null) return;
        File local = FileLoader.getInstance(currentAccount).getPathToMessage(message.messageOwner);
        if ((local == null || !local.isFile()) && !TextUtils.isEmpty(message.messageOwner.attachPath)) {
            local = new File(message.messageOwner.attachPath);
        }
        if (local == null || !local.isFile() || local.length() == 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
                    getString(R.string.PengramHistoryMediaUnavailable)).show();
            return;
        }
        try {
            if (message.isVoice() || message.isMusic()) {
                MediaController.getInstance().playMessage(message);
            } else if (message.isPhoto() || message.isVideo() || message.isRoundVideo()) {
                PhotoViewer.getInstance().setParentActivity(this);
                PhotoViewer.getInstance().openPhoto(message, null, 0, 0, 0,
                        new PhotoViewer.EmptyPhotoViewerProvider());
            }
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
                    getString(R.string.PengramHistoryMediaUnavailable)).show();
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
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
            cell.isChat = dialogId <= 0;
            cell.setFullyDraw(true);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return true;
                }

                @Override
                public boolean needPlayMessage(ChatMessageCell cell, MessageObject message, boolean muted) {
                    openArchivedMedia(message);
                    return MediaController.getInstance().isPlayingMessage(message);
                }

                @Override
                public void didPressImage(ChatMessageCell cell, float x, float y, boolean fullPreview) {
                    openArchivedMedia(cell.getMessageObject());
                }

                @Override
                public void didLongPress(ChatMessageCell cell, float x, float y) {
                    int index = listView.getChildAdapterPosition((View) cell.getParent());
                    showActions(index);
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

    private void refreshTheme() {
        if (contentView == null) return;
        Theme.createChatResources(contentView.getContext(), false);
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        if (actionBar != null) {
            actionBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        }
        if (emptyView != null) {
            emptyView.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
            emptyView.setBackground(Theme.createServiceDrawable(dp(12), emptyView, contentView));
        }
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View row = listView.getChildAt(i);
                TextView service = (TextView) row.getTag(R.id.object_tag);
                if (service != null) {
                    service.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
                    service.setBackground(Theme.createServiceDrawable(dp(10), service, contentView));
                }
                ChatMessageCell cell = (ChatMessageCell) row.getTag();
                if (cell != null) cell.invalidate();
                row.invalidate();
            }
            listView.invalidate();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshTheme();
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultSelector));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR,
                null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBTITLECOLOR,
                null, null, null, null, Theme.key_actionBarDefaultSubtitle));
        descriptions.add(new ThemeDescription(contentView, 0, null, null, null,
                this::refreshTheme, Theme.key_chat_wallpaper));
        return descriptions;
    }

    @Override
    public boolean isLightStatusBar() {
        return super.isLightStatusBar();
    }
}
