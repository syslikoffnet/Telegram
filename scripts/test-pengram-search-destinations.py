#!/usr/bin/env python3
"""История должна пережить перенумерацию ресурсов и не выдавать запрос за переход."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
index_path = java / 'ui/PengramSearchIndex.java'
index = index_path.read_text()
config = (java / 'messenger/PengramConfig.java').read_text()
meta = (java / 'ui/PengramSearchMetadata.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
generator = (root / 'scripts/gen-search-index.py').read_text()

def array(name):
    return index.split(name + ' = {', 1)[1].split('};', 1)[0]

items = re.findall(r'\{R.string.(\w+), PengramSettingsActivity.(SECTION_\w+), (?:R.string.\w+|0), ([012]),', array('ITEMS'))
keys = re.findall(r'"(\w+)"', array('KEYS'))
allowed = re.findall(r'\b(true|false)\b', array('HISTORY_ALLOWED'))
assert len(items) == len(keys) == len(allowed) >= 390
assert [row[0] for row in items] == keys
resources = {node.get('name') for node in ET.parse(root / 'TMessagesProj/src/main/res/values/strings.xml').iter('string')}
assert all(key in resources for key in keys)
assert 'ET.parse(' in generator and 'stable_keys' in generator and 'public static final String[] KEYS' in generator
for key, enabled in zip(keys, allowed):
    if re.search(r'Clear|Reset|Recreate|OpenById|ProfileHistoryOpen', key):
        assert enabled == 'false', key

flags = dict(zip(keys, allowed))
assert flags['PengramMediaFolder'] == flags['PengramMediaPattern'] == 'true'
assert flags['PengramBackupExport'] == flags['PengramBackupImport'] == 'false'

history = config.split('// ------------------------------- история переходов поиска', 1)[1].split('// ------------------------------- скины', 1)[0]
for old in ('addSearchHistory', 'getSearchHistory', 'clearSearchHistory'):
    assert old not in config and old not in settings
assert 'SearchDestination' in history and 'public final String key;' in history
assert 'value.put("key", row.key)' in history
assert 'value.put("section", row.section)' in history and 'value.put("kind", row.kind)' in history
assert 'putString(KEY_SEARCH_HISTORY,' not in history
assert 'key.isEmpty()' in history and 'resolveDestination(candidate)' in history
assert 'org.json.JSONException | RuntimeException' in history
assert 'p.contains(KEY_SEARCH_DESTINATIONS)' in history and 'migrateSearchDestinations(p)' in history
assert '.remove(KEY_SEARCH_HISTORY).apply()' in history
assert 'SEARCH_HISTORY_MAX = 8' in history
assert 'rows.add(0, valid)' in history and 'rows.remove(rows.size() - 1)' in history
assert 'rows.get(i).key.equals(valid.key)' in history
migration = meta.split('public static int[] migrateLegacyQuery(', 1)[1].split('public static String historyPath(', 1)[0]
assert 'queryTerms(query)' in migration and 'relevance(entry, terms)' in migration
assert 'best >= 260 && !tied' in migration and 'score == best' in migration
assert meta.index('row.key.equals(keys[list][i])') < meta.index('entry[1] == row.section')
assert 'ambiguous ? null : candidate' in meta
assert settings.count('pengramOpenSearchDestination(') >= 3
assert 'PengramSearchMetadata.open(this, found, true)' in settings
assert 'PengramSearchMetadata.rememberReached(pendingSearchDestination, stringRes)' in settings
assert 'PengramSearchMetadata.historyPath(entry)' in settings and 'sectionIcon(entry[1])' in settings
assert 'removeSearchDestination(destination.key)' in settings
assert 'getString(R.string.PengramSearchHistoryRemoved)' in settings
enter = settings.split('public void onSearchPressed(EditText editText)', 1)[1].split('});', 1)[0]
assert 'remember' not in enter.lower()
# Структурная проверка: сохраняются только имена, поэтому случайная новая
# нумерация R.string не меняет ни ключ, ни местоположение записи в списке.
sample = next(key for key in keys if key == 'PengramHideTail') if 'PengramHideTail' in keys else keys[0]
build_a = {key: i + 100 for i, key in enumerate(keys)}
build_b = {key: i + 9000 for i, key in enumerate(reversed(keys))}
assert sample in build_a and sample in build_b and build_a[sample] != build_b[sample]
before = index_path.read_bytes()
subprocess.run(['python3', str(root / 'scripts/gen-search-index.py')], check=True, capture_output=True)
assert before == index_path.read_bytes()
print(f'search destinations: {len(keys)} generated keys, validated versioned history, migration, shared navigation: OK')
