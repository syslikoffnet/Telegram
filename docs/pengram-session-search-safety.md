# Pengram: session / account / settings-search proof log

Дата: 2026-10-22. Исходный коммит: `26237c3399a7dc6183abd5f158151457240c4268`. **Статус: частичный диагностический этап, НЕ закрытие трёх дефектов / НЕ полный DoD.**

## Безопасная исходная точка

Восстановленный HEAD был `73091c11`, но все 106 изменённых/неотслеживаемых файлов побайтово совпали с серверным `26237c339`. До fast-forward сохранены tar.gz, SHA-256 manifest и binary patch в `/home/user/pengram-safety/`; дополнительно оставлен safety stash. После fast-forward проверены SHA-256 всех 106 файлов, дерево было чистым. Не применялись reset/clean/rebase. Нового PR и merge нет; APK publication workflow не менялся.

## PROOF LOG: факты отдельно от гипотез

| Симптом / риск | Механизм и точное место | Доказательство до изменения | Действие / тест / предел доказательства |
|---|---|---|---|
| Ключ другого аккаунта импортируется поверх занятого слота | `TMessagesProj/src/main/java/org/telegram/messenger/PengramSessionLogin.java:133` (baseline): native cleanup непосредственно перед importAuthorization; start не проверял UserConfig / retained local copy | Новый session-safety тест на baseline: exit 1, `occupied slot is not rejected before starting import` | `TMessagesProj/src/main/java/org/telegram/messenger/PengramSessionLogin.java:113`: `slot_busy` до cancel/cleanup/import; повторная проверка connect и ответа. Тест стал зелёным. **Не доказано**, что именно это произошло у пользователя; атомарная замена/очистка БД не реализована |
| Старый ответ импорта затрагивает новый flow | baseline callback проверял только cancelled; активный flow/занятость слота не проверялись | Чтение callback на baseline | Ответ переведён на UI thread, проверки cancelled/active/slot до setUserId и cleanup. volatile для межпоточных флагов. Source contract, не Android race test |
| Действие экрана A попадает в глобально выбранный B | baseline DialogsActivity:2234/5300/5900/13983 | Четыре конкретных getInstance(UserConfig.selectedAccount) внутри текущего экрана | Теперь currentAccount: notifications / stories / status / attach-bots. Тест запрещает selectedAccount во всём этом файле. Это не доказательство смешения сохранённых диалогов |
| История неявно зависит от глобального аккаунта | baseline PengramHistory:1381–1392 | Две legacy-перегрузки; один актуальный внешний getEntries-callsite уже передаёт currentAccount | Удалены обе неоднозначные перегрузки; остался explicit account API (`TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1382`). Старый тест усилен: legacy delegation запрещена, explicit signature обязательна |
| Пропавшая отправка: ghost delay или revoked/localOnly? | `TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java:8193`, `TMessagesProj/src/main/java/org/telegram/messenger/PengramLocalKeep.java:68`, `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java:895` | В коде: 12/20 секунд отложки; memory-only localOnly; logout возвращается после keepRevokedAccount; cleanup не отменяет этот таймер | Добавлены logout timestamp/userId, import-attempt previousUserId, pending-marker count и экран всех 16 слотов. **Причина account A не установлена; поведение отправки пока НЕ исправлено** |
| Обфусцированный createView NPE 71789 | Точного mapping/необфусцированного debug stack/нажатой записи нет | Поиск файлов checkout и первых 100 GitHub artifacts не нашёл mapping/71789; это не доказательство отсутствия архивов вне этих источников. BaseFragment по умолчанию возвращает null; отсутствие super.createView не объявляется причиной | Новый search-crash тест baseline: exit 1, `createView failure lacks persistent route/phase diagnostics`. Теперь `TMessagesProj/src/main/java/org/telegram/ui/PengramSearchMetadata.java:159` сохраняет route; `TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BaseFragment.java:288` — фазу/цель/наличие root. Исходное исключение повторно выбрасывается: **crash НЕ исправлен** |

