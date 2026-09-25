# Parallel CI Toolkit Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 직렬 검증 범위를 보존하면서 immutable Toolkit reusable workflow 기반 병렬 테스트를 shadow mode에서 시작해 최종 `verify` fan-in과 지속적인 성능 증적으로 전환한다.

**Architecture:** OnMaru-backend가 모듈 catalog와 실제 테스트 명령을 소유하고, Toolkit은 고정된 40자리 SHA의 reusable workflow 및 planner로만 호출한다. rollout은 #365 shadow caller, #368 serial artifact, 병렬 성공 표본, #364 final fan-in, #366 장기 추세 순으로 진행하며 각 단계는 별도 브랜치와 PR로 격리한다.

**Tech Stack:** GitHub Actions reusable workflows, YAML/JSON catalog, Node.js `node:test`, Gradle/JDK 21, Python 3.12/uv/pytest, GitHub CLI

## Global Constraints

- Toolkit workflow ref와 `toolkit_ref`는 모두 output 줄바꿈 fix가 포함된 `v0.1.2` SHA `ff3028ae728de076ea38aa56135529c1566f25a8`을 사용한다. 최초 지정된 `v0.1.1` SHA는 reusable workflow output을 한 줄로 합치는 결함 때문에 대체했다.
- Toolkit을 backend application에 import하지 않고 consumer-owned `.github/benchmark-modules.yml`의 명령만 실행한다.
- 기존 `.github/workflows/ci.yml`의 직렬 `verify`는 shadow evidence가 승인되기 전 삭제·약화·대체하지 않는다.
- caller 권한은 `contents: read`이며 `secrets: inherit`, write token, release/deploy environment, deployment credential을 금지한다.
- matrix 동시성 상한은 `max_parallel: 4`다.
- 실패, 취소, artifact 누락, 실행 조건 불일치는 성공이나 성능 개선으로 간주하지 않고 `failed` 또는 `inconclusive`로 기록한다.
- CPU/RSS를 수집할 수 없으면 `null` 또는 `unavailable`로 남기고 `0`으로 기록하지 않는다.
- 직렬 기준선은 SHA `34276f201ce6f7b5ffb6e7ab2784eb584a91a699`의 run `36159816646`, `36160594916`, `36161322635`와 중앙값 400초다.
- PR #374는 대체 PR의 Actions 성공 및 merge 전에는 닫지 않는다.
- `develop`과 `master`에 직접 push하지 않고 Issue 번호를 포함한 짧은 브랜치와 PR을 사용한다.

---

### Task 1: Issue #365 shadow module benchmark caller

**Files:**
- Create: `.github/workflows/module-benchmark.yml`
- Create: `scripts/test/module-benchmark-caller.test.mjs`
- Modify: `handoff.md`

**Interfaces:**
- Consumes: `.github/benchmark-modules.yml`, Toolkit `.github/workflows/module-benchmark.yml` at the pinned SHA
- Produces: PR/develop/manual `Module Benchmark` workflow with module and aggregate artifacts while leaving `CI / verify` unchanged

- [ ] **Step 1: Write the failing caller contract tests**

  Parse the workflow as text and assert the three triggers, `contents: read`, absence of inherited secrets/write permissions/environments, exact reusable workflow SHA, identical `toolkit_ref`, catalog path, `max_parallel: 4`, `baseline_ref: develop`, and `comment_mode: none`. Assert the existing `ci.yml` bytes are unchanged from `origin/develop` outside the test process.

- [ ] **Step 2: Verify RED**

  Run `node --test scripts/test/module-benchmark-caller.test.mjs` and confirm failure because `.github/workflows/module-benchmark.yml` does not exist.

- [ ] **Step 3: Add the minimal shadow caller**

  Add a caller for pull requests, pushes to `develop`, and manual dispatch. Use one reusable-workflow job plus a read-only summary job if and only if the pinned workflow exposes the outputs used by the summary. Do not edit `ci.yml`.

- [ ] **Step 4: Verify GREEN and regression**

  Run `node --test scripts/test/module-benchmark-caller.test.mjs`, `node --test scripts/test/module-catalog.test.mjs`, `node --test scripts/test/*.test.mjs`, `git diff --check`, and `node scripts/print-branch-issue.mjs`.

- [ ] **Step 5: Commit and open the replacement PR**

  Commit with `feat(ci): Toolkit module benchmark shadow caller 추가`, push `feature/365-toolkit-module-caller-rollout`, and open a Korean PR into `develop` with `Refs #365`. Include the immutable Toolkit SHA, local verification, known baseline, and explicit statement that `verify` remains unchanged.

### Task 2: Shadow Actions evidence and PR #374 supersession

**Files:**
- Modify: `handoff.md` only if the current-session evidence needs restart context
- External evidence: replacement PR, Issue #365, PR #374

**Interfaces:**
- Consumes: Task 1 PR runs
- Produces: successful module evidence, aggregate report, critical path, and three comparable successful parallel samples

- [ ] **Step 1: Inspect the replacement PR run**

  Verify every selected catalog command ran on a fresh runner, AI bootstrapped `uv`, matrix concurrency did not exceed four, and module plus aggregate artifacts are downloadable.

- [ ] **Step 2: Exercise selection fixtures**

  Record Actions evidence for direct module, docs-only, common Gradle/workflow, and unknown path inputs; the last three must fail safe to the documented full-suite plan.

- [ ] **Step 3: Merge only after both checks pass**

  Require existing `CI / verify` and shadow `Module Benchmark` success, then merge to `develop` and reconcile Issue #365 according to its acceptance criteria.

- [ ] **Step 4: Supersede PR #374**

  Only after merge, comment on #374 with the replacement PR link, immutable SHA, successful run/artifact links, and conflict/obsolete-run rationale; then close #374 as superseded.

