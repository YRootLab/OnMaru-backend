# Moderation Queue And Operator Drill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 보호된 운영 queue와 synthetic moderation drill을 구현해 Issue #139의 authorization, privacy, SLA, disposition, audit, public exclusion 기준을 검증한다.

**Architecture:** `modules/community`가 open report와 review/audit state를 queue projection으로 계산하고 기존 M05 command가 system/operator transition을 수행한다. Spring 내부 operations boundary는 SecretProvider의 rotation token과 actor header를 함께 인증하며, 공개 query는 기존 `PUBLISHED` filter를 그대로 신뢰하되 E2E drill로 hide/restore/remove 직후 재검증한다.

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5, AssertJ, MockMvc, Gradle, repository-local JSON fixture

## Global Constraints

- Target branch is `develop`; work branch is `feature/139-moderation-queue`.
- JDBC repository와 일반 공개 관리자 UI는 Issue #139 범위 밖이다.
- Reporter identity, report detail, review text, operator actor/token/private note는 public DTO와 telemetry에 넣지 않는다.
- Current/previous operator token을 constant-time 방식으로 검증하며 missing credential은 401, invalid credential은 403이다.
- 모든 운영 disposition은 `VisitReviewModerationService` command를 재사용한다.
- `CHANGELOG.md`와 `handoff.md`는 최신 `origin/develop` merge 뒤 union 결과를 확인한 후 갱신한다.

---

### Task 1: Queue Projection And Moderation Disposition

**Files:**
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/moderation/ModerationQueuePriority.java`
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/moderation/ModerationQueueReport.java`
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/moderation/ModerationQueueItem.java`
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/moderation/ModerationQueueSnapshot.java`
- Create: `modules/community/src/main/java/com/yrootlab/onmaru/community/moderation/ModerationQueueService.java`
- Create: `modules/community/src/test/java/com/yrootlab/onmaru/community/moderation/ModerationQueueServiceTests.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/moderation/InMemoryReviewReportStore.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/moderation/ModerationReason.java`
- Modify: `modules/community/src/main/java/com/yrootlab/onmaru/community/moderation/VisitReviewModerationService.java`
- Modify: `modules/community/src/test/java/com/yrootlab/onmaru/community/moderation/VisitReviewModerationServiceTests.java`

**Interfaces:**
- Consumes: `InMemoryVisitReviewStore.findSnapshot()`, `InMemoryReviewReportStore.openReports()`, existing M05 `moderate(...)`.
- Produces: `ModerationQueueService.snapshot(int limit)`, `VisitReviewModerationService.hideHighRiskPii(UUID,String)`, operator report close behavior, `ModerationReason.FALSE_POSITIVE`.

- [ ] **Step 1: Write failing queue and disposition tests**

```java
@Test
void projectsHighRiskBeforeStandardWithoutReporterIdentity() {
    ModerationQueueSnapshot snapshot = queueService.snapshot(100);
    assertThat(snapshot.items()).extracting(ModerationQueueItem::priority)
            .containsExactly(HIGH_RISK, STANDARD);
    assertThat(snapshot.items().getFirst().ageSeconds()).isEqualTo(90_000);
    assertThat(snapshot.items().getFirst().slaTargetAt())
            .isEqualTo(Instant.parse("2026-09-15T02:00:00Z"));
}

@Test
void systemHideKeepsReportOpenAndOperatorRestoreDismissesIt() {
    service.hideHighRiskPii(REVIEW_ID, "pii-detector-v1");
    assertThat(service.openReports()).hasSize(1);
    service.moderate(REVIEW_ID, "operator-1", PUBLISHED, FALSE_POSITIVE);
    assertThat(service.openReports()).isEmpty();
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew :modules:community:test --tests '*ModerationQueueServiceTests' --tests '*VisitReviewModerationServiceTests' --no-daemon`

Expected: compilation fails because queue types, `hideHighRiskPii`, and `FALSE_POSITIVE` do not exist.

- [ ] **Step 3: Implement minimal domain behavior**

```java
public ModerationQueueSnapshot snapshot(int limit) {
    var now = clock.instant();
    var items = reportStore.openReports().stream()
            .collect(groupingBy(ReviewReport::reviewId))
            .entrySet().stream()
            .map(entry -> project(entry.getKey(), entry.getValue(), now))
            .sorted(queueOrder())
            .limit(limit)
            .toList();
    return ModerationQueueSnapshot.from(now, items);
}
```

