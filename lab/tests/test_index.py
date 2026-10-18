import sqlite3
from datetime import datetime
from pathlib import Path

import pytest

from cribrum_lab.index import ImageIndex, IndexVersionError
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


def test_changed_image_drops_its_stale_data_but_keeps_the_review_decision(index):
    index.record(PHOTO)
    index.set_metadata(PHOTO.path, META)
    index.set_unreadable(PHOTO.path)
    index.set_content_hash(PHOTO.path, "0123abcd")
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])
    index.set_review_state(PHOTO.path, ReviewState.KEPT)

    index.record(ImageRef(PHOTO.path, 200, 5))  # edited, or just touched by a sync tool

    assert index.metadata(PHOTO.path) is None
    assert index.unreadable() == []
    assert index.content_hash(PHOTO.path) is None
    assert index.signals(PHOTO.path) == []
    assert not index.is_analyzed(PHOTO.path, "blur", 1)
    assert index.review_state(PHOTO.path) is ReviewState.KEPT


@pytest.mark.parametrize("meta", [META, ImageMetadata(1080, 2400, "image/png")])
def test_metadata_round_trip(index, meta):
    index.record(PHOTO)
    index.set_metadata(PHOTO.path, meta)

    assert index.metadata(PHOTO.path) == meta


def test_content_hash_round_trip(index):
    index.record(PHOTO)
    assert index.content_hash(PHOTO.path) is None

    index.set_content_hash(PHOTO.path, "0123abcd")

    assert index.content_hash(PHOTO.path) == "0123abcd"


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


@pytest.mark.parametrize(
    "call",
    [
        lambda index: index.set_metadata(PHOTO.path, META),
        lambda index: index.set_content_hash(PHOTO.path, "0123abcd"),
        lambda index: index.set_unreadable(PHOTO.path),
        lambda index: index.content_hash(PHOTO.path),
        lambda index: index.set_signals(PHOTO.path, "blur", 1, [BLUR]),
        lambda index: index.set_review_state(PHOTO.path, ReviewState.KEPT),
        lambda index: index.review_state(PHOTO.path),
    ],
    ids=[
        "set_metadata",
        "set_content_hash",
        "set_unreadable",
        "content_hash",
        "set_signals",
        "set_review_state",
        "review_state",
    ],
)
def test_unrecorded_image_raises_key_error(index, call):
    with pytest.raises(KeyError):
        call(index)


def test_keeping_records_the_current_signals(index):
    index.record(PHOTO)
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])

    index.set_review_state(PHOTO.path, ReviewState.KEPT)
    index.set_signals(PHOTO.path, "blur", 2, [Signal("blur", 2, Category.BLURRY, 0.9)])

    [image] = index.indexed_images()
    assert image.review_state is ReviewState.KEPT
    assert image.signals == (Signal("blur", 2, Category.BLURRY, 0.9),)
    assert image.seen_when_kept == {BLUR.identity}


def test_leaving_kept_forgets_the_recorded_signals(index):
    index.record(PHOTO)
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])
    index.set_review_state(PHOTO.path, ReviewState.KEPT)

    index.set_review_state(PHOTO.path, ReviewState.UNREVIEWED)

    [image] = index.indexed_images()
    assert image.seen_when_kept == frozenset()


def test_indexed_images_lists_unanalyzed_images_too(index):
    index.record(PHOTO)
    other = ImageRef(Path("/photos/b.jpg"), 5, 5)
    index.record(other)
    index.set_signals(other.path, "blur", 1, [BLUR])

    assert [(i.ref, i.signals) for i in index.indexed_images()] == [(PHOTO, ()), (other, (BLUR,))]


def test_images_without_metadata_leave_out_unreadable_ones(index):
    other = ImageRef(Path("/photos/b.jpg"), 5, 5)
    broken = ImageRef(Path("/photos/c.jpg"), 5, 5)
    for image in [PHOTO, other, broken]:
        index.record(image)
    index.set_metadata(PHOTO.path, META)
    index.set_unreadable(broken.path)

    assert index.without_metadata() == [other]
    assert index.unreadable() == [broken.path]


def test_forget_removes_an_image_and_its_data(index):
    index.record(PHOTO)
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])

    index.forget([PHOTO.path])

    assert index.images() == []
    assert index.signals(PHOTO.path) == []


def test_signals_of_many_images_at_once(index):
    other = ImageRef(Path("/photos/b.jpg"), 5, 5)
    index.record(PHOTO)
    index.record(other)

    index.set_signals_of_many("blur", 1, {PHOTO.path: [BLUR], other.path: []})

    assert index.signals(PHOTO.path) == [BLUR]
    assert index.is_analyzed(other.path, "blur", 1)


def test_signals_of_many_store_nothing_if_one_image_is_missing(index):
    index.record(PHOTO)

    with pytest.raises(KeyError):
        index.set_signals_of_many("blur", 1, {PHOTO.path: [BLUR], Path("/nope.jpg"): []})

    assert index.signals(PHOTO.path) == []


def test_group_fingerprint_is_per_detector_version(index):
    assert index.group_fingerprint("exact_duplicate", 1) is None

    index.set_group_fingerprint("exact_duplicate", 1, "abc")

    assert index.group_fingerprint("exact_duplicate", 1) == "abc"
    assert index.group_fingerprint("exact_duplicate", 2) is None


def test_index_from_another_schema_version_is_refused(tmp_path):
    db = tmp_path / "index.sqlite"
    ImageIndex(db).close()
    with sqlite3.connect(db) as connection:
        connection.execute("PRAGMA user_version = 999")
    connection.close()

    with pytest.raises(IndexVersionError):
        ImageIndex(db)
