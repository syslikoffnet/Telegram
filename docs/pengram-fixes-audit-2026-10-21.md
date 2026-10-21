# Pengram: исправления и аудит — 2026-10-21

## Статус сдачи

Работа **не прошла полную приёмку**: изменения исходников и host-side проверки выполнены,
Android-компиляция и ручной прогон не выполнены. Не считать этот набор готовым релизом.
Ничего не мержилось. API ID и `api.properties` не менялись.

## 1. Объединённая навигация поиска

Подтверждены все три описанные причины: зависимость режима от количества архивных
попаданий, граница по обещанию серверного счётчика, запасной индекс/подстановка из
серверного массива. Также при изменении текста сброс сравнивался с pending-запросом,
а не с запросом, которому принадлежит идентичность.

Изменены:
- `ChatActivity.java`: текстовый поиск использует объединённую ветку независимо от
  количества удалённых; счётчик берётся из `loadedCount`; текущий индекс выводится из
  пары dialogId/messageId, отсутствующая идентичность даёт -1, а не прежний индекс.
  Начальный серверный переход запоминает ID из самого события. Убран выбор из
  `getFoundMessageObjects()[num]`, начальный архивный результат открывается через общий
  путь. При смене запроса идентичность сбрасывается по запросу.
- `MessagesSearchAdapter.java`: явное состояние ожидания асинхронного архива.
- Ожидание стрелки возобновляется колбэками результатов, сосед вычисляется заново от
  идентичности. Убран таймер 8 × 700 мс и молчаливое прекращение ожидания. На границе
  неполной выдачи запрашивается страница с видимым индикатором поиска.

Тест: новый `scripts/test-pengram-search-navigation.py`, обновлён контракт
`test-pengram-history-search.py`. Модель вставки строки проверяет сохранение идентичности;
это не исполнение Java-кода.

На устройстве: 10 проходов в обе стороны, архив+сервер, медленная сеть/ошибка сети,
дубли по id, смена запроса, выбор строки списка, топики и реакции. Отдельно проверить
полный хронологический порядок при поздней серверной странице: статический тест его
не доказывает. Повторные нажатия во время ожидания не образуют очередь переходов.

## 2. Строка песни

Подтверждено: ранний return находился до перепланирования; повторный show прекращался
по флагу до восстановления видимости/насоса.

- `FragmentContextView.java`: цикл живёт от attach до detach; перепланируется до
  проверок настройки/стиля; возобновляется при VISIBLE. Панель сверяет трек с
  `MediaController`, в том числе без событий экрана чатов. Смена ключа убирает старый
  текст. Повторный show восстанавливает видимость, режим и запрос кадра до проверки
  флага. Прогресс берётся из playing.audioProgress, пауза не останавливает цикл.
- `PengramLyricsView.java`: повторная передача неизменного режима не сбрасывает
  разметку каждые 500 мс; размер/анимация перечитываются насосом, bold/marquee —
  существующей проверкой разметки.

Тест: новый `test-pengram-ticker-lifecycle.py`, прежние lyrics-suite зелёные.
На устройстве: старт/смена/пауза трека из списка без открытия чата, уход из чата,
attach/detach, включение настройки и изменение всех параметров уже показанной строки.
Интервал контрольного насоса — 500 мс, кадры анимации запрашивает сам вид.

## 3. Текст за пузырём

Подтверждено: фактическая ширина StaticLayout проверялась только для RTL.

- `ChatMessageCell.java`: проверка ширины layout для любого направления; clip по
  актуальным bounds фона с допуском 1dp вместо правой границы текста. Условие
  вычисляется при каждой отрисовке по актуальному maxWidth, поэтому не кэширует
  прежнюю ширину пузыря до появления inline-клавиатуры. Переносы MessageObject
  не изменены.
- Обновлён `test-pengram-text-overflow.py`: запрещает RTL-only условие и требует bounds.

На устройстве: URL, слова без пробелов, RTL, цитаты с padTop/padBottom, emoji,
боты с inline-клавиатурой, анимации ширины, grouped captions. Нормальные сообщения
и выделение текста не должны обрезаться. Дополнительного перевычисления самого
StaticLayout при появлении клавиатуры не добавлено: текущая правка — draw-time страховка.

## 4. Стекло

