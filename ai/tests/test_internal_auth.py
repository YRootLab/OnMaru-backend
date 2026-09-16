from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta

from httpx import ASGITransport, AsyncClient, Response

from onmaru_ai.config.secrets import FakeSecretProvider
from onmaru_ai.main import create_app
from onmaru_ai.security.internal_auth import create_internal_token


async def post_proposal(
    token: str,
    headers: dict[str, str] | None = None,
    body: dict[str, object] | None = None,
) -> Response:
    app = create_app(secret_provider=FakeSecretProvider())
    request_headers = {
        "Authorization": f"Bearer {token}",
        "X-Request-Id": "req-ai-001",
        "X-Run-Id": "run-ai-001",
        "X-Revision": "dataset-2026-09-15",
        "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    }
    if headers:
        request_headers.update(headers)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post(
            "/internal/v1/journey/proposals",
            headers=request_headers,
            json=body or {
                "schemaVersion": "internal.ai.v1",
                "requestId": "req-ai-001",
                "runId": "run-ai-001",
                "candidateCount": 2,
            },
        )


def token(
    *,
    audience: str = "onmaru-ai",
    scope: str = "journey.proposal:write",
    expires_delta: timedelta = timedelta(seconds=60),
    secret: str = "fake-internal-ai-service-token-current",
) -> str:
    now = datetime.now(UTC) - timedelta(seconds=10)
    return create_internal_token(
        secret=secret,
        issuer="onmaru-spring",
        subject="spring-api",
        audience=audience,
        scope=scope,
        jti="550e8400-e29b-41d4-a716-446655440000",
        issued_at=now,
        expires_at=now + expires_delta,
    )


def test_accepts_current_internal_token_and_propagates_correlation_headers() -> None:
    response = asyncio.run(post_proposal(token()))

    assert response.status_code == 202
    assert response.json() == {
        "schemaVersion": "internal.ai.v1",
        "runId": "run-ai-001",
        "orderedRefs": ["place:001", "place:002"],
        "outcome": "PROPOSAL",
    }
    assert response.headers["X-Request-Id"] == "req-ai-001"
    assert response.headers["X-Run-Id"] == "run-ai-001"
    assert response.headers["X-Revision"] == "dataset-2026-09-15"


def test_accepts_previous_internal_token_during_rotation_overlap() -> None:
    response = asyncio.run(
        post_proposal(token(secret="fake-internal-ai-service-token-previous"))
    )

    assert response.status_code == 202


def test_rejects_wrong_audience_before_request_body_is_trusted() -> None:
    response = asyncio.run(
        post_proposal(
            token(audience="other-service"),
            body={"schemaVersion": "wrong", "candidateCount": "too-many"},
        )
    )

    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_AUTH_INVALID_AUDIENCE"


def test_rejects_missing_required_scope() -> None:
    response = asyncio.run(post_proposal(token(scope="journey.read")))

    assert response.status_code == 403
    assert response.json()["code"] == "INTERNAL_AUTH_FORBIDDEN_SCOPE"


def test_rejects_expired_internal_token() -> None:
    response = asyncio.run(post_proposal(token(expires_delta=timedelta(seconds=-1))))

    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_AUTH_EXPIRED"


def test_rejects_invalid_contract_after_authentication() -> None:
    response = asyncio.run(
        post_proposal(
            token(),
            body={
                "schemaVersion": "wrong",
                "requestId": "req-ai-001",
                "runId": "run-ai-001",
                "candidateCount": 13,
            },
        )
    )

    assert response.status_code == 422
    assert response.json()["code"] == "INTERNAL_AI_CONTRACT_INVALID"


def test_rejects_body_run_id_that_does_not_match_correlation_header() -> None:
    response = asyncio.run(
        post_proposal(
            token(),
            body={
                "schemaVersion": "internal.ai.v1",
                "requestId": "req-ai-001",
                "runId": "different-run",
                "candidateCount": 2,
            },
        )
    )

    assert response.status_code == 422
    assert response.json()["code"] == "INTERNAL_AI_CONTRACT_INVALID"
