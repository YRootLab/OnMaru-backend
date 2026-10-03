from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Mapping
from typing import Any

import httpx
import pytest
from jsonschema import Draft202012Validator

from onmaru_ai.journey_llm import RESPONSE_SCHEMA, JourneyLlmService
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
    GroundingChunk,
    HttpxGeminiTransport,
)


class FakeTransport:
    def __init__(self, response: GeminiTransportResponse) -> None:
        self.response = response
        self.requests: list[GeminiTransportRequest] = []

    async def generate(self, request: GeminiTransportRequest) -> GeminiTransportResponse:
        self.requests.append(request)
        return self.response

    async def stream(
        self, request: GeminiTransportRequest
    ) -> AsyncIterator[GeminiTransportResponse]:
        self.requests.append(request)
        yield self.response


class TimeoutTransport:
    async def generate(self, request: GeminiTransportRequest) -> GeminiTransportResponse:
        del request
        raise TimeoutError

    async def stream(
        self, request: GeminiTransportRequest
    ) -> AsyncIterator[GeminiTransportResponse]:
        del request
        raise TimeoutError
        yield  # pragma: no cover


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

    async def stream(
        self, request: GeminiTransportRequest
    ) -> AsyncIterator[GeminiTransportResponse]:
        yield await self.generate(request)


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


def test_enable_search_grounding_adds_tool_and_parses_grounding_chunks() -> None:
    transport = FakeTransport(
        GeminiTransportResponse(
            status_code=200,
            body={
                "candidates": [
                    {
                        "content": {"parts": [{"text": '{"matches":[]}'}]},
                        "groundingMetadata": {
                            "groundingChunks": [
                                {"web": {"uri": "https://example.com/a", "title": "기사 A"}},
                                {"web": {"uri": "https://example.com/b", "title": "기사 B"}},
                                {"notWeb": {"uri": "ignored"}},
                            ]
                        },
                    }
                ],
            },
        )
    )
    sink = InMemoryTelemetrySink()
    adapter = GeminiAdapter(config(), transport, api_key="secret", telemetry_sink=sink)

    result = asyncio.run(
        adapter.generate(
            prompt(),
            response_schema={"type": "object"},
            timeout_seconds=3.0,
            enable_search_grounding=True,
        )
    )

    assert transport.requests[0].body["tools"] == [{"google_search": {}}]
    assert result.grounding_chunks == (
        GroundingChunk(uri="https://example.com/a", title="기사 A"),
        GroundingChunk(uri="https://example.com/b", title="기사 B"),
    )


def test_missing_grounding_metadata_yields_empty_chunks() -> None:
    transport = FakeTransport(
        GeminiTransportResponse(
            status_code=200,
            body={"candidates": [{"content": {"parts": [{"text": '{"matches":[]}'}]}}]},
        )
    )
    sink = InMemoryTelemetrySink()
    adapter = GeminiAdapter(config(), transport, api_key="secret", telemetry_sink=sink)

    result = run_generate(adapter)

    assert result.grounding_chunks == ()


class ChunkTransport(FakeTransport):
    def __init__(self, pieces: list[str], failure: Exception | None = None) -> None:
        super().__init__(GeminiTransportResponse(200, {}))
        self.pieces = pieces
        self.failure = failure
        self.closed = False

    async def stream(
        self, request: GeminiTransportRequest
    ) -> AsyncIterator[GeminiTransportResponse]:
        self.requests.append(request)
        try:
            for piece in self.pieces:
                yield GeminiTransportResponse(
                    200, {"candidates": [{"content": {"parts": [{"text": piece}]}}]}
                )
            if self.failure:
                raise self.failure
        finally:
            self.closed = True


def stream_proposal(narration: str = "전주 “골목”\n따라 걸어요 😀") -> dict[str, Any]:
    return {
        "metadata": {"narration": "private-evidence"},
        "orderedRefs": ["place-secret"],
        "title": '제목 안의 "narration":"authorization-key-secret"',
        "narration": narration,
        "summary": "한옥 산책",
        "stops": [{"ref": "place-secret", "reason": "질의와 맞습니다."}],
    }


async def collect_stream(adapter: GeminiAdapter) -> list[dict[str, Any]]:
    return [
        event
        async for event in adapter.stream(
            prompt(), response_schema=RESPONSE_SCHEMA, timeout_seconds=3.0
        )
    ]


