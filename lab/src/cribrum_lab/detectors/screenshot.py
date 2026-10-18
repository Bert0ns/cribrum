"""SCREENSHOT: path and file name, screen-sized image, no camera EXIF (docs/design.md, section 5).

Each rule is a separate piece of evidence. The score combines them as independent
chances (noisy OR), so any rule alone can make an image a candidate and each extra rule
raises the score. The weights are starting points, to be tuned for recall in the lab.
"""

import re

from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.model import Category, ImageRef, Signal

PATH_WEIGHT = 0.9
SCREEN_SIZE_WEIGHT = 0.6
NO_CAMERA_EXIF_WEIGHT = 0.3
"""Weak: forwarded and downloaded images have no camera EXIF either."""

_PATTERN = re.compile(
    r"screen[ _-]?shot|screencap|schermata|bildschirmfoto|capture d.[ée]cran"
    r"|captura de (?:pantalla|tela)|скриншот|снимок экрана|截屏|截图|スクリーンショット",
    re.IGNORECASE,
)
_PATH_PARTS = 3
"""File name and its two nearest folders: higher folders say nothing about the image."""


class ScreenshotDetector:
    id = "screenshot"
    version = 1

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        meta = context.metadata(image)
        path_match = next(
            (m.group() for part in image.path.parts[-_PATH_PARTS:] if (m := _PATTERN.search(part))),
            "",
        )
        screen_size = (min(meta.width, meta.height), max(meta.width, meta.height))
        is_screen_size = screen_size in context.screen_sizes
        weights = [
            PATH_WEIGHT if path_match else 0.0,
            SCREEN_SIZE_WEIGHT if is_screen_size else 0.0,
            0.0 if meta.has_camera_exif else NO_CAMERA_EXIF_WEIGHT,
        ]
        miss = 1.0
        for weight in weights:
            miss *= 1.0 - weight
        evidence = {
            "path_match": path_match,
            "size": f"{meta.width}x{meta.height}",
            "screen_size": "yes" if is_screen_size else "no",
            "camera_exif": "yes" if meta.has_camera_exif else "no",
        }
        return [Signal(self.id, self.version, Category.SCREENSHOT, 1.0 - miss, evidence)]
