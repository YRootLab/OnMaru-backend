# FE SSoT 잔여 범위 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #342, #343, #344, #346에서 실제 코드로 검증 가능한 SSoT 책임을 완료하고, 운영 권한 또는 미정 계약이 필요한 항목을 구현 이슈와 운영 작업으로 분리한다.

**Architecture:** 지도 게시글은 별도 warmth 리소스를 만들지 않고 `VisitReview`를 단일 모델로 사용한다. 스크린 한옥은 Catalog 후보와 FastAPI 리서치 결과를 원자적으로 게시하되, 프로세스 재시작 후에도 유지되는 저장소를 사용해야 한다. 소리마루의 Redis 캐시는 active revision을 cache key에 결합해 revision 변경 뒤 오래된 결과가 노출되지 않게 한다.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/Flyway, Spring Data Redis, JUnit 5/MockMvc, FastAPI 내부 AI 계약.

## Global Constraints

- `develop`에 이미 병합된 `/api/stories`, `/api/stories/nearby`, `/api/recommendation` 구현을 중복하지 않는다.
- 공용 조회 API는 기존 `Cache-Control: no-store` 정책을 유지한다. Redis는 서버 내부 원본 조회 부하 완화용이며 브라우저 cache directive가 아니다.
- `VisitReview`의 `mood`, `score`, `tags`는 각각 `북적|한적`, 1..5, 최대 5개·각 20 code points다. `visitorCount`는 데이터랩 장소·권역 매핑이 확정될 때까지 노출하지 않는다.
- 화면의 `visited`, 지도 게시글, 온기 후기는 모두 `VisitReview`다. `/visited`, `/warmths`, `/api/map/warmth`를 새로 만들지 않는다.
- Flyway baseline migration은 수정하지 않고 새 버전 migration만 추가한다.
- 외부 API 키, Redis URL, 운영 DB 자격은 코드나 문서에 기록하지 않는다.

---

## File Structure

- `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/screenhanok/ScreenHanokConfiguration.java`: 화면 한옥 query/ingestion store wiring을 JDBC 저장소로 전환한다.
- `adapters/persistence-jdbc/src/main/java/.../screenhanok/JdbcScreenHanokPlacementStore.java`: published placement snapshot의 PostgreSQL read/replace를 구현한다.
- `apps/spring-api/src/main/resources/db/migration/baseline/V019__...sql`: screen-hanok placement snapshot 테이블과 인덱스를 만든다.
- `apps/spring-api/src/test/java/.../screenhanok/*`: 재시작/필터/출처 보존과 JDBC migration을 검증한다.
- `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/audio/OdiiStoryConfiguration.java`: revision-bound Redis cache adapter를 wiring한다.
- `modules/audio/src/main/java/.../query/*`: cache key를 만들기 위한 revision-aware query port를 제공한다.
- `apps/spring-api/src/test/java/.../web/audio/*`: miss/hit, active revision 변경, Redis 장애 fallback을 테스트한다.
- `docs/contracts/rest-api.md`, `docs/toFE/fe-api-compatibility-handoff-2026-09-23.md`, `handoff.md`: canonical route·명칭 매핑·운영 전제와 검증 결과를 갱신한다.
- `modules/catalog/src/main/java/.../publicid/*`: FE에 노출하는 `p-*` 장소 ID와 Catalog 내부 UUID의 안정적인 일대일 매핑 port를 정의한다.
- `adapters/persistence-jdbc/src/main/java/.../catalog/JdbcCatalogPublicPlaceIdStore.java`: Catalog 소유 공개 ID 매핑을 PostgreSQL에 영속화한다.
- `apps/spring-api/src/main/resources/db/migration/baseline/V020__...sql`: 공개 장소 ID 매핑 테이블을 추가한다.

## Task 1: 현재 변경(#342/#344 VisitReview·호환 경로)을 독립적으로 검증하고 PR 단위로 고정

**Files:**
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review/command/VisitReviewCommandWebBoundaryTests.java`
- Modify: `docs/contracts/openapi/visit-reviews.openapi.json`
- Modify: `docs/toFE/fe-api-compatibility-handoff-2026-09-23.md`

**Interfaces:**
- Consumes: `POST /api/v1/places/{placeId}/visit-reviews` body `{text,mood?,score?,tags?}`.
- Produces: `VisitReview` item containing `mood`, `score`, `tags`; legacy map route aliases.

- [ ] **Step 1: Verify failing/green contract boundary tests**

Run: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.web.review.command.VisitReviewCommandWebBoundaryTests' --tests 'com.yrootlab.onmaru.web.map.place.MapPlaceWebBoundaryTests' --tests 'com.yrootlab.onmaru.web.insights.InsightsWebBoundaryTests' --no-daemon --max-workers=1`

Expected: all tests green; the VisitReview test asserts create and subsequent list preserve `mood:"한적"`, `score:5`, and `tags:["고즈넉함","처마"]`.

- [ ] **Step 2: Run contract validation**

Run: `bash scripts/verify-contracts`

