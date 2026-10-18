"""Keyboard-driven labeling tool: a local web page (docs/design.md, section 10, step 3).

The server listens on 127.0.0.1 only and serves nothing but the images of its queue.
"""

import io
import json
import random
import threading
from collections.abc import Sequence
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PIL import Image, ImageOps

from cribrum_lab import hashing
from cribrum_lab.labels import Decision, Label, LabelStore
from cribrum_lab.model import Category, ImageRef
from cribrum_lab.ranker import Candidate

CATEGORY_KEYS = {str(i): category for i, category in enumerate(Category, 1)}
"""Digit keys that label an image as deletable for a given reason."""
PREVIEW_SIDE = 1600
"""Browsers can't show HEIC: every image is sent as a JPEG this size at most."""
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


def preview(path: Path) -> bytes:
    with Image.open(path) as image:
        image.draft("RGB", (PREVIEW_SIDE, PREVIEW_SIDE))
        image = ImageOps.exif_transpose(image).convert("RGB")
        image.thumbnail((PREVIEW_SIDE, PREVIEW_SIDE))
        buffer = io.BytesIO()
        image.save(buffer, "JPEG", quality=85)
        return buffer.getvalue()


class LabelingServer(ThreadingHTTPServer):
    def __init__(self, queue: Sequence[ImageRef], store: LabelStore, port: int = 0) -> None:
        super().__init__(("127.0.0.1", port), _Handler)
        self.queue = list(queue)
        self.store = store
        self.lock = threading.Lock()
        self.hashes: dict[int, str] = {}

    def label(self, item: int, decision: Decision, category: Category | None) -> None:
        image = self.queue[item]
        with self.lock:
            digest = self.hashes.get(item) or hashing.content_hash(image)
            self.hashes[item] = digest
            self.store.set(Label(digest, image.path, decision, category))

    def unlabel(self, item: int) -> None:
        with self.lock:
            if item in self.hashes:
                self.store.remove(self.hashes[item])


class _Handler(BaseHTTPRequestHandler):
    server: LabelingServer

    def do_GET(self) -> None:
        if self.path == "/":
            self._send(_PAGE.read_bytes(), "text/html; charset=utf-8")
        elif self.path == "/api/queue":
            items = [{"path": str(image.path)} for image in self.server.queue]
            keys = {key: category.value for key, category in CATEGORY_KEYS.items()}
            self._json({"items": items, "categories": keys})
        elif self.path.startswith("/image/"):
            item = self._item(self.path.removeprefix("/image/"))
            if item is None:
                return
            try:
                self._send(preview(self.server.queue[item].path), "image/jpeg")
            except Exception as error:  # an undecodable file must not stop the session
                self.send_error(HTTPStatus.UNPROCESSABLE_ENTITY, str(error))
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or "{}")
        item = self._item(str(body.get("item")))
        if item is None:
            return
        if self.path == "/api/label":
            try:
                decision = Decision(body["decision"])
                category = Category(body["category"]) if body.get("category") else None
            except (KeyError, ValueError):
                self.send_error(HTTPStatus.BAD_REQUEST)
                return
            self.server.label(item, decision, category)
        elif self.path == "/api/unlabel":
            self.server.unlabel(item)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        self._json({"ok": True})

    def _item(self, text: str) -> int | None:
        if text.isdigit() and int(text) < len(self.server.queue):
            return int(text)
        self.send_error(HTTPStatus.NOT_FOUND)
        return None

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
