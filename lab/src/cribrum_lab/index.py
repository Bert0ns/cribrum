"""Persistent image index, so re-runs only process new or changed files.

Mirrors the planned Room schema (docs/design.md, section 7). Keep the database
under lab/data/: it holds paths and metadata of personal images.
"""

import json
import sqlite3
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Self

from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import Category, ImageRef, IndexedImage, ReviewState, Signal

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
    content_hash TEXT
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
"""


class ImageIndex:
    """Methods taking a path raise KeyError for an image that was never recorded."""

    def __init__(self, database: Path | str) -> None:
        self._db = sqlite3.connect(database)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.executescript(_SCHEMA)

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        self._db.close()

    def record(self, image: ImageRef) -> bool:
        """Adds or updates an image. Returns True if it is new or changed.

        A changed image (different size or mtime) loses its metadata, content hash and
        signals, which must be computed again. It keeps its review state: the file may
        only have been touched, and a decision the user made must not be lost. A real
        edit shows up as new signals, which the ranker treats as such.
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
                       content_hash = NULL WHERE path = ?""",
                    (image.size, image.mtime_ns, path),
                )
                self._db.execute("DELETE FROM analyses WHERE path = ?", (path,))
        return True

    def images(self) -> list[ImageRef]:
        rows = self._db.execute("SELECT path, size, mtime_ns FROM images ORDER BY path")
        return [ImageRef(Path(path), size, mtime_ns) for path, size, mtime_ns in rows]

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

    def set_signals(
        self, path: Path, detector_id: str, detector_version: int, signals: list[Signal]
    ) -> None:
        """Replaces whatever this detector stored for the image, from any version."""
        exists = self._db.execute("SELECT 1 FROM images WHERE path = ?", (str(path),)).fetchone()
        _require(exists, path)
        with self._db:
            self._db.execute(
                "DELETE FROM analyses WHERE path = ? AND detector_id = ?", (str(path), detector_id)
            )
            self._db.execute(
                "INSERT INTO analyses VALUES (?, ?, ?)", (str(path), detector_id, detector_version)
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
                    for s in signals
                ],
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
            Signal(detector_id, version, Category(category), score, json.loads(evidence))
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

    def indexed_images(self) -> list[IndexedImage]:
        """Every image with its review state and signals, as the ranker takes them."""
        signals = defaultdict(list)
        for path, *fields, evidence in self._db.execute(
            """SELECT path, detector_id, detector_version, category, score, evidence
               FROM signals ORDER BY rowid"""
        ):
            detector_id, version, category, score = fields
            signals[path].append(
                Signal(detector_id, version, Category(category), score, json.loads(evidence))
            )
        seen = defaultdict(set)
        for path, category, group, role in self._db.execute("SELECT * FROM kept_signals"):
            seen[path].add((Category(category), group, role))
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
