# Spring·FastAPI Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 전역 Gradle이나 시스템 Python에 의존하지 않고 Spring API와 FastAPI AI 서비스를 실행·테스트하며, 같은 장비의 예열된 캐시로 오프라인 검증할 수 있는 개발 기반을 만든다.

**Architecture:** 저장소 루트의 Gradle 멀티프로젝트가 `apps/spring-api` 실행 모듈을 소유하고, `ai/`는 독립적인 uv Python 프로젝트로 유지한다. 두 프로세스는 DB나 외부 API 키 없이 시작하며 각자의 health 계약을 실제 HTTP/ASGI 테스트로 검증한다.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Gradle Wrapper/Kotlin DSL, JUnit 5, Python 3.12, uv, FastAPI, Uvicorn, pytest, HTTPX, Ruff, mypy

## Global Constraints

- Java Toolchain은 21이고 Spring Boot는 4.1.1이다.
- Spring의 최초 의존성은 Web MVC와 Actuator로 제한한다.
- Python은 3.12 minor 계열과 uv를 사용하고 `uv.lock`을 커밋한다.
- FastAPI runtime dependency는 FastAPI와 Uvicorn, development dependency는 pytest, HTTPX, Ruff, mypy다.
- DB 자격 증명과 외부 API 키 없이 두 애플리케이션이 시작해야 한다.
- 생성물과 캐시는 커밋하지 않고 Wrapper, lockfile, 안전한 예제 설정만 커밋한다.

---

## File Structure

- `settings.gradle.kts`: Gradle plugin repository와 `apps:spring-api` 모듈 등록.
- `build.gradle.kts`: Java 21과 공통 repository를 정의하는 루트 빌드.
- `gradle/libs.versions.toml`: Spring Boot와 dependency-management plugin 버전의 단일 원본.
- `gradle/wrapper/*`, `gradlew`, `gradlew.bat`: 전역 Gradle 없는 재현 가능한 실행 진입점.
- `settings-gradle.lockfile`, `apps/spring-api/gradle.lockfile`: 해석된 plugin과 애플리케이션 의존성 버전 고정.
- `apps/spring-api/build.gradle.kts`: Spring Web, Actuator, test starter 의존성.
- `apps/spring-api/src/main/java/com/yrootlab/onmaru/OnMaruApplication.java`: Spring 실행 진입점.
- `apps/spring-api/src/test/java/com/yrootlab/onmaru/OnMaruApplicationTests.java`: context smoke test.
- `apps/spring-api/src/test/java/com/yrootlab/onmaru/HealthEndpointTests.java`: 실제 Actuator health HTTP 계약 테스트.
- `ai/pyproject.toml`: Python version, runtime/dev dependency와 lint/type/test 설정.
- `ai/.python-version`, `ai/uv.lock`: Python minor 선택과 완전한 dependency lock.
- `ai/src/onmaru_ai/main.py`: app factory와 health/readiness endpoint.
- `ai/tests/test_health.py`: 실제 ASGI health/readiness 계약 테스트.
- `README.md`: 공통 bootstrap 및 검증 명령.
- `apps/spring-api/README.md`: IntelliJ와 Spring 실행 방법.
- `ai/README.md`: VS Code interpreter와 FastAPI 실행 방법.

### Task 1: 재현 가능한 Gradle 멀티프로젝트 기반

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: 로컬 Java 21과 Spring Initializr의 Gradle Kotlin scaffold.
- Produces: `./gradlew` 명령과 `:apps:spring-api` project path.

- [x] **Step 1: 공식 Initializr 결과를 임시 디렉터리에 생성한다**

```bash
curl -fsSLG https://start.spring.io/starter.zip \
  --data-urlencode type=gradle-project-kotlin \
  --data-urlencode language=java \
  --data-urlencode bootVersion=4.1.1 \
  --data-urlencode javaVersion=21 \
  --data-urlencode groupId=com.yrootlab.onmaru \
  --data-urlencode artifactId=spring-api \
  --data-urlencode packageName=com.yrootlab.onmaru \
  --data-urlencode dependencies=web,actuator \
  --output /tmp/onmaru-spring-api.zip
```

