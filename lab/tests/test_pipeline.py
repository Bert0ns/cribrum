import os
import shutil

import numpy as np
import pytest
from PIL import Image

from cribrum_lab import pipeline
from cribrum_lab.index import ImageIndex
from cribrum_lab.model import Category, Protection, ReviewState, Signal


def save(path, seed=0, size=(64, 48)):
    """A random pattern: flat images would all be near-duplicates of each other."""
    path.parent.mkdir(parents=True, exist_ok=True)
    noise = np.random.default_rng(seed).integers(0, 256, (6, 8, 3), np.uint8)
    Image.fromarray(noise).resize(size).save(path)
    return path


@pytest.fixture
def index(tmp_path):
    with ImageIndex(tmp_path / "index.sqlite") as index:
        yield index


@pytest.fixture
def photos(tmp_path):
    root = tmp_path / "device"
    save(root / "DCIM/Camera/a.png")
    save(root / "WhatsApp/Media/a-copy.png")  # same bytes as a.png
    save(root / "Pictures/Screenshots/Screenshot_1.png", seed=1)
    return root


def categories(index, path):
    return {s.category for s in index.signals(path)}


def test_first_run_stores_metadata_and_signals_of_every_detector(photos, index):
    stats = pipeline.run(photos, index)

    assert stats.images == 3
    assert stats.failures == []
    screenshot = photos.resolve() / "Pictures/Screenshots/Screenshot_1.png"
    assert index.metadata(screenshot).width == 64
    assert categories(index, screenshot) == {
        Category.BLURRY,
        Category.BAD_EXPOSURE,
        Category.SCREENSHOT,
        Category.STICKER,
        Protection.FACES,
    }
    copy = photos.resolve() / "WhatsApp/Media/a-copy.png"
    assert {Category.EXACT_DUPLICATE, Category.NEAR_DUPLICATE} <= categories(index, copy)


def test_second_run_on_unchanged_data_does_no_detector_work(photos, index):
    pipeline.run(photos, index)

    stats = pipeline.run(photos, index)

    assert stats.new_or_changed == 0
    assert stats.detector_runs == 0


def test_changed_image_is_analyzed_again_and_groups_are_recomputed(photos, index):
    pipeline.run(photos, index)
    changed = save(photos / "DCIM/Camera/a.png", seed=2)
    os.utime(changed, ns=(1, 1))

    stats = pipeline.run(photos, index)

    assert stats.new_or_changed == 1
    without_embeddings = 5  # blur, exposure, screenshot, faces, sticker: the others need embeddings
    phash = 1
    assert stats.detector_runs == without_embeddings + phash + len(pipeline.GROUP_DETECTORS)
    copy = photos.resolve() / "WhatsApp/Media/a-copy.png"
    assert Category.EXACT_DUPLICATE not in categories(index, copy)


def test_removed_image_leaves_the_index(photos, index):
    pipeline.run(photos, index)
    (photos / "DCIM/Camera/a.png").unlink()

    stats = pipeline.run(photos, index)

    assert stats.forgotten == 1
    assert len(index.images()) == 2


def test_a_root_the_scan_did_not_walk_keeps_its_rows(photos, index):
    pipeline.run(photos, index)
    kept = (photos / "DCIM/Camera/a.png").resolve()
    index.set_review_state(kept, ReviewState.KEPT)
    shutil.rmtree(photos)  # the volume is out

    stats = pipeline.run(photos, index)

    assert stats.forgotten == 0
    assert index.review_state(kept) == ReviewState.KEPT
    assert len(index.images()) == 3


def test_a_folder_whose_listing_fails_keeps_its_rows(photos, index):
    pipeline.run(photos, index)
    kept = (photos / "DCIM/Camera/a.png").resolve()
    index.set_review_state(kept, ReviewState.KEPT)
    locked = photos / "DCIM/Camera"
    locked.chmod(0)
    try:
        if os.access(locked, os.R_OK):
            pytest.skip("the folder is still listable (running as root?)")

        stats = pipeline.run(photos, index)
    finally:
        locked.chmod(0o755)

    assert stats.forgotten == 0
    assert index.review_state(kept) == ReviewState.KEPT
    assert len(index.images()) == 3


