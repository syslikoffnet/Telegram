# Аудит удалённых и изменённых сообщений — 2026-10-22

**Статус: укрепляющий патч, НЕ полная приёмка всего запроса.** База `72cabd9`, ветка
`arena/42ea70d0-telegram`, draft PR #5 без merge. Подтверждённые исправления ниже; открытые
части перечислены явно. Устройства/эмулятора нет. Ни один пункт device-матрицы не принят.

## Что сделано и что обещает этот патч

- Новая запись удалённого и durable mark коммитятся вместе; speculative UI/cache mark убран.
  Ошибка insert/mark/commit не должна публиковать новую метку. Public save/mark перехватывают
  и ошибки submission. Server deletion processing не отключали и не добавляли getMessages.
- При LocalKeep snapshot записывается даже при выключенном spy-архиве: иначе обещание метки
  нельзя выполнить. **Это изменение поведения явно описано в настройках.** Оно обратимо
  выключением LocalKeep/retention для новых захватов; старые записи не удаляются автоматически.
- E2E, TTL/self-destruct и edit_hide новые снимки исключены единой политикой. Старая БД не
  «очищается ради приватности»: тихое удаление старых данных запрещено требованием сохранности.
- Глобальный архив больше не делает SQL на UI и не обрезается на 500: фоновые страницы,
  честный filtered count, generation/destroy guards, retry UI. Сортировка глобального списка
  теперь по порядку сохранения (`id DESC`), а не дате сообщения. Поиск ВСЕГО глобального списка
  отдельным search-field в этой итерации не реализован; dialog-search сохранён.
- Индексы v8, WAL/NORMAL, периодический quick_check; corruption handler больше не удаляет
  оригинал. Fail-closed read error — это НЕ автоматическое восстановление или новый пустой архив.
- UI-замок использует существующий код Telegram/настроенную биометрию, default OFF. БД остаётся
  plaintext. Замок защищает **два экрана архива**, не bubbles/ghost search/exported files.
  На возврате требуется повторный код. Изменения отправки/шифрования/подписи/API ID отсутствуют;
  в CopySender изменён лишь запрет повторного скачивания архивного источника.

## Таблица наблюдений

`72cabd9:` — положение удалённого кода в базе. Остальные пути/строки — итог патча.
Проверка `deleted-edited` — новый suite, преимущественно source contracts + host SQLite.
Это НЕ выполнение Android Java/GL/UI и НЕ доказательство всех инвариантов в рантайме.

