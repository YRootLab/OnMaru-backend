from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from typing import Any, cast

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient, Response

from onmaru_ai.config.secrets import FakeSecretProvider
from onmaru_ai.evals import compare_reports, evaluate_document
from onmaru_ai.main import create_app
from onmaru_ai.rag import InMemoryRagActivationLog, RagActivationPolicy, RagActivationThresholds
from onmaru_ai.security.internal_auth import create_internal_token


class CountingRagRetriever:
    def __init__(self) -> None:
        self.calls = 0

    def retrieve(self, corpus_revision_id: str) -> tuple[str, ...]:
        self.calls += 1
        return (f"{corpus_revision_id}:evidence:001",)


async def post_proposal(
    token: str,
    headers: dict[str, str] | None = None,
    body: dict[str, object] | None = None,
    app: FastAPI | None = None,
) -> Response:
    app = app or create_app(secret_provider=FakeSecretProvider())
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
            json=body
            or {
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


def failing_eval_document() -> dict[str, object]:
    return {
        "schemaVersion": "1.1",
        "versions": {
            "model": "rag-challenger-v1",
            "prompt": "journey-proposal-v1",
            "ranking": "rag-ranking-v1",
            "dataset": "journey-held-out-v1",
        },
        "thresholds": {
            "minRetrievalRecallAt5": 0.85,
            "minNdcgAt3": 0.8,
            "minClaimSupport": 0.9,
            "minSafety": 1.0,
            "maxLatencyP95Ms": 8000,
            "maxMeanCostMicros": 1000,
            "maxPerRunCostMicros": 2000,
        },
        "cases": [
            {
                "id": "rag-disabled",
                "gold": {
                    "relevance": [{"ref": "place-a", "grade": 2}],
                    "supportedEvidence": {"place-a": ["evidence-a"]},
                    "allowedRefs": ["place-a"],
                    "pinnedRefs": [],
                    "excludedRefs": [],
                    "expectedOutcome": "PROPOSE_BOARD",
                },
                "actual": {
                    "retrievedRefs": [],
                    "outcome": "PROPOSE_BOARD",
                    "orderedRefs": ["place-a"],
                    "reasons": [
                        {
                            "ref": "place-a",
                            "evidenceIds": ["evidence-a"],
                            "summary": "검수된 근거만 사용합니다.",
                            "humanClaimSupported": True,
                        }
                    ],
                    "latencyMs": 7000,
                    "costMicros": 300,
                },
            }
        ],
    }


def passing_eval_document() -> dict[str, object]:
    document = failing_eval_document()
    cases = cast(list[dict[str, Any]], document["cases"])
    cases[0]["actual"]["retrievedRefs"] = ["place-a"]
    return document


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
    response = asyncio.run(post_proposal(token(secret="fake-internal-ai-service-token-previous")))

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


def test_skips_rag_retrieval_when_activation_gate_recorded_rollback() -> None:
    log = InMemoryRagActivationLog()
    RagActivationPolicy(log=log).evaluate(
        evaluate_document(failing_eval_document()),
        corpus_revision_id="catalog-rev-2026-09-17",
    )
    retriever = CountingRagRetriever()
    app = create_app(
        secret_provider=FakeSecretProvider(),
        rag_activation_log=log,
        rag_retriever=retriever,
        environ={"ONMARU_RAG_ENABLED": "true"},
    )

    response = asyncio.run(
        post_proposal(
            token(),
            app=app,
            body={
                "schemaVersion": "internal.ai.v1",
                "requestId": "req-ai-001",
                "runId": "run-ai-001",
                "candidateCount": 2,
                "useRag": True,
                "corpusRevisionId": "catalog-rev-2026-09-17",
            },
        )
    )

    assert response.status_code == 202
    assert response.json()["ragEvidenceRefs"] == []
    assert retriever.calls == 0


def test_returns_rag_evidence_when_activation_gate_recorded_active_revision() -> None:
    log = InMemoryRagActivationLog()
    baseline = evaluate_document(failing_eval_document())
    current = evaluate_document(passing_eval_document())
    current = current.model_copy(update={"comparison": compare_reports(current, baseline)})
    RagActivationPolicy(
        log=log,
        thresholds=RagActivationThresholds(min_retrieval_recall_at_5_delta=0.1),
    ).evaluate(current, corpus_revision_id="catalog-rev-2026-09-17")
    retriever = CountingRagRetriever()
    app = create_app(
        secret_provider=FakeSecretProvider(),
        rag_activation_log=log,
        rag_retriever=retriever,
        environ={"ONMARU_RAG_ENABLED": "true"},
    )

    response = asyncio.run(
        post_proposal(
            token(),
            app=app,
            body={
                "schemaVersion": "internal.ai.v1",
                "requestId": "req-ai-001",
                "runId": "run-ai-001",
                "candidateCount": 2,
                "useRag": True,
                "corpusRevisionId": "catalog-rev-2026-09-17",
            },
        )
    )

    assert response.status_code == 202
    assert response.json()["ragEvidenceRefs"] == ["catalog-rev-2026-09-17:evidence:001"]
    assert retriever.calls == 1


def test_rejects_blank_rag_corpus_revision_id() -> None:
    response = asyncio.run(
        post_proposal(
            token(),
            body={
                "schemaVersion": "internal.ai.v1",
                "requestId": "req-ai-001",
                "runId": "run-ai-001",
                "candidateCount": 2,
                "useRag": True,
                "corpusRevisionId": "",
            },
        )
    )

    assert response.status_code == 422
    assert response.json()["code"] == "INTERNAL_AI_CONTRACT_INVALID"
