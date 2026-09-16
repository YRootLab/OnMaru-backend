# handoff.md

## Current Session Quick Handoff - 2026-09-16 Issue #111

- 병합 후 final audit: `fix/111-eval-text-safety`에서 Unicode 접두어 뒤 ASCII URI scheme을 공용 text safety가 놓치지 않도록 보강하고, offline launcher가 Python 3.11 이하와 3.13 이상 모두 `uv` Python 3.12로 재실행하도록 shell regression test와 CI를 추가했다.
- final audit 재리뷰: URI·domain heuristic이 `Reason: ...`, version, 평점, 거리의 decimal token을 오탐하지 않도록 경계를 좁혔다. focused proposal/eval 78개, FastAPI 전체 140 passed·1 opt-in live smoke skipped, Ruff/mypy, launcher shell test, offline 5 gates·schema/byte 재현성을 통과했다.
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
