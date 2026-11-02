"""Measure SSIM and space saved by the three compression presets on lab photos.

Run it from anywhere: uv run python lab/scripts/measure_compression.py
Or from lab/: uv run python scripts/measure_compression.py

Levels (issue #173):
- High quality: JPEG quality 90, full resolution.
- Balanced: JPEG quality 85, downscaled to at most 50 MP.
- Smallest: JPEG quality 80, downscaled to at most 12 MP.

Never prints or logs image paths (only aggregate numbers or exception class names).
Temporary compressed files are written under lab/data/ and deleted immediately.
Quality metrics (SSIM, PSNR) are computed in 1024-row chunks to bound array
allocations, though full-resolution Pillow image buffers for 200 MP photos still
use ~800 MB - 1 GB RAM per worker process.
"""

import argparse
import sqlite3
import sys
from concurrent.futures import ProcessPoolExecutor
from contextlib import closing
from pathlib import Path

from PIL import Image

from cribrum_lab.compression import (
    LEVELS,
    CompressionLevel,
    ImageMeasurement,
    LevelReport,
    aggregate_level_results,
    compress_and_measure,
    format_report,
)

DATA = Path(__file__).resolve().parents[1] / "data"
DEFAULT_LABELS = DATA / "labels.sqlite"
DEFAULT_INDEX = DATA / "index.sqlite"
DEFAULT_TEMP_DIR = DATA / "tmp_compression"


def find_jpeg_paths(
    labels_db: Path | None = None,
    index_db: Path | None = None,
    folder: Path | None = None,
    camera_only: bool = False,
) -> list[Path]:
    """Find JPEG photos available in the lab.

    If camera_only is True, filters to photos taken by a camera using
    index_db (camera_make IS NOT NULL) or EXIF Make metadata.
    """
    camera_set: set[Path] | None = None
    if camera_only and index_db and index_db.exists():
        with closing(sqlite3.connect(index_db)) as con:
            cur = con.cursor()
            cur.execute("SELECT path FROM images WHERE camera_make IS NOT NULL")
            camera_set = {Path(r[0]) for r in cur.fetchall()}

    def is_camera(p: Path) -> bool:
        if camera_set is not None:
            return p in camera_set
        try:
            with Image.open(p) as img:
                return bool(img.getexif().get(271))
        except Exception:
            return False

    if folder and folder.is_dir():
        candidates = sorted(
            p for p in folder.rglob("*") if p.is_file() and p.suffix.lower() in (".jpg", ".jpeg")
        )
        return [p for p in candidates if not camera_only or is_camera(p)]

    if labels_db and labels_db.exists():
        with closing(sqlite3.connect(labels_db)) as con:
            cur = con.cursor()
            cur.execute(
                "SELECT path FROM labels "
                "WHERE lower(path) LIKE '%.jpg' OR lower(path) LIKE '%.jpeg'"
            )
            paths = [Path(row[0]) for row in cur.fetchall()]
        existing = [p for p in paths if p.exists()]
        if existing:
            if camera_only:
                existing = [p for p in existing if is_camera(p)]
            return sorted(existing)

    if index_db and index_db.exists():
        query = "SELECT path FROM images WHERE mime = 'image/jpeg'"
        if camera_only:
            query += " AND camera_make IS NOT NULL"
        with closing(sqlite3.connect(index_db)) as con:
            cur = con.cursor()
            cur.execute(query)
            paths = [Path(row[0]) for row in cur.fetchall()]
        existing = [p for p in paths if p.exists()]
        if existing:
            return sorted(existing)
    return []


def _process_image_all_levels(
    args: tuple[Path, tuple[CompressionLevel, ...], Path],
) -> tuple[list[ImageMeasurement], list[CompressionLevel]]:
    path, levels, temp_dir = args
    results: list[ImageMeasurement] = []
    skipped: list[CompressionLevel] = []
    for level in levels:
        try:
            m = compress_and_measure(path, level, temp_dir)
            results.append(m)
        except Exception as e:
            print(f"Skipping level '{level.name}': {type(e).__name__}", file=sys.stderr)
            skipped.append(level)
    return results, skipped


