from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any

import pytest

from onmaru_ai.observability import InMemoryTelemetrySink
from onmaru_ai.providers.gemini import (
    GeminiAdapter,
    GeminiConfig,
    GeminiFailureCode,
    GeminiPricing,
    GeminiPrompt,
    GeminiProviderError,
    GeminiTransportRequest,
    GeminiTransportResponse,
)


class FakeTransport:
    def __init__(self, response: GeminiTransportResponse) -> None:
        self.response = response
        self.requests: list[GeminiTransportRequest] = []

    async def generate(self, request: GeminiTransportRequest) -> GeminiTransportResponse:
        self.requests.append(request)
        return self.response


class TimeoutTransport:
    async def generate(self, request: GeminiTransportRequest) -> GeminiTransportResponse:
        del request
        raise TimeoutError


class BlockingTransport:
    def __init__(self) -> None:
        self.cancelled = False
        self.started = asyncio.Event()

    async def generate(self, request: GeminiTransportRequest) -> GeminiTransportResponse:
        del request
        self.started.set()
        try:
            await asyncio.Event().wait()
            raise AssertionError("blocking transport unexpectedly resumed")
        except asyncio.CancelledError:
            self.cancelled = True
            raise


def prompt() -> GeminiPrompt:
    return GeminiPrompt(
        policy_block="공급된 후보와 근거만 사용한다.",
        few_shot_block="synthetic-place-a를 선택하는 예시",
        data_block={
            "normalizedQuery": "전주에서 조용한 한옥 산책",
            "candidates": [{"placeRef": "place-secret", "evidence": "private-evidence"}],
        },
        prompt_version="journey-prompt-v1",
        adapter_version="gemini-adapter-v1",
        candidate_revision="catalog-revision-7",
        taxonomy_version="taxonomy-v1",
        safety_policy_version="intake-policy-v1",
    )


def config() -> GeminiConfig:
    return GeminiConfig(
        model_alias="gemini-flash-approved",
        model_name="gemini-3.5-flash",
        max_output_tokens=512,
        pricing=GeminiPricing(input_micros_per_million=100_000, output_micros_per_million=400_000),
    )


def run_generate(
    adapter: GeminiAdapter,
    *,
    cancellation_event: asyncio.Event | None = None,
) -> Any:
    return asyncio.run(
        adapter.generate(
            prompt(),
            response_schema={
                "type": "object",
                "additionalProperties": False,
                "required": ["outcome", "orderedRefs"],
                "properties": {
                    "outcome": {"type": "string", "enum": ["PROPOSE_BOARD"]},
                    "orderedRefs": {"type": "array", "items": {"type": "string"}},
                },
            },
            timeout_seconds=3.0,
            cancellation_event=cancellation_event,
        )
    )


def test_builds_single_structured_output_request_and_records_sanitized_usage() -> None:
    transport = FakeTransport(
        GeminiTransportResponse(
            status_code=200,
            body={
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {"text": '{"outcome":"PROPOSE_BOARD","orderedRefs":["place-a"]}'}
                            ]
                        }
                    }
                ],
                "usageMetadata": {
                    "promptTokenCount": 120,
                    "candidatesTokenCount": 30,
                    "thoughtsTokenCount": 10,
                    "totalTokenCount": 160,
                },
                "modelVersion": "gemini-3.5-flash-2026-09",
            },
        )
    )
    sink = InMemoryTelemetrySink()
    adapter = GeminiAdapter(
        config(), transport, api_key="authorization-key-secret", telemetry_sink=sink
    )

    result = run_generate(adapter)

    assert result.proposal == {"outcome": "PROPOSE_BOARD", "orderedRefs": ["place-a"]}
    assert result.usage.prompt_tokens == 120
    assert result.usage.output_tokens == 30
    assert result.usage.thought_tokens == 10
    assert result.usage.total_tokens == 160
    assert result.usage.estimated_cost_micros == 28
    assert result.model_version == "gemini-3.5-flash-2026-09"

    request = transport.requests[0]
    assert request.url.endswith("/v1beta/models/gemini-3.5-flash:generateContent")
    assert request.headers == {
        "Content-Type": "application/json",
        "x-goog-api-key": "authorization-key-secret",
    }
    assert request.timeout_seconds == 3.0
    assert "tools" not in request.body
    assert request.body["generationConfig"] == {
        "temperature": 0,
        "maxOutputTokens": 512,
        "responseMimeType": "application/json",
        "responseJsonSchema": {
            "type": "object",
            "additionalProperties": False,
            "required": ["outcome", "orderedRefs"],
            "properties": {
                "outcome": {"type": "string", "enum": ["PROPOSE_BOARD"]},
                "orderedRefs": {"type": "array", "items": {"type": "string"}},
            },
        },
    }
    serialized_request = str(request.body)
    assert "공급된 후보와 근거만 사용한다." in serialized_request
    assert "synthetic-place-a" in serialized_request
    assert "private-evidence" in serialized_request

    assert len(sink.events) == 1
    attributes: Mapping[str, str] = sink.events[0].attributes
    assert attributes["ai.provider"] == "gemini"
    assert attributes["ai.model_alias"] == "gemini-flash-approved"
    assert attributes["ai.usage.total_tokens"] == "160"
    assert attributes["ai.usage.estimated_cost_micros"] == "28"
    forbidden = " ".join(attributes) + " " + " ".join(attributes.values())
    assert "authorization-key-secret" not in forbidden
    assert "전주에서 조용한 한옥 산책" not in forbidden
    assert "private-evidence" not in forbidden
    assert "prompt" not in attributes


