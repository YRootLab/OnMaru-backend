# 한옥 수결첩 익명 공개 랭킹 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 명시적으로 동의한 활성 회원만 서버 생성 익명 별명으로 노출되는 한옥 수결첩 공개 랭킹과 개인 참여 설정 API를 구현한다.

**Architecture:** `modules:stamp`에 framework 독립 랭킹 모델·service·store port를 두고, `adapters:persistence-jdbc`가 PostgreSQL 원장 집계와 참여 transaction을 구현한다. `apps:spring-api`는 인증·CSRF·HTTP DTO·오류 계약을 담당하며, 점수 projection이나 cache 없이 `stamp_awards`를 실시간 집계한다.

**Tech Stack:** Java 21, Spring Boot 3, JDBC, PostgreSQL 17/PostGIS, Flyway, JUnit 5, AssertJ, MockMvc, Testcontainers, OpenAPI 3.1, Python JSON Schema contract validator

## Global Constraints

- 랭킹 참여 기본값은 `false`이며 동의하지 않은 회원은 익명이어도 공개하지 않는다.
- 첫 버전은 서버 생성 익명 별명만 지원하고 사용자 입력 nickname과 금칙어 정책은 후속 Issue로 분리한다.
- 공개 응답에 OAuth 이름, 이메일, member ID, 내부 UUID, 위치, 장소, 체크인·수결 시각을 포함하지 않는다.
- 참여 철회는 rate limit보다 우선하며 commit 직후 시작된 공개 조회에서 제외되어야 한다.
- `GET /api/v1/stamps/leaderboard`와 두 개인 API 모두 `Cache-Control: no-store`를 사용한다.
- ordinal rank 정렬은 `stampCount DESC`, `visitedRegionCount DESC`, `lastAwardedAt ASC NULLS LAST`, `rankingPublicId ASC` 순이다.
- 수결 수·방문 권역 수·완료율은 활성 `stamp_definitions`와 `stamp_awards`에서 계산하며 mutable 점수 column을 만들지 않는다.
- 모든 production 동작은 실패 test를 먼저 실행하고 예상 원인으로 실패한 것을 확인한 뒤 구현한다.
- 구현과 검증이 끝나도 사용자 최종 승인 전에는 원격 PR을 갱신하거나 merge하지 않는다.

## 파일 구조

- `apps/spring-api/src/main/resources/db/migration/baseline/V029__262_hanok_stamp_ranking.sql`: 참여 profile table, 제약과 index
- `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/*`: 랭킹 모델, 익명 identity 생성, service와 store port
- `modules/stamp/src/test/java/com/yrootlab/onmaru/stamp/ranking/StampRankingServiceTests.java`: 상태 전이·익명화·정렬 domain test
- `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/InMemoryStampStore.java`: local/test용 랭킹 store 구현
- `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/stamp/JdbcStampRankingStore.java`: 참여 transaction과 실시간 집계 query
- `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampRankingController.java`: 공개 및 개인 랭킹 HTTP 경계
- `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampApiContract.java`: stamp 계약 version `1.3`
- `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/stamp/StampRankingWebBoundaryTests.java`: 인증·CSRF·cache·privacy web test
- `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcStampRankingStoreTests.java`: PostgreSQL 집계·동시성·철회 integration test
- `docs/contracts/fixtures/hanok-stamps/*.json`: 실행 가능한 공개·개인 랭킹 fixture
- `docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md`: FE 호출 및 상태 처리 인계

---

### Task 1: V029 참여 설정 schema와 migration 계약

**Files:**
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V029__262_hanok_stamp_ranking.sql`
- Modify: `db/migration/registry/migrations.json`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcStampMigrationTests.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/DatabaseMigrationContractTests.java`
- Modify: `docs/database/schema.md`

**Interfaces:**
- Consumes: `onmaru.identity_members(id, status)`와 V028의 `stamp_awards`
- Produces: `onmaru.stamp_ranking_profiles` 및 latest Flyway version `029`

- [ ] **Step 1: migration 실패 test 작성**

`JdbcStampMigrationTests`에 기본값, 참여/미참여 check constraint, partial uniqueness와 cascade를 실제 SQL로 검증하는 test를 추가한다.

