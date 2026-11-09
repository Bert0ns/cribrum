import numpy as np
from PIL import Image

from cribrum_lab.imaging import Pixels, load_grayscale


def test_16_bit_images_are_scaled_not_clipped(tmp_path):
    path = tmp_path / "a.png"
    ramp = np.linspace(0, 65535, 256, dtype=np.uint16)
    Image.fromarray(np.tile(ramp, (16, 1))).save(path)

    gray = load_grayscale(path, 256)

    assert gray.min() < 5 and gray.max() > 250
    assert abs(float(np.median(gray)) - 127.5) < 5


def test_palette_image_with_transparency_loads_without_warnings(tmp_path, recwarn):
    path = tmp_path / "a.png"
    image = Image.new("P", (16, 16), 0)
    image.putpalette([0, 0, 0, 255, 255, 255, 0, 255, 0])
    image.save(path, transparency=b"\x00\xff\x80")  # per-entry alpha, as in sticker PNGs

    load_grayscale(path, 256)

    assert not [w for w in recwarn if issubclass(w.category, UserWarning)]


def test_pixels_load_the_grayscale_once_per_side(tmp_path):
    path = tmp_path / "a.jpg"
    noise = np.random.default_rng(0).integers(0, 256, (12, 16, 3), np.uint8)
    Image.fromarray(noise).resize((1600, 1200)).save(path)
    pixels = Pixels(path)

    for side in (512, 256):
        gray = pixels.gray(side)
        assert pixels.gray(side) is gray
        assert np.array_equal(gray, load_grayscale(path, side))
