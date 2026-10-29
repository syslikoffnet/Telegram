package org.telegram.messenger;

import android.text.TextUtils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pengram: встроенный калькулятор, конвертер физических величин и живой конвертер валют/крипто
 * прямо в строке набора сообщения (< 0.05 мс на UI-потоке, нулевые блокировки).
 */
public final class PengramInlineCalc {

    public static final int KIND_MATH = 0;
    public static final int KIND_UNIT = 1;
    public static final int KIND_CURRENCY = 2;

    public static final class Suggestion {
        public final int kind;
        public final String signature;
        public final String displayTitle;
        public final String displaySubtitle;
        public final int replaceStart;
        public final int replaceEnd;
        public final String primaryReplacement;
        public final String secondaryLabel;
        public final int secondaryReplaceStart;
        public final int secondaryReplaceEnd;
        public final String secondaryReplacement;
        public final boolean loadingRates;

        public Suggestion(
                int kind,
                String signature,
                String displayTitle,
                String displaySubtitle,
                int replaceStart,
                int replaceEnd,
                String primaryReplacement,
                String secondaryLabel,
                int secondaryReplaceStart,
                int secondaryReplaceEnd,
                String secondaryReplacement,
                boolean loadingRates
        ) {
            this.kind = kind;
            this.signature = signature;
            this.displayTitle = displayTitle;
            this.displaySubtitle = displaySubtitle;
            this.replaceStart = replaceStart;
            this.replaceEnd = replaceEnd;
            this.primaryReplacement = primaryReplacement;
            this.secondaryLabel = secondaryLabel;
            this.secondaryReplaceStart = secondaryReplaceStart;
            this.secondaryReplaceEnd = secondaryReplaceEnd;
            this.secondaryReplacement = secondaryReplacement;
            this.loadingRates = loadingRates;
        }
    }

    // --- Валютные алиасы ---
    private static final Map<String, String> CURRENCY_ALIASES = new HashMap<>();
    /** Однозначные токены для автоподсказки цены без «=» и без «в ...» (исключая одиночные «р», «м», «г»). */
    private static final Map<String, String> UNAMBIGUOUS_CURRENCY_ALIASES = new HashMap<>();

    // --- Единицы измерения ---
    private static final int CAT_LENGTH = 1;
    private static final int CAT_WEIGHT = 2;
    private static final int CAT_TEMP = 3;
    private static final int CAT_VOLUME = 4;
    private static final int CAT_DATA = 5;
    private static final int CAT_SPEED = 6;

    private static final class UnitDef {
        final int category;
        final double factorToBase; // для температуры: 1=C, 2=F, 3=K
        final String shortLabelRu;
        final String shortLabelEn;

        UnitDef(int category, double factorToBase, String shortLabelRu, String shortLabelEn) {
            this.category = category;
            this.factorToBase = factorToBase;
            this.shortLabelRu = shortLabelRu;
            this.shortLabelEn = shortLabelEn;
        }
    }

    private static final Map<String, UnitDef> UNIT_ALIASES = new HashMap<>();

