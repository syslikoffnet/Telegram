package org.telegram.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.SparseArray;

import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import static org.telegram.messenger.LocaleController.getString;

/** Search vocabulary and navigable breadcrumbs for real Pengram settings. */
public final class PengramSearchMetadata {
    private PengramSearchMetadata() { }

    /** Precomputed, locale-scoped search entry: zero regex, zero getString, and zero stem allocations per keystroke. */
    private static final class EntryDoc {
        final int[] entry;
        final String rawTitle;
        final boolean indexable;
        final String normTitle;
        final String[] titleWords;
        final String[] titleStems;
        final String[] words;
        final String[] wordStems;
        final String fullPath;
        final String lowerFoldedPath;
        final int leafStart;

        EntryDoc(int[] entry, String rawTitle, String normTitle, String[] titleWords, String[] titleStems,
                 String[] words, String[] wordStems, String fullPath, String lowerFoldedPath, int leafStart) {
            this.entry = entry;
            this.rawTitle = rawTitle;
            this.indexable = !TextUtils.isEmpty(rawTitle) && !rawTitle.startsWith("LOC_ERR");
            this.normTitle = normTitle;
            this.titleWords = titleWords;
            this.titleStems = titleStems;
            this.words = words;
            this.wordStems = wordStems;
            this.fullPath = fullPath;
            this.lowerFoldedPath = lowerFoldedPath;
            this.leafStart = leafStart;
        }
    }

    /** Immutable, locale-scoped tokens. Text never comes from an account. */
    private static final class Corpus {
        final Locale locale;
        final EntryDoc[] docs;
        final Map<int[], EntryDoc> byEntry;
        Corpus(Locale locale, EntryDoc[] docs, Map<int[], EntryDoc> byEntry) {
            this.locale = locale;
            this.docs = docs;
            this.byEntry = byEntry;
        }
    }

    /** One-pass prepared query variants cached across all 437 relevance() calls of a single keystroke. */
    private static final class PreparedTerms {
        final String[] sourceRef;
        final String[] terms;
        final String[] stems;
        final String joined;
        final boolean hasFlip;
        final String[] flipTerms;
        final String[] flipStems;
        final String joinedFlip;
        final boolean hasTranslit;
        final String[] translitTerms;
        final String[] translitStems;
        final String joinedTranslit;

        PreparedTerms(String[] terms) {
            this.sourceRef = terms;
            this.terms = terms;
            this.stems = buildStems(terms);
            this.joined = joinTerms(terms);

            boolean flipDiff = false;
            final String[] fTerms = new String[terms.length];
            for (int i = 0; i < terms.length; i++) {
                final String f = normalized(org.telegram.messenger.PengramSmartSearch.flipKeyboardLayout(terms[i]));
                fTerms[i] = f.isEmpty() ? terms[i] : f;
                if (!fTerms[i].equals(terms[i])) {
                    flipDiff = true;
                }
            }
            this.hasFlip = flipDiff;
            this.flipTerms = flipDiff ? fTerms : null;
            this.flipStems = flipDiff ? buildStems(fTerms) : null;
            this.joinedFlip = flipDiff ? joinTerms(fTerms) : null;

            boolean trDiff = false;
            final String[] trTerms = new String[terms.length];
            for (int i = 0; i < terms.length; i++) {
                final String tr = normalized(org.telegram.messenger.PengramSmartSearch.transliterate(terms[i]));
                trTerms[i] = tr.isEmpty() ? terms[i] : tr;
                if (!trTerms[i].equals(terms[i]) && (!flipDiff || !trTerms[i].equals(fTerms[i]))) {
                    trDiff = true;
                }
            }
            this.hasTranslit = trDiff;
            this.translitTerms = trDiff ? trTerms : null;
            this.translitStems = trDiff ? buildStems(trTerms) : null;
            this.joinedTranslit = trDiff ? joinTerms(trTerms) : null;
        }
    }

    private static final class PreparedHighlight {
        final String rawQuery;
        final String foldedQuery;
        final String[] tokens;

