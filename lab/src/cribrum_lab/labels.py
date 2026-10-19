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

_SCHEMA = """
CREATE TABLE IF NOT EXISTS labels (
    content_hash TEXT PRIMARY KEY,
    path TEXT NOT NULL,
    decision TEXT NOT NULL,
    labeled_at TEXT NOT NULL
);
"""
# Databases from before labels were only keep or delete also have a category column, which
# is left empty: naming columns in every query works with both.


class Decision(Enum):
    KEEP = "keep"
    DELETE = "delete"


@dataclass(frozen=True)
class Label:
    content_hash: str
    path: Path
    decision: Decision


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
                "INSERT OR REPLACE INTO labels (content_hash, path, decision, labeled_at) "
                "VALUES (?, ?, ?, ?)",
                (
                    label.content_hash,
                    str(label.path),
                    label.decision.value,
                    datetime.now(UTC).isoformat(),
                ),
            )

    def remove(self, content_hash: str) -> None:
        with self._db:
            self._db.execute("DELETE FROM labels WHERE content_hash = ?", (content_hash,))

    def all(self) -> list[Label]:
        rows = self._db.execute(
            "SELECT content_hash, path, decision FROM labels ORDER BY labeled_at"
        )
        return [Label(digest, Path(path), Decision(decision)) for digest, path, decision in rows]
