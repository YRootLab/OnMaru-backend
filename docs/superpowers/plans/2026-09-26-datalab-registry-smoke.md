# DataLab Registry And Staging Smoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DataLab 지역 mapping을 감사 가능한 fail-closed registry로 만들고 staging에서 실제 수집·revision·API projection을 자동 검증한다.

**Architecture:** 기존 Catalog source-code row를 identity로 유지하고 DataLab 전용 registry metadata를 FK 확장으로 저장한다. typed registry lookup과 구조화된 collection result가 ACTIVE mapping만 전국 응답에 결박하며, Spring 운영 endpoint와 GitHub workflow가 secret을 노출하지 않고 동일 경로를 smoke 검증한다.

**Tech Stack:** Java 21, Spring Boot 3, JDBC, PostgreSQL 17/PostGIS, Flyway, Micrometer, JUnit 5/AssertJ/Testcontainers, Node.js 22, GitHub Actions

## Global Constraints

- 공식 문서 또는 인증된 실제 응답 근거가 없는 mapping은 `ACTIVE`가 될 수 없다.
- 결측 방문자 수는 `null + NOT_AVAILABLE`이며 `0`으로 합성하지 않는다.
- region code, provider payload, source URL, credential을 metric label이나 smoke artifact에 기록하지 않는다.
- 실패한 batch는 `kto-datalab-visitor` active revision을 변경하지 않는다.
- Issue #392는 실제 staging smoke 성공 전까지 닫지 않는다.

---

### Task 1: PostgreSQL DataLab Registry Contract

**Files:**
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V027__399_datalab_region_mapping_registry.sql`
- Modify: `db/migration/registry/migrations.json`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/CatalogMigrationTests.java`
- Modify: `docs/database/schema.md`

**Interfaces:**
- Consumes: `catalog_regions`, `catalog_region_source_codes`, `catalog_region_source_code_verifications`
- Produces: `catalog_datalab_region_mappings` keyed by `region_id`, unique `source_code`, status enum and validated provenance

- [ ] **Step 1: Write failing migration integration tests**

Add tests that insert literal SIDO/SIGUNGU fixtures and assert rejection of duplicate region, duplicate source code, mismatched level/parent, malformed URL, and incomplete ACTIVE provenance. Assert the four V025 official mappings become ACTIVE with the documented source URL and timestamps.

```java
assertThatThrownBy(() -> statement.execute(activeMappingSql(sigunguWithoutSidoParent)))
        .hasMessageContaining("catalog_datalab_region_mapping_structure");
assertThat(countRows(statement, """
        SELECT COUNT(*) FROM onmaru.catalog_datalab_region_mappings
        WHERE status = 'ACTIVE' AND source_url = 'https://www.data.go.kr/data/15101972/openapi.do'
        """)).isEqualTo(4);
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :apps:spring-api:test --tests com.yrootlab.onmaru.testing.postgres.CatalogMigrationTests --no-daemon --max-workers=1`

Expected: FAIL because `catalog_datalab_region_mappings` does not exist.

- [ ] **Step 3: Add V027 schema, validation trigger, backfill, and registry metadata**

Create the status enum and FK extension table. The validation function must derive Catalog level/parent from referenced rows and reject ACTIVE rows without all provenance. Update V025's deferred registration function so later Catalog imports also populate the registry. Register V027 with its actual SHA-256.

```sql
CREATE TYPE onmaru.datalab_region_mapping_status AS ENUM ('PENDING', 'ACTIVE', 'REJECTED');
CREATE TABLE onmaru.catalog_datalab_region_mappings (
    provider varchar NOT NULL DEFAULT 'KTO_DATALAB' CHECK (provider = 'KTO_DATALAB'),
    dataset varchar NOT NULL DEFAULT 'visitor' CHECK (dataset = 'visitor'),
    source_code varchar NOT NULL,
    valid_from date NOT NULL,
    region_id uuid NOT NULL UNIQUE REFERENCES onmaru.catalog_regions(id),
    level onmaru.catalog_region_level NOT NULL,
    name varchar NOT NULL,
    source_url text,
    source_observed_at timestamptz,
    verified_by varchar,
    verified_at timestamptz,
    status onmaru.datalab_region_mapping_status NOT NULL,
    PRIMARY KEY (provider, dataset, source_code, valid_from),
    FOREIGN KEY (provider, dataset, source_code, valid_from)
        REFERENCES onmaru.catalog_region_source_codes(provider, dataset, source_code, valid_from)
);
```

