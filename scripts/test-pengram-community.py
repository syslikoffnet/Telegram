#!/usr/bin/env python3
"""Скрипт сообщества: безопасный запуск и полный набор текстов.

Проверяем то, что легко сломать при правках:

1. По умолчанию скрипт только показывает план — без ключей и без сети.
2. Реальное создание включается лишь флагом --apply.
3. Состояние и сессия не попадают в репозиторий.
4. Секреты не печатаются.
5. В плане есть канал, чат-форум, темы, посты, закрепы и папка.
6. Тексты человеческие и с шаблонами для багов/идей.
7. Аватарки существуют и их достаточно для выбора.
"""

import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
script = root / "scripts" / "pengram-community.py"
source = script.read_text(encoding="utf-8")

# --- запуск без сети: план и список аватарок --------------------------------
plan = subprocess.run([sys.executable, str(script)], capture_output=True, text=True)
assert plan.returncode == 0, plan.stderr
assert "ЧТО БУДЕТ СОЗДАНО" in plan.stdout
assert "ничего не отправляется" in plan.stdout
assert "--apply" in plan.stdout
assert "КАНАЛ" in plan.stdout and "ЧАТ" in plan.stdout
assert "Пингвин на связи" in plan.stdout
assert "Баги" in plan.stdout and "Идеи" in plan.stdout

texts = subprocess.run([sys.executable, str(script), "--show-texts"],
                       capture_output=True, text=True)
assert texts.returncode == 0, texts.stderr
assert "ТЕКСТЫ ЦЕЛИКОМ" in texts.stdout
assert "{group}" not in texts.stdout, "в плане остался неразвёрнутый placeholder"

avatars = subprocess.run([sys.executable, str(script), "--avatar-list"],
                         capture_output=True, text=True)
assert avatars.returncode == 0, avatars.stderr
assert avatars.stdout.count("✓") >= 10

# --- защита от случайного запуска ------------------------------------------
assert 'parser.add_argument("--apply", action="store_true"' in source
assert "if not args.apply:" in source

# --- секреты и состояние ---------------------------------------------------
assert "print(api_hash" not in source and "print(api_id" not in source
assert "TELEGRAM_API_HASH" in source and "api.properties" in source
assert "Path.home()" in source and "SPECIAL" not in source
assert ".pengram-community" in source

# --- структура площадки ----------------------------------------------------
for marker in (
    "CreateChannelRequest",
    "ToggleForumRequest",
    "CreateForumTopicRequest",
    "SetDiscussionGroupRequest",
    "UpdateDialogFilterRequest",
    "ExportChatInviteRequest",
    "InputReplyToMessage",
    "GetForumTopicsRequest",
    "pin_message",
):
    assert marker in source, marker

# --- темы и закрепы --------------------------------------------------------
for topic in ("Баги", "Идеи", "Помощь", "Скриншоты", "Релизы", "Оффтоп"):
    assert f'"key": "{topic}"' in source, topic

# --- эмодзи и доотделка ----------------------------------------------------
for emoji_title in ("🐞 Баги", "💡 Идеи", "🛠 Помощь", "📸 Скриншоты",
                    "📦 Релизы", "🌿 Оффтоп"):
    assert emoji_title in source, emoji_title

assert "EditForumTopicRequest" in source          # старые темы переименовываются
assert "has_photo(" in source                     # аватарка проверяется по факту
assert "pinned_in_chat(" in source and "pinned_in_topic(" in source
assert "GetFullChannelRequest" in source          # закреп канала проверяется
assert "InputMessagesFilterPinned" in source      # закреп темы проверяется
assert '"--repair"' in source
assert "эмодзи на папке не поставился" in source  # Premium-фолбэк для папки
assert "Настройки → Pengram → О программе" in source      # где взять отчёт
assert "одно сообщение — одна проблема".lower() in source.lower()

# --- блокировка публичных @адресов не роняет запуск -------------------------
assert "ChannelsAdminPublicTooMuchError" in source
assert "public_username_blocked" in source
assert "--retry-usernames" in source
assert "оставьте" in source or "оставьте " in source

# --- повторный запуск находит уже созданное --------------------------------
assert "channel_access_hash" in source and "group_access_hash" in source
assert "iter_dialogs" in source                     # запасной поиск по названию

# --- лимиты Telegram обработаны понятным текстом ---------------------------
assert "FloodWaitError" in source
assert "ChannelsTooMuchError" in source
assert "подождите" in source.lower() or "через сутки" in source.lower()

# --- аватарки: локально или докачиваются -----------------------------------
assert "raw.githubusercontent.com" in source            # быстрый путь
assert "api.github.com" in source                        # запасной путь
assert "ASSETS_REF" in source and "REPO_SLUG" in source  # пин на коммит
assert "urlopen" in source
assert "required: bool = True" in source                 # без сети не падаем
assert "канал останется без фото" in source

# --- аватарки на месте -----------------------------------------------------
avatars_dir = root / "branding" / "community" / "avatars"
files = sorted(avatars_dir.glob("*.jpg"))
assert len(files) >= 10, f"найдено {len(files)} аватарок"
for path in files:
    assert path.stat().st_size > 20_000, path.name

print("community setup: dry-run by default, forum topics, pinned texts, "
      "10 avatars, secrets kept out: OK")
