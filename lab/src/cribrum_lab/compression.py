"""JPEG compression presets and quality measurement (docs/design.md, issue #173).

Evaluates the three compression presets:
- High quality: JPEG quality 90, full resolution.
- Balanced: JPEG quality 85, downscaled to at most 50 MP.
- Smallest: JPEG quality 80, downscaled to at most 12 MP.

Differences from the app:
- Baseline JPEG encoding via Pillow with default Huffman tables (no optimize=True).
- Subsampling is 4:2:0 at every quality level in Pillow (Skia/Android platform encoders
  may switch subsampling, e.g. to 4:4:4 at high qualities, unverified).
- Downscaling uses Pillow's PIL.Image.Resampling.LANCZOS filter here, vs whatever Android/
  Skia uses in #285 (typically bilinear or Mitchell/Catmull-Rom in platform decoders).
- Embedded ICC color profiles are dropped during re-encoding here.
- Quality metrics (SSIM, PSNR) are calculated on luminance (Y) only, so chroma sub-sampling
  loss is not reflected in these scores.
"""

import math
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image

BALANCED_MAX_PIXELS = 50_000_000
SMALLEST_MAX_PIXELS = 12_000_000


@dataclass(frozen=True)
class CompressionLevel:
    name: str
    quality: int
    max_pixels: int | None


HIGH_QUALITY = CompressionLevel("High quality", quality=90, max_pixels=None)
BALANCED = CompressionLevel("Balanced", quality=85, max_pixels=BALANCED_MAX_PIXELS)
SMALLEST = CompressionLevel("Smallest", quality=80, max_pixels=SMALLEST_MAX_PIXELS)
LEVELS = (HIGH_QUALITY, BALANCED, SMALLEST)


@dataclass(frozen=True)
class ImageMeasurement:
    level: CompressionLevel
    original_bytes: int
    compressed_bytes: int
    saved_bytes: int
    saved_share: float
    ssim: float
    psnr: float
    compressed_size: tuple[int, int]


@dataclass(frozen=True)
class LevelReport:
    level: CompressionLevel
    sample_count: int
    ssim_median: float
    ssim_worst_decile: float
    saved_share_median: float
    saved_share_worst_decile: float
    psnr_median: float
    psnr_worst_decile: float
    total_saved_bytes: int
    total_original_bytes: int
    refused_share: float = 0.0
    skipped_count: int = 0


def target_size(width: int, height: int, max_pixels: int | None) -> tuple[int, int]:
    """Compute target dimensions preserving aspect ratio, ensuring width * height <= max_pixels."""
    if max_pixels is None or width * height <= max_pixels:
        return width, height
    scale = math.sqrt(max_pixels / (width * height))
    target_w = max(1, round(width * scale))
    target_h = max(1, round(height * scale))
    while target_w * target_h > max_pixels:
        if target_w / width >= target_h / height:
            target_w -= 1
        else:
            target_h -= 1
    return target_w, target_h


