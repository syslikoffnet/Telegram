#!/usr/bin/env python3
"""Подмена устройства: версия ОС и видимый бейдж типа устройства.

Требование: подменять не только имя модели, но и версию системы, а в
интерфейсе показывать, что за устройство заявлено (iPhone / Mac / Windows…),
иначе подмена «Samsung» с «SDK 34» выдаёт себя в активных сеансах.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

device = (java / 'org/telegram/messenger/PengramDeviceName.java').read_text()
connections = (java / 'org/telegram/tgnet/ConnectionsManager.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
sheet = (java / 'org/telegram/ui/Components/PengramDeviceNameSheet.java').read_text()
en = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()
ru = (root / 'TMessagesProj/src/main/res/values-ru/strings.xml').read_text()

# --- версия живёт рядом с именем и так же применяется при старте -------------
assert 'public static final String KEY_VERSION_ENABLED = "deviceVersionSpoof";' in device
assert 'public static final String KEY_VERSION_VALUE = "deviceVersionValue";' in device
assert 'public static String resolveSystemVersion()' in device
assert 'return "SDK " + Build.VERSION.SDK_INT;' in device
# подмена версии не работает без подмены имени: иначе «Samsung c iOS»
assert 'if (isEnabled() && isVersionSpoofEnabled())' in device
# и версия, и имя уходят в initConnection из одного места
assert 'org.telegram.messenger.PengramDeviceName.resolveSystemVersion();' in connections
assert 'org.telegram.messenger.PengramDeviceName.resolveDeviceModel();' in connections

# --- бейдж: тип устройства + версия, те же иконки, что в сеансах -------------
assert 'public static int kindOf(String model)' in device
for kind in ('KIND_IPHONE', 'KIND_IPAD', 'KIND_MAC', 'KIND_WINDOWS',
             'KIND_ANDROID', 'KIND_ANDROID_TABLET', 'KIND_LINUX', 'KIND_WEB'):
    assert kind in device, kind
assert 'R.drawable.device_desktop_win' in device
assert 'R.drawable.device_desktop_osx' in device
assert 'R.drawable.device_phone_ios' in device
assert 'R.drawable.device_tablet_android' in device
assert 'public static String badgeSummary()' in device
assert 'public static int labelResForKind(int kind)' in device

# --- настройки: строка версии, переключатель и бейдж в подписи --------------
assert 'private static final int BTN_DEVICE_VERSION = 1037;' in settings
assert 'private void showDeviceVersionPicker()' in settings
assert 'case BTN_DEVICE_VERSION:' in settings
assert 'R.string.PengramDeviceVersion' in settings
assert 'KEY_VERSION_ENABLED' in settings
assert 'PengramDeviceVersionInfo' in settings
assert 'PengramDeviceBadge' in settings
# значок строки модели — заявленный тип устройства, а не безликий «девайс»
assert 'org.telegram.messenger.PengramDeviceName.iconRes()' in settings

# --- до входа в аккаунт бейдж тоже виден ------------------------------------
assert 'PengramDeviceName.kindOf(model)' in sheet
assert 'labelResForKind(kind)' in sheet
assert 'setTextAndText2AndValue' in sheet

# --- строки на двух языках ---------------------------------------------------
for key in ('PengramDeviceVersion', 'PengramDeviceVersionSpoof',
            'PengramDeviceVersionInfo', 'PengramDeviceVersionReal',
            'PengramDeviceVersionCustomTitle', 'PengramDeviceVersionCustomHint',
            'PengramDeviceBadge', 'PengramDeviceKindAndroid',
            'PengramDeviceKindAndroidTablet', 'PengramDeviceKindIphone',
            'PengramDeviceKindIpad', 'PengramDeviceKindMac',
            'PengramDeviceKindWindows', 'PengramDeviceKindLinux',
            'PengramDeviceKindWeb'):
    assert 'name="%s"' % key in en, 'EN missing ' + key
    assert 'name="%s"' % key in ru, 'RU missing ' + key

print('device spoof: OS version + device-type badge: OK')
