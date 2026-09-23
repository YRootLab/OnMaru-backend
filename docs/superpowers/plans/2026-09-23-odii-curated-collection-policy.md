# Odii Curated Collection Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand Odii synchronization from the single `한옥` query to a policy-driven, deduplicated collection that excludes leisure and sports content, preserves culturally contextualized traditional accommodation, and keeps the last active PostgreSQL revision on unsafe sync results.

**Architecture:** Keep the existing `OdiiPageSource → OdiiRevisionSyncService → AudioRevisionStore → publish` pipeline. Make the source accept a list of configured keywords, introduce a pure curation policy for deduplication and inclusion decisions, and expose sync counts through the existing result/observer boundary. Do not delete raw provider observations or introduce AI classification in this change.

**Tech Stack:** Java 21, Spring Boot configuration properties, JUnit 5, AssertJ, existing PostgreSQL/Testcontainers migration and audio sync test fixtures.

## Global Constraints

- The production source remains the Korea Tourism Organization Odii API; request-time public APIs must continue reading the active PostgreSQL revision.
- `#307` production credential/empty-dataset recovery is a prerequisite and must remain diagnostically separate from `#329` policy changes.
- Preserve atomic staging/publication and keep the previous active revision when source fetch fails, the result is empty, or the result falls below the configured safety threshold.
- Keep the source allowlist and exclusion rules deterministic, reviewable, and free of AI/model dependencies.
- Do not remove excluded provider data permanently; retain enough identity/reason information for sync observability or quarantine follow-up.

---

### Task 1: Introduce the deterministic Odii curation policy

**Files:**
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiCurationPolicy.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiCurationDecision.java`
- Create: `modules/audio/src/test/java/com/yrootlab/onmaru/audio/sync/OdiiCurationPolicyTests.java`

**Interfaces:**
- Consumes: `OdiiSourceStory` and the originating search keyword.
- Produces: a decision containing `INCLUDED`, `EXCLUDED`, or `DUPLICATE` plus a stable reason code; later sync tasks use it before `store.stage`.

- [ ] **Step 1: Write failing policy tests**

Cover these exact cases:

```java
assertThat(policy.decide(story("한옥마을", "전통문화 해설"), "한옥").status())
        .isEqualTo(OdiiCurationDecision.Status.INCLUDED);
assertThat(policy.decide(story("스키장 체험", "겨울 스포츠"), "한옥").reason())
        .isEqualTo("EXCLUDED_SPORTS_OR_LEISURE");
assertThat(policy.decide(story("전주 축제", "지역 역사와 문화 해설"), "축제").status())
        .isEqualTo(OdiiCurationDecision.Status.INCLUDED);
```

Also test case-insensitive matching, blank title/script handling, and duplicate story IDs.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :modules:audio:test --tests '*OdiiCurationPolicyTests'
```

Expected: compilation failure because the policy types do not exist.

- [ ] **Step 3: Implement the minimal pure policy**

