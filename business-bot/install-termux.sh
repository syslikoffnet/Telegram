#!/usr/bin/env bash
# ============================================================================
#  Установщик TOP-бота для Termux (Android).
#
#  Запуск (из папки business-bot):
#      bash install-termux.sh
#
#  Что делает:
#    1. проверяет, что это Termux (а не обычный Linux);
#    2. ставит python + ca-certificates (и tmux/nano, если нет);
#    3. копирует бота в ~/top-bot;
#    4. спрашивает токен и создаёт ~/top-bot/.env с правами 600;
#    5. проверяет сертификаты и токен (getMe);
#    6. готовит автозапуск для Termux:Boot.
#
#  Скрипт идемпотентный: повторный запуск не затрёт .env и ваш top.json.
# ============================================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${TARGET_DIR:-$HOME/top-bot}"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ok\033[0m  %s\n' "$*"; }
warn() { printf '\033[1;33m  !!\033[0m  %s\n' "$*"; }
err()  { printf '\033[1;31m  xx\033[0m  %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
say "1/6 Проверяю окружение"
# ---------------------------------------------------------------------------
if [ -n "${PREFIX:-}" ] && printf '%s' "$PREFIX" | grep -q 'com.termux'; then
  ok "Termux: $PREFIX"
  IS_TERMUX=1
else
  IS_TERMUX=0
  warn "Это не Termux. Скрипт всё равно поставит файлы, но шаги с pkg/wake-lock пропущены."
  warn "Для обычного Linux/VPS используйте README, раздел «VPS и systemd»."
fi

if [ ! -f "$SCRIPT_DIR/bot.py" ]; then
  err "Рядом нет bot.py. Запускайте install-termux.sh из папки business-bot."
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  if [ "$IS_TERMUX" = "1" ]; then
    say "Python не найден — устанавливаю"
    pkg update -y || warn "pkg update завершился с ошибкой, продолжаю"
    pkg install -y python ca-certificates || {
      err "Не удалось поставить python. Проверьте интернет и зеркала: termux-change-repo"
      exit 1
    }
  else
    err "Нет python3. Установите: sudo apt install python3"
    exit 1
  fi
fi

PY_OK=$(python3 -c 'import sys; print(1 if sys.version_info >= (3, 9) else 0)' 2>/dev/null || echo 0)
if [ "$PY_OK" != "1" ]; then
  err "Нужен Python 3.9+. Сейчас: $(python3 -V 2>&1). Обновите: pkg upgrade python"
  exit 1
fi
ok "Python: $(python3 -V 2>&1)"

if [ "$IS_TERMUX" = "1" ]; then
  for pkgname in ca-certificates; do
    pkg list-installed 2>/dev/null | grep -q "^$pkgname/" || pkg install -y "$pkgname" || warn "не смог поставить $pkgname"
  done
  command -v tmux >/dev/null 2>&1 || warn "tmux не установлен (для фонового режима: pkg install tmux)"
  command -v nano >/dev/null 2>&1 || warn "nano не установлен (для правки .env: pkg install nano)"
fi

# ---------------------------------------------------------------------------
say "2/6 Копирую файлы в $TARGET"
# ---------------------------------------------------------------------------
mkdir -p "$TARGET" || { err "Не могу создать $TARGET"; exit 1; }
cp -f "$SCRIPT_DIR/bot.py" "$TARGET/bot.py" || { err "Не скопировал bot.py"; exit 1; }
[ -f "$SCRIPT_DIR/README.md" ] && cp -f "$SCRIPT_DIR/README.md" "$TARGET/README.md"
[ -f "$SCRIPT_DIR/ANALYSIS.md" ] && cp -f "$SCRIPT_DIR/ANALYSIS.md" "$TARGET/ANALYSIS.md"
[ -f "$SCRIPT_DIR/env.example" ] && cp -f "$SCRIPT_DIR/env.example" "$TARGET/env.example"
[ -f "$SCRIPT_DIR/run.sh" ] && cp -f "$SCRIPT_DIR/run.sh" "$TARGET/run.sh" && chmod +x "$TARGET/run.sh"

if [ -f "$TARGET/top.json" ]; then
  ok "top.json уже есть — не трогаю (ваш список сохранён)"
else
  cp -f "$SCRIPT_DIR/top.json" "$TARGET/top.json" 2>/dev/null || printf '{"title":"ТОП","entries":["Misha","Misha","misha"]}\n' > "$TARGET/top.json"
  ok "Создан top.json"
fi
chmod +x "$TARGET/bot.py" 2>/dev/null || true

# ---------------------------------------------------------------------------
say "3/6 Токен бота"
# ---------------------------------------------------------------------------
TOKEN="${BOT_TOKEN:-}"
if [ -s "$TARGET/.env" ] && grep -q '^BOT_TOKEN=.\+' "$TARGET/.env"; then
  ok "В $TARGET/.env уже есть BOT_TOKEN — оставляю как есть"
else
  if [ -z "$TOKEN" ]; then
    printf '\nТокен берётся у @BotFather: /newbot → скопируйте строку вида 123456789:AAH...\n'
    printf 'Вставьте токен и нажмите Enter (ввод не отображается): '
    stty -echo 2>/dev/null || true
    IFS= read -r TOKEN
    stty echo 2>/dev/null || true
    printf '\n'
  fi
  TOKEN="$(printf '%s' "$TOKEN" | tr -d '[:space:]')"
  if ! printf '%s' "$TOKEN" | grep -Eq '^[0-9]{5,}:[A-Za-z0-9_-]{20,}$'; then
    err "Токен не похож на настоящий. Ожидается 123456789:AAH... (без пробелов и кавычек)."
    err "Файл .env не создан. Получите токен заново: @BotFather → /mybots → API Token."
    exit 1
  fi
  {
    printf 'BOT_TOKEN=%s\n' "$TOKEN"
    printf 'MODE=auto\n'
    printf 'TOP_FILE=top.json\n'
    printf 'COMMAND=/top\n'
    printf 'DELETE_COMMAND=true\n'
    printf 'COOLDOWN_SECONDS=3\n'
    printf 'MAX_TOPS_PER_MINUTE=6\n'
    printf 'POLL_TIMEOUT=30\n'
    printf 'STATE_FILE=state.json\n'
    printf 'LOG_FILE=bot.log\n'
    printf 'LOG_LEVEL=INFO\n'
    printf 'PARSE_MODE=HTML\n'
    printf 'SEND_HELP=true\n'
    printf 'SET_COMMANDS_MENU=true\n'
  } > "$TARGET/.env"
  chmod 600 "$TARGET/.env"
  ok "Создан $TARGET/.env (права 600)"
fi

# ---------------------------------------------------------------------------
say "4/6 Проверяю сертификаты (частая ошибка Termux)"
# ---------------------------------------------------------------------------
python3 - <<'PY'
import ssl, sys, urllib.request
try:
    ctx = ssl.create_default_context()
    with urllib.request.urlopen("https://api.telegram.org", timeout=15, context=ctx) as r:
        r.read(0)
    print("  ok  TLS до api.telegram.org работает")
except ssl.SSLCertVerificationError as exc:
    print("  xx  Ошибка сертификатов:", exc)
    print("      Исправление:")
    print("        pkg install -y ca-certificates openssl")
    print("        pkg reinstall -y ca-certificates")
    print("        export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem")
    print("      Если не помогло — временно INSECURE_TLS=true в .env (небезопасно).")
    sys.exit(1)
except Exception as exc:
    print(f"  !!  Сеть/TLS: {type(exc).__name__}: {exc}")
    print("      Проверьте интернет, VPN/прокси и системное время телефона.")
PY

# ---------------------------------------------------------------------------
say "5/6 Проверяю токен через getMe"
# ---------------------------------------------------------------------------
cd "$TARGET" || exit 1
if python3 bot.py --check; then
  ok "Бот готов к запуску"
else
  warn "Проверка не прошла — смотрите вывод выше. Частые причины:"
  warn "  • токен скопирован с пробелом/кавычками;"
  warn "  • нет интернета или блокируется api.telegram.org;"
  warn "  • неверное время на телефоне."
fi

# ---------------------------------------------------------------------------
say "6/6 Автозапуск (Termux:Boot)"
# ---------------------------------------------------------------------------
if [ "$IS_TERMUX" = "1" ]; then
  mkdir -p "$HOME/.termux/boot" 2>/dev/null || true
  cat > "$HOME/.termux/boot/start-top-bot.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
# Автозапуск TOP-бота после перезагрузки телефона.
# Требуется приложение Termux:Boot (F-Droid) и один запуск Termux после установки.
termux-wake-lock 2>/dev/null
cd "$TARGET" || exit 0
[ -f bot.py ] || exit 0
exec python3 bot.py >> "$TARGET/boot.log" 2>&1
EOF
  chmod +x "$HOME/.termux/boot/start-top-bot.sh" 2>/dev/null || true
  ok "Скрипт автозапуска: ~/.termux/boot/start-top-bot.sh"
  warn "Он сработает только если установлено приложение Termux:Boot (F-Droid) и Termux хотя бы раз запущен вручную."
else
  warn "Автозапуск Termux:Boot пропущен (не Termux)."
fi

cat <<EOF

============================================================================
 Готово. Дальше:

   cd $TARGET
   bash run.sh               # запуск с wake-lock
   # или в фоне:
   nohup python3 bot.py >> bot.log 2>&1 &

 Проверить:      python3 bot.py --print-top
 Диагностика:    python3 bot.py --check
 Тест логики:    python3 bot.py --selftest
 Логи:           tail -f $TARGET/bot.log

 Откройте бота в Telegram и отправьте /top.
 Если в ответ тишина — смотрите bot.log и раздел «Ошибки» в README.md.
============================================================================
EOF
