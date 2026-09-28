from __future__ import annotations

from collections.abc import Mapping
from typing import Any, Protocol

from onmaru_ai.providers.gemini.models import GeminiPrompt, GeminiProviderError, GeminiResult

RESPONSE_SCHEMA: Mapping[str, Any] = {
    "type": "OBJECT",
    "properties": {
        "orderedRefs": {"type": "ARRAY", "items": {"type": "STRING"}},
        "title": {"type": "STRING"},
        "summary": {"type": "STRING"},
        "stops": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "ref": {"type": "STRING"},
                    "reason": {"type": "STRING"},
                },
                "required": ["ref", "reason"],
            },
        },
    },
    "required": ["orderedRefs", "title", "summary", "stops"],
}


class JourneyGeminiAdapter(Protocol):
    async def generate(
        self, prompt: GeminiPrompt, *, response_schema: Mapping[str, Any], timeout_seconds: float
    ) -> GeminiResult: ...


class JourneyLlmService:
    def __init__(self, adapter: JourneyGeminiAdapter) -> None:
        self._adapter = adapter

    async def generate(
        self, *, query: str, candidate_refs: list[str], request_id: str
    ) -> dict[str, Any] | None:
        prompt = GeminiPrompt(
            policy_block=(
                "You are OnMaru's Korean travel planner. Use only supplied place refs; "
                "never invent refs. Return concise Korean JSON. Prefer a coherent route "
                "and explain each selected stop."
            ),
            few_shot_block=(
                '<example-output>{"orderedRefs":["place:001"],"title":"전통 산책",'
                '"summary":"한옥과 골목을 잇는 코스",'
                '"stops":[{"ref":"place:001","reason":"질의와 가장 잘 맞습니다."}]}'
                "</example-output>"
            ),
            data_block={"query": query, "candidateRefs": candidate_refs},
            prompt_version="journey-gemini-v1",
            adapter_version="gemini-adapter-v1",
            candidate_revision=request_id,
            taxonomy_version="journey-v1",
            safety_policy_version="journey-safety-v1",
        )
        try:
            result = await self._adapter.generate(
                prompt, response_schema=RESPONSE_SCHEMA, timeout_seconds=20.0
            )
        except GeminiProviderError:
            return None
        proposal = dict(result.proposal)
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
        return proposal
