from __future__ import annotations

import asyncio
import contextlib
import json
import re
from collections.abc import AsyncGenerator, AsyncIterator, Mapping
from dataclasses import replace
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

    async def stream(
        self,
        prompt: GeminiPrompt,
        *,
        response_schema: Mapping[str, Any],
        timeout_seconds: float,
        cancellation_event: asyncio.Event | None = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
        if timeout_seconds <= 0:
            raise ValueError("Gemini timeout must be positive")
        unary = self._request(prompt, response_schema, timeout_seconds)
        request = replace(
            unary, url=unary.url.removesuffix(":generateContent") + ":streamGenerateContent?alt=sse"
        )
        parser = _NarrationParser()
        forbidden = {
            self._api_key,
            *_candidate_refs(prompt.data_block),
            *_internal_prompt_markers(prompt),
        }
        guard = _NarrationGuard(forbidden)
        usage: GeminiUsage | None = None
        model_version = self._config.model_name
        iterator = self._transport.stream(request)
        try:
            async with asyncio.timeout(timeout_seconds):
                while True:
                    response = await self._next_chunk(iterator, cancellation_event)
                    if response is None:
                        break
                    if response.status_code != 200:
                        self._parse(response, usage)
                    if not isinstance(response.body, dict):
                        raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)
                    usage = self._usage_from_response(response) or usage
                    version = response.body.get("modelVersion")
                    if (
                        isinstance(version, str)
                        and MODEL_NAME_PATTERN.fullmatch(version)
                        and not any(value in version for value in forbidden)
                    ):
                        model_version = version
                    for text in _response_text_parts(response.body):
                        decoded = parser.feed(text)
                        public_text = guard.feed(decoded, finished=parser.narration_finished)
                        for start in range(0, len(public_text), 512):
                            yield {
                                "event": "text.delta",
                                "data": {"text": public_text[start : start + 512]},
                            }
                proposal = parser.finish()
                _validate_stream_value(proposal, response_schema)
                proposal["narration"] = proposal["narration"][:4000]
                self._record(prompt, outcome="SUCCESS", usage=usage, model_version=model_version)
                yield {"event": "proposal", "data": {"proposal": proposal}}
        except GeminiProviderError as error:
            self._record(prompt, outcome=error.code.value, usage=usage)
            raise
        except (TimeoutError, ConnectionError) as error:
            code = (
                GeminiFailureCode.AI_TIMEOUT
                if isinstance(error, TimeoutError)
                else GeminiFailureCode.AI_SERVICE_UNAVAILABLE
            )
            self._record(prompt, outcome=code.value, usage=usage)
            raise GeminiProviderError(code) from None
        finally:
            close = getattr(iterator, "aclose", None)
            if close is not None:
                await close()

    async def _next_chunk(
        self,
        iterator: AsyncIterator[GeminiTransportResponse],
        cancellation_event: asyncio.Event | None,
    ) -> GeminiTransportResponse | None:
        if cancellation_event is not None and cancellation_event.is_set():
            raise GeminiProviderError(GeminiFailureCode.CANCELLED)
        next_task = asyncio.ensure_future(anext(iterator))
        cancel_task = (
            asyncio.create_task(cancellation_event.wait())
            if cancellation_event is not None
            else None
        )
        try:
            if cancel_task is not None:
                done, _ = await asyncio.wait(
                    {next_task, cancel_task}, return_when=asyncio.FIRST_COMPLETED
                )
                if cancel_task in done:
                    raise GeminiProviderError(GeminiFailureCode.CANCELLED)
            return await next_task
        except StopAsyncIteration:
            return None
        finally:
            next_task.cancel()
            with contextlib.suppress(asyncio.CancelledError, StopAsyncIteration):
                await next_task
            if cancel_task is not None:
                cancel_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await cancel_task

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


def _invalid_stream() -> GeminiProviderError:
    return GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)


