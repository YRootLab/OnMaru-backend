# improvements.md

Untriaged follow-up ideas captured during work. GitHub Issues are the source of truth after triage; this file is only a temporary inbox for the next session.

## P0 - Must Resolve Before Backend Implementation

2026-09-09 triage: [P0 분류와 남은 증거](docs/planning/p0-triage.md), [통합 후보 본문](docs/planning/implementation-issues.md). 범위/자료는 사용자 입력 대기, snapshot은 원본 dirty 상태 때문에 미완료, 보안은 FE 제거·회전 증거 대기, ID는 D3 승인·fixture 대기다. Follow-up PR tracking Issue: #49. 발행 후 W0/X0 후보를 실제 Issue 링크로 교체한다.

- [ ] Confirm contest and planning scope.
  - Context: The planning assumes the 2026 Korea Tourism Organization tourism data contest development track, but the user has not confirmed the actual track.
  - Acceptance: Track, deadline, judging emphasis, team capacity, budget, and demo target are recorded in `docs/planning/README.md`.
  - Suggested labels: `needs-triage`, `type:planning`, `priority:p0`
  - Issue: none

- [ ] Review the two missing user reference materials.
  - Context: The user mentioned two additional materials, but they were not attached or otherwise available in this session.
  - Acceptance: Both materials are linked or copied into the planning references, and their impact on PRD/API/modeling is summarized.
  - Suggested labels: `needs-triage`, `type:research`, `priority:p0`
  - Issue: none

- [x] Create a reproducible FE and external-doc reference snapshot.
  - Context: `docs/specs` and `docs/backend_schema_design_guide.md` are local symlinks. They are useful locally but not reproducible for CI or another machine unless source commit/path metadata is captured.
  - Acceptance: FE commit hash, source paths, snapshot policy, and generated contract ownership are documented.
  - Suggested labels: `needs-triage`, `type:docs`, `priority:p0`
  - Issue: #131
  - Resolution: implemented in `docs/reference-snapshots/planning-inputs`, `scripts/verify-planning-inputs.mjs`, and CI; close via PR for #131.

- [ ] Handle the observed FE hardcoded service-key fallback.
  - Context: FE source audit found a hardcoded public-data service-key fallback. Do not reproduce the key in docs, logs, commits, or issue bodies.
  - Acceptance: Exposure is assessed, key rotation is decided if required, and FE/backend secret loading policy is documented.
  - Suggested labels: `needs-triage`, `type:security`, `priority:p0`
  - Issue: none

- [ ] Normalize source IDs and provenance before schema implementation.
  - Context: Tourism API, Odii, FE mocks, editorial content, and public data may use incompatible IDs and region names.
  - Acceptance: Canonical ID policy, provider composite key policy, source freshness, and provenance fields are defined before migrations.
  - Suggested labels: `needs-triage`, `type:data`, `priority:p0`
  - Issue: none

- [x] Merge W0-W11 and X0-X8 into one implementation graph (local preparation only).
  - Context: `docs/planning/work-graph.json` only contains the baseline W issues; journey exploration produced additional X candidates.
  - Acceptance: One acyclic graph exists, overlaps are removed, wave order is recalculated, and shared-file ownership warnings are either resolved or intentionally serialized.
  - Suggested labels: `needs-triage`, `type:planning`, `priority:p0`
  - Issue: none

## P1 - High Value After Scope Is Confirmed

- [ ] Re-evaluate the public Gemini usage policy with measured quota and abuse evidence.
  - Context: The provisional MVP policy is two AI journey runs per KST day for guests and five for signed-in members. AI requires explicit opt-in; baseline exploration remains available after exhaustion or provider failure.
  - Acceptance: Provider quota, latency, failure rate, opt-in conversion, abuse signals, and user value are measured; then decide whether to retain limits, add a paid tier, or keep AI demo-only.
  - Suggested labels: `needs-triage`, `type:ai`, `type:product`, `priority:p1`
  - Issue: none

- [ ] Expand CI after backend scaffold exists.
  - Context: Spring and FastAPI manifests now exist, while CI still runs repository hygiene only. The next CI change belongs to the published contract/build verification work.
  - Acceptance: CI runs Gradle build/test, Python test/lint if FastAPI exists, architecture tests, migration validation, and OpenAPI/schema validation.
  - Suggested labels: `needs-triage`, `type:chore`, `priority:p1`
  - Issue: #69

- [x] Formalize architecture rules with ArchUnit and Gradle boundaries.
  - Context: Planning requires domain code to avoid Spring/JPA/adapter dependencies, but no code-level enforcement exists yet.
  - Acceptance: Architecture tests fail on forbidden domain/application/adapter dependencies.
  - Suggested labels: `needs-triage`, `type:architecture`, `priority:p1`
  - Issue: #68
  - Resolution: implemented with ArchUnit tests under `apps/spring-api/src/test/java/com/yrootlab/onmaru/architecture`; PR closes #68.

