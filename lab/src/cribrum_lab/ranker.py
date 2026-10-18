"""Turns signals into candidates and a review order (docs/design.md, section 6)."""

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from cribrum_lab.model import Category, IndexedImage, ReviewState, Signal

THRESHOLDS = {
    Category.EXACT_DUPLICATE: 0.5,
    Category.NEAR_DUPLICATE: 0.5,
    Category.BURST: 0.5,
    Category.BLURRY: 0.4,
    Category.BAD_EXPOSURE: 0.5,
    Category.SCREENSHOT: 0.5,
    Category.DOCUMENT: 0.5,
    Category.FORWARDED: 0.5,
}
"""Score a signal needs to make an image a candidate. Starting points: the evaluation
report picks them from the labeled sample, for recall."""


@dataclass(frozen=True)
class Candidate:
    image: IndexedImage
    reasons: tuple[Signal, ...]
    """Signals past their threshold, strongest first. Empty when the image is listed only
    for "review everything"."""
    likelihood: float
    """How likely the image is deletable, comparable across categories. At least 1 for
    candidates, below 1 for the others."""

    @property
    def is_candidate(self) -> bool:
        return bool(self.reasons)


class Ranker:
    def __init__(self, thresholds: Mapping[Category, float] = THRESHOLDS) -> None:
        self.thresholds = thresholds

    def rank(self, images: Sequence[IndexedImage]) -> list[Candidate]:
        """The "review everything" order: every unreviewed image, most likely deletable first.

        A kept image is listed again only for a signal it did not have when the user kept
        it, such as a new duplicate group or a new role in its group. A newer detector
        version or a higher score is not new. Trashed images are never listed.
        """
        ranked = []
        for image in images:
            if image.review_state is ReviewState.TRASHED:
                continue
            reasons = [s for s in image.signals if self._passes(s)]
            if image.review_state is ReviewState.KEPT:
                reasons = [s for s in reasons if s.identity not in image.seen_when_kept]
                if not reasons:
                    continue
            reasons.sort(key=self._likelihood, reverse=True)
            likelihood = max(map(self._likelihood, image.signals), default=0.0)
            ranked.append(Candidate(image, tuple(reasons), likelihood))
        ranked.sort(key=lambda c: (-c.likelihood, str(c.image.ref.path)))
        return ranked

    @staticmethod
    def by_category(ranked: Sequence[Candidate]) -> dict[Category, list[Candidate]]:
        """Candidates under each category they have a reason for, highest score first."""
        groups = defaultdict(list)
        for candidate in ranked:
            for category in {s.category for s in candidate.reasons}:
                groups[category].append(candidate)
        for category, candidates in groups.items():
            candidates.sort(
                key=lambda c: -max(s.score for s in c.reasons if s.category == category)
            )
        return dict(groups)

    def _passes(self, signal: Signal) -> bool:
        threshold = self.thresholds.get(signal.category)
        return threshold is not None and signal.score >= threshold

    def _likelihood(self, signal: Signal) -> float:
        """Score / threshold below the threshold, 1 + the margin over it above."""
        threshold = self.thresholds.get(signal.category)
        if threshold is None:
            return 0.0
        if signal.score < threshold:
            return signal.score / threshold
        if threshold == 1.0:
            return 1.0
        return 1.0 + (signal.score - threshold) / (1.0 - threshold)
