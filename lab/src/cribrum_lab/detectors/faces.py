"""FACES protection: images with faces are more likely memories (docs/design.md, 6.2).

YuNet (OpenCV Zoo, MIT license) is a 230 KB ONNX face detector, committed under assets/
with its license so the Android app can ship the same file. It runs on ONNX Runtime here
and on Android, like MobileCLIP. The decoding below follows OpenCV's FaceDetectorYN and
gives the same boxes.
"""

import functools
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.model import ImageRef, Protection, Signal
from cribrum_lab.runtime import ModelRuntime, OnnxRuntime

INPUT_SIDE = 640
STRIDES = (8, 16, 32)
MIN_CONFIDENCE = 0.6
"""Lower than OpenCV's 0.9: a missed face costs more than a protected landscape."""
NMS_OVERLAP = 0.3

MODEL = Path(__file__).parents[1] / "assets" / "face_detection_yunet_2023mar.onnx"
"""From opencv/opencv_zoo at f12e12798e8314f7c074a6656816c048dcc95b7a."""


class FaceDetector:
    id = "faces"
    version = 1
    category = None
    """It emits a protection signal, which is no category to review."""

    def __init__(self, runtime: ModelRuntime | None = None) -> None:
        self._runtime = runtime

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        pixels, (width, height) = letterbox(image.path)
        faces = detect(self._runtime or _default_runtime(), pixels)
        evidence = {"faces": str(len(faces))}
        score = 0.0
        if faces:
            score = max(confidence for confidence, _ in faces)
            area = max(w * h for _, (_, _, w, h) in faces)
            evidence["largest_face"] = f"{area / (width * height):.4f}"  # fraction of the image
        return [Signal(self.id, self.version, Protection.FACES, score, evidence)]


def letterbox(path: Path) -> tuple[np.ndarray, tuple[int, int]]:
    """BGR float pixels in [0, 255], shape (1, 3, 640, 640), and the image's size in them.

    The image is scaled so its longest side is 640 px, then padded with black at the
    bottom and right.
    """
    with Image.open(path) as image:
        image.draft("RGB", (INPUT_SIDE, INPUT_SIDE))
        image = ImageOps.exif_transpose(image).convert("RGB")
        scale = INPUT_SIDE / max(image.size)
        size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
        image = image.resize(size, Image.Resampling.BILINEAR)
        canvas = np.zeros((INPUT_SIDE, INPUT_SIDE, 3), dtype=np.float32)
        canvas[: size[1], : size[0]] = np.asarray(image, dtype=np.float32)
    return canvas[:, :, ::-1].transpose(2, 0, 1)[None].copy(), size


def detect(
    runtime: ModelRuntime, pixels: np.ndarray
) -> list[tuple[float, tuple[float, float, float, float]]]:
    """Faces as (confidence, (x, y, width, height)) in input pixels, after NMS."""
    outputs = runtime.run({"input": pixels})
    scores, boxes = [], []
    for stride in STRIDES:
        cells = INPUT_SIDE // stride
        rows, cols = np.divmod(np.arange(cells * cells), cells)
        cls = np.clip(outputs[f"cls_{stride}"][0, :, 0], 0, 1)
        obj = np.clip(outputs[f"obj_{stride}"][0, :, 0], 0, 1)
        bbox = outputs[f"bbox_{stride}"][0]
        cx, cy = (cols + bbox[:, 0]) * stride, (rows + bbox[:, 1]) * stride
        w, h = np.exp(bbox[:, 2]) * stride, np.exp(bbox[:, 3]) * stride
        scores.append(np.sqrt(cls * obj))
        boxes.append(np.stack([cx - w / 2, cy - h / 2, w, h], axis=1))
    score, box = np.concatenate(scores), np.concatenate(boxes)
    keep = score >= MIN_CONFIDENCE
    return _nms(score[keep], box[keep])


def _nms(scores: np.ndarray, boxes: np.ndarray) -> list[tuple[float, tuple]]:
    kept = []
    for i in np.argsort(-scores):
        if all(_overlap(boxes[i], boxes[j]) <= NMS_OVERLAP for j in kept):
            kept.append(i)
    return [(float(scores[i]), tuple(float(v) for v in boxes[i])) for i in kept]


def _overlap(a: np.ndarray, b: np.ndarray) -> float:
    """Intersection over union of two (x, y, width, height) boxes."""
    w = min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])
    h = min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1])
    intersection = max(0.0, w) * max(0.0, h)
    return intersection / (a[2] * a[3] + b[2] * b[3] - intersection)


@functools.cache
def _default_runtime() -> OnnxRuntime:
    """One thread: the detector runs in every pipeline worker process at once."""
    return OnnxRuntime(MODEL, threads=1)
