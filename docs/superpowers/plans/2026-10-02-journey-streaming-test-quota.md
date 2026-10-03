# AI Journey Streaming and Test Quota Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026년 10월 KST 테스트 기간에 회원별 AI 여정 생성 2회 제한을 원자적으로 적용하고, 검증 전 지도 데이터 없이 Gemini narration을 기존 브라우저 SSE로 전달한다.

**Architecture:** quota는 기존 admission counter에 고정 KST window anchor와 서버 설정 기반 예외 resolver를 추가한다. streaming은 FastAPI가 Gemini SSE에서 `narration`만 내부 event로 내보내고, Spring이 `run.text.delta`로 relay한 뒤 완성 proposal을 기존 검증·snapshot 경로에 전달한다.

**Tech Stack:** Java 21, Spring Boot 3, PostgreSQL/Flyway, Python 3.12, FastAPI, httpx, Gemini REST SSE, JUnit 5, pytest.

## Global Constraints

- 기본 기간은 `[2026-10-01T00:00:00+09:00, 2026-11-01T00:00:00+09:00)`이고 일반 회원 한도는 2회다.
- guest는 provider 호출·worker dispatch 전에 `401 AUTH_REQUIRED`를 받는다.
- 예외 회원도 active run 1회 제한을 유지한다.
- 취소·provider 실패·baseline fallback은 사용량을 환불하지 않는다.
- 브라우저에는 `narration`만 전달하고 provider raw JSON, candidate refs, 좌표, prompt, secret, 내부 오류 원문을 전달하지 않는다.
- 각 공개 delta는 최대 512자, run 전체 narration은 최대 4,000자다.
- 최종 지도 결과는 Spring 검증과 snapshot 저장을 통과한 뒤 GET snapshot으로만 반영한다.

---

### Task 1: 고정 KST quota window

**Files:**
- Modify: `modules/operations/src/main/java/com/yrootlab/onmaru/operations/admission/OperationBudget.java`
- Modify: `modules/operations/src/main/java/com/yrootlab/onmaru/operations/admission/AdmissionService.java`
- Modify: `modules/operations/src/main/java/com/yrootlab/onmaru/operations/admission/InMemoryAdmissionStore.java`
- Modify: `adapters/persistence-jdbc/src/main/java/com/yrootlab/onmaru/persistence/operations/admission/JdbcAdmissionStore.java`
- Test: `modules/operations/src/test/java/com/yrootlab/onmaru/operations/admission/AdmissionServiceTests.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcAdmissionStoreTests.java`

**Interfaces:**
- Produces: `OperationBudget(..., Duration window, int activeLimit, Instant windowAnchor)`; null anchor keeps current behavior.
- Produces: anchored start `anchor + floor((now-anchor)/window) * window`.

- [ ] **Step 1: Write RED tests** for the exact UTC boundaries `2026-09-30T15:00:00Z` and `2026-10-31T15:00:00Z`, two allowed starts, third rejection, and one active run. A run started before a window boundary must still occupy the active slot after the boundary until release; only consumed resets when the window changes.
- [ ] **Step 2: Run RED:** `./gradlew :modules:operations:test :apps:spring-api:test --tests '*AdmissionServiceTests' --tests '*JdbcAdmissionStoreTests' --no-daemon --max-workers=1`.
- [ ] **Step 3: Implement the anchor** while keeping every existing constructor source-compatible; reject an instant before the anchor instead of assigning it to a future test window.
- [ ] **Step 4: Run the Step 2 command and require GREEN.**

### Task 2: 회원 전용 테스트 정책과 예외 계정

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/exploration/JourneyAiTestQuotaProperties.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/exploration/JourneyAiAdmissionPolicyResolver.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/admission/AdmissionWebConfiguration.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/exploration/ExplorationController.java`
- Modify: `apps/spring-api/src/main/resources/application.yaml`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/exploration/ExplorationWebBoundaryTests.java`

**Interfaces:**
- Consumes: Task 1 anchored budget.
- Produces: `AdmissionPolicy resolve(ExplorationActor actor, Instant now)` using limit `2` or `Integer.MAX_VALUE`, fixed period duration/anchor, and active limit `1`.
- Produces env keys `ONMARU_JOURNEY_TEST_QUOTA_START`, `ONMARU_JOURNEY_TEST_QUOTA_END`, `ONMARU_JOURNEY_TEST_QUOTA_LIMIT`, `ONMARU_JOURNEY_TEST_QUOTA_EXEMPT_MEMBER_IDS`.

- [ ] **Step 1: Write RED web tests** covering guest zero-dispatch 401, normal member 2/3, exempt member total bypass with active limit, idempotent retry, and post-period fallback.
- [ ] **Step 2: Run RED:** `./gradlew :apps:spring-api:test --tests '*ExplorationWebBoundaryTests' --no-daemon --max-workers=1`.
- [ ] **Step 3: Implement resolver and boundary.** `admitAiRun` rejects `GUEST`, resolves with the injected clock, and calls existing atomic `admitActive`. Keep operation key `journey.ai` so release works across the period boundary.
- [ ] **Step 4: Run GREEN** plus `--tests '*ApiErrorContractTests'`, preserving #520 aliases.

