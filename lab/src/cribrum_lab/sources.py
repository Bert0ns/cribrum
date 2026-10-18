"""Image sources: where images come from. Mirrors docs/design.md, sections 4 and 7."""

from collections.abc import Iterator
from pathlib import Path
from typing import Protocol

from cribrum_lab.model import ImageRef

# ISO base media brands of HEIF and AVIF still images. Videos (e.g. "isom", "mp42") are left out.
_HEIF_BRANDS = {b"heic", b"heix", b"heim", b"heis", b"hevc", b"hevx", b"mif1", b"msf1", b"avif"}


class ImageSource(Protocol):
    def scan(self) -> Iterator[ImageRef]: ...


class FilesystemImageSource:
    """Walks a folder tree and yields every image file, like the Android deep scan.

    Hidden folders and folders with a `.nomedia` file are included: that is where
    messaging apps keep media the gallery never shows. Files are recognized by their
    content, not their extension. Symlinks are skipped so no file is yielded twice.
    """

    def __init__(self, root: Path) -> None:
        self.root = root

    def scan(self) -> Iterator[ImageRef]:
        for folder, dirs, files in self.root.walk():
            dirs.sort()
            for name in sorted(files):
                path = folder / name
                if path.is_symlink():
                    continue
                try:
                    if not _is_image(path):
                        continue
                    stat = path.stat()
                except OSError:
                    continue  # unreadable or vanished during the scan
                yield ImageRef(path, stat.st_size, stat.st_mtime_ns)


def _is_image(path: Path) -> bool:
    with path.open("rb") as file:
        head = file.read(12)
    return (
        head.startswith(b"\xff\xd8\xff")  # JPEG
        or head.startswith(b"\x89PNG\r\n\x1a\n")
        or head.startswith((b"GIF87a", b"GIF89a"))
        or (head.startswith(b"RIFF") and head[8:12] == b"WEBP")
        or (head[4:8] == b"ftyp" and head[8:12] in _HEIF_BRANDS)
    )
