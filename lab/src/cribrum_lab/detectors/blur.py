"""BLURRY: variance of the Laplacian on a downscaled grayscale image (docs/design.md, section 5)."""

from pathlib import Path

import numpy as np

from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.imaging import load_grayscale
from cribrum_lab.model import Category, ImageRef, Signal

MAX_SIDE = 512
TILES = 4
"""The image is split into TILES x TILES regions; the sharpest one stands for the subject."""
HALF_SCORE_VARIANCE = 100.0
"""Laplacian variance that scores 0.5. A starting point, to be tuned for recall in the lab."""
PEAK_VARIANCE_KEY = "peak_variance"
"""The evidence key of the sharpest region's variance, which BURST reads back as sharpness."""


class BlurDetector:
    id = "blur"
    version = 1
    category = Category.BLURRY

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        measured = measure(image.path)
        if measured is None:
            return []
        global_variance, peak_variance = measured
        score = HALF_SCORE_VARIANCE / (HALF_SCORE_VARIANCE + peak_variance)
        evidence = {
            "global_variance": f"{global_variance:.1f}",
            PEAK_VARIANCE_KEY: f"{peak_variance:.1f}",
        }
        return [Signal(self.id, self.version, Category.BLURRY, score, evidence)]


def measure(path: Path) -> tuple[float, float] | None:
    """Laplacian variance of the whole image and of its sharpest tile, which stands for the
    subject. None when the image is under 3 px on a side after downscaling."""
    laplacian = _laplacian(load_grayscale(path, MAX_SIDE))
    if laplacian.size == 0:
        return None
    # A portrait with a blurred background still has a sharp region: score on that one.
    peak_variance = max(
        float(tile.var())
        for rows in np.array_split(laplacian, TILES, axis=0)
        for tile in np.array_split(rows, TILES, axis=1)
        if tile.size > 0  # images narrower than TILES px leave some tiles empty
    )
    return float(laplacian.var()), peak_variance


def peak_variance(image: ImageRef) -> float:
    """The peak variance `measure` gives for the image, 0 when it can't be decoded or is under
    3 px on a side."""
    try:
        measured = measure(image.path)
    except Exception:  # undecodable pixels have no detail
        return 0.0
    return measured[1] if measured else 0.0


def _laplacian(gray: np.ndarray) -> np.ndarray:
    """4-neighbor Laplacian over the interior pixels."""
    return (
        gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:] - 4 * gray[1:-1, 1:-1]
    )