def compute_quality_metrics(
    img1: Image.Image, img2: Image.Image, chunk_rows: int = 1024
) -> tuple[float, float]:
    """Compute SSIM index and PSNR (dB) using luminance in memory-efficient row chunks."""
    if img1.size != img2.size:
        raise ValueError(f"Image sizes must match: {img1.size} vs {img2.size}")

    g1 = np.asarray(img1.convert("L"))
    g2 = np.asarray(img2.convert("L"))

    h, w = g1.shape
    bh, bw = (h // 8) * 8, (w // 8) * 8
    c1 = (0.01 * 255.0) ** 2
    c2 = (0.03 * 255.0) ** 2

    if bh == 0 or bw == 0:
        f1, f2 = g1.astype(np.float32), g2.astype(np.float32)
        m1, m2 = float(f1.mean()), float(f2.mean())
        v1, v2 = float(f1.var()), float(f2.var())
        cov = float(np.mean((f1 - m1) * (f2 - m2)))
        ssim = float(
            ((2 * m1 * m2 + c1) * (2 * cov + c2)) / ((m1**2 + m2**2 + c1) * (v1 + v2 + c2))
        )
        mse = float(np.mean((f1 - f2) ** 2))
        psnr = float("inf") if mse == 0 else float(10.0 * math.log10((255.0**2) / mse))
        return ssim, psnr

    chunk_rows = max(8, (chunk_rows // 8) * 8)
    total_ssim_sum = 0.0
    total_ssim_count = 0
    total_sq_err = 0.0
    total_pixels = h * w

    for y in range(0, bh, chunk_rows):
        y_end = min(y + chunk_rows, bh)
        b1_raw = g1[y:y_end, :bw].astype(np.float32)
        b2_raw = g2[y:y_end, :bw].astype(np.float32)
        total_sq_err += float(np.sum((b1_raw - b2_raw) ** 2))

        nrows = y_end - y
        b1 = b1_raw.reshape(nrows // 8, 8, bw // 8, 8).transpose(0, 2, 1, 3).reshape(-1, 64)
        b2 = b2_raw.reshape(nrows // 8, 8, bw // 8, 8).transpose(0, 2, 1, 3).reshape(-1, 64)
        m1 = b1.mean(axis=1)
        m2 = b2.mean(axis=1)
        cov = (b1 * b2).mean(axis=1) - m1 * m2
        v1 = (b1**2).mean(axis=1) - m1**2
        v2 = (b2**2).mean(axis=1) - m2**2
        vals = ((2 * m1 * m2 + c1) * (2 * cov + c2)) / ((m1**2 + m2**2 + c1) * (v1 + v2 + c2))
        total_ssim_sum += float(np.sum(vals))
        total_ssim_count += len(vals)

    if bh < h:
        total_sq_err += float(
            np.sum((g1[bh:, :].astype(np.float32) - g2[bh:, :].astype(np.float32)) ** 2)
        )
    if bw < w:
        total_sq_err += float(
            np.sum((g1[:bh, bw:].astype(np.float32) - g2[:bh, bw:].astype(np.float32)) ** 2)
        )

    ssim = total_ssim_sum / total_ssim_count if total_ssim_count else 1.0
    mse = total_sq_err / total_pixels if total_pixels else 0.0
    psnr = float("inf") if mse == 0 else float(10.0 * math.log10((255.0**2) / mse))
    return ssim, psnr


def compute_ssim(img1: Image.Image, img2: Image.Image) -> float:
    """Compute SSIM index between two images of the same dimensions using luminance."""
    return compute_quality_metrics(img1, img2)[0]


def compute_psnr(img1: Image.Image, img2: Image.Image) -> float:
    """Compute Peak Signal-to-Noise Ratio (dB) between two images of the same dimensions."""
    return compute_quality_metrics(img1, img2)[1]


def aggregate_level_results(
    level: CompressionLevel,
    measurements: Sequence[ImageMeasurement],
    skipped_count: int = 0,
) -> LevelReport:
    """Aggregate per-image measurements for a given compression level."""
    if not measurements:
        raise ValueError(f"No measurements provided for level {level.name}")
    ssims = np.array([m.ssim for m in measurements], dtype=float)
    saved_shares = np.array([m.saved_share for m in measurements], dtype=float)
    psnrs = np.array([m.psnr for m in measurements], dtype=float)
    total_saved = sum(m.saved_bytes for m in measurements)
    total_orig = sum(m.original_bytes for m in measurements)
    refused_count = sum(1 for m in measurements if m.saved_bytes <= 0)
    refused_share = refused_count / len(measurements) if measurements else 0.0

    return LevelReport(
        level=level,
        sample_count=len(measurements),
        ssim_median=float(np.percentile(ssims, 50)),
        ssim_worst_decile=float(np.percentile(ssims, 10)),
        saved_share_median=float(np.percentile(saved_shares, 50)),
        saved_share_worst_decile=float(np.percentile(saved_shares, 10)),
        psnr_median=float(np.percentile(psnrs, 50)),
        psnr_worst_decile=float(np.percentile(psnrs, 10)),
        total_saved_bytes=total_saved,
        total_original_bytes=total_orig,
        refused_share=refused_share,
        skipped_count=skipped_count,
    )


def compress_and_measure(
    image_path: Path,
    level: CompressionLevel,
    temp_dir: Path,
) -> ImageMeasurement:
    """Compress image_path at the given level to a temporary file under temp_dir,
    measure SSIM and saved bytes, then delete the temporary file.
    Keep EXIF orientation as stored (do not rotate pixels).
    """
    original_bytes = image_path.stat().st_size
    temp_dir.mkdir(parents=True, exist_ok=True)
    temp_file = temp_dir / f"cribrum_cmp_{uuid.uuid4().hex}.jpg"

    try:
        with Image.open(image_path) as orig_img:
            exif = orig_img.info.get("exif")
            img = orig_img
            if img.mode not in ("RGB", "L"):
                img = img.convert("RGB")

            target_w, target_h = target_size(img.width, img.height, level.max_pixels)
            needs_downscale = (target_w, target_h) != (img.width, img.height)

            if needs_downscale:
                to_save = img.resize((target_w, target_h), Image.Resampling.LANCZOS)
                ref_img = to_save
            else:
                to_save = img
                ref_img = img

            save_kwargs: dict[str, object] = {"quality": level.quality}
            if exif is not None:
                save_kwargs["exif"] = exif

            to_save.save(temp_file, "JPEG", **save_kwargs)
            compressed_bytes = temp_file.stat().st_size

            with Image.open(temp_file) as comp_img:
                compressed_size = comp_img.size
                ssim, psnr = compute_quality_metrics(ref_img, comp_img)

        saved_bytes = original_bytes - compressed_bytes
        saved_share = saved_bytes / original_bytes if original_bytes else 0.0

        return ImageMeasurement(
            level=level,
            original_bytes=original_bytes,
            compressed_bytes=compressed_bytes,
            saved_bytes=saved_bytes,
            saved_share=saved_share,
            ssim=ssim,
            psnr=psnr,
            compressed_size=compressed_size,
        )
    finally:
        if temp_file.exists():
            temp_file.unlink(missing_ok=True)


def format_report(reports: Sequence[LevelReport]) -> str:
    """Format reports into a readable table with medians, worst deciles, and byte totals."""
    header = (
        f"{'Level':<15} {'Count':>5} {'Skipped':>7} {'SSIM (med)':>10} {'SSIM (p10)':>10} "
        f"{'PSNR (med)':>10} {'PSNR (p10)':>10} {'Saved (med)':>12} {'Saved (p10)':>12} "
        f"{'Refused':>8} {'Total saved':>14}"
    )
    separator = "-" * len(header)
    lines = [
        "Compression level quality and byte savings evaluation:",
        "Note: for downscaled levels (Balanced, Smallest), SSIM/PSNR isolate the JPEG",
        "re-encode step at target size and do not reflect resolution loss from downscaling.",
        "",
        header,
        separator,
    ]

    for r in reports:
        total_saved_mb = r.total_saved_bytes / (1024 * 1024)
        total_orig_mb = r.total_original_bytes / (1024 * 1024)
        psnr_med_str = f"{r.psnr_median:.1f}dB" if not math.isinf(r.psnr_median) else "inf"
        psnr_p10_str = (
            f"{r.psnr_worst_decile:.1f}dB" if not math.isinf(r.psnr_worst_decile) else "inf"
        )

        lines.append(
            f"{r.level.name:<15} {r.sample_count:>5} {r.skipped_count:>7} "
            f"{r.ssim_median:>10.4f} {r.ssim_worst_decile:>10.4f} "
            f"{psnr_med_str:>10} {psnr_p10_str:>10} "
            f"{r.saved_share_median * 100:>11.1f}% {r.saved_share_worst_decile * 100:>11.1f}% "
            f"{r.refused_share * 100:>7.1f}% "
            f"{total_saved_mb:>7.1f} / {total_orig_mb:.1f} MB"
        )
    return "\n".join(lines)
