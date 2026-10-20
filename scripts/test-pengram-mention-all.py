#!/usr/bin/env python3
"""«@all» в группах: обычные упоминания с жёстким лимитом.

Требование: упомянуть всех, но так, чтобы аккаунт не улетел в бан. Значит
жёсткий потолок (~100), пауза между использованиями, режим «только админы»,
по умолчанию выключено, и в сообщении — обычные упоминания Telegram, а не
самодельная разметка. Список участников грузится заранее, чтобы при отправке
в чат не ушёл буквальный «@all».
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

mention = (java / 'org/telegram/messenger/PengramMentionAll.java').read_text()
chat = (java / 'org/telegram/ui/ChatActivity.java').read_text()
enter = (java / 'org/telegram/ui/Components/ChatActivityEnterView.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
en = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()
ru = (root / 'TMessagesProj/src/main/res/values-ru/strings.xml').read_text()

# --- настройки и жёсткие пределы ---------------------------------------------
assert 'public static final String KEY_ENABLED = "mentionAllEnabled";' in mention
assert 'return PengramConfig.getBool(KEY_ENABLED, false);' in mention          # выключено по умолчанию
assert 'public static final int HARD_LIMIT = 100;' in mention
assert 'public static final int[] LIMITS = new int[]{25, 50, 100};' in mention
assert 'public static final int[] COOLDOWNS = new int[]{1, 5, 15, 30};' in mention
assert 'Math.max(1, Math.min(HARD_LIMIT, value));' in mention                  # потолок не обойти
assert 'public static int cooldownLeft(int account, long chatId)' in mention
# только группы: каналы-вещания не трогаем
assert 'return !ChatObject.isChannel(chat) || chat.megagroup;' in mention
assert 'if (!isEnabled() || !isGroup(chat)) {' in mention

# --- обычные упоминания Telegram ---------------------------------------------
assert 'new URLSpanUserMention(String.valueOf(user.id), 3)' in mention
assert 'out.replace(token[0], token[1], mentions);' in mention
assert 'UserObject.getPublicUsername(user)' in mention
assert 'UserObject.getFirstName(user, false)' in mention
# не упоминаем себя, ботов и удалённые аккаунты
assert 'user.deleted || user.bot || user.id == self' in mention
# команды, которые понимает текст
for token in ('"@all"', '"@все"', '"@everyone"'):
    assert token in mention, token
assert 'private static boolean isWordChar(char c)' in mention

# --- список участников: заранее и с кэшем ------------------------------------
assert 'public static void prefetch(int account, TLRPC.Chat chat)' in mention
assert 'TLRPC.TL_channels_getParticipants' in mention
assert 'TLRPC.TL_channelParticipantsAdmins' in mention
assert 'TLRPC.TL_messages_getFullChat' in mention
assert 'private static final long CACHE_TTL = 10 * 60 * 1000L;' in mention
assert 'public static int canSend(int account, TLRPC.Chat chat, CharSequence text)' in mention

# --- отправка: не буквальный «@all» ------------------------------------------
assert 'public boolean pengramMentionAllCanSend(CharSequence text)' in chat
assert 'if (!parentFragment.pengramMentionAllCanSend(text)) return false;' in enter
assert 'part = parentFragment.pengramMentionAllTextForSend(part);' in enter
assert 'parentFragment.pengramMentionAllTyped(charSequence);' in enter
assert 'public void pengramMentionAllTyped(CharSequence text)' in chat
assert 'public CharSequence pengramMentionAllTextForSend(CharSequence text)' in chat
# проверка готовности стоит ДО нарезки текста на части
can_pos = enter.index('pengramMentionAllCanSend(text)')
part_pos = enter.index('pengramMentionAllTextForSend(part)')
assert can_pos < part_pos

# --- настройки: без дублей, с вариантами -------------------------------------
assert 'private static final int BTN_MENTION_ALL_MODE = 1038;' in settings
assert 'private static final int BTN_MENTION_ALL_LIMIT = 1039;' in settings
assert 'private static final int BTN_MENTION_ALL_COOLDOWN = 1040;' in settings
assert 'case BTN_MENTION_ALL_MODE:' in settings and 'case BTN_MENTION_ALL_LIMIT:' in settings
assert 'case BTN_MENTION_ALL_COOLDOWN:' in settings
assert 'private void showMentionAllLimitPicker()' in settings
assert 'PengramMentionAll.resetCache();' in settings
# объявление + строка настроек + обработчик, и ничего больше (без дублей в UI)
for identifier in ('BTN_MENTION_ALL_MODE', 'BTN_MENTION_ALL_LIMIT', 'BTN_MENTION_ALL_COOLDOWN'):
    assert settings.count(identifier) == 3, (identifier, settings.count(identifier))
for value in ('1038', '1039', '1040'):
    assert settings.count('= %s;' % value) == 1, value

# --- строки в обеих локалях ---------------------------------------------------
needed = set()
for source in (mention, chat, settings):
    index = 0
    while True:
        index = source.find('R.string.PengramMentionAll', index)
        if index < 0:
            break
        end = index + len('R.string.')
        name = ''
        while end < len(source) and (source[end].isalnum() or source[end] == '_'):
            name += source[end]
            end += 1
        needed.add(name)
        index = end
assert 'PengramMentionAll' in needed
for name in sorted(needed):
    assert 'name="%s"' % name in en, 'EN missing ' + name
    assert 'name="%s"' % name in ru, 'RU missing ' + name

print('mention all: %d strings, cap 100, cooldown, groups only, off by default: OK' % len(needed))
