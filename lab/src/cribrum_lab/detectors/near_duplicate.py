"""NEAR_DUPLICATE: resized, recompressed and edited copies (docs/design.md, section 5).

Two images are linked when their perceptual hashes are within a Hamming distance, which
catches resized and recompressed copies such as WhatsApp's, or when their embeddings are
close, which also catches crops and edits. Linked images form a group. Recall first: both
thresholds start loose.
"""

from collections.abc import Iterable, Sequence

import numpy as np
import xxhash

from cribrum_lab.detectors.base import AnalysisContext, Role
from cribrum_lab.folders import is_camera_folder, is_messaging_folder
from cribrum_lab.model import GROUP_KEY, ROLE_KEY, Category, ImageRef, Signal

MAX_DISTANCE = 10
"""Hamming distance between 64-bit pHashes that still links two images."""
MIN_COSINE = 0.90
"""Embedding similarity that still links two images. A 75% crop of a photo scores about
0.90 with MobileCLIP-S0; different photos of the same party stay under 0.8. MobileCLIP2-S0
links about as many pairs at this threshold (docs/design.md, section 5)."""
_CHUNK = 1024


class NearDuplicateDetector:
    id = "near_duplicate"
    version = 2
    category = Category.NEAR_DUPLICATE

    def analyze(
        self, images: Sequence[ImageRef], context: AnalysisContext
    ) -> dict[ImageRef, list[Signal]]:
        members, hashes, vectors = [], [], []
        for image in images:
            try:
                hashes.append(context.perceptual_hash(image))
            except Exception:  # Pillow raises many types on undecodable pixels
                continue
            try:
                vectors.append(context.embedding(image))
            except LookupError:
                vectors.append(None)  # a run without embeddings: hashes alone
            members.append(image)
        links = [*_hash_links(hashes), *_embedding_links(vectors)]
        result = {}
        for component in _components(len(members), links):
            best = min(component, key=lambda i: _preference(members[i], context))
            group_id = xxhash.xxh3_64(str(members[best].path).encode()).hexdigest()
            for i in component:
                signal = self._signal(i, best, group_id, hashes, vectors)
                result[members[i]] = [signal]
        return result

    def _signal(
        self,
        i: int,
        best: int,
        group_id: str,
        hashes: list[int],
        vectors: list[np.ndarray | None],
    ) -> Signal:
        distance = (hashes[i] ^ hashes[best]).bit_count()
        evidence = {GROUP_KEY: group_id, ROLE_KEY: Role.INFERIOR, "distance": str(distance)}
        # Each measure scores 1 for an identical copy and 0.5 at its linking threshold.
        scores = [1.0 - distance / (2 * (MAX_DISTANCE + 1))]
        if vectors[i] is not None and vectors[best] is not None:
            cosine = float(vectors[i] @ vectors[best])
            evidence["cosine"] = f"{cosine:.3f}"
            scores.append(0.5 + 0.5 * (cosine - MIN_COSINE) / (1.0 - MIN_COSINE))
        score = min(1.0, max(0.0, *scores))
        if i == best:
            evidence[ROLE_KEY], score = Role.BEST, 0.0
        return Signal(self.id, self.version, Category.NEAR_DUPLICATE, score, evidence)


def _hash_links(hashes: list[int]) -> Iterable[tuple[int, int]]:
    array = np.array(hashes, dtype=np.uint64)
    for start in range(0, len(array), _CHUNK):
        distances = np.bitwise_count(array[start : start + _CHUNK, None] ^ array[None, :])
        for i, j in zip(*np.nonzero(distances <= MAX_DISTANCE), strict=True):
            yield start + int(i), int(j)


def _embedding_links(vectors: list[np.ndarray | None]) -> Iterable[tuple[int, int]]:
    indices = [i for i, v in enumerate(vectors) if v is not None]
    if not indices:
        return
    matrix = np.stack([vectors[i] for i in indices])
    for start in range(0, len(matrix), _CHUNK):
        similar = matrix[start : start + _CHUNK] @ matrix.T >= MIN_COSINE
        for i, j in zip(*np.nonzero(similar), strict=True):
            yield indices[start + int(i)], indices[int(j)]


def _components(count: int, links: Iterable[tuple[int, int]]) -> list[list[int]]:
    """Groups of two or more items connected by links (union-find)."""
    parent = list(range(count))

    def root(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for a, b in links:
        a, b = root(a), root(b)
        if a != b:
            parent[a] = b
    components: dict[int, list[int]] = {}
    for i in range(count):
        components.setdefault(root(i), []).append(i)
    return [members for members in components.values() if len(members) > 1]


def _preference(image: ImageRef, context: AnalysisContext) -> tuple:
    """The copy to keep: highest resolution, least compression, camera EXIF, then folder."""
    meta = context.metadata(image)
    pixels = meta.width * meta.height
    folder = 2 if is_messaging_folder(image.path) else 0 if is_camera_folder(image.path) else 1
    return -pixels, -image.size / pixels, not meta.has_camera_exif, folder, str(image.path)
