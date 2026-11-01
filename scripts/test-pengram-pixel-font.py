#!/usr/bin/env python3
"""Regression checks for the bundled, Cyrillic-capable pixel UI font."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
font_java = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/PengramFonts.java").read_text(encoding="utf-8")
asset = ROOT / "TMessagesProj/src/main/assets/fonts/PixelifySans-Regular.ttf"
license_file = ROOT / "TMessagesProj/src/main/assets/fonts/OFL-PixelifySans.txt"

assert asset.is_file() and 50_000 < asset.stat().st_size < 250_000, "pixel font asset must be present and reasonably sized"
assert license_file.is_file() and "SIL OPEN FONT LICENSE Version 1.1" in license_file.read_text(encoding="utf-8"), "bundle the font's OFL 1.1 license"
assert "public static final int PIXEL = 12" in font_java, "pixel font ID must be stable and unique"
assert "PIXEL" in font_java.split("private static final int[] ALL", 1)[1].split("};", 1)[0], "include it in the selectable font list"
assert '"fonts/PixelifySans-Regular.ttf"' in font_java, "load the bundled asset on every supported Android version"
assert "case PIXEL: return R.string.PengramFontPixel;" in font_java, "provide a localized picker label"
assert "if (font == PIXEL)" in font_java and "Typeface.create(base, style)" in font_java, "support normal/bold/italic variants"
constructor = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PengramConstructorActivity.java").read_text(encoding="utf-8")
assert "PengramFonts.name(font)" in constructor, "the live constructor preview must show the selected font name"

for rel in ("TMessagesProj/src/main/res/values/strings.xml", "TMessagesProj/src/main/res/values-ru/strings.xml"):
    root = ET.parse(ROOT / rel).getroot()
    assert any(item.attrib.get("name") == "PengramFontPixel" for item in root.findall("string")), f"missing localized label in {rel}"

print("ok: pixel font, OFL license, Android asset loader, and EN/RU picker labels")
