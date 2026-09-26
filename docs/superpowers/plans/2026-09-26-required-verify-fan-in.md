# Required Verify Fan-out/Fan-in Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 동일 SHA 직렬 기준선 artifact를 확정하고, 기존 `CI / verify` 이름과 실패 의미를 보존한 채 native validation과 Toolkit module matrix를 병렬 실행한다.

**Architecture:** `hygiene`와 `contract`는 consumer 저장소의 고정 검증을 소유하고, `module-tests`는 Toolkit v0.1.2 reusable workflow에 모듈 선택·병렬 실행·aggregate evidence를 위임한다. 마지막 `verify`는 모든 dependency와 Toolkit output을 fail-closed로 검사하는 작은 Node program을 실행한다.

**Tech Stack:** GitHub Actions, Node.js `node:test`, GitHub CLI, OnMaru Backend Pipeline Toolkit v0.1.2, Gradle/JDK 21, Python 3.12/uv/pytest

## Global Constraints

- Toolkit workflow ref와 `toolkit_ref`는 `ff3028ae728de076ea38aa56135529c1566f25a8`로 고정한다.
- required check 이름은 `CI / verify`를 유지한다.
- caller 권한은 `contents: read`만 사용하며 secrets 상속, write 권한, environment를 추가하지 않는다.
- matrix 동시성은 `max_parallel: 4`를 넘지 않는다.
- 실패, 취소, 예기치 않은 skip, Toolkit output 누락을 성공으로 처리하지 않는다.
- `improved`, `unchanged`, `regressed`, `inconclusive`는 테스트 실행 자체가 성공하고 evidence가 완전한 상태이며, `failed`와 알 수 없는 값은 실패다.
- 직렬 기준선은 develop SHA `34276f201ce6f7b5ffb6e7ab2784eb584a91a699`의 run `36159816646`, `36160594916`, `36161322635`를 사용한다.
- `develop`과 `master`에 직접 push하지 않는다.

---

### Task 1: Issue #368 immutable baseline artifact 생성

**Files:**
- Inspect: `.github/workflows/collect-ci-baseline.yml`
- Inspect: `scripts/benchmark/ci-run-collector.mjs`
- Inspect: `scripts/benchmark/serial-baseline.mjs`
- Modify after evidence: `handoff.md`

**Interfaces:**
- Consumes: 세 serial CI run ID와 `ubuntu-latest`, `locked`, `unknown`, Java 21, Python 3.12 identity
- Produces: `ci-serial-baseline-<run-id>` artifact, validated `serial-baseline.json`, Issue #368 evidence comment

- [ ] **Step 1: 세 원본 run identity 재검증**

  `gh api repos/YRootLab/OnMaru-backend/actions/runs/<id>`와 jobs endpoint로 세 run이 모두 `success`, workflow `CI`, head SHA `34276f...699`, job `verify`인지 확인한다.

- [ ] **Step 2: 기본 브랜치 collector dispatch**

  다음 입력으로 실행한다.

  ```bash
  gh workflow run 'Collect CI Baseline Evidence' \
    --repo YRootLab/OnMaru-backend \
    --ref master \
    -f run_ids=36159816646,36160594916,36161322635 \
    -f runner_image=ubuntu-latest \
    -f dependency_mode=locked \
    -f cache_state=unknown \
    -f java_version=21 \
    -f python_version=3.12
  ```

- [ ] **Step 3: artifact 내용 검증**

  완료 run의 artifact를 임시 디렉터리에 내려받아 `serial-baseline.json`이 `status=valid`, `runCount=3`, 동일 commit, `verifyWallClockMedianMillis=400000`, 세 run URL, unavailable resource evidence를 포함하는지 검증한다.

- [ ] **Step 4: #368에 증적 기록**

  원본 run 세 개, collector run, artifact 이름, SHA, 중앙값, CPU/RSS 제한, #364 작업 브랜치를 코멘트한다. #364 PR URL이 생기기 전에는 종료하지 않는다.

### Task 2: Final fan-in 실행기의 TDD RED

