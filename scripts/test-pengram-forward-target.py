#!/usr/bin/env python3
"""Пересылка медиа должна уходить только в выбранный чат.

Пользователь ловил такую картину: переслал видео в один чат, потом открыл
другой — видео появилось в обоих. Проверяем все места, где одно и то же
сообщение могло быть отправлено дважды: панель пересылки, перенос панели в
другой чат, повторный вызов делегата выбора чата и очередь копий.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent

chat = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text()
sender = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramCopySender.java').read_text()

# --- панель пересылки отправляет ровно один раз ------------------------------
assert 'private boolean pengramForwardPanelConsumed;' in chat
assert 'if (!pengramForwardPanelConsumed) {\n                    pengramForwardPanelConsumed = true;' in chat
# новая панель — снова можно отправлять (иначе вторая пересылка терялась)
assert 'pengramForwardPanelConsumed = false;' in chat

# --- панель, уехавшая в другой чат, гаснет молча ------------------------------
assert 'private void discardForwardPanel()' in chat
assert 'discardForwardPanel();\n                Bundle args = new Bundle();' in chat
assert 'forwardingPreviewView.dismiss(false);' in chat

# --- выбор чата забирает состояние сразу --------------------------------------
assert '// Снимок уже сделан: снимаем состояние пересылки и выделение до любых' in chat
assert 'forwardingMessage = null;\n            forwardingMessageGroup = null;' in chat

# --- повторный вызов делегата не отправляет второй раз ------------------------
assert 'final java.util.concurrent.atomic.AtomicBoolean pengramSent = new java.util.concurrent.atomic.AtomicBoolean();' in chat
assert 'if (!pengramSent.compareAndSet(false, true)) {' in chat

# --- копия всегда адресуется конкретному чату ---------------------------------
assert 'private static void sendOne(int account, MessageObject message, long dialogId, boolean notify' in sender
assert 'private static boolean sendWithoutFile(int account, MessageObject message, long dialogId, boolean notify)' in sender
assert 'helper.sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId,' in sender
assert 'SendMessagesHelper.SendMessageParams.of(document, null, path, dialogId,' in sender
assert 'deviceModel = org.telegram.messenger.PengramDeviceName.resolveDeviceModel();' in (
    root / 'TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java').read_text()

# --- прогресс отправки считается по каждому чату отдельно ---------------------
assert 'private static final HashMap<Long, int[]> jobs = new HashMap<>();' in sender
assert 'public static int getTotal(long dialogId)' in sender
assert 'public static int getDone(long dialogId)' in sender
assert 'PengramCopySender.getTotal(dialog_id)' in chat
assert 'PengramCopySender.isPreparing(dialog_id)' in chat

print('forwarding: one send per panel, per-chat copies, no stale panel: OK')
