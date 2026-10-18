"""Detector interfaces. Every analysis technique implements one of them."""

from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol

from cribrum_lab.model import ImageRef, Signal

GROUP_KEY = "group"
"""Evidence key holding the id of the group an image belongs to."""

ROLE_KEY = "role"
"""Evidence key holding the image's `Role` within its group."""


class Role(StrEnum):
    BEST = "best"
    INFERIOR = "inferior"


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


class GroupDetector(Protocol):
    """Analyzes all images at once and emits signals for groups of them (duplicates, bursts).

    Every member of a group gets a signal, so the review screen can show the whole
    group. Its evidence holds the group id under `GROUP_KEY` and the member's role
    under `ROLE_KEY`: the copy to keep is `Role.BEST` with score 0, so no threshold
    ever suggests it; the others are `Role.INFERIOR`. Images in no group are left
    out of the result.

    `version` follows the same rule as `Detector.version`.

    shortcut: only `ImageRef`s come in, which is enough for EXACT_DUPLICATE; reshape the
    input when NEAR_DUPLICATE and BURST need indexed metadata and embeddings.
    """

    id: str
    version: int

    def analyze(
        self, images: Sequence[ImageRef], context: AnalysisContext
    ) -> dict[ImageRef, list[Signal]]: ...
