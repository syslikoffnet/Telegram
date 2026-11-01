#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gift Offer Bot — 1:1 намерения «подарок ↔ звёзды» без хранения средств.

Идея
====
Бот — это доска намерений, а не касса. Один человек публикует предложение
(«хочу купить подарок за N ⭐» или «продаю подарок за N ⭐»), второй принимает
намерение — и **обе стороны получают точную инструкцию**, как совершить
настоящую сделку через встроенный в Telegram «Offer to Buy».

Ключевой принцип: звёзды и подарки никогда не проходят через бота.
Официальный оффер замораживает Stars силами Telegram, при отказе или истечении
они возвращаются покупателю, а подарок передаётся в момент принятия оффера.
Поэтому украсть нечего, и бот не может «не выплатить».

Что делает бот
==============
* ``/offer <ссылка на подарок> <цена>`` — «хочу купить» (buy).
* ``/sell  <ссылка на подарок> <цена>`` — «продаю» (sell).
* команда удаляется (``deleteMessage`` / ``deleteBusinessMessages``),
  карточка приходит НОВЫМ сообщением;
* бот выдаёт ссылку-приглашение ``t.me/<бот>?start=offer_<id>`` для второй стороны;
* вторая сторона открывает карточку и жмёт «Принять намерение»;
* покупателю уходит инструкция отправить официальный Offer to Buy,
  продавцу — инструкция дождаться и принять его в клиенте;
* ``/myoffers``, ``/cancel <id>``, ``/how``, ``/help``;
* карточки со статусами бот правит только у СВОИХ сообщений (это разрешено),
  сообщения людей не редактируются никогда.

Экономика
=========
Бот берёт 0%. Комиссию при продаже подарка за звёзды берёт Telegram — её нельзя
обойти, и это честно написано в карточке и в README.

Зависимости
===========
Только стандартная библиотека Python 3.9+ (urllib, sqlite3, ssl). Ставится в
Termux без сборки колёс: ``pkg install python ca-certificates``.

Запуск
======
    python3 bot.py --check       # токен, БД, настройки
    python3 bot.py --selftest    # офлайн-проверки логики
    python3 bot.py               # рабочий режим (long polling)

Полная инструкция — README.md рядом.
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
import re
import signal
import socket
import sqlite3
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple

VERSION = "1.0.1"

MAX_TEXT_UTF16 = 3800
STARS = "⭐"
STATUSES = ("open", "accepted", "sent", "completed", "declined", "cancelled", "expired")
KINDS = ("buy", "sell")

ALLOWED_UPDATES = [
    "message",
    "callback_query",
    "business_connection",
    "business_message",
]

ERROR_HINTS: Tuple[Tuple[str, str], ...] = (
    (
        "message to delete not found",
        "Сообщение уже удалено — это нормально, бот продолжает.",
    ),
    (
        "message can't be deleted",
        "Нельзя удалить: старше 48 ч, служебное, нет прав. В бизнес-чате нужно право "
        "«Delete Received Messages».",
    ),
    (
        "message is not modified",
        "Карточка уже в этом состоянии — правка не требуется.",
    ),
    (
        "message can't be edited",
        "Бот может править только свои сообщения. Карточки — его, команды людей — нет.",
    ),
    (
        "not enough rights",
        "Не хватает прав: в группе нужен админ с «Удалять сообщения», в бизнес-чате — "
        "can_delete_all_messages / can_reply.",
    ),
    (
        "bot was blocked by the user",
        "Пользователь заблокировал бота — уведомление не доставлено.",
    ),
    (
        "bot can't initiate conversation",
        "Бот не может писать первым: человек должен сам нажать /start. "
        "Отправьте ему ссылку на карточку — после открытия уведомления заработают.",
    ),
    (
        "peer_flood",
        "PEER_FLOOD: Telegram временно ограничил боту рассылку в ЛС (антиспам). "
        "Пишите только тем, кто нажимал /start; в бизнес-чатах — с business_connection_id. "
        "Ограничение снимается само через несколько часов, ускорить нельзя.",
    ),
    (
        "can't parse entities",
        "Сломан HTML в тексте карточки — проверьте имена и ссылки.",
    ),
    (
        "message is too long",
        "Текст длиннее 4096 символов — уменьшите название/подпись.",
    ),
    (
        "query is too old",
        "Кнопка устарела (бот перезапускался) — откройте карточку заново.",
    ),
    (
        "conflict",
        "Запущено два экземпляра бота или стоит webhook: остановите лишний.",
    ),
    (
        "unauthorized",
        "Неверный токен: получите новый у @BotFather и запишите в .env.",
    ),
    (
        "not found",
        "Токен/бот не найден или неверный API_BASE.",
    ),
    (
        "too many requests",
        "Флуд-лимит Telegram: бот сам ждёт retry_after, при повторах увеличьте паузы.",
    ),
)


def error_hint(description: str) -> str:
    low = (description or "").lower()
    for needle, hint in ERROR_HINTS:
        if needle in low:
            return hint
    return ""


def mask_token(token: str) -> str:
    if not token:
        return "<пусто>"
    if len(token) <= 12:
        return token[:3] + "***"
    return f"{token[:8]}***{token[-2:]}"


def utf16_len(text: str) -> int:
    return sum(2 if ord(ch) > 0xFFFF else 1 for ch in text)


def log_text(text: str, limit: int = 200) -> str:
    flat = (text or "").replace("\n", "\\n")
    return flat if len(flat) <= limit else flat[:limit] + "…"


def esc(value: Any) -> str:
    return html.escape(str(value), quote=False)


# ---------------------------------------------------------------------------
# Ссылки, цены, названия
# ---------------------------------------------------------------------------

# t.me/nft/<slug>, t.me/<collection>/<number>, полные URL
GIFT_URL_RE = re.compile(
    r"^(?:https?://)?(?:t\.me|telegram\.me)/"
    r"(?:nft/[A-Za-z0-9_\-]{1,64}|[A-Za-z0-9_]{3,32}/\d{1,15})"
    r"/?$",
    re.IGNORECASE,
)

GIFT_URL_ANY_RE = re.compile(
    r"(?:https?://)?(?:t\.me|telegram\.me)/"
    r"(?:nft/[A-Za-z0-9_\-]{1,64}|[A-Za-z0-9_]{3,32}/\d{1,15})",
    re.IGNORECASE,
)

USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{5,32}$")

TOKEN_RE = re.compile(r"^\d{6,12}:[A-Za-z0-9_\-]{30,}$")


def normalize_gift_url(raw: str) -> Optional[str]:
    """Приводит ссылку к https://t.me/... или возвращает None."""
    if not raw:
        return None
    url = raw.strip().rstrip(".,;)]}\"'").rstrip("/")
    if url.startswith("http://"):
        url = "https://" + url[len("http://") :]
    if url.startswith("t.me/") or url.startswith("telegram.me/"):
        url = "https://" + url
    if url.startswith("https://telegram.me/"):
        url = "https://t.me/" + url[len("https://telegram.me/"):]
    if "?" in url or "#" in url:
        return None
    if not GIFT_URL_RE.match(url):
        return None
    return url


def gift_title_from_url(url: str) -> str:
    path = url.split("t.me/", 1)[-1].split("telegram.me/", 1)[-1].strip("/")
    parts = [p for p in path.split("/") if p]
    if len(parts) >= 2 and parts[0] == "nft":
        slug = parts[1].replace("_", " ").replace("-", " ").strip()
        return " ".join(word.capitalize() if word.islower() else word for word in slug.split()) or "Подарок"
    if len(parts) >= 2:
        name = parts[0].replace("_", " ").strip()
        return f"{name} #{parts[1]}"
    return "Подарок"


def parse_price(text_without_url: str) -> Optional[int]:
    """Достаёт цену: '100', '1 000', '250⭐', '1.500'."""
    match = re.search(r"\d[\d\s.,]*\d|\d", text_without_url)
    if not match:
        return None
    digits = re.sub(r"\D", "", match.group(0))
    if not digits or len(digits) > 9:
        return None
    return int(digits)


def parse_offer_text(text: str) -> Tuple[Optional[str], Optional[int]]:
    text = text or ""
    match = re.search(
        r"(?:https?://)?(?:t\.me|telegram\.me)/"
        r"(?:nft/[A-Za-z0-9_\-]{1,64}|[A-Za-z0-9_]{3,32}/\d{1,15})",
        text,
        re.IGNORECASE,
    )
    if not match:
        return None, None
    url = normalize_gift_url(match.group(0))
    rest = (text[: match.start()] + " " + text[match.end() :]).strip()
    price = parse_price(rest)
    return url, price


# ---------------------------------------------------------------------------
# Конфигурация
# ---------------------------------------------------------------------------


def read_env_file(path: Path) -> Dict[str, str]:
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
            line = line[7:].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        result[key] = value.replace("\\n", "\n")
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


def env_chat_id(value: Optional[str]) -> Optional[Any]:
    if not value:
        return None
    value = value.strip()
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    if USERNAME_RE.match(value.lstrip("@")):
        return value if value.startswith("@") else "@" + value
    return None


