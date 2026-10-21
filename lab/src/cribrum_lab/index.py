"""Persistent image index, so re-runs only process new or changed files.

Mirrors the planned Room schema (docs/design.md, section 7). Keep the database
under lab/data/: it holds paths and metadata of personal images.
"""

import json
import sqlite3
from collections import defaultdict
from collections.abc import Iterable, Mapping
from datetime import datetime
from pathlib import Path
from typing import Self

import numpy as np

from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import ImageRef, IndexedImage, ReviewState, Signal, kind

_SCHEMA = """
CREATE TABLE IF NOT EXISTS images (
    path TEXT PRIMARY KEY,
    size INTEGER NOT NULL,
    mtime_ns INTEGER NOT NULL,
    review_state TEXT NOT NULL DEFAULT 'unreviewed',
    width INTEGER,
    height INTEGER,
    mime TEXT,
    camera_make TEXT,
    camera_model TEXT,
    captured_at TEXT,
    unreadable INTEGER NOT NULL DEFAULT 0,
    content_hash TEXT,
    perceptual_hash TEXT
);
-- Which detector version last analyzed an image, even if it emitted no signal.
CREATE TABLE IF NOT EXISTS analyses (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    detector_id TEXT NOT NULL,
    detector_version INTEGER NOT NULL,
    PRIMARY KEY (path, detector_id)
);
CREATE TABLE IF NOT EXISTS signals (
    path TEXT NOT NULL,
    detector_id TEXT NOT NULL,
    detector_version INTEGER NOT NULL,
    category TEXT NOT NULL,
    score REAL NOT NULL,
    evidence TEXT NOT NULL,
    FOREIGN KEY (path, detector_id) REFERENCES analyses ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS signals_by_analysis ON signals (path, detector_id);
-- Signal identities (Signal.identity) an image had when the user kept it.
CREATE TABLE IF NOT EXISTS kept_signals (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    category TEXT NOT NULL,
    group_id TEXT NOT NULL,
    role TEXT NOT NULL,
    PRIMARY KEY (path, category, group_id, role)
);
CREATE TABLE IF NOT EXISTS embeddings (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    model TEXT NOT NULL,
    model_version INTEGER NOT NULL,
    vector BLOB NOT NULL,  -- float32, little-endian
    PRIMARY KEY (path, model)
);
-- The image set a group detector version last ran on, so unchanged sets are skipped.
CREATE TABLE IF NOT EXISTS group_runs (
    detector_id TEXT PRIMARY KEY,
    detector_version INTEGER NOT NULL,
    fingerprint TEXT NOT NULL
);
-- Folders the user excluded (docs/design.md, section 6.3): scanned, but never candidates.
CREATE TABLE IF NOT EXISTS excluded_folders (
    path TEXT PRIMARY KEY
);
"""
SCHEMA_VERSION = 4
"""Bump on every schema change: an index from another version is refused, not misread."""


class IndexVersionError(Exception):
    """The database was written with another schema version."""


