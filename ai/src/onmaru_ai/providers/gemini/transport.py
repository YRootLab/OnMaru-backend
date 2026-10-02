from __future__ import annotations

import json
from collections.abc import AsyncGenerator, AsyncIterator
from contextlib import aclosing
from typing import Protocol

import httpx

from onmaru_ai.providers.gemini.models import (
    GeminiFailureCode,
    GeminiProviderError,
    GeminiTransportRequest,
    GeminiTransportResponse,
)


class GeminiTransport(Protocol):
    async def generate(self, request: GeminiTransportRequest) -> GeminiTransportResponse:
        """Send one bounded provider request."""

    def stream(self, request: GeminiTransportRequest) -> AsyncIterator[GeminiTransportResponse]:
        """Read bounded provider SSE frames without exposing error bodies."""


class HttpxGeminiTransport:
    def __init__(self, client: httpx.AsyncClient | None = None) -> None:
        self._client = client

    async def generate(self, request: GeminiTransportRequest) -> GeminiTransportResponse:
        try:
            if self._client is not None:
                response = await self._client.post(
                    request.url,
                    headers=request.headers,
                    json=request.body,
                    timeout=request.timeout_seconds,
                )
            else:
                async with httpx.AsyncClient() as client:
                    response = await client.post(
                        request.url,
                        headers=request.headers,
                        json=request.body,
                        timeout=request.timeout_seconds,
                    )
        except httpx.TimeoutException as error:
            raise TimeoutError from error
        except httpx.RequestError as error:
            raise ConnectionError from error

        try:
            body = response.json()
        except ValueError:
            body = response.text
        return GeminiTransportResponse(status_code=response.status_code, body=body)

    async def stream(
        self, request: GeminiTransportRequest
    ) -> AsyncIterator[GeminiTransportResponse]:
        try:
            if self._client is not None:
                async with aclosing(self._stream(self._client, request)) as responses:
                    async for response in responses:
                        yield response
            else:
                async with (
                    httpx.AsyncClient() as client,
                    aclosing(self._stream(client, request)) as responses,
                ):
                    async for response in responses:
                        yield response
        except httpx.TimeoutException:
            raise TimeoutError from None
        except httpx.RequestError:
            raise ConnectionError from None

    async def _stream(
        self, client: httpx.AsyncClient, request: GeminiTransportRequest
    ) -> AsyncGenerator[GeminiTransportResponse, None]:
        async with client.stream(
            "POST",
            request.url,
            headers={**request.headers, "Accept-Encoding": "identity"},
            json=request.body,
            timeout=request.timeout_seconds,
        ) as response:
            if response.status_code != 200:
                yield GeminiTransportResponse(response.status_code, {})
                return
            if response.headers.get("content-encoding", "identity").lower() != "identity":
                raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)
            data: list[str] = []
            incomplete = bytearray()
            total_size = 0
            frame_size = 0
            async for chunk in response.aiter_raw():
                total_size += len(chunk)
                if total_size > 1_048_576:
                    raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)
                position = 0
                while position < len(chunk):
                    newline = chunk.find(b"\n", position)
                    end = len(chunk) if newline < 0 else newline
                    fragment_size = end - position
                    frame_size += fragment_size + (newline >= 0)
                    # Check before copying/decoding; ignored SSE lines count too.
                    if len(incomplete) + fragment_size > 65_536 or frame_size > 65_536:
                        raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)
                    incomplete.extend(chunk[position:end])
                    if newline < 0:
                        break
                    try:
                        line = incomplete.removesuffix(b"\r").decode("utf-8")
                    except UnicodeDecodeError:
                        raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE) from None
                    incomplete.clear()
                    position = end + 1
                    if line == "":
                        frame_size = 0
                        if data:
                            try:
                                body = json.loads("\n".join(data))
                            except ValueError:
                                raise GeminiProviderError(
                                    GeminiFailureCode.AI_INVALID_RESPONSE
                                ) from None
                            data.clear()
                            yield GeminiTransportResponse(200, body)
                    elif line.startswith("data:"):
                        data.append(line[5:].removeprefix(" "))
            if data or incomplete:
                raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)