@dataclasses.dataclass
class Config:
    env_path: Optional[Path] = None
    base_dir: Path = dataclasses.field(default_factory=lambda: Path(__file__).resolve().parent)

    token: str = ""
    api_base: str = "https://api.telegram.org"

    db_path: Path = dataclasses.field(default_factory=lambda: Path("offers.db"))
    log_file: Path = dataclasses.field(default_factory=lambda: Path("bot.log"))
    log_level: str = "INFO"

    ttl_hours: float = 24.0
    min_price: int = 1
    max_price: int = 1_000_000
    max_open_offers: int = 5
    create_cooldown: float = 20.0

    board_chat_id: Optional[Any] = None
    delete_commands: bool = True
    edit_cards: bool = True
    notify_on_expire: bool = True

    poll_timeout: int = 30
    dry_run: bool = False
    insecure_tls: bool = False
    ca_bundle: str = ""
    support_contact: str = ""

    def resolve_paths(self) -> None:
        if not self.db_path.is_absolute():
            self.db_path = self.base_dir / self.db_path
        if not self.log_file.is_absolute():
            self.log_file = self.base_dir / self.log_file

    @property
    def ttl_seconds(self) -> float:
        return max(1.0, self.ttl_hours) * 3600.0

    def summary(self) -> str:
        return "\n".join(
            [
                f"версия:        {VERSION}",
                f"токен:         {mask_token(self.token)}",
                f"база:          {self.db_path}",
                f"лог:           {self.log_file}",
                f"TTL оффера:    {self.ttl_hours:g} ч",
                f"цена:          {self.min_price} … {self.max_price} ⭐",
                f"лимиты:        {self.max_open_offers} открытых, кулдаун {self.create_cooldown:g} c",
                f"доска:         {self.board_chat_id or 'выключена'}",
                f"удалять cmds:  {self.delete_commands}",
                f"dry-run:       {self.dry_run}",
            ]
        )


def load_config(env_path: Optional[str] = None, overrides: Optional[Dict[str, str]] = None) -> Config:
    base_dir = Path(__file__).resolve().parent
    candidates: List[Path] = []
    if env_path:
        candidates.append(Path(env_path).expanduser())
    if os.environ.get("BOT_ENV_FILE"):
        candidates.append(Path(os.environ["BOT_ENV_FILE"]).expanduser())
    candidates.extend([Path.cwd() / ".env", base_dir / ".env", base_dir / "config.env"])

    file_values: Dict[str, str] = {}
    found: Optional[Path] = None
    for candidate in candidates:
        if candidate.is_file():
            file_values = read_env_file(candidate)
            found = candidate.resolve()
            base_dir = candidate.resolve().parent
            break

    def get(name: str) -> Optional[str]:
        if overrides and overrides.get(name) is not None:
            return overrides[name]
        if os.environ.get(name):
            return os.environ[name]
        return file_values.get(name)

    cfg = Config(env_path=found, base_dir=base_dir)
    cfg.token = (get("BOT_TOKEN") or "").strip()
    cfg.api_base = (get("API_BASE") or cfg.api_base).rstrip("/")
    cfg.db_path = Path(get("DB_PATH") or "offers.db")
    cfg.log_file = Path(get("LOG_FILE") or "bot.log")
    cfg.log_level = (get("LOG_LEVEL") or "INFO").strip().upper()
    cfg.ttl_hours = max(0.1, env_float(get("OFFER_TTL_HOURS"), 24.0))
    cfg.min_price = max(1, env_int(get("MIN_PRICE"), 1))
    cfg.max_price = max(cfg.min_price, env_int(get("MAX_PRICE"), 1_000_000))
    cfg.max_open_offers = max(1, env_int(get("MAX_OPEN_OFFERS"), 5))
    cfg.create_cooldown = max(0.0, env_float(get("CREATE_COOLDOWN_SECONDS"), 20.0))
    cfg.board_chat_id = env_chat_id(get("BOARD_CHAT_ID"))
    cfg.delete_commands = env_bool(get("DELETE_COMMANDS"), True)
    cfg.edit_cards = env_bool(get("EDIT_CARDS"), True)
    cfg.notify_on_expire = env_bool(get("NOTIFY_ON_EXPIRE"), True)
    cfg.poll_timeout = max(0, min(50, env_int(get("POLL_TIMEOUT"), 30)))
    cfg.dry_run = env_bool(get("DRY_RUN"), False)
    cfg.insecure_tls = env_bool(get("INSECURE_TLS"), False)
    cfg.ca_bundle = get("CA_BUNDLE") or ""
    cfg.support_contact = (get("SUPPORT_CONTACT") or "").strip()
    cfg.resolve_paths()
    return cfg


def set_token(env_path: Path, token: str, api_base: str = "https://api.telegram.org",
              insecure_tls: bool = False, ca_bundle: str = "", verify: bool = True) -> int:
    """Атомарно вписывает токен в .env (chmod 600) и проверяет его через getMe."""
    token = (token or "").strip()
    if not TOKEN_RE.match(token):
        print(
            "Это не похоже на токен. Нужен формат 123456789:AAH... (30+ символов после двоеточия).\n"
            "Токен выдаёт @BotFather → /newbot или /token.",
            file=sys.stderr,
        )
        return 2
    lines: List[str] = []
    if env_path.is_file():
        lines = env_path.read_text(encoding="utf-8-sig").splitlines()
    replaced = False
    for index, line in enumerate(lines):
        if re.match(r"^\s*(export\s+)?BOT_TOKEN\s*=", line):
            lines[index] = f"BOT_TOKEN={token}"
            replaced = True
    if not replaced:
        if lines and lines[-1].strip():
            lines.append("")
        lines.append(f"BOT_TOKEN={token}")
    env_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = env_path.with_name(env_path.name + ".tmp")
    tmp.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    os.chmod(tmp, 0o600)
    os.replace(tmp, env_path)
    try:
        os.chmod(env_path, 0o600)
    except OSError:
        pass
    print(f"Токен записан в {env_path} (права 600).")

    if not verify:
        return 0
    api = TelegramAPI(
        token=token, api_base=api_base, insecure_tls=insecure_tls,
        ca_bundle=ca_bundle, max_network_retries=1,
    )
    try:
        me = api.get_me()
    except TelegramError as exc:
        print(f"[!] getMe не прошёл: {exc}")
        if error_hint(exc.description):
            print(f"    {error_hint(exc.description)}")
        print("Токен сохранён. Проверьте сеть/прокси и запустите: python3 bot.py --check")
        return 1
    print(f"getMe ok: @{me.get('username')} (id={me.get('id')})")
    return 0


def setup_logging(cfg: Config, verbose: bool = False) -> logging.Logger:
    logger = logging.getLogger("offerbot")
    logger.setLevel(logging.DEBUG if verbose else getattr(logging, cfg.log_level, logging.INFO))
    logger.propagate = False
    for handler in list(logger.handlers):
        logger.removeHandler(handler)
        try:
            handler.close()
        except Exception:  # noqa: BLE001
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
        logger.warning("Не смог открыть лог %s: %s", cfg.log_file, exc)
    return logger


# ---------------------------------------------------------------------------
# Bot API
# ---------------------------------------------------------------------------


class TelegramError(Exception):
    def __init__(self, description: str, code: int = 0, retry_after: Optional[float] = None,
                 method: str = "") -> None:
        super().__init__(description)
        self.description = description
        self.code = code
        self.retry_after = retry_after
        self.method = method

    def __str__(self) -> str:  # pragma: no cover
        prefix = self.method or "api"
        return f"{prefix}: код {self.code}: {self.description}"


