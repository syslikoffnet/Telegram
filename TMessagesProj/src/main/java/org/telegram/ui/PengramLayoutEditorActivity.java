package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PengramMD3;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.PengramScreenMockView;
import org.telegram.ui.Components.PengramInteractivePreviewView;
import org.telegram.ui.Components.SeekBarView;

/**
 * Pengram: sample-data chat preview plus shortcuts to the existing section editors.
 * Dragging is limited to supported chat slots; no real messages are loaded here.
 */
public class PengramLayoutEditorActivity extends BaseFragment {

    private PengramScreenMockView mock;
    private PengramInteractivePreviewView preview;
    private LinearLayout panel;
    private ScrollView panelScroll;
    private int panelToken = Integer.MIN_VALUE;
    private TextView panelTitle;
    private TextView panelHint;
    private LinearLayout actionsRow;
    private LinearLayout sliderBox;
    private View panelScrim;
    private PengramConfig.VisualDraft visualDraft;
    private int draftRadius = -1, draftFontSize = -1;
    private static final int ACTION_SAVE = 702, ACTION_PREVIEW_MENU = 705;

    @Override
    public View createView(Context context) {
        if (visualDraft == null) {
            visualDraft = PengramConfig.beginVisualDraft();
            draftRadius = SharedConfig.bubbleRadius;
            draftFontSize = SharedConfig.fontSize;
        }
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PengramEditor));
        actionBar.setTitleActionRunnable(() -> {
            if (preview != null) preview.selectTitle();
        });
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (panelScroll != null && panelScroll.getVisibility() == View.VISIBLE) hidePanel();
                    else if (preview == null || !preview.goBack()) finishFragment();
                } else if (id == ACTION_SAVE) {
                    saveVisualDraft();
                } else if (id == ACTION_PREVIEW_MENU && preview != null) {
                    openPreviewMenu();
                }
            }
        });
        actionBar.createMenu().addItem(ACTION_PREVIEW_MENU, R.drawable.ic_ab_other)
                .setContentDescription(getString(R.string.AccDescrMoreOptions));
        actionBar.createMenu().addItem(ACTION_SAVE, getString(R.string.Save));

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        final PengramInteractivePreviewView previousPreview = preview;
        mock = new PengramScreenMockView(context);
        mock.setEditorMessageSize(draftRadius, draftFontSize);
        mock.setListener(new PengramScreenMockView.Listener() {
            @Override
            public void onSelection(PengramScreenMockView.Element element) {
                if (preview != null) updatePanel(element);
            }

            @Override
            public void onChanged() {
                updatePanel(mock.getSelected());
                mock.refresh();
            }
        });
        preview = new PengramInteractivePreviewView(context, mock);
        preview.restoreFrom(previousPreview);
        preview.setListener(new PengramInteractivePreviewView.Listener() {
            @Override
            public void onPageChanged(String title, String subtitle) {
                actionBar.setTitle(title);
                actionBar.setSubtitle(subtitle);
            }

            @Override
            public void onSelection(int element, int id) {
                if (element < 0) updateInspectPanel(-1, -1);
                else updateInspectPanel(element, id);
            }

            @Override
            public void onChanged() {
                mock.refresh();
            }
        });
        // The sample Telegram occupies the entire editor surface. Controls are
        // an on-demand overlay; they never squeeze the chat into half a screen.
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        panelScrim = new View(context);
        panelScrim.setBackgroundColor(0x66000000);
        panelScrim.setOnClickListener(v -> hidePanel());
        panelScrim.setVisibility(View.GONE);
        root.addView(panelScrim, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(context) {
            private final RectF rect = new RectF();
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight() + dp(20));
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                paint.setShadowLayer(dp(8), 0, -dp(1), 0x22000000);
                canvas.drawRoundRect(rect, dp(18), dp(18), paint);
            }
        };
        panel.setWillNotDraw(false);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(14));
        panelScroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int available = MeasureSpec.getSize(heightMeasureSpec);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                        Math.min(dp(520), available * 3 / 4), MeasureSpec.AT_MOST));
            }
        };
        panelScroll.setFillViewport(false);
        panelScroll.addView(panel, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(panelScroll, sheetParams);
        panelScroll.setVisibility(View.GONE);

        TextView closeSheet = new TextView(context);
        closeSheet.setText("×");
        closeSheet.setContentDescription(getString(R.string.Close));
        closeSheet.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        closeSheet.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        closeSheet.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        closeSheet.setOnClickListener(v -> hidePanel());
        panel.addView(closeSheet, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 34));

        panelTitle = new TextView(context);
        panelTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        panelTitle.setTypeface(AndroidUtilities.bold());
        panelTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        panel.addView(panelTitle);

        panelHint = new TextView(context);
        panelHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        panelHint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        panel.addView(panelHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        final HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.addView(actionsRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        panel.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        sliderBox = new LinearLayout(context);
        sliderBox.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sliderBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        panelToken = Integer.MIN_VALUE;
        updateInspectPanel(-1, -1);
        hidePanel();

        fragmentView = root;
        return fragmentView;
    }

    /** The sample's overflow uses Telegram's own popup, not a fake full-page menu.
     * Organising hidden rows stays on the preview-only editor screen. */
    private void openPreviewMenu() {
        if (preview == null || actionBar == null) return;
        if (preview.getPage() != PengramInteractivePreviewView.DIALOGS
                && preview.getPage() != PengramInteractivePreviewView.CHAT) {
            preview.openToolbarAction();
            return;
        }
        ItemOptions options = ItemOptions.makeOptions(this, actionBar);
        if (preview.getPage() == PengramInteractivePreviewView.DIALOGS) {
            for (int id : PengramConfig.getMenuOrder()) {
                if (PengramConfig.isMenuItemHidden(id)) continue;
                final int itemId = id;
                options.add(PengramConfig.getMenuItemIcon(id),
                        getString(PengramConfig.getMenuItemTitle(id)),
                        () -> preview.openSampleMenuItem(itemId));
            }
        } else {
            for (int id : PengramConfig.getChatItemsOrder()) {
                if (PengramConfig.isChatItemHidden(id)) continue;
                final int itemId = id;
                options.add(PengramConfig.getChatItemIcon(id),
                        getString(PengramConfig.getChatItemTitle(id)),
                        () -> preview.selectSampleChatItem(itemId));
            }
        }
        options.add(R.drawable.msg_edit, getString(R.string.PengramEditorAdjust), () -> {
            updateInspectPanel(-1, -1);
            showPanel();
        });
        options.add(R.drawable.msg_settings, getString(R.string.PengramEditorOrganizeSections),
                () -> preview.openToolbarAction());
        options.show();
    }

    private void showPanel() {
        if (panelScrim != null) panelScrim.setVisibility(View.VISIBLE);
        if (panelScroll != null) panelScroll.setVisibility(View.VISIBLE);
    }

    private void hidePanel() {
        if (panelScroll != null) panelScroll.setVisibility(View.GONE);
        if (panelScrim != null) panelScrim.setVisibility(View.GONE);
    }

    private void saveVisualDraft() {
        if (visualDraft == null || getParentActivity() == null) return;
        new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PengramEditorSaveConfirmTitle))
                .setMessage(getString(R.string.PengramEditorSaveConfirmInfo))
                .setPositiveButton(getString(R.string.Save), (dialog, which) -> commitVisualDraft())
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void commitVisualDraft() {
        if (visualDraft == null) return;
        try {
            if (!visualDraft.save()) {
                showSaveError();
                return;
            }
        } catch (Exception e) {
            FileLog.e(e);
            showSaveError();
            return;
        }
        visualDraft = null;
        if (draftRadius != SharedConfig.bubbleRadius) applyBubbleRadius(draftRadius);
        if (draftFontSize != SharedConfig.fontSize) applyFontSize(draftFontSize);
        org.telegram.messenger.PengramMonet.update(getContext());
        applyEverywhere();
        finishFragment();
    }

    private void showSaveError() {
        String message;
        try {
            message = getString(R.string.PengramEditorSaveFailed);
        } catch (Resources.NotFoundException e) {
            FileLog.e(e);
            message = "Could not save the layout";
        }
        if (getContext() != null) Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onFragmentDestroy() {
        if (visualDraft != null) {
            visualDraft.discard();
            visualDraft = null;
            // Any screen behind the full-screen editor may have drawn a draft
            // value while the editor was open; restore its original appearance.
            applyEverywhere();
        }
        super.onFragmentDestroy();
    }

    // ------------------------------------------------------------- панель

    private void updateInspectPanel(int element, int id) {
        if (panel == null) return;
        resetPanelScrollIfChanged((preview == null ? 0 : preview.getPage()) * 100000
                + (element == PengramInteractivePreviewView.TITLE
                ? 1000 + PengramScreenMockView.EL_TITLE : element * 1000 + id));
        actionsRow.removeAllViews();
        sliderBox.removeAllViews();
        if (element < 0) {
            hidePanel();
            setPanelText(panelTitle, R.string.PengramEditorPick);
            setPanelText(panelHint, R.string.PengramEditorNavigationHint);
            addAppearanceChips();
            return;
        }
        showPanel();
        switch (element) {
            case PengramInteractivePreviewView.TITLE:
                updatePanel(mock.elementById(PengramScreenMockView.EL_TITLE));
                return;
            case PengramInteractivePreviewView.SEARCH_BAR:
                setPanelText(panelTitle, R.string.Search);
                setPanelText(panelHint, R.string.PengramEditorSampleOnly);
                addToggle(R.string.PengramMD3, PengramMD3.KEY_ENABLED, false);
                if (PengramMD3.isEnabled()) {
                    addToggle(R.string.PengramMD3SearchBar, PengramMD3.KEY_SEARCH_BAR, true);
                    if (PengramConfig.getBool(PengramMD3.KEY_SEARCH_BAR, true)) {
                        addToggle(R.string.PengramMD3SearchShadow, PengramMD3.KEY_SEARCH_SHADOW, false);
                    }
                }
                return;
            case PengramInteractivePreviewView.DIALOG_ROW:
                setPanelText(panelTitle, R.string.PengramConstructorDialogs);
                setPanelText(panelHint, R.string.PengramEditorRowInfo);
                addToggle(R.string.PengramMD3, PengramMD3.KEY_ENABLED, false);
                if (PengramMD3.isEnabled()) {
                    addToggle(R.string.PengramMD3Dialogs, PengramMD3.KEY_DIALOGS, true);
                    if (PengramConfig.getBool(PengramMD3.KEY_DIALOGS, true)) {
                        addToggle(R.string.PengramMD3Tonal, PengramMD3.KEY_TONAL_UNREAD, true);
                        addToggle(R.string.PengramMD3NoDividers, PengramMD3.KEY_NO_DIVIDERS, true);
                    }
                }
                for (int shape = 0; shape < PengramConfig.DIALOG_AVATAR_COUNT; shape++) {
                    final int choice = shape;
                    addChip(getString(PengramConfig.getDialogAvatarShapeName(shape)),
                            PengramConfig.getDialogAvatarShape() == shape, () -> {
                                PengramConfig.setDialogAvatarShape(choice);
                                afterChange();
                            });
                }
                addToggle(R.string.PengramSenderAvatars, PengramConfig.KEY_DIALOG_SENDER_AVATARS, false);
                if (PengramConfig.isDialogSenderAvatars()) addSenderAvatarPositions();
                return;
            case PengramInteractivePreviewView.FAB:
                setPanelText(panelTitle, R.string.PengramEditorFab);
                setPanelText(panelHint, R.string.PengramEditorSafeInfo);
                addToggle(R.string.PengramMD3, PengramMD3.KEY_ENABLED, false);
                if (PengramMD3.isEnabled()) addToggle(R.string.PengramMD3Fab, PengramMD3.KEY_FAB, true);
                addToggle(R.string.PengramHideWriteButton, PengramConfig.KEY_HIDE_WRITE_BUTTON, false);
                return;
            case PengramInteractivePreviewView.STORIES:
                setPanelText(panelTitle, R.string.PengramEditorStories);
                setPanelText(panelHint, R.string.PengramEditorSafeInfo);
                addToggle(R.string.PengramHideStories, PengramConfig.KEY_HIDE_STORIES, false);
                return;
            case PengramInteractivePreviewView.TAB:
                setPanelText(panelTitle, R.string.PengramEditorTabs);
                setPanelText(panelHint, R.string.PengramEditorSafeInfo);
                if (id > 0) {
                    final boolean calls = org.telegram.messenger.UserConfig.getInstance(
                            org.telegram.messenger.UserConfig.selectedAccount).showCallsTab;
                    final String key = id == 1 ? PengramConfig.KEY_TAB_CONTACTS : id == 2
                            ? (calls ? PengramConfig.KEY_TAB_CALLS : PengramConfig.KEY_TAB_SETTINGS)
                            : PengramConfig.KEY_TAB_PROFILE;
                    addChip(getString(PengramConfig.getBool(key, false) ? R.string.PengramEditorShow : R.string.PengramEditorHide),
                            PengramConfig.getBool(key, false), () -> {
                                PengramConfig.setBool(key, !PengramConfig.getBool(key, false));
                                afterChange();
                            });
                }
                addChip(getString(R.string.PengramEditorAllTabs), false, () -> {
                    PengramConfig.setBool(PengramConfig.KEY_TAB_CONTACTS, false);
                    PengramConfig.setBool(PengramConfig.KEY_TAB_CALLS, false);
                    PengramConfig.setBool(PengramConfig.KEY_TAB_SETTINGS, false);
                    PengramConfig.setBool(PengramConfig.KEY_TAB_PROFILE, false);
                    afterChange();
                });
                return;
            case PengramInteractivePreviewView.SETTINGS_ROW:
                setPanelText(panelTitle, PengramConfig.getSettingsItemTitle(id));
                setPanelText(panelHint, R.string.PengramEditorReorderHint);
                if (id != PengramConfig.SETTINGS_ITEM_PENGRAM || PengramConfig.isSettingsItemHidden(id)) {
                    addChip(getString(PengramConfig.isSettingsItemHidden(id) ? R.string.PengramEditorShow : R.string.PengramEditorHide),
                            false, () -> {
                                PengramConfig.setSettingsItemHidden(id, !PengramConfig.isSettingsItemHidden(id));
                                afterChange();
                            });
                }
                if (!(id == PengramConfig.SETTINGS_ITEM_PENGRAM && PengramConfig.isPengramCardOnTop()
                        && !PengramConfig.isSettingsItemHidden(id))) {
                    addOrderControls(PengramInteractivePreviewView.SETTINGS_ROW, id);
                }
                return;
            case PengramInteractivePreviewView.MENU_ROW:
                setPanelText(panelTitle, PengramConfig.getMenuItemTitle(id));
                setPanelText(panelHint, R.string.PengramEditorReorderHint);
                if ((id != PengramConfig.MENU_ITEM_PENGRAM && id != PengramConfig.MENU_ITEM_SETTINGS)
                        || PengramConfig.isMenuItemHidden(id)) {
                    addChip(getString(PengramConfig.isMenuItemHidden(id) ? R.string.PengramEditorShow : R.string.PengramEditorHide), false, () -> {
                        PengramConfig.setMenuItemHidden(id, !PengramConfig.isMenuItemHidden(id));
                        afterChange();
                    });
                }
                addOrderControls(PengramInteractivePreviewView.MENU_ROW, id);
                return;
            case PengramInteractivePreviewView.CHAT_BUTTON_ROW:
                setPanelText(panelTitle, PengramConfig.getChatItemTitle(id));
                setPanelText(panelHint, R.string.PengramEditorReorderHint);
                addChip(getString(PengramConfig.isChatItemHidden(id) ? R.string.PengramEditorShow : R.string.PengramEditorHide), false, () -> {
                    PengramConfig.setChatItemHidden(id, !PengramConfig.isChatItemHidden(id));
                    afterChange();
                });
                for (int place : new int[]{PengramConfig.CHAT_PLACE_MAIN, PengramConfig.CHAT_PLACE_ISLAND}) {
                    final int choice = place;
                    addChip(getString(place == PengramConfig.CHAT_PLACE_MAIN ? R.string.PengramPlaceVisible : R.string.PengramPlaceIsland),
                            PengramConfig.getChatItemPlacement(id) == place, () -> {
                                PengramConfig.setChatItemPlacement(id, choice);
                                afterChange();
                            });
                }
                addOrderControls(PengramInteractivePreviewView.CHAT_BUTTON_ROW, id);
                return;
            case PengramInteractivePreviewView.SETTINGS_OPTION:
                if (id < 0 || id >= PengramSearchIndex.ITEMS.length) return;
                final int[] setting = PengramSearchIndex.ITEMS[id];
                setPanelText(panelTitle, setting[0]);
                panelHint.setText(PengramSearchMetadata.emphasizedPath(setting, ""));
                // Opening the real settings page here would bypass Save.
                int beforeControls = actionsRow.getChildCount() + sliderBox.getChildCount();
                addListedSettingControls(setting[0]);
                if (beforeControls == actionsRow.getChildCount() + sliderBox.getChildCount()) {
                    panelHint.append("\n" + getString(R.string.PengramEditorBrowseOnly));
                }
                return;
            case PengramInteractivePreviewView.PREVIEW_ONLY:
                // A native mock row is informational, not an invitation to
                // change an unrelated global font or account preference.
                if (preview != null && preview.getPage() == PengramInteractivePreviewView.NATIVE_SETTINGS && id != 0) {
                    setPanelText(panelTitle, id);
                } else {
                    setPanelText(panelTitle, R.string.PengramEditorSampleOnly);
                }
                setPanelText(panelHint, R.string.PengramEditorSafePreviewHint);
                return;
            case PengramInteractivePreviewView.PLAYER_CARD:
                setPanelText(panelTitle, R.string.PengramConstructorPlayer);
                setPanelText(panelHint, R.string.PengramEditorSampleOnly);
                final LinearLayout styles = addOptionsGroup(R.string.PengramPlayerLook);
                for (int style = 0; style < PengramConfig.PLAYER_STYLE_COUNT; style++) {
                    final int choice = style;
                    addChip(styles, getString(PengramConfig.getPlayerStyleName(style)), PengramConfig.getPlayerStyle() == style, () -> {
                        PengramConfig.setPlayerStyle(choice);
                        afterChange();
                    });
                }
                if (PengramConfig.isNewPlayer()) {
                    final LinearLayout backgrounds = addOptionsGroup(R.string.PengramPlayerBg);
                    for (int bg = PengramConfig.PLAYER_BG_COVER; bg <= PengramConfig.PLAYER_BG_THEME; bg++) {
                        final int choice = bg;
                        addChip(backgrounds, getString(PengramConfig.getPlayerBgName(bg)), PengramConfig.getPlayerBg() == bg, () -> {
                            PengramConfig.setPlayerBg(choice);
                            afterChange();
                        });
                    }
                    final LinearLayout shapes = addOptionsGroup(R.string.PengramCoverShape);
                    for (int shape = PengramConfig.COVER_SHAPE_ROUNDED; shape <= PengramConfig.COVER_SHAPE_SQUARE; shape++) {
                        final int choice = shape;
                        addChip(shapes, getString(PengramConfig.getCoverShapeName(shape)), PengramConfig.getCoverShape() == shape, () -> {
                            PengramConfig.setCoverShape(choice);
                            afterChange();
                        });
                    }
                }
                return;
            default:
                setPanelText(panelTitle, R.string.PengramMD3);
                setPanelText(panelHint, R.string.PengramEditorSampleOnly);
                addAppearanceChips();
        }
    }

    /** Only preferences already demonstrated by this sample get inline controls.
     * Other rows retain a clearly labelled link to their actual setting. */
    private void addListedSettingControls(int title) {
        if (title == R.string.PengramTitleText || title == R.string.PengramTitleCustom) {
            addTitleControls(); return;
        }
        if (title == R.string.PengramTitleCenter) {
            addToggle(title, PengramConfig.KEY_TITLE_CENTER, false); return;
        }
        if (title == R.string.PengramDeletedMark) {
            addDeletedMarkControls(); return;
        }
        if (title == R.string.PengramChatMenu || title == R.string.PengramChatMenuTop
                || title == R.string.PengramChatMenuBottom) {
            addChatMenuControls(); return;
        }
        if (title == R.string.PengramMD3) {
            addToggle(title, PengramMD3.KEY_ENABLED, false); return;
        }
        if (title == R.string.PengramMD3Dialogs) {
            addMd3ListedOption(title, PengramMD3.KEY_DIALOGS, true, 0); return;
        }
        if (title == R.string.PengramMD3Tonal) {
            addMd3ListedOption(title, PengramMD3.KEY_TONAL_UNREAD, true, R.string.PengramMD3Dialogs); return;
        }
        if (title == R.string.PengramMD3NoDividers) {
            addMd3ListedOption(title, PengramMD3.KEY_NO_DIVIDERS, true, R.string.PengramMD3Dialogs); return;
        }
        if (title == R.string.PengramMD3SearchBar) {
            addMd3ListedOption(title, PengramMD3.KEY_SEARCH_BAR, true, 0); return;
        }
        if (title == R.string.PengramMD3SearchShadow) {
            addMd3ListedOption(title, PengramMD3.KEY_SEARCH_SHADOW, false, R.string.PengramMD3SearchBar); return;
        }
        if (title == R.string.PengramMD3Fab) {
            addMd3ListedOption(title, PengramMD3.KEY_FAB, true, 0); return;
        }
        if (title == R.string.PengramMD3Settings) {
            addMd3ListedOption(title, PengramMD3.KEY_SETTINGS, true, 0); return;
        }
        if (title == R.string.PengramMD3Sheets) {
            addMd3ListedOption(title, PengramMD3.KEY_SHEETS, true, 0); return;
        }
        if (title == R.string.PengramMD3Alerts) {
            addMd3ListedOption(title, PengramMD3.KEY_ALERTS, true, 0); return;
        }
        if (title == R.string.PengramHideWriteButton) {
            addToggle(title, PengramConfig.KEY_HIDE_WRITE_BUTTON, false); return;
        }
        if (title == R.string.PengramHideStories) {
            addToggle(title, PengramConfig.KEY_HIDE_STORIES, false); return;
        }
        if (title == R.string.PengramSenderAvatars) {
            addToggle(title, PengramConfig.KEY_DIALOG_SENDER_AVATARS, false);
            if (PengramConfig.isDialogSenderAvatars()) addSenderAvatarPositions();
            return;
        }
        if (title == R.string.PengramSenderAvatarPosition) {
            if (PengramConfig.isDialogSenderAvatars()) {
                addSenderAvatarPositions();
            } else {
                showParentRequired();
                addToggle(R.string.PengramSenderAvatars, PengramConfig.KEY_DIALOG_SENDER_AVATARS, false);
            }
            return;
        }
        if (title == R.string.PengramQuoteNames) {
            addToggle(title, org.telegram.ui.Components.PengramQuoteMaker.KEY_NAME, true); return;
        }
        if (title == R.string.PengramQuoteAvatar) {
            // The real control is conditional on names; do not change a
            // dormant preference without showing the parent first.
            if (PengramConfig.getBool(org.telegram.ui.Components.PengramQuoteMaker.KEY_NAME, true)) {
                addToggle(title, org.telegram.ui.Components.PengramQuoteMaker.KEY_AVATAR, true);
            } else {
                showParentRequired();
                addToggle(R.string.PengramQuoteNames,
                        org.telegram.ui.Components.PengramQuoteMaker.KEY_NAME, true);
            }
            return;
        }
        if (title == R.string.BubbleRadius) {
            addSlider(getString(title), 0, 17, draftRadius, value -> {
                draftRadius = value;
                if (mock != null) mock.setEditorMessageSize(draftRadius, draftFontSize);
                if (mock != null) mock.refresh();
            });
            return;
        }
        if (title == R.string.PengramFont) {
            addFontOptions(); return;
        }
        if (title == R.string.PengramMonet) {
            if (org.telegram.messenger.PengramMonet.isSupported()) {
                addToggle(title, org.telegram.messenger.PengramMonet.KEY_ENABLED, false);
            }
            return;
        }
        if (title == R.string.PengramPlayerLook) {
            for (int style = 0; style < PengramConfig.PLAYER_STYLE_COUNT; style++) {
                final int choice = style;
                addChip(getString(PengramConfig.getPlayerStyleName(style)),
                        PengramConfig.getPlayerStyle() == style, () -> {
                            PengramConfig.setPlayerStyle(choice);
                            afterChange();
                        });
            }
            return;
        }
        if (title == R.string.PengramPlayerBg) {
            if (PengramConfig.isNewPlayer()) {
                for (int bg = PengramConfig.PLAYER_BG_COVER; bg <= PengramConfig.PLAYER_BG_THEME; bg++) {
                    final int choice = bg;
                    addChip(getString(PengramConfig.getPlayerBgName(bg)),
                            PengramConfig.getPlayerBg() == bg, () -> {
                                PengramConfig.setPlayerBg(choice);
                                afterChange();
                            });
                }
            }
            return;
        }
        if (title == R.string.PengramCoverShape) {
            if (PengramConfig.isNewPlayer()) {
                for (int shape = PengramConfig.COVER_SHAPE_ROUNDED; shape <= PengramConfig.COVER_SHAPE_SQUARE; shape++) {
                    final int choice = shape;
                    addChip(getString(PengramConfig.getCoverShapeName(shape)),
                            PengramConfig.getCoverShape() == shape, () -> {
                                PengramConfig.setCoverShape(choice);
                                afterChange();
                            });
                }
            }
            return;
        }
        if (title == R.string.PengramChatLook || title == R.string.PengramAvatarPos) {
            for (int pos : PengramConfig.AVATAR_POS_ORDER) {
                addChip(getString(PengramConfig.getGroupAvatarPosName(pos)),
                        PengramConfig.getGroupAvatarPos() == pos, () -> {
                            PengramConfig.setGroupAvatarPos(pos);
                            afterChange();
                        });
            }
            return;
        }
        if (title == R.string.PengramDialogAvatars) {
            for (int shape = 0; shape < PengramConfig.DIALOG_AVATAR_COUNT; shape++) {
                final int choice = shape;
                addChip(getString(PengramConfig.getDialogAvatarShapeName(shape)),
                        PengramConfig.getDialogAvatarShape() == shape, () -> {
                            PengramConfig.setDialogAvatarShape(choice);
                            afterChange();
                        });
            }
            return;
        }

    }

    private void showParentRequired() {
        if (panelHint != null) panelHint.append("\n" + getString(R.string.PengramEditorEnableParentFirst));
    }

    private void addMd3ListedOption(int title, String key, boolean defaultValue, int prerequisite) {
        if (!PengramMD3.isEnabled()) {
            showParentRequired();
            addToggle(R.string.PengramMD3, PengramMD3.KEY_ENABLED, false);
        } else if (prerequisite == R.string.PengramMD3Dialogs
                && !PengramConfig.getBool(PengramMD3.KEY_DIALOGS, true)) {
            showParentRequired();
            addToggle(R.string.PengramMD3Dialogs, PengramMD3.KEY_DIALOGS, true);
        } else if (prerequisite == R.string.PengramMD3SearchBar
                && !PengramConfig.getBool(PengramMD3.KEY_SEARCH_BAR, true)) {
            showParentRequired();
            addToggle(R.string.PengramMD3SearchBar, PengramMD3.KEY_SEARCH_BAR, true);
        } else {
            addToggle(title, key, defaultValue);
        }
    }

    private void addSenderAvatarPositions() {
        final int[] names = {
                R.string.PengramSenderAvatarInline, R.string.PengramSenderAvatarBottom,
                R.string.PengramSenderAvatarTop, R.string.PengramSenderAvatarBeforeName,
                R.string.PengramSenderAvatarAfterName
        };
        final LinearLayout positions = addOptionsGroup(R.string.PengramSenderAvatarPosition);
        for (int position = 0; position < names.length; position++) {
            final int choice = position;
            addChip(positions, getString(names[position]),
                    PengramConfig.getDialogSenderAvatarPosition() == position, () -> {
                        PengramConfig.setDialogSenderAvatarPosition(choice);
                        afterChange();
                    });
        }
    }

    private void addOrderControls(int kind, int id) {
        addChip(getString(R.string.PengramEditorMoveUp), false, () -> moveItem(kind, id, -1));
        addChip(getString(R.string.PengramEditorMoveDown), false, () -> moveItem(kind, id, 1));
    }

    private void moveItem(int kind, int id, int direction) {
        final java.util.ArrayList<Integer> order = new java.util.ArrayList<>();
        if (kind == PengramInteractivePreviewView.SETTINGS_ROW) order.addAll(PengramConfig.getSettingsOrder());
        else if (kind == PengramInteractivePreviewView.CHAT_BUTTON_ROW) order.addAll(PengramConfig.getChatItemsOrder());
        else for (int menuId : PengramConfig.getMenuOrder()) order.add(menuId);
        final int origin = order.indexOf(id);
        if (origin < 0) return;
        final int section = itemSection(kind, id);
        for (int next = origin + direction; next >= 0 && next < order.size(); next += direction) {
            if (section == itemSection(kind, order.get(next))) {
                java.util.Collections.swap(order, origin, next);
                if (kind == PengramInteractivePreviewView.SETTINGS_ROW) PengramConfig.setSettingsOrder(order);
                else if (kind == PengramInteractivePreviewView.CHAT_BUTTON_ROW) PengramConfig.setChatItemsOrder(order);
                else PengramConfig.setMenuOrder(order);
                afterChange();
                return;
            }
        }
    }

    private int itemSection(int kind, int id) {
        if (kind == PengramInteractivePreviewView.SETTINGS_ROW) {
            return PengramConfig.isSettingsItemHidden(id) ? 2 : 0;
        }
        if (kind == PengramInteractivePreviewView.MENU_ROW) {
            return PengramConfig.isMenuItemHidden(id) ? 2 : 0;
        }
        if (PengramConfig.isChatItemHidden(id)) return 2;
        return PengramConfig.getChatItemPlacement(id) == PengramConfig.CHAT_PLACE_ISLAND ? 1 : 0;
    }

    private void addAppearanceChips() {
        addToggle(R.string.PengramMD3, PengramMD3.KEY_ENABLED, false);
        if (org.telegram.messenger.PengramMonet.isSupported()) {
            addToggle(R.string.PengramMonet, org.telegram.messenger.PengramMonet.KEY_ENABLED, false);
        }
        final LinearLayout md3 = addOptionsGroup(R.string.PengramMD3Header);
        addToggle(md3, R.string.PengramMD3Dialogs, PengramMD3.KEY_DIALOGS, true);
        addToggle(md3, R.string.PengramMD3Tonal, PengramMD3.KEY_TONAL_UNREAD, true);
        addToggle(md3, R.string.PengramMD3NoDividers, PengramMD3.KEY_NO_DIVIDERS, true);
        addToggle(md3, R.string.PengramMD3SearchBar, PengramMD3.KEY_SEARCH_BAR, true);
        addToggle(md3, R.string.PengramMD3SearchShadow, PengramMD3.KEY_SEARCH_SHADOW, false);
        addToggle(md3, R.string.PengramMD3Fab, PengramMD3.KEY_FAB, true);
        addToggle(md3, R.string.PengramMD3Settings, PengramMD3.KEY_SETTINGS, true);
        addToggle(md3, R.string.PengramMD3Sheets, PengramMD3.KEY_SHEETS, true);
        addToggle(md3, R.string.PengramMD3Alerts, PengramMD3.KEY_ALERTS, true);
        if (org.telegram.messenger.PengramMonet.isSupported()) {
            final LinearLayout monet = addOptionsGroup(R.string.PengramMonetHeader);
            addToggle(monet, R.string.PengramMonetMessages, org.telegram.messenger.PengramMonet.KEY_MESSAGES, true);
        }
        addFontOptions();
    }

    private void addFontOptions() {
        final LinearLayout fonts = addOptionsGroup(R.string.PengramFont);
        for (int font : org.telegram.messenger.PengramFonts.available()) {
            final int choice = font;
            addChip(fonts, getString(org.telegram.messenger.PengramFonts.nameRes(font)),
                    PengramConfig.getAppFont() == font, () -> {
                        PengramConfig.setAppFont(choice);
                        afterChange();
                    });
        }
    }

    private void resetPanelScrollIfChanged(int token) {
        if (panelToken != token) {
            panelToken = token;
            if (panelScroll != null) panelScroll.scrollTo(0, 0);
        }
    }

    /** A preview is optional UI; a stale or non-string resource ID must not crash
     * the whole app. The offending ID is logged without reading real chat data. */
    private void setPanelText(TextView target, int resId) {
        try {
            Resources resources = target.getResources();
            if (!"string".equals(resources.getResourceTypeName(resId))) {
                throw new Resources.NotFoundException("Not a string resource: " + resId);
            }
            target.setText(resources.getText(resId));
        } catch (Resources.NotFoundException error) {
            FileLog.e(new IllegalStateException("Pengram editor label 0x"
                    + Integer.toHexString(resId) + " on " + target.getClass().getSimpleName(), error));
            target.setText(target == panelHint ? "—" : "Pengram");
        }
    }

    private LinearLayout addOptionsGroup(int titleRes) {
        final TextView title = new TextView(getContext());
        setPanelText(title, titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        sliderBox.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 7, 0, 3));
        final HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        sliderBox.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return row;
    }

    private void addToggle(int label, String key, boolean defaultValue) {
        addToggle(actionsRow, label, key, defaultValue);
    }

    private void addToggle(LinearLayout target, int label, String key, boolean defaultValue) {
        final boolean enabled = PengramConfig.getBool(key, defaultValue);
        addChip(target, getString(label) + (enabled ? " ✓" : " ○"), enabled, () -> {
            PengramConfig.setBool(key, !PengramConfig.getBool(key, defaultValue));
            afterChange();
        });
    }

    private void updatePanel(PengramScreenMockView.Element element) {
        if (panel == null) return;
        if (element == null) hidePanel(); else showPanel();
        resetPanelScrollIfChanged((preview == null ? 0 : preview.getPage()) * 100000
                + 1000 + (element == null ? 0 : element.id));
        actionsRow.removeAllViews();
        sliderBox.removeAllViews();

        if (element == null) {
            panelTitle.setText(getString(R.string.PengramEditorPick));
            panelHint.setText(getString(R.string.PengramEditorPickInfo));
            return;
        }
        panelTitle.setText(getString(element.titleRes));
        panelHint.setText(getString(hintFor(element.id)));
        if (preview != null && preview.getPage() == PengramInteractivePreviewView.CHAT) {
            addChip(getString(preview.isPlacementMode() ? R.string.PengramEditorBackToChat
                    : R.string.PengramEditorPlacementMode), false, () -> {
                hidePanel();
                preview.togglePlacementMode();
            });
        }

        switch (element.id) {
            case PengramScreenMockView.EL_AVATAR: {
                for (int pos : PengramConfig.AVATAR_POS_ORDER) {
                    addChip(getString(PengramConfig.getGroupAvatarPosName(pos)),
                            PengramConfig.getGroupAvatarPos() == pos,
                            () -> {
                                PengramConfig.setGroupAvatarPos(pos);
                                afterChange();
                            });
                }
                break;
            }
            case PengramScreenMockView.EL_MENU: {
                addChatMenuControls();
                break;
            }
            case PengramScreenMockView.EL_TITLE: {
                addTitleControls();
                break;
            }
            case PengramScreenMockView.EL_MARK: {
                addDeletedMarkControls();
                break;
            }
            case PengramScreenMockView.EL_BUBBLE: {
                addSlider(getString(R.string.PengramEditorRadius), 0, 17, draftRadius, value -> {
                    draftRadius = value;
                    if (mock != null) mock.setEditorMessageSize(draftRadius, draftFontSize);
                    if (mock != null) {
                        mock.refresh();
                    }
                });
                addSlider(getString(R.string.PengramEditorTextSize), 12, 30, draftFontSize, value -> {
                    draftFontSize = value;
                    if (mock != null) mock.setEditorMessageSize(draftRadius, draftFontSize);
                    if (mock != null) {
                        mock.refresh();
                    }
                });
                break;
            }
            case PengramScreenMockView.EL_TABS: {
                panelHint.setText(getString(R.string.PengramTabsMockHint));
                addChip(getString(R.string.PengramEditorAllTabs), false, () -> {
                    PengramConfig.setBool(PengramConfig.KEY_TAB_CONTACTS, false);
                    PengramConfig.setBool(PengramConfig.KEY_TAB_CALLS, false);
                    PengramConfig.setBool(PengramConfig.KEY_TAB_SETTINGS, false);
                    PengramConfig.setBool(PengramConfig.KEY_TAB_PROFILE, false);
                    afterChange();
                });
                break;
            }
        }
    }

    private void addChatMenuControls() {
        addChip(getString(R.string.PengramChatMenuTop), PengramConfig.isChatMenuEnabled() && PengramConfig.getChatMenuPosition() == PengramConfig.MENU_POS_TOP, () -> {
            if (!PengramConfig.isChatMenuEnabled()) {
                PengramConfig.toggleChatMenu();
            }
            PengramConfig.setChatMenuPosition(PengramConfig.MENU_POS_TOP);
            afterChange();
        });
        addChip(getString(R.string.PengramChatMenuBottom), PengramConfig.isChatMenuEnabled() && PengramConfig.getChatMenuPosition() == PengramConfig.MENU_POS_BOTTOM, () -> {
            if (!PengramConfig.isChatMenuEnabled()) {
                PengramConfig.toggleChatMenu();
            }
            PengramConfig.setChatMenuPosition(PengramConfig.MENU_POS_BOTTOM);
            afterChange();
        });
        addChip(getString(R.string.PengramEditorHide), !PengramConfig.isChatMenuEnabled(), () -> {
            if (PengramConfig.isChatMenuEnabled()) {
                PengramConfig.toggleChatMenu();
            }
            afterChange();
        });
        addChip(getString(R.string.PengramEditorOpenList), false,
                () -> preview.showPage(PengramInteractivePreviewView.CHAT_BUTTONS));
    }

    private void addTitleControls() {
        addChip("Pengram", PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_PENGRAM,
                () -> setTitleMode(PengramConfig.TITLE_MODE_PENGRAM));
        addChip(getString(R.string.PengramTitleChats), PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_CHATS,
                () -> setTitleMode(PengramConfig.TITLE_MODE_CHATS));
        addChip(getString(R.string.PengramTitleModeName), PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_NAME,
                () -> setTitleMode(PengramConfig.TITLE_MODE_NAME));
        addChip(getString(R.string.PengramTitleModeUsername), PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_USERNAME,
                () -> setTitleMode(PengramConfig.TITLE_MODE_USERNAME));
        addChip(getString(R.string.PengramTitleModeDefault), PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_DEFAULT,
                () -> setTitleMode(PengramConfig.TITLE_MODE_DEFAULT));
        addChip(getString(R.string.PengramTitleCustom), PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_CUSTOM,
                () -> setTitleMode(PengramConfig.TITLE_MODE_CUSTOM));
        if (PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_CUSTOM) {
            final EditText customTitle = new EditText(getContext());
            customTitle.setSingleLine(true);
            customTitle.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
            customTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            PengramInteractivePreviewView.setSafeHint(customTitle, R.string.PengramTitleCustom, "Название / Title");
            customTitle.setText(PengramConfig.getTitleCustom());
            sliderBox.addView(customTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            addChip(getString(R.string.PengramEditorApplyDraft), false, () -> {
                PengramConfig.setTitleCustom(customTitle.getText().toString().trim());
                afterChange();
            });
        }
        addToggle(R.string.PengramTitleCenter, PengramConfig.KEY_TITLE_CENTER, false);
    }

    private void addDeletedMarkControls() {
        addChip(getString(R.string.PengramMarkTrash), PengramConfig.getDeletedMark() == PengramConfig.MARK_TRASH,
                () -> setMark(PengramConfig.MARK_TRASH));
        addChip(getString(R.string.PengramMarkCross), PengramConfig.getDeletedMark() == PengramConfig.MARK_CROSS,
                () -> setMark(PengramConfig.MARK_CROSS));
        addChip(getString(R.string.PengramMarkEye), PengramConfig.getDeletedMark() == PengramConfig.MARK_EYE,
                () -> setMark(PengramConfig.MARK_EYE));
        addChip(getString(R.string.PengramMarkFire), PengramConfig.getDeletedMark() == PengramConfig.MARK_FIRE,
                () -> setMark(PengramConfig.MARK_FIRE));
        addChip(getString(R.string.PengramMarkNone), PengramConfig.getDeletedMark() == PengramConfig.MARK_NONE,
                () -> setMark(PengramConfig.MARK_NONE));
    }

    private void setTitleMode(int mode) {
        PengramConfig.setTitleMode(mode);
        afterChange();
    }

    private void setMark(int mark) {
        PengramConfig.setDeletedMark(mark);
        afterChange();
    }

    private void afterChange() {
        if (mock != null) {
            mock.refresh();
        }
        if (preview != null) {
            preview.render();
            if (preview.getSelectedElement() >= 0) updateInspectPanel(preview.getSelectedElement(), preview.getSelectedId());
            else if (preview.getPage() == PengramInteractivePreviewView.CHAT) updatePanel(mock.getSelected());
            else updateInspectPanel(-1, -1);
        } else updatePanel(mock == null ? null : mock.getSelected());
    }

    /** Apply committed visual settings to all accounts only after Save. */
    private void applyEverywhere() {
        try {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            if (getParentLayout() != null) {
                for (BaseFragment fragment : getParentLayout().getFragmentStack()) {
                    if (fragment instanceof MainTabsActivity) {
                        // Reconcile the actual page stack as well as the buttons:
                        // hiding a tab must not leave a stale navigable fragment.
                        ((MainTabsActivity) fragment).checkPengramTabs();
                    }
                    if (fragment instanceof DialogsActivity) {
                        ((DialogsActivity) fragment).pengramUpdateTitle();
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void applyBubbleRadius(int radius) {
        try {
            SharedConfig.bubbleRadius = radius;
            org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", radius).apply();
            Theme.createCommonMessageResources();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void applyFontSize(int size) {
        try {
            SharedConfig.fontSize = size;
            SharedConfig.fontSizeIsDefault = false;
            org.telegram.messenger.ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                    .edit().putInt("fons_size", size).apply();
            Theme.createCommonMessageResources();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static int hintFor(int id) {
        switch (id) {
            case PengramScreenMockView.EL_AVATAR: return R.string.PengramEditorAvatarInfo;
            case PengramScreenMockView.EL_MENU: return R.string.PengramEditorMenuInfo;
            case PengramScreenMockView.EL_TITLE: return R.string.PengramEditorTitleInfo;
            case PengramScreenMockView.EL_MARK: return R.string.PengramEditorMarkInfo;
            case PengramScreenMockView.EL_BUBBLE: return R.string.PengramEditorBubbleInfo;
            default: return R.string.PengramEditorTabsInfo;
        }
    }

    // ------------------------------------------------------- мелкие детали

    private void addChip(CharSequence label, boolean active, Runnable action) {
        addChip(actionsRow, label, active, action);
    }

    private void addChip(LinearLayout target, CharSequence label, boolean active, Runnable action) {
        final Context context = getContext();
        final TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setText(label);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        chip.setTextColor(active ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16),
                active ? accent : ColorUtils.setAlphaComponent(accent, 28), ColorUtils.setAlphaComponent(accent, 60)));
        chip.setOnClickListener(v -> {
            v.animate().cancel();
            v.setScaleX(0.94f);
            v.setScaleY(0.94f);
            v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            action.run();
        });
        target.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 6, 0));
    }

    private void addSlider(CharSequence label, int min, int max, int value, org.telegram.messenger.Utilities.Callback<Integer> callback) {
        final Context context = getContext();
        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        title.setText(label + ": " + value);
        sliderBox.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        final SeekBarView seekBar = new SeekBarView(context);
        final int[] lastValue = {value};
        final boolean[] dirty = {false};
        seekBar.setReportChanges(true);
        seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                final int result = Math.round(min + progress * (max - min));
                if (lastValue[0] != result) {
                    lastValue[0] = result;
                    title.setText(label + ": " + result);
                    callback.run(result);
                    dirty[0] = true;
                }
                if (stop && dirty[0]) {
                    dirty[0] = false;
                }
            }

            @Override
            public void onSeekBarPressed(boolean pressed) {
            }
        });
        seekBar.setProgress((value - min) / (float) Math.max(1, max - min));
        sliderBox.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (preview != null) {
            preview.render();
            if (preview.getSelectedElement() >= 0) {
                updateInspectPanel(preview.getSelectedElement(), preview.getSelectedId());
            } else if (preview.getPage() == PengramInteractivePreviewView.CHAT) {
                updatePanel(mock == null ? null : mock.getSelected());
            } else {
                updateInspectPanel(-1, -1);
            }
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (panelScroll != null && panelScroll.getVisibility() == View.VISIBLE) {
            if (invoked) hidePanel();
            return false;
        }
        if (preview != null && preview.getPage() != PengramInteractivePreviewView.DIALOGS) {
            if (invoked) preview.goBack();
            return false;
        }
        return super.onBackPressed(invoked);
    }
}
