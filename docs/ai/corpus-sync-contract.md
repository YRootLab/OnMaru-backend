# Spring-to-FastAPI corpus sync contract

> **공통 프로토콜:** 새 홈 여정 RAG의 필수 코퍼스 범위·활성화·인프라 기준은 [새 아키텍처](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/architecture.md)와 [ADR-0017](../decisions/0017-home-journey-hybrid-rag.md)을 따른다. 이 문서의 optional RAG 출시 게이트 표현은 2026-09 설계의 기록이다.

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

- Spring retains each published RAG export payload for 30 days and while any FastAPI sync is pending; manifest/hash metadata lasts 180 days. FastAPI retains the previous active corpus as an eligible rollback candidate for up to 30 days, with at most one additional 14-day rollback candidate. Revocation deletion overrides ordinary retention. See [corpus lifecycle and GC](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/corpus-lifecycle-and-gc.md) for holds, audit metadata, backup replay, and physical deletion.
- A partial fetch, hash mismatch, expired internal token, or embedding failure produces `REJECTED`; FastAPI retries idempotently within its sync budget. It does not serve a half-indexed revision.
- A proposal names the candidate/source revision allowed by Spring. `EvidenceRetriever` rejects any chunk outside that revision and candidate allowlist. If it is not active, Spring uses deterministic baseline or returns `CONTEXT_NOT_INDEXED`; stale corpus substitution is prohibited.
- 긴급 공개 철회·권리 취소는 전체 revision 재색인과 별도로 Spring의 현재 공개 자격을 즉시 변경하고, FastAPI에 단조 증가 `sequence`가 있는 `SOURCE_REVISION|PLACE` 철회 이벤트로 전달한다. FastAPI는 연속 sequence만 ACK하고 출처의 특정 revision과 장소 전체의 차단 집합을 분리한다. Spring이 현재 sequence와 heartbeat를 제공하므로 새 철회가 없는 시간과 전파 중단을 구별한다. 차단 집합의 신선도를 확인할 수 없으면 RAG를 중지한다. 최종 snapshot 저장 직전에 Spring이 다시 현재 공개 자격을 확인한다. 전파 목표와 강제 지연 검증은 [운영 게이트](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/operations-and-evaluation.md)를 따른다.
- Grafana records `publishedRevision`, `activeCorpusRevision`, manifest ACK lag, rejected count, and sync duration as low-cardinality aggregates. Raw corpus text is never telemetry.

## Required implementation proof

Fixture coverage must prove: duplicate pull produces no duplicate chunk; a tombstone is absent after promotion; failed revision leaves old pointer active; out-of-order old manifest cannot roll back pointer; invalid hash is rejected; candidate allowlist violation is zero. The new home journey RAG release cannot ship before this proof and its offline quality/cost gates pass.
