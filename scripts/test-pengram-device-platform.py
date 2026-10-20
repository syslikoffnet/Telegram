#!/usr/bin/env python3
"""Подмена устройства: то, что реально уходит серверу, и своё значение.

Требование: собеседник/сервер не должны узнавать Android, если заявлен Mac,
iPhone или Windows. Значит подменяется не только `device_model`, но и версия
системы, формат версии приложения, платформа (`lang_pack`) и android-отпечатки
в параметрах initConnection. Плюс должна оставаться возможность ввести ЛЮБОЕ
своё имя — включая последнюю строку длинного списка, до которой раньше было
не добраться (шторка обрезалась по нижней границе).
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'
jni = root / 'TMessagesProj/jni'

device = (java / 'org/telegram/messenger/PengramDeviceName.java').read_text()
connections = (java / 'org/telegram/tgnet/ConnectionsManager.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
sheet = (java / 'org/telegram/ui/Components/PengramDeviceNameSheet.java').read_text()
sheet_list = (java / 'org/telegram/ui/Components/PengramSheetList.java').read_text()
wrapper = (jni / 'TgNetWrapper.cpp').read_text()
native_cpp = (jni / 'tgnet/ConnectionsManager.cpp').read_text()
native_h = (jni / 'tgnet/ConnectionsManager.h').read_text()
en = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()
ru = (root / 'TMessagesProj/src/main/res/values-ru/strings.xml').read_text()

# --- идентичность целиком, а не только модель --------------------------------
assert 'public static String resolveLangPack()' in device
assert 'public static boolean isDesktopSpoof()' in device
assert 'public static String resolveAppVersion(String real)' in device
for value in ('"ios"', '"macos"', '"windows"', '"linux"', '"web"', '"android"'):
    assert value in device, value
# iPhone и iPad — один lang_pack, различает модель
assert 'case KIND_IPHONE:' in device and 'case KIND_IPAD:' in device
# версия приложения без номера сборки Android
assert 'real.indexOf(" (");' in device
# в настройках видно и модель, и платформу
assert 'public static String serverIdentity()' in device
assert 'PengramDeviceName.serverIdentity()' in settings

# --- всё это уходит в соединение ---------------------------------------------
assert 'org.telegram.messenger.PengramDeviceName.resolveAppVersion(' in connections
assert 'native_pengramSetPlatform(' in connections
assert 'public static native void native_pengramSetPlatform(int currentAccount, String langPack, boolean desktopSpoof);' in connections
assert 'org.telegram.messenger.PengramDeviceName.resolveLangPack()' in connections
assert 'org.telegram.messenger.PengramDeviceName.isDesktopSpoof()' in connections
# вызов идёт после native_init, иначе первый запрос уйдёт со старой платформой
init_call = connections.index('init(SharedConfig.buildVersion()')
platform_call = connections.index('native_pengramSetPlatform(currentAccount')
assert platform_call > init_call

# --- нативная часть ----------------------------------------------------------
assert 'std::string currentLangPack = "android";' in native_h
assert 'bool pengramDesktopSpoof = false;' in native_h
assert 'void pengramSetPlatform(std::string langPack, bool desktopSpoof);' in native_h
assert 'void ConnectionsManager::pengramSetPlatform(std::string langPack, bool desktopSpoof)' in native_cpp
assert 'request->lang_pack = currentLangPack.empty() ? "android" : currentLangPack;' in native_cpp
assert 'if (!pengramDesktopSpoof) {' in native_cpp
# android-отпечатки не отправляются при подмене на другое устройство
for key in ('"data"', '"installer"', '"package_id"', '"perf_cat"'):
    assert key in native_cpp, key
assert 'if (!pengramDesktopSpoof && currentPerformanceClass != -1)' in native_cpp
# десинхронизация первого пакета заявлена в таблице JNI
assert '{"native_pengramSetPlatform", "(ILjava/lang/String;Z)V", (void *) pengramSetPlatform},' in wrapper
assert 'void pengramSetPlatform(JNIEnv *env, jclass c, jint instanceNum, jstring langPack, jboolean desktopSpoof)' in wrapper

# --- произвольное значение действительно вводится ----------------------------
# список моделей и версий прокручивается, иначе последняя строка («Своё») недоступна
assert 'class PengramSheetList extends ScrollView' in sheet_list
assert 'MeasureSpec.AT_MOST' in sheet_list
assert 'PengramSheetList' in sheet and sheet.count('PengramSheetList') >= 2
assert 'PengramSheetList' in settings
assert 'sheetList.addRow(cells[a], 0);' in settings
# длинный список моделей больше не обрезает вторую строку (бейдж устройства)
assert 'LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50)' not in settings.split('private void showChoicePicker(CharSequence title, CharSequence[] options, CharSequence[] subtitles,')[1][:4000]
# своё имя открывается и долгим нажатием на строку модели
assert 'private void showDeviceCustomDialog()' in settings
assert 'if (item.id == BTN_DEVICE_MODEL) {' in settings
assert 'showDeviceCustomDialog();' in settings
assert 'PengramDeviceName.sanitize(text)' in settings
assert 'PengramDeviceName.setEnabled(true)' in settings
# и до входа: та же шторка со «Своим» в конце
assert 'PengramDeviceCustom' in sheet
assert 'PengramDeviceName.setEnabled(true);' in sheet

# --- список моделей включает iPhone -------------------------------------------
assert '"Apple iPhone 15 Pro"' in device
assert 'R.string.PengramDeviceIphone15Pro' in device
for xml, name in ((en, 'EN'), (ru, 'RU')):
    assert 'name="PengramDeviceIphone15Pro"' in xml, name

print('device spoof: model + system version + app version + lang_pack + params, custom input: OK')
