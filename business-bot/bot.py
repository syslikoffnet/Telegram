#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TOP-бот для Telegram (обычный ЛС + Telegram Business).

Что делает
==========
Пользователь пишет в личку боту ``/top``. Бот:

1. удаляет сообщение с командой ``/top`` (а не «съедает» его молча);
2. отправляет НОВОЕ сообщение с нумерованным списком::

       1. Misha
       2. Misha
       3. misha

Сообщение никогда не редактируется: каждый ``/top`` — это новое сообщение.
Список берётся из ``top.json`` (или ``top.txt``) и правится без перезапуска бота:
файл перечитывается на каждый вызов, если изменился mtime.

Почему без зависимостей
=======================
Скрипт использует только стандартную библиотеку Python (urllib, json, ssl).
В Termux это важно: не нужно собирать колёса (pydantic/uvloop/aiogram), не
ломается pip на новой версии Python, не нужны Rust/NDK. Достаточно
``pkg install python ca-certificates``.

Режимы работы
=============
* ``private``  — обычный ЛС с ботом. Бот удаляет входящие сообщения
  пользователя в приватном чате (это разрешено Bot API) и пишет ответ.
* ``business`` — бот подключён как чат-бот Telegram Business
  (Настройки → Telegram Business → Чат-боты). Тогда сообщения приходят как
  ``business_message``, а бот пишет от имени аккаунта. Удаление чужого
  сообщения требует права ``can_delete_all_messages`` («Удалять полученные
  сообщения») и делается другим методом — ``deleteBusinessMessages``.
* ``auto``     — по умолчанию: работает и там, и там.

Быстрые команды
===============
    python3 bot.py --check        # проверить токен/конфиг/файл топа, ничего не отправляя
    python3 bot.py --print-top    # показать, как выглядит сообщение
    python3 bot.py --selftest     # офлайн-тест логики (без сети и токена)
    python3 bot.py --once         # обработать одну порцию апдейтов и выйти
    python3 bot.py                # обычный запуск (long polling)

