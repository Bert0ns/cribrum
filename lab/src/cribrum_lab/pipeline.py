"""Analysis pipeline: scan → metadata → per-image detectors → embeddings → group detectors.

Every step stores its results as it goes and skips what the index already holds for the
same detector version, so an interrupted run resumes where it stopped and a second run on
unchanged data does no detector work.
"""

import multiprocessing
from collections.abc import Callable, Sequence
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

import numpy as np
import xxhash

from cribrum_lab import hashing, mobileclip
from cribrum_lab.detectors import AnalysisContext, Detector, GroupDetector
from cribrum_lab.detectors.blur import BlurDetector
from cribrum_lab.detectors.exact_duplicate import ExactDuplicateDetector
from cribrum_lab.detectors.exposure import ExposureDetector
from cribrum_lab.detectors.near_duplicate import NearDuplicateDetector
from cribrum_lab.detectors.screenshot import ScreenshotDetector
from cribrum_lab.index import ImageIndex
from cribrum_lab.metadata import ImageMetadata, UnreadableImage, read_metadata
from cribrum_lab.model import ImageRef, Signal
from cribrum_lab.sources import FilesystemImageSource

DETECTORS: list[Detector] = [BlurDetector(), ExposureDetector(), ScreenshotDetector()]
GROUP_DETECTORS: list[GroupDetector] = [ExactDuplicateDetector(), NearDuplicateDetector()]

Progress = Callable[[str, int, int], None]
"""Called with a step name, the items done and the items in total."""

EMBEDDING_CHUNK = 256
"""Images decoded per round before the model runs: bounds the memory held by decoded pixels."""
EMBEDDING_BATCH = 32


class Embedder(Protocol):
    """Embeds images preprocessed by `mobileclip.preprocess`, as `MobileClip` does."""

    id: str
    version: int

    def embed_pixels(self, pixels: np.ndarray) -> np.ndarray: ...


@dataclass
class Stats:
    images: int = 0
    new_or_changed: int = 0
    forgotten: int = 0
    unreadable: int = 0
    detector_runs: int = 0
    """Per-image analyses, perceptual hashes and embeddings computed, plus group detector runs."""
    failures: list[str] = field(default_factory=list)
    """Analyses that raised. They are not stored, so the next run retries them."""


def run(
    root: Path,
    index: ImageIndex,
    detectors: Sequence[Detector] = DETECTORS,
    group_detectors: Sequence[GroupDetector] = GROUP_DETECTORS,
    embedder: Embedder | None = None,
    jobs: int = 1,
    progress: Progress | None = None,
) -> Stats:
    """Analyzes every image under `root`. Images under it that are gone leave the index.

    Without an `embedder` no embeddings are computed, and detectors that need them get
    an `AnalysisContext.embedding` that raises.
    """
    progress = progress or (lambda step, done, total: None)
    root = root.resolve()
    stats = Stats()

    found = set()
    for image in FilesystemImageSource(root).scan():
        found.add(image.path)
        stats.new_or_changed += index.record(image)
        progress("scan", len(found), 0)
    gone = [
        p for p in (i.path for i in index.images()) if p.is_relative_to(root) and p not in found
    ]
    index.forget(gone)
    stats.forgotten = len(gone)
    stats.images = len(found)

    pending = index.without_metadata()
    for done, image in enumerate(pending, 1):
        try:
            index.set_metadata(image.path, read_metadata(image.path))
        except UnreadableImage:
            index.set_unreadable(image.path)
        progress("metadata", done, len(pending))
    unreadable = set(index.unreadable())
    stats.unreadable = len(unreadable & found)

    images = [i for i in index.images() if i.path in found]
    tasks = []
    for image in images:
        if image.path in unreadable:
            continue  # pixels can't be decoded either; group detectors still see the file
        todo = [d for d in detectors if not index.is_analyzed(image.path, d.id, d.version)]
        needs_phash = index.perceptual_hash(image.path) is None
        if todo or needs_phash:
            tasks.append((image, index.metadata(image.path), todo, needs_phash))
    for done, (image, results, phash) in enumerate(_map(_analyze, tasks, jobs), 1):
        for detector_id, version, outcome in results:
            if isinstance(outcome, str):
                stats.failures.append(f"{image.path}: {detector_id}: {outcome}")
            else:
                index.set_signals(image.path, detector_id, version, outcome)
                stats.detector_runs += 1
        if isinstance(phash, str):
            stats.failures.append(f"{image.path}: perceptual hash: {phash}")
        elif phash is not None:
            index.set_perceptual_hash(image.path, phash)
            stats.detector_runs += 1
        progress("detectors", done, len(tasks))

    embeddings = {}
    if embedder is not None:
        _embed(
            embedder, index, [i for i in images if i.path not in unreadable], jobs, stats, progress
        )
        embeddings = index.embeddings(embedder.id, embedder.version)
    context = AnalysisContext(
        content_hash=_cached_content_hash(index),
        perceptual_hash=_stored_perceptual_hash(index),
        metadata=_metadata(index),
        embedding=_stored_embedding(embeddings),
    )
    fingerprint = _fingerprint(images)
    for detector in group_detectors:
        if index.group_fingerprint(detector.id, detector.version) == fingerprint:
            continue
        progress(detector.id, 0, 1)
        signals = detector.analyze(images, context)
        index.set_signals_of_many(
            detector.id, detector.version, {i.path: signals.get(i, []) for i in images}
        )
        index.set_group_fingerprint(detector.id, detector.version, fingerprint)
        stats.detector_runs += 1
        progress(detector.id, 1, 1)
    return stats


