# Public Health API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swagger에서 호출할 수 있는 공개 `GET /api/v1/health` liveness API를 제공한다.

**Architecture:** `apps/spring-api` web 계층에 외부 의존성이 없는 전용 controller와 response record를 추가한다. 실제 HTTP 응답과 생성된 OpenAPI 문서를 Spring Boot 통합 테스트로 검증한다.

**Tech Stack:** Java 21, Spring Boot WebMVC, springdoc-openapi, JUnit 5, MockMvc

## Global Constraints

- 응답 과정에서 PostgreSQL, Neon, TourAPI를 호출하지 않는다.
- `GET /api/v1/health`는 HTTP 200과 `{"status":"UP"}`만 공개한다.
- 기존 `/actuator/health`는 변경하지 않는다.
- Render sleep 상태의 첫 요청에는 cold start 지연이 발생할 수 있다.

---

### Task 1: 공개 liveness 및 OpenAPI 계약

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/health/PublicHealthController.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/health/PublicHealthResponse.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/HealthEndpointTests.java`
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/swagger/SwaggerEndpointTests.java`

**Interfaces:**
- Consumes: Spring WebMVC와 springdoc annotation
- Produces: `GET /api/v1/health`, JSON object `{ "status": "UP" }`

- [ ] **Step 1: 실제 HTTP 계약의 실패 테스트 작성**

`HealthEndpointTests`에 다음 테스트를 추가한다.

```java
@Test
void publicHealthEndpointReportsUp() throws Exception {
    mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
}
```

- [ ] **Step 2: 테스트가 404로 실패하는지 확인**

Run: `./gradlew :apps:spring-api:test --tests com.yrootlab.onmaru.HealthEndpointTests.publicHealthEndpointReportsUp --no-daemon`

Expected: FAIL, expected 200 but received 404.

- [ ] **Step 3: OpenAPI 계약의 실패 테스트 작성**

`SwaggerEndpointTests.openApiDocsEndpointReturnsValidSpec`에 다음 assertion을 추가한다.

```java
.andExpect(jsonPath("$.paths['/api/v1/health'].get").exists())
.andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['200']").exists())
```

- [ ] **Step 4: 최소 controller와 response 구현**

`PublicHealthResponse`:

```java
package com.yrootlab.onmaru.web.health;

record PublicHealthResponse(String status) {
    static PublicHealthResponse up() {
        return new PublicHealthResponse("UP");
    }
}
```

`PublicHealthController`:

```java
package com.yrootlab.onmaru.web.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "99. 운영 상태 (Health)", description = "서버 liveness 확인 API")
@RestController
public final class PublicHealthController {
    @Operation(summary = "서버 상태 확인", description = "외부 저장소에 의존하지 않고 애플리케이션의 liveness를 확인합니다.")
    @ApiResponse(responseCode = "200", description = "서버가 요청을 처리할 수 있음")
    @GetMapping("/api/v1/health")
    PublicHealthResponse health() {
        return PublicHealthResponse.up();
    }
}
```

- [ ] **Step 5: 대상 테스트와 전체 Spring API 테스트 확인**

Run: `./gradlew :apps:spring-api:test --tests com.yrootlab.onmaru.HealthEndpointTests --tests com.yrootlab.onmaru.web.swagger.SwaggerEndpointTests --no-daemon`

Expected: PASS.

Run: `./gradlew :apps:spring-api:test --no-daemon`

Expected: PASS.

- [ ] **Step 6: 구현 커밋**

```bash
git add apps/spring-api/src/main/java/com/yrootlab/onmaru/web/health \
  apps/spring-api/src/test/java/com/yrootlab/onmaru/HealthEndpointTests.java \
  apps/spring-api/src/test/java/com/yrootlab/onmaru/web/swagger/SwaggerEndpointTests.java \
  docs/superpowers/plans/2026-09-28-public-health-api.md
git commit -m "feat(api): Swagger 공개 헬스체크 추가"
```
