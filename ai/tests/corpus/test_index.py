from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta

from test_sync import FixtureSpringCorpusClient, document, manifest

from onmaru_ai.corpus.index import (
    CorpusIndexService,
    DeterministicEmbeddingProvider,
    EvidenceRetriever,
    FixedWordChunker,
)
from onmaru_ai.corpus.sync import (
    CorpusDocument,
    CorpusManifest,
    CorpusSyncResult,
    CorpusSyncService,
    InMemoryCorpusStore,
    SyncStatus,
)

NOW = datetime(2026, 9, 16, 4, 0, tzinfo=UTC)


class FailingEmbeddingProvider(DeterministicEmbeddingProvider):
    def embed(self, text: str) -> tuple[float, ...]:
        if "실패" in text:
            raise RuntimeError("embedding unavailable")
        return super().embed(text)


class DelayedDocumentClient(FixtureSpringCorpusClient):
    def __init__(
        self,
        manifests: dict[str, CorpusManifest],
        documents: dict[str, CorpusDocument],
        delayed_document_id: str,
    ) -> None:
        super().__init__(manifests, documents)
        self._delayed_document_id = delayed_document_id

    async def fetch_document(self, revision_id: str, document_id: str) -> CorpusDocument:
        if document_id == self._delayed_document_id:
            await asyncio.sleep(0)
        return await super().fetch_document(revision_id, document_id)


def test_completed_inactive_revision_is_retrievable_only_after_promotion() -> None:
    old_id = "catalog-r1"
    new_id = "catalog-r2"
    old = document(f"place:old:{old_id}", "삭제될 한옥 숙소")
    allowed = document(f"place:allowed:{new_id}", "바다 근처 한옥 숙소와 산책로")
    source = FixtureSpringCorpusClient(
        {
            old_id: manifest(old_id, NOW, [old]),
            new_id: manifest(
                new_id,
                NOW + timedelta(minutes=5),
                [allowed],
                tombstones=(old.document_id,),
            ),
        },
        {old.document_id: old, allowed.document_id: allowed},
    )
    store = InMemoryCorpusStore()
    index = CorpusIndexService(FixedWordChunker(max_words=3), DeterministicEmbeddingProvider())
    sync = CorpusSyncService(source, store, embedding_profile="test-v1", index=index)

    assert asyncio.run(sync.pull(old_id)).status is SyncStatus.ACTIVE
    assert asyncio.run(sync.pull(new_id)).status is SyncStatus.ACTIVE

    retriever = EvidenceRetriever(store, DeterministicEmbeddingProvider())
    allowed_results = retriever.retrieve(
        "한옥 산책로",
        revision_id=new_id,
        candidate_allowlist=frozenset({allowed.source_id}),
    )
    disallowed_results = retriever.retrieve(
        "한옥 산책로",
        revision_id=new_id,
        candidate_allowlist=frozenset({"not-an-allowed-candidate"}),
    )
    stale_revision_results = retriever.retrieve(
        "한옥 산책로",
        revision_id=old_id,
        candidate_allowlist=frozenset({old.source_id}),
    )

    assert {result.document_id for result in allowed_results} == {allowed.document_id}
    assert disallowed_results == ()
    assert stale_revision_results == ()
    assert old.document_id not in {result.document_id for result in allowed_results}


def test_embedding_failure_preserves_old_active_revision() -> None:
    old_id = "catalog-r1"
    failed_id = "catalog-r2"
    old = document(f"place:old:{old_id}", "정상 한옥 숙소")
    failed = document(f"place:failed:{failed_id}", "embedding 실패 문서")
    source = FixtureSpringCorpusClient(
        {
            old_id: manifest(old_id, NOW, [old]),
            failed_id: manifest(failed_id, NOW + timedelta(minutes=5), [failed]),
        },
        {old.document_id: old, failed.document_id: failed},
    )
    store = InMemoryCorpusStore()
    index = CorpusIndexService(FixedWordChunker(max_words=3), FailingEmbeddingProvider())
    sync = CorpusSyncService(source, store, embedding_profile="test-v1", index=index)

    assert asyncio.run(sync.pull(old_id)).status is SyncStatus.ACTIVE
    rejected = asyncio.run(sync.pull(failed_id))

    assert rejected.status is SyncStatus.REJECTED
    assert rejected.error_code == "CORPUS_EMBEDDING_FAILED"
    assert store.active_revision_id == old_id
    assert store.chunk_count(failed_id) == 0


def test_late_concurrent_revision_cannot_roll_back_active_pointer() -> None:
    old_id = "catalog-r1"
    new_id = "catalog-r2"
    old = document(f"place:old:{old_id}", "느린 이전 revision")
    new = document(f"place:new:{new_id}", "빠른 최신 revision")
    source = DelayedDocumentClient(
        {
            old_id: manifest(old_id, NOW, [old]),
            new_id: manifest(new_id, NOW + timedelta(minutes=5), [new]),
        },
        {old.document_id: old, new.document_id: new},
        delayed_document_id=old.document_id,
    )
    store = InMemoryCorpusStore()
    index = CorpusIndexService(FixedWordChunker(max_words=3), DeterministicEmbeddingProvider())
    sync = CorpusSyncService(source, store, embedding_profile="test-v1", index=index)

    async def pull_both() -> tuple[CorpusSyncResult, CorpusSyncResult]:
        results = await asyncio.gather(sync.pull(old_id), sync.pull(new_id))
        return results[0], results[1]

    old_result, new_result = asyncio.run(pull_both())

    assert old_result.status is SyncStatus.REJECTED
    assert old_result.error_code == "CORPUS_OUT_OF_ORDER"
    assert new_result.status is SyncStatus.ACTIVE
    assert store.active_revision_id == new_id
    assert store.chunk_count(old_id) == 0
