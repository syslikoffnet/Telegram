# Pengram: меню чата, тема и изоляция импортированного аккаунта

Дата: 22.10.2026. Ветка: `arena/42ea70d0-telegram`.
Функциональные изменения: `0dc5927f`, `908de62f` и `09e4396f`.

## Что исправлено и чем подтверждено

| Дефект / риск | Проверяемый механизм | Исправление |
|---|---|---|
| В Pengram-подменю только фон | `PengramChatMenuWrapper.java:75`; popup переопределяет `addView`, добавляя кнопки во внутренний `linearLayout`. Старый `removeAllViews` удалял внешний scroll/swipe-back контейнер, оставляя список отсоединённым. | Штатный `removeInnerViews`: удаляются кнопки, а не иерархия popup. |
| NPE `Drawable.setBounds` при теме | Предоставленный стек называет `ChatInputViewsContainer.dispatchDraw`; в исходной версии оба drawable разыменовывались без проверки. | `ChatInputViewsContainer.java:278–346`: независимые проверки обоих drawable, фон текущей темы при их отсутствии, сохранены `super.dispatchDraw`, дети и clip keyboard. Setters/updateColors/insets также выдерживают неполную инициализацию. Alpha сохраняется при замене drawable. Новых аллокаций в dispatchDraw/fallback нет. |
| После темы новые backgrounds вообще не назначались | `ChatActivity.createView` создаёт новый контейнер и вызывает refresh, но сохранённые поля сигнатуры оставались от старого view. `ActionBarLayout.rebuildAllFragmentViews` переиспользует фрагмент после clearViews. | `ChatActivity.java:4798,28936–28942`: обе surface-сигнатуры сбрасываются перед новым createView; refresh снова назначает drawable. Это устраняет пропуск инициализации, а не только маскирует NPE плоским fallback. |
| Вкладка аккаунта A может создать ребёнка аккаунта B | `BaseFragment.java:79` по умолчанию берёт глобальный `selectedAccount`; `ViewPagerActivity` создавал страницы лениво без привязки к account родителя. | `ViewPagerActivity.java:92,131–139`: account назначается до `onFragmentCreate`/регистрации observer-ов, включая заранее созданную страницу диалогов. |
| Аватар запрашивается другим account | `GlassTabView` получал User по переданному account, но ImageReceiver оставался с account по умолчанию. | `GlassTabView.java:473,489`: явный account и при создании, и при обновлении аватара. |
| Неперестроенный root при уже изменённом selectedAccount | Старый `switchToAccount` сразу возвращался при совпадении только глобального selectedAccount. Это не доказывало account показанного root. | `LaunchActivity.java:1211–1220,1248`: проверка account Activity и корневого фрагмента; новый MainTabs получает target account явно. |
| Неверный профиль после импорта | Старый импорт выбирал `full.users.get(0)`. Вектор также содержит связанных пользователей и не гарантирует self на первом месте. | `PengramSessionLogin.java:188,214–265`: ищется пользователь по `full_user.id` с `self`, отвергаются deleted/неоднозначные ответы, несовпадение userId файла и дубликат существующего профиля. Legacy full_user.user проверяется отдельно. |
| Пустой UserConfig поверх старого кеша | Нулевой clientUserId сам по себе не доказывает отсутствие локальных данных. | `MessagesStorage.java:239–264`, `PengramSessionLogin.java:126`: до native cleanup/import проверяются dialogs/messages_v2/enc_chats/users/chats/contacts на storageQueue. Любая найденная строка или ошибка чтения отклоняет импорт, а не удаляет сохранённые данные. |
| Старый callback применён не к своему слоту | Account фрагмента и target потока должны совпадать непосредственно перед штатным onAuthSuccess. | `LoginActivity.java:1887–1908`: captured importedAccount и повторная проверка занятого/local-only слота. Нет дополнительного UI-post между проверкой и commit. |

Это подтверждённые дефекты/нарушения инвариантов исходного кода. Без трассы двух аккаунтов с телефона нельзя утверждать, что каждый из них наблюдался в конкретной пользовательской сессии или что исключены все причины уже повреждённого кеша/native-конфига.

## RED → GREEN и регрессии

Новый suite: `scripts/test-pengram-chat-account-theme.py`.

