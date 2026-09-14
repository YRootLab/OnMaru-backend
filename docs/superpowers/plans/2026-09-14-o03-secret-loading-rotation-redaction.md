# O03 Secret Loading Rotation Redaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement server-only secret loading, startup validation, rotation overlap, and log redaction for Spring API and FastAPI AI service.

**Architecture:** Each runtime gets a small `config/secrets` boundary with a provider interface, fake local/test provider, environment provider, validation, and redaction utility. Production-like validation is triggered by selecting the environment provider, while default dev/test remains deterministic and does not require real credentials.

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5, Python 3.12, FastAPI, pytest, GitHub Actions CI.

## Global Constraints

- Do not record real secret values in code, docs, logs, fixtures, or tests.
- Keep dev/test fake secrets separate from production environment secret loading.
- Required secret absence must fail startup safely when the environment provider is selected.
- Rotation must support current and previous values during overlap.
- Log redaction tests must cover current and previous secret values.
- CI must execute the added Spring and FastAPI tests.

---

### Task 1: Spring Secret Boundary

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/SecretBundle.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/SecretProvider.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/SecretRedactor.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/OnMaruSecretProperties.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/FakeSecretProvider.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/EnvironmentSecretProvider.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/config/secrets/SecretConfiguration.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/config/secrets/SecretConfigurationTests.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/config/secrets/SecretRedactorTests.java`

**Interfaces:**
- Produces: `SecretProvider#get(String name): SecretBundle`
- Produces: `SecretBundle#matches(String value): boolean`
- Produces: `SecretRedactor#redact(String message): String`

- [ ] Write failing Spring tests for missing required environment secrets, fake provider startup, rotation overlap, and redaction.
- [ ] Run focused Spring tests and verify they fail because the secret boundary does not exist.
- [ ] Implement the minimal Spring secret boundary.
- [ ] Run focused Spring tests and verify they pass.

### Task 2: FastAPI Secret Boundary

**Files:**
- Create: `ai/src/onmaru_ai/config/__init__.py`
- Create: `ai/src/onmaru_ai/config/secrets.py`
- Create: `ai/tests/test_secrets.py`
- Modify: `ai/src/onmaru_ai/main.py`

**Interfaces:**
- Produces: `SecretProvider.get(name: str) -> SecretBundle`
- Produces: `SecretBundle.matches(value: str) -> bool`
- Produces: `SecretRedactor.redact(message: str) -> str`
- Produces: `create_app(secret_provider: SecretProvider | None = None) -> FastAPI`

- [ ] Write failing FastAPI tests for missing required environment secrets, fake provider startup, rotation overlap, and redaction.
- [ ] Run focused pytest and verify it fails because the secret boundary does not exist.
- [ ] Implement the minimal FastAPI secret boundary and wire readiness through validation.
- [ ] Run focused pytest and verify it passes.

### Task 3: Runbook And CI Coverage

**Files:**
- Create: `docs/operations/runbooks/secrets.md`
- Modify: `.github/workflows/ci.yml` if the existing workflow does not execute the new tests.

- [ ] Add the secret loading and rotation drill runbook without real values.
- [ ] Confirm `.github/workflows/ci.yml` runs `./gradlew test` and `uv run pytest`; add only missing coverage.
- [ ] Run full repository verification used by CI.
