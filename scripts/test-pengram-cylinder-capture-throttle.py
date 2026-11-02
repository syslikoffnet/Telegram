#!/usr/bin/env python3
"""Guard the pre-33 cylinder fallback against per-frame software row snapshots."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderWarp.java").read_text()
capture = source.split("private Capture capture(View child, int w, int h)", 1)[1].split("@Override\n    public void onChildInvalidate", 1)[0]

assert "MIN_CAPTURE_INTERVAL_MS = 33L" in source
assert "long lastCaptureUptime" in source
assert "boolean refreshPosted" in source
assert capture.index("child.postInvalidateDelayed(remaining);") < capture.index("child.draw(onto);")
assert "capture.lastCaptureUptime = SystemClock.uptimeMillis();" in capture
assert "capture.refreshPosted = false;" in capture
print("cylinder capture throttle regression checks passed")
