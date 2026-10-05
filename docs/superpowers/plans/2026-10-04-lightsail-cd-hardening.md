# Lightsail CD Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OnMaru Spring 운영 CD가 GitHub Actions의 변수 평가 시점에 막히지 않고, 동일 SHA를 build 전에 종료하며, 성공 후 장애도 제한된 명령으로 rollback하고 알림과 디스크 정리까지 수행하도록 보강한다.

**Architecture:** production trigger는 repository variable로 활성화하고 `production-preflight`가 제한 SSH `status <sha>`를 호출해 `deploy`, `deployed`, `held` 상태를 결정한다. 실제 배포와 rollback은 별도 root-owned script로 격리하고 forced-command wrapper가 허용된 명령만 전달한다. 배포 완료 후에는 오래된 dangling image만 정리하고, 모든 production 실행 결과는 environment secret의 webhook으로 통지한다.

**Tech Stack:** GitHub Actions, POSIX shell, Docker Compose, Nginx, GHCR, Node.js test runner, Markdown/Mermaid

## Global Constraints

- 운영 대상은 원격 `master`의 Spring API image뿐이며 FastAPI는 별도 Lightsail CD 전까지 포함하지 않는다.
- 보호 브랜치에 직접 push하지 않고 `develop → release/* → master` Git Flow를 유지한다.
- production SSH principal은 일반 shell을 제공하지 않고 `status`, `deploy`, `rollback`만 허용한다.
- DB migration rollback은 자동화하지 않으며 expand/contract 호환성을 전제로 한다.
- 1GB RAM에서 두 Spring slot은 전환 구간에만 겹치고 메모리·swap·OOM gate를 유지한다.
- 실제 production 배포, GitHub environment 변경, commit, PR 생성은 이 구현 작업에서 수행하지 않는다.

---

### Task 1: Workflow gate와 동일 SHA 조기 종료

**Files:**
- Modify: `scripts/test/production-blue-green-deploy.test.mjs`
- Modify: `.github/workflows/deploy.yml`
- Create: `infra/lightsail/production/status-blue-green.sh`
- Modify: `infra/lightsail/production/deployer-command.sh`
- Modify: `infra/lightsail/production/install-deployer.sh`

**Interfaces:**
- Consumes: repository variable `PRODUCTION_DEPLOY_ENABLED`, production environment SSH secret/host key
- Produces: `production-preflight.outputs.deploy-required`와 `status <master-sha>` forced command

- [x] **Step 1: Write failing tests**

Add assertions that the production job gate no longer depends on an environment-scoped variable, `production-preflight` runs before image build for production triggers, and the forced command accepts `status <sha>` only.

- [x] **Step 2: Verify RED**

Run: `node --test scripts/test/production-blue-green-deploy.test.mjs`

Expected: FAIL because the preflight job and status script do not exist.

- [x] **Step 3: Implement preflight and status command**

The status script returns exactly one of `deploy`, `deployed`, or `held`. `deployed` requires matching deployed SHA, matching checkout SHA, and public health. A rollback hold blocks only the bad SHA recorded in the hold file; a newer master returns `deploy`.

- [x] **Step 4: Verify GREEN**

Run: `node --test scripts/test/production-blue-green-deploy.test.mjs`

Expected: all production deployment tests pass.

### Task 2: Explicit post-success rollback

**Files:**
- Modify: `scripts/test/production-blue-green-deploy.test.mjs`
- Create: `infra/lightsail/production/rollback-blue-green.sh`
- Modify: `infra/lightsail/production/deploy-blue-green.sh`
- Modify: `infra/lightsail/production/deployer-command.sh`
- Modify: `infra/lightsail/production/install-deployer.sh`
- Modify: `.github/workflows/deploy.yml`

**Interfaces:**
- Consumes: `rollback_production=true`, active/previous slot state, previous SHA/image state
- Produces: restricted `rollback <github-actor>` command and rollback hold for the rejected SHA

- [x] **Step 1: Write failing rollback contract tests**

Assert that rollback is manual-only, records previous SHA, starts and health-checks the stopped slot, atomically switches Nginx, updates state, and writes a rollback hold.

- [x] **Step 2: Verify RED**

Run: `node --test scripts/test/production-blue-green-deploy.test.mjs`

Expected: FAIL because the rollback script and workflow input/job are absent.

- [x] **Step 3: Implement bounded rollback**

