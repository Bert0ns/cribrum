"""EXACT_DUPLICATE: byte-identical files (docs/design.md, section 5)."""

from collections import defaultdict
from collections.abc import Sequence

from cribrum_lab.detectors.base import AnalysisContext, Role
from cribrum_lab.folders import is_camera_folder, is_messaging_folder
from cribrum_lab.model import GROUP_KEY, ROLE_KEY, Category, ImageRef, Signal


class ExactDuplicateDetector:
    """Groups files by size first, and hashes only the ones that share a size."""

    id = "exact_duplicate"
    version = 1
    category = Category.EXACT_DUPLICATE

    def analyze(
        self, images: Sequence[ImageRef], context: AnalysisContext
    ) -> dict[ImageRef, list[Signal]]:
        by_size = defaultdict(list)
        for image in images:
            by_size[image.size].append(image)
        by_hash = defaultdict(list)
        for same_size in by_size.values():
            if len(same_size) < 2:
                continue
            for image in same_size:
                try:
                    by_hash[context.content_hash(image)].append(image)
                except OSError:
                    continue  # unreadable or gone since the scan
        result = {}
        for digest, copies in by_hash.items():
            if len(copies) < 2:
                continue
            best = min(copies, key=_preference)
            for image in copies:
                role, score = (Role.BEST, 0.0) if image == best else (Role.INFERIOR, 1.0)
                evidence = {GROUP_KEY: digest, ROLE_KEY: role}
                result[image] = [
                    Signal(self.id, self.version, Category.EXACT_DUPLICATE, score, evidence)
                ]
        return result


def _preference(image: ImageRef) -> tuple:
    """The copy to keep: camera folder first, messaging folders last, then the shortest path."""
    if is_messaging_folder(image.path):
        place = 2
    elif is_camera_folder(image.path):
        place = 0
    else:
        place = 1
    return place, len(image.path.parts), str(image.path)