| # | Файл:строка | Что | Категория | Решение | Тест | Устройство |
|---|---|---|---|---|---|---|
| 1 | `72cabd9:TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:219` | H1: два legacy-ключа и всегда-true getter не имели потребителей. | мёртвый API | Удалить: KEY_RESEND_AS_MINE/KEY_RESEND_MENU/isResendDeletedAsMine; отправку не переписывали. | deleted-edited | не проверено |
| 2 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:5806` | H2: BTN_HIST_PROFILE уже удалён предыдущим аудитом, остальные BTN_HIST имеют case. | не дефект этой базы | Оставить текущие обработчики; не изобретать новую строку. Проверено новым suite. | deleted-edited | не проверено |
| 3 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:1120` | H3: доступ к истории подменял NONE карандашом. | вид | Починить: marking=false или MARK_EDIT_NONE дают 0, доступ к архиву отдельно. | deleted-edited | не проверено |
| 4 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:1100` | H3/realtime: подпись вида не включала исходный markEdited/режимы доступа. | realtime | Доделать: markEdited, editedMark, mark/menu access и forceDeleteForAll в signature. | deleted-edited | не проверено |
| 5 | `TMessagesProj/src/main/java/org/telegram/ui/PengramHistoryActivity.java:47` | H4: глобальный архив молча читал первые 500. | архив | Починить: 100 лёгких строк на страницу, id-keyset, автодогрузка и кнопка. | deleted-edited | не проверено |
| 6 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1265` | H4: reload глобального архива читал SQLite на UI. | потоки | Починить: весь count/page на history executor; callback на UI. | deleted-edited | не проверено |
| 7 | `TMessagesProj/src/main/java/org/telegram/ui/PengramHistoryActivity.java:191` | H4: заголовок показывал число загруженных, не всех строк. | счётчик | Починить: filtered COUNT(account,dialog,action) из БД. | deleted-edited | не проверено |
| 8 | `TMessagesProj/src/main/java/org/telegram/ui/PengramHistoryActivity.java:152` | Фильтр/удаление/destroy могли принять устаревшую страницу. | race | Доделать: generation и destroyed, лёгкие строки без blob. | deleted-edited | не проверено |
| 9 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:171` | H5: отсутствовали индексы по account. | БД | Починить: v8 аддитивно добавляет 3 индекса; host SQLite benchmark ниже, Android-миграция не замерена. | deleted-edited | не проверено |
| 10 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:823` | H6: секретные чаты могли попадать в незашифрованную БД. | приватность | Починить новые захваты: E2E не архивируется. Старые записи сознательно не удалены. | deleted-edited | не проверено |
| 11 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:825` | Самоуничтожающиеся/TTL-снимки могли переживать волю автора. | приватность | Починить новые захваты: ttl_period/media.ttl_seconds исключены; серверный TTL не менялся. | deleted-edited | не проверено |
| 12 | `TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java:11480` | Скрытые edit_hide правки сохранялись как обычные. | приватность | Починить: единая политика отсекает новые hidden edits. | deleted-edited | не проверено |
| 13 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:267` | Предварительная своя правка тоже могла записать E2E/TTL. | захват | Починить: общая политика до сериализации/записи pending. | deleted-edited | не проверено |
| 14 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:424` | H7: метка могла пережить провал insert. | целостность | Починить: snapshot + mark в одной транзакции; кэш только после endTransaction. | deleted-edited | не проверено |
| 15 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:760` | H7: публичный markDeleted спекулятивно менял кэш. | целостность | Починить: executor, hasEntry(ACTION_DELETED), transaction, committed publish; fail-soft submission. | deleted-edited | не проверено |
| 16 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:893` | Старые orphan-метки загружались как сохранённые сообщения. | целостность | Починить чтение: legacy marks без deleted row не загружаются; исходные строки БД не стирались. | deleted-edited | не проверено |
| 17 | `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java:2404` | Нет строки messages_v2, но MessageObject есть в открытом чате. | захват | Доделать memory snapshot для retained-сообщений; без запросов по id. Остальные memory-only пути открыты. | deleted-edited | не проверено |
| 18 | `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java:2405` | Чат ставил pengramDeleted=true до подтверждения записи. | целостность | Починить: брать только committed cache, listener обновляет после сохранения. | deleted-edited | не проверено |
| 19 | `TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java:14697` | LocalKeep раньше ставил метку даже при выключенном архиве. | источник истины | Починить: retained-сообщение тоже имеет snapshot; новая политика явно описана в UI. | deleted-edited | не проверено |
| 20 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:55` | H8: dedup check+insert не имел UNIQUE. | дедуп | Оставить кодовый dedup: фактические вставки идут single executor; правки с одним messageId законны. Мультипроцесс не принят. | deleted-edited | не проверено |
| 21 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1230` | H9: Cyrillic/Unicode поиск имел SQL без LIMIT. | поиск | Починить searchDeleted: страницы 256, курсор date+id, остановка по matches. Worst-case всё ещё O(N). | deleted-edited | не проверено |
| 22 | `TMessagesProj/src/main/java/org/telegram/ui/PengramHistoryChatActivity.java:313` | H10: архив чата читает только текущий dialogId; полная миграция не доказана. | открытая гипотеза | Оставить OPEN: нельзя механически менять id — old-group и channel messageId могут совпасть. | deleted-edited | не проверено |
| 23 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:110` | H11: журнал/проверка целостности не были явными. | БД | Доделать WAL/NORMAL, quick_check(1) раз в 10 открытий; стоимость на Android не измерена. | deleted-edited | не проверено |
| 24 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:107` | Default Android corruption handler мог удалить единственную БД. | сохранность | Починить fail-closed: исходный файл сохраняется, generic FileLog, read error UI. Автовосстановление/карантин не реализованы. | deleted-edited | не проверено |
| 25 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1903` | H12: clear/deleteEntry оставляли связанные файлы. | файлы | Доделать доказуемую очистку private prev_media_path/attachPath и saved_media после commit. Gallery/legacy unknown association не удаляем. | deleted-edited | не проверено |
| 26 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1906` | Битый surviving snapshot не позволяет доказать отсутствие ссылки на файл. | защита данных | Оставить файл при неопределённости, а не удалять возможную последнюю копию. Полный orphan sweep открыт. | deleted-edited | не проверено |
| 27 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1864` | Удаление записи оставляло старую метку в памяти. | realtime | Починить: сброс/перезагрузка затронутых кэшей после commit; открытый чат получает change event. | deleted-edited | не проверено |
| 28 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1568` | Автолимит/keep-days мог оставить orphan-метки. | целостность | Доделать DB/cache reconciliation после pruning. Полная файловая уборка pruning пока не сделана. | deleted-edited | не проверено |
| 29 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1762` | Настройка лимита прямо вызывала SQL с UI-потока. | потоки | Починить: UI вызов переводится на executor; фоновая цепочка остаётся последовательной. | deleted-edited | не проверено |
| 30 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1739` | VACUUM force обходил суточный лимит и не учитывал питание/место. | обслуживание | Починить: суточный предел даже force, свободное место, low-battery unplugged skip. | deleted-edited | не проверено |
| 31 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:1927` | Размер БД не учитывал WAL/SHM. | статистика | Починить: сумма трёх файлов; размер медиа по всему диску пока не сертифицирован. | deleted-edited | не проверено |
| 32 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramLocalKeep.java:66` | H14: native_pauseNetwork вызывался многократно. | сессия | Починить идемпотентность и синхронизацию. Resume/authenticated-slot reset + UI статуса пока OPEN. | deleted-edited | не проверено |
| 33 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramChatExport.java:358` | Дополнительно: export local snapshots мог скачать отсутствующий архивный файл. | сеть | Починить: localOnly attachment, никакого download для visitor архива. | deleted-edited | не проверено |
| 34 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramCopySender.java:793` | Копирование архивной старой версии могло запустить обычный downloader. | сеть | Починить только вход downloader: missing local archive media -> failure; сборка/шифрование отправки не менялись. | deleted-edited | не проверено |
| 35 | `TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java:826` | Автозагрузка media MessageObject не различала архивные версии. | сеть | Добавить отказ в сетевой операции для archived/deleted/historySnapshot parent. Все косвенные HTTP/thumbnail пути ещё требуют проверки. | deleted-edited | не проверено |
| 36 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramArchiveGuard.java:89` | У архива не было собственного UI-замка. | доступ | Доделать opt-in passcode/biometric guard двух screens; appLocked не меняется. Без настроенного PIN — fail closed. | deleted-edited | не проверено |
| 37 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramArchiveGuard.java:108` | Preview списка задач мог показать открытый архив. | приватность | Доделать FLAG_SECURE lease на window, re-lock on pause/resume. Конкуренция с другими secure-владельцами требует device-теста. | deleted-edited | не проверено |
| 38 | `TMessagesProj/src/main/AndroidManifest.xml:100` | Android backup уже запрещён для приложения. | не дефект | Оставить: allowBackup=false/fullBackupContent=false; новые manifest-флаги не нужны. | deleted-edited | не проверено |
| 39 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:3195` | Обещания UI не описывали plaintext/E2E/localKeep/export. | объяснение | Доделать EN/RU policy, отдельная строка замка; индекс 401 строка. | deleted-edited | не проверено |
| 40 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:608` | unpack возвращал частичный TL blob при обрыве и не ограничивал раздувание. | повреждения | Починить: null для truncated stream, 32MiB cap, старый raw формат сохранён. | deleted-edited | не проверено |
| 41 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java:69` | Массовые saves могли засыпать UI/listeners перезагрузками; listener-исключение ломало остальные. | события | Доделать coalescing 16ms с гарантированным последним состоянием и отдельным catch каждого listener. | deleted-edited | не проверено |

