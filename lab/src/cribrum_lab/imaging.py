"""Pixel loading shared by the detectors that look at image content."""

from pathlib import Path

import numpy as np
from PIL import Image, ImageOps


def load_grayscale(path: Path, max_side: int) -> np.ndarray:
    """Decodes an image as float32 luminance in [0, 255], downscaled to fit `max_side`.

    Measures computed on it depend on `max_side`: changing it means bumping the
    version of every detector that uses it.
    """
    with Image.open(path) as image:
        image.draft("L", (max_side, max_side))  # JPEG decodes straight at a reduced scale
        gray = ImageOps.exif_transpose(image).convert("L")
        gray.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
        return np.asarray(gray, dtype=np.float32)
