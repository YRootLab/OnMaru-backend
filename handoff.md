# handoff.md

## Current Session Quick Handoff - 2026-09-16 Issue #106

- 현재 작업 브랜치와 worktree: `feature/106-corpus-manifest-sync`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/issue-106-corpus-manifest-sync`.
- 관련 Issue: #106 `[AI07] Revision-pinned corpus export·manifest sync 구현`; blocked-by #74/#95/#96은 모두 Closed였다.
- 구현 범위: Spring internal corpus export scaffold와 FastAPI corpus pull/stage/ACK service를 추가했다. Spring은 immutable revision publish, canonical JSON 기반 manifest/document hash, tombstone manifest entry, revision-pinned document fetch, ACK endpoint와 manifest hash 검증을 제공한다. FastAPI는 duplicate pull insert 0, partial fetch reject, hash mismatch reject, out-of-order manifest rollback 방지, complete activation tombstone 적용을 처리한다.
- 계약 문서: `docs/contracts/openapi/internal-ai.yaml`에 corpus manifest/document/ACK private contract를 추가했다.
- 작업 로그: `troubleshooting-worklog/26.09.16 corpus-manifest-sync.md`.
- 독립 리뷰 보완: ACK endpoint 누락, Spring controller 응답 shape와 OpenAPI 불일치, Spring/FastAPI hash canonicalization 불일치, store-null service split 문제를 수정했다. Spring/Python 양쪽에 같은 document/manifest hash literal 회귀 테스트를 추가했다.
- 검증: focused Spring corpus tests, Spring app 전체 테스트, Gradle 전체 `test` 41 tasks, FastAPI 전체 pytest 185 passed/1 skipped, corpus Ruff/mypy, contract validation, `scripts/verify-contracts`, `git diff --check`, branch parser `106`을 통과했다. 첫 `./gradlew test`는 Gradle result binary `NoSuchFileException`으로 실패했으나 `:apps:spring-api:cleanTest :apps:spring-api:test` 성공 후 전체 `./gradlew test`를 재실행해 성공했다. contract 검증 중 Python 3.9/LibreSSL `urllib3 NotOpenSSLWarning`은 출력됐지만 실패는 아니다.
- 다음 단계: PR 생성 전 work log cleanup과 Issue #106 AC를 다시 대조한다.

## Current Session Quick Handoff - 2026-09-16 Issue #109

- 현재 작업 브랜치와 worktree: `feature/109-durable-run`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/issue-109-durable-run`.
- 관련 Issue: #109 `[J02] Durable run 상태 머신·command idempotency 구현`; blocked-by #101은 Closed이고 시작 시 열린 중복 PR은 없었다.
- 구현 범위: `modules/journey/run`에 create/claim/stage/terminal 상태 머신과 persistence port를 추가하고, `adapters/persistence-jdbc`에서 PostgreSQL CAS와 durable command receipt를 한 transaction으로 구현했다. Exploration scaffold도 `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED`와 active run 경계를 표현한다.
- active run rule: 같은 exploration의 latest run이 `QUEUED` 또는 `RUNNING`이면 새 turn을 `ACTIVE_RUN` 409로 막는다. stale/unknown clarification answer는 기존 J01 계약대로 `VERSION_CONFLICT`를 유지한다.
- idempotency: actor/operation/key와 request hash별 immutable receipt를 저장한다. 같은 payload는 원래 result를 replay하고 다른 payload는 conflict로 rollback한다.
- migration: V009가 `discovery_run_commands`와 canonical status/outcome/stage 제약을 추가한다. V006의 FAILED/CANCELLED legacy outcome은 `error_code`로 무손실 이관한다.
- 동시성 검증: 실제 PostgreSQL에서 동일 command race 효과 1회, exploration/actor active run 하나, cancel/complete terminal 하나, stale generation/stage conflict와 새 adapter instance snapshot 복구를 확인했다.
- 현재 검증: focused Journey/JDBC/web/migration/Testcontainers 통과. 전체 Java 검증 중 `apps:spring-api:test` result binary `NoSuchFileException`이 한 번 발생했으나 XML assertion failure는 없었고, `./gradlew :apps:spring-api:cleanTest :apps:spring-api:test --no-daemon` 재실행으로 Spring API 전체가 통과했다. Node 37 tests, planning/Odii, contract/generated artifact, Python contract 9 passed, AI pytest 180 passed/1 skipped, Ruff/mypy, offline AI eval 5 gates, branch parser `109`, `git diff --check` 통과.
- PR: #211 `feat(journey): durable run 상태 머신과 command idempotency 구현` (`develop` 대상, `Closes #109`). CI와 approval 전에는 merge하지 않는다.



