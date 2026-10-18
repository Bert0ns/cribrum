from collections import defaultdict
from pathlib import Path

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, AnalysisContext, GroupDetector, Role
from cribrum_lab.model import Category, ImageRef, Signal


class SameSizeDetector:
    """Toy group detector: images with the same size form a group, the first path is best."""

    id = "same-size"
    version = 1

    def analyze(self, images, context):
        groups = defaultdict(list)
        for image in images:
            groups[image.size].append(image)
        result = {}
        for size, members in groups.items():
            if len(members) < 2:
                continue
            best, *others = sorted(members, key=lambda image: image.path)
            roles = [(best, Role.BEST, 0.0)] + [(other, Role.INFERIOR, 1.0) for other in others]
            for image, role, score in roles:
                evidence = {GROUP_KEY: str(size), ROLE_KEY: role}
                result[image] = [
                    Signal(self.id, self.version, Category.EXACT_DUPLICATE, score, evidence)
                ]
        return result


def run(detector: GroupDetector, images):
    return detector.analyze(images, AnalysisContext())


def test_group_members_share_a_group_id_and_get_a_role():
    a = ImageRef(Path("a.jpg"), 10, 0)
    b = ImageRef(Path("b.jpg"), 10, 0)
    unique = ImageRef(Path("c.jpg"), 20, 0)

    signals = run(SameSizeDetector(), [b, unique, a])

    assert unique not in signals
    assert signals[a][0].evidence == {"group": "10", "role": "best"}
    assert signals[b][0].evidence == {"group": "10", "role": "inferior"}
