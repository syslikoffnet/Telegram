# Штатная анимация отправки сообщения возвращена

Дата: 2026-10-22 · ветка `arena/c8e4a10f-telegram` · откат `af2fa9c`

## Что просил пользователь

«Сейчас вовсе нет анимации отправки сообщений — откати к обычной телеграмовской
отправке сообщений с её анимациями, но ничего не сломай».

## Что сделано

Откат полный, к состоянию до `af2fa9c` (базовый коммит `44b113c`), где
`ChatListItemAnimator.java` побайтово совпадал с upstream DrKLO/Telegram:

| Файл | Действие |
|---|---|
| `ui/recyclerview/ChatListItemAnimator.java` | возвращён к `44b113c`: в `animateAddImpl(holder, addedItemsHeight)` снова есть `else`-ветка, создающая `TextMessageEnterTransition`, и импорт `SharedConfig` |
| `ui/TextMessageEnterTransition.java` | возвращён к `44b113c` (снята добавленная Javadoc-плашка про отключение) |
| `scripts/test-pengram-send-text-draw.py` | возвращён к `44b113c`: снят контракт «overlay в аниматоре не создаётся» |
| `scripts/test-pengram-send-text-blank.py` | удалён — его 9 кейсов JVM-harness проверяли именно отключённый перелёт |
| `docs/pengram-send-text-blank.md` | удалён, заменён этим документом |

`git diff 44b113c HEAD -- ChatListItemAnimator.java TextMessageEnterTransition.java
scripts/test-pengram-send-text-draw.py` пуст: путь отправки снова ровно тот, что
в upstream, вместе со всеми его анимациями — перелёт текста из поля ввода в
пузырь, перелёт голосового, появление строки снизу, сброс композиции.

Условие запуска штатное: `SharedConfig.getDevicePerformanceClass() !=
PERFORMANCE_CLASS_LOW && Math.abs(view.getTranslationY()) <
recyclerListView.getMeasuredHeight()`, то есть на слабых устройствах и при
далёком смещении строки перелёт текста не создаётся — так же, как в оригинале.

## Что осталось нетронутым

- Все прочие форк-фиксы этой ветки: пингвин настроек (256dp/96dp),
  `FragmentContextView.pengramFadeHeader`, превью отправки, страховки
  `MessageEnterTransitionContainer` (stableId/generation), watchdog
  `ChatMessageCell` (500 мс), gate `enterTransitionInProgress`.
- `TextMessageEnterTransition`, `VoiceMessageEnterTransition`,
  `MessageEnterTransitionContainer` — без изменений относительно базы.
- Улучшение шага «Test all Pengram regressions» в
  `.github/workflows/preflight-android.yml` (вывод падения сюита и
  `::error`-аннотации) оставлено: оно к анимации отношения не имеет.

## Известная цена возврата (честно)

Вместе со штатной анимацией возвращается и её особенность, из-за которой
возник прежний запрос: на время перелёта `ChatMessageCell.enterTransitionInProgress`
запрещает ячейке рисовать собственный текст, а overlay рисует и пузырь
(`drawable.setAlpha(255 * alphaProgress)`), и текст
(`drawMessageText(..., alphaProgress, true)`) с
`alphaProgress = progress > 0.4f ? 1f : progress / 0.4f` при
`DEFAULT_DURATION = 250`. То есть первые ~100 мс сообщение выглядит пустым,
затем текст появляется. Если overlay не создаётся (поле ввода уже очищено —
конструктор выходит по `TextUtils.isEmpty`) или завершается досрочно (смена
`stableId`/`MessageObject`/generation), пустой пузырь может жить до 250 мс, а
watchdog добивает флаг через 500 мс.

Это не форк-регрессия, а устройство штатного «морфа отправки» Telegram: тот же
код работает в оригинальном клиенте. Выбор пользователя — штатная анимация
ценой этого короткого окна.

## Если понадобится снова отключить перелёт

Одна правка в `ChatListItemAnimator.animateAddImpl(holder, addedItemsHeight)`
(:340): убрать `else`-ветку с `new TextMessageEnterTransition(...)` (и импорт
`SharedConfig`, если станет неиспользуемым). Появление строки снизу, перелёт
голосового и `startMessageTransition()` при этом сохраняются — ровно то, что
было в `af2fa9c`.

## Проверки

- Локально все 59 сюитов `scripts/test-pengram-*.py` — exit 0
  (на один меньше: удалён `test-pengram-send-text-blank.py`).
- `scripts/test-pengram-text-visibility.py` и
  `scripts/test-pengram-send-delete-regressions.py` проверяют контракты самого
  `TextMessageEnterTransition` (`finishTransition`, generation, bail-out по
  пустому полю ввода) — они писались под штатную версию и зелены.
- Инвентарь аудита пересобран `scripts/gen-pengram-audit.py`,
  `test-pengram-audit-clean.py` PASS.
- CI: preflight (компиляция Java, слияние ресурсов, все сюиты) и сборка ARM64
  APK — статусы в истории ветки.

**NOT RUN:** на устройстве и в пикселях анимация не проверялась — в этой среде
нет ни Android-устройства, ни эмулятора, ни GL. Равенство с upstream
установлено побайтовым diff против `44b113c`, а не сравнением с живым
клиентом Telegram.