### Task 3: Public `run.text.delta` contract and bounded replay

**Files:**
- Modify: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/events/JourneyRunEventType.java`
- Modify: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/events/JourneyRunEventSink.java`
- Modify: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/events/JourneyRunEventBuffer.java`
- Test: `modules/journey/src/test/java/com/yrootlab/onmaru/journey/events/JourneyRunEventBufferTests.java`
- Modify: `docs/contracts/schemas/journey-sse-event.schema.json`
- Modify: `docs/contracts/fixtures/journey-sse-fixtures.json`
- Modify: `docs/contracts/rest-api.md`
- Modify: `docs/contracts/frontend-handoff.md`

**Interfaces:**
- Produces `TEXT_DELTA("run.text.delta")` and `JourneyRunEvent textDelta(UUID runId, String text)`.
- Public data is exactly `{schemaVersion,runId,sequence,text}` and shares monotonic replay sequence with stage/terminal.

- [ ] **Step 1: Write RED tests** for delta-before-terminal, replay, 512-char chunk, 4,000-char run cap, and unchanged run state.
- [ ] **Step 2: Run RED:** `./gradlew :modules:journey:test --tests '*JourneyRunEventBufferTests' --no-daemon` and `bash scripts/verify-contracts --contracts-only`.
- [ ] **Step 3: Implement bounded append and schema.** Never log text; telemetry may record event type, run ID, sequence, and character count.
- [ ] **Step 4: Run Step 2 and require GREEN.**

### Task 4: FastAPI Gemini streaming and narration extraction

**Files:**
- Modify: `ai/src/onmaru_ai/providers/gemini/transport.py`
- Modify: `ai/src/onmaru_ai/providers/gemini/adapter.py`
- Modify: `ai/src/onmaru_ai/journey_llm.py`
- Modify: `ai/src/onmaru_ai/security/internal_auth.py`
- Test: `ai/tests/providers/gemini/test_adapter.py`
- Test: `ai/tests/test_internal_auth.py`

**Interfaces:**
- Produces `GeminiTransport.stream(request) -> AsyncIterator[GeminiTransportResponse]` using `:streamGenerateContent?alt=sse`.
- Produces internal events `text.delta` with `{"text": string}` and one terminal `proposal` with `{"proposal": object}`.
- `RESPONSE_SCHEMA` requires `narration`, `orderedRefs`, `title`, `summary`, `stops`; only narration is incremental.

- [ ] **Step 1: Write RED tests** with Korean text and JSON escapes split across network chunks; delta must precede proposal and must not contain raw keys/refs.
- [ ] **Step 2: Run RED:** `cd ai && uv run pytest tests/providers/gemini/test_adapter.py tests/test_internal_auth.py -q`.
- [ ] **Step 3: Implement stream parsing.** Reuse unary body, preserve timeout, accumulate full text for final JSON validation, cap narration, and sanitize provider failures.
- [ ] **Step 4: Run GREEN:** repeat Step 2, then `cd ai && uv run ruff check . && uv run mypy`.

### Task 5: Spring stream relay and final validation

**Files:**
- Modify: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/AiProposalClient.java`
- Modify: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/JourneyWorkerService.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/integration/ai/HttpAiProposalClient.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/worker/journey/JourneyWorkerConfiguration.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/integration/ai/HttpAiProposalClientTests.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/worker/journey/JourneyWorkerEndToEndTests.java`

**Interfaces:**
- Extends `AiProposalClient` with `JourneyWorkerPlan propose(JourneyWorkerRequest, CandidatePayload, Consumer<String> textDelta)`; default delegates to the existing method.
- Consumes Task 4 `text.delta`/`proposal` and Task 3 `JourneyRunEventSink.textDelta`.

- [ ] **Step 1: Write RED tests** for two deltas before completion, candidate allowlist preservation, malformed stream baseline, and cancel/late-result safety.
- [ ] **Step 2: Run RED:** `./gradlew :modules:journey:test :apps:spring-api:test --tests '*HttpAiProposalClientTests' --tests '*JourneyWorkerEndToEndTests' --no-daemon --max-workers=1`.
- [ ] **Step 3: Implement relay.** Accept only named internal events, require exactly one final proposal, and keep existing Spring validation/persistence.
- [ ] **Step 4: Run GREEN** plus `--tests '*JourneyContractE2ETests'`.

### Task 6: Regression gate and staging handoff

**Files:**
- Modify: `docs/operations/release-evidence/journey/README.md`
- Modify: `handoff.md`

- [ ] **Step 1: Run serial regression:** Gradle `*Admission*`, `*Exploration*`, `*Journey*`; full `cd ai && uv run pytest -q`; `bash scripts/verify-contracts`; `git diff --check`.
- [ ] **Step 2: Record pending staging gates:** actual model/tier, first-delta latency, proxy buffering, deployed quota settings, failure fallback, and final snapshot. Do not claim #518 complete before these pass.
