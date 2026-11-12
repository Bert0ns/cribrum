"""Study the four scan options of issue 359 that change outputs, on the lab's index and labels.

Run from lab/, in this order:

    uv run --with onnx --with onnxconverter-common python scripts/study_output_options.py models
    uv run python scripts/study_output_options.py coverage --index data/baseline.sqlite
    uv run python scripts/study_output_options.py speed
    uv run python scripts/study_output_options.py report --index data/baseline.sqlite

`models` builds the int8 and fp16 text models from the pinned PP-OCRv4 file. The static int8
one is calibrated on library images, so it is derived from personal images and stays under
lab/data/ with everything else this script writes. `coverage` runs every text variant on
the index's non-stickers. `report` prints aggregate numbers only, no paths.

- O1: no FACES on stickers.
- O2: no BLURRY and BAD_EXPOSURE on stickers.
- O3: a smaller text input, or an int8 or fp16 text model.
- O4: no text coverage when the zero-shot probability alone clears a cutoff.
"""

import argparse
import functools
import json
import multiprocessing
import random
import statistics
import time
from collections.abc import Mapping, Sequence
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

from cribrum_lab import prompts, text
from cribrum_lab.comparison import Comparison, compare, with_signals, without_signals
from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.detectors.document import FULL_COVERAGE, TEXT_WEIGHT, DocumentDetector
from cribrum_lab.evaluation import CategoryResult, evaluate
from cribrum_lab.folders import is_sticker_folder
from cribrum_lab.index import ImageIndex
from cribrum_lab.labels import Decision, Label, LabelStore
from cribrum_lab.mobileclip import MobileClip
from cribrum_lab.model import Category, IndexedImage, Protection, Signal
from cribrum_lab.ranker import THRESHOLDS, Ranker
from cribrum_lab.runtime import OnnxRuntime

DATA = Path(__file__).resolve().parents[1] / "data"
VARIANTS_DIR = DATA / "models-variants"
STUDY = DATA / "study"
SOURCE = DATA / "models" / "ppocr" / "ch_PP-OCRv4_det_infer.onnx"
CALIBRATION = VARIANTS_DIR / "calibration.txt"
CALIBRATION_IMAGES = 128

VARIANTS = {
    "fp32 640": ("ch_PP-OCRv4_det_infer.onnx", 640),
    "fp32 480": ("ch_PP-OCRv4_det_infer.onnx", 480),
    "int8 640": ("det_int8_qdq.onnx", 640),
    "int8 480": ("det_int8_qdq.onnx", 480),
    "int8 dynamic 640": ("det_int8_dynamic.onnx", 640),
    "fp16 640": ("det_fp16.onnx", 640),
}
"""Text variants: model file and input side. "fp32 640" is today's, computed again."""
TODAY = "fp32 640"
O4_CUTOFFS = (0.2, 0.3, 0.5, 0.8, 0.95)
USER_THRESHOLDS = tuple(round(0.05 * step, 2) for step in range(1, 21))
"""Every DOCUMENT threshold the settings slider offers: 0.05 to 1 in steps of 0.05
(docs/design.md, 6.1; `Thresholds.kt`, `ThresholdsScreen.kt`)."""
RETUNED = (0.15, 0.1, 0.05)
"""Lower DOCUMENT thresholds tried for a text variant, against today at the default."""
BOOTSTRAP_ROUNDS = 2000


def model_path(file: str) -> Path:
    return SOURCE if file == SOURCE.name else VARIANTS_DIR / file


# --- models ---


