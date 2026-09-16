# Durable Run State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PostgreSQL을 source of truth로 사용하는 Journey run 상태 머신과 durable command idempotency를 구현한다.

**Architecture:** Journey module은 상태 전이와 port를 정의하고 persistence-jdbc adapter가 command lock, CAS, immutable receipt를 단일 transaction으로 수행한다. V006 run schema는 유지하고 V009 command receipt schema를 forward migration으로 추가한다.

**Tech Stack:** Java 21, PostgreSQL 17, JDBC, Flyway, JUnit 5, Testcontainers

## Global Constraints

- AI 호출과 SSE는 구현하지 않는다.
- exploration마다 `QUEUED` 또는 `RUNNING` run은 하나만 존재한다.
- terminal 전이는 단 한 번만 성공하며 DB snapshot이 authoritative하다.
- 같은 command key와 payload는 replay하고 다른 payload는 conflict다.

---

### Task 1: Domain state machine contract

**Files:**
- Create: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/run/*.java`
- Test: `modules/journey/src/test/java/com/yrootlab/onmaru/journey/run/JourneyRunServiceTests.java`

- [x] 실패 테스트로 stage 순서, terminal status, command validation을 고정한다.
- [x] run snapshot, command, receipt, exception, store port와 service를 최소 구현한다.
- [x] focused unit test를 통과시킨다.

### Task 2: Durable command migration

**Files:**
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V009__j02_durable_run_commands.sql`
- Modify: `db/migration/registry/migrations.json`
- Modify: `docs/database/modules/discovery.dbml`
- Modify: `docs/database/schema.md`
- Regenerate: `docs/database/azimutt/*`

- [x] migration contract RED를 추가한다.
- [x] command receipt PK, hash와 result constraints를 migration/DBML에 추가한다.
- [x] registry checksum과 generated artifacts를 동기화한다.

### Task 3: JDBC transaction adapter

**Files:**
- Create: `adapters/persistence-jdbc/build.gradle.kts`
- Create: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/journey/run/JdbcJourneyRunStore.java`
- Modify: `settings.gradle.kts`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcJourneyRunStoreTests.java`

- [x] 실제 PostgreSQL에서 create/claim/stage/terminal RED를 확인한다.
- [x] advisory command lock, receipt replay, CAS와 unique violation mapping을 구현한다.
- [x] concurrent duplicate command와 cancel/complete race를 통과시킨다.
- [x] 새 adapter instance가 DB snapshot과 receipt를 복구하는지 검증한다.

### Task 4: Documentation and verification

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `handoff.md`
- Create: `troubleshooting-worklog/26.09.16 j02-durable-run.md`

- [x] focused/full Java와 repository gate를 실행한다.
- [x] 독립 self-review로 AC와 transaction rollback 경계를 확인한다.
- [ ] commit/push 후 `develop` PR에 `Closes #109`를 기록한다.
