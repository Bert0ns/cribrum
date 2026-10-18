import numpy as np
import pytest
from PIL import Image

from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.faces import INPUT_SIDE, STRIDES, FaceDetector, detect, letterbox
from cribrum_lab.model import ImageRef, Protection


class FakeYuNet:
    """Outputs of YuNet with chosen anchors lit: (stride, row, col, confidence, log size)."""

    def __init__(self, *anchors):
        self.anchors = anchors

    def run(self, inputs):
        assert inputs["input"].shape == (1, 3, INPUT_SIDE, INPUT_SIDE)
        outputs = {}
        for stride in STRIDES:
            n = (INPUT_SIDE // stride) ** 2
            outputs[f"cls_{stride}"] = np.zeros((1, n, 1), np.float32)
            outputs[f"obj_{stride}"] = np.zeros((1, n, 1), np.float32)
            outputs[f"bbox_{stride}"] = np.zeros((1, n, 4), np.float32)
        for stride, row, col, confidence, log_size in self.anchors:
            i = row * (INPUT_SIDE // stride) + col
            outputs[f"cls_{stride}"][0, i] = confidence
            outputs[f"obj_{stride}"][0, i] = confidence
            outputs[f"bbox_{stride}"][0, i] = [0.5, 0.5, log_size, log_size]
        return outputs


def test_letterbox_scales_the_longest_side_and_pads_in_bgr(tmp_path):
    path = tmp_path / "a.png"
    Image.new("RGB", (1280, 640), (255, 0, 0)).save(path)

    pixels, size = letterbox(path)

    assert size == (640, 320)
    assert pixels.shape == (1, 3, 640, 640)
    assert pixels[0, :, 100, 100].tolist() == [0, 0, 255]  # red, as blue-green-red
    assert pixels[0, :, 400, 100].tolist() == [0, 0, 0]  # padding below the image


def test_detect_decodes_anchor_offsets_and_sizes():
    # Anchor at row 2, column 3 of the stride-32 grid, box of exp(1) * 32 px.
    [(confidence, box)] = detect(FakeYuNet((32, 2, 3, 0.9, 1.0)), np.zeros((1, 3, 640, 640)))

    size = np.exp(1.0) * 32
    assert confidence == pytest.approx(0.9)
    assert box == pytest.approx((3.5 * 32 - size / 2, 2.5 * 32 - size / 2, size, size))


def test_detect_drops_weak_and_overlapping_faces():
    runtime = FakeYuNet((8, 10, 10, 0.9, 1.0), (8, 10, 11, 0.8, 1.0), (8, 50, 50, 0.3, 1.0))

    faces = detect(runtime, np.zeros((1, 3, 640, 640)))

    assert [round(c, 2) for c, _ in faces] == [0.9]


def test_face_detector_emits_a_faces_protection(tmp_path):
    path = tmp_path / "a.jpg"
    Image.new("RGB", (640, 640)).save(path)
    detector = FaceDetector(FakeYuNet((32, 5, 5, 0.95, 2.0), (16, 30, 30, 0.7, 1.0)))

    [signal] = detector.analyze(ImageRef(path, 0, 0), AnalysisContext())

    assert signal.category is Protection.FACES
    assert signal.score == pytest.approx(0.95)
    assert signal.evidence["faces"] == "2"
    assert float(signal.evidence["largest_face"]) == pytest.approx(
        (np.exp(2.0) * 32) ** 2 / 640**2, rel=1e-3
    )


def test_no_face_scores_zero(tmp_path):
    path = tmp_path / "a.jpg"
    Image.new("RGB", (300, 200)).save(path)

    [signal] = FaceDetector(FakeYuNet()).analyze(ImageRef(path, 0, 0), AnalysisContext())

    assert signal.score == 0.0
    assert signal.evidence == {"faces": "0"}
