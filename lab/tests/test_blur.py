import numpy as np
from PIL import Image, ImageFilter

from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.blur import BlurDetector
from cribrum_lab.model import Category, ImageRef


def texture(size=(800, 600), seed=0):
    noise = np.random.default_rng(seed).integers(0, 256, (size[1] // 8, size[0] // 8), np.uint8)
    return Image.fromarray(noise).resize(size, Image.Resampling.NEAREST).convert("RGB")


def blur_score(tmp_path, image, name="a.jpg"):
    path = tmp_path / name
    image.save(path, quality=95)
    [signal] = BlurDetector().analyze(ImageRef(path, 0, 0), AnalysisContext())
    assert signal.category is Category.BLURRY
    return signal.score


def test_blurred_copy_scores_higher_than_the_sharp_original(tmp_path):
    sharp = texture()
    blurred = sharp.filter(ImageFilter.GaussianBlur(8))

    sharp_score = blur_score(tmp_path, sharp, "sharp.jpg")
    blurred_score = blur_score(tmp_path, blurred, "blurred.jpg")

    assert sharp_score < 0.5 < blurred_score


def test_sharp_subject_on_a_blurred_background_is_not_blurry(tmp_path):
    portrait = texture().filter(ImageFilter.GaussianBlur(8))
    portrait.paste(texture((200, 200), seed=1), (300, 200))

    assert blur_score(tmp_path, portrait) < 0.5


def test_score_grows_with_blur(tmp_path):
    scores = [
        blur_score(tmp_path, texture().filter(ImageFilter.GaussianBlur(radius)))
        for radius in [0, 2, 8, 30]
    ]

    assert scores == sorted(scores)


def test_evidence_records_the_measures(tmp_path):
    path = tmp_path / "a.png"
    texture().save(path)

    [signal] = BlurDetector().analyze(ImageRef(path, 0, 0), AnalysisContext())

    assert set(signal.evidence) == {"global_variance", "peak_variance"}
