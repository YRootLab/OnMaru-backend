from __future__ import annotations

import asyncio
import os

import pytest

from onmaru_ai.observability import InMemoryTelemetrySink
from onmaru_ai.providers.gemini import (
    GeminiAdapter,
    GeminiConfig,
    GeminiPricing,
    GeminiPrompt,
    HttpxGeminiTransport,
)

pytestmark = pytest.mark.skipif(
    os.environ.get("ONMARU_GEMINI_LIVE_SMOKE") != "1",
    reason="bounded Gemini live smoke is explicitly opt-in",
)


def test_bounded_authorization_key_live_smoke() -> None:
    key = os.environ["ONMARU_SECRET_GEMINI_API_KEY_CURRENT"]
    model = os.environ["ONMARU_GEMINI_MODEL"]
    adapter = GeminiAdapter(
        GeminiConfig(
            model_alias="live-smoke",
            model_name=model,
            max_output_tokens=64,
            pricing=GeminiPricing(0, 0),
        ),
        HttpxGeminiTransport(),
        api_key=key,
        telemetry_sink=InMemoryTelemetrySink(),
    )

    result = asyncio.run(
        adapter.generate(
            GeminiPrompt(
                policy_block="Return only the supplied synthetic place ID.",
                few_shot_block='input synthetic-a -> {"orderedRefs":["synthetic-a"]}',
                data_block={"candidateIds": ["synthetic-a"]},
                prompt_version="live-smoke-v1",
                adapter_version="gemini-adapter-v1",
                candidate_revision="synthetic-revision",
                taxonomy_version="synthetic-taxonomy",
                safety_policy_version="live-smoke-policy-v1",
            ),
            response_schema={
                "type": "object",
                "additionalProperties": False,
                "required": ["orderedRefs"],
                "properties": {
                    "orderedRefs": {
                        "type": "array",
                        "minItems": 1,
                        "maxItems": 1,
                        "items": {"type": "string", "enum": ["synthetic-a"]},
                    }
                },
            },
            timeout_seconds=5.0,
        )
    )

    assert result.proposal == {"orderedRefs": ["synthetic-a"]}