def build_models(index_path: Path) -> None:
    """Needs onnx and onnxconverter-common, which the lab does not depend on."""
    import onnx
    import onnx.version_converter
    from onnxconverter_common import float16
    from onnxruntime.quantization import (
        CalibrationDataReader,
        CalibrationMethod,
        QuantFormat,
        QuantType,
        quantize_dynamic,
        quantize_static,
    )
    from onnxruntime.quantization.shape_inference import quant_pre_process

    VARIANTS_DIR.mkdir(parents=True, exist_ok=True)
    # Per-channel quantization needs opset 13. The pre-processed graph also folds the batch
    # norms into the convolutions: unfolded, they break in fp16.
    op13 = VARIANTS_DIR / "det_op13.onnx"
    onnx.save(onnx.version_converter.convert_version(onnx.load(SOURCE), 13), op13)
    folded = VARIANTS_DIR / "det_folded.onnx"
    quant_pre_process(str(op13), str(folded), skip_symbolic_shape=True)
    half = float16.convert_float_to_float16(onnx.load(folded), keep_io_types=True)
    onnx.save(half, VARIANTS_DIR / "det_fp16.onnx")
    quantize_dynamic(folded, VARIANTS_DIR / "det_int8_dynamic.onnx", weight_type=QuantType.QUInt8)

    with ImageIndex(index_path) as index:
        unreadable = set(index.unreadable())
        real = sorted(
            i.path
            for i in index.images()
            if i.path not in unreadable and not is_sticker_folder(i.path)
        )
    random.Random(359).shuffle(real)
    calibration = real[:CALIBRATION_IMAGES]
    CALIBRATION.write_text("".join(f"{path}\n" for path in calibration))

    class Reader(CalibrationDataReader):
        def __init__(self) -> None:
            self._paths = iter(calibration)

        def get_next(self):
            for path in self._paths:
                try:
                    return {"x": text.preprocess(path)}
                except Exception:
                    continue
            return None

    quantize_static(
        folded,
        VARIANTS_DIR / "det_int8_qdq.onnx",
        Reader(),
        quant_format=QuantFormat.QDQ,
        per_channel=True,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
        calibrate_method=CalibrationMethod.MinMax,
    )


# --- coverage ---


@functools.cache
def _runtime(file: str) -> OnnxRuntime:
    return OnnxRuntime(model_path(file), threads=1)


def _coverages(path: Path) -> tuple[str, dict[str, float] | None]:
    """Every variant's coverage of one image, in a worker process."""
    try:
        inputs = {side: text.preprocess(path, side) for side in {s for _, s in VARIANTS.values()}}
        result = {}
        for name, (file, side) in VARIANTS.items():
            [probability] = _runtime(file).run({"x": inputs[side]}).values()
            result[name] = float((probability > text.TEXT_PROBABILITY).mean())
        return str(path), result
    except Exception:
        return str(path), None


def compute_coverage(index_path: Path, out: Path, jobs: int) -> None:
    with ImageIndex(index_path) as index:
        images = index.indexed_images()
    paths = [i.ref.path for i in images if any(s.detector_id == "document" for s in i.signals)]
    result, failed = {}, 0
    context = multiprocessing.get_context("forkserver")
    with ProcessPoolExecutor(jobs, mp_context=context) as pool:
        for done, (path, coverage) in enumerate(pool.map(_coverages, paths, chunksize=8), 1):
            if coverage is None:
                failed += 1
            else:
                result[path] = coverage
            if done % 200 == 0:
                print(f"{done}/{len(paths)}", flush=True)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result))
    print(f"{len(result)} images, {failed} failed")


# --- speed ---


