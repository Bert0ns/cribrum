"""What a scan option that changes outputs does to the candidates and the review (issue 359).

An option is studied as two lists of the same images: today's signals, and the signals the
option would store. `compare` ranks both and counts, per category, what leaves the
candidates, what the labels say about it, and how the category's list reorders. Recall
comes first (docs/design.md, D4): an option that drops a candidate shows in `dropped`.
Nothing here holds a path, so a `Comparison` is safe to quote.
"""

import dataclasses
from collections.abc import Callable, Collection, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from cribrum_lab.labels import Decision, Label
from cribrum_lab.model import Category, IndexedImage, ReviewState, Signal
from cribrum_lab.ranker import Candidate, Ranker

PAGE_SIZE = 48
"""Images on one page of the app's review (`Review.kt`)."""


@dataclass(frozen=True)
class CategoryChange:
    category: Category
    flagged: tuple[int, int]
    """Images with a reason in the category, before and after."""
    lost: int
    """Images the category flagged before and not after. They may still be candidates."""
    caught: tuple[int, int]
    """Images labeled delete that the category flags, before and after."""
    listed: tuple[int, int]
    """Images in the category's review list, the list `Ranker.by_category` builds, before and
    after. A sticker is listed under STICKER only, so a reason it loses may change no list."""
    moved: int
    """Images in the list both times whose position in it changed."""
    first_page_kept: float | None
    """Share of the list's first page before that is still on the first page after."""
    rank_correlation: float | None
    """Spearman's correlation of the positions of the images listed both times."""
    deletes_first: tuple[float | None, float | None]
    """Chance that a labeled delete comes before a labeled keep in the list, before and after:
    how well the order puts the deletable images first. None without both kinds of label."""


@dataclass(frozen=True)
class Comparison:
    images: int
    candidates: tuple[int, int]
    dropped: int
    """Candidates before that are no candidate after: an option with any breaks D4."""
    added: int
    deletable: int
    """Labeled delete images in the index."""
    caught: tuple[int, int]
    """Labeled delete images that are candidates, before and after."""
    reasons_changed: int
    """Candidates whose reasons changed, in category or score."""
    categories: list[CategoryChange]


def without_signals(
    images: Sequence[IndexedImage], detector_ids: Collection[str], where: Callable[[Path], bool]
) -> list[IndexedImage]:
    """The images as if the detectors had not run on those `where` accepts."""
    return [
        dataclasses.replace(
            image, signals=tuple(s for s in image.signals if s.detector_id not in detector_ids)
        )
        if where(image.ref.path)
        else image
        for image in images
    ]


def with_signals(
    images: Sequence[IndexedImage], detector_id: str, signals: Mapping[Path, Sequence[Signal]]
) -> list[IndexedImage]:
    """The images with the detector's signals replaced by `signals`, for the images it holds."""
    return [
        dataclasses.replace(
            image,
            signals=tuple(s for s in image.signals if s.detector_id != detector_id)
            + tuple(signals[image.ref.path]),
        )
        if image.ref.path in signals
        else image
        for image in images
    ]


def compare(
    before: Sequence[IndexedImage],
    after: Sequence[IndexedImage],
    labels: Sequence[Label] = (),
    ranker: Ranker | None = None,
) -> Comparison:
    """Review states are ignored, as in `evaluation.evaluate`: a kept image counts like any
    other. Both lists hold the same images."""
    ranker = ranker or Ranker()
    old, new = _candidates(before, ranker), _candidates(after, ranker)
    decisions = {label.path: label.decision for label in labels}
    deletes = {path for path, decision in decisions.items() if decision is Decision.DELETE}
    deletes &= {image.ref.path for image in before}
    old_lists, new_lists = Ranker.by_category(old.values()), Ranker.by_category(new.values())
    return Comparison(
        images=len(before),
        candidates=(len(old), len(new)),
        dropped=len(old.keys() - new.keys()),
        added=len(new.keys() - old.keys()),
        deletable=len(deletes),
        caught=(len(deletes & old.keys()), len(deletes & new.keys())),
        reasons_changed=sum(
            _reasons(old[path]) != _reasons(new[path]) for path in old.keys() & new.keys()
        ),
        categories=[
            _category_change(
                category,
                old,
                new,
                old_lists.get(category, []),
                new_lists.get(category, []),
                decisions,
                deletes,
            )
            for category in Category
        ],
    )


def _candidates(images: Sequence[IndexedImage], ranker: Ranker) -> dict[Path, Candidate]:
    images = [dataclasses.replace(i, review_state=ReviewState.UNREVIEWED) for i in images]
    return {c.image.ref.path: c for c in ranker.rank(images) if c.is_candidate}


def _reasons(candidate: Candidate) -> set[tuple[Category, float]]:
    return {(s.category, s.score) for s in candidate.reasons}


def _category_change(
    category: Category,
    old: Mapping[Path, Candidate],
    new: Mapping[Path, Candidate],
    old_list: Sequence[Candidate],
    new_list: Sequence[Candidate],
    decisions: Mapping[Path, Decision],
    deletes: set[Path],
) -> CategoryChange:
    def flagged(candidates: Mapping[Path, Candidate]) -> set[Path]:
        return {p for p, c in candidates.items() if any(s.category is category for s in c.reasons)}

    was, now = flagged(old), flagged(new)
    old_order = [c.image.ref.path for c in old_list]
    new_order = [c.image.ref.path for c in new_list]
    old_at = {path: i for i, path in enumerate(old_order)}
    new_at = {path: i for i, path in enumerate(new_order)}
    both = [path for path in old_order if path in new_at]
    first_page = set(old_order[:PAGE_SIZE])
    return CategoryChange(
        category,
        flagged=(len(was), len(now)),
        lost=len(was - now),
        caught=(len(deletes & was), len(deletes & now)),
        listed=(len(old_order), len(new_order)),
        moved=sum(old_at[path] != new_at[path] for path in both),
        first_page_kept=len(first_page & set(new_order[:PAGE_SIZE])) / len(first_page)
        if first_page
        else None,
        rank_correlation=_spearman([old_at[p] for p in both], [new_at[p] for p in both]),
        deletes_first=(_deletes_first(old_order, decisions), _deletes_first(new_order, decisions)),
    )


def _spearman(a: Sequence[int], b: Sequence[int]) -> float | None:
    """Positions are distinct within a list, so ranking them needs no tie rule."""
    if len(a) < 2:
        return None
    ra, rb = np.argsort(np.argsort(a)), np.argsort(np.argsort(b))
    return float(np.corrcoef(ra, rb)[0, 1])


def _deletes_first(order: Sequence[Path], decisions: Mapping[Path, Decision]) -> float | None:
    """The area under the ROC curve of the list's order, labeled delete as the positives."""
    keeps = deletes = pairs_right = 0
    for path in reversed(order):  # each delete is ahead of every keep seen so far
        decision = decisions.get(path)
        if decision is Decision.KEEP:
            keeps += 1
        elif decision is Decision.DELETE:
            pairs_right += keeps
            deletes += 1
    return pairs_right / (deletes * keeps) if deletes and keeps else None
