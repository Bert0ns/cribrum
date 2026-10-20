from datetime import datetime, timedelta

import numpy as np
import pytest
from PIL import Image, ImageFilter

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, AnalysisContext, Role
from cribrum_lab.detectors.burst import BurstDetector
from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import Category, ImageRef

START = datetime(2024, 5, 1, 10, 0, 0)


def texture(seed=0):
    noise = np.random.default_rng(seed).integers(0, 256, (60, 80), np.uint8)
    return Image.fromarray(noise).resize((640, 480), Image.Resampling.NEAREST)


class Shots:
    """Synthetic photos with chosen capture times, cameras, blur and embeddings."""

    def __init__(self, folder):
        self.folder = folder
        self.meta = {}
        self.vectors = {}

    def add(self, name, seconds, blur=0, camera="Pixel 8", vector=None):
        path = self.folder / f"{name}.jpg"
        texture().filter(ImageFilter.GaussianBlur(blur)).save(path)
        image = ImageRef(path, path.stat().st_size, 0)
        captured = START + timedelta(seconds=seconds) if seconds is not None else None
        self.meta[image] = ImageMetadata(640, 480, "image/jpeg", "Google", camera, captured)
        if vector is not None:
            self.vectors[image] = np.array(vector, dtype=np.float32) / np.linalg.norm(vector)
        return image

    def run(self):
        def embedding(image):
            if image not in self.vectors:
                raise LookupError(image.path)
            return self.vectors[image]

        context = AnalysisContext(metadata=self.meta.__getitem__, embedding=embedding)
        return BurstDetector().analyze(list(self.meta), context)


@pytest.fixture
def shots(tmp_path):
    return Shots(tmp_path)


def role(signals, image):
    return signals[image][0].evidence[ROLE_KEY]


def test_shots_seconds_apart_form_a_burst_with_the_sharpest_as_best(shots):
    blurry = shots.add("1", 0, blur=3)
    sharp = shots.add("2", 1)
    shaky = shots.add("3", 3, blur=1)

    signals = shots.run()

    assert role(signals, sharp) == Role.BEST
    assert signals[sharp][0].score == 0.0
    assert role(signals, blurry) == role(signals, shaky) == Role.INFERIOR
    assert signals[blurry][0].score > signals[shaky][0].score >= 0.5
    assert len({s[0].evidence[GROUP_KEY] for s in signals.values()}) == 1
    assert all(s[0].category is Category.BURST for s in signals.values())


def test_a_long_pause_splits_bursts(shots):
    first = [shots.add("1", 0), shots.add("2", 2)]
    second = [shots.add("3", 60), shots.add("4", 61)]

    signals = shots.run()

    groups = [signals[image][0].evidence[GROUP_KEY] for image in first + second]
    assert groups[0] == groups[1] != groups[2] == groups[3]


def test_lone_shots_and_shots_without_a_capture_time_are_left_out(shots):
    shots.add("lone", 0)
    shots.add("later", 30)
    shots.add("no-time-1", None)
    shots.add("no-time-2", None)

    assert shots.run() == {}


def test_different_cameras_never_share_a_burst(shots):
    shots.add("mine", 0, camera="Pixel 8")
    shots.add("friend", 1, camera="iPhone 15")

    assert shots.run() == {}


def test_different_subjects_at_the_same_moment_are_not_a_burst(shots):
    a = shots.add("1", 0, vector=[1, 0])
    b = shots.add("2", 1, vector=[0, 1])
    c = shots.add("3", 2, vector=[0, 1])

    signals = shots.run()

    assert set(signals) == {b, c}
    assert a not in signals


def test_undecodable_image_has_sharpness_zero_and_is_never_best_shot(shots):
    sharp = shots.add("sharp", 0)
    broken_path = shots.folder / "broken.jpg"
    broken_path.write_bytes(b"\xff\xd8\xff\xe0" + b"corrupt")
    broken_ref = ImageRef(broken_path, broken_path.stat().st_size, 0)
    shots.meta[broken_ref] = ImageMetadata(640, 480, "image/jpeg", "Google", "Pixel 8", START + timedelta(seconds=1))

    signals = shots.run()

    assert set(signals) == {sharp, broken_ref}
    assert role(signals, sharp) == Role.BEST
    assert role(signals, broken_ref) == Role.INFERIOR
    assert signals[broken_ref][0].score == 1.0
    assert signals[broken_ref][0].evidence["sharpness"] == "0.0"
