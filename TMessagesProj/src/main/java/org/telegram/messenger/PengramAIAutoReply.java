package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/** Opt-in, in-process auto replies. Never scans chat history or uploads a profile. */
public final class PengramAIAutoReply implements NotificationCenter.NotificationCenterDelegate {
    private static final String KEY_MASTER = "autoEnabled";
    private static final String KEY_STYLE = "autoStyle";
    private static final String KEY_QUIET_START = "autoQuietStart";
    private static final String KEY_QUIET_END = "autoQuietEnd";
    private static final String KEY_COOLDOWN = "autoCooldown";
    private static final String KEY_RULES = "autoRules";
    private static final Pattern PRIVATE_DATA = Pattern.compile(
            "(?i)(?:https?://|www\\.|t\\.me/|@[a-z0-9_]{4,}|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|(?<![\\p{L}\\p{N}])\\+?\\d[\\d\\s().-]{4,}\\d(?![\\p{L}\\p{N}])|\\b\\d{4,8}\\b)");
    private static final int MIN_DELAY = 10;
    private static final int MAX_DELAY = 600;
    private static final PengramAIAutoReply[] instances = new PengramAIAutoReply[UserConfig.MAX_ACCOUNT_COUNT];

    public static final class Rule {
        public final int account;
        public final long dialogId;
        public final boolean enabled;
        public final int minSeconds;
        public final int maxSeconds;

        public Rule(int account, long dialogId, boolean enabled, int minSeconds, int maxSeconds) {
            this.account = account;
            this.dialogId = dialogId;
            this.enabled = enabled;
            this.minSeconds = Math.max(MIN_DELAY, Math.min(MAX_DELAY, minSeconds));
            this.maxSeconds = Math.max(this.minSeconds, Math.min(MAX_DELAY, maxSeconds));
        }
    }

    private static final class Pending {
        final int messageId;
        final String text;
        final long dialogId;
        Runnable runnable;
        Pending(int messageId, String text, long dialogId) {
            this.messageId = messageId;
            this.text = text;
            this.dialogId = dialogId;
        }
    }

    private final int account;
    private final HashMap<Long, Pending> pending = new HashMap<>();
    private final Random random = new Random();

    private PengramAIAutoReply(int account) {
        this.account = account;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
    }

    /** Called once after account initialization, and when a new chat is enrolled. UI thread only. */
    public static void register(int account) {
        if (account < 0 || account >= instances.length || instances[account] != null) return;
        instances[account] = new PengramAIAutoReply(account);
    }