## Current Session Quick Handoff - 2026-09-16 Issue #197

- 현재 작업 브랜치와 worktree: `feature/197-odii-production`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/issue-197-odii-production`.
- 관련 Issue: #197 `[A02-FOLLOWUP] Odii production revision 저장·공개 projection wiring 구현`.
- 구현 범위: PostgreSQL `AudioRevisionStore` adapter, production Spring composition, Odii sync source composition, active revision 기반 공개 projection, category/region/subtitle hydrate, place-link DB 승인 원자성, 관측성 이벤트를 추가했다.
- DB 변경: `V010__a02_odii_production_persistence.sql`이 `audio_revision_stages`, version row의 `source_modified_at/observed/missing_observations`, `transcript_provenance`, place-link `review_status`, spot별 approved partial unique index를 추가한다. `db/migration/registry/migrations.json`과 DBML/schema 문서도 갱신했다.
- runtime 구성: production profile은 JDBC `AudioRevisionStore`/`AudioPlaceLinkStore`를 선택하고, non-production에서만 in-memory fallback을 둔다. `OdiiClientConfiguration`은 production에서 Odii HTTP source와 `OdiiRevisionSyncService`를 구성한다.
- 공개 projection: `ActiveRevisionOdiiStoryQueryStore`가 active revision snapshot을 읽어 subtitle line을 transcript로 매핑하고, `OdiiProjectionMetadataResolver`로 category/region을 hydrate한다. region 미해결은 대한민국 fallback을 사용한다.
- 장소 연결: `AudioPlaceLinkStore.approveExclusive(...)`로 승인 전환을 store-level 단일 operation으로 올렸고, JDBC adapter는 row lock + transaction + partial unique index로 동시 승인 단일 승자와 rollback 보존을 검증한다.
- 관측성: `odii.sync.started/failed/completed`, `odii.place_link.approval.completed/failed`, `odii.active_revision.snapshot/unavailable` 이벤트를 기존 `TelemetrySink`로 기록한다.
- 작업 로그: `troubleshooting-worklog/26.09.16 odii-production-persistence-observability.md`.
- 검증: `./gradlew :modules:audio:test --no-daemon --max-workers=1`, `./gradlew :apps:spring-api:compileTestJava --no-daemon --max-workers=1`, `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.tourism.audio.JdbcAudioRevisionStoreIntegrationTests' --tests 'com.yrootlab.onmaru.testing.postgres.AudioMigrationTests.permitsOnlyOneApprovedPlaceLinkPerAudioSpot' --no-daemon --max-workers=1`, `git diff --check`, `node scripts/print-branch-issue.mjs`를 통과했다. 사용자가 Spring 전체 테스트 1회 수행 완료를 확인했다.
- PR #214 생성 후 GitHub `verify`가 실패했다. 원인은 `V010` 반영 후 migration contract 기대값 미갱신, 새 `transcript_provenance` NOT NULL 컬럼을 legacy fixture insert가 채우지 않음, `web -> observability` 직접 참조로 인한 ArchUnit cycle, production-profile 테스트의 전역 system property 오염이었다.
- CI 보정: query 관측성은 `audio` observer port + `observability` adapter로 이동했고, fixture/contract/property override를 수정했다. 재검증으로 실패 4개 클래스 묶음, `./gradlew :apps:spring-api:test --no-daemon --max-workers=1`, `./gradlew :modules:audio:test --no-daemon --max-workers=1`, `git diff --check`를 통과했다.
- 추가 CI 보정: 두 번째 `verify`는 Spring 단계까지 통과했으나 `Public contract and generated artifact validation`에서 stale Azimutt SQL artifact diff로 실패했다. `node scripts/azimutt-export.mjs`로 `docs/database/azimutt/*`를 재생성했고 `bash scripts/verify-contracts`가 통과했다.
- CI 주의: 로컬 Spring 전체 테스트 성공은 GitHub required `verify` check를 대체하지 않는다. push 후 GitHub CI 녹색과 최소 1명 approval을 확인해야 merge 가능하다.
- 다음 단계: PR #214의 재실행된 `verify` check를 확인한다. PR merge 후 `Closes/Fixes/Resolves #197`가 `develop` 대상 auto-close로 처리되지 않을 수 있으므로 Issue 상태를 확인하고 필요 시 검증 근거 comment 후 수동 close한다.

## Current Session Quick Handoff - 2026-09-16 Issue #101

