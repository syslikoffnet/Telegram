package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/**
 * Pengram: пользовательский порядок аккаунтов.
 *
 * Раньше каждый список аккаунтов сортировался по {@link UserConfig#loginTime},
 * поэтому переставленные вручную аккаунты возвращались на место при каждом
 * открытии меню. Порядок хранится одной CSV-строкой слотов в
 * {@link PengramConfig} и применяется всеми списками: настройки, переключатель
 * на главном экране, селектор в списке чатов, профиль, OAuth и Wear.
 *
 * Строка может содержать слоты уже вышедших аккаунтов — так аккаунт,
 * который войдёт в тот же слот снова, вернётся на своё место.
 */
public final class PengramAccountOrder {

    private PengramAccountOrder() {
    }

    /** Прочитать сохранённый порядок без проверки активности аккаунтов. */
    private static ArrayList<Integer> stored() {
        final ArrayList<Integer> order = new ArrayList<>();
        final String raw;
        try {
            raw = PengramConfig.getStringValue(PengramConfig.KEY_ACCOUNT_ORDER, "");
        } catch (Throwable ignore) {
            return order;
        }
        if (raw == null || raw.isEmpty()) {
            return order;
        }
        for (String part : raw.split(",")) {
            final int account;
            try {
                account = Integer.parseInt(part.trim());
            } catch (NumberFormatException ignore) {
                continue;
            }
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || order.contains(account)) {
                continue;
            }
            order.add(account);
        }
        return order;
    }

    private static void store(ArrayList<Integer> order) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(order.get(i));
        }
        PengramConfig.setStringValue(PengramConfig.KEY_ACCOUNT_ORDER, out.toString());
    }

    private static int compareLoginTime(int a, int b) {
        final long l1 = UserConfig.getInstance(a).loginTime;
        final long l2 = UserConfig.getInstance(b).loginTime;
        if (l1 != l2) {
            return l1 < l2 ? -1 : 1;
        }
        return Integer.compare(a, b);
    }

    /**
     * Все активные аккаунты в пользовательском порядке. Аккаунты, о которых
     * сохранённый порядок ещё не знает (первый запуск, только что добавленный),
     * идут в конец по времени входа — как раньше.
     */
    public static ArrayList<Integer> activeOrder() {
        final ArrayList<Integer> result = new ArrayList<>();
        for (int account : stored()) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                result.add(account);
            }
        }
        final ArrayList<Integer> unknown = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && !result.contains(a)) {
                unknown.add(a);
            }
        }
        // стабильная сортировка по времени входа; пустой хвост не трогаем
        Collections.sort(unknown, PengramAccountOrder::compareLoginTime);
        result.addAll(unknown);
        return result;
    }

    /**
     * Пересортировать готовый список аккаунтов (с любым фильтром — например,
     * «все, кроме текущего») по общему порядку.
     */
    public static void sort(ArrayList<Integer> accounts) {
        if (accounts == null || accounts.size() < 2) {
            return;
        }
        final ArrayList<Integer> order = activeOrder();
        Collections.sort(accounts, (a, b) -> {
            final int ra = order.indexOf(a);
            final int rb = order.indexOf(b);
            if (ra < 0 && rb < 0) {
                return compareLoginTime(a, b);
            }
            if (ra < 0) {
                return 1;
            }
            if (rb < 0) {
                return -1;
            }
            return Integer.compare(ra, rb);
        });
    }

    /**
     * Сохранить новый порядок видимых строк. Скрытые аккаунты (например,
     * текущий, которого нет в списке настроек) остаются на своих местах —
     * перетаскивание не должно двигать то, чего пользователь не видел.
     *
     * @param visible аккаунты в том порядке, в котором они стоят на экране
     */
    public static void saveVisibleOrder(ArrayList<Integer> visible) {
        if (visible == null || visible.isEmpty()) {
            return;
        }
        final ArrayList<Integer> full = activeOrder();
        final HashSet<Integer> visibleSet = new HashSet<>(visible);
        int cursor = 0;
        for (int i = 0; i < full.size() && cursor < visible.size(); i++) {
            if (visibleSet.contains(full.get(i))) {
                full.set(i, visible.get(cursor++));
            }
        }
        // слоты вышедших аккаунтов сохраняем: вернувшийся аккаунт встанет на место
        for (int account : stored()) {
            if (!full.contains(account)) {
                full.add(account);
            }
        }
        store(full);
    }
}