```java
@Test
void constrainsPrivateByDefaultRankingProfilesAndDeletesThemWithMember() throws Exception {
    var memberId = UUID.randomUUID();
    seedMember(memberId);
    insertNonParticipatingProfile(memberId);

    assertThat(profileParticipating(memberId)).isFalse();
    assertThatThrownBy(() -> insertParticipatingWithoutAnonymousIdentity(UUID.randomUUID()))
            .isInstanceOf(SQLException.class);
    deleteMember(memberId);
    assertThat(profileCount(memberId)).isZero();
}
```

`DatabaseMigrationContractTests`의 최신 version 기대값 두 곳을 `029`로 바꾸고 table 존재를 검증한다.

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :apps:spring-api:test --tests '*JdbcStampMigrationTests' --tests '*DatabaseMigrationContractTests' --no-daemon --max-workers=1
```

Expected: `stamp_ranking_profiles` relation과 V029가 없어 실패한다.

- [ ] **Step 3: V029 최소 schema 구현**

다음 shape과 동등한 migration을 작성한다.

```sql
CREATE TABLE onmaru.stamp_ranking_profiles (
    member_id uuid PRIMARY KEY REFERENCES onmaru.identity_members (id) ON DELETE CASCADE,
    ranking_public_id uuid,
    public_nickname varchar(20),
    nickname_normalized varchar(20),
    nickname_type varchar,
    participating boolean NOT NULL DEFAULT false,
    consented_at timestamptz,
    withdrawn_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT stamp_ranking_profiles_state_ck CHECK (
        (participating AND ranking_public_id IS NOT NULL
          AND public_nickname IS NOT NULL AND nickname_normalized IS NOT NULL
          AND nickname_type = 'GENERATED' AND consented_at IS NOT NULL
          AND withdrawn_at IS NULL)
        OR
        (NOT participating AND ranking_public_id IS NULL
          AND public_nickname IS NULL AND nickname_normalized IS NULL
          AND nickname_type IS NULL)
    ),
    CONSTRAINT stamp_ranking_profiles_nickname_ck CHECK (
        public_nickname IS NULL OR (
            public_nickname = btrim(public_nickname)
            AND char_length(public_nickname) BETWEEN 2 AND 20
        )
    )
);

CREATE UNIQUE INDEX stamp_ranking_profiles_public_id_uq
    ON onmaru.stamp_ranking_profiles (ranking_public_id)
    WHERE participating;
CREATE UNIQUE INDEX stamp_ranking_profiles_nickname_uq
    ON onmaru.stamp_ranking_profiles (nickname_normalized)
    WHERE participating;
CREATE INDEX stamp_ranking_profiles_participating_idx
    ON onmaru.stamp_ranking_profiles (member_id)
    WHERE participating;
