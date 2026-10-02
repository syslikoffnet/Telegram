package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PengramScreenMockView;
import org.telegram.ui.Components.SeekBarView;

/**
 * Pengram: «Экран» — живой редактор интерфейса.
 * Это не список галочек, а сам Telegram в миниатюре: нажал на элемент — выбрал,
 * зажал и потащил — переставил, внизу появились его настройки.
 */
public class PengramLayoutEditorActivity extends BaseFragment {

    private PengramScreenMockView mock;
    private LinearLayout panel;
    private TextView panelTitle;
    private TextView panelHint;
    private LinearLayout actionsRow;
    private LinearLayout sliderBox;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PengramEditor));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        mock = new PengramScreenMockView(context);
        mock.setListener(new PengramScreenMockView.Listener() {
            @Override
            public void onSelection(PengramScreenMockView.Element element) {
                updatePanel(element);
            }

            @Override
            public void onChanged() {
                updatePanel(mock.getSelected());
                applyEverywhere();
                mock.refresh();
            }
        });
        root.addView(mock, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.TOP, 8, 8, 8, 196));

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
        root.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

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
        panel.addView(actionsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        sliderBox = new LinearLayout(context);
        sliderBox.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sliderBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        updatePanel(null);

        fragmentView = root;
        return fragmentView;
    }

    // ------------------------------------------------------------- панель

    private void updatePanel(PengramScreenMockView.Element element) {
        if (panel == null) {
            return;
        }
        actionsRow.removeAllViews();
        sliderBox.removeAllViews();

        if (element == null) {
            panelTitle.setText(getString(R.string.PengramEditorPick));
            panelHint.setText(getString(R.string.PengramEditorPickInfo));
            return;
        }
        panelTitle.setText(getString(element.titleRes));
        panelHint.setText(getString(hintFor(element.id)));

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
                addChip(getString(R.string.PengramChatMenuTop), PengramConfig.chatMenuEnabled && PengramConfig.chatMenuPosition == PengramConfig.MENU_POS_TOP, () -> {
                    if (!PengramConfig.chatMenuEnabled) {
                        PengramConfig.toggleChatMenu();
                    }
                    PengramConfig.setChatMenuPosition(PengramConfig.MENU_POS_TOP);
                    afterChange();
                });
                addChip(getString(R.string.PengramChatMenuBottom), PengramConfig.chatMenuEnabled && PengramConfig.chatMenuPosition == PengramConfig.MENU_POS_BOTTOM, () -> {
                    if (!PengramConfig.chatMenuEnabled) {
                        PengramConfig.toggleChatMenu();
                    }
                    PengramConfig.setChatMenuPosition(PengramConfig.MENU_POS_BOTTOM);
                    afterChange();
                });
                addChip(getString(R.string.PengramEditorHide), !PengramConfig.chatMenuEnabled, () -> {
                    if (PengramConfig.chatMenuEnabled) {
                        PengramConfig.toggleChatMenu();
                    }
                    afterChange();
                });
                addChip(getString(R.string.PengramEditorOpenList), false,
                        () -> presentFragment(new PengramMenuItemsActivity(PengramMenuItemsActivity.MODE_CHAT)));
                break;
            }
            case PengramScreenMockView.EL_TITLE: {
                addChip("Pengram", PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_PENGRAM,
                        () -> setTitleMode(PengramConfig.TITLE_MODE_PENGRAM));
                addChip(getString(R.string.PengramTitleChats), PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_CHATS,
                        () -> setTitleMode(PengramConfig.TITLE_MODE_CHATS));
                addChip("@username", PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_USERNAME,
                        () -> setTitleMode(PengramConfig.TITLE_MODE_USERNAME));
                addChip("Telegram", PengramConfig.getTitleMode() == PengramConfig.TITLE_MODE_DEFAULT,
                        () -> setTitleMode(PengramConfig.TITLE_MODE_DEFAULT));
                break;
            }
            case PengramScreenMockView.EL_MARK: {
                addChip(getString(R.string.PengramMarkTrash), PengramConfig.getDeletedMark() == PengramConfig.MARK_TRASH,
                        () -> setMark(PengramConfig.MARK_TRASH));
                addChip(getString(R.string.PengramMarkCross), PengramConfig.getDeletedMark() == PengramConfig.MARK_CROSS,
                        () -> setMark(PengramConfig.MARK_CROSS));
                addChip(getString(R.string.PengramMarkFire), PengramConfig.getDeletedMark() == PengramConfig.MARK_FIRE,
                        () -> setMark(PengramConfig.MARK_FIRE));
                addChip(getString(R.string.PengramMarkNone), PengramConfig.getDeletedMark() == PengramConfig.MARK_NONE,
                        () -> setMark(PengramConfig.MARK_NONE));
                break;
            }
            case PengramScreenMockView.EL_BUBBLE: {
                addSlider(getString(R.string.PengramEditorRadius), 0, 17, SharedConfig.bubbleRadius, value -> {
                    applyBubbleRadius(value);
                    if (mock != null) {
                        mock.refresh();
                    }
                });
                addSlider(getString(R.string.PengramEditorTextSize), 12, 30, SharedConfig.fontSize, value -> {
                    applyFontSize(value);
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
        updatePanel(mock == null ? null : mock.getSelected());
        applyEverywhere();
    }

    /** все изменения применяются к живому приложению сразу */
    private void applyEverywhere() {
        try {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            if (getParentLayout() != null) {
                for (BaseFragment fragment : getParentLayout().getFragmentStack()) {
                    if (fragment instanceof MainTabsActivity) {
                        ((MainTabsActivity) fragment).checkPengramTabsVisibility();
                    }
                    if (fragment instanceof DialogsActivity) {
                        ((DialogsActivity) fragment).pengramUpdateTitle();
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private void applyBubbleRadius(int radius) {
        try {
            SharedConfig.bubbleRadius = radius;
            org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", radius).apply();
            Theme.createCommonMessageResources();
        } catch (Throwable ignore) {
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
        } catch (Throwable ignore) {
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
        actionsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 6, 0));
    }

    private void addSlider(CharSequence label, int min, int max, int value, org.telegram.messenger.Utilities.Callback<Integer> callback) {
        final Context context = getContext();
        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        title.setText(label + ": " + value);
        sliderBox.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        final SeekBarView seekBar = new SeekBarView(context);
        seekBar.setReportChanges(true);
        seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                final int result = Math.round(min + progress * (max - min));
                title.setText(label + ": " + result);
                callback.run(result);
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
        if (mock != null) {
            mock.refresh();
        }
        updatePanel(mock == null ? null : mock.getSelected());
    }
}
