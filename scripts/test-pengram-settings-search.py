#!/usr/bin/env python3
"""Pengram: поиск по настройкам — релевантность, точный переход и сохранение переходов.

Проверяем:
  * в корпус поиска попадают только собственные слова строки, а не весь путь;
  * переход из выдачи и из «Недавних» ведёт строго в целевую строку;
  * в «Недавних» сохраняется НЕ сырой текст запроса, а сам переход (стабильный ID
    из PengramSearchIndex.IDS, независимый от aapt2 R.string и порядка элементов),
    как в поиске по настройкам официального Telegram.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = (ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java').read_text()
INDEX = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java').read_text()
META = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchMetadata.java').read_text()
SETTINGS = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java').read_text()
PROFILE = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java').read_text()

# --- корпус и релевантность ---------------------------------------------------
assert 'normalized(tags(entry)).split(" +")' in META, 'корпус должен строиться только по своим словам строки'
assert 'normalized(path(entry) + " " + tags(entry))' not in META, 'путь не должен участвовать в совпадении'
assert 'public static int relevance(int[] entry, String[] terms)' in META
assert 'public static boolean matches(int[] entry, String[] terms)' in META
assert 'return relevance(entry, terms) > 0;' in META
assert re.search(r'return \(prefix \? 420 : 340\)', META)
assert 'return 100;' in META and 'return 220;' in META
assert 'private static boolean wordMatches(' in META

# --- выдача: точный переход и отсутствие «шума» -------------------------------
assert 'PengramSearchMetadata.relevance(entry, terms)' in SETTINGS
assert 'Collections.sort(order, (a, b) ->' in SETTINGS
assert 'result.object = entry[0];' in SETTINGS, 'результат должен помнить свой строковый ресурс'
assert 'private void pengramTagRows(ArrayList<UItem> items)' in SETTINGS
assert 'pengramTagRows(items);' in SETTINGS
assert 'item.object instanceof Integer && (Integer) item.object == stringRes' in SETTINGS
assert 'private void pengramJumpTo(int stringRes, int tries)' in SETTINGS
assert "if (tries < 12 && listView.adapter.getItemCount() < 3)" in SETTINGS
assert SETTINGS.count('TextUtils.equals(target, item.text)') == 2
assert 'R.string.PengramSearchParentOff' in SETTINGS

# --- стабильные ID переходов в PengramSearchIndex -----------------------------
item_rows = re.findall(r'\{R\.string\.\w+, PengramSettingsActivity\.SECTION_\w+,', INDEX)
id_rows = re.findall(r'0x([0-9a-f]{8}), // \w+ \(SECTION_\w+\)', INDEX)
assert len(item_rows) == len(id_rows) and len(id_rows) >= 400, (len(item_rows), len(id_rows))
assert len(set(id_rows)) == len(id_rows), 'коллизия стабильных ID в PengramSearchIndex.IDS'

# --- сохранение именно переходов (как в официальном поиске Telegram) ----------
assert 'private static final String KEY_SEARCH_RECENT = "settingsSearchRecentEntries"' in CONFIG
assert 'public static java.util.ArrayList<Integer> getRecentSearchIds()' in CONFIG
assert 'public static void addRecentSearchId(int stableId)' in CONFIG
assert 'public static void removeRecentSearchId(int stableId)' in CONFIG
assert 'public static void clearSearchHistory()' in CONFIG
assert 'addSearchHistory(String query)' not in CONFIG, 'сырой текст запроса больше не должен сохраняться'

assert 'public static int stableId(int[] entry)' in META
assert 'public static int[] entryByStableId(int stableId)' in META
assert 'public static void rememberEntry(int[] entry)' in META
assert 'public static void removeRecentEntry(int[] entry)' in META
assert 'public static ArrayList<int[]> recentEntries()' in META

# нажатие Enter на клавиатуре лишь прячет клавиатуру, а не пишет сырой запрос
assert 'public void onSearchPressed(EditText editText)' in SETTINGS
assert 'pengramRememberSearch' not in SETTINGS
assert 'pengramApplySearch' not in SETTINGS

# и выдача, и список «Недавние» открывают целевой переход через единый метод
assert 'final ArrayList<int[]> recent = PengramSearchMetadata.recentEntries();' in SETTINGS
assert 'PengramSearchMetadata.emphasizedPath(entry, "")' in SETTINGS
assert 'private void pengramOpenSearchEntry(int[] found)' in SETTINGS
assert 'pengramOpenSearchEntry(recentSearchEntries.get(index))' in SETTINGS
assert 'pengramOpenSearchEntry(searchResults.get(item.id - BTN_SEARCH_BASE))' in SETTINGS
assert 'PengramSearchMetadata.removeRecentEntry(entry)' in SETTINGS
assert 'BTN_SEARCH_BASE && item.id < BTN_SEARCH_HISTORY_BASE' in SETTINGS
assert 'PengramSearchMetadata.stableId(entry)' in PROFILE

# --- строки в обеих локалях ---------------------------------------------------
needed = ['PengramSearchRecent', 'PengramSearchRemoveRecent', 'PengramSearchHistoryClear', 'PengramSearchParentOff']
for locale in ('values', 'values-ru'):
    names = {n.get('name') for n in ET.parse(
        ROOT / f'TMessagesProj/src/main/res/{locale}/strings.xml').iter('string')}
    for key in needed:
        assert key in names, (locale, key)

print('Pengram settings search: relevance, exact jump and transition history: OK')
sys.exit(0)
