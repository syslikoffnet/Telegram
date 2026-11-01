# Анализ: офферы на NFT-подарки и бизнес-боты (по коду Pengram + Bot API)

Документ отвечает на два вопроса:

1. что в этом репозитории (форк Telegram для Android **Pengram**) относится к
   **офферам на уникальные подарки (NFT)** и **бизнес-ботам**, и как это устроено;
2. что из этого может делать **бот**, а что — только клиент/аккаунт, и почему
   `/top`-бот правильно сделан на Bot API, а не на userbot-сессии.

Все ссылки на код даны на файлы этого репозитория (пути от корня).

---

## TL;DR

| Вопрос | Ответ |
|---|---|
| Офферы на NFT-подарки — это клиентская фича? | Да. `payments.sendStarGiftOffer` / `resolveStarGiftOffer`, UI `GiftOfferSheet`, действия-сообщения `messageActionStarGiftPurchaseOffer…` |
| Есть ли эти методы в Bot API? | **Нет.** В Bot API есть только `sendGift` / `getAvailableGifts` / переводы и права бизнес-бота на подарки, но не офферы и не ресейл-маркетплейс |
| Можно ли «идеальным бизнес-ботом» продавать NFT-подарки? | Нет. Максимум — уведомления, учёт, отправка обычных подарков и операции с подарками бизнес-аккаунта при наличии прав |
| Бизнес-боты в Bot API — это что? | `business_connection` + `business_message` + методы с `business_connection_id`; права `BusinessBotRights` |
| Может ли бизнес-бот удалить сообщение клиента `/top`? | Да, но через `deleteBusinessMessages` и только с правом `can_delete_all_messages` («Delete Received Messages») |
| Может ли обычный бот удалить `/top` в ЛС? | Да, `deleteMessage` — Bot API прямо разрешает ботам удалять **входящие** сообщения в приватных чатах (до 48 ч) |
| Почему не редактировать вместо отправки? | Бот не может редактировать чужие сообщения; `editMessageText` работает только со своими. Схема «удалить → отправить» единственная корректная |

---

# Часть A. Офферы на уникальные подарки (NFT)

## A.1. Что это за механика

Уникальный подарок (`starGiftUnique`) — это коллекционный предмет с номером, моделью,
фоном и символом; его можно продавать на самой площадке Telegram или на NFT-маркетплейсах.
«Оффер» — предложение купить конкретный уникальный подарок за **Stars** или **TON** с
ограниченным сроком действия. Владелец подарка может принять или отклонить предложение;
по истечении срока деньги возвращаются.

## A.2. Где это в коде Pengram

### Протокол (TL)

| Файл | Что внутри |
|---|---|
| `TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_payments.java:486` | `payments.sendStarGiftOffer` (`0x8fb86b41`): `peer`, `slug`, `price: StarsAmount`, `duration`, `random_id`, `allow_paid_stars` |
| `TL_payments.java:465` | `payments.resolveStarGiftOffer` (`0xe9ce781c`): `decline:flags.0?true`, `offer_msg_id` |
| `TL_payments.java:450` | `payments.getStarGiftActiveAuctions` (`0xa5d0514d`) — аукционы подарков |
| `TL_payments.java:~420` | `payments.getStarGiftAuctionAcquiredGifts` — выигранные лоты |
| `TL_stars.java:19` / `:141` | `StarGift` / `TL_starGiftUnique`: `id`, `gift_id`, `slug`, `num`, `owner_id`, `attributes`, `availability_issued/total`, `resell_amount`, `value_amount/currency/usd`, `offer_min_stars` |
| `TL_stars.java:69` | `offer_min_stars` — минимальная сумма оффера, которую вообще разрешает сервер |

Схема из официальной документации Telegram (`core.telegram.org/api/gifts`) полностью
совпадает с этими классами — форк использует актуальный слой.

### Сообщения-действия

| Файл | Класс |
|---|---|
| `TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java:67514` | `messageActionStarGiftPurchaseOffer` — `accepted`, `declined`, `gift`, `price`, `expires_at` |
| `TLRPC.java:67546` | `messageActionStarGiftPurchaseOfferDeclined` — `expired`, `gift`, `price` |
| `TLRPC.java:67222` | `messageActionStarGiftUnique` — в т.ч. флаг `from_offer` (`:67231`): предмет получен именно через принятый оффер |