```

마지막 예약 insert는 version `029`, `HANOK_STAMP_RANKING`, Issue `262`를 사용한다. SQL SHA-256을 계산해 registry에 기록한다.

- [ ] **Step 4: migration GREEN과 정책 검증**

Run:

```bash
node --test scripts/test/migration-policy.test.mjs
./gradlew :apps:spring-api:test --tests '*JdbcStampMigrationTests' --tests '*DatabaseMigrationContractTests' --no-daemon --max-workers=1
```

Expected: 두 명령 모두 PASS이며 latest version이 `029`다.

- [ ] **Step 5: schema 문서화 및 커밋**

`docs/database/schema.md`에 nullable 공개 식별자, opt-in constraint, cascade와 비저장 점수 원칙을 추가한다.

```bash
git add apps/spring-api/src/main/resources/db/migration/baseline/V029__262_hanok_stamp_ranking.sql db/migration/registry/migrations.json apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcStampMigrationTests.java apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/DatabaseMigrationContractTests.java docs/database/schema.md
git commit -m "feat(stamp): 익명 랭킹 참여 스키마 추가"
```

### Task 2: 랭킹 domain 모델과 익명 상태 전이

**Files:**
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingNicknameType.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingIdentity.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingEntry.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingStatus.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampLeaderboard.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingStore.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingIdentityGenerator.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/RandomStampRankingIdentityGenerator.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingService.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingInputInvalidException.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingIdentityConflictException.java`
- Create: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/ranking/StampRankingRateLimitedException.java`
- Modify: `modules/stamp/src/main/java/com/yrootlab/onmaru/stamp/InMemoryStampStore.java`
- Create: `modules/stamp/src/test/java/com/yrootlab/onmaru/stamp/ranking/StampRankingServiceTests.java`

**Interfaces:**
- Consumes: 기존 `InMemoryStampStore.book(UUID)`의 authoritative `StampBookSummary`
- Produces:

```java
public interface StampRankingStore {
    List<StampRankingEntry> leaderboard(int limit);
    StampRankingStatus status(UUID memberId);
    StampRankingStatus participate(UUID memberId, StampRankingIdentity identity, Instant now);
    StampRankingStatus withdraw(UUID memberId, Instant now);
}
```

- [ ] **Step 1: identity와 참여 상태 실패 test 작성**

```java
@Test
void optsInWithGeneratedIdentityAndErasesItImmediatelyOnWithdrawal() {
    var store = new InMemoryStampStore();
    var identities = new QueueIdentityGenerator(
            StampRankingIdentity.generated(PUBLIC_ID, "고즈넉한여행자-A7K2"));
    var service = rankingService(store, identities, NOW);

    assertThat(service.status(MEMBER_ID).participating()).isFalse();
    assertThat(service.update(MEMBER_ID, true).publicNickname())
            .isEqualTo("고즈넉한여행자-A7K2");
    assertThat(service.update(MEMBER_ID, false).publicNickname()).isNull();
    assertThat(service.leaderboard(20).entries()).isEmpty();
}
```

별도 test로 NFKC 정규화, 2~20 code point, 다섯 번 충돌 후 503용 예외, `limit` 0·101 거절, 같은 상태 update의 멱등성을 검증한다.

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :modules:stamp:test --tests '*StampRankingServiceTests' --no-daemon --max-workers=1
```

Expected: ranking package type이 없어 compile FAIL한다.

- [ ] **Step 3: 모델과 identity 생성기 구현**

`StampRankingIdentity.generated(UUID, String)`이 직접 정규화와 검증을 수행하게 한다.

```java
public static StampRankingIdentity generated(UUID publicId, String nickname) {
    var display = Normalizer.normalize(nickname.strip(), Normalizer.Form.NFKC);
    int length = display.codePointCount(0, display.length());
    if (length < 2 || length > 20) {
        throw new StampRankingInputInvalidException("publicNickname");
    }
    return new StampRankingIdentity(
            publicId, display, display.toLowerCase(Locale.ROOT),
            StampRankingNicknameType.GENERATED);
}
```

production generator는 회원 정보 없이 고정 adjective/noun 목록, `SecureRandom`, 4자리 Crockford Base32 suffix와 `UUID.randomUUID()`를 사용한다.

- [ ] **Step 4: service의 최대 5회 충돌 재시도 구현**

```java
public StampRankingStatus update(UUID memberId, boolean participating) {
    if (!participating) {
        return store.withdraw(memberId, clock.instant());
    }
    for (int attempt = 0; attempt < 5; attempt++) {
        try {
            return store.participate(memberId, identities.generate(), clock.instant());
        } catch (StampRankingIdentityConflictException ignored) {
            // 새 무작위 후보로 제한된 재시도
        }
    }
    throw new IllegalStateException("Failed to allocate anonymous ranking identity");
}
```

`leaderboard(limit)`는 1~100을 검증하고 `clock.instant()`을 `generatedAt`으로 감싼다.

- [ ] **Step 5: InMemory store에서 ordinal rank와 상태 전이 구현**

profile map과 기존 award map을 같은 synchronized 경계에서 읽는다. 정렬 comparator는 네 기준을 모두 명시하고 `rank`를 1부터 부여한다. 철회는 공개 identity를 제거하며 참여 요청 직후 5초 이내 재참여만 `StampRankingRateLimitedException`으로 막는다. 철회와 같은 상태의 멱등 요청은 제한하지 않는다.

- [ ] **Step 6: GREEN 및 stamp 회귀 test 실행**

Run:

```bash
./gradlew :modules:stamp:test --no-daemon --max-workers=1
```

