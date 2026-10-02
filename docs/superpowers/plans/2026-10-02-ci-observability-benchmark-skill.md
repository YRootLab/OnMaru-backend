# CI Observability And Benchmark Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OnMaruBE의 CI 관측 후처리, release 3회 판정, 수동 pipeline experiment, 저장소 전용 Skill을 안전한 dry-run 우선 계약으로 구현한다.

**Architecture:** OnMaruBE workflow는 trusted controller와 consumer-local diagnostics를 소유하고, Toolkit v0.1.3 commit `d5b7892875000afc2deba6e6873717974d558ee5`를 실행·검증 권한으로 사용한다. 일반 CI와 release/experiment 경로를 분리하며, 실제 dispatch와 Grafana Cloud 검증은 코드·PR 병합·Issue gate·credential이 준비된 뒤에만 수행한다.

**Tech Stack:** GitHub Actions YAML, Node.js 22 contract tests, Python 3.12 adapters, YRootLab/OnMaru-backend-ci-toolkit v0.1.3, OpenTelemetry OTLP/HTTP, Grafana Mimir/Tempo.

## Global Constraints

- `CI / verify`의 기존 테스트 범위와 verdict를 줄이거나 후처리 실패에 연결하지 않는다.
- Toolkit pin은 불변 commit `d5b7892875000afc2deba6e6873717974d558ee5` 하나를 workflow, helper, test, docs에서 일치시킨다.
- 일반 PR, push, CD는 baseline/candidate 3회 실험을 자동 시작하지 않는다.
- pipeline experiment의 기본 action은 `dry-run`; 실제 `dispatch`는 현재 대화의 명시적 실행 요청이 있을 때만 허용하고 응답 유실 후 자동 재시도하지 않는다.
- `inconclusive`는 성공이나 회귀로 승격하지 않으며 failed/cancelled/missing/mismatch 증적을 성능 회귀로 위장하지 않는다.
- fork PR head code나 artifact 명령을 privileged context에서 실행하지 않고, test/sample job에 Grafana·OTLP·attestation secret을 전달하지 않는다.
- 실제 Grafana Cloud round trip, 실제 pipeline dispatch, release 3+3 Actions 검증은 외부 gate이며 fixture 통과로 대체하거나 완료로 주장하지 않는다.
- 정본 Skill은 `skills/onmaru-ci-benchmark-experiment/` 하나이며 `.agents/skills` 복사·symlink를 만들지 않는다.

---

### Task 1: Trusted CI observability post-run (#554)

**Files:**
- Create: `.github/workflows/ci-observability.yml`
- Create: `scripts/benchmark/ci-observability.py`
- Create: `scripts/test/fixtures/ci-observability/`
- Create: `scripts/test/ci-observability-workflow.test.mjs`
- Modify: `.github/workflows/module-benchmark.yml`
- Modify: `scripts/test/module-benchmark-caller.test.mjs`
- Modify: `docs/benchmark/README.md`

**Interfaces:**
- Consumes: completed `CI`/`Module Benchmark` run ID and attempt, Toolkit telemetry Python APIs, environment-scoped `OTLP_ENDPOINT` and `OTLP_HEADERS`.
- Produces: bounded `ci-observability-diagnostic-<run>-<attempt>` artifact containing normalized evidence, redacted diagnostics, export result, and durable replay state identity.

- [ ] **Step 1: Write failing workflow and adapter tests**

  Add behavior tests that execute the Python adapter against fixture API pages and bounded ZIPs. Cover success/failure/cancelled, attempt mismatch, pagination, path traversal/symlink/oversized/duplicate members, malicious command strings, duplicate digest replay, and export failure preserving source conclusion. Add workflow assertions for completed-only `workflow_run`, manual replay, least privilege, trusted checkout, environment-only secrets, `if: always()` diagnostics, and no dependency from `CI / verify`.

- [ ] **Step 2: Run tests and verify RED**

  Run `node --test scripts/test/ci-observability-workflow.test.mjs scripts/test/module-benchmark-caller.test.mjs` and confirm failure because the workflow/adapter and v0.1.3 pin are absent.

- [ ] **Step 3: Implement the bounded adapter and trusted workflow**

  Implement explicit subcommands `collect` and `export`. `collect` must validate repository, workflow, run, attempt, pagination, and artifact limits before calling Toolkit normalization; `export` must use Toolkit `MetricPolicy`, `transform_actions_evidence`, `SQLiteReplayStore`, and `export_otlp`. Never execute artifact fields. Store only allowlisted JSON and redacted diagnostics. Update the module caller and its test to the single Toolkit pin.

- [ ] **Step 4: Run focused tests and refactor**

  Run the focused Node suite plus Python syntax compilation. Keep API/ZIP parsing and Toolkit invocation separately testable and retain bounded output.

- [ ] **Step 5: Commit**

  Commit as `feat(ci): 신뢰된 CI 관측 후처리 추가 (#554)`.

