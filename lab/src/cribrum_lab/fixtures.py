"""Golden fixtures for the Kotlin port (docs/design.md, section 10, step 5).

Synthetic images only, never personal photos, generated from fixed seeds into
fixtures/golden/ at the repository root, together with expected.json: what the lab's
detectors say about them. The Android tests run their port on the same files and
compare. Regenerate after a detector change, and commit both:

    uv run python -m cribrum_lab.fixtures
"""

import base64
import contextlib
import json
import shutil
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from cribrum_lab import hashing, mobileclip
from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.metadata import read_metadata
from cribrum_lab.model import ImageRef
from cribrum_lab.pipeline import DETECTORS, GROUP_DETECTORS

ROOT = Path(__file__).resolve().parents[3] / "fixtures" / "golden"
FORMAT_VERSION = 1
TOLERANCE = {"score": 0.05, "embedding_cosine": 0.99}
"""Decoders and resamplers differ between Pillow and Android: numbers match only closely."""
_EMBEDDED = ["DCIM/Camera/texture.jpg", "Documents/page.png"]


def _texture(seed: int, size: tuple[int, int] = (640, 480)) -> Image.Image:
    """Sharp colored blocks: plenty of detail for the blur measure."""
    blocks = np.random.default_rng(seed).integers(0, 256, (size[1] // 8, size[0] // 8, 3), np.uint8)
    return Image.fromarray(blocks).resize(size, Image.Resampling.NEAREST)


def _scene(shift: int = 0) -> Image.Image:
    """Simple shapes on a sky: the burst's subject, moved by `shift` px between shots."""
    scene = Image.new("RGB", (640, 480), (120, 170, 230))
    draw = ImageDraw.Draw(scene)
    draw.rectangle((0, 330, 640, 480), fill=(70, 140, 60))
    draw.ellipse((200 + shift, 150, 360 + shift, 310), fill=(230, 60, 40))
    draw.rectangle((420, 200 - shift, 520, 330), fill=(240, 220, 90), outline=(40, 40, 40), width=4)
    return scene


def _frame(level: int) -> Image.Image:
    """A dark or bright frame with faint detail, as a pocket shot or blown-out frame."""
    detail = np.random.default_rng(level).integers(0, 8, (480, 640, 3))
    return Image.fromarray(np.clip(level + detail - 4, 0, 255).astype(np.uint8))


def _page() -> Image.Image:
    page = Image.new("RGB", (600, 800), "white")
    draw = ImageDraw.Draw(page)
    for y in range(60, 760, 28):
        draw.text((50, y), "The quick brown fox jumps over the lazy dog " * 2, fill="black")
    return page


def _screenshot() -> Image.Image:
    screen = Image.new("RGB", (1080, 2400), (245, 245, 245))
    draw = ImageDraw.Draw(screen)
    draw.rectangle((0, 0, 1080, 160), fill=(30, 110, 200))
    for y in range(260, 2200, 180):
        draw.rounded_rectangle(
            (60, y, 900, y + 120), 30, fill=(255, 255, 255), outline=(200, 200, 200)
        )
    return screen


def _sticker() -> Image.Image:
    """A WhatsApp sticker: a shape on a transparent background, 512 px, as WebP."""
    sticker = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    draw = ImageDraw.Draw(sticker)
    draw.ellipse((56, 56, 456, 456), fill=(250, 200, 40, 255), outline=(60, 30, 10, 255), width=12)
    draw.ellipse((170, 180, 220, 250), fill=(60, 30, 10, 255))
    draw.ellipse((292, 180, 342, 250), fill=(60, 30, 10, 255))
    draw.arc((150, 230, 362, 390), 20, 160, fill=(60, 30, 10, 255), width=14)
    return sticker


def _exif_camera() -> bytes:
    exif = Image.Exif()
    exif[0x010F] = "Google"
    exif[0x0110] = "Pixel 8"
    exif.get_ifd(0x8769)[0x9003] = "2024:05:01 10:00:00"
    return exif.tobytes()


def _burst_exif(second: int) -> bytes:
    exif = Image.Exif()
    exif[0x010F] = "Google"
    exif[0x0110] = "Pixel 8"
    exif.get_ifd(0x8769)[0x9003] = f"2024:05:01 11:00:{second:02d}"
    return exif.tobytes()


def write_images(root: Path) -> None:
    """Every fixture image, at a path that matters for detectors that look at folders."""

    def save(relative: str, image: Image.Image, **params) -> Path:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path, **params)
        return path

    camera = save("DCIM/Camera/texture.jpg", _texture(1), quality=92, exif=_exif_camera())
    copy = root / "WhatsApp/Media/WhatsApp Images/IMG-20240501-WA0001.jpg"
    copy.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(camera, copy)  # exact duplicate
    save("Download/texture-small.jpg", _texture(1).resize((320, 240)), quality=60)  # near
    save("DCIM/Camera/blurred.jpg", _texture(2).filter(ImageFilter.GaussianBlur(6)), quality=92)
    save("DCIM/Camera/dark.jpg", _frame(6), quality=92)
    save("DCIM/Camera/bright.jpg", _frame(250), quality=92)
    save("Pictures/Screenshots/Screenshot_20240501-120000.png", _screenshot())
    save("Documents/page.png", _page())
    sticker = "WhatsApp/Media/WhatsApp Stickers/STK-20240501-WA0001.webp"
    save(sticker, _sticker(), lossless=True, exact=True)  # exact: transparent pixels stay black
    for second, blur in [(0, 4), (1, 0), (2, 2)]:
        image = _scene(shift=6 * second).filter(ImageFilter.GaussianBlur(blur))
        save(f"DCIM/Camera/burst-{second}.jpg", image, quality=92, exif=_burst_exif(second))


def expected(root: Path, embed: bool) -> dict:
    """What the lab says about the fixture images. Embeddings need the MobileCLIP model."""
    paths = sorted(p for p in root.rglob("*") if p.is_file() and p.suffix != ".json")
    images = [ImageRef(p, p.stat().st_size, 0) for p in paths]
    metadata = {image: read_metadata(image.path) for image in images}
    model = mobileclip.MobileClip() if embed else None
    vectors = {}
    if model:
        vectors = {i: model.embed_images([i.path])[0] for i in images}

    def embedding(image: ImageRef) -> np.ndarray:
        if image not in vectors:
            raise LookupError(image.path)
        return vectors[image]

    context = AnalysisContext(metadata=metadata.__getitem__, embedding=embedding)
    signals = {image: [] for image in images}
    for detector in DETECTORS:
        for image in images:
            with contextlib.suppress(LookupError):  # the detector needs embeddings
                signals[image] += detector.analyze(image, context)
    for detector in GROUP_DETECTORS:
        for image, found in detector.analyze(images, context).items():
            signals[image] += found

    result = {}
    for image in images:
        meta = metadata[image]
        entry = {
            "width": meta.width,
            "height": meta.height,
            "has_camera_exif": meta.has_camera_exif,
            "content_hash": hashing.content_hash(image),
            "perceptual_hash": f"{hashing.perceptual_hash(image):016x}",
            "signals": [
                {
                    "detector": s.detector_id,
                    "version": s.detector_version,
                    "category": s.category.value,
                    "score": round(s.score, 4),
                    "evidence": s.evidence,
                }
                for s in signals[image]
            ],
        }
        relative = image.path.relative_to(root).as_posix()
        if relative in _EMBEDDED and image in vectors:
            entry["embedding"] = base64.b64encode(vectors[image].astype("<f4").tobytes()).decode()
        result[relative] = entry
    return {
        "format_version": FORMAT_VERSION,
        "tolerance": TOLERANCE,
        "embedding_model": mobileclip.MODEL_ID if embed else None,
        "images": result,
    }


def main() -> None:
    if ROOT.exists():
        shutil.rmtree(ROOT)
    write_images(ROOT)
    data = expected(ROOT, embed=True)
    (ROOT / "expected.json").write_text(json.dumps(data, indent=1, sort_keys=True) + "\n")
    print(f"wrote {ROOT}")


if __name__ == "__main__":
    main()
