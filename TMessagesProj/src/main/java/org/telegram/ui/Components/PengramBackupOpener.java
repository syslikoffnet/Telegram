package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramBackup;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Pengram: открытие файла настроек .pen прямо из чата или из системы.
 *
 * Нажатие на такой файл не должно уводить в «чем открыть» — это наш собственный
 * формат, и единственное осмысленное действие с ним одно: применить настройки.
 * Поэтому здесь один общий сценарий: распознать файл, спросить пароль (если он
 * есть), показать подтверждение и применить.
 */
public final class PengramBackupOpener {

    /** больше этого .pen не бывает — дальше читать смысла нет */
    private static final int MAX_SIZE = 8 * 1024 * 1024;

    private PengramBackupOpener() {}

    /** похоже ли имя файла на настройки Pengram */
    public static boolean isBackupName(String name) {
        return name != null && name.toLowerCase().endsWith(".pen");
    }

    /** .pen ли это по имени: content://-ссылки приходится спрашивать у провайдера */
    public static boolean looksLikePenUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        if (isBackupName(uri.getLastPathSegment()) || isBackupName(uri.toString())) {
            return true;
        }
        try (android.database.Cursor cursor = ApplicationLoader.applicationContext.getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return isBackupName(cursor.getString(0));
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * Предложить применить файл как настройки.
     * @return true, если файл распознан и сценарий запущен — тогда обычное открытие не нужно
     */
    public static boolean tryOpen(Activity activity, BaseFragment fragment, File file, String fileName) {
        if (activity == null || file == null || !file.exists()) {
            return false;
        }
        // сначала дешёвая проверка имени: читать каждый открываемый документ целиком нельзя
        if (!isBackupName(fileName) && !isBackupName(file.getName())) {
            return false;
        }
        final byte[] data = read(file);
        if (data == null || !PengramBackup.looksLikeBackup(data)) {
            return false;
        }
        start(activity, fragment, data);
        return true;
    }

    /** то же самое, но для системного Uri («Открыть с помощью» из файлового менеджера) */
    public static boolean tryOpen(Activity activity, BaseFragment fragment, Uri uri) {
        if (activity == null || uri == null) {
            return false;
        }
        final byte[] data = read(uri);
        if (data == null || !PengramBackup.looksLikeBackup(data)) {
            return false;
        }
        start(activity, fragment, data);
        return true;
    }

    /** применить уже прочитанные байты (используется экраном настроек) */
    public static void start(Activity activity, BaseFragment fragment, byte[] data) {
        if (PengramBackup.needsPassword(data)) {
            askPassword(activity, fragment, password -> confirm(activity, fragment, data, password));
        } else {
            confirm(activity, fragment, data, null);
        }
    }

    // ------------------------------------------------------------------ чтение

    private static byte[] read(File file) {
        if (file.length() > MAX_SIZE) {
            return null;
        }
        try (InputStream stream = new FileInputStream(file)) {
            return read(stream);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static byte[] read(Uri uri) {
        try (InputStream stream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            return stream == null ? null : read(stream);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static byte[] read(InputStream stream) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) > 0 && out.size() < MAX_SIZE) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    // ----------------------------------------------------------------- диалоги

    private static void confirm(Activity activity, BaseFragment fragment, byte[] data, String password) {
        final String json = PengramBackup.unpack(data, password);
        if (json == null) {
            error(activity, fragment, LocaleController.getString(password == null
                    ? R.string.PengramBackupNotPen : R.string.PengramBackupWrongPassword));
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.PengramBackupOpenTitle));
        builder.setMessage(LocaleController.getString(R.string.PengramBackupOpenMessage));
        builder.setPositiveButton(LocaleController.getString(R.string.PengramBackupApply), (d, w) -> {
            if (PengramBackup.apply(json)) {
                applied(activity, fragment);
            } else {
                error(activity, fragment, LocaleController.getString(R.string.PengramBackupFailed));
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        show(fragment, builder);
    }

    private static void applied(Activity activity, BaseFragment fragment) {
        try {
            org.telegram.messenger.PengramVoiceChanger.reset();
            org.telegram.messenger.PengramBackgroundService.update(activity);
        } catch (Throwable ignore) {}
        if (fragment != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done,
                    LocaleController.getString(R.string.PengramBackupImported)).show();
        } else {
            final AlertDialog.Builder done = new AlertDialog.Builder(activity);
            done.setTitle(LocaleController.getString(R.string.PengramBackupImported));
            done.setMessage(LocaleController.getString(R.string.PengramBackupOpenDone));
            done.setPositiveButton(LocaleController.getString(R.string.OK), null);
            done.show();
        }
    }

    private static void error(Activity activity, BaseFragment fragment, CharSequence text) {
        if (fragment != null) {
            BulletinFactory.of(fragment).createErrorBulletin(text).show();
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.show();
    }

    private static void askPassword(Activity activity, BaseFragment fragment, Utilities.Callback<String> whenDone) {
        final Context context = activity;
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(R.string.PengramBackupPasswordEnter));
        field.setBackground(null);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setCursorWidth(1.5f);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 22, 4, 22, 0));

        final TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        hint.setText(LocaleController.getString(R.string.PengramBackupPasswordInfoOpen));
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 10, 22, 4));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.PengramBackupPasswordTitle));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.PengramBackupApply), (d, w) ->
                whenDone.run(field.getText() == null ? "" : field.getText().toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        show(fragment, builder);
    }

    private static void show(BaseFragment fragment, AlertDialog.Builder builder) {
        if (fragment != null) {
            fragment.showDialog(builder.create());
        } else {
            builder.show();
        }
    }
}
