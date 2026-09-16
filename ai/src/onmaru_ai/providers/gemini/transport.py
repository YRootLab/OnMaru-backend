from __future__ import annotations

from typing import Protocol

import httpx

from onmaru_ai.providers.gemini.models import GeminiTransportRequest, GeminiTransportResponse


class GeminiTransport(Protocol):
    async def generate(self, request: GeminiTransportRequest) -> GeminiTransportResponse:
        """Send one bounded provider request."""


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
