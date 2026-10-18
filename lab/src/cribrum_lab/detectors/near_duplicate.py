"""NEAR_DUPLICATE: resized and recompressed copies, such as WhatsApp's (docs/design.md, section 5).

Stage 1: perceptual hashes within a Hamming distance are linked, and linked images form a
group. Recall first: the distance starts loose, embeddings will refine it.
"""

from collections.abc import Sequence

import numpy as np
import xxhash

from cribrum_lab.detectors.base import AnalysisContext, Role
from cribrum_lab.folders import is_camera_folder, is_messaging_folder
from cribrum_lab.model import GROUP_KEY, ROLE_KEY, Category, ImageRef, Signal

MAX_DISTANCE = 10
"""Hamming distance between 64-bit pHashes that still links two images."""
_CHUNK = 1024


class NearDuplicateDetector:
    id = "near_duplicate"
    version = 1

    def analyze(
        self, images: Sequence[ImageRef], context: AnalysisContext
    ) -> dict[ImageRef, list[Signal]]:
        hashed, hashes = [], []
        for image in images:
            try:
                hashes.append(context.perceptual_hash(image))
            except Exception:  # Pillow raises many types on undecodable pixels
                continue
            hashed.append(image)
        result = {}
        for members in _groups(np.array(hashes, dtype=np.uint64)):
            group = [hashed[i] for i in members]
            phash = {hashed[i]: hashes[i] for i in members}
            best = min(group, key=lambda image: _preference(image, context))
            group_id = xxhash.xxh3_64(str(best.path).encode()).hexdigest()
            for image in group:
                d = (phash[image] ^ phash[best]).bit_count()
                if image == best:
                    role, score = Role.BEST, 0.0
                else:
                    role, score = Role.INFERIOR, max(0.0, 1.0 - d / (2 * (MAX_DISTANCE + 1)))
                evidence = {GROUP_KEY: group_id, ROLE_KEY: role, "distance": str(d)}
                result[image] = [
                    Signal(self.id, self.version, Category.NEAR_DUPLICATE, score, evidence)
                ]
        return result


def _groups(hashes: np.ndarray) -> list[list[int]]:
    """Connected components of the images whose hashes are within MAX_DISTANCE."""
    parent = list(range(len(hashes)))

    def root(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for start in range(0, len(hashes), _CHUNK):
        rows = hashes[start : start + _CHUNK]
        distances = np.bitwise_count(rows[:, None] ^ hashes[None, :])
        for i, j in zip(*np.nonzero(distances <= MAX_DISTANCE), strict=True):
            a, b = root(start + int(i)), root(int(j))
            if a != b:
                parent[a] = b
    components: dict[int, list[int]] = {}
    for i in range(len(hashes)):
        components.setdefault(root(i), []).append(i)
    return [members for members in components.values() if len(members) > 1]


def _preference(image: ImageRef, context: AnalysisContext) -> tuple:
    """The copy to keep: highest resolution, least compression, camera EXIF, then folder."""
    meta = context.metadata(image)
    pixels = meta.width * meta.height
    folder = 2 if is_messaging_folder(image.path) else 0 if is_camera_folder(image.path) else 1
    return -pixels, -image.size / pixels, not meta.has_camera_exif, folder, str(image.path)
