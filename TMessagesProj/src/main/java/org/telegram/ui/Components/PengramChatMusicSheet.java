package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

/**
 * Pengram: вся музыка одного чата на одном экране.
 *
 * Ванильная вкладка «Музыка» — это серый список файлов. Здесь то же самое, но так,
 * как это выглядит в музыкальных приложениях: обложки, разбивка по месяцам, общая
 * длительность, играющий трек подсвечен и «дышит» эквалайзером, поиск по названию
 * и исполнителю, кнопки «Слушать всё» и «Перемешать».
 */
public class PengramChatMusicSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    /** что открыть по долгому нажатию на трек — показать сообщение в чате */
    public interface OnShowInChat {
        void run(MessageObject messageObject);
    }

    private static final int ITEM_TRACK = 0;
    private static final int ITEM_HEADER = 1;

    private final int currentAccount;
    private final long dialogId;
    private final int classGuid;
    private final OnShowInChat showInChat;

    private final ArrayList<MessageObject> tracks = new ArrayList<>();
    private final HashSet<Integer> knownIds = new HashSet<>();
    private final ArrayList<Object> items = new ArrayList<>();

    private final RecyclerListView listView;
    private final Adapter adapter;
    private final TextView subtitleView;
    private final TextView emptyView;
    private final RadialProgressView progressView;
    private final EditTextBoldCursor searchField;

    private String query = "";
    private final Runnable favoritesChangedListener = this::onFavoritesChanged;
    private int sortMode; // recent, oldest, title, artist
    private TextView sortButton;
    private boolean loading;
    private boolean cachePhase = true;
    private boolean endReached;
    private boolean loadError;
    private int minLoadedId = 0;
    private int requestIndex;
    private Runnable loadTimeout;

    private final int accentColor = 0xFF5FD0A0;

    public PengramChatMusicSheet(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, long dialogId, CharSequence chatName, OnShowInChat showInChat) {
        super(context, true, resourcesProvider);
        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.showInChat = showInChat;
        this.classGuid = ConnectionsManager.generateClassGuid();

        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        setUseLightStatusBar(false);

        final FrameLayout root = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
            }
        };
        root.setBackground(new HeaderBackground());
        containerView = root;

        // ---------- шапка ----------
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        root.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        final TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(TextUtils.isEmpty(chatName) ? getString(R.string.PengramChatMusicTitle) : chatName);
        header.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 22, 20, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(0x99FFFFFF);
        subtitleView.setText(getString(R.string.PengramChatMusicTitle));
        subtitleView.setOnClickListener(v -> {
            if (loadError) {
                loadError = false;
                loadMore();
                rebuild();
            }
        });
        header.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 4, 20, 0));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));
        buttons.addView(createActionButton(context, R.drawable.msg_played, getString(R.string.PengramChatMusicPlayAll), true, v -> playAll(false)),
                LayoutHelper.createLinear(0, 40, 1f, 4, 0, 4, 0));
        buttons.addView(createActionButton(context, R.drawable.player_new_shuffle, getString(R.string.PengramChatMusicShuffle), false, v -> playAll(true)),
                LayoutHelper.createLinear(0, 40, 1f, 4, 0, 4, 0));

        final LinearLayout searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(searchRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 16, 12, 16, 12));

        final FrameLayout searchBox = new FrameLayout(context);
        searchBox.setBackground(Theme.createRoundRectDrawable(dp(12), 0x1AFFFFFF));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, dp(40), 1f));

        sortButton = new TextView(context);
        sortButton.setGravity(Gravity.CENTER);
        sortButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sortButton.setTypeface(AndroidUtilities.bold());
        sortButton.setTextColor(0xFFFFFFFF);
        sortButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12), 0x1AFFFFFF, 0x2AFFFFFF));
        sortButton.setPadding(dp(12), 0, dp(12), 0);
        sortButton.setOnClickListener(v -> showSortMenu());
        searchRow.addView(sortButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, 8, 0, 0, 0));
        updateSortButton();

        final ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.smiles_inputsearch);
        searchIcon.setColorFilter(new PorterDuffColorFilter(0x80FFFFFF, PorterDuff.Mode.SRC_IN));
        searchBox.addView(searchIcon, LayoutHelper.createFrame(20, 20, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        searchField = new EditTextBoldCursor(context);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchField.setTextColor(0xFFFFFFFF);
        searchField.setHintTextColor(0x80FFFFFF);
        searchField.setHint(getString(R.string.PengramChatMusicSearchHint));
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setCursorColor(0xFFFFFFFF);
        searchField.setCursorWidth(1.5f);
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                rebuild();
                if (listView != null) listView.scrollToPosition(0);
            }
        });
        searchBox.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 38, 0, 12, 0));

        // ---------- список ----------
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.statusBarHeight, 0, dp(16));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size() || !(items.get(position) instanceof MessageObject)) {
                return;
            }
            play((MessageObject) items.get(position));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= items.size() || !(items.get(position) instanceof MessageObject)) {
                return false;
            }
            showTrackMenu((MessageObject) items.get(position));
            return true;
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                final LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() > items.size() - 12) {
                    loadMore();
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 196, 0, 0));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(0x80FFFFFF);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText(getString(R.string.PengramChatMusicEmpty));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        progressView = new RadialProgressView(context);
        progressView.setProgressColor(accentColor);
        progressView.setSize(dp(28));
        root.addView(progressView, LayoutHelper.createFrame(42, 42, Gravity.CENTER));

        // Под этой шапкой лишь градиент, а не обложка: здесь нужна тональная
        // MD3-поверхность, но не имитация преломления однотонного фона.
        org.telegram.messenger.PengramMD3.styleSheetHeader(header, resourcesProvider, null);
        loadMore();
    }

    private View createActionButton(Context context, int icon, CharSequence text, boolean filled, View.OnClickListener listener) {
        final FrameLayout button = new FrameLayout(context);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(20),
                filled ? accentColor : 0x1AFFFFFF, 0x22FFFFFF));
        button.setOnClickListener(listener);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        button.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final ImageView iconView = new ImageView(context);
        iconView.setImageResource(icon);
        iconView.setColorFilter(new PorterDuffColorFilter(filled ? 0xFF10231C : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        row.addView(iconView, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        final TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(filled ? 0xFF10231C : 0xFFFFFFFF);
        label.setText(text);
        row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        return button;
    }

    // ------------------------------- данные -------------------------------

    private void loadMore() {
        if (loading || endReached) {
            return;
        }
        loading = true;
        loadError = false;
        final int requestedIndex = ++requestIndex;
        MediaDataController.getInstance(currentAccount).loadMedia(dialogId, 60, minLoadedId, 0,
                MediaDataController.MEDIA_MUSIC, 0, cachePhase ? 1 : 0, classGuid, requestedIndex, null, null);
        if (loadTimeout != null) AndroidUtilities.cancelRunOnUIThread(loadTimeout);
        loadTimeout = () -> {
            if (loading && requestIndex == requestedIndex) {
                loading = false;
                loadError = true;
                rebuild();
            }
        };
        AndroidUtilities.runOnUIThread(loadTimeout, 15000);
    }

    @Override
    public void show() {
        super.show();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.mediaDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        org.telegram.messenger.PengramMusicFavorites.addListener(favoritesChangedListener);
    }

    @Override
    public void dismiss() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.mediaDidLoad);
        if (loadTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(loadTimeout);
            loadTimeout = null;
        }
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        org.telegram.messenger.PengramMusicFavorites.removeListener(favoritesChangedListener);
        AndroidUtilities.hideKeyboard(searchField);
        super.dismiss();
    }

    private void onFavoritesChanged() {
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.mediaDidLoad) {
            if (args.length <= 7 || !(args[3] instanceof Integer) || (Integer) args[3] != classGuid
                    || !(args[7] instanceof Integer) || (Integer) args[7] != requestIndex) {
                return;
            }
            if (loadTimeout != null) {
                AndroidUtilities.cancelRunOnUIThread(loadTimeout);
                loadTimeout = null;
            }
            final int type = (Integer) args[4];
            if (type != MediaDataController.MEDIA_MUSIC) {
                return;
            }
            final ArrayList<MessageObject> arr = (ArrayList<MessageObject>) args[2];
            boolean added = false;
            for (int a = 0; a < arr.size(); ++a) {
                final MessageObject messageObject = arr.get(a);
                if (messageObject == null || !knownIds.add(messageObject.getId())) {
                    continue;
                }
                tracks.add(messageObject);
                added = true;
                if (minLoadedId == 0 || messageObject.getId() < minLoadedId) {
                    minLoadedId = messageObject.getId();
                }
            }
            loading = false;
            loadError = false;
            if (cachePhase) {
                // кеш показали мгновенно — теперь догружаем то же самое с сервера
                cachePhase = false;
                loadMore();
            } else {
                endReached = arr.isEmpty() || (Boolean) args[5];
            }
            if (added || !loading) {
                rebuild();
            }
        } else if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged) {
            adapter.notifyDataSetChanged();
        }
    }

    /** пересобирает видимый список: фильтр поиска + заголовки месяцев */
    private void rebuild() {
        items.clear();
        long totalDuration = 0;
        final ArrayList<MessageObject> visible = getVisibleTracks();
        for (int a = 0; a < visible.size(); ++a) totalDuration += (long) visible.get(a).getDuration();
        int lastMonth = -1;
        int lastYear = -1;
        String lastLetter = null;
        final Calendar calendar = Calendar.getInstance();
        for (int a = 0; a < visible.size(); ++a) {
            final MessageObject messageObject = visible.get(a);
            if (sortMode < 2) {
                calendar.setTimeInMillis(messageObject.messageOwner.date * 1000L);
                final int month = calendar.get(Calendar.MONTH);
                final int year = calendar.get(Calendar.YEAR);
                if (month != lastMonth || year != lastYear) {
                    lastMonth = month;
                    lastYear = year;
                    items.add(LocaleController.formatYearMont(messageObject.messageOwner.date, true));
                }
            } else {
                final String group = firstLetter(sortText(messageObject, sortMode));
                if (!TextUtils.equals(lastLetter, group)) {
                    lastLetter = group;
                    items.add(group);
                }
            }
            items.add(messageObject);
        }
        final int shownCount = visible.size();
        final String countText = TextUtils.isEmpty(query)
                ? LocaleController.formatPluralString("MusicFiles", tracks.size())
                : LocaleController.formatPluralString("MusicFiles", shownCount) + " / " + tracks.size();
        subtitleView.setText(countText + " · " + formatTotal(totalDuration));
        progressView.setVisibility(loading && tracks.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(!loading && items.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setText(getString(loadError ? R.string.TryAgain : tracks.isEmpty() ? R.string.PengramChatMusicEmpty : R.string.NoResult));
        emptyView.setOnClickListener(loadError ? v -> {
            loadError = false;
            loadMore();
            rebuild();
        } : null);
        subtitleView.setContentDescription(loadError ? getString(R.string.TryAgain) : null);
        adapter.notifyDataSetChanged();
    }

    private ArrayList<MessageObject> getVisibleTracks() {
        final ArrayList<MessageObject> visible = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            final MessageObject track = tracks.get(i);
            if (matches(track)) visible.add(track);
        }
        if (sortMode == 0) {
            Collections.sort(visible, (a, b) -> Integer.compare(messageDate(b), messageDate(a)));
        } else if (sortMode == 1) {
            Collections.sort(visible, (a, b) -> Integer.compare(messageDate(a), messageDate(b)));
        } else {
            Collections.sort(visible, (a, b) -> sortText(a, sortMode).compareToIgnoreCase(sortText(b, sortMode)));
        }
        return visible;
    }

    private static int messageDate(MessageObject track) {
        return track == null || track.messageOwner == null ? 0 : track.messageOwner.date;
    }

    private static String sortText(MessageObject track, int mode) {
        final String title = track == null ? "" : track.getMusicTitle(true);
        final String artist = track == null ? "" : track.getMusicAuthor(true);
        final String safeTitle = title == null ? "" : title;
        final String safeArtist = artist == null ? "" : artist;
        final String value = mode == 3 ? safeArtist + " " + safeTitle : safeTitle;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstLetter(String value) {
        if (TextUtils.isEmpty(value)) return "#";
        final int cp = value.codePointAt(0);
        return Character.isLetterOrDigit(cp) ? new String(Character.toChars(cp)).toUpperCase(Locale.ROOT) : "#";
    }

    private void updateSortButton() {
        if (sortButton == null) return;
        final int[] labels = {R.string.PengramChatMusicSortRecent, R.string.PengramChatMusicSortOldest,
                R.string.PengramChatMusicSortTitle, R.string.PengramChatMusicSortArtist};
        sortButton.setText(getString(labels[Math.max(0, Math.min(sortMode, labels.length - 1))]));
        sortButton.setContentDescription(getString(R.string.PengramChatMusicSort));
    }

    private void showSortMenu() {
        final BottomSheet.Builder builder = new BottomSheet.Builder(getContext(), false, resourcesProvider);
        builder.setTitle(getString(R.string.PengramChatMusicSort), true);
        builder.setItems(new CharSequence[]{
                getString(R.string.PengramChatMusicSortRecent),
                getString(R.string.PengramChatMusicSortOldest),
                getString(R.string.PengramChatMusicSortTitle),
                getString(R.string.PengramChatMusicSortArtist)
        }, new int[]{R.drawable.msg_recent, R.drawable.msg_recent, R.drawable.msg_search, R.drawable.msg_contacts}, (dialog, which) -> {
            if (which < 0 || which > 3 || sortMode == which) return;
            sortMode = which;
            updateSortButton();
            rebuild();
        });
        builder.show();
    }

    private boolean matches(MessageObject messageObject) {
        if (TextUtils.isEmpty(query)) {
            return true;
        }
        final String needle = query.toLowerCase(Locale.ROOT);
        final String title = messageObject.getMusicTitle(true);
        final String author = messageObject.getMusicAuthor(true);
        final String filename = messageObject.getFileName();
        return contains(title, needle) || contains(author, needle) || contains(filename, needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String formatTotal(long seconds) {
        final long hours = seconds / 3600;
        final long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return LocaleController.formatString("PengramChatMusicHours", R.string.PengramChatMusicHours, hours, minutes);
        }
        return LocaleController.formatString("PengramChatMusicMinutes", R.string.PengramChatMusicMinutes, Math.max(1, minutes));
    }

    /** долгое нажатие на трек: переслать или показать сообщение в чате */
    private void showTrackMenu(MessageObject messageObject) {
        final BottomSheet.Builder builder = new BottomSheet.Builder(getContext(), false, resourcesProvider);
        final ArrayList<CharSequence> titles = new ArrayList<>();
        final ArrayList<Integer> icons = new ArrayList<>();
        titles.add(getString(R.string.PengramTrackForwardTitle));
        icons.add(R.drawable.msg_forward);
        if (showInChat != null) {
            titles.add(getString(R.string.PengramChatMusicShowInChat));
            icons.add(R.drawable.msg_message);
        }
        final int[] iconsArray = new int[icons.size()];
        for (int a = 0; a < icons.size(); ++a) {
            iconsArray[a] = icons.get(a);
        }
        builder.setTitle(messageObject.getMusicTitle(true), true);
        builder.setItems(titles.toArray(new CharSequence[0]), iconsArray, (dialog, which) -> {
            if (which == 0) {
                PengramTrackForward.start(getContext(), resourcesProvider, messageObject);
            } else if (showInChat != null) {
                showInChat.run(messageObject);
                dismiss();
            }
        });
        builder.show();
    }

    private void play(MessageObject messageObject) {
        final ArrayList<MessageObject> playlist = getVisibleTracks();
        if (MediaController.getInstance().setPlaylist(playlist, messageObject, 0, false, null)) {
            adapter.notifyDataSetChanged();
        }
    }

    private void playAll(boolean shuffle) {
        final ArrayList<MessageObject> playlist = getVisibleTracks();
        if (playlist.isEmpty()) {
            return;
        }
        if (shuffle) {
            Collections.shuffle(playlist);
        }
        MediaController.getInstance().setPlaylist(playlist, playlist.get(0), 0, false, null);
        adapter.notifyDataSetChanged();
    }

    // ------------------------------- список -------------------------------

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == ITEM_TRACK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            if (viewType == ITEM_HEADER) {
                final TextView header = new TextView(context);
                header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                header.setTypeface(AndroidUtilities.bold());
                header.setTextColor(0x99FFFFFF);
                header.setPadding(dp(20), dp(14), dp(20), dp(6));
                header.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                return new RecyclerListView.Holder(header);
            }
            return new RecyclerListView.Holder(new TrackCell(context));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Object item = items.get(position);
            if (item instanceof MessageObject) {
                ((TrackCell) holder.itemView).set((MessageObject) item);
            } else {
                ((TextView) holder.itemView).setText(String.valueOf(item));
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof MessageObject ? ITEM_TRACK : ITEM_HEADER;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** строка трека: обложка, название, исполнитель, длительность и эквалайзер у играющего */
    private class TrackCell extends FrameLayout {

        private final BackupImageView cover;
        private final TextView title;
        private final TextView subtitle;
        private final TextView duration;
        private final ImageView heartButton;
        private final EqualizerView equalizer;
        private final RectF rect = new RectF();
        private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** заглушка вместо обложки создаётся один раз на ячейку, а не на каждую привязку */
        private final android.graphics.drawable.Drawable coverPlaceholder = Theme.createRoundRectDrawable(dp(10), 0x1FFFFFFF);
        private boolean active;
        private MessageObject boundMessage;

        TrackCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(14), 0, dp(14), 0);

            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(10));
            addView(cover, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));

            equalizer = new EqualizerView(context);
            equalizer.setVisibility(GONE);
            addView(equalizer, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));

            final boolean favOn = org.telegram.messenger.PengramConfig.isPlayerFavoritesEnabled()
                    && org.telegram.messenger.PengramConfig.isPlayerFavShowChatList();
            final int rightTextPad = favOn ? 104 : 74;

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.TOP, 80, 12, rightTextPad, 0));

            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setTextColor(0x8AFFFFFF);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            addView(subtitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.TOP, 80, 33, rightTextPad, 0));

            heartButton = new ImageView(context);
            heartButton.setScaleType(ImageView.ScaleType.CENTER);
            heartButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(16)));
            heartButton.setVisibility(favOn ? VISIBLE : GONE);
            heartButton.setOnClickListener(v -> {
                if (boundMessage == null || (!org.telegram.messenger.PengramConfig.isPlayerFavoritesEnabled()
                        || !org.telegram.messenger.PengramConfig.isPlayerFavShowChatList())) {
                    return;
                }
                org.telegram.messenger.PengramMusicFavorites.toggleFavorite(boundMessage, getContext());
                updateHeart(boundMessage);
            });
            addView(heartButton, LayoutHelper.createFrame(32, 32,
                    Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 56, 0));

            duration = new TextView(context);
            duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            duration.setTextColor(0x8AFFFFFF);
            addView(duration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
        }

        private void updateHeart(MessageObject msg) {
            if (heartButton == null) {
                return;
            }
            final boolean showHeart = org.telegram.messenger.PengramConfig.isPlayerFavoritesEnabled()
                    && org.telegram.messenger.PengramConfig.isPlayerFavShowChatList();
            final int rightTextPad = dp(showHeart ? 104 : 74);
            final FrameLayout.LayoutParams titleLp = (FrameLayout.LayoutParams) title.getLayoutParams();
            final FrameLayout.LayoutParams subtitleLp = (FrameLayout.LayoutParams) subtitle.getLayoutParams();
            if (titleLp.rightMargin != rightTextPad || subtitleLp.rightMargin != rightTextPad) {
                titleLp.rightMargin = rightTextPad;
                subtitleLp.rightMargin = rightTextPad;
                title.setLayoutParams(titleLp);
                subtitle.setLayoutParams(subtitleLp);
            }
            if (!showHeart || msg == null) {
                heartButton.setVisibility(GONE);
                return;
            }
            heartButton.setVisibility(VISIBLE);
            final boolean liked = org.telegram.messenger.PengramMusicFavorites.isLiked(msg);
            heartButton.setImageResource(liked ? R.drawable.ic_pengram_heart_filled : R.drawable.ic_pengram_heart_outline);
            heartButton.setColorFilter(new PorterDuffColorFilter(liked ? 0xFFFF3B5C : 0x80FFFFFF, PorterDuff.Mode.SRC_IN));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(68), MeasureSpec.EXACTLY));
        }

        void set(MessageObject messageObject) {
            boundMessage = messageObject;
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            active = playing != null && playing.getId() == messageObject.getId()
                    && playing.getDialogId() == messageObject.getDialogId();

            if (org.telegram.messenger.PengramConfig.isSmartTrackRecognition()) {
                final String[] pair = org.telegram.messenger.PengramMusicMeta.resolveDisplayPair(messageObject);
                title.setText(pair[1]);
                subtitle.setText(pair[0]);
            } else {
                title.setText(messageObject.getMusicTitle(true));
                subtitle.setText(messageObject.getMusicAuthor(true));
            }
            title.setTextColor(active ? accentColor : 0xFFFFFFFF);
            title.setTypeface(active ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
            duration.setText(AndroidUtilities.formatShortDuration((int) messageObject.getDuration()));
            updateHeart(messageObject);

            final TLRPC.Document document = messageObject.getDocument();
            final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90) : null;
            final ImageLocation thumbLocation = thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive
                    ? ImageLocation.getForDocument(thumb, document) : null;
            final String artworkUrl = messageObject.getArtworkUrl(true);
            if (!TextUtils.isEmpty(artworkUrl)) {
                cover.setImage(ImageLocation.getForPath(artworkUrl), "48_48", thumbLocation, null, null, 0, 1, messageObject);
            } else if (thumbLocation != null) {
                cover.setImage(null, null, thumbLocation, null, null, 0, 1, messageObject);
            } else {
                cover.setImageDrawable(coverPlaceholder);
            }

            final boolean playingNow = active && !MediaController.getInstance().isMessagePaused();
            equalizer.setVisibility(active ? VISIBLE : GONE);
            equalizer.setPlaying(playingNow);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!active) {
                return;
            }
            rect.set(dp(12), dp(4), getWidth() - dp(12), getHeight() - dp(4));
            highlightPaint.setColor(ColorUtils.setAlphaComponent(accentColor, 28));
            canvas.drawRoundRect(rect, dp(14), dp(14), highlightPaint);
        }
    }

    /** три столбика, которые скачут, пока трек играет */
    private class EqualizerView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float[] phase = new float[]{0f, 0.35f, 0.7f};
        private boolean playing;
        private long lastTime;

        EqualizerView(Context context) {
            super(context);
            paint.setColor(0xFFFFFFFF);
            scrim.setColor(0x80000000);
        }

        void setPlaying(boolean value) {
            if (playing != value) {
                playing = value;
                lastTime = 0;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, dp(10), dp(10), scrim);

            final long now = System.currentTimeMillis();
            final float dt = lastTime == 0 ? 0 : Math.min(60, now - lastTime) / 1000f;
            lastTime = now;

            final float barWidth = dp(3);
            final float gap = dp(3);
            final float totalWidth = barWidth * 3 + gap * 2;
            final float left = (getWidth() - totalWidth) / 2f;
            final float centerY = getHeight() / 2f;
            final float maxHeight = dp(18);
            for (int a = 0; a < 3; ++a) {
                if (playing) {
                    phase[a] += dt * (1.6f + a * 0.35f);
                }
                final float value = playing
                        ? 0.35f + 0.65f * (float) Math.abs(Math.sin(phase[a] * Math.PI))
                        : 0.35f;
                final float height = maxHeight * value;
                rect.set(left + a * (barWidth + gap), centerY - height / 2f,
                        left + a * (barWidth + gap) + barWidth, centerY + height / 2f);
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
            }
            if (playing && isAttachedToWindow() && isShown()) {
                postInvalidateOnAnimation();
            }
        }
    }

    /** фон: мягкий градиент от акцента к почти чёрному — как у плеера Pengram */
    private class HeaderBackground extends android.graphics.drawable.Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** градиент пересоздаём только при смене размера, а не в каждом кадре */
        private int shaderHeight = -1;

        @Override
        public void draw(@NonNull Canvas canvas) {
            final android.graphics.Rect bounds = getBounds();
            if (shaderHeight != bounds.height()) {
                shaderHeight = bounds.height();
                paint.setShader(new LinearGradient(0, 0, 0, Math.max(1, shaderHeight * 0.5f),
                        new int[]{ColorUtils.blendARGB(0xFF11161B, accentColor, 0.28f), 0xFF0E1115},
                        null, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(bounds, paint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }
}
