package org.telegram.messenger;

import android.os.Build;

/**
 * Pengram: паспорт сборки.
 *
 * Всё, что нужно бета-тестеру для осмысленного баг-репорта: своя версия форка,
 * версия телеграм-базы, дата сборки, коммит и железо. Значения приезжают из
 * BuildConfig (их проставляет gradle), поэтому руками их править не нужно.
 */
public class PengramVersion {

    private PengramVersion() {
    }

    /** «1.0.0 beta 1» */
    public static String name() {
        try {
            final String value = BuildConfig.PENGRAM_VERSION;
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable ignore) {
        }
        return "1.0.0";
    }

    /** сквозной номер сборки Pengram */
    public static int code() {
        try {
            return BuildConfig.PENGRAM_VERSION_CODE;
        } catch (Throwable ignore) {
            return 1;
        }
    }

    /** дата сборки в UTC */
    public static String buildDate() {
        try {
            final String value = BuildConfig.PENGRAM_BUILD_DATE;
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable ignore) {
        }
        return "—";
    }

    /** короткий хеш коммита */
    public static String commit() {
        try {
            final String value = BuildConfig.PENGRAM_COMMIT;
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable ignore) {
        }
        return "local";
    }

    /** «1.0.0 beta 1 (1)» — короткая строка для шапок и подписей */
    public static String shortLine() {
        return name() + " (" + code() + ")";
    }

    /** версия телеграм-базы, на которой собран форк, плюс versionCode из APK */
    public static String telegramVersion() {
        String version = "—";
        try {
            version = BuildVars.BUILD_VERSION_STRING;
        } catch (Throwable ignore) {
        }
        try {
            final android.content.pm.PackageInfo info = ApplicationLoader.applicationContext
                    .getPackageManager()
                    .getPackageInfo(packageName(), 0);
            if (info != null && info.versionCode > 0) {
                return version + " (" + info.versionCode + ")";
            }
        } catch (Throwable ignore) {
        }
        return version;
    }

    public static String device() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    public static String androidVersion() {
        return "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
    }

    public static String abi() {
        try {
            final String[] abis = Build.SUPPORTED_ABIS;
            return abis != null && abis.length > 0 ? abis[0] : "—";
        } catch (Throwable ignore) {
            return "—";
        }
    }

    public static String packageName() {
        try {
            return ApplicationLoader.applicationContext.getPackageName();
        } catch (Throwable ignore) {
            return "—";
        }
    }

    /** установщик: из магазина, из файла или сторонний источник */
    public static String installer() {
        try {
            final android.content.pm.PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            final String from;
            if (Build.VERSION.SDK_INT >= 30) {
                from = pm.getInstallSourceInfo(packageName()).getInstallingPackageName();
            } else {
                from = pm.getInstallerPackageName(packageName());
            }
            return from == null || from.isEmpty() ? "APK" : from;
        } catch (Throwable ignore) {
            return "APK";
        }
    }

    /**
     * Всё одним куском — ровно то, что улетает в буфер обмена по кнопке
     * «скопировать всё» и что просят приложить к баг-репорту.
     */
    public static String report() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Pengram ").append(shortLine()).append('\n');
        sb.append("build: ").append(buildDate()).append(" · ").append(commit()).append('\n');
        sb.append("base: Telegram ").append(telegramVersion()).append('\n');
        sb.append("package: ").append(packageName()).append(" · ").append(installer()).append('\n');
        sb.append("device: ").append(device()).append('\n');
        sb.append("system: ").append(androidVersion()).append(" · ").append(abi()).append('\n');
        sb.append("locale: ").append(java.util.Locale.getDefault().toString());
        return sb.toString();
    }
}
