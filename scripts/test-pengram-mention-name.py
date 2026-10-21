#!/usr/bin/env python3
"""Упоминание по имени из контактов: долгое нажатие в списке «@…».

Требование: человека без юзернейма (и любого другого) можно упомянуть по
имени, под которым он записан в контактах, не раскрывая @username. Вставка
идёт обычной сущностью mention_name, поэтому уведомление приходит, а ника в
тексте нет. Настройка объясняет жест и ничего не делает сама по себе.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

name = (java / 'org/telegram/messenger/PengramMentionName.java').read_text()
chat = (java / 'org/telegram/ui/ChatActivity.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
en = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()
ru = (root / 'TMessagesProj/src/main/res/values-ru/strings.xml').read_text()

# --- отдельный класс, без телефона и без сети -------------------------------
assert 'public static final String KEY_ENABLED = "mentionByName";' in name
assert 'PengramConfig.getBool(KEY_ENABLED, true)' in name
assert 'public static String displayName(TLRPC.User user)' in name
assert 'public static CharSequence buildMention(TLRPC.User user)' in name
assert 'UserObject.getUserName(user)' in name
assert 'UserObject.getFirstName(user, false)' in name
assert 'new URLSpanUserMention(String.valueOf(user.id), 3)' in name
assert 'Spanned.SPAN_EXCLUSIVE_EXCLUSIVE' in name
assert 'MAX_LENGTH' in name and 'isHighSurrogate' in name    # эмодзи не режем
assert 'phone' not in name.lower()                            # номер не трогаем
assert 'sendRequest' not in name                              # и в сеть не ходим

# --- жест: долгое нажатие в обычном списке упоминаний -----------------------
assert 'mentionContainer.getListView().setOnItemLongClickListener' in chat
assert 'if (!mentionContainer.getAdapter().isLongClickEnabled()) {' in chat
assert 'if (PengramMentionName.isEnabled()) {' in chat
assert 'final Object mentionItem = mentionContainer.getAdapter().getItem(position - 1);' in chat
assert 'mentionItem instanceof TLRPC.User' in chat
assert 'return pengramInsertMentionByName((TLRPC.User) mentionItem, view);' in chat
assert 'private boolean pengramInsertMentionByName(TLRPC.User user, android.view.View view)' in chat
assert 'PengramMentionName.buildMention(user)' in chat
assert 'adapter.getResultStartPosition(),' in chat
assert 'chatActivityEnterView.replaceWithText(' in chat
assert 'mentionContainer.updateVisibility(false);' in chat
assert 'HapticFeedbackConstants.LONG_PRESS' in chat
# жест не перехватывает хэштеги и команды: там ветка long click работает как раньше
assert 'position--;\n            Object object = mentionContainer.getAdapter().getItem(position);' in chat

# --- настройка ---------------------------------------------------------------
assert 'BTN_MENTION_ALL_STYLE = 1050;' in settings
assert 'items.add(checkInfo(org.telegram.messenger.PengramMentionName.KEY_ENABLED, true,' in settings
assert 'getString(R.string.PengramMentionName)' in settings
assert 'getString(R.string.PengramMentionNameInfo)' in settings
assert 'getString(R.string.PengramMentionsHeader)' in settings

# --- строки в обеих локалях ---------------------------------------------------
for locale, text in (('en', en), ('ru', ru)):
    for key in ('PengramMentionsHeader', 'PengramMentionName', 'PengramMentionNameInfo'):
        assert 'name="%s"' % key in text, (locale, key)

print('mention by contact name: long press, mention_name entity, setting, both locales: OK')