- [x] **Step 2: Wrapper를 저장소 루트에 두고 멀티프로젝트 빌드 파일을 작성한다**

`settings.gradle.kts`는 `rootProject.name = "onmaru-backend"`와 `include("apps:spring-api")`를 정의한다. 루트 `build.gradle.kts`는 모든 Java subproject에 Maven Central과 Java 21 toolchain을 적용하고 dependency locking을 활성화한다.

- [x] **Step 3: Wrapper와 project graph를 검증한다**

Run: `./gradlew --version && ./gradlew projects`

Expected: Wrapper가 고정된 Gradle 배포판을 내려받고 `:apps:spring-api`를 표시한다.

- [x] **Step 4: 기반 파일을 커밋한다**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat
git commit -m "build: initialize Gradle multi-project"
```

### Task 2: Spring 실행과 Health 계약

**Files:**
- Create: `apps/spring-api/build.gradle.kts`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/OnMaruApplicationTests.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/HealthEndpointTests.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/OnMaruApplication.java`
- Create: `apps/spring-api/src/main/resources/application.yaml`
- Create: `settings-gradle.lockfile`
- Create: `apps/spring-api/gradle.lockfile`

**Interfaces:**
- Consumes: Task 1의 `:apps:spring-api` project path.
- Produces: `com.yrootlab.onmaru.OnMaruApplication`과 `GET /actuator/health`의 HTTP 200/`UP` 계약.

- [x] **Step 1: 실패하는 Spring 테스트를 작성한다**