def _response_text_parts(body: dict[str, Any]) -> list[str]:
    feedback = body.get("promptFeedback", {})
    if not isinstance(feedback, dict) or body.get("error") or feedback.get("blockReason"):
        raise _invalid_stream()
    candidates = body.get("candidates", [])
    if not isinstance(candidates, list):
        raise _invalid_stream()
    if not candidates:
        return []
    try:
        candidate = candidates[0]
        if candidate.get("finishReason", "STOP") != "STOP":
            raise _invalid_stream()
        parts = candidate.get("content", {}).get("parts", [])
        result = [part["text"] for part in parts if not part.get("thought") and "text" in part]
        if any(not isinstance(text, str) for text in result):
            raise _invalid_stream()
        return result
    except (AttributeError, TypeError, KeyError):
        raise _invalid_stream() from None


def _candidate_refs(data: Mapping[str, Any]) -> set[str]:
    refs = {ref for ref in data.get("candidateRefs", []) if isinstance(ref, str) and ref}
    for candidate in data.get("candidates", []):
        if isinstance(candidate, dict):
            ref = candidate.get("placeRef")
            if isinstance(ref, str) and ref:
                refs.add(ref)
    return refs


def _internal_prompt_markers(prompt: GeminiPrompt) -> set[str]:
    markers = {
        "orderedRefs",
        "candidateRefs",
        "narration",
        "systemInstruction",
        "system prompt",
        "internal policy",
        "internal instructions",
        "developer instructions",
        "policy_block",
        "trusted-few-shot",
        "untrusted-data",
        "example-output",
        "api_key",
        "authorization",
        "latitude",
        "longitude",
        "coordinates",
    }
    # Bounded prefixes detect copied policy sentences without delaying all narration
    # behind an arbitrarily long internal prompt. No prompt content is logged.
    for sentence in re.split(r"(?<=[.!?])\s+|\n", prompt.policy_block):
        if len(sentence.strip()) >= 8:
            markers.add(sentence.strip()[:64])
    return markers


class _NarrationGuard:
    """Withhold bounded lookbehind before publishing prose, never structured/private text."""

    def __init__(self, forbidden: set[str]) -> None:
        self._forbidden = {value.casefold() for value in forbidden if value}
        self._hold = max(80, max((len(value) for value in self._forbidden), default=1) - 1)
        self._pending = ""
        self._seen = ""
        self._emitted = 0

    def feed(self, text: str, *, finished: bool) -> str:
        self._pending += text
        self._seen += text
        if len(self._seen) > 65_536:
            raise _invalid_stream()
        folded = self._pending.casefold()
        if (
            any(value in folded for value in self._forbidden)
            # A decoded ASCII JSON quote is always private/structured here. Reject
            # at the opening quote, independent of field length or later whitespace.
            or re.search(r'["{}\[\]<>°º]', self._pending)
            # Fail closed on coordinate-like precision and numeric coordinate pairs.
            # A lone first number is not sensitive; keep bounded full history so
            # unlimited separating whitespace cannot conceal a later coordinate.
            or self._contains_coordinates(finished=finished)
        ):
            raise _invalid_stream()
        count = len(self._pending) if finished else max(0, len(self._pending) - self._hold)
        public = self._pending[:count][: max(0, 4000 - self._emitted)]
        self._pending = self._pending[count:]
        self._emitted += len(public)
        return public

    def _contains_coordinates(self, *, finished: bool) -> bool:
        if re.search(r"\d{1,3}\.\d{2}|\d{1,3}\.\d{1,10}\s+[+-]?\d{1,3}\.\d", self._seen):
            return True
        for match in re.finditer(r"(?<!\d)[+-]?\d{1,3}\s*,\s*[+-]?\d{1,3}(?![\d,])", self._seen):
            suffix = self._seen[match.end() :]
            if not suffix and not finished:
                continue  # Still receiving digits or a normal monetary suffix.
            if suffix.startswith(("원", "만원", "달러", "유로", "KRW", "USD")):
                continue
            return True
        return False


