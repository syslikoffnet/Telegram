package org.telegram.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.Locale;

import static org.telegram.messenger.LocaleController.getString;

/** Search vocabulary and navigable breadcrumbs for real Pengram settings. */
public final class PengramSearchMetadata {
    private PengramSearchMetadata() { }

    public static void open(BaseFragment fragment, int[] entry) {
        if (entry.length > 3 && entry[3] == 1) {
            fragment.presentFragment(new PengramChatLookActivity(entry[0]));
        } else if (entry.length > 3 && entry[3] == 2) {
            fragment.presentFragment(new PengramProfileHistorySettingsActivity(entry[0]));
        } else {
            fragment.presentFragment(new PengramSettingsActivity(entry[1], entry[0],
                    entry.length > 2 ? entry[2] : 0));
        }
    }

    public static String path(int[] entry) {
        StringBuilder path = new StringBuilder(getString(R.string.PengramSettings));
        int section = entry[1];
        if (isChatChild(section)) {
            path.append(" ➔ ").append(PengramSettingsActivity.sectionTitle(PengramSettingsActivity.SECTION_CHATS));
        }
        if (section != PengramSettingsActivity.SECTION_ROOT) {
            path.append(" ➔ ").append(PengramSettingsActivity.sectionTitle(section));
        }
        if (entry.length > 2 && entry[2] != 0 && entry[2] != entry[0]) {
            path.append(" ➔ ").append(getString(entry[2]));
        }
        path.append(" ➔ ").append(getString(entry[0]));
        return path.toString();
    }

    private static boolean isChatChild(int section) {
        return section == PengramSettingsActivity.SECTION_CHAT_ACTIONS
                || section == PengramSettingsActivity.SECTION_CHAT_MESSAGES
                || section == PengramSettingsActivity.SECTION_CHAT_INTERFACE
                || section == PengramSettingsActivity.SECTION_CHAT_MENUS
                || section == PengramSettingsActivity.SECTION_TYPING
                || section == PengramSettingsActivity.SECTION_QUOTES;
    }

    /** Every indexed setting has its own localized title, group and path tags;
     * additional colloquial tags are attached only to relevant controls. */
    public static String tags(int[] entry) {
        int id = entry[0];
        String title = getString(id);
        StringBuilder tags = new StringBuilder(title);
        if (entry.length > 2 && entry[2] != 0) tags.append(' ').append(getString(entry[2]));
        // Own description is indexed for every row that has one. The generated
        // resource ID avoids accidentally indexing the previous row's shadow.
        if (entry.length > 4 && entry[4] != 0) {
            String info = getString(entry[4]);
            if (info.indexOf('%') < 0) tags.append(' ').append(info);
        }
        if (id == R.string.PengramSenderAvatars || id == R.string.PengramSenderAvatarPosition
                || id == R.string.PengramQuoteAvatar || id == R.string.PengramChatLook) {
            tags.append(" аватарка аву фото профиля avatar hide avatar скрыть аву показывать фото");
        }
        if (id == R.string.PengramChatLook) {
            tags.append(" форма аватарки радиус скругления аватара окно чата avatar shape");
        }
        if (id == R.string.BubbleRadius) {
            tags.append(" скругление пузыря радиус сообщения corners roundness");
        }
        if (id == R.string.PengramQuoteRadius) {
            tags.append(" радиус скругления карточки цитаты quote corner");
        }
        if (id == R.string.PengramQuoteAvatar) tags.append(" фото отправителя цитата");
        // This is a storage limit for profile HISTORY, not an avatar-size control.
        if (id == R.string.PengramProfileLimitSize) tags.append(" размер истории профиля хранилище storage limit");
        // Shared concept vocabulary applies to each OWN title, not to an entire
        // section. This avoids irrelevant results such as quote corner radius
        // appearing for an avatar query simply because it shares a group.
        String own = normalized(title);
        if (has(own, "аватар", "avatar")) tags.append(" ава аву юзерпик userpic фото профиля");
        if (has(own, "скругл", "радиус", "round", "radius")) tags.append(" радиус закругление скругление углы corners");
        if (has(own, "размер", "size")) tags.append(" габарит величина размер size scale");
        if (has(own, "скры", "спрят", "hide")) tags.append(" скрыть убрать не показывать hide disable");
        if (has(own, "уведом", "notification")) tags.append(" пуш оповещение пуши уведомления notification");
        if (has(own, "сообщени", "message")) tags.append(" сообщения месседж переписка message chat");
        if (has(own, "облож", "cover")) tags.append(" картинка альбома обложка cover artwork");
        if (has(own, "плеер", "player")) tags.append(" проигрыватель музыка player audio");
        if (has(own, "шпион", "spy")) tags.append(" слежение отслеживание история spy");
        if (has(own, "призрак", "ghost")) tags.append(" невидимка невидимость инкогнито ghost");
        if (has(own, "удален", "удалён", "deleted")) tags.append(" удаленка стертые удаленные deleted");
        if (has(own, "шрифт", "font")) tags.append(" буквы типографика шрифт font");
        if (has(own, "вклад", "tab")) tags.append(" табы навигация вкладки tabs");
        if (has(own, "цитат", "quote")) tags.append(" цитирование цитата quote");
        if (has(own, "голос", "voice")) tags.append(" речь аудио voice speech");
        return tags.toString();
    }

    private static boolean has(String title, String... stems) {
        for (String stem : stems) if (title.contains(stem)) return true;
        return false;
    }

    private static String normalized(String input) {
        return input.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Token prefixes make "аватарка", "аватарки" and "аву" useful alike. */
    public static boolean matches(int[] entry, String query) {
        String q = normalized(query);
        if (q.isEmpty()) return false;
        String searchable = normalized(path(entry) + " " + tags(entry));
        for (String term : q.split(" +")) {
            boolean found = false;
            for (String word : searchable.split(" +")) {
                if (word.startsWith(term) || (word.length() >= 4 && term.length() >= 4
                        && term.length() <= word.length() + 3 && term.startsWith(word))) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Full readable breadcrumb. Keep the destination bold even if a synonym matched. */
    public static CharSequence emphasizedPath(int[] entry, String query) {
        String path = path(entry);
        SpannableStringBuilder result = new SpannableStringBuilder(path);
        int leaf = path.lastIndexOf(" ➔ ") + 3;
        result.setSpan(new StyleSpan(Typeface.BOLD), leaf, path.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String q = query.trim().toLowerCase(Locale.ROOT);
        int start = path.toLowerCase(Locale.ROOT).indexOf(q);
        if (!q.isEmpty() && start >= 0) {
            result.setSpan(new StyleSpan(Typeface.BOLD), start, start + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }
}
