# TOP-бот для Telegram — установка в Termux, настройка и все ошибки

Бот для личных сообщений (и опционально для **Telegram Business**), который по команде
`/top`:

1. **удаляет сообщение с командой** `/top`;
2. **отправляет новое сообщение** с нумерованным списком:

```
🏆 ТОП

1. Misha
2. Misha
3. misha
```

Никакого `editMessageText`: каждый `/top` — это **новое** сообщение, как и просили.
Список берётся из файла `top.json` и правится на ходу, без перезапуска бота.

Зависимостей нет вообще: только стандартная библиотека Python. В Termux это принципиально —
не нужно собирать `aiogram`/`pydantic`/`uvloop`, не ломается pip на новой версии Python,
не нужны Rust и Android NDK.

---

## Содержание

- [Что именно делает бот](#что-именно-делает-бот)
- [Файлы](#файлы)
- [Часть 1. Создание бота в BotFather](#часть-1-создание-бота-в-botfather)
- [Часть 2. Установка в Termux](#часть-2-установка-в-termux)
- [Часть 3. Настройка списка](#часть-3-настройка-списка)
- [Часть 4. Telegram Business (необязательно)](#часть-4-telegram-business-необязательно)
- [Часть 5. Работа 24/7 в Termux](#часть-5-работа-247-в-termux)
- [Часть 6. Диагностика](#часть-6-диагностика)
- [Ошибки и решения](#ошибки-и-решения)
- [Ограничения Telegram](#ограничения-telegram)
- [Безопасность](#безопасность)
- [VPS и systemd](#vps-и-systemd)
- [FAQ](#faq)

---

## Что именно делает бот

Сценарий в личке с ботом:

```
Вы:   /top                    ← это сообщение бот удаляет
Бот:  🏆 ТОП
      1. Misha
      2. Misha
      3. misha                ← это НОВОЕ сообщение, не правка
```

Второй `/top` → второе новое сообщение. Старое остаётся (можно включить автоудаление
прошлого топа: `DELETE_PREVIOUS_TOPS=true`).

Почему удаление, а не редактирование: бот не может «превратить» ваше сообщение в топ —
`editMessageText` правит только сообщения бота. Поэтому схема всегда:

```
удалить сообщение пользователя → отправить своё новое
```

Мелочи, которые обычно забывают (и из-за которых боты «глючат»), здесь уже учтены:

| Что | Как решено |
|---|---|
| Пачка ответов после перезапуска на старые `/top` | сообщения старше `MAX_MESSAGE_AGE_SECONDS` (120 c) игнорируются |
| Дубли при рестарте | `offset` сохраняется в `state.json` |
| Ошибка «message can't be deleted» | удаление не блокирует отправку топа, в лог пишется причина |
| Флуд и `429 Too Many Requests` | кулдаун на чат + лимит в минуту + автоматическое ожидание `retry_after` |
| Длинный список | режется на части по 3800 UTF-16-символов (эмодзи считаются за 2) |
| Имена с `<`, `>`, `&` | HTML-экранирование, иначе Telegram отвечает «can't parse entities» |
| Токен в логах | маскируется |
| Битый `top.json` | остаётся предыдущий рабочий список, бот не падает |
| Сеть/перезапуск | экспоненциальный backoff, `--once`, корректная остановка по SIGTERM |

---

## Файлы

| Файл | Зачем |
|---|---|
| `bot.py` | сам бот (один файл, без зависимостей) |
| `top.json` | список топа (правится без перезапуска) |
| `env.example` | шаблон конфига → копируется в `.env` |
| `install-termux.sh` | установщик для Termux (python, сертификаты, токен, автозапуск) |
| `run.sh` | запуск с wake-lock и UTF-8 |
| `tests/` | 71 тест: юнит, транспорт и сквозные прогоны через mock Bot API |
| `run-tests.sh` | прогнать все тесты |
| `ANALYSIS.md` | разбор подарков/NFT-офферов и бизнес-ботов в этом репозитории |

---

## Часть 1. Создание бота в BotFather

1. Откройте [@BotFather](https://t.me/BotFather) → `/newbot`.
2. Имя — любое («Мой TOP-бот»). Username — **обязательно заканчивается на `bot`**
   (`my_top_bot`). Если имя занято — BotFather попросит другое.
3. BotFather пришлёт токен:

```
123456789:AAH6Xk...        ← это пароль от бота, никому не показывайте
```

4. Полезно сразу:
   - `/setdescription` — описание бота;
   - `/setcommands` → `top - Показать топ` (бот ещё и сам ставит меню при старте);
   - `/setprivacy` → **Disable** — нужен только если планируете команды в группах.

> Токен не пересылайте в чаты и не коммитьте. Если утёк — `/revoke` у BotFather,
> старый токен сразу перестанет работать.

---

## Часть 2. Установка в Termux

### 2.0. Где взять Termux

Ставьте Termux **только** из F-Droid или GitHub Releases:

- <https://f-droid.org/packages/com.termux/>
- <https://github.com/termux/termux-app/releases>

Версия из Google Play устарела и падает на `pkg install` (ошибки вида
`Unable to install... targetSdkVersion` / `pkg: repository ... 404`). Это не лечится
обновлением — нужно переустановить Termux из F-Droid.

### 2.1. Автоматический способ (рекомендуется)

Положите папку `business-bot` в память телефона (Telegram «Сохранить в файлы», кабель,
`git clone` — как удобно), затем в Termux:

```bash
pkg update -y && pkg upgrade -y
cd ~/storage/downloads/Telegram/business-bot     # путь к папке с bot.py
bash install-termux.sh
```

> Если `~/storage` ещё не настроен: `termux-setup-storage` и разрешите доступ.
> Если папки нет в Downloads — просто найдите её: `find ~ -name bot.py 2>/dev/null`.

Установщик:

- поставит `python` и `ca-certificates`;
- скопирует бота в `~/top-bot`;
- спросит токен (ввод не отображается) и создаст `~/top-bot/.env` с правами `600`;
- проверит TLS и вызовет `getMe`;
- подготовит автозапуск `~/.termux/boot/start-top-bot.sh`.

### 2.2. Ручной способ (если хочется контролировать каждый шаг)

```bash
pkg update -y && pkg upgrade -y
pkg install -y python ca-certificates nano tmux

mkdir -p ~/top-bot && cd ~/top-bot
# скопируйте сюда bot.py, top.json, env.example
cp env.example .env
nano .env                     # впишите BOT_TOKEN=123456789:AAH...
chmod 600 .env

python3 -V                    # должно быть 3.9+ (в Termux сейчас 3.11/3.12)
python3 bot.py --selftest     # офлайн-проверка логики, без сети
python3 bot.py --check        # проверка токена и конфига
```

Ожидаемый вывод `--check` (сокращённо):

```
getMe: @my_top_bot (id=123456789, name=Мой TOP-бот)
deleteWebhook: ok

Итог: всё готово к запуску.
```

### 2.3. Первый запуск

```bash
cd ~/top-bot
bash run.sh
```

Или без скрипта:

```bash
python3 bot.py
```

В логе должно появиться:

```
Бот: @my_top_bot (id=..., name=...)
Слушаю обновления (long polling, timeout=30 c)
```

Откройте бота в Telegram → `/start` → `/top`. Сообщение `/top` исчезнет, придёт топ.

Остановка: `Ctrl+C` (бот завершится аккуратно и сохранит `state.json`).

---

## Часть 3. Настройка списка

### 3.1. JSON (по умолчанию)

`top.json`:

```json
{
  "title": "ТОП",
  "entries": ["Misha", "Misha", "misha"]
}
```

С очками:

```json
{
  "title": "ТОП недели",
  "entries": [
    {"name": "Misha", "score": 120},
    {"name": "Oleg", "score": 80},
    {"name": "misha", "score": 15}
  ]
}
```

Если в шаблоне строки нет `{score}`, очки автоматически допишутся через « — »:
`1. Misha — 120`.

Сортировка по очкам: `SORT_BY_SCORE=true` в `.env`.

### 3.2. Обычный текст (`top.txt`)

```
# комментарий, строки с # игнорируются
Misha | 120
Oleg
misha | 15
```

Имя и очки разделяются `|`. В `.env`: `TOP_FILE=top.txt`.

### 3.3. Форматирование

Всё в `.env`:

```ini
TOP_HEADER=🏆 <b>{title}</b>     # пусто → без заголовка
TOP_FOOTER=Обновлено: {count} мест
ITEM_TEMPLATE={n}. {name}        # например {n}) {name} или {n}. <b>{name}</b>
ITEM_SEPARATOR=\n                # \n\n → пустая строка между позициями
SHOW_MEDALS=false                # 🥇🥈🥉 для первых трёх
TOP_LIMIT=0                      # 0 = все, 10 = только первые 10
PARSE_MODE=HTML                  # или none (без разметки)
```

Пример «как в запросе, с пустыми строками»:

```ini
ITEM_SEPARATOR=\n\n
TOP_HEADER=
```

даст ровно:

```
1. Misha

2. Misha

3. misha
```

### 3.4. Правка на ходу

Меняете `top.json` → следующий `/top` уже с новым списком (файл перечитывается, если
содержимое изменилось). Если правите через `nano` в Termux — просто сохраните файл.
Принудительно перечитать: `/reload` (только для ID из `ADMIN_IDS`).

---

## Часть 4. Telegram Business (необязательно)

Обычный ЛС с ботом работает сразу и ничего не требует. Режим Business нужен, если бот
должен отвечать **в ваших чатах с клиентами** от имени вашего аккаунта.

### 4.1. Подключение

1. В BotFather: `/mybots` → ваш бот → **Bot Settings → Business Mode → Turn on**.
   (Без этого бот не получит `business_connection`.)
2. В Telegram: **Настройки → Telegram Business → Чат-боты → Добавить бота** → username.
3. Выберите, к каким чатам у бота есть доступ (можно исключить личные).
4. Включите права:
   - **Read Messages** — читать сообщения;
   - **Reply to Messages** — отвечать (`can_reply`, только в чатах, куда писали за последние 24 ч);
   - **Delete Received Messages** — **обязательно**, иначе `/top` клиента не удалится
     (`can_delete_all_messages`);
   - **Delete Sent Messages** — по желанию.

Telegram Business в большинстве регионов требует Premium у владельца аккаунта; часть
возможностей в 2026 году уже доступна без него — ориентируйтесь на то, что показывает
ваш клиент.

### 4.2. Чем бизнес-режим отличается технически

| | ЛС с ботом | Бизнес-чат |
|---|---|---|
| Апдейт | `message` | `business_message` |
| Кто «автор» ответа | бот | ваш аккаунт (бот не виден как бот) |
| Удаление чужого сообщения | `deleteMessage` (разрешено) | `deleteBusinessMessages` + право `can_delete_all_messages` |
| Отправка | `sendMessage` | `sendMessage` + `business_connection_id` |
| Кому доступно | всем | аккаунтам с Telegram Business |

Бот сам определяет режим (`MODE=auto`). Хотите только ЛС — поставьте `MODE=private`,
только Business — `MODE=business`.

Важно: команду `/top` в бизнес-чате должен написать **собеседник** (входящее сообщение).
Свои исходящие сообщения бот как «запрос» не обрабатывает.

---

## Часть 5. Работа 24/7 в Termux

Android любит убивать фоновые процессы. Нужны три вещи: **wake-lock**, **отключённая
оптимизация батареи** и **автозапуск**.

### 5.1. Простейший фон

```bash
cd ~/top-bot
nohup python3 bot.py >> bot.log 2>&1 &
```

Остановить: `pkill -f bot.py` (или `kill <PID>`).

### 5.2. tmux (удобнее: можно вернуться в «живой» терминал)

```bash
pkg install -y tmux
tmux new -s topbot
bash run.sh
# свернуть: Ctrl+B, затем D
# вернуться: tmux attach -t topbot
```

### 5.3. Wake-lock и батарея

```bash
termux-wake-lock        # снять: termux-wake-unlock
```

- Потяните шторку уведомлений Termux → «Acquire wakelock».
- Настройки Android → Приложения → Termux → Батарея → **Без ограничений**
  (Xiaomi/Huawei/Samsung: ещё «Автозапуск» и «Работа в фоне»).

### 5.4. Автозапуск после перезагрузки

Поставьте **Termux:Boot** (F-Droid), запустите его один раз, затем:

```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/start-top-bot.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock 2>/dev/null
cd "$HOME/top-bot" || exit 0
exec python3 bot.py >> "$HOME/top-bot/boot.log" 2>&1
EOF
chmod +x ~/.termux/boot/start-top-bot.sh
```

`install-termux.sh` создаёт этот файл сам.

### 5.5. Android 12+ «phantom process killer»

Если процесс стабильно умирает через 10–30 минут, помогите системе:

```
Настройки → Для разработчиков → Отключить дочерние процессы (если есть)
```

или с компьютера по adb (по Wi-Fi отладке):

```bash
adb shell settings put global settings_enable_monitor_phantom_procs false
adb shell device_config put activity_manager max_phantom_processes 2147483647
```

Крайний вариант — VPS (см. ниже): на телефоне постоянно держать бота — самое хрупкое место.

### 5.6. Логи

```bash
tail -f ~/top-bot/bot.log
```

Файл ротируется сам: 1 МБ × 3 копии.

---

## Часть 6. Диагностика

```bash
python3 bot.py --check        # конфиг + getMe + webhook + предпросмотр
python3 bot.py --print-top    # как увидит человек + исходный HTML, сколько частей
python3 bot.py --selftest     # 27 проверок логики без сети
python3 bot.py --set-token '123456789:AAH...'   # записать новый токен в .env и проверить
python3 bot.py --once         # один цикл getUpdates и выход
python3 bot.py -v             # DEBUG-лог
python3 bot.py --dry-run      # ничего не отправляет/удаляет, только логирует
bash run-tests.sh             # 71 тест, включая mock Bot API
```

> `--print-top` специально печатает два блока: «как увидит получатель» (без тегов) и
> исходный HTML, который уходит в API. Строки вида `🏆 &lt;b&gt;ТОП&lt;/b&gt;` в консоли —
> это нормально, в Telegram они превращаются в жирный заголовок.

Проверка вручную через curl (если охота увидеть «сырой» ответ):

```bash
curl -s "https://api.telegram.org/bot<ТОКЕН>/getMe"
curl -s "https://api.telegram.org/bot<ТОКЕН>/getWebhookInfo"
```

---

## Ошибки и решения

### Сеть, TLS, Termux

| Ошибка | Причина | Решение |
|---|---|---|
| `SSLCertVerificationError: certificate verify failed` | в Termux нет CA-сертификатов | `pkg install -y ca-certificates openssl && pkg reinstall -y ca-certificates`; затем `export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem`. В крайнем случае `INSECURE_TLS=true` в `.env` (небезопасно) |
| `SSLError: [SSL: CERTIFICATE_VERIFY_FAILED]` при `pip` | та же причина | сертификаты выше; боту pip не нужен вообще |
| `OSError: [Errno -3] Try again` / `getaddrinfo failed` | DNS/провайдер блокирует | VPN, другой DNS, или `export HTTPS_PROXY=http://127.0.0.1:8080` (urllib понимает `HTTPS_PROXY`) |
| `socket.timeout` / `Timed out` | сеть, VPN, мобильный интернет | бот сам повторяет с backoff; проверьте `curl https://api.telegram.org` |
| `pkg: command not found` | запущен не Termux или другая оболочка | Termux из F-Droid, откройте новый сеанс |
| `Unable to locate package python` | не обновлены списки | `pkg update -y && pkg upgrade -y` |
| `termux-wake-lock: command not found` | старый Termux | `pkg install -y termux-tools` |
| `Permission denied` при запуске `.sh` | нет `+x` | `bash run.sh` или `chmod +x run.sh` |
| `No such file or directory: 'bot.py'` | не та папка | `cd ~/top-bot`, либо используйте `bash run.sh` (он сам переходит в свою папку) |
| `OSError: [Errno 28] No space left on device` | закончилось место | `rm -f bot.log.*`, `pkg clean`, освободите память |
| `SyntaxError` / `invalid syntax` | старый Python или файл побился при копировании | `python3 -V` (нужно 3.9+), перекопируйте `bot.py` |
| `UnicodeDecodeError` при чтении `top.json` | файл сохранён в UTF-16/в другой кодировке | сохраните как UTF-8 |

### Telegram Bot API

| Ошибка в логе | Причина | Решение |
|---|---|---|
| `409 Conflict: terminated by other getUpdates request` | запущено **два** бота с этим токеном или стоит webhook | `pkill -f bot.py`, затем один запуск. Webhook бот снимает сам (`DELETE_WEBHOOK_ON_START=true`) |
| `401 Unauthorized` | токен отозван, перевыпущен, бот удалён или токен скопирован с ошибкой | новый токен у BotFather (`/mybots → API Token → Revoke/Show`), затем `python3 bot.py --set-token 'НОВЫЙ'` |
| `404 Not Found` | токен битый, бот удалён, или неверный `API_BASE` | проверьте `BOT_TOKEN` и `API_BASE` |
| `400 Bad Request: message to delete not found` | сообщение уже удалено / удалил собеседник | это норма: бот логирует и продолжает |
| `400 Bad Request: message can't be deleted` | старше 48 ч, служебное, или нет прав (бизнес-чат) | для Business включите право **Delete Received Messages**; для групп — админку |
| `403 Forbidden: bot was blocked by the user` | пользователь заблокировал бота | разблокировать; бот продолжит работать для остальных |
| `403 Forbidden: bot can't initiate conversation with a user` | бот пишет первым | пользователь должен сам нажать `/start` |
| `400 Bad Request: not enough rights to send text messages to the chat` | бот не админ/запрещено писать | в группе — права, в бизнесе — `can_reply` |
| `429 Too Many Requests: retry after N` | флуд-лимит | ожидание автоматическое; увеличьте `COOLDOWN_SECONDS` / уменьшите `MAX_TOPS_PER_MINUTE` |
| `400 Bad Request: can't parse entities` | сломан HTML в `TOP_HEADER`, `TOP_FOOTER`, `ITEM_TEMPLATE` | закройте теги или `PARSE_MODE=none` |
| `400 Bad Request: message is too long` | >4096 символов (эмодзи = 2) | уменьшите `TOP_LIMIT`; бот режет сам, но шаблон может это ломать |
| `400 Bad Request: business_connection_id is invalid` | бот отключён в Telegram Business | заново подключите в Настройки → Telegram Business → Чат-боты |
| `400 Bad Request: chat not found` | неверный `chat_id` (в `ALLOWED_*` или при ручной отправке) | узнайте ID командой `/id` |
| `409` и в логе «уже запущен другой экземпляр» | второй процесс | `ps aux | grep bot.py`, `pkill -f bot.py` |

### Если `--check` пишет `401 Unauthorized`

Это единственная ошибка, которую нельзя «починить» на стороне бота: Telegram не признаёт
токен. Причины: токен отозван (`/revoke`), перевыпущен, бот удалён у BotFather, либо строка
скопирована с лишним символом.

Порядок:

1. @BotFather → `/mybots` → ваш бот → **API Token** → **Revoke current token** (или Show).
2. Скопируйте новый токен целиком, вид `123456789:AAH...`.
3. В Termux:

```bash
cd ~/top-bot
python3 bot.py --set-token '123456789:AAH...'   # запишет в .env и сразу проверит getMe
```

Если версия бота старая и `--set-token` ещё нет:

```bash
sed -i 's|^BOT_TOKEN=.*|BOT_TOKEN=123456789:AAH...|' ~/top-bot/.env
chmod 600 ~/top-bot/.env
python3 ~/top-bot/bot.py --check
```

4. Должно появиться `[ok] Токен рабочий: @ваш_бот`, а `--check` — `Итог: всё готово к запуску`.

> `404 Not Found` — это другое: токен синтаксически верный, но бота удалили или неверен
> `API_BASE`. `401` — именно недействительный токен.

### «Бот молчит» — чек-лист

1. В `bot.log` есть `Слушаю обновления`? Нет → процесс упал, смотрите ошибку выше.
2. Отправляли `/top` именно **боту в личку**, а не в избранное/канал?
3. `MODE=private`, а команда пришла в бизнес-чат (или наоборот) — бот проигнорирует.
4. В `.env` заполнены `ALLOWED_USER_IDS`/`ALLOWED_CHAT_IDS` без вашего ID?
5. Сообщение старше `MAX_MESSAGE_AGE_SECONDS` (бот лежал) — отправьте заново.
6. Сработал кулдаун (`COOLDOWN_SECONDS`) или лимит `MAX_TOPS_PER_MINUTE` — подождите.
7. Для групп: `ALLOW_GROUPS=false` (по умолчанию), нужен `/top@ваш_бот` и админка.
8. Для бизнеса: команда должна идти **от клиента**, право `can_reply` включено.
9. Не запущены ли два процесса (409) и не занят ли токен другим ботом.
10. Проверьте время на телефоне: сильное расхождение ломает TLS/подпись.

---

## Ограничения Telegram

Это не баги бота, а правила платформы:

- **48 часов** — максимальный возраст сообщения, которое можно удалить.
- **Бот не может редактировать ваши сообщения.** Только удалить и отправить новое.
- В **группах** удалять чужие сообщения бот может только админом с правом «Удалять сообщения».
- В **бизнес-чате** удаление чужого сообщения требует права `can_delete_all_messages`
  («Delete Received Messages»).
- **4096 символов** в сообщении — бот режет длинный топ на части.
- Лимиты: ~30 сообщений/с на бота, ~1 сообщение/с в один чат, 20 сообщений/мин в группу.
- Бот **не может написать первым** тому, кто не нажимал `/start`.
- Бизнес-бот отвечает только в чатах, где было входящее сообщение за последние **24 часа**.
- **Bot API не умеет создавать/принимать офферы на покупку NFT-подарков** — это только
  клиентские MTProto-методы (`payments.sendStarGiftOffer` / `resolveStarGiftOffer`).
  Подробный разбор — в [ANALYSIS.md](ANALYSIS.md).
- Username бота обязан заканчиваться на `bot`; менять username можно 2 раза в сутки.

---

## Безопасность

- Токен = полный доступ к боту. Храните только в `.env` с правами `600`
  (`chmod 600 .env`), не публикуйте в Git — `.env` уже в `.gitignore`.
- Утёк токен → BotFather `/revoke` → впишите новый в `.env` → перезапустите бота.
- Не запускайте скачанные «сборки» ботов с готовыми токенами.
- `INSECURE_TLS=true` отключает проверку сертификатов — только на своём устройстве и временно.
- `DRY_RUN=true` удобен для отладки, но не держите его на боевом токене долго: бот всё равно
  «съедает» апдейты и не отвечает людям.
- Скрипт не использует ваш аккаунт (никаких session-файлов MTProto), поэтому его нельзя
  «угнать» как userbot и на него не прилетит бан аккаунта.

---

## VPS и systemd

Тот же `bot.py` отлично живёт на любом Linux-сервере (Python 3.9+):

```bash
sudo mkdir -p /opt/top-bot
sudo cp bot.py top.json .env /opt/top-bot/
sudo chown -R topbot:topbot /opt/top-bot
sudo chmod 600 /opt/top-bot/.env
```

`/etc/systemd/system/topbot.service`:

```ini
[Unit]
Description=Telegram TOP bot
After=network-online.target

[Service]
Type=simple
User=topbot
WorkingDirectory=/opt/top-bot
ExecStart=/usr/bin/python3 /opt/top-bot/bot.py
Restart=always
RestartSec=5
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now topbot
journalctl -u topbot -f
```

---

## FAQ

**Нужен ли Telegram Premium?**
Для обычного бота в ЛС — нет. Для Telegram Business — обычно да (у владельца аккаунта).

**Можно ли без телефона?**
Да, на VPS (см. выше). На телефоне всегда есть риск, что Android убьёт процесс.

**Как сделать список без заголовка?**
`TOP_HEADER=` (пусто) в `.env`.

**Как сделать пустые строки между пунктами?**
`ITEM_SEPARATOR=\n\n`.

**Как добавить очки/баллы?**
`top.json` с объектами `{"name": "...", "score": 100}` или `top.txt` строкой `Имя | 100`.
Сортировка — `SORT_BY_SCORE=true`.

**Как сделать несколько команд (`/top`, `/top10`)?**
`COMMAND=/top,/top10`. Обе покажут один и тот же список (разный лимит — только отдельными
запусками/файлами).

**Можно ли не удалять `/top`?**
Да: `DELETE_COMMAND=false`.

**Будут ли копиться старые топы?**
По умолчанию да. `DELETE_PREVIOUS_TOPS=true` — бот удалит свой прошлый топ перед новым
(в бизнес-чате для этого тоже нужно право удаления).

**Почему бот не отвечает в группе?**
`ALLOW_GROUPS=false` по умолчанию + нужен `/top@имя_бота` + права админа на удаление.

**Как обновить бота, не потеряв настройки?**
Скопируйте новый `bot.py` поверх старого. `.env`, `top.json`, `state.json` не трогаются.

**Сколько ресурсов ест?**
Python-процесс ~25–40 МБ RSS, CPU почти ноль (long polling спит 30 с).

---

## Под капотом

```
Telegram
   │  message / business_message
   ▼
getUpdates (long polling, offset из state.json)
   │
   ├─ фильтры: режим, возраст, whitelist, кулдаун, лимит
   ▼
deleteMessage / deleteBusinessMessages   ← /top удалён
   ▼
sendMessage(новый текст, HTML, без превью) ← новое сообщение
   ▼
state.json (offset, id топов, бизнес-подключения, статистика)
```

Ключевые модули в `bot.py`:

- `TelegramAPI` — JSON-транспорт, ретраи, FloodWait, понятные ошибки;
- `Bot` — разбор апдейтов, права, антифлуд, удаление/отправка;
- `TopData` — чтение/валидация/рендер списка;
- `State` — атомарное сохранение прогресса;
- `FakeTelegramAPI` — тесты без сети.

Лицензия: как у репозитория (GPL-3.0), см. корневой `LICENSE`.
