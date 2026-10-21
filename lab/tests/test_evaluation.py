from pathlib import Path

import pytest

from cribrum_lab.evaluation import evaluate, format_report
from cribrum_lab.labels import Decision, Label, Stratum
from cribrum_lab.model import Category, ImageRef, IndexedImage, ReviewState, Signal

THRESHOLDS = {Category.BLURRY: 0.5, Category.SCREENSHOT: 0.5}


def image(name, blur=0.0, screenshot=0.0, state=ReviewState.UNREVIEWED):
    signals = (
        Signal("blur", 1, Category.BLURRY, blur),
        Signal("screenshot", 1, Category.SCREENSHOT, screenshot),
    )
    return IndexedImage(ImageRef(Path(name), 0, 0), state, signals)


def label(name, decision):
    return Label(name, Path(name), decision)


IMAGES = [
    image("blur-caught", blur=0.9),
    image("blur-barely", blur=0.55, state=ReviewState.KEPT),
    image("blur-missed", blur=0.3),
    image("screen", screenshot=0.95),
    image("photo"),
    image("photo-flagged", blur=0.7),
    image("unlabeled", blur=0.8),
]
LABELS = [
    label("blur-caught", Decision.DELETE),
    label("blur-barely", Decision.DELETE),
    label("blur-missed", Decision.DELETE),
    label("screen", Decision.DELETE),
    label("photo", Decision.KEEP),
    label("photo-flagged", Decision.KEEP),
    label("gone", Decision.DELETE),
]


def by_category(report, category):
    return next(r for r in report.categories if r.category is category)


def test_overall_recall_and_its_cost():
    report = evaluate(IMAGES, LABELS, THRESHOLDS)

    assert report.unmatched == 1
    assert (report.caught, report.deletable) == (3, 4)
    assert report.false_positives == 1
    assert report.candidates == 5
    assert report.missed == [Path("blur-missed")]


def test_each_category_counts_the_labels_it_flags_and_its_candidates():
    report = evaluate(IMAGES, LABELS, THRESHOLDS)

    blurry = by_category(report, Category.BLURRY)
    assert (blurry.caught, blurry.false_positives, blurry.candidates) == (2, 1, 4)
    document = by_category(report, Category.DOCUMENT)
    assert (document.caught, document.false_positives, document.candidates) == (0, 0, 0)


def at(sweep, threshold):
    return next(p for p in sweep if p.threshold == threshold)


def test_sweep_counts_what_each_threshold_catches_and_lists():
    report = evaluate(IMAGES, LABELS, THRESHOLDS)

    sweep = by_category(report, Category.BLURRY).sweep
    low, high = at(sweep, 0.55), at(sweep, 0.8)
    assert (low.caught, low.false_positives, low.listed) == (2, 1, 4)
    assert (high.caught, high.false_positives, high.listed) == (1, 0, 2)


def test_sweep_leaves_out_images_a_sticker_owns():
    stickers = {Category.STICKER: 0.5}
    images = [
        IndexedImage(
            ImageRef(Path("sticker"), 0, 0),
            ReviewState.UNREVIEWED,
            (Signal("blur", 1, Category.BLURRY, 0.9), Signal("sticker", 1, Category.STICKER, 1.0)),
        ),
        image("soft", blur=0.9),
    ]
    labels = [label("sticker", Decision.DELETE), label("soft", Decision.DELETE)]

    report = evaluate(images, labels, THRESHOLDS | stickers)

    # the review lists a sticker under STICKER only (docs/design.md, 8)
    assert at(by_category(report, Category.BLURRY).sweep, 0.5).listed == 1
    assert at(by_category(report, Category.STICKER).sweep, 0.5).listed == 1


def test_each_target_recall_gets_thresholds_and_their_cost():
    report = evaluate(IMAGES, LABELS, THRESHOLDS, targets=(0.5, 1.0))

    half, full = report.curve
    assert (half.target, half.caught, half.false_positives, half.candidates) == (0.5, 2, 0, 2)
    assert (full.target, full.caught, full.false_positives, full.candidates) == (1.0, 4, 1, 6)
    assert full.thresholds[Category.BLURRY] == 0.3
    assert full.thresholds[Category.SCREENSHOT] == 0.95
    assert full.thresholds[Category.DOCUMENT] == 0.95


def test_the_category_that_costs_fewer_candidates_is_lowered_first():
    images = [
        image("blurry", blur=0.4),
        image("screen", screenshot=0.4),
        *[image(f"soft{i}", blur=0.45) for i in range(5)],
    ]
    labels = [label("blurry", Decision.DELETE), label("screen", Decision.DELETE)]

    (point,) = evaluate(images, labels, THRESHOLDS, targets=(0.5,)).curve

    assert point.thresholds[Category.SCREENSHOT] == 0.4
    assert point.thresholds[Category.BLURRY] == 0.95
    assert (point.caught, point.candidates) == (1, 1)


def test_a_threshold_a_later_move_made_unnecessary_goes_back_up():
    images = [
        image("both", blur=0.9, screenshot=0.3),
        image("screen", screenshot=0.3),
        image("other-screen", screenshot=0.3),
    ]
    labels = [label("both", Decision.DELETE), label("screen", Decision.DELETE)]

    (point,) = evaluate(images, labels, THRESHOLDS, targets=(1.0,)).curve

    # blur 0.9 is lowered first, for one new candidate; screenshot 0.3 then catches both
    assert point.thresholds[Category.BLURRY] == 0.95
    assert point.thresholds[Category.SCREENSHOT] == 0.3