def _analyze(
    task: tuple[ImageRef, ImageMetadata, list[Detector], bool],
) -> tuple[ImageRef, list[tuple[str, int, list[Signal] | str]], int | str | None]:
    """Runs in a worker process: the metadata comes along, the index stays in the parent.

    Errors come back as text, so one broken file does not stop a run over thousands.
    """
    image, metadata, detectors, needs_phash = task
    context = AnalysisContext(metadata=lambda _: metadata)
    results = []
    for detector in detectors:
        try:
            outcome = detector.analyze(image, context)
        except Exception as error:
            outcome = _describe(error)
        results.append((detector.id, detector.version, outcome))
    phash = None
    if needs_phash:
        try:
            phash = hashing.perceptual_hash(image)
        except Exception as error:
            phash = _describe(error)
    return image, results, phash


def _embed(
    embedder: Embedder,
    index: ImageIndex,
    images: list[ImageRef],
    jobs: int,
    stats: Stats,
    progress: Progress,
) -> None:
    """Decodes in the worker processes; the model runs here, on all cores, in batches."""
    stored = index.embeddings(embedder.id, embedder.version).keys()
    todo = [image for image in images if image.path not in stored]
    for start in range(0, len(todo), EMBEDDING_CHUNK):
        chunk = todo[start : start + EMBEDDING_CHUNK]
        decoded = []
        for image, pixels in zip(chunk, _map(_preprocess, chunk, jobs), strict=True):
            if isinstance(pixels, str):
                stats.failures.append(f"{image.path}: embedding: {pixels}")
            else:
                decoded.append((image, pixels))
        for batch_start in range(0, len(decoded), EMBEDDING_BATCH):
            batch = decoded[batch_start : batch_start + EMBEDDING_BATCH]
            vectors = embedder.embed_pixels(np.stack([pixels for _, pixels in batch]))
            index.set_embeddings(
                embedder.id,
                embedder.version,
                {image.path: v for (image, _), v in zip(batch, vectors, strict=True)},
            )
            stats.detector_runs += len(batch)
            progress("embeddings", start + batch_start + len(batch), len(todo))


def _preprocess(image: ImageRef) -> np.ndarray | str:
    try:
        return mobileclip.preprocess(image.path)
    except Exception as error:
        return _describe(error)


def _describe(error: Exception) -> str:
    return f"{type(error).__name__}: {error}"


def _map(function, tasks, jobs):
    if jobs == 1:
        yield from map(function, tasks)
        return
    # Not fork: ONNX Runtime's threads would be copied mid-state into the children.
    with ProcessPoolExecutor(jobs, mp_context=multiprocessing.get_context("forkserver")) as pool:
        yield from pool.map(function, tasks, chunksize=4)


def _cached_content_hash(index: ImageIndex) -> Callable[[ImageRef], str]:
    def content_hash(image: ImageRef) -> str:
        digest = index.content_hash(image.path)
        if digest is None:
            digest = hashing.content_hash(image)
            index.set_content_hash(image.path, digest)
        return digest

    return content_hash


def _stored_perceptual_hash(index: ImageIndex) -> Callable[[ImageRef], int]:
    """Hashes come from the worker step; an image without one could not be decoded."""

    def perceptual_hash(image: ImageRef) -> int:
        phash = index.perceptual_hash(image.path)
        if phash is None:
            raise UnreadableImage(image.path)
        return phash

    return perceptual_hash


def _stored_embedding(embeddings: dict[Path, np.ndarray]) -> Callable[[ImageRef], np.ndarray]:
    def embedding(image: ImageRef) -> np.ndarray:
        found = embeddings.get(image.path)
        if found is None:
            raise LookupError(f"no embedding for {image.path}")
        return found

    return embedding


def _metadata(index: ImageIndex) -> Callable[[ImageRef], ImageMetadata]:
    def metadata(image: ImageRef) -> ImageMetadata:
        found = index.metadata(image.path)
        if found is None:
            raise UnreadableImage(image.path)
        return found

    return metadata


def _fingerprint(images: Sequence[ImageRef]) -> str:
    digest = xxhash.xxh3_64()
    for image in sorted(images, key=lambda i: str(i.path)):
        digest.update(f"{image.path}\0{image.size}\0{image.mtime_ns}\n".encode())
    return digest.hexdigest()