class TelegramAPI:
    """Минимальный клиент Bot API: JSON, таймауты, FloodWait, понятные ошибки."""

    READ_ONLY = ("getMe", "getUpdates", "getWebhookInfo", "getBusinessConnection")

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
        self.logger = logger or logging.getLogger("offerbot")
        self._sleep = sleep
        self.max_network_retries = max_network_retries
        self.max_flood_retries = max_flood_retries
        self.stop_event = threading.Event()

        if insecure_tls:
            context = ssl._create_unverified_context()  # noqa: SLF001
        else:
            context = ssl.create_default_context(cafile=ca_bundle or None)
        self._opener = urllib.request.build_opener(
            urllib.request.ProxyHandler(),
            urllib.request.HTTPSHandler(context=context),
        )

    def call(self, method: str, params: Optional[Dict[str, Any]] = None) -> Any:
        if self.dry_run and method not in self.READ_ONLY:
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
                    self.logger.warning("FloodWait %s: ждём %.1f c", method, wait)
                    self._sleep(wait)
                    continue
                if exc.code in (-1, 500, 502, 503, 504) and network_attempt < self.max_network_retries:
                    network_attempt += 1
                    wait = min(30.0, 1.5 ** network_attempt) + random.uniform(0, 0.5)
                    self.logger.warning("Сеть %s (%s): повтор через %.1f c", method, exc.description, wait)
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
            url, data=data, headers={"Content-Type": "application/json; charset=utf-8"}, method="POST"
        )
        timeout = self.poll_timeout + 20 if method == "getUpdates" else 30
        try:
            with self._opener.open(request, timeout=timeout) as response:
                body = response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            body = ""
            try:
                body = exc.read().decode("utf-8", "replace")
            except Exception:  # pragma: no cover
                pass
            description, retry_after = self._parse_error(body, exc.code)
            raise TelegramError(description, exc.code, retry_after, method) from None
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, ssl.SSLError) as exc:
            raise TelegramError(f"сетевая ошибка: {getattr(exc, 'reason', exc)}", -1, None, method) from None
        except OSError as exc:
            raise TelegramError(f"сетевая ошибка: {exc}", -1, None, method) from None

        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            raise TelegramError(
                f"ответ не JSON (проверьте API_BASE/прокси): {(body or '<пусто>')[:200]}", 0, None, method
            ) from None
        if not isinstance(payload, dict):
            raise TelegramError("неожиданный формат ответа", 0, None, method)
        if not payload.get("ok"):
            description, retry_after = self._parse_error(body, 400)
            raise TelegramError(description, 400, retry_after, method)
        return payload.get("result")

    @staticmethod
    def _parse_error(body: str, code: int) -> Tuple[str, Optional[float]]:
        description = body or "пустой ответ"
        retry_after: Optional[float] = None
        try:
            payload = json.loads(body)
            if isinstance(payload, dict):
                description = str(payload.get("description") or description)
                params = payload.get("parameters") or {}
                if params.get("retry_after") is not None:
                    retry_after = float(params["retry_after"])
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
        chat_id: Any,
        text: str,
        conn_id: Optional[str] = None,
        keyboard: Optional[Dict[str, Any]] = None,
        parse_mode: Optional[str] = "HTML",
        disable_preview: bool = True,
    ) -> Dict[str, Any]:
        params: Dict[str, Any] = {"chat_id": chat_id, "text": text}
        if parse_mode:
            params["parse_mode"] = parse_mode
        if disable_preview:
            params["link_preview_options"] = {"is_disabled": True}
        if keyboard:
            params["reply_markup"] = keyboard
        if conn_id:
            params["business_connection_id"] = conn_id
        return self.call("sendMessage", params)

    def edit_message(
        self,
        chat_id: Any,
        message_id: int,
        text: str,
        keyboard: Optional[Dict[str, Any]] = None,
        conn_id: Optional[str] = None,
        parse_mode: Optional[str] = "HTML",
        disable_preview: bool = True,
    ) -> Any:
        params: Dict[str, Any] = {"chat_id": chat_id, "message_id": message_id, "text": text}
        if parse_mode:
            params["parse_mode"] = parse_mode
        if disable_preview:
            params["link_preview_options"] = {"is_disabled": True}
        if keyboard is not None:
            params["reply_markup"] = keyboard
        if conn_id:
            params["business_connection_id"] = conn_id
        return self.call("editMessageText", params)

    def delete_message(self, chat_id: Any, message_id: int, conn_id: Optional[str] = None) -> Any:
        if conn_id:
            return self.call(
                "deleteBusinessMessages",
                {"business_connection_id": conn_id, "message_ids": [message_id]},
            )
        return self.call("deleteMessage", {"chat_id": chat_id, "message_id": message_id})

    def answer_callback(self, callback_id: str, text: Optional[str] = None, alert: bool = False) -> Any:
        params: Dict[str, Any] = {"callback_query_id": callback_id}
        if text:
            params["text"] = text[:200]
            params["show_alert"] = alert
        return self.call("answerCallbackQuery", params)


class FakeTelegramAPI(TelegramAPI):
    """Для --selftest и тестов: тот же интерфейс, без сети."""

    READ_ONLY = TelegramAPI.READ_ONLY

    def __init__(self, fail_on: Optional[Dict[str, TelegramError]] = None) -> None:
        self.calls: List[Tuple[str, Dict[str, Any]]] = []
        self.fail_on = fail_on or {}
        self._next_id = 500
        self.dry_run = False
        self.logger = logging.getLogger("offerbot")

    def call(self, method: str, params: Optional[Dict[str, Any]] = None) -> Any:
        params = dict(params or {})
        if self.dry_run and method not in self.READ_ONLY:
            self.calls.append((method, params))
            return True
        self.calls.append((method, params))
        if method in self.fail_on:
            raise self.fail_on[method]
        if method in ("sendMessage", "editMessageText"):
            if method == "sendMessage":
                self._next_id += 1
                return {"message_id": self._next_id, "date": int(time.time())}
            return True
        if method == "getMe":
            return {"id": 1, "is_bot": True, "first_name": "OfferBot", "username": "gift_offer_bot"}
        return True

    def methods(self) -> List[str]:
        return [m for m, _ in self.calls]

    def only(self, method: str) -> List[Dict[str, Any]]:
        return [p for m, p in self.calls if m == method]

    def clear(self) -> None:
        self.calls.clear()


# ---------------------------------------------------------------------------
# База
# ---------------------------------------------------------------------------


@dataclasses.dataclass
class Offer:
    id: int
    kind: str
    creator_id: int
    creator_name: str
    recipient_id: Optional[int]
    gift_url: str
    gift_title: str
    price: int
    status: str
    conn_id: Optional[str]
    chat_id: Optional[int]
    card_chat_id: Optional[int]
    card_msg_id: Optional[int]
    card_conn_id: Optional[str]
    board_chat_id: Optional[Any]
    board_msg_id: Optional[int]
    created_at: float
    updated_at: float

    @property
    def buyer_id(self) -> Optional[int]:
        return self.creator_id if self.kind == "buy" else self.recipient_id

    @property
    def seller_id(self) -> Optional[int]:
        return self.recipient_id if self.kind == "buy" else self.creator_id

    @property
    def kind_title(self) -> str:
        return "Хочу купить" if self.kind == "buy" else "Продаю"

    @property
    def kind_emoji(self) -> str:
        return "🛒" if self.kind == "buy" else "🏷"


COLUMNS = (
    "id, kind, creator_id, creator_name, recipient_id, gift_url, gift_title, price, "
    "status, conn_id, chat_id, card_chat_id, card_msg_id, card_conn_id, "
    "board_chat_id, board_msg_id, created_at, updated_at"
)


class DB:
    def __init__(self, path: str) -> None:
        self.path = path
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA synchronous=NORMAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self._init()

    def _init(self) -> None:
        self.conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS offers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                creator_id INTEGER NOT NULL,
                creator_name TEXT NOT NULL DEFAULT '',
                recipient_id INTEGER,
                gift_url TEXT NOT NULL,
                gift_title TEXT NOT NULL DEFAULT 'Подарок',
                price INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'open',
                conn_id TEXT,
                chat_id INTEGER,
                card_chat_id INTEGER,
                card_msg_id INTEGER,
                card_conn_id TEXT,
                board_chat_id TEXT,
                board_msg_id INTEGER,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_offers_status ON offers(status);
            CREATE INDEX IF NOT EXISTS idx_offers_creator ON offers(creator_id);
            CREATE INDEX IF NOT EXISTS idx_offers_created ON offers(created_at);
            CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
            CREATE TABLE IF NOT EXISTS users (
                user_id INTEGER PRIMARY KEY,
                username TEXT NOT NULL DEFAULT '',
                first_seen REAL NOT NULL,
                last_seen REAL NOT NULL
            );
            """
        )
        columns = {row["name"] for row in self.conn.execute("PRAGMA table_info(offers)")}
        if "card_conn_id" not in columns:
            self.conn.execute("ALTER TABLE offers ADD COLUMN card_conn_id TEXT")
        self.conn.commit()

    # -- meta --------------------------------------------------------------

    def meta_get(self, key: str, default: Optional[str] = None) -> Optional[str]:
        row = self.conn.execute("SELECT v FROM meta WHERE k=?", (key,)).fetchone()
        return row["v"] if row else default

    def meta_set(self, key: str, value: str) -> None:
        with self._lock:
            self.conn.execute("INSERT OR REPLACE INTO meta(k, v) VALUES (?, ?)", (key, value))
            self.conn.commit()

    # -- пользователи ------------------------------------------------------

    def touch_user(self, user_id: int, username: str = "") -> None:
        now = time.time()
        with self._lock:
            self.conn.execute(
                "INSERT INTO users(user_id, username, first_seen, last_seen) VALUES (?,?,?,?) "
                "ON CONFLICT(user_id) DO UPDATE SET username=excluded.username, last_seen=excluded.last_seen",
                (int(user_id), username or "", now, now),
            )
            self.conn.commit()

    def known_user(self, user_id: int) -> bool:
        row = self.conn.execute("SELECT 1 FROM users WHERE user_id=?", (int(user_id),)).fetchone()
        return row is not None

    def users_count(self) -> int:
        row = self.conn.execute("SELECT COUNT(*) AS c FROM users").fetchone()
        return int(row["c"] if row else 0)

    # -- offers ------------------------------------------------------------

    def create(self, offer: "Offer") -> "Offer":
        with self._lock:
            cur = self.conn.execute(
                """
                INSERT INTO offers (kind, creator_id, creator_name, recipient_id, gift_url,
                                    gift_title, price, status, conn_id, chat_id, card_chat_id,
                                    card_msg_id, card_conn_id, board_chat_id, board_msg_id,
                                    created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    offer.kind, offer.creator_id, offer.creator_name, offer.recipient_id,
                    offer.gift_url, offer.gift_title, offer.price, offer.status, offer.conn_id,
                    offer.chat_id, offer.card_chat_id, offer.card_msg_id, offer.card_conn_id,
                    offer.board_chat_id, offer.board_msg_id, offer.created_at, offer.updated_at,
                ),
            )
            self.conn.commit()
            offer.id = int(cur.lastrowid)
        return offer

    def get(self, offer_id: int) -> Optional[Offer]:
        row = self.conn.execute(f"SELECT {COLUMNS} FROM offers WHERE id=?", (int(offer_id),)).fetchone()
        return Offer(**dict(row)) if row else None

    def update(self, offer_id: int, **fields: Any) -> None:
        if not fields:
            return
        fields["updated_at"] = time.time()
        keys = ", ".join(f"{key}=?" for key in fields)
        values = list(fields.values()) + [int(offer_id)]
        with self._lock:
            self.conn.execute(f"UPDATE offers SET {keys} WHERE id=?", values)
            self.conn.commit()

    def list_by_creator(self, creator_id: int, limit: int = 10) -> List[Offer]:
        rows = self.conn.execute(
            f"SELECT {COLUMNS} FROM offers WHERE creator_id=? ORDER BY id DESC LIMIT ?",
            (creator_id, limit),
        ).fetchall()
        return [Offer(**dict(row)) for row in rows]

    def count_open_by_creator(self, creator_id: int) -> int:
        row = self.conn.execute(
            "SELECT COUNT(*) AS c FROM offers WHERE creator_id=? AND status IN ('open','accepted','sent')",
            (creator_id,),
        ).fetchone()
        return int(row["c"] if row else 0)

    def expired(self, cutoff: float) -> List[Offer]:
        rows = self.conn.execute(
            f"SELECT {COLUMNS} FROM offers WHERE status IN ('open','accepted','sent') AND created_at < ?",
            (cutoff,),
        ).fetchall()
        return [Offer(**dict(row)) for row in rows]

    def stats(self) -> Dict[str, int]:
        rows = self.conn.execute("SELECT status, COUNT(*) AS c FROM offers GROUP BY status").fetchall()
        return {row["status"]: int(row["c"]) for row in rows}

    def close(self) -> None:
        try:
            self.conn.close()
        except Exception:  # noqa: BLE001
            pass


