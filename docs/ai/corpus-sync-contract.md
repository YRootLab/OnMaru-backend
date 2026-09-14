# Spring-to-FastAPI corpus sync contract

FastAPI owns corpus ingestion, chunking, embeddings, retrieval, `ai` schema migration, and active corpus pointer. Spring owns canonical public data and exposes a read-only, revision-pinned internal corpus export. Neither service reads the other's runtime schema. This is a design contract; no endpoint is deployed yet.

## Protocol

1. Spring publishes a canonical dataset revision only after its normal LKG checks pass.
2. FastAPI requests `GET /internal/ai/corpus/manifests/{revisionId}` using the internal caller credential. The response is immutable for that revision.
3. FastAPI verifies the signed manifest hash, then fetches only documents named by the manifest through `GET /internal/ai/corpus/revisions/{revisionId}/documents/{documentId}`.
4. FastAPI writes chunks and embeddings into a private, non-active corpus revision. Repeated document or manifest fetches are idempotent by `manifestHash` and `documentHash`.
5. FastAPI validates counts, hashes, retrieval allowlist, and embedding profile. Only then does it atomically point `ai.active_corpus` at the new revision.
6. FastAPI posts an acknowledgement to `POST /internal/ai/corpus/manifests/{revisionId}/acks`. Spring records delivery state only; it never treats an ACK as permission to delete its canonical revision.

The request path is private-network only and accepts no browser credential. Every call uses the service authentication contract in [internal service authentication](internal-service-authentication.md).

## Manifest and document shape

```json
{
  "contractVersion": "1.0",
  "revisionId": "catalog-2026-09-13T03:00:00Z-01",
  "publishedAt": "2026-09-13T03:05:00Z",
  "manifestHash": "sha256:...",
  "documents": [
    {"documentId": "place:uuid:revision", "kind": "PLACE", "documentHash": "sha256:...", "state": "UPSERT", "contentUrl": "/internal/..."},
    {"documentId": "place:old-uuid:revision", "kind": "PLACE", "state": "TOMBSTONE"}
  ]
}
```

`UPSERT` document content is sanitized public text plus stable opaque source ID, source revision, provenance identifier, category, region, publication eligibility, and content hash. It excludes raw provider payloads, member data, query text, precise user location, secrets, and unreviewed URLs. `TOMBSTONE` has no content URL and removes the document from retrieval before active-pointer promotion. A tombstone is irreversible within that revision; an older corpus revision must never be reactivated after a newer tombstone was acknowledged.

The ACK shape is `{contractVersion, revisionId, manifestHash, status: ACTIVE|REJECTED, documentCount, tombstoneCount, embeddingProfile, completedAt, errorCode?}`. `ACTIVE` means FastAPI's pointer changed after all checks. `REJECTED` preserves the previous pointer and exposes only a non-sensitive error code.

## Failure, retention, and activation

- Spring retains a manifest and its document payloads for at least seven days after publication and while any FastAPI sync is pending. FastAPI retains the previous active corpus until a later revision is ACTIVE.
- A partial fetch, hash mismatch, expired internal token, or embedding failure produces `REJECTED`; FastAPI retries idempotently within its sync budget. It does not serve a half-indexed revision.
- A proposal names the candidate/source revision allowed by Spring. `EvidenceRetriever` rejects any chunk outside that revision and candidate allowlist. If it is not active, Spring uses deterministic baseline or returns `CONTEXT_NOT_INDEXED`; stale corpus substitution is prohibited.
- Grafana records `publishedRevision`, `activeCorpusRevision`, manifest ACK lag, rejected count, and sync duration as low-cardinality aggregates. Raw corpus text is never telemetry.

## Required implementation proof

Fixture coverage must prove: duplicate pull produces no duplicate chunk; a tombstone is absent after promotion; failed revision leaves old pointer active; out-of-order old manifest cannot roll back pointer; invalid hash is rejected; candidate allowlist violation is zero. RAG remains disabled until this proof and offline quality/cost gates pass.
