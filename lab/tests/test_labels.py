import sqlite3
from pathlib import Path

import pytest

from cribrum_lab.labels import Decision, Label, LabelStore


@pytest.fixture
def store():
    with LabelStore(":memory:") as store:
        yield store


def test_labels_round_trip_in_labeling_order(store):
    keep = Label("aa", Path("/a.jpg"), Decision.KEEP)
    delete = Label("bb", Path("/b.jpg"), Decision.DELETE)

    store.set(keep)
    store.set(delete)

    assert store.all() == [keep, delete]


def test_same_content_keeps_one_label(store):
    store.set(Label("aa", Path("/a.jpg"), Decision.KEEP))
    store.set(Label("aa", Path("/copy/a.jpg"), Decision.DELETE))

    assert store.all() == [Label("aa", Path("/copy/a.jpg"), Decision.DELETE)]


def test_remove_undoes_a_label(store):
    store.set(Label("aa", Path("/a.jpg"), Decision.KEEP))

    store.remove("aa")

    assert store.all() == []


def test_labels_persist(tmp_path):
    with LabelStore(tmp_path / "labels.sqlite") as store:
        store.set(Label("aa", Path("/a.jpg"), Decision.KEEP))

    with LabelStore(tmp_path / "labels.sqlite") as store:
        assert [label.content_hash for label in store.all()] == ["aa"]


def test_labels_from_before_keep_and_delete_only_still_load(tmp_path):
    database = tmp_path / "labels.sqlite"
    with sqlite3.connect(database) as db:
        db.execute(
            "CREATE TABLE labels (content_hash TEXT PRIMARY KEY, path TEXT NOT NULL, "
            "decision TEXT NOT NULL, category TEXT, labeled_at TEXT NOT NULL)"
        )
        db.execute("INSERT INTO labels VALUES ('aa', '/a.jpg', 'delete', 'blurry', '2026-10-19')")
    db.close()

    with LabelStore(database) as store:
        store.set(Label("bb", Path("/b.jpg"), Decision.KEEP))

        assert [label.decision for label in store.all()] == [Decision.DELETE, Decision.KEEP]