@pytest.mark.parametrize(
    ("transport", "expected"),
    [
        (TimeoutTransport(), GeminiFailureCode.AI_TIMEOUT),
        (
            FakeTransport(
                GeminiTransportResponse(status_code=429, body={"error": {"message": "quota"}})
            ),
            GeminiFailureCode.AI_QUOTA_EXCEEDED,
        ),
        (
            FakeTransport(
                GeminiTransportResponse(status_code=503, body={"error": {"message": "down"}})
            ),
            GeminiFailureCode.AI_SERVICE_UNAVAILABLE,
        ),
        (
            FakeTransport(GeminiTransportResponse(status_code=200, body={"candidates": []})),
            GeminiFailureCode.AI_INVALID_RESPONSE,
        ),
        (
            FakeTransport(
                GeminiTransportResponse(
                    status_code=200,
                    body={"candidates": [{"content": {"parts": [{"text": "not-json"}]}}]},
                )
            ),
            GeminiFailureCode.AI_INVALID_RESPONSE,
        ),
    ],
)
def test_maps_provider_failures_to_typed_codes(transport: Any, expected: GeminiFailureCode) -> None:
    adapter = GeminiAdapter(
        config(), transport, api_key="secret", telemetry_sink=InMemoryTelemetrySink()
    )

    with pytest.raises(GeminiProviderError) as captured:
        run_generate(adapter)

    assert captured.value.code is expected


def test_cancellation_stops_in_flight_transport() -> None:
    async def scenario() -> tuple[GeminiFailureCode, bool]:
        transport = BlockingTransport()
        adapter = GeminiAdapter(
            config(), transport, api_key="secret", telemetry_sink=InMemoryTelemetrySink()
        )
        cancellation = asyncio.Event()
        task = asyncio.create_task(
            adapter.generate(
                prompt(),
                response_schema={"type": "object"},
                timeout_seconds=3.0,
                cancellation_event=cancellation,
            )
        )
        await transport.started.wait()
        cancellation.set()
        with pytest.raises(GeminiProviderError) as captured:
            await task
        return captured.value.code, transport.cancelled

    code, transport_cancelled = asyncio.run(scenario())

    assert code is GeminiFailureCode.CANCELLED
    assert transport_cancelled


def test_parent_task_cancellation_stops_transport_and_preserves_cancelled_error() -> None:
    async def scenario() -> tuple[bool, bool]:
        transport = BlockingTransport()
        adapter = GeminiAdapter(
            config(), transport, api_key="secret", telemetry_sink=InMemoryTelemetrySink()
        )
        task = asyncio.create_task(
            adapter.generate(prompt(), response_schema={"type": "object"}, timeout_seconds=3.0)
        )
        await transport.started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        return task.cancelled(), transport.cancelled

    task_cancelled, transport_cancelled = asyncio.run(scenario())

    assert task_cancelled
    assert transport_cancelled


def test_invalid_json_still_records_billed_usage() -> None:
    transport = FakeTransport(
        GeminiTransportResponse(
            status_code=200,
            body={
                "candidates": [{"content": {"parts": [{"text": "not-json"}]}}],
                "usageMetadata": {
                    "promptTokenCount": 120,
                    "candidatesTokenCount": 30,
                    "thoughtsTokenCount": 10,
                    "totalTokenCount": 160,
                },
            },
        )
    )
    sink = InMemoryTelemetrySink()
    adapter = GeminiAdapter(config(), transport, api_key="secret", telemetry_sink=sink)

    with pytest.raises(GeminiProviderError) as captured:
        run_generate(adapter)

    assert captured.value.code is GeminiFailureCode.AI_INVALID_RESPONSE
    assert sink.events[0].attributes["ai.usage.total_tokens"] == "160"
    assert sink.events[0].attributes["ai.usage.estimated_cost_micros"] == "28"
