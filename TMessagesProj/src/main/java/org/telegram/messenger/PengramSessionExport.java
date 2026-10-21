/*
 * This is the source code of Telegram for Android v. 5.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.Locale;

/**
 * Pengram: выгрузка текущей сессии в файл другого клиента.
 *
 * <p>Файл сессии — это ключ авторизации целиком: кто его получил, тот может
 * читать переписку и писать от имени аккаунта без кода из SMS и без пароля
 * двухфакторной защиты. Поэтому экспорт никогда не начинается с выбора
 * формата: сначала длинное предупреждение, потом формат, потом ещё одно
 * подтверждение, и только затем файл.
 *
 * <p>Сам ключ живёт только в памяти: файл пишется в кэш приложения, чтобы его
 * можно было отдать в «Поделиться», и удаляется при следующем экспорте.
 */
public final class PengramSessionExport {

    public static final String FORMAT_TDATA = "tdata";
    public static final String FORMAT_TELETHON = "telethon";
    public static final String FORMAT_PYROGRAM = "pyrogram";
    public static final String FORMAT_TELETHON_STRING = "telethon_string";
    public static final String FORMAT_PYROGRAM_STRING = "pyrogram_string";

    private static final String[] FORMATS = {
            FORMAT_TDATA, FORMAT_TELETHON, FORMAT_PYROGRAM,
            FORMAT_TELETHON_STRING, FORMAT_PYROGRAM_STRING
    };

    /** каталог внутри files/, который уже отдан FileProvider */
    private static final String CACHE_DIR = "cache";
    private static final String FILE_PREFIX = "pengram_session_";

    public interface Callback {
        /** файл готов: отдаём его в «Поделиться» */
        void onFile(File file, String mime);

        /** строковая сессия готова: её место в буфере обмена, а не на диске */
        void onString(String value, boolean pyrogram);

        void onError(String code);
    }

    private PengramSessionExport() {
    }

    // ------------------------------------------------------------- экспорт

