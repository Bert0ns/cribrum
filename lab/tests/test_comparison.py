from pathlib import Path

import pytest

from cribrum_lab.comparison import compare, with_signals, without_signals
from cribrum_lab.labels import Decision, Label
from cribrum_lab.model import Category, ImageRef, IndexedImage, Protection, Signal


def image(name, *signals):
    return IndexedImage(ImageRef(Path(name), 0, 0), signals=signals)


def blur(score):
    return Signal("blur", 1, Category.BLURRY, score)


def faces(score):
    return Signal("faces", 1, Protection.FACES, score)


def sticker():
    return Signal("sticker", 1, Category.STICKER, 1.0)


def label(name, decision):
    return Label(name, Path(name), decision)


def change(comparison, category):
    return next(c for c in comparison.categories if c.category is category)


def test_an_option_that_changes_nothing_drops_nothing_and_keeps_every_order():
    images = [image("a", blur(0.9)), image("b", blur(0.6)), image("c", blur(0.1))]

    result = compare(images, images)

    assert result.candidates == (2, 2)
    assert (result.dropped, result.added, result.reasons_changed) == (0, 0, 0)
    blurry = change(result, Category.BLURRY)
    assert blurry.flagged == (2, 2)
    assert (blurry.lost, blurry.moved) == (0, 0)
    assert blurry.first_page_kept == 1.0
    assert blurry.rank_correlation == pytest.approx(1.0)


def test_a_candidate_that_loses_its_only_reason_is_dropped_and_counted_against_recall():
    before = [image("a", blur(0.9)), image("b", blur(0.5))]
    after = [image("a", blur(0.9)), image("b", blur(0.1))]
    labels = [label("a", Decision.DELETE), label("b", Decision.DELETE)]

    result = compare(before, after, labels)

    assert result.dropped == 1
    assert result.caught == (2, 1)
    blurry = change(result, Category.BLURRY)
    assert (blurry.lost, blurry.caught) == (1, (2, 1))


def test_a_reordered_list_shows_in_its_category():
    before = [image("a", blur(0.9)), image("b", blur(0.7)), image("c", blur(0.5))]
    after = [image("a", blur(0.5)), image("b", blur(0.7)), image("c", blur(0.9))]

    blurry = change(compare(before, after), Category.BLURRY)

    assert blurry.moved == 2
    assert blurry.rank_correlation == pytest.approx(-1.0)


def test_deletes_first_is_the_chance_a_delete_comes_before_a_keep():
    # Order a (delete), b (keep), c (delete): of the two pairs, one is right.
    before = [image("a", blur(0.9)), image("b", blur(0.8)), image("c", blur(0.7))]
    after = [image("a", blur(0.9)), image("b", blur(0.6)), image("c", blur(0.7))]
    labels = [label("a", Decision.DELETE), label("b", Decision.KEEP), label("c", Decision.DELETE)]

    blurry = change(compare(before, after, labels), Category.BLURRY)

    assert blurry.deletes_first == (0.5, 1.0)


def test_without_faces_on_stickers_only_their_order_changes():
    before = [
        image("s1", sticker(), faces(0.9)),
        image("s2", sticker(), faces(0.0)),
        image("photo", blur(0.9), faces(0.9)),
    ]

    after = without_signals(before, {"faces"}, lambda path: path.name.startswith("s"))

    assert after[0].signals == (sticker(),)
    assert after[2] == before[2]
    result = compare(before, after)
    assert result.dropped == 0
    assert change(result, Category.STICKER).listed == (2, 2)
    assert change(result, Category.STICKER).moved == 2


def test_with_signals_replaces_one_detector_for_the_images_given():
    before = [image("a", blur(0.9), sticker()), image("b", blur(0.9))]

    after = with_signals(before, "blur", {Path("a"): [blur(0.2)]})

    assert after[0].signals == (sticker(), blur(0.2))
    assert after[1] == before[1]
