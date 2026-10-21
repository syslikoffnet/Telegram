#!/usr/bin/env python3
"""Pengram: канал + чат-форум одним запуском.

Что делает скрипт
-----------------
Заходит в ваш аккаунт Telegram и создаёт «под ключ» площадку проекта:

  • канал (broadcast) — релизы, беты, «что нового», жизнь проекта;
  • супергруппу-форум — обычное общение, разложенное по темам:
    Баги, Идеи, Помощь, Скриншоты, Релизы, Оффтоп;
  • связывает канал и чат (комментарии к постам падают в чат);
  • ставит аватарки канала и чата из branding/community/avatars;
  • публикует готовые посты и закрепляет нужное;
  • заводит папку «Pengram» в вашем клиенте;
  • печатает invite-ссылки.

Как запускать
-------------
Сначала посмотреть план, ничего не создавая (креды не нужны):

    python3 scripts/pengram-community.py

Потом применить:

    python3 scripts/pengram-community.py --apply

Аватарки (10 штук на выбор):

    python3 scripts/pengram-community.py --avatar-list
    python3 scripts/pengram-community.py --apply --channel-avatar 2 --group-avatar 3

Логин: телефон, код из Telegram, пароль 2FA — всё спросит в терминале.
Ключи берутся из api.properties (APP_ID / APP_HASH), переменных окружения
TELEGRAM_API_ID / TELEGRAM_API_HASH или спрашиваются вручную.

Что важно знать
---------------
• Сессия и состояние лежат в домашней папке (~/.pengram-community.*),
  в репозиторий они не попадают.
• Повторный запуск не создаёт дубли: скрипт помнит id канала и чата.
• Username подбирается из списка кандидатов; занятые пропускаются.
• Между запросами есть пауза, чтобы не поймать FloodWait.
• Скрипт ничего не удаляет и не меняет в других чатах.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import random
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Пути и состояние
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent

# В репозитории картинки лежат в branding/community. Если скрипт скачали
# одним файлом в произвольную папку — складываем их рядом с собой.
_REPO_ASSETS = ROOT / "branding" / "community"
PROJECT_DIR = _REPO_ASSETS if _REPO_ASSETS.is_dir() else SCRIPT_DIR / "pengram-community-assets"
AVATAR_DIR = PROJECT_DIR / "avatars"

STATE_PATH = Path.home() / ".pengram-community.json"
SESSION_PATH = Path.home() / ".pengram-community.session"

STEP_PAUSE = 1.6  # секунды между запросами: мягче к лимитам Telegram

# откуда докачивать аватарки, если скрипт скачали одним файлом
REPO_SLUG = "syslikoffnet/Telegram"
ASSETS_REF = "0063d6b29a25eb47851d58136a9130d7d7677ab5"


# ---------------------------------------------------------------------------
# Тексты. Всё, что публикуется, лежит здесь — правьте под себя.
# {channel} и {group} подставятся в @username (или в название, если username нет).
# ---------------------------------------------------------------------------

CHANNEL = {
    "title": "Pengram",
    "about": (
        "Telegram с доработками: призрак, антиудаление, свобода, стекло. "
        "Релизы, беты и жизнь проекта.\n"
        "Чат: {group}"
    ),
    "usernames": [
        "pengram",
        "pengramapp",
        "pengram_news",
        "pengramapp_news",
        "pengram_beta",
        "pengramclient",
    ],
}

GROUP = {
    "title": "Pengram Chat",
    "about": (
        "Живое общение проекта Pengram: баги, идеи, помощь, скриншоты и оффтоп. "
        "Всё разложено по темам — выбирайте нужную сверху."
    ),
    "usernames": [
        "pengram_chat",
        "pengramchat",
        "pengram_talk",
        "pengram_community",
        "pengram_lounge",
        "pengram_ru_chat",
    ],
    # только админы могут добавлять людей: спам-боты не затащат своих
    "invite_admins_only": True,
}

# Папка в клиенте (dialog filter)
FOLDER = {
    "title": "Pengram",
    "emoticon": "🐧",
}

# Посты канала. pinned=True — сразу закрепить.
CHANNEL_POSTS = [
    {
        "key": "intro",
        "pinned": True,
        "text": (
            "Пингвин на связи 🐧\n\n"
            "Это канал Pengram — того самого Telegram с доработками.\n\n"
            "Что здесь будет:\n"
            "— релизы и беты: файлом, сразу, без ожидания;\n"
            "— что нового и зачем это нужно;\n"
            "— честные заметки: что сломалось, что чиню, что отложил;\n"
            "— иногда просто жизнь проекта.\n\n"
            "Что такое Pengram в двух строках: призрак (не свечусь онлайн, "
            "не отправляю «прочитано» и «печатает»), антиудаление (удалённое "
            "и изменённое остаётся), свобода (скриншоты, обход запрета пересылки, "
            "одноразовые не сгорают), жидкое стекло, свои шрифты, эффекты голоса, "
            "тексты песен, AI-автоответы.\n\n"
            "Общение и поддержка — в чате: @{group}\n"
            "Баги и идеи — туда же, в нужную тему.\n\n"
            "Пока бета: всё может меняться, но ваши чаты и аккаунты — нет. "
            "Спасибо, что вы здесь."
        ),
    },
    {
        "key": "features",
        "pinned": False,
        "text": (
            "Что уже умеет Pengram\n\n"
            "👻 Призрак — всегда оффлайн, без «прочитано», без «печатает», "
            "без отметок о просмотре сторис.\n\n"
            "🕵️ Антиудаление — удалённые и изменённые сообщения остаются у вас. "
            "История правок, прежние фото и видео, история профилей и аватарок.\n\n"
            "🔓 Свобода — скриншоты везде, обход запрета пересылки, одноразовые "
            "медиа не сгорают, без рекламы, звёздочка Premium у своего имени, "
            "скрытие номера.\n\n"
            "🎨 Внешность — жидкое стекло, MD3-остров, свои шрифты, иконки "
            "уведомлений, плитки на главном, живой пингвин.\n\n"
            "💬 Чаты — свой порядок меню, скрытие лишних пунктов, остров Pengram "
            "в меню чата, метки удалённых и изменённых, цитаты с подписью, "
            "переход в чат по ID.\n\n"
            "🎙 Голос и видео — скорость видео 2× и 4×, эффекты голоса, своя "
            "голосовая студия, шумодав.\n\n"
            "🎵 Плеер — тексты песен всегда, плавная подсветка, карточка трека, "
            "«что я слушаю».\n\n"
            "🤖 Нейросети — OpenAI, OpenRouter, DeepSeek, Groq, Mistral, "
            "локальная Ollama, автоответы и свой стиль ответа.\n\n"
            "👤 Аккаунты — несколько аккаунтов, порядок перетаскиванием, вход "
            "по tdata и .session, выгрузка сессии.\n\n"
            "🧰 Ещё — экспорт чата в HTML, копирование фото, бэкап настроек, "
            "статистика набора, @all с лимитом, работа в фоне.\n\n"
            "Всё бесплатно, без подписки. Баги и пожелания — в чате: @{group}"
        ),
    },
    {
        "key": "install",
        "pinned": False,
        "text": (
            "Как поставить бету\n\n"
            "1. Скачайте APK из этого канала (файл появляется вместе с постом "
            "о новой версии).\n"
            "2. Android спросит разрешение на установку из этого источника — "
            "разрешите. Это стандартное поведение системы, а не «вирус».\n"
            "3. Ставьте поверх старой версии: подпись одна и та же, чаты и "
            "настройки не пропадут. Удалять приложение не нужно.\n"
            "4. Откройте и войдите как обычно — по номеру, по tdata или "
            "по .session.\n\n"
            "Если обновление не ставится: проверьте, что APK скачался целиком "
            "(вес указан в посте) и что вы не открываете его из архиватора.\n\n"
            "Просьба: не выкладывайте APK в открытые чаты и на форумы. "
            "Пока проект живёт в узком кругу — он живой."
        ),
    },
    {
        "key": "report",
        "pinned": False,
        "text": (
            "Как рассказать о баге, чтобы его починили\n\n"
            "Одно сообщение — одна проблема. Дальше по шаблону:\n\n"
            "1. Что делал: «открыл чат, нажал на стикер, выбрал „Отправить без звука“».\n"
            "2. Что случилось: «вылетело», «белый экран», «кнопка не нажимается».\n"
            "3. Чего ожидал: «стикер должен уйти тихо».\n"
            "4. Как часто: «всегда», «иногда», «один раз».\n\n"
            "И приложите отчёт: Настройки → Pengram → О программе → "
            "«Скопировать всё». Там версия, модель телефона и Android — без них "
            "половину багов не воспроизвести.\n\n"
            "Скриншот или короткое видео очень помогают. Спасибо!"
        ),
    },
    {
        "key": "life",
        "pinned": False,
        "text": (
            "Первый день\n\n"
            "Канал только появился, в нём пока тихо. Собираю всё, что делал "
            "последние месяцы, в одну нормальную точку входа: тут будут файлы, "
            "а не «скачай где-то там».\n\n"
            "Если вы здесь — вы из первых. Пишите, что не работает и чего не "
            "хватает: сейчас самое время ломать и предлагать. Чат: @{group}"
        ),
    },
]

# Закреп в общей теме чата
GROUP_PINNED = (
    "Привет 👋\n\n"
    "Это чат Pengram. Здесь можно всё, кроме спама.\n\n"
    "Куда что писать:\n"
    "🐞 Баги — что-то сломалось\n"
    "💡 Идеи — чего не хватает\n"
    "🛠 Помощь — установка, вход, tdata, сессии\n"
    "📸 Скриншоты — покажи, как выглядит у тебя\n"
    "📦 Релизы — обсуждение новых версий\n"
    "🌿 Оффтоп — всё остальное\n\n"
    "Правила простые: уважайте друг друга, не спамьте, не выкладывайте APK "
    "в открытые места.\n\n"
    "Перед тем как писать о баге: Настройки → Pengram → О программе → "
    "«Скопировать всё» и приложите отчёт."
)

# Темы форума. color — палитра иконок Telegram, pinned — текст закрепа в теме.
TOPICS = [
    {
        "key": "Баги",
        "title": "🐞 Баги",
        "color": 0xFB6F5F,
        "pinned": (
            "Пишите по шаблону — так починю быстрее:\n\n"
            "1. Что делал\n"
            "2. Что случилось\n"
            "3. Чего ожидал\n"
            "4. Как часто\n\n"
            "Плюс отчёт: Настройки → Pengram → О программе → «Скопировать всё». "
            "Скриншот или видео — очень помогает."
        ),
    },
    {
        "key": "Идеи",
        "title": "💡 Идеи",
        "color": 0xFFD67E,
        "pinned": (
            "Опишите в трёх строках:\n\n"
            "— что хочу;\n"
            "— зачем это нужно;\n"
            "— как представляю (где кнопка, как выглядит).\n\n"
            "Чем конкретнее — тем больше шансов, что появится."
        ),
    },
    {
        "key": "Помощь",
        "title": "🛠 Помощь",
        "color": 0x6FB9F0,
        "pinned": (
            "Установка, вход по номеру, tdata, .session, перенос с другого "
            "клиента — спрашивайте здесь.\n\n"
            "Если что-то не входит: приложите отчёт из «О программе» и "
            "напишите, на каком шаге остановились."
        ),
    },
    {
        "key": "Скриншоты",
        "title": "📸 Скриншоты",
        "color": 0x8EEE98,
        "pinned": (
            "Показывайте, как собрали интерфейс: тема, стекло, шрифт, пингвин.\n\n"
            "Идеи оформления отсюда часто доходят до релиза."
        ),
    },
    {
        "key": "Релизы",
        "title": "📦 Релизы",
        "color": 0xCB86DB,
        "pinned": (
            "Каждая новая версия — пост в канале. Здесь можно обсудить, "
            "что зашло, а что сломали.\n\n"
            "Пожалуйста, указывайте версию из «О программе»."
        ),
    },
    {
        "key": "Оффтоп",
        "title": "🌿 Оффтоп",
        "color": 0xFF93B2,
        "pinned": "Всё, что не про Pengram. Без политики и спама.",
    },
]

# Аватарки: номер -> файл и подпись
AVATAR_CHOICES = [
    (1, "01_channel_megaphone", "Пингвин с рупором — канал"),
    (2, "02_channel_plane", "Пингвин с бумажным самолётиком — новости"),
    (3, "03_chat_bubble", "Пингвин в облачке — чат"),
    (4, "04_beta_engineer", "Пингвин-инженер с пробиркой — беты"),
    (5, "05_beta_astronaut", "Пингвин-космонавт — эксперименты"),
    (6, "06_life_coffee", "Пингвин с кофе — жизнь проекта"),
    (7, "07_ideas_bulb", "Пингвин с лампочкой — идеи"),
    (8, "08_bugs_detective", "Пингвин-детектив — баги"),
    (9, "09_help_lifebuoy", "Пингвин со спасательным кругом — помощь"),
    (10, "10_mark_monogram", "Знак P — минимализм"),
]


# ---------------------------------------------------------------------------
# Мелкие помощники
# ---------------------------------------------------------------------------

class Stop(Exception):
    """Понятная человеку ошибка: печатаем текст и выходим без трейсбека."""


def log(step: str, message: str) -> None:
    print(f"[{step}] {message}", flush=True)


def warn(message: str) -> None:
    print(f"      ⚠️  {message}", flush=True)


def _download(url: str, headers: dict | None = None, timeout: int = 25) -> bytes:
    import urllib.request

    request = urllib.request.Request(url, headers={
        "User-Agent": "pengram-community-script",
        **(headers or {}),
    })
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def download_avatar(number: int) -> Path | None:
    """Тянет аватарку из репозитория, если её нет рядом со скриптом.

    Скрипт можно скачать одним файлом — картинки он дозагрузит сам.
    Сначала быстрый raw, потом API (у него другие лимиты и хосты).
    """
    path = AVATAR_DIR / f"{name_for(number)}.jpg"
    urls = [
        f"https://raw.githubusercontent.com/{REPO_SLUG}/{ASSETS_REF}/"
        f"branding/community/avatars/{path.name}",
        f"https://api.github.com/repos/{REPO_SLUG}/contents/"
        f"branding/community/avatars/{path.name}?ref={ASSETS_REF}",
    ]
    for url in urls:
        try:
            payload = _download(
                url, {"Accept": "application/vnd.github.raw"} if "api." in url else None)
        except Exception:
            continue
        if len(payload) < 10_000:
            continue  # пришёл текст ошибки, а не картинка
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
        log("аватарка", f"скачана: {path.name}")
        return path
    return None


def name_for(number: int) -> str:
    for value, name, _ in AVATAR_CHOICES:
        if value == number:
            return name
    raise Stop(f"Аватарки №{number} нет. Допустимо: 1–{len(AVATAR_CHOICES)}")


def avatar_path(number: int, *, required: bool = True) -> Path | None:
    """Локальный файл, иначе — скачанный, иначе None (если not required)."""
    name = name_for(number)
    path = AVATAR_DIR / f"{name}.jpg"
    if path.exists():
        return path
    path = download_avatar(number)
    if path is not None:
        return path
    if required:
        raise Stop(f"Аватарка №{number} недоступна: нет локально и не скачалась. "
                   f"Проверьте интернет или положите файл в {AVATAR_DIR}")
    return None


def load_state() -> dict:
    if STATE_PATH.exists():
        try:
            return json.loads(STATE_PATH.read_text(encoding="utf-8"))
        except Exception:
            warn(f"Состояние {STATE_PATH} не читается, начну с чистого листа")
    return {}


def save_state(state: dict) -> None:
    STATE_PATH.write_text(json.dumps(state, ensure_ascii=False, indent=2),
                          encoding="utf-8")


def format_text(template: str, usernames: dict) -> str:
    """Подставляет @channel / @group, а если username нет — просто название.

    Именно replace, а не str.format: в текстах могут встретиться фигурные
    скобки, и .format на них падает.
    """
    channel = usernames.get("channel")
    group = usernames.get("group")
    return (template
            .replace("{channel}", f"@{channel}" if channel else f"«{CHANNEL['title']}»")
            .replace("{group}", f"@{group}" if group else f"«{GROUP['title']}»"))


def read_api_credentials() -> tuple[int, str]:
    api_id = os.environ.get("TELEGRAM_API_ID", "").strip()
    api_hash = os.environ.get("TELEGRAM_API_HASH", "").strip()

    if not (api_id and api_hash):
        props = ROOT / "api.properties"
        if props.exists():
            values = {}
            for raw in props.read_text(encoding="utf-8").splitlines():
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
            api_id = api_id or values.get("APP_ID", values.get("TELEGRAM_API_ID", ""))
            api_hash = api_hash or values.get("APP_HASH", values.get("TELEGRAM_API_HASH", ""))

    if not api_id or not api_hash:
        print("Нужны APP_ID и APP_HASH (my.telegram.org → API development tools).")
        print("Можно положить их в api.properties, задать TELEGRAM_API_ID / "
              "TELEGRAM_API_HASH или ввести сейчас.")
        api_id = api_id or input("APP_ID: ").strip()
        api_hash = api_hash or input("APP_HASH: ").strip()

    try:
        return int(api_id), api_hash
    except ValueError:
        raise Stop("APP_ID должен быть числом")


# ---------------------------------------------------------------------------
# План в режиме просмотра
# ---------------------------------------------------------------------------

def print_plan(args) -> None:
    channel_avatar = avatar_path(args.channel_avatar, required=False)
    group_avatar = avatar_path(args.group_avatar, required=False)

    print()
    print("=" * 72)
    print("  ЧТО БУДЕТ СОЗДАНО (просмотр, ничего не отправляется)")
    print("=" * 72)

    print("\nКАНАЛ")
    print(f"  название:  {CHANNEL['title']}")
    print(f"  аватарка:  {channel_avatar.name if channel_avatar else 'недоступна (без фото)'}")
    print(f"  username:  {', '.join('@' + u for u in CHANNEL['usernames'])}"
          "  (первый свободный)")
    print("  описание:")
    for line in CHANNEL["about"].replace(
            "{group}", "@" + GROUP["usernames"][0]).split("\n"):
        print(f"      {line}")

    print("\nЧАТ (супергруппа-форум)")
    print(f"  название:  {GROUP['title']}")
    print(f"  аватарка:  {group_avatar.name if group_avatar else 'недоступна (без фото)'}")
    print(f"  username:  {', '.join('@' + u for u in GROUP['usernames'])}"
          "  (первый свободный)")
    print(f"  темы:      {', '.join(t['title'] for t in TOPICS)}")
    print(f"  добавлять участников: {'только админы' if GROUP['invite_admins_only'] else 'все'}")

    print("\nПОСТЫ КАНАЛА")
    for post in CHANNEL_POSTS:
        pin = " + закреп" if post["pinned"] else ""
        first = post["text"].split("\n")[0]
        print(f"  • {first} ({len(post['text'])} символов){pin}")

    print("\nЗАКРЕПЫ В ЧАТЕ")
    print(f"  • общая тема: правила и навигация ({len(GROUP_PINNED)} символов)")
    for topic in TOPICS:
        print(f"  • {topic['title']}: как писать ({len(topic['pinned'])} символов)")

    print("\nПАПКА В КЛИЕНТЕ")
    print(f"  {FOLDER['emoticon']} {FOLDER['title']}: канал + чат")

    if getattr(args, "show_texts", False):
        preview_names = {"channel": CHANNEL["usernames"][0],
                         "group": GROUP["usernames"][0]}
        print("\nТЕКСТЫ ЦЕЛИКОМ (username — как в первом варианте)")
        for post in CHANNEL_POSTS:
            print("\n— — — канал: " + post["text"].splitlines()[0] + " — — —")
            print(format_text(post["text"], preview_names))
        print("\n— — — закреп в чате — — —")
        print(format_text(GROUP_PINNED, preview_names))
        for topic in TOPICS:
            print(f"\n— — — тема «{topic['title']}» — — —")
            print(topic["pinned"])

    print("\nПОСЛЕ СОЗДАНИЯ")
    print("  • канал и чат связываются: комментарии к постам идут в чат")
    print("  • печатаются invite-ссылки для раздачи тестерам")
    print()
    print("Запустить по-настоящему:  python3 scripts/pengram-community.py --apply")
    print()


def print_avatars() -> None:
    print("\nАватарки в branding/community/avatars:\n")
    for number, name, caption in AVATAR_CHOICES:
        path = AVATAR_DIR / f"{name}.jpg"
        mark = "✓" if path.exists() else "✗"
        print(f"  {mark} {number:>2}. {caption}  ({path.name})")
    print("\nЕсли файла нет рядом со скриптом, он скачается сам при первом запуске.")
    print("Выбор: --channel-avatar N и --group-avatar M")
    print("Превью всех сразу: branding/community/avatars-preview.jpg\n")


# ---------------------------------------------------------------------------
# Работа с Telegram
# ---------------------------------------------------------------------------

def peer(entity):
    """Приводит канал/группу к inputPeer — так их принимают поля *peer*."""
    from telethon.tl.types import InputChannel, InputPeerChannel

    if isinstance(entity, InputPeerChannel):
        return entity
    if isinstance(entity, InputChannel):
        return InputPeerChannel(entity.channel_id, entity.access_hash)
    return InputPeerChannel(entity.id, entity.access_hash)


def input_channel(entity):
    """InputChannel там, где Telegram требует именно его (getFullChannel)."""
    from telethon.tl.types import InputChannel

    if isinstance(entity, InputChannel):
        return entity
    return InputChannel(entity.id, entity.access_hash)


def _filter_title(item) -> str:
    title = getattr(item, "title", "")
    return getattr(title, "text", title) or ""


def has_photo(entity) -> bool:
    """У сущности реально есть аватарка (а не пустышка)."""
    photo = getattr(entity, "photo", None)
    if photo is None:
        return False
    return type(photo).__name__ != "PhotoEmpty"


def topic_key(title: str) -> str | None:
    """«🐞 Баги» -> «Баги»: сравниваем темы без учёта эмодзи."""
    if not title:
        return None
    for topic in TOPICS:
        if topic["key"] in title:
            return topic["key"]
    return None


async def pinned_in_topic(client, group, topic_id: int):
    from telethon.tl.functions.messages import SearchRequest
    from telethon.tl.types import InputMessagesFilterPinned

    try:
        found = await client(SearchRequest(
            peer=peer(group), q="", filter=InputMessagesFilterPinned(),
            min_date=None, max_date=None, offset_id=0, add_offset=0,
            limit=1, max_id=0, min_id=0, hash=0, top_msg_id=topic_id))
        return bool(found.messages)
    except Exception:
        return None


async def pinned_in_chat(client, entity):
    from telethon.tl.functions.channels import GetFullChannelRequest

    try:
        result = await client(GetFullChannelRequest(input_channel(entity)))
        return bool(getattr(result.full_chat, "pinned_msg_id", None))
    except Exception:
        return None


async def resolve_saved(client, state: dict, id_key: str, hash_key: str,
                        title: str, megagroup: bool):
    """Находит канал/чат, созданный в прошлый раз.

    Просто get_entity(id) ненадёжен: без access_hash Telegram отвечает
    «could not find the input entity». Поэтому: access_hash из состояния →
    get_entity(id) → поиск среди своих диалогов по точному названию.
    """
    from telethon.tl.types import InputChannel

    entity_id = state.get(id_key)
    if not entity_id:
        return None

    access_hash = state.get(hash_key)
    if access_hash:
        candidate = InputChannel(entity_id, access_hash)
        try:
            found = await client.get_entity(candidate)
            if found is not None:
                state[hash_key] = getattr(found, "access_hash", access_hash)
                save_state(state)
                return found
        except Exception:
            pass

    try:
        found = await client.get_entity(entity_id)
        if found is not None:
            state[hash_key] = getattr(found, "access_hash", None)
            save_state(state)
            return found
    except Exception:
        pass

    try:
        async for dialog in client.iter_dialogs(limit=200):
            entity = dialog.entity
            if (getattr(entity, "title", None) == title
                    and bool(getattr(entity, "megagroup", False)) == megagroup):
                state[id_key] = entity.id
                state[hash_key] = getattr(entity, "access_hash", None)
                save_state(state)
                return entity
    except Exception:
        pass

    return None


async def create_entity(client, *, title: str, about: str, megagroup: bool):
    from telethon.tl.functions.channels import CreateChannelRequest

    result = await client(CreateChannelRequest(
        title=title, about=about, megagroup=megagroup, broadcast=not megagroup))
    for chat in result.chats:
        if getattr(chat, "megagroup", False) == megagroup:
            return chat
    return result.chats[0]


async def set_photo(client, entity, path: Path) -> None:
    from telethon.tl.functions.channels import EditPhotoRequest
    from telethon.tl.types import InputChatUploadedPhoto

    uploaded = await client.upload_file(str(path))
    await client(EditPhotoRequest(
        channel=entity, photo=InputChatUploadedPhoto(file=uploaded)))


class UsernameBlocked(Exception):
    """Telegram не даёт этому аккаунту ставить публичные @адреса вообще."""


def _is_public_username_block(error: Exception) -> bool:
    """Признак того, что дело не в конкретном username, а в аккаунте.

    «You're admin of too many public channels» прилетает на ЛЮБОЙ свободный
    username, поэтому перебирать шесть вариантов бессмысленно.
    """
    name = type(error).__name__
    return name in ("ChannelsAdminPublicTooMuchError",
                    "ChannelsAdminLocatedTooMuchError",
                    "ChannelsPublicTooMuchError")


async def try_username(client, entity, username: str) -> bool:
    """True — username занят/недопустим, False — свободен."""
    from telethon.tl.functions.channels import CheckUsernameRequest
    from telethon.errors import (UsernameInvalidError, UsernameOccupiedError,
                                 UsernamePurchaseAvailableError)

    try:
        await client(CheckUsernameRequest(entity, username))
        return False
    except UsernameOccupiedError:
        return True
    except UsernamePurchaseAvailableError:
        return True
    except UsernameInvalidError:
        return True
    except Exception as error:
        if _is_public_username_block(error):
            raise UsernameBlocked(str(error))
        raise


async def ensure_username(client, entity, candidates, *, label: str,
                          preferred: str | None, state: dict, key: str) -> str | None:
    from telethon.errors import (UsernameInvalidError, UsernameOccupiedError,
                                 UsernamePurchaseAvailableError)
    from telethon.tl.functions.channels import UpdateUsernameRequest

    if state.get(key):
        log(label, f"username уже стоит: @{state[key]}")
        return state[key]

    if state.get("public_username_blocked") and not state.get("_retry_usernames"):
        log(label, "публичный @адрес пропускаю: в прошлый раз Telegram не дал")
        return None

    preferred = (preferred or "").strip().lstrip("@")
    queue = []
    if preferred:
        queue.append(preferred)
    queue += [u for u in candidates if u not in queue]

    for username in queue:
        try:
            taken = await try_username(client, entity, username)
        except UsernameBlocked as error:
            warn(f"Telegram не даёт ставить публичный @адрес: {error}")
            warn("Похоже, вы админ слишком многих публичных каналов. "
                 "Освободите один (сделайте канал частным) или оставьте "
                 "канал/чат частными — ссылка-приглашение всё равно будет.")
            state["public_username_blocked"] = True
            save_state(state)
            return None
        if taken:
            warn(f"@{username} занят — пробую следующий")
            continue
        try:
            await client(UpdateUsernameRequest(channel=entity, username=username))
        except (UsernameOccupiedError, UsernameInvalidError,
                UsernamePurchaseAvailableError):
            warn(f"@{username} не подошёл — пробую следующий")
            continue
        except Exception as error:
            if _is_public_username_block(error):
                warn(f"Telegram не даёт ставить публичный @адрес: {error}")
                state["public_username_blocked"] = True
                save_state(state)
                return None
            raise
        state[key] = username
        save_state(state)
        log(label, f"username: @{username}")
        return username

    warn("свободного username не нашлось: канал/чат останутся без @адреса. "
         "Это не мешает работе, ссылку-приглашение скрипт всё равно выдаст.")
    return None


async def post_safe(client, entity, text: str, *, reply_to=None, parse_mode="html"):
    from telethon.errors import FloodWaitError

    for attempt in range(4):
        try:
            return await client.send_message(
                entity, text, reply_to=reply_to, parse_mode=parse_mode,
                link_preview=False)
        except FloodWaitError as error:
            wait = int(getattr(error, "seconds", 5)) + 2
            warn(f"Telegram просит подождать {wait} с (flood) — жду")
            await asyncio.sleep(wait)
    raise Stop("не удалось отправить сообщение: слишком много запросов")


async def pin_safe(client, entity, message) -> None:
    from telethon.errors import FloodWaitError

    try:
        await client.pin_message(entity, message, notify=False)
    except FloodWaitError as error:
        await asyncio.sleep(int(getattr(error, "seconds", 5)) + 2)
        await client.pin_message(entity, message, notify=False)


async def apply_plan(args) -> None:
    from telethon import TelegramClient
    from telethon.errors import ChannelsTooMuchError, FloodWaitError, RPCError
    from telethon.tl.functions.channels import (SetDiscussionGroupRequest,
                                                ToggleForumRequest)
    from telethon.tl.functions.messages import (EditChatDefaultBannedRightsRequest,
                                                ExportChatInviteRequest,
                                                GetForumTopicsRequest,
                                                UpdateDialogFilterRequest)
    from telethon.tl.types import (ChatBannedRights, DialogFilter,
                                   InputReplyToMessage, TextWithEntities)

    api_id, api_hash = read_api_credentials()
    state = load_state()

    client = TelegramClient(str(SESSION_PATH), api_id, api_hash)
    await client.start()  # спросит телефон, код и пароль 2FA

    me = await client.get_me()
    who = " ".join(x for x in [(me.first_name or "").strip(),
                               ("@" + me.username) if me.username else ""] if x)
    log("вход", f"вы вошли как {who or me.id}")
    save_state(state)

    # --- канал ------------------------------------------------------------
    channel = await resolve_saved(client, state, "channel_id",
                                  "channel_access_hash", CHANNEL["title"], False)
    if channel is not None:
        log("канал", f"уже создан: {CHANNEL['title']}")

    if channel is None:
        log("канал", "создаю")
        try:
            channel = await create_entity(
            client,
            title=CHANNEL["title"],
                about=CHANNEL["about"].replace(
                    "{group}", "@" + GROUP["usernames"][0])[:255],
                megagroup=False,
            )
        except ChannelsTooMuchError:
            raise Stop("Telegram не даёт этому аккаунту создать ещё один канал "
                       "сегодня (лимит на новые каналы и группы). "
                       "Попробуйте через сутки или с другого аккаунта.")
        except FloodWaitError as error:
            raise Stop(f"Telegram просит подождать {error.seconds} с перед "
                       f"созданием канала. Запустите позже — уже созданное "
                       f"подхватится автоматически.")
        state["channel_id"] = channel.id
        state["channel_access_hash"] = getattr(channel, "access_hash", None)
        save_state(state)
        await asyncio.sleep(STEP_PAUSE)

    if not has_photo(channel):
        photo = avatar_path(args.channel_avatar, required=False)
        if photo is None:
            warn("аватарка канала недоступна — канал останется без фото")
        else:
            log("канал", f"ставлю аватарку: {photo.name}")
            await set_photo(client, channel, photo)
            state["channel_photo"] = args.channel_avatar
            save_state(state)
            await asyncio.sleep(STEP_PAUSE)
            channel = await client.get_entity(peer(channel)) or channel
            if has_photo(channel):
                log("канал", "аватарка на месте")
            else:
                warn("аватарка канала не появилась — попробуйте позже или "
                     "поставьте вручную")

    channel_username = await ensure_username(
        client, channel, CHANNEL["usernames"], label="канал",
        preferred=args.channel_username, state=state, key="channel_username")
    await asyncio.sleep(STEP_PAUSE)

    # --- чат --------------------------------------------------------------
    group = await resolve_saved(client, state, "group_id",
                                "group_access_hash", GROUP["title"], True)
    if group is not None:
        log("чат", f"уже создан: {GROUP['title']}")

    if group is None:
        log("чат", "создаю супергруппу")
        try:
            group = await create_entity(
                client, title=GROUP["title"], about=GROUP["about"][:255],
                megagroup=True)
        except ChannelsTooMuchError:
            raise Stop("Telegram не даёт этому аккаунту создать ещё одну "
                       "группу сегодня. Попробуйте через сутки: канал уже "
                       "сохранён в состоянии и не будет создан заново.")
        state["group_id"] = group.id
        state["group_access_hash"] = getattr(group, "access_hash", None)
        save_state(state)
        await asyncio.sleep(STEP_PAUSE)

        log("чат", "включаю темы (форум)")
        try:
            await client(ToggleForumRequest(channel=group, enabled=True, tabs=False))
        except RPCError as error:
            warn(f"темы не включились автоматически: {error}. "
                 f"Включите вручную: управление чатом → «Темы».")
        await asyncio.sleep(STEP_PAUSE)

    if not has_photo(group):
        photo = avatar_path(args.group_avatar, required=False)
        if photo is None:
            warn("аватарка чата недоступна — чат останется без фото")
        else:
            log("чат", f"ставлю аватарку: {photo.name}")
            await set_photo(client, group, photo)
            state["group_photo"] = args.group_avatar
            save_state(state)
            await asyncio.sleep(STEP_PAUSE)
            group = await client.get_entity(peer(group)) or group
            if has_photo(group):
                log("чат", "аватарка на месте")
            else:
                warn("аватарка чата не появилась — попробуйте позже или "
                     "поставьте вручную")

    group_username = await ensure_username(
        client, group, GROUP["usernames"], label="чат",
        preferred=args.group_username, state=state, key="group_username")
    await asyncio.sleep(STEP_PAUSE)

    usernames = {"channel": channel_username, "group": group_username}

    # права по умолчанию: добавлять участников только админам
    if GROUP["invite_admins_only"] and not state.get("rights_done"):
        try:
            await client(EditChatDefaultBannedRightsRequest(
                peer=peer(group),
                banned_rights=ChatBannedRights(until_date=None, invite_users=True)))
            state["rights_done"] = True
            save_state(state)
            log("чат", "добавлять участников могут только админы")
        except RPCError as error:
            warn(f"права не применились: {error}")
        await asyncio.sleep(STEP_PAUSE)

    # --- связка канал ↔ чат ----------------------------------------------
    if not state.get("linked"):
        log("связка", "комментарии к постам канала → в чат")
        try:
            await client(SetDiscussionGroupRequest(broadcast=channel, group=group))
            state["linked"] = True
            save_state(state)
        except RPCError as error:
            warn(f"не получилось связать автоматически: {error}. "
                 f"Вручную: канал → управление → «Обсуждение» → выбрать чат.")
        await asyncio.sleep(STEP_PAUSE)

    # --- посты канала -----------------------------------------------------
    published = state.setdefault("posts", {})
    for post in CHANNEL_POSTS:
        if post["key"] in published:
            continue
        text = format_text(post["text"], usernames)
        message = await post_safe(client, channel, text)
        published[post["key"]] = message.id
        save_state(state)
        log("пост", f"{post['text'].splitlines()[0]}")
        await asyncio.sleep(STEP_PAUSE)

    # закреп канала проверяем по факту: после падений он мог не встать
    channel_pinned = await pinned_in_chat(client, channel)
    if channel_pinned is True:
        log("канал", "закреп на месте")
    elif channel_pinned is None and state.get("channel_pin_done"):
        log("канал", "закреп уже ставили (проверить сейчас не вышло)")
    else:
        pinned_key = next((p["key"] for p in CHANNEL_POSTS if p["pinned"]), None)
        message_id = published.get(pinned_key) if pinned_key else None
        try:
            if message_id:
                await client.pin_message(channel, message_id, notify=False)
                state["channel_pin_done"] = True
                save_state(state)
            else:
                raise RuntimeError("нет id поста")
            log("канал", "закреплён первый пост")
        except Exception as error:
            warn(f"закрепить пост в канале не удалось: {error}")
        await asyncio.sleep(STEP_PAUSE)

    # --- закреп в общей теме чата ----------------------------------------
    general_pinned = await pinned_in_chat(client, group)
    if general_pinned is True:
        log("чат", "закреп в общей теме на месте")
    elif general_pinned is None and state.get("general_pin_done"):
        log("чат", "закреп уже ставили (проверить сейчас не вышло)")
    else:
        message = await post_safe(client, group, format_text(GROUP_PINNED, usernames))
        await pin_safe(client, group, message)
        state["group_pinned"] = message.id
        state["general_pin_done"] = True
        save_state(state)
        log("чат", "правила закреплены в общей теме")
        await asyncio.sleep(STEP_PAUSE)

    # --- темы -------------------------------------------------------------
    # Смотрим, что реально есть в чате: темы могли создать раньше без эмодзи.
    from telethon.tl.functions.messages import CreateForumTopicRequest, EditForumTopicRequest

    topics_state = state.setdefault("topics", {})
    existing = {}
    try:
        result = await client(GetForumTopicsRequest(
            peer=peer(group), offset_date=None, offset_id=0,
            offset_topic=0, limit=100))
        for item in result.topics:
            key = topic_key(getattr(item, "title", "") or "")
            if key and key not in existing:
                existing[key] = item
    except RPCError as error:
        warn(f"список тем не получен: {error}")

    for topic in TOPICS:
        key = topic["key"]
        item = existing.get(key)
        if item is None:
            log("темы", f"создаю «{topic['title']}»")
            try:
                await client(CreateForumTopicRequest(
                    peer=peer(group),
                    title=topic["title"],
                    icon_color=topic["color"],
                    random_id=random.getrandbits(63),
                ))
            except RPCError as error:
                warn(f"тема «{topic['title']}» не создалась: {error}")
            await asyncio.sleep(STEP_PAUSE)
            continue

        topics_state[key] = item.id
        if getattr(item, "title", "") != topic["title"]:
            try:
                await client(EditForumTopicRequest(
                    peer=peer(group), topic_id=item.id, title=topic["title"]))
                log("темы", f"переименовал в «{topic['title']}»")
            except RPCError as error:
                warn(f"тему «{key}» не переименовать: {error}")
            await asyncio.sleep(STEP_PAUSE)
        else:
            log("темы", f"«{topic['title']}» уже есть")
    save_state(state)

    # после создания/переименования перечитываем id тем
    try:
        result = await client(GetForumTopicsRequest(
            peer=peer(group), offset_date=None, offset_id=0,
            offset_topic=0, limit=100))
        for item in result.topics:
            key = topic_key(getattr(item, "title", "") or "")
            if key:
                topics_state[key] = item.id
        save_state(state)
    except RPCError:
        pass

    # --- закрепы в темах ---------------------------------------------------
    pinned_state = state.setdefault("topic_pinned", {})
    for topic in TOPICS:
        key = topic["key"]
        topic_id = topics_state.get(key)
        if not topic_id:
            continue
        topic_pinned = await pinned_in_topic(client, group, topic_id)
        if topic_pinned is True or (topic_pinned is None and key in pinned_state):
            log("закреп", f"тема «{topic['title']}» уже с закрепом")
            continue
        message = await post_safe(
            client, group, topic["pinned"],
            reply_to=InputReplyToMessage(reply_to_msg_id=topic_id))
        await pin_safe(client, group, message)
        pinned_state[key] = message.id
        save_state(state)
        log("закреп", f"тема «{topic['title']}»")
        await asyncio.sleep(STEP_PAUSE)

    # --- папка в клиенте ---------------------------------------------------
    # Ищем свою папку по названию: чужие папки не трогаем.
    from telethon.tl.functions.messages import GetDialogFiltersRequest

    folder_id = state.get("folder_id")
    try:
        filters = await client(GetDialogFiltersRequest())
        for item in getattr(filters, "filters", []) or []:
            if isinstance(item, DialogFilter) and _filter_title(item) == FOLDER["title"]:
                folder_id = item.id
                state["folder_id"] = folder_id
                save_state(state)
                break
    except RPCError as error:
        warn(f"список папок не получен ({error})")

    if folder_id is None:
        used = set()
        try:
            for item in getattr(await client(GetDialogFiltersRequest()), "filters", []) or []:
                if isinstance(item, DialogFilter):
                    used.add(item.id)
        except RPCError:
            pass
        folder_id = next((i for i in range(2, 20) if i not in used), 2)
        state["folder_id"] = folder_id
        save_state(state)

    def build_filter(with_emoticon: bool):
        return DialogFilter(
            id=folder_id,
            title=TextWithEntities(FOLDER["title"], []),
            pinned_peers=[],
            include_peers=[peer(channel), peer(group)],
            exclude_peers=[],
            emoticon=FOLDER["emoticon"] if with_emoticon else None,
        )

    try:
        try:
            await client(UpdateDialogFilterRequest(
                id=folder_id, filter=build_filter(True)))
        except RPCError as error:
            # эмодзи на папке — возможность Premium; без него папка всё равно нужна
            warn(f"эмодзи на папке не поставился ({error}) — создаю без него")
            await client(UpdateDialogFilterRequest(
                id=folder_id, filter=build_filter(False)))
            state["folder_no_emoji"] = True
        state["folder_done"] = True
        save_state(state)
        log("папка", f"«{FOLDER['title']}» готова (канал + чат)")
    except RPCError as error:
        warn(f"папка не создалась: {error}")
    await asyncio.sleep(STEP_PAUSE)

    # --- ссылки -----------------------------------------------------------
    links = {}
    for name, entity in (("channel", channel), ("group", group)):
        try:
            invite = await client(ExportChatInviteRequest(peer=peer(entity)))
            links[name] = invite.link
        except RPCError as error:
            warn(f"invite-ссылка для {name} не получена: {error}")
        await asyncio.sleep(STEP_PAUSE)

    if channel_username:
        links["channel_public"] = f"https://t.me/{channel_username}"
    if group_username:
        links["group_public"] = f"https://t.me/{group_username}"

    state["links"] = links
    save_state(state)

    print()
    print("=" * 72)
    print("  ГОТОВО")
    print("=" * 72)
    print(f"  канал:  {getattr(channel, 'title', CHANNEL['title'])}"
          + (f"  @{channel_username}" if channel_username else ""))
    print(f"  чат:    {getattr(group, 'title', GROUP['title'])}"
          + (f"  @{group_username}" if group_username else ""))
    for label, link in links.items():
        print(f"  {label:<15} {link}")
    if not channel_username or not group_username:
        print()
        print("  Канал и чат остались частными: публичный @адрес поставить не "
              "удалось.")
        print("  Это не мешает работе — раздавайте invite-ссылку выше.")
        print("  Когда освободите публичный канал, повторите:")
        print("     python3 pengram-community.py --apply --retry-usernames")
    print()
    missing = []
    if not has_photo(channel):
        missing.append("аватарка канала")
    if not has_photo(group):
        missing.append("аватарка чата")
    if not channel_username:
        missing.append("публичный @адрес канала")
    if not group_username:
        missing.append("публичный @адрес чата")
    if missing:
        print("  Не встало: " + ", ".join(missing))
        print("  Повторить: python3 pengram-community.py --repair")
    print("  Дальше: зайдите в чат и проверьте темы, затем раздайте invite-ссылку.")
    print(f"  Состояние: {STATE_PATH}")
    print()

    await client.disconnect()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Создаёт канал и чат-форум Pengram в вашем аккаунте.")
    parser.add_argument("--apply", action="store_true",
                        help="реально создать (без флага — только показать план)")
    parser.add_argument("--avatar-list", action="store_true",
                        help="показать доступные аватарки и выйти")
    parser.add_argument("--channel-avatar", type=int, default=1,
                        help="номер аватарки канала (по умолчанию 1)")
    parser.add_argument("--group-avatar", type=int, default=3,
                        help="номер аватарки чата (по умолчанию 3)")
    parser.add_argument("--channel-username", default=None,
                        help="свой username для канала (без @)")
    parser.add_argument("--group-username", default=None,
                        help="свой username для чата (без @)")
    parser.add_argument("--show-texts", action="store_true",
                        help="в просмотре показать все тексты целиком")
    parser.add_argument("--repair", action="store_true",
                        help="пройтись по уже созданному и доставить всё, "
                             "что не встало: аватарки, темы, закрепы, папку")
    parser.add_argument("--retry-usernames", action="store_true",
                        help="снова попробовать поставить публичный @адрес "
                             "(если в прошлый раз Telegram не дал)")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    try:
        if args.avatar_list:
            print_avatars()
            return 0

        name_for(args.channel_avatar)
        name_for(args.group_avatar)

        if args.repair:
            args.apply = True

        if args.retry_usernames:
            state = load_state()
            state.pop("public_username_blocked", None)
            for key in ("channel_username", "group_username"):
                state.pop(key, None)
            save_state(state)

        if not args.apply:
            print_plan(args)
            return 0

        asyncio.run(apply_plan(args))
        return 0
    except Stop as error:
        print(f"\nОстановился: {error}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("\nПрервано пользователем. Уже созданное сохранено в состоянии.",
              file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