        PreparedHighlight(String rawQuery) {
            this.rawQuery = rawQuery;
            final String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
            this.foldedQuery = q;
            if (q.isEmpty()) {
                this.tokens = new String[0];
            } else {
                final String flipped = org.telegram.messenger.PengramSmartSearch.flipKeyboardLayout(q);
                final String[] t1 = queryTerms(q);
                final String[] t2 = flipped.isEmpty() ? new String[0] : queryTerms(flipped);
                final ArrayList<String> list = new ArrayList<>(t1.length + t2.length);
                for (String t : t1) {
                    if (t.length() >= 2 && !list.contains(t)) list.add(t);
                }
                for (String t : t2) {
                    if (t.length() >= 2 && !list.contains(t)) list.add(t);
                }
                this.tokens = list.toArray(new String[0]);
            }
        }
    }

    private static volatile Corpus cachedCorpus;
    private static volatile PreparedTerms cachedPreparedTerms;
    private static volatile PreparedHighlight cachedHighlight;
    private static volatile IdentityHashMap<int[], Integer> cachedEntryIds;
    private static volatile SparseArray<int[]> cachedIdEntries;

    private static void ensureIdMaps() {
        if (cachedEntryIds != null && cachedIdEntries != null) {
            return;
        }
        synchronized (PengramSearchMetadata.class) {
            if (cachedEntryIds == null || cachedIdEntries == null) {
                final int count = Math.min(PengramSearchIndex.ITEMS.length, PengramSearchIndex.IDS.length);
                final IdentityHashMap<int[], Integer> toId = new IdentityHashMap<>(count);
                final SparseArray<int[]> toEntry = new SparseArray<>(count);
                for (int i = 0; i < count; i++) {
                    final int[] entry = PengramSearchIndex.ITEMS[i];
                    final int id = PengramSearchIndex.IDS[i];
                    toId.put(entry, id);
                    toEntry.put(id, entry);
                }
                cachedEntryIds = toId;
                cachedIdEntries = toEntry;
            }
        }
    }

    /** Стабильный ID перехода для сохранения в «Недавние» (как guid в поиске Telegram). */
    public static int stableId(int[] entry) {
        if (entry == null || entry.length < 2) {
            return 0;
        }
        ensureIdMaps();
        final Integer fast = cachedEntryIds.get(entry);
        if (fast != null) {
            return fast;
        }
        final int count = Math.min(PengramSearchIndex.ITEMS.length, PengramSearchIndex.IDS.length);
        final int ext = entry.length > 3 ? entry[3] : 0;
        for (int i = 0; i < count; i++) {
            final int[] candidate = PengramSearchIndex.ITEMS[i];
            final int candExt = candidate.length > 3 ? candidate[3] : 0;
            if (candidate[0] == entry[0] && candidate[1] == entry[1] && candExt == ext) {
                return PengramSearchIndex.IDS[i];
            }
        }
        return 0;
    }

    public static int[] entryByStableId(int stableId) {
        if (stableId <= 0) {
            return null;
        }
        ensureIdMaps();
        return cachedIdEntries.get(stableId);
    }

    /** Запомнить сам переход к настройке в списке недавних (MRU). */
    public static void rememberEntry(int[] entry) {
        final int id = stableId(entry);
        if (id > 0) {
            PengramConfig.addRecentSearchId(id);
        }
    }

    /** Удалить конкретный переход из списка недавних. */
    public static void removeRecentEntry(int[] entry) {
        final int id = stableId(entry);
        if (id > 0) {
            PengramConfig.removeRecentSearchId(id);
        }
    }

    /** Актуальный список недавних переходов в порядке последнего открытия. */
    public static ArrayList<int[]> recentEntries() {
        final ArrayList<Integer> ids = PengramConfig.getRecentSearchIds();
        final ArrayList<int[]> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            final int[] entry = entryByStableId(ids.get(i));
            if (entry == null || entry.length < 2) {
                continue;
            }
            final String title = getString(entry[0]);
            if (TextUtils.isEmpty(title) || title.startsWith("LOC_ERR")) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    private static String[] buildStems(String[] tokens) {
        if (tokens == null || tokens.length == 0) {
            return new String[0];
        }
        final String[] out = new String[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            final String t = tokens[i];
            out[i] = t != null && t.length() >= 4 ? org.telegram.messenger.PengramSmartSearch.stem(t) : (t == null ? "" : t);
        }
        return out;
    }

    private static String joinTerms(String[] terms) {
        if (terms == null || terms.length == 0) {
            return "";
        }
        if (terms.length == 1) {
            return terms[0];
        }
        final StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < terms.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(terms[i]);
        }
        return sb.toString();
    }

