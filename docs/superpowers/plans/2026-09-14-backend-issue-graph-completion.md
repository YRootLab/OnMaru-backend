# Backend Issue Graph Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 누락된 backend 계약·schema·원천 데이터·운영 gate Leaf를 추가하고 로컬 Work Graph와 GitHub native Issue 관계를 동일하게 만든다.

**Architecture:** 기존 Mega Root #52와 Track #53~#60을 유지하고, 독립 PR로 검증할 수 있는 Leaf 10개만 추가한다. 로컬 JSON을 유일한 발행 입력으로 사용하며 renderer가 문서를 생성하고 publisher가 신규 Issue, 본문, parent, blocked-by 관계를 멱등 동기화한다.

**Tech Stack:** Markdown, JSON Work Graph, Node.js renderer/publisher, Python `spec-to-issues` validator/wave calculator, GitHub CLI.

## Global Constraints

- Spring/FastAPI runtime 코드는 만들지 않는다.
- `docs/toFE/**`는 수정하지 않는다.
- 기존 Mega Root와 Track 번호를 유지한다.
- 신규 Leaf 하나는 한 worktree·한 PR·독립 검증 결과물에 대응한다.
- GitHub Issue는 acceptance criteria와 merge가 확인되기 전 닫지 않는다.
- #49는 이번 문서 PR이 `develop`에 merge되기 전 닫지 않는다.
- ADR-0003~0009는 사용자의 명시적 일괄 승인을 근거로 `accepted`로 전환한다.

---

### Task 1: 승인된 ADR과 API 계약 문구 정합화

**Files:**
- Modify: `docs/decisions/0003-module-boundaries.md`
- Modify: `docs/decisions/0004-postgresql-ownership.md`
- Modify: `docs/decisions/0005-rest-sse-run-lifecycle.md`
- Modify: `docs/decisions/0006-atomic-dataset-publication.md`
- Modify: `docs/decisions/0007-baseline-optional-rag.md`
- Modify: `docs/decisions/0008-opaque-session-guest-grant.md`
- Modify: `docs/decisions/0009-grafana-cloud-observability.md`
- Modify: `docs/spring/catalog-ingestion.md`
- Modify: `docs/contracts/rest-api.md`

**Interfaces:**
- Consumes: 사용자의 ADR 일괄 승인과 `schemaVersion: "1.2"` 계약
- Produces: 구현자가 하나로 해석할 수 있는 accepted ADR 및 구현 전 1.2 계약 상태

- [ ] ADR-0003~0009 front matter의 `status: proposed`를 `status: accepted`로 변경한다.
- [ ] `catalog-ingestion.md` 첫 문단과 API 절의 1.2 상태를 `rest-api.md`와 같은 구현 전 계약으로 바꾼다.
- [ ] `rest-api.md`에서 금지 대상을 Issue 발행이 아니라 관련 기능 구현 착수로 명확히 한다.
- [ ] `rg -n 'status: proposed' docs/decisions/000[3-9]-*.md` 결과가 없음을 확인한다.
- [ ] `git diff --check`를 통과시킨다.
- [ ] `git add docs/decisions docs/spring/catalog-ingestion.md docs/contracts/rest-api.md && git commit -m "docs: accept backend decisions and align api contract"`로 커밋한다.

### Task 2: Work Graph에 신규 Leaf와 dependency 보강

**Files:**
- Modify: `docs/planning/github-issues/work-graph.json`
- Modify: `docs/planning/github-issues/issue-tree.json`
- Modify: `docs/planning/github-issues/requirements-map.md`
- Modify: `docs/planning/work-graph.json`

**Interfaces:**
- Consumes: `F01~O09` stable IDs와 `docs/superpowers/specs/2026-09-14-backend-issue-graph-completion-design.md`
- Produces: F07, F08, F09, D09, P07, M06, M07, M08, M09, J11을 포함하는 canonical DAG

- [ ] 설계 문서의 신규 Leaf 10개를 full issue schema로 작성한다.
- [ ] `issue-tree.json`의 기존 Track에 신규 stable ID를 배치한다.
- [ ] C06, M01, P06, D08, I01, I04~I07, M03, M05, A03, J01, J06, J08, O09 dependency를 설계대로 갱신한다.
- [ ] D01과 O07 acceptance criteria에 DB role 권한 분리와 runtime DDL 금지를 추가한다.
- [ ] `requirements-map.md`에서 BE-REQ-005·010과 계약·보안·운영 요구를 신규 Leaf까지 매핑한다.
- [ ] root `docs/planning/work-graph.json`을 canonical graph와 byte-for-byte 동일하게 동기화한다.
- [ ] Python validator로 duplicate, missing dependency, cycle, XS/XL, conflict candidate를 검사한다.
- [ ] wave calculator로 신규 Wave와 전체 Mermaid가 계산되는지 확인한다.
- [ ] `git diff --check`를 통과시킨다.
- [ ] `git add docs/planning && git commit -m "docs: complete backend implementation issue graph"`로 커밋한다.

