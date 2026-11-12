import importlib.util
from pathlib import Path

import pytest

from cribrum_lab import prompts
from cribrum_lab.labels import Decision, Label
from cribrum_lab.model import Category, ImageRef, IndexedImage, Protection, Signal

_SCRIPT = Path(__file__).parents[1] / "scripts" / "study_output_options.py"
_spec = importlib.util.spec_from_file_location("study_output_options", _SCRIPT)
study = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(study)

STICKERS = Path("WhatsApp/Media/WhatsApp Stickers")


def document_embedding():
    texts, vectors = prompts._embeddings()
    return vectors[texts.index("a photo of a document")]


def image(path, *signals):
    return IndexedImage(ImageRef(Path(path), 0, 0), signals=signals)


def test_document_signals_combine_the_zero_shot_with_the_coverage_given():
    images = [image("a"), image("b")]
    embeddings = {Path("a"): document_embedding(), Path("b"): document_embedding()}

    signals = study.document_signals(images, embeddings, {Path("a"): 0.0, Path("b"): 0.1})

    [a], [b] = signals[Path("a")], signals[Path("b")]
    probability = float(a.evidence["probability"])
    assert a.score == pytest.approx(probability, abs=5e-4)
    assert b.score == pytest.approx(1 - (1 - probability) * 0.2, abs=5e-4)


def test_skipping_the_coverage_only_touches_the_images_past_the_cutoff():
    coverage = {Path("low"): 0.05, Path("high"): 0.05}
    probability = {Path("low"): 0.1, Path("high"): 0.6}

    assert study.skip_coverage(coverage, probability, 0.5) == {Path("low"): 0.05, Path("high"): 0.0}


def test_agreement_of_equal_coverages_is_perfect():
    result = study.agreement([0.0, 0.02, 0.2], [0.0, 0.02, 0.2])

    assert result["mean_abs"] == 0.0
    assert result["pearson"] == pytest.approx(1.0)
    assert result["text_chance_off_by_0.05"] == 0.0


def test_agreement_counts_a_gap_in_text_chance_after_the_saturation():
    # Past FULL_COVERAGE (0.1) the text chance is 0.8 whatever the coverage: no gap.
    result = study.agreement([0.0, 0.2, 0.3], [0.01, 0.3, 0.2])

    assert result["text_chance_off_by_0.05"] == pytest.approx(1 / 3)


def test_protection_levels_show_how_the_sticker_list_is_ordered():
    sticker = Signal("sticker", 1, Category.STICKER, 1.0)
    images = [
        image(STICKERS / "1.webp", sticker, Signal("faces", 1, Protection.FACES, 0.9)),
        image(STICKERS / "2.webp", sticker, Signal("faces", 1, Protection.FACES, 0.0)),
        image(STICKERS / "3.webp", sticker, Signal("faces", 1, Protection.FACES, 0.0)),
        image("DCIM/Camera/photo.jpg", Signal("faces", 1, Protection.FACES, 0.9)),
    ]

    levels = study.protection_levels(images)

    assert levels["stickers"] == 3
    assert levels["with_a_face"] == pytest.approx(1 / 3)
    assert levels["levels"] == 2
    assert levels["largest_level_share"] == pytest.approx(2 / 3)


def sticker_with_face(name, face):
    return image(
        STICKERS / name,
        Signal("sticker", 1, Category.STICKER, 1.0),
        Signal("faces", 1, Protection.FACES, face),
    )


def test_face_rates_split_the_labeled_stickers_by_decision():
    images = [
        sticker_with_face("1.webp", 0.9),
        sticker_with_face("2.webp", 0.0),
        sticker_with_face("3.webp", 0.8),
        image("DCIM/Camera/photo.jpg", Signal("faces", 1, Protection.FACES, 0.9)),
    ]
    labels = [
        Label("1", STICKERS / "1.webp", Decision.KEEP),
        Label("2", STICKERS / "2.webp", Decision.KEEP),
        Label("3", STICKERS / "3.webp", Decision.DELETE),
        Label("p", Path("DCIM/Camera/photo.jpg"), Decision.KEEP),
    ]

    rates = study.face_rates(images, labels)

    assert rates == {
        "keeps": 2,
        "keeps_with_a_face": 0.5,
        "deletes": 1,
        "deletes_with_a_face": 1.0,
    }


def test_the_bootstrap_of_an_unchanged_order_is_zero():
    images = [sticker_with_face(f"{i}.webp", 0.1 * i) for i in range(6)]
    labels = [
        Label(str(i), STICKERS / f"{i}.webp", Decision.DELETE if i % 2 else Decision.KEEP)
        for i in range(6)
    ]

    assert study.bootstrap_deletes_first(images, images, labels, Category.STICKER, 50) == (
        0.0,
        0.0,
    )


def test_the_threshold_sweep_shows_what_leaves_and_joins_a_category():
    def document(name, score):
        return image(name, Signal("document", 3, Category.DOCUMENT, score))

    before = [document("a", 0.52), document("b", 0.48)]
    after = [document("a", 0.48), document("b", 0.52)]

    lines = study._user_thresholds(before, after)

    assert "    0.5: candidates -1/+1, document -1/+1" in lines
    assert lines[-1].startswith("    no change at: 0.05, 0.1,")