class _NarrationParser:
    """Incrementally decode only a top-level JSON narration value, never JSON fragments."""

    def __init__(self) -> None:
        self._source = ""
        self._position = 0
        self._state = "start"
        self._key = ""
        self._keys: set[str] = set()
        self.narration_finished = False
        self._decoder = json.JSONDecoder()

    def feed(self, text: str) -> str:
        self._source += text
        if len(self._source) > 65_536:
            raise _invalid_stream()
        emitted: list[str] = []
        while self._position < len(self._source):
            if self._state == "narration":
                decoded = self._narration_character()
                if decoded is None:
                    break
                emitted.append(decoded)
                continue
            if self._source[self._position].isspace():
                self._position += 1
                continue
            char = self._source[self._position]
            if self._state == "start":
                if char != "{":
                    raise _invalid_stream()
                self._position += 1
                self._state = "key"
            elif self._state == "key":
                if char == "}":
                    self._position += 1
                    self._state = "done"
                    continue
                if char != '"':
                    raise _invalid_stream()
                try:
                    key, end = self._decoder.raw_decode(self._source, self._position)
                except json.JSONDecodeError:
                    break
                if key in self._keys:
                    raise _invalid_stream()
                self._keys.add(key)
                self._key = key
                self._position = end
                self._state = "colon"
            elif self._state == "colon":
                if char != ":":
                    raise _invalid_stream()
                self._position += 1
                self._state = "value"
            elif self._state == "value":
                if self._key == "narration":
                    if char != '"':
                        raise _invalid_stream()
                    self._position += 1
                    self._state = "narration"
                else:
                    try:
                        _, end = self._decoder.raw_decode(self._source, self._position)
                    except json.JSONDecodeError:
                        break
                    self._position = end
                    self._state = "comma"
            elif self._state == "comma":
                if char not in {",", "}"}:
                    raise _invalid_stream()
                self._position += 1
                self._state = "key" if char == "," else "done"
            else:
                raise _invalid_stream()
        return "".join(emitted)

    def _narration_character(self) -> str | None:
        index = self._position
        source = self._source
        char = source[index]
        if char == '"':
            self._position += 1
            self._state = "comma"
            self.narration_finished = True
            return ""
        if char != "\\":
            if ord(char) < 32 or 0xD800 <= ord(char) <= 0xDFFF:
                raise _invalid_stream()
            self._position += 1
            return char
        if len(source) < index + 2:
            return None
        escape = source[index + 1]
        simple = {
            '"': '"',
            "\\": "\\",
            "/": "/",
            "b": "\b",
            "f": "\f",
            "n": "\n",
            "r": "\r",
            "t": "\t",
        }
        if escape in simple:
            self._position += 2
            return simple[escape]
        if escape != "u":
            raise _invalid_stream()
        if len(source) < index + 6:
            return None
        try:
            code = int(source[index + 2 : index + 6], 16)
        except ValueError:
            raise _invalid_stream() from None
        length = 6
        if 0xD800 <= code <= 0xDBFF:
            if len(source) < index + 12:
                return None
            if source[index + 6 : index + 8] != "\\u":
                raise _invalid_stream()
            try:
                low = int(source[index + 8 : index + 12], 16)
            except ValueError:
                raise _invalid_stream() from None
            if not 0xDC00 <= low <= 0xDFFF:
                raise _invalid_stream()
            code = 0x10000 + (code - 0xD800) * 1024 + low - 0xDC00
            length = 12
        elif 0xDC00 <= code <= 0xDFFF:
            raise _invalid_stream()
        self._position += length
        return chr(code)

    def finish(self) -> dict[str, Any]:
        if self._state != "done" or not self.narration_finished:
            raise _invalid_stream()
        try:
            proposal = json.loads(self._source)
        except ValueError:
            raise _invalid_stream() from None
        if not isinstance(proposal, dict):
            raise _invalid_stream()
        return proposal


def _validate_stream_value(value: Any, schema: Mapping[str, Any]) -> None:
    kind = str(schema.get("type", "")).upper()
    if kind == "OBJECT":
        if not isinstance(value, dict) or any(
            key not in value for key in schema.get("required", [])
        ):
            raise _invalid_stream()
        properties = schema.get("properties", {})
        for key, child in properties.items():
            if key in value:
                _validate_stream_value(value[key], child)
    elif kind == "ARRAY":
        if not isinstance(value, list):
            raise _invalid_stream()
        for item in value:
            _validate_stream_value(item, schema.get("items", {}))
    elif kind == "STRING" and not isinstance(value, str):
        raise _invalid_stream()
