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

    public PengramHistoryActivity() {
        this(0);
    }

    public PengramHistoryActivity(long dialogId) {
        super();
        this.dialogId = dialogId;
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
        reload();
        return view;
    }

    private void showClearAlert() {
        if (getParentActivity() == null) return;
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
        entries = PengramHistory.getEntries(dialogId, filter, 500);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PengramHistoryTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.PengramHistoryFilter)));
        items.add(UItem.asRadio(10, getString(R.string.PengramHistoryFilterAll)).setChecked(filter == PengramHistory.FILTER_ALL));
        items.add(UItem.asRadio(11, getString(R.string.PengramHistoryFilterDeleted)).setChecked(filter == PengramHistory.FILTER_DELETED));
        items.add(UItem.asRadio(12, getString(R.string.PengramHistoryFilterEdited)).setChecked(filter == PengramHistory.FILTER_EDITED));
        items.add(UItem.asShadow(null));

        if (entries.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.PengramHistoryEmpty)));
            return;
        }

        items.add(UItem.asHeader(LocaleController.formatPluralString("messages", entries.size())));
        for (int i = 0; i < entries.size(); ++i) {
            final PengramHistory.Entry entry = entries.get(i);
            EntryView view = new EntryView(getContext());
            view.set(entry);
            items.add(UItem.asCustom(1000 + i, view));
        }
        items.add(UItem.asShadow(getString(R.string.PengramHistoryInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 10) {
            filter = PengramHistory.FILTER_ALL;
            reload();
        } else if (item.id == 11) {
            filter = PengramHistory.FILTER_DELETED;
            reload();
        } else if (item.id == 12) {
            filter = PengramHistory.FILTER_EDITED;
            reload();
        } else if (item.id >= 1000) {
            final int index = item.id - 1000;
            if (index >= 0 && index < entries.size()) {
                final PengramHistory.Entry entry = entries.get(index);
                if (entry.action == PengramHistory.ACTION_EDITED) {
                    presentFragment(new PengramHistoryChatActivity(entry.dialogId, PengramHistoryChatActivity.MODE_EDITED, entry.messageId));
                } else {
                    presentFragment(new PengramHistoryChatActivity(entry.dialogId, PengramHistoryChatActivity.MODE_DELETED));
                }
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
                            PengramHistory.deleteEntry(entry.rowId);
                            reload();
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
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
            setPadding(dp(21), dp(10), dp(21), dp(10));

            headerText = new TextView(context);
            headerText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            headerText.setTypeface(AndroidUtilities.bold());
            addView(headerText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            prevText = new TextView(context);
            prevText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            prevText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
            addView(prevText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 3, 0, 0));

            bodyText = new TextView(context);
            bodyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            bodyText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            addView(bodyText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 3, 0, 0));
        }

        public void set(PengramHistory.Entry entry) {
            final boolean deleted = entry.action == PengramHistory.ACTION_DELETED;
            headerText.setTextColor(Theme.getColor(deleted ? Theme.key_text_RedBold : Theme.key_windowBackgroundWhiteBlueText, getResourceProvider()));

            String who = peerName(entry.fromId != 0 ? entry.fromId : entry.dialogId);
            String when = LocaleController.getInstance().getFormatterStats().format(new Date(entry.savedAt * 1000L));
            headerText.setText((deleted ? getString(R.string.PengramHistoryDeletedMark) : getString(R.string.PengramHistoryEditedMark)) + " · " + who + " · " + when);

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
                } else if (peerId < 0) {
                    TLRPC.Chat chat = getMessagesController().getChat(-peerId);
                    if (chat != null) {
                        return chat.title;
                    }
                }
            } catch (Throwable ignore) {}
            return String.valueOf(peerId);
        }
    }
}
