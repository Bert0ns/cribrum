from pathlib import Path

from cribrum_lab.evaluation import evaluate, format_report
from cribrum_lab.labels import Decision, Label
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


def test_suggested_thresholds_reach_the_target_recall():
    report = evaluate(IMAGES, LABELS, THRESHOLDS)

    assert by_category(report, Category.BLURRY).suggested_threshold == 0.3
    assert by_category(report, Category.SCREENSHOT).suggested_threshold == 0.95
    assert by_category(report, Category.DOCUMENT).suggested_threshold == 0.95
    suggestion = report.suggestion
    assert (suggestion.caught, suggestion.false_positives, suggestion.candidates) == (4, 1, 6)


def test_the_category_that_costs_fewer_candidates_is_lowered_first():
    images = [
        image("blurry", blur=0.4),
        image("screen", screenshot=0.4),
        *[image(f"soft{i}", blur=0.45) for i in range(5)],
    ]
    labels = [label("blurry", Decision.DELETE), label("screen", Decision.DELETE)]

    report = evaluate(images, labels, THRESHOLDS, target=0.5)

    assert by_category(report, Category.SCREENSHOT).suggested_threshold == 0.4
    assert by_category(report, Category.BLURRY).suggested_threshold == 0.95
    assert (report.suggestion.caught, report.suggestion.candidates) == (1, 1)


def test_a_threshold_a_later_move_made_unnecessary_goes_back_up():
    images = [
        image("both", blur=0.9, screenshot=0.3),
        image("screen", screenshot=0.3),
        image("other-screen", screenshot=0.3),
    ]
    labels = [label("both", Decision.DELETE), label("screen", Decision.DELETE)]

    report = evaluate(images, labels, THRESHOLDS, target=1.0)

    # blur 0.9 is lowered first, for one new candidate; screenshot 0.3 then catches both
    assert by_category(report, Category.BLURRY).suggested_threshold == 0.95
    assert by_category(report, Category.SCREENSHOT).suggested_threshold == 0.3


def test_suggestion_stops_when_no_threshold_catches_more():
    report = evaluate([image("plain")], [label("plain", Decision.DELETE)], THRESHOLDS)

    assert report.suggestion.caught == 0
    assert {r.suggested_threshold for r in report.categories} == {0.95}


def test_report_text_lists_categories_suggestion_and_missed_images():
    text = format_report(evaluate(IMAGES, LABELS, THRESHOLDS))

    assert "recall: 75% (3/4)" in text
    assert "blurry" in text
    assert "suggested thresholds: recall 100% (4/4)" in text
    assert "  blur-missed" in text
