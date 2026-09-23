# 소리마루 Single Source of Truth API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redis 없이 기존 Odii active revision을 원천으로 검색·근처 조회·키워드 추천 API를 제공한다.

**Architecture:** `OdiiStoryQueryService`가 공통 공개 데이터 필터와 언어 선택을 재사용하고, 세 신규 HTTP 경로는 동일한 `OdiiStoryPage` 응답을 반환한다. 기존 v1 Odii API는 유지한다.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, MockMvc

## Global Constraints

- Redis 의존성, 설정, 캐시 코드는 추가하지 않는다.
- 외부 Whale.Be API는 호출하지 않는다.
- 공개 가능한 active Odii story만 반환한다.
- 기존 `/api/v1/odii/stories` 계약은 변경하지 않는다.

### Task 1: 검색·근처·추천 서비스 계약

**Files:**
- Modify: `modules/audio/src/test/java/com/yrootlab/onmaru/audio/query/OdiiStoryQueryServiceTests.java`
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/query/OdiiStoryQueryService.java`

- [ ] 검색이 title/audioTitle/contentTags를 대상으로 동작하는 실패 테스트 작성
- [ ] `./gradlew :modules:audio:test --tests '*OdiiStoryQueryServiceTests'`로 실패 확인
- [ ] 반경 검증·거리순 근처 조회 실패 테스트 작성
- [ ] 추천의 keyword match score 및 게시일 tie-breaker 실패 테스트 작성
- [ ] 최소 구현으로 신규 query method와 공통 응답 생성을 추가
- [ ] 같은 테스트 명령으로 통과 확인

### Task 2: HTTP endpoint와 오류 계약

**Files:**
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/audio/OdiiStoryWebBoundaryTests.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/audio/OdiiStoryController.java`

- [ ] 세 신규 경로의 200 응답 실패 테스트 작성
- [ ] keyword/좌표/radius 오류와 unavailable snapshot의 400/503 실패 테스트 작성
- [ ] 컨트롤러에 파라미터 파싱과 신규 서비스 호출 추가
- [ ] `./gradlew :apps:spring-api:test --tests '*OdiiStoryWebBoundaryTests'` 통과 확인

### Task 3: 계약 문서와 전체 검증

**Files:**
- Modify: `docs/toFE/home-api-spec.md` 또는 신규 `docs/toFE/sorimaru-api.md`
- Modify: `docs/contracts/openapi/r2-map-audio-insights.openapi.yaml`

- [ ] 신규 endpoint 요청 파라미터·응답·오류를 문서화
- [ ] `./gradlew test --no-daemon --max-workers=1` 실행
- [ ] `git diff --check` 실행
- [ ] Redis 변경이 없는지 의존성/소스 검색으로 확인
