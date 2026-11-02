#!/usr/bin/env python3
"""Prevent the chat's deleted/edited viewer from leaking ACTION_SAVED full-chat copies."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
history = (JAVA / "messenger/PengramHistory.java").read_text()
archive = (JAVA / "ui/PengramHistoryChatActivity.java").read_text()
chat = (JAVA / "ui/ChatActivity.java").read_text()

assert "FILTER_CHANGES = 4" in history
page = history.split("public static void loadArchivePage(", 1)[1].split("public interface PageCallback", 1)[0]
entries = history.split("public static ArrayList<Entry> getEntries(", 1)[1].split("public static", 1)[0]
for query in (page, entries):
    changes = query.split("filter == FILTER_CHANGES", 1)[1].split("} else if", 1)[0]
    assert 'action IN (?, ?)' in changes
    assert "ACTION_DELETED" in changes and "ACTION_EDITED" in changes
    assert "ACTION_SAVED" not in changes

reload = archive.split("private void reload(boolean keepScroll)", 1)[1].split("private void applyEntries", 1)[0]
assert "mode == MODE_ALL" in reload and "filter = PengramHistory.FILTER_CHANGES" in reload
assert "mode == MODE_SAVED" in reload and "filter = PengramHistory.FILTER_SAVED" in reload

# The chat-menu actions remain distinct: View Deleted = changes, Saved from this chat = saved.
assert "private void openPengramHistory()" in chat
assert "openPengramHistory(PengramHistoryChatActivity.MODE_ALL)" in chat
assert "openPengramHistory(PengramHistoryChatActivity.MODE_SAVED)" in chat
assert "id == pengram_saved_media" in chat and "openPengramSavedHistory();" in chat
assert "PengramHistoryChatActivity.MODE_ALL" not in chat.split("id == pengram_saved_media", 1)[1].split("return;", 1)[0]

print("archive scope regression checks passed: deleted viewer excludes full saved chat history")
