# OnMaru CI Performance Measurement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 현재 `develop`의 Gradle CI profile과 보수적인 candidate profile을 baseline/candidate 각각 3회 실행해 검증 가능한 성능 기준값을 만든다.

**Architecture:** 실제 application source와 test plan은 immutable `develop` SHA 하나로 고정하고, candidate branch에서는 allowlist된 `max-workers`와 `build-cache` 값만 바꾼다. 저장소 전용 Skill과 pinned Toolkit이 controller, 여섯 sample run, attestation, comparison을 소유하며 결과는 working tree 밖 receipt와 저장소 내 비밀값 없는 보고서로 분리한다.

**Tech Stack:** GitHub Actions `workflow_dispatch`, Python 3.12, Node.js 22, Java 21/Gradle, `pipeline-toolkit` commit `7ecbb89aae771604d9c1c532cf123f239e279110`

## Global Constraints

- 실제 dispatch는 현재 사용자의 명시적 승인에만 근거한다.
- baseline은 실행 직전 `origin/develop`의 immutable SHA이며 application source와 test plan도 그 SHA를 사용한다.
- candidate branch는 정확히 하나의 `feature/*` remote branch여야 하고 `gradle.properties`의 `onmaru.ci.performance.max-workers`와 `onmaru.ci.performance.build-cache`만 실험 입력으로 사용한다.
- 일반 PR/push/required `verify`에 benchmark job을 추가하지 않는다.
- receipt, raw collection, credential, raw API response는 커밋하지 않는다.
- POST 응답이 모호하면 재-dispatch하지 않는다.
- 비교 결과가 `inconclusive`이면 개선 또는 회귀라고 주장하지 않는다.

---

### Task 1: 격리된 candidate profile 준비

**Files:**
- Modify: `gradle.properties`
- Verify: `scripts/test/gradle-ci-performance.test.mjs`

**Interfaces:**
- Consumes: `origin/develop`의 `gradle.properties` baseline `{max_workers: 4, build_cache: true}`
- Produces: remote `feature/525-ci-performance-measurement`의 candidate `{max_workers: 2, build_cache: false}`와 immutable commit SHA

- [ ] **Step 1: 최신 baseline과 기존 config를 확인한다**

Run:

```bash
git fetch origin --prune
git show origin/develop:gradle.properties
```

Expected: `max-workers=4`, `build-cache=true`, `enabled=false`가 한 번씩 존재한다.

- [ ] **Step 2: 별도 worktree와 feature branch를 만든다**

Run:

```bash
git worktree add /Users/yangseunghyeon/orca/workspaces/OnMaruBE/ci-performance-candidate \
  -b feature/525-ci-performance-measurement origin/develop
```

Expected: clean worktree와 Issue #525를 파싱하는 branch가 생성된다.

- [ ] **Step 3: candidate 값만 변경한다**

`gradle.properties`를 다음 값으로 만든다.

```properties
# CI invokes build-logic/ci-performance.gradle.kts explicitly. Local Gradle defaults stay unchanged.
onmaru.ci.performance.enabled=false
onmaru.ci.performance.max-workers=2
onmaru.ci.performance.build-cache=false
```

- [ ] **Step 4: config allowlist와 실제 Gradle profile을 검증한다**

Run:

```bash
node --test scripts/test/gradle-ci-performance.test.mjs scripts/test/pipeline-benchmark-experiment.test.mjs
git diff --check
node scripts/print-branch-issue.mjs
```

Expected: 모든 테스트 통과, whitespace 오류 없음, branch Issue `525`.

- [ ] **Step 5: candidate를 커밋하고 push한다**

Run:

```bash
git add gradle.properties
git commit -m "perf(ci): 현재 Gradle profile 비교 candidate 준비 (#525)"
git push -u origin feature/525-ci-performance-measurement
```

Expected: local HEAD와 remote branch SHA가 동일하다.

### Task 2: pinned Toolkit 설치와 dry-run

**Files:**
- Read: `skills/onmaru-ci-benchmark-experiment/SKILL.md`
- Execute: `skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py`

