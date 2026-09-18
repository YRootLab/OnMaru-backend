from __future__ import annotations

import asyncio
import contextlib
import json
import re
from collections.abc import Mapping
from typing import Any

from onmaru_ai.observability import TelemetryEvent, TelemetrySink
from onmaru_ai.providers.gemini.models import (
    GeminiConfig,
    GeminiFailureCode,
    GeminiPrompt,
    GeminiProviderError,
    GeminiResult,
    GeminiTransportRequest,
    GeminiTransportResponse,
    GeminiUsage,
    GroundingChunk,
)
from onmaru_ai.providers.gemini.transport import GeminiTransport

MODEL_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")


class GeminiAdapter:
    def __init__(
        self,
        config: GeminiConfig,
        transport: GeminiTransport,
        *,
        api_key: str,
        telemetry_sink: TelemetrySink,
    ) -> None:
        if not config.model_alias.strip():
            raise ValueError("Gemini model alias must not be blank")
        if MODEL_NAME_PATTERN.fullmatch(config.model_name) is None:
            raise ValueError("Gemini model name contains unsupported characters")
        if config.max_output_tokens < 1:
            raise ValueError("Gemini max output tokens must be positive")
        if not config.endpoint.startswith("https://"):
            raise ValueError("Gemini endpoint must use HTTPS")
        if not api_key.strip():
            raise ValueError("Gemini authorization key must not be blank")
        self._config = config
        self._transport = transport
        self._api_key = api_key
        self._telemetry_sink = telemetry_sink

    async def generate(
        self,
        prompt: GeminiPrompt,
        *,
        response_schema: Mapping[str, Any],
        timeout_seconds: float,
        cancellation_event: asyncio.Event | None = None,
        enable_search_grounding: bool = False,
    ) -> GeminiResult:
        if timeout_seconds <= 0:
            raise ValueError("Gemini timeout must be positive")
        request = self._request(prompt, response_schema, timeout_seconds, enable_search_grounding)
        usage: GeminiUsage | None = None
        try:
            response = await self._send(request, timeout_seconds, cancellation_event)
            usage = self._usage_from_response(response)
            result = self._parse(response, usage)
        except GeminiProviderError as error:
            self._record(prompt, outcome=error.code.value, usage=usage)
            raise
        except TimeoutError as error:
            failure = GeminiProviderError(GeminiFailureCode.AI_TIMEOUT)
            self._record(prompt, outcome=failure.code.value)
            raise failure from error
        except ConnectionError as error:
            failure = GeminiProviderError(GeminiFailureCode.AI_SERVICE_UNAVAILABLE)
            self._record(prompt, outcome=failure.code.value)
            raise failure from error

        self._record(
            prompt, outcome="SUCCESS", usage=result.usage, model_version=result.model_version
        )
        return result

    def _request(
        self,
        prompt: GeminiPrompt,
        response_schema: Mapping[str, Any],
        timeout_seconds: float,
        enable_search_grounding: bool = False,
    ) -> GeminiTransportRequest:
        data = json.dumps(
            prompt.data_block, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )
        few_shot = f"<trusted-few-shot>\n{prompt.few_shot_block}\n</trusted-few-shot>"
        body: dict[str, Any] = {
            "systemInstruction": {"parts": [{"text": prompt.policy_block}]},
            "contents": [
                {
                    "role": "user",
                    "parts": [
                        {"text": few_shot},
                        {"text": f"<untrusted-data>\n{data}\n</untrusted-data>"},
                    ],
                }
            ],
            "generationConfig": {
                "temperature": 0,
                "maxOutputTokens": self._config.max_output_tokens,
                "responseMimeType": "application/json",
                "responseJsonSchema": dict(response_schema),
            },
        }
        if enable_search_grounding:
            body["tools"] = [{"google_search": {}}]
        return GeminiTransportRequest(
            url=(
                f"{self._config.endpoint.rstrip('/')}/v1beta/models/"
                f"{self._config.model_name}:generateContent"
            ),
            headers={
                "Content-Type": "application/json",
                "x-goog-api-key": self._api_key,
            },
            body=body,
            timeout_seconds=timeout_seconds,
        )

    async def _send(
        self,
        request: GeminiTransportRequest,
        timeout_seconds: float,
        cancellation_event: asyncio.Event | None,
    ) -> GeminiTransportResponse:
        if cancellation_event is not None and cancellation_event.is_set():
            raise GeminiProviderError(GeminiFailureCode.CANCELLED)

        transport_task = asyncio.create_task(self._transport.generate(request))
        cancellation_task = (
            asyncio.create_task(cancellation_event.wait())
            if cancellation_event is not None
            else None
        )
        try:
            async with asyncio.timeout(timeout_seconds):
                if cancellation_task is None:
                    return await transport_task
                done, _ = await asyncio.wait(
                    {transport_task, cancellation_task},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if cancellation_task in done:
                    transport_task.cancel()
                    with contextlib.suppress(asyncio.CancelledError):
                        await transport_task
                    raise GeminiProviderError(GeminiFailureCode.CANCELLED)
                return await transport_task
        except asyncio.CancelledError:
            transport_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await transport_task
            raise
        except TimeoutError:
            transport_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await transport_task
            raise
        finally:
            if cancellation_task is not None:
                cancellation_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await cancellation_task

    def _parse(self, response: GeminiTransportResponse, usage: GeminiUsage | None) -> GeminiResult:
        if response.status_code == 429:
            raise GeminiProviderError(GeminiFailureCode.AI_QUOTA_EXCEEDED)
        if response.status_code in {408, 504}:
            raise GeminiProviderError(GeminiFailureCode.AI_TIMEOUT)
        if response.status_code >= 500 or response.status_code in {401, 403}:
            raise GeminiProviderError(GeminiFailureCode.AI_SERVICE_UNAVAILABLE)
        if response.status_code != 200 or not isinstance(response.body, dict):
            raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)

        try:
            candidate = response.body["candidates"][0]
            text = candidate["content"]["parts"][0]["text"]
            proposal = json.loads(text)
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
            raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE) from error
        if not isinstance(proposal, dict):
            raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)

        usage = usage or self._usage(None)
        model_version = response.body.get("modelVersion", self._config.model_name)
        if not isinstance(model_version, str):
            model_version = self._config.model_name
        return GeminiResult(
            proposal=proposal,
            usage=usage,
            model_version=model_version,
            grounding_chunks=self._grounding_chunks(candidate),
        )

    def _grounding_chunks(self, candidate: Any) -> tuple[GroundingChunk, ...]:
        if not isinstance(candidate, dict):
            return ()
        metadata = candidate.get("groundingMetadata")
        if not isinstance(metadata, dict):
            return ()
        chunks = metadata.get("groundingChunks")
        if not isinstance(chunks, list):
            return ()
        result: list[GroundingChunk] = []
        for chunk in chunks:
            if not isinstance(chunk, dict):
                continue
            web = chunk.get("web")
            if not isinstance(web, dict):
                continue
            uri = web.get("uri")
            title = web.get("title")
            if isinstance(uri, str) and uri.strip():
                result.append(
                    GroundingChunk(uri=uri, title=title if isinstance(title, str) else "")
                )
        return tuple(result)

    def _usage_from_response(self, response: GeminiTransportResponse) -> GeminiUsage | None:
        if not isinstance(response.body, dict) or not isinstance(
            response.body.get("usageMetadata"), dict
        ):
            return None
        return self._usage(response.body["usageMetadata"])

    def _usage(self, raw_usage: Any) -> GeminiUsage:
        usage = raw_usage if isinstance(raw_usage, dict) else {}
        prompt_tokens = non_negative_int(usage.get("promptTokenCount"))
        output_tokens = non_negative_int(usage.get("candidatesTokenCount"))
        thought_tokens = non_negative_int(usage.get("thoughtsTokenCount"))
        total_tokens = non_negative_int(usage.get("totalTokenCount"))
        pricing = self._config.pricing
        estimated_cost = (
            prompt_tokens * pricing.input_micros_per_million
            + (output_tokens + thought_tokens) * pricing.output_micros_per_million
        ) // 1_000_000
        return GeminiUsage(
            prompt_tokens=prompt_tokens,
            output_tokens=output_tokens,
            thought_tokens=thought_tokens,
            total_tokens=total_tokens,
            estimated_cost_micros=estimated_cost,
        )

    def _record(
        self,
        prompt: GeminiPrompt,
        *,
        outcome: str,
        usage: GeminiUsage | None = None,
        model_version: str | None = None,
    ) -> None:
        attributes = {
            "ai.provider": "gemini",
            "ai.model_alias": self._config.model_alias,
            "ai.model_version": model_version or "unknown",
            "ai.adapter_version": prompt.adapter_version,
            "ai.prompt_version": prompt.prompt_version,
            "ai.candidate_revision": prompt.candidate_revision,
            "ai.taxonomy_version": prompt.taxonomy_version,
            "ai.safety_policy_version": prompt.safety_policy_version,
            "ai.outcome": outcome,
        }
        if usage is not None:
            attributes.update(
                {
                    "ai.usage.prompt_tokens": str(usage.prompt_tokens),
                    "ai.usage.output_tokens": str(usage.output_tokens),
                    "ai.usage.thought_tokens": str(usage.thought_tokens),
                    "ai.usage.total_tokens": str(usage.total_tokens),
                    "ai.usage.estimated_cost_micros": str(usage.estimated_cost_micros),
                }
            )
        self._telemetry_sink.record(
            TelemetryEvent(name="ai.provider.request", attributes=attributes)
        )


def non_negative_int(value: Any) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else 0
