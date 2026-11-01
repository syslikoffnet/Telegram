#!/usr/bin/env python3
"""A revoked account intentionally kept local must not be resumed by app lifecycle."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def main():
    local = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramLocalKeep.java")
    connection = read("TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java")
    assert "native_pauseNetwork(account);" in local
    assert "localOnly.add(account);" in local
    disable = local[local.index("public static void setEnabled(boolean value)"):local.index("/** чат остаётся")]
    assert "localOnly.clear();" in disable and "native_resumeNetwork(resume.get(i), false);" in disable
    resume = connection[connection.index("public void resumeNetworkMaybe()"):connection.index("public void updateDcSettings()")]
    assert "PengramLocalKeep.isLocalOnly(currentAccount)" in resume
    lifecycle = connection[connection.index("public void setAppPaused("):connection.index("@Keep", connection.index("public void setAppPaused("))]
    guard = lifecycle.index("PengramLocalKeep.isLocalOnly(currentAccount)")
    resume_call = lifecycle.index("native_resumeNetwork(currentAccount, false)")
    assert guard < resume_call
    assert "lastPauseTime = 0;" in lifecycle[guard:resume_call]
    print("OK: lifecycle resumes respect local-only state; disabling it releases the network pause")


if __name__ == "__main__":
    main()
