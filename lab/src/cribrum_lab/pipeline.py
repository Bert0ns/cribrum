"""Analysis pipeline: scan → metadata → detectors that read no embedding → embeddings →
detectors that read one → group detectors.

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
from cribrum_lab.detectors.burst import BurstDetector
from cribrum_lab.detectors.document import DocumentDetector
from cribrum_lab.detectors.exact_duplicate import ExactDuplicateDetector
from cribrum_lab.detectors.exposure import ExposureDetector
from cribrum_lab.detectors.faces import FaceDetector
from cribrum_lab.detectors.forwarded import ForwardedDetector
from cribrum_lab.detectors.near_duplicate import NearDuplicateDetector
from cribrum_lab.detectors.screenshot import ScreenshotDetector
from cribrum_lab.detectors.sticker import StickerDetector
from cribrum_lab.folders import is_sticker_folder
from cribrum_lab.index import ImageIndex
from cribrum_lab.metadata import ImageMetadata, UnreadableImage, read_metadata
from cribrum_lab.model import ImageRef, Signal
from cribrum_lab.sources import FilesystemImageSource

DETECTORS: list[Detector] = [
    BlurDetector(),
    ExposureDetector(),
    ScreenshotDetector(),
    DocumentDetector(),
    ForwardedDetector(),
    FaceDetector(),
    StickerDetector(),
]
GROUP_DETECTORS: list[GroupDetector] = [
    ExactDuplicateDetector(),
    NearDuplicateDetector(),
    BurstDetector(),
]

Progress = Callable[[str, int, int], None]
"""Called with a step name, the items done and the items in total."""

EMBEDDING_CHUNK = 256
"""Images decoded per round before the model runs: bounds the memory held by decoded pixels."""
EMBEDDING_BATCH = 32
RECORD_BATCH = 300
"""Images the walk records in one transaction."""


class MissingEmbedding(LookupError):
    """The run has no embedding for this image: no embedder, or it could not decode it."""


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
    skipped: int = 0
    """Per-image analyses left for a run with embeddings."""
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
    record_batch: int = RECORD_BATCH,
) -> Stats:
    """Analyzes every image under `root`.

    Images under it that are gone leave the index, unless the scan could not list the root in
    full: then it cannot tell a deleted file from a folder it could not read.

    Without an `embedder` no embeddings are computed. Per-image detectors that need one
    are skipped, without storing anything, so a later run with an embedder runs them.
    """
    progress = progress or (lambda step, done, total: None)
    root = root.resolve()
    stats = Stats()

    found = set()
    source = FilesystemImageSource(root, index.images)
    batch: list[ImageRef] = []
    for image in source.scan():
        found.add(image.path)
        batch.append(image)
        if len(batch) == record_batch:
            stats.new_or_changed += index.record_all(batch)
            batch.clear()
        progress("scan", len(found), 0)
    stats.new_or_changed += index.record_all(batch)
    walked = source.walked_roots
    gone = [
        p
        for p in (i.path for i in index.images())
        if p not in found and any(p.is_relative_to(r) for r in walked)
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
    readable = [i for i in images if i.path not in unreadable]
    # The home screen lists candidates as soon as these are stored; the embeddings come after.
    fast = [d for d in detectors if not getattr(d, "uses_embeddings", False)]
    _analyze_each(
        readable, fast, {}, "detectors", index, jobs, stats, progress, before_embeddings=True
    )
    # A sticker is listed under STICKER only (docs/design.md, 8), so neither MobileCLIP nor
    # text coverage runs on it.
    embeddable = [i for i in readable if not is_sticker_folder(i.path)]
    embeddings = {}
    if embedder is not None:
        _embed(embedder, index, detectors, embeddable, jobs, stats, progress)
        kept = {i.path for i in embeddable}
        embeddings = {
            path: vector
            for path, vector in index.embeddings(embedder.id, embedder.version).items()
            if path in kept
        }
    reading = [d for d in detectors if d not in fast]
    _analyze_each(
        embeddable,
        reading,
        embeddings,
        "embedded_detectors",
        index,
        jobs,
        stats,
        progress,
        before_embeddings=False,
    )

    # The exact detector skips an image whose content hash fails: the run stores those paths
    # with its fingerprint.
    hash_failed: set[str] = set()
    cached_content_hash = _cached_content_hash(index)

    def content_hash(image: ImageRef) -> str:
        try:
            return cached_content_hash(image)
        except OSError:
            hash_failed.add(str(image.path))
            raise

    def hashes(image: ImageRef) -> bool:
        try:
            cached_content_hash(image)
        except OSError:
            return False
        return True

    context = AnalysisContext(
        content_hash=content_hash,
        perceptual_hash=_stored_perceptual_hash(index),
        metadata=_metadata(index),
        embedding=_stored_embedding(embeddings),
    )
    # Embeddings change what group detectors can see: a run with them is a different run.
    model = f"{embedder.id}:{embedder.version}:{len(embeddings)}" if embedder else ""
    # A hash that failed on an earlier run is a result a group detector lacked.
    hashed = index.hashed_paths()
    fingerprint = _fingerprint(images, f"{model}:{sum(i.path in hashed for i in images)}")
    for detector in group_detectors:
        # Hashing again only what failed in the last run groups a file that has become
        # readable, without a run on every scan.
        failed = set(index.group_hash_failed(detector.id, detector.version))
        if index.group_fingerprint(detector.id, detector.version) == fingerprint and not any(
            hashes(i) for i in images if str(i.path) in failed
        ):
            continue
        progress(detector.id, 0, 1)
        hash_failed.clear()
        signals = detector.analyze(images, context)
        index.set_signals_of_many(
            detector.id, detector.version, {i.path: signals.get(i, []) for i in images}
        )
        index.set_group_fingerprint(detector.id, detector.version, fingerprint, sorted(hash_failed))
        stats.detector_runs += 1
        progress(detector.id, 1, 1)
    return stats


def _analyze_each(
    images: list[ImageRef],
    detectors: Sequence[Detector],
    embeddings: dict[Path, np.ndarray],
    step: str,
    index: ImageIndex,
    jobs: int,
    stats: Stats,
    progress: Progress,
    *,
    before_embeddings: bool,
) -> None:
    """Runs `detectors` on the images that lack them.

    The pass `before_embeddings` also computes the perceptual hashes, and a detector in it that
    asks for an embedding fails: it should have set `uses_embeddings`.
    """
    tasks = []
    for image in images:
        todo = [d for d in detectors if not index.is_analyzed(image.path, d.id, d.version)]
        needs_phash = before_embeddings and index.perceptual_hash(image.path) is None
        if todo or needs_phash:
            tasks.append(
                (
                    image,
                    index.metadata(image.path),
                    embeddings.get(image.path),
                    todo,
                    needs_phash,
                    before_embeddings,
                )
            )
    for done, (image, results, phash) in enumerate(_map(_analyze, tasks, jobs), 1):
        for detector_id, version, outcome in results:
            if outcome is None:
                stats.skipped += 1
            elif isinstance(outcome, str):
                stats.failures.append(f"{image.path}: {detector_id}: {outcome}")
            else:
                index.set_signals(image.path, detector_id, version, outcome)
                stats.detector_runs += 1
        if isinstance(phash, str):
            stats.failures.append(f"{image.path}: perceptual hash: {phash}")
        elif phash is not None:
            index.set_perceptual_hash(image.path, phash)
            stats.detector_runs += 1
        progress(step, done, len(tasks))


def _analyze(
    task: tuple[ImageRef, ImageMetadata, np.ndarray | None, list[Detector], bool, bool],
) -> tuple[ImageRef, list[tuple[str, int, list[Signal] | str | None]], int | str | None]:
    """Runs in a worker process: metadata and embedding come along, the index stays in the
    parent.

    Errors come back as text, so one broken file does not stop a run over thousands. A
    detector that needs the missing embedding comes back as None.
    """
    image, metadata, embedding, detectors, needs_phash, before_embeddings = task
    context = AnalysisContext(
        metadata=lambda _: metadata,
        embedding=_no_embedding_yet
        if before_embeddings
        else _stored_embedding({image.path: embedding} if embedding is not None else {}),
    )
    results = []
    for detector in detectors:
        try:
            outcome = detector.analyze(image, context)
        except MissingEmbedding:
            outcome = None
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


def _no_embedding_yet(image: ImageRef) -> np.ndarray:
    raise RuntimeError(f"{image.path}: reads an embedding without uses_embeddings")


def _embed(
    embedder: Embedder,
    index: ImageIndex,
    detectors: Sequence[Detector],
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
            # A detector's result belongs to the embedding it read: a new one means analyzing again.
            index.forget_analyses(
                [image.path for image, _ in batch],
                [d.id for d in detectors if getattr(d, "uses_embeddings", False)],
            )
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
            raise MissingEmbedding(image.path)
        return found

    return embedding


def _metadata(index: ImageIndex) -> Callable[[ImageRef], ImageMetadata]:
    def metadata(image: ImageRef) -> ImageMetadata:
        found = index.metadata(image.path)
        if found is None:
            raise UnreadableImage(image.path)
        return found

    return metadata


def _fingerprint(images: Sequence[ImageRef], inputs: str) -> str:
    digest = xxhash.xxh3_64(inputs.encode())
    for image in sorted(images, key=lambda i: str(i.path)):
        digest.update(f"{image.path}\0{image.size}\0{image.mtime_ns}\n".encode())
    return digest.hexdigest()
