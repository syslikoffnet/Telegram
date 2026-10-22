# Текст отправленного сообщения и рассыпание удаляемых букв

Дата: 2026-10-22. Baseline: `45950bf83c4b224a9e34c90bde99d1b9b86ad659`.

## Proof log

Пользователь наблюдает пустой текст после send до его появления и нерегулярное рассыпание удаляемых букв. На устройстве это здесь не воспроизводилось: ниже подтверждены конкретные пути исходного кода, не утверждается, что каждый из них сработал в пользовательской сессии.

| Подтверждённый механизм | Исправление | Проверка |
|---|---|---|
| checkVisibility отменял старый hideRunnable только если container ещё не VISIBLE. Новый overlay мог зарегистрироваться при VISIBLE, а старый idle callback позже скрывал его, пока ChatMessageCell не рисует собственный текст (ChatMessageCell:14552) | `ui/MessageEnterTransitionContainer.java:65`: отменять отложенное скрытие при любом непустом списке. Callback также повторно проверяет transitions.isEmpty перед GONE | Новый test-pengram-send-delete-regressions.py сначала exit1 на baseline: active overlay must cancel pending hide even when already VISIBLE. JVM harness выполняет настоящий production checkVisibility и hide callback:100циклов с отменой/уже извлечённым callback/завершением |
| Конструктор TextMessageEnterTransition мог получить уже пустой composer и затем скрыть реальную ячейку ради пустого layout/crossfade | `ui/TextMessageEnterTransition.java:159`: пустой/пробельный source не получает владение текстом. Animator не создаётся, ячейка рисуется обычным путём | Source contract до start/animator; форматирование и обычный send morph сохранены |
| onDraw при изменившемся stableId выходил без release; ячейка оставалась скрытой до watchdog | `ui/TextMessageEnterTransition.java:495`: немедленно finishTransition перед return | Source contract; предыдущие generation/cancel/detach/watchdog tests сохранены |
| live/settingsChanged отключали все эффекты при INPUT_ANIM_NONE, включая отдельно включённое удаление | `ui/Components/PengramTypingEffects.java:107`: появление, удаление и плавный курсор включаются независимо; reduce motion по-прежнему запрещает всё движение | Source contracts; insertion/commit отдельно запрещают enqueue при INPUT_ANIM_NONE |
| ChatActivityEnterView снимал snapshot только при after==0 и без composing. IME backspace часто заменяет целое слово более коротким | `ui/Components/ChatActivityEnterView.java:5382`: snapshot перед любым shrinking edit; apply допускает composing deletion, но явно получает composition flag, чтобы не анимировать недокоммиченный вставляемый текст | Source contracts. Не меняются Editable, InputConnection semantics, стили, IME span-и или отправка |
| Нормализация prefix/suffix получала удалённый поддиапазон, но spawn требовал точного равенства исходному snapshot. Density cap отбрасывал хвост слова ещё до фильтрации | `ui/Components/PengramTypingEffects.java:583`: сохраняются UTF-16 offsets целых кластеров; выбираются только полностью удалённые кластеры; лимит применяется после фильтра. Snapshot бюджет24кластера смещён к старому курсору одним прямым проходом и переиспользует offset array как ring buffer (без повторных сканирований строки) | Второй JVM harness исполняет настоящий spawnFromSnapshot:9случаев long-word tail, selection, stale range, Cyrillic/Arabic/ZWJ cluster и запрет частичного кластера |
| Preview имел отдельный after==0 watcher, не captureReplacement, и неверный global-off caption при delete ON / appear NONE | Preview использует тот же replacement/deletion flow и явный composition flag; enabled учитывает отдельные эффекты | Source contract; обе setComposingText overloads сохраняют flag через finally |

## Границы изменений

- Изменены: MessageEnterTransitionContainer, TextMessageEnterTransition, PengramTypingEffects, ChatActivityEnterView, PengramTypingPreviewView.
- Новый тест использует только Python stdlib; JVM части требуют javac/java. Fake Android scheduler/particle classes проверяют извлечённые production методы, а не настоящие Android пиксели/IME.
- Inventory пересоздан штатным gen-pengram-audit.py из-за смещённых строк. gen-search-index.py запущен:402записи, новых строк/пунктов не добавлено.
- Существующие тесты не ослаблялись. В preflight уже полный glob test-pengram-*.py, теперь56suite.
- На onDraw/onMeasure не добавлялись new/DB/network/logging. Новые offset arrays создаются один раз в State, snapshot/raster работа остаётся на beforeTextChanged.
- Не менялись sending/network/auth/session/history/JNI/API/signing/ProGuard, APK publication workflow. Новый PR/merge не выполнялись.

## Проверка

Первый запуск нового теста **до исправления**: exit1, лог `/home/user/pengram-send-delete-red.log`. После изменений: exit0. Полный локальный прогон **56 scripts,0 nonzero exits,последний27.0s**. Локального JDK нет, поэтому новые extracted-method JVM harness и прежние JVM subchecks здесь SKIP, не PASS. Android Java/resources и JVM подтверждаются отдельно настоящим GitHub preflight; APK сборка отдельно.

**CI: PENDING. APK: PENDING.** Обновить только после реального результата, не на основании Python/source PASS.

## Ручные сценарии: все NOT RUN (устройства нет)

1. Send короткого текста, длинного текста, нескольких быстрых подряд; текст не исчезает между overlay.
2. Send после очистки composer, обычного/форматированного текста, emoji/RTL; нативный текст не подменяется.
3. Send/back/возврат и новая отправка до предыдущего idle-hide; нет пустого пузыря.
4. Удаление в режиме appear NONE/delete LETTERS ON, count1/2/0; эффект независим, count0 отключает частицы.
5. Gboard/Xiaomi IME: backspace внутри composing word, удержание backspace, длинное слово>24графем.
6. Selection/delete в середине, кириллица/Arabic/ZWJ emoji; целый глиф, без распада UTF-16.
7. Paste/undo/autofill/draft/отправка/переключение чата не создают ложных deletion particles.
8. Reduce motion/system animations OFF: частиц нет, текст и курсор остаются нормальными.
9. Settings preview даёт тот же эффект удаления; appear NONE не означает delete OFF.

## Заключительные строки всех локальных suite

```text
test-pengram-account-order.py [exit=0]: account order: drag in settings, one order for every account list: OK
test-pengram-audit-clean.py [exit=0]: audit regression contracts + reproducible inventory PASS; semantic key/dispatch/RTL review remains open
test-pengram-bot-login.py [exit=0]: bot token login: OK
test-pengram-chat-menu-live.py [exit=0]: chat menu: all items editable, popup rebuilds in place, island refreshes live: OK
test-pengram-community.py [exit=0]: community setup: dry-run by default, forum topics, pinned texts, 10 avatars, secrets kept out: OK
test-pengram-deleted-edited.py [exit=0]: HOST SQLite medians ms (not device): {"before_indexes": {"dialog_count": 0.89, "saved_page": 2.677, "search_page": 3.402}, "after_indexes": {"dialog_count": 0.392, "saved_page": 0.029, "search_page": 0.077}}
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
test-pengram-send-delete-regressions.py [exit=0]: OK: send overlay ownership, empty-source fallback, independent delete effect and IME subranges; JVM=SKIP(no JDK)
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
