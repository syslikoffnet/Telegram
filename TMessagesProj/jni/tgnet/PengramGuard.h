/*
 * Pengram: нативная часть проверки сборки.
 *
 * Всё, что здесь есть, работает без Java и без доступа к PackageManager:
 * отпечаток сертификата, отладчик, хуки и root. Проверять это из Java можно,
 * но Java-проверку видно в DEX и её легко вырезать патчером — поэтому ключевые
 * вещи живут в .so.
 *
 * Отпечаток сертификата передаётся сюда байтами во время сборки
 * (PENGRAM_CERT_SHA256_BYTES), а не hex-строкой: в бинарнике его нельзя найти
 * ни `strings`, ни поиском по тексту, и он не попадает в DEX.
 */

#ifndef PENGRAM_GUARD_H
#define PENGRAM_GUARD_H

#include <cstddef>
#include <cstdint>

namespace pengram {

/** биты состояния: 1 — отладчик, 2 — хуки/инъекции, 4 — root */
enum {
    kFlagDebugger = 1,
    kFlagHooks = 2,
    kFlagRoot = 4,
};

/**
 * Однократный (кэшируемый вызывающим) опрос окружения.
 * Читает /proc/self/status и /proc/self/maps, поэтому вызывать лучше не в UI-потоке.
 */
int securityFlags();

/**
 * Совпадает ли SHA-256 сертификата, которым подписан установленный APK,
 * с отпечатком, зашитым в эту сборку.
 *
 * @param der DER-кодированный X.509 сертификат (PackageInfo.signatures[0])
 * @param length его длина
 * @return true, если отпечаток совпал или в сборке нет отпечатка (проверка выключена)
 */
bool signatureTrusted(const uint8_t *der, size_t length);

/** есть ли в сборке зашитый отпечаток (для экрана «Защита») */
bool hasPinnedSignature();

} // namespace pengram

#endif // PENGRAM_GUARD_H
