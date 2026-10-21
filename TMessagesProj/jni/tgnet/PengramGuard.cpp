#include "PengramGuard.h"

#include <openssl/sha.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <unistd.h>

#ifndef PENGRAM_CERT_SHA256_BYTES
#define PENGRAM_CERT_SHA256_BYTES 0
#endif

namespace pengram {
namespace {

/** ключ для строк ниже: в .so не должно оставаться читаемых имён хуков и root-менеджеров */
constexpr uint8_t kSeed = 0x5A;

/** отпечаток сертификата, переданный сборкой байтами (0 — проверка выключена) */
const uint8_t kPin[] = {PENGRAM_CERT_SHA256_BYTES};

std::string decode(const uint8_t *data, size_t size) {
    std::string out(size, '\0');
    for (size_t i = 0; i < size; ++i) {
        out[i] = static_cast<char>(data[i] ^ static_cast<uint8_t>(kSeed + i * 31));
    }
    return out;
}

template <size_t N>
std::string decode(const uint8_t (&data)[N]) {
    return decode(data, N);
}

// Строки хуков и root-менеджеров. Ищем их в карте памяти процесса, поэтому
// сравнение идёт с уже приведённым к нижнему регистру содержимым.
const uint8_t kHookSetA0[] = {0x3c, 0x0b, 0xf1, 0xd3, 0xb7};                                        // frida
const uint8_t kHookSetA1[] = {0x3d, 0x0c, 0xf5, 0x9a, 0xbc, 0x86, 0x39, 0x5f, 0x3d, 0x1e, 0xe0};    // gum-js-loop
const uint8_t kHookSetA2[] = {0x36, 0x10, 0xfa, 0xd0, 0xb7, 0x91, 0x73, 0x56, 0x26};                // libgadget
const uint8_t kHookSetB0[] = {0x22, 0x09, 0xf7, 0xc4, 0xb3, 0x91};                                 // xposed
const uint8_t kHookSetB1[] = {0x36, 0x0a, 0xe8, 0xd8, 0xa5, 0x90, 0x70};                            // lsposed
const uint8_t kHookSetB2[] = {0x28, 0x10, 0xea, 0xc2};                                              // riru
const uint8_t kHookSetB3[] = {0x20, 0x00, 0xff, 0xde, 0xa5, 0x9e};                                  // zygisk
const uint8_t kHookSetB4[] = {0x29, 0x0c, 0xfa, 0xc4, 0xa2, 0x87, 0x75, 0x47, 0x37};                // substrate

const uint8_t kRoot0[] = {0x37, 0x18, 0xff, 0xde, 0xa5, 0x9e};                                        // magisk
const uint8_t kRoot1[] = {0x31, 0x1c, 0xea, 0xd9, 0xb3, 0x99, 0x67, 0x46};                            // kernelsu
const uint8_t kRoot2[] = {0x3b, 0x09, 0xf9, 0xc3, 0xb5, 0x9d};                                        // apatch

const uint8_t kSuPaths0[] = {0x75, 0x0a, 0xe1, 0xc4, 0xa2, 0x90, 0x79, 0x1c, 0x30, 0x18, 0xfe, 0x80, 0xbd, 0x98}; // /system/bin/su
const uint8_t kSuPaths1[] = {0x75, 0x0a, 0xe1, 0xc4, 0xa2, 0x90, 0x79, 0x1c, 0x2a, 0x13, 0xf9, 0xc1, 0xe1, 0x9e, 0x79}; // /system/xbin/su
const uint8_t kSuPaths2[] = {0x75, 0x0a, 0xfa, 0xde, 0xb8, 0xda, 0x67, 0x46};                        // /sbin/su
const uint8_t kSuPaths3[] = {0x75, 0x0a, 0xed, 0x98, 0xb4, 0x9c, 0x7a, 0x1c, 0x21, 0x04};            // /su/bin/su

const uint8_t kRootDirs0[] = {0x75, 0x1d, 0xf9, 0xc3, 0xb7, 0xda, 0x75, 0x57, 0x30, 0x5e, 0xfd, 0xce, 0xa9, 0x84, 0x7f, 0x40}; // /data/adb/magisk
const uint8_t kRootDirs1[] = {0x75, 0x1d, 0xf9, 0xc3, 0xb7, 0xda, 0x75, 0x57, 0x30, 0x5e, 0xfb, 0xdc, 0xbb};                    // /data/adb/ksu
const uint8_t kRootDirs2[] = {0x75, 0x1d, 0xf9, 0xc3, 0xb7, 0xda, 0x75, 0x57, 0x30, 0x5e, 0xf1, 0xdf};                          // /data/adb/ap

const uint8_t kStatusTrace[] = {0x2e, 0x0b, 0xf9, 0xd4, 0xb3, 0x87, 0x64, 0x5a, 0x36};            // tracerpid

/** файл целиком, но не больше limit байт: /proc/self/maps бывает очень большим */
std::string readFile(const char *path, size_t limit) {
    FILE *file = fopen(path, "r");
    if (file == nullptr) {
        return std::string();
    }
    std::string out;
    char buffer[8192];
    size_t total = 0;
    while (total < limit) {
        const size_t read = fread(buffer, 1, sizeof(buffer), file);
        if (read == 0) {
            break;
        }
        out.append(buffer, read);
        total += read;
    }
    fclose(file);
    return out;
}

/** копия в нижнем регистре: карта памяти может быть в любом регистре */
std::string lowered(const std::string &source) {
    std::string out(source);
    for (size_t i = 0; i < out.size(); ++i) {
        const char c = out[i];
        if (c >= 'A' && c <= 'Z') {
            out[i] = static_cast<char>(c - 'A' + 'a');
        }
    }
    return out;
}

bool containsAny(const std::string &haystack, const std::vector<std::string> &needles) {
    for (const std::string &needle : needles) {
        if (!needle.empty() && haystack.find(needle) != std::string::npos) {
            return true;
        }
    }
    return false;
}

/** поле из /proc/self/status: 0 — за нами никто не следит */
int tracePid() {
    const std::string status = lowered(readFile("/proc/self/status", 64 * 1024));
    const std::string needle = decode(kStatusTrace);
    const size_t at = status.find(needle);
    if (at == std::string::npos) {
        return 0;
    }
    size_t pos = at + needle.size();
    while (pos < status.size() && (status[pos] == ' ' || status[pos] == ':' || status[pos] == '\t')) {
        ++pos;
    }
    int value = 0;
    while (pos < status.size() && status[pos] >= '0' && status[pos] <= '9') {
        value = value * 10 + (status[pos] - '0');
        ++pos;
    }
    return value;
}

bool hasHooks() {
    const std::string maps = lowered(readFile("/proc/self/maps", 4 * 1024 * 1024));
    if (maps.empty()) {
        return false;
    }
    return containsAny(maps, {
            decode(kHookSetA0), decode(kHookSetA1), decode(kHookSetA2),
            decode(kHookSetB0), decode(kHookSetB1), decode(kHookSetB2),
            decode(kHookSetB3), decode(kHookSetB4),
    });
}

bool hasRoot() {
    const std::string maps = lowered(readFile("/proc/self/maps", 4 * 1024 * 1024));
    if (!maps.empty() && containsAny(maps, {
            decode(kRoot0), decode(kRoot1), decode(kRoot2)})) {
        return true;
    }
    const std::string paths[] = {
            decode(kSuPaths0), decode(kSuPaths1), decode(kSuPaths2), decode(kSuPaths3),
            decode(kRootDirs0), decode(kRootDirs1), decode(kRootDirs2),
    };
    for (const std::string &path : paths) {
        if (!path.empty() && access(path.c_str(), F_OK) == 0) {
            return true;
        }
    }
    return false;
}

} // namespace

bool hasPinnedSignature() {
    return sizeof(kPin) == 32;
}

int securityFlags() {
    int flags = 0;
    if (tracePid() != 0) {
        flags |= kFlagDebugger;
    }
    if (hasHooks()) {
        flags |= kFlagHooks;
    }
    if (hasRoot()) {
        flags |= kFlagRoot;
    }
    return flags;
}

bool signatureTrusted(const uint8_t *der, size_t length) {
    if (!hasPinnedSignature()) {
        // Отпечатка нет (сборка без ключа) — судить не о чем, не мешаем запуску.
        return true;
    }
    if (der == nullptr || length == 0) {
        return false;
    }
    uint8_t digest[SHA256_DIGEST_LENGTH];
    if (SHA256(der, length, digest) == nullptr) {
        return false;
    }
    // volatile: компилятор не имеет права свернуть сравнение с константой в
    // immediates. Отпечаток должен лежать в .rodata целиком — иначе проверка
    // в собранном .so выглядит так, будто её вырезали.
    const volatile uint8_t *pin = kPin;
    uint8_t difference = 0;
    for (size_t i = 0; i < sizeof(kPin); ++i) {
        difference |= static_cast<uint8_t>(digest[i] ^ pin[i]);
    }
    return difference == 0;
}

} // namespace pengram