**Interfaces:**
- Consumes: clean/pushed candidate branch, closed integration gates, authenticated `gh`
- Produces: immutable baseline/candidate SHA, policy `pipeline-experiment/2`, sample count 3+3, dispatch 가능 여부

- [ ] **Step 1: 전용 virtualenv에 pinned Toolkit을 설치한다**

Run:

```bash
python3 -m venv /tmp/onmaru-pipeline-toolkit-venv
/tmp/onmaru-pipeline-toolkit-venv/bin/python -m pip install \
  'git+https://github.com/YRootLab/OnMaru-backend-ci-toolkit.git@7ecbb89aae771604d9c1c532cf123f239e279110'
```

- [ ] **Step 2: candidate worktree에서 dry-run한다**

Run:

```bash
ONMARU_PIPELINE_TOOLKIT_BIN=/tmp/onmaru-pipeline-toolkit-venv/bin/pipeline-toolkit \
ONMARU_PIPELINE_TOOLKIT_REF=7ecbb89aae771604d9c1c532cf123f239e279110 \
python3 skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py \
  --scope ci --reason 'develop 4 workers/cache와 2 workers/no-cache 현재 성능 비교'
```

Expected: repository, auth, clean tree, remote SHA, workflow, gate가 모두 허용되고 baseline/candidate SHA가 다르다.

- [ ] **Step 3: gate가 열려 있으면 원인을 수정하고 dry-run을 반복한다**

Issue 상태 자체가 순환 조건이면 우회하지 않는다. 이번 명시적 사용자 승인과 실제 Cloud 검증을 반영하는 gate 수정은 별도 TDD/PR로 처리한 뒤 동일 명령을 다시 실행한다.

### Task 3: 정확히 한 번 dispatch하고 receipt 보존

**Files:**
- Create outside repository: `/tmp/onmaru-ci-performance-receipt.json`

**Interfaces:**
- Consumes: Task 2의 성공한 dry-run
- Produces: exact controller run/attempt receipt

- [ ] **Step 1: 실제 3+3 실험을 한 번 dispatch한다**

Run:

```bash
ONMARU_PIPELINE_TOOLKIT_BIN=/tmp/onmaru-pipeline-toolkit-venv/bin/pipeline-toolkit \
ONMARU_PIPELINE_TOOLKIT_REF=7ecbb89aae771604d9c1c532cf123f239e279110 \
python3 skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py dispatch \
  --authorize-dispatch --scope ci \
  --reason 'develop 4 workers/cache와 2 workers/no-cache 현재 성능 비교' \
  > /tmp/onmaru-ci-performance-receipt.json
```

Expected: receipt가 controller run ID와 attempt 1을 포함하고 stdout에 credential이 없다.

- [ ] **Step 2: receipt가 JSON이며 working tree 밖에 있는지 확인한다**

Run:

```bash
python3 -m json.tool /tmp/onmaru-ci-performance-receipt.json >/dev/null
git status --short
```

Expected: JSON 검증 성공, candidate tree clean.

### Task 4: 여섯 run과 attestation 대기

**Files:**
- Read outside repository: `/tmp/onmaru-ci-performance-receipt.json`
- Create outside repository: `/tmp/onmaru-ci-performance-result.json`

**Interfaces:**
- Consumes: exact receipt
- Produces: API와 artifact identity가 검증된 collection 및 verdict

- [ ] **Step 1: exact run/attempt만 기다린다**

Run:

```bash
ONMARU_PIPELINE_TOOLKIT_BIN=/tmp/onmaru-pipeline-toolkit-venv/bin/pipeline-toolkit \
ONMARU_PIPELINE_TOOLKIT_REF=7ecbb89aae771604d9c1c532cf123f239e279110 \
python3 skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py wait \
  --receipt /tmp/onmaru-ci-performance-receipt.json --timeout 3600 \
  > /tmp/onmaru-ci-performance-result.json
```

Expected: baseline/candidate 각 ordinal 1–3, attempt 1, success artifact와 final attestation이 검증된다.

