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
import java.lang.ref.WeakReference;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *   <li>Рыночные TON/USD и прямые TON/фиат котировки CoinGecko, CoinPaprika и CoinCap;
 *       спотовый Binance используется как дополнительный TON/USD резерв.</li>
 *   <li>Спотовые крипто-котировки {@code api.binance.com} (BTC, ETH, TON, SOL, BNB, XRP, DOGE, LTC, TRX).</li>
 * </ol>
 * Все сетевые и дисковые операции выполняются строго вне UI-потока.
 */
public final class PengramCurrencyRates {

    private static final String PREFS_NAME = "pengram_currency_rates";
    private static final String KEY_RATES_JSON = "rates_json";
    private static final String KEY_UPDATED_AT = "updated_at_ms";
    private static final String KEY_TON_MARKET_UPDATED_AT = "ton_market_updated_at_ms";
    private static final String KEY_TON_FIAT_UPDATED_AT = "ton_fiat_updated_at_ms";
    private static final String KEY_TON_FIAT_RATES = "ton_fiat_rates_json";

    /** Автообновление каждые 15 минут. */
    private static final long TTL_MS = 15L * 60L * 1000L;
    private static final int HTTP_TIMEOUT_MS = 4500;
    private static final long MAX_TON_MARKET_AGE_MS = 30L * 60L * 1000L;

    private static final ConcurrentHashMap<String, Double> UNITS_PER_USD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> TON_TO_FIAT = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<WeakReference<Runnable>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean DISK_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean FETCH_IN_FLIGHT = new AtomicBoolean(false);

