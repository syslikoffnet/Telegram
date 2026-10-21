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
    /** подменять ли версию системы вместе с именем устройства */
    public static final String KEY_VERSION_ENABLED = "deviceVersionSpoof";
    /** конкретная строка версии; пусто — берём подходящую к выбранной модели */
    public static final String KEY_VERSION_VALUE = "deviceVersionValue";

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
            "Apple iPhone 15 Pro",
    };

    public static final int PRESET_REAL = 0;
    public static final int PRESET_RANDOM = -1;

    // ------------------------------------------------------------------
    // Тип устройства. Сервер и «Активные сеансы» показывают не только имя
    // модели, но и платформу, поэтому выбранной модели нужно соответствие:
    // iPhone — iOS, MacBook — macOS, Windows PC — Windows.
    // ------------------------------------------------------------------

    public static final int KIND_ANDROID = 0;
    public static final int KIND_ANDROID_TABLET = 1;
    public static final int KIND_IPHONE = 2;
    public static final int KIND_IPAD = 3;
    public static final int KIND_MAC = 4;
    public static final int KIND_WINDOWS = 5;
    public static final int KIND_LINUX = 6;
    public static final int KIND_WEB = 7;

    /**
     * Версии для каждого типа устройства. Первая — та, что подставляется по
     * умолчанию: она должна выглядеть как обычный свежий клиент, а не как
     * «музейный экспонат» — по устаревшей версии легко вычислить подмену.
     */
    private static final String[][] KIND_VERSIONS = new String[][]{
            {"SDK 35", "SDK 34", "SDK 33", "SDK 31"},              // KIND_ANDROID
            {"SDK 35", "SDK 34", "SDK 33"},                        // KIND_ANDROID_TABLET
            {"iOS 18.4", "iOS 18.0", "iOS 17.6", "iOS 16.7"},      // KIND_IPHONE
            {"iPadOS 18.4", "iPadOS 18.0", "iPadOS 17.6"},         // KIND_IPAD
            {"macOS 15.3", "macOS 14.6", "macOS 13.6"},            // KIND_MAC
            {"Windows 11", "Windows 10"},                          // KIND_WINDOWS
            {"Linux", "Ubuntu 24.04"},                             // KIND_LINUX
            {"Web"}                                                // KIND_WEB
    };

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
        final String clean = sanitize(value);
        PengramConfig.setStringValue(KEY_VALUE, clean);
        // Версия должна соответствовать новой модели: iPhone с «SDK 34» в
        // сеансах выглядит как явная подмена. Пользователь может потом
        // поставить свою версию — она сохранится до следующей смены модели.
        PengramConfig.setStringValue(KEY_VERSION_VALUE,
                clean.length() > 0 ? defaultVersionFor(kindOf(clean)) : "");
    }

    /** подменять ли версию системы */
    public static boolean isVersionSpoofEnabled() {
        return PengramConfig.getBool(KEY_VERSION_ENABLED, true);
    }

    public static void setVersionSpoofEnabled(boolean value) {
        PengramConfig.setBool(KEY_VERSION_ENABLED, value);
    }

    /** выбранная вручную версия (может быть пустой) */
    public static String getVersionValue() {
        return PengramConfig.getStringValue(KEY_VERSION_VALUE, "");
    }

    public static void setVersionValue(String value) {
        PengramConfig.setStringValue(KEY_VERSION_VALUE, sanitize(value));
    }

    /** что отправляет штатный клиент без подмены */
    public static String realSystemVersion() {
        return "SDK " + Build.VERSION.SDK_INT;
    }

    /**
     * Итоговая строка версии для initConnection. Подмена версии работает
     * только вместе с подменой имени: иначе получится «Samsung» с iOS.
     */
    public static String resolveSystemVersion() {
        if (isEnabled() && isVersionSpoofEnabled()) {
            final String custom = getVersionValue();
            if (!TextUtils.isEmpty(custom)) {
                return custom;
            }
            if (isSpoofed()) {
                return defaultVersionFor(kind());
            }
        }
        return realSystemVersion();
    }

    /** тип устройства по строке модели */
    public static int kindOf(String model) {
        final String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.contains("ipad")) {
            return KIND_IPAD;
        }
        if (m.contains("iphone") || m.contains("ipod")) {
            return KIND_IPHONE;
        }
        if (m.contains("macbook") || m.contains("imac") || m.contains("macos")
                || m.startsWith("mac") || m.contains(" mac")) {
            return KIND_MAC;
        }
        if (m.contains("windows") || m.contains("win32") || m.equals("pc")
                || m.endsWith(" pc") || m.startsWith("pc ")) {
            return KIND_WINDOWS;
        }
        if (m.contains("linux") || m.contains("ubuntu")) {
            return KIND_LINUX;
        }
        if (m.contains("web") || m.contains("browser")) {
            return KIND_WEB;
        }
        if (m.contains("tab")) {
            return KIND_ANDROID_TABLET;
        }
        return KIND_ANDROID;
    }

    /** тип устройства, который сейчас заявлен */
    public static int kind() {
        return kindOf(isSpoofed() ? getValue() : realDeviceModel());
    }

    /** иконка типа устройства — та же, что в «Активных сеансах» */
    public static int iconResForKind(int kind) {
        switch (kind) {
            case KIND_IPHONE: return R.drawable.device_phone_ios;
            case KIND_IPAD: return R.drawable.device_tablet_ios;
            case KIND_MAC: return R.drawable.device_desktop_osx;
            case KIND_WINDOWS: return R.drawable.device_desktop_win;
            case KIND_LINUX: return R.drawable.device_desktop_other;
            case KIND_WEB: return R.drawable.device_web_chrome;
            case KIND_ANDROID_TABLET: return R.drawable.device_tablet_android;
            case KIND_ANDROID:
            default: return R.drawable.device_phone_android;
        }
    }

    public static int iconRes() {
        return iconResForKind(kind());
    }

    /** подпись типа устройства для бейджа */
    public static int labelResForKind(int kind) {
        switch (kind) {
            case KIND_IPHONE: return R.string.PengramDeviceKindIphone;
            case KIND_IPAD: return R.string.PengramDeviceKindIpad;
            case KIND_MAC: return R.string.PengramDeviceKindMac;
            case KIND_WINDOWS: return R.string.PengramDeviceKindWindows;
            case KIND_LINUX: return R.string.PengramDeviceKindLinux;
            case KIND_WEB: return R.string.PengramDeviceKindWeb;
            case KIND_ANDROID_TABLET: return R.string.PengramDeviceKindAndroidTablet;
            case KIND_ANDROID:
            default: return R.string.PengramDeviceKindAndroid;
        }
    }

    /** варианты версии для выбранного типа устройства */
    public static String[] versionsFor(int kind) {
        final int index = Math.max(0, Math.min(KIND_VERSIONS.length - 1, kind));
        return KIND_VERSIONS[index];
    }

    public static String defaultVersionFor(int kind) {
        final String[] versions = versionsFor(kind);
        return versions.length == 0 ? realSystemVersion() : versions[0];
    }

    /**
     * Бейдж «что за устройство» для строк настроек: тип и версия одной
     * строкой. Именно это видно в сеансах, поэтому и подпись такая же.
     */
    public static String badgeSummary() {
        return safeLabel(kind()) + " \u00b7 " + resolveSystemVersion() + " \u00b7 " + resolveLangPack();
    }

    private static String safeLabel(int kind) {
        try {
            return LocaleController.getString(labelResForKind(kind));
        } catch (Throwable e) {
            return "Android";
        }
    }

    /**
     * Платформа для initConnection ({@code lang_pack}). Сервер помечает сеанс
     * именно по ней, а не по строке модели: с «android» любой MacBook в списке
     * сеансов останется телефоном с роботом. iPhone и iPad делят «ios» — их
     * различает модель.
     */
    public static String resolveLangPack() {
        if (!isSpoofed()) {
            return "android";
        }
        switch (kind()) {
            case KIND_IPHONE:
            case KIND_IPAD:
                return "ios";
            case KIND_MAC:
                return "macos";
            case KIND_WINDOWS:
                return "windows";
            case KIND_LINUX:
                return "linux";
            case KIND_WEB:
                return "web";
            default:
                return "android";
        }
    }

    /** заявлено не-Android устройство: android-параметры initConnection не шлём */
    public static boolean isDesktopSpoof() {
        if (!isSpoofed()) {
            return false;
        }
        final int kind = kind();
        return kind != KIND_ANDROID && kind != KIND_ANDROID_TABLET;
    }

    /**
     * Версия приложения. У Android в строке есть номер сборки в скобках
     * («11.4.2 (5230)»), у десктопа и iOS — только номер: иначе подменённый
     * Mac выдаёт себя форматом строки.
     */
    public static String resolveAppVersion(String real) {
        if (!isDesktopSpoof() || TextUtils.isEmpty(real)) {
            return real;
        }
        final int space = real.indexOf(" (");
        return space > 0 ? real.substring(0, space) : real;
    }

    /** «что реально уйдёт серверу»: заявленная модель и платформа */
    public static String serverIdentity() {
        return summary() + " \u00b7 " + resolveLangPack();
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
            case 10:
                return R.string.PengramDeviceIphone15Pro;
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
