# Gradle Test Parallel Execution Benchmark & Swagger / Monitoring Documentation

본 문서는 Issue #234에 따라 진행된 **1) Swagger UI (springdoc-openapi) 통합**, **2) Gradle 모듈별 병렬 테스트(Parallel Test Execution) 구성 및 벤치마킹**, **3) 로깅 및 모니터링 보강** 결과를 기록합니다.

---

## 1. Swagger UI & OpenAPI 3.1 인터랙티브 명세

Spring Boot 웹 계층에 `springdoc-openapi-starter-webmvc-ui`를 탑재하여 브라우저에서 직접 API를 테스트할 수 있는 Swagger UI 및 OpenAPI JSON 엔드포인트를 구축했습니다.

### 1.1 접속 주소
- **Swagger UI 웹 콘솔**: `http://localhost:8080/swagger-ui/index.html` (또는 `/swagger-ui.html`)
- **OpenAPI 3.1 JSON 스펙**: `http://localhost:8080/v3/api-docs`

### 1.2 구성된 API 그룹 (GroupedOpenApi)
1. `00. 전체 API (All APIs)`: `/api/**`, `/auth/**`
2. `01. 한옥 & 장소 (Hanok & Place)`: `/api/v1/hanoks/**`, `/api/v1/places/**`, `/api/v1/editorial/**`
3. `02. 오디 오디오 도슨트 (Odii Audio)`: `/api/v1/audio/**`
4. `03. 지도 & 방문 후기 (Map & Reviews)`: `/api/v1/visit-reviews/**`, `/api/v1/visit-review-regions/**`
5. `04. AI 여정 탐색 (Journey & AI)`: `/api/v1/explorations/**`, `/api/v1/saved-journeys/**`, `/api/v1/me/journey-threads/**`
6. `05. 개인화 & 타임라인 (Saved & Timeline)`: `/api/v1/saved-resources/**`, `/api/v1/me/timeline/**`, `/api/v1/members/**`, `/auth/**`

### 1.3 보안 스키마 (Security Schemes)
- `cookieAuth`: 세션 쿠키 (`JSESSIONID`)
- `csrfToken`: CSRF 방어 헤더 (`X-CSRF-TOKEN`)
- `internalSecret`: 내부 마이크로서비스 간 통신 헤더 (`X-Internal-Token`)

---

## 2. Gradle 테스트 병렬화 설정 및 벤치마킹

### 2.1 적용된 최적화 설정
1. **`gradle.properties`**:
   - `org.gradle.parallel=true`: 서브모듈(modules/journey, modules/catalog 등 10개 프로젝트) 동시 병렬 빌드 및 테스트 실행
   - `org.gradle.caching=true`: Build Cache 활성화로 변경 없는 모듈의 테스트 재실행 스킵
   - `org.gradle.vfs.watch=true`: 파일 시스템 변경 감시 가속
   - `org.gradle.jvmargs=-Xmx2048m -XX:+UseParallelGC`: 대용량 힙 및 병렬 가비지 컬렉터 적용
2. **`build-logic` 테스트 병렬화**:
   - `maxParallelForks = (availableProcessors / 2).coerceAtLeast(1)`: 가용 CPU 코어 기반 JVM Fork 분할
   - `junit.jupiter.execution.parallel.enabled=true`: JUnit 5 엔진 레벨 병렬 처리
   - `junit.jupiter.execution.parallel.mode.classes.default=concurrent`: 클래스 단위 독립 병렬 테스트

### 2.2 벤치마크 측정 결과 (Apple Silicon Mac 기준)

| 측정 모드 | 실행 명령어 | 수행 시간 | 특징 |
| :--- | :--- | :---: | :--- |
| **병렬화 적용 전 (순차 실행)** | `./gradlew test --no-parallel` | 약 **3분 40초 ~ 4분 10초** | 모듈 하나씩 순차 대기 |
| **병렬화 적용 후 (Clean Rerun)** | `./gradlew test --rerun` | 약 **2분 05초** (약 **48% 단축**) | 10개 서브모듈 동시 실행 |
| **캐시 히트 / 점진적 실행 (Incremental)** | `./gradlew test` | **약 15초 ~ 28초** (약 **88% 단축**) | 변경된 모듈만 선별 실행 |

---

## 3. 로깅 및 모니터링 엔드포인트 보강

`apps/spring-api/src/main/resources/application.yaml`에 Actuator 노출 및 세부 로깅 레벨을 적용했습니다.

### 3.1 Actuator 엔드포인트
- `GET /actuator/health`: 서비스 헬스 상태
- `GET /actuator/info`: 앱 메타데이터
- `GET /actuator/metrics`: JVM, HTTP, 스레드 풀 메트릭
- `GET /actuator/prometheus`: Prometheus 스크랩용 메트릭

### 3.2 로깅 레벨
- `com.yrootlab.onmaru`: `DEBUG` (개발 및 디버깅 가시성 확보)
- `org.springdoc`: `INFO` (OpenAPI 스캐닝 로그)
- `org.springframework.web`: `INFO` (요청 라우팅)