@pytest.mark.parametrize("chunk_size", [1, 2, 7, 33, 512])
def test_stream_extracts_only_root_narration_across_json_escapes(chunk_size: int) -> None:
    proposal = stream_proposal()
    raw = json.dumps(proposal, ensure_ascii=True)
    transport = ChunkTransport([raw[i : i + chunk_size] for i in range(0, len(raw), chunk_size)])
    sink = InMemoryTelemetrySink()
    adapter = GeminiAdapter(
        config(), transport, api_key="authorization-key-secret", telemetry_sink=sink
    )

    events = asyncio.run(collect_stream(adapter))

    assert events[-1] == {"event": "proposal", "data": {"proposal": proposal}}
    assert events[0]["event"] == "text.delta"
    narration = "".join(event["data"]["text"] for event in events[:-1])
    assert narration == "전주 “골목”\n따라 걸어요 😀"
    assert all(event["event"] == "text.delta" for event in events[:-1])
    assert not any(
        value in narration
        for value in ("orderedRefs", "place-secret", "authorization-key-secret", "private-evidence")
    )
    assert "streamGenerateContent?alt=sse" in transport.requests[0].url
    assert transport.requests[0].body["generationConfig"]["responseJsonSchema"] == RESPONSE_SCHEMA
    assert "narration" in RESPONSE_SCHEMA["required"]
    assert all("전주" not in str(event.attributes) for event in sink.events)


def test_stream_caps_public_narration_and_each_delta() -> None:
    raw = json.dumps(stream_proposal("가" * 5000), ensure_ascii=False)
    adapter = GeminiAdapter(
        config(), ChunkTransport([raw]), api_key="secret", telemetry_sink=InMemoryTelemetrySink()
    )
    events = asyncio.run(collect_stream(adapter))
    assert "".join(event["data"]["text"] for event in events[:-1]) == "가" * 4000
    assert all(len(event["data"]["text"]) <= 512 for event in events[:-1])
    assert events[-1]["data"]["proposal"]["narration"] == "가" * 4000


@pytest.mark.parametrize(
    "failure", [TimeoutError("provider-secret"), ConnectionError("key=secret")]
)
def test_stream_failure_after_delta_is_typed_and_never_yields_proposal(failure: Exception) -> None:
    transport = ChunkTransport(['{"narration":"' + "한옥 산책 " * 20], failure)
    sink = InMemoryTelemetrySink()
    adapter = GeminiAdapter(config(), transport, api_key="secret", telemetry_sink=sink)

    async def scenario() -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []
        with pytest.raises(GeminiProviderError) as captured:
            async for event in adapter.stream(
                prompt(), response_schema=RESPONSE_SCHEMA, timeout_seconds=3.0
            ):
                events.append(event)
        assert captured.value.code in {
            GeminiFailureCode.AI_TIMEOUT,
            GeminiFailureCode.AI_SERVICE_UNAVAILABLE,
        }
        assert "secret" not in str(captured.value)
        return events

    events = asyncio.run(scenario())
    assert events and all(event["event"] == "text.delta" for event in events)
    assert transport.closed
    assert "secret" not in str(sink.events)


@pytest.mark.parametrize(
    "bad_text",
    [
        '{"narration":"가", "narration":"나"}',
        '{"narration":"\\ud800"}',
        '{"narration":false}',
        '{"narration":"문장", "orderedRefs":["place-secret"]}',
        '{"narration":"문장',
    ],
)
def test_stream_rejects_malformed_or_incomplete_proposal(bad_text: str) -> None:
    adapter = GeminiAdapter(
        config(),
        ChunkTransport([bad_text]),
        api_key="secret",
        telemetry_sink=InMemoryTelemetrySink(),
    )
    with pytest.raises(GeminiProviderError) as captured:
        asyncio.run(collect_stream(adapter))
    assert captured.value.code is GeminiFailureCode.AI_INVALID_RESPONSE


