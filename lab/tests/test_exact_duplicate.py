from pathlib import Path

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, AnalysisContext, Role
from cribrum_lab.detectors.exact_duplicate import ExactDuplicateDetector
from cribrum_lab.model import Category, ImageRef


def write(root: Path, name: str, content: bytes) -> ImageRef:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return ImageRef(path, len(content), 0)


def run(images, context=None):
    return ExactDuplicateDetector().analyze(images, context or AnalysisContext())


def test_identical_files_form_a_group_with_one_best_copy(tmp_path):
    camera = write(tmp_path, "DCIM/Camera/a.jpg", b"photo")
    forwarded = write(tmp_path, "WhatsApp/Media/WhatsApp Images/b.jpg", b"photo")
    downloaded = write(tmp_path, "Download/c.jpg", b"photo")

    signals = run([forwarded, downloaded, camera])

    roles = {image: signals[image][0].evidence[ROLE_KEY] for image in signals}
    assert roles == {camera: Role.BEST, forwarded: Role.INFERIOR, downloaded: Role.INFERIOR}
    assert len({s[0].evidence[GROUP_KEY] for s in signals.values()}) == 1
    assert signals[camera][0].score == 0.0
    assert signals[forwarded][0].score == 1.0
    assert all(s[0].category is Category.EXACT_DUPLICATE for s in signals.values())


def test_messaging_copy_loses_to_any_other_folder(tmp_path):
    received = write(tmp_path, "Android/media/com.whatsapp/WhatsApp/Media/a.jpg", b"meme")
    telegram = write(tmp_path, "Telegram/Telegram Images/b.jpg", b"meme")
    saved = write(tmp_path, "Pictures/c.jpg", b"meme")

    signals = run([received, telegram, saved])

    assert signals[saved][0].evidence[ROLE_KEY] == Role.BEST


def test_same_size_with_different_content_is_not_a_duplicate(tmp_path):
    a = write(tmp_path, "a.jpg", b"aaaa")
    b = write(tmp_path, "b.jpg", b"bbbb")

    assert run([a, b]) == {}


def test_unique_sizes_are_never_hashed(tmp_path):
    a = write(tmp_path, "a.jpg", b"a")
    b = write(tmp_path, "b.jpg", b"bb")
    hashed = []

    def content_hash(image):
        hashed.append(image)
        return "same"

    assert run([a, b], AnalysisContext(content_hash=content_hash)) == {}
    assert hashed == []


def test_separate_groups_get_separate_ids(tmp_path):
    images = [
        write(tmp_path, "a1.jpg", b"aaaa"),
        write(tmp_path, "a2.jpg", b"aaaa"),
        write(tmp_path, "b1.jpg", b"bbbb"),
        write(tmp_path, "b2.jpg", b"bbbb"),
    ]

    signals = run(images)

    groups = [signals[image][0].evidence[GROUP_KEY] for image in images]
    assert groups[0] == groups[1] != groups[2] == groups[3]


def test_file_gone_since_the_scan_is_skipped(tmp_path):
    a = write(tmp_path, "a.jpg", b"photo")
    b = write(tmp_path, "b.jpg", b"photo")
    gone = write(tmp_path, "c.jpg", b"photo")
    gone.path.unlink()

    assert set(run([a, b, gone])) == {a, b}