Expected: 신규 ranking test와 기존 `StampServiceTests` 모두 PASS한다.

- [ ] **Step 7: 커밋**

```bash
git add modules/stamp/src/main modules/stamp/src/test
git commit -m "feat(stamp): 익명 랭킹 도메인 추가"
```

### Task 3: PostgreSQL 랭킹 store와 결정적 집계

**Files:**
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/stamp/JdbcStampRankingStore.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcStampRankingStoreTests.java`

**Interfaces:**
- Consumes: Task 1의 `stamp_ranking_profiles`, Task 2의 `StampRankingStore`
- Produces: production 참여 transaction과 단일-query leaderboard/status 집계

- [ ] **Step 1: 공개 필터와 집계 동등성 실패 test 작성**

Testcontainers fixture에 미동의, 참여, `DELETING` 회원과 award를 넣고 다음을 검증한다.

```java
var entries = store.leaderboard(20);

assertThat(entries).extracting(StampRankingEntry::publicNickname)
        .containsExactly("달빛여행자-A001");
assertThat(entries.getFirst().stampCount()).isEqualTo(
        new JdbcStampStore(dataSource).book(activeMember).summary().collectedCount());
assertThat(entries.getFirst().visitedRegionCount()).isEqualTo(
        new JdbcStampStore(dataSource).book(activeMember).summary().visitedRegionCount());
```

추가 test는 최종 tie-break, limit 적용 전 rank, 철회 즉시 제외, 재동의 공개 ID 회전과 award 추가 직후 갱신을 검증한다.

- [ ] **Step 2: 동시성과 rate limit 실패 test 작성**

두 thread가 같은 미참여 회원에게 동시에 `participate`를 호출하고 둘 다 같은 저장 profile을 받는지 검증한다. `true → false`는 5초 안에도 성공하고, 바로 `false → true`만 `StampRankingRateLimitedException`인지 확인한다.

- [ ] **Step 3: RED 확인**

Run:

```bash
./gradlew :apps:spring-api:test --tests '*JdbcStampRankingStoreTests' --no-daemon --max-workers=1
```

Expected: `JdbcStampRankingStore`가 없어 compile FAIL한다.

- [ ] **Step 4: 참여 transaction 구현**

모든 변경 transaction 시작 시 아래 lock을 얻는다.

```sql
SELECT pg_advisory_xact_lock(hashtext('stamp-ranking|' || ?));
```

현재 row를 읽어 같은 상태면 그대로 반환한다. 참여 시작은 `updated_at + interval '5 seconds'`를 확인한 뒤 후보 identity를 upsert한다. SQLState `23505` 중 공개 ID 또는 nickname unique constraint만 `StampRankingIdentityConflictException`으로 변환한다. 철회는 rate limit 없이 공개 필드를 null로 만든다.

- [ ] **Step 5: 단일 집계 CTE 구현**

다음 의미를 보존하는 parameterized SQL을 사용한다.

```sql
WITH active_definitions AS (... WHERE active),
participant_scores AS (
  SELECT profile.member_id,
         profile.ranking_public_id,
         profile.public_nickname,
         count(definition.code)::int AS stamp_count,
         count(DISTINCT definition.region_group)
           FILTER (WHERE definition.condition_type = 'REGION_VISIT')::int AS visited_region_count,
         max(award.awarded_at) FILTER (WHERE definition.code IS NOT NULL) AS last_awarded_at
  FROM onmaru.stamp_ranking_profiles profile
  JOIN onmaru.identity_members member
    ON member.id = profile.member_id AND member.status = 'ACTIVE'
  LEFT JOIN onmaru.stamp_awards award ON award.member_id = profile.member_id
  LEFT JOIN active_definitions definition ON definition.code = award.stamp_code
  WHERE profile.participating
  GROUP BY profile.member_id, profile.ranking_public_id, profile.public_nickname
), ranked AS (
  SELECT row_number() OVER (
           ORDER BY stamp_count DESC, visited_region_count DESC,
                    last_awarded_at ASC NULLS LAST, ranking_public_id ASC
         )::int AS rank,
         *
  FROM participant_scores
)
SELECT ... FROM ranked ORDER BY rank LIMIT ?;
```

award count는 active definition join 결과만 세고, 완료율 분모도 같은 active definition count를 사용한다.

- [ ] **Step 6: query plan test 추가**

`EXPLAIN (FORMAT JSON)` 결과에 participant profile과 member-leading award index가 등장하는지 검증하고, repository 호출 한 번이 전체 목록을 반환하는지 확인한다. 구체적인 운영 latency 수치는 staging 전에는 주장하지 않는다.

- [ ] **Step 7: GREEN 및 기존 JDBC stamp 회귀 실행**

Run:

```bash
./gradlew :apps:spring-api:test --tests '*JdbcStampRankingStoreTests' --tests '*JdbcStampStoreTests' --no-daemon --max-workers=1
```

Expected: 신규 및 기존 JDBC test 모두 PASS한다.

- [ ] **Step 8: 커밋**

```bash
git add adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/stamp/JdbcStampRankingStore.java apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcStampRankingStoreTests.java
git commit -m "feat(stamp): 공개 랭킹 JDBC 집계 구현"
```

### Task 4: 공개·개인 랭킹 HTTP API

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampApiContract.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampRankingController.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampController.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampConfiguration.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp/StampExceptionHandler.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/security/web/CsrfProtectionFilter.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/stamp/StampRankingWebBoundaryTests.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/stamp/StampWebBoundaryTests.java`

