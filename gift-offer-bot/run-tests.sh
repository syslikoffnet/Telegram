#!/usr/bin/env bash
# Тесты Gift Offer Bot. Сеть не нужна.
set -u
cd "$(dirname "$0")" || exit 1

PY=python3
command -v "$PY" >/dev/null 2>&1 || PY=python
if ! command -v "$PY" >/dev/null 2>&1; then
    echo "Python 3 не найден. Termux: pkg install python" >&2
    exit 1
fi

echo "== Версия Python =="
"$PY" --version

echo
echo "== Юнит-тесты =="
"$PY" -m unittest discover -s tests -v
UNIT=$?

echo
echo "== Офлайн-сценарий (--selftest) =="
"$PY" bot.py --selftest
SELF=$?

echo
if [ "$UNIT" -eq 0 ] && [ "$SELF" -eq 0 ]; then
    echo "ВСЁ ЗЕЛЁНОЕ."
    exit 0
fi
echo "ЕСТЬ ПАДЕНИЯ: unit=$UNIT selftest=$SELF" >&2
exit 1