    static {
        // Символы и ISO-коды валют
        regCur("RUB", true, "₽", "rub", "rur", "руб", "рубл", "рубль", "рубля", "рублей", "рублях");
        regCur("RUB", false, "р", "р.");
        regCur("USD", true, "$", "usd", "долл", "доллар", "доллара", "долларов", "бакс", "бакса", "баксов");
        regCur("EUR", true, "€", "eur", "евро");
        regCur("GBP", true, "£", "gbp", "фунт стерлингов", "фунтов стерлингов");
        regCur("UAH", true, "₴", "uah", "грн", "гривна", "гривны", "гривен");
        regCur("KZT", true, "₸", "kzt", "тг", "тенге");
        regCur("BYN", true, "byn", "бел руб", "бел.руб");
        regCur("UZS", true, "uzs", "сум", "сумов", "сума");
        regCur("GEL", true, "₾", "gel", "лари");
        regCur("AMD", true, "֏", "amd", "драм", "драмов");
        regCur("AZN", true, "₼", "azn", "манат", "манатов");
        regCur("KGS", true, "kgs", "сом", "сомов");
        regCur("TJS", true, "tjs", "сомони");
        regCur("TRY", true, "₺", "try", "лир", "лира", "лиры");
        regCur("CNY", true, "cny", "rmb", "юань", "юаня", "юаней");
        regCur("JPY", true, "¥", "jpy", "иен", "иена", "иены", "йен", "йена", "йены");
        regCur("AED", true, "aed", "дирхам", "дирхама", "дирхамов");
        regCur("PLN", true, "zł", "pln", "злотый", "злотых");
        regCur("CHF", true, "chf", "франк", "франка", "франков");
        regCur("CAD", true, "cad");
        regCur("AUD", true, "aud");
        regCur("THB", true, "฿", "thb", "бат", "бата", "батов");
        regCur("VND", true, "₫", "vnd", "донг", "донгов");
        regCur("IDR", true, "idr", "рупий индонезийских");
        regCur("INR", true, "₹", "inr", "рупия", "рупии", "рупий");
        regCur("KRW", true, "₩", "krw", "вон", "вона", "воны");
        regCur("ILS", true, "₪", "ils", "шекель", "шекеля", "шекелей");
        regCur("BRL", true, "brl", "реал", "реала", "реалов");
        regCur("CZK", true, "czk", "крон", "крона", "кроны");
        regCur("SEK", true, "sek");
        regCur("NOK", true, "nok");
        regCur("RSD", true, "rsd", "динар", "динаров");

        // Крипто и Telegram
        regCur("BTC", true, "₿", "btc", "биток", "биткоин", "биткоина", "биткоинов");
        regCur("ETH", true, "eth", "эфир", "эфира", "эфириум");
        regCur("TON", true, "ton", "toncoin", "тон", "тона", "тонов");
        regCur("USDT", true, "usdt", "тезер");
        regCur("USDC", true, "usdc");
        regCur("SOL", true, "sol", "солана", "соланы");
        regCur("BNB", true, "bnb");
        regCur("XRP", true, "xrp");
        regCur("DOGE", true, "doge", "доги");
        regCur("LTC", true, "ltc", "лайткоин");
        regCur("TRX", true, "trx", "трон");
        regCur("XTR", true, "★", "xtr", "stars", "star", "звезд", "звёзд", "звезды", "звёзды");

        // Длина (база = метры)
        regUnit(new UnitDef(CAT_LENGTH, 0.001, "мм", "mm"), "mm", "мм", "миллиметр", "миллиметра", "миллиметров");
        regUnit(new UnitDef(CAT_LENGTH, 0.01, "см", "cm"), "cm", "см", "сантиметр", "сантиметра", "сантиметров");
        regUnit(new UnitDef(CAT_LENGTH, 1.0, "м", "m"), "m", "м", "метр", "метра", "метров");
        regUnit(new UnitDef(CAT_LENGTH, 1000.0, "км", "km"), "km", "км", "километр", "километра", "километров");
        regUnit(new UnitDef(CAT_LENGTH, 0.0254, "дюйм.", "in"), "in", "inch", "inches", "дюйм", "дюйма", "дюймов");
        regUnit(new UnitDef(CAT_LENGTH, 0.3048, "фут.", "ft"), "ft", "foot", "feet", "фут", "фута", "футов");
        regUnit(new UnitDef(CAT_LENGTH, 0.9144, "ярд.", "yd"), "yd", "yard", "yards", "ярд", "ярда", "ярдов");
        regUnit(new UnitDef(CAT_LENGTH, 1609.344, "миль", "mi"), "mi", "mile", "miles", "миля", "мили", "миль");

        // Вес (база = килограммы)
        regUnit(new UnitDef(CAT_WEIGHT, 0.000001, "мг", "mg"), "mg", "мг", "миллиграмм", "миллиграммов");
        regUnit(new UnitDef(CAT_WEIGHT, 0.001, "г", "g"), "g", "gr", "г", "гр", "грамм", "грамма", "граммов");
        regUnit(new UnitDef(CAT_WEIGHT, 1.0, "кг", "kg"), "kg", "кг", "килограмм", "килограмма", "килограммов");
        regUnit(new UnitDef(CAT_WEIGHT, 1000.0, "т", "t"), "t", "tonn", "т", "тонна", "тонны", "тонн");
        regUnit(new UnitDef(CAT_WEIGHT, 0.028349523125, "унц.", "oz"), "oz", "ounce", "ounces", "унция", "унции", "унций");
        regUnit(new UnitDef(CAT_WEIGHT, 0.45359237, "фунт.", "lb"), "lb", "lbs", "pound", "pounds", "фунт", "фунта", "фунтов");

        // Температура
        regUnit(new UnitDef(CAT_TEMP, 1.0, "°C", "°C"), "c", "°c", "цельсий", "цельсия", "celsius");
        regUnit(new UnitDef(CAT_TEMP, 2.0, "°F", "°F"), "f", "°f", "фаренгейт", "фаренгейта", "fahrenheit");
        regUnit(new UnitDef(CAT_TEMP, 3.0, "K", "K"), "k", "°k", "кельвин", "кельвина", "kelvin");

        // Объём (база = литры)
        regUnit(new UnitDef(CAT_VOLUME, 0.001, "мл", "ml"), "ml", "мл", "миллилитр", "миллилитра", "миллилитров");
        regUnit(new UnitDef(CAT_VOLUME, 1.0, "л", "L"), "l", "л", "литр", "литра", "литров");
        regUnit(new UnitDef(CAT_VOLUME, 3.785411784, "гал.", "gal"), "gal", "gallon", "gallons", "галлон", "галлона", "галлонов");
        regUnit(new UnitDef(CAT_VOLUME, 0.0295735295625, "жидк. унц.", "fl oz"), "floz", "fl.oz");

        // Данные (база = байты, двоичные 1024)
        regUnit(new UnitDef(CAT_DATA, 1.0, "Б", "B"), "byte", "bytes", "байт", "байта", "байтов");
        regUnit(new UnitDef(CAT_DATA, 1024.0, "КБ", "KB"), "kb", "кб", "килобайт");
        regUnit(new UnitDef(CAT_DATA, 1024.0 * 1024.0, "МБ", "MB"), "mb", "мб", "мегабайт");
        regUnit(new UnitDef(CAT_DATA, 1024.0 * 1024.0 * 1024.0, "ГБ", "GB"), "gb", "гб", "гигабайт");
        regUnit(new UnitDef(CAT_DATA, 1024.0 * 1024.0 * 1024.0 * 1024.0, "ТБ", "TB"), "tb", "тб", "терабайт");

        // Скорость (база = м/с)
        regUnit(new UnitDef(CAT_SPEED, 1.0, "м/с", "m/s"), "m/s", "mps", "м/с");
        regUnit(new UnitDef(CAT_SPEED, 1.0 / 3.6, "км/ч", "km/h"), "km/h", "kmh", "км/ч", "кмч");
        regUnit(new UnitDef(CAT_SPEED, 0.44704, "миль/ч", "mph"), "mph", "миль/ч");
        regUnit(new UnitDef(CAT_SPEED, 0.514444, "узл.", "kn"), "kn", "knot", "knots", "узел", "узла", "узлов");
    }

