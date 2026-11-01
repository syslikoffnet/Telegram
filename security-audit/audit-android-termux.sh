#!/usr/bin/env bash
# audit-android-termux.sh — локальный аудит Android + Termux БЕЗ передачи доступа.
#
# Скрипт только ЧИТАЕТ данные, ничего не меняет и никуда не отправляет.
# Токены/ключи в отчёте автоматически вырезаются, отчёт можно присылать в чат.
#
# Запуск:
#   bash audit-android-termux.sh              # отчёт в ~/security-audit-ДАТА.txt
#   OUT=/tmp/report.txt bash audit...         # свой путь
#
# Требования: bash, coreutils. Android-команды (getprop, settings, pm)
# используются, если доступны; иначе раздел пропускается.

set -u

VERSION="1.0.0"
OUT="${OUT:-$HOME/security-audit-$(date +%Y%m%d-%H%M).txt}"
TMP="$(mktemp -d 2>/dev/null || echo /tmp/audit.$$)"
mkdir -p "$TMP" 2>/dev/null || true
RISKS="$TMP/risks.txt"
: > "$RISKS"
: > "$OUT" 2>/dev/null || { echo "Не могу писать в $OUT" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }
say() { printf '%s\n' "$*" | tee -a "$OUT"; }
section() { printf '\n\n===== %s =====\n' "$*" | tee -a "$OUT"; }
risk() { printf '%s\n' "$*" >> "$RISKS"; }

# Вырезаем секреты из всего, что попадает в отчёт.
redact() {
    sed -E \
        -e 's#[0-9]{6,12}:[A-Za-z0-9_-]{30,}#<REDACTED_BOT_TOKEN>#g' \
        -e 's#gh[pousr]_[A-Za-z0-9]{30,}#<REDACTED_GITHUB_TOKEN>#g' \
        -e 's#github_pat_[A-Za-z0-9_]{30,}#<REDACTED_GITHUB_TOKEN>#g' \
        -e 's#sk-[A-Za-z0-9_-]{20,}#<REDACTED_API_KEY>#g' \
        -e 's#(https?://)[^/@[:space:]]+:[^/@[:space:]]+@#\1<REDACTED_USERINFO>@#g' \
        -e 's#(BOT_TOKEN[[:space:]]*=[[:space:]]*)[^[:space:]]+#\1<REDACTED>#g' \
        -e 's#(api_hash[[:space:]]*=[[:space:]]*)[^[:space:]]+#\1<REDACTED>#g'
}

# out <заголовок> <команда> [аргументы...]
out() {
    local title="$1"; shift
    printf '\n--- %s ---\n' "$title" | tee -a "$OUT"
    if ! have "$1"; then
        echo "(команда $1 недоступна — пропущено)" | tee -a "$OUT"
        return 0
    fi
    { "$@" 2>&1 || echo "(код возврата $?)"; } | redact | head -n 150 | tee -a "$OUT"
}

# prop <имя> — значение getprop или пусто
prop() { have getprop && getprop "$1" 2>/dev/null || true; }

say "Аудит Android/Termux — $(date '+%Y-%m-%d %H:%M:%S %Z')"
say "Скрипт v$VERSION: только чтение, ничего не меняет и не отправляет."
say "Секреты в отчёте заменяются на <REDACTED>, файл: $OUT"
say "Устройство: $(uname -a 2>/dev/null)"
say "Пользователь: $(id 2>/dev/null)"
say "Termux: ${PREFIX:-нет}"

# ---------------------------------------------------------------------------
section "1. Устройство и состояние прошивки"
# ---------------------------------------------------------------------------
if have getprop; then
    out "Модель" getprop ro.product.model
    out "Производитель" getprop ro.product.manufacturer
    out "Android" getprop ro.build.version.release
    out "Патч безопасности" getprop ro.build.version.security_patch
    out "Verified boot" getprop ro.boot.verifiedbootstate
    out "Загрузчик заблокирован (1 = да)" getprop ro.boot.flash.locked
    out "ro.debuggable (0 = норма)" getprop ro.debuggable
    out "ro.secure (1 = норма)" getprop ro.secure
    out "SELinux" getenforce

    patch="$(prop ro.build.version.security_patch)"
    boot="$(prop ro.boot.verifiedbootstate)"
    locked="$(prop ro.boot.flash.locked)"
    dbg="$(prop ro.debuggable)"

    case "$boot" in
        green|"") ;;
        *) risk "MEDIUM: verified boot = '$boot' (прошивка изменена/разблокирован загрузчик)" ;;
    esac
    case "$locked" in
        1|"") ;;
        *) risk "MEDIUM: загрузчик разблокирован (ro.boot.flash.locked=$locked) — возможен физический доступ к данным" ;;
    esac
    case "$dbg" in
        0|"") ;;
        *) risk "HIGH: ro.debuggable=$dbg — это eng/userdebug сборка" ;;
    esac
    if [ -n "$patch" ]; then
        year="${patch%%-*}"
        if [ "$year" -lt "$(date +%Y)" ] 2>/dev/null; then
            risk "HIGH: патч безопасности $patch старше года — известные уязвимости Android не закрыты"
        fi
    fi
