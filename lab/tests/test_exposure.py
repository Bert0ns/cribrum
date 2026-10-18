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


def night_shot():
    sky = noisy(10, spread=4)
    sky.paste(Image.new("RGB", (160, 150), (220, 200, 180)), (120, 80))  # lit subject, 20% of frame
    return sky


def document():
    page = Image.new("RGB", (400, 300), (245, 245, 245))
    for top in range(20, 280, 20):  # lines of dark text, about 10% of the page
        page.paste(Image.new("RGB", (360, 2), (20, 20, 20)), (20, top))
    return page


@pytest.mark.parametrize("image", [night_shot(), document()], ids=["night shot", "document"])
def test_high_contrast_scenes_with_skewed_histograms_score_low(tmp_path, image):
    assert analyze(tmp_path, image).score < 0.5