## Что стало лучше: пять запрошенных метрик

| Метрика | До | После | Доказательство/ограничение |
|---|---|---|---|
| Открытие глобального архива | SQL + до 500 blobs синхронно на UI | worker + страница 100 без blob + полный count | Структурное улучшение; Android ≤300ms не измерено |
| Поиск | Unicode SQL без LIMIT | keyset date/id, 256 строк на cursor, stop по hits | Пиковая выборка ограничена, worst-case CPU всё ещё O(N); Android ≤300ms не измерено |
| Память меток | HashSet по всем посещённым чатам | та же схема, commit-only публикация/очистка после удаления | Размер на 200 чатах не измерен; LRU/eviction не реализованы, улучшение памяти не заявляется |
| Размер БД | v7 без account indexes; stats не учитывает WAL | v8 + 3 индекса/WAL, stats включает db+wal+shm | Данные аддитивны; индексы могут увеличить размер. Android до/после нет, уменьшение не заявляется |
| Файлы-сироты | clear/deleteEntry не удаляли media | cleanup известных private paths после commit, shared references защищены | Число реальных orphan-файлов до/после неизвестно; полный sweep/legacy URI ownership не готовы |

Воспроизводимый **host SQLite**, 20 000 строк, 7 повторов (median ms), один прогон нового suite:
account/dialog COUNT **0.731→0.284**, account/saved page **2.144→0.021**, account/action/date page
**2.858→0.059**. Это desktop Python SQLite in-memory с синтетическими текстами, НЕ Android,
НЕ первичный кадр, НЕ end-to-end Unicode поиск, НЕ измерение миграции гигабайтной БД.
Не выдаётся за выполнение бюджетов 300/300/50ms или 60fps.

