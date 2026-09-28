from __future__ import annotations

from datetime import datetime
from typing import Literal

from fastapi import FastAPI, Header, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from onmaru_ai.config.secrets import SecretProvider
from onmaru_ai.security.internal_auth import validate_internal_token

from .index import CorpusIndexService
from .sync import CorpusDocument, CorpusManifest, CorpusManifestEntry, InMemoryCorpusStore


class CorpusManifestEntryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    document_id: str = Field(alias="documentId")
    kind: str
    document_hash: str | None = Field(default=None, alias="documentHash")
    state: Literal["UPSERT", "TOMBSTONE"]
    content_url: str | None = Field(default=None, alias="contentUrl")


class CorpusDocumentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    contract_version: str = Field(alias="contractVersion")
    revision_id: str = Field(alias="revisionId")
    document_id: str = Field(alias="documentId")
    kind: str
    source_id: str = Field(alias="sourceId")
    source_revision: str = Field(alias="sourceRevision")
    provenance_id: str = Field(alias="provenanceId")
    category: str
    region: str
    publication_eligible: bool = Field(alias="publicationEligible")
    text: str
    document_hash: str = Field(alias="documentHash")


class CorpusSyncRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    contract_version: str = Field(alias="contractVersion")
    revision_id: str = Field(alias="revisionId")
    published_at: datetime = Field(alias="publishedAt")
    manifest_hash: str = Field(alias="manifestHash")
    documents: list[CorpusManifestEntryRequest]
    payloads: list[CorpusDocumentRequest] = Field(default_factory=list)


def install_corpus_rest(
    app: FastAPI,
    secret_provider: SecretProvider,
    store: InMemoryCorpusStore,
    index: CorpusIndexService,
) -> None:
    @app.post("/internal/v1/corpus/sync", status_code=status.HTTP_200_OK)
    async def sync_corpus(
        request: Request,
        authorization: str = Header(default=""),
    ) -> dict[str, object]:
        validate_internal_token(
            authorization,
            secret_provider,
            required_scope="corpus.sync:write",
        )
        try:
            body = CorpusSyncRequest.model_validate(await request.json())
        except (ValueError, ValidationError) as exc:
            raise HTTPException(status_code=422, detail="CORPUS_CONTRACT_INVALID") from exc

        manifest = CorpusManifest(
            contract_version=body.contract_version,
            revision_id=body.revision_id,
            published_at=body.published_at,
            manifest_hash=body.manifest_hash,
            documents=tuple(
                CorpusManifestEntry(
                    document_id=item.document_id,
                    kind=item.kind,
                    document_hash=item.document_hash,
                    state=item.state,
                    content_url=item.content_url,
                )
                for item in body.documents
            ),
        )
        if manifest.contract_version != "1.0" or manifest.revision_id != body.revision_id:
            raise HTTPException(status_code=422, detail="CORPUS_MANIFEST_INVALID")
        if manifest.manifest_hash != manifest.calculated_hash():
            raise HTTPException(status_code=422, detail="CORPUS_MANIFEST_HASH_MISMATCH")

        by_id = {item.document_id: item for item in body.payloads}
        if len(by_id) != len(body.payloads):
            raise HTTPException(status_code=422, detail="CORPUS_DOCUMENT_DUPLICATE")
        documents: list[CorpusDocument] = []
        for entry in manifest.documents:
            if entry.state == "TOMBSTONE":
                continue
            item = by_id.get(entry.document_id)
            if (
                item is None
                or item.revision_id != body.revision_id
                or item.document_hash != entry.document_hash
            ):
                raise HTTPException(status_code=422, detail="CORPUS_DOCUMENT_MISMATCH")
            document = CorpusDocument(
                contract_version=item.contract_version,
                revision_id=item.revision_id,
                document_id=item.document_id,
                kind=item.kind,
                source_id=item.source_id,
                source_revision=item.source_revision,
                provenance_id=item.provenance_id,
                category=item.category,
                region=item.region,
                publication_eligible=item.publication_eligible,
                text=item.text,
                document_hash=item.document_hash,
            )
            if not document.has_valid_hash():
                raise HTTPException(status_code=422, detail="CORPUS_DOCUMENT_HASH_MISMATCH")
            documents.append(document)

        active = store.active_manifest
        if (
            active is not None
            and body.revision_id != active.revision_id
            and body.published_at <= active.published_at
        ):
            raise HTTPException(status_code=409, detail="CORPUS_OUT_OF_ORDER")
        chunks = index.build(documents)
        inserted = store.commit(manifest, tuple(documents), chunks)
        return {
            "revisionId": body.revision_id,
            "manifestHash": body.manifest_hash,
            "status": "ACTIVE",
            "documentCount": len(documents),
            "chunkCount": len(chunks),
            "insertedDocumentCount": inserted,
        }
