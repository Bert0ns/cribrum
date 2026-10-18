"""Command line: `scan` analyzes images, `report` lists candidates, `label` opens the labeler,
`evaluate` measures recall on the labels."""

import argparse
import os
import sys
from pathlib import Path

from cribrum_lab import pipeline
from cribrum_lab.evaluation import evaluate, format_report
from cribrum_lab.index import ImageIndex
from cribrum_lab.labeling import LabelingServer, clusters, sample
from cribrum_lab.labels import LabelStore
from cribrum_lab.mobileclip import MobileClip
from cribrum_lab.ranker import Ranker

DATA = Path(__file__).resolve().parents[2] / "data"
"""lab/data/, which git ignores: the index and the labels hold paths of personal images."""
DEFAULT_INDEX = DATA / "index.sqlite"
DEFAULT_LABELS = DATA / "labels.sqlite"


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
    label = commands.add_parser("label", help="label a sample of images in the browser")
    label.add_argument("--labels", type=Path, default=DEFAULT_LABELS, help="SQLite labels file")
    label.add_argument("--size", type=int, default=500, help="images in the sample")
    label.add_argument("--seed", type=int, default=0, help="change it for a new sample")
    label.add_argument("--port", type=int, default=8765)
    label.add_argument(
        "--clusters", action="store_true", help="show similar images as grids labeled at once"
    )
    evaluate = commands.add_parser("evaluate", help="recall per category on the labels")
    evaluate.add_argument("--labels", type=Path, default=DEFAULT_LABELS, help="SQLite labels file")
    evaluate.add_argument(
        "--out", type=Path, help="also write the report here (keep it under lab/data/)"
    )
    args = parser.parse_args(argv)

    args.index.parent.mkdir(parents=True, exist_ok=True)
    with ImageIndex(args.index) as index:
        if args.command == "scan":
            embedder = None if args.no_embeddings else MobileClip()
            _scan(index, args.folder, args.jobs, embedder)
        elif args.command == "report":
            _report(index, args.top)
        elif args.command == "label":
            _label(index, args.labels, args.size, args.seed, args.port, args.clusters)
        else:
            _evaluate(index, args.labels, args.out)


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


def _label(index: ImageIndex, labels: Path, size: int, seed: int, port: int, grouped: bool) -> None:
    with LabelStore(labels) as store:
        done = {label.path for label in store.all()}
        exclude = done | set(index.unreadable())
        queue = sample(Ranker().rank(index.indexed_images()), size, exclude, seed)
        groups = []
        if grouped:
            groups = clusters(queue, index.embeddings(MobileClip.id, MobileClip.version))
        in_groups = {image for group in groups for image in group}
        groups += [[image] for image in queue if image not in in_groups]
        server = LabelingServer(groups, store, port)
        print(f"{len(queue)} images to label at http://127.0.0.1:{server.server_port}/")
        print(f"{len(done)} labeled so far, stored in {labels}. Ctrl-C to stop.")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()


def _evaluate(index: ImageIndex, labels: Path, out: Path | None) -> None:
    with LabelStore(labels) as store:
        text = format_report(evaluate(index.indexed_images(), store.all()))
    print(text)
    if out:
        out.write_text(text + "\n")


if __name__ == "__main__":
    main()
