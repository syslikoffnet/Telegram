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

BUILD_TAG = "community 2026-10-21.9"

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

# Группа для комментариев к постам канала.
# Telegram не разрешает привязать к каналу форум (ошибка FORUM_ENABLED),
# поэтому комментарии живут в отдельной обычной супергруппе.
COMMENTS = {
    "title": "Pengram · Обсуждение",
    "about": "Комментарии к постам канала Pengram. Общий чат — @pengram_chat.",
    "pinned": (
        "Здесь появляются комментарии к постам канала.\n\n"
        "Нажмите «Комментировать» под любым постом — сообщение придёт сюда.\n"
        "Обычное общение, баги и идеи — в основном чате: {group}"
    ),
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
            "Общение и поддержка — в чате: {group}\n"
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
            "Всё бесплатно, без подписки. Баги и пожелания — в чате: {group}"
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
            "хватает: сейчас самое время ломать и предлагать. Чат: {group}"
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
        "title": "Баги",
        "emoji": "🐞",
        "icons": ("🐞", "🪲", "⚠️", "❗️", "🚨"),
        "color": 0xFB6F5F,
        "pinned": (
            "Тема для поломок. Одно сообщение — одна проблема.\n\n"
            "Шаблон, по которому починю быстрее всего:\n\n"
            "1. Версия: «Настройки → Pengram → О программе» (например, beta 9).\n"
            "2. Что делал: «открыл чат → нажал на стикер → выбрал „без звука“».\n"
            "3. Что случилось: «вылет», «белый экран», «кнопка не нажимается».\n"
            "4. Чего ждал: как должно было быть.\n"
            "5. Как часто: всегда или через раз.\n\n"
            "И приложите отчёт: «Настройки → Pengram → О программе → "
            "Скопировать всё». Скриншот или видео — по возможности.\n\n"
            "Если баг уже описали — плюсните сообщение, а не заводите новое."
        ),
    },
    {
        "key": "Идеи",
        "title": "Идеи",
        "emoji": "💡",
        "icons": ("💡", "✨", "⭐️", "🌟", "🔥"),
        "color": 0xFFD67E,
        "pinned": (
            "Чего не хватает — сюда. Хорошая идея выглядит так:\n\n"
            "1. Что хочу: «своя папка в настройках уведомлений».\n"
            "2. Зачем: какую задачу это решает.\n"
            "3. Как представляю: где кнопка, что открывается, как выглядит.\n"
            "4. Насколько важно: «мешает каждый день» или «приятный бонус».\n\n"
            "Одна идея — одно сообщение: так её удобно обсуждать и отмечать.\n"
            "Чем конкретнее описание, тем больше шансов, что появится в релизе."
        ),
    },
    {
        "key": "Помощь",
        "title": "Помощь",
        "emoji": "🛠",
        "icons": ("🛠", "❓", "💬", "🧭", "🔧"),
        "color": 0x6FB9F0,
        "pinned": (
            "Установка, вход по номеру, tdata, .session, перенос с другого "
            "клиента — спрашивайте здесь, поможем.\n\n"
            "Чтобы не гадать, напишите сразу:\n\n"
            "1. Что ставите: APK из канала или собрали сами.\n"
            "2. На чём: телефон, версия Android.\n"
            "3. На каком шаге остановились и что видно на экране.\n"
            "4. Отчёт: «Настройки → Pengram → О программе → Скопировать всё».\n\n"
            "Пароли и коды из Telegram не присылайте никому — даже админам."
        ),
    },
    {
        "key": "Скриншоты",
        "title": "Скриншоты",
        "emoji": "📸",
        "icons": ("📸", "🖼", "📷", "🎨", "🌅"),
        "color": 0x8EEE98,
        "pinned": (
            "Покажите, как выглядит Pengram у вас: тема, стекло, шрифт, "
            "плитки, пингвин.\n\n"
            "Оформление отсюда часто доходит до релиза — это лучший способ "
            "повлиять на внешний вид без кода.\n\n"
            "Пара просьб: не светите чужие переписки и номера телефонов, "
            "и подпишите, какая тема включена."
        ),
    },
    {
        "key": "Релизы",
        "title": "Релизы",
        "emoji": "📦",
        "icons": ("📦", "🚀", "⬇️", "📥", "🎁"),
        "color": 0xCB86DB,
        "pinned": (
            "Обсуждение версий: что зашло, что сломалось, что откатить.\n\n"
            "Пишите так:\n\n"
            "1. Версия из «Настройки → Pengram → О программе».\n"
            "2. Что понравилось / что не так.\n"
            "3. Ставили поверх старой или с нуля.\n\n"
            "Сами APK — в канале, файлом к посту о релизе. Здесь только "
            "разговоры: так легче найти нужное.\n\n"
            "Нашли баг — не сюда, а в «Баги»: там шаблон и его точно увидят."
        ),
    },
    {
        "key": "Оффтоп",
        "title": "Оффтоп",
        "emoji": "🌿",
        "icons": ("🌿", "🍃", "🎲", "☕️", "🎈"),
        "color": 0xFF93B2,
        "pinned": (
            "Всё, что не про Pengram: музыка, техника, жизнь.\n\n"
            "Без политики, спама, рекламы и чужих APK.\n"
            "Уважайте друг друга — и здесь тоже действуют правила чата."
        ),
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
        if len(payload) < 10_000 or not payload.startswith(b"\xff\xd8\xff"):
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


def looks_like_jpeg(path: Path) -> bool:
    """Файл действительно картинка JPEG, а не обрезанный кусок или страница.

    Telegram отвечает Forbidden/PhotoExtInvalid на битый файл, поэтому
    проверяем до загрузки: начало FF D8 FF, конец FF D9 и разумный размер.
    """
    try:
        if path.stat().st_size < 10_000:
            return False
        with path.open("rb") as handle:
            head = handle.read(3)
            handle.seek(-2, os.SEEK_END)
            tail = handle.read(2)
        return head == b"\xff\xd8\xff" and tail == b"\xff\xd9"
    except Exception:
        return False


def avatar_path(number: int, *, required: bool = True) -> Path | None:
    """Локальный файл, иначе — скачанный, иначе None (если not required)."""
    name = name_for(number)
    path = AVATAR_DIR / f"{name}.jpg"
    if path.exists():
        if looks_like_jpeg(path):
            return path
        warn(f"файл аватарки {path.name} повреждён — скачаю заново")
        try:
            path.unlink()
        except Exception:
            pass
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
    """Пишет состояние через временный файл.

    Так обрыв питания или убитый Termux не оставит половину JSON: старый
    файл останется целым, а новый появится только целиком.
    """
    data = json.dumps(state, ensure_ascii=False, indent=2)
    try:
        tmp = STATE_PATH.with_suffix(STATE_PATH.suffix + ".tmp")
        tmp.write_text(data, encoding="utf-8")
        os.replace(tmp, STATE_PATH)
    except Exception as error:
        warn(f"состояние не сохранилось ({error}) — продолжаю без него")


def format_text(template: str, usernames: dict) -> str:
    """Подставляет @channel / @group, а если username нет — просто название.

    Именно replace, а не str.format: в текстах могут встретиться фигурные
    скобки, и .format на них падает.
    """
    channel = usernames.get("channel")
    group = usernames.get("group")
    text = (template
            .replace("{channel}", f"@{channel}" if channel else f"«{CHANNEL['title']}»")
            .replace("{group}", f"@{group}" if group else f"«{GROUP['title']}»"))
    return text.replace("@@", "@")


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
    print(f"  {BUILD_TAG}")
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
    print("  темы (значок + имя):")
    for topic in TOPICS:
        print(f"      {topic['emoji']}  {topic['title']}")
    print(f"  добавлять участников: {'только админы' if GROUP['invite_admins_only'] else 'все'}")

    print("\nАВТОУДАЛЕНИЕ")
    print("  выключено в канале, чате и группе комментариев")

    print("\nПОСТЫ КАНАЛА")
    for post in CHANNEL_POSTS:
        pin = " + закреп" if post["pinned"] else ""
        first = post["text"].split("\n")[0]
        print(f"  • {first} ({len(post['text'])} символов){pin}")

    print("\nКОММЕНТАРИИ К ПОСТАМ")
    print(f"  группа «{COMMENTS['title']}» (форум к каналу привязать нельзя)")
    print(f"  аватарка:  номер {getattr(args, 'comments_avatar', 2)}")

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
    print("  • канал и чат связываются, комментарии идут в отдельную группу")
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
    """У сущности реально есть аватарка.

    У каналов и групп «пустая» аватарка — это ChatPhotoEmpty, у пользователей
    PhotoEmpty. Раньше проверялся только второй вариант, поэтому канал с
    непоставленной аватаркой считался готовым и фото больше не пытались ставить.
    """
    photo = getattr(entity, "photo", None)
    if photo is None:
        return False
    if type(photo).__name__ in ("PhotoEmpty", "ChatPhotoEmpty"):
        return False
    return bool(getattr(photo, "id", 0) or getattr(photo, "photo_id", 0)
                or getattr(photo, "file_reference", None))


def topic_key(title: str) -> str | None:
    """«🐞 Баги» -> «Баги»: сравниваем темы без учёта эмодзи."""
    if not title:
        return None
    for topic in TOPICS:
        if topic["key"] in title:
            return topic["key"]
    return None


def normalize_emoji(text: str) -> str:
    """Убирает селекторы начертания: «🛠️» и «🛠» — один и тот же значок."""
    return (text or "").replace("\ufe0f", "").replace("\ufe0e", "").strip()


async def fetch_topic_icons(client) -> dict:
    """Значки, которые Telegram разрешает темам: эмодзи → id документа.

    Свои картинки темам ставить нельзя, а вот значок из набора
    inputStickerSetEmojiDefaultTopicIcons — можно, и не нужно Premium.
    Хардкодить id нельзя: набор меняется, поэтому тянем его при запуске.
    """
    try:
        from telethon.tl.functions.messages import GetStickerSetRequest
        from telethon.tl.types import InputStickerSetEmojiDefaultTopicIcons
    except Exception:
        return {}

    try:
        result = await client(GetStickerSetRequest(
            stickerset=InputStickerSetEmojiDefaultTopicIcons(), hash=0))
    except Exception as error:
        warn(f"набор значков для тем не получен ({error}) — оставлю эмодзи "
             f"в названии темы")
        return {}

    icons = {}
    for document in getattr(result, "documents", []) or []:
        for attribute in getattr(document, "attributes", []) or []:
            alt = getattr(attribute, "alt", None)
            if alt and getattr(document, "id", None):
                icons.setdefault(normalize_emoji(alt), document.id)
    if not icons:
        warn("набор значков для тем пуст — оставлю эмодзи в названии темы")
    return icons


def pick_icon(icons: dict, candidates) -> int | None:
    for emoji in candidates:
        found = icons.get(normalize_emoji(emoji))
        if found:
            return found
    return None


def topic_title(topic: dict, icon_id) -> str:
    """С значком — чистое имя, без него — эмодзи в названии.

    Так тема всегда узнаваема: либо красивая иконка, либо эмодзи рядом
    с именем, если набор значков недоступен.
    """
    if icon_id:
        return topic["title"]
    return f"{topic.get('emoji', '')} {topic['title']}".strip()


async def set_ttl_off(client, entity) -> bool | None:
    """Выключает автоудаление сообщений в чате. True/False — получилось ли."""
    from telethon.tl.functions.messages import SetHistoryTTLRequest

    try:
        await client(SetHistoryTTLRequest(peer=peer(entity), period=0))
        return True
    except Exception as error:
        warn(f"автоудаление не выключить ({error})")
        return None


async def chat_ttl(client, entity):
    """Текущий таймер автоудаления в секундах. None — не проверить."""
    from telethon.tl.functions.channels import GetFullChannelRequest

    try:
        result = await client(GetFullChannelRequest(input_channel(entity)))
        return getattr(result.full_chat, "ttl_period", 0) or 0
    except Exception:
        return None


def is_file_message(message) -> bool:
    """Сообщение с файлом или фото (а не просто текст с превью ссылки)."""
    media = getattr(message, "media", None)
    if media is None:
        return False
    return type(media).__name__ not in ("MessageMediaWebPage",
                                        "MessageMediaEmpty", "MessageMediaGame")


async def delete_all_messages(client, entity, keep_ids=()) -> int:
    """Удаляет текстовые сообщения, кроме перечисленных id.

    Нужно для --reset: снести старые тексты и разложить заново.
    Корни тем не трогаем — иначе Telegram удалит саму тему.
    Сообщения с файлами и фото остаются: в канале так живут APK и картинки,
    и терять их при пересборке текстов нельзя.
    """
    keep = {int(i) for i in keep_ids if i}
    removed = 0
    batch = []
    try:
        async for message in client.iter_messages(entity, limit=None):
            if message.id in keep or is_file_message(message):
                continue
            batch.append(message.id)
            if len(batch) >= 100:
                await client.delete_messages(entity, batch)
                removed += len(batch)
                batch = []
                await asyncio.sleep(STEP_PAUSE)
        if batch:
            await client.delete_messages(entity, batch)
            removed += len(batch)
    except Exception as error:
        warn(f"удалилось не всё: {error}")
        problem("очистка", error)
    return removed


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


async def message_exists(client, entity, message_id) -> bool | None:
    """Есть ли такое сообщение. None — проверить не удалось."""
    try:
        found = await client.get_messages(entity, ids=int(message_id))
        if found is None:
            return False
        if isinstance(found, list):
            return any(item is not None for item in found)
        return True
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
    Поиск по названию спасает и тогда, когда файл состояния потерялся —
    иначе скрипт создал бы второй канал.
    """
    from telethon.tl.types import InputChannel

    entity_id = state.get(id_key)

    access_hash = state.get(hash_key) if entity_id else None
    if entity_id and access_hash:
        candidate = InputChannel(entity_id, access_hash)
        try:
            found = await client.get_entity(candidate)
            if found is not None:
                state[hash_key] = getattr(found, "access_hash", access_hash)
                save_state(state)
                return found
        except Exception:
            pass

    if entity_id:
        try:
            found = await client.get_entity(entity_id)
            if found is not None:
                state[hash_key] = getattr(found, "access_hash", None)
                save_state(state)
                return found
        except Exception:
            pass

    try:
        candidates = []
        async for dialog in client.iter_dialogs(limit=200):
            entity = dialog.entity
            if (getattr(entity, "title", None) == title
                    and bool(getattr(entity, "megagroup", False)) == megagroup):
                candidates.append(entity)
        if candidates:
            # Только свои: в чужом канале с тем же названием мы не сможем
            # публиковать и ставить фото (Telegram ответит Forbidden).
            mine = [e for e in candidates
                    if getattr(e, "creator", False)
                    or getattr(e, "admin_rights", None) is not None]
            if not mine:
                warn(f"нашёл «{title}», но вы там не создатель и не админ — "
                     f"не трогаю его, создам свой")
                return None
            def rank(entity):
                return (bool(getattr(entity, "creator", False)),
                        getattr(entity, "admin_rights", None) is not None)
            best = max(mine, key=rank)
            state[id_key] = best.id
            state[hash_key] = getattr(best, "access_hash", None)
            save_state(state)
            return best
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


def message_id_of(result) -> int | None:
    """id отправленного сообщения из ответа Telegram.

    client(SendMessageRequest) возвращает Updates, а не Message: передать такой
    объект в pin_message нельзя — Telethon ждёт номер и падает с TypeError.
    """
    for update in getattr(result, "updates", None) or []:
        message = getattr(update, "message", None)
        if message is not None and getattr(message, "id", None):
            return message.id
    for update in getattr(result, "updates", None) or []:
        if type(update).__name__ == "UpdateMessageID":
            return getattr(update, "id", None)
    update = getattr(result, "update", None)
    message = getattr(update, "message", None)
    if message is not None and getattr(message, "id", None):
        return message.id
    return None


async def post_safe(client, entity, text: str, *, topic_id=None):
    """Публикует текст и возвращает id сообщения (или None).

    topic_id — сообщение уходит внутрь темы форума. Через client.send_message
    этого не сделать: он не умеет ставить top_msg_id, без которого форум
    отправляет сообщение в общую тему.
    """
    from telethon.errors import FloodWaitError
    from telethon.tl.functions.messages import SendMessageRequest
    from telethon.tl.types import InputReplyToMessage

    for attempt in range(4):
        try:
            reply_to = None
            if topic_id:
                reply_to = InputReplyToMessage(
                    reply_to_msg_id=topic_id, top_msg_id=topic_id)
            result = await client(SendMessageRequest(
                peer=peer(entity),
                message=text,
                random_id=random.getrandbits(63),
                no_webpage=True,
                reply_to=reply_to,
            ))
            return message_id_of(result)
        except FloodWaitError as error:
            wait = int(getattr(error, "seconds", 5)) + 2
            warn(f"Telegram просит подождать {wait} с (flood) — жду")
            await asyncio.sleep(wait)
    raise RuntimeError("не удалось отправить сообщение: слишком много запросов")


async def pin_safe(client, entity, message_id) -> bool:
    """Закрепляет сообщение по номеру. Ошибки не роняют запуск."""
    from telethon.errors import FloodWaitError, RPCError

    if not message_id:
        warn("не знаю id сообщения — закреп пропущен")
        return False

    for attempt in range(2):
        try:
            await client.pin_message(entity, int(message_id), notify=False)
            return True
        except FloodWaitError as error:
            if attempt:
                break
            await asyncio.sleep(int(getattr(error, "seconds", 5)) + 2)
        except RPCError as error:
            warn(f"закрепить не удалось: {error}")
            return False
        except Exception as error:
            warn(f"закрепить не удалось: {error}")
            return False
    warn("закрепить не удалось: Telegram просит подождать")
    return False


problems: list[tuple[str, str]] = []


def problem(where: str, error) -> None:
    """Запоминает, что именно не встало, чтобы показать в конце списком."""
    problems.append((where, str(error)))


def photo_error_hint(error) -> str:
    """Подсказка, если Telegram не принял фото."""
    text = str(error)
    low = text.lower()
    if "forbidden" in low or "not enough rights" in low:
        return (" — Telegram не дал этому аккаунту менять фото. Поставьте "
                "вручную в приложении или повторите позже.")
    if "photoextinvalid" in low or "photo_invalid" in low or "file_parts_invalid" in low:
        return (" — Telegram не принял файл. Попробуйте другую аватарку: "
                "--channel-avatar 2 (доступно 1–10).")
    return ""


async def start_client(client) -> None:
    """Вход с понятными сообщениями вместо трейсбеков Telethon."""
    from telethon.errors import (ApiIdInvalidError, AuthKeyDuplicatedError,
                                 AuthKeyUnregisteredError, PhoneNumberBannedError,
                                 SessionRevokedError)

    try:
        await client.start()  # спросит телефон, код и пароль 2FA
    except ApiIdInvalidError:
        raise Stop("APP_ID/API_HASH не подходят. Возьмите свои на "
                   "my.telegram.org → API development tools и впишите их в "
                   "api.properties рядом со скриптом (APP_ID=…, APP_HASH=…).")
    except AuthKeyDuplicatedError:
        raise Stop("этот файл сессии уже использовали на другом устройстве, "
                   "Telegram его отключил. Удалите " + str(SESSION_PATH) +
                   " и войдите заново.")
    except (AuthKeyUnregisteredError, SessionRevokedError):
        raise Stop("сессия больше не действует (аккаунт вышел из неё или "
                   "сбросил устройства). Удалите " + str(SESSION_PATH) +
                   " и войдите заново.")
    except PhoneNumberBannedError:
        raise Stop("этот номер заблокирован в Telegram — войти нельзя.")


async def show_status() -> None:
    """Показывает, что реально стоит в Telegram прямо сейчас.

    Ничего не создаёт и не меняет — только читает. Полезно, когда кажется,
    что аватарка или закреп «не встали»: видно, где именно пусто.
    """
    print(f"[сборка] {BUILD_TAG}", flush=True)

    if not SESSION_PATH.exists():
        print(f"Ещё не было входа: файла {SESSION_PATH} нет. "
              f"Запустите --apply один раз.", file=sys.stderr)
        return

    from telethon import TelegramClient

    api_id, api_hash = read_api_credentials()
    state = load_state()
    client = TelegramClient(str(SESSION_PATH), api_id, api_hash)
    await start_client(client)
    try:
        me = await client.get_me()
        print(f"  вход:   {getattr(me, 'first_name', '') or ''}"
              f" @{getattr(me, 'username', '') or '—'}")

        channel = await resolve_saved(client, state, "channel_id",
                                      "channel_access_hash", CHANNEL["title"], False)
        group = await resolve_saved(client, state, "group_id",
                                    "group_access_hash", GROUP["title"], True)
        comments = await resolve_saved(client, state, "comments_id",
                                       "comments_access_hash", COMMENTS["title"], True)

        def line(label, entity):
            if entity is None:
                print(f"  {label}: нет")
                return
            photo = "есть" if has_photo(entity) else "НЕТ"
            user = getattr(entity, "username", None)
            print(f"  {label}: {getattr(entity, 'title', '?')}"
                  f"{' @' + user if user else ''} · аватарка: {photo}")

        line("канал", channel)
        line("чат", group)
        line("комменты", comments)

        for label, entity in (("канал", channel), ("чат", group),
                              ("комменты", comments)):
            if entity is None:
                continue
            ttl = await chat_ttl(client, entity)
            if ttl is None:
                print(f"  автоудаление в {label}е: не проверить")
            else:
                print(f"  автоудаление в {label}е: "
                      f"{'ВЫКЛЮЧЕНО' if ttl == 0 else f'включено ({ttl} с)'}")

        if channel is not None:
            pinned = await pinned_in_chat(client, channel)
            print(f"  закреп канала: {'да' if pinned else 'НЕТ' if pinned is False else 'не проверить'}")

        if group is not None:
            print("  темы:")
            try:
                from telethon.tl.functions.messages import GetForumTopicsRequest
                found = await client(GetForumTopicsRequest(
                    peer=peer(group), offset_date=None, offset_id=0,
                    offset_topic=0, limit=100))
                by_key = {}
                for item in found.topics:
                    key = topic_key(getattr(item, "title", "") or "")
                    if key:
                        by_key[key] = item
                for topic in TOPICS:
                    item = by_key.get(topic["key"])
                    if item is None:
                        print(f"    • {topic['emoji']} {topic['title']}: НЕТ")
                        continue
                    pinned = await pinned_in_topic(client, group, item.id)
                    mark = ("закреп есть" if pinned else "закрепа нет"
                            if pinned is False else "закреп не проверить")
                    icon = getattr(item, "icon_emoji_id", None)
                    icon_mark = "значок есть" if icon else "значка нет"
                    print(f"    • {getattr(item, 'title', '?')} · {icon_mark} · {mark}")
            except Exception as error:
                print(f"    список тем не получен: {error}")

            pinned = await pinned_in_chat(client, group)
            print(f"  закреп общей темы: {'да' if pinned else 'НЕТ' if pinned is False else 'не проверить'}")

        try:
            from telethon.tl.functions.messages import GetDialogFiltersRequest
            from telethon.tl.types import DialogFilter
            filters = await client(GetDialogFiltersRequest())
            mine = [f for f in getattr(filters, "filters", []) or []
                    if isinstance(f, DialogFilter) and _filter_title(f) == FOLDER["title"]]
            if mine:
                emoticon = getattr(mine[0], "emoticon", None) or "—"
                print(f"  папка: «{FOLDER['title']}» есть · эмодзи: {emoticon}")
            else:
                print(f"  папка: «{FOLDER['title']}» НЕТ")
        except Exception as error:
            print(f"  папка: не проверить ({error})")

        links = state.get("links") or {}
        if links:
            print("  ссылки:")
            for label, link in links.items():
                print(f"    {label:<14} {link}")
        print(f"  состояние: {STATE_PATH}")
    finally:
        await client.disconnect()


async def apply_plan(args) -> None:
    problems.clear()
    from telethon import TelegramClient
    from telethon.errors import ChannelsTooMuchError, FloodWaitError, RPCError
    from telethon.tl.functions.channels import (SetDiscussionGroupRequest,
                                                ToggleForumRequest)
    from telethon.tl.functions.messages import (EditChatDefaultBannedRightsRequest,
                                                ExportChatInviteRequest,
                                                GetForumTopicsRequest,
                                                UpdateDialogFilterRequest)
    from telethon.tl.types import ChatBannedRights, DialogFilter, TextWithEntities

    print(f"[сборка] {BUILD_TAG}", flush=True)
    api_id, api_hash = read_api_credentials()
    state = load_state()

    client = TelegramClient(str(SESSION_PATH), api_id, api_hash)
    await start_client(client)  # спросит телефон, код и пароль 2FA

    me = await client.get_me()
    if me is None:
        raise Stop("Telegram не подтвердил вход. Удалите файл сессии и "
                   "запустите заново: " + str(SESSION_PATH))
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
            try:
                await set_photo(client, channel, photo)
            except Exception as error:
                warn(f"аватарку канала поставить не вышло: {error}{photo_error_hint(error)}"); problem("аватарка канала", error)
            state["channel_photo"] = args.channel_avatar
            save_state(state)
            await asyncio.sleep(STEP_PAUSE)
            try:
                channel = await client.get_entity(peer(channel)) or channel
            except Exception:
                pass
            if has_photo(channel):
                log("канал", "аватарка на месте")
            else:
                warn("аватарка канала не появилась — поставлю при следующем "
                     "запуске --repair")
                problem("аватарка канала", "не появилась после загрузки")

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
            try:
                await set_photo(client, group, photo)
            except Exception as error:
                warn(f"аватарку чата поставить не вышло: {error}{photo_error_hint(error)}"); problem("аватарка чата", error)
            state["group_photo"] = args.group_avatar
            save_state(state)
            await asyncio.sleep(STEP_PAUSE)
            try:
                group = await client.get_entity(peer(group)) or group
            except Exception:
                pass
            if has_photo(group):
                log("чат", "аватарка на месте")
            else:
                warn("аватарка чата не появилась — поставлю при следующем "
                     "запуске --repair")
                problem("аватарка чата", "не появилась после загрузки")

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
    # Telegram не даёт привязать к каналу форум (FORUM_ENABLED): комментарии
    # могут жить только в обычной супергруппе. Тогда заводим её отдельно.
    comments = await resolve_saved(client, state, "comments_id",
                                   "comments_access_hash",
                                   COMMENTS["title"], True)

    if not state.get("linked") and state.get("comments_id") is None:
        log("связка", "комментарии к постам канала → в чат")
        try:
            await client(SetDiscussionGroupRequest(broadcast=channel, group=group))
            state["linked"] = True
            save_state(state)
            log("связка", "готово")
        except RPCError as error:
            if "FORUM_ENABLED" in str(error).upper():
                log("связка", "форум привязать нельзя — создаю отдельную "
                              "группу для комментариев")
            else:
                warn(f"связать автоматически не вышло: {error}")
        await asyncio.sleep(STEP_PAUSE)

    if not state.get("linked") and comments is None:
        try:
            comments = await create_entity(
                client, title=COMMENTS["title"], about=COMMENTS["about"][:255],
                megagroup=True)
            state["comments_id"] = comments.id
            state["comments_access_hash"] = getattr(comments, "access_hash", None)
            save_state(state)
            log("связка", f"создана группа «{COMMENTS['title']}»")
            await asyncio.sleep(STEP_PAUSE)
        except Exception as error:
            warn(f"группу для комментариев создать не удалось: {error}")
            problem("группа комментариев", error)
            comments = None

    # аватарка группы комментариев: ставим и досоздаём при повторном запуске
    if comments is not None:
        try:
            comments = await client.get_entity(peer(comments)) or comments
        except Exception:
            pass
        if not has_photo(comments):
            photo = avatar_path(getattr(args, "comments_avatar", 2), required=False)
            if photo is None:
                warn("аватарка группы комментариев недоступна — останется без фото")
            else:
                try:
                    await set_photo(client, comments, photo)
                    log("связка", f"аватарка комментариев: {photo.name}")
                except Exception as error:
                    warn(f"аватарку группы комментариев не поставить: {error}{photo_error_hint(error)}")
                    problem("аватарка комментариев", error)
                await asyncio.sleep(STEP_PAUSE)
                try:
                    comments = await client.get_entity(peer(comments)) or comments
                except Exception:
                    pass
                if not has_photo(comments):
                    warn("аватарка комментариев не появилась — повторю при --repair")
                    problem("аватарка комментариев", "не появилась после загрузки")

    if not state.get("linked") and comments is not None:
        try:
            await client(SetDiscussionGroupRequest(
                broadcast=channel, group=comments))
            state["linked"] = True
            save_state(state)
            log("связка", "комментарии к постам идут в отдельную группу")
        except RPCError as error:
            warn(f"привязать комментарии не вышло: {error}. "
                 f"Вручную: канал → управление → «Обсуждение».")
        await asyncio.sleep(STEP_PAUSE)

    if comments is not None:
        live = await pinned_in_chat(client, comments)
        if live is True or (live is None and state.get("comments_pin_done")):
            log("связка", "правила в группе комментариев на месте")
        else:
            message_id = state.get("comments_pinned")
            if not message_id:
                try:
                    message_id = await post_safe(
                        client, comments,
                        format_text(COMMENTS["pinned"], usernames))
                except Exception as error:
                    warn(f"правила в группе комментариев не отправились: {error}")
                    problem("правила комментариев", error)
                    message_id = None
                state["comments_pinned"] = message_id
                save_state(state)
            if message_id and await pin_safe(client, comments, message_id):
                state["comments_pin_done"] = True
                save_state(state)
                log("связка", "правила закреплены в группе комментариев")
            else:
                warn("правила в комментариях есть, но закреп не встал — "
                     "повторю позже")
                problem("правила комментариев", "не удалось закрепить")
        await asyncio.sleep(STEP_PAUSE)

    # --- автоудаление выключаем везде --------------------------------------
    # Таймер автоудаления живёт в самом чате: если он включён, посты и
    # закрепы со временем исчезнут. Ставим 0 у канала, чата и комментариев.
    for label, entity in (("канал", channel), ("чат", group), ("комментарии", comments)):
        if entity is None:
            continue
        current = await chat_ttl(client, entity)
        if current == 0:
            log(label, "автоудаление выключено")
            continue
        if await set_ttl_off(client, entity):
            was = f"было {current} с" if current else "проверить не вышло"
            log(label, f"автоудаление выключено ({was})")
        else:
            problem(f"автоудаление в {label}е", "не удалось выключить")
        await asyncio.sleep(STEP_PAUSE)

    # --- полная пересборка текстов (--reset) -------------------------------
    if getattr(args, "reset", False):
        import contextlib

        keep = set()
        try:
            result = await client(GetForumTopicsRequest(
                peer=peer(group), offset_date=None, offset_id=0,
                offset_topic=0, limit=100))
            keep = {item.id for item in result.topics}
        except Exception:
            keep = set(state.get("topics", {}).values())
        log("очистка", "удаляю старые сообщения: канал")
        first = await delete_all_messages(client, channel)
        log("очистка", "удаляю старые сообщения: чат (темы остаются)")
        second = await delete_all_messages(client, group, keep)
        third = 0
        if comments is not None:
            log("очистка", "удаляю старые сообщения: комментарии")
            third = await delete_all_messages(client, comments)
        log("очистка", f"удалено: {first + second + third}")
        for key in ("posts", "topic_pinned", "topic_pin_fails"):
            state.pop(key, None)
        for key in ("group_pinned", "general_pin_done", "channel_pin_done",
                    "comments_pinned", "comments_pin_done"):
            state.pop(key, None)
        save_state(state)
        await asyncio.sleep(STEP_PAUSE)

    # --- посты канала -----------------------------------------------------
    published = state.setdefault("posts", {})
    for post in CHANNEL_POSTS:
        if published.get(post["key"]):
            continue
        text = format_text(post["text"], usernames)
        try:
            message_id = await post_safe(client, channel, text)
        except Exception as error:
            warn(f"пост «{post['text'].splitlines()[0]}» не отправился: {error}"); problem("пост в канал", error)
            continue
        published[post["key"]] = message_id
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
        if message_id and await pin_safe(client, channel, message_id):
            state["channel_pin_done"] = True
            save_state(state)
            log("канал", "закреплён первый пост")
        else:
            warn("закрепить пост в канале не удалось — попробую в следующий раз")
            problem("закреп канала", "не удалось закрепить")
        await asyncio.sleep(STEP_PAUSE)

    # --- закреп в общей теме чата ----------------------------------------
    general_pinned = await pinned_in_chat(client, group)
    if general_pinned is True:
        log("чат", "закреп в общей теме на месте")
    elif general_pinned is None and state.get("general_pin_done"):
        log("чат", "закреп уже ставили (проверить сейчас не вышло)")
    else:
        message_id = state.get("group_pinned")
        if not message_id:
            try:
                message_id = await post_safe(
                    client, group, format_text(GROUP_PINNED, usernames))
            except Exception as error:
                warn(f"правила в чат не отправились: {error}")
                problem("закреп в чате", error)
                message_id = None
            state["group_pinned"] = message_id
            save_state(state)
        if message_id and await pin_safe(client, group, message_id):
            state["general_pin_done"] = True
            save_state(state)
            log("чат", "правила закреплены в общей теме")
        else:
            warn("правила в чате есть, но закреп не встал — повторю позже")
            problem("закреп в чате", "не удалось закрепить")
        await asyncio.sleep(STEP_PAUSE)

    # --- темы -------------------------------------------------------------
    # Смотрим, что реально есть в чате: темы могли создать раньше, с другим
    # названием или без значка. Ничего не пересоздаём — правим на месте.
    from telethon.tl.functions.messages import (CreateForumTopicRequest,
                                                EditForumTopicRequest)

    icons = await fetch_topic_icons(client)
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
        icon_id = pick_icon(icons, topic.get("icons", ()))
        title = topic_title(topic, icon_id)

        if item is None:
            log("темы", f"создаю «{title}»")
            request = dict(peer=peer(group), title=title,
                           icon_color=topic["color"],
                           random_id=random.getrandbits(63))
            if icon_id:
                request["icon_emoji_id"] = icon_id
            try:
                await client(CreateForumTopicRequest(**request))
            except TypeError:
                request.pop("icon_emoji_id", None)
                request["title"] = topic_title(topic, None)
                await client(CreateForumTopicRequest(**request))
            except Exception as error:
                warn(f"тема «{title}» не создалась: {error}")
                problem(f"тема «{title}»", error)
            await asyncio.sleep(STEP_PAUSE)
            continue

        topics_state[key] = item.id
        current_title = getattr(item, "title", "") or ""
        current_icon = getattr(item, "icon_emoji_id", None)
        need_title = current_title != title
        need_icon = bool(icon_id) and current_icon != icon_id

        if not need_title and not need_icon:
            log("темы", f"«{title}» уже как надо")
            continue

        edits = dict(peer=peer(group), topic_id=item.id)
        if need_title:
            edits["title"] = title
        if need_icon:
            edits["icon_emoji_id"] = icon_id
        try:
            await client(EditForumTopicRequest(**edits))
            if need_icon:
                log("темы", f"«{title}» — поставил значок")
            else:
                log("темы", f"переименовал в «{title}»")
        except TypeError:
            # старый Telethon без поддержки значков
            if need_title:
                try:
                    await client(EditForumTopicRequest(
                        peer=peer(group), topic_id=item.id, title=title))
                    log("темы", f"переименовал в «{title}»")
                except Exception as error:
                    warn(f"тему «{key}» не переименовать: {error}")
            if need_icon:
                warn("эта версия Telethon не умеет значки тем — обновите: "
                     "pip install -U telethon")
                problem(f"значок темы «{title}»", "нужен свежий Telethon")
        except Exception as error:
            warn(f"тему «{key}» не поправить: {error}")
            problem(f"тема «{title}»", error)
        await asyncio.sleep(STEP_PAUSE)
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
    pin_fails = state.setdefault("topic_pin_fails", {})
    for topic in TOPICS:
        key = topic["key"]
        topic_id = topics_state.get(key)
        if not topic_id:
            continue

        topic_pinned = await pinned_in_topic(client, group, topic_id)
        if topic_pinned is True:
            pin_fails.pop(key, None)
            log("закреп", f"тема «{topic['title']}» уже с закрепом")
            continue

        saved = pinned_state.get(key)
        if saved and topic_pinned is None:
            # проверить не вышло — верим своей отметке, чтобы не наплодить дублей
            log("закреп", f"тема «{topic['title']}» уже с закрепом (по отметке)")
            continue

        if saved and topic_pinned is False:
            # Сообщение когда-то отправили, но закреп не встал: закрепляем
            # именно его, а не публикуем шаблон заново — дублей не будет.
            if await pin_safe(client, group, saved):
                pin_fails.pop(key, None)
                log("закреп", f"тема «{topic['title']}» — перезакрепил")
                save_state(state)
                continue
            exists = await message_exists(client, group, saved)
            if exists is False:
                warn(f"шаблон в теме «{topic['title']}» пропал — опубликую заново")
                pinned_state.pop(key, None)
                pin_fails.pop(key, None)
                save_state(state)
                continue
            pin_fails[key] = pin_fails.get(key, 0) + 1
            save_state(state)
            warn(f"закреп в теме «{topic['title']}» не встал — попробую ещё "
                 f"раз в следующий запуск (шаблон уже на месте, дублей не будет)")
            problem(f"тема «{topic['title']}»", "закреп не встал")
            continue

        try:
            message_id = await post_safe(
                client, group, topic["pinned"], topic_id=topic_id)
        except Exception as error:
            warn(f"шаблон в теме «{topic['title']}» не отправился: {error}")
            problem(f"тема «{topic['title']}»", error)
            continue

        if message_id:
            pinned_state[key] = message_id
            pin_fails.pop(key, None)
            save_state(state)
        if message_id and await pin_safe(client, group, message_id):
            log("закреп", f"тема «{topic['title']}»")
        else:
            warn(f"шаблон в теме «{topic['title']}» есть, но закреп не встал — "
                 f"повторю позже, дублей не будет")
            problem(f"тема «{topic['title']}»", "закреп не встал")
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
    except Exception as error:
        warn(f"папка не создалась: {error}")
        problem("папка", error)
    await asyncio.sleep(STEP_PAUSE)

    # --- ссылки -----------------------------------------------------------
    links = {}
    targets = [("channel", channel), ("group", group)]
    if comments is not None:
        targets.append(("comments", comments))
    for name, entity in targets:
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
    print(f"  Сборка:    {BUILD_TAG}")
    if problems:
        print()
        print("  Что не удалось:")
        for where, error in problems:
            print(f"    • {where}: {error}")
        print("  Повторить: python3 pengram-community.py --repair")
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
    parser.add_argument("--version", action="store_true",
                        help="показать метку сборки скрипта и выйти")
    parser.add_argument("--avatar-list", action="store_true",
                        help="показать доступные аватарки и выйти")
    parser.add_argument("--channel-avatar", type=int, default=1,
                        help="номер аватарки канала (по умолчанию 1)")
    parser.add_argument("--group-avatar", type=int, default=3,
                        help="номер аватарки чата (по умолчанию 3)")
    parser.add_argument("--comments-avatar", type=int, default=2,
                        help="номер аватарки группы комментариев (по умолчанию 2)")
    parser.add_argument("--channel-username", default=None,
                        help="свой username для канала (без @)")
    parser.add_argument("--group-username", default=None,
                        help="свой username для чата (без @)")
    parser.add_argument("--status", action="store_true",
                        help="показать, что реально стоит в Telegram (ничего "
                             "не меняет): аватарки, закрепы, темы, папка, ссылки")
    parser.add_argument("--show-texts", action="store_true",
                        help="в просмотре показать все тексты целиком")
    parser.add_argument("--reset", action="store_true",
                        help="удалить все сообщения в канале, чате и "
                             "комментариях и разложить тексты заново "
                             "(темы и аватарки остаются)")
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
        if args.version:
            print(BUILD_TAG)
            return 0

        if args.avatar_list:
            print_avatars()
            return 0

        if args.status:
            asyncio.run(show_status())
            return 0

        name_for(args.channel_avatar)
        name_for(args.group_avatar)
        name_for(args.comments_avatar)

        if args.repair or args.reset:
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
    except Exception as error:
        kind = type(error).__name__
        text = f"{kind}: {error}"
        print(f"\nЗапуск прервался: {text}", file=sys.stderr)
        if "database is locked" in text or "locked" in text:
            print("Файл сессии занят: закройте другие окна Termux, где запущен "
                  "этот скрипт, и повторите.", file=sys.stderr)
        print("Уже созданное сохранено. Запустите ещё раз ту же команду — "
              "скрипт продолжит с того же места:", file=sys.stderr)
        print("    python pengram-community.py --repair", file=sys.stderr)
        return 3
    except KeyboardInterrupt:
        print("\nПрервано пользователем. Уже созданное сохранено в состоянии.",
              file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
