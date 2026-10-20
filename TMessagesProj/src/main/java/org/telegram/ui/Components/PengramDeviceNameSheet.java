package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
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
        for (int a = 0; a < values.length; ++a) {
            options[a] = LocaleController.getString(PengramDeviceName.presetNameRes(a));
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
            cells[a].setTextAndValue(options[a], selected == a);
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
                    LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));
        }
        builder.setCustomView(layout);
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