def test_unreadable_image_gets_no_per_image_analysis(photos, index):
    broken = photos / "Download/broken.jpg"
    broken.parent.mkdir()
    broken.write_bytes(b"\xff\xd8\xff\xe0 not really a jpeg")

    stats = pipeline.run(photos, index)

    assert stats.unreadable == 1
    assert stats.failures == []
    assert index.signals(broken.resolve()) == []


class FailingDetector:
    id = "failing"
    version = 1

    def analyze(self, image, context):
        raise OSError("truncated")


class FixedDetector:
    id = "failing"
    version = 1

    def analyze(self, image, context):
        return [Signal(self.id, self.version, Category.BLURRY, 0.5)]


def test_failed_analysis_is_reported_and_retried_on_the_next_run(photos, index):
    stats = pipeline.run(photos, index, detectors=[FailingDetector()], group_detectors=[])

    assert len(stats.failures) == 3
    assert "OSError: truncated" in stats.failures[0]

    stats = pipeline.run(photos, index, detectors=[FixedDetector()], group_detectors=[])

    assert stats.failures == []
    assert stats.detector_runs == 3


def test_worker_processes_give_the_same_signals(photos, tmp_path, index):
    pipeline.run(photos, index)
    with ImageIndex(tmp_path / "parallel.sqlite") as parallel:
        pipeline.run(photos, parallel, jobs=2)

        assert parallel.indexed_images() == index.indexed_images()


class MeanColorEmbedder:
    """Embeds an image as its mean color, padded to MobileCLIP's 512 values."""

    id = "mean_color"

    def __init__(self, version=1, scale=1.0):
        self.version = version
        self.scale = scale

    def embed_pixels(self, pixels):
        vectors = np.ones((len(pixels), 512))
        vectors[:, :3] = self.scale * pixels.mean(axis=(2, 3))
        return vectors / np.linalg.norm(vectors, axis=1, keepdims=True)


class EmbeddingReader:
    """Group detector that records the embeddings it can look up."""

    id = "embedding_reader"
    version = 1

    def __init__(self):
        self.seen = {}

    def analyze(self, images, context):
        self.seen = {image.path.name: context.embedding(image) for image in images}
        return {}


def test_embeddings_are_computed_once_and_reach_group_detectors(photos, index):
    reader = EmbeddingReader()

    first = pipeline.run(photos, index, embedder=MeanColorEmbedder(), group_detectors=[reader])
    second = pipeline.run(photos, index, embedder=MeanColorEmbedder(), group_detectors=[])

    assert first.detector_runs == 3 * len(pipeline.DETECTORS) + 3 * 2 + 1  # + hashes, embeddings
    assert second.detector_runs == 0
    assert set(reader.seen) == {"a.png", "a-copy.png", "Screenshot_1.png"}
    assert len(index.embeddings("mean_color", 1)) == 3


def test_without_an_embedder_the_lookup_raises(photos, index):
    reader = EmbeddingReader()

    with pytest.raises(LookupError):
        pipeline.run(photos, index, group_detectors=[reader])


class EmbeddingDetector:
    id = "embedding_user"
    version = 1
    uses_embeddings = True

    def analyze(self, image, context):
        return [
            Signal(self.id, self.version, Category.DOCUMENT, float(context.embedding(image)[0]))
        ]


def test_detector_needing_embeddings_waits_for_a_run_with_them(photos, index):
    stats = pipeline.run(photos, index, detectors=[EmbeddingDetector()], group_detectors=[])

    assert stats.skipped == 3
    assert stats.failures == []

    stats = pipeline.run(
        photos,
        index,
        detectors=[EmbeddingDetector()],
        group_detectors=[],
        embedder=MeanColorEmbedder(),
    )

    assert stats.skipped == 0
    assert stats.detector_runs == 3 + 3  # embeddings, then the detector


