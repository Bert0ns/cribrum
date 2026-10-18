"""DOCUMENT: slides, whiteboards, notes, homework, receipts, printed pages (docs/design.md, 5).

shortcut: MobileCLIP zero-shot only; OCR text coverage is not measured yet.
"""

from cribrum_lab import prompts
from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.model import Category, ImageRef, Signal


class DocumentDetector:
    id = "document"
    version = 1
    """Bump with prompts.VERSION."""

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        probability, prompt = prompts.classify(context.embedding(image))["document"]
        evidence = {"prompt": prompt, "probability": f"{probability:.3f}"}
        return [Signal(self.id, self.version, Category.DOCUMENT, probability, evidence)]
