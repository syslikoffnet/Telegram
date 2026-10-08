#!/usr/bin/env python3
"""
Генератор поискового индекса настроек Pengram.

Поиск в настройках Telegram должен находить ИМЕННО функции — не заголовки секций,
не пояснения под ними и не варианты выбора. Единственный надёжный источник правды о
том, что является функцией, — сам экран настроек: каждая строка там создаётся через
check/checkInfo/subCheck/tgCheck/liteCheck/asSettingsCell/asButton/sectionRow.

Скрипт разбирает PengramSettingsActivity.java и выписывает заголовки таких строк
вместе с разделом, в котором они живут, в PengramSearchIndex.java.

Запуск:  python3 scripts/gen-search-index.py
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
OUT = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java")

FILL_TO_SECTION = {
    "fillRoot": "SECTION_ROOT",
    "fillGeneral": "SECTION_GENERAL",
    "fillCustom": "SECTION_CUSTOM",
    "fillProfile": "SECTION_PROFILE",
    "fillHistory": "SECTION_HISTORY",
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
    "UItem.asRoundCheckbox(", "UItem.asExpandableSwitch(",
    "sectionRow(", "aboutRow(",
)
# всё, что функцией не является
SKIP_MARKERS = (
    "UItem.asHeader(", "UItem.asShadow(", "UItem.asCustom(", "UItem.asCustomShadow(",
    "UItem.asRadio", "UItem.asIntSlideView", "moreButton(", "UItem.asSpace",
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
    for section, raw in statements:
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
        entries.append(key)

    if not entries:
        print("не нашёл ни одной строки — проверь разметку PengramSettingsActivity", file=sys.stderr)
        return 1

    body = "\n".join(
        "            {R.string.%s, PengramSettingsActivity.%s}," % (name, sec)
        for name, sec in entries
    )
    out = """package org.telegram.ui;

import org.telegram.messenger.R;

/**
 * Поисковый индекс настроек Pengram: только сами функции и разделы, в которых они лежат.
 *
 * Файл СГЕНЕРИРОВАН скриптом scripts/gen-search-index.py по экрану настроек.
 * Руками не править — добавил настройку, перегенерировал:
 *   python3 scripts/gen-search-index.py
 */
public final class PengramSearchIndex {

    private PengramSearchIndex() {}

    /** пары {строка заголовка, раздел настроек} */
    public static final int[][] ITEMS = {
%s
    };
}
""" % body

    with open(OUT, "w", encoding="utf-8") as f:
        f.write(out)
    print("записано %d пунктов в %s" % (len(entries), os.path.relpath(OUT, ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
