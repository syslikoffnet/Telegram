package org.telegram.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import static org.telegram.messenger.LocaleController.getString;

/** Search vocabulary and navigable breadcrumbs for real Pengram settings. */
public final class PengramSearchMetadata {
    private PengramSearchMetadata() { }

    /** Immutable, locale-scoped tokens. Text never comes from an account. */
    private static final class Corpus {
        final Locale locale;
        final Map<int[], String[]> words;
        Corpus(Locale locale, Map<int[], String[]> words) {
            this.locale = locale;
            this.words = words;
        }
    }

    private static volatile Corpus cachedCorpus;

    private static Corpus corpus() {
        Locale locale = org.telegram.messenger.LocaleController.getInstance().getCurrentLocale();
        if (locale == null) locale = Locale.getDefault();
        Corpus current = cachedCorpus;
        if (current != null && current.locale.equals(locale)) return current;
        synchronized (PengramSearchMetadata.class) {
            current = cachedCorpus;
            if (current == null || !current.locale.equals(locale)) {
                Map<int[], String[]> words = new IdentityHashMap<>();
                for (int[] entry : PengramSearchIndex.ITEMS) {
                    // Только собственные слова строки: заголовок, группа, описание
                    // и синонимы. Полный путь сюда не входит — иначе запрос
                    // «сообщения» находил вообще все настройки раздела.
                    words.put(entry, normalized(tags(entry)).split(" +"));
                }
                current = new Corpus(locale, words);
                cachedCorpus = current;
            }
        }
        return current;
    }

    public static org.telegram.messenger.PengramConfig.SearchDestination destination(int[] entry) {
        for (int i = 0; i < PengramSearchIndex.ITEMS.length; i++) {
            if (sameEntry(PengramSearchIndex.ITEMS[i], entry)) {
                return new org.telegram.messenger.PengramConfig.SearchDestination(
                        PengramSearchIndex.KEYS[i], entry[1], entry[3]);
            }
        }
        for (int i = 0; i < PengramSearchIndex.GROUP_ITEMS.length; i++) {
            if (sameEntry(PengramSearchIndex.GROUP_ITEMS[i], entry)) {
                return new org.telegram.messenger.PengramConfig.SearchDestination(
                        PengramSearchIndex.GROUP_KEYS[i], entry[1], entry[3]);
            }
        }
        return null;
    }

    private static boolean sameEntry(int[] a, int[] b) {
        return a != null && b != null && a.length >= 4 && b.length >= 4
                && a[0] == b[0] && a[1] == b[1] && a[3] == b[3];
    }

    public static boolean canRemember(int[] entry) {
        for (int i = 0; i < PengramSearchIndex.ITEMS.length; i++) {
            if (sameEntry(PengramSearchIndex.ITEMS[i], entry)) return PengramSearchIndex.HISTORY_ALLOWED[i];
        }
        for (int[] group : PengramSearchIndex.GROUP_ITEMS) if (sameEntry(group, entry)) return true;
        return false;
    }

    public static int[] resolveDestination(org.telegram.messenger.PengramConfig.SearchDestination row) {
        if (row == null || row.key == null || row.key.trim().isEmpty()) return null;
        int[] candidate = null;
        boolean ambiguous = false;
        final int[][][] inventories = {PengramSearchIndex.ITEMS, PengramSearchIndex.GROUP_ITEMS};
        final String[][] keys = {PengramSearchIndex.KEYS, PengramSearchIndex.GROUP_KEYS};
        for (int list = 0; list < inventories.length; list++) {
            for (int i = 0; i < inventories[list].length; i++) {
                if (!row.key.equals(keys[list][i])) continue;
                final int[] entry = inventories[list][i];
                if (entry[1] == row.section && entry[3] == row.kind) return entry;
                if (candidate != null && !sameEntry(candidate, entry)) ambiguous = true;
                candidate = entry;
            }
        }
        // Ключ важнее старого раздела: перенесённая строка остаётся доступной,
        // но при двух новых владельцах не угадываем, куда хотел пользователь.
        return ambiguous ? null : candidate;
    }

