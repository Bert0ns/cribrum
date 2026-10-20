from pathlib import Path

import pytest

from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.sticker import StickerDetector
from cribrum_lab.model import Category, ImageRef

MEDIA = "/sdcard/Android/media/com.whatsapp/WhatsApp/Media"


def analyze(path):
    [signal] = StickerDetector().analyze(ImageRef(Path(path), 0, 0), AnalysisContext())
    assert signal.category is Category.STICKER
    return signal


@pytest.mark.parametrize(
    "path",
    [
        f"{MEDIA}/WhatsApp Stickers/STK-20240501-WA0001.webp",
        f"{MEDIA}/WhatsApp Backup Excluded Stickers/STK-20240501-WA0002.webp",
        "/sdcard/WhatsApp Business/Media/WhatsApp Business Stickers/a.webp",
    ],
)
def test_images_in_a_messaging_sticker_folder_are_stickers(path):
    signal = analyze(path)

    assert signal.score == 1.0
    assert signal.evidence == {"sticker_folder": "yes"}


@pytest.mark.parametrize(
    "path",
    [
        f"{MEDIA}/WhatsApp Images/IMG-20240501-WA0001.jpg",
        "/sdcard/Pictures/Stickers/mine.webp",  # not a messaging app's folder
        f"{MEDIA}/WhatsApp Images/stickers.jpg",  # the file name says nothing
    ],
)
def test_other_images_are_not(path):
    assert analyze(path).score == 0.0
