from datetime import datetime
from pathlib import Path

import pytest

from cribrum_lab.index import ImageIndex
from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import Category, ImageRef, ReviewState, Signal

PHOTO = ImageRef(Path("/photos/a.jpg"), 100, 1)
META = ImageMetadata(4000, 3000, "image/jpeg", "Google", "Pixel 8", datetime(2024, 5, 1, 10, 20))
BLUR = Signal("blur", 1, Category.BLURRY, 0.7, {"variance": "12.5"})


@pytest.fixture
def index():
    with ImageIndex(":memory:") as index:
        yield index


def test_record_reports_new_and_changed_images(index):
    assert index.record(PHOTO)
    assert not index.record(PHOTO)
    assert index.record(ImageRef(PHOTO.path, 101, 1))
    assert index.record(ImageRef(PHOTO.path, 101, 2))
    assert index.images() == [ImageRef(PHOTO.path, 101, 2)]


def test_changed_image_drops_its_stale_data(index):
    index.record(PHOTO)
    index.set_metadata(PHOTO.path, META)
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])
    index.set_review_state(PHOTO.path, ReviewState.KEPT)

    index.record(ImageRef(PHOTO.path, 200, 5))

    assert index.metadata(PHOTO.path) is None
    assert index.signals(PHOTO.path) == []
    assert not index.is_analyzed(PHOTO.path, "blur", 1)
    assert index.review_state(PHOTO.path) is ReviewState.UNREVIEWED


@pytest.mark.parametrize("meta", [META, ImageMetadata(1080, 2400, "image/png")])
def test_metadata_round_trip(index, meta):
    index.record(PHOTO)
    index.set_metadata(PHOTO.path, meta)

    assert index.metadata(PHOTO.path) == meta


def test_signals_are_stored_per_detector_version(index):
    index.record(PHOTO)
    screenshot = Signal("screenshot", 3, Category.SCREENSHOT, 0.9)
    index.set_signals(PHOTO.path, "screenshot", 3, [screenshot])
    index.set_signals(PHOTO.path, "blur", 1, [Signal("blur", 1, Category.BLURRY, 0.2)])

    index.set_signals(PHOTO.path, "blur", 2, [BLUR])

    assert sorted(index.signals(PHOTO.path), key=lambda s: s.detector_id) == [BLUR, screenshot]
    assert index.is_analyzed(PHOTO.path, "blur", 2)
    assert not index.is_analyzed(PHOTO.path, "blur", 1)


def test_detector_with_no_signals_still_counts_as_analyzed(index):
    index.record(PHOTO)
    index.set_signals(PHOTO.path, "blur", 1, [])

    assert index.is_analyzed(PHOTO.path, "blur", 1)


def test_review_state_persists(tmp_path):
    db = tmp_path / "index.sqlite"
    with ImageIndex(db) as index:
        index.record(PHOTO)
        assert index.review_state(PHOTO.path) is ReviewState.UNREVIEWED
        index.set_review_state(PHOTO.path, ReviewState.KEPT)

    with ImageIndex(db) as index:
        assert index.review_state(PHOTO.path) is ReviewState.KEPT
