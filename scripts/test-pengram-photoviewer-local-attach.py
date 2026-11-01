#!/usr/bin/env python3
"""Regression guard for PhotoViewer rendering of local photo attachPath files."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
source = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java").read_text(encoding="utf-8")

branch_start = source.find("if (messageObject != null && messageObject.isPhoto() && !TextUtils.isEmpty(messageObject.messageOwner.attachPath))")
assert branch_start >= 0, "PhotoViewer must handle photos with a local attachPath"
branch_end = source.find("long[] size = new long[1];", branch_start)
assert branch_end > branch_start, "local-photo path branch must precede Telegram-location fallback"
branch = source[branch_start:branch_end]

assert "new File(messageObject.messageOwner.attachPath)" in branch, "read the real local attachment file"
assert "localPhotoFile.isFile() && localPhotoFile.length() > 0" in branch, "ignore missing or empty stale paths"
assert "ImageLocation.getForPath(localPhotoFile.getAbsolutePath())" in branch, "feed ImageLoader the direct local path"
assert "imageReceiver.setImage(" in branch and "imageReceiver.setMark(null)" in branch, "render local bytes without deferred remote loading"
assert "return;" in branch, "do not fall through to the unavailable synthetic Telegram location"

# Keep this fallback below the new branch: synthetic TL_fileLocationUnavailable cannot resolve
# the actual downloaded JPEG stored at attachPath.
assert source.find("ImageLocation imageLocation = getImageLocation(index, size);", branch_start) > branch_end
print("ok: PhotoViewer renders existing local photo attachments directly and skips invalid synthetic locations")
