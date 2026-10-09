/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service {

    /** идентификатор тихого уведомления фонового режима Pengram */
    private static final int PENGRAM_NOTIFICATION_ID = 38201;
    /** каналы разводим по важности: сменить важность у готового канала система не даёт */
    private static final String CHANNEL_QUIET = "pengram_background_quiet";
    private static final String CHANNEL_NORMAL = "pengram_background";
    private boolean foregroundStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Начиная с Android 8 обычный сервис живёт считанные секунды, поэтому
        // «работать в фоне» без foreground — пустое обещание. Поднимаем себя в
        // передний план с минимальным уведомлением; «тихий значок» прячет его в
        // самый низ шторки (IMPORTANCE_MIN), а выключенный — оставляет обычным.
        startPengramForeground();
        return START_STICKY;
    }

    private void startPengramForeground() {
        try {
            // Every startForegroundService MUST call startForeground, including
            // Telegram's per-account keep-alive when Pengram mode is off.
            final boolean quiet = PengramConfig.isBackgroundMode() && PengramConfig.isBackgroundSilent();
            final String channelId = quiet ? CHANNEL_QUIET : CHANNEL_NORMAL;
            if (Build.VERSION.SDK_INT >= 26) {
                final NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null && manager.getNotificationChannel(channelId) == null) {
                    final NotificationChannel channel = new NotificationChannel(channelId,
                            LocaleController.getString(R.string.PengramBackgroundChannel),
                            quiet ? NotificationManager.IMPORTANCE_MIN : NotificationManager.IMPORTANCE_LOW);
                    channel.setShowBadge(false);
                    channel.enableLights(false);
                    channel.enableVibration(false);
                    channel.setSound(null, null);
                    manager.createNotificationChannel(channel);
                }
            }
            final Intent open = new Intent(this, LaunchActivity.class);
            open.setAction("org.pengram.openapp");
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            final PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            // The notification is deliberately visual: no description of the
            // background activity in the lock screen or notification shade.
            final boolean penguinAwake = PengramConfig.isBackgroundMode();
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(PengramNotificationIcon.drawable())
                    .setContentTitle(LocaleController.getString(penguinAwake
                            ? R.string.PengramBackgroundTitle : R.string.PengramBackgroundSleepTitle))
                    .setContentText(LocaleController.getString(penguinAwake
                            ? R.string.PengramBackgroundText : R.string.PengramBackgroundSleepText))
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setSilent(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(quiet ? NotificationCompat.PRIORITY_MIN : NotificationCompat.PRIORITY_LOW);

            final Notification notification = builder.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(PENGRAM_NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(PENGRAM_NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } catch (Throwable e) {
            FileLog.e(e);
            stopSelf(); // never leave a started foreground service unpromoted
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        if (foregroundStarted && ApplicationLoader.shouldStartNotificationService()) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
