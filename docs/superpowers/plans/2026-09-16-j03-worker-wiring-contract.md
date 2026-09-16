# J03 Worker Wiring Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Issue #114's controllable worker wiring, candidate provider, FastAPI contract, end-to-end tests, and worker observability.

**Architecture:** Keep `modules:journey` as the pure worker core and `apps:spring-api` as Spring wiring/HTTP adapter ownership. Use in-memory adapters for local deterministic E2E tests and JDBC adapter for DB CAS late-write proof.

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, AssertJ, JDK HttpClient, PostgreSQL Testcontainers, FastAPI contract YAML/Python endpoint.

## Global Constraints

- TDD: write failing tests before production code.
- Do not send raw query, token, cookie, or evidence body in worker telemetry.
- FastAPI boundary must include `Authorization`, `X-Request-Id`, `X-Run-Id`, `X-Revision`, and `traceparent`.
- Late result after terminal or expiry must not write board/proposal.
- Branch parser currently does not return Issue #114; PR branch must be fixed later.

---

### Task 1: Worker Wiring And Queue Runner

**Files:**
- Create: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/JourneyWorkerQueue.java`
- Create: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/InMemoryJourneyWorkerQueue.java`
- Create: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/JourneyWorkerRunner.java`
- Test: `modules/journey/src/test/java/com/yrootlab/onmaru/journey/worker/JourneyWorkerRunnerTests.java`

**Interfaces:**
- Consumes: `JourneyWorkerService.process(JourneyWorkerRequest)`.
- Produces: `JourneyWorkerRunner.drain()` for deterministic startup/scheduler tests.

- [x] **Step 1: Write failing runner test**
- [x] **Step 2: Run focused test and verify RED**
- [x] **Step 3: Implement queue and runner**
- [x] **Step 4: Run focused test and verify GREEN**

### Task 2: Candidate Provider

**Files:**
- Create: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/InMemoryJourneyCandidateProvider.java`
- Test: `modules/journey/src/test/java/com/yrootlab/onmaru/journey/worker/InMemoryJourneyCandidateProviderTests.java`

**Interfaces:**
- Consumes: `JourneyWorkerRequest.regionCode()` and `datasetRevision()`.
- Produces: deterministic `CandidatePayload` for worker E2E tests.

- [x] **Step 1: Write failing provider test**
- [x] **Step 2: Run focused test and verify RED**
- [x] **Step 3: Implement provider**
- [x] **Step 4: Run focused test and verify GREEN**

### Task 3: Spring Wiring

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/integration/ai/AiIntegrationProperties.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/worker/journey/JourneyWorkerConfiguration.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/worker/journey/JourneyWorkerConfigurationTests.java`

**Interfaces:**
- Consumes: `HttpAiProposalClient`, `DefaultBaselinePlanner`, `SpringJourneyWorkerTelemetry`, `JdbcJourneyResultStore`.
- Produces: Spring beans for worker orchestration.

- [x] **Step 1: Write failing Spring context test**
- [x] **Step 2: Run focused test and verify RED**
- [x] **Step 3: Implement properties and configuration**
- [x] **Step 4: Run focused test and verify GREEN**

### Task 4: FastAPI Contract Alignment

**Files:**
- Modify: `docs/contracts/openapi/internal-ai.yaml`
- Modify: `ai/src/onmaru_ai/security/internal_auth.py`
- Modify: `ai/tests/test_internal_auth.py`

**Interfaces:**
- Consumes: Spring `HttpAiProposalClient` response contract.
- Produces: synchronous fake proposal response with `orderedRefs` and `outcome`.

- [x] **Step 1: Write failing FastAPI response test**
- [x] **Step 2: Run pytest focused test and verify RED**
- [x] **Step 3: Implement minimal response shape**
- [x] **Step 4: Run focused pytest and verify GREEN**

### Task 5: End-To-End Worker Contract And Observability

**Files:**
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/worker/journey/JourneyWorkerEndToEndTests.java`
- Modify: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/JourneyWorkerService.java`
- Modify: `troubleshooting-worklog/26.09.16 j03-spring-journey-worker-fastapi-baseline-orchestration.md`

**Interfaces:**
- Consumes: queue runner, candidate provider, HTTP AI adapter, JDBC result store.
- Produces: proof that success, fallback, late terminal discard, and telemetry are controlled.

- [x] **Step 1: Write failing E2E test**
- [x] **Step 2: Run focused test and verify RED**
- [x] **Step 3: Add missing worker telemetry attributes/events**
- [x] **Step 4: Run focused test and verify GREEN**
- [x] **Step 5: Run full focused verification set**
