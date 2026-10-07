package org.telegram.messenger;

/** Small monochrome Android status-bar icon shared by message and keep-alive notifications. */
public final class PengramNotificationIcon {
    public static final String KEY = "notificationIconStyle";
    public static final int COUNT = 5;
    private static final int[] DRAWABLES = {
            R.drawable.pengram_notify_plane,
            R.drawable.pengram_notify_penguin,
            R.drawable.pengram_notify_bubble,
            R.drawable.pengram_notify_bell,
            R.drawable.pengram_notify_star
    };

    private PengramNotificationIcon() {}

    public static int drawable() {
        int style = PengramConfig.getIntCached(KEY, 0);
        return DRAWABLES[Math.max(0, Math.min(COUNT - 1, style))];
    }

    public static void setStyle(int style) {
        PengramConfig.setIntValue(KEY, Math.max(0, Math.min(COUNT - 1, style)));
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                NotificationsController.getInstance(account).showNotifications();
            }
        }
        // Also refresh the persistent background-service icon, if active.
        if (ApplicationLoader.applicationContext != null && ApplicationLoader.shouldStartNotificationService()) {
            ApplicationLoader.startPushService();
        }
    }
}
