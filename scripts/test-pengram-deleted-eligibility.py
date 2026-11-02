#!/usr/bin/env python3
"""Regression contracts for deletions that must not become unmarked ghosts."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def main():
    history = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramHistory.java")
    storage = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
    chat = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")

    real_id = history.split("public static boolean isRealId(Integer id)", 1)[1].split("}", 1)[0]
    assert "id > 0" in real_id and "!MessageObject.isEphemeralMessageId(id)" in real_id

    snapshot = history.split("public static boolean isArchivableSnapshot", 1)[1].split("/**", 1)[0]
    for guard in (
        "!DialogObject.isEncryptedDialog(dialogId)",
        "!MessageObject.isEphemeral(message)",
        "message.ttl_period == 0",
        "message.media.ttl_seconds == 0",
    ):
        assert guard in snapshot, f"snapshot eligibility is missing {guard}"

    retain = history.split("public static boolean canRetainDeletedMessage", 1)[1].split("private static boolean isBotDialog", 1)[0]
    assert "isArchivableSnapshot(dialogId, message, ACTION_DELETED)" in retain
    assert "isUserDeleted(account, dialogId, message.id)" in retain
    assert "PengramLocalKeep.keepDeletedMessages()" in retain
    assert "PengramConfig.isSavingDeleted(account)" in retain
    assert "isSavingOutgoing(account)" in retain and "isSavingInBots(account)" in retain

    keep = history.split("public static boolean keepInChat", 1)[1].split("public static boolean marksEnabled", 1)[0]
    assert "dialogId == 0" in keep and "DialogObject.isEncryptedDialog(dialogId)" in keep
    assert "if (!isRealId(id))" in keep, "mixed draft/ephemeral IDs must not be blanket-retained"

    # Storage keeps only IDs backed by a safe candidate and removes the rest
    # through Telegram's ordinary deletion path, even for mixed batches.
    assert "PengramHistory.canRetainDeletedMessage(currentAccount, did, message)" in storage
    assert "final ArrayList<Integer> retainableDeleted = pengramSaveDeleted(dialogId, messages)" in storage
    assert "keepInChat(currentAccount, dialogId, retainableDeleted)" in storage
    assert "messages = deleteNormally;" in storage

    # UI never sets a deleted ghost flag if snapshot capture was rejected, and
    # processing removes only kept IDs rather than swallowing the whole update.
    helper = chat.split("private ArrayList<Integer> pengramKeepDeletedMessages", 1)[1].split("private void pengramUnselect", 1)[0]
    assert "canRetainDeletedMessage(currentAccount, deletedDialogId, obj.messageOwner)" in helper
    assert "saveDeletedSnapshot(currentAccount, deletedDialogId, obj.messageOwner)" in helper
    assert helper.index("saveDeletedSnapshot") < helper.index("obj.pengramDeleted = true;")
    assert "return keptIds;" in helper
    assert "markAsDeletedMessages.removeAll(keptSet);" in chat

    # A failed marks read is not cached as a successful empty result; turning
    # the setting on in an already-open chat requests the missing load.
    load = history.split("public static void loadMarks", 1)[1].split("public static void unmarkDeleted", 1)[0]
    assert "marksLoadingDialogs.add(key)" in load
    assert "boolean querySucceeded = false;" in load
    assert "if (querySucceeded)" in load and "marksLoadedDialogs.add(key);" in load
    assert "marksLoadingDialogs.remove(key);" in load
    assert "marksLoadFailures" in load
    assert "marksCacheGeneration.get()" in load
    assert "generation == marksCacheGeneration.get()" in load
    assert "marksCacheGeneration.incrementAndGet();" in history
    assert "PengramHistory.loadMarks(currentAccount, dialog_id, null);" in chat

    print("OK: TTL/ephemeral/secret and unarchivable deletions cannot remain as normal unmarked chat messages")


if __name__ == "__main__":
    main()
