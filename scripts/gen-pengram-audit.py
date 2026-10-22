#!/usr/bin/env python3
"""Reproducible lexical inventory; references are evidence, NOT semantic acceptance."""
import json
import re
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / 'TMessagesProj/src/main/java'
CONFIG = JAVA / 'org/telegram/messenger/PengramConfig.java'
OUT = ROOT / 'docs/pengram-audit-inventory.json'
CANDIDATES = 'isResendDeletedAsMine isSharePhoneOptionHidden isEmptyCoverHidden isLocalPremium getVoiceGainDriveForPreset isHidingEditedLabel keepStyleOnType getIslandHeight isAccentFill createQueueRow cyclePanelView hideShiftHintNow applyAccentToViews toggleSystemEmoji toggleInterfaceAnimations isPrimaryVoiceMode getTelegramVersion quickUrlPreview tabStateText'.split()

def inventory():
    config = CONFIG.read_text()
    keys = {}
    for m in re.finditer(r'\b(KEY_\w+)\s*=\s*([^;]+);', config):
        keys[m[1]] = {'expression': m[2].strip(), 'definition': 'TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java:' + str(config.count('\n', 0, m.start()) + 1), 'references': []}
    definitions = {}
    declaration = re.compile(r'^\s*(?:(?:public|private|protected|static|final|synchronized|native)\s+)+[\w.$<>, ?\[\]]+\s+(\w+)\s*\(', re.M)
    for path in sorted(JAVA.rglob('*Pengram*.java')):
        text = path.read_text()
        for m in declaration.finditer(text):
            definitions.setdefault(m[1], []).append(f'{path.relative_to(ROOT)}:{text.count(chr(10), 0, m.start())+1}')
    method_counts = {name: {'declarations': refs, 'lexical_occurrences': 0, 'examples': []} for name, refs in definitions.items()}
    calls = re.compile(r'\b([A-Za-z_]\w*)\s*\(|::([A-Za-z_]\w*)')
    methods = {name: [] for name in CANDIDATES}
    dispatch, literals, suspects = {}, [], []
    token = re.compile(r'\b(?:KEY_\w+|(?:BTN|SECTION|MSG_ACTION|CHAT_ITEM|MENU_ITEM)_\w+)\b|' + '|'.join(r'\b'+n+r'\s*\(|::'+n+r'\b' for n in CANDIDATES))
    literal = re.compile(r'\b(getBool|putInt|getIntCached|visualString|setBool|setIntValue|setStringValue|putBoolean|putString)\s*\(\s*"([^"]+)"')
    suspicious = re.compile(r'catch\s*\(Throwable\s+ignore\)|Gravity\.(?:LEFT|RIGHT)\b|0x[fF]{2}[\da-fA-F]{6}\b|\b(?:TODO|FIXME)\b|\breturn\s*;')
    for path in sorted(JAVA.rglob('*.java')):
        text = path.read_text()
        pengram = 'Pengram' in path.name
        for no, line in enumerate(text.splitlines(), 1):
            ref = f'{path.relative_to(ROOT)}:{no}'
            for match in calls.finditer(line):
                name = match[1] or match[2]
                if name in method_counts:
                    info = method_counts[name]
                    info['lexical_occurrences'] += 1
                    if len(info['examples']) < 8: info['examples'].append({'at': ref, 'code': line.strip()})
            for match in token.finditer(line):
                name = match[0].lstrip(':').split('(')[0].strip()
                entry = {'at': ref, 'code': line.strip()}
                if name in keys: keys[name]['references'].append(entry)
                elif name in methods: methods[name].append(entry)
                elif pengram: dispatch.setdefault(name, []).append(entry)
            if pengram:
                for match in suspicious.finditer(line):
                    suspects.append({'kind': match[0], 'at': ref, 'code': line.strip()})
        if pengram:
            for match in literal.finditer(text):
                no = text.count('\n', 0, match.start()) + 1
                literals.append({'key': match[2], 'operation': match[1], 'at': f'{path.relative_to(ROOT)}:{no}'})
    # JNI names and method references are included separately, including exported strings.
    native = []
    for path in sorted((ROOT/'TMessagesProj/jni').rglob('*')):
        if path.suffix not in ('.c', '.cpp', '.h', '.cc'): continue
        for no, line in enumerate(path.read_text(errors='replace').splitlines(), 1):
            for name in CANDIDATES:
                if name in line: native.append({'method': name, 'at': f'{path.relative_to(ROOT)}:{no}', 'code': line.strip()})
    return {'notice': 'LEXICAL inventory, includes declarations/comments; not proof of read/write reachability, dispatch completeness or absence of leaks. Dynamic/composed keys require manual review.', 'keys': keys, 'method_candidates': method_counts, 'literal_operations': literals, 'candidate_methods': methods, 'jni_references': native, 'dispatch_references': dispatch, 'review_candidates': suspects}

if __name__ == '__main__':
    data = inventory()
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n')
    print(f"Inventory: {len(data['keys'])} KEY constants, {len(data['literal_operations'])} literal operations, {len(data['dispatch_references'])} dispatch names, {len(data['review_candidates'])} lexical review candidates")
