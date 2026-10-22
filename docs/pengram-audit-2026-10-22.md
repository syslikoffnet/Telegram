# Pengram audit — 2026-10-22 (промежуточный, не акт полной приёмки)

База проверки: `45edea6`, ветка `arena/42ea70d0-telegram`, PR #5. Merge не выполнялся.

**Статус: исправлен подтверждённый набор дефектов, но полный аудит 1.1–1.9 НЕ завершён.**
Таблица содержит 60 проверенных наблюдений, а не 60 якобы найденных багов. В ней отдельно
отмечены исправления, ложные кандидаты, оставленные API и открытые части запроса.
Устройство/APK/Android javac недоступны. Нет доказательств визуальных 56–64dp в компактной
шапке, >=8% полей во всех анимированных позах или работы на всех сочетаниях темы/шрифта/RTL.

## Что исправлено

Главная причина огромного компактного пингвина — **двойное применение density**:
`dp(72)` передавался в `LayoutHelper.createLinear/createFrame`, которые ещё раз переводят
положительные размеры в px. На density=3 компактный контейнер получался 216dp, не 72dp.
Полное превью также смешивало dp/px в clamp и меняло систему единиц после движения слайдера.
Теперь контейнер владеет размером в px, GL и fallback — MATCH_PARENT. Размер большого
превью пересчитывается на каждом шаге слайдера и при measure по фактической ширине.

Камера: fill .82 вместо .97, наружный нижний запас, фактические minY/maxY и XZ-radius
тела/аксессуара, сброс камеры перед первым кадром нового скина. Убрано смешение двух чтений
volatile skin. Исправлены остановка рендера за пределами видимой области, EGL/SurfaceTexture
cleanup, проверка link/swap и возврат fallback. Join выполняется не на UI-потоке;
при зависшем старом GPU-потоке новый остаётся на fallback, а не переиспользует его EGL.
Экстремальное танцевальное squash/flip ещё требует проекционного/device-теста.

Удалены только 5 приватных неиспользуемых методов и 3 константы без UI.
Также удалены 4 оставшихся без потребителей ресурса version/tabState в EN и RU. Открытие очереди,
реальный плеер, голосовые пресеты и публичные JNI-имена не удалялись. Публичные legacy
helpers сознательно оставлены и не выдаются за работающую пользовательскую настройку.

## Воспроизводимая инвентаризация

`python3 scripts/gen-pengram-audit.py` → `docs/pengram-audit-inventory.json`:

- 166 KEY-констант: выражения, объявления и ссылки по всему Java-дереву.
- 170 прямых literal getBool/putInt/getIntCached/visualString и родственных операций.
- 364 имён BTN/SECTION/MSG_ACTION/CHAT_ITEM/MENU_ITEM со ссылками в Pengram-файлах.
- 2089 различных имён объявленных Pengram-методов: лексические количества и до 8 примеров, не полное разрешение перегрузок/receiver.
- 1272 совпадений catch/return/TODO/FIXME/LEFT/RIGHT/opaque-color. Это кандидаты, **не подтверждённые дефекты**.
- Для 19 явно названных методов сохранены все найденные Java/::-совпадения; совпадений имён в `.c/.cpp/.h/.cc` под JNI нет.

Сканер включает объявления/комментарии и не понимает динамически составленные ключи,
reflection, package-private методы без модификаторов или точный receiver. Поэтому он не
заменяет семантическую таблицу «UI writer → persisted key → application reader» и service
whitelist: **эта часть остаётся открытой**. Отсутствие ссылок в этом JSON само по себе не
даёт права удалять настройку. Пять приватных удалений дополнительно проверены по исходникам.

## Наблюдения и решения

Пути ниже относительно корня репозитория. `45edea6:` означает место удалённого объявления
в исходной базе, остальные строки относятся к итоговому рабочему дереву этого патча.
Тест `geometry` означает `scripts/test-pengram-penguin-geometry.py`, остальные сокращения
обозначают `scripts/test-pengram-<name>.py`; `inventory` — поиск ссылок, не runtime-тест.
Колонка «Устройство» везде **не проверено**.

