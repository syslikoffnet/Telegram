#!/usr/bin/env bash
# Все проверки без сети/токена: юнит-тесты + встроенный selftest.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"
python3 -m unittest discover -s tests -v
python3 bot.py --selftest
