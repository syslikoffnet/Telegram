package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengram: роли для нейросети.
 *
 * Роль — это заготовка системной подсказки: как модель должна себя вести с
 * выбранным текстом. Несколько ролей идут в комплекте, любую свою можно
 * добавить рядом и выбрать так же, как встроенную.
 */
public final class PengramAIRoles {

    private static final String KEY_ROLES = "roles";
    private static final String KEY_ACTIVE = "activeRole";

    private PengramAIRoles() {
    }

    public static class Role {
        public String id;
        public String title;
        public String prompt;
        public boolean builtin;

        public Role(String id, String title, String prompt, boolean builtin) {
            this.id = id;
            this.title = title;
            this.prompt = prompt;
            this.builtin = builtin;
        }
    }

    /**
     * Встроенные роли. Формулировки короткие и без вежливых реверансов:
     * модель и так тратит внимание на сам текст, а не на инструкцию.
     */
    public static List<Role> builtin() {
        final ArrayList<Role> list = new ArrayList<>();
        list.add(new Role("talk", "Собеседник",
                "Отвечай на русском, по делу и живым языком. Без вступлений вроде «конечно» и без пересказа вопроса. Если данных не хватает — задай один уточняющий вопрос.", true));
        list.add(new Role("short", "Коротко о главном",
                "Сожми присланный текст до сути: три-пять пунктов или один абзац. Сохрани имена, числа и даты, выброси воду и повторы. Своих выводов не добавляй.", true));
        list.add(new Role("translate", "Перевод",
                "Переведи присланный текст на русский, а если он уже русский — на английский. Верни только перевод, без пояснений. Разговорные обороты переводи разговорно, термины оставляй как есть.", true));
        list.add(new Role("fix", "Поправить текст",
                "Исправь орфографию, пунктуацию и неуклюжие обороты, не меняя смысл и интонацию автора. Верни только готовый текст.", true));
        list.add(new Role("reply", "Набросать ответ",
                "Напиши вариант ответа на присланное сообщение от моего лица: по-человечески, в тон собеседнику, не длиннее двух-трёх предложений. Верни только сам ответ.", true));
        list.add(new Role("simple", "Объяснить просто",
                "Объясни, о чём речь, так, чтобы понял человек не из этой области. Без жаргона, с одним бытовым примером.", true));
        list.add(new Role("code", "Код",
                "Ты помогаешь с кодом. Отвечай кодом и парой строк пояснения, без лекций. Если видишь ошибку — покажи исправленный фрагмент целиком.", true));
        list.add(new Role("check", "Проверить на враньё",
                "Разбери присланное утверждение: что в нём похоже на правду, что сомнительно, чего не хватает для проверки. Пиши честно, что именно ты знать не можешь.", true));
        return list;
    }

    // ------------------------------------------------------------ свои роли

    public static List<Role> custom() {
        final ArrayList<Role> list = new ArrayList<>();
        try {
            final String raw = PengramAI.prefs().getString(KEY_ROLES, null);
            if (TextUtils.isEmpty(raw)) {
                return list;
            }
            final JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.getJSONObject(i);
                list.add(new Role(o.optString("id"), o.optString("title"), o.optString("prompt"), false));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return list;
    }

    public static List<Role> all() {
        final ArrayList<Role> list = new ArrayList<>(builtin());
        list.addAll(custom());
        return list;
    }

    private static void save(List<Role> list) {
        try {
            final JSONArray array = new JSONArray();
            for (Role r : list) {
                final JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("title", r.title);
                o.put("prompt", r.prompt);
                array.put(o);
            }
            PengramAI.prefs().edit().putString(KEY_ROLES, array.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static Role put(Role role) {
        if (TextUtils.isEmpty(role.id)) {
            role.id = "r" + System.currentTimeMillis();
        }
        final List<Role> list = custom();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (TextUtils.equals(list.get(i).id, role.id)) {
                list.set(i, role);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(role);
        }
        save(list);
        return role;
    }

    public static void remove(String id) {
        final List<Role> list = custom();
        for (int i = 0; i < list.size(); i++) {
            if (TextUtils.equals(list.get(i).id, id)) {
                list.remove(i);
                break;
            }
        }
        save(list);
        if (TextUtils.equals(activeId(), id)) {
            setActive("talk");
        }
    }

    public static String activeId() {
        return PengramAI.prefs().getString(KEY_ACTIVE, "talk");
    }

    public static void setActive(String id) {
        PengramAI.prefs().edit().putString(KEY_ACTIVE, id).apply();
    }

    public static Role active() {
        final String id = activeId();
        for (Role r : all()) {
            if (TextUtils.equals(r.id, id)) {
                return r;
            }
        }
        return builtin().get(0);
    }

    public static Role byId(String id) {
        for (Role r : all()) {
            if (TextUtils.equals(r.id, id)) {
                return r;
            }
        }
        return null;
    }

    static void resetAll() {
        PengramAI.prefs().edit().remove(KEY_ROLES).remove(KEY_ACTIVE).apply();
    }
}
