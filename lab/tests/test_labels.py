from pathlib import Path

import pytest

from cribrum_lab.labels import Decision, Label, LabelStore
from cribrum_lab.model import Category


@pytest.fixture
def store():
    with LabelStore(":memory:") as store:
        yield store


def test_labels_round_trip_in_labeling_order(store):
    keep = Label("aa", Path("/a.jpg"), Decision.KEEP)
    delete = Label("bb", Path("/b.jpg"), Decision.DELETE, Category.SCREENSHOT)

    store.set(keep)
    store.set(delete)

    assert store.all() == [keep, delete]


def test_same_content_keeps_one_label(store):
    store.set(Label("aa", Path("/a.jpg"), Decision.KEEP))
    store.set(Label("aa", Path("/copy/a.jpg"), Decision.DELETE, Category.EXACT_DUPLICATE))

    assert store.all() == [
        Label("aa", Path("/copy/a.jpg"), Decision.DELETE, Category.EXACT_DUPLICATE)
    ]


def test_remove_undoes_a_label(store):
    store.set(Label("aa", Path("/a.jpg"), Decision.KEEP))

    store.remove("aa")

    assert store.all() == []


def test_labels_persist(tmp_path):
    with LabelStore(tmp_path / "labels.sqlite") as store:
        store.set(Label("aa", Path("/a.jpg"), Decision.KEEP))

    with LabelStore(tmp_path / "labels.sqlite") as store:
        assert [label.content_hash for label in store.all()] == ["aa"]
