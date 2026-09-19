# OnMaru Backend Agent Guide

## Project Context

- Project: OnMaru backend.
- Current repository shape: documentation-first backend repository with initial Spring Boot and FastAPI runtime scaffolds.
- User-selected stack (2026-09-09): Java/Spring Boot for business APIs and Python/FastAPI for AI. Older Node.js guidance is historical.
- Architecture planning entry point: `docs/planning/README.md`. Recommendations remain proposals until the corresponding decision is approved.
- Canonical backend references:
  - `docs/backend_schema_design_guide.md`
  - `docs/specs`
  - `docs/database/schema.md`
  - `docs/api/**`
  - `docs/decisions/**`
- Treat frontend specs in `docs/specs` as required backend input because they include FE contracts, wireframes, class diagrams, and backend requirements.

## Language Policy

- Use Korean by default for Pull Request titles and bodies, GitHub Issue descriptions, review summaries, work logs, and project documentation.
- Keep explanations natural and readable in Korean instead of translating word-for-word from English templates.
- English is allowed for Conventional Commit types such as `feat`, `fix`, and `docs`, as well as technical keywords, skill names, library/product names, code identifiers, commands, API fields, and standards terminology.
- Conventional Commit subjects may be written in Korean after the English type and optional scope, for example `feat(api): 장소 조회 API 구현`.
- Existing historical English documents do not need bulk translation unless the current task materially edits them.

## Source Of Truth

- GitHub Issues are the Source of Truth for triaged, actionable work.
- Before starting non-trivial work, search existing open and closed Issues. If no suitable Issue exists, create one with context, proposed change, and acceptance criteria before branching.
- Keep `project-roadmap.md` limited to long-term vision and milestone-level goals.
- Use `handoff.md` for current-session continuity and immediate restart context.
- Use `improvements.md` for untriaged follow-up ideas.
- Do not treat `handoff.md` or `improvements.md` as a permanent backlog.
- During triage, keep a local note, link it to an existing Issue, promote it to a new Issue, or remove it only when completion is verified.
- Every PR must reference its related Issue. Use auto-close keywords only when merging that PR should actually close the Issue.
- Before closing an Issue, verify its acceptance criteria and merge state.
- GitHub auto-close is not sufficient under this Git Flow. The repository default branch is `main`, while normal work PRs merge into `develop`; `Closes #...` may not close Issues until the change reaches the default branch.
- After a PR into `develop` is merged, explicitly check related Issues with `gh issue view <number> --json state` if the post-merge workflow did not run or did not close them. Close manually only when the acceptance criteria, merged PR, and verification commands all match.
- When closing manually, leave a comment that names the merged PR, explains why auto-close did not apply, and records the verification commands.
- Prefer the post-merge Issue Reconcile workflow for this check. It may close only still-open Issues referenced by `Closes/Fixes/Resolves #...` after the PR has actually merged into `develop`, and it must leave a comment explaining the `develop` versus `main` auto-close limitation.

## Work Logs And Cleanup

- Work branches should include the related Issue number as `feature/<issue-number>-short-name`, `fix/<issue-number>-short-name`, `docs/<issue-number>-short-name`, or `hotfix/<issue-number>-short-name`.
- Use `node scripts/print-branch-issue.mjs` to parse the current branch and confirm the related Issue before PR creation. Long-lived branches such as `develop`, `main`, and `release/*` intentionally print nothing.
- Immediately before creating a Pull Request, reconcile branch name, touched files, work logs, related Issues, PR body, and verification results.
- Immediately before merge, repeat cleanup because review may change scope, follow-ups, or Issue state.
- Immediately after merge, reconcile GitHub Issue state for every referenced `Closes/Fixes/Resolves #...` line. If the PR merged to `develop` and the Issue remains open, decide whether to close manually or leave it open for a later `main` release, then record the reason in the Issue comment or `handoff.md`.
- If an agent harness cannot invoke `cleaning-work-logs`, perform the equivalent scan, classification, approval, and verification manually.
- Record ad hoc user requests immediately in `handoff.md` when they affect the current session, or `improvements.md` when they are follow-up ideas.

