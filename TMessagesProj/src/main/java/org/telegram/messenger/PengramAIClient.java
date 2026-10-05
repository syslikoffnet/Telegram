package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pengram: разговор с выбранным сервисом по OpenAI-совместимому протоколу.
 *
 * Запрос уходит прямо с устройства на указанный адрес: ни ключ, ни текст не
 * проходят через чужие серверы Pengram — их попросту нет. Поддержана как
 * обычная отправка, так и потоковая (ответ приходит кусками по мере набора).
 */
public final class PengramAIClient {

    public interface Listener {
        /** очередной кусок текста при потоковой передаче */
        void onChunk(String text);

        /** ответ целиком */
        void onDone(String text);

        void onError(String message);
    }

    /** одна реплика разговора */
    public static class Turn {
        public final String role;
        public final String content;

        public Turn(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private PengramAIClient() {
    }

    private static volatile DispatchQueue queue;

    private static DispatchQueue queue() {
        if (queue == null) {
            synchronized (PengramAIClient.class) {
                if (queue == null) {
                    queue = new DispatchQueue("pengramAI");
                }
            }
        }
        return queue;
    }

    /** короткая проверка связи: попросить модель ответить одним словом */
    public static void check(PengramAI.Service service, Listener listener) {
        final ArrayList<Turn> turns = new ArrayList<>();
        turns.add(new Turn("user", "ping"));
        ask(service, null, turns, false, listener);
    }

    public static void ask(PengramAI.Service service, String systemPrompt, List<Turn> turns,
                           boolean stream, Listener listener) {
        if (service == null || !service.isReady()) {
            post(() -> listener.onError(LocaleController.getString(R.string.PengramAINoService)));
            return;
        }
        queue().postRunnable(() -> run(service, systemPrompt, turns, stream, listener));
    }

    private static void post(Runnable runnable) {
        AndroidUtilities.runOnUIThread(runnable);
    }

    private static void run(PengramAI.Service service, String systemPrompt, List<Turn> turns,
                            boolean stream, Listener listener) {
        HttpURLConnection connection = null;
        try {
            final JSONObject body = new JSONObject();
            body.put("model", service.model);
            body.put("stream", stream);
            final JSONArray messages = new JSONArray();
            if (!TextUtils.isEmpty(systemPrompt)) {
                final JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
                messages.put(sys);
            }
            for (Turn turn : turns) {
                final JSONObject m = new JSONObject();
                m.put("role", turn.role);
                m.put("content", turn.content);
                messages.put(m);
            }
            body.put("messages", messages);

            connection = (HttpURLConnection) new URL(service.endpoint()).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(stream ? 180000 : 90000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            if (!TextUtils.isEmpty(service.apiKey)) {
                connection.setRequestProperty("Authorization", "Bearer " + service.apiKey.trim());
            }
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            final int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                final String error = read(connection.getErrorStream());
                post(() -> listener.onError(describe(code, error)));
                return;
            }

            if (stream) {
                readStream(connection.getInputStream(), listener);
            } else {
                final String answer = extract(read(connection.getInputStream()));
                post(() -> listener.onDone(answer));
            }
        } catch (Throwable e) {
            FileLog.e(e);
            final String message = e.getMessage();
            post(() -> listener.onError(TextUtils.isEmpty(message)
                    ? LocaleController.getString(R.string.PengramAIErrorNetwork) : message));
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** понятная человеку причина отказа вместо голого кода */
    private static String describe(int code, String error) {
        String detail = null;
        try {
            if (!TextUtils.isEmpty(error)) {
                final JSONObject o = new JSONObject(error);
                final JSONObject err = o.optJSONObject("error");
                detail = err != null ? err.optString("message") : o.optString("message");
            }
        } catch (Throwable ignore) {
        }
        if (TextUtils.isEmpty(detail)) {
            if (code == 401 || code == 403) {
                return LocaleController.getString(R.string.PengramAIErrorKey);
            }
            if (code == 404) {
                return LocaleController.getString(R.string.PengramAIErrorModel);
            }
            if (code == 429) {
                return LocaleController.getString(R.string.PengramAIErrorLimit);
            }
            return "HTTP " + code;
        }
        return detail;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** разбор потока server-sent events: строки «data: {…}» до «data: [DONE]» */
    private static void readStream(InputStream stream, Listener listener) throws Exception {
        final StringBuilder answer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                final String payload = line.substring(5).trim();
                if (TextUtils.isEmpty(payload) || "[DONE]".equals(payload)) {
                    continue;
                }
                try {
                    final JSONObject chunk = new JSONObject(payload);
                    final JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    final JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    final String piece = delta == null ? null : delta.optString("content", "");
                    if (!TextUtils.isEmpty(piece)) {
                        answer.append(piece);
                        post(() -> listener.onChunk(piece));
                    }
                } catch (Throwable ignore) {
                }
            }
        }
        final String text = answer.toString().trim();
        post(() -> {
            if (TextUtils.isEmpty(text)) {
                listener.onError(LocaleController.getString(R.string.PengramAIErrorEmpty));
            } else {
                listener.onDone(text);
            }
        });
    }

    private static String extract(String json) {
        try {
            final JSONObject o = new JSONObject(json);
            final JSONArray choices = o.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                final JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                if (message != null) {
                    final String content = message.optString("content", "");
                    if (!TextUtils.isEmpty(content)) {
                        return content.trim();
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return "";
    }
}
