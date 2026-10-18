# Golden fixtures

Synthetic images with the signals the Python lab gives them. The Kotlin port runs its detectors on the same files and checks that it agrees (`docs/design.md`, section 10, step 5).

Every image here is generated from fixed seeds by `lab/src/cribrum_lab/fixtures.py`. **Never add personal photos**, or anything derived from them. To regenerate after a detector change (this needs the MobileCLIP model, which `scan` downloads):

```bash
cd lab
uv run python -m cribrum_lab.fixtures
```

## `golden/expected.json`

```jsonc
{
  "format_version": 1,
  "tolerance": {"score": 0.05, "embedding_cosine": 0.99},
  "embedding_model": "mobileclip_s0",
  "images": {
    "DCIM/Camera/texture.jpg": {           // path relative to golden/: folders matter
      "width": 640, "height": 480,          // as displayed, EXIF orientation applied
      "has_camera_exif": true,
      "content_hash": "…",                  // xxHash3 64-bit of the file, hex
      "perceptual_hash": "…",               // 64-bit pHash, hex
      "embedding": "…",                     // only some images: base64 of 512 little-endian float32
      "signals": [
        {"detector": "blur", "version": 1, "category": "blurry", "score": 0.12, "evidence": {…}}
      ]
    }
  }
}
```

Group detectors (exact and near duplicates, bursts) run over all the images at once, as they do on a phone. Decoders and resamplers differ between Pillow and Android, so scores must match within `tolerance.score` and embeddings within `tolerance.embedding_cosine`. Hashes, sizes, categories and roles must match exactly. Numbers in `evidence` are informative only.
