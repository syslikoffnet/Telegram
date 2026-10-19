#!/usr/bin/env python3
"""Source-level invariants for the sample-only visual editor (no Android build required)."""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main"
JAVA = ROOT / "java/org/telegram/ui"
PREVIEW = (JAVA / "Components/PengramInteractivePreviewView.java").read_text()
EDITOR = (JAVA / "PengramLayoutEditorActivity.java").read_text()
MOCK = (JAVA / "Components/PengramScreenMockView.java").read_text()
TABS = (JAVA / "Components/PengramTabsMockView.java").read_text()
CONFIG = (ROOT / "java/org/telegram/messenger/PengramConfig.java").read_text()
SETTINGS = (JAVA / "PengramSettingsActivity.java").read_text()


def string_names(locale):
    path = ROOT / "res" / locale / "strings.xml"
    strings = [node.attrib["name"] for node in ET.parse(path).getroot().findall("string")]
    assert len(strings) == len(set(strings)), f"Duplicate string in {path}"
    return set(strings)


def check():
    default, russian = string_names("values"), string_names("values-ru")
    for name, text in {
        "preview": PREVIEW,
        "editor": EDITOR,
        "chat mock": MOCK,
        "tabs mock": TABS,
    }.items():
        referenced = set(re.findall(r"R\.string\.(\w+)", text))
        assert referenced <= default, f"Missing default strings in {name}: {referenced - default}"
        editor_strings = {key for key in referenced if key.startswith("PengramEditor")}
        assert editor_strings <= russian, f"Missing Russian strings in {name}: {editor_strings - russian}"

    # No dialog, contact, call or media controller must ever enter the synthetic preview.
    for forbidden in ("MessagesController", "MediaController", "ContactsController", "getDialogs(", "getPlayingMessageObject("):
        assert forbidden not in PREVIEW + MOCK, f"Live account data in preview: {forbidden}"
    assert "BTN_VISUAL_EDITOR" in SETTINGS and "new PengramLayoutEditorActivity()" in SETTINGS
    assert "case R.string." not in EDITOR, "AGP 8 may generate non-final resource IDs"
    # A real device crashed opening the editor when TextView.setText(int) resolved
    # a non-string/stale ID. Route every panel label through the guarded overload.
    assert "private void setPanelText(TextView target, int resId)" in EDITOR
    assert "resources.getResourceTypeName(resId)" in EDITOR
    assert "catch (Resources.NotFoundException error)" in EDITOR
    assert "catch (Resources.NotFoundException error)" in PREVIEW
    assert "setSafeHint(search, R.string.Search" in PREVIEW
    assert "setSafeHint(draft, R.string.PengramEditorInput" in PREVIEW
    assert "setSafeHint(customTitle, R.string.PengramTitleCustom" in EDITOR
    assert not re.search(r"\.setHint\(\s*R\.string\.", PREVIEW + EDITOR)
    assert not re.search(r"(?:panelTitle|panelHint|title)\.setText\(\s*(?:R\.string\.|titleRes\b|id\b|setting\[0\])", EDITOR)
    root_rows = SETTINGS.split("private void fillRoot(", 1)[1].split("private UItem sectionRow(", 1)[0]
    assert "sectionRow(BTN_VISUAL_EDITOR" in root_rows
    assert "sectionRow(BTN_CONSTRUCTOR" not in root_rows, "No competing editor entry on the root"
    assert "sectionRow(BTN_CONSTRUCTOR" in SETTINGS.split("private void fillAppearance(", 1)[1]
    assert "presentFragment(" not in PREVIEW, "The mock must navigate inside its preview"
    assert "presentFragment(" not in EDITOR and "PengramSearchMetadata.open(this" not in EDITOR, \
        "External settings must not bypass the Save transaction"
    assert "FrameLayout root = new FrameLayout(context)" in EDITOR
    assert "root.addView(preview, new FrameLayout.LayoutParams(-1, -1))" in EDITOR
    assert "panelScroll.setVisibility(View.GONE)" in EDITOR
    assert "visualDraft.save()" in EDITOR and "visualDraft.discard()" in EDITOR
    assert "((MainTabsActivity) fragment).checkPengramTabs();" in EDITOR
    assert "if (!visualDraft.save())" in EDITOR and "showSaveError();" in EDITOR
    assert "if (!editor.commit()) return false;" in CONFIG
    assert "renderSettingsSearchResults(results, settingsSearchQuery)" in PREVIEW
    assert "PengramSearchMetadata.matches(entry, terms)" in PREVIEW
    assert "settingsSection = entry[1];" in PREVIEW
    assert "SETTINGS_SEARCH" in PREVIEW
    assert "SETTINGS_RESULT_BATCH = 60" in PREVIEW
    assert "results.postDelayed(pendingSearch[0], 90)" in PREVIEW
    assert "results.removeCallbacks(pendingSearch[0])" in PREVIEW
    assert "PengramEditorMoreResults" in PREVIEW
    assert "item.setEllipsize(null)" in PREVIEW
    assert "PengramEditorSettingsSearchEmpty" in PREVIEW
    assert "addTitleControls(); return;" in EDITOR
    assert "addDeletedMarkControls(); return;" in EDITOR
    assert "addChatMenuControls(); return;" in EDITOR
    assert "PengramMarkEye" in EDITOR and "PengramEditorApplyDraft" in EDITOR
    assert "private static boolean stageVisual(String key, Object value)" in CONFIG
    assert "if (stageVisual(key, value)) return;" in CONFIG
    assert 'visualString("settingsOrder")' in CONFIG
    assert 'visualString("chatItemsOrder")' in CONFIG
    assert 'visualString("menuOrder")' in CONFIG
    assert "getResourceTypeName(stringId)" in PREVIEW
    assert "mock.setEditorMessageSize(draftRadius, draftFontSize)" in EDITOR
    assert "if (embedded) canvas.drawRect(rect, fill)" in MOCK
    assert "getResources().getDisplayMetrics().heightPixels - dp(170)" in PREVIEW
    assert "SharedConfig.bubbleRadius = radius" in EDITOR.split("private void saveVisualDraft()", 1)[1]
    assert "sampleSent[index] = message.substring(" in PREVIEW, "Sample input must remain local"
    assert "PengramConfig.isDialogSenderAvatars()" in PREVIEW
    assert "addSenderAvatarPositions()" in EDITOR
    assert "settingsSectionHistory.addLast(settingsSection)" in PREVIEW
    assert "restoreSelectedRow();" in PREVIEW and "clearSelectedRow();" in PREVIEW
    assert "settingsSection = settingsSectionHistory.removeLast()" in PREVIEW
    assert "displayPage(SETTINGS_LIST);" in PREVIEW, "Same-page settings navigation must redraw"
    assert "startDragAndDrop" in PREVIEW and "Build.VERSION.SDK_INT >= Build.VERSION_CODES.N" in PREVIEW
    assert "return v.startDrag(" in PREVIEW, "Android 21-23 drag fallback is required"
    assert "((int[]) state).length < 2" in PREVIEW and "((int[]) local).length < 2" in PREVIEW
    assert "SETTINGS_ITEM_PENGRAM && destination == 2" in PREVIEW
    assert "MENU_ITEM_SETTINGS" in PREVIEW and "MENU_ITEM_PENGRAM" in PREVIEW
    assert "UserConfig.getInstance(UserConfig.selectedAccount).showCallsTab" in TABS
    assert "KEY_TAB_CALLS" in PREVIEW and "KEY_TAB_SETTINGS" in PREVIEW
    assert "MotionEvent.ACTION_CANCEL" in MOCK and "event.getAction() == MotionEvent.ACTION_UP ? slotAt" in MOCK
    print("Pengram visual editor source/resource/safety invariants: OK")


if __name__ == "__main__":
    check()