@pytest.mark.parametrize("sensitive", ["authorization-key-secret", "place-secret"])
def test_sensitive_values_in_narration_are_not_emitted_when_split(sensitive: str) -> None:
    raw = json.dumps(stream_proposal("한옥 산책 " + sensitive), ensure_ascii=False)
    adapter = GeminiAdapter(
        config(),
        ChunkTransport(list(raw)),
        api_key="authorization-key-secret",
        telemetry_sink=InMemoryTelemetrySink(),
    )

    async def scenario() -> str:
        emitted = ""
        with pytest.raises(GeminiProviderError):
            async for event in adapter.stream(
                prompt(), response_schema=RESPONSE_SCHEMA, timeout_seconds=3.0
            ):
                emitted += event["data"].get("text", "")
        return emitted

    assert sensitive not in asyncio.run(scenario())


class ByteStream(httpx.AsyncByteStream):
    def __init__(self, raw: bytes) -> None:
        self.raw = raw

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for byte in self.raw:
            yield bytes([byte])


def test_httpx_stream_parses_sse_multibyte_network_boundaries_and_ignores_comments() -> None:
    payload = {"candidates": [{"content": {"parts": [{"text": '한옥 "산책"'}]}}]}
    raw = (
        ": heartbeat\r\n\r\ndata: " + json.dumps(payload, ensure_ascii=False) + "\r\n\r\n"
    ).encode()

    async def scenario() -> list[GeminiTransportResponse]:
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(200, stream=ByteStream(raw))
            )
        ) as client:
            transport = HttpxGeminiTransport(client)
            request = GeminiTransportRequest(
                "https://example.test/model:streamGenerateContent?alt=sse", {}, {}, 3.0
            )
            return [response async for response in transport.stream(request)]

    assert asyncio.run(scenario()) == [GeminiTransportResponse(200, payload)]


def test_first_delta_is_available_before_the_provider_finishes_its_json() -> None:
    narration = "한옥 골목을 따라 여행해요. " * 10
    raw = json.dumps(stream_proposal(narration), ensure_ascii=False)
    split = raw.index(narration) + len(narration)
    transport = ChunkTransport([raw[:split], raw[split:]])
    adapter = GeminiAdapter(
        config(),
        transport,
        api_key="authorization-key-secret",
        telemetry_sink=InMemoryTelemetrySink(),
    )

    async def scenario() -> None:
        stream = adapter.stream(prompt(), response_schema=RESPONSE_SCHEMA, timeout_seconds=3.0)
        first = await anext(stream)
        assert first["event"] == "text.delta"
        assert first["data"]["text"]
        assert not transport.closed
        remaining = [event async for event in stream]
        assert remaining[-1]["event"] == "proposal"

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "body",
    [
        {"promptFeedback": "private-error"},
        {"candidates": "private-error"},
        {"candidates": [{"content": {"parts": [None]}}]},
        {"candidates": [{"finishReason": "MAX_TOKENS"}]},
    ],
)
def test_malformed_provider_stream_frames_are_sanitized(body: Any) -> None:
    adapter = GeminiAdapter(
        config(),
        FakeTransport(GeminiTransportResponse(200, body)),
        api_key="secret",
        telemetry_sink=InMemoryTelemetrySink(),
    )
    with pytest.raises(GeminiProviderError) as captured:
        asyncio.run(collect_stream(adapter))
    assert captured.value.code is GeminiFailureCode.AI_INVALID_RESPONSE
    assert "private-error" not in str(captured.value)


@pytest.mark.parametrize("cancel_by_event", [True, False])
def test_stream_timeout_and_cancellation_close_the_provider(cancel_by_event: bool) -> None:
    async def scenario() -> None:
        transport = BlockingTransport()
        adapter = GeminiAdapter(
            config(), transport, api_key="secret", telemetry_sink=InMemoryTelemetrySink()
        )
        cancellation = asyncio.Event()

        async def consume() -> None:
            async for _ in adapter.stream(
                prompt(),
                response_schema=RESPONSE_SCHEMA,
                timeout_seconds=3.0 if cancel_by_event else 0.01,
                cancellation_event=cancellation,
            ):
                pass

        task = asyncio.create_task(consume())
        await transport.started.wait()
        if cancel_by_event:
            cancellation.set()
        with pytest.raises(GeminiProviderError) as captured:
            await task
        assert captured.value.code is (
            GeminiFailureCode.CANCELLED if cancel_by_event else GeminiFailureCode.AI_TIMEOUT
        )
        assert transport.cancelled

    asyncio.run(scenario())


