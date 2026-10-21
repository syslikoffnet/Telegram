#!/usr/bin/env python3
"""Настройки вида должны применяться сразу, а не после перезахода в чат.

Тестеры видят это как «переключил — ничего не изменилось»: ячейка сообщения
уже построена со старым видом, а пересобрать её никто не просил. Здесь
проверяется, что у чата есть отпечаток всех настроек отрисовки, что он
сравнивается при возвращении на экран и что по изменению сообщения реально
пересобираются.

Заодно проверяются найденные по пути недоработки: переключатель «всегда
спрашивать чат» был в списке, но ни на что не влиял; плитка «удалённые» читала
поле вместо геттера и не обновляла открытые чаты; шапка раздела проигрывала
появление на каждую пересборку списка; предпросмотр свайпа собирал строки в
каждом кадре; у пингвина два потока дрались за один EGL-контекст.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

config = (java / 'org/telegram/messenger/PengramConfig.java').read_text()
chat = (java / 'org/telegram/ui/ChatActivity.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
hero = (java / 'org/telegram/ui/Components/PengramSectionHero.java').read_text()
toggles = (java / 'org/telegram/ui/Components/PengramQuickToggles.java').read_text()
swipe = (java / 'org/telegram/ui/Components/PengramSwipePreview.java').read_text()
penguin = (java / 'org/telegram/ui/Components/PengramPenguinView.java').read_text()

# --- 1. отпечаток вида: все ключи, которые читает ячейка сообщения -----------
assert 'public static int chatLookSignature(int account)' in config
signature = config.split('public static int chatLookSignature(int account)')[1].split('\n    }')[0]
for read in ('isHidingBubbleTail()', 'shouldHideEditedLabel()', 'getMediaTimeMode()',
             'isMediaTimeRoundHidden()', 'isMediaTimeKeptWhileSending()', 'getGroupAvatarPos()',
             'isNoRounding()', 'getDeletedMark()', 'getEditedMarkIconRes()', 'isFadingDeleted()',
             'isKeepingDeletedInChat(account)', 'isSavingDeleted(account)',
             'PengramLocalKeep.keepDeletedMessages()'):
    assert read in signature, read

# --- 2. чат сравнивает отпечаток и пересобирает сообщения -------------------
assert 'PengramConfig.chatLookSignature(currentAccount)' in chat
assert 'chatAdapter.notifyDataSetChanged(false);' in chat
assert 'chatListView.invalidateViews();' in chat
# отпечаток считается в onResume, а не только при открытии чата
resume = chat.split('public void onResume()')[1].split('activityResumeTime')[0]
assert 'pengramCheckMarksSettings();' in resume

# --- 3. «всегда спрашивать чат» больше не мёртвый переключатель -------------
assert 'items.add(check(PengramConfig.KEY_RESEND_ASK_CHAT' in settings
assert 'PengramConfig.isResendAskChat()' in chat
assert 'pengramResendToChat(selectedObject);' in chat

# --- 4. плитка «удалённые»: геттер, профиль аккаунта и мгновенное обновление -
assert 'PengramConfig.isSavingDeleted(currentAccount)' in settings
assert 'PengramHistory.notifyMarksChanged()' in settings
assert 'PengramConfig.saveDeleted;' not in settings

# --- 5. шапка раздела: появление проигрывается один раз --------------------
assert 'private boolean appearPlayed;' in hero
assert 'if (!appearPlayed) {' in hero

# --- 6. предпросмотр свайпа: никаких строк в каждом кадре -------------------
onDraw = swipe.split('protected void onDraw(Canvas canvas)')[1].split('private void updateCachedText')[0]
assert 'LocaleController.getString' not in onDraw, 'getString снова в onDraw'
assert 'String.valueOf(TextUtils.ellipsize' not in onDraw, 'ellipsize снова в onDraw'
assert 'updateCachedText(' in onDraw
assert 'private void updateCachedText(' in swipe

# --- 7. пингвин: поверхность пересоздаётся без гонки за EGL ----------------
assert 'glToken' in penguin and 'glLock' in penguin
assert 'private volatile boolean running;' not in penguin
assert 'previous.join(' in penguin
assert 'releaseGLLocked();' in penguin
assert 'onSurfaceTextureDestroyed' in penguin

print('chat look: settings apply without re-entering the chat, dead switch wired, '
      'hero/preview/penguin render fixes in place: OK')
