# 한옥 수결첩 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인 회원의 위치 기반 한옥 체크인을 검증하고 관계형 PostgreSQL 수결첩에 즉시·멱등하게 수결을 지급하는 API를 구현한다.

**Architecture:** 새 `modules:stamp`가 framework 독립 정책과 port를 소유하고, `adapters:persistence-jdbc`가 active Catalog/PostGIS 및 ACID 저장을 구현한다. `apps:spring-api`는 session, CSRF, idempotency와 HTTP mapping만 담당하며 기존 찜 도메인과 분리한다.

**Tech Stack:** Java 21, Spring Boot 3, JDBC, PostgreSQL 17/PostGIS 3.5, Flyway, JUnit 5, AssertJ, MockMvc, Testcontainers, OpenAPI 3.1

## Global Constraints

- 원본 latitude/longitude는 DB, log, metric label, HTTP response에 저장하거나 노출하지 않는다.
- accuracy는 `0 < accuracyMeters <= 100`, 성공은 `distanceMeters - accuracyMeters <= 200`이다.
- `HANOK`, `HANOK_STAY`, `HANOK_CAFE`, `HANOK_EXPERIENCE`만 체크인 가능하다.
- 체크인, award, idempotency receipt는 공유 `JdbcTransactionRunner`의 한 transaction으로 commit한다.
- POST와 개인 GET은 로그인 필수이며 private response는 `Cache-Control: no-store`다.
- 구현 test는 반드시 RED 확인 후 GREEN으로 진행한다.

---

## File Map

- `modules/stamp/**`: 수결 definition, 위치 검증 결과, award policy, service, store port, in-memory test adapter
- `adapters/persistence-jdbc/**/stamp`: active Catalog/PostGIS lookup과 PostgreSQL stamp store
- `apps/spring-api/**/web/stamp`: REST controller, DTO, exception mapping, Spring wiring
- `apps/spring-api/src/main/resources/db/migration/baseline/V027__262_hanok_stamp_book.sql`: enum/table/index/seed
- `docs/contracts/openapi/hanok-stamps.openapi.yaml`: canonical API contract
- `docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md`: FE 연동 안내

### Task 1: 수결 schema와 migration contract

**Files:**
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V027__262_hanok_stamp_book.sql`
- Modify: `db/migration/registry/migrations.json`
- Modify: `docs/database/schema.md`
- Test: `scripts/test/migration-policy.test.mjs`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcStampMigrationTests.java`

**Interfaces:**
- Produces: `onmaru.stamp_definitions`, `stamp_region_rules`, `stamp_check_ins`, `stamp_awards`
- Enforces: unique `(member_id, place_id, check_in_bucket)` and `(member_id, stamp_code)`

- [ ] Add a PostgreSQL integration test that migrates from zero, asserts 12 definitions/10 region rules, rejects duplicate check-in/award, and cascades member deletion.
- [ ] Run `./gradlew :apps:spring-api:test --tests '*JdbcStampMigrationTests'` and confirm failure because V027 tables do not exist.
- [ ] Add enum/table/index/seed DDL, reservation row, registry SHA-256, and schema documentation.
- [ ] Run the focused test and `node --test scripts/test/migration-policy.test.mjs`; confirm both pass.
- [ ] Commit `feat(stamp): 수결첩 관계형 스키마 추가`.

### Task 2: framework 독립 수결 domain