    public static int[] migrateLegacyQuery(String query) {
        if (query == null || query.trim().length() < 2 || query.length() > 80) return null;
        final String[] terms = queryTerms(query);
        int best = 0;
        boolean tied = false;
        int[] result = null;
        try {
            for (int[] entry : PengramSearchIndex.ITEMS) {
                final String title = getString(entry[0]);
                if (title.isEmpty() || title.startsWith("LOC_ERR")) continue;
                final int score = relevance(entry, terms);
                if (score > best) { best = score; result = entry; tied = false; }
                else if (score == best && score > 0) tied = true;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        // Только совпадение всех слов в заголовке; синоним или ничья не дают
        // права превращать старый запрос в якобы совершённый переход.
        return best >= 260 && !tied && canRemember(result) ? result : null;
    }

    public static String historyPath(int[] entry) {
        final String full = path(entry);
        final int first = full.indexOf(" ➔ ");
        final int last = full.lastIndexOf(" ➔ ");
        return first >= 0 && last > first ? full.substring(first + 3, last) : "";
    }

    public static void rememberReached(int[] requested, int actualRes) {
        if (requested == null) return;
        if (requested[0] == actualRes) {
            org.telegram.messenger.PengramConfig.rememberSearchDestination(destination(requested));
            return;
        }
        for (int[] entry : PengramSearchIndex.ITEMS) {
            if (entry[0] == actualRes && entry[1] == requested[1] && entry[3] == requested[3]) {
                org.telegram.messenger.PengramConfig.rememberSearchDestination(destination(entry));
                return;
            }
        }
        for (int[] group : PengramSearchIndex.GROUP_ITEMS) {
            if (group[0] == actualRes && group[1] == requested[1]) {
                org.telegram.messenger.PengramConfig.rememberSearchDestination(destination(group));
                return;
            }
        }
    }

    public static void open(BaseFragment fragment, int[] entry) {
        open(fragment, entry, false);
    }

    public static void open(BaseFragment fragment, int[] entry, boolean remember) {
        if (entry == null || entry.length < 4) return;
        org.telegram.messenger.PengramDiagnostics.recordSearch(entry, fragment);
        if (entry[3] == 1) {
            PengramChatLookActivity target = new PengramChatLookActivity(entry[0]);
            if (remember) target.searchDestination = entry;
            fragment.presentFragment(target);
        } else if (entry[3] == 2) {
            PengramProfileHistorySettingsActivity target = new PengramProfileHistorySettingsActivity(entry[0]);
            if (remember) target.searchDestination = entry;
            fragment.presentFragment(target);
        } else {
            PengramSettingsActivity target = new PengramSettingsActivity(entry[1], entry[0], entry[2]);
            if (remember) target.pendingSearchDestination = entry;
            fragment.presentFragment(target);
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
                || id == R.string.PengramQuoteAvatar || id == R.string.PengramChatLook
                || id == R.string.PengramAvatarPos) {
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
        if (id == R.string.PengramMD3Menus) tags.append(" меню попап контекстное скругление углы menu popup context corners островок действий кнопки сообщения island message actions материал 3");
        if (id == R.string.PengramSendStyle) tags.append(" шрифт шрифтом писать написание написания текста жирный курсив моно цитата font bold italic quote");
        if (id == R.string.PengramSendStyleLargeEmoji) tags.append(" смайлы маленькие крупные эмодзи шрифт big emoji");
        if (id == R.string.PengramQuoteAvatar) tags.append(" фото отправителя цитата");
        if (id == R.string.PengramIdFormatHide) tags.append(" скрыть айди скрыть id идентификатор профиль");
        if (id == R.string.PengramTabBarGlass) tags.append(" стекло размытие блюр прозрачность блик blur glass frosted transparency");
        if (id == R.string.PengramGlassMore) tags.append(" стекло настройка прозрачность блик glass settings tune");
        if (id == R.string.PengramGlassOpacity) tags.append(" прозрачность плотность непрозрачность стекло alpha transparency opacity density");
        if (id == R.string.PengramGlassThickness) tags.append(" толщина линза преломление кромка thickness lens");
        if (id == R.string.PengramGlassIntensity) tags.append(" преломление искажение линза refraction distortion");
        if (id == R.string.PengramGlassGlow) tags.append(" блик свечение яркость подсветка glow highlight brightness");
        if (id == R.string.PengramGlassSheen) tags.append(" блик полоса переключение вкладки прокрутка sheen swipe");
        if (id == R.string.PengramGlassDrag) tags.append(" палец жест перетаскивание протяжка drag finger gesture");
        if (id == R.string.PengramGlassRim) tags.append(" кант кромка обводка контур rim edge outline");
        if (id == R.string.PengramMessageIslandLinkTrash) tags.append(" ссылка удаление корзина удалить убрать trash delete link");
        if (id == R.string.PengramOriginalName) tags.append(" имя контакт проверка настоящее официальное имя профиль name contact check real profile");
        if (id == R.string.PengramDeviceModel) tags.append(" устройство модель телефон ноутбук компьютер подмена сеансы device model phone laptop pc computer spoof sessions");
        if (id == R.string.PengramDeviceSpoof) tags.append(" устройство модель телефон ноутбук компьютер подмена сеансы device model phone laptop pc computer spoof sessions samsung pixel");
        if (id == R.string.PengramDeviceVersion) tags.append(" версия ос система андроид ios iphone ipados macos windows version os system");
        if (id == R.string.PengramDeviceVersionSpoof) tags.append(" версия ос система подмена spoof version os system");
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
        if (has(own, "голос", "voice")) tags.append(" речь аудио голосовые войсы voice speech");
        if (has(own, "отправ", "send", "sent")) tags.append(" послать отправка отправлять переслать send submit");
        if (entry[1] == PengramSettingsActivity.SECTION_TYPING) tags.append(" анимация ввода печатание каретка курсор частицы исчезновение эмодзи typing caret cursor particles deletion emoji");
        if (has(own, "ввод", "набор", "писать", "typing")) tags.append(" печатать клавиатура текст набор ввода typing keyboard");
        if (has(own, "анимац", "эффект", "animation", "effect")) tags.append(" движение эффекты плавно animate motion");
        if (has(own, "пузыр", "bubble")) tags.append(" бабл баллон сообщение bubble chat");
        if (has(own, "икон", "значок", "icon")) tags.append(" значки символ пиктограмма icon symbol");
        if (has(own, "цвет", "color", "colour", "акцент")) tags.append(" оттенок палитра окраска accent palette color");
        if (has(own, "тем", "theme", "оформлен")) tags.append(" дизайн внешний вид тема appearance look skin theme");
        if (has(own, "поиск", "search")) tags.append(" найти находить искать лупа search find");
        if (has(own, "профил", "profile")) tags.append(" страница аккаунта пользователь профиль account bio");
        if (has(own, "имя", "ник", "username", "name")) tags.append(" никнейм юзернейм название nickname handle name");
        if (has(own, "телефон", "номер", "phone")) tags.append(" мобила контакт номер телефона mobile phone");
        if (has(own, "ссылк", "link", "url")) tags.append(" адрес url линк ссылка hyperlink");
        if (has(own, "истори", "history")) tags.append(" архив журнал версии лог история log archive");
        if (has(own, "измен", "правк", "edit")) tags.append(" редакция исправления отредактировано edit edited version");
        if (has(own, "медиа", "media")) tags.append(" фото видео файлы вложение attachment photo video");
        if (has(own, "музык", "трек", "music", "track")) tags.append(" песня аудиоплеер композиция playback song music");
        if (has(own, "сохран", "save")) tags.append(" записать оставить копия сохранить store keep save");
        if (has(own, "хранил", "storage", "лимит", "limit")) tags.append(" память место объём размер capacity storage space");
        if (has(own, "времен", "дат", "time", "date")) tags.append(" часы когда дата день время timestamp date");
        if (has(own, "скрин", "screenshot")) tags.append(" снимок экрана скриншот screen capture");
        if (has(own, "скорост", "speed")) tags.append(" быстрее медленнее ускорение темп pace rate");
        if (has(own, "фон", "background", "обои", "wallpaper")) tags.append(" задний план фон обои заставка background wallpaper");
        if (has(own, "эмодзи", "emoji", "смайл")) tags.append(" смайлики эмоджи смайлы emoji emoticon");
        if (has(own, "удерж", "нажат", "tap", "press")) tags.append(" тап коснуться кнопка касание клик touch click longpress");
        if (has(own, "увелич", "масштаб", "zoom")) tags.append(" больше крупнее масштаб размер zoom scale");
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

    /** Normalize once for an entire search, not once per result on each keystroke. */
    public static String[] queryTerms(String query) {
        String q = normalized(query == null ? "" : query);
        return q.isEmpty() ? new String[0] : q.split(" +");
    }

    /** Token prefixes make "аватарка", "аватарки" and "аву" useful alike. */
    public static boolean matches(int[] entry, String query) {
        return matches(entry, queryTerms(query));
    }

    public static boolean matches(int[] entry, String[] terms) {
        return relevance(entry, terms) > 0;
    }

    /**
     * Насколько строка похожа на запрос. 0 — не подходит, чем больше, тем выше
     * в выдаче. Заголовки с прямым совпадением идут первыми, совпадения только
     * по синонимам — последними: так выдача не тонет в «похожих» настройках.
     */
    public static int relevance(int[] entry, String[] terms) {
        if (terms == null || terms.length == 0) return 0;
        String[] words = corpus().words.get(entry);
        if (words == null) words = normalized(tags(entry)).split(" +");
        final String title = normalized(getString(entry[0]));
        final String[] titleWords = title.isEmpty() ? new String[0] : title.split(" +");

        int inTitle = 0;
        for (String term : terms) {
            if (wordMatches(titleWords, term)) inTitle++;
        }
        if (inTitle == 0) {
            // совпадение только по синонимам/описанию — всё равно проверяем все слова
            for (String term : terms) {
                if (!wordMatches(words, term)) return 0;
            }
            return 100;
        }
        for (String term : terms) {
            if (!wordMatches(words, term)) return 0;
        }
        if (inTitle == terms.length) {
            final StringBuilder joined = new StringBuilder();
            for (String term : terms) {
                if (joined.length() > 0) joined.append(' ');
                joined.append(term);
            }
            final boolean prefix = title.startsWith(joined.toString());
            // короткий точный заголовок важнее длинного с тем же словом
            return (prefix ? 420 : 340) - Math.min(80, title.length());
        }
        return 220;
    }

    /** Слово совпадает по началу: «аватар» → «аватарка», «аватарки» → «аватар». */
    private static boolean wordMatches(String[] words, String term) {
        for (String word : words) {
            if (word.startsWith(term) || (word.length() >= 4 && term.length() >= 4
                    && term.length() <= word.length() + 3 && term.startsWith(word))) {
                return true;
            }
        }
        return false;
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