- [ ] **Step 2: 실패 시 재-dispatch하지 않고 diagnostic을 수집한다**

Run:

```bash
python3 -m json.tool /tmp/onmaru-ci-performance-result.json
gh run view <controller-run-id> --repo YRootLab/OnMaru-backend --json status,conclusion,jobs,url
```

Expected: 실패 코드는 source/artifact/timeout/comparability 경계 중 하나로 식별된다.

### Task 5: offline replay와 성능 보고서

**Files:**
- Create outside repository: `/tmp/onmaru-ci-performance-collection.json`
- Create: `docs/reports/2026-10-04-ci-performance-measurement.md`
- Modify: `README.md`
- Modify: `docs/benchmark/README.md`

**Interfaces:**
- Consumes: Task 4 result의 `collection`
- Produces: 재현 가능한 Markdown/JSON 비교와 비밀값 없는 evidence 링크

- [ ] **Step 1: result에서 collection만 분리한다**

Run:

```bash
python3 -c 'import json; d=json.load(open("/tmp/onmaru-ci-performance-result.json")); json.dump(d["collection"], open("/tmp/onmaru-ci-performance-collection.json","w"), indent=2)'
```

- [ ] **Step 2: pinned Toolkit으로 offline compare한다**

Run:

```bash
ONMARU_PIPELINE_TOOLKIT_BIN=/tmp/onmaru-pipeline-toolkit-venv/bin/pipeline-toolkit \
ONMARU_PIPELINE_TOOLKIT_REF=7ecbb89aae771604d9c1c532cf123f239e279110 \
python3 skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py compare \
  --input /tmp/onmaru-ci-performance-collection.json --format markdown
```

Expected: 여섯 개별 값, 양쪽 중앙값과 범위, 상대 변화, 실패율, exclusions, evidence URL, `offline_replay` 표시.

- [ ] **Step 3: 결과와 실행법을 문서화한다**

보고서에는 실제 run URL, immutable SHA, 두 config, 개별 값, 중앙값, 범위, delta, queue/runner 한계, verdict를 기록한다. README에는 dry-run → dispatch → wait → collection 추출 → compare 명령을 그대로 제공하고 `/tmp` 산출물을 커밋하지 않는다고 명시한다.

- [ ] **Step 4: 문서와 전체 계약을 검증한다**

Run:

```bash
node --test scripts/test/pipeline-benchmark-experiment.test.mjs scripts/test/onmaru-ci-benchmark-experiment-skill.test.mjs
bash scripts/verify-contracts --contracts-only
git diff --check
```

Expected: 모든 검증 통과.

### Task 6: GitHub 상태 정합성과 정리

**Files:**
- Modify: `handoff.md`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: live run과 offline replay 결과
- Produces: PR, Issue evidence comments, 충족된 이슈의 종료 상태

- [ ] **Step 1: 결과를 #525/#556과 Toolkit #122에 연결한다**

비교 가능 여부와 관계없이 run URL과 exclusions를 기록한다. #556/#122는 실제 3+3 dispatch 및 attestation AC가 충족된 경우에만 종료 후보로 둔다.

- [ ] **Step 2: integration branch에 보고서·README를 커밋하고 PR을 연다**

PR 본문에는 fresh verification과 `Refs #525`, 완료 조건 충족 시에만 `Closes #556`을 사용한다.

- [ ] **Step 3: required CI 통과 후 병합하고 이슈를 재확인한다**

`develop` 병합 후 Issue Reconcile 결과와 실제 상태를 조회하고, auto-close되지 않은 완료 이슈에는 merge SHA와 검증 명령을 남긴 뒤 수동 종료한다.

- [ ] **Step 4: 단기 candidate branch를 삭제한다**

보고서와 evidence가 병합된 뒤 `feature/525-ci-performance-measurement` remote/local branch와 worktree를 제거한다. receipt와 collection은 GitHub artifact가 보존되는 동안 로컬 `/tmp` 참고자료로만 둔다.

