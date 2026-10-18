package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.os.Build;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramMD3;
import org.telegram.messenger.PengramFonts;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PengramSearchIndex;
import org.telegram.ui.PengramSettingsActivity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/** A self-contained, sample-data navigation surface. No real dialogs or users are read here. */
public class PengramInteractivePreviewView extends LinearLayout {
    public static final int DIALOGS = 0, CHAT = 1, CONTACTS = 2, SETTINGS = 3,
            CALLS = 4, PROFILE = 5, PLAYER = 6, SEARCH = 7, MENU = 8, CHAT_BUTTONS = 9,
            APPEARANCE = 10, SHEET = 11, ALERT = 12, NATIVE_SETTINGS = 13,
            NEW_MESSAGE = 14, STORY = 15, SETTINGS_LIST = 16;
    public static final int TITLE = 1, SEARCH_BAR = 2, DIALOG_ROW = 3, FAB = 4,
            TAB = 5, MD3 = 6, SETTINGS_ROW = 7, MENU_ROW = 8, PLAYER_CARD = 9,
            STORIES = 10, CHAT_BUTTON_ROW = 11, PREVIEW_ONLY = 12, SETTINGS_OPTION = 13;

    public interface Listener {
        void onSelection(int element, int id);
        void onChanged();
    }

    private final PengramScreenMockView chatMock;
    private final FrameLayout heading;
    private final LinearLayout body;
    private final ScrollView scroller;
    private final FrameLayout contentFrame;
    private final LinearLayout footer;
    private TextView fabView;
    private Listener listener;
    private int page = DIALOGS;
    private final ArrayDeque<Integer> history = new ArrayDeque<>();
    private int selectedElement = -1, selectedId = -1;
    private int menuMode;
    private int nativeSettingsId = PengramConfig.SETTINGS_ITEM_CHAT;
    private int settingsSection = PengramSettingsActivity.SECTION_APPEARANCE;
    private int sampleChatIndex;
    private String searchQuery = "";
    private final String[] sampleDrafts = new String[3];
    private final String[] sampleSent = new String[3];
    private static final int[] SAMPLE_CHAT_TITLES = {
            R.string.PengramMD3PreviewChat1, R.string.PengramMD3PreviewChat2,
            R.string.PengramMD3PreviewChat3
    };
    private static final int[] SAMPLE_CHAT_LINES = {
            R.string.PengramMD3PreviewLine1, R.string.PengramMD3PreviewLine2,
            R.string.PengramMD3PreviewLine3
    };

