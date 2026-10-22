# Pengram: аудит анимаций набора — 2026-10-22

## Статус и границы доказательств

Подготовлен патч корректности/оформления, **не сертификат готовности на всех устройствах**. Все 47 Python suites проходят. Новая проверка включает source contracts и независимую reference model на 766 официальных Unicode 17 GraphemeBreakTest-векторах. Production Java UAX runner добавлен, но локально **SKIP**: доступен минимальный Java runtime из jdk4py, без javac; загрузка полноценного JDK через GitHub release asset заблокирована сетью. Android SDK, эмулятор и физическое устройство недоступны. Разбор tree-sitter — только синтаксис, не компиляция/проверка API.

Следовательно, критерии K, отсутствие tofu на конкретных шрифтах/EmojiSpan, Android type checking, время кадра ≤2 мс, 60/120 Гц, память native/GPU и батарея **пока не подтверждены**. Предыдущий CI не запускал compile из-за billing/spending GitHub Actions; результат нового запуска публикуется в PR. Ничего не мержилось.

## Архитектура и сохранность текста

`EditText` по-прежнему рисует настоящий текст. Рендерер не вызывает setText/setSpan, не прячет буквы и не переводит поле в software layer. Глиф/удаление — отдельный прозрачный bitmap/геометрия поверх штатного рисования. Если индексы, граница кластера, layout, шрифт, изображение или бюджет не подходят — эффект пропускается/становится пылью. Неподдерживаемые custom ReplacementSpan не захватываются. EmojiSpan допускается только целиком с загруженным EmojiDrawable; обычный текст проверяется Paint.hasGlyph по целым кластерам, вне кадра.

Границы — UAX #29 Unicode 17, включая InCB/GB9c, Extended_Pictographic/GB11, RI, Hangul, marks, VS, emoji modifiers и ZWJ. Старый java.text.BreakIterator не использован, поскольку его сегментация зависит от версии Android/ICU. Таблицы генерируются `scripts/gen-typing-graphemes.py` из UCD Unicode 17, содержат SHA256 источников; источник закреплён коммитом unicode-org/unicodetools `578f6c0516f5f268292ebda50b51848407a06478`. В APK нет загрузки Unicode-данных по сети. Официальные тестовые векторы и Unicode License V3 включены в scripts/testdata/unicode.

Важно: если конкретная IME сама удаляет только часть графемы, рендерер пропускает её эффект, а не переписывает запрос удаления. Принудительно менять настоящий текст ради анимации противоречило бы правилу overlay-only. Полноту native backspace на произвольной клавиатуре этот патч не доказывает; это отдельный пункт ручной матрицы.

## Аспект → найдено → сделано → проверено → устройство

Обозначения: **S** — статическая проверка/ревью; **U** — независимая Unicode reference model; **D** — ручная проверка ещё не выполнена. U не означает выполнение Java-класса.