    private static volatile long lastUpdatedMs = 0L;
    private static volatile long tonMarketUpdatedMs = 0L;
    private static volatile long tonFiatUpdatedMs = 0L;

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
        if (listener == null) {
            return;
        }
        for (WeakReference<Runnable> ref : LISTENERS) {
            final Runnable cur = ref.get();
            if (cur == null) {
                LISTENERS.remove(ref);
            } else if (cur == listener) {
                return;
            }
        }
        LISTENERS.add(new WeakReference<>(listener));
    }

    public static void removeListener(Runnable listener) {
        if (listener == null) {
            return;
        }
        for (WeakReference<Runnable> ref : LISTENERS) {
            final Runnable cur = ref.get();
            if (cur == null || cur == listener) {
                LISTENERS.remove(ref);
            }
        }
    }

    private static void notifyListenersOnUi() {
        AndroidUtilities.runOnUIThread(() -> {
            for (WeakReference<Runnable> ref : LISTENERS) {
                final Runnable r = ref.get();
                if (r == null) {
                    LISTENERS.remove(ref);
                    continue;
                }
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
        final String from = fromCode == null ? "" : fromCode.toUpperCase(Locale.US);
        final String to = toCode == null ? "" : toCode.toUpperCase(Locale.US);
        if (isFreshTonFiatRate()) {
            if ("TON".equals(from) && TON_TO_FIAT.containsKey(to)) {
                return amount * TON_TO_FIAT.get(to);
            }
            if ("TON".equals(to) && TON_TO_FIAT.containsKey(from)) {
                final double rate = TON_TO_FIAT.get(from);
                return rate > 0.0 ? amount / rate : Double.NaN;
            }
        }
        final Double fromRate = getUnitsPerUsd(from);
        final Double toRate = getUnitsPerUsd(to);
        if (fromRate == null || toRate == null || fromRate <= 0.0 || toRate <= 0.0) {
            return Double.NaN;
        }
        final double usd = amount / fromRate;
        return usd * toRate;
    }

    /** Свежая рыночная цена TON в выбранной фиатной валюте; не использует старую оценку Wallet. */
    public static double getFreshTonRate(String fiatCode) {
        if (!isFreshTonMarketRate() || TextUtils.isEmpty(fiatCode)) return Double.NaN;
        final Double direct = isFreshTonFiatRate() ? TON_TO_FIAT.get(fiatCode.toUpperCase(Locale.US)) : null;
        if (direct != null && direct > 0.0 && !Double.isNaN(direct) && !Double.isInfinite(direct)) return direct;
        return convert(1.0, "TON", fiatCode);
    }

    private static boolean isFreshTonMarketRate() {
        final long updated = tonMarketUpdatedMs;
        return updated > 0L && System.currentTimeMillis() - updated <= MAX_TON_MARKET_AGE_MS;
    }

    private static boolean isFreshTonFiatRate() {
        final long updated = tonFiatUpdatedMs;
        return updated > 0L && System.currentTimeMillis() - updated <= MAX_TON_MARKET_AGE_MS;
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
                                        && !"TON".equalsIgnoreCase(r.currency)
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
                final boolean directTonRates = fetchCoinGeckoTon();
                if (directTonRates) {
                    updatedAny = true;
                } else if (fetchCoinPaprikaTon()) {
                    updatedAny = true;
                }
                if (fetchBinanceCryptoSpot()) {
                    updatedAny = true;
                }
                if (!directTonRates && !isFreshTonFiatRate() && fetchCoinCapTon()) {
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
                if (tonUsd > 0.0 && !Double.isNaN(tonUsd) && !Double.isInfinite(tonUsd)
                        && !isFreshTonMarketRate()) {
                    // Telegram's conversion is a fallback only; a fresh market quote wins.
                    UNITS_PER_USD.put("TON", 1.0 / tonUsd);
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

    private static boolean fetchCoinGeckoTon() {
        try {
            final String body = httpGet("https://api.coingecko.com/api/v3/simple/price?ids=the-open-network&vs_currencies=usd,rub,eur,gbp");
            if (TextUtils.isEmpty(body)) return false;
            final JSONObject coin = new JSONObject(body).optJSONObject("the-open-network");
            if (coin == null) return false;
            final double usd = coin.optDouble("usd", -1.0);
            if (usd <= 0.0 || Double.isNaN(usd) || Double.isInfinite(usd)) return false;
            UNITS_PER_USD.put("TON", 1.0 / usd);
            TON_TO_FIAT.clear();
            int count = putTonFiatRate("USD", usd) ? 1 : 0;
            count += putTonFiatRate("RUB", coin.optDouble("rub", -1.0)) ? 1 : 0;
            count += putTonFiatRate("EUR", coin.optDouble("eur", -1.0)) ? 1 : 0;
            count += putTonFiatRate("GBP", coin.optDouble("gbp", -1.0)) ? 1 : 0;
            if (count > 0) {
                tonMarketUpdatedMs = System.currentTimeMillis();
                tonFiatUpdatedMs = tonMarketUpdatedMs;
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean fetchCoinPaprikaTon() {
        try {
            final String body = httpGet("https://api.coinpaprika.com/v1/tickers/ton-toncoin?quotes=USD,RUB,EUR,GBP");
            if (TextUtils.isEmpty(body)) return false;
            final JSONObject quotes = new JSONObject(body).optJSONObject("quotes");
            if (quotes == null) return false;
            final HashMap<String, Double> freshQuotes = new HashMap<>();
            for (String code : new String[]{"USD", "RUB", "EUR", "GBP"}) {
                final JSONObject quote = quotes.optJSONObject(code);
                final double price = quote == null ? -1.0 : quote.optDouble("price", -1.0);
                if (price > 0.0 && !Double.isNaN(price) && !Double.isInfinite(price)) freshQuotes.put(code, price);
            }
            final Double usd = freshQuotes.get("USD");
            if (usd != null && usd > 0.0) {
                UNITS_PER_USD.put("TON", 1.0 / usd);
                TON_TO_FIAT.clear();
                TON_TO_FIAT.putAll(freshQuotes);
                tonMarketUpdatedMs = System.currentTimeMillis();
                tonFiatUpdatedMs = tonMarketUpdatedMs;
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean fetchCoinCapTon() {
        try {
            final String body = httpGet("https://api.coincap.io/v2/assets/toncoin");
            if (TextUtils.isEmpty(body)) return false;
            final JSONObject data = new JSONObject(body).optJSONObject("data");
            if (data == null) return false;
            final double usd = Double.parseDouble(data.optString("priceUsd", "-1"));
            if (usd <= 0.0 || Double.isNaN(usd) || Double.isInfinite(usd)) return false;
            UNITS_PER_USD.put("TON", 1.0 / usd);
            putTonFiatRate("USD", usd);
            tonMarketUpdatedMs = System.currentTimeMillis();
            return true;
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean putTonFiatRate(String code, double rate) {
        if (TextUtils.isEmpty(code) || rate <= 0.0 || Double.isNaN(rate) || Double.isInfinite(rate)) return false;
        TON_TO_FIAT.put(code.toUpperCase(Locale.US), rate);
        return true;
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
                    if ("TON".equals(coin)) tonMarketUpdatedMs = System.currentTimeMillis();
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
            tonMarketUpdatedMs = prefs.getLong(KEY_TON_MARKET_UPDATED_AT, 0L);
            tonFiatUpdatedMs = prefs.getLong(KEY_TON_FIAT_UPDATED_AT, 0L);
            final String tonRaw = prefs.getString(KEY_TON_FIAT_RATES, null);
            if (!TextUtils.isEmpty(tonRaw)) {
                final JSONObject tonObj = new JSONObject(tonRaw);
                final Iterator<String> tonKeys = tonObj.keys();
                while (tonKeys.hasNext()) {
                    final String code = tonKeys.next().toUpperCase(Locale.US);
                    final double rate = tonObj.optDouble(code, -1.0);
                    if (rate > 0.0 && !Double.isNaN(rate) && !Double.isInfinite(rate)) TON_TO_FIAT.put(code, rate);
                }
            }
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
            final JSONObject tonObj = new JSONObject();
            for (Map.Entry<String, Double> e : TON_TO_FIAT.entrySet()) {
                final Double v = e.getValue();
                if (v != null && v > 0.0 && !Double.isNaN(v) && !Double.isInfinite(v)) tonObj.put(e.getKey(), v);
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_RATES_JSON, obj.toString())
                    .putString(KEY_TON_FIAT_RATES, tonObj.toString())
                    .putLong(KEY_TON_MARKET_UPDATED_AT, tonMarketUpdatedMs)
                    .putLong(KEY_TON_FIAT_UPDATED_AT, tonFiatUpdatedMs)
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
