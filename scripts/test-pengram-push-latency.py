#!/usr/bin/env python3
"""Уведомления не должны опаздывать, когда FCM в сборке не работает.

Сборка подписана собственным ключом и живёт в чужом Firebase-проекте: токен
может не выдаваться вовсе. Тогда сервер не отправляет пуши, и сообщения ждут,
пока система случайно разбудит процесс. Проверяем, что приложение само
замечает отсутствие токена и поднимает своё постоянное соединение, что сервис
переживает перезапуск/обновление и что у него нет 6-часового лимита dataSync.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'
manifest = (root / 'TMessagesProj/src/main/AndroidManifest.xml').read_text()

background = (java / 'org/telegram/messenger/PengramBackgroundService.java').read_text()
loader = (java / 'org/telegram/messenger/ApplicationLoader.java').read_text()
push = (java / 'org/telegram/messenger/PushListenerController.java').read_text()
service = (java / 'org/telegram/messenger/NotificationsService.java').read_text()
receiver = (java / 'org/telegram/messenger/AppStartReceiver.java').read_text()
login = (java / 'org/telegram/ui/LoginActivity.java').read_text()

# --- признак «FCM не может нас разбудить» -----------------------------------
assert 'public static boolean isPushTokenMissing()' in background
assert 'TextUtils.isEmpty(SharedConfig.pushString)' in background
# «получаем токен» — ещё не приговор, иначе сервис поднимался бы на каждом старте
assert 'status.startsWith("__FIREBASE_GENERATING")' in background

# --- авто-включение постоянного соединения ---------------------------------
assert 'public static boolean shouldKeepAlive()' in background
assert """        if (PengramConfig.isBackgroundMode()) {
            return true;
        }
        if (isPushTokenMissing()) {
            return true;
        }""" in background
# сервис и нативное соединение решают одинаково
assert 'return PengramBackgroundService.shouldKeepAlive();' in loader
assert 'final boolean enabled = shouldKeepAlive();' in background

# --- ошибки FCM включают страховку сразу -----------------------------------
assert 'public static void onPushRegistrationResult(boolean success)' in background
assert 'SharedConfig.pushString = "";' in background
assert push.count('PengramBackgroundService.onPushRegistrationResult(false);') == 2
assert 'PengramBackgroundService.onPushRegistrationResult(true);' in push
assert 'PengramBackgroundService.onPushRegistrationResult(false);' in loader   # нет Play Services

# --- после входа сервис поднимается, а не ждёт следующего запуска -----------
assert 'public static void onAccountAuthorized()' in background
assert 'PengramBackgroundService.onAccountAuthorized();' in login

# --- сервис переживает гибель и обновление APK -----------------------------
assert '"org.telegram.start".equals(action)' in receiver
assert 'Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)' in receiver
assert 'android.intent.action.MY_PACKAGE_REPLACED' in manifest
assert 'android.intent.action.QUICKBOOT_POWERON' in manifest

# --- у постоянного соединения нет лимита dataSync ---------------------------
assert 'android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING' in manifest
assert 'android:foregroundServiceType="dataSync|remoteMessaging"' in manifest
assert 'FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING' in service
assert 'FOREGROUND_SERVICE_TYPE_DATA_SYNC' in service
# тихий канал больше не привязан к ручному режиму: страховка не мозолит глаза
assert 'final boolean quiet = PengramConfig.isBackgroundSilent();' in service

print('push latency: missing FCM detected, keep-alive started, service survives restart: OK')
