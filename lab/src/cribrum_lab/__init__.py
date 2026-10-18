"""Cribrum lab: prototype and reference implementation of Cribrum's detectors."""

import pillow_heif

# Phone cameras save HEIC by default: let Pillow open it anywhere in the lab.
pillow_heif.register_heif_opener()
