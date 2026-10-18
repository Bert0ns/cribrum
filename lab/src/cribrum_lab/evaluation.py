"""Evaluation report: recall per category on the labeled sample (docs/design.md, 10, step 4).

Recall first, then what it costs: how many candidates the whole index gets at that
threshold. The report holds paths of personal images: print it, or write it under
lab/data/, never into the repository.
"""

import dataclasses
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from cribrum_lab.labels import Decision, Label
from cribrum_lab.model import Category, IndexedImage, ReviewState
from cribrum_lab.ranker import THRESHOLDS, Ranker

TARGET_RECALL = 0.95
SWEEP = [round(0.05 * i, 2) for i in range(1, 20)]


@dataclass
class CategoryResult:
    category: Category
    labeled: int
    """Images labeled delete for this category."""
    caught: int
    """Of those, the ones that are candidates for this category."""
    candidates: int
    """Candidates for this category in the whole index: the cost of the recall."""
    suggested_threshold: float | None
    """Highest swept threshold that still reaches TARGET_RECALL, if any does."""
    sweep: list[tuple[float, float, int]] = field(default_factory=list)
    """(threshold, recall, candidates in the index) for each swept threshold."""

    @property
    def recall(self) -> float | None:
        return self.caught / self.labeled if self.labeled else None


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
    missed: list[Path]
    """Images labeled delete that no detector made a candidate: the false negatives to chase."""

    @property
    def recall(self) -> float | None:
        return self.caught / self.deletable if self.deletable else None


def evaluate(
    images: Sequence[IndexedImage],
    labels: Sequence[Label],
    thresholds: Mapping[Category, float] = THRESHOLDS,
) -> Report:
    """Review states are ignored: a kept image is evaluated like any other.

    shortcut: labels are matched to images by path; match by content hash once images get
    copied to new paths.
    """
    images = [dataclasses.replace(i, review_state=ReviewState.UNREVIEWED) for i in images]
    by_path = {image.ref.path: image for image in images}
    ranked = {c.image.ref.path: c for c in Ranker(thresholds).rank(images)}
    labeled = [(label, by_path[label.path]) for label in labels if label.path in by_path]

    deletable = [(label, image) for label, image in labeled if label.decision is Decision.DELETE]
    kept = [image for label, image in labeled if label.decision is Decision.KEEP]
    categories = []
    for category in Category:
        positives = [image for label, image in deletable if label.category is category]
        threshold = thresholds.get(category, 1.0)
        sweep = [(t, _recall(positives, category, t), _count(images, category, t)) for t in SWEEP]
        reaching = [t for t, recall, _ in sweep if recall is not None and recall >= TARGET_RECALL]
        categories.append(
            CategoryResult(
                category,
                labeled=len(positives),
                caught=sum(_score(image, category) >= threshold for image in positives),
                candidates=_count(images, category, threshold),
                suggested_threshold=max(reaching) if reaching else None,
                sweep=sweep,
            )
        )
    return Report(
        labeled=len(labels),
        unmatched=len(labels) - len(labeled),
        deletable=len(deletable),
        caught=sum(ranked[image.ref.path].is_candidate for _, image in deletable),
        false_positives=sum(ranked[image.ref.path].is_candidate for image in kept),
        candidates=sum(c.is_candidate for c in ranked.values()),
        images=len(images),
        categories=categories,
        missed=[i.ref.path for _, i in deletable if not ranked[i.ref.path].is_candidate],
    )


def format_report(report: Report) -> str:
    lines = [
        f"{report.labeled} labels ({report.unmatched} no longer in the index), "
        f"{report.deletable} labeled delete",
        f"recall: {_percent(report.recall)} ({report.caught}/{report.deletable}), "
        f"{report.false_positives} kept images flagged, "
        f"{report.candidates} candidates out of {report.images} images",
        "",
        f"{'category':16} {'labeled':>7} {'recall':>7} {'candidates':>10}  suggested threshold",
    ]
    for result in report.categories:
        suggested = (
            f"{result.suggested_threshold:.2f}" if result.suggested_threshold is not None else "-"
        )
        lines.append(
            f"{result.category.value:16} {result.labeled:7} {_percent(result.recall):>7} "
            f"{result.candidates:10}  {suggested}"
        )
    lines += ["", f"missed ({len(report.missed)}): labeled delete, no detector flagged them"]
    lines += [f"  {path}" for path in report.missed]
    return "\n".join(lines)


def _score(image: IndexedImage, category: Category) -> float:
    return max((s.score for s in image.signals if s.category is category), default=0.0)


def _recall(positives: list[IndexedImage], category: Category, threshold: float) -> float | None:
    if not positives:
        return None
    return sum(_score(image, category) >= threshold for image in positives) / len(positives)


def _count(images: list[IndexedImage], category: Category, threshold: float) -> int:
    return sum(_score(image, category) >= threshold for image in images)


def _percent(value: float | None) -> str:
    return "-" if value is None else f"{100 * value:.0f}%"
