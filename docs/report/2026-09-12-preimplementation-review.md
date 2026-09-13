# 2026-09-12 구현 전 설계 재심사

상태: review report. 코드, migration, 실제 endpoint, 배포 환경이 없는 문서 단계의 evidence 기반 평가다. 이 보고서는 설계를 수정하거나 구현을 승인하지 않는다.

## Executive Summary

현재 목표 구조는 Spring modular monolith가 business state와 관광 원천을 소유하고, FastAPI가 provider adapter와 선택 RAG를 소유하는 형태다. 여정은 REST command, SSE notification, DB snapshot 복구를 사용하고, 지도 후기는 행정구역 집계 뒤 명시적 REST 목록 조회를 사용한다.

이전 심사에서 지적한 지도 1.1/1.2, FastAPI RAG ownership, DB 불변식, 공개 후기 신고 모델은 크게 보강됐다. 하지만 여정 SSE의 **현재 단일 계약**은 아직 고정되지 않았다. 최신 contracts 문서와 과거 journey-exploration 문서가 서로 다른 MVP 통신 방식을 구현 지시로 남긴다. 따라서 최종 판정은 `IMPLEMENTABLE WITH FIXES`가 아니라 **`NEEDS ARCHITECTURE REVISION`**이다.

## Reviewed Documents

| 영역 | 확인 문서 | 판단 |
|---|---|---|
| 제품/요구 | [backend PRD](../planning/backend-prd.md), [readiness](../planning/implementation-readiness.md) | 현재 MVP 범위는 명확해졌으나 과거 MVP 전달서와 충돌 |
| 경계 | [module boundaries](../architecture/module-boundaries.md), [ADR drafts](../decisions/drafts/foundation-architecture.md) | Spring/FastAPI 역할과 modular monolith 방향은 적절 |
| AI | [application architecture](../ai/application-architecture.md), [guardrails](../ai/journey-guardrails.md), [retrieval](../ai/retrieval-and-rag.md) | provider/RAG 경계는 강함, corpus export contract는 미정 |
| API | [REST contract](../contracts/rest-api.md), [VisitReview OpenAPI](../contracts/openapi/visit-reviews.openapi.json) | 지도/후기는 1.2로 개선, 여정 REST/SSE OpenAPI는 없음 |
| 데이터 | [identity/journey](../spring/identity-and-journey.md), [DBML](../database/schema.dbml) | 논리 제약은 명확, executable DDL과 concurrency proof 없음 |
| 운영 | [runtime/reliability](../operations/runtime-and-reliability.md), [catalog ingestion](../spring/catalog-ingestion.md) | LKG/SSE/외부 API release gate는 좋음, hosting/runbook은 미정 |

## Reconstructed Architecture

```text
Browser -- public REST, SSE --> Spring Boot modular monolith -- typed internal call --> FastAPI -- structured output --> Gemini
                                  |                                      |
                                  +-- PostgreSQL/PostGIS business schemas  +-- ai schema: corpus, chunks, vectors
                                  +-- TourAPI/Odii ingestion               +-- revision-pinned corpus import
```

Spring은 actor, quota, run, candidate eligibility, canonical catalog, public response를 소유한다. FastAPI는 Gemini adapter, prompt/eval, corpus sync, retrieval을 소유한다. FastAPI는 Spring business schema에 접근하지 않고, Spring도 `ai` schema에 접근하지 않는다. 이 경계는 MVP 규모에 맞으며 별도 microservice 분해를 더 늘릴 이유는 없다.

## Traceability Assessment

| 흐름 | 상태 | 근거 |
|---|---|---|
| 여정 AI 요구 → FastAPI proposal → run/SSE | 부분 | PRD와 AI 설계는 연결되나 full OpenAPI/fixture 없음 |
| 지도 후기 요구 → 1.2 REST → OpenAPI → DBML | 대체로 연결 | region/review/report endpoint와 report/audit DBML 존재 |
| 외부 API → ingestion → LKG → release mode | 대체로 연결 | 실제 adapter capture는 구현 Issue에서 생성 예정 |
| RAG → corpus export → ai schema → eval | 부분 | ownership은 연결됐지만 export request/manifest/tombstone contract 없음 |
| moderation → report → operator action → audit | 부분 | 데이터와 public report는 있으나 운영자 처리 SLA/runbook 없음 |

## Scorecard

