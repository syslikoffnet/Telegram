#!/usr/bin/env bash
# Аудит чужого VPS: только чтение, ничего не ставится и не меняется.
#
# Запуск с твоей машины (ничего не копируя на сервер):
#   ssh user@IP 'bash -s' < scripts/vps-audit.sh | tee vps-audit.txt
#
# Если на сервере есть sudo без пароля — часть разделов будет подробнее.
# Без sudo скрипт тоже отработает, просто покажет меньше.

export LC_ALL=C
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    if sudo -n true 2>/dev/null; then SUDO="sudo -n"; fi
fi

sec() {
    printf '\n\n===== %s =====\n' "$1"
}

run() {
    # показываем команду и её вывод, молча переживаем отсутствие утилиты
    printf '\n$ %s\n' "$*"
    eval "$@" 2>&1 | head -n 120
}

printf 'Аудит VPS, снят %s\n' "$(date -u '+%Y-%m-%d %H:%M UTC')"

sec "1. Что за машина"
run 'cat /etc/os-release'
run 'uname -a'
run 'uptime'
run 'ls -ld --time-style=long-iso /lost+found / /etc 2>/dev/null'
run 'stat -c "%y  %n" /etc/machine-id /var/log/installer 2>/dev/null'
run 'free -m; df -h'
run 'systemd-detect-virt'

sec "2. Как сервер создавали (cloud-init часто хранит исходный сценарий)"
run 'ls -la /var/lib/cloud/instance 2>/dev/null'
run 'cat /var/lib/cloud/instance/user-data.txt 2>/dev/null'
run "$SUDO cat /var/lib/cloud/instance/cloud-config.txt 2>/dev/null"
run 'cat /var/log/cloud-init-output.log 2>/dev/null | tail -n 40'

sec "3. Пользователи и права"
run "getent passwd | awk -F: '\$7 !~ /(nologin|false)\$/'"
run "getent group sudo adm admin wheel"
run "$SUDO cat /etc/sudoers 2>/dev/null | grep -v '^#' | grep -v '^\$'"
run "$SUDO ls -la /etc/sudoers.d/ 2>/dev/null"
run "$SUDO grep -rs '' /etc/sudoers.d/ 2>/dev/null"
run "$SUDO awk -F: '\$2 !~ /^[*!]/ {print \$1\" имеет пароль\"}' /etc/shadow 2>/dev/null"

sec "4. Ключи SSH — чьи ещё пускают"
run "$SUDO find /root /home -maxdepth 3 -name authorized_keys -exec ls -la {} \; -exec cat {} \; 2>/dev/null"
run "$SUDO grep -Ev '^\s*#|^\s*$' /etc/ssh/sshd_config 2>/dev/null"
run "$SUDO ls -la /etc/ssh/sshd_config.d/ 2>/dev/null"
run "$SUDO grep -rs '' /etc/ssh/sshd_config.d/ 2>/dev/null"
run 'ls -la /etc/ssh/*.pub 2>/dev/null; for f in /etc/ssh/*.pub; do ssh-keygen -lf "$f" 2>/dev/null; done'

sec "5. Что слушает сеть и куда ходит"
run "$SUDO ss -tulpn"
run "$SUDO ss -tupn state established"
run 'cat /etc/hosts; cat /etc/resolv.conf'
run "$SUDO iptables -S 2>/dev/null; $SUDO nft list ruleset 2>/dev/null | head -n 60"
run 'ufw status verbose 2>/dev/null'

sec "6. Что запущено"
run "ps auxww --sort=-%mem | head -n 30"
run 'systemctl list-units --type=service --state=running --no-pager'
run 'systemctl list-timers --all --no-pager'
run "$SUDO ls -la /etc/systemd/system/ /lib/systemd/system/*.service 2>/dev/null | head -n 60"
run 'docker ps -a 2>/dev/null; docker images 2>/dev/null'

sec "7. Автозапуск и расписания"
run "$SUDO ls -la /etc/cron.d /etc/cron.daily /etc/cron.hourly /etc/cron.weekly 2>/dev/null"
run "$SUDO grep -rs '' /etc/cron.d/ /etc/crontab 2>/dev/null"
run "for u in \$(cut -d: -f1 /etc/passwd); do out=\$($SUDO crontab -l -u \$u 2>/dev/null); [ -n \"\$out\" ] && echo \"-- \$u --\" && echo \"\$out\"; done"
run "$SUDO cat /etc/rc.local 2>/dev/null"
run "$SUDO grep -rs '' /etc/profile.d/ 2>/dev/null | head -n 40"

sec "8. Классические закладки"
run "$SUDO cat /etc/ld.so.preload 2>/dev/null; echo '(пусто — хорошо)'"
run 'echo "$LD_PRELOAD"'
run "$SUDO lsmod | head -n 40"
run "$SUDO find /tmp /var/tmp /dev/shm -maxdepth 2 -type f -newermt '-30 days' -ls 2>/dev/null | head -n 40"
run "$SUDO find /usr/local/bin /usr/local/sbin /opt -maxdepth 3 -type f -newermt '-180 days' -ls 2>/dev/null | head -n 40"
run "$SUDO find / -xdev -perm -4000 -type f -ls 2>/dev/null | head -n 40"

sec "9. Целостность пакетов (расхождения = подменённые бинарники)"
run "$SUDO dpkg --verify 2>/dev/null | head -n 40"
run 'which debsums >/dev/null 2>&1 && $SUDO debsums -c 2>/dev/null | head -n 40 || echo "debsums не установлен — пропускаем (сами НЕ ставим)"'

sec "10. Следы входов и истории"
run 'last -aiF 2>/dev/null | head -n 25'
run 'lastlog 2>/dev/null | head -n 25'
run "$SUDO grep -Ei 'accepted|new user|new group' /var/log/auth.log 2>/dev/null | tail -n 40"
run "$SUDO tail -n 20 /root/.bash_history 2>/dev/null"
run "$SUDO find /root /home -maxdepth 3 -name '.*history' -exec ls -la {} \; 2>/dev/null"

sec "11. Обновления и уязвимость"
run 'apt-get -s upgrade 2>/dev/null | tail -n 15'
run 'cat /etc/apt/apt.conf.d/20auto-upgrades 2>/dev/null'
run "$SUDO grep -rs '' /etc/apt/sources.list.d/ /etc/apt/sources.list 2>/dev/null | grep -v '^\s*#' | head -n 30"

printf '\n\nГотово. Что считать тревожным:\n'
printf '  • чужие ключи в authorized_keys, лишние пользователи с паролем или в sudo\n'
printf '  • непустой /etc/ld.so.preload, свежие файлы в /tmp, незнакомые systemd-юниты и таймеры\n'
printf '  • открытые наружу порты, которых ты не поднимал\n'
printf '  • расхождения dpkg --verify по /usr/bin, /usr/sbin\n'
printf '  • cloud-init user-data со скриптами, которых ты не писал\n'
printf 'Чистый отчёт НЕ означает безопасность: у владельца панели остаётся доступ к диску и консоли.\n'
