#!/usr/bin/env python3
"""Regression checks for the exact-name check in a profile.

Telegram shows a contact's name the way you saved it, so the "official" name has
to come from somewhere else. The old implementation deleted the contact for one
request and restored it — the user rejected that. The current one never touches
contacts: it reads the public profile page (t.me/<username>), which is exactly
what a stranger sees, and falls back to names already observed locally.

Constraints that must not regress:
  * no contact deletion / restoration on the live path, no phone numbers;
  * no acknowledgement dialog and no daily limits;
  * @username is never presented as the name;
  * repeat gestures show the cached value instead of hitting the network again.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
name = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramOriginalName.java').read_text()
lookup = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramNameLookup.java').read_text()
profile = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java').read_text()
settings = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java').read_text()
index = (root / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java').read_text()
block = profile.split('private void pengramToggleOriginalName()', 1)[1].split('private void updateProfileData(', 1)[0]

# --- no contact surgery and no phone numbers --------------------------------
assert 'deleteContacts' not in name
assert 'TL_contacts_addContact' not in name
assert 'pengramMarkContactRemoved' not in name
assert name.count('phone') == 1 and 'restore.phone = "";' in name, 'name check must not send phones'
assert 'add_phone_privacy_exception' not in name
# the only surviving use is the legacy restore of contacts left behind by the old version
assert name.count('pengramRestoreContact') == 1
assert 'public static void restorePending(int account) {' in name
assert name.index('restorePending') < name.index('pengramRestoreContact')
assert 'PengramOriginalName.restorePending(a);' in (root / 'TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java').read_text()

# --- no acknowledgement dialog, no limits -----------------------------------
for banned in ('probeAcknowledged', 'setProbeAcknowledged', 'KEY_PROBE',
               'PROBE_GLOBAL_COOLDOWN', 'PROBE_USER_COOLDOWN', 'PROBE_DAILY_LIMIT',
               'PROBE_LIMITED', 'PROBE_DISABLED'):
    assert banned not in name, banned
    assert banned not in block, banned
assert 'AlertDialog' not in block
assert 'pengramAskProbePermission' not in profile

# --- the official name comes from the public page ---------------------------
assert 'PengramNameLookup.lookup(username' in name
assert 'PengramNameLookup.isValidUsername' in name
assert 'public static final int SOURCE_PROFILE = 4;' in name
assert 'R.string.PengramOriginalNamePublicProfile' in name
assert "https://t.me/" in lookup
assert '?embed=1' not in lookup
assert 'og:title' in lookup
assert 'tgme_page_title' in lookup
assert lookup.index('tgme_page_title') < lookup.index('og:title')
assert 'static String parseName(@Nullable String html)' in lookup
assert 'static String cleanName(@Nullable String raw)' in lookup
# a bare @username must never be returned as a name
assert 'startsWith("@")' in lookup
assert 'return null;' in lookup

# --- @username is no longer a result source ---------------------------------
other = name.split('public static Result other(int account, TLRPC.User user)', 1)[1].split('public static boolean canRefresh(', 1)[0]
assert 'getPublicUsername' not in other
assert '"@"' not in other
assert 'SOURCE_USERNAME' not in other

# --- cache first, refresh at most once per known state ----------------------
assert 'private static final long FRESH_FOR' in name
assert 'private static final long VERIFIED_TTL' in name
assert 'public static boolean canRefresh(int account, TLRPC.User user) {' in name
assert 'refreshedThisRun.contains(id)' in name
assert 'refreshing.contains(id)' in name
assert 'refreshedThisRun.add(id)' in name
# the profile asks for the cached value first, then optionally refreshes
assert block.index('PengramOriginalName.other(') < block.index('PengramOriginalName.canRefresh(')
assert block.index('PengramOriginalName.canRefresh(') < block.index('PengramOriginalName.refresh(')
assert 'if (!refresh) {' in block
# a stale answer must not overwrite a newer gesture; no auto-revert timer
assert 'pengramNameCheckSeq' in block
assert 'seq != pengramNameCheckSeq' in block
assert 'postDelayed' not in block
assert 'PengramOriginalNameSame' in block

# --- the gesture works on SimpleTextView (claim touch, keep right-drawable click) ---
attach = name.split('public static void attach(View view, Runnable onToggle)', 1)[1]
assert 'detector.onTouchEvent(event)' in attach
assert 'v.getVisibility() != View.VISIBLE' in attach
assert 'onSingleTapUp' in attach and 'onFling' in attach
chat_text = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text()
avatar_container = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java').read_text()
assert 'pengramToggleHeaderOriginalName' in chat_text
assert 'parentFragment.pengramToggleHeaderOriginalName()' in avatar_container

# --- UI wiring --------------------------------------------------------------
assert 'PengramOriginalName.attach(nameTextView[a], this::pengramToggleOriginalName)' in profile
assert 'PengramOriginalName.canRefresh' in block
assert 'PengramOriginalName.refresh(' in block
assert 'R.string.PengramOriginalNameProbing' in block
assert 'R.string.PengramOriginalNameNothing' in block
# one home in settings: the mode cell, nothing else
assert 'UItem.asSettingsCell(BTN_ORIGINAL_NAME' in settings
assert 'checkInfo(org.telegram.messenger.PengramOriginalName.KEY_PROBE' not in settings
assert 'R.string.PengramOriginalNameProbe,' not in settings
assert 'PengramOriginalNameProbe' not in index

# --- strings ----------------------------------------------------------------
for res in ('values', 'values-ru'):
    xml = (root / f'TMessagesProj/src/main/res/{res}/strings.xml').read_text()
    for s in ('PengramOriginalName', 'PengramOriginalNameInfo', 'PengramOriginalNamePublicProfile',
              'PengramOriginalNameProbing', 'PengramOriginalNameProbeSource',
              'PengramOriginalNameNothing'):
        assert f'<string name="{s}">' in xml, (s, res)
        assert xml.count(f'<string name="{s}">') == 1, (s, res)
    for gone in ('PengramOriginalNameProbe', 'PengramOriginalNameProbeInfo',
                 'PengramOriginalNameProbeConfirm', 'PengramOriginalNameProbeLimit',
                 'PengramOriginalNameUsername'):
        assert f'<string name="{gone}">' not in xml, (gone, res)

print('exact name check: OK')