## Что сознательно не менял / ещё не готово

- **H10 / миграция basic group→supergroup:** нужна lineage с сохранением `(sourceDialogId,messageId)`;
  перенос всех старых id в новый namespace способен столкнуть разные сообщения. Есть legacy support
  в export и mergeDialogId в deleted search, но полный архив/menu/count/ghost lineage не проверен.
- **H11:** quick_check/fail-closed сохранение оригинала добавлены; quarantine/новая БД/пользовательское
  восстановление не реализованы. Не имитируем ремонт файла молчаливым удалением. Existing corruption
  и ENOSPC должны проверяться на Android. Старые snapshots при смене privacy-политики не стирались.
- **H12:** не удаляли gallery/content URI без доказуемой связи с конкретной записью/аккаунтом;
  saved_media legacy не содержит такого ownership. Файлы неизвестных старых каталогов, неизвестные
  ссылки из повреждённых blobs, pruning-associated media и полный orphan sweep остаются открытыми.
  Cleanup сканирует surviving snapshots на worker; большая очистка может быть медленной.
- **H13:** бесконечный marks-cache не заменён LRU; generation для одновременного countLoading/countDirty
  полностью не внедрён. Кэш-протечки на 200 чатах и гонки user-slot reuse не приняты.
- **H14:** повторная pause устранена. Обратный путь authenticated resume, сброс localOnly при новом
  входе в тот же слот, явный UI статуса не реализованы: нельзя автоматически возобновить отозванную
  сессию на основании одного isClientActivated. Требуется отдельный подтверждённый login hook.
- **Memory capture** покрывает MessageObject в retained-ветке открытого чата, а не все cache-evicted,
  batch, scheduled/TTL/whole-dialog варианты. Нет полного in-memory event snapshot broker.
- **Batch 500:** записи идут последовательно, каждая snapshot+mark атомарна; общей транзакции на
  всю Telegram batch пока нет. Не заявляется бюджет массового удаления или нулевые пропуски.
- **Own optimistic edits/pending recovery:** существующий SendMessagesHelper не изменялся.
  ClearPendingEdit после save всё ещё требует проверки failure/revision-generation цепочки;
  rollback не должен терять последнюю pending-версию. Это открытый риск, не принятый сценарий.
- **UI:** полная глобальная search UI, виртуализация custom EntryView, diff-highlighting, все
  fontScale/RTL/hit-test/альбомы/опросы/стикеры и ghost navigation пока не прошли полный аудит.
  Ноль аллокаций bind не заявляется. Старые defensive comments/TODO чужого кода не вычищались.
- **Edited label:** иконка NONE больше не подменяется; текст edited не связан с этой настройкой.
  Сочетание label+иконка остаётся осознанно независимым. Взаимодействие decorative icon и
  отключённого mark-access требует UX/device-проверки (меню/экран архива остаются отдельными).
