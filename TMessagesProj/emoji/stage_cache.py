"""Кэш не должен исполнять код: каталог может содержать подменённые файлы."""
import base64
import json
import os
from pathlib import Path
import tempfile
import numpy as np


def encode(value):
    if isinstance(value, np.ndarray):
        if value.dtype.hasobject:
            raise ValueError('Object arrays are not cacheable')
        return {'array': base64.b64encode(value.tobytes()).decode('ascii'),
                'dtype': value.dtype.str, 'shape': list(value.shape)}
    if isinstance(value, np.generic):
        return encode(value.item())
    if isinstance(value, bytes):
        return {'bytes': base64.b64encode(value).decode('ascii')}
    if isinstance(value, tuple):
        return {'tuple': [encode(x) for x in value]}
    if isinstance(value, list):
        return [encode(x) for x in value]
    if isinstance(value, dict):
        return {'dict': [[encode(k), encode(v)] for k, v in value.items()]}
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    raise ValueError('Unsupported cache type')


def decode(value):
    if isinstance(value, list):
        return [decode(x) for x in value]
    if not isinstance(value, dict):
        return value
    if set(value) == {'bytes'}:
        return base64.b64decode(value['bytes'], validate=True)
    if set(value) == {'tuple'}:
        return tuple(decode(x) for x in value['tuple'])
    if set(value) == {'dict'}:
        return {decode(k): decode(v) for k, v in value['dict']}
    if set(value) == {'array', 'dtype', 'shape'}:
        dtype = np.dtype(value['dtype'])
        if dtype.hasobject:
            raise ValueError('Object arrays are forbidden')
        data = base64.b64decode(value['array'], validate=True)
        return np.frombuffer(data, dtype=dtype).reshape(value['shape']).copy()
    raise ValueError('Invalid cache record')


def read_cache(path):
    if Path(path).stat().st_size > 256 * 1024 * 1024:
        raise ValueError('Stage cache exceeds memory budget')
    with Path(path).open('r', encoding='utf-8') as stream:
        return decode(json.load(stream))


def write_cache(path, value):
    path = Path(path)
    name = None
    try:
        # Непредсказуемое имя исключает подмену предсказуемого .tmp симлинком.
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                         prefix='.emoji-cache-', delete=False) as stream:
            name = stream.name
            json.dump(encode(value), stream, separators=(',', ':'))
        os.replace(name, path)
    finally:
        if name is not None and os.path.exists(name):
            os.unlink(name)
