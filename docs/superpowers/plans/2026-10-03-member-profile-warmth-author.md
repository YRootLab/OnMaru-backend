# 회원 익명 프로필과 온기 후기 작성자 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회원에게 수정 가능한 익명 이름·온니 캐릭터·배경을 배정하고, 마이페이지와 온기 후기에서 최신 프로필을 일관되게 제공한다.

**Architecture:** Identity 모듈이 프로필 생성·검증·영속화의 Source of Truth가 되고 신규 OAuth 회원 생성 transaction에 프로필을 포함한다. Community 모듈은 Identity 구현을 참조하지 않고 `VisitReviewAuthorProfileLookup` port로 페이지 작성자 프로필을 batch 조회하며, FE는 고정 ID에 대응하는 이미지와 HEX만 관리한다.

**Tech Stack:** Java 21, Spring Boot 3, PostgreSQL 17, Flyway, JDBC, JUnit 5, AssertJ, MockMvc, Testcontainers, OpenAPI 3.1

**Spec:** `docs/superpowers/specs/2026-10-03-member-profile-warmth-author-design.md`

## Global Constraints

- 카카오 OAuth에서는 provider·issuer·subject만 사용하고 닉네임·프로필 이미지를 수집하지 않는다.
- 캐릭터 ID는 `CHARACTER_01`~`CHARACTER_10`, 배경 ID는 `BACKGROUND_01`~`BACKGROUND_10`만 허용한다.
- 실제 캐릭터 자산과 배경 HEX는 FE가 소유하며 BE 응답에 URL·HEX를 포함하지 않는다.
- 표시 이름은 trim·NFC 정규화된 2~20 code points이며 개행·제어문자를 허용하지 않는다.
- 프로필 변경은 후기 snapshot을 수정하지 않고 조회 시 최신 작성자 프로필로 반영한다.
- 공개 응답에 내부 member UUID, Kakao subject, 이메일을 작성자 정보로 노출하지 않는다.
- cookie 기반 `PATCH`에는 기존 CSRF 정책을 적용한다.
- GitHub Issue #552와 브랜치 `feature/552-member-profile`을 사용하고 문서·로그는 한국어로 작성한다.

## Review Focus

- 동시에 같은 카카오 계정으로 최초 로그인해도 member·external account·profile이 각각 하나만 생성되는지 Task 2 race test로 고정한다.
- 부분 수정에서 누락 필드는 보존되고 명시적 `null`, 빈 body, 잘못된 ID는 `400 VALIDATION_ERROR`가 되는지 Task 3 web test로 고정한다.
- 한 페이지에 같은 작성자의 후기가 여러 개 있어도 profile lookup을 한 번만 호출하고 작성자 ID를 deduplicate하는지 Task 4 unit test로 고정한다.
- 프로필이 없는 legacy/deleted 작성자가 피드 전체를 깨뜨리지 않고 중립 fallback 프로필로 표시되는지 Task 4 unit/web test로 고정한다.
- FE가 알 수 없는 미래 ID를 받더라도 안전한 기본 자산으로 fallback할 수 있도록 enum 범위와 OpenAPI 정규식이 동일한지 Task 5 contract test로 고정한다.

---

### Task 1: V039 회원 프로필 schema와 기존 회원 backfill

**Files:**
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V039__552_member_profiles.sql`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/IdentityMigrationTests.java`
- Modify: `docs/database/schema.md`

**Interfaces:**
- Consumes: `onmaru.identity_members(id, status, created_at)` from V003.
- Produces: `onmaru.identity_member_profiles(member_id, display_name, character_id, background_id, created_at, updated_at)` with one profile per existing member.

- [ ] **Step 1: Write the failing V038→V039 migration test**

Add `memberProfilesBackfillExistingMembersAndEnforceCatalog()` to `IdentityMigrationTests`: migrate through V038, insert a fixed ACTIVE member, migrate through V039, then assert exactly one profile exists; `display_name` is 2~20 characters; character/background match `CHARACTER_(0[1-9]|10)` and `BACKGROUND_(0[1-9]|10)`; invalid IDs, blank name, and `updated_at < created_at` fail; deleting the member cascades the profile.