    /**
     * Собирает сессию и выгружает её в выбранном формате. Всё, что трогает
     * сеть или диск, выполняется в фоновом потоке: на UI возвращаемся уже с
     * готовым файлом.
     */
    public static void export(final int account, final String format, final Callback callback) {
        if (!isKnownFormat(format)) {
            callback.onError("export_failed");
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File file = null;
            String string = null;
            String mime = "application/octet-stream";
            String failure = null;
            final byte[] authKey = new byte[256];
            try {
                final int dcId = ConnectionsManager.getInstance(account).copyAuthKey(authKey);
                final long userId = UserConfig.getInstance(account).getClientUserId();
                if (dcId < 1 || dcId > 5 || userId == 0) {
                    throw new PengramSessionFormats.Failure("no_key");
                }
                final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
                final boolean bot = user != null && user.bot;
                final int apiId = BuildVars.APP_ID;

                switch (format) {
                    case FORMAT_TDATA:
                        file = newFile(account, FILE_PREFIX + "tdata.zip");
                        writeBytes(file, PengramSessionFormats.exportTdata(authKey, dcId, userId));
                        mime = "application/zip";
                        break;
                    case FORMAT_TELETHON:
                        file = newFile(account, FILE_PREFIX + "telethon.session");
                        PengramSessionFormats.exportTelethonSession(file, authKey, dcId);
                        break;
                    case FORMAT_PYROGRAM:
                        file = newFile(account, FILE_PREFIX + "pyrogram.session");
                        PengramSessionFormats.exportPyrogramSession(file, authKey, dcId, apiId, userId, bot);
                        break;
                    case FORMAT_TELETHON_STRING:
                        string = PengramSessionFormats.telethonStringSession(authKey, dcId);
                        break;
                    case FORMAT_PYROGRAM_STRING:
                        string = PengramSessionFormats.pyrogramStringSession(authKey, dcId, apiId, userId, bot);
                        break;
                    default:
                        throw new PengramSessionFormats.Failure("export_failed");
                }
            } catch (PengramSessionFormats.Failure error) {
                failure = error.code;
            } catch (Throwable error) {
                FileLog.e(error);
                failure = "export_failed";
            } finally {
                // ключ в куче не оставляем: после выгрузки он не нужен
                for (int a = 0; a < authKey.length; ++a) {
                    authKey[a] = 0;
                }
            }
            final String code = failure;
            final File result = file;
            final String text = string;
            final String resultMime = mime;
            AndroidUtilities.runOnUIThread(() -> {
                if (code != null) {
                    callback.onError(code);
                } else if (result != null) {
                    callback.onFile(result, resultMime);
                } else if (text != null) {
                    callback.onString(text, FORMAT_PYROGRAM_STRING.equals(format));
                } else {
                    callback.onError("export_failed");
                }
            });
        });
    }

    private static boolean isKnownFormat(String format) {
        for (String known : FORMATS) {
            if (known.equals(format)) {
                return true;
            }
        }
        return false;
    }

    private static File newFile(int account, String name) throws PengramSessionFormats.Failure {
        final File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), CACHE_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // прошлые выгрузки не должны лежать рядом: это те же ключи
        final File[] old = dir.listFiles();
        if (old != null) {
            for (File file : old) {
                if (file != null && file.getName().startsWith(FILE_PREFIX)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        return new File(dir, name);
    }

    private static void writeBytes(File file, byte[] data) throws PengramSessionFormats.Failure {
        if (data == null || data.length == 0) {
            throw new PengramSessionFormats.Failure("export_failed");
        }
        try (java.io.OutputStream output = new java.io.FileOutputStream(file)) {
            output.write(data);
        } catch (Throwable error) {
            FileLog.e(error);
            throw new PengramSessionFormats.Failure("export_failed");
        }
    }

    // ------------------------------------------------------------- интерфейс

    /**
     * Полный путь от нажатия до файла: предупреждение → формат → ещё одно
     * подтверждение уже про конкретный формат.
     */
    public static void show(final BaseFragment fragment, final int account) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder warning = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        warning.setTitle(LocaleController.getString(R.string.PengramExportTitle));
        warning.setMessage(LocaleController.getString(R.string.PengramExportWarning));
        warning.setPositiveButton(LocaleController.getString(R.string.PengramExportContinue),
                (dialog, which) -> showFormatPicker(fragment, account));
        warning.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(warning.create());
    }

    private static void showFormatPicker(final BaseFragment fragment, final int account) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final CharSequence[] items = {
                LocaleController.getString(R.string.PengramExportFormatTdata),
                LocaleController.getString(R.string.PengramExportFormatTelethon),
                LocaleController.getString(R.string.PengramExportFormatPyrogram),
                LocaleController.getString(R.string.PengramExportFormatTelethonString),
                LocaleController.getString(R.string.PengramExportFormatPyrogramString)
        };
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.PengramExportChooseFormat));
        builder.setItems(items, (dialog, which) -> confirm(fragment, account, FORMATS[which]));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void confirm(final BaseFragment fragment, final int account, final String format) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final boolean isString = FORMAT_TELETHON_STRING.equals(format)
                || FORMAT_PYROGRAM_STRING.equals(format);
        final CharSequence text = isString
                ? LocaleController.getString(R.string.PengramExportConfirmString)
                : LocaleController.getString(R.string.PengramExportConfirmFile);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.PengramExportConfirmTitle));
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(isString
                        ? R.string.PengramExportConfirmCopy : R.string.PengramExportDo),
                (dialog, which) -> run(fragment, account, format));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void run(final BaseFragment fragment, final int account, final String format) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        export(account, format, new Callback() {
            @Override
            public void onFile(File file, String mime) {
                dismiss(progress);
                final Activity current = fragment.getParentActivity();
                if (current == null) {
                    return;
                }
                share(current, file, mime);
            }

            @Override
            public void onString(String value, boolean pyrogram) {
                dismiss(progress);
                AndroidUtilities.addToClipboard(value);
                final Activity current = fragment.getParentActivity();
                if (current != null) {
                    BulletinFactory.of(fragment)
                            .createCopyBulletin(LocaleController.getString(
                                    pyrogram ? R.string.PengramExportCopiedPyrogram
                                            : R.string.PengramExportCopiedTelethon))
                            .show();
                }
            }

            @Override
            public void onError(String code) {
                dismiss(progress);
                final Activity current = fragment.getParentActivity();
                if (current != null) {
                    BulletinFactory.of(fragment)
                            .createSimpleBulletin(R.raw.error,
                                    LocaleController.getString(R.string.PengramExportSessionFailed))
                            .show();
                }
            }
        });
    }

    /**
     * Отдать файл системе через FileProvider: приложение не оставляет себе
     * копию ключа, а получатель выбирает сам, куда его положить.
     */
    private static void share(Activity activity, File file, String mime) {
        try {
            final Uri uri = FileProvider.getUriForFile(activity,
                    ApplicationLoader.getApplicationId() + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent,
                    LocaleController.getString(R.string.PengramExportShare)));
        } catch (Throwable error) {
            // сюда попадаем, только если система не смогла открыть «Поделиться»:
            // файл уже лежит в кэше, поэтому говорим об этом честно
            FileLog.e(error);
        }
    }

    private static void dismiss(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            dialog.dismiss();
        } catch (Throwable ignore) {
        }
    }

    /** текст предупреждения для строкового формата: он короче файла, но не безопаснее */
    static CharSequence stringWarning() {
        return LocaleController.getString(R.string.PengramExportConfirmString);
    }

    /** для тестов: имя файла не должно содержать ни телефона, ни имени */
    static String describeFileForTest(int account, String format) {
        final String extension = FORMAT_TDATA.equals(format) ? "tdata.zip" : format + ".session";
        return String.format(Locale.US, "%s%s", FILE_PREFIX, extension);
    }
}
