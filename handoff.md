# handoff.md

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
- 다음 단계: branch를 push하고 `develop` 대상 PR을 만든다. 필수 CI와 approval 전에는 merge하지 않는다.
## Current Session Quick Handoff - 2026-09-16 Issue #111

- 현재 작업 브랜치와 worktree: `feature/111-ai-eval-harness`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/issue-111-ai-eval-harness`.
- 관련 Issue: #111 `[AI06] AI 품질·안전·비용·latency 평가 harness 구축`; blocked-by #105는 Closed이고 담당자·열린 중복 PR은 없다.
- 구현 범위: model·prompt·ranking·dataset version을 고정한 synthetic held-out seed, recall@5·nDCG@3·human claim support·evidence ID precision·allowlist/pin/exclude/outcome safety·p95·평균/단건 비용 gate를 추가했다.
- 재현 계약: canonical input SHA-256과 case ID+immutable gold 전용 SHA-256, 분자·분모, 관측값·threshold, gate 결과를 closed JSON report `1.1`에 기록한다. dataset 표시명과 actual은 gold hash에서 제외하고 실행 시각도 기록하지 않아 같은 fixture는 byte-identical report를 만든다.
- 리뷰 보강: #105와 같은 board cardinality/shape를 입력 단계에서 강제하고 retrieval 중복은 입력 거절과 nDCG 방어를 모두 적용했다. reason별 생성 summary와 provider가 아닌 frozen `humanClaimSupported`를 저장해 의미 지지율을 ID 정합성과 분리했다. production과 공용 plain-text safety validator로 공백·URL·Markdown summary를 거절하며 camelCase alias-only wire 계약을 적용했다.
- report 불변식: `quality`, `safety`, `latency`, `cost`, `determinism` 다섯 gate를 정확히 한 번 요구하고 `overallPassed`를 gate 논리곱과 일치시킨다. JSON Schema도 gate 5개와 고유성을 제한한다.
- 실행·CI: `scripts/run-ai-evals`가 offline report를 생성하고 실패 gate가 있으면 종료 코드 1을 반환한다. CI는 5개 gate와 runtime JSON Schema drift를 검사한다.
- 범위 경계: 현재 4건은 harness 검증용 synthetic seed이며 60건 사람 이중 검수 gold set 또는 실제 모델 출시 승인을 의미하지 않는다. RAG 비교·activation은 #119가 소유한다.
- 검증: eval 21개와 production proposal validator 42개, FastAPI 전체 125 passed·1 opt-in live smoke skipped, Ruff/mypy, Node 37개, contract validator 9개, planning/Odii, offline 5 gates·schema/byte 재현성, Gradle 전체 41 tasks를 통과했다.
- 다음 단계: `develop` 대상 PR의 `verify` CI와 최소 1명 approval을 확인한다. Merge 후 #111 상태를 조회하고 `develop` 대상 auto-close가 적용되지 않으면 정책에 따라 검증 근거를 남긴 뒤 수동 close 여부를 조정한다.

## Current Session Quick Handoff - 2026-09-16 Issue #103

- 현재 작업 브랜치와 worktree: `fix/103-atomic-active-snapshot`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/fix-103-atomic-snapshot`.
- 관련 Issue: #103 `[A03] Odii story·음원·대본 공개 API 구현`; PR #204 병합 후 감사에서 revision ID와 snapshot의 비원자 read 경쟁 조건을 재현해 Issue를 다시 열었다.
- 보완 PR: #207 `fix(audio): active revision snapshot 원자성 보장`, base `develop`, head `fix/103-atomic-active-snapshot`.
- 원인: `ActiveRevisionOdiiStoryQueryStore`가 `activeRevision`과 `activeSnapshot`을 따로 읽어 두 호출 사이 publish가 완료되면 이전 revision ID와 새 snapshot을 조합할 수 있었다. 첫 페이지 cursor가 이전 revision에 묶이고 본문은 새 revision 기준이 되어 다음 페이지가 즉시 `CURSOR_EXPIRED`가 될 수 있었다.
- 수정: `AudioRevisionStore`가 revision ID와 defensive-copy snapshot을 단일 `ActiveAudioRevision` value로 반환하고, in-memory store가 하나의 synchronized read로 구성하며 공개 query adapter는 이 원자 API만 사용한다.
- 회귀 테스트: `ActiveRevisionOdiiStoryQueryStoreTests.keepsRevisionAndSnapshotFromTheSameAtomicPublicationRead`가 publish interleave를 결정적으로 모사한다.
- 검증: PR #207의 `verify` CI는 성공했다. 로컬 검증은 `./gradlew :modules:audio:test --tests '*ActiveRevisionOdiiStoryQueryStoreTests' --no-daemon --max-workers=1`, `./gradlew :modules:audio:test --no-daemon --max-workers=1`, `node scripts/print-branch-issue.mjs`, `git diff --check`를 통과했다.
- 다음 단계: 독립 review와 최소 1명 approval을 확인한 뒤 PR #207을 `develop`에 병합한다. 병합 후 #103 상태를 조회하고, `develop` 대상 auto-close가 적용되지 않으면 병합 PR과 검증 근거를 comment로 남긴 뒤 수동 close한다. #113은 #103이 다시 Closed일 때 시작한다.

## Cleanup Note

- 2026-09-16에 오래된 다른 Issue의 `Current Session Quick Handoff` 블록을 제거했다. 완료되었거나 별도 worktree/PR의 과거 상태였고, 현재 #103 fix 재시작에는 필요하지 않았다.
