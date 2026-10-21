"""Detector interfaces. Every analysis technique implements one of them."""

from collections.abc import Callable, Collection, Sequence
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol

import numpy as np

from cribrum_lab import hashing
from cribrum_lab.metadata import ImageMetadata, read_metadata
from cribrum_lab.model import Category, ImageRef, Signal
from cribrum_lab.screens import SCREEN_SIZES


class Role(StrEnum):
    BEST = "best"
    INFERIOR = "inferior"


@dataclass(frozen=True)
class AnalysisContext:
    """Shared, read-only information available to detectors.

    Per-image lookups compute their value from the file by default; the pipeline
    replaces them with lookups backed by the index, so each value is computed once.
    Grows as detectors need it (e.g. cached embeddings).
    """

    content_hash: Callable[[ImageRef], str] = hashing.content_hash
    perceptual_hash: Callable[[ImageRef], int] = hashing.perceptual_hash
    embedding: Callable[[ImageRef], np.ndarray] = lambda image: _embed(image)
    """L2-normalized MobileCLIP embedding."""
    metadata: Callable[[ImageRef], ImageMetadata] = lambda image: read_metadata(image.path)
    """Raises `UnreadableImage` when the header can't be decoded."""
    screen_sizes: Collection[tuple[int, int]] = SCREEN_SIZES
    """Known screen resolutions as (short side, long side)."""


def _embed(image: ImageRef) -> np.ndarray:
    from cribrum_lab import mobileclip  # loads ONNX Runtime only when a detector needs it

    return mobileclip.default_model().embed_images([image.path])[0]


class Detector(Protocol):
    """Analyzes one image and emits zero or more signals.

    Bump `version` whenever the output for the same input can change, so stored
    signals from older versions get recomputed.

    A detector that reads the embedding also sets `uses_embeddings = True`, so a new
    embedding makes its stored result stale. A Protocol can't make an attribute optional:
    the pipeline reads it with `getattr`, and a detector without it is false.
    """

    id: str
    version: int
    category: Category | None
    """The category its signals are about, so the app can tell which categories it looks for."""

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]: ...


class GroupDetector(Protocol):
    """Analyzes all images at once and emits signals for groups of them (duplicates, bursts).

    Every member of a group gets a signal, so the review screen can show the whole
    group. Its evidence holds the group id under `GROUP_KEY` and the member's role
    under `ROLE_KEY`: the copy to keep is `Role.BEST` with score 0, so no threshold
    ever suggests it; the others are `Role.INFERIOR`. Images in no group are left
    out of the result.

    `version` follows the same rule as `Detector.version`. Per-image data they need
    (content hashes, metadata, embeddings) comes from the lookups in `AnalysisContext`.
    """

    id: str
    version: int
    category: Category | None
    """As in `Detector`."""

    def analyze(
        self, images: Sequence[ImageRef], context: AnalysisContext
    ) -> dict[ImageRef, list[Signal]]: ...