**Interfaces:**
- Consumes: `StampRankingService`, `MemberLifecycleService`, 기존 CSRF filter
- Produces: 세 ranking endpoint와 stamp 응답 `schemaVersion: "1.3"`

- [ ] **Step 1: 공개 조회 RED web test 작성**

```java
mockMvc.perform(get("/api/v1/stamps/leaderboard"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.schemaVersion").value("1.3"))
        .andExpect(jsonPath("$.entries[0].publicId").exists())
        .andExpect(jsonPath("$.entries[0].memberId").doesNotExist())
        .andExpect(jsonPath("$.entries[0].checkedInAt").doesNotExist());
```

기본 limit 20, 1과 100 성공, 0과 101의 `400 VALIDATION_ERROR`도 같은 test class에서 검증한다.

- [ ] **Step 2: 개인 조회·변경 RED web test 작성**

- session 없음: GET/PUT `401 AUTH_REQUIRED`
- CSRF 없음: PUT `403 CSRF_INVALID`
- 미참여 GET: 200, null nickname/rank
- 참여 PUT: generated nickname과 `GENERATED`
- 같은 PUT replay: 같은 공개 ID와 200
- 철회 PUT: 즉시 null nickname/rank와 공개 목록 제외
- private/public 모두 `no-store`
- 재참여 제한: `429 RATE_LIMITED`, `Retry-After`, details 초 단위 값

- [ ] **Step 3: RED 확인**

Run:

```bash
./gradlew :apps:spring-api:test --tests '*StampRankingWebBoundaryTests' --no-daemon --max-workers=1
```

Expected: endpoint가 없어 404로 실패한다.

- [ ] **Step 4: controller와 bean wiring 구현**

`StampRankingController`는 아래 세 mapping을 갖는다.

```java
@GetMapping("/api/v1/stamps/leaderboard")
ResponseEntity<StampLeaderboardResponse> leaderboard(
        @RequestParam(defaultValue = "20") int limit)

@PrivateResponse
@GetMapping("/api/v1/me/stamp-ranking")
ResponseEntity<?> status(@CookieValue(name = SESSION_COOKIE, required = false) String session,
                         HttpServletRequest request)

@PrivateResponse
@PutMapping("/api/v1/me/stamp-ranking")
ResponseEntity<?> update(@RequestBody(required = false) StampRankingUpdateRequest body,
                         @CookieValue(name = SESSION_COOKIE, required = false) String session,
                         HttpServletRequest request)
```

`StampConfiguration`은 non-production에서 `InMemoryStampStore`를 `StampRankingStore`로도 사용하고 production에서 `JdbcStampRankingStore`를 주입한다. `RandomStampRankingIdentityGenerator`, `StampRankingService` bean을 추가한다.

- [ ] **Step 5: version과 오류 mapping 구현**

