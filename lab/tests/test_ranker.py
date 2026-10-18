from pathlib import Path

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, Role
from cribrum_lab.model import Category, ImageRef, IndexedImage, Protection, ReviewState, Signal
from cribrum_lab.ranker import Ranker

THRESHOLDS = {Category.BLURRY: 0.5, Category.SCREENSHOT: 0.8, Category.EXACT_DUPLICATE: 0.5}


def image(name, *signals, state=ReviewState.UNREVIEWED, seen_when_kept=frozenset()):
    return IndexedImage(ImageRef(Path(name), 0, 0), state, signals, frozenset(seen_when_kept))


def blur(score, version=1):
    return Signal("blur", version, Category.BLURRY, score)


def screenshot(score):
    return Signal("screenshot", 1, Category.SCREENSHOT, score)


def duplicate(group, role):
    score = 0.0 if role == Role.BEST else 1.0
    evidence = {GROUP_KEY: group, ROLE_KEY: role}
    return Signal("exact_duplicate", 1, Category.EXACT_DUPLICATE, score, evidence)


def rank(*images):
    return Ranker(THRESHOLDS).rank(list(images))


def names(candidates):
    return [c.image.ref.path.name for c in candidates]


def test_any_signal_past_its_threshold_makes_a_candidate():
    blurry = image("blurry", blur(0.6), screenshot(0.1))
    screen = image("screen", blur(0.1), screenshot(0.9))
    neither = image("neither", blur(0.4), screenshot(0.7))

    candidates = {c.image.ref.path.name: c for c in rank(blurry, screen, neither)}

    assert candidates["blurry"].reasons == (blur(0.6),)
    assert candidates["screen"].reasons == (screenshot(0.9),)
    assert not candidates["neither"].is_candidate


def test_signal_exactly_at_the_threshold_passes():
    [candidate] = rank(image("a", blur(0.5)))

    assert candidate.is_candidate


def test_category_without_a_threshold_never_passes():
    signal = Signal("document", 1, Category.DOCUMENT, 1.0)

    [candidate] = rank(image("a", signal))

    assert not candidate.is_candidate


def test_review_everything_lists_every_unreviewed_image_candidates_first():
    order = rank(
        image("unflagged", blur(0.45)),
        image("barely", blur(0.55)),
        image("clean"),
        image("sure", blur(0.95)),
        image("trashed", blur(0.9), state=ReviewState.TRASHED),
    )

    assert names(order) == ["sure", "barely", "unflagged", "clean"]


def test_order_compares_categories_by_their_margin_over_the_threshold():
    # 0.85 is barely past the screenshot threshold, 0.7 well past the blur one.
    order = rank(image("screen", screenshot(0.85)), image("blurry", blur(0.7)))

    assert names(order) == ["blurry", "screen"]


def test_by_category_sorts_each_category_by_score():
    order = rank(
        image("a", blur(0.6)),
        image("b", blur(0.9), screenshot(0.95)),
        image("c", screenshot(0.85)),
        image("d", blur(0.1)),
    )

    groups = Ranker.by_category(order)

    assert {category: names(cs) for category, cs in groups.items()} == {
        Category.BLURRY: ["b", "a"],
        Category.SCREENSHOT: ["b", "c"],
    }


def test_best_copy_of_a_group_is_not_a_candidate():
    order = rank(
        image("best", duplicate("g", Role.BEST)), image("copy", duplicate("g", Role.INFERIOR))
    )

    assert [c.is_candidate for c in order] == [True, False]


def test_kept_image_is_not_suggested_again_for_the_same_signals():
    kept = image(
        "kept",
        blur(0.9),
        duplicate("g", Role.INFERIOR),
        state=ReviewState.KEPT,
        seen_when_kept={blur(0.9).identity, duplicate("g", Role.INFERIOR).identity},
    )

    assert rank(kept) == []


def test_new_detector_version_or_score_is_not_a_new_signal():
    kept = image(
        "kept", blur(0.95, version=2), state=ReviewState.KEPT, seen_when_kept={blur(0.2).identity}
    )

    assert rank(kept) == []


def test_kept_image_comes_back_for_a_new_signal_only():
    kept = image(
        "kept",
        blur(0.9),
        duplicate("new-group", Role.INFERIOR),
        state=ReviewState.KEPT,
        seen_when_kept={blur(0.9).identity},
    )

    [candidate] = rank(kept)

    assert candidate.reasons == (duplicate("new-group", Role.INFERIOR),)


def test_kept_best_copy_comes_back_when_it_becomes_the_inferior_one():
    # A higher-resolution copy showed up after the user kept this one.
    kept = image(
        "kept",
        duplicate("g", Role.INFERIOR),
        state=ReviewState.KEPT,
        seen_when_kept={duplicate("g", Role.BEST).identity},
    )

    assert names(rank(kept)) == ["kept"]


def test_kept_image_with_a_new_signal_below_threshold_stays_hidden():
    kept = image("kept", screenshot(0.5), state=ReviewState.KEPT)

    assert rank(kept) == []


def test_threshold_of_one_passes_only_a_perfect_score():
    ranked = Ranker({Category.BLURRY: 1.0}).rank([image("a", blur(1.0)), image("b", blur(0.9))])

    assert [c.is_candidate for c in ranked] == [True, False]


def faces(score=1.0):
    return Signal("faces", 1, Protection.FACES, score, {"faces": "2"})


def test_protection_lowers_an_image_but_keeps_it_a_candidate():
    order = rank(image("people", blur(0.9), faces()), image("wall", blur(0.8)))

    assert names(order) == ["wall", "people"]
    assert all(c.is_candidate for c in order)
    assert order[1].reasons == (blur(0.9),)


def test_protected_candidate_still_comes_before_every_non_candidate():
    order = rank(
        image("people", blur(0.5), faces(), Signal("x", 1, Protection.FAVORITE, 1.0)),
        image("unflagged", blur(0.49)),
    )

    assert names(order) == ["people", "unflagged"]


def test_protection_signal_alone_never_makes_a_candidate():
    [candidate] = rank(image("people", faces()))

    assert not candidate.is_candidate


def test_unique_image_ranks_below_an_equally_scored_duplicate():
    order = rank(
        image("unique", blur(0.9)),
        image("copy", blur(0.9), duplicate("g", Role.INFERIOR)),
        image("best", blur(0.9), duplicate("g", Role.BEST)),
    )

    assert names(order)[-1] == "unique"
    assert order[-1].protection < 1.0


def test_by_category_applies_protection_too():
    order = rank(image("people", blur(0.9), faces()), image("wall", blur(0.8)))

    assert names(Ranker.by_category(order)[Category.BLURRY]) == ["wall", "people"]
