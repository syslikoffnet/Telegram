#!/usr/bin/env python3
"""Pengram: поиск по настройкам — релевантность, точный переход и история.

Проверяем три вещи, которые ломались на практике:
  * в корпус поиска попадал весь путь строки, поэтому запрос «сообщения»
    находил вообще все настройки раздела;
  * переход из выдачи вёл не в ту строку, если такой же текст встречался
    в другом месте экрана;
  * запросы нигде не сохранялись, хотя в Telegram история поиска есть.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = (ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java').read_text()
META = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchMetadata.java').read_text()
SETTINGS = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java').read_text()

# --- корпус и релевантность ---------------------------------------------------
assert 'normalized(tags(entry)).split(" +")' in META, 'корпус должен строиться только по своим словам строки'
assert 'normalized(path(entry) + " " + tags(entry))' not in META, 'путь не должен участвовать в совпадении'
assert 'public static int relevance(int[] entry, String[] terms)' in META
assert 'public static boolean matches(int[] entry, String[] terms)' in META
assert 'return relevance(entry, terms) > 0;' in META
# заголовок весит больше, чем синоним
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
# заголовок группы — последний шанс для строк без собственного текста (слайдеры)
assert SETTINGS.count('TextUtils.equals(target, item.text)') == 2
assert 'R.string.PengramSearchParentOff' in SETTINGS

# --- история запросов ---------------------------------------------------------
assert 'private static final String KEY_SEARCH_HISTORY' in CONFIG
assert 'public static java.util.ArrayList<String> getSearchHistory()' in CONFIG
assert 'public static void addSearchHistory(String query)' in CONFIG
assert 'public static void clearSearchHistory()' in CONFIG
assert 'public void onSearchPressed(EditText editText)' in SETTINGS
assert 'pengramRememberSearch();' in SETTINGS
assert 'BTN_SEARCH_HISTORY_BASE' in SETTINGS and 'BTN_SEARCH_HISTORY_CLEAR' in SETTINGS
assert 'private void pengramApplySearch(String query)' in SETTINGS
assert 'searchItem.getSearchField()' in SETTINGS
assert 'BTN_SEARCH_BASE && item.id < BTN_SEARCH_HISTORY_BASE' in SETTINGS
assert 'history.get(i)' in SETTINGS

# --- строки в обеих локалях ---------------------------------------------------
needed = ['PengramSearchRecent', 'PengramSearchHistoryClear', 'PengramSearchParentOff']
for locale in ('values', 'values-ru'):
    names = {n.get('name') for n in ET.parse(
        ROOT / f'TMessagesProj/src/main/res/{locale}/strings.xml').iter('string')}
    for key in needed:
        assert key in names, (locale, key)

print('Pengram settings search: relevance, exact jump and history: OK')
sys.exit(0)
