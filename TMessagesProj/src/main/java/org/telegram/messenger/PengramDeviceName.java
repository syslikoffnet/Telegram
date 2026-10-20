package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.R;

import java.util.Locale;

/**
 * Pengram: подмена имени устройства.
 *
 * <p>Telegram отправляет серверу строку {@code device_model} в
 * {@code initConnection}. Именно её видно в «Активных сеансах», и именно по ней
 * собеседник (или сам сервер) понимает, с какого устройства выполнен вход.
 * Здесь пользователь может поставить любое имя — из списка популярных моделей
 * или своё: ноутбук, компьютер, что угодно.
 *
 * <p>Строка запоминается до {@code native_init}, поэтому после изменения нужен
 * перезапуск приложения — об этом честно сказано в настройках. Для нового
 * входа перезапуска не требуется: сессия создаётся уже с новым именем.
 */
public final class PengramDeviceName {

    /** включена ли подмена */
    public static final String KEY_ENABLED = "deviceNameSpoof";
    /** что именно отправлять вместо реальной модели */
    public static final String KEY_VALUE = "deviceNameValue";

    /**
     * Готовые варианты. Первый — «как есть» (реальное устройство), дальше
     * популярные модели: по ним проще всего спрятаться в толпе.
     */
    public static final String[] PRESETS = new String[]{
            "", // реальное устройство
            "Samsung SM-S918B",            // Galaxy S23 Ultra
            "Samsung SM-S928B",            // Galaxy S24 Ultra
            "Google Pixel 8 Pro",
            "Google Pixel 7a",
            "Xiaomi 23127PN0CG",           // Xiaomi 14
            "OnePlus CPH2449",             // OnePlus 11
            "Apple MacBook Pro",
            "Microsoft Windows PC",
            "iPad Pro",
    };

    public static final int PRESET_REAL = 0;
    public static final int PRESET_RANDOM = -1;

    private PengramDeviceName() {
    }

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean value) {
        PengramConfig.setBool(KEY_ENABLED, value);
    }

    /** то, что пользователь выбрал (может быть пустым) */
    public static String getValue() {
        return PengramConfig.getStringValue(KEY_VALUE, "");
    }

    public static void setValue(String value) {
        PengramConfig.setStringValue(KEY_VALUE, sanitize(value));
    }

    /** реальная модель в том же формате, что отправляет штатный клиент */
    public static String realDeviceModel() {
        final String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        final String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (manufacturer.length() == 0) {
            return model.length() == 0 ? "Android unknown" : model;
        }
        if (model.length() == 0) {
            return manufacturer;
        }
        // штатный клиент склеивает без пробела; нам нужна читаемая строка,
        // но и повторять производителя («samsung Samsung …») не стоит
        if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return model;
        }
        return manufacturer + " " + model;
    }

    /** итоговая строка для initConnection: подмена или реальная модель */
    public static String resolveDeviceModel() {
        final String custom = getValue();
        if (isEnabled() && !TextUtils.isEmpty(custom)) {
            return custom;
        }
        return realDeviceModel();
    }

    /** человекочитаемое «что сейчас отправляется» для настроек */
    public static String summary() {
        return isEnabled() && !TextUtils.isEmpty(getValue()) ? getValue() : realDeviceModel();
    }

    public static boolean isSpoofed() {
        return isEnabled() && !TextUtils.isEmpty(getValue());
    }

    /** индекс пресета или -1, если стоит своё значение */
    public static int findPresetIndex() {
        final String value = getValue();
        if (TextUtils.isEmpty(value)) {
            return PRESET_REAL;
        }
        for (int a = 1; a < PRESETS.length; a++) {
            if (PRESETS[a].equalsIgnoreCase(value)) {
                return a;
            }
        }
        return -1;
    }

    public static int presetNameRes(int index) {
        switch (index) {
            case PRESET_REAL:
                return R.string.PengramDeviceReal;
            case 1:
                return R.string.PengramDeviceS23Ultra;
            case 2:
                return R.string.PengramDeviceS24Ultra;
            case 3:
                return R.string.PengramDevicePixel8Pro;
            case 4:
                return R.string.PengramDevicePixel7a;
            case 5:
                return R.string.PengramDeviceXiaomi14;
            case 6:
                return R.string.PengramDeviceOnePlus11;
            case 7:
                return R.string.PengramDeviceMacBook;
            case 8:
                return R.string.PengramDeviceWindowsPc;
            case 9:
                return R.string.PengramDeviceIpadPro;
            default:
                return R.string.PengramDeviceCustom;
        }
    }

    /** случайное имя из списка: «спрятаться в толпе» одним нажатием */
    public static String randomPreset() {
        final int index = 1 + (int) (Math.random() * (PRESETS.length - 1));
        return PRESETS[Math.max(1, Math.min(PRESETS.length - 1, index))];
    }

    /**
     * Чистим ввод: сервер ждёт короткую однострочную строку. Управляющие
     * символы и переводы строк ломают initConnection, поэтому вырезаем их, а не
     * надеемся на везение.
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(raw.length());
        for (int a = 0; a < raw.length(); a++) {
            final char c = raw.charAt(a);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c >= 32) {
                out.append(c);
            }
        }
        String value = out.toString().trim();
        while (value.contains("  ")) {
            value = value.replace("  ", " ");
        }
        if (value.length() > 64) {
            value = value.substring(0, 64).trim();
        }
        return value;
    }

    /**
     * Перезапуск приложения: native_init читает имя устройства один раз при
     * старте, поэтому применить подмену к текущей сессии иначе нельзя.
     */
    public static void restartApp(Context context) {
        if (context == null) {
            return;
        }
        try {
            final Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                int flags = PendingIntent.FLAG_CANCEL_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                final PendingIntent pending = PendingIntent.getActivity(context, 0, intent, flags);
                final AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (manager != null) {
                    manager.set(AlarmManager.RTC, System.currentTimeMillis() + 150L, pending);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        System.exit(0);
    }
}
