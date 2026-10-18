"""BAD_EXPOSURE: pocket shots, black frames, blown-out and flat frames (docs/design.md, section 5).

All thresholds are starting points, to be tuned for recall in the lab.
"""

import numpy as np

from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.imaging import load_grayscale
from cribrum_lab.model import Category, ImageRef, Signal

MAX_SIDE = 256
DARK_LEVEL = 30
"""Luminance at or below which a pixel counts as near-black."""
BRIGHT_LEVEL = 235
"""Luminance at or above which a pixel counts as blown out."""
FLAT_RANGE = 64
"""Spread between the 1st and 99th luminance percentiles at which an image stops being flat."""


class ExposureDetector:
    id = "exposure"
    version = 1

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        gray = load_grayscale(image.path, MAX_SIDE)
        low, high = np.percentile(gray, [1, 99])
        cases = {
            "dark": float((gray <= DARK_LEVEL).mean()),
            "bright": float((gray >= BRIGHT_LEVEL).mean()),
            "flat": max(0.0, 1.0 - float(high - low) / FLAT_RANGE),
        }
        case = max(cases, key=cases.get)
        evidence = {"case": case} | {
            f"{name}_score": f"{value:.3f}" for name, value in cases.items()
        }
        return [Signal(self.id, self.version, Category.BAD_EXPOSURE, cases[case], evidence)]
