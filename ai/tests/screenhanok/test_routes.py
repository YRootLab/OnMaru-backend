import asyncio
from collections.abc import Mapping
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient, Response

from onmaru_ai.config.secrets import FakeSecretProvider
from onmaru_ai.providers.gemini.models import (
    GeminiPrompt,
    GeminiResult,
    GeminiUsage,
    GroundingChunk,
)
from onmaru_ai.screenhanok.routes import install_screen_hanok_research
from onmaru_ai.screenhanok.service import ScreenHanokResearchService
from onmaru_ai.security.internal_auth import create_internal_token


class FakeAdapter:
    async def generate(
        self,
        prompt: GeminiPrompt,
        *,
        response_schema: Mapping[str, Any],
        timeout_seconds: float,
        cancellation_event: asyncio.Event | None = None,
        enable_search_grounding: bool = False,
    ) -> GeminiResult:
        del prompt, response_schema, timeout_seconds, cancellation_event, enable_search_grounding
        return GeminiResult(
            proposal={
                "matches": [
                    {
                        "placeId": "p-001",
                        "mediaType": "K_DRAMA",
                        "workTitle": "OO",
                        "subtitle": "실제 촬영 보도",
                        "tags": ["#사극"],
                        "sourceUrl": "https://example.com/a",
                        "sourceTitle": "기사",
                    }
                ]
            },
            usage=GeminiUsage(
                prompt_tokens=1,
                output_tokens=1,
                thought_tokens=0,
                total_tokens=2,
                estimated_cost_micros=0,
            ),
            model_version="gemini-test",
            grounding_chunks=(GroundingChunk(uri="https://example.com/a", title="기사"),),
        )


def app() -> FastAPI:
    application = FastAPI()
    secret_provider = FakeSecretProvider()
    install_screen_hanok_research(
        application, secret_provider, ScreenHanokResearchService(FakeAdapter())
    )
    return application


def token(*, scope: str = "screen-hanok.research:write", audience: str = "onmaru-ai") -> str:
    now = datetime.now(UTC) - timedelta(seconds=10)
    return create_internal_token(
        secret="fake-internal-ai-service-token-current",
        issuer="onmaru-spring",
        subject="spring-api",
        audience=audience,
        scope=scope,
        jti="550e8400-e29b-41d4-a716-446655440001",
        issued_at=now,
        expires_at=now + timedelta(seconds=60),
    )


async def post_research(
    bearer: str, headers: dict[str, str] | None = None, body: dict[str, object] | None = None
) -> Response:
    request_headers = {
        "Authorization": f"Bearer {bearer}",
        "X-Request-Id": "req-sh-001",
    }
    if headers:
        request_headers.update(headers)
    transport = ASGITransport(app=app())
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post(
            "/internal/v1/screen-hanok/research",
            headers=request_headers,
            json=body
            or {
                "schemaVersion": "internal.screen-hanok.v1",
                "requestId": "req-sh-001",
                "candidates": [
                    {
                        "placeId": "p-001",
                        "name": "OO 고택",
                        "regionName": "경북",
                        "category": "HANOK",
                    }
                ],
            },
        )


def test_accepts_valid_token_and_returns_matches() -> None:
    import asyncio

    response = asyncio.run(post_research(token()))

    assert response.status_code == 200
    body = response.json()
    assert body["requestId"] == "req-sh-001"
    assert body["matches"][0]["placeId"] == "p-001"
    assert body["matches"][0]["sourceUrl"] == "https://example.com/a"


def test_rejects_missing_token() -> None:
    import asyncio

    response = asyncio.run(post_research(""))

    assert response.status_code == 401


def test_rejects_wrong_scope() -> None:
    import asyncio

    response = asyncio.run(post_research(token(scope="journey.proposal:write")))

    assert response.status_code == 403


def test_rejects_request_id_mismatch_between_body_and_header() -> None:
    import asyncio

    response = asyncio.run(
        post_research(
            token(),
            body={
                "schemaVersion": "internal.screen-hanok.v1",
                "requestId": "different-request-id",
                "candidates": [
                    {
                        "placeId": "p-001",
                        "name": "OO 고택",
                        "regionName": "경북",
                        "category": "HANOK",
                    }
                ],
            },
        )
    )

    assert response.status_code == 422


def test_rejects_body_with_no_candidates() -> None:
    import asyncio

    response = asyncio.run(
        post_research(
            token(),
            body={
                "schemaVersion": "internal.screen-hanok.v1",
                "requestId": "req-sh-001",
                "candidates": [],
            },
        )
    )

    assert response.status_code == 422
