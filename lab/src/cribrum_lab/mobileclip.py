"""MobileCLIP2-S0 image and text embeddings (docs/design.md, section 5).

The ONNX export from plhery/mobileclip2-onnx runs both here and on Android, so embeddings
computed in the lab match the app's. The files are downloaded once, pinned to a revision
and checked against their SHA-256, into lab/data/models/.
"""

import functools
from collections.abc import Sequence
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
from tokenizers import Tokenizer

from cribrum_lab import models
from cribrum_lab.runtime import ModelRuntime, OnnxRuntime

MODEL_ID = "mobileclip2_s0"
DIMENSIONS = 512
INPUT_SIDE = 256
CONTEXT_LENGTH = 77
LOGIT_SCALE = 64.33
"""CLIP's learned temperature (`logit_scale.exp()` of the checkpoint): turns cosine
similarities into softmax logits."""

MODELS = models.MODELS / MODEL_ID
_REPOSITORY = "plhery/mobileclip2-onnx"
_REVISION = "ba95759a5bdbaca53e9111e2550a76ec09c8fd9e"
_FILES = {
    "vision_model.onnx": (
        "onnx/s0/", "13d20ebfa8a8f63890eb2727fe4dc63009ff970f43e0f7d9d2ed999659f70c8a"
    ),
    "text_model.onnx": (
        "onnx/s0/", "df590d47744f2ee9f3ccb67c4414d17419568c05bca0c4d166f2faeedf8b92f3"
    ),
    "tokenizer.json": ("", "b556ac8c99757ffb677208af34bc8c6721572114111a6e0aaf5fa69ff0b8d842"),
}  # fmt: skip
"""Folder in the repository and SHA-256 of each file."""


def model_file(name: str, folder: Path = MODELS) -> Path:
    """Path to a model file, downloaded and verified on first use."""
    prefix, sha256 = _FILES[name]
    url = f"https://huggingface.co/{_REPOSITORY}/resolve/{_REVISION}/{prefix}{name}"
    return models.fetch(url, sha256, folder / name)


def preprocess(path: Path) -> np.ndarray:
    """RGB in [0, 1], CHW: shortest side resized to 256 (bilinear), then center-cropped."""
    with Image.open(path) as image:
        image.draft("RGB", (INPUT_SIDE, INPUT_SIDE))
        image = ImageOps.exif_transpose(image).convert("RGB")
        scale = INPUT_SIDE / min(image.size)
        size = (
            max(INPUT_SIDE, round(image.width * scale)),
            max(INPUT_SIDE, round(image.height * scale)),
        )
        image = image.resize(size, Image.Resampling.BILINEAR)
        left, top = (image.width - INPUT_SIDE) // 2, (image.height - INPUT_SIDE) // 2
        image = image.crop((left, top, left + INPUT_SIDE, top + INPUT_SIDE))
        return (np.asarray(image, dtype=np.float32) / 255.0).transpose(2, 0, 1)


class MobileClip:
    """Embeddings are L2-normalized, so a dot product is the cosine similarity."""

    id = MODEL_ID
    version = 1

    def __init__(self, vision: ModelRuntime | None = None, text: ModelRuntime | None = None):
        self._vision = vision
        self._text = text
        self._tokenizer: Tokenizer | None = None

    def embed_pixels(self, pixels: np.ndarray) -> np.ndarray:
        """`pixels` is a batch of `preprocess` outputs, shape (n, 3, 256, 256)."""
        if self._vision is None:
            self._vision = OnnxRuntime(model_file("vision_model.onnx"))
        return _normalize(self._vision.run({"pixel_values": pixels})["image_embeds"])

    def embed_images(self, paths: Sequence[Path]) -> np.ndarray:
        return self.embed_pixels(np.stack([preprocess(path) for path in paths]))

    def embed_texts(self, texts: Sequence[str]) -> np.ndarray:
        if self._text is None:
            self._text = OnnxRuntime(model_file("text_model.onnx"))
        if self._tokenizer is None:
            self._tokenizer = Tokenizer.from_file(str(model_file("tokenizer.json")))
            self._tokenizer.enable_truncation(CONTEXT_LENGTH)
            self._tokenizer.enable_padding(length=CONTEXT_LENGTH, pad_id=0)
        ids = np.array([e.ids for e in self._tokenizer.encode_batch(list(texts))], dtype=np.int64)
        return _normalize(self._text.run({"input_ids": ids})["text_embeds"])


@functools.cache
def default_model() -> MobileClip:
    return MobileClip()


def zero_shot(image_embeddings: np.ndarray, prompt_embeddings: np.ndarray) -> np.ndarray:
    """Probability of each prompt for each image: softmax over the scaled cosine similarities."""
    logits = LOGIT_SCALE * image_embeddings @ prompt_embeddings.T
    logits -= logits.max(axis=-1, keepdims=True)
    exp = np.exp(logits)
    return exp / exp.sum(axis=-1, keepdims=True)


def _normalize(vectors: np.ndarray) -> np.ndarray:
    return (vectors / np.linalg.norm(vectors, axis=-1, keepdims=True)).astype(np.float32)
