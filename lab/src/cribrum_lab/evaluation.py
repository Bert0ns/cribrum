"""Evaluation report: recall on the labeled sample (docs/design.md, 10, step 4).

Recall first, then what it costs: how many candidates the whole index gets at those
thresholds. Labels only say keep or delete, so the thresholds are chosen together, for the
recall of all categories at once. The report holds paths of personal images: print it, or
write it under lab/data/, never into the repository.
"""

import dataclasses
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from cribrum_lab.labels import Decision, Label
from cribrum_lab.model import Category, IndexedImage, ReviewState
from cribrum_lab.ranker import THRESHOLDS, Ranker

TARGET_RECALL = 0.80
"""Above it, the first real labels needed nearly every image as a candidate: docs/design.md, 10."""
SWEEP = [round(0.05 * i, 2) for i in range(1, 20)]


@dataclass
class CategoryResult:
    category: Category
    caught: int
    """Images labeled delete that this category flags."""
    false_positives: int
    """Images labeled keep that this category flags."""
    candidates: int
    """Images this category flags in the whole index: the cost of what it catches."""
    suggested_threshold: float


@dataclass
class Suggestion:
    """What the suggested thresholds catch and cost, all categories together."""

    caught: int
    false_positives: int
    candidates: int


@dataclass
class Report:
    labeled: int
    unmatched: int
    """Labels whose path is no longer in the index."""
    deletable: int
    caught: int
    """Images labeled delete that are candidates for any reason."""
    false_positives: int
    """Images labeled keep that are candidates."""
    candidates: int
    images: int
    categories: list[CategoryResult]
    suggestion: Suggestion
    missed: list[Path]
    """Images labeled delete that no detector made a candidate: the false negatives to chase."""

    @property
    def recall(self) -> float | None:
        return self.caught / self.deletable if self.deletable else None


def evaluate(
    images: Sequence[IndexedImage],
    labels: Sequence[Label],
    thresholds: Mapping[Category, float] = THRESHOLDS,
    target: float = TARGET_RECALL,
) -> Report:
    """Review states are ignored: a kept image is evaluated like any other.

    shortcut: labels are matched to images by path; match by content hash once images get
    copied to new paths.
    """
    images = [dataclasses.replace(i, review_state=ReviewState.UNREVIEWED) for i in images]
    row = {image.ref.path: i for i, image in enumerate(images)}
    ranked = {c.image.ref.path: c for c in Ranker(thresholds).rank(images)}
    labeled = [label for label in labels if label.path in row]
    deletable = np.array([row[x.path] for x in labeled if x.decision is Decision.DELETE], int)
    kept = np.array([row[x.path] for x in labeled if x.decision is Decision.KEEP], int)

    categories = list(Category)
    scores = np.array([[_score(image, c) for c in categories] for image in images])
    scores = scores.reshape(len(images), len(categories))
    current = scores >= [thresholds.get(c, 1.0) for c in categories]
    suggested = _suggest(scores, deletable, target)
    flagged = (scores >= list(suggested.values())).any(axis=1)

    return Report(
        labeled=len(labels),
        unmatched=len(labels) - len(labeled),
        deletable=len(deletable),
        caught=sum(ranked[images[i].ref.path].is_candidate for i in deletable),
        false_positives=sum(ranked[images[i].ref.path].is_candidate for i in kept),
        candidates=sum(c.is_candidate for c in ranked.values()),
        images=len(images),
        categories=[
            CategoryResult(
                category,
                caught=int(current[deletable, j].sum()),
                false_positives=int(current[kept, j].sum()),
                candidates=int(current[:, j].sum()),
                suggested_threshold=suggested[category],
            )
            for j, category in enumerate(categories)
        ],
        suggestion=Suggestion(
            caught=int(flagged[deletable].sum()),
            false_positives=int(flagged[kept].sum()),
            candidates=int(flagged.sum()),
        ),
        missed=[
            images[i].ref.path for i in deletable if not ranked[images[i].ref.path].is_candidate
        ],
    )


def _suggest(scores: np.ndarray, deletable: np.ndarray, target: float) -> dict[Category, float]:
    """Thresholds that reach `target` recall on the deletable rows with few candidates.

    Greedy: every category starts at the highest swept threshold, then the move that catches
    the most new deletable images per new candidate in the index lowers one category's
    threshold, until no move catches more. When that move would reach the target, the move
    reaching it with the fewest new candidates is taken instead, so the target isn't overshot.
    Then each threshold goes back up as far as the target allows, since a later move can make
    an earlier one unnecessary.
    """
    categories = list(Category)
    chosen = {category: SWEEP[-1] for category in categories}
    flagged = (scores >= list(chosen.values())).any(axis=1)
    is_deletable = np.zeros(len(scores), bool)
    is_deletable[deletable] = True
    needed = target * len(deletable)
    while len(deletable) and flagged[deletable].sum() < needed:
        best = last = None
        caught = int(flagged[deletable].sum())
        for j, category in enumerate(categories):
            for threshold in (t for t in reversed(SWEEP) if t < chosen[category]):
                new = ~flagged & (scores[:, j] >= threshold)
                gain, cost = int(new[is_deletable].sum()), int(new.sum())
                if not gain:
                    continue
                if caught + gain >= needed and (last is None or cost < last[0]):
                    last = (cost, category, threshold, new)
                if best is None or (gain / cost, gain) > best[0]:
                    best = ((gain / cost, gain), category, threshold, new)
        if best is None:
            break
        (_, gain), *_ = best
        _, category, threshold, new = last if caught + gain >= needed else best
        chosen[category] = threshold
        flagged |= new
    if len(deletable) and flagged[deletable].sum() >= needed:
        for j, category in enumerate(categories):
            limits = np.delete(list(chosen.values()), j)
            others = (np.delete(scores, j, axis=1) >= limits).any(axis=1)
            for threshold in reversed(SWEEP):
                if threshold <= chosen[category]:
                    break
                if (others | (scores[:, j] >= threshold))[deletable].sum() >= needed:
                    chosen[category] = threshold
                    break
    return chosen


def format_report(report: Report) -> str:
    suggestion = report.suggestion
    recall = suggestion.caught / report.deletable if report.deletable else None
    lines = [
        f"{report.labeled} labels ({report.unmatched} no longer in the index), "
        f"{report.deletable} labeled delete",
        f"recall: {_percent(report.recall)} ({report.caught}/{report.deletable}), "
        f"{report.false_positives} kept images flagged, "
        f"{report.candidates} candidates out of {report.images} images",
        "",
        f"{'category':16} {'deletes':>7} {'keeps':>5} {'candidates':>10}  suggested threshold",
    ]
    for result in report.categories:
        lines.append(
            f"{result.category.value:16} {result.caught:7} {result.false_positives:5} "
            f"{result.candidates:10}  {result.suggested_threshold:.2f}"
        )
    lines += [
        "",
        f"suggested thresholds: recall {_percent(recall)} "
        f"({suggestion.caught}/{report.deletable}), "
        f"{suggestion.false_positives} kept images flagged, "
        f"{suggestion.candidates} candidates out of {report.images} images",
        "",
        f"missed ({len(report.missed)}): labeled delete, no detector flagged them",
    ]
    lines += [f"  {path}" for path in report.missed]
    return "\n".join(lines)


def _score(image: IndexedImage, category: Category) -> float:
    return max((s.score for s in image.signals if s.category is category), default=0.0)


def _percent(value: float | None) -> str:
    return "-" if value is None else f"{100 * value:.0f}%"
