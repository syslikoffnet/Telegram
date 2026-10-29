#!/usr/bin/env python3
"""Regression checks for comprehensive Pengram backup/restore, real-name peek,
'Read All' default-off, unified deleted/edited controls, and privacy leak guards."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'

config = (java / 'messenger/PengramConfig.java').read_text()
backup = (java / 'messenger/PengramBackup.java').read_text()
history = (java / 'messenger/PengramHistory.java').read_text()
storage = (java / 'messenger/MessagesStorage.java').read_text()
controller = (java / 'messenger/MessagesController.java').read_text()
send_helper = (java / 'messenger/SendMessagesHelper.java').read_text()
media_ctrl = (java / 'messenger/MediaController.java').read_text()
emoji_overlay = (java / 'ui/EmojiAnimationsOverlay.java').read_text()
mark_span = (java / 'ui/Components/PengramMarkSpan.java').read_text()
msg_cell = (java / 'ui/Cells/ChatMessageCell.java').read_text()
chat = (java / 'ui/ChatActivity.java').read_text()
hist_chat = (java / 'ui/PengramHistoryChatActivity.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
orig_name = (java / 'messenger/PengramOriginalName.java').read_text()
lookup = (java / 'messenger/PengramNameLookup.java').read_text()

# 1. Real Telegram name gesture (Profile + Chat header swipe, default MODE_BOTH)
assert 'getIntCached(KEY_MODE, MODE_BOTH)' in orig_name
assert 'private_forward_name' in orig_name
assert 'TL_users_getFullUser' in orig_name
assert 'sameAsShown' in orig_name
assert 'SharedConfig.currentProxy' in lookup
for locale in ('values', 'values-ru'):
    strings = {e.get('name'): (e.text or '') for e in ET.parse(root / f'TMessagesProj/src/main/res/{locale}/strings.xml').iter('string')}
    assert 'PengramOriginalNameSame' in strings and '%1$s' in strings['PengramOriginalNameSame']
    assert 'PengramMarkEditStandard' in strings
    assert 'PengramMarkEditHidden' in strings
    assert 'PengramMenuReadAll' in strings

# 2. "Read All" in chat list 3-dots menu disabled by default + one-time migration + settings toggle
assert 'case MENU_ITEM_READ_ALL:\n                return true;' in config
assert 'migrMenuReadAllOff' in config
assert 'BTN_MENU_READ_ALL' in settings and 'PengramConfig.isReadAllMenuShown()' in settings

# 3. Comprehensive multi-store typed backup/restore without leaking API keys
assert 'EXPORTABLE_AI_KEYS' in config
for store in ('PengramAI.PREFS', '"pengram_bypass"', '"mainconfig"'):
    assert store in config, store
assert 'root.put("types", types)' in config
assert 'root.put("ai", aiObj)' in config and 'root.put("bypass", bypassObj)' in config and 'root.put("visual", visualObj)' in config
assert 'java.util.Set<?>' in config and '"set".equals(declaredType)' in config and '"float".equals(declaredType)' in config
assert 'PengramConfig.exportToJsonInternal(SKIP_PREFIX_SET, false)' in backup
assert 'PengramConfig.countExportableEntries(SKIP_PREFIX_SET)' in backup
# Sensitive API keys and ephemeral counters must never be exported
export_block = config.split('EXPORTABLE_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(', 1)[1].split('));', 1)[0]
exported_keys = set(re.findall(r'"([^"]+)"', export_block))
for newly_covered in (
    'linkAssistant', 'linkAssistantPreview', 'linkAssistantFallback', 'linkAssistantPhotoStitch',
    'linkAssistantSlideAudio', 'linkAssistantSlideSec', 'linkAssistantCaption', 'linkAssistantIslandPreviewBtn',
    'hideMenuReadAll', 'hideMenuArchive', 'originalName', 'profileHistoryEnabled',
    'autoEnabled', 'autoCooldown', 'autoRules', 'autoRecentContext',
    'bypassEnabled', 'md3MonetStrength',
):
    assert newly_covered in exported_keys, f'Missing from EXPORTABLE_NAMES: {newly_covered}'
for forbidden in ('migrIslandBottom', 'migrMenuReadAllOff', 'lastSeenCrashTimestamp', 'apiKey', 'key_openai'):
    assert forbidden not in exported_keys
# Exhaustively verify every KEY_* constant across all Pengram*.java files is accounted for
ai_exported = set(re.findall(r'"([^"]+)"', config.split('EXPORTABLE_AI_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(', 1)[1].split('));', 1)[0]))
ephemeral_keys = {
    'antiCrashBlocked', 'settingsSearchRecentEntries', 'settingsSearchHistory',
    'pending', 'pendingTime', 'pendingInstallStamp', 'nativeExitSeen',
    'rates_json', 'updated_at_ms',
}
for p in sorted(java.rglob('Pengram*.java')):
    for m in re.finditer(r'String\s+KEY_[A-Z0-9_]+\s*=\s*"([^"]+)"', p.read_text()):
        k = m.group(1)
        if k.endswith('_'):
            continue
        assert k in exported_keys or k in ai_exported or k in ephemeral_keys, f'Uncovered {p.name}: {k}'

# 4. Deleted/edited marks, shared drawable mutation & alpha restore, unified 5-option editedMark
assert 'MARK_EDIT_HIDDEN = 4' in config
assert 'final int count = 5;' in settings
assert 'Drawable.ConstantState' in mark_span and '.mutate()' in mark_span
assert 'currentBackgroundDrawable.setAlpha(255);' in msg_cell
assert 'hasEntry(account, dialogId, id)' not in history.split('public static void markDeleted(', 1)[1].split('public static', 1)[0]
assert 'mergeDialogId != 0 && org.telegram.messenger.PengramHistory.isMarkedDeleted' in chat
assert 'new ChatMessageCell(context, currentAccount, false, null, getResourceProvider())' in hist_chat

# 5. Privacy leak guards (Ghost Mode / No-Screenshot / No-Typing / Keep-Once-Media)
assert 'if (PengramConfig.isNoScreenshotNotify())' in send_helper.split('public void sendScreenshotMessage(', 1)[1].split('TLRPC.TL_messages_sendScreenshotNotification', 1)[0]
assert '!PengramConfig.isNoScreenshotNotify()' in media_ctrl
assert 'PengramConfig.isNotSendingRead()' in controller.split('public void markMentionMessageAsRead(', 1)[1].split('public long createDeleteShowOnceTask', 1)[0]
assert 'PengramConfig.isNotSendingRead()' in controller.split('public void markMentionsAsRead(', 1)[1].split('public void markDialogAsRead', 1)[0]
assert 'PengramConfig.isNotSendingRead()' in controller.split('public void markPollVotesAsRead(', 1)[1].split('TLRPC.TL_messages_readPollVotes', 1)[0]
assert '!DialogObject.isEncryptedDialog(task.dialogId)' not in controller.split('private void completeReadTask(', 1)[1].split('if (task.replyId != 0', 1)[0]
assert 'PengramConfig.isNotSendingTyping()' in emoji_overlay
assert 'PengramConfig.isKeepingOnceMedia()' in storage.split('public void emptyMessagesMedia(', 1)[1].split('storageQueue.postRunnable', 1)[0]

print('Pengram backup, real-name peek, Read-All default, marks/themes, and privacy guards: OK')
