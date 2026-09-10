# handoff.md

## Active Discussion - 2026-09-09

- Current workspace: `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/prd-planning-2`; branch: `feature/onmaru-be-prd-planningv2-2`. The snapshot below describes the earlier session.
- User requested a critical discussion of Cline's story-to-place proposal for journey exploration and Odii AI docent, including stronger alternatives. This is product exploration, not approval to implement or finalize the proposal.
- Review focus: distinguish a useful interaction from defensible advantage; verify competitor claims; compare story-to-place recommendations with question-driven, on-site observation and follow-up exploration.
- Still unresolved: target use moment (before a visit or on site), pilot content coverage, and whether the proposed experience improves actual exploration over a static curated guide. New alternatives remain unvalidated hypotheses.
- At the user's request, saved the critique, alternatives, proposed flow, and validation questions in `docs/planning/journey-exploration/kick-brief.md`; the brief remains unapproved product exploration.
- User subsequently endorsed continuity from pre-visit web exploration to on-site web/app use and explicitly requested login/member features in the plan. User also asked to clarify a broader journey exploration page using OnMaru data and its relationship to Odii.
- Added `docs/planning/journey-exploration/journey-service-plan.md`: proposed shared exploration workspace, lightweight Odii entry, multi-source discovery, focused relation view, member save/resume/share, and JE-01~07 acceptance candidates. Updated planning entry links and the previous account-management deferral. Detailed policies remain proposals; no runtime, schema, or GitHub Issue changes were made.
- Tracking is provisionally interpreted as explicit exploration/selection history. GPS route tracking remains unconfirmed. Pilot region/tasks, auth provider, retention, AI quotas, app technology, and detailed UX still need decisions; the earlier on-site-only validation suggestion has been broadened to pre-visit and on-site use.
- 2026-09-10: User requested continued review. Cross-checked the journey/member proposal against API and issue planning; documented ANSWER-only results, apply vs durable save, explicit add/remove commands, anonymous-to-member copy after run completion, supported graph refs, sharing boundaries, and separate playback concurrency. Updated `fe-api-handoff.md` and JE-to-W/X impact mapping as proposals. OpenAPI, runtime tests, unified work graph, and actual Issues remain outstanding.
- 2026-09-10 MVP decision: about seven days remain. User chose to implement the journey exploration experience before Odii-specific AI and selected Kakao Login as the sole MVP login provider. Added `seven-day-mvp-fe-handoff.md` with the landing-to-workspace transition, maximum three candidates, pin-preserving proposal flow, small relation view, responsive FE states, Spring/FastAPI boundary, Kakao save flow, seven-day split, acceptance checks, and explicit exclusions. Odii AI, multi-agent, live warmth, sharing, GPS, and native app are outside submission P0.
- 2026-09-10 contract refinement: User requested a concrete transport choice and sendable data types. The seven-day handoff now selects REST command + 1-second polling + snapshot instead of SSE for submission, defines browser endpoints, request/response examples, FE TypeScript types, error enums, source-to-field availability, Spring-to-FastAPI allowlisted candidate documents, timeouts, retry rules, and no-broker execution. Kakao Login is confirmed as missing current user-facing implementation and remains P0. OpenAPI/JSON Schema files still need to be generated from the reviewed contract.
- 2026-09-10 journey-flow decision: User approved a horizontal journey flow without an MVP routing engine. AI proposes an evidence-bounded story order; Spring calculates straight-line distance between adjacent canonical coordinates and returns `JourneyLeg`; FE renders `NEAR / MEDIUM / FAR / UNKNOWN` as bounded connector lengths. The UI must label this as relative or straight-line distance, never as an optimal route, walking distance, or walking time. Actual route calculation and map polylines remain excluded.
- 2026-09-10 FE report request: Added `fe-experience-api-implementation-report.md` as the concrete FE/BE handoff for vertical conversation plus horizontal journey rails, proposal comparison, responsive behavior, API-to-component mapping, recent history, ownership boundaries, failure recovery, and acceptance checks. ADR Toolkit preflight passed, no related ADR was found, and the Spring-owned typed snapshot/AI proposal boundary scored 14 (`recommended`). The ADR itself still requires the toolkit's explicit draft approval before creation.
- 2026-09-10 save/export/share roadmap: User fixed the order as Kakao-authenticated server save/reopen first, anonymous browser PDF print/file save if time remains, then authenticated read-only share snapshots. PDF uses a print layout rather than a submission-time server renderer. Share URLs must not expose guest exploration ownership or private questions/history, and remain outside P0 until save/reopen is stable.
- 2026-09-10 environment decision: User chose real-use staging/prod as the product target while retaining a demo environment for live-judging contingencies. Added `staging-demo-release-and-success-gates.md`: staging uses live canonical data, live AI, and real Kakao flow; demo uses the same release artifact/OpenAPI with a verified real-place snapshot, deterministic adapter, isolated DB/Kakao test app, and visible disclosure. Product success metrics exclude demo results; separate rehearsal, contract-equivalence, credential, and data-isolation gates apply.

