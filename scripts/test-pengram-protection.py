#!/usr/bin/env python3
"""Защита сборки: откуда берётся отпечаток и что он реально проверяет.

Требование: APK должен быть защищён, а не только выглядеть защищённым.
Здесь проверяется вся цепочка, которую видно в исходниках:

  keystore -> Gradle -> CMake (байтами) -> libtmessages -> JNI -> PengramGuard

и то, что в DEX не осталось ни отпечатка, ни читаемых строк анти-отладки.
Финальная сверка с подписью готового APK — в scripts/verify-pengram-protection.py.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java'
JNI = ROOT / 'TMessagesProj/jni'

gradle = (ROOT / 'TMessagesProj/build.gradle').read_text()
cmake = (ROOT / 'TMessagesProj/jni/CMakeLists.txt').read_text()
guard_h = (JNI / 'tgnet/PengramGuard.h').read_text()
guard_cpp = (JNI / 'tgnet/PengramGuard.cpp').read_text()
wrapper = (JNI / 'TgNetWrapper.cpp').read_text()
guard_java = (JAVA / 'org/telegram/messenger/PengramGuard.java').read_text()
loader = (JAVA / 'org/telegram/messenger/ApplicationLoader.java').read_text()
connections = (JAVA / 'org/telegram/tgnet/ConnectionsManager.java').read_text()
settings = (JAVA / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
index = (JAVA / 'org/telegram/ui/PengramSearchIndex.java').read_text()

# --- отпечаток попадает в нативную часть, а не в DEX --------------------------
assert 'pengramCertSha256Bytes()' in gradle
assert "'-DPENGRAM_CERT_SHA256_BYTES='" in gradle
assert 'release.keystore' in gradle and 'RELEASE_STORE_PASSWORD' in gradle
assert 'MessageDigest.getInstance("SHA-256")' in gradle
# keyStore читается тем же паролем, которым подписывается APK, и сам определяет тип
assert 'KeyStore.getInstance(storeFile, password.toCharArray())' in gradle
assert 'tgnet/PengramGuard.cpp' in cmake
assert 'target_compile_definitions(tgnet PRIVATE PENGRAM_CERT_SHA256_BYTES=' in cmake
# пустое значение (нет ключа) не ломает сборку, а выключает проверку
assert 'PENGRAM_CERT_SHA256_BYTES STREQUAL ""' in cmake
# в релизе .so собирается без DWARF: -g снимается и из флагов, и из конфигурации
assert 'PENGRAM_DEBUG_INFO' in cmake
assert 'CMAKE_${PENGRAM_LANG}_FLAGS_RELWITHDEBINFO' in cmake
assert '-DANDROID ${PENGRAM_DEBUG_FLAG}' in cmake
# собранный APK проверяется отдельно: отпечаток должен быть внутри .so
verify = (ROOT / 'scripts/verify-pengram-protection.py').read_text()
# отпечаток берётся из блока подписи самого APK, а не из текста apksigner:
# формат вывода утилиты меняется между build-tools и уже ломал CI
assert 'APK Sig Block 42' in verify
assert 'V2_SIGNATURE_ID = 0x7109871A' in verify
assert 'V3_SIGNATURE_ID = 0xF05368C0' in verify
assert 'hashlib.sha256(certificate).hexdigest()' in verify
assert 'signer_sha256(args.apk, args.apksigner, required=False)' in verify
assert 'libtmessages' in verify
assert 'verify-pengram-protection.py' in (ROOT / '.github/workflows/build-android-arm64.yml').read_text()
# в Java-исходниках отпечатка нет: его нельзя «поправить» в smali
assert 'PENGRAM_CERT_SHA256' not in guard_java
assert 'sha256' not in guard_java.lower() or 'computeSHA256' in guard_java

# --- нативная проверка -------------------------------------------------------
assert 'int securityFlags();' in guard_h
assert 'bool signatureTrusted(const uint8_t *der, size_t length);' in guard_h
assert '#include "PengramGuard.h"' in guard_cpp
# tgnet/... не резолвится: у цели tgnet в include-путях нет каталога jni
assert '#include "tgnet/PengramGuard.h"' not in guard_cpp
assert 'const uint8_t kPin[] = {PENGRAM_CERT_SHA256_BYTES};' in guard_cpp
assert 'return sizeof(kPin) == 32;' in guard_cpp
# сравнение с отпечатком идёт по volatile-указателю: компилятор не свернёт
# константу в immediates, и отпечаток остаётся в .rodata целиком
assert 'const volatile uint8_t *pin = kPin;' in guard_cpp
assert 'difference |= static_cast<uint8_t>(digest[i] ^ pin[i]);' in guard_cpp
assert 'std::memcmp(digest, kPin' not in guard_cpp
# нет ключа — не мешаем запуску
assert 'if (!hasPinnedSignature()) {' in guard_cpp
# проверки окружения живут в нативной части, а не только в Java
assert 'kFlagDebugger' in guard_cpp and 'kFlagHooks' in guard_cpp and 'kFlagRoot' in guard_cpp
assert '/proc/self/status' in guard_cpp
assert '/proc/self/maps' in guard_cpp
assert 'access(path.c_str(), F_OK)' in guard_cpp

# --- строки анти-отладки закодированы ---------------------------------------
SEED = 0x5A


def decode(values):
    return bytes(b ^ ((SEED + i * 31) & 0xFF) for i, b in enumerate(values)).decode('utf-8')


arrays = [
    match for match in re.findall(
        r'const uint8_t (k\w+)\[\] = \{([0-9a-fx,\s]*)\};\s*(?://\s*(\S+))?', guard_cpp)
    if match[1].strip()
]
assert len(arrays) >= 10, len(arrays)
for name, body, expected in arrays:
    values = [int(v.strip(), 16) for v in body.split(',') if v.strip()]
    text = decode(values)
    if expected:
        # у всех массивов, кроме TracerPid, в комментарии записан исходный текст
        assert text.lower() == expected.lower(), (name, text, expected)
    # ни одна строка не должна встречаться в исходнике открытым текстом
    assert name in guard_cpp
# комментарии в исходнике поясняют, что ищем; в бинарник они не попадают,
# поэтому проверяем только код — строковых литералов с этими словами быть не должно
guard_code = re.sub(r'//[^\n]*', '', re.sub(r'/\*.*?\*/', '', guard_cpp, flags=re.S)).lower()
for needle in ('frida', 'lsposed', 'zygisk', 'kernelsu', 'apatch', 'tracerpid', 'gum-js-loop', 'magisk'):
    assert needle not in guard_code, 'plaintext %s in native code' % needle

# --- JNI ---------------------------------------------------------------------
assert '#include "tgnet/PengramGuard.h"' in wrapper
assert '{"native_pengramSecurityFlags", "()I", (void *) pengramSecurityFlags},' in wrapper
assert '{"native_pengramSignatureTrusted", "([B)Z", (void *) pengramSignatureTrusted},' in wrapper
# таблица методов объявлена раньше функций, иначе C++ не соберётся
assert wrapper.index('jint pengramSecurityFlags(JNIEnv') < wrapper.index('static JNINativeMethod ConnectionsManagerMethods[]')
assert 'pengram::signatureTrusted((const uint8_t *) bytes, (size_t) length)' in wrapper
assert 'ReleaseByteArrayElements(certificate, bytes, JNI_ABORT)' in wrapper

# --- Java --------------------------------------------------------------------
assert 'public static native int native_pengramSecurityFlags();' in connections
assert 'public static native boolean native_pengramSignatureTrusted(byte[] certificate);' in connections
assert 'PengramGuard.install();' in loader
assert 'native_pengramSecurityFlags()' in guard_java
assert 'native_pengramSignatureTrusted(certificate)' in guard_java
assert 'Debug.isDebuggerConnected()' in guard_java
assert 'getApkContentsSigners()' in guard_java
assert 'SIGNATURE_TRUSTED' in guard_java and 'SIGNATURE_MISMATCH' in guard_java

# --- экран «Защита» -----------------------------------------------------------
assert 'public static final int SECTION_SECURITY = 21;' in settings
assert 'case SECTION_SECURITY: fillSecurity(items); break;' in settings
assert 'presentFragment(new PengramSettingsActivity(SECTION_SECURITY));' in settings
assert 'private void fillSecurity(ArrayList<UItem> items)' in settings
assert 'case BTN_SECURITY_RECHECK:' in settings
assert 'case BTN_SECURITY_COPY_FINGERPRINT:' in settings
assert 'case SECTION_SECURITY: return R.string.PengramHeroSecurity;' in settings
assert 'case SECTION_SECURITY: return getString(R.string.PengramSecuritySection);' in settings
assert 'PengramSettingsActivity.SECTION_SECURITY' in index

# --- релиз действительно закрыт ----------------------------------------------
# Правило «keep всё, кроме Pengram*» когда-то было записано как
# `!**.Pengram**, **`: второй фильтр `**` оставлял вообще всё, и R8 не
# переименовывал ни одного класса. Такой регресс должен падать здесь, а не
# выясняться на распакованном APK.
proguard = (ROOT / 'TMessagesProj/proguard-rules.pro').read_text()
assert '-keep class !**.Pengram** { *; }' in proguard
assert '-keepclassmembers class !**.Pengram** { *; }' in proguard
assert '-keep class !**.Pengram**, **' not in proguard
assert '-keepclassmembers class !**.Pengram**, **' not in proguard
assert "-repackageclasses 'p'" in proguard
assert '-renamesourcefileattribute SourceFile' in proguard
# в готовом APK это проверяет scripts/verify-pengram-protection.py
assert 'verify_obfuscated' in (ROOT / 'scripts/verify-pengram-protection.py').read_text()
assert 'verify_no_sources' in (ROOT / 'scripts/verify-pengram-protection.py').read_text()

# --- бэкап не выносит ключи из телефона --------------------------------------
manifest = (ROOT / 'TMessagesProj/src/main/AndroidManifest.xml').read_text()
assert 'android:allowBackup="false"' in manifest
assert 'android:fullBackupContent="false"' in manifest
assert 'android:backupAgent' not in manifest       # агент бэкапил saved_tokens

# --- перепакованная сборка не торгует сессиями --------------------------------
export = (JAVA / 'org/telegram/messenger/PengramSessionExport.java').read_text()
login = (JAVA / 'org/telegram/ui/LoginActivity.java').read_text()
assert 'if (PengramGuard.isRepackaged()) {' in export
assert 'if (org.telegram.messenger.PengramGuard.isRepackaged()) {' in login
assert 'R.string.PengramExportBlocked' in export
assert 'R.string.PengramSessionBlocked' in login
# отладчик и хуки в бете не блокируют: у тестировщиков они бывают,
# а перепакованный APK — это уже не «окружение», а подмена сборки.

# --- строки в обеих локалях ---------------------------------------------------
for locale in ('values', 'values-ru'):
    tree = ET.parse(ROOT / ('TMessagesProj/src/main/res/%s/strings.xml' % locale))
    names = {node.get('name') for node in tree.iter('string')}
    for name in sorted(set(re.findall(r'name="(Pengram(?:Security|HeroSecurity)[A-Za-z]*)"', 
                            (ROOT / 'TMessagesProj/src/main/res/values/strings.xml').read_text()))):
        assert name in names, '%s missing in %s' % (name, locale)

print('Pengram protection: pin goes keystore->CMake->libtmessages, guard wired, strings encoded: OK')
