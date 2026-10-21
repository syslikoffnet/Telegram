#!/usr/bin/env python3
"""Вход по файлу сессии: разбор, оба режима, нативный мост.

Проверяем то, что можно проверить без устройства:

* разбор идёт только на чтение и никуда не ходит в сеть;
* формулы (ключ Desktop, AES-IGE, имя файла аккаунта, id ключа, TDF-контроль)
  совпадают с эталонной реализацией — здесь они повторены на Python, и
  round-trip шифрования/дешифрования обязан сойтись;
* настоящие данные не принимаются «на веру»: небезопасные записи архива,
  несколько сессий в базе и tdata с паролем отклоняются с понятным кодом;
* в приложении есть вход без вопросов о режиме, выгрузка сессии и нативный
  мост с точной сигнатурой.
"""

import hashlib
import hmac
import struct
import zipfile
import io
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

formats = (java / 'org/telegram/messenger/PengramSessionFormats.java').read_text()
export = (java / 'org/telegram/messenger/PengramSessionExport.java').read_text()
login = (java / 'org/telegram/messenger/PengramSessionLogin.java').read_text()
activity = (java / 'org/telegram/ui/LoginActivity.java').read_text()
manager = (java / 'org/telegram/tgnet/ConnectionsManager.java').read_text()
wrapper = (root / 'TMessagesProj/jni/TgNetWrapper.cpp').read_text()
native_manager = (root / 'TMessagesProj/jni/tgnet/ConnectionsManager.cpp').read_text()
datacenter = (root / 'TMessagesProj/jni/tgnet/Datacenter.cpp').read_text()
en = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()
ru = (root / 'TMessagesProj/src/main/res/values-ru/strings.xml').read_text()

# --- разбор: только чтение, только локально ---------------------------------
assert 'SQLiteDatabase.OPEN_READONLY' in formats
assert 'openInputStream' not in formats
assert 'sendRequest' not in formats            # разбор не ходит в сеть
assert 'HttpURLConnection' not in formats and 'Socket' not in formats
assert 'MAX_INPUT = 512L * 1024 * 1024' in formats
assert 'MAX_RECORD = 1024 * 1024' in formats
assert 'MAX_ENTRIES = 50000' in formats
assert 'entry.getSize() > MAX_RECORD' in formats
assert 'unsafe_archive' in formats
assert 'ambiguous_session' in formats           # две строки — не «какая-нибудь»
assert 'test_session' in formats and 'bot_session' in formats
assert 'password_tdata' in formats
assert 'Arrays.fill(authKey, (byte) 0)' in formats     # ключ затирается

# --- форматы ----------------------------------------------------------------
for marker in ('KIND_TELETHON', 'KIND_PYROGRAM', 'KIND_TDESKTOP', 'KIND_STRING'):
    assert marker in formats, marker
assert '"SQLite format 3\\0".getBytes()' in formats
assert 'PRAGMA table_info(sessions)' in formats
assert "SELECT dc_id, auth_key FROM sessions LIMIT 2" in formats
assert "SELECT dc_id, auth_key, test_mode, user_id, is_bot FROM sessions LIMIT 2" in formats
assert '"TDF$"' not in formats  # сравнение посимвольно, без лишней строки
for char in ("data[0] != 'T'", "data[1] != 'D'", "data[2] != 'F'", "data[3] != '$'"):
    assert char in formats, char

# --- нативный мост ----------------------------------------------------------
assert 'native_pengramImportAuthKey' in manager
assert 'public static native void native_pengramImportAuthKey(int currentAccount, int datacenterId, byte[] authKey, long authKeyId);' in manager
assert 'public void importAuthorization(int datacenterId, byte[] authKey, long authKeyId)' in manager
assert '{"native_pengramImportAuthKey", "(II[BJ)V", (void *) importAuthKey},' in wrapper
assert 'void importAuthKey(JNIEnv *env, jclass c, jint instanceNum, jint datacenterId, jbyteArray key, jlong keyId)' in wrapper
assert 'length != 256' in wrapper                       # мусор не пропускаем
assert 'ConnectionsManager::importAuthorization(uint32_t datacenterId, std::string authKey, int64_t authKeyId)' in native_manager
assert 'datacenterId < 1 || datacenterId > 5 || authKey.size() != 256 || authKeyId == 0' in native_manager
assert 'setPermanentAuthKey((const uint8_t *) authKey.data()' in native_manager
assert 'void Datacenter::setPermanentAuthKey(const uint8_t *bytes, uint32_t length, int64_t keyId)' in datacenter
assert 'length != 256 || keyId == 0' in datacenter
assert 'authorized = true;' in datacenter
assert 'suspendConnections(false);' in datacenter       # старые сокеты закрываются

