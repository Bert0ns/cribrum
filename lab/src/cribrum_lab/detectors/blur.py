"""BLURRY: variance of the Laplacian on a downscaled grayscale image (docs/design.md, section 5)."""

import numpy as np

from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.imaging import load_grayscale
from cribrum_lab.model import Category, ImageRef, Signal

MAX_SIDE = 512
TILES = 4
"""The image is split into TILES x TILES regions; the sharpest one stands for the subject."""
HALF_SCORE_VARIANCE = 100.0
"""Laplacian variance that scores 0.5. A starting point, to be tuned for recall in the lab."""


class BlurDetector:
    id = "blur"
    version = 1

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        laplacian = _laplacian(load_grayscale(image.path, MAX_SIDE))
        if laplacian.size == 0:
            return []  # under 3 px on a side (after downscaling): nothing to measure
        global_variance = float(laplacian.var())
        # A portrait with a blurred background still has a sharp region: score on that one.
        peak_variance = max(
            float(tile.var())
            for rows in np.array_split(laplacian, TILES, axis=0)
            for tile in np.array_split(rows, TILES, axis=1)
            if tile.size > 0  # images narrower than TILES px leave some tiles empty
        )
        score = HALF_SCORE_VARIANCE / (HALF_SCORE_VARIANCE + peak_variance)
        evidence = {
            "global_variance": f"{global_variance:.1f}",
            "peak_variance": f"{peak_variance:.1f}",
        }
        return [Signal(self.id, self.version, Category.BLURRY, score, evidence)]


def _laplacian(gray: np.ndarray) -> np.ndarray:
    """4-neighbor Laplacian over the interior pixels."""
    return (
        gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:] - 4 * gray[1:-1, 1:-1]
    )
