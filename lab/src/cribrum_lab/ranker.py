"""Turns signals into candidates and a review order (docs/design.md, section 6)."""

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from cribrum_lab.model import Category, IndexedImage, Protection, ReviewState, Signal

THRESHOLDS = {
    Category.EXACT_DUPLICATE: 0.5,
    Category.NEAR_DUPLICATE: 0.5,
    Category.BURST: 0.5,
    Category.BLURRY: 0.4,
    Category.BAD_EXPOSURE: 0.4,
    Category.SCREENSHOT: 0.5,
    Category.DOCUMENT: 0.2,
    Category.FORWARDED: 0.5,
    Category.STICKER: 0.5,
}
"""Score a signal needs to make an image a candidate. Picked by the maintainer from the
evaluation report's sweep, for recall (docs/design.md, 10). Keep them equal to the app's."""

PROTECTION_WEIGHTS = {Protection.FACES: 0.5, Protection.FAVORITE: 0.8, Protection.ALBUM: 0.6}
"""How much a protection signal at score 1 lowers an image's likelihood."""
UNIQUE_WEIGHT = 0.2
"""Lowers images in no duplicate or burst group: nothing else keeps the same memory."""
GROUP_CATEGORIES = {Category.EXACT_DUPLICATE, Category.NEAR_DUPLICATE, Category.BURST}


@dataclass(frozen=True)
class Candidate:
    image: IndexedImage
    reasons: tuple[Signal, ...]
    """Signals past their threshold, strongest first. Empty when the image is listed only
    for "review everything"."""
    likelihood: float
    """How likely the image is deletable, comparable across categories, after protection."""
    protection: float = 1.0
    """Factor protection signals applied to the likelihood: 1 when nothing protects it."""

    @property
    def is_candidate(self) -> bool:
        return bool(self.reasons)


class Ranker:
    def __init__(self, thresholds: Mapping[Category, float] = THRESHOLDS) -> None:
        self.thresholds = thresholds

    def rank(self, images: Sequence[IndexedImage]) -> list[Candidate]:
        """The "review everything" order: every unreviewed image, most likely deletable first.

        Candidates always come before the other images. Protection signals lower an image
        within its part of the order but never move a candidate behind a non-candidate.
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
            protection = _protection(image.signals)
            likelihood = protection * max(map(self._likelihood, image.signals), default=0.0)
            ranked.append(Candidate(image, tuple(reasons), likelihood, protection))
        ranked.sort(key=lambda c: (not c.is_candidate, -c.likelihood, str(c.image.ref.path)))
        return ranked

    @staticmethod
    def by_category(ranked: Sequence[Candidate]) -> dict[Category, list[Candidate]]:
        """Candidates under each category they have a reason for, highest score first, each
        score lowered by the candidate's protection. A sticker is listed under STICKER only."""
        groups = defaultdict(list)
        for candidate in ranked:
            categories = {s.category for s in candidate.reasons}
            if Category.STICKER in categories:
                categories = {Category.STICKER}  # stickers would crowd every other category
            for category in categories:
                groups[category].append(candidate)
        for category, candidates in groups.items():
            candidates.sort(
                key=lambda c: (
                    -c.protection * max(s.score for s in c.reasons if s.category == category)
                )
            )
        return dict(groups)

    def _passes(self, signal: Signal) -> bool:
        """Protection signals have no threshold, so they never pass."""
        threshold = self.thresholds.get(signal.category)
        return threshold is not None and signal.score >= threshold

    def _likelihood(self, signal: Signal) -> float:
        """Score / threshold below the threshold, 1 + the margin over it above, 0 for
        protection signals."""
        threshold = self.thresholds.get(signal.category)
        if threshold is None:
            return 0.0
        if signal.score < threshold:
            return signal.score / threshold
        if threshold == 1.0:
            return 1.0
        return 1.0 + (signal.score - threshold) / (1.0 - threshold)


def _protection(signals: Sequence[Signal]) -> float:
    factor = 1.0
    for signal in signals:
        if isinstance(signal.category, Protection):
            factor *= 1.0 - PROTECTION_WEIGHTS.get(signal.category, 0.0) * signal.score
    if not any(s.category in GROUP_CATEGORIES for s in signals):  # members always get one
        factor *= 1.0 - UNIQUE_WEIGHT
    return factor
