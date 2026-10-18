"""Cribrum lab: prototype and reference implementation of Cribrum's detectors."""

import pillow_heif
from PIL import Image

# Phone cameras save HEIC by default: let Pillow open it anywhere in the lab.
pillow_heif.register_heif_opener()

# 200 MP phone sensors exceed Pillow's decompression-bomb limit; the lab only opens the
# user's own photos, so the guard would just drop real images.
Image.MAX_IMAGE_PIXELS = None
