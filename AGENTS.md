# OnMaru Backend Agent Guide

## Project Context

- Project: OnMaru backend.
- Current repository shape: documentation-first backend planning repository.
- User-selected stack (2026-09-09): Java/Spring Boot for business APIs and Python/FastAPI for AI. Older Node.js guidance is historical.
- Architecture planning entry point: `docs/planning/README.md`. Recommendations remain proposals until the corresponding decision is approved.
- Canonical backend references:
  - `docs/backend_schema_design_guide.md`
  - `docs/specs`
  - `docs/database/schema.md`
  - `docs/api/**`
  - `docs/decisions/**`
- Treat frontend specs in `docs/specs` as required backend input because they include FE contracts, wireframes, class diagrams, and backend requirements.

## Source Of Truth

- GitHub Issues are the Source of Truth for triaged, actionable work.
- Keep `project-roadmap.md` limited to long-term vision and milestone-level goals.
- Use `handoff.md` for current-session continuity and immediate restart context.
- Use `improvements.md` for untriaged follow-up ideas.
- Do not treat `handoff.md` or `improvements.md` as a permanent backlog.
- During triage, keep a local note, link it to an existing Issue, promote it to a new Issue, or remove it only when completion is verified.
- Every PR must reference its related Issue. Use auto-close keywords only when merging that PR should actually close the Issue.
- Before closing an Issue, verify its acceptance criteria and merge state.

## Work Logs And Cleanup

- Immediately before creating a Pull Request, reconcile branch name, touched files, work logs, related Issues, PR body, and verification results.
- Immediately before merge, repeat cleanup because review may change scope, follow-ups, or Issue state.
- If an agent harness cannot invoke `cleaning-work-logs`, perform the equivalent scan, classification, approval, and verification manually.
- Record ad hoc user requests immediately in `handoff.md` when they affect the current session, or `improvements.md` when they are follow-up ideas.

## Git Flow Branch Policy

- Integration branch: `develop`.
- Production branch: `main`.
- Work branches: `feature/*`, `fix/*`, and `docs/*` merge into `develop` by Pull Request.
- Release branches: `release/*` merge into `main` and back into `develop`.
- Emergency fixes: `hotfix/*` merge into `main` and back into `develop`.
- Direct pushes to `develop` and `main` are prohibited.
- Required CI checks must pass before every merge.
- Delete short-lived branches after merge.
- Create semantic version tags such as `v0.3.2` only from `main`.

## Release Policy

- Release mode: Release Please release PR.
- Release source: `main` after a successful release merge.
- Commit style: Conventional Commits for release note and version inference.
- Changelog: `CHANGELOG.md`.
- Release tags: semantic version tags such as `v0.3.2`.
- Release workflows must use least-privilege permissions and concurrency controls.
- Generated artifacts must be rebuilt and verified before release when the project starts generating code, schema, SDKs, or bundles.
- CI must pass before any tag or GitHub Release is created.

## GitHub Controls

- Require Pull Requests before merging to `develop` and `main`.
- Require the CI status check.
- Require at least one approval for shared repository changes.
- Restrict force pushes and protected branch deletion.
- The GitHub API could not read branch protection for this private repository under the current plan. Treat the workflow checks and documented PR policy as compensating controls until protection can be verified in GitHub settings.

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
- While this repository has no backend build manifest, use the CI workflow's documentation and repository hygiene checks as the baseline.
- Expand CI with lint, typecheck, tests, migration validation, and OpenAPI/schema validation as soon as the backend application scaffold exists.
