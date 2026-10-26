from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, AnalysisContext, Role
from cribrum_lab.detectors.near_duplicate import NearDuplicateDetector
from cribrum_lab.metadata import ImageMetadata, read_metadata
from cribrum_lab.model import Category, ImageRef


def scene(seed, size=(1600, 1200)):
    noise = np.random.default_rng(seed).integers(0, 256, (12, 16, 3), np.uint8)
    return Image.fromarray(noise).resize(size, Image.Resampling.BICUBIC)


def save(image, path, **params):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, **params)
    return ImageRef(path, path.stat().st_size, 0)


def no_embedding(image):
    raise LookupError(image.path)


def run(images, embedding=no_embedding):
    context = AnalysisContext(metadata=lambda image: read_metadata(image.path), embedding=embedding)
    return NearDuplicateDetector().analyze(images, context)


def unit(*values):
    vector = np.zeros(512, dtype=np.float32)
    vector[: len(values)] = values
    return vector / np.linalg.norm(vector)


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


def test_close_embeddings_link_images_the_hash_misses(tmp_path):
    original = save(scene(1), tmp_path / "DCIM/Camera/a.jpg")
    crop = save(scene(1).crop((200, 150, 1400, 1050)), tmp_path / "Pictures/a-crop.jpg")
    other = save(scene(2), tmp_path / "DCIM/Camera/b.jpg")
    vectors = {original: unit(1, 0), crop: unit(1, 0.4), other: unit(0, 1)}

    assert run([original, crop, other]) == {}  # the crop's hash is far from the original

    signals = run([original, crop, other], embedding=vectors.__getitem__)

    assert set(signals) == {original, crop}
    assert signals[crop][0].evidence[ROLE_KEY] == Role.INFERIOR
    assert float(signals[crop][0].evidence["cosine"]) == pytest.approx(0.928, abs=1e-3)
    assert signals[crop][0].score > 0.5


def test_images_without_an_embedding_still_link_by_hash(copies):
    original, resized, recompressed, other = copies
    vectors = {original: unit(1), other: unit(0, 1)}

    def embedding(image):
        return vectors[image] if image in vectors else no_embedding(image)

    signals = run(list(copies), embedding=embedding)

    assert set(signals) == {original, resized, recompressed}


BASE = 0x5555555555555555


def run_fakes(hashes, vectors=None):
    """Images with chosen hashes and embeddings, so the linking thresholds are hit exactly."""
    vectors = vectors or {}
    context = AnalysisContext(
        perceptual_hash=hashes.__getitem__,
        embedding=lambda image: vectors[image] if image in vectors else no_embedding(image),
        metadata=lambda image: ImageMetadata(100, 100, "image/jpeg", None, None, None),
    )
    return NearDuplicateDetector().analyze(list(hashes), context)


def fake(name, size):
    return ImageRef(Path(f"/fake/{name}.jpg"), size, 0)


def test_a_copy_linked_only_through_another_copy_scores_under_the_threshold():
    a, b, c = fake("a", 3000), fake("b", 2000), fake("c", 1000)

    signals = run_fakes({a: BASE, b: BASE ^ 0x3FF, c: BASE ^ 0x3FF ^ 0xFFC00})

    assert set(signals) == {a, b, c}
    assert len({s[0].evidence[GROUP_KEY] for s in signals.values()}) == 1
    assert signals[a][0].evidence[ROLE_KEY] == Role.BEST
    assert signals[c][0].evidence["distance"] == "20"
    assert signals[c][0].score == pytest.approx(1 - 20 / 22)  # scored against the best copy
    assert signals[c][0].score < 0.5 <= signals[b][0].score


def test_hashes_10_bits_apart_link_and_11_bits_apart_do_not():
    a, b = fake("a", 2000), fake("b", 1000)

    linked = run_fakes({a: BASE, b: BASE ^ 0x3FF})
    apart = run_fakes({a: BASE, b: BASE ^ 0x7FF})

    assert set(linked) == {a, b}
    assert linked[b][0].score == pytest.approx(1 - 10 / 22)
    assert apart == {}


@pytest.mark.parametrize(("cosine", "linked"), [(0.90, True), (0.89, False)])
def test_embeddings_link_at_cosine_0_90_and_not_below(cosine, linked):
    a, b = fake("a", 2000), fake("b", 1000)
    vectors = {a: unit(1), b: unit(cosine, np.sqrt(1 - cosine**2))}

    signals = run_fakes({a: BASE, b: ~BASE & (2**64 - 1)}, vectors)  # 64 bits apart

    assert (set(signals) == {a, b}) is linked
