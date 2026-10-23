package org.telegram.messenger;

/**
 * Pengram: локальная копия аккаунта.
 *
 * <p>Telegram — клиент без собственного архива: если сервер сказал «чат удалён»,
 * «вы больше не участник» или «сессия отозвана», клиент стирает локальные
 * данные. В результате пропадает всё, что человек уже видел и скачал, хотя
 * эти данные лежали на телефоне и никуда не девались.
 *
 * <p>С включённой локальной копией деструктивные действия не выполняются:
 * <ul>
 *   <li>чат, из которого вас убрали или который стал недоступен, остаётся в
 *       списке — открывается только чтение, история и медиа на месте;</li>
 *   <li>сообщения, удалённые на сервере, остаются в переписке (и в кэше, и в
 *       интерфейсе);</li>
 *   <li>отозванная сессия не приводит к выходу из аккаунта: сеть для этого
 *       аккаунта выключается, но профиль, чаты и файлы остаются доступны.</li>
 * </ul>
 *
 * <p>Это осознанно локальное поведение: ничего не отправляется на сервер и не
 * запрашивается заново, поэтому функция не может привести к бану. Выключить
 * можно в любой момент — новые удаления снова будут применяться, а уже
 * сохранённое останется, пока его не удалить вручную.
 */
public final class PengramLocalKeep {

    /** включена ли локальная копия (по умолчанию — да: ничего не должно исчезать) */
    public static final String KEY_ENABLED = "localKeep";

    /** аккаунты, у которых сессия отозвана и сеть выключена */
    private static final java.util.HashSet<Integer> localOnly = new java.util.HashSet<>();

    private PengramLocalKeep() {
    }

    public static boolean isEnabled() {
        return PengramConfig.getBool(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean value) {
        PengramConfig.setBool(KEY_ENABLED, value);
    }

    /** чат остаётся в списке, даже если сервер считает, что мы не участники */
    public static boolean keepDialog() {
        return isEnabled();
    }

    /** удалённые на сервере сообщения остаются в переписке */
    public static boolean keepDeletedMessages() {
        return isEnabled();
    }

    /**
     * Сессия отозвана. Возвращает true, если аккаунт надо оставить локальной
     * копией: пользователь остаётся «в» аккаунте, читает кэш, но сеть для него
     * выключена — иначе каждый запрос падал бы с 401 и засыпал интерфейс
     * ошибками.
     */
    public static boolean keepRevokedAccount(int account) {
        if (!isEnabled()) {
            return false;
        }
        localOnly.add(account);
        try {
            org.telegram.tgnet.ConnectionsManager.native_pauseNetwork(account);
        } catch (Throwable ignore) {
        }
        return true;
    }

    /** аккаунт работает только с локальными данными */
    public static boolean isLocalOnly(int account) {
        return localOnly.contains(account);
    }
}
