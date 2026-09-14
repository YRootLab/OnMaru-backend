# Next 20 Backend Issues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitHub에서 열린 OnMaru backend 이슈 중 의존성이 풀린 약 20개를 source of truth 기준으로 순차 구현, 검증, worklog 작성, PR/merge/issue close까지 진행한다.

**Architecture:** Spring Boot modular monolith가 business truth와 PostgreSQL/PostGIS persistence를 소유하고, FastAPI는 AI service boundary와 guardrail/baseline 기능을 담당한다. 각 이슈는 GitHub Issue 하나, 브랜치 하나, PR 하나를 기본 단위로 하며, 이미 develop에 반영된 이슈는 fresh verification과 issue close 정리로 처리한다.

**Tech Stack:** Java 21, Spring Boot, Gradle, Flyway, PostgreSQL/PostGIS, Testcontainers, Python/FastAPI, uv, OpenAPI/JSON Schema, GitHub Actions.

## Global Constraints

- GitHub Issues are the Source of Truth for triaged, actionable work.
- Integration branch: `develop`; direct pushes to `develop` and `main` are prohibited.
- Every PR must reference its related Issue.
- Use Korean by default for PR bodies, Issue comments, review summaries, work logs, and project documentation.
- For implementation work, use TDD: write a failing test first, verify red, implement minimal code, verify green.
- Before claiming completion, run fresh repository-specific verification.
- After each issue, write or update a Korean worklog in `troubleshooting-worklog/` using `agent-toolkit-skills:troubleshooting-worklog`.
- Automatable checks should be added to CI or repository scripts when the issue introduces a repeatable policy.

---

## Rolling Issue Order

1. #67 D01 - Flyway migration 소유권·버전·baseline 체계 구현
2. #74 AI01 - Spring-FastAPI 내부 계약과 서비스 인증 구현
3. #134 J11 - Journey actions·SavedJourney OpenAPI·fixture 완성
4. #81 O02 - Grafana Cloud dashboard·alert·resolve 통지 구성
5. #82 O07 - Spring·FastAPI container·staging·release/rollback pipeline 구현
6. #75 D02 - Catalog canonical place·source·revision schema 구현
7. #76 D03 - Member·OAuth identity·session·guest grant schema 구현
8. #80 D07 - Sync run·lease·checkpoint·quarantine·outbox schema 구현
9. #77 D04 - Exploration·run·saved journey·saved resource schema 구현
10. #78 D05 - VisitReview·like·report·moderation schema 구현
11. #79 D06 - Odii spot·story·language·transcript revision schema 구현
12. #83 AI02 - FastAPI intake normalization·privacy·safety guardrail 구현
13. #87 I03 - CSRF·cookie·인가·private cache 보안 경계 구현
14. #88 P03 - 03:00 KST sync scheduler·lease·checkpoint 구현
15. #89 P04 - Source validation·category mapping·quarantine 구현
16. #135 D09 - Insights 관측·target link 실행 migration 구현
17. #136 F08 - 공개 API rate-limit·IP/member admission 기반 구현
18. #84 C04 - 월별 한옥 editorial edition·placement API 구현
19. #137 P07 - 행정구역 경계 source qualification·revision import 구현
20. #85 D08 - 전체 migration·동시성·rollback 계약 테스트 완성

이 순서는 2026-09-15 현재 open issue와 `docs/planning/work-graph.json` 의존성을 기준으로 한다. 각 이슈 시작 직전에 `gh issue view`와 open dependency 재계산을 다시 수행한다.

## Per-Issue Execution Template

### Task N: Issue Execution

**Files:**
- Modify: issue-specific files from `expected_touch_points`
- Create or update: `troubleshooting-worklog/yy.mm.dd <issue-slug>.md`
- Possibly modify: `.github/workflows/ci.yml`, `scripts/**`, or test files when automation is part of the issue

**Interfaces:**
- Consumes: closed dependencies from `docs/planning/work-graph.json`
- Produces: verified code/docs/contracts and a GitHub PR that references the issue

