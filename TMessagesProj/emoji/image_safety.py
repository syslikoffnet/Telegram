"""Ограничения декодирования: формат пака допускает только текстуры 64×64."""
from contextlib import contextmanager
import io
from pathlib import Path
import warnings
from PIL import Image

MAX_ENCODED_BYTES = 1024 * 1024
MAX_IMAGE_PIXELS = 64 * 64
Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS


@contextmanager
def open_image(source):
    # ZIP-поток тоже закрываем: иначе сотни текстур удерживают дескрипторы.
    if isinstance(source, (str, Path)):
        with open(source, 'rb') as stream:
            data = stream.read(MAX_ENCODED_BYTES + 1)
    else:
        try:
            data = source.read(MAX_ENCODED_BYTES + 1)
        finally:
            source.close()
    if len(data) > MAX_ENCODED_BYTES:
        raise ValueError('Encoded emoji exceeds size limit')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            # Ограничиваем плагины до разбора заголовка: EPS/PDF здесь не нужны.
            with Image.open(io.BytesIO(data), formats=('PNG', 'WEBP')) as check:
                if check.size != (64, 64) or check.width * check.height > MAX_IMAGE_PIXELS:
                    raise ValueError('Expected a 64x64 emoji texture')
                if getattr(check, 'n_frames', 1) != 1:
                    raise ValueError('Animated emoji input is not supported')
                check.verify()
            with Image.open(io.BytesIO(data), formats=('PNG', 'WEBP')) as image:
                if image.width * image.height > MAX_IMAGE_PIXELS:
                    raise ValueError('Emoji exceeds pixel limit')
                image.load()
                yield image
    except (Image.DecompressionBombWarning, Image.DecompressionBombError) as error:
        raise ValueError('Unsafe emoji dimensions') from error


def image_array(source, mode):
    import numpy as np
    with open_image(source) as image:
        with image.convert(mode) as converted:
            return np.array(converted)


def build_path(root, relative):
    # CLI выбирает корень явно; имена из архивов не могут стать путём записи,
    # а подложенный симлинк внутри рабочего каталога не должен вывести наружу.
    root = Path(root).resolve()
    path = root / relative
    if not path.resolve().is_relative_to(root):
        raise ValueError('Build path escapes root: ' + str(relative))
    return path