## Session Snapshot

- Date: 2026-09-09 KST.
- Repository: `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/develop`.
- Current branch: `feature/prd-mvp-implements`.
- Git Flow target: create a PR from `feature/prd-mvp-implements` into `develop`.
- Production branch recorded in `AGENTS.md`: `main`.
- Current work type: backend planning, architecture blueprint, operating harness setup, and documentation. No backend application source code has been scaffolded yet.

## Completed Work

- Added local backend reference links under `docs/`:
  - `docs/backend_schema_design_guide.md` points to `/Users/yangseunghyeon/Development/OnMaru/OnMaru-docs/backend_schema_design_guide.md`.
  - `docs/specs` points to `/Users/yangseunghyeon/Development/OnMaru/OnMaruFE/docs/specs`.
- Initialized project operating docs and agent handoff conventions:
  - `AGENTS.md`, `CODEX.md`, `CLAUDE.md`, `GEMINI.md`, `CLINE.md`.
  - `project-roadmap.md`, `improvements.md`, `handoff.md`, `CHANGELOG.md`.
- Added GitHub workflow scaffolding:
  - `.github/workflows/ci.yml`
  - `.github/workflows/release-please.yml`
- Added ADR toolkit bootstrap:
  - `.adr-toolkit.json`
  - `docs/decisions/README.md`
  - `docs/decisions/adr-template.md`
  - `docs/decisions/0001-record-architecture-decisions.md`
- Prepared backend planning artifacts under `docs/planning/`:
  - `README.md`
  - `architecture-blueprint.md`
  - `backend-prd.md`
  - `data-api-design.md`
  - `issue-plan.md`
  - `work-graph.json`
  - `adr-proposals.md`
  - `adr-scores/*.json`
- Added expanded journey exploration planning under `docs/planning/journey-exploration/`:
  - FE source audit.
  - Brainstorming plus Red/Blue review.
  - Story-linked exploration PRD.
  - Recommendation, Hugging Face candidate, RAG, LangGraph, and harness architecture notes.
  - FE API and SSE handoff draft.
  - 2026 contest scoring review.
  - Implementation impact and issue candidates.
- Updated `docs/database/schema.md` with successor planning context.

## Plugin State

- Local Codex plugin is updated and enabled:
  - Plugin: `agent-toolkit-skills@personal`.
  - Version: `0.3.20+codex.20260909022246`.
  - Enabled skills: 29.
  - Installed skill files checked: 231 files matched local source.
  - Cache root: `/Users/yangseunghyeon/.codex/plugins/cache/personal/agent-toolkit-skills/0.3.20+codex.20260909022246`.
- New sessions should discover the updated plugin automatically.
- `adr-toolkit` is a separate local skill, not part of the `agent-toolkit-skills` plugin bundle.

## Architecture Decisions Drafted, Not Yet Final

- Recommended baseline: Modular Monolith + selective Hexagonal Architecture + Lightweight DDD + Gradle Multi-Module.
- Spring Boot owns business APIs and business transactions.
- FastAPI is treated as an external AI/data-processing system behind Spring output ports.
- PostgreSQL/PostGIS is the current relational database recommendation.
- pgvector and AI indexing are proposed for later stages after source qualification.
- Redis, broker, Neo4j, MSA, and heavy graph database adoption are deferred until evidence justifies them.
- ADR proposals exist in planning docs, but only ADR-0001 process initialization has been formally recorded.

