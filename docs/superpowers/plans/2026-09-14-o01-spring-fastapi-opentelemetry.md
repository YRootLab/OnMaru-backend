# O01 Spring FastAPI OpenTelemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring API와 FastAPI AI 서비스가 requestId, traceId, runId, revision을 안전하게 상관 분석할 수 있도록 구조화 로그와 OpenTelemetry 계측 경계를 추가한다.

**Architecture:** 각 런타임의 HTTP boundary에서 correlation context를 추출하거나 생성하고 응답 헤더, 로그 컨텍스트, telemetry attribute에 동일하게 반영한다. 민감한 query, location, cookie, token, evidence body는 기록하지 않고 low-cardinality HTTP label만 남긴다.

**Tech Stack:** Java 21, Spring Boot 4.1, Micrometer/OpenTelemetry dependency boundary, Python 3.12, FastAPI, pytest, ruff, mypy, GitHub Actions.

## Global Constraints

- GitHub Issue #71 Acceptance Criteria: cross-service trace correlation.
- GitHub Issue #71 Acceptance Criteria: 민감 fixture가 log/metric/trace에 없음.
- 금지 필드: query, location, cookie, token, evidence body.
- Dashboard 구성은 out of scope.
- 관련 모듈: `apps/spring-api/observability`, `ai/src/onmaru_ai/observability`.
- TDD: production behavior를 추가하기 전에 실패하는 테스트를 먼저 작성하고 확인한다.

---

### Task 1: Spring HTTP Observability Boundary

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/observability/CorrelationContext.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/observability/TelemetryEvent.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/observability/TelemetrySink.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/observability/InMemoryTelemetrySink.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/observability/CorrelationFilter.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/observability/CorrelationFilterTests.java`
- Modify: `apps/spring-api/build.gradle.kts`

**Interfaces:**
- Consumes: HTTP headers `X-Request-Id`, `X-Run-Id`, `X-Revision`, and optional W3C `traceparent`.
- Produces: response headers `X-Request-Id`, `X-Run-Id`, `X-Revision`; telemetry event attributes `request.id`, `trace.id`, `run.id`, `revision`, `http.request.method`, `http.route`, `http.response.status_code`.

- [ ] **Step 1: Write failing Spring MockMvc tests**

```java
@Test
void correlatesRequestRunRevisionAndTraceWithoutSensitiveAttributes() throws Exception {
    mockMvc.perform(get("/actuator/health")
                    .header("X-Request-Id", "req-123")
                    .header("X-Run-Id", "run-456")
                    .header("X-Revision", "rev-789")
                    .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                    .header("Cookie", "session=secret")
                    .header("Authorization", "Bearer secret-token")
                    .queryParam("query", "secret")
                    .queryParam("location", "secret"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "req-123"))
            .andExpect(header().string("X-Run-Id", "run-456"))
            .andExpect(header().string("X-Revision", "rev-789"));

    TelemetryEvent event = telemetrySink.events().getFirst();
    assertThat(event.attributes())
            .containsEntry("request.id", "req-123")
            .containsEntry("trace.id", "4bf92f3577b34da6a3ce929d0e0e4736")
            .containsEntry("run.id", "run-456")
            .containsEntry("revision", "rev-789")
            .containsEntry("http.request.method", "GET")
            .containsEntry("http.response.status_code", "200");
    assertThat(event.attributes().keySet()).doesNotContain("query", "location", "cookie", "token", "evidence.body");
    assertThat(event.attributes().values()).doesNotContain("secret", "secret-token", "session=secret");
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :apps:spring-api:test --tests '*CorrelationFilterTests' --no-daemon`
Expected: FAIL because observability classes do not exist.

- [ ] **Step 3: Implement Spring filter and telemetry sink**

Implement a servlet filter that extracts/generates IDs, parses `traceparent`, sets response headers, records sanitized low-cardinality attributes, and clears MDC after the request.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :apps:spring-api:test --tests '*CorrelationFilterTests' --no-daemon`
Expected: PASS.

### Task 2: FastAPI HTTP Observability Boundary

**Files:**
- Create: `ai/src/onmaru_ai/observability/__init__.py`
- Create: `ai/src/onmaru_ai/observability/correlation.py`
- Create: `ai/tests/test_observability.py`
- Modify: `ai/src/onmaru_ai/main.py`
- Modify: `ai/pyproject.toml`

**Interfaces:**
- Consumes: HTTP headers `X-Request-Id`, `X-Run-Id`, `X-Revision`, and optional W3C `traceparent`.
- Produces: response headers `X-Request-Id`, `X-Run-Id`, `X-Revision`; telemetry records with the same sanitized attributes as Spring.

- [ ] **Step 1: Write failing FastAPI tests**

```python
def test_correlates_request_run_revision_and_trace_without_sensitive_attributes() -> None:
    sink = InMemoryTelemetrySink()
    app = create_app(telemetry_sink=sink)
    response = asyncio.run(request(app, "/ready?query=secret&location=secret", headers={
        "X-Request-Id": "req-123",
        "X-Run-Id": "run-456",
        "X-Revision": "rev-789",
        "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "Cookie": "session=secret",
        "Authorization": "Bearer secret-token",
    }))
    assert response.headers["X-Request-Id"] == "req-123"
    event = sink.events[0]
    assert event.attributes["trace.id"] == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert "query" not in event.attributes
    assert "secret" not in event.attributes.values()
```

- [ ] **Step 2: Verify RED**

Run: `cd ai && uv run pytest tests/test_observability.py -q`
Expected: FAIL because observability module does not exist.

- [ ] **Step 3: Implement FastAPI middleware**

Implement middleware with generated defaults, `traceparent` parsing, response headers, and sanitized low-cardinality telemetry attributes.

- [ ] **Step 4: Verify GREEN**

Run: `cd ai && uv run pytest tests/test_observability.py -q`
Expected: PASS.

### Task 3: CI Quality Gate

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: CI steps for FastAPI `ruff check`, `mypy`, and existing `pytest`.

- [ ] **Step 1: Update CI to run FastAPI lint/type/test**

Use `uv run ruff check .`, `uv run mypy`, and `uv run pytest` in `ai`.

- [ ] **Step 2: Verify locally**

Run:
```bash
./gradlew test --no-daemon
cd ai && uv run ruff check . && uv run mypy && uv run pytest
```

Expected: all checks PASS.

## Self-Review

- Spec coverage: Spring correlation, FastAPI correlation, redaction, CI verification covered.
- Placeholder scan: no deferred TODO/TBD content remains.
- Type consistency: telemetry attribute names are consistent across Spring and FastAPI.