| # | Файл:строка | Наблюдение | Категория | Решение | Проверка | Устройство |
|---|---|---|---|---|---|---|
| 1 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:7040` | Компактный размер dp(72) раньше повторно переводился LayoutHelper в px (density²). | геометрия | Complete: px-параметры контейнера | geometry | не проверено |
| 2 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:7010` | Полное превью смешивало размер в dp с ограничениями в px. | геометрия | Complete: расчёт и LayoutParams в px | geometry | не проверено |
| 3 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:7233` | Применение слайдера раньше задавало px дочерним view, а первоначальная сборка повторно конвертировала их. | realtime | Complete: единый путь updatePenguinSize | geometry | не проверено |
| 4 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:7125` | Ограничение только displaySize не учитывало фактическую ширину строки/split-screen. | геометрия | Complete: measuredWidth минус padding | geometry | не проверено |
| 5 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:7132` | Старый нижний предел 96dp мог превышать доступный экран. | геометрия | Complete: доступный max имеет приоритет | geometry | не проверено |
| 6 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:6947` | Повторное измерение не обновляло размер при смене ширины/ориентации. | realtime | Complete: повторный расчёт в measure | geometry | не проверено |
| 7 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:6963` | GL и fallback имели собственные абсолютные размеры помимо контейнера. | источник истины | Complete: дочерние MATCH_PARENT | geometry | не проверено |
| 8 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:6960` | Fallback занимал .55 контейнера и отдельно пересчитывался слайдером. | геометрия | Complete: постоянная пропорция .78; визуальное совпадение не измерено | geometry | не проверено |
| 9 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:6985` | После потери/ошибки GL скрытый fallback не возвращался. | lifecycle | Connect: отмена fade и alpha=1 | geometry | не проверено |
| 10 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:268` | Одноразовый callback исчезал после первого кадра и не скрывал fallback после пересоздания. | lifecycle | Complete: подписка на готовность каждой поверхности | geometry | не проверено |
| 11 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:1454` | Отложенный UI callback устаревшего GL-потока мог скрыть fallback. | lifecycle | Complete: проверка токена на UI | geometry | не проверено |
| 12 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:138` | Заполнение .97 оставляло лишь 1.5% вертикального запаса с каждой стороны. | геометрия | Complete: именованный fill .82 (9% аналитически, не замер устройства) | geometry | не проверено |
| 13 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:692` | Нижний отступ прибавлялся внутрь содержимого вместо наружного запаса. | геометрия | Complete: знак минус | geometry | не проверено |
| 14 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:998` | Условие i+8<len пропускало Y последней вершины сетки. | геометрия | Complete: учитываются все вершины | geometry | не проверено |
| 15 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:689` | Плоский расчёт камеры игнорировал перспективное увеличение ближних вершин. | геометрия | Complete: radiusXZ тела/аксессуара и резерв глубины | geometry | не проверено |
| 16 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:687` | Низ кадра не учитывал реальные minY тела/аксессуара. | геометрия | Complete: минимум геометрии с исходным пределом | geometry | не проверено |
| 17 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:668` | При высокой шляпе камера догоняла новый размер, временно обрезая верх. | realtime | Complete: camDistance=0 до первой новой сетки | geometry | не проверено |
| 18 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:671` | Два чтения volatile skin могли пометить старую сетку как уже собранный новый скин. | race | Complete: один снимок requestedSkin | geometry | не проверено |
| 19 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:1417` | Новая поверхность наследовала старое состояние камеры. | lifecycle | Complete: camDistance=0 после сборки GL | geometry | не проверено |
| 20 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:373` | Прокрученный за экран пингвин продолжал рендерить. | lifecycle | Connect: видимость и getGlobalVisibleRect | geometry | не проверено |
| 21 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:325` | Новый scroll-listener требует симметричного снятия. | lifecycle | Complete: attach/detach пара | geometry | не проверено |
| 22 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:326` | Long press мог остаться в очереди после detach. | lifecycle | Complete: отмена отложенного действия | geometry | не проверено |
| 23 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:1381` | Старый поток после смены токена пропускал EGL release; без новой поверхности ресурсы оставались. | lifecycle | Complete: finally у владельца контекста | geometry | не проверено |
| 24 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:1393` | Join с таймаутом 500ms позволял новому потоку забирать EGL-поля ещё живого предыдущего. | lifecycle | Complete: ожидание только в рабочем потоке; зависший драйвер оставит fallback | geometry | не проверено |
| 25 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:1356` | onSurfaceTextureDestroyed возвращал false без release SurfaceTexture. | lifecycle | Complete: release после EGL, CAS от повторного освобождения | geometry | не проверено |
| 26 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:1383` | Запрос release мог прийти на границе завершения потока. | race | Complete: finished + releaseRequested закрывают обе очередности | geometry | не проверено |
| 27 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:951` | Основная GL-программа не проверяла link status. | ошибка GL | Complete: проверка compile/link, fallback при исключении | geometry | не проверено |
| 28 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:1446` | Ошибка swap считалась успешным первым кадром. | ошибка GL | Complete: проверка возврата, fallback | geometry | не проверено |
| 29 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:7038` | Компактная шапка должна игнорировать слайдер большой. | контракт | Leave: 72dp независимо от percent | geometry | не проверено |
| 30 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMusicPlayerSheet.java:1556` | Плеер уже ограничивает penguinCover своим holder и полями 10dp. | плеер | Leave: MATCH_PARENT в пределах обложки | player-wiring | не проверено |
| 31 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:6977` | Тап, haptic и секрет 10 тапов — работающие пути, не мёртвые функции. | функция | Leave: сохранены обработчики | penguin-header | не проверено |
| 32 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramTabBarGlass.java:85` | Предположение о пропущенном glassGlow устарело: он уже входит в signature. | не дефект | Leave: не добавлять дублирующее хеширование | tab-glass | не проверено |
| 33 | `45edea6:TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:280` | BTN_FIND_BY_ID был только объявлением без строки UI или dispatch. | legacy | Remove: только неиспользуемая константа, не функция пользователя | audit-clean | не проверено |
| 34 | `45edea6:TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:104` | BTN_HIST_PROFILE был только объявлением без строки UI или dispatch. | legacy | Remove: только неиспользуемая константа, не функция пользователя | audit-clean | не проверено |
| 35 | `45edea6:TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:281` | BTN_SELECTION_LIMIT был только объявлением без строки UI или dispatch. | legacy | Remove: только неиспользуемая константа, не функция пользователя | audit-clean | не проверено |
| 36 | `45edea6:TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMusicPlayerSheet.java:967` | Приватный createQueueRow не имел вызовов, ::-ссылок или JNI-совпадений. | legacy | Remove: неиспользуемый путь; рабочие UI/очередь сохранены | audit-clean | не проверено |
| 37 | `45edea6:TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:3824` | Приватный isPrimaryVoiceMode не имел вызовов, ::-ссылок или JNI-совпадений. | legacy | Remove: неиспользуемый путь; рабочие UI/очередь сохранены | audit-clean | не проверено |
| 38 | `45edea6:TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:7352` | Приватный getTelegramVersion не имел вызовов, ::-ссылок или JNI-совпадений. | legacy | Remove: неиспользуемый путь; рабочие UI/очередь сохранены | audit-clean | не проверено |
| 39 | `45edea6:TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:4690` | Приватный quickUrlPreview не имел вызовов, ::-ссылок или JNI-совпадений. | legacy | Remove: неиспользуемый путь; рабочие UI/очередь сохранены | audit-clean | не проверено |
| 40 | `45edea6:TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:2044` | Приватный tabStateText не имел вызовов, ::-ссылок или JNI-совпадений. | legacy | Remove: неиспользуемый путь; рабочие UI/очередь сохранены | audit-clean | не проверено |
| 41 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMusicPlayerSheet.java:602` | Кандидат «unused» реально используется через method reference: this::cyclePanelView. | не дефект | Leave: callback сохранён, это не доказательство realtime всего потребителя | audit-clean | не проверено |
| 42 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMusicPlayerSheet.java:1242` | Кандидат «unused» реально используется через method reference: this::hideShiftHintNow. | не дефект | Leave: callback сохранён, это не доказательство realtime всего потребителя | audit-clean | не проверено |
| 43 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMusicPlayerSheet.java:1002` | Кандидат «unused» реально используется через method reference: PengramMusicPlayerSheet.this::applyAccentToViews. | не дефект | Leave: callback сохранён, это не доказательство realtime всего потребителя | audit-clean | не проверено |
| 44 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:3629` | Кандидат «unused» реально используется через method reference: this::toggleSystemEmoji. | не дефект | Leave: callback сохранён, это не доказательство realtime всего потребителя | audit-clean | не проверено |
| 45 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:3640` | Кандидат «unused» реально используется через method reference: this::toggleInterfaceAnimations. | не дефект | Leave: callback сохранён, это не доказательство realtime всего потребителя | audit-clean | не проверено |
| 46 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:1031` | isLocalPremium вызывается каноническим делегирующим getter в том же файле. | не дефект | Leave: сохраняем цепочку вызовов | inventory | не проверено |
| 47 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:2793` | getVoiceGainDriveForPreset вызывается каноническим делегирующим getter в том же файле. | не дефект | Leave: сохраняем цепочку вызовов | inventory | не проверено |
| 48 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:2748` | isHidingEditedLabel вызывается каноническим делегирующим getter в том же файле. | не дефект | Leave: сохраняем цепочку вызовов | inventory | не проверено |
| 49 | `TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java:10279` | keepStyleOnType используется при редактировании сообщения; не мёртвый метод. | не дефект | Leave: путь composer сохранён | inventory | не проверено |
| 50 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:2825` | Публичный helper: найдено только объявление, нет Java/::/JNI потребителя в текущем дереве. | legacy API | Leave: совместимость публичного API; не считать подключённой функцией | inventory | не проверено |
| 51 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:894` | Публичный helper: найдено только объявление, нет Java/::/JNI потребителя в текущем дереве. | legacy API | Leave: совместимость публичного API; не считать подключённой функцией | inventory | не проверено |
| 52 | `TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:3606` | Публичный helper: найдено только объявление, нет Java/::/JNI потребителя в текущем дереве. | legacy API | Leave: совместимость публичного API; не считать подключённой функцией | inventory | не проверено |
| 53 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMessageIslandView.java:139` | Публичный helper: найдено только объявление, нет Java/::/JNI потребителя в текущем дереве. | legacy API | Leave: совместимость публичного API; не считать подключённой функцией | inventory | не проверено |
| 54 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMessageIslandView.java:430` | Публичный helper: найдено только объявление, нет Java/::/JNI потребителя в текущем дереве. | legacy API | Leave: совместимость публичного API; не считать подключённой функцией | inventory | не проверено |
| 55 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramMusicPlayerSheet.java:1836` | Все пять наблюдателей NotificationCenter снимаются при dismiss. | lifecycle | Leave: симметричные removeObserver присутствуют | player-wiring | не проверено |
| 56 | `docs/pengram-search-coverage.md:4` | Coverage-документ отставал: 344 вместо текущих 400 строк. | поиск | Complete: обновлена цифра; генератор повторно запущен | search-index | не проверено |
| 57 | `TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java:6010` | Меню ссылок было физически RIGHT и в RTL; ItemOptions сравнивает именно LEFT/RIGHT, не END. | RTL | Complete: разрешение направления при открытии меню | audit-clean | не проверено |
| 58 | `scripts/gen-pengram-audit.py:29` | Остальные LEFT/RIGHT, literal colors, catch/return — только лексические кандидаты, ещё не классифицированы все. | 1.4/1.8/1.9 | Leave OPEN: требуется просмотр геометрии, темы, catch и lifecycle каждого пути | inventory | не проверено |
| 59 | `scripts/gen-pengram-audit.py:14` | 166 KEY и 170 прямых literal операций проинвентаризированы, но сквозной read/write/service whitelist не завершён. | 1.1/1.3/1.5–1.7 | Leave OPEN: нет сертификата полноты настроек, dispatch и канонизации дублей | inventory | не проверено |
| 60 | `TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java:1400` | Сохранение предыдущего потока для join без сброса ссылки удерживало бы цепочку завершённых потоков/поверхностей. | lifecycle | Complete: ссылка сбрасывается после join, отменённый наследник тоже ждёт очистки | geometry | не проверено |

## Проверки и ограничения

- **49/49** `scripts/test-pengram-*.py` PASS, в том числе 2 новых suite. Это преимущественно
  source-contract тесты. Новая geometry-suite проверяет 75 сочетаний density/размера/ширины/
  ориентации численно и статическую перспективную модель; она НЕ исполняет Android renderer.
- Изменены старые ожидания в `penguin-header` и `profile-smooth`: старые проверки закрепляли
  density² и fill=.97. Заменены на px-контейнер, безопасный clamp и fill=.82. Остальные
  проверки сохранены. Ни одного теста не отключено.
- Поиск заново сгенерирован: **400 строк**. Скрытые настройки пингвина намеренно не индексируются;
  секрет 10 тапов сохранён. Coverage-документ не является доказательством семантической полноты.
- 3 изменённых Java-файла прошли tree-sitter syntax parse, **не Java typecheck**.
- Новых ресурсов нет; regression-suite проверяет наличие используемых строк в values и values-ru.
- В изменённых Java-файлах нет TODO/FIXME. API ID/signing/proguard не затрагивались.
- Android SDK/javac/device нет. Предыдущая CI #39693700278 не дошла до compile из-за billing;
  это не результат компиляции данного патча. Новый APK и профилирование не выполнялись.

## Открытые требования 1.1–1.9

1. **1.1:** завершить для всех KEY/literal динамических ключей семантические writer/reader пары,
   readonly/service whitelist, проверить каждую настройку на отсутствие UI-zombie. JSON — старт,
   не финальная whitelist. Остальные unused методы (кроме явных кандидатов) ещё не разрешены
   по receiver/::/reflection/JNI registration.
2. **1.2:** пингвин исправлен на уровне кода/статической математики. Замеры 56–64dp, ≥8% полей,
   всех скинов, шляпы и лап при flip/dance, fonts 1.3/2 и отказа EGL не выполнены.
   Height-clamp всё ещё резервирует 240dp под chrome; он не измеряет фактическую высоту текста
   при fontScale=2. TextureView остаётся владельцем buffer sizing; вручную setDefaultBufferSize
   не добавлен. Реальное соответствие surface/buffer/viewport при повороте требует Android.
3. **1.3–1.4:** есть полный лексический список dispatch-имён и suspicious-кандидатов, но нет
   доказательства всех обработчиков, ранних return/catch и всех путей slider coalescing.
4. **1.5:** canonicalization «chat look/messages», трёх player-входов, двух histories и menu editors
   целиком не подтверждена. Не удаляли альтернативные экраны на основании похожих названий.
5. **1.6–1.7:** полный UI/preview/theme/search и lifecycle-аудит WeakHashMap/executors/cursors/
   observer остальных Pengram-компонентов не завершён. Проверена только описанная GL-цепочка
   и observer-пары данного player sheet. Наличие remove не доказывает отсутствия всех утечек.
6. **1.8–1.9:** исправлено одно RTL-меню, остальная физическая геометрия и opaque colors требуют
   построчной классификации (арт/намеренная физическая ось/нетематизированный UI). Не выполнялась
   механическая замена RIGHT→END: ItemOptions, например, не поддерживает END в сравнении gravity.
7. **Realtime contract в целом не принят:** применение размеров пингвина подключено напрямую;
   сохранение через Config существует. Нет device-доказательства «тот же кадр» для каждого
   переключателя, live theme/language/RTL, foreground refresh и профиля аллокаций.

## Ручной чек-лист (все пункты ожидают устройство)

1. Root settings на density 1/2/3/4: контейнер 72dp, не 72×density dp; тело ориентировочно 56–64dp.
2. Полное превью 70/100/150: persisted percent, каждый шаг сразу; без пересоздания GL-view.
3. Те же значения при portrait↔landscape и split-screen: нет density-прыжка или выхода за ширину.
4. Tablet 640dp и fontScale 1.3/2: текст, ссылки и содержимое доступны; отдельно проверить height-clamp.
5. None→Santa→Wizard→glasses→None: шляпа/лапки целиком с ≥8% top/bottom, без zoom-catching-up.
6. Jump, spin, flip, dance/energy=1: проверить поля каждого анимированного кадра, а не только idle.
7. Скрыть/показать окно, быстро открыть/закрыть settings 30 раз: EGL/SurfaceTexture не накапливаются.
8. Прокрутить header за экран: GL-кадры прекращаются; вернуть: рендер возобновляется.
9. Форсировать shader/link/swap failure: fallback виден; следующая новая поверхность возвращает GL.
10. Все входы в музыкальный player, отсутствующая/поздняя обложка, каждый layout: пингвин только в cover.
11. Tap/haptic, long-press/cancel/detach и 10 быстрых тапов: действия/секрет не регрессировали.
12. Light/dark/accent/gradient смена без закрытия: цвета, fallback и тексты актуальны.
13. EN/RU/RTL live: меню ссылок зеркалится, текст не обрезается, иконки/озвучка доступны.
14. Перезапуск/foreground после изменения size/skin/settings: сохранённые значения применены.
15. Allocation tracker onMeasure/onDraw + GPU/CPU trace: нет новых hot-path аллокаций,
    рендер offscreen/background остановлен; composer Unicode/IME/send/drafts/attachments не регрессировали.

**Итог:** патч и проверяемая начальная инвентаризация готовы; полный запрошенный аудит,
сквозная семантическая таблица ключей и device/performance acceptance остаются незавершёнными.