def test_a_target_nothing_can_reach_stops_when_no_threshold_catches_more():
    report = evaluate([image("plain")], [label("plain", Decision.DELETE)], THRESHOLDS)

    assert all(p.caught == 0 for p in report.curve)
    assert {t for p in report.curve for t in p.thresholds.values()} == {0.95}


def test_report_text_lists_categories_curve_and_missed_images():
    text = format_report(evaluate(IMAGES, LABELS, THRESHOLDS, targets=(1.0,)))

    assert "recall: 75% (3/4)" in text
    assert "blurry" in text
    assert "100%  4/4" in text
    assert "  blur-missed" in text


def test_missed_images_are_counted_by_folder_kind_and_closest_category():
    images = [
        image("DCIM/Camera/soft.jpg", blur=0.3),
        image("DCIM/Camera/softer.jpg", blur=0.1),
        image("WhatsApp/Media/WhatsApp Images/Sent/a.jpg", screenshot=0.4),
        image("WhatsApp/Media/WhatsApp Stickers/s.webp"),
        image("Download/plain.jpg"),
    ]
    labels = [label(i.ref.path.as_posix(), Decision.DELETE) for i in images]

    report = evaluate(images, labels, THRESHOLDS)

    assert report.missed_by_folder == {
        "camera": 2,
        "messaging app": 1,
        "sticker folder": 1,
        "other": 1,
    }
    assert report.missed_by_closest == {"blurry": 2, "screenshot": 1, "none": 2}


def test_report_text_counts_the_missed_images_without_their_paths_first():
    text = format_report(evaluate(IMAGES, LABELS, THRESHOLDS))

    assert text.index("by folder kind") < text.index("other: 1") < text.index(" paths:")
    assert text.index("by closest category") < text.index("blurry: 1") < text.index(" paths:")


# A library of 2 candidates and 8 unflagged images (at the thresholds the sample was drawn
# with), labeled 2 and 2: a label of an unflagged image stands for 4 images, a candidate's for 1.
SAMPLED_WITH = {Category.BLURRY: 0.5}
LIBRARY = [
    image("c1", blur=0.9),
    image("c2", blur=0.9),
    image("u1", blur=0.3),
    *[image(f"u{i}") for i in range(2, 9)],
]
SAMPLE = [
    label("c1", Decision.DELETE),
    label("c2", Decision.KEEP),
    label("u1", Decision.DELETE),
    label("u2", Decision.DELETE),
]


def test_library_recall_weights_each_label_by_the_size_of_its_stratum():
    report = evaluate(LIBRARY, SAMPLE, {Category.BLURRY: 0.2}, sampled_with=SAMPLED_WITH)

    assert report.recall == 2 / 3
    # caught: c1 (weight 1) and u1 (weight 4); missed: u2 (weight 4)
    assert report.weighted_recall == 5 / 9
    assert report.derived_strata == 4


def test_a_recorded_stratum_beats_the_one_derived_from_the_sampling_thresholds():
    sample = [*SAMPLE[:3], Label("u2", Path("u2"), Decision.DELETE, Stratum.CANDIDATE)]

    report = evaluate(LIBRARY, sample, {Category.BLURRY: 0.2}, sampled_with=SAMPLED_WITH)

    # 3 labels stand for the 2 candidates (2/3 each), 1 for the 8 unflagged images (8)
    assert report.weighted_recall == pytest.approx(13 / 14)
    assert report.derived_strata == 3


def test_each_curve_point_has_its_library_recall_too():
    report = evaluate(LIBRARY, SAMPLE, THRESHOLDS, targets=(1.0,), sampled_with=SAMPLED_WITH)

    assert report.curve[0].weighted_recall == 5 / 9


def test_report_text_prints_the_library_recall_next_to_the_sample_recall():
    report = evaluate(LIBRARY, SAMPLE, {Category.BLURRY: 0.2}, sampled_with=SAMPLED_WITH)
    text = format_report(report)

    assert "recall: 67% (2/3)" in text
    assert "library recall: 56%" in text
    assert "4 labels without a stratum" in text


def test_a_category_counts_the_deletes_no_other_category_flags():
    images = [*IMAGES, image("both", blur=0.9, screenshot=0.9)]
    labels = [*LABELS, label("both", Decision.DELETE)]

    report = evaluate(images, labels, THRESHOLDS)

    blurry, screenshot = (
        by_category(report, Category.BLURRY),
        by_category(report, Category.SCREENSHOT),
    )
    assert (blurry.caught, blurry.only_caught) == (3, 2)
    assert (screenshot.caught, screenshot.only_caught) == (2, 1)


def test_report_text_headers_say_what_the_numbers_cover():
    text = format_report(evaluate(IMAGES, LABELS, THRESHOLDS))

    assert "blurry: threshold sweep, outside sticker folders" in text
    assert "sticker: threshold sweep\n" in text
    assert "not lowered (only scores of 0.95 or more flag)" in text


def test_sweep_counts_what_a_threshold_adds_to_the_candidates_the_others_make():
    images = [
        image("a", blur=0.9),
        image("both", blur=0.9, screenshot=0.9),
        image("keep", blur=0.6),
    ]
    labels = [
        label("a", Decision.DELETE),
        label("both", Decision.DELETE),
        label("keep", Decision.KEEP),
    ]

    sweep = by_category(evaluate(images, labels, THRESHOLDS), Category.BLURRY).sweep

    low, high = at(sweep, 0.5), at(sweep, 0.8)
    assert (low.caught, low.listed) == (2, 3)
    # "both" is a candidate for its screenshot score anyway
    assert (low.added_caught, low.added_false_positives, low.added_candidates) == (1, 1, 2)
    assert (high.added_caught, high.added_false_positives, high.added_candidates) == (1, 0, 1)
