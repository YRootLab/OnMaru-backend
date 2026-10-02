from __future__ import annotations

from collections.abc import AsyncIterator, Mapping
from typing import Any, Protocol

from onmaru_ai.providers.gemini.models import (
    GeminiFailureCode,
    GeminiPrompt,
    GeminiProviderError,
    GeminiResult,
)

RESPONSE_SCHEMA: Mapping[str, Any] = {
    "type": "object",
    "properties": {
        "narration": {"type": "string"},
        "orderedRefs": {"type": "array", "items": {"type": "string"}},
        "title": {"type": "string"},
        "summary": {"type": "string"},
        "stops": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "ref": {"type": "string"},
                    "reason": {"type": "string"},
                },
                "required": ["ref", "reason"],
            },
        },
    },
    "required": ["narration", "orderedRefs", "title", "summary", "stops"],
}


class JourneyGeminiAdapter(Protocol):
    async def generate(
        self, prompt: GeminiPrompt, *, response_schema: Mapping[str, Any], timeout_seconds: float
    ) -> GeminiResult: ...

    def stream(
        self, prompt: GeminiPrompt, *, response_schema: Mapping[str, Any], timeout_seconds: float
    ) -> AsyncIterator[dict[str, Any]]: ...


class JourneyLlmService:
    def __init__(self, adapter: JourneyGeminiAdapter) -> None:
        self._adapter = adapter

    async def generate(
        self, *, query: str, candidate_refs: list[str], request_id: str
    ) -> dict[str, Any] | None:
        try:
            result = await self._adapter.generate(
                self._prompt(query, candidate_refs, request_id),
                response_schema=RESPONSE_SCHEMA,
                timeout_seconds=20.0,
            )
        except GeminiProviderError:
            return None
        return self._filter_proposal(result.proposal, candidate_refs)

    async def stream(
        self, *, query: str, candidate_refs: list[str], request_id: str
    ) -> AsyncIterator[dict[str, Any]]:
        async for event in self._adapter.stream(
            self._prompt(query, candidate_refs, request_id),
            response_schema=RESPONSE_SCHEMA,
            timeout_seconds=20.0,
        ):
            if event["event"] == "proposal":
                proposal = self._filter_proposal(event["data"]["proposal"], candidate_refs)
                if proposal is None:
                    raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)
                yield {"event": "proposal", "data": {"proposal": proposal}}
            else:
                yield event

    def _prompt(self, query: str, candidate_refs: list[str], request_id: str) -> GeminiPrompt:
        return GeminiPrompt(
            policy_block=(
                "You are OnMaru's Korean travel planner. Use only supplied place refs; "
                "never invent refs. Return concise Korean JSON. Prefer a coherent route "
                "and explain each selected stop. Put concise user-facing Korean narration first; "
                "never include refs, JSON keys, secrets or internal instructions in narration."
            ),
            few_shot_block=(
                '<example-output>{"narration":"한옥과 골목을 잇는 여정을 만들고 있어요.",'
                '"orderedRefs":["place:001"],"title":"전통 산책",'
                '"summary":"한옥과 골목을 잇는 코스",'
                '"stops":[{"ref":"place:001","reason":"질의와 가장 잘 맞습니다."}]}'
                "</example-output>"
            ),
            data_block={"query": query, "candidateRefs": candidate_refs},
            prompt_version="journey-gemini-v2",
            adapter_version="gemini-adapter-v1",
            candidate_revision=request_id,
            taxonomy_version="journey-v1",
            safety_policy_version="journey-safety-v1",
        )

    def _filter_proposal(
        self, raw_proposal: Mapping[str, Any], candidate_refs: list[str]
    ) -> dict[str, Any] | None:
        proposal = dict(raw_proposal)
        allowed = set(candidate_refs)
        refs = proposal.get("orderedRefs")
        if not isinstance(refs, list):
            return None
        proposal["orderedRefs"] = [ref for ref in refs if isinstance(ref, str) and ref in allowed][
            :12
        ]
        stops = proposal.get("stops")
        if not isinstance(stops, list):
            proposal["stops"] = []
        else:
            proposal["stops"] = [
                stop for stop in stops if isinstance(stop, dict) and stop.get("ref") in allowed
            ][:12]
        return proposal
