from datetime import datetime

import pytest
from PIL import Image

from cribrum_lab.metadata import UnreadableImage, read_metadata

MAKE, MODEL, ORIENTATION, DATETIME = 0x010F, 0x0110, 0x0112, 0x0132
EXIF_IFD, DATETIME_ORIGINAL = 0x8769, 0x9003


def save(path, size=(40, 30), exif=None, **params):
    image = Image.new("RGB", size, "gray")
    if exif is not None:
        params["exif"] = exif.tobytes()
    image.save(path, **params)
    return path


def camera_exif(captured="2024:05:01 10:20:30"):
    exif = Image.Exif()
    exif[MAKE] = "Google"
    exif[MODEL] = "Pixel 8"
    exif[ORIENTATION] = 6
    exif.get_ifd(EXIF_IFD)[DATETIME_ORIGINAL] = captured
    return exif


def test_reads_size_and_mime(tmp_path):
    meta = read_metadata(save(tmp_path / "a.png", size=(1080, 2400)))

    assert (meta.width, meta.height, meta.mime) == (1080, 2400, "image/png")


def test_reads_camera_exif(tmp_path):
    meta = read_metadata(save(tmp_path / "a.jpg", exif=camera_exif()))

    assert meta.camera_make == "Google"
    assert meta.camera_model == "Pixel 8"
    assert meta.captured_at == datetime(2024, 5, 1, 10, 20, 30)
    assert meta.has_camera_exif


def test_image_without_exif(tmp_path):
    meta = read_metadata(save(tmp_path / "a.jpg"))

    assert meta.camera_make is None
    assert meta.captured_at is None
    assert not meta.has_camera_exif


@pytest.mark.parametrize("fmt", ["JPEG", "HEIF"])
def test_size_is_as_displayed(tmp_path, fmt):
    meta = read_metadata(save(tmp_path / "a", size=(40, 30), exif=camera_exif(), format=fmt))

    assert (meta.width, meta.height) == (30, 40)  # orientation 6: rotated by 90 degrees


def test_modification_datetime_is_not_a_capture_time(tmp_path):
    exif = Image.Exif()
    exif[DATETIME] = "2024:05:01 10:20:30"  # editors and screenshot tools set this one

    assert read_metadata(save(tmp_path / "a.jpg", exif=exif)).captured_at is None


@pytest.mark.parametrize("value", ["0000:00:00 00:00:00", "garbage", "    :  :     :  :  "])
def test_invalid_capture_time_is_ignored(tmp_path, value):
    meta = read_metadata(save(tmp_path / "a.jpg", exif=camera_exif(captured=value)))

    assert meta.captured_at is None


def test_reads_heic(tmp_path):
    meta = read_metadata(save(tmp_path / "a.heic", exif=camera_exif(), format="HEIF"))

    assert meta.mime == "image/heic"
    assert meta.camera_model == "Pixel 8"


def test_unreadable_file_raises(tmp_path):
    path = tmp_path / "a.jpg"
    path.write_bytes(b"\xff\xd8\xff\xe0 truncated")

    with pytest.raises(UnreadableImage):
        read_metadata(path)


def test_reads_photos_above_pillow_pixel_limit(tmp_path):
    path = tmp_path / "200mp.png"
    Image.new("1", (16320, 12240)).save(path)  # 200 MP sensor; 1-bit keeps the test file small

    assert (read_metadata(path).width, read_metadata(path).height) == (16320, 12240)
