"""Evaluation report: recall on the labeled sample (docs/design.md, 10, step 4).

Recall first, then what it costs: how many candidates the whole index gets at those
thresholds. Labels only say keep or delete, so the thresholds are chosen together, for the
recall of all categories at once. The report holds paths of personal images: print it, or
write it under lab/data/, never into the repository.
"""

import dataclasses
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from cribrum_lab.folders import is_camera_folder, is_messaging_folder, is_sticker_folder
from cribrum_lab.labels import Decision, Label, Stratum
from cribrum_lab.model import Category, IndexedImage, ReviewState
from cribrum_lab.ranker import THRESHOLDS, Ranker

SAMPLING_THRESHOLDS = {
    Category.EXACT_DUPLICATE: 0.5,
    Category.NEAR_DUPLICATE: 0.5,
    Category.BURST: 0.5,
    Category.BLURRY: 0.4,
    Category.BAD_EXPOSURE: 0.5,
    Category.SCREENSHOT: 0.5,
    Category.DOCUMENT: 0.5,
    Category.FORWARDED: 0.5,
    Category.STICKER: 0.5,
}
"""The ranker's thresholds when the existing 1,200 labels were sampled. A label without a
recorded stratum is placed by whether these made its image a candidate."""
CURVE_TARGETS = (0.70, 0.75, 0.80, 0.85, 0.90, 0.95)
"""Recall levels the curve prices. The goal is 95% (issue 25), but the first real labels needed
nearly every image as a candidate to reach it: docs/design.md, 10."""
SWEEP = [round(0.05 * i, 2) for i in range(1, 20)]


@dataclass
class SweepPoint:
    """What a category flags at one threshold, outside the images a sticker owns."""

    threshold: float
    caught: int
    false_positives: int
    listed: int
    """Images in the category's review list, in the whole index."""
    added_caught: int
    """Images labeled delete that only this category flags at this threshold, given the
    others at their current thresholds: what lowering it to here adds to the candidates."""
    added_false_positives: int
    added_candidates: int


@dataclass
class CategoryResult:
    category: Category
    caught: int
    """Images labeled delete that this category flags."""
    only_caught: int
    """Images labeled delete that no other category flags: what dropping this one would lose."""
    false_positives: int
    """Images labeled keep that this category flags."""
    candidates: int
    """Images this category flags in the whole index: the cost of what it catches."""
    threshold: float
    sweep: list[SweepPoint]


@dataclass
class CurvePoint:
    """The cheapest thresholds found for a target recall, with what they catch and cost, all
    categories together."""

    target: float
    thresholds: dict[Category, float]
    caught: int
    false_positives: int
    candidates: int
    weighted_recall: float | None


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
    curve: list[CurvePoint]
    weighted_recall: float | None
    """Recall for the whole library: every label counts for as many images as its stratum has
    in the index per label in the sample, since the sample takes half of its images from the
    candidates and half from the rest."""
    derived_strata: int
    """Labels with no recorded stratum, placed with `sampled_with`."""
    missed: list[Path]
    """Images labeled delete that no detector made a candidate: the false negatives to chase."""
    missed_by_folder: dict[str, int]
    """The same images counted by kind of folder: no paths, so safe to quote."""
    missed_by_closest: dict[str, int]
    """The same images counted by the category whose score came closest to its threshold."""

    @property
    def recall(self) -> float | None:
        return self.caught / self.deletable if self.deletable else None


