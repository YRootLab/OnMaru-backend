# 관리자 JWT 폐기 영속화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자 access token의 개별 logout 폐기와 계정 단위 발급 경계를 PostgreSQL에 영속화해 재시작·다중 인스턴스·DB 장애에서도 안전하게 인증한다.

**Architecture:** logout은 검증된 token의 JTI를 SHA-256 hash로 저장하고, 계정 비활성화는 `tokens_valid_after`를 전진시킨다. 모든 관리자 요청은 JWT 자체 검증 후 PostgreSQL의 계정 상태·발급 경계와 JTI 폐기 상태를 확인하며 production 장애 시 fail-closed 한다.

**Tech Stack:** Java 21, Spring Boot 3, JDBC, PostgreSQL, Flyway, Micrometer, JUnit 5, AssertJ, Testcontainers

## Global Constraints

- token 원문, signing secret, JTI 원문을 PostgreSQL·로그·metric tag에 저장하지 않는다.
- production은 JDBC 저장소만 사용하며 메모리 fallback을 허용하지 않는다.
- JWT 자체 오류와 폐기된 token은 `401 AUTH_REQUIRED`, 저장소 장애는 `503 AUTH_UNAVAILABLE`로 구분한다.
- 계정이 `ACTIVE`가 아니거나 JWT `iat < tokens_valid_after`이면 거부한다.
- 모든 production 변경은 실패하는 테스트를 먼저 확인한 뒤 최소 구현한다.
- 관련 Issue는 #492이며 branch parser 예외인 현재 외부 관리 branch에서 작업한다.

---

### Task 1: PostgreSQL schema와 관리자 token validity 경계

**Files:**
- Create: `apps/spring-api/src/main/resources/db/migration/baseline/V044__492_admin_jwt_revocation.sql`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/DatabaseMigrationContractTests.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminAccount.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminAccountStore.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/InMemoryAdminAccountStore.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/persistence/admin/JdbcAdminAccountStore.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminTokenValidity.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminTokenValidityStore.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/persistence/admin/JdbcAdminTokenValidityStore.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcAdminTokenValidityStoreTests.java`

**Interfaces:**
- Produces: `AdminTokenValidity(AdminAccountStatus status, Instant tokensValidAfter)`
- Produces: `AdminTokenValidityStore.findByAdminId(UUID): Optional<AdminTokenValidity>`
- Produces: `AdminAccountStore.changeStatus(UUID, AdminAccountStatus, Instant): void`

- [ ] **Step 1: Write migration and JDBC contract tests that fail because V044 and validity APIs do not exist**

```java
assertThat(columns("onmaru", "identity_admin_accounts"))
        .contains("tokens_valid_after");
assertThat(columns("onmaru", "identity_admin_access_token_revocations"))
        .contains("jti_hash", "admin_id", "expires_at", "revoked_at");

store.changeStatus(adminId, AdminAccountStatus.DISABLED, Instant.parse("2026-10-06T00:00:00.500Z"));
assertThat(validityStore.findByAdminId(adminId).orElseThrow().tokensValidAfter())
        .isEqualTo(Instant.parse("2026-10-06T00:00:01Z"));
```

- [ ] **Step 2: Run the focused tests and verify compilation/schema assertions fail for the missing contract**

Run: `./gradlew :apps:spring-api:test --tests '*DatabaseMigrationContractTests' --tests '*JdbcAdminTokenValidityStoreTests'`

Expected: FAIL because V044 and `AdminTokenValidityStore` are absent.

- [ ] **Step 3: Add V044 and implement atomic status/boundary persistence**

```sql
ALTER TABLE onmaru.identity_admin_accounts
    ADD COLUMN tokens_valid_after timestamptz NOT NULL DEFAULT '-infinity';