점수는 문서의 완성도이며 실행 증거가 아니다. confidence는 `중간`이다.

| 영역 | 점수 | 근거 |
|---|---:|---|
| Requirements & Traceability | 6/10 | 최신 PRD는 개선됐지만 7일 MVP 문서와 충돌 |
| Domain Architecture | 8/10 | context ownership과 dependency 방향이 명확 |
| API Design | 7/10 | 지도/후기 OpenAPI는 개선, 여정 SSE OpenAPI 부재 |
| Data Architecture | 8/12 | revision/제약/audit 설계는 좋고 migration SQL은 없음 |
| Reliability | 9/12 | LKG, deadline, SSE reset, baseline 경로가 구체적 |
| Security | 7/10 | OAuth/CSRF/service token 설계는 좋고 token replay/rotation 운영은 미확정 |
| Performance & Scalability | 7/10 | admission/SSE cap/query 기준은 있으나 실측 없음 |
| Observability | 6/8 | trace/run/error metric은 있으나 owner·dashboard·alert test 없음 |
| Deployment & Operations | 4/7 | rollback/backup 원칙은 있고 hosting/runbook은 미정 |
| Testing | 5/6 | fixture 목록은 좋으나 contract/E2E/load 구현 없음 |
| Cost & Complexity | 4/5 | 불필요한 broker/MSA를 피했으나 과거 문서가 복잡도를 되살릴 위험 |

**총점: 71/100**

## Critical Findings

### [P0] 여정 MVP 통신 기준이 SSE와 polling으로 동시에 남아 있다

- Evidence: 최신 REST 계약은 SSE notification + snapshot을 현재 계약으로 둔다. 반면 [7일 MVP FE handoff](../planning/journey-exploration/seven-day-mvp-fe-handoff.md)는 polling을 이번 MVP의 우선 기준으로 명시한다. [journey-exploration README](../planning/journey-exploration/README.md)도 같은 위치를 유지한다.
- Risk: FE와 Spring이 서로 다른 run lifecycle, reconnect, timeout, proxy 구성을 구현한다.
- Impact: 여정 구현 Issue를 안전하게 분해할 수 없다.
- Recommendation: 해당 7일 MVP 문서를 archived로 전환하거나 SSE+snapshot 계약으로 재기준화하고, `docs/contracts` 한 곳만 current source of truth로 선언한다.
- Reference criterion: requirements-to-contract traceability, single public contract.
- Confidence: 높음.

### [P1] 여정 REST/SSE는 prose만 있고 기계 판독 contract와 fixture가 없다

- Evidence: [REST 계약](../contracts/rest-api.md)은 `eventsUrl`, `run.stage`, `run.terminal`, `reset`을 정의하지만 OpenAPI 파일은 VisitReview 영역만 가진다. [readiness](../planning/implementation-readiness.md)는 이를 열린 gate로 기록한다.
- Risk: event ID, Last-Event-ID, terminal payload, auth expiry, reconnect backoff를 FE/BE가 다르게 해석한다.
- Impact: SSE가 연결은 되지만 재연결 때 상태가 틀어지는 형태의 장애가 남는다.
- Recommendation: `journey.openapi`와 event JSON Schema, 정상/재연결/reset/권한만료/cancel fixture를 contract-first로 만든다.
- Reference criterion: API design, failure recovery.
- Confidence: 높음.

### [P1] FastAPI RAG corpus export의 lifecycle contract가 빠져 있다

- Evidence: [AI architecture](../ai/application-architecture.md)는 Spring export와 FastAPI ingestion을 정의하지만 export path, manifest schema, document deletion/tombstone, revision retention, sync acknowledgement는 정의하지 않는다.
- Risk: FastAPI vector index가 숨김·삭제된 source를 계속 추천하거나 revision drift가 생긴다.
- Impact: evidence allowlist가 있어도 corpus freshness와 권리 변경을 증명할 수 없다.
- Recommendation: internal corpus export API의 schema, signed manifest hash, full/incremental semantics, tombstone, retry/idempotency, retention을 별도 contract로 고정한다.
- Reference criterion: data ownership, sync/cache reliability.
- Confidence: 높음.

### [P1] service token replay protection과 key rotation의 운영 책임이 미정이다