То есть оффер — это не «запрос в базе», а **обычное сообщение в чате** с особым
действием и сроком жизни. Отсюда все UI-решения.

### UI и логика клиента

| Файл | Что делает |
|---|---|
| `org/telegram/ui/Stars/GiftOfferSheet.java` (879 строк) | Шторка «Offer to Buy»: выбор валюты Stars/TON, ввод суммы, срок, комиссия |
| `GiftOfferSheet.java:111` | `ALLOWED_DURATIONS = {21600, 43200, 86400, 129600, 172800, 259200}` → 6/12/24/36/48/72 часа |
| `GiftOfferSheet.java:147` | TON-офферы доступны только если `StarsController.getTonInstance().canUseTon()` |
| `GiftOfferSheet.java:161-177` | Лимиты: минимум из `offer_min_stars` (для TON — через конверсию и `tonStarGiftResaleAmountMin`), максимум — `max(min*2, tonStarGiftResaleAmountMax)` |
| `org/telegram/ui/Stars/StarGiftSheet.java:1124` | Пункт меню «Offer to buy» появляется только если `giftUnique.offer_min_stars > 0` |
| `StarGiftSheet.java:1138` | Открывает `GiftOfferSheet` для владельца подарка |
| `org/telegram/messenger/BotInlineKeyboard.java:167` | `addGiftOfferKeyboard()` — кнопки `GiftOfferAccept` / `GiftOfferDecline` |
| `org/telegram/ui/Cells/ChatActionCell.java:876-896` | Считает `offerExpired` по `expires_at`, рисует кнопки только для входящего неистёкшего оффера |
| `ChatActionCell.java:4207-4226` | Принятие/отклонение: `TL_resolveStarGiftOffer { offer_msg_id, decline }` |
| `org/telegram/messenger/MessageObject.java:5887-5923` | Тексты статусов: «вам предложили N Stars», «истёк», «отклонён» |
| `org/telegram/ui/Stars/StarsIntroActivity.java:1675` | Транзакции: `Gift Sale`, `Gift Offer`, возвраты (`StarGiftTransactionGiftOfferRefund`) |

Вывод: оффер живёт **в переписке**. Срок, цена, статус, кнопки — всё привязано к
`message_id`, а `resolveStarGiftOffer` принимает именно `offer_msg_id`.

## A.3. Жизненный цикл оффера

```
Покупатель                         Сервер                         Владелец подарка
    │                                 │                                  │
    │ payments.sendStarGiftOffer      │                                  │
    │ peer, slug, price, duration ───►│                                  │
    │                                 │  messageActionStarGiftPurchaseOffer
    │                                 │  (expires_at = now + duration) ──►│
    │                                 │                                  │  Accept / Decline
    │                                 │◄── payments.resolveStarGiftOffer  │
    │◄── accepted / declined ─────────┤                                  │
    │   (или expired по таймеру — деньги возвращаются)                     │
```

* `duration` — от 6 до 72 часов (см. `ALLOWED_DURATIONS`);
* `random_id` — идемпотентность запроса;
* `allow_paid_stars` — разрешённая докупка Stars при нехватке;
* по истечении срока `messageActionStarGiftPurchaseOfferDeclined { expired }`, а Stars/TON
  возвращаются на баланс (в транзакциях это `…Refund`).

## A.4. Аукционы — отдельная ветка

`org/telegram/messenger/GiftAuctionController.java` + `getStarGiftActiveAuctions` +
`org/telegram/ui/Gifts/AuctionBidSheet.java` — это торги за новые уникальные подарки
(ставки, повышение ставки, смена получателя). К офферам на вторичном рынке отношения
не имеет, но тоже **недоступно** через Bot API.

## A.5. Что из этого может бот

| Возможность | Клиент | Bot API | Комментарий |
|---|---|---|---|
| Отправить обычный подарок | да | **да** (`sendGift`, `getAvailableGifts`, Bot API 8.0+) | получатель не может конвертировать его в Stars |
| Отправить Premium в подарок | да | **да** (`giftPremiumSubscription`) | |
| Создать оффер на покупку уникального подарка | да | **нет** | только MTProto с аккаунта |
| Принять/отклонить оффер | да | **нет** | только MTProto с аккаунта |
| Смотреть ресейл-маркетплейс | да | **нет** | `payments.getResaleStarGifts` — MTProto |
| Управлять подарками бизнес-аккаунта | да | **частично** | права `can_view_gifts_and_stars`, `can_convert_gifts_to_stars`, `can_transfer_and_upgrade_gifts` |
| Узнать о новом оффере | да | **нет** (в апдейтах бота такого события нет) | бизнес-бот увидит разве что текст сообщения, если оно пришло в его чат |