Rollback must support the retained pre-Blue-Green legacy slot for the first transition while refusing ambiguous state, staging overlap, unhealthy previous slot, memory floor violations, or schema-risk automation. It switches traffic only after health succeeds, restores the previous slot/image/SHA state, stops the rejected slot after drain, and leaves a hold that prevents the same SHA from returning on the next schedule.

- [x] **Step 4: Verify GREEN**

Run: `node --test scripts/test/production-blue-green-deploy.test.mjs`

Expected: all production deployment tests pass.

### Task 3: Notification, image retention, and action pinning

**Files:**
- Modify: `scripts/test/production-blue-green-deploy.test.mjs`
- Modify: `.github/workflows/deploy.yml`
- Modify: `infra/lightsail/production/deploy-blue-green.sh`
- Modify: `infra/lightsail/production/rollback-blue-green.sh`
- Modify: `infra/lightsail/README.md`

**Interfaces:**
- Consumes: production environment secret `PRODUCTION_DEPLOY_WEBHOOK_URL`
- Produces: success/failure/no-op webhook payload and bounded dangling-image cleanup

- [x] **Step 1: Write failing hardening tests**

Assert that every third-party action uses a 40-character commit SHA with a version comment, production notification runs with `always()`, and successful deploy/rollback invokes `docker image prune` with an age filter.

- [x] **Step 2: Verify RED**

Run: `node --test scripts/test/production-blue-green-deploy.test.mjs`

Expected: FAIL on mutable action tags, missing webhook job, and missing prune command.

- [x] **Step 3: Implement minimal hardening**

Resolve each current action tag to its upstream commit SHA, add a production result webhook containing repository/run/SHA/result without secrets, and prune only dangling images older than 168 hours after a successful state transition.

- [x] **Step 4: Verify GREEN**

Run: `node --test scripts/test/deploy-pipeline.test.mjs scripts/test/production-blue-green-deploy.test.mjs`

Expected: all deployment contract tests pass.

### Task 4: Operations guide, project skill, and technical blog

**Files:**
- Modify: `infra/lightsail/README.md`
- Modify: `infra/lightsail/staging/README.md`
- Modify: `skills/onmaru-production-deploy/SKILL.md`
- Modify: `docs/drafts/2026-10-04-lightsail-blue-green-cd.md`
- Modify: `handoff.md`

**Interfaces:**
- Consumes: final verified workflow and script behavior
- Produces: operator bootstrap/runbook, accurate skill preflight, and publishable long-form narrative

- [x] **Step 1: Update operational documentation**

Document repository-level activation variable, environment secrets, webhook contract, preflight no-op, manual rollback/hold release behavior, image retention, first-deploy checklist, and the boundary between automatic rollback and post-success rollback.

- [x] **Step 2: Extend the blog draft**

Using `blog-tone` and `writing-rule`, add the discovered environment-variable timing trap, the distinction between deployment transaction rollback and operational rollback, why a rollback hold is required for scheduled CD, why safe image retention follows container references, and how action SHA pinning changes the supply-chain trust boundary. Add Mermaid only where the state flow is otherwise difficult to follow.

- [x] **Step 3: Verify documentation consistency**

Run: `rg -n "PRODUCTION_DEPLOY_ENABLED|rollback_production|PRODUCTION_DEPLOY_WEBHOOK_URL|168|rollback hold" infra/lightsail/README.md docs/drafts/2026-10-04-lightsail-blue-green-cd.md skills/onmaru-production-deploy/SKILL.md`

Expected: each operational contract appears in the runbook and narrative without contradictory environment scope.

### Task 5: Full verification

**Files:**
- Verify all modified files

**Interfaces:**
- Consumes: completed Tasks 1–4
- Produces: evidence suitable for handoff without claiming a live production deployment

- [x] **Step 1: Run static and contract checks**

Run:

```bash
node --test --test-concurrency=1 scripts/test/*.test.mjs
for file in infra/lightsail/production/*.sh; do sh -n "$file"; done
docker compose --env-file infra/lightsail/.env.example -f infra/lightsail/compose.yaml config --quiet
ruby -e "require 'yaml'; YAML.load_file('.github/workflows/deploy.yml')"
git diff --check
```

Expected: every command exits zero.

- [x] **Step 2: Record remaining live prerequisites**

Confirm that no workflow was dispatched and report that merge, production environment creation, Lightsail bootstrap, and the first observed manual deployment remain external activation steps.
