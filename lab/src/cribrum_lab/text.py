"""Text coverage: the share of an image covered by text (docs/design.md, section 5).

PP-OCRv4's text detector (Apache-2.0, 4.7 MB ONNX) gives a probability of text for every
pixel; the coverage is the share of pixels above TEXT_PROBABILITY. Only detection runs,
not recognition: whether there is a lot of text matters, not what it says. The Android
app runs the same file on ONNX Runtime.
"""

import functools
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

from cribrum_lab import models
from cribrum_lab.runtime import ModelRuntime, OnnxRuntime

INPUT_SIDE = 640
"""Longest side fed to the detector, rounded to a multiple of 32 like each side."""
TEXT_PROBABILITY = 0.3
_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
_URL = (
    "https://huggingface.co/SWHL/RapidOCR/resolve/1cfba2e90fc938db55889873735088de210cc173"
    "/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx"
)
_SHA256 = "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9"


def preprocess(path: Path) -> np.ndarray:
    """RGB normalized with ImageNet mean and deviation, shape (1, 3, H, W), H and W
    multiples of 32 with the longest near INPUT_SIDE."""
    with Image.open(path) as image:
        image.draft("RGB", (INPUT_SIDE, INPUT_SIDE))
        image = ImageOps.exif_transpose(image).convert("RGB")
        scale = INPUT_SIDE / max(image.size)
        size = tuple(max(32, round(side * scale / 32) * 32) for side in image.size)
        pixels = np.asarray(image.resize(size, Image.Resampling.BILINEAR), dtype=np.float32)
    return ((pixels / 255.0 - _MEAN) / _STD).transpose(2, 0, 1)[None]


def text_coverage(path: Path, runtime: ModelRuntime | None = None) -> float:
    [probability] = (runtime or _default_runtime()).run({"x": preprocess(path)}).values()
    return float((probability > TEXT_PROBABILITY).mean())


@functools.cache
def _default_runtime() -> OnnxRuntime:
    """One thread: it runs in every pipeline worker process at once."""
    path = models.fetch(_URL, _SHA256, models.MODELS / "ppocr" / "ch_PP-OCRv4_det_infer.onnx")
    return OnnxRuntime(path, threads=1)
