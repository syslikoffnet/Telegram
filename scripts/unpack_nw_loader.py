#!/usr/bin/env python3
"""unpack_nw_loader.py — статический распаковщик «защищённого» блока из плагинов
exteraGram/Turmx вида:

    def _x7a2(self): ...            # запускает _x7a3 в потоке
    def _x7a3(self):
        _ik = sha256(bytes([...]) + __id__.encode())      # ключ привязан к __id__
        _tab = (...); _idx = (...); _ms = ...
        _m[t] = (_tab[_idx[t]] - _ms) & 255; _k = _m ^ _ik
        _enc = ('...' + '...' + ...)                      # base64 в своём алфавите
        if sha256(_k + _n + _ct) != _mac: return          # проверка целостности
        _src = zlib.decompress(xor_keystream(_ct, _k, _n))
        exec(compile(_src, "<n>", "exec"), globals())     # <-- вот тут исполняется код

Скрипт повторяет ТОЛЬКО расшифровку: ключ, XOR-keystream, zlib. Ничего не
исполняется — результат пишется в *.decoded.py и сканируется на индикаторы.

Использование:
    python3 scripts/unpack_nw_loader.py plugin.plugin
    python3 scripts/unpack_nw_loader.py plugin.plugin --out hidden.py --extract-strings
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import os
import re
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    from inspect_plugin_payload import print_report  # общий формат отчёта + индикаторы
except Exception:  # pragma: no cover - инструмент должен работать и в одиночку
    print_report = None

NUM_LIST_RE = re.compile(r"\((?:\s*\d+\s*,)+\s*\d*\s*\)|\[(?:\s*\d+\s*,)+\s*\d*\s*\]")


class UnpackError(Exception):
    pass


def _ints(chunk: str) -> list[int]:
    return [int(x) for x in re.findall(r"\d+", chunk)]


def _find_ints(source: str, name: str) -> list[int] | None:
    m = re.search(name + r"\s*=\s*(\(|\[)([^\])]*)[)\]]", source)
    if not m:
        return None
    return _ints(m.group(2))


def parse(source: str) -> dict:
    """Достаёт из исходника все части схемы. Ничего не исполняет."""
    plugin_id = ""
    m = re.search(r"""__id__\s*=\s*["']([^"']+)["']""", source)
    if m:
        plugin_id = m.group(1)

    tab = _find_ints(source, r"_tab")
    idx = _find_ints(source, r"_idx")
    if not tab or not idx:
        # запасной путь: таблица — самая длинная последовательность чисел, индексы — 32 числа
        cands = [(_ints(c), c) for c in NUM_LIST_RE.findall(source)]
        big = max(cands, key=lambda kv: len(kv[0]), default=None)
        if big is None:
            raise UnpackError("не нашёл таблицу подстановки (_tab / _idx)")
        tab = big[0]
        idx = next((n for n, _ in cands if len(n) == 32 and all(i < len(tab) for i in n)), None)
        if not idx:
            raise UnpackError("не нашёл таблицу индексов (_idx)")

    ms = 0
    m = re.search(r"_ms\s*=\s*(-?\d+)", source)
    if m:
        ms = int(m.group(1))

    abc = None
    m = re.search(r"""_abc\s*=\s*["']([A-Za-z0-9+/=]+)["']""", source)
    if m:
        abc = m.group(1)
    std = None
    m = re.search(r"""_std\s*=\s*["']([A-Za-z0-9+/=]+)["']""", source)
    if m:
        std = m.group(1)

    payload = None
    m = re.search(r"_enc\s*=\s*\((.*?)\)\s*\n", source, re.S)
    if m:
        payload = "".join(re.findall(r"""["']([^"']*)["']""", m.group(1)))
    if not payload:
        longest = max(re.findall(r"""["']([^"'\n]{500,})["']""", source), key=len, default=None)
        if not longest:
            raise UnpackError("не нашёл зашифрованный блок (_enc)")
        payload = longest

    # nonce и mac: ищем по именам, иначе — по длине литерала
    nonce = _find_ints(source, r"_n")
    m = re.search(r"_n\s*=\s*bytes\(\(([^)]*)\)\)", source)
    if m:
        nonce = _ints(m.group(1))
    mac = None
    m = re.search(r"_mac\s*=\s*bytes\(\(([^)]*)\)\)", source)
    if m:
        mac = bytes(_ints(m.group(1)))
    if nonce is None or len(nonce) != 16:
        nonce = None
    if mac is None or len(mac) != 32:
        mac = None

    pepper = None
    m = re.search(r"bytes\(\[([\d,\s]+)\]\)\s*\+\s*_id\.encode\(\)", source)
    if m:
        pepper = bytes(_ints(m.group(1)))

    return {
        "id": plugin_id,
        "tab": tab,
        "idx": idx,
        "ms": ms,
        "abc": abc,
        "std": std or "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",
        "payload": payload,
        "nonce": bytes(nonce) if nonce else None,
        "mac": mac,
        "pepper": pepper,
    }


def derive_key(cfg: dict) -> bytes:
    if not cfg["pepper"]:
        raise UnpackError("не нашёл соль для ключа (bytes([...]) + _id.encode())")
    ik = hashlib.sha256(cfg["pepper"] + cfg["id"].encode()).digest()
    out = bytearray(32)
    for t in range(32):
        if t >= len(cfg["idx"]) or cfg["idx"][t] >= len(cfg["tab"]):
            raise UnpackError("индекс ключевой таблицы вышел за границы")
        out[t] = (cfg["tab"][cfg["idx"][t]] - cfg["ms"] & 0xFF) & 0xFF
    return bytes((out[t] ^ ik[t]) & 0xFF for t in range(32))


def decode_body(cfg: dict) -> bytes:
    if cfg["abc"]:
        table = str.maketrans(cfg["abc"], cfg["std"])
        b64 = cfg["payload"].translate(table)
    else:
        b64 = cfg["payload"]
    b64 += "=" * (-len(b64) % 4)
    try:
        return base64.b64decode(b64, validate=False)
    except (binascii.Error, ValueError) as exc:
        raise UnpackError("зашифрованный блок не декодируется как base64: %s" % exc)


def keystream_xor(data: bytes, key: bytes, nonce: bytes) -> bytes:
    out = bytearray(len(data))
    i = 0
    while i < len(data):
        block = hashlib.sha256(key + nonce + i.to_bytes(4, "little")).digest()
        for j in range(min(32, len(data) - i)):
            out[i + j] = data[i + j] ^ block[j]
        i += 32
    return bytes(out)


def unpack(source: str) -> tuple[bytes, dict]:
    cfg = parse(source)
    key = derive_key(cfg)
    ct = decode_body(cfg)
    if cfg["mac"]:
        if hashlib.sha256(key + cfg["nonce"] + ct).digest() != cfg["mac"]:
            raise UnpackError(
                "не сходится контрольный хэш (неверный __id__, повреждённый файл "
                "или другая версия схемы)"
            )
    elif cfg["nonce"] is None:
        raise UnpackError("не нашёл nonce (_n)")
    plain = keystream_xor(ct, key, cfg["nonce"])
    try:
        return zlib.decompress(plain), cfg
    except zlib.error:
        try:
            return zlib.decompress(plain, 47), cfg
        except zlib.error as exc:
            raise UnpackError("zlib не распаковал расшифрованные данные: %s" % exc)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Статический распаковщик скрытого блока плагина (без exec).")
    ap.add_argument("path", help="файл .plugin / .py с блоком _x7a2")
    ap.add_argument("--out", help="куда сохранить расшифрованный код")
    ap.add_argument("--extract-strings", action="store_true", help="показать строковые литералы")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.path):
        print("нет такого файла: %s" % args.path, file=sys.stderr)
        return 2

    source = open(args.path, "r", encoding="utf-8", errors="replace").read()
    try:
        plain, cfg = unpack(source)
        code = plain.decode("utf-8")
    except (UnpackError, UnicodeDecodeError) as exc:
        print("не удалось распаковать: %s" % exc, file=sys.stderr)
        return 1

    out_path = args.out or (os.path.splitext(args.path)[0] + ".decoded.py")
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("# СТАТИЧЕСКИ РАСШИФРОВАНО (%s). НЕ ИСПОЛНЯТЬ.\n" % (cfg["id"] or "plugin"))
        fh.write("# Источник: %s\n\n" % args.path)
        fh.write(code)

    print("плагин: %s" % (cfg["id"] or "?"))
    print("расшифровано: %d байт, строк: %d" % (len(code), code.count("\n") + 1))
    print("сохранено в: %s (НЕ импортировать и не запускать)" % out_path)
    if print_report:
        print_report("скрытый блок", code, None, args.extract_strings)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
