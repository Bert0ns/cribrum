import os
from pathlib import Path

import pytest

from cribrum_lab.model import ImageRef
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


def test_skips_prefixed_names_in_nested_folders_and_walks_a_prefixed_folder(tmp_path):
    write(tmp_path / "a/b/.trashed-1790000000-IMG_1.jpg", JPEG)
    write(tmp_path / "a/b/.pending-1790000000-IMG_2.jpg", JPEG)
    write(tmp_path / "a/b/IMG_3.jpg", JPEG)
    write(tmp_path / "a/.trashed-folder/IMG_4.jpg", JPEG)  # only file names carry the prefixes

    assert scanned_paths(tmp_path) == {Path("a/b/IMG_3.jpg"), Path("a/.trashed-folder/IMG_4.jpg")}


def test_skips_everything_below_the_recycle_bin(tmp_path):
    write(tmp_path / "Android/.Trash/Android/.Trash/a.jpg", JPEG)
    write(tmp_path / "Android/.Trash/com.sec.android.gallery3d/b.jpg", JPEG)
    write(tmp_path / "Android/c.jpg", JPEG)

    assert scanned_paths(tmp_path) == {Path("Android/c.jpg")}


def ref_of(path: Path) -> ImageRef:
    info = path.stat()
    return ImageRef(path, info.st_size, info.st_mtime_ns)


def test_takes_a_file_the_index_has_with_the_same_size_and_mtime_as_an_image(tmp_path):
    fake = write(tmp_path / "fake.jpg", b"not an image")  # the head would say no

    found = {ref.path for ref in FilesystemImageSource(tmp_path, lambda: [ref_of(fake)]).scan()}

    assert found == {fake}


def test_sniffs_a_known_file_again_when_its_size_or_mtime_changed(tmp_path):
    sized = write(tmp_path / "sized.jpg", b"not an image")
    touched = write(tmp_path / "touched.jpg", b"not an image")
    edited = write(tmp_path / "edited.jpg", JPEG)
    before = [ref_of(p) for p in (sized, touched, edited)]

    sized.write_bytes(b"not an image!")
    os.utime(touched, ns=(1, touched.stat().st_mtime_ns + 5_000_000_000))
    edited.write_bytes(JPEG + b"\x00")

    found = {ref.path for ref in FilesystemImageSource(tmp_path, lambda: before).scan()}

    assert found == {edited}


def test_reads_the_known_files_once_per_scan_not_once_per_file(tmp_path):
    for name in ["a.jpg", "b.jpg", "c.jpg"]:
        write(tmp_path / name, JPEG)
    reads = []
    source = FilesystemImageSource(tmp_path, lambda: reads.append(1) or [])

    list(source.scan())

    assert len(reads) == 1


class MemoryNotImages:
    def __init__(self, *initial: ImageRef) -> None:
        self.stored = {ref.path: ref for ref in initial}
        self.reads = 0
        self.batches: list[int] = []

    def not_images(self) -> list[ImageRef]:
        self.reads += 1
        return list(self.stored.values())

    def add_not_images(self, files: list[ImageRef]) -> None:
        self.batches.append(len(files))
        self.stored |= {ref.path: ref for ref in files}

    def forget_not_images(self, paths: list[Path]) -> None:
        for path in paths:
            self.stored.pop(path, None)


def scanned_remembering(store: MemoryNotImages, root: Path) -> set[Path]:
    return {ref.path for ref in FilesystemImageSource(root, not_images=store).scan()}


def rewrite_quietly(path: Path, content: bytes) -> None:
    """Overwrites the file with content of the same length and puts its mtime back."""
    before = path.stat().st_mtime_ns
    path.write_bytes(content)
    os.utime(path, ns=(before, before))


def test_remembers_a_file_that_is_not_an_image_and_does_not_sniff_it_on_the_next_scan(tmp_path):
    store = MemoryNotImages()
    text = write(tmp_path / "notes.txt", b"x" * len(JPEG))
    a = write(tmp_path / "a.jpg", JPEG)

    assert scanned_remembering(store, tmp_path) == {a}
    assert list(store.stored.values()) == [ref_of(text)]

    # The head would now say image, so a found file here proves the head was not read.
    rewrite_quietly(text, JPEG)
    assert scanned_remembering(store, tmp_path) == {a}


