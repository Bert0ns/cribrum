"""Zero-shot classes and their MobileCLIP prompts, in one versioned place (docs/design.md, 5).

The prompt embeddings are generated once and committed in `prompt_embeddings.json`, which
the Android app ships as is, so it never needs the text encoder. After editing PROMPTS,
bump VERSION and the version of every detector using them, then regenerate the file:

    uv run python -m cribrum_lab.prompts
"""

import base64
import functools
import json
from pathlib import Path

import numpy as np

from cribrum_lab import mobileclip

VERSION = 1
PROMPTS = {
    "document": [
        "a photo of a projector slide in a classroom",
        "a photo of a presentation slide on a screen",
        "a photo of a whiteboard with writing",
        "a photo of a blackboard with writing",
        "a photo of handwritten notes on paper",
        "a photo of handwritten homework in a notebook",
        "a photo of math exercises on paper",
        "a photo of a printed page of text",
        "a photo of a page of a book",
        "a photo of a document",
        "a photo of a receipt",
        "a photo of a form or a contract",
        "a photo of a ticket or a bill",
        "a photo of a timetable or a notice board",
        "a photo of a computer monitor showing text",
    ],
    "forwarded": [
        "an internet meme",
        "a meme with a caption",
        "a funny picture with text on top",
        "a sticker",
        "a cartoon sticker",
        "an emoji sticker",
        "a good morning greeting image",
        "a good night greeting image",
        "a greeting card image with flowers and text",
        "a religious image with a quote",
        "a motivational quote on a background",
        "a birthday wishes image",
        "a viral image from social media",
    ],
    "screenshot": [
        "a screenshot of a phone screen",
        "a screenshot of a chat conversation",
        "a screenshot of a website",
    ],
    "photo": [
        "a photo of people",
        "a selfie",
        "a group photo of friends",
        "a photo of a family",
        "a photo of a child",
        "a photo of a pet",
        "a photo of a landscape",
        "a photo of a city",
        "a photo of a building",
        "a photo of food",
        "a photo at a party",
        "a photo of a concert",
        "a photo of an object",
        "a photo of a car",
        "a photo of nature",
        "a photo of the sea",
        "a photo of a sports event",
        "a photo of a room",
    ],
}
"""Each class's score is the sum of its prompts' probabilities. "photo" is the class of
memories, and only competes for probability."""

EMBEDDINGS = Path(__file__).with_name("prompt_embeddings.json")


def classify(embedding: np.ndarray) -> dict[str, tuple[float, str]]:
    """Probability of each class for one image embedding, with the class's best prompt."""
    texts, vectors = _embeddings()
    probabilities = mobileclip.zero_shot(embedding[None, :], vectors)[0]
    result = {}
    for name, prompts in PROMPTS.items():
        indices = [texts.index(prompt) for prompt in prompts]
        best = max(indices, key=lambda i: probabilities[i])
        result[name] = (float(probabilities[indices].sum()), texts[best])
    return result


@functools.cache
def _embeddings() -> tuple[list[str], np.ndarray]:
    stored = json.loads(EMBEDDINGS.read_text())
    if stored["version"] != VERSION or stored["model"] != mobileclip.MODEL_ID:
        raise RuntimeError(f"{EMBEDDINGS.name} is stale: regenerate it (see cribrum_lab.prompts)")
    texts = list(stored["prompts"])
    vectors = np.stack(
        [np.frombuffer(base64.b64decode(v), dtype="<f4") for v in stored["prompts"].values()]
    )
    return texts, vectors


def generate() -> dict:
    texts = [prompt for prompts in PROMPTS.values() for prompt in prompts]
    vectors = mobileclip.MobileClip().embed_texts(texts)
    return {
        "version": VERSION,
        "model": mobileclip.MODEL_ID,
        "encoding": "base64 of 512 little-endian float32, L2-normalized",
        "prompts": {
            text: base64.b64encode(vector.astype("<f4").tobytes()).decode()
            for text, vector in zip(texts, vectors, strict=True)
        },
    }


if __name__ == "__main__":
    EMBEDDINGS.write_text(json.dumps(generate(), indent=1) + "\n")
    print(f"wrote {EMBEDDINGS}")