    private static void regCur(String code, boolean unambiguous, String... aliases) {
        for (String a : aliases) {
            final String key = a.toLowerCase(Locale.ROOT);
            CURRENCY_ALIASES.put(key, code);
            if (unambiguous) {
                UNAMBIGUOUS_CURRENCY_ALIASES.put(key, code);
            }
        }
    }

    private static void regUnit(UnitDef def, String... aliases) {
        for (String a : aliases) {
            UNIT_ALIASES.put(a.toLowerCase(Locale.ROOT), def);
        }
    }

    // Скомпилированные регулярные выражения (Android ART-safe)
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[^\\s]+",
            Pattern.CASE_INSENSITIVE
    );

    /** Явная конвертация в конце или во всём сообщении: [=] <выражение/число> <из> (в|во|to|in|->|→) <в> */
    private static final Pattern EXPLICIT_CONVERT_PATTERN = Pattern.compile(
            "(?:^|(?<=\\s))(=\\s*)?([0-9(][0-9()+\\-*/^×÷:.,\\u00A0 ]*[0-9)]|[0-9]+(?:[.,][0-9]+)?(?:[kкmм])?)\\s*"
                    + "([$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿]|°?[A-Za-zА-Яа-яЁё./]+)\\s*"
                    + "(?:в|во|in|to|into|->|→)\\s*"
                    + "([$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿]|°?[A-Za-zА-Яа-яЁё./]+)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /** Префиксная валюта с явным переводом: [=] $150 в руб */
    private static final Pattern PREFIX_EXPLICIT_CURRENCY_PATTERN = Pattern.compile(
            "(?:^|(?<=\\s))(=\\s*)?([$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿])\\s*([0-9][0-9\\u00A0 ]*(?:[.,][0-9]+)?(?:[kкmм])?)\\s*"
                    + "(?:в|во|in|to|into|->|→)\\s*"
                    + "([$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿]|[A-Za-zА-Яа-яЁё.]+)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /** Быстрая цена вида «= 150$», «150$», «$150», «3500 лир», «25 ton» (в том числе внутри фразы). */
    private static final Pattern SUFFIX_PRICE_PATTERN = Pattern.compile(
            "(?:^|(?<=[\\s(\\[«\"']))(=\\s*)?([0-9][0-9\\u00A0 ]*(?:[.,][0-9]+)?(?:[kкmм])?)\\s*"
                    + "([$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿]|[A-Za-zА-Яа-яЁё]{2,12})(?=$|[\\s.,!?;)\\]»\"'])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern PREFIX_PRICE_PATTERN = Pattern.compile(
            "(?:^|(?<=[\\s(\\[«\"']))(=\\s*)?([$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿])\\s*"
                    + "([0-9][0-9\\u00A0 ]*(?:[.,][0-9]+)?(?:[kкmм])?)(?=$|[\\s.,!?;)\\]»\"'])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /** Проценты вида «= 20% от 4500» / «= 20% of 4500». */
    private static final Pattern PERCENT_OF_PATTERN = Pattern.compile(
            "^([0-9]+(?:[.,][0-9]+)?)\\s*%\\s*(?:от|из|of)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /** Хвостовое «=» в конце текста: «Итого 1450 * 3.2 =» */
    private static final Pattern TRAILING_EQUALS_PATTERN = Pattern.compile(
            "([0-9(][0-9()+\\-*/^×÷:%.,\\u00A0 kкmм]*[0-9)%kкmм])\\s*=\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private PengramInlineCalc() {
    }

    /**
     * Анализирует текущий текст поля ввода и возвращает подсказку или {@code null}.
     */
    public static Suggestion analyze(CharSequence rawText, int currentAccount) {
        if (rawText == null || rawText.length() == 0 || rawText.length() > 600) {
            return null;
        }
        final boolean calcOn = PengramConfig.isInlineCalcEnabled();
        final boolean curOn = PengramConfig.isInlineCurrencyEnabled();
        if (!calcOn && !curOn) {
            return null;
        }

        final String text = rawText.toString();
        // Быстрый отсев команд ботов
        if (text.startsWith("/") && !text.startsWith("/=")) {
            return null;
        }

        // 1. Явная конвертация «150$ в руб», «= 100 миль в км», «$150 to eur»
        Suggestion explicit = matchExplicitConversion(text, calcOn, curOn, currentAccount);
        if (explicit != null) {
            return explicit;
        }

        // 2. Явный префикс «= ...» (математика или одиночная валюта «= 150р»)
        final String trimmedLead = stripLeadingSpaces(text);
        if (trimmedLead.startsWith("=")) {
            final int eqIdx = text.indexOf('=');
            final String afterEq = text.substring(eqIdx + 1).trim();
            if (!afterEq.isEmpty()) {
                // Попробуем «= 150$» / «= 5000 р» как валюту
                if (curOn) {
                    Suggestion eqCur = matchLeadingEqualsCurrency(text, eqIdx, afterEq, currentAccount);
                    if (eqCur != null) {
                        return eqCur;
                    }
                }
                // Чистая математика «= 1450 * 3.2»
                if (calcOn) {
                    Suggestion math = evaluateMathSuggestion(afterEq, eqIdx, text.length(), true);
                    if (math != null) {
                        return math;
                    }
                }
            }
            return null;
        }

        // 3. Хвостовое «=» в конце строки: «Скинь 1450 * 3.2 =»
        if (calcOn && text.trim().endsWith("=")) {
            final Matcher tm = TRAILING_EQUALS_PATTERN.matcher(text);
            if (tm.find()) {
                final String expr = tm.group(1).trim();
                if (containsMathOperator(expr)) {
                    Suggestion trailMath = evaluateTrailingEqualsMath(text, tm.start(1), text.length(), expr);
                    if (trailMath != null) {
                        return trailMath;
                    }
                }
            }
        }

        // 4. Ненавязчивая живая подсказка цены («150$», «$49.99», «25 ton», «3500 лир»)
        if (curOn) {
            return matchImplicitPrice(text, currentAccount);
        }

        return null;
    }

    private static Suggestion matchExplicitConversion(String text, boolean calcOn, boolean curOn, int currentAccount) {
        Matcher m = EXPLICIT_CONVERT_PATTERN.matcher(text);
        boolean prefixSym = false;
        if (!m.find()) {
            m = PREFIX_EXPLICIT_CURRENCY_PATTERN.matcher(text);
            if (!m.find()) {
                return null;
            }
            prefixSym = true;
        }
        if (overlapsUrl(text, m.start(), m.end())) {
            return null;
        }
        final boolean hasLeadingEq = m.group(1) != null;
        final String exprPart = prefixSym ? m.group(3) : m.group(2);
        final String fromRaw = (prefixSym ? m.group(2) : m.group(3)).trim().toLowerCase(Locale.ROOT);
        final String toRaw = m.group(4).trim().toLowerCase(Locale.ROOT);

        final Double amount = evaluateNumericExpression(exprPart);
        if (amount == null || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return null;
        }

        // Проверяем валюты (приоритетно, если оба токена — валюты)
        final String fromCur = resolveCurrencyCode(fromRaw, true);
        final String toCur = resolveCurrencyCode(toRaw, true);
        if (fromCur != null && toCur != null) {
            if (!curOn || fromCur.equals(toCur)) {
                return null;
            }
            return buildCurrencySuggestion(
                    text, m.start(), m.end(), hasLeadingEq,
                    amount, fromCur, toCur, currentAccount, true
            );
        }

        // Проверяем физические величины
        if (calcOn && !prefixSym) {
            final UnitDef uFrom = resolveUnit(fromRaw);
            final UnitDef uTo = resolveUnit(toRaw);
            if (uFrom != null && uTo != null && uFrom.category == uTo.category && uFrom != uTo) {
                final double converted = convertUnits(amount, uFrom, uTo);
                if (Double.isNaN(converted) || Double.isInfinite(converted)) {
                    return null;
                }
                final boolean ru = isRussianUi();
                final String fromLbl = ru ? uFrom.shortLabelRu : uFrom.shortLabelEn;
                final String toLbl = ru ? uTo.shortLabelRu : uTo.shortLabelEn;
                final String formattedVal = PengramCurrencyRates.formatSmartNumber(converted, false) + " " + toLbl;
                final String srcFormatted = PengramCurrencyRates.formatSmartNumber(amount, false) + " " + fromLbl;
                final String tapHint = safeString(org.telegram.messenger.R.string.PengramCalcTapToInsert,
                        ru ? "нажмите для подстановки" : "tap to insert");
                final String matchedOriginal = text.substring(m.start(), m.end()).trim();
                final String secRepl = hasLeadingEq
                        ? (srcFormatted + " = " + formattedVal)
                        : (matchedOriginal + " (" + formattedVal + ")");
                final String secChip = hasLeadingEq ? "= …" : "(…)";
                return new Suggestion(
                        KIND_UNIT,
                        "unit:" + amount + ":" + fromLbl + ":" + toLbl,
                        "= " + formattedVal,
                        srcFormatted + " → " + toLbl + "  ·  " + tapHint,
                        m.start(), m.end(),
                        formattedVal,
                        secChip,
                        m.start(), m.end(),
                        secRepl,
                        false
                );
            }
        }
        return null;
    }

    private static Suggestion matchLeadingEqualsCurrency(String fullText, int eqIdx, String afterEq, int currentAccount) {
        // Форматы: «= 150$», «= $150», «= (40 + 10) usd», «= 5000 р»
        String numExpr = null;
        String curToken = null;
        if (!afterEq.isEmpty()) {
            final char first = afterEq.charAt(0);
            if ("$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿".indexOf(first) >= 0) {
                curToken = String.valueOf(first);
                numExpr = afterEq.substring(1).trim();
            } else {
                // Отрезаем хвостовой валютный токен
                int split = afterEq.length();
                while (split > 0) {
                    char c = afterEq.charAt(split - 1);
                    if (Character.isLetter(c) || c == '.' || "$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★₿".indexOf(c) >= 0) {
                        split--;
                    } else {
                        break;
                    }
                }
                if (split > 0 && split < afterEq.length()) {
                    numExpr = afterEq.substring(0, split).trim();
                    curToken = afterEq.substring(split).trim();
                }
            }
        }
        if (TextUtils.isEmpty(numExpr) || TextUtils.isEmpty(curToken)) {
            return null;
        }
        final String fromCur = resolveCurrencyCode(curToken, true);
        if (fromCur == null) {
            return null;
        }
        final Double amount = evaluateNumericExpression(numExpr);
        if (amount == null || amount <= 0.0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return null;
        }
        final String targetCur = pickAutoTargetCurrency(fromCur);
        return buildCurrencySuggestion(
                fullText, eqIdx, fullText.length(), true,
                amount, fromCur, targetCur, currentAccount, true
        );
    }

    private static Suggestion matchImplicitPrice(String text, int currentAccount) {
        // Ищем ПОСЛЕДНЕЕ однозначное упоминание цены в сообщении
        int bestStart = -1;
        int bestEnd = -1;
        double bestAmount = 0.0;
        String bestCur = null;

        final Matcher m1 = SUFFIX_PRICE_PATTERN.matcher(text);
        while (m1.find()) {
            if (overlapsUrl(text, m1.start(), m1.end())) {
                continue;
            }
            if (isFollowedByApproxAnnotation(text, m1.end())) {
                continue;
            }
            final String curRaw = m1.group(3).toLowerCase(Locale.ROOT);
            final String code = UNAMBIGUOUS_CURRENCY_ALIASES.get(curRaw);
            if (code == null) {
                continue;
            }
            final Double val = evaluateNumericExpression(m1.group(2));
            if (val != null && val > 0.0 && !Double.isNaN(val) && !Double.isInfinite(val)) {
                bestStart = m1.start();
                bestEnd = m1.end();
                bestAmount = val;
                bestCur = code;
            }
        }

        final Matcher m2 = PREFIX_PRICE_PATTERN.matcher(text);
        while (m2.find()) {
            if (m2.start() < bestStart || overlapsUrl(text, m2.start(), m2.end())) {
                continue;
            }
            if (isFollowedByApproxAnnotation(text, m2.end())) {
                continue;
            }
            final String curRaw = m2.group(2).toLowerCase(Locale.ROOT);
            final String code = UNAMBIGUOUS_CURRENCY_ALIASES.get(curRaw);
            if (code == null) {
                continue;
            }
            final Double val = evaluateNumericExpression(m2.group(3));
            if (val != null && val > 0.0 && !Double.isNaN(val) && !Double.isInfinite(val)) {
                bestStart = m2.start();
                bestEnd = m2.end();
                bestAmount = val;
                bestCur = code;
            }
        }

        if (bestCur == null || bestStart < 0) {
            return null;
        }
        final String defaultTarget = PengramConfig.resolveCalcDefaultCurrency();
        // В неявном режиме без «=», если человек просто пишет в своей родной валюте («купил за 500 руб»),
        // не спамим переводом в доллары, КРОМЕ случая, когда сообщение состоит ТОЛЬКО из суммы («5000₽»).
        if (bestCur.equals(defaultTarget) && !(bestStart == 0 && bestEnd == text.trim().length())) {
            return null;
        }
        final String targetCur = pickAutoTargetCurrency(bestCur);
        return buildCurrencySuggestion(
                text, bestStart, bestEnd, false,
                bestAmount, bestCur, targetCur, currentAccount, false
        );
    }

    private static String pickAutoTargetCurrency(String fromCur) {
        final String def = PengramConfig.resolveCalcDefaultCurrency();
        if (!def.equalsIgnoreCase(fromCur)) {
            return def;
        }
        return "USD".equalsIgnoreCase(fromCur) ? "EUR" : "USD";
    }

    private static Suggestion buildCurrencySuggestion(
            String fullText,
            int spanStart,
            int spanEnd,
            boolean hasLeadingEq,
            double amount,
            String fromCur,
            String toCur,
            int currentAccount,
            boolean explicit
    ) {
        PengramCurrencyRates.ensureFresh(currentAccount);
        final boolean ru = isRussianUi();

        if (!PengramCurrencyRates.canConvert(fromCur, toCur)) {
            final String loadingTxt = safeString(org.telegram.messenger.R.string.PengramCalcRatesLoading,
                    ru ? "обновление курса…" : "Updating rates…");
            final String srcStr = PengramCurrencyRates.formatAmountWithCurrency(amount, fromCur);
            return new Suggestion(
                    KIND_CURRENCY,
                    "cur:" + amount + ":" + fromCur + ":" + toCur,
                    srcStr + " → " + PengramCurrencyRates.symbolFor(toCur),
                    loadingTxt,
                    spanStart, spanEnd,
                    fullText.substring(spanStart, spanEnd),
                    null, spanStart, spanEnd, null,
                    true
            );
        }

        final double converted = PengramCurrencyRates.convert(amount, fromCur, toCur);
        if (Double.isNaN(converted) || Double.isInfinite(converted)) {
            return null;
        }
        final String formattedTarget = PengramCurrencyRates.formatAmountWithCurrency(converted, toCur);
        final String formattedSource = PengramCurrencyRates.formatAmountWithCurrency(amount, fromCur);

        // Кросс-курс за 1 единицу (или за 100 / 1000 при мелких номиналах вроде UZS/JPY/KRW/IDR)
        double unitBase = 1.0;
        double unitRate = PengramCurrencyRates.convert(1.0, fromCur, toCur);
        if (unitRate > 0.0 && unitRate < 0.05 && !PengramCurrencyRates.isCrypto(fromCur)) {
            unitBase = 100.0;
            unitRate = PengramCurrencyRates.convert(100.0, fromCur, toCur);
        }
        final String unitCross = (unitBase == 1.0 ? "1 " : "100 ")
                + PengramCurrencyRates.symbolFor(fromCur)
                + " = "
                + PengramCurrencyRates.formatAmountWithCurrency(unitRate, toCur);

        // Если переводим свою валюту в USD (например, «5000₽»), покажем рядом и EUR
        String extraCross = "";
        if (!explicit && "USD".equals(toCur) && !"EUR".equals(fromCur) && PengramCurrencyRates.canConvert(fromCur, "EUR")) {
            final double inEur = PengramCurrencyRates.convert(amount, fromCur, "EUR");
            extraCross = "  ·  ≈ " + PengramCurrencyRates.formatAmountWithCurrency(inEur, "EUR");
        }

        final String liveBadge = safeString(org.telegram.messenger.R.string.PengramCalcRatesLive,
                ru ? "актуальный курс" : "live rate");
        final String subtitle = formattedSource + " → " + PengramCurrencyRates.symbolFor(toCur)
                + "  ·  " + unitCross + " (" + liveBadge + ")";

        final String matchedSlice = fullText.substring(spanStart, spanEnd);
        final String secLabel;
        final String secRepl;
        if (hasLeadingEq) {
            secLabel = "= …";
            secRepl = formattedSource + " ≈ " + formattedTarget;
        } else {
            secLabel = "(≈ …)";
            secRepl = matchedSlice + " (≈ " + formattedTarget + ")";
        }

        return new Suggestion(
                KIND_CURRENCY,
                "cur:" + amount + ":" + fromCur + ":" + toCur,
                "≈ " + formattedTarget + extraCross,
                subtitle,
                spanStart, spanEnd,
                formattedTarget,
                secLabel,
                spanStart, spanEnd,
                secRepl,
                false
        );
    }

    private static Suggestion evaluateMathSuggestion(String expr, int replaceStart, int replaceEnd, boolean leadingEquals) {
        // Не перехватываем голые одиночные числа вроде «= 1450» без операторов/функций/суффиксов
        if (!containsMathOperatorOrSuffix(expr)) {
            return null;
        }
        final Double result = evaluateNumericExpression(expr);
        if (result == null || Double.isNaN(result) || Double.isInfinite(result)) {
            return null;
        }
        final String prettyResult = PengramCurrencyRates.formatSmartNumber(result, false);
        final String plainInsert = formatPlainInsertNumber(result);
        final String cleanExpr = expr.trim();
        final boolean ru = isRussianUi();
        final String tapHint = safeString(org.telegram.messenger.R.string.PengramCalcTapToInsert,
                ru ? "нажмите для подстановки" : "tap to insert");

        return new Suggestion(
                KIND_MATH,
                "math:" + cleanExpr,
                "= " + prettyResult,
                prettifyMathDisplay(cleanExpr) + "  ·  " + tapHint,
                replaceStart, replaceEnd,
                plainInsert,
                "= …",
                replaceStart, replaceEnd,
                cleanExpr + " = " + plainInsert,
                false
        );
    }

    private static Suggestion evaluateTrailingEqualsMath(String fullText, int exprStart, int exprEnd, String expr) {
        final Double result = evaluateNumericExpression(expr);
        if (result == null || Double.isNaN(result) || Double.isInfinite(result)) {
            return null;
        }
        final String prettyResult = PengramCurrencyRates.formatSmartNumber(result, false);
        final String plainInsert = formatPlainInsertNumber(result);
        final String cleanExpr = expr.trim();
        final boolean ru = isRussianUi();
        final String tapHint = safeString(org.telegram.messenger.R.string.PengramCalcTapToInsert,
                ru ? "нажмите для подстановки" : "tap to insert");
        final String keepEq = cleanExpr + " = " + plainInsert;

        return new Suggestion(
                KIND_MATH,
                "tmath:" + cleanExpr,
                "= " + prettyResult,
                prettifyMathDisplay(cleanExpr) + "  ·  " + tapHint,
                exprStart, exprEnd,
                keepEq,
                plainInsert,
                exprStart, exprEnd,
                plainInsert,
                false
        );
    }

    private static double convertUnits(double val, UnitDef from, UnitDef to) {
        if (from.category == CAT_TEMP) {
            double celsius;
            if (from.factorToBase == 1.0) {
                celsius = val;
            } else if (from.factorToBase == 2.0) {
                celsius = (val - 32.0) * 5.0 / 9.0;
            } else {
                celsius = val - 273.15;
            }
            if (to.factorToBase == 1.0) {
                return celsius;
            } else if (to.factorToBase == 2.0) {
                return celsius * 9.0 / 5.0 + 32.0;
            } else {
                return celsius + 273.15;
            }
        }
        final double inBase = val * from.factorToBase;
        return inBase / to.factorToBase;
    }

    private static String resolveCurrencyCode(String raw, boolean allowShortAmbiguous) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        if (k.length() > 1 && k.endsWith(".")) {
            final String noDot = k.substring(0, k.length() - 1);
            if (CURRENCY_ALIASES.containsKey(noDot)) {
                k = noDot;
            }
        }
        final String found = allowShortAmbiguous ? CURRENCY_ALIASES.get(k) : UNAMBIGUOUS_CURRENCY_ALIASES.get(k);
        if (found != null) {
            return found;
        }
        // Любой 3-буквенный ISO-код, уже загруженный в таблицу живых курсов (MXN, SGD, HKD, NZD, ZAR, ...)
        if (k.length() == 3 && isAsciiLetters(k)) {
            final String iso = k.toUpperCase(Locale.US);
            if (PengramCurrencyRates.getUnitsPerUsd(iso) != null) {
                return iso;
            }
        }
        return null;
    }

    private static UnitDef resolveUnit(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        if (k.length() > 1 && k.endsWith(".")) {
            k = k.substring(0, k.length() - 1);
        }
        return UNIT_ALIASES.get(k);
    }

    private static boolean isAsciiLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlapsUrl(String text, int start, int end) {
        if (!text.contains("http") && !text.contains("www.") && !text.contains(".com") && !text.contains("t.me/")) {
            return false;
        }
        final Matcher um = URL_PATTERN.matcher(text);
        while (um.find()) {
            if (start < um.end() && end > um.start()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFollowedByApproxAnnotation(String text, int endIdx) {
        int i = endIdx;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i < text.length() && text.charAt(i) == '(') {
            final String tail = text.substring(i, Math.min(text.length(), i + 6));
            return tail.contains("≈") || tail.contains("=");
        }
        return false;
    }

    private static String stripLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }

    private static boolean containsMathOperator(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '+' || c == '*' || c == '×' || c == '/' || c == '÷' || c == ':' || c == '^' || c == '%') {
                return true;
            }
            if (c == '-' && i > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMathOperatorOrSuffix(String s) {
        if (containsMathOperator(s)) {
            return true;
        }
        final String low = s.toLowerCase(Locale.ROOT);
        return low.contains("sqrt") || low.contains("корень") || low.contains("sin")
                || low.contains("cos") || low.contains("tan") || low.contains("log")
                || low.contains("ln") || low.contains("abs") || low.contains("round")
                || low.endsWith("k") || low.endsWith("к") || low.endsWith("m") || low.endsWith("м");
    }

    private static String prettifyMathDisplay(String s) {
        return s.replace('*', '×').replace('/', '÷');
    }

    private static String formatPlainInsertNumber(double val) {
        if (Math.abs(val - Math.rint(val)) < 1e-9 && Math.abs(val) < 1e15) {
            return Long.toString(Math.round(val));
        }
        final DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        sym.setDecimalSeparator('.');
        final DecimalFormat df = new DecimalFormat("0.########", sym);
        return df.format(val);
    }

    private static boolean isRussianUi() {
        try {
            final Locale loc = LocaleController.getInstance().getCurrentLocale();
            if (loc != null && loc.getLanguage() != null) {
                final String l = loc.getLanguage();
                return "ru".equals(l) || "uk".equals(l) || "be".equals(l) || "kk".equals(l);
            }
        } catch (Throwable ignore) {
        }
        return true;
    }

    private static String safeString(int resId, String fallback) {
        try {
            if (ApplicationLoader.applicationContext != null) {
                final String s = LocaleController.getString(resId);
                if (!TextUtils.isEmpty(s)) {
                    return s;
                }
            }
        } catch (Throwable ignore) {
        }
        return fallback;
    }

    // =========================================================================
    // Быстрый рекурсивный парсер математических выражений (без аллокаций AST)
    // =========================================================================

    public static Double evaluateNumericExpression(String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 256) {
            return null;
        }

        // «20% от 4500» / «20% of 4500»
        final Matcher pm = PERCENT_OF_PATTERN.matcher(trimmed);
        if (pm.matches()) {
            final Double pct = evaluateNumericExpression(pm.group(1));
            final Double base = evaluateNumericExpression(pm.group(2));
            if (pct != null && base != null) {
                return base * (pct / 100.0);
            }
            return null;
        }

        // Подготовка строки: «×»->«*», «÷»/«:»->«/», «**»->«^», «−»->«-»
        final String norm = normalizeMathInput(trimmed);
        if (norm.isEmpty()) {
            return null;
        }
        final MathParser parser = new MathParser(norm);
        final double val = parser.parseExpression();
        if (parser.failed || parser.pos < norm.length() || Double.isNaN(val) || Double.isInfinite(val)) {
            return null;
        }
        return val;
    }

    private static String normalizeMathInput(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\u00A0' || c == '\u202F' || c == '\u2009') {
                sb.append(' ');
            } else if (c == '×' || c == '⋅' || c == '·') {
                sb.append('*');
            } else if (c == '÷' || c == ':') {
                sb.append('/');
            } else if (c == '−' || c == '–' || c == '—') {
                sb.append('-');
            } else if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                sb.append('^');
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static final class MathParser {
        final String s;
        final int len;
        int pos;
        boolean failed;

        MathParser(String s) {
            this.s = s;
            this.len = s.length();
        }

        void skipSpaces() {
            while (pos < len && s.charAt(pos) == ' ') {
                pos++;
            }
        }

        double parseExpression() {
            double left = parseTerm();
            if (failed) return Double.NaN;
            while (true) {
                skipSpaces();
                if (pos >= len) break;
                final char op = s.charAt(pos);
                if (op != '+' && op != '-') break;
                pos++;
                final int saved = pos;
                double right = parseTerm();
                if (failed) return Double.NaN;
                skipSpaces();
                // Умный процент при сложении/вычитании: «1000 + 15%» => 1150, «1000 - 20%» => 800
                if (wasJustPercentFactor && !containsHighPrecedenceBetween(saved, pos)) {
                    // parseFactor уже поделил на 100? Нет: флаг выставляется ТОЛЬКО на чистом «N%»!
                    right = left * right;
                }
                left = (op == '+') ? (left + right) : (left - right);
            }
            return left;
        }

        private boolean containsHighPrecedenceBetween(int from, int to) {
            for (int i = from; i < to && i < len; i++) {
                final char c = s.charAt(i);
                if (c == '*' || c == '/' || c == '^') {
                    return true;
                }
            }
            return false;
        }

        boolean wasJustPercentFactor;

        double parseTerm() {
            double left = parsePower();
            boolean singlePercent = wasJustPercentFactor;
            if (failed) return Double.NaN;
            while (true) {
                skipSpaces();
                if (pos >= len) break;
                final char op = s.charAt(pos);
                if (op != '*' && op != '/') break;
                singlePercent = false;
                pos++;
                double right = parsePower();
                if (failed) return Double.NaN;
                if (op == '*') {
                    left *= right;
                } else {
                    if (right == 0.0) {
                        failed = true;
                        return Double.NaN;
                    }
                    left /= right;
                }
            }
            wasJustPercentFactor = singlePercent;
            return left;
        }

        double parsePower() {
            double base = parseUnary();
            if (failed) return Double.NaN;
            final boolean pct = wasJustPercentFactor;
            skipSpaces();
            if (pos < len && s.charAt(pos) == '^') {
                pos++;
                wasJustPercentFactor = false;
                final double exp = parsePower();
                if (failed) return Double.NaN;
                wasJustPercentFactor = false;
                return Math.pow(base, exp);
            }
            wasJustPercentFactor = pct;
            return base;
        }

        double parseUnary() {
            skipSpaces();
            if (pos >= len) {
                failed = true;
                return Double.NaN;
            }
            final char c = s.charAt(pos);
            if (c == '+') {
                pos++;
                return parseUnary();
            }
            if (c == '-') {
                pos++;
                final double v = parseUnary();
                return -v;
            }
            return parsePrimary();
        }

        double parsePrimary() {
            wasJustPercentFactor = false;
            skipSpaces();
            if (pos >= len) {
                failed = true;
                return Double.NaN;
            }
            final char c = s.charAt(pos);
            double value;
            if (c == '(') {
                pos++;
                value = parseExpression();
                skipSpaces();
                if (pos >= len || s.charAt(pos) != ')') {
                    failed = true;
                    return Double.NaN;
                }
                pos++;
                wasJustPercentFactor = false;
            } else if (Character.isLetter(c) || c == 'π') {
                final int st = pos;
                while (pos < len && (Character.isLetter(s.charAt(pos)) || s.charAt(pos) == 'π')) {
                    pos++;
                }
                final String ident = s.substring(st, pos).toLowerCase(Locale.ROOT);
                if ("pi".equals(ident) || "пи".equals(ident) || "π".equals(ident)) {
                    value = Math.PI;
                } else if ("e".equals(ident)) {
                    value = Math.E;
                } else {
                    skipSpaces();
                    if (pos >= len || s.charAt(pos) != '(') {
                        failed = true;
                        return Double.NaN;
                    }
                    pos++;
                    final double arg = parseExpression();
                    skipSpaces();
                    if (pos >= len || s.charAt(pos) != ')') {
                        failed = true;
                        return Double.NaN;
                    }
                    pos++;
                    value = applyFunction(ident, arg);
                    if (Double.isNaN(value)) {
                        failed = true;
                        return Double.NaN;
                    }
                }
            } else if ((c >= '0' && c <= '9') || c == '.' || c == ',') {
                value = parseNumberLiteral();
                if (failed) return Double.NaN;
            } else {
                failed = true;
                return Double.NaN;
            }

            // Множители k / к (×1 000) и m / м (×1 000 000) сразу после числа
            if (pos < len) {
                final char suf = s.charAt(pos);
                if ((suf == 'k' || suf == 'K' || suf == 'к' || suf == 'К')
                        && (pos + 1 >= len || !Character.isLetter(s.charAt(pos + 1)))) {
                    value *= 1000.0;
                    pos++;
                } else if ((suf == 'm' || suf == 'M' || suf == 'м' || suf == 'М')
                        && (pos + 1 >= len || !Character.isLetter(s.charAt(pos + 1)))) {
                    value *= 1_000_000.0;
                    pos++;
                }
            }

            // Суффикс процента «%»: переводит в долю (0.15) и помечает флаг для «1000 + 15%»
            skipSpaces();
            if (pos < len && s.charAt(pos) == '%') {
                pos++;
                value /= 100.0;
                wasJustPercentFactor = true;
            }
            return value;
        }

        private double parseNumberLiteral() {
            final StringBuilder num = new StringBuilder(16);
            boolean seenDecimal = false;
            while (pos < len) {
                final char ch = s.charAt(pos);
                if (ch >= '0' && ch <= '9') {
                    num.append(ch);
                    pos++;
                } else if (ch == ' ' && !seenDecimal && num.length() > 0 && isThousandsGroupAhead(pos)) {
                    // Пробел-разделитель разрядов вроде «1 450 * 3,2»
                    pos++;
                } else if ((ch == '.' || ch == ',') && !seenDecimal) {
                    seenDecimal = true;
                    num.append('.');
                    pos++;
                } else {
                    break;
                }
            }
            if (num.length() == 0 || ".".equals(num.toString())) {
                failed = true;
                return Double.NaN;
            }
            try {
                return Double.parseDouble(num.toString());
            } catch (NumberFormatException e) {
                failed = true;
                return Double.NaN;
            }
        }

        private boolean isThousandsGroupAhead(int spaceIdx) {
            if (spaceIdx + 3 >= len) {
                return false;
            }
            for (int i = 1; i <= 3; i++) {
                final char d = s.charAt(spaceIdx + i);
                if (d < '0' || d > '9') {
                    return false;
                }
            }
            if (spaceIdx + 4 < len) {
                final char after = s.charAt(spaceIdx + 4);
                if (after >= '0' && after <= '9') {
                    return false;
                }
            }
            return true;
        }

        private double applyFunction(String fn, double arg) {
            switch (fn) {
                case "sqrt":
                case "корень":
                    return arg < 0.0 ? Double.NaN : Math.sqrt(arg);
                case "abs":
                    return Math.abs(arg);
                case "round":
                    return Math.rint(arg);
                case "sin":
                    return Math.sin(arg);
                case "cos":
                    return Math.cos(arg);
                case "tan":
                    return Math.tan(arg);
                case "ln":
                    return arg <= 0.0 ? Double.NaN : Math.log(arg);
                case "log":
                    return arg <= 0.0 ? Double.NaN : Math.log10(arg);
                default:
                    return Double.NaN;
            }
        }
    }
}