Изменены:
- `PengramConfig.java`: отдельные ключи/геттеры/сеттеры ввода, шапки списка и шапки
  чата. Шапка чата по умолчанию включена для сохранения прежнего поведения.
  Параметры общие с панелью вкладок, с существующей валидацией диапазонов.
- Новые `PengramGlassSkin.java` и `PengramGlassSheen.java`: тематические цвета без
  хардкод-цветов, оптика, сигнатуры, слабые подписки, рассылка UI с объединением 80 мс,
  реакция на смену темы; бегущий блик без аллокаций внутри draw.
- `PengramTabBarGlass.java`: signature включает glow, sheen и drag.
- `ActionBar.java`: clearGlass возвращает сохранённые фон/clipChildren, сбрасывает
  три drawable, режимы и сдвиги меню; оптика применяется ко всем трём drawable.
- `ChatActivity.java`: пересоздание ввода/подклавиатурного фона и шапки по signature;
  отключение ввода возвращает штатный провайдер; guard API 33 + LiteMode до create.
- `DialogsActivity.java`: setupGlass/clearGlass шапки, провайдер верхней панели;
  обе activity регистрируют/снимают callback в onResume/onPause.
- `DialogsActivityTopPanelLayout.java`: новый drawable сразу получает bounds, не
  ждёт повторного layout; слой блика.
- `chat/ChatInputViewsContainer.java`: слой блика без изменений windowInsets и
  геометрии клавиатуры. Контейнер сохраняет собственные радиусы/отступы.
- `MainTabsActivity.java`: пользовательская настройка больше не обходит LiteMode/API.
- `PengramSettingsActivity.java`, `res/values/strings.xml`: три переключателя и
  общие раскрываемые параметры; вызовы notifyChanged. `PengramSearchIndex.java`
  перегенерирован (389 записей).

Тест: новый `test-pengram-glass-live.py`; старый `test-pengram-glass-drag.py`
обновлён под требуемый AND-guard вместо прежнего обхода LiteMode через OR.

**Незавершённое/неподтверждённое:**
- Отдельные drawable SendButton и кнопок записи/вложений не переведены на новый
  skin. Их существующее оформление может не совпадать с параметрами ввода.
- На API 31–32 используется существующий RenderNode blur без рефракции. Ниже API 31
  остался штатный color/wallpaper-source; настоящий blur на этих версиях этой
  правкой не реализован. Это неполное соответствие требуемой деградации до blur.
- Не проверены FPS, контраст и отсутствие захвата поверхности самой себя, RTL,
  landscape, комментарии, топики, emoji/sticker/voice-панели и смена темы во время
  перехода. Нужен Android-прогон, а не только чтение исходников.
- clearGlass возвращает стандартные нулевые сдвиги кнопок; пользовательские
  нестандартные сдвиги и chatAvatarContainer нужно проверить на устройстве.

## 5. Аудит сборки emoji

Pillow остаётся только Python-зависимостью машины сборки. В APK он не добавлен;
`assets/emoji.pack` не изменён.

| Файл | Было | Стало | Риск до правки | Проверка |
|---|---|---|---|---|
| `image_safety.py` (новый) | Общей точки защиты не было | Лимит 4096 пикселей, строго 64×64, максимум 1 MiB входа; только PNG/WebP, без анимации; warning/error бомбы преобразуются в ошибку; verify + повторное открытие + load; закрытие потоков | Высокий | Runtime: PNG/P/WebP, неверный размер, обрезанный PNG, bomb header, большой вход |
| `base_builder.py` | Прямой Image.open; pickle.load из переиспользуемого cache; предсказуемый .tmp | Общий декодер; JSON-кэш с явными типами bytes/tuple/ndarray; confined cache path | Высокий | Статика, py_compile, round-trip кэша |
| `stage_cache.py` (новый) | Исполняемый pickle-кэш | Нет десериализации Python-объектов; object dtype запрещён; лимит файла 256 MiB; NamedTemporaryFile + atomic replace | Высокий | Round-trip numpy/bytes/tuple; статический запрет pickle.load |
| `optimizer_core.py` | Image.open без verify/close; число записей ограничивалось после чтения | Защищённый context manager; лимит 8192 суммарных текстур до массивов | Высокий | Runtime RGBA/RGB/L/P, py_compile |
| `optimizer_visual.py` | Декодирование результата WebP без закрытия/лимита | Общий декодер | Средний | Статика, py_compile, декодирование текущего пака |
| `verify_emoji_pack.py` | Image.open в PNG/WebP/ZIP без лимита/закрытия | Все точки через общий декодер | Высокий | Декодированы все 3606 emoji текущего пака |
| `build_emoji_pack.py` | Фиксированные выходы могли следовать симлинкам рабочего каталога; прямой Image.open | Проверка фиксированных путей под build root; отказ materialize от symlink output; общий декодер копируется в work | Средний | Runtime traversal/symlink, статика, py_compile |
| `test_java_loader.py` | Прямой Image.open | Общий декодер, источники закрываются | Средний | Статика, py_compile; JVM-тест не запускался |
| Все subprocess | Уже argv-списки, без shell=True; CXX проходит shlex.split | Поведение сохранено | Низкий | AST-проверка |
| Запись исследовательских records.pkl/final_selected.pkl | Только pickle.dump, не чтение в доступных скриптах | Сохранена для совместимости формата исследовательских стадий | Низкий для записи | AST: pickle.load/loads запрещены во всех доступных emoji-скриптах |
| `requirements.txt` | Pillow 12.3.0 | Pillow 13.0.0; NumPy 2.3.5 и SciPy 1.17.0 без изменения | Высокий | GitHub Advisory API, установка в venv, runtime |

