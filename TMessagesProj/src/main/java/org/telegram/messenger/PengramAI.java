package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengram: свои нейросети вместо встроенных.
 *
 * Хранит список сервисов (адрес, модель, ключ), выбранную роль и то, как
 * показывать ответ. Ключи лежат в обычных настройках приложения и никуда,
 * кроме указанного сервиса, не уходят: запрос идёт напрямую с устройства.
 */
public final class PengramAI {

    public static final String PREFS = "pengram_ai";

    private static final String KEY_SERVICES = "services";
    private static final String KEY_ACTIVE = "activeService";

    public static final String KEY_STREAM = "aiStream";
    public static final String KEY_ONLY_ANSWER = "aiOnlyAnswer";
    public static final String KEY_AS_QUOTE = "aiAsQuote";
    public static final String KEY_HISTORY = "aiHistory";
    public static final String KEY_HISTORY_DEPTH = "aiHistoryDepth";

    private PengramAI() {
    }

    // ------------------------------------------------------------ сервис

    public static class Service {
        public String id;
        public String title;
        public String baseUrl;
        public String model;
        public String apiKey;

        public Service(String id, String title, String baseUrl, String model, String apiKey) {
            this.id = id;
            this.title = title;
            this.baseUrl = baseUrl;
            this.model = model;
            this.apiKey = apiKey;
        }

        /** полный адрес запроса: к базе дописываем /chat/completions, если его нет */
        public String endpoint() {
            String url = baseUrl == null ? "" : baseUrl.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (url.endsWith("/chat/completions")) {
                return url;
            }
            if (!url.endsWith("/v1") && !url.contains("/v1/") && !url.endsWith("/api")) {
                url = url + "/v1";
            }
            return url + "/chat/completions";
        }

        public boolean isReady() {
            return !TextUtils.isEmpty(baseUrl) && !TextUtils.isEmpty(model);
        }

        /** короткая подпись под названием: домен и модель */
        public String summary() {
            String host = baseUrl == null ? "" : baseUrl.replaceFirst("^https?://", "");
            final int slash = host.indexOf('/');
            if (slash > 0) {
                host = host.substring(0, slash);
            }
            if (TextUtils.isEmpty(model)) {
                return host;
            }
            return host + " · " + model;
        }

        JSONObject toJson() throws Exception {
            final JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("url", baseUrl);
            o.put("model", model);
            o.put("key", apiKey == null ? "" : apiKey);
            return o;
        }

        static Service fromJson(JSONObject o) {
            return new Service(o.optString("id"), o.optString("title"), o.optString("url"),
                    o.optString("model"), o.optString("key"));
        }
    }

    /**
     * Заготовки под популярные шлюзы. Все они говорят на одном языке
     * (OpenAI-совместимый /chat/completions), поэтому достаточно подставить ключ.
     */
    public static class Preset {
        public final String title;
        public final String baseUrl;
        public final String model;
        public final String hint;

        Preset(String title, String baseUrl, String model, String hint) {
            this.title = title;
            this.baseUrl = baseUrl;
            this.model = model;
            this.hint = hint;
        }
    }

    public static final Preset[] PRESETS = {
            new Preset("GWarden", "https://gwarden.su/v1", "glm-5.3", "GWAR-…"),
            new Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "sk-…"),
            new Preset("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat", "sk-or-…"),
            new Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "sk-…"),
            new Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "gsk_…"),
            new Preset("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", "…"),
            new Preset("Ollama (на этом же устройстве)", "http://127.0.0.1:11434/v1", "llama3.1", ""),
    };

    // ------------------------------------------------------------ хранение

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    public static List<Service> services() {
        final ArrayList<Service> list = new ArrayList<>();
        try {
            final String raw = prefs().getString(KEY_SERVICES, null);
            if (TextUtils.isEmpty(raw)) {
                return list;
            }
            final JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                list.add(Service.fromJson(array.getJSONObject(i)));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return list;
    }

    private static void save(List<Service> list) {
        try {
            final JSONArray array = new JSONArray();
            for (Service s : list) {
                array.put(s.toJson());
            }
            prefs().edit().putString(KEY_SERVICES, array.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static Service put(Service service) {
        if (TextUtils.isEmpty(service.id)) {
            service.id = "s" + System.currentTimeMillis();
        }
        final List<Service> list = services();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (TextUtils.equals(list.get(i).id, service.id)) {
                list.set(i, service);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(service);
        }
        save(list);
        if (TextUtils.isEmpty(activeId())) {
            setActive(service.id);
        }
        return service;
    }

    public static void remove(String id) {
        final List<Service> list = services();
        for (int i = 0; i < list.size(); i++) {
            if (TextUtils.equals(list.get(i).id, id)) {
                list.remove(i);
                break;
            }
        }
        save(list);
        if (TextUtils.equals(activeId(), id)) {
            setActive(list.isEmpty() ? null : list.get(0).id);
        }
    }

    public static String activeId() {
        return prefs().getString(KEY_ACTIVE, null);
    }

    public static void setActive(String id) {
        if (id == null) {
            prefs().edit().remove(KEY_ACTIVE).apply();
        } else {
            prefs().edit().putString(KEY_ACTIVE, id).apply();
        }
    }

    public static Service active() {
        final String id = activeId();
        final List<Service> list = services();
        for (Service s : list) {
            if (TextUtils.equals(s.id, id)) {
                return s;
            }
        }
        return list.isEmpty() ? null : list.get(0);
    }

    public static boolean hasService() {
        final Service s = active();
        return s != null && s.isReady();
    }

    // ------------------------------------------------------------ поведение ответа

    public static boolean getBool(String key, boolean def) {
        return prefs().getBoolean(key, def);
    }

    public static void setBool(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    public static boolean toggle(String key, boolean def) {
        final boolean value = !getBool(key, def);
        setBool(key, value);
        return value;
    }

    public static boolean isStream() {
        return getBool(KEY_STREAM, true);
    }

    public static boolean isOnlyAnswer() {
        return getBool(KEY_ONLY_ANSWER, false);
    }

    public static boolean isAsQuote() {
        return getBool(KEY_AS_QUOTE, false);
    }

    public static boolean isHistory() {
        return getBool(KEY_HISTORY, true);
    }

    public static int historyDepth() {
        return prefs().getInt(KEY_HISTORY_DEPTH, 10);
    }

    public static void setHistoryDepth(int value) {
        prefs().edit().putInt(KEY_HISTORY_DEPTH, Math.max(1, Math.min(50, value))).apply();
    }

    /** полный сброс раздела — на случай «начать с чистого листа» */
    public static void resetAll() {
        prefs().edit().clear().apply();
        PengramAIRoles.resetAll();
    }
}
