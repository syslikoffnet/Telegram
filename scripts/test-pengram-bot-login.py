#!/usr/bin/env python3
"""Regression checks for signing in with a bot token.

`auth.importBotAuthorization` is not part of the TL layer this fork was generated
from, so it lives in its own hand-written class. The login screen must offer it
next to the phone-number flow, send it without a login (there is no account yet)
and finish through the very same onAuthSuccess() path.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
tl = (root / 'TMessagesProj/src/main/java/org/telegram/tgnet/TL_auth_importBotAuthorization.java').read_text()
login = (root / 'TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java').read_text()

# --- the TL method itself ---------------------------------------------------
assert 'public class TL_auth_importBotAuthorization extends TLObject' in tl
assert 'public static final int constructor = 0x67a3ff2c;' in tl
assert 'public int flags;' in tl
assert 'public int api_id;' in tl
assert 'public String api_hash;' in tl
assert 'public String bot_auth_token;' in tl
# field order is the wire format: constructor, flags, api_id, api_hash, token
order = [tl.index('stream.writeInt32(constructor);'),
         tl.index('stream.writeInt32(flags);'),
         tl.index('stream.writeInt32(api_id);'),
         tl.index('stream.writeString(api_hash);'),
         tl.index('stream.writeString(bot_auth_token);')]
assert order == sorted(order), 'serializeToStream field order changed'
assert 'TLRPC.auth_Authorization.TLdeserialize(stream, constructor, exception)' in tl
# it must not be tucked into the generated TLRPC file, which is replaced wholesale
assert 'class TL_auth_importBotAuthorization' not in (
    root / 'TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java').read_text()

# --- the entry point on the login screen ------------------------------------
assert 'import org.telegram.tgnet.TL_auth_importBotAuthorization;' in login
assert 'private TextView botLoginView;' in login
assert 'botLoginView.setText(getString(R.string.PengramBotLogin));' in login
assert 'if (activityMode == MODE_LOGIN) {' in login
assert 'botLoginView.setOnClickListener(v -> pengramShowBotLoginDialog());' in login

dialog = login.split('private void pengramShowBotLoginDialog()', 1)[1].split('private boolean pengramBotLoginRunning;', 1)[0]
assert 'R.string.PengramBotLoginTitle' in dialog
assert 'R.string.PengramBotLoginInfo' in dialog
assert 'R.string.PengramBotLoginHint' in dialog
assert 'EditTextBoldCursor' in dialog
assert 'setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)' in dialog

flow = login.split('private void pengramLoginWithBotToken(String token)', 1)[1].split('private void fakeSuccess()', 1)[0]
assert 'new TL_auth_importBotAuthorization()' in flow
assert 'request.api_id = BuildVars.APP_ID;' in flow
assert 'request.api_hash = BuildVars.APP_HASH;' in flow
assert 'request.bot_auth_token = token;' in flow
assert 'request.flags = 0;' in flow
# no account is authorized yet, so the request goes out without a login
assert 'ConnectionsManager.RequestFlagWithoutLogin' in flow
assert 'response instanceof TLRPC.TL_auth_authorization' in flow
assert 'onAuthSuccess((TLRPC.TL_auth_authorization) response);' in flow
# a bot does not need the phone book
assert 'syncContacts = false;' in flow
# a token looks like 123456789:AA... and nothing else is accepted
assert 'token.indexOf(\':\')' in flow
assert 'R.string.PengramBotLoginInvalid' in flow
assert 'R.string.PengramBotLoginError' in flow
# double taps must not send the request twice
assert 'if (pengramBotLoginRunning) {' in flow
# progress dialog is dismissed on the UI thread in both outcomes
assert 'progress.dismiss();' in flow

# --- a bot account has no phone number --------------------------------------
# contacts.* is forbidden for bots, and the phone book does not exist: without
# these guards the contacts screen waits for a load that can never finish.
userconfig = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java').read_text()
contacts = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/ContactsController.java').read_text()
assert 'public boolean isBotAccount() {' in userconfig
assert 'return currentUser != null && currentUser.bot;' in userconfig

read_contacts = contacts.split('public void readContacts() {', 1)[1].split('private boolean isNotValidNameString', 1)[0]
assert 'if (getUserConfig().isBotAccount()) {' in read_contacts
assert 'contactsLoaded = true;' in read_contacts
assert 'contactsBookLoaded = true;' in read_contacts
assert 'postNotificationName(NotificationCenter.contactsDidLoad)' in read_contacts

sync = contacts.split('protected void performSyncPhoneBook(', 1)[1].split('Utilities.globalQueue.postRunnable(() -> {', 1)[0]
assert 'getUserConfig().isBotAccount()' in sync
# a failed contacts.getContacts must not leave the screen loading forever
load_server = contacts.split('public void loadContacts(boolean fromCache, final long hash) {', 1)[1].split('public void processLoadedContacts', 1)[0]
assert load_server.count('loadingContacts = false;') >= 1
assert 'postNotificationName(NotificationCenter.contactsDidLoad)' in load_server

# the settings profile line shows @username instead of "+null"
settings = (root / 'TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java').read_text()
info = settings.split('public void setInfo(TLRPC.User user) {', 1)[1].split('versionView.setText(getVersionName());', 1)[0]
assert 'final boolean bot = user != null && user.bot;' in info
assert 'R.string.PengramBotAccount' in info
assert 'if (username != null && !bot) {' in info

# --- strings ----------------------------------------------------------------
for res in ('values', 'values-ru'):
    xml = (root / f'TMessagesProj/src/main/res/{res}/strings.xml').read_text()
    for s in ('PengramBotLogin', 'PengramBotAccount', 'PengramBotLoginTitle', 'PengramBotLoginInfo',
              'PengramBotLoginHint', 'PengramBotLoginInvalid', 'PengramBotLoginFailed',
              'PengramBotLoginError'):
        assert f'<string name="{s}">' in xml, (s, res)
        assert xml.count(f'<string name="{s}">') == 1, (s, res)

print('bot token login: OK')
