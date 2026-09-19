# Exploration Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Issue #101 exploration creation, lookup, and turn intake for guest/member actors.

**Architecture:** Add a journey-domain service with an in-memory store, actor ownership model, and explicit AI run port. Add a Spring web controller that resolves member/guest cookies, maps validation and ownership failures to API envelopes, and returns OpenAPI-shaped responses.

**Tech Stack:** Java 21, Spring Boot 3.x, Gradle, JUnit 5, MockMvc, in-memory stores.

## Global Constraints

- Korean project docs and work logs; code identifiers remain English.
- Do not implement durable worker, SSE streaming, or the #109 run state machine.
- Do not call AI when region is missing; return clarification outcome.
- 422 rejection must not create an exploration or raw turn.
- Other actor access must be concealed as 404.

---

### Task 1: Journey Domain Exploration Service

**Files:**
- Create: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/exploration/ExplorationActor.java`
- Create: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/exploration/ExplorationService.java`
- Create: `modules/journey/src/main/java/com/yrootlab/onmaru/journey/exploration/InMemoryExplorationStore.java`
- Create: supporting records and exceptions in the same package
- Test: `modules/journey/src/test/java/com/yrootlab/onmaru/journey/exploration/ExplorationServiceTests.java`

**Interfaces:**
- Produces: `ExplorationService.create(CreateExplorationCommand)`, `get(ExplorationActor, UUID)`, `createTurn(CreateTurnCommand)`, `turnCount(UUID)`, `explorationCount()`
- Produces: `ExplorationRunPort.start(ExplorationRunRequest)`

- [ ] **Step 1: Write failing service tests**

```java
@Test
void missingRegionCompletesWithClarificationWithoutCallingAi()

@Test
void otherActorCannotReadGuestExploration()

@Test
void invalidCreateDoesNotPersistExploration()

@Test
void invalidTurnDoesNotPersistRawTurn()
```

Run: `./gradlew :modules:journey:test --tests '*ExplorationServiceTests'`
Expected: compile failure because classes do not exist.

- [ ] **Step 2: Implement minimal domain**

Create actor, command, snapshot, run, and exception records. Store explorations by UUID with owner actor, state version, region code, board null, pinned/excluded refs as empty lists, and turns by exploration.

- [ ] **Step 3: Verify service tests pass**

Run: `./gradlew :modules:journey:test --tests '*ExplorationServiceTests'`
Expected: PASS.

### Task 2: Spring Exploration API Boundary

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/exploration/ExplorationController.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/exploration/ExplorationConfiguration.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/exploration/ExplorationWebBoundaryTests.java`

**Interfaces:**
- Consumes: `ExplorationService` from Task 1.
- Produces: `/api/v1/explorations`, `/api/v1/explorations/{explorationId}`, `/api/v1/explorations/{explorationId}/turns`.

- [ ] **Step 1: Write failing MockMvc tests**

```java
@Test
void guestCanCreateMissingRegionExplorationAndReadSnapshot()

@Test
void otherGuestReceivesNotFound()

@Test
void memberCanCreateAndPostTurn()

@Test
void invalidTurnReturnsUnprocessableEntityWithoutPersistingTurn()
```

Run: `./gradlew :apps:spring-api:test --tests '*ExplorationWebBoundaryTests'`
Expected: compile failure because controller does not exist.

- [ ] **Step 2: Implement controller and config**

Resolve actor from `__Host-onmaru-session` when active member exists, otherwise from `__Host-onmaru-guest`. Return 401 when neither exists. Require CSRF through the existing filter for POSTs. Map validation failures to 422, stale/active run conflicts to 409 where applicable, and ownership misses to 404.

- [ ] **Step 3: Verify web tests pass**

Run: `./gradlew :apps:spring-api:test --tests '*ExplorationWebBoundaryTests'`
Expected: PASS.

### Task 3: Repository Verification And Handoff

**Files:**
- Modify: `handoff.md`

**Interfaces:**
- Consumes: Task 1 and Task 2 implementation.
- Produces: verification notes for Issue #101.

- [ ] **Step 1: Run focused verification**

Run: `./gradlew :modules:journey:test :apps:spring-api:test --tests '*Exploration*'`
Expected: PASS.

- [ ] **Step 2: Run broader verification**

Run: `./gradlew test`
Expected: PASS or record exact unrelated failure.

- [ ] **Step 3: Update handoff**

Record branch, Issue #101, touched files, verification commands, and remaining #109 out-of-scope work.
