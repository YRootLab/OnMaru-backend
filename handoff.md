# handoff.md

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