| Аспект | Что найдено | Что сделано / чем проверено | Что требует устройства |
|---|---|---|---|
| A1, C0: графемы | Snapshot хранил один char, LETTERS String.valueOf(p.ch) создавал одинокие суррогаты; лимит был UTF-16 | String[] removedClusters, Particle.text, целый raster; MAX_DELETE_CLUSTERS=24, первые допустимые кластеры большого удаления, UAX #29; S+U 766 | D: все эмодзи/диакритики и OEM-шрифты, визуальное отсутствие tofu |
| A2: composing/commit | Замена слова могла анимировать добавленный хвост; не было полноценного разделения composition/commit | InputConnectionWrapper: composing подавляет эффекты, commit/finish фиксируют диапазон, последний pending batch выигрывает; старые и TextAttribute overloads; замены before>0/count>0 не создают глифов/частиц. Сигнатуры сверены с AOSP InputConnectionWrapper; S | D: Gboard, китайский, японский, корейский, вложенные batch edits |
| A2: glide | Слово трактовалось как набор хвостовых букв | Зафиксированное слово получает один мягкий FADE-raster, не больше MAX_PER_BATCH объектов; многострочный/слишком широкий layout пропускается; S | D: Gboard glide и CJK commit |
| A2/A3: происхождение | Был isPaste guard лишь у внешнего вызова, короткие вставки нельзя было классифицировать рендерером | isPaste передаётся явно; внешние изменения имеют depth-guard; ignoreTextChange/innerTextChange, paste/plain paste, autofill, Accessibility SET_TEXT, undo/redo и Ctrl+Z/Y подавляются; S | D: password managers, IME paste, OEM autofill, голосовой ввод |
| A3: порог | Любые >12 UTF-16 units считались вставкой | Порог оставлен лишь страховкой массового много-словного/голосового ввода; длинное отдельное слово допускается. IME не сообщает универсальный voice/glide origin: короткий voice commit может получить такой же мягкий эффект, но не посимвольную очередь; S | D: реальная классификация клавиатур; стопроцентное различение unlabeled IME commit не обещается |
| A4: координаты | Старое вычитание scroll второй раз уже было исправлено предыдущей задачей; ломать обратно нельзя | Единый glyphRect, getTotalPaddingTop, primary/secondary horizontal и run RTL. Canvas остаётся в content coordinates; клип учитывает scroll. Частицы компенсируют изменение scroll после удаления; S, обновлён старый regression contract | D: переносы, панель эмодзи, вращение, удаление с изменением scroll |
| A5: строки/длина | Проверялась последняя строка и использовались временные подстроки в draw | Проверка текущей строки selection при выключенном all-lines; точные диапазоны/clip; сравнение expected без создания строки в кадре; Java fixture для offset>5000; S | D: 3000/5000+ символов, первая/последняя строка, быстрая прокрутка |
| A6: bidi | SLIDE зеркалился по LocaleController.isRTL, а не направлению текста | Направление из Layout.isRtlCharAt; secondary horizontal у края run, signed liquid dx; S | D: арабский/иврит+латиница, границы bidi-run |
| A7: lifecycle | Остаточные callbacks/системная каретка нуждались в явном восстановлении | clear снимает callbacks и attach listener; hooks focus/window visibility/window focus/detach/pause/keyboard/chat/account/resize; State и listener registry держат только weak ссылки на поля/callback; S | D: heap dump/GC, screen off, смена аккаунта, окно/клавиатура |
| B FADE | След уже рисовался поверх оригинала — это сохранено | Мягкий затухающий raster из Layout, цвета/спаны настоящего текста; intensity управляет alpha, speed duration; S | D: контраст/читаемость тем |
| B POP | Масштаб стартовал больше 1 | Старт около .7, небольшой overshoot, центр rect; S | D: визуальная плавность |
| B SLIDE | Знак зависел от языка UI | Смещение относительно textSize и фактического RTL-run; клип; S | D: mixed bidi |
| B RISE | Движение было dp-ориентированным | Сдвиг от textSize; самостоятельный затухающий след, без накопления амплитуд; S | D: быстрый набор/несколько строк |
| B BOUNCE | Нужна защита от наслоения | Ограниченный одиночный отскок от времени; переполненный MAX_ACTIVE пропускает новый след, а не обрывает старый; S | D: 8–10 символов/с |
| B SHAKE | Был поворот до 12°, заметно больше микро-дрожания | Локальный сдвиг ≤1.5 dp, затухание по времени; S | D: восприятие на разных density |
| B NONE | Требуется отсутствие собственного кадрового цикла | Не создаётся State при apply; при выключении states/таймеры очищаются, возвращается штатная каретка. Остаётся вызов-hook/lookup, абсолютный ноль CPU инструкций не заявляется; S | D: profiler отсутствия overlay callbacks |
| B: FPS/скорость/интенсивность | Cursor имел покадровое затухание | Glyph progress от uptime, cursor exponential dt, 150/210/320 мс; amplitude/alpha от intensity; S | D: одинаковая длительность 60/120 Гц |
| B: пробел/перенос | Риск фантомного символа | IgnoreSpaces, запрет raster для CR/LF, отдельные cursor events; S | D: IME Enter/пробел/NBSP |
| B: blur | BlurMaskFilter создавался в draw, аппаратный результат ненадёжен | Вместо фильтра — два дешёвых полупрозрачных отпечатка; ключи сохранены, подписи RU/EN честно называют эффект растворением; S | D: качество, GPU-cost; это не настоящий Gaussian blur |
| C1: направление/геометрия | Индексы и baseline могли описывать половину эмодзи | Snapshot только полных графем, old-scroll anchor, точный top-baseline delta; RTL-разлёт зеркален; newline не создаёт частицы; S+U | D: mid-line, selection, swipe-delete, Ctrl+Backspace |
| C2: DUST/SPARKS/SNOW/PETALS/LETTERS | Буквы рвались; снег/лепестки могли стартовать вверх | Все пять case; dust theme color, sparks+gravity, snow медленно вниз, petals rotation+wind, LETTERS целый cached Layout raster; S | D: визуальная матрица пяти стилей |
| C3: параметры | count принудительно Math.max(1), LETTERS игнорировал density/size | count=0 строго выключает; speed влияет на duration/velocity, spread на vx, size на частицы/ширину glyph raster. LETTERS density ограничивает число **целых** глифов (до count×2, максимум24), не размножает один эмодзи; S | D: четыре ступени каждого стиля |
| C4: бюджет/кадры | Строка для буквы создавалась каждый кадр | Строки и bitmap готовятся при событии; ceiling160, MAX24, кэш32/2MiB, eviction только неиспользуемых raster. При заполнении — dust/skip; clip и остановка по lifetime; S | D: allocation profiler, backspace bursts |
| C5: haptic | Только вставки | Чистое удаление тоже даёт feedback, не чаще90мс; системный запрет вибрации больше не обходится; S | D: OEM haptics, системные настройки |
| C6: программные изменения/undo | Snapshot снимался даже при программной очистке | Снимается только для пользовательского pure delete; stale snapshot сбрасывается; внешние операции, draft, send-clear подавляются; S | D: undo/redo, send-clear, шаблоны, восстановление черновика |
| D1: blink | Период был500мс и после набора1400мс | 530мс, сброс паузы на ввод/selection, нет overlay blink при выделении; S | D: native/custom переходы |
| D2: movement | Был принудительный snap после любого edit/tap | dt-smoothing для tap/стрелок/Home/End/переноса без накопления хвостов; resize очищает старую геометрию; S | D: аппаратная клавиатура/частые layout changes |
| D3: liquid | Растяжение было преимущественно вертикальным | Signed dx, горизонтальный след по направлению и сжатие ширины, scale0..40; S | D: RTL/line wrap/end-of-text |
| D4: события | Старое событие могло блокировать новое | Последнее PULSE/DIVE/JUMP выигрывает; ограниченный200мс event; S | D: сочетания backspace/Enter/space |
| D5: ширина/цвет | Уже брался Theme cursor color | Сохранён Theme.key_chat_messagePanelCursor, width1..12dp; S | D: обе темы/кастомные темы |
| D6: selection | Края могли зависеть от старых отступов | getTotalPaddingTop, обе фактические границы, нет мутации selection/clipboard; S | D: обратное выделение, несколько строк/RTL/копирование |
| D7: native caret | При hide после super мог появиться лишний курсор в первом кадре | prepareDraw до super скрывает native только при действительном target/custom cursor; clear возвращает. Plain EditText не получает второй custom cursor; S | D: один курсор во всех состояниях |
| D8: крайние случаи | Не было полного lifecycle/composing guard | Empty layout/focus/viewport/IME guards, composing оставляет native, selection не прячется; S | D: пустое поле/hint, IME собственной каретки, аппаратный ввод |
| E1: ≤2мс/60fps | Измерений нет | Перенос raster/cache за draw и жёсткие бюджеты; S, но **не замер** | D: Perfetto/GPU profiler на среднем устройстве |
| E2: ноль alloc/frame | getSpans/subSequence/new BlurMaskFilter/String.valueOf попадали в горячий путь | Удалены; reusable Paint/RectF, indexed loops, bitmap готовится до draw; source suite проверяет draw и координатные helpers; S | D: скрытые аллокации framework/native проверить профайлером |
| E3: кэши | blurCache не имел явного потолка | Blur-cache больше не нужен; raster cache ≤32 и≤2MiB. Не recycle bitmap, уже переданный GPU: native освобождает после последней ссылки; S | D: Java/native/GPU heap до/после |
| E4: idle | Полный FPS мог оставаться у курсора | Animation frames только glyph/particle/movement; в покое custom caret имеет редкий530мс blink callback, при selection даже его нет. Буквально ноль кадров несовместим с мигающей кареткой; S | D: frame trace/native Editor blink |
| E5: фон/батарея | Требовались foreground guards | Нет собственного цикла при none/focus loss/detach/pause/window-hidden; S | D: 10 минут набора/фон/screen off |
| E6: слабые устройства | Нет измерений дешёвого телефона | Бounded cache/particles, отказ от software EditText/BlurMaskFilter, fail-soft skip при нехватке бюджета/bitmap. Отдельный автоматический device-tier режим без замеров не вводился; S | D: слабый телефон +3000 символов |
| F1/F2: system/app motion | Не было системного gate | API26 areAnimatorsEnabled, API23 Global scale, SharedConfig.animationsEnabled; process-owned observers, очистка состояния при изменении; S | D: OEM accessibility/scale0/App animations |
| F3: честная подпись | Не было reduce-motion объяснения | RU/EN подпись, переключатели остаются; превью off не мигает; S | D: настройки системы на месте |
| F4: TalkBack | Overlay должен оставаться рисунком | Нет contentDescription/announce/accessibility nodes у эффекта; ACTION_SET_TEXT подавляется без подмены стандартной обработки; S | D: TalkBack диктовка/выделение |
| F5: fontScale | Preview90dp мог обрезать hint | Геометрия из Layout/TextPaint, preview wrap-content с измерением подписи/шрифта, raster cache учитывает font/size; S | D:1.3×/2.0×, rotated layout |
| F6: контраст | Нужно не закрывать оригинал | Настоящий TextView не меняет alpha/spans/layer; theme raster/particles, cache очищается при смене paint size/color; S | D: светлая/тёмная/пользовательская тема |
| G1: пресеты | Не было | Off/Calm/Lively/Maximum атомарно задают согласованные ключи; ручная правка отмечает Custom, контроль значений предотвращает ложный badge; S | D: каждый preset + каждый slider |
| G2: preview | Уже использовал тот же renderer — сохранено | Живые weak attach-owned listeners; изменения чисел обновляют preview/preset cell без перезагрузки slider во время drag; theme и off/reduce hint; S | D: немедленный следующий символ |
| G3: группы | Длинный плоский список | Четыре раскрываемые группы, зависимые параметры скрыты, searchRevealGroups раскрывает их; send-style/WPM не удалены; S | D: переход из поиска при parent off |
| G4: значения | Много непрерывных ползунков с голыми числами | Четыре понятных ступени; старые числовые значения не переписываются до ручного изменения; существующие speed/intensity names сохранены; S | D: UX/локализации |
| G5: описания | Blur назывался blur даже без аппаратного результата | Краткая честная справка о dissolve/энергии/fallback; повторяющиеся описания убраны из основной группы; S | D: длинные переводы/шрифт2× |
| G6: поиск/локали | Новые контролы отсутствовали | Обе XML, синонимы typing/caret/particles/emoji/RU, generator понимает discrete slider helper; индекс400, детерминирован; S | D: RU/EN поиск и navigation |
| G7: reset | Не было целевого reset | Confirmation + сброс только input/typing animation keys; не трогает текст, send style и WPM counters; S | D: на открытом чате/после restart |
| H: охват | Composer и preview уже подключены | Только они; non-chat/caption edit и editingMessageObject исключены; поиск/AI/цитаты не подключались; публичные существующие renderer API сохранены; S | D: секретные чаты/комментарии/топики/slow mode/Enter/голос/стикеры/вложения |
| I1: typing status | sendTyping/TypingDots отдельные | Не менялись, ghost guard isNotSendingTyping в MessagesController остаётся; overlay не вызывает sendTyping; S | D: ghost on/off, серверный статус |
| I2: WPM | Мог считать короткую замену/IME | Исключены external edits/обычные word replacements; composition считает только положительную дельту, commit той же длины не удваивает слово. WPM остаётся приблизительным UTF-16-счётчиком (не метрологией IME/autocorrect); бейдж только в chat reserve50dp, singleLine+ellipsis, hide сразу от настроек; счётчики без текста; S | D: spans/IME/WPM и шрифт2×, heap |
| I3: drafts | Программный snapshot был возможен | ignoreTextChange/innerTextChange/external guards, clear на chat/account/pause; S | D: восстановить и отправить draft |
| I4: formatting | keepStyleOnType уже имел вызов при открытии edit, но не на каждом вводе | guarded PengramTextFormatting.apply в afterTextChanged для user composer; overlay raster сохраняет Layout shaping/styles; S | D: bold/italic/mono/emoji + IME |
| J | Отдельного typing suite не было | test-pengram-typing-anim.py, Unicode corpus/license, optional production Java runner; два старых suite обновлены под точный clip и новый порядок групп, не ослабляя send-style/resource contracts; S+U | D: вся матрица ниже |
| K | Полное доказательство требует Android | Не объявляется выполненным без compilation + device matrix | D: блокирует выпуск |

