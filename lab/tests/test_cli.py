from PIL import Image

from cribrum_lab.cli import main


def test_scan_then_report_lists_candidates_per_category(tmp_path, capsys):
    folder = tmp_path / "device/Pictures/Screenshots"
    folder.mkdir(parents=True)
    Image.new("RGB", (1080, 2400), "white").save(folder / "Screenshot_1.png")
    index = str(tmp_path / "index.sqlite")

    main(["--index", index, "scan", str(tmp_path / "device"), "--jobs", "1", "--no-embeddings"])
    main(["--index", index, "report"])

    out = capsys.readouterr().out
    assert "1 images, 1 to review, 1 candidates" in out
    assert "screenshot: 1" in out
    assert "Screenshot_1.png" in out
