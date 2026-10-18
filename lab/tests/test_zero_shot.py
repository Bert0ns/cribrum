import base64
import json
from pathlib import Path

import numpy as np
import pytest

from cribrum_lab import mobileclip, prompts
from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.document import DocumentDetector
from cribrum_lab.detectors.forwarded import ForwardedDetector
from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import Category, ImageRef

STORED = json.loads(prompts.EMBEDDINGS.read_text())
HAS_MODEL = all((mobileclip.MODELS / name).exists() for name in mobileclip._FILES)


def prompt_vector(text):
    """An image embedding that sits exactly on a prompt: the class it belongs to wins."""
    return np.frombuffer(base64.b64decode(STORED["prompts"][text]), dtype="<f4")


def analyze(detector, text, path="/sdcard/Download/a.jpg", camera=False):
    meta = ImageMetadata(1000, 1000, "image/jpeg", camera_model="Pixel 8" if camera else None)
    context = AnalysisContext(metadata=lambda _: meta, embedding=lambda _: prompt_vector(text))
    [signal] = detector.analyze(ImageRef(Path(path), 0, 0), context)
    return signal


def test_stored_embeddings_cover_exactly_the_current_prompts():
    assert STORED["version"] == prompts.VERSION
    assert STORED["model"] == mobileclip.MODEL_ID
    assert list(STORED["prompts"]) == [p for ps in prompts.PROMPTS.values() for p in ps]


def test_every_prompt_appears_once():
    every = [p for ps in prompts.PROMPTS.values() for p in ps]

    assert len(every) == len(set(every))


@pytest.mark.skipif(not HAS_MODEL, reason="MobileCLIP files not downloaded into lab/data/models")
def test_stored_embeddings_match_the_text_encoder():
    generated = prompts.generate()

    for text, encoded in generated["prompts"].items():
        expected = np.frombuffer(base64.b64decode(encoded), dtype="<f4")
        np.testing.assert_allclose(prompt_vector(text), expected, atol=1e-5)


def test_class_probabilities_sum_to_one():
    classes = prompts.classify(prompt_vector("a photo of a receipt"))

    assert sum(p for p, _ in classes.values()) == pytest.approx(1.0, abs=1e-5)
    assert classes["document"][1] == "a photo of a receipt"


@pytest.mark.parametrize("text", prompts.PROMPTS["document"])
def test_document_prompts_score_high_as_documents(text):
    signal = analyze(DocumentDetector(), text)

    assert signal.category is Category.DOCUMENT
    assert signal.score > 0.5
    assert signal.evidence["prompt"] == text


def test_photo_of_people_is_not_a_document():
    assert analyze(DocumentDetector(), "a group photo of friends").score < 0.1


def test_meme_in_a_messaging_folder_scores_highest():
    meme = "an internet meme"
    whatsapp = "/sdcard/WhatsApp/Media/WhatsApp Images/IMG-1.jpg"

    in_chat = analyze(ForwardedDetector(), meme, whatsapp).score
    downloaded = analyze(ForwardedDetector(), meme).score

    assert in_chat > downloaded > 0.5


def test_photo_a_friend_sent_stays_below_a_meme():
    whatsapp = "/sdcard/WhatsApp/Media/WhatsApp Images/IMG-1.jpg"

    friend = analyze(ForwardedDetector(), "a group photo of friends", whatsapp)

    assert friend.score < 0.5
    assert friend.evidence["messaging_folder"] == "yes"
    assert friend.evidence["camera_exif"] == "no"


def test_camera_photo_outside_messaging_folders_scores_near_zero():
    signal = analyze(
        ForwardedDetector(), "a photo of a landscape", "/sdcard/DCIM/Camera/a.jpg", True
    )

    assert signal.score < 0.05