`PERSONAL_DATA` 또는 `SYSTEM/PII_HIGH_RISK` action은 24시간, 나머지는 72시간 SLA를 사용한다. Queue report record에는 `reason`, `detail`, `createdAt`만 두고 `reporterMemberId`를 복사하지 않는다. System hide는 audit만 추가하며, operator action은 `PUBLISHED`면 open report를 `DISMISSED`, 그 외면 `RESOLVED`로 교체한다.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./gradlew :modules:community:test --tests '*ModerationQueueServiceTests' --tests '*VisitReviewModerationServiceTests' --no-daemon`

Expected: all focused community moderation tests pass.

- [ ] **Step 5: Commit domain slice**

```bash
git add modules/community
git commit -m "feat(moderation): 운영 queue와 disposition 추가"
```

### Task 2: Operator Authentication And Protected HTTP Boundary

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/operations/moderation/queue/OperatorAuthenticator.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/operations/moderation/queue/OperatorPrincipal.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/operations/moderation/queue/OperatorAuthenticationRequiredException.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/operations/moderation/queue/OperatorForbiddenException.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/operations/moderation/queue/ModerationQueueController.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/operations/moderation/queue/OperatorAuthenticatorTests.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/operations/moderation/queue/ModerationQueueWebBoundaryTests.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/SecretBundle.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/OnMaruSecretProperties.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/config/secrets/SecretConfigurationTests.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/query/VisitReviewQueryConfiguration.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/moderation/VisitReviewModerationController.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/review/moderation/VisitReviewModerationWebBoundaryTests.java`

**Interfaces:**
- Consumes: `SecretProvider.get("moderation.operator-token")`, `ModerationQueueService.snapshot(100)`.
- Produces: authenticated `GET /api/v1/operations/moderation/queue`; authenticated existing moderation POST; stable 401/403 envelopes.

- [ ] **Step 1: Write failing authentication and web boundary tests**

```java
@Test
void acceptsCurrentAndPreviousRotationTokensWithActorHeader() {
    assertThat(authenticator.authenticate("Bearer current-token", "operator-1").actorRef())
            .isEqualTo("operator-1");
    assertThat(authenticator.authenticate("Bearer previous-token", "operator-2").actorRef())
            .isEqualTo("operator-2");
}

@Test
void actorHeaderWithoutBearerIsUnauthorizedAndInvalidBearerIsForbidden() throws Exception {
    queue(null, "operator-1").andExpect(status().isUnauthorized());
    queue("Bearer wrong", "operator-1").andExpect(status().isForbidden());
}
```

Add a regression assertion that the existing moderation POST rejects actor-header-only requests. Add a context-runner assertion that default environment startup fails when `moderation.operator-token` is missing.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew :apps:spring-api:test --tests '*OperatorAuthenticatorTests' --tests '*ModerationQueueWebBoundaryTests' --tests '*VisitReviewModerationWebBoundaryTests' --tests '*SecretConfigurationTests' --no-daemon`

Expected: queue/auth types are missing and the legacy actor-header-only request is still accepted.

- [ ] **Step 3: Implement protected boundary**

```java
public OperatorPrincipal authenticate(String authorization, String actorRef) {
    if (isBlank(authorization) || isBlank(actorRef)) {
        throw new OperatorAuthenticationRequiredException();
    }
    if (!authorization.startsWith("Bearer ") || !validActor(actorRef)) {
        throw new OperatorForbiddenException();
    }
    var token = authorization.substring("Bearer ".length());
    if (!secretProvider.get(SECRET_NAME).matches(token)) {
        throw new OperatorForbiddenException();
    }
    return new OperatorPrincipal(actorRef.trim());
}
```

Implement `SecretBundle.matches` with fixed-length SHA-256 digests and non-short-circuit comparison of current and previous values. Queue and moderation responses use `Cache-Control: no-store`; neither error includes credential detail.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./gradlew :apps:spring-api:test --tests '*OperatorAuthenticatorTests' --tests '*ModerationQueueWebBoundaryTests' --tests '*VisitReviewModerationWebBoundaryTests' --tests '*SecretConfigurationTests' --no-daemon`

Expected: all authentication/configuration/boundary tests pass.

- [ ] **Step 5: Commit HTTP slice**

```bash
git add apps/spring-api
git commit -m "feat(moderation): operator queue 접근을 보호"
```

### Task 3: Synthetic Operator Drill And Privacy Regression

