"""DOCUMENT: slides, whiteboards, notes, homework, receipts, printed pages (docs/design.md, 5).

Two pieces of evidence, combined as independent chances (noisy OR): the MobileCLIP
zero-shot class, and how much of the image is text. The text weight saturates at
FULL_COVERAGE, a page of printed text; photos stay well under 1%.
"""

from collections.abc import Callable
from pathlib import Path

from cribrum_lab import prompts, text
from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.model import Category, ImageRef, Signal

FULL_COVERAGE = 0.10
TEXT_WEIGHT = 0.8


class DocumentDetector:
    id = "document"
    version = 3
    category = Category.DOCUMENT
    """Bump with prompts.VERSION too."""
    uses_embeddings = True

    def __init__(self, coverage: Callable[[Path], float] = text.text_coverage) -> None:
        self._coverage = coverage

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        probability, prompt = prompts.classify(context.embedding(image))["document"]
        coverage = self._coverage(image.path)
        text_chance = TEXT_WEIGHT * min(1.0, coverage / FULL_COVERAGE)
        score = 1.0 - (1.0 - probability) * (1.0 - text_chance)
        evidence = {
            "prompt": prompt,
            "probability": f"{probability:.3f}",
            "text_coverage": f"{coverage:.4f}",
        }
        return [Signal(self.id, self.version, Category.DOCUMENT, score, evidence)]
