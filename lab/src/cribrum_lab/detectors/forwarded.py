"""FORWARDED: memes, stickers and greeting images from messaging apps (docs/design.md, 5).

Three pieces of evidence, combined as independent chances (noisy OR) like SCREENSHOT:
the zero-shot class is the strongest, because messaging folders and missing camera EXIF
also describe every photo a friend sends. The weights are starting points, to be tuned
for recall in the lab.
"""

from cribrum_lab import prompts
from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.folders import is_messaging_folder
from cribrum_lab.model import Category, ImageRef, Signal

ZERO_SHOT_WEIGHT = 0.9
MESSAGING_FOLDER_WEIGHT = 0.4
NO_CAMERA_EXIF_WEIGHT = 0.1


class ForwardedDetector:
    id = "forwarded"
    version = 1
    """Bump with prompts.VERSION."""

    def analyze(self, image: ImageRef, context: AnalysisContext) -> list[Signal]:
        probability, prompt = prompts.classify(context.embedding(image))["forwarded"]
        messaging = is_messaging_folder(image.path)
        camera_exif = context.metadata(image).has_camera_exif
        miss = (
            (1.0 - ZERO_SHOT_WEIGHT * probability)
            * (1.0 - (MESSAGING_FOLDER_WEIGHT if messaging else 0.0))
            * (1.0 - (0.0 if camera_exif else NO_CAMERA_EXIF_WEIGHT))
        )
        evidence = {
            "prompt": prompt,
            "probability": f"{probability:.3f}",
            "messaging_folder": "yes" if messaging else "no",
            "camera_exif": "yes" if camera_exif else "no",
        }
        return [Signal(self.id, self.version, Category.FORWARDED, 1.0 - miss, evidence)]