- 현재 작업 브랜치와 worktree: `feature/101-exploration-intake`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/issue-101-exploration-intake`.
- 관련 Issue: #101 `[J01] Exploration 생성·조회·turn intake와 소유권 구현`; blocked-by #70/#77/#87/#92/#134/#136은 모두 Closed이고 시작 시 담당자·열린 중복 PR은 없었다.
- 구현 범위: guest/member actor-scoped Exploration 생성·조회·turn intake, valid raw turn persistence, clientTurnId replay, stateVersion 검증, OpenAPI 1.2 `RunAccepted`/snapshot 응답을 추가했다.
- 보안 경계: `/auth/csrf`가 server-registered 24시간 opaque guest credential을 발급하고 raw token은 cookie에만 둔다. credential 없음/미등록은 401, 타 actor 접근은 404, member guest grant를 지원하며 private response는 no-store다.
- intake 경계: privacy/safety/scope 422와 structural 400 validation은 exploration·원문 turn·dispatch를 만들지 않는다. create/turn은 shared idempotency store로 replay/conflict를 구분하고 baseVersion 누락을 거절한다.
- AI 경계: 지역이 없으면 dispatcher를 호출하지 않고 terminal `CLARIFICATION_REQUIRED/REGION_MISSING`을 저장한다. 지역이 있으면 #109가 이어받을 `ExplorationRunDispatcher`로 queued run을 전달한다.
- 작업 로그: `troubleshooting-worklog/26.09.16 j01-exploration-intake.md`.
- 독립 리뷰: raw input policy 누락, Idempotency-Key 미적용, 미등록 guest cookie 신뢰, baseVersion 기본값 승인, 비계약 오류 code를 보완했다. null replay와 canonical clarification answer도 함께 교정했다.
- 최종 리뷰 보완: clarification 답변은 최신 `COMPLETED/CLARIFICATION_REQUIRED` run의 실제 ID와 일치할 때만 승인하고 stale/unknown/non-pending 답변은 409로 거절한다. control character와 create/turn/nested unknown field는 저장·dispatch 전에 422로 거절하며, CSRF 403에도 `Cache-Control: no-store`를 적용했다.
- 검증: 최신 `origin/develop` 병합과 최종 리뷰 보완 뒤 격리 Gradle home 전체 Java 41 tasks, focused domain/web/CSRF/ArchUnit 24 tasks, FastAPI Ruff·mypy와 pytest 104 passed/1 skipped, Node 37 tests, planning/Odii fixture, 전체 contract/generated artifact, branch parser `101`, `git diff --check`를 통과했다. 첫 전체 Java 실행의 result-file `NoSuchFileException` 뒤 `cleanTest` Spring 전체와 전체 41 tasks를 순차 재실행해 성공을 확인했다.
- 최신 통합: PR #205 merge commit `56f6d54`를 union merge해 #101/#111 work log를 모두 보존했다. develop 변경은 AI/CI/문서에 한정되며, #101 focused Java 24 tasks와 최신 AI 125 passed/1 skipped, offline eval 5 gates, Node/contract/planning/Odii 검증을 재통과했다.
- 최신 통합: PR #207 merge commit `3ddb690`의 audio atomic active snapshot과 handoff cleanup을 union merge했다. #101/#111/#103 현재 기록은 보존하고 중복 #103 changelog 항목은 원자 snapshot 설명으로 통합했다.
- 다음 단계: branch를 push하고 `develop` 대상 PR을 만든다. 필수 CI와 approval 전에는 merge하지 않는다.
## Current Session Quick Handoff - 2026-09-16 Issue #111

- 현재 작업 브랜치와 worktree: `feature/111-ai-eval-harness`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/issue-111-ai-eval-harness`.
## Current Session Quick Handoff - 2026-09-16 Issue #111

