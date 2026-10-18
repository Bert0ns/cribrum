from pathlib import Path

import pytest

from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.screenshot import ScreenshotDetector
from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import Category, ImageRef

SCREEN = (1080, 2400)
PHOTO = (4000, 3000)


def analyze(path, size, camera=False, screen_sizes=None):
    meta = ImageMetadata(*size, "image/jpeg", camera_model="Pixel 8" if camera else None)
    context = AnalysisContext(metadata=lambda image: meta)
    if screen_sizes is not None:
        context = AnalysisContext(metadata=lambda image: meta, screen_sizes=screen_sizes)
    [signal] = ScreenshotDetector().analyze(ImageRef(Path(path), 0, 0), context)
    assert signal.category is Category.SCREENSHOT
    return signal


@pytest.mark.parametrize(
    "path",
    [
        "/sdcard/Pictures/Screenshots/IMG_0001.png",
        "/sdcard/DCIM/Screenshots/Screenshot_20240501-102030_Chrome.jpg",
        "/sdcard/Download/Screen Shot 2020-01-01 at 10.00.00.png",
        "/sdcard/Download/Schermata 2024-05-01 alle 10.20.30.png",
        "/sdcard/Download/Bildschirmfoto vom 2024-05-01.png",
        "/sdcard/Download/Capture d'écran 2024-05-01.png",
        "/sdcard/Download/Captura de pantalla 2024-05-01.png",
    ],
)
def test_screenshot_paths_in_several_languages(path):
    signal = analyze(path, PHOTO, camera=True)

    assert signal.score >= 0.9
    assert signal.evidence["path_match"] != ""


def test_camera_photo_scores_zero():
    signal = analyze("/sdcard/DCIM/Camera/IMG_20240501_102030.jpg", PHOTO, camera=True)

    assert signal.score == 0.0
    assert signal.evidence == {
        "path_match": "",
        "size": "4000x3000",
        "screen_size": "no",
        "camera_exif": "yes",
    }


def test_folder_outside_the_last_three_path_parts_is_ignored():
    signal = analyze("/home/me/screenshots-project/data/DCIM/Camera/a.jpg", PHOTO, camera=True)

    assert signal.score == 0.0


def test_missing_camera_exif_alone_is_weak_evidence():
    signal = analyze("/sdcard/Download/a.jpg", PHOTO)

    assert 0.0 < signal.score < 0.5
    assert signal.evidence["camera_exif"] == "no"


@pytest.mark.parametrize("size", [SCREEN, SCREEN[::-1]], ids=["portrait", "landscape"])
def test_screen_size_in_either_orientation(size):
    signal = analyze("/sdcard/Download/a.jpg", size, camera=True)

    assert signal.score > 0.0
    assert signal.evidence["screen_size"] == "yes"


def test_screen_size_without_camera_exif_scores_higher_than_either_alone():
    both = analyze("/sdcard/Download/a.jpg", SCREEN).score
    size_only = analyze("/sdcard/Download/a.jpg", SCREEN, camera=True).score
    no_exif_only = analyze("/sdcard/Download/a.jpg", PHOTO).score

    assert both > max(size_only, no_exif_only)
    assert both >= 0.5


def test_every_rule_together_scores_highest():
    path = "/sdcard/Pictures/Screenshots/Screenshot_20240501-102030.png"

    assert analyze(path, SCREEN).score > analyze(path, PHOTO, camera=True).score


def test_screen_sizes_come_from_the_context():
    signal = analyze("/sdcard/Download/a.jpg", (500, 700), camera=True, screen_sizes={(500, 700)})

    assert signal.evidence["screen_size"] == "yes"
