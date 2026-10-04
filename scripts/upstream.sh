#!/usr/bin/env bash
#
# Обновление Pengram на новые версии Telegram.
#
#   ./scripts/upstream.sh setup    — один раз: подключить апстрим и «пришить» его историю
#   ./scripts/upstream.sh check    — посмотреть, что нового вышло у DrKLO
#   ./scripts/upstream.sh update   — влить свежий апстрим в текущую ветку
#   ./scripts/upstream.sh surface  — пересобрать список файлов апстрима, которые трогает форк
#
# Зачем setup: репозиторий форка создан «плоским» снимком, общей истории с
# DrKLO/Telegram у него нет, поэтому обычный merge ругается на unrelated histories.
# setup делает merge -s ours с базовым коммитом Telegram: содержимое файлов не
# меняется вообще, но git начинает знать, от какой версии мы отпочковались.
# После этого любой следующий merge видит нормальную базу и конфликтует только
# там, где апстрим и мы правили одно и то же место.

set -euo pipefail
cd "$(dirname "$0")/.."

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/DrKLO/Telegram.git}"
# коммит DrKLO/Telegram «update to 12.10.6 (7112)» — на нём основан текущий форк
BASE_COMMIT="${BASE_COMMIT:-f2908b14133bbffbf7ab04f641ecb5bfaf533242}"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
die()  { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

ensure_remote() {
    if ! git remote get-url upstream >/dev/null 2>&1; then
        say "Добавляю remote upstream -> $UPSTREAM_URL"
        git remote add upstream "$UPSTREAM_URL"
    fi
}

# Качаем только историю и деревья, без старых бинарников: клон апстрима так
# весит в разы меньше, а файлы нужных коммитов git дотянет сам по мере надобности.
fetch_upstream() {
    ensure_remote
    say "Забираю апстрим (это долго только в первый раз)…"
    git fetch --filter=blob:none upstream master
}

cmd_setup() {
    fetch_upstream
    if git merge-base --is-ancestor "$BASE_COMMIT" HEAD 2>/dev/null; then
        warn "История апстрима уже пришита, setup не нужен."
        return 0
    fi
    git cat-file -e "${BASE_COMMIT}^{commit}" 2>/dev/null || die "Не нашёл базовый коммит $BASE_COMMIT"
    if [ -n "$(git status --porcelain)" ]; then
        die "Сначала закоммить или спрячь незакоммиченные изменения."
    fi
    say "Пришиваю базу Telegram 12.10.6 (файлы не меняются)"
    git merge -s ours --allow-unrelated-histories --no-edit \
        -m "baseline: Telegram 12.10.6 (7112) как база форка" "$BASE_COMMIT"
    say "Готово. Дальше обновление — это ./scripts/upstream.sh update"
}

cmd_check() {
    fetch_upstream
    say "Новые релизы у DrKLO после нашей базы:"
    git log --oneline --grep='^update to' "$BASE_COMMIT"..upstream/master || true
    local behind
    behind="$(git rev-list --count "$BASE_COMMIT"..upstream/master 2>/dev/null || echo 0)"
    say "Всего новых коммитов апстрима: $behind"
    say "Файлы апстрима, которые апстрим и мы правили одновременно (будущие конфликты):"
    git diff --name-only "$BASE_COMMIT"..upstream/master > /tmp/pengram-upstream-changed.txt 2>/dev/null || true
    if [ -f docs/fork-surface.txt ]; then
        comm -12 <(sort -u /tmp/pengram-upstream-changed.txt) <(sort -u docs/fork-surface.txt) | sed 's/^/  /' || true
    else
        warn "  нет docs/fork-surface.txt — сначала ./scripts/upstream.sh surface"
    fi
}

cmd_update() {
    [ -z "$(git status --porcelain)" ] || die "Сначала закоммить или спрячь незакоммиченные изменения."
    git merge-base --is-ancestor "$BASE_COMMIT" HEAD 2>/dev/null || die "Сначала ./scripts/upstream.sh setup"
    fetch_upstream
    local branch; branch="$(git rev-parse --abbrev-ref HEAD)"
    say "Вливаю upstream/master в $branch"
    if git merge --no-edit upstream/master; then
        say "Смёржилось без конфликтов. Проверь сборку: ./scripts/build-arm64.sh standalone"
    else
        warn "Есть конфликты — это нормально. Что делать:"
        git diff --name-only --diff-filter=U | sed 's/^/  конфликт: /'
        cat <<'HINT'

  1. Открывай файлы из списка. Наши куски помечены комментариями с Pengram —
     в 90% случаев правильный ответ «взять версию апстрима и вернуть внутрь
     наши pengram-строчки».
  2. Полностью наши файлы (Pengram*.java) в конфликты не попадают никогда.
  3. После правок:  git add -A && git commit
  4. Сборка:        ./scripts/build-arm64.sh standalone
  5. Если всё пошло не так:  git merge --abort
HINT
        exit 1
    fi
}

# Список файлов апстрима, в которых живут наши правки. Чем он короче,
# тем дешевле каждое обновление.
cmd_surface() {
    say "Считаю поверхность форка…"
    grep -rli "pengram" \
        --include=*.java --include=*.xml --include=*.gradle --include=*.pro \
        TMessagesProj/src TMessagesProj_App*/src TMessagesProj/build.gradle build.gradle 2>/dev/null \
        | grep -v '/Pengram' | sort -u > docs/fork-surface.txt
    printf 'файлов апстрима с нашими правками: %s\n' "$(wc -l < docs/fork-surface.txt)"
    printf 'топ-15 по количеству вхождений:\n'
    while read -r f; do printf '%s %s\n' "$(grep -ci pengram "$f")" "$f"; done < docs/fork-surface.txt \
        | sort -rn | head -15 | sed 's/^/  /'
    printf '\nсписок сохранён в docs/fork-surface.txt\n'
}

case "${1:-}" in
    setup)   cmd_setup ;;
    check)   cmd_check ;;
    update)  cmd_update ;;
    surface) cmd_surface ;;
    *) sed -n '2,20p' "$0"; exit 1 ;;
esac
