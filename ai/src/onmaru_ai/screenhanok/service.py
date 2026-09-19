from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any, Protocol

from onmaru_ai.providers.gemini.models import (
    GeminiPrompt,
    GeminiProviderError,
    GeminiResult,
)
from onmaru_ai.screenhanok.models import ScreenHanokCandidate, ScreenHanokMatch
from onmaru_ai.screenhanok.prompt import RESPONSE_SCHEMA, build_prompt

REQUEST_TIMEOUT_SECONDS = 20.0


class GeminiResearchAdapter(Protocol):
    async def generate(
        self,
        prompt: GeminiPrompt,
        *,
        response_schema: Mapping[str, Any],
        timeout_seconds: float,
        cancellation_event: asyncio.Event | None = None,
        enable_search_grounding: bool = False,
    ) -> GeminiResult: ...


class ScreenHanokResearchService:
    def __init__(self, adapter: GeminiResearchAdapter) -> None:
        self._adapter = adapter

    async def research(
        self, candidates: list[ScreenHanokCandidate], *, request_id: str
    ) -> list[ScreenHanokMatch]:
        prompt = build_prompt(candidates, request_id=request_id)
        try:
            result = await self._adapter.generate(
                prompt,
                response_schema=RESPONSE_SCHEMA,
                timeout_seconds=REQUEST_TIMEOUT_SECONDS,
                enable_search_grounding=True,
            )
        except GeminiProviderError:
            return []

        known_place_ids = {candidate.place_id for candidate in candidates}
        grounded_uris = {chunk.uri for chunk in result.grounding_chunks}
        raw_matches = result.proposal.get("matches")
        if not isinstance(raw_matches, list):
            return []

        matches: list[ScreenHanokMatch] = []
        for raw in raw_matches:
            if not isinstance(raw, dict):
                continue
            try:
                match = ScreenHanokMatch.model_validate(raw)
            except ValueError:
                continue
            if match.place_id not in known_place_ids:
                continue
            if match.source_url not in grounded_uris:
                # The model claimed a source Google Search grounding never actually surfaced --
                # never trust an unverified citation (ADR-0010's minimum safety net).
                continue
            matches.append(match)
        return matches