- [ ] **Step 2: Run the migration test to verify RED**

Run: `./gradlew :apps:spring-api:test --tests '*IdentityMigrationTests.memberProfilesBackfillExistingMembersAndEnforceCatalog' --no-daemon`

Expected: FAIL because V039/table `identity_member_profiles` does not exist.

- [ ] **Step 3: Implement V039**

Create the table and CHECK constraints from the spec, backfill every existing member using stable UUID hash buckets for the exact 10 adjective values, 10 noun values, 4-digit suffix, 10 character IDs, and 10 background IDs. Register version `039`, `reserved_for='MEMBER_PROFILES'`, Issue `552`; add checksum/comment metadata. Do not add image URL or HEX columns.

- [ ] **Step 4: Document the executable schema**

Append the V039 ownership, ID catalog, backfill behavior, latest-profile join behavior, and FK cascade to `docs/database/schema.md`.

- [ ] **Step 5: Run migration and policy tests to verify GREEN**

Run: `./gradlew :apps:spring-api:test --tests '*IdentityMigrationTests' --tests '*FlywayMigrationBaselineTests' --no-daemon && node --test scripts/test/migration-policy.test.mjs`

Expected: Gradle `BUILD SUCCESSFUL`; migration policy test passes with V039 reserved for #552.

- [ ] **Step 6: Commit**

```bash
git add apps/spring-api/src/main/resources/db/migration/baseline/V039__552_member_profiles.sql apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/IdentityMigrationTests.java docs/database/schema.md
git commit -m "feat(profile): 회원 익명 프로필 스키마 추가"
```

### Task 2: 프로필 도메인과 OAuth 원자 생성

