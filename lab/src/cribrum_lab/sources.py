"""Image sources: where images come from. Mirrors docs/design.md, sections 4 and 7."""

import stat
from collections.abc import Iterator
from pathlib import Path
from typing import Protocol

from cribrum_lab.model import ImageRef

# ISO base media brands of HEIF and AVIF images. Videos (e.g. "isom", "mp42") are left out.
_HEIF_BRANDS = {
    b"heic", b"heix", b"heim", b"heis", b"hevc", b"hevx",
    b"mif1", b"mif2", b"msf1", b"miaf", b"avif", b"avis",
}  # fmt: skip
_HEAD_SIZE = 64
"""Enough for the magic bytes of every format and the brand list of a typical ftyp box."""


class ImageSource(Protocol):
    def scan(self) -> Iterator[ImageRef]: ...


class FilesystemImageSource:
    """Walks a folder tree and yields every image file, like the Android deep scan.

    Hidden folders and folders with a `.nomedia` file are included: that is where
    messaging apps keep media the gallery never shows. Files are recognized by their
    content, not their extension. Symlinks are skipped so no file is yielded twice, and
    so are FIFOs and other special files, which would block or fail on read. Left out on
    purpose: files in the system trash or still being written (`.trashed-*`,
    `.pending-*`), and `Android/.Trash` at the root, which is Samsung Gallery's recycle bin.
    """

    SKIPPED_PREFIXES = (".trashed-", ".pending-")
    SKIPPED_FOLDERS = (Path("Android/.Trash"),)

    def __init__(self, root: Path) -> None:
        self.root = root

    def scan(self) -> Iterator[ImageRef]:
        for folder, dirs, files in self.root.walk():
            dirs[:] = sorted(
                d for d in dirs if (folder / d).relative_to(self.root) not in self.SKIPPED_FOLDERS
            )
            for name in sorted(files):
                if name.startswith(self.SKIPPED_PREFIXES):
                    continue
                path = folder / name
                try:
                    info = path.lstat()
                    if not stat.S_ISREG(info.st_mode) or not _is_image(path):
                        continue
                except OSError:
                    continue  # unreadable or vanished during the scan
                yield ImageRef(path, info.st_size, info.st_mtime_ns)


def _is_image(path: Path) -> bool:
    with path.open("rb") as file:
        head = file.read(_HEAD_SIZE)
    return (
        head.startswith(b"\xff\xd8\xff")  # JPEG
        or head.startswith(b"\x89PNG\r\n\x1a\n")
        or head.startswith((b"GIF87a", b"GIF89a"))
        or (head.startswith(b"RIFF") and head[8:12] == b"WEBP")
        or (head[4:8] == b"ftyp" and not _HEIF_BRANDS.isdisjoint(_ftyp_brands(head)))
    )


def _ftyp_brands(head: bytes) -> list[bytes]:
    """Major brand plus compatible brands: HEIF files may declare their brand in either."""
    box_end = min(int.from_bytes(head[:4]), len(head))
    return [head[8:12]] + [head[i : i + 4] for i in range(16, box_end - 3, 4)]
