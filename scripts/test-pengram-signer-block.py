#!/usr/bin/env python3
"""Отпечаток подписи читается из самого APK, а не из текста apksigner.

Формат вывода apksigner меняется между build-tools, и CI уже падал на разборе
строки «Signer #1 certificate SHA-256 digest». Здесь проверяется собственный
парсер блока подписи (схема v2): собираем синтетический APK с известным
сертификатом и убеждаемся, что отпечаток совпадает с SHA-256 его DER.
"""

import hashlib
import importlib.util
import io
import struct
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
script = root / 'scripts/verify-pengram-protection.py'
spec = importlib.util.spec_from_file_location('verify', script)
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)

MAGIC = b'APK Sig Block 42'


def length_prefixed(value: bytes) -> bytes:
    return struct.pack('<I', len(value)) + value


def build_v2_signer(certificate: bytes) -> bytes:
    digests = length_prefixed(b'\x00' * 32)           # последовательность из одного элемента
    certificates = length_prefixed(certificate)       # ровно один DER-сертификат
    signed_data = length_prefixed(digests) + length_prefixed(certificates) + length_prefixed(b'')
    signatures = length_prefixed(length_prefixed(b'\x00' * 64))
    public_key = length_prefixed(b'\x00' * 64)
    return length_prefixed(signed_data) + length_prefixed(signatures) + public_key


def signing_block(certificate: bytes) -> bytes:
    signers = length_prefixed(length_prefixed(build_v2_signer(certificate)))
    pairs = struct.pack('<QI', len(signers) + 4, verify.V2_SIGNATURE_ID) + signers
    body = pairs
    size = len(body) + 8 + 16
    return struct.pack('<Q', size) + body + struct.pack('<Q', size) + MAGIC


def build_apk(path: Path, certificate: bytes) -> None:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_STORED) as archive:
        archive.writestr('AndroidManifest.xml', b'manifest')
        archive.writestr('classes.dex', b'dex\n035\x00' + b'\x00' * 64)
    raw = bytearray(buffer.getvalue())

    eocd = raw.rfind(b'PK\x05\x06')
    cd_offset = struct.unpack_from('<I', raw, eocd + 16)[0]
    block = signing_block(certificate)
    raw = raw[:cd_offset] + block + raw[cd_offset:]
    eocd += len(block)
    struct.pack_into('<I', raw, eocd + 16, cd_offset + len(block))
    path.write_bytes(bytes(raw))


def main() -> int:
    certificate = bytes(range(64)) * 4          # условный DER, важен только хеш
    expected = hashlib.sha256(certificate).hexdigest()
    target = Path('/tmp/pengram-signer-block.apk')
    build_apk(target, certificate)

    digest = verify.apk_signer_sha256(target)
    assert digest == expected, (digest, expected)

    # без магии блока отпечаток брать неоткуда — парсер обязан отказаться
    broken = bytearray(target.read_bytes())
    eocd = broken.rfind(b'PK\x05\x06')
    cd_offset = struct.unpack_from('<I', broken, eocd + 16)[0]
    broken[cd_offset - 1] ^= 0xFF
    target.write_bytes(bytes(broken))
    try:
        verify.apk_signer_sha256(target)
    except ValueError:
        pass
    else:
        raise AssertionError('broken signing block was accepted')

    # v3 тоже читается, если v2 нет
    certificate2 = bytes(reversed(range(64))) * 2
    signers = length_prefixed(length_prefixed(build_v2_signer(certificate2)))
    pairs = struct.pack('<QI', len(signers) + 4, verify.V3_SIGNATURE_ID) + signers
    body = pairs
    size = len(body) + 8 + 16
    block = struct.pack('<Q', size) + body + struct.pack('<Q', size) + MAGIC
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_STORED) as archive:
        archive.writestr('classes.dex', b'dex\n035\x00')
    raw = bytearray(buffer.getvalue())
    eocd = raw.rfind(b'PK\x05\x06')
    cd_offset = struct.unpack_from('<I', raw, eocd + 16)[0]
    raw = raw[:cd_offset] + block + raw[cd_offset:]
    eocd += len(block)
    struct.pack_into('<I', raw, eocd + 16, cd_offset + len(block))
    target.write_bytes(bytes(raw))
    assert verify.apk_signer_sha256(target) == hashlib.sha256(certificate2).hexdigest()

    print('signer block: v2/v3 certificates are read from the APK itself: OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