**Files:**
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/MemberProfileCharacter.java`
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/MemberProfileBackground.java`
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/MemberProfile.java`
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/NewMemberProfile.java`
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/MemberProfileGenerator.java`
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/MemberProfilePatch.java`
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/MemberProfileStore.java`
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/MemberProfileService.java`
- Create: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/profile/MemberProfileInvalidException.java`
- Create: `modules/identity/src/test/java/com/yrootlab/onmaru/identity/profile/MemberProfileServiceTests.java`
- Modify: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/oauth/IdentityStore.java`
- Modify: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/oauth/OAuthLoginService.java`
- Modify: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/oauth/InMemoryIdentityStore.java`
- Modify: `modules/identity/src/main/java/com/yrootlab/onmaru/identity/lifecycle/MemberSummary.java`
- Modify: `modules/identity/src/test/java/com/yrootlab/onmaru/identity/oauth/OAuthLoginServiceTests.java`
- Modify: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/identity/JdbcIdentityStore.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcIdentityStampFlowTests.java`

**Interfaces:**
- Consumes: Task 1 `identity_member_profiles` table.
- Produces: `MemberProfileService.updateActiveProfile(UUID, MemberProfilePatch, Instant)`, `MemberProfileService.findByMemberIds(Set<UUID>)`, and `IdentityStore.linkExternalIdentity(ExternalIdentity, NewMemberProfile, Instant)`.

- [ ] **Step 1: Write failing generator and validation tests**

In `MemberProfileServiceTests`, pin an injectable deterministic random source and assert generated names use the exact adjective/noun lists plus zero-padded 4 digits, IDs remain in the two 10-value enums, duplicate display names are allowed, omitted patch fields are preserved, trim/NFC is applied, and null/blank/1-char/21-code-point/newline/control-character names plus IDs outside 01~10 throw `MemberProfileInvalidException` with the correct field.

- [ ] **Step 2: Run identity profile tests to verify RED**

Run: `./gradlew :modules:identity:test --tests '*MemberProfileServiceTests' --no-daemon`

Expected: FAIL because the profile package does not exist.

- [ ] **Step 3: Implement the profile domain**

Define the two 10-value enums, immutable records, generator, store port, patch validation, and service signatures listed above. `findByMemberIds(Set.of())` must return an empty map without store I/O; update must preserve omitted fields and use the persisted row as the source of truth.

- [ ] **Step 4: Write failing OAuth and in-memory tests**

Extend `OAuthLoginServiceTests` to assert first login creates one profile, a later login for the same external identity keeps user edits, and concurrent first-login callbacks produce one member/account/profile. Assert `MemberSummary` carries non-null `displayName`, `characterId`, and `backgroundId`.

- [ ] **Step 5: Verify OAuth RED**

Run: `./gradlew :modules:identity:test --tests '*OAuthLoginServiceTests' --no-daemon`

Expected: FAIL because OAuth linking does not accept or persist `NewMemberProfile`.

- [ ] **Step 6: Integrate OAuth and both stores**

Inject `MemberProfileGenerator` into `OAuthLoginService`; change `IdentityStore.linkExternalIdentity` to accept generated defaults; make `InMemoryIdentityStore` and `JdbcIdentityStore` implement `MemberProfileStore`; insert member, external account, and profile in the existing first-login transaction; ignore generated defaults for an existing account; join profile fields in `findActiveMemberBySessionHash`; batch JDBC reads with one `member_id = ANY (?)` query; update only ACTIVE members.

- [ ] **Step 7: Write and run the JDBC integration assertions**

Extend `JdbcIdentityStampFlowTests.oauthLoginCreatesCanonicalMemberUsedByCheckInAndRankingWithoutMemberPreseed` to assert the session summary profile is non-null, the DB has exactly one profile, and a second login preserves an updated profile.

Run: `./gradlew :apps:spring-api:test --tests '*JdbcIdentityStampFlowTests' --no-daemon`

Expected: PASS with one atomic profile row and preserved edit.

- [ ] **Step 8: Run the Identity suites**

Run: `./gradlew :modules:identity:test :adapters:persistence-jdbc:test :apps:spring-api:test --tests '*OAuthLoginServiceTests' --tests '*MemberProfileServiceTests' --tests '*JdbcIdentityStampFlowTests' --no-daemon`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add modules/identity adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/identity/JdbcIdentityStore.java apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcIdentityStampFlowTests.java
git commit -m "feat(profile): OAuth 회원 익명 프로필 생성"
```

### Task 3: 내 프로필 GET·PATCH API

**Files:**
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/security/oauth/kakao/KakaoOAuthConfiguration.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/member/MemberLifecycleController.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/member/MemberLifecycleWebBoundaryTests.java`

**Interfaces:**
- Consumes: Task 2 `MemberProfileService`, expanded `MemberSummary`, and `MemberProfilePatch`.
- Produces: `GET /api/v1/members/me` and CSRF-protected `PATCH /api/v1/members/me`, both returning `MemberMeResponse(schemaVersion, id, displayName, characterId, backgroundId)`. PATCH는 raw `JsonNode`의 `has(field)`와 `get(field).isNull()`을 구분해 누락과 명시적 null을 다르게 처리한다.

- [ ] **Step 1: Write failing GET/PATCH web tests**

Extend `MemberLifecycleWebBoundaryTests` to assert GET returns all three non-null profile fields; PATCH changes any subset and returns the full profile; a later GET returns the saved values; omitted fields stay unchanged; no session returns 401; missing CSRF returns 403; empty body, explicit null, invalid display name, `CHARACTER_00/11`, and `BACKGROUND_00/11` return 400 with `details.field`.

- [ ] **Step 2: Run web tests to verify RED**

Run: `./gradlew :apps:spring-api:test --tests '*MemberLifecycleWebBoundaryTests' --no-daemon`

Expected: FAIL because GET lacks IDs and PATCH has no handler.

- [ ] **Step 3: Wire profile service and implement PATCH**

Register `MemberProfileGenerator` and `MemberProfileService` beans over the configured in-memory/JDBC store. Add a `@PatchMapping` that accepts a raw Jackson `JsonNode`, maps only present non-null fields to `MemberProfilePatch`, returns field-specific validation errors for explicit null, and expands `MemberMeResponse`; resolve the authenticated member via the existing lifecycle service and return `Cache-Control: no-store`.

- [ ] **Step 4: Run web tests to verify GREEN**

Run: `./gradlew :apps:spring-api:test --tests '*MemberLifecycleWebBoundaryTests' --no-daemon`

Expected: PASS with GET/PATCH/CSRF/error assertions.

- [ ] **Step 5: Commit**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/security/oauth/kakao/KakaoOAuthConfiguration.java apps/spring-api/src/main/java/com/yrootlab/onmaru/web/member/MemberLifecycleController.java apps/spring-api/src/test/java/com/yrootlab/onmaru/web/member/MemberLifecycleWebBoundaryTests.java
git commit -m "feat(profile): 내 프로필 조회와 수정 API 제공"
```

