"""Hashes used to find duplicates (docs/design.md, section 5)."""

import numpy as np
import xxhash
from PIL import Image

from cribrum_lab.imaging import load_grayscale
from cribrum_lab.model import ImageRef

_CHUNK = 1 << 20


def content_hash(image: ImageRef) -> str:
    """xxHash3 64-bit digest of the whole file, as hex. Raises OSError if it can't be read."""
    digest = xxhash.xxh3_64()
    with image.path.open("rb") as file:
        while chunk := file.read(_CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


def perceptual_hash(image: ImageRef) -> int:
    """64-bit pHash: the signs of the 8x8 lowest frequencies of a 32x32 DCT against their median.

    Resizing and recompression barely change it: near-duplicates differ in a few bits.
    `_PHASH_DECODE_SIDE` and the resampling are part of the hash: changing them means
    bumping the version of every detector that uses it.
    """
    gray = Image.fromarray(load_grayscale(image.path, _PHASH_DECODE_SIDE))
    small = np.asarray(gray.resize((_PHASH_SIZE, _PHASH_SIZE), Image.Resampling.LANCZOS))
    # Rounded, so a flat image's AC terms are exactly 0 instead of platform-dependent noise.
    low = np.round(_DCT @ small.astype(np.float64) @ _DCT.T, 6)[:8, :8]
    bits = (low > np.median(low)).flatten()
    return int.from_bytes(np.packbits(bits).tobytes())


def is_flat_hash(phash: int) -> bool:
    """True for a hash that is 0 or has only the DC bit set: it has no AC energy to tell it apart.

    A uniform image gives one of them, since every AC term rounds to 0: black hashes to 0 and
    any other flat image to the DC bit, all within a bit of each other. A few simple shapes
    give the same hash and are treated the same way.
    """
    return phash in (0, 1 << 63)


_PHASH_SIZE = 32
_PHASH_DECODE_SIDE = 256
"""Decode at this size before shrinking to 32 px: cheap, and enough for a smooth downscale."""


def _dct_matrix(n: int) -> np.ndarray:
    """Orthonormal DCT-II matrix: `M @ x` is the DCT of the column vector x."""
    k = np.arange(n)[:, None]
    m = np.cos(np.pi * (2 * np.arange(n)[None, :] + 1) * k / (2 * n)) * np.sqrt(2 / n)
    m[0] /= np.sqrt(2)
    return m


_DCT = _dct_matrix(_PHASH_SIZE)