## Реальные результаты проверок и замеров

| Проверка / метрика | Результат |
|---|---|
| Все scripts/test-pengram-*.py | 47/47 PASS |
| Unicode17 reference model | 766/766 PASS; не execution Java |
| Production Java helper runner | SKIP: javac недоступен |
| Search generator | 400 строк; повторная генерация не меняет файл |
| Java tree-sitter | PASS синтаксис; не type checking |
| py_compile / git diff --check | PASS |
| Android APK compilation | Не выполнена локально; статус нового CI в PR |
| Время draw ≤2мс | **Не измерено** |
| FPS60/120, набор10симв/с | **Не измерено** |
| Java/native/GPU память до/после | **Не измерена**; 32/2MiB — лимит кода, не замер RSS |
| Нагрев/энергия10мин | **Не измерены** |

## Ручная матрица — все пункты пока НЕ ВЫПОЛНЕНЫ

- [ ] Android6/API23: загрузка класса с API26/33 overloads, системные анимации0, native caret fallback.
- [ ] Русский обычный набор — Android8/12/14/16: след точно над буквой, текст не мигает; все7 режимов ×3скорости ×3интенсивности.
- [ ] Emoji/ZWJ/flag/skin tone — Android12+: целый кластер при добавлении и каждом backspace.
- [ ] **Все5 стилей удаления, Android10+:** 👍, 👍🏽, 👨‍👩‍👧‍👦, 🏳️‍🌈, 🇷🇺, 1️⃣, ❤️, e+U+0301, вьетнамские marks и индийские conjunct; ни одного синтезированного ?/tofu.
- [ ] «привет 👋 мир 🇷🇺», выделение/удаление середины/слова/свайп/Ctrl+Backspace,13 и30эмодзи; лимит не выключает всю пачку, LETTERS не размножает половинки.
- [ ] Незагруженный EmojiDrawable/неподдерживаемый системный кластер/custom emoji: dust или пропуск, исходный текст штатный.
- [ ] Китайский/японский/корейский IME — Android12+, а также API33+TextAttribute: composition/commit/finish не дают двойного эффекта.
- [ ] Gboard glide: слово появляется одним мягким следом; длинное слово>12 допустимо, длинный voice paragraph не даёт очереди букв.
- [ ] Вставка3 и200символов — Android10+: paste/plain paste/Ctrl+V/IMEclipboard/autofill/password manager/voice; не «пулемёт».
- [ ] Undo/redo (menu/Ctrl+Z/Y/IME), send-clear, draft restore, шаблон/стиль: нет частиц из будущего.
- [ ] Многострочное поле с scrollY/scrollX — Android10+: частицы из старой экранной позиции, insertion следует актуальному layout; переносы/первая/последняя строка/rotate/emoji panel/resize.
- [ ] Арабский/иврит+латиница — Android10+: bidi-run edges, SLIDE/cursor signs, обратное выделение, копирование.
- [ ] FontScale1.3×/2.0× и120Гц — Android12+: размеры/длительность/preview-hint/WPM badge не плывут и не обрезают поле.
- [ ] Tap/стрелки/Home/End/end-of-text/empty hint: одна каретка, видимый PULSE/DIVE/JUMP, blink после530мс, selection не мигает.
- [ ] Системная анимация0, app animations off, TalkBack, китайская IME-собственная каретка: штатный ввод/озвучивание сохраняются.
- [ ] Focus loss/keyboard close/window hidden/activity background/screen off/chat/account/detach: callbacks остановлены, native caret возвращена, heap не удерживает поле.
- [ ] Все4пресета, ручная правка каждой настройки, collapse/search/reset, RU/EN; следующий символ уже с новым эффектом, preview не сбрасывает введённый текст.
- [ ] Секретные чаты/комментарии/топики/slow mode/Enter-send/editing/attachments/stickers/voice: отправка и черновики не регрессировали; AI/quote/caption/search не получили overlay.
- [ ] Ghost/sendTyping и штатные три точки не изменились; WPM только opt-in, не растёт от программных операций/эффекта.
- [ ] Среднее устройство: Perfetto/allocations/GPU profile, draw≤2мс,10символов/с и одновременная прокрутка списка сообщений.
- [ ] Слабое устройство+3000/5000символов, быстрый backspace, заполнение32raster/160particles: нет пиков/утечек.
- [ ]10минут набора: FPS, Java/native/GPU memory до/после, температура и энергия — записать реальные числа перед признанием K выполненным.
