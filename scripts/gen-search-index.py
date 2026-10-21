#!/usr/bin/env python3
"""
Генератор поискового индекса настроек Pengram.

Поиск в настройках Telegram должен находить именно доступные строки настроек,
включая радиоварианты и заголовки со слайдером, но не пояснения или произвольные
заголовки секций. Источник правды — экран настроек: check/checkInfo/subCheck,
радиокнопки, asSettingsCell/asButton/sectionRow и подписанные слайдеры.

Скрипт разбирает PengramSettingsActivity.java и выписывает заголовки таких строк
вместе с разделом, в котором они живут, в PengramSearchIndex.java.

Запуск:  python3 scripts/gen-search-index.py
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
OUT = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java")

FILL_TO_SECTION = {
    "fillRoot": "SECTION_ROOT",
    "fillGeneral": "SECTION_GENERAL",
    "fillCustom": "SECTION_CUSTOM",
    "fillProfile": "SECTION_PROFILE",
    "fillHistory": "SECTION_HISTORY",
    "fillHistoryAdvanced": "SECTION_HISTORY",
    "fillGhost": "SECTION_GHOST",
    "fillFreedom": "SECTION_FREEDOM",
    "fillAppearance": "SECTION_APPEARANCE",
    "fillMedia": "SECTION_MEDIA",
    "fillPlayer": "SECTION_PLAYER",
    "fillChats": "SECTION_CHATS",
    "fillChatActions": "SECTION_CHAT_ACTIONS",
    "fillChatMessages": "SECTION_CHAT_MESSAGES",
    "fillTyping": "SECTION_TYPING",
    "fillQuotes": "SECTION_QUOTES",
    "fillMediaTime": "SECTION_CHAT_MESSAGES",
    "fillChatInterface": "SECTION_CHAT_INTERFACE",
    "fillChatMenus": "SECTION_CHAT_MENUS",
    "fillAI": "SECTION_AI",
    "fillAbout": "SECTION_ABOUT",
    "fillLyrics": "SECTION_LYRICS",
}

# строки-функции
ROW_MARKERS = (
    "check(", "checkInfo(", "subCheck(", "tgCheck(", "liteCheck(", "tgCheckInfo(",
    "UItem.asSettingsCell(", "UItem.asButton(", "UItem.asCheck(",
    "UItem.asRadio(", "UItem.asRadio2(",
    "UItem.asRoundCheckbox(", "UItem.asExpandableSwitch(",
    "sectionRow(", "aboutRow(",
)
# всё, что функцией не является
SKIP_MARKERS = (
    "UItem.asHeader(", "UItem.asShadow(", "UItem.asCustom(", "UItem.asCustomShadow(",
    "moreButton(", "UItem.asSpace",
    "asShadowCollapseButton(",
)

FILL_RE = re.compile(r"^\s{4}private\s+void\s+(fill[A-Za-z]*)\s*\(")
RES_RE = re.compile(r"R\.string\.([A-Za-z0-9_]+)")


def main():
    with open(SRC, encoding="utf-8") as f:
        lines = f.readlines()

    # Склеиваем многострочные вызовы items.add(...) в одну строку, иначе заголовок,
    # перенесённый на следующую строку, терялся бы.
    statements = []   # (section, текст)
    section = None
    buffer = None
    depth = 0
    for raw in lines:
        m = FILL_RE.match(raw)
        if m:
            section = FILL_TO_SECTION.get(m.group(1))
            buffer = None
            depth = 0
            continue
        if section is None:
            continue
        if buffer is None:
            if "items.add(" not in raw:
                continue
            buffer = raw.strip()
            depth = raw.count("(") - raw.count(")")
        else:
            buffer += " " + raw.strip()
            depth += raw.count("(") - raw.count(")")
        if depth <= 0:
            statements.append((section, buffer))
            buffer = None
            depth = 0

    entries = []
    seen = set()
    header_by_section = {}
    pending_slider = None
    for section, raw in statements:
        # A labelled slider has no separate cell: its header IS the visible
        # setting. Keep it searchable and use the label as the jump target.
        if pending_slider is not None:
            if "UItem.asIntSlideView(" in raw and section == pending_slider[1]:
                name, sec, parent = pending_slider
                if (name, sec) not in seen:
                    seen.add((name, sec))
                    entries.append((name, sec, parent))
            if "UItem.asIntSlideView(" in raw and section == pending_slider[1] and header_by_section.get(section) == pending_slider[0]:
                header_by_section[section] = pending_slider[2]
            pending_slider = None
        if "UItem.asHeader(" in raw:
            label = RES_RE.search(raw)
            if label:
                name = label.group(1)
                parent = header_by_section.get(section)
                pending_slider = (name, section, parent) if "items.add(UItem.asHeader(" in raw else None
                header_by_section[section] = name
            continue
        if "UItem.asButton(BTN_AI_AUTO_RULE_BASE" in raw:
            continue  # Dynamic chat names are not static setting labels.
        if any(marker in raw for marker in SKIP_MARKERS):
            continue
        if not any(marker in raw for marker in ROW_MARKERS):
            continue
        res = RES_RE.search(raw)
        if not res:
            continue
        name = res.group(1)
        if name.endswith("Info") or name.endswith("Info2") or name.endswith("Header"):
            continue
        key = (name, section)
        if key in seen:
            continue
        seen.add(key)
        entries.append((name, section, header_by_section.get(section)))

    if not entries:
        print("не нашёл ни одной строки — проверь разметку PengramSettingsActivity", file=sys.stderr)
        return 1

    # Dedicated screens reachable from these parent settings rows. Explicit
    # inventory: never index a control unless the owning screen can be opened.
    external = [
        # The mode picker is a custom view, not a static settings row.
        ("PengramVoiceTrolling", "SECTION_MEDIA", "PengramVoiceHeader", 0),
        ("PengramAvatarPos", "SECTION_CHAT_MESSAGES", "PengramChatLook", 1),
        ("PengramDialogAvatars", "SECTION_CHAT_MESSAGES", "PengramChatLook", 1),
        ("PengramProfileHistoryEnable", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileHistoryStorage", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileCloudFormat", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileCloudRecreate", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileHistoryScope", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileHistoryBio", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileHistoryAvatars", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileLimitSize", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileLimitTime", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileHistoryOpen", "SECTION_HISTORY", "PengramProfileHistory", 2),
        ("PengramProfileHistoryClearAvatars", "SECTION_HISTORY", "PengramProfileHistory", 2),
    ]

    resources = ET.parse(os.path.join(ROOT, 'TMessagesProj/src/main/res/values/strings.xml'))
    available = {node.get('name') for node in resources.iter('string')}
    indexed = [(name, sec, group, 0) for name, sec, group in entries]
    indexed += [row for row in external if (row[0], row[1]) not in seen]
    missing = [name for name, _, _, _ in indexed if name not in available]
    if missing:
        raise ValueError('Unknown string resources: ' + ', '.join(missing))
    stable_keys = '\n'.join('            "%s",' % name for name, _, _, _ in indexed)
    # Родитель может быть заголовком, которого нет в обычной выдаче. Его ключ
    # тоже генерируем, чтобы fallback пережил перенумерацию ресурсов.
    groups = sorted({(group, sec) for _, sec, group, kind in indexed
                     if group and group in available and kind == 0})
    group_body = '\n'.join('            {R.string.%s, PengramSettingsActivity.%s, 0, 0, 0},' % pair
                           for pair in groups)
    group_keys = '\n'.join('            "%s",' % name for name, _ in groups)
    action_titles = set()
    # Некоторые кнопки — редакторы значения, а не немедленные действия.
    value_buttons = re.compile(r'UItem.asButton\(BTN_(?:MEDIA_FOLDER|MEDIA_PATTERN|AI_DEPTH|AI_AUTO_MASTER|AI_AUTO_CONTEXT_LIMIT|AI_AUTO_STYLE|AI_AUTO_QUIET|AI_AUTO_COOLDOWN|AI_AUTO_SPAM_COUNT|AI_AUTO_SPAM_PAUSE),')
    for sec, raw in statements:
        if ('UItem.asButton(' in raw and not value_buttons.search(raw)) or re.search(r'BTN_[A-Z_]*(?:CLEAR|RESET|BY_ID|RECREATE|_EXPORT|_IMPORT|_COPY)[A-Z_]*', raw):
            title = RES_RE.search(raw)
            if title:
                action_titles.add((title.group(1), sec))
    history_allowed = '\n'.join('            %s,' % str(
        (name, sec) not in action_titles and not re.search(r'(Clear|Reset|Recreate|OpenById|ProfileHistoryOpen)', name)).lower()
        for name, sec, _, _ in indexed)

    def description(name):
        for suffix in ('Info', 'Subtitle'):
            if name + suffix in available:
                return 'R.string.' + name + suffix
        return '0'

    body = "\n".join(
        "            {R.string.%s, PengramSettingsActivity.%s, %s, 0, %s}," % (name, sec,
            "R.string." + header if header and header != name else "0", description(name))
        for name, sec, header in entries
    )
    body += "\n" + "\n".join(
        "            {R.string.%s, PengramSettingsActivity.%s, R.string.%s, %d, %s}," %
        (name, sec, group, external_screen, description(name))
        for name, sec, group, external_screen in external if (name, sec) not in seen
    )
    out = """package org.telegram.ui;

import org.telegram.messenger.R;

/**
 * Поисковый индекс настроек Pengram: функции, разделы и группы для полного пути.
 *
 * Файл СГЕНЕРИРОВАН скриптом scripts/gen-search-index.py по экрану настроек.
 * Руками не править — добавил настройку, перегенерировал:
 *   python3 scripts/gen-search-index.py
 */
public final class PengramSearchIndex {

    private PengramSearchIndex() {}

    /** {строка, раздел, группа, внешний экран (0=нет), собственное описание (0=нет)} */
    public static final int[][] ITEMS = {
%s
    };

    /** Имена ресурсов, а не их номера: эти ключи допустимо хранить в prefs. */
    public static final String[] KEYS = {
%s
    };
    public static final boolean[] HISTORY_ALLOWED = {
%s
    };
    public static final int[][] GROUP_ITEMS = {
%s
    };
    public static final String[] GROUP_KEYS = {
%s
    };
}
""" % (body, stable_keys, history_allowed, group_body, group_keys)

    with open(OUT, "w", encoding="utf-8") as f:
        f.write(out)
    print("записано %d пунктов в %s" % (len(entries) + sum((name, sec) not in seen for name, sec, _, _ in external), os.path.relpath(OUT, ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
