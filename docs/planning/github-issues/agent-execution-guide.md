# OnMaru Backend Issue별 구현 설명과 Multi-Agent 실행 가이드

2026-09-14 기준이다. GitHub Issue가 실행 상태의 Source of Truth이며, 이 문서는 각 Leaf의 구현 의미와 여러 Agent의 권장 담당 범위를 빠르게 이해하기 위한 안내서다. 상세 Acceptance Criteria와 최신 blocked-by 관계는 각 GitHub Issue를 우선한다.

## 문서 읽는 방법

- `구현`: 이 Issue가 최종적으로 만들어야 하는 결과물이다.
- `범위`: 한 Agent가 해당 PR에서 책임질 기능·모듈 경계다.
- `완료 증거`: Issue를 닫기 전에 통과해야 하는 검증 방법이다.
- `Wn`: dependency DAG로 계산한 Execution Wave다. Wave가 같아도 touch point가 겹치면 동시에 수정하지 않는다.
- Root #52와 Track #53~#60은 관리용이며 실제 구현 코드를 소유하지 않는다.

## 관리용 Root와 Track

| Issue | 역할 |
|---|---|
| [#52 Root](https://github.com/YRootLab/OnMaru-backend/issues/52) | 전체 backend 구현, 통합 gate와 출시 상태를 추적한다. |
| [#53 Track A — 기반·계약·개발환경](https://github.com/YRootLab/OnMaru-backend/issues/53) | 9개 Leaf의 진행·검증·merge 상태를 관리하며 코드를 직접 구현하지 않는다. |
| [#54 Track B — 데이터베이스·관광공사 수집](https://github.com/YRootLab/OnMaru-backend/issues/54) | 16개 Leaf의 진행·검증·merge 상태를 관리하며 코드를 직접 구현하지 않는다. |
| [#55 Track C — 한옥·장소 공개 API](https://github.com/YRootLab/OnMaru-backend/issues/55) | 6개 Leaf의 진행·검증·merge 상태를 관리하며 코드를 직접 구현하지 않는다. |
| [#56 Track D — 인증·개인 저장](https://github.com/YRootLab/OnMaru-backend/issues/56) | 7개 Leaf의 진행·검증·merge 상태를 관리하며 코드를 직접 구현하지 않는다. |
| [#57 Track E — 후기·지도·Odii](https://github.com/YRootLab/OnMaru-backend/issues/57) | 13개 Leaf의 진행·검증·merge 상태를 관리하며 코드를 직접 구현하지 않는다. |
| [#58 Track F — FastAPI AI 서버](https://github.com/YRootLab/OnMaru-backend/issues/58) | 9개 Leaf의 진행·검증·merge 상태를 관리하며 코드를 직접 구현하지 않는다. |
| [#59 Track G — 여정 실행](https://github.com/YRootLab/OnMaru-backend/issues/59) | 11개 Leaf의 진행·검증·merge 상태를 관리하며 코드를 직접 구현하지 않는다. |
| [#60 Track H — 운영·출시](https://github.com/YRootLab/OnMaru-backend/issues/60) | 9개 Leaf의 진행·검증·merge 상태를 관리하며 코드를 직접 구현하지 않는다. |

## Track A — 기반·계약·개발환경

| Issue | P/W | 구현 | 범위 | 완료 증거 |
|---|---:|---|---|---|
| [#63 F01 — Java 21·Spring Boot·Gradle Wrapper 멀티모듈 골격 구축](https://github.com/YRootLab/OnMaru-backend/issues/63) | P0/W0 | 새 checkout에서 Spring API를 동일 버전으로 boot·test할 수 있는 기반을 만든다. | Java 21 toolchain, 검증된 Spring Boot/Gradle 정확 버전, wrapper, dependency lock, spring-api와 최소 모듈, health, repository gitignore | clean checkout Gradle Wrapper check, Boot smoke, git check-ignore fixture |
| [#68 F02 — Spring 모듈 port·adapter 경계와 ArchUnit 규칙 구현](https://github.com/YRootLab/OnMaru-backend/issues/68) | P0/W1 | core의 framework 독립성과 module DAG를 CI에서 강제한다. | package 규칙, 금지 import/cycle fixture, composition bridge | ArchUnit negative/positive tests |
| [#64 F03 — FastAPI Python 서비스 실행·테스트 골격 구축](https://github.com/YRootLab/OnMaru-backend/issues/64) | P0/W0 | Spring과 독립 배포되는 AI service skeleton을 만든다. | pyproject, app factory, health/readiness, lint/type/test | Python CI와 ASGI smoke |
| [#65 F04 — PostgreSQL·PostGIS 로컬 통합 테스트 환경 구축](https://github.com/YRootLab/OnMaru-backend/issues/65) | P0/W0 | migration과 공간 SQL을 실제 DB에서 검증할 기반을 만든다. | Testcontainers, local compose, readiness, test DB reset | container integration smoke |
| [#69 F05 — OpenAPI·JSON Schema·DBML 검증 CI 구축](https://github.com/YRootLab/OnMaru-backend/issues/69) | P0/W1 | 계약과 schema drift를 PR에서 차단한다. | OpenAPI lint, JSON fixture/schema, DBML compile, generated artifact diff | CI positive/negative fixture |
| [#70 F06 — Spring 공통 오류·cursor·멱등 command web 기반 구현](https://github.com/YRootLab/OnMaru-backend/issues/70) | P0/W1 | 모든 공개 API가 동일 오류·paging·idempotency 규칙을 사용하게 한다. | error envelope, requestId, validation, cursor codec, Idempotency-Key storage port | MockMvc와 concurrency test |
| [#131 F07 — Backend 설계 입력 snapshot·provenance manifest 고정](https://github.com/YRootLab/OnMaru-backend/issues/131) | P0/W0 | 개인 절대경로 없이 모든 Agent와 CI가 동일한 backend 입력 문서를 재현한다. | allowlisted repository-local snapshot, upstream repository/commit/path/hash manifest, 갱신 절차, secret scan | clean checkout manifest/hash/secret-scan tests |
| [#136 F08 — 공개 API rate-limit·IP/member admission 기반 구현](https://github.com/YRootLab/OnMaru-backend/issues/136) | P0/W3 | 로그인·작성·신고·여정 command의 남용을 원자적으로 제한하고 일관된 429를 반환한다. | trusted proxy client identity, operation budget policy, atomic admission store, Retry-After, metrics | fake clock·proxy header·DB concurrency integration tests |
| [#132 F09 — 인증·회원·SavedResource OpenAPI·fixture 동결](https://github.com/YRootLab/OnMaru-backend/issues/132) | P0/W1 | session·CSRF·회원 lifecycle·개인 저장 API를 구현 전에 기계 판독 계약으로 고정한다. | auth/login/callback/logout/csrf, members/me, place/Odii save, saved list, timeline schemas and fixtures | OpenAPI validator and fixture schema tests |

## Track B — 데이터베이스·관광공사 수집

| Issue | P/W | 구현 | 범위 | 완료 증거 |
|---|---:|---|---|---|
| [#67 D01 — Flyway migration 소유권·버전·baseline 체계 구현](https://github.com/YRootLab/OnMaru-backend/issues/67) | P0/W1 | 빈 DB와 기존 DB를 동일 schema로 올리는 실행 migration 기준을 만든다. | schema namespace, version registry, baseline, migration checksum policy, database role grants | Flyway Testcontainers matrix |
| [#75 D02 — Catalog canonical place·source·revision schema 구현](https://github.com/YRootLab/OnMaru-backend/issues/75) | P0/W2 | 공급자 ID와 안정된 placeId, versioned projection을 저장한다. | region/place/source identity, source refs, dataset/place versions, PostGIS indexes | PostGIS repository integration tests |
| [#76 D03 — Member·OAuth identity·session·guest grant schema 구현](https://github.com/YRootLab/OnMaru-backend/issues/76) | P0/W2 | provider 독립 회원과 opaque 인증 상태를 저장한다. | members, oauth identities, sessions, login state, guest grants, deletion ledger | DB constraint/concurrency tests |
| [#77 D04 — Exploration·run·saved journey·saved resource schema 구현](https://github.com/YRootLab/OnMaru-backend/issues/77) | P1/W2 | 여정 실행과 개인 저장의 durable truth를 만든다. | exploration, turns, runs, proposals, idempotency, saved journeys/resources | transaction/concurrency Testcontainers |
| [#78 D05 — VisitReview·like·report·moderation schema 구현](https://github.com/YRootLab/OnMaru-backend/issues/78) | P1/W2 | 방문 후기와 운영 판정을 원자적으로 저장한다. | reviews, likes, reports, moderation audit, partial indexes | DB constraints and repository tests |
| [#79 D06 — Odii spot·story·language·transcript revision schema 구현](https://github.com/YRootLab/OnMaru-backend/issues/79) | P1/W2 | 언어별 Odii identity와 대본 provenance를 저장한다. | audio identities, versions, transcripts, place links | audio repository integration tests |
| [#80 D07 — Sync run·lease·checkpoint·quarantine·outbox schema 구현](https://github.com/YRootLab/OnMaru-backend/issues/80) | P0/W2 | 수집 실패 복구와 원자 게시를 위한 operations 저장소를 만든다. | schedules, runs, leases, checkpoints, watermarks, quarantine, outbox | DB concurrency tests |
| [#85 D08 — 전체 migration·동시성·rollback 계약 테스트 완성](https://github.com/YRootLab/OnMaru-backend/issues/85) | P0/W4 | context schema를 함께 올리고 핵심 race를 증명한다. | empty/upgrade/downgrade policy, concurrent login/save/like/run/publish matrix | Testcontainers full matrix |
| [#135 D09 — Insights 관측·target link 실행 migration 구현](https://github.com/YRootLab/OnMaru-backend/issues/135) | P0/W3 | 방문자·관광지 집중률 관측을 provenance와 공간 단위가 보존되는 executable schema에 저장한다. | visitor observations, tourism targets, target-place links, concentration observations, keys/indexes/checks | PostgreSQL/PostGIS repository migration tests |
| [#66 P01 — 관광공사 API 실제 응답·quota·license qualification](https://github.com/YRootLab/OnMaru-backend/issues/66) | P0/W0 | 서버 호출 가능한 operation과 공개 가능한 필드를 증거로 고정한다. | 국문/위치/상세 API live capture, quota/header, 이용약관·출처, redacted fixtures | redacted live capture manifest와 schema check |
| [#73 P02 — TourAPI HTTP client·envelope parser 구현](https://github.com/YRootLab/OnMaru-backend/issues/73) | P0/W1 | 외부 응답을 typed source record 또는 명시 오류로 변환한다. | timeouts, retry classification, content type, XML/JSON envelope, pagination | mock HTTP contract tests |
| [#88 P03 — 03:00 KST sync scheduler·lease·checkpoint 구현](https://github.com/YRootLab/OnMaru-backend/issues/88) | P0/W3 | 누락 일정을 복구하며 dataset 작업을 중복 실행하지 않는다. | schedule catch-up, fenced lease heartbeat, page checkpoint, retry schedule | fake clock + DB + mock source tests |
| [#89 P04 — Source validation·category mapping·quarantine 구현](https://github.com/YRootLab/OnMaru-backend/issues/89) | P0/W3 | 허용 category만 canonical 후보로 만들고 잘못된 row를 격리한다. | schema validation, normalized hash, allowlist version, quarantine redaction | mapping fixtures and redaction tests |
| [#95 P05 — Dataset revision 원자 게시·watermark·tombstone 구현](https://github.com/YRootLab/OnMaru-backend/issues/95) | P0/W4 | 완주한 revision만 공개하고 실패 시 LKG를 유지한다. | stage validation, publish CAS, watermark advance, deletion/tombstone, revision GC hook | publication concurrency integration tests |
| [#90 P06 — 관광 관측 DataLab 실제 계약·adapter 구현](https://github.com/YRootLab/OnMaru-backend/issues/90) | P2/W4 | 날짜·공간 단위가 명확한 관측만 typed observation으로 저장한다. | live qualification, response fixture, adapter, observation provenance/status | provider fixture and DB integration tests |
| [#137 P07 — 행정구역 경계 source qualification·revision import 구현](https://github.com/YRootLab/OnMaru-backend/issues/137) | P1/W3 | 좌표 resolve와 지역 관측에 사용할 행정경계를 출처·권리·revision과 함께 안전하게 적재한다. | source/license qualification, SIDO/SIGUNGU code mapping, MultiPolygon validation, revision import/activation | source manifest, PostGIS import and boundary resolution tests |

## Track C — 한옥·장소 공개 API

| Issue | P/W | 구현 | 범위 | 완료 증거 |
|---|---:|---|---|---|
| [#62 C01 — R1 한옥·장소·찜 OpenAPI와 fixture 동결](https://github.com/YRootLab/OnMaru-backend/issues/62) | P0/W0 | Spring과 FE가 공유할 R1 기계 계약을 완성한다. | hanok list/detail/monthly, place detail, saved place/list/timeline schemas and examples | OpenAPI lint and example validation |
| [#98 C02 — 한옥 목록 검색·필터·cursor API 구현](https://github.com/YRootLab/OnMaru-backend/issues/98) | P1/W5 | published snapshot에서 한옥 후보를 안정적으로 조회한다. | keyword/region/category/hasImage, stable cursor, count/page response | MockMvc + PostGIS fixture tests |
| [#99 C03 — Canonical place·한옥 상세 API 구현](https://github.com/YRootLab/OnMaru-backend/issues/99) | P1/W5 | 공개 가능한 현재 projection과 savedByMe를 상세 DTO로 제공한다. | place/hanok detail, nullable provenance fields, eligibility, optional auth saved state | API contract integration tests |
| [#84 C04 — 월별 한옥 editorial edition·placement API 구현](https://github.com/YRootLab/OnMaru-backend/issues/84) | P1/W3 | 사람이 게시한 월별 순서와 이야기를 재현 가능하게 제공한다. | edition/placement persistence, publish validation, monthly query | domain + API + DB tests |
| [#100 C05 — 주변·지도 canonical 장소 조회 API 구현](https://github.com/YRootLab/OnMaru-backend/issues/100) | P1/W5 | 공개 장소를 반경·거리순으로 안전하게 조회한다. | nearby, category filter, coordinate validation, distance projection | PostGIS API integration tests |
| [#107 C06 — R1 serializer·OpenAPI·LKG 통합 게이트](https://github.com/YRootLab/OnMaru-backend/issues/107) | P1/W7 | 한옥 R1을 FE 전달 가능한 실행 계약으로 증명한다. | serializer examples, fixture seed, source outage E2E, Swagger publication, place save/savedByMe | contract + E2E suite |

## Track D — 인증·개인 저장

| Issue | P/W | 구현 | 범위 | 완료 증거 |
|---|---:|---|---|---|
| [#86 I01 — Kakao OAuth state·callback·opaque session 구현](https://github.com/YRootLab/OnMaru-backend/issues/86) | P0/W4 | provider token을 브라우저에 저장하지 않는 회원 로그인을 제공한다. | login redirect, state PKCE/nonce policy, callback, identity link, session rotate | OAuth mock + DB security tests |
| [#92 I02 — Guest grant·exploration 소유권 승계 구현](https://github.com/YRootLab/OnMaru-backend/issues/92) | P0/W5 | 로그인 전 탐색 소유권을 제한된 grant로 안전하게 연결한다. | guest cookie hash, grant expiry/scope, login claim, replay prevention | ownership integration tests |
| [#87 I03 — CSRF·cookie·인가·private cache 보안 경계 구현](https://github.com/YRootLab/OnMaru-backend/issues/87) | P0/W3 | unsafe request와 개인 응답의 browser 보안 정책을 강제한다. | csrf token API, cookie flags, CORS, no-store, ownership 404, security headers | MockMvc security negative tests |
| [#93 I04 — 회원 조회·logout·탈퇴·보존 lifecycle 구현](https://github.com/YRootLab/OnMaru-backend/issues/93) | P1/W5 | 회원 세션 폐기와 비동기 탈퇴를 재노출 없이 처리한다. | members/me, logout, DELETING, cleanup command, deletion ledger | lifecycle integration tests |
| [#108 I05 — Canonical 관광 장소 찜 PUT·DELETE 구현](https://github.com/YRootLab/OnMaru-backend/issues/108) | P1/W6 | 한옥·지도·Odii 연결 카드가 동일 placeId 저장 상태를 공유한다. | save/unsave place, eligibility port, idempotent desired state, savedByMe projection | concurrent API/DB tests |
| [#113 I06 — Odii story 저장과 saved-resource 목록 구현](https://github.com/YRootLab/OnMaru-backend/issues/113) | P1/W7 | 보조 재생 저장과 type별 개인 목록을 제공한다. | Odii PUT/DELETE, type-required list, cursor, current hydration | API contract tests |
| [#126 I07 — 내 월간 활동 타임라인 read model 구현](https://github.com/YRootLab/OnMaru-backend/issues/126) | P1/W11 | 월별 찜·여정·후기 활동을 날짜 그룹으로 제공한다. | KST month, day groups, target union, unavailableCount, cursor | projection integration tests |

## Track E — 후기·지도·Odii

| Issue | P/W | 구현 | 범위 | 완료 증거 |
|---|---:|---|---|---|
| [#102 M01 — 행정구역 resolve·VisitReview 지역 집계 API 구현](https://github.com/YRootLab/OnMaru-backend/issues/102) | P1/W5 | 좌표 후보 지역과 공개 후기 수를 계층적으로 제공한다. | regions/resolve, sido/sigungu counts, regionRevision, unassignedCount | PostGIS + API tests |
| [#110 M02 — VisitReview ALL·REGION·place 목록과 cursor 구현](https://github.com/YRootLab/OnMaru-backend/issues/110) | P1/W6 | 공개 후기 목록을 scope별 안정 순서로 조회한다. | ALL, REGION, place filter, limit+1 cursor, mine/likedByMe | API/DB paging tests |
| [#118 M03 — VisitReview 작성·본인 삭제·멱등성 구현](https://github.com/YRootLab/OnMaru-backend/issues/118) | P1/W7 | 회원이 eligible 장소에 짧은 후기를 안전하게 작성·삭제한다. | POST place review, DELETE own review, text validation, idempotency | security/idempotency integration tests |
| [#122 M04 — VisitReview 좋아요 desired-state API 구현](https://github.com/YRootLab/OnMaru-backend/issues/122) | P1/W8 | toggle race 없이 본인 좋아요 상태와 count를 갱신한다. | like/unlike, self-like prohibition, atomic count | concurrency API tests |
| [#123 M05 — VisitReview 신고·moderation audit·운영 명령 구현](https://github.com/YRootLab/OnMaru-backend/issues/123) | P1/W8 | 신고와 운영 판정을 권한·감사 기록과 함께 처리한다. | report dedupe, PUBLISHED/HIDDEN/REMOVED transition, operator command, audit | authorization/domain/DB tests |
| [#133 M06 — 지도·Odii·관광 관측 R2 OpenAPI·fixture 동결](https://github.com/YRootLab/OnMaru-backend/issues/133) | P1/W1 | R2 public query와 연결 resource DTO를 기능 구현 전에 기계 판독 계약으로 고정한다. | map place, region resolve/count, Odii story, observation/heatmap paths and schemas, shared fixtures | OpenAPI validator and fixture schema tests |
| [#138 M07 — 방문자·관광지 집중률 공개 조회 API 구현](https://github.com/YRootLab/OnMaru-backend/issues/138) | P1/W5 | 관측 날짜·공간 단위·결측 상태가 명확한 지도 insights를 제공한다. | regional visitor observation query, tourism target concentration query, basisDate/spatialLevel/unit/status projection | MockMvc + PostgreSQL fixture contract tests |
| [#139 M08 — 보호된 moderation queue·operator drill·runbook 구현](https://github.com/YRootLab/OnMaru-backend/issues/139) | P1/W9 | 운영자가 공개 DB 직접 수정 없이 신고를 처리하고 숨김 데이터 재노출을 검증한다. | internal queue query, operator authorization, SLA/age projection, runbook, synthetic report/hide/restore/remove drill | authorization integration tests and recorded synthetic operator drill |
| [#140 M09 — R2 지도·후기·Odii·찜 통합 계약 E2E 게이트](https://github.com/YRootLab/OnMaru-backend/issues/140) | P1/W10 | R2 기능을 독립 unit이 아니라 사용자 흐름과 runtime 계약으로 출시 가능하게 검증한다. | region select, place/review read-write-like-report, Odii link/save, insights missing/stale, authorization, serializer drift | Spring+PostgreSQL provider-fixture E2E suite |
| [#61 A01 — Odii API 실제 응답·언어·음원 license qualification](https://github.com/YRootLab/OnMaru-backend/issues/61) | P0/W0 | spot/story/language/audio/script 계약과 재사용 범위를 실제 fixture로 고정한다. | live capture, pagination/error/quota, language mapping, audio/script rights | redacted capture manifest validation |
| [#96 A02 — Odii 수집·revision·tombstone publish 구현](https://github.com/YRootLab/OnMaru-backend/issues/96) | P1/W5 | spot/story를 같은 dataset revision으로 LKG 게시한다. | Odii client/parser, multilingual mapping, transcript provenance, sync/publish | mock provider + DB publication tests |
| [#103 A03 — Odii story·음원·대본 공개 API 구현](https://github.com/YRootLab/OnMaru-backend/issues/103) | P1/W6 | 언어·revision·자막 정확도를 보존한 audio 조회를 제공한다. | story list/detail, audio metadata URL policy, transcript segments/status | OpenAPI/API integration tests |
| [#104 A04 — Odii–canonical place 검수 연결과 projection 구현](https://github.com/YRootLab/OnMaru-backend/issues/104) | P1/W6 | Odii에서 발견한 실제 장소를 canonical placeId로 연결한다. | link candidate, review status, approved projection, linked place hydration | matching fixture and projection tests |

## Track F — FastAPI AI 서버

| Issue | P/W | 구현 | 범위 | 완료 증거 |
|---|---:|---|---|---|
| [#74 AI01 — Spring↔FastAPI 내부 계약과 서비스 인증 구현](https://github.com/YRootLab/OnMaru-backend/issues/74) | P0/W2 | 외부에 노출되지 않는 typed AI 요청 경계를 만든다. | internal OpenAPI/schema, token audience/scope, rotation overlap, requestId/trace propagation | two-service auth contract tests |
| [#83 AI02 — FastAPI intake normalization·privacy·safety guardrail 구현](https://github.com/YRootLab/OnMaru-backend/issues/83) | P2/W3 | 모델 호출 전 입력을 결정론적으로 허용·거절한다. | typed intake, unsupported scope, privacy redact required, safety block | pure pytest fixture matrix |
| [#97 AI03 — 검증 데이터 deterministic baseline 검색·ranking 구현](https://github.com/YRootLab/OnMaru-backend/issues/97) | P2/W5 | LLM/RAG 없이 후보를 재현 가능하게 선택한다. | region resolution input, candidate allowlist, rank30→12→3, ranking version | held-out retrieval fixtures |
| [#91 AI04 — Gemini provider adapter·timeout·usage 계측 구현](https://github.com/YRootLab/OnMaru-backend/issues/91) | P2/W4 | 모델을 untrusted typed proposal source로 격리한다. | prompt package, structured output, timeout/cancel, usage/cost, error mapping | fake provider + bounded live smoke |
| [#105 AI05 — AI proposal schema·evidence allowlist validator 구현](https://github.com/YRootLab/OnMaru-backend/issues/105) | P2/W6 | 모델 출력을 canonical 후보와 근거 범위 안에서만 수용한다. | ordered refs, rationale/evidence validation, duplicate/unknown ref, insufficient evidence | property and adversarial tests |
| [#111 AI06 — AI 품질·안전·비용·latency 평가 harness 구축](https://github.com/YRootLab/OnMaru-backend/issues/111) | P2/W7 | baseline과 모델 제안의 출시 기준을 재현 가능하게 측정한다. | gold set, retrieval recall, nDCG, evidence faithfulness, safety, p95, cost report | offline eval command and report schema |
| [#106 AI07 — Revision-pinned corpus export·manifest sync 구현](https://github.com/YRootLab/OnMaru-backend/issues/106) | P3/W6 | Spring canonical 문서를 hash manifest로 FastAPI에 전달한다. | Spring export, manifest/doc hash, tombstone, FastAPI pull/ACK/idempotency | two-service corpus fixtures |
| [#112 AI08 — FastAPI private corpus embedding·retrieval 구현](https://github.com/YRootLab/OnMaru-backend/issues/112) | P3/W7 | 비활성 revision에서 chunk/embedding을 완성하고 allowlist 검색한다. | chunking, embeddings, private store, promotion, candidate/revision filter | corpus integration tests |
| [#119 AI09 — Optional RAG 비교 평가·activation gate 구현](https://github.com/YRootLab/OnMaru-backend/issues/119) | P3/W8 | baseline보다 유의미할 때만 RAG를 활성화한다. | A/B offline eval, quality/cost/latency thresholds, feature flag, rollback | eval report + feature flag E2E |

## Track G — 여정 실행

| Issue | P/W | 구현 | 범위 | 완료 증거 |
|---|---:|---|---|---|
| [#101 J01 — Exploration 생성·조회·turn intake와 소유권 구현](https://github.com/YRootLab/OnMaru-backend/issues/101) | P2/W6 | guest/member가 공개 후보 탐색을 시작하고 이어간다. | POST/GET exploration, turn command, ownership, input persistence policy | API ownership/intake tests |
| [#109 J02 — Durable run 상태 머신·command idempotency 구현](https://github.com/YRootLab/OnMaru-backend/issues/109) | P2/W7 | QUEUED→RUNNING→단일 terminal과 재전송을 보장한다. | run create/claim/CAS, stage, terminal, idempotency, active run rule | transaction concurrency tests |
| [#114 J03 — Spring journey worker·FastAPI baseline orchestration 구현](https://github.com/YRootLab/OnMaru-backend/issues/114) | P2/W8 | candidate retrieval과 FastAPI proposal을 deadline 안에 조율한다. | worker claim, candidate payload, internal call, baseline fallback, result persist | two-service fake/real contract tests |
| [#115 J04 — Exploration·run snapshot DTO와 복구 조회 구현](https://github.com/YRootLab/OnMaru-backend/issues/115) | P2/W8 | 새로고침·재접속 시 DB 상태로 화면을 복구한다. | exploration snapshot, run snapshot, public hydration, unavailable refs | OpenAPI serializer tests |
| [#116 J05 — SSE stage·terminal·heartbeat·replay/reset 구현](https://github.com/YRootLab/OnMaru-backend/issues/116) | P2/W8 | 진행 알림을 제공하고 공백 시 snapshot 복구를 지시한다. | event IDs, replay buffer, heartbeat15s, Last-Event-ID, reset, auth close | parsed-frame schema and reconnect tests |
| [#120 J06 — PIN·EXCLUDE·proposal action과 stateVersion 구현](https://github.com/YRootLab/OnMaru-backend/issues/120) | P2/W9 | 사용자 선택을 optimistic concurrency로 원자 변경한다. | PIN/UNPIN/EXCLUDE/UNEXCLUDE/APPLY/DISMISS, version, invalidation, leg recalc | domain/property/DB tests |
| [#121 J07 — Run cancel·20초 deadline·sweeper 구현](https://github.com/YRootLab/OnMaru-backend/issues/121) | P2/W9 | 중단·만료 run을 terminal로 수렴시키고 자원을 회수한다. | cancel endpoint, cooperative cancel, deadline, lease expiry, sweeper | fake clock concurrency tests |
| [#124 J08 — Saved journey 생성·목록·상세·재개·삭제 구현](https://github.com/YRootLab/OnMaru-backend/issues/124) | P2/W10 | 확정 board snapshot을 회원이 장기 저장하고 복사 재개한다. | save, list cursor, read-only detail, resume copy, unavailableRefs, delete | API ownership and snapshot tests |
| [#117 J09 — Guest·member AI 일일 quota와 admission 구현](https://github.com/YRootLab/OnMaru-backend/issues/117) | P2/W8 | KST 일일 한도와 동시 run admission을 원자 적용한다. | quota counter, KST boundary, retryAfter, concurrent admission, usage audit | fake clock concurrency tests |
| [#127 J10 — Journey REST·SSE·FastAPI 전체 계약 E2E 게이트](https://github.com/YRootLab/OnMaru-backend/issues/127) | P2/W11 | 두 runtime에서 초기 탐색·수정·복구·저장 흐름을 증명한다. | clarification, board, proposal, reconnect/reset, cancel, quota/fallback, save/resume | Spring+FastAPI+Postgres E2E |
| [#134 J11 — Journey actions·SavedJourney OpenAPI·fixture 완성](https://github.com/YRootLab/OnMaru-backend/issues/134) | P2/W2 | 여정의 모든 public command·snapshot·saved journey 계약을 구현 전에 기계 판독 가능하게 완성한다. | actions, proposal/version errors, saved journey create/list/detail/resume/delete, shared snapshot schema and fixtures | OpenAPI validator, JSON Schema and scenario fixture tests |

## Track H — 운영·출시

| Issue | P/W | 구현 | 범위 | 완료 증거 |
|---|---:|---|---|---|
| [#71 O01 — Spring·FastAPI 구조화 로그와 OpenTelemetry 계측 구현](https://github.com/YRootLab/OnMaru-backend/issues/71) | P2/W1 | request/run/revision을 두 runtime에서 상관 분석한다. | requestId/traceId/runId/revision, metrics/traces/logs, redaction, low-cardinality labels | OTLP test collector assertions |
| [#81 O02 — Grafana Cloud dashboard·alert·resolve 통지 구성](https://github.com/YRootLab/OnMaru-backend/issues/81) | P2/W2 | API·sync·AI·SSE·moderation·backup 상태를 한 곳에서 운영한다. | dashboards, warning/critical rules, notification route, resolve message | synthetic alerts and dashboard checklist |
| [#72 O03 — Server-only secret loading·rotation·redaction 정책 구현](https://github.com/YRootLab/OnMaru-backend/issues/72) | P0/W1 | TourAPI/Odii/Gemini/OAuth/OTLP secret을 browser와 repository에서 격리한다. | config binding, secret provider interface, startup validation, rotation overlap, log redaction | configuration negative tests and runbook drill |
| [#94 O04 — PostgreSQL backup·PITR·restore drill 자동화](https://github.com/YRootLab/OnMaru-backend/issues/94) | P2/W5 | RPO 24h/RTO 4h 초안을 실제 복원 증거로 검증한다. | backup policy, encrypted storage, restore order, integrity query, deletion ledger replay | recorded restore drill |
| [#128 O05 — API·DB·SSE 부하·성능 예산 검증](https://github.com/YRootLab/OnMaru-backend/issues/128) | P2/W12 | 문서 p95와 timeout 목표를 실제 부하에서 측정한다. | hanok/review/region/journey/SSE scenarios, EXPLAIN baselines, connection budgets | repeatable load scripts and EXPLAIN |
| [#129 O06 — TourAPI·Odii·FastAPI·SSE 장애 복구 리허설](https://github.com/YRootLab/OnMaru-backend/issues/129) | P2/W12 | 외부·내부 dependency 장애에서 약속한 degraded 동작을 증명한다. | source down/429/schema drift, AI down/timeout, restart/replay gap, late result | fault-injection E2E drill |
| [#82 O07 — Spring·FastAPI container·staging·release/rollback pipeline 구현](https://github.com/YRootLab/OnMaru-backend/issues/82) | P2/W2 | 두 runtime과 migration을 CI gate 뒤 재현 가능하게 배포한다. | multi-stage images, SBOM/scan, deploy order, migration gate, smoke, rollback, concurrency | CI staging deployment rehearsal |
| [#125 O08 — TTL·revision GC·회원 탈퇴 cleanup·복원 삭제 ledger 구현](https://github.com/YRootLab/OnMaru-backend/issues/125) | P2/W10 | 보존기간 경과와 탈퇴 후 개인·stale 데이터를 재노출하지 않는다. | cleanup jobs, batch limits, tombstone/revision GC safety, deletion ledger replay | fake clock DB integration tests |
| [#130 O09 — Backend 전체 staging release gate와 운영 인수 완료](https://github.com/YRootLab/OnMaru-backend/issues/130) | P2/W13 | 필수 Track의 증거를 모아 출시 가능 여부를 판정한다. | R1/R2/R3 smoke, security, contract, migration, performance, chaos, restore, alerts, rollback evidence | staging release checklist automation |

## 세 개 상위 Agent 세션의 권장 소유권

도메인과 파일 경계를 기준으로 아래처럼 나누면 담당량이 25·26·29개로 비교적 균형적이다. 상위 Agent는 자신의 Track 안에서 독립 작업을 subagent/worktree로 다시 나눌 수 있지만, GitHub blocked-by가 열린 Leaf는 시작하지 않는다.

| 상위 Agent | 소유 Track | Leaf 수 | 책임 |
|---|---|---:|---|
| GPT | Track A+B | 25 | Spring/FastAPI 기반, 계약 CI, DB, 관광공사 수집 |
| Claude | Track C+D+E | 26 | 한옥·장소, 인증·저장, 지도·후기·Odii |
| Agy | Track F+G+H | 29 | FastAPI AI, 여정 실행, 관측성·배포·운영 gate |

### GPT 세션 실행 순서

- Wave 0: [F01 #63](https://github.com/YRootLab/OnMaru-backend/issues/63), [F03 #64](https://github.com/YRootLab/OnMaru-backend/issues/64), [F04 #65](https://github.com/YRootLab/OnMaru-backend/issues/65), [F07 #131](https://github.com/YRootLab/OnMaru-backend/issues/131), [P01 #66](https://github.com/YRootLab/OnMaru-backend/issues/66)
- Wave 1: [F02 #68](https://github.com/YRootLab/OnMaru-backend/issues/68), [F05 #69](https://github.com/YRootLab/OnMaru-backend/issues/69), [F06 #70](https://github.com/YRootLab/OnMaru-backend/issues/70), [F09 #132](https://github.com/YRootLab/OnMaru-backend/issues/132), [D01 #67](https://github.com/YRootLab/OnMaru-backend/issues/67), [P02 #73](https://github.com/YRootLab/OnMaru-backend/issues/73)
- Wave 2: [D02 #75](https://github.com/YRootLab/OnMaru-backend/issues/75), [D03 #76](https://github.com/YRootLab/OnMaru-backend/issues/76), [D04 #77](https://github.com/YRootLab/OnMaru-backend/issues/77), [D05 #78](https://github.com/YRootLab/OnMaru-backend/issues/78), [D06 #79](https://github.com/YRootLab/OnMaru-backend/issues/79), [D07 #80](https://github.com/YRootLab/OnMaru-backend/issues/80)
- Wave 3: [F08 #136](https://github.com/YRootLab/OnMaru-backend/issues/136), [D09 #135](https://github.com/YRootLab/OnMaru-backend/issues/135), [P03 #88](https://github.com/YRootLab/OnMaru-backend/issues/88), [P04 #89](https://github.com/YRootLab/OnMaru-backend/issues/89), [P07 #137](https://github.com/YRootLab/OnMaru-backend/issues/137)
- Wave 4: [D08 #85](https://github.com/YRootLab/OnMaru-backend/issues/85), [P05 #95](https://github.com/YRootLab/OnMaru-backend/issues/95), [P06 #90](https://github.com/YRootLab/OnMaru-backend/issues/90)

### Claude 세션 실행 순서

- Wave 0: [C01 #62](https://github.com/YRootLab/OnMaru-backend/issues/62), [A01 #61](https://github.com/YRootLab/OnMaru-backend/issues/61)
- Wave 1: [M06 #133](https://github.com/YRootLab/OnMaru-backend/issues/133)
- Wave 3: [C04 #84](https://github.com/YRootLab/OnMaru-backend/issues/84), [I03 #87](https://github.com/YRootLab/OnMaru-backend/issues/87)
- Wave 4: [I01 #86](https://github.com/YRootLab/OnMaru-backend/issues/86)
- Wave 5: [C02 #98](https://github.com/YRootLab/OnMaru-backend/issues/98), [C03 #99](https://github.com/YRootLab/OnMaru-backend/issues/99), [C05 #100](https://github.com/YRootLab/OnMaru-backend/issues/100), [I02 #92](https://github.com/YRootLab/OnMaru-backend/issues/92), [I04 #93](https://github.com/YRootLab/OnMaru-backend/issues/93), [M01 #102](https://github.com/YRootLab/OnMaru-backend/issues/102), [M07 #138](https://github.com/YRootLab/OnMaru-backend/issues/138), [A02 #96](https://github.com/YRootLab/OnMaru-backend/issues/96)
- Wave 6: [I05 #108](https://github.com/YRootLab/OnMaru-backend/issues/108), [M02 #110](https://github.com/YRootLab/OnMaru-backend/issues/110), [A03 #103](https://github.com/YRootLab/OnMaru-backend/issues/103), [A04 #104](https://github.com/YRootLab/OnMaru-backend/issues/104)
- Wave 7: [C06 #107](https://github.com/YRootLab/OnMaru-backend/issues/107), [I06 #113](https://github.com/YRootLab/OnMaru-backend/issues/113), [M03 #118](https://github.com/YRootLab/OnMaru-backend/issues/118)
- Wave 8: [M04 #122](https://github.com/YRootLab/OnMaru-backend/issues/122), [M05 #123](https://github.com/YRootLab/OnMaru-backend/issues/123)
- Wave 9: [M08 #139](https://github.com/YRootLab/OnMaru-backend/issues/139)
- Wave 10: [M09 #140](https://github.com/YRootLab/OnMaru-backend/issues/140)
- Wave 11: [I07 #126](https://github.com/YRootLab/OnMaru-backend/issues/126)

### Agy 세션 실행 순서

- Wave 1: [O01 #71](https://github.com/YRootLab/OnMaru-backend/issues/71), [O03 #72](https://github.com/YRootLab/OnMaru-backend/issues/72)
- Wave 2: [AI01 #74](https://github.com/YRootLab/OnMaru-backend/issues/74), [J11 #134](https://github.com/YRootLab/OnMaru-backend/issues/134), [O02 #81](https://github.com/YRootLab/OnMaru-backend/issues/81), [O07 #82](https://github.com/YRootLab/OnMaru-backend/issues/82)
- Wave 3: [AI02 #83](https://github.com/YRootLab/OnMaru-backend/issues/83)
- Wave 4: [AI04 #91](https://github.com/YRootLab/OnMaru-backend/issues/91)
- Wave 5: [AI03 #97](https://github.com/YRootLab/OnMaru-backend/issues/97), [O04 #94](https://github.com/YRootLab/OnMaru-backend/issues/94)
- Wave 6: [AI05 #105](https://github.com/YRootLab/OnMaru-backend/issues/105), [AI07 #106](https://github.com/YRootLab/OnMaru-backend/issues/106), [J01 #101](https://github.com/YRootLab/OnMaru-backend/issues/101)
- Wave 7: [AI06 #111](https://github.com/YRootLab/OnMaru-backend/issues/111), [AI08 #112](https://github.com/YRootLab/OnMaru-backend/issues/112), [J02 #109](https://github.com/YRootLab/OnMaru-backend/issues/109)
- Wave 8: [AI09 #119](https://github.com/YRootLab/OnMaru-backend/issues/119), [J03 #114](https://github.com/YRootLab/OnMaru-backend/issues/114), [J04 #115](https://github.com/YRootLab/OnMaru-backend/issues/115), [J05 #116](https://github.com/YRootLab/OnMaru-backend/issues/116), [J09 #117](https://github.com/YRootLab/OnMaru-backend/issues/117)
- Wave 9: [J06 #120](https://github.com/YRootLab/OnMaru-backend/issues/120), [J07 #121](https://github.com/YRootLab/OnMaru-backend/issues/121)
- Wave 10: [J08 #124](https://github.com/YRootLab/OnMaru-backend/issues/124), [O08 #125](https://github.com/YRootLab/OnMaru-backend/issues/125)
- Wave 11: [J10 #127](https://github.com/YRootLab/OnMaru-backend/issues/127)
- Wave 12: [O05 #128](https://github.com/YRootLab/OnMaru-backend/issues/128), [O06 #129](https://github.com/YRootLab/OnMaru-backend/issues/129)
- Wave 13: [O09 #130](https://github.com/YRootLab/OnMaru-backend/issues/130)

## 상위 세션 내부의 권장 Lane

### GPT

- Spring Platform: `F01 → F02/F05/F06`
- Python·계약: `F03`, `F07 → F09`
- Database: `F04 → D01 → D02~D07 → D09 → D08`
- Provider: `P01 → P02 → P03/P04 → P05`, `P07 → P06`

### Claude

- 한옥·장소: `C01 → C04`, `C02/C03/C05 → C06`
- 인증·저장: `I03 → I01 → I02/I04 → I05/I06 → I07`
- 후기·지도: `M06 → M01/M07`, `M02 → M03 → M04/M05 → M08 → M09`
- Odii: `A01 → A02 → A03/A04`, 이후 `I06/M09`

### Agy

- FastAPI Baseline: `AI01 → AI02/AI04 → AI03 → AI05 → AI06`
- Optional RAG: `AI07 → AI08 → AI09`; 평가 gate 미달이면 비활성 상태로 종료한다.
- Journey: `J11 → J01 → J02 → J03/J04/J05/J09 → J06/J07 → J08 → J10`
- Operations: `O01 → O02`, `O03 → O04/O07`, 이후 `O08 → O05/O06 → O09`

## 최초 병렬 착수 묶음

현재 모든 Issue가 Open이므로 dependency가 없는 Wave 0만 즉시 착수할 수 있다.

- GPT: `F01(#63)`, `F03(#64)`, `F04(#65)`, `F07(#131)`, `P01(#66)`
- Claude: `C01(#62)`, `A01(#61)`
- Agy: 직접 구현 가능한 Wave 0 Leaf가 없다. 초기에는 작업을 선점하지 말고, `F01/F03` 종료 후 `O01/O03`, `F07/F09` 종료 후 `J11`을 시작한다.

## Agent가 다음 Issue를 선택하는 규칙

1. Issue가 Open이고 모든 native `blocked-by`가 Closed인지 확인한다.
2. assignee와 연결된 Open PR이 없는지 확인한다.
3. 같은 `Expected Touch Points`를 수정 중인 Agent가 없는지 확인한다.
4. 한 Issue마다 별도 `feature/<issue-number>-<slug>` branch와 worktree를 사용한다.
5. PR은 `develop`을 대상으로 하고 본문에 관련 Issue를 연결한다.
6. CI·review·merge와 Acceptance Criteria 확인 후에만 Issue를 닫는다.
7. 선행 Issue가 닫힐 때마다 전체 Wave가 아니라 각 Leaf의 blocked-by를 다시 확인한다.
8. 구현 중 새 의존성이나 touch point 충돌을 발견하면 먼저 Work Graph와 GitHub native 관계를 수정한다.

## Cross-Agent 인계 지점

- GPT의 `F07/F09` 완료 → Claude의 인증·저장 계약 구현과 Agy의 `J11` 착수 가능
- GPT의 `D02/D07/P05/P07` 완료 → Claude의 한옥·지도·Odii 수집·조회 구현 가능
- Claude의 `C03/A02` 완료 → Agy의 Journey snapshot·optional corpus 작업 가능
- Agy의 `O01/O02` 완료 → Claude의 moderation queue·drill 착수 가능
- Claude의 `C06/M09`와 Agy의 `J10` 완료 → 성능·장애·최종 `O09` gate 가능

## 관련 기준 문서

- [Canonical Work Graph](work-graph.json)
- [Issue Tree](issue-tree.json)
- [요구사항 추적표](requirements-map.md)
- [GitHub 발행 번호 Manifest](publication.json)
- [Backend 기획 진입점](../README.md)

