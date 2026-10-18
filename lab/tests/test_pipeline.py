import os

import pytest
from PIL import Image

from cribrum_lab import pipeline
from cribrum_lab.index import ImageIndex
from cribrum_lab.model import Category, Signal


def save(path, color="gray", size=(64, 48)):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, color).save(path)
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
    save(root / "Pictures/Screenshots/Screenshot_1.png", "white")
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
    assert Category.EXACT_DUPLICATE in categories(index, copy)


def test_second_run_on_unchanged_data_does_no_detector_work(photos, index):
    pipeline.run(photos, index)

    stats = pipeline.run(photos, index)

    assert stats.new_or_changed == 0
    assert stats.detector_runs == 0


def test_changed_image_is_analyzed_again_and_groups_are_recomputed(photos, index):
    pipeline.run(photos, index)
    changed = save(photos / "DCIM/Camera/a.png", "black")
    os.utime(changed, ns=(1, 1))

    stats = pipeline.run(photos, index)

    assert stats.new_or_changed == 1
    assert stats.detector_runs == len(pipeline.DETECTORS) + len(pipeline.GROUP_DETECTORS)
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
