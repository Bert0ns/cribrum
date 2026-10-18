import os

import numpy as np
import pytest
from PIL import Image

from cribrum_lab import pipeline
from cribrum_lab.index import ImageIndex
from cribrum_lab.model import Category, Signal


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
    phash = 1
    assert stats.detector_runs == len(pipeline.DETECTORS) + phash + len(pipeline.GROUP_DETECTORS)
    copy = photos.resolve() / "WhatsApp/Media/a-copy.png"
    assert Category.EXACT_DUPLICATE not in categories(index, copy)


def test_removed_image_leaves_the_index(photos, index):
    pipeline.run(photos, index)
    (photos / "DCIM/Camera/a.png").unlink()

    stats = pipeline.run(photos, index)

    assert stats.forgotten == 1
    assert len(index.images()) == 2


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
    """Embeds an image as its normalized mean color, padded: enough to tell images apart."""

    id = "mean_color"
    version = 1

    def embed_pixels(self, pixels):
        vectors = np.concatenate([pixels.mean(axis=(2, 3)), np.ones((len(pixels), 1))], axis=1)
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