def test_sniffs_a_remembered_file_again_when_its_size_or_mtime_changed(tmp_path):
    store = MemoryNotImages()
    sized = write(tmp_path / "sized.txt", b"not an image")
    touched = write(tmp_path / "touched.txt", b"not an image")
    scanned_remembering(store, tmp_path)

    sized.write_bytes(JPEG)
    mtime = touched.stat().st_mtime_ns
    touched.write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 8)  # as long as before
    os.utime(touched, ns=(mtime, mtime + 5_000_000_000))

    assert scanned_remembering(store, tmp_path) == {sized, touched}
    assert store.stored == {}  # they are images now


def test_a_file_that_is_not_an_image_after_a_change_is_remembered_with_its_new_size(tmp_path):
    store = MemoryNotImages()
    text = write(tmp_path / "notes.txt", b"not an image")
    scanned_remembering(store, tmp_path)

    text.write_bytes(b"not an image!")
    scanned_remembering(store, tmp_path)

    assert list(store.stored.values()) == [ref_of(text)]


def test_forgets_a_remembered_file_the_walk_no_longer_finds(tmp_path):
    store = MemoryNotImages()
    keep = write(tmp_path / "keep.txt", b"not an image")
    gone = write(tmp_path / "gone.txt", b"not an image")
    scanned_remembering(store, tmp_path)

    gone.unlink()
    scanned_remembering(store, tmp_path)

    assert list(store.stored) == [keep]


def test_leaves_the_remembered_files_of_another_root_alone(tmp_path):
    elsewhere = ImageRef(Path("/card/notes.txt"), 5, 5)
    store = MemoryNotImages(elsewhere)
    write(tmp_path / "a/notes.txt", b"not an image")

    scanned_remembering(store, tmp_path / "a")

    assert store.stored[elsewhere.path] == elsewhere


def test_keeps_the_remembered_files_of_a_root_it_could_not_list_in_full(tmp_path):
    notes = write(tmp_path / "a/notes.txt", b"not an image")
    deep = write(tmp_path / "a/b/more.txt", b"not an image")
    other = write(tmp_path / "c.txt", b"not an image")
    store = MemoryNotImages()
    scanned_remembering(store, tmp_path)
    locked = tmp_path / "a"
    locked.chmod(0)
    try:
        if os.access(locked, os.R_OK):
            pytest.skip("the folder is still listable (running as root?)")

        scanned_remembering(store, tmp_path)
    finally:
        locked.chmod(0o755)

    assert set(store.stored) == {notes, deep, other}


def test_reads_the_remembered_files_once_per_scan_not_once_per_file(tmp_path):
    for name in ["a.txt", "b.txt", "c.txt"]:
        write(tmp_path / name, b"not an image")
    store = MemoryNotImages()

    scanned_remembering(store, tmp_path)

    assert store.reads == 1


def test_writes_the_sniffed_non_images_in_batches_of_300_and_the_leftover_at_the_end(tmp_path):
    store = MemoryNotImages()
    for i in range(301):
        write(tmp_path / f"n{i}.txt", b"not an image")

    scanned_remembering(store, tmp_path)

    assert store.batches == [300, 1]
    assert len(store.stored) == 301


def test_yields_a_file_that_is_both_indexed_and_remembered_and_drops_its_row(tmp_path):
    fake = write(tmp_path / "fake.jpg", b"not an image")
    ref = ref_of(fake)
    store = MemoryNotImages(ref)

    found = list(FilesystemImageSource(tmp_path, lambda: [ref], not_images=store).scan())

    assert found == [ref]
    assert store.stored == {}


def test_reports_its_root_as_walked_once_the_whole_tree_is_listed(tmp_path):
    write(tmp_path / "a/b.jpg", JPEG)
    source = FilesystemImageSource(tmp_path)

    list(source.scan())

    assert source.walked_roots == [tmp_path]


def test_reports_no_walked_root_when_a_folder_cannot_be_listed(tmp_path):
    write(tmp_path / "a/b.jpg", JPEG)
    write(tmp_path / "c.jpg", JPEG)
    locked = tmp_path / "a"
    locked.chmod(0)
    try:
        if os.access(locked, os.R_OK):
            pytest.skip("the folder is still listable (running as root?)")
        source = FilesystemImageSource(tmp_path)

        found = [ref.path for ref in source.scan()]
    finally:
        locked.chmod(0o755)

    assert found == [tmp_path / "c.jpg"]
    assert source.walked_roots == []


def test_reports_no_walked_root_when_the_root_is_missing(tmp_path):
    source = FilesystemImageSource(tmp_path / "gone")

    list(source.scan())

    assert source.walked_roots == []