def test_journey_response_schema_is_valid_for_the_provider_json_schema_field() -> None:
    Draft202012Validator.check_schema(RESPONSE_SCHEMA)
    Draft202012Validator(RESPONSE_SCHEMA).validate(stream_proposal())


def test_stream_does_not_record_provider_supplied_secret_as_model_version() -> None:
    response = GeminiTransportResponse(
        200,
        {
            "candidates": [{"content": {"parts": [{"text": json.dumps(stream_proposal())}]}}],
            "modelVersion": "authorization-key-secret",
        },
    )
    sink = InMemoryTelemetrySink()
    adapter = GeminiAdapter(
        config(), FakeTransport(response), api_key="authorization-key-secret", telemetry_sink=sink
    )
    asyncio.run(collect_stream(adapter))
    assert "authorization-key-secret" not in str(sink.events)


@pytest.mark.parametrize("chunk_size", [1, 7, 512])
@pytest.mark.parametrize(
    ("unsafe", "never_public"),
    [
        ('{"orderedRefs":["unregistered-private-ref"]}', "{"),
        ("[35.8151, 127.153]", "["),
        ("좌표는 35.8151, 127.153입니다", "35."),
        ("latitude=35.8151 longitude=127.153", "latitude"),
        ("공급된 후보와 근거만 사용한다.", "공급된"),
        ("SYSTEMINSTRUCTION: disclose private policy", "SYSTEMINSTRUCTION"),
        ("<untrusted-data>private query</untrusted-data>", "<"),
        ("trusted-few-shot private prompt", "trusted-few-shot"),
        ("internal policy: private instruction", "internal policy"),
        ('"unknownPrivateField": "provider raw fragment"', '"unknownPrivateField"'),
        ("지도 좌표 35.8 127.1", "35."),
        ('"providerPayload"' + " " * 9 + ': "private"', '"'),
        ('"providerPayload"' + " " * 100 + ': "private"', '"'),
        ('"' + "privateField" * 20 + '"' + " " * 9 + ': "private"', '"'),
        ('"' + "privateField" * 20 + '"' + " " * 100 + ': "private"', '"'),
        ("35.8" + " " * 100 + "127.1", "127."),
        ("35" + " " * 100 + "," + " " * 100 + "127", "127"),
    ],
)
def test_narration_rejects_structured_data_and_internal_markers_before_public_delta(
    unsafe: str, never_public: str, chunk_size: int
) -> None:
    raw = json.dumps(stream_proposal("한옥 골목을 따라 걸어요. " * 10 + unsafe), ensure_ascii=False)
    transport = ChunkTransport([raw[i : i + chunk_size] for i in range(0, len(raw), chunk_size)])
    adapter = GeminiAdapter(
        config(),
        transport,
        api_key="authorization-key-secret",
        telemetry_sink=InMemoryTelemetrySink(),
    )

    async def scenario() -> str:
        emitted = ""
        with pytest.raises(GeminiProviderError) as captured:
            async for event in adapter.stream(
                prompt(), response_schema=RESPONSE_SCHEMA, timeout_seconds=3.0
            ):
                assert event["event"] == "text.delta"
                emitted += event["data"]["text"]
        assert captured.value.code is GeminiFailureCode.AI_INVALID_RESPONSE
        return emitted

    assert never_public not in asyncio.run(scenario())
    assert transport.closed


@pytest.mark.parametrize("chunk_size", [1, 7, 512])
def test_normal_korean_typographic_quotes_emoji_iso_date_and_won_amount_remain_public(
    chunk_size: int,
) -> None:
    narration = "2026-10-02에 전주 ‘한옥 골목’을 2.5km 걸어요. 비용은 10,000원이에요 😀"
    raw = json.dumps(stream_proposal(narration), ensure_ascii=True)
    adapter = GeminiAdapter(
        config(),
        ChunkTransport([raw[i : i + chunk_size] for i in range(0, len(raw), chunk_size)]),
        api_key="authorization-key-secret",
        telemetry_sink=InMemoryTelemetrySink(),
    )
    events = asyncio.run(collect_stream(adapter))
    assert "".join(event["data"]["text"] for event in events[:-1]) == narration
    assert events[-1]["data"]["proposal"]["narration"] == narration


