#!/usr/bin/env python3
"""Static search-index checks; does not compile or start an Android build."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java'
METADATA = ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSearchMetadata.java'
SETTINGS = ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java'
PROFILE = ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java'
rows = re.findall(r'\{R\.string\.(\w+), PengramSettingsActivity\.(SECTION_\w+), (?:R\.string\.(\w+)|0), ([012]), (?:R\.string\.(\w+)|0)\}', INDEX.read_text())
assert len(rows) >= 344, len(rows)
assert len({(name, section) for name, section, *_ in rows}) == len(rows), 'duplicate destination'
assert not any('PENGUIN' in section or name == 'PengramSectionPenguin' for name, section, *_ in rows)
assert any(name == 'PengramAvatarPos' and external == '1' for name, _, _, external, _ in rows)
assert any(name == 'PengramDialogAvatars' and external == '1' for name, _, _, external, _ in rows)
assert any(name == 'PengramProfileLimitSize' and external == '2' for name, _, _, external, _ in rows)
assert any(name == 'PengramQuoteRadius' for name, *_ in rows), 'labelled slider missing'
assert any(name == 'PengramIdFormatHide' for name, *_ in rows), 'radio choice missing'
assert any(name == 'PengramIdFormatPicker' and section == 'SECTION_PROFILE' for name, section, *_ in rows)
assert any(name == 'PengramIdPlacementPicker' and section == 'SECTION_PROFILE' for name, section, *_ in rows)
# стиль отправки живёт в одном месте — в разделе набора текста
assert any(name == 'PengramSendStyle' and section == 'SECTION_TYPING' for name, section, *_ in rows)
assert not any(name == 'PengramSendStyle' and section == 'SECTION_CHATS' for name, section, *_ in rows)
assert any(name == 'PengramMediaTimeStickers' for name, *_ in rows), 'media radio missing'
assert all(section in SETTINGS.read_text() for _, section, *_ in rows)

resources = {}
for locale in ('values', 'values-ru'):
    path = ROOT / f'TMessagesProj/src/main/res/{locale}/strings.xml'
    resources[locale] = {node.get('name') for node in ET.parse(path).iter('string')}
for name, _, group, _, info in rows:
    assert name in resources['values'], name
    if group: assert group in resources['values'], group
    if info: assert info in resources['values'], info
assert sum(bool(info) for *_, info in rows) >= 80, 'missing per-control descriptions'
for translated in ('PengramAvatarPos', 'PengramDialogAvatars', 'PengramProfileLimitSize'):
    assert translated in resources['values-ru'], translated
assert 'PengramSearchMetadata.relevance(entry, terms)' in SETTINGS.read_text()
assert 'PengramSearchMetadata.matches(result.pengramEntry, pengramTerms)' in PROFILE.read_text()
assert 'searchRevealGroups = true' in SETTINGS.read_text()
assert 'result.adaptiveLayout = true' in SETTINGS.read_text()
assert 'аватарка аву' in METADATA.read_text()
assert 'PengramSearchMetadata.open(this, found)' in SETTINGS.read_text()

before = INDEX.read_bytes()
subprocess.run(['python3', str(ROOT / 'scripts/gen-search-index.py')], check=True, capture_output=True)
assert INDEX.read_bytes() == before, 'generator output is not deterministic'
print(f'Pengram search-index source/resource checks: OK ({len(rows)} rows)')
