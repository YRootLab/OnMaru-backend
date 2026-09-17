# handoff.md

## Current Session Quick Handoff - 2026-09-17 Issue #126

- 현재 작업 브랜치와 worktree: `feature/126-timeline-read-model`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/i07-read-model`.
- 관련 Issue: #126 `[I07] 내 월간 활동 타임라인 read model 구현`; blocked-by #108/#113/#124/#118/#132는 모두 Closed임을 확인했다.
- 구현 범위:
  - `modules/journey/src/main/java/com/yrootlab/onmaru/journey/timeline`: 도메인 프로젝션 서비스 `MemberTimelineService`, 이벤트 타입 `TimelineItemType`(4종: `SAVED_PLACE`, `SAVED_ODII_STORY`, `SAVED_JOURNEY`, `WROTE_VISIT_REVIEW`), `TimelineTarget`, 일자별 그룹 `TimelineDayGroup`, 커서 `TimelineCursor` 및 포트(`TimelinePlaceLookup`, `TimelineOdiiStoryLookup`, `TimelineVisitReviewSource`).
  - `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/me/timeline`: `GET /api/v1/me/timeline` 웹 컨트롤러, 어댑터(`PlaceDetailTimelineAdapter`, `OdiiStoryTimelineAdapter`, `VisitReviewTimelineAdapter`), 10분 TTL 서명 커서 코덱 `MemberTimelineCursorCodec` 및 스프링 빈 설정 `MemberTimelineConfiguration`.
- 보안/정책: 세션 쿠키(`__Host-onmaru-session`) 기반 인증 필수(미인증 시 `401 AUTH_REQUIRED`), 타 회원 커서 은닉(`404 NOT_FOUND`), `Cache-Control: no-store` 적용, 비공개/삭제 리소스는 목록에서 제외하고 `unavailableCount`로만 집계하여 stale 데이터 유출 방지.
- 관측성: SLF4J Key-Value 구조화 로깅(`member.id`, `month`, `limit`, `timeline.group_count`, `timeline.unavailable_count`, `timeline.has_more`).
- 테스트 및 검증:
  - 도메인 단위 테스트: `MemberTimelineServiceTests` (KST 월 경계, 4종 이벤트 정렬, 비공개 리소스 집계, 타인 데이터 격리, 커서 페이지네이션).
  - 웹 경계 통합 테스트: `MemberTimelineWebBoundaryTests` (401 인증, 정상 200, 400 validation, 410 만료 커서, 404 타인 커서, 400 변조 커서, no-store 헤더).
  - 아키텍처/ArchUnit: `ModuleBoundaryArchUnitTests` (순환 의존성 없음, 모듈 경계 준수).
  - 계약 검증: `validate-identity-saved-contract.py`, `validate-r1-contract.py`, `test_contract_validation.py` 통과.
  - Node 스크립트 및 기획 입력 검증: `node --test scripts/test/*.test.mjs`, `node scripts/verify-planning-inputs.mjs`, `node scripts/validate-odii-fixtures.mjs` 통과.
  - 전체 프로젝트 검증: Gradle 전체 53 tasks 통과 (`./gradlew test --no-daemon`), `node scripts/print-branch-issue.mjs` 출력 `126`, `git diff --check` 통과.
- 다음 단계: PR #231 merge 후 Issue #126 상태 확인 및 필요 시 수동 close.
