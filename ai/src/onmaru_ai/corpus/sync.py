from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Coroutine
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any, Protocol

from .index import CorpusChunk, CorpusIndexService


def _sha256(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


@dataclass(frozen=True)
class CorpusDocument:
    contract_version: str
    revision_id: str
    document_id: str
    kind: str
    source_id: str
    source_revision: str
    provenance_id: str
    category: str
    region: str
    publication_eligible: bool
    text: str
    document_hash: str

    @classmethod
    def create(
        cls,
        *,
        contract_version: str,
        revision_id: str,
        document_id: str,
        kind: str,
        source_id: str,
        source_revision: str,
        provenance_id: str,
        category: str,
        region: str,
        publication_eligible: bool,
        text: str,
    ) -> CorpusDocument:
        values: dict[str, object] = {
            "contractVersion": contract_version,
            "revisionId": revision_id,
            "documentId": document_id,
            "kind": kind,
            "sourceId": source_id,
            "sourceRevision": source_revision,
            "provenanceId": provenance_id,
            "category": category,
            "region": region,
            "publicationEligible": publication_eligible,
            "text": text,
        }
        return cls(
            contract_version=contract_version,
            revision_id=revision_id,
            document_id=document_id,
            kind=kind,
            source_id=source_id,
            source_revision=source_revision,
            provenance_id=provenance_id,
            category=category,
            region=region,
            publication_eligible=publication_eligible,
            text=text,
            document_hash=_sha256(values),
        )

    def has_valid_hash(self) -> bool:
        values = {
            "contractVersion": self.contract_version,
            "revisionId": self.revision_id,
            "documentId": self.document_id,
            "kind": self.kind,
            "sourceId": self.source_id,
            "sourceRevision": self.source_revision,
            "provenanceId": self.provenance_id,
            "category": self.category,
            "region": self.region,
            "publicationEligible": self.publication_eligible,
            "text": self.text,
        }
        return self.document_hash == _sha256(values)


@dataclass(frozen=True)
class CorpusManifestEntry:
    document_id: str
    kind: str
    document_hash: str | None
    state: str
    content_url: str | None

    def canonical(self) -> dict[str, object]:
        return {
            "contentUrl": self.content_url,
            "documentHash": self.document_hash,
            "documentId": self.document_id,
            "kind": self.kind,
            "state": self.state,
        }


@dataclass(frozen=True)
class CorpusManifest:
    contract_version: str
    revision_id: str
    published_at: datetime
    manifest_hash: str
    documents: tuple[CorpusManifestEntry, ...]

    @classmethod
    def create(
        cls,
        *,
        contract_version: str,
        revision_id: str,
        published_at: datetime,
        documents: tuple[CorpusManifestEntry, ...],
    ) -> CorpusManifest:
        manifest = cls(
            contract_version=contract_version,
            revision_id=revision_id,
            published_at=published_at,
            manifest_hash="",
            documents=documents,
        )
        return cls(
            contract_version=contract_version,
            revision_id=revision_id,
            published_at=published_at,
            manifest_hash=manifest.calculated_hash(),
            documents=documents,
        )

    def calculated_hash(self) -> str:
        return _sha256(
            {
                "contractVersion": self.contract_version,
                "revisionId": self.revision_id,
                "publishedAt": self.published_at.isoformat().replace("+00:00", "Z"),
                "documents": [entry.canonical() for entry in self.documents],
            }
        )


class SyncStatus(StrEnum):
    ACTIVE = "ACTIVE"
    REJECTED = "REJECTED"


@dataclass(frozen=True)
class CorpusSyncResult:
    revision_id: str
    manifest_hash: str
    status: SyncStatus
    inserted_document_count: int
    document_count: int
    tombstone_count: int
    error_code: str | None = None


class SpringCorpusClient(Protocol):
    def fetch_manifest(self, revision_id: str) -> Coroutine[Any, Any, CorpusManifest]: ...

    def fetch_document(
        self, revision_id: str, document_id: str
    ) -> Coroutine[Any, Any, CorpusDocument]: ...

    def acknowledge(self, acknowledgement: dict[str, object]) -> Coroutine[Any, Any, None]: ...


class InMemoryCorpusStore:
    def __init__(self) -> None:
        self._manifests: dict[str, CorpusManifest] = {}
        self._documents: dict[str, dict[str, CorpusDocument]] = {}
        self._chunks: dict[str, tuple[CorpusChunk, ...]] = {}
        self.active_revision_id: str | None = None

    @property
    def active_manifest(self) -> CorpusManifest | None:
        if self.active_revision_id is None:
            return None
        return self._manifests[self.active_revision_id]

    @property
    def active_document_ids(self) -> set[str]:
        if self.active_revision_id is None:
            return set()
        return set(self._documents[self.active_revision_id])

    @property
    def active_chunks(self) -> tuple[CorpusChunk, ...]:
        if self.active_revision_id is None:
            return ()
        return self._chunks.get(self.active_revision_id, ())

    def document_count(self, revision_id: str) -> int:
        return len(self._documents.get(revision_id, {}))

    def chunk_count(self, revision_id: str) -> int:
        return len(self._chunks.get(revision_id, ()))

    def commit(
        self,
        manifest: CorpusManifest,
        documents: tuple[CorpusDocument, ...],
        chunks: tuple[CorpusChunk, ...] = (),
    ) -> int:
        existing = self._manifests.get(manifest.revision_id)
        if existing is not None:
            if existing.manifest_hash != manifest.manifest_hash:
                raise ValueError("CORPUS_REVISION_CONFLICT")
            self.active_revision_id = manifest.revision_id
            return 0

        active = self.active_manifest
        if active is not None and manifest.published_at <= active.published_at:
            raise ValueError("CORPUS_OUT_OF_ORDER")

        # Build the entire immutable revision before changing the active pointer.
        revision_documents = {item.document_id: item for item in documents}
        self._documents[manifest.revision_id] = revision_documents
        self._chunks[manifest.revision_id] = chunks
        self._manifests[manifest.revision_id] = manifest
        self.active_revision_id = manifest.revision_id
        return len(revision_documents)


class CorpusSyncService:
    def __init__(
        self,
        client: SpringCorpusClient,
        store: InMemoryCorpusStore,
        *,
        embedding_profile: str,
        index: CorpusIndexService | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._client = client
        self._store = store
        self._embedding_profile = embedding_profile
        self._index = index
        self._clock = clock or (lambda: datetime.now(UTC))

    async def pull(self, revision_id: str) -> CorpusSyncResult:
        try:
            manifest = await self._client.fetch_manifest(revision_id)
        except Exception:
            return await self._reject(revision_id, "", "CORPUS_MANIFEST_FETCH_FAILED", 0, 0)

        error_code = self._validate_manifest(revision_id, manifest)
        if error_code is not None:
            return await self._reject(
                revision_id,
                manifest.manifest_hash,
                error_code,
                len(manifest.documents),
                self._tombstone_count(manifest),
            )

        active = self._store.active_manifest
        if active is not None:
            if manifest.revision_id == active.revision_id:
                if manifest.manifest_hash != active.manifest_hash:
                    return await self._reject(
                        revision_id,
                        manifest.manifest_hash,
                        "CORPUS_REVISION_CONFLICT",
                        len(manifest.documents),
                        self._tombstone_count(manifest),
                    )
                return await self._activate(manifest, (), inserted_count=0)
            if manifest.published_at <= active.published_at:
                return await self._reject(
                    revision_id,
                    manifest.manifest_hash,
                    "CORPUS_OUT_OF_ORDER",
                    len(manifest.documents),
                    self._tombstone_count(manifest),
                )

        fetched: list[CorpusDocument] = []
        try:
            for entry in manifest.documents:
                if entry.state == "TOMBSTONE":
                    continue
                item = await self._client.fetch_document(revision_id, entry.document_id)
                if (
                    item.revision_id != revision_id
                    or item.document_id != entry.document_id
                    or item.document_hash != entry.document_hash
                    or not item.has_valid_hash()
                ):
                    return await self._reject(
                        revision_id,
                        manifest.manifest_hash,
                        "CORPUS_DOCUMENT_HASH_MISMATCH",
                        len(manifest.documents),
                        self._tombstone_count(manifest),
                    )
                fetched.append(item)
        except Exception:
            return await self._reject(
                revision_id,
                manifest.manifest_hash,
                "CORPUS_PARTIAL_FETCH",
                len(manifest.documents),
                self._tombstone_count(manifest),
            )

        chunks: tuple[CorpusChunk, ...] = ()
        if self._index is not None:
            try:
                chunks = self._index.build(fetched)
            except Exception:
                return await self._reject(
                    revision_id,
                    manifest.manifest_hash,
                    "CORPUS_EMBEDDING_FAILED",
                    len(manifest.documents),
                    self._tombstone_count(manifest),
                )

        try:
            inserted_count = self._store.commit(manifest, tuple(fetched), chunks)
        except ValueError as exception:
            return await self._reject(
                revision_id,
                manifest.manifest_hash,
                str(exception),
                len(manifest.documents),
                self._tombstone_count(manifest),
            )
        return await self._activate(manifest, tuple(fetched), inserted_count=inserted_count)

    def _validate_manifest(self, revision_id: str, manifest: CorpusManifest) -> str | None:
        if manifest.contract_version != "1.0" or manifest.revision_id != revision_id:
            return "CORPUS_MANIFEST_INVALID"
        if manifest.manifest_hash != manifest.calculated_hash():
            return "CORPUS_MANIFEST_HASH_MISMATCH"
        if len({item.document_id for item in manifest.documents}) != len(manifest.documents):
            return "CORPUS_MANIFEST_INVALID"
        for item in manifest.documents:
            if item.state not in {"UPSERT", "TOMBSTONE"}:
                return "CORPUS_MANIFEST_INVALID"
            if item.state == "UPSERT" and (item.document_hash is None or item.content_url is None):
                return "CORPUS_MANIFEST_INVALID"
            if item.state == "TOMBSTONE" and (
                item.document_hash is not None or item.content_url is not None
            ):
                return "CORPUS_MANIFEST_INVALID"
        return None

    async def _activate(
        self,
        manifest: CorpusManifest,
        documents: tuple[CorpusDocument, ...],
        *,
        inserted_count: int,
    ) -> CorpusSyncResult:
        result = CorpusSyncResult(
            revision_id=manifest.revision_id,
            manifest_hash=manifest.manifest_hash,
            status=SyncStatus.ACTIVE,
            inserted_document_count=inserted_count,
            document_count=(
                len(documents)
                if inserted_count
                else self._store.document_count(manifest.revision_id)
            ),
            tombstone_count=self._tombstone_count(manifest),
        )
        await self._acknowledge(result)
        return result

    async def _reject(
        self,
        revision_id: str,
        manifest_hash: str,
        error_code: str,
        document_count: int,
        tombstone_count: int,
    ) -> CorpusSyncResult:
        result = CorpusSyncResult(
            revision_id=revision_id,
            manifest_hash=manifest_hash,
            status=SyncStatus.REJECTED,
            inserted_document_count=0,
            document_count=document_count,
            tombstone_count=tombstone_count,
            error_code=error_code,
        )
        await self._acknowledge(result)
        return result

    async def _acknowledge(self, result: CorpusSyncResult) -> None:
        acknowledgement: dict[str, object] = {
            "contractVersion": "1.0",
            "revisionId": result.revision_id,
            "manifestHash": result.manifest_hash,
            "status": result.status.value,
            "documentCount": result.document_count,
            "tombstoneCount": result.tombstone_count,
            "embeddingProfile": self._embedding_profile,
            "completedAt": self._clock().isoformat().replace("+00:00", "Z"),
        }
        if result.error_code is not None:
            acknowledgement["errorCode"] = result.error_code
        await self._client.acknowledge(acknowledgement)

    @staticmethod
    def _tombstone_count(manifest: CorpusManifest) -> int:
        return sum(item.state == "TOMBSTONE" for item in manifest.documents)
