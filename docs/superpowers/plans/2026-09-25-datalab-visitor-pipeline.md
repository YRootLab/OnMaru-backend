# DataLab Visitor Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DataLab 지역 방문자 관측을 PostgreSQL에 자동 저장하고 VisitReview의 nullable `visitorCount`로 제공한다.

**Architecture:** production은 JDBC observation store와 scheduled DataLab ingestion만 사용한다. 후기 페이지의 distinct `regionCode`를 한 번에 조회해 최신 `COMPLETE` 관측만 매핑하며, 누락값은 0이 아닌 null이다.

**Tech Stack:** Java 21, Spring Boot scheduling, PostgreSQL/Flyway, JDBC, JUnit 5/Testcontainers.

## Global Constraints

- production에서 in-memory observation 또는 VisitReview fallback을 사용하지 않는다.
- `visitorCount`는 장소별 실시간 인원이 아닌 후기 장소 지역의 최신 DataLab 관측이다.
- 유효하지 않은 payload와 수집 실패는 이전 정상 관측값을 삭제하지 않는다.
- Redis는 이 범위에서 구현하지 않는다.

---

### Task 1: JDBC 지역 방문자 관측 store

**Files:**
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/insights/JdbcVisitorObservationStore.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcVisitorObservationStoreTests.java`

**Interfaces:**
- Produces: `save(VisitorObservation)` and `findLatestCompleteByRegionCodes(Set<String>)`.

- [ ] **Step 1: Write a failing Testcontainers test**

Persist two dated observations for `kr-45-jeonju`, then assert a new store instance returns only the later `COMPLETE` value; assert `NOT_AVAILABLE` returns no count.

- [ ] **Step 2: Implement JDBC upsert/query**

Resolve `regionCode` to `catalog_regions.id`; upsert `insights_visitor_observations`; query one latest complete row per requested region with `row_number()`.

- [ ] **Step 3: Verify**

Run: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.testing.postgres.JdbcVisitorObservationStoreTests' --no-daemon --max-workers=1`

### Task 2: VisitReview nullable visitor count mapping

**Files:**
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/query/VisitReview.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/query/VisitReviewQueryService.java`
- Test: `modules/community/src/test/java/com/yrootlab/onmaru/community/query/VisitReviewQueryServiceTests.java`

- [ ] **Step 1: Write a failing query test**

Assert a review in `kr-45-jeonju` returns `18240L`; assert an unknown region returns `null`.

- [ ] **Step 2: Add a region-count lookup port and map it once per page**

Expose `Map<String, Long> findLatestComplete(Set<String> regionCodes)` and use it after pagination, never once per review.

- [ ] **Step 3: Verify module tests**

Run: `./gradlew :modules:community:test --tests 'com.yrootlab.onmaru.community.query.VisitReviewQueryServiceTests' --no-daemon --max-workers=1`

### Task 3: Production scheduling and strict JDBC wiring

**Files:**
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights/InsightsConfiguration.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/scheduling/insights/DataLabVisitorSchedulingAdapter.java`
- Modify: `apps/spring-api/src/main/resources/application.yaml`

- [ ] **Step 1: Add scheduler test with a fake client**

Assert a valid daily payload is persisted; a failed fetch leaves the last successful value unchanged.

- [ ] **Step 2: Wire production JDBC only**

Use cron `0 30 3 * * *`, zone `Asia/Seoul`; production missing DataSource must fail bean construction rather than install fixtures.

- [ ] **Step 3: Verify focused tests and contracts**

Run: `./gradlew :apps:spring-api:test :modules:community:test --no-daemon --max-workers=1 && bash scripts/verify-contracts`