Expected: OpenAPI and fixture validators succeed.

- [ ] **Step 3: Commit the completed API-contract slice**

Run: `git add apps/spring-api modules/catalog modules/community docs/contracts docs/database docs/toFE handoff.md && git commit -m 'feat(map): FE 지도 계약과 방문 후기 온기 필드 확장'`

## Task 2: 스크린 한옥 게시 snapshot을 PostgreSQL에 영속화

**Files:**
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/screenhanok/JdbcScreenHanokPlacementStore.java`
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V019__343_screen_hanok_placements.sql`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/screenhanok/ScreenHanokConfiguration.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/persistence/screenhanok/JdbcScreenHanokPlacementStoreTests.java`

**Interfaces:**
- Consumes: `ScreenHanokPlacementStore.publish(List<ScreenHanokPlacement>)` and `findPublishedSnapshot()`.
- Produces: a restart-safe snapshot for `ScreenHanokQueryService.list(region, mediaType, memberId)`.

- [ ] **Step 1: Write a failing JDBC-store test**

Create a Testcontainers PostgreSQL test that publishes two placements, constructs a new store against the same `DataSource`, and asserts the second store returns both rows with `sourceUrl`, `sourceTitle`, media type, tags, and `publishedAt` intact. Publish a replacement list and assert the old rows are absent.

- [ ] **Step 2: Run the store test to verify it fails**

Run: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.persistence.screenhanok.JdbcScreenHanokPlacementStoreTests' --no-daemon --max-workers=1`

Expected: FAIL because the JDBC store and V019 migration do not exist.

- [ ] **Step 3: Add V019 and the JDBC implementation**

Create `onmaru.catalog_screen_hanok_placements` with `place_id`, `media_type`, `work_title`, `subtitle`, `tags jsonb`, `source_url`, `source_title`, and `published_at`; use a transaction that deletes the current snapshot then inserts the complete replacement. Reject blank source URLs and use `jsonb_typeof(tags) = 'array'`.

- [ ] **Step 4: Wire the JDBC store only when a DataSource is available**

Keep `InMemoryScreenHanokPlacementStore` as the local/test fallback; use a conditional JDBC bean for production so the existing no-DB tests retain deterministic behavior.

- [ ] **Step 5: Run tests and migration verification**

Run: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.testing.postgres.JdbcScreenHanokPlacementStoreTests' :modules:catalog:test --tests 'com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokQueryServiceTests' --no-daemon --max-workers=1`

Expected: PASS; region/media type filters and source preservation remain green.

- [ ] **Step 6: Commit the persistence slice**

Run: `git add adapters/persistence-jdbc apps/spring-api/src/main docs/database/schema.md && git commit -m 'feat(screen-hanok): 게시 snapshot 영속화'`

## Task 3: Catalog 공개 장소 ID와 VisitReview 작성 시점 장소 snapshot을 영속화

**Decision:** ADR-0014를 따른다. VisitReview는 Catalog 내부 UUID FK를 유지하고, FE가 쓰는 공개 장소 ID와 표시용 장소 정보는 작성 시점 snapshot으로 보존한다. 읽기 때 active Catalog revision을 다시 조인하지 않는다.

**Files:**
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/publicid/CatalogPublicPlaceIdStore.java`
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/catalog/JdbcCatalogPublicPlaceIdStore.java`
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V020__346_catalog_public_place_ids.sql`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcCatalogPublicPlaceIdStoreTests.java`

**Interfaces:**
- Consumes: Catalog write path에서 생성한 `publicPlaceId`와 `catalog_place_identity.id`.
- Produces: public ID에서 내부 UUID로의 안정적·일대일 lookup. 동일 pair의 재등록은 idempotent이고, 다른 pair와의 충돌은 거절한다.

- [ ] **Step 1: Write a failing mapping-store test**

Create a Testcontainers PostgreSQL test that seeds two `catalog_place_identity` rows, registers `p-jeonju-hanok-village` to the first UUID, recreates the store, and verifies the lookup survives. Assert same-pair retry succeeds while public-ID or place-UUID collisions fail.

- [ ] **Step 2: Add V020 and JDBC mapping implementation**

Create `onmaru.catalog_place_public_ids` with `public_id` primary key and a unique `place_id` FK. Keep public ID assignment in the Catalog boundary; Community only resolves it and never creates mappings.

- [ ] **Step 3: Verify migration and retry/collision behavior**

Run: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.testing.postgres.JdbcCatalogPublicPlaceIdStoreTests' --no-daemon --max-workers=1`

Expected: PASS; mapping survives a new adapter instance, repeat is idempotent, and both uniqueness directions reject remapping.

- [ ] **Step 4: Add VisitReview snapshot persistence after mapping is available**

Replace production-only `InMemoryVisitReviewStore` wiring with a JDBC store that writes the resolved Catalog UUID plus immutable `public_place_id`, `place_name`, `region_code`, `latitude`, and `longitude` in the same review insert. Keep existing local/test in-memory wiring until all command, like, report, and moderation paths have JDBC coverage.

