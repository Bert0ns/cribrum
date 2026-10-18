"""Persistent image index, so re-runs only process new or changed files.

Mirrors the planned Room schema (docs/design.md, section 7). Keep the database
under lab/data/: it holds paths and metadata of personal images.
"""

import json
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Self

from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import Category, ImageRef, ReviewState, Signal

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
    captured_at TEXT
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

        A changed image (different size or mtime) loses its metadata and signals, which
        must be computed again. It keeps its review state: the file may only have been
        touched, and a decision the user made must not be lost. A real edit shows up as
        new signals, which the ranker treats as such.
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
                       mime = NULL, camera_make = NULL, camera_model = NULL, captured_at = NULL
                       WHERE path = ?""",
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
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET review_state = ? WHERE path = ?", (state.value, str(path))
            )
        _require(updated.rowcount, path)

    def review_state(self, path: Path) -> ReviewState:
        row = self._db.execute(
            "SELECT review_state FROM images WHERE path = ?", (str(path),)
        ).fetchone()
        _require(row, path)
        return ReviewState(row[0])


def _require(found: object, path: Path) -> None:
    if not found:
        raise KeyError(f"image not in the index: {path}")