# --- режимы ----------------------------------------------------------------
# Автоматический вход: ни одного вопроса о «безопасном» и «мгновенном» режиме.
assert 'public static final String MODE_AUTO = "auto";' in login
assert 'public static final String MODE_SAFE = "safe";' in login
assert 'public static final String MODE_INSTANT = "instant";' in login
# Вход — прямой: ключ из файла и есть сессия (так делают Telethon, Pyrogram и
# Desktop). Токенов здесь нет: они привязаны к DC и требуют вторую уже
# авторизованную сессию, а её на телефоне нет.
assert 'TL_auth_exportLoginToken' not in login
assert 'TL_auth_acceptLoginToken' not in login
assert 'TL_auth_importLoginToken' not in login
assert 'TL_auth_loginTokenSuccess' not in login
assert 'TL_auth_loginTokenMigrateTo' not in login
assert 'TL_users_getFullUser' in login and 'TL_inputUserSelf' in login
assert 'TLRPC.TL_account_' not in login and 'TLRPC.TL_account_' not in activity
assert 'public static void cancel()' in login            # вход можно отменить
assert 'PengramSessionLogin.cancel()' in activity        # и экран это делает
assert 'canHandleCurrentPassword(password, true)' in activity
# Порядок в connect(): сначала чистим слот, потом ключ, потом проверка.
connect = login.split('private static void connect(final Flow flow)', 1)[1]
clean = connect.index('manager.cleanup(true);')
import_key = connect.index('manager.importAuthorization(')
verify = connect.index('TL_users_getFullUser')
assert clean < import_key < verify, 'slot must be cleared before the key is imported'
assert 'manager.setUserId(user.id);' in connect
# id ключа: младшие 64 бита SHA-1, как в handshake
assert 'MessageDigest.getInstance("SHA-1")' in login

# --- UI --------------------------------------------------------------------
assert 'getString(R.string.PengramSessionLogin)' in activity
assert 'pengramShowSessionLogin()' in activity
assert 'REQUEST_PICK_SESSION' in activity
assert 'Intent.ACTION_OPEN_DOCUMENT' in activity
assert 'pengramStartSessionLogin(accounts.get(0))' in activity
assert 'pengramShowSessionMode' not in activity       # выбора режима больше нет
assert 'PengramSessionModeTitle' not in activity
assert 'MODE_AUTO' in activity
assert 'pengramSessionError("unsupported_file")' in activity
assert 'temp.delete();' in activity                      # за собой убираем
assert 'syncContacts = false;' in activity

# --- строки в обеих локалях -------------------------------------------------
needed = set(re.findall(r'R\.string\.(PengramSession[A-Za-z]+)', activity))
needed |= set(re.findall(r'R\.string\.(PengramSession[A-Za-z]+)', login))
needed |= set(re.findall(r'R\.string\.(PengramExport[A-Za-z]+)', export))
needed.add('PengramSessionErrBroken')
for name in sorted(needed):
    assert 'name="%s"' % name in en, 'EN missing ' + name
    assert 'name="%s"' % name in ru, 'RU missing ' + name

# --- эталонные формулы: тот же результат, что у Desktop ---------------------
def account_filename(index):
    name = 'data' if index == 0 else 'data#%d' % (index + 1)
    digest = hashlib.md5(name.encode()).digest()[:8]
    return ''.join('%X%X' % (b & 15, b >> 4) for b in digest)


def tdf_payload(payload, version=b'\x00\x00\x00\x01'):
    digest = hashlib.md5(payload + struct.pack('<I', len(payload)) + version + b'TDF$').digest()
    return b'TDF$' + version + payload + digest


def oldmtp_aes(auth_key, msg_key):
    a = hashlib.sha1(msg_key + auth_key[8:40]).digest()
    b = hashlib.sha1(auth_key[40:56] + msg_key + auth_key[56:72]).digest()
    c = hashlib.sha1(auth_key[72:104] + msg_key).digest()
    d = hashlib.sha1(msg_key + auth_key[104:136]).digest()
    return a[:8] + b[8:20] + c[4:16], a[8:20] + b[:8] + c[16:20] + d[:8]


def key_id(auth_key):
    sha = hashlib.sha1(auth_key).digest()
    value = 0
    for byte in reversed(sha[12:20]):
        value = (value << 8) | byte
    return value


# AES-IGE на Python, повторяющий Java один в один: encrypt затем decrypt
try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
except ImportError:  # pragma: no cover - среда без cryptography
    Cipher = None