def measure_speed(coverage_file: Path, out: Path, images: int, rounds: int) -> None:
    """Model time alone, one thread as in the app, on the same real inputs for every variant.
    The variants run in turn, the order reversed every round, and the median round counts."""
    paths = sorted(json.loads(coverage_file.read_text()))
    calibration = set(CALIBRATION.read_text().splitlines())
    paths = [p for p in paths if p not in calibration]
    random.Random(1).shuffle(paths)
    sides = {side for _, side in VARIANTS.values()}
    inputs = {side: [text.preprocess(Path(p), side) for p in paths[:images]] for side in sides}
    # Interleaved and repeated like the models, the order reversed every round.
    preprocess = {side: [] for side in sides}
    for round_ in range(rounds):
        for side in sorted(sides, reverse=round_ % 2 == 1):
            start = time.perf_counter()
            for p in paths[:images]:
                text.preprocess(Path(p), side)
            preprocess[side].append(time.perf_counter() - start)
    preprocess = {side: statistics.median(t) for side, t in preprocess.items()}
    sessions = {
        name: OnnxRuntime(model_path(file), threads=1) for name, (file, _) in VARIANTS.items()
    }
    for name, (_, side) in VARIANTS.items():  # warm up
        sessions[name].run({"x": inputs[side][0]})
    times = {name: [] for name in VARIANTS}
    names = list(VARIANTS)
    for round_ in range(rounds):
        for name in names if round_ % 2 == 0 else reversed(names):
            side = VARIANTS[name][1]
            start = time.perf_counter()
            for x in inputs[side]:
                sessions[name].run({"x": x})
            times[name].append(time.perf_counter() - start)
    medians = {name: statistics.median(t) for name, t in times.items()}
    result = {
        "images": images,
        "rounds": rounds,
        "model_ms": {name: 1000 * t / images for name, t in medians.items()},
        "model_ratio": {name: t / medians[TODAY] for name, t in medians.items()},
        "preprocess_ratio": {str(side): t / preprocess[640] for side, t in preprocess.items()},
    }
    out.write_text(json.dumps(result, indent=1))
    print(json.dumps(result, indent=1))


# --- report ---


def document_signals(
    images: Sequence[IndexedImage],
    embeddings: Mapping[Path, np.ndarray],
    coverage: Mapping[Path, float],
) -> dict[Path, list[Signal]]:
    """DOCUMENT's signals with the coverage given, for the images it has one for."""
    detector = DocumentDetector(coverage=lambda path: coverage[path])
    context = AnalysisContext(embedding=lambda image: embeddings[image.path])
    return {i.ref.path: detector.analyze(i.ref, context) for i in images if i.ref.path in coverage}


def skip_coverage(
    coverage: Mapping[Path, float], probability: Mapping[Path, float], cutoff: float
) -> dict[Path, float]:
    """O4: no coverage, so no text chance, where the zero-shot probability reaches `cutoff`."""
    return {path: 0.0 if probability[path] >= cutoff else c for path, c in coverage.items()}


def agreement(today: Sequence[float], variant: Sequence[float]) -> dict[str, float]:
    a, b = np.asarray(today), np.asarray(variant)
    chance = lambda c: TEXT_WEIGHT * np.minimum(1.0, c / FULL_COVERAGE)  # noqa: E731
    gap = np.abs(chance(b) - chance(a))
    return {
        "mean_abs": float(np.abs(b - a).mean()),
        "p95_abs": float(np.quantile(np.abs(b - a), 0.95)),
        "pearson": float(np.corrcoef(a, b)[0, 1]),
        "text_chance_off_by_0.05": float((gap > 0.05).mean()),
        "text_chance_mean_abs": float(gap.mean()),
    }


def protection_levels(images: Sequence[IndexedImage]) -> dict[str, float]:
    """How the STICKER list is ordered: every sticker scores 1, so only protection and then
    the path hash order it."""
    stickers = [i for i in images if is_sticker_folder(i.ref.path)]
    ranked = [c for c in Ranker().rank(stickers) if c.is_candidate]
    levels = [round(c.protection, 9) for c in ranked]
    counts = {level: levels.count(level) for level in set(levels)}
    faces = [
        max((s.score for s in i.signals if s.category is Protection.FACES), default=0.0)
        for i in stickers
    ]
    return {
        "stickers": len(stickers),
        "with_a_face": sum(score > 0 for score in faces) / len(stickers) if stickers else 0.0,
        "levels": len(counts),
        "largest_level_share": max(counts.values()) / len(levels) if levels else 0.0,
    }


def face_rates(images: Sequence[IndexedImage], labels: Sequence[Label]) -> dict[str, float]:
    """Share of the labeled stickers, kept and deleted, with a FACES score above 0."""
    faces = {
        i.ref.path: any(s.category is Protection.FACES and s.score > 0 for s in i.signals)
        for i in images
        if is_sticker_folder(i.ref.path)
    }
    rates = {}
    for decision in (Decision.KEEP, Decision.DELETE):
        found = [faces[x.path] for x in labels if x.decision is decision and x.path in faces]
        rates[f"{decision.value}s"] = len(found)
        rates[f"{decision.value}s_with_a_face"] = sum(found) / len(found) if found else 0.0
    return rates


