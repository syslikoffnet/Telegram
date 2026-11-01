#!/usr/bin/env python3
"""scan_nw_loader_iocs.py — поиск индикаторов семейства «nw» (скрытый exec-загрузчик
и RCE-агент) в файлах плагинов. Ничего не исполняет и ничего не «лечит» — только читает.

Использование:
    python3 scripts/scan_nw_loader_iocs.py ~/plugins
    python3 scripts/scan_nw_loader_iocs.py plugin.plugin --json
    python3 scripts/scan_nw_loader_iocs.py dir --ext .plugin .py .elyx

Код возврата: 0 — чисто, 1 — найдены индикаторы, 2 — ошибка ввода.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

# (регулярка/строка, семейство, severity, человекочитаемое описание)
IOCS: list[tuple[str, str, str, str]] = [
    # C2 и протокол
    (r"5\.129\.236\.207", "c2", "critical", "адрес C2-сервера 5.129.236.207"),
    (r"/api/v1/rt\b", "c2", "critical", "эндпоинт приёма команд (exec кода с сервера)"),
    (r"/api/v1/plugins/companions", "c2", "critical", "список компаньонов с C2"),
    (r"/api/v1/plugins/file/", "c2", "critical", "скачивание плагинов с C2"),
    (r"X-Plugin-Token|X-Owner-Id", "c2", "critical", "служебные заголовки оператора"),
    (r"nw_buyer_[A-Za-z0-9_\-]{6,}", "c2", "critical", "токен сборки nw_buyer_*"),
    # персистентность
    (r"nw_keep", "persistence", "critical", "бэкап плагинов в files/nw_keep"),
    (r"nw:keepalive", "persistence", "high", "PARTIAL_WAKE_LOCK для работы в фоне"),
    (r"_NW_GUARD_STARTED|nw_guard|nw_fwatch", "persistence", "critical", "потоки-хранители"),
    (r"loadPluginFromFile\s*\(\s*_local", "persistence", "critical", "загрузка скачанного .plugin"),
    (r"setPluginEnabled\s*\(", "persistence", "medium", "принудительное включение плагинов"),
    (r"""["']nw0[0-9]["']|%s%02d|nw%02d""", "persistence", "high", "компаньоны nw00..nw99"),
    (r"nw_bak|/\.did\b|filesDir.*\.did", "persistence", "high", "идентификатор устройства .did"),
    # скрытый код
    (r"_NW_SRC", "nw_loader", "critical", "расшифрованный payload в глобале"),
    (r"def _x7a[0-9]\s*\(", "nw_loader", "critical", "блок _x7a2/_x7a3 (скрытый exec)"),
    (r"_CACHE_BLOB|_CACHE_KEY", "cache_loader", "critical", "блок _CACHE_BLOB (b85+zlib+xor)"),
    (r"_ld_start\s*\(", "cache_loader", "high", "запуск старого загрузчика _ld_start"),
    (r"exec\s*\(\s*compile\s*\(", "exec", "high", "exec(compile(...)) — исполнение кода из данных"),
]

SEVERITY_ORDER = {"critical": 0, "high": 1, "medium": 2}


def iter_files(root: str, exts: tuple[str, ...]):
    if os.path.isfile(root):
        yield root
        return
    for base, _dirs, names in os.walk(root):
        for name in names:
            if name.lower().endswith(exts):
                yield os.path.join(base, name)


def scan_file(path: str) -> list[dict]:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError:
        return []

    import re

    hits: list[dict] = []
    seen: set[tuple[str, str]] = set()
    for pattern, family, severity, note in IOCS:
        for m in re.finditer(pattern, text):
            line = text.count("\n", 0, m.start()) + 1
            key = (family, note)
            if key in seen:
                break
            seen.add(key)
            hits.append(
                {
                    "file": path,
                    "line": line,
                    "family": family,
                    "severity": severity,
                    "note": note,
                    "match": text[m.start() : m.end()][:80],
                }
            )
            break
    return hits


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Поиск IOC семейства nw в плагинах (только чтение).")
    ap.add_argument("path", help="файл или каталог")
    ap.add_argument("--ext", nargs="*", default=[".plugin", ".py", ".elyx", ".txt", ".json"],
                    help="расширения файлов при обходе каталога")
    ap.add_argument("--json", action="store_true", help="машиночитаемый вывод")
    args = ap.parse_args(argv)

    if not os.path.exists(args.path):
        print("нет такого пути: %s" % args.path, file=sys.stderr)
        return 2

    exts = tuple(args.ext)
    all_hits: list[dict] = []
    scanned = 0
    for path in iter_files(args.path, exts):
        scanned += 1
        all_hits.extend(scan_file(path))

    all_hits.sort(key=lambda h: (SEVERITY_ORDER.get(h["severity"], 9), h["file"], h["line"]))
    families = sorted({h["family"] for h in all_hits})

    if args.json:
        print(json.dumps({"scanned": scanned, "families": families, "hits": all_hits}, ensure_ascii=False, indent=2))
    else:
        print("проверено файлов: %d" % scanned)
        if not all_hits:
            print("индикаторов не найдено")
        else:
            print("НАЙДЕНО индикаторов: %d, семейства: %s" % (len(all_hits), ", ".join(families)))
            for h in all_hits:
                print("  [%s] %s:%d  %s  (%s)" % (h["severity"], h["file"], h["line"], h["note"], h["family"]))
            if {"c2", "persistence"} & set(families):
                print("\nВНИМАНИЕ: это RCE-агент с персистентностью. Порядок действий —")
                print("docs/comfort-lyrics-malware.md, раздел «Удаление и восстановление».")

    return 1 if any(h["severity"] in ("critical", "high") for h in all_hits) else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
