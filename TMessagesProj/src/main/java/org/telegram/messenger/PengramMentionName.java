package org.telegram.messenger;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.URLSpanUserMention;

/**
 * Pengram: упоминание человека по имени, а не по @юзернейму.
 *
 * <p>В списке, который открывается после «@», обычный тап вставляет @username —
 * и тем самым показывает всем ник, который человек, возможно, не хочет
 * светить в этом чате. Долгое нажатие вставляет имя, под которым человек
 * записан у вас в контактах, и прикрепляет к нему обычную сущность
 * mention_name. Уведомление приходит, а ника в тексте нет.
 *
 * <p>То же самое спасает людей без юзернейма: их имя и раньше вставлялось по
 * тапу, но только имя, а не полное «Имя Фамилия», и без выбора. Здесь берётся
 * полное имя из контакта.
 *
 * <p>Настройка выключена по умолчанию? Нет — включена: жест ничего не делает,
 * пока его не вызвали, ничего не отправляет и не меняет видимость номера.
 * Выключить можно в настройках, там же объяснение.
 */
public final class PengramMentionName {

    /** включено ли упоминание по имени */
    public static final String KEY_ENABLED = "mentionByName";

    /** длиннее в ник никто не поверит, и это ломает вёрстку черновика */
    public static final int MAX_LENGTH = 64;

    private PengramMentionName() {
    }

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean value) {
        PengramConfig.setBool(KEY_ENABLED, value);
    }

    /**
     * Имя для упоминания: как человек записан у вас (это и есть имя из
     * контакта — Telegram хранит вашу правку имени на сервере), иначе имя из
     * профиля. Обрезается по границе символа, чтобы не разрезать эмодзи.
     */
    public static String displayName(TLRPC.User user) {
        if (user == null) {
            return null;
        }
        String name = UserObject.getUserName(user);
        if (TextUtils.isEmpty(name)) {
            name = UserObject.getFirstName(user, false);
        }
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        // переносы строк в имени сломали бы строку ввода
        name = name.replace('\n', ' ').replace('\r', ' ').trim();
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() > MAX_LENGTH) {
            int end = MAX_LENGTH;
            if (Character.isHighSurrogate(name.charAt(end - 1))) {
                end--;
            }
            name = name.substring(0, end).trim();
        }
        return name;
    }

    /** готовая вставка: имя + пробел, с сущностью упоминания на самом имени */
    public static CharSequence buildMention(TLRPC.User user) {
        final String name = displayName(user);
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        final SpannableString spannable = new SpannableString(name + " ");
        spannable.setSpan(new URLSpanUserMention(String.valueOf(user.id), 3),
                0, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }
}
