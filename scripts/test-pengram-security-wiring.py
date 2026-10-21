#!/usr/bin/env python3
"""Check critical wiring of the Java security policy into production call sites.

The pure Java behavior is executed by PengramSecurityPolicyTest on CI.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'TMessagesProj/src/main/java/org/telegram'
read = lambda path: (root / path).read_text()
profile = read('messenger/PengramProfileHistory.java')
cloud = read('messenger/PengramProfileCloud.java')
auto = read('messenger/PengramAIAutoReply.java')
controller = read('messenger/MessagesController.java')
config = read('messenger/UserConfig.java')
client = read('messenger/PengramAIClient.java')
proxy = read('messenger/PengramBypass.java')
engine = read('messenger/PengramBypassEngine.java')

assert 'observeBio(currentAccount, userFull.user, userFull.about, false)' in controller
for observer in ('observeUser', 'observeAdditionalUsernames', 'observeBio'):
    section = profile.split('public static void ' + observer + '(', 1)[1].split('\n    }', 1)[0]
    assert 'canObserve(account, user, fromCache)' in section, observer
assert 'if (owner == 0 || UserConfig.getInstance(account).getClientUserId() != owner' in profile
assert 'PengramProfilePolicy.queueToCloud(storage, true)' in profile
assert 'WHERE account=? AND user_id=?' in profile
assert 'where.append(" AND user_id=?")' in profile
assert 'db.delete("cloud_queue", "account=?", args)' in profile
assert 'db.delete("changes", "account=?", args)' in profile
assert 'PengramProfileHistory.clearAccount(currentAccount)' in config
assert 'PengramAIAutoReply.clearAccount(currentAccount)' in config
assert 'ownerId != UserConfig.getInstance(account).getClientUserId()' in auto
assert 'bindRules(account, UserConfig.getInstance(account).getClientUserId())' in auto
assert 'PengramProfileHistory.ensureAccountOwner(account)' in cloud
assert 'discardPending(account)' in cloud
assert 'owner != UserConfig.getInstance(account).getClientUserId()' in cloud
assert 'setInstanceFollowRedirects(false)' in client
assert 'bounded(stream, MAX_RESPONSE_BYTES)' in client
assert 'PengramAITransport.isSafeEndpoint(endpoint)' in client
assert 'node.host = checked.getAddress().getHostAddress()' in proxy
assert 'return new ArrayList<>(); // never try a node that failed validation/probing' in proxy
assert 'publicProxyAddress(n.host, n.port)' in engine
assert 'expected.equals(accept)' in engine
print('Pengram security wiring: OK')