## Git Flow Branch Policy

- Integration branch: `develop`.
- Production branch: `main`.
- Work branches: `feature/*`, `fix/*`, and `docs/*` merge into `develop` by Pull Request.
- Release branches: `release/*` merge into `main` and back into `develop`.
- Emergency fixes: `hotfix/*` merge into `main` and back into `develop`.
- Direct pushes to `develop` and `main` are prohibited.
- Required CI check: `verify` from the `CI` workflow must pass before every merge.
- Work branch names must carry the Issue number and pass the branch parser contract unless the branch is a long-lived integration, production, release, or externally managed branch.
- Delete short-lived branches after merge.
- Create semantic version tags such as `v0.3.2` only from `main`.

## Release Policy

- Release mode: Release Please release PR.
- Release source: `main` after a successful release merge.
- Commit style: Conventional Commits for release note and version inference.
- Changelog: `CHANGELOG.md`.
- Release tags: semantic version tags such as `v0.3.2`.
- Release flow: accumulate feature/fix/docs PRs in `develop`, cut `release/<version>` from `develop`, merge the release branch into `main`, let Release Please create or update release metadata, and merge `main` back into `develop`.
- Release workflows must use least-privilege permissions and concurrency controls.
- Generated artifacts must be rebuilt and verified before release when the project starts generating code, schema, SDKs, or bundles.
- CI must pass before any tag or GitHub Release is created.

## GitHub Controls

- Require Pull Requests before merging to `develop` and `main`.
- Require the `verify` CI status check.
- Require at least one approval for shared repository changes.
- Restrict force pushes and protected branch deletion.
- The GitHub API could not read branch protection for this private repository under the current plan. Treat the workflow checks and documented PR policy as compensating controls until protection can be verified in GitHub settings.

## Project Harness Lifecycle

- Keep `README.md` for product overview and local setup, `project-roadmap.md` for durable vision and milestones, GitHub Issues for executable work, PRs for reviewed changes, and `handoff.md`/`improvements.md` for local capture only.
- Use `handoff.md` for active session restart context: branch, Issue, touched files, verification, next step, and open risk.
- Use `improvements.md` for vague or untriaged follow-up ideas. During cleanup, classify each item as local keep, existing Issue link, new Issue promotion, or verified completion removal.
- Do not copy operating rules into `CODEX.md`, `CLAUDE.md`, `GEMINI.md`, or `CLINE.md`; those files should remain thin pointers to `AGENTS.md`.

## Architecture Decisions

- Use ADRs under `docs/decisions` for long-lived structural decisions.
- Do not put routine implementation details, commit-level rationale, or temporary task notes in ADRs.
- Before a planning-stage architecture decision, inspect existing docs, specs, code, workflows, and related ADRs.
- Separate observed facts, assumptions, recommendation, rejected options, and validation signals.
- Prefer modular monolith boundaries until ownership, scaling, deployment, reliability, or regulatory constraints justify service extraction.

## Backend Design Rules

- Start backend design from `docs/backend_schema_design_guide.md` and `docs/specs`.
- Preserve FE/BE traceability from `docs/specs/traceability/fe-be-traceability-matrix.md` when defining APIs, persistence, and sync jobs.
- Model database access, authentication, and secret boundaries explicitly. PostgreSQL is the current recommendation; Supabase hosting/Auth is not yet selected. If selected, specify RLS and service-role isolation.
- External API integrations must define timeout, retry, rate-limit, idempotency, pagination, response validation, and sync observability behavior.
- Schema changes must update `docs/database/schema.md` or a successor database design document in the same PR.

## Verification

- Run repository-specific verification before claiming completion.
- Until CI Issue #69 is implemented, use the CI workflow's documentation and repository hygiene checks plus local Gradle and Python verification as the baseline.
- Expand CI with lint, typecheck, tests, migration validation, and OpenAPI/schema validation through Issue #69 now that the backend application scaffold exists.