def run_measurements(
    images: list[Path],
    levels: tuple[CompressionLevel, ...] = LEVELS,
    temp_dir: Path = DEFAULT_TEMP_DIR,
    jobs: int = 2,
) -> list[LevelReport]:
    temp_dir.mkdir(parents=True, exist_ok=True)
    work = [(p, levels, temp_dir) for p in images]

    if jobs > 1:
        with ProcessPoolExecutor(max_workers=jobs) as pool:
            all_batches = list(pool.map(_process_image_all_levels, work, chunksize=8))
    else:
        all_batches = [_process_image_all_levels(item) for item in work]

    per_level: dict[CompressionLevel, list[ImageMeasurement]] = {lvl: [] for lvl in levels}
    skipped_per_level: dict[CompressionLevel, int] = {lvl: 0 for lvl in levels}
    for batch, skipped_levels in all_batches:
        for m in batch:
            per_level[m.level].append(m)
        for lvl in skipped_levels:
            skipped_per_level[lvl] += 1

    reports = []
    for lvl in levels:
        measurements = per_level[lvl]
        if measurements:
            reports.append(
                aggregate_level_results(lvl, measurements, skipped_count=skipped_per_level[lvl])
            )
    return reports


def recommendation(reports: list[LevelReport]) -> str:
    """Summarize measured metrics per level without hardcoded values."""
    lines = ["Measured summary:"]
    for r in reports:
        saved_mb = r.total_saved_bytes / (1024 * 1024)
        total_mb = r.total_original_bytes / (1024 * 1024)
        lines.append(
            f"- {r.level.name}: median SSIM {r.ssim_median:.4f} (p10: {r.ssim_worst_decile:.4f}), "
            f"median saved {r.saved_share_median * 100:.1f}%, "
            f"refused {r.refused_share * 100:.1f}%, "
            f"total saved {saved_mb:.1f} / {total_mb:.1f} MB."
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--labels", type=Path, default=DEFAULT_LABELS, help="SQLite labels file")
    parser.add_argument("--index", type=Path, default=DEFAULT_INDEX, help="SQLite index file")
    parser.add_argument("--folder", type=Path, help="Folder containing images")
    parser.add_argument(
        "--camera-only",
        action="store_true",
        help="Filter to photos taken by a camera (using index.sqlite or EXIF Make)",
    )
    parser.add_argument("--limit", type=int, help="Limit number of images evaluated")
    parser.add_argument(
        "--jobs",
        type=int,
        default=2,
        help="Parallel worker processes (default: 2; 200 MP photos use ~1 GB RAM per worker)",
    )
    parser.add_argument(
        "--out", type=Path, help="Write text report to this file (e.g. under lab/data/)"
    )
    args = parser.parse_args(argv)

    images = find_jpeg_paths(
        labels_db=args.labels,
        index_db=args.index,
        folder=args.folder,
        camera_only=args.camera_only,
    )
    if not images:
        sys.exit(
            "No JPEG images found. Ensure lab/data/ has labels.sqlite, "
            "index.sqlite, or pass --folder."
        )

    if args.limit:
        images = images[: args.limit]

    print(
        f"Measuring {len(images)} photos across {len(LEVELS)} levels using {args.jobs} workers...",
        file=sys.stderr,
    )

    temp_dir = DEFAULT_TEMP_DIR
    temp_dir.mkdir(parents=True, exist_ok=True)
    try:
        reports = run_measurements(images, LEVELS, temp_dir=temp_dir, jobs=args.jobs)
        report_text = format_report(reports)
        rec_text = recommendation(reports)
        output = f"{report_text}\n\n{rec_text}\n"
        print(output)

        if args.out:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text(output)
            print(f"Report written to {args.out}", file=sys.stderr)
    finally:
        if temp_dir.exists():
            for f in temp_dir.iterdir():
                f.unlink(missing_ok=True)
            temp_dir.rmdir()


if __name__ == "__main__":
    main()
