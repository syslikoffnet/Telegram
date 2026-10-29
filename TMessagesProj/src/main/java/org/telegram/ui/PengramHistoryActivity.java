package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramHistory;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Date;

/**
 * Просмотр сохранённых удалённых и отредактированных сообщений.
 * dialogId == 0 — по всем чатам, иначе только по конкретному чату.
 */
public class PengramHistoryActivity extends UniversalFragment {

    private static final int MENU_CLEAR = 1;

    private final long dialogId;
    private int filter = PengramHistory.FILTER_ALL;
    private ArrayList<PengramHistory.Entry> entries = new ArrayList<>();
    private final Runnable historyChanged = this::reload;
    private static final int PAGE_SIZE = 100;
    private int generation, totalEntries;
    private boolean loading, morePages, failed, destroyed;

    public PengramHistoryActivity() {
        this(0);
    }

    public PengramHistoryActivity(long dialogId) {
        super();
        this.dialogId = dialogId;
    }

    @Override
    public boolean onFragmentCreate() {
        boolean created = super.onFragmentCreate();
        if (created) PengramHistory.addChangeListener(historyChanged);
        return created;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        generation++;
        PengramHistory.removeChangeListener(historyChanged);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_CLEAR, R.drawable.msg_delete);
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
        listView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(androidx.recyclerview.widget.RecyclerView recycler, int dx, int dy) {
                if (dy > 0 && !recycler.canScrollVertically(1)) loadNextPage();
            }
        });
        reload();
        org.telegram.messenger.PengramMD3.watch(view, () -> {
            if (listView != null && listView.adapter != null) listView.adapter.update(false);
        });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
    }


    private void showClearAlert() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PengramHistoryClearTitle));
        builder.setMessage(getString(dialogId != 0 ? R.string.PengramHistoryClearChat : R.string.PengramHistoryClearAll));
        builder.setPositiveButton(getString(R.string.Delete), (d, w) -> {
            PengramHistory.clear(currentAccount, dialogId);
            // The change listener reloads after the queued SQLite deletion.
            // Успех показывает только обновлённый список после commit, не оптимистичный bulletin.
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
        generation++;
        entries.clear();
        totalEntries = 0;
        morePages = true;
        loading = false;
        failed = false;
        loadNextPage();
    }

    private void loadNextPage() {
        if (destroyed || loading || !morePages) return;
        loading = true;
        final int request = generation;
        final long beforeId = entries.isEmpty() ? Long.MAX_VALUE : entries.get(entries.size() - 1).rowId;
        PengramHistory.loadArchivePage(currentAccount, dialogId, filter, beforeId, PAGE_SIZE, (page, total, error) -> {
            if (destroyed || request != generation) return;
            loading = false;
            failed = error;
            if (!error) {
                entries.addAll(page);
                totalEntries = total;
                morePages = page.size() == PAGE_SIZE;
            }
            if (listView != null && listView.adapter != null) listView.adapter.update(true);
        });
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PengramHistoryTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.PengramHistoryFilter)));
        final UItem all = UItem.asRadio(10, getString(R.string.PengramHistoryFilterAll)).setChecked(filter == PengramHistory.FILTER_ALL);
        all.hideDivider = org.telegram.messenger.PengramMD3.hideDividers();
        items.add(all);
        final UItem deleted = UItem.asRadio(11, getString(R.string.PengramHistoryFilterDeleted)).setChecked(filter == PengramHistory.FILTER_DELETED);
        deleted.hideDivider = org.telegram.messenger.PengramMD3.hideDividers();
        items.add(deleted);
        final UItem edited = UItem.asRadio(12, getString(R.string.PengramHistoryFilterEdited)).setChecked(filter == PengramHistory.FILTER_EDITED);
        edited.hideDivider = org.telegram.messenger.PengramMD3.hideDividers();
        items.add(edited);
        final UItem saved = UItem.asRadio(13, getString(R.string.PengramHistoryFilterMessages)).setChecked(filter == PengramHistory.FILTER_SAVED);
        saved.hideDivider = org.telegram.messenger.PengramMD3.hideDividers();
        items.add(saved);
        items.add(UItem.asShadow(null));

        if (failed) {
            items.add(UItem.asButton(20, R.drawable.msg_retry, getString(R.string.PengramHistoryReadFailed)));
        }
        if (entries.isEmpty()) {
            items.add(UItem.asShadow(getString(loading ? R.string.Loading : R.string.PengramHistoryEmpty)));
            return;
        }

        items.add(UItem.asHeader(LocaleController.formatPluralString("messages", totalEntries)));
        for (int i = 0; i < entries.size(); ++i) {
            final PengramHistory.Entry entry = entries.get(i);
            EntryView view = new EntryView(getContext());
            view.set(entry);
            items.add(UItem.asCustom(1000 + i, view));
        }
        if (morePages && !failed) items.add(UItem.asButton(20, R.drawable.msg_download, getString(R.string.PengramHistoryLoadMore)));
        items.add(UItem.asShadow(getString(R.string.PengramHistoryInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 20) {
            loadNextPage();
        } else if (item.id == 10) {
            filter = PengramHistory.FILTER_ALL;
            reload();
        } else if (item.id == 11) {
            filter = PengramHistory.FILTER_DELETED;
            reload();
        } else if (item.id == 12) {
            filter = PengramHistory.FILTER_EDITED;
            reload();
        } else if (item.id == 13) {
            filter = PengramHistory.FILTER_SAVED;
            reload();
        } else if (item.id >= 1000) {
            final int index = item.id - 1000;
            if (index >= 0 && index < entries.size()) {
                final PengramHistory.Entry entry = entries.get(index);
                PengramHistoryChatActivity detail = entry.action == PengramHistory.ACTION_EDITED
                        ? new PengramHistoryChatActivity(entry.dialogId, PengramHistoryChatActivity.MODE_EDITED, entry.messageId)
                        : entry.action == PengramHistory.ACTION_SAVED
                        ? new PengramHistoryChatActivity(entry.dialogId, PengramHistoryChatActivity.MODE_SAVED)
                        : new PengramHistoryChatActivity(entry.dialogId, PengramHistoryChatActivity.MODE_DELETED);
                detail.setCurrentAccount(currentAccount);
                presentFragment(detail);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= 1000) {
            final int index = item.id - 1000;
            if (index >= 0 && index < entries.size()) {
                final PengramHistory.Entry entry = entries.get(index);
                ItemOptions.makeOptions(this, view)
                        .add(R.drawable.msg_copy, getString(R.string.Copy), () -> {
                            AndroidUtilities.addToClipboard(entry.text);
                            BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
                        })
                        .add(R.drawable.msg_delete, getString(R.string.Delete), true, () -> {
                            PengramHistory.deleteEntry(currentAccount, entry.rowId);
                            // Reload on the persisted change, not before the executor runs.
                        })
                        .show();
                return true;
            }
        }
        return false;
    }

    private class EntryView extends LinearLayout {

        private final TextView headerText;
        private final TextView bodyText;
        private final TextView prevText;

        public EntryView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            if (org.telegram.messenger.PengramMD3.settingsScreen()) {
                final int inset = org.telegram.messenger.PengramMD3.rowInset();
                setBackground(new android.graphics.drawable.InsetDrawable(Theme.createRoundRectDrawable(dp(12),
                        org.telegram.messenger.PengramMD3.surface(getResourceProvider(), .05f)), inset, dp(2), inset, dp(2)));
            } else setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
            setPadding(dp(21), dp(10), dp(21), dp(10));

            headerText = new TextView(context);
            headerText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            headerText.setTypeface(AndroidUtilities.bold());
            addView(headerText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            prevText = new TextView(context);
            prevText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            prevText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
            addView(prevText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START, 0, 3, 0, 0));

            bodyText = new TextView(context);
            bodyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            bodyText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            addView(bodyText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START, 0, 3, 0, 0));
        }

        public void set(PengramHistory.Entry entry) {
            final boolean deleted = entry.action == PengramHistory.ACTION_DELETED;
            final boolean saved = entry.action == PengramHistory.ACTION_SAVED;
            headerText.setTextColor(Theme.getColor(deleted ? Theme.key_text_RedBold : Theme.key_windowBackgroundWhiteBlueText, getResourceProvider()));

            String who = peerName(entry.fromId != 0 ? entry.fromId : entry.dialogId);
            String when = LocaleController.getInstance().getFormatterStats().format(new Date(entry.savedAt * 1000L));
            final String mark = deleted ? getString(R.string.PengramHistoryDeletedMark)
                    : saved ? getString(R.string.PengramHistorySavedMark)
                    : getString(R.string.PengramHistoryEditedMark);
            headerText.setText(mark + " · " + who + " · " + when);

            if (!deleted && entry.prevText != null) {
                prevText.setVisibility(VISIBLE);
                prevText.setText(entry.prevText);
                prevText.setPaintFlags(prevText.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                prevText.setVisibility(GONE);
            }
            bodyText.setText(entry.text);
        }

        private String peerName(long peerId) {
            try {
                if (peerId > 0) {
                    TLRPC.User user = getMessagesController().getUser(peerId);
                    if (user != null) {
                        return org.telegram.messenger.UserObject.getUserName(user);
                    }
                    org.telegram.messenger.PengramSenderHydrator.ensureLoaded(currentAccount, 0, peerId, id -> {
                        if (adapter != null) adapter.notifyDataSetChanged();
                    });
                } else if (peerId < 0) {
                    TLRPC.Chat chat = getMessagesController().getChat(-peerId);
                    if (chat != null) {
                        return chat.title;
                    }
                    org.telegram.messenger.PengramSenderHydrator.ensureLoaded(currentAccount, peerId, peerId, id -> {
                        if (adapter != null) adapter.notifyDataSetChanged();
                    });
                }
            } catch (Throwable ignore) {}
            return String.valueOf(peerId);
        }
    }
}
