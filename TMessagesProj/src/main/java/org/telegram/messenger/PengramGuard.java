package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Pengram: состояние защиты самой сборки.
 *
 * <p>Здесь сходятся три проверки, и все они про APK, а не про пользователя:
 *
 * <ul>
 *   <li><b>Подпись.</b> SHA-256 сертификата, которым подписан установленный APK,
 *       сверяется с отпечатком, зашитым в нативную часть при сборке. Отпечаток
 *       передаётся байтами через CMake и в DEX не попадает, поэтому «поправить
 *       константу в smali» недостаточно: пересобранный и переподписанный APK
 *       виден сразу.</li>
 *   <li><b>Отладчик.</b> Java-проверка плюс TracerPid из /proc/self/status:
 *       подключённый jdwp или ptrace-хук видно даже при выключенном
 *       {@code android:debuggable}.</li>
 *   <li><b>Хуки и root.</b> Frida, Xposed/LSPosed, Riru, Zygisk ищутся в карте
 *       памяти процесса, root-менеджеры — по своим путям. Строки в .so
 *       закодированы, чтобы их не нашли обычным {@code strings}.</li>
 * </ul>
 *
 * <p>Проверки ничего не ломают и не мешают работать: это датчики, а не блокировка.
 * Блокировать запуск по отпечатку опасно — ошибка чтения сертификата на редкой
 * прошивке заперла бы пользователя в нерабочем приложении, а сама защита от
 * подмены уже обеспечена подписью APK v2/v3: правленый архив не установится
 * поверх оригинала.
 */
public final class PengramGuard {

    /** за нами следит отладчик (jdwp, ptrace) */
    public static final int FLAG_DEBUGGER = 1;
    /** в процессе есть библиотеки Frida/Xposed-семейства */
    public static final int FLAG_HOOKS = 2;
    /** на устройстве есть root-менеджер */
    public static final int FLAG_ROOT = 4;

    /** сертификат совпал с зашитым при сборке */
    public static final int SIGNATURE_TRUSTED = 0;
    /** APK подписан другим ключом: сборку перепаковали */
    public static final int SIGNATURE_MISMATCH = 1;
    /** в сборке нет отпечатка (ключ не читался при сборке) — судить не о чем */
    public static final int SIGNATURE_UNKNOWN = 2;

    /** как часто перепроверять окружение при открытом экране «Защита» */
    private static final long CACHE_MS = 30_000L;

    private static volatile boolean evaluated;
    private static volatile long evaluatedAt;
    private static volatile int flags;
    private static volatile int signatureState = SIGNATURE_UNKNOWN;
    private static volatile String fingerprint = "";

    private PengramGuard() {
    }

    /**
     * Первый опрос на старте приложения. Нативная часть уже загружена к этому
     * моменту, поэтому проверка идёт в фоне и ничего не задерживает.
     */
    public static void install() {
        try {
            Utilities.globalQueue.postRunnable(PengramGuard::evaluate);
        } catch (Throwable ignore) {
        }
    }

    /** принудительная перепроверка (кнопка «Проверить снова») */
    public static synchronized void refresh() {
        evaluate();
    }

    /** полная переоценка, включая подпись (её значение могло измениться только при обновлении APK) */

    /**
     * Перепроверка, если данные устарели: корень настроек можно открывать
     * сколько угодно раз. Карту памяти читаем в фоне — на экране настроек
     * достаточно уже посчитанного снимка, а «Проверить снова» считает заново.
     */
    public static void refreshIfStale() {
        if (!evaluated) {
            synchronized (PengramGuard.class) {
                if (!evaluated) {
                    evaluate();
                }
            }
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - evaluatedAt > CACHE_MS) {
            evaluatedAt = now;
            try {
                Utilities.globalQueue.postRunnable(PengramGuard::evaluate);
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Подпись — дешёвая проверка (PackageManager + SHA-256), её можно делать
     * прямо на UI-потоке: именно она решает, показывать ли диалог «сборка
     * изменена». Окружение (карта памяти на 4 МБ) — только в фоне.
     */
    private static synchronized void evaluateSignature() {
        final byte[] certificate = signingCertificate();
        fingerprint = certificate == null ? "" : Utilities.bytesToHex(Utilities.computeSHA256(certificate));
        if (certificate == null) {
            signatureState = SIGNATURE_UNKNOWN;
        } else {
            boolean trusted = true;
            try {
                trusted = ConnectionsManager.native_pengramSignatureTrusted(certificate);
            } catch (Throwable ignore) {
                trusted = true;
            }
            signatureState = trusted ? SIGNATURE_TRUSTED : SIGNATURE_MISMATCH;
        }
    }

    private static void evaluate() {
        evaluateSignature();
        int nativeFlags = 0;
        try {
            nativeFlags = ConnectionsManager.native_pengramSecurityFlags();
        } catch (Throwable ignore) {
            // нативная часть недоступна — остаются Java-проверки ниже
        }
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                nativeFlags |= FLAG_DEBUGGER;
            }
        } catch (Throwable ignore) {
        }
        flags = nativeFlags;
        evaluatedAt = System.currentTimeMillis();
        evaluated = true;
    }

    /**
     * DER-сертификат, которым подписан именно установленный APK. История смены
     * ключа (v3 lineage) намеренно не используется: сверяем то, что лежит в
     * архиве, а не то, что когда-то было.
     */
    @SuppressLint("PackageManagerGetSignatures")
    private static byte[] signingCertificate() {
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return null;
            }
            final PackageManager manager = context.getPackageManager();
            final String packageName = context.getPackageName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo != null) {
                    final Signature[] signatures = info.signingInfo.getApkContentsSigners();
                    if (signatures != null && signatures.length > 0) {
                        return signatures[0].toByteArray();
                    }
                }
            }
            final PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            if (info.signatures != null && info.signatures.length > 0) {
                return info.signatures[0].toByteArray();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public static int flags() {
        refreshIfStale();
        return flags;
    }

    public static int signatureState() {
        // Без refreshIfStale: подпись читается один раз и дёшево, а полная
        // перепроверка окружения с чтением карты памяти здесь ни к чему.
        if (!evaluated) {
            evaluateSignature();
        }
        return signatureState;
    }

    public static boolean hasDebugger() {
        return (flags() & FLAG_DEBUGGER) != 0;
    }

    public static boolean hasHooks() {
        return (flags() & FLAG_HOOKS) != 0;
    }

    public static boolean hasRoot() {
        return (flags() & FLAG_ROOT) != 0;
    }

    /** сборка подписана не тем ключом, которым её собрали */
    public static boolean isRepackaged() {
        return signatureState() == SIGNATURE_MISMATCH;
    }

    /** полный отпечаток сертификата (hex, нижний регистр) или пустая строка */
    public static String fingerprint() {
        refreshIfStale();
        return fingerprint;
    }

    /** короткий отпечаток для строки настроек: первые и последние 8 символов */
    public static String shortFingerprint() {
        final String value = fingerprint();
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        if (value.length() <= 20) {
            return value.toUpperCase();
        }
        return (value.substring(0, 8) + "\u2026" + value.substring(value.length() - 8)).toUpperCase();
    }
}
