#!/usr/bin/env python3
"""Подмена имени устройства: настройки, вход и то, что реально уходит серверу.

Ключевое требование: имя можно поставить любым — популярная модель, ноутбук,
компьютер или своя строка, — и оно должно попасть в initConnection, а не просто
лежать в настройках.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent

helper = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramDeviceName.java').read_text()
connections = (root / 'TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java').read_text()
config = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java').read_text()
settings = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java').read_text()
login = (root / 'TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java').read_text()
sheet = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/PengramDeviceNameSheet.java').read_text()
index = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java').read_text()

# --- настоящая подмена, а не мёртвая настройка -------------------------------
assert 'deviceModel = org.telegram.messenger.PengramDeviceName.resolveDeviceModel();' in connections
assert 'public static String resolveDeviceModel()' in helper
assert 'return isEnabled() && !TextUtils.isEmpty(custom) ? custom : ' not in helper
assert 'return custom;' in helper and 'return realDeviceModel();' in helper
# пустое имя или выключенный тумблер = настоящее устройство
assert 'if (isEnabled() && !TextUtils.isEmpty(custom)) {' in helper
# любые готовые варианты: телефоны, ноутбук, компьютер, планшет
for preset in ('Samsung SM-S918B', 'Samsung SM-S928B', 'Google Pixel 8 Pro',
               'Apple MacBook Pro', 'Microsoft Windows PC', 'iPad Pro'):
    assert '"%s"' % preset in helper, preset
assert 'public static String randomPreset()' in helper
# своё название: чистим переводы строк и мусор, ограничиваем длину
assert 'public static String sanitize(String raw)' in helper
assert "c == '\\n' || c == '\\r' || c == '\\t'" in helper
assert 'if (value.length() > 64)' in helper
# имя читается при старте — значит нужен перезапуск, и он есть
assert 'public static void restartApp(Context context)' in helper
assert 'System.exit(0);' in helper

# --- настройки ---------------------------------------------------------------
assert 'PengramDeviceName.KEY_ENABLED' in settings
assert 'BTN_DEVICE_MODEL' in settings and 'BTN_DEVICE_RANDOM' in settings and 'BTN_DEVICE_APPLY' in settings
assert 'showDeviceModelPicker();' in settings
assert 'requestDeviceRestart();' in settings
assert 'PengramDeviceName.setValue(org.telegram.messenger.PengramDeviceName.randomPreset())' in settings
# строки должны быть в индексе поиска
assert 'R.string.PengramDeviceSpoof' in index and 'R.string.PengramDeviceModel' in index

# --- вход до регистрации ------------------------------------------------------
assert 'PengramDeviceNameSheet.show(this, getResourceProvider())' in login
assert 'PengramDeviceName.setEnabled(index != PengramDeviceName.PRESET_REAL);' in sheet
assert 'askCustomName(' in sheet

# хранение строк в общих настройках Pengram (иначе бэкап их не увидит)
assert 'public static String getStringValue(String key, String def)' in config
assert 'public static void setStringValue(String key, String value)' in config

# --- строки в обеих локалях ---------------------------------------------------
for name in ('PengramDeviceHeader', 'PengramDeviceSpoof', 'PengramDeviceSpoofInfo',
             'PengramDeviceModel', 'PengramDeviceRandom', 'PengramDeviceApply',
             'PengramDeviceInfo', 'PengramDeviceReal', 'PengramDeviceCustom',
             'PengramDeviceCustomHint', 'PengramDeviceRestartTitle',
             'PengramDeviceRestartInfo', 'PengramDeviceRestartButton'):
    for res in ('values', 'values-ru'):
        xml = (root / ('TMessagesProj/src/main/res/%s/strings.xml' % res)).read_text()
        assert '<string name="%s">' % name in xml, (name, res)

print('device name spoofing: presets, custom text, login screen and restart: OK')
