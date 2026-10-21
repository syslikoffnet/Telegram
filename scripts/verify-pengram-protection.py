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
import hashlib
import re
import struct
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


# Отпечаток в выводе apksigner: 32 байта через двоеточия, но версии выводят
# его по-разному (заголовок, регистр, лишние пробелы) — ищем сами байты.
DIGEST_PATTERN = re.compile(r'(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}')


def parse_signer_digest(output: str) -> str:
    for line in output.splitlines():
        lower = line.lower()
        if 'sha-256' not in lower or 'digest' not in lower:
            continue
        match = DIGEST_PATTERN.search(line)
        if match:
            return match.group(0).replace(':', '').lower()
    # часть версий печатает отпечаток без слова digest в той же строке
    match = DIGEST_PATTERN.search(output)
    if match:
        return match.group(0).replace(':', '').lower()
    raise ValueError('apksigner did not print a SHA-256 digest')


def signer_sha256(apk: Path, apksigner: str, required: bool = True):
    """Отпечаток из apksigner. required=False — вернуть None вместо падения шага."""
    attempts = (
        [apksigner, 'verify', '--print-certs', '-v', str(apk)],
        [apksigner, 'verify', '--print-certs', str(apk)],
    )
    captured = ''
    for command in attempts:
        result = subprocess.run(command, check=False, capture_output=True, text=True)
        combined = (result.stdout or '') + (result.stderr or '')
        captured += combined
        if result.returncode != 0:
            continue
        try:
            return parse_signer_digest(combined)
        except ValueError:
            continue
    # Полный вывод уходит в лог шага, в ::error:: — одна строка: так её видно
    # в аннотации check-run, даже если лог недоступен.
    print(captured)
    summary = ' '.join(captured.split())[:300]
    message = 'apksigner printed no SHA-256 certificate digest (output: %s)' % (summary or 'empty')
    if not required:
        print('::warning::' + message)
        return None
    fail(message)


# --------------------------------------------------------------- v2-подпись
# Отпечаток подписи нужен от самого APK, но парсить текст apksigner ненадёжно:
# формат вывода меняется между build-tools. Читаем блок подписи напрямую —
# схемы v2/v3 хранят DER-сертификат открытым, а отпечаток в рантайме считается
# ровно по этим байтам.

APK_SIG_BLOCK_MAGIC = b'APK Sig Block 42'
V2_SIGNATURE_ID = 0x7109871A
V3_SIGNATURE_ID = 0xF05368C0


def _read_u32(data: bytes, offset: int) -> int:
    return struct.unpack_from('<I', data, offset)[0]


def _read_u64(data: bytes, offset: int) -> int:
    return struct.unpack_from('<Q', data, offset)[0]


def _signing_block(data: bytes) -> bytes:
    """Блок подписи лежит между ZIP-содержимым и центральным каталогом."""
    search_from = max(0, len(data) - 65557)
    eocd = data.rfind(b'PK\x05\x06', search_from)
    if eocd < 0:
        raise ValueError('no EOCD')
    cd_offset = _read_u32(data, eocd + 16)
    if cd_offset < 32 or data[cd_offset - 16:cd_offset] != APK_SIG_BLOCK_MAGIC:
        raise ValueError('no APK signing block')
    size_in_footer = _read_u64(data, cd_offset - 24)
    start = cd_offset - size_in_footer - 8
    if start < 0:
        raise ValueError('broken signing block size')
    if _read_u64(data, start) != size_in_footer:
        raise ValueError('signing block sizes do not match')
    return data[start + 8:cd_offset - 24]


def _pairs(block: bytes):
    offset = 0
    while offset + 12 <= len(block):
        length = _read_u64(block, offset)
        if length < 4 or offset + 8 + length > len(block):
            raise ValueError('broken id-value pair')
        pair_id = _read_u32(block, offset + 8)
        yield pair_id, block[offset + 12:offset + 8 + length]
        offset += 8 + length


def _first_certificate(sequence: bytes) -> bytes:
    """sequence: [u32 length][value]…; первый value — DER-сертификат."""
    if len(sequence) < 4:
        raise ValueError('empty certificate sequence')
    length = _read_u32(sequence, 0)
    if length == 0 or 4 + length > len(sequence):
        raise ValueError('broken certificate length')
    return sequence[4:4 + length]


def _certificate_from_signer(signer: bytes) -> bytes:
    signed_data_len = _read_u32(signer, 0)
    signed_data = signer[4:4 + signed_data_len]
    # signed data: digests, certificates, additional attributes
    digests_len = _read_u32(signed_data, 0)
    certificates_at = 4 + digests_len
    certificates_len = _read_u32(signed_data, certificates_at)
    certificates = signed_data[certificates_at + 4:certificates_at + 4 + certificates_len]
    return _first_certificate(certificates)


def _signers(block: bytes, signature_id: int) -> bytes:
    for pair_id, value in _pairs(block):
        if pair_id != signature_id:
            continue
        count = _read_u32(value, 0)
        length = _read_u32(value, 4)
        signer_start = 8
        signer = value[signer_start:signer_start + length]
        if count < 1 or length == 0 or len(signer) < length:
            raise ValueError('broken signer')
        return signer
    raise ValueError('signature block %d not found' % signature_id)


def apk_signer_sha256(apk: Path) -> str:
    data = apk.read_bytes()
    block = _signing_block(data)
    for signature_id in (V2_SIGNATURE_ID, V3_SIGNATURE_ID):
        try:
            signer = _signers(block, signature_id)
        except ValueError:
            continue
        certificate = _certificate_from_signer(signer)
        return hashlib.sha256(certificate).hexdigest()
    raise ValueError('no v2/v3 signature block')


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

    # Сначала читаем сертификат прямо из блока подписи: это не зависит от
    # версии build-tools. apksigner остаётся проверкой на стороне инструмента.
    try:
        digest = apk_signer_sha256(args.apk)
        print('signer certificate read from the APK signing block')
        tool_digest = signer_sha256(args.apk, args.apksigner, required=False)
        if tool_digest is not None and tool_digest != digest:
            fail('apksigner and the APK signing block disagree on the signer certificate')
    except ValueError as error:
        print('signing block is not readable (%s), falling back to apksigner' % error)
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
