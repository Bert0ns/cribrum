import importlib.util
from pathlib import Path

import pytest

from cribrum_lab import prompts
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