**Files:**
- Create: `scripts/test/ci-fan-in.test.mjs`
- Create after RED: `scripts/ci/verify-fan-in.mjs`

**Interfaces:**
- Consumes: `HYGIENE_RESULT`, `CONTRACT_RESULT`, `MODULE_JOB_RESULT`, `TOOLKIT_RESULT`, `MANIFEST_URI`, `REPORT_ARTIFACT`
- Produces: 완전한 성공/evidence이면 exit 0, 그 외 exit 1과 원인 메시지

- [ ] **Step 1: 실제 process를 실행하는 실패 테스트 작성**

  `spawnSync(process.execPath, ['scripts/ci/verify-fan-in.mjs'])`를 사용해 다음을 각각 검증한다.

  ```javascript
  test('accepts complete successful fan-in evidence', () => {
    const result = runFanIn({
      HYGIENE_RESULT: 'success', CONTRACT_RESULT: 'success', MODULE_JOB_RESULT: 'success',
      TOOLKIT_RESULT: 'inconclusive', MANIFEST_URI: 'https://github.com/YRootLab/OnMaru-backend/actions/runs/1',
      REPORT_ARTIFACT: 'module-benchmark-report',
    });
    assert.equal(result.status, 0, result.stderr);
  });

  for (const override of [
    { HYGIENE_RESULT: 'failure' }, { CONTRACT_RESULT: 'cancelled' },
    { MODULE_JOB_RESULT: 'skipped' }, { TOOLKIT_RESULT: 'failed' },
    { TOOLKIT_RESULT: '' }, { MANIFEST_URI: '' }, { REPORT_ARTIFACT: '' },
  ]) {
    test(`rejects incomplete or failed fan-in: ${JSON.stringify(override)}`, () => {
      assert.notEqual(runFanIn({ ...validEnvironment, ...override }).status, 0);
    });
  }
  ```

- [ ] **Step 2: RED 확인**

  Run: `node --test scripts/test/ci-fan-in.test.mjs`

  Expected: `scripts/ci/verify-fan-in.mjs` 부재로 성공 case가 exit 1이어야 한다.

- [ ] **Step 3: 최소 실행기 구현**

  필수 job result 세 개가 모두 `success`인지, Toolkit result가 `improved|unchanged|regressed|inconclusive` 중 하나인지, manifest URI가 현재 저장소의 Actions run URL인지, report artifact가 정확히 `module-benchmark-report`인지 검사한다. 모든 오류를 stderr에 나열하고 하나라도 있으면 exit 1한다.

- [ ] **Step 4: GREEN 확인**

  Run: `node --test scripts/test/ci-fan-in.test.mjs`

  Expected: 모든 success/failure/skip/missing-output case가 통과한다.

### Task 3: CI DAG 계약의 TDD RED/GREEN

**Files:**
- Modify: `scripts/test/ci-fan-in.test.mjs`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: Task 2 `scripts/ci/verify-fan-in.mjs`, `.github/benchmark-modules.yml`, pinned Toolkit workflow
- Produces: `hygiene`, `contract`, `module-tests`, final `verify` DAG

- [ ] **Step 1: workflow 정적 계약 테스트 추가**

  다음 계약을 text assertion으로 고정한다.

  - `permissions:\n  contents: read`
  - jobs `hygiene`, `contract`, `module-tests`, `verify`
  - `module-tests`가 pinned reusable workflow와 같은 `toolkit_ref` 사용
  - catalog path, `max_parallel: 4`, `baseline_ref: develop`, `comment_mode: none`
  - PR은 `pr`, 그 외는 `develop` mode
  - `verify`의 `needs: [hygiene, contract, module-tests]`와 `if: always()`
  - final step이 여섯 환경변수에 `needs` result/output을 전달하고 `node scripts/ci/verify-fan-in.mjs` 실행
  - native lane에 Gradle module test와 AI pytest 명령이 없고, secrets/write/environment가 없음

