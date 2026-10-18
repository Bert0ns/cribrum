"""Core types shared by every component. Mirrors docs/design.md, section 7."""

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path


class Category(Enum):
    EXACT_DUPLICATE = "exact_duplicate"
    NEAR_DUPLICATE = "near_duplicate"
    BURST = "burst"
    BLURRY = "blurry"
    BAD_EXPOSURE = "bad_exposure"
    SCREENSHOT = "screenshot"
    DOCUMENT = "document"
    FORWARDED = "forwarded"


class ReviewState(Enum):
    UNREVIEWED = "unreviewed"
    KEPT = "kept"
    TRASHED = "trashed"


@dataclass(frozen=True)
class ImageRef:
    """Identifies an image file. Size and mtime detect changes between scans."""

    path: Path
    size: int
    mtime_ns: int


@dataclass(frozen=True)
class Signal:
    """A detector's evidence that an image may belong to a deletable category.

    Signals never decide on their own: the ranker combines them.
    """

    detector_id: str
    detector_version: int
    category: Category
    score: float
    evidence: dict[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not 0.0 <= self.score <= 1.0:
            raise ValueError(f"score must be in [0, 1], got {self.score}")