def bootstrap_deletes_first(
    before: Sequence[IndexedImage],
    after: Sequence[IndexedImage],
    labels: Sequence[Label],
    category: Category,
    rounds: int = BOOTSTRAP_ROUNDS,
    seed: int = 0,
) -> tuple[float, float]:
    """95% interval of the change in `deletes_first` of the category's list, after minus
    before, resampling the labeled images it lists. Both orders see the same resample."""

    def positions(images: Sequence[IndexedImage]) -> dict[Path, int]:
        ranked = [c for c in Ranker().rank(images) if c.is_candidate]
        listed = Ranker.by_category(ranked).get(category, [])
        return {c.image.ref.path: i for i, c in enumerate(listed)}

    old, new = positions(before), positions(after)
    decisions = {x.path: x.decision for x in labels}
    labeled = [p for p in old if p in decisions and p in new]
    deletes = np.array([decisions[p] is Decision.DELETE for p in labeled])
    at_old = np.array([old[p] for p in labeled])
    at_new = np.array([new[p] for p in labeled])
    rng = np.random.default_rng(seed)
    changes = []
    for _ in range(rounds):
        pick = rng.integers(0, len(labeled), len(labeled))
        if deletes[pick].all() or not deletes[pick].any():
            continue
        changes.append(_auc(at_new[pick], deletes[pick]) - _auc(at_old[pick], deletes[pick]))
    low, high = np.quantile(changes, [0.025, 0.975])
    return float(low), float(high)


def _auc(positions: np.ndarray, deletes: np.ndarray) -> float:
    """Chance that a delete is listed before a keep."""
    return float((positions[deletes][:, None] < positions[~deletes][None, :]).mean())