def test_the_next_run_after_deleting_the_embeddings_computes_them_again(photos, index):
    class Reader(EmbeddingDetector):
        uses_embeddings = True

    def run():
        return pipeline.run(
            photos, index, detectors=[Reader()], group_detectors=[], embedder=MeanColorEmbedder()
        )

    run()
    before = index.signals(photos / "DCIM/Camera/a.png")

    index.delete_embeddings()

    assert index.signals(photos / "DCIM/Camera/a.png") == before  # candidates stay until a scan
    assert run().detector_runs == 3 + 3  # embeddings, then the detector
    assert len(index.embeddings("mean_color", 1)) == 3
    assert index.signals(photos / "DCIM/Camera/a.png") == before


def every_signal(index):
    return {
        image.path: sorted(index.signals(image.path), key=lambda s: s.detector_id)
        for image in index.images()
    }


def test_group_detectors_do_not_rerun_with_the_same_vectors_after_deleting_the_embeddings(
    photos, index
):
    pipeline.run(photos, index, embedder=MeanColorEmbedder())
    before = every_signal(index)

    index.delete_embeddings()
    stats = pipeline.run(photos, index, embedder=MeanColorEmbedder())

    readers = sum(getattr(d, "uses_embeddings", False) for d in pipeline.DETECTORS)
    assert stats.detector_runs == 3 + 3 * readers  # embeddings, then document and forwarded
    assert every_signal(index) == before


def test_a_new_embedder_version_reruns_the_embedding_detectors_and_the_group_detectors(
    photos, index
):
    pipeline.run(photos, index, embedder=MeanColorEmbedder())

    stats = pipeline.run(photos, index, embedder=MeanColorEmbedder(version=2))

    readers = sum(getattr(d, "uses_embeddings", False) for d in pipeline.DETECTORS)
    assert stats.detector_runs == 3 + 3 * readers + len(pipeline.GROUP_DETECTORS)


def test_a_detectors_signal_follows_the_embedding_it_read(photos, index):
    class Reader(EmbeddingDetector):
        uses_embeddings = True

    path = photos / "DCIM/Camera/a.png"
    pipeline.run(
        photos, index, detectors=[Reader()], group_detectors=[], embedder=MeanColorEmbedder()
    )
    [before] = index.signals(path)

    pipeline.run(
        photos,
        index,
        detectors=[Reader()],
        group_detectors=[],
        embedder=MeanColorEmbedder(version=2, scale=4.0),
    )

    [after] = index.signals(path)
    assert after.score == pytest.approx(index.embeddings("mean_color", 2)[path][0])
    assert after.score != pytest.approx(before.score)


def test_the_detectors_that_read_no_embedding_store_their_signals_before_the_first_embedding(
    photos, index
):
    screenshot = photos.resolve() / "Pictures/Screenshots/Screenshot_1.png"
    before = []

    class Recording(MeanColorEmbedder):
        def embed_pixels(self, pixels):
            before.append(categories(index, screenshot))
            return super().embed_pixels(pixels)

    pipeline.run(photos, index, embedder=Recording())

    assert before[0] == {
        Category.BLURRY,
        Category.BAD_EXPOSURE,
        Category.SCREENSHOT,
        Category.STICKER,
        Protection.FACES,
    }


class UnflaggedEmbeddingDetector:
    id = "unflagged"
    version = 1

    def analyze(self, image, context):
        return [
            Signal(self.id, self.version, Category.DOCUMENT, float(context.embedding(image)[0]))
        ]


def test_a_detector_that_reads_an_embedding_without_saying_so_fails_on_every_run(photos, index):
    def run():
        return pipeline.run(
            photos,
            index,
            detectors=[UnflaggedEmbeddingDetector()],
            group_detectors=[],
            embedder=MeanColorEmbedder(),
        )

    first, second = run(), run()

    assert first.skipped == 0
    assert len(first.failures) == 3
    assert all("without uses_embeddings" in failure for failure in first.failures)
    assert len(second.failures) == 3