**Files:**
- Create: `modules/stamp/build.gradle.kts`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/*.java`
- Create: `modules/stamp/src/test/java/com/yrootlab/onmaru/stamp/StampServiceTests.java`
- Modify: `settings.gradle.kts`

**Interfaces:**
- `CheckInPlaceLookup.verify(String placeId, double latitude, double longitude): Optional<VerifiedPlace>`
- `StampStore.record(UUID memberId, VerifiedPlace place, Instant now, int accuracyMeters): StampCheckInResult`
- `StampStore.book(UUID memberId): StampBook`
- `StampService.checkIn(UUID memberId, CheckInCommand command): StampCheckInResult`
- `StampService.book(UUID memberId): StampBook`

- [ ] Write tests proving invalid finite/range/accuracy input rejection, outside-radius rejection, exact boundary acceptance, duplicate bucket behavior, regional award, KST night award, and legendary award at five distinct groups.
- [ ] Run `./gradlew :modules:stamp:test`; confirm compile/failing behavior because domain types are absent.
- [ ] Add immutable records/enums/exceptions, `StampAwardPolicy`, `StampService`, and deterministic `InMemoryStampStore` with the signatures above.
- [ ] Run `./gradlew :modules:stamp:test`; confirm all tests pass.
- [ ] Commit `feat(stamp): 위치 체크인과 수결 지급 도메인 구현`.

### Task 3: PostGIS 장소 검증과 JDBC transaction store

**Files:**
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/stamp/JdbcCheckInPlaceLookup.java`
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/stamp/JdbcStampStore.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcStampStoreTests.java`
- Modify: `adapters/persistence-jdbc/build.gradle.kts`

**Interfaces:**
- Consumes: Task 2 ports and shared `JdbcTransactionRunner`
- Produces: active revision query using `ST_Distance(version.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)` and transactional record/book implementation

- [ ] Write Testcontainers tests for active eligible place, hidden/wrong category/missing coordinate rejection, near/far distance, same-bucket convergence, concurrent award uniqueness, daily KST limit, and rollback.
- [ ] Run `./gradlew :apps:spring-api:test --tests '*JdbcStampStoreTests'`; confirm failure because adapters are absent.
- [ ] Implement parameterized SELECTs with explicit columns, member advisory transaction lock, conflict-safe inserts, bulk stamp-book join, and no coordinate persistence.
- [ ] Run focused PostgreSQL tests and confirm pass.
- [ ] Commit `feat(stamp): PostGIS 체크인 영속 파이프라인 구현`.

### Task 4: REST API와 인증·멱등 경계

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampController.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampConfiguration.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampExceptionHandler.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/stamp/StampWebBoundaryTests.java`
- Modify: `apps/spring-api/build.gradle.kts`

**Interfaces:**
- `GET /api/v1/stamps`
- `GET /api/v1/me/stamp-book`
- `POST /api/v1/places/{placeId}/check-ins`
- Request: `{latitude: double, longitude: double, accuracyMeters: double}` plus UUID `Idempotency-Key`

- [ ] Write MockMvc tests for public catalog, private book, 201 new check-in, 200 same bucket, 401, validation 400, missing/invalid key, 404, both 422 codes, 429, 503, replay/conflict, no-store, and coordinate non-disclosure.
- [ ] Run `./gradlew :apps:spring-api:test --tests '*StampWebBoundaryTests'`; confirm missing endpoint failures.
- [ ] Implement DTO validation, member resolution, fingerprinted `IdempotencyService` execution, response mapping, error mapping, and profile-specific in-memory/JDBC beans.
- [ ] Run focused web tests and confirm pass.
- [ ] Commit `feat(api): 위치 기반 수결첩 API 추가`.

### Task 5: OpenAPI와 repository contract

**Files:**
- Create: `docs/contracts/openapi/hanok-stamps.openapi.yaml`
- Modify: `docs/contracts/rest-api.md`
- Modify: `scripts/validate_contracts.py`
- Test: `scripts/test/test_contract_validation.py`

**Interfaces:**
- Documents the three Task 4 endpoints, schemas, cookie auth, `Idempotency-Key`, and exact error codes.

- [ ] Add a contract validation test that fails when the stamp OpenAPI file is absent or omits required operations/error schemas.
- [ ] Run `python -m pytest scripts/test/test_contract_validation.py`; confirm RED.
- [ ] Add OpenAPI 3.1 contract and validator registration, then update REST summary/privacy rules.
- [ ] Run `./scripts/verify-contracts`; confirm GREEN.
- [ ] Commit `docs(api): 수결첩 OpenAPI 계약 추가`.

### Task 6: FE 인계와 운영 문서

**Files:**
- Create: `docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md`
- Modify: `handoff.md`

**Interfaces:**
- Explains browser geolocation, authenticated fetch with credentials, UUID key reuse on retry, response rendering, error UX, and localStorage removal.

- [ ] Write a non-developer overview and copy-paste `navigator.geolocation`/`fetch` example without real secrets.
- [ ] Add a status/error handling table and explicit migration from `onmaru_hanok_stamps_v1` to server truth.
- [ ] Record branch, Issue #262, touched paths, verification, open risk, and FE next step in `handoff.md`.
- [ ] Run `git diff --check` and repository documentation checks.
- [ ] Commit `docs(fe): 수결첩 API 연동 안내 추가`.

### Task 7: 전체 회귀 검증과 완료 점검

**Files:**
- Modify only files required by failures attributable to Tasks 1-6.

**Interfaces:**
- Produces a clean branch ready for review; does not push, open, or merge a PR without a separate request.

- [ ] Run `node scripts/print-branch-issue.mjs` and confirm `262`.
- [ ] Run `npm test` and `./scripts/verify-contracts`.
- [ ] Run `./gradlew test` and the Python baseline test command defined by CI.
- [ ] Run `git diff --check`, `git status --short`, and inspect all commits/files against Issue #262.
- [ ] Apply `verification-before-completion` and report exact commands/results plus remaining production/staging risks.
