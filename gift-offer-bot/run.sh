#!/usr/bin/env bash
# Запуск бота в переднем плане. Для фона: nohup ./run.sh >> bot.log 2>&1 &
set -eu
cd "$(dirname "$0")" || exit 1
exec python3 bot.py "$@"