def report(index_path: Path, labels_path: Path, coverage_file: Path, speed_file: Path) -> str:
    with ImageIndex(index_path) as index:
        images = index.indexed_images()
        embeddings = index.embeddings(MobileClip.id, MobileClip.version)
    with LabelStore(labels_path) as store:
        labels = store.all()
    coverage = {Path(p): c for p, c in json.loads(coverage_file.read_text()).items()}
    calibration = {Path(p) for p in CALIBRATION.read_text().splitlines()}
    speed = json.loads(speed_file.read_text()) if speed_file.exists() else None

    paths = {i.ref.path for i in images}
    matched = [label for label in labels if label.path in paths]
    sticker_labels = [label for label in matched if is_sticker_folder(label.path)]
    stickers = sum(is_sticker_folder(p) for p in paths)
    lines = [
        f"{len(images)} images, {stickers} in sticker folders, {len(coverage)} with text coverage",
        f"{len(matched)} labels match the index: "
        f"{sum(x.decision is Decision.DELETE for x in matched)} delete, "
        f"{sum(x.decision is Decision.KEEP for x in matched)} keep, "
        f"{sum(x.decision is Decision.DELETE for x in sticker_labels)} delete "
        f"and {sum(x.decision is Decision.KEEP for x in sticker_labels)} keep among stickers",
    ]

    # O1, O2
    o1 = without_signals(images, {"faces"}, is_sticker_folder)
    o2 = without_signals(images, {"blur", "exposure"}, is_sticker_folder)
    both = without_signals(o1, {"blur", "exposure"}, is_sticker_folder)
    for name, after in (("O1", o1), ("O2", o2), ("O1 and O2", both)):
        lines += ["", f"== {name}", *format_comparison(compare(images, after, labels))]
    lines += ["", "STICKER list, how it is ordered (protection only)"]
    for name, variant in (("today", images), ("O1", o1), ("O2", o2), ("O1 and O2", both)):
        lines.append(f"  {name:10} {_format_numbers(protection_levels(variant))}")
    blur_in_burst = sum(
        is_sticker_folder(i.ref.path) and any(s.category is Category.BURST for s in i.signals)
        for i in images
    )
    lines.append(f"  stickers with a BURST signal, which reads blur's measure: {blur_in_burst}")
    lines.append(f"  labeled stickers: {_format_numbers(face_rates(images, labels))}")
    low, high = bootstrap_deletes_first(images, o1, labels, Category.STICKER)
    lines.append(
        f"  O1's change in STICKER deletes first, 95% bootstrap interval over the labeled "
        f"stickers ({BOOTSTRAP_ROUNDS} rounds): {low:.3f} to {high:.3f}"
    )
    lines += ["", "evaluation rows (deletes, only, keeps, candidates), today -> O2"]
    rows = [{r.category: r for r in evaluate(v, labels).categories} for v in (images, o2)]
    for category in (Category.BLURRY, Category.BAD_EXPOSURE, Category.STICKER):
        today_row, o2_row = (_evaluation_row(r[category]) for r in rows)
        lines.append(f"  {category.value}: {today_row} -> {o2_row}")

    # O3
    lines += ["", "== O3"]
    outside = [p for p in coverage if p not in calibration]
    stored = {
        image.ref.path: s.evidence["text_coverage"]
        for image in images
        for s in image.signals
        if s.detector_id == "document" and image.ref.path in coverage
    }
    same = sum(f"{coverage[p][TODAY]:.4f}" == value for p, value in stored.items())
    lines.append(f"today's coverage computed again matches the index on {same}/{len(stored)}")
    by_variant = {name: {path: c[name] for path, c in coverage.items()} for name in VARIANTS}
    today_signals = document_signals(images, embeddings, by_variant[TODAY])
    base = with_signals(images, "document", today_signals)
    for name in VARIANTS:
        if name == TODAY:
            continue
        lines += ["", f"-- {name}"]
        if speed:
            lines.append(
                f"  model time {speed['model_ratio'][name]:.2f} of today's "
                f"({speed['model_ms'][name]:.0f} ms against {speed['model_ms'][TODAY]:.0f} ms)"
            )
        lines.append(
            "  coverage against today's, outside the calibration images: "
            + _format_numbers(
                agreement(
                    [by_variant[TODAY][p] for p in outside], [by_variant[name][p] for p in outside]
                )
            )
        )
        after = with_signals(
            images, "document", document_signals(images, embeddings, by_variant[name])
        )
        lines += format_comparison(compare(base, after, labels))
        lines += _user_thresholds(base, after)
        lines += _retuned(base, after)

    # O4
    lines += ["", "== O4"]
    probability = {p: prompts.classify(embeddings[p])["document"][0] for p in coverage}
    for cutoff in O4_CUTOFFS:
        skipped = sum(probability[p] >= cutoff for p in coverage)
        lines += [
            "",
            f"-- skip when p >= {cutoff}: {skipped}/{len(coverage)} images skip the text model "
            f"({skipped / len(coverage):.1%})",
        ]
        variant = skip_coverage(by_variant[TODAY], probability, cutoff)
        after = with_signals(images, "document", document_signals(images, embeddings, variant))
        lines += format_comparison(compare(base, after, labels))
        lines += _user_thresholds(base, after)
    if speed:
        lines += [
            "",
            f"speed: {speed['images']} images, median of {speed['rounds']} rounds, one thread, "
            f"ONNX Runtime CPU on the dev machine. Pillow preprocessing at 480 takes "
            f"{speed['preprocess_ratio']['480']:.2f} of the time at 640, interleaved the same way.",
        ]
    return "\n".join(lines)


def _evaluation_row(result: CategoryResult) -> str:
    return f"{result.caught}, {result.only_caught}, {result.false_positives}, {result.candidates}"