- [ ] **Step 2: workflow RED 확인**

  Run: `node --test scripts/test/ci-fan-in.test.mjs`

  Expected: 현재 단일 `verify` job 때문에 DAG assertion이 실패한다.

- [ ] **Step 3: `ci.yml` 최소 분리 구현**

  기존 검증을 다음과 같이 이동한다.

  - `hygiene`: checkout, repository hygiene, branch parser, Node script tests, planning snapshot, Odii fixture validation
  - `contract`: checkout, Python 3.12/pip setup, contract validator tests, Node 22 setup, `bash scripts/verify-contracts`
  - `module-tests`: pinned Toolkit reusable workflow 호출
  - `verify`: checkout 없이 `ubuntu-latest`에서 Task 2 실행기에 모든 result/output 전달

- [ ] **Step 4: workflow GREEN과 전체 Node regression 확인**

  Run: `node --test scripts/test/ci-fan-in.test.mjs`

  Run: `node --test scripts/test/*.test.mjs`

  Expected: focused와 전체 fixture가 모두 통과한다.

- [ ] **Step 5: 구현 커밋**

  ```bash
  git add .github/workflows/ci.yml scripts/ci/verify-fan-in.mjs scripts/test/ci-fan-in.test.mjs
  git commit -m "feat(ci): required verify를 fan-out fan-in으로 전환"
  ```

### Task 4: 로컬 전체 검증과 운영 문서 갱신

**Files:**
- Modify: `docs/operations/ci-benchmark-baseline.md`
- Modify: `handoff.md`

**Interfaces:**
- Consumes: baseline artifact와 새 CI DAG
- Produces: 재현 가능한 검증 기록과 PR handoff

- [ ] **Step 1: repository 검증 실행**

  ```bash
  git diff --check
  node --test scripts/test/*.test.mjs
  python3 -m pytest scripts/test/test_contract_validation.py
  bash scripts/verify-contracts
  ./gradlew test --no-daemon --max-workers=4
  uv run --project ai pytest ai/tests
  uv run --project ai ruff check
  uv run --project ai mypy
  bash scripts/test/run-ai-evals.test.sh
  ```

- [ ] **Step 2: 기준선 문서와 handoff 갱신**

  collector run/artifact URL, 검증된 중앙값, 새 DAG, 실행한 명령과 결과, 열린 위험을 기록한다. raw artifact 데이터는 commit하지 않는다.

- [ ] **Step 3: 문서 커밋**

  ```bash
  git add docs/operations/ci-benchmark-baseline.md handoff.md
  git commit -m "docs(ci): 기준선과 fan-in 검증 증적 기록"
  ```

### Task 5: PR Actions 검증과 Issue 정리

**Files:**
- External: GitHub PR, Actions runs, Issues #368/#364/#255

**Interfaces:**
- Consumes: pushed branch와 local verification
- Produces: reviewable PR, 병렬 execution evidence, reconciled Issue state

- [ ] **Step 1: push 및 develop 대상 PR 생성**

  PR은 `Refs #364`, `Refs #368`, serial artifact, pinned Toolkit SHA, fail-closed contract, local verification을 포함한다. 원격 branch는 `feature/364-verify-fan-in`을 사용한다.

- [ ] **Step 2: Actions DAG와 artifacts 확인**

  `hygiene`, `contract`, reusable Toolkit detect/matrix/aggregate가 병렬로 실행되고 마지막 `CI / verify`가 성공하는지 확인한다. 모든 module evidence와 aggregate report artifact가 존재해야 한다.

- [ ] **Step 3: #368 종료 조건 충족**

  #368 코멘트에 #364 PR과 baseline artifact를 연결한다. 세 acceptance criterion을 다시 확인한 뒤, develop auto-close 제한을 설명하고 검증 명령을 남겨 수동 종료한다.

- [ ] **Step 4: #364와 #255 상태 기록**

  PR이 병합되기 전에는 #364를 닫지 않는다. #255 체크리스트에서 완료된 child를 갱신하되 #366이 남으므로 #255는 열린 상태로 유지한다.