def evaluate(
    images: Sequence[IndexedImage],
    labels: Sequence[Label],
    thresholds: Mapping[Category, float] = THRESHOLDS,
    targets: Sequence[float] = CURVE_TARGETS,
    sampled_with: Mapping[Category, float] = SAMPLING_THRESHOLDS,
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
    limits = np.array([thresholds.get(c, 1.0) for c in categories])
    current = scores >= limits
    missed = [i for i in deletable if not ranked[images[i].ref.path].is_candidate]
    weights, derived = _library_weights(images, labeled, row, sampled_with)
    flagged = np.array([ranked[image.ref.path].is_candidate for image in images], bool)
    sticker = categories.index(Category.STICKER)
    owned = scores[:, sticker] >= thresholds.get(Category.STICKER, 1.0)
    # others[:, j]: some category other than j flags the image
    others = np.stack(
        [np.delete(current, j, axis=1).any(axis=1) for j in range(len(categories))], 1
    )

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
                only_caught=int((current[deletable, j] & ~others[deletable, j]).sum()),
                false_positives=int(current[kept, j].sum()),
                candidates=int(current[:, j].sum()),
                threshold=thresholds.get(category, 1.0),
                sweep=_sweep(
                    scores[:, j], deletable, kept, None if j == sticker else owned, others[:, j]
                ),
            )
            for j, category in enumerate(categories)
        ],
        curve=[_curve_point(scores, deletable, kept, weights, target) for target in targets],
        weighted_recall=_weighted_recall(flagged, deletable, weights),
        derived_strata=derived,
        missed=[images[i].ref.path for i in missed],
        missed_by_folder=dict(Counter(_folder_kind(images[i].ref.path) for i in missed)),
        missed_by_closest=dict(Counter(_closest(scores[i], limits, categories) for i in missed)),
    )


def _library_weights(
    images: Sequence[IndexedImage],
    labeled: Sequence[Label],
    row: Mapping[Path, int],
    sampled_with: Mapping[Category, float],
) -> tuple[np.ndarray, int]:
    """How many library images each label stands for, and how many labels had no stratum.

    A stratum's weight is its size in the index divided by its labels. A label without a
    recorded stratum is placed by whether `sampled_with` made its image a candidate.
    """
    candidate = {c.image.ref.path: c.is_candidate for c in Ranker(sampled_with).rank(images)}

    def stratum(label: Label) -> Stratum:
        if label.stratum:
            return label.stratum
        return Stratum.CANDIDATE if candidate[label.path] else Stratum.UNFLAGGED

    library = Counter(Stratum.CANDIDATE if c else Stratum.UNFLAGGED for c in candidate.values())
    sampled = Counter(stratum(label) for label in labeled)
    weights = np.zeros(len(images))
    for label in labeled:
        weights[row[label.path]] = library[stratum(label)] / sampled[stratum(label)]
    return weights, sum(label.stratum is None for label in labeled)


def _weighted_recall(
    flagged: np.ndarray, deletable: np.ndarray, weights: np.ndarray
) -> float | None:
    total = weights[deletable].sum()
    return float(weights[deletable][flagged[deletable]].sum() / total) if total else None


