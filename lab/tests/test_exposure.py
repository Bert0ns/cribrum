import numpy as np
import pytest
from PIL import Image

from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.exposure import ExposureDetector
from cribrum_lab.model import Category, ImageRef


def analyze(tmp_path, image):
    path = tmp_path / "a.png"
    image.save(path)
    [signal] = ExposureDetector().analyze(ImageRef(path, 0, 0), AnalysisContext())
    assert signal.category is Category.BAD_EXPOSURE
    return signal


def gradient():
    row = np.linspace(0, 255, 400, dtype=np.uint8)
    return Image.fromarray(np.tile(row, (300, 1))).convert("RGB")


def noisy(level, spread=8, seed=0):
    values = np.random.default_rng(seed).normal(level, spread, (300, 400))
    return Image.fromarray(values.clip(0, 255).astype(np.uint8)).convert("RGB")


@pytest.mark.parametrize(
    ("image", "case"),
    [
        (Image.new("RGB", (400, 300), "black"), "dark"),
        (noisy(12), "dark"),  # pocket shot: near-black sensor noise
        (Image.new("RGB", (400, 300), "white"), "bright"),
        (Image.new("RGB", (400, 300), (128, 128, 128)), "flat"),
    ],
)
def test_bad_frames_score_high_and_name_their_case(tmp_path, image, case):
    signal = analyze(tmp_path, image)

    assert signal.score > 0.9
    assert signal.evidence["case"] == case


def test_well_exposed_image_scores_low(tmp_path):
    assert analyze(tmp_path, gradient()).score < 0.5