else
    say "(Android-свойства недоступны: это не Termux/Android — раздел пропущен)"
fi

# ---------------------------------------------------------------------------
section "2. Опасные настройки Android"
# ---------------------------------------------------------------------------
if have settings; then
    adb="$(settings get global adb_enabled 2>/dev/null | tr -d '\r')"
    dev="$(settings get global development_settings_enabled 2>/dev/null | tr -d '\r')"
    acc="$(settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
    notif="$(settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r')"
    proxy="$(settings get global http_proxy 2>/dev/null | tr -d '\r')"
    install="$(settings get secure install_non_market_apps 2>/dev/null | tr -d '\r')"

    say "adb_enabled: $adb"
    say "development_settings_enabled: $dev"
    say "enabled_accessibility_services: $acc"
    say "enabled_notification_listeners: $notif"
    say "http_proxy: $proxy"
    say "install_non_market_apps: $install"

    [ "$adb" = "1" ] && risk "HIGH: включена отладка по USB (ADB). Выключите: Настройки → Для разработчиков → Отладка по USB"
    [ "$dev" = "1" ] && risk "LOW: включены параметры разработчика — держите выключенными, если не нужны"
    [ -n "$acc" ] && [ "$acc" != "null" ] && risk "HIGH: включены службы доступности ($acc). Проверьте каждую: доступность = чтение экрана и нажатия"
    [ -n "$notif" ] && [ "$notif" != "null" ] && risk "MEDIUM: включён доступ к уведомлениям ($notif) — так читают коды и сообщения"
    [ -n "$proxy" ] && [ "$proxy" != "null" ] && [ "$proxy" != ":0" ] && risk "MEDIUM: задан HTTP-прокси ($proxy) — проверьте, что это ваш"
else
    say "(settings недоступен — раздел пропущен)"
fi

if have pm; then
    section "3. Приложения (сторонние)"
    { pm list packages -3 2>/dev/null || true; } | sed 's/^package://' | sort | redact | head -n 200 | tee -a "$OUT"
    count="$({ pm list packages -3 2>/dev/null || true; } | wc -l | tr -d ' ')"
    say ""
    say "Всего сторонних приложений: $count (просмотрите список: незнакомые — удалить)"
else
    section "3. Приложения (сторонние)"
    say "(pm недоступен — раздел пропущен)"
fi

if have dumpsys; then
    section "4. Администраторы устройства и special access"
    { dumpsys device_policy 2>/dev/null | grep -iE "admin|Device Owner|Profile Owner" | head -n 40 || true; } | redact | tee -a "$OUT"
    say "(если видите незнакомого администратора — Настройки → Безопасность → Администраторы устройства)"
fi

# ---------------------------------------------------------------------------
section "5. Root, Magisk, подозрительные бинарники"
# ---------------------------------------------------------------------------
for path in /system/bin/su /system/xbin/su /sbin/su /su/bin/su /data/adb/magisk /data/adb/modules /sbin/.magisk; do
    if [ -e "$path" ]; then
        say "НАЙДЕНО: $path"
        risk "MEDIUM: обнаружен root/модификация ($path). Root-доступ = любое приложение может читать данные"
    fi
done
if have su; then
    say "su в PATH: $(command -v su)"
fi
say "SELinux: $(have getenforce && getenforce 2>/dev/null || echo 'неизвестно')"

# ---------------------------------------------------------------------------
section "6. Termux: пакеты и окружение"
# ---------------------------------------------------------------------------
out "termux-info" termux-info
if have pkg; then
    printf '\n--- Установленные пакеты (ключевые) ---\n' | tee -a "$OUT"
    { pkg list-installed 2>/dev/null || true; } | grep -E '^(python|nodejs|openssh|curl|wget|git|nmap|netcat|tcpdump|tsu|proot|sqlite|jq)/' | tee -a "$OUT"