Use fixed immutable sets for the initial allow keywords and exclusion terms. Normalize title, audio title, and script with lowercase and whitespace folding. Use `stlid` as the duplicate identity; if it is blank, use the stable available story identity and return an explicit missing-identity exclusion reason rather than silently publishing an ambiguous row.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same Gradle command. Expected: all policy tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiCurationPolicy.java modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiCurationDecision.java modules/audio/src/test/java/com/yrootlab/onmaru/audio/sync/OdiiCurationPolicyTests.java
git commit -m "feat(audio): add deterministic Odii curation policy"
```

### Task 2: Change Odii source configuration and page fetching to support multiple keywords

**Files:**
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/tourism/audio/OdiiClientConfiguration.java`
- Modify: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/sync/OdiiStorySearchPageSource.java`
- Modify: `adapters/tourism-api/src/test/java/com/yrootlab/onmaru/tourism/audio/client/OdiiHttpClientTests.java`
- Modify: production configuration files containing `onmaru.odii.sync.keyword`

**Interfaces:**
- Consumes: `OdiiSyncSettings.keywords()` as an immutable list.
- Produces: `OdiiStorySearchPageSource.fetch(language, keyword, page)` with the same provider error and page-drift behavior as today.

- [ ] **Step 1: Add failing configuration/source tests**

Assert that default settings contain the approved first-wave keywords, explicit configured keywords are preserved in order, blank entries are rejected, and the source builds a request for each keyword without changing language/page semantics.

- [ ] **Step 2: Run focused adapter/configuration tests and verify failure**

```bash
./gradlew :adapters:tourism-api:test :apps:spring-api:test --tests '*OdiiHttpClientTests' --tests '*OdiiClientConfigurationTests'
```

Expected: compile or assertion failures against the current single-keyword contract.

- [ ] **Step 3: Implement list-based settings and source signature**

Replace `keyword` with `List<String> keywords` in `OdiiSyncSettings`, validate non-empty unique values, and update the Spring bean wiring. Keep the HTTP source responsible only for one `(language, keyword, page)` request so it remains independently testable.

- [ ] **Step 4: Run focused tests and verify pass**

Run the commands above. Expected: all configuration and request-building tests pass.

- [ ] **Step 5: Commit**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/tourism/audio/OdiiClientConfiguration.java adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/sync/OdiiStorySearchPageSource.java adapters/tourism-api/src/test/java/com/yrootlab/onmaru/tourism/audio/client/OdiiHttpClientTests.java
git commit -m "feat(audio): configure Odii multi-keyword source"
```

### Task 3: Apply deduplication and curation during revision synchronization

**Files:**
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiRevisionSyncService.java`
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSyncResult.java`
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSyncCommand.java` only if policy/safety thresholds must be command-scoped
- Modify: `modules/audio/src/test/java/com/yrootlab/onmaru/audio/sync/OdiiRevisionSyncServiceTests.java`

**Interfaces:**
- Consumes: configured keyword list, `OdiiCurationPolicy`, and each fetched `OdiiSourcePage`.
- Produces: staged stories with no duplicate IDs and an `OdiiSyncResult` summary containing fetched, duplicate, excluded, included, and published counts.

- [ ] **Step 1: Write failing sync tests**

Add tests for:

```java
// Same stlid returned by two keywords is staged once.
assertThat(result.itemCount()).isEqualTo(1);

// Sports/leisure result is not staged and is reported as excluded.
assertThat(result.excludedItemCount()).isEqualTo(1);

// Provider failure, zero usable results, and below-threshold results do not replace active revision.
assertThat(store.activeRevisionId()).isEqualTo(previousActiveRevision);
```

Retain existing lease loss, stale base, tombstone, and source failure tests.

- [ ] **Step 2: Run the audio sync tests and verify new tests fail**

```bash
./gradlew :modules:audio:test --tests '*OdiiRevisionSyncServiceTests'
```

Expected: missing constructor/result fields or incorrect staging counts.

- [ ] **Step 3: Implement the sync loop**

Iterate language × keyword × page, fetch one page at a time, maintain a `Set<String>` of seen story IDs, apply the curation policy before mapping/staging, and aggregate counts. Do not publish a revision with no included stories or with a configured safety-threshold violation. Return a typed non-published status rather than throwing for expected policy rejection.

- [ ] **Step 4: Run focused sync tests and verify pass**

Run the same Gradle command. Expected: all existing and new revision tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiRevisionSyncService.java modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSyncResult.java modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSyncCommand.java modules/audio/src/test/java/com/yrootlab/onmaru/audio/sync/OdiiRevisionSyncServiceTests.java
git commit -m "feat(audio): curate and deduplicate Odii revisions"
```

### Task 4: Extend sync observability and production wiring

**Files:**
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/tourism/audio/TelemetryOdiiSyncObserver.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/scheduling/audio/OdiiSyncSchedulingAdapter.java` only if non-publish outcomes are currently logged identically
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/tourism/audio/OdiiClientConfigurationTests.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/scheduling/audio/*` matching existing scheduler tests