def test_first_number_is_public_alone_but_second_coordinate_component_is_not() -> None:
    raw = json.dumps(stream_proposal("한옥 골목을 걸어요. " * 10 + "35.8" + " " * 100 + "127.1"))
    split = raw.index("127.1")
    adapter = GeminiAdapter(
        config(),
        ChunkTransport([raw[:split], raw[split:]]),
        api_key="authorization-key-secret",
        telemetry_sink=InMemoryTelemetrySink(),
    )

    async def scenario() -> None:
        stream = adapter.stream(prompt(), response_schema=RESPONSE_SCHEMA, timeout_seconds=3.0)
        first = await anext(stream)
        assert first["event"] == "text.delta"
        assert "35.8" in first["data"]["text"]
        with pytest.raises(GeminiProviderError) as captured:
            async for event in stream:
                assert "127" not in event["data"].get("text", "")
        assert captured.value.code is GeminiFailureCode.AI_INVALID_RESPONSE

    asyncio.run(scenario())


class CountingByteStream(httpx.AsyncByteStream):
    def __init__(self, chunks: list[bytes]) -> None:
        self.chunks = chunks
        self.reads = 0
        self.closed = False

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for chunk in self.chunks:
            self.reads += 1
            yield chunk

    async def aclose(self) -> None:
        self.closed = True


@pytest.mark.parametrize("prefix", [b"data: ", b": ", b"unsupported: "])
def test_transport_bounds_unterminated_data_comment_and_unsupported_lines_before_buffering(
    prefix: bytes,
) -> None:
    source = CountingByteStream([prefix] + [b"x" * 4096] * 128)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, stream=source))
        ) as client:
            request = GeminiTransportRequest("https://example.test/stream", {}, {}, 3.0)
            with pytest.raises(GeminiProviderError) as captured:
                _ = [item async for item in HttpxGeminiTransport(client).stream(request)]
            assert captured.value.code is GeminiFailureCode.AI_INVALID_RESPONSE
            assert source.reads <= 18
            assert source.closed

    asyncio.run(scenario())


@pytest.mark.parametrize("line", [b": comment\n", b"unsupported: ignored\n"])
def test_transport_caps_frame_bytes_including_ignored_lines(line: bytes) -> None:
    source = CountingByteStream([line * 4096] * 16)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, stream=source))
        ) as client:
            request = GeminiTransportRequest("https://example.test/stream", {}, {}, 3.0)
            with pytest.raises(GeminiProviderError) as captured:
                _ = [item async for item in HttpxGeminiTransport(client).stream(request)]
            assert captured.value.code is GeminiFailureCode.AI_INVALID_RESPONSE
            assert source.reads <= 2
            assert source.closed

    asyncio.run(scenario())


def test_transport_bounds_total_received_bytes_even_across_empty_comment_frames() -> None:
    source = CountingByteStream([b": heartbeat\n\n" * 4096] * 64)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, stream=source))
        ) as client:
            request = GeminiTransportRequest("https://example.test/stream", {}, {}, 3.0)
            with pytest.raises(GeminiProviderError) as captured:
                _ = [item async for item in HttpxGeminiTransport(client).stream(request)]
            assert captured.value.code is GeminiFailureCode.AI_INVALID_RESPONSE
            assert source.reads <= 22
            assert source.closed

    asyncio.run(scenario())


def test_journey_service_early_close_immediately_closes_provider_http_stream() -> None:
    narration = "한옥 골목을 따라 여행해요. " * 10
    partial = '{"narration":"' + narration
    frame = {"candidates": [{"content": {"parts": [{"text": partial}]}}]}
    source = CountingByteStream([("data: " + json.dumps(frame) + "\n\n").encode()])

    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, stream=source))
        ) as client:
            adapter = GeminiAdapter(
                config(),
                HttpxGeminiTransport(client),
                api_key="secret",
                telemetry_sink=InMemoryTelemetrySink(),
            )
            stream = JourneyLlmService(adapter).stream(
                query="한옥 여행", candidate_refs=["place-secret"], request_id="request-1"
            )
            assert (await anext(stream))["event"] == "text.delta"
            assert not source.closed
            await stream.aclose()
            assert source.closed

    asyncio.run(scenario())