Именно поэтому «бот, который сам торгует NFT-подарками», на Bot API невозможен —
нужна либо userbot-сессия (см. часть E), либо ручные действия владельца.

---

# Часть B. Бизнес-боты

## B.1. Как устроено в клиенте (MTProto-сторона)

| Файл | Что |
|---|---|
| `TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java:3059` | `account.getConnectedBots` (`0x4ea4c80f`) |
| `TL_account.java:3006` | `account.connectedBots` (`0x17d7f87b`): `connected_bots[]` + `users[]` |
| `TL_account.java:2938` | `TL_connectedBot` (`0x33ed001`): `bot_id`, `recipients`, `rights`, `device`, `date`, `location` |
| `TL_account.java:2763` | `TL_businessBotRights` (`0xa0624cf7`) — полный набор прав |
| `TL_account.java:~3090` | `account.updateConnectedBot` (`0x66a08c7e`) — подключить/отключить/изменить права |
| `TL_account.java:~2970` | `account.confirmBotConnection` (`0x67ed1f68`) |
| `TL_account.java:2604` / `:2655` | `inputBusinessBotRecipients` / `businessBotRecipients` — какие чаты бот видит (existing/new/contacts/non_contacts + исключения) |

Права `TL_businessBotRights` (флаги из кода):

```
reply (1)                     read_messages (2)
delete_sent_messages (4)      delete_received_messages (8)
edit_name (16)                edit_bio            edit_profile_photo
edit_username                 view_gifts          sell_gifts
change_gift_settings          transfer_and_upgrade_gifts
transfer_stars                manage_stories
```

Обратите внимание: `sell_gifts` — это и есть мостик к подаркам. Бизнес-бот с таким правом
может конвертировать/продавать подарки аккаунта, но **не** создавать офферы на чужие NFT.

## B.2. UI клиента

| Файл | Роль |
|---|---|
| `org/telegram/ui/Business/ChatbotsActivity.java` (853 строки) | Экран «Чат-боты»: выбор бота по ссылке, настройка прав и чатов |
| `org/telegram/ui/Business/ChatbotSheet.java` | Карточка подключённого бота: права, получатели, «Terminate» |
| `org/telegram/ui/Business/BusinessChatbotController.java` | Кэш `getConnectedBots`, уведомление `updatedChatbot` |
| `org/telegram/ui/Business/BusinessRecipientsHelper.java` | Выбор «какие чаты бот НЕ трогает» |
| `.../Business/AwayMessagesActivity.java`, `GreetMessagesActivity.java`, `QuickRepliesActivity.java`, `OpeningHoursActivity.java` | Автоответы, приветствия, быстрые ответы, часы работы — соседние бизнес-функции |

Строки прав в `TMessagesProj/src/main/res/values/strings.xml:7896-7905`:

```
Bot permissions
 Manage Messages
  Read Messages
  Reply to Messages
  Mark Messages As Read
  Delete Sent Messages
  Delete Received Messages
```

Именно **Delete Received Messages** соответствует `delete_received_messages` в TL и
`can_delete_all_messages` в Bot API. Без него бизнес-бот не удалит сообщение клиента.

## B.3. Что даёт Bot API (сторона бота)

| Событие/метод | Смысл |
|---|---|
| update `business_connection` | бот подключён/отключён, пришли `rights` |
| update `business_message` | сообщение в чате бизнес-аккаунта (у `Message` есть `business_connection_id`) |
| update `edited_business_message` | изменённое сообщение |
| update `deleted_business_messages` | удалённые сообщения |
| `sendMessage(..., business_connection_id)` | ответ **от имени аккаунта** |
| `deleteBusinessMessages(business_connection_id, message_ids)` | удаление в бизнес-чате (нужно право) |
| `readBusinessMessage` | отметить прочитанным (`can_read_messages`) |
| `getBusinessConnection` | проверить права/статус |

Ключевые ограничения:

* `can_reply` — «send and edit messages in the private chats that had incoming messages in
  the last 24 hours». Значит, ответ возможен только после входящего сообщения и только 24 ч.
