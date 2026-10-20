package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramDeviceName;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;

/**
 * Pengram: выбор имени устройства до входа в аккаунт.
 *
 * <p>В настройках этот же выбор живёт отдельным разделом, но пока пользователь
 * не вошёл, настроек нет — а имя нужно успеть указать именно до регистрации,
 * иначе сессия создастся с настоящей моделью. Поэтому здесь тот же список
 * моделей в компактной шторке.
 */
public final class PengramDeviceNameSheet {

    private PengramDeviceNameSheet() {
    }

    public static void show(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        final Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final String[] values = PengramDeviceName.PRESETS;
        final CharSequence[] options = new CharSequence[values.length + 1];
        final CharSequence[] subtitles = new CharSequence[values.length + 1];
        for (int a = 0; a < values.length; ++a) {
            options[a] = LocaleController.getString(PengramDeviceName.presetNameRes(a));
            // Бейдж под названием: что за устройство и какая версия системы
            // уйдут на сервер. Видно ещё до входа в аккаунт.
            final String model = a == PengramDeviceName.PRESET_REAL
                    ? PengramDeviceName.realDeviceModel() : values[a];
            final int kind = PengramDeviceName.kindOf(model);
            final String version = a == PengramDeviceName.PRESET_REAL
                    ? PengramDeviceName.realSystemVersion()
                    : PengramDeviceName.defaultVersionFor(kind);
            subtitles[a] = LocaleController.getString(PengramDeviceName.labelResForKind(kind))
                    + " \u00b7 " + version;
        }
        options[values.length] = LocaleController.getString(R.string.PengramDeviceCustom);
        final int current = PengramDeviceName.findPresetIndex();
        final int selected = current < 0 ? values.length : current;

        final BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.PengramDeviceModel), true);

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        final RadioColorCell[] cells = new RadioColorCell[options.length];
        for (int a = 0; a < options.length; ++a) {
            final int index = a;
            cells[a] = new RadioColorCell(activity, resourcesProvider);
            cells[a].setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cells[a].setCheckColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            if (subtitles[a] != null) {
                cells[a].setTextAndText2AndValue(options[a], subtitles[a], selected == a);
            } else {
                cells[a].setTextAndValue(options[a], selected == a);
            }
            cells[a].setBackground(Theme.getSelectorDrawable(false));
            cells[a].setOnClickListener(v -> {
                try {
                    builder.getDismissRunnable().run();
                } catch (Throwable ignore) {
                }
                if (index < values.length) {
                    PengramDeviceName.setValue(values[index]);
                    PengramDeviceName.setEnabled(index != PengramDeviceName.PRESET_REAL);
                    confirmRestart(fragment, resourcesProvider);
                } else {
                    askCustomName(fragment, resourcesProvider);
                }
            });
            layout.addView(cells[a], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        // Версия системы доступна и до входа: она подставляется по выбранной
        // модели, но её можно уточнить здесь же — как и в настройках.
        final android.widget.TextView versionRow = new android.widget.TextView(activity);
        versionRow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionRow.setSingleLine(true);
        versionRow.setEllipsize(android.text.TextUtils.TruncateAt.END);
        versionRow.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        versionRow.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), 0);
        versionRow.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        versionRow.setText(LocaleController.getString(R.string.PengramDeviceVersion) + ": "
                + PengramDeviceName.resolveSystemVersion());
        versionRow.setBackground(Theme.getSelectorDrawable(false));
        versionRow.setOnClickListener(v -> showVersionSheet(fragment, resourcesProvider));
        layout.addView(versionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(44)));

        builder.setCustomView(layout);
        fragment.showDialog(builder.create());
    }

    /** компактный выбор версии системы для шторки до входа */
    private static void showVersionSheet(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        final Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final int kind = PengramDeviceName.kind();
        final String[] versions = PengramDeviceName.versionsFor(kind);
        final CharSequence[] options = new CharSequence[versions.length + 2];
        for (int a = 0; a < versions.length; ++a) {
            options[a] = versions[a];
        }
        options[versions.length] = LocaleController.getString(R.string.PengramDeviceVersionReal);
        options[versions.length + 1] = LocaleController.getString(R.string.PengramDeviceCustom);
        final String current = PengramDeviceName.getVersionValue();
        int selected = versions.length;
        for (int a = 0; a < versions.length; ++a) {
            if (versions[a].equalsIgnoreCase(current) && PengramDeviceName.isVersionSpoofEnabled()) {
                selected = a;
                break;
            }
        }

        final BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.PengramDeviceVersion), true);
        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        final RadioColorCell[] cells = new RadioColorCell[options.length];
        for (int a = 0; a < options.length; ++a) {
            final int index = a;
            cells[a] = new RadioColorCell(activity, resourcesProvider);
            cells[a].setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cells[a].setCheckColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            cells[a].setTextAndValue(options[a], selected == a);
            cells[a].setBackground(Theme.getSelectorDrawable(false));
            cells[a].setOnClickListener(v -> {
                try {
                    builder.getDismissRunnable().run();
                } catch (Throwable ignore) {
                }
                if (index < versions.length) {
                    PengramDeviceName.setVersionValue(versions[index]);
                    PengramDeviceName.setVersionSpoofEnabled(true);
                    confirmRestart(fragment, resourcesProvider);
                } else if (index == versions.length) {
                    PengramDeviceName.setVersionSpoofEnabled(false);
                    confirmRestart(fragment, resourcesProvider);
                } else {
                    askCustomVersion(fragment, resourcesProvider);
                }
            });
            layout.addView(cells[a], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        builder.setCustomView(layout);
        fragment.showDialog(builder.create());
    }

    private static void askCustomVersion(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        final Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final Context context = activity;
        final EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(LocaleController.getString(R.string.PengramDeviceVersionCustomHint));
        input.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), AndroidUtilities.dp(8));
        input.setText(PengramDeviceName.getVersionValue());
        input.setSelection(input.getText().length());

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.PengramDeviceVersionCustomTitle));
        builder.setView(input);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            final String clean = PengramDeviceName.sanitize(input.getText().toString());
            if (clean.length() == 0) {
                return;
            }
            PengramDeviceName.setVersionValue(clean);
            PengramDeviceName.setVersionSpoofEnabled(true);
            confirmRestart(fragment, resourcesProvider);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void askCustomName(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        final Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final Context context = activity;
        final EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(LocaleController.getString(R.string.PengramDeviceCustomHint));
        input.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), AndroidUtilities.dp(8));
        input.setText(PengramDeviceName.getValue());
        input.setSelection(input.getText().length());

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.PengramDeviceCustomTitle));
        builder.setView(input);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            final String clean = PengramDeviceName.sanitize(input.getText().toString());
            if (clean.length() == 0) {
                return;
            }
            PengramDeviceName.setValue(clean);
            PengramDeviceName.setEnabled(true);
            confirmRestart(fragment, resourcesProvider);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** имя уходит на сервер при подключении — предупреждаем до перезапуска */
    private static void confirmRestart(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        final Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.PengramDeviceRestartTitle));
        builder.setMessage(LocaleController.getString(R.string.PengramDeviceRestartInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.PengramDeviceRestartButton), (d, w) ->
                PengramDeviceName.restartApp(org.telegram.messenger.ApplicationLoader.applicationContext));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }
}