**Progress (2026-09-24):** V020/V021, JDBC mapping/store/active-eligible Catalog lookup과 production DataSource wiring을 구현했다. 신규 후기·좋아요·상태 변경은 JDBC store를 사용한다. 신고·moderation audit·idempotency persistence는 별도 후속으로 남아 있다.

## Task 4: #360 소리마루 Redis cache 구현

**Files:**
- Modify: `apps/spring-api/build.gradle.kts` or the repository dependency convention that owns Spring Data Redis.
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/audio/RevisionBoundOdiiStoryCache.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/audio/OdiiStoryConfiguration.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/audio/OdiiStoryController.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/audio/OdiiStoryCacheWebBoundaryTests.java`

**Interfaces:**
- Consumes: story query inputs (`keyword`, `language`, `limit`; `lat`, `lng`, `radius`; recommendation inputs) and the active revision ID.
- Produces: server-side cache values with a 24-hour TTL and keys prefixed `odii:v1:{activeRevision}:`.

- [ ] **Step 1: Write a failing cache-hit/cache-miss test**

Use a fake cache port and query-store invocation counter. Call `GET /api/stories?keyword=한옥&language=ko&limit=10` twice and assert two equivalent bodies with one underlying query. Replace the active revision and assert a third call invokes the query again.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.web.audio.OdiiStoryCacheWebBoundaryTests' --no-daemon --max-workers=1`

Expected: FAIL because no cache port is consulted.

- [ ] **Step 3: Implement a revision-bound cache port and Redis adapter**

Serialize the complete response body as JSON. Build keys from a canonical NFC/trimmed query representation plus the active revision; set TTL to 24 hours. Never cache an error response or a missing active revision. When Redis throws a connectivity exception, execute the uncached query and record a cache-fallback metric.

- [ ] **Step 4: Add Redis configuration and observability**

Bind Redis endpoint/password only from server configuration. Add Micrometer counters `onmaru.odii.cache.hit`, `.miss`, `.fallback`, tagged by endpoint. Do not place credentials in tests or fixtures.

- [ ] **Step 5: Verify cache contracts**

Run: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.web.audio.OdiiStoryCacheWebBoundaryTests' --no-daemon --max-workers=1`

Expected: PASS for hit/miss, revision invalidation, TTL configuration, and Redis-failure fallback.

- [ ] **Step 6: Commit the cache slice**

Run: `git add apps/spring-api modules/audio adapters/persistence-jdbc docs && git commit -m 'feat(audio): revision-bound Redis cache 추가'`

## Task 5: 운영 smoke와 미정 범위를 이슈 상태로 정직하게 정리

**Files:**
- Modify: `handoff.md`
- Modify: `docs/toFE/fe-api-compatibility-handoff-2026-09-23.md`

**Interfaces:**
- Consumes: deployed Spring API, deployed FastAPI screen-hanok research service, production Gemini secret, production Redis endpoint.
- Produces: dated smoke result or an issue comment naming the unavailable external prerequisite.

- [ ] **Step 1: Run screen-hanok smoke after the production secret is configured**

Run: `curl --fail --silent --show-error 'https://onmaru-backend.onrender.com/api/v1/hanoks/screen-hanok?mediaType=K_DRAMA'`

Expected: a 200 response with at least one source-backed item after the scheduled/manual ingestion job completes.

- [ ] **Step 2: Run Redis smoke after the production endpoint is configured**

Call each story endpoint twice with the same request and inspect the `onmaru.odii.cache.hit` and `.miss` metrics. Confirm a revision publish causes the next equivalent request to miss.

- [ ] **Step 3: Reconcile umbrella issues**

Close #343 only after Task 2 and the Gemini production smoke pass. Close #344 only after Task 1 and its documented VisitReview substitution are accepted; do not add Redis as an implicit requirement because its concrete completion is #360. Keep #342 and #346 open until their unspecified Admin and product-decision sections are replaced with independently actionable issues.

## Scope gaps that cannot be implemented from the four issue bodies

- #346 identifies Admin only as “state unknown” and defines no endpoint, role model, request/response schema, or acceptance condition. No safe Admin API can be invented from that text.
- #346 says to “decide” whether Gemini stays in FE or moves to BE; the repository already has a server-side AI integration, but changing the public Journey contract requires the FE’s request payload and error UX contract.
- #343’s final Gemini production smoke and #360’s Redis production smoke require deployment secrets and hosted service access; source changes alone cannot manufacture those operational resources.

## Self-Review

- Spec coverage: Task 1 covers completed #342/#344 map contracts; Task 2 covers #343’s missing restart-safe backend path; Task 3 covers #345/#360’s concrete caching acceptance; Task 4 separates deploy-only work and undefined #346 areas.
- Placeholder scan: no implementation task delegates validation or error behavior without a concrete condition, key shape, test, or command.
- Type consistency: Task 2 uses the existing `ScreenHanokPlacementStore`; Task 3 preserves the existing Odii controllers and adds an internal cache boundary rather than a new public route.
