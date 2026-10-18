"""Image metadata used by detectors and best-copy selection (docs/design.md, section 7)."""

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from PIL import ExifTags, Image

_EXIF_DATETIME_FORMAT = "%Y:%m:%d %H:%M:%S"
_ROTATED_ORIENTATIONS = {5, 6, 7, 8}  # EXIF orientations that turn the image by 90 degrees


class UnreadableImage(Exception):
    """The file looks like an image but its header can't be decoded."""


@dataclass(frozen=True)
class ImageMetadata:
    """`width` and `height` are as displayed, with the EXIF orientation already applied."""

    width: int
    height: int
    mime: str | None
    camera_make: str | None = None
    camera_model: str | None = None
    captured_at: datetime | None = None
    """EXIF capture time, in the camera's local time. Used for bursts, never as an age signal."""

    @property
    def has_camera_exif(self) -> bool:
        """Screenshots and forwarded media usually have no camera EXIF."""
        return self.camera_make is not None or self.camera_model is not None


def read_metadata(path: Path) -> ImageMetadata:
    """Reads the header only: the pixels are not decoded."""
    try:
        with Image.open(path) as image:
            exif, details = _exif(image)
            # pillow-heif already applies the orientation and resets the tag to 1.
            rotated = exif.get(ExifTags.Base.Orientation) in _ROTATED_ORIENTATIONS
            width, height = (image.height, image.width) if rotated else image.size
            return ImageMetadata(
                width=width,
                height=height,
                mime=image.get_format_mimetype(),
                camera_make=_text(exif.get(ExifTags.Base.Make)),
                camera_model=_text(exif.get(ExifTags.Base.Model)),
                captured_at=_datetime(
                    details.get(ExifTags.Base.DateTimeOriginal)
                    or details.get(ExifTags.Base.DateTimeDigitized)
                ),
            )
    except (OSError, SyntaxError, ValueError) as error:  # how Pillow reports undecodable files
        raise UnreadableImage(path) from error


def _exif(image: Image.Image) -> tuple[Image.Exif, dict]:
    """Main EXIF tags and the Exif sub-IFD, or empty ones when the EXIF block is malformed."""
    try:
        exif = image.getexif()
        return exif, exif.get_ifd(ExifTags.IFD.Exif)
    except (OSError, SyntaxError, ValueError):  # e.g. sticker packs storing JSON as EXIF
        return Image.Exif(), {}


def _text(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    return value.strip("\x00 ") or None


def _datetime(value: object) -> datetime | None:
    try:
        return datetime.strptime(str(value), _EXIF_DATETIME_FORMAT)
    except ValueError:
        return None
