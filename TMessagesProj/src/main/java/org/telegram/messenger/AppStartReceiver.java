/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AppStartReceiver extends BroadcastReceiver {

    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        // NotificationsService сам просит перезапуск после гибели, а после
        // обновления APK сервис не живёт вовсе. Без этих действий страховка
        // от неработающего FCM держалась бы только до первого kills.
        final boolean restart = "org.telegram.start".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (boot || restart) {
            // onReceive уже на главном потоке. postInitApplication обязателен:
            // до него UserConfig не знает активных слотов, и решение «нужен ли
            // сервис» принималось бы вслепую — только по наличию FCM-токена.
            try {
                ApplicationLoader.postInitApplication();
            } catch (Throwable e) {
                // сервис всё равно попробуем поднять: его onCreate повторит
                // инициализацию, а падать на загрузке телефона нельзя
                FileLog.e(e);
            }
            SharedConfig.loadConfig();
            if (boot && SharedConfig.passcodeHash.length() > 0) {
                SharedConfig.appLocked = true;
                SharedConfig.saveConfig();
            }
            ApplicationLoader.startPushService();
        }
    }
}
