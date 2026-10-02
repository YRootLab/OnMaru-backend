from __future__ import annotations

import json
from collections.abc import AsyncIterator
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
                async for response in self._stream(self._client, request):
                    yield response
            else:
                async with httpx.AsyncClient() as client:
                    async for response in self._stream(client, request):
                        yield response
        except httpx.TimeoutException:
            raise TimeoutError from None
        except httpx.RequestError:
            raise ConnectionError from None

    async def _stream(
        self, client: httpx.AsyncClient, request: GeminiTransportRequest
    ) -> AsyncIterator[GeminiTransportResponse]:
        async with client.stream(
            "POST",
            request.url,
            headers=request.headers,
            json=request.body,
            timeout=request.timeout_seconds,
        ) as response:
            if response.status_code != 200:
                yield GeminiTransportResponse(response.status_code, {})
                return
            data: list[str] = []
            size = 0
            async for line in response.aiter_lines():
                if line == "":
                    if data:
                        try:
                            body = json.loads("\n".join(data))
                        except ValueError:
                            raise GeminiProviderError(
                                GeminiFailureCode.AI_INVALID_RESPONSE
                            ) from None
                        yield GeminiTransportResponse(200, body)
                        data.clear()
                        size = 0
                elif line.startswith("data:"):
                    value = line[5:].removeprefix(" ")
                    size += len(value)
                    if size > 65_536:
                        raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)
                    data.append(value)
            if data:
                raise GeminiProviderError(GeminiFailureCode.AI_INVALID_RESPONSE)