def _curve_point(
    scores: np.ndarray, deletable: np.ndarray, kept: np.ndarray, weights: np.ndarray, target: float
) -> CurvePoint:
    chosen = _suggest(scores, deletable, target)
    flagged = (scores >= list(chosen.values())).any(axis=1)
    return CurvePoint(
        target,
        chosen,
        caught=int(flagged[deletable].sum()),
        false_positives=int(flagged[kept].sum()),
        candidates=int(flagged.sum()),
        weighted_recall=_weighted_recall(flagged, deletable, weights),
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


def _sweep(
    scores: np.ndarray,
    deletable: np.ndarray,
    kept: np.ndarray,
    owned: np.ndarray | None,
    others: np.ndarray,
) -> list[SweepPoint]:
    """`owned` marks the images another category lists them under: this one skips them in its
    list. `others` marks the images another category already makes candidates."""
    listing = scores if owned is None else np.where(owned, -1.0, scores)
    points = []
    for threshold in SWEEP:
        added = (scores >= threshold) & ~others
        points.append(
            SweepPoint(
                threshold,
                caught=int((listing[deletable] >= threshold).sum()),
                false_positives=int((listing[kept] >= threshold).sum()),
                listed=int((listing >= threshold).sum()),
                added_caught=int(added[deletable].sum()),
                added_false_positives=int(added[kept].sum()),
                added_candidates=int(added.sum()),
            )
        )
    return points


def format_report(report: Report) -> str:
    lines = [
        f"{report.labeled} labels ({report.unmatched} no longer in the index), "
        f"{report.deletable} labeled delete",
        f"recall: {_percent(report.recall)} ({report.caught}/{report.deletable}), "
        f"{report.false_positives} kept images flagged, "
        f"{report.candidates} candidates out of {report.images} images",
        f"library recall: {_percent(report.weighted_recall)}, each label weighted by the size "
        "of the stratum (candidates or not) it was sampled from",
        f"{report.derived_strata} labels without a stratum are placed by their candidate status "
        "at the thresholds used when sampling",
        "",
        f"{'category':16} {'threshold':>9} {'deletes':>7} {'only':>5} {'keeps':>5} "
        f"{'candidates':>10}",
    ]
    for result in report.categories:
        lines.append(
            f"{result.category.value:16} {result.threshold:9.2f} {result.caught:7} "
            f"{result.only_caught:5} {result.false_positives:5} {result.candidates:10}"
        )
    lines += [
        "",
        "deletes: labeled delete the category flags. only: the ones no other category flags.",
        "",
        "cheapest thresholds per target recall; a category not listed is not lowered "
        "(only scores of 0.95 or more flag):",
        f"{'target':>6} {'recall':>6} {'library':>7}  {'caught':7} {'keeps':>5} "
        f"{'candidates':>10}  thresholds",
    ]
    for point in report.curve:
        recall = point.caught / report.deletable if report.deletable else None
        lowered = " ".join(
            f"{c.value}={t:.2f}" for c, t in point.thresholds.items() if t < SWEEP[-1]
        )
        lines.append(
            f"{_percent(point.target):>6} {_percent(recall):>6} "
            f"{_percent(point.weighted_recall):>7}  "
            f"{f'{point.caught}/{report.deletable}':7} {point.false_positives:5} "
            f"{point.candidates:10}  {lowered}"
        )
    for result in report.categories:
        where = "" if result.category is Category.STICKER else ", outside sticker folders"
        lines += ["", f"{result.category.value}: threshold sweep{where}"]
        lines.append(
            f"  {'threshold':>9} {'deletes':>7} {'keeps':>5} {'listed':>6}"
            f"  | adds to the candidates: {'deletes':>7} {'keeps':>5} {'images':>6}"
        )
        lines += [
            f"  {p.threshold:9.2f} {p.caught:7} {p.false_positives:5} {p.listed:6}"
            f"  |                         {p.added_caught:7} {p.added_false_positives:5} "
            f"{p.added_candidates:6}"
            for p in result.sweep
        ]
    lines += ["", f"missed ({len(report.missed)}): labeled delete, no detector flagged them"]
    for title, counts in (
        ("folder kind", report.missed_by_folder),
        ("closest category", report.missed_by_closest),
    ):
        lines.append(f" by {title}:")
        lines += [f"  {name}: {count}" for name, count in sorted(counts.items())]
    lines.append(" paths:")
    lines += [f"  {path}" for path in report.missed]
    return "\n".join(lines)


def _folder_kind(path: Path) -> str:
    if is_sticker_folder(path):
        return "sticker folder"
    if is_messaging_folder(path):
        return "messaging app"
    return "camera" if is_camera_folder(path) else "other"


def _closest(scores: np.ndarray, limits: np.ndarray, categories: list[Category]) -> str:
    """The category with the highest score as a fraction of its threshold, "none" if all are 0."""
    fractions = scores / limits
    return categories[int(fractions.argmax())].value if fractions.max() > 0 else "none"


def _score(image: IndexedImage, category: Category) -> float:
    return max((s.score for s in image.signals if s.category is category), default=0.0)


def _percent(value: float | None) -> str:
    return "-" if value is None else f"{100 * value:.0f}%"