- Evidence: [journey guardrails](../ai/journey-guardrails.md)는 short-lived token과 replay-safe `jti`를 요구하지만, `jti` 저장소/TTL, signer/verifier key distribution, emergency rotation, FastAPI unavailable 시 동작이 정해지지 않았다.
- Risk: bearer token 재사용 또는 rotation 중 Spring-FastAPI 전체 단절.
- Impact: quota/cost abuse 방어와 AI availability가 hosting 선택에 따라 달라진다.
- Recommendation: hosting 독립 `InternalCallerAuthenticator` contract와 dev/staging/prod key lifecycle runbook을 정의한다.
- Reference criterion: service-to-service authentication and secret lifecycle.
- Confidence: 중간-높음.

### [P1] moderation은 데이터 모델은 있지만 운영 처리 정책이 없다

- Evidence: report와 audit 모델은 [community DBML](../database/modules/community.dbml)에 있고 public 신고는 [REST 계약](../contracts/rest-api.md)에 있다. 그러나 운영자 알림, triage SLA, appeal/false-positive, emergency PII hide의 책임자는 없다.
- Risk: 공개 UGC의 신고가 쌓여도 처리되지 않거나 운영자가 DB를 직접 수정하게 된다.
- Impact: 공개 후기 출시 뒤 안전·신뢰 리스크가 남는다.
- Recommendation: 최소 운영 runbook에 alert route, 일일 triage, emergency hide 권한, audit retention, test drill을 추가한다.
- Reference criterion: public UGC operational safety.
- Confidence: 높음.

### [P2] DBML 불변식은 선언됐지만 migration dialect와 검증 실행계획이 없다

- Evidence: [identity/journey](../spring/identity-and-journey.md)는 XOR, partial unique, lock order를 DDL 필수로 적고 [discovery DBML](../database/modules/discovery.dbml)은 note로 표시한다.
- Risk: PostgreSQL partial index/CHECK/CAS query가 구현 시점에 서로 달라질 수 있다.
- Recommendation: migration Issue 전에 PostgreSQL DDL sketch와 concurrent start/cancel/complete integration matrix를 작성한다.
- Confidence: 높음.

## Failure Scenario Results

| 시나리오 | 문서 대응 | 남은 검증 |
|---|---|---|
| Gemini timeout/malformed output | terminal FAILED, 새 baseline run | SSE terminal 후 reconnect fixture |
| SSE proxy restart | `reset` + GET snapshot | ingress buffering/idle timeout 실측 |
| TourAPI 429/HTTP200 error | retry/LKG/LIVE gate | 실제 redacted capture와 scheduler test |
| sync partial failure | private revision/LKG/lease fence | real DB kill/restart test |
| duplicate run/save | idempotency, partial unique, lock order | PostgreSQL integration test |
| member deletion during AI | DELETING/CAS discard | E2E late-result test |
| report abuse or PII | report/audit/HIDDEN policy | operator drill, escalation route |
| migration/rollback | expand-contract/restore proposal | actual migration and restore drill |

## Overengineering / Underengineering

Spring modular monolith + FastAPI bounded AI service는 적정하다. Redis, Kafka, durable event store, service mesh, separate vector DB는 현재 요구만으로 도입하지 않는다. SSE replay를 in-memory notification buffer와 authoritative GET snapshot으로 제한한 선택도 적정하다.

반대로 과거 journey-exploration 문서를 current처럼 남기는 것은 문서 복잡도를 실제 구현 복잡도로 전파하는 under-governance다. RAG는 FastAPI owner를 확정했지만 corpus export contract 없이는 under-specified다.

## Recommended Remediation Order

1. P0: current MVP transport source를 `docs/contracts`로 단일화하고 polling 기준 문서를 archive/rebaseline한다.
2. P1: 여정 REST/SSE OpenAPI, event schema, FE/BE fixture를 작성한다.
3. P1: FastAPI corpus export/sync contract와 service-token lifecycle contract를 작성한다.
4. P1: moderation runbook과 operator drill acceptance criteria를 작성한다.
5. P2: PostgreSQL migration DDL sketch와 concurrency integration matrix를 작성한다.
6. 실제 API capture, load, restore drill 결과로 readiness를 갱신한 후 새 GitHub Issue graph를 발행한다.

## Final Verdict

**`NEEDS ARCHITECTURE REVISION`**

핵심 구조는 재설계할 필요가 없다. 현재 필요한 revision은 범위 재논의가 아니라, 단일 SSE 계약과 FastAPI corpus/identity 운영 계약을 문서상 하나의 실행 기준으로 닫는 일이다.
