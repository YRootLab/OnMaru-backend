# External Place VisitReview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FE가 전달한 대한민국 내 Kakao 장소를 Catalog-owned external-place registry에서 원자적으로 resolve-or-create하고 canonical 태그를 포함한 VisitReview를 생성한다.

**Architecture:** revision 기반 정식 Catalog와 분리된 `catalog_external_places`가 기존 `catalog_place_identity` 및 공개 ID mapping을 공유한다. Community는 Catalog의 `ExternalPlaceRegistry` port만 호출하고 production에서는 `JdbcTransactionRunner` 하나가 외부 장소 생성과 후기 insert를 같은 transaction으로 묶는다. OpenAPI가 FE/BE validation의 단일 계약이며 BE가 최종 권위를 가진다.

**Tech Stack:** Java 21, Spring Boot 3, PostgreSQL/PostGIS, JDBC, Flyway, JUnit 5, AssertJ, MockMvc, Testcontainers, OpenAPI 3.1

## Global Constraints

- 관련 Issue는 #643이고 PR 대상은 `develop`이다.
- 신규 API는 `POST /api/v1/visit-reviews`; 기존 place path API 의미는 바꾸지 않는다.
- MVP provider는 `KAKAO` 하나이며 백엔드는 Kakao API를 호출하지 않는다.
- 대한민국 1차 범위는 `32.0 <= lat <= 39.5`, `123.0 <= lng <= 132.0`이다.
- region 해석 실패는 `kr-unassigned`로 허용하고 별도 지표로 관찰한다.
- 공개 ID는 Kakao ID를 숨기는 `p-ext-{32 lowercase hex}`이다.
- MVP에서는 Kakao 장소와 기존 TourAPI 장소를 자동 병합하지 않는다. 서로 다른 canonical identity로 저장하고 alias/merge는 별도 Issue로 다룬다.
- 동일 외부 ID의 좌표가 저장 좌표에서 1,000m를 초과하면 `PLACE_IDENTITY_CONFLICT`로 거절하고 client assertion으로 기존 장소를 이동시키지 않는다.
- 태그는 최대 5개, canonical value 1~15 code points, 완성형 한글·영문·숫자와 단어 사이 공백만 허용한다.
- 선행 `#` 하나, NFC, trim, 연속 ASCII 공백 축소, 영문 소문자화만 허용 정규화다.
- invalid tag 하나라도 있으면 장소와 후기 모두 저장하지 않는다. 정규화 후 중복만 제거한다.
- 장소명·태그·좌표·외부 ID를 metric label이나 일반 오류 로그에 넣지 않는다.
- 요청량 제한은 기존 `AdmissionFilter`의 API rate limit과 `Retry-After` 계약을 재사용한다. 신규 장소 전용 quota는 운영 지표가 필요성을 입증하기 전에는 추가하지 않는다.
- schema 변경은 Flyway, `docs/database/schema.md`, `docs/database/schema.dbml`을 함께 갱신한다.

---

## File Structure

- `modules/catalog/.../externalplace/*`: provider, candidate/result record, validation policy, registry port와 typed exception.
- `modules/community/.../command/review/*`: 태그 정책, 외부 장소 후기 command, application transaction port.
- `adapters/persistence-jdbc/.../catalog/JdbcExternalPlaceRegistry.java`: identity/public ID/external snapshot 원자 저장.
- `apps/spring-api/.../web/review/*`: region adapter, bean wiring, 신규 endpoint와 error mapping.
- `V043__643_external_place_registry.sql`: external registry 실행 schema. 구현 중 `develop`의 Issue #640이 V042를 선점해 최신 병합 시 V043으로 재예약했다.
- `docs/contracts/openapi/visit-reviews.openapi.json`: FE/BE 단일 계약.

### Task 1: Canonical Tag Policy