    private static EntryDoc buildDoc(int[] entry) {
        final String rawTitle = getString(entry[0]);
        final String normTitle = normalized(rawTitle);
        final String[] titleWords = org.telegram.messenger.PengramSmartSearch.splitTokens(normTitle);
        final String[] titleStems = buildStems(titleWords);
        // Только собственные слова строки: заголовок, группа, описание
        // и синонимы. Полный путь сюда не входит — иначе запрос
        // «сообщения» находил вообще все настройки раздела.
        final String[] words = normalized(tags(entry)).split(" +");
        final String[] wordStems = buildStems(words);
        final String fullPath = path(entry);
        final String lowerFoldedPath = fullPath.toLowerCase(Locale.ROOT).replace('ё', 'е');
        final int arrow = fullPath.lastIndexOf(" ➔ ");
        final int leafStart = arrow >= 0 ? arrow + 3 : 0;
        return new EntryDoc(entry, rawTitle, normTitle, titleWords, titleStems,
                words, wordStems, fullPath, lowerFoldedPath, leafStart);
    }

    private static Corpus corpus() {
        Locale locale = org.telegram.messenger.LocaleController.getInstance().getCurrentLocale();
        if (locale == null) locale = Locale.getDefault();
        Corpus current = cachedCorpus;
        if (current != null && current.locale.equals(locale)) return current;
        synchronized (PengramSearchMetadata.class) {
            current = cachedCorpus;
            if (current == null || !current.locale.equals(locale)) {
                final int n = PengramSearchIndex.ITEMS.length;
                final EntryDoc[] docs = new EntryDoc[n];
                final Map<int[], EntryDoc> byEntry = new IdentityHashMap<>(n);
                for (int i = 0; i < n; i++) {
                    final int[] entry = PengramSearchIndex.ITEMS[i];
                    final EntryDoc doc = buildDoc(entry);
                    docs[i] = doc;
                    byEntry.put(entry, doc);
                }
                current = new Corpus(locale, docs, byEntry);
                cachedCorpus = current;
            }
        }
        return current;
    }