### Task 4: 온기 후기의 최신 작성자 프로필

**Files:**
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/query/VisitReviewAuthor.java`
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/query/VisitReviewAuthorProfileLookup.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/query/VisitReview.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/query/VisitReviewQueryService.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/command/review/VisitReviewCommandService.java`
- Modify: `modules/community/src/test/java/com/yrootlab/onmaru/community/query/VisitReviewQueryServiceTests.java`
- Modify: `modules/community/src/test/java/com/yrootlab/onmaru/community/command/review/VisitReviewCommandServiceTests.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/query/VisitReviewQueryConfiguration.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review/query/VisitReviewQueryWebBoundaryTests.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review/command/VisitReviewCommandWebBoundaryTests.java`

**Interfaces:**
- Consumes: Task 2 `MemberProfileService.findByMemberIds(Set<UUID>)`.
- Produces: `VisitReview.author()` with `displayName`, `characterId`, `backgroundId`; Community port `Map<UUID, VisitReviewAuthor> findByMemberIds(Set<UUID>)` returns only public profile values.

- [ ] **Step 1: Write failing query/command tests**

Add tests that assert list and create results contain the author object, a mutable lookup change appears on the next query for an old review, duplicate authors are passed to the lookup once as a deduplicated set, no internal author UUID is serialized, and a missing profile maps to `탈퇴한 여행자`/`CHARACTER_01`/`BACKGROUND_01` without failing the page.

- [ ] **Step 2: Run Community tests to verify RED**

Run: `./gradlew :modules:community:test --tests '*VisitReviewQueryServiceTests' --tests '*VisitReviewCommandServiceTests' --no-daemon`

Expected: FAIL because `VisitReview` has no author and services have no lookup.

- [ ] **Step 3: Implement the Community profile port and mapping**

Add `VisitReviewAuthor`, `VisitReviewAuthorProfileLookup`, the neutral fallback factory, and inject the lookup into query/command services. Query service must collect author IDs only after pagination, perform one lookup call, and map current author profiles without changing cursor/totalCount behavior.

- [ ] **Step 4: Adapt Identity in Spring configuration**

Create the application-level adapter bean in `VisitReviewQueryConfiguration` that converts Task 2 `MemberProfile` values to Community `VisitReviewAuthor`; inject it into query and command services. Do not add a Community dependency to the Identity module.

- [ ] **Step 5: Verify web JSON boundaries**

Extend both VisitReview web boundary tests to assert `author.displayName`, `author.characterId`, and `author.backgroundId`, and assert `author.memberId`/`authorMemberId` are absent.

Run: `./gradlew :apps:spring-api:test --tests '*VisitReviewQueryWebBoundaryTests' --tests '*VisitReviewCommandWebBoundaryTests' --no-daemon`

Expected: PASS without cursor, like, mine, or create-response regressions.

- [ ] **Step 6: Run Community regression tests**

