#!/usr/bin/env python3
"""Regression checks for the exact-name check in a profile.

Telegram shows a contact's name the way you saved it. To see the name the
person set for themselves, the contact is removed for one request and put back
with exactly the saved name. That is a server-visible trick, so it must be
opt-in, strictly rate limited, and must never touch phone numbers in either
direction.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
name = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramOriginalName.java').read_text()
contacts = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/ContactsController.java').read_text()
profile = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java').read_text()
settings = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java').read_text()
launch = (root / 'TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java').read_text()

# --- off by default, explained before the first request ----------------------
assert 'public static final String KEY_PROBE = "originalNameProbe";' in name
assert 'return PengramConfig.getBool(KEY_PROBE, false);' in name
assert 'public static final String KEY_PROBE_ACK = "originalNameProbeAck";' in name
assert 'public static final int SOURCE_PROBE = 3;' in name
assert 'if (!probeEnabled()) {' in name
assert 'callback.onProbeResult(null, PROBE_DISABLED);' in name

# --- the contact must really be in the contacts -----------------------------
assert 'public static boolean isContact(int account, TLRPC.User user) {' in name
assert 'contactsDict.containsKey(user.id)' in name
assert '|| !isContact(account, user)) {' in name

# --- exactly one remove request and one restore request ---------------------
assert 'final TLRPC.TL_contacts_deleteContacts request = new TLRPC.TL_contacts_deleteContacts();' in name
assert name.count('TL_contacts_deleteContacts') == 2  # тип + new
assert name.count('pengramRestoreContact') == 2  # live path + pending restore
assert name.count('pengramMarkContactRemoved') == 1
# a second gesture while the probe runs must not fire a second request
assert 'probing.add(userId)' in name and 'probing.remove(userId)' in name
assert 'PROBE_BUSY' in name
assert 'hasPending(account, user.id)' in name

# --- rate limits ------------------------------------------------------------
assert 'private static final long PROBE_GLOBAL_COOLDOWN = 15L * 60L * 1000L;' in name
assert 'private static final long PROBE_USER_COOLDOWN = 24L * 60L * 60L * 1000L;' in name
assert 'private static final int PROBE_DAILY_LIMIT = 10;' in name
assert 'if (!allowProbe(account, userId)) {' in name
assert 'noteProbe(account, userId);' in name
# the checked name is cached, so repeat gestures cost nothing
assert 'private static final long PROBE_CACHE_TTL' in name
assert 'storeProbedName(account, userId, name);' in name
assert 'TL_users_getUsers' in name  # имя берём отдельным лёгким запросом, если ответ удаления пуст
assert 'probedName(account, user.id)' in name

# --- crash safety -----------------------------------------------------------
# the restore marker is written before the contact is removed ...
assert name.index('savePending(account, userId, savedFirst, savedLast, accessHash);') \
    < name.index('TL_contacts_deleteContacts request = new')
# ... and only cleared after a successful restore
assert 'public static void restorePending(int account) {' in name
assert 'ContactsController.getInstance(account).pengramRestoreContact(restore, ok -> {' in name
assert 'if (ok) {' in name
assert 'PengramOriginalName.restorePending(a);' in launch

# --- never touch phone numbers in either direction --------------------------
restore = contacts.split('public void pengramRestoreContact', 1)[1].split('private void addContact(TLRPC.User user, TLRPC.TL_textWithEntities note, boolean exception, boolean silentRestore, Utilities.Callback<Boolean> done)', 1)[0]
assert 'req.phone = silentRestore ? "" : user.phone;' in contacts
assert 'req.add_phone_privacy_exception = !silentRestore && exception' in contacts
# the silent restore must not write the phone book
assert 'if (!silentRestore) {\n                    Utilities.phoneBookQueue.postRunnable(() -> addContactToPhoneBook(u, true));' in contacts
assert 'if (!silentRestore && !TextUtils.isEmpty(u.phone)) {' in contacts
# ... and must still persist the contact locally, or it would vanish on restart
assert 'getMessagesStorage().putContacts(arrayList, false);' in contacts
assert 'req.id == null' in contacts  # no request without access_hash

# --- UI ---------------------------------------------------------------------
assert 'private void pengramAskProbePermission(TLRPC.User user)' in profile
assert 'R.string.PengramOriginalNameProbeConfirm' in profile
assert 'PengramOriginalName.setProbeAcknowledged(true);' in profile
assert 'private void pengramStartNameProbe(TLRPC.User user)' in profile
assert 'PengramOriginalName.probe(currentAccount, user, (result, error) -> {' in profile
assert 'private boolean pengramNameProbeRunning;' in profile
assert 'PengramOriginalName.KEY_PROBE' in settings  # the switch lives in settings
assert 'R.string.PengramOriginalNameProbe,' in (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java').read_text()

for res in ('values', 'values-ru'):
    xml = (root / f'TMessagesProj/src/main/res/{res}/strings.xml').read_text()
    for s in ('PengramOriginalNameProbe', 'PengramOriginalNameProbeInfo',
              'PengramOriginalNameProbeConfirm', 'PengramOriginalNameProbing',
              'PengramOriginalNameProbeLimit', 'PengramOriginalNameProbeFailed',
              'PengramOriginalNameProbeSource'):
        assert f'<string name="{s}">' in xml, (s, res)
        assert xml.count(f'<string name="{s}">') == 1, (s, res)

print('exact name check: OK')