- [ ] **Step 1: Re-read issue and dependency state**

Run:

```bash
gh issue view <issue-number> --repo YRootLab/OnMaru-backend --json number,title,state,body,comments
```

Expected: issue is open or already implemented but not closed. If dependencies are still open, skip to the next ready issue.

- [ ] **Step 2: Create or switch to the issue branch**

Run:

```bash
git switch develop
git pull --ff-only origin develop
git switch -c <type>/<issue-number>-<issue-id>-<short-slug>
```

Expected: clean issue branch from latest `develop`.

- [ ] **Step 3: Write the failing test or validation first**

Run the narrow test command for the new behavior. Examples:

```bash
./gradlew :apps:spring-api:test --tests '*SpecificNewTest'
node --test scripts/test/specific-policy.test.mjs
python scripts/test/specific_contract_validation.py
cd ai && uv run pytest tests/test_specific.py -q
```

Expected: FAIL for the intended missing behavior, not syntax or setup failure.

- [ ] **Step 4: Implement the minimal behavior**

Modify only the issue-owned touch points. Keep shared files such as CI workflows, migration registry, and common web primitives scoped to the issue.

- [ ] **Step 5: Verify green locally**

Run the narrow test first, then the relevant package-level command:

```bash
./gradlew check
cd ai && uv run pytest
node --test scripts/test/*.test.mjs
python scripts/validate_contracts.py
```

Expected: all relevant commands exit 0. If a command is not applicable, record why in the worklog.

- [ ] **Step 6: Write troubleshooting worklog**

Create or update:

```text
troubleshooting-worklog/26.09.15 <issue-slug>.md
```

The worklog must include the problem, failure symptom, code context, options, decision, solution, and result.

- [ ] **Step 7: Commit, push, PR, merge, close**

Run:

```bash
git status --short
git add <changed-files>
git commit -m "<type>(<scope>): <Korean subject>"
git push -u origin <branch>
gh pr create --repo YRootLab/OnMaru-backend --base develop --head <branch> --title "<Korean PR title>" --body-file <pr-body-file>
```

After checks pass and merge is allowed:

```bash
gh pr merge <pr-number> --repo YRootLab/OnMaru-backend --squash --delete-branch
gh issue close <issue-number> --repo YRootLab/OnMaru-backend --comment "<verification summary>"
git switch develop
git pull --ff-only origin develop
```

Expected: PR merged to `develop`, issue closed only after acceptance criteria are verified.

## Branch Naming

- `feature/67-d01-flyway-migration-baseline`
- `feature/74-ai01-internal-ai-contract-auth`
- `docs/134-j11-journey-actions-saved-contract`
- `ops/81-o02-grafana-dashboard-alerts`
- `ops/82-o07-container-staging-release-pipeline`
- `feature/75-d02-catalog-revision-schema`
- `feature/76-d03-identity-session-schema`
- `feature/80-d07-sync-operations-schema`
- `feature/77-d04-journey-saved-resource-schema`
- `feature/78-d05-visit-review-schema`
- `feature/79-d06-odii-audio-schema`
- `feature/83-ai02-intake-guardrails`
- `feature/87-i03-csrf-cookie-auth-boundary`
- `feature/88-p03-sync-scheduler-lease`
- `feature/89-p04-source-validation-quarantine`
- `feature/135-d09-insights-migration`
- `feature/136-f08-api-admission-rate-limit`
- `feature/84-c04-monthly-editorial-api`
- `feature/137-p07-region-boundary-import`
- `feature/85-d08-migration-concurrency-contracts`

## Self-Review

- Spec coverage: the plan covers the next ready gate (#67), currently ready independent work (#74, #81, #82, #134), and the DB/platform chain that unlocks product implementation.
- Placeholder scan: no task uses TBD/TODO/fill-in placeholders; issue-specific commands are parameterized by issue number because the same required lifecycle repeats.
- Type consistency: branch names and issue IDs match GitHub titles and `work-graph` IDs.