- [ ] **Step 4: Run migration and policy tests GREEN**

Run: `./gradlew :apps:spring-api:test --tests com.yrootlab.onmaru.testing.postgres.CatalogMigrationTests --no-daemon --max-workers=1 && node --test scripts/test/migration-policy.test.mjs`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/spring-api/src/main/resources/db/migration/baseline/V027__399_datalab_region_mapping_registry.sql apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/CatalogMigrationTests.java db/migration/registry/migrations.json docs/database/schema.md
git commit -m "feat(insights): DataLab mapping registry 추가"
```

### Task 2: Typed Registry Lookup And Fail-Closed Collection

**Files:**
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/region/DataLabRegionMapping.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/region/DataLabRegionMappingStatus.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/region/DataLabRegionMappingRegistry.java`
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/catalog/JdbcDataLabRegionMappingRegistry.java`
- Delete: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/region/CatalogRegionSourceCode.java`
- Delete: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/region/CatalogRegionSourceCodeLookup.java`
- Delete: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/catalog/JdbcCatalogRegionSourceCodeLookup.java`
- Create: `modules/insights/src/main/java/com/yrootlab/onmaru/insights/ingestion/DataLabCollectionReason.java`
- Create: `modules/insights/src/main/java/com/yrootlab/onmaru/insights/ingestion/DataLabCollectionExclusion.java`
- Create: `modules/insights/src/main/java/com/yrootlab/onmaru/insights/ingestion/DataLabVisitorFetchResult.java`
- Modify: `modules/insights/src/main/java/com/yrootlab/onmaru/insights/ingestion/DataLabVisitorSource.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights/DataLabVisitorSourceAdapter.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/insights/DataLabVisitorSourceAdapterTests.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcDataLabVisitorSnapshotPublisherTests.java`

**Interfaces:**
- Produces: `List<DataLabRegionMapping> findCurrent(LocalDate asOf)`
- Produces: `DataLabVisitorFetchResult fetchDailyVisitorObservations()` with observations, exclusions, and quarantine flag

- [ ] **Step 1: Write failing registry and adapter tests**

Tests must prove PENDING/REJECTED-only registries make zero fetch calls, ACTIVE nullable values remain NOT_AVAILABLE, duplicate/scope-mismatched/missing ACTIVE responses quarantine the whole batch, and unknown nationwide rows do not become observations.

```java
var result = source.fetchDailyVisitorObservations();
assertThat(fetchCalls).isZero();
assertThat(result.exclusions()).extracting(DataLabCollectionExclusion::reason)
        .containsExactly(PENDING_MAPPING, REJECTED_MAPPING);
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew :apps:spring-api:test --tests '*DataLabVisitorSourceAdapterTests' --tests '*JdbcDataLabVisitorSnapshotPublisherTests' --no-daemon --max-workers=1`

Expected: compilation/test failure because typed registry/result contracts do not exist.

- [ ] **Step 3: Implement typed lookup and collection result**

The JDBC query joins generic mapping, DataLab registry, and Catalog region, returning all current registry statuses. The adapter filters ACTIVE mappings, binds matched provider codes only, rejects duplicate matched records, and returns a quarantined result instead of publishable observations for any active contract violation.

```java
public record DataLabVisitorFetchResult(
        List<VisitorObservation> observations,
        List<DataLabCollectionExclusion> exclusions,
        boolean quarantined) {
    public boolean publishable() {
        return !quarantined && !observations.isEmpty();
    }
}
```

- [ ] **Step 4: Run focused tests GREEN**