### Task 2: Round-trip evidence and operating contract (#555)

**Files:**
- Create: `observability/grafana/dashboards/ci-benchmark.json`
- Create: `docs/operations/release-evidence/ci-observability.md`
- Create: `scripts/test/grafana-ci-observability.test.mjs`
- Modify: `docs/operations/release-evidence/README.md`
- Modify: `docs/operations/runbooks/secrets.md`

**Interfaces:**
- Consumes: Task 1 diagnostic artifact and Toolkit v0.1.3 dashboard/query contract.
- Produces: importable CI dashboard, local success/failure/outage/replay verification, and a Cloud checklist that distinguishes verified evidence from pending tenant-only evidence.

- [ ] **Step 1: Write failing dashboard and evidence tests**

  Test datasource/query/link safety, required success/failure/quality/export panels, low-cardinality labels, retention/cardinality budgets, credential isolation, replay/outage commands, and explicit `pending` fields for Cloud-only evidence.

- [ ] **Step 2: Run tests and verify RED**

  Run `node --test scripts/test/grafana-ci-observability.test.mjs` and confirm missing dashboard/evidence failures.

- [ ] **Step 3: Add dashboard export and operational evidence template**

  Copy the pinned Toolkit dashboard contract without changing metric semantics, document Cloud datasource remapping, least-privilege credential creation/rotation, Mimir/Tempo queries, outage/replay drill, series/span/retention/cost evidence fields, and non-secret run/manifest links. Mark unexecuted Cloud checks as pending rather than pass.

- [ ] **Step 4: Run focused verification**

  Run the dashboard test and Task 1 fixture suite. Record that live Cloud verification remains blocked when repository environment secrets are absent.

- [ ] **Step 5: Commit**

  Commit as `test(observability): CI telemetry 왕복 검증 계약 추가 (#555)`.

### Task 3: Release three-run authority and Java CI decision (#543, #525)

**Files:**
- Create: `scripts/benchmark/release-module-comparison.py`
- Create: `scripts/test/fixtures/release-module-comparison/`
- Create: `scripts/test/release-module-comparison.test.mjs`
- Modify: `.github/workflows/benchmark-release.yml`
- Modify: `scripts/test/workflow-benchmark-release.test.mjs`
- Modify: `docs/benchmark/contracts.md`
- Modify: `docs/operations/release-evidence/benchmark.md`
- Modify: `docs/benchmark/README.md`

**Interfaces:**
- Consumes: exactly three distinct valid baseline and candidate module evidence records per side and Toolkit `compare_module_benchmarks(target=RELEASE)` from the pinned checkout.
- Produces: one canonical result containing values, median, range, delta, exclusions, source Actions/manifest links, and `approval_hold` only for a valid regression strictly greater than 15%.

- [ ] **Step 1: Write failing comparison and workflow tests**

  Cover 2+2 `inconclusive`, 3+3 comparison, exactly 15%, greater than 15%, duplicate run, failed/cancelled/missing, comparability mismatch, zero baseline, unsafe links, and proof that the legacy 5% W4 adapter no longer owns promotion.

- [ ] **Step 2: Run tests and verify RED**

  Run `node --test scripts/test/release-module-comparison.test.mjs scripts/test/workflow-benchmark-release.test.mjs` and confirm the new canonical result is absent.

- [ ] **Step 3: Implement thin pinned-Toolkit comparison and single gate mapping**

  Parse allowlisted JSON into Toolkit module evidence types, call the Toolkit comparator, and map `approval_hold` to the protected `benchmark-promotion` environment. `failed`/`inconclusive` block automatic promotion without creating a regression approval. Preserve the develop-only module benchmark as diagnostics and the required CI unchanged.

- [ ] **Step 4: Document #525 decision with measured limits**

  Record that the shared-runner Java lane remains required because the matrix experiment's whole-workflow median was 725s versus serial 470s, while its 411.62s critical-path proxy is not a same-boundary win. Keep `max-workers=4` opt-in and describe self-hosted runner isolation/cost as unselected; do not claim a new performance improvement without live same-boundary runs.

- [ ] **Step 5: Run focused tests and commit**

  Commit as `feat(ci): release 3회 benchmark 판정 연결 (#543 #525)`.

### Task 4: Manual pipeline benchmark producer (#556)

**Files:**
- Create: `.github/workflows/pipeline-benchmark-experiment.yml`
- Create: `.github/workflows/pipeline-benchmark-sample.yml`
- Create: `.github/pipeline-benchmark-test-plan.json`
- Create: `scripts/benchmark/pipeline-experiment.mjs`
- Create: `scripts/test/pipeline-benchmark-experiment.test.mjs`
- Modify: `docs/benchmark/README.md`

