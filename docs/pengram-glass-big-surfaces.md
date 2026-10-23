# Жидкое стекло и MD3 в больших местах

Контекст: после полного отката к мастеру (`fe99da9`, байт-в-байт
`build-master-20261023-1345`) пользователь заказал вернуть MD3 и жидкое
стекло на крупные поверхности. Всё восстановлено выборочно из истории
коммита `f03ce811` — линейные файлы пути отправки не трогались.

## Что входит в этот шаг

| Место | Механика | Переключатель (Настройки → Вкладки) |
|---|---|---|
| Шапка чата | `ChatActivity.refreshPengramSurfaces` → `ActionBar.setupGlass` + линза + блик | `chatHeaderLiquidGlass` (по умолчанию вкл) |
| Панель ввода чата | та же пересборка, `ChatInputViewsContainer.setPengramGlass` + sheen | `inputBarLiquidGlass` |
| Шапка списка чатов + верхняя панель | `DialogsActivity.refreshPengramHeaderGlass` + sheen на панели | `chatListHeaderGlass` |
| Общая шапка любых экранов | `ActionBar`: сохранение фона, `applyPengramGlassLens`, `clearGlass` | зависит от настроек экрана |
| Шторки MD3 | `PengramMD3.styleSheetHeader` (тонированный заголовок), живое обновление `watch`/`notifyChanged` | `md3*` (общий тумблер MD3) |

Общие ползунки (плотность, толщина линзы, преломление, блик) шарятся со
всеми поверхностями и после правки шлют `PengramGlassSkin.notifyChanged()`.

## Намеренно НЕ перенесено (следующий шаг)

- `PengramSettingsSurfaceGlass` — «стекло над обложками и списками»:
  строка настройки не выводится, пока не перенесены проводки
  `PengramMusicPlayerSheet` (шапка плеера над обложкой) и
  `PengramMenuItemsActivity` (плавающая панель «Готово»). Ключ конфига
  есть, тумблера нет — иначе получается мёртвый переключатель.
- Прочая линейка (поиск-destination, typing-пресеты, история, диагностика,
  следящие MD3-перерисовки в Constructor/History).

## Гарантии

- `scripts/test-pengram-glass-big-surfaces.py` — обвязка на месте,
  строки en/ru, а `TextMessageEnterTransition`/`ChatMessageCell`/
  контейнер/аниматор/`MessageObject` байт-в-байт равны мастер-тегу.
- Преломление — только Android 13+ (`PengramGlassSkin.refractionAllowed`),
  на старых — тонированная подложка без линзы.
- Первый кадр: пока watcher не захватил сцену, стекло не рисуется
  (`BlurredBackgroundSource.hasContent` + guard в
  `BlurredBackgroundDrawableRenderNode.draw`) — чёрной вспышки при
  входе в чат нет; устоявшийся вид не меняется. Подробности —
  `docs/pengram-chat-enter-anim.md` и
  `scripts/test-pengram-chat-enter-anim.py`.
