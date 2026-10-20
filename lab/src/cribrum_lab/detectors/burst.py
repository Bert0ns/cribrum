"""BURST: shots of the same moment, taken seconds apart (docs/design.md, section 5).

Photos from the same camera whose capture times are at most MAX_GAP apart are chained
into a burst, as long as consecutive shots also look alike when both have an embedding.
The sharpest shot, by the BLURRY measure, is the one to keep.

shortcut: sharpness only; add "eyes open" once a face detector exists.
"""

from collections import defaultdict
from collections.abc import Sequence
from datetime import datetime, timedelta
from itertools import pairwise

import numpy as np
import xxhash

from cribrum_lab.detectors import blur
from cribrum_lab.detectors.base import AnalysisContext, Role
from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import GROUP_KEY, ROLE_KEY, Category, ImageRef, Signal

MAX_GAP = timedelta(seconds=3)
"""EXIF capture times have 1 s resolution; manual bursts are often 1-2 s apart."""
BURST_MIN_COSINE = 0.80
"""Consecutive shots below this similarity are different subjects, whatever the time."""


class BurstDetector:
    id = "burst"
    version = 1
    category = Category.BURST

    def analyze(
        self, images: Sequence[ImageRef], context: AnalysisContext
    ) -> dict[ImageRef, list[Signal]]:
        by_camera = defaultdict(list)
        for image in images:
            meta = _metadata(image, context)
            if meta is not None and meta.captured_at is not None:
                by_camera[meta.camera_make, meta.camera_model].append((meta.captured_at, image))
        result = {}
        for shots in by_camera.values():
            shots.sort(key=lambda shot: (shot[0], str(shot[1].path)))
            for burst in _bursts(shots, context):
                result.update(self._signals(burst))
        return result

    def _signals(self, burst: list[ImageRef]) -> dict[ImageRef, list[Signal]]:
        sharpness = {image: _sharpness(image) for image in burst}
        best = max(burst, key=lambda image: sharpness[image])
        group_id = xxhash.xxh3_64(str(burst[0].path).encode()).hexdigest()
        result = {}
        for image in burst:
            if image == best:
                role, score = Role.BEST, 0.0
            else:
                # 0.5 when as sharp as the best shot, up to 1 when it has no detail at all.
                ratio = sharpness[image] / sharpness[best] if sharpness[best] > 0 else 1.0
                role, score = Role.INFERIOR, 1.0 - 0.5 * min(1.0, ratio)
            evidence = {
                GROUP_KEY: group_id,
                ROLE_KEY: role,
                "sharpness": f"{sharpness[image]:.1f}",
                "shots": str(len(burst)),
            }
            result[image] = [Signal(self.id, self.version, Category.BURST, score, evidence)]
        return result


def _bursts(
    shots: list[tuple[datetime, ImageRef]], context: AnalysisContext
) -> list[list[ImageRef]]:
    bursts, current = [], [shots[0][1]]
    for (previous_time, previous), (time, image) in pairwise(shots):
        if time - previous_time <= MAX_GAP and _alike(previous, image, context):
            current.append(image)
        else:
            bursts.append(current)
            current = [image]
    bursts.append(current)
    return [burst for burst in bursts if len(burst) > 1]


def _alike(a: ImageRef, b: ImageRef, context: AnalysisContext) -> bool:
    """Recall first: without both embeddings, time alone decides."""
    try:
        return float(np.dot(context.embedding(a), context.embedding(b))) >= BURST_MIN_COSINE
    except LookupError:
        return True


def _metadata(image: ImageRef, context: AnalysisContext) -> ImageMetadata | None:
    try:
        return context.metadata(image)
    except Exception:  # unreadable header: no capture time to cluster on
        return None


def _sharpness(image: ImageRef) -> float:
    try:
        measured = blur.measure(image.path)
    except Exception:  # undecodable pixels: never the best shot
        return 0.0
    return measured[1] if measured else 0.0
