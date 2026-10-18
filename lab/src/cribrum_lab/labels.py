"""Ground-truth labels for measuring recall (docs/design.md, section 10).

Labels are keyed by content hash, so they survive a new copy of the device's images at
other paths. Keep the database under lab/data/: it holds paths of personal images.
"""

import sqlite3
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from typing import Self

from cribrum_lab.model import Category

_SCHEMA = """
CREATE TABLE IF NOT EXISTS labels (
    content_hash TEXT PRIMARY KEY,
    path TEXT NOT NULL,
    decision TEXT NOT NULL,
    category TEXT,
    labeled_at TEXT NOT NULL
);
"""


class Decision(Enum):
    KEEP = "keep"
    DELETE = "delete"


@dataclass(frozen=True)
class Label:
    content_hash: str
    path: Path
    decision: Decision
    category: Category | None = None
    """Why the image is deletable, when the labeler said so."""


class LabelStore:
    def __init__(self, database: Path | str) -> None:
        self._db = sqlite3.connect(database, check_same_thread=False)
        self._db.executescript(_SCHEMA)

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        self._db.close()

    def set(self, label: Label) -> None:
        """Adds a label, or replaces the one the same content already had."""
        with self._db:
            self._db.execute(
                "INSERT OR REPLACE INTO labels VALUES (?, ?, ?, ?, ?)",
                (
                    label.content_hash,
                    str(label.path),
                    label.decision.value,
                    label.category.value if label.category else None,
                    datetime.now(UTC).isoformat(),
                ),
            )

    def remove(self, content_hash: str) -> None:
        with self._db:
            self._db.execute("DELETE FROM labels WHERE content_hash = ?", (content_hash,))

    def all(self) -> list[Label]:
        rows = self._db.execute(
            "SELECT content_hash, path, decision, category FROM labels ORDER BY labeled_at"
        )
        return [
            Label(digest, Path(path), Decision(decision), Category(category) if category else None)
            for digest, path, decision, category in rows
        ]