Run: `./gradlew :modules:catalog:test :modules:insights:test :apps:spring-api:test --tests '*DataLabVisitorSourceAdapterTests' --tests '*JdbcDataLabVisitorSnapshotPublisherTests' --no-daemon --max-workers=1`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/catalog modules/insights adapters/persistence-jdbc apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights apps/spring-api/src/test/java/com/yrootlab/onmaru/web/insights apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcDataLabVisitorSnapshotPublisherTests.java
git commit -m "feat(insights): DataLab 수집을 fail-closed registry로 전환"
```

### Task 3: Publish Decision And Low-Cardinality Metrics

**Files:**
- Create: `modules/insights/src/main/java/com/yrootlab/onmaru/insights/ingestion/DataLabCollectionObserver.java`
- Create: `modules/insights/src/main/java/com/yrootlab/onmaru/insights/ingestion/DataLabVisitorSyncResult.java`
- Modify: `modules/insights/src/main/java/com/yrootlab/onmaru/insights/ingestion/DataLabVisitorIngestionService.java`
- Modify: `modules/insights/src/test/java/com/yrootlab/onmaru/insights/ingestion/DataLabVisitorIngestionServiceTests.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights/MicrometerDataLabCollectionObserver.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights/InsightsConfiguration.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/insights/InsightsProductionConfigurationTests.java`

**Interfaces:**
- Produces: `DataLabVisitorSyncResult sync()`
- Produces metric: `onmaru.datalab.collection` tagged only with `outcome` and enum `reason`

- [ ] **Step 1: Write failing publish-decision and meter tests**

Assert publish occurs only for `publishable()`, skipped/quarantined results preserve the fake writer's previous observations, and `SimpleMeterRegistry` contains only bounded outcome/reason tags.

```java
assertThat(service.sync().published()).isFalse();
assertThat(writer.replaceCalls).isZero();
assertThat(registry.find("onmaru.datalab.collection")
        .tags("outcome", "skipped", "reason", "pending_mapping").counter().count()).isEqualTo(1.0);
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :modules:insights:test :apps:spring-api:test --tests '*InsightsProductionConfigurationTests' --no-daemon --max-workers=1`

Expected: FAIL because sync results and observer are missing.

- [ ] **Step 3: Implement publish gate and observer**

`sync()` records every structured exclusion, never invokes the writer for skipped/quarantined batches, and returns counts suitable for a sanitized operations response. Configure Micrometer when available and NOOP otherwise.

- [ ] **Step 4: Run tests GREEN**

Run: `./gradlew :modules:insights:test :apps:spring-api:test --tests '*InsightsProductionConfigurationTests' --no-daemon --max-workers=1`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/insights apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights apps/spring-api/src/test/java/com/yrootlab/onmaru/web/insights
git commit -m "feat(observability): DataLab 수집 결과와 metric 기록"
```

### Task 4: Protected Staging Sync Endpoint

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights/DataLabOperationsAuthenticator.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights/DataLabOperationsController.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights/DataLabOperationsResponse.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/OnMaruSecretProperties.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/config/secrets/SecretConfigurationTests.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/insights/DataLabOperationsControllerTests.java`
- Modify: `docs/contracts/rest-api.md`

**Interfaces:**
- Consumes: `Authorization: Bearer <datalab.operations-token>`
- Produces: `POST /api/v1/operations/datalab/visitor-sync` with `{published, observationCount, skippedCount, quarantinedCount, reasons}`

- [ ] **Step 1: Write failing authentication and endpoint tests**

Assert missing/wrong tokens return 401 without invoking sync, current/previous rotating tokens are accepted, concurrent request returns 409, response has no source URL/code/token, and the controller is production-only.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :apps:spring-api:test --tests '*DataLabOperationsControllerTests' --tests '*SecretConfigurationTests' --no-daemon --max-workers=1`

Expected: FAIL because the endpoint and required secret do not exist.

- [ ] **Step 3: Implement constant-time auth, lock, and sanitized response**

Use `SecretBundle.matches`, an `AtomicBoolean.compareAndSet(false, true)` guard released in `finally`, and no request body or provider override. Add `datalab.operations-token` to production required secret names.

- [ ] **Step 4: Run tests GREEN**

