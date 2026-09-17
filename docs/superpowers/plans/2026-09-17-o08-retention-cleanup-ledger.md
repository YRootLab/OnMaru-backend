# O08 Retention Cleanup Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TTL 경과 데이터, revision GC, 회원 탈퇴 cleanup, deletion ledger replay를 재시작 안전한 배치 작업으로 구현한다.

**Architecture:** `modules/operations`에 retention 도메인 서비스와 store 포트를 두고, `adapters/persistence-jdbc`에서 PostgreSQL cleanup 쿼리를 구현한다. Spring app은 scheduling package에 job bean과 관측성 adapter만 연결한다.

**Tech Stack:** Java 21, Spring Boot 3, PostgreSQL/Flyway, JUnit 5, AssertJ, Testcontainers.

## Global Constraints

- GitHub Issue #125 acceptance criteria: TTL 경계와 batch 재시작 안전.
- GitHub Issue #125 acceptance criteria: 탈퇴/삭제 resource가 restore/late event 후 재노출되지 않음.
- Verification method: fake clock DB integration tests.
- Active revision과 참조 saved journey는 GC에서 보호.
- 관측성은 structured logging/telemetry event에 dataset, job, deleted count, ledger count를 남긴다.

---

### Task 1: Retention Domain Service

**Files:**
- Create: `modules/operations/src/main/java/com/yrootlab/onmaru/operations/retention/RetentionCleanupService.java`
- Create: `modules/operations/src/main/java/com/yrootlab/onmaru/operations/retention/RetentionCleanupStore.java`
- Create: `modules/operations/src/main/java/com/yrootlab/onmaru/operations/retention/RetentionCleanupPolicy.java`
- Create: `modules/operations/src/main/java/com/yrootlab/onmaru/operations/retention/RetentionCleanupResult.java`
- Create: `modules/operations/src/main/java/com/yrootlab/onmaru/operations/retention/RetentionCleanupObserver.java`
- Test: `modules/operations/src/test/java/com/yrootlab/onmaru/operations/retention/RetentionCleanupServiceTests.java`

**Interfaces:**
- Produces: `RetentionCleanupService.runOnce(RetentionCleanupPolicy policy)` returns `RetentionCleanupResult`.
- Produces: `RetentionCleanupStore.cleanup(RetentionCleanupPolicy policy, Instant now)` performs one bounded cleanup batch.

- [ ] **Step 1: Write the failing test**

```java
@Test
void passesFakeClockAndRecordsObserverEvent() {
    var now = Instant.parse("2026-09-17T00:00:00Z");
    var store = new CapturingStore(new RetentionCleanupResult(3, 2, 1, 1, 7));
    var observer = new CapturingObserver();
    var service = new RetentionCleanupService(store, Clock.fixed(now, ZoneOffset.UTC), observer);
    var policy = RetentionCleanupPolicy.defaults();

    var result = service.runOnce(policy);

    assertThat(store.observedNow).isEqualTo(now);
    assertThat(result.ledgerEntries()).isEqualTo(7);
    assertThat(observer.events).containsExactly(result);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:operations:test --tests '*RetentionCleanupServiceTests'`
Expected: FAIL because retention classes do not exist.

- [ ] **Step 3: Write minimal implementation**

