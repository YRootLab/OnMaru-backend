from __future__ import annotations

import asyncio

from onmaru_ai.providers.gemini.models import (
    GeminiFailureCode,
    GeminiProviderError,
    GeminiResult,
    GeminiUsage,
    GroundingChunk,
)
from onmaru_ai.screenhanok.models import ScreenHanokCandidate
from onmaru_ai.screenhanok.service import ScreenHanokResearchService


def usage() -> GeminiUsage:
    return GeminiUsage(
        prompt_tokens=1, output_tokens=1, thought_tokens=0, total_tokens=2, estimated_cost_micros=0
    )


def candidate(place_id: str = "p-001") -> ScreenHanokCandidate:
    return ScreenHanokCandidate(
        placeId=place_id, name="OO 고택", regionName="경북", category="HANOK"
    )


class FakeAdapter:
    def __init__(
        self, result: GeminiResult | None = None, error: GeminiProviderError | None = None
    ) -> None:
        self._result = result
        self._error = error

    async def generate(
        self,
        prompt,
        *,
        response_schema,
        timeout_seconds,
        cancellation_event=None,
        enable_search_grounding=False,
    ):
        del prompt, response_schema, timeout_seconds, cancellation_event, enable_search_grounding
        if self._error is not None:
            raise self._error
        assert self._result is not None
        return self._result


def test_keeps_matches_whose_source_url_is_actually_grounded() -> None:
    result = GeminiResult(
        proposal={
            "matches": [
                {
                    "placeId": "p-001",
                    "mediaType": "K_DRAMA",
                    "workTitle": "OO",
                    "subtitle": "실제 촬영 보도",
                    "tags": ["#사극"],
                    "sourceUrl": "https://example.com/a",
                    "sourceTitle": "기사",
                }
            ]
        },
        usage=usage(),
        model_version="gemini-test",
        grounding_chunks=(GroundingChunk(uri="https://example.com/a", title="기사"),),
    )
    service = ScreenHanokResearchService(FakeAdapter(result=result))

    matches = asyncio.run(service.research([candidate()], request_id="req-1"))

    assert len(matches) == 1
    assert matches[0].source_url == "https://example.com/a"


def test_drops_match_whose_source_url_grounding_never_actually_surfaced() -> None:
    result = GeminiResult(
        proposal={
            "matches": [
                {
                    "placeId": "p-001",
                    "mediaType": "K_DRAMA",
                    "workTitle": "OO",
                    "sourceUrl": "https://example.com/invented",
                }
            ]
        },
        usage=usage(),
        model_version="gemini-test",
        grounding_chunks=(GroundingChunk(uri="https://example.com/a", title="기사"),),
    )
    service = ScreenHanokResearchService(FakeAdapter(result=result))

    matches = asyncio.run(service.research([candidate()], request_id="req-1"))

    assert matches == []


def test_drops_match_for_a_place_id_that_was_never_a_candidate() -> None:
    result = GeminiResult(
        proposal={
            "matches": [
                {
                    "placeId": "p-injected",
                    "mediaType": "K_DRAMA",
                    "workTitle": "OO",
                    "sourceUrl": "https://example.com/a",
                }
            ]
        },
        usage=usage(),
        model_version="gemini-test",
        grounding_chunks=(GroundingChunk(uri="https://example.com/a", title="기사"),),
    )
    service = ScreenHanokResearchService(FakeAdapter(result=result))

    matches = asyncio.run(service.research([candidate("p-001")], request_id="req-1"))

    assert matches == []


def test_returns_empty_list_when_provider_fails_instead_of_raising() -> None:
    service = ScreenHanokResearchService(
        FakeAdapter(error=GeminiProviderError(GeminiFailureCode.AI_TIMEOUT))
    )

    matches = asyncio.run(service.research([candidate()], request_id="req-1"))

    assert matches == []


def test_returns_empty_list_when_proposal_has_no_matches_array() -> None:
    result = GeminiResult(
        proposal={"unexpected": "shape"},
        usage=usage(),
        model_version="gemini-test",
    )
    service = ScreenHanokResearchService(FakeAdapter(result=result))

    matches = asyncio.run(service.research([candidate()], request_id="req-1"))

    assert matches == []