def test_a_run_stopped_after_the_fast_detectors_resumes_with_the_embeddings(photos, index):
    class Stopped(MeanColorEmbedder):
        def embed_pixels(self, pixels):
            raise KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        pipeline.run(photos, index, embedder=Stopped())
    screenshot = photos.resolve() / "Pictures/Screenshots/Screenshot_1.png"
    assert categories(index, screenshot) == {
        Category.BLURRY,
        Category.BAD_EXPOSURE,
        Category.SCREENSHOT,
        Category.STICKER,
        Protection.FACES,
    }

    stats = pipeline.run(photos, index, embedder=MeanColorEmbedder())

    readers = 2  # document and forwarded
    assert stats.detector_runs == 3 + 3 * readers + len(pipeline.GROUP_DETECTORS)


def test_the_steps_run_in_order_the_detectors_that_read_embeddings_last(photos, index):
    steps = []

    def progress(step, done, total):
        if not steps or steps[-1] != step:
            steps.append(step)

    pipeline.run(photos, index, embedder=MeanColorEmbedder(), progress=progress)

    assert steps == [
        "scan",
        "metadata",
        "detectors",
        "embeddings",
        "embedded_detectors",
        "exact_duplicate",
        "near_duplicate",
        "burst",
    ]


STICKERS = "WhatsApp/Media/WhatsApp Stickers"


def test_a_sticker_is_not_embedded_and_the_detectors_reading_embeddings_leave_it_alone(
    photos, index
):
    sticker = save(photos / STICKERS / "s.png", seed=3)

    stats = pipeline.run(photos, index, embedder=MeanColorEmbedder())

    assert stats.failures == []
    assert stats.skipped == 0
    assert len(index.embeddings("mean_color", 1)) == 3
    signals = index.signals(sticker.resolve())
    assert {s.category for s in signals} == {
        Category.BLURRY,
        Category.BAD_EXPOSURE,
        Category.SCREENSHOT,
        Category.STICKER,
        Protection.FACES,
    }
    assert next(s for s in signals if s.category == Category.STICKER).score == 1.0


def test_a_sticker_with_no_embedding_is_still_in_a_near_duplicate_group_through_its_hash(
    tmp_path, index
):
    root = tmp_path / "device"
    save(root / STICKERS / "s.png", seed=3)
    save(root / STICKERS / "s-copy.png", seed=3)

    pipeline.run(root, index, embedder=MeanColorEmbedder())

    assert Category.NEAR_DUPLICATE in categories(index, root.resolve() / STICKERS / "s-copy.png")


def test_an_embedding_a_sticker_got_from_an_earlier_scan_is_not_used(tmp_path, index):
    root = tmp_path / "device"
    sticker = save(root / STICKERS / "s.png", seed=3)
    pipeline.run(root, index, detectors=[], group_detectors=[])
    index.set_embeddings("mean_color", 1, {sticker.resolve(): np.ones(512)})
    seen = []

    class Probe:
        id = "probe"
        version = 1

        def analyze(self, images, context):
            try:
                context.embedding(images[0])
                seen.append(True)
            except LookupError:
                seen.append(False)
            return {}

    pipeline.run(root, index, detectors=[], group_detectors=[Probe()], embedder=MeanColorEmbedder())

    assert seen == [False]


def test_only_the_document_and_forwarded_detectors_read_embeddings():
    users = [d.id for d in pipeline.DETECTORS if getattr(d, "uses_embeddings", False)]

    assert users == ["document", "forwarded"]


def test_every_category_has_a_detector():
    detectors = [*pipeline.DETECTORS, *pipeline.GROUP_DETECTORS]

    assert {d.category for d in detectors} - {None} == set(Category)