**Interfaces:**
- Consumes: enriched `OdiiSyncResult`.
- Produces: telemetry/log fields for keyword count, fetched count, duplicate count, exclusion count, included count, publish status, and failure reason without exposing service keys.

- [ ] **Step 1: Add failing observer assertions**

Verify telemetry receives structured values for a successful curated publish, a zero-result rejection, and a source failure. Verify secret values are absent from serialized fields.

- [ ] **Step 2: Implement observer and scheduler logging changes**

Keep the existing `OdiiSyncObserver` lifecycle. Add fields to the completion event/result rather than adding a second logging path. Preserve the current error log for unexpected exceptions and distinguish expected policy rejection from provider failure.

- [ ] **Step 3: Run application tests**

```bash
./gradlew :apps:spring-api:test --tests '*OdiiClientConfigurationTests' --tests '*OdiiSync*' --tests '*TelemetryOdiiSync*'
```

- [ ] **Step 4: Commit**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/tourism/audio/TelemetryOdiiSyncObserver.java apps/spring-api/src/main/java/com/yrootlab/onmaru/scheduling/audio apps/spring-api/src/test/java/com/yrootlab/onmaru/tourism/audio apps/spring-api/src/test/java/com/yrootlab/onmaru/scheduling/audio
git commit -m "feat(observability): report Odii curation sync counts"
```

### Task 5: Verify PostgreSQL staging/publication and update contracts

**Files:**
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/*Audio*Tests.java` or the existing PostgreSQL audio persistence test file
- Modify: `docs/database/schema.md` only if a migration/schema field is added
- Modify: `docs/superpowers/specs/2026-09-23-odii-curated-collection-policy-design.md` only for verified implementation notes
- Modify: `handoff.md` with branch, Issue #329, verification, and remaining #307 production action

**Interfaces:**
- Consumes: the completed sync service and JDBC `AudioRevisionStore`.
- Produces: evidence that a curated revision is staged/published atomically and that failed/unsafe runs leave the prior active revision visible.

- [ ] **Step 1: Add PostgreSQL integration assertions**

Use the existing Testcontainers PostgreSQL harness to verify one curated revision, duplicate elimination, excluded content absence from active rows, and rollback/active-pointer preservation on unsafe sync.

- [ ] **Step 2: Run the focused PostgreSQL tests**

```bash
./gradlew :apps:spring-api:test --tests '*Audio*Postgres*' --tests '*JdbcAudio*'
```

Expected: tests pass against real PostgreSQL rather than H2.

- [ ] **Step 3: Run repository verification**

```bash
./gradlew check --no-daemon
./scripts/verify-contracts
git diff --check
```

- [ ] **Step 4: Update Issue #329 and handoff evidence**

Record the exact test commands, counts observed in the sync summary, the deployed environment result, and whether #307 has been separately recovered. Do not close either issue until its own acceptance criteria and merge state are verified.

- [ ] **Step 5: Commit documentation and verification evidence**

```bash
git add docs/database/schema.md docs/superpowers/specs/2026-09-23-odii-curated-collection-policy-design.md handoff.md
git commit -m "docs(audio): record Odii curation verification"
```

## Plan Self-Review

- Spec coverage: multi-keyword collection, exclusion policy, deduplication, safe publication, observability, PostgreSQL verification, and #307 separation are covered by Tasks 1–5.
- Placeholder scan: no `TBD`, `TODO`, or unspecified implementation step is required; all commands and target files are named.
- Type consistency: Task 1 defines `OdiiCurationDecision`; Task 3 consumes it. Task 2 defines `OdiiSyncSettings.keywords()` and keyword-aware source fetching; Task 3 consumes the list. Task 3 enriches `OdiiSyncResult`; Task 4 consumes it.
- Scope check: no AI classifier, general tourism category rewrite, search engine, or raw-data deletion is included.
