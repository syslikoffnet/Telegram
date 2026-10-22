# Отправленное сообщение стояло пустым, потом текст «вспыхивал»

Дата: 2026-10-22. Ветка `arena/c8e4a10f-telegram`, базовый коммит `44b113c`.

## Симптом (со слов пользователя)

После отправки сообщения в чат пузырь появляется сразу, но текста в нём какое-то
время нет, затем он появляется. Не воспроизводилось на устройстве здесь: устройства
и Android SDK в этой среде нет, ниже — подтверждённые пути в исходном коде, а не
утверждение, что каждый из них сработал в пользовательской сессии.

## Что это за «анимация»

Это штатный морф отправки Telegram: `TextMessageEnterTransition` рисует поверх
списка overlay, в котором текст «перелетает» из поля ввода в пузырь. Он создаётся
в `ChatListItemAnimator.animateAddImpl(holder, addedItemsHeight)`
(`ui/recyclerview/ChatListItemAnimator.java:340`, ветка добавления строки снизу —
`runMessageEnterTransition`, `:293`), то есть ровно при отправке своего сообщения.

## Причина пустого пузыря

| Шаг | Код | Следствие |
|---|---|---|
| 1 | `TextMessageEnterTransition.start()` → `messageView.setEnterTransitionInProgress(true)` (`ui/TextMessageEnterTransition.java:460`) | ячейка перестаёт рисовать собственный текст: `ui/Cells/ChatMessageCell.java:14552` |
| 2 | overlay рисует пузырь с `drawable.setAlpha((int) (255 * alphaProgress))` (`:567`) | первые кадры пузырь полупрозрачный |
| 3 | `alphaProgress = this.progress > 0.4f ? 1f : this.progress / 0.4f` (`:521`), `DEFAULT_DURATION = 250` (`ChatListItemAnimator.java:45`) | ~100 мс alpha растёт от 0 до 1 |
| 4 | текст сообщения в overlay рисуется тем же `alphaProgress`: `messageView.drawMessageText(canvas, ..., true, alphaProgress, true)` (`:891`) | ~100 мс текста в пузыре нет, затем он появляется рывком |

Дополнительно пустой пузырь жил дольше 100 мс, если overlay вообще не рисовал
текст ячейки:

- конструктор выходит по `TextUtils.isEmpty(AndroidUtilities.trim(editText, null))`
  (`:171`) — поле ввода очищается через 200 мс после отправки
  (`ui/Components/ChatActivityEnterView.java:7626`), и при медленной вставке строки
  overlay не создаётся вовсе, хотя страховка ячейки уже могла быть взведена;
- `onDraw` завершает переход при смене `stableId` (`:507`), `MessageObject` или
  generation (`:481`), а флаг ячейки при этом снимается только через `finishEnterTransition`;
- крайний срок — watchdog `DEFAULT_DURATION + 250 = 500 мс`
  (`ui/Cells/ChatMessageCell.java:316`).

Отсюда «текста нет какое-то время, потом появляется»: от ~100 мс (штатный
crossfade) до 250–500 мс (overlay не состоялся).

## Исправление

`ui/recyclerview/ChatListItemAnimator.java:340` — overlay перелёта текста при
отправке больше не создаётся. Ячейка рисует свой пузырь и свой текст сразу и
полностью; `enterTransitionInProgress` для текстовых сообщений не взводится ни в
одном месте (единственный владелец — `TextMessageEnterTransition.start()`).

Сохранено без изменений:

- штатное появление строки снизу (`translationY → 0`, 250 мс) и весь
  `ChatListItemAnimator` для удалений/перемещений/изменений;
- перелёт голосового сообщения `VoiceMessageEnterTransition` (у него свой текст,
  симптом к нему не относится);
- немедленный сброс поля ввода `startMessageTransition()` — иначе текст поля
 очищался бы только через 200 мс;
- все прежние страховки (`MessageEnterTransitionContainer`, generation/watchdog,
  plain-text fallback вёрстки, viewport-диапазон overlay) — они остались и
  продолжают защищать голосовой перелёт и превью отправки;
- `TextMessageEnterTransition` не удалён: отключение обратимо в одну строку
  (ветка `else` с `new TextMessageEnterTransition(...)`), import оставлен.

Это тот же путь, которым Telegram рисует отправку на
`PERFORMANCE_CLASS_LOW` и при большом смещении строки, то есть поведение не новое.

Побочные видимые отличия от прежнего вида: текст больше не «летит» из поля ввода
и не растворяется в пузыре, а кнопка отправки не рисуется летящей вместе с
overlay (`ChatActivityEnterView.drawChild` прячет её только при
`textTransitionIsRunning`, который теперь не взводится).

## Проверка

- Новый `scripts/test-pengram-send-text-blank.py`: source-контракты
  (overlay не создаётся, голосовой перелёт и сброс поля ввода на месте, контракт
  ячейки `!enterTransitionInProgress` и watchdog сохранены) + JVM-исполнение
  настоящего production `animateAddImpl` с фейковым Android: 8 случаев
  (текст на мощном/слабом устройстве, далеко/близко, не из отправки, запрещённый
  переход, голосовое близко/далеко) и 2 отрицательных контроля (возврат overlay →
  `blank_bubble`, потеря голосового перелёта → `voice_transition_lost`).
  Локально **SKIP: JDK в этой среде нет**, синтаксис сгенерированного harness
  проверен парсером javalang; исполнение — только в preflight с JDK17.
- `scripts/test-pengram-send-text-draw.py`: добавлен контракт, что overlay в
  аниматоре не создаётся; прежние контракты forwarding/диапазона/alpha ячейки не
  ослаблены (они по-прежнему нужны голосовому перелёту и превью).
- Локальный прогон всех suite: `test-pengram-*` — 0 ненулевых выходов после
  пересчёта `docs/pengram-audit-inventory.json` штатным
  `scripts/gen-pengram-audit.py` (единственное отличие — лексический счётчик
  `start` 2511 → 2510 из-за удалённого `transition.start()`).
  `test-voice-studio.py`, `test-native-report.py`, `test-cover-cache.py` локально
  падают из-за отсутствия `javac`, `test-emoji-security-runtime.py` — из-за
  отсутствия numpy; это ограничения среды, они же были до изменения, в CI обе
  зависимости есть.
- Ветка добавлена в триггеры `preflight-android.yml` (компиляция Java JDK17,
  merge ресурсов, все suite) и `build-android-arm64.yml` (APK), чтобы изменение
  было проверено настоящей сборкой и чтобы был APK для установки.

## NOT RUN / границы

- Устройство/пиксели: не проверялось. Нет ни одного скриншота или записи экрана,
  подтверждающих, что после изменения текст виден в первом же кадре — это
  следует из того, что ячейка больше не прячет текст и ничто не рисует его с
  alpha < 1.
- Точный вклад каждого из четырёх механизмов в пользовательском сеансе не
  измерен: все они подтверждены по коду, но не воспроизведены здесь.
- Поведение при отправке в медленном чате, с форматированием, emoji/RTL, при
  редактировании и пересылке на устройстве не проверялось (ручная матрица
  `docs/pengram-text-invisible-report.md` остаётся NOT RUN).
- Откат: вернуть ветку `else` с `new TextMessageEnterTransition(...)` в
  `animateAddImpl(holder, addedItemsHeight)` — overlay снова возьмёт текст на
  время перелёта вместе со старым ~100 мс crossfade.
