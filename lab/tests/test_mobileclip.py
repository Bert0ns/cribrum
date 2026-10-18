import hashlib

import numpy as np
import pytest
from PIL import Image

from cribrum_lab import mobileclip, models
from cribrum_lab.mobileclip import MODELS, MobileClip, model_file, preprocess, zero_shot

HAS_MODEL = all((MODELS / name).exists() for name in mobileclip._FILES)


class FakeVision:
    def run(self, inputs):
        pixels = inputs["pixel_values"]
        return {"image_embeds": np.tile(pixels.mean(axis=(1, 2, 3))[:, None], (1, 512)) + 1}


def test_preprocess_center_crops_the_shortest_side_to_256(tmp_path):
    image = Image.new("RGB", (1200, 600), "black")
    image.paste((255, 0, 0), (500, 0, 700, 600))  # red stripe in the middle
    path = tmp_path / "a.png"
    image.save(path)

    pixels = preprocess(path)

    assert pixels.shape == (3, 256, 256)
    assert pixels.dtype == np.float32
    assert pixels.min() >= 0.0 and pixels.max() <= 1.0
    assert pixels[0, :, 128].mean() > 0.99  # the stripe survives the crop, centered
    assert pixels[0, :, 0].mean() < 0.01


def test_preprocess_applies_the_exif_orientation(tmp_path):
    exif = Image.Exif()
    exif[0x0112] = 6  # rotate 90 degrees clockwise
    image = Image.new("RGB", (400, 300), "black")
    image.paste((255, 255, 255), (0, 0, 400, 30))  # white band on top, before rotation
    path = tmp_path / "a.jpg"
    image.save(path, exif=exif.tobytes(), quality=95)

    pixels = preprocess(path)

    assert pixels[:, :, -10:].mean() > 0.9  # the band ends up on the right
    assert pixels[:, :10, 128].mean() < 0.1


def test_image_embeddings_are_normalized(tmp_path):
    path = tmp_path / "a.png"
    Image.new("RGB", (300, 300), "gray").save(path)

    [vector] = MobileClip(vision=FakeVision()).embed_images([path])

    assert np.linalg.norm(vector) == pytest.approx(1.0)


def test_zero_shot_is_a_softmax_over_prompts():
    images = np.array([[1.0, 0.0], [0.0, 1.0]], dtype=np.float32)
    prompts = np.array([[1.0, 0.0], [0.0, 1.0], [0.6, 0.8]], dtype=np.float32)

    probabilities = zero_shot(images, prompts)

    np.testing.assert_allclose(probabilities.sum(axis=1), 1.0, rtol=1e-6)
    assert list(probabilities.argmax(axis=1)) == [0, 1]


def test_model_file_with_a_wrong_checksum_is_rejected(tmp_path, monkeypatch):
    def download(url, path):
        assert mobileclip._REVISION in url
        path.write_bytes(b"not the model")

    monkeypatch.setattr(models.urllib.request, "urlretrieve", download)

    with pytest.raises(RuntimeError, match="SHA-256"):
        model_file("tokenizer.json", tmp_path)

    assert list(tmp_path.iterdir()) == []


def test_model_file_is_downloaded_once(tmp_path, monkeypatch):
    content = b"tokenizer"
    monkeypatch.setitem(
        mobileclip._FILES, "tokenizer.json", ("", hashlib.sha256(content).hexdigest())
    )
    downloads = []

    def download(url, path):
        downloads.append(url)
        path.write_bytes(content)

    monkeypatch.setattr(models.urllib.request, "urlretrieve", download)

    assert model_file("tokenizer.json", tmp_path).read_bytes() == content
    assert model_file("tokenizer.json", tmp_path).read_bytes() == content
    assert len(downloads) == 1


@pytest.mark.skipif(not HAS_MODEL, reason="MobileCLIP files not downloaded into lab/data/models")
def test_real_model_matches_text_to_images(tmp_path):
    noise = np.random.default_rng(0).integers(0, 256, (12, 16, 3), np.uint8)
    Image.fromarray(noise).resize((800, 600)).save(tmp_path / "noise.png")
    Image.new("RGB", (800, 600), (200, 30, 30)).save(tmp_path / "red.png")
    model = MobileClip()

    images = model.embed_images([tmp_path / "noise.png", tmp_path / "red.png"])
    prompts = model.embed_texts(["abstract colorful noise", "a plain red surface"])

    assert list(zero_shot(images, prompts).argmax(axis=1)) == [0, 1]
