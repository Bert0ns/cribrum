import sqlite3
from datetime import datetime
from pathlib import Path

import numpy as np
import pytest

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, Role
from cribrum_lab.index import ImageIndex, IndexVersionError
from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import Category, ImageRef, Protection, ReviewState, Signal
from cribrum_lab.ranker import Ranker

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


def test_record_all_counts_the_new_and_changed_images_and_leaves_the_rest(index):
    other = ImageRef(Path("/photos/b.jpg"), 5, 5)
    fresh = ImageRef(Path("/photos/c.jpg"), 7, 7)
    index.record(PHOTO)
    index.record(other)
    index.set_metadata(other.path, META)

    changed_other = ImageRef(other.path, 6, 5)
    assert index.record_all([PHOTO, changed_other, fresh]) == 2

    assert index.images() == [PHOTO, changed_other, fresh]
    assert index.metadata(other.path) is None  # the changed one was reset, like record does


def test_a_batch_that_fails_part_way_leaves_the_index_as_it_was(index):
    index.record(PHOTO)

    def batch():
        yield ImageRef(Path("/photos/new-0.jpg"), 1, 1)
        yield ImageRef(Path("/photos/new-1.jpg"), 1, 1)
        raise OSError("disk full")

    with pytest.raises(OSError):
        index.record_all(batch())

    assert index.images() == [PHOTO]


def test_changed_image_drops_its_stale_data_but_keeps_the_review_decision(index):
    index.record(PHOTO)
    index.set_metadata(PHOTO.path, META)
    index.set_unreadable(PHOTO.path)
    index.set_content_hash(PHOTO.path, "0123abcd")
    index.set_perceptual_hash(PHOTO.path, 42)
    index.set_embeddings("clip", 1, {PHOTO.path: np.ones(4)})
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])
    index.set_review_state(PHOTO.path, ReviewState.KEPT)

    index.record(ImageRef(PHOTO.path, 200, 5))  # edited, or just touched by a sync tool

    assert index.metadata(PHOTO.path) is None
    assert index.unreadable() == []
    assert index.content_hash(PHOTO.path) is None
    assert index.perceptual_hash(PHOTO.path) is None
    assert index.embeddings("clip", 1) == {}
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
    assert index.perceptual_hash(PHOTO.path) is None

    index.set_content_hash(PHOTO.path, "0123abcd")

    assert index.content_hash(PHOTO.path) == "0123abcd"


def test_perceptual_hash_round_trip_uses_all_64_bits(index):
    index.record(PHOTO)
    assert index.perceptual_hash(PHOTO.path) is None

    index.set_perceptual_hash(PHOTO.path, 2**64 - 1)

    assert index.perceptual_hash(PHOTO.path) == 2**64 - 1


def test_embeddings_are_stored_per_model_version(index):
    index.record(PHOTO)
    vector = np.array([0.5, -0.25, 1.0], dtype=np.float32)

    index.set_embeddings("clip", 2, {PHOTO.path: vector})

    [(path, stored)] = index.embeddings("clip", 2).items()
    assert path == PHOTO.path
    np.testing.assert_array_equal(stored, vector)
    assert index.embeddings("clip", 1) == {}


def test_deleting_the_embeddings_leaves_the_rest_of_the_index_as_it_was(index):
    other = ImageRef(Path("/photos/b.jpg"), 5, 5)
    index.record(PHOTO)
    index.record(other)
    index.set_embeddings(
        "clip", 1, {PHOTO.path: np.ones(2, np.float32), other.path: np.ones(2, np.float32)}
    )
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])
    index.set_review_state(PHOTO.path, ReviewState.KEPT)
    index.exclude_folder(Path("/photos/memes"))

    index.delete_embeddings()

    assert index.embeddings("clip", 1) == {}
    assert index.images() == [PHOTO, other]
    assert index.signals(PHOTO.path) == [BLUR]
    assert index.is_analyzed(PHOTO.path, "blur", 1)
    assert index.review_state(PHOTO.path) == ReviewState.KEPT
    assert index.excluded_folders() == [Path("/photos/memes")]


def test_forgetting_analyses_drops_the_signals_of_the_chosen_detectors_only(index):
    index.record(PHOTO)
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])
    index.set_signals(PHOTO.path, "document", 1, [Signal("document", 1, Category.DOCUMENT, 0.9)])

    index.forget_analyses([PHOTO.path], ["document"])

    assert not index.is_analyzed(PHOTO.path, "document", 1)
    assert index.signals(PHOTO.path) == [BLUR]


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
        lambda index: index.set_perceptual_hash(PHOTO.path, 42),
        lambda index: index.perceptual_hash(PHOTO.path),
        lambda index: index.content_hash(PHOTO.path),
        lambda index: index.set_signals(PHOTO.path, "blur", 1, [BLUR]),
        lambda index: index.set_review_state(PHOTO.path, ReviewState.KEPT),
        lambda index: index.review_state(PHOTO.path),
    ],
    ids=[
        "set_metadata",
        "set_content_hash",
        "set_unreadable",
        "set_perceptual_hash",
        "perceptual_hash",
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


def group(group_id, role):
    score = 0.0 if role == Role.BEST else 0.8
    evidence = {GROUP_KEY: group_id, ROLE_KEY: role}
    return Signal("near_duplicate", 1, Category.NEAR_DUPLICATE, score, evidence)


def test_a_first_result_after_the_keep_counts_as_seen_and_a_later_group_does_not(index):
    index.record(PHOTO)
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])
    index.set_review_state(PHOTO.path, ReviewState.KEPT)

    index.set_signals(PHOTO.path, "near_duplicate", 1, [group("g", Role.BEST)])
    [image] = index.indexed_images()
    assert image.seen_when_kept == {BLUR.identity, group("g", Role.BEST).identity}
    assert Ranker().rank([image]) == []

    index.set_signals(PHOTO.path, "near_duplicate", 1, [group("g", Role.INFERIOR)])
    [image] = index.indexed_images()
    assert image.seen_when_kept == {BLUR.identity, group("g", Role.BEST).identity}
    assert [c.image.ref.path for c in Ranker().rank([image])] == [PHOTO.path]


