package org.telegram.messenger;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.URLSpanUserMention;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Pengram: массовое упоминание «@all» в группах.
 *
 * <p>«@all» в тексте заменяется обычными упоминаниями участников — такими же,
 * какие Telegram вставляет из списка упоминаний. Ничего своего в протокол не
 * уходит, поэтому сообщение выглядит как ручное упоминание.
 *
 * <p>Ограничения — не украшение, а защита аккаунта: массовые упоминания легко
 * попадают в спам-фильтры. Поэтому есть жёсткий потолок в 100 человек, пауза
 * между использованиями и выбор «все / только админы». По умолчанию выключено.
 *
 * <p>Список участников грузится заранее (как только «@all» набрали) и живёт в
 * кэше 10 минут: в момент отправки сеть уже не нужна, и сообщение уходит сразу.
 */
public final class PengramMentionAll {

    /** включено ли массовое упоминание */
    public static final String KEY_ENABLED = "mentionAllEnabled";
    /** кого упоминать: все или только админы */
    public static final String KEY_MODE = "mentionAllMode";
    /** сколько человек максимум */
    public static final String KEY_LIMIT = "mentionAllLimit";
    /** пауза между массовыми упоминаниями, минут */
    public static final String KEY_COOLDOWN = "mentionAllCooldown";

    public static final int MODE_EVERYONE = 0;
    public static final int MODE_ADMINS = 1;

    /** выше этого не поднимаемся никогда — даже если настройку подменили */
    public static final int HARD_LIMIT = 100;
    public static final int[] LIMITS = new int[]{25, 50, 100};
    public static final int[] COOLDOWNS = new int[]{1, 5, 15, 30};

    /** что считать командой: латиница, кириллица и «everyone» */
    private static final String[] TOKENS = new String[]{"@all", "@все", "@everyone"};

    private static final long CACHE_TTL = 10 * 60 * 1000L;

    public static final int RESULT_NONE = 0;
    public static final int RESULT_OK = 1;
    public static final int RESULT_LOADING = 2;
    public static final int RESULT_COOLDOWN = 3;
    public static final int RESULT_EMPTY = 4;
    public static final int RESULT_TOO_LONG = 5;

    /** результат подстановки: текст, причина и статистика для подсказки */
    public static class Result {
        public CharSequence text;
        public int reason = RESULT_NONE;
        public int mentioned;
        public int total;
        public int cooldownMinutes;
    }

    private static final class Cache {
        ArrayList<TLRPC.User> users;
        int total;
        long time;
    }

    private static final HashMap<String, Cache> caches = new HashMap<>();
    private static final HashSet<String> loading = new HashSet<>();
    private static final HashMap<String, Long> lastUse = new HashMap<>();

