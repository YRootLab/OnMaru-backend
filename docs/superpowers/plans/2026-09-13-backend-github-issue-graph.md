# Backend GitHub Issue Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FE 전용 구현을 제외한 OnMaru backend 문서 요구사항을 우선순위·의존성·병렬 안전성이 명확한 GitHub Issue Tree로 등록한다.

**Architecture:** Mega Root 아래 8개 Track control issue를 두고, 각 Track 아래에 독립 PR 단위 Leaf를 둔다. 실행 순서는 Leaf 간 blocked-by DAG가 결정하고, Wave와 priority는 서로 다른 축으로 기록한다.

**Tech Stack:** GitHub Issues/Sub-Issues, blocked-by 관계, `gh` CLI, JSON Work Graph, Python graph validator, Mermaid.

## Global Constraints

- `docs/toFE/**`의 FE 구현 작업은 제외한다.
- backend OpenAPI, fixture, serializer contract와 FE 호환성 검증은 포함한다.
- 기존 Issue #49는 이번 계획과 Issue 발행을 추적하며, 관련 PR merge 전 닫지 않는다.
- 기존 labels를 재사용하고 priority label은 실제 생성 전 사용자 확인 목록에 포함한다.
- Mega Root와 Track은 control plane이고 Leaf 하나는 한 세션·한 worktree·한 PR에 대응한다.

---

### Task 1: 요구사항 Coverage Map 작성

**Files:**
- Create: `docs/planning/github-issues/requirements-map.md`

- [ ] 최신 PRD, architecture, Spring, AI, database, contracts, operations, ADR을 구현 책임 단위로 추출한다.
- [ ] archived 문서와 FE-only 작업을 제외하고 이유를 기록한다.
- [ ] BE-REQ-001~010과 NFR이 최소 하나의 Leaf에 매핑되는지 확인한다.

### Task 2: Tree와 Leaf Work Graph 작성

**Files:**
- Create: `docs/planning/github-issues/issue-tree.json`
- Create: `docs/planning/github-issues/work-graph.json`

- [ ] Mega Root, Track 8개, Leaf를 안정적인 ID로 작성한다.
- [ ] 각 Leaf에 priority, dependencies, touch points, acceptance criteria, verification을 기록한다.
- [ ] 공통 Gradle/OpenAPI/migration 파일의 단일 소유자를 정한다.

### Task 3: Graph 검증과 Issue 본문 생성

**Files:**
- Create: `docs/planning/github-issues/README.md`
- Create: `docs/planning/github-issues/issue-drafts.md`

- [ ] `validate_work_graph.py`로 cycle, 중복, 누락 필드, conflict 후보를 검사한다.
- [ ] `compute_waves.py --mermaid`로 전체·로컬 DAG를 계산한다.
- [ ] 각 Track/Leaf 본문에 Ready/Waiting 판정, blocked-by, blocks, local graph를 포함한다.

### Task 4: 사용자 생성 게이트와 GitHub 등록

**Files:**
- Modify: `docs/planning/github-issues/README.md`
- Modify: `docs/planning/github-issues/issue-tree.json`

- [ ] 전체 제목·Track·priority·Wave·dependency 목록을 사용자에게 보여준다.
- [ ] 확인 후 Root → Track → Leaf 순서로 생성하고 native Sub-Issue 관계를 연결한다.
- [ ] blocked-by/Blocking 관계와 실제 Issue 번호를 본문 및 manifest에 반영한다.
- [ ] Root에서 모든 하위 Issue와 상태를 조회할 수 있는지 재검증한다.

### Task 5: 계획 Issue #49와 로컬 문서 정합성 확인

**Files:**
- Modify: `docs/planning/README.md`
- Modify: `docs/planning/implementation-issues.md`
- Modify: `docs/planning/work-graph.json`

- [ ] 오래된 W/X graph가 현재 구현 기준으로 오해되지 않게 새 graph로 연결하거나 교체한다.
- [ ] #49 Acceptance Criteria와 관련 PR/merge 조건을 기록한다.
- [ ] 문서 링크, JSON, graph validator, `git diff --check`를 통과시킨다.
