package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_wallet;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pengram: актуальные курсы мировых валют, криптовалют и Telegram Stars (XTR / TON).
 *
 * <p>Источники объединяются в единую таблицу {@code unitsPerUsd} (1 USD = X единиц валюты):
 * <ol>
 *   <li>MTProto {@link TL_wallet.getCurrencyRates} + {@code tonUsdRate} / {@code starsUsdSellRate1000}
 *       (работает даже через встроенный прокси Telegram при блокировках внешних CDN);</li>
 *   <li>Мировые фиатные курсы {@code open.er-api.com} (резерв — {@code currency-api} jsDelivr);</li>
 *   <li>Курсы ЦБ РФ {@code cbr-xml-daily.ru} (быстрый прямой резерв в РФ);</li>
 *   <li>Спотовые крипто-котировки {@code api.binance.com} (BTC, ETH, TON, SOL, BNB, XRP, DOGE, LTC, TRX).</li>
 * </ol>
 * Все сетевые и дисковые операции выполняются строго вне UI-потока.
 */
public final class PengramCurrencyRates {

    private static final String PREFS_NAME = "pengram_currency_rates";
    private static final String KEY_RATES_JSON = "rates_json";
    private static final String KEY_UPDATED_AT = "updated_at_ms";

    /** Автообновление каждые 15 минут. */
    private static final long TTL_MS = 15L * 60L * 1000L;
    private static final int HTTP_TIMEOUT_MS = 4500;

    private static final ConcurrentHashMap<String, Double> UNITS_PER_USD = new ConcurrentHashMap<>();
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static final AtomicBoolean DISK_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean FETCH_IN_FLIGHT = new AtomicBoolean(false);

    private static volatile long lastUpdatedMs = 0L;

    static {
        UNITS_PER_USD.put("USD", 1.0);
        UNITS_PER_USD.put("USDT", 1.0);
        UNITS_PER_USD.put("USDC", 1.0);
    }

    private PengramCurrencyRates() {
    }

