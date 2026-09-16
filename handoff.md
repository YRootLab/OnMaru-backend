# handoff.md

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
- 구현: 회원 전용 Odii PUT/DELETE desired state와 type별 300개 상한, `type` 필수 saved-resource 목록, `(savedAt DESC, resourceId DESC)` actor-bound signed cursor, Catalog/Audio current-public hydration을 추가했다.
- 보안/정책: auth·CSRF·private `no-store` 경계를 유지하고, 다른 actor cursor는 404로 숨긴다. hidden/deleted story는 저장 404 및 목록 제외, raw provider ID는 응답하지 않으며 ODII 저장은 PLACE 저장을 만들지 않는다.
- DB: V006가 이미 `ODII_STORY` enum, actor/type/resource 유니크 제약과 목록 인덱스를 제공하므로 migration과 schema 문서 변경은 필요하지 않았다.
- 테스트: `SavedOdiiResourceWebBoundaryTests`가 저장/삭제 멱등성, actor/type cursor binding, current hydration과 보안 경계를 검증한다. `./gradlew test --no-daemon --max-workers=1`, Node tests, planning/fixture 검증, contract pytest와 `bash scripts/verify-contracts`, branch parser, `git diff --check`가 통과했다.
- 다음 단계: 전체 검증, commit/push, `develop` 대상 PR과 CI/독립 review를 완료한다. approval 전에는 merge하지 않는다.

## Cleanup Note

- 2026-09-16에 오래된 다른 Issue의 `Current Session Quick Handoff` 블록을 제거했다. 완료되었거나 별도 worktree/PR의 과거 상태였고, 현재 #113 재시작에는 필요하지 않았다.