class ImageIndex:
    """Methods taking a path raise KeyError for an image that was never recorded."""

    def __init__(self, database: Path | str) -> None:
        self._db = sqlite3.connect(database)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.execute("PRAGMA journal_mode = WAL")  # one commit per image must stay cheap
        self._db.execute("PRAGMA synchronous = NORMAL")
        tables = self._db.execute("SELECT count(*) FROM sqlite_master").fetchone()[0]
        version = self._db.execute("PRAGMA user_version").fetchone()[0]
        if tables and version != SCHEMA_VERSION:
            self._db.close()
            raise IndexVersionError(
                f"{database} has schema version {version}, expected {SCHEMA_VERSION}"
            )
        self._db.executescript(_SCHEMA)
        self._db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        self._db.close()

    def record(self, image: ImageRef) -> bool:
        """Adds or updates an image. Returns True if it is new or changed.

        A changed image (different size or mtime) loses its metadata, hashes, embeddings,
        unreadable mark and signals, which must be computed again. It keeps its review
        state: the file may only have been touched, and a decision the user made must not
        be lost. A real edit shows up as new signals, which the ranker treats as such.
        """
        path = str(image.path)
        row = self._db.execute(
            "SELECT size, mtime_ns FROM images WHERE path = ?", (path,)
        ).fetchone()
        if row == (image.size, image.mtime_ns):
            return False
        with self._db:
            if row is None:
                self._db.execute(
                    "INSERT INTO images (path, size, mtime_ns) VALUES (?, ?, ?)",
                    (path, image.size, image.mtime_ns),
                )
            else:
                self._db.execute(
                    """UPDATE images SET size = ?, mtime_ns = ?, width = NULL, height = NULL,
                       mime = NULL, camera_make = NULL, camera_model = NULL, captured_at = NULL,
                       unreadable = 0, content_hash = NULL, perceptual_hash = NULL
                       WHERE path = ?""",
                    (image.size, image.mtime_ns, path),
                )
                self._db.execute("DELETE FROM analyses WHERE path = ?", (path,))
                self._db.execute("DELETE FROM embeddings WHERE path = ?", (path,))
        return True

    def images(self) -> list[ImageRef]:
        rows = self._db.execute("SELECT path, size, mtime_ns FROM images ORDER BY path")
        return [ImageRef(Path(path), size, mtime_ns) for path, size, mtime_ns in rows]

    def forget(self, paths: Iterable[Path]) -> None:
        """Removes images from the index, with everything stored about them. Files are untouched."""
        with self._db:
            self._db.executemany("DELETE FROM images WHERE path = ?", [(str(p),) for p in paths])

    def without_metadata(self) -> list[ImageRef]:
        """Images whose metadata was never read, leaving out the unreadable ones."""
        rows = self._db.execute(
            """SELECT path, size, mtime_ns FROM images
               WHERE width IS NULL AND NOT unreadable ORDER BY path"""
        )
        return [ImageRef(Path(path), size, mtime_ns) for path, size, mtime_ns in rows]

    def set_unreadable(self, path: Path) -> None:
        """Marks an image whose header can't be decoded, so it isn't retried until it changes."""
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET unreadable = 1 WHERE path = ?", (str(path),)
            )
        _require(updated.rowcount, path)

    def unreadable(self) -> list[Path]:
        rows = self._db.execute("SELECT path FROM images WHERE unreadable ORDER BY path")
        return [Path(path) for (path,) in rows]

    def set_metadata(self, path: Path, metadata: ImageMetadata) -> None:
        captured_at = metadata.captured_at.isoformat() if metadata.captured_at else None
        with self._db:
            updated = self._db.execute(
                """UPDATE images SET width = ?, height = ?, mime = ?, camera_make = ?,
                   camera_model = ?, captured_at = ? WHERE path = ?""",
                (
                    metadata.width,
                    metadata.height,
                    metadata.mime,
                    metadata.camera_make,
                    metadata.camera_model,
                    captured_at,
                    str(path),
                ),
            )
        _require(updated.rowcount, path)

    def metadata(self, path: Path) -> ImageMetadata | None:
        row = self._db.execute(
            """SELECT width, height, mime, camera_make, camera_model, captured_at
               FROM images WHERE path = ? AND width IS NOT NULL""",
            (str(path),),
        ).fetchone()
        if row is None:
            return None
        *fields, captured_at = row
        return ImageMetadata(
            *fields, captured_at=datetime.fromisoformat(captured_at) if captured_at else None
        )

    def set_content_hash(self, path: Path, digest: str) -> None:
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET content_hash = ? WHERE path = ?", (digest, str(path))
            )
        _require(updated.rowcount, path)

    def content_hash(self, path: Path) -> str | None:
        row = self._db.execute(
            "SELECT content_hash FROM images WHERE path = ?", (str(path),)
        ).fetchone()
        _require(row, path)
        return row[0]

    def set_perceptual_hash(self, path: Path, phash: int) -> None:
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET perceptual_hash = ? WHERE path = ?", (f"{phash:016x}", str(path))
            )
        _require(updated.rowcount, path)

    def perceptual_hash(self, path: Path) -> int | None:
        """Stored as hex text: SQLite integers are signed and a hash may use all 64 bits."""
        row = self._db.execute(
            "SELECT perceptual_hash FROM images WHERE path = ?", (str(path),)
        ).fetchone()
        _require(row, path)
        return None if row[0] is None else int(row[0], 16)

    def set_embeddings(
        self, model: str, model_version: int, vectors: Mapping[Path, np.ndarray]
    ) -> None:
        with self._db:
            for path, vector in vectors.items():
                exists = self._db.execute(
                    "SELECT 1 FROM images WHERE path = ?", (str(path),)
                ).fetchone()
                _require(exists, path)
                self._db.execute(
                    "INSERT OR REPLACE INTO embeddings VALUES (?, ?, ?, ?)",
                    (str(path), model, model_version, vector.astype("<f4").tobytes()),
                )

    def delete_embeddings(self) -> None:
        """Deletes every stored embedding and nothing else: the next run computes them again."""
        with self._db:
            self._db.execute("DELETE FROM embeddings")

    def forget_analyses(self, paths: Iterable[Path], detector_ids: Iterable[str]) -> None:
        """Drops what these detectors stored for these images, signals included."""
        with self._db:
            self._db.executemany(
                "DELETE FROM analyses WHERE path = ? AND detector_id = ?",
                [(str(p), d) for p in paths for d in detector_ids],
            )

    def embeddings(self, model: str, model_version: int) -> dict[Path, np.ndarray]:
        """Every stored embedding of this model version, loaded at once for group detectors."""
        rows = self._db.execute(
            "SELECT path, vector FROM embeddings WHERE model = ? AND model_version = ?",
            (model, model_version),
        )
        return {Path(path): np.frombuffer(vector, dtype="<f4") for path, vector in rows}

    def set_signals(
        self, path: Path, detector_id: str, detector_version: int, signals: list[Signal]
    ) -> None:
        """Replaces whatever this detector stored for the image, from any version."""
        self.set_signals_of_many(detector_id, detector_version, {path: signals})

    def set_signals_of_many(
        self, detector_id: str, detector_version: int, signals: Mapping[Path, list[Signal]]
    ) -> None:
        """`set_signals` for many images in one transaction, as group detectors need."""
        with self._db:
            for path, image_signals in signals.items():
                exists = self._db.execute(
                    "SELECT 1 FROM images WHERE path = ?", (str(path),)
                ).fetchone()
                _require(exists, path)
                self._db.execute(
                    "DELETE FROM analyses WHERE path = ? AND detector_id = ?",
                    (str(path), detector_id),
                )
                self._db.execute(
                    "INSERT INTO analyses VALUES (?, ?, ?)",
                    (str(path), detector_id, detector_version),
                )
                self._db.executemany(
                    "INSERT INTO signals VALUES (?, ?, ?, ?, ?, ?)",
                    [
                        (
                            str(path),
                            s.detector_id,
                            s.detector_version,
                            s.category.value,
                            s.score,
                            json.dumps(s.evidence),
                        )
                        for s in image_signals
                    ],
                )

    def group_fingerprint(self, detector_id: str, detector_version: int) -> str | None:
        row = self._db.execute(
            "SELECT fingerprint FROM group_runs WHERE detector_id = ? AND detector_version = ?",
            (detector_id, detector_version),
        ).fetchone()
        return row[0] if row else None

    def set_group_fingerprint(
        self, detector_id: str, detector_version: int, fingerprint: str
    ) -> None:
        with self._db:
            self._db.execute(
                "INSERT OR REPLACE INTO group_runs VALUES (?, ?, ?)",
                (detector_id, detector_version, fingerprint),
            )

    def is_analyzed(self, path: Path, detector_id: str, detector_version: int) -> bool:
        row = self._db.execute(
            "SELECT 1 FROM analyses WHERE path = ? AND detector_id = ? AND detector_version = ?",
            (str(path), detector_id, detector_version),
        ).fetchone()
        return row is not None

    def signals(self, path: Path) -> list[Signal]:
        rows = self._db.execute(
            """SELECT detector_id, detector_version, category, score, evidence
               FROM signals WHERE path = ?""",
            (str(path),),
        )
        return [
            Signal(detector_id, version, kind(category), score, json.loads(evidence))
            for detector_id, version, category, score, evidence in rows
        ]

    def set_review_state(self, path: Path, state: ReviewState) -> None:
        """Keeping an image also records the signals it has now: only a different one
        brings it back among the candidates (docs/design.md, section 6.3)."""
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET review_state = ? WHERE path = ?", (state.value, str(path))
            )
            _require(updated.rowcount, path)
            self._db.execute("DELETE FROM kept_signals WHERE path = ?", (str(path),))
            if state is ReviewState.KEPT:
                self._db.executemany(
                    "INSERT OR IGNORE INTO kept_signals VALUES (?, ?, ?, ?)",
                    [
                        (str(path), category.value, group, role)
                        for category, group, role in (s.identity for s in self.signals(path))
                    ],
                )

    def review_state(self, path: Path) -> ReviewState:
        row = self._db.execute(
            "SELECT review_state FROM images WHERE path = ?", (str(path),)
        ).fetchone()
        _require(row, path)
        return ReviewState(row[0])

    def exclude_folder(self, path: Path) -> None:
        with self._db:
            self._db.execute("INSERT OR IGNORE INTO excluded_folders VALUES (?)", (str(path),))

    def remove_excluded_folder(self, path: Path) -> None:
        with self._db:
            self._db.execute("DELETE FROM excluded_folders WHERE path = ?", (str(path),))

    def excluded_folders(self) -> list[Path]:
        rows = self._db.execute("SELECT path FROM excluded_folders ORDER BY path")
        return [Path(path) for (path,) in rows]

    def indexed_images(self) -> list[IndexedImage]:
        """Every image with its review state and signals, as the ranker takes them."""
        signals = defaultdict(list)
        for path, *fields, evidence in self._db.execute(
            """SELECT path, detector_id, detector_version, category, score, evidence
               FROM signals ORDER BY rowid"""
        ):
            detector_id, version, category, score = fields
            signals[path].append(
                Signal(detector_id, version, kind(category), score, json.loads(evidence))
            )
        seen = defaultdict(set)
        for path, category, group, role in self._db.execute("SELECT * FROM kept_signals"):
            seen[path].add((kind(category), group, role))
        rows = self._db.execute(
            "SELECT path, size, mtime_ns, review_state FROM images ORDER BY path"
        )
        return [
            IndexedImage(
                ImageRef(Path(path), size, mtime_ns),
                ReviewState(state),
                tuple(signals[path]),
                frozenset(seen[path]),
            )
            for path, size, mtime_ns, state in rows
        ]


def _require(found: object, path: Path) -> None:
    if not found:
        raise KeyError(f"image not in the index: {path}")
