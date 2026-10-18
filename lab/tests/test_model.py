import pytest

from cribrum_lab.model import Category, Signal


def test_signal_accepts_score_in_range():
    signal = Signal("blur", 1, Category.BLURRY, 0.8)
    assert signal.score == 0.8


@pytest.mark.parametrize("score", [-0.1, 1.1])
def test_signal_rejects_score_out_of_range(score):
    with pytest.raises(ValueError):
        Signal("blur", 1, Category.BLURRY, score)
