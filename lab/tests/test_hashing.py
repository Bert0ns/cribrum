import numpy as np
from PIL import Image, ImageFilter

from cribrum_lab.hashing import content_hash, perceptual_hash
from cribrum_lab.model import ImageRef


def scene(seed):
    noise = np.random.default_rng(seed).integers(0, 256, (12, 16, 3), np.uint8)
    return Image.fromarray(noise).resize((1600, 1200), Image.Resampling.BICUBIC)


def phash(tmp_path, image, name, **params):
    path = tmp_path / name
    image.save(path, **params)
    return perceptual_hash(ImageRef(path, 0, 0))


def distance(a, b):
    return (a ^ b).bit_count()


def test_content_hash_depends_on_every_byte(tmp_path):
    a, b, c = tmp_path / "a", tmp_path / "b", tmp_path / "c"
    a.write_bytes(b"x" * 3_000_000)
    b.write_bytes(b"x" * 3_000_000)
    c.write_bytes(b"x" * 2_999_999 + b"y")

    hashes = [content_hash(ImageRef(p, 0, 0)) for p in (a, b, c)]

    assert hashes[0] == hashes[1] != hashes[2]


def test_perceptual_hash_survives_resizing_recompression_and_light_blur(tmp_path):
    original = phash(tmp_path, scene(1), "a.jpg", quality=95)

    for name, copy, params in [
        ("small.jpg", scene(1).resize((640, 480)), {"quality": 50}),
        ("blur.jpg", scene(1).filter(ImageFilter.GaussianBlur(2)), {}),
        ("a.png", scene(1), {}),
    ]:
        assert distance(original, phash(tmp_path, copy, name, **params)) <= 4


def test_perceptual_hash_tells_different_images_apart(tmp_path):
    hashes = [phash(tmp_path, scene(seed), f"{seed}.jpg") for seed in range(5)]

    assert min(distance(a, b) for i, a in enumerate(hashes) for b in hashes[i + 1 :]) > 16


def test_flat_images_hash_the_same_on_every_platform(tmp_path):
    hashes = {phash(tmp_path, Image.new("RGB", (64, 48), c), f"{c}.png") for c in ("gray", "white")}

    assert hashes == {1 << 63}  # only the DC term is above the median