### Проверенные опровержения / уже существующие защиты

- Утверждение «native_resumeNetwork нигде нет» неверно для текущего дерева: ConnectionsManager:786 (`resumeNetworkMaybe`), :851 (обычный resume). Это не доказывает валидность отозванного ключа и не решает UI local-only.
- `userId` и `authKeyId` — разные идентификаторы, их равенство не проверялось и не вводилось.
- PengramHistory уже DB_VERSION=8, account indexes уже есть (:172–174); повторная миграция 7→8 не добавлялась.
- PengramAccountOrder сортирует слоты, а не переносит данные аккаунтов. Формат slot:userId пока не внедрён.
- `List.removeIf` не изменялся. `AndroidUtilities.scrollToFragmentRow` не изменялся. JNI/ключи API/шифрование не менялись.

## Что собирает диагностика

Открыть: **Настройки Pengram → О программе → Диагностика**. Обновление раз в секунду, только когда TextView прикреплён к окну; callback отменяется при detach/destroy. Кнопка копирует актуальный снимок, включая user IDs (об этом есть предупреждение RU/EN). Нет фонового polling, отправки отчёта, session bytes, exception messages или содержимого переписки.

Для каждого слота: userId, activated, connectionState, localOnly, pendingGhost, lastLogout/lastLogoutUser, lastImportAttempt/previousImportUser. Общие: версия, selectedAccount, ghostDelay, задержки 12000/20000 мс. connectionState=-1 означает, что Java manager не создан; чтение не создаёт сеть неактивного слота.

**authKeyPresent=UNKNOWN**: Java API не даёт нативный getter. Это явно неполное требование A5, не подставленный false/true. PendingGhost — размер существующего identity-set отложенных запросов, не durable очередь, не timer ledger и не подтверждение отправки. После process death счётчик не восстанавливается.

Поиск сохраняет target с необфусцируемым строковым именем одной из трёх целей, resource/section/group/kind, наличие sourceLayout/sourceAttached и timestamp. Сбой createView/onViewCreated этих трёх экранов сохраняет цель, phase, тип исключения, account, root-present и timestamp; stack идёт в FileLog. Эти два события **не считаются автоматически связанными**: необходимо сравнить время/цель. Последняя запись синхронно commit-ится перед возможным падением. Ошибка диагностического writer не подменяет исходный crash. Это не deobfuscation старого APK.

## Матрица незавершённых требований

| Область | Статус |
|---|---|
| R1–R9: воспроизведение A/B, durable pause/resume/isPaused, LOCAL_ONLY banner/read-only/error UI, pending persistence/cancellation/dedup/session guards | **НЕ ЗАВЕРШЕНО**. Только диагностические hooks/count; существующее отправочное поведение сохранено |
| A5: все slots + copy + realtime | Реализовано чтение доступных Java данных; authKeyPresent UNKNOWN, pending не durable. Полным A5 не считается |
| R10–R15: slot isolation | Частично: fail-closed busy-slot, stale-flow checks, четыре callsite, explicit history API. Нет подтверждённой replacement flow, PengramSlotReset, disk/DB barrier, startup key barrier, account-order identity migration |
| P2 / mapping 71789 | **БЛОКЕР:** точного mapping или debug reproduction нет. Старую строку 134 не сопоставляли с текущим кодом |
| C1–C8: live-top navigation, guarded fallback, 12-root audit/fixes, BaseFragment back fallback, index guards, lifecycle highlight/dedup | **НЕ ЗАВЕРШЕНО**. Только instrumentation; catch в BaseFragment перекидывает original exception, не реализует back fallback |

## Причина каждого изменённого файла