`StampApiContract.SCHEMA_VERSION = "1.3"`을 두 controller와 stamp advice가 사용한다. ranking validation은 400, ranking rate limit은 429와 `Retry-After`, DB/identity allocation 실패는 503으로 변환한다. CSRF filter는 stamp mutation 경로에서 1.3 오류 envelope를 반환하되 다른 API의 1.2 계약은 바꾸지 않는다.

- [ ] **Step 6: GREEN 및 기존 endpoint 회귀 실행**

Run:

```bash
./gradlew :apps:spring-api:test --tests '*StampRankingWebBoundaryTests' --tests '*StampWebBoundaryTests' --no-daemon --max-workers=1
```

Expected: 신규 endpoint와 기존 catalog/book/check-in 모두 PASS한다.

- [ ] **Step 7: 커밋**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/web/stamp apps/spring-api/src/main/java/com/yrootlab/onmaru/security/web/CsrfProtectionFilter.java apps/spring-api/src/test/java/com/yrootlab/onmaru/web/stamp
git commit -m "feat(api): 익명 수결 랭킹 API 추가"
```

### Task 5: OpenAPI 1.3과 실행 가능한 계약 fixture

**Files:**
- Modify: `docs/contracts/openapi/hanok-stamps.openapi.yaml`
- Create: `docs/contracts/fixtures/hanok-stamps/leaderboard-normal.json`
- Create: `docs/contracts/fixtures/hanok-stamps/ranking-not-participating.json`
- Create: `docs/contracts/fixtures/hanok-stamps/ranking-participating.json`
- Create: `docs/contracts/fixtures/hanok-stamps/ranking-rate-limited.json`
- Modify: `scripts/validate_contracts.py`
- Modify: `scripts/test/test_contract_validation.py`

**Interfaces:**
- Consumes: Task 4의 실제 JSON field와 status/header
- Produces: OpenAPI `info.version: 1.3.0`과 validator가 실행하는 4개 fixture

- [ ] **Step 1: validator RED test 작성**

stamp 필수 path/schema 목록에 다음을 요구한다.

```python
required_operations = {
    "/stamps/leaderboard": {"get": {"200", "400", "503"}},
    "/me/stamp-ranking": {
        "get": {"200", "401", "503"},
        "put": {"200", "400", "401", "403", "429", "503"},
    },
}
```

필수 schema에는 `StampLeaderboardResponse`, `StampRankingStatusResponse`, `StampRankingUpdateRequest`, `StampRankingEntry`를 추가한다.

- [ ] **Step 2: RED 확인**

Run:

```bash
python3 -m pytest scripts/test/test_contract_validation.py -q
```

Expected: 현재 OpenAPI에 ranking path가 없어 FAIL한다.

- [ ] **Step 3: OpenAPI와 fixture 구현**

- document version `1.3.0`
- ranking response `schemaVersion` const `1.3`
- `limit` default 20, minimum 1, maximum 100
- 모든 response `additionalProperties: false`
- 개인 nullable field는 OpenAPI 3.1 union type으로 표현
- public entry에는 privacy 금지 field를 정의하지 않음
- PUT body는 required `participating` 한 필드만 허용
- `no-store`와 429 `Retry-After` header를 선언

fixture group 이름이 OpenAPI 파일 stem과 맞도록 directory를 `hanok-stamps`로 사용한다.

- [ ] **Step 4: GREEN 계약 검증**

Run:

```bash
python3 -m pytest scripts/test/test_contract_validation.py -q
bash scripts/verify-contracts --contracts-only
```

Expected: validator unit test와 모든 repository fixture가 PASS한다.

- [ ] **Step 5: 커밋**

```bash
git add docs/contracts/openapi/hanok-stamps.openapi.yaml docs/contracts/fixtures/hanok-stamps scripts/validate_contracts.py scripts/test/test_contract_validation.py
git commit -m "docs(api): 수결 랭킹 OpenAPI 1.3 계약 추가"
```

### Task 6: FE 인계와 운영 문서

**Files:**
- Modify: `docs/contracts/rest-api.md`
- Modify: `docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md`
- Modify: `docs/superpowers/specs/2026-09-26-hanok-stamp-book-design.md`
- Modify: `handoff.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: 확정된 OpenAPI field, error code, cache와 tie-break 규칙
- Produces: 비개발자도 호출·화면 상태를 이해할 수 있는 FE 인계 문서

