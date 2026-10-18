"""The detector interface. Every analysis technique implements it."""

from dataclasses import dataclass
from typing import Protocol

from cribrum_lab.model import ImageRef, Signal


@dataclass(frozen=True)
class AnalysisContext:
    """Shared, read-only information available to detectors.

    Grows as detectors need it (e.g. screen resolutions, cached embeddings).
    """


class Detector(Protocol):
    """Analyzes one image and emits zero or more signals.

    Bump `version` whenever the output for the same input can change, so stored
    signals from older versions get recomputed.
    """

    id: str
    version: int

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]: ...
