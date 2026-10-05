package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramBypass;
import org.telegram.messenger.PengramBypassSources;
import org.telegram.messenger.PengramNet;
import org.telegram.messenger.PengramNetTuner;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Pengram: экран обхода блокировок.
 *
 * Здесь ровно одна галочка и живой кружок состояния. Всё остальное спрятано
 * под строку «Для любопытных» — обычному человеку туда заходить не нужно.
 */
public class PengramBypassActivity extends BaseFragment {

    private StatusView statusView;
    private TextCheckCell switchCell;
    private LinearLayout advancedBlock;
    private TextSettingsCell advancedButton;
    private TextSettingsCell refreshCell;
    private final TextCheckCell[] routeCells = new TextCheckCell[4];
    private boolean advancedShown;

    // разрез пакетов: отдельный от туннеля слой, работает без всяких посредников
    private TextCheckCell desyncSwitch;
    private TextSettingsCell desyncProfile;
    private LinearLayout desyncCustom;
    private TextSettingsCell[] desyncValues;
    private TextCheckCell[] desyncFlags;
    private TextSettingsCell tuneCell;
    private TextSettingsCell ipCell;
    private CharSequence tuneState;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PengramBypass));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        statusView = new StatusView(context);
        root.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 132));

        switchCell = new TextCheckCell(context);
        switchCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        switchCell.setOnClickListener(v -> {
            final boolean value = !PengramBypass.isEnabled();
            switchCell.setChecked(value);
            PengramBypass.setEnabled(value);
            updateAll();
        });
        root.addView(switchCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell mainInfo = new TextInfoPrivacyCell(context);
        mainInfo.setText(LocaleController.getString(R.string.PengramBypassMainInfo));
        root.addView(mainInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        buildDesyncBlock(context, root);

        advancedButton = new TextSettingsCell(context);
        advancedButton.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        advancedButton.setText(LocaleController.getString(R.string.PengramBypassAdvanced), false);
        advancedButton.setOnClickListener(v -> {
            advancedShown = !advancedShown;
            advancedBlock.setVisibility(advancedShown ? View.VISIBLE : View.GONE);
            updateAll();
        });
        root.addView(advancedButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        advancedBlock = new LinearLayout(context);
        advancedBlock.setOrientation(LinearLayout.VERTICAL);
        advancedBlock.setVisibility(View.GONE);
        root.addView(advancedBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HeaderCell routeHeader = new HeaderCell(context);
        routeHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        routeHeader.setText(LocaleController.getString(R.string.PengramBypassRouteHeader));
        advancedBlock.addView(routeHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final int[] routeNames = new int[]{R.string.PengramBypassRouteAuto, R.string.PengramBypassModeWs,
                R.string.PengramBypassModeMt, R.string.PengramBypassRouteSplitLegacy};
        for (int route = 0; route < routeCells.length; route++) {
            final int selectedRoute = route;
            final TextCheckCell cell = new TextCheckCell(context);
            routeCells[route] = cell;
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setTextAndCheck(LocaleController.getString(routeNames[route]), PengramBypass.getPreferredRoute() == route,
                    route + 1 < routeCells.length);
            cell.setOnClickListener(v -> {
                PengramBypass.setPreferredRoute(selectedRoute);
                updateAll();
            });
            advancedBlock.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        final TextInfoPrivacyCell routeInfo = new TextInfoPrivacyCell(context);
        routeInfo.setText(LocaleController.getString(R.string.PengramBypassRouteInfo));
        advancedBlock.addView(routeInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HeaderCell howHeader = new HeaderCell(context);
        howHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        howHeader.setText(LocaleController.getString(R.string.PengramBypassHowHeader));
        advancedBlock.addView(howHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell how = new TextInfoPrivacyCell(context);
        how.setText(LocaleController.getString(R.string.PengramBypassHow));
        advancedBlock.addView(how, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextSettingsCell retry = new TextSettingsCell(context);
        retry.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        retry.setText(LocaleController.getString(R.string.PengramBypassRetry), true);
        retry.setOnClickListener(v -> {
            PengramBypass.retryNow();
            updateAll();
        });
        advancedBlock.addView(retry, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        refreshCell = new TextSettingsCell(context);
        refreshCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        refreshCell.setOnClickListener(v -> {
            if (PengramBypass.isBusy()) {
                return;
            }
            updateRefreshCell(true);
            PengramBypass.refreshNodes((alive, total) -> {
                updateAll();
                if (getParentActivity() != null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                            LocaleController.formatString(R.string.PengramBypassFound, alive, total)).show();
                }
            });
        });
        advancedBlock.addView(refreshCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextSettingsCell paste = new TextSettingsCell(context);
        paste.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        paste.setText(LocaleController.getString(R.string.PengramBypassPaste), false);
        paste.setOnClickListener(v -> showPasteDialog());
        advancedBlock.addView(paste, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell pasteInfo = new TextInfoPrivacyCell(context);
        pasteInfo.setText(LocaleController.getString(R.string.PengramBypassPasteInfo));
        advancedBlock.addView(pasteInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell limits = new TextInfoPrivacyCell(context);
        limits.setText(LocaleController.getString(R.string.PengramBypassLimits));
        advancedBlock.addView(limits, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        container.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = container;

        updateAll();
        return fragmentView;
    }

    @Override
    public boolean onFragmentCreate() {
        PengramBypass.setListener(this::updateAll);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        PengramBypass.setListener(null);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAll();
    }

    // ------------------------------------------------- разрез первых пакетов

    private static final int[] SPLIT_OPTIONS = {0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, -1, -8, -16};
    private static final int[] DELAY_OPTIONS = {0, 5, 10, 25, 40, 60, 100};
    private static final int[] FIRST_OPTIONS = {1, 2, 3, 4, 6, 8};
    private static final int[] TTL_OPTIONS = {1, 2, 3, 4, 5, 6, 8, 12};

    private void buildDesyncBlock(Context context, LinearLayout root) {
        final HeaderCell header = new HeaderCell(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setText(LocaleController.getString(R.string.PengramDesyncHeader));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        desyncSwitch = new TextCheckCell(context);
        desyncSwitch.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        desyncSwitch.setOnClickListener(v -> {
            final boolean value = !PengramNet.isEnabled();
            desyncSwitch.setChecked(value);
            PengramNet.setEnabled(value);
            updateAll();
        });
        root.addView(desyncSwitch, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        desyncProfile = new TextSettingsCell(context);
        desyncProfile.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        desyncProfile.setOnClickListener(v -> showProfilePicker());
        root.addView(desyncProfile, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        desyncCustom = new LinearLayout(context);
        desyncCustom.setOrientation(LinearLayout.VERTICAL);
        desyncCustom.setVisibility(View.GONE);
        root.addView(desyncCustom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        desyncValues = new TextSettingsCell[6];
        for (int a = 0; a < desyncValues.length; ++a) {
            final int index = a;
            final TextSettingsCell cell = new TextSettingsCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setOnClickListener(v -> showValuePicker(index));
            desyncValues[a] = cell;
            desyncCustom.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        final String[] flagKeys = {PengramNet.FLAG_RANDOM, PengramNet.FLAG_NODELAY,
                PengramNet.FLAG_OOB, PengramNet.FLAG_FAKE};
        desyncFlags = new TextCheckCell[flagKeys.length];
        for (int a = 0; a < flagKeys.length; ++a) {
            final String key = flagKeys[a];
            final TextCheckCell cell = new TextCheckCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setOnClickListener(v -> {
                PengramNet.toggleCustomFlag(key);
                updateAll();
            });
            desyncFlags[a] = cell;
            desyncCustom.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        tuneCell = new TextSettingsCell(context);
        tuneCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        tuneCell.setOnClickListener(v -> startTuning());
        root.addView(tuneCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ipCell = new TextSettingsCell(context);
        ipCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ipCell.setOnClickListener(v -> showIpPicker());
        root.addView(ipCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.PengramDesyncInfo));
        root.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    // ------------------------------------------------------- автоподбор

    private void startTuning() {
        if (PengramNetTuner.isRunning()) {
            PengramNetTuner.stop();
            return;
        }
        PengramNetTuner.start(new PengramNetTuner.Callback() {
            @Override
            public void onProgress(int profile, int index, int total) {
                tuneState = LocaleController.formatString(R.string.PengramDesyncTuneProgress,
                        String.valueOf(profileName(profile)), index + 1, total);
                updateDesync();
            }

            @Override
            public void onResult(PengramNetTuner.Result result) {
                updateDesync();
            }

            @Override
            public void onFinish(java.util.ArrayList<PengramNetTuner.Result> results, int best) {
                tuneState = null;
                updateDesync();
                showTuneResult(best);
            }
        });
        tuneState = LocaleController.getString(R.string.PengramDesyncTuneStart);
        updateDesync();
    }

    private void showTuneResult(int best) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.PengramDesyncTune));
        final StringBuilder sb = new StringBuilder();
        for (PengramNetTuner.Result result : PengramNetTuner.getLastResults()) {
            sb.append(result.ok() ? "✓ " : "✕ ").append(profileName(result.profile));
            if (result.ok()) {
                sb.append(" — ").append(result.ms).append(" ms");
            }
            sb.append('\n');
        }
        if (best < 0) {
            sb.append('\n').append(LocaleController.getString(R.string.PengramDesyncTuneNone));
            builder.setMessage(sb.toString().trim());
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        } else {
            sb.append('\n').append(LocaleController.formatString(R.string.PengramDesyncTuneBest,
                    String.valueOf(profileName(best))));
            builder.setMessage(sb.toString().trim());
            builder.setPositiveButton(LocaleController.getString(R.string.PengramBackupApply), (dialog, which) -> {
                PengramNetTuner.apply(best);
                updateAll();
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        }
        showDialog(builder.create());
    }

    private void showIpPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] names = {
                LocaleController.getString(R.string.PengramDesyncIpAuto),
                "IPv4", "IPv6",
                LocaleController.getString(R.string.PengramDesyncIpBoth)};
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.PengramDesyncIp));
        builder.setItems(names, (dialog, which) -> {
            PengramNet.setIpStrategy(which);
            updateAll();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private CharSequence ipName(int value) {
        switch (value) {
            case PengramNet.IP_V4: return "IPv4";
            case PengramNet.IP_V6: return "IPv6";
            case PengramNet.IP_BOTH: return LocaleController.getString(R.string.PengramDesyncIpBoth);
            default: return LocaleController.getString(R.string.PengramDesyncIpAuto);
        }
    }

    private CharSequence profileName(int profile) {
        switch (profile) {
            case PengramNet.PROFILE_SPLIT2: return LocaleController.getString(R.string.PengramDesyncSplit2);
            case PengramNet.PROFILE_SPLIT_MTPROTO: return LocaleController.getString(R.string.PengramDesyncSplitMt);
            case PengramNet.PROFILE_MULTISPLIT: return LocaleController.getString(R.string.PengramDesyncMulti);
            case PengramNet.PROFILE_PACED: return LocaleController.getString(R.string.PengramDesyncPaced);
            case PengramNet.PROFILE_MOBILE: return LocaleController.getString(R.string.PengramDesyncMobile);
            case PengramNet.PROFILE_HARD: return LocaleController.getString(R.string.PengramDesyncHard);
            case PengramNet.PROFILE_OOB: return LocaleController.getString(R.string.PengramDesyncOob);
            case PengramNet.PROFILE_FAKE: return LocaleController.getString(R.string.PengramDesyncFake);
            case PengramNet.PROFILE_CUSTOM: return LocaleController.getString(R.string.PengramDesyncCustom);
            default: return LocaleController.getString(R.string.PengramDesyncNone);
        }
    }

    private void showProfilePicker() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] names = new CharSequence[PengramNet.PROFILE_COUNT];
        for (int a = 0; a < names.length; ++a) {
            final PengramNetTuner.Result result = PengramNetTuner.resultOf(a);
            names[a] = result == null ? profileName(a)
                    : result.ok() ? profileName(a) + "  ✓ " + result.ms + " ms"
                    : profileName(a) + "  ✕";
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.PengramDesyncProfile));
        builder.setItems(names, (dialog, which) -> {
            PengramNet.setProfile(which);
            updateAll();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /** 0..2 — позиции разрезов, 3 — пауза, 4 — сколько пакетов, 5 — TTL фейка */
    private void showValuePicker(int index) {
        if (getParentActivity() == null) {
            return;
        }
        final int[] options = index <= 2 ? SPLIT_OPTIONS
                : index == 3 ? DELAY_OPTIONS
                : index == 4 ? FIRST_OPTIONS : TTL_OPTIONS;
        final CharSequence[] names = new CharSequence[options.length];
        for (int a = 0; a < options.length; ++a) {
            names[a] = index <= 2 && options[a] == 0
                    ? LocaleController.getString(R.string.PengramDesyncNoCut)
                    : index == 3 && options[a] == 0
                        ? LocaleController.getString(R.string.PengramDesyncNoPause)
                        : String.valueOf(options[a]);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(valueTitle(index));
        builder.setItems(names, (dialog, which) -> {
            final int value = options[which];
            if (index <= 2) {
                PengramNet.setCustomSplit(index, value);
            } else if (index == 3) {
                PengramNet.setCustomDelay(value);
            } else if (index == 4) {
                PengramNet.setCustomFirstPackets(value);
            } else {
                PengramNet.setCustomFakeTtl(value);
            }
            updateAll();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private CharSequence valueTitle(int index) {
        switch (index) {
            case 0: return LocaleController.getString(R.string.PengramDesyncCut1);
            case 1: return LocaleController.getString(R.string.PengramDesyncCut2);
            case 2: return LocaleController.getString(R.string.PengramDesyncCut3);
            case 3: return LocaleController.getString(R.string.PengramDesyncDelay);
            case 4: return LocaleController.getString(R.string.PengramDesyncPackets);
            default: return LocaleController.getString(R.string.PengramDesyncTtl);
        }
    }

    private void updateDesync() {
        if (desyncSwitch == null) {
            return;
        }
        final boolean enabled = PengramNet.isEnabled();
        final String details = PengramNet.describe();
        desyncSwitch.setTextAndValueAndCheck(LocaleController.getString(R.string.PengramDesyncMain),
                details == null ? LocaleController.getString(R.string.PengramDesyncOffValue) : details,
                enabled, true, false);
        desyncProfile.setTextAndValue(LocaleController.getString(R.string.PengramDesyncProfile),
                String.valueOf(profileName(PengramNet.getProfile())), false);
        desyncProfile.setVisibility(enabled ? View.VISIBLE : View.GONE);

        if (tuneCell != null) {
            final CharSequence value = tuneState != null ? tuneState
                    : PengramNetTuner.getLastResults().isEmpty()
                        ? LocaleController.getString(R.string.PengramDesyncTuneIdle)
                        : LocaleController.getString(R.string.PengramDesyncTuneAgain);
            tuneCell.setTextAndValue(LocaleController.getString(R.string.PengramDesyncTune),
                    String.valueOf(value), true);
        }
        if (ipCell != null) {
            ipCell.setTextAndValue(LocaleController.getString(R.string.PengramDesyncIp),
                    String.valueOf(ipName(PengramNet.getIpStrategy())), false);
        }

        final boolean custom = enabled && PengramNet.getProfile() == PengramNet.PROFILE_CUSTOM;
        desyncCustom.setVisibility(custom ? View.VISIBLE : View.GONE);
        if (!custom) {
            return;
        }
        for (int a = 0; a < 3; ++a) {
            final int value = PengramNet.getCustomSplit(a);
            desyncValues[a].setTextAndValue(String.valueOf(valueTitle(a)),
                    value == 0 ? LocaleController.getString(R.string.PengramDesyncNoCut) : String.valueOf(value), true);
        }
        final int delay = PengramNet.getCustomDelay();
        desyncValues[3].setTextAndValue(String.valueOf(valueTitle(3)),
                delay == 0 ? LocaleController.getString(R.string.PengramDesyncNoPause) : delay + " ms", true);
        desyncValues[4].setTextAndValue(String.valueOf(valueTitle(4)),
                String.valueOf(PengramNet.getCustomFirstPackets()), true);
        desyncValues[5].setTextAndValue(String.valueOf(valueTitle(5)),
                String.valueOf(PengramNet.getCustomFakeTtl()), true);

        desyncFlags[0].setTextAndCheck(LocaleController.getString(R.string.PengramDesyncRandom),
                PengramNet.isCustomRandom(), true);
        desyncFlags[1].setTextAndCheck(LocaleController.getString(R.string.PengramDesyncNoDelay),
                PengramNet.isCustomNoDelay(), true);
        desyncFlags[2].setTextAndCheck(LocaleController.getString(R.string.PengramDesyncOobFlag),
                PengramNet.isCustomOob(), true);
        final boolean rootMissing = PengramNet.fakeSupport() < 0;
        desyncFlags[3].setTextAndValueAndCheck(LocaleController.getString(R.string.PengramDesyncFakeFlag),
                rootMissing ? LocaleController.getString(R.string.PengramDesyncNeedsRoot) : "",
                PengramNet.isCustomFake(), true, false);
    }

    private void updateAll() {
        updateDesync();
        if (switchCell == null) {
            return;
        }
        switchCell.setTextAndValueAndCheck(
                LocaleController.getString(R.string.PengramBypassMain),
                String.valueOf(stateText()),
                PengramBypass.isEnabled(), true, false);
        updateRefreshCell(PengramBypass.isBusy());
        for (int a = 0; a < routeCells.length; a++) {
            if (routeCells[a] != null) routeCells[a].setChecked(PengramBypass.getPreferredRoute() == a);
        }
        if (advancedButton != null) {
            advancedButton.setText(LocaleController.getString(R.string.PengramBypassAdvanced), false);
        }
        if (statusView != null) {
            statusView.update();
        }
    }

    private void updateRefreshCell(boolean busy) {
        if (refreshCell == null) {
            return;
        }
        final int count = PengramBypass.savedCount();
        final CharSequence value = busy
                ? LocaleController.getString(R.string.PengramBypassSearching)
                : (count == 0
                    ? LocaleController.getString(R.string.PengramBypassNoEntries)
                    : LocaleController.formatString(R.string.PengramBypassEntries, count));
        refreshCell.setTextAndValue(LocaleController.getString(R.string.PengramBypassRefresh),
                String.valueOf(value), true);
    }

    private CharSequence stateText() {
        switch (PengramBypass.getStatus()) {
            case PengramBypass.STATUS_PROXY:
                return LocaleController.getString(R.string.PengramBypassStateProxy);
            case PengramBypass.STATUS_SEARCHING:
                return LocaleController.getString(R.string.PengramBypassStateSearching);
            case PengramBypass.STATUS_FAILED:
                return LocaleController.getString(R.string.PengramBypassStateFailed);
            case PengramBypass.STATUS_DIRECT:
                return LocaleController.getString(R.string.PengramBypassStateDirect);
            default:
                return LocaleController.getString(R.string.PengramBypassStateOff);
        }
    }

    private void showPasteDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final EditText editText = new EditText(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint("vless://…");
        editText.setSingleLine(true);
        editText.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), AndroidUtilities.dp(8));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.PengramBypassPaste));
        builder.setView(editText);
        builder.setPositiveButton(LocaleController.getString(R.string.Add), (dialog, which) -> {
            final String link = editText.getText() == null ? "" : editText.getText().toString();
            final boolean ok = !TextUtils.isEmpty(link) && PengramBypass.addFromLink(link);
            BulletinFactory.of(this).createSimpleBulletin(ok ? R.raw.info : R.raw.error,
                    LocaleController.getString(ok ? R.string.PengramBypassAdded : R.string.PengramBypassBadLink)).show();
            updateAll();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /** живой кружок: спокойный зелёный, тревожный оранжевый, ищущий синий с пульсом */
    private class StatusView extends View {

        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaintHolder title = new TextPaintHolder(20, true);
        private final TextPaintHolder subtitle = new TextPaintHolder(14, false);
        private ValueAnimator pulse;
        private float phase;

        StatusView(Context context) {
            super(context);
        }

        void update() {
            invalidate();
            final boolean animate = PengramBypass.getStatus() == PengramBypass.STATUS_SEARCHING;
            if (animate && pulse == null) {
                pulse = ValueAnimator.ofFloat(0f, 1f);
                pulse.setDuration(1400);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setInterpolator(CubicBezierInterpolator.DEFAULT);
                pulse.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
                pulse.start();
            } else if (!animate && pulse != null) {
                pulse.cancel();
                pulse = null;
                phase = 0;
                invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (pulse != null) {
                pulse.cancel();
                pulse = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int status = PengramBypass.getStatus();
            int color;
            if (status == PengramBypass.STATUS_PROXY || status == PengramBypass.STATUS_DIRECT) {
                color = 0xff4bb34b;
            } else if (status == PengramBypass.STATUS_FAILED) {
                color = 0xffe8663a;
            } else if (status == PengramBypass.STATUS_SEARCHING) {
                color = Theme.getColor(Theme.key_featuredStickers_addButton);
            } else {
                color = Theme.getColor(Theme.key_windowBackgroundWhiteHintText);
            }
            final float cx = AndroidUtilities.dp(42);
            final float cy = getMeasuredHeight() / 2f;

            if (phase > 0) {
                wavePaint.setColor(color);
                wavePaint.setAlpha((int) (90 * (1f - phase)));
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(16) + AndroidUtilities.dp(20) * phase, wavePaint);
            }
            circlePaint.setColor(color);
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(14), circlePaint);

            final float left = AndroidUtilities.dp(76);
            title.paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            subtitle.paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            final String top = String.valueOf(stateText());
            String bottom = PengramBypass.getStatusDetails();
            if (TextUtils.isEmpty(bottom)) {
                bottom = LocaleController.getString(R.string.PengramBypassInfo);
            }
            canvas.drawText(top, left, cy - AndroidUtilities.dp(4), title.paint);
            final CharSequence ellipsized = TextUtils.ellipsize(bottom, subtitle.textPaint(),
                    getMeasuredWidth() - left - AndroidUtilities.dp(18), TextUtils.TruncateAt.END);
            canvas.drawText(String.valueOf(ellipsized), left, cy + AndroidUtilities.dp(20), subtitle.paint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(132));
        }
    }

    /** маленькая обёртка, чтобы не плодить настройку кистей в onDraw */
    private static class TextPaintHolder {
        final android.text.TextPaint paint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);

        TextPaintHolder(int sizeDp, boolean bold) {
            paint.setTextSize(AndroidUtilities.dp(sizeDp));
            if (bold) {
                paint.setTypeface(AndroidUtilities.bold());
            }
        }

        android.text.TextPaint textPaint() {
            return paint;
        }
    }

    @Override
    public boolean needDelayOpenAnimation() {
        return true;
    }

    public static CharSequence shortState() {
        switch (PengramBypass.getStatus()) {
            case PengramBypass.STATUS_PROXY:
                return LocaleController.getString(R.string.PengramBypassStateProxy);
            case PengramBypass.STATUS_SEARCHING:
                return LocaleController.getString(R.string.PengramBypassStateSearching);
            case PengramBypass.STATUS_FAILED:
                return LocaleController.getString(R.string.PengramBypassStateFailed);
            case PengramBypass.STATUS_DIRECT:
                return LocaleController.getString(R.string.PengramBypassStateDirect);
            default:
                return LocaleController.getString(R.string.PengramBypassStateOff);
        }
    }
}
