"""Image sources: where images come from. Mirrors docs/design.md, sections 4 and 7."""

import stat
from collections.abc import Callable, Iterable, Iterator
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

    @property
    def walked_roots(self) -> list[Path]:
        """The roots the last `scan` listed in full.

        An image under one of them that `scan` did not yield is gone; under any other root the
        scan could not look, so it says nothing.
        """
        ...


class NotImages(Protocol):
    """What the walk remembers of the files it sniffed and found not to be images, so it does
    not read their head again while their size and mtime stay the same."""

    def not_images(self) -> Iterable[ImageRef]: ...

    def add_not_images(self, files: list[ImageRef]) -> None:
        """Stores the files; one already stored with the same path is replaced."""
        ...

    def forget_not_images(self, paths: list[Path]) -> None: ...


NOT_IMAGES_BATCH = 300
"""Files the walk hands to `add_not_images` at once."""


class FilesystemImageSource:
    """Walks a folder tree and yields every image file, like the Android deep scan.

    Hidden folders and folders with a `.nomedia` file are included: that is where
    messaging apps keep media the gallery never shows. Files are recognized by their
    content, not their extension, except a file that `indexed` returns with the same path,
    size and mtime: the index took it as an image, so its head is not read again. `indexed`
    is called once per `scan`, before the walk. The same goes for a file `not_images` has: an
    earlier walk found it not to be an image. The walk adds the non-images it sniffs, and drops
    the ones it no longer finds when it listed the whole root. Symlinks are skipped so no file
    is yielded twice, and so are FIFOs and other special files, which would block or fail on
    read. Left out on purpose: files in the system trash or still being written (`.trashed-*`,
    `.pending-*`), and `Android/.Trash` at the root, which is Samsung Gallery's recycle bin.
    """

    SKIPPED_PREFIXES = (".trashed-", ".pending-")
    SKIPPED_FOLDERS = (Path("Android/.Trash"),)

    def __init__(
        self,
        root: Path,
        indexed: Callable[[], Iterable[ImageRef]] = list,
        *,
        not_images: NotImages | None = None,
    ) -> None:
        self.root = root
        self._indexed = indexed
        self._not_images = not_images
        self.walked_roots: list[Path] = []
        """The root once the walk listed every folder; a folder that cannot be listed, or a
        missing root, leaves it out."""

    def scan(self) -> Iterator[ImageRef]:
        self.walked_roots = []
        listed_all = True
        known = set(self._indexed())
        remembered = list(self._not_images.not_images()) if self._not_images is not None else []
        remembered_set = set(remembered)
        met: set[Path] = set()  # non-images this walk met, remembered or newly sniffed
        sniffed: list[ImageRef] = []

        def on_error(_: OSError) -> None:
            nonlocal listed_all
            listed_all = False

        for folder, dirs, files in self.root.walk(on_error=on_error):
            dirs[:] = sorted(
                d for d in dirs if (folder / d).relative_to(self.root) not in self.SKIPPED_FOLDERS
            )
            for name in sorted(files):
                if name.startswith(self.SKIPPED_PREFIXES):
                    continue
                path = folder / name
                try:
                    info = path.lstat()
                    if not stat.S_ISREG(info.st_mode):
                        continue
                    image = ImageRef(path, info.st_size, info.st_mtime_ns)
                    if image not in known:
                        again = image in remembered_set
                        if again or not _is_image(path):
                            met.add(path)
                            if self._not_images is not None and not again:
                                sniffed.append(image)
                                if len(sniffed) == NOT_IMAGES_BATCH:
                                    self._not_images.add_not_images(sniffed)
                                    sniffed = []  # the store may still hold the list it was given
                            continue
                except OSError:
                    continue  # unreadable or vanished during the scan
                yield image
        if self._not_images is not None:
            if sniffed:
                self._not_images.add_not_images(sniffed)
            if listed_all:  # only a root listed in full can tell a gone file from an unseen one
                self._not_images.forget_not_images(
                    [
                        r.path
                        for r in remembered
                        if r.path not in met and r.path.is_relative_to(self.root)
                    ]
                )
        if listed_all:
            self.walked_roots = [self.root]


def _is_image(path: Path) -> bool:
    # The walk remembers the files this refused, by size and mtime (docs/design.md, section 4).
    # A change that lets the head accept more, such as a new format or HEIF brand, must empty
    # `not_images` in the same change: bump SCHEMA_VERSION in index.py, which refuses older
    # files. Otherwise those files are never sniffed again.
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
