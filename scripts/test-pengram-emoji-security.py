#!/usr/bin/env python3
"""Проверяем все точки декодирования, а не наличие слова MAX_IMAGE_PIXELS."""
import ast
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'TMessagesProj/emoji'
for path in root.rglob('*.py'):
    source = path.read_text()
    tree = ast.parse(source)
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        name = ast.unparse(node.func)
        assert name not in ('eval', 'exec', 'pickle.load', 'pickle.loads', 'yaml.load'), (path, name)
        for kw in node.keywords:
            assert not (kw.arg in ('allow_pickle', 'shell') and isinstance(kw.value, ast.Constant) and kw.value.value is True), path
        if name == 'Image.open':
            assert path.name == 'image_safety.py', path
            assert any(kw.arg == 'formats' for kw in node.keywords)
    if path.name != 'image_safety.py':
        assert 'Image.open(' not in source, path
safe = (root / 'image_safety.py').read_text()
assert 'Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS' in safe
assert 'Image.DecompressionBombWarning' in safe and 'Image.DecompressionBombError' in safe
assert 'check.verify()' in safe
assert safe.index('check.width * check.height') < safe.index('image.load()')
assert 'with Image.open' in safe and 'with open_image(source)' in safe
assert 'MAX_ENCODED_BYTES + 1' in safe and 'source.close()' in safe
assert 'tempfile.NamedTemporaryFile' in (root / 'stage_cache.py').read_text()
assert 'dtype.hasobject' in (root / 'stage_cache.py').read_text()
assert 'Pillow==13.0.0' in (root / 'requirements.txt').read_text()
print('emoji security: bounded verified decoding, no executable deserialization/shell: OK')