- 병합 후 final audit: `fix/111-eval-text-safety`에서 Unicode 접두어 뒤 ASCII URI scheme을 공용 text safety가 놓치지 않도록 보강하고, offline launcher가 Python 3.11 이하와 3.13 이상 모두 `uv` Python 3.12로 재실행하도록 shell regression test와 CI를 추가했다.
- final audit 재리뷰: 모든 ASCII scheme token을 scan해 기본 거절하고 exact title-case prose label 5개만 단일 행 plain payload로 허용한다. URI 구조·nested scheme·lowercase scheme과 `splitlines()`가 인식하는 Unicode line boundary는 거절한다. focused proposal/eval 118개, FastAPI 전체 180 passed·1 opt-in live smoke skipped, Ruff/mypy, launcher shell test, offline 5 gates·schema/byte 재현성을 통과했다.
- 현재 작업 브랜치와 worktree: `fix/111-eval-text-safety`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/fix-111-eval-text-safety`.
- 관련 Issue: #111 `[AI06] AI 품질·안전·비용·latency 평가 harness 구축`; blocked-by #105는 Closed이고 담당자·열린 중복 PR은 없다.
- 구현 범위: model·prompt·ranking·dataset version을 고정한 synthetic held-out seed, recall@5·nDCG@3·human claim support·evidence ID precision·allowlist/pin/exclude/outcome safety·p95·평균/단건 비용 gate를 추가했다.
- 재현 계약: canonical input SHA-256과 case ID+immutable gold 전용 SHA-256, 분자·분모, 관측값·threshold, gate 결과를 closed JSON report `1.1`에 기록한다. dataset 표시명과 actual은 gold hash에서 제외하고 실행 시각도 기록하지 않아 같은 fixture는 byte-identical report를 만든다.
- 리뷰 보강: #105와 같은 board cardinality/shape를 입력 단계에서 강제하고 retrieval 중복은 입력 거절과 nDCG 방어를 모두 적용했다. reason별 생성 summary와 provider가 아닌 frozen `humanClaimSupported`를 저장해 의미 지지율을 ID 정합성과 분리했다. production과 공용 plain-text safety validator로 공백·URL·Markdown summary를 거절하며 camelCase alias-only wire 계약을 적용했다.
- report 불변식: `quality`, `safety`, `latency`, `cost`, `determinism` 다섯 gate를 정확히 한 번 요구하고 `overallPassed`를 gate 논리곱과 일치시킨다. JSON Schema도 gate 5개와 고유성을 제한한다.
- 실행·CI: `scripts/run-ai-evals`가 offline report를 생성하고 실패 gate가 있으면 종료 코드 1을 반환한다. CI는 5개 gate와 runtime JSON Schema drift를 검사한다.
- 범위 경계: 현재 4건은 harness 검증용 synthetic seed이며 60건 사람 이중 검수 gold set 또는 실제 모델 출시 승인을 의미하지 않는다. RAG 비교·activation은 #119가 소유한다.
- 검증: eval 21개와 production proposal validator 42개, FastAPI 전체 125 passed·1 opt-in live smoke skipped, Ruff/mypy, Node 37개, contract validator 9개, planning/Odii, offline 5 gates·schema/byte 재현성, Gradle 전체 41 tasks를 통과했다.
- 다음 단계: `develop` 대상 PR의 `verify` CI와 최소 1명 approval을 확인한다. Merge 후 #111 상태를 조회하고 `develop` 대상 auto-close가 적용되지 않으면 정책에 따라 검증 근거를 남긴 뒤 수동 close 여부를 조정한다.

## Current Session Quick Handoff - 2026-09-16 Issue #113

- 현재 작업 브랜치와 worktree: `feature/113-odii-saved-resource`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/issue-113-odii-saved-resource`.
- 관련 Issue: #113 `[I06] Odii story 저장과 saved-resource 목록 구현`; 모든 dependency #77/#103/#86/#87/#132는 Closed이며 작업 시작 시 열린 중복 PR이 없었다.
- 구현: 회원 전용 Odii PUT/DELETE desired state와 type별 300개 상한, `type` 필수 saved-resource 목록, 외부 비노출 UUID row ID 기반 `(savedAt DESC, id DESC)` actor-bound signed cursor, Catalog/Audio current-public hydration을 추가했다. 중복 PUT은 최초 row ID와 `savedAt`을 유지한다.
- 보안/정책: auth·CSRF·private `no-store` 경계를 유지하고, 다른 actor cursor는 404로 숨긴다. hidden/deleted story는 저장 404 및 목록 제외, raw provider ID와 내부 row ID는 응답하지 않으며 ODII 저장은 PLACE 저장을 만들지 않는다. Audio/Place current source 부재는 PUT/목록에서 공통 503으로 fail-closed한다.
- DB: V006가 이미 `ODII_STORY` enum, actor/type/resource 유니크 제약과 목록 인덱스를 제공하므로 migration과 schema 문서 변경은 필요하지 않았다.
- 테스트: `SavedOdiiResourceWebBoundaryTests`가 저장/삭제 멱등성, actor/type cursor binding, current hydration, source unavailable 503과 보안 경계를 검증한다. 최신 develop 병합 후 `./gradlew :apps:spring-api:test --no-daemon --stacktrace`, `./gradlew :modules:journey:test --no-daemon`, contract pytest, `bash scripts/verify-contracts`, branch parser, `git diff --check`가 통과했다.
- 다음 단계: 변경 push 후 `develop` 대상 PR #210의 CI와 최소 1명 approval을 확인한다. approval 전에는 merge하지 않는다.

## Cleanup Note

- 2026-09-16에 오래된 다른 Issue의 `Current Session Quick Handoff` 블록을 제거했다. 완료되었거나 별도 worktree/PR의 과거 상태였고, 현재 #113 재시작에는 필요하지 않았다.