- [ ] Convert prose FE API handoff into versioned OpenAPI and SSE schemas.
  - Context: Journey exploration API handoff is descriptive, not a validated contract.
  - Acceptance: REST endpoints, SSE events, error envelopes, snapshot/replay behavior, and compatibility with existing `/api/odii/ask` are schema-checked.
  - Suggested labels: `needs-triage`, `type:api`, `priority:p1`
  - Issue: none

- [ ] Implement server-side editorial and weekly placement model.
  - Context: FE currently has weekly labels and curated content, but there is no backend-owned publication model.
  - Acceptance: Editorial article, placement, publish window, source relation, and audit fields are designed and exposed.
  - Suggested labels: `needs-triage`, `type:feature`, `priority:p1`
  - Issue: none

- [ ] Define truthful warmth and popularity metrics.
  - Context: Warmth and weekly TOP5 must distinguish public data, editorial choice, user behavior, and derived estimates.
  - Acceptance: Each metric has source, freshness, calculation date, confidence, and missing-data behavior.
  - Suggested labels: `needs-triage`, `type:data`, `priority:p1`
  - Issue: none

- [ ] Evaluate Korean embedding and reranking models.
  - Context: Hugging Face and managed models are candidates, but no benchmark has been run.
  - Acceptance: Representative Korean tourism queries are evaluated against E5-small, BGE-M3, reranker options, and any managed provider candidate with latency/cost/license notes.
  - Suggested labels: `needs-triage`, `type:ai`, `priority:p1`
  - Issue: none

- [ ] Design bounded LangGraph harness and AI run lifecycle.
  - Context: The product needs graph-linked natural language discovery, not a free-form chatbot.
  - Acceptance: Tool allowlist, auth context, run state, checkpointing, idempotency, timeout, budget limit, repair limit, and observability are defined.
  - Suggested labels: `needs-triage`, `type:ai`, `priority:p1`
  - Issue: none

- [ ] Build FE integration fixtures for journey exploration.
  - Context: FE needs stable typed blocks, SSE examples, map cards, selected-place vertical expansion, and relation graph payloads before backend is complete.
  - Acceptance: Fixture payloads cover loading, partial stream, committed board, error, replay, and empty states.
  - Suggested labels: `needs-triage`, `type:frontend-contract`, `priority:p1`
  - Issue: none

- [ ] Prepare contest demo evidence.
  - Context: Current score review is internal planning only and does not prove user value.
  - Acceptance: 90-second demo journey, before/after comparison, public data usage explanation, and at least five formative user-test notes are prepared.
  - Suggested labels: `needs-triage`, `type:product`, `priority:p1`
  - Issue: none

## P2 - Defer Until Evidence Exists

- [ ] Add real qualified weekly popularity.
  - Context: Weekly TOP5 should not be claimed until there is enough behavioral data or a defensible public-data proxy.
  - Acceptance: Minimum sample threshold, fallback behavior, anti-gaming rule, and editorial override labeling are defined.
  - Suggested labels: `needs-triage`, `type:data`, `priority:p2`
  - Issue: none

- [ ] Explore the two-era experimental journey UI.
  - Context: A more experimental before/after or era-linked map view may differentiate the product, but it can distract from MVP completion.
  - Acceptance: Prototype only after the core story-linked journey is demonstrable.
  - Suggested labels: `needs-triage`, `type:ux`, `priority:p2`
  - Issue: none

- [ ] Revisit Redis, broker, Neo4j, and service extraction.
  - Context: These can improve scale or graph exploration later, but initial adoption would add operational load before evidence exists.
  - Acceptance: Add only when latency, concurrency, graph traversal, async reliability, or team ownership requires it.
  - Suggested labels: `needs-triage`, `type:architecture`, `priority:p2`
  - Issue: none

- [ ] Verify branch protection manually in GitHub settings.
  - Context: The GitHub API endpoint could not verify branch protection for this private repository under the current plan.
  - Acceptance: `develop` and `main` require PRs, CI, restricted force push, and appropriate approvals.
  - Suggested labels: `needs-triage`, `type:ops`, `priority:p2`
  - Issue: none

## Resolved Or Captured Locally

- [x] Local `agent-toolkit-skills` plugin was refreshed, validated, installed, and enabled for new Codex sessions.
- [x] Baseline backend architecture planning report was created under `docs/planning/`.
- [x] Journey exploration addendum was created under `docs/planning/journey-exploration/`.
- [x] Git Flow policy was recorded in `AGENTS.md`.
- [x] ADR process was initialized under `docs/decisions/`.
- [x] This handoff and improvements cleanup was prepared for session restart.