fi
out "Версия Python" python3 -V
if have python3; then
    printf '\n--- pip-пакеты (могут быть чужие) ---\n' | tee -a "$OUT"
    { python3 -m pip list --format=freeze 2>/dev/null || echo "(pip нет)"; } | head -n 100 | tee -a "$OUT"
fi
printf '\n--- Переменные прокси в окружении ---\n' | tee -a "$OUT"
{ env 2>/dev/null || true; } | grep -iE '^(http_proxy|https_proxy|all_proxy|no_proxy)=' | redact | tee -a "$OUT" || true

# ---------------------------------------------------------------------------
section "7. Автозапуск и задачи"
# ---------------------------------------------------------------------------
for f in "$HOME/.bashrc" "$HOME/.bash_profile" "$HOME/.profile" "$HOME/.zshrc"; do
    if [ -f "$f" ]; then
        printf '\n--- %s ---\n' "$f" | tee -a "$OUT"
        redact < "$f" | head -n 80 | tee -a "$OUT"
        if grep -qE 'curl[^|]*\|[[:space:]]*(ba)?sh|wget[^|]*\|[[:space:]]*(ba)?sh|base64[[:space:]]+-d|python[0-9.]*[[:space:]]+-c' "$f" 2>/dev/null; then
            risk "CRITICAL: в $f есть автозапуск скачанного/закодированного кода. Проверьте вручную!"
        fi
    fi
done
out "Termux:Boot" ls -la "$HOME/.termux/boot"
out "Shortcuts" ls -la "$HOME/.shortcuts"
out "crontab" crontab -l
if have termux-job-scheduler; then
    out "termux-job-scheduler" termux-job-scheduler --list
fi
if [ -d "$HOME/.termux/boot" ] && [ -n "$(ls -A "$HOME/.termux/boot" 2>/dev/null)" ]; then
    risk "MEDIUM: есть скрипты Termux:Boot — они запускаются автоматически при загрузке"
fi

# ---------------------------------------------------------------------------
section "8. Файлы в домашней папке"
# ---------------------------------------------------------------------------
out "Корень ~" ls -la "$HOME"
out "Downloads (имена и даты)" ls -lat "$HOME/storage/downloads"
out "Недавние файлы (14 дней)" find "$HOME" -maxdepth 3 -type f -mtime -14 -not -path '*/.cache/*' -not -path '*/node_modules/*'

printf '\n--- Файлы .env и их права ---\n' | tee -a "$OUT"
find "$HOME" -maxdepth 4 \( -name '.env' -o -name '*.env' \) 2>/dev/null | while read -r envf; do
    [ -n "$envf" ] || continue
    mode="$(stat -c '%a' "$envf" 2>/dev/null || echo '?')"
    say "$envf  права=$mode"
    if [ "$mode" != "600" ] && [ "$mode" != "?" ]; then
        risk "MEDIUM: $envf доступен другим (права $mode) — нужно chmod 600"
    fi
done

# ---------------------------------------------------------------------------
section "9. Поиск утёкших секретов (значения маскируются)"
# ---------------------------------------------------------------------------
say "Ищу токены ботов, GitHub-токены, приватные ключи…"
{
    grep -rIlE '[0-9]{6,12}:[A-Za-z0-9_-]{30,}' "$HOME" \
        --exclude-dir=.cache --exclude-dir=node_modules --exclude-dir=.git \
        --exclude='*.log' --exclude="$(basename "$0")" --exclude="$(basename "$OUT")" \
        2>/dev/null | head -n 30 || true
} > "$TMP/tokenfiles.txt"
if [ -s "$TMP/tokenfiles.txt" ]; then
    risk "CRITICAL: в файлах найдены строки, похожие на токен бота — отозвать в @BotFather"
    while read -r f; do
        printf '\n--- %s ---\n' "$f" | tee -a "$OUT"
        grep -nE '[0-9]{6,12}:[A-Za-z0-9_-]{30,}' "$f" 2>/dev/null | head -n 5 | redact | tee -a "$OUT"
    done < "$TMP/tokenfiles.txt"
else
    say "Строк вида <bot_token> в ~ не найдено."
fi

