#!/usr/bin/env bash
# Запуск TOP-бота: берёт wake-lock (Termux) и не даёт телефону усыпить процесс.
#
#   bash run.sh              # обычный запуск
#   bash run.sh --check      # проверка конфига
#   bash run.sh --print-top  # предпросмотр сообщения
#   bash run.sh --once       # один цикл и выход (для cron/отладки)
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR" || exit 1

if ! command -v python3 >/dev/null 2>&1; then
  echo "[x] python3 не найден. В Termux: pkg install python ca-certificates" >&2
  exit 1
fi

# Не даём Android убить процесс, пока он нужен.
if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock 2>/dev/null || true
fi

if [ ! -f "$DIR/.env" ]; then
  echo "[!] .env не найден — запускаю с переменными окружения."
  echo "    Создайте: cp env.example .env && nano .env"
fi

# UTF-8, чтобы русские имена в логах не превращались в «кракозябры».
export LANG="${LANG:-en_US.UTF-8}"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export PYTHONIOENCODING=utf-8
export PYTHONUNBUFFERED=1

exec python3 "$DIR/bot.py" "$@"
