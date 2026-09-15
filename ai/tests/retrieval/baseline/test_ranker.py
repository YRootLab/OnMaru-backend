from __future__ import annotations

from onmaru_ai.retrieval.baseline import (
    BaselineConstraintError,
    BaselineRanker,
    BaselineRequest,
    Candidate,
    CandidateStatus,
    Evidence,
    HardFilterReason,
)


def request_for(
    *,
    query_text: str = "전주 한옥과 시장 산책",
    query_tokens: tuple[str, ...] = ("한옥", "시장", "산책"),
    topics: tuple[str, ...] = ("HANOK", "MARKET", "WALK"),
    excluded_refs: frozenset[str] = frozenset(),
    pinned_refs: tuple[str, ...] = (),
) -> BaselineRequest:
    return BaselineRequest(
        dataset_revision="catalog-rev-7",
        region_code="KR-45-JEONJU",
        query_text=query_text,
        query_tokens=query_tokens,
        topics=topics,
        excluded_refs=excluded_refs,
        pinned_refs=pinned_refs,
    )


def candidate_for(
    ref: str,
    *,
    name: str | None = None,
    category: str = "HANOK",
    topics: tuple[str, ...] = ("HANOK", "WALK"),
    summary: str | None = "검수된 한옥 산책 소개",
    revision_id: str = "catalog-rev-7",
    region_code: str = "KR-45-JEONJU",
    public: bool = True,
    status: CandidateStatus = CandidateStatus.ACTIVE,
    evidence: tuple[Evidence, ...] | None = None,
) -> Candidate:
    return Candidate(
        ref=ref,
        revision_id=revision_id,
        region_code=region_code,
        name=name or f"후보 {ref}",
        category=category,
        summary=summary,
        topics=topics,
        curated_relations=("WALK",),
        evidence=evidence
        if evidence is not None
        else (Evidence(source_id=f"source-{ref}", text=f"근거 {ref}"),),
        status=status,
        public=public,
    )


def test_same_revision_and_input_are_independent_of_candidate_order() -> None:
    candidates = (
        candidate_for("place-c", category="MARKET", topics=("MARKET",)),
        candidate_for("place-a"),
        candidate_for("place-b", topics=("HANOK",)),
    )

    first = BaselineRanker().rank(request_for(), candidates)
    second = BaselineRanker().rank(request_for(), tuple(reversed(candidates)))

    assert first == second


def test_hard_filtered_candidates_are_absent_and_audited() -> None:
    candidates = (
        candidate_for("wrong-revision", revision_id="catalog-rev-6"),
        candidate_for("wrong-region", region_code="KR-11"),
        candidate_for("excluded"),
        candidate_for("private", public=False),
        candidate_for("deleted", status=CandidateStatus.TOMBSTONE),
        candidate_for("missing-evidence", evidence=()),
        candidate_for("eligible"),
    )

    result = BaselineRanker().rank(request_for(excluded_refs=frozenset({"excluded"})), candidates)

    assert result.ranked_refs == ("eligible",)
    assert result.excluded_reasons == (
        ("deleted", HardFilterReason.TOMBSTONE),
        ("excluded", HardFilterReason.EXCLUDED),
        ("missing-evidence", HardFilterReason.MISSING_EVIDENCE),
        ("private", HardFilterReason.NOT_PUBLIC),
        ("wrong-region", HardFilterReason.REGION_MISMATCH),
        ("wrong-revision", HardFilterReason.REVISION_MISMATCH),
    )


def test_exact_name_match_precedes_a_higher_numeric_score() -> None:
    exact = candidate_for(
        "exact",
        name="경기전",
        topics=(),
        summary=None,
    )
    higher_score = candidate_for("higher-score")

    result = BaselineRanker().rank(
        request_for(query_text="경기전 방문", query_tokens=("방문",)),
        (higher_score, exact),
    )

    assert result.ranked_refs[:2] == ("exact", "higher-score")


def test_exact_name_match_remains_first_after_diversity_selection() -> None:
    exact = candidate_for(
        "exact",
        name="경기전",
        topics=(),
        summary=None,
    )
    higher_score = candidate_for("higher-score")

    result = BaselineRanker().rank(
        request_for(query_text="경기전 한옥 산책", query_tokens=("한옥", "산책")),
        (higher_score, exact),
    )

    assert result.board_refs[:2] == ("exact", "higher-score")


def test_canonical_duplicate_keeps_the_best_scored_version() -> None:
    lower = candidate_for("same-ref", topics=(), summary=None)
    higher = candidate_for("same-ref", name="한옥 시장 산책")

    result = BaselineRanker().rank(request_for(), (lower, higher))

    assert result.ranked_refs == ("same-ref",)
    assert result.ranked[0].candidate.name == "한옥 시장 산책"


def test_limits_each_stage_to_thirty_twelve_and_three() -> None:
    candidates = tuple(candidate_for(f"place-{index:02d}") for index in range(40))

    result = BaselineRanker().rank(request_for(), candidates)

    assert len(result.ranked) == 30
    assert len(result.proposal) == 12
    assert len(result.board) == 3


def test_pin_outside_top_thirty_is_preserved_in_proposal_and_board() -> None:
    candidates = tuple(candidate_for(f"place-{index:02d}") for index in range(40))

    result = BaselineRanker().rank(request_for(pinned_refs=("place-39",)), candidates)

    assert result.proposal_refs[0] == "place-39"
    assert result.board_refs[0] == "place-39"
    assert "place-39" not in result.ranked_refs


def test_diversity_soft_cap_prefers_a_third_candidate_from_another_category() -> None:
    candidates = (
        candidate_for("hanok-1"),
        candidate_for("hanok-2"),
        candidate_for("hanok-3"),
        candidate_for(
            "market",
            category="MARKET",
            topics=("MARKET",),
            summary="검수된 시장 소개",
        ),
    )

    result = BaselineRanker().rank(request_for(), candidates)

    assert result.board_refs == ("hanok-1", "hanok-2", "market")


def test_soft_cap_relaxes_when_it_is_the_only_way_to_fill_the_board() -> None:
    candidates = tuple(candidate_for(f"hanok-{index}") for index in range(3))

    result = BaselineRanker().rank(request_for(), candidates)

    assert result.board_refs == ("hanok-0", "hanok-1", "hanok-2")


def test_pin_and_exclude_conflict_fails_instead_of_silently_dropping_the_pin() -> None:
    baseline_request = request_for(pinned_refs=("place-1",), excluded_refs=frozenset({"place-1"}))

    try:
        BaselineRanker().rank(baseline_request, (candidate_for("place-1"),))
    except BaselineConstraintError as error:
        assert error.code == "PIN_EXCLUDED"
        assert error.refs == ("place-1",)
    else:
        raise AssertionError("expected pin/exclude conflict")


def test_missing_or_ineligible_pin_fails_instead_of_changing_user_intent() -> None:
    candidates = (candidate_for("private-pin", public=False),)

    try:
        BaselineRanker().rank(request_for(pinned_refs=("missing-pin", "private-pin")), candidates)
    except BaselineConstraintError as error:
        assert error.code == "PIN_UNAVAILABLE"
        assert error.refs == ("missing-pin", "private-pin")
    else:
        raise AssertionError("expected unavailable pin failure")
