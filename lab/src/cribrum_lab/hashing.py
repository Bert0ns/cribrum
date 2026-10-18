"""File hashes used to find duplicates (docs/design.md, section 5)."""

import xxhash

from cribrum_lab.model import ImageRef

_CHUNK = 1 << 20


def content_hash(image: ImageRef) -> str:
    """xxHash3 64-bit digest of the whole file, as hex. Raises OSError if it can't be read."""
    digest = xxhash.xxh3_64()
    with image.path.open("rb") as file:
        while chunk := file.read(_CHUNK):
            digest.update(chunk)
    return digest.hexdigest()