Документация: см. README.md рядом и ANALYSIS.md (разбор подарков/NFT-офферов
и бизнес-ботов в этом репозитории).
"""

from __future__ import annotations

import argparse
import dataclasses
import html
import json
import logging
import logging.handlers
import os
import random
import signal
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict, deque
from pathlib import Path
from typing import Any, Callable, Deque, Dict, List, Optional, Sequence, Set, Tuple

VERSION = "1.0.0"

# Telegram считает лимит текста в UTF-16 code units (эмодзи = 2). 4096 — жёсткий
# лимит API, берём 3800 с запасом, чтобы не ловить «message is too long».
MAX_TEXT_UTF16 = 3800
TELEGRAM_TEXT_LIMIT = 4096
MAX_ENTRIES = 1000
DEFAULT_TOP_FALLBACK = ["Misha", "Misha", "misha"]

ALLOWED_UPDATES = [
    "message",
    "business_connection",
    "business_message",
    "my_chat_member",
]

MEDALS = ("🥇", "🥈", "🥉")

# Подсказки к типовым ошибкам Telegram. Ключ — фрагмент description (в нижнем
# регистре), значение — что делать. Используется в логах и в README.
ERROR_HINTS: Tuple[Tuple[str, str], ...] = (
    (
        "message to delete not found",
        "Сообщение уже удалено (или его удалил собеседник) — это нормально, бот продолжает работу.",
    ),
    (
        "message can't be deleted",
        "Удалить нельзя: сообщение старше 48 ч, служебное, или у бота нет прав. "
        "В бизнес-чате включите право «Удалять полученные сообщения».",
    ),
    (
        "not enough rights",
        "Не хватает прав. В группе сделайте бота админом с правом «Удалять сообщения»; "
        "в бизнес-чате — право can_delete_all_messages.",
    ),
    (
        "bot was blocked by the user",
        "Пользователь заблокировал бота — писать ему нельзя, пока он не разблокирует.",
    ),
    (
        "user is deactivated",
        "Аккаунт удалён/деактивирован.",
    ),
    (
        "chat not found",
        "Неверный chat_id или бот никогда не переписывался с этим чатом.",
    ),
    (
        "can't parse entities",
        "Сломан HTML в тексте. Проверьте TOP_HEADER/TOP_FOOTER и ITEM_TEMPLATE "
        "или поставьте PARSE_MODE=none.",
    ),
    (
        "message is too long",
        "Текст длиннее 4096 символов. Уменьшите TOP_LIMIT или длину подписей.",
    ),
    (
        "can't initiate conversation",
        "Бот не может написать первым: пользователь должен сам нажать /start.",
    ),
    (
        "conflict",
        "Запущено два экземпляра бота или установлен webhook. Остановите лишний процесс, "
        "webhook снимается автоматически при старте (или вызовите --check).",
    ),
    (
        "unauthorized",
        "Неверный токен бота. Скопируйте токен в BotFather заново (формат 123456789:AA...).",
    ),
    (
        "not found",
        "Токен не найден/бот удалён, либо неверный API_BASE. Проверьте токен в BotFather.",
    ),
    (
        "too many requests",
        "Флуд-лимит Telegram. Бот сам ждёт retry_after; если повторяется — увеличьте COOLDOWN_SECONDS.",
    ),
    (
        "business_connection",
        "Бизнес-подключение недействительно: бот отключён в Настройки → Telegram Business → Чат-боты.",
    ),
)


def error_hint(description: str) -> str:
    """Человеческая подсказка по тексту ошибки Bot API."""
    low = (description or "").lower()
    for needle, hint in ERROR_HINTS:
        if needle in low:
            return hint
    return ""


def mask_token(token: str) -> str:
    """Токен в логах никогда не должен светиться целиком."""
    if not token:
        return "<пусто>"
    if len(token) <= 12:
        return token[:3] + "***"
    return f"{token[:8]}***{token[-2:]}"


def utf16_len(text: str) -> int:
    return sum(2 if ord(ch) > 0xFFFF else 1 for ch in text)


def log_text(text: str, limit: int = 300) -> str:
    """Однострочное представление текста для логов."""
    flat = text.replace("\n", "\\n")
    return flat if len(flat) <= limit else flat[:limit] + "…"


# ---------------------------------------------------------------------------
# Конфигурация
# ---------------------------------------------------------------------------


def _strip_quotes(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def read_env_file(path: Path) -> Dict[str, str]:
    """Мини-парсер .env без внешних зависимостей."""
    result: Dict[str, str] = {}
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except FileNotFoundError:
        return result
    except OSError as exc:
        print(f"[warn] не смог прочитать {path}: {exc}", file=sys.stderr)
        return result

    for line in raw.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            continue
        value = _strip_quotes(value)
        # даём возможность писать \n в одну строку (заголовок/разделитель)
        value = value.replace("\\n", "\n")
        result[key] = value
    return result


def env_bool(value: Optional[str], default: bool = False) -> bool:
    if value is None or value == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "y", "on", "да")


def env_int(value: Optional[str], default: int) -> int:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def env_float(value: Optional[str], default: float) -> float:
    try:
        return float(str(value).strip().replace(",", "."))
    except (TypeError, ValueError):
        return default


def env_ids(value: Optional[str]) -> Set[int]:
    ids: Set[int] = set()
    if not value:
        return ids
    for chunk in value.replace(";", ",").replace(" ", ",").split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        try:
            ids.add(int(chunk))
        except ValueError:
            print(f"[warn] не число в списке ID: {chunk!r}", file=sys.stderr)
    return ids


def env_commands(value: Optional[str], default: str = "/top") -> List[str]:
    raw = value or default
    parts = [p.strip() for p in raw.replace(";", ",").split(",") if p.strip()]
    out: List[str] = []
    for part in parts:
        if not part.startswith("/"):
            part = "/" + part
        out.append(part.lower())
    return out or ["/top"]


@dataclasses.dataclass
class Config:
    # путь к .env (для логов)
    env_path: Optional[Path] = None
    base_dir: Path = dataclasses.field(default_factory=lambda: Path(__file__).resolve().parent)

    token: str = ""
    api_base: str = "https://api.telegram.org"
    mode: str = "auto"  # auto | private | business

    # список топа
    top_file: Path = dataclasses.field(default_factory=lambda: Path("top.json"))
    title: str = "ТОП"
    header: str = "🏆 <b>{title}</b>"
    footer: str = ""
    item_template: str = "{n}. {name}"
    separator: str = "\n"
    show_medals: bool = False
    top_limit: int = 0
    sort_by_score: bool = False
    parse_mode: str = "HTML"  # HTML | none

    # поведение
    commands: List[str] = dataclasses.field(default_factory=lambda: ["/top"])
    delete_command: bool = True
    delete_previous: bool = False
    pin_top: bool = False
    reply_to_command: bool = False
    typing: bool = False
    send_help: bool = True
    set_commands_menu: bool = True

    # защита от флуда
    cooldown_seconds: float = 3.0
    max_per_minute: int = 6
    max_message_age_seconds: int = 120

    # доступ
    allowed_users: Set[int] = dataclasses.field(default_factory=set)
    allowed_chats: Set[int] = dataclasses.field(default_factory=set)
    admin_ids: Set[int] = dataclasses.field(default_factory=set)
    allow_groups: bool = False

    # сеть / рантайм
    poll_timeout: int = 30
    state_file: Path = dataclasses.field(default_factory=lambda: Path("state.json"))
    log_file: Path = dataclasses.field(default_factory=lambda: Path("bot.log"))
    log_level: str = "INFO"
    drop_pending_updates: bool = False
    delete_webhook_on_start: bool = True
    dry_run: bool = False
    insecure_tls: bool = False
    ca_bundle: str = ""

    def resolve_paths(self) -> None:
        base = self.base_dir
        if not self.top_file.is_absolute():
            self.top_file = base / self.top_file
        if not self.state_file.is_absolute():
            self.state_file = base / self.state_file
        if not self.log_file.is_absolute():
            self.log_file = base / self.log_file

    @property
    def parse_mode_value(self) -> Optional[str]:
        return None if self.parse_mode.lower() in ("", "none", "plain", "off") else self.parse_mode

    def summary(self) -> str:
        return "\n".join(
            [
                f"версия:           {VERSION}",
                f"режим:            {self.mode}",
                f"токен:            {mask_token(self.token)}",
                f"команда(ы):       {', '.join(self.commands)}",
                f"файл топа:        {self.top_file}",
                f"состояние:        {self.state_file}",
                f"лог:              {self.log_file}",
                f"parse_mode:       {self.parse_mode}",
                f"удалять команду:  {self.delete_command}",
                f"удалять прошлый:  {self.delete_previous}",
                f"закреплять топ:   {self.pin_top}",
                f"кулдаун:          {self.cooldown_seconds} c, {self.max_per_minute}/мин на чат",
                f"макс. возраст:    {self.max_message_age_seconds} c",
                f"группы:           {self.allow_groups}",
                f"whitelist users:  {sorted(self.allowed_users) or 'все'}",
                f"dry-run:          {self.dry_run}",
            ]
        )


def load_config(env_path: Optional[str] = None, overrides: Optional[Dict[str, str]] = None) -> Config:
    """Собирает конфиг: .env файл → переменные окружения → CLI-overrides."""
    base_dir = Path(__file__).resolve().parent
    candidates: List[Path] = []
    if env_path:
        candidates.append(Path(env_path).expanduser())
    env_from_shell = os.environ.get("BOT_ENV_FILE")
    if env_from_shell:
        candidates.append(Path(env_from_shell).expanduser())
    candidates.extend([Path.cwd() / ".env", base_dir / ".env", base_dir / "config.env"])

    file_values: Dict[str, str] = {}
    found_env: Optional[Path] = None
    for candidate in candidates:
        if candidate.is_file():
            file_values = read_env_file(candidate)
            found_env = candidate.resolve()
            base_dir = candidate.resolve().parent
            break

    def get(name: str) -> Optional[str]:
        if overrides and overrides.get(name) is not None:
            return overrides[name]
        if os.environ.get(name):
            return os.environ[name]
        return file_values.get(name)

    cfg = Config(env_path=found_env, base_dir=base_dir)
    cfg.token = (get("BOT_TOKEN") or "").strip()
    cfg.api_base = (get("API_BASE") or cfg.api_base).rstrip("/")
    cfg.mode = (get("MODE") or "auto").strip().lower()
    if cfg.mode not in ("auto", "private", "business"):
        cfg.mode = "auto"

    cfg.top_file = Path(get("TOP_FILE") or "top.json")
    cfg.title = get("TOP_TITLE") or cfg.title
    cfg.header = (get("TOP_HEADER") if get("TOP_HEADER") is not None else cfg.header).replace("\\n", "\n")
    cfg.footer = (get("TOP_FOOTER") or "").replace("\\n", "\n")
    cfg.item_template = get("ITEM_TEMPLATE") or cfg.item_template
    separator = get("ITEM_SEPARATOR")
    cfg.separator = "\n" if separator is None else separator.replace("\\n", "\n")
    cfg.show_medals = env_bool(get("SHOW_MEDALS"), False)
    cfg.top_limit = max(0, env_int(get("TOP_LIMIT"), 0))
    cfg.sort_by_score = env_bool(get("SORT_BY_SCORE"), False)
    cfg.parse_mode = (get("PARSE_MODE") or "HTML").strip()

    cfg.commands = env_commands(get("COMMAND"), "/top")
    cfg.delete_command = env_bool(get("DELETE_COMMAND"), True)
    cfg.delete_previous = env_bool(get("DELETE_PREVIOUS_TOPS"), False)
    cfg.pin_top = env_bool(get("PIN_TOP"), False)
    cfg.reply_to_command = env_bool(get("REPLY_TO_COMMAND"), False)
    cfg.typing = env_bool(get("SEND_TYPING"), False)
    cfg.send_help = env_bool(get("SEND_HELP"), True)
    cfg.set_commands_menu = env_bool(get("SET_COMMANDS_MENU"), True)

    cfg.cooldown_seconds = max(0.0, env_float(get("COOLDOWN_SECONDS"), 3.0))
    cfg.max_per_minute = max(1, env_int(get("MAX_TOPS_PER_MINUTE"), 6))
    cfg.max_message_age_seconds = max(0, env_int(get("MAX_MESSAGE_AGE_SECONDS"), 120))

    cfg.allowed_users = env_ids(get("ALLOWED_USER_IDS"))
    cfg.allowed_chats = env_ids(get("ALLOWED_CHAT_IDS"))
    cfg.admin_ids = env_ids(get("ADMIN_IDS"))
    cfg.allow_groups = env_bool(get("ALLOW_GROUPS"), False)

    cfg.poll_timeout = max(0, min(50, env_int(get("POLL_TIMEOUT"), 30)))
    cfg.state_file = Path(get("STATE_FILE") or "state.json")
    cfg.log_file = Path(get("LOG_FILE") or "bot.log")
    cfg.log_level = (get("LOG_LEVEL") or "INFO").strip().upper()
    cfg.drop_pending_updates = env_bool(get("DROP_PENDING_UPDATES"), False)
    cfg.delete_webhook_on_start = env_bool(get("DELETE_WEBHOOK_ON_START"), True)
    cfg.dry_run = env_bool(get("DRY_RUN"), False)
    cfg.insecure_tls = env_bool(get("INSECURE_TLS"), False)
    cfg.ca_bundle = get("CA_BUNDLE") or ""

    cfg.resolve_paths()
    return cfg


# ---------------------------------------------------------------------------
# Логирование
# ---------------------------------------------------------------------------


def setup_logging(cfg: Config, verbose: bool = False) -> logging.Logger:
    logger = logging.getLogger("topbot")
    logger.setLevel(logging.DEBUG if verbose else getattr(logging, cfg.log_level, logging.INFO))
    logger.propagate = False
    for handler in list(logger.handlers):
        logger.removeHandler(handler)
        try:
            handler.close()
        except Exception:  # noqa: BLE001 - закрытие хендлера не должно мешать
            pass

    fmt = logging.Formatter("%(asctime)s %(levelname)-7s %(message)s", "%Y-%m-%d %H:%M:%S")

    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(fmt)
    logger.addHandler(console)

    try:
        cfg.log_file.parent.mkdir(parents=True, exist_ok=True)
        file_handler = logging.handlers.RotatingFileHandler(
            cfg.log_file, maxBytes=1_000_000, backupCount=3, encoding="utf-8"
        )
        file_handler.setFormatter(fmt)
        logger.addHandler(file_handler)
    except OSError as exc:
        logger.warning("Не смог открыть лог-файл %s: %s (пишу только в консоль)", cfg.log_file, exc)

    return logger


# ---------------------------------------------------------------------------
# Telegram Bot API (только stdlib)
# ---------------------------------------------------------------------------


class TelegramError(Exception):
    """Ошибка Bot API или сети."""

    def __init__(
        self,
        description: str,
        code: int = 0,
        retry_after: Optional[float] = None,
        method: str = "",
    ) -> None:
        super().__init__(description)
        self.description = description
        self.code = code
        self.retry_after = retry_after
        self.method = method

    def __str__(self) -> str:  # pragma: no cover - косметика
        parts = [f"код {self.code}" if self.code else "сеть", self.description]
        if self.method:
            parts.insert(0, self.method)
        return ": ".join(parts)


class TelegramAPI:
    """Тонкий клиент Bot API: JSON, таймауты, retry, FloodWait, понятные ошибки."""

    def __init__(
        self,
        token: str,
        api_base: str = "https://api.telegram.org",
        poll_timeout: int = 30,
        dry_run: bool = False,
        insecure_tls: bool = False,
        ca_bundle: str = "",
        logger: Optional[logging.Logger] = None,
        sleep: Callable[[float], None] = time.sleep,
        max_network_retries: int = 5,
        max_flood_retries: int = 4,
    ) -> None:
        self.token = token
        self.api_base = api_base.rstrip("/")
        self.poll_timeout = poll_timeout
        self.dry_run = dry_run
        self.logger = logger or logging.getLogger("topbot")
        self._sleep = sleep
        self.max_network_retries = max_network_retries
        self.max_flood_retries = max_flood_retries
        self.stop_event = threading.Event()

        if insecure_tls:
            ctx = ssl._create_unverified_context()  # noqa: SLF001 - осознанный флаг для Termux
        else:
            ctx = ssl.create_default_context(cafile=ca_bundle or None)
        handlers: List[Any] = [urllib.request.HTTPSHandler(context=ctx)]
        # ProxyHandler() читает HTTPS_PROXY/HTTP_PROXY/NO_PROXY из окружения.
        handlers.insert(0, urllib.request.ProxyHandler())
        self._opener = urllib.request.build_opener(*handlers)

    # -- низкий уровень ----------------------------------------------------

    def call(self, method: str, params: Optional[Dict[str, Any]] = None) -> Any:
        if self.dry_run and method not in (
            "getMe",
            "getUpdates",
            "getWebhookInfo",
            "getBusinessConnection",
        ):
            self.logger.info("[dry-run] %s %s", method, log_text(json.dumps(params or {}, ensure_ascii=False)))
            return True

        network_attempt = 0
        flood_attempt = 0
        while True:
            try:
                return self._request(method, params or {})
            except TelegramError as exc:
                if exc.code == 429 and flood_attempt < self.max_flood_retries:
                    flood_attempt += 1
                    wait = (exc.retry_after or 1.0) + random.uniform(0.2, 0.8)
                    self.logger.warning(
                        "FloodWait на %s: ждём %.1f c (попытка %d/%d)",
                        method, wait, flood_attempt, self.max_flood_retries,
                    )
                    self._sleep(wait)
                    continue
                if exc.code in (-1, 500, 502, 503, 504) and network_attempt < self.max_network_retries:
                    network_attempt += 1
                    wait = min(30.0, 1.5 ** network_attempt) + random.uniform(0, 0.5)
                    self.logger.warning(
                        "Сеть/сервер на %s (%s): повтор через %.1f c (попытка %d/%d)",
                        method, exc.description, wait, network_attempt, self.max_network_retries,
                    )
                    if self.stop_event.wait(wait):
                        raise
                    continue
                hint = error_hint(exc.description)
                if hint:
                    self.logger.error("%s → %s. %s", method, exc.description, hint)
                raise

    def _request(self, method: str, params: Dict[str, Any]) -> Any:
        url = f"{self.api_base}/bot{self.token}/{method}"
        data = json.dumps(params, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=data,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        timeout = self.poll_timeout + 20 if method == "getUpdates" else 30
        try:
            with self._opener.open(request, timeout=timeout) as response:
                body = response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            body = ""
            try:
                body = exc.read().decode("utf-8", "replace")
            except Exception:  # pragma: no cover - тело уже потеряно
                pass
            description, retry_after = self._parse_error_body(body, exc.code)
            raise TelegramError(description, exc.code, retry_after, method) from None
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, ssl.SSLError) as exc:
            reason = getattr(exc, "reason", exc)
            raise TelegramError(f"сетевая ошибка: {reason}", -1, None, method) from None
        except OSError as exc:  # сокеты Termux/Android
            raise TelegramError(f"сетевая ошибка: {exc}", -1, None, method) from None

        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            snippet = (body or "<пусто>")[:200].replace("\n", " ")
            raise TelegramError(f"ответ не JSON (проверьте API_BASE/прокси): {snippet}", 0, None, method)

        if not isinstance(payload, dict):
            raise TelegramError("неожиданный формат ответа", 0, None, method)
        if not payload.get("ok"):
            description, retry_after = self._parse_error_body(body, 400)
            raise TelegramError(description, 400, retry_after, method)
        return payload.get("result")

    @staticmethod
    def _parse_error_body(body: str, code: int) -> Tuple[str, Optional[float]]:
        description = body or "пустой ответ"
        retry_after: Optional[float] = None
        try:
            payload = json.loads(body)
            if isinstance(payload, dict):
                description = str(payload.get("description") or description)
                params = payload.get("parameters") or {}
                if params.get("retry_after") is not None:
                    retry_after = float(params["retry_after"])
                elif params.get("migrate_to_chat_id"):
                    description += f" (migrate_to_chat_id={params['migrate_to_chat_id']})"
        except (json.JSONDecodeError, TypeError, ValueError):
            pass
        return description, retry_after

    # -- методы ------------------------------------------------------------

    def get_me(self) -> Dict[str, Any]:
        return self.call("getMe")

    def get_webhook_info(self) -> Dict[str, Any]:
        return self.call("getWebhookInfo")

    def delete_webhook(self) -> Any:
        return self.call("deleteWebhook", {"drop_pending_updates": False})

    def set_my_commands(self, commands: Sequence[Tuple[str, str]]) -> Any:
        payload = [{"command": c.lstrip("/"), "description": d} for c, d in commands]
        return self.call("setMyCommands", {"commands": payload})

    def get_updates(self, offset: int = 0, timeout: int = 30, limit: int = 100) -> List[Dict[str, Any]]:
        params: Dict[str, Any] = {
            "timeout": timeout,
            "limit": limit,
            "allowed_updates": ALLOWED_UPDATES,
        }
        if offset:
            params["offset"] = offset
        result = self.call("getUpdates", params)
        return result if isinstance(result, list) else []

    def send_message(
        self,
        chat_id: int,
        text: str,
        business_connection_id: Optional[str] = None,
        reply_to: Optional[int] = None,
        disable_notification: bool = False,
        parse_mode: Optional[str] = "HTML",
    ) -> Dict[str, Any]:
        params: Dict[str, Any] = {
            "chat_id": chat_id,
            "text": text,
            "disable_notification": disable_notification,
            "link_preview_options": {"is_disabled": True},
        }
        if parse_mode:
            params["parse_mode"] = parse_mode
        if business_connection_id:
            params["business_connection_id"] = business_connection_id
        if reply_to:
            params["reply_parameters"] = {
                "message_id": reply_to,
                "allow_sending_without_reply": True,
            }
        return self.call("sendMessage", params)

    def send_typing(self, chat_id: int, business_connection_id: Optional[str] = None) -> Any:
        params: Dict[str, Any] = {"chat_id": chat_id, "action": "typing"}
        if business_connection_id:
            params["business_connection_id"] = business_connection_id
        return self.call("sendChatAction", params)

    def delete_message(
        self,
        chat_id: int,
        message_id: int,
        business_connection_id: Optional[str] = None,
    ) -> Any:
        if business_connection_id:
            # В бизнес-чате deleteMessage не принимает business_connection_id:
            # удалять нужно через deleteBusinessMessages (право can_delete_all_messages).
            return self.call(
                "deleteBusinessMessages",
                {
                    "business_connection_id": business_connection_id,
                    "message_ids": [message_id],
                },
            )
        return self.call("deleteMessage", {"chat_id": chat_id, "message_id": message_id})

    def pin_message(self, chat_id: int, message_id: int) -> Any:
        return self.call(
            "pinChatMessage",
            {"chat_id": chat_id, "message_id": message_id, "disable_notification": True},
        )

    def get_business_connection(self, connection_id: str) -> Dict[str, Any]:
        return self.call("getBusinessConnection", {"business_connection_id": connection_id})


# ---------------------------------------------------------------------------
# Хранилище состояния (offset + id прошлых топов + бизнес-подключения)
# ---------------------------------------------------------------------------


class State:
    def __init__(self, path: Path, logger: logging.Logger) -> None:
        self.path = path
        self.logger = logger
        self.offset = 0
        self.connections: Dict[str, Dict[str, Any]] = {}
        self.last_top: Dict[str, List[int]] = {}
        self.stats: Dict[str, int] = {"tops_sent": 0, "messages_deleted": 0}
        self._dirty = False
        self.load()

    def load(self) -> None:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return
        except (OSError, json.JSONDecodeError) as exc:
            self.logger.warning("state-файл повреждён (%s), начинаю с чистого", exc)
            try:
                self.path.replace(self.path.with_suffix(".broken"))
            except OSError:
                pass
            return
        self.offset = int(data.get("offset") or 0)
        self.connections = dict(data.get("connections") or {})
        self.last_top = {
            str(k): [int(x) for x in v] for k, v in (data.get("last_top") or {}).items() if isinstance(v, list)
        }
        self.stats.update({str(k): int(v) for k, v in (data.get("stats") or {}).items()})

    def save(self, force: bool = False) -> None:
        if not self._dirty and not force:
            return
        payload = {
            "version": 1,
            "offset": self.offset,
            "connections": self.connections,
            "last_top": {k: v[-20:] for k, v in list(self.last_top.items())[-50:]},
            "stats": self.stats,
        }
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(payload, fh, ensure_ascii=False, indent=2)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp, self.path)
            try:
                os.chmod(self.path, 0o600)
            except OSError:
                pass
            self._dirty = False
        except OSError as exc:
            self.logger.warning("Не смог сохранить state %s: %s", self.path, exc)

    def touch(self) -> None:
        self._dirty = True

    def set_last_top(self, chat_key: str, message_ids: List[int]) -> None:
        self.last_top[chat_key] = message_ids
        self.touch()

    def bump(self, key: str) -> None:
        self.stats[key] = int(self.stats.get(key, 0)) + 1
        self.touch()


# ---------------------------------------------------------------------------
# Данные топа
# ---------------------------------------------------------------------------


@dataclasses.dataclass
class Entry:
    name: str
    score: Optional[float] = None

    def score_text(self) -> str:
        if self.score is None:
            return ""
        if float(self.score).is_integer():
            return str(int(self.score))
        return f"{self.score:g}".replace(".", ",")


class TopData:
    """Читает top.json/top.txt и рендерит сообщение.

    Файл перечитывается на каждый вызов, но парсится только если содержимое
    изменилось: правки в top.json подхватываются без перезапуска и без
    зависимости от точности mtime (на Android бывает и секундная гранулярность).
    """

    def __init__(self, cfg: Config, logger: logging.Logger) -> None:
        self.cfg = cfg
        self.logger = logger
        self.title = cfg.title
        self.entries: List[Entry] = [Entry(name) for name in DEFAULT_TOP_FALLBACK]
        self._raw: Optional[str] = None
        self._last_error: Optional[str] = None
        self.load(force=True)

    # -- загрузка ----------------------------------------------------------

    def load(self, force: bool = False) -> bool:
        path = self.cfg.top_file
        try:
            raw = path.read_text(encoding="utf-8-sig")
        except FileNotFoundError:
            if self._last_error != "missing":
                self.logger.warning(
                    "Файл топа %s не найден — использую встроенный список %s",
                    path, DEFAULT_TOP_FALLBACK,
                )
                self._last_error = "missing"
            return False
        except (OSError, UnicodeDecodeError) as exc:
            if self._last_error != str(exc):
                self.logger.warning("Не могу прочитать %s: %s", path, exc)
                self._last_error = str(exc)
            return False

        if not force and self._raw == raw:
            return True

        try:
            entries, title = self._parse(raw)
        except Exception as exc:  # noqa: BLE001 - кривой файл не должен ронять бота
            message = f"{type(exc).__name__}: {exc}"
            if self._last_error != message:
                self.logger.error(
                    "Ошибка в %s: %s. Оставляю предыдущий список (%d поз.)", path, message, len(self.entries)
                )
                self._last_error = message
            return False

        self._raw = raw
        self._last_error = None
        self.entries = entries
        self.title = title or self.cfg.title
        self.logger.info("Топ загружен: %d поз. из %s", len(self.entries), path)
        return True

    def _parse(self, raw: str) -> Tuple[List[Entry], Optional[str]]:
        stripped = raw.strip()

        if stripped.startswith("{") or stripped.startswith("["):
            data = json.loads(stripped)
            if isinstance(data, list):
                return self._entries_from_list(data), None
            if isinstance(data, dict):
                entries = self._entries_from_list(data.get("entries") or data.get("items") or [])
                title = data.get("title")
                return entries, (str(title) if title is not None else None)
            raise ValueError("ожидался массив или объект с полем entries")

        # обычный текст: одна строка = одна позиция, опционально "Имя | 100"
        entries: List[Entry] = []
        for line in stripped.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            score: Optional[float] = None
            name = line
            if "|" in line:
                left, right = line.rsplit("|", 1)
                try:
                    score = float(right.strip().replace(",", "."))
                    name = left.strip()
                except ValueError:
                    name = line
            entries.append(self._make_entry(name, score))
        return entries, None

    def _entries_from_list(self, data: Any) -> List[Entry]:
        if not isinstance(data, list):
            raise ValueError("entries должен быть массивом")
        entries: List[Entry] = []
        for item in data:
            if isinstance(item, str):
                entries.append(self._make_entry(item, None))
            elif isinstance(item, dict):
                name = item.get("name") or item.get("title")
                if not name:
                    continue
                score = item.get("score")
                try:
                    score_value = float(score) if score is not None else None
                except (TypeError, ValueError):
                    score_value = None
                entries.append(self._make_entry(str(name), score_value))
            else:
                raise ValueError(f"неподдерживаемый элемент списка: {item!r}")
        if not entries:
            raise ValueError("список пуст")
        return entries

    def _make_entry(self, name: str, score: Optional[float]) -> Entry:
        name = " ".join(name.split())
        if not name:
            raise ValueError("пустое имя")
        if len(name) > 200:
            name = name[:197] + "…"
        return Entry(name, score)

    # -- рендер ------------------------------------------------------------

    def ordered(self) -> List[Entry]:
        entries = list(self.entries)
        if self.cfg.sort_by_score and any(e.score is not None for e in entries):
            entries.sort(key=lambda e: (e.score is None, -(e.score or 0.0)))
        if self.cfg.top_limit:
            entries = entries[: self.cfg.top_limit]
        return entries[:MAX_ENTRIES]

    def render(self) -> str:
        self.load()
        entries = self.ordered()
        use_html = self.cfg.parse_mode_value is not None

        lines: List[str] = []
        for index, entry in enumerate(entries, start=1):
            name = html.escape(entry.name, quote=False) if use_html else entry.name
            try:
                line = self.cfg.item_template.format(
                    n=index, name=name, score=entry.score_text(), title=self.title
                )
            except (KeyError, IndexError, ValueError):
                # кривой шаблон не должен убивать отправку
                line = f"{index}. {name}"
            if entry.score is not None and "{score}" not in self.cfg.item_template:
                line = f"{line} — {entry.score_text()}"
            if self.cfg.show_medals and index <= len(MEDALS):
                line = f"{MEDALS[index - 1]} {line}"
            lines.append(line)

        header = self._format(self.cfg.header, len(entries))
        footer = self._format(self.cfg.footer, len(entries))
        body = self.cfg.separator.join(lines)

        parts = [part for part in (header, body, footer) if part]
        return "\n\n".join(parts)

    def _format(self, template: str, count: int) -> str:
        if not template:
            return ""
        title = self.title
        if self.cfg.parse_mode_value is not None:
            title = html.escape(title, quote=False)
            template = template.replace("{title}", title)
        else:
            template = template.replace("{title}", title)
        return template.replace("{count}", str(count)).replace("\\n", "\n")


def split_text(text: str, limit: int = MAX_TEXT_UTF16) -> List[str]:
    """Режет текст по лимиту UTF-16, не разрывая строки без нужды."""
    if utf16_len(text) <= limit:
        return [text]

    chunks: List[str] = []
    current = ""
    for line in text.split("\n"):
        candidate = line if not current else f"{current}\n{line}"
        if utf16_len(candidate) <= limit:
            current = candidate
            continue
        if current:
            chunks.append(current)
            current = ""
        while utf16_len(line) > limit:
            width = 0
            cut = 0
            for cut, ch in enumerate(line, start=1):
                width += 2 if ord(ch) > 0xFFFF else 1
                if width > limit:
                    cut -= 1
                    break
            chunks.append(line[:cut])
            line = line[cut:]
        current = line
    if current:
        chunks.append(current)
    return [c for c in chunks if c] or [""]


# ---------------------------------------------------------------------------
# Фейковый API для --selftest и юнит-тестов
# ---------------------------------------------------------------------------


class FakeTelegramAPI(TelegramAPI):
    """Наследует высокоуровневые методы, но никуда не ходит по сети."""

    def __init__(self, fail_on: Optional[Dict[str, TelegramError]] = None) -> None:
        # TelegramAPI.__init__ намеренно не вызываем: сеть и TLS не нужны.
        self.calls: List[Tuple[str, Dict[str, Any]]] = []
        self.fail_on = fail_on or {}
        self._next_id = 100
        self.dry_run = False
        self.logger = logging.getLogger("topbot")

    READ_ONLY_METHODS = (
        "getMe",
        "getUpdates",
        "getWebhookInfo",
        "getBusinessConnection",
    )

    def call(self, method: str, params: Optional[Dict[str, Any]] = None) -> Any:
        params = dict(params or {})
        if self.dry_run and method not in self.READ_ONLY_METHODS:
            self.calls.append((method, params))
            return True
        self.calls.append((method, params))
        if method in self.fail_on:
            raise self.fail_on[method]
        if method == "sendMessage":
            self._next_id += 1
            return {"message_id": self._next_id, "date": int(time.time()), "text": params.get("text")}
        if method == "getMe":
            return {"id": 42, "is_bot": True, "first_name": "TopBot", "username": "top_bot"}
        return True

    # помощники для тестов
    def methods(self) -> List[str]:
        return [m for m, _ in self.calls]

    def only(self, method: str) -> List[Dict[str, Any]]:
        return [p for m, p in self.calls if m == method]

    def clear(self) -> None:
        self.calls.clear()


# ---------------------------------------------------------------------------
# Сам бот
# ---------------------------------------------------------------------------


class Bot:
    def __init__(
        self,
        cfg: Config,
        api: Any,
        state: Optional[State] = None,
        logger: Optional[logging.Logger] = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.cfg = cfg
        self.api = api
        self.logger = logger or logging.getLogger("topbot")
        self.state = state or State(cfg.state_file, self.logger)
        self.top = TopData(cfg, self.logger)
        self.clock = clock
        self.offset = self.state.offset
        self._cooldowns: Dict[str, float] = {}
        self._send_windows: Dict[str, Deque[float]] = defaultdict(deque)
        self._stop = threading.Event()
        self.bot_username: Optional[str] = None

    # -- инфраструктура ----------------------------------------------------

    def stop(self) -> None:
        self._stop.set()

    def _chat_key(self, chat_id: int, conn_id: Optional[str]) -> str:
        return f"{conn_id or 'dm'}:{chat_id}"

    def _is_html(self) -> bool:
        return self.cfg.parse_mode_value is not None

    BUILTIN_COMMANDS = ("/start", "/help", "/id", "/reload")

    def _parse_command(self, text: str) -> Optional[str]:
        """'/top', '/top@MyBot arg' → '/top'; иначе None."""
        if not text or not text.startswith("/"):
            return None
        first = text.split(maxsplit=1)[0]
        if "@" in first:
            first = first.split("@", 1)[0]
        first = first.lower()
        if first in self.cfg.commands or first in self.BUILTIN_COMMANDS:
            return first
        return None

    def _command_args(self, text: str) -> str:
        parts = text.split(maxsplit=1)
        return parts[1].strip() if len(parts) > 1 else ""

    # -- обработка апдейтов ------------------------------------------------

    def handle_update(self, update: Dict[str, Any]) -> None:
        try:
            self._handle_update(update)
        except TelegramError as exc:
            self.logger.error("Ошибка API при обработке апдейта: %s", exc)
        except Exception:  # noqa: BLE001 - бот не должен умирать из-за одного апдейта
            self.logger.exception("Необработанная ошибка в апдейте %s", update.get("update_id"))

    def _handle_update(self, update: Dict[str, Any]) -> None:
        if "business_connection" in update:
            self._on_business_connection(update["business_connection"])
            return

        if "my_chat_member" in update:
            self._on_my_chat_member(update["my_chat_member"])
            return

        message = update.get("message") or update.get("business_message")
        if not isinstance(message, dict):
            return

        conn_id = message.get("business_connection_id") or update.get("business_connection_id")
        is_business = bool(conn_id)
        chat = message.get("chat") or {}
        chat_id = chat.get("id")
        chat_type = chat.get("type", "private")
        message_id = message.get("message_id")
        sender = message.get("from") or {}
        sender_id = sender.get("id")
        text = message.get("text") or ""
        date = int(message.get("date") or 0)

        if chat_id is None or message_id is None:
            return
        if sender.get("is_bot"):
            return

        if self.cfg.mode == "private" and is_business:
            return
        if self.cfg.mode == "business" and not is_business:
            return

        if is_business:
            connection = self.state.connections.get(str(conn_id)) or {}
            if connection and connection.get("is_enabled") is False:
                self.logger.info(
                    "Пропускаю: бизнес-подключение %s отключено владельцем", conn_id
                )
                return

        # Старые сообщения (пока бот лежал/перезапускался) не обрабатываем:
        # иначе после рестарта прилетит пачка ответов на вчерашние /top.
        if self.cfg.max_message_age_seconds and date:
            age = time.time() - date
            if age > self.cfg.max_message_age_seconds:
                self.logger.info(
                    "Пропускаю старое сообщение (%d c): chat=%s msg=%s", int(age), chat_id, message_id
                )
                return

        command = self._parse_command(text)
        if command is None:
            if chat_type == "private" and not is_business:
                self.logger.debug("Не команда: %s", log_text(text, 80))
            return

        if not self._access_allowed(chat_id, chat_type, sender_id, conn_id):
            return

        if command in self.cfg.commands:
            self._handle_top(message, conn_id, chat_id, message_id, text)
        elif command == "/start":
            self._handle_start(message, conn_id, chat_id, message_id)
        elif command == "/help":
            self._handle_help(message, conn_id, chat_id, message_id)
        elif command == "/id":
            self._handle_id(message, conn_id, chat_id, message_id)
        elif command == "/reload":
            self._handle_reload(message, conn_id, chat_id, message_id, sender_id)

    def _access_allowed(
        self, chat_id: int, chat_type: str, sender_id: Optional[int], conn_id: Optional[str]
    ) -> bool:
        if chat_type != "private" and not self.cfg.allow_groups:
            self.logger.info("Игнорирую команду в чате типа %s (ALLOW_GROUPS=false)", chat_type)
            return False
        if self.cfg.allowed_chats and chat_id not in self.cfg.allowed_chats:
            self.logger.info("Чат %s не в белом списке ALLOWED_CHAT_IDS", chat_id)
            return False
        if self.cfg.allowed_users and (sender_id is None or sender_id not in self.cfg.allowed_users):
            self.logger.info("Пользователь %s не в белом списке ALLOWED_USER_IDS", sender_id)
            return False
        return True

    def _on_business_connection(self, connection: Dict[str, Any]) -> None:
        conn_id = connection.get("id")
        if not conn_id:
            return
        record = {
            "user_chat_id": connection.get("user_chat_id"),
            "is_enabled": bool(connection.get("is_enabled")),
            "rights": connection.get("rights") or {},
            "date": connection.get("date"),
        }
        self.state.connections[conn_id] = record
        self.state.touch()
        rights = record["rights"]
        can_delete_all = bool(rights.get("can_delete_all_messages"))
        can_reply = bool(rights.get("can_reply"))
        if record["is_enabled"]:
            self.logger.info(
                "Бизнес-подключение %s: can_reply=%s, can_delete_all_messages=%s",
                conn_id, can_reply, can_delete_all,
            )
            if not can_delete_all:
                self.logger.warning(
                    "У бизнес-бота нет права «Удалять полученные сообщения» (can_delete_all_messages). "
                    "Команда /top не будет удаляться — включите право в Настройки → Telegram Business → Чат-боты."
                )
            if not can_reply:
                self.logger.warning(
                    "У бизнес-бота нет права «Отвечать на сообщения» (can_reply) — отправка топа будет падать."
                )
        else:
            self.logger.warning("Бизнес-подключение %s отключено владельцем аккаунта.", conn_id)

    def _on_my_chat_member(self, update: Dict[str, Any]) -> None:
        chat = update.get("chat") or {}
        status = (update.get("new_chat_member") or {}).get("status")
        if status in ("kicked", "left"):
            self.logger.warning("Бота удалили/заблокировали в чате %s", chat.get("id"))
        elif status == "member":
            self.logger.info("Бота добавили в чат %s", chat.get("id"))

    # -- /top --------------------------------------------------------------

    def _rate_limited(self, chat_key: str) -> bool:
        now = self.clock()
        cooldown = self.cfg.cooldown_seconds
        if cooldown:
            last = self._cooldowns.get(chat_key)
            if last is not None and now - last < cooldown:
                self.logger.info(
                    "Кулдаун: %.1f c из %.1f, пропускаю /top в %s",
                    now - last, cooldown, chat_key,
                )
                return True
        window = self._send_windows[chat_key]
        while window and now - window[0] > 60:
            window.popleft()
        if len(window) >= self.cfg.max_per_minute:
            self.logger.warning(
                "Лимит %d топов/мин для %s — пропускаю, чтобы не получить FloodWait",
                self.cfg.max_per_minute, chat_key,
            )
            return True
        return False

    def _mark_sent(self, chat_key: str) -> None:
        now = self.clock()
        self._cooldowns[chat_key] = now
        self._send_windows[chat_key].append(now)
        if len(self._cooldowns) > 5000:  # простая защита от роста памяти
            cutoff = now - 3600
            self._cooldowns = {k: v for k, v in self._cooldowns.items() if v >= cutoff}

    def _handle_top(
        self,
        message: Dict[str, Any],
        conn_id: Optional[str],
        chat_id: int,
        message_id: int,
        text: str,
    ) -> None:
        chat_key = self._chat_key(chat_id, conn_id)
        if self._rate_limited(chat_key):
            return

        self.logger.info(
            "/top: chat=%s user=%s msg=%s business=%s",
            chat_id, (message.get("from") or {}).get("id"), message_id, conn_id or "нет",
        )

        # 1) Сначала удаляем сообщение с командой — ровно то, что просил заказчик.
        if self.cfg.delete_command:
            self._delete_safely(
                chat_id, message_id, conn_id, purpose="команда /top",
                require_delete_right=True,
            )

        # 2) Затем отправляем НОВОЕ сообщение (никаких editMessageText).
        self._send_top(chat_id, conn_id, reply_to=message_id if self.cfg.reply_to_command else None)

    def _delete_safely(
        self,
        chat_id: int,
        message_id: int,
        conn_id: Optional[str],
        purpose: str,
        require_delete_right: bool = False,
    ) -> bool:
        if conn_id and require_delete_right:
            rights = (self.state.connections.get(conn_id) or {}).get("rights") or {}
            if rights and not rights.get("can_delete_all_messages"):
                self.logger.warning(
                    "Пропускаю удаление (%s): нет права can_delete_all_messages у бизнес-бота", purpose
                )
                return False
        try:
            self.api.delete_message(chat_id, message_id, conn_id)
            self.state.bump("messages_deleted")
            self.logger.info("Удалено сообщение %s (%s)", message_id, purpose)
            return True
        except TelegramError as exc:
            low = (exc.description or "").lower()
            if "message to delete not found" in low:
                self.logger.info("Сообщение %s уже удалено (%s)", message_id, purpose)
                return True
            hint = error_hint(exc.description)
            self.logger.warning(
                "Не смог удалить %s (%s): %s%s",
                message_id, purpose, exc, f" — {hint}" if hint else "",
            )
            return False
        except Exception:  # noqa: BLE001
            self.logger.exception("Неожиданная ошибка при удалении %s", message_id)
            return False

    def _send_top(self, chat_id: int, conn_id: Optional[str], reply_to: Optional[int] = None) -> List[int]:
        try:
            text = self.top.render()
        except Exception:  # noqa: BLE001
            self.logger.exception("Не смог собрать текст топа")
            return []

        if not text.strip():
            self.logger.error("Текст топа пустой — проверьте TOP_HEADER/ITEM_TEMPLATE и top.json")
            return []

        chat_key = self._chat_key(chat_id, conn_id)

        if self.cfg.delete_previous:
            previous = self.state.last_top.get(chat_key) or []
            for old_id in previous:
                self._delete_safely(chat_id, old_id, conn_id, purpose="прошлый топ")

        if self.cfg.typing:
            try:
                self.api.send_typing(chat_id, conn_id)
            except TelegramError:
                pass

        chunks = split_text(text)
        sent: List[int] = []
        for index, chunk in enumerate(chunks, start=1):
            try:
                response = self.api.send_message(
                    chat_id=chat_id,
                    text=chunk,
                    business_connection_id=conn_id,
                    reply_to=reply_to if index == 1 else None,
                    parse_mode=self.cfg.parse_mode_value,
                )
            except TelegramError as exc:
                self.logger.error("Не смог отправить топ в %s: %s", chat_id, exc)
                return sent
            if isinstance(response, dict) and response.get("message_id"):
                sent.append(int(response["message_id"]))
            self.logger.info(
                "Топ отправлен: chat=%s msg=%s часть=%d/%d символов=%d",
                chat_id, sent[-1] if sent else "?", index, len(chunks), utf16_len(chunk),
            )

        if sent:
            self.state.set_last_top(chat_key, sent)
            self.state.bump("tops_sent")
            self._mark_sent(chat_key)
            if self.cfg.pin_top:
                if conn_id:
                    self.logger.info("PIN_TOP пропущен: в бизнес-чате закрепление ботом не поддерживается")
                else:
                    try:
                        self.api.pin_message(chat_id, sent[0])
                    except TelegramError as exc:
                        self.logger.warning("Не смог закрепить топ: %s", exc)
        return sent

    # -- прочие команды ----------------------------------------------------

    def _send_text(
        self,
        chat_id: int,
        conn_id: Optional[str],
        text: str,
        reply_to: Optional[int] = None,
    ) -> None:
        try:
            self.api.send_message(
                chat_id=chat_id,
                text=text,
                business_connection_id=conn_id,
                reply_to=reply_to,
                parse_mode=self.cfg.parse_mode_value,
            )
        except TelegramError as exc:
            self.logger.error("Не смог отправить служебный текст: %s", exc)

    def _handle_start(self, message: Dict[str, Any], conn_id: Optional[str], chat_id: int, message_id: int) -> None:
        if not self.cfg.send_help:
            return
        primary = self.cfg.commands[0]
        text = (
            f"Привет! Напиши {primary} — пришлю актуальный топ.\n"
            f"Сообщение с командой удаляется, ответ приходит новым сообщением."
        )
        self._send_text(chat_id, conn_id, text)

    def _handle_help(self, message: Dict[str, Any], conn_id: Optional[str], chat_id: int, message_id: int) -> None:
        if not self.cfg.send_help:
            return
        primary = self.cfg.commands[0]
        lines = [f"{primary} — показать топ", "/id — узнать chat_id (для настройки)"]
        if self.cfg.admin_ids:
            lines.append("/reload — перечитать файл топа (админ)")
        self._send_text(chat_id, conn_id, "\n".join(lines))

    def _handle_id(self, message: Dict[str, Any], conn_id: Optional[str], chat_id: int, message_id: int) -> None:
        user_id = (message.get("from") or {}).get("id")
        text = f"chat_id: {chat_id}\nuser_id: {user_id}"
        if conn_id:
            text += f"\nbusiness_connection_id: {conn_id}"
        self._send_text(chat_id, conn_id, text)

    def _handle_reload(
        self,
        message: Dict[str, Any],
        conn_id: Optional[str],
        chat_id: int,
        message_id: int,
        sender_id: Optional[int],
    ) -> None:
        if not self.cfg.admin_ids or sender_id not in self.cfg.admin_ids:
            self.logger.info("Отказ /reload: %s не админ", sender_id)
            return
        ok = self.top.load(force=True)
        self._send_text(
            chat_id,
            conn_id,
            "Топ перечитан: %d позиций" % len(self.top.entries) if ok else "Не смог перечитать файл топа (смотри лог)",
        )

    # -- основной цикл -----------------------------------------------------

    def bootstrap(self) -> None:
        me = self.api.get_me()
        self.bot_username = me.get("username")
        self.logger.info(
            "Бот: @%s (id=%s, name=%s)",
            me.get("username"), me.get("id"), me.get("first_name"),
        )

        if self.cfg.delete_webhook_on_start:
            try:
                info = self.api.get_webhook_info()
                if info.get("url"):
                    self.logger.warning(
                        "У бота был webhook (%s) — снимаю, иначе getUpdates даёт 409 Conflict", info["url"]
                    )
            except TelegramError as exc:
                self.logger.debug("getWebhookInfo: %s", exc)
        if self.cfg.delete_webhook_on_start:
            try:
                self.api.delete_webhook()
            except TelegramError as exc:
                self.logger.warning("deleteWebhook: %s", exc)
        else:
            self.logger.info("DELETE_WEBHOOK_ON_START=false — webhook не трогаю")

        if self.cfg.drop_pending_updates:
            try:
                last = self.api.get_updates(offset=-1, timeout=0, limit=1)
                if last:
                    last_id = int(last[-1].get("update_id") or 0)
                    self.offset = max(self.offset, last_id + 1)
                    self.state.offset = self.offset
                    self.state.touch()
                self.logger.info(
                    "Пропущены накопившиеся апдейты (DROP_PENDING_UPDATES=true), offset=%d", self.offset
                )
            except TelegramError as exc:
                self.logger.warning("drop pending: %s", exc)

        if self.cfg.set_commands_menu:
            try:
                commands = [(self.cfg.commands[0], "Показать топ"), ("/help", "Помощь")]
                self.api.set_my_commands(commands)
            except TelegramError as exc:
                self.logger.debug("setMyCommands: %s", exc)

        self.logger.info("Слушаю обновления (long polling, timeout=%d c)", self.cfg.poll_timeout)

    def poll_once(self, timeout: Optional[int] = None, limit: int = 100) -> int:
        updates = self.api.get_updates(
            offset=self.offset,
            timeout=self.cfg.poll_timeout if timeout is None else timeout,
            limit=limit,
        )
        for update in updates:
            if self._stop.is_set():
                break
            self.handle_update(update)
            update_id = int(update.get("update_id") or 0)
            if update_id >= self.offset:
                self.offset = update_id + 1
                self.state.offset = self.offset
                self.state.touch()
        if updates:
            self.state.save()
        return len(updates)

    def run(self, max_updates: Optional[int] = None, once: bool = False) -> int:
        self.bootstrap()
        processed = 0
        backoff = 3.0
        while not self._stop.is_set():
            try:
                batch = self.poll_once()
                backoff = 3.0
                processed += batch
                if batch == 0 and once:
                    break
                if once and batch:
                    break
                if max_updates is not None and processed >= max_updates:
                    break
            except TelegramError as exc:
                low = (exc.description or "").lower()
                if exc.code in (401, 404):
                    self.logger.critical("Фатально: %s", exc)
                    self.logger.critical(error_hint(exc.description))
                    return 3
                if exc.code == 409 or "conflict" in low:
                    self.logger.critical(
                        "409 Conflict: уже запущен другой экземпляр бота или остался webhook. "
                        "Остановите второй процесс (ps aux | grep bot.py; pkill -f bot.py) и запустите снова."
                    )
                    return 2
                self.logger.error("Сбой опроса: %s. Повтор через %.0f c", exc, backoff)
                if self._stop.wait(backoff):
                    break
                backoff = min(60.0, backoff * 2)
            except Exception:  # noqa: BLE001
                self.logger.exception("Неожиданный сбой в цикле опроса")
                if self._stop.wait(backoff):
                    break
                backoff = min(60.0, backoff * 2)
        self.state.save(force=True)
        self.logger.info("Остановлено. Отправлено топов за сессию: %d", processed)
        return 0


# ---------------------------------------------------------------------------
# Проверки и режимы CLI
# ---------------------------------------------------------------------------


def print_top(cfg: Config, logger: logging.Logger) -> int:
    top = TopData(cfg, logger)
    text = top.render()
    print("—" * 60)
    print(text)
    print("—" * 60)
    print(f"Позиций: {len(top.ordered())}, UTF-16 символов: {utf16_len(text)}, "
          f"частей при отправке: {len(split_text(text))}")
    return 0


def check(cfg: Config, logger: logging.Logger) -> int:
    problems: List[str] = []
    warnings: List[str] = []

    print("Конфигурация:\n" + cfg.summary() + "\n")

    if not cfg.token:
        problems.append("BOT_TOKEN пуст — впишите токен от @BotFather в .env")
    elif ":" not in cfg.token or not cfg.token.split(":", 1)[0].isdigit():
        problems.append("BOT_TOKEN не похож на токен (ожидается 123456789:AA...)")

    top = TopData(cfg, logger)
    if not cfg.top_file.exists():
        warnings.append(f"Файл топа {cfg.top_file} не найден — используется встроенный список")
    text = top.render()
    print("Предпросмотр сообщения:\n" + ("—" * 60) + f"\n{text}\n" + ("—" * 60))
    print(f"Позиций: {len(top.ordered())}; символов UTF-16: {utf16_len(text)}; "
          f"частей: {len(split_text(text))}\n")

    if cfg.token and ":" in cfg.token:
        api = TelegramAPI(
            token=cfg.token,
            api_base=cfg.api_base,
            poll_timeout=cfg.poll_timeout,
            insecure_tls=cfg.insecure_tls,
            ca_bundle=cfg.ca_bundle,
            logger=logger,
            max_network_retries=2,  # диагностика не должна ждать минуту
        )
        try:
            me = api.get_me()
            print(f"getMe: @{me.get('username')} (id={me.get('id')}, name={me.get('first_name')})")
        except TelegramError as exc:
            problems.append(f"getMe не прошёл: {exc}. {error_hint(exc.description)}".strip())
        else:
            try:
                info = api.get_webhook_info()
                url = info.get("url") or ""
                if url:
                    warnings.append(
                        f"У бота установлен webhook ({url}) — при long polling будет 409 Conflict. "
                        "Бот снимает webhook сам при старте."
                    )
                pending = info.get("pending_update_count")
                if pending:
                    print(f"В очереди обновлений: {pending}")
            except TelegramError as exc:
                warnings.append(f"getWebhookInfo: {exc}")
            if cfg.delete_webhook_on_start:
                try:
                    api.delete_webhook()
                    print("deleteWebhook: ok")
                except TelegramError as exc:
                    warnings.append(f"deleteWebhook: {exc}")
            else:
                print("deleteWebhook: пропущен (DELETE_WEBHOOK_ON_START=false)")

    print()
    for warning in warnings:
        print(f"[!] {warning}")
    for problem in problems:
        print(f"[x] {problem}")

    if problems:
        print("\nИтог: есть ошибки, запускать нельзя.")
        return 1
    print("Итог: всё готово к запуску." + (" (dry-run включён)" if cfg.dry_run else ""))
    return 0


def build_test_message(chat_id: int = 1, message_id: int = 10, text: str = "/top") -> Dict[str, Any]:
    return {
        "message_id": message_id,
        "date": int(time.time()),
        "chat": {"id": chat_id, "type": "private"},
        "from": {"id": chat_id, "is_bot": False, "first_name": "Tester"},
        "text": text,
    }


def selftest(cfg: Config, logger: logging.Logger) -> int:
    """Офлайн-проверка логики без сети и токена."""
    failures: List[str] = []

    def expect(name: str, condition: bool, detail: str = "") -> None:
        if condition:
            print(f"  ok   {name}")
        else:
            print(f"  FAIL {name} {detail}")
            failures.append(name)

    def make_bot() -> Tuple[Bot, FakeTelegramAPI, List[float]]:
        fake_time = [1000.0]
        api = FakeTelegramAPI()
        local_cfg = dataclasses.replace(cfg)
        local_cfg.token = "123456:SELFTEST"
        local_cfg.top_file = cfg.base_dir / "top.json"
        local_cfg.state_file = cfg.base_dir / ".selftest-state.json"
        local_cfg.cooldown_seconds = 0.0
        local_cfg.max_per_minute = 100
        local_cfg.delete_previous = False
        fake_state = State(local_cfg.state_file, logger)
        fake_state.offset = 0
        bot = Bot(local_cfg, api, state=fake_state, logger=logger, clock=lambda: fake_time[0])
        bot.top.entries = [Entry("Misha"), Entry("Misha"), Entry("misha")]
        bot.top.title = "ТОП"
        return bot, api, fake_time

    print("Проверка логики /top:")

    bot, api, _ = make_bot()
    bot.handle_update({"update_id": 1, "message": build_test_message()})
    expect("deleteMessage вызван", "deleteMessage" in api.methods(), str(api.methods()))
    expect("deleteMessage до sendMessage", api.methods().index("deleteMessage") < api.methods().index("sendMessage"))
    expect(
        "удаляется именно /top",
        api.only("deleteMessage") and api.only("deleteMessage")[0]["message_id"] == 10,
    )
    sent = api.only("sendMessage")
    expect("ровно одно сообщение", len(sent) == 1, str(len(sent)))
    body = sent[0]["text"] if sent else ""
    expect("нумерация 1. Misha", "1. Misha" in body, body)
    expect("заголовок перед списком", body.index("ТОП") < body.index("1. Misha"), body)
    expect("нумерация 2. Misha", "2. Misha" in body, body)
    expect("нумерация 3. misha", "3. misha" in body, body)
    expect("нет editMessageText", "editMessageText" not in api.methods())

    bot.handle_update({"update_id": 2, "message": build_test_message(message_id=11)})
    expect("второй /top = второе новое сообщение", len(api.only("sendMessage")) == 2)
    expect("по-прежнему без редактирования", "editMessageText" not in api.methods())

    # кулдаун
    bot2, api2, fake_time2 = make_bot()
    bot2.cfg.cooldown_seconds = 5.0
    bot2.handle_update({"update_id": 3, "message": build_test_message()})
    bot2.handle_update({"update_id": 4, "message": build_test_message(message_id=12)})
    expect("кулдаун блокирует второй вызов", len(api2.only("sendMessage")) == 1)
    fake_time2[0] += 10
    bot2.handle_update({"update_id": 5, "message": build_test_message(message_id=13)})
    expect("после кулдауна снова отвечает", len(api2.only("sendMessage")) == 2)

    # бизнес-режим
    bot3, api3, _ = make_bot()
    business_message = build_test_message()
    business_message["business_connection_id"] = "bc-1"
    bot3.state.connections["bc-1"] = {"rights": {"can_delete_all_messages": True, "can_reply": True}}
    bot3.handle_update({"update_id": 6, "business_message": business_message})
    expect("бизнес: deleteBusinessMessages", "deleteBusinessMessages" in api3.methods(), str(api3.methods()))
    if api3.only("deleteBusinessMessages"):
        expect(
            "бизнес: передан business_connection_id",
            api3.only("deleteBusinessMessages")[0].get("business_connection_id") == "bc-1",
        )
    expect(
        "бизнес: sendMessage с business_connection_id",
        api3.only("sendMessage") and api3.only("sendMessage")[0].get("business_connection_id") == "bc-1",
    )

    # без права удаления — не пытаемся удалять, но топ отправляем
    bot4, api4, _ = make_bot()
    business_message4 = build_test_message()
    business_message4["business_connection_id"] = "bc-2"
    bot4.state.connections["bc-2"] = {"rights": {"can_delete_all_messages": False, "can_reply": True}}
    bot4.handle_update({"update_id": 7, "business_message": business_message4})
    expect("нет права — удаление пропущено", "deleteBusinessMessages" not in api4.methods())
    expect("нет права — топ всё равно отправлен", len(api4.only("sendMessage")) == 1)

    # не команда
    bot5, api5, _ = make_bot()
    bot5.handle_update({"update_id": 8, "message": build_test_message(text="привет")})
    expect("обычный текст игнорируется", not api5.calls, str(api5.calls))

    # /top@bot
    bot6, api6, _ = make_bot()
    bot6.handle_update({"update_id": 9, "message": build_test_message(text="/top@my_bot")})
    expect("/top@my_bot распознан", len(api6.only("sendMessage")) == 1)

    # устаревшее сообщение
    bot7, api7, _ = make_bot()
    old = build_test_message()
    old["date"] = int(time.time()) - 3600
    bot7.handle_update({"update_id": 10, "message": old})
    expect("старое сообщение пропущено", not api7.calls)

    # белый список
    bot8, api8, _ = make_bot()
    bot8.cfg.allowed_users = {999}
    bot8.handle_update({"update_id": 11, "message": build_test_message(chat_id=1)})
    expect("чужой пользователь отсечён", not api8.calls)

    # группы выключены
    bot9, api9, _ = make_bot()
    group = build_test_message()
    group["chat"] = {"id": -100123, "type": "supergroup"}
    bot9.handle_update({"update_id": 12, "message": group})
    expect("группа игнорируется при ALLOW_GROUPS=false", not api9.calls)

    # длинный текст
    bot10, api10, _ = make_bot()
    bot10.top.entries = [Entry("Name" * 40) for _ in range(40)]
    bot10.handle_update({"update_id": 13, "message": build_test_message()})
    texts = [p["text"] for p in api10.only("sendMessage")]
    expect("длинный топ разбит на части", len(texts) > 1, str(len(texts)))
    expect("все части в лимите", all(utf16_len(t) <= TELEGRAM_TEXT_LIMIT for t in texts))

    # экранирование HTML
    bot11, api11, _ = make_bot()
    bot11.top.entries = [Entry("<b>hack</b>")]
    bot11.top.render()
    bot11.handle_update({"update_id": 14, "message": build_test_message()})
    text11 = api11.only("sendMessage")[0]["text"]
    expect("HTML в имени экранирован", "&lt;b&gt;hack&lt;/b&gt;" in text11, text11)

    # ошибка удаления не мешает отправке
    bot12, api12, _ = make_bot()
    api12.fail_on["deleteMessage"] = TelegramError("Bad Request: message can't be deleted", 400)
    bot12.handle_update({"update_id": 15, "message": build_test_message()})
    expect("ошибка удаления не блокирует отправку", len(api12.only("sendMessage")) == 1)

    try:
        Path(bot.cfg.state_file).unlink(missing_ok=True)
    except OSError:
        pass

    print()
    if failures:
        print(f"ПРОВАЛЕНО: {len(failures)}")
        return 1
    print("Все проверки пройдены.")
    return 0


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="bot.py",
        description="Telegram TOP-бот: /top → удалить команду → отправить новый список.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Примеры:\n"
            "  python3 bot.py --check\n"
            "  python3 bot.py --print-top\n"
            "  python3 bot.py --selftest\n"
            "  python3 bot.py\n"
        ),
    )
    parser.add_argument("--env", help="путь к .env (по умолчанию ищется в папке скрипта и cwd)")
    parser.add_argument("--token", help="токен бота (перебивает .env)")
    parser.add_argument("--check", action="store_true", help="проверить токен/конфиг и выйти")
    parser.add_argument("--print-top", action="store_true", help="напечатать сообщение топа и выйти")
    parser.add_argument("--selftest", action="store_true", help="офлайн-тест логики")
    parser.add_argument("--once", action="store_true", help="обработать одну порцию апдейтов и выйти")
    parser.add_argument("--dry-run", action="store_true", help="не отправлять и не удалять (только логи)")
    parser.add_argument("--verbose", "-v", action="store_true", help="подробный лог (DEBUG)")
    parser.add_argument("--version", action="version", version=f"topbot {VERSION}")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    overrides: Dict[str, str] = {}
    if args.token:
        overrides["BOT_TOKEN"] = args.token
    cfg = load_config(args.env, overrides)
    if args.dry_run:
        cfg.dry_run = True

    logger = setup_logging(cfg, verbose=args.verbose)

    if args.print_top:
        return print_top(cfg, logger)
    if args.selftest:
        return selftest(cfg, logger)
    if args.check:
        return check(cfg, logger)

    if not cfg.token:
        print(
            "Нет токена. Создайте .env рядом с bot.py:\n\n"
            "    BOT_TOKEN=123456789:AA...\n\n"
            "или запустите: python3 bot.py --token '123456789:AA...'\n"
            "Токен выдаёт @BotFather (/newbot).",
            file=sys.stderr,
        )
        return 4

    api = TelegramAPI(
        token=cfg.token,
        api_base=cfg.api_base,
        poll_timeout=cfg.poll_timeout,
        dry_run=cfg.dry_run,
        insecure_tls=cfg.insecure_tls,
        ca_bundle=cfg.ca_bundle,
        logger=logger,
    )
    state = State(cfg.state_file, logger)
    bot = Bot(cfg, api, state=state, logger=logger)

    def _signal_handler(signum: int, _frame: Any) -> None:
        logger.info("Получен сигнал %s — завершаюсь аккуратно", signum)
        bot.stop()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, _signal_handler)
        except (ValueError, OSError):  # не главный поток / Windows
            pass

    try:
        return bot.run(once=args.once)
    except KeyboardInterrupt:
        bot.stop()
        state.save(force=True)
        return 0


if __name__ == "__main__":
    sys.exit(main())
