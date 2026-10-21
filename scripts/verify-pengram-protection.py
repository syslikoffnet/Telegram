#!/usr/bin/env python3
"""Проверка защиты готового APK.

Что доказывается на собранном файле:

1. Отпечаток, зашитый в libtmessages, совпадает с SHA-256 сертификата, которым
   подписан этот APK. Значит, сверка подписи в рантайме действительно включена,
   а не отключена из-за нечитаемого keystore.
2. В DEX отпечатка нет ни в каком виде — его нельзя «поправить» в smali.
3. В нативной библиотеке нет открытым текстом имён хуков и root-менеджеров:
   строки закодированы, и обычный `strings` их не показывает.

Запуск: python3 scripts/verify-pengram-protection.py <apk> [--apksigner path]
"""

import argparse
import re
import subprocess
import sys
import zipfile
from pathlib import Path

# то, что не должно читаться в .so: имена хуков и root-менеджеров, а также поле
# /proc/self/status, по которому ищут анти-отладку
FORBIDDEN_IN_NATIVE = (
    b'frida',
    b'gum-js-loop',
    b'libgadget',
    b'xposed',
    b'lsposed',
    b'riru',
    b'zygisk',
    b'substrate',
    b'magisk',
    b'kernelsu',
    b'apatch',
    b'tracerpid',
)


def fail(message: str) -> None:
    """Ошибка шага CI: workflow-команда должна быть в stdout, иначе её не видно."""
    print('::error::' + message)
    raise SystemExit(1)


def parse_signer_digest(output: str) -> str:
    # apksigner печатает отпечаток через двоеточия: AA:BB:CC:...
    match = re.search(r'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)', output)
    if not match:
        raise ValueError('apksigner did not print a SHA-256 digest')
    digest = match.group(1).replace(':', '').strip().lower()
    if len(digest) != 64 or re.fullmatch(r'[0-9a-f]{64}', digest) is None:
        raise ValueError('unexpected SHA-256 digest length: %d' % len(digest))
    return digest


def signer_sha256(apk: Path, apksigner: str) -> str:
    result = subprocess.run(
        [apksigner, 'verify', '--print-certs', '-v', str(apk)],
        check=False, capture_output=True, text=True)
    if result.returncode != 0:
        fail('apksigner verify failed: ' + (result.stderr or result.stdout).strip()[:400])
    try:
        return parse_signer_digest(result.stdout)
    except ValueError as error:
        fail('%s\n%s' % (error, result.stdout.strip()[:800]))


def native_libraries(archive: zipfile.ZipFile):
    names = [n for n in archive.namelist()
             if re.match(r'lib/[^/]+/libtmessages[^/]*\.so$', n)]
    if not names:
        fail('APK has no libtmessages .so — the native guard cannot run')
    return names


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--apksigner', default='apksigner')
    args = parser.parse_args()

    digest = signer_sha256(args.apk, args.apksigner)
    pin = bytes.fromhex(digest)
    print('signer certificate SHA-256:', digest)

    with zipfile.ZipFile(args.apk) as archive:
        libraries = native_libraries(archive)
        found_in = None
        for name in libraries:
            data = archive.read(name)
            if pin in data:
                found_in = name
                break
        if found_in is None:
            fail('the signer certificate fingerprint is not embedded in any '
                 'libtmessages .so — runtime signature pinning is disabled')
        print('fingerprint embedded in:', found_in)

        for name in libraries:
            data = archive.read(name)
            for needle in FORBIDDEN_IN_NATIVE:
                if needle in data.lower():
                    fail('plaintext "%s" in %s — anti-debug strings are not encoded'
                         % (needle.decode(), name))
        print('%d native libraries: no plaintext hook/debug strings' % len(libraries))

        hex_digest = digest.encode()
        for name in archive.namelist():
            if not re.match(r'classes\d*\.dex$', name):
                continue
            dex = archive.read(name)
            if pin in dex or hex_digest in dex.lower():
                fail('certificate fingerprint found in %s — it belongs in native code only'
                     % name)
        print('DEX files carry neither the raw pin nor its hex form')

    print('Pengram protection: OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