# ---------------------------------------------------------------------------
# Тексты
# ---------------------------------------------------------------------------


def fmt_expires(ts: float) -> str:
    return time.strftime("%d.%m %H:%M", time.localtime(ts))


def how_text(bot_username: Optional[str]) -> str:
    return (
        "❓ <b>Как проходит сделка 1:1</b>\n\n"
        "1. Один человек публикует намерение: <i>хочу купить</i> подарок за N ⭐ "
        "или <i>продаю</i> свой подарок за N ⭐.\n"
        "2. Второй открывает карточку и нажимает «Принять намерение».\n"
        "3. Бот присылает обоим инструкции.\n"
        "4. <b>Покупатель</b> отправляет в Telegram официальный <b>Offer to Buy</b> "
        "(подарок → ⋮ → «Offer to Buy»).\n"
        "5. <b>Продавец</b> принимает оффер в клиенте.\n"
        "6. Telegram атомарно передаёт подарок и зачисляет звёзды.\n\n"
        "<b>Бот не хранит звёзды и подарки, не пересылает их и берёт 0%.</b>\n"
        "Звёзды замораживает сам Telegram: при отказе или истечении оффера они вернутся "
        "покупателю.\n\n"
        "🚩 <b>Красные флаги:</b> просят отправить подарок первым, перевести звёзды "
        "«на аккаунт», заплатить вне Telegram, обещают «без комиссии» через посредника — "
        "это мошенничество. Жмите «Отменить» и жалуйтесь.\n\n"
        "Бот никогда не просит подарок или пароль и не пишет первым."
    )


def card_text(offer: Offer, ttl_hours: float) -> str:
    status_line = ""
    if offer.status == "accepted":
        status_line = "\n\n✅ <b>Намерение принято</b> — ждём официальный оффер в Telegram."
    elif offer.status == "sent":
        status_line = "\n\n📨 <b>Покупатель отправил официальный оффер</b> — продавец принимает его в клиенте."
    elif offer.status == "completed":
        status_line = "\n\n🏁 <b>Сделка закрыта.</b>"
    elif offer.status == "declined":
        status_line = "\n\n❌ <b>Намерение отклонено.</b>"
    elif offer.status == "cancelled":
        status_line = "\n\n✖️ <b>Отменено.</b>"
    elif offer.status == "expired":
        status_line = "\n\n⌛ <b>Истекло.</b>"

    expires = fmt_expires(offer.created_at + ttl_hours * 3600)
    return (
        f"{offer.kind_emoji} <b>{offer.kind_title}</b> · 1:1\n\n"
        f"🎁 <a href=\"{esc(offer.gift_url)}\">{esc(offer.gift_title)}</a>\n"
        f"💰 <b>{offer.price} {STARS}</b>\n"
        f"👤 {esc(offer.creator_name)}\n"
        f"🆔 №{offer.id}\n\n"
        "<i>Сделка проходит через официальный Offer to Buy в Telegram: "
        "звёзды замораживает Telegram, бот их не хранит.</i>\n"
        f"⏳ до {esc(expires)}"
        f"{status_line}"
    )


def keyboard_for(offer: Offer) -> Optional[Dict[str, Any]]:
    base = {"open_gift": {"text": "🔗 Открыть подарок", "url": offer.gift_url}}
    how = {"text": "❓ Как это работает", "callback_data": f"how:{offer.id}"}
    if offer.status == "open":
        rows = [
            [
                {"text": "🤝 Принять намерение", "callback_data": f"acc:{offer.id}"},
                {"text": "❌ Отклонить", "callback_data": f"dec:{offer.id}"},
            ],
            [base["open_gift"], how],
        ]
        return {"inline_keyboard": rows}
    if offer.status in ("accepted", "sent"):
        rows = [
            [
                {"text": "📨 Я отправил оффер", "callback_data": f"sent:{offer.id}"},
                {"text": "✅ Я принял оффер", "callback_data": f"done:{offer.id}"},
            ],
            [
                base["open_gift"],
                {"text": "✖️ Отменить", "callback_data": f"cxl:{offer.id}"},
            ],
        ]
        return {"inline_keyboard": rows}
    return {"inline_keyboard": [[base["open_gift"], how]]}


def buyer_instructions(offer: Offer) -> str:
    return (
        "🤝 <b>Намерение принято</b>\n\n"
        "Вы <b>покупатель</b>. Отправьте официальный Offer to Buy:\n"
        f"1. Откройте подарок: {esc(offer.gift_url)}\n"
        "2. Нажмите ⋮ → <b>«Offer to Buy»</b> (Предложить цену)\n"
        f"3. Сумма: <b>{offer.price} {STARS}</b>\n"
        "4. Отправьте оффер\n\n"
        "Звёзды заморозит Telegram. Если оффер отклонят или он истечёт — они вернутся.\n\n"
        "❗ Не переводите подарок вручную, не платите вне Telegram и не отправляйте "
        "ничего «на аккаунт» — так работают только мошенники."
    )


def seller_instructions(offer: Offer) -> str:
    return (
        "🤝 <b>Намерение принято</b>\n\n"
        "Вы <b>продавец</b>. Ничего не отправляйте вручную.\n"
        f"1. Дождитесь официального оффера на <a href=\"{esc(offer.gift_url)}\">"
        f"{esc(offer.gift_title)}</a> — он придёт сообщением в Telegram.\n"
        "2. Проверьте сумму и откройте подарок в клиенте.\n"
        "3. Примите оффер кнопкой «Принять».\n"
        "4. Telegram сам передаст подарок и зачислит звёзды.\n\n"
        "❗ Если просят отправить подарок первым, перейти в другой чат или «подтвердить» "
        "перевод — это мошенничество. Нажмите «Отменить» в карточке."
    )


# ---------------------------------------------------------------------------
# Бот
# ---------------------------------------------------------------------------


