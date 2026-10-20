"""STICKER: stickers saved by messaging apps (docs/design.md, 5).

Nearly deterministic: WhatsApp keeps every sticker it receives in its sticker folders, and
nothing else goes there.
"""

from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.folders import is_sticker_folder
from cribrum_lab.model import Category, ImageRef, Signal


class StickerDetector:
    id = "sticker"
    version = 1

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        in_folder = is_sticker_folder(image.path)
        score = 1.0 if in_folder else 0.0
        evidence = {"sticker_folder": "yes" if in_folder else "no"}
        return [Signal(self.id, self.version, Category.STICKER, score, evidence)]
