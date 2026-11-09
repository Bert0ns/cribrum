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
        image = ImageOps.exif_transpose(image)
        if image.mode.startswith("I;16"):  # 16-bit PNG: Pillow would clip to 255, scale instead
            image = Image.fromarray((np.asarray(image) >> 8).astype(np.uint8))
        elif image.mode == "P":  # palettes may carry per-entry alpha, which L conversion rejects
            image = image.convert("RGBA")
        gray = image.convert("L")
        gray.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
        return np.asarray(gray, dtype=np.float32)


class Pixels:
    """One image's pixels while one task analyzes it (docs/design.md, section 7): the grayscale
    at a side is loaded once, for every step of the task. Nothing is kept past the task.

    The app also shares one decode between the sides that give the same sample size. Here
    Pillow's draft decodes each mode and side on its own, so only the same side is shared:
    exposure and the perceptual hash, both at 256.
    """

    def __init__(self, path: Path) -> None:
        self.path = path
        self._grays: dict[int, np.ndarray] = {}

    def gray(self, max_side: int) -> np.ndarray:
        """As `load_grayscale`. The steps read the array and never write to it."""
        if max_side not in self._grays:
            self._grays[max_side] = load_grayscale(self.path, max_side)
        return self._grays[max_side]