CREATE TABLE onmaru.identity_admin_access_token_revocations (
    jti_hash varchar(64) PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES onmaru.identity_admin_accounts (id),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_admin_access_token_revocations_hash_ck
        CHECK (jti_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX identity_admin_access_token_revocations_expires_at_idx
    ON onmaru.identity_admin_access_token_revocations (expires_at);
```

Implement `changeStatus` with one `UPDATE`; for inactive states set `tokens_valid_after = greatest(tokens_valid_after, date_trunc('second', ?) + interval '1 second')` and update `status`/`updated_at` in the same statement. Extend `AdminAccount` with `tokensValidAfter` and preserve it in `withStatus`.

- [ ] **Step 4: Run focused migration and validity tests**

Run: `./gradlew :apps:spring-api:test --tests '*DatabaseMigrationContractTests' --tests '*JdbcAdminTokenValidityStoreTests'`

Expected: PASS.

- [ ] **Step 5: Commit the schema boundary**

```bash
git add apps/spring-api/src/main/resources/db/migration/baseline/V044__492_admin_jwt_revocation.sql apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth apps/spring-api/src/main/java/com/yrootlab/onmaru/persistence/admin apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres
git commit -m "feat(security): 관리자 token 유효 경계 영속화"
```

### Task 2: JTI hash와 JDBC 폐기 저장소

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminJtiHasher.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminJtiRevocationStore.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/InMemoryAdminJtiRevocationStore.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/persistence/admin/JdbcAdminJtiRevocationStore.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/admin/auth/AdminJtiHasherTests.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/JdbcAdminJtiRevocationStoreTests.java`

**Interfaces:**
- Consumes: V044 `identity_admin_access_token_revocations`
- Produces: `AdminJtiHasher.hash(String): String`
- Produces: `AdminJtiRevocationStore.revoke(UUID, String, Instant): void`
- Produces: `AdminJtiRevocationStore.isRevoked(String, Instant): boolean`
- Produces: `AdminJtiRevocationStore.deleteExpired(Instant, int): int`

- [ ] **Step 1: Write failing hash, persistence, restart, concurrency, expiry, cleanup, and SQL failure tests**

```java
assertThat(hasher.hash("jti-1"))
        .isEqualTo("5964da055ae31cf8eb7230e190a504899c6f60f07f9f1cd3cade9fbf226e8c28");
first.revoke(adminId, "secret-jti", expiresAt);
assertThat(second.isRevoked("secret-jti", now)).isTrue();
assertThat(second.isRevoked("secret-jti", expiresAt)).isFalse();
assertThat(second.deleteExpired(expiresAt, 100)).isEqualTo(1);
```

Use two store objects over the same Testcontainers DataSource for restart/multi-instance semantics, and concurrent executors for idempotent upsert. Inspect the row and assert `jti_hash` is 64 hex characters and neither the JTI nor token text is stored.

- [ ] **Step 2: Run focused tests and verify they fail because the hasher/JDBC store do not exist**

Run: `./gradlew :apps:spring-api:test --tests '*AdminJtiHasherTests' --tests '*JdbcAdminJtiRevocationStoreTests'`

Expected: FAIL at compilation for missing types/methods.

- [ ] **Step 3: Implement minimal hashing and JDBC operations**

```sql
INSERT INTO onmaru.identity_admin_access_token_revocations
    (jti_hash, admin_id, expires_at, revoked_at)
VALUES (?, ?, ?, ?)
ON CONFLICT (jti_hash) DO UPDATE
SET expires_at = greatest(identity_admin_access_token_revocations.expires_at, EXCLUDED.expires_at);
```

`isRevoked` must query `jti_hash = ? AND expires_at > ?`. `deleteExpired` must select a bounded batch and delete only `expires_at <= now`; JDBC exceptions become a dedicated `AdminTokenStoreException` without token material in the message.

- [ ] **Step 4: Run focused tests and refactor only after green**

Run: `./gradlew :apps:spring-api:test --tests '*AdminJtiHasherTests' --tests '*JdbcAdminJtiRevocationStoreTests'`

Expected: PASS with concurrent upsert producing one row.

- [ ] **Step 5: Commit the JTI store**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth apps/spring-api/src/main/java/com/yrootlab/onmaru/persistence/admin apps/spring-api/src/test/java/com/yrootlab/onmaru/admin/auth apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres
git commit -m "feat(security): 관리자 JTI 폐기 상태 영속화"
```

### Task 3: JWT 계정 경계 검증과 fail-closed 오류 구분

**Files:**
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminJwtTokenCodec.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminAuthenticator.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminAuthenticationUnavailableException.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/admin/auth/AdminJwtTokenCodecTests.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/admin/auth/AdminAuthenticatorTests.java`

**Interfaces:**
- Consumes: `AdminTokenValidityStore`, `AdminJtiRevocationStore`
- Produces: `AdminAuthenticator.authenticateToken(String): AdminAccessToken`
- Preserves: `AdminAuthenticator.authenticate(String): AdminPrincipal`

- [ ] **Step 1: Write failing tests for inactive account, `iat` boundary, revoked JTI, and store exception**

```java
assertThatThrownBy(() -> codec.verifyToken(tokenIssuedBeforeBoundary))
        .isInstanceOf(AdminAuthenticationException.class);
assertThatThrownBy(() -> codec.verifyToken(validTokenWhenStoreThrows))
        .isInstanceOf(AdminAuthenticationUnavailableException.class);
```

The test must prove a token issued exactly at `tokens_valid_after` is accepted and a token one second earlier is rejected.

- [ ] **Step 2: Run auth tests and verify expected failures**

Run: `./gradlew :apps:spring-api:test --tests '*AdminJwtTokenCodecTests' --tests '*AdminAuthenticatorTests'`

Expected: FAIL because account validity is not checked and storage errors collapse into authentication failure.

- [ ] **Step 3: Implement ordered verification**

After signature/issuer/audience/time claim verification, parse `sub`, `iat`, `jti`, then:

```java
AdminTokenValidity validity = validityStore.findByAdminId(principal.id())
        .orElseThrow(AdminAuthenticationException::new);
if (validity.status() != ACTIVE || issuedAt.isBefore(validity.tokensValidAfter())) {
    throw new AdminAuthenticationException();
}
if (revokedJtis.isRevoked(jti, now)) {
    throw new AdminAuthenticationException();
}
```

Catch `AdminTokenStoreException` separately and translate it to `AdminAuthenticationUnavailableException`; do not let the generic malformed-token catch turn it into 401.

- [ ] **Step 4: Run focused auth tests**

Run: `./gradlew :apps:spring-api:test --tests '*AdminJwtTokenCodecTests' --tests '*AdminAuthenticatorTests'`

Expected: PASS.

- [ ] **Step 5: Commit account-aware verification**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth apps/spring-api/src/test/java/com/yrootlab/onmaru/admin/auth
git commit -m "feat(security): 관리자 JWT 계정 경계 검증"
```

### Task 4: logout 즉시 access token 폐기

**Files:**
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminTokenRevocationService.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/admin/AdminAuthController.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/admin/AdminApiWebBoundaryTests.java`

**Interfaces:**
- Consumes: `authenticateToken(String): AdminAccessToken`
- Consumes: `AdminJtiRevocationStore.revoke(UUID, String, Instant)`
- Produces: logout `204`, subsequent `/me` `401`, storage outage `503 AUTH_UNAVAILABLE`

- [ ] **Step 1: Write failing web tests for immediate rejection and 503 behavior**

```java
String accessToken = loginAndReadAccessToken();
logout(accessToken).andExpect(status().isNoContent());
me(accessToken).andExpect(status().isUnauthorized());

logoutWithFailingStore(accessToken)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
```

Also assert the failed logout response does not emit a successful refresh-cookie deletion header.

- [ ] **Step 2: Run the web test and verify logout currently leaves the access token usable**

Run: `./gradlew :apps:spring-api:test --tests '*AdminApiWebBoundaryTests'`

Expected: FAIL because `/me` still returns 200 and storage failure becomes 401.

- [ ] **Step 3: Revoke the verified access token before the refresh session**

```java
AdminAccessToken token = authenticator.authenticateToken(authorization);
tokenRevocationService.revoke(token);
sessionService.revoke(refreshToken);
```

Inject `AdminTokenRevocationService`, map `AdminAuthenticationUnavailableException`/`AdminTokenStoreException` to `503 AUTH_UNAVAILABLE`, and retain `401 AUTH_REQUIRED` for invalid JWTs.

- [ ] **Step 4: Run the web and auth regression tests**

Run: `./gradlew :apps:spring-api:test --tests '*AdminApiWebBoundaryTests' --tests '*AdminJwtTokenCodecTests'`

Expected: PASS.

- [ ] **Step 5: Commit logout revocation**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/admin apps/spring-api/src/main/java/com/yrootlab/onmaru/web/admin apps/spring-api/src/test/java/com/yrootlab/onmaru/web/admin
git commit -m "feat(security): 관리자 logout access token 폐기"
```

### Task 5: profile 안전장치, cleanup, metric

**Files:**
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminAuthConfiguration.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/ObservedAdminJtiRevocationStore.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/admin/auth/AdminRevocationStoreStartupValidator.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/scheduling/admin/AdminJtiRevocationCleanupJob.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/admin/auth/AdminAuthConfigurationTests.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/admin/auth/ObservedAdminJtiRevocationStoreTests.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/scheduling/admin/AdminJtiRevocationCleanupJobTests.java`

**Interfaces:**
- Consumes: `deleteExpired(Instant, int): int`
- Produces: `production` JDBC beans, `!production` memory beans
- Produces: configurable `onmaru.admin.jwt-revocation.cleanup-cron`, batch size, max batches

- [ ] **Step 1: Write failing context, startup, cleanup, and metric tests**

```java
assertThat(context.getBean(AdminJtiRevocationStore.class))
        .isInstanceOf(ObservedAdminJtiRevocationStore.class);
assertThat(observedStore.delegate()).isInstanceOf(JdbcAdminJtiRevocationStore.class);
assertThatThrownBy(() -> validator.validate(new InMemoryAdminJtiRevocationStore()))
        .isInstanceOf(IllegalStateException.class);
```

Metric tests assert lookup/write timers use only bounded `result` tags; cleanup tests assert expired row count aggregation and stop at max batches.

- [ ] **Step 2: Run focused tests and verify missing profile isolation/observability fails**

Run: `./gradlew :apps:spring-api:test --tests '*AdminAuthConfigurationTests' --tests '*ObservedAdminJtiRevocationStoreTests' --tests '*AdminJtiRevocationCleanupJobTests'`

Expected: FAIL for missing classes and production still selecting memory.

- [ ] **Step 3: Implement profile wiring and bounded cleanup**

Use `@Profile("!production")` for memory beans and `@Profile("production")` for JDBC beans. The observation decorator records `clear|revoked|error` lookup and `success|error` write results without identifiers. Schedule cleanup hourly at minute 10 by default and cap work using configured batch size/max batches.

- [ ] **Step 4: Run focused tests and production context smoke test**

Run: `./gradlew :apps:spring-api:test --tests '*AdminAuthConfigurationTests' --tests '*ObservedAdminJtiRevocationStoreTests' --tests '*AdminJtiRevocationCleanupJobTests' --tests '*SecretConfigurationTests'`

Expected: PASS.

- [ ] **Step 5: Commit operational wiring**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/admin apps/spring-api/src/main/java/com/yrootlab/onmaru/scheduling/admin apps/spring-api/src/test/java/com/yrootlab/onmaru/admin apps/spring-api/src/test/java/com/yrootlab/onmaru/scheduling/admin
git commit -m "feat(security): 관리자 JWT 폐기 운영 안전장치 추가"
```

### Task 6: 계약·schema·runbook과 전체 검증

**Files:**
- Modify: `docs/contracts/openapi/admin.openapi.yaml`
- Modify: `docs/database/schema.md`
- Modify: `docs/database/azimutt/onmaru-schema.postgres.sql`
- Create: `docs/operations/runbooks/admin-jwt-revocation.md`
- Modify: `handoff.md`

**Interfaces:**
- Documents: logout `503 AUTH_UNAVAILABLE`, DB tables, cleanup/incident commands
- Verifies: Issue #492 acceptance criteria and repository CI baseline

- [ ] **Step 1: Update the API contract, database docs, and runbook**

The runbook must include safe count-only SQL:

```sql
SELECT count(*) AS unexpired_revocations
FROM onmaru.identity_admin_access_token_revocations
WHERE expires_at > CURRENT_TIMESTAMP;

SELECT count(*) AS expired_revocations
FROM onmaru.identity_admin_access_token_revocations
WHERE expires_at <= CURRENT_TIMESTAMP;
```

Document alert signals, PostgreSQL outage behavior, recovery verification, manual cleanup safety, and the prohibition on logging token/JTI/hash values.

- [ ] **Step 2: Record exact verification evidence in `handoff.md`**

Include branch, Issue #492, commits, touched areas, commands, results, next PR step, and any residual operational risk.

- [ ] **Step 3: Run focused Java verification**

Run: `./gradlew :apps:spring-api:test --tests '*Admin*' --tests '*DatabaseMigrationContractTests'`

Expected: PASS.

- [ ] **Step 4: Run repository baseline verification**

Run: `./gradlew test`

Run: `python3 -m pytest ai/tests`

Run: `node scripts/verify-project-harness.mjs`

Run: `node scripts/verify-documentation.mjs`

Expected: all commands PASS with no secret/token material in output.

- [ ] **Step 5: Inspect final diff and commit documentation**

```bash
git diff --check
git status --short
git add docs/contracts/openapi/admin.openapi.yaml docs/database/schema.md docs/database/azimutt/onmaru-schema.postgres.sql docs/operations/runbooks/admin-jwt-revocation.md handoff.md
git commit -m "docs(security): 관리자 JWT 폐기 운영 절차 문서화"
```

- [ ] **Step 6: Reconcile the Issue and prepare PR evidence**

Run: `node scripts/print-branch-issue.mjs`

Because the current externally managed branch name does not match the repository parser contract, record Issue #492 explicitly in the PR body with `Closes #492`. Do not close #492 until the PR is merged into `develop`, acceptance criteria are verified, and the post-merge Issue reconciliation is complete.
