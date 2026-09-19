from __future__ import annotations

import pytest

from onmaru_ai.retrieval.baseline import (
    BaselineRequest,
    Candidate,
    CandidateStatus,
    Evidence,
    HardFilterReason,
    filter_reason,
    score_candidate,
)


def request_for(
    *,
    query_text: str = "전주 한옥 산책",
    query_tokens: tuple[str, ...] = ("한옥", "산책"),
    topics: tuple[str, ...] = ("HANOK", "WALK"),
    excluded_refs: frozenset[str] = frozenset(),
) -> BaselineRequest:
    return BaselineRequest(
        dataset_revision="catalog-rev-7",
        region_code="KR-45-JEONJU",
        query_text=query_text,
        query_tokens=query_tokens,
        topics=topics,
        excluded_refs=excluded_refs,
        nearby_radius_meters=1_000,
    )


def candidate_for(
    *,
    ref: str = "place-1",
    revision_id: str = "catalog-rev-7",
    region_code: str = "KR-45-JEONJU",
    name: str = "고요한 한옥",
    category: str = "HANOK",
    aliases: tuple[str, ...] = ("고요한 고택",),
    summary: str | None = "한옥 마당 산책",
    topics: tuple[str, ...] = ("HANOK",),
    curated_relations: tuple[str, ...] = ("WALK",),
    evidence: tuple[Evidence, ...] = (Evidence(source_id="source-1", text="검수된 한옥 소개"),),
    status: CandidateStatus = CandidateStatus.ACTIVE,
    public: bool = True,
    distance_meters: float | None = 500,
) -> Candidate:
    return Candidate(
        ref=ref,
        revision_id=revision_id,
        region_code=region_code,
        name=name,
        category=category,
        aliases=aliases,
        summary=summary,
        topics=topics,
        curated_relations=curated_relations,
        evidence=evidence,
        status=status,
        public=public,
        distance_meters=distance_meters,
    )


def test_scores_with_documented_v1_weights_without_reweighting() -> None:
    scored = score_candidate(candidate_for(), request_for())

    assert scored.exact_name_match is False
    assert scored.lexical == pytest.approx(1.0)
    assert scored.topic == pytest.approx(0.5)
    assert scored.evidence == pytest.approx(1.0)
    assert scored.distance == pytest.approx(0.5)
    assert scored.score == pytest.approx(0.825)


def test_marks_a_verified_name_or_alias_contained_in_the_query_as_exact() -> None:
    scored = score_candidate(
        candidate_for(name="전주한옥마을"),
        request_for(query_text="전주한옥마을을 조용히 산책하고 싶어요"),
    )

    assert scored.exact_name_match is True


@pytest.mark.parametrize(
    ("candidate", "baseline_request", "expected"),
    [
        (
            candidate_for(revision_id="catalog-rev-6"),
            request_for(),
            HardFilterReason.REVISION_MISMATCH,
        ),
        (candidate_for(region_code="KR-11"), request_for(), HardFilterReason.REGION_MISMATCH),
        (
            candidate_for(),
            request_for(excluded_refs=frozenset({"place-1"})),
            HardFilterReason.EXCLUDED,
        ),
        (candidate_for(public=False), request_for(), HardFilterReason.NOT_PUBLIC),
        (
            candidate_for(status=CandidateStatus.TOMBSTONE),
            request_for(),
            HardFilterReason.TOMBSTONE,
        ),
        (candidate_for(evidence=()), request_for(), HardFilterReason.MISSING_EVIDENCE),
        (
            candidate_for(evidence=(Evidence(source_id="", text="검수된 소개"),)),
            request_for(),
            HardFilterReason.MISSING_EVIDENCE,
        ),
        (
            candidate_for(evidence=(Evidence(source_id="source-1", text="  "),)),
            request_for(),
            HardFilterReason.MISSING_EVIDENCE,
        ),
    ],
)
def test_hard_filters_run_before_ranking(
    candidate: Candidate,
    baseline_request: BaselineRequest,
    expected: HardFilterReason,
) -> None:
    assert filter_reason(candidate, baseline_request) is expected


def test_scoring_rejects_candidates_that_failed_a_hard_filter() -> None:
    with pytest.raises(ValueError, match="REGION_MISMATCH"):
        score_candidate(candidate_for(region_code="KR-11"), request_for())


@pytest.mark.parametrize(
    "pinned_refs",
    [
        ("place-1", "place-1"),
        ("place-1", "place-2", "place-3", "place-4"),
    ],
)
def test_request_rejects_duplicate_or_more_than_three_pins(
    pinned_refs: tuple[str, ...],
) -> None:
    with pytest.raises(ValueError, match="pinned_refs"):
        BaselineRequest(
            dataset_revision="catalog-rev-7",
            region_code="KR-45-JEONJU",
            query_text="전주 한옥",
            query_tokens=("한옥",),
            topics=("HANOK",),
            pinned_refs=pinned_refs,
        )


def test_candidate_rejects_negative_distance_before_it_can_exceed_feature_bounds() -> None:
    with pytest.raises(ValueError, match="distance_meters"):
        candidate_for(distance_meters=-1)


@pytest.mark.parametrize("distance_meters", [float("nan"), float("inf")])
def test_candidate_rejects_non_finite_distance(distance_meters: float) -> None:
    with pytest.raises(ValueError, match="distance_meters"):
        candidate_for(distance_meters=distance_meters)


@pytest.mark.parametrize("nearby_radius_meters", [float("nan"), float("inf")])
def test_request_rejects_non_finite_nearby_radius(nearby_radius_meters: float) -> None:
    with pytest.raises(ValueError, match="nearby_radius_meters"):
        BaselineRequest(
            dataset_revision="catalog-rev-7",
            region_code="KR-45-JEONJU",
            query_text="전주 한옥",
            query_tokens=("한옥",),
            topics=("HANOK",),
            nearby_radius_meters=nearby_radius_meters,
        )