## FE And Data Findings To Preserve

- FE docs and source must remain backend input because they include contracts, wireframes, class diagrams, and current data assumptions.
- FE audit found these important backend implications:
  - `/discover` currently uses local fixed mood plans and timers, not real AI.
  - Graph positions are visual percentages, not geographic coordinates.
  - TOP5 and weekly labels are not backed by verified server-side ranking evidence yet.
  - Hanok monthly content can mismatch fallback place data if source IDs are not normalized.
  - Odii ask API currently expects `{ question, filters }`, so new selected-story APIs must preserve compatibility or provide a clear version.
  - Warmth is currently seeded and localStorage-based; server-side warmth needs truthful provenance.
  - Visitor data is regional and delayed; it must not be described as live POI congestion.
  - A hardcoded FE service-key fallback was observed. Do not copy the key into docs or logs; handle removal and rotation as a separate security task.

## Journey Exploration Proposal

- Working product direction: "이야기길" for the `/discover` experience.
- Core user flow: listen to an Odii or editorial story, discover grounded nearby places, pin one place, then replace alternatives without losing the selected place.
- UI contract direction:
  - Horizontal alternatives.
  - Vertical selected detail.
  - Map as geography.
  - Relation graph as explanation and story connection.
  - Typed UI blocks from backend, not arbitrary frontend code generation.
- Backend direction:
  - Spring creates canonical sessions and validates published content.
  - Python handles bounded AI planning, retrieval, embedding, and LangGraph execution.
  - Spring hydrates AI results against current canonical data before returning to FE.
  - SSE streams progress and typed blocks, with snapshot and replay support.

## Issue Planning State

- Existing `docs/planning/work-graph.json` covers W0-W11 only.
- Journey exploration introduced X0-X8 issue candidates, but they are not merged into `work-graph.json` yet.
- No GitHub Issues have been created from the graph yet.
- No PR had been created before this handoff update.
- Before implementation starts, unify W and X issue candidates, remove overlaps, recalculate dependency waves, then create GitHub Issues with `spec-to-issues`.

## Verification Already Done

- Plugin export and validation completed in the earlier session.
- Planning JSON and Markdown sanity checks were run earlier for the expanded journey docs.
- Existing W graph was checked earlier: no graph errors, but five shared build-file conflict warnings were noted.
- Branch protection could not be verified through the GitHub API because this private repository plan blocks that endpoint.
- Current repository still has no backend build manifest, so CI is limited to documentation and repository hygiene checks.

## Known Gaps

- Two user-mentioned external reference materials were not provided in the session and have not been reviewed.
- Contest track is assumed to be the 2026 development track, but the user must confirm the actual submission track.
- Team, budget, auth provider, deployment target, API quota, data license review, and model budget are unresolved.
- No runtime backend, database migration, OpenAPI generation, SSE server, FastAPI service, model inference, or frontend browser integration test exists yet.
- The expanded FE API handoff is a prose draft, not a validated OpenAPI schema.

## Restart Order For Next Session

1. Run `git status --short --branch` and confirm branch `feature/prd-mvp-implements`.
2. Read `AGENTS.md`, `docs/planning/README.md`, and this `handoff.md`.
3. Confirm whether the user wants to keep the current planning commit as-is or split it into smaller commits.
4. Confirm missing contest track and the two missing reference materials.
5. Unify W0-W11 and X0-X8 into one implementation issue graph.
6. Review and approve ADR proposals before locking architecture decisions.
7. Use `spec-to-issues` to create implementation issues.
8. Start backend scaffold only after W0 source/contracts/auth qualification is clear.

## Git Flow PR Preparation

- Commit this documentation/planning state from `feature/prd-mvp-implements`.
- Push the branch to origin.
- Open a PR into `develop`.
- PR body should state that this is a planning/harness PR, not a backend runtime implementation.
- Required checks should pass before merge.