- [ ] **Step 5: Collect three parallel samples**

  Re-run the same merged commit under the same runner, cache, Java/Python, dependency, and command identity. Preserve run ID, artifact URL, status, module wall-clock, aggregate wall-clock, and critical path for all three samples.

### Task 3: Issue #368 immutable serial baseline artifact

**Files:**
- Modify when required: `.github/workflows/collect-ci-baseline.yml`
- Modify when required: `scripts/test/ci-run-collector.test.mjs`
- Modify: `docs/operations/ci-benchmark-baseline.md`
- Modify: `handoff.md`

**Interfaces:**
- Consumes: serial run IDs `36159816646`, `36160594916`, `36161322635`
- Produces: downloadable `ci-serial-baseline-*` artifact and Issue #368 evidence comment

- [ ] **Step 1: Confirm the collector is reachable from the default branch**

  If absent from `master`, use the repository release flow to land the already-reviewed collector without direct push. Do not dispatch a workflow definition that exists only on `develop`.

- [ ] **Step 2: Lock missing resource behavior with a failing test if code changes are needed**

  The manifest must retain unavailable CPU/RSS as `null` or an explicit unavailable marker and reject failed, incomplete, or identity-mismatched candidates.

- [ ] **Step 3: Dispatch the collector**

  Supply the exact three run IDs plus `ubuntu-latest`, locked dependency mode, the verified cache identity, Java 21, and Python 3.12.

- [ ] **Step 4: Inspect and record the artifact**

  Download the artifact, validate three successful runs at the same SHA and the 400-second median, then comment with immutable run/artifact URLs and close #368 only when every acceptance criterion is met.

### Task 4: Issue #364 final fan-out/fan-in verify

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `scripts/test/ci-fan-in.test.mjs`
- Modify: `handoff.md`

**Interfaces:**
- Consumes: stable Task 2 shadow evidence and Task 3 serial baseline artifact
- Produces: independent native lane(s), Toolkit module matrix, and final required job named exactly `verify`

- [ ] **Step 1: Write failing DAG and fail-closed fixture tests**

  Assert native lanes exclude Gradle/Python module tests, final `verify` depends on every required lane, and failure/cancellation/missing artifacts cannot become success. Assert allowed skip is explicit.

- [ ] **Step 2: Verify RED**

  Run `node --test scripts/test/ci-fan-in.test.mjs` and confirm the current serial workflow fails the new topology contract.

- [ ] **Step 3: Split native non-module validation from Toolkit tests**

  Keep repository hygiene, branch parser, Node fixtures, planning snapshots, contract/generated artifact validation, and other non-matrix checks in native lanes. Remove only module tests proven equivalent in the Toolkit matrix.

- [ ] **Step 4: Add final `verify` fan-in**

  Make the final job evaluate all lane results and required artifact completeness with `if: always()`. Any failure, cancellation, or missing required evidence must fail.

- [ ] **Step 5: Verify locally and in Actions**

  Run focused fixture tests, all Node tests, repository hygiene/contract commands, Gradle/Python suites, and inspect a PR run showing concurrent lanes and final `verify` semantics before merge.

### Task 5: Comparable performance decision

**Files:**
- Modify when needed: `scripts/benchmark/compare-ci-baselines.mjs`
- Modify when needed: `scripts/test/compare-ci-baselines.test.mjs`
- Create or modify: `docs/operations/ci-benchmark-rollout.md`

**Interfaces:**
- Consumes: Task 2 three-sample parallel aggregate and Task 3 serial baseline
- Produces: comparable/inconclusive decision, medians, critical path, failure/cancellation/artifact rates, and improvement percentage

- [ ] **Step 1: Validate comparison identity**

  Require identical commit or approved equivalent code/commands, runner image/architecture, dependency/cache mode, Java/Python toolchain, suite, and complete success artifacts.

- [ ] **Step 2: Compute the decision**

  Calculate `(400 - parallelMedianSeconds) / 400 * 100`. Emit `improved` only when reliability and artifact completeness do not regress; otherwise emit `inconclusive` with reasons.

- [ ] **Step 3: Publish auditable evidence**

  Record run IDs, artifact URLs, both medians, critical path, improvement percentage, missing resource limits, and comparison status without committing raw benchmark data.

### Task 6: Issue #366 durable develop/nightly/release/CD evidence

**Files:**
- Modify or create: `.github/workflows/benchmark-release.yml`
- Modify or create: `scripts/benchmark/release-evidence.mjs`
- Modify or create: `scripts/test/release-evidence.test.mjs`
- Modify: `docs/operations/ci-benchmark-release.md`
- Modify: `handoff.md`

**Interfaces:**
- Consumes: final fan-in workflow and validated comparison identity
- Produces: PR affected feedback, develop/nightly full-suite samples, release comparison with at least five successes, and CD lead-time evidence

- [ ] **Step 1: Write failing lifecycle fixtures**

  Assert PR uses affected selection, develop/nightly uses full-suite, release requires at least five comparable successful samples, and a median regression greater than 15% creates approval-hold. Missing/incompatible evidence must be inconclusive.

- [ ] **Step 2: Implement durable schedules and release evidence**

  Preserve tag, commit SHA, image digest, runner profile, configuration hash, cache state, benchmark suite, build start, deploy completion, and successful health-check time.

- [ ] **Step 3: Verify and document operations**

  Run focused and full fixtures, inspect actual artifacts and protected-environment behavior, and document manual recovery plus evidence-retention limits.

- [ ] **Step 4: Propose branch-protection change separately**

  After final `verify` is stable and evidence is sufficient, create a separate Issue/PR proposal for required checks. Do not mutate branch protection as part of this rollout implementation.