- **Network:** исправлены обнаруженные export/copy/FileLoader snapshot download входы.
  Все косвенные HTTP thumbnail/avatar/PhotoViewer/MediaController ветки ещё не проверены
  сетевым interceptor на устройстве. Отсутствие любых сетевых запросов целиком не сертифицировано.
- **Lock:** не шифрует БД и не замещает глобальный app lock; archive export/ghost search не gated.
  Window FLAG_SECURE lease с одновременным другим security owner, rotation/biometric background
  и live сменой настройки требует device-проверки. Не выдаётся за криптографическую защиту.
- **User guard:** приоритет сохранён, save/mark проверяют его до публикации. Legacy guard по одному
  messageId (не account+dialog) не переписывался; это отдельный риск совпадений id/аккаунтов.
- **Privacy TTL policy:** архив больше не сохраняет новые ephemeral/TTL snapshots; исходный серверный
  TTL и AutoDeleteMessagesActivity не изменялись. Уже существующие архивы остаются.
- Archive/global count methods для maintenance намеренно читают все аккаунты, per-dialog SQL
  ограничен account. Глобальная проверка shared file ownership тоже намеренно cross-account.

## Ручная матрица

Все 20 пунктов **□ NOT RUN — нет Android-устройства/эмулятора**. Это не функциональные PASS.

| # | Сценарий | Статус/что проверить |
|---|---|---|
| 1 | Удаление собеседником | □ commit/метка/архив и отказ insert |
| 2 | 50/500 удалений | □ полнота, batch latency; общей транзакции нет |
| 3 | Удалить у всех | □ explicit save-for-myself vs guard; sender не меняли |
| 4 | Удалить у меня | □ абсолютный guard, отсутствие новой метки/записи |
| 5 | Фото/видео/voice | □ private copy, локальное открытие, запрет повторной загрузки |
| 6 | Нет строки Telegram DB | □ memory capture и границы остальных event-путей |
| 7 | Чужая правка | □ цепочка before/after |
| 8 | Своя оптимистичная правка | □ pending + insert failure + recovery |
| 9 | Caption/media edit | □ старый файл, общие ссылки |
| 10 | 5 правок | □ все ревизии и истинный оригинал |
| 11 | Канал | □ edit/date/noforwards |
| 12 | NONE | □ нет значка, независимый archive access |
| 13 | Realtime | □ mark/label/fade/keep/account на обоих открытых экранах |
| 14 | Group migration | □ OPEN lineage/account/message namespace |
| 15 | Secret | □ новые E2E/TTL не записываются, warning виден |
| 16 | Account/logout | □ rows/files/pending/cache/localOnly slot reuse |
| 17 | 10k | □ Android 300/300/50ms, 60fps и память 200 чатов |
| 18 | Мало места/битая БД | □ no throw, rollback, original retained, error UI |
| 19 | RTL/font2 | □ icon hit rect, text/diff/archive layout |
| 20 | Export | □ все local snapshots, corrupt blob fallback, предупреждение и отсутствие downloads |

## Проверки

- Новый `scripts/test-pengram-deleted-edited.py`: source contracts; additive v1-column/index
  model и повторный index migration; rollback при ошибке marks insert; legal edited duplicates;
  20k-row keyset paging без потерь/повторов; host SQL medians. Не исполняет production Java.
- Все `scripts/test-pengram-*.py`: **50/50 PASS** на текущем патче; прежние ожидания v7,
  forced pencil и speculative cache publication заменены контрактами нового поведения.
- `gen-search-index.py`: **401** строка (добавлен реальный opt-in lock). Локализации EN/RU.
  `gen-pengram-audit.py` также обновлён, это лексическая инвентаризация, не семантический сертификат.
- Java syntax parse / XML parse / git diff --check выполняются перед push. Реальный javac/CI
  для данного патча проверяется отдельно; предыдущий SUCCESS 39724354122 относится к `72cabd9`,
  а не к этим изменениям. Нет APK/device/performance acceptance для этого патча.

**Итог:** этот патч укрепляет ряд подтверждённых дыр, но полный запрос и критерии готовности
НЕ закрыты. Особо блокируют полную приёмку H10, recovery, legacy media ownership/sweep, H13/H14,
pending-edit failure, batch/memory completeness, all-network-path checks и device-матрица.