    public PengramInteractivePreviewView(Context context, PengramScreenMockView chatMock) {
        super(context);
        this.chatMock = chatMock;
        setOrientation(VERTICAL);
        setBackgroundColor(color(Theme.key_windowBackgroundWhite));
        heading = new FrameLayout(context);
        addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setClipChildren(false);
        scroller.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentFrame = new FrameLayout(context);
        contentFrame.addView(scroller, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        render();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Keep local test-only navigation and draft state if Telegram recreates this fragment view. */
    public void restoreFrom(PengramInteractivePreviewView previous) {
        if (previous == null) return;
        page = previous.page;
        menuMode = previous.menuMode;
        nativeSettingsId = previous.nativeSettingsId;
        settingsSection = previous.settingsSection;
        sampleChatIndex = previous.sampleChatIndex;
        searchQuery = previous.searchQuery;
        history.clear();
        history.addAll(previous.history);
        System.arraycopy(previous.sampleDrafts, 0, sampleDrafts, 0, sampleDrafts.length);
        System.arraycopy(previous.sampleSent, 0, sampleSent, 0, sampleSent.length);
        render();
    }

    public int getPage() {
        return page;
    }

    public int getSelectedElement() {
        return selectedElement;
    }

    public int getSelectedId() {
        return selectedId;
    }

    public boolean goBack() {
        if (page == DIALOGS) return false;
        final int target = history.isEmpty() ? DIALOGS : history.removeLast();
        displayPage(target);
        return true;
    }

    public void showPage(int newPage) {
        if (newPage == page) return;
        history.addLast(page);
        // Bound the navigation history even if the user repeatedly opens sample chats.
        if (history.size() > 20) history.removeFirst();
        displayPage(newPage);
    }

    private void openChat(int index) {
        sampleChatIndex = Math.max(0, Math.min(SAMPLE_CHAT_TITLES.length - 1, index));
        showPage(CHAT);
    }

    private void openNativeSettings(int id) {
        nativeSettingsId = id;
        showPage(NATIVE_SETTINGS);
    }

    private void openSettingsSection(int section) {
        settingsSection = section;
        showPage(SETTINGS_LIST);
    }

    private void switchTab(int newPage) {
        history.clear();
        displayPage(newPage);
    }

    private void displayPage(int newPage) {
        page = newPage;
        selectedElement = selectedId = -1;
        if (listener != null) listener.onSelection(-1, -1);
        scroller.scrollTo(0, 0);
        render();
    }

    private void select(int element, int id) {
        selectedElement = element;
        selectedId = id;
        if (listener != null) listener.onSelection(element, id);
    }

    private int color(int key) {
        return Theme.getColor(key);
    }

    private TextView text(String label, int size, int foreground) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(foreground);
        view.setGravity(Gravity.CENTER_VERTICAL);
        final Typeface font = PengramFonts.typeface(PengramConfig.getAppFont());
        if (font != null) view.setTypeface(font);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private TextView button(String label, Runnable click) {
        TextView view = text(label, 17, color(Theme.key_windowBackgroundWhiteBlackText));
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private void drawHeading() {
        heading.removeAllViews();
        heading.setBackgroundColor(color(Theme.key_windowBackgroundWhite));
        final TextView back = button(page == DIALOGS ? "☰" : "‹", () -> {
            if (page == DIALOGS) {
                menuMode = 0;
                showPage(MENU);
            } else goBack();
        });
        heading.addView(back, new FrameLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));
        final String title;
        switch (page) {
            case CHAT: title = getString(SAMPLE_CHAT_TITLES[sampleChatIndex]); break;
            case CONTACTS: title = getString(R.string.Contacts); break;
            case SETTINGS: title = getString(R.string.Settings); break;
            case CALLS: title = getString(R.string.Calls); break;
            case PROFILE: title = getString(R.string.PengramTabProfile); break;
            case PLAYER: title = getString(R.string.PengramConstructorPlayer); break;
            case SEARCH: title = getString(R.string.Search); break;
            case MENU: title = getString(menuMode == 1 ? R.string.PengramChatItemsTitle : R.string.PengramMenuItemsTitle); break;
            case CHAT_BUTTONS: title = getString(R.string.PengramConstructorChatButtons); break;
            case APPEARANCE: title = getString(R.string.PengramSettings); break;
            case SHEET: title = getString(R.string.PengramMD3Sheets); break;
            case ALERT: title = getString(R.string.PengramMD3Alerts); break;
            case NATIVE_SETTINGS: title = getString(PengramConfig.getSettingsItemTitle(nativeSettingsId)); break;
            case SETTINGS_LIST: title = PengramSettingsActivity.sectionTitle(settingsSection).toString(); break;
            case NEW_MESSAGE: title = getString(R.string.PengramEditorNewMessage); break;
            case STORY: title = getString(R.string.PengramEditorStories); break;
            default: title = sampleTitle(); break;
        }
        final TextView name = text(title, 18, color(Theme.key_windowBackgroundWhiteBlackText));
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        if (page == DIALOGS && PengramConfig.isTitleCentered()) name.setGravity(Gravity.CENTER);
        name.setPadding(dp(4), 0, dp(5), 0);
        name.setOnClickListener(v -> {
            if (page == DIALOGS) select(TITLE, 0);
        });
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        nameParams.leftMargin = dp(52);
        nameParams.rightMargin = dp(50);
        heading.addView(name, nameParams);
        final TextView more = button(page == DIALOGS || page == CHAT ? "⋮" : "⌕", () -> {
            if (page == DIALOGS || page == CHAT) {
                menuMode = page == CHAT ? 1 : 0;
                showPage(MENU);
            } else showPage(SEARCH);
        });
        heading.addView(more, new FrameLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
    }

    private String sampleTitle() {
        switch (PengramConfig.getTitleMode()) {
            case PengramConfig.TITLE_MODE_PENGRAM: return "Pengram";
            case PengramConfig.TITLE_MODE_CHATS: return getString(R.string.PengramTitleChats);
            case PengramConfig.TITLE_MODE_NAME: return getString(R.string.PengramEditorName);
            case PengramConfig.TITLE_MODE_USERNAME: return "@username";
            case PengramConfig.TITLE_MODE_CUSTOM:
                return PengramConfig.getTitleCustom().isEmpty() ? "Pengram" : PengramConfig.getTitleCustom();
            default: return "Telegram";
        }
    }

    public void render() {
        drawHeading();
        body.removeAllViews();
        footer.removeAllViews();
        if (fabView != null) {
            contentFrame.removeView(fabView);
            fabView = null;
        }
        body.setBackgroundColor(color(Theme.key_windowBackgroundWhite));
        body.setPadding(0, 0, 0, page == DIALOGS ? dp(68) : 0);
        switch (page) {
            case DIALOGS: renderDialogs(); renderFab(); break;
            case CHAT:
                chatMock.setEmbedded(true);
                chatMock.setSampleMessage(sampleSent[sampleChatIndex]);
                chatMock.refresh();
                if (chatMock.getParent() instanceof ViewGroup) ((ViewGroup) chatMock.getParent()).removeView(chatMock);
                body.addView(chatMock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(350)));
                final TextView editButtons = row(getString(R.string.PengramConstructorChatButtons), null, () -> showPage(CHAT_BUTTONS));
                body.addView(editButtons);
                break;
            case SETTINGS: renderSettings(); break;
            case APPEARANCE: renderAppearance(); break;
            case SHEET: renderDemoOverlay(PengramMD3.sheets()); break;
            case ALERT: renderDemoOverlay(PengramMD3.alerts()); break;
            case NATIVE_SETTINGS: renderNativeSettings(); break;
            case SETTINGS_LIST: renderSettingsList(); break;
            case MENU: renderMenu(); break;
            case CHAT_BUTTONS: renderChatButtons(); break;
            case PLAYER: renderPlayer(); break;
            case SEARCH: renderSearch(); break;
            case NEW_MESSAGE:
                body.addView(row(getString(R.string.PengramEditorComposeSampleInfo), null,
                        () -> select(PREVIEW_ONLY, 0)));
                renderSampleDialogs();
                break;
            case STORY:
                TextView storyCard = button(getString(R.string.PengramEditorSampleStory),
                        () -> select(STORIES, 0));
                storyCard.setTextColor(color(Theme.key_featuredStickers_buttonText));
                storyCard.setBackground(Theme.createRoundRectDrawable(dp(22),
                        ColorUtils.blendARGB(color(Theme.key_featuredStickers_addButton), 0xFF171B32, .52f)));
                LinearLayout.LayoutParams storyParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(335));
                storyParams.setMargins(dp(14), dp(8), dp(14), dp(8));
                body.addView(storyCard, storyParams);
                body.addView(row(getString(R.string.PengramEditorSafePreviewHint), null,
                        () -> select(STORIES, 0)));
                break;
            case CONTACTS:
                body.addView(row(getString(R.string.PengramEditorSampleOnly), null, () -> select(PREVIEW_ONLY, 0)));
                renderSampleDialogs();
                break;
            case CALLS:
                body.addView(row(getString(R.string.PengramEditorSampleCalls), null, () -> select(PREVIEW_ONLY, 0)));
                for (int i = 0; i < 3; i++) {
                    final int index = i;
                    body.addView(row(getString(i == 0 ? R.string.PengramMD3PreviewChat1
                                    : i == 1 ? R.string.PengramMD3PreviewChat2 : R.string.PengramMD3PreviewChat3),
                            getString(i == 1 ? R.string.PengramEditorMissedCall : R.string.PengramEditorOutgoingCall),
                            () -> select(PREVIEW_ONLY, index)));
                }
                break;
            case PROFILE:
                body.addView(row("◉   " + getString(R.string.PengramEditorName),
                        getString(R.string.PengramEditorSampleOnly), () -> select(PREVIEW_ONLY, 0)));
                body.addView(row(getString(R.string.PengramConstructorPlayer), null, () -> showPage(PLAYER)));
                body.addView(row(getString(R.string.Settings), null, () -> showPage(SETTINGS)));
                break;
        }
        final boolean tabPage = page == DIALOGS || page == CONTACTS || page == SETTINGS || page == CALLS || page == PROFILE;
        footer.setVisibility(tabPage || page == CHAT ? VISIBLE : GONE);
        if (tabPage) renderTabs();
        else if (page == CHAT) renderChatComposer();
    }

    private TextView row(String label, String sub, Runnable click) {
        final TextView cell = text(sub == null ? label : label + "\n" + sub, sub == null ? 15 : 14,
                color(Theme.key_windowBackgroundWhiteBlackText));
        cell.setSingleLine(false);
        cell.setMaxLines(2);
        cell.setPadding(dp(18), dp(6), dp(14), dp(6));
        cell.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
        cell.setOnClickListener(v -> click.run());
        cell.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(sub == null ? 51 : 62)));
        return cell;
    }

    private void renderDialogs() {
        TextView stories = row("◉   " + getString(R.string.PengramEditorStories), null,
                () -> { if (PengramConfig.getBool(PengramConfig.KEY_HIDE_STORIES, false)) select(STORIES, 0);
                        else showPage(STORY); });
        stories.setOnLongClickListener(v -> { select(STORIES, 0); return true; });
        stories.setAlpha(PengramConfig.getBool(PengramConfig.KEY_HIDE_STORIES, false) ? .38f : 1f);
        body.addView(stories);
        final TextView search = row("⌕    " + getString(R.string.Search), null, () -> showPage(SEARCH));
        search.setBackground(Theme.createRoundRectDrawable(dp(PengramMD3.searchBar() ? 22 : 12),
                PengramMD3.searchBar() ? PengramMD3.searchBarColor(null, Theme.isCurrentThemeDark())
                        : ColorUtils.blendARGB(color(Theme.key_windowBackgroundWhite), color(Theme.key_windowBackgroundWhiteBlackText), .06f)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45));
        sp.setMargins(dp(12), dp(3), dp(12), dp(12));
        body.addView(search, sp);
        search.setElevation(PengramMD3.searchBarShadow() ? dp(4) : 0);
        search.setOnLongClickListener(v -> { select(SEARCH_BAR, 0); return true; });
        renderSampleDialogs();
    }

    private void renderFab() {
        fabView = button("✎", () -> {
            if (PengramConfig.getBool(PengramConfig.KEY_HIDE_WRITE_BUTTON, false)) select(FAB, 0);
            else showPage(NEW_MESSAGE);
        });
        fabView.setOnLongClickListener(v -> { select(FAB, 0); return true; });
        fabView.setContentDescription(getString(R.string.PengramEditorFab));
        fabView.setTextColor(color(Theme.key_featuredStickers_buttonText));
        fabView.setBackground(Theme.createRoundRectDrawable(dp(PengramMD3.fab() ? 16 : 28),
                color(Theme.key_featuredStickers_addButton)));
        fabView.setAlpha(PengramConfig.getBool(PengramConfig.KEY_HIDE_WRITE_BUTTON, false) ? .38f : 1f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.RIGHT | Gravity.BOTTOM);
        params.setMargins(0, 0, dp(16), dp(14));
        contentFrame.addView(fabView, params);
    }

    private void renderSampleDialogs() {
        renderSampleDialogs(body, "");
    }

    private void renderSearch() {
        final EditText search = new EditText(getContext());
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint(R.string.Search);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        search.setText(searchQuery);
        search.setSelection(search.length());
        search.setPadding(dp(18), dp(4), dp(18), dp(4));
        search.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        search.setHintTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        search.setBackground(Theme.createRoundRectDrawable(dp(PengramMD3.searchBar() ? 22 : 12),
                PengramMD3.searchBar() ? PengramMD3.searchBarColor(null, Theme.isCurrentThemeDark())
                        : ColorUtils.blendARGB(color(Theme.key_windowBackgroundWhite),
                                color(Theme.key_windowBackgroundWhiteBlackText), .06f)));
        search.setElevation(PengramMD3.searchBarShadow() ? dp(4) : 0);
        search.setOnLongClickListener(v -> { select(SEARCH_BAR, 0); return true; });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        searchParams.setMargins(dp(12), dp(4), dp(12), dp(6));
        body.addView(search, searchParams);
        final LinearLayout results = new LinearLayout(getContext());
        results.setOrientation(VERTICAL);
        body.addView(results);
        renderSampleDialogs(results, searchQuery);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString();
                results.removeAllViews();
                renderSampleDialogs(results, searchQuery);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void renderSampleDialogs(LinearLayout destination, String query) {
        final String normalized = query.trim().toLowerCase(Locale.ROOT);
        int matches = 0;
        for (int i = 0; i < SAMPLE_CHAT_TITLES.length; i++) {
            if (!normalized.isEmpty() && !getString(SAMPLE_CHAT_TITLES[i]).toLowerCase(Locale.ROOT).contains(normalized)
                    && !getString(SAMPLE_CHAT_LINES[i]).toLowerCase(Locale.ROOT).contains(normalized)) continue;
            matches++;
            final int index = i;
            FrameLayout item = new FrameLayout(getContext());
            final int surface = color(Theme.key_windowBackgroundWhite);
            final int accent = color(Theme.key_featuredStickers_addButton);
            TextView avatar = button(i == 2 ? "★" : getString(SAMPLE_CHAT_TITLES[i]).substring(0, 1),
                    () -> select(page == CONTACTS ? PREVIEW_ONLY : DIALOG_ROW, index));
            avatar.setTextSize(16);
            avatar.setTextColor(color(Theme.key_featuredStickers_buttonText));
            final int radius = PengramConfig.getDialogAvatarShape() == PengramConfig.DIALOG_AVATAR_CIRCLE
                    ? 25 : PengramConfig.getDialogAvatarShape() == PengramConfig.DIALOG_AVATAR_ROUNDED ? 12 : 3;
            avatar.setBackground(Theme.createRoundRectDrawable(dp(radius),
                    ColorUtils.blendARGB(accent, surface, i * .16f)));
            FrameLayout.LayoutParams avatarParams = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER_VERTICAL | Gravity.LEFT);
            avatarParams.leftMargin = dp(12);
            item.addView(avatar, avatarParams);
            TextView summary = row(getString(SAMPLE_CHAT_TITLES[i]), getString(SAMPLE_CHAT_LINES[i]), () -> openChat(index));
            summary.setBackground(null);
            FrameLayout.LayoutParams summaryParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            summaryParams.leftMargin = dp(60);
            item.addView(summary, summaryParams);
            if (PengramMD3.tonalRows() && i < 2) {
                item.setBackground(Theme.createRoundRectDrawable(dp(22),
                        i == 0 ? PengramMD3.unreadContainer(null) : PengramMD3.pinnedContainer(null)));
            }
            item.setOnClickListener(v -> openChat(index));
            item.setOnLongClickListener(v -> { select(page == CONTACTS ? PREVIEW_ONLY : DIALOG_ROW, index); return true; });
            summary.setOnLongClickListener(v -> { select(page == CONTACTS ? PREVIEW_ONLY : DIALOG_ROW, index); return true; });
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68));
            itemParams.setMargins(dp(8), dp(2), dp(8), dp(2));
            destination.addView(item, itemParams);
            if (!PengramMD3.hideDividers() && i != 2) {
                View line = new View(getContext());
                line.setBackgroundColor(ColorUtils.setAlphaComponent(color(Theme.key_windowBackgroundWhiteBlackText), 25));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                lp.leftMargin = dp(60);
                destination.addView(line, lp);
            }
        }
        if (matches == 0) {
            destination.addView(row(getString(R.string.PengramEditorNoMatches), null,
                    () -> select(PREVIEW_ONLY, 0)));
        }
    }

    private void sectionHeader(int label, int kind, int destination) {
        TextView header = text(getString(label), 13, color(Theme.key_windowBackgroundWhiteGrayText2));
        header.setPadding(dp(18), dp(8), dp(12), 0);
        header.setContentDescription(getString(label));
        header.setOnDragListener((v, event) -> {
            Object state = event.getLocalState();
            if (!(state instanceof int[]) || ((int[]) state)[0] != kind) return false;
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return true;
            if (event.getAction() == DragEvent.ACTION_DRAG_ENTERED) {
                v.setBackground(Theme.createRoundRectDrawable(dp(12),
                        ColorUtils.setAlphaComponent(color(Theme.key_featuredStickers_addButton), 55)));
            } else if (event.getAction() == DragEvent.ACTION_DRAG_EXITED
                    || event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                v.setBackground(null);
            } else if (event.getAction() == DragEvent.ACTION_DROP) {
                v.setBackground(null);
                final int id = ((int[]) state)[1];
                if (setItemSection(kind, id, destination)) {
                    post(() -> { if (isAttachedToWindow()) render(); });
                    if (listener != null) listener.onChanged();
                }
                return true;
            }
            return true;
        });
        body.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
    }

    private boolean setItemSection(int kind, int id, int destination) {
        if (kind == SETTINGS_ROW) {
            if (id == PengramConfig.SETTINGS_ITEM_PENGRAM && destination == 2) return false;
            boolean hidden = destination == 2;
            if (PengramConfig.isSettingsItemHidden(id) == hidden) return false;
            PengramConfig.setSettingsItemHidden(id, hidden);
        } else if (kind == MENU_ROW) {
            if ((id == PengramConfig.MENU_ITEM_PENGRAM || id == PengramConfig.MENU_ITEM_SETTINGS)
                    && destination == 2) return false;
            boolean hidden = destination == 2;
            if (PengramConfig.isMenuItemHidden(id) == hidden) return false;
            PengramConfig.setMenuItemHidden(id, hidden);
        } else if (kind == CHAT_BUTTON_ROW) {
            boolean hidden = destination == 2;
            int place = destination == 1 ? PengramConfig.CHAT_PLACE_ISLAND : PengramConfig.CHAT_PLACE_MAIN;
            if (PengramConfig.isChatItemHidden(id) == hidden
                    && (hidden || PengramConfig.getChatItemPlacement(id) == place)) return false;
            PengramConfig.setChatItemHidden(id, hidden);
            if (!hidden) PengramConfig.setChatItemPlacement(id, place);
        } else return false;
        select(kind, id);
        return true;
    }

    private void renderSettings() {
        final ArrayList<Integer> order = PengramConfig.getSettingsOrder();
        sectionHeader(R.string.PengramPlaceVisible, SETTINGS_ROW, 0);
        final boolean cardOnTop = PengramConfig.isPengramCardOnTop();
        if (cardOnTop && !PengramConfig.isSettingsItemHidden(PengramConfig.SETTINGS_ITEM_PENGRAM)) {
            addSettingsItem(PengramConfig.SETTINGS_ITEM_PENGRAM, false);
        }
        for (int id : order) {
            if (!PengramConfig.isSettingsItemHidden(id)
                    && !(cardOnTop && id == PengramConfig.SETTINGS_ITEM_PENGRAM)) addSettingsItem(id, false);
        }
        sectionHeader(R.string.PengramEditorHiddenSection, SETTINGS_ROW, 2);
        for (int id : order) if (PengramConfig.isSettingsItemHidden(id)) addSettingsItem(id, true);
    }

    private void addSettingsItem(int id, boolean hidden) {
        TextView item = row("⚙   " + getString(PengramConfig.getSettingsItemTitle(id)), null,
                () -> { if (hidden) select(SETTINGS_ROW, id);
                        else if (id == PengramConfig.SETTINGS_ITEM_PENGRAM) showPage(APPEARANCE);
                        else openNativeSettings(id); });
        item.setAlpha(hidden ? .42f : 1f);
        body.addView(item);
        if (id == PengramConfig.SETTINGS_ITEM_PENGRAM && PengramConfig.isPengramCardOnTop() && !hidden) {
            item.setOnLongClickListener(v -> { select(SETTINGS_ROW, id); return true; });
        } else {
            enableSafeDrag(item, SETTINGS_ROW, id);
        }
    }

    private void renderNativeSettings() {
        body.addView(row(getString(R.string.PengramEditorNativeSettingsSample), null,
                () -> select(SETTINGS_ROW, nativeSettingsId)));
        // Representative static labels only: never instantiate native screens or
        // read the account to populate privacy, devices, folders or notifications.
        final int[] labels;
        switch (nativeSettingsId) {
            case PengramConfig.SETTINGS_ITEM_NOTIFICATIONS:
                labels = new int[]{R.string.NotificationsPrivateChats, R.string.NotificationsGroups,
                        R.string.NotificationsChannels, R.string.NotificationsStories}; break;
            case PengramConfig.SETTINGS_ITEM_ACCOUNT:
                labels = new int[]{R.string.EditName, R.string.ChangePhoneNumber,
                        R.string.Username, R.string.PrivacyBio}; break;
            case PengramConfig.SETTINGS_ITEM_CHAT:
                labels = new int[]{R.string.ChatBackground, R.string.TextSizeHeader,
                        R.string.BubbleRadius, R.string.LargeEmoji}; break;
            case PengramConfig.SETTINGS_ITEM_PRIVACY:
                labels = new int[]{R.string.PrivacyPhone, R.string.PrivacyLastSeen,
                        R.string.Passcode, R.string.TwoStepVerification}; break;
            case PengramConfig.SETTINGS_ITEM_DATA:
                labels = new int[]{R.string.StorageUsage, R.string.AutomaticMediaDownload,
                        R.string.DataUsage, R.string.SaveToGallery}; break;
            case PengramConfig.SETTINGS_ITEM_FOLDERS:
                labels = new int[]{R.string.FilterNew, R.string.FilterInclude, R.string.FilterExclude}; break;
            case PengramConfig.SETTINGS_ITEM_DEVICES:
                labels = new int[]{R.string.CurrentSession, R.string.OtherSessions,
                        R.string.LinkDesktopDevice}; break;
            case PengramConfig.SETTINGS_ITEM_POWER:
                labels = new int[]{R.string.LiteBatteryTitle, R.string.LiteBatteryAlwaysEnabled,
                        R.string.LiteOptionsStickers}; break;
            case PengramConfig.SETTINGS_ITEM_LANGUAGE:
                labels = new int[]{R.string.Language, R.string.DoNotTranslate,
                        R.string.ShowTranslateButton}; break;
            default: labels = new int[0]; break;
        }
        for (int label : labels) {
            body.addView(row(getString(label), null, () -> select(PREVIEW_ONLY, label)));
        }
        if (nativeSettingsId == PengramConfig.SETTINGS_ITEM_CHAT) {
            body.addView(row(getString(R.string.PengramPreviewChatName), null, () -> showPage(CHAT)));
        } else if (nativeSettingsId == PengramConfig.SETTINGS_ITEM_ACCOUNT) {
            body.addView(row(getString(R.string.PengramTabProfile), null, () -> showPage(PROFILE)));
        }
    }

    private void renderAppearance() {
        if (PengramMD3.settingsScreen()) {
            body.setBackgroundColor(PengramMD3.surface(null, .035f));
        }
        body.addView(row(getString(R.string.PengramMD3) + (PengramMD3.isEnabled() ? " ✓" : " ○"),
                getString(R.string.PengramMD3Subtitle), () -> select(MD3, 0)));
        body.addView(row(getString(R.string.PengramConstructorDialogs),
                getString(R.string.PengramConstructorDialogsInfo), () -> showPage(DIALOGS)));
        body.addView(row(getString(R.string.PengramEditorTitle), null, () -> select(TITLE, 0)));
        body.addView(row(getString(R.string.PengramFont), null, () -> select(MD3, 0)));
        body.addView(row(getString(R.string.PengramMD3Sheets), null, () -> showPage(SHEET)));
        body.addView(row(getString(R.string.PengramMD3Alerts), null, () -> showPage(ALERT)));
        body.addView(row(getString(R.string.PengramConstructorPlayer), null, () -> showPage(PLAYER)));
        body.addView(row(getString(R.string.PengramConstructorChatButtons), null, () -> showPage(CHAT_BUTTONS)));
        // The same one-screen editor can browse the actual Pengram setting lists;
        // only controls with implemented context actions can be edited in the mock.
        body.addView(row(getString(R.string.PengramEditorBrowseSettings), null,
                () -> openSettingsSection(PengramSettingsActivity.SECTION_ROOT)));
    }

    private void renderSettingsList() {
        body.addView(row(getString(R.string.PengramEditorSettingsListHint), null,
                () -> select(PREVIEW_ONLY, 0)));
        if (settingsSection == PengramSettingsActivity.SECTION_ROOT) {
            final int[] sections = {
                    PengramSettingsActivity.SECTION_GENERAL, PengramSettingsActivity.SECTION_APPEARANCE,
                    PengramSettingsActivity.SECTION_CUSTOM, PengramSettingsActivity.SECTION_GHOST,
                    PengramSettingsActivity.SECTION_HISTORY, PengramSettingsActivity.SECTION_PROFILE,
                    PengramSettingsActivity.SECTION_CHATS, PengramSettingsActivity.SECTION_AI,
                    PengramSettingsActivity.SECTION_PLAYER, PengramSettingsActivity.SECTION_MEDIA,
                    PengramSettingsActivity.SECTION_FREEDOM, PengramSettingsActivity.SECTION_LYRICS,
                    PengramSettingsActivity.SECTION_ABOUT
            };
            for (int section : sections) {
                body.addView(row(PengramSettingsActivity.sectionTitle(section).toString(), null,
                        () -> openSettingsSection(section)));
            }
            return;
        }
        if (settingsSection == PengramSettingsActivity.SECTION_CHATS) {
            final int[] children = {
                    PengramSettingsActivity.SECTION_CHAT_INTERFACE, PengramSettingsActivity.SECTION_CHAT_MESSAGES,
                    PengramSettingsActivity.SECTION_CHAT_MENUS, PengramSettingsActivity.SECTION_CHAT_ACTIONS,
                    PengramSettingsActivity.SECTION_TYPING, PengramSettingsActivity.SECTION_QUOTES
            };
            for (int child : children) {
                body.addView(row(PengramSettingsActivity.sectionTitle(child).toString(), null,
                        () -> openSettingsSection(child)));
            }
        }
        int previousHeader = -1;
        for (int i = 0; i < PengramSearchIndex.ITEMS.length; i++) {
            final int[] entry = PengramSearchIndex.ITEMS[i];
            if (entry[1] != settingsSection) continue;
            final int group = entry.length > 2 ? entry[2] : 0;
            if (group != previousHeader && group != 0) {
                TextView header = text(getString(group), 13, color(Theme.key_windowBackgroundWhiteGrayText2));
                header.setPadding(dp(18), dp(6), dp(12), 0);
                body.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(35)));
            }
            previousHeader = group;
            final int index = i;
            body.addView(row(getString(entry[0]), null, () -> select(SETTINGS_OPTION, index)));
        }
    }

    private void renderDemoOverlay(boolean md3) {
        TextView backdrop = row(getString(R.string.PengramEditorSampleOnly), null, () -> select(MD3, 0));
        body.addView(backdrop);
        TextView demo = row(getString(page == SHEET ? R.string.PengramMD3Sheets : R.string.PengramMD3Alerts),
                getString(R.string.PengramMD3Subtitle), () -> select(MD3, 0));
        demo.setGravity(Gravity.CENTER);
        demo.setBackground(Theme.createRoundRectDrawable(dp(md3 ? 28 : 12),
                md3 ? PengramMD3.surface(null, .12f) : color(Theme.key_windowBackgroundWhite)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        params.setMargins(dp(16), dp(60), dp(16), dp(12));
        body.addView(demo, params);
    }

    private void renderMenu() {
        if (menuMode == 1) {
            renderChatButtons();
        } else {
            final int[] order = PengramConfig.getMenuOrder();
            sectionHeader(R.string.PengramPlaceVisible, MENU_ROW, 0);
            for (int id : order) if (!PengramConfig.isMenuItemHidden(id)) addMenuItem(id, false);
            sectionHeader(R.string.PengramEditorHiddenSection, MENU_ROW, 2);
            for (int id : order) if (PengramConfig.isMenuItemHidden(id)) addMenuItem(id, true);
        }
    }

    private void addMenuItem(int id, boolean hidden) {
        TextView item = row(getString(PengramConfig.getMenuItemTitle(id)), null,
                () -> { if (hidden) select(MENU_ROW, id); else openMenuItem(id); });
        item.setAlpha(hidden ? .42f : 1f);
        body.addView(item);
        enableSafeDrag(item, MENU_ROW, id);
    }

    private void openMenuItem(int id) {
        switch (id) {
            case PengramConfig.MENU_ITEM_PENGRAM: showPage(APPEARANCE); break;
            case PengramConfig.MENU_ITEM_SETTINGS: showPage(SETTINGS); break;
            case PengramConfig.MENU_ITEM_CONTACTS: showPage(CONTACTS); break;
            case PengramConfig.MENU_ITEM_CALLS: showPage(CALLS); break;
            case PengramConfig.MENU_ITEM_PROFILE: showPage(PROFILE); break;
            case PengramConfig.MENU_ITEM_SAVED: openChat(2); break;
            default: select(MENU_ROW, id); break;
        }
    }

    private void renderChatButtons() {
        final ArrayList<Integer> order = PengramConfig.getChatItemsOrder();
        sectionHeader(R.string.PengramPlaceVisible, CHAT_BUTTON_ROW, 0);
        for (int id : order) if (!PengramConfig.isChatItemHidden(id)
                && PengramConfig.getChatItemPlacement(id) == PengramConfig.CHAT_PLACE_MAIN) addChatButtonItem(id, false);
        sectionHeader(R.string.PengramPlaceIsland, CHAT_BUTTON_ROW, 1);
        for (int id : order) if (!PengramConfig.isChatItemHidden(id)
                && PengramConfig.getChatItemPlacement(id) == PengramConfig.CHAT_PLACE_ISLAND) addChatButtonItem(id, false);
        sectionHeader(R.string.PengramEditorHiddenSection, CHAT_BUTTON_ROW, 2);
        for (int id : order) if (PengramConfig.isChatItemHidden(id)) addChatButtonItem(id, true);
    }

    private void addChatButtonItem(int id, boolean hidden) {
        TextView item = row(getString(PengramConfig.getChatItemTitle(id)), null, () -> select(CHAT_BUTTON_ROW, id));
        item.setAlpha(hidden ? .42f : 1f);
        body.addView(item);
        enableSafeDrag(item, CHAT_BUTTON_ROW, id);
    }

    private void renderPlayer() {
        body.addView(row(getString(R.string.PengramEditorSampleTrack),
                getString(R.string.PengramEditorSampleArtwork), () -> select(PLAYER_CARD, 0)));
        PengramPlayerMockView player = new PengramPlayerMockView(getContext(), PengramConfig.getPlayerStyle());
        player.setAccent(color(Theme.key_featuredStickers_addButton));
        player.setOnClickListener(v -> select(PLAYER_CARD, 0));
        body.addView(player, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        body.addView(row(getString(R.string.PengramConstructorPlayer), getString(R.string.PengramEditorSampleOnly),
                () -> select(PLAYER_CARD, 0)));
    }

    private void renderChatComposer() {
        footer.setPadding(dp(8), dp(5), dp(8), dp(5));
        footer.setBackgroundColor(color(Theme.key_windowBackgroundWhite));
        final EditText draft = new EditText(getContext());
        draft.setTextSize(14);
        draft.setSingleLine(true);
        draft.setHint(R.string.PengramEditorInput);
        draft.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        draft.setHintTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        draft.setImeOptions(EditorInfo.IME_ACTION_SEND);
        draft.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        draft.setText(sampleDrafts[sampleChatIndex] == null ? "" : sampleDrafts[sampleChatIndex]);
        draft.setPadding(dp(14), 0, dp(14), 0);
        draft.setBackground(Theme.createRoundRectDrawable(dp(20),
                ColorUtils.blendARGB(color(Theme.key_windowBackgroundWhite),
                        color(Theme.key_windowBackgroundWhiteBlackText), .06f)));
        footer.addView(draft, new LinearLayout.LayoutParams(0, dp(44), 1f));
        final int chatIndex = sampleChatIndex;
        draft.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                sampleDrafts[chatIndex] = s.toString();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        draft.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEND) return false;
            sendSampleMessage(chatIndex, draft);
            return true;
        });
        final TextView send = button("➤", () -> sendSampleMessage(chatIndex, draft));
        send.setTextColor(color(Theme.key_featuredStickers_buttonText));
        send.setContentDescription(getString(R.string.PengramEditorSendPreview));
        send.setBackground(Theme.createRoundRectDrawable(dp(24),
                color(Theme.key_featuredStickers_addButton)));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        sendParams.leftMargin = dp(7);
        footer.addView(send, sendParams);
    }

    private void sendSampleMessage(int index, EditText draft) {
        final String message = draft.getText().toString().trim();
        if (message.isEmpty()) return;
        int end = Math.min(300, message.length());
        if (end < message.length() && Character.isHighSurrogate(message.charAt(end - 1))) end--;
        sampleSent[index] = message.substring(0, end);
        sampleDrafts[index] = "";
        draft.setText("");
        if (page == CHAT && sampleChatIndex == index) chatMock.setSampleMessage(sampleSent[index]);
    }

    private void renderTabs() {
        footer.setPadding(0, 0, 0, 0);
        final boolean calls = UserConfig.getInstance(UserConfig.selectedAccount).showCallsTab;
        final int[] pages = {DIALOGS, CONTACTS, calls ? CALLS : SETTINGS, PROFILE};
        final int[] titles = {R.string.PengramTabChats, R.string.PengramTabContacts,
                calls ? R.string.PengramTabCalls : R.string.PengramTabSettings, R.string.PengramTabProfile};
        final int[] icons = {R.drawable.msg_discussion, R.drawable.msg_contacts,
                calls ? R.drawable.msg_calls : R.drawable.msg_settings, R.drawable.msg_openprofile};
        final String[] keys = {null, PengramConfig.KEY_TAB_CONTACTS,
                calls ? PengramConfig.KEY_TAB_CALLS : PengramConfig.KEY_TAB_SETTINGS, PengramConfig.KEY_TAB_PROFILE};
        footer.setBackground(Theme.createRoundRectDrawable(dp(25), color(Theme.key_windowBackgroundWhite)));
        for (int i = 0; i < pages.length; i++) {
            final int index = i;
            final LinearLayout tab = new LinearLayout(getContext());
            tab.setOrientation(VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setContentDescription(getString(titles[i]));
            tab.setAlpha(keys[i] != null && PengramConfig.getBool(keys[i], false) ? .38f : 1f);
            final int tint = page == pages[i] ? color(Theme.key_featuredStickers_addButton)
                    : color(Theme.key_windowBackgroundWhiteGrayText2);
            final ImageView icon = new ImageView(getContext());
            icon.setImageResource(icons[i]);
            icon.setColorFilter(tint);
            tab.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            final TextView label = text(getString(titles[i]), 10, tint);
            label.setGravity(Gravity.CENTER);
            tab.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)));
            tab.setOnClickListener(v -> switchTab(pages[index]));
            tab.setOnLongClickListener(v -> { select(TAB, index); return true; });
            footer.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void enableSafeDrag(View item, int kind, int id) {
        item.setOnLongClickListener(v -> {
            select(kind, id);
            final ClipData data = ClipData.newPlainText("pengram-editor", "");
            final View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
            final int[] localState = new int[]{kind, id};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return v.startDragAndDrop(data, shadow, localState, 0);
            }
            // minSdk is 21: startDragAndDrop was added only in API 24.
            return v.startDrag(data, shadow, localState, 0);
        });
        item.setOnDragListener((v, event) -> {
            final Object local = event.getLocalState();
            if (!(local instanceof int[])) return false;
            int[] source = (int[]) local;
            if (source[0] != kind) return false;
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return true;
            if (event.getAction() == DragEvent.ACTION_DRAG_ENTERED) { v.setScaleX(.96f); return true; }
            if (event.getAction() == DragEvent.ACTION_DRAG_EXITED || event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                v.setScaleX(1f); return true;
            }
            if (event.getAction() == DragEvent.ACTION_DROP) {
                v.setScaleX(1f);
                if (source[1] != id) {
                    final int targetSection = kind == SETTINGS_ROW
                            ? (PengramConfig.isSettingsItemHidden(id) ? 2 : 0)
                            : kind == MENU_ROW ? (PengramConfig.isMenuItemHidden(id) ? 2 : 0)
                            : PengramConfig.isChatItemHidden(id) ? 2
                            : PengramConfig.getChatItemPlacement(id) == PengramConfig.CHAT_PLACE_ISLAND ? 1 : 0;
                    if (targetSection == 2 && ((kind == SETTINGS_ROW && source[1] == PengramConfig.SETTINGS_ITEM_PENGRAM)
                            || (kind == MENU_ROW && (source[1] == PengramConfig.MENU_ITEM_PENGRAM
                            || source[1] == PengramConfig.MENU_ITEM_SETTINGS)))) return true;
                    final boolean moved = move(kind, source[1], id);
                    final boolean sectionChanged = setItemSection(kind, source[1], targetSection);
                    if (moved || sectionChanged) {
                        post(() -> { if (isAttachedToWindow()) render(); });
                        if (listener != null) listener.onChanged();
                    }
                }
                return true;
            }
            return true;
        });
    }

    private boolean move(int kind, int from, int to) {
        ArrayList<Integer> order = new ArrayList<>();
        if (kind == SETTINGS_ROW) order.addAll(PengramConfig.getSettingsOrder());
        else if (kind == CHAT_BUTTON_ROW) order.addAll(PengramConfig.getChatItemsOrder());
        else for (int id : PengramConfig.getMenuOrder()) order.add(id);
        if (!order.remove(Integer.valueOf(from))) return false;
        final int target = order.indexOf(to);
        if (target < 0) return false;
        order.add(target, from);
        if (kind == SETTINGS_ROW) PengramConfig.setSettingsOrder(order);
        else if (kind == CHAT_BUTTON_ROW) PengramConfig.setChatItemsOrder(order);
        else PengramConfig.setMenuOrder(order);
        return true;
    }
}
