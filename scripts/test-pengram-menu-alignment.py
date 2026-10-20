#!/usr/bin/env python3
"""Меню по долгому нажатию: крайние пункты вровень и один стиль на всё меню.

Пользователь видел, что первый и последний пункты «съезжают»: скругление
доставалось разделителю или скрытой строке, а строка с галочкой без иконки
начинала текст на 9dp левее соседей. Здесь проверяется, что крайние строки
считаются по видимым пунктам, а не по индексу, и что подложка меню следует
единому переключателю Material 3.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent

chat = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text()
items = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/ItemOptions.java').read_text()
sub = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarMenuSubItem.java').read_text()
popup = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java').read_text()

# --- крайние пункты считаются по реально добавленным строкам ------------------
assert 'int firstCellIndex = -1;' in chat and 'int lastCellIndex = -1;' in chat
assert 'a == firstCellIndex && !islandAtTop, a == lastCellIndex && !islandAtBottom' in chat

# --- скругление не достаётся разделителям и скрытым строкам -------------------
assert 'item instanceof ActionBarPopupWindow.GapView' in items
assert 'item.getVisibility() != View.VISIBLE' in items
assert "((ActionBarMenuSubItem) first).updateSelectorBackground(true, first == last, 12);" in items
assert "((ActionBarMenuSubItem) last).updateSelectorBackground(last == first, true, 12);" in items

# --- строка с галочкой выравнивается по иконочной колонке ---------------------
assert 'public void alignTextWithIcons()' in sub
assert 'textView.setPadding(dp(43), 0, 0, 0);' in sub
assert '((ActionBarMenuSubItem) item).alignTextWithIcons();' in items

# --- Material 3 задаёт форму всего меню, а не только полосы островка ----------
assert 'setRadius(org.telegram.messenger.PengramMD3.menus() ? dp(16) : dp(12))' in chat
assert 'backgroundDrawable = org.telegram.messenger.PengramMD3.menuBackground(backgroundDrawable);' in popup
assert 'final Drawable pill = Theme.createRadSelectorDrawable(selectorColor,' in sub
assert 'new android.graphics.drawable.InsetDrawable(pill, dp(8), dp(2), dp(8), dp(2))' in sub

print('menu alignment: visible-edge rounding, icon column and MD3 container: OK')