| Файл | Почему изменён |
|---|---|
| messenger/PengramSessionLogin.java | Предотвратить конкретный путь import-over-activated-slot; UI-serialized callback, stale-flow guards, volatile visibility |
| ui/LoginActivity.java | Понятный локализованный slot_busy вместо network error |
| messenger/PengramHistory.java | Убрать API с неявным selectedAccount |
| ui/DialogsActivity.java | Четыре действия теперь используют account экрана |
| messenger/PengramDiagnostics.java (новый) | Локальное сохранение минимальных событий и снимок без секретов |
| messenger/SendMessagesHelper.java | Getter размера существующих ghost markers без создания helper |
| tgnet/ConnectionsManager.java | Peek Java connectionState без создания manager; запись onLogout |
| ui/ActionBar/BaseFragment.java | Сохранить create phase на трёх целях без проглатывания original exception |
| ui/PengramSearchMetadata.java | Сохранить фактическую индексную запись перед navigation |
| ui/PengramDiagnosticsActivity.java (новый) | On-demand экран, attach-only refresh, copy |
| ui/PengramSettingsActivity.java | Вход в диагностику из About |
| values/strings.xml, values-ru/strings.xml | RU/EN заголовок/описание/copy/slot_busy |
| ui/PengramSearchIndex.java | Результат gen-search-index.py; не редактировался вручную |
| docs/pengram-audit-inventory.json | Перегенерирован после сдвига строк/новых методов; обязателен existing audit-clean test |
| scripts/test-pengram-session-safety.py (новый) | Source contracts guard/routing/diagnostics; не симуляция Telegram auth |
| scripts/test-pengram-settings-search-crash.py (новый) | Source contracts crash-triage persistence/lifecycle; НЕ воспроизведение NPE |
| scripts/test-pengram-history-account-safety.py | Усилен контракт explicit API; старое ожидание legacy selectedAccount удалено вместе с опасным API |
| docs/pengram-session-search-safety.md | Этот proof log, пределы выполнения, все suite final lines и manual matrix |

## Автоматическая проверка

Два новых теста действительно запущены до изменений на baseline, оба exit 1; полные исходные traceback в `/home/user/pengram-safety/proof/session-red.log` и `search-red.log`. После реализации оба exit 0, печатают ровно одну заключительную OK-строку. **Это red→green контрактов защиты/инструментации, а не red→green пользовательского NPE или сценариев R1–R15.**

Первый полный sweep: 55, один FAIL audit-clean (нужно пересоздать inventory). После `gen-pengram-audit.py`: **55 scripts, 0 nonzero exits, 28.6 s**. В локальной среде нет javac: три JVM-подпроверки честно SKIP; Python/source contracts не заменяют Android/JVM выполнение. Preflight уже содержит полный glob `scripts/test-pengram-*.py`, поэтому автоматически включает оба новых теста; workflow не менялся. Текущий список 55 скриптов приведён ниже.

Java/resource CI и APK для этого изменения: **PENDING**, ещё не подтверждены. Старые успешные runs 39735275998 / APK35239735275990 относятся к baseline26237c339, не к этому изменению. Старый APK35139734279419 теперь также подтверждён SUCCESS; это не новая сборка.

## Ручная матрица (ровно 10 сценариев)

| № | Сценарий / ожидаемая проверка | Фактический результат |
|---|---|---|
| 1 | A не отправляет / B отправляет: сравнить diagnostics до/после одинакового текста | NOT RUN — нет устройства/сессий A/B |
| 2 | Ghost delay off/on: 12s text / 20s media и UI indicator | NOT RUN — таймеры на Android не выполнялись; indicator не реализован |
| 3 | 401/revoked: retained cache, LOCAL_ONLY, error вместо вечной отправки | NOT RUN — нет отозванной тестовой сессии; UI/error fix не реализован |
| 4 | Cleanup/switch/delay-off/restart: queue отменена/не воскресает | NOT RUN — durable cancellation не реализована |
| 5 | Импорт в занятый обычный слот | NOT RUN на устройстве — source guard/test есть; реальный импорт не делался |
| 6 | Импорт в LOCAL_ONLY слот: cache сохранён до явного replacement | NOT RUN — нет retained-slot fixtures на Android; replacement/reset не реализован |
| 7 | Освобождённый повреждённый слот: старые DB/диалоги не попадают новому user | NOT RUN — DB/startup barrier не реализован |
| 8 | История и четыре Dialogs actions при account switch/reorder | NOT RUN на устройстве — source contracts и существующие Python DB checks не считаются ручным результатом |
| 9 | Cold settings search → три target routes, back/double tap, Xiaomi Android14 | NOT RUN — нет устройства, exact71789mapping и debug reproduced stack |
| 10 | Диагностика: visible refresh/copy, leave/reopen, после crash сохраняются phase/route | NOT RUN на устройстве — проверены source contracts, lifecycle не исполнялся Android |

