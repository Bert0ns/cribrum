"""Downloads model files once into lab/data/models/, checked against a pinned SHA-256."""

import hashlib
import os
import urllib.request
from pathlib import Path

MODELS = Path(__file__).resolve().parents[2] / "data" / "models"


def fetch(url: str, sha256: str, path: Path) -> Path:
    """`path`, downloaded from `url` first if missing. Raises if the checksum differs."""
    if path.exists():
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    # One partial file per process: pipeline workers may all fetch a model at once.
    partial = path.with_suffix(f"{path.suffix}.{os.getpid()}.part")
    urllib.request.urlretrieve(url, partial)
    digest = hashlib.sha256(partial.read_bytes()).hexdigest()
    if digest != sha256:
        partial.unlink()
        raise RuntimeError(f"{path.name}: SHA-256 {digest}, expected {sha256}")
    partial.replace(path)
    return path
