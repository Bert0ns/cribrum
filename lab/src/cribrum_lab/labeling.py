"""Keyboard-driven labeling tool: a local web page (docs/design.md, section 10, step 3).

The server listens on 127.0.0.1 only and serves nothing but the images of its queue.
"""

import io
import json
import random
import threading
from collections.abc import Mapping, Sequence
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from itertools import pairwise
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

from cribrum_lab import hashing
from cribrum_lab.labels import Decision, Label, LabelStore
from cribrum_lab.model import Category, ImageRef
from cribrum_lab.ranker import Candidate

PREVIEW_SIDE = 1600
"""Browsers can't show HEIC: every image is sent as a JPEG this size at most."""
THUMBNAIL_SIDE = 400
"""Size of each image in a cluster's grid. Zooming in loads the original at full size."""
CLUSTER_COSINE = 0.8
"""Embedding similarity to a cluster's first image that still joins the cluster."""
MAX_CLUSTER = 48
"""Larger clusters are split: a grid should fit one screen."""
_PAGE = Path(__file__).with_name("labeling.html")


def sample(
    ranked: Sequence[Candidate], size: int, exclude: set[Path], seed: int = 0
) -> list[ImageRef]:
    """Half candidates, spread over their categories, half images no detector flagged.

    Recall can only be measured on images no detector flagged if some get labeled too.
    The same seed gives the same sample, so a session can be resumed.
    """
    rng = random.Random(seed)
    pool = [c for c in ranked if c.image.ref.path not in exclude]
    flagged = [c for c in pool if c.is_candidate]
    unflagged = [c.image.ref for c in pool if not c.is_candidate]
    by_category: dict[Category, list[ImageRef]] = {}
    for candidate in flagged:
        top = candidate.reasons[0].category  # strongest reason
        by_category.setdefault(top, []).append(candidate.image.ref)
    for refs in by_category.values():
        rng.shuffle(refs)
    rng.shuffle(unflagged)

    picked = []
    queues = list(by_category.values())
    while len(picked) < size // 2 and any(queues):
        for queue in queues:  # round robin: small categories get labels too
            if queue and len(picked) < size // 2:
                picked.append(queue.pop())
    picked += unflagged[: size - len(picked)]
    leftover = [ref for queue in queues for ref in queue]
    picked += leftover[: size - len(picked)]  # too few unflagged images: more candidates
    rng.shuffle(picked)
    return picked


def clusters(
    images: Sequence[ImageRef], vectors: Mapping[Path, np.ndarray]
) -> list[list[ImageRef]]:
    """Groups of similar images, largest first, so a whole group can be labeled at once.

    Leader clustering: each image joins the first cluster whose first image is at least
    CLUSTER_COSINE similar, or starts a new one. Images without an embedding and images
    alike to no other are left out: they are labeled one by one.
    """
    leaders: list[np.ndarray] = []
    members: list[list[ImageRef]] = []
    for image in images:
        vector = vectors.get(image.path)
        if vector is None:
            continue
        if leaders:
            similarity = np.stack(leaders) @ vector
            best = int(similarity.argmax())
            if similarity[best] >= CLUSTER_COSINE:
                members[best].append(image)
                continue
        leaders.append(vector)
        members.append([image])
    groups = [
        group[start : start + MAX_CLUSTER]
        for group in members
        for start in range(0, len(group), MAX_CLUSTER)
    ]
    return sorted((g for g in groups if len(g) > 1), key=len, reverse=True)


def preview(path: Path, side: int | None = PREVIEW_SIDE) -> bytes:
    """A JPEG of the image at most `side` pixels wide and high, or at full size if None."""
    with Image.open(path) as image:
        if side:
            image.draft("RGB", (side, side))
        image = ImageOps.exif_transpose(image).convert("RGB")
        if side:
            image.thumbnail((side, side))
        buffer = io.BytesIO()
        image.save(buffer, "JPEG", quality=85 if side else 95)
        return buffer.getvalue()


class LabelingServer(ThreadingHTTPServer):
    """Serves a queue of groups of images: a group of one is shown alone, a larger group
    as a grid labeled with one key."""

    def __init__(
        self, groups: Sequence[Sequence[ImageRef]], store: LabelStore, port: int = 0
    ) -> None:
        super().__init__(("127.0.0.1", port), _Handler)
        self.queue = [image for group in groups for image in group]
        starts = [0]
        for group in groups:
            starts.append(starts[-1] + len(group))
        self.groups = [list(range(a, b)) for a, b in pairwise(starts)]
        self.store = store
        self.lock = threading.Lock()
        self.hashes: dict[int, str] = {}

    def label(self, items: Sequence[int], decision: Decision) -> None:
        with self.lock:
            for item in items:
                image = self.queue[item]
                digest = self.hashes.get(item) or hashing.content_hash(image)
                self.hashes[item] = digest
                self.store.set(Label(digest, image.path, decision))

    def unlabel(self, items: Sequence[int]) -> None:
        with self.lock:
            for item in items:
                if item in self.hashes:
                    self.store.remove(self.hashes[item])


class _Handler(BaseHTTPRequestHandler):
    server: LabelingServer

    def do_GET(self) -> None:
        if self.path == "/":
            self._send(_PAGE.read_bytes(), "text/html; charset=utf-8")
        elif self.path == "/api/queue":
            items = [{"path": str(image.path)} for image in self.server.queue]
            self._json({"items": items, "groups": self.server.groups})
        elif self.path.startswith(("/image/", "/thumbnail/", "/original/")):
            kind, _, number = self.path[1:].partition("/")
            item = self._item(number)
            if item is None:
                return
            side = {"image": PREVIEW_SIDE, "thumbnail": THUMBNAIL_SIDE, "original": None}[kind]
            try:
                self._send(preview(self.server.queue[item].path, side), "image/jpeg")
            except Exception as error:  # an undecodable file must not stop the session
                self.send_error(HTTPStatus.UNPROCESSABLE_ENTITY, str(error))
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or "{}")
        items = [_index(str(item), len(self.server.queue)) for item in body.get("items", [])]
        if not items or None in items:
            self.send_error(HTTPStatus.BAD_REQUEST)
            return
        if self.path == "/api/label":
            try:
                decision = Decision(body["decision"])
            except (KeyError, ValueError):
                self.send_error(HTTPStatus.BAD_REQUEST)
                return
            self.server.label(items, decision)
        elif self.path == "/api/unlabel":
            self.server.unlabel(items)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        self._json({"ok": True})

    def _item(self, text: str) -> int | None:
        item = _index(text, len(self.server.queue))
        if item is None:
            self.send_error(HTTPStatus.NOT_FOUND)
        return item

    def _json(self, value: object) -> None:
        self._send(json.dumps(value).encode(), "application/json")

    def _send(self, content: bytes, content_type: str) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, format: str, *args: object) -> None:
        pass  # one line per image request would bury the terminal


def _index(text: str, size: int) -> int | None:
    return int(text) if text.isdigit() and int(text) < size else None
