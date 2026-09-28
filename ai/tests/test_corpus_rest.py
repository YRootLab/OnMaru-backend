from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from httpx import ASGITransport, AsyncClient

from onmaru_ai.config.secrets import FakeSecretProvider
from onmaru_ai.corpus.index import (
    CorpusIndexService,
    DeterministicEmbeddingProvider,
    FixedWordChunker,
)
from onmaru_ai.corpus.sync import CorpusManifest, CorpusManifestEntry, InMemoryCorpusStore
from onmaru_ai.main import create_app
from onmaru_ai.security.internal_auth import create_internal_token


def _token() -> str:
    now = datetime.now(UTC)
    return create_internal_token(
        secret="fake-internal-ai-service-token-current",
        issuer="onmaru-spring",
        subject="spring-api",
        audience="onmaru-ai",
        scope="corpus.sync:write",
        issued_at=now,
        expires_at=now + timedelta(seconds=30),
    )


@pytest.mark.anyio
async def test_hanok_corpus_sync_activates_bounded_chunks() -> None:
    published_at = datetime(2026, 9, 28, tzinfo=UTC)
    entry = CorpusManifestEntry(
        document_id="hanok:p-1",
        kind="HANOK_PLACE",
        document_hash="sha256:placeholder",
        state="UPSERT",
        content_url="/documents/hanok:p-1",
    )
    # Build the canonical document hash using the same contract as Spring.
    from onmaru_ai.corpus.sync import CorpusDocument

    document = CorpusDocument.create(
        contract_version="1.0",
        revision_id="hanok-20260928",
        document_id="hanok:p-1",
        kind="HANOK_PLACE",
        source_id="p-1",
        source_revision="published-1",
        provenance_id="tourapi:p-1",
        category="HANOK",
        region="SEOUL",
        publication_eligible=True,
        text="종로 한옥 체험 서울 전통마을",
    )
    entry = entry.__class__(
        entry.document_id,
        entry.kind,
        document.document_hash,
        entry.state,
        entry.content_url,
    )
    manifest = CorpusManifest.create(
        contract_version="1.0",
        revision_id="hanok-20260928",
        published_at=published_at,
        documents=(entry,),
    )
    app = create_app(
        secret_provider=FakeSecretProvider(),
        corpus_store=InMemoryCorpusStore(),
        corpus_index=CorpusIndexService(
            FixedWordChunker(max_words=1),
            DeterministicEmbeddingProvider(),
            max_chunks_per_document=4,
        ),
    )
    payload = {
        "contractVersion": manifest.contract_version,
        "revisionId": manifest.revision_id,
        "publishedAt": published_at.isoformat().replace("+00:00", "Z"),
        "manifestHash": manifest.manifest_hash,
        "documents": [{
            "documentId": entry.document_id,
            "kind": entry.kind,
            "documentHash": entry.document_hash,
            "state": entry.state,
            "contentUrl": entry.content_url,
        }],
        "payloads": [{
            "contractVersion": document.contract_version,
            "revisionId": document.revision_id,
            "documentId": document.document_id,
            "kind": document.kind,
            "sourceId": document.source_id,
            "sourceRevision": document.source_revision,
            "provenanceId": document.provenance_id,
            "category": document.category,
            "region": document.region,
            "publicationEligible": document.publication_eligible,
            "text": document.text,
            "documentHash": document.document_hash,
        }],
    }
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            "/internal/v1/corpus/sync",
            headers={"Authorization": f"Bearer {_token()}"},
            json=payload,
        )
    assert response.status_code == 200
    assert response.json()["status"] == "ACTIVE"
    assert response.json()["chunkCount"] == 4