    private PengramMentionAll() {
    }

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean value) {
        PengramConfig.setBool(KEY_ENABLED, value);
    }

    public static int mode() {
        return PengramConfig.getIntCached(KEY_MODE, MODE_EVERYONE) == MODE_ADMINS ? MODE_ADMINS : MODE_EVERYONE;
    }

    public static void setMode(int value) {
        PengramConfig.setIntValue(KEY_MODE, value == MODE_ADMINS ? MODE_ADMINS : MODE_EVERYONE);
    }

    /** потолок упоминаний: только из разрешённого списка и не больше жёсткого лимита */
    public static int limit() {
        final int value = PengramConfig.getIntCached(KEY_LIMIT, HARD_LIMIT);
        return Math.max(1, Math.min(HARD_LIMIT, value));
    }

    public static void setLimit(int value) {
        PengramConfig.setIntValue(KEY_LIMIT, Math.max(1, Math.min(HARD_LIMIT, value)));
    }

    public static int cooldownMinutes() {
        final int value = PengramConfig.getIntCached(KEY_COOLDOWN, 5);
        return Math.max(1, Math.min(60, value));
    }

    public static void setCooldownMinutes(int value) {
        PengramConfig.setIntValue(KEY_COOLDOWN, Math.max(1, Math.min(60, value)));
    }

    /** группа — чат или супергруппа; каналы-вещания не трогаем */
    public static boolean isGroup(TLRPC.Chat chat) {
        if (chat == null) {
            return false;
        }
        return !ChatObject.isChannel(chat) || chat.megagroup;
    }

    /** есть ли в тексте команда «@all» как отдельное слово */
    public static boolean hasToken(CharSequence text) {
        return findToken(text) != null;
    }

    private static int[] findToken(CharSequence text) {
        if (text == null) {
            return null;
        }
        final int length = text.length();
        for (String token : TOKENS) {
            final int tokenLength = token.length();
            for (int at = 0; at + tokenLength <= length; ++at) {
                if (!matchesAt(text, at, token)) {
                    continue;
                }
                final int end = at + tokenLength;
                final boolean before = at == 0 || !isWordChar(text.charAt(at - 1));
                final boolean after = end >= length || !isWordChar(text.charAt(end));
                if (before && after) {
                    return new int[]{at, end};
                }
            }
        }
        return null;
    }

    private static boolean matchesAt(CharSequence text, int at, String token) {
        for (int a = 0; a < token.length(); ++a) {
            if (Character.toLowerCase(text.charAt(at + a)) != Character.toLowerCase(token.charAt(a))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String key(int account, long chatId) {
        return account + ":" + chatId;
    }

    /**
     * Заранее приносит список участников. Вызывается, как только «@all»
     * набрали: к моменту отправки ответ уже лежит в кэше.
     */
    public static void prefetch(int account, TLRPC.Chat chat) {
        if (!isEnabled() || !isGroup(chat)) {
            return;
        }
        final String cacheKey = key(account, chat.id);
        final Cache cached = caches.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.time < CACHE_TTL) {
            return;
        }
        if (!loading.add(cacheKey)) {
            return;
        }
        final int want = Math.min(200, Math.max(limit() + 20, 100));
        if (ChatObject.isChannel(chat)) {
            final TLRPC.TL_channels_getParticipants request = new TLRPC.TL_channels_getParticipants();
            request.channel = MessagesController.getInputChannel(chat);
            request.filter = mode() == MODE_ADMINS
                    ? new TLRPC.TL_channelParticipantsAdmins()
                    : new TLRPC.TL_channelParticipantsRecent();
            request.offset = 0;
            request.limit = want;
            request.hash = 0;
            ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
                loading.remove(cacheKey);
                if (!(response instanceof TLRPC.channels_ChannelParticipants)) {
                    return;
                }
                final TLRPC.channels_ChannelParticipants participants = (TLRPC.channels_ChannelParticipants) response;
                final int total = mode() == MODE_ADMINS
                        ? participants.users.size()
                        : Math.max(participants.count, participants.users.size());
                putCache(cacheKey, participants.users, total);
            }, ConnectionsManager.RequestFlagFailOnServerErrors);
        } else {
            final TLRPC.TL_messages_getFullChat request = new TLRPC.TL_messages_getFullChat();
            request.chat_id = chat.id;
            ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
                loading.remove(cacheKey);
                if (!(response instanceof TLRPC.TL_messages_chatFull)) {
                    return;
                }
                final TLRPC.TL_messages_chatFull full = (TLRPC.TL_messages_chatFull) response;
                final ArrayList<TLRPC.User> users = new ArrayList<>();
                int total = 0;
                if (full.full_chat != null && full.full_chat.participants != null) {
                    for (TLRPC.ChatParticipant participant : full.full_chat.participants.participants) {
                        if (participant == null) {
                            continue;
                        }
                        total++;
                        if (mode() == MODE_ADMINS && !isAdmin(participant)) {
                            continue;
                        }
                        final TLRPC.User user = findUser(full.users, participant.user_id);
                        if (user != null) {
                            users.add(user);
                        }
                    }
                }
                putCache(cacheKey, users, total);
            }, ConnectionsManager.RequestFlagFailOnServerErrors);
        }
    }

    private static boolean isAdmin(TLRPC.ChatParticipant participant) {
        return participant instanceof TLRPC.TL_chatParticipantAdmin
                || participant instanceof TLRPC.TL_chatParticipantCreator;
    }

    private static TLRPC.User findUser(ArrayList<TLRPC.User> users, long id) {
        if (users == null) {
            return null;
        }
        for (TLRPC.User user : users) {
            if (user != null && user.id == id) {
                return user;
            }
        }
        return null;
    }

    private static void putCache(String key, ArrayList<TLRPC.User> users, int total) {
        final Cache cache = new Cache();
        cache.users = users == null ? new ArrayList<>() : users;
        cache.total = Math.max(total, cache.users.size());
        cache.time = System.currentTimeMillis();
        caches.put(key, cache);
    }

    /** можно ли сейчас подставлять: пауза между массовыми упоминаниями */
    public static int cooldownLeft(int account, long chatId) {
        final Long last = lastUse.get(key(account, chatId));
        if (last == null) {
            return 0;
        }
        final long left = cooldownMinutes() * 60_000L - (System.currentTimeMillis() - last);
        return left <= 0 ? 0 : (int) Math.ceil(left / 60_000.0);
    }

    /**
     * Проверка перед отправкой: готова ли подстановка. Отдельно от {@link #expand},
     * потому что решать «отправлять или подождать» нужно до того, как текст
     * разрезан на части, и потому что эта проверка не запускает паузу.
     */
    public static int canSend(int account, TLRPC.Chat chat, CharSequence text) {
        if (!isEnabled() || text == null || !isGroup(chat) || !hasToken(text)) {
            return RESULT_NONE;
        }
        if (text.length() > 3000) {
            return RESULT_TOO_LONG;
        }
        if (cooldownLeft(account, chat.id) > 0) {
            return RESULT_COOLDOWN;
        }
        final String cacheKey = key(account, chat.id);
        final Cache cache = caches.get(cacheKey);
        if (cache == null || System.currentTimeMillis() - cache.time > CACHE_TTL) {
            prefetch(account, chat);
            return RESULT_LOADING;
        }
        // Пустой список — тоже повод не отправлять: иначе в чат уйдёт
        // буквальный «@all» (в маленькой группе ботов и удалённых может не быть
        // вообще никого, кого можно упомянуть).
        if (pick(account, cache.users).isEmpty()) {
            return RESULT_EMPTY;
        }
        return RESULT_OK;
    }

    /**
     * Заменяет «@all» обычными упоминаниями. Если список ещё грузится или не
     * прошла пауза — возвращает текст как есть и причину, чтобы вызывающий мог
     * объяснить это одной строкой.
     */
    public static Result expand(int account, TLRPC.Chat chat, CharSequence text) {
        final Result result = new Result();
        result.text = text;
        if (!isEnabled() || text == null || !isGroup(chat)) {
            return result;
        }
        final int[] token = findToken(text);
        if (token == null) {
            return result;
        }
        if (text.length() > 3000) {
            // 100 упоминаний — это ещё ~1000 символов; длинный текст разрежем
            // на несколько сообщений и раскидаем упоминания по частям
            result.reason = RESULT_TOO_LONG;
            return result;
        }
        final int cooldown = cooldownLeft(account, chat.id);
        if (cooldown > 0) {
            result.reason = RESULT_COOLDOWN;
            result.cooldownMinutes = cooldown;
            return result;
        }
        final String cacheKey = key(account, chat.id);
        final Cache cache = caches.get(cacheKey);
        if (cache == null || System.currentTimeMillis() - cache.time > CACHE_TTL) {
            prefetch(account, chat);
            result.reason = RESULT_LOADING;
            return result;
        }
        final ArrayList<TLRPC.User> picked = pick(account, cache.users);
        if (picked.isEmpty()) {
            result.reason = RESULT_EMPTY;
            return result;
        }
        final SpannableStringBuilder mentions = new SpannableStringBuilder();
        int mentioned = 0;
        for (TLRPC.User user : picked) {
            final String username = UserObject.getPublicUsername(user);
            final String label;
            if (!TextUtils.isEmpty(username)) {
                label = "@" + username;
            } else {
                label = UserObject.getFirstName(user, false);
            }
            if (TextUtils.isEmpty(label)) {
                continue;
            }
            final int start = mentions.length();
            mentions.append(label);
            mentions.append(' ');
            // Сущность важна в обоих случаях: с юзернеймом текст выглядит обычным
            // @упоминанием, без юзернейма — только сущность и делает его упоминанием.
            mentions.setSpan(new URLSpanUserMention(String.valueOf(user.id), 3),
                    start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mentioned++;
        }
        if (mentioned == 0) {
            result.reason = RESULT_EMPTY;
            return result;
        }
        final SpannableStringBuilder out = new SpannableStringBuilder(text);
        out.replace(token[0], token[1], mentions);
        result.text = out;
        result.reason = RESULT_OK;
        result.mentioned = mentioned;
        result.total = Math.max(mentioned, cache.total);
        lastUse.put(cacheKey, System.currentTimeMillis());
        return result;
    }

    private static ArrayList<TLRPC.User> pick(int account, ArrayList<TLRPC.User> users) {
        final ArrayList<TLRPC.User> picked = new ArrayList<>();
        if (users == null) {
            return picked;
        }
        final long self = UserConfig.getInstance(account).getClientUserId();
        final int cap = limit();
        for (TLRPC.User user : users) {
            if (user == null || user.deleted || user.bot || user.id == self) {
                continue;
            }
            picked.add(user);
            if (picked.size() >= cap) {
                break;
            }
        }
        return picked;
    }

    /** сброс кэша: после смены настроек список «кого упоминать» другой */
    public static void resetCache() {
        caches.clear();
        loading.clear();
    }
}
