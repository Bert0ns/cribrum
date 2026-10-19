from PIL import Image

from cribrum_lab.cli import main
from cribrum_lab.labels import Decision, Label, LabelStore


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


def test_evaluate_prints_recall(tmp_path, capsys):
    folder = tmp_path / "device"
    folder.mkdir()
    Image.new("RGB", (1080, 2400), "white").save(folder / "Screenshot_1.png")
    index, labels = str(tmp_path / "index.sqlite"), tmp_path / "labels.sqlite"
    main(["--index", index, "scan", str(folder), "--jobs", "1", "--no-embeddings"])
    with LabelStore(labels) as store:
        path = (folder / "Screenshot_1.png").resolve()
        store.set(Label("h", path, Decision.DELETE))

    main(["--index", index, "evaluate", "--labels", str(labels), "--out", str(tmp_path / "r.txt")])

    assert "recall: 100% (1/1)" in capsys.readouterr().out
    assert "recall: 100%" in (tmp_path / "r.txt").read_text()