### Task 3: Publisher를 native 관계 멱등 동기화로 보강

**Files:**
- Modify: `scripts/publish-backend-issues.mjs`
- Modify: `scripts/render-backend-issue-graph.mjs`

**Interfaces:**
- Consumes: canonical work graph, issue tree, `publication.json`
- Produces: 계산된 본문·Wave와 정확히 같은 GitHub parent/blocked-by 관계

- [ ] publisher에 `--dry-run`과 `--apply` 명시 모드를 추가하고 기본 실행은 원격 변경 없이 종료하게 한다.
- [ ] existing leaf의 현재 blocked-by를 조회해 desired set과 비교하는 함수를 추가한다.
- [ ] `--apply`에서 누락 관계는 `gh issue edit --add-blocked-by`, 제거 관계는 `--remove-blocked-by`로 동기화한다.
- [ ] parent가 다른 신규·기존 Leaf는 `gh issue edit --parent`로 교정한다.
- [ ] renderer가 80개 Leaf와 변경된 Wave·Mermaid·본문 초안을 생성하게 한다.
- [ ] dry-run 출력에 create/update/add/remove 관계 개수를 표시하고 GitHub mutation이 없음을 확인한다.
- [ ] `git diff --check`를 통과시킨다.
- [ ] `git add scripts docs/planning/github-issues/README.md docs/planning/github-issues/issue-drafts.md && git commit -m "build: synchronize github issue graph relationships"`로 커밋한다.

### Task 4: 로컬 Graph 전체 검증

**Files:**
- Modify: `docs/planning/github-issues/README.md`
- Modify: `docs/planning/github-issues/issue-drafts.md`

**Interfaces:**
- Consumes: 갱신된 canonical graph와 renderer
- Produces: GitHub 발행 전 검증된 80-Leaf 문서와 dry-run 증거

- [ ] `node scripts/render-backend-issue-graph.mjs`를 실행한다.
- [ ] `python3 .../validate_work_graph.py --in docs/planning/github-issues/work-graph.json`을 통과시킨다.
- [ ] `python3 .../compute_waves.py --in docs/planning/github-issues/work-graph.json --mermaid`를 실행해 cycle 없는 Wave를 확인한다.
- [ ] `node scripts/publish-backend-issues.mjs --dry-run`에서 신규 10개와 관계 delta가 예상과 일치하는지 확인한다.
- [ ] `jq`로 Leaf 80개, stable ID 80개, priority 합계 80개를 확인한다.
- [ ] `git diff --check`를 통과시킨다.
- [ ] 생성 파일 변경이 있으면 `git add docs/planning/github-issues && git commit -m "docs: render completed backend issue graph"`로 커밋한다.

### Task 5: GitHub 신규 Issue 생성과 기존 관계 동기화

**Files:**
- Modify: `docs/planning/github-issues/publication.json`
- Regenerate: `docs/planning/github-issues/README.md`
- Regenerate: `docs/planning/github-issues/issue-drafts.md`

**Interfaces:**
- Consumes: Task 4에서 검증된 dry-run plan
- Produces: Root #52 아래 총 80개 Leaf와 canonical native dependency graph

- [ ] `node scripts/publish-backend-issues.mjs --apply`를 한 번 실행한다.
- [ ] 같은 명령을 dry-run으로 다시 실행해 create/update/relationship delta가 0인지 확인한다.
- [ ] Root #52, Track #53~#60, 신규 Leaf parent를 GitHub API로 조회한다.
- [ ] 전체 80개 Leaf의 native blocked-by set을 `work-graph.json`과 비교한다.
- [ ] 기존 #52~#130과 신규 Issue 본문의 Dependencies, Blocks, Wave가 canonical graph와 일치하는지 표본 및 자동 비교한다.
- [ ] #49에 신규 Issue Graph 보강 결과와 관련 문서 PR merge 전 Open 유지 조건을 comment한다.
- [ ] `git add docs/planning/github-issues/publication.json docs/planning/github-issues/README.md docs/planning/github-issues/issue-drafts.md && git commit -m "docs: publish completed backend issue graph"`로 커밋한다.

### Task 6: 완료 전 최종 감사

**Files:**
- Verify only: repository and GitHub Issues

**Interfaces:**
- Consumes: 로컬·원격 최종 상태
- Produces: 누락·cycle·관계 drift·문서 충돌이 없는 검증 보고

- [ ] `git status --short --branch`로 예상하지 않은 사용자 변경이 없음을 확인한다.
- [ ] `git diff --check`와 repository documentation checks를 실행한다.
- [ ] ADR-0003~0009가 모두 accepted인지 확인한다.
- [ ] `docs/specs` 절대 symlink 위험이 F07에서 명시적으로 추적되는지 확인한다.
- [ ] BE-REQ-001~010과 NFR이 하나 이상의 구현 Leaf 및 verification에 매핑되는지 다시 감사한다.
- [ ] #49와 신규 Issue를 닫지 않았음을 확인한다.