class OfferBot:
    def __init__(
        self,
        cfg: Config,
        api: Any,
        db: DB,
        logger: Optional[logging.Logger] = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.cfg = cfg
        self.api = api
        self.db = db
        self.logger = logger or logging.getLogger("offerbot")
        self.clock = clock
        self.username: Optional[str] = None
        self.offset = int(self.db.meta_get("offset", "0") or 0)
        self._stop = threading.Event()
        self._last_create: Dict[int, float] = {}

    # -- инфраструктура ----------------------------------------------------

    def stop(self) -> None:
        self._stop.set()

    def _kb(self, offer: Offer) -> Optional[Dict[str, Any]]:
        return keyboard_for(offer) if self.cfg.edit_cards else None

    def _send(
        self,
        chat_id: Any,
        text: str,
        offer: Optional[Offer] = None,
        keyboard: Optional[Dict[str, Any]] = None,
        conn_id: Optional[str] = None,
    ) -> Optional[int]:
        try:
            response = self.api.send_message(
                chat_id=chat_id,
                text=text,
                conn_id=conn_id,
                keyboard=keyboard,
                parse_mode="HTML",
            )
        except TelegramError as exc:
            low = (exc.description or "").lower()
            if any(
                needle in low
                for needle in (
                    "bot was blocked",
                    "chat not found",
                    "user is deactivated",
                    "peer_flood",
                    "bot can't initiate conversation",
                    "user not found",
                )
            ):
                hint = error_hint(exc.description)
                self.logger.warning(
                    "Не доставлено в %s: %s%s",
                    chat_id,
                    exc.description,
                    (" — " + hint) if hint else "",
                )
                return None
            self.logger.error("sendMessage в %s: %s", chat_id, exc)
            return None
        if isinstance(response, dict) and response.get("message_id"):
            return int(response["message_id"])
        return None

    def _edit_card(self, offer: Offer) -> None:
        if not self.cfg.edit_cards or not offer.card_chat_id or not offer.card_msg_id:
            return
        try:
            self.api.edit_message(
                chat_id=offer.card_chat_id,
                message_id=offer.card_msg_id,
                text=card_text(offer, self.cfg.ttl_hours),
                keyboard=self._kb(offer),
                conn_id=offer.card_conn_id,
                parse_mode="HTML",
            )
        except TelegramError as exc:
            low = (exc.description or "").lower()
            if "message is not modified" in low:
                return
            if "message can't be edited" in low:
                self.logger.warning("Карточку %s нельзя править — отправляю новую", offer.id)
                new_id = self._send(
                    offer.card_chat_id,
                    card_text(offer, self.cfg.ttl_hours),
                    keyboard=self._kb(offer),
                    conn_id=offer.card_conn_id,
                )
                if new_id:
                    self.db.update(offer.id, card_msg_id=new_id)
                return
            self.logger.warning("editMessageText %s: %s", offer.id, exc)

    def _edit_board(self, offer: Offer) -> None:
        if not offer.board_chat_id or not offer.board_msg_id:
            return
        chat: Any = offer.board_chat_id
        if isinstance(chat, str) and re.fullmatch(r"-?\d+", chat):
            chat = int(chat)
        try:
            self.api.edit_message(
                chat_id=chat,
                message_id=offer.board_msg_id,
                text=card_text(offer, self.cfg.ttl_hours),
                keyboard=self._board_kb(offer),
                parse_mode="HTML",
            )
        except TelegramError:
            pass

    def _board_kb(self, offer: Offer) -> Dict[str, Any]:
        return {
            "inline_keyboard": [
                [
                    {"text": "🔎 Открыть оффер 1:1", "url": self.deep_link(offer)},
                    {"text": "🔗 Подарок", "url": offer.gift_url},
                ]
            ]
        }

    def _delete_command(self, message: Dict[str, Any], conn_id: Optional[str]) -> None:
        if not self.cfg.delete_commands:
            return
        chat_id = (message.get("chat") or {}).get("id")
        message_id = message.get("message_id")
        if chat_id is None or message_id is None:
            return
        try:
            self.api.delete_message(chat_id, int(message_id), conn_id)
        except TelegramError as exc:
            low = (exc.description or "").lower()
            if "message to delete not found" in low:
                return
            self.logger.warning("Не удалил команду %s: %s", message_id, exc)

    def deep_link(self, offer: Offer) -> str:
        return f"https://t.me/{self.username}?start=offer_{offer.id}"

    def _route(self, offer: Offer, user_id: Optional[int]) -> Tuple[Optional[Any], Optional[str]]:
        """Куда писать человеку: сначала его ЛС с ботом, иначе чат/бизнес-чат из оффера."""
        if not user_id:
            return None, None
        if self.db.known_user(user_id):
            return int(user_id), None
        if user_id == offer.creator_id and offer.chat_id:
            return offer.chat_id, offer.conn_id
        if user_id == offer.recipient_id and offer.card_chat_id:
            return offer.card_chat_id, offer.card_conn_id
        return None, None

    def _notify(self, offer: Offer, user_id: Optional[int], text: str) -> Optional[int]:
        """Уведомление стороне сделки по правильному маршруту (без слепых ЛС → PEER_FLOOD)."""
        chat_id, conn_id = self._route(offer, user_id)
        if chat_id is None:
            self.logger.warning(
                "Пропускаю уведомление для %s: человек не начинал чат с ботом "
                "(слепая ЛС дала бы PEER_FLOOD). Пусть нажмёт /start.", user_id,
            )
            return None
        return self._send(chat_id, text, conn_id=conn_id)

    # -- обработка апдейтов ------------------------------------------------

    def handle_update(self, update: Dict[str, Any]) -> None:
        try:
            self._handle_update(update)
        except TelegramError as exc:
            self.logger.error("API при апдейте %s: %s", update.get("update_id"), exc)
        except Exception:  # noqa: BLE001
            self.logger.exception("Необработанная ошибка в апдейте %s", update.get("update_id"))

    def _handle_update(self, update: Dict[str, Any]) -> None:
        if "business_connection" in update:
            connection = update["business_connection"]
            self.logger.info(
                "Бизнес-подключение %s: enabled=%s, can_reply=%s, delete_all=%s",
                connection.get("id"),
                connection.get("is_enabled"),
                (connection.get("rights") or {}).get("can_reply"),
                (connection.get("rights") or {}).get("can_delete_all_messages"),
            )
            return
        if "callback_query" in update:
            self._on_callback(update["callback_query"])
            return
        message = update.get("message") or update.get("business_message")
        if isinstance(message, dict):
            self._on_message(message)

    # -- сообщения ---------------------------------------------------------

    def _on_message(self, message: Dict[str, Any]) -> None:
        text = (message.get("text") or "").strip()
        if not text.startswith("/"):
            return
        chat = message.get("chat") or {}
        chat_id = chat.get("id")
        message_id = message.get("message_id")
        if chat_id is None or message_id is None:
            return
        sender = message.get("from") or {}
        if sender.get("is_bot"):
            return
        conn_id = message.get("business_connection_id")
        user_id = sender.get("id") or chat_id
        # «Начинал чат с ботом» = обычное ЛС без бизнес-подключения.
        # Бизнес-чат выглядит как private, но писать в него можно только с conn_id,
        # иначе Telegram отвечает PEER_FLOOD.
        if conn_id is None and chat.get("type") == "private" and sender.get("id"):
            self.db.touch_user(int(sender["id"]), sender.get("username") or "")

        first = text.split(maxsplit=1)[0]
        command = first.split("@", 1)[0].lower()
        args = text.split(maxsplit=1)[1].strip() if len(text.split(maxsplit=1)) > 1 else ""

        # Удаляем команду — это первое действие, как и просили.
        self._delete_command(message, conn_id)

        if command == "/offer":
            self._cmd_create("buy", message, conn_id, args, user_id)
        elif command == "/sell":
            self._cmd_create("sell", message, conn_id, args, user_id)
        elif command == "/start":
            self._cmd_start(message, conn_id, args, user_id)
        elif command == "/help":
            self._cmd_help(chat_id, conn_id)
        elif command == "/how":
            self._send(chat_id, how_text(self.username), conn_id=conn_id)
        elif command == "/myoffers":
            self._cmd_myoffers(chat_id, conn_id, user_id)
        elif command == "/cancel":
            self._cmd_cancel(chat_id, conn_id, user_id, args)
        elif command == "/id":
            self._send(chat_id, f"chat_id: <code>{chat_id}</code>\nuser_id: <code>{user_id}</code>", conn_id=conn_id)

    def _cmd_create(self, kind: str, message: Dict[str, Any], conn_id: Optional[str],
                    args: str, user_id: int) -> None:
        chat = message.get("chat") or {}
        chat_id = int(chat["id"])
        name = (message.get("from") or {}).get("first_name") or "Пользователь"
        if chat.get("type") not in (None, "private"):
            self._send(
                chat_id,
                "Офферы создаются только в личке с ботом: сделка 1:1, подробности "
                "и инструкции не должны попадать в общий чат.\n\n"
                f"Напишите боту в ЛС: @{esc(self.username or 'этот_бот')}",
                conn_id=conn_id,
            )
            return
        url, price = parse_offer_text(args)

        if not url:
            self._send(
                chat_id,
                "Не вижу ссылку на подарок.\n\n"
                "Формат:\n"
                "<code>/offer https://t.me/nft/PlushPepe-123 250</code> — хочу купить\n"
                "<code>/sell https://t.me/nft/PlushPepe-123 250</code> — продаю\n\n"
                "Ссылку берите у подарка: ⋮ → «Копировать ссылку».",
                conn_id=conn_id,
            )
            return
        if price is None:
            self._send(chat_id, "Не вижу цену в звёздах. Пример: <code>/offer " + esc(url) + " 250</code>", conn_id=conn_id)
            return
        if price < self.cfg.min_price or price > self.cfg.max_price:
            self._send(
                chat_id,
                f"Цена должна быть от {self.cfg.min_price} до {self.cfg.max_price} {STARS}.",
                conn_id=conn_id,
            )
            return
        if re.search(r"\b(ton|тон|грам|gram)\b", GIFT_URL_ANY_RE.sub(" ", args), re.IGNORECASE):
            self._send(
                chat_id,
                "Пока только звёзды (⭐). Сделки за TON — отдельный сценарий, в боте его нет.",
                conn_id=conn_id,
            )
            return

        now = self.clock()
        last = self._last_create.get(user_id, 0.0)
        if self.cfg.create_cooldown and now - last < self.cfg.create_cooldown:
            self._send(chat_id, "Слишком часто. Подождите немного и создайте оффер снова.", conn_id=conn_id)
            return
        if self.db.count_open_by_creator(user_id) >= self.cfg.max_open_offers:
            self._send(
                chat_id,
                f"У вас уже {self.cfg.max_open_offers} активных офферов. "
                "Закройте лишние: /myoffers → /cancel ID.",
                conn_id=conn_id,
            )
            return

        offer = Offer(
            id=0, kind=kind, creator_id=int(user_id), creator_name=str(name),
            recipient_id=None, gift_url=url, gift_title=gift_title_from_url(url),
            price=price, status="open", conn_id=conn_id, chat_id=chat_id,
            card_chat_id=None, card_msg_id=None, card_conn_id=None,
            board_chat_id=None, board_msg_id=None,
            created_at=now, updated_at=now,
        )
        offer = self.db.create(offer)
        self._last_create[user_id] = now

        header = "🛒 <b>Оффер создан</b>" if kind == "buy" else "🏷 <b>Лот создан</b>"
        text = (
            f"{header} · 1:1\n\n"
            f"🎁 <a href=\"{esc(url)}\">{esc(offer.gift_title)}</a>\n"
            f"💰 <b>{price} {STARS}</b>\n"
            f"🆔 №{offer.id}\n\n"
            "Отправьте эту ссылку второй стороне:\n"
            f"<code>{self.deep_link(offer)}</code>\n\n"
            "Она откроет карточку и сможет принять намерение. "
            "Дальше бот объяснит обоим, как провести официальную сделку в Telegram.\n\n"
            "<i>Бот не хранит звёзды и подарки.</i>"
        )
        self._send(chat_id, text, conn_id=conn_id)

        if self.cfg.board_chat_id:
            board_id = self._send(
                self.cfg.board_chat_id,
                card_text(offer, self.cfg.ttl_hours),
                keyboard=self._board_kb(offer),
            )
            if board_id:
                self.db.update(
                    offer.id,
                    board_chat_id=str(self.cfg.board_chat_id),
                    board_msg_id=board_id,
                )

    def _cmd_start(self, message: Dict[str, Any], conn_id: Optional[str], args: str, user_id: int) -> None:
        chat_id = int((message.get("chat") or {})["id"])
        payload = args.strip()
        if payload.startswith("offer_"):
            try:
                offer_id = int(payload.split("_", 1)[1])
            except (ValueError, IndexError):
                self._send(chat_id, "Битая ссылка приглашения.", conn_id=conn_id)
                return
            self._show_offer(offer_id, chat_id, conn_id, user_id)
            return
        self._send(
            chat_id,
            "👋 Это бот для сделок 1:1 «подарок ↔ звёзды».\n\n"
            "• <code>/offer &lt;ссылка на подарок&gt; &lt;цена&gt;</code> — хочу купить\n"
            "• <code>/sell &lt;ссылка на подарок&gt; &lt;цена&gt;</code> — продаю\n"
            "• <code>/myoffers</code> — мои офферы\n"
            "• <code>/how</code> — как проходит сделка\n\n"
            "Звёзды и подарки через бота не проходят: официальный Offer to Buy "
            "замораживает звёзды силами Telegram.",
            conn_id=conn_id,
        )

    def _show_offer(self, offer_id: int, chat_id: int, conn_id: Optional[str], user_id: int) -> None:
        offer = self.db.get(offer_id)
        if not offer:
            self._send(chat_id, "Оффер не найден или удалён.", conn_id=conn_id)
            return
        if offer.creator_id == user_id:
            self._send(
                chat_id,
                "Это ваш оффер. Отправьте ссылку второй стороне:\n"
                f"<code>{self.deep_link(offer)}</code>",
                conn_id=conn_id,
            )
            return
        message_id = self._send(
            chat_id,
            card_text(offer, self.cfg.ttl_hours),
            keyboard=self._kb(offer),
            conn_id=conn_id,
        )
        if message_id and offer.status == "open":
            self.db.update(
                offer.id,
                card_chat_id=chat_id,
                card_msg_id=message_id,
                card_conn_id=conn_id,
                recipient_id=offer.recipient_id or int(user_id),
            )
            offer = self.db.get(offer.id) or offer
            self._edit_board(offer)

    def _cmd_help(self, chat_id: int, conn_id: Optional[str]) -> None:
        support = f"\n\nПоддержка: {esc(self.cfg.support_contact)}" if self.cfg.support_contact else ""
        self._send(
            chat_id,
            "<b>Команды</b>\n"
            "/offer &lt;ссылка&gt; &lt;цена&gt; — хочу купить за звёзды\n"
            "/sell &lt;ссылка&gt; &lt;цена&gt; — продаю за звёзды\n"
            "/myoffers — мои офферы и статусы\n"
            "/cancel ID — отменить свой оффер\n"
            "/how — как проходит сделка 1:1\n"
            "/id — chat_id и user_id\n\n"
            "Бот не хранит звёзды и подарки и берёт 0%. Комиссию за продажу берёт Telegram."
            + support,
            conn_id=conn_id,
        )

    def _cmd_myoffers(self, chat_id: int, conn_id: Optional[str], user_id: int) -> None:
        offers = self.db.list_by_creator(user_id, limit=10)
        if not offers:
            self._send(chat_id, "У вас пока нет офферов. Начните с /offer или /sell.", conn_id=conn_id)
            return
        lines = ["<b>Мои офферы</b>"]
        for offer in offers:
            lines.append(
                f"№{offer.id} · {offer.kind_emoji} {offer.kind_title} · "
                f"{offer.price} {STARS} · <i>{esc(status_ru(offer.status))}</i>\n"
                f"<a href=\"{esc(self.deep_link(offer))}\">ссылка для второй стороны</a>"
            )
        lines.append("\nОтменить: <code>/cancel ID</code>")
        self._send(chat_id, "\n\n".join(lines), conn_id=conn_id)

    def _cmd_cancel(self, chat_id: int, conn_id: Optional[str], user_id: int, args: str) -> None:
        match = re.search(r"\d+", args or "")
        if not match:
            self._send(chat_id, "Укажите номер: <code>/cancel 12</code>", conn_id=conn_id)
            return
        offer = self.db.get(int(match.group(0)))
        if not offer or offer.creator_id != user_id:
            self._send(chat_id, "Оффер не найден или он не ваш.", conn_id=conn_id)
            return
        if offer.status not in ("open", "accepted", "sent"):
            self._send(chat_id, f"Уже закрыт: {status_ru(offer.status)}.", conn_id=conn_id)
            return
        self._set_status(offer, "cancelled", by="creator")
        self._send(chat_id, f"Оффер №{offer.id} отменён.", conn_id=conn_id)

    # -- callbacks ---------------------------------------------------------

    def _on_callback(self, callback: Dict[str, Any]) -> None:
        data = callback.get("data") or ""
        callback_id = callback.get("id") or ""
        user = callback.get("from") or {}
        user_id = int(user.get("id") or 0)
        message = callback.get("message") or {}
        chat_id = (message.get("chat") or {}).get("id")
        message_id = message.get("message_id")

        if ":" not in data:
            self._answer(callback_id, "Кнопка устарела", alert=False)
            return
        action, raw_id = data.split(":", 1)
        if not raw_id.isdigit():
            self._answer(callback_id, "Кнопка устарела", alert=False)
            return
        offer = self.db.get(int(raw_id))
        if not offer:
            self._answer(callback_id, "Оффер не найден", alert=True)
            return

        if action == "how":
            self._answer(callback_id)
            self._send(chat_id, how_text(self.username), conn_id=offer.conn_id)
            return

        if action == "acc":
            self._on_accept(callback_id, offer, user_id, chat_id, message_id)
        elif action == "dec":
            self._on_decline(callback_id, offer, user_id)
        elif action == "sent":
            self._on_sent(callback_id, offer, user_id)
        elif action == "done":
            self._on_done(callback_id, offer, user_id)
        elif action == "cxl":
            self._on_cancel_button(callback_id, offer, user_id)
        else:
            self._answer(callback_id, "Неизвестное действие", alert=False)

    def _answer(self, callback_id: str, text: Optional[str] = None, alert: bool = False) -> None:
        try:
            self.api.answer_callback(callback_id, text, alert)
        except TelegramError as exc:
            self.logger.debug("answerCallbackQuery: %s", exc)

    def _on_accept(self, callback_id: str, offer: Offer, user_id: int, chat_id: Optional[int],
                   message_id: Optional[int]) -> None:
        if offer.creator_id == user_id:
            self._answer(callback_id, "Это ваш оффер. Отправьте ссылку второй стороне.", alert=True)
            return
        if offer.status != "open":
            self._answer(callback_id, f"Уже {status_ru(offer.status)}.", alert=True)
            return
        if not chat_id:
            self._answer(callback_id, "Ошибка карточки", alert=True)
            return

        self.db.update(
            offer.id,
            status="accepted",
            recipient_id=offer.recipient_id or int(user_id),
            card_chat_id=chat_id,
            card_msg_id=message_id or offer.card_msg_id,
        )
        offer = self.db.get(offer.id) or offer
        self.logger.info("Оффер %s принят пользователем %s", offer.id, user_id)
        self._edit_card(offer)
        self._edit_board(offer)

        # Инструкции обеим сторонам: покупатель отправляет оффер, продавец ждёт.
        buyer_id = offer.buyer_id
        seller_id = offer.seller_id
        sent: Dict[int, bool] = {}
        if buyer_id:
            sent[buyer_id] = self._notify(offer, buyer_id, buyer_instructions(offer)) is not None
        if seller_id:
            sent[seller_id] = self._notify(offer, seller_id, seller_instructions(offer)) is not None

        if all(sent.values()) and sent:
            self._answer(callback_id, "Готово — инструкции отправлены обеим сторонам")
        else:
            self._answer(
                callback_id,
                "Намерение принято, но второй стороне не удалось написать "
                "(Telegram: PEER_FLOOD / человек не начинал чат с ботом). "
                "Попросите её открыть бота и нажать /start — тогда инструкции дойдут.",
                alert=True,
            )

    def _on_decline(self, callback_id: str, offer: Offer, user_id: int) -> None:
        if offer.creator_id == user_id:
            self._answer(callback_id, "Это ваш оффер", alert=True)
            return
        if offer.status not in ("open", "accepted"):
            self._answer(callback_id, f"Уже {status_ru(offer.status)}.", alert=True)
            return
        self.db.update(offer.id, recipient_id=offer.recipient_id or int(user_id))
        offer = self.db.get(offer.id) or offer
        self._set_status(offer, "declined", by="recipient")
        self._answer(callback_id, "Отклонено")
        if offer.creator_id:
            self._notify(offer, offer.creator_id, f"❌ Вторая сторона отклонила оффер №{offer.id}.")

    def _on_sent(self, callback_id: str, offer: Offer, user_id: int) -> None:
        if user_id != offer.buyer_id:
            self._answer(callback_id, "Эту кнопку нажимает покупатель.", alert=True)
            return
        if offer.status != "accepted":
            self._answer(callback_id, f"Сейчас статус: {status_ru(offer.status)}.", alert=True)
            return
        self._set_status(offer, "sent", by="buyer")
        self._answer(callback_id, "Отмечено")
        if offer.seller_id:
            self._notify(
                offer,
                offer.seller_id,
                f"📨 Покупатель отправил официальный оффер по лоту №{offer.id}.\n\n"
                "Примите его в Telegram: сообщение с оффером → «Принять». "
                "Подарок и звёзды обменяет сам Telegram.\n\n"
                "❗ Ничего не пересылайте вручную.",
            )

    def _on_done(self, callback_id: str, offer: Offer, user_id: int) -> None:
        if user_id != offer.seller_id:
            self._answer(callback_id, "Эту кнопку нажимает продавец.", alert=True)
            return
        if offer.status not in ("sent", "accepted"):
            self._answer(callback_id, f"Сейчас статус: {status_ru(offer.status)}.", alert=True)
            return
        self._set_status(offer, "completed", by="seller")
        self._answer(callback_id, "Сделка закрыта")
        if offer.buyer_id:
            self._notify(
                offer,
                offer.buyer_id,
                f"🏁 Продавец подтвердил сделку по лоту №{offer.id}.\n"
                "Проверьте подарок в профиле. Если что-то не так — напишите в поддержку.",
            )

    def _on_cancel_button(self, callback_id: str, offer: Offer, user_id: int) -> None:
        if user_id != offer.creator_id:
            self._answer(callback_id, "Отменить может только автор оффера.", alert=True)
            return
        if offer.status not in ("open", "accepted", "sent"):
            self._answer(callback_id, f"Уже {status_ru(offer.status)}.", alert=True)
            return
        self._set_status(offer, "cancelled", by="creator")
        self._answer(callback_id, "Отменено")
        if offer.recipient_id:
            self._notify(offer, offer.recipient_id, f"✖️ Автор отменил оффер №{offer.id}.")

    def _set_status(self, offer: Offer, status: str, by: str) -> None:
        self.logger.info("Оффер %s: %s → %s (%s)", offer.id, offer.status, status, by)
        self.db.update(offer.id, status=status)
        offer = self.db.get(offer.id) or offer
        self._edit_card(offer)
        self._edit_board(offer)

    # -- истечение ---------------------------------------------------------

    def expire_stale(self) -> int:
        cutoff = self.clock() - self.cfg.ttl_seconds
        expired = self.db.expired(cutoff)
        for offer in expired:
            self.db.update(offer.id, status="expired")
            fresh = self.db.get(offer.id) or offer
            self._edit_card(fresh)
            self._edit_board(fresh)
            self.logger.info("Оффер %s истёк", offer.id)
            if self.cfg.notify_on_expire:
                if offer.creator_id:
                    self._notify(
                        offer,
                        offer.creator_id,
                        f"⌛ Оффер №{offer.id} истёк. Создайте новый: /offer или /sell.",
                    )
                if offer.recipient_id:
                    self._notify(offer, offer.recipient_id, f"⌛ Оффер №{offer.id} истёк.")
        return len(expired)

    # -- запуск ------------------------------------------------------------

    def bootstrap(self) -> None:
        me = self.api.get_me()
        self.username = me.get("username") or ""
        self.logger.info("Бот: @%s (id=%s)", self.username, me.get("id"))
        try:
            info = self.api.get_webhook_info()
            if info.get("url"):
                self.logger.warning("Снимаю webhook (%s), иначе getUpdates даст 409", info["url"])
        except TelegramError:
            pass
        try:
            self.api.delete_webhook()
        except TelegramError as exc:
            self.logger.warning("deleteWebhook: %s", exc)
        try:
            self.api.set_my_commands(
                [
                    ("offer", "Хочу купить подарок за звёзды"),
                    ("sell", "Продаю подарок за звёзды"),
                    ("myoffers", "Мои офферы"),
                    ("how", "Как проходит сделка"),
                    ("help", "Помощь"),
                ]
            )
        except TelegramError:
            pass
        self.logger.info("Слушаю обновления (timeout=%d c)", self.cfg.poll_timeout)

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
                self.db.meta_set("offset", str(self.offset))
        return len(updates)

    def run(self, once: bool = False, max_updates: Optional[int] = None) -> int:
        self.bootstrap()
        processed = 0
        backoff = 3.0
        last_expire = 0.0
        while not self._stop.is_set():
            try:
                if self.clock() - last_expire > 60:
                    self.expire_stale()
                    last_expire = self.clock()
                batch = self.poll_once()
                backoff = 3.0
                processed += batch
                if once:
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
                        "409 Conflict: уже запущен другой экземпляр или остался webhook. "
                        "Остановите лишний процесс: pkill -f bot.py"
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
        self.db.meta_set("offset", str(self.offset))
        self.logger.info("Остановлено. Обработано апдейтов: %d", processed)
        return 0


def status_ru(status: str) -> str:
    return {
        "open": "открыт",
        "accepted": "принят",
        "sent": "оффер отправлен",
        "completed": "завершён",
        "declined": "отклонён",
        "cancelled": "отменён",
        "expired": "истёк",
    }.get(status, status)


# ---------------------------------------------------------------------------
# Проверки и CLI
# ---------------------------------------------------------------------------


def check(cfg: Config, logger: logging.Logger) -> int:
    problems: List[str] = []
    warnings: List[str] = []

    print("Конфигурация:\n" + cfg.summary() + "\n")

    if not cfg.token:
        problems.append("BOT_TOKEN пуст — впишите токен от @BotFather в .env")
    elif ":" not in cfg.token or not cfg.token.split(":", 1)[0].isdigit():
        problems.append("BOT_TOKEN не похож на токен (ожидается 123456789:AA...)")

    try:
        db = DB(str(cfg.db_path))
        print(f"База: {cfg.db_path} — ok, офферов: {sum(db.stats().values())}")
        if db.stats():
            print("Статусы: " + ", ".join(f"{status_ru(k)}: {v}" for k, v in db.stats().items()))
        db.close()
    except Exception as exc:  # noqa: BLE001
        problems.append(f"База {cfg.db_path}: {exc}")

    if cfg.token and ":" in cfg.token:
        api = TelegramAPI(
            token=cfg.token,
            api_base=cfg.api_base,
            poll_timeout=cfg.poll_timeout,
            insecure_tls=cfg.insecure_tls,
            ca_bundle=cfg.ca_bundle,
            logger=logger,
            max_network_retries=2,
        )
        try:
            me = api.get_me()
            print(f"getMe: @{me.get('username')} (id={me.get('id')})")
            if not me.get("username"):
                warnings.append("У бота нет username — deep-link приглашения работать не будут.")
        except TelegramError as exc:
            problems.append(f"getMe не прошёл: {exc}. {error_hint(exc.description)}".strip())
        else:
            try:
                info = api.get_webhook_info()
                if info.get("url"):
                    warnings.append(
                        f"Установлен webhook ({info['url']}) — при long polling будет 409. Бот снимает его сам."
                    )
            except TelegramError as exc:
                warnings.append(f"getWebhookInfo: {exc}")

    print()
    for warning in warnings:
        print(f"[!] {warning}")
    for problem in problems:
        print(f"[x] {problem}")
    if problems:
        print("\nИтог: есть ошибки, запускать нельзя.")
        return 1
    print("Итог: всё готово к запуску.")
    return 0


# ---------------------------------------------------------------------------
# Selftest
# ---------------------------------------------------------------------------


def selftest(cfg: Config, logger: logging.Logger) -> int:
    failures: List[str] = []

    def expect(name: str, condition: bool, detail: str = "") -> None:
        if condition:
            print(f"  ok   {name}")
        else:
            print(f"  FAIL {name} {detail}")
            failures.append(name)

    db = DB(":memory:")
    cfg = dataclasses.replace(cfg)
    cfg.token = "123456:SELFTEST"
    cfg.create_cooldown = 0.0
    cfg.board_chat_id = None
    api = FakeTelegramAPI()
    bot = OfferBot(cfg, api, db, logger=logger)
    bot.username = "gift_offer_bot"

    def dm(text: str, user_id: int = 100, message_id: int = 10) -> Dict[str, Any]:
        return {
            "message_id": message_id,
            "date": int(time.time()),
            "chat": {"id": user_id, "type": "private"},
            "from": {"id": user_id, "is_bot": False, "first_name": "Tester"},
            "text": text,
        }

    print("Проверка сценария 1:1:")

    bot.handle_update({"update_id": 1, "message": dm("/offer https://t.me/nft/PlushPepe-123 250")})
    expect("команда удалена", "deleteMessage" in api.methods(), str(api.methods()))
    expect("удаление раньше отправки",
           api.methods().index("deleteMessage") < api.methods().index("sendMessage"))
    first = api.only("sendMessage")[0]["text"] if api.only("sendMessage") else ""
    expect("карточка-подтверждение с deep-link", "t.me/gift_offer_bot?start=offer_1" in first, first)
    expect("цена в карточке", "250" in first, first)

    offer = db.get(1)
    expect("оффер в базе", offer is not None and offer.status == "open")

    api.clear()
    bot.handle_update({"update_id": 2, "message": dm("/start offer_1", user_id=200)})
    expect("вторая сторона получила карточку", len(api.only("sendMessage")) == 1)
    card = api.only("sendMessage")[0]
    expect("в карточке кнопка «Принять»",
           "acc:1" in json.dumps(card.get("reply_markup") or {}), json.dumps(card.get("reply_markup")))

    api.clear()
    bot.handle_update({
        "update_id": 3,
        "callback_query": {
            "id": "cb1",
            "from": {"id": 200, "is_bot": False, "first_name": "Owner"},
            "data": "acc:1",
            "message": {"message_id": 501, "chat": {"id": 200, "type": "private"}},
        },
    })
    expect("намерение принято", db.get(1).status == "accepted", db.get(1).status)
    expect("карточка отредактирована (своя)", "editMessageText" in api.methods(), str(api.methods()))
    texts = [p.get("text", "") for p in api.only("sendMessage")]
    expect("инструкция покупателю (Offer to Buy)", any("Offer to Buy" in t for t in texts), str(texts)[:300])
    expect("инструкция продавцу про безопасность", any("Ничего не отправляйте" in t for t in texts))

    api.clear()
    bot.handle_update({
        "update_id": 4,
        "callback_query": {
            "id": "cb2",
            "from": {"id": 100, "is_bot": False},
            "data": "sent:1",
            "message": {"message_id": 501, "chat": {"id": 200, "type": "private"}},
        },
    })
    expect("покупатель отметил отправку", db.get(1).status == "sent", db.get(1).status)

    api.clear()
    bot.handle_update({
        "update_id": 5,
        "callback_query": {
            "id": "cb3",
            "from": {"id": 200, "is_bot": False},
            "data": "done:1",
            "message": {"message_id": 501, "chat": {"id": 200, "type": "private"}},
        },
    })
    expect("сделка завершена", db.get(1).status == "completed", db.get(1).status)

    # продажа: принимающий = покупатель, ему и инструкция
    api.clear()
    bot.handle_update({"update_id": 6, "message": dm("/sell https://t.me/nft/Durov-1 999", user_id=300, message_id=12)})
    expect("лот /sell создан", db.get(2) is not None and db.get(2).kind == "sell")
    api.clear()
    bot.handle_update({"update_id": 7, "message": dm("/start offer_2", user_id=400, message_id=13)})
    api.clear()
    bot.handle_update({
        "update_id": 8,
        "callback_query": {
            "id": "cb4",
            "from": {"id": 400, "is_bot": False},
            "data": "acc:2",
            "message": {"message_id": 700, "chat": {"id": 400, "type": "private"}},
        },
    })
    texts = [p.get("text", "") for p in api.only("sendMessage")]
    buyer_text = [t for t in texts if "Вы <b>покупатель</b>".lower() in t.lower()]
    expect("при принятии лота /sell покупатель получает инструкцию", bool(buyer_text), str(texts)[:300])

    # защита: создатель не может принять свой оффер
    api.clear()
    bot.handle_update({
        "update_id": 9,
        "callback_query": {
            "id": "cb5",
            "from": {"id": 300, "is_bot": False},
            "data": "acc:2",
            "message": {"message_id": 700, "chat": {"id": 300, "type": "private"}},
        },
    })
    expect("автор не может принять свой оффер", not api.only("sendMessage"))

    # лимит цены
    api.clear()
    bot.handle_update({"update_id": 10, "message": dm("/offer https://t.me/nft/PlushPepe-1 999999999", message_id=14)})
    expect("слишком большая цена отклонена", any("Цена должна быть" in p["text"] for p in api.only("sendMessage")))

    # мусорная ссылка
    api.clear()
    bot.handle_update({"update_id": 11, "message": dm("/offer https://example.com/x 100", message_id=15)})
    expect("чужая ссылка отклонена", any("Не вижу ссылку" in p["text"] for p in api.only("sendMessage")))

    # TON отклоняем
    api.clear()
    bot.handle_update({"update_id": 12, "message": dm("/offer https://t.me/nft/PlushPepe-2 100 TON", message_id=16)})
    expect("TON отклонён", any("только звёзды" in p["text"].lower() for p in api.only("sendMessage")))

    # HTML-экранирование имени
    api.clear()
    msg = dm("/offer https://t.me/nft/PlushPepe-3 50", message_id=17)
    msg["from"]["first_name"] = "<i>hack</i>"
    bot.handle_update({"update_id": 13, "message": msg})
    bot.handle_update({"update_id": 14, "message": dm("/start offer_3", user_id=301, message_id=18)})
    joined = json.dumps(api.calls, ensure_ascii=False)
    expect("HTML в имени экранирован", "&lt;i&gt;hack&lt;/i&gt;" in joined, joined[:300])

    # истечение открытого оффера
    db.update(3, created_at=time.time() - 100 * 3600)
    expired = bot.expire_stale()
    expect("истёкший оффер закрывается", expired >= 1 and db.get(3).status == "expired", str(expired))

    db.close()
    print()
    if failures:
        print(f"ПРОВАЛЕНО: {len(failures)}")
        return 1
    print("Все проверки пройдены.")
    return 0


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="offer_bot.py",
        description="Gift Offer Bot: 1:1 намерения «подарок ↔ звёзды» без хранения средств.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Примеры:\n"
            "  python3 bot.py --check\n"
            "  python3 bot.py --selftest\n"
            "  python3 bot.py\n"
        ),
    )
    parser.add_argument("--env", help="путь к .env")
    parser.add_argument("--token", help="токен бота (перебивает .env)")
    parser.add_argument("--set-token", metavar="TOKEN", help="вписать токен в .env (chmod 600) и выйти")
    parser.add_argument("--check", action="store_true", help="проверить токен, базу и настройки")
    parser.add_argument("--selftest", action="store_true", help="офлайн-тест сценария 1:1")
    parser.add_argument("--once", action="store_true", help="один цикл getUpdates и выход")
    parser.add_argument("--dry-run", action="store_true", help="ничего не отправлять")
    parser.add_argument("--verbose", "-v", action="store_true", help="DEBUG-лог")
    parser.add_argument("--version", action="version", version=f"offer-bot {VERSION}")
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

    if args.set_token:
        env_path = Path(args.env).expanduser() if args.env else (cfg.env_path or cfg.base_dir / ".env")
        return set_token(
            env_path,
            args.set_token,
            api_base=cfg.api_base,
            insecure_tls=cfg.insecure_tls,
            ca_bundle=cfg.ca_bundle,
        )

    if args.selftest:
        return selftest(cfg, logger)
    if args.check:
        return check(cfg, logger)

    if not cfg.token:
        print(
            "Нет токена. Создайте .env рядом с bot.py:\n\n    BOT_TOKEN=123456789:AA...\n\n"
            "или: python3 bot.py --token '123456789:AA...'\n"
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
    db = DB(str(cfg.db_path))
    bot = OfferBot(cfg, api, db, logger=logger)

    def _signal(signum: int, _frame: Any) -> None:
        logger.info("Сигнал %s — завершаюсь", signum)
        bot.stop()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, _signal)
        except (ValueError, OSError):
            pass

    try:
        return bot.run(once=args.once)
    except KeyboardInterrupt:
        bot.stop()
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    sys.exit(main())
