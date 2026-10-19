package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.Context;
import android.content.res.Resources;
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

import org.telegram.messenger.FileLog;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramMD3;
import org.telegram.messenger.PengramFonts;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PengramSearchIndex;
import org.telegram.ui.PengramSearchMetadata;
import org.telegram.ui.PengramSettingsActivity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/** A self-contained, sample-data navigation surface. No real dialogs or users are read here. */
public class PengramInteractivePreviewView extends LinearLayout {
    public static final int DIALOGS = 0, CHAT = 1, CONTACTS = 2, SETTINGS = 3,
            CALLS = 4, PROFILE = 5, PLAYER = 6, SEARCH = 7, MENU = 8, CHAT_BUTTONS = 9,
            APPEARANCE = 10, SHEET = 11, ALERT = 12, NATIVE_SETTINGS = 13,
            NEW_MESSAGE = 14, STORY = 15, SETTINGS_LIST = 16, SETTINGS_SEARCH = 17;
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
    private final ArrayDeque<Integer> settingsSectionHistory = new ArrayDeque<>();
    private int selectedElement = -1, selectedId = -1;
    private View selectedRowView;
    private android.graphics.drawable.Drawable selectedRowBackground;
    private int menuMode;
    private int nativeSettingsId = PengramConfig.SETTINGS_ITEM_CHAT;
    private int settingsSection = PengramSettingsActivity.SECTION_APPEARANCE;
    private int sampleChatIndex;
    private String searchQuery = "";
    private String settingsSearchQuery = "";
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
        addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(Math.max(54, Math.min(90, Math.round(46 * fontScale() + 8))))));
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
        addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(Math.max(60, Math.min(108, Math.round(52 * fontScale() + 8))))));
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
        settingsSectionHistory.clear();
        settingsSectionHistory.addAll(previous.settingsSectionHistory);
        sampleChatIndex = previous.sampleChatIndex;
        searchQuery = previous.searchQuery;
        settingsSearchQuery = previous.settingsSearchQuery;
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
        if (page == SETTINGS_LIST && !settingsSectionHistory.isEmpty()) {
            settingsSection = settingsSectionHistory.removeLast();
            displayPage(SETTINGS_LIST);
            return true;
        }
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
        if (page == SETTINGS_LIST) {
            if (settingsSection == section) return;
            settingsSectionHistory.addLast(settingsSection);
            settingsSection = section;
            // showPage() intentionally ignores same-page navigation, but here
            // each section is a separate view within the ONE preview screen.
            displayPage(SETTINGS_LIST);
        } else {
            settingsSectionHistory.clear();
            settingsSection = section;
            showPage(SETTINGS_LIST);
        }
    }

    private void switchTab(int newPage) {
        history.clear();
        settingsSectionHistory.clear();
        displayPage(newPage);
    }

    private void displayPage(int newPage) {
        clearSelectedRow();
        page = newPage;
        selectedElement = selectedId = -1;
        if (listener != null) listener.onSelection(-1, -1);
        scroller.scrollTo(0, 0);
        render();
    }

    private void select(int element, int id) {
        clearSelectedRow();
        selectedElement = element;
        selectedId = id;
        if (listener != null) listener.onSelection(element, id);
    }

    private <T extends View> T tagRow(T row, int kind, int id) {
        row.setTag(new int[]{kind, id});
        return row;
    }

    private void restoreSelectedRow() {
        if (selectedElement < 0) return;
        for (int i = 0; i < body.getChildCount(); i++) {
            View row = body.getChildAt(i);
            Object tag = row.getTag();
            if (tag instanceof int[] && ((int[]) tag).length >= 2
                    && ((int[]) tag)[0] == selectedElement && ((int[]) tag)[1] == selectedId) {
                highlightSelectedRow(row);
                return;
            }
        }
    }

    private void clearSelectedRow() {
        if (selectedRowView != null) selectedRowView.setBackground(selectedRowBackground);
        selectedRowView = null;
        selectedRowBackground = null;
    }

    private void highlightSelectedRow(View row) {
        if (row == null || selectedElement < 0 || !row.isAttachedToWindow()) return;
        clearSelectedRow();
        selectedRowView = row;
        selectedRowBackground = row.getBackground();
        row.setBackground(Theme.createRoundRectDrawable(dp(12),
                ColorUtils.blendARGB(color(Theme.key_windowBackgroundWhite),
                        color(Theme.key_featuredStickers_addButton), .22f)));
    }

    private int color(int key) {
        return Theme.getColor(key);
    }

    /** Resolve the hint to text before passing it to TextView; an invalid ID must
     * never terminate the editor's test-only navigation. */
    public static void setSafeHint(TextView view, int stringId, String fallback) {
        try {
            Resources resources = view.getResources();
            if (!"string".equals(resources.getResourceTypeName(stringId))) {
                throw new Resources.NotFoundException("Not a string resource: " + stringId);
            }
            view.setHint(resources.getText(stringId));
        } catch (Resources.NotFoundException error) {
            FileLog.e(new IllegalStateException("Pengram editor hint 0x" + Integer.toHexString(stringId), error));
            view.setHint(fallback);
        }
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
        back.setContentDescription(getString(page == DIALOGS ? R.string.AccDescrOpenMenu : R.string.Back));
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
            case SETTINGS_SEARCH: title = getString(R.string.PengramEditorSettingsSearch); break;
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
            } else if (page == SETTINGS || page == SETTINGS_LIST || page == APPEARANCE
                    || page == NATIVE_SETTINGS || page == SETTINGS_SEARCH) {
                showPage(SETTINGS_SEARCH);
            } else showPage(SEARCH);
        });
        more.setContentDescription(getString(page == DIALOGS || page == CHAT
                ? R.string.AccDescrMoreOptions : R.string.Search));
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
        clearSelectedRow();
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
                body.addView(chatMock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(dp(420), getResources().getDisplayMetrics().heightPixels - dp(170))));
                final TextView editButtons = row(getString(R.string.PengramConstructorChatButtons), null, () -> showPage(CHAT_BUTTONS));
                body.addView(editButtons);
                break;
            case SETTINGS: renderSettings(); break;
            case APPEARANCE: renderAppearance(); break;
            case SHEET: renderDemoOverlay(PengramMD3.sheets()); break;
            case ALERT: renderDemoOverlay(PengramMD3.alerts()); break;
            case NATIVE_SETTINGS: renderNativeSettings(); break;
            case SETTINGS_LIST: renderSettingsList(); break;
            case SETTINGS_SEARCH: renderSettingsSearch(); break;
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
        restoreSelectedRow();
    }

    private float fontScale() {
        return Math.max(1f, getResources().getConfiguration().fontScale);
    }

    private int rowHeight(boolean subtitle) {
        int minimum = subtitle ? 62 : 51;
        return dp(Math.max(minimum, Math.min(144,
                Math.round((subtitle ? 48 : 39) * fontScale() + 12))));
    }

    private TextView row(String label, String sub, Runnable click) {
        final TextView cell = text(sub == null ? label : label + "\n" + sub, sub == null ? 15 : 14,
                color(Theme.key_windowBackgroundWhiteBlackText));
        cell.setSingleLine(false);
        cell.setMaxLines(2);
        cell.setPadding(dp(18), dp(6), dp(14), dp(6));
        cell.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
        cell.setOnClickListener(v -> {
            final int beforePage = page;
            click.run();
            if (beforePage == page) highlightSelectedRow(v);
        });
        cell.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight(sub != null)));
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
        setSafeHint(search, R.string.Search, "Поиск / Search");
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
            final String sampleName = getString(SAMPLE_CHAT_TITLES[i]);
            final String initial = sampleName.isEmpty() ? "•"
                    : sampleName.substring(0, sampleName.offsetByCodePoints(0, 1));
            TextView avatar = button(i == 2 ? "★" : initial,
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
            if (i == 0 && PengramConfig.isDialogSenderAvatars()) {
                // A tiny synthetic sender marker on the sample group dialog.
                // Keep it strictly visual: no contact photo or user is loaded.
                final int placement = PengramConfig.getDialogSenderAvatarPosition();
                final String senderName = getString(R.string.PengramEditorName);
                final String senderInitial = senderName.isEmpty() ? "•"
                        : senderName.substring(0, senderName.offsetByCodePoints(0, 1));
                final TextView sender = button(senderInitial, () -> select(DIALOG_ROW, index));
                sender.setTextSize(10);
                sender.setContentDescription(getString(R.string.PengramSenderAvatars));
                sender.setTextColor(color(Theme.key_featuredStickers_buttonText));
                sender.setBackground(Theme.createRoundRectDrawable(dp(10),
                        ColorUtils.blendARGB(accent, surface, .25f)));
                FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(19), dp(19));
                badge.leftMargin = dp(placement == PengramConfig.SENDER_AVATAR_AFTER_NAME ? 120
                        : placement == PengramConfig.SENDER_AVATAR_BEFORE_NAME
                        || placement == PengramConfig.SENDER_AVATAR_INLINE ? 64 : 42);
                badge.topMargin = dp(placement == PengramConfig.SENDER_AVATAR_BOTTOM ? 45
                        : placement == PengramConfig.SENDER_AVATAR_INLINE ? 38 : 5);
                item.addView(sender, badge);
            }
            if (PengramMD3.tonalRows() && i < 2) {
                item.setBackground(Theme.createRoundRectDrawable(dp(22),
                        i == 0 ? PengramMD3.unreadContainer(null) : PengramMD3.pinnedContainer(null)));
            }
            item.setOnClickListener(v -> openChat(index));
            item.setOnLongClickListener(v -> { select(page == CONTACTS ? PREVIEW_ONLY : DIALOG_ROW, index); return true; });
            summary.setOnLongClickListener(v -> { select(page == CONTACTS ? PREVIEW_ONLY : DIALOG_ROW, index); return true; });
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(68), rowHeight(true) + dp(6)));
            itemParams.setMargins(dp(8), dp(2), dp(8), dp(2));
            tagRow(item, page == CONTACTS ? PREVIEW_ONLY : DIALOG_ROW, index);
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
            if (!(state instanceof int[]) || ((int[]) state).length < 2
                    || ((int[]) state)[0] != kind) return false;
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

    private TextView settingsRow(String label, Runnable click) {
        TextView item = row(label, null, click);
        if (PengramMD3.settingsScreen()) {
            item.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16),
                    PengramMD3.surface(null, .09f),
                    ColorUtils.blendARGB(PengramMD3.surface(null, .09f),
                            color(Theme.key_featuredStickers_addButton), .18f)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight(false));
            params.setMargins(dp(12), dp(3), dp(12), dp(3));
            item.setLayoutParams(params);
        }
        return item;
    }

    private void renderSettings() {
        if (PengramMD3.settingsScreen()) body.setBackgroundColor(PengramMD3.surface(null, .035f));
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
        TextView item = settingsRow("⚙   " + getString(PengramConfig.getSettingsItemTitle(id)),
                () -> { if (hidden) select(SETTINGS_ROW, id);
                        else if (id == PengramConfig.SETTINGS_ITEM_PENGRAM) showPage(APPEARANCE);
                        else openNativeSettings(id); });
        item.setAlpha(hidden ? .42f : 1f);
        tagRow(item, SETTINGS_ROW, id);
        body.addView(item);
        if (id == PengramConfig.SETTINGS_ITEM_PENGRAM && PengramConfig.isPengramCardOnTop() && !hidden) {
            item.setOnLongClickListener(v -> {
                select(SETTINGS_ROW, id);
                highlightSelectedRow(v);
                return true;
            });
        } else {
            enableSafeDrag(item, SETTINGS_ROW, id);
        }
    }

    private void renderNativeSettings() {
        if (PengramMD3.settingsScreen()) body.setBackgroundColor(PengramMD3.surface(null, .035f));
        body.addView(settingsRow(getString(R.string.PengramEditorNativeSettingsSample),
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
            body.addView(tagRow(settingsRow(getString(label),
                    () -> select(PREVIEW_ONLY, label)), PREVIEW_ONLY, label));
        }
        if (nativeSettingsId == PengramConfig.SETTINGS_ITEM_CHAT) {
            body.addView(settingsRow(getString(R.string.PengramPreviewChatName), () -> showPage(CHAT)));
        } else if (nativeSettingsId == PengramConfig.SETTINGS_ITEM_ACCOUNT) {
            body.addView(settingsRow(getString(R.string.PengramTabProfile), () -> showPage(PROFILE)));
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

    private void renderSettingsSearch() {
        final EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setTextSize(15);
        setSafeHint(input, R.string.PengramEditorSettingsSearch, "Search settings");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        input.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        input.setPadding(dp(16), dp(5), dp(16), dp(5));
        input.setBackground(Theme.createRoundRectDrawable(dp(20),
                ColorUtils.blendARGB(color(Theme.key_windowBackgroundWhite),
                        color(Theme.key_windowBackgroundWhiteBlackText), .06f)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.setMargins(dp(12), dp(8), dp(12), dp(8));
        body.addView(input, params);
        TextView hint = text(getString(R.string.PengramEditorSettingsSearchHint), 13,
                color(Theme.key_windowBackgroundWhiteGrayText2));
        hint.setSingleLine(false);
        hint.setPadding(dp(16), dp(5), dp(16), dp(8));
        body.addView(hint);
        LinearLayout results = new LinearLayout(getContext());
        results.setOrientation(VERTICAL);
        body.addView(results);
        input.setText(settingsSearchQuery);
        input.setSelection(input.length());
        renderSettingsSearchResults(results, settingsSearchQuery);
        final Runnable[] pendingSearch = {null};
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                settingsSearchQuery = s.toString();
                if (pendingSearch[0] != null) results.removeCallbacks(pendingSearch[0]);
                results.removeAllViews();
                final String requested = settingsSearchQuery;
                pendingSearch[0] = () -> {
                    if (page == SETTINGS_SEARCH && results.getParent() == body
                            && requested.equals(settingsSearchQuery)) {
                        renderSettingsSearchResults(results, requested);
                    }
                };
                results.postDelayed(pendingSearch[0], 90);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private static final int SETTINGS_RESULT_BATCH = 60;

    private void renderSettingsSearchResults(LinearLayout results, String query) {
        renderSettingsSearchResults(results, query, 0);
    }

    private void renderSettingsSearchResults(LinearLayout results, String query, int offset) {
        String[] terms = PengramSearchMetadata.queryTerms(query);
        if (terms.length == 0) return;
        int count = 0;
        for (int i = 0; i < PengramSearchIndex.ITEMS.length; i++) {
            final int index = i;
            final int[] entry = PengramSearchIndex.ITEMS[i];
            if (!PengramSearchMetadata.matches(entry, terms)) continue;
            count++;
            // A short query can match hundreds of rows. Render small batches to
            // avoid blocking input and allow the user to request the rest.
            if (count <= offset || count > offset + SETTINGS_RESULT_BATCH) continue;
            TextView item = text("", 14, color(Theme.key_windowBackgroundWhiteBlackText));
            item.setText(PengramSearchMetadata.emphasizedPath(entry, query));
            item.setSingleLine(false);
            item.setEllipsize(null);
            item.setMaxLines(Integer.MAX_VALUE);
            item.setMinHeight(dp(58));
            item.setPadding(dp(16), dp(9), dp(16), dp(9));
            item.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
            item.setOnClickListener(v -> {
                settingsSectionHistory.clear();
                settingsSection = entry[1];
                showPage(SETTINGS_LIST);
                select(SETTINGS_OPTION, index);
                for (int rowIndex = 0; rowIndex < body.getChildCount(); rowIndex++) {
                    View row = body.getChildAt(rowIndex);
                    Object tag = row.getTag();
                    if (tag instanceof int[] && ((int[]) tag).length >= 2
                            && ((int[]) tag)[0] == SETTINGS_OPTION && ((int[]) tag)[1] == index) {
                        final View selectedRow = row;
                        scroller.post(() -> {
                            if (page == SETTINGS_LIST && selectedRow.getParent() == body) {
                                scroller.smoothScrollTo(0, Math.max(0, selectedRow.getTop() - dp(16)));
                                highlightSelectedRow(selectedRow);
                            }
                        });
                        break;
                    }
                }
            });
            results.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        if (count == 0) {
            TextView empty = text(getString(R.string.PengramEditorSettingsSearchEmpty), 14,
                    color(Theme.key_windowBackgroundWhiteGrayText2));
            empty.setPadding(dp(16), dp(18), dp(16), dp(18));
            results.addView(empty);
        } else if (count > offset + SETTINGS_RESULT_BATCH) {
            TextView more = row(getString(R.string.PengramEditorMoreResults), null, () -> {
                // The query may have changed while an older button was visible.
                if (page == SETTINGS_SEARCH && results.getParent() == body
                        && query.equals(settingsSearchQuery)) {
                    results.removeViewAt(results.getChildCount() - 1);
                    renderSettingsSearchResults(results, query, offset + SETTINGS_RESULT_BATCH);
                }
            });
            results.addView(more);
        }
    }

    private void renderSettingsList() {
        if (PengramMD3.settingsScreen()) body.setBackgroundColor(PengramMD3.surface(null, .035f));
        body.addView(settingsRow(getString(R.string.PengramEditorSettingsSearch),
                () -> showPage(SETTINGS_SEARCH)));
        body.addView(settingsRow(getString(R.string.PengramEditorSettingsListHint),
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
                String label = PengramSettingsActivity.sectionTitle(section).toString();
                TextView sectionRow = settingsRow(label, () -> openSettingsSection(section));
                // The category itself is indexed: make the search destination
                // focusable without replacing its normal navigation action.
                for (int i = 0; i < PengramSearchIndex.ITEMS.length; i++) {
                    int[] entry = PengramSearchIndex.ITEMS[i];
                    if (entry[1] == PengramSettingsActivity.SECTION_ROOT
                            && label.equals(getString(entry[0]))) {
                        tagRow(sectionRow, SETTINGS_OPTION, i);
                        break;
                    }
                }
                body.addView(sectionRow);
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
                body.addView(settingsRow(PengramSettingsActivity.sectionTitle(child).toString(),
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
            body.addView(tagRow(settingsRow(getString(entry[0]),
                    () -> select(SETTINGS_OPTION, index)), SETTINGS_OPTION, index));
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
        tagRow(item, MENU_ROW, id);
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
        tagRow(item, CHAT_BUTTON_ROW, id);
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
        setSafeHint(draft, R.string.PengramEditorInput, "Сообщение / Message");
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
            tab.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(Math.max(19, Math.round(19 * fontScale())))));
            tab.setOnClickListener(v -> switchTab(pages[index]));
            tab.setOnLongClickListener(v -> { select(TAB, index); return true; });
            footer.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void enableSafeDrag(View item, int kind, int id) {
        item.setOnLongClickListener(v -> {
            select(kind, id);
            highlightSelectedRow(v);
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
            if (!(local instanceof int[]) || ((int[]) local).length < 2) return false;
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