## Полные заключительные строки локальных 55 suite

```text
test-pengram-account-order.py [exit=0]: account order: drag in settings, one order for every account list: OK
test-pengram-audit-clean.py [exit=0]: audit regression contracts + reproducible inventory PASS; semantic key/dispatch/RTL review remains open
test-pengram-bot-login.py [exit=0]: bot token login: OK
test-pengram-chat-menu-live.py [exit=0]: chat menu: all items editable, popup rebuilds in place, island refreshes live: OK
test-pengram-community.py [exit=0]: community setup: dry-run by default, forum topics, pinned texts, 10 avatars, secrets kept out: OK
test-pengram-deleted-edited.py [exit=0]: HOST SQLite medians ms (not device): {"before_indexes": {"dialog_count": 0.887, "saved_page": 2.598, "search_page": 3.316}, "after_indexes": {"dialog_count": 0.412, "saved_page": 0.029, "search_page": 0.081}}
test-pengram-deleted-marks.py [exit=0]: deleted marks: update -> keepInChat -> marks -> cache -> repaint, no restart and no re-entering the chat: OK
test-pengram-device-name.py [exit=0]: device name spoofing: presets, custom text, login screen and restart: OK
test-pengram-device-os.py [exit=0]: device spoof: OS version + device-type badge: OK
test-pengram-device-platform.py [exit=0]: device spoof: model + system version + app version + lang_pack + params, custom input: OK
test-pengram-edit-chain.py [exit=0]: Edit capture, recovery, history rendering and composer scroll coordinates: OK
test-pengram-edited-media.py [exit=0]: Edited-media archive and edit-history controls: OK
test-pengram-emoji-security.py [exit=0]: emoji security: bounded verified decoding, no executable deserialization/shell: OK
test-pengram-forward-quotes.py [exit=0]: forwarding and quote geometry: OK
test-pengram-forward-target.py [exit=0]: forwarding: one send per panel, per-chat copies, no stale panel: OK
test-pengram-glass-drag.py [exit=0]: liquid glass: transparent provider, real lens and press-drag response: OK
test-pengram-glass-live.py [exit=0]: glass: settings, signature, guarded recreate, reversible header, live dispatch: OK
test-pengram-glass-settings.py [exit=0]: liquid glass settings: knobs, wiring and search home: OK
test-pengram-history-account-safety.py [exit=0]: Archive account isolation and copy-routing regressions passed
test-pengram-history-search.py [exit=0]: Archived-message search and paging checks: OK
test-pengram-local-keep.py [exit=0]: local copy: ban keeps dialog, server deletions keep messages, revoked session keeps account offline: OK
test-pengram-lyrics-header.py [exit=0]: lyrics header: frames keep coming, late text picked up, profile pill text safe: OK
test-pengram-lyrics-wiring.py [exit=0]: Lyric switches and playback wiring: OK
test-pengram-md3-glass-coverage.py [exit=0]: MD3/glass: shared search field, tonal menus/history, reversible sheets, real backdrop capture and API fallback: OK
test-pengram-mention-all.py [exit=0]: mention all: 21 strings, cap 100, cooldown, groups only, off by default, 3 styles (full/usernames/hidden): OK
test-pengram-mention-name.py [exit=0]: mention by contact name: long press, mention_name entity, setting, both locales: OK
test-pengram-menu-alignment.py [exit=0]: menu alignment: visible-edge rounding, icon column and MD3 container: OK
test-pengram-message-island.py [exit=0]: message island configuration and menu wiring: OK
test-pengram-name-check.py [exit=0]: exact name check: OK
test-pengram-penguin-alive.py [exit=0]: penguin alive: hemisphere light, fresnel, feather grain, contact shadow, eye saccades: OK
test-pengram-penguin-geometry.py [exit=0]: penguin geometry: 75 density/size cases + static camera envelopes PASS; animated/device acceptance NOT RUN
test-pengram-penguin-header.py [exit=0]: penguin header: full row width, centred content, square viewport, size capped: OK
test-pengram-penguin-visibility.py [exit=0]: Penguin cover ownership, stale callback model, fallback, neutral background, soft/off/clip contracts: PASS; device NOT RUN
test-pengram-player-wiring.py [exit=0]: Player settings, artwork, compact header, swipe and queue/chat wiring: OK
test-pengram-profile-smooth.py [exit=0]: own profile smoothness + large header penguin: OK
test-pengram-protection.py [exit=0]: Pengram protection: pin goes keystore->CMake->libtmessages, guard wired, strings encoded: OK
test-pengram-push-latency.py [exit=0]: push latency: missing FCM detected, keep-alive started, service survives restart: OK
test-pengram-realtime-look.py [exit=0]: chat look: settings apply without re-entering the chat, dead switch wired, hero/preview/penguin render fixes in place: OK
test-pengram-search-destinations.py [exit=0]: search destinations: 402 generated keys, validated versioned history, migration, shared navigation: OK
test-pengram-search-index.py [exit=0]: Pengram search-index source/resource checks: OK (402 rows)
test-pengram-search-navigation.py [exit=0]: merged search: identity, loaded bounds, callback-driven loading: OK
test-pengram-security-wiring.py [exit=0]: Pengram security wiring: OK
test-pengram-send-style.py [exit=0]: Outgoing style and large-emoji routing: OK
test-pengram-session-login.py [exit=0]: session login: read-only parsers (Telethon/Pyrogram/tdata/string), direct key sign-in with one retry, native key export/import, 40 strings: OK
test-pengram-session-safety.py [exit=0]: OK: occupied-slot guards, explicit account routing, diagnostic source contracts
test-pengram-settings-create.py [exit=0]: Settings search lazy initialisation contracts PASS; Android cold-open NOT RUN
test-pengram-settings-search-crash.py [exit=0]: OK: persistent search/create-phase diagnostics and attached-only diagnostic screen
test-pengram-settings-search.py [exit=0]: Pengram settings search: relevance, exact jump and history: OK
test-pengram-signer-block.py [exit=0]: signer block: v2/v3 certificates are read from the APK itself: OK
test-pengram-tab-glass.py [exit=0]: liquid glass bottom bar: OK
test-pengram-text-overflow.py [exit=0]: text overflow: block right edge measured for every block, clip safety net armed: OK
test-pengram-text-visibility.py [exit=0]: Extracted lifecycle JVM runner: SKIP (JDK unavailable; model is not Android proof)
test-pengram-ticker-lifecycle.py [exit=0]: ticker: lifecycle, track identity, reusable show, settings: OK
test-pengram-typing-anim.py [exit=0]: Typing source contracts, all modes/styles, lifecycle, settings and 766 Unicode reference vectors: PASS
test-pengram-visual-editor.py [exit=0]: Pengram visual editor source/resource/safety invariants: OK
```

Локальные JVM skips: settings-create JVM model; text-visibility extracted lifecycle JVM runner; typing-anim production Java UAX runner. Реальное CI должно проверить их с JDK17.