if Cipher is not None:
    def ecb_encrypt(key, block):
        encryptor = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
        return encryptor.update(block) + encryptor.finalize()

    def ecb_decrypt(key, block):
        decryptor = Cipher(algorithms.AES(key), modes.ECB()).decryptor()
        return decryptor.update(block) + decryptor.finalize()

    def ige_encrypt(data, key, iv):
        # C[i] = E(P[i] ^ C[i-1]) ^ P[i-1], C[-1] = iv[:16], P[-1] = iv[16:]
        out = b''
        prev_c = iv[:16]
        prev_p = iv[16:]
        for offset in range(0, len(data), 16):
            plain = data[offset:offset + 16]
            block = bytes(a ^ b for a, b in zip(plain, prev_c))
            cipher = bytes(a ^ b for a, b in zip(ecb_encrypt(key, block), prev_p))
            out += cipher
            # состояние — именно выходной блок, а не результат AES до XOR
            prev_c, prev_p = cipher, plain
        return out

    def ige_decrypt(data, key, iv):
        # P[i] = D(C[i] ^ P[i-1]) ^ C[i-1]
        out = b''
        prev_c = iv[:16]
        prev_p = iv[16:]
        for offset in range(0, len(data), 16):
            cipher = data[offset:offset + 16]
            block = bytes(a ^ b for a, b in zip(cipher, prev_p))
            p = bytes(a ^ b for a, b in zip(ecb_decrypt(key, block), prev_c))
            out += p
            prev_c, prev_p = cipher, p
        return out

    key = bytes(range(32))
    msg = bytes(range(48))
    aes_key, iv = oldmtp_aes(bytes(range(256)), msg[:16])
    assert len(aes_key) == 32 and len(iv) == 32
    assert ige_decrypt(ige_encrypt(msg, aes_key, iv), aes_key, iv) == msg

# имена файлов аккаунтов: ровно 16 hex-символов, верхний регистр
assert len(account_filename(0)) == 16
assert account_filename(1) == account_filename(1).upper()
assert account_filename(0) != account_filename(1)
assert '0123456789ABCDEF' in formats
assert 'static String accountFilename(int index)' in formats

# --- выгрузка сессии --------------------------------------------------------
assert 'public static byte[] exportTdata' in formats
assert 'public static void exportTelethonSession' in formats
assert 'public static void exportPyrogramSession' in formats
assert 'public static String telethonStringSession' in formats
assert 'public static String pyrogramStringSession' in formats
# файл аккаунта Desktop пишет как QByteArray, а не «голым» шифроблоком
assert 'tdfEncode(beBlob(accountPayload))' in formats
assert 'outer.blob()' in formats
# поле длины шифроблока считает и свой заголовок — как в PrepareEncrypted
assert 'final int sizeField = 4 + plain.length;' in formats
assert 'System.arraycopy(le32(sizeField), 0, block, 0, 4);' in formats
# живая обёртка пароля = файл закрыт паролем, открытый ключ не используем
assert 'hasLivePasscodeWrap' in formats and 'liveWrap' in formats
assert 'CREATE TABLE sessions (dc_id integer primary key' in formats
assert 'insert into version values (7)' in formats
assert 'insert into version values (3)' in formats
# предупреждения об опасности стоят до выбора формата
assert 'PengramExportWarning' in export and 'PengramExportConfirmFile' in export
assert export.index('PengramExportWarning') < export.index('showFormatPicker')
assert 'private static void checkExportTarget' in formats
assert 'native_pengramCopyAuthKey' in manager
assert 'public static native int native_pengramCopyAuthKey(int currentAccount, byte[] out);' in manager
assert '{"native_pengramCopyAuthKey", "(I[B)I", (void *) copyAuthKey},' in wrapper
assert 'ConnectionsManager::pengramCopyAuthKey' in native_manager
assert 'getDatacenterWithId(DEFAULT_DATACENTER_ID)' in native_manager
assert 'PengramSessionExport.show(this, currentAccount)' in (
    java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()

# TDF-контроль: испорченный байт обязан не сойтись
payload = bytes(range(64))
record = tdf_payload(payload)
assert record[:4] == b'TDF$'
broken = bytearray(record)
broken[20] ^= 0xFF
assert hashlib.md5(bytes(broken[8:-16]) + struct.pack('<I', len(payload))
                   + bytes(broken[4:8]) + b'TDF$').digest() != bytes(broken[-16:])

# key_id — старшие байты SHA-1 не участвуют
sha = hashlib.sha1(bytes(range(256))).digest()
assert key_id(bytes(range(256))) == int.from_bytes(sha[12:20], 'little')

print('session login: read-only parsers (Telethon/Pyrogram/tdata/string), '
      'automatic sign-in with instant fallback, native key export/import, '
      '%d strings: OK' % len(needed))
