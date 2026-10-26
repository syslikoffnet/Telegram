#!/usr/bin/env python3
"""Реальные дубли сигнатур методов в изменённых файлах (ловит доjavac-класс
'already defined', который javalang-синтаксис и статус-сьюты не видят).
Ложные: varargs-коллизии — фильтруются по varargs/арности."""
import io, os, subprocess, sys
from collections import Counter
import javalang

out = subprocess.run(['git', 'diff', '--name-only', 'HEAD~20..HEAD'], capture_output=True, text=True).stdout
files = [p for p in out.splitlines() if p.endswith('.java') and os.path.exists(p)]
dups = []
for p in files:
    try:
        tree = javalang.parse.parse(io.open(p, encoding='utf-8').read())
    except Exception:
        continue
    for _, node in tree.filter(javalang.tree.TypeDeclaration):
        sigs = Counter()
        for m in getattr(node, 'methods', []) or []:
            params = tuple(
                ('varargs' if getattr(x, 'varargs', False) else str(x.type)) + '[]' * (1 if getattr(x, 'varargs', False) else 0)
                for x in (m.parameters or []))
            sigs[(m.name, len(params), params)] += 1
        for sig, c in sigs.items():
            if c > 1:
                dups.append(f'{p} :: {node.name}.{sig[0]}{sig[2]} x{c}')
if dups:
    print('ДУБЛИ МЕТОДОВ:')
    print('\n'.join(dups))
    sys.exit(1)
print(f'dup-methods: OK ({len(files)} java, 0 дублей)')