Create immutable records and a service that validates policy, calls the store with `clock.instant()`, and calls the observer exactly once after a successful cleanup.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:operations:test --tests '*RetentionCleanupServiceTests'`
Expected: PASS.

### Task 2: Retention Migration Contract

**Files:**
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V015__o08_retention_cleanup_ledger.sql`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/RetentionCleanupMigrationTests.java`

**Interfaces:**
- Produces: `onmaru.operations_retention_deletion_ledger` with unique `(resource_type, resource_id, reason)`.
- Produces: indexes for expired guest/session/run/proposal cleanup and inactive revision GC candidates.

- [ ] **Step 1: Write the failing migration test**

```java
@Test
void migratesRetentionCleanupLedgerSchema() throws Exception {
    resetAndMigrate();
    try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
         var statement = connection.createStatement()) {
        assertThat(countRows(statement, """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'onmaru'
                  AND table_name = 'operations_retention_deletion_ledger'
                """)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :apps:spring-api:test --tests '*RetentionCleanupMigrationTests'`
Expected: FAIL because migration V015 does not exist.

- [ ] **Step 3: Write minimal migration**

Add ledger table, retention indexes, and migration reservation for version `015`, issue `125`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :apps:spring-api:test --tests '*RetentionCleanupMigrationTests'`
Expected: PASS.

### Task 3: JDBC Cleanup Store

**Files:**
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/operations/retention/JdbcRetentionCleanupStore.java`
- Modify: `adapters/persistence-jdbc/build.gradle.kts`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/RetentionCleanupJdbcTests.java`

**Interfaces:**
- Consumes: `RetentionCleanupStore.cleanup(RetentionCleanupPolicy policy, Instant now)`.
- Produces: idempotent cleanup that deletes expired sessions/guests/runs/proposals and inactive unreferenced revisions in policy-sized batches while inserting replayable ledger rows.

- [ ] **Step 1: Write failing DB integration tests**

```java
@Test
void cleanupIsBatchBoundedAndRestartSafeAtTtlBoundary() throws Exception {
    // Insert expired guest/session/proposal/run fixtures at and before the cutoff.
    // Run store.cleanup(policy.withBatchSize(1), now) twice.
    // Assert each run deletes at most one row per category and ledger rows are unique.
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :apps:spring-api:test --tests '*RetentionCleanupJdbcTests'`
Expected: FAIL because JDBC cleanup store does not exist.

- [ ] **Step 3: Write minimal JDBC implementation**

Use transaction-scoped `DELETE ... WHERE id IN (SELECT ... LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING ...`, insert ledger rows with `ON CONFLICT DO NOTHING`, protect active dataset revisions and saved journey source explorations.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :apps:spring-api:test --tests '*RetentionCleanupJdbcTests'`
Expected: PASS.

### Task 4: Spring Wiring And Observability

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/scheduling/retention/RetentionCleanupConfiguration.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/scheduling/retention/RetentionCleanupJob.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/scheduling/retention/TelemetryRetentionCleanupObserver.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/persistence/operations/AdmissionPersistenceConfiguration.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/scheduling/retention/RetentionCleanupConfigurationTests.java`

**Interfaces:**
- Consumes: `RetentionCleanupService.runOnce(policy)`.
- Produces: telemetry event `operations.retention.cleanup.completed` with deleted counts and ledger count.

- [ ] **Step 1: Write failing configuration/observer test**

```java
@Test
void observerRecordsStructuredTelemetryWithoutResourceIds() {
    var sink = new InMemoryTelemetrySink();
    var observer = new TelemetryRetentionCleanupObserver(sink);
    observer.record(new RetentionCleanupResult(1, 2, 3, 4, 10));
    assertThat(sink.events().getFirst().attributes()).containsEntry("ledger_entries", "10");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :apps:spring-api:test --tests '*RetentionCleanupConfigurationTests'`
Expected: FAIL because Spring wiring does not exist.

- [ ] **Step 3: Write minimal wiring**

Register JDBC store when `DataSource` exists, register service and job, and log cleanup failures without swallowing observability for successful runs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :apps:spring-api:test --tests '*RetentionCleanupConfigurationTests'`
Expected: PASS.

### Task 5: Verification

**Files:**
- Modify: `handoff.md`

- [ ] **Step 1: Run focused tests**

Run: `./gradlew :modules:operations:test :apps:spring-api:test --tests '*RetentionCleanup*'`
Expected: PASS.

- [ ] **Step 2: Run repository baseline**

Run: `./gradlew test`
Expected: PASS or document any unrelated pre-existing failure.

- [ ] **Step 3: Update handoff**

Record branch, issue #125, touched files, verification commands, and any residual risk.