* `deleteMessage` **не принимает** `business_connection_id`; для бизнес-чатов — только
  `deleteBusinessMessages`.
* Бизнес-бот работает в чатах, которые выбраны в `recipients`, и не видит исключённые.
* С 2026 года (Bot API 10.0) часть бизнес-функций доступна без Premium у владельца,
  но подключение всё равно идёт через Telegram Business.

## B.4. Специфика именно этого форка

* **Вход в аккаунт по токену бота** — `TMessagesProj/src/main/java/org/telegram/tgnet/TL_auth_importBotAuthorization.java`
  (`auth.importBotAuthorization`, `0x67a3ff2c`) + `LoginActivity` (кнопка «Войти как бот»).
  Это удобно для отладки ботов, но **не заменяет** Bot API: у бота-аккаунта нет истории
  чатов пользователя, только его собственные чаты.
* **История удалённых/изменённых** (`pengram_history.db`) — локальная фича клиента. Бот,
  даже подключённый как бизнес-бот, эту базу не видит и не получает удалённые сообщения
  из неё. Не путайте: `deleted_business_messages` приходит от сервера, а не из истории форка.
* **Локальный Premium** форка не влияет на серверные права бота (это клиентская разблокировка).

---

# Часть C. Матрица «фича ↔ доступность»

| Фича | Клиент Pengram | Обычный бот (Bot API) | Бизнес-бот (Bot API) | Userbot (MTProto) |
|---|---|---|---|---|
| Читать сообщения в своём ЛС | да | да | — | да |
| Удалить входящее сообщение в ЛС | да | **да** (48 ч) | — | да |
| Удалить сообщение клиента в бизнес-чате | владелец | нет | **да** (с правом) | да |
| Отправить новое сообщение | да | да | да (24 ч окно) | да |
| Редактировать своё сообщение | да | да | да | да |
| Редактировать чужое сообщение | нет | нет | нет | нет |
| Отправить подарок | да | да (`sendGift`) | да | да |
| Создать/принять оффер NFT | да | **нет** | **нет** | **да** |
| Аукционы подарков | да | нет | нет | да |
| Автоответы/приветствия | да | — | да | да |
| Часы работы, быстрые ответы | да | — | частично (настраивает владелец) | да |

---

# Часть D. Почему `/top`-бот — это Bot API, и каким должен быть «идеальный» бизнес-бот

## D.1. Схема

```
Клиент пишет /top
        │
        ▼
getUpdates (long polling, offset сохраняется)
        │
        ├─ фильтры: MODE, возраст сообщения, whitelist, кулдаун, лимит/мин
        ▼
deleteMessage(chat_id, message_id)            ← /top исчезает
   или deleteBusinessMessages(conn_id, [id])  ← в бизнес-чате
        ▼
sendMessage(chat_id, text, business_connection_id?)  ← НОВОЕ сообщение
        ▼
state.json: offset, id отправленных топов, права подключений
```

## D.2. Чек-лист «идеального» бота (реализовано в `bot.py`)

**Надёжность**

- [x] `offset` в `state.json`, атомарная запись (`tmp` + `os.replace`), права 600.
- [x] фильтр старых сообщений — после рестарта нет пачки ответов.
- [x] изоляция ошибок: один плохой апдейт не роняет процесс.
- [x] ретраи: сеть/5xx — экспоненциальный backoff; 429 — ждём `retry_after`.
- [x] грациозная остановка по SIGINT/SIGTERM.
- [x] `409 Conflict` распознаётся отдельно и объясняется в логе.
- [x] webhook снимается при старте (иначе long polling не работает).

**Соблюдение правил Telegram**

- [x] удаление до 48 ч (свежие сообщения — всегда);
- [x] лимит 4096 UTF-16, нарезка по строкам;
- [x] антифлуд: кулдаун на чат + минута;
- [x] HTML-экранирование и отключаемое превью ссылок;
- [x] никакого `editMessageText` — каждое нажатие `/top` даёт новое сообщение.

**Бизнес-режим**

- [x] `business_connection` запоминается вместе с правами;
- [x] удаление через `deleteBusinessMessages`, отправка с `business_connection_id`;
- [x] если права на удаление нет — бот не спамит ошибками, а пишет понятное предупреждение;
- [x] `MODE=auto|private|business`.

**Эксплуатация**