    /** Фоновый прогрев индекса текущей локали: к первому нажатию клавиши корпус уже в RAM. */
    public static void warmupAsync() {
        Locale locale = org.telegram.messenger.LocaleController.getInstance().getCurrentLocale();
        if (locale == null) locale = Locale.getDefault();
        final Corpus current = cachedCorpus;
        if (current != null && current.locale.equals(locale)) {
            ensureIdMaps();
            return;
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                ensureIdMaps();
                corpus();
            } catch (Throwable ignore) {
            }
        });
    }

    /** Возвращает закэшированный для текущей локали заголовок строки (или {@code null}, если она не индексируется). */
    public static String cachedTitle(int[] entry) {
        if (entry == null || entry.length == 0) {
            return null;
        }
        EntryDoc doc = corpus().byEntry.get(entry);
        if (doc == null) {
            final String t = getString(entry[0]);
            return TextUtils.isEmpty(t) || t.startsWith("LOC_ERR") ? null : t;
        }
        return doc.indexable ? doc.rawTitle : null;
    }

    public static void open(BaseFragment fragment, int[] entry) {
        rememberEntry(entry);
        if (entry.length > 3 && entry[3] == 1) {
            fragment.presentFragment(new PengramChatLookActivity(entry[0]));
        } else if (entry.length > 3 && entry[3] == 2) {
            fragment.presentFragment(new PengramProfileHistorySettingsActivity(entry[0]));
        } else if (entry[0] == R.string.PengramInputToolsSection) {
            fragment.presentFragment(new PengramSettingsActivity(PengramSettingsActivity.SECTION_INPUT_TOOLS));
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
                || section == PengramSettingsActivity.SECTION_QUOTES
                || section == PengramSettingsActivity.SECTION_LINK_ASSISTANT
                || section == PengramSettingsActivity.SECTION_INPUT_TOOLS;
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
        if (id == R.string.PengramGlassJuicyPreset) tags.append(" максимум сочное красиво эффект линза преломление жидкое стекло apply preset vivid liquid glass");
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
        if (has(own, "ссылк", "link", "url")) tags.append(" адрес url линк ссылка hyperlink tiktok тикток youtube ютуб shorts шортс instagram инстаграм reels рилс twitter твиттер pinterest пинтерест vk вк клипы rutube рутуб reddit реддит soundcloud саундклауд яндекс");
        if (has(own, "предпросмотр", "preview")) tags.append(" превью выбор просмотр фото видео preview picker");
        if (has(own, "скле", "stitch", "кадр", "slide")) tags.append(" склеить слайдшоу карусель фото в видео slideshow carousel stitch");
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
        if (id == R.string.PengramInputToolsSection) {
            tags.append(" поиск калькулятор валюты курс быстрый поиск в интернете инете сети префикс точка строка ввода чата search calculator currency prefix web internet chat input");
        } else if (id == R.string.PengramInlineWebSearch) {
            tags.append(" быстрый поиск в интернете инете сети веб гугл яндекс википедия браузер префикс по префиксу точка знак символ вопрос строка ввода чата quick web internet search prefix dot chat input bar google yandex duckduckgo wikipedia");
        } else if (id == R.string.PengramWebSearchPrefix) {
            tags.append(" префикс поиска быстрый поиск знак символ точка вопрос восклицательный свой префикс интернет строка ввода search prefix trigger symbol dot custom web");
        } else if (id == R.string.PengramWebSearchEngine) {
            tags.append(" поисковик поисковая система браузер интернет гугл яндекс brave duckduckgo google yandex web search engine browser");
        } else if (id == R.string.PengramInlineCalc) {
            tags.append(" калькулятор считать посчитать вычисление математика примеры формула конвертер величин единицы измерения метры километры вес температура строка ввода inline calculator math unit converter");
        } else if (id == R.string.PengramInlineCurrency) {
            tags.append(" конвертер валют курс валюты валюта доллар евро рубль тенге гривна крипта криптовалюта биткоин тон звезды актуальный живой курс live currency crypto converter exchange rate usd eur rub ton btc");
        } else if (id == R.string.PengramCalcDefaultCurrency) {
            tags.append(" основная валюта перевода валюта по умолчанию рубль доллар евро курс target default currency rub usd eur");
        } else if (id == R.string.PengramCalcRefreshRates) {
            tags.append(" обновить курсы валют перезагрузить курс валюты крипта refresh update currency exchange rates");
        } else if (id == R.string.PengramServiceJoinLeaveProfile) {
            tags.append(" профиль вход выход вступил покинул чат группа уведомление сервисное сообщение join leave profile chat service message");
        } else if (id == R.string.PengramCylinderScroll || id == R.string.PengramCylinderToBars || id == R.string.PengramCylinderStrength) {
            tags.append(" цилиндр цилиндрическая прокрутка 3d 3д скролл изгиб сообщения гнутся барабан валик перспектива cylinder scroll bend curve 3d");
        } else if (id == R.string.PengramInputAnimation || id == R.string.PengramTypingCursorSmooth || id == R.string.PengramTypingCursorLiquid) {
            tags.append(" анимация ввода печать набор букв плавный курсор жидкий курсор каретка искры частицы typing animation smooth liquid cursor caret");
        } else if (id == R.string.PengramWebSearchHistory) {
            tags.append(" история поиска запросы веб очистить недавние web search history recent queries");
        } else if (id == R.string.PengramGhostReadOnReply) {
            tags.append(" прочтение при ответе прочитать при отправке галочки призрак невидимка read on reply ghost mark read");
        } else if (id == R.string.PengramVoiceAmbience || id == R.string.PengramVoiceAmbienceLevel || id == R.string.PengramVoiceAmbienceDuck) {
            tags.append(" фоновый шум атмосфера эмбиент амбиент саундборд метро улица дождь офис клавиатура рация помехи клуб голос звонок войс кружок background ambience noise soundboard subway street rain office radio club");
        } else if (id == R.string.PengramLinkIncomingWatch || id == R.string.PengramLinkIncomingOutgoing) {
            tags.append(" смотреть тут слушать тут входящие ссылки кнопка в чате встроенный плеер без браузера watch here listen inline player incoming links");
        } else if (id == R.string.PengramLinkQualityMode || id == R.string.PengramLinkDefaultFormat || id == R.string.PengramLinkAudioChip) {
            tags.append(" качество видео 1080p 720p экономия трафика максимальное только звук как аудио музыка mp3 m4a трек video quality data saver audio only music");
        }
        return tags.toString();
    }

    private static boolean has(String title, String... stems) {
        for (String stem : stems) if (title.contains(stem)) return true;
        return false;
    }

    private static String normalized(String input) {
        return org.telegram.messenger.PengramSmartSearch.normalize(input);
    }

    /** Normalize once for an entire search, not once per result on each keystroke. */
    public static String[] queryTerms(String query) {
        return org.telegram.messenger.PengramSmartSearch.splitTokens(normalized(query == null ? "" : query));
    }

    /** Token prefixes make "аватарка", "аватарки" and "аву" useful alike. */
    public static boolean matches(int[] entry, String query) {
        return matches(entry, queryTerms(query));
    }

    public static boolean matches(int[] entry, String[] terms) {
        return relevance(entry, terms) > 0;
    }

    private static PreparedTerms prepareTerms(String[] terms) {
        final PreparedTerms cached = cachedPreparedTerms;
        if (cached != null && (cached.sourceRef == terms || java.util.Arrays.equals(cached.terms, terms))) {
            return cached;
        }
        final PreparedTerms next = new PreparedTerms(terms);
        cachedPreparedTerms = next;
        return next;
    }

    /**
     * Насколько строка похожа на запрос. 0 — не подходит, чем больше, тем выше
     * в выдаче. Заголовки с прямым совпадением идут первыми, совпадения только
     * по синонимам — последними: так выдача не тонет в «похожих» настройках.
     */
    public static int relevance(int[] entry, String[] terms) {
        if (terms == null || terms.length == 0 || entry == null) return 0;
        EntryDoc doc = corpus().byEntry.get(entry);
        if (doc == null) {
            doc = buildDoc(entry);
        }
        if (!doc.indexable) {
            return 0;
        }
        final PreparedTerms prepared = prepareTerms(terms);
        final int direct = relevanceDirect(doc, prepared.terms, prepared.stems, prepared.joined);
        if (direct > 0) {
            return direct;
        }
        // Автоисправление забытой раскладки (QWERTY ↔ ЙЦУКЕН) и транслита (предвычислено 1 раз на запрос!)
        if (prepared.hasFlip) {
            final int byFlip = relevanceDirect(doc, prepared.flipTerms, prepared.flipStems, prepared.joinedFlip);
            if (byFlip > 0) {
                return Math.max(60, byFlip - 20);
            }
        }
        if (prepared.hasTranslit) {
            final int byTranslit = relevanceDirect(doc, prepared.translitTerms, prepared.translitStems, prepared.joinedTranslit);
            if (byTranslit > 0) {
                return Math.max(50, byTranslit - 30);
            }
        }
        return 0;
    }

    private static int relevanceDirect(EntryDoc doc, String[] terms, String[] termStems, String joinedTerms) {
        final String title = doc.normTitle;
        final String[] titleWords = doc.titleWords;
        final String[] titleStems = doc.titleStems;
        final String[] words = doc.words;
        final String[] wordStems = doc.wordStems;

        int inTitle = 0;
        for (int i = 0; i < terms.length; i++) {
            if (wordMatches(titleWords, titleStems, terms[i], termStems[i])) {
                inTitle++;
            }
        }
        if (inTitle == 0) {
            // совпадение только по синонимам/описанию — всё равно проверяем все слова
            for (int i = 0; i < terms.length; i++) {
                if (!wordMatches(words, wordStems, terms[i], termStems[i])) return 0;
            }
            return 100;
        }
        for (int i = 0; i < terms.length; i++) {
            if (!wordMatches(words, wordStems, terms[i], termStems[i])) return 0;
        }
        if (inTitle == terms.length) {
            final boolean prefix = !joinedTerms.isEmpty() && title.startsWith(joinedTerms);
            // короткий точный заголовок важнее длинного с тем же словом
            return (prefix ? 420 : 340) - Math.min(80, title.length());
        }
        return 220;
    }

    /** Если запрос введён в неверной раскладке и дал выдачу именно после переворота, возвращает читаемый вариант. */
    public static String suggestedFlippedQuery(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        final String q = rawQuery.trim();
        if (q.length() < 2) {
            return null;
        }
        final String flipped = org.telegram.messenger.PengramSmartSearch.flipKeyboardLayout(q).trim();
        if (flipped.isEmpty() || flipped.equalsIgnoreCase(q)) {
            return null;
        }
        final String[] rawTerms = queryTerms(q);
        final String[] flipTerms = queryTerms(flipped);
        if (rawTerms.length == 0 || flipTerms.length == 0) {
            return null;
        }
        final String[] rawStems = buildStems(rawTerms);
        final String rawJoined = joinTerms(rawTerms);
        final String[] flipStems = buildStems(flipTerms);
        final String flipJoined = joinTerms(flipTerms);
        final EntryDoc[] docs = corpus().docs;
        boolean anyFlipHit = false;
        for (int i = 0; i < docs.length; i++) {
            final EntryDoc doc = docs[i];
            if (doc == null || !doc.indexable) continue;
            // Как только прямое совпадение найдено — это НЕ ошибочная раскладка (ранний выход за микросекунды!)
            if (relevanceDirect(doc, rawTerms, rawStems, rawJoined) > 0) {
                return null;
            }
            if (!anyFlipHit && relevanceDirect(doc, flipTerms, flipStems, flipJoined) > 0) {
                anyFlipHit = true;
            }
        }
        return anyFlipHit ? flipped : null;
    }

    /** Слово совпадает по началу, предвычисленной основе или с допуском 1 опечатки на длинных словах без аллокаций. */
    private static boolean wordMatches(String[] words, String[] wordStems, String term, String termStem) {
        if (term == null || term.isEmpty() || words == null) {
            return false;
        }
        final int tLen = term.length();
        final int tsLen = termStem == null ? 0 : termStem.length();
        final char tFirst = term.charAt(0);
        for (int i = 0; i < words.length; i++) {
            final String word = words[i];
            if (word == null || word.isEmpty()) continue;
            final int wLen = word.length();
            if (word.startsWith(term) || (wLen >= 4 && tLen >= 4
                    && tLen <= wLen + 3 && term.startsWith(word))) {
                return true;
            }
            if (tsLen >= 4 && wLen >= 4 && wordStems != null && i < wordStems.length) {
                final String wordStem = wordStems[i];
                if (wordStem != null && wordStem.length() >= 3
                        && (wordStem.startsWith(termStem) || termStem.startsWith(wordStem))) {
                    return true;
                }
            }
            // Первая буква совпадает в 95% реальных опечаток; если длина >= 5, допускаем и замену/транспозицию 1-й буквы
            if (tLen >= 4 && wLen >= 4 && (word.charAt(0) == tFirst || tLen >= 5)) {
                if (org.telegram.messenger.PengramSmartSearch.withinPrefixOneEdit(word, term)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Full readable breadcrumb. Keep the destination bold even if a synonym matched. */
    public static CharSequence emphasizedPath(int[] entry, String query) {
        EntryDoc doc = entry != null ? corpus().byEntry.get(entry) : null;
        if (doc == null && entry != null) {
            doc = buildDoc(entry);
        }
        final String path = doc != null ? doc.fullPath : path(entry);
        final String lowerPath = doc != null ? doc.lowerFoldedPath : path.toLowerCase(Locale.ROOT).replace('ё', 'е');
        final int leaf = doc != null ? doc.leafStart : (path.lastIndexOf(" ➔ ") + 3);

        final SpannableStringBuilder result = new SpannableStringBuilder(path);
        if (leaf >= 0 && leaf < path.length()) {
            result.setSpan(new StyleSpan(Typeface.BOLD), leaf, path.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (TextUtils.isEmpty(query)) {
            return result;
        }
        PreparedHighlight hl = cachedHighlight;
        if (hl == null || !query.equals(hl.rawQuery)) {
            hl = new PreparedHighlight(query);
            cachedHighlight = hl;
        }
        final String q = hl.foldedQuery;
        if (q.isEmpty()) {
            return result;
        }
        final int start = lowerPath.indexOf(q);
        if (start >= 0 && start + q.length() <= path.length()) {
            result.setSpan(new StyleSpan(Typeface.BOLD), start, start + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            for (int i = 0; i < hl.tokens.length; i++) {
                final String token = hl.tokens[i];
                final int idx = lowerPath.indexOf(token);
                if (idx >= 0 && idx + token.length() <= path.length()) {
                    result.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + token.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return result;
    }
}
