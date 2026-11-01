#!/usr/bin/env python3
"""Regression contract for live, truly centered ActionBar titles."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def main():
    bar = read("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBar.java")
    config = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
    assert "private static final WeakHashMap<ActionBar, Boolean> pengramActionBars" in bar
    assert "public static void refreshPengramTitleAlignment()" in bar
    assert "bar.requestLayout();" in bar
    assert "if (pengramCentered())" in bar
    assert "Math.min(center - leftBoundary, rightBoundary - center) * 2" in bar, \
        "center mode must measure a symmetric text lane before layout"
    assert "return (getMeasuredWidth() - measuredWidth) / 2;" in bar, \
        "center mode must place title at the actual ActionBar midpoint"
    assert "if (KEY_TITLE_CENTER.equals(key))" in config
    assert "ActionBar.refreshPengramTitleAlignment();" in config
    print("OK: title alignment uses a centered symmetric lane and refreshes existing headers")


if __name__ == "__main__":
    main()
