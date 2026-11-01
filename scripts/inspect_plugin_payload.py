#!/usr/bin/env python3
"""inspect_plugin_payload.py — статический разбор обфусцированных блоков
в exteraGram/Turmx-плагинах (*.plugin, base_plugin).

Инструмент НИЧЕГО НЕ ИСПОЛНЯЕТ. Он повторяет "упаковку" вида

    blob -> base85 -> zlib.decompress -> XOR(key) -> compile/exec

но вместо `exec` пишет результат в файл и сканирует его на индикаторы
сетевой активности, чтения сессий/токенов, запуска процессов и т.д.

Использование:
    python3 scripts/inspect_plugin_payload.py payload.txt
    python3 scripts/inspect_plugin_payload.py plugin.plugin --extract-strings
    python3 scripts/inspect_plugin_payload.py payload.txt --out decoded.py --key 3b84656121 3edbf1

payload.txt может содержать HTML-экранированный текст (&lt; &gt; &amp;),
кавычки, переносы строк — всё это нормализуется автоматически.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import html
import os
import re
import sys
import textwrap
import zlib

# Ключ из плагина: _CACHE_KEY = b';\x84ea!>\xdb\xf1'
DEFAULT_KEY = bytes([0x3B, 0x84, 0x65, 0x61, 0x21, 0x3E, 0xDB, 0xF1])

BLOCK_RE = re.compile(
    r"""_CACHE_BLOB\s*=\s*(?P<q>["'])(?P<blob>.*?)(?P=q)""",
    re.S,
)
KEY_RE = re.compile(r"""_CACHE_KEY\s*=\s*(?P<lit>b['"][^'"]*['"])""")

# Индикаторы, по которым помечаем декодированный код.
INDICATORS: list[tuple[str, str]] = [
    (r"\b(?:socket|ssl|http\.client|urllib|requests|aiohttp|httpx)\b", "сетевой ввод-вывод"),
    (r"\b(?:subprocess|os\.system|os\.popen|pty|shutil\.which)\b", "запуск внешних процессов"),
    (r"\b(?:eval|exec|compile|__import__|marshal\.loads)\b", "динамическое исполнение кода"),
    (r"\b(?:base64|binascii|codecs|zlib|bz2|lzma|b85decode|b64decode)\b", "раскодирование данных"),
    (r"\b(?:tdata|key_datas|userconfing|tgnet|session|auth_key|dc_id)\b", "данные сессии Telegram"),
    (r"\b(?:api_id|api_hash|phone_number|auth_key_id|token|bearer|password|cookie)\b", "секреты/учётки"),
    (r"\b(?:getExternalStorage|Environment|open\(|read\(|listdir|walk|glob)\b", "чтение файловой системы"),
    (r"\b(?:https?://|wss?://)\S+", "URL"),
    (r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b", "IP-адрес"),
    (r"\b(?:bot|chat|send)Message\b|send_text\(|messages\.sendMessage", "отправка сообщений"),
    (r"\b(?:getPhoneNumber|getClientUserId|UserConfig|AccountInstance)\b", "доступ к аккаунту"),
    (r"\b(?:getDeviceName|Build\.|getprop|DEVICE|ro\.product)\b", "сбор данных об устройстве"),
    (r"\b(?:contacts|getContacts|PhoneBook|DialogsSearch)\b", "чтение контактов/списка чатов"),
]

SUSPICIOUS_STRING_RE = re.compile(r"""(['"])((?:\\.|(?!\1).){8,200}?)\1""", re.S)


def normalize_blob(text: str) -> str:
    """Убирает HTML-экранирование, пробелы и служебные символы обёртки."""
    # 1) склеиваем переносы ДО unescape: b85-блоки в плагинах разбиты по строкам,
    #    иначе HTML-entity, разрезанная переносом, не распознается.
    text = re.sub(r"\s+", "", text)
    # 2) снимаем HTML-экранирование (&amp; &lt; &gt; и т.п.)
    text = html.unescape(text)
    # 3) убираем обёртку вида (b"...") / "..."; кавычек в алфавите b85 нет.
    for _ in range(2):
        if len(text) > 2 and text[0] in "([" and text[-1] in ")]" and text[1] in "\"'bB":
            text = text[1:-1].strip()
        if len(text) > 2 and text[0] in "\"'" and text[-1] == text[0]:
            text = text[1:-1].strip()
    return re.sub(r"\s+", "", text)


def xor(data: bytes, key: bytes) -> bytes:
    if not key:
        return data
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def _decode_wrappers(normalized: str) -> list[bytes]:
    """Пробуем разные кодировки обёртки: b85 (как в плагине), base64, a85."""
    data = normalized.encode("ascii", "ignore")
    out: list[bytes] = []
    for name, fn in (
        ("b85", base64.b85decode),
        ("b64", base64.b64decode),
        ("a85", base64.a85decode),
    ):
        try:
            out.append(fn(data))
        except (binascii.Error, ValueError):
            continue
    return out


def _inflate(raw: bytes) -> bytes:
    try:
        return zlib.decompress(raw)
    except zlib.error:
        # gzip / raw deflate
        return zlib.decompress(raw, 47)


def unpack(blob: str, key: bytes) -> bytes:
    normalized = normalize_blob(blob)
    errors: list[str] = []
    for raw in _decode_wrappers(normalized):
        try:
            return xor(_inflate(raw), key)
        except (zlib.error, ValueError) as exc:
            errors.append(str(exc))
    raise ValueError("; ".join(errors) or "не удалось раскодировать обёртку")


def parse_key_literal(lit: str) -> bytes:
    """b';\\x84ea!>\\xdb\\xf1' -> bytes"""
    body = lit.strip()
    if body[:1] in ("b", "B"):
        body = body[1:]
    if body[:1] in ("'", '"'):
        body = body[1:-1]
    out = bytearray()
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            if nxt == "x" and i + 4 <= len(body):
                try:
                    out.append(int(body[i + 2 : i + 4], 16))
                    i += 4
                    continue
                except ValueError:
                    pass
            escapes = {"n": 10, "r": 13, "t": 9, "0": 0, "\\": 92, "'": 39, '"': 34}
            if nxt in escapes:
                out.append(escapes[nxt])
                i += 2
                continue
            out.append(ord(nxt))
            i += 2
            continue
        out.append(ord(ch))
        i += 1
    return bytes(out)


def find_blobs(source: str) -> list[tuple[str, bytes]]:
    """Ищет пары _CACHE_KEY/_CACHE_BLOB (и похожие имена) в исходнике плагина."""
    key = DEFAULT_KEY
    m = KEY_RE.search(source)
    if m:
        try:
            key = parse_key_literal(m.group("lit"))
        except Exception:
            pass
    blobs = []
    for m in BLOCK_RE.finditer(source):
        blobs.append((m.group("blob"), key))
    if not blobs:
        # запасной вариант: ищем самую длинную строку из b85-алфавита
        for cand in re.findall(r"""["']([0-9A-Za-z!#$%&()*+\-;<=>?@^_`{|}~]{3000,})["']""", source):
            blobs.append((cand, key))
    return blobs


def scan(code: str) -> list[tuple[str, int, str]]:
    hits: list[tuple[str, int, str]] = []
    lines = code.splitlines()
    for pattern, label in INDICATORS:
        rx = re.compile(pattern, re.I)
        for idx, line in enumerate(lines, 1):
            if rx.search(line):
                hits.append((label, idx, line.strip()[:200]))
    return hits


def strings(code: str, min_len: int = 6) -> list[str]:
    found = {m.group(2) for m in SUSPICIOUS_STRING_RE.finditer(code)}
    return sorted((s for s in found if len(s) >= min_len), key=lambda s: (len(s), s))


def print_report(name: str, code: str, out_path: str | None, show_strings: bool) -> None:
    print(f"\n=== {name} ===")
    print(f"размер декодированного кода: {len(code)} байт, {code.count(chr(10)) + 1} строк")
    if out_path:
        print(f"сохранено в: {out_path} (НЕ импортировать и не запускать)")

    heads = [l for l in code.splitlines() if re.match(r"\s*(import|from)\s+\S+", l)]
    print("\n-- импорты --")
    for h in heads[:40]:
        print("   " + h.strip())
    if not heads:
        print("   (нет)")

    hits = scan(code)
    grouped: dict[str, list[tuple[int, str]]] = {}
    for label, line_no, snippet in hits:
        grouped.setdefault(label, []).append((line_no, snippet))

    print("\n-- индикаторы --")
    if not grouped:
        print("   (ничего не найдено)")
    for label, items in sorted(grouped.items(), key=lambda kv: -len(kv[1])):
        print(f"   [{label}]  {len(items)} совпадений")
        for line_no, snippet in items[:6]:
            print(f"      :{line_no}: {snippet}")
        if len(items) > 6:
            print(f"      ... ещё {len(items) - 6}")

    if show_strings:
        print("\n-- строковые литералы --")
        for s in strings(code)[:120]:
            print("   " + repr(s))


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Статический декодер payload'ов из .plugin (без exec).")
    ap.add_argument("path", help="файл с BLOB или с исходником плагина")
    ap.add_argument("--out", help="куда сохранить декодированный код (по умолчанию рядом с входным файлом)")
    ap.add_argument("--key", nargs="*", default=None, help="ключ XOR как hex-байты, напр. 3b 84 65 61 21 3e db f1")
    ap.add_argument("--extract-strings", action="store_true", help="показать строковые литералы")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.path):
        print(f"нет такого файла: {args.path}", file=sys.stderr)
        return 2

    source = open(args.path, "r", encoding="utf-8", errors="replace").read()
    key = bytes(int(x, 16) for x in args.key) if args.key else None

    explicit = bool(re.search(r"_CACHE_BLOB\s*=", html.unescape(source)))
    blobs = find_blobs(source)
    if not blobs:
        text = normalize_blob(source)
        if len(text) >= 100:
            blobs = [(text, key or DEFAULT_KEY)]
    if not blobs:
        print("не нашёл ни _CACHE_BLOB, ни подходящей длинной строки", file=sys.stderr)
        return 1

    base_out = args.out or (os.path.splitext(args.path)[0] + ".decoded.py")
    rc = 0
    decoded = 0
    for i, (blob, blob_key) in enumerate(blobs):
        use_key = key or blob_key
        try:
            code_bytes = unpack(blob, use_key)
            code = code_bytes.decode("utf-8")
        except (binascii.Error, ValueError, zlib.error, UnicodeDecodeError) as exc:
            hint = "" if explicit else " (похоже, обфусцированного блока здесь нет)"
            print(f"[{i}] не удалось распаковать этим ключом: {exc}{hint}", file=sys.stderr)
            rc = 1
            continue
        decoded += 1

        out_path = base_out if len(blobs) == 1 else base_out.replace(".py", f".{i}.py")
        with open(out_path, "w", encoding="utf-8") as fh:
            fh.write("# СТАТИЧЕСКИ ДЕКОДИРОВАНО. НЕ ИСПОЛНЯТЬ.\n")
            fh.write("# Источник: %s, блок %d\n\n" % (args.path, i))
            fh.write(code)
        print_report(f"блок {i}", code, out_path, args.extract_strings)

    if not decoded:
        print("\nОбфусцированный payload не найден или ключ не подходит. "
              "Укажи ключ вручную: --key 3b 84 65 61 21 3e db f1", file=sys.stderr)
    return rc if decoded else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