Run: `./gradlew :modules:community:test :apps:spring-api:test --tests '*VisitReview*Tests' --no-daemon`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add modules/community apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/query/VisitReviewQueryConfiguration.java apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review
git commit -m "feat(review): 온기 후기에 최신 작성자 프로필 제공"
```

### Task 5: OpenAPI·fixture·FE 계약 동결

**Files:**
- Modify: `docs/contracts/openapi/identity-saved.openapi.yaml`
- Modify: `docs/contracts/fixtures/identity-saved/member-me-normal.json`
- Create: `docs/contracts/fixtures/identity-saved/member-profile-update-normal.json`
- Modify: `docs/contracts/openapi/visit-reviews.openapi.json`
- Modify: `docs/contracts/frontend-handoff.md`
- Create: `docs/toFE/member-profile-api-handoff-2026-10-03.md`
- Modify: `docs/toFE/kakao-login-flow.md`

**Interfaces:**
- Consumes: Tasks 3~4 runtime JSON.
- Produces: machine-readable MemberMe/PATCH/VisitReviewAuthor schemas and FE #292 implementation handoff.

- [ ] **Step 1: Update fixtures first and verify contract RED**

Change `member-me-normal.json` to require the three profile values and add a PATCH fixture with CSRF request metadata and full response. Add `author` to the VisitReview response example/schema expectations.

Run: `bash scripts/verify-contracts`

Expected: FAIL because OpenAPI does not yet allow PATCH/profile fields/author.

- [ ] **Step 2: Update both OpenAPI documents**

Add `CharacterId` and `BackgroundId` patterns with exact 01~10 bounds, non-null `MemberMe` fields, partial update request with at least one property, 200/400/401/403 PATCH responses, and required `VisitReviewAuthor`. Keep `additionalProperties: false` and do not add `profileImageUrl`, URL, HEX, or member ID to author.

- [ ] **Step 3: Write the FE handoff**

Document the 20 stable IDs, FE-owned asset/HEX maps, GET/PATCH examples, CSRF, validation errors, 100-combination contrast rule, unknown-ID fallback, latest-profile behavior, and links to BE #552 and FE #292. Update Kakao flow to state that Kakao profile fields are not collected.

- [ ] **Step 4: Verify contracts GREEN**

Run: `bash scripts/verify-contracts && python3 scripts/validate_contracts.py`

Expected: both commands pass; fixture schemas reject ID 00/11 and missing required author fields.

- [ ] **Step 5: Commit**

```bash
git add docs/contracts docs/toFE
git commit -m "docs(profile): FE 익명 프로필 계약 동결"
```

### Task 6: 전체 회귀 검증과 작업 로그 정리

**Files:**
- Modify: `handoff.md`

**Interfaces:**
- Consumes: Tasks 1~5 complete implementation.
- Produces: reproducible verification record and PR-ready branch state.

- [ ] **Step 1: Run focused modules and PostgreSQL tests**

Run: `./gradlew :modules:identity:test :modules:community:test :adapters:persistence-jdbc:test :apps:spring-api:test --tests '*MemberProfile*' --tests '*OAuthLogin*' --tests '*MemberLifecycle*' --tests '*VisitReview*' --tests '*IdentityMigrationTests' --tests '*JdbcIdentityStampFlowTests' --no-daemon`

Expected: `BUILD SUCCESSFUL` with no failed tests.

- [ ] **Step 2: Run full repository verification**

Run: `./gradlew test --no-daemon && bash scripts/verify-contracts && node --test scripts/test/*.test.mjs && (cd ai && uv run pytest)`

Expected: all Java, contract, Node, and Python tests pass. Record every unrelated pre-existing failure by exact test name if the full run is not green.

- [ ] **Step 3: Verify hygiene and branch linkage**

Run: `git diff --check && node scripts/print-branch-issue.mjs && git status --short`

Expected: no whitespace errors; branch parser prints `552`; only intended files are modified.

- [ ] **Step 4: Update handoff and commit verification evidence**

Record changed schema/API/contracts, FE #292, exact verification commands/results, remaining staging risk, and the next PR step in `handoff.md`.

```bash
git add handoff.md
git commit -m "docs(handoff): 회원 프로필 검증 결과 기록"
```
