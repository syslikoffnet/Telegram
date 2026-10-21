package org.telegram.messenger;

/** Pure account/scope policy, shared by all four profile observers before persistence. */
public final class PengramProfilePolicy {
    private PengramProfilePolicy() {}

    public static boolean allows(int scope, boolean enabled, boolean accountOwned,
                                 boolean fromCache, boolean self, boolean bot,
                                 boolean deleted, boolean support, boolean service,
                                 boolean tracked, boolean contact, boolean inDialogs) {
        if (!enabled || !accountOwned || fromCache || self || bot || deleted || support || service)
            return false;
        switch (scope) {
            case 0: return tracked;                         // manual
            case 1: return tracked || inDialogs;            // interacted
            case 2: return contact || inDialogs;            // contacts/chats
            case 3: return true;                            // encountered
            default: return false;                           // unknown scope: fail closed
        }
    }

    public static boolean queueToCloud(int storage, boolean allowed) {
        return allowed && (storage == 1 || storage == 2);
    }
}
