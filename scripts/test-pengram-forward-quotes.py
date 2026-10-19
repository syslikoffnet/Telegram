#!/usr/bin/env python3
"""Static regression checks for forwarding classification and quote geometry.

Run on a developer machine without Android; the Java preflight and APK build
remain necessary to check compiler compatibility and UI behavior on a device.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'TMessagesProj/src/main'
java = ROOT / 'java/org/telegram'
copy = (java / 'messenger/PengramCopySender.java').read_text()
forward = (java / 'messenger/SendMessagesHelper.java').read_text()
chat = (java / 'ui/ChatActivity.java').read_text()
quote = (java / 'ui/Components/PengramQuoteMaker.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
index = (java / 'ui/PengramSearchIndex.java').read_text()

# Chat auto-delete does not protect messages against native forwarding.
protected = copy.split('public static boolean isProtected(', 1)[1].split('public static boolean isPeerRestricted(', 1)[0]
assert 'messageOwner.ttl_period' not in protected
assert 'message.messageOwner.noforwards' in protected
assert 'message.isSecretMedia()' in protected
assert 'message.isOutOwner()' in protected
assert 'full.noforwards_my_enabled : full.noforwards_peer_enabled' in copy
assert 'isPeerRestricted(message.currentAccount, dialogId, message.isOutOwner())' in protected
assert 'finished.compareAndSet(false, true)' in copy
assert 'AndroidUtilities.runOnUIThread(() -> callback.run(path))' in copy

# A protected element of a multi-selection must not strip attribution from
# the ordinary elements. The standard forward retains all its arguments.
split = forward.split('if (PengramCopySender.shouldCopy(messages, peer))', 1)[1].split('// Pengram: трек', 1)[0]
assert 'PengramCopySender.isProtected(item) ? protectedMessages : ordinaryMessages' in split
assert 'sendMessage(ordinaryMessages, peer, forwardFromMyNameArg,' in split
assert 'PengramCopySender.sendCopies(currentAccount, protectedMessages, peer, notify, null)' in split
assert 'boolean notify, Result result' in copy
assert 'sendWithoutFile(account, message, dialogId, notify)' in copy
assert 'params, notify, 0, 0, 0,' in copy
assert 'caption, entities, null, null, 0, null, notify, 0, 0, null)' in copy
assert 'sendCopies(currentAccount, messages, peer)' not in split
assert 'pengramCopySendError(messages, peer, scheduleDate, scheduleRepeatPeriod)' in split
assert 'copyTargetError(currentAccount, peer, scheduleDate, scheduleRepeatPeriod)' in forward
assert 'return 23' in copy and 'return price > 0 ? 24 : 0' in copy
assert 'copyTargetError(account, dialogId, 0, 0)' in copy

# A deleted/one-time item never forces other selected messages to lose their
# forwarded-from attribution in either chat forwarding entry point.
assert 'pengramForwardAsMine(' not in chat
assert 'sendMessage(arrayList, dialog_id, fromMyName,' in chat
assert 'sendMessage(fmessages, did, false, false, notify,' in chat
assert 'pengramCopySendError(\n                            fmessages, destination.dialogId' in chat

menu = chat.split('private boolean pengramCanResend(', 1)[1].split('private void pengramResend(', 1)[0]
assert re.search(r'return message\.pengramDeleted\s*\|\|\s*org\.telegram\.messenger\.PengramCopySender\.isProtected\(message\)', menu)

# Both the face and its name are measured/drawn with the same gap and size.
for key in ('KEY_AVATAR_SIZE', 'KEY_AVATAR_GAP', 'KEY_AVATAR_ROUNDED'):
    assert key in quote and key in settings, key
assert 'nameInset = avatar ? avatarSize + avatarGap : 0' in quote
assert 'cy = inner + nameHeight / 2f' in quote
assert 'paint.setColor(accent);\n                        paint.setTextSize(25 * fontScale);' in quote
for key in ('PengramQuoteAvatarSize', 'PengramQuoteAvatarGap', 'PengramQuoteAvatarRounded'):
    assert f'R.string.{key}' in index
    for lang in ('values', 'values-ru'):
        tree = ET.parse(ROOT / f'res/{lang}/strings.xml')
        assert tree.find(f'.//string[@name="{key}"]') is not None
print('forwarding and quote geometry: OK')
