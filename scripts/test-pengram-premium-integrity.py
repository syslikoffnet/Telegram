#!/usr/bin/env python3
"""A legacy local-Premium bit must never overwrite server subscription state."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java"


def main():
    text = SOURCE.read_text(encoding="utf-8")
    start = text.index("public void pengramApplyLocalPremiumStatus()")
    end = text.index("/**", start)
    method = text[start:end]
    assert 'PengramConfig.getBool("localPremiumStatusApplied", false)' in method
    assert 'PengramConfig.setBool("localPremiumStatusApplied", false)' in method
    assert "user.premium = false" not in method
    assert "cached.premium = false" not in method
    assert "saveConfig(true)" not in method
    assert "isPremium()" in text and "return user.premium;" in text, \
        "actual Premium must remain sourced from the server-backed user object"
    print("OK: legacy local-Premium migration clears only its marker, never real Premium")


if __name__ == "__main__":
    main()
