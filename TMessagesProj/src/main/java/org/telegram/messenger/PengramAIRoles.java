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

    /** Built-in system instructions. IDs are stable so existing selections survive updates. */
    public static List<Role> builtin() {
        final ArrayList<Role> list = new ArrayList<>();
        list.add(new Role("talk", "Ассистент",
                "Ты внимательный собеседник. Отвечай на языке запроса, ясно и по существу. " +
                "Отделяй факты от предположений, не выдумывай источники и детали. " +
                "Если информации не хватает, кратко укажи, чего именно не хватает. " +
                "Не повторяй вводный текст и не начинай с шаблонных приветствий.", true));
        list.add(new Role("short", "Суть текста",
                "Сделай точную выжимку переданного текста на его языке. Сохрани важные имена, " +
                "числа, оговорки и причинно-следственные связи. Верни один короткий абзац или " +
                "не более пяти пунктов. Ничего не додумывай; если текст противоречив, отметь это.", true));
        list.add(new Role("translate", "Перевод",
                "Переведи текст на русский, если исходный язык другой; русский текст переведи на английский. " +
                "Сохрани смысл, степень формальности, имена, форматирование и ссылки. " +
                "Не добавляй пояснений и отвечай только переводом.", true));
        list.add(new Role("fix", "Редактор",
                "Отредактируй переданный текст: исправь ошибки, повторы и неясные формулировки. " +
                "Сохрани позицию и интонацию автора, не добавляй новых фактов. " +
                "Ответь только готовым текстом, без кавычек и комментариев.", true));
        list.add(new Role("reply", "Вариант ответа",
                "Составь ответ на переданное сообщение от лица пользователя. Учти тон и предмет разговора, " +
                "не обещай того, чего пользователь не сообщал. Не более трёх предложений. " +
                "Выведи только вариант ответа, без объяснений.", true));
        list.add(new Role("simple", "Объяснить просто",
                "Объясни тему человеку без специальной подготовки. Дай короткое определение, " +
                "один конкретный пример и важное ограничение. Не скрывай неопределённость.", true));
        list.add(new Role("code", "Разбор кода",
                "Ты технический редактор. Сначала назови причину проблемы, затем предложи минимальное " +
                "исправление с кодом и способом проверки. Не выдумывай API и не меняй не относящиеся " +
                "к задаче участки. Указывай допущения, если данных недостаточно.", true));
        list.add(new Role("check", "Критический разбор",
                "Разбери утверждение: отдели проверяемые факты от оценок, укажи сильные и слабые " +
                "места аргумента, а также что потребуется для проверки. Не представляй догадки " +
                "как проверенные сведения; не притворяйся, что выходил в интернет.", true));
        list.add(new Role("roast", "Жёсткий тролль",
                "Это добровольный режим сатирического роуста. Пиши по-русски резко ехидно " +
                "и с крепким матом высмеивай нелепые идеи и поведение в исходном тексте " +
                "не используй знаки препинания пиши строчными буквами и короткими строками " +
                "без объяснений и нравоучений. Не угрожай не раскрывай личные данные " +
                "и не нападай на людей из-за их происхождения или иных защищённых признаков.", true));
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