**Interfaces:**
- Consumes: `baseline_ref`, `candidate_ref`, `scope`, `reason` workflow-dispatch inputs and one trusted application source/test plan.
- Produces: six distinct sample runs, exact run-name correlation, sample artifacts, a trusted `pipeline-experiment/2` manifest, provenance attestation, diagnostic artifact, and Job Summary.

- [ ] **Step 1: Write failing producer tests**

  Test dispatch-only trigger, four inputs, concurrency, immutable refs, baseline/candidate ordinals 1..3, distinct run IDs, exact run-name, committed-plan hashing, same application tree, no candidate commands/secrets, artifact names, attestation byte identity, and failure/inconclusive diagnostics.

- [ ] **Step 2: Run tests and verify RED**

  Run `node --test scripts/test/pipeline-benchmark-experiment.test.mjs` and confirm missing files fail.

- [ ] **Step 3: Implement the trusted controller, sample worker, and plan**

  Keep test commands fixed in the committed plan, resolve refs once, dispatch each sample separately, wait for exact receipts, validate actual checkouts and wall-clock evidence, and attest only the final trusted manifest. Never place privileged secrets in sample jobs or execute candidate-provided text.

- [ ] **Step 4: Run fake integration and document live gate**

  Exercise the helper with a fake GitHub API through success/failure/cancelled/missing fixtures. Document that Toolkit POST remains blocked until #555/#556 are closed and the workflow exists on the required default-branch path; do not bypass or perform a real dispatch.

- [ ] **Step 5: Commit**

  Commit as `feat(ci): 수동 pipeline benchmark 실험 workflow 추가 (#556)`.

### Task 5: Repository-local benchmark Skill and migration handoff (#568)

**Files:**
- Import: `docs/superpowers/specs/2026-10-02-onmaru-ci-benchmark-experiment-skill-design.md`
- Create: `skills/onmaru-ci-benchmark-experiment/SKILL.md`
- Create: `skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py`
- Create: `skills/onmaru-ci-benchmark-experiment/evals/evals.json`
- Create: `scripts/test/onmaru-ci-benchmark-experiment-skill.test.mjs`
- Modify: `AGENTS.md`
- Modify: `handoff.md`

**Interfaces:**
- Consumes: pinned `pipeline-toolkit experiment` CLI and Task 4 workflow contract.
- Produces: default-dry-run natural-language workflow, explicit dispatch boundary, exact wait/compare forwarding, bounded stable output, Korean error/inconclusive guidance, and deterministic trigger/behavior eval fixtures.

- [ ] **Step 1: Write failing helper and skill behavior tests**

  Use a fake Toolkit executable to assert exact argv, `shell=False`, default dry-run, explicit dispatch only, no lost-response retry, exit/stdout preservation, bounded output, stable error translation, and `inconclusive` handling. Validate positive/negative trigger eval coverage and canonical layout.

- [ ] **Step 2: Run tests and verify RED**

  Run `node --test scripts/test/onmaru-ci-benchmark-experiment-skill.test.mjs` and confirm the skill/helper are absent.

- [ ] **Step 3: Implement the Skill and deterministic adapter**

  Keep calculations, GitHub validation, evidence parsing, and policy in Toolkit. Accept only `dry-run|dispatch|wait|compare`; use argument arrays without a shell; cap captured output; preserve exit 0/2; never print raw stderr/secrets. Add repository-local skill discovery rules to `AGENTS.md` without duplicating the skill.

- [ ] **Step 4: Validate the Skill**

  Run `python3 /Users/yangseunghyeon/.codex/skills/.system/skill-creator/scripts/quick_validate.py skills/onmaru-ci-benchmark-experiment`, the focused test, and an independent fake-run evaluation. Record Toolkit #115/#122 and Agent Toolkit #58 ownership-link changes as post-merge work, not as completed in this branch.

- [ ] **Step 5: Commit**

  Commit as `feat(skill): OnMaru pipeline benchmark Skill 추가 (#568)`.

### Task 6: Whole-branch verification and work-log reconciliation

**Files:**
- Modify: `handoff.md`
- Modify if needed: `improvements.md`

- [ ] **Step 1: Run repository verification**

  Run `node --test scripts/test/*.test.mjs`, `bash scripts/verify-contracts`, Python helper tests/compilation, Skill quick validation, `git diff --check`, and `node scripts/print-branch-issue.mjs`.

- [ ] **Step 2: Classify external gates**

  List live Grafana Cloud round trip, release 3+3 Actions execution, pipeline dispatch, default-branch availability, #555/#556 circular state gate, Toolkit/Agent Toolkit link updates, and Agent Toolkit #58 closure as pending external/post-merge items unless actually verified.

- [ ] **Step 3: Reconcile work logs**

  Record branch, Issues, touched files, verification, next step, and open risks in `handoff.md`; do not remove unrelated parallel work-log entries.

- [ ] **Step 4: Final review**

  Review the complete branch diff against Issues #554, #555, #543, #525, #556, and #568, then fix only verified Critical/Important findings through the SDD review loop.
