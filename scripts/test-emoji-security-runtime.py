#!/usr/bin/env python3
"""Запускать с зависимостями emoji/requirements.txt; вредные входы не попадают в APK."""
import io
from pathlib import Path
import struct
import sys
import tempfile
import zlib
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'TMessagesProj/emoji'))
import numpy as np
from PIL import Image
from image_safety import image_array, open_image, build_path, MAX_ENCODED_BYTES
from stage_cache import read_cache, write_cache
from optimizer_core import png_read


def reject(data):
    try:
        with open_image(io.BytesIO(data)):
            pass
    except (ValueError, OSError, Image.DecompressionBombError):
        return
    raise AssertionError('Unsafe image accepted')


for mode in ('RGBA', 'RGB', 'L', 'P'):
    with Image.new(mode, (64, 64)) as image:
        buffer = io.BytesIO()
        image.save(buffer, format='PNG')
    data = buffer.getvalue()
    assert image_array(io.BytesIO(data), 'RGBA').shape == (64, 64, 4)
    assert png_read(data)['rgba'].shape == (64, 64, 4)
    reject(data[:20])
reject(b'X' * (MAX_ENCODED_BYTES + 1))
with Image.new('RGBA', (65, 64)) as image:
    buffer = io.BytesIO(); image.save(buffer, format='PNG')
reject(buffer.getvalue())
# Заголовок PNG с огромными размерами: отклоняется до выделения пиксельного буфера.
chunk = b'IHDR' + struct.pack('>IIBBBBB', 100000, 100000, 8, 6, 0, 0, 0)
reject(b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + chunk + struct.pack('>I', zlib.crc32(chunk)))
with Image.new('RGBA', (64, 64)) as image:
    buffer = io.BytesIO(); image.save(buffer, format='WEBP', lossless=True)
assert image_array(io.BytesIO(buffer.getvalue()), 'RGBA').shape == (64, 64, 4)
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    value = {'key': 'hash', 'value': [(np.arange(16, dtype=np.uint8).reshape(4, 4), b'abc', np.int64(2))]}
    path = root / 'cache.json'
    write_cache(path, value)
    saved = read_cache(path)
    assert saved['key'] == value['key']
    assert np.array_equal(saved['value'][0][0], value['value'][0][0])
    assert saved['value'][0][1:] == (b'abc', 2)
    for name in ('../escape', '/tmp/escape'):
        try: build_path(root, name)
        except ValueError: pass
        else: raise AssertionError('Traversal accepted')
    (root / 'link').symlink_to(root.parent, target_is_directory=True)
    try: build_path(root, 'link/escape')
    except ValueError: pass
    else: raise AssertionError('Symlink escape accepted')
print('emoji runtime: PNG/WebP/palette, truncated/bomb/oversized input, cache and paths: OK')