**Files:**
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/command/review/VisitReviewTagPolicy.java`
- Create: `modules/community/src/test/java/com/yrootlab/onmaru/community/command/review/VisitReviewTagPolicyTests.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/command/review/VisitReviewWarmthInvalidException.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/command/review/VisitReviewCommandService.java`
- Modify: `modules/community/src/test/java/com/yrootlab/onmaru/community/command/review/VisitReviewCommandServiceTests.java`

**Interfaces:**
- Consumes: `List<String>` from both review creation paths.
- Produces: `List<String> VisitReviewTagPolicy.normalize(List<String>)`; error metadata `field`, `reason`, `index`.

- [ ] **Step 1: Write the failing tag tests**

```java
@Test
void canonicalizesDisplaySyntaxAndDuplicates() {
    assertThat(policy.normalize(List.of("  #힐링  ", "#야경   명소", "Healing", "힐링")))
            .containsExactly("힐링", "야경 명소", "healing");
}

@ParameterizedTest
@ValueSource(strings = {"####하이", "#하이.", "# 힐링", "#", "야경\n명소", "야경\t명소"})
void rejectsUnsupportedSyntax(String tag) {
    assertThatThrownBy(() -> policy.normalize(List.of(tag)))
            .isInstanceOfSatisfying(VisitReviewWarmthInvalidException.class, error -> {
                assertThat(error.field()).isEqualTo("tags");
                assertThat(error.reason()).isEqualTo("INVALID_TAG_FORMAT");
                assertThat(error.index()).isZero();
            });
}
```

같은 테스트 파일에서 `TOO_MANY_TAGS`, `TAG_REQUIRED`, `TAG_TOO_LONG`, NFC, 15/16 code point 경계도 고정한다.

- [ ] **Step 2: Run RED**

```bash
./gradlew :modules:community:test --tests '*VisitReviewTagPolicyTests' --no-daemon
```

Expected: 새 policy와 structured exception accessor가 없어 compilation failure.

- [ ] **Step 3: Implement the policy**

```java
public final class VisitReviewTagPolicy {
    private static final Pattern CANONICAL = Pattern.compile("[가-힣a-z0-9]+(?: [가-힣a-z0-9]+)*");

    public List<String> normalize(List<String> rawTags) {
        if (rawTags == null) return List.of();
        if (rawTags.size() > 5) throw error("TOO_MANY_TAGS", null);
        var result = new LinkedHashSet<String>();
        for (int index = 0; index < rawTags.size(); index++) {
            String raw = rawTags.get(index);
            if (raw == null) throw error("TAG_REQUIRED", index);
            String value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
            if (value.startsWith("#")) value = value.substring(1);
            value = value.replaceAll(" +", " ").toLowerCase(Locale.ROOT);
            if (value.isBlank()) throw error("TAG_REQUIRED", index);
            if (value.codePointCount(0, value.length()) > 15) throw error("TAG_TOO_LONG", index);
            if (!CANONICAL.matcher(value).matches()) throw error("INVALID_TAG_FORMAT", index);
            result.add(value);
        }
        return List.copyOf(result);
    }
}
```

탭과 줄바꿈은 축소하지 않는다. 기존 service의 private tag normalization은 이 policy 호출로 교체한다.

- [ ] **Step 4: Run GREEN**

```bash
./gradlew :modules:community:test --tests '*VisitReviewTagPolicyTests' --tests '*VisitReviewCommandServiceTests' --no-daemon
```

- [ ] **Step 5: Commit**

```bash
git add modules/community/src/main modules/community/src/test
git commit -m "feat(community): 온기 태그 정책을 정규화"
```

### Task 2: External Place Domain Boundary

**Files:**
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlaceProvider.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlaceCandidate.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlace.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlaceRegistry.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlaceRegionResolver.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlacePolicy.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlaceValidationException.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlaceIdentityConflictException.java`
- Create: `modules/catalog/src/test/java/com/yrootlab/onmaru/catalog/externalplace/ExternalPlacePolicyTests.java`

**Interfaces:**

```java
public enum ExternalPlaceProvider { KAKAO }
public record ExternalPlaceCandidate(ExternalPlaceProvider provider, String externalId,
        String name, double lat, double lng) {}
public record ExternalPlace(UUID placeId, String publicPlaceId, String name,
        String regionCode, double lat, double lng, boolean created) {}
public interface ExternalPlaceRegistry {
    ExternalPlace resolveOrCreate(ExternalPlaceCandidate candidate, String regionCode);
}
public interface ExternalPlaceRegionResolver {
    String resolve(ExternalPlaceCandidate candidate);
}
```

- [ ] **Step 1: Write policy tests**

