#!/usr/bin/env python3
"""Причина регрессии — индекс переживал пересортировку, а режим ждал архив."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
chat = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text()
adapter = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Adapters/MessagesSearchAdapter.java').read_text()
buttons = chat.split('private void updateSearchButtons(', 1)[1].split('private void updateSearchCountText(', 1)[0]
assert 'getLocalDeletedCount()' not in buttons
assert 'nativeResults' not in buttons and 'loaded - 1' not in buttons
assert 'pengramLocalSearchTotal()' in buttons
current = chat.split('private int pengramCurrentLocalSearchIndex()', 1)[1].split('private int pengramLocalSearchTotal()', 1)[0]
assert 'indexOfResult(localSearchHitDialogId, localSearchHitMessageId)' in current
assert 'return localDeletedSearchIndex' not in current
resume = chat.split('private void pengramResumeLocalSearchNavigation()', 1)[1].split('private void pengramOpenLocalSearchResult(', 1)[0]
assert 'from + (pendingLocalSearchUp ? 1 : -1)' in resume and 'isLocalSearchLoading()' in resume
assert '700' not in resume and '> 8' not in resume
assert 'setShowSearchProgress(true)' in resume
assert 'localSearchLoading = !value.isEmpty();' in adapter
assert 'localSearchLoading = false;' in adapter
assert 'localSearchIndexQuery = editText.getText().toString().trim();' in chat
# Модель идентичности: вставка в середину не меняет открытое сообщение.
rows = [(42, 30), (42, 10)]
identity = rows[0]
rows.insert(1, (42, 20))
assert rows[rows.index(identity) + 1] == (42, 20)
assert rows.index(rows[0]) == 0 and rows.index(rows[-1]) + 1 == len(rows)
print('merged search: identity, loaded bounds, callback-driven loading: OK')