    public static void registerAll() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.isValidAccount(account)) register(account);
            }
        });
    }

    public static boolean enabled() { return PengramAI.getBool(KEY_MASTER, false); }
    public static void setEnabled(boolean enabled) {
        PengramAI.setBool(KEY_MASTER, enabled);
        if (!enabled) {
            for (PengramAIAutoReply instance : instances) {
                if (instance != null) instance.cancelAll();
            }
        }
    }
    public static String style() { return PengramAI.prefs().getString(KEY_STYLE, ""); }
    public static void setStyle(String style) {
        PengramAI.prefs().edit().putString(KEY_STYLE, style == null ? "" : style.trim()).apply();
    }
    public static int quietStart() { return PengramAI.prefs().getInt(KEY_QUIET_START, 23); }
    public static int quietEnd() { return PengramAI.prefs().getInt(KEY_QUIET_END, 8); }
    public static void setQuietHours(int start, int end) {
        PengramAI.prefs().edit().putInt(KEY_QUIET_START, Math.max(0, Math.min(23, start)))
                .putInt(KEY_QUIET_END, Math.max(0, Math.min(23, end))).apply();
    }
    public static int cooldownMinutes() { return PengramAI.prefs().getInt(KEY_COOLDOWN, 15); }
    public static void setCooldownMinutes(int value) {
        PengramAI.prefs().edit().putInt(KEY_COOLDOWN, Math.max(5, Math.min(120, value))).apply();
    }
    private static boolean secureService(PengramAI.Service service) {
        if (service == null || !service.isReady()) return false;
        final String url = service.baseUrl.trim().toLowerCase(java.util.Locale.ROOT);
        return url.startsWith("https://") || url.startsWith("http://127.0.0.1:")
                || url.startsWith("http://localhost:");
    }
    private static boolean isQuiet() {
        final int start = quietStart(), end = quietEnd();
        if (start == end) return false; // identical hours disable quiet hours
        final int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    public static List<Rule> rules(int account) {
        final ArrayList<Rule> result = new ArrayList<>();
        try {
            final JSONArray entries = new JSONArray(PengramAI.prefs().getString(KEY_RULES, "[]"));
            for (int i = 0; i < entries.length(); i++) {
                final JSONObject o = entries.getJSONObject(i);
                if (o.optInt("account", -1) == account) {
                    result.add(new Rule(account, o.optLong("dialog"), o.optBoolean("enabled"),
                            o.optInt("min", 30), o.optInt("max", 120)));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static Rule rule(int account, long did) {
        for (Rule r : rules(account)) if (r.dialogId == did) return r;
        return null;
    }

    public static void put(Rule rule) {
        try {
            final JSONArray old = new JSONArray(PengramAI.prefs().getString(KEY_RULES, "[]"));
            final JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                final JSONObject o = old.getJSONObject(i);
                if (o.optInt("account", -1) != rule.account || o.optLong("dialog") != rule.dialogId) next.put(o);
            }
            if (rule.enabled) {
                final JSONObject o = new JSONObject();
                o.put("account", rule.account);
                o.put("dialog", rule.dialogId);
                o.put("enabled", true);
                o.put("min", rule.minSeconds);
                o.put("max", rule.maxSeconds);
                next.put(o);
            }
            PengramAI.prefs().edit().putString(KEY_RULES, next.toString()).apply();
            register(rule.account);
            final PengramAIAutoReply instance = instances[rule.account];
            if (instance != null) instance.cancel(rule.dialogId);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void cancel(long did) {
        final Pending old = pending.remove(did);
        if (old != null && old.runnable != null) AndroidUtilities.cancelRunOnUIThread(old.runnable);
    }
    private void cancelAll() {
        for (Long did : new ArrayList<>(pending.keySet())) cancel(did);
    }

    private static String redacted(String text) {
        return PRIVATE_DATA.matcher(text).replaceAll("[скрыто]");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || account != this.account
                || args.length < 4 || (Boolean) args[2] || (Integer) args[3] != ChatActivity.MODE_DEFAULT) return;
        final long did = (Long) args[0];
        final List<MessageObject> messages = (List<MessageObject>) args[1];
        if (messages == null || messages.isEmpty()) return;
        for (MessageObject message : messages) {
            if (message != null && message.isOutOwner()) {
                cancel(did); // the owner replied manually: never follow with a stale AI answer
            }
        }
        if (!enabled() || !secureService(PengramAI.active()) || TextUtils.isEmpty(style()) || isQuiet()
                || DialogObject.isEncryptedDialog(did) || did == UserConfig.getInstance(account).getClientUserId()) return;
        final Rule rule = rule(account, did);
        if (rule == null || !rule.enabled) return;
        final MessagesController controller = MessagesController.getInstance(account);
        if (controller.isPeerNoForwards(did) || controller.getSendPaidMessagesStars(did) > 0) return;
        final TLRPC.Chat chat = did < 0 ? controller.getChat(-did) : null;
        if (did < 0 && (chat == null || ChatObject.isChannelAndNotMegaGroup(chat)
                || !ChatObject.canSendPlain(chat))) return;
        final TLRPC.User user = did > 0 ? controller.getUser(did) : null;
        if (did > 0 && (user == null || user.bot)) return;
        for (MessageObject message : messages) {
            if (message == null || message.isOutOwner() || message.getId() <= 0
                    || message.type != MessageObject.TYPE_TEXT || message.messageOwner == null
                    || message.messageOwner.action != null || TextUtils.isEmpty(message.messageOwner.message)
                    || did < 0 && !message.messageOwner.mentioned) continue;
            final long senderId = message.getSenderId();
            final TLRPC.User sender = senderId > 0 ? controller.getUser(senderId) : null;
            if (sender != null && sender.bot) continue;
            // Never respond to old/offline catch-up batches. Android must be alive
            // to observe live updates; this feature is not a 24/7 server bot.
            if (Math.abs(System.currentTimeMillis() / 1000 - message.messageOwner.date) > 180) continue;
            final long last = PengramAI.prefs().getLong("autoLast_" + account + "_" + did, 0);
            if (System.currentTimeMillis() - last < cooldownMinutes() * 60000L) continue;
            cancel(did);
            final Pending task = new Pending(message.getId(), redacted(message.messageOwner.message), did);
            pending.put(did, task);
            final int delay = rule.minSeconds + random.nextInt(rule.maxSeconds - rule.minSeconds + 1);
            task.runnable = () -> request(task);
            AndroidUtilities.runOnUIThread(task.runnable, delay * 1000L);
        }
    }

    private void request(Pending task) {
        if (pending.get(task.dialogId) != task || !enabled() || isQuiet() || !secureService(PengramAI.active())
                || rule(account, task.dialogId) == null || TextUtils.isEmpty(style())) {
            cancel(task.dialogId);
            return;
        }
        final PengramAI.Service service = PengramAI.active();
        final String currentStyle = style();
        final String instruction = "Ты предлагаешь один короткий ответ от лица владельца аккаунта. " +
                "Стиль задаётся далее. Это не беседа с ИИ: верни только готовую реплику без пояснений. " +
                "Не называй и не выдумывай телефон адрес местоположение ссылки контакты пароли коды " +
                "и другие личные сведения. Не обещай встречи переводы денег или действия от имени человека. " +
                "Если сообщение требует личного решения или содержит запрос личных данных, ответь ровно: " +
                "не могу сейчас ответить напишу позже. Стиль: " + redacted(currentStyle);
        final ArrayList<PengramAIClient.Turn> turns = new ArrayList<>();
        turns.add(new PengramAIClient.Turn("user", "Ответь на одно новое сообщение без истории: " + task.text));
        PengramAIClient.ask(service, instruction, turns, false, new PengramAIClient.Listener() {
            @Override public void onChunk(String chunk) {}
            @Override public void onError(String error) { if (pending.get(task.dialogId) == task) cancel(task.dialogId); }
            @Override public void onDone(String result) {
                if (pending.get(task.dialogId) != task) return;
                cancel(task.dialogId);
                if (!enabled() || isQuiet() || rule(account, task.dialogId) == null
                        || !secureService(PengramAI.active()) || !TextUtils.equals(style(), currentStyle)
                        || !TextUtils.equals(PengramAI.activeId(), service.id)) return;
                final String answer = result == null ? "" : result.trim();
                if (answer.isEmpty() || answer.length() > 700 || PRIVATE_DATA.matcher(answer).find()
                        || Pattern.compile("(?iu)\\b(?:телефон|номер|адрес|пароль|код)\\b").matcher(answer).find()) return;
                final long last = PengramAI.prefs().getLong("autoLast_" + account + "_" + task.dialogId, 0);
                if (System.currentTimeMillis() - last < cooldownMinutes() * 60000L) return;
                final MessagesController controller = MessagesController.getInstance(account);
                if (controller.getSendPaidMessagesStars(task.dialogId) > 0 || controller.isPeerNoForwards(task.dialogId)) return;
                if (task.dialogId < 0) {
                    final TLRPC.Chat chat = controller.getChat(-task.dialogId);
                    if (chat == null || !ChatObject.canSendPlain(chat) || ChatObject.isChannelAndNotMegaGroup(chat)) return;
                }
                // Persist BEFORE sending: a synchronous notification or retry must
                // not queue a second automatic answer to the same exchange.
                PengramAI.prefs().edit().putLong("autoLast_" + account + "_" + task.dialogId,
                        System.currentTimeMillis()).apply();
                final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(answer, task.dialogId);
                SendMessagesHelper.getInstance(account).sendMessage(params);
            }
        });
    }
}