필수값, external ID 128 code point 경계, name 100 code point 경계, non-finite, WGS84 범위, 대한민국 bbox 안/밖을 검사한다. bbox 밖은 `field=place.location`, `reason=OUTSIDE_SERVICE_AREA`여야 한다.

- [ ] **Step 2: Run RED**

```bash
./gradlew :modules:catalog:test --tests '*ExternalPlacePolicyTests' --no-daemon
```

- [ ] **Step 3: Implement validation**

`validate`는 NFC+trim 된 새 candidate를 반환한다. 일반 좌표 오류는 `INVALID_COORDINATE`, WGS84로는 유효하지만 bbox 밖이면 `OUTSIDE_SERVICE_AREA`, null/blank이면 `REQUIRED`를 사용한다.

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :modules:catalog:test --tests '*ExternalPlacePolicyTests' --no-daemon
git add modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/externalplace \
  modules/catalog/src/test/java/com/yrootlab/onmaru/catalog/externalplace
git commit -m "feat(catalog): 외부 장소 등록 경계를 정의"
```

### Task 3: External Place Registry Migration

**Files:**
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V043__643_external_place_registry.sql`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/DatabaseMigrationContractTests.java`
- Modify: `docs/database/schema.md`
- Modify: `docs/database/schema.dbml`

**Interfaces:**
- Consumes: `catalog_place_identity`, `catalog_place_public_ids`.
- Produces: one immutable snapshot per `(provider, external_id)`.

- [ ] **Step 1: Change latest-version assertions from `042` to `043` and run RED**

```bash
./gradlew :apps:spring-api:test --tests '*DatabaseMigrationContractTests' --no-daemon
```

- [ ] **Step 2: Add V043**

```sql
CREATE TABLE onmaru.catalog_external_places (
    provider varchar NOT NULL,
    external_id varchar(128) NOT NULL,
    place_id uuid NOT NULL UNIQUE REFERENCES onmaru.catalog_place_identity (id),
    public_place_id varchar NOT NULL UNIQUE REFERENCES onmaru.catalog_place_public_ids (public_id),
    name varchar(100) NOT NULL,
    region_code varchar NOT NULL,
    location geography(Point, 4326) NOT NULL,
    provenance varchar NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (provider, external_id),
    CONSTRAINT catalog_external_places_provider_ck CHECK (provider = 'KAKAO'),
    CONSTRAINT catalog_external_places_external_id_ck CHECK (btrim(external_id) <> ''),
    CONSTRAINT catalog_external_places_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT catalog_external_places_region_code_ck CHECK (
        region_code = 'kr-unassigned' OR region_code ~ '^kr-[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT catalog_external_places_provenance_ck CHECK (provenance = 'CLIENT_ASSERTED'),
    CONSTRAINT catalog_external_places_public_place_ck CHECK (public_place_id ~ '^p-ext-[a-f0-9]{32}$')
);
CREATE INDEX catalog_external_places_location_gix
    ON onmaru.catalog_external_places USING gist (location);
```

checksum header, Issue #643, migration reservation `043`을 포함한다.

- [ ] **Step 3: Update DBML and schema narrative**

registry는 Catalog-owned이지만 active dataset revision에는 포함되지 않으며 VisitReview backing identity로만 사용한다고 명시한다.

- [ ] **Step 4: Verify and commit**

```bash
node --test scripts/test/migration-policy.test.mjs
./gradlew :apps:spring-api:test --tests '*DatabaseMigrationContractTests' --tests '*FlywayMigrationBaselineTests' --no-daemon
bash scripts/verify-contracts
git add apps/spring-api/src/main/resources/db/migration/baseline/V043__643_external_place_registry.sql \
  apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/DatabaseMigrationContractTests.java \
  docs/database/schema.md docs/database/schema.dbml
git commit -m "feat(db): 외부 장소 registry 스키마 추가"
```

### Task 4: Transactional JDBC Registry

**Files:**
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/catalog/JdbcExternalPlaceRegistry.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcExternalPlaceRegistryTests.java`

**Interfaces:**
- Consumes: `ExternalPlacePolicy`, `JdbcTransactionRunner`, `Supplier<UUID>`.
- Produces: `ExternalPlace resolveOrCreate(...)`.

- [ ] **Step 1: Write PostgreSQL tests**

신규 3-table insert, retry snapshot 재사용, opaque public ID, concurrent convergence, 1,000m 초과 conflict, 1,000m 이내 stored snapshot 우선, outer transaction rollback을 검증한다.

- [ ] **Step 2: Run RED**

```bash
./gradlew :apps:spring-api:test --tests '*JdbcExternalPlaceRegistryTests' --no-daemon
```

- [ ] **Step 3: Implement on the shared connection**

adapter는 `transactions.execute(connection -> ...)`만 사용한다. transaction 시작 직후 `pg_advisory_xact_lock(hashtextextended(provider || ':' || externalId, 0))`를 획득한 뒤 기존 row를 재조회한다. 이 key별 transaction lock으로 동시 최초 등록을 직렬화해 orphan identity/public ID를 만들지 않는다. 기존 row 조회에는 PostGIS `ST_Distance`를 포함한다. 신규 UUID 하나로 identity를 만들고 공개 ID는 `p-ext-`와 hyphen 없는 UUID로 만든다. advisory lock 이후에도 발생한 다른 unique conflict는 데이터 무결성 오류로 실패시킨다.

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :apps:spring-api:test --tests '*JdbcExternalPlaceRegistryTests' --no-daemon
git add adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/catalog/JdbcExternalPlaceRegistry.java \
  apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcExternalPlaceRegistryTests.java
git commit -m "feat(catalog): 외부 장소를 원자적으로 등록"
```

### Task 5: External Review Command and Transaction Port

**Files:**
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/command/review/CreateExternalPlaceVisitReviewCommand.java`
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/command/review/VisitReviewTransaction.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/command/review/VisitReviewCommandService.java`
- Modify: `modules/community/src/test/java/com/yrootlab/onmaru/community/command/review/VisitReviewCommandServiceTests.java`

**Interfaces:**

```java
public record CreateExternalPlaceVisitReviewCommand(ExternalPlaceCandidate place,
        String text, String mood, Integer score, List<String> tags) {}
@FunctionalInterface
public interface VisitReviewTransaction {
    <T> T execute(Supplier<T> operation);
}
```

`VisitReviewCommandService`는 `ExternalPlacePolicy`, `ExternalPlaceRegistry`, `ExternalPlaceRegionResolver`를 constructor로 받는다. policy가 반환한 normalized candidate만 region resolver와 registry에 전달한다.

- [ ] **Step 1: Write service tests**

후기 validation이 registry mutation보다 먼저 수행되는지, resolved place snapshot으로 review가 만들어지는지, `kr-unassigned`, registry/store 실패, 기존 internal-place create 회귀를 검증한다.

- [ ] **Step 2: Run RED**

```bash
./gradlew :modules:community:test --tests '*VisitReviewCommandServiceTests' --no-daemon
```

- [ ] **Step 3: Implement `createExternal`**

text/mood/score/tags와 `ExternalPlacePolicy`의 candidate를 transaction 전에 canonicalize한다. transaction 안에서 `regionResolver.resolve(candidate)`, registry resolve-or-create, `store.add`를 호출한다. 공통 projection 생성 private method를 추출하되 기존 API의 의미와 응답은 유지한다. Community는 JDBC와 Spring을 import하지 않는다.

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :modules:community:test --tests '*VisitReviewCommandServiceTests' --no-daemon
git add modules/community/src/main modules/community/src/test
git commit -m "feat(community): 외부 장소 온기 생성 command 추가"
```

### Task 6: Region Resolution and Spring Wiring

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/command/CatalogExternalPlaceRegionResolver.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review/command/CatalogExternalPlaceRegionResolverTests.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/query/VisitReviewQueryConfiguration.java`

**Interfaces:**
- Produces: SIGUNGU 우선 canonical region 또는 `kr-unassigned`; production JDBC registry/transaction beans; 동등한 non-production in-memory registry.

- [ ] **Step 1: Write resolver tests**

SIDO+SIGUNGU면 SIGUNGU, SIDO만 있으면 SIDO, 결과 없음이면 `kr-unassigned`를 검증한다.

- [ ] **Step 2: Run RED**

```bash
./gradlew :apps:spring-api:test --tests '*CatalogExternalPlaceRegionResolverTests' --no-daemon
```

- [ ] **Step 3: Implement beans**

production transaction adapter는 `transactions.execute(ignored -> operation.get())`를 호출한다. non-production registry도 `(provider, externalId)` unique, stored snapshot 우선, opaque ID, 1,000m conflict를 동일하게 구현해 MockMvc가 약한 정책을 테스트하지 않게 한다.

- [ ] **Step 4: Verify Spring context and commit**

```bash
./gradlew :apps:spring-api:test --tests '*CatalogExternalPlaceRegionResolverTests' --tests '*VisitReviewCommandWebBoundaryTests' --no-daemon
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review \
  apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review
git commit -m "feat(api): 외부 장소 registry 구성을 연결"
```

### Task 7: New Endpoint and Typed Errors

**Files:**
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/command/VisitReviewCommandController.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review/command/VisitReviewCommandWebBoundaryTests.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/common/error/ApiErrorCode.java` if codes are enum-bound.

**Interfaces:**
- Produces: existing `VisitReview`, `400 VALIDATION_ERROR`, `422 PLACE_OUTSIDE_SERVICE_AREA`, `409 PLACE_IDENTITY_CONFLICT`.

- [ ] **Step 1: Write MockMvc tests**

정상 201/opaque place ID/canonical tags, semantic idempotency replay, invalid tag field+reason, six tags, Tokyo 422, missing external ID, 1,000m conflict, unauthenticated 401을 각각 검증한다.

- [ ] **Step 2: Run RED**

```bash
./gradlew :apps:spring-api:test --tests '*VisitReviewCommandWebBoundaryTests' --no-daemon
```

- [ ] **Step 3: Add nested request DTOs and endpoint**

```java
record CreateExternalPlaceReviewRequest(ExternalPlaceRequest place, String text,
        String mood, Integer score, List<String> tags) {}
record ExternalPlaceRequest(String provider, String externalId, String name,
        Double lat, Double lng) {}
```

boxed coordinate null은 primitive record 생성 전에 typed validation error로 변환한다. idempotency fingerprint는 canonical command를 사용해 `#힐링`과 `힐링`을 같은 의미로 처리한다.

- [ ] **Step 4: Map errors without raw input**

`details`는 `field`, `reason`, 선택적 `index`만 포함한다. text 오류는 기존 호환성을 위해 400을 유지한다. outside service area message는 `현재 대한민국 내 장소만 온기를 남길 수 있습니다.`로 고정한다.

기존 `AdmissionFilter`가 신규 `/api/v1/visit-reviews` POST에도 적용되고 429에서 `Retry-After`를 반환하는 회귀 테스트를 추가한다. 별도 in-controller limiter는 만들지 않는다.

- [ ] **Step 5: Run GREEN and commit**

```bash
./gradlew :apps:spring-api:test --tests '*VisitReviewCommandWebBoundaryTests' --no-daemon
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/command \
  apps/spring-api/src/main/java/com/yrootlab/onmaru/web/common/error \
  apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review/command
git commit -m "feat(api): Kakao 장소 온기 생성 API 추가"
```

### Task 8: PostgreSQL End-to-End Atomicity

**Files:**
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/ExternalPlaceVisitReviewTransactionTests.java`
- Modify: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/community/JdbcVisitReviewStore.java` only if a test proves a transaction escape.

- [ ] **Step 1: Write integration tests**

실제 member로 성공 시 identity/public ID/external place/review 네 row를 확인한다. invalid member FK로 review insert를 실패시킨 뒤 place 관련 세 row가 모두 rollback되는지 확인한다.

- [ ] **Step 2: Run the test**

```bash
./gradlew :apps:spring-api:test --tests '*ExternalPlaceVisitReviewTransactionTests' --no-daemon
```

Expected: shared runner가 정확하면 PASS, 아니면 별도 connection을 연 adapter가 드러나는 FAIL.

- [ ] **Step 3: Fix only demonstrated escapes and verify**

별도 connection이 확인된 adapter만 injected runner로 전환한다. 관련 없는 JDBC store는 수정하지 않는다.

```bash
./gradlew :apps:spring-api:test --tests '*ExternalPlaceVisitReviewTransactionTests' \
  --tests '*JdbcVisitReviewStoreTests' --tests '*JdbcExternalPlaceRegistryTests' --no-daemon
git add apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres \
  adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/community/JdbcVisitReviewStore.java
git commit -m "test(api): 외부 장소와 온기 저장 원자성 검증"
```

### Task 9: OpenAPI, Fixtures, and FE Handoff

**Files:**
- Modify: `docs/contracts/openapi/visit-reviews.openapi.json`
- Create: `docs/contracts/fixtures/visit-review/create-external-place-normal.json`
- Create: `docs/contracts/fixtures/visit-review/create-external-place-invalid-tag.json`
- Create: `docs/toFE/external-place-visit-review-contract.md`
- Modify: relevant contract validation test that enumerates fixtures.

- [ ] **Step 1: Add failing contract assertions**

`POST /visit-reviews`, operation ID `createExternalPlaceVisitReview`, external input, canonical Tag, headers, 201/400/401/409/422/429/503을 요구한다.

- [ ] **Step 2: Run RED**

```bash
bash scripts/verify-contracts
```

- [ ] **Step 3: Repair existing schema drift and add the new contract**

실제 controller가 받는 mood/score/tags가 현재 text-only `CreateReview`와 일치하도록 중복 creation schema를 정리한다. Tag에는 `maxLength: 15`, array에는 `maxItems: 5`, `uniqueItems: true`, canonical pattern을 둔다. description에 선행 `#` 호환 정규화를 기록한다.

- [ ] **Step 4: Add fixtures and FE handoff**

정상 fixture 요청은 `#힐링`, 응답은 `힐링`을 보여준다. invalid fixture는 raw tag를 echo하지 않고 `tags[1]`과 `INVALID_TAG_FORMAT`만 제공한다. FE 문서에는 form-time 문구, reason mapping, 400/409/422 무재시도, 429/503 `Retry-After`, staging feature flag와 draft 보존 fallback을 기록한다.

- [ ] **Step 5: Run GREEN and commit**

```bash
bash scripts/verify-contracts
python3 -m unittest scripts.test.test_contract_validation
git add docs/contracts docs/toFE/external-place-visit-review-contract.md scripts/test
git commit -m "docs(api): 외부 장소 온기 FE 계약 동결"
```

### Task 10: Full Verification and PR Reconciliation

**Files:**
- Modify: `handoff.md`
- Modify: `improvements.md` only for genuinely untriaged follow-ups.

- [ ] **Step 1: Run focused and full tests**

```bash
./gradlew :modules:catalog:test :modules:community:test --no-daemon
./gradlew :apps:spring-api:test --tests '*VisitReview*' --tests '*ExternalPlace*' \
  --tests '*DatabaseMigrationContractTests' --tests '*FlywayMigrationBaselineTests' --no-daemon
bash scripts/verify-contracts
node --test scripts/test/migration-policy.test.mjs
./gradlew check --no-daemon
git diff --check
```

- [ ] **Step 2: Audit sensitive/high-cardinality output**

```bash
rg -n 'tag\(|metric|logger|log\.' apps/spring-api adapters modules | rg 'externalId|placeName|lat|lng|tags'
git diff --stat origin/develop...HEAD
```

Expected: raw external ID, place name, tags, coordinates가 metric label이나 routine log에 없다.

- [ ] **Step 3: Reconcile Issue and work logs**

`node scripts/print-branch-issue.mjs`가 `643`을 출력하는지 확인한다. `handoff.md`에 touched files, 정확한 검증 결과, FE staging rollout과 남은 운영 위험을 기록한다.

- [ ] **Step 4: Commit reconciliation if changed**

```bash
git add handoff.md improvements.md
git commit -m "docs(api): 외부 장소 온기 검증 결과 기록"
```

- [ ] **Step 5: Prepare PR without merging**

PR은 `develop` 대상이다. staging FE 연동까지 Issue 완료 조건이고 아직 검증하지 않았다면 `Refs #643`을 사용한다. PR 본문에는 migration 043, tag normalization 표, 400/409/422 fixture, 동시 등록 수렴, atomic rollback, 전체 검증 명령과 결과를 포함한다.
