#!/usr/bin/env python3
"""Source contracts for occupied-slot rejection and diagnostic coverage.

These offline assertions do NOT prove device account isolation or queue recovery.
"""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
login = (java / 'messenger/PengramSessionLogin.java').read_text()
start = login.split('public static void start(', 1)[1].split('public static void cancel()', 1)[0]
assert 'isSlotAvailable(targetAccount)' in start, 'occupied slot is not rejected before starting import'
assert start.index('isSlotAvailable(targetAccount)') < start.index('cancel();') < start.index('connect(flow);')
assert 'listener.onError("slot_busy")' in start
connect = login.split('private static void connect(final Flow flow)', 1)[1].split('private static void succeed', 1)[0]
assert 'isSlotAvailable(flow.account)' in connect
assert connect.index('isSlotAvailable(flow.account)') < connect.index('manager.cleanup(true)') < connect.index('manager.importAuthorization(')
assert 'flow.cancelled || active != flow' in connect
assert 'volatile boolean cancelled' in login and 'volatile Flow active' in login
ui = (java / 'ui/LoginActivity.java').read_text()
assert '"slot_busy".equals(code)' in ui and 'R.string.PengramSessionErrSlotBusy' in ui
assert 'UserConfig.selectedAccount' not in (java / 'ui/DialogsActivity.java').read_text()
history = (java / 'messenger/PengramHistory.java').read_text()
assert 'public static ArrayList<Entry> getEntries(long dialogId, int filter, int limit) {' not in history
assert 'boolean ascending) {' not in history.split('public static ArrayList<Entry> getEntries', 1)[1].split('/** Archive queries', 1)[0]
diag = (java / 'messenger/PengramDiagnostics.java').read_text()
for key in ('userId=', 'activated=', 'connectionState=', 'localOnly=', 'authKeyPresent=UNKNOWN', 'pendingGhost=', 'lastLogout=', 'ghostDelay='):
    assert key in diag, key
assert 'native_' not in diag, 'diagnostics must not mutate native state or extract keys'
assert 'recordLogout(currentAccount)' in (java / 'tgnet/ConnectionsManager.java').read_text()
assert 'getPendingGhostCount(int account)' in (java / 'messenger/SendMessagesHelper.java').read_text()
print('OK: occupied-slot guards, explicit account routing, diagnostic source contracts')
