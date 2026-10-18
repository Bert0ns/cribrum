import io
import json
import threading
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from cribrum_lab.hashing import content_hash
from cribrum_lab.labeling import LabelingServer, clusters, sample
from cribrum_lab.labels import Decision, LabelStore
from cribrum_lab.model import Category, ImageRef, IndexedImage, Signal
from cribrum_lab.ranker import Candidate


def candidate(name, category=None):
    image = IndexedImage(ImageRef(Path(name), 0, 0))
    reasons = (Signal("x", 1, category, 1.0),) if category else ()
    return Candidate(image, reasons, 2.0 if category else 0.5)


def names(refs):
    return sorted(ref.path.name for ref in refs)


RANKED = (
    [candidate(f"s{i}", Category.SCREENSHOT) for i in range(20)]
    + [candidate("b0", Category.BLURRY), candidate("b1", Category.BLURRY)]
    + [candidate(f"u{i}") for i in range(20)]
)


def test_sample_is_half_candidates_from_every_category_and_half_unflagged():
    picked = names(sample(RANKED, 10, exclude=set()))

    assert len(picked) == 10
    assert {"b0", "b1"} <= set(picked)  # the small category is not crowded out
    assert sum(name.startswith("u") for name in picked) == 5


def test_sample_is_stable_for_a_seed_and_skips_labeled_images():
    assert sample(RANKED, 10, set(), seed=1) == sample(RANKED, 10, set(), seed=1)

    picked = sample(RANKED, 40, exclude={Path("b0"), Path("u3")})

    assert "b0" not in names(picked)
    assert "u3" not in names(picked)
    assert len(picked) == 40


def test_sample_fills_up_with_candidates_when_few_images_are_unflagged():
    ranked = [candidate(f"s{i}", Category.SCREENSHOT) for i in range(10)] + [candidate("u0")]

    assert len(sample(ranked, 8, set())) == 8


@pytest.fixture
def server(tmp_path):
    paths = []
    for name, size in [("a.png", (3000, 2000)), ("b.jpg", (100, 100))]:
        path = tmp_path / name
        Image.new("RGB", size, "red").save(path)
        paths.append(path)
    (tmp_path / "broken.jpg").write_bytes(b"\xff\xd8\xff not an image")
    paths.append(tmp_path / "broken.jpg")
    store = LabelStore(":memory:")
    refs = [ImageRef(p, p.stat().st_size, 0) for p in paths]
    server = LabelingServer([[refs[0], refs[1]], [refs[2]]], store)
    threading.Thread(target=server.serve_forever, args=(0.05,), daemon=True).start()
    yield server
    server.shutdown()
    server.server_close()
    store.close()


def get(server, path):
    return urllib.request.urlopen(f"http://127.0.0.1:{server.server_port}{path}").read()


def post(server, path, body):
    request = urllib.request.Request(
        f"http://127.0.0.1:{server.server_port}{path}", json.dumps(body).encode(), method="POST"
    )
    return json.loads(urllib.request.urlopen(request).read())


def test_server_listens_on_localhost_only(server):
    assert server.server_address[0] == "127.0.0.1"


def test_page_and_queue(server):
    assert b"Cribrum labeling" in get(server, "/")
    queue = json.loads(get(server, "/api/queue"))

    assert [Path(item["path"]).name for item in queue["items"]] == ["a.png", "b.jpg", "broken.jpg"]
    assert queue["groups"] == [[0, 1], [2]]
    assert queue["categories"]["6"] == "screenshot"


def test_images_are_sent_as_bounded_jpegs(server):
    with Image.open(io.BytesIO(get(server, "/image/0"))) as image:
        assert image.format == "JPEG"
        assert max(image.size) == 1600


def test_thumbnails_for_grids_are_smaller(server):
    with Image.open(io.BytesIO(get(server, "/thumbnail/0"))) as image:
        assert max(image.size) == 400


@pytest.mark.parametrize("path", ["/image/3", "/image/-1", "/image/../etc/passwd", "/nope"])
def test_only_queued_images_are_served(server, path):
    with pytest.raises(urllib.error.HTTPError) as error:
        get(server, path)

    assert error.value.code == 404


def test_undecodable_image_is_an_error_not_a_crash(server):
    with pytest.raises(urllib.error.HTTPError) as error:
        get(server, "/image/2")

    assert error.value.code == 422
    assert json.loads(get(server, "/api/queue"))  # still serving


def test_label_and_undo(server):
    post(server, "/api/label", {"items": [1], "decision": "delete", "category": "blurry"})

    [label] = server.store.all()
    assert label.decision is Decision.DELETE
    assert label.category is Category.BLURRY
    assert label.content_hash == content_hash(server.queue[1])

    post(server, "/api/unlabel", {"items": [1]})

    assert server.store.all() == []


def test_a_whole_group_is_labeled_at_once(server):
    post(server, "/api/label", {"items": [0, 1], "decision": "keep"})

    assert [label.path.name for label in server.store.all()] == ["a.png", "b.jpg"]


@pytest.mark.parametrize(
    "body",
    [{"items": [0], "decision": "maybe"}, {"items": [], "decision": "keep"}, {"items": [0, 9]}],
)
def test_invalid_label_is_rejected(server, body):
    with pytest.raises(urllib.error.HTTPError) as error:
        post(server, "/api/label", {"decision": "keep", **body})

    assert error.value.code == 400
    assert server.store.all() == []


def vector(*values):
    v = np.zeros(8, dtype=np.float32)
    v[: len(values)] = values
    return v / np.linalg.norm(v)


def test_clusters_group_similar_images_largest_first():
    refs = [ImageRef(Path(f"{i}"), 0, 0) for i in range(7)]
    vectors = {
        refs[0].path: vector(1, 0),
        refs[1].path: vector(0, 1),
        refs[2].path: vector(1, 0.1),
        refs[3].path: vector(0.1, 1),
        refs[4].path: vector(1, 0.2),
        refs[5].path: vector(0, 0, 1),  # alike to no other image
    }  # refs[6] has no embedding

    groups = clusters(refs, vectors)

    assert groups == [[refs[0], refs[2], refs[4]], [refs[1], refs[3]]]


def test_large_clusters_are_split_to_fit_a_screen():
    refs = [ImageRef(Path(f"{i}"), 0, 0) for i in range(100)]

    groups = clusters(refs, {ref.path: vector(1) for ref in refs})

    assert [len(g) for g in groups] == [48, 48, 4]
