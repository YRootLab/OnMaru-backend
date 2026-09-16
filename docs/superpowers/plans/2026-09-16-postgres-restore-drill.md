# PostgreSQL Backup And Restore Drill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 암호화 PostgreSQL logical backup을 격리 DB에 복원하고 deletion ledger, integrity, RPO/RTO evidence를 자동 검증한다.

**Architecture:** `infra/backup`의 pinned runner가 backup/restore 명령과 SQL 검증을 소유한다. repository contract test는 정책과 안전 guard를 정적으로 검증하고, Docker drill은 실제 source/target PostGIS에서 전체 흐름을 실행한다. hosting 미선정 provider PITR은 fail-closed 정책으로 남기고 성공 evidence를 만들지 않는다.

**Tech Stack:** POSIX shell, PostgreSQL 17/PostGIS 3.5, pg_dump/pg_restore/psql, GnuPG AES-256, jq, Docker, Node.js test runner, GitHub Actions

## Global Constraints

- RPO 목표는 24시간(`86400`초), RTO 목표는 4시간(`14400`초)이다.
- encrypted full logical backup retention은 7일이다.
- backup credential과 storage는 application runtime에서 분리한다.
- restore는 source와 다른 명시적 isolated target에서만 실행한다.
- deletion ledger replay 이후 active session/run/exploration/public review 재노출은 0이어야 한다.
- evidence에는 secret, connection string, member ID를 기록하지 않는다.
- provider PITR은 hosting과 WAL recovery 증거가 없으면 성공으로 기록하지 않는다.

---

### Task 1: Backup And Restore Contract

**Files:**
- Create: `scripts/test/backup-restore-drill.test.mjs`
- Create: `infra/backup/policy.json`
- Create: `infra/backup/Dockerfile`
- Create: `infra/backup/bin/create-backup`
- Create: `infra/backup/bin/restore-drill`
- Create: `infra/backup/sql/critical-counts.sql`
- Create: `infra/backup/sql/replay-deletion-ledger.sql`
- Create: `infra/backup/sql/integrity.sql`

**Interfaces:**
- Consumes: backup-only PostgreSQL environment variables, encryption key file, isolated target credential, external deletion ledger TSV
- Produces: encrypted `.tar.gpg` artifact, SHA-256 checksum, sanitized restore evidence JSON

- [ ] **Step 1: Write the failing Node contract test**

The test requires the exact RPO/RTO/retention policy, GPG key-file encryption, source/target guard, deletion replay, integrity metrics, and evidence schema.

- [ ] **Step 2: Run the test to verify RED**

Run: `node --test scripts/test/backup-restore-drill.test.mjs`

Expected: FAIL because `infra/backup/policy.json` and the runner files do not exist.

- [ ] **Step 3: Implement the minimal backup runner and SQL suite**

`create-backup` emits only encrypted artifacts and checksums. `restore-drill` rejects a non-isolated or matching target, restores the dump, loads expected counts and deletion ledger into temporary tables, runs replay and integrity SQL, and fails on any non-zero integrity metric or threshold breach.

- [ ] **Step 4: Run the contract test to verify GREEN**

Run: `node --test scripts/test/backup-restore-drill.test.mjs`

Expected: PASS.

### Task 2: Executable Docker Drill And Runbook

**Files:**
- Create: `infra/backup/test/run-restore-drill`
- Create: `.github/workflows/backup-restore-drill.yml`
- Create: `docs/operations/runbooks/restore.md`
- Modify: `docs/operations/README.md`

**Interfaces:**
- Consumes: Task 1 runner image and repository Flyway migration SQL
- Produces: two-database restore proof and uploaded evidence artifact

- [ ] **Step 1: Extend the contract test for workflow and runbook requirements**

Require separate source/target containers, encrypted artifact assertion, zero deletion exposure assertion, least-privilege workflow permissions, and evidence upload.

- [ ] **Step 2: Run the test to verify RED**

Run: `node --test scripts/test/backup-restore-drill.test.mjs`

Expected: FAIL because the Docker drill, workflow, and runbook are missing.

- [ ] **Step 3: Implement and execute the Docker drill**

Run: `infra/backup/test/run-restore-drill`

Expected: source migration/seed, encrypted backup, isolated restore, deletion replay and integrity verification complete with a PASS evidence JSON.

- [ ] **Step 4: Run focused verification**

Run: `node --test scripts/test/backup-restore-drill.test.mjs`
Run: `infra/backup/test/run-restore-drill`

Expected: both PASS.

### Task 3: Delivery

**Files:**
- Modify after final develop merge: `CHANGELOG.md`
- Modify after final develop merge: `handoff.md`
- Create after final develop merge: `troubleshooting-worklog/26.09.16 o04-postgres-restore-drill.md`

**Interfaces:**
- Consumes: Tasks 1-2
- Produces: reviewable Issue #94 PR into `develop`

- [ ] **Step 1: Merge latest develop without rebasing**

Run: `git fetch origin develop`
Run: `git merge --no-edit origin/develop`

- [ ] **Step 2: Reconcile shared logs and run full verification**

Run the repository Gradle, Node, planning, fixture, contract, FastAPI, backup drill, diff-check, and branch parser gates.

- [ ] **Step 3: Commit, push, create PR, and request review**

Commit: `feat(ops): PostgreSQL 복원 drill 자동화`

PR: Korean title/body, base `develop`, `Closes #94`, request reviewer `yshls`, no direct merge before CI and approval.