**Files:**
- Create: `testing/e2e/moderation/operator-drill.json`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/operations/moderation/queue/ModerationOperatorDrillTests.java`

**Interfaces:**
- Consumes: public report endpoint, protected queue, system PII hide command, protected operator disposition, public review query, audit log, in-memory telemetry sink.
- Produces: one deterministic synthetic drill covering every Issue #139 scenario.

- [ ] **Step 1: Write the failing synthetic drill**

```java
@Test
void completesSyntheticReportHideRestoreRemoveAuditAndPublicExclusionDrill() throws Exception {
    submitEveryReportReasonFromFixture();
    assertDuplicateReportReturnsOriginalReceipt();
    moderationService.hideHighRiskPii(PII_REVIEW_ID, "pii-detector-v1");
    assertPublicListOmits(PII_REVIEW_ID, SYNTHETIC_PII_TEXT);
    restoreWithPreviousOperatorToken(PII_REVIEW_ID);
    removeConfirmedViolationWithCurrentToken(ABUSE_REVIEW_ID);
    assertAuditSequenceAndPublicExclusion();
    assertTelemetryContainsNoPrivateValues();
}
```

- [ ] **Step 2: Run drill and verify RED**

Run: `./gradlew :apps:spring-api:test --tests '*ModerationOperatorDrillTests' --no-daemon`

Expected: drill fails until all fixture steps, report close behavior, audit sequence, and telemetry redaction are integrated.

- [ ] **Step 3: Complete only integration gaps exposed by the drill**

Use synthetic UUIDs and text from the JSON fixture. Do not add a production endpoint for the detector; inject the existing internal service in the test. Assert telemetry attribute keys/values never contain reporter UUIDs, report detail, review text, actor ref, or either token.

- [ ] **Step 4: Run drill and all moderation tests**

Run: `./gradlew :apps:spring-api:test --tests '*Moderation*' :modules:community:test --tests '*Moderation*' --no-daemon`

Expected: all moderation domain, boundary, and drill tests pass.

- [ ] **Step 5: Commit drill slice**

```bash
git add testing/e2e/moderation apps/spring-api/src/test
git commit -m "test(moderation): synthetic operator drill 추가"
```

### Task 4: Runbook, Work Log, Sync, And Full Verification

**Files:**
- Create: `docs/operations/runbooks/moderation.md`
- Create: `troubleshooting-worklog/26.09.15 m08-moderation-queue-drill.md`
- Modify: `docs/operations/moderation.md`
- Modify after develop sync: `CHANGELOG.md`
- Modify after develop sync: `handoff.md`

**Interfaces:**
- Consumes: implemented headers, queue fields, disposition reason codes, test command, Grafana alert names.
- Produces: executable operator runbook and PR handoff evidence.

- [ ] **Step 1: Write the runbook and work log**

Document exact credential headers, queue priority/SLA, Grafana warning/critical response, `FALSE_POSITIVE` restore, confirmed `REMOVED`, audit verification, public query negative checks, and the focused drill command. State that direct DB updates and production data are prohibited during the drill.

- [ ] **Step 2: Verify documentation and focused behavior**

Run: `git diff --check`

Run: `./gradlew :apps:spring-api:test --tests '*Moderation*' :modules:community:test --tests '*Moderation*' --no-daemon`

Expected: clean diff and all moderation tests pass.

- [ ] **Step 3: Commit documentation**

```bash
git add docs/operations troubleshooting-worklog docs/superpowers/plans/2026-09-15-moderation-queue.md
git commit -m "docs(moderation): operator drill runbook 기록"
```

- [ ] **Step 4: Merge the latest develop and reconcile shared logs**

Run: `git fetch origin develop`

Run: `git merge --no-edit origin/develop`

After the merge, inspect `CHANGELOG.md` and `handoff.md`, then add one Issue #139 entry without removing parallel PR entries.

- [ ] **Step 5: Run complete repository verification**

Run each command independently and require exit code 0:

```bash
./gradlew test --no-daemon
cd ai && uv run ruff check .
cd ai && uv run mypy
cd ai && uv run pytest
node --test scripts/test/*.test.mjs
node scripts/verify-planning-inputs.mjs
node scripts/validate-odii-fixtures.mjs
python3 -m pytest scripts/test/test_contract_validation.py
bash scripts/verify-contracts
node scripts/print-branch-issue.mjs
git diff --check
```

- [ ] **Step 6: Commit final reconciled evidence**

```bash
git add CHANGELOG.md handoff.md
git commit -m "docs(ops): Issue #139 PR handoff 갱신"
```

- [ ] **Step 7: Request code review and address findings**

Review `origin/develop..HEAD` against Issue #139 and this plan. Fix every Critical/Important finding and rerun affected focused tests plus the complete verification gate.

- [ ] **Step 8: Push and create the PR**

Push `feature/139-moderation-queue`, then create a Korean PR targeting `develop` with `Closes #139`, AC-to-test evidence, complete verification results, and shared-log conflict risk. Do not merge the PR.
