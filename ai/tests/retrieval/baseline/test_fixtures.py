from __future__ import annotations

import json
from pathlib import Path
from typing import TypedDict, cast

import pytest

from onmaru_ai.retrieval.baseline import (
    BaselineRanker,
    BaselineRequest,
    Candidate,
    CandidateStatus,
    Evidence,
    HardFilterReason,
)

FIXTURE_DIRECTORY = Path(__file__).parent / "fixtures"
FIXTURE_NAMES = ("determinism.json", "hard-filters.json", "pin-diversity.json")


class RequestDocument(TypedDict):
    datasetRevision: str
    regionCode: str
    queryText: str
    queryTokens: list[str]
    topics: list[str]
    excludedRefs: list[str]
    pinnedRefs: list[str]


class EvidenceDocument(TypedDict):
    sourceId: str
    text: str


class CandidateDocument(TypedDict, total=False):
    ref: str
    revisionId: str
    regionCode: str
    name: str
    category: str
    aliases: list[str]
    summary: str | None
    topics: list[str]
    curatedRelations: list[str]
    evidence: list[EvidenceDocument]
    status: str
    public: bool


class ExpectedDocument(TypedDict):
    rankedRefs: list[str]
    proposalRefs: list[str]
    boardRefs: list[str]
    excludedReasons: list[list[str]]


class FixtureDocument(TypedDict):
    name: str
    request: RequestDocument
    candidates: list[CandidateDocument]
    inputOrders: list[list[str]]
    expected: ExpectedDocument


@pytest.mark.parametrize("fixture_name", FIXTURE_NAMES)
def test_held_out_fixture_is_reproducible_for_every_declared_input_order(
    fixture_name: str,
) -> None:
    document = cast(
        FixtureDocument,
        json.loads((FIXTURE_DIRECTORY / fixture_name).read_text(encoding="utf-8")),
    )
    request = parse_request(document["request"])
    candidates = {item["ref"]: parse_candidate(item) for item in document["candidates"]}
    expected = document["expected"]

    for input_order in document["inputOrders"]:
        result = BaselineRanker().rank(request, tuple(candidates[ref] for ref in input_order))

        assert result.ranked_refs == tuple(expected["rankedRefs"]), document["name"]
        assert result.proposal_refs == tuple(expected["proposalRefs"]), document["name"]
        assert result.board_refs == tuple(expected["boardRefs"]), document["name"]
        assert result.excluded_reasons == tuple(
            (ref, HardFilterReason(reason)) for ref, reason in expected["excludedReasons"]
        ), document["name"]


def parse_request(document: RequestDocument) -> BaselineRequest:
    return BaselineRequest(
        dataset_revision=document["datasetRevision"],
        region_code=document["regionCode"],
        query_text=document["queryText"],
        query_tokens=tuple(document["queryTokens"]),
        topics=tuple(document["topics"]),
        excluded_refs=frozenset(document["excludedRefs"]),
        pinned_refs=tuple(document["pinnedRefs"]),
    )


def parse_candidate(document: CandidateDocument) -> Candidate:
    return Candidate(
        ref=document["ref"],
        revision_id=document["revisionId"],
        region_code=document["regionCode"],
        name=document["name"],
        category=document["category"],
        aliases=tuple(document.get("aliases", ())),
        summary=document.get("summary"),
        topics=tuple(document.get("topics", ())),
        curated_relations=tuple(document.get("curatedRelations", ())),
        evidence=tuple(
            Evidence(source_id=item["sourceId"], text=item["text"])
            for item in document.get("evidence", ())
        ),
        status=CandidateStatus(document.get("status", CandidateStatus.ACTIVE)),
        public=document.get("public", True),
    )