- До исправления baseline `ca98b56a` не проходил контракт сохранения popup; отдельно зафиксировано отсутствие каждой новой защиты в исходном baseline.
- Дополнительный RED theme lifecycle: createView не сбрасывал surface-кеш до создания нового контейнера.
- Дополнительный RED: проверка профильного кеша не проходила, пока проверялись только сообщения/диалоги, но не users/chats/contacts.
- Старый `test-pengram-chat-menu-live.py` требовал именно ошибочный внешний removeAllViews. Контракт заменён на проверку штатного inner-clear и сохранения внешней структуры; существующие проверки состава/быстрого применения меню сохранены.
- 68 компонентных JVM-кейсов исполняют извлечённые production-методы: self/legacy/порядок вектора/неоднозначность; 20 rebuild popup; parent account; матрица двух nullable drawable, флагов отрисовки и повторного сброса; сохранение alpha; сброс кеша при пересоздании view.
- 18 JVM-кейсов исполняют production start/connect/cancel/succeed/fail/self resolver и production storage-check с fake transport/SQLite. Проверяются дубликаты, несовпадение metadata, занятый/unloaded/local-only слот, отменённый и опоздавший ответ, каждый из шести типов сохранённых строк и ошибка чтения. Главный slot=0 не должен менять userId и не должен получать cleanup/import.
- Пять отрицательных JVM-контролей должны падать при возвращении внешнего removeAllViews, unguarded drawable, глобального account ребёнка выбора users[0] и сохранения старой surface-сигнатуры при новом view.
- JVM использует host stubs: это не Android rendering, не настоящий SQLite/native network и не проверка устройства.
- Локально: **57 suites, 0 failures**, 29.2 с; локальные JVM-тесты **SKIP**, поскольку javac отсутствует.
- Search index пересоздан: 402 пункта, изменения не потребовались. Generated audit воспроизводим, проверка PASS.

## Сборки

Функциональный source commit: `09e4396fb2a2c22204b62870e758cf59fb350a95`.

- [Финальный preflight 39771397789](https://github.com/syslikoffnet/Telegram/actions/runs/39771397789): **SUCCESS**, 6 мин 12 с. Java, ресурсы, все 57 suites с JDK 17, включая 86 production-method JVM-кейсов и пять отрицательных контролей.
- [Финальный APK #366 / 39771397842](https://github.com/syslikoffnet/Telegram/actions/runs/39771397842): **SUCCESS**, 18 мин 7 с. Build, R8/obfuscation, signing schemes, protection и Upload APK — SUCCESS.
- Artifact: [`Pengram-ARM64-standalone-run-366`](https://github.com/syslikoffnet/Telegram/actions/runs/39771397842/artifacts/12423965847), ID `12423965847`, **48,165,755 bytes**, не expired. APK соответствует функциональному source commit `09e4396f`; этот отчёт добавлен отдельно без изменения кода приложения.

Промежуточный `908de62f`: [preflight 39769376258](https://github.com/syslikoffnet/Telegram/actions/runs/39769376258) SUCCESS, включая 85 JVM-кейсов и четыре отрицательных контроля. Он ещё не содержит сброса theme lifecycle cache из `09e4396f`.

Промежуточный `0dc5927f`: [preflight 39768916757](https://github.com/syslikoffnet/Telegram/actions/runs/39768916757) SUCCESS. Он ещё не содержит расширенной проверки profile-only кеша из `908de62f`.

## Что не менялось

APK workflow/публикация, JNI, API ID/hash, подпись, сетевое шифрование и порядок аккаунтов не менялись. Новых app dependencies нет. Предыдущие send/delete/typing исправления сохранены. Новых PR и merge не было.

Существующие активные аккаунты и сохранённые локальные копии не сбрасываются этим исправлением. Импорт в слот с обнаруженными сохранёнными данными отклоняется. Уже смешанные исторические данные автоматически не переносятся, не «склеиваются» и не удаляются: происхождение строк нельзя надёжно восстановить по одному описанию.

## Ручная проверка — NOT RUN

Нет доступа к Xiaomi duchamp/Android 14 пользователя и его двум аккаунтам. Не заявляются device/FPS/визуальные результаты.

1. Меню: вход/Back/swipe-back, несколько открытий, смена состава на открытом чате, быстрые действия.
2. Тема: светлая/тёмная/пользовательская при открытом чате, IME и emoji keyboard; composer и ввод остаются видимыми.
3. Два разных аккаунта A/B: импорт B в пустой слот, многократное переключение, все ленивые вкладки, имя/аватар/список чатов относятся к одному account.
4. Холодный старт и возврат из background во время переключения A/B.
5. Повторный импорт того же аккаунта с известным и неизвестным metadata userId должен быть отклонён без изменения A.
6. Неверный metadata userId/неподтверждённый self не должен создавать профиль.
7. Слот с сохранёнными локальными данными не должен терять их при отклонённом импорте.
8. Отмена импорта/закрытие LoginActivity до ответа, затем другой импорт; старый callback ничего не устанавливает.

Старый отдельный обфусцированный search/createView crash 71789 без точного mapping не считается этим исправлением воспроизведённым или доказанно устранённым. Новый предоставленный theme stack разобран отдельно, по явно названному классу и операции.
