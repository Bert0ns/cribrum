import numpy as np
import pytest
from PIL import Image

from cribrum_lab.text import INPUT_SIDE, preprocess, text_coverage


class FakeDetector:
    """Marks the top quarter of the image as text."""

    def run(self, inputs):
        _, _, height, width = inputs["x"].shape
        probability = np.zeros((1, 1, height, width), np.float32)
        probability[:, :, : height // 4] = 0.9
        return {"sigmoid_0.tmp_0": probability}


def test_preprocess_keeps_the_aspect_in_multiples_of_32(tmp_path):
    path = tmp_path / "a.png"
    Image.new("RGB", (3000, 1000), "white").save(path)

    pixels = preprocess(path)

    assert pixels.shape == (1, 3, 224, INPUT_SIDE)
    assert pixels[0, 0, 0, 0] == pytest.approx((1.0 - 0.485) / 0.229)


def test_coverage_is_the_share_of_text_pixels(tmp_path):
    path = tmp_path / "a.jpg"
    Image.new("RGB", (640, 640)).save(path)

    assert text_coverage(path, FakeDetector()) == pytest.approx(0.25)