Входные CLI reference/textures/output — явно выбранные пользователем пути, не
ограничены cwd: иначе ломается штатная сборка с исходниками и выходами в другом
каталоге. ZIP не распаковывается в файловую систему, имена текстур строятся из
числовых ID; shell-подстановок имён нет. `build_path` не является sandbox против
параллельного злоумышленника, меняющего симлинки между проверкой и записью.
Общего OS-лимита RSS процесса/пула оптимизации не добавлено: защиту составляют
лимиты текстуры, числа записей и файла кэша; NumPy-массивы всего пака по-прежнему
живут в памяти. Это не обещание константного потребления RAM.

### Проверка advisories

Проверено 2026-10-21 через GitHub REST `/advisories?ecosystem=pip&affects=...`:
- Pillow@12.3.0: 21 совпадение; у всех исправленная версия 13.0.0.
- Pillow@13.0.0: 0 совпадений.
- numpy@2.3.5 и scipy@1.17.0: 0 совпадений в этом источнике.

Отсутствие совпадения в одном источнике не доказывает отсутствие всех уязвимостей.
OSV/NVD отдельно не опрашивались (сетевой доступ среды ограничен).

- [GHSA-xvh5-g6gv-559v](https://github.com/advisories/GHSA-xvh5-g6gv-559v):  Pillow: Memory disclosure and segfault in libtiff encoder via crafted TIFF tags
- [GHSA-332p-7hrc-92wf](https://github.com/advisories/GHSA-332p-7hrc-92wf): Pillow: Heap buffer overflow in _tiffWriteProc when saving TIFF to BytesIO
- [GHSA-f2g6-542v-9j58](https://github.com/advisories/GHSA-f2g6-542v-9j58): Pillow: Infinite loop in PdfParser.rewrite_pages on page tree Parent cycle
- [GHSA-5xm2-6676-qcp9](https://github.com/advisories/GHSA-5xm2-6676-qcp9): Pillow: APNG background disposal allocates full canvas before decompression bomb check
- [GHSA-jg86-v3jv-hrpm](https://github.com/advisories/GHSA-jg86-v3jv-hrpm): Pillow: Quadratic-time DoS in PDF save_all (PdfParser.next_object_id)
- [GHSA-254f-3936-288h](https://github.com/advisories/GHSA-254f-3936-288h): Pillow: PdfParser can recurse without limit in get_value() and linearize_page_tree()
- [GHSA-xgh8-jp6q-p5x8](https://github.com/advisories/GHSA-xgh8-jp6q-p5x8): Pillow: ReDoS in XbmImagePlugin: 512-byte file stalls Image.open() for minutes
- [GHSA-rfgq-q3w2-pmv2](https://github.com/advisories/GHSA-rfgq-q3w2-pmv2): Pillow: Image.quantize() with empty palette leaks uninitialized stack memory
- [GHSA-74r2-7ff9-mjj2](https://github.com/advisories/GHSA-74r2-7ff9-mjj2): Pillow: Quadratic GIF comment concatenation allows DoS in Image.open()
- [GHSA-22hg-r758-rrw9](https://github.com/advisories/GHSA-22hg-r758-rrw9): Pillow: Infinite loop in MIC n_frames when the embedded TIFF
- [GHSA-27h8-gf7j-v5hc](https://github.com/advisories/GHSA-27h8-gf7j-v5hc): Pillow: NULL dereference in ImagingExpand via rank filters on zero-size images
- [GHSA-7mwm-ghpf-9vrm](https://github.com/advisories/GHSA-7mwm-ghpf-9vrm): Pillow: Double free in ImagingScaleAffine on calloc failure via Image.transform
- [GHSA-4558-3h8r-v8xp](https://github.com/advisories/GHSA-4558-3h8r-v8xp): Pillow: P;2L and P;4L decoders may read 1 to 3 bytes past the passed buffer size
- [GHSA-w889-685w-c8fh](https://github.com/advisories/GHSA-w889-685w-c8fh): Pillow: Argument Injection in Ghostscript call, potentially leading into RCE
- [GHSA-58r4-6rv3-gfh8](https://github.com/advisories/GHSA-58r4-6rv3-gfh8): Pillow: Out-of-bounds access in _imagingmorph on a zero-width image
- [GHSA-65qm-jvmh-6pqg](https://github.com/advisories/GHSA-65qm-jvmh-6pqg): Pillow: GIF seek keeps rejected size after DecompressionBombError, allowing DoS
- [GHSA-vx6w-vv9f-6gvg](https://github.com/advisories/GHSA-vx6w-vv9f-6gvg): Pillow: EPS load(scale) lets Ghostscript render past the decompression bomb limit
- [GHSA-q45j-58j2-45rg](https://github.com/advisories/GHSA-q45j-58j2-45rg): Pillow: TiffDecode returns uninitialized memory as pixels for short JPEG strips
- [GHSA-pchr-8j38-3x4p](https://github.com/advisories/GHSA-pchr-8j38-3x4p): Pillow: TIFF encoder ColorMap plane-size mismatch causes a heap out-of-bounds read
- [GHSA-c6hw-5grf-ff96](https://github.com/advisories/GHSA-c6hw-5grf-ff96): Pillow: OOB read in TiffDecode.c tile and RGBA paths leaks heap memory into pixels
- [GHSA-8g5g-8vmr-9782](https://github.com/advisories/GHSA-8g5g-8vmr-9782): Pillow: Heap buffer overflow in ImagingJpegDecode

Полный rebuild/сравнение качества с исходными PNG не выполнены: исходный PNG-pack и
texture ZIP не предоставлены, `emoji/experiments` отсутствует в checkout. Неизвестно,
сохранится ли фиксированный baseline SHA256 сборщика после обновления Pillow/libwebp;
этот check не удалялся и не обходился. Аналогично в `test_java_loader.py` ссылки на
`emoji/tests/src` и `emoji/EmojiPack.java` не существуют в этом checkout. Проверка
текущего бинарного пака — декодирование, а не воспроизведение сборки или quality gate.

## Выполненные проверки

- Все 44 `scripts/test-pengram-*.py` — зелёные.
- Новый `scripts/test-emoji-security-runtime.py` в venv с пинами — зелёный.
- `python3 -m py_compile TMessagesProj/emoji/*.py` — успешно.
- Все изменённые/новые Java-файлы разобраны tree-sitter-java без синтаксических ошибок.
  **Это не компиляция и не проверка разрешения типов.**
- Декодировано 3606 emoji из `assets/emoji.pack`; SHA256 последовательности RGBA:
  `1820abb01bd1ffd4055c7139c8f11f673981dadd0bb1737aa76f0281163b474b`.
- `python3 scripts/gen-search-index.py` — 389 записей.
- `git diff --check` — успешно.

### Сборка и устройство

Java/Android SDK локально отсутствуют. Последний доступный preflight GitHub
[39684180494](https://github.com/syslikoffnet/Telegram/actions/runs/39684180494)
не получил runner: “recent account payments have failed or your spending limit
needs to be increased”. Добавлен trigger текущей ветки в preflight, но без
исправления биллинга зелёная сборка недоступна. Эмулятор/устройство не подключены.
Все ручные пункты приёмки остаются невыполненными; прежде всего нужен compile,
затем перечисленные выше сценарии.
