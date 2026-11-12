"""Checks that lab/ and android/ agree on detector ids, versions and thresholds.

Run it from anywhere: python3 lab/scripts/check_parity.py

It reads the sources with regular expressions, so it needs no dependencies. Per detector (matched
by id) it compares the version and every numeric constant with an UPPER_CASE name; for the ranker,
the thresholds, the protection weights and its constants. Everything must exist on both sides,
except what LAB_ONLY (detectors not ported yet) and ONE_SIDED (implementation details) list.
It also compares the ids of the detectors a sticker skips, SKIPPED_ON_STICKERS in each pipeline.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAB = ROOT / "lab/src/cribrum_lab"
ANDROID = ROOT / "android/app/src/main/kotlin/io/github/bert0ns/cribrum"

LAB_ONLY: set[str] = set()
"""Ids of detectors the lab has and the app does not yet. Empty when they are all ported."""
ONE_SIDED = {"near_duplicate.CHUNK", "near_duplicate.ROWS_PER_TASK"}
"""Constants only one side defines, such as a chunk size that bounds the lab's memory."""

NUMBER = r"(-?\d+(?:\.\d+)?)"
LAB_ID = re.compile(r'^\s+id = "(\w+)"', re.M)
LAB_VERSION = re.compile(r"^\s+version = (\d+)", re.M)
LAB_CONSTANT = re.compile(rf"^_?([A-Z][A-Z0-9_]*) = {NUMBER}\s*(?:#.*)?$", re.M)
ANDROID_ID = re.compile(r'override val id = "(\w+)"')
ANDROID_VERSION = re.compile(r"override val version = (\d+)")
ANDROID_CONSTANT = re.compile(
    rf"\bval ([A-Z][A-Z0-9_]*)(?::\s*\w+)? = {NUMBER}f?\s*(?://.*)?$", re.M
)
SKIPPED = re.compile(r"^(?:val )?SKIPPED_ON_STICKERS\b.*$", re.M)
WEIGHT = re.compile(rf"\b((?:Category|Protection)\.\w+)\s*(?::|to)\s*{NUMBER}")


def values(source: str, id_re, version_re, constant_re, owner=None) -> dict[str, str]:
    """Maps "<owner>.<name>" to a number, where the owner is the detector id or "ranker"."""
    if owner is None:
        ids = id_re.findall(source)
        if not ids:
            return {}  # not a detector file
        owner = ids[0]
    found = {f"{owner}.{name}": number for name, number in constant_re.findall(source)}
    if version := version_re.search(source):
        found[f"{owner}.version"] = version.group(1)
    if owner == "ranker":
        found |= {f"ranker.{name}": number for name, number in WEIGHT.findall(source)}
    return found


def collect(files, id_re, version_re, constant_re) -> dict[str, str]:
    found = {}
    for file in files:
        owner = "ranker" if file.stem.lower() == "ranker" else None
        found |= values(file.read_text(), id_re, version_re, constant_re, owner)
    return found


def mismatches(lab: dict[str, str], android: dict[str, str]) -> list[str]:
    problems = []
    for key in sorted(lab.keys() | android.keys()):
        if key.split(".")[0] in LAB_ONLY or key in ONE_SIDED:
            continue
        if key not in lab or key not in android or float(lab[key]) != float(android[key]):
            lab_value, android_value = lab.get(key, "missing"), android.get(key, "missing")
            problems.append(f"{key}: lab {lab_value}, android {android_value}")
    return problems


def skipped_on_stickers(source: str) -> set[str] | None:
    """The ids in the one-line SKIPPED_ON_STICKERS definition, or None when there is none."""
    line = SKIPPED.search(source)
    return set(re.findall(r'"(\w+)"', line.group())) if line else None


def skipped_mismatch(lab: set[str] | None, android: set[str] | None) -> list[str]:
    if lab is not None and lab == android:
        return []

    def show(ids):
        return "missing" if ids is None else ", ".join(sorted(ids)) or "none"

    return [f"SKIPPED_ON_STICKERS: lab {show(lab)}, android {show(android)}"]


def main() -> int:
    lab_files = [*sorted((LAB / "detectors").glob("*.py")), LAB / "ranker.py"]
    android_files = [*sorted((ANDROID / "detectors").glob("*.kt")), ANDROID / "ranker/Ranker.kt"]
    lab = collect(lab_files, LAB_ID, LAB_VERSION, LAB_CONSTANT)
    android = collect(android_files, ANDROID_ID, ANDROID_VERSION, ANDROID_CONSTANT)
    if not lab or not android:
        print("parity: no detectors found, so the paths or patterns in this script are stale")
        return 1
    problems = mismatches(lab, android) + skipped_mismatch(
        skipped_on_stickers((LAB / "pipeline.py").read_text()),
        skipped_on_stickers((ANDROID / "pipeline/Pipeline.kt").read_text()),
    )
    for problem in problems:
        print(f"parity: {problem}")
    if problems:
        print("Fix the side that is wrong, and bump the detector version if its output changes.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
