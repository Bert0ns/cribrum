import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "check_parity.py"
spec = importlib.util.spec_from_file_location("check_parity", SCRIPT)
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)


def test_parsing_each_language():
    lab = parity.values(
        'MAX_SIDE = 512\n_CHUNK = 8\nclass D:\n    id = "d"\n    version = 2\n',
        parity.LAB_ID,
        parity.LAB_VERSION,
        parity.LAB_CONSTANT,
    )
    android = parity.values(
        'class D {\n  override val id = "d"\n  override val version = 2\n'
        "  const val MAX_SIDE = 512\n  private val CHUNK: Float = 8f\n}\n",
        parity.ANDROID_ID,
        parity.ANDROID_VERSION,
        parity.ANDROID_CONSTANT,
    )
    assert lab == android == {"d.MAX_SIDE": "512", "d.CHUNK": "8", "d.version": "2"}


def test_a_different_value_names_the_mismatch():
    problems = parity.mismatches({"d.version": "2", "d.A": "0.5"}, {"d.version": "3", "d.A": "0.5"})
    assert problems == ["d.version: lab 2, android 3"]


def test_a_detector_only_the_lab_has_is_a_mismatch():
    problems = parity.mismatches({"d.version": "1", "e.version": "1"}, {"e.version": "1"})
    assert problems == ["d.version: lab 1, android missing"]


def test_a_detector_listed_as_lab_only_is_skipped(monkeypatch):
    monkeypatch.setattr(parity, "LAB_ONLY", {"d"})
    assert parity.mismatches({"d.version": "1", "e.version": "1"}, {"e.version": "1"}) == []


def test_an_android_file_whose_id_does_not_match_gives_nothing():
    source = 'class D {\n  override val identifier = "d"\n  const val A = 1\n}\n'
    android = parity.values(
        source, parity.ANDROID_ID, parity.ANDROID_VERSION, parity.ANDROID_CONSTANT
    )
    assert parity.mismatches({"d.version": "1", "d.A": "1"}, android) != []


def test_a_trailing_comment_does_not_hide_a_constant():
    lab = parity.values(
        'A = 0.5  # tuned\nclass D:\n    id = "d"\n',
        parity.LAB_ID,
        parity.LAB_VERSION,
        parity.LAB_CONSTANT,
    )
    android = parity.values(
        'override val id = "d"\nconst val A = 0.9f // tuned\n',
        parity.ANDROID_ID,
        parity.ANDROID_VERSION,
        parity.ANDROID_CONSTANT,
    )
    assert lab == {"d.A": "0.5"}
    assert android == {"d.A": "0.9"}
    assert parity.mismatches(lab, android) == ["d.A: lab 0.5, android 0.9"]


def test_a_constant_renamed_on_one_side_is_a_mismatch():
    problems = parity.mismatches({"d.FULL_COVERAGE": "0.1"}, {"d.FULL_COV": "0.1"})
    assert problems == [
        "d.FULL_COV: lab missing, android 0.1",
        "d.FULL_COVERAGE: lab 0.1, android missing",
    ]


def test_a_listed_one_sided_constant_is_allowed():
    assert parity.mismatches({"near_duplicate.CHUNK": "1024"}, {}) == []