def test_a_first_result_for_an_image_that_is_not_kept_is_not_recorded(index):
    index.record(PHOTO)

    index.set_signals(PHOTO.path, "blur", 1, [BLUR])

    [image] = index.indexed_images()
    assert image.seen_when_kept == frozenset()


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


def test_hashed_paths_lists_the_images_that_have_a_perceptual_hash(index):
    other = ImageRef(Path("/photos/b.jpg"), 100, 1)
    index.record(PHOTO)
    index.record(other)

    index.set_perceptual_hash(PHOTO.path, 0xFF)

    assert index.hashed_paths() == {PHOTO.path}


def test_group_fingerprint_is_per_detector_version(index):
    assert index.group_fingerprint("exact_duplicate", 1) is None

    index.set_group_fingerprint("exact_duplicate", 1, "abc")

    assert index.group_fingerprint("exact_duplicate", 1) == "abc"
    assert index.group_fingerprint("exact_duplicate", 2) is None


def test_a_group_fingerprint_keeps_the_paths_whose_content_hash_failed(index):
    assert index.group_hash_failed("exact_duplicate", 1) == []

    index.set_group_fingerprint(
        "exact_duplicate", 1, "abc", ["/photos/a [1].jpg", '/photos/b"\n.jpg']
    )

    assert index.group_fingerprint("exact_duplicate", 1) == "abc"
    assert index.group_hash_failed("exact_duplicate", 1) == [
        "/photos/a [1].jpg",
        '/photos/b"\n.jpg',
    ]

    index.set_group_fingerprint("exact_duplicate", 1, "def")

    assert index.group_fingerprint("exact_duplicate", 1) == "def"
    assert index.group_hash_failed("exact_duplicate", 1) == []


def test_not_images_are_stored_by_path_the_newest_size_and_mtime_winning(index):
    photo = ImageRef(Path("/photos/a.jpg"), 100, 1)
    other = ImageRef(Path("/photos/b.jpg"), 5, 5)
    index.add_not_images([photo, other])
    index.add_not_images([ImageRef(photo.path, 7, 1)])

    assert set(index.not_images()) == {ImageRef(photo.path, 7, 1), other}

    index.forget_not_images([other.path])

    assert index.not_images() == [ImageRef(photo.path, 7, 1)]


def test_not_images_are_no_images_and_forgetting_an_image_leaves_them_alone(index):
    note = ImageRef(Path("/photos/notes.txt"), 5, 5)
    index.add_not_images([note])
    assert index.images() == []

    index.record(ImageRef(Path("/photos/a.jpg"), 100, 1))
    index.forget([Path("/photos/a.jpg")])

    assert index.not_images() == [note]


def test_index_from_another_schema_version_is_refused(tmp_path):
    db = tmp_path / "index.sqlite"
    ImageIndex(db).close()
    with sqlite3.connect(db) as connection:
        connection.execute("PRAGMA user_version = 999")
    connection.close()

    with pytest.raises(IndexVersionError):
        ImageIndex(db)


def test_protection_signals_round_trip(index):
    index.record(PHOTO)
    faces = Signal("faces", 1, Protection.FACES, 0.8, {"faces": "3"})

    index.set_signals(PHOTO.path, "faces", 1, [faces])

    assert index.signals(PHOTO.path) == [faces]
    assert index.indexed_images()[0].signals == (faces,)


def test_excluded_folders_are_listed_added_once_and_removed(index):
    index.exclude_folder(Path("/photos/b"))
    index.exclude_folder(Path("/photos/a"))
    index.exclude_folder(Path("/photos/a"))

    assert index.excluded_folders() == [Path("/photos/a"), Path("/photos/b")]

    index.remove_excluded_folder(Path("/photos/a"))

    assert index.excluded_folders() == [Path("/photos/b")]


def test_excluding_a_folder_leaves_its_images_and_their_signals_alone(index):
    index.record(PHOTO)
    index.set_signals(PHOTO.path, "blur", 1, [BLUR])

    index.exclude_folder(PHOTO.path.parent)
    index.remove_excluded_folder(PHOTO.path.parent)

    assert index.signals(PHOTO.path) == [BLUR]
    assert index.review_state(PHOTO.path) is ReviewState.UNREVIEWED
