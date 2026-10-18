"""Command line: `cribrum-lab scan FOLDER` analyzes images, `cribrum-lab report` lists them."""

import argparse
import os
import sys
from pathlib import Path

from cribrum_lab import pipeline
from cribrum_lab.index import ImageIndex
from cribrum_lab.mobileclip import MobileClip
from cribrum_lab.ranker import Ranker

DEFAULT_INDEX = Path(__file__).resolve().parents[2] / "data" / "index.sqlite"
"""Under lab/data/, which git ignores: the index holds paths of personal images."""


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="cribrum-lab")
    parser.add_argument("--index", type=Path, default=DEFAULT_INDEX, help="SQLite index file")
    commands = parser.add_subparsers(dest="command", required=True)
    scan = commands.add_parser("scan", help="find and analyze every image under a folder")
    scan.add_argument("folder", type=Path)
    scan.add_argument("--jobs", type=int, default=os.cpu_count(), help="worker processes")
    scan.add_argument(
        "--no-embeddings", action="store_true", help="skip MobileCLIP (no model download)"
    )
    report = commands.add_parser("report", help="candidates per category")
    report.add_argument("--top", type=int, default=10, help="paths listed per category")
    args = parser.parse_args(argv)

    args.index.parent.mkdir(parents=True, exist_ok=True)
    with ImageIndex(args.index) as index:
        if args.command == "scan":
            embedder = None if args.no_embeddings else MobileClip()
            _scan(index, args.folder, args.jobs, embedder)
        else:
            _report(index, args.top)


def _scan(index: ImageIndex, folder: Path, jobs: int, embedder: pipeline.Embedder | None) -> None:
    if not folder.is_dir():
        sys.exit(f"not a folder: {folder}")
    stats = pipeline.run(folder, index, embedder=embedder, jobs=jobs, progress=_progress)
    print(
        f"\r\033[K{stats.images} images, {stats.new_or_changed} new or changed, "
        f"{stats.forgotten} gone, {stats.unreadable} unreadable, "
        f"{stats.detector_runs} detector runs, {stats.skipped} waiting for embeddings",
        file=sys.stderr,
    )
    for failure in stats.failures:
        print(f"failed: {failure}", file=sys.stderr)


def _progress(step: str, done: int, total: int) -> None:
    if done % 50 and done != total:
        return  # repaint now and then: thousands of terminal writes slow the run down
    count = f"{done}/{total}" if total else str(done)
    print(f"\r\033[K{step}: {count}", end="", file=sys.stderr, flush=True)


def _report(index: ImageIndex, top: int) -> None:
    images = index.indexed_images()
    ranked = Ranker().rank(images)
    candidates = [c for c in ranked if c.is_candidate]
    print(f"{len(images)} images, {len(ranked)} to review, {len(candidates)} candidates")
    by_category = Ranker.by_category(candidates)
    for category, members in sorted(by_category.items(), key=lambda item: -len(item[1])):
        print(f"\n{category.value}: {len(members)}")
        for candidate in members[:top]:
            score = max(s.score for s in candidate.reasons if s.category is category)
            print(f"  {score:.2f}  {candidate.image.ref.path}")


if __name__ == "__main__":
    main()