    public interface RefreshCallback {
        void onResult(boolean success);
    }

    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    private static void notifyListenersOnUi() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable r : LISTENERS) {
                try {
                    r.run();
                } catch (Throwable ignore) {
                }
            }
        });
    }

    public static boolean hasRates() {
        return lastUpdatedMs > 0L && UNITS_PER_USD.size() > 5;
    }

    public static boolean isUpdating() {
        return FETCH_IN_FLIGHT.get();
    }

    public static long getLastUpdatedMs() {
        return lastUpdatedMs;
    }

    public static Double getUnitsPerUsd(String code) {
        if (code == null) {
            return null;
        }
        return UNITS_PER_USD.get(code.toUpperCase(Locale.US));
    }

    public static boolean canConvert(String fromCode, String toCode) {
        final Double a = getUnitsPerUsd(fromCode);
        final Double b = getUnitsPerUsd(toCode);
        return a != null && b != null && a > 0.0 && b > 0.0 && !Double.isNaN(a) && !Double.isNaN(b);
    }

    public static double convert(double amount, String fromCode, String toCode) {
        final Double fromRate = getUnitsPerUsd(fromCode);
        final Double toRate = getUnitsPerUsd(toCode);
        if (fromRate == null || toRate == null || fromRate <= 0.0 || toRate <= 0.0) {
            return Double.NaN;
        }
        final double usd = amount / fromRate;
        return usd * toRate;
    }

    /**
     * Асинхронно подгружает кэш с диска и при необходимости обновляет живые котировки.
     */
    public static void ensureFresh(int currentAccount) {
        updateTelegramConfigRates(currentAccount);
        if (!DISK_LOADED.get()) {
            Utilities.globalQueue.postRunnable(() -> {
                loadFromDiskSync();
                if (System.currentTimeMillis() - lastUpdatedMs > TTL_MS || UNITS_PER_USD.size() <= 6) {
                    refreshNow(currentAccount, null);
                }
            });
            return;
        }
        if (System.currentTimeMillis() - lastUpdatedMs > TTL_MS || UNITS_PER_USD.size() <= 6) {
            refreshNow(currentAccount, null);
        }
    }

    public static void refreshNow(int currentAccount, RefreshCallback callback) {
        updateTelegramConfigRates(currentAccount);
        if (!FETCH_IN_FLIGHT.compareAndSet(false, true)) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(hasRates()));
            }
            return;
        }

        // 1. MTProto курсы кошелька Telegram (работают через штатное соединение клиента)
        try {
            ConnectionsManager.getInstance(currentAccount).sendRequestTyped(
                    new TL_wallet.getCurrencyRates(),
                    AndroidUtilities::runOnUIThread,
                    (rates, err) -> {
                        if (err == null && rates != null && rates.rates != null && !rates.rates.isEmpty()) {
                            boolean any = false;
                            for (int i = 0; i < rates.rates.size(); i++) {
                                final TL_wallet.currencyRate r = rates.rates.get(i);
                                if (r != null && !TextUtils.isEmpty(r.currency)
                                        && r.rate > 0.0 && !Double.isNaN(r.rate) && !Double.isInfinite(r.rate)) {
                                    UNITS_PER_USD.put(r.currency.toUpperCase(Locale.US), r.rate);
                                    any = true;
                                }
                            }
                            if (any) {
                                lastUpdatedMs = System.currentTimeMillis();
                                Utilities.globalQueue.postRunnable(PengramCurrencyRates::saveToDiskSync);
                                notifyListenersOnUi();
                            }
                        }
                    });
        } catch (Throwable ignore) {
        }

        // 2. Внешние актуальные источники (мировые валюты + ЦБ РФ + спот крипты)
        Utilities.globalQueue.postRunnable(() -> {
            loadFromDiskSync();
            boolean updatedAny = false;
            try {
                if (fetchOpenErApi()) {
                    updatedAny = true;
                } else if (fetchJsDelivrCurrencyApi()) {
                    updatedAny = true;
                }
                if (fetchCbrDaily()) {
                    updatedAny = true;
                }
                if (fetchBinanceCryptoSpot()) {
                    updatedAny = true;
                }
                UNITS_PER_USD.put("USD", 1.0);
                UNITS_PER_USD.put("USDT", 1.0);
                UNITS_PER_USD.put("USDC", 1.0);
                if (updatedAny) {
                    lastUpdatedMs = System.currentTimeMillis();
                    saveToDiskSync();
                }
            } finally {
                FETCH_IN_FLIGHT.set(false);
                notifyListenersOnUi();
                if (callback != null) {
                    final boolean ok = updatedAny || hasRates();
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(ok));
                }
            }
        });
    }

    private static void updateTelegramConfigRates(int currentAccount) {
        try {
            final MessagesController mc = MessagesController.getInstance(currentAccount);
            if (mc != null) {
                final double tonUsd = mc.config.tonUsdRate.get();
                if (tonUsd > 0.0 && !Double.isNaN(tonUsd) && !Double.isInfinite(tonUsd)) {
                    UNITS_PER_USD.putIfAbsent("TON", 1.0 / tonUsd);
                }
                final float starsCentsPer1000 = mc.starsUsdSellRate1000;
                if (starsCentsPer1000 > 0f) {
                    // starsUsdSellRate1000 — центы США за 1000 звёзд => звёзд за 1 USD = 100_000 / cents
                    UNITS_PER_USD.put("XTR", 100000.0 / starsCentsPer1000);
                } else {
                    UNITS_PER_USD.putIfAbsent("XTR", 50.0);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static boolean fetchOpenErApi() {
        try {
            final String body = httpGet("https://open.er-api.com/v6/latest/USD");
            if (TextUtils.isEmpty(body)) {
                return false;
            }
            final JSONObject root = new JSONObject(body);
            final JSONObject rates = root.optJSONObject("rates");
            if (rates == null) {
                return false;
            }
            int count = 0;
            final Iterator<String> keys = rates.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                final double v = rates.optDouble(k, -1.0);
                if (v > 0.0 && !Double.isNaN(v) && !Double.isInfinite(v)) {
                    UNITS_PER_USD.put(k.toUpperCase(Locale.US), v);
                    count++;
                }
            }
            return count > 10;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean fetchJsDelivrCurrencyApi() {
        try {
            final String body = httpGet("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json");
            if (TextUtils.isEmpty(body)) {
                return false;
            }
            final JSONObject root = new JSONObject(body);
            final JSONObject usd = root.optJSONObject("usd");
            if (usd == null) {
                return false;
            }
            int count = 0;
            final Iterator<String> keys = usd.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                final double v = usd.optDouble(k, -1.0);
                if (v > 0.0 && !Double.isNaN(v) && !Double.isInfinite(v)) {
                    UNITS_PER_USD.put(k.toUpperCase(Locale.US), v);
                    count++;
                }
            }
            return count > 10;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean fetchCbrDaily() {
        try {
            final String body = httpGet("https://www.cbr-xml-daily.ru/daily_json.js");
            if (TextUtils.isEmpty(body)) {
                return false;
            }
            final JSONObject root = new JSONObject(body);
            final JSONObject valute = root.optJSONObject("Valute");
            if (valute == null) {
                return false;
            }
            final JSONObject usdObj = valute.optJSONObject("USD");
            if (usdObj == null) {
                return false;
            }
            final double rubPerUsd = usdObj.optDouble("Value", -1.0) / Math.max(1.0, usdObj.optDouble("Nominal", 1.0));
            if (rubPerUsd <= 0.0 || Double.isNaN(rubPerUsd)) {
                return false;
            }
            UNITS_PER_USD.put("RUB", rubPerUsd);
            final Iterator<String> keys = valute.keys();
            while (keys.hasNext()) {
                final String code = keys.next();
                final JSONObject item = valute.optJSONObject(code);
                if (item == null) {
                    continue;
                }
                final double val = item.optDouble("Value", -1.0);
                final double nom = Math.max(1.0, item.optDouble("Nominal", 1.0));
                if (val > 0.0) {
                    final double rubPerOne = val / nom;
                    final double unitsPerUsd = rubPerUsd / rubPerOne;
                    if (unitsPerUsd > 0.0 && !Double.isNaN(unitsPerUsd) && !Double.isInfinite(unitsPerUsd)) {
                        UNITS_PER_USD.put(code.toUpperCase(Locale.US), unitsPerUsd);
                    }
                }
            }
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean fetchBinanceCryptoSpot() {
        try {
            final String url = "https://api.binance.com/api/v3/ticker/price?symbols="
                    + "%5B%22BTCUSDT%22,%22ETHUSDT%22,%22TONUSDT%22,%22SOLUSDT%22,"
                    + "%22BNBUSDT%22,%22XRPUSDT%22,%22DOGEUSDT%22,%22LTCUSDT%22,%22TRXUSDT%22%5D";
            final String body = httpGet(url);
            if (TextUtils.isEmpty(body)) {
                return false;
            }
            final JSONArray arr = new JSONArray(body);
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                final String sym = obj.optString("symbol", "");
                final double priceUsd = Double.parseDouble(obj.optString("price", "-1"));
                if (sym.endsWith("USDT") && sym.length() > 4 && priceUsd > 0.0) {
                    final String coin = sym.substring(0, sym.length() - 4).toUpperCase(Locale.US);
                    UNITS_PER_USD.put(coin, 1.0 / priceUsd);
                    count++;
                }
            }
            return count > 0;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Pengram)");
            final int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            final InputStream in = conn.getInputStream();
            final StringBuilder sb = new StringBuilder(4096);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[2048];
                int n;
                while ((n = br.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 512 * 1024) {
                        break;
                    }
                }
            }
            return sb.toString();
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static synchronized void loadFromDiskSync() {
        if (DISK_LOADED.get()) {
            return;
        }
        try {
            final Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return;
            }
            final SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            final String raw = prefs.getString(KEY_RATES_JSON, null);
            final long ts = prefs.getLong(KEY_UPDATED_AT, 0L);
            if (!TextUtils.isEmpty(raw)) {
                final JSONObject obj = new JSONObject(raw);
                final Iterator<String> it = obj.keys();
                int loaded = 0;
                while (it.hasNext()) {
                    final String k = it.next();
                    final double v = obj.optDouble(k, -1.0);
                    if (v > 0.0 && !Double.isNaN(v) && !Double.isInfinite(v)) {
                        UNITS_PER_USD.putIfAbsent(k.toUpperCase(Locale.US), v);
                        loaded++;
                    }
                }
                if (loaded > 0 && lastUpdatedMs == 0L) {
                    lastUpdatedMs = ts;
                }
            }
            DISK_LOADED.set(true);
        } catch (Throwable ignore) {
            DISK_LOADED.set(true);
        }
    }

    private static synchronized void saveToDiskSync() {
        try {
            final Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return;
            }
            final JSONObject obj = new JSONObject();
            for (Map.Entry<String, Double> e : UNITS_PER_USD.entrySet()) {
                final Double v = e.getValue();
                if (v != null && v > 0.0 && !Double.isNaN(v) && !Double.isInfinite(v)) {
                    obj.put(e.getKey(), v.doubleValue());
                }
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_RATES_JSON, obj.toString())
                    .putLong(KEY_UPDATED_AT, lastUpdatedMs)
                    .apply();
        } catch (Throwable ignore) {
        }
    }

    public static String symbolFor(String code) {
        if (code == null) {
            return "";
        }
        switch (code.toUpperCase(Locale.US)) {
            case "RUB": return "₽";
            case "USD": return "$";
            case "EUR": return "€";
            case "GBP": return "£";
            case "UAH": return "₴";
            case "KZT": return "₸";
            case "BYN": return "Br";
            case "TRY": return "₺";
            case "CNY":
            case "JPY": return "¥";
            case "GEL": return "₾";
            case "AMD": return "֏";
            case "AZN": return "₼";
            case "THB": return "฿";
            case "VND": return "₫";
            case "KRW": return "₩";
            case "INR": return "₹";
            case "PLN": return "zł";
            case "ILS": return "₪";
            case "BTC": return "₿";
            case "XTR": return "★";
            default: return code.toUpperCase(Locale.US);
        }
    }

    public static String displayLabelForPref(String code) {
        if (code == null || "AUTO".equalsIgnoreCase(code)) {
            return LocaleController.getString(org.telegram.messenger.R.string.PengramCalcCurrencyAuto)
                    + " (" + PengramConfig.resolveCalcDefaultCurrency() + ")";
        }
        final String up = code.toUpperCase(Locale.US);
        final String sym = symbolFor(up);
        return sym.equals(up) ? up : (up + " (" + sym + ")");
    }

    /**
     * Форматирует денежную/крипто сумму аккуратно и читаемо:
     * 14 475 ₽, 51,82 $, 0,001542 BTC.
     */
    public static String formatAmountWithCurrency(double value, String code) {
        final String up = code == null ? "" : code.toUpperCase(Locale.US);
        final String num = formatSmartNumber(value, isCrypto(up));
        final String sym = symbolFor(up);
        if (sym.length() == 1 && "$€£¥₽₴₸₺₾֏₼฿₫₩₹₪★".indexOf(sym.charAt(0)) >= 0) {
            return num + " " + sym;
        }
        return num + " " + up;
    }

    public static boolean isCrypto(String code) {
        if (code == null) {
            return false;
        }
        switch (code.toUpperCase(Locale.US)) {
            case "BTC":
            case "ETH":
            case "TON":
            case "SOL":
            case "BNB":
            case "XRP":
            case "DOGE":
            case "LTC":
            case "TRX":
                return true;
            default:
                return false;
        }
    }

    public static String formatSmartNumber(double value, boolean highPrecisionSmall) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "—";
        }
        final double abs = Math.abs(value);
        final DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        final boolean ruComma = isCommaDecimalLocale();
        sym.setDecimalSeparator(ruComma ? ',' : '.');
        sym.setGroupingSeparator(' ');

        final String pattern;
        if (abs == 0.0) {
            return "0";
        } else if (abs >= 1000.0 && !highPrecisionSmall) {
            pattern = Math.abs(value - Math.rint(value)) < 0.005 ? "#,##0" : "#,##0.##";
        } else if (abs >= 1.0) {
            pattern = highPrecisionSmall ? "#,##0.####" : "#,##0.##";
        } else if (abs >= 0.01) {
            pattern = "0.####";
        } else if (abs >= 0.0001) {
            pattern = "0.######";
        } else {
            pattern = "0.########";
        }
        final DecimalFormat df = new DecimalFormat(pattern, sym);
        return df.format(value);
    }

    private static boolean isCommaDecimalLocale() {
        try {
            final Locale loc = LocaleController.getInstance().getCurrentLocale();
            if (loc != null) {
                final String lang = loc.getLanguage();
                return "ru".equals(lang) || "uk".equals(lang) || "be".equals(lang)
                        || "kk".equals(lang) || "de".equals(lang) || "fr".equals(lang)
                        || "es".equals(lang) || "it".equals(lang) || "nl".equals(lang)
                        || "pl".equals(lang) || "tr".equals(lang);
            }
        } catch (Throwable ignore) {
        }
        return true;
    }
}
