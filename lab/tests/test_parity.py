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


def test_a_detector_only_the_lab_has_is_skipped():
    assert parity.mismatches({"d.version": "1", "e.version": "1"}, {"e.version": "1"}) == []


def test_a_detector_only_android_has_is_a_mismatch():
    assert parity.mismatches({}, {"d.version": "1"}) == ["d.version: lab missing, android 1"]