- [x] `--check`, `--print-top`, `--selftest`, `--once`, `--dry-run`, `-v`;
- [x] ротация логов, маскирование токена;
- [x] без внешних зависимостей → Termux, VPS, Docker, cron;
- [x] 57 тестов, включая mock Bot API (429, 400, не-JSON, сеть).

## D.3. Ошибки, которые чаще всего ломают таких ботов

1. **Пытаться отредактировать сообщение пользователя** — API этого не умеет.
2. **Удалять в бизнес-чате через `deleteMessage`** — нужен `deleteBusinessMessages`.
3. **Не запрашивать право `can_delete_all_messages`** — удаление молча не работает.
4. **Слать в бизнес-чат без `business_connection_id`** — сообщение уйдёт «от бота» и не туда.
5. **`parse_mode=MarkdownV2` с именами** — любая точка/дефис в имени ломает разметку
   (`can't parse entities`). HTML + экранирование надёжнее.
6. **Считать символы, а не UTF-16** — эмодзи в заголовке съедают лимит вдвое.
7. **Игнорировать `offset` и возраст сообщений** — после простоя бот отвечает на всё подряд.
8. **Держать webhook и long polling одновременно** — вечный 409.
9. **Не обрабатывать 429** — бот «умирает» на первом же флуде.
10. **Токен в git/логах** — самый частый способ потерять бота.

---

# Часть E. Если всё-таки нужен мониторинг NFT-офферов

Bot API этого не умеет, значит нужен **MTProto с аккаунта** (userbot). Архитектурно это
другой продукт:

```
Python + Telethon/teleproto (api_id/api_hash + session)
        │
        ├── payments.getResaleStarGifts / getStarGifts  → каталог и ресейл
        ├── payments.getUniqueStarGift                  → карточка подарка
        ├── payments.sendStarGiftOffer(peer, slug, price, duration)
        └── payments.resolveStarGiftOffer(offer_msg_id, decline)
```

Что нужно учитывать:

* **Отдельный аккаунт**, не основной. Хранить `*.session` как пароль, не в Git.
* `api_id`/`api_hash` — свои, из <https://my.telegram.org>.
* Ответ `STARGIFT_OFFER_NOT_ALLOWED` означает, что фича недоступна аккаунту/региону —
  это не баг кода (подтверждается описанием `payments.sendStarGiftOffer`).
* Лимиты офферов и FloodWait агрессивнее, чем в Bot API; нужна очередь и идемпотентность
  (`random_id`, дедуп по `slug`/владельцу).
* **ToS**: массовые автоматические офферы и парсинг маркетплейса могут привести к
  ограничениям аккаунта. Ответственность на владельце.
* Офферы живут сообщениями в чате, поэтому «принять» можно только с того аккаунта,
  которому их прислали (владельцу подарка).

Практический компромисс: **бизнес-бот на Bot API** делает всю коммуникацию с людьми
(топы, ответы, статусы), а операции с NFT — отдельный сервис на MTProto, который шлёт
уведомления в чат через того же бота. Смешивать их в одном процессе не стоит: падение
userbot-сессии не должно ронять клиентский бот.

---

# Часть F. Источники

**В репозитории**

- `TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_payments.java` — офферы и аукционы.
- `TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stars.java` — `StarGift`, `starGiftUnique`, лимиты офферов.
- `TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java` — действия-сообщения офферов.
- `TMessagesProj/src/main/java/org/telegram/ui/Stars/GiftOfferSheet.java`, `StarGiftSheet.java`.
- `TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatActionCell.java` — кнопки и статусы.
- `TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java` — `connectedBots`, права бизнес-ботов.
- `TMessagesProj/src/main/java/org/telegram/ui/Business/*` — UI подключения ботов.
- `TMessagesProj/src/main/res/values/strings.xml` — тексты прав и статусов.
- `TMessagesProj/src/main/java/org/telegram/tgnet/TL_auth_importBotAuthorization.java` — вход по токену бота.

**Официальная документация**

- Bot API: <https://core.telegram.org/bots/api> (`deleteMessage`, `deleteBusinessMessages`,
  `BusinessBotRights`, `sendGift`, `getAvailableGifts`).
- Фичи бизнес-ботов: <https://core.telegram.org/bots/features#business-bots>.
- Connected business bots (MTProto): <https://core.telegram.org/api/bots/connected-business-bots>.
- Подарки и офферы (MTProto): <https://core.telegram.org/api/gifts>.