Run: `./gradlew :apps:spring-api:test --tests '*DataLabOperationsControllerTests' --tests '*SecretConfigurationTests' --no-daemon --max-workers=1`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/web/insights apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets apps/spring-api/src/test/java/com/yrootlab/onmaru/web/insights apps/spring-api/src/test/java/com/yrootlab/onmaru/config/secrets docs/contracts/rest-api.md
git commit -m "feat(operations): DataLab staging 수집 명령 보호"
```

### Task 5: Automated Staging Smoke Evidence

**Files:**
- Create: `scripts/datalab-staging-smoke.mjs`
- Create: `scripts/test/datalab-staging-smoke.test.mjs`
- Create: `.github/workflows/datalab-staging-smoke.yml`
- Modify: `docs/operations/datalab-region-source-codes.md`
- Modify: `docs/operations/README.md`
- Modify: `handoff.md`

**Interfaces:**
- Consumes env names: `STAGING_SPRING_URL`, `ONMARU_DATALAB_OPERATIONS_TOKEN`, `ONMARU_STAGING_READONLY_DB_URL`
- Produces: sanitized JSON evidence containing workflow/run SHA, timestamps, status counts, revision IDs, observation count, coverage counts, and API status

- [ ] **Step 1: Write failing smoke script tests**

Test pure validation/parsing exports with literal fixtures: missing configuration fails naming only missing env keys; ACTIVE count below one fails; no COMPLETE observation fails; changed active revision after a simulated failed sync fails; output recursively excludes keys matching `token|secret|password|sourceUrl|payload`.

```javascript
assert.throws(() => validateConfig({}), /STAGING_SPRING_URL/);
assert.deepEqual(sanitize({ revisionId: 'r1', token: 'hidden' }), { revisionId: 'r1' });
```

- [ ] **Step 2: Run Node test and verify RED**

Run: `node --test scripts/test/datalab-staging-smoke.test.mjs`

Expected: FAIL because the script does not exist.

- [ ] **Step 3: Implement script, workflow, runbook, and handoff**

The workflow uses the `staging` environment, masks the token, applies concurrency, calls the protected endpoint, executes fixed read-only SQL through `psql`, validates public endpoints, uploads only sanitized JSON, and comments on #392 with run URL and pass/fail without secret values.

```yaml
concurrency:
  group: datalab-staging-smoke
  cancel-in-progress: false
permissions:
  contents: read
  issues: write
```

- [ ] **Step 4: Run smoke contract tests GREEN**

Run: `node --test scripts/test/datalab-staging-smoke.test.mjs scripts/test/deploy-pipeline.test.mjs && node scripts/print-branch-issue.mjs`

Expected: PASS and branch parser prints `399`.

- [ ] **Step 5: Commit**

```bash
git add scripts/datalab-staging-smoke.mjs scripts/test/datalab-staging-smoke.test.mjs .github/workflows/datalab-staging-smoke.yml docs/operations/datalab-region-source-codes.md docs/operations/README.md handoff.md
git commit -m "feat(operations): DataLab staging smoke 자동화"
```

### Task 6: Full Verification And Issue Evidence

**Files:**
- Modify only if verification exposes defects or stale generated artifacts.

**Interfaces:**
- Consumes: all implementation tasks
- Produces: reproducible verification evidence and accurate #392/#399 status

- [ ] **Step 1: Run repository verification**

```bash
./gradlew test --no-daemon --max-workers=1
node --test scripts/test/*.test.mjs
python3 -m pytest scripts/test/test_contract_validation.py
bash scripts/verify-contracts
git diff --check
```

- [ ] **Step 2: Reconcile acceptance criteria**

Read #392 and #399 line by line. Record registry counts, ACTIVE evidence URLs, excluded reasons, sensitive-data confirmation, test commands, and the fact that CI Toolkit rollout was untouched. Do not claim #392 complete unless the real staging workflow succeeds.

- [ ] **Step 3: Prepare PR and issue comments**

Run work-log cleanup, push `feature/399-datalab-registry-smoke`, and open a Korean PR into `develop` with `Closes #399` and `Refs #392`. Comment on #392 with the workflow path and current staging preflight state; close it only after a successful real run.