- [ ] **Step 1: 기존 설계의 제외 문구를 후속 구현 링크로 갱신**

기존 설계에서 “탐방 랭킹 제외” 단락을 삭제하지 말고, `2026-09-27-hanok-stamp-ranking-design.md`에서 opt-in 익명 랭킹이 승인·구현됐음을 기록해 의사결정 이력을 보존한다.

- [ ] **Step 2: FE 호출 예제와 상태표 작성**

인계 문서에 아래 상태를 표로 추가한다.

| 상태 | FE 처리 |
|---|---|
| 비로그인 공개 조회 | leaderboard 표시, 참여 버튼은 로그인 유도 |
| 로그인·미참여 | 내 진행률 표시, 익명 참여 CTA 표시 |
| 로그인·참여 | 내 rank·익명 별명 표시, 철회 action 제공 |
| 429 | `Retry-After` 이후 참여 재시도, 철회는 즉시 가능 |
| 철회 성공 | 공개 목록 refetch 후 개인 nickname/rank 제거 |

`curl` 또는 `fetch` 예제에 cookie credentials, `/auth/csrf`, `X-CSRF-TOKEN`, GET/PUT request와 정확한 response type을 넣는다.

- [ ] **Step 3: 개인정보·정렬·cache 문서화**

내부 식별자/OAuth/방문 이력은 절대 노출하지 않으며, cache 없음, 철회 반영은 commit 후 다음 요청, ordinal tie-break 네 단계, generated nickname만 지원함을 명시한다.

- [ ] **Step 4: work log와 changelog 갱신 및 커밋**

`handoff.md`에는 branch, Issue #262, V029, 변경 API, 검증 명령, 원격 PR 미갱신 상태를 기록한다.

```bash
git add docs/contracts/rest-api.md docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md docs/superpowers/specs/2026-09-26-hanok-stamp-book-design.md handoff.md CHANGELOG.md
git commit -m "docs(fe): 익명 수결 랭킹 연동 안내 추가"
```

### Task 7: 전체 회귀 검증과 최종 인계

**Files:**
- Verify only: Tasks 1~6에서 커밋한 source, test, migration과 문서 전체

**Interfaces:**
- Consumes: Tasks 1~6 전체 결과
- Produces: 원격 push 전 검증 근거와 사용자 승인 가능한 변경 요약

- [ ] **Step 1: 변경 무결성 검사**

Run:

```bash
git diff --check
node scripts/print-branch-issue.mjs
node --test scripts/test/migration-policy.test.mjs
```

Expected: whitespace 오류 없음, Issue `262`, migration policy PASS.

- [ ] **Step 2: Java 전체 검증**

Run:

```bash
./gradlew test --no-daemon --max-workers=1
```

Expected: 모든 module과 Testcontainers test PASS.

- [ ] **Step 3: Node·계약·AI 회귀 검증**

Run:

```bash
node --test scripts/test/*.test.mjs
bash scripts/verify-contracts --contracts-only
cd apps/ai && uv run ruff check . && uv run mypy . && uv run pytest -q
```

Expected: Node, contract, ruff, mypy, pytest 모두 PASS 또는 기존에 문서화된 skip만 존재한다.

- [ ] **Step 4: 개인정보 payload 최종 점검**

MockMvc와 fixture 결과에서 `memberId`, `email`, `oauth`, `latitude`, `longitude`, `placeId`, `checkedInAt`, `lastAwardedAt`이 공개 ranking entry에 없음을 확인한다. 철회 직후 같은 test process의 공개 조회가 빈 목록임을 다시 실행한다.

- [ ] **Step 5: 작업 로그 정리와 최종 로컬 커밋**

```bash
git status --short
git log --oneline --decorate -15
```

미커밋 파일이 없고 각 commit이 한 가지 책임만 갖는지 확인한다. 사용자에게 변경 파일, V029, 세 endpoint, 검증 결과, FE 주의사항과 기존 PR #402가 아직 이전 원격 HEAD임을 보고한다. 최종 승인 전에는 `git push`, PR 수정, merge, Issue close를 실행하지 않는다.
