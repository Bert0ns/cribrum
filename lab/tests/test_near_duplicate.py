import numpy as np
import pytest
from PIL import Image

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, AnalysisContext, Role
from cribrum_lab.detectors.near_duplicate import NearDuplicateDetector
from cribrum_lab.metadata import read_metadata
from cribrum_lab.model import Category, ImageRef


def scene(seed, size=(1600, 1200)):
    noise = np.random.default_rng(seed).integers(0, 256, (12, 16, 3), np.uint8)
    return Image.fromarray(noise).resize(size, Image.Resampling.BICUBIC)


def save(image, path, **params):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, **params)
    return ImageRef(path, path.stat().st_size, 0)


def run(images):
    context = AnalysisContext(metadata=lambda image: read_metadata(image.path))
    return NearDuplicateDetector().analyze(images, context)


@pytest.fixture
def copies(tmp_path):
    original = save(scene(1), tmp_path / "DCIM/Camera/a.jpg", quality=95)
    resized = save(scene(1).resize((800, 600)), tmp_path / "WhatsApp/a.jpg", quality=95)
    recompressed = save(scene(1), tmp_path / "Download/a.jpg", quality=40)
    other = save(scene(2), tmp_path / "DCIM/Camera/b.jpg", quality=95)
    return original, resized, recompressed, other


def test_resized_and_recompressed_copies_group_with_the_original(copies):
    original, resized, recompressed, other = copies

    signals = run(list(copies))

    assert other not in signals
    groups = {signals[i][0].evidence[GROUP_KEY] for i in (original, resized, recompressed)}
    assert len(groups) == 1
    assert all(s[0].category is Category.NEAR_DUPLICATE for s in signals.values())


def test_highest_resolution_then_least_compression_is_the_best_copy(copies):
    original, resized, recompressed, _ = copies

    signals = run(list(copies))

    assert signals[original][0].evidence[ROLE_KEY] == Role.BEST
    assert signals[original][0].score == 0.0
    for copy in (resized, recompressed):
        assert signals[copy][0].evidence[ROLE_KEY] == Role.INFERIOR
        assert signals[copy][0].score >= 0.5


def test_camera_exif_wins_between_equal_copies(tmp_path):
    exif = Image.Exif()
    exif[0x0110] = "Pixel 8"  # camera model
    plain = save(scene(1), tmp_path / "a.png")
    with_exif = save(scene(1), tmp_path / "b.png", exif=exif.tobytes())

    signals = run([plain, with_exif])

    assert signals[with_exif][0].evidence[ROLE_KEY] == Role.BEST


def test_different_images_are_left_out(tmp_path):
    images = [save(scene(seed), tmp_path / f"{seed}.jpg") for seed in range(5)]

    assert run(images) == {}


def test_undecodable_image_is_skipped(copies, tmp_path):
    broken = tmp_path / "broken.jpg"
    broken.write_bytes(b"\xff\xd8\xff\xe0 not really a jpeg")

    signals = run([*copies, ImageRef(broken, 30, 0)])

    assert ImageRef(broken, 30, 0) not in signals
    assert len(signals) == 3