{ find "$HOME" -maxdepth 4 -type f \( -name 'id_rsa' -o -name 'id_ed25519' -o -name '*.pem' -o -name '*.key' \) 2>/dev/null | head -n 20 || true; } > "$TMP/keys.txt"
if [ -s "$TMP/keys.txt" ]; then
    risk "HIGH: найдены приватные ключи в домашней папке — проверьте, что они нужны и с правами 600"
    while read -r k; do
        say "$k права=$(stat -c '%a' "$k" 2>/dev/null || echo '?')"
    done < "$TMP/keys.txt"
fi

if [ -f "$HOME/.ssh/authorized_keys" ]; then
    risk "HIGH: в ~/.ssh/authorized_keys есть ключи — любой с этим ключом может зайти на устройство"
    say "authorized_keys: $(wc -l < "$HOME/.ssh/authorized_keys") строк"
fi
[ -f "$HOME/.git-credentials" ] && risk "HIGH: ~/.git-credentials хранит пароли/токены в открытом виде"
[ -f "$HOME/.netrc" ] && risk "HIGH: ~/.netrc хранит логины/пароли в открытом виде"

# ---------------------------------------------------------------------------
section "10. Процессы и сеть"
# ---------------------------------------------------------------------------
if ps -A -o PID,PPID,ELAPSED,ARGS >/dev/null 2>&1; then
    out "Процессы" ps -A -o PID,PPID,ELAPSED,ARGS
else
    out "Процессы" ps aux
fi
if have ss; then
    out "Слушающие порты" ss -ltnp
elif have netstat; then
    out "Слушающие порты" netstat -ltnp
else
    say "ss/netstat нет — смотрю /proc/net/tcp"
    { cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || true; } | head -n 30 | tee -a "$OUT"
fi
LISTEN_COUNT=$({ (have ss && ss -ltn 2>/dev/null) || (have netstat && netstat -ltn 2>/dev/null) || true; } | grep -c LISTEN 2>/dev/null || true)
LISTEN_COUNT=${LISTEN_COUNT:-0}
if [ "${LISTEN_COUNT:-0}" -gt 0 ]; then
    risk "MEDIUM: есть слушающие TCP-порты — убедитесь, что это ваши сервисы (SSH и т. п.)"
fi

out "Доступность Telegram API" curl -sS -m 10 -o /dev/null -w 'HTTP %{http_code}, время %{time_total}s\n' https://api.telegram.org
out "DNS api.telegram.org" getent hosts api.telegram.org

# ---------------------------------------------------------------------------
section "11. Подозрительные конструкции в скриптах"
# ---------------------------------------------------------------------------
susp="$(grep -rIlE 'curl[^|]*\|[[:space:]]*(ba)?sh|wget[^|]*\|[[:space:]]*(ba)?sh|base64[[:space:]]+-d[^|]*\|[[:space:]]*(ba)?sh' "$HOME" \
    --include='*.sh' --include='*.py' \
    --exclude-dir=.cache --exclude-dir=node_modules --exclude-dir=.git \
    --exclude="$(basename "$0")" --exclude="$(basename "$OUT")" 2>/dev/null | head -n 20 || true)"
if [ -n "$susp" ]; then
    say "Файлы с потенциально опасными конструкциями (только имена):"
    printf '%s\n' "$susp" | tee -a "$OUT"
    risk "HIGH: есть скрипты, скачивающие и сразу исполняющие код. Проверьте каждый файл из списка"
else
    say "Явных 'curl | bash' в скриптах не найдено."
fi

# ---------------------------------------------------------------------------
section "12. ИТОГ"
# ---------------------------------------------------------------------------
if [ -s "$RISKS" ]; then
    say "Найдено замечаний: $(sort -u "$RISKS" | wc -l | tr -d ' ')"
    say ""
    sort -u "$RISKS" | tee -a "$OUT"
else
    say "Красных флагов не найдено. Это не гарантия чистоты: аудит проверяет"
    say "настройки и файлы, но не поведение каждого приложения."
fi

say ""
say "Что дальше:"
say "1) Просмотрите отчёт глазами (он без токенов)."
say "2) Пришлите его в чат — разберу по пунктам и дам точные команды."
say "3) Никому не передавайте сам токен и не устанавливайте 'удалённый доступ'."
say ""
say "Отчёт: $OUT"

rm -rf "$TMP" 2>/dev/null || true
exit 0
