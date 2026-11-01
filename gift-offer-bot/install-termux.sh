#!/usr/bin/env bash
# Установка Gift Offer Bot в Termux (и в любой Linux) одной командой:
#   curl -fsSL -o install-termux.sh https://raw.githubusercontent.com/syslikoffnet/Telegram/arena/f69ca5ad-telegram/gift-offer-bot/install-termux.sh
#   bash install-termux.sh
#
# Скрипт НЕ трогает существующие .env и offers.db, токен не запрашивает:
# его вписывают отдельной командой --set-token.
set -eu

REPO="${REPO:-syslikoffnet/Telegram}"
BRANCH="${BRANCH:-arena/f69ca5ad-telegram}"
BOT_DIR="${BOT_DIR:-$HOME/gift-offer-bot}"
RAW="${RAW_BASE:-https://raw.githubusercontent.com/${REPO}/${BRANCH}/gift-offer-bot}"

say() { printf '%s\n' "$*"; }
warn() { printf '[!] %s\n' "$*" >&2; }
die() { printf '[x] %s\n' "$*" >&2; exit 1; }

need_curl() {
    command -v curl >/dev/null 2>&1 || die "Нет curl. Выполните: pkg install curl"
}

fetch() { # fetch <url> <dest>
    mkdir -p "$(dirname "$2")"
    curl -fsSL --retry 3 --retry-delay 2 -o "$2" "$1" || die "Не скачалось: $1"
}

command -v python3 >/dev/null 2>&1 || die "Нет python3. Выполните: pkg install python"
need_curl

say "== Gift Offer Bot: установка =="
say "папка:  $BOT_DIR"
say "ветка:  $BRANCH"
say ""

mkdir -p "$BOT_DIR"
cd "$BOT_DIR"

say "Скачиваю файлы…"
fetch "$RAW/bot.py" "bot.py"
fetch "$RAW/env.example" "env.example"
fetch "$RAW/README.md" "README.md"
fetch "$RAW/run-tests.sh" "run-tests.sh"
fetch "$RAW/run.sh" "run.sh"
fetch "$RAW/tests/test_bot.py" "tests/test_bot.py"
# имя файла с кириллицей скачиваем отдельно, чтобы не зависеть от локали
fetch "$RAW/%D0%9A%D0%90%D0%9A-%D0%97%D0%90%D0%9F%D0%A3%D0%A1%D0%A2%D0%98%D0%A2%D0%AC.txt" "КАК-ЗАПУСТИТЬ.txt"

chmod +x bot.py run-tests.sh run.sh 2>/dev/null || true

if [ -f .env ]; then
    say ""
    say "Файл .env уже есть — не трогаю."
    chmod 600 .env 2>/dev/null || true
else
    cp env.example .env
    chmod 600 .env
    say ""
    say "Создан .env с ПУСТЫМ токеном (права 600)."
fi

say ""
python3 - <<'PY'
import re
from pathlib import Path
text = Path(".env").read_text(encoding="utf-8")
m = re.search(r"^BOT_TOKEN=(.*)$", text, re.MULTILINE)
token = (m.group(1).strip() if m else "")
print("Токен в .env: " + ("уже вписан" if token else "пустой"))
PY

cat <<EOF

== Готово. Осталось 3 шага ==

1) Впишите токен от @BotFather (файл станет 600, токен проверится):
     cd "$BOT_DIR"
     python3 bot.py --set-token '123456789:AAH...'

2) Проверьте настройки и базу:
     python3 bot.py --check
     python3 bot.py --selftest

3) Запустите:
     nohup python3 bot.py >> bot.log 2>&1 &
     termux-wake-lock
     tail -f bot.log

Остановить:  pkill -f 'python3 bot.py'
Инструкция:  $BOT_DIR/README.md и КАК-ЗАПУСТИТЬ.txt
EOF