def _user_thresholds(before: Sequence[IndexedImage], after: Sequence[IndexedImage]) -> list[str]:
    """At every DOCUMENT threshold the user can set, both sides at that threshold: the
    candidates dropped and added, and the images that leave and join each category."""
    lines, unchanged = ["  at each user DOCUMENT threshold, -left/+joined:"], []
    for threshold in USER_THRESHOLDS:
        ranker = Ranker({**THRESHOLDS, Category.DOCUMENT: threshold})
        result = compare(before, after, ranker=ranker)
        moves = [
            f"{c.category.value} -{c.lost}/+{c.joined}"
            for c in result.categories
            if c.lost or c.joined
        ]
        if not (result.dropped or result.added or moves):
            unchanged.append(str(threshold))
            continue
        lines.append(
            f"    {threshold}: candidates -{result.dropped}/+{result.added}, " + ", ".join(moves)
        )
    lines.append(f"    no change at: {', '.join(unchanged) or '-'}")
    return lines


def _retuned(before: Sequence[IndexedImage], after: Sequence[IndexedImage]) -> list[str]:
    """Today's candidates at the default thresholds against the variant's at a lower DOCUMENT
    threshold: whether a retune wins back what the variant drops, and what it adds."""
    today = {c.image.ref.path for c in Ranker().rank(before) if c.is_candidate}
    parts = []
    for threshold in RETUNED:
        ranker = Ranker({**THRESHOLDS, Category.DOCUMENT: threshold})
        variant = {c.image.ref.path for c in ranker.rank(after) if c.is_candidate}
        parts.append(f"{threshold}: -{len(today - variant)}/+{len(variant - today)}")
    return [
        "  today at the default against the variant at a lower DOCUMENT threshold, "
        "-dropped/+added: " + ", ".join(parts)
    ]


def format_comparison(result: Comparison) -> list[str]:
    lines = [
        f"  candidates {result.candidates[0]} -> {result.candidates[1]}, "
        f"dropped {result.dropped}, added {result.added}, "
        f"labeled deletes caught {result.caught[0]} -> {result.caught[1]} of {result.deletable}, "
        f"reasons changed on {result.reasons_changed}",
    ]
    for c in result.categories:
        if (
            c.flagged[0] == c.flagged[1]
            and c.listed[0] == c.listed[1]
            and not c.lost
            and not c.moved
        ):
            continue
        lines.append(
            f"  {c.category.value}: flagged {c.flagged[0]} -> {c.flagged[1]}, lost {c.lost}, "
            f"deletes caught {c.caught[0]} -> {c.caught[1]}, "
            f"listed {c.listed[0]} -> {c.listed[1]}, {c.moved} moved, "
            f"first page kept {_number(c.first_page_kept)}, "
            f"spearman {_number(c.rank_correlation)}, "
            f"deletes first {_number(c.deletes_first[0])} -> {_number(c.deletes_first[1])}"
        )
    return lines


def _format_numbers(values: Mapping[str, float]) -> str:
    return ", ".join(f"{k} {_number(v)}" for k, v in values.items())


def _number(value: float | None) -> str:
    if value is None:
        return "-"
    return str(value) if isinstance(value, int) else f"{value:.4g}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["models", "coverage", "speed", "report"])
    parser.add_argument("--index", type=Path, default=DATA / "baseline.sqlite")
    parser.add_argument("--labels", type=Path, default=DATA / "labels.sqlite")
    parser.add_argument("--coverage", type=Path, default=STUDY / "coverage.json")
    parser.add_argument("--speed", type=Path, default=STUDY / "speed.json")
    parser.add_argument("--jobs", type=int, default=7)
    parser.add_argument("--images", type=int, default=60, help="images timed by speed")
    parser.add_argument("--rounds", type=int, default=6)
    args = parser.parse_args()
    if args.command == "models":
        build_models(args.index)
    elif args.command == "coverage":
        compute_coverage(args.index, args.coverage, args.jobs)
    elif args.command == "speed":
        measure_speed(args.coverage, args.speed, args.images, args.rounds)
    else:
        text_report = report(args.index, args.labels, args.coverage, args.speed)
        (STUDY / "report.txt").write_text(text_report + "\n")
        print(text_report)


if __name__ == "__main__":
    main()
