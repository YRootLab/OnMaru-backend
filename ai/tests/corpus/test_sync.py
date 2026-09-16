from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import UTC, datetime, timedelta

from onmaru_ai.corpus.sync import (
    CorpusDocument,
    CorpusManifest,
    CorpusManifestEntry,
    CorpusSyncService,
    InMemoryCorpusStore,
    SyncStatus,
)

NOW = datetime(2026, 9, 16, 4, 0, tzinfo=UTC)


class FixtureSpringCorpusClient:
    def __init__(self, manifests: dict[str, CorpusManifest], documents: dict[str, CorpusDocument]):
        self.manifests = manifests
        self.documents = documents
        self.acks: list[dict[str, object]] = []
        self.fail_document_id: str | None = None
        self.fetch_count = 0

    async def fetch_manifest(self, revision_id: str) -> CorpusManifest:
        return self.manifests[revision_id]

    async def fetch_document(self, revision_id: str, document_id: str) -> CorpusDocument:
        self.fetch_count += 1
        if document_id == self.fail_document_id:
            raise TimeoutError("fixture fetch failed")
        return self.documents[document_id]

    async def acknowledge(self, acknowledgement: dict[str, object]) -> None:
        self.acks.append(acknowledgement)


def document(document_id: str, text: str) -> CorpusDocument:
    return CorpusDocument.create(
        contract_version="1.0",
        revision_id=document_id.rsplit(":", 1)[-1],
        document_id=document_id,
        kind="PLACE",
        source_id="opaque-place-1",
        source_revision="source-17",
        provenance_id="kto-korean-tour",
        category="HANOK",
        region="11",
        publication_eligible=True,
        text=text,
    )


def manifest(
    revision_id: str,
    published_at: datetime,
    documents: list[CorpusDocument],
    tombstones: tuple[str, ...] = (),
) -> CorpusManifest:
    entries = [
        CorpusManifestEntry(
            document_id=item.document_id,
            kind=item.kind,
            document_hash=item.document_hash,
            state="UPSERT",
            content_url=f"/internal/v1/corpus/revisions/{revision_id}/documents/{item.document_id}",
        )
        for item in documents
    ]
    entries.extend(
        CorpusManifestEntry(
            document_id=document_id,
            kind="PLACE",
            document_hash=None,
            state="TOMBSTONE",
            content_url=None,
        )
        for document_id in tombstones
    )
    return CorpusManifest.create(
        contract_version="1.0",
        revision_id=revision_id,
        published_at=published_at,
        documents=tuple(entries),
    )


def test_duplicate_manifest_pull_inserts_no_duplicate_documents() -> None:
    revision_id = "catalog-r1"
    item = document(f"place:one:{revision_id}", "한옥 설명")
    source = FixtureSpringCorpusClient(
        {revision_id: manifest(revision_id, NOW, [item])},
        {item.document_id: item},
    )
    store = InMemoryCorpusStore()
    service = CorpusSyncService(source, store, embedding_profile="none")

    first = asyncio.run(service.pull(revision_id))
    second = asyncio.run(service.pull(revision_id))

    assert first.status is SyncStatus.ACTIVE
    assert first.inserted_document_count == 1
    assert second.status is SyncStatus.ACTIVE
    assert second.inserted_document_count == 0
    assert store.document_count(revision_id) == 1
    assert source.fetch_count == 1


def test_corpus_hashes_match_spring_wire_contract_fixture() -> None:
    revision_id = "catalog-r1"
    item = document(f"place:one:{revision_id}", "한옥 설명")
    published = manifest(
        revision_id,
        NOW,
        [item],
        tombstones=(f"place:removed:{revision_id}",),
    )

    assert item.document_hash == (
        "sha256:2396a8d72fbc8cb98e303b2268ed39233e1de820cd30dc0cdf5b1e671720bd25"
    )
    assert published.manifest_hash == (
        "sha256:d9e4a11d5df721b6d78725ab96e08483d99792ad034981005521a7bc5d999d92"
    )


def test_partial_fetch_and_hash_mismatch_leave_previous_revision_active() -> None:
    old_id = "catalog-r1"
    new_id = "catalog-r2"
    old = document(f"place:one:{old_id}", "이전 설명")
    changed = document(f"place:one:{new_id}", "새 설명")
    second = document(f"place:two:{new_id}", "두 번째 설명")
    manifests = {
        old_id: manifest(old_id, NOW, [old]),
        new_id: manifest(new_id, NOW + timedelta(minutes=5), [changed, second]),
    }
    source = FixtureSpringCorpusClient(
        manifests,
        {old.document_id: old, changed.document_id: changed, second.document_id: second},
    )
    store = InMemoryCorpusStore()
    service = CorpusSyncService(source, store, embedding_profile="none")
    assert asyncio.run(service.pull(old_id)).status is SyncStatus.ACTIVE

    source.fail_document_id = second.document_id
    failed = asyncio.run(service.pull(new_id))

    assert failed.status is SyncStatus.REJECTED
    assert failed.error_code == "CORPUS_PARTIAL_FETCH"
    assert store.active_revision_id == old_id
    assert store.document_count(new_id) == 0

    source.fail_document_id = None
    source.documents[changed.document_id] = replace(changed, document_hash="sha256:bad")
    mismatched = asyncio.run(service.pull(new_id))

    assert mismatched.status is SyncStatus.REJECTED
    assert mismatched.error_code == "CORPUS_DOCUMENT_HASH_MISMATCH"
    assert store.active_revision_id == old_id
    assert store.document_count(new_id) == 0


def test_out_of_order_manifest_cannot_roll_back_active_revision() -> None:
    old_id = "catalog-r1"
    new_id = "catalog-r2"
    old = document(f"place:one:{old_id}", "이전 설명")
    new = document(f"place:one:{new_id}", "새 설명")
    source = FixtureSpringCorpusClient(
        {
            old_id: manifest(old_id, NOW, [old]),
            new_id: manifest(new_id, NOW + timedelta(minutes=5), [new]),
        },
        {old.document_id: old, new.document_id: new},
    )
    store = InMemoryCorpusStore()
    service = CorpusSyncService(source, store, embedding_profile="none")

    assert asyncio.run(service.pull(new_id)).status is SyncStatus.ACTIVE
    stale = asyncio.run(service.pull(old_id))

    assert stale.status is SyncStatus.REJECTED
    assert stale.error_code == "CORPUS_OUT_OF_ORDER"
    assert store.active_revision_id == new_id
    assert store.document_count(old_id) == 0


def test_tombstone_is_applied_only_with_complete_revision_activation() -> None:
    old_id = "catalog-r1"
    new_id = "catalog-r2"
    old = document(f"place:one:{old_id}", "삭제될 설명")
    surviving = document(f"place:two:{new_id}", "남는 설명")
    source = FixtureSpringCorpusClient(
        {
            old_id: manifest(old_id, NOW, [old]),
            new_id: manifest(
                new_id,
                NOW + timedelta(minutes=5),
                [surviving],
                tombstones=(old.document_id,),
            ),
        },
        {old.document_id: old, surviving.document_id: surviving},
    )
    store = InMemoryCorpusStore()
    service = CorpusSyncService(source, store, embedding_profile="none")

    asyncio.run(service.pull(old_id))
    result = asyncio.run(service.pull(new_id))

    assert result.status is SyncStatus.ACTIVE
    assert result.tombstone_count == 1
    assert store.active_document_ids == {surviving.document_id}
    assert source.acks[-1]["status"] == "ACTIVE"
