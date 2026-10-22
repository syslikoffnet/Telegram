#!/usr/bin/env python3
"""Regression contracts for this audit; deliberately not a semantic whole-fork certificate."""
import importlib.util
import json
from pathlib import Path
root=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('audit',root/'scripts/gen-pengram-audit.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
saved=json.loads((root/'docs/pengram-audit-inventory.json').read_text())
assert saved==audit.inventory(), 'Regenerate scripts/gen-pengram-audit.py'
assert len(saved['keys']) >= 160 and len(saved['dispatch_references']) >= 350
for name in ('BTN_FIND_BY_ID','BTN_HIST_PROFILE','BTN_SELECTION_LIMIT'):
    assert name not in saved['dispatch_references'], name
for name in ('createQueueRow','isPrimaryVoiceMode','getTelegramVersion','quickUrlPreview','tabStateText'):
    assert not saved['candidate_methods'][name],name
for name in ('cyclePanelView','hideShiftHintNow','applyAccentToViews','toggleSystemEmoji','toggleInterfaceAnimations'):
    assert len(saved['candidate_methods'][name]) >= 2,name
assert not saved['jni_references']
ui=root/'TMessagesProj/src/main/java/org/telegram/ui'
assert 'LocaleController.isRTL ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT' in (ui/'PengramSettingsActivity.java').read_text()
glass=(ui/'Components/PengramTabBarGlass.java').read_text()
assert 'getGlassGlow()' in glass[glass.index('public static int signature()'):]
for name in ('PengramSettingsActivity.java','Components/PengramPenguinView.java','Components/PengramMusicPlayerSheet.java'):
    text=(ui/name).read_text()
    assert 'TODO' not in text and 'FIXME' not in text
# No new resources here; existing penguin strings must exist in both locales.
for locale in ('values','values-ru'):
    xml=''.join(p.read_text() for p in (root/'TMessagesProj/src/main/res'/locale).glob('*.xml'))
    for name in ('PengramPenguinSize','PengramPenguinAutoSkin','PengramPenguinActionsHeader'):
        assert f'name="{name}"' in xml,(locale,name)
print('audit regression contracts + reproducible inventory PASS; semantic key/dispatch/RTL review remains open')
