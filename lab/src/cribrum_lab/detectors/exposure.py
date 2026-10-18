"""BAD_EXPOSURE: pocket shots, black frames, blown-out and flat frames (docs/design.md, section 5).

Each case looks at the opposite end of the histogram too, so a night shot with a
lit subject is not "dark" and a page of text is not "bright". All thresholds are
starting points, to be tuned for recall in the lab.
"""

import numpy as np

from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.imaging import load_grayscale
from cribrum_lab.model import Category, ImageRef, Signal

MAX_SIDE = 256
DARK_LEVEL = 30
"""Dark scores 1 when 95% of the pixels are at or below this luminance."""
BRIGHT_LEVEL = 235
"""Bright scores 1 when 95% of the pixels are at or above this luminance."""
RAMP = 64
"""Luminance distance over which each score fades from 1 to 0."""


class ExposureDetector:
    id = "exposure"
    version = 1

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        gray = load_grayscale(image.path, MAX_SIDE)
        p1, p5, p95, p99 = (float(v) for v in np.percentile(gray, [1, 5, 95, 99]))
        cases = {
            "dark": _fade(p95 - DARK_LEVEL),
            "bright": _fade(BRIGHT_LEVEL - p5),
            "flat": _fade(p99 - p1),
        }
        # The strongest case, whether or not it is bad enough: the ranker decides that.
        case = max(cases, key=cases.get)
        evidence = {"case": case} | {
            f"{name}_score": f"{value:.3f}" for name, value in cases.items()
        }
        return [Signal(self.id, self.version, Category.BAD_EXPOSURE, cases[case], evidence)]


def _fade(distance: float) -> float:
    """1 at distance 0 or less, 0 at RAMP or more, linear in between."""
    return min(1.0, max(0.0, 1.0 - distance / RAMP))
