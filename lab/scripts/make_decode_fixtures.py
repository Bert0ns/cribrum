"""Writes the synthetic animated images the android decoding tests read: fixtures/decode/.

Run it from lab/: uv run python scripts/make_decode_fixtures.py

Both files hold three 64 x 48 frames of four flat colours, one per quadrant, that turn by one
quadrant per frame, so a decoder that returns another frame than the first shows. The colours
are flat so that GIF's palette keeps them exact.
"""

from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parents[2] / "fixtures/decode"
WIDTH, HEIGHT = 64, 48
QUADRANTS = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)]


def frame(turn: int) -> Image.Image:
    image = Image.new("RGB", (WIDTH, HEIGHT))
    for i in range(4):
        left, top = (i % 2) * WIDTH // 2, (i // 2) * HEIGHT // 2
        image.paste(QUADRANTS[(i + turn) % 4], (left, top, left + WIDTH // 2, top + HEIGHT // 2))
    return image


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    frames = [frame(turn) for turn in range(3)]
    frames[0].save(OUT / "animated.gif", save_all=True, append_images=frames[1:], duration=100)
    frames[0].save(
        OUT / "animated.webp",
        save_all=True,
        append_images=frames[1:],
        duration=100,
        lossless=True,
    )


if __name__ == "__main__":
    main()
