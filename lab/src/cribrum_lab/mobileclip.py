"""MobileCLIP-S0 image and text embeddings (docs/design.md, section 5).

The ONNX export from Xenova/mobileclip_s0 runs both here and on Android, so embeddings
computed in the lab match the app's. The files are downloaded once, pinned to a revision
and checked against their SHA-256, into lab/data/models/.
"""

import functools
import hashlib
import urllib.request
from collections.abc import Sequence
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
from tokenizers import Tokenizer

from cribrum_lab.runtime import ModelRuntime, OnnxRuntime

MODEL_ID = "mobileclip_s0"
DIMENSIONS = 512
INPUT_SIDE = 256
CONTEXT_LENGTH = 77
LOGIT_SCALE = 100.0
"""CLIP's learned temperature: turns cosine similarities into softmax logits."""

MODELS = Path(__file__).resolve().parents[2] / "data" / "models" / MODEL_ID
_REVISION = "757d59c9c6870a76a4b0306f05f5061bca15c39f"
_FILES = {
    "vision_model.onnx": (
        "onnx/", "17d3c037b1d488c10c50e09f6009ea5a198caef4e0e8f4ea5617b7cb2d067ac0"
    ),
    "text_model.onnx": (
        "onnx/", "f6e9bd5742bfc515889e901634d8a2ff2a57fab8564e4ad3760e800b1a51b77c"
    ),
    "tokenizer.json": ("", "72ed5c96db5729294468543e4bc75fce14ca63f58e37300290189ba1c1e52b85"),
}  # fmt: skip
"""Folder in the repository and SHA-256 of each file."""


def model_file(name: str, folder: Path = MODELS) -> Path:
    """Path to a model file, downloaded and verified on first use."""
    path = folder / name
    if path.exists():
        return path
    prefix, sha256 = _FILES[name]
    url = f"https://huggingface.co/Xenova/mobileclip_s0/resolve/{_REVISION}/{prefix}{name}"
    folder.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + ".part")
    urllib.request.urlretrieve(url, partial)
    digest = hashlib.sha256(partial.read_bytes()).hexdigest()
    if digest != sha256:
        partial.unlink()
        raise RuntimeError(f"{name}: SHA-256 {digest}, expected {sha256}")
    partial.rename(path)
    return path


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
