import os
from pathlib import Path

import pytest

from cribrum_lab.sources import FilesystemImageSource

JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 16
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16
GIF = b"GIF89a" + b"\x00" * 16
WEBP = b"RIFF\x00\x00\x00\x00WEBPVP8 " + b"\x00" * 8
HEIC = b"\x00\x00\x00\x18ftypheic" + b"\x00" * 12
# MIAF profile: the HEIF brand appears only among the compatible brands
MIAF_HEIC = b"\x00\x00\x00\x1cftypmiaf\x00\x00\x00\x00mif1heic" + b"\x00" * 8
AVIF = b"\x00\x00\x00\x1cftypavif\x00\x00\x00\x00mif1miaf" + b"\x00" * 8
MP4 = b"\x00\x00\x00\x18ftypisom" + b"\x00" * 12


def write(path: Path, content: bytes) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


def scanned_paths(root: Path) -> set[Path]:
    return {ref.path.relative_to(root) for ref in FilesystemImageSource(root).scan()}


def test_finds_every_supported_format(tmp_path):
    for name, content in [("a.jpg", JPEG), ("b.png", PNG), ("c.gif", GIF), ("d.webp", WEBP)]:
        write(tmp_path / name, content)
    write(tmp_path / "e.heic", HEIC)

    assert scanned_paths(tmp_path) == {
        Path(n) for n in ["a.jpg", "b.png", "c.gif", "d.webp", "e.heic"]
    }


@pytest.mark.parametrize("content", [MIAF_HEIC, AVIF], ids=["MIAF HEIC", "AVIF"])
def test_finds_heif_declared_by_compatible_brand(tmp_path, content):
    write(tmp_path / "a.heic", content)

    assert scanned_paths(tmp_path) == {Path("a.heic")}


def test_includes_nomedia_and_hidden_folders(tmp_path):
    write(tmp_path / "WhatsApp Images/Sent/.nomedia", b"")
    write(tmp_path / "WhatsApp Images/Sent/IMG-1.jpg", JPEG)
    write(tmp_path / ".thumbnails/t.jpg", JPEG)

    assert scanned_paths(tmp_path) == {
        Path("WhatsApp Images/Sent/IMG-1.jpg"),
        Path(".thumbnails/t.jpg"),
    }


def test_detects_images_by_content_not_extension(tmp_path):
    write(tmp_path / "no_extension", JPEG)
    write(tmp_path / "photo.jpg", PNG)
    write(tmp_path / "fake.jpg", b"not an image")
    write(tmp_path / "clip.mp4", MP4)
    write(tmp_path / "empty.jpg", b"")

    assert scanned_paths(tmp_path) == {Path("no_extension"), Path("photo.jpg")}


def test_skips_samsung_gallery_recycle_bin_at_the_root(tmp_path):
    write(tmp_path / "Android/.Trash/com.sec.android.gallery3d/a.jpg", JPEG)
    write(tmp_path / "Android/media/com.whatsapp/b.jpg", JPEG)
    write(tmp_path / "DCIM/Android/.Trash/c.jpg", JPEG)

    assert scanned_paths(tmp_path) == {
        Path("Android/media/com.whatsapp/b.jpg"),
        Path("DCIM/Android/.Trash/c.jpg"),
    }


def test_image_ref_identifies_the_file(tmp_path):
    path = write(tmp_path / "a.jpg", JPEG)

    [ref] = FilesystemImageSource(tmp_path).scan()

    stat = path.stat()
    assert (ref.path, ref.size, ref.mtime_ns) == (path, stat.st_size, stat.st_mtime_ns)


def test_skips_symlinks(tmp_path):
    target = write(tmp_path / "a.jpg", JPEG)
    (tmp_path / "link.jpg").symlink_to(target)

    assert scanned_paths(tmp_path) == {Path("a.jpg")}


def test_skips_special_and_unreadable_files(tmp_path):
    os.mkfifo(tmp_path / "pipe.jpg")  # opening a FIFO for reading would block forever
    write(tmp_path / "locked.jpg", JPEG).chmod(0)
    write(tmp_path / "ok.jpg", JPEG)

    assert scanned_paths(tmp_path) == {Path("ok.jpg")}


def test_scan_order_is_deterministic(tmp_path):
    for name in ["b/2.jpg", "b/1.jpg", "a/9.jpg", "c.jpg"]:
        write(tmp_path / name, JPEG)

    paths = [ref.path.relative_to(tmp_path) for ref in FilesystemImageSource(tmp_path).scan()]

    assert paths == [Path(n) for n in ["c.jpg", "a/9.jpg", "b/1.jpg", "b/2.jpg"]]


def test_skips_system_trash_and_pending_files(tmp_path):
    write(tmp_path / "IMG.jpg", JPEG)
    write(tmp_path / ".trashed-1790000000-IMG_1.jpg", JPEG)
    write(tmp_path / ".pending-1790000000-IMG_2.jpg", JPEG)

    assert scanned_paths(tmp_path) == {Path("IMG.jpg")}
