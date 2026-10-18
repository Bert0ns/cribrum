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


def label(name, decision, category=None):
    return Label(name, Path(name), decision, category)


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
    label("blur-caught", Decision.DELETE, Category.BLURRY),
    label("blur-barely", Decision.DELETE, Category.BLURRY),
    label("blur-missed", Decision.DELETE, Category.BLURRY),
    label("screen", Decision.DELETE, Category.SCREENSHOT),
    label("photo", Decision.KEEP),
    label("photo-flagged", Decision.KEEP),
    label("gone", Decision.DELETE),
]


def test_overall_recall_and_its_cost():
    report = evaluate(IMAGES, LABELS, THRESHOLDS)

    assert report.unmatched == 1
    assert (report.caught, report.deletable) == (3, 4)
    assert report.false_positives == 1
    assert report.candidates == 5
    assert report.missed == [Path("blur-missed")]


def test_recall_per_category_counts_kept_images_too():
    report = evaluate(IMAGES, LABELS, THRESHOLDS)

    blurry = next(r for r in report.categories if r.category is Category.BLURRY)
    assert (blurry.caught, blurry.labeled) == (2, 3)
    assert blurry.candidates == 4
    unlabeled = next(r for r in report.categories if r.category is Category.DOCUMENT)
    assert unlabeled.recall is None


def test_sweep_suggests_the_highest_threshold_reaching_the_target():
    report = evaluate(IMAGES, LABELS, THRESHOLDS)

    blurry = next(r for r in report.categories if r.category is Category.BLURRY)
    assert blurry.suggested_threshold == 0.3  # catches 0.9, 0.55 and 0.3
    sweep = dict((t, (recall, count)) for t, recall, count in blurry.sweep)
    assert sweep[0.3] == (1.0, 5)
    assert sweep[0.6] == (1 / 3, 3)
    screenshot = next(r for r in report.categories if r.category is Category.SCREENSHOT)
    assert screenshot.suggested_threshold == 0.95


def test_report_text_lists_categories_and_missed_images():
    text = format_report(evaluate(IMAGES, LABELS, THRESHOLDS))

    assert "recall: 75% (3/4)" in text
    assert "blurry" in text
    assert "  blur-missed" in text