```java
@SpringBootTest
class OnMaruApplicationTests {
    @Test
    void startsWithoutExternalCredentials() {
    }
}
```

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTests {
    @Autowired TestRestTemplate restTemplate;

    @Test
    void reportsUpForLiveness() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [x] **Step 2: 테스트가 main class 부재로 실패하는지 확인한다**

Run: `./gradlew :apps:spring-api:test`

Expected: FAIL because a `@SpringBootConfiguration`/`OnMaruApplication` cannot be found.

- [x] **Step 3: 최소 Spring 애플리케이션을 구현한다**

```java
package com.yrootlab.onmaru;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OnMaruApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnMaruApplication.class, args);
    }
}
```

`application.yaml`은 health detail을 노출하지 않고 `health` endpoint만 공개한다.

- [x] **Step 4: Spring 테스트와 dependency lock을 검증한다**

Run: `./gradlew :apps:spring-api:dependencies --write-locks && ./gradlew :apps:spring-api:test && ./gradlew check`

Expected: 모든 task가 성공하고 두 테스트가 통과한다.

- [x] **Step 5: Spring 동작을 커밋한다**

```bash
git add apps/spring-api settings-gradle.lockfile
git commit -m "feat: add Spring API health bootstrap"
```

### Task 3: FastAPI 실행과 Health·Readiness 계약

**Files:**
- Create: `ai/.python-version`
- Create: `ai/pyproject.toml`
- Create: `ai/uv.lock`
- Create: `ai/src/onmaru_ai/__init__.py`
- Create: `ai/tests/test_health.py`
- Create: `ai/src/onmaru_ai/main.py`

**Interfaces:**
- Consumes: Python 3.12 resolved by uv.
- Produces: `onmaru_ai.main:create_app`, `GET /health`, `GET /ready`, 각각 JSON `{"status":"ok"}`와 `{"status":"ready"}`.

- [x] **Step 1: Python 프로젝트 설정과 lock을 생성한다**

`pyproject.toml`에 `requires-python = ">=3.12,<3.13"`, src layout, runtime dependencies, development dependency group, Ruff와 strict mypy 설정을 작성한 뒤 실행한다.

Run: `cd ai && uv lock && uv sync --frozen --all-groups`

Expected: uv가 호환 Python과 모든 패키지를 받고 `ai/.venv`를 만든다.

- [x] **Step 2: 실패하는 ASGI 계약 테스트를 작성한다**

```python
from fastapi.testclient import TestClient
from onmaru_ai.main import create_app

client = TestClient(create_app())

def test_health_reports_process_is_alive() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_readiness_reports_service_can_accept_requests() -> None:
    response = client.get("/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ready"}
```

- [x] **Step 3: 테스트가 애플리케이션 모듈 부재로 실패하는지 확인한다**

Run: `cd ai && uv run pytest tests/test_health.py -v`

Expected: FAIL with `ModuleNotFoundError: onmaru_ai.main`.

- [x] **Step 4: 최소 app factory와 endpoint를 구현한다**

```python
from fastapi import FastAPI

def create_app() -> FastAPI:
    app = FastAPI(title="OnMaru AI")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    async def readiness() -> dict[str, str]:
        return {"status": "ready"}

    return app

app = create_app()
```

- [x] **Step 5: Python 품질 검증을 실행한다**

Run: `cd ai && uv run ruff check . && uv run mypy src tests && uv run pytest -v`

Expected: lint, typecheck와 두 ASGI 테스트가 모두 통과한다.

- [x] **Step 6: FastAPI 동작을 커밋한다**

```bash
git add ai/.python-version ai/pyproject.toml ai/uv.lock ai/src ai/tests
git commit -m "feat: add FastAPI health bootstrap"
```

### Task 4: IDE 안내와 캐시 기반 오프라인 검증

**Files:**
- Create: `README.md`
- Create: `apps/spring-api/README.md`
- Create: `ai/README.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: Task 2의 Gradle 명령과 Task 3의 uv 명령.
- Produces: IntelliJ, VS Code, terminal에서 재현 가능한 개발 시작 절차.

- [x] **Step 1: 실행 문서를 작성한다**

루트 README에는 요구 도구와 전체 검증 명령을, Spring README에는 IntelliJ Gradle import와 `bootRun`을, AI README에는 VS Code의 `ai/.venv` 선택과 Uvicorn 실행을 기록한다.

- [x] **Step 2: 온라인 전체 검증으로 캐시를 예열한다**

Run: `./gradlew --no-daemon check && (cd ai && uv sync --frozen --all-groups && uv run ruff check . && uv run mypy src tests && uv run pytest)`

Expected: Spring과 Python 검증이 모두 성공한다.

- [x] **Step 3: 같은 장비에서 오프라인 검증을 실행한다**

Run: `./gradlew --offline --no-daemon check && (cd ai && UV_OFFLINE=1 uv sync --frozen --all-groups && UV_OFFLINE=1 uv run pytest)`

Expected: 추가 package 다운로드 없이 모두 성공한다.

- [x] **Step 4: 저장소 위생을 확인한다**

Run: `git status --short && git check-ignore ai/.venv .gradle apps/spring-api/build .env`

Expected: 캐시와 secret fixture가 ignore되고 Wrapper, lockfile, source와 README만 변경 목록에 포함된다.

- [x] **Step 5: 문서와 ignore 규칙을 커밋한다**

```bash
git add README.md apps/spring-api/README.md ai/README.md .gitignore
git commit -m "docs: add local backend development guide"
```

### Task 5: Issue 완료 증거 정리

**Files:**
- Modify: `handoff.md`

**Interfaces:**
- Consumes: Task 1~4의 실행 결과와 commit IDs.
- Produces: #63·#64 PR/Issue에 옮길 수 있는 검증 증거와 재시작 문맥.

- [x] **Step 1: 변경 범위와 검증 결과를 기록한다**

`handoff.md`에 Spring/Python 버전, online/offline 명령 결과, 남은 범위와 Issue 번호를 기록한다.

- [x] **Step 2: 최종 검증을 반복한다**

Run: `git diff --check && ./gradlew --offline --no-daemon check && (cd ai && UV_OFFLINE=1 uv run ruff check . && UV_OFFLINE=1 uv run mypy src tests && UV_OFFLINE=1 uv run pytest)`

Expected: whitespace 오류가 없고 모든 검증이 offline mode에서 통과한다.

- [x] **Step 3: 작업 로그를 커밋한다**

```bash
git add handoff.md
git commit -m "docs: record backend bootstrap verification"
```
